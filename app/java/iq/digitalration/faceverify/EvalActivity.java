package iq.digitalration.faceverify;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * 本地评测模式：多选一批人像 → 按文件名分组算同人/异人对相似度
 * → 输出 FAR/FRR/EER 与「按目标 FAR 的建议阈值」，并可一键应用。
 *
 * 命名约定：文件名到最后一个 '_' 或 '-' 之前的部分视为同一个人，
 * 例如 iraq001_a.jpg 与 iraq001_b.jpg 是同一个人。
 */
public class EvalActivity extends Activity {

    private static final int REQ_PICK = 3001;
    private static final int MAX_IMAGES = 400;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView statusTv, reportTv;
    private Button targetBtn, applyBtn;
    private volatile float targetFar = 1e-2f;
    private Stats.Result lastResult;
    private float[] lastG, lastM;
    private volatile boolean running;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
    }

    private ScrollView buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF0E1117);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(32));
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("本地精度评测（FAR / FRR / EER）");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFFF2F5FA);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("多选一批人像照片，App 会在本地对每一张提取 ArcFace 特征，"
                + "按文件名分组统计同人/异人对相似度，算出误接受率(FAR)、误拒绝率(FRR)、等错误率(EER)，"
                + "并按你选的目标 FAR 给出建议阈值。\n\n"
                + "命名约定：文件名到最后一个 _ 或 - 之前的部分 = 同一个人。\n"
                + "例：iraq001_a.jpg、iraq001_b.jpg 是同一个人；iraq002_a.jpg 是另一个人。\n\n"
                + "建议：≥ 30 人、每人 2~3 张，覆盖不同光照/角度/年龄差。");
        desc.setTextSize(12.5f);
        desc.setTextColor(0xFF9AA6B8);
        desc.setPadding(0, dp(10), 0, dp(14));
        root.addView(desc);

        Button pick = new Button(this);
        pick.setText("选择照片（可多选）");
        pick.setAllCaps(false);
        pick.setOnClickListener(v -> {
            if (running) { toast("正在计算，请稍等"); return; }
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try {
                startActivityForResult(i, REQ_PICK);
            } catch (Exception e) {
                toast("无法打开相册：" + e.getMessage());
            }
        });
        root.addView(pick, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        targetBtn = new Button(this);
        targetBtn.setText("目标 FAR：1e-2（门禁级）");
        targetBtn.setAllCaps(false);
        targetBtn.setOnClickListener(v -> cycleFar());
        root.addView(targetBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        statusTv = new TextView(this);
        statusTv.setText("尚未选择照片");
        statusTv.setTextSize(12);
        statusTv.setTextColor(0xFF7FB2FF);
        statusTv.setPadding(0, dp(12), 0, dp(8));
        root.addView(statusTv);

        applyBtn = new Button(this);
        applyBtn.setText("应用建议阈值到核验");
        applyBtn.setAllCaps(false);
        applyBtn.setEnabled(false);
        applyBtn.setOnClickListener(v -> applyThreshold());
        root.addView(applyBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        Button copy = new Button(this);
        copy.setText("复制报告");
        copy.setAllCaps(false);
        copy.setOnClickListener(v -> copyReport());
        root.addView(copy, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        reportTv = new TextView(this);
        reportTv.setTextSize(11);
        reportTv.setTextColor(0xFFCBD5E1);
        reportTv.setTypeface(Typeface.MONOSPACE);
        reportTv.setTextIsSelectable(true);
        reportTv.setPadding(0, dp(14), 0, 0);
        root.addView(reportTv);
        return sv;
    }

    private void cycleFar() {
        if (targetFar >= 1e-1f) {
            targetFar = 1e-2f;
            targetBtn.setText("目标 FAR：1e-2（门禁级）");
        } else if (targetFar >= 1e-2f) {
            targetFar = 1e-3f;
            targetBtn.setText("目标 FAR：1e-3（核身级，更严）");
        } else {
            targetFar = 1e-1f;
            targetBtn.setText("目标 FAR：1e-1（体验优先，较松）");
        }
        if (lastG != null && lastM != null) {
            lastResult = Stats.compute(lastG, lastM, targetFar);
        }
        if (lastResult != null) render();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData cd = data.getClipData();
        if (cd != null) {
            for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) { toast("没有选到照片"); return; }
        if (uris.size() > MAX_IMAGES) {
            uris = uris.subList(0, MAX_IMAGES);
        }
        runEval(uris);
    }

    private void runEval(final List<Uri> uris) {
        App app = App.I;
        if (app.face == null || app.recog == null) { toast("模型还没加载好"); return; }
        running = true;
        applyBtn.setEnabled(false);
        setStatus("准备中…");
        reportTv.setText("");

        final List<Uri> list = uris;
        new Thread(() -> {
            Map<String, List<float[]>> byId = new LinkedHashMap<>();
            long t0 = System.currentTimeMillis();
            int skipped = 0;
            for (int i = 0; i < list.size(); i++) {
                final int idx = i + 1;
                final int total = list.size();
                Uri u = list.get(i);
                String name = ImgUtil.displayName(this, u);
                long per = i > 0 ? (System.currentTimeMillis() - t0) / i : 0;
                setStatus(String.format(Locale.US, "处理中 %d/%d  %s%s",
                        idx, total, name,
                        per > 0 ? String.format(Locale.US, "  (预计还需 %ds)", (total - i) * per / 1000) : ""));
                try {
                    Bitmap bmp = ImgUtil.decodeUri(this, u, 1200);
                    if (bmp == null) { skipped++; continue; }
                    FaceEngine.Result f = App.I.face.detect(bmp);
                    if (!f.found) { skipped++; continue; }
                    Bitmap aligned = Align.warp(bmp, f.l5);
                    if (aligned == null) { skipped++; continue; }
                    float[] e = App.I.recog.embed(aligned);
                    String id = identityOf(name);
                    byId.computeIfAbsent(id, k -> new ArrayList<>()).add(e);
                } catch (Throwable t) {
                    skipped++;
                }
            }
            finishEval(byId, skipped, System.currentTimeMillis() - t0);
        }, "eval").start();
    }

    private void finishEval(Map<String, List<float[]>> byId, int skipped, long ms) {
        List<float[]> gen = new ArrayList<>();
        List<float[]> imp = new ArrayList<>();
        int people = 0, photos = 0;
        for (List<float[]> v : byId.values()) {
            people++;
            photos += v.size();
        }
        // 同人对
        for (List<float[]> v : byId.values()) {
            for (int i = 0; i < v.size(); i++)
                for (int j = i + 1; j < v.size(); j++)
                    gen.add(new float[]{RecogEngine.cosine(v.get(i), v.get(j))});
        }
        // 异人对（过多时抽样）
        List<String> ids = new ArrayList<>(byId.keySet());
        long totalImp = 0;
        for (int i = 0; i < ids.size(); i++)
            for (int j = i + 1; j < ids.size(); j++)
                totalImp += (long) byId.get(ids.get(i)).size() * byId.get(ids.get(j)).size();
        Random rnd = new Random(42);
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                List<float[]> a = byId.get(ids.get(i)), b = byId.get(ids.get(j));
                for (float[] x : a) {
                    for (float[] y : b) {
                        if (totalImp > 60000 && rnd.nextDouble() > 60000.0 / totalImp) continue;
                        imp.add(new float[]{RecogEngine.cosine(x, y)});
                    }
                }
            }
        }
        float[] g = new float[gen.size()];
        float[] m = new float[imp.size()];
        for (int i = 0; i < g.length; i++) g[i] = gen.get(i)[0];
        for (int i = 0; i < m.length; i++) m[i] = imp.get(i)[0];

        final Stats.Result r = Stats.compute(g, m, targetFar);
        lastG = g;
        lastM = m;
        final int fPeople = people, fPhotos = photos, fSkipped = skipped;
        final long fMs = ms;
        final long fTotalImp = totalImp;
        ui.post(() -> {
            running = false;
            lastResult = r;
            if (r.nGenuine == 0 || r.nImpostor == 0) {
                reportTv.setText(String.format(Locale.US,
                        "照片 %d 张 · 身份 %d 个 · 跳过 %d 张\n\n"
                                + "样本不足：需要至少 2 个身份、且其中一个人有 ≥2 张照片。\n"
                                + "同人对 %d，异人对 %d。",
                        fPhotos, fPeople, fSkipped, r.nGenuine, r.nImpostor));
                applyBtn.setEnabled(false);
                setStatus("样本不足");
                return;
            }
            applyBtn.setEnabled(true);
            setStatus(String.format(Locale.US, "完成：%d 张 / %d 人 / 耗时 %.1fs",
                    fPhotos, fPeople, fMs / 1000.0));
            render();
            saveReport(r, fPeople, fPhotos, fSkipped, fMs, fTotalImp);
        });
    }

    private void render() {
        Stats.Result r = lastResult;
        if (r == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "同人对 %d · 异人对 %d%n", r.nGenuine, r.nImpostor));
        sb.append(String.format(Locale.US, "同人相似度  均值 %.3f  下限 %.3f%n",
                r.meanGenuine, r.minGenuine));
        sb.append(String.format(Locale.US, "异人相似度  均值 %.3f  上限 %.3f%n",
                r.meanImpostor, r.maxImpostor));
        sb.append("\n");
        sb.append(String.format(Locale.US, "EER = %5.2f%%  @ 阈值 %.3f%n",
                r.eer * 100, r.eerThreshold));
        sb.append(String.format(Locale.US, "目标 FAR %s → 建议阈值 %.3f%n",
                farLabel(targetFar), r.thresholdAtFar));
        sb.append(String.format(Locale.US, "   该点 FRR %.2f%%  TAR %.2f%%  FAR %.4f%n",
                r.frrAtThreshold * 100, r.tarAtFar * 100, r.farAtThreshold));
        sb.append("\n").append(r.table);
        sb.append("\n注：阈值 = 余弦相似度，越大越严；当前核验用 ")
                .append(String.format(Locale.US, "%.2f", RefStore.threshold(this))).append("。");
        reportTv.setText(sb.toString());
    }

    private static String farLabel(float f) {
        if (f >= 1e-1f) return "1e-1";
        if (f >= 1e-2f) return "1e-2";
        return "1e-3";
    }

    private String identityOf(String fileName) {
        String stem = fileName;
        int dot = stem.lastIndexOf('.');
        if (dot > 0) stem = stem.substring(0, dot);
        int a = stem.lastIndexOf('_');
        int b = stem.lastIndexOf('-');
        int cut = Math.max(a, b);
        if (cut > 0 && stem.length() - cut - 1 <= 6) {
            return stem.substring(0, cut);
        }
        return stem;
    }

    private void applyThreshold() {
        Stats.Result r = lastResult;
        if (r == null) return;
        float v = Math.round(r.thresholdAtFar * 100f) / 100f;
        v = Math.max(0.20f, Math.min(0.80f, v));
        RefStore.setThreshold(this, v);
        toast(String.format(Locale.US, "已应用阈值 %.2f（目标 FAR %s）", v, farLabel(targetFar)));
        render();
    }

    private void saveReport(Stats.Result r, int people, int photos, int skipped, long ms, long totalImp) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            File f = new File(dir, "eval_report.txt");
            FileOutputStream fo = new FileOutputStream(f);
            String head = String.format(Locale.US,
                    "离线人脸核验 · 本地评测报告\n"
                            + "时间: %s\n设备: %s %s  sdk=%d\n模型: ArcFace w600k_r50 (512d)\n"
                            + "照片 %d 张 / 身份 %d 个 / 跳过 %d 张 / 耗时 %.1fs\n"
                            + "异人对总数 %d（报告内为抽样或全部）\n\n",
                    new java.util.Date(), android.os.Build.MANUFACTURER, android.os.Build.MODEL,
                    android.os.Build.VERSION.SDK_INT, photos, people, skipped, ms / 1000.0, totalImp);
            fo.write(head.getBytes("UTF-8"));
            fo.write(reportTv.getText().toString().getBytes("UTF-8"));
            fo.close();
            setStatus("完成 · 报告已存 " + f.getAbsolutePath());
        } catch (Throwable t) {
            // 保存失败不影响主流程
        }
    }

    private void copyReport() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("eval", reportTv.getText()));
                toast("报告已复制到剪贴板");
            }
        } catch (Throwable ignored) {}
    }

    private void setStatus(final String s) {
        ui.post(() -> statusTv.setText(s));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
