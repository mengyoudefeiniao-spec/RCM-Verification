// 对齐敏感度实验：把 5 点中的某一个点人为偏移 d 像素，重新对齐后与原裁剪比对，
// 看 embedding 掉多少。用来判断「MediaPipe 关键点索引选得准不准」这件事到底多要紧。
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

public class PerturbTest {
    static final float[][] CANON = {
            {89f, 106f}, {161f, 106f}, {125f, 141f}, {100f, 172f}, {150f, 172f}
    };
    static final String[] NAMES = {"左眼", "右眼", "鼻尖", "左嘴角", "右嘴角"};
    static final int[] DELTAS = {3, 6, 12, 20};

    public static void main(String[] args) throws Exception {
        ArcFaceCheck.main(new String[]{args[0], args[1]}); // 初始化会话

        double[][] sum = new double[5][DELTAS.length];
        double[][] min = new double[5][DELTAS.length];
        for (double[] row : min) java.util.Arrays.fill(row, 9);
        int n = 0;

        for (int k = 1; k < args.length; k++) {
            BufferedImage img = ImageIO.read(new File(args[k]));
            if (img == null) continue;
            BufferedImage c0 = ArcFaceCheck.warp(img, flat(CANON));
            float[] e0 = ArcFaceCheck.embed(c0, true);
            for (int pt = 0; pt < 5; pt++) {
                for (int di = 0; di < DELTAS.length; di++) {
                    int d = DELTAS[di];
                    double acc = 0;
                    int cnt = 0;
                    for (int[] dir : new int[][]{{d, 0}, {-d, 0}, {0, d}, {0, -d}}) {
                        float[][] p = new float[5][2];
                        for (int i = 0; i < 5; i++) { p[i][0] = CANON[i][0]; p[i][1] = CANON[i][1]; }
                        p[pt][0] += dir[0];
                        p[pt][1] += dir[1];
                        BufferedImage c = ArcFaceCheck.warp(img, flat(p));
                        float s = ArcFaceCheck.cos(e0, ArcFaceCheck.embed(c, true));
                        acc += s;
                        cnt++;
                        min[pt][di] = Math.min(min[pt][di], s);
                    }
                    sum[pt][di] += acc / cnt;
                }
            }
            n++;
        }

        System.out.println("\n=== 单点偏移后与原始对齐裁剪的余弦相似度（n=" + n + " 张）===");
        System.out.printf("%-8s", "偏移点");
        for (int d : DELTAS) System.out.printf("   d=%2dpx  ", d);
        System.out.println();
        for (int pt = 0; pt < 5; pt++) {
            System.out.printf("%-8s", NAMES[pt]);
            for (int di = 0; di < DELTAS.length; di++) {
                System.out.printf("  %.3f/%.3f", sum[pt][di] / Math.max(1, n), min[pt][di]);
            }
            System.out.println("   (均值/最差)");
        }
        System.out.println("\n解读：>0.98 基本无影响；0.90~0.98 轻微；<0.85 说明该点很关键。");
    }

    static float[] flat(float[][] p) {
        float[] o = new float[p.length * 2];
        for (int i = 0; i < p.length; i++) { o[i * 2] = p[i][0]; o[i * 2 + 1] = p[i][1]; }
        return o;
    }
}
