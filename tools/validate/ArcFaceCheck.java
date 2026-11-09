// 桌面端 ArcFace 管线校验：用 onnxruntime 桌面版跑 w600k_r50.onnx，
// 验证预处理（RGB / [-1,1] / NCHW）与人脸对齐是否得到合理相似度分布。
// // 用法: java -jar ... （通过 tools/validate/ 下的脚本编译运行）

import ai.onnxruntime.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.List;

public class ArcFaceCheck {

    static final float[][] DST = {
            {38.2946f, 51.6963f}, {73.5318f, 51.5014f}, {56.0252f, 71.7366f},
            {41.5493f, 92.3655f}, {70.7299f, 92.2041f}
    };

    static OrtEnvironment env;
    static OrtSession session;
    static String inputName;
    static int dim = 512;
    static final boolean SWAP = Boolean.getBoolean("swap");

    /** 只加载模型，供其它测试类复用。 */
    static void init(String modelPath) throws Exception {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions so = new OrtSession.SessionOptions();
        so.setIntraOpNumThreads(4);
        session = env.createSession(new File(modelPath).getAbsolutePath(), so);
        inputName = session.getInputInfo().keySet().iterator().next();
        long[] shape = ((TensorInfo) session.getInputInfo().get(inputName).getInfo()).getShape();
        System.out.println("model input=" + inputName + " shape=" + Arrays.toString(shape));
    }

    public static void main(String[] args) throws Exception {
        init(args[0]);

        // 输入：每一行 "路径" 或 "路径|landmarks(10个数,空格分隔)" 或 "路径|center"
        List<String> specs = new ArrayList<>();
        for (int i = 1; i < args.length; i++) specs.add(args[i]);

        Map<String, float[]> embs = new LinkedHashMap<>();
        for (String spec : specs) {
            String[] parts = spec.split("\\|");
            String path = parts[0];
            BufferedImage img = ImageIO.read(new File(path));
            if (img == null) { System.out.println("skip " + path); continue; }
            BufferedImage crop;
            if (parts.length > 1 && parts[1].trim().equals("center")) {
                crop = centerCrop112(img);
            } else if (parts.length > 1) {
                String[] n = parts[1].trim().split("\\s+");
                float[] l5 = new float[10];
                for (int i = 0; i < 10; i++) l5[i] = Float.parseFloat(n[i]);
                crop = warp(img, l5);
            } else {
                crop = centerCrop112(img);
            }
            float[] e = embed(crop, true);
            embs.put(path, e);
            System.out.printf("embedded %-40s %s%n", new File(path).getName(), path);
        }

        String[] keys = embs.keySet().toArray(new String[0]);
        double sum = 0; int n = 0; float mx = -1; String mxPair = "";
        for (int i = 0; i < keys.length; i++) {
            for (int j = i + 1; j < keys.length; j++) {
                float s = cos(embs.get(keys[i]), embs.get(keys[j]));
                sum += s; n++;
                if (s > mx) { mx = s; mxPair = new File(keys[i]).getName() + " / " + new File(keys[j]).getName(); }
            }
        }
        System.out.printf("%n=== cross-identity (different people) n=%d ===%n", n);
        System.out.printf("mean=%.3f   max=%.3f (%s)%n", sum / Math.max(1, n), mx, mxPair);

        System.out.println("\n=== self-consistency (same image vs horizontally flipped) ===");
        double ss = 0; int sn = 0;
        for (String k : keys) {
            BufferedImage im = ImageIO.read(new File(k));
            BufferedImage crop = centerCrop112(im);
            BufferedImage fl = flip(crop);
            float s = cos(embs.get(k), embed(fl, true));
            ss += s; sn++;
            System.out.printf("%6.3f  %s%n", s, new File(k).getName());
        }
        System.out.printf("mean self-flip similarity = %.3f%n", ss / Math.max(1, sn));
        System.out.println("swapRB=" + SWAP);
    }

    static BufferedImage flip(BufferedImage img) {
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(img, img.getWidth(), 0, -img.getWidth(), img.getHeight(), null);
        g.dispose();
        return out;
    }

