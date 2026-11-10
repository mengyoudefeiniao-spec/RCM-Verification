package iq.digitalration.faceverify;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * 现场验证清单：把每个验证项的证据、结论、备注收集起来，导出成 ZIP 发回。
 *
 * <p>入口：主界面「④ 现场验证清单」。数据全部来自本机（{@link ChecklistStore}、
 * {@link DeviceInfo}、{@link Checklist}），不依赖网络与账号。
 */
public class ChecklistActivity extends Activity {

    private static final int BG = 0xFF0E1117;
    private static final int CARD = 0xFF151A24;
    private static final int TEXT = 0xFFF2F5FA;
    private static final int MUTED = 0xFF9AA6B8;
    private static final int DIM = 0xFF6E7A8C;
    private static final int ACCENT = 0xFF7FB2FF;

    private LinearLayout listBox;
    private TextView statusTv, progressTv, metaTv, pathTv;
    private JSONObject device;
    private List<Checklist.Row> rows;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (rows != null) refresh();
    }

    // ------------------------------------------------------------ UI

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, dp(20), pad, dp(40));
        sv.addView(root);

        TextView back = text("← 返回主界面", 13, MUTED);
        back.setPadding(0, 0, 0, dp(8));
        back.setOnClickListener(v -> finish());
        root.addView(back);

        TextView title = text("现场验证清单", 22, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = text("每项跑一次、选结论、导出 ZIP 发回；判据由我方离线复核", 12, MUTED);
        sub.setPadding(0, dp(6), 0, dp(12));
        root.addView(sub);

        statusTv = text("", 12, 0xFF7FB2FF);
        statusTv.setBackground(round(CARD, 10));
        statusTv.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(statusTv);

        progressTv = text("", 12, TEXT);
        progressTv.setPadding(0, dp(10), 0, 0);
        root.addView(progressTv);

        metaTv = text("", 12, MUTED);
        metaTv.setPadding(0, dp(4), 0, 0);
        root.addView(metaTv);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(12), 0, 0);
        root.addView(btns);
        btns.addView(smallBtn("填写执行人/现场", v -> editMeta()), weight());
        btns.addView(smallBtn("刷新设备信息", v -> refresh()), weight());

        LinearLayout btns2 = new LinearLayout(this);
        btns2.setOrientation(LinearLayout.HORIZONTAL);
        btns2.setPadding(0, dp(8), 0, 0);
        root.addView(btns2);
        btns2.addView(smallBtn("预览报告", v -> previewReport()), weight());
        btns2.addView(smallBtn("清空记录", v -> confirmClear()), weight());

        Button export = new Button(this);
        export.setText("导出验证报告（ZIP 并分享）");
        export.setTextSize(16);
        export.setAllCaps(false);
        export.setOnClickListener(v -> exportReport());
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        ep.topMargin = dp(10);
        root.addView(export, ep);

        pathTv = text("", 10, DIM);
        pathTv.setPadding(0, dp(6), 0, 0);
        root.addView(pathTv);

        root.addView(stepTitle("验证项"));
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox);

        TextView foot = text("说明：以上证据只存本机（files/checklist），导出后请通过 WhatsApp / 邮件把 ZIP 发回；"
                + "报告不含模型与密码，芯片结论只证明数据与 SOD 自洽，未锚定 CSCA。", 11, DIM);
        foot.setPadding(0, dp(18), 0, 0);
        root.addView(foot);

        return sv;
    }

    private void refresh() {
        device = DeviceInfo.collect(this);
        rows = Checklist.rows(this, device);

        JSONObject a = device.optJSONObject("app");
        JSONObject d = device.optJSONObject("device");
        JSONObject rt = device.optJSONObject("runtime");
        JSONObject nfc = device.optJSONObject("nfc");
        StringBuilder sb = new StringBuilder();
        if (d != null) sb.append(d.optString("manufacturer")).append(' ').append(d.optString("model"));
        if (d != null) sb.append(" · Android ").append(d.optString("release")).append(" (API ").append(d.optInt("sdk")).append(')');
        sb.append("\n应用 ").append(a == null ? "—" : a.optString("versionName"))
                .append(" · 模型 ").append(rt == null ? "—" : rt.optString("modelId"))
                .append(" · 阈值 ").append(rt == null ? "—" : String.format(Locale.US, "%.2f", rt.optDouble("threshold", 0)));
        if (nfc != null && !nfc.optBoolean("supported")) sb.append("\n⚠ 本机无 NFC：NFC-01 只能用不含芯片的卡或换机验证");
        statusTv.setText(sb.toString());

        int done = Checklist.done(rows);
        progressTv.setText(String.format(Locale.US, "已出结论 %d/%d", done, rows.size()));
        progressTv.setTextColor(done == rows.size() ? 0xFF5FD4A0 : TEXT);

        String tester = ChecklistStore.meta(this, "tester");
        String site = ChecklistStore.meta(this, "site");
        metaTv.setText("执行人：" + (tester.isEmpty() ? "未填写" : tester)
                + "   现场：" + (site.isEmpty() ? "未填写" : site));

        pathTv.setText("导出目录：" + ChecklistStore.exportDir(this).getAbsolutePath());

        listBox.removeAllViews();
        for (Checklist.Row r : rows) listBox.addView(card(r));
    }

    private View card(Checklist.Row r) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(CARD, 12));
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        box.setLayoutParams(lp);
        box.setOnClickListener(v -> openDetail(r));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(head);

        TextView id = text(r.c.id + " · " + r.c.tv, 11, ACCENT);
        id.setTypeface(Typeface.MONOSPACE);
        head.addView(id, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chip = text(Checklist.label(r.verdict), 11, verdictColor(r.verdict));
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setBackground(round(verdictBg(r.verdict), 9));
        chip.setPadding(dp(8), dp(3), dp(8), dp(3));
        head.addView(chip);

        TextView name = text(r.c.title, 15, TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setPadding(0, dp(6), 0, 0);
        box.addView(name);

        int show = Math.min(3, r.ev.size());
        for (int i = 0; i < show; i++) {
            TextView ev = text("· " + r.ev.get(i), 11, MUTED);
            ev.setPadding(0, dp(3), 0, 0);
            box.addView(ev);
        }
        TextView more = text(r.ev.size() > show ? "共 " + r.ev.size() + " 条证据，点击查看 / 选结论" : "点击查看 / 选结论",
                10, r.ev.size() > show ? ACCENT : DIM);
        more.setPadding(0, dp(6), 0, 0);
        box.addView(more);

        if (!r.note.isEmpty()) {
            TextView nt = text("备注：" + r.note, 11, 0xFFFFD08A);
            nt.setPadding(0, dp(6), 0, 0);
            box.addView(nt);
        }
        return box;
    }

    // ------------------------------------------------------------ 详情 / 结论

    private void openDetail(Checklist.Row r) {
        final String[] chosen = {r.verdict};

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        body.setPadding(pad, dp(8), pad, dp(8));

        body.addView(label("判据", r.c.criteria));
        body.addView(label("做法", r.c.howto));
        if (!r.hint.isEmpty()) body.addView(label("自动判断", r.hint));

        body.addView(sectionLabel("证据（" + r.ev.size() + " 条）"));
        if (r.ev.isEmpty()) {
            body.addView(value("（无记录）"));
        } else {
            for (String e : r.ev) body.addView(value("· " + e));
        }

        body.addView(sectionLabel("结论"));
        LinearLayout vrow1 = new LinearLayout(this);
        vrow1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout vrow2 = new LinearLayout(this);
        vrow2.setOrientation(LinearLayout.HORIZONTAL);
        String[][] defs = {
                {Checklist.PASS, "可行"}, {Checklist.PARTIAL, "有条件"}, {Checklist.FAIL, "不可行"},
                {Checklist.NA, "不适用"}, {Checklist.NONE, "未测"},
        };
        final TextView[] chips = new TextView[defs.length];
        for (int i = 0; i < defs.length; i++) {
            final String v = defs[i][0];
            final TextView chip = text(defs[i][1], 13, verdictColor(v));
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(6), dp(9), dp(6), dp(9));
            chip.setBackground(round(verdictBg(v), 10));
            chip.setOnClickListener(x -> {
                chosen[0] = v;
                for (int k = 0; k < defs.length; k++) {
                    boolean on = defs[k][0].equals(v);
                    chips[k].setBackground(round(on ? verdictBg(v) : 0xFF1B2230, 10));
                    chips[k].setTextColor(on ? verdictColor(v) : MUTED);
                    chips[k].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                }
            });
            chips[i] = chip;
            LinearLayout row = i < 3 ? vrow1 : vrow2;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            row.addView(chip, lp);
        }
        body.addView(vrow1);
        body.addView(vrow2);
        // 初始化选中态
        for (int k = 0; k < defs.length; k++) {
            boolean on = defs[k][0].equals(chosen[0]) || (chosen[0].isEmpty() && defs[k][0].isEmpty());
            chips[k].setBackground(round(on ? verdictBg(chosen[0]) : 0xFF1B2230, 10));
            chips[k].setTextColor(on ? verdictColor(chosen[0]) : MUTED);
            chips[k].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }

        body.addView(sectionLabel("备注（给复核人的一句话：样本量、光照、异常）"));
        EditText note = new EditText(this);
        note.setHint("例：同人 3 次全过，强光下 1 次失败");
        note.setText(r.note);
        note.setTextSize(13);
        note.setTextColor(TEXT);
        note.setHintTextColor(DIM);
        note.setBackground(round(0xFF1B2230, 10));
        note.setPadding(dp(10), dp(8), dp(10), dp(8));
        note.setMinLines(2);
        note.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        body.addView(note);

        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        sv.setPadding(0, 0, 0, 0);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(r.c.id + " " + r.c.title)
                .setView(sv)
                .setPositiveButton("保存", (d, w) -> {
                    ChecklistStore.setVerdict(this, r.c.id, chosen[0], note.getText().toString().trim());
                    refresh();
                    toast("已保存：" + Checklist.label(chosen[0]));
                })
                .setNegativeButton("取消", null)
                .setNeutralButton("清除", (d, w) -> {
                    ChecklistStore.setVerdict(this, r.c.id, Checklist.NONE, "");
                    refresh();
                })
                .create();
        dlg.show();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(round(0xFF10151F, 16));
        }
    }

    private void editMeta() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        body.setPadding(pad, dp(8), pad, dp(8));

        body.addView(sectionLabel("执行人"));
        EditText tester = input(ChecklistStore.meta(this, "tester"), "姓名 / 工号");
        body.addView(tester);
        body.addView(sectionLabel("现场地点"));
        EditText site = input(ChecklistStore.meta(this, "site"), "例：巴格达 卡尔赫 第 3 分发中心");
        body.addView(site);
        body.addView(sectionLabel("现场备注（网络、光照、机型问题等）"));
        EditText notes = input(ChecklistStore.meta(this, "notes"), "可留空");
        notes.setMinLines(2);
        body.addView(notes);

        new AlertDialog.Builder(this)
                .setTitle("执行人 / 现场")
                .setView(body)
                .setPositiveButton("保存", (d, w) -> {
                    ChecklistStore.setMeta(this,
                            tester.getText().toString().trim(),
                            site.getText().toString().trim(),
                            notes.getText().toString().trim());
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private EditText input(String value, String hint) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setTextSize(14);
        e.setTextColor(TEXT);
        e.setHintTextColor(DIM);
        e.setBackground(round(0xFF1B2230, 10));
        e.setPadding(dp(10), dp(8), dp(10), dp(8));
        e.setSingleLine(true);
        return e;
    }

    // ------------------------------------------------------------ 导出

    private void previewReport() {
        JSONObject dev = DeviceInfo.collect(this);
        List<Checklist.Row> rs = Checklist.rows(this, dev);
        String md = Checklist.markdown(this, dev, rs);

        TextView tv = new TextView(this);
        tv.setText(md);
        tv.setTextSize(11);
        tv.setTextColor(0xFFDCE5F2);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        int pad = dp(14);
        tv.setPadding(pad, pad, pad, pad);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("报告预览（" + Checklist.done(rs) + "/" + rs.size() + " 已出结论）")
                .setView(sv)
                .setPositiveButton("去导出", (d, w) -> exportReport())
                .setNegativeButton("关闭", null)
                .create();
        dlg.show();
        if (dlg.getWindow() != null) dlg.getWindow().setBackgroundDrawable(round(0xFF10151F, 16));
    }

    private void exportReport() {
        JSONObject dev;
        List<Checklist.Row> rs;
        String md, json, csv;
        try {
            dev = DeviceInfo.collect(this);
            rs = Checklist.rows(this, dev);
            md = Checklist.markdown(this, dev, rs);
            json = Checklist.json(this, dev, rs);
            csv = Checklist.csv(rs);
        } catch (Throwable t) {
            toast("报告生成失败：" + t);
            return;
        }
        File zip = ChecklistStore.exportZip(this, md, json, csv, dev);
        if (zip == null || !zip.isFile() || zip.length() == 0) {
            toast("导出失败：无法写入 " + ChecklistStore.exportDir(this));
            return;
        }
        String msg = "已生成 " + zip.getName()
                + "\n" + DeviceInfo.humanBytes(zip.length())
                + "\n\n" + zip.getAbsolutePath()
                + "\n\n点「分享」选 WhatsApp / 邮件发回给我们。";
        new AlertDialog.Builder(this)
                .setTitle("报告已导出")
                .setMessage(msg)
                .setPositiveButton("分享", (d, w) -> share(zip))
                .setNeutralButton("复制路径", (d, w) -> copyPath(zip))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void share(File zip) {
        try {
            Uri uri = ReportProvider.uriFor(this, zip);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/zip");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "技术验证报告 " + zip.getName());
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "发送验证报告"));
        } catch (Throwable t) {
            toast("没有可用的分享应用，请用文件管理器取：" + zip.getAbsolutePath());
        }
    }

    private void copyPath(File zip) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("path", zip.getAbsolutePath()));
            toast("路径已复制");
        } catch (Throwable t) {
            toast(zip.getAbsolutePath());
        }
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("清空本机记录")
                .setMessage("会删除已采集的核验/OCR/芯片结果与全部结论（执行人/现场保留）。导出过的 ZIP 不受影响。")
                .setPositiveButton("清空", (d, w) -> {
                    ChecklistStore.clearRecords(this);
                    refresh();
                    toast("已清空");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------ 小工具

    private int verdictColor(String v) {
        if (Checklist.PASS.equals(v)) return 0xFF5FD4A0;
        if (Checklist.PARTIAL.equals(v)) return 0xFFFFB020;
        if (Checklist.FAIL.equals(v)) return 0xFFFF6B6B;
        if (Checklist.NA.equals(v)) return MUTED;
        return DIM;
    }

    private int verdictBg(String v) {
        if (Checklist.PASS.equals(v)) return 0xFF173D2E;
        if (Checklist.PARTIAL.equals(v)) return 0xFF3A2E12;
        if (Checklist.FAIL.equals(v)) return 0xFF3A1C1C;
        if (Checklist.NA.equals(v)) return 0xFF232A35;
        return 0xFF232A35;
    }

    private TextView sectionLabel(String s) {
        TextView tv = text(s, 12, ACCENT);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(14), 0, dp(4));
        return tv;
    }

    private TextView label(String title, String value) {
        TextView tv = text(title + "：" + value, 12, 0xFFCDD6E3);
        tv.setPadding(0, dp(4), 0, 0);
        return tv;
    }

    private TextView value(String s) {
        TextView tv = text(s, 12, MUTED);
        tv.setPadding(0, dp(2), 0, 0);
        return tv;
    }

    private TextView stepTitle(String s) {
        TextView tv = text(s, 15, 0xFF9CC4FF);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(22), 0, dp(4));
        return tv;
    }

    private TextView text(String s, int sp, int color) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        return tv;
    }

    private Button smallBtn(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }

    private GradientDrawable round(int color, int rad) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(rad));
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
