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

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 前置摄像头人脸登录（v2.8）。
 *
 * <p><b>为什么不用系统 {@code BiometricPrompt}</b>：它的 {@code setAllowedAuthenticators(BIOMETRIC_WEAK)}
 * 只是"允许"两档，<b>不能指定只要人脸</b>——受屏下指纹的机型（小米/Redmi 实测）一律先弹指纹，
 * 于是「人脸登录」按下去还是按手指，前置摄像头根本没开。要"确实打开前置摄像头做人脸"，
 * 只能在 App 内自己做：
 *
 * <pre>前置相机 → MediaPipe(478 点 + blendshape) → 随机 3 个活体动作 → 6 帧对齐 112×112
 *   → ArcFace 特征平均 → 与登记参照算余弦 →（通过则）设备密钥签发登录令牌</pre>
 *
 * <p><b>证据强度照实标注，不冒充系统强生物识别</b>：
 * <ul>
 *   <li>{@code cryptoBound=false}：2D 人脸在 public SDK 里解锁不了 Keystore 的生物绑定密钥，
 *       令牌由普通设备密钥签名（与 {@link BioAuth#authenticateWeak} 同一档）；</li>
 *   <li>{@code identityBound=true/false}：登记过参照人脸才算"证明了是这个人"；
 *       没登记时只有活体证据（证明镜头前是真人，不证明是谁），结果里写明；</li>
 *   <li>活体是**挑战-响应**（眨眼/转头/张嘴/微笑随机抽 3 个），静态照片无法在时序上完成。</li>
 * </ul>
 *
 * <p>结果写入 {@code lab.json} 的 {@code results.bio.faceAuth}，随「导出验证报告」一起交回。
 */
public class FaceLoginActivity extends Activity implements CameraController.FrameListener {

    private static final int REQ_CAM = 2011;

    public static final String EXTRA_USER = "user";

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
    private long liveStart;
    private int faceStreak;
    private volatile boolean destroyed;
    private long lastPreviewTs;
    private volatile int frameW = 1, frameH = 1;

    private String user = "face-login";
    private volatile boolean refBound;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String u = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_USER);
        if (u != null && !u.trim().isEmpty()) user = u.trim();
        RefStore.load(this);
        refBound = RefStore.emb != null && RefStore.emb.length > 0;
        liveStart = System.currentTimeMillis();

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

        overlay.setInfo("人脸登录（前置摄像头） · 模型加载中…");

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
        } else {
            waitReadyThenStart();
        }
    }

    /** 引擎初始化是应用级一次性的；实验室可能先进来，这里等它 ready 再开相机。 */
    private void waitReadyThenStart() {
        App app = App.I;
        if (app == null) {
            toast("引擎未就绪，请回到主界面重进");
            finish();
            return;
        }
        app.ensureInit(
                () -> runOnUiThread(() -> {
                    if (!destroyed) initCamera();
                }),
                () -> runOnUiThread(() -> {
                    if (!destroyed && overlay != null) overlay.setInfo("人脸登录（前置摄像头） · " + app.status);
                }));
    }

    private void initCamera() {
        if (destroyed) return;
        if (App.I.face == null || App.I.recog == null) {
            toast("模型未就绪：" + (App.I.error == null ? "未知原因" : App.I.error));
            finish();
            return;
        }
        cam = new CameraController(this, this);
        cam.setBackCamera(false);                     // 明确要求前置：这个界面就是"对着自己刷脸"
        cam.setRotationOverride(RefStore.rotOverride(this));
        cam.start();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] g) {
        super.onRequestPermissionsResult(req, p, g);
        if (req != REQ_CAM) return;
        if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) waitReadyThenStart();
        else {
            toast("人脸登录需要相机权限");
            finish();
        }
    }

    // ------------------------------------------------------------ 帧处理（相机线程）

    @Override
    public void onFrame(Bitmap bmp, long ts) {
        if (destroyed) return;
        if (state == State.DONE || state == State.MATCH) return;

        frameW = bmp.getWidth();
        frameH = bmp.getHeight();
        postPreview(bmp, ts);

        FaceEngine fe = App.I == null ? null : App.I.face;
        if (fe == null) return;

        FaceEngine.Result f;
        try {
            f = fe.detect(bmp);
        } catch (Throwable t) {
            return;
        }
        final float yaw = f.found ? f.yaw : 0f;
        final float boxW = f.found ? f.box.width() : 0f;
        final int nFaces = f.numFaces;

        if (state == State.LIVE) {
            if (!f.found) {
                faceStreak = 0;
                overlayState("请把脸放到框内", f.error != null ? ("检测异常: " + f.error) : "正对镜头，光线均匀",
                        0, 0, yaw, boxW, nFaces);
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

    /** 把 MediaPipe 检测结果适配成活体模块的观测量（与核验页一致）。 */
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
            if (!f.plausible) return;
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
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 比对 + 签发

    private void startMatch() {
        final float[] ref = RefStore.emb;
        final boolean identityBound = ref != null && ref.length > 0;
        final boolean tta = RefStore.flipTta(this);
        final float thr = RefStore.threshold(this);
        final Liveness.State ls = livenessState;
        final List<Cand> snapshot;
        synchronized (cands) { snapshot = new ArrayList<>(cands); }

        compute.submit(() -> {
            long t0 = System.nanoTime();
            float[] acc = null;
            int used = 0;
            for (Cand c : snapshot) {
                try {
                    float[] e = tta ? App.I.recog.embedTta(c.aligned) : App.I.recog.embed(c.aligned);
                    if (acc == null) acc = new float[e.length];
                    for (int i = 0; i < e.length; i++) acc[i] += e[i];
                    used++;
                } catch (Throwable ignored) {
                }
            }
            long featureMs = (System.nanoTime() - t0) / 1000000L;
            if (acc == null || used == 0) {
                runOnUiThread(() -> showError("特征提取失败：没有可用帧，请重试"));
                return;
            }
            RecogEngine.normalize(acc);
            float sim = identityBound ? RecogEngine.cosine(ref, acc) : Float.NaN;
            boolean pass = !identityBound || sim >= thr;

            JSONObject r = buildResult(pass, identityBound, sim, thr, used, featureMs, ls);
            saveResult(r);
            runOnUiThread(() -> showResult(r));
        });
    }

    private JSONObject buildResult(boolean pass, boolean identityBound, float sim, float thr,
                                   int frames, long featureMs, Liveness.State ls) {
        JSONObject r = new JSONObject();
        try {
            r.put("ok", pass);
            r.put("mode", "front-camera-face");
            r.put("cameraFront", cam != null && cam.isFront());
            r.put("camera", cam == null ? "" : cam.cameraId());
            r.put("sensorOrientation", cam == null ? -1 : cam.sensorOrientation());
            r.put("frame", frameW + "x" + frameH);
            r.put("livenessPassed", true);
            r.put("livenessActions", ls == null ? "" : android.text.TextUtils.join(" → ", ls.log));
            // 判据是"抽到的动作都完成"；这两个字段只列抽到的动作及其峰值，
            // 没抽到的动作不出现（旧版本会把全程 yaw 峰值 0.04 当"转头"显示，容易被误读）
            r.put("tasks", ls == null ? "" : ls.tasksText());
            r.put("taskPeaks", ls == null ? "" : ls.peaksText());
            r.put("blink", ls == null ? 0 : Math.round(ls.maxBlink * 100) / 100.0);
            r.put("yawPeak", ls == null ? 0 : Math.round(ls.maxYaw * 100) / 100.0);
            r.put("jaw", ls == null ? 0 : Math.round(ls.maxJaw * 100) / 100.0);
            r.put("smile", ls == null ? 0 : Math.round(ls.maxSmile * 100) / 100.0);
            r.put("livenessMs", System.currentTimeMillis() - liveStart);
            r.put("identityBound", identityBound);
            if (identityBound) {
                r.put("similarity", Math.round(sim * 1000) / 1000.0);
                r.put("threshold", Math.round(thr * 1000) / 1000.0);
            } else {
                r.put("note", "没有登记参照人脸：本次只证明镜头前是真人（活体），未证明是谁；"
                        + "到主界面「① 从相册选择一张人像」登记后再测，才有 1:1 身份绑定");
            }
            r.put("frames", frames);
            r.put("featureMs", featureMs);
            r.put("tta", RefStore.flipTta(this));
            r.put("cryptoBound", false);
            r.put("token", issueToken(identityBound, sim, ls));
        } catch (Throwable t) {
            try {
                r.put("ok", false);
                r.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            } catch (Throwable ignored) {
            }
        }
        return r;
    }

    /**
     * 通过后签发登录令牌：普通设备密钥（非生物绑定）签名，payload 里带上这次是"前置相机活体 + 端侧比对"，
     * 让服务端知道证据来源，避免与系统强生物识别混同。
     */
    private JSONObject issueToken(boolean identityBound, float sim, Liveness.State ls) {
        JSONObject t = new JSONObject();
        try {
            JSONObject payload = new JSONObject();
            payload.put("user", user);
            payload.put("issuedAt", System.currentTimeMillis());
            payload.put("deviceId", Receipt.deviceId(this));
            payload.put("scope", "login");
            payload.put("authMode", "front-camera-face");
            payload.put("liveness", "challenge-response");
            payload.put("identityBound", identityBound);
            if (identityBound) payload.put("similarity", Math.round(sim * 1000) / 1000.0);
            String text = Receipt.canonical(payload);
            byte[] data = text.getBytes("UTF-8");
            byte[] sig = DeviceKey.sign(data);
            boolean ok = DeviceKey.verify(data, sig);

            t.put("payload", payload);
            t.put("payloadText", text);
            t.put("pubSha256", DeviceKey.publicSha256());
            t.put("sig", DeviceKey.b64(sig));
            t.put("verified", ok);
            t.put("cryptoBound", false);
            t.put("note", "App 内前置相机活体 + 端侧比对；签名不绑定系统生物识别（2D 人脸无法解锁 Keystore 生物密钥）");
            if (ls != null) t.put("livenessActions", android.text.TextUtils.join(" → ", ls.log));
        } catch (Throwable e) {
            try {
                t.put("verified", false);
                t.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            } catch (Throwable ignored) {
            }
        }
        return t;
    }

    private void saveResult(JSONObject r) {
        try {
            JSONObject bio = LabStore.get(this, "bio");
            if (bio == null) bio = new JSONObject();
            bio.put("faceAuth", r);
            LabStore.put(this, "bio", bio);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 结果面板

    private void showResult(JSONObject r) {
        state = State.DONE;
        if (cam != null) {
            cam.stop();
            cam = null;
        }
        boolean ok = r.optBoolean("ok");
        boolean identityBound = r.optBoolean("identityBound");

        resultPanel.setVisibility(View.VISIBLE);
        TextView verdict = resultPanel.findViewById(1);
        TextView detail = resultPanel.findViewById(2);
        verdict.setText(ok ? "人脸登录通过" : "人脸登录未通过");
        verdict.setTextColor(ok ? 0xFF5FD4A0 : 0xFFFF6B6B);

        StringBuilder sb = new StringBuilder();
        if (identityBound) {
            sb.append(String.format(Locale.US, "前置摄像头 · 活体通过 · 相似度 %.3f / 阈值 %.2f %s\n",
                    r.optDouble("similarity"), r.optDouble("threshold"), ok ? "✓" : "✗"));
            sb.append("身份绑定：是（与已登记参照人脸做了 1:1 比对）\n");
        } else {
            sb.append("前置摄像头 · 活体通过\n");
            sb.append("身份绑定：否（未登记参照人脸，只证明镜头前是真人）\n");
        }
        sb.append("活体动作：").append(r.optString("tasks")).append('\n');
        sb.append("各动作峰值：").append(r.optString("taskPeaks")).append('\n');
        sb.append(String.format(Locale.US,
                "  全程峰值（含自然微动，不作判据）眨眼%.2f 转头%.2f 张嘴%.2f 微笑%.2f · 用时 %dms\n",
                r.optDouble("blink"), r.optDouble("yawPeak"), r.optDouble("jaw"), r.optDouble("smile"),
                r.optLong("livenessMs")));
        sb.append(String.format(Locale.US, "相机：%s · 帧 %s · 融合 %d 帧 · 特征 %dms · TTA %s\n",
                r.optString("camera"), r.optString("frame"), r.optInt("frames"),
                r.optLong("featureMs"), r.optBoolean("tta") ? "开" : "关"));

        JSONObject token = r.optJSONObject("token");
        if (token != null) {
            sb.append("\n登录令牌（设备密钥签名，未绑定系统生物识别）\n");
            sb.append("  验签：").append(token.optBoolean("verified") ? "通过" : "失败").append('\n');
            String sig = token.optString("sig");
            sb.append("  payload：").append(token.optString("payloadText")).append('\n');
            if (!sig.isEmpty()) {
                sb.append("  签名：").append(sig.length() > 56 ? sig.substring(0, 56) + "…" : sig).append('\n');
            }
        }
        if (!identityBound) {
            sb.append("\n提示：到主界面「① 从相册选择一张人像」登记参照人脸后再测，");
            sb.append("这条就能给出 1:1 相似度（身份绑定）。");
        }
        detail.setText(sb.toString());
    }

    private void showError(String msg) {
        state = State.DONE;
        if (cam != null) {
            cam.stop();
            cam = null;
        }
        resultPanel.setVisibility(View.VISIBLE);
        TextView verdict = resultPanel.findViewById(1);
        TextView detail = resultPanel.findViewById(2);
        verdict.setText("出错了");
        verdict.setTextColor(0xFFFF6B6B);
        detail.setText(msg);
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
        title.setTextSize(26);
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
        again.setText("重新测一次");
        again.setAllCaps(false);
        again.setOnClickListener(v -> {
            resultPanel.setVisibility(View.GONE);
            synchronized (cands) { cands.clear(); }
            liveness = null;
            livenessState = null;
            faceStreak = 0;
            liveStart = System.currentTimeMillis();
            refBound = RefStore.emb != null;
            state = State.LIVE;
            initCamera();
        });
        panel.addView(again, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        Button back = new Button(this);
        back.setText("返回实验室");
        back.setAllCaps(false);
        back.setOnClickListener(v -> finish());
        panel.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        return panel;
    }

    // ------------------------------------------------------------ UI 小工具

    private void overlayState(String ins, String hint, int done, int total,
                              float yaw, float boxW, int nFaces) {
        final String info = String.format(Locale.US, "前置摄像头（%s） · 人脸 %d 个 · yaw %.2f · 脸宽 %.0fpx · 帧 %dx%d · %s",
                cam == null ? "?" : cam.cameraId(), nFaces, yaw, boxW, frameW, frameH,
                refBound ? "参照=已登记" : "参照=未登记");
        runOnUiThread(() -> {
            if (destroyed) return;
            overlay.setState(ins, hint, done, total);
            overlay.setInfo(info);
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (cam != null) cam.stop();
        compute.shutdownNow();
        super.onDestroy();
    }
}