    static BufferedImage centerCrop112(BufferedImage img) {
        int s = Math.min(img.getWidth(), img.getHeight());
        int x = (img.getWidth() - s) / 2, y = (int) ((img.getHeight() - s) * 0.36);
        y = Math.max(0, Math.min(img.getHeight() - s, y));
        BufferedImage c = img.getSubimage(x, y, s, s);
        BufferedImage out = new BufferedImage(112, 112, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(c, 0, 0, 112, 112, null);
        g.dispose();
        return out;
    }

    static BufferedImage warp(BufferedImage img, float[] src5) {
        float[] dst5 = new float[10];
        for (int i = 0; i < 5; i++) { dst5[i * 2] = DST[i][0]; dst5[i * 2 + 1] = DST[i][1]; }
        float[] m = similarity(src5, dst5);
        double a = m[0], b = m[1], tx = m[2], c = m[3], d = m[4], ty = m[5];
        double det = a * d - b * c;
        double ia = d / det, ib = -b / det, ic = -c / det, id = a / det;
        double itx = -(ia * tx + ib * ty), ity = -(ic * tx + id * ty);
        BufferedImage out = new BufferedImage(112, 112, BufferedImage.TYPE_INT_RGB);
        int w = img.getWidth(), h = img.getHeight();
        for (int y = 0; y < 112; y++) {
            for (int x = 0; x < 112; x++) {
                double sx = ia * x + ib * y + itx;
                double sy = ic * x + id * y + ity;
                int sx0 = (int) Math.floor(sx), sy0 = (int) Math.floor(sy);
                if (sx0 < 0 || sy0 < 0 || sx0 >= w - 1 || sy0 >= h - 1) continue;
                double fx = sx - sx0, fy = sy - sy0;
                int p00 = img.getRGB(sx0, sy0), p10 = img.getRGB(sx0 + 1, sy0);
                int p01 = img.getRGB(sx0, sy0 + 1), p11 = img.getRGB(sx0 + 1, sy0 + 1);
                int r = (int) (bl(p00, 16, fx, fy, p10, p01, p11));
                int g = (int) (bl(p00, 8, fx, fy, p10, p01, p11));
                int b2 = (int) (bl(p00, 0, fx, fy, p10, p01, p11));
                out.setRGB(x, y, (r << 16) | (g << 8) | b2);
            }
        }
        return out;
    }

    static double bl(int p00, int shift, double fx, double fy, int p10, int p01, int p11) {
        double v00 = (p00 >> shift) & 0xFF, v10 = (p10 >> shift) & 0xFF;
        double v01 = (p01 >> shift) & 0xFF, v11 = (p11 >> shift) & 0xFF;
        return (1 - fx) * (1 - fy) * v00 + fx * (1 - fy) * v10 + (1 - fx) * fy * v01 + fx * fy * v11;
    }

    static float[] similarity(float[] src, float[] dst) {
        int n = 5;
        double sx = 0, sy = 0, dx = 0, dy = 0;
        for (int i = 0; i < n; i++) {
            sx += src[i * 2]; sy += src[i * 2 + 1];
            dx += dst[i * 2]; dy += dst[i * 2 + 1];
        }
        sx /= n; sy /= n; dx /= n; dy /= n;
        double varSrc = 0;
        double[][] C = new double[2][2];
        for (int i = 0; i < n; i++) {
            double x = src[i * 2] - sx, y = src[i * 2 + 1] - sy;
            double u = dst[i * 2] - dx, v = dst[i * 2 + 1] - dy;
            varSrc += x * x + y * y;
            C[0][0] += u * x; C[0][1] += u * y;
            C[1][0] += v * x; C[1][1] += v * y;
        }
        C[0][0] /= n; C[0][1] /= n; C[1][0] /= n; C[1][1] /= n;
        varSrc /= n;
        double[] svd = svd2x2(C);
        double u00 = svd[0], u01 = svd[1], u10 = svd[2], u11 = svd[3];
        double s0 = svd[4], s1 = svd[5];
        double v00 = svd[6], v01 = svd[7], v10 = svd[8], v11 = svd[9];
        double detU = u00 * u11 - u01 * u10, detV = v00 * v11 - v01 * v10;
        double sgn = (detU * detV) < 0 ? -1 : 1;
        double US00 = u00, US01 = u01 * sgn, US10 = u10, US11 = u11 * sgn;
        double r00 = US00 * v00 + US01 * v01, r01 = US00 * v10 + US01 * v11;
        double r10 = US10 * v00 + US11 * v01, r11 = US10 * v10 + US11 * v11;
        double scale = (s0 + s1 * sgn) / varSrc;
        double tx = dx - scale * (r00 * sx + r01 * sy);
        double ty = dy - scale * (r10 * sx + r11 * sy);
        return new float[]{(float) (scale * r00), (float) (scale * r01), (float) tx,
                (float) (scale * r10), (float) (scale * r11), (float) ty};
    }

    static double[] svd2x2(double[][] C) {
        double a = C[0][0], b = C[0][1], c = C[1][0], d = C[1][1];
        double p = a * a + c * c, q = a * b + c * d, r = b * b + d * d;
        double tr = p + r;
        double disc = Math.sqrt(Math.max(0, (p - r) * (p - r) / 4 + q * q));
        double l1 = tr / 2 + disc, l2 = Math.max(0, tr / 2 - disc);
        double s0 = Math.sqrt(Math.max(0, l1)), s1 = Math.sqrt(l2);
        double v1x, v1y, v2x, v2y;
        if (Math.abs(q) < 1e-12) {
            if (p >= r) { v1x = 1; v1y = 0; v2x = 0; v2y = 1; } else { v1x = 0; v1y = 1; v2x = 1; v2y = 0; }
        } else {
            v1x = q; v1y = l1 - p;
            double n1 = Math.hypot(v1x, v1y);
            if (n1 < 1e-12) { v1x = 1; v1y = 0; n1 = 1; }
            v1x /= n1; v1y /= n1;
            v2x = -v1y; v2y = v1x;
        }
        double u00, u01, u10, u11;
        if (s0 > 1e-12) { u00 = (a * v1x + b * v1y) / s0; u10 = (c * v1x + d * v1y) / s0; }
        else { u00 = 1; u10 = 0; }
        if (s1 > 1e-12) { u01 = (a * v2x + b * v2y) / s1; u11 = (c * v2x + d * v2y) / s1; }
        else { u01 = -u10; u11 = u00; }
        return new double[]{u00, u01, u10, u11, s0, s1, v1x, v2x, v1y, v2y};
    }

    /** RGB 顺序 + [-1,1]；bgr=true 时交换 R/B 用于对照实验。 */
    static float[] embed(BufferedImage img, boolean rgb) throws Exception {
        FloatBuffer fb = FloatBuffer.allocate(3 * 112 * 112);
        for (int c = 0; c < 3; c++) {
            int shift = c == 0 ? (SWAP ? 0 : 16) : (c == 1 ? 8 : (SWAP ? 16 : 0));
            for (int y = 0; y < 112; y++)
                for (int x = 0; x < 112; x++) {
                    int p = img.getRGB(x, y);
                    int v = (p >> shift) & 0xFF;
                    fb.put((v - 127.5f) / 127.5f);
                }
        }
        fb.rewind();
        try (OnnxTensor t = OnnxTensor.createTensor(env, fb, new long[]{1, 3, 112, 112});
             OrtSession.Result r = session.run(Collections.singletonMap(inputName, t))) {
            Object v = r.get(0).getValue();
            float[] out = new float[dim];
            if (v instanceof float[][]) System.arraycopy(((float[][]) v)[0], 0, out, 0, dim);
            else if (v instanceof float[]) System.arraycopy((float[]) v, 0, out, 0, dim);
            normalize(out);
            return out;
        }
    }

    static void normalize(float[] v) {
        double s = 0;
        for (float x : v) s += (double) x * x;
        s = Math.sqrt(s);
        for (int i = 0; i < v.length; i++) v[i] /= (float) s;
    }

    static float cos(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += (double) a[i] * b[i];
        return (float) s;
    }
}

