package com.youlong.priv;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.youlong.hd.YouLongApp;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 拉起特权服务端（{@code app_process}）。
 *
 * <p>启动命令形如：
 * <pre>
 * CLASSPATH=&lt;apk&gt; /system/bin/app_process64 /system/bin --nice-name=youlong_priv \
 *     com.youlong.priv.server.YlServerMain --yl-channel=&lt;抽象socket名&gt; --yl-pkg=&lt;宿主包名&gt;
 * </pre>
 * 类名之后的参数由 {@code app_process} 原样转给 {@code YlServerMain.main}。
 */
public final class YlPrivLauncher {

    private static final String TAG = "YlPrivLauncher";

    /** 服务端入口类（不能改：proguard 里专门 keep 了 main 方法） */
    public static final String SERVER_ENTRY_CLASS =
            "com.youlong.priv.server.YlServerMain";

    /** 服务端进程名（pidof / kill 都按它找） */
    public static final String SERVER_NICE_NAME = "youlong_priv";

    /** 启动参数前缀：会合通道（抽象 Unix socket 名） */
    public static final String ARG_CHANNEL = "--yl-channel=";

    /** 启动参数前缀：宿主包名（服务端据此解析可信 uid） */
    public static final String ARG_PKG = "--yl-pkg=";

    /** 等 pidof 认出服务端的超时 */
    private static final long PID_WAIT_MS = 6000L;

    private YlPrivLauncher() {}

    /** 拉起结果 */
    public static final class SpawnResult {
        public final boolean ok;
        public final long pid;
        public final String message;

        SpawnResult(boolean ok, long pid, String message) {
            this.ok = ok;
            this.pid = pid;
            this.message = message;
        }
    }

