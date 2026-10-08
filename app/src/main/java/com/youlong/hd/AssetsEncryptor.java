package com.youlong.hd;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;


public class AssetsEncryptor {

    
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128; 
    private static final int IV_LENGTH = 12;       
    private static final int KEY_LENGTH = 32;      

    
    private static volatile byte[] sCertFingerprint = null;

    // ========================================================================
    
    
    
    // ========================================================================
    public static void setSignatureFingerprint(byte[] certSha256) {
        sCertFingerprint = certSha256;
    }

    
    public static boolean hasSignatureFingerprint() {
        byte[] fp = sCertFingerprint;
        return fp != null && fp.length == 32;
    }

    // ========================================================================
    
    
    
    // ========================================================================
    private static byte[] deriveKey() throws Exception {
        byte[] fp = sCertFingerprint;
        if (NativeCrypto.isNativeLoaded()) {
            byte[] nativeKey = NativeCrypto.deriveKey(fp);
            if (nativeKey != null && nativeKey.length == KEY_LENGTH) {
                return nativeKey;
            }
        }
        
        return javaFallbackDeriveKey(fp);
    }

    // ========================================================================
    
    // 与 native 侧 crypto_core.h 保持一致的掩码派生（开源版密钥材料公开，
    // 此处仅作为 native 库缺失时的同值回退，不构成保密）：
    //   mask_byte(i) = MASK_ORIGIN[i] ^ MASK_ORIGIN[(i+7)%16] ^ 0x3D
    //   seed/salt   = 全零 CIPHER 数组 ^ mask_byte(i) = mask_byte(i)
    // ========================================================================
    private static final byte[] MASK_ORIGIN = {
            (byte) 0x5A, (byte) 0x3C, (byte) 0xF1, (byte) 0x27,
            (byte) 0x8E, (byte) 0x4B, (byte) 0xD6, (byte) 0x0F,
            (byte) 0x39, (byte) 0xA8, (byte) 0x7E, (byte) 0xC4,
            (byte) 0x15, (byte) 0x92, (byte) 0x6D, (byte) 0xB3
    };

    private static byte[] deriveSeedSalt() {
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            out[i] = (byte) (MASK_ORIGIN[i] ^ MASK_ORIGIN[(i + 7) % 16] ^ 0x3D);
        }
        return out;
    }

    // ========================================================================
    
    // ========================================================================
    private static byte[] javaFallbackDeriveKey(byte[] fp) throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        int fpLen = (fp != null) ? fp.length : 0;
        byte[] combined = new byte[16 + 16 + fpLen];

        byte[] seed = deriveSeedSalt();
        byte[] salt = deriveSeedSalt();
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

        
        Arrays.fill(key, (byte) 0);

        
        byte[] result = new byte[IV_LENGTH + encrypted.length];
        System.arraycopy(iv, 0, result, 0, IV_LENGTH);
        System.arraycopy(encrypted, 0, result, IV_LENGTH, encrypted.length);
        return result;
    }

    // ========================================================================
    
    
    // ========================================================================
    public static byte[] decrypt(byte[] data) throws Exception {
        if (data.length <= IV_LENGTH + GCM_TAG_LENGTH / 8) {
            throw new IllegalArgumentException("data too short or corrupted");
        }

        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(data, 0, iv, 0, IV_LENGTH);
        byte[] encrypted = new byte[data.length - IV_LENGTH];
        System.arraycopy(data, IV_LENGTH, encrypted, 0, encrypted.length);

        
        if (NativeCrypto.isNativeLoaded()) {
            byte[] decrypted = NativeCrypto.decrypt(iv, encrypted, sCertFingerprint);
            if (decrypted != null) {
                return decrypted;
            }
            // native 解密失败（如签名指纹变化）：回退 Java 实现
        }

        
        byte[] key = deriveKey();
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);
        byte[] decrypted = cipher.doFinal(encrypted);

        
        Arrays.fill(key, (byte) 0);

        return decrypted;
    }

    // ========================================================================
    
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