package iq.digitalration.faceverify;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 技术验证实验室：把各条技术验证（TV-xx）的自测**在这台手机上真跑一遍**，结果落到本机，
 * 再导出一份能发回的报告。
 *
 * <p>设计原则（与整个工程一致）：不填表、不手打结论。每一项都点一下按钮跑真实代码，
 * 看到的数字就是本机这台机器的数字；跑不了的（真卡、真卡面、帧率）在报告里明确写「未验证」。
 *
 * <p>结果存 {@link LabStore}（{@code files/lab/lab.json}），导出的 ZIP 里有
 * {@code report.md} / {@code report.json} / {@code lab.json} / {@code device.json}，
 * 走 {@link ReportProvider} 交给系统分享。
 */
public class LabActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, TextView> verdictViews = new HashMap<>();
    private final Map<String, TextView> detailViews = new HashMap<>();
    private final Map<String, Button> runViews = new HashMap<>();

    private TextView headTv;
    private TextView logTv;
    private Button exportBtn;
    private LinearLayout extraBox;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        refresh();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        // TV-05：API 24-27 走锁屏确认，结果从这里回来
        BioAuth.onActivityResult(this, req, res, this::onBioResult);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 前置相机人脸登录在 FaceLoginActivity 里跑完并把结果写进 lab.json，回来要刷新卡片
        refresh();
    }

    // ------------------------------------------------------------ 界面

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF0E1117);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, dp(24), pad, dp(32));
        sv.addView(root);

        TextView title = text("技术验证实验室", 22, 0xFFF2F5FA);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = text("每一项都在本机真跑一遍：不联网、不填表，数字来自这台手机", 13, 0xFF8A96A8);
        sub.setPadding(0, dp(6), 0, dp(14));
        root.addView(sub);

        headTv = text("", 12, 0xFF7FB2FF);
        headTv.setBackground(round(0xFF151A24, 10));
        headTv.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(headTv);

        TextView secTitle = text("条目自测", 15, 0xFF9CC4FF);
        secTitle.setTypeface(Typeface.DEFAULT_BOLD);
        secTitle.setPadding(0, dp(20), 0, dp(6));
        root.addView(secTitle);

        for (LabReport.Item it : LabReport.items()) root.addView(itemCard(it));

        TextView othTitle = text("其它界面自动登记（不在这里运行）", 15, 0xFF9CC4FF);
        othTitle.setTypeface(Typeface.DEFAULT_BOLD);
        othTitle.setPadding(0, dp(20), 0, dp(6));
        root.addView(othTitle);

        extraBox = new LinearLayout(this);
        extraBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(extraBox);

        TextView expTitle = text("证据导出", 15, 0xFF9CC4FF);
        expTitle.setTypeface(Typeface.DEFAULT_BOLD);
        expTitle.setPadding(0, dp(20), 0, dp(6));
        root.addView(expTitle);

        exportBtn = new Button(this);
        exportBtn.setText("导出验证报告 ZIP（report.md / report.json / 设备快照）");
        exportBtn.setTextSize(14);
        exportBtn.setAllCaps(false);
        exportBtn.setOnClickListener(v -> export());
        root.addView(exportBtn);

        Button shareLast = new Button(this);
        shareLast.setText("分享上一次导出的报告");
        shareLast.setTextSize(13);
        shareLast.setAllCaps(false);
        shareLast.setOnClickListener(v -> {
            java.util.List<File> fs = LabStore.listExports(this);
            if (fs.isEmpty()) { toast("还没有导出过报告"); return; }
            share(fs.get(0));
        });
        root.addView(shareLast);

        Button clear = new Button(this);
        clear.setText("清空本机自测记录");
        clear.setTextSize(13);
        clear.setAllCaps(false);
        clear.setOnClickListener(v -> confirmClear());
        root.addView(clear);

        TextView logTitle = text("最近一次原始结果", 13, 0xFF6E7A8C);
        logTitle.setPadding(0, dp(18), 0, dp(4));
        root.addView(logTitle);

        logTv = text("（还没跑过任何一项）", 11, 0xFF8A96A8);
        logTv.setTextIsSelectable(true);
        logTv.setBackground(round(0xFF151A24, 10));
        logTv.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(logTv);

        return sv;
    }

    private View itemCard(final LabReport.Item it) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(0xFF151A24, 12));
        int p = dp(12);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        card.setLayoutParams(lp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = text(it.tv + "  " + it.name, 14, 0xFFE6EDF7);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView verdict = text("未跑", 13, 0xFF9AA6B8);
        row.addView(verdict);
        verdictViews.put(it.key, verdict);
        card.addView(row);

        TextView hint = text(it.hint, 11, 0xFF8A96A8);
        hint.setPadding(0, dp(4), 0, 0);
        card.addView(hint);

        TextView detail = text("", 11, 0xFF7FB2FF);
        detail.setPadding(0, dp(4), 0, 0);
        card.addView(detail);
        detailViews.put(it.key, detail);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(6), 0, 0);
        // 第二行按钮（生物识别有三条路，一行放不下）
        LinearLayout btns2 = new LinearLayout(this);
        btns2.setOrientation(LinearLayout.HORIZONTAL);
        btns2.setPadding(0, dp(4), 0, 0);

        Button run = new Button(this);
        run.setText("运行自测");
        run.setTextSize(13);
        run.setAllCaps(false);
        run.setOnClickListener(v -> runItem(it));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btns.addView(run, bp);
        runViews.put(it.key, run);

        if ("bio".equals(it.key)) {
            // 生物识别要多一步：先真做一次认证，才有"令牌能签发"的证据
            Button auth = new Button(this);
            auth.setText("强生物识别");
            auth.setTextSize(13);
            auth.setAllCaps(false);
            auth.setOnClickListener(v -> bioAuth());
            btns.addView(auth, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            // v2.8：人脸登录改走 App 内前置相机（系统弹窗在录了指纹的机器上只会给指纹）
            Button face = new Button(this);
            face.setText("人脸登录（前置）");
            face.setTextSize(13);
            face.setAllCaps(false);
            face.setOnClickListener(v -> bioFaceLogin());
            btns2.addView(face, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            // 系统认证：指纹 / 人脸 / 锁屏密码任一通过都算「系统认证通过」，随后签发凭据。
            // 注意模态由系统决定（有指纹的机器上 OEM 一律先给指纹），这是平台限制，不是实现问题。
            Button sys = new Button(this);
            sys.setText("系统认证（指纹/人脸）");
            sys.setTextSize(13);
            sys.setAllCaps(false);
            sys.setOnClickListener(v -> bioAuthSystem());
            btns2.addView(sys, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }

        if ("code".equals(it.key)) {
            Button qr = new Button(this);
            qr.setText("看动态码 / 二维码");
            qr.setTextSize(13);
            qr.setAllCaps(false);
            qr.setOnClickListener(v -> startActivity(new Intent(this, CodeActivity.class)));
            btns.addView(qr, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }

        if ("roster".equals(it.key)) {
            Button browse = new Button(this);
            browse.setText("查看花名册");
            browse.setTextSize(13);
            browse.setAllCaps(false);
            browse.setOnClickListener(v -> startActivity(new Intent(this, RosterActivity.class)));
            btns.addView(browse, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        card.addView(btns);
        if (btns2.getChildCount() > 0) card.addView(btns2);
        return card;
    }

    // ------------------------------------------------------------ 执行

    private void runItem(final LabReport.Item it) {
        setBusy(true, "正在运行 " + it.tv + " …（花名册/队列会写本机 lab 目录）");
        new Thread(() -> {
            JSONObject r;
            try {
                r = exec(it);
            } catch (Throwable t) {
                r = error(t);
            }
            LabStore.put(this, it.key, r);
            final JSONObject fr = r;
            ui.post(() -> {
                setBusy(false, null);
                showRaw(it.tv + " " + it.name, fr);
                refresh();
                toast(LabReport.verdict(fr));
            });
        }, "lab-" + it.key).start();
    }

    private JSONObject exec(LabReport.Item it) throws Exception {
        switch (it.key) {
            case "env":
                return DeviceInfo.collect(this);
            case "trust":
                return IntegrityProbe.collect(this);
            case "receipt":
                return Receipt.tamperTest(this);
            case "code":
                return DynamicCode.selfTest(this, 30);
            case "roster":
                // 会覆盖本机 lab 目录里的测试花名册（1,500 户），不影响 RefStore / 阈值
                return RosterStore.runFullTest(this, 1500);
            case "queue":
                return OfflineQueue.runFullTest(this, 300);
            case "bio":
                return bioProbe();
            default:
                JSONObject o = new JSONObject();
                o.put("ok", false);
                o.put("reason", "没有对应的自测入口");
                return o;
        }
    }

    /** TV-05：只做"能不能用"的探测 + 未认证必须签不出名；认证结果由 {@link #bioAuth()} 单独写。 */
    private JSONObject bioProbe() {
        JSONObject r = new JSONObject();
        try {
            r.put("capabilities", BioAuth.capabilities(this));
            JSONObject enf = BioAuth.enforcementProbe();
            r.put("enforcement", enf);
            JSONObject prev = LabStore.get(this, "bio");
            JSONObject auth = prev == null ? null : prev.optJSONObject("auth");
            JSONObject weak = prev == null ? null : prev.optJSONObject("weakAuth");
            JSONObject face = prev == null ? null : prev.optJSONObject("faceAuth");
            JSONObject sys = prev == null ? null : prev.optJSONObject("systemAuth");
            if (auth != null) r.put("auth", auth);
            else {
                r.put("authNote", "还没做过强生物识别登录：点「强生物识别」跑一次，才有令牌签发证据");
            }
            if (weak != null) r.put("weakAuth", weak);
            // 前置相机人脸登录（v2.8）是独立入口的结果，重跑自测时必须一并保留
            if (face != null) r.put("faceAuth", face);
            else {
                r.put("faceAuthNote", "还没做过人脸登录：点「人脸登录（前置）」跑一次，"
                        + "这份记录里才会有活体与 1:1 比对证据");
            }
            if (sys != null) r.put("systemAuth", sys);
            else {
                r.put("systemAuthNote", "还没做过系统认证：点「系统认证（指纹/人脸）」跑一次，"
                        + "这份记录里才会有「系统认证通过」的凭据");
            }
            boolean authOk = auth != null && auth.optBoolean("ok");
            r.put("ok", authOk && enf.optBoolean("blocked"));
            if (!authOk) r.put("reason", "尚无通过认证的登录令牌（先点「生物识别登录」）");
            else if (!enf.optBoolean("blocked")) r.put("reason", "未认证也能签名，密钥没有绑定用户认证");
        } catch (Throwable t) {
            return error(t);
        }
        return r;
    }

    private void bioAuth() {
        setBusy(true, "等待生物识别…");
        BioAuth.authenticate(this, "lab-operator", this::onBioResult);
    }

    /**
     * 人脸登录：走 App 内前置相机（活体挑战 + 端侧 1:1 比对 + 令牌签发）。
     *
     * <p>为什么不用系统弹窗：{@code BiometricPrompt} 不能指定"只要人脸"，录了指纹的机器一律先弹指纹
     * （v2.7 真机就是这个现象：按「人脸登录」让按手指）。系统弱模态保留为「系统弹窗（对照）」。
     */
    private void bioFaceLogin() {
        Intent i = new Intent(this, FaceLoginActivity.class);
        i.putExtra(FaceLoginActivity.EXTRA_USER, "lab-operator");
        startActivity(i);
    }

    /** 系统认证：指纹 / 2D 或 3D 人脸 / 锁屏密码，任一通过即"系统认证通过"，随后签发凭据。 */
    private void bioAuthSystem() {
        setBusy(true, "等待系统认证（指纹 / 人脸 / 密码）…");
        BioAuth.authenticateSystem(this, "lab-operator", this::onSystemResult);
    }

    /** 系统认证结果单独记一条：与强通道（绑定密钥）、前置人脸（活体+比对）分开列，三条证据各说各的。 */
    private void onSystemResult(JSONObject auth) {
        JSONObject merged = LabStore.get(this, "bio");
        if (merged == null) merged = new JSONObject();
        try {
            merged.put("systemAuth", auth);
        } catch (Throwable ignored) {
        }
        LabStore.put(this, "bio", merged);
        final JSONObject fm = merged;
        ui.post(() -> {
            setBusy(false, null);
            showRaw("TV-05 系统认证（指纹 / 人脸 / 密码）", fm);
            refresh();
            toast(auth.optBoolean("ok")
                    ? "系统认证通过：" + auth.optString("mode")
                    : "系统认证未通过：" + auth.optString("reason", "未知原因"));
        });
    }

    private void onBioResult(JSONObject auth) {
        JSONObject merged = LabStore.get(this, "bio");
        if (merged == null) merged = new JSONObject();
        try {
            merged.put("auth", auth);
            JSONObject enfObj = merged.optJSONObject("enforcement");
            boolean enf = enfObj != null && enfObj.optBoolean("blocked");
            merged.put("ok", auth.optBoolean("ok") && enf);
            if (!auth.optBoolean("ok")) {
                merged.put("reason", auth.optString("reason"));
            } else if (enfObj == null) {
                merged.put("reason", "已拿到可验签的登录令牌；再点一次「运行自测」补上"
                        + "「未认证时必须签不出名」的强制探测，结论才能判通过");
            } else if (!enf) {
                merged.put("reason", "未认证也能签名（异常：密钥没有绑定用户认证）");
            } else {
                merged.remove("reason");
            }
        } catch (Throwable ignored) {
        }
        LabStore.put(this, "bio", merged);
        final JSONObject fm = merged;
        ui.post(() -> {
            setBusy(false, null);
            showRaw("TV-05 本机生物识别登录", fm);
            refresh();
            toast(LabReport.verdict(fm));
        });
    }

    // ------------------------------------------------------------ 刷新 / 展示

    private void refresh() {
        JSONObject all = LabStore.all(this);
        int judged = 0, passed = 0;
        for (LabReport.Item it : LabReport.items()) {
            JSONObject r = all.optJSONObject(it.key);
            String v = LabReport.verdict(r);
            TextView vt = verdictViews.get(it.key);
            if (vt != null) {
                vt.setText(v);
                vt.setTextColor(LabReport.verdictColor(v));
            }
            TextView dt = detailViews.get(it.key);
            if (dt != null) dt.setText(LabReport.summary(it, r));
            if (it.judgeable) {
                judged++;
                if ("通过".equals(v)) passed++;
            }
        }
        headTv.setText(String.format(java.util.Locale.US,
                "判据类条目 %d/%d 通过 · 结果只存本机（files/lab/lab.json）· 导出后可直接发给甲方/研发",
                passed, judged));

        extraBox.removeAllViews();
        String[][] extra = {
                {"face", "TV-03", "1:1 人脸比对与活体"},
                {"ocr", "TV-01", "卡背家庭编号与 MRZ"},
                {"nfc", "TV-02 / TV-29", "读芯片与被动认证"},
                {"spoof", "TV-28", "被动反欺诈（如实记录缺口）"}
        };
        for (String[] e : extra) {
            JSONObject r = all.optJSONObject(e[0]);
            String v = LabReport.verdict(r);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(8), 0, 0);
            TextView n = text(e[1] + "  " + e[2], 12, 0xFFCDD6E3);
            row.addView(n, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView vt = text(v, 12, LabReport.verdictColor(v));
            row.addView(vt);
            extraBox.addView(row);
            if (r != null) {
                String s = r.has("similarity") ? ("相似度 " + r.optDouble("similarity"))
                        : r.has("familyNo") ? ("户号 " + r.optString("familyNo"))
                        : r.has("access") ? (r.optString("access") + " · APDU " + r.optInt("apduCount"))
                        : "";
                if (!s.isEmpty()) {
                    TextView d = text(s, 11, 0xFF8A96A8);
                    extraBox.addView(d);
                }
            }
        }
    }

    private void showRaw(String title, JSONObject r) {
        logTv.setText(title + "\n\n" + LabReport.pretty(r));
    }

    private void setBusy(boolean busy, String msg) {
        if (exportBtn != null) exportBtn.setEnabled(!busy);
        for (Button b : runViews.values()) b.setEnabled(!busy);
        if (msg != null) headTv.setText(msg);
        else refresh();
    }

    // ------------------------------------------------------------ 导出

    private void export() {
        setBusy(true, "正在生成报告与 ZIP…");
        new Thread(() -> {
            File zip = null;
            String err = null;
            try {
                JSONObject results = LabStore.all(this);
                JSONObject dev = DeviceInfo.collect(this);
                String md = LabReport.md(this, dev, results);
                String js = LabReport.pretty(LabReport.json(this, dev, results));
                zip = LabStore.exportZip(this, md, js, dev);
                if (zip == null || !zip.isFile()) throw new IllegalStateException("ZIP 写入失败");
            } catch (Throwable t) {
                err = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            final File fz = zip;
            final String fe = err;
            ui.post(() -> {
                setBusy(false, null);
                if (fe != null) {
                    showRaw("导出失败", null);
                    logTv.setText("导出失败：" + fe);
                    toast("导出失败：" + fe);
                    return;
                }
                logTv.setText("已导出：\n" + fz.getAbsolutePath() + "\n\n报告内容：\n"
                        + LabReport.md(LabActivity.this, DeviceInfo.collect(LabActivity.this),
                        LabStore.all(LabActivity.this)));
                share(fz);
            });
        }, "lab-export").start();
    }

    private void share(File zip) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/zip");
            i.putExtra(Intent.EXTRA_STREAM, ReportProvider.uriFor(this, zip));
            i.putExtra(Intent.EXTRA_SUBJECT, "技术验证报告 " + zip.getName());
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "分享验证报告"));
        } catch (Throwable t) {
            toast("没有可用的分享目标：" + t.getMessage() + "\n文件在：" + zip.getAbsolutePath());
        }
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("清空本机自测记录")
                .setMessage("只删除 lab.json 里的自测结果，不动参照照片、阈值和花名册测试文件。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (d, w) -> {
                    LabStore.clear(this);
                    logTv.setText("（已清空，还没跑过任何一项）");
                    refresh();
                })
                .show();
    }

    // ------------------------------------------------------------ 小工具

    private static JSONObject error(Throwable t) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        } catch (Throwable ignored) {
        }
        return o;
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

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
