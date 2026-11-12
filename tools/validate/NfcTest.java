import iq.digitalration.faceverify.card.ActiveAuth;
import iq.digitalration.faceverify.card.CardSession;
import iq.digitalration.faceverify.card.Csca;
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
import java.util.ArrayList;
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

    /** 在假芯片记录的 C-APDU 列表里按十六进制找第一条，返回下标（找不到 -1）。 */
    static int indexOfCmd(VirtualCard vc, String hex) {
        for (int i = 0; i < vc.cmds.size(); i++) {
            if (Tlv.hex(vc.cmds.get(i)).equals(hex)) return i;
        }
        return -1;
    }

    /** 测试辅助：用私钥签一段数据（模拟芯片内部私钥）。 */
    static byte[] sign(java.security.PrivateKey key, String alg, byte[] data) throws Exception {
        java.security.Signature s = java.security.Signature.getInstance(alg);
        s.initSign(key);
        s.update(data);
        return s.sign();
    }

    /** 测试辅助：从 SOD 的 certs 里挑出自我签发的 CSCA（fixture 里 DS 由它签发）。 */
    static java.security.cert.X509Certificate cscaFromSod(byte[] sod) throws Exception {
        Tlv root = null;
        for (Tlv t : Tlv.parse(sod)) if (t.tag == 0x77) root = t;
        if (root == null) return null;
        Tlv sd = null;
        for (Tlv t : Tlv.parse(root.value)) if (t.tag == 0x30) { sd = t; break; }
        if (sd == null) return null;
        Tlv certs = null;
        for (Tlv c : sd.children()) if (c.tag == 0xA0) certs = c;
        if (certs == null) return null;
        java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
        for (Tlv c : certs.children()) {
            if (c.tag != 0x30) continue;
            java.security.cert.X509Certificate x = (java.security.cert.X509Certificate)
                    cf.generateCertificate(new java.io.ByteArrayInputStream(c.raw));
            if (x.getSubjectX500Principal().equals(x.getIssuerX500Principal())) return x;
        }
        return null;
    }

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

        // ICAO 9303-11 附录 D 的 EXTERNAL AUTHENTICATE 示例：验证 E_IFD / M_IFD 的加密与 retail MAC。
        // 假芯片用的是同一套 Crypt 实现，只靠"假芯片能通"证明不了算法对，必须对官方向量。
        byte[] vRndIc = Tlv.unhex("4608F91988702212");
        byte[] vRndIfd = Tlv.unhex("781723860C06C226");
        byte[] vKifd = Tlv.unhex("0B795240CB7049B01C19B33E32804F0B");
        byte[] vPlain = Crypt.concat(vRndIfd, vRndIc, vKifd);
        String eIfd = Tlv.hex(Crypt.desCbc(kEnc, new byte[8], vPlain, true));
        String mIfd = Tlv.hex(Crypt.retailMac(kMac, Crypt.pad(Tlv.unhex(eIfd), 8)));
        check("E.IFD", eIfd.equals("72C29C2371CC9BDB65B779B8E8D37B29ECC154AA56A8799FAE2F498F76ED92F2"), eIfd);
        check("M.IFD", mIfd.equals("5F1448EEA8AD90A7"), mIfd);

        System.out.println("=== 1b. 可选文件（EF.CardAccess）的拒绝码语义 ===");
        // 现场实测：伊拉克 eMRTD 对 SELECT EF.CardAccess(011C) 回 6982（安全状态不满足），
        // 不是 6A82。这一码必须当"芯片不开放该文件"，否则界面会把 PACE 探测显示成"读卡失败"。
        check("SELECT 6982 → 视为不可用（不抛异常）",
                new CardSession(cmd -> new byte[]{(byte) 0x69, (byte) 0x82}).readFile(0x011C, true) == null,
                "6982");
        check("SELECT 6A82 → 视为不存在",
                new CardSession(cmd -> new byte[]{(byte) 0x6A, (byte) 0x82}).readFile(0x011C, true) == null,
                "6A82");
        check("SELECT 6D00 → 视为不支持",
                new CardSession(cmd -> new byte[]{(byte) 0x6D, (byte) 0x00}).readFile(0x011C, true) == null,
                "6D00");
        boolean strictThrew = false;
        try {
            new CardSession(cmd -> new byte[]{(byte) 0x6A, (byte) 0x86}).readFile(0x011C, true);
        } catch (IllegalStateException e) {
            strictThrew = true;
        }
        check("SELECT 6A86 → 仍抛异常（真错误不吞）", strictThrew, "6A86");

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

        System.out.println("=== 4b. MRZ（TD3 护照样本，2×44，ICAO 附录示例）===");
        Mrz m3 = Mrz.parse("P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
                + "L898902C36UTO7408122F1204159ZE184226B<<<<<10");
        check("格式", "TD3".equals(m3.format), m3.format);
        check("证件号", "L898902C3".equals(m3.docNumber) && m3.docNumberOk, m3.docNumber);
        check("出生/有效期", "740812".equals(m3.dob) && m3.dobOk
                && "120415".equals(m3.expiry) && m3.expiryOk, m3.dob + "/" + m3.expiry);
        check("综合校验位", m3.compositeOk, "");
        check("MRZi", "L898902C3674081221204159".equals(m3.mrzInfo()), m3.mrzInfo());

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
        check("芯片未声明 DG15 → 不做 AA", r1.aa == null, "aa=" + r1.aa);

        System.out.println("=== 4c. 真实卡（伊拉克国民卡）MRZ → BAC 密钥 ===");
        Mrz m4 = Mrz.parse("IDIRQAC67648836197662080255<<<"
                + "7602067M2705088IRQ<<<<<<<<<<<5"
                + "ALEQYLY<<ELY<<<<<<<<<<<<<<<<<<");
        check("格式/证件号", "TD1".equals(m4.format) && "AC6764883".equals(m4.docNumber), m4.format + " " + m4.docNumber);
        check("出生/有效期", "760206".equals(m4.dob) && "270508".equals(m4.expiry), m4.dob + "/" + m4.expiry);
        check("MRZi", "AC6764883676020672705088".equals(m4.mrzInfo()), m4.mrzInfo());
        byte[] ks4 = new byte[16];
        System.arraycopy(Crypt.sha1(m4.mrzInfo().getBytes("US-ASCII")), 0, ks4, 0, 16);
        check("Kseed", "738D010B58A3E1C224625B3637CCF8E7".equals(Tlv.hex(ks4)), Tlv.hex(ks4));
        check("Kenc", "C7948F2F9D7A7FE31986DCEC324CD575".equals(Tlv.hex(Crypt.kdf(ks4, 1, false, 112))),
                Tlv.hex(Crypt.kdf(ks4, 1, false, 112)));
        check("Kmac", "EF0D520DBC078A86D0C79D68B064D54C".equals(Tlv.hex(Crypt.kdf(ks4, 2, false, 112))),
                Tlv.hex(Crypt.kdf(ks4, 2, false, 112)));

        System.out.println("=== 7. 假芯片：PACE-ECDH-GM-AES-128 全流程 ===");
        VirtualCard.mfSelected = false;
        IcaoReader.Result r2 = IcaoReader.read(new VirtualCard(dir, true), m.mrzInfo(), text -> { });
        for (String l : r2.log) System.out.println("       · " + l);
        check("读取成功", r2.ok, r2.error);
        check("安全通道", r2.accessControl.startsWith("PACE"), r2.accessControl);
        check("EF.CardAccess 从主文件读（§9.2.11）", VirtualCard.mfSelected, "没有 SELECT MF");
        check("DG1 MRZ", r2.doc.mrz != null && "AE0644356".equals(r2.doc.mrz.docNumber), "");
        check("DG2 人像", r2.doc.faceImage != null && r2.doc.faceImage.length > 1000, r2.faceInfo());
        check("被动认证", r2.sod != null && r2.sod.signatureOk && r2.sod.dgHashesOk,
                r2.sod == null ? "null" : r2.sod.summary());

        System.out.println("=== 7b. 假芯片：无 EF.CardAccess 时 PACE 盲试（显式 PACE_ONLY）===");
        IcaoReader.Result r4 = IcaoReader.read(new VirtualCard(dir, true, false), m.mrzInfo(),
                text -> { }, IcaoReader.MODE_PACE_ONLY);
        for (String l : r4.log) System.out.println("       · " + l);
        check("读取成功（盲试）", r4.ok, r4.error);
        check("安全通道", r4.accessControl != null && r4.accessControl.startsWith("PACE"), r4.accessControl);

        System.out.println("=== 7c. 假芯片：MODE_PACE_ONLY（BAC 超时后跳过 BAC）===");
        IcaoReader.Result r5 = IcaoReader.read(new VirtualCard(dir, true), m.mrzInfo(),
                text -> { }, IcaoReader.MODE_PACE_ONLY);
        for (String l : r5.log) System.out.println("       · " + l);
        check("读取成功（跳过 BAC）", r5.ok, r5.error);
        check("安全通道", r5.accessControl != null && r5.accessControl.startsWith("PACE"), r5.accessControl);

        System.out.println("=== 7d. 假芯片：PACE 密码用 CAN（不是 MRZ）===");
        VirtualCard.CAN = "123456";
        IcaoReader.Result r6 = IcaoReader.read(new VirtualCard(dir, true, false), m.mrzInfo(),
                text -> { }, IcaoReader.MODE_PACE_ONLY, "123456");
        for (String l : r6.log) System.out.println("       · " + l);
        check("读取成功（PACE-CAN）", r6.ok, r6.error);
        check("安全通道", r6.accessControl != null && r6.accessControl.contains("CAN"), r6.accessControl);
        VirtualCard.CAN = "";

        System.out.println("=== 7e. 现场卡形态：有 CAN 也必须 BAC 优先，且全程不碰 EF.CardAccess ===");
        VirtualCard.breakPace = true;            // PACE 一旦被尝试就必失败（复现现场 6800）
        VirtualCard.cardAccessTouched = false;
        IcaoReader.Result r7 = IcaoReader.read(new VirtualCard(dir, false), m.mrzInfo(),
                text -> { }, IcaoReader.MODE_AUTO, "317584");
        for (String l : r7.log) System.out.println("       · " + l);
        boolean noPace = true;
        for (String l : r7.log) if (l.contains("PACE 盲试")) noPace = false;
        check("读取成功（BAC 优先）", r7.ok, r7.error);
        check("安全通道是 BAC", r7.accessControl != null && r7.accessControl.startsWith("BAC"), r7.accessControl);
        check("没有走 PACE 盲试", noPace, "");
        check("先探测了 EF.CardAccess（PACE 优先）", VirtualCard.cardAccessTouched, "");
        VirtualCard.breakPace = false;

        System.out.println("=== 7f. 通信中断分类：BAC 阶段 TagLost → FAIL_TRANSPORT / stage=BAC ===");
        VirtualCard.failBacTransport = true;
        IcaoReader.Result r8 = IcaoReader.read(new VirtualCard(dir, false), m.mrzInfo(),
                text -> { }, IcaoReader.MODE_AUTO, null);
        for (String l : r8.log) System.out.println("       · " + l);
        check("失败分类=通信中断", r8.failureKind == IcaoReader.Result.FAIL_TRANSPORT,
                "kind=" + r8.failureKind);
        check("失败阶段=BAC", "BAC".equals(r8.failStage), r8.failStage);
        VirtualCard.failBacTransport = false;

        System.out.println("=== 7g. 盲试提前退出：首个 General Authenticate 6800 立即停 ===");
        VirtualCard.breakPace = true;
        IcaoReader.Result r9 = IcaoReader.read(new VirtualCard(dir, true, false), m.mrzInfo(),
                text -> { }, IcaoReader.MODE_PACE_ONLY, null);
        for (String l : r9.log) System.out.println("       · " + l);
        boolean stopped = false;
        for (String l : r9.log) if (l.contains("停止盲试")) stopped = true;
        check("失败分类=协议拒绝", r9.failureKind == IcaoReader.Result.FAIL_PROTOCOL,
                "kind=" + r9.failureKind);
        check("6800 后提前停止盲试", stopped, "");
        check("APDU 条数 ≤12（原 33）", r9.apduCount <= 12, String.valueOf(r9.apduCount));
        VirtualCard.breakPace = false;

        System.out.println("=== 7h. BAC 报文结构：SELECT / GET CHALLENGE / EXTERNAL AUTHENTICATE ===");
        VirtualCard vc8 = new VirtualCard(dir, false);
        IcaoReader.Result r10 = IcaoReader.read(vc8, m.mrzInfo(), text -> { }, IcaoReader.MODE_AUTO, null);
        check("读取成功", r10.ok, r10.error);
        // PACE 优先后，SELECT AID 不再固定是第 1 条（前面有 SELECT MF / SELECT 011C），按内容定位
        int ai = indexOfCmd(vc8, "00A4040C07A0000002471001");
        String cCh = ai >= 0 && ai + 1 < vc8.cmds.size() ? Tlv.hex(vc8.cmds.get(ai + 1)) : "";
        String cExt = ai >= 0 && ai + 2 < vc8.cmds.size() ? Tlv.hex(vc8.cmds.get(ai + 2)) : "";
        check("存在 SELECT AID（P2=0C）", ai >= 0, "index=" + ai);
        check("紧接 GET CHALLENGE（Le=8，共 5 字节）", "0084000008".equals(cCh), cCh);
        check("再紧接 EXTERNAL AUTHENTICATE（Lc=28，40 字节，Le=28，共 46 字节）",
                cExt.startsWith("0082000028") && cExt.length() == 46 * 2 && cExt.endsWith("28"), cExt);
        boolean rawLogged = false;
        String tag = "APDU #" + (ai + 3) + " ← ";
        for (String l : r10.log) if (l.startsWith(tag) && l.contains("9000")) rawLogged = true;
        check("日志里有 EXTERNAL AUTH 的原始响应（9000）", rawLogged, "");

        System.out.println("=== 7j. 现场卡形态：芯片要求带 Le 的 EXTERNAL AUTH（第一次就发 46 字节）===");
        VirtualCard.requireLeForExtAuth = true;
        VirtualCard vc9 = new VirtualCard(dir, false);
        IcaoReader.Result r11 = IcaoReader.read(vc9, m.mrzInfo(), text -> { }, IcaoReader.MODE_AUTO, null);
        for (String l : r11.log) System.out.println("       · " + l);
        int ai9 = indexOfCmd(vc9, "00A4040C07A0000002471001");
        boolean firstTry = ai9 >= 0 && ai9 + 2 < vc9.cmds.size() && vc9.cmds.get(ai9 + 2).length == 46;
        check("读取成功（带 Le 一次通过）", r11.ok, r11.error);
        check("EXTERNAL AUTH 首次即 46 字节（不浪费挑战）", firstTry,
                ai9 >= 0 && ai9 + 2 < vc9.cmds.size() ? String.valueOf(vc9.cmds.get(ai9 + 2).length) : "cmds=" + vc9.cmds.size());
        VirtualCard.requireLeForExtAuth = false;

        System.out.println("=== 7k. 反向芯片：带 Le 回 6700 → 重选应用 + 重新取挑战 + 无 Le 重发 ===");
        VirtualCard.rejectLeForExtAuth = true;
        VirtualCard vc10 = new VirtualCard(dir, false);
        IcaoReader.Result r12 = IcaoReader.read(vc10, m.mrzInfo(), text -> { }, IcaoReader.MODE_AUTO, null);
        for (String l : r12.log) System.out.println("       · " + l);
        int ai10 = indexOfCmd(vc10, "00A4040C07A0000002471001");
        boolean seq = ai10 >= 0 && ai10 + 5 < vc10.cmds.size()
                && vc10.cmds.get(ai10 + 2).length == 46 && (vc10.cmds.get(ai10 + 2)[1] & 0xFF) == 0x82
                && (vc10.cmds.get(ai10 + 3)[1] & 0xFF) == 0xA4      // 6700 后重选应用
                && (vc10.cmds.get(ai10 + 4)[1] & 0xFF) == 0x84      // 重新 GET CHALLENGE
                && vc10.cmds.get(ai10 + 5).length == 45;            // 再以无 Le 发
        check("读取成功（换长度后）", r12.ok, r12.error);
        check("6700 → 重选 → 重取挑战 → 无 Le 重发", seq,
                ai10 >= 0 && ai10 + 5 < vc10.cmds.size()
                        ? String.format("%d/%02X/%02X/%d", vc10.cmds.get(ai10 + 2).length,
                        vc10.cmds.get(ai10 + 3)[1] & 0xFF, vc10.cmds.get(ai10 + 4)[1] & 0xFF,
                        vc10.cmds.get(ai10 + 5).length)
                        : "cmds=" + vc10.cmds.size());
        VirtualCard.rejectLeForExtAuth = false;

        System.out.println("=== 7l. 现场卡形态：nonce 无填充 + PACE 四步 CLA=10/10/10/00 ===");
        VirtualCard.noPadNonce = true;
        VirtualCard vc11 = new VirtualCard(dir, true);
        IcaoReader.Result r13 = IcaoReader.read(vc11, m.mrzInfo(), text -> { }, IcaoReader.MODE_AUTO, null);
        for (String l : r13.log) System.out.println("       · " + l);
        int gaN = 0;
        StringBuilder clas = new StringBuilder();
        for (byte[] c : vc11.cmds) {
            if ((c[1] & 0xFF) == 0x86) {
                gaN++;
                clas.append(String.format("%02X ", c[0] & 0xFF));
            }
        }
        check("读取成功（16 字节 nonce 不做 unpad）", r13.ok, r13.error);
        check("GA 四步 CLA = 10 10 10 00", gaN >= 4 && clas.toString().startsWith("10 10 10 00 "), clas.toString());
        VirtualCard.noPadNonce = false;

        System.out.println("=== 7i. PACE 口令派生 Kπ（§9.7.3 表11）===");
        Pace.Info aesInfo = Pace.defaultCandidates().get(0);      // AES-128 / brainpoolP384(13)
        String canKpi = Tlv.hex(Pace.passwordKey(aesInfo, "123456", Pace.PWD_CAN));
        check("CAN 直接编码（不再 SHA-1）", "591468CDA83D65219CCCB8560233600F".equals(canKpi), canKpi);
        String mrzKpi = Tlv.hex(Pace.passwordKey(aesInfo, "L898902C<369080619406236", Pace.PWD_MRZ));
        check("MRZ 先 SHA-1", "7DF6B4716ABD95CC58E7D2559D3600C8".equals(mrzKpi), mrzKpi);

        System.out.println("=== 8. 错误 MRZ 应当失败 ===");
        IcaoReader.Result r3 = IcaoReader.read(new VirtualCard(dir, false), "AE0644356291051262707245", text -> { });
        check("错误 MRZ 被拒绝", !r3.ok && r3.error != null, r3.error);

        System.out.println("=== 9. 主动认证（DG15/AA）与 CSCA 锚定 ===");
        // 9a. AA 验签：芯片用内部私钥签随机挑战，RSA(SHA-1/SHA-256) 与 ECDSA 都要支持
        java.security.KeyPairGenerator rsaGen = java.security.KeyPairGenerator.getInstance("RSA");
        rsaGen.initialize(2048);
        java.security.KeyPair rsa = rsaGen.generateKeyPair();
        byte[] chal = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        byte[] sig1 = sign(rsa.getPrivate(), "SHA1withRSA", chal);
        check("AA RSA-SHA1 验签", "SHA1withRSA".equals(ActiveAuth.verifySignature(rsa.getPublic(), chal, sig1)), "");
        byte[] sig256 = sign(rsa.getPrivate(), "SHA256withRSA", chal);
        check("AA RSA-SHA256 验签", "SHA256withRSA".equals(ActiveAuth.verifySignature(rsa.getPublic(), chal, sig256)), "");
        byte[] badSig = sig1.clone();
        badSig[0] ^= 0x01;
        check("AA 篡改签名被拒", ActiveAuth.verifySignature(rsa.getPublic(), chal, badSig) == null, "");
        check("AA 换挑战被拒（防重放）",
                ActiveAuth.verifySignature(rsa.getPublic(), new byte[]{8, 7, 6, 5, 4, 3, 2, 1}, sig1) == null, "");

        // 9b. DG15 结构：6F { 30 SubjectPublicKeyInfo }
        // 真实 DG15 = 6F { SubjectPublicKeyInfo }，SPKI 自己就以 0x30 开头（不要再包一层 0x30）
        byte[] dg15 = Tlv.encode(0x6F, rsa.getPublic().getEncoded());
        byte[] spki = ActiveAuth.spkiOf(dg15);
        check("DG15 → SPKI", spki != null && java.util.Arrays.equals(spki, rsa.getPublic().getEncoded()),
                spki == null ? "null" : (spki.length + " 字节"));
        check("DG15 → 公钥可解析", ActiveAuth.publicKey(spki) != null, "");
        ActiveAuth.Result aaNoKey = ActiveAuth.run(
                new CardSession(new VirtualCard(dir, false)), null);
        check("DG15 为空时 AA 不抛异常、如实报未通过",
                aaNoKey.attempted && !aaNoKey.ok && !aaNoKey.error.isEmpty(), aaNoKey.summary());

        // 9c. CSCA：空库只能说"未收录"，装入 fixture 里的 CSCA 之后才允许说"由 IQ 签发"
        Csca.clear();
        PassiveAuth.Result prEmpty = PassiveAuth.verify(sod, dgs);
        check("CSCA 空库 → 未收录", !prEmpty.cscaTrusted && prEmpty.cscaStoreSize == 0, prEmpty.cscaReason);
        check("空库时 summary 写明未收录", prEmpty.summary().contains("CSCA 未收录"), prEmpty.summary());

        java.security.cert.X509Certificate fixtureCsca = cscaFromSod(sod);
        check("SOD 里能取到自签 CSCA", fixtureCsca != null, "");
        Csca.add(fixtureCsca);
        PassiveAuth.Result prAnchor = PassiveAuth.verify(sod, dgs);
        for (String d : prAnchor.details) System.out.println("       · " + d);
        check("CSCA 装入后锚定", prAnchor.cscaTrusted, prAnchor.cscaReason);
        check("CSCA 国别取自证书 C=IQ", "IQ".equals(prAnchor.cscaCountry), prAnchor.cscaCountry);
        check("锚定后 summary 标记", prAnchor.summary().contains("CSCA 已锚定(IQ)"), prAnchor.summary());
        Csca.clear();
        check("Csca.clear() 后回到未收录", Csca.size() == 0, String.valueOf(Csca.size()));

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
        /** PACE 密码改用 CAN 时（密码参考 0x02）的值；空 = 卡只认 MRZ。 */
        static String CAN = "";
        /** true = 复现现场卡形态：MSE:Set AT 回 9000 但 General Authenticate 一律 6800（v2.9 日志 idcard-diag-20261110-143055）。 */
        static boolean breakPace = false;
        /** true = 复现现场卡：nonce 密文只有一块（16 字节），不带 ISO9797 填充。 */
        static boolean noPadNonce = false;
        /** 现场卡一 SELECT EF.CardAccess 就复位会话——这里记录"有没有碰过"，用于断言 BAC 优先。 */
        static boolean cardAccessTouched = false;
        /** true = BAC 的 GET CHALLENGE 直接抛 IOException("Tag was lost.")，复现"通信中断而非芯片拒绝"。 */
        static boolean failBacTransport = false;
        /** 是否收到过 SELECT MF（EF.CardAccess 属于主文件，§9.2.11）。 */
        static boolean mfSelected = false;
        /** true = EXTERNAL AUTHENTICATE 必须带 Le，否则回 6700（现场卡形态）。 */
        static boolean requireLeForExtAuth = false;
        /** true = 反过来：带了 Le 就回 6700（用来验证"换长度必须重新取挑战"）。 */
        static boolean rejectLeForExtAuth = false;
        static byte[] KSEED = new byte[16];
        private int pwdRef = 0x01;
        /** PACE 的安全环境必须在主文件里建立（§4.4）；用来复现"在应用 DF 下 GA 回 6800"。 */
        private boolean mfActive;

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

        /** true = 只支持 PACE（对 BAC 的 GET CHALLENGE 返回 6A81），用来测"BAC 失败后回退 PACE"。 */
        private final boolean paceOnly;

        VirtualCard(String dir, boolean usePace) throws Exception {
            this(dir, usePace, usePace);
        }

        /**
         * @param usePace        支持 PACE（且拒绝 BAC）
         * @param exposeCardAccess 是否提供 EF.CardAccess；false = 现场那种"被 6982 挡住、只能盲试"的卡
         */
        VirtualCard(String dir, boolean usePace, boolean exposeCardAccess) throws Exception {
            this.paceOnly = usePace;
            files.put(0x0101, Files.readAllBytes(Paths.get(dir, "dg1.bin")));
            files.put(0x0102, Files.readAllBytes(Paths.get(dir, "dg2.bin")));
            files.put(0x010B, Files.readAllBytes(Paths.get(dir, "dg11.bin")));
            files.put(0x011D, Files.readAllBytes(Paths.get(dir, "sod.bin")));
            files.put(0x011E, Files.readAllBytes(Paths.get(dir, "com.bin")));
            if (usePace && exposeCardAccess) {
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

        /** 收到的原始 C-APDU（只记前 10 条），用来断言 BAC 的报文结构。 */
        final List<byte[]> cmds = new ArrayList<>();

        @Override public byte[] transceive(byte[] cmd) throws Exception {
            if (cmds.size() < 10) cmds.add(cmd.clone());
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
                    if (c.p1 == 0x00) { mfSelected = true; mfActive = true; return new Resp(new byte[0], 0x9000); }   // SELECT MF
                    if (c.p1 == 0x04) { mfActive = false; return new Resp(new byte[0], 0x9000); }
                    if (c.p1 == 0x02) {
                        int fid = ((c.data[0] & 0xFF) << 8) | (c.data[1] & 0xFF);
                        if (fid == 0x011C) cardAccessTouched = true;
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
                    if (failBacTransport) throw new java.io.IOException("Tag was lost.");
                    // 只支持 PACE 的卡（usePace=true）不给 BAC 挑战：这样测试 7 才能真正验证
                    // 「BAC 先试 → 被拒 → 读 EF.CardAccess → 走 PACE」这条回退链路
                    if (paceOnly) return new Resp(new byte[0], 0x6A81);
                    rndIc = Crypt.random(8);
                    return new Resp(rndIc, 0x9000);
                case 0x82:
                    if (paceOnly) return new Resp(new byte[0], 0x6A81);
                    // 两种现场形态：
                    //   requireLeForExtAuth：不带 Le 回 6700（老做法）
                    //   rejectLeForExtAuth ：带了 Le 回 6700（反过来的芯片）
                    // 关键：6700 之后原挑战作废（真实芯片回 6985），逼着实现重新 GET CHALLENGE
                    if (requireLeForExtAuth && c.le == null) { rndIc = null; return new Resp(new byte[0], 0x6700); }
                    if (rejectLeForExtAuth && c.le != null) { rndIc = null; return new Resp(new byte[0], 0x6700); }
                    if (rndIc == null) return new Resp(new byte[0], 0x6985);
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
            if (!java.util.Arrays.equals(Crypt.retailMac(kMacL, Crypt.pad(eifd, 8)), mifd)) return new Resp(new byte[0], 0x6300);
            byte[] dec = Crypt.desCbc(kEncL, new byte[8], eifd, false);
            rndIfd = slice(dec, 0, 8);
            byte[] icEcho = slice(dec, 8, 8);
            kIfd = slice(dec, 16, 16);
            byte[] kIc = Crypt.random(16);
            byte[] enc = Crypt.desCbc(kEncL, new byte[8], Crypt.concat(icEcho, rndIfd, kIc), true);
            byte[] mac = Crypt.retailMac(kMacL, Crypt.pad(enc, 8));
            byte[] seed2 = Crypt.concat(kIfd, kIc);
            kEncBac = Crypt.kdf(seed2, 1, false, 112);
            kMacBac = Crypt.kdf(seed2, 2, false, 112);
            sm = new ServerSm(false, kEncBac, kMacBac, SecureMessaging.bacSsc(icEcho, rndIfd));
            return new Resp(Crypt.concat(enc, mac), 0x9000);
        }

        private Resp paceMse(byte[] data) {
            // 现场卡：不在主文件下做 PACE 就是 MSE 9000、GA 6800。这里同样表态，
            // 让"读完 CardAccess 又选回应用 DF"的回归立刻暴露。
            if (!mfActive) return new Resp(new byte[0], 0x6800);
            curve = EcCurve.byId(paceParamId);
            for (Tlv t : Tlv.parse(data)) {
                if (t.tag == 0x83 && t.value.length > 0) {
                    pwdRef = t.value[0] & 0xFF;          // 0x01=MRZ, 0x02=CAN
                }
                if (t.tag == 0x84 && t.value.length > 0) {
                    paceParamId = t.value[0] & 0xFF;
                    curve = EcCurve.byId(paceParamId);
                }
            }
            if (curve == null) return new Resp(new byte[0], 0x6A86);
            if (pwdRef == 0x02 && CAN.isEmpty()) return new Resp(new byte[0], 0x6A88);   // 卡要求 CAN，但没给
            if (!CAN.isEmpty() && pwdRef != 0x02) return new Resp(new byte[0], 0x6A88); // 卡只认 CAN
            paceStarted = true;
            return new Resp(new byte[0], 0x9000);
        }

        private Resp paceGeneralAuth(byte[] data) throws Exception {
            if (breakPace) return new Resp(new byte[0], 0x6800);   // 现场：Get Nonce 阶段就被拒
            if (!paceStarted || curve == null) return new Resp(new byte[0], 0x6985);
            if (data.length == 2 && data[0] == 0x7C && data[1] == 0x00) {
                nonce = new BigInteger(1, Crypt.random(16)).mod(curve.n);
                String pwd = pwdRef == 0x02 ? CAN : MRZI;
                // §9.7.3 表11：CAN 直接按 ISO/IEC 8859-1 编码，MRZ 才先 SHA-1
                byte[] kInput = pwdRef == 0x02
                        ? pwd.getBytes("ISO-8859-1")
                        : Crypt.sha1(pwd.getBytes("US-ASCII"));
                byte[] kPi = Crypt.kdf(kInput, 3, true, 128);
                byte[] s16 = Ec.fill(nonce, 16);
                // noPadNonce：现场卡只回一块密文（16 字节），没有 ISO9797 填充
                byte[] enc = Crypt.aesCbc(kPi, new byte[16], noPadNonce ? s16 : Crypt.pad(s16, 16), true);
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
                // 与产品代码保持一致：AES-CMAC 用自己的 10* 填充，不能预补 0x80 块
                if (aes) return Crypt.aesCmac(ksMac, d, 8);
                return Crypt.retailMac(ksMac, Crypt.pad(d, block));
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
