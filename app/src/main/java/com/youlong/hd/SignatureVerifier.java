package com.youlong.hd;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.security.MessageDigest;

/**
 * 签名验证器 — 防篡改核心
 * 在 App 启动时校验当前 APK 签名是否与官方一致
 * 
 * 加固策略：
 * - 官方签名 SHA-256 被拆分为多个片段，运行时通过 XOR 重建
 * - 防止 hex 编辑器和 grep 直接搜索到完整哈希
 * - 多重校验：Application 入口 + PiracyWarningActivity 自检
 */
public class SignatureVerifier {

    // ====== 官方签名密钥密码：youlong123 ======
    // ====== 密钥别名：youlong_release ======
    // ====== 签名文件：app-release.jks ======

    /**
     * 验证当前 APK 签名是否匹配官方签名
     * @return true=签名一致（正版）, false=签名不一致（疑似盗版）
     */
    public static boolean verify(Context ctx) {
        try {
            byte[] certBytes = getCertificateBytes(ctx);
            if (certBytes == null) return false;

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(certBytes);
            String hex = bytesToHex(digest);
            
            // 通过分段重建获取预期哈希，防止直接搜索
            String expected = rebuildExpectedHash();
            return expected.equals(hex);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 分段存储 + 运行时重建预期签名哈希
     * 将完整哈希拆分为 8 段，通过拼接重建
     * 防止攻击者通过 hex dump 或 grep 直接定位完整哈希字符串
     */
    private static String rebuildExpectedHash() {
        // 官方 SHA-256: D3D868C90DFCDFE45FFA6891EA8F2E2EB6CEB49BC63EEA5FD1AAD54C5D46AE2C
        // 拆分为 8 段，每段用不同密钥 XOR 编码存储
        String[] p = new String[8];
        p[0] = x("\u0006q\u0006ztz\u0001{", 0x42);   // D3D868C9
        p[1] = x("\u0011egbegd\u0015", 0x21);          // 0DFCDFE4
        p[2] = x("&UUR%+*\"", 0x13);                   // 5FFA6891
        p[3] = x("\u0010\u0014m\u0013g\u0010g\u0010", 0x55); // EA8F2E2E
        p[4] = x("s\u0007rts\u0005\u0008s", 0x31);     // B6CEB49B
        p[5] = x("4AD226B1", 0x77);                    // C63EEA5F
        p[6] = x("N;KKN?>I", 0x0A);                    // D1AAD54C
        p[7] = x("5D46AE2C", 0x00);                    // 5D46AE2C
        
        StringBuilder sb = new StringBuilder();
        for (String s : p) {
            sb.append(s);
        }
        return sb.toString();
    }

    /**
     * XOR 解码辅助函数
     * 对字符串每个字符与 key 做 XOR 来隐藏真正的哈希片段
     */
    private static String x(String encoded, int key) {
        char[] chars = encoded.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            chars[i] = (char) (chars[i] ^ key);
        }
        return new String(chars);
    }

    /** 获取 APK 签名证书字节 */
    private static byte[] getCertificateBytes(Context ctx) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(
                    ctx.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.signingInfo == null) return null;
            Signature[] sigs = info.signingInfo.getSigningCertificateHistory();
            if (sigs == null || sigs.length == 0) return null;
            return sigs[0].toByteArray();
        } else {
            @SuppressWarnings("deprecation")
            PackageInfo info = ctx.getPackageManager().getPackageInfo(
                    ctx.getPackageName(), PackageManager.GET_SIGNATURES);
            if (info.signatures == null || info.signatures.length == 0) return null;
            return info.signatures[0].toByteArray();
        }
    }

    /**
     * 获取签名证书的 SHA-256 指纹（32 字节）
     * 与构建期从 jks 读取的证书指纹完全一致 → 用于绑定加密密钥
     * @return 32 字节指纹；失败返回 null
     */
    public static byte[] getCertSha256Fingerprint(Context ctx) {
        try {
            byte[] certBytes = getCertificateBytes(ctx);
            if (certBytes == null) return null;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(certBytes);
        } catch (Exception e) {
            return null;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }
}
