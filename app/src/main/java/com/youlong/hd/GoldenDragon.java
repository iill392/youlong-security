package com.youlong.hd;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import roro.stellar.Stellar;

/**
 * 金龙引擎（2026-10，用户需求）
 * ==========================================================================
 * 一句话需求
 * --------------------------------------------------------------------------
 * 在「自动拦截」页加一个「金龙引擎」开关，默认开启。
 * 开启后：只要检测到有软件在修改系统音量，就通过内置特权立刻执行
 * 「停止所有第三方指令调用内置特权」。
 * 未连接内置特权时开关自动关闭；连接后自动开启。
 *
 * 实现拆解
 * --------------------------------------------------------------------------
 * 1) 开关状态（持久化在 shield_prefs）
 *      golden_dragon_on        当前开关（默认 true）
 *      golden_dragon_auto_off  是否「因为没连特权而被自动关掉」
 *      → {@link #sync(Context)} 每次被页面/服务调用时做一次自动开/关：
 *         · 未连接特权 → 自动关（记 auto_off=true）
 *         · 已连接特权且是被自动关的 → 自动开（清 auto_off）
 *         · 用户手动关闭 → auto_off=false，重连后不会被强行打开（尊重用户手选）
 *
 * 2) 检测（ProtectService 里做，见 onVolumeStreamChanged）
 *      内置特权的音量回调 + 120ms 轮询会在「音频流音量发生变化」时通知这里；
 *      ProtectService 会排除「用户刚按过物理音量键」的情况，只把
 *      第三方应用在前台时的异常音量改动交给金龙引擎。
 *
 * 3) 处置（{@link #neutralize}）
 *      · 若篡改音量的应用本身在用内置特权（声明了 Stellar/Shizuku 接入）→
 *        先 am force-stop 它（通过内置特权执行，无需 root）；
 *      · 撤销**所有第三方应用**的内置特权（stellar / follow_stellar_startup /
 *        shizuku 三条权限全部置为 DENIED）。Stellar 服务端在撤销
 *        runtime 权限时会对已授权应用执行 forceStopPackage（见
 *        PermissionManager.updateFlagForUid），因此这一步等价于
 *        「停止所有第三方指令调用内置特权」——
 *        所有已授权/正在使用特权的第三方应用会被立即停掉，之后的指令调用
 *        会被服务端直接拒绝。
 *
 * ⚠️ 权限撤销只处理「声明过 Stellar / Shizuku 接入」的第三方应用
 *    （meta-data 或 provider 任一命中）：只有这类应用才可能拿到特权 Binder
 *    （见 BinderSender），对普通应用逐个写配置既慢又毫无意义。
 * ==========================================================================
 */
public final class GoldenDragon {

    private static final String TAG = "GoldenDragon";
    private static final String PREF = "shield_prefs";

    /** 当前开关（默认开启） */
    public static final String KEY_ON = "golden_dragon_on";
    /** 是否因「未连接内置特权」被自动关闭 */
    public static final String KEY_AUTO_OFF = "golden_dragon_auto_off";
    /** 最近一次处置的文案（页面展示用） */
    public static final String KEY_LAST_ACTION = "golden_dragon_last_action";
    /** 最近一次处置的时间 */
    public static final String KEY_LAST_ACTION_TIME = "golden_dragon_last_action_time";

    // ===== Stellar 权限常量（与 embedded/shared/.../StellarApiConstants.kt、
    //       embedded/server/.../ConfigManager.kt 保持一致）=====
    private static final String PERM_STELLAR = "stellar";
    private static final String PERM_FOLLOW_STARTUP = "follow_stellar_startup";
    private static final String PERM_SHIZUKU = "shizuku";
    /** ConfigManager.FLAG_DENIED */
    private static final int STELLAR_FLAG_DENIED = 2;
    /** Shizuku 侧 FLAG_DENIED（0=ask 2=granted 4=denied，见管理器 AppsScreen） */
    private static final int SHIZUKU_FLAG_DENIED = 4;

    private GoldenDragon() {}

    /** 内置特权（Stellar）是否已连接且已授权 */
    public static boolean isPrivilegeReady() {
        try {
            return StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission();
        } catch (Throwable tr) {
            return false;
        }
    }

