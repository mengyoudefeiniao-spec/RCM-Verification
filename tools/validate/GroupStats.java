// 分人群统计「异人相似度」分布：同一处理流程（中心裁剪 112），比较不同种族的嵌入拥挤程度。
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class GroupStats {
    public static void main(String[] args) throws Exception {
        ArcFaceCheck.init(args[0]);   // 只初始化会话
        System.out.println("\n组别                  n     均值     p95      p99     最大   >0.30  >0.40  >0.45");
        for (int a = 1; a < args.length; a++) {
            File dir = new File(args[a]);
            File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".jpg"));
            if (files == null || files.length < 3) { System.out.println("skip " + dir); continue; }
            Arrays.sort(files);
            List<float[]> embs = new ArrayList<>();
            for (File f : files) {
                BufferedImage img = ImageIO.read(f);
                if (img == null) continue;
                embs.add(ArcFaceCheck.embed(ArcFaceCheck.centerCrop112(img), true));
            }
            int n = embs.size();
            double sum = 0; long cnt = 0;
            double[] all = new double[n * (n - 1) / 2];
            int k = 0;
            int over30 = 0, over40 = 0, over45 = 0;
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    float s = ArcFaceCheck.cos(embs.get(i), embs.get(j));
                    all[k++] = s;
                    sum += s;
                    if (s > 0.30f) over30++;
                    if (s > 0.40f) over40++;
                    if (s > 0.45f) over45++;
                }
            }
            int m = k;
            Arrays.sort(all, 0, m);
            System.out.printf("%-18s %5d  %.3f  %.3f   %.3f   %.3f   %.1f%%  %.2f%%  %.2f%%%n",
                    dir.getName(), n, sum / m,
                    all[(int) (m * 0.95)], all[(int) (m * 0.99)], all[m - 1],
                    100.0 * over30 / m, 100.0 * over40 / m, 100.0 * over45 / m);
            // 阈值扫描：FAR = 异人对分数 >= t 的比例
            StringBuilder sweep = new StringBuilder("     FAR@");
            int aboveThreshold;
            for (float t = 0.30f; t <= 0.76f; t += 0.05f) {
                aboveThreshold = 0;
                for (int i = 0; i < m; i++) if (all[i] >= t - 1e-6) aboveThreshold++;
                sweep.append(String.format(java.util.Locale.US, " %.2f:%.2e", t, (double) aboveThreshold / m));
            }
            System.out.println(sweep);
        }
    }
}
