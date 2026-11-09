package iq.digitalration.faceverify;

import android.graphics.Bitmap;
import android.graphics.RectF;

import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/**
 * 被动反欺诈（翻拍/屏幕重放）分：MiniFASNetV2，输入 80x80，输出 3 类
 * [print, real, replay]，取 real 概率。作为"辅助信号"，默认不硬拦截。
 */
public final class SpoofEngine {

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final int inH, inW;

    public SpoofEngine(String modelPath) throws Exception {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions so = new OrtSession.SessionOptions();
        so.setIntraOpNumThreads(1);
        so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        session = env.createSession(modelPath, so);
        Map<String, ai.onnxruntime.NodeInfo> in = session.getInputInfo();
        inputName = in.keySet().iterator().next();
        long[] shape = ((TensorInfo) in.get(inputName).getInfo()).getShape();
        int h = 80, w = 80;
        if (shape != null && shape.length == 4) {
            if (shape[2] > 0) h = (int) shape[2];
            if (shape[3] > 0) w = (int) shape[3];
        }
        inH = h; inW = w;
    }

    /** @return real 类概率 0..1；出错返回 -1。 */
    public float realScore(Bitmap frame, RectF box) {
        try {
            Bitmap crop = cropNorm(frame, box, 2.7f, inW, inH);
            if (crop == null) return -1f;
            int w = crop.getWidth(), h = crop.getHeight();
            int[] px = new int[w * h];
            crop.getPixels(px, 0, w, 0, 0, w, h);
            FloatBuffer fb = FloatBuffer.allocate(3 * w * h);
            for (int c = 0; c < 3; c++) {
                int shift = c == 0 ? 16 : (c == 1 ? 8 : 0);
                for (int p : px) {
                    int v = (p >> shift) & 0xFF;
                    fb.put((v / 255f - 0.5f) / 0.5f);
                }
            }
            fb.rewind();
            try (OnnxTensor t = OnnxTensor.createTensor(env, fb, new long[]{1, 3, h, w});
                 OrtSession.Result r = session.run(Collections.singletonMap(inputName, t))) {
                float[] logits = RecogEngine.flatten(r.get(0).getValue(), 3);
                if (logits.length < 3) return -1f;
                double sum = 0;
                float max = -1e9f;
                for (float v : logits) { if (v > max) max = v; }
                double[] ex = new double[logits.length];
                for (int i = 0; i < logits.length; i++) { ex[i] = Math.exp(logits[i] - max); sum += ex[i]; }
                return (float) (ex[1] / sum);
            }
        } catch (Throwable t) {
            return -1f;
        }
    }

    /** 以 bbox 中心、scale 倍边长的方形裁剪并缩放到 targetW x targetH。 */
    static Bitmap cropNorm(Bitmap src, RectF box, float scale, int targetW, int targetH) {
        float cx = box.centerX(), cy = box.centerY();
        float side = Math.max(box.width(), box.height()) * scale;
        if (side < 8) return null;
        float x0 = cx - side / 2f, y0 = cy - side / 2f;
        int ix0 = Math.max(0, Math.round(x0)), iy0 = Math.max(0, Math.round(y0));
        int ix1 = Math.min(src.getWidth(), Math.round(x0 + side));
        int iy1 = Math.min(src.getHeight(), Math.round(y0 + side));
        if (ix1 - ix0 < 8 || iy1 - iy0 < 8) return null;
        Bitmap c = Bitmap.createBitmap(src, ix0, iy0, ix1 - ix0, iy1 - iy0);
        if (c.getWidth() != targetW || c.getHeight() != targetH) {
            c = Bitmap.createScaledBitmap(c, targetW, targetH, true);
        }
        return c;
    }

    public void close() {
        try { session.close(); } catch (Throwable ignored) {}
    }
}
