package iq.digitalration.faceverify;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 离线二维码编码器（TV-08 的"能扫"的那一半）。
 *
 * <p>为什么自己写：本工程不许联网、也不引第三方库（工程里没有 zxing），
 * 但现场需要"让对方拿手机扫我屏幕上的码"。所以按 ISO/IEC 18004 手写一个最小实现：
 * <ul>
 *   <li>只做 <b>Byte 模式</b>（UTF-8 字节，动态码 payload 是 ASCII）；</li>
 *   <li>只做 <b>纠错等级 M</b>、<b>版本 1—6</b>（容量 14/26/42/62/84/106 字节）——
 *       dynamic code 的 payload 固定 40 字节，落在版本 3；</li>
 *   <li>8 个掩码全试，按标准 4 条罚分选最优；</li>
 *   <li>版本 ≤ 6 不需要版本信息块（18 位 BCH），少一处出错的地方。</li>
 * </ul>
 *
 * <p>正确性不是靠"看着像"：{@code tools/validate/QrTest.java} 用一个**独立写的解码器**
 * 把矩阵读回字符串逐字节比对，并用另一套 GF(256) 实现独立验证 RS 校验子全零。
 */
public final class QrCode {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** 纠错等级 M 的 2 位编码（L=01, M=00, Q=11, H=10）。 */
    private static final int EC_BITS_M = 0b00;

    /**
     * 版本 → 分块表（纠错等级 M）：{version, 组1块数, 组1数据码字, 组2块数, 组2数据码字, 每块纠错码字, 总码字}。
     * 每一行都满足 (组1块数×(组1数据+ec) + 组2块数×(组2数据+ec)) == 总码字，
     * 而总码字等于"数据模块数/8"——这两条在 QrTest 里都会断言。
     */
    public static final int[][] BLOCKS = {
            {1, 1, 16, 0, 0, 10, 26},
            {2, 1, 28, 0, 0, 16, 44},
            {3, 1, 44, 0, 0, 26, 70},
            {4, 2, 32, 0, 0, 18, 100},
            {5, 2, 43, 0, 0, 24, 134},
            {6, 4, 27, 0, 0, 16, 172},
    };

    private QrCode() {}

    // ------------------------------------------------------------ 对外接口

    public static int sizeOf(int version) {
        return 17 + 4 * version;
    }

    public static int dataCodewords(int version) {
        int[] t = BLOCKS[version - 1];
        return t[1] * t[2] + t[3] * t[4];
    }

    /** 该版本能装多少字节（Byte 模式，版本 ≤ 9 用 8 位长度域）。 */
    public static int capacityBytes(int version) {
        return dataCodewords(version) - 2;
    }

    /** 装得下的最小版本；太大返回 -1。 */
    public static int versionFor(int bytes) {
        for (int v = 1; v <= BLOCKS.length; v++) if (capacityBytes(v) >= bytes) return v;
        return -1;
    }

    /** 编码成模块矩阵：true = 深色。超长返回 null。 */
    public static boolean[][] encode(String text) {
        byte[] data = text.getBytes(UTF8);
        int v = versionFor(data.length);
        if (v < 0) return null;
        return encode(data, v);
    }

    public static boolean[][] encode(byte[] data, int version) {
        if (version < 1 || version > BLOCKS.length) return null;
        if (data.length > capacityBytes(version)) return null;

        int size = sizeOf(version);
        byte[] all = makeCodewords(data, version);

        // 先画功能图形，再放数据，最后每个掩码试一遍
        boolean[][] base = new boolean[size][size];
        boolean[][] fn = new boolean[size][size];
        drawFunctionPatterns(base, fn, version);

        boolean[][] best = null;
        int bestPenalty = Integer.MAX_VALUE;
        int bestMask = 0;
        for (int mask = 0; mask < 8; mask++) {
            boolean[][] m = copy(base);
            drawCodewords(m, fn, all);
            applyMask(m, fn, mask);
            drawFormatBits(m, fn, size, mask);
            int p = penalty(m, size);
            if (p < bestPenalty) {
                bestPenalty = p;
                bestMask = mask;
                best = m;
            }
        }
        // 保险：矩阵必须真的被选出来了
        if (best == null) {
            best = copy(base);
            drawCodewords(best, fn, all);
            applyMask(best, fn, bestMask);
            drawFormatBits(best, fn, size, bestMask);
        }
        return best;
    }

    // ------------------------------------------------------------ 码字

