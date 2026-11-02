package com.youlong.hd;

/**
 * 哨兵守护 Native 桥接层
 *
 * 通过 libnativecrypto.so 内的 fork() 子进程实时监视主进程与哨兵进程存活：
 * 发现兄弟进程死亡 → 立即写入标记文件（sentinel_main_dead / sentinel_guard_dead），
 * Java 层（ForegroundService / ProtectService）轮询标记核实后拉起对方。
 *
 * 说明：JNI 方法由 JNI_OnLoad 动态注册（RegisterNatives），
 *       类名与方法名必须保持（proguard 已 keep）。
 */
public final class GuardNative {

    private static volatile boolean sLoaded = false;
    private static final Object sLoadLock = new Object();

    private GuardNative() {}

    private static void ensureLoaded() {
        if (sLoaded) return;
        synchronized (sLoadLock) {
            if (sLoaded) return;
            try {
                System.loadLibrary("nativecrypto");
            } catch (Throwable ignored) {
                // SO 加载失败（极端情况），哨兵 native 层不可用，
                // Java 层双进程守护（ActivityManager 检查 + TIME_TICK）仍可工作
            }
            sLoaded = true;
        }
    }

    /**
     * 启动 Native 哨兵（fork 子进程）
     *
     * @param mainPid   主进程 PID（com.youlong.hd，不含 :guard 后缀），未知传 -1
     * @param guardPid  哨兵进程自身 PID（com.youlong.hd:guard）
     * @param signalDir 标记文件目录（getFilesDir()）
     * @return 哨兵子进程 PID，失败返回 -1
     */
    public static int startSentinel(int mainPid, int guardPid, String signalDir) {
        ensureLoaded();
        try {
            return nativeStartSentinel(mainPid, guardPid, signalDir);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 停止 Native 哨兵（杀子进程） */
    public static void stopSentinel() {
        ensureLoaded();
        try {
            nativeStopSentinel();
        } catch (Throwable ignored) {}
    }

    private static native int nativeStartSentinel(int mainPid, int guardPid, String signalDir);

    private static native void nativeStopSentinel();
}
