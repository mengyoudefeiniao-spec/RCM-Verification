package iq.digitalration.faceverify;

import android.graphics.Bitmap;

import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/**
 * ArcFace(AAM-Softmax) 特征提取：insightface w600k_r50.onnx，112x112，RGB，[-1,1]，NCHW。
 * 输出 512 维，L2 归一化后用余弦相似度做 1:1 比对。
 */
public final class RecogEngine {

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final int inH, inW;
    private final int dim;
    public volatile boolean flipTta = true;

    public RecogEngine(String modelPath, int threads) throws Exception {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions so = new OrtSession.SessionOptions();
        so.setIntraOpNumThreads(Math.max(1, threads));
        so.setInterOpNumThreads(1);
        so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        session = env.createSession(modelPath, so);

        Map<String, ai.onnxruntime.NodeInfo> in = session.getInputInfo();
        inputName = in.keySet().iterator().next();
        long[] shape = ((TensorInfo) in.get(inputName).getInfo()).getShape();
        int h = 112, w = 112;
        if (shape != null && shape.length == 4) {
            if (shape[2] > 0) h = (int) shape[2];
            if (shape[3] > 0) w = (int) shape[3];
        }
        inH = h; inW = w;

        Map<String, ai.onnxruntime.NodeInfo> out = session.getOutputInfo();
        long[] oshape = ((TensorInfo) out.values().iterator().next().getInfo()).getShape();
        int d = 512;
        if (oshape != null && oshape.length == 2 && oshape[1] > 0) d = (int) oshape[1];
        dim = d;
    }

    public int dim() { return dim; }
    public int inputSize() { return inW; }

    /** 输入必须是已对齐的 112x112（或模型输入尺寸）位图。 */
    public float[] embed(Bitmap aligned) {
        float[] e = raw(aligned);
        normalize(e);
        return e;
    }

    /** 带水平翻转 TTA：两次前向平均，抵消对齐镜像误差并小幅提升准确率。 */
    public float[] embedTta(Bitmap aligned) {
        float[] a = raw(aligned);
        float[] b = raw(ImgUtil.flipHorizontal(aligned));
        for (int i = 0; i < a.length; i++) a[i] += b[i];
        normalize(a);
        return a;
    }

    private float[] raw(Bitmap bmp) {
        Bitmap in = bmp;
        if (bmp.getWidth() != inW || bmp.getHeight() != inH) {
            in = Bitmap.createScaledBitmap(bmp, inW, inH, true);
        }
        int w = inW, h = inH;
        int[] px = new int[w * h];
        in.getPixels(px, 0, w, 0, 0, w, h);
        FloatBuffer fb = FloatBuffer.allocate(3 * w * h);
        // NCHW，RGB，(x-127.5)/127.5
        for (int c = 0; c < 3; c++) {
            int shift = c == 0 ? 16 : (c == 1 ? 8 : 0);
            for (int i = 0; i < px.length; i++) {
                int v = (px[i] >> shift) & 0xFF;
                fb.put((v - 127.5f) / 127.5f);
            }
        }
        fb.rewind();
        try (OnnxTensor t = OnnxTensor.createTensor(env, fb, new long[]{1, 3, h, w});
             OrtSession.Result r = session.run(Collections.singletonMap(inputName, t))) {
            Object v = r.get(0).getValue();
            return flatten(v, dim);
        } catch (Exception ex) {
            throw new RuntimeException("ArcFace 推理失败: " + ex.getMessage(), ex);
        }
    }

    static float[] flatten(Object v, int dim) {
        float[] out = new float[dim];
        if (v instanceof float[][]) {
            float[][] b = (float[][]) v;
            if (b.length > 0) System.arraycopy(b[0], 0, out, 0, Math.min(dim, b[0].length));
        } else if (v instanceof float[]) {
            float[] b = (float[]) v;
            System.arraycopy(b, 0, out, 0, Math.min(dim, b.length));
        } else if (v instanceof float[][][]) {
            float[][][] b = (float[][][]) v;
            if (b.length > 0 && b[0].length > 0) System.arraycopy(b[0][0], 0, out, 0, Math.min(dim, b[0][0].length));
        }
        return out;
    }

    public static void normalize(float[] v) {
        double s = 0;
        for (float x : v) s += (double) x * x;
        s = Math.sqrt(s);
        if (s < 1e-9) return;
        float inv = (float) (1.0 / s);
        for (int i = 0; i < v.length; i++) v[i] *= inv;
    }

    public static float cosine(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        double s = 0;
        for (int i = 0; i < n; i++) s += (double) a[i] * b[i];
        return (float) s;
    }

    public void close() {
        try { session.close(); } catch (Throwable ignored) {}
    }
}