    private static byte[] makeCodewords(byte[] data, int version) {
        int[] t = BLOCKS[version - 1];
        int g1 = t[1], g1d = t[2], g2 = t[3], g2d = t[4], ecLen = t[5], total = t[6];
        int dataWords = g1 * g1d + g2 * g2d;

        BitBuf bb = new BitBuf(dataWords);
        bb.append(4, 0b0100);                 // Byte 模式
        bb.append(8, data.length);            // 长度（8 位，版本 ≤ 9）
        for (byte b : data) bb.append(8, b & 0xFF);
        bb.append(Math.min(4, dataWords * 8 - bb.len), 0);            // 结束符
        bb.append((8 - bb.len % 8) % 8, 0);                            // 补齐到字节
        for (int pad = 0; bb.len < dataWords * 8; pad++) {
            bb.append(8, (pad & 1) == 0 ? 0xEC : 0x11);                // 交替填充
        }
        byte[] dcw = bb.bytes();

        byte[][] dblocks = new byte[g1 + g2][];
        int pos = 0;
        for (int i = 0; i < g1; i++) {
            dblocks[i] = Arrays.copyOfRange(dcw, pos, pos + g1d);
            pos += g1d;
        }
        for (int i = 0; i < g2; i++) {
            dblocks[g1 + i] = Arrays.copyOfRange(dcw, pos, pos + g2d);
            pos += g2d;
        }

        byte[] divisor = rsDivisor(ecLen);
        byte[][] ecblocks = new byte[dblocks.length][];
        for (int i = 0; i < dblocks.length; i++) ecblocks[i] = rsRemainder(dblocks[i], divisor);

        byte[] all = new byte[total];
        int k = 0;
        int maxData = Math.max(g1d, g2d);
        for (int i = 0; i < maxData; i++) {
            for (byte[] b : dblocks) if (i < b.length) all[k++] = b[i];
        }
        for (int i = 0; i < ecLen; i++) {
            for (byte[] b : ecblocks) all[k++] = b[i];
        }
        return all;
    }

