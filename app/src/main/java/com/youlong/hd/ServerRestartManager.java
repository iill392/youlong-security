package com.youlong.hd;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.util.Log;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 2026-10-30：应用更新后自动重启内置特权服务端。
 *
 * 背景：APK 更新后，旧的 stellar_server 进程不会自动退出，仍跑旧代码。
 * 这里用两个标记判断：
 *   · SharedPreferences 里记 last_app_version_code / server_pending_version；
 *   · 服务端启动时在外部目录写 server_version（当前 APK versionCode）。
 *
 * 新版本 App 启动或收到 MY_PACKAGE_REPLACED 时，如果 server_version 与当前
 * App versionCode 不一致，就直接用旧 Stellar Binder 执行：
 *   kill -9 <旧 stellar_server> → 用新 APK 的 libstellar.so 重新拉起。
 * 全程不需要用户手动点“重启服务”。
 */
public final class ServerRestartManager {

    private static final String TAG = "ServerRestart";
    private static final String PREFS = "shield_prefs";
    private static final String KEY_LAST_APP_VERSION = "last_app_version_code";
    private static final String KEY_PENDING_VERSION = "server_pending_version";
    private static final String KEY_LAST_RESTART_ATTEMPT = "server_restart_last_attempt";
    private static final String MARKER_NAME = "server_version";
    private static final long RESTART_RETRY_BACKOFF_MS = 60_000L;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private ServerRestartManager() {
    }

