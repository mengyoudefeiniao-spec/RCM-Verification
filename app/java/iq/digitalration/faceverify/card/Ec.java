package iq.digitalration.faceverify.card;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * 短 Weierstrass 曲线（y² = x³ + ax + b over GF(p)）的点运算，供 PACE 的 ECDH 使用。
 *
 * <p>为什么不用 java.security 的 EC：Android 的 Conscrypt 和 JDK 的 SunEC 对 brainpool 的
 * 支持不一致，而身份证/护照芯片最常用的就是 brainpoolP256r1。这里与 {@link EcCurve} 的常量
 * 配套，任何平台都能算。
 *
 * <p>性能：仿射坐标 + BigInteger.modInverse，256 位曲线一次标量乘约 3~8ms（手机更慢但只用 4 次），
 * 校验性比 Jacobian 简单得多，验证阶段优先要"算得对"。
 */
public final class Ec {

    private Ec() {}

    public static final class Point {
        public final BigInteger x, y;

        public Point(BigInteger x, BigInteger y) {
            this.x = x;
            this.y = y;
        }

        public boolean isInfinity() { return x == null || y == null; }

        @Override public String toString() {
            return isInfinity() ? "INF" : "(" + x.toString(16) + ", " + y.toString(16) + ")";
        }
    }

    public static final Point INFINITY = new Point(null, null);

    private static BigInteger mod(BigInteger v, BigInteger p) {
        BigInteger r = v.mod(p);
        return r.signum() < 0 ? r.add(p) : r;
    }

    public static Point add(EcCurve c, Point a, Point b) {
        if (a.isInfinity()) return b;
        if (b.isInfinity()) return a;
        BigInteger p = c.p;
        if (a.x.equals(b.x)) {
            if (!a.y.equals(b.y) || a.y.signum() == 0) return INFINITY;
            return twice(c, a);
        }
        BigInteger lambda = mod(b.y.subtract(a.y).multiply(b.x.subtract(a.x).modInverse(p)), p);
        BigInteger x3 = mod(lambda.multiply(lambda).subtract(a.x).subtract(b.x), p);
        BigInteger y3 = mod(lambda.multiply(a.x.subtract(x3)).subtract(a.y), p);
        return new Point(x3, y3);
    }

    public static Point twice(EcCurve c, Point a) {
        if (a.isInfinity() || a.y.signum() == 0) return INFINITY;
        BigInteger p = c.p;
        BigInteger num = a.x.multiply(a.x).multiply(BigInteger.valueOf(3)).add(c.a);
        BigInteger lambda = mod(num.multiply(a.y.shiftLeft(1).modInverse(p)), p);
        BigInteger x3 = mod(lambda.multiply(lambda).subtract(a.x.shiftLeft(1)), p);
        BigInteger y3 = mod(lambda.multiply(a.x.subtract(x3)).subtract(a.y), p);
        return new Point(x3, y3);
    }

    /** 标量乘 k*P（二进制展开，从高位到低位）。 */
    public static Point multiply(EcCurve c, Point p, BigInteger k) {
        BigInteger kk = mod(k, c.n);
        if (kk.signum() == 0 || p.isInfinity()) return INFINITY;
        Point r = INFINITY;
        for (int i = kk.bitLength() - 1; i >= 0; i--) {
            r = twice(c, r);
            if (kk.testBit(i)) r = add(c, r, p);
        }
        return r;
    }

    public static Point baseMultiply(EcCurve c, BigInteger k) {
        return multiply(c, new Point(c.gx, c.gy), k);
    }

    /** 生成临时密钥对：私钥在 [1, n-1]。 */
    public static BigInteger randomScalar(EcCurve c) {
        SecureRandom rnd = new SecureRandom();
        BigInteger d;
        do {
            byte[] b = new byte[c.fieldBytes + 8];
            rnd.nextBytes(b);
            d = new BigInteger(1, b).mod(c.n.subtract(BigInteger.ONE)).add(BigInteger.ONE);
        } while (d.signum() == 0 || d.compareTo(c.n) >= 0);
        return d;
    }

