import ai.onnxruntime.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;

/** MiniFASNet 探针：区分"真脸 vs 屏幕翻拍"的类别索引与通道顺序。 */
public class SpoofCheck {
    static OrtEnvironment env;
    static OrtSession session;
    static String inName;

    public static void main(String[] args) throws Exception {
        env = OrtEnvironment.getEnvironment();
        session = env.createSession(new File(args[0]).getAbsolutePath(), new OrtSession.SessionOptions());
        inName = session.getInputInfo().keySet().iterator().next();

        double sumRealReal = 0, sumFakeReal = 0;
        int n = 0;
        for (int i = 1; i < args.length; i++) {
            BufferedImage img = ImageIO.read(new File(args[i]));
            if (img == null) continue;
            BufferedImage real = to80(img);

            float[] pReal = infer(real, false);
            float[] pRealBgr = infer(real, true);

            BufferedImage fake = screenRecapture(img);
            float[] pFake = infer(fake, false);
            float[] pFakeBgr = infer(fake, true);

            System.out.printf("%-34s real RGB=[%.3f %.3f %.3f]  BGR=[%.3f %.3f %.3f]%n",
                    new File(args[i]).getName(), pReal[0], pReal[1], pReal[2], pRealBgr[0], pRealBgr[1], pRealBgr[2]);
            System.out.printf("%-34s fake RGB=[%.3f %.3f %.3f]  BGR=[%.3f %.3f %.3f]%n",
                    "", pFake[0], pFake[1], pFake[2], pFakeBgr[0], pFakeBgr[1], pFakeBgr[2]);
            sumRealReal += pReal[1];
            sumFakeReal += pFake[1];
            n++;
        }
        System.out.printf("%nmean class-1 prob: real=%.3f  fake=%.3f  (n=%d)%n", sumRealReal / n, sumFakeReal / n, n);
    }

    static BufferedImage to80(BufferedImage img) {
        BufferedImage o = new BufferedImage(80, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = o.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, 80, 80, null);
        g.dispose();
        return o;
    }

    /** 模拟"对着屏幕翻拍"：降采样回放大 + 重压缩 + 轻微模糊/偏色 */
    static BufferedImage screenRecapture(BufferedImage img) throws Exception {
        int w = Math.round(img.getWidth() * 0.55f), h = Math.round(img.getHeight() * 0.55f);
        BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(small, "jpg", bos);
        BufferedImage jpg1 = ImageIO.read(new ByteArrayInputStream(bos.toByteArray()));
        BufferedImage big = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        g = big.createGraphics();
        g.drawImage(jpg1, 0, 0, img.getWidth(), img.getHeight(), null);
        g.dispose();
        // 轻模糊 + 提亮
        for (int y = 0; y < big.getHeight(); y++)
            for (int x = 0; x < big.getWidth(); x++) {
                int p = big.getRGB(x, y);
                int r = Math.min(255, ((p >> 16) & 0xFF) + 12);
                int gg = Math.min(255, ((p >> 8) & 0xFF) + 12);
                int b = Math.min(255, (p & 0xFF) + 14);
                big.setRGB(x, y, (r << 16) | (gg << 8) | b);
            }
        return big;
    }

    static float[] infer(BufferedImage img, boolean bgr) throws Exception {
        FloatBufferHolder fb = new FloatBufferHolder(3 * 80 * 80);
        for (int c = 0; c < 3; c++) {
            int shift = c == 0 ? (bgr ? 0 : 16) : (c == 1 ? 8 : (bgr ? 16 : 0));
            for (int y = 0; y < 80; y++)
                for (int x = 0; x < 80; x++) {
                    int v = (img.getRGB(x, y) >> shift) & 0xFF;
                    fb.put((v / 255f - 0.5f) / 0.5f);
                }
        }
        fb.rewind();
        try (OnnxTensor t = OnnxTensor.createTensor(env, fb.buf, new long[]{1, 3, 80, 80});
             OrtSession.Result r = session.run(Collections.singletonMap(inName, t))) {
            Object v = r.get(0).getValue();
            float[][] out = new float[1][3];
            if (v instanceof float[][]) out = (float[][]) v;
            float[] logits = out[0];
            double sum = 0;
            float max = -1e9f;
            for (float z : logits) max = Math.max(max, z);
            double[] ex = new double[logits.length];
            for (int i = 0; i < logits.length; i++) { ex[i] = Math.exp(logits[i] - max); sum += ex[i]; }
            float[] p = new float[logits.length];
            for (int i = 0; i < logits.length; i++) p[i] = (float) (ex[i] / sum);
            return p;
        }
    }

    static class FloatBufferHolder {
        final java.nio.FloatBuffer buf;
        FloatBufferHolder(int n) { buf = java.nio.FloatBuffer.allocate(n); }
        void put(float v) { buf.put(v); }
        void rewind() { buf.rewind(); }
    }
}
