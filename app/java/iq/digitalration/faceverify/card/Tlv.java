package iq.digitalration.faceverify.card;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 极简 BER/DER TLV 解析器：够解析 EF.CardAccess、DG1/DG2/DG11、EF.SOD 与 PACE 的 7C 动态认证数据。
 *
 * <p>支持多字节 tag（5F1F、7F61 这类）与短/长格式长度（含 0x80 不定长，直接按后续长度读）。
 * 不做 DER 规范性校验——芯片给的数据只要能读出来就行。
 */
public final class Tlv {

    public final int tag;
    public final byte[] value;
    /** 整个 TLV（含 tag+len+value），SOD/DG 哈希要按它算。 */
    public final byte[] raw;

    public Tlv(int tag, byte[] value, byte[] raw) {
        this.tag = tag;
        this.value = value;
        this.raw = raw;
    }

    public static List<Tlv> parse(byte[] data) {
        List<Tlv> out = new ArrayList<>();
        parseInto(data, 0, data.length, out);
        return out;
    }

    public List<Tlv> children() {
        return parse(value);
    }

    /** 按 tag 找第一个直接子节点；找不到返回 null。 */
    public Tlv find(int tag) {
        for (Tlv t : children()) if (t.tag == tag) return t;
        return null;
    }

    /** 递归查找（深度优先）。 */
    public Tlv findDeep(int tag) {
        if (this.tag == tag) return this;
        for (Tlv t : children()) {
            Tlv r = t.findDeep(tag);
            if (r != null) return r;
        }
        return null;
    }

    public static void parseInto(byte[] d, int from, int to, List<Tlv> out) {
        int p = from;
        while (p + 2 <= to) {
            int tagStart = p;
            int tag = d[p++] & 0xFF;
            if ((tag & 0x1F) == 0x1F) {
                // 多字节 tag：后续字节最高位为 1 表示还要继续（5F1F / 7F61 / 5F8100…）
                int b;
                do {
                    if (p >= to) return;
                    b = d[p++] & 0xFF;
                    tag = (tag << 8) | b;
                } while ((b & 0x80) != 0);
            }
            if (p >= to) return;
            int lb = d[p++] & 0xFF;
            int len;
            if (lb < 0x80) {
                len = lb;
            } else if (lb == 0x80) {
                len = to - p;                    // 不定长：吃到结尾（本 App 用不到，兜底）
            } else {
                int n = lb & 0x7F;
                if (n > 4 || p + n > to) return;
                len = 0;
                for (int i = 0; i < n; i++) len = (len << 8) | (d[p++] & 0xFF);
            }
            if (len < 0 || p + len > to) return;
            byte[] v = new byte[len];
            System.arraycopy(d, p, v, 0, len);
            byte[] raw = new byte[p + len - tagStart];
            System.arraycopy(d, tagStart, raw, 0, raw.length);
            out.add(new Tlv(tag, v, raw));
            p += len;
        }
    }

    /**
     * 只解析开头的 tag+length，返回 {头字节数, value 长度}。
     * 用于 EF 文件：先读 4 字节头部得到总长，再分块读余下部分。
     */
    public static int[] header(byte[] d) {
        int p = 0;
        if (d.length < 2) return null;
        int tag = d[p++] & 0xFF;
        if ((tag & 0x1F) == 0x1F) {
            int b;
            do {
                if (p >= d.length) return null;
                b = d[p++] & 0xFF;
            } while ((b & 0x80) != 0);
        }
        if (p >= d.length) return null;
        int lb = d[p++] & 0xFF;
        int len;
        if (lb < 0x80) {
            len = lb;
        } else {
            int n = lb & 0x7F;
            if (n == 0 || n > 4 || p + n > d.length) return null;
            len = 0;
            for (int i = 0; i < n; i++) len = (len << 8) | (d[p++] & 0xFF);
        }
        return new int[]{p, len};
    }

    public static byte[] encode(int tag, byte[] value) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        if (tag > 0xFF) {
            o.write((tag >> 8) & 0xFF);
            o.write(tag & 0xFF);
        } else {
            o.write(tag);
        }
        byte[] l = encodeLen(value.length);
        o.write(l, 0, l.length);
        o.write(value, 0, value.length);
        return o.toByteArray();
    }

    public static byte[] encodeLen(int len) {
        if (len < 0x80) return new byte[]{(byte) len};
        if (len <= 0xFF) return new byte[]{(byte) 0x81, (byte) len};
        return new byte[]{(byte) 0x82, (byte) (len >> 8), (byte) len};
    }

    public static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    public static String hex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02X", x & 0xFF));
        return sb.toString();
    }

    public static String hex(byte[] b, int max) {
        if (b == null) return "null";
        String s = hex(b);
        return s.length() <= max * 2 ? s : s.substring(0, max * 2) + "…";
    }

    public static byte[] unhex(String s) {
        String t = s.replaceAll("\\s", "");
        byte[] o = new byte[t.length() / 2];
        for (int i = 0; i < o.length; i++) {
            o[i] = (byte) Integer.parseInt(t.substring(i * 2, i * 2 + 2), 16);
        }
        return o;
    }

    public List<Tlv> unmodifiableChildren() {
        return Collections.unmodifiableList(children());
    }
}
