import ai.onnxruntime.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Collections;

/** 快速判断 minifasnet_v2.onnx 是真模型还是退化模型：比较 4 种预处理下的输出。 */
public class SpoofVariants {
    public static void main(String[] args) throws Exception {
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession s = env.createSession(new File(args[0]).getAbsolutePath(), new OrtSession.SessionOptions());
        String in = s.getInputInfo().keySet().iterator().next();

        String[] names = {"[-1,1] full", "[0,1] full", "[-1,1] crop60", "imagenet-norm full"};
        for (int i = 1; i < args.length; i++) {
            BufferedImage img = ImageIO.read(new File(args[i]));
            if (img == null) continue;
            System.out.println("-- " + new File(args[i]).getName());
            for (int m = 0; m < 4; m++) {
                BufferedImage crop = m == 2 ? centerCrop(img, 0.6f) : img;
                float[] p = infer(env, s, in, crop, m);
                System.out.printf("   %-16s  [%.4f %.4f %.4f]%n", names[m], p[0], p[1], p[2]);
            }
        }
    }

    static float[] infer(OrtEnvironment env, OrtSession s, String inName, BufferedImage img, int mode) throws Exception {
        java.nio.FloatBuffer fb = java.nio.FloatBuffer.allocate(3 * 80 * 80);
        for (int c = 0; c < 3; c++) {
            int shift = c == 0 ? 16 : (c == 1 ? 8 : 0);
            for (int y = 0; y < 80; y++)
                for (int x = 0; x < 80; x++) {
                    int p = img.getRGB(x * img.getWidth() / 80, y * img.getHeight() / 80);
                    float v = ((p >> shift) & 0xFF) / 255f;
                    switch (mode) {
                        case 0: v = (v - 0.5f) / 0.5f; break;
                        case 1: break;
                        case 2: v = (v - 0.5f) / 0.5f; break;
                        default: v = (v - (c == 0 ? 0.485f : c == 1 ? 0.456f : 0.406f))
                                / (c == 0 ? 0.229f : c == 1 ? 0.224f : 0.225f); break;
                    }
                    fb.put(v);
                }
        }
        fb.rewind();
        try (OnnxTensor t = OnnxTensor.createTensor(env, fb, new long[]{1, 3, 80, 80});
             OrtSession.Result r = s.run(Collections.singletonMap(inName, t))) {
            Object v = r.get(0).getValue();
            float[] logits = v instanceof float[][] ? ((float[][]) v)[0] : (float[]) v;
            double sum = 0; float mx = -1e9f;
            for (float z : logits) mx = Math.max(mx, z);
            for (float z : logits) sum += Math.exp(z - mx);
            float[] p = new float[3];
            for (int i = 0; i < 3; i++) p[i] = (float) (Math.exp(logits[i] - mx) / sum);
            return p;
        }
    }

    static BufferedImage centerCrop(BufferedImage img, float frac) {
        int w = Math.round(img.getWidth() * frac), h = Math.round(img.getHeight() * frac);
        BufferedImage o = new BufferedImage(80, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = o.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, (img.getWidth() - w) / 2, (img.getHeight() - h) / 2, w, h, 0, 0, 80, 80, null);
        g.dispose();
        return o;
    }
}
