package iq.digitalration.faceverify.card;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 主动认证（Active Authentication，ICAO 9303-11 §5.3）。
 *
 * <p>被动认证只能证明「芯片里的数据没被改」，证明不了「这张卡不是复制的」——整套 DG1/DG2/SOD
 * 可以被原样拷进另一颗芯片。AA 用芯片内部**不可导出**的私钥对随机数签名：
 * 芯片必须真的持有那把私钥，复制数据组是过不了这一步的。
 *
 * <p>流程（与 gdis 的 {@code ChipClient/icao/aa.c} 等价）：
 * <ol>
 *   <li>读 DG15：{@code 6F { 30 SubjectPublicKeyInfo }}；</li>
 *   <li>发 8 字节随机挑战 {@code 00 88 00 00 08 <challenge> 00}；</li>
 *   <li>用 DG15 里的公钥验签（RSA/ECDSA，摘要 SHA-1/256/384/512 依次试）。</li>
 * </ol>
 *
 * <p>注意：AA 必须在安全报文（SM）建立之后做，且挑战必须每次新鲜随机；固定挑战等于把 AA 变成重放。
 */
public final class ActiveAuth {

    public static final class Result {
        public boolean attempted;
        public boolean ok;
        public String keyType = "";
        public int keyBits;
        public String algorithm = "";
        /** 芯片没给 9000 时的状态字说明（6982=未启用、6A88/6A81=不支持…）。 */
        public String error = "";
        public final List<String> details = new ArrayList<>();

        public String summary() {
            if (!attempted) return "未尝试（芯片未声明 DG15）";
            if (ok) {
                return "通过 · " + keyType + " " + keyBits + " 位 · " + algorithm;
            }
            return "未通过" + (error.isEmpty() ? "" : "：" + error);
        }
    }

    private ActiveAuth() {}

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 读 DG15 并做一次主动认证；任何一步失败都返回 attempted=true 的结果，不抛异常打断整次读卡。 */
    public static Result run(CardSession session, byte[] dg15) {
        Result r = new Result();
        r.attempted = true;
        try {
            byte[] spki = spkiOf(dg15);
            if (spki == null) {
                r.error = "DG15 里没有 SubjectPublicKeyInfo";
                return r;
            }
            PublicKey pub = publicKey(spki);
            if (pub == null) {
                r.error = "DG15 公钥无法解析（不是 RSA/EC）";
                return r;
            }
            r.keyType = pub.getAlgorithm();
            r.keyBits = keyBits(pub);

            byte[] challenge = new byte[8];
            RANDOM.nextBytes(challenge);

            Apdu.Resp resp = session.send(0x88, 0x00, 0x00, challenge, 256);
            r.details.add("INTERNAL AUTHENTICATE ← " + resp.describe());
            if (resp.sw == 0x6982) {
                r.error = "芯片未启用主动认证（6982 安全状态不满足）";
                return r;
            }
            if (resp.sw == 0x6A88 || resp.sw == 0x6A81 || resp.sw == 0x6D00) {
                r.error = "芯片不支持主动认证（" + resp.describe() + "）";
                return r;
            }
            if (!resp.ok() || resp.data == null || resp.data.length == 0) {
                r.error = "签名返回异常（" + resp.describe() + "）";
                return r;
            }
            String alg = verifySignature(pub, challenge, resp.data);
            if (alg == null) {
                r.error = "签名验证失败（公钥 " + r.keyType + "，签名 " + resp.data.length + " 字节）";
                r.details.add("挑战 " + Tlv.hex(challenge) + " / 签名 " + Tlv.hex(resp.data, 16));
                return r;
            }
            r.algorithm = alg;
            r.ok = true;
            return r;
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : "：" + e.getMessage());
            return r;
        }
    }

    /** DG15：6F { 30 SPKI }。返回 SPKI 的 DER（给 X509EncodedKeySpec 用）。 */
    public static byte[] spkiOf(byte[] dg15) {
        if (dg15 == null) return null;
        for (Tlv t : Tlv.parse(dg15)) {
            if (t.tag != 0x6F) continue;
            Tlv spki = t.find(0x30);
            if (spki != null) return spki.raw;
            Tlv deep = t.findDeep(0x30);
            if (deep != null) return deep.raw;
        }
        return null;
    }

    public static PublicKey publicKey(byte[] spki) {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(spki));
        } catch (Exception ignored) {
        }
        try {
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spki));
        } catch (Exception ignored) {
        }
        try {
            return KeyFactory.getInstance("DSA").generatePublic(new X509EncodedKeySpec(spki));
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 依次尝试 JCA 算法名；返回命中的那个（null = 全部验签失败）。 */
    public static String verifySignature(PublicKey pub, byte[] challenge, byte[] signature) {
        if (pub == null || challenge == null || signature == null) return null;
        List<String> algs = new ArrayList<>();
        String k = pub.getAlgorithm();
        boolean ec = "EC".equals(k) || "ECDSA".equals(k);
        if (ec) {
            algs.add("SHA1withECDSA");
            algs.add("SHA256withECDSA");
            algs.add("SHA384withECDSA");
            algs.add("SHA512withECDSA");
        } else {
            algs.add("SHA1withRSA");
            algs.add("SHA256withRSA");
            algs.add("SHA384withRSA");
            algs.add("SHA512withRSA");
        }
        for (String name : algs) {
            try {
                Signature s = Signature.getInstance(name);
                s.initVerify(pub);
                s.update(challenge);
                if (s.verify(signature)) return name;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static int keyBits(PublicKey pub) {
        try {
            if (pub instanceof RSAPublicKey) return ((RSAPublicKey) pub).getModulus().bitLength();
            if (pub instanceof ECPublicKey) {
                ECPublicKey ec = (ECPublicKey) pub;
                return ec.getParams().getCurve().getField().getFieldSize();
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 把公钥信息压成一行，日志里能直接看出卡用的是 RSA-2048 还是 EC P-256。 */
    public static String describeKey(PublicKey pub) {
        if (pub == null) return "无";
        return String.format(Locale.US, "%s %d 位", pub.getAlgorithm(), keyBits(pub));
    }
}
