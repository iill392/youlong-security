package com.youlong.hd;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 手动扫描（扫描页「立即扫描」按钮调用）
 *
 * ==========================================================================
 * 需求（2026-10，用户指定）
 * --------------------------------------------------------------------------
 * 顶部导航新增「扫描」页：点「立即扫描」→ 把手机里已安装的应用逐个拿到
 * 病毒库里比对，结果分两档：
 *
 *   ① 100% 病毒 —— safe1.json（包名） + mingcheng1.json / mian1.json（应用名）
 *   ② 可能病毒  —— safe2.json（包名） + mingcheng2.json / mian2.json（应用名）
 *                  + key.json（关键字，包含匹配）
 *
 * 两条链路的判定逻辑与后台自动拦截完全一致（都走 {@link VirusDb.Data}），
 * 所以手动扫描看到的结果与自动拦截命中的结果不会互相矛盾。
 *
 * 应用清单来源（两路合并，避免国内 ROM 的包可见性限制导致漏扫）：
 *   1) {@code getInstalledApplications()} —— 有 QUERY_ALL_PACKAGES / 读取应用列表
 *      权限时最全，能扫到不显示图标的隐藏应用；
 *   2) {@code queryIntentActivities(MAIN + LAUNCHER)} —— Android 11+ 不需要任何
 *      权限就能拿到所有有桌面的应用，用来补齐第 1 路被系统过滤掉的条目。
 *   两路按包名去重后再逐条比对。
 *
 * ⚠️ 本类只做「只读扫描」，不卸载、不改任何配置：
 *    卸载由 MainActivity 的 JS 桥 uninstallVirusApp() 执行，
 *    这样扫描页本身不会因为误触而直接删应用。
 *
 * ==========================================================================
 * 2026-10-24 新增：内容级特征库（virus-db.json）检测
 * --------------------------------------------------------------------------
 * 第一轮仍是老的「包名 / 名称」快查（{@link VirusDb}）；没命中的应用再做一次
 * APK 内容检测（{@link VirusContentDb}）：签名证书 / 整包哈希 / DEX 哈希 /
 * 资产哈希 / 高特异 token / 弱信号 —— 专门抓「换包名 + 重签名」的克隆体。
 *   · BLOCK → 归入 certain（why 用特征库给出的中文原因）
 *   · WARN  → 归入 suspect
 *   · 整个过程有 8 秒总预算，超时停止并标记 contentTruncated，绝不卡死手动扫描
 *   · 单个应用异常只记日志、继续下一个
 * ==========================================================================
 */
public final class VirusScanner {

    private static final String TAG = "VirusScanner";

    /**
     * 2026-10-24 新增：内容级特征库检测的总预算（毫秒）。
     *
     * <p>手动扫描是在 WebView 的 JS 桥里同步调用的，逐个 APK 读证书/哈希/token
     * 在真机上很慢，必须给一个硬上限：超时立即停止并标记 truncated。
     */
    private static final long CONTENT_SCAN_BUDGET_MS = 8000L;

    private VirusScanner() {}

