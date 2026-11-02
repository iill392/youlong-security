package com.youlong.redteam;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;

/**
 * 测试 App 自己持有的 ADB RSA key（不依赖 Stellar/Shizuku）。
 * 私钥存 SharedPreferences；第一次连接时手机会弹「允许 USB 调试」，用户批准后
 * adbd 会记住这把 key，后续直接可用。
 */
public final class AdbKeyPair {

    private static final String PREFS = "redteam_adb";
    private static final String KEY_PRIV = "rsa_priv_b64";
    private static final String KEY_NAME = "redteam@youlong";
    private static final BigInteger F4 = BigInteger.valueOf(65537);
    private static final int MODULUS_BYTES = 256;
    private static final int MODULUS_WORDS = MODULUS_BYTES / 4;
    private static final int ANDROID_PUBKEY_SIZE = 4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4;

    public final RSAPrivateKey privateKey;
    public final RSAPublicKey publicKey;
    public final byte[] adbPublicKey;
    public final String fingerprint;

    private AdbKeyPair(RSAPrivateKey priv, RSAPublicKey pub, byte[] adbPub, String fp) {
        this.privateKey = priv;
        this.publicKey = pub;
        this.adbPublicKey = adbPub;
        this.fingerprint = fp;
    }

    public static AdbKeyPair loadOrCreate(Context context) throws Exception {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String b64 = sp.getString(KEY_PRIV, "");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        RSAPrivateKey priv;
        if (b64 == null || b64.isEmpty()) {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();
            priv = (RSAPrivateKey) kp.getPrivate();
            sp.edit().putString(KEY_PRIV, Base64.encodeToString(kp.getPrivate().getEncoded(), Base64.NO_WRAP)).apply();
        } else {
            byte[] der = Base64.decode(b64, Base64.NO_WRAP);
            priv = (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(der));
        }
        RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(
                new RSAPublicKeySpec(priv.getModulus(), F4));
        byte[] adbPub = adbEncode(pub);
        String fp = fingerprint(pub.getEncoded());
        return new AdbKeyPair(priv, pub, adbPub, fp);
    }

    public byte[] sign(byte[] token) throws Exception {
        Signature sig = Signature.getInstance("SHA1withRSA");
        sig.initSign(privateKey);
        sig.update(token);
        return sig.sign();
    }

    private static byte[] adbEncode(RSAPublicKey pub) {
        BigInteger modulus = pub.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = modulus.remainder(r32).modInverse(r32).negate();
        BigInteger r = BigInteger.ONE.shiftLeft(MODULUS_BYTES * 8);
        BigInteger rr = r.modPow(BigInteger.valueOf(2), modulus);

        ByteBuffer buf = ByteBuffer.allocate(ANDROID_PUBKEY_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(MODULUS_WORDS);
        buf.putInt(n0inv.intValue());
        putWords(buf, modulus);
        putWords(buf, rr);
        buf.putInt(pub.getPublicExponent().intValue());

        String base64 = Base64.encodeToString(buf.array(), Base64.NO_WRAP);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] head = base64.getBytes(StandardCharsets.US_ASCII);
        out.write(head, 0, head.length);
        byte[] tail = (" " + KEY_NAME + "\0").getBytes(StandardCharsets.US_ASCII);
        out.write(tail, 0, tail.length);
        return out.toByteArray();
    }

    private static void putWords(ByteBuffer buf, BigInteger value) {
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger tmp = value.add(BigInteger.ZERO);
        for (int i = 0; i < MODULUS_WORDS; i++) {
            BigInteger[] divRem = tmp.divideAndRemainder(r32);
            tmp = divRem[0];
            buf.putInt(divRem[1].intValue());
        }
    }

    private static String fingerprint(byte[] encoded) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(encoded);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
                if (i < 7) sb.append(':');
            }
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
