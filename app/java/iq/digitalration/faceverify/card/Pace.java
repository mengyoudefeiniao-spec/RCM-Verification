package iq.digitalration.faceverify.card;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PACE（Password Authenticated Connection Establishment）——ICAO 9303-11 §4.4 的
 * ECDH + Generic Mapping 版本，支持 AES-CBC-CMAC-128/192/256 与 3DES-CBC-CBC。
 *
 * <p>流程（与 BSI TR-03110 / 9303-11 一致）：
 * <pre>
 *   MSE:Set AT 80 OID, 83 01 01(MRZ), 84 01 paramId
 *   GA 7C 00                     -> 7C 80 E(Kπ, nonce)
 *   GA 7C 81 PK_map,IFD          -> 7C 82 PK_map,IC      H = ECDH,  G' = s·G + H
 *   GA 7C 83 PK_ka,IFD           -> 7C 84 PK_ka,IC       K = ECDH
 *   GA 7C 85 T_IFD               -> 7C 86 T_IC           T = MAC(KSmac, 7F49{06 OID,86 PK})
 *   SSC 从 0 开始，进入安全报文
 * </pre>
 *
 * <p>DH-GM（参数 0/1/2）与 Integrated Mapping 未实现——实际卡片极少只用这些组合；
 * 遇到时调用方会回退到 BAC 并在界面说明。
 */
public final class Pace {

    /** EF.CardAccess 里的一条 PACEInfo。 */
    public static final class Info {
        public final byte[] oid;
        public final String oidText;
        public final int version;
        public final int paramId;
        public final boolean aes;
        public final int keyBits;
        public final boolean ecdhGeneric;

        Info(byte[] oid, String oidText, int version, int paramId, boolean aes, int keyBits, boolean ecdhGeneric) {
            this.oid = oid;
            this.oidText = oidText;
            this.version = version;
            this.paramId = paramId;
            this.aes = aes;
            this.keyBits = keyBits;
            this.ecdhGeneric = ecdhGeneric;
        }

        @Override public String toString() {
            return "PACE " + oidText + (aes ? "-AES-" + keyBits : "-3DES") + " paramId=" + paramId;
        }
    }

    private static final String OID_ECDH_GM = "0.4.0.127.0.7.2.2.4.2";

    private Pace() {}

    /** 解析 EF.CardAccess，返回所有 PACE 条目（不可用的也算，便于界面说明）。 */
    public static List<Info> parseCardAccess(byte[] cardAccess) {
        List<Info> out = new ArrayList<>();
        if (cardAccess == null || cardAccess.length == 0) return out;
        List<Tlv> roots = Tlv.parse(cardAccess);
        List<Tlv> nodes = new ArrayList<>();
        collectSequences(roots, nodes);
        for (Tlv seq : nodes) {
            List<Tlv> ch = seq.children();
            if (ch.size() < 2) continue;
            Tlv oidNode = null;
            for (Tlv c : ch) {
                if (c.tag == 0x06) { oidNode = c; break; }
            }
            if (oidNode == null) continue;
            String oid = oidText(oidNode.value);
            if (!oid.startsWith("0.4.0.127.0.7.2.2.4.")) continue;

            List<Integer> ints = new ArrayList<>();
            for (int i = 1; i < ch.size(); i++) {
                Tlv c = ch.get(i);
                if (c.tag == 0x02) ints.add(new BigInteger(1, c.value).intValue());
            }
            int version = ints.isEmpty() ? 0 : ints.get(0);
            int paramId = ints.size() > 1 ? ints.get(1) : -1;
            // 编码：0.4.0.127.0.7.2.2.4.<mapping>.<alg>
            //   1=DH-GM 2=ECDH-GM 3=DH-IM 4=ECDH-IM 6=ECDH-CAM
            //   alg: 1=3DES 2=AES-128 3=AES-192 4=AES-256
            String[] arcs = oid.split("\\.");
            int mapping = arcs.length > 9 ? Integer.parseInt(arcs[9]) : -1;
            int alg = arcs.length > 10 ? Integer.parseInt(arcs[10]) : -1;
            boolean ecdhGeneric = mapping == 2;
            if (!ecdhGeneric) continue;             // DH / IM / CAM 暂不实现

            boolean aes = alg >= 2;
            int bits = alg == 2 ? 128 : alg == 3 ? 192 : alg == 4 ? 256 : 112;
            out.add(new Info(oidNode.value, oid, version, paramId, aes, bits, true));
        }
        return out;
    }