    /**
     * 扫描是否就绪（病毒库是否已有数据）。
     * 返回给网页用于显示「病毒库同步中…」或「立即扫描」可点状态。
     */
    public static String status(Context ctx) {
        try {
            VirusDb.Data db = VirusDb.get(ctx);
            JSONObject o = new JSONObject();
            o.put("ready", !db.isEmpty());
            o.put("total", db.total());
            o.put("refreshing", VirusDb.isRefreshing());
            o.put("reachable", VirusDb.isServerReachable());
            o.put("lastSuccess", VirusDb.getLastSuccess());
            o.put("fetchTime", VirusDb.getCachedFetchTime());
            // 2026-10-24 新增：内容级特征库（virus-db.json）状态 —— 只新增字段，
            // 老字段（ready/total/refreshing/...）保持原样，网页兼容不受影响
            o.put("contentReady", safeContentReady(ctx));
            o.put("contentVersion", safeContentVersion(ctx));
            o.put("contentChecked", 0);
            o.put("contentTruncated", false);
            // 2026-10-24 新增：病毒特征库定时拉取状态（主进程运行时每 3 分钟一次）
            o.put("contentPeriodic", safePeriodicStarted());
            o.put("contentPollIntervalMs", safePollIntervalMs());
            o.put("contentPollCount", safePollCount());
            o.put("contentLastPoll", safeLastPollAt());
            return o.toString();
        } catch (Throwable tr) {
            Log.e(TAG, "status error", tr);
            return "{\"ready\":false,\"total\":0,\"refreshing\":false,"
                    + "\"contentReady\":false,\"contentVersion\":\"\","
                    + "\"contentChecked\":0,\"contentTruncated\":false}";
        }
    }