    /** X9.62 未压缩点编码：0x04 || X || Y（定长）。 */
    public static byte[] encode(EcCurve c, Point p) {
        if (p.isInfinity()) throw new IllegalArgumentException("无穷远点无法编码");
        byte[] out = new byte[1 + 2 * c.fieldBytes];
        out[0] = 0x04;
        byte[] x = p.x.toByteArray();
        byte[] y = p.y.toByteArray();
        copyRight(x, out, 1, c.fieldBytes);
        copyRight(y, out, 1 + c.fieldBytes, c.fieldBytes);
        return out;
    }

    private static void copyRight(byte[] src, byte[] dst, int off, int len) {
        int s = Math.max(0, src.length - len);
        int n = Math.min(len, src.length);
        System.arraycopy(src, s, dst, off + len - n, n);
    }

    public static Point decode(EcCurve c, byte[] data) {
        if (data == null || data.length == 0) throw new IllegalArgumentException("ECDH 公钥为空");
        if (data[0] == 0x04) {
            if (data.length != 1 + 2 * c.fieldBytes) {
                throw new IllegalArgumentException("公钥长度不符: " + data.length + " (期望 " + (1 + 2 * c.fieldBytes) + ")");
            }
            BigInteger x = new BigInteger(1, slice(data, 1, c.fieldBytes));
            BigInteger y = new BigInteger(1, slice(data, 1 + c.fieldBytes, c.fieldBytes));
            Point p = new Point(x, y);
            if (!isOnCurve(c, p)) throw new IllegalArgumentException("公钥不在曲线上");
            return p;
        }
        if (data[0] == 0x00 || data[0] == 0x02 || data[0] == 0x03) {
            return decompress(c, data);
        }
        throw new IllegalArgumentException("不支持的公钥格式: " + Tlv.hex(data, 4));
    }

    private static Point decompress(EcCurve c, byte[] data) {
        BigInteger x = new BigInteger(1, slice(data, 1, Math.min(c.fieldBytes, data.length - 1)));
        BigInteger alpha = mod(x.pow(3).add(c.a.multiply(x)).add(c.b), c.p);
        // p ≡ 3 (mod 4) 时 y = alpha^((p+1)/4)
        BigInteger y;
        if (c.p.testBit(1)) {
            y = alpha.modPow(c.p.add(BigInteger.ONE).shiftRight(2), c.p);
        } else {
            throw new IllegalArgumentException("压缩点需要 Tonelli-Shanks，本 App 不支持");
        }
        if (y.testBit(0) != ((data[0] & 1) == 1)) y = c.p.subtract(y);
        return new Point(x, y);
    }

    public static boolean isOnCurve(EcCurve c, Point p) {
        if (p.isInfinity()) return true;
        BigInteger left = mod(p.y.multiply(p.y), c.p);
        BigInteger right = mod(p.x.pow(3).add(c.a.multiply(p.x)).add(c.b), c.p);
        return left.equals(right);
    }

    private static byte[] slice(byte[] d, int off, int len) {
        byte[] o = new byte[len];
        System.arraycopy(d, off, o, 0, Math.min(len, d.length - off));
        return o;
    }

    /** ECDH：返回共享点 X 坐标的定长字节串（ICAO 9303-11 §9.6 要求 FE2OS 定长）。 */
    public static byte[] sharedSecret(EcCurve c, BigInteger privateKey, Point publicKey) {
        Point s = multiply(c, publicKey, privateKey);
        if (s.isInfinity()) throw new IllegalStateException("ECDH 得到无穷远点");
        return fill(s.x, c.fieldBytes);
    }

    public static byte[] fill(BigInteger v, int len) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[len];
        int n = Math.min(len, raw.length);
        System.arraycopy(raw, raw.length - n, out, len - n, n);
        return out;
    }
}
