package iq.digitalration.faceverify;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.Locale;

/**
 * 60 秒动态码页（TV-08 的可视化部分）。
 *
 * <p>三个东西一起显示，互相印证：
 * <ul>
 *   <li><b>8 位数字码</b>：HMAC-SHA256(Keystore 密钥, 设备号|窗口号) 截出来的；</li>
 *   <li><b>二维码</b>：内容就是 {@code RCM1|设备号|窗口号|码}，用自研 {@link QrCode} 画，
 *       不联网、不依赖任何扫码库，拿另一台手机扫就能读到同样的四个字段；</li>
 *   <li><b>倒计时</b>：60 秒一个窗口，归零自动换码。</li>
 * </ul>
 *
 * <p>下面的「校验」框走的是与终端相同的 {@link DynamicCode#verify}：
 * 同一个码第二次校验会被判重放（这一层就是离线防截图复用的全部依据）。
 */
public class CodeActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView codeTv, metaTv, resultTv;
    private ImageView qrIv;
    private ProgressBar bar;
    private EditText input;
    private long shownWindow = -1;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh(false);
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        refresh(true);
        ui.postDelayed(tick, 500);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(tick);
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF0E1117);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, dp(24), pad, dp(32));
        sv.addView(root);

        TextView title = text("60 秒动态码（离线）", 22, 0xFFF2F5FA);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = text("码在手机本地算，不联网；二维码内容 = RCM1|设备号|窗口号|码", 12, 0xFF8A96A8);
        sub.setPadding(0, dp(6), 0, dp(14));
        root.addView(sub);

        codeTv = text("········", 44, 0xFF6BE39A);
        codeTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeTv.setGravity(Gravity.CENTER);
        codeTv.setBackground(round(0xFF151A24, 12));
        codeTv.setPadding(0, dp(18), 0, dp(18));
        root.addView(codeTv, fullWidth());

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax((int) DynamicCode.PERIOD_MS);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(10));
        bp.topMargin = dp(10);
        root.addView(bar, bp);

        metaTv = text("", 12, 0xFF7FB2FF);
        metaTv.setPadding(0, dp(6), 0, dp(12));
        root.addView(metaTv);

        qrIv = new ImageView(this);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(260), dp(260));
        ip.gravity = Gravity.CENTER_HORIZONTAL;
        qrIv.setLayoutParams(ip);
        qrIv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrIv.setBackgroundColor(0xFFFFFFFF);
        root.addView(qrIv);

        TextView qrHint = text("用另一台手机扫这个码 → 应读到同一设备号、同一窗口号、同一个 8 位码", 11, 0xFF8A96A8);
        qrHint.setGravity(Gravity.CENTER_HORIZONTAL);
        qrHint.setPadding(0, dp(8), 0, dp(16));
        root.addView(qrHint);

        Button rotate = new Button(this);
        rotate.setText("立即作废旧码（删 HMAC 密钥）");
        rotate.setTextSize(13);
        rotate.setAllCaps(false);
        rotate.setOnClickListener(v -> {
            DynamicCode.rotate();
            shownWindow = -1;
            refresh(true);
            toast("已删除本机 HMAC 密钥，之前所有码全部失效");
        });
        root.addView(rotate, fullWidth());

        // ---- 校验 ----
        LinearLayout.LayoutParams st = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        st.topMargin = dp(22);
        TextView vt = text("校验一个码（模拟门禁端）", 15, 0xFF9CC4FF);
        vt.setTypeface(Typeface.DEFAULT_BOLD);
        vt.setLayoutParams(st);
        root.addView(vt);

        TextView vh = text("同一个码校验第二次会被判「重放」——这就是离线防截图复用的那一层", 11, 0xFF8A96A8);
        vh.setPadding(0, dp(4), 0, dp(6));
        root.addView(vh);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        input = new EditText(this);
        input.setHint("输入 8 位数字");
        input.setTextColor(0xFFE6EDF7);
        input.setHintTextColor(0xFF6E7A8C);
        input.setTextSize(16);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        row.addView(input, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button verify = new Button(this);
        verify.setText("校验");
        verify.setTextSize(13);
        verify.setAllCaps(false);
        verify.setOnClickListener(v -> doVerify(input.getText().toString().trim()));
        row.addView(verify);
        root.addView(row);

        Button fill = new Button(this);
        fill.setText("把当前码填进校验框");
        fill.setTextSize(13);
        fill.setAllCaps(false);
        fill.setOnClickListener(v -> input.setText(DynamicCode.current(this)));
        root.addView(fill, fullWidth());

        resultTv = text("", 13, 0xFFCDD6E3);
        resultTv.setTextIsSelectable(true);
        resultTv.setPadding(0, dp(10), 0, 0);
        root.addView(resultTv);

        return sv;
    }

    // ------------------------------------------------------------ 刷新

    private void refresh(boolean force) {
        long now = System.currentTimeMillis();
        long win = DynamicCode.window(now);
        String code = DynamicCode.codeForWindow(Receipt.deviceId(this), win);
        if (code == null || code.isEmpty()) {
            codeTv.setText("——");
            metaTv.setText("还没有 HMAC 密钥，点下面「立即作废旧码」后再看（会重新生成）");
            return;
        }
        codeTv.setText(code);
        bar.setProgress((int) DynamicCode.remainMs(now));
        metaTv.setText(String.format(Locale.US,
                "窗口 %d · 剩余 %.1fs · 容错 ±%d 窗口 · 设备 %s",
                win, DynamicCode.remainMs(now) / 1000.0, DynamicCode.TOLERANCE_WINDOWS,
                shortId(Receipt.deviceId(this))));

        if (force || win != shownWindow) {
            shownWindow = win;
            String payload = DynamicCode.payload(this, now);
            boolean[][] m = QrCode.encode(payload);
            if (m == null) {
                qrIv.setImageBitmap(null);
                resultTv.setText("二维码生成失败（payload 超长：" + payload.length() + " 字节）");
            } else {
                qrIv.setImageBitmap(render(m, dp(260)));
            }
        }
    }

    /** 矩阵 → 位图（4 模块静区，整数倍缩放，避免扫码时出现灰边）。 */
    private static Bitmap render(boolean[][] m, int targetPx) {
        int n = m.length;
        int quiet = 4;
        int scale = Math.max(1, targetPx / (n + quiet * 2));
        int px = (n + quiet * 2) * scale;
        int[] argb = new int[px * px];
        int black = 0xFF000000, white = 0xFFFFFFFF;
        for (int y = 0; y < px; y++) {
            int my = y / scale - quiet;
            for (int x = 0; x < px; x++) {
                int mx = x / scale - quiet;
                boolean dark = mx >= 0 && my >= 0 && mx < n && my < n && m[my][mx];
                argb[y * px + x] = dark ? black : white;
            }
        }
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        bmp.setPixels(argb, 0, px, 0, 0, px, px);
        return bmp;
    }

    private void doVerify(String code) {
        JSONObject r = DynamicCode.verify(this, code, System.currentTimeMillis(), null);
        StringBuilder sb = new StringBuilder();
        sb.append(r.optBoolean("ok") ? "✓ 校验通过" : "✗ 未通过").append('\n');
        if (r.has("reason")) sb.append(r.optString("reason")).append('\n');
        if (r.has("windowMatched")) {
            sb.append("命中窗口 ").append(r.optLong("windowMatched"))
                    .append(" · 时钟偏差 ").append(r.optLong("skewMs")).append("ms\n");
        }
        if (r.optBoolean("replay")) sb.append("这是重放：该码已被使用过\n");
        if (r.has("verifyMs")) sb.append("耗时 ").append(r.optLong("verifyMs")).append("ms\n");
        resultTv.setTextColor(r.optBoolean("ok") ? 0xFF6BE39A : 0xFFFF9A9A);
        resultTv.setText(sb.toString());

        JSONObject rec = new JSONObject();
        try {
            rec.put("code", code);
            rec.put("ok", r.optBoolean("ok"));
            rec.put("result", r);
        } catch (Throwable ignored) {
        }
        LabStore.put(this, "codeCheck", rec);
    }

    private static String shortId(String s) {
        if (s == null) return "";
        return s.length() <= 8 ? s : s.substring(0, 8) + "…";
    }

    // ------------------------------------------------------------ 小工具

    private TextView text(String t, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
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
