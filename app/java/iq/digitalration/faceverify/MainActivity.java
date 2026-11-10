package iq.digitalration.faceverify;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.Locale;

/** 主界面：选参照照片（建模）+ 启动核验 + 参数。 */
public class MainActivity extends Activity {

    private static final int REQ_PICK = 1001;

    private TextView statusTv, refTv, thrTv, logTv;
    private ImageView refIv;
    private Button verifyBtn;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Bitmap pendingRef;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        RefStore.load(this);

        final App app = App.I;
        app.ensureInit(
                () -> ui.post(this::refreshAll),
                () -> ui.post(this::refreshStatus));
        ui.postDelayed(this::refreshStatus, 300);
    }

    private void refreshStatus() {
        App app = App.I;
        if (statusTv != null) statusTv.setText(app.status);
        if (app.ready) {
            if (verifyBtn != null) verifyBtn.setEnabled(RefStore.has(this) && refMatchesModel());
            refreshRef();
        }
    }

    private void refreshAll() {
        refreshStatus();
        refreshRef();
    }

    /** 参照特征是否属于当前模型；换 APK（标准版/精简版）后必须重建。 */
    private boolean refMatchesModel() {
        String tag = RefStore.modelTag;
        String cur = App.I.modelId;
        return tag == null || tag.isEmpty() || cur == null || tag.equals(cur);
    }

    private void refreshRef() {
        if (refTv == null) return;
        if (RefStore.has(this) && RefStore.thumb != null && !refMatchesModel()) {
            refIv.setVisibility(View.GONE);
            refTv.setText("参照照片与当前模型不匹配（" + RefStore.modelTag + " → " + App.I.modelId + "）\n请重新选择一张人像建模");
            refTv.setTextColor(0xFFFFB020);
            verifyBtn.setEnabled(false);
            return;
        }
        if (RefStore.has(this) && RefStore.thumb != null) {
            refIv.setImageBitmap(RefStore.thumb);
            refIv.setVisibility(View.VISIBLE);
            refTv.setText("参照特征已就绪 · " + RefStore.emb.length + " 维 · 建模 "
                    + RefStore.genMs + "ms" + (RefStore.label.isEmpty() ? "" : " · " + RefStore.label)
                    + (RefStore.quality.isEmpty() ? "" : "\n" + RefStore.quality));
            refTv.setTextColor(RefStore.qualityWarn ? 0xFFFFB020 : 0xFF5FD4A0);
            verifyBtn.setEnabled(App.I.ready);
        } else {
            refIv.setVisibility(View.GONE);
            refTv.setText("尚未选择参照照片");
            refTv.setTextColor(0xFF9AA6B8);
            verifyBtn.setEnabled(false);
        }
    }

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF0E1117);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, dp(24), pad, dp(32));
        sv.addView(root);

        TextView title = text("离线人脸核验 · 技术验证", 22, 0xFFF2F5FA);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = text("MediaPipe Face Mesh 活体 + ArcFace 1:1 比对 · 全程飞行模式可运行", 13, 0xFF8A96A8);
        sub.setPadding(0, dp(6), 0, dp(14));
        root.addView(sub);

        statusTv = text(App.I.status, 12, 0xFF7FB2FF);
        statusTv.setBackground(round(0xFF151A24, 10));
        statusTv.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(statusTv);

        // ---- 步骤 1 ----
        root.addView(stepTitle("① 选择参照照片"));
        Button pick = new Button(this);
        pick.setText("从相册选择一张人像");
        pick.setTextSize(16);
        pick.setAllCaps(false);
        pick.setOnClickListener(v -> pickImage());
        root.addView(pick, fullWidth());

        refIv = new ImageView(this);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(120), dp(120));
        ip.topMargin = dp(12);
        ip.gravity = Gravity.CENTER_HORIZONTAL;
        refIv.setLayoutParams(ip);
        refIv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(refIv);

        refTv = text("尚未选择参照照片", 13, 0xFF9AA6B8);
        refTv.setGravity(Gravity.CENTER);
        refTv.setPadding(0, dp(8), 0, dp(4));
        root.addView(refTv);

        // ---- 步骤 2 ----
        root.addView(stepTitle("② 实时活体 + 比对"));
        verifyBtn = new Button(this);
        verifyBtn.setText("开始核验");
        verifyBtn.setTextSize(17);
        verifyBtn.setAllCaps(false);
        verifyBtn.setEnabled(false);
        verifyBtn.setOnClickListener(v -> {
            if (!RefStore.has(this)) { toast("请先选择参照照片"); return; }
            startActivity(new Intent(this, VerifyActivity.class));
        });
        root.addView(verifyBtn, fullWidth());

        // ---- 参数 ----
        root.addView(stepTitle("③ 本地精度评测（可选）"));
        Button eval = new Button(this);
        eval.setText("打开评测模式 · 算 FAR/FRR/EER 并标定阈值");
        eval.setTextSize(14);
        eval.setAllCaps(false);
        eval.setOnClickListener(v -> startActivity(new Intent(this, EvalActivity.class)));
        root.addView(eval, fullWidth());

        Button card = new Button(this);
        card.setText("证件核验（卡背 OCR 家庭编号 + NFC 芯片）");
        card.setTextSize(14);
        card.setAllCaps(false);
        card.setOnClickListener(v -> startActivity(new Intent(this, IdCardActivity.class)));
        root.addView(card, fullWidth());

        Button compare = new Button(this);
        compare.setText("双图比对（不用相机，验证核心算法）");
        compare.setTextSize(14);
        compare.setAllCaps(false);
        compare.setOnClickListener(v -> {
            if (!RefStore.has(this)) { toast("请先选择参照照片"); return; }
            startActivity(new Intent(this, CompareActivity.class));
        });
        root.addView(compare, fullWidth());

        Button camdiag = new Button(this);
        camdiag.setText("相机诊断（看方向/帧率/能否检出）");
        camdiag.setTextSize(14);
        camdiag.setAllCaps(false);
        camdiag.setOnClickListener(v -> startActivity(new Intent(this, CameraDiagActivity.class)));
        root.addView(camdiag, fullWidth());

        Button selftest = new Button(this);
        selftest.setText("自检（不用相机/相册，验证管线）");
        selftest.setTextSize(14);
        selftest.setAllCaps(false);
        selftest.setOnClickListener(v -> runSelfTest());
        root.addView(selftest, fullWidth());

        // ---- 技术验证实验室 ----
        root.addView(stepTitle("④ 技术验证实验室"));
        Button lab = new Button(this);
        lab.setText("技术验证实验室（逐条自测 + 报告导出）");
        lab.setTextSize(14);
        lab.setAllCaps(false);
        lab.setOnClickListener(v -> startActivity(new Intent(this, LabActivity.class)));
        root.addView(lab, fullWidth());

        // ---- 参数 ----
        root.addView(stepTitle("参数"));
        thrTv = text("", 13, 0xFFCDD6E3);
        root.addView(thrTv);
        SeekBar sb = new SeekBar(this);
        sb.setMax(60);
        sb.setProgress(Math.round((RefStore.threshold(this) - 0.20f) * 100));
        updateThrLabel();
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                RefStore.setThreshold(MainActivity.this, 0.20f + p / 100f);
                updateThrLabel();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(sb);

        Switch tta = new Switch(this);
        tta.setText("水平翻转 TTA（更准，耗时 ×2）");
        tta.setTextColor(0xFFCDD6E3);
        tta.setTextSize(13);
        tta.setChecked(RefStore.flipTta(this));
        tta.setOnCheckedChangeListener((v, c) -> RefStore.setFlipTta(this, c));
        root.addView(tta);

        Switch strict = new Switch(this);
        strict.setText("活体失败时也给出相似度（调试用）");
        strict.setTextColor(0xFFCDD6E3);
        strict.setTextSize(13);
        strict.setChecked(RefStore.strictSpoof(this));
        strict.setOnCheckedChangeListener((v, c) -> RefStore.setStrictSpoof(this, c));
        root.addView(strict);

        // ---- 调试 ----
        root.addView(stepTitle("调试"));
        TextView rotTv = text("分析帧旋转（画面方向不对时手动指定）", 12, 0xFF7C8798);
        root.addView(rotTv);
        LinearLayout rotRow = new LinearLayout(this);
        rotRow.setOrientation(LinearLayout.HORIZONTAL);
        int[] opts = {-1, 0, 90, 180, 270};
        for (int o : opts) {
            Button bb = new Button(this);
            bb.setText(o < 0 ? "自动" : String.valueOf(o));
            bb.setTextSize(12);
            bb.setAllCaps(false);
            bb.setOnClickListener(v -> {
                RefStore.setRotOverride(this, o);
                toast("旋转设置为 " + (o < 0 ? "自动" : o + "°"));
            });
            rotRow.addView(bb, new LinearLayout.LayoutParams(0, dp(44), 1f));
        }
        root.addView(rotRow);

        Button clear = new Button(this);
        clear.setText("清空参照照片");
        clear.setTextSize(13);
        clear.setAllCaps(false);
        clear.setOnClickListener(v -> {
            RefStore.clear(this);
            refreshRef();
        });
        root.addView(clear);

        logTv = text("", 11, 0xFF6E7A8C);
        logTv.setPadding(0, dp(10), 0, 0);
        logTv.setTextIsSelectable(true);
        root.addView(logTv);

        String crash = App.I.lastCrash();
        if (crash != null && !crash.isEmpty()) {
            TextView ct = text("上次异常：\n" + crash, 10, 0xFFFF9A9A);
            ct.setTextIsSelectable(true);
            ct.setPadding(0, dp(16), 0, 0);
            root.addView(ct);
            Button cb = new Button(this);
            cb.setText("清除异常记录");
            cb.setTextSize(12);
            cb.setAllCaps(false);
            cb.setOnClickListener(v -> {
                App.I.clearCrash();
                toast("已清除，重启应用后不再显示");
            });
            root.addView(cb);
        }
        return sv;
    }

    private void updateThrLabel() {
        if (thrTv != null) {
            thrTv.setText(String.format("余弦相似度阈值：%.2f", RefStore.threshold(this)));
        }
    }

    /** 设备端自检：不用相机/相册，跑一遍「检测 → 对齐 → 特征」并给出耗时与一致性。 */
    private void runSelfTest() {
        final TextView tv = text("自检中…（首次会先加载模型）", 12, 0xFFCDD6E3);
        tv.setTextIsSelectable(true);
        final ImageView iv = new ImageView(this);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(140), dp(140));
        ip.topMargin = dp(12);
        iv.setLayoutParams(ip);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(18), dp(18), dp(18));
        box.addView(tv);
        box.addView(iv);

        ScrollView sc = new ScrollView(this);
        sc.addView(box);
        final Dialog dlg = new Dialog(this);
        dlg.setTitle("设备端自检");
        dlg.setContentView(sc);
        dlg.show();

        new Thread(() -> {
            final StringBuilder sb = new StringBuilder();
            Bitmap al = null;
            String err = null;
            try {
                App app = App.I;
                long w0 = System.currentTimeMillis();
                while (!app.ready && app.error == null && System.currentTimeMillis() - w0 < 300000) {
                    Thread.sleep(200);
                }
                if (app.error != null) throw new IllegalStateException("模型加载失败：" + app.error);
                if (app.face == null || app.recog == null) throw new IllegalStateException("引擎未就绪");

                sb.append(String.format(Locale.US, "%s %s / %s / sdk %d%n",
                        android.os.Build.MANUFACTURER, android.os.Build.MODEL,
                        android.os.Build.SUPPORTED_ABIS[0], android.os.Build.VERSION.SDK_INT));
                sb.append(String.format(Locale.US, "模型加载 %dms%n%n", app.initMs));

                Bitmap bmp = ImgUtil.decodeAsset(getAssets(), "selftest.jpg", 480);
                sb.append(String.format(Locale.US, "测试图 %dx%d%n%n", bmp.getWidth(), bmp.getHeight()));

                long t = System.nanoTime();
                FaceEngine.Result f = app.face.detect(bmp);
                long d1 = (System.nanoTime() - t) / 1000000;
                sb.append(String.format(Locale.US, "① 人脸网格  %s · %d 点 · %dms%n",
                        f.found ? "检出" : "未检出", f.lm == null ? 0 : f.lm.length / 3, d1));
                if (!f.found) throw new IllegalStateException("自检图未检出人脸，模型可能加载异常");
                sb.append(String.format(Locale.US, "   yaw=%.2f blink=%.2f jaw=%.2f%n",
                        f.yaw, 0.5f * (f.get("eyeBlinkLeft") + f.get("eyeBlinkRight")), f.jawOpen));

                t = System.nanoTime();
                al = Align.warp(bmp, f.l5);
                long d2 = (System.nanoTime() - t) / 1000000;
                if (al == null) throw new IllegalStateException("对齐失败");
                sb.append(String.format(Locale.US, "② 5 点对齐 112x112 · %dms%n", d2));

                t = System.nanoTime();
                float[] e1 = app.recog.embed(al);
                long d3 = (System.nanoTime() - t) / 1000000;
                sb.append(String.format(Locale.US, "③ ArcFace  %d 维 · %dms%n", e1.length, d3));

                t = System.nanoTime();
                float[] e2 = app.recog.embed(al);
                long d4 = (System.nanoTime() - t) / 1000000;
                float det = RecogEngine.cosine(e1, e2);
                sb.append(String.format(Locale.US, "   重复一致性 %.3f（应为 1.000）· %dms%n", det, d4));

                t = System.nanoTime();
                float[] e3 = app.recog.embedTta(al);
                long d5 = (System.nanoTime() - t) / 1000000;
                float flip = RecogEngine.cosine(e1, e3);
                sb.append(String.format(Locale.US, "   翻转 TTA 一致性 %.3f（0.75~0.95 正常）· %dms%n", flip, d5));

                long single = d3;
                sb.append(String.format(Locale.US, "%n预估单次核验：%dms（6 帧+TTA 约 %dms）%n",
                        d3, d3 * 12));

                Runtime rt = Runtime.getRuntime();
                sb.append(String.format(Locale.US, "内存 已用 %dMB / 上限 %dMB%n",
                        (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20));

                if (flip < 0.55f) sb.append("\n⚠ 翻转一致性偏低，预处理/对齐可能有问题");
                if (det < 0.999f) sb.append("\n⚠ 推理不确定，检查是否加载了错误的模型");
                if (single > 3000) sb.append("\n⚠ 单帧特征耗时过高，这台机器可能较吃力");
                sb.append("\n把这段文字截图给我即可评估设备端性能。");
            } catch (Throwable e) {
                err = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            final Bitmap fal = al;
            final String ferr = err;
            ui.post(() -> {
                if (ferr != null) sb.append("\n✗ ").append(ferr);
                tv.setText(sb.toString());
                if (fal != null) iv.setImageBitmap(Bitmap.createScaledBitmap(fal, 280, 280, false));
            });
        }, "selftest").start();
    }
    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            toast("无法打开相册：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        statusTv.setText("正在解析参照照片…");
        new Thread(() -> {
            String err = null;
            Bitmap bmp = null;
            try {
                bmp = decodeUri(uri, 1600);
                if (bmp == null) throw new IllegalStateException("图片解码失败");
                App app = App.I;
                if (!app.ready) {
                    final long t0 = System.currentTimeMillis();
                    while (!app.ready && app.error == null && System.currentTimeMillis() - t0 < 300000) {
                        Thread.sleep(200);
                    }
                    if (app.error != null) throw new IllegalStateException(app.error);
                }
                long t0 = System.nanoTime();
                FaceEngine.Result f = app.face.detect(bmp);
                if (!f.found) throw new IllegalStateException(f.error != null ? ("检测异常 " + f.error) : "照片里没检测到人脸");
                Bitmap aligned = Align.warp(bmp, f.l5);
                if (aligned == null) throw new IllegalStateException("人脸对齐失败");
                float[] e = RefStore.flipTta(this) ? app.recog.embedTta(aligned) : app.recog.embed(aligned);
                long ms = (System.nanoTime() - t0) / 1000000L;
                if (ms == 0) ms = 1;
                RefStore.emb = e;
                RefStore.genMs = ms;
                RefStore.thumb = Bitmap.createScaledBitmap(aligned, 112, 112, true);
                RefStore.label = f.numFaces > 1 ? ("照片含 " + f.numFaces + " 张脸，用最靠前的") : "";
                RefStore.modelTag = App.I.modelId;
                // 参照照片质量提示：太小/太糊/侧脸都会让后续比对系统性偏低
                float boxRatio = f.box.width() / (float) f.imgW;
                float blurVal = (float) ImgUtil.laplacianVariance(aligned);
                float yawAbs = Math.abs(f.yaw);
                StringBuilder q = new StringBuilder();
                boolean warn = false;
                if (boxRatio < 0.10f) { q.append("人脸偏小 ").append(String.format(Locale.US, "%.0f%%", boxRatio * 100)).append("；"); warn = true; }
                if (blurVal < 40f) { q.append(String.format(Locale.US, "清晰度偏低 %.0f；", blurVal)); warn = true; }
                if (yawAbs > 0.25f) { q.append(String.format(Locale.US, "侧脸 yaw %.2f；", yawAbs)); warn = true; }
                if (q.length() == 0) {
                    q.append(String.format(Locale.US, "质量良好（人脸占 %.0f%% · 清晰度 %.0f · yaw %.2f）",
                            boxRatio * 100, blurVal, yawAbs));
                }
                RefStore.quality = q.toString();
                RefStore.qualityWarn = warn;
                RefStore.save(this);
                pendingRef = aligned;
            } catch (Throwable t) {
                err = t.getMessage();
            }
            final String ferr = err;
            final Bitmap fb = bmp;
            ui.post(() -> {
                if (ferr != null) {
                    statusTv.setText("建模失败：" + ferr);
                    toast(ferr);
                } else {
                    statusTv.setText("建模完成 · 特征 " + (RefStore.emb == null ? 0 : RefStore.emb.length) + " 维 · " + RefStore.genMs + "ms");
                    refreshRef();
                }
            });
        }, "ref-build").start();
    }

    private Bitmap decodeUri(Uri uri, int maxSide) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(in, null, o);
        in.close();
        int sample = 1;
        int longer = Math.max(o.outWidth, o.outHeight);
        while (longer / sample > maxSide * 2) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        in = getContentResolver().openInputStream(uri);
        Bitmap bmp = BitmapFactory.decodeStream(in, null, o2);
        in.close();
        if (bmp == null) return null;
        if (Math.max(bmp.getWidth(), bmp.getHeight()) > maxSide) {
            float s = maxSide / (float) Math.max(bmp.getWidth(), bmp.getHeight());
            bmp = ImgUtil.scale(bmp, s);
        }
        return bmp;
    }

    // ---- 小工具 ----
    private TextView stepTitle(String t) {
        TextView v = text(t, 15, 0xFF9CC4FF);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0, dp(22), 0, dp(8));
        return v;
    }

    private TextView text(String t, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private LinearLayout.LayoutParams fullWidth() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(10);
        return lp;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
