package com.youlong.hd;

/**
 * 游龙 Native 加密核心 - Java 桥接层
 * =======================================================
 * 职责：将密钥派生与 AES-GCM 解密下沉到 native 层（SO）
 * 安全设计：
 *   - Java 层【不持有】任何种子密钥字节，只传签名指纹（动态）
 *   - 种子密钥在 SO 内以混淆常量存储，反编译拿不到明文
 *   - 密钥生命周期：native 内部派生 → 用后立即擦除
 *
 * 依赖：libnativecrypto.so（由 CMake 构建，随 APK 分发）
 */
public final class NativeCrypto {

    static {
        try {
            System.loadLibrary("nativecrypto");
        } catch (UnsatisfiedLinkError e) {
            // 极端情况下 SO 加载失败（被剥离/替换），静默处理
            // 上层调用时 isNativeAvailable() 返回 false，走纯 Java 兜底
        }
    }

    private NativeCrypto() {}

    /** Native 是否可用（SO 加载成功） */
    public static native boolean isNativeLoaded();

    /** Java 兜底用：从 native 读取混淆存储的种子密钥（16 字节） */
    public static native byte[] deriveSeedForFallback();

    /** Java 兜底用：从 native 读取混淆存储的 HMAC 盐（16 字节） */
    public static native byte[] deriveSaltForFallback();

    /**
     * 密钥派生：SHA-256(种子 + 盐 + 证书指纹) → 32 字节 AES-256 密钥
     * @param fingerprint 签名证书 SHA-256 指纹（32 字节），可为 null
     * @return 32 字节密钥；失败返回 null
     */
    public static native byte[] deriveKey(byte[] fingerprint);

    /**
     * AES-256-GCM 解密
     * @param iv 12 字节 IV
     * @param ciphertext 密文（含 16 字节 GCM tag）
     * @param fingerprint 签名证书指纹（32 字节），可为 null
     * @return 明文；认证失败或参数错误返回 null
     */
    public static native byte[] decrypt(byte[] iv, byte[] ciphertext, byte[] fingerprint);

    /**
     * 只读检测 TracerPid 是否非 0（/proc/self/status）。
     *
     * ⚠️ **仅用于环境信息记录，绝不作为报复/崩溃条件** —— TracerPid 非 0 在 Android 上
     *    多由厂商 ROM 加固 / 无障碍 / 性能工具 / 沙箱多开引起，误报极高。
     *    本 SO 已**不再**调用 ptrace(PTRACE_TRACEME)（那会让 TracerPid 被 zygote 污染，
     *    并因 zygote 不履行 tracer 职责而使进程永久 ptrace-stop → ANR）。
     *    详见 NativeCrypto.cpp 的"已移除"注释块。
     */
    public static native boolean isTracerAttached();

    /** 反注入：扫描 /proc/self/maps 检测 Frida/Xposed 特征 */
    public static native boolean detectFrida();

    /** 辅助：加载失败时 Java 层可感知 */
    static {
        // isNativeLoaded 由 native 实现
    }
}
