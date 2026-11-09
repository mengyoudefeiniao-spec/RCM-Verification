package iq.digitalration.faceverify.card;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 被动认证（Passive Authentication）：校验 EF.SOD 的 CMS 签名，并逐个比对数据组哈希。
 *
 * <p>本 App 不带 CSCA 主列表（各国根证书清单，几十 MB 且需持续更新），所以只能做到：
 * <ol>
 *   <li>SOD 的签名能被 SOD 内自带的证件签名证书（DS）验证 →「SOD 完整性」；</li>
 *   <li>DS 证书的签发者能在 SOD 里找到且签名自洽 →「证书链自洽」（未锚定根）；</li>
 *   <li>数据组哈希与 SOD 一致 →「芯片数据未被篡改」。</li>
 * </ol>
 * 「这张证件由某国签发」必须导入对应 CSCA 主列表才能判定，界面会明确标注「未锚定」。
 */
public final class PassiveAuth {

    public static final class Result {
        public boolean sodParsed;
        public boolean signatureOk;
        public boolean messageDigestOk;
        public boolean chainOk;
        public boolean certValidOk;
        public boolean dgHashesOk;
        public String hashAlg = "";
        public String dsSubject = "";
        public String dsIssuer = "";
        public int dgMatched, dgVerified, dgDeclared, dgMissing;
        public final List<String> details = new ArrayList<>();
        public String error;

        /** 芯片数据是否可信（签名 + 哈希都对）。注意：不等于「证件真实」。 */
        public boolean chipDataTrusted() { return sodParsed && signatureOk && dgHashesOk; }

        public String summary() {
            if (!sodParsed) return "SOD 解析失败" + (error == null ? "" : "：" + error);
            return (signatureOk ? "SOD 签名有效" : "SOD 签名无效")
                    + " · 数据组哈希 " + dgMatched + "/" + dgVerified
                    + (chainOk ? " · 证书链自洽" : " · 证书链未锚定")
                    + (certValidOk ? " · 证书在有效期内" : " · 证书有效期异常");
        }
    }

    private PassiveAuth() {}

    private static final String OID_SHA1 = "1.3.14.3.2.26";
    private static final String OID_SHA224 = "2.16.840.1.101.3.4.2.4";
    private static final String OID_SHA256 = "2.16.840.1.101.3.4.2.1";
    private static final String OID_SHA384 = "2.16.840.1.101.3.4.2.2";
    private static final String OID_SHA512 = "2.16.840.1.101.3.4.2.3";
    private static final String OID_MESSAGE_DIGEST = "1.2.840.113549.1.9.4";

    /**
     * @param sod EF.SOD 原始文件（含 0x77 头）
     * @param dgs 已读出的数据组（key 为数据组编号，value 为完整 DG 文件字节）
     */
    public static Result verify(byte[] sod, Map<Integer, byte[]> dgs) {
        Result r = new Result();
        try {
            verifyInternal(sod, dgs, r);
        } catch (Throwable t) {
            r.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            r.details.add("被动认证异常：" + r.error);
        }
        return r;
    }

