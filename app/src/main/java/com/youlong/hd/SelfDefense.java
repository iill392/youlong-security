package com.youlong.hd;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
 *   4. 自保只恢复自己：不再归因、冻结、force-stop 或卸载任何其它应用；
 *   5. 严重异常时只弹自保提示，然后尝试恢复自己的组件/进程。
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
    private static volatile StellarRemoteProcess process;
    private static volatile long lastStopCleanup = 0L;

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
        if (enabled && active) {
            ensureRunning(context);
        } else if (!active) {
            // 关闭自保模式时除了写 0，还要清理旧 watchdog，避免旧脚本继续误冻结。
            stopOldWatchdogsIfDisabled(context);
        }
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
     * 脚本通过 am start MainActivity 拉起此弹窗。
     * 2026-11-02：自保只关心自己，不再冻结/归因任何嫌疑应用。
     */
    public static void handleAlert(Activity activity) {
        try {
            Intent intent = activity.getIntent();
            if (intent == null) return;
            String reason = intent.getStringExtra("self_defense_reason");
            if (reason == null) return;

            new AlertDialog.Builder(activity)
                    .setTitle("自保检测")
                    .setMessage("检测到游龙安全护盾被停止/冻结，已强制恢复。")
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


