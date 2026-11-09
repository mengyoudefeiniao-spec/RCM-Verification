// 直接对 App 里编译出的真实类做 JVM 测试（对齐矩阵 + 活体状态机）。
// 运行：java -cp "build/classes;<android.jar>" JvmTest
import iq.digitalration.faceverify.Align;
import iq.digitalration.faceverify.Liveness;

public class JvmTest {
    public static void main(String[] args) {
        testAlign();
        testLiveness();
    }

    // ---------- 1. 对齐矩阵：用已知相似变换生成点对，看能否精确还原 ----------
    static void testAlign() {
        System.out.println("=== Align.similarity ===");
        float[] src = {
                90, 105,    // 眼
                160, 103,   // 眼
                126, 140,   // 鼻
                101, 173,   // 嘴角
                151, 171    // 嘴角
        };
        double[] cases = {1.0, 1.7, 0.6};
        double[] angles = {0, 17, -33};
        double maxErr = 0;
        for (double s : cases) {
            for (double deg : angles) {
                double r = Math.toRadians(deg);
                double tx = 23, ty = -11;
                float[] dst = new float[10];
                for (int i = 0; i < 5; i++) {
                    double x = src[i * 2], y = src[i * 2 + 1];
                    dst[i * 2] = (float) (s * (Math.cos(r) * x - Math.sin(r) * y) + tx);
                    dst[i * 2 + 1] = (float) (s * (Math.sin(r) * x + Math.cos(r) * y) + ty);
                }
                float[] m = Align.similarity(src, dst);
                if (m == null) { System.out.println("  返回 null（不该发生）"); continue; }
                double err = 0;
                for (int i = 0; i < 5; i++) {
                    double px = m[0] * src[i * 2] + m[1] * src[i * 2 + 1] + m[2];
                    double py = m[3] * src[i * 2] + m[4] * src[i * 2 + 1] + m[5];
                    err = Math.max(err, Math.hypot(px - dst[i * 2], py - dst[i * 2 + 1]));
                }
                // 尺度还原：sqrt(a^2+c^2)
                double recovered = Math.hypot(m[0], m[3]);
                System.out.printf("  s=%.1f rot=%4.0f°  最大点误差=%.2e px  还原尺度=%.4f（应为 %.1f）%n",
                        s, deg, err, recovered, s);
                maxErr = Math.max(maxErr, err);
            }
        }
        System.out.println(maxErr < 1e-3 ? "  ✓ 通过（误差 < 0.001px）" : "  ✗ 失败，误差过大：" + maxErr);

        // 退化输入不能崩
        float[] flat = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        System.out.println("  退化输入（全同点）返回 " + (Align.similarity(flat, flat) == null ? "null ✓" : "非 null ✗"));
    }

