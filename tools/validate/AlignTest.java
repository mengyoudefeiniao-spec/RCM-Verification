// 对齐算法校验：把原图旋转 20° 后，用「同样旋转后的 5 点」重新对齐，
// 若 Umeyama + 双线性 warp 正确，两张 112x112 裁剪应几乎一致 → 相似度接近 1。
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;

public class AlignTest {
    // LFW 250x250 平均脸的大致 5 点（眼-眼-鼻-嘴角-嘴角）
    static final float[][] CANON = {
            {89f, 106f}, {161f, 106f}, {125f, 141f}, {100f, 172f}, {150f, 172f}
    };

    public static void main(String[] args) throws Exception {
        File model = new File(args[0]);
        ArcFaceCheck.main(new String[]{model.getAbsolutePath(), args[1]}); // 预热并加载会话
        double sumPlain = 0, sumRot = 0;
        int n = 0;
        for (int i = 1; i < args.length; i++) {
            BufferedImage img = ImageIO.read(new File(args[i]));
            if (img == null) continue;

            BufferedImage rot = rotate(img, 20);
            float[] p = flat(CANON);
            float[] pRot = flat(rotatePoints(CANON, img.getWidth() / 2.0, img.getHeight() / 2.0, 20));

            BufferedImage c0 = ArcFaceCheck.warp(img, p);
            BufferedImage c1 = ArcFaceCheck.warp(rot, pRot);
            ImageIO.write(c0, "png", new File("build/val/crop_plain_" + i + ".png"));
            ImageIO.write(c1, "png", new File("build/val/crop_rot_" + i + ".png"));

            float[] e0 = ArcFaceCheck.embed(c0, true);
            float[] e1 = ArcFaceCheck.embed(c1, true);
            float s = ArcFaceCheck.cos(e0, e1);
            sumRot += s;
            n++;
            System.out.printf("%6.3f  rotated-vs-plain  %s%n", s, new File(args[i]).getName());
        }
        System.out.printf("mean = %.3f  (理想 ~0.95+；明显偏低说明对齐/插值有问题)%n", sumRot / Math.max(1, n));
    }

    static float[] flat(float[][] p) {
        float[] o = new float[p.length * 2];
        for (int i = 0; i < p.length; i++) { o[i * 2] = p[i][0]; o[i * 2 + 1] = p[i][1]; }
        return o;
    }

    static float[][] rotatePoints(float[][] p, double cx, double cy, double deg) {
        double r = Math.toRadians(deg), c = Math.cos(r), s = Math.sin(r);
        float[][] o = new float[p.length][2];
        for (int i = 0; i < p.length; i++) {
            double dx = p[i][0] - cx, dy = p[i][1] - cy;
            o[i][0] = (float) (cx + c * dx - s * dy);
            o[i][1] = (float) (cy + s * dx + c * dy);
        }
        return o;
    }

    static BufferedImage rotate(BufferedImage src, double deg) {
        double r = Math.toRadians(deg);
        double cx = src.getWidth() / 2.0, cy = src.getHeight() / 2.0;
        AffineTransform at = new AffineTransform();
        at.translate(cx, cy);
        at.rotate(r);
        at.translate(-cx, -cy);
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, at, null);
        g.dispose();
        return out;
    }
}
