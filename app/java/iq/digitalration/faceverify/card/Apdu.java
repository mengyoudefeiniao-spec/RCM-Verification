package iq.digitalration.faceverify.card;

import java.io.ByteArrayOutputStream;

/** APDU 编解码（只做短 APDU：eMRTD 的 READ BINARY / SELECT / MSE / GA 都在 256 字节内）。 */
public final class Apdu {

    public static final int SW_OK = 0x9000;
    public static final int SW_EOF = 0x6282;          // 读到文件尾
    public static final int SW_WRONG_LENGTH = 0x6700;
    public static final int SW_WRONG_LE = 0x6C00;     // 6Cxx：Le 不对，xx 是正确长度
    public static final int SW_SECURITY = 0x6982;

    private Apdu() {}

    public static final class Resp {
        public final byte[] data;
        public final int sw;

        public Resp(byte[] data, int sw) {
            this.data = data == null ? new byte[0] : data;
            this.sw = sw & 0xFFFF;
        }

        public boolean ok() { return sw == SW_OK; }

        public String swHex() { return String.format("%04X", sw); }

        public String describe() {
            switch (sw) {
                case SW_OK: return "9000 OK";
                case SW_EOF: return "6282 文件结束";
                case SW_SECURITY: return "6982 安全状态不满足（未认证或密钥错误）";
                case 0x63C0: return "63Cx 校验失败";
                case 0x6A81: return "6A81 功能不支持";
                case 0x6A82: return "6A82 文件/应用未找到";
                case 0x6A86: return "6A86 参数不正确";
                case 0x6A88: return "6A88 引用数据未找到";
                case 0x6A80: return "6A80 数据不正确";
                case 0x6300: return "6300 认证失败";
                default: return swHex();
            }
        }
    }

    /** 组 C-APDU；le 传 null 表示无 Le，0 表示 256。 */
    public static byte[] cmd(int cla, int ins, int p1, int p2, byte[] data, Integer le) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(cla & 0xFF);
        o.write(ins & 0xFF);
        o.write(p1 & 0xFF);
        o.write(p2 & 0xFF);
        int n = data == null ? 0 : data.length;
        if (n > 0) {
            if (n > 255) throw new IllegalArgumentException("短 APDU 数据超长: " + n);
            o.write(n);
            o.write(data, 0, n);
        }
        if (le != null) o.write(le & 0xFF);
        return o.toByteArray();
    }

    /** 拆 R-APDU：最后两字节是状态字。 */
    public static Resp parse(byte[] r) {
        if (r == null || r.length < 2) throw new IllegalStateException("R-APDU 太短");
        int sw = ((r[r.length - 2] & 0xFF) << 8) | (r[r.length - 1] & 0xFF);
        byte[] d = new byte[r.length - 2];
        System.arraycopy(r, 0, d, 0, d.length);
        return new Resp(d, sw);
    }

    public static byte[] selectAid() {
        return cmd(0x00, 0xA4, 0x04, 0x0C, Tlv.unhex("A0000002471001"), null);
    }

    public static byte[] selectEf(int fid) {
        return cmd(0x00, 0xA4, 0x02, 0x0C, new byte[]{(byte) (fid >> 8), (byte) fid}, null);
    }

    public static byte[] readBinary(int offset, int len) {
        return cmd(0x00, 0xB0, (offset >> 8) & 0x7F, offset & 0xFF, null, len & 0xFF);
    }

    public static byte[] getChallenge(int len) {
        return cmd(0x00, 0x84, 0x00, 0x00, null, len & 0xFF);
    }

    public static byte[] mseSetAT(byte[] data) {
        return cmd(0x00, 0x22, 0xC1, 0xA4, data, null);
    }

    public static byte[] generalAuthenticate(boolean chaining, byte[] data) {
        return cmd(chaining ? 0x10 : 0x00, 0x86, 0x00, 0x00, data, 0x00);
    }

    public static byte[] externalAuthenticate(byte[] data) {
        return cmd(0x00, 0x82, 0x00, 0x00, data, 0x00);
    }
}
