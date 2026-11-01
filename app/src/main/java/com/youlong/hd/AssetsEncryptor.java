package com.youlong.hd;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 游龙超级加密引擎 v4.0 - YouLong Super Encryption Engine
 * =======================================================
 * ✓ AES-256-GCM 认证加密（较 AES-CBC 更安全，自带完整性校验）
 * ✓ 签名绑定密钥（核心升级）：密钥 = KDF(硬编码种子 + 签名证书指纹)
 *    构建时用官方 jks 证书指纹加密；运行时用 APK 实际签名派生密钥
 *    重签名 / 盗版篡改 → 指纹不同 → 密钥不同 → GCM 认证失败 → 解密即闪退
 * ✓ 【v4.0】Native 密钥下沉：种子密钥/密钥派生/AES-GCM 解密核心
 *    全部移入 libnativecrypto.so（C++），Java 层【不再持有任何密钥字节】
 *    jadx 反编译 Java 层只能看到 NativeCrypto JNI 调用，拿不到密钥
 * ✓ 防静态提取：即使反编译拿到 SO 混淆常量，没有官方签名证书也无法解密
 * ✓ 全内存解密，不落盘
 */
public class AssetsEncryptor {

    // AES-GCM 参数（GCM 自带认证标签，不需要额外 HMAC）
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128; // 16 字节认证标签
    private static final int IV_LENGTH = 12;       // GCM 推荐 12 字节 IV
    private static final int KEY_LENGTH = 32;      // AES-256 = 32 字节

    // 运行时签名证书指纹（由 Application 启动时注入，来自 APK 签名）
    private static volatile byte[] sCertFingerprint = null;

    // ========================================================================
    // 签名指纹注入：必须在任何解密之前调用（YouLongApp / MainActivity）
    // 若被攻击者绕过（不调用），则密钥退化为种子密钥 → 与构建期密钥不一致
    // → GCM 认证必然失败 → 解密抛异常 → 闪退。这是「签名绑定」的兜底。
    // ========================================================================
    public static void setSignatureFingerprint(byte[] certSha256) {
        sCertFingerprint = certSha256;
    }

    /**
     * 是否有有效签名指纹（供上层判断是否已完成签名绑定）
     */
    public static boolean hasSignatureFingerprint() {
        byte[] fp = sCertFingerprint;
        return fp != null && fp.length == 32;
    }

    // ========================================================================
    // 密钥派生函数：KDF(种子 + HMAC盐 + 签名证书指纹) → AES-256 密钥
    // v4.0: 优先走 Native（种子密钥在 SO 内混淆存储）
    //       仅当 Native 不可用（极端情况）才用 Java 兜底
    // ========================================================================
    private static byte[] deriveKey() throws Exception {
        byte[] fp = sCertFingerprint;
        if (NativeCrypto.isNativeLoaded()) {
            byte[] nativeKey = NativeCrypto.deriveKey(fp);
            if (nativeKey != null && nativeKey.length == KEY_LENGTH) {
                return nativeKey;
            }
        }
        // Java 兜底（与构建期 KDF 完全一致；仅 SO 被剥离时触发）
        return javaFallbackDeriveKey(fp);
    }

    // ========================================================================
    // Java 兜底密钥派生（保持与构建期 / native 一致）
    // 注：此路径仅在 Native SO 无法加载时启用，密钥字节仅在内存瞬时存在
    // ========================================================================
    private static byte[] javaFallbackDeriveKey(byte[] fp) throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        int fpLen = (fp != null) ? fp.length : 0;
        byte[] combined = new byte[16 + 16 + fpLen];
        // 种子与盐已从 native 移除，此处通过内置 JNI 常量等价序列重建
        // （安全考虑：本类不硬编码种子，避免反编译直接提取；由 native 提供）
        byte[] seed = NativeCrypto.deriveSeedForFallback();
        byte[] salt = NativeCrypto.deriveSaltForFallback();
        System.arraycopy(seed, 0, combined, 0, 16);
        System.arraycopy(salt, 0, combined, 16, 16);
        if (fp != null && fp.length > 0) {
            System.arraycopy(fp, 0, combined, 32, fp.length);
        }
        byte[] key = sha256.digest(combined);
        Arrays.fill(seed, (byte) 0);
        Arrays.fill(salt, (byte) 0);
        return key;
    }

    // ========================================================================
    // 加密：AES-256-GCM（格式：IV(12) + 密文 + GCM 认证标签(16)）
    // 注：运行时无调用（仅构建期 Gradle 任务加密），保留供工具/测试使用
    // ========================================================================
    public static byte[] encrypt(byte[] data) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);

        byte[] key = deriveKey();
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
        byte[] encrypted = cipher.doFinal(data);

        // 清除密钥
        Arrays.fill(key, (byte) 0);

        // 格式：IV(12) + 密文(含 GCM 认证标签)
        byte[] result = new byte[IV_LENGTH + encrypted.length];
        System.arraycopy(iv, 0, result, 0, IV_LENGTH);
        System.arraycopy(encrypted, 0, result, IV_LENGTH, encrypted.length);
        return result;
    }

    // ========================================================================
    // 解密：AES-256-GCM（GCM 自动验证完整性，无效则抛异常）
    // v4.0: 优先 Native 解密（密钥不出 SO，解密核心在 C++）
    // ========================================================================
    public static byte[] decrypt(byte[] data) throws Exception {
        if (data.length <= IV_LENGTH + GCM_TAG_LENGTH / 8) {
            throw new IllegalArgumentException("data too short or corrupted");
        }

        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(data, 0, iv, 0, IV_LENGTH);
        byte[] encrypted = new byte[data.length - IV_LENGTH];
        System.arraycopy(data, IV_LENGTH, encrypted, 0, encrypted.length);

        // Native 解密（密钥在 SO 内派生，不在 Java 层出现）
        if (NativeCrypto.isNativeLoaded()) {
            byte[] decrypted = NativeCrypto.decrypt(iv, encrypted, sCertFingerprint);
            if (decrypted != null) {
                return decrypted;
            }
            // Native 解密失败（认证失败/参数错误）→ 抛异常（防静默）
            throw new IllegalArgumentException("native decrypt failed");
        }

        // Java 兜底解密
        byte[] key = deriveKey();
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);
        byte[] decrypted = cipher.doFinal(encrypted);

        // 清除密钥
        Arrays.fill(key, (byte) 0);

        return decrypted;
    }

    // ========================================================================
    // 工具方法
    // ========================================================================
    public static byte[] readAllBytes(InputStream is) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[8192];
        int n;
        while ((n = is.read(data)) != -1) {
            buffer.write(data, 0, n);
        }
        buffer.flush();
        return buffer.toByteArray();
    }

    public static String guessMimeType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".js")) return "application/javascript";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".ttf")) return "font/ttf";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".xml")) return "text/xml";
        if (lower.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }
}