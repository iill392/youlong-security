package com.youlong.hd;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 病毒特征库后台循环检测（2026-10-24 新增，用户需求）
 * ==========================================================================
 * 需求：内置特权的病毒库要一直在后台循环——每 3 分钟拉一次库，然后扫描已安
 * 装应用；发现与特征库匹配的应用就弹窗「XX 高风险应用，建议立即卸载」，
 * 给「立即卸载 / 稍后处理」两个选项。
 *
 * 性能（2026-10-24 晚追加，用户反馈「检测好慢」）：
 *   · 内容检测只做「可能命中」的工作：整包哈希先按样本 file_size 过滤，库里
 *     没有 DEX 样本就不逐个解压哈希（见 VirusContentDb）；
 *   · 判定结果落盘（virus_verdict_cache），进程重启/下一轮直接复用；
 *   · 一轮扫描用 2–3 个线程并行（每个应用仍是独立 ZipFile，线程安全）；
 *   · 新装/更新应用由 VirusInstallReceiver 立即扫单个包，不必等 3 分钟轮询；
 *   · 单轮预算 45 秒，扫不完记游标下轮继续。
 * ==========================================================================
 */
public final class VirusAutoScanner {

    private static final String TAG = "VirusAutoScanner";

    private static final String PREF = "virus_auto_scan";
    private static final String K_ALERTED_PREFIX = "alerted_";

    /** 通知渠道（全屏通知兜底用）。 */
    private static final String CHANNEL_ID = "virus_db_alert";

    /** 单轮扫描预算：超时就停，下一轮从游标处继续。 */
    private static final long SCAN_BUDGET_MS = 45000L;

    /** 安装后单包扫描预算（只扫一个应用，给宽一点）。 */
    private static final long SINGLE_SCAN_BUDGET_MS = 15000L;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    /** 下一轮从这里开始（安装列表可能很长，避免总扫前几个大应用）。 */
    private static final AtomicInteger CURSOR = new AtomicInteger(0);
    /** 弹窗/去重的串行锁：全量扫描与安装广播可能同时命中同一个包。 */
    private static final Object ALERT_LOCK = new Object();

    private VirusAutoScanner() {}

    // ======================================================================
    // 全量扫描（3 分钟循环）
    // ======================================================================

    /**
     * 执行一轮后台检测（阻塞，必须在后台线程调用）。
     *
     * <p>由 {@link VirusContentDb} 的 3 分钟定时任务在刷新特征库之后调用；
     * 也可在其它后台任务里手动调用（单飞，重复调用会被忽略）。
     */
    public static void scanOnce(final Context ctx) {
        if (ctx == null) return;
        if (!RUNNING.compareAndSet(false, true)) {
            Log.d(TAG, "上一轮检测还没结束，跳过本次");
            return;
        }
        final Context app = ctx.getApplicationContext() != null
                ? ctx.getApplicationContext() : ctx;
        try {
            if (!VirusContentDb.isReady(app)) {
                Log.d(TAG, "特征库未就绪，本轮跳过");
                return;
            }

            final PackageManager pm = app.getPackageManager();
            List<ApplicationInfo> installed = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            if (installed == null || installed.isEmpty()) return;

            final List<ApplicationInfo> candidates = new ArrayList<>();
            for (ApplicationInfo ai : installed) {
                if (ai == null || ai.packageName == null) continue;
                if (shouldSkip(app, ai)) continue;
                candidates.add(ai);
            }
            if (candidates.isEmpty()) return;

            final int size = candidates.size();
            final int start = Math.floorMod(CURSOR.get(), size);
            final long deadline = System.currentTimeMillis() + SCAN_BUDGET_MS;

            final AtomicInteger next = new AtomicInteger(0);
            final AtomicReference<Hit> found = new AtomicReference<>();
            final AtomicBoolean timedOut = new AtomicBoolean(false);

            final int workers = Math.min(3,
                    Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
            Thread[] threads = new Thread[workers];
            for (int w = 0; w < workers; w++) {
                threads[w] = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        while (found.get() == null) {
                            int i = next.getAndIncrement();
                            if (i >= size) return;
                            if (System.currentTimeMillis() > deadline) {
                                timedOut.set(true);
                                return;
                            }
                            int idx = (start + i) % size;
                            ApplicationInfo ai = candidates.get(idx);
                            if (ai == null || ai.packageName == null) continue;
                            String label = labelOf(pm, ai);
                            String reason = matchReason(app, ai.packageName, label, deadline);
                            if (reason != null) {
                                found.compareAndSet(null,
                                        new Hit(ai.packageName, label, reason, idx));
                                return;
                            }
                        }
                    }
                }, "VirusScan-" + w);
                threads[w].start();
            }

            for (Thread t : threads) {
                try {
                    t.join(90000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }

            Hit hit = found.get();
            if (hit != null) {
                CURSOR.set((start + hit.index + 1) % size);
                synchronized (ALERT_LOCK) {
                    if (markAlerted(app, hit.pkg)) {
                        VirusContentDb.onPackageMatched(app, hit.pkg);
                        Log.w(TAG, "发现高风险应用: " + hit.pkg + "（" + hit.label + "）→ " + hit.reason);
                        // V5：自动扫描不再拉 VirusAlertActivity 弹窗。
                        // 弹出提示统一交给 ProtectService 的自动拦截，避免两套弹窗冲突。
                    }
                }
                return;
            }

            if (timedOut.get()) {
                int done = Math.min(next.get(), size);
                CURSOR.set((start + done) % size);
                Log.i(TAG, "本轮检测达到时间预算，已检测 " + done + " 个，下轮继续");
            } else {
                CURSOR.set(0);
            }
        } catch (Throwable tr) {
            Log.w(TAG, "后台病毒库检测异常（下轮继续）", tr);
        } finally {
            RUNNING.set(false);
        }
    }

