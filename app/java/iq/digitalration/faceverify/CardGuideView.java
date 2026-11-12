package iq.digitalration.faceverify;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 证件核验取景参考线（全部虚线，只做提示，不参与识别）：
 *
 * <ol>
 *   <li><b>整卡外框</b>（蓝）：按 ID-1 比例 85.6 : 54 画一个横向矩形——卡放进来就等于"卡占满画面"，
 *       MRZ 每字符才有足够像素（现场日志：卡只占一小块时 MRZ 直接漏检）；</li>
 *   <li><b>家庭编号区</b>（黄）：卡面最后一行数据（الرقم العائلي / ژمارەی خێزانی），值印在**左侧**；</li>
 *   <li><b>MRZ 三行区</b>（绿）：卡底那三条机读行——BAC 密码的唯一来源，也是对照 App 的识别区域。</li>
 * </ol>
 *
 * <p>比例按真实卡背照片（微信图片_20261029170402_11_52）量出来的版面：
 * 家庭编号行 ~44%~53%（值在左侧 11%~47%）、全息条 ~56%~62%、MRZ 三行 ~71%~93%
 * （行距按卡高的比例量，与拍摄缩放无关）。只要求"区域落在框内"，
 * 不要求卡严丝合缝——OCR 会自己定方向、自己找行。
 */
public final class CardGuideView extends View {

    /** ISO/IEC 7810 ID-1：85.6mm × 54mm。 */
    private static final float CARD_RATIO = 85.6f / 54f;

    // —— 卡片内部区域（相对整卡宽高的比例），按真实卡背照片量得 ——
    /** 家庭编号（最后一行数据）横向范围：值印在左侧，右侧是阿拉伯/库尔德文标签。 */
    private static final float FAMILY_L = 0.11f, FAMILY_R = 0.47f;
    private static final float FAMILY_T = 0.44f, FAMILY_B = 0.53f;
    /** MRZ 三行：卡底整条（右端留一点白，行尾都是 '<' 填充）。 */
    private static final float MRZ_L = 0.03f, MRZ_R = 0.95f;
    private static final float MRZ_T = 0.71f, MRZ_B = 0.93f;

    private final Paint pCard = dashed(0xCC9CC4FF);
    private final Paint pFamily = dashed(0xCCFFD479);
    private final Paint pMrz = dashed(0xCC5FD4A0);

    private final RectF cardRect = new RectF();
    private final RectF familyRect = new RectF();
    private final RectF mrzRect = new RectF();

    public CardGuideView(Context c) {
        super(c);
        setClickable(false);
        setFocusable(false);
    }

    private Paint dashed(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(2));
        p.setColor(color);
        // 虚线段约 8dp、间隔 6dp：太密看着像实线，太疏又容易断在反光处看不清
        p.setPathEffect(new DashPathEffect(new float[]{dp(8), dp(6)}, 0));
        return p;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 整卡外框：在可用区域里按 ID-1 比例居中（横着放）
        float inner = dp(10);
        float cw = w - inner * 2, ch = h - inner * 2;
        if (cw / ch > CARD_RATIO) cw = ch * CARD_RATIO;
        else ch = cw / CARD_RATIO;
        float left = (w - cw) / 2f, top = (h - ch) / 2f;
        cardRect.set(left, top, left + cw, top + ch);
        canvas.drawRoundRect(cardRect, dp(10), dp(10), pCard);

        // 框只画线，不带文字：卡面本身信息密度高，压字反而看不清
        familyRect.set(left + cw * FAMILY_L, top + ch * FAMILY_T,
                left + cw * FAMILY_R, top + ch * FAMILY_B);
        canvas.drawRect(familyRect, pFamily);

        mrzRect.set(left + cw * MRZ_L, top + ch * MRZ_T,
                left + cw * MRZ_R, top + ch * MRZ_B);
        canvas.drawRect(mrzRect, pMrz);
    }
}
