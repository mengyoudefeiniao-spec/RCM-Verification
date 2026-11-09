// 退化实验：把同一张人脸做降分辨率 / JPEG 压缩 / 模糊 / 明暗变化，
// 看 ArcFace 相似度掉多少。用来推导「参照照片最低质量要求」。
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DegradeTest {

    static final String[] NAMES = {
            "原图", "缩到160", "缩到120", "缩到80", "缩到48", "缩到32",
            "JPEG q=60", "JPEG q=30", "JPEG q=15", "模糊σ2", "模糊σ4", "亮度-40", "亮度+40"
    };

    public static void main(String[] args) throws Exception {
        ArcFaceCheck.init(args[0]);
        double[] sum = new double[NAMES.length];
        int[] count = new int[NAMES.length];

        for (int a = 1; a < args.length; a++) {
            BufferedImage src = ImageIO.read(new File(args[a]));
            if (src == null) continue;
            float[] base = ArcFaceCheck.embed(ArcFaceCheck.centerCrop112(src), true);

            List<BufferedImage> variants = new ArrayList<>();
            variants.add(src);                                  // 0 原图
            for (int px : new int[]{160, 120, 80, 48, 32}) {     // 1-5 降分辨率（再放大回原尺寸）
                variants.add(rescaleRound(src, px));
            }
            variants.add(jpeg(src, 0.60f));                      // 6
            variants.add(jpeg(src, 0.30f));                      // 7
            variants.add(jpeg(src, 0.15f));                      // 8
            variants.add(blur(src, 2f));                         // 9
            variants.add(blur(src, 4f));                         // 10
            variants.add(brightness(src, -40));                  // 11
            variants.add(brightness(src, +40));                  // 12

            for (int i = 0; i < variants.size() && i < NAMES.length; i++) {
                float[] e = ArcFaceCheck.embed(ArcFaceCheck.centerCrop112(variants.get(i)), true);
                sum[i] += ArcFaceCheck.cos(base, e);
                count[i]++;
            }
        }

        System.out.println("\n=== 同一张人脸退化后与原图的余弦相似度（均值）===");
        System.out.printf("%-12s %8s   %s%n", "条件", "相似度", "相对原图掉落");
        for (int i = 0; i < NAMES.length; i++) {
            if (count[i] == 0) continue;
            double mean = sum[i] / count[i];
            System.out.printf("%-12s %8.3f   %+.3f%n", NAMES[i], mean, mean - 1.0);
        }
        System.out.println("\n解读：同人正常在 0.6~0.85；退化到 0.5 以下基本等于「换了一个人」。");
    }

    /** 把整图缩小到 facePx 见方再放大回原尺寸：等价于「人脸只有 facePx 像素大」。 */
    static BufferedImage rescaleRound(BufferedImage src, int facePx) {
        int w = Math.max(8, Math.round(src.getWidth() * facePx / 250f));
        int h = Math.max(8, Math.round(src.getHeight() * facePx / 250f));
        BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = small.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        BufferedImage big = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        g = big.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(small, 0, 0, src.getWidth(), src.getHeight(), null);
        g.dispose();
        return big;
    }

    static BufferedImage jpeg(BufferedImage src, float quality) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam p = writer.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(quality);
        MemoryCacheImageOutputStream mos = new MemoryCacheImageOutputStream(bos);
        writer.setOutput(mos);
        writer.write(null, new IIOImage(src, null, null), p);
        mos.close();
        writer.dispose();
        return ImageIO.read(new ByteArrayInputStream(bos.toByteArray()));
    }

    static BufferedImage blur(BufferedImage src, float sigma) {
        int r = Math.max(1, Math.round(sigma));
        int size = r * 2 + 1;
        float[] k = new float[size * size];
        float c = 1f / (size * size);
        for (int i = 0; i < k.length; i++) k[i] = c;
        ConvolveOp op = new ConvolveOp(new Kernel(size, size, k), ConvolveOp.EDGE_NO_OP, null);
        return op.filter(src, null);
    }

    static BufferedImage brightness(BufferedImage src, int delta) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int p = src.getRGB(x, y);
                int r = clamp(((p >> 16) & 0xFF) + delta);
                int g = clamp(((p >> 8) & 0xFF) + delta);
                int b = clamp((p & 0xFF) + delta);
                out.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return out;
    }

    static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
