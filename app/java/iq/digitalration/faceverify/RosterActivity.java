package iq.digitalration.faceverify;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 离线花名册查看（TV-09 的"看得见"的那一半）。
 *
 * <p>证明的不是"文件存在"，而是**真能查**：解不开就报错、查不到就是空、成员挂在户下。
 * 搜索走内存索引（与门禁扫码同一条路径），所以这里的毫秒数就是现场的查询耗时量级。
 */
public class RosterActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<JSONObject> rows = new ArrayList<>();

    private TextView statusTv, countTv;
    private EditText queryEt;
    private ListView list;
    private ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        load("");
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0E1117);
        int pad = dp(16);
        root.setPadding(pad, dp(22), pad, dp(16));

        TextView title = text("离线花名册（本机）", 21, 0xFFF2F5FA);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = text("AES-256-GCM 落盘 · 内存索引查询 · 换版留 .bak", 12, 0xFF8A96A8);
        sub.setPadding(0, dp(4), 0, dp(10));
        root.addView(sub);

        statusTv = text("读取中…", 12, 0xFF7FB2FF);
        statusTv.setBackground(round(0xFF151A24, 10));
        statusTv.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(statusTv);

        // ---- 搜索 ----
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, dp(6));
        queryEt = new EditText(this);
        queryEt.setHint("输入户号（可只输前几位）");
        queryEt.setHintTextColor(0xFF6E7A8C);
        queryEt.setTextColor(0xFFE6EDF7);
        queryEt.setTextSize(15);
        queryEt.setSingleLine(true);
        queryEt.setInputType(InputType.TYPE_CLASS_TEXT);
        row.addView(queryEt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button search = new Button(this);
        search.setText("查询");
        search.setAllCaps(false);
        search.setTextSize(13);
        search.setOnClickListener(v -> load(queryEt.getText().toString()));
        row.addView(search);
        root.addView(row);

        countTv = text("", 11, 0xFF8A96A8);
        countTv.setPadding(0, 0, 0, dp(6));
        root.addView(countTv);

        list = new ListView(this);
        list.setBackgroundColor(0xFF151A24);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(adapter);
        list.setOnItemClickListener((AdapterView<?> parent, View v, int pos, long id) -> showDetail(pos));
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- 底部操作 ----
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(0, dp(8), 0, 0);

        Button seed = new Button(this);
        seed.setText("导入测试花名册（1,500 户）");
        seed.setAllCaps(false);
        seed.setTextSize(12);
        seed.setOnClickListener(v -> seedRoster());
        bottom.addView(seed, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button clear = new Button(this);
        clear.setText("清空本机花名册");
        clear.setAllCaps(false);
        clear.setTextSize(12);
        clear.setOnClickListener(v -> confirmClear());
        bottom.addView(clear, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bottom);

        return root;
    }

    // ------------------------------------------------------------ 数据

    private void load(final String q) {
        countTv.setText("查询中…");
        new Thread(() -> {
            JSONObject status = RosterStore.status(this);
            JSONObject br = RosterStore.browse(this, q, 50);
            ui.post(() -> {
                statusTv.setText(describe(status));
                rows.clear();
                List<String> lines = new ArrayList<>();
                JSONArray items = br.optJSONArray("items");
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject o = items.optJSONObject(i);
                        if (o == null) continue;
                        rows.add(o);
                        lines.add(String.format(Locale.US, "%s · %s · %d 人 · 成员 %d",
                                o.optString("familyNo"), trim(o.optString("name"), 14),
                                o.optInt("members"), o.optInt("memberCount")));
                    }
                }
                adapter.clear();
                adapter.addAll(lines);
                adapter.notifyDataSetChanged();
                if (br.has("error")) {
                    countTv.setText("读取失败：" + br.optString("error"));
                } else {
                    countTv.setText(String.format(Locale.US,
                            "共 %d 户，匹配 %d 户，显示 %d 行（点一行看明细）",
                            br.optInt("total"), br.optInt("matched"), br.optInt("shown")));
                }
            });
        }, "roster-browse").start();
    }

    private String describe(JSONObject s) {
        if (s == null) return "无数据";
        if (s.has("error")) return "读取失败：" + s.optString("error");
        return String.format(Locale.US,
                "版本 %d · %d 户 · 来源 %s · 主库 %s · 备份 %s · 校验和 %s",
                s.optInt("version"), s.optInt("count"), s.optString("source"),
                bytes(s.optLong("mainBytes")), s.optBoolean("hasBackup") ? "有" : "无",
                s.optBoolean("checksumOk") ? "通过" : "未通过");
    }

    private void showDetail(int pos) {
        if (pos < 0 || pos >= rows.size()) return;
        final String fam = rows.get(pos).optString("familyNo");
        new Thread(() -> {
            JSONObject r = RosterStore.query(this, fam);
            JSONObject one = r.optJSONObject("row");
            ui.post(() -> {
                if (one == null) {
                    toast("这户已经不在花名册里了");
                    return;
                }
                StringBuilder sb = new StringBuilder();
                sb.append("户号：").append(one.optString("familyNo")).append('\n');
                sb.append("户主：").append(one.optString("name")).append('\n');
                sb.append("人数：").append(one.optInt("members")).append('\n');
                sb.append("中心：").append(one.optString("center")).append('\n');
                sb.append("地址：").append(one.optString("address")).append('\n');
                sb.append("电话：").append(one.optString("phone")).append('\n');
                JSONArray ml = one.optJSONArray("memberList");
                if (ml != null && ml.length() > 0) {
                    sb.append("\n成员（").append(ml.length()).append("）：\n");
                    for (int i = 0; i < ml.length(); i++) {
                        JSONObject m = ml.optJSONObject(i);
                        if (m == null) continue;
                        sb.append("  ").append(m.optString("no")).append("  ")
                                .append(m.optString("name")).append('\n');
                    }
                } else {
                    sb.append("\n成员：本户没有成员行\n");
                }
                new AlertDialog.Builder(this)
                        .setTitle("花名册明细")
                        .setMessage(sb.toString())
                        .setPositiveButton("关闭", null)
                        .show();
            });
        }, "roster-one").start();
    }

    private void seedRoster() {
        statusTv.setText("正在生成并导入 1,500 户测试花名册…");
        new Thread(() -> {
            String err = null;
            long ms = 0;
            try {
                long t0 = System.currentTimeMillis();
                java.io.File csv = RosterStore.generateCsv(this, 1500);
                RosterStore.resetCache();
                JSONObject r = RosterStore.importCsv(this, csv);
                ms = System.currentTimeMillis() - t0;
                if (r.has("error")) err = r.optString("error");
            } catch (Throwable t) {
                err = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            final String fe = err;
            final long fms = ms;
            ui.post(() -> {
                if (fe != null) {
                    statusTv.setText("导入失败：" + fe);
                    return;
                }
                toast("导入完成 " + fms + " ms");
                load("");
            });
        }, "roster-seed").start();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("清空本机花名册")
                .setMessage("删除 roster.bin / roster.bak 与内存索引（不动结果库、不删测试 CSV）。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (d, w) -> {
                    RosterStore.clearRoster(this);
                    load("");
                })
                .show();
    }

    // ------------------------------------------------------------ 小工具

    private static String trim(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static String bytes(long b) {
        if (b <= 0) return "0 B";
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024.0);
        return String.format(Locale.US, "%.1f MB", b / 1024.0 / 1024.0);
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
