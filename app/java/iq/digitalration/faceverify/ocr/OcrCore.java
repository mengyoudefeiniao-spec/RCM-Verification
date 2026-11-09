package iq.digitalration.faceverify.ocr;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * PP-OCR（DBNet 检测 + CTC 识别）最小实现：纯 Java + onnxruntime，不依赖 Android，
 * 因此可以在 PC 的 JVM 上直接跑（tools/validate/OcrTest.java）验证参数后再装进手机。
 *
 * <p><b>两个识别模型是刻意分开的</b>（同一张卡上两类文字差别太大）：
 * <ul>
 *   <li><b>英文/数字模型</b>（en_PP-OCRv3_rec，436 类，含 {@code <}）：读家庭编号、MRZ、日期；</li>
 *   <li><b>阿拉伯文模型</b>（arabic rec，161 类）：只读「الرقم العائلي / ژمارەی خێزانی」这类标签。</li>
 * </ul>
 *
 * <p>数字行除了「白名单解码」还会做**多尺度集成**：同一行在 1.0× 和 1.35× 宽度、原图与二值化上各跑一次，
 * 逐位按置信度加权投票。单次识别里 {@code M→4}、{@code O→0} 这种错误，不同尺度经常不会同时发生，
 * 集成后能修掉相当一部分；修不掉的部分留在 {@link Line#charAlt} 里，交给上层展示"候选值"。
 *
 * <p>阿拉伯文是 RTL：模型输出的是**视觉顺序**，本类在 {@link Line#rtl} 标记并已反转成逻辑顺序。
 *
 * <p>参数与 tools/ocr-proto/proto.py 一一对应，改一边必须同步另一边。
 */
public final class OcrCore {

    /** 识别到的一行文字。坐标均为传入图像坐标系。 */
    public static final class Line {
        public final String text;
        public final float conf;
        public final float cx, cy, w, h, angle;
        public final int[] box; // 轴对齐外包 x0,y0,x1,y1，供界面画框
        public final boolean rtl;
        /** 逐字符置信度（与 text 等长；可能为 null）。 */
        public final float[] charConf;
        /** 逐字符次优候选（与 text 等长；没有次优时为 ""）。 */
        public final String[] charAlt;

        public Line(String text, float conf, float cx, float cy, float w, float h, float angle,
                    int[] box, boolean rtl, float[] charConf, String[] charAlt) {
            this.text = text;
            this.conf = conf;
            this.cx = cx; this.cy = cy; this.w = w; this.h = h; this.angle = angle;
            this.box = box;
            this.rtl = rtl;
            this.charConf = charConf;
            this.charAlt = charAlt;
        }

        public Line(String text, float conf, float cx, float cy, float w, float h, float angle,
                    int[] box, boolean rtl) {
            this(text, conf, cx, cy, w, h, angle, box, rtl, null, null);
        }

        public Line withText(String t) {
            return new Line(t, conf, cx, cy, w, h, angle, box, rtl, charConf, charAlt);
        }

        @Override public String toString() {
            return String.format(Locale.US, "%-40s %.2f  (%.0f,%.0f %.0fx%.0f %.0f°)%s",
                    text, conf, cx, cy, w, h, angle, rtl ? " [rtl]" : "");
        }
    }

    /** 一次识别（单个变体）的结果，带逐字符置信度与次优候选。 */
    public static final class Word {
        public final String text;
        public final float mean;
        public final float[] conf;
        public final String[] alt;
        public final boolean rtl;

        Word(String text, float mean, float[] conf, String[] alt, boolean rtl) {
            this.text = text;
            this.mean = mean;
            this.conf = conf;
            this.alt = alt;
            this.rtl = rtl;
        }

        static Word empty() {
            return new Word("", 0f, new float[0], new String[0], false);
        }
    }

    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    private static final int MAX_SIDE = 1280;   // 检测最长边
    private static final float DET_THR = 0.30f; // DBNet 概率阈值
    private static final float UNCLIP = 1.80f;  // 按面积/周长外扩，恢复 DBNet 收缩掉的笔画
    private static final int MIN_AREA = 20;
    private static final float MIN_CONF = 0.30f;
    private static final int REC_H = 48;
    private static final float WEAK = 0.72f;    // 低于这个置信度就换预处理/换模型重试
    private static final float WEAK2 = 0.62f;   // 低于这个才值得动阿拉伯文模型

    /** 数字/机读区用的白名单：只有这些类参与 CTC argmax。 */
    private static final String ALNUM = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ<";

    /** 一个识别模型 + 它的字符表。 */
    private static final class Rec {
        final OrtSession s;
        final String in;
        final String[] cs;
        final boolean rtl;
        final boolean[] allowAll;
        final boolean[] allowAlnum;

        Rec(OrtEnvironment env, OrtSession.SessionOptions so, String model, String dict, boolean rtl) throws Exception {
            this.s = env.createSession(model, so);
            this.in = s.getInputNames().iterator().next();
            this.rtl = rtl;
            List<String> c = new ArrayList<>();
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(dict), "UTF-8"));
            try {
                String l;
                while ((l = br.readLine()) != null) {
                    if (l.endsWith("\r")) l = l.substring(0, l.length() - 1);
                    c.add(l);
                }
            } finally {
                br.close();
            }
            cs = new String[c.size() + 2];
            cs[0] = "";                                  // CTC blank
            for (int i = 0; i < c.size(); i++) cs[i + 1] = c.get(i);
            cs[cs.length - 1] = " ";                     // PaddleOCR 末尾的空格类
            allowAll = new boolean[cs.length];
            for (int i = 1; i < cs.length; i++) allowAll[i] = true;
            allowAlnum = new boolean[cs.length];
            for (int i = 1; i < cs.length; i++) allowAlnum[i] = cs[i].length() == 1 && ALNUM.indexOf(cs[i].charAt(0)) >= 0;
        }

        int size() { return cs.length; }
    }

    private final OrtEnvironment env;
    private final OrtSession det;
    private final String detIn;
    private final Rec alnum;
    private final Rec arabic;

    /** 只做英文/数字识别（单模型）。 */
    public OcrCore(String detModel, String recModel, String dictFile, int threads) throws Exception {
        this(detModel, recModel, dictFile, null, null, threads);
    }

    /**
     * @param arabicModel 可为 null（没有阿拉伯文模型时退化为只读数字/英文，标签靠版式与形状推断）
     */
    public OcrCore(String detModel, String alnumModel, String alnumDict,
                   String arabicModel, String arabicDict, int threads) throws Exception {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions so = new OrtSession.SessionOptions();
        so.setIntraOpNumThreads(Math.max(1, threads));
        so.setInterOpNumThreads(1);
        so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        det = env.createSession(detModel, so);
        detIn = det.getInputNames().iterator().next();
        alnum = new Rec(env, so, alnumModel, alnumDict, false);
        arabic = arabicModel == null ? null : new Rec(env, so, arabicModel, arabicDict, true);
    }

    public int charsetSize() { return alnum.size(); }

    public boolean hasArabicModel() { return arabic != null; }

    /** 自动判向后的识别结果：lines 的坐标已映射回**原始**图像，rotation 为实际使用的顺时针角度。 */
    public static final class Scan {
        public final List<Line> lines;
        public final int rotation;

        Scan(List<Line> lines, int rotation) {
            this.lines = lines;
            this.rotation = rotation;
        }
    }

    /**
     * 自动判向：卡背可能是横着/倒着拍的。DB 检测对「侧躺 90°」的文字几乎不出框，
     * 所以先用小图跑 4 个方向，按概率图有效面积选方向，再对整幅图做识别。
     */
    public Scan recognizeAuto(int[] argb, int w, int h) {
        int deg = 0;
        float s = 480f / Math.max(w, h);
        if (s > 1f) s = 1f;
        int sw = Math.max(32, Math.round(w * s)), sh = Math.max(32, Math.round(h * s));
        int[] small = downscale(argb, w, h, sw, sh);

        double mass0 = 0, best = -1;
        for (int d : new int[]{0, 90, 270, 180}) {
            int[] rot = d == 0 ? small : rotate(small, sw, sh, d);
            int rw = (d == 90 || d == 270) ? sh : sw;
            int rh = (d == 90 || d == 270) ? sw : sh;
            float[] p = detProb(rot, rw, rh);
            double mass = 0;
            for (float v : p) if (v > 0.35f) mass += 1;
            if (d == 0) mass0 = mass;
            if (mass > best) { best = mass; deg = d; }
        }
        if (best <= mass0 * 1.25) deg = 0;
        if (Boolean.getBoolean("ocr.debug")) {
            System.out.println("[ocr] 方向候选 mass0=" + Math.round(mass0) + " best=" + Math.round(best)
                    + " -> " + deg + "°");
        }
        if (deg == 0) return new Scan(recognize(argb, w, h), 0);

        int[] rot = rotate(argb, w, h, deg);
        int rw = (deg == 90 || deg == 270) ? h : w;
        int rh = (deg == 90 || deg == 270) ? w : h;
        List<Line> rl = recognize(rot, rw, rh);
        List<Line> mapped = new ArrayList<>(rl.size());
        for (Line l : rl) {
            int[] b = mapBoxBack(l.box, w, h, deg);
            mapped.add(new Line(l.text, l.conf, (b[0] + b[2]) / 2f, (b[1] + b[3]) / 2f,
                    b[2] - b[0], b[3] - b[1], l.angle, b, l.rtl, l.charConf, l.charAlt));
        }
        Collections.sort(mapped, new Comparator<Line>() {
            @Override public int compare(Line a, Line b) {
                int dy = Math.round(a.cy / 12f) - Math.round(b.cy / 12f);
                return dy != 0 ? dy : Float.compare(a.cx, b.cx);
            }
        });
        return new Scan(mapped, deg);
    }

    /** 把旋转后图像里的框映射回原图（旋转均为顺时针）。 */
    static int[] mapBoxBack(int[] box, int w, int h, int deg) {
        int[][] cs = {{box[0], box[1]}, {box[2], box[1]}, {box[0], box[3]}, {box[2], box[3]}};
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for (int[] c : cs) {
            int x, y;
            switch (deg) {
                case 90:  x = c[1]; y = h - 1 - c[0]; break;
                case 180: x = w - 1 - c[0]; y = h - 1 - c[1]; break;
                case 270: x = w - 1 - c[1]; y = c[0]; break;
                default:  x = c[0]; y = c[1]; break;
            }
            x0 = Math.min(x0, x); y0 = Math.min(y0, y);
            x1 = Math.max(x1, x); y1 = Math.max(y1, y);
        }
        return new int[]{x0, y0, x1, y1};
    }

    static int[] downscale(int[] src, int w, int h, int nw, int nh) {
        int[] o = new int[nw * nh];
        for (int y = 0; y < nh; y++) {
            int sy = Math.min(h - 1, y * h / nh);
            for (int x = 0; x < nw; x++) o[y * nw + x] = src[sy * w + Math.min(w - 1, x * w / nw)];
        }
        return o;
    }

    /** 顺时针旋转 90/180/270 度。 */
    static int[] rotate(int[] src, int w, int h, int deg) {
        int[] o;
        switch (deg) {
            case 90:
                o = new int[w * h];
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++) o[x * h + (h - 1 - y)] = src[y * w + x];
                return o;
            case 180:
                o = new int[w * h];
                for (int i = 0; i < w * h; i++) o[w * h - 1 - i] = src[i];
                return o;
            case 270:
                o = new int[w * h];
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++) o[(w - 1 - x) * h + y] = src[y * w + x];
                return o;
            default:
                return src;
        }
    }

    /** 整幅图「检测 → 逐行识别」，返回按阅读顺序排好的行。 */
    public List<Line> recognize(int[] argb, int w, int h) {
        float scale = 1f;
        int m = Math.max(w, h);
        if (m > MAX_SIDE) scale = MAX_SIDE / (float) m;
        int sw = Math.max(32, Math.round(w * scale));
        int sh = Math.max(32, Math.round(h * scale));
        int[] small = argb;
        if (scale < 1f) {
            small = new int[sw * sh];
            for (int y = 0; y < sh; y++) {
                int sy = Math.min(h - 1, Math.round(y / scale));
                int row = sy * w;
                for (int x = 0; x < sw; x++) {
                    small[y * sw + x] = argb[row + Math.min(w - 1, Math.round(x / scale))];
                }
            }
        }

        float[] prob = detProb(small, sw, sh);
        List<int[]> comps = components(prob, sw, sh);
        List<Line> out = new ArrayList<>();
        for (int[] px : comps) {
            Line l = readLine(small, sw, sh, px);
            if (l != null) out.add(l);
        }
        if (Boolean.getBoolean("ocr.debug")) {
            int hit = 0;
            for (float v : prob) if (v > 0.35f) hit++;
            System.out.println("[ocr] 全图 " + sw + "x" + sh + " 概率像素=" + hit
                    + " 连通域=" + comps.size() + " 成行=" + out.size());
        }
        mergeLines(out);
        Collections.sort(out, new Comparator<Line>() {
            @Override public int compare(Line a, Line b) {
                int dy = Math.round(a.cy / 12f) - Math.round(b.cy / 12f);
                return dy != 0 ? dy : Float.compare(a.cx, b.cx);
            }
        });
        if (scale >= 1f) return out;

        List<Line> mapped = new ArrayList<>(out.size());
        for (Line l : out) {
            mapped.add(new Line(l.text, l.conf, l.cx / scale, l.cy / scale, l.w / scale, l.h / scale,
                    l.angle, new int[]{
                    Math.round(l.box[0] / scale), Math.round(l.box[1] / scale),
                    Math.round(l.box[2] / scale), Math.round(l.box[3] / scale)}, l.rtl,
                    l.charConf, l.charAlt));
        }
        return mapped;
    }

    // ---------------------------------------------------------------- 检测

    /** DBNet 前向，返回与原图同尺寸的概率图。 */
    private float[] detProb(int[] argb, int w, int h) {
        int nw = Math.max(32, Math.round(w / 32f) * 32);
        int nh = Math.max(32, Math.round(h / 32f) * 32);
        float[] in = new float[3 * nh * nw];
        int plane = nh * nw;
        for (int y = 0; y < nh; y++) {
            int sy = Math.min(h - 1, y * h / nh);
            for (int x = 0; x < nw; x++) {
                int sx = Math.min(w - 1, x * w / nw);
                int p = argb[sy * w + sx];
                int i = y * nw + x;
                in[i] = (((p >> 16) & 0xFF) / 255f - MEAN[0]) / STD[0];
                in[plane + i] = (((p >> 8) & 0xFF) / 255f - MEAN[1]) / STD[1];
                in[2 * plane + i] = ((p & 0xFF) / 255f - MEAN[2]) / STD[2];
            }
        }
        float[] map;
        int mh, mw;
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(in), new long[]{1, 3, nh, nw});
             OrtSession.Result r = det.run(Collections.singletonMap(detIn, t))) {
            float[][][][] o = (float[][][][]) r.get(0).getValue();
            mh = o[0][0].length;
            mw = o[0][0][0].length;
            map = new float[mh * mw];
            for (int y = 0; y < mh; y++) System.arraycopy(o[0][0][y], 0, map, y * mw, mw);
        } catch (Exception e) {
            throw new RuntimeException("OCR 检测推理失败: " + e.getMessage(), e);
        }
        float[] full = new float[w * h];
        float fx = (mw - 1) / (float) Math.max(1, w - 1);
        float fy = (mh - 1) / (float) Math.max(1, h - 1);
        for (int y = 0; y < h; y++) {
            float sy = y * fy;
            int y0 = (int) sy, y1 = Math.min(mh - 1, y0 + 1);
            float wy = sy - y0;
            for (int x = 0; x < w; x++) {
                float sx = x * fx;
                int x0 = (int) sx, x1 = Math.min(mw - 1, x0 + 1);
                float wx = sx - x0;
                float a = map[y0 * mw + x0], b = map[y0 * mw + x1];
                float c = map[y1 * mw + x0], d = map[y1 * mw + x1];
                full[y * w + x] = (a * (1 - wx) + b * wx) * (1 - wy) + (c * (1 - wx) + d * wx) * wy;
            }
        }
        return full;
    }

    /** 8 连通域，返回每个连通域的像素下标（y*w+x）。 */
    private List<int[]> components(float[] prob, int w, int h) {
        boolean[] seen = new boolean[w * h];
        int[] stack = new int[w * h];
        int[] grow = new int[1024];
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < prob.length; i++) {
            if (seen[i] || prob[i] <= DET_THR) continue;
            int sp = 0, n = 0;
            stack[sp++] = i;
            seen[i] = true;
            while (sp > 0) {
                int p = stack[--sp];
                if (n == grow.length) grow = Arrays.copyOf(grow, grow.length * 2);
                grow[n++] = p;
                int x = p % w, y = p / w;
                int y0 = Math.max(0, y - 1), y1 = Math.min(h - 1, y + 1);
                int x0 = Math.max(0, x - 1), x1 = Math.min(w - 1, x + 1);
                for (int ny = y0; ny <= y1; ny++) {
                    int row = ny * w;
                    for (int nx = x0; nx <= x1; nx++) {
                        int q = row + nx;
                        if (seen[q] || prob[q] <= DET_THR) continue;
                        seen[q] = true;
                        stack[sp++] = q;
                    }
                }
            }
            if (n < MIN_AREA) continue;
            out.add(Arrays.copyOf(grow, n));
        }
        return out;
    }

    /** 一个连通域 → 一行文字：定轴 → 抠图 → CTC；置信度不足返回 null。 */
    private Line readLine(int[] img, int w, int h, int[] px) {
        double sx = 0, sy = 0;
        int n = px.length;
        for (int p : px) { sx += p % w; sy += p / w; }
        double mx = sx / n, my = sy / n;
        double mxx = 0, myy = 0, mxy = 0;
        for (int p : px) {
            double dx = (p % w) - mx, dy = (p / w) - my;
            mxx += dx * dx; myy += dy * dy; mxy += dx * dy;
        }
        mxx /= n; myy /= n; mxy /= n;
        double theta = 0.5 * Math.atan2(2 * mxy, mxx - myy);
        double ex0 = Math.cos(theta), ex1 = Math.sin(theta);
        double ey0 = -ex1, ey1 = ex0;
        double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE;
        double minV = Double.MAX_VALUE, maxV = -Double.MAX_VALUE;
        for (int p : px) {
            double dx = (p % w) - mx, dy = (p / w) - my;
            double u = dx * ex0 + dy * ex1, v = dx * ey0 + dy * ey1;
            if (u < minU) minU = u;
            if (u > maxU) maxU = u;
            if (v < minV) minV = v;
            if (v > maxV) maxV = v;
        }
        double bw = maxU - minU, bh = maxV - minV;
        if (bw < 4 || bh < 2) return null;
        double d = n * UNCLIP / Math.max(1e-3, 2 * (bw + bh));
        double cx = mx + ex0 * (minU + maxU) / 2 + ey0 * (minV + maxV) / 2;
        double cy = my + ex1 * (minU + maxU) / 2 + ey1 * (minV + maxV) / 2;
        int ow = Math.max(8, (int) Math.round(bw + 2 * d));
        int oh = Math.max(8, (int) Math.round(bh + 2 * d));

        int[] crop = new int[ow * oh];
        for (int y = 0; y < oh; y++) {
            double v = y - oh / 2.0 + 0.5;
            for (int x = 0; x < ow; x++) {
                double u = x - ow / 2.0 + 0.5;
                crop[y * ow + x] = bilinear(img, w, h, cx + ex0 * u + ey0 * v, cy + ex1 * u + ey1 * v);
            }
        }
        Word word = recBest(crop, ow, oh);
        float conf = word.mean;
        String text = word.text.trim();
        if (Boolean.getBoolean("ocr.debug")) {
            System.out.println("[ocr] 行候选 " + ow + "x" + oh + " θ=" + Math.round(Math.toDegrees(theta))
                    + " n=" + n + " -> '" + text + "' " + conf + (word.rtl ? " rtl" : ""));
        }
        if (text.isEmpty() || conf < MIN_CONF) return null;

        double hw = (Math.abs(ex0) * ow + Math.abs(ey0) * oh) / 2;
        double hh = (Math.abs(ex1) * ow + Math.abs(ey1) * oh) / 2;
        int[] box = new int[]{
                (int) Math.round(cx - hw), (int) Math.round(cy - hh),
                (int) Math.round(cx + hw), (int) Math.round(cy + hh)};
        return new Line(text, conf, (float) cx, (float) cy, (float) (bw + 2 * d), (float) (bh + 2 * d),
                (float) Math.toDegrees(theta) % 180f, box, word.rtl, word.conf, word.alt);
    }

    private static int bilinear(int[] img, int w, int h, double x, double y) {
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x > w - 1) x = w - 1;
        if (y > h - 1) y = h - 1;
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        int x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1);
        double fx = x - x0, fy = y - y0;
        int p00 = img[y0 * w + x0], p10 = img[y0 * w + x1];
        int p01 = img[y1 * w + x0], p11 = img[y1 * w + x1];
        int r = (int) (((p00 >> 16 & 0xFF) * (1 - fx) + (p10 >> 16 & 0xFF) * fx) * (1 - fy)
                + ((p01 >> 16 & 0xFF) * (1 - fx) + (p11 >> 16 & 0xFF) * fx) * fy);
        int g = (int) (((p00 >> 8 & 0xFF) * (1 - fx) + (p10 >> 8 & 0xFF) * fx) * (1 - fy)
                + ((p01 >> 8 & 0xFF) * (1 - fx) + (p11 >> 8 & 0xFF) * fx) * fy);
        int b = (int) (((p00 & 0xFF) * (1 - fx) + (p10 & 0xFF) * fx) * (1 - fy)
                + ((p01 & 0xFF) * (1 - fx) + (p11 & 0xFF) * fx) * fy);
        return 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    /** 同一行被切成多块时合并；避免家庭编号/证件号被拆开。 */
    private static void mergeLines(List<Line> out) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < out.size() && !changed; i++) {
                for (int j = i + 1; j < out.size(); j++) {
                    Line a = out.get(i), b = out.get(j);
                    if (a.rtl != b.rtl) continue;
                    boolean va = isVertical(a), vb = isVertical(b);
                    if (va != vb) continue;
                    float hmax = Math.max(a.h, b.h), hmin = Math.min(a.h, b.h);
                    if (hmin < 0.45f * hmax) continue;
                    boolean same;
                    if (va) {
                        float dx = Math.abs(a.cx - b.cx);
                        float gap = Math.abs(a.cy - b.cy) - (a.w + b.w) / 2f;
                        same = dx < 0.55f * hmax && gap < 0.9f * hmax;
                    } else {
                        float dy = Math.abs(a.cy - b.cy);
                        float gap = Math.abs(a.cx - b.cx) - (a.w + b.w) / 2f;
                        same = dy < 0.55f * hmax && gap < 0.9f * hmax;
                    }
                    if (same) {
                        out.set(i, combine(a, b, va));
                        out.remove(j);
                        changed = true;
                        break;
                    }
                }
            }
        }
    }

    private static boolean isVertical(Line a) {
        float ang = ((a.angle % 180f) + 180f) % 180f;
        return ang > 60f && ang < 120f;
    }

    private static Line combine(Line a, Line b, boolean vertical) {
        boolean aFirst = vertical ? a.cy <= b.cy : a.cx <= b.cx;
        String t = aFirst ? a.text + b.text : b.text + a.text;
        float x0 = Math.min(a.box[0], b.box[0]), y0 = Math.min(a.box[1], b.box[1]);
        float x1 = Math.max(a.box[2], b.box[2]), y1 = Math.max(a.box[3], b.box[3]);
        float[] cc = null;
        String[] al = null;
        if (a.charConf != null && b.charConf != null) {
            cc = new float[a.charConf.length + b.charConf.length];
            al = new String[cc.length];
            if (aFirst) {
                System.arraycopy(a.charConf, 0, cc, 0, a.charConf.length);
                System.arraycopy(b.charConf, 0, cc, a.charConf.length, b.charConf.length);
            } else {
                System.arraycopy(b.charConf, 0, cc, 0, b.charConf.length);
                System.arraycopy(a.charConf, 0, cc, b.charConf.length, a.charConf.length);
            }
        }
        return new Line(t, Math.min(a.conf, b.conf), (x0 + x1) / 2f, (y0 + y1) / 2f,
                x1 - x0, y1 - y0, a.angle,
                new int[]{Math.round(x0), Math.round(y0), Math.round(x1), Math.round(y1)}, a.rtl, cc, al);
    }

    // ---------------------------------------------------------------- 识别

    /**
     * 一行图的识别：
     * 1) 英文/数字模型（白名单），原图 + 180°；
     * 2) 弱行换二值化再试；
     * 3) 仍弱且像阿拉伯文 → 阿拉伯文模型；
     * 4) 像编号/MRZ 的长 alnum 行 → 1.35× 宽度 + 二值化 1.35× 再各跑一次，逐位加权投票。
     */
    public Word recBest(int[] crop, int cw, int ch) {
        Word best = recPair(crop, cw, ch, alnum, true, false, 1.0f);
        int[] bin = null;
        if (best.mean < WEAK) {
            bin = binarize(crop, cw, ch);
            Word b = recPair(bin, cw, ch, alnum, true, false, 1.0f);
            if (b.mean > best.mean) best = b;
        }
        if (arabic != null && (best.mean < WEAK2 || best.text.trim().length() <= 6)) {
            Word ar = recPair(crop, cw, ch, arabic, false, true, 1.0f);
            if (ar.mean < WEAK) {
                int[] b2 = binarize(crop, cw, ch);
                Word ar2 = recPair(b2, cw, ch, arabic, false, true, 1.0f);
                if (ar2.mean > ar.mean) ar = ar2;
            }
            if (hasArabic(ar.text) && ar.mean > best.mean) best = ar;
        }
        // 长 alnum 行（编号/MRZ/日期）做多尺度集成
        if (isCandidateLike(best.text)) {
            if (bin == null) bin = binarize(crop, cw, ch);
            Word w2 = recPair(crop, cw, ch, alnum, true, false, 1.35f);
            Word w3 = recPair(bin, cw, ch, alnum, true, false, 1.35f);
            best = ensemble(best, w2, w3);
        }
        return best;
    }

    /** 兼容旧签名：只要文本和均值。 */
    public String[] recBestArray(int[] crop, int cw, int ch) {
        Word w = recBest(crop, cw, ch);
        return new String[]{w.text, String.format(Locale.US, "%.4f", w.mean), w.rtl ? "1" : "0"};
    }

    private static boolean isCandidateLike(String t) {
        if (t.length() < 14) return false;
        int alnum = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || c == '<') alnum++;
        }
        return alnum >= t.length() * 0.9;
    }

    /** 同长度逐位加权投票；长度不一致就直接取置信度最高的那个。 */
    static Word ensemble(Word... ws) {
        Word best = ws[0];
        for (Word w : ws) if (w.mean > best.mean) best = w;
        int len = best.text.length();
        for (Word w : ws) if (w.text.length() != len) return best;

        StringBuilder sb = new StringBuilder(len);
        float[] conf = new float[len];
        String[] alt = new String[len];
        for (int i = 0; i < len; i++) {
            // 逐位加权投票：char → 权重（权重用该变体在这一位的真实字符概率）
            char c1 = ' ', c2 = ' ';
            float w1 = 0, w2 = 0;
            for (Word w : ws) {
                char c = w.text.charAt(i);
                float cw = w.conf != null && i < w.conf.length ? Math.max(0.05f, w.conf[i]) : 0.3f;
                if (c == c1) { w1 += cw; continue; }
                if (c == c2) { w2 += cw; continue; }
                if (w1 == 0) { c1 = c; w1 = cw; }
                else if (w2 == 0) { c2 = c; w2 = cw; }
                else if (cw > w1) { c2 = c1; w2 = w1; c1 = c; w1 = cw; }
                else if (cw > w2) { c2 = c; w2 = cw; }
            }
            if (w2 > w1) { char tc = c1; float tw = w1; c1 = c2; w1 = w2; c2 = tc; w2 = tw; }
            sb.append(c1);
            // 逐位置信度取"投给胜者的变体里最高的那个模型概率"，不要用票占比（会把 1.0 当成确定，
            // 上层据此判断"形近字母是否该数字化"时就会误判）
            float bestConf = 0;
            for (Word w : ws) {
                if (w.text.charAt(i) != c1) continue;
                float cw = w.conf != null && i < w.conf.length ? w.conf[i] : 0.3f;
                if (cw > bestConf) bestConf = cw;
            }
            conf[i] = bestConf;
            alt[i] = w2 > 0 ? String.valueOf(c2) : "";
        }
        return new Word(sb.toString(), best.mean, conf, alt, best.rtl);
    }

    /** 原文 + 180° 两向，取置信度高的那个。 */
    private Word recPair(int[] crop, int cw, int ch, Rec r, boolean whitelist, boolean rtlFix, float widthScale) {
        Word a = decode(recProb(r, crop, cw, ch, widthScale), r, whitelist, rtlFix);
        int[] rot = new int[cw * ch];
        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw; x++) rot[(ch - 1 - y) * cw + (cw - 1 - x)] = crop[y * cw + x];
        }
        Word b = decode(recProb(r, rot, cw, ch, widthScale), r, whitelist, rtlFix);
        return a.mean >= b.mean ? a : b;
    }

    private float[] recProb(Rec r, int[] argb, int w, int h, float widthScale) {
        if (w < 4 || h < 4) return new float[0];
        int nw = Math.max(16, Math.min(1024, Math.round(REC_H * w / (float) h * widthScale)));
        int nh = REC_H;
        int[] src = resize(argb, w, h, nw, nh);
        float[] in = new float[3 * nh * nw];
        int plane = nh * nw;
        for (int i = 0; i < nh * nw; i++) {
            int p = src[i];
            in[i] = (((p >> 16) & 0xFF) / 255f - 0.5f) / 0.5f;
            in[plane + i] = (((p >> 8) & 0xFF) / 255f - 0.5f) / 0.5f;
            in[2 * plane + i] = ((p & 0xFF) / 255f - 0.5f) / 0.5f;
        }
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(in), new long[]{1, 3, nh, nw});
             OrtSession.Result res = r.s.run(Collections.singletonMap(r.in, t))) {
            float[][][] o = (float[][][]) res.get(0).getValue();
            int steps = o[0].length, cls = o[0][0].length;
            float[] flat = new float[steps * cls];
            for (int i = 0; i < steps; i++) System.arraycopy(o[0][i], 0, flat, i * cls, cls);
            return flat;
        } catch (Exception e) {
            throw new RuntimeException("OCR 识别推理失败: " + e.getMessage(), e);
        }
    }

    /** 缩放到 (nw,nh)：缩小用面积平均，放大用双线性。 */
    static int[] resize(int[] src, int w, int h, int nw, int nh) {
        int[] o = new int[nw * nh];
        boolean shrink = nw < w || nh < h;
        if (shrink) {
            double sx = w / (double) nw, sy = h / (double) nh;
            for (int y = 0; y < nh; y++) {
                int y0 = (int) (y * sy), y1 = Math.max(y0 + 1, Math.min(h, (int) ((y + 1) * sy)));
                for (int x = 0; x < nw; x++) {
                    int x0 = (int) (x * sx), x1 = Math.max(x0 + 1, Math.min(w, (int) ((x + 1) * sx)));
                    long sr = 0, sg = 0, sb = 0;
                    int cnt = 0;
                    for (int yy = y0; yy < y1; yy++) {
                        int row = yy * w;
                        for (int xx = x0; xx < x1; xx++) {
                            int p = src[row + xx];
                            sr += (p >> 16) & 0xFF;
                            sg += (p >> 8) & 0xFF;
                            sb += p & 0xFF;
                            cnt++;
                        }
                    }
                    if (cnt == 0) { o[y * nw + x] = src[y0 * w + x0]; continue; }
                    o[y * nw + x] = 0xFF000000 | ((int) (sr / cnt) << 16) | ((int) (sg / cnt) << 8) | (int) (sb / cnt);
                }
            }
            return o;
        }
        for (int y = 0; y < nh; y++) {
            double fy = (nh == 1) ? 0 : y * (h - 1.0) / (nh - 1);
            int y0 = (int) fy, y1 = Math.min(h - 1, y0 + 1);
            double wy = fy - y0;
            for (int x = 0; x < nw; x++) {
                double fx = (nw == 1) ? 0 : x * (w - 1.0) / (nw - 1);
                int x0 = (int) fx, x1 = Math.min(w - 1, x0 + 1);
                double wx = fx - x0;
                int p00 = src[y0 * w + x0], p10 = src[y0 * w + x1];
                int p01 = src[y1 * w + x0], p11 = src[y1 * w + x1];
                int r = (int) (((p00 >> 16 & 0xFF) * (1 - wx) + (p10 >> 16 & 0xFF) * wx) * (1 - wy)
                        + ((p01 >> 16 & 0xFF) * (1 - wx) + (p11 >> 16 & 0xFF) * wx) * wy);
                int g = (int) (((p00 >> 8 & 0xFF) * (1 - wx) + (p10 >> 8 & 0xFF) * wx) * (1 - wy)
                        + ((p01 >> 8 & 0xFF) * (1 - wx) + (p11 >> 8 & 0xFF) * wx) * wy);
                int b = (int) (((p00 & 0xFF) * (1 - wx) + (p10 & 0xFF) * wx) * (1 - wy)
                        + ((p01 & 0xFF) * (1 - wx) + (p11 & 0xFF) * wx) * wy);
                o[y * nw + x] = 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
            }
        }
        return o;
    }

    /** Otsu 二值化：卡面底纹/水印会把笔画和背景拉近，二值化后识别率通常更高。 */
    static int[] binarize(int[] src, int w, int h) {
        int[] gray = new int[w * h];
        long sum = 0;
        for (int i = 0; i < gray.length; i++) {
            int p = src[i];
            int g = (((p >> 16) & 0xFF) * 299 + ((p >> 8) & 0xFF) * 587 + (p & 0xFF) * 114) / 1000;
            gray[i] = g;
            sum += g;
        }
        int mean = (int) (sum / gray.length);
        int[] hist = new int[256];
        for (int g : gray) hist[g]++;
        double total = gray.length, sumAll = 0;
        for (int i = 0; i < 256; i++) sumAll += (double) i * hist[i];
        double sumB = 0, wB = 0, best = -1;
        int thr = mean;
        for (int t = 0; t < 256; t++) {
            wB += hist[t];
            if (wB == 0) continue;
            double wF = total - wB;
            if (wF == 0) break;
            sumB += (double) t * hist[t];
            double mB = sumB / wB, mF = (sumAll - sumB) / wF;
            double between = wB * wF * (mB - mF) * (mB - mF);
            if (between > best) { best = between; thr = t; }
        }
        boolean invert = mean < 128;      // 深底浅字
        int[] out = new int[w * h];
        for (int i = 0; i < gray.length; i++) {
            boolean dark = gray[i] <= thr;
            int v = (dark ^ invert) ? 0 : 255;
            out[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        return out;
    }

    private Word decode(float[] flat, Rec r, boolean whitelist, boolean rtlFix) {
        if (flat.length == 0) return Word.empty();
        int cls = r.size();
        int steps = flat.length / cls;
        boolean[] allow = whitelist ? r.allowAlnum : r.allowAll;
        StringBuilder sb = new StringBuilder();
        List<Float> confList = new ArrayList<>();
        List<String> altList = new ArrayList<>();
        double sum = 0;
        int cnt = 0, prev = -1;
        for (int t = 0; t < steps; t++) {
            int best = 0;
            float bv = flat[t * cls];              // blank
            for (int c = 1; c < cls; c++) {
                if (!allow[c]) continue;
                float v = flat[t * cls + c];
                if (v > bv) { bv = v; best = c; }
            }
            if (best != 0 && bv < 0.15f) best = 0; // 白名单里也没有像样的类 → 空白
            // 次优（同白名单内），用于上层给"候选值"
            int second = 0;
            float sv = -1;
            for (int c = 1; c < cls; c++) {
                if (!allow[c] || c == best) continue;
                float v = flat[t * cls + c];
                if (v > sv) { sv = v; second = c; }
            }
            if (best != 0 && best != prev) {
                sb.append(r.cs[best]);
                confList.add(bv);
                altList.add(second != 0 && sv > 0.10f ? r.cs[second] : "");
                sum += bv;
                cnt++;
            }
            prev = best;
        }
        String text = sb.toString();
        float[] conf = new float[confList.size()];
        String[] alt = new String[confList.size()];
        for (int i = 0; i < conf.length; i++) {
            conf[i] = confList.get(i);
            alt[i] = altList.get(i);
        }
        boolean rtl = false;
        if (rtlFix && hasArabic(text)) {
            text = reverse(text);
            reverseInPlace(conf);
            reverseInPlace(alt);
            rtl = true;
        }
        return new Word(text, cnt == 0 ? 0f : (float) (sum / cnt), conf, alt, rtl);
    }

    private static void reverseInPlace(float[] a) {
        for (int i = 0, j = a.length - 1; i < j; i++, j--) {
            float t = a[i]; a[i] = a[j]; a[j] = t;
        }
    }

    private static void reverseInPlace(String[] a) {
        for (int i = 0, j = a.length - 1; i < j; i++, j--) {
            String t = a[i]; a[i] = a[j]; a[j] = t;
        }
    }

    static String reverse(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = s.length() - 1; i >= 0; i--) sb.append(s.charAt(i));
        return sb.toString();
    }

    /** 含阿拉伯字母（含库尔德语的 ڕ ڵ ێ ە ۆ 等）。 */
    public static boolean hasArabic(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0600 && c <= 0x06FF) return true;
            if (c >= 0x0750 && c <= 0x077F) return true;
            if (c >= 0xFB50 && c <= 0xFDFF) return true;
            if (c >= 0xFE70 && c <= 0xFEFF) return true;
        }
        return false;
    }

    public void close() {
        try { det.close(); } catch (Throwable ignored) {}
        try { alnum.s.close(); } catch (Throwable ignored) {}
        if (arabic != null) try { arabic.s.close(); } catch (Throwable ignored) {}
    }
}
