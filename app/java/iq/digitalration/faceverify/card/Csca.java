package iq.digitalration.faceverify.card;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.security.auth.x500.X500Principal;

/**
 * CSCA 信任锚（信任模型见 ICAO Doc 9303 Part 12 / PKD MasterList）。
 *
 * <p>被动认证里「DS 证书能被 SOD 内的签发者验证」只说明**自洽**——中间人完全可以自己造一对
 * CSCA/DS 证书自签一份 SOD。要回答「这张证件由哪国签发」，必须拿 DS 的签发者去比对
 * **受信 CSCA 列表**（ICAO PKD 的 MasterList，或本国边检下发的 CSCA 包）。
 *
 * <p>本类只做「库 + 比对」，不内置任何国家的根证书：
 * <ul>
 *   <li>{@code Csca.add(cert)} / {@code loadPem(stream)} 由宿主装入（assets、SD 卡、后端下发都行）；</li>
 *   <li>库为空时 {@code verify()} 返回「未收录」，界面照旧显示"未锚定"，绝不把自洽说成可信。</li>
 * </ul>
 *
 * <p>比对规则与 MasterList 一致：**公钥指纹优先**（同一把 CSCA 密钥可能重签过证书），
 * 其次是「Subject DN + 序列号」完全相等。
 */
public final class Csca {

    public static final class Verdict {
        public boolean trusted;
        /** 签发国的 ISO 3166 两字母代码（取自 CSCA 证书 Subject 的 C=）。 */
        public String country = "";
        public String subject = "";
        public String reason = "";
        public int storeSize;
    }

    private static final List<X509Certificate> STORE = new ArrayList<>();

    private Csca() {}

    public static synchronized void clear() {
        STORE.clear();
    }

    public static synchronized int size() {
        return STORE.size();
    }

    public static synchronized void add(X509Certificate cert) {
        if (cert == null) return;
        String fp = fingerprint(cert);
        for (X509Certificate c : STORE) {
            if (fingerprint(c).equals(fp)) return;
        }
        STORE.add(cert);
    }

    /** 多张 PEM 串在一起的证书包（ICAO MasterList 导出、边检下发都用这个格式）。返回装入张数。 */
    public static synchronized int loadPem(InputStream in) {
        if (in == null) return 0;
        int n = 0;
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            for (java.security.cert.Certificate c : cf.generateCertificates(in)) {
                if (c instanceof X509Certificate) {
                    add((X509Certificate) c);
                    n++;
                }
            }
        } catch (Exception ignored) {
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
        return n;
    }

    public static synchronized int loadPem(String pem) {
        if (pem == null || pem.indexOf("BEGIN CERTIFICATE") < 0) return 0;
        return loadPem(new ByteArrayInputStream(pem.getBytes(java.nio.charset.Charset.forName("ISO-8859-1"))));
    }

    /** 拿 SOD 里的 CSCA 候选证书来查库。 */
    public static synchronized Verdict verify(X509Certificate candidate) {
        Verdict v = new Verdict();
        v.storeSize = STORE.size();
        if (candidate == null) {
            v.reason = "SOD 内没有 CSCA 证书";
            return v;
        }
        v.subject = candidate.getSubjectX500Principal().getName();
        v.country = countryOf(candidate);
        if (STORE.isEmpty()) {
            v.reason = "本地 CSCA 库为空（未装入任何国家的根证书），只做到「自洽」";
            return v;
        }
        String fp = fingerprint(candidate);
        for (X509Certificate t : STORE) {
            if (fingerprint(t).equals(fp)) {
                v.trusted = true;
                v.country = countryOf(t);
                v.subject = t.getSubjectX500Principal().getName();
                v.reason = "公钥指纹命中";
                return v;
            }
        }
        X500Principal subj = candidate.getSubjectX500Principal();
        String serial = candidate.getSerialNumber().toString(16).toUpperCase(Locale.US);
        for (X509Certificate t : STORE) {
            if (t.getSubjectX500Principal().equals(subj)
                    && t.getSerialNumber().toString(16).toUpperCase(Locale.US).equals(serial)) {
                v.trusted = true;
                v.country = countryOf(t);
                v.reason = "Subject + 序列号命中";
                return v;
            }
        }
        v.reason = "未收录（库内 " + STORE.size() + " 张，均不匹配该 CSCA）";
        return v;
    }

    /** 从 Subject DN 里取 C=XX（没有就返回空串）。 */
    public static String countryOf(X509Certificate cert) {
        try {
            String dn = cert.getSubjectX500Principal().getName(X500Principal.RFC2253);
            for (String part : dn.split(",")) {
                String p = part.trim();
                if (p.length() > 2 && (p.startsWith("C=") || p.startsWith("c="))) {
                    return p.substring(2).trim().toUpperCase(Locale.US);
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String fingerprint(X509Certificate cert) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Tlv.hex(md.digest(cert.getPublicKey().getEncoded()));
        } catch (Exception e) {
            return "";
        }
    }
}