    private static void collectSequences(List<Tlv> src, List<Tlv> out) {
        for (Tlv t : src) {
            if (t.tag == 0x30) out.add(t);
            if (t.tag == 0x30 || t.tag == 0x31) collectSequences(t.children(), out);
        }
    }

    public static String oidText(byte[] oid) {
        StringBuilder sb = new StringBuilder();
        sb.append(oid[0] / 40).append('.').append(oid[0] % 40);
        int v = 0;
        for (int i = 1; i < oid.length; i++) {
            v = (v << 7) | (oid[i] & 0x7F);
            if ((oid[i] & 0x80) == 0) {
                sb.append('.').append(v);
                v = 0;
            }
        }
        return sb.toString();
    }

    /**
     * 没有 EF.CardAccess 时的默认候选（按 ICAO 常见度排序）。
     *
     * <p>现场卡（伊拉克 eMRTD）：EF.CardAccess 被 6982 挡住拿不到参数，而 BAC 的 GET CHALLENGE
     * 直接挂起（8 秒超时）——只能按常见组合盲试。先用 {@link #probe} 发一条 MSE 筛，
     * 命中再走完整 PACE，避免几十条无用 APDU。
     */
    public static List<Info> defaultCandidates() {
        List<Info> out = new ArrayList<>();
        byte[] aes128 = Tlv.unhex("04007F00070202040202");
        byte[] aes256 = Tlv.unhex("04007F00070202040204");
        byte[] des3 = Tlv.unhex("04007F00070202040201");
        int[] curves = {13, 15, 12, 16, 17, 14, 9, 10, 11};   // brainpoolP384/256、NIST P-256/384/521…
        for (int c : curves) {
            if (EcCurve.byId(c) == null) continue;
            out.add(new Info(aes128, "0.4.0.127.0.7.2.2.4.2.2", 2, c, true, 128, true));
        }
        for (int c : new int[]{13, 15, 12}) {
            if (EcCurve.byId(c) == null) continue;
            out.add(new Info(aes256, "0.4.0.127.0.7.2.2.4.2.4", 2, c, true, 256, true));
        }
        for (int c : new int[]{13, 15}) {
            if (EcCurve.byId(c) == null) continue;
            out.add(new Info(des3, "0.4.0.127.0.7.2.2.4.2.1", 2, c, false, 112, true));
        }
        return out;
    }

    /** PACE 密码参考（ICAO 9303-11 §4.4.2）：0x01=MRZ、0x02=CAN、0x03=PIN、0x04=PUK。 */
    public static final int PWD_MRZ = 0x01;
    public static final int PWD_CAN = 0x02;

