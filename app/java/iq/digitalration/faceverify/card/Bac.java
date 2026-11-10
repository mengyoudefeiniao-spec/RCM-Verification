package iq.digitalration.faceverify.card;

/**
 * BAC（Basic Access Control）——ICAO 9303-11 §4.3。
 *
 * <p>BAC 是 PACE 的"老方案"：用 MRZ 派生的 3DES 密钥做一次双向挑战-响应，之后所有数据用
 * 3DES 安全报文传输。很多老芯片只支持 BAC，所以 PACE 不可用时回退到这里。
 *
 * <p>密钥：Kseed = SHA1(MRZi)[0..16]，Kenc/Kmac = KDF(Kseed, 1/2)。
 */
public final class Bac {

    private Bac() {}

    public static String establish(CardSession session, String mrzi) throws Exception {
        byte[] seed = new byte[16];
        System.arraycopy(Crypt.sha1(mrzi.getBytes("US-ASCII")), 0, seed, 0, 16);
        byte[] kEnc = Crypt.kdf(seed, 1, false, 112);
        byte[] kMac = Crypt.kdf(seed, 2, false, 112);

        Apdu.Resp r = session.send(0x84, 0x00, 0x00, null, 8);
        if (!r.ok() || r.data.length != 8) {
            throw new IllegalStateException("BAC 取挑战失败：" + r.describe()
                    + (r.sw == 0x6982 || r.sw == 0x6A81
                    ? "（芯片拒绝 BAC：这张卡可能只支持 PACE）" : ""));
        }
        byte[] rndIc = r.data;
        byte[] rndIfd = Crypt.random(8);
        byte[] kIfd = Crypt.random(16);

        byte[] plain = Crypt.concat(rndIfd, rndIc, kIfd);          // 32 字节
        byte[] eIfd = Crypt.desCbc(kEnc, new byte[8], plain, true);
        byte[] mIfd = Crypt.retailMac(kMac, eIfd);

        r = session.send(0x82, 0x00, 0x00, Crypt.concat(eIfd, mIfd), 0x28);
        if (!r.ok() || r.data.length != 40) {
            String hint = r.sw == 0x6300 ? "（MRZ 不正确：证件号/出生/有效期必须与卡面完全一致）"
                    : (r.sw == 0x6982 || r.sw == 0x6A81)
                    ? "（芯片拒绝 BAC：这张卡可能只支持 PACE，而 EF.CardAccess 又被保护，需要预置 PACE 参数）"
                    : "";
            throw new IllegalStateException("BAC 外部认证失败：" + r.describe() + hint);
        }
        byte[] cipher = new byte[32];
        byte[] mac = new byte[8];
        System.arraycopy(r.data, 0, cipher, 0, 32);
        System.arraycopy(r.data, 32, mac, 0, 8);

        byte[] expMac = Crypt.retailMac(kMac, cipher);
        if (!java.util.Arrays.equals(expMac, mac)) {
            throw new IllegalStateException("BAC 响应 MAC 校验失败");
        }
        byte[] dec = Crypt.desCbc(kEnc, new byte[8], cipher, false);
        byte[] icRnd = new byte[8];
        byte[] ifdRnd = new byte[8];
        byte[] kIc = new byte[16];
        System.arraycopy(dec, 0, icRnd, 0, 8);
        System.arraycopy(dec, 8, ifdRnd, 0, 8);
        System.arraycopy(dec, 16, kIc, 0, 16);
        if (!java.util.Arrays.equals(icRnd, rndIc)) throw new IllegalStateException("BAC 的 RND.IC 不一致");
        if (!java.util.Arrays.equals(ifdRnd, rndIfd)) throw new IllegalStateException("BAC 的 RND.IFD 不一致");

        byte[] seed2 = Crypt.concat(kIfd, kIc);
        byte[] kEnc2 = Crypt.kdf(seed2, 1, false, 112);
        byte[] kMac2 = Crypt.kdf(seed2, 2, false, 112);
        byte[] ssc = SecureMessaging.bacSsc(rndIc, rndIfd);

        session.setSm(new SecureMessaging(false, kEnc2, kMac2, ssc));
        return "BAC（3DES 安全报文）";
    }
}
