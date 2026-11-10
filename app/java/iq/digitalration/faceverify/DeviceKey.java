package iq.digitalration.faceverify;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 设备密钥：结果签名（TV-04）、终端回执（TV-11）、离线库加密（TV-09）、动态码密钥（TV-08）的地基。
 *
 * <p>签名用的 EC P-256 私钥**只存在于 Android Keystore**，生成时带 attestation challenge，
 * 证书链里能拿到硬件证明（是否真由 TEE/StrongBox 保护取决于机型和系统）。
 * 本类只保证"这台设备能签名、别人改不了"；改包/重放能不能放行，还要靠服务端验签。
 */
public final class DeviceKey {

    public static final String SIG_ALIAS = "rcm.lab.sig";
    /** 每次签名都要过一次生物识别（CryptoObject）。 */
    public static final String BIO_ALIAS = "rcm.lab.bio";
    // 说明：public SDK 里 KeyProperties 只有 AUTH_BIOMETRIC_STRONG / AUTH_DEVICE_CREDENTIAL，
    // 没有 AUTH_BIOMETRIC_WEAK —— 也就是说「2D 人脸」无法用于解锁 Keystore 密钥。
    // 想让 2D 人脸也能"登录"，只能走不绑定密钥的那条路（见 BioAuth.authenticateWeak）。
    /** 锁屏确认后 30s 内可用（API 24-27 兜底）。 */
    public static final String BIO_TIMED_ALIAS = "rcm.lab.bio.t";
    public static final String HMAC_ALIAS = "rcm.lab.hmac";
    public static final String AES_ALIAS = "rcm.lab.aes";

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final byte[] CHALLENGE = "rcm-lab-attest-v1".getBytes(UTF8);

    private DeviceKey() {}

    private static KeyStore ks() throws Exception {
        KeyStore k = KeyStore.getInstance("AndroidKeyStore");
        k.load(null);
        return k;
    }

    // ------------------------------------------------------------ 签名密钥