    public static void checkAsync(Context context) {
        if (context == null) return;
        if (!RUNNING.compareAndSet(false, true)) return;
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                check(app);
            } catch (Throwable tr) {
                Log.w(TAG, "检查/重启服务端失败", tr);
            } finally {
                RUNNING.set(false);
            }
        }, "ServerRestartManager").start();
    }

    private static void check(Context context) {
        int appVersion = appVersionCode(context);
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int lastVersion = sp.getInt(KEY_LAST_APP_VERSION, -1);

        // 2026-10-31：首次安装不是“版本更新”。
        // 旧逻辑把 lastVersion=-1 也当成更新，写入 pending；服务端刚启动、
        // marker 还没落盘时，App 就会在 Binder 就绪后把它 kill 掉再重启，
        // 表现为“全新设备第一次启动内置特权后服务自己关闭，第二次才正常”。
        if (lastVersion <= 0) {
            sp.edit()
                    .putInt(KEY_LAST_APP_VERSION, appVersion)
                    .remove(KEY_PENDING_VERSION)
                    .remove(KEY_LAST_RESTART_ATTEMPT)
                    .apply();
            Log.i(TAG, "首次安装，仅记录版本 " + appVersion + "，不重启服务端");
            RuntimeEventLog.log(context, "AUTO",
                    "首次安装，记录版本 " + appVersion + "，不重启服务端");
            return;
        }

        if (lastVersion != appVersion) {
            sp.edit()
                    .putInt(KEY_LAST_APP_VERSION, appVersion)
                    .putInt(KEY_PENDING_VERSION, appVersion)
                    .remove(KEY_LAST_RESTART_ATTEMPT)
                    .apply();
            Log.i(TAG, "检测到版本变化: " + lastVersion + " -> " + appVersion);
            RuntimeEventLog.log(context, "AUTO",
                    "检测到 App 版本变化 " + lastVersion + " -> " + appVersion + "，标记服务端待重启");
        }

        int pending = sp.getInt(KEY_PENDING_VERSION, 0);
        String marker = readMarker(context);
        if (marker != null && marker.trim().equals(String.valueOf(appVersion))) {
            if (pending != 0) {
                sp.edit()
                        .remove(KEY_PENDING_VERSION)
                        .remove(KEY_LAST_RESTART_ATTEMPT)
                        .apply();
                Log.i(TAG, "服务端版本已匹配: " + appVersion + "，清除 pending");
                RuntimeEventLog.log(context, "AUTO", "服务端版本已匹配 " + appVersion + "，无需重启");
            }
            return;
        }

        if (pending == 0) {
            // 没有版本变更 pending 时绝不动服务端，避免使用过程中“突然重启”。
            return;
        }

        // 失败重试必须间隔 60 秒，且只在版本变更 pending 期间发生；
        // 这样一次更新最多触发有限次，不会反复打断用户。
        long now = System.currentTimeMillis();
        long lastAttempt = sp.getLong(KEY_LAST_RESTART_ATTEMPT, 0L);
        if (lastAttempt > 0L && now - lastAttempt < RESTART_RETRY_BACKOFF_MS) {
            Log.i(TAG, "服务端重启仍在退避期，跳过本次");
            return;
        }
        sp.edit().putLong(KEY_LAST_RESTART_ATTEMPT, now).apply();

        Log.i(TAG, "服务端需要重启: marker=" + marker + ", expected=" + appVersion);
        boolean connected = false;
        for (int i = 0; i < 20; i++) {
            if (StellarUtils.isStellarAvailable()) {
                connected = true;
                break;
            }
            sleep(500);
        }
        if (!connected) {
            Log.w(TAG, "旧服务端 Binder 不可用，等待下次启动/守护自启后重试");
            RuntimeEventLog.log(context, "AUTO",
                    "服务端需要重启，但旧服务端 Binder 不可用，等待下次启动/守护自启");
            return;
        }

        // 2026-10-31：Binder 可用后、真正 kill 之前再读一次 marker。
        // 服务端可能刚写完当前版本 marker，之前读到的是启动前的空/旧值。
        for (int i = 0; i < 10; i++) {
            String markerNow = readMarker(context);
            if (markerNow != null && markerNow.trim().equals(String.valueOf(appVersion))) {
                sp.edit()
                        .remove(KEY_PENDING_VERSION)
                        .remove(KEY_LAST_RESTART_ATTEMPT)
                        .apply();
                Log.i(TAG, "服务端启动后 marker 已匹配 " + appVersion + "，取消重启");
                RuntimeEventLog.log(context, "AUTO",
                        "服务端启动后版本已匹配 " + appVersion + "，取消重启");
                return;
            }
            sleep(300);
        }

        RuntimeEventLog.log(context, "AUTO",
                "旧服务端 Binder 可用，开始自动重启（目标 versionCode=" + appVersion + "）");
        if (!restartServer(context)) {
            Log.w(TAG, "重启命令未成功发出");
            RuntimeEventLog.log(context, "AUTO", "服务端自动重启命令发送失败");
            return;
        }

        // 等待新服务端写 version marker
        for (int i = 0; i < 30; i++) {
            sleep(500);
            String markerNow = readMarker(context);
            if (markerNow != null && markerNow.trim().equals(String.valueOf(appVersion))) {
                sp.edit()
                        .remove(KEY_PENDING_VERSION)
                        .remove(KEY_LAST_RESTART_ATTEMPT)
                        .apply();
                Log.i(TAG, "服务端自动重启完成，版本匹配: " + appVersion);
                RuntimeEventLog.log(context, "AUTO",
                        "服务端自动重启完成，新版本=" + appVersion);
                return;
            }
        }
        Log.w(TAG, "服务端重启后 version marker 仍未匹配，保留 pending 下次重试");
        RuntimeEventLog.log(context, "AUTO",
                "服务端重启后版本标记仍未匹配，保留 pending 下次重试");
    }

    private static boolean restartServer(Context context) {
        try {
            ApplicationInfo ai = context.getApplicationInfo();
            String lib = new File(ai.nativeLibraryDir, "libstellar.so").getAbsolutePath();
            if (!new File(lib).isFile()) {
                Log.w(TAG, "libstellar.so 不存在: " + lib);
                return false;
            }
            // 与手动可启动方式一致：直接执行 libstellar.so，不带 --apk，
            // starter 会自己从 /lib/ 反推 base.apk 并结束旧 stellar_server。
            String command =
                    "nohup setsid \"" + lib + "\" >/dev/null 2>&1 & " +
                    "echo SERVER_RESTARTED";
            String out = StellarUtils.runCommand(command, 8000);
            Log.i(TAG, "服务端重启命令输出: " + out);
            // 旧服务端被 starter 结束后 Binder 可能返回 ERROR；命令本身已发出，
            // 真正是否成功由后续 server_version marker 判断。
            return true;
        } catch (Throwable tr) {
            Log.w(TAG, "执行服务端重启命令失败", tr);
            return false;
        }
    }

    private static int appVersionCode(Context context) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                return (int) pi.getLongVersionCode();
            }
            return pi.versionCode;
        } catch (Throwable tr) {
            return -1;
        }
    }

    private static String readMarker(Context context) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) return null;
            File f = new File(dir, MARKER_NAME);
            if (!f.isFile()) return null;
            return readText(f);
        } catch (Throwable tr) {
            return null;
        }
    }

    private static String readText(File file) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] buf = new byte[(int) Math.min(file.length(), 128)];
            int n = in.read(buf);
            if (n <= 0) return "";
            return new String(buf, 0, n, "UTF-8");
        } catch (Throwable tr) {
            return null;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
