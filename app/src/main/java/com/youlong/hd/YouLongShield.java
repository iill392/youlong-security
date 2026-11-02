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

/**
 * 游龙安全护盾 v6.0 — 大厂级纵深防御
 * ===========================
 * ✓ 防篡改（DEX CRC 校验 + APK 结构完整性 + META-INF 校验）→ 立即终止（资产底线）
 * ✓ 反 Root（Magisk / KernelSU / busybox / su 全路径 / test-keys）
 * ✓ 反模拟器（Build 特征 + 模拟器特征文件 + 硬件特征）
 * ✓ 反虚拟环境（VirtualApp / 平行空间 / 双开）
 * ✓ 反调试（Debug API + Native TracerPid 只读检测）
 * ✓ 反 Frida（端口扫描 + D-Bus 握手 + Native maps 扫描）
 * ✓ 反 Xposed（包名 + XposedBridge 类 + 类加载器特征）
 * ✓ 多线程随机间隔自检（后台守护线程，攻击者难以定位触发点）
 * ✓ 大厂报复策略：高危(调试器 / Frida) → 随机延时 3-15s 随机崩溃；
 *                 环境风险(Root/模拟器/多开/TracerPid 非 0) → 仅记录日志，绝不报复
 *
 * ⚠️ TracerPid 是**环境特征**而非"攻击行为"，已从报复条件中移除。
 *    Android 上 TracerPid 非 0 的常见原因是厂商 ROM 加固、无障碍/性能剖析工具、
 *    沙箱/多开框架，以及历史版本本应用自己误用的 ptrace(PTRACE_TRACEME)
 *    （见 cpp/NativeCrypto.cpp）—— 据此报复会随机崩溃误杀正常用户。
 */
public class YouLongShield {

    private static final String TAG = "YouLongShield";
    private static boolean sIsSecure = true;
    private static boolean sIsRooted = false;
    private static boolean sIsEmulator = false;
    private static volatile boolean sRetaliatingB = false; // B 级崩溃报复已排队
    private static final AtomicBoolean sSelfCheckStarted = new AtomicBoolean(false);
    private static final Random sRandom = new Random();

    // 缓存的 Context（init 时注入；用于包检测，避免依赖隐藏 API ActivityThread）
    private static volatile Context sContext = null;

