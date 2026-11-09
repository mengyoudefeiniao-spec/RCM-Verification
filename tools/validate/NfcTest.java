import iq.digitalration.faceverify.card.CardSession;
import iq.digitalration.faceverify.card.Crypt;
import iq.digitalration.faceverify.card.Ec;
import iq.digitalration.faceverify.card.EcCurve;
import iq.digitalration.faceverify.card.IcaoReader;
import iq.digitalration.faceverify.card.Mrz;
import iq.digitalration.faceverify.card.Pace;
import iq.digitalration.faceverify.card.PassiveAuth;
import iq.digitalration.faceverify.card.SecureMessaging;
import iq.digitalration.faceverify.card.Tlv;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NFC / ICAO 协议栈的 PC 端验证：
 *   1) BAC 密钥派生（ICAO 9303-11 附录测试向量）
 *   2) AES-CMAC（RFC 4493）
 *   3) brainpool / NIST 曲线标量乘与 ECDH（参考值由 python cryptography 生成）
 *   4) MRZ TD1 解析与校验位
 *   5) EF.SOD 的 CMS 验签 + 数据组哈希（样本由 tools/nfc-proto/gen_nfc_sample.py 生成）
 *   6) 用"假芯片"完整跑 BAC 与 PACE-ECDH-GM 的读取流程（APDU + 安全报文）
 *
 * 用法：java -cp ... NfcTest dl/tmp/nfc-sample
 */
