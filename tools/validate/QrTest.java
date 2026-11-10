import iq.digitalration.faceverify.QrCode;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 二维码编码器的独立校验（纯 JVM，不依赖 Android）。
 *
 * <p>不"看着像"就算过：这里另写一个**解码器**，从矩阵把字符串读回来逐字节比对；
 * 再用另一套 GF(256) 实现独立算 RS 校验子（必须全零）；最后核对分块表与"数据模块数/8"。
 */
public class QrTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        // 1) 分块表自洽：块数据+纠错 == 总码字，总码字 == 数据模块数/8
        for (int v = 1; v <= QrCode.BLOCKS.length; v++) {
            int[] t = QrCode.BLOCKS[v - 1];
            int sum = t[1] * (t[2] + t[5]) + t[3] * (t[4] + t[5]);
            check("v" + v + " 分块求和 " + sum + " == 总码字 " + t[6], sum == t[6]);
            // 版本 2—6 的剩余位（remainder bits）是 7：数据区模块数 = 总码字*8 + 剩余位
            int remainder = (v == 1) ? 0 : 7;
            int modules = dataModules(v);
            check("v" + v + " 数据模块 " + modules + " == 总码字*8(" + (t[6] * 8) + ") + 剩余位 " + remainder,
                    modules == t[6] * 8 + remainder);
        }

        // 2) 容量：与规格一致（版本 1—6，M 级）
        int[] cap = {14, 26, 42, 62, 84, 106};
        for (int i = 0; i < cap.length; i++) {
            check("v" + (i + 1) + " 容量 " + QrCode.capacityBytes(i + 1) + " == " + cap[i],
                    QrCode.capacityBytes(i + 1) == cap[i]);
            check("v" + (i + 1) + " 装 " + cap[i] + " 字节选 v" + (i + 1),
                    QrCode.versionFor(cap[i]) == i + 1);
        }

        // 3) 真实 payload 往返 + RS 校验子
        List<String> payloads = new ArrayList<>();
        payloads.add("RCM1|a1b2c3d4e5f60718|1794278037|12345678");     // 40 字节，真实动态码格式
        payloads.add("A");
        payloads.add("HELLO WORLD");
        payloads.add(repeat('x', 14));
        payloads.add(repeat('y', 26));
        payloads.add(repeat('z', 42));
        payloads.add(repeat('k', 62));
        payloads.add(repeat('m', 84));
        payloads.add(repeat('n', 106));
        payloads.add("RCM1|0123456789abcdef|1794278037|00000000");
        for (String s : payloads) {
            boolean[][] m = QrCode.encode(s);
            if (m == null) {
                check("编码 \"" + brief(s) + "\"", false);
                continue;
            }
            String back = decode(m);
            check("往返 \"" + brief(s) + "\" → v" + ((m.length - 17) / 4)
                    + " " + m.length + "x" + m.length, s.equals(back));
            if (!s.equals(back)) System.out.println("     期望: " + s + "\n     实得: " + back);
            check("RS 校验子全零 \"" + brief(s) + "\"", syndromesZero(m));
        }

        // 4) 超容量必须返回 null（不能悄悄截断）
        check("107 字节超容量返回 null", QrCode.encode(repeat('q', 107)) == null);

        // 5) 定位图形必须画对（三个角各一个 7×7 环 + 中心 3×3）
        boolean[][] m = QrCode.encode("HELLO WORLD");
        if (m != null) check("定位图形位置正确", findersOk(m));

        System.out.println();
        if (failed == 0) {
            System.out.println("QR TESTS PASSED (" + passed + ")");
        } else {
            System.out.println("QR TESTS FAILED (" + failed + " / " + (passed + failed) + ")");
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ 独立解码器

    private static String decode(boolean[][] m) {
        int size = m.length;
        int version = (size - 17) / 4;
        if (version < 1 || version > 6) return "<版本不支持>";

        boolean[][] fn = functionMap(version);

        int f1 = readFormat(m, size, 0);
        int f2 = readFormat(m, size, 1);
        if (f1 < 0 || f1 != f2) return "<格式信息不一致 f1=" + f1 + " f2=" + f2 + ">";
        int data = f1 >>> 10;
        int ec = data >>> 3;
        int mask = data & 7;
        if (ec != 0) return "<纠错等级不是 M: " + ec + ">";

        byte[] all = readCodewords(m, fn, mask);

        int[] t = QrCode.BLOCKS[version - 1];
        int g1 = t[1], g1d = t[2], g2 = t[3], g2d = t[4];
        int blocks = g1 + g2;
        int[] lens = new int[blocks];
        for (int i = 0; i < g1; i++) lens[i] = g1d;
        for (int i = 0; i < g2; i++) lens[g1 + i] = g2d;
        byte[][] dblocks = new byte[blocks][];
        for (int i = 0; i < blocks; i++) dblocks[i] = new byte[lens[i]];
        int p = 0;
        int maxData = Math.max(g1d, g2d);
        for (int i = 0; i < maxData; i++) {
            for (int b = 0; b < blocks; b++) if (i < lens[b]) dblocks[b][i] = all[p++];
        }
        ByteArrayOutputStream dcw = new ByteArrayOutputStream();
        for (byte[] b : dblocks) dcw.write(b, 0, b.length);

        return parseByteMode(dcw.toByteArray());
    }

    private static String parseByteMode(byte[] dcw) {
        int bitPos = 0;
        int mode = readBits(dcw, bitPos, 4);
        bitPos += 4;
        if (mode != 0b0100) return "<模式不是 Byte: " + mode + ">";
        int len = readBits(dcw, bitPos, 8);
        bitPos += 8;
        if (len < 0 || bitPos + len * 8 > dcw.length * 8) return "<长度越界: " + len + ">";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append((char) readBits(dcw, bitPos, 8));
            bitPos += 8;
        }
        return sb.toString();
    }

    private static int readBits(byte[] b, int pos, int count) {
        int v = 0;
        for (int i = 0; i < count; i++) {
            int bit = (b[(pos + i) >>> 3] >>> (7 - ((pos + i) & 7))) & 1;
            v = (v << 1) | bit;
        }
        return v;
    }

    /** 左上一份格式信息的 15 个位置（行, 列），下标 = 位序号。 */
    private static final int[] FMT_ROW = {0, 1, 2, 3, 4, 5, 7, 8, 8, 8, 8, 8, 8, 8, 8};
    private static final int[] FMT_COL = {8, 8, 8, 8, 8, 8, 8, 8, 7, 5, 4, 3, 2, 1, 0};

    private static int readFormat(boolean[][] m, int size, int copy) {
        int bits = 0;
        for (int i = 14; i >= 0; i--) {
            boolean v;
            if (copy == 0) {
                v = m[FMT_ROW[i]][FMT_COL[i]];
            } else {
                if (i < 8) v = m[8][size - 1 - i];
                else v = m[size - 15 + i][8];
            }
            bits = (bits << 1) | (v ? 1 : 0);
        }
        int raw = bits ^ 0x5412;
        int data = raw >>> 10;
        int rem = raw & 0x3FF;
        int calc = data;
        for (int i = 0; i < 10; i++) calc = (calc << 1) ^ ((calc >>> 9) * 0x537);
        if ((calc & 0x3FF) != rem) return -1;
        return raw;
    }

    /** 独立构造功能模块图（与编码器分开写），用于判断哪些格子放数据。 */
    private static boolean[][] functionMap(int version) {
        int size = QrCode.sizeOf(version);
        boolean[][] fn = new boolean[size][size];
        int[][] centers = {{3, 3}, {size - 4, 3}, {3, size - 4}};
        for (int[] c : centers) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dx = -4; dx <= 4; dx++) {
                    int x = c[0] + dx, y = c[1] + dy;
                    if (x >= 0 && y >= 0 && x < size && y < size) fn[y][x] = true;
                }
            }
        }
        for (int i = 0; i < size; i++) {
            fn[6][i] = true;
            fn[i][6] = true;
        }
        if (version > 1) {
            int a = 6, b = size - 7;
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) fn[b + dy][b + dx] = true;   // 只有右下角一个不撞定位图形
            }
        }
        for (int i = 0; i < 9; i++) {
            fn[8][i] = true;
            fn[i][8] = true;
        }
        for (int i = 0; i < 8; i++) fn[8][size - 1 - i] = true;
        for (int i = 0; i < 7; i++) fn[size - 1 - i][8] = true;
        fn[size - 8][8] = true;
        return fn;
    }

    private static byte[] readCodewords(boolean[][] m, boolean[][] fn, int mask) {
        int size = m.length;
        int total = QrCode.BLOCKS[(size - 17) / 4 - 1][6];
        boolean[] bits = new boolean[total * 8];
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            boolean upward = ((right + 1) & 2) == 0;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    int y = upward ? size - 1 - vert : vert;
                    if (fn[y][x]) continue;
                    if (i >= bits.length) continue;
                    bits[i++] = m[y][x] ^ maskBit(mask, x, y);
                }
            }
        }
        byte[] out = new byte[total];
        for (int k = 0; k < total * 8; k++) {
            if (bits[k]) out[k >>> 3] |= (byte) (0x80 >>> (k & 7));
        }
        return out;
    }

    private static boolean maskBit(int mask, int x, int y) {
        switch (mask) {
            case 0: return (x + y) % 2 == 0;
            case 1: return y % 2 == 0;
            case 2: return x % 3 == 0;
            case 3: return (x + y) % 3 == 0;
            case 4: return (x / 3 + y / 2) % 2 == 0;
            case 5: return x * y % 2 + x * y % 3 == 0;
            case 6: return (x * y % 2 + x * y % 3) % 2 == 0;
            default: return ((x + y) % 2 + x * y % 3) % 2 == 0;
        }
    }

    // ------------------------------------------------------------ RS 校验子（另一套 GF 实现）

    private static final int[] EXP = new int[512];
    private static final int[] LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11D;
        }
        for (int i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
    }

    private static int mul(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return EXP[LOG[a] + LOG[b]];
    }

    private static boolean syndromesZero(boolean[][] m) {
        int size = m.length;
        int version = (size - 17) / 4;
        boolean[][] fn = functionMap(version);
        int f = readFormat(m, size, 0);
        if (f < 0) return false;
        int mask = (f >>> 10) & 7;
        byte[] all = readCodewords(m, fn, mask);

        int[] t = QrCode.BLOCKS[version - 1];
        int g1 = t[1], g1d = t[2], g2 = t[3], g2d = t[4], ecLen = t[5];
        int blocks = g1 + g2;
        int[] lens = new int[blocks];
        for (int i = 0; i < g1; i++) lens[i] = g1d;
        for (int i = 0; i < g2; i++) lens[g1 + i] = g2d;

        byte[][] data = new byte[blocks][];
        for (int i = 0; i < blocks; i++) data[i] = new byte[lens[i]];
        int p = 0;
        int maxData = Math.max(g1d, g2d);
        for (int i = 0; i < maxData; i++) {
            for (int b = 0; b < blocks; b++) if (i < lens[b]) data[b][i] = all[p++];
        }
        byte[][] ecc = new byte[blocks][ecLen];
        for (int i = 0; i < ecLen; i++) {
            for (int b = 0; b < blocks; b++) ecc[b][i] = all[p++];
        }

        for (int b = 0; b < blocks; b++) {
            int[] cw = new int[lens[b] + ecLen];
            int n = 0;
            for (byte v : data[b]) cw[n++] = v & 0xFF;
            for (byte v : ecc[b]) cw[n++] = v & 0xFF;
            // QR 的 RS 生成多项式根是 α^0 … α^(ecLen-1)（不是从 α^1 开始）
            for (int j = 0; j < ecLen; j++) {
                int s = 0;
                for (int i = 0; i < cw.length; i++) {
                    s ^= mul(cw[i], EXP[(j * (cw.length - 1 - i)) % 255]);
                }
                if (s != 0) {
                    System.out.println("     块 " + b + " 的 S" + j + " = " + s + "（应为 0）");
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------ 工具

    private static int dataModules(int version) {
        boolean[][] fn = functionMap(version);
        int size = fn.length;
        int free = 0;
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) if (!fn[y][x]) free++;
        return free;
    }

    private static boolean findersOk(boolean[][] m) {
        int size = m.length;
        int[][] c = {{3, 3}, {size - 4, 3}, {3, size - 4}};
        for (int[] p : c) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dx = -3; dx <= 3; dx++) {
                    int dist = Math.max(Math.abs(dx), Math.abs(dy));
                    boolean expect = dist != 2;
                    if (m[p[1] + dy][p[0] + dx] != expect) return false;
                }
            }
            // 分隔符必须是浅色
            if (p[0] == 3 && p[1] == 3) {
                for (int i = 0; i < 8; i++) if (m[7][i] || m[i][7]) return false;
            }
        }
        return true;
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    private static String brief(String s) {
        if (s.length() <= 24) return s;
        return s.substring(0, 21) + "...(" + s.length() + ")";
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("OK   " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }
}