    private static void verifyInternal(byte[] sod, Map<Integer, byte[]> dgs, Result r) throws Exception {
        if (sod == null || sod.length == 0) {
            r.error = "EF.SOD 未读取";
            return;
        }
        Tlv sodRoot = null;
        for (Tlv t : Tlv.parse(sod)) if (t.tag == 0x77) sodRoot = t;
        if (sodRoot == null) {
            r.error = "EF.SOD 缺少 0x77";
            return;
        }
        Tlv signedData = null;
        for (Tlv t : Tlv.parse(sodRoot.value)) if (t.tag == 0x30) { signedData = t; break; }
        if (signedData == null) {
            r.error = "CMS SignedData 缺失";
            return;
        }
        Tlv encap = null, certs = null, signerInfos = null;
        for (Tlv c : signedData.children()) {
            if (c.tag == 0x30 && encap == null) encap = c;
            else if (c.tag == 0xA0) certs = c;
            else if (c.tag == 0x31) signerInfos = c;
        }
        if (encap == null || signerInfos == null) {
            r.error = "CMS 结构不完整（缺少 eContent 或 SignerInfo）";
            return;
        }

        // eContentInfo ::= SEQUENCE { eContentType OID, eContent [0] EXPLICIT OCTET STRING }
        byte[] eContent = null;
        for (Tlv c : encap.children()) {
            if (c.tag == 0xA0) {
                for (Tlv cc : c.children()) if (cc.tag == 0x04) eContent = cc.value;
            }
        }
        if (eContent == null) {
            r.error = "LDS Security Object（eContent）缺失";
            return;
        }

        // LDSSecurityObject ::= SEQUENCE { version, hashAlgorithm, dataGroupHashValues, [ldsVersionInfo] }
        List<Tlv> ldsList = Tlv.parse(eContent);
        if (ldsList.isEmpty()) {
            r.error = "LDS Security Object 无法解析";
            return;
        }
        List<Tlv> lc = ldsList.get(0).children();
        int idx = 0;
        if (idx < lc.size() && lc.get(idx).tag == 0x02) idx++;
        Tlv algId = null, hashValues = null;
        if (idx < lc.size() && lc.get(idx).tag == 0x30) algId = lc.get(idx++);
        if (idx < lc.size() && lc.get(idx).tag == 0x30) hashValues = lc.get(idx);
        String hashOid = algId == null ? OID_SHA256 : Pace.oidText(algId.children().get(0).value);
        r.hashAlg = hashName(hashOid);

        // ---- 数据组哈希比对：只校验「读到的」数据组（本 App 只读 1/2/11，
        //      芯片可能声明更多组，如 DG12/DG14/DG15，未读取的不算不匹配）
        if (hashValues != null && dgs != null) {
            for (Tlv entry : hashValues.children()) {
                List<Tlv> ec = entry.children();
                if (ec.size() < 2 || ec.get(0).tag != 0x02) continue;
                int dgNo = new BigInteger(1, ec.get(0).value).intValue();
                byte[] exp = ec.get(1).value;
                byte[] raw = dgs.get(dgNo);
                r.dgDeclared++;
                if (raw == null) {
                    r.dgMissing++;
                    continue;
                }
                r.dgVerified++;
                byte[] act = MessageDigest.getInstance(jcaHash(hashOid)).digest(raw);
                if (Arrays.equals(exp, act)) r.dgMatched++;
                else r.details.add("DG" + dgNo + "：哈希不匹配（SOD " + Tlv.hex(exp, 8)
                        + " / 实际 " + Tlv.hex(act, 8) + "）");
            }
            r.dgHashesOk = r.dgVerified > 0 && r.dgMatched == r.dgVerified;
            r.details.add("数据组哈希 " + r.dgMatched + "/" + r.dgVerified
                    + (r.dgMissing > 0 ? "（SOD 还声明了 " + r.dgMissing + " 组未读取）" : "")
                    + (r.dgHashesOk ? " 匹配" : " 有差异"));
        } else {
            r.details.add("SOD 未包含数据组哈希");
        }

        // ---- 证书
        List<X509Certificate> certList = new ArrayList<>();
        if (certs != null) {
            try {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                for (Tlv c : certs.children()) {
                    if (c.tag != 0x30) continue;
                    try {
                        certList.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(c.raw)));
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                r.details.add("X.509 解析失败：" + e.getMessage());
            }
        }
        if (certList.isEmpty()) {
            r.details.add("SOD 内没有可用证书，无法验证签名");
            return;
        }
        r.sodParsed = true;

        // ---- SignerInfo（按位置解析：version, sid, digestAlgorithm, [signedAttrs], signatureAlgorithm, signature）
        Tlv signer = null;
        for (Tlv t : signerInfos.children()) if (t.tag == 0x30) { signer = t; break; }
        if (signer == null) {
            r.details.add("SignerInfo 缺失");
            return;
        }
        List<Tlv> sc = signer.children();
        int i = 0;
        if (i < sc.size() && sc.get(i).tag == 0x02) i++;          // version
        if (i < sc.size()) i++;                                    // sid
        Tlv digestAlg = (i < sc.size() && sc.get(i).tag == 0x30) ? sc.get(i++) : null;
        Tlv signedAttrs = (i < sc.size() && sc.get(i).tag == 0xA0) ? sc.get(i++) : null;
        Tlv sigAlg = (i < sc.size() && sc.get(i).tag == 0x30) ? sc.get(i++) : null;
        Tlv sigValue = (i < sc.size() && sc.get(i).tag == 0x04) ? sc.get(i) : null;
        if (sigAlg == null || sigValue == null) {
            r.details.add("SignerInfo 缺少签名算法/签名值");
            return;
        }
        String sigAlgOid = Pace.oidText(sigAlg.children().get(0).value);
        String digestOid = digestAlg == null ? hashOid : Pace.oidText(digestAlg.children().get(0).value);

        // 签名覆盖的是 SET OF Attribute（0xA0 → 0x31）或 eContent
        byte[] signedBytes;
        if (signedAttrs != null) {
            signedBytes = signedAttrs.raw.clone();
            signedBytes[0] = 0x31;
        } else {
            signedBytes = eContent;
        }

        if (signedAttrs != null) {
            for (Tlv attr : signedAttrs.children()) {
                List<Tlv> ac = attr.children();
                if (ac.size() < 2) continue;
                if (!OID_MESSAGE_DIGEST.equals(Pace.oidText(ac.get(0).value))) continue;
                List<Tlv> vals = ac.get(1).children();
                if (!vals.isEmpty()) {
                    byte[] actual = MessageDigest.getInstance(jcaHash(digestOid)).digest(eContent);
                    r.messageDigestOk = Arrays.equals(vals.get(0).value, actual);
                    if (!r.messageDigestOk) r.details.add("signedAttrs 的 messageDigest 与 eContent 不一致");
                }
            }
        }

        X509Certificate signerCert = null;
        for (X509Certificate cert : certList) {
            if (verifyWith(cert, sigAlgOid, digestOid, signedBytes, sigValue.value)) {
                signerCert = cert;
                r.signatureOk = true;
                break;
            }
        }
        if (signerCert == null) {
            r.details.add("签名验证失败（签名算法 " + sigAlgOid + "，摘要 " + hashName(digestOid) + "）");
            return;
        }
        r.dsSubject = signerCert.getSubjectX500Principal().getName();
        r.dsIssuer = signerCert.getIssuerX500Principal().getName();

        try {
            signerCert.checkValidity();
            r.certValidOk = true;
        } catch (Exception e) {
            r.details.add("证件签名证书不在有效期内：" + e.getMessage());
        }

        for (X509Certificate dsca : certList) {
            if (dsca == signerCert) continue;
            if (!dsca.getSubjectX500Principal().equals(signerCert.getIssuerX500Principal())) continue;
            try {
                signerCert.verify(dsca.getPublicKey());
                r.chainOk = true;
                r.details.add("证书链自洽：DS ← " + shorten(dsca.getSubjectX500Principal().getName())
                        + "（未与 CSCA 主列表比对，不能据此判定签发国）");
                break;
            } catch (Exception ignored) {
            }
        }
        if (!r.chainOk) {
            r.details.add("SOD 内未找到签发者证书，无法检查证书链（" + shorten(r.dsIssuer) + "）");
        }
        r.details.add("签名算法 " + sigAlgOid + " / 摘要 " + hashName(digestOid));
        r.details.add("签名证书 " + shorten(r.dsSubject));
    }

