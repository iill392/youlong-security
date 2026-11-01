package com.youlong.hd;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import roro.stellar.Stellar;
import roro.stellar.StellarRemoteProcess;

/**
 * 自保 watchdog 启动器 + 异常关闭检测弹窗（2026-10-25）。
 *
 * 脚本 assets/self_defense.sh 负责：
 *   1. 自己被 disable → pm enable；
 *   2. 组件被 disable → 恢复；
 *   3. 无障碍被挤掉 → 恢复；
 *   4. 已知恶意包 → force-stop/uninstall；
 *   5. 检测异常关闭/冻结，采集前台包、事件日志、Shizuku 日志做证据归因；
 *      只要归因出嫌疑应用就反复冻结；系统/用户操作只恢复。
 *
 * 管理器包走 Stellar manager 豁免，脚本不会被自己的策略拦截。
 */
public final class SelfDefense {

    private static final String TAG = "SelfDefense";
    /** V30：watchdog 开关标记，写在外置目录，stellar_server 和脚本都能读。 */
    private static final String GUARD_FLAG_NAME = "self_defense_enabled";
    /** 2026-10-31：独立「自保模式」开关，默认关闭。 */
    private static final String PREF_SELF_DEFENSE = "self_defense_on";
    private static final AtomicBoolean STARTING = new AtomicBoolean(false);
    private static final AtomicBoolean IMPORTING_SYSTEM_WHITELIST = new AtomicBoolean(false);
    private static volatile StellarRemoteProcess process;
    private static volatile long lastStopCleanup = 0L;
    private static volatile long lastSystemWhitelistImport = 0L;

    private SelfDefense() {
    }

