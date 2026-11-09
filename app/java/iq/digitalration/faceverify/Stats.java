package iq.digitalration.faceverify;

import java.util.Arrays;
import java.util.Random;

/**
 * 纯数学的人脸比对评测：由同人/异人对的相似度分布算 FAR/FRR/EER，
 * 并按目标 FAR 给出建议阈值。无 Android 依赖，可在桌面上直接跑 main() 自测。
 */
public final class Stats {

    private static final float T_MIN = -1.0f, T_MAX = 1.0f, T_STEP = 0.005f;

    public static final class Result {
        public int nGenuine, nImpostor;
        public float meanGenuine, meanImpostor;
        public float minGenuine = 2, maxImpostor = -2;
        public float eer;              // 等错误率
        public float eerThreshold;     // EER 对应阈值
        public float thresholdAtFar;   // 满足目标 FAR 的最低阈值（该点 TAR 最大）
        public float tarAtFar;         // = 1 - FRR
        public float farAtThreshold;
        public float frrAtThreshold;
        public String table = "";
    }

    private Stats() {}

    public static Result compute(float[] genuine, float[] impostor, float targetFar) {
        Result r = new Result();
        r.nGenuine = genuine.length;
        r.nImpostor = impostor.length;
        if (genuine.length == 0 || impostor.length == 0) return r;

        double sg = 0, si = 0;
        for (float v : genuine) { sg += v; r.minGenuine = Math.min(r.minGenuine, v); }
        for (float v : impostor) { si += v; r.maxImpostor = Math.max(r.maxImpostor, v); }
        r.meanGenuine = (float) (sg / genuine.length);
        r.meanImpostor = (float) (si / impostor.length);

        float[] gs = genuine.clone();
        float[] is = impostor.clone();
        Arrays.sort(gs);
        Arrays.sort(is);

        int steps = Math.round((T_MAX - T_MIN) / T_STEP) + 1;
        float[] far = new float[steps];
        float[] frr = new float[steps];
        float[] thr = new float[steps];

        float bestDiff = Float.MAX_VALUE;
        int eerIdx = 0;
        for (int i = 0; i < steps; i++) {
            float t = T_MIN + i * T_STEP;
            thr[i] = t;
            // FAR: 异人对分数 >= t 的比例
            int accImp = is.length - lowerBound(is, t);
            far[i] = accImp / (float) is.length;
            // FRR: 同人对分数 < t 的比例
            int rejGen = lowerBound(gs, t);
            frr[i] = rejGen / (float) gs.length;
            float diff = Math.abs(far[i] - frr[i]);
            if (diff < bestDiff) { bestDiff = diff; eerIdx = i; }
        }

        // EER 用相邻三点做一次线性插值，减少网格量化误差
        r.eerThreshold = thr[eerIdx];
        r.eer = (far[eerIdx] + frr[eerIdx]) / 2f;
        if (eerIdx > 0) {
            float d0 = frr[eerIdx - 1] - far[eerIdx - 1];
            float d1 = frr[eerIdx] - far[eerIdx];
            if (d0 != d1) {
                float k = d0 / (d0 - d1);
                r.eerThreshold = thr[eerIdx - 1] + k * (thr[eerIdx] - thr[eerIdx - 1]);
                float f = far[eerIdx - 1] + k * (far[eerIdx] - far[eerIdx - 1]);
                float g = frr[eerIdx - 1] + k * (frr[eerIdx] - frr[eerIdx - 1]);
                r.eer = (f + g) / 2f;
            }
        }

        // 满足目标 FAR 的最低阈值
        r.thresholdAtFar = thr[steps - 1];
        r.tarAtFar = 0f;
        r.farAtThreshold = far[steps - 1];
        r.frrAtThreshold = frr[steps - 1];
        for (int i = 0; i < steps; i++) {
            if (far[i] <= targetFar) {
                r.thresholdAtFar = thr[i];
                r.farAtThreshold = far[i];
                r.frrAtThreshold = frr[i];
                r.tarAtFar = 1f - frr[i];
                break;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("阈值     FAR       FRR       接受异对/拒绝同对\n");
        float[] marks = {0.30f, 0.35f, 0.40f, 0.45f, 0.50f, 0.55f, 0.60f};
        int mi = 0;
        for (int i = 0; i < steps; i++) {
            boolean show = false;
            if (mi < marks.length && thr[i] >= marks[mi] - 1e-4f) { show = true; mi++; }
            if (Math.abs(thr[i] - r.eerThreshold) < T_STEP / 2) show = true;
            if (Math.abs(thr[i] - r.thresholdAtFar) < T_STEP / 2) show = true;
            if (show) {
                sb.append(String.format("%.3f    %.4f    %.4f     %d/%d  <-%s%n",
                        thr[i], far[i], frr[i],
                        Math.round(far[i] * is.length), Math.round(frr[i] * gs.length),
                        (Math.abs(thr[i] - r.eerThreshold) < T_STEP / 2 ? " EER" : "")
                                + (Math.abs(thr[i] - r.thresholdAtFar) < T_STEP / 2 ? " 目标FAR" : "")));
            }
        }
        r.table = sb.toString();
        return r;
    }

    private static int lowerBound(float[] a, float v) {
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] < v) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    // ---------------- 自测 ----------------

    public static void main(String[] args) {
        // 1) 完全可分：应该 EER≈0，目标 FAR 阈值落在两类之间
        float[] g = new float[50];
        float[] im = new float[200];
        Arrays.fill(g, 0.8f);
        Arrays.fill(im, 0.2f);
        Result r = compute(g, im, 1e-2f);
        System.out.printf("[可分] meanG=%.3f meanI=%.3f EER=%.4f@%.3f thr@FAR1e-2=%.3f TAR=%.3f%n",
                r.meanGenuine, r.meanImpostor, r.eer, r.eerThreshold, r.thresholdAtFar, r.tarAtFar);
        System.out.println(r.table);

        // 2) 两个高斯：EER 应接近理论交叉点
        Random rnd = new Random(7);
        float[] g2 = new float[400];
        float[] i2 = new float[400];
        for (int i = 0; i < g2.length; i++) g2[i] = (float) (0.62 + rnd.nextGaussian() * 0.10);
        for (int i = 0; i < i2.length; i++) i2[i] = (float) (0.12 + rnd.nextGaussian() * 0.10);
        Result r2 = compute(g2, i2, 1e-3f);
        System.out.printf("%n[高斯] EER=%.4f@%.3f  thr@FAR1e-3=%.3f TAR=%.3f%n",
                r2.eer, r2.eerThreshold, r2.thresholdAtFar, r2.tarAtFar);

        // 3) 重叠严重：EER 应明显变大
        float[] g3 = new float[400];
        float[] i3 = new float[400];
        for (int i = 0; i < g3.length; i++) g3[i] = (float) (0.40 + rnd.nextGaussian() * 0.12);
        for (int i = 0; i < i3.length; i++) i3[i] = (float) (0.30 + rnd.nextGaussian() * 0.12);
        Result r3 = compute(g3, i3, 1e-3f);
        System.out.printf("[重叠] EER=%.4f@%.3f  thr@FAR1e-3=%.3f TAR=%.3f%n",
                r3.eer, r3.eerThreshold, r3.thresholdAtFar, r3.tarAtFar);
    }
}