    /** 依次尝试几种 JCA 名称（不同厂商对 PSS/参数编码的处理不一）。 */
    private static boolean verifyWith(X509Certificate cert, String sigAlgOid, String digestOid,
                                      byte[] data, byte[] sig) {
        List<String> candidates = new ArrayList<>();
        String key = cert.getPublicKey().getAlgorithm();
        boolean ec = "EC".equals(key) || "ECDSA".equals(key);
        String mapped = mapSig(sigAlgOid);
        if (mapped != null) candidates.add(mapped);
        candidates.add((ec ? "SHA256withECDSA" : "SHA256withRSA"));
        candidates.add((ec ? "SHA1withECDSA" : "SHA1withRSA"));
        if (ec) {
            candidates.add("SHA384withECDSA");
            candidates.add("SHA512withECDSA");
        } else {
            candidates.add("SHA384withRSA");
            candidates.add("SHA512withRSA");
        }
        for (String name : candidates) {
            try {
                Signature s = Signature.getInstance(name);
                s.initVerify(cert.getPublicKey());
                s.update(data);
                if (s.verify(sig)) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static String mapSig(String oid) {
        switch (oid) {
            case "1.2.840.113549.1.1.5": return "SHA1withRSA";
            case "1.2.840.113549.1.1.11": return "SHA256withRSA";
            case "1.2.840.113549.1.1.12": return "SHA384withRSA";
            case "1.2.840.113549.1.1.13": return "SHA512withRSA";
            case "1.2.840.10045.4.1": return "SHA1withECDSA";
            case "1.2.840.10045.4.3.2": return "SHA256withECDSA";
            case "1.2.840.10045.4.3.3": return "SHA384withECDSA";
            case "1.2.840.10045.4.3.4": return "SHA512withECDSA";
            case "1.3.101.112": return "Ed25519";
            default: return null;
        }
    }

    private static String shorten(String dn) {
        if (dn == null) return "";
        return dn.length() > 70 ? dn.substring(0, 70) + "…" : dn;
    }

    private static String hashName(String oid) {
        if (OID_SHA1.equals(oid)) return "SHA-1";
        if (OID_SHA224.equals(oid)) return "SHA-224";
        if (OID_SHA384.equals(oid)) return "SHA-384";
        if (OID_SHA512.equals(oid)) return "SHA-512";
        return "SHA-256";
    }

    private static String jcaHash(String oid) {
        return hashName(oid);
    }

    /** 便于单测/界面展示：单独算一个数据组的哈希。 */
    public static byte[] dgHash(String hashOid, byte[] data) throws Exception {
        return MessageDigest.getInstance(jcaHash(hashOid)).digest(data);
    }

    public static String describe(String hashOid) {
        return String.format(Locale.US, "摘要 %s", hashName(hashOid));
    }
}
