package iq.digitalration.faceverify.ocr;

import java.util.ArrayList;
import java.util.List;

/**
 * 不需要模型的 OCR 单元测试：标签匹配、户号位置纠错、MRZ 校验位反解、多帧投票。
 * 跑法：java -cp build/jvmtest iq.digitalration.faceverify.ocr.OcrUnitTest
 */
public class OcrUnitTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  OK   " + name + (detail == null ? "" : "  (" + detail + ")"));
        } else {
            fail++;
            System.out.println("  FAIL " + name + (detail == null ? "" : "  -> " + detail));
        }
    }

    static OcrCore.Line line(String text, float cx, float cy, float conf) {
        return new OcrCore.Line(text, conf, cx, cy, text.length() * 12f, 24f, 0f,
                new int[]{(int) cx - 100, (int) cy - 12, (int) cx + 100, (int) cy + 12},
                OcrCore.hasArabic(text));
    }

    public static void main(String[] a) {
        System.out.println("=== OCR 后处理单元测试 ===");

        // 1. 标签归一化：ئ/ى→ي、ێ→ي、ە→ه、ژ→ز、ک→ك
        check("标签(阿拉伯文)归一", CardFields.normArabic("الرقم العائلي").contains("عايلي"),
                CardFields.normArabic("الرقم العائلي"));
        check("标签(库尔德文)归一", CardFields.normArabic("ژمارەی خێزانی").contains("خيزاني"),
                CardFields.normArabic("ژمارەی خێزانی"));

        // 2. 认标签定位户号：卡上另有一个 18 位数字，必须取标签旁边那个
        List<OcrCore.Line> lines = new ArrayList<>();
        lines.add(line("100256996574360150", 400, 100, 0.55f));      // 别处的长数字（低置信度）
        lines.add(line("الرقم العائلي", 800, 160, 0.92f));            // 标签
        lines.add(line("1002E6996574360150", 400, 160, 0.90f));       // 标签同一行的值
        CardFields f = CardFields.parse(lines);
        check("标签锚定取同行编号", "1002E6996574360150".equals(f.familyNo), f.familyNo);
        check("标签原文保留", f.familyLabel != null && f.familyLabel.contains("عائلي"), f.familyLabel);

        // 3. 库尔德文标签 + 编号在下一行
        List<OcrCore.Line> l2 = new ArrayList<>();
        l2.add(line("زمارهى خىزانى", 800, 200, 0.88f));
        l2.add(line("1002E6996574360150", 400, 240, 0.95f));
        CardFields f2 = CardFields.parse(l2);
        check("库尔德文标签锚定", "1002E6996574360150".equals(f2.familyNo), f2.familyNo);

        // 4. 位置感知纠错：字母混进数字位
        check("I->1 修正", "1002E6996574360150".equals(CardFields.normalizeFamily("1002E6996574360I50", 1)),
                CardFields.normalizeFamily("1002E6996574360I50", 1));
        check("O->0 修正", "1002E6996574360150".equals(CardFields.normalizeFamily("1OO2E699657436015O", 3)),
                CardFields.normalizeFamily("1OO2E699657436015O", 3));
        check("代价过高拒绝", CardFields.normalizeFamily("1OO2E699657436O15O", 4) == null, "cost=4");

        // 4b. 真实样例：尾部带字母的户号不能被"数字化"（M 曾被害成 4）
        check("尾部字母 M 保留", "1002L00M9770006805".equals(CardFields.normalizeFamily("1002L00M9770006805", 0)),
                CardFields.normalizeFamily("1002L00M9770006805", 0));
        check("前 4 位形近字母仍纠正", "1002L00M9770006805".equals(CardFields.normalizeFamily("1OO2L00M9770006805", 2)),
                CardFields.normalizeFamily("1OO2L00M9770006805", 2));
        // 低位把 M 读成 4：算法无法凭空知道，但要把它列进"候选"
        List<OcrCore.Line> l3 = new ArrayList<>();
        String bad = "1002L0049770006805";
        float[] cf = new float[bad.length()];
        String[] al = new String[bad.length()];
        java.util.Arrays.fill(cf, 0.99f);
        java.util.Arrays.fill(al, "");
        cf[7] = 0.62f;
        al[7] = "M";
        l3.add(new OcrCore.Line(bad, 0.9f, 400, 160, 300, 28, 0f,
                new int[]{250, 146, 550, 174}, false, cf, al));
        CardFields f3 = CardFields.parse(l3);
        check("歧义位进候选", f3.familyAlternatives.contains("1002L00M9770006805"),
                f3.familyAlternatives.toString());

        // 5. MRZ 校验位反解（910512 的校验位是 6）
        check("日期 2->Z 反解", "910512".equals(CardFields.fixToCheckDigit("91051Z", "6")),
                CardFields.fixToCheckDigit("91051Z", "6"));
        check("日期 1->I 反解", "910512".equals(CardFields.fixToCheckDigit("9I0512", "6")),
                CardFields.fixToCheckDigit("9I0512", "6"));
        check("字母数字证件号不被整体数字化", "AE0644356".equals(
                CardFields.fixToCheckDigit("AE0644356", String.valueOf(CardFields.checkDigit("AE0644356")))),
                CardFields.fixToCheckDigit("AE0644356", String.valueOf(CardFields.checkDigit("AE0644356"))));

        // 6. 多帧投票：两帧各错一位，投票后回到正确值
        List<CardFields> frames = new ArrayList<>();
        CardFields a1 = new CardFields();
        a1.familyNo = "1002E6996574360150";
        a1.familyConf = 0.9f;
        CardFields a2 = new CardFields();
        a2.familyNo = "1002E6996574360I50";   // 错一位（I vs 1）
        a2.familyConf = 0.8f;
        frames.add(a1);
        frames.add(a2);
        CardFields voted = CardFields.vote(frames);
        check("两帧投票修单字符错", "1002E6996574360150".equals(voted.familyNo), voted.familyNo);

        // ---- 证件正面 CAN（Card Access Number）：右下角 6 位数字
        List<OcrCore.Line> canLines = new ArrayList<>();
        canLines.add(line("20220228", 500, 120, 0.90f));   // 8 位日期：长度不对
        canLines.add(line("123456", 250, 120, 0.98f));     // 左上 6 位：可能是别的编号
        canLines.add(line("654321", 900, 800, 0.85f));     // 右下 6 位：应该赢
        canLines.add(line("ABC123", 600, 700, 0.99f));     // 非纯数字
        List<String> cans = CardFields.canCandidates(canLines, 1000, 900);
        check("CAN 优先右下角", !cans.isEmpty() && "654321".equals(cans.get(0)), String.valueOf(cans));

        List<OcrCore.Line> arLines = new ArrayList<>();
        arLines.add(line("١٢٣٤٥٦", 900, 800, 0.90f));      // 阿拉伯-印度数字要归一
        List<String> arc = CardFields.canCandidates(arLines, 1000, 900);
        check("CAN 阿拉伯数字归一", !arc.isEmpty() && "123456".equals(arc.get(0)), String.valueOf(arc));

        List<OcrCore.Line> badCan = new ArrayList<>();
        badCan.add(line("12345", 900, 800, 0.90f));
        badCan.add(line("1234567", 900, 800, 0.90f));
        check("CAN 长度过滤", CardFields.canCandidates(badCan, 1000, 900).isEmpty(), "");

        // 8. 真实卡背（伊拉克国民卡：户号 1002L00M9770006805，MRZ 三行）
        List<OcrCore.Line> real = new ArrayList<>();
        real.add(line("الرقم العائلي / ژمارەی خێزانی", 800, 100, 0.92f));
        real.add(line("1002L00M9770006805", 300, 100, 0.98f));
        real.add(line("IDIRQAC67648836197662080255<<<", 500, 300, 0.99f));
        real.add(line("7602067M2705088IRQ<<<<<<<<<<<5", 500, 330, 0.99f));
        real.add(line("ALEQYLY<<ELY<<<<<<<<<<<<<<<<", 450, 360, 0.95f));
        CardFields rc = CardFields.parse(real);
        check("真实卡：户号", "1002L00M9770006805".equals(rc.familyNo), rc.familyNo);
        check("真实卡：证件号", "AC6764883".equals(rc.docNumber), rc.docNumber);
        check("真实卡：出生/有效期", "760206".equals(rc.dob) && "270508".equals(rc.expiry),
                rc.dob + "/" + rc.expiry);
        check("真实卡：校验位四项全过",
                rc.checkDocOk && rc.checkDobOk && rc.checkExpiryOk && rc.checkCompositeOk, rc.summary());
        check("真实卡：MRZi", "AC6764883676020672705088".equals(rc.mrzInfo()), rc.mrzInfo());

        System.out.println();
        System.out.println(fail == 0 ? "OCR UNIT TESTS PASSED (" + pass + ")" : "OCR UNIT TESTS FAILED: " + fail + "/" + (pass + fail));
        if (fail != 0) System.exit(1);
    }
}
