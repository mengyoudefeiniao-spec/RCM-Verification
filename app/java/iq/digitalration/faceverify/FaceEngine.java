package iq.digitalration.faceverify;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** MediaPipe Face Landmarker（478 点 + 52 blendshape）封装。 */
public final class FaceEngine {

    public static final String MODEL_ASSET = "face_landmarker.task";

    /** 5 点索引（MediaPipe FaceMesh）：左右眼外角、鼻尖、左右嘴角。 */
    private static final int IDX_EYE_A = 33;
    private static final int IDX_EYE_B = 263;
    private static final int IDX_NOSE = 4;
    private static final int IDX_MOUTH_A = 61;
    private static final int IDX_MOUTH_B = 291;

    public static final class Result {
        public boolean found;
        public int numFaces;
        public int imgW, imgH;
        public float[] lm;            // 478*3 像素坐标
        public float[] l5 = new float[10]; // 排序后的 5 点
        public Map<String, Float> bs = new HashMap<>();
        public RectF box = new RectF();
        public float yaw;             // >0 表示头部转向画面右侧
        public float pitch;
        public float eyeOpen;         // 0..1
        public float jawOpen;
        public float smile;
        public long ts;
        public boolean plausible = true;   // 5 点几何是否可信
        public String rejectReason = "";
        public String error;   // 检测/解析过程中的异常（便于远程定位）
        public float detectMs;

        public float get(String k) {
            Float v = bs.get(k);
            return v == null ? 0f : v;
        }
    }

    private final FaceLandmarker fl;

    public FaceEngine(Context ctx) throws Exception {
        BaseOptions base = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .build();
        FaceLandmarker.FaceLandmarkerOptions opts =
                FaceLandmarker.FaceLandmarkerOptions.builder()
                        .setBaseOptions(base)
                        .setRunningMode(RunningMode.IMAGE)
                        .setNumFaces(2)
                        .setOutputFaceBlendshapes(true)
                        .setOutputFacialTransformationMatrixes(false)
                        .setMinFaceDetectionConfidence(0.5f)
                        .setMinFacePresenceConfidence(0.5f)
                        .setMinTrackingConfidence(0.5f)
                        .build();
        this.fl = FaceLandmarker.createFromOptions(ctx, opts);
    }

    public synchronized Result detect(Bitmap bmp) {
        Result r = new Result();
        r.imgW = bmp.getWidth();
        r.imgH = bmp.getHeight();
        long t0 = System.nanoTime();
        MPImage img = new BitmapImageBuilder(bmp).build();
        FaceLandmarkerResult res;
        try {
            res = fl.detect(img);
        } catch (Throwable t) {
            r.found = false;
            return r;
        }
        r.detectMs = (System.nanoTime() - t0) / 1e6f;
        try {
        List<List<NormalizedLandmark>> faces = res.faceLandmarks();
        r.numFaces = faces == null ? 0 : faces.size();
        if (r.numFaces == 0) return r;

        List<NormalizedLandmark> f = faces.get(0);
        int n = f.size();
        r.lm = new float[n * 3];
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            NormalizedLandmark p = f.get(i);
            float x = p.x() * r.imgW, y = p.y() * r.imgH;
            r.lm[i * 3] = x;
            r.lm[i * 3 + 1] = y;
            r.lm[i * 3 + 2] = p.z();
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
        r.box.set(minX, minY, maxX, maxY);

        Optional<List<List<Category>>> bo = res.faceBlendshapes();
        if (bo.isPresent() && !bo.get().isEmpty()) {
            for (Category c : bo.get().get(0)) r.bs.put(c.categoryName(), c.score());
        }
        r.eyeOpen = 1f - 0.5f * (r.get("eyeBlinkLeft") + r.get("eyeBlinkRight"));
        r.jawOpen = r.get("jawOpen");
        r.smile = 0.5f * (r.get("mouthSmileLeft") + r.get("mouthSmileRight"));

        // 5 点：按 x 排序，保证与 insightface 模板左右一致（不依赖人脸左右语义）
        float[] e0 = pt(r, IDX_EYE_A), e1 = pt(r, IDX_EYE_B), nose = pt(r, IDX_NOSE),
                m0 = pt(r, IDX_MOUTH_A), m1 = pt(r, IDX_MOUTH_B);
        float[] eyeL = e0[0] <= e1[0] ? e0 : e1;
        float[] eyeR = e0[0] <= e1[0] ? e1 : e0;
        float[] mouthL = m0[0] <= m1[0] ? m0 : m1;
        float[] mouthR = m0[0] <= m1[0] ? m1 : m0;
        r.l5[0] = eyeL[0]; r.l5[1] = eyeL[1];
        r.l5[2] = eyeR[0]; r.l5[3] = eyeR[1];
        r.l5[4] = nose[0]; r.l5[5] = nose[1];
        r.l5[6] = mouthL[0]; r.l5[7] = mouthL[1];
        r.l5[8] = mouthR[0]; r.l5[9] = mouthR[1];

        // 头部姿态代理量（无需相机内参，单调、稳定）
        float eyeMidX = (eyeL[0] + eyeR[0]) / 2f;
        float eyeMidY = (eyeL[1] + eyeR[1]) / 2f;
        float eyeDist = Math.max(1e-3f, dist(eyeL, eyeR));
        r.yaw = (nose[0] - eyeMidX) / eyeDist;          // 0 = 正脸
        r.pitch = (nose[1] - eyeMidY) / eyeDist;        // 0.35~0.5 = 正脸
        // 5 点几何合理性（防遮挡/侧脸/误检导致的对齐崩坏）
        checkGeometry(r, eyeL, eyeR, nose, mouthL, mouthR);
        r.found = true;
        r.ts = System.currentTimeMillis();
        } catch (Throwable t) {
            // 解析结果时可能抛 NoClassDefFoundError 等，不能静默当成"没检测到人脸"
            r.found = false;
            r.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            android.util.Log.e("FaceEngine", "detect/parse failed", t);
        }
        return r;
    }

    /** 眼睛应基本等高、鼻尖在两眼中点下方、嘴角在鼻下、嘴宽与眼距比例合理。 */
    private static void checkGeometry(Result r, float[] eyeL, float[] eyeR, float[] nose,
                                      float[] mouthL, float[] mouthR) {
        float eyeDist = Math.max(1e-3f, dist(eyeL, eyeR));
        float mouthW = dist(mouthL, mouthR);
        float eyeMidY = (eyeL[1] + eyeR[1]) / 2f;
        float mouthMidY = (mouthL[1] + mouthR[1]) / 2f;
        String bad = null;

        if (Math.abs(eyeL[1] - eyeR[1]) / eyeDist > 0.45f) bad = "双眼不等高（头太歪）";
        else if (mouthW / eyeDist < 0.38f || mouthW / eyeDist > 1.35f) bad = "嘴宽/眼距比例异常";
        else if (nose[1] <= eyeMidY) bad = "鼻尖位置异常";
        else if (nose[1] >= mouthMidY) bad = "鼻尖低于嘴角";
        else if (mouthMidY <= eyeMidY) bad = "嘴部在眼睛上方";
        else if (eyeDist < 24f) bad = "人脸太小";

        if (bad != null) {
            r.plausible = false;
            r.rejectReason = bad;
        }
    }

    private static float[] pt(Result r, int idx) {
        return new float[]{r.lm[idx * 3], r.lm[idx * 3 + 1]};
    }

    static float dist(float[] a, float[] b) {
        return (float) Math.hypot(a[0] - b[0], a[1] - b[1]);
    }

    public void close() {
        try { fl.close(); } catch (Throwable ignored) {}
    }
}