    // ======================================================================
    // 新装/更新应用的单包扫描（安装广播触发）
    // ======================================================================

    /**
     * 只检测一个包（新装/更新时调用），命中就弹窗。
     * 不必等 3 分钟全量轮询；也不受全量扫描的游标影响。
     */
    public static void scanPackage(final Context ctx, final String pkg) {
        if (ctx == null || pkg == null || pkg.isEmpty()) return;
        final Context app = ctx.getApplicationContext() != null
                ? ctx.getApplicationContext() : ctx;
        try {
            if (!VirusContentDb.isReady(app)) return;

            PackageManager pm = app.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if (shouldSkip(app, ai)) return;

            long deadline = System.currentTimeMillis() + SINGLE_SCAN_BUDGET_MS;
            String label = labelOf(pm, ai);
            String reason = matchReason(app, pkg, label, deadline);
            if (reason == null) {
                // 应用更新后不再命中：从内置特权黑名单里移除（下次快照重推）
                VirusContentDb.removeMatchedPackage(app, pkg);
                return;
            }
            synchronized (ALERT_LOCK) {
                if (markAlerted(app, pkg)) {
                    VirusContentDb.onPackageMatched(app, pkg);
                    Log.w(TAG, "新装应用命中黑名单: " + pkg + "（" + label + "）→ " + reason);
                    // V5：不再弹 VirusAlertActivity；自动拦截统一由 ProtectService 处理。
                }
            }
        } catch (Throwable tr) {
            Log.w(TAG, "单包检测异常: " + pkg, tr);
        }
    }

    /** 一个命中结果。 */
    private static final class Hit {
        final String pkg;
        final String label;
        final String reason;
        /** 在 candidates 里的绝对下标（用于推进游标）。 */
        final int index;

        Hit(String pkg, String label, String reason, int index) {
            this.pkg = pkg;
            this.label = label;
            this.reason = reason;
            this.index = index;
        }
    }

    // ======================================================================
    // 判定
    // ======================================================================

    /** @return 命中原因；未命中返回 null */
    private static String matchReason(Context ctx, String pkg, String label, long deadline) {
        // 2026-10-25：黑名单只来自 safe1/safe2（包名）+ mingcheng1/2（名称）
        try {
            VirusDb.Data db = VirusDb.get(ctx);
            if (db != null && db.isBlacklisted(pkg, label)) {
                return "命中黑名单（safe1/safe2/名称库）";
            }
        } catch (Throwable tr) {
            Log.w(TAG, "黑名单比对失败: " + pkg, tr);
        }
        return null;
    }

    private static boolean shouldSkip(Context ctx, ApplicationInfo ai) {
        String pkg = ai.packageName;
        if (pkg.equals(ctx.getPackageName())) return true;
        if ("com.youlong.tool".equals(pkg) || "com.youlong.zoo".equals(pkg)) return true;
        // 纯系统应用（无更新版）：不可卸载，扫出来也无从处理
        return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
    }

    private static String labelOf(PackageManager pm, ApplicationInfo ai) {
        try {
            CharSequence cs = pm.getApplicationLabel(ai);
            if (cs != null) {
                String s = cs.toString().trim();
                if (!s.isEmpty()) return s;
            }
        } catch (Throwable ignored) {}
        return ai.packageName;
    }

    // ======================================================================
    // 去重（同一应用同一版本只提示一次）
    // ======================================================================

    private static boolean markAlerted(Context ctx, String pkg) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String current = versionTag(ctx, pkg);
            String old = sp.getString(K_ALERTED_PREFIX + pkg, "");
            if (current.equals(old)) return false;
            sp.edit().putString(K_ALERTED_PREFIX + pkg, current).apply();
            return true;
        } catch (Throwable tr) {
            Log.w(TAG, "记录提示状态失败: " + pkg, tr);
            return true;
        }
    }

    private static String versionTag(Context ctx, String pkg) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(pkg, 0);
            return pi.versionCode + "|" + pi.lastUpdateTime;
        } catch (Throwable ignored) {
            return "0|0";
        }
    }

    // ======================================================================
    // V5：自动扫描不再拉 VirusAlertActivity 弹窗，只保留通知兜底
    // ======================================================================

    /**
     * 自动扫描命中时的通知兜底。
     * 不再启动 VirusAlertActivity（那个“检测到高风险应用 / 立即卸载 / 稍后处理”
     * 弹窗与 ProtectService 的自动拦截冲突）；这里只发普通高优先级通知。
     */
    public static void showNotificationOnly(Context ctx, String title, String text) {
        try {
            ensureChannel(ctx);
            Intent intent = new Intent(ctx, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                piFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(ctx, 7999, intent, piFlags);
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setTimeoutAfter(60000L);
            NotificationManagerCompat.from(ctx).notify(7999, b.build());
        } catch (Throwable tr) {
            Log.w(TAG, "发自动拦截通知失败", tr);
        }
    }

    private static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.virus_alert_channel_name),
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription(ctx.getString(R.string.virus_alert_channel_desc));
            ch.setShowBadge(true);
            nm.createNotificationChannel(ch);
        } catch (Throwable tr) {
            Log.w(TAG, "创建通知渠道失败", tr);
        }
    }
}
