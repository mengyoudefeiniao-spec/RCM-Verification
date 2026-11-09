package iq.digitalration.faceverify.card;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * ICAO 9303 用到的密码学原语（全部走 JCE，不引第三方库）：
 * <ul>
 *   <li>3DES-CBC（BAC 的密钥/报文加密）</li>
 *   <li>ISO 9797-1 MAC algorithm 3（"retail MAC"，BAC 与 PACE-3DES 的 MAC）</li>
 *   <li>AES-CBC / AES-CMAC（PACE-AES 的 SM 与认证令牌）</li>
 *   <li>ICAO 的 KDF（SHA-1/SHA-256 截断）</li>
 *   <li>ISO 9797-1 padding method 2</li>
 * </ul>
 * CMAC 自己实现是因为 JDK/Android 都没有公开的 AES-CMAC（Conscrypt 内部有但不可用）。
 */
public final class Crypt {

    private Crypt() {}

    private static final SecureRandom RNG = new SecureRandom();

    // ---------------------------------------------------------------- 摘要 / 随机

    public static byte[] sha1(byte[] d) {
        return digest("SHA-1", d);
    }

    public static byte[] sha256(byte[] d) {
        return digest("SHA-256", d);
    }

    private static byte[] digest(String alg, byte[] d) {
        try {
            return MessageDigest.getInstance(alg).digest(d);
        } catch (Exception e) {
            throw new IllegalStateException(alg + " 不可用: " + e.getMessage(), e);
        }
    }