    /** 自保模式开关本身是否开启。 */
    public static boolean isSelfDefenseModeOn(Context context) {
        try {
            return context.getSharedPreferences("shield_prefs", Context.MODE_PRIVATE)
                    .getBoolean(PREF_SELF_DEFENSE, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * watchdog 是否允许运行：必须同时开启「安全守护」和「自保模式」。
     * 只开安全守护、没开自保模式时，不启动/不保留 watchdog。
     */
    public static boolean isGuardEnabled(Context context) {
        try {
            return context.getSharedPreferences("shield_prefs", Context.MODE_PRIVATE)
                    .getBoolean("protect_on", false)
                    && isSelfDefenseModeOn(context);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 用户切换「自保模式」后立即生效：开启时尝试启动，关闭时写 0 让脚本退出。 */
    public static void setSelfDefenseMode(Context context, boolean enabled) {
        try {
            context.getSharedPreferences("shield_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean(PREF_SELF_DEFENSE, enabled).apply();
        } catch (Throwable ignored) {
        }
        boolean active = isGuardEnabled(context);
        writeGuardFlag(context, active);
        if (enabled) {
            // 2026-11-01（用户需求）：开启自保检测时，把系统应用自动加入用户白名单。
            importSystemAppsToWhitelist(context, true);
        }
        if (enabled && active) {
            ensureRunning(context);
        } else if (!active) {
            // 关闭自保模式时除了写 0，还要清理旧 watchdog，避免旧脚本继续误冻结。
            stopOldWatchdogsIfDisabled(context);
        }
    }

    /**
     * 2026-11-01（用户需求）：通过 PackageManager 获取所有系统应用，
     * 自动合并进用户白名单（whitelist_pkgs），并同步到指令白名单。
     *
     * 这样即使某些系统应用被伪装/名称特殊，也会因为真实 FLAG_SYSTEM 被保护，
     * 自保脚本不会冻结它们。用户原有白名单保留。
     */
    public static void importSystemAppsToWhitelist(Context context) {
        importSystemAppsToWhitelist(context, false);
    }

    private static void importSystemAppsToWhitelist(Context context, boolean force) {
        if (!isSelfDefenseModeOn(context)) return;
        final long now = System.currentTimeMillis();
        if (!force && lastSystemWhitelistImport > 0L
                && now - lastSystemWhitelistImport < 10 * 60 * 1000L) {
            return;
        }
        if (!IMPORTING_SYSTEM_WHITELIST.compareAndSet(false, true)) return;
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                PackageManager pm = app.getPackageManager();
                Set<String> all = new LinkedHashSet<>();
                String raw = app.getSharedPreferences("shield_prefs", Context.MODE_PRIVATE)
                        .getString("whitelist_pkgs", "");
                if (raw != null && !raw.isEmpty()) {
                    for (String s : raw.split(",")) {
                        String t = s.trim();
                        if (!t.isEmpty()) all.add(t);
                    }
                }
                int before = all.size();
                for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                    if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0) continue;
                    String pkg = ai.packageName;
                    if (pkg != null && !pkg.isEmpty()) all.add(pkg);
                }
                int added = all.size() - before;
                if (added > 0 || force) {
                    StringBuilder sb = new StringBuilder();
                    for (String p : all) {
                        if (sb.length() > 0) sb.append(',');
                        sb.append(p);
                    }
                    app.getSharedPreferences("shield_prefs", Context.MODE_PRIVATE)
                            .edit().putString("whitelist_pkgs", sb.toString()).apply();
                    WhitelistActivity.syncToStellarAsync(app);
                    Log.i(TAG, "系统应用白名单导入完成: 新增 " + added + "，总计 " + all.size());
                }
                lastSystemWhitelistImport = now;
            } catch (Throwable tr) {
                Log.w(TAG, "导入系统应用白名单失败", tr);
            } finally {
                IMPORTING_SYSTEM_WHITELIST.set(false);
            }
        }, "SystemWhitelistImport").start();
    }

    /**
     * 2026-10-31：自保模式/安全守护关闭时，确保旧脚本进程退出。
     * 不仅写 flag，还主动 kill 旧 self_defense.sh 并清理自身可能残留的冻结记录。
     * 这样从旧版本升级、旧 watchdog 仍在跑时，也不会继续误冻结 com.youlong.hd。
     */
    public static void stopOldWatchdogsIfDisabled(Context context) {
        if (isGuardEnabled(context)) return;
        long now = System.currentTimeMillis();
        if (now - lastStopCleanup < 15_000L) return;
        lastStopCleanup = now;
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                if (!StellarUtils.isStellarAvailable()) {
                    writeGuardFlag(app, false);
                    return;
                }
                String cmd = "for p in $(ps -A -o PID,ARGS 2>/dev/null | grep '[s]elf_defense.sh' | awk '{print $1}'); do kill -9 \"$p\" 2>/dev/null; done; "
                        + "rm -rf /data/local/tmp/youlong_selfdefense.lock /data/local/tmp/youlong_selfdefense_a.lock /data/local/tmp/youlong_selfdefense_b.lock "
                        + "/data/local/tmp/youlong_selfdefense.pid /data/local/tmp/youlong_selfdefense_a.pid /data/local/tmp/youlong_selfdefense_b.pid; "
                        + "F=/data/local/tmp/youlong_frozen_list.txt; "
                        + "if [ -f \"$F\" ]; then grep -vxF -e 'com.youlong.hd' -e 'com.youlong.tool' -e 'com.youlong.zoo' \"$F\" >\"$F.tmp\" 2>/dev/null; mv \"$F.tmp\" \"$F\" 2>/dev/null; fi; "
                        + "pm enable --user 0 com.youlong.hd >/dev/null 2>&1; echo STOPPED";
                String out = StellarUtils.runCommand(cmd, 5000);
                Log.i(TAG, "自保停用清理: " + out);
            } catch (Throwable tr) {
                Log.w(TAG, "自保停用清理失败", tr);
            } finally {
                writeGuardFlag(app, false);
            }
        }, "SelfDefenseStop").start();
    }

    public static void writeGuardFlag(Context context, boolean enabled) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) return;
            if (!dir.exists()) dir.mkdirs();
            File flag = new File(dir, GUARD_FLAG_NAME);
            try (FileOutputStream out = new FileOutputStream(flag)) {
                out.write(enabled ? "1".getBytes(StandardCharsets.UTF_8)
                        : "0".getBytes(StandardCharsets.UTF_8));
            }
            flag.setReadable(true, false);
        } catch (Throwable t) {
            Log.w(TAG, "写自保开关标记失败", t);
        }
    }

    /** 由 UI 在开启/关闭安全守护时调用；关闭后脚本会在 2 秒内自行退出。 */
    public static void setGuardEnabled(Context context, boolean enabled) {
        boolean active = enabled && isSelfDefenseModeOn(context);
        writeGuardFlag(context, active);
        if (active) {
            importSystemAppsToWhitelist(context);
            ensureRunning(context);
        }
    }

    public static void ensureRunning(Context context) {
        // V30/2026-10-31：必须同时开启「安全守护」与「自保模式」才启动 watchdog。
        boolean guardEnabled = isGuardEnabled(context);
        writeGuardFlag(context, guardEnabled);
        if (!guardEnabled) {
            Log.i(TAG, "安全守护或自保模式未开启，跳过自保 watchdog");
            stopOldWatchdogsIfDisabled(context);
            return;
        }
        importSystemAppsToWhitelist(context);
        if (!STARTING.compareAndSet(false, true)) return;
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                boolean connected = false;
                try {
                    connected = Stellar.INSTANCE.pingBinder();
                } catch (Throwable ignored) {
                }
                if (!connected) return;

                writeBlacklistIfNeeded(app);
                File script = copyScript(app);
                if (script == null) return;
                killOldWatchdogsIfScriptChanged(app, script);

                Log.i(TAG, "启动自保 watchdog: " + script);
                // 关键：不要把脚本作为 App 的受管子进程。
                // nohup setsid 让它独立会话；App 被 disable/kill 后脚本仍能恢复我们。
                String cmd = "nohup setsid sh " + script.getAbsolutePath()
                        + " >/dev/null 2>&1 &";
                process = Stellar.INSTANCE.newProcess(
                        new String[]{"sh", "-c", cmd},
                        null,
                        null
                );
            } catch (Throwable t) {
                Log.w(TAG, "启动自保 watchdog 失败", t);
            } finally {
                STARTING.set(false);
            }
        }, "SelfDefenseStarter").start();
    }

    /**
     * V12：APK 更新后，旧的自保 shell 进程不会自己退出；它们会继续跑旧逻辑
     * （例如旧版只报警不冻结）。这里比较脚本哈希，变化时杀掉旧 watchdog
     * 并清掉旧锁，保证新脚本真正接管。
     */
    private static void killOldWatchdogsIfScriptChanged(Context app, File script) {
        try {
            String hash = sha256(script);
            if (hash == null || hash.isEmpty()) return;
            String prefsName = "self_defense_runtime";
            android.content.SharedPreferences sp =
                    app.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
            if (hash.equals(sp.getString("script_sha256", ""))) return;

            String cmd = "for f in "
                    + "/data/local/tmp/youlong_selfdefense.pid "
                    + "/data/local/tmp/youlong_selfdefense_a.pid "
                    + "/data/local/tmp/youlong_selfdefense_b.pid; do "
                    + "p=$(cat \"$f\" 2>/dev/null); "
                    + "[ -z \"$p\" ] && continue; "
                    + "[ -r \"/proc/$p/cmdline\" ] || continue; "
                    + "tr '\\0' ' ' < \"/proc/$p/cmdline\" 2>/dev/null | grep -q 'self_defense.sh' "
                    + "&& kill -9 \"$p\" 2>/dev/null; "
                    + "done; "
                    + "rm -f /data/local/tmp/youlong_selfdefense.pid "
                    + "/data/local/tmp/youlong_selfdefense_a.pid "
                    + "/data/local/tmp/youlong_selfdefense_b.pid; "
                    + "rm -rf /data/local/tmp/youlong_selfdefense.lock "
                    + "/data/local/tmp/youlong_selfdefense_a.lock "
                    + "/data/local/tmp/youlong_selfdefense_b.lock; "
                    + "echo CLEANED";
            String out = StellarUtils.runCommand(cmd, 5000);
            Log.i(TAG, "脚本已更新，清理旧 watchdog: " + out);
            sp.edit().putString("script_sha256", hash).apply();
        } catch (Throwable tr) {
            Log.w(TAG, "清理旧 watchdog 失败", tr);
        }
    }

    private static String sha256(File file) {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable tr) {
            return "";
        }
    }

    private static void writeBlacklistIfNeeded(Context context) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) return;
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "self_defense_blacklist.txt");
            if (!f.exists()) {
                try (FileOutputStream out = new FileOutputStream(f)) {
                    // 只放已确认的恶意样本；不要泛化 Shizuku/Termux。
                    out.write("mio.TerminalDeity.Ultra\n".getBytes(StandardCharsets.UTF_8));
                }
            }
            // Shell 要读黑名单；放宽到 other-readable。
            f.setReadable(true, true);
        } catch (Throwable t) {
            Log.w(TAG, "写自保黑名单失败", t);
        }
    }

    private static File copyScript(Context context) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) return null;
            if (!dir.exists()) dir.mkdirs();
            File script = new File(dir, "self_defense.sh");
            // V20：Android shell 对 CRLF 会逐行报错，脚本会直接退出。
            // 这里复制 raw 时主动去掉所有 \r，保证设备上的 .sh 一定是 LF；
            // 同时配合 .gitattributes 防止 Windows checkout 再次把脚本写成 CRLF。
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (InputStream in = context.getResources().openRawResource(R.raw.self_defense_script)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            }
            byte[] data = bos.toByteArray();
            try (FileOutputStream out = new FileOutputStream(script)) {
                for (byte b : data) {
                    if (b != '\r') out.write(b);
                }
            }
            // Stellar 服务是 shell uid，必须让 shell 能读到/执行这份脚本；
            // 只给 owner 权限会导致 sh 读文件失败，watchdog 静默退出。
            script.setReadable(true, true);
            script.setExecutable(true, true);
            return script;
        } catch (Throwable t) {
            Log.w(TAG, "复制自保脚本失败", t);
            return null;
        }
    }

    /**
     * V25：App 一启动就刷新外部脚本，不依赖 Stellar 是否已连接。
     * 这样 stellar_server 直接拉起脚本时，读到的就是当前版本（含 PID 校验/桌面保护）。
     */
    public static void prepareScript(Context context) {
        try {
            writeGuardFlag(context, isGuardEnabled(context));
            copyScript(context);
        } catch (Throwable t) {
            Log.w(TAG, "预刷新自保脚本失败", t);
        }
    }

    /**
     * V12：旧 watchdog 只报警不冻结时，App 侧立即补一次冻结，并写入
     * 持久冻结列表；新版脚本接管后会继续反复冻结。
     */
    private static boolean freezeSuspectsNow(String attacker) {
        if (attacker == null || attacker.trim().isEmpty()) return false;
        try {
            StringBuilder cmd = new StringBuilder();
            int count = 0;
            for (String raw : attacker.split("[,\\s]+")) {
                String p = raw == null ? "" : raw.trim();
                if (p.isEmpty() || p.equals("com.youlong.hd")
                        || p.equals("com.youlong.tool")
                        || p.equals("com.youlong.zoo")) continue;
                if (!p.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) continue;
                cmd.append("echo '").append(p)
                        .append("' >> /data/local/tmp/youlong_frozen_list.txt; ")
                        .append("am force-stop --user 0 ").append(p).append(" >/dev/null 2>&1; ")
                        .append("pm disable-user --user 0 ").append(p).append(" >/dev/null 2>&1; ");
                count++;
            }
            if (count == 0) return false;
            String out = StellarUtils.runCommand(cmd.toString(), 5000);
            boolean ok = out != null && !out.startsWith("ERROR:");
            Log.w(TAG, "App 侧补冻结 " + count + " 个嫌疑应用: " + ok + " / " + out);
            return ok;
        } catch (Throwable tr) {
            Log.w(TAG, "App 侧补冻结失败", tr);
            return false;
        }
    }

    /**
     * 脚本通过 am start MainActivity 拉起此弹窗。
     * 有嫌疑应用时脚本已执行反复冻结，弹窗只做告知；解冻统一走「冻结管理」。
     */
    public static void handleAlert(Activity activity) {
        try {
            Intent intent = activity.getIntent();
            if (intent == null) return;
            String attacker = intent.getStringExtra("self_defense_attacker");
            String confidence = intent.getStringExtra("self_defense_confidence");
            String reason = intent.getStringExtra("self_defense_reason");
            String action = intent.getStringExtra("self_defense_action");
            if (attacker == null && reason == null) return;

            String shownAttacker = (attacker == null || attacker.isEmpty()) ? "未定位" : attacker;
            // 旧版 watchdog 可能只把嫌疑应用列出来、action=warn；这里补一次强制冻结。
            if (!"freeze".equals(action) && attacker != null && !attacker.isEmpty()) {
                if (freezeSuspectsNow(attacker)) {
                    action = "freeze";
                }
            }
            StringBuilder msg = new StringBuilder();
            msg.append("检测到游龙被异常关闭/冻结。\n\n");
            msg.append("嫌疑应用：").append(shownAttacker).append("\n");
            if (confidence != null) msg.append("置信度：").append(confidence).append("\n");
            if (reason != null) msg.append("依据：").append(reason).append("\n");
            if ("freeze".equals(action)) {
                msg.append("\n已对嫌疑应用执行反复冻结（force-stop + pm disable-user）。");
                msg.append("\n可在 App 首页「冻结管理」中解冻。");
            } else if (attacker != null && !attacker.isEmpty()) {
                msg.append("\n已识别到嫌疑应用，但当前运行的是旧自保脚本，未执行冻结。");
                msg.append("\nApp 会自动清理旧 watchdog 并启动新版；请重新打开护盾。");
            } else {
                msg.append("\n未定位到具体可疑应用，本次没有冻结。");
            }
            msg.append("\n\n若误判，请到「冻结管理」解冻。");

            new AlertDialog.Builder(activity)
                    .setTitle("自保检测")
                    .setMessage(msg.toString())
                    .setPositiveButton("知道了", null)
                    .show();

            intent.removeExtra("self_defense_attacker");
            intent.removeExtra("self_defense_confidence");
            intent.removeExtra("self_defense_reason");
            intent.removeExtra("self_defense_evidence");
            intent.removeExtra("self_defense_action");
        } catch (Throwable t) {
            Log.w(TAG, "显示自保弹窗失败", t);
        }
    }
}


