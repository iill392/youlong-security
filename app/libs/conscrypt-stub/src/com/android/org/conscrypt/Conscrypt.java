package com.android.org.conscrypt;

import java.net.Socket;
import javax.net.ssl.SSLSocket;

/**
 * 编译期桩类 —— 只用于让 Kotlin/Java 代码能编译通过，不会被打进 APK。
 *
 * 原因：ADB 无线调试配对需要 RFC 5705 的 TLS keying material 导出：
 *     Conscrypt.exportKeyingMaterial(socket, "adb-label\0", null, 64)
 * JSSE 至今没有公开这个能力，Android 上只有隐藏类
 *     com.android.org.conscrypt.Conscrypt
 * 提供它。而 android.jar（API 34）里并不包含这个 @hide 类，
 * 所以需要一个 pure-compile 的桩顶住编译；运行期用的仍然是系统
 * boot classpath 里的真实实现（Android 10+ 均有，Shizuku 也是这么调的）。
 *
 * 不要把这个类打进 APK：
 *   - 本目录的 jar 只声明在 compileOnly 依赖里；
 *   - 打包后请确认 dex 中不存在 com/android/org/conscrypt/Conscrypt。
 */
public final class Conscrypt {

    private Conscrypt() {
    }

    /** 对应系统隐藏 API：SSLSocket -> keying material 字节数组 */
    public static byte[] exportKeyingMaterial(SSLSocket socket, String label, byte[] context, int length) {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public static boolean isConscrypt(Socket socket) {
        throw new UnsupportedOperationException("compile-time stub");
    }
}
