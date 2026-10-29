package com.tv.remotemouse.v2;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * adb 认证用的 RSA 密钥对。
 *
 * 优先使用 assets 内置的密钥（= 电脑上已被电视信任的那把），这样永远不会再弹授权框。
 * 本 ROM 的 adbd 对"运行期新批准的密钥"持久化不可靠，自生成密钥每次都要人工确认。
 *
 * 注意：Android 的 OpenSSLRSAPrivateKey 不实现 RSAPrivateCrtKey，
 * 所以不能从私钥推导公钥，公钥直接用 assets/adbkey.pub 里的原始块。
 */
final class AdbKey {

    private static final String PREF = "adbkey";
    private static final String NAME = "remotemouse@tv";

    private final KeyPair kp;
    private final byte[] embeddedPayload;   // 非空表示用的是内置密钥

    AdbKey(Context ctx) throws Exception {
        byte[] payload = null;
        KeyPair pair = null;
        try {
            pair = loadPrivate(ctx);
            payload = loadPublicBlob(ctx);
        } catch (Throwable ignored) {
            pair = null;
            payload = null;
        }

        if (pair != null && payload != null) {
            kp = pair;
            embeddedPayload = payload;
            return;
        }

        embeddedPayload = null;
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String priv = sp.getString("priv", null);
        String pub = sp.getString("pub", null);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv != null && pub != null) {
            PrivateKey sk = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(priv, Base64.NO_WRAP)));
            java.security.PublicKey pk = kf.generatePublic(
                    new java.security.spec.X509EncodedKeySpec(Base64.decode(pub, Base64.NO_WRAP)));
            kp = new KeyPair(pk, sk);
        } else {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            kp = g.generateKeyPair();
            sp.edit()
                    .putString("priv", Base64.encodeToString(kp.getPrivate().getEncoded(), Base64.NO_WRAP))
                    .putString("pub", Base64.encodeToString(kp.getPublic().getEncoded(), Base64.NO_WRAP))
                    .apply();
        }
    }

    private AdbKey(KeyPair pair, byte[] payload) {
        this.kp = pair;
        this.embeddedPayload = payload;
    }

    static String diag(Context ctx) {
        try {
            KeyPair pair = loadPrivate(ctx);
            byte[] payload = loadPublicBlob(ctx);
            String fp = fingerprint(payload);
            return "内置密钥 OK 指纹=" + fp;
        } catch (Throwable t) {
            return "内置密钥失败: " + t.getClass().getSimpleName() + " " + t.getMessage();
        }
    }

    /**
     * 对 adbd 发来的 20 字节 token 签名。
     *
     * 关键：adb 的约定是把 token **直接当作 SHA-1 摘要**塞进 DigestInfo，
     * 而不是对 token 再做一次 SHA-1（实测逐字节比对 adb 签名得出）。
     * 所以这里手工拼 PKCS#1 v1.5 结构再 RSA 加密，不能用 "SHA1withRSA"。
     */
    byte[] sign(byte[] token) throws Exception {
        byte[] prefix = {
                0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
                0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        };
        byte[] info = new byte[prefix.length + token.length];
        System.arraycopy(prefix, 0, info, 0, prefix.length);
        System.arraycopy(token, 0, info, prefix.length, token.length);

        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, kp.getPrivate());
        return c.doFinal(info);
    }

    /** AUTH RSAPUBLICKEY 载荷：base64(RSAPublicKey 结构体) + " 用户名" + NUL */
    byte[] publicKeyPayload() {
        if (embeddedPayload != null) {
            byte[] out = new byte[embeddedPayload.length + 1];
            System.arraycopy(embeddedPayload, 0, out, 0, embeddedPayload.length);
            out[embeddedPayload.length] = 0;
            return out;
        }
        return buildPayload((RSAPrivateKey) kp.getPrivate());
    }

    private static byte[] buildPayload(RSAPrivateKey priv) {
        BigInteger n = priv.getModulus();
        BigInteger e = BigInteger.valueOf(65537);

        final int MOD = 256;
        byte[] mod = toLE(n, MOD);
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.modInverse(r32).negate().mod(r32);
        BigInteger R = BigInteger.ONE.shiftLeft(MOD * 8);
        BigInteger rr = R.multiply(R).mod(n);

        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + MOD + MOD + 4).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(64);
        buf.putInt(n0inv.intValue());
        buf.put(mod);
        buf.put(toLE(rr, MOD));
        buf.putInt(e.intValue());

        String b64 = Base64.encodeToString(buf.array(), Base64.NO_WRAP);
        byte[] s = (b64 + " " + NAME).getBytes();
        byte[] out = new byte[s.length + 1];
        System.arraycopy(s, 0, out, 0, s.length);
        out[s.length] = 0;
        return out;
    }

    private static KeyPair loadPrivate(Context ctx) throws Exception {
        String pem = readAsset(ctx, "adbkey")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.decode(pem, Base64.DEFAULT);
        PrivateKey sk = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        return new KeyPair(null, sk);
    }

    private static byte[] loadPublicBlob(Context ctx) throws Exception {
        String s = readAsset(ctx, "adbkey.pub").trim();
        return s.getBytes("UTF-8");   // 已含 " 用户名"，直接当载荷用
    }

    private static String readAsset(Context ctx, String name) throws Exception {
        InputStream is = ctx.getAssets().open(name);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static String fingerprint(byte[] payload) {
        try {
            String b64 = new String(payload, "UTF-8").trim().split(" ")[0];
            byte[] blob = Base64.decode(b64, Base64.DEFAULT);
            byte[] md5 = java.security.MessageDigest.getInstance("MD5").digest(blob);
            StringBuilder sb = new StringBuilder();
            for (byte b : md5) {
                if (sb.length() > 0) sb.append(':');
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static byte[] toLE(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[len];
        int n = Math.min(be.length, len);
        for (int i = 0; i < n; i++) out[i] = be[be.length - 1 - i];
        return out;
    }
}
