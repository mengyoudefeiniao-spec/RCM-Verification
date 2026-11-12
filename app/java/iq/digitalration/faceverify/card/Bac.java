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

    /** EXTERNAL AUTHENTICATE 的 Le 不被芯片接受（6700）——需要换另一种长度并**重新取挑战**。 */
    private static final class RetryWithOtherLe extends Exception {
        RetryWithOtherLe(String msg) { super(msg); }
    }

    /**
     * 现场日志（idcard-diag-20261111-081310）说明了这个顺序为什么重要：
     * <pre>
     *   EXTERNAL AUTH 45 字节（无 Le） → 6700（长度错）
     *   拿同一个 E_IFD 补 Le 重发     → 6985（条件不满足：挑战已被上一次消耗）
     * </pre>
     * 所以：**第一次就带 Le=0x28**（这张卡要求的长度）；只有芯片回 6700 时，才重选应用、
     * 重新 GET CHALLENGE、用新的随机数重算 E_IFD，再以"无 Le"来一遍。
     */
    public static String establish(CardSession session, String mrzi) throws Exception {
        byte[] seed = new byte[16];
        System.arraycopy(Crypt.sha1(mrzi.getBytes("US-ASCII")), 0, seed, 0, 16);
        byte[] kEnc = Crypt.kdf(seed, 1, false, 112);
        byte[] kMac = Crypt.kdf(seed, 2, false, 112);

        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            // 第 1 次带 Le（现场卡要这个）；第 2 次不带 Le（ICAO §4.3.4.2 表格的写法）
            Integer le = attempt == 0 ? Integer.valueOf(0x28) : null;
            try {
                return handshake(session, kEnc, kMac, le);
            } catch (RetryWithOtherLe e) {
                last = e;
                session.log.add("EXTERNAL AUTHENTICATE 回 6700（Le 不被接受），重新取挑战后再试另一种长度");
                // 芯片状态可能已乱，重选应用清状态；随后的 handshake 会重新 GET CHALLENGE
                Apdu.Resp resel = session.send(0xA4, 0x04, 0x0C, Tlv.unhex("A0000002471001"), null);
                if (!resel.ok()) {
                    throw new IllegalStateException("BAC 重试前无法重新选中应用：" + resel.describe());
                }
            }
        }
        throw new IllegalStateException("EXTERNAL AUTHENTICATE 的两种长度都被芯片拒绝（6700）", last);
    }

    /** 一次完整的 BAC 握手（每次都会**重新**取挑战，绝不复用旧 E_IFD）。 */
    private static String handshake(CardSession session, byte[] kEnc, byte[] kMac, Integer le) throws Exception {
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
        // ISO 9797-1 MAC algorithm 3 = padding method 2 + CBC-MAC + K1/K2。
        // **即使数据已 8 字节对齐也必须补一个 0x80 块**——漏了会算出错误的 M_IFD，
        // 芯片按"MAC 不对"直接复位会话（现场表现：EXTERNAL AUTHENTICATE 无响应 → TagLost）。
        byte[] mIfd = Crypt.retailMac(kMac, Crypt.pad(eIfd, 8));

        byte[] body = Crypt.concat(eIfd, mIfd);
        r = session.send(0x82, 0x00, 0x00, body, le);
        if (r.sw == 0x6700) throw new RetryWithOtherLe("EXTERNAL AUTHENTICATE 6700");
        if (!r.ok() || r.data.length != 40) {
            String hint = r.sw == 0x6300 ? "（MRZ 不正确：证件号/出生/有效期必须与卡面完全一致）"
                    : (r.sw == 0x6982 || r.sw == 0x6A81)
                    ? "（芯片拒绝 BAC：这张卡可能只支持 PACE，而 EF.CardAccess 又被保护，需要预置 PACE 参数）"
                    : r.sw == 0x6985 ? "（芯片认为当前状态不允许认证：挑战可能已失效，或 BAC 被禁用）"
                    : "";
            throw new IllegalStateException("BAC 外部认证失败：" + r.describe() + hint);
        }
        byte[] cipher = new byte[32];
        byte[] mac = new byte[8];
        System.arraycopy(r.data, 0, cipher, 0, 32);
        System.arraycopy(r.data, 32, mac, 0, 8);

        byte[] expMac = Crypt.retailMac(kMac, Crypt.pad(cipher, 8));
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