    /** 生成多项式 Π(x - α^i)，i = 0..degree-1（最高次在前的系数，长度 degree）。 */
    public static byte[] rsDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) gfMul(result[j] & 0xFF, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = gfMul(root, 0x02);
        }
        return result;
    }

    /** 余数（即纠错码字）。 */
    public static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) {
                result[i] ^= (byte) gfMul(divisor[i] & 0xFF, factor);
            }
        }
        return result;
    }

    /** GF(256)，本原多项式 0x11D。 */
    public static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    // ------------------------------------------------------------ 功能图形

    private static void drawFunctionPatterns(boolean[][] m, boolean[][] fn, int version) {
        int size = sizeOf(version);
        // 定时图形
        for (int i = 0; i < size; i++) {
            setFn(m, fn, 6, i, (i & 1) == 0);
            setFn(m, fn, i, 6, (i & 1) == 0);
        }
        // 三个定位图形（含分隔符，dist==4 是浅色）
        drawFinder(m, fn, 3, 3, size);
        drawFinder(m, fn, size - 4, 3, size);
        drawFinder(m, fn, 3, size - 4, size);
        // 校正图形
        int[] centers = alignmentCenters(version);
        int n = centers.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                boolean corner = (i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0);
                if (!corner) drawAlignment(m, fn, centers[i], centers[j]);
            }
        }
        // 先占位格式信息区（真正内容在掩码确定后再写）；必须同时标记为功能模块，
        // 否则数据码字会把格式信息区覆盖掉
        drawFormatBits(m, fn, size, 0);
        // 深色模块
        setFn(m, fn, 8, size - 8, true);
    }

    private static void drawFinder(boolean[][] m, boolean[][] fn, int cx, int cy, int size) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= size || y >= size) continue;
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                setFn(m, fn, x, y, dist != 2 && dist != 4);
            }
        }
    }

    private static void drawAlignment(boolean[][] m, boolean[][] fn, int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                setFn(m, fn, cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
    }

    /** 校正图形中心坐标（版本 1 没有）。 */
    public static int[] alignmentCenters(int version) {
        if (version == 1) return new int[0];
        // 版本 2—6 的两个中心：6 与 size-7
        return new int[]{6, sizeOf(version) - 7};
    }

    private static void setFn(boolean[][] m, boolean[][] fn, int x, int y, boolean dark) {
        m[y][x] = dark;
        fn[y][x] = true;
    }

    // ------------------------------------------------------------ 数据 / 掩码 / 格式

    private static void drawCodewords(boolean[][] m, boolean[][] fn, byte[] data) {
        int size = m.length;
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;                       // 跳过定时列
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!fn[y][x] && i < data.length * 8) {
                        m[y][x] = ((data[i >>> 3] >>> (7 - (i & 7))) & 1) != 0;
                        i++;
                    }
                }
            }
        }
    }

    private static void applyMask(boolean[][] m, boolean[][] fn, int mask) {
        int size = m.length;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (fn[y][x]) continue;
                boolean invert;
                switch (mask) {
                    case 0: invert = (x + y) % 2 == 0; break;
                    case 1: invert = y % 2 == 0; break;
                    case 2: invert = x % 3 == 0; break;
                    case 3: invert = (x + y) % 3 == 0; break;
                    case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                    case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (invert) m[y][x] = !m[y][x];
            }
        }
    }

    private static void drawFormatBits(boolean[][] m, boolean[][] fn, int size, int mask) {
        int data = (EC_BITS_M << 3) | mask;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = ((data << 10) | rem) ^ 0x5412;

        for (int i = 0; i <= 5; i++) setFn(m, fn, 8, i, bit(bits, i));
        setFn(m, fn, 8, 7, bit(bits, 6));
        setFn(m, fn, 8, 8, bit(bits, 7));
        setFn(m, fn, 7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) setFn(m, fn, 14 - i, 8, bit(bits, i));

        for (int i = 0; i < 8; i++) setFn(m, fn, size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) setFn(m, fn, 8, size - 15 + i, bit(bits, i));
    }

    private static boolean bit(int v, int i) {
        return ((v >>> i) & 1) != 0;
    }

    private static boolean[][] copy(boolean[][] src) {
        boolean[][] out = new boolean[src.length][];
        for (int i = 0; i < src.length; i++) out[i] = src[i].clone();
        return out;
    }

    // ------------------------------------------------------------ 罚分（掩码选择）

    private static int penalty(boolean[][] m, int size) {
        int result = 0;

        // 规则 1：同色连续 ≥5
        for (int y = 0; y < size; y++) {
            boolean color = m[y][0];
            int run = 1;
            for (int x = 1; x < size; x++) {
                if (m[y][x] == color) {
                    run++;
                    if (run == 5) result += 3;
                    else if (run > 5) result++;
                } else {
                    color = m[y][x];
                    run = 1;
                }
            }
        }
        for (int x = 0; x < size; x++) {
            boolean color = m[0][x];
            int run = 1;
            for (int y = 1; y < size; y++) {
                if (m[y][x] == color) {
                    run++;
                    if (run == 5) result += 3;
                    else if (run > 5) result++;
                } else {
                    color = m[y][x];
                    run = 1;
                }
            }
        }

        // 规则 2：2×2 同色
        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                boolean c = m[y][x];
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) result += 3;
            }
        }

        // 规则 3：类定位图形 1011101 0000 / 0000 1011101
        int[] p1 = {1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0};
        int[] p2 = {0, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1};
        for (int y = 0; y < size; y++) {
            for (int x = 0; x <= size - 11; x++) {
                if (match(m, y, x, true, p1) || match(m, y, x, true, p2)) result += 40;
            }
        }
        for (int x = 0; x < size; x++) {
            for (int y = 0; y <= size - 11; y++) {
                if (match(m, y, x, false, p1) || match(m, y, x, false, p2)) result += 40;
            }
        }

        // 规则 4：深色比例偏离 50%
        int dark = 0;
        for (boolean[] row : m) for (boolean b : row) if (b) dark++;
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        result += Math.max(0, k) * 10;
        return result;
    }

    private static boolean match(boolean[][] m, int y, int x, boolean horizontal, int[] pat) {
        for (int i = 0; i < pat.length; i++) {
            boolean v = horizontal ? m[y][x + i] : m[y + i][x];
            if ((pat[i] == 1) != v) return false;
        }
        return true;
    }

    // ------------------------------------------------------------ 小工具

    private static final class BitBuf {
        private final byte[] buf;
        int len;

        BitBuf(int bytes) {
            buf = new byte[bytes];
        }

        void append(int count, int value) {
            for (int i = count - 1; i >= 0; i--) {
                if (((value >>> i) & 1) != 0) buf[len >>> 3] |= (byte) (0x80 >>> (len & 7));
                len++;
            }
        }

        byte[] bytes() {
            return buf;
        }
    }

    /** 给界面用：把矩阵转成一行一行的字符画（调试/日志）。 */
    public static List<String> toAscii(boolean[][] m) {
        List<String> out = new ArrayList<>();
        if (m == null) return out;
        for (boolean[] row : m) {
            StringBuilder sb = new StringBuilder(row.length);
            for (boolean b : row) sb.append(b ? '#' : '.');
            out.add(sb.toString());
        }
        return out;
    }
}
