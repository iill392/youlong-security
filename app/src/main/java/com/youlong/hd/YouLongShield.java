package com.youlong.hd;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;


public class YouLongShield {

    private static final String TAG = "YouLongShield";
    private static boolean sIsSecure = true;
    private static boolean sIsRooted = false;
    private static boolean sIsEmulator = false;
    private static volatile boolean sRetaliatingB = false; 
    private static final AtomicBoolean sSelfCheckStarted = new AtomicBoolean(false);
    private static final Random sRandom = new Random();

    
    private static volatile Context sContext = null;

    
    public static void init(Context context) {
        Log.i(TAG, "游龙安全护盾 v6.0 纵深防御启动中...");
        sContext = context != null ? context.getApplicationContext() : null;

        
        String tamperInfo = checkTamper(context);
        if (tamperInfo != null) {
            Log.w(TAG, "⚠ 防篡改检测异常: " + tamperInfo);
            sIsSecure = false;
        }
        if (!sIsSecure) {
            Log.e(TAG, "✗ APK 被篡改，立即终止（资产底线）");
            killNow();
            return;
        }

        
        
        
        
        //
        
        
        
        
        boolean debugged = detectDebugger();           
        boolean frida = detectFrida();                 
        boolean xposed = detectXposed();               
        if (debugged || frida) {
            Log.e(TAG, "✗ 检测到主动攻击（调试/Frida），启动随机报复");
            scheduleRetaliation(3, 15); 
        } else {
            // 端口探测误报率高（本地工具/端口转发都可能占用 27042/27043），
            // 只记录不报复（2026-10 审查修复）
            if (detectFridaPort()) {
                Log.w(TAG, "发现可疑 Frida 端口（27042/27043 可被普通工具占用，仅记录不处理）");
            }
            
            if (isTracerPidNonZero()) {
                Log.i(TAG, "环境信息: TracerPid 非 0（系统/ROM/工具所致，仅记录，正常使用）");
            }
            sIsRooted = detectRoot();
            sIsEmulator = detectEmulator();
            boolean virtualEnv = detectVirtualEnv();
            if (sIsRooted) Log.i(TAG, "环境信息: Root 设备（仅记录，正常使用）");
            if (sIsEmulator) Log.i(TAG, "环境信息: 模拟器（仅记录，正常使用）");
            if (virtualEnv) Log.i(TAG, "环境信息: 虚拟环境/多开（仅记录，正常使用）");
            if (xposed) Log.i(TAG, "环境信息: 检测到 Xposed 框架包（仅记录，正常使用）");
        }

        
        startBackgroundSelfCheck();

        Log.i(TAG, "游龙安全护盾检测流程完成");
    }

    // ========================================================================
    