    /**
     * 执行一次全量扫描。
     *
     * <p>同步方法，由 WebView 的 JS 桥直接调用；包名/名称比对是毫秒级的，
     * 内容级检测（2026-10-24 新增）另有 8 秒总预算兜底，所以最坏也就 8 秒左右，
     * 不会把扫描页卡死（超时会在 JSON 里标记 contentTruncated=true）。
     * 病毒库为空时立即触发一次后台拉取，并返回 ready=false，让网页稍后重试。
     */
    public static String scan(Context ctx) {
        long started = System.currentTimeMillis();
        try {
            VirusDb.Data db = VirusDb.get(ctx);

            // 2026-10-24 新增：内容特征库后台同步（自带 30 秒节流 + 单飞，
            // 不会阻塞本方法；未就绪时本次仍可只做包名/名称比对）
            try { VirusDb.refreshAsync(ctx); } catch (Throwable ignored) {}

            if (db.isEmpty()) {
                // 本地还没数据 → 后台拉一次，本次先返回未就绪
                VirusDb.refreshAsync(ctx);
                JSONObject o = new JSONObject();
                o.put("ready", false);
                o.put("dbTotal", 0);
                o.put("scanned", 0);
                o.put("certain", new JSONArray());
                o.put("suspect", new JSONArray());
                // 2026-10-24 新增：内容级检测字段（本次未扫描，字段保持完整）
                o.put("contentReady", safeContentReady(ctx));
                o.put("contentVersion", safeContentVersion(ctx));
                o.put("contentChecked", 0);
                o.put("contentTruncated", false);
                return o.toString();
            }

            PackageManager pm = ctx.getPackageManager();
            Map<String, AppEntry> apps = collectInstalledApps(ctx, pm);

            final List<Hit> certain = new ArrayList<>();
            final List<Hit> suspect = new ArrayList<>();
            int scanned = 0;

            for (AppEntry e : apps.values()) {
                if (e == null || e.pkg == null || e.pkg.isEmpty()) continue;
                if (e.pkg.equals(ctx.getPackageName())) continue;
                // 自家配套应用（桌面宠物 / 游龙工具）不参与扫描，避免误报导致用户
                // 把自家应用当成病毒卸载（与后台自动拦截的受保护清单保持一致）
                if ("com.youlong.zoo".equals(e.pkg) || "com.youlong.tool".equals(e.pkg)) continue;
                // 纯系统应用：无法 pm uninstall，扫出来也让用户无从处理，直接跳过
                if (e.system) continue;

                String label = e.label == null || e.label.isEmpty() ? e.pkg : e.label;
                scanned++;
                try {
                    if (db.isCertain(e.pkg, label)) {
                        certain.add(new Hit(e.pkg, label, "病毒", true));
                    } else {
                        String why = db.matchSuspect(e.pkg, label);
                        if (why != null) {
                            suspect.add(new Hit(e.pkg, label, why, false));
                        }
                    }
                } catch (Throwable tr) {
                    Log.w(TAG, "比对失败: " + e.pkg, tr);
                }
            }

            // ==============================================================
            // 2026-10-24 新增：内容级特征库检测（virus-db.json）
            // --------------------------------------------------------------
            // 第一轮（包名 / 名称）没命中的应用，再做一次 APK 内容检测：
            //   证书 → 整包哈希 → DEX 哈希 → 资产哈希 → 高特异 token → 弱信号
            // 只有「换包名 + 重签名」的克隆体才会在这里被抓到（原版安装包
            // mio.TerminalDeity.Ultra 已在 safe1.json 里，第一轮就命中了）。
            //
            // 性能约束：真机上逐个 APK 读文件很慢，所以整个过程有 8 秒总预算；
            // 超时立即停止并标记 contentTruncated=true，绝不把手动扫描卡死。
            // ==============================================================
            // 2026-10-25（用户要求换思路）：内容特征分析已停用。
            // 黑名单只看 safe1/safe2（包名）+ mingcheng1/2（应用名称）。
            final boolean contentReady = false;
            final String contentVersion = "";
            final int contentChecked = 0;
            final boolean contentTruncated = false;

            // 100% 病毒按名称排序，便于用户快速浏览；可能病毒同理
            Comparator<Hit> byName = new Comparator<Hit>() {
                @Override
                public int compare(Hit a, Hit b) {
                    return a.name.compareToIgnoreCase(b.name);
                }
            };
            Collections.sort(certain, byName);
            Collections.sort(suspect, byName);

            // 2026-10-25：本轮命中的所有黑名单应用都记录到内置特权，
            // 服务端会把它们的全部命令拦掉（safe1/safe2/名称库任意一路命中）。
            try {
                java.util.List<String> matched = new ArrayList<>();
                for (int i = 0; i < certain.size(); i++) matched.add(certain.get(i).pkg);
                for (int i = 0; i < suspect.size(); i++) matched.add(suspect.get(i).pkg);
                VirusContentDb.onPackagesMatched(ctx, matched);
            } catch (Throwable tr) {
                Log.w(TAG, "记录内置特权黑名单失败", tr);
            }

            JSONObject o = new JSONObject();
            o.put("ready", true);
            o.put("dbTotal", db.total());
            o.put("scanned", scanned);
            o.put("elapsed", System.currentTimeMillis() - started);
            o.put("certain", toJson(certain));
            o.put("suspect", toJson(suspect));
            // 2026-10-24 新增：内容级检测统计（老字段保持不变）
            o.put("contentReady", contentReady);
            o.put("contentVersion", contentVersion);
            o.put("contentChecked", contentChecked);
            o.put("contentTruncated", contentTruncated);
            Log.i(TAG, "扫描完成: 应用=" + scanned + " 100%病毒=" + certain.size()
                    + " 可疑=" + suspect.size()
                    + " 内容检测=" + contentChecked + (contentTruncated ? "(截断)" : "")
                    + " 耗时=" + (System.currentTimeMillis() - started) + "ms");
            return o.toString();
        } catch (Throwable tr) {
            Log.e(TAG, "scan error", tr);
            try {
                JSONObject o = new JSONObject();
                o.put("ready", false);
                o.put("error", tr.getClass().getSimpleName());
                o.put("certain", new JSONArray());
                o.put("suspect", new JSONArray());
                o.put("contentReady", false);
                o.put("contentVersion", "");
                o.put("contentChecked", 0);
                o.put("contentTruncated", false);
                return o.toString();
            } catch (Throwable ignored) {
                return "{\"ready\":false,\"certain\":[],\"suspect\":[],"
                        + "\"contentReady\":false,\"contentVersion\":\"\","
                        + "\"contentChecked\":0,\"contentTruncated\":false}";
            }
        }
    }

    // ======================================================================
    // 2026-10-24 新增：内容特征库状态（异常一律按「未就绪/空版本」处理）
    // ======================================================================
    /** 内容特征库是否就绪（2026-10-25：内容分析已停用，恒 false） */
    private static boolean safeContentReady(Context ctx) {
        return false;
    }

