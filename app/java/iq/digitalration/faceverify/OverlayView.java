package iq.digitalration.faceverify;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** 叠在实时画面上的引导层：椭圆框、动作指令、进度点、状态行。 */
public class OverlayView extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);

    private String instruction = "请把脸放到框内";
    private String hint = "";
    private String info = "";
    private int done = 0, total = 0;

    public OverlayView(Context c) {
        super(c);
        guide.setStyle(Paint.Style.STROKE);
        guide.setStrokeWidth(dp(3));
        guide.setColor(0xAA9CC4FF);
        tp.setColor(Color.WHITE);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTypeface(Typeface.DEFAULT_BOLD);
    }

    public void setState(String instruction, String hint, int done, int total) {
        this.instruction = instruction;
        this.hint = hint == null ? "" : hint;
        this.done = done;
        this.total = total;
        postInvalidateOnAnimation();
    }

    public void setInfo(String info) {
        this.info = info == null ? "" : info;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        int w = getWidth(), h = getHeight();

        float gw = w * 0.68f;
        float gh = gw * 1.28f;
        float cx = w / 2f;
        float cy = h * 0.45f;
        RectF r = new RectF(cx - gw / 2, cy - gh / 2, cx + gw / 2, cy + gh / 2);
        guide.setColor(done > 0 ? 0xCC5FD4A0 : 0xAA9CC4FF);
        cv.drawOval(r, guide);

        tp.setTextSize(dp(22));
        tp.setColor(Color.WHITE);
        cv.drawText(instruction, w / 2f, h * 0.13f, tp);

        if (!hint.isEmpty()) {
            tp.setTextSize(dp(13));
            tp.setColor(0xFFBFD2EC);
            cv.drawText(hint, w / 2f, h * 0.13f + dp(26), tp);
        }

        if (total > 0) {
            float dotY = r.bottom + dp(24);
            float gap = dp(15);
            float startX = cx - (total - 1) * gap / 2f;
            for (int i = 0; i < total; i++) {
                p.setColor(i < done ? 0xFF5FD4A0 : 0x66FFFFFF);
                cv.drawCircle(startX + i * gap, dotY, dp(5), p);
            }
        }

        if (!info.isEmpty()) {
            tp.setTextAlign(Paint.Align.LEFT);
            tp.setTextSize(dp(10.5f));
            tp.setColor(0x99BFD2EC);
            cv.drawText(info, dp(12), h - dp(14), tp);
            tp.setTextAlign(Paint.Align.CENTER);
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