public class NfcTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  OK   " + name + (detail == null || detail.isEmpty() ? "" : "  (" + detail + ")"));
        } else {
            fail++;
            System.out.println("  FAIL " + name + (detail == null ? "" : "  → " + detail));
        }
    }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : "dl/tmp/nfc-sample";

        System.out.println("=== 1. BAC 密钥派生（ICAO 9303-11 附录示例 MRZ L898902C<369080619406236）===");
        byte[] ks = new byte[16];
        System.arraycopy(Crypt.sha1("L898902C<369080619406236".getBytes("US-ASCII")), 0, ks, 0, 16);
        byte[] kEnc = Crypt.kdf(ks, 1, false, 112);
        byte[] kMac = Crypt.kdf(ks, 2, false, 112);
        check("Kseed", Tlv.hex(ks).equals("239AB9CB282DAF66231DC5A4DF6BFBAE"), Tlv.hex(ks));
        check("Kenc", Tlv.hex(kEnc).equals("AB94FDECF2674FDFB9B391F85D7F76F2"), Tlv.hex(kEnc));
        check("Kmac", Tlv.hex(kMac).equals("7962D9ECE03D1ACD4C76089DCE131543"), Tlv.hex(kMac));

        System.out.println("=== 2. AES-CMAC（RFC 4493）===");
        byte[] ck = Tlv.unhex("2b7e151628aed2a6abf7158809cf4f3c");
        check("空消息", Tlv.hex(Crypt.aesCmac(ck, new byte[0], 16)).equals("BB1D6929E95937287FA37D129B756746"),
                Tlv.hex(Crypt.aesCmac(ck, new byte[0], 16)));
        byte[] m40 = Tlv.unhex("6bc1bee22e409f96e93d7e117393172a"
                + "ae2d8a571e03ac9c9eb76fac45af8e51" + "30c81c46a35ce411");   // 40 字节，最后一块不完整
        check("40 字节消息", Tlv.hex(Crypt.aesCmac(ck, m40, 16)).equals("DFA66747DE9AE63030CA32611497C827"),
                Tlv.hex(Crypt.aesCmac(ck, m40, 16)));
        check("截断 8 字节", Tlv.hex(Crypt.aesCmac(ck, m40, 8)).equals("DFA66747DE9AE630"), "");
        byte[] m64 = Tlv.unhex("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51"
                + "30c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710");
        check("64 字节消息", Tlv.hex(Crypt.aesCmac(ck, m64, 16)).equals("51F0BEBF7E3B9D92FC49741779363CFE"),
                Tlv.hex(Crypt.aesCmac(ck, m64, 16)));

        System.out.println("=== 3. 椭圆曲线（参考值来自 python cryptography）===");
        List<String> vec = Files.readAllLines(Paths.get(dir, "vectors.txt"));
        for (String line : vec) {
            String[] p = line.split("\\|");
            if (p[0].equals("shared")) continue;
            EcCurve c = curveByName(p[0]);
            if (c == null) {
                check(p[0] + " 曲线表", false, "EcCurve 里找不到");
                continue;
            }
            BigInteger dA = new BigInteger(p[1], 16);
            Ec.Point pubA = Ec.baseMultiply(c, dA);
            check(p[0] + " 标量乘", pubA.x.equals(new BigInteger(p[2], 16))
                    && pubA.y.equals(new BigInteger(p[3], 16)), "");
            BigInteger dB = new BigInteger(p[4], 16);
            Ec.Point pubB = Ec.baseMultiply(c, dB);
            check(p[0] + " 对端标量乘", pubB.x.equals(new BigInteger(p[5], 16))
                    && pubB.y.equals(new BigInteger(p[6], 16)), "");
            for (String l2 : vec) {
                String[] q = l2.split("\\|");
                if (q[0].equals("shared") && q[1].equals(p[0])) {
                    byte[] s1 = Ec.sharedSecret(c, dA, pubB);
                    check(p[0] + " ECDH 共享密钥", Tlv.hex(s1).equalsIgnoreCase(q[2]), Tlv.hex(s1));
                }
            }
        }

        System.out.println("=== 4. MRZ（TD1 样本）===");
        byte[] dg1 = Files.readAllBytes(Paths.get(dir, "dg1.bin"));
        String mrzRaw = new String(dg1, "ISO-8859-1");
        int at = mrzRaw.indexOf("IDIRQ");
        String mrz = mrzRaw.substring(at, at + 90);
        Mrz m = Mrz.parse(mrz);
        System.out.println("       MRZ: " + mrz);
        check("格式", "TD1".equals(m.format), m.format);
        check("证件号", "AE0644356".equals(m.docNumber), m.docNumber);
        check("出生", "910512".equals(m.dob) && m.dobOk, m.dob + " ok=" + m.dobOk);
        check("有效期", "270724".equals(m.expiry) && m.expiryOk, m.expiry + " ok=" + m.expiryOk);
        check("综合校验位", m.compositeOk, "");
        check("MRZi", "AE0644356291051262707244".equals(m.mrzInfo()), m.mrzInfo());
        check("姓名", m.nameDisplay().contains("ALDAWDY"), m.nameDisplay());

        System.out.println("=== 5. EF.SOD 被动认证 ===");
        Map<Integer, byte[]> dgs = new HashMap<>();
        dgs.put(1, dg1);
        dgs.put(2, Files.readAllBytes(Paths.get(dir, "dg2.bin")));
        dgs.put(11, Files.readAllBytes(Paths.get(dir, "dg11.bin")));
        byte[] sod = Files.readAllBytes(Paths.get(dir, "sod.bin"));
        PassiveAuth.Result pr = PassiveAuth.verify(sod, dgs);
        for (String d : pr.details) System.out.println("       · " + d);
        check("SOD 解析", pr.sodParsed, pr.error);
        check("签名有效", pr.signatureOk, "");
        check("messageDigest", pr.messageDigestOk, "");
        check("数据组哈希 3/3", pr.dgHashesOk && pr.dgMatched == 3, pr.dgMatched + "/" + pr.dgDeclared);
        check("证书链自洽", pr.chainOk, "");
        check("证书有效期", pr.certValidOk, "");

        byte[] tampered = dgs.get(11).clone();
        tampered[tampered.length - 1] ^= 0x01;
        Map<Integer, byte[]> bad = new HashMap<>(dgs);
        bad.put(11, tampered);
        PassiveAuth.Result pr2 = PassiveAuth.verify(sod, bad);
        check("篡改后哈希不通过", !pr2.dgHashesOk, pr2.dgMatched + "/" + pr2.dgDeclared);

        VirtualCard.MRZI = m.mrzInfo();
        VirtualCard.KSEED = new byte[16];
        System.arraycopy(Crypt.sha1(m.mrzInfo().getBytes("US-ASCII")), 0, VirtualCard.KSEED, 0, 16);

        System.out.println("=== 6. 假芯片：BAC 全流程 ===");
        IcaoReader.Result r1 = IcaoReader.read(new VirtualCard(dir, false), m.mrzInfo(), text -> { });
        for (String l : r1.log) System.out.println("       · " + l);
        check("读取成功", r1.ok, r1.error);
        check("安全通道", r1.accessControl.startsWith("BAC"), r1.accessControl);
        check("DG1 MRZ", r1.doc.mrz != null && "AE0644356".equals(r1.doc.mrz.docNumber), "");
        check("DG2 人像", r1.doc.faceImage != null && r1.doc.faceImage.length > 1000, r1.faceInfo());
        check("DG2 格式", "JPEG".equals(r1.doc.faceFormat), r1.doc.faceFormat);
        check("DG11 姓名", "ALDAWDY<<JWD".equals(r1.doc.person.name), r1.doc.person.name);
        check("DG11 家庭编号", "1002E6996574360150".equals(r1.doc.person.personalNumber), r1.doc.person.personalNumber);
        check("被动认证", r1.sod != null && r1.sod.signatureOk && r1.sod.dgHashesOk,
                r1.sod == null ? "null" : r1.sod.summary());
        check("EF.COM 数据组", r1.doc.dgList.size() == 3, String.valueOf(r1.doc.dgList));

        System.out.println("=== 7. 假芯片：PACE-ECDH-GM-AES-128 全流程 ===");
        IcaoReader.Result r2 = IcaoReader.read(new VirtualCard(dir, true), m.mrzInfo(), text -> { });
        for (String l : r2.log) System.out.println("       · " + l);
        check("读取成功", r2.ok, r2.error);
        check("安全通道", r2.accessControl.startsWith("PACE"), r2.accessControl);
        check("DG1 MRZ", r2.doc.mrz != null && "AE0644356".equals(r2.doc.mrz.docNumber), "");
        check("DG2 人像", r2.doc.faceImage != null && r2.doc.faceImage.length > 1000, r2.faceInfo());
        check("被动认证", r2.sod != null && r2.sod.signatureOk && r2.sod.dgHashesOk,
                r2.sod == null ? "null" : r2.sod.summary());

        System.out.println("=== 8. 错误 MRZ 应当失败 ===");
        IcaoReader.Result r3 = IcaoReader.read(new VirtualCard(dir, false), "AE0644356291051262707245", text -> { });
        check("错误 MRZ 被拒绝", !r3.ok && r3.error != null, r3.error);

        System.out.println();
        System.out.println(fail == 0 ? "NFC TESTS PASSED (" + pass + ")" : "NFC TESTS FAILED: " + fail + "/" + (pass + fail));
        if (fail != 0) System.exit(1);
    }

    static EcCurve curveByName(String name) {
        String key = name.toLowerCase();
        for (int id = 0; id <= 18; id++) {
            EcCurve c = EcCurve.byId(id);
            if (c == null) continue;
            String n = c.name.toLowerCase().replace(" ", "");
            if (key.startsWith("brainpool") && n.equals("brainpool" + key.substring(9))) return c;
            if (key.startsWith("secp") && n.equals("nistp" + key.substring(4, 7))) return c;
        }
        return null;
    }

    // ------------------------------------------------------------------ 假芯片

    /** 只实现 App 用到的那几条 APDU；芯片侧 PACE/BAC 与安全报文独立实现一遍，用来检验报文格式。 */
    static final class VirtualCard implements CardSession.Transport {

        static String MRZI = "";
        static byte[] KSEED = new byte[16];

        private final Map<Integer, byte[]> files = new HashMap<>();
        private int selectedFid;
        private ServerSm sm;

        private byte[] rndIc;
        private byte[] kIfd, rndIfd, kEncBac, kMacBac;

        private boolean paceStarted;
        private int paceParamId = 13;
        private byte[] paceOid = Tlv.unhex("04007F00070202040202");   // ECDH-GM-AES-128
        private EcCurve curve;
        private BigInteger nonce, chipMapPriv, chipKaPriv;
        private Ec.Point termMapPub, mappedG, termKaPub, chipKaPub;
        private byte[] ksEnc, ksMac;

        VirtualCard(String dir, boolean usePace) throws Exception {
            files.put(0x0101, Files.readAllBytes(Paths.get(dir, "dg1.bin")));
            files.put(0x0102, Files.readAllBytes(Paths.get(dir, "dg2.bin")));
            files.put(0x010B, Files.readAllBytes(Paths.get(dir, "dg11.bin")));
            files.put(0x011D, Files.readAllBytes(Paths.get(dir, "sod.bin")));
            files.put(0x011E, Files.readAllBytes(Paths.get(dir, "com.bin")));
            if (usePace) {
                byte[] info = Tlv.encode(0x30, Crypt.concat(
                        Tlv.encode(0x06, paceOid),
                        Tlv.encode(0x02, new byte[]{0x02}),
                        Tlv.encode(0x02, new byte[]{(byte) paceParamId})));
                files.put(0x011C, Tlv.encode(0x31, info));
            }
        }

        /** 一条业务命令的结果。 */
        static final class Resp {
            final byte[] data;
            final int sw;

            Resp(byte[] data, int sw) {
                this.data = data;
                this.sw = sw;
            }
        }

        @Override public byte[] transceive(byte[] cmd) throws Exception {
            if (sm != null) return sm.handle(cmd);
            Cmd c = Cmd.parse(cmd);
            Resp r = business(c);
            return encode(r.data, r.sw);
        }

        static final class Cmd {
            int ins, p1, p2;
            byte[] data = new byte[0];
            Integer le;

            static Cmd parse(byte[] cmd) {
                Cmd c = new Cmd();
                c.ins = cmd[1] & 0xFF;
                c.p1 = cmd[2] & 0xFF;
                c.p2 = cmd[3] & 0xFF;
                int p = 4;
                if (p < cmd.length) {
                    int lc = cmd[p++] & 0xFF;
                    if (lc > 0 && p + lc <= cmd.length) {
                        c.data = new byte[lc];
                        System.arraycopy(cmd, p, c.data, 0, lc);
                        p += lc;
                    }
                    if (p < cmd.length) c.le = cmd[p] & 0xFF;
                }
                return c;
            }
        }

        private Resp business(Cmd c) throws Exception {
            switch (c.ins) {
                case 0xA4:
                    if (c.p1 == 0x04) return new Resp(new byte[0], 0x9000);
                    if (c.p1 == 0x02) {
                        int fid = ((c.data[0] & 0xFF) << 8) | (c.data[1] & 0xFF);
                        selectedFid = fid;
                        return new Resp(new byte[0], files.containsKey(fid) ? 0x9000 : 0x6A82);
                    }
                    return new Resp(new byte[0], 0x6A86);
                case 0xB0: {
                    byte[] f = files.get(selectedFid);
                    if (f == null) return new Resp(new byte[0], 0x6A82);
                    int off = ((c.p1 & 0x7F) << 8) | c.p2;
                    if (off >= f.length) return new Resp(new byte[0], 0x6282);
                    int want = c.le == null || c.le == 0 ? 256 : c.le;
                    int n = Math.min(want, f.length - off);
                    byte[] out = new byte[n];
                    System.arraycopy(f, off, out, 0, n);
                    return new Resp(out, 0x9000);
                }
                case 0x84:
                    rndIc = Crypt.random(8);
                    return new Resp(rndIc, 0x9000);
                case 0x82:
                    return bacExternalAuth(c.data);
                case 0x22:
                    return paceMse(c.data);
                case 0x86:
                    return paceGeneralAuth(c.data);
                default:
                    return new Resp(new byte[0], 0x6D00);
            }
        }

        private Resp bacExternalAuth(byte[] data) throws Exception {
            byte[] eifd = new byte[32], mifd = new byte[8];
            System.arraycopy(data, 0, eifd, 0, 32);
            System.arraycopy(data, 32, mifd, 0, 8);
            byte[] kEncL = Crypt.kdf(KSEED, 1, false, 112);
            byte[] kMacL = Crypt.kdf(KSEED, 2, false, 112);
            if (!java.util.Arrays.equals(Crypt.retailMac(kMacL, eifd), mifd)) return new Resp(new byte[0], 0x6300);
            byte[] dec = Crypt.desCbc(kEncL, new byte[8], eifd, false);
            rndIfd = slice(dec, 0, 8);
            byte[] icEcho = slice(dec, 8, 8);
            kIfd = slice(dec, 16, 16);
            byte[] kIc = Crypt.random(16);
            byte[] enc = Crypt.desCbc(kEncL, new byte[8], Crypt.concat(icEcho, rndIfd, kIc), true);
            byte[] mac = Crypt.retailMac(kMacL, enc);
            byte[] seed2 = Crypt.concat(kIfd, kIc);
            kEncBac = Crypt.kdf(seed2, 1, false, 112);
            kMacBac = Crypt.kdf(seed2, 2, false, 112);
            sm = new ServerSm(false, kEncBac, kMacBac, SecureMessaging.bacSsc(icEcho, rndIfd));
            return new Resp(Crypt.concat(enc, mac), 0x9000);
        }

        private Resp paceMse(byte[] data) {
            curve = EcCurve.byId(paceParamId);
            for (Tlv t : Tlv.parse(data)) {
                if (t.tag == 0x84 && t.value.length > 0) {
                    paceParamId = t.value[0] & 0xFF;
                    curve = EcCurve.byId(paceParamId);
                }
            }
            if (curve == null) return new Resp(new byte[0], 0x6A86);
            paceStarted = true;
            return new Resp(new byte[0], 0x9000);
        }

        private Resp paceGeneralAuth(byte[] data) throws Exception {
            if (!paceStarted || curve == null) return new Resp(new byte[0], 0x6985);
            if (data.length == 2 && data[0] == 0x7C && data[1] == 0x00) {
                nonce = new BigInteger(1, Crypt.random(16)).mod(curve.n);
                byte[] kPi = Crypt.kdf(Crypt.sha1(MRZI.getBytes("US-ASCII")), 3, true, 128);
                byte[] enc = Crypt.aesCbc(kPi, new byte[16], Crypt.pad(Ec.fill(nonce, 16), 16), true);
                return new Resp(Tlv.encode(0x7C, Tlv.encode(0x80, enc)), 0x9000);
            }
            byte[] p81 = Pace.dynAuth(data, 0x81);
            if (p81 != null) {
                termMapPub = Ec.decode(curve, p81);
                chipMapPriv = Ec.randomScalar(curve);
                Ec.Point chipPub = Ec.baseMultiply(curve, chipMapPriv);
                Ec.Point h = Ec.multiply(curve, termMapPub, chipMapPriv);
                mappedG = Ec.add(curve, Ec.baseMultiply(curve, nonce), h);
                return new Resp(Tlv.encode(0x7C, Tlv.encode(0x82, Ec.encode(curve, chipPub))), 0x9000);
            }
            byte[] p83 = Pace.dynAuth(data, 0x83);
            if (p83 != null) {
                termKaPub = Ec.decode(curve, p83);
                chipKaPriv = Ec.randomScalar(curve);
                chipKaPub = Ec.multiply(curve, mappedG, chipKaPriv);
                byte[] shared = Ec.sharedSecret(curve, chipKaPriv, termKaPub);
                ksEnc = Crypt.kdf(shared, 1, true, 128);
                ksMac = Crypt.kdf(shared, 2, true, 128);
                return new Resp(Tlv.encode(0x7C, Tlv.encode(0x84, Ec.encode(curve, chipKaPub))), 0x9000);
            }
            byte[] p85 = Pace.dynAuth(data, 0x85);
            if (p85 != null) {
                byte[] tIfdData = Tlv.encode(0x7F49, Crypt.concat(Tlv.encode(0x06, paceOid),
                        Tlv.encode(0x86, Ec.encode(curve, chipKaPub))));
                byte[] tIcData = Tlv.encode(0x7F49, Crypt.concat(Tlv.encode(0x06, paceOid),
                        Tlv.encode(0x86, Ec.encode(curve, termKaPub))));
                if (!java.util.Arrays.equals(Crypt.aesCmac(ksMac, tIfdData, 8), p85)) {
                    return new Resp(new byte[0], 0x6988);
                }
                sm = new ServerSm(true, ksEnc, ksMac, new byte[16]);
                return new Resp(Tlv.encode(0x7C, Tlv.encode(0x86, Crypt.aesCmac(ksMac, tIcData, 8))), 0x9000);
            }
            return new Resp(new byte[0], 0x6A80);
        }

        private static byte[] slice(byte[] d, int off, int len) {
            byte[] o = new byte[len];
            System.arraycopy(d, off, o, 0, len);
            return o;
        }

        private static byte[] encode(byte[] data, int sw) {
            byte[] o = new byte[data.length + 2];
            System.arraycopy(data, 0, o, 0, data.length);
            o[data.length] = (byte) (sw >> 8);
            o[data.length + 1] = (byte) sw;
            return o;
        }

        /** 芯片侧安全报文（与 App 的 SecureMessaging 互为镜像）。 */
        final class ServerSm {
            final boolean aes;
            final int block;
            final byte[] ksEnc, ksMac;
            byte[] ssc;

            ServerSm(boolean aes, byte[] ksEnc, byte[] ksMac, byte[] ssc) {
                this.aes = aes;
                this.block = aes ? 16 : 8;
                this.ksEnc = ksEnc;
                this.ksMac = ksMac;
                this.ssc = ssc.clone();
            }

            void inc() {
                for (int i = ssc.length - 1; i >= 0; i--) {
                    ssc[i]++;
                    if (ssc[i] != 0) return;
                }
            }

            byte[] mac(byte[] d) {
                byte[] padded = Crypt.pad(d, block);
                return aes ? Crypt.aesCmac(ksMac, padded, 8) : Crypt.retailMac(ksMac, padded);
            }

            byte[] cbc(byte[] d, boolean enc) {
                byte[] iv = aes ? Crypt.aesEcbBlock(ksEnc, ssc) : new byte[block];
                return aes ? Crypt.aesCbc(ksEnc, iv, d, enc) : Crypt.desCbc(ksEnc, iv, d, enc);
            }

            byte[] handle(byte[] cmd) throws Exception {
                inc();
                int ins = cmd[1] & 0xFF, p1 = cmd[2] & 0xFF, p2 = cmd[3] & 0xFF;
                int lc = cmd[4] & 0xFF;
                byte[] body = new byte[lc];
                System.arraycopy(cmd, 5, body, 0, lc);
                Tlv do87 = null, do8e = null, do97 = null;
                for (Tlv t : Tlv.parse(body)) {
                    if (t.tag == 0x87) do87 = t;
                    else if (t.tag == 0x8E) do8e = t;
                    else if (t.tag == 0x97) do97 = t;
                }
                byte[] macData = Crypt.concat(ssc,
                        Crypt.pad(new byte[]{0x0C, (byte) ins, (byte) p1, (byte) p2}, block),
                        do87 == null ? new byte[0] : do87.raw,
                        do97 == null ? new byte[0] : do97.raw);
                if (do8e == null || !java.util.Arrays.equals(mac(macData), do8e.value)) {
                    return encode(new byte[0], 0x6988);
                }
                byte[] plainData = new byte[0];
                if (do87 != null) {
                    byte[] ct = slice(do87.value, 1, do87.value.length - 1);
                    plainData = Crypt.unpad(cbc(ct, false));
                }
                Cmd c = new Cmd();
                c.ins = ins;
                c.p1 = p1;
                c.p2 = p2;
                c.data = plainData;
                c.le = do97 != null ? 0 : null;
                Resp r = business(c);

                inc();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ByteArrayOutputStream macIn = new ByteArrayOutputStream();
                macIn.write(ssc, 0, ssc.length);
                if (r.data.length > 0) {
                    byte[] enc = cbc(Crypt.pad(r.data, block), true);
                    byte[] v = new byte[enc.length + 1];
                    v[0] = 0x01;
                    System.arraycopy(enc, 0, v, 1, enc.length);
                    byte[] n87 = Tlv.encode(0x87, v);
                    out.write(n87, 0, n87.length);
                    macIn.write(n87, 0, n87.length);
                }
                byte[] n99 = Tlv.encode(0x99, new byte[]{(byte) (r.sw >> 8), (byte) r.sw});
                out.write(n99, 0, n99.length);
                macIn.write(n99, 0, n99.length);
                byte[] n8e = Tlv.encode(0x8E, mac(macIn.toByteArray()));
                out.write(n8e, 0, n8e.length);
                return encode(out.toByteArray(), 0x9000);
            }
        }
    }
}