    /** 内容特征库版本（已停用，恒空） */
    private static String safeContentVersion(Context ctx) {
        return "";
    }

    /** 病毒特征库定时拉取是否已启动 */
    private static boolean safePeriodicStarted() {
        try {
            return VirusContentDb.isPeriodicRefreshStarted();
        } catch (Throwable tr) {
            return false;
        }
    }

    private static long safePollIntervalMs() {
        try {
            return VirusContentDb.getPollIntervalMs();
        } catch (Throwable tr) {
            return 0L;
        }
    }

    private static int safePollCount() {
        try {
            return VirusContentDb.getPollCount();
        } catch (Throwable tr) {
            return 0;
        }
    }

    private static long safeLastPollAt() {
        try {
            return VirusContentDb.getLastPollAt();
        } catch (Throwable tr) {
            return 0L;
        }
    }

    // ======================================================================
    // 应用清单：两路合并（installedApplications + launcher 查询）
    // ======================================================================
    private static Map<String, AppEntry> collectInstalledApps(Context ctx, PackageManager pm) {
        LinkedHashMap<String, AppEntry> map = new LinkedHashMap<>();

        // ① 已安装应用（有权限时最全，含无桌面图标的隐藏应用）
        try {
            List<ApplicationInfo> installed = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            if (installed != null) {
                for (ApplicationInfo ai : installed) {
                    if (ai == null || ai.packageName == null) continue;
                    String label = "";
                    try {
                        CharSequence cs = pm.getApplicationLabel(ai);
                        if (cs != null) label = cs.toString().trim();
                    } catch (Throwable ignored) {}
                    map.put(ai.packageName, new AppEntry(ai.packageName, label, ai));
                }
            }
        } catch (Throwable tr) {
            Log.w(TAG, "getInstalledApplications 失败", tr);
        }

        // ② 桌面可见应用（Android 11+ 无需权限；补齐被包可见性过滤掉的条目）
        try {
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
            if (ris != null) {
                for (ResolveInfo ri : ris) {
                    if (ri == null || ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (pkg == null || map.containsKey(pkg)) continue;
                    String label = "";
                    try {
                        CharSequence cs = ri.loadLabel(pm);
                        if (cs != null) label = cs.toString().trim();
                    } catch (Throwable ignored) {}
                    ApplicationInfo ai = ri.activityInfo.applicationInfo;
                    map.put(pkg, new AppEntry(pkg, label, ai));
                }
            }
        } catch (Throwable tr) {
            Log.w(TAG, "queryIntentActivities 失败", tr);
        }

        return map;
    }

    private static JSONArray toJson(List<Hit> hits) throws Exception {
        JSONArray arr = new JSONArray();
        for (Hit h : hits) {
            JSONObject o = new JSONObject();
            o.put("pkg", h.pkg);
            o.put("name", h.name);
            o.put("why", h.why);
            o.put("certain", h.certain);
            arr.put(o);
        }
        return arr;
    }

    /** 扫描用的应用条目 */
    private static final class AppEntry {
        final String pkg;
        final String label;
        /** 纯系统应用（无更新版）→ 不可卸载，扫描时跳过 */
        final boolean system;
        final int uid;

        AppEntry(String pkg, String label, ApplicationInfo ai) {
            this.pkg = pkg;
            this.label = label;
            boolean sys = false;
            int u = -1;
            if (ai != null) {
                sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                u = ai.uid;
            }
            this.system = sys;
            this.uid = u;
        }
    }

    private static final class Hit {
        final String pkg;
        final String name;
        final String why;
        final boolean certain;

        Hit(String pkg, String name, String why, boolean certain) {
            this.pkg = pkg;
            this.name = name;
            this.why = why;
            this.certain = certain;
        }
    }
}