    public static byte[] random(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    // ---------------------------------------------------------------- 3DES

    /** 16 字节双倍长密钥 → 24 字节（K1||K2||K1）。 */
    public static byte[] tdesKey(byte[] k) {
        if (k.length == 24) return k;
        if (k.length != 16) throw new IllegalArgumentException("3DES key must be 16/24 bytes: " + k.length);
        byte[] o = new byte[24];
        System.arraycopy(k, 0, o, 0, 16);
        System.arraycopy(k, 0, o, 16, 8);
        return o;
    }

    /** 调整 DES 奇校验位（KDF 的输出必须过这一步，否则部分芯片 MAC 校验不过）。 */
    public static byte[] desParity(byte[] k) {
        byte[] o = k.clone();
        for (int i = 0; i < o.length; i++) {
            int v = o[i] & 0xFF;
            int ones = Integer.bitCount(v & 0xFE);
            o[i] = (byte) ((v & 0xFE) | ((ones & 1) == 0 ? 1 : 0));
        }
        return o;
    }

    public static byte[] desCbc(byte[] key16or24, byte[] iv, byte[] data, boolean encrypt) {
        return cbc("DESede/CBC/NoPadding", tdesKey(key16or24), iv, data, encrypt);
    }

    public static byte[] desEcbBlock(byte[] key8, byte[] block) {
        return cbc("DES/ECB/NoPadding", key8, null, block, true);
    }

    public static byte[] desBlockDecrypt(byte[] key8, byte[] block) {
        return cbc("DES/ECB/NoPadding", key8, null, block, false);
    }

    public static byte[] aesCbc(byte[] key, byte[] iv, byte[] data, boolean encrypt) {
        return cbc("AES/CBC/NoPadding", key, iv, data, encrypt);
    }

    public static byte[] aesEcbBlock(byte[] key, byte[] block) {
        return cbc("AES/ECB/NoPadding", key, null, block, true);
    }

    private static byte[] cbc(String alg, byte[] key, byte[] iv, byte[] data, boolean encrypt) {
        try {
            Cipher c = Cipher.getInstance(alg);
            SecretKeySpec ks = new SecretKeySpec(key, alg.startsWith("AES") ? "AES" : (alg.startsWith("DES/") ? "DES" : "DESede"));
            if (iv == null) c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, ks);
            else c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, ks, new IvParameterSpec(iv));
            return c.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(alg + " 失败: " + e.getMessage(), e);
        }
    }

    /** ISO 9797-1 MAC algorithm 3（retail MAC），输入必须 8 字节对齐。返回 8 字节。 */
    public static byte[] retailMac(byte[] key16, byte[] data) {
        if (key16.length != 16) throw new IllegalArgumentException("retail MAC 需要 16 字节密钥");
        if (data.length == 0 || data.length % 8 != 0) {
            throw new IllegalArgumentException("retail MAC 数据必须 8 字节对齐: " + data.length);
        }
        byte[] k1 = Arrays.copyOfRange(key16, 0, 8);
        byte[] k2 = Arrays.copyOfRange(key16, 8, 16);
        byte[] cbc = desCbc(key16, new byte[8], data, true);
        byte[] last = Arrays.copyOfRange(cbc, cbc.length - 8, cbc.length);
        byte[] back = desBlockDecrypt(k2, last);
        return desEcbBlock(k1, back);
    }

    /** ISO 9797-1 padding method 2：0x80 后补 0x00 到块对齐。 */
    public static byte[] pad(byte[] data, int blockSize) {
        int len = ((data.length / blockSize) + 1) * blockSize;
        byte[] o = new byte[len];
        System.arraycopy(data, 0, o, 0, data.length);
        o[data.length] = (byte) 0x80;
        return o;
    }

    public static byte[] unpad(byte[] data) {
        int i = data.length - 1;
        while (i >= 0 && data[i] == 0) i--;
        if (i < 0 || data[i] != (byte) 0x80) {
            throw new IllegalStateException("ISO9797 padding 缺失");
        }
        return Arrays.copyOf(data, i);
    }

    // ---------------------------------------------------------------- AES-CMAC

    private static byte[] cmacSubkey(byte[] l) {
        byte[] out = new byte[16];
        // 大端左移 1 位：byte i 的 LSB 来自 byte i+1 的 MSB（索引越大越低字节）
        for (int i = 0; i < 15; i++) {
            out[i] = (byte) (((l[i] << 1) & 0xFE) | ((l[i + 1] & 0x80) != 0 ? 1 : 0));
        }
        out[15] = (byte) (l[15] << 1);
        // 最高位溢出时，最低字节异或 Rb = 0x87
        if ((l[0] & 0x80) != 0) out[15] ^= 0x87;
        return out;
    }

    /** SP 800-38B CMAC，返回前 macLen 字节。 */
    public static byte[] aesCmac(byte[] key, byte[] data, int macLen) {
        byte[] l = aesEcbBlock(key, new byte[16]);
        byte[] k1 = cmacSubkey(l);
        byte[] k2 = cmacSubkey(k1);

        int n = (data.length + 15) / 16;
        boolean complete = data.length > 0 && data.length % 16 == 0;
        if (n == 0) { n = 1; complete = false; }

        byte[] x = new byte[16];
        for (int i = 0; i < n - 1; i++) {
            byte[] blk = new byte[16];
            for (int j = 0; j < 16; j++) blk[j] = (byte) (data[i * 16 + j] ^ x[j]);
            x = aesEcbBlock(key, blk);
        }

        byte[] last = new byte[16];
        int off = (n - 1) * 16;
        if (complete) {
            for (int j = 0; j < 16; j++) last[j] = (byte) (data[off + j] ^ k1[j] ^ x[j]);
        } else {
            int rem = data.length - off;
            byte[] padBlk = new byte[16];
            System.arraycopy(data, off, padBlk, 0, rem);
            padBlk[rem] = (byte) 0x80;
            for (int j = 0; j < 16; j++) last[j] = (byte) (padBlk[j] ^ k2[j] ^ x[j]);
        }
        byte[] full = aesEcbBlock(key, last);
        return Arrays.copyOf(full, macLen);
    }

    // ---------------------------------------------------------------- KDF

    /**
     * ICAO 9303-11 §9.7.1 / §9.7.3 的密钥派生：Hash(secret || counter32)，按算法取长度。
     * counter: 1=KSenc, 2=KSmac, 3=Kπ（PACE 的密码密钥）。
     */
    public static byte[] kdf(byte[] secret, int counter, boolean aes, int keyBits) {
        byte[] input = new byte[secret.length + 4];
        System.arraycopy(secret, 0, input, 0, secret.length);
        input[secret.length] = 0;
        input[secret.length + 1] = 0;
        input[secret.length + 2] = (byte) (counter >> 8);
        input[secret.length + 3] = (byte) counter;

        if (!aes) {
            if (keyBits != 112) throw new IllegalArgumentException("3DES 只支持 112 位: " + keyBits);
            return desParity(Arrays.copyOf(sha1(input), 16));
        }
        if (keyBits == 128) return Arrays.copyOf(sha1(input), 16);
        if (keyBits == 192) return Arrays.copyOf(sha256(input), 24);
        if (keyBits == 256) return sha256(input);
        throw new IllegalArgumentException("AES 位数不支持: " + keyBits);
    }

    public static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] o = new byte[n];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, o, off, p.length);
            off += p.length;
        }
        return o;
    }
}
