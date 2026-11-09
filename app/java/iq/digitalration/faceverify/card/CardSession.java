package iq.digitalration.faceverify.card;

import java.util.ArrayList;
import java.util.List;

/**
 * 与芯片的一次会话：负责 APDU 传输、安全报文的开关、EF 文件的分块读取。
 *
 * <p>传输层只暴露一个 {@link Transport}（Android 端就是 IsoDep.transceive），
 * 这样整个 ICAO 协议栈可以在 PC 上用"假芯片"做单测，不需要真机。
 */
public final class CardSession {

    public interface Transport {
        byte[] transceive(byte[] command) throws Exception;
    }

    private final Transport transport;
    private SecureMessaging sm;
    public final List<String> log = new ArrayList<>();
    public int apduCount;

    public CardSession(Transport t) {
        this.transport = t;
    }

    public boolean hasSm() { return sm != null; }

    public SecureMessaging sm() { return sm; }

    public void setSm(SecureMessaging sm) {
        this.sm = sm;
        log.add("安全通道已建立（" + (sm == null ? "无" : "SM") + "）");
    }

    /** 发一条 APDU（有 SM 时自动加/解保护）。data 为明文，le 为期望长度（null = 无 Le）。 */
    public Apdu.Resp send(int ins, int p1, int p2, byte[] data, Integer le) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            byte[] cmd = sm == null ? Apdu.cmd(0x00, ins, p1, p2, data, le)
                    : sm.wrap(ins, p1, p2, data, le);
            byte[] raw = transport.transceive(cmd);
            apduCount++;
            Apdu.Resp r = sm == null ? Apdu.parse(raw) : sm.unwrap(raw);
            if ((r.sw & 0xFF00) == Apdu.SW_WRONG_LE) {
                // 6Cxx 只有明文阶段能安全重发：SM 下芯片的 SSC 与我们的计数已经错位，
                // 重发必然 MAC 失败，所以直接抛给调用方（并给出芯片想要的长度）。
                if (sm != null) {
                    throw new IllegalStateException(String.format(
                            "芯片要求 Le=%d（%s），安全报文中无法重发", r.sw & 0xFF, r.describe()));
                }
                int correct = r.sw & 0xFF;
                le = correct == 0 ? 256 : correct;
                continue;
            }
            return r;
        }
        throw new IllegalStateException("芯片连续返回 6Cxx（Le 不匹配）");
    }

    public Apdu.Resp send(byte[] cmd, boolean protectedAlready) throws Exception {
        if (sm != null || protectedAlready) throw new IllegalStateException("请用 send(ins,...)");
        return Apdu.parse(transport.transceive(cmd));
    }

    /** 选 EF 并读完整个文件（按 TLV 头里的长度分块）。文件不存在返回 null。 */
    public byte[] readFile(int fid) throws Exception {
        Apdu.Resp r = send(0xA4, 0x02, 0x0C, new byte[]{(byte) (fid >> 8), (byte) fid}, null);
        if (!r.ok()) {
            if (r.sw == 0x6A82 || r.sw == 0x6A88) return null;
            throw new IllegalStateException(String.format("选择 EF %04X 失败：%s", fid, r.describe()));
        }
        Apdu.Resp head = send(0xB0, 0x00, 0x00, null, 4);
        if (!head.ok() || head.data.length < 2) {
            throw new IllegalStateException(String.format("读取 EF %04X 头部失败：%s", fid, head.describe()));
        }
        int[] hl = Tlv.header(head.data);
        if (hl == null) throw new IllegalStateException(String.format("EF %04X 头部 TLV 解析失败", fid));
        int total = hl[0] + hl[1];

        byte[] buf = new byte[Math.max(total, 4)];
        int got = Math.min(4, head.data.length);
        System.arraycopy(head.data, 0, buf, 0, got);

        while (got < total) {
            // 224 是 SM 下最划算的块长：加密后 87+99+8E 恰好不超 256，APDU 数量最少
            int want = Math.min(224, total - got);
            Apdu.Resp part = send(0xB0, (got >> 8) & 0x7F, got & 0xFF, null, want);
            if (part.sw == Apdu.SW_EOF || (part.ok() && part.data.length == 0)) break;
            if (!part.ok()) {
                // 有的芯片对"读超过文件尾"返回 6B00/6982，能拿到多少算多少
                if (got > 4) break;
                throw new IllegalStateException(String.format("读取 EF %04X 偏移 %d 失败：%s", fid, got, part.describe()));
            }
            int n = Math.min(part.data.length, total - got);
            System.arraycopy(part.data, 0, buf, got, n);
            got += n;
            if (n == 0) break;
        }
        if (got < total) {
            byte[] shrink = new byte[got];
            System.arraycopy(buf, 0, shrink, 0, got);
            buf = shrink;
        }
        log.add(String.format("读取 EF %04X：%d 字节（%d 条 APDU）", fid, buf.length, apduCount));
        return buf;
    }

}
