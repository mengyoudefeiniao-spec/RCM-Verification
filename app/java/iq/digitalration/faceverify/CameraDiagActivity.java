package iq.digitalration.faceverify;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 相机诊断：不比对、不活体，只把「相机 → 旋转 → 帧率 → 人脸可检出」这条链路显示出来。
 * 用来在没有 USB 的情况下判断机型的相机行为是否正常。
 */
public class CameraDiagActivity extends Activity implements CameraController.FrameListener {

    private static final int REQ_CAM = 4001;

    private FrameLayout root;
    private ImageView preview;
    private TextView info;
    private LinearLayout buttons;

    private CameraController cam;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicInteger frames = new AtomicInteger();
    private final AtomicInteger faceHits = new AtomicInteger();
    private volatile boolean destroyed;
    private long lastPreview;
    private long statStart;
    private volatile String lastText = "启动中…";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        setContentView(root);

        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        info = new TextView(this);
        info.setTextSize(11);
        info.setTextColor(0xFFE6EEF8);
        info.setTypeface(Typeface.MONOSPACE);
        info.setBackgroundColor(0xCC000000);
        info.setPadding(dp(10), dp(8), dp(10), dp(8));
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.gravity = Gravity.TOP;
        root.addView(info, ip);

        buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setBackgroundColor(0xCC000000);
        buttons.setPadding(dp(6), dp(6), dp(6), dp(6));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.gravity = Gravity.BOTTOM;
        root.addView(buttons, bp);
        buildButtons();

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
        } else {
            startCamera();
        }
    }

    private void buildButtons() {
        buttons.removeAllViews();
        int[] opts = {-1, 0, 90, 180, 270};
        for (int o : opts) {
            Button b = new Button(this);
            b.setText(o < 0 ? "自动" : String.valueOf(o));
            b.setTextSize(11);
            b.setAllCaps(false);
            b.setPadding(0, 0, 0, 0);
            final int deg = o;
            b.setOnClickListener(v -> {
                RefStore.setRotOverride(this, deg);
                restart();
            });
            buttons.addView(b, new LinearLayout.LayoutParams(0, dp(42), 1f));
        }
        Button back = new Button(this);
        back.setText("返回");
        back.setTextSize(11);
        back.setAllCaps(false);
        back.setOnClickListener(v -> finish());
        buttons.addView(back, new LinearLayout.LayoutParams(0, dp(42), 1f));
    }

    private void restart() {
        ui.removeCallbacksAndMessages(null);   // 否则每次切换旋转都会多一条 tick 链
        if (cam != null) cam.stop();
        frames.set(0);
        faceHits.set(0);
        statStart = System.currentTimeMillis();
        startCamera();
    }

    private void startCamera() {
        ui.removeCallbacksAndMessages(null);
        cam = new CameraController(this, this);
        cam.setRotationOverride(RefStore.rotOverride(this));
        cam.start();
        statStart = System.currentTimeMillis();
        ui.postDelayed(this::tick, 600);
    }

    private void tick() {
        if (destroyed) return;
        long dt = System.currentTimeMillis() - statStart;
        if (dt < 500) dt = 500;
        double fps = frames.get() * 1000.0 / dt;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "机型  %s %s / %s / sdk %d%n",
                android.os.Build.MANUFACTURER, android.os.Build.MODEL,
                android.os.Build.SUPPORTED_ABIS[0], android.os.Build.VERSION.SDK_INT));
        if (cam != null) {
            sb.append(String.format(Locale.US, "相机  id=%s  sensor=%d°  前=%s%n",
                    cam.cameraId(), cam.sensorOrientation(), cam.isFront()));
            sb.append(String.format(Locale.US, "分析帧 %s  旋转 %d°%n",
                    cam.analysisSize(), cam.rotationDeg()));
            if (cam.lastError() != null) sb.append("错误  ").append(cam.lastError()).append('\n');
        }
        sb.append(String.format(Locale.US, "帧率  %.1f fps  人脸检出 %d/%d%n",
                fps, faceHits.get(), frames.get()));
        sb.append(lastText);
        info.setText(sb.toString());
        ui.postDelayed(this::tick, 700);
    }

    @Override
    public void onFrame(Bitmap bmp, long ts) {
        if (destroyed) return;
        frames.incrementAndGet();
        if (ts - lastPreview > 70) {
            lastPreview = ts;
            runOnUiThread(() -> {
                if (!destroyed) preview.setImageBitmap(bmp);
            });
        }
        App app = App.I;
        if (app.face != null) {
            FaceEngine.Result f;
            try {
                f = app.face.detect(bmp);
            } catch (Throwable t) {
                lastText = "检测异常: " + t;
                return;
            }
            if (f.found) faceHits.incrementAndGet();
            lastText = String.format(Locale.US,
                    "画面 %dx%d  人脸 %s  yaw %.2f  %s",
                    bmp.getWidth(), bmp.getHeight(), f.found ? "有" : "无", f.yaw,
                    f.error != null ? ("err=" + f.error) : "");
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] g) {
        super.onRequestPermissionsResult(req, p, g);
        if (req == REQ_CAM) {
            if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) startCamera();
            else finish();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        if (cam != null) cam.stop();
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