    /** 本应用的 APK 路径（CLASSPATH 用） */
    public static String apkPath() {
        try {
            YouLongApp app = YouLongApp.instance();
            if (app != null) {
                android.content.pm.ApplicationInfo ai = app.getApplicationInfo();
                if (ai.publicSourceDir != null) return ai.publicSourceDir;
                if (ai.sourceDir != null) return ai.sourceDir;
            }
        } catch (Throwable t) {
            Log.w(TAG, "从 YouLongApp 取 APK 路径失败", t);
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof android.app.Application) {
                android.content.pm.ApplicationInfo ai =
                        ((android.app.Application) app).getApplicationInfo();
                if (ai.publicSourceDir != null) return ai.publicSourceDir;
                if (ai.sourceDir != null) return ai.sourceDir;
            }
        } catch (Throwable t) {
            Log.w(TAG, "反射 ActivityThread 取 APK 路径失败", t);
        }
        return null;
    }

    /** app_process 路径（优先 64 位，取不到再退回另一个） */
    public static String appProcessPath() {
        boolean is64 = "64".equals(System.getProperty("sun.arch.data.model"))
                || isAbi64(System.getProperty("os.arch"));
        if (!is64 && Build.SUPPORTED_64_BIT_ABIS != null) {
            is64 = Build.SUPPORTED_64_BIT_ABIS.length > 0;
        }
        String path = is64 ? "/system/bin/app_process64" : "/system/bin/app_process32";
        if (new File(path).exists()) return path;
        String fallback = is64 ? "/system/bin/app_process32" : "/system/bin/app_process64";
        if (new File(fallback).exists()) return fallback;
        return "/system/bin/app_process";
    }

    private static boolean isAbi64(String abi) {
        return abi != null && abi.contains("64");
    }

    /**
     * 生成会合通道名（抽象 Unix socket，不用文件系统路径）。
     *
     * <p>带 pid + 纳秒 + 随机数，避免拿到上一次失败残留的 socket。
     */
    public static String newChannelName() {
        return "youlong.ylpriv." + android.os.Process.myPid()
                + "-" + Long.toHexString(System.nanoTime())
                + "-" + Long.toHexString(System.nanoTime() ^ (System.currentTimeMillis() * 0x9E3779B9L));
    }

    /** 拉起服务端（不带会合通道/包名，兼容旧调用） */
    public static SpawnResult spawnServer(Context ctx) {
        return spawnServer(ctx, null, null);
    }

    /**
     * 拉起服务端。
     *
     * @param channelName 会合通道名（{@link #newChannelName()}，可为 null）
     * @param packageName 宿主包名（服务端解析可信 uid 用，可为 null）
     */
    public static SpawnResult spawnServer(Context ctx, String channelName, String packageName) {
        String apk = apkPath();
        if (apk == null) {
            return new SpawnResult(false, -1, "无法定位本应用 APK 路径");
        }
        if (ctx == null) {
            return new SpawnResult(false, -1, "Context 为空");
        }
        String appProcess = appProcessPath();
        List<String> argv = new ArrayList<>();
        argv.add("sh");
        argv.add("-c");

        StringBuilder cmd = new StringBuilder();
        cmd.append("CLASSPATH=").append(shellQuote(apk)).append(' ')
                .append(appProcess).append(" /system/bin")
                .append(" --nice-name=").append(SERVER_NICE_NAME)
                .append(' ').append(SERVER_ENTRY_CLASS);
        if (channelName != null && !channelName.isEmpty()) {
            cmd.append(' ').append(ARG_CHANNEL).append(shellQuote(channelName));
        }
        if (packageName != null && !packageName.isEmpty()) {
            cmd.append(' ').append(ARG_PKG).append(shellQuote(packageName));
        }
        cmd.append(" >/dev/null 2>&1 &");
        argv.add(cmd.toString());

        try {
            Process p = new ProcessBuilder(argv).start();
            int code = p.waitFor();
            if (code != 0) {
                killServer(-1);
                return new SpawnResult(false, -1, "启动命令退出码=" + code);
            }
            long pid = waitForServerPid(PID_WAIT_MS);
            if (pid <= 0) {
                // 自清理：命令下发成功但没等到进程，可能已经起了个卡死的残留
                killServer(-1);
                return new SpawnResult(false, -1, "未检测到 " + SERVER_NICE_NAME + " 进程");
            }
            Log.i(TAG, "已拉起特权服务端 pid=" + pid + " app_process=" + appProcess
                    + " channel=" + channelName);
            return new SpawnResult(true, pid, "启动命令已下发");
        } catch (IOException e) {
            killServer(-1);
            return new SpawnResult(false, -1, "启动失败: " + e);
        } catch (Throwable t) {
            killServer(-1);
            return new SpawnResult(false, -1, "启动异常: " + t);
        }
    }

    /**
     * 杀掉残留的服务端进程（失败自清理）。
     *
     * @param expectPid 只杀这个 pid（从 {@link SpawnResult#pid} 拿）；&lt;=0 表示按进程名杀全部
     * @return 实际杀掉的进程数
     */
    public static int killServer(long expectPid) {
        List<Long> pids = new ArrayList<>();
        if (expectPid > 0) {
            pids.add(expectPid);
        } else {
            pids.addAll(pidOf(SERVER_NICE_NAME));
        }
        if (pids.isEmpty()) return 0;
        StringBuilder sb = new StringBuilder("kill -9");
        for (Long pid : pids) {
            sb.append(' ').append(pid);
        }
        int killed = 0;
        try {
            Process p = new ProcessBuilder("sh", "-c", sb.toString()).start();
            p.waitFor();
            killed = pids.size();
            Log.w(TAG, "已清理残留服务端进程: " + sb);
        } catch (Throwable t) {
            Log.w(TAG, "清理残留服务端进程失败: " + sb, t);
        }
        return killed;
    }

    /** 等 pidof 认出服务端进程（拿不到返回 -1） */
    private static long waitForServerPid(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<Long> pids = pidOf(SERVER_NICE_NAME);
            if (!pids.isEmpty()) return pids.get(0);
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return -1;
    }

    /** pidof：只接受纯数字结果，避免把 shell 的报错文本当 pid */
    private static List<Long> pidOf(String name) {
        List<Long> out = new ArrayList<>();
        BufferedReader r = null;
        try {
            Process p = new ProcessBuilder("sh", "-c", "pidof " + shellQuote(name))
                    .redirectErrorStream(true).start();
            r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line = r.readLine();
            p.waitFor();
            if (line == null) return out;
            for (String part : line.trim().split("\\s+")) {
                if (part.isEmpty()) continue;
                for (int i = 0; i < part.length(); i++) {
                    char c = part.charAt(i);
                    if (c < '0' || c > '9') {
                        return out;   // 非法字符：整行丢弃，别拿去 kill
                    }
                }
                try {
                    out.add(Long.parseLong(part));
                } catch (NumberFormatException ignored) {
                    return out;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (r != null) {
                try { r.close(); } catch (Throwable ignored) {}
            }
        }
        return out;
    }

    private static String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