    /**
     * 初始化安全护盾 — 大厂报复策略
     *
     * 分级处置：
     *   A 级（资产底线）篡改/签名破坏 → 立即终止，不给任何机会
     *   B 级（主动攻击）调试器/Frida/Xposed → 随机延时 3-15s 后随机崩溃
     *   C 级（环境风险）Root/模拟器/多开 → 随机延时 5-30s 后功能错乱（正常用户无感）
     */
    public static void init(Context context) {
        Log.i(TAG, "游龙安全护盾 v6.0 纵深防御启动中...");
        sContext = context != null ? context.getApplicationContext() : null;

        // ---- A 级：防篡改检测（立即终止）----
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

        // ---- B 级：主动攻击检测（随机延时崩溃）----
        // 只对"真正的攻击行为"报复：调试器连接（Debug API，权威且无误报）/ Frida。
        // 注意：Root/模拟器/多开/Xposed 框架包是玩机用户(本 App 目标群体)的常见环境，
        //       属于"环境特征"而非"攻击行为"，只记录日志，绝不报复（否则误杀正常用户）。
        //
        // ⚠️ TracerPid 非 0 同样归入"环境特征"，**已从报复条件中移除**（仅记录日志）。
        //    历史缺陷：native 层 ptrace(PTRACE_TRACEME) 把 TracerPid 污染成 zygote →
        //    被判定为"被调试" → 随机 3-15s 崩溃，表现为「启动即无响应」。
        //    详见 cpp/NativeCrypto.cpp 与类头注释。
        boolean debugged = detectDebugger();           // 仅 Debug API（权威、无误报）
        boolean frida = detectFrida();                 // 端口 + D-Bus + Native maps
        boolean xposed = detectXposed();               // 仅记录，不报复（LSPosed 用户常见）
        if (debugged || frida) {
            Log.e(TAG, "✗ 检测到主动攻击（调试/Frida），启动随机报复");
            scheduleRetaliation(3, 15); // 随机延时 3-15s 后随机崩溃
        } else {
            // ---- 环境风险检测：仅记录日志，不干扰不报复 ----
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

        // ---- 后台守护自检：无论是否命中，持续随机间隔复检 ----
        startBackgroundSelfCheck();

        Log.i(TAG, "游龙安全护盾检测流程完成");
    }

    // ========================================================================
    // 报复机制（大厂风格：随机延时 + 随机方式，正常用户无感、攻击者难定位）
    // ========================================================================

    /** 立即终止进程（A 级资产底线用） */
    private static void killNow() {
        try {
            Process.killProcess(Process.myPid());
        } catch (Exception ignored) {}
        System.exit(0);
    }

    /**
     * B 级：随机延时后随机崩溃
     * 方式随机化，避免攻击者通过固定崩溃点定位检测逻辑
     */
    private static void scheduleRetaliation(int minSec, int maxSec) {
        if (sRetaliatingB) return;
        sRetaliatingB = true;
        final int delaySec = minSec + sRandom.nextInt(maxSec - minSec + 1);
        final int mode = sRandom.nextInt(4);
        new Thread(() -> {
            try { Thread.sleep(delaySec * 1000L); } catch (InterruptedException ignored) {}
            switch (mode) {
                case 0: // 静默自杀（看似正常退出）
                    killNow();
                    break;
                case 1: // 抛出未捕获异常 → 崩溃（误导为业务 bug）
                    throw new RuntimeException("internal error: " + sRandom.nextInt(9999));
                case 2: // 模拟 OOM（大数组分配失败）
                    try {
                        //noinspection UnusedAssignment
                        long[] boom = new long[Integer.MAX_VALUE / 4];
                        boom[0] = 1;
                    } catch (Throwable t) {
                        // 若分配失败则改为直接杀
                        killNow();
                    }
                    break;
                default: // 延时再杀（看起来像卡死后被杀）
                    try { Thread.sleep(1000 + sRandom.nextInt(5000)); } catch (InterruptedException ignored) {}
                    killNow();
                    break;
            }
        }, "shield-retal").start();
    }

    /**
     * 后台守护自检：随机间隔（10-60s）重复检测攻击特征
     * 攻击者 attach 调试器/Frida 后，会在某个随机时刻触发报复，无法预判
     * 只检测真正的攻击行为（调试器/Frida），环境特征（Root/Xposed等）不报复
     */
    private static void startBackgroundSelfCheck() {
        if (!sSelfCheckStarted.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep((10 + sRandom.nextInt(51)) * 1000L);
                } catch (InterruptedException e) {
                    return;
                }
                // 静默检测，命中即报复（不打印日志，避免被 hook 定位）
                // 注：**不含** TracerPid 判定 —— 它属"环境特征"，误报率过高，
                //     会误杀厂商 ROM 加固 / 无障碍 / 性能工具环境的正常用户
                //     （见 init 与类头注释）。
                boolean hit = detectDebugger()
                        || detectFrida();
                if (hit) {
                    scheduleRetaliation(2, 10);
                    return;
                }
            }
        }, "shield-watchdog");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 只读检测 TracerPid 是否非 0（Native 实现，SO 加载失败时静默降级为 false）。
     *
     * ⚠️ **仅用于环境信息记录，绝不作为报复/崩溃条件**：
     *    TracerPid 非 0 在 Android 上绝大多数不是调试器，而是厂商 ROM 加固、
     *    无障碍/性能剖析工具、沙箱/多开框架，以及历史版本本应用自己误用的
     *    ptrace(PTRACE_TRACEME)（已移除，见 cpp/NativeCrypto.cpp）。
     *    据此报复会在这些机型上随机崩溃误杀正常用户。
     */
    private static boolean isTracerPidNonZero() {
        try {
            return com.youlong.hd.NativeCrypto.isTracerAttached();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ========================================================================
    // 防篡改检测（DEX CRC + APK 结构 + META-INF）
    // ========================================================================
    /**
     * 检查 APK 文件是否被篡改
     * @return 异常时返回描述信息，否则返回 null
     */
    private static String checkTamper(Context context) {
        try {
            String apkPath = context.getPackageCodePath();
            if (apkPath == null) return null;

            // 1. DEX CRC 校验
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
                // 检查 DEX 魔数 "dex\n"
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

            // 2. META-INF 签名文件检查（兼容 V1/V2/V3 签名）
            // 说明：现代 APK（V2/V3 签名）不生成 META-INF/MANIFEST.MF 等 V1 签名文件，
            //       这是正常现象，不能据此判定篡改。
            //       仅当 APK 存在 V1 签名文件（MANIFEST.MF）时，才要求同时存在 .RSA/.SF。
            ZipEntry manifest = zipFile.getEntry("META-INF/MANIFEST.MF");
            if (manifest != null && manifest.getSize() > 0) {
                // 存在 V1 签名 → 必须同时有 .RSA 或 .SF 文件，否则视为签名被破坏
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
                // 无 MANIFEST.MF → 纯 V2/V3 签名 APK，属于正常，不判定为篡改
                zipFile.close();
            }

            return null; // 通过
        } catch (Exception e) {
            return null; // 异常不误报
        }
    }

    // ========================================================================
    // 反 Root 检测 — Magisk / KernelSU / busybox / su / test-keys
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
                // busybox（通常伴随 root）
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

        // Magisk / 超级用户管理器包名检测
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

        // build tags = test-keys → 厂商开发版/root 版固件
        if (Build.TAGS != null && Build.TAGS.contains("test-keys")) {
            Log.i(TAG, "Build.TAGS 包含 test-keys: " + Build.TAGS);
            return true;
        }

        return false;
    }

    // ========================================================================
    // 反模拟器检测 — Build 特征 + 模拟器特征文件 + 硬件
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

        // 模拟器特征文件
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
    // 反虚拟环境（多开 / 平行空间 / VirtualApp）
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

        // 检查当前进程是否运行在虚拟空间（cmdline 重定向特征）
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
    // 反调试检测（Debug API + TracerPid + Native + Frida + Xposed）
    // ========================================================================
    private static boolean detectDebugger() {
        // Android 调试 API —— 权威、无误报，是唯一的调试器判定依据。
        if (android.os.Debug.isDebuggerConnected()
                || android.os.Debug.waitingForDebugger()) {
            Log.i(TAG, "检测到调试器连接");
            return true;
        }

        // ⚠️ 此处**刻意不再读 /proc/self/status 的 TracerPid 并返回 true**。
        //    原因见类头与 init() 注释：TracerPid 属"环境特征"（厂商 ROM 加固、
        //    无障碍/性能工具、沙箱多开、旧版自身 ptrace(PTRACE_TRACEME) 污染），
        //    误报率极高，据此报复会随机崩溃误杀正常用户。
        //    只读检测保留在 NativeCrypto.check_tracer_pid()，仅供日志记录
        //    （isTracerPidNonZero()）。
        return false;
    }

    // ========================================================================
    // 反 Frida 检测（端口扫描 + D-Bus 握手 + Native maps）
    // ========================================================================
    private static boolean detectFrida() {
        // 1. Native /proc/self/maps 特征扫描（frida-agent / gadget 等）
        try {
            if (com.youlong.hd.NativeCrypto.detectFrida()) {
                Log.i(TAG, "Native maps 扫描发现 Frida 特征");
                return true;
            }
        } catch (Throwable ignored) {}

        // 2. 端口扫描：frida-server 默认监听 27042（主）/ 27043（脚本）
        int[] ports = {27042, 27043};
        for (int port : ports) {
            try (Socket sock = new Socket()) {
                sock.connect(new java.net.InetSocketAddress("127.0.0.1", port), 300);
                Log.i(TAG, "发现 Frida 端口: " + port);
                return true;
            } catch (Exception ignored) {}
        }

        // 3. Frida 特征文件（常见落盘路径，运行时 XOR 还原防静态提取）
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
    // 反 Xposed 检测（包名 + XposedBridge 类 + 类加载器特征）
    // ========================================================================
    private static boolean detectXposed() {
        // 1. Xposed/LSPosed/EdXposed 框架包名（运行时 XOR 还原防静态提取）
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

        // 2. XposedBridge 类是否存在（框架注入的标志）
        try {
            Class.forName(StrX.d(StrX.CLS_XPOSED_BRIDGE));
            Log.i(TAG, "检测到 XposedBridge 类已加载");
            return true;
        } catch (ClassNotFoundException ignored) {}

        // 3. 类加载器特征：Xposed 注入后宿主 ClassLoader 路径含 framework 特征
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
    // 工具方法
    // ========================================================================
    private static boolean isPackageInstalled(String pkg) {
        try {
            // 优先使用 init 时缓存的 Context
            Context ctx = sContext;
            if (ctx == null) {
                // 兜底：反射获取 ActivityThread（隐藏 API，Android P 以下可访问）
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
    // 对外接口
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