    // ========================================================================

    
    private static void killNow() {
        try {
            Process.killProcess(Process.myPid());
        } catch (Exception ignored) {}
        System.exit(0);
    }

    
    private static void scheduleRetaliation(int minSec, int maxSec) {
        if (sRetaliatingB) return;
        sRetaliatingB = true;
        final int delaySec = minSec + sRandom.nextInt(maxSec - minSec + 1);
        final int mode = sRandom.nextInt(4);
        new Thread(() -> {
            try { Thread.sleep(delaySec * 1000L); } catch (InterruptedException ignored) {}
            switch (mode) {
                case 0: 
                    killNow();
                    break;
                case 1: 
                    throw new RuntimeException("internal error: " + sRandom.nextInt(9999));
                case 2: 
                    try {
                        //noinspection UnusedAssignment
                        long[] boom = new long[Integer.MAX_VALUE / 4];
                        boom[0] = 1;
                    } catch (Throwable t) {
                        
                        killNow();
                    }
                    break;
                default: 
                    try { Thread.sleep(1000 + sRandom.nextInt(5000)); } catch (InterruptedException ignored) {}
                    killNow();
                    break;
            }
        }, "shield-retal").start();
    }

    
    private static void startBackgroundSelfCheck() {
        if (!sSelfCheckStarted.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep((10 + sRandom.nextInt(51)) * 1000L);
                } catch (InterruptedException e) {
                    return;
                }
                
                
                
                
                boolean hit = detectDebugger()
                        || detectFrida();
                if (hit) {
                    scheduleRetaliation(2, 10);
                    return;
                }
                // 端口探测仅记录（可能误报）
                if (detectFridaPort()) {
                    Log.w(TAG, "后台巡检发现可疑 Frida 端口（仅记录，不处理）");
                }
            }
        }, "shield-watchdog");
        t.setDaemon(true);
        t.start();
    }

    
    private static boolean isTracerPidNonZero() {
        try {
            return com.youlong.hd.NativeCrypto.isTracerAttached();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ========================================================================
    
    // ========================================================================
    
    private static String checkTamper(Context context) {
        try {
            String apkPath = context.getPackageCodePath();
            if (apkPath == null) return null;

            
            ZipFile zipFile = new ZipFile(apkPath);
            String result = null;

            List<String> dexNames = new ArrayList<>();
            int idx = 1;
            while (true) {
                String name = (idx == 1) ? "classes.dex" : "classes" + idx + ".dex";
                ZipEntry entry = zipFile.getEntry(name);
                if (entry == null) break;
                dexNames.add(name);
                idx++;
            }

            for (String dexName : dexNames) {
                ZipEntry entry = zipFile.getEntry(dexName);
                if (entry == null) {
                    result = dexName + " 不存在";
                    break;
                }
                if (entry.getCrc() == 0 || entry.getSize() <= 0) {
                    result = dexName + " 损坏";
                    break;
                }
                
                try (java.io.InputStream is = zipFile.getInputStream(entry)) {
                    byte[] magic = new byte[4];
                    int read = is.read(magic);
                    if (read < 4 || magic[0] != 0x64 || magic[1] != 0x65
                            || magic[2] != 0x78 || magic[3] != 0x0A) {
                        result = dexName + " 魔数异常";
                        break;
                    }
                }
            }

            if (result != null) {
                zipFile.close();
                return result;
            }

            
            
            
            
            ZipEntry manifest = zipFile.getEntry("META-INF/MANIFEST.MF");
            if (manifest != null && manifest.getSize() > 0) {
                
                boolean hasSig = false;
                java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.startsWith("META-INF/") && (name.endsWith(".RSA") || name.endsWith(".SF"))) {
                        hasSig = true;
                        break;
                    }
                }
                zipFile.close();
                if (!hasSig) {
                    return "META-INF 签名文件缺失";
                }
            } else {
                
                zipFile.close();
            }

            return null; 
        } catch (Exception e) {
            return null; 
        }
    }

    // ========================================================================
    
    // ========================================================================
    private static boolean detectRoot() {
        String[] rootPaths = {
                "/system/app/Superuser.apk",
                "/sbin/su",
                "/system/bin/su",
                "/system/xbin/su",
                "/data/local/xbin/su",
                "/data/local/bin/su",
                "/system/sd/xbin/su",
                "/system/bin/failsafe/su",
                "/data/local/su",
                "/su/bin/su",
                // Magisk
                "/sbin/magisk",
                "/system/bin/magisk",
                "/system/xbin/magisk",
                "/data/adb/magisk",
                "/data/adb/ksu",          // KernelSU
                "/data/adb/apd",          // APatch
                "/system/bin/ksud",
                
                "/system/xbin/busybox",
                "/system/bin/busybox",
                "/data/adb/busybox"
        };

        for (String path : rootPaths) {
            if (new File(path).exists()) {
                Log.i(TAG, "发现 root 特征文件: " + path);
                return true;
            }
        }

        
        if (isPackageInstalled("com.topjohnwu.magisk")) return true;
        if (isPackageInstalled("io.github.huskydg.magisk")) return true;
        if (isPackageInstalled("com.kingroot.kinguser")) return true;
        if (isPackageInstalled("com.kingo.root")) return true;
        if (isPackageInstalled("eu.chainfire.supersu")) return true;
        if (isPackageInstalled("com.koushikdutta.superuser")) return true;
        if (isPackageInstalled("com.dianxinos.superuser")) return true;

        // which su
        java.lang.Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"which", "su"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            if (line != null && !line.isEmpty()) {
                Log.i(TAG, "which su 找到: " + line);
                return true;
            }
        } catch (Exception ignored) {
        } finally {
            if (process != null) process.destroy();
        }

        
        if (Build.TAGS != null && Build.TAGS.contains("test-keys")) {
            Log.i(TAG, "Build.TAGS 包含 test-keys: " + Build.TAGS);
            return true;
        }

        return false;
    }

    // ========================================================================
    
    // ========================================================================
    private static boolean detectEmulator() {
        String fp = Build.FINGERPRINT;
        if (fp != null) {
            String f = fp.toLowerCase();
            if (f.contains("generic")
                    || f.contains("emulator")
                    || f.contains("sdk_gphone")
                    || f.contains("vbox")
                    || f.contains("qemu")
                    || f.contains("genymotion")
                    || f.contains("bluestacks")
                    || f.contains("nox")
                    || f.contains("mumu")
                    || f.contains("ldplayer")
                    || f.contains("memu")) {
                Log.i(TAG, "Build.FINGERPRINT 模拟器特征: " + fp);
                return true;
            }
        }

        String model = Build.MODEL;
        if (model != null) {
            String m = model.toLowerCase();
            if (m.contains("google_sdk")
                    || m.contains("emulator")
                    || m.contains("sdk_gphone")
                    || m.contains("genymotion")
                    || m.contains("bluestacks")
                    || m.contains("nox")
                    || m.contains("mumu")
                    || m.contains("ldplayer")
                    || m.contains("memu")
                    || m.contains("droid4x")
                    || m.contains("ttvm")) {
                Log.i(TAG, "Build.MODEL 模拟器特征: " + model);
                return true;
            }
        }

        String hardware = Build.HARDWARE;
        if (hardware != null) {
            String h = hardware.toLowerCase();
            if (h.contains("goldfish")
                    || h.contains("ranchu")
                    || h.contains("qemu")
                    || h.contains("nox")
                    || h.contains("vbox")
                    || h.contains("emulator")) {
                Log.i(TAG, "Build.HARDWARE 模拟器特征: " + hardware);
                return true;
            }
        }

        String product = Build.PRODUCT;
        if (product != null) {
            String p = product.toLowerCase();
            if (p.contains("sdk")
                    || p.contains("emulator")
                    || p.contains("simulator")
                    || p.contains("vbox")
                    || p.contains("nox")
                    || p.contains("goldfish")) {
                Log.i(TAG, "Build.PRODUCT 模拟器特征: " + product);
                return true;
            }
        }

        
        String[] emuFiles = {
                "/system/lib/libc_malloc_debug_qemu.so",
                "/sys/qemu_trace",
                "/system/bin/qemu-props",
                "/system/lib/libdroid4x.so",
                "/system/lib/libnoxspeed.so",
                "/system/bin/noxd",
                "/system/lib/libbluestacks.so",
                "/dev/socket/qemud",
                "/dev/qemu_pipe",
                "/system/lib/libgoldfish.so"
        };
        for (String path : emuFiles) {
            if (new File(path).exists()) {
                Log.i(TAG, "模拟器特征文件: " + path);
                return true;
            }
        }

        return false;
    }

    // ========================================================================
    
    // ========================================================================
    private static boolean detectVirtualEnv() {
        String[] vPackages = {
                "com.lbe.parallel",
                "com.parallel.space",
                "com.qihoo.magic",
                "com.by.chaos",
                "com.excelliance.dualaiv2",
                "com.excelliance.dualaiv3",
                "com.moxiaoxi.dualspace",
                "com.dualspace.app",
                "com.lody.virtual",
                "io.va.exposed",
                "com.vphone.launcher",
                "com.pirateking.hook",
                "com.zeroone.fake"
        };
        for (String pkg : vPackages) {
            if (isPackageInstalled(pkg)) {
                Log.i(TAG, "检测到虚拟环境包: " + pkg);
                return true;
            }
        }

        
        try {
            File cmdlineFile = new File("/proc/self/cmdline");
            if (cmdlineFile.exists()) {
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(new FileInputStream(cmdlineFile)));
                String cmd = br.readLine();
                br.close();
                if (cmd != null && cmd.toLowerCase().contains("virtual")) {
                    Log.i(TAG, "进程运行于虚拟环境 cmdline: " + cmd);
                    return true;
                }
            }
        } catch (Exception ignored) {}

        return false;
    }

    // ========================================================================
    
    // ========================================================================
    private static boolean detectDebugger() {
        
        if (android.os.Debug.isDebuggerConnected()
                || android.os.Debug.waitingForDebugger()) {
            Log.i(TAG, "检测到调试器连接");
            return true;
        }

        
        
        
        
        
        
        return false;
    }

    // ========================================================================
    
    // ========================================================================
    private static boolean detectFrida() {
        
        try {
            if (com.youlong.hd.NativeCrypto.detectFrida()) {
                Log.i(TAG, "Native maps 扫描发现 Frida 特征");
                return true;
            }
        } catch (Throwable ignored) {}

        
        String[] fridaFiles = {
                StrX.d(StrX.FRIDA_SERVER),
                StrX.d(StrX.FRIDA_SERVER_14),
                StrX.d(StrX.FRIDA_SERVER_15),
                StrX.d(StrX.RE_FRIDA_SERVER),
                StrX.d(StrX.FRIDA_GADGET),
                StrX.d(StrX.FRIDA_AGENT_SO),
                StrX.d(StrX.LINJECTOR)
        };
        for (String path : fridaFiles) {
            if (new File(path).exists()) {
                Log.i(TAG, "发现 Frida 特征文件: " + path);
                return true;
            }
        }

        return false;
    }

    // ========================================================================
    
    // ========================================================================
    // 纯端口探测（27042/27043）误报率高：本地开发工具、端口转发等都可能占用。
    // 从 detectFrida() 拆出，仅用于记录，不触发报复（2026-10 审查修复）。
    private static boolean detectFridaPort() {
        int[] ports = {27042, 27043};
        for (int port : ports) {
            try (Socket sock = new Socket()) {
                sock.connect(new java.net.InetSocketAddress("127.0.0.1", port), 300);
                Log.i(TAG, "发现 Frida 端口: " + port);
                return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    // ========================================================================
    
    // ========================================================================
    private static boolean detectXposed() {
        
        String[] xposedPkgs = {
                StrX.d(StrX.PKG_XPOSED_INSTALLER),
                StrX.d(StrX.PKG_LSPATCH),
                StrX.d(StrX.PKG_LSPD),
                StrX.d(StrX.PKG_HIDE_MY_APPLIST),
                StrX.d(StrX.PKG_SUBSTRATE),
                StrX.d(StrX.PKG_DREAMLAND),
                StrX.d(StrX.PKG_EDXP)
        };
        for (String pkg : xposedPkgs) {
            if (isPackageInstalled(pkg)) {
                Log.i(TAG, "检测到 Xposed 框架包: " + pkg);
                return true;
            }
        }

        
        try {
            Class.forName(StrX.d(StrX.CLS_XPOSED_BRIDGE));
            Log.i(TAG, "检测到 XposedBridge 类已加载");
            return true;
        } catch (ClassNotFoundException ignored) {}

        
        try {
            ClassLoader cl = YouLongShield.class.getClassLoader();
            if (cl != null) {
                String cls = String.valueOf(cl);
                if (cls.contains("xposed") || cls.contains("edxp") || cls.contains("lspd")) {
                    Log.i(TAG, "类加载器含 Xposed 特征: " + cls);
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        return false;
    }

    // ========================================================================
    
    // ========================================================================
    private static boolean isPackageInstalled(String pkg) {
        try {
            
            Context ctx = sContext;
            if (ctx == null) {
                
                Object thread = Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication")
                        .invoke(null);
                ctx = (Context) thread;
            }
            if (ctx == null) return false;
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ========================================================================
    
    // ========================================================================
    public static boolean isSecure() {
        return sIsSecure;
    }

    public static boolean isRooted() {
        return sIsRooted;
    }

    public static boolean isEmulator() {
        return sIsEmulator;
    }
}
