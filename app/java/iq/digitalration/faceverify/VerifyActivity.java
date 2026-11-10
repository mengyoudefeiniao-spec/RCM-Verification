package iq.digitalration.faceverify;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 实时核验：活体挑战 → 多帧特征平均 → 余弦相似度判定。 */
public class VerifyActivity extends Activity implements CameraController.FrameListener {

    private static final int REQ_CAM = 2001;

    private enum State { LIVE, COLLECT, MATCH, DONE }

    private static final class Cand {
        Bitmap aligned;
        float quality;
        float yaw;
        float boxW;
        float blur;
    }

    private FrameLayout root;
    private ImageView preview;
    private OverlayView overlay;
    private LinearLayout resultPanel;

    private CameraController cam;
    private final ExecutorService compute = Executors.newSingleThreadExecutor();
    private final List<Cand> cands = new ArrayList<>();

    private volatile State state = State.LIVE;
    private Liveness liveness;
    private volatile Liveness.State livenessState;
    private long collectStart;
    private int faceStreak;
    private volatile boolean destroyed;
    private long lastPreviewTs;
    private volatile int frameW = 1, frameH = 1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        setContentView(root);

        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackgroundColor(0xFF000000);
        root.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlay = new OverlayView(this);
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        resultPanel = buildResultPanel();
        root.addView(resultPanel);

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
        } else {
            initCamera();
        }
    }

    private void initCamera() {
        if (App.I.recog == null || RefStore.emb == null) {
            Toast.makeText(this, "模型或参照特征未就绪", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        cam = new CameraController(this, this);
        cam.setRotationOverride(RefStore.rotOverride(this));
        cam.start();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] g) {
        super.onRequestPermissionsResult(req, p, g);
        if (req == REQ_CAM) {
            if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) initCamera();
            else {
                Toast.makeText(this, "需要相机权限", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    // ---------------- 帧处理（相机线程） ----------------
    @Override
    public void onFrame(Bitmap bmp, long ts) {
        if (destroyed) return;
        if (state == State.DONE || state == State.MATCH) return;

        frameW = bmp.getWidth();
        frameH = bmp.getHeight();
        postPreview(bmp, ts);

        App app = App.I;
        FaceEngine fe = app.face;
        if (fe == null) return;

        FaceEngine.Result f;
        try {
            f = fe.detect(bmp);
        } catch (Throwable t) {
            return;
        }
        final FaceEngine.Result ff = f;
        final float yaw = f.found ? f.yaw : 0f;
        final float boxW = f.found ? f.box.width() : 0f;
        final int nFaces = f.numFaces;

        if (state == State.LIVE) {
            if (!f.found) {
                faceStreak = 0;
                overlayState("请把脸放到框内", f.error != null ? ("检测异常: " + f.error) : "正对镜头，光线均匀", 0, 0, yaw, 0, nFaces);
                return;
            }
            faceStreak++;
            if (liveness == null && faceStreak >= 3) liveness = new Liveness(System.nanoTime());
            if (liveness != null) {
                Liveness.State st = liveness.update(new FaceSensor(f), ts);
                livenessState = st;
                if (st.passed) {
                    state = State.COLLECT;
                    collectStart = System.currentTimeMillis();
                    overlayState("活体通过，正在采集…", "", st.done, st.total, yaw, boxW, nFaces);
                    addCandidate(bmp, f);
                } else {
                    overlayState(st.instruction, st.hint, st.done, st.total, yaw, boxW, nFaces);
                }
            } else {
                overlayState("保持正对镜头", "活体检测即将开始", 0, 0, yaw, boxW, nFaces);
            }
            return;
        }

        if (state == State.COLLECT) {
            if (f.found) addCandidate(bmp, f);
            long elapsed = System.currentTimeMillis() - collectStart;
            boolean enough = cands.size() >= 6 || (elapsed > 1400 && cands.size() >= 2) || elapsed > 3000;
            if (enough) {
                state = State.MATCH;
                overlayState("比对中…", "ArcFace 特征提取", 3, 3, yaw, boxW, nFaces);
                startMatch();
            } else {
                String hint = f.plausible ? ("已采集 " + cands.size() + " 帧")
                        : ("几何不可信：" + f.rejectReason + "，请正对镜头");
                overlayState("保持不动…", hint, 3, 3, yaw, boxW, nFaces);
            }
        }
    }

    /** 把 MediaPipe 检测结果适配成活体模块的观测量。 */
    private static final class FaceSensor implements Liveness.Sensor {
        private final FaceEngine.Result r;
        FaceSensor(FaceEngine.Result r) { this.r = r; }
        @Override public boolean found() { return r.found; }
        @Override public float blink() { return 0.5f * (r.get("eyeBlinkLeft") + r.get("eyeBlinkRight")); }
        @Override public float yaw() { return r.yaw; }
        @Override public float jawOpen() { return r.jawOpen; }
        @Override public float smile() { return r.smile; }
    }
    private void postPreview(Bitmap bmp, long now) {
        if (now - lastPreviewTs < 55) return;
        lastPreviewTs = now;
        runOnUiThread(() -> {
            if (!destroyed) preview.setImageBitmap(bmp);
        });
    }

    private void addCandidate(Bitmap frame, FaceEngine.Result f) {
        try {
            float boxW = f.box.width();
            if (boxW < f.imgW * 0.14f) return;
            if (Math.abs(f.yaw) > 0.30f) return;
            if (!f.plausible) return;   // 5 点几何不可信，不参与比对
            Cand c = new Cand();
            c.aligned = Align.warp(frame, f.l5);
            if (c.aligned == null) return;

            float blur = (float) ImgUtil.laplacianVariance(c.aligned);
            float sizeScore = Math.min(1f, boxW / (f.imgW * 0.42f));
            float blurScore = Math.min(1f, blur / 120f);
            float poseScore = 1f - Math.min(1f, Math.abs(f.yaw) / 0.32f);
            float eyeScore = Math.min(1f, f.eyeOpen * 1.4f);
            c.quality = 0.34f * sizeScore + 0.28f * blurScore + 0.22f * poseScore + 0.16f * eyeScore;
            c.yaw = f.yaw;
            c.boxW = boxW;
            c.blur = blur;

            synchronized (cands) {
                cands.add(c);
                if (cands.size() > 6) {
                    Cand worst = cands.get(0);
                    for (Cand x : cands) if (x.quality < worst.quality) worst = x;
                    cands.remove(worst);
                }
            }
        } catch (Throwable ignored) {}
    }

    private void startMatch() {
        final float[] ref = RefStore.emb;
        final boolean tta = RefStore.flipTta(this);
        final float thr = RefStore.threshold(this);
        final List<Cand> snapshot;
        synchronized (cands) { snapshot = new ArrayList<>(cands); }

        compute.submit(() -> {
            long t0 = System.nanoTime();
            float[] acc = null;
            int used = 0;
            StringBuilder dbg = new StringBuilder();
            for (Cand c : snapshot) {
                try {
                    float[] e = tta ? App.I.recog.embedTta(c.aligned) : App.I.recog.embed(c.aligned);
                    if (acc == null) acc = new float[e.length];
                    for (int i = 0; i < e.length; i++) acc[i] += e[i];
                    used++;
                    dbg.append(String.format("帧 q=%.2f yaw=%.2f 人脸宽=%.0fpx 清晰度=%.0f\n",
                            c.quality, c.yaw, c.boxW, c.blur));
                } catch (Throwable ignored) {}
            }
            long ms = (System.nanoTime() - t0) / 1000000L;
            if (acc == null || used == 0) {
                runOnUiThread(() -> showError("特征提取失败：没有可用帧，请重试"));
                return;
            }
            RecogEngine.normalize(acc);
            float sim = RecogEngine.cosine(ref, acc);
            final float fsim = sim;
            final int fused = used;
            final long fms = ms;
            final String fdbg = dbg.toString();
            runOnUiThread(() -> showResult(fsim, thr, fused, fms, fdbg));
        });
    }

    // ---------------- 结果 ----------------
    private void showResult(float sim, float thr, int frames, long ms, String dbg) {
        state = State.DONE;
        boolean pass = sim >= thr;
        resultPanel.setVisibility(View.VISIBLE);
        TextView verdict = resultPanel.findViewById(1);
        TextView detail = resultPanel.findViewById(2);
        verdict.setText(pass ? "核验通过" : "核验不通过");
        verdict.setTextColor(pass ? 0xFF5FD4A0 : 0xFFFF6B6B);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("相似度 %.3f  /  阈值 %.2f  %s\n\n", sim, thr, pass ? "✓" : "✗"));
        sb.append("活体：通过（MediaPipe Face Mesh · blendshape 随机挑战）\n");
        Liveness.State ls = livenessState;
        if (ls != null) {
            sb.append(String.format("  峰值 眨眼%.2f 转头%.2f 张嘴%.2f 微笑%.2f\n",
                    ls.maxBlink, ls.maxYaw, ls.maxJaw, ls.maxSmile));
            sb.append("  动作 ").append(ls.log).append('\n');
        }
        sb.append(String.format("融合 %d 帧 · 特征耗时 %dms · TTA %s\n",
                frames, ms, RefStore.flipTta(this) ? "开" : "关"));
        sb.append("\n—— 采集明细 ——\n").append(dbg);
        String path = saveMontage();
        if (path != null) sb.append("\n调试图（参照 + 各帧对齐结果）：\n").append(path);
        detail.setText(sb.toString());

        // 现场验证清单：把本次结果留在本机，供 ChecklistActivity 汇总（与网络无关）
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("model", App.I.modelId);
            o.put("modelDesc", App.I.modelDesc);
            o.put("sim", Math.round(sim * 1000) / 1000.0);
            o.put("thr", Math.round(thr * 1000) / 1000.0);
            o.put("pass", pass);
            o.put("frames", frames);
            o.put("ms", ms);
            o.put("tta", RefStore.flipTta(this));
            if (ls != null) {
                o.put("blink", Math.round(ls.maxBlink * 100) / 100.0);
                o.put("yaw", Math.round(ls.maxYaw * 100) / 100.0);
                o.put("jaw", Math.round(ls.maxJaw * 100) / 100.0);
                o.put("smile", Math.round(ls.maxSmile * 100) / 100.0);
                o.put("actions", android.text.TextUtils.join(" → ", ls.log));
            }
            o.put("montage", path == null ? "" : path);
            ChecklistStore.putResult(this, "face", o);
        } catch (Throwable ignored) {
        }
    }

    /** 把「参照 + 本次各帧的 112x112 对齐结果」拼成一张图存盘，便于远端肉眼判断对齐是否正常。 */
    private String saveMontage() {
        try {
            List<Cand> snap;
            synchronized (cands) { snap = new ArrayList<>(cands); }
            int n = snap.size() + 1;
            if (n < 2) return null;
            int cell = 112, gap = 4;
            Bitmap out = Bitmap.createBitmap(cell * n + gap * (n - 1), cell, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(out);
            cv.drawColor(0xFF101010);
            int x = 0;
            Bitmap ref = RefStore.thumb;
            if (ref != null) {
                if (ref.getWidth() != cell) ref = Bitmap.createScaledBitmap(ref, cell, cell, true);
                cv.drawBitmap(ref, x, 0, null);
            }
            x += cell + gap;
            for (Cand c : snap) {
                if (c.aligned != null) cv.drawBitmap(c.aligned, x, 0, null);
                x += cell + gap;
            }
            java.io.File dir = new java.io.File(getExternalFilesDir(null), "debug");
            if (!dir.exists() && !dir.mkdirs()) return null;
            java.io.File f = new java.io.File(dir, "verify_" + System.currentTimeMillis() + ".png");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            out.compress(Bitmap.CompressFormat.PNG, 100, fo);
            fo.close();
            return f.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }
    private void showError(String msg) {
        state = State.DONE;
        resultPanel.setVisibility(View.VISIBLE);
        TextView verdict = resultPanel.findViewById(1);
        TextView detail = resultPanel.findViewById(2);
        verdict.setText("出错了");
        verdict.setTextColor(0xFFFF6B6B);
        detail.setText(msg);
    }

    // ---------------- UI ----------------
    private void overlayState(String ins, String hint, int done, int total,
                              float yaw, float boxW, int nFaces) {
        final String info = String.format("人脸 %d 个 · yaw %.2f · 脸宽 %.0fpx · 帧 %dx%d",
                nFaces, yaw, boxW, frameW, frameH);
        runOnUiThread(() -> {
            if (destroyed) return;
            overlay.setState(ins, hint, done, total);
            overlay.setInfo(info);
        });
    }

    private LinearLayout buildResultPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF210141B);
        bg.setCornerRadius(28);
        panel.setBackground(bg);
        panel.setPadding(dp(20), dp(24), dp(20), dp(20));
        panel.setVisibility(View.GONE);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        lp.setMargins(dp(16), 0, dp(16), 0);
        panel.setLayoutParams(lp);

        TextView title = new TextView(this);
        title.setId(1);
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(Color.WHITE);
        panel.addView(title);

        TextView detail = new TextView(this);
        detail.setId(2);
        detail.setTextSize(12.5f);
        detail.setTextColor(0xFFB9C6D8);
        detail.setPadding(0, dp(14), 0, dp(18));
        detail.setTextIsSelectable(true);
        panel.addView(detail);

        Button again = new Button(this);
        again.setText("重新测试");
        again.setAllCaps(false);
        again.setOnClickListener(v -> {
            resultPanel.setVisibility(View.GONE);
            synchronized (cands) { cands.clear(); }
            liveness = null;
            livenessState = null;
            faceStreak = 0;
            state = State.LIVE;
        });
        panel.addView(again, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        Button back = new Button(this);
        back.setText("返回");
        back.setAllCaps(false);
        back.setOnClickListener(v -> finish());
        panel.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        return panel;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (cam != null) cam.stop();
        compute.shutdownNow();
        super.onDestroy();
    }
}