    /**
     * PACE 的口令密钥 Kπ = KDF(f(π), 3)（§9.7.3 表11）。
     *
     * <p><b>两种口令的编码不一样</b>：
     * <ul>
     *   <li>MRZ：f(π) = SHA-1(MRZ_information)，MRZ_information = 证件号+校验位+出生+校验位+有效期+校验位；</li>
     *   <li>CAN：f(π) = CAN 的 ISO/IEC 8859-1 编码字符串本身，<b>不再做 SHA-1</b>。</li>
     * </ul>
     * 以前两种都先 SHA-1，CAN 通道的 Kπ 必然不对，PACE-CAN 永远过不了。
     * 公开出来是为了单测能直接对规范值。
     */
    public static byte[] passwordKey(Info info, String password, int pwdRef) {
        try {
            byte[] input = pwdRef == PWD_CAN
                    ? password.getBytes("ISO-8859-1")
                    : Crypt.sha1(password.getBytes("US-ASCII"));
            return Crypt.kdf(input, 3, info.aes, info.keyBits);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String pwdName(int ref) {
        switch (ref) {
            case PWD_MRZ: return "MRZ";
            case PWD_CAN: return "CAN";
            case 0x03: return "PIN";
            case 0x04: return "PUK";
            default: return "0x" + Integer.toHexString(ref);
        }
    }

    private static byte[] mseData(Info info, int pwdRef) {
        return Crypt.concat(
                Tlv.encode(0x80, info.oid),
                Tlv.encode(0x83, new byte[]{(byte) pwdRef}),
                Tlv.encode(0x84, new byte[]{(byte) info.paramId}));
    }

    /** 只发 MSE:Set AT，判断芯片是否接受这个 PACE 组合 / 这种密码参考（1 条 APDU）。 */
    public static boolean probe(CardSession session, Info info, int pwdRef) throws Exception {
        Apdu.Resp r = session.send(0x22, 0xC1, 0xA4, mseData(info, pwdRef), null);
        return r.ok();
    }

    public static boolean probe(CardSession session, Info info) throws Exception {
        return probe(session, info, PWD_MRZ);
    }

    /** 选一条能用的：优先 AES-256 → 192 → 128 → 3DES。 */
    public static Info pick(List<Info> infos) {
        Info best = null;
        for (Info i : infos) {
            if (i.paramId < 0) continue;
            if (EcCurve.byId(i.paramId) == null) continue;
            if (best == null || i.keyBits > best.keyBits) best = i;
        }
        return best;
    }

    /**
     * 执行 PACE 并装好安全通道。
     *
     * @param session 未建立 SM 的会话
     * @param mrzi    MRZ 信息（24 字符，见 {@link Mrz#mrzInfo()}）
     * @return 描述（如 "PACE-ECDH-GM-AES-128 · brainpoolP256r1"）
     */
    public static String establish(CardSession session, Info info, String mrzi) throws Exception {
        return establish(session, info, mrzi, PWD_MRZ);
    }

    /**
     * @param password 密码原文：MRZ 时是 24 字符 MRZi；CAN 时是证件正面那串数字（原样 ASCII）
     * @param pwdRef   {@link #PWD_MRZ} 或 {@link #PWD_CAN}
     */
    public static String establish(CardSession session, Info info, String password, int pwdRef) throws Exception {
        EcCurve curve = EcCurve.byId(info.paramId);
        if (curve == null) throw new IllegalStateException("不支持的椭圆曲线参数编号 " + info.paramId);

        byte[] kPi = passwordKey(info, password, pwdRef);

        // ---- MSE:Set AT
        Apdu.Resp r = session.send(0x22, 0xC1, 0xA4, mseData(info, pwdRef), null);
        if (!r.ok()) throw new IllegalStateException("PACE MSE:Set AT 失败（密码参考 " + pwdName(pwdRef)
                + "）：" + r.describe());

        // ---- 取 nonce 并解密
        r = ga(session, new byte[]{0x7C, 0x00}, false);
        if (!r.ok()) throw new IllegalStateException("PACE 读取 nonce 失败：" + r.describe());
        byte[] encNonce = dynAuth(r.data, 0x80);
        if (encNonce == null || encNonce.length % (info.aes ? 16 : 8) != 0) {
            throw new IllegalStateException("PACE nonce 长度异常：" + (encNonce == null ? "无" : encNonce.length));
        }
        byte[] raw;
        if (info.aes) raw = Crypt.aesCbc(kPi, new byte[16], encNonce, false);
        else raw = Crypt.desCbc(kPi, new byte[8], encNonce, false);
        // §4.4.3.3 说随机数按 ISO 9797-1 填充方法 2 加密（密文比 s 多一块）；
        // 但现场这张卡的 7C 80 就是 16 字节（**没有填充**），强行 unpad 会抛
        // "ISO9797 padding 缺失"，PACE 直接死在第一步。两种都接受：
        // 只有末尾确实是 0x80 00… 时才剥离，否则整段 16 字节就是 s。
        int blk = info.aes ? 16 : 8;
        // 密文只有一块 ⇒ 芯片根本没加填充（现场卡就是 16 字节），整段就是 s；
        // 多出至少一块 ⇒ 末尾应该是 0x80 00…，能剥就剥，剥不掉也按原样用（不抛异常）。
        byte[] nonce = encNonce.length <= blk ? raw : tryUnpad(raw);

        // ---- Generic Mapping：G' = s·G + H
        BigInteger dMap = Ec.randomScalar(curve);
        Ec.Point pkMap = Ec.baseMultiply(curve, dMap);
        r = ga(session, Tlv.encode(0x7C, Tlv.encode(0x81, Ec.encode(curve, pkMap))), false);
        if (!r.ok()) throw new IllegalStateException("PACE 映射交换失败：" + r.describe());
        byte[] pkMapIcBytes = dynAuth(r.data, 0x82);
        if (pkMapIcBytes == null) throw new IllegalStateException("PACE 响应缺少 7C/82");
        Ec.Point pkMapIc = Ec.decode(curve, pkMapIcBytes);
        if (pkMapIc.x.equals(pkMap.x) && pkMapIc.y.equals(pkMap.y)) {
            throw new IllegalStateException("芯片回显了终端公钥（反射攻击）");
        }
        Ec.Point h = Ec.multiply(curve, pkMapIc, dMap);

        // signum=1：无符号大端。传 0 会把最高位为 1 的 s 解析成负数，点乘结果就全错。
        BigInteger sVal = new BigInteger(1, nonce).mod(curve.n);
        Ec.Point mappedG = Ec.add(curve, Ec.baseMultiply(curve, sVal), h);
        if (!Ec.isOnCurve(curve, mappedG)) throw new IllegalStateException("映射后的基点不在曲线上");

        // ---- 密钥协商
        BigInteger dKa = Ec.randomScalar(curve);
        Ec.Point pkKa = Ec.multiply(curve, mappedG, dKa);
        r = ga(session, Tlv.encode(0x7C, Tlv.encode(0x83, Ec.encode(curve, pkKa))), false);
        if (!r.ok()) throw new IllegalStateException("PACE 密钥协商失败：" + r.describe());
        byte[] pkKaIcBytes = dynAuth(r.data, 0x84);
        if (pkKaIcBytes == null) throw new IllegalStateException("PACE 响应缺少 7C/84");
        Ec.Point pkKaIc = Ec.decode(curve, pkKaIcBytes);
        if (pkKaIc.x.equals(pkKa.x) && pkKaIc.y.equals(pkKa.y)) {
            throw new IllegalStateException("芯片回显了终端公钥（反射攻击）");
        }
        byte[] shared = Ec.sharedSecret(curve, dKa, pkKaIc);

        byte[] ksEnc = Crypt.kdf(shared, 1, info.aes, info.keyBits);
        byte[] ksMac = Crypt.kdf(shared, 2, info.aes, info.keyBits);

        byte[] tIfd = authToken(info, ksMac, Ec.encode(curve, pkKaIc));
        byte[] tIcExp = authToken(info, ksMac, Ec.encode(curve, pkKa));
        // 第 4 步是链条最后一条：CLA 必须从 0x10 切回 0x00
        r = ga(session, Tlv.encode(0x7C, Tlv.encode(0x85, tIfd)), true);
        if (!r.ok()) throw new IllegalStateException("PACE 认证令牌交换失败：" + r.describe());
        byte[] tIc = dynAuth(r.data, 0x86);
        if (tIc == null || !java.util.Arrays.equals(tIc, tIcExp)) {
            throw new IllegalStateException("PACE 芯片认证令牌不匹配（MRZ 可能读错）");
        }

        session.setSm(new SecureMessaging(info.aes, ksEnc, ksMac, SecureMessaging.zeroSsc(info.aes)));
        return String.format(Locale.US, "PACE-ECDH-GM-%s · %s(参数 %d) · 密码 %s",
                info.aes ? "AES-" + info.keyBits : "3DES", curve.name, curve.id, pwdName(pwdRef));
    }

    /**
     * 发一条 GENERAL AUTHENTICATE。
     *
     * <p>§9.3.2 要求用命令链把 PACE 的 GA 序列串起来；多数芯片 CLA=0x00 也接受，
     * 但个别芯片（现场那张伊拉克卡，MSE:Set AT 回 9000 后 GA 直接 6800）只认带链位的写法。
     * 这里先用 0x00，收到 6800/6E00 时用 **CLA=0x10** 原样重发一次——数据与卡片状态都不变，
     * 失败也不会让会话更糟。
     */
    /**
     * 发一条 GENERAL AUTHENTICATE。
     *
     * <p>§9.3.2 要求用命令链把 PACE 的四步串起来，且**最后一步要摘掉链位**：
     * <pre>
     *   Step 1~3（取 nonce / 映射 / 密钥协商）：10 86 00 00 …
     *   Step 4  （T_PCD 认证令牌）            ：00 86 00 00 …  ← 链结束
     * </pre>
     * 芯片不认某种 CLA（6800/6E00，或回 9000 但不给数据）时，换另一种 CLA 原样重发一次。
     *
     * @param last true = 第 4 步（认证令牌），用 CLA=0x00
     */
    private static Apdu.Resp ga(CardSession session, byte[] data, boolean last) throws Exception {
        int cla = last ? 0x00 : 0x10;
        Apdu.Resp r = session.sendPlain(cla, 0x86, 0x00, 0x00, data, 0x00);
        boolean noData = r.ok() && r.data.length == 0;      // 有的芯片把链位当"待续"，回 9000 但不给数据
        if (r.sw == 0x6800 || r.sw == 0x6E00 || noData) {
            int alt = last ? 0x10 : 0x00;
            session.log.add("GA（CLA=0x" + String.format(Locale.US, "%02X", cla) + "）"
                    + (r.ok() ? "无数据" : "回 " + r.swHex())
                    + "，改用 CLA=0x" + String.format(Locale.US, "%02X", alt) + " 重发");
            r = session.sendPlain(alt, 0x86, 0x00, 0x00, data, 0x00);
        }
        return r;
    }

    /** 只在末尾确实是 ISO 9797-1 填充（0x80 后全 0）时才剥离；否则原样返回。 */
    private static byte[] tryUnpad(byte[] d) {
        int i = d.length - 1;
        while (i >= 0 && d[i] == 0) i--;
        if (i < 0 || i == d.length - 1 || d[i] != (byte) 0x80) return d;
        byte[] out = new byte[i];
        System.arraycopy(d, 0, out, 0, i);
        return out;
    }

    private static byte[] authToken(Info info, byte[] ksMac, byte[] publicKey) {
        byte[] data = Tlv.encode(0x7F49, Crypt.concat(
                Tlv.encode(0x06, info.oid),
                Tlv.encode(0x86, publicKey)));
        if (info.aes) return Crypt.aesCmac(ksMac, data, 8);
        return Crypt.retailMac(ksMac, Crypt.pad(data, 8));
    }

    /** 从 7C 动态认证数据里取子标签（public：假芯片单测也用它解析）。 */
    public static byte[] dynAuth(byte[] data, int tag) {
        if (data == null) return null;
        List<Tlv> roots = Tlv.parse(data);
        for (Tlv t : roots) {
            if (t.tag != 0x7C) continue;
            Tlv v = t.find(tag);
            if (v != null) return v.value;
        }
        return null;
    }
}
