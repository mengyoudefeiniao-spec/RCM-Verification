package iq.digitalration.faceverify.card;

import java.util.ArrayList;
import java.util.List;

/** 从芯片读出的证件数据（DG1 机读区、DG2 人像、DG11 附加信息、EF.COM 数据组清单）。 */
public final class DocData {

    public Mrz mrz;

    public byte[] faceImage;
    public String faceFormat = "";     // JPEG / JPEG2000 / 未知
    public byte[] dg2Raw;

    public final PersonDetails person = new PersonDetails();
    public byte[] dg11Raw;

    /** EF.COM 里的数据组编号清单。 */
    public final List<Integer> dgList = new ArrayList<>();

    /** LDS / Unicode 版本（EF.COM 里 tag 5F36 / 5F37）。 */
    public String ldsVersion = "", unicodeVersion = "";

    public static final class PersonDetails {
        public String name = "";           // 5F0E
        public String otherNames = "";     // 5F0F
        public String personalNumber = ""; // 5F10
        public String fullDob = "";        // 5F2B
        public String placeOfBirth = "";   // 5F11
        public String address = "";        // 5F42
        public String telephone = "";      // 5F12
        public String profession = "";     // 5F13
        public String title = "";          // 5F14
        public String summary = "";        // 5F15
        /** DG11 里出现的所有 tag，便于排查厂商私有字段。 */
        public final List<String> rawTags = new ArrayList<>();
    }

    // ---------------------------------------------------------------- 解析

    /** EF.COM：60 { 5F01 LDS版本, 5F36 Unicode版本, 5C 数据组清单 } */
    public void parseCom(byte[] com) {
        if (com == null) return;
        for (Tlv t : Tlv.parse(com)) {
            if (t.tag != 0x60) continue;
            for (Tlv c : t.children()) {
                if (c.tag == 0x5F01) ldsVersion = ascii(c.value);
                else if (c.tag == 0x5F36) unicodeVersion = ascii(c.value);
                else if (c.tag == 0x5C) {
                    for (byte b : c.value) dgList.add(b & 0xFF);
                }
            }
        }
    }

    /** DG1：61 { 5F1F MRZ } */
    public void parseDg1(byte[] dg1) {
        if (dg1 == null) return;
        for (Tlv t : Tlv.parse(dg1)) {
            if (t.tag != 0x61) continue;
            Tlv mrzNode = t.find(0x5F1F);
            if (mrzNode != null) mrz = Mrz.parse(ascii(mrzNode.value));
        }
    }

    /** DG2：75 { 7F61 { 7F60 BHT, 5F2E 图像 } } */
    public void parseDg2(byte[] dg2) {
        dg2Raw = dg2;
        if (dg2 == null) return;
        byte[] img = null;
        for (Tlv t : Tlv.parse(dg2)) {
            if (t.tag != 0x75) continue;
            Tlv body = t.find(0x7F61);
            List<Tlv> scan = body != null ? body.children() : t.children();
            for (Tlv c : scan) {
                if (c.tag == 0x5F2E) img = c.value;
                else if (c.tag == 0x7F2E) {
                    // 有些实现把图像再包一层
                    for (Tlv cc : c.children()) {
                        if (cc.tag == 0x5F2E) img = cc.value;
                    }
                    if (img == null) img = c.value;
                }
            }
            if (img == null) {
                Tlv deep = t.findDeep(0x5F2E);
                if (deep != null) img = deep.value;
            }
        }
        if (img == null) return;
        faceImage = img;
        faceFormat = guessImageFormat(img);
    }

    private static String guessImageFormat(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "JPEG";
        if (b.length > 4 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0x4F
                && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x51) return "JPEG2000";
        if (b.length > 12 && (b[4] & 0xFF) == 0x6A && (b[5] & 0xFF) == 0x50
                && (b[6] & 0xFF) == 0x20 && (b[7] & 0xFF) == 0x20) return "JPEG2000";
        if (b.length > 2 && (b[0] & 0xFF) == 0x50 && (b[1] & 0xFF) == 0x4B) return "PNG";
        return "未知";
    }

    /** DG11：6B { 5C 标签清单, 5F0E 姓名, … } */
    public void parseDg11(byte[] dg11) {
        dg11Raw = dg11;
        if (dg11 == null) return;
        for (Tlv t : Tlv.parse(dg11)) {
            if (t.tag != 0x6B) continue;
            for (Tlv c : t.children()) {
                if (c.tag == 0x5C) continue;
                person.rawTags.add(String.format("%02X", c.tag));
                String v = text(c.value);
                switch (c.tag) {
                    case 0x5F0E: person.name = v; break;
                    case 0x5F0F: person.otherNames = v; break;
                    case 0x5F10: person.personalNumber = v; break;
                    case 0x5F2B: person.fullDob = v; break;
                    case 0x5F11: person.placeOfBirth = join(person.placeOfBirth, v); break;
                    case 0x5F42: person.address = join(person.address, v); break;
                    case 0x5F12: person.telephone = v; break;
                    case 0x5F13: person.profession = v; break;
                    case 0x5F14: person.title = v; break;
                    case 0x5F15: person.summary = v; break;
                    default: break;
                }
            }
        }
    }

    private static String join(String a, String b) {
        if (a == null || a.isEmpty()) return b;
        if (b == null || b.isEmpty()) return a;
        return a + " / " + b;
    }

    static String ascii(byte[] b) {
        try {
            return new String(b, "US-ASCII");
        } catch (Exception e) {
            return new String(b);
        }
    }

    /** DG11 的文本字段可能是 UTF-8，也可能是 Latin-1（厂商差异），这里优先 UTF-8 且不允许替换。 */
    static String text(byte[] b) {
        try {
            java.nio.charset.CharsetDecoder dec = java.nio.charset.Charset.forName("UTF-8")
                    .newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            return dec.decode(java.nio.ByteBuffer.wrap(b)).toString().trim();
        } catch (Exception e) {
            return new String(b, java.nio.charset.Charset.forName("ISO-8859-1")).trim();
        }
    }
}