    public static synchronized KeyPair ensureSigningKey() throws Exception {
        KeyStore k = ks();
        if (k.containsAlias(SIG_ALIAS)) {
            Certificate c = k.getCertificate(SIG_ALIAS);
            if (c != null) return new KeyPair(c.getPublicKey(), (PrivateKey) k.getKey(SIG_ALIAS, null));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(SIG_ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge(CHALLENGE);
        g.initialize(b.build());
        return g.generateKeyPair();
    }

    public static byte[] sign(byte[] data) throws Exception {
        KeyPair kp = ensureSigningKey();
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(kp.getPrivate());
        s.update(data);
        return s.sign();
    }

    public static boolean verify(byte[] data, byte[] sig) {
        try {
            KeyPair kp = ensureSigningKey();
            return verify(data, sig, kp.getPublic());
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean verify(byte[] data, byte[] sig, PublicKey pub) {
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(pub);
            s.update(data);
            return s.verify(sig);
        } catch (Throwable t) {
            return false;
        }
    }

    public static Certificate[] chain() {
        try {
            return ks().getCertificateChain(SIG_ALIAS);
        } catch (Throwable t) {
            return null;
        }
    }

    public static PublicKey publicKey() {
        try {
            return ensureSigningKey().getPublic();
        } catch (Throwable t) {
            return null;
        }
    }

    public static String publicSha256() {
        try {
            KeyPair kp = ensureSigningKey();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(kp.getPublic().getEncoded()));
        } catch (Throwable t) {
            return "";
        }
    }

    public static JSONObject describe() {
        JSONObject o = new JSONObject();
        try {
            KeyStore k = ks();
            boolean exists = k.containsAlias(SIG_ALIAS);
            o.put("alias", SIG_ALIAS);
            o.put("exists", exists);
            if (!exists) return o;
            Certificate cert = k.getCertificate(SIG_ALIAS);
            PublicKey pub = cert == null ? null : cert.getPublicKey();
            o.put("algorithm", pub == null ? "" : pub.getAlgorithm());
            o.put("publicSha256", publicSha256());
            Certificate[] ch = chain();
            o.put("chainLength", ch == null ? 0 : ch.length);
            o.put("hardwareAttested", ch != null && ch.length > 1);
            if (ch != null && ch.length > 0 && ch[0] instanceof X509Certificate) {
                X509Certificate leaf = (X509Certificate) ch[0];
                o.put("subject", leaf.getSubjectDN().getName());
                o.put("issuer", leaf.getIssuerDN().getName());
                o.put("notBefore", DeviceInfo.fmt(leaf.getNotBefore()));
                o.put("notAfter", DeviceInfo.fmt(leaf.getNotAfter()));
                if (ch.length > 1) {
                    X509Certificate att = (X509Certificate) ch[ch.length - 1];
                    o.put("attestationIssuer", att.getIssuerDN().getName());
                }
            }
            o.put("securityLevel", securityLevel(k));
        } catch (Throwable t) {
            try {
                o.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            } catch (Throwable ignored) {
            }
        }
        return o;
    }

    /** SECURITY_LEVEL 要 API 31；低版本只报 unknown。 */
    private static String securityLevel(KeyStore k) {
        try {
            PrivateKey pk = (PrivateKey) k.getKey(SIG_ALIAS, null);
            KeyFactory kf = KeyFactory.getInstance(pk.getAlgorithm(), "AndroidKeyStore");
            KeyInfo info = kf.getKeySpec(pk, KeyInfo.class);
            if (Build.VERSION.SDK_INT >= 31) {
                int lv = info.getSecurityLevel();
                if (lv == KeyProperties.SECURITY_LEVEL_STRONGBOX) return "StrongBox";
                if (lv == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT) return "TEE";
                if (lv == KeyProperties.SECURITY_LEVEL_SOFTWARE) return "Software";
                return "unknown(" + lv + ")";
            }
            return "unknown(API<31)";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    // ------------------------------------------------------------ 生物识别绑定密钥

    public static synchronized KeyPair ensureBiometricKey() throws Exception {
        KeyStore k = ks();
        if (k.containsAlias(BIO_ALIAS)) {
            Certificate c = k.getCertificate(BIO_ALIAS);
            if (c != null) return new KeyPair(c.getPublicKey(), (PrivateKey) k.getKey(BIO_ALIAS, null));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(BIO_ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true);
        if (Build.VERSION.SDK_INT >= 30) {
            b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
        } else {
            b.setUserAuthenticationValidityDurationSeconds(-1);
        }
        g.initialize(b.build());
        return g.generateKeyPair();
    }

    public static synchronized KeyPair ensureTimedAuthKey() throws Exception {
        KeyStore k = ks();
        if (k.containsAlias(BIO_TIMED_ALIAS)) {
            Certificate c = k.getCertificate(BIO_TIMED_ALIAS);
            if (c != null) return new KeyPair(c.getPublicKey(), (PrivateKey) k.getKey(BIO_TIMED_ALIAS, null));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(BIO_TIMED_ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationValidityDurationSeconds(30)
                .setInvalidatedByBiometricEnrollment(true);
        g.initialize(b.build());
        return g.generateKeyPair();
    }

    public static boolean hasBiometricKey() {
        try {
            return ks().containsAlias(BIO_ALIAS);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasTimedKey() {
        try {
            return ks().containsAlias(BIO_TIMED_ALIAS);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void deleteBiometricKey() {
        deleteAlias(BIO_ALIAS);
    }

    /** 删掉某个别名（用于吊销 HMAC 密钥 / 重置生物密钥）。 */
    public static void deleteAlias(String alias) {
        try {
            KeyStore k = ks();
            if (k.containsAlias(alias)) k.deleteEntry(alias);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ HMAC / AES

    public static synchronized SecretKey hmacKey() throws Exception {
        KeyStore k = ks();
        if (k.containsAlias(HMAC_ALIAS)) return (SecretKey) k.getKey(HMAC_ALIAS, null);
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(HMAC_ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY).build());
        return g.generateKey();
    }

    public static byte[] hmac(byte[] data) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(hmacKey());
        return m.doFinal(data);
    }

    public static synchronized SecretKey aesKey() throws Exception {
        KeyStore k = ks();
        if (k.containsAlias(AES_ALIAS)) return (SecretKey) k.getKey(AES_ALIAS, null);
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(AES_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }

    /** AES-GCM 加密：输出 = 12 字节 IV || 密文 || tag。 */
    public static byte[] seal(byte[] plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, aesKey());
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(plain);
        ByteArrayOutputStream bo = new ByteArrayOutputStream(iv.length + ct.length);
        bo.write(iv);
        bo.write(ct);
        return bo.toByteArray();
    }

    public static byte[] open(byte[] sealed) throws Exception {
        if (sealed == null || sealed.length < 13) throw new IllegalArgumentException("blob too short");
        byte[] iv = new byte[12];
        System.arraycopy(sealed, 0, iv, 0, 12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, aesKey(), new GCMParameterSpec(128, iv));
        return c.doFinal(sealed, 12, sealed.length - 12);
    }

    // ------------------------------------------------------------ 小工具

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }

    public static String sha256Hex(byte[] b) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Throwable t) {
            return "";
        }
    }

    public static String b64(byte[] b) {
        return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
    }

    public static byte[] unb64(String s) {
        return android.util.Base64.decode(s, android.util.Base64.NO_WRAP);
    }
}
