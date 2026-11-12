package iq.digitalration.faceverify.card;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    /** 日志里最多打印前 N 条 APDU 的原始字节：够看清 PACE 的 MSE:Set AT / 各条 GA。 */
    private static final int RAW_APDU_LIMIT = 30;
    /** 单条 APDU 打印上限：DG2 的读块响应可能几百字节，全打会把日志刷爆。 */
    private static final int RAW_APDU_BYTES = 64;

    /** 发一条 APDU（有 SM 时自动加/解保护）。data 为明文，le 为期望长度（null = 无 Le）。 */
    public Apdu.Resp send(int ins, int p1, int p2, byte[] data, Integer le) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            byte[] cmd = sm == null ? Apdu.cmd(0x00, ins, p1, p2, data, le)
                    : sm.wrap(ins, p1, p2, data, le);
            // 先计数：超时/断开时这条其实已经发出去了，日志里"1 条 APDU"会误导成"没发命令"
            apduCount++;
            // 前几条把**原始字节**记下来：现场读到一半断开时，只能靠它判断
            //   #2 是不是 00 84 00 00 08（GET CHALLENGE）
            //   #3 是不是 00 82 00 00 28 <40 字节> 28（EXTERNAL AUTHENTICATE，Lc=Le=0x28）
            boolean raw = apduCount <= RAW_APDU_LIMIT;
            if (raw) log.add("APDU #" + apduCount + " → " + hex(cmd));
            byte[] rawResp;
            try {
                rawResp = transport.transceive(cmd);
            } catch (Exception e) {
                if (raw) log.add("APDU #" + apduCount + " ← 无响应：" + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : " " + e.getMessage()));
                throw e;
            }
            if (raw) log.add("APDU #" + apduCount + " ← " + hex(rawResp));
            Apdu.Resp r = sm == null ? Apdu.parse(rawResp) : sm.unwrap(rawResp);
            if (sm != null && sm.terminated()) {
                // §9.8.5：芯片在识别到安全通讯错误时以明文状态字回包并终止会话，
                // 这里必须把 SM 摘掉，否则后续命令还会按旧会话密钥加密，得到的全是 6982。
                log.add("安全通讯已终止（芯片明文返回 " + r.swHex() + "）");
                sm = null;
            }
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

    /**
     * 指定 CLA 的明文命令（只用于 SM 建立之前）。
     *
     * <p>PACE 的 GENERAL AUTHENTICATE 在个别芯片上要求命令链（CLA=0x10），
     * 而 {@link #send(int, int, int, byte[], Integer)} 固定用 0x00，所以单开一个入口做兼容重试。
     */
    public Apdu.Resp sendPlain(int cla, int ins, int p1, int p2, byte[] data, Integer le) throws Exception {
        if (sm != null) throw new IllegalStateException("SM 已建立，不能用明文 CLA 覆盖");
        apduCount++;
        byte[] cmd = Apdu.cmd(cla, ins, p1, p2, data, le);
        boolean raw = apduCount <= RAW_APDU_LIMIT;
        if (raw) log.add("APDU #" + apduCount + " → " + hex(cmd));
        byte[] rawResp;
        try {
            rawResp = transport.transceive(cmd);
        } catch (Exception e) {
            if (raw) log.add("APDU #" + apduCount + " ← 无响应：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage()));
            throw e;
        }
        if (raw) log.add("APDU #" + apduCount + " ← " + hex(rawResp));
        return Apdu.parse(rawResp);
    }

    /** 十六进制（超长截断并标注总长），日志用；纯 Java，JVM 单测也能跑。 */
    private static String hex(byte[] b) {
        if (b == null) return "(null)";
        int n = Math.min(b.length, RAW_APDU_BYTES);
        StringBuilder sb = new StringBuilder(n * 2 + 8);
        for (int i = 0; i < n; i++) sb.append(String.format(Locale.US, "%02X", b[i] & 0xFF));
        if (b.length > n) sb.append("…(").append(b.length).append("B)");
        return sb.toString();
    }

    /** 选 EF 并读完整个文件（按 TLV 头里的长度分块）。文件不存在返回 null。 */
    public byte[] readFile(int fid) throws Exception {
        return readFile(fid, false);
    }

    /**
     * @param optional true 时把"选择文件被拒"也当"文件不可用"返回 null（6A82/6A88/6D00/6982）。
     *                 只用于探测 EF.CardAccess：不少不支持 PACE 的芯片用 **6982（安全状态不满足）**
     *                 而不是 6A82 回绝这个文件，真实现场就是这么回的。
     */
    public byte[] readFile(int fid, boolean optional) throws Exception {
        Apdu.Resp r = send(0xA4, 0x02, 0x0C, new byte[]{(byte) (fid >> 8), (byte) fid}, null);
        if (!r.ok()) {
            boolean unavailable = r.sw == 0x6A82 || r.sw == 0x6A88
                    || (optional && (r.sw == 0x6D00 || r.sw == 0x6982));
            if (unavailable) return null;
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
