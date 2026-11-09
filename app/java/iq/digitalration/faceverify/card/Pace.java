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
        EcCurve curve = EcCurve.byId(info.paramId);
        if (curve == null) throw new IllegalStateException("不支持的椭圆曲线参数编号 " + info.paramId);

        byte[] kPi = Crypt.kdf(Crypt.sha1(mrzi.getBytes("US-ASCII")), 3, info.aes, info.keyBits);

        // ---- MSE:Set AT
        byte[] mse = Crypt.concat(
                Tlv.encode(0x80, info.oid),
                Tlv.encode(0x83, new byte[]{0x01}),                    // 1 = MRZ 作为密码
                Tlv.encode(0x84, new byte[]{(byte) info.paramId}));
        Apdu.Resp r = session.send(0x22, 0xC1, 0xA4, mse, null);
        if (!r.ok()) throw new IllegalStateException("PACE MSE:Set AT 失败：" + r.describe());

        // ---- 取 nonce 并解密
        r = session.send(0x86, 0x00, 0x00, new byte[]{0x7C, 0x00}, 0x00);
        if (!r.ok()) throw new IllegalStateException("PACE 读取 nonce 失败：" + r.describe());
        byte[] encNonce = dynAuth(r.data, 0x80);
        if (encNonce == null || encNonce.length % (info.aes ? 16 : 8) != 0) {
            throw new IllegalStateException("PACE nonce 长度异常：" + (encNonce == null ? "无" : encNonce.length));
        }
        byte[] nonce;
        if (info.aes) nonce = Crypt.aesCbc(kPi, new byte[16], encNonce, false);
        else nonce = Crypt.desCbc(kPi, new byte[8], encNonce, false);
        nonce = Crypt.unpad(nonce);

        // ---- Generic Mapping：G' = s·G + H
        BigInteger dMap = Ec.randomScalar(curve);
        Ec.Point pkMap = Ec.baseMultiply(curve, dMap);
        r = session.send(0x86, 0x00, 0x00, Tlv.encode(0x7C, Tlv.encode(0x81, Ec.encode(curve, pkMap))), 0x00);
        if (!r.ok()) throw new IllegalStateException("PACE 映射交换失败：" + r.describe());
        byte[] pkMapIcBytes = dynAuth(r.data, 0x82);
        if (pkMapIcBytes == null) throw new IllegalStateException("PACE 响应缺少 7C/82");
        Ec.Point pkMapIc = Ec.decode(curve, pkMapIcBytes);
        if (pkMapIc.x.equals(pkMap.x) && pkMapIc.y.equals(pkMap.y)) {
            throw new IllegalStateException("芯片回显了终端公钥（反射攻击）");
        }
        Ec.Point h = Ec.multiply(curve, pkMapIc, dMap);

        BigInteger sVal = new BigInteger(1, nonce).mod(curve.n);
        Ec.Point mappedG = Ec.add(curve, Ec.baseMultiply(curve, sVal), h);
        if (!Ec.isOnCurve(curve, mappedG)) throw new IllegalStateException("映射后的基点不在曲线上");

        // ---- 密钥协商
        BigInteger dKa = Ec.randomScalar(curve);
        Ec.Point pkKa = Ec.multiply(curve, mappedG, dKa);
        r = session.send(0x86, 0x00, 0x00, Tlv.encode(0x7C, Tlv.encode(0x83, Ec.encode(curve, pkKa))), 0x00);
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
        r = session.send(0x86, 0x00, 0x00, Tlv.encode(0x7C, Tlv.encode(0x85, tIfd)), 0x00);
        if (!r.ok()) throw new IllegalStateException("PACE 认证令牌交换失败：" + r.describe());
        byte[] tIc = dynAuth(r.data, 0x86);
        if (tIc == null || !java.util.Arrays.equals(tIc, tIcExp)) {
            throw new IllegalStateException("PACE 芯片认证令牌不匹配（MRZ 可能读错）");
        }

        session.setSm(new SecureMessaging(info.aes, ksEnc, ksMac, SecureMessaging.zeroSsc(info.aes)));
        return String.format(Locale.US, "PACE-ECDH-GM-%s · %s(参数 %d)",
                info.aes ? "AES-" + info.keyBits : "3DES", curve.name, curve.id);
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