    /** 读取持久化的开关值（不判断特权是否连接） */
    public static boolean isSwitchOn(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getBoolean(KEY_ON, true);
        } catch (Throwable tr) {
            return true;
        }
    }

    /** 真正生效（开关打开 + 特权已连接），供 ProtectService 每轮判断 */
    public static boolean isActive(Context ctx) {
        return isSwitchOn(ctx) && isPrivilegeReady();
    }

    /**
     * 与内置特权连接状态自动同步，并在需要时切换开关。
     *
     * @return JSON {"enabled":bool,"stellarReady":bool}（给网页直接展示）
     */
    public static JSONObject sync(Context ctx) {
        boolean ready = isPrivilegeReady();
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        boolean on = sp.getBoolean(KEY_ON, true);
        boolean autoOff = sp.getBoolean(KEY_AUTO_OFF, false);

        if (!ready && on) {
            // 未连接内置特权 → 自动关闭（并记住这是自动关的）
            sp.edit().putBoolean(KEY_ON, false).putBoolean(KEY_AUTO_OFF, true).apply();
            on = false;
            Log.i(TAG, "未连接内置特权，金龙引擎已自动关闭");
        } else if (ready && !on && autoOff) {
            // 连接内置特权 → 自动开启
            sp.edit().putBoolean(KEY_ON, true).putBoolean(KEY_AUTO_OFF, false).apply();
            on = true;
            Log.i(TAG, "已连接内置特权，金龙引擎已自动开启");
        }

        JSONObject o = new JSONObject();
        try {
            o.put("enabled", on && ready);
            o.put("stellarReady", ready);
            o.put("lastAction", sp.getString(KEY_LAST_ACTION, ""));
            o.put("lastActionTime", sp.getLong(KEY_LAST_ACTION_TIME, 0L));
        } catch (Throwable ignored) {}
        return o;
    }

    /**
     * 用户手动切换开关。
     *
     * @return 实际生效值；开启时若未连接内置特权会返回 false（并保持关闭）
     */
    public static boolean setUserEnabled(Context ctx, boolean on) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (on && !isPrivilegeReady()) {
            sp.edit().putBoolean(KEY_ON, false).putBoolean(KEY_AUTO_OFF, false).apply();
            return false;
        }
        // 手动操作即视为用户意图：清掉 auto_off，避免重连后被强行改回
        sp.edit().putBoolean(KEY_ON, on).putBoolean(KEY_AUTO_OFF, false).apply();
        Log.i(TAG, "用户手动" + (on ? "开启" : "关闭") + "金龙引擎");
        return on;
    }

    /**
     * 停止所有第三方应用对内置特权的指令调用。
     *
     * <p>做法：把声明过 Stellar / Shizuku 接入的第三方应用（外加本次触发处置的
     * 那个应用）的特权权限全部置为 DENIED。
     * Stellar 服务端在撤销 runtime 权限时会对已授权应用执行 forceStopPackage，
     * 因此已连接的应用会立刻被停掉，后续 newProcess 等调用会被直接拒绝。
     *
     * <p>耗时的 binder 操作，必须在后台线程调用。
     *
     * @param ctx         上下文
     * @param extraTarget 额外要撤销的应用（检测到篡改音量的那个，可为 null）
     * @return 实际处理的应用数量
     */
    public static int stopAllThirdPartyPrivilegeCalls(Context ctx, String extraTarget) {
        if (!isPrivilegeReady()) return 0;
        int handled = 0;
        try {
            PackageManager pm = ctx.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            if (apps == null) apps = new ArrayList<>();
            int selfUid = Process.myUid();
            java.util.HashSet<Integer> seenUids = new java.util.HashSet<>();
            for (ApplicationInfo ai : apps) {
                if (ai == null || ai.packageName == null) continue;
                if (ai.packageName.equals(ctx.getPackageName())) continue;
                if (ai.uid == selfUid) continue;
                // 只处理第三方应用（系统应用不通过本应用的特权调用命令）
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
                    continue;
                }
                boolean isTarget = extraTarget != null && extraTarget.equals(ai.packageName);
                if (!isTarget && !declaresPrivilegeAccess(pm, ai)) continue;
                // 同一 UID 的多个包只处理一次（省掉重复 binder 事务）
                if (!seenUids.add(ai.uid)) continue;
                try {
                    Stellar.INSTANCE.updateFlagForUid(ai.uid, PERM_STELLAR, STELLAR_FLAG_DENIED);
                    Stellar.INSTANCE.updateFlagForUid(ai.uid, PERM_FOLLOW_STARTUP, STELLAR_FLAG_DENIED);
                    Stellar.INSTANCE.updateFlagForUid(ai.uid, PERM_SHIZUKU, SHIZUKU_FLAG_DENIED);
                    handled++;
                    Log.w(TAG, "已停止第三方特权调用: " + ai.packageName + " (uid=" + ai.uid + ")");
                } catch (Throwable tr) {
                    Log.w(TAG, "撤销特权失败: " + ai.packageName, tr);
                }
            }
        } catch (Throwable tr) {
            Log.e(TAG, "stopAllThirdPartyPrivilegeCalls error", tr);
        }
        return handled;
    }

    /**
     * 应用是否声明过 Stellar / Shizuku 接入（只有这类应用才可能拿到特权 Binder）。
     *
     * <p>两条判定路径对应 Stellar 的 BinderSender：
     * meta-data（roro.stellar.permissions / moe.shizuku.client.V3_SUPPORT）
     * 或 provider（StellarProvider / ShizukuProvider）。
     */
    private static boolean declaresPrivilegeAccess(PackageManager pm, ApplicationInfo ai) {
        try {
            android.os.Bundle meta = ai.metaData;
            if (meta != null) {
                String perms = meta.getString("roro.stellar.permissions", "");
                if (perms != null && perms.contains(PERM_STELLAR)) return true;
                if (meta.getBoolean("moe.shizuku.client.V3_SUPPORT", false)) return true;
                String v3 = meta.getString("moe.shizuku.client.V3_SUPPORT");
                if (v3 != null && "true".equalsIgnoreCase(v3)) return true;
            }
            // provider 接入方式（BinderSender 的 hasStellarProvider / hasShizukuProvider）
            PackageInfo pi = pm.getPackageInfo(ai.packageName, PackageManager.GET_PROVIDERS);
            if (pi != null && pi.providers != null) {
                for (android.content.pm.ProviderInfo p : pi.providers) {
                    if (p == null || p.name == null) continue;
                    if ("roro.stellar.StellarProvider".equals(p.name)
                            || "moe.shizuku.provider.ShizukuProvider".equals(p.name)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Throwable tr) {
            return false;
        }
    }

    /**
     * 金龙引擎处置：停止所有第三方对内置特权的指令调用。
     *
     * <p>如果篡改音量的应用本身声明过 Stellar / Shizuku 接入（即它可能正在使用
     * 内置特权），先 force-stop 它再撤销全部第三方特权；普通应用只撤销特权，
     * 不做 force-stop —— 避免「后台病毒改音量、前台无辜应用被误杀」。
     *
     * <p>耗时（binder / shell），必须在后台线程调用。
     */
    public static Result neutralize(Context ctx, String pkg, String streamName, int from, int to) {
        Result r = new Result();
        if (!isPrivilegeReady()) return r;
        r.pkg = pkg;

        // ① 篡改者确实在调用内置特权 → 先停掉它，避免撤销过程中继续作恶
        boolean targetUsesPrivilege = isDeclaredPrivilegeApp(ctx, pkg);
        if (targetUsesPrivilege && pkg != null && !pkg.isEmpty()) {
            try {
                String out = StellarUtils.runCommand("am force-stop " + pkg, 12000);
                r.appStopped = out != null && !out.startsWith("ERROR:");
                Log.w(TAG, "金龙引擎 am force-stop " + pkg + " => " + out);
            } catch (Throwable tr) {
                Log.w(TAG, "金龙引擎 force-stop 失败: " + pkg, tr);
            }
        }

        // ② 停止所有第三方对内置特权的指令调用
        try {
            r.revokedCount = stopAllThirdPartyPrivilegeCalls(ctx, pkg);
        } catch (Throwable tr) {
            Log.w(TAG, "金龙引擎撤销特权失败", tr);
        }

        // ③ 记录最近一次动作（页面/通知展示用）
        String msg = "检测到「" + (pkg == null || pkg.isEmpty() ? "未知应用" : pkg)
                + "」修改" + (streamName == null ? "系统" : streamName)
                + "音量（" + from + "→" + to + "），已停止所有第三方调用内置特权";
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                    .putString(KEY_LAST_ACTION, msg)
                    .putLong(KEY_LAST_ACTION_TIME, System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) {}
        r.message = msg;
        return r;
    }

    /** 指定包名是否声明过 Stellar / Shizuku 接入 */
    private static boolean isDeclaredPrivilegeApp(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA);
            return declaresPrivilegeAccess(pm, ai);
        } catch (Throwable tr) {
            return false;
        }
    }

    /** 处置结果 */
    public static final class Result {
        public String pkg = "";
        public boolean appStopped = false;
        public int revokedCount = 0;
        public String message = "";
    }
}