    // ---------- 2. 活体状态机：喂合成序列，检查判定与超时 ----------
    static void testLiveness() {
        System.out.println("\n=== Liveness 状态机 ===");
        testSwap();

        // (a) 完整完成 3 个动作：按提示逐帧喂
        Liveness lv = new Liveness(12345L);
        Liveness.State st = null;
        long ts = 1000;
        int guard = 0;
        while (!lv.state().finished && guard++ < 4000) {
            ts += 100;
            Liveness.Task ins = lv.currentTask();
            if (ins == Liveness.Task.BLINK) {
                // 睁 -> 闭 -> 睁
                st = lv.update(sensor(true, 0.05f, 0, 0, 0), ts);
                st = lv.update(sensor(true, 0.70f, 0, 0, 0), ts += 100);
                st = lv.update(sensor(true, 0.05f, 0, 0, 0), ts += 100);
            } else if (ins == Liveness.Task.TURN) {
                st = lv.update(sensor(true, 0, 0.30f, 0, 0), ts += 100);
                st = lv.update(sensor(true, 0, 0.31f, 0, 0), ts += 100);
            } else if (ins == Liveness.Task.MOUTH) {
                st = lv.update(sensor(true, 0, 0, 0.60f, 0), ts += 100);
                st = lv.update(sensor(true, 0, 0, 0.61f, 0), ts += 100);
            } else {
                st = lv.update(sensor(true, 0, 0, 0, 0.70f), ts += 100);
                st = lv.update(sensor(true, 0, 0, 0, 0.71f), ts += 100);
            }
        }
        System.out.printf("  (a) 完成 3 个动作：finished=%s passed=%s done=%d/%d log=%s%n",
                lv.state().finished, lv.state().passed, lv.state().done, lv.state().total, lv.state().log);

        // (b) 静态照片：永远不眨眼/不转头，只在超时后重置，不应通过
        Liveness lv2 = new Liveness(999L);
        long t2 = 5000;
        for (int i = 0; i < 600; i++) {
            t2 += 100;
            lv2.update(sensor(true, 0.03f, 0.01f, 0.02f, 0.01f), t2);
        }
        System.out.printf("  (b) 静止 60s：finished=%s passed=%s done=%d（应 False/False/0）%n",
                lv2.state().finished, lv2.state().passed, lv2.state().done);

        // (c) 没人脸：不应推进
        Liveness lv3 = new Liveness(1L);
        long t3 = 0;
        for (int i = 0; i < 100; i++) lv3.update(sensor(false, 0, 0, 0, 0), t3 += 100);
        System.out.printf("  (c) 无脸 10s：done=%d（应 0）hint=%s%n", lv3.state().done, lv3.state().hint);

        // (d) 随机性：不同种子应产生不同动作顺序
        StringBuilder seqs = new StringBuilder();
        for (long seed : new long[]{1L, 2L, 7L, 42L}) {
            Liveness l = new Liveness(seed);
            seqs.append(seed).append("=").append(l.currentInstruction()).append("  ");
        }
        System.out.println("  (d) 不同种子首个动作：" + seqs);

        // (e) 单帧误触不应通过（转头要连续 2 帧）
        Liveness lv5 = new Liveness(4242L);
        String first = String.valueOf(lv5.currentTask());
        long t5 = 0;
        // 先把它推到转头（若首个不是，则先完成眨眼等）——这里直接测"单帧高值不触发"
        lv5.update(sensor(true, 0, 0.9f, 0.9f, 0.9f), t5 += 100);
        System.out.printf("  (e) 首帧全高值(%s)：done=%d（多帧要求生效则应为 0）%n", first, lv5.state().done);
    }

    /** 用户眨不了眼（低帧率抓不到）：连续超时后应自动换动作，最终仍能通过。 */
    static void testSwap() {
        Liveness lv = null;
        for (long seed = 1; seed < 500; seed++) {
            Liveness l = new Liveness(seed);
            if (l.currentTask() == Liveness.Task.BLINK) { lv = l; break; }
        }
        if (lv == null) { System.out.println("  (f) 找不到以眨眼开头的种子"); return; }
        long ts = System.currentTimeMillis();   // 必须与状态机内部时钟同基准
        int guard = 0;
        while (!lv.state().finished && guard++ < 5000) {
            Liveness.Task t = lv.currentTask();
            if (t == null) break;
            switch (t) {
                case BLINK:   // 永远眨不了
                    lv.update(sensor(true, 0.02f, 0f, 0f, 0f), ts += 100);
                    break;
                case TURN:
                    lv.update(sensor(true, 0f, 0.30f, 0f, 0f), ts += 100);
                    lv.update(sensor(true, 0f, 0.30f, 0f, 0f), ts += 100);
                    break;
                case MOUTH:
                    lv.update(sensor(true, 0f, 0f, 0.60f, 0f), ts += 100);
                    lv.update(sensor(true, 0f, 0f, 0.60f, 0f), ts += 100);
                    break;
                default:
                    lv.update(sensor(true, 0f, 0f, 0f, 0.70f), ts += 100);
                    lv.update(sensor(true, 0f, 0f, 0f, 0.70f), ts += 100);
                    break;
            }
        }
        System.out.printf("  (f) 眨不了眼：finished=%s passed=%s done=%d log=%s%n",
                lv.state().finished, lv.state().passed, lv.state().done, lv.state().log);
    }

    static Liveness.Sensor sensor(final boolean found, final float blink,
                                  final float yaw, final float jaw, final float smile) {
        return new Liveness.Sensor() {
            public boolean found() { return found; }
            public float blink() { return blink; }
            public float yaw() { return yaw; }
            public float jawOpen() { return jaw; }
            public float smile() { return smile; }
        };
    }
}
