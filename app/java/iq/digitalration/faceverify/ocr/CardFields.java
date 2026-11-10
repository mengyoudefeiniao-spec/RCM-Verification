package iq.digitalration.faceverify.ocr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 从 OCR 行结果里抠出结构化字段：卡背「家庭编号」+ TD1/TD3 MRZ。
 *
 * <p>家庭编号（户号）的定位有两条路，优先用第一条：
 * <ol>
 *   <li><b>认标签</b>：阿拉伯文模型读出「الرقم العائلي」或库尔德文「ژمارەی خێزانی」，
 *       标签所在行的同一行/紧邻的左（RTL 布局里值在标签左侧）就是编号；</li>
 *   <li><b>认形状</b>：18 位 = 4 数字 + 1 大写字母 + 13 数字。对候选做**位置感知纠错**
 *       （第 5 位按字母修、其余按数字修）并计代价，代价 ≤2 才算数，取代价最小/置信度最高。</li>
 * </ol>
 * 两条路都给不出结果时宁可不报，也不报一个"看着像"的号——户号错一位就等于另一家人。
 *
 * <p>MRZ：OCR 把 5 认成 S、1 认成 I 时，用**校验位反解**：枚举 0↔O、1↔I、5↔S、8↔B、2↔Z、6↔G
 * 里至多 2 处替换，找到能让 ICAO 7-3-1 校验位成立的组合。这样 18 位人像号那种"一个字符错就整张卡
 * 读不了芯片"的问题，多数能被自动纠正。
 */
public final class CardFields {

    public String familyNo;
    public float familyConf;
    /** 识别到的标签原文（用于界面显示与人工确认），如 "الرقم العائلي"、""。 */
    public String familyLabel;

    public String mrzLine1, mrzLine2;
    public String docNumber, dob, expiry, nationality, sex;
    public boolean checkDocOk, checkDobOk, checkExpiryOk, checkCompositeOk;
    public boolean mrzFound;

    /** 每行 OCR 文本（调试/展示用）。 */
    public final List<String> lines = new ArrayList<>();

    /** 户号的其它可能写法（OCR 有歧义的位置替换而来），给人工核对用，最多 3 个。 */
    public final List<String> familyAlternatives = new ArrayList<>();

    /**
     * 只保留**字形真的像数字**的字母映射；M/C/F/H/J/K/N/R/U/V/W/X/Y 这些不像数字的字母
     * 一律映射成 '?'，表示"这里是真字母，别硬改成数字"。
     *
     * <p>这条规则是踩坑换来的：老版本把 M 也映射成 4，结果真卡号 {@code 1002L00M9770006805}
     * 被改成了 {@code 1002L0049770006805}。卡上户号的尾部是有可能带字母的（4 位数字 + 1 字母 +
     * 13 位字母数字），所以尾部字母不能一律数字化。
     */
    private static final char[] CONFUSABLE_TO_DIGIT = {
            // A  B  C   D  E  F   G  H  I  J  K  L  M  N  O  P  Q  R  S  T  U  V  W  X  Y  Z
            '4', '8', '?', '0', '3', '?', '6', '?', '1', '?', '?', '1', '?', '?', '0', '9', '0', '?', '5', '7',
            '?', '?', '?', '?', '?', '2'
    };

    /** 常被 OCR 当成数字的字母（0/O、1/I、5/S…）。 */
    private static final String DIGIT_LOOKALIKE = "OQDILZEASGTBP";

    private static char toDigit(char c) {
        if (c >= '0' && c <= '9') return c;
        if (c >= 'A' && c <= 'Z') return CONFUSABLE_TO_DIGIT[c - 'A'];
        return '?';
    }

    private static char toLetter(char c) {
        if (c >= 'A' && c <= 'Z') return c;
        switch (c) {
            case '0': return 'O';
            case '1': return 'I';
            case '5': return 'S';
            case '8': return 'B';
            case '2': return 'Z';
            case '6': return 'G';
            case '3': return 'E';
            case '4': return 'A';
            case '7': return 'T';
            case '9': return 'P';
            default: return '?';
        }
    }

    public static CardFields parse(List<OcrCore.Line> src) {
        CardFields f = new CardFields();
        for (OcrCore.Line l : src) f.lines.add(l.text);
        int labelIdx = f.findFamilyLabel(src);
        f.findFamilyNo(src, labelIdx);
        f.findMrz(src);
        return f;
    }

    /**
     * 多帧投票：连拍 3 帧各识别一次，逐位取多数。
     * 单帧错一个字符很常见，三帧里同一位置错成同一个字符的概率低得多。
     */
    public static CardFields vote(List<CardFields> frames) {
        if (frames.isEmpty()) return new CardFields();
        if (frames.size() == 1) return frames.get(0);
        CardFields out = new CardFields();

        // 家庭编号：同长度里逐位投票，再按位置规则修一遍
        List<String> fams = new ArrayList<>();
        for (CardFields f : frames) if (f.familyNo != null) fams.add(f.familyNo);
        if (!fams.isEmpty()) {
            int len = 0;
            Map<Integer, Integer> lenVotes = new HashMap<>();
            for (String s : fams) lenVotes.merge(s.length(), 1, Integer::sum);
            for (Map.Entry<Integer, Integer> e : lenVotes.entrySet()) {
                if (len == 0 || e.getValue() > lenVotes.get(len)) len = e.getKey();
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) {
                Map<Character, Integer> votes = new HashMap<>();
                for (String s : fams) if (s.length() == len) votes.merge(s.charAt(i), 1, Integer::sum);
                char best = '?';
                int bn = -1;
                for (Map.Entry<Character, Integer> e : votes.entrySet()) {
                    if (e.getValue() > bn) { bn = e.getValue(); best = e.getKey(); }
                }
                sb.append(best);
            }
            String voted = sb.toString();
            float[] cfg = new float[voted.length()];
            java.util.Arrays.fill(cfg, 0.9f);
            out.familyNo = normalizeFamily(voted, cfg, new int[1]);
            if (out.familyNo == null) out.familyNo = fams.get(0);
            float c = 0;
            for (CardFields f : frames) if (out.familyNo.equals(f.familyNo)) c = Math.max(c, f.familyConf);
            out.familyConf = c;
        }
        for (CardFields f : frames) {
            if (f.familyLabel != null && !f.familyLabel.isEmpty()) { out.familyLabel = f.familyLabel; break; }
        }

        // MRZ：逐字段投票（三个字段各自独立，取出现次数最多的值）
        out.docNumber = mode(frames, f -> f.docNumber);
        out.dob = mode(frames, f -> f.dob);
        out.expiry = mode(frames, f -> f.expiry);
        CardFields mrz = null;
        for (CardFields f : frames) if (f.mrzFound) { mrz = f; break; }
        if (mrz != null) {
            out.mrzFound = true;
            out.mrzLine1 = mrz.mrzLine1;
            out.mrzLine2 = mrz.mrzLine2;
            out.nationality = mrz.nationality;
            out.sex = mrz.sex;
            out.checkDocOk = out.docNumber != null && out.docNumber.equals(mrz.docNumber) && mrz.checkDocOk;
            out.checkDobOk = mrz.checkDobOk;
            out.checkExpiryOk = mrz.checkExpiryOk;
            out.checkCompositeOk = mrz.checkCompositeOk;
        }
        return out;
    }

    private interface Getter { String get(CardFields f); }

    private static String mode(List<CardFields> fs, Getter g) {
        Map<String, Integer> votes = new HashMap<>();
        String best = null;
        int bn = 0;
        for (CardFields f : fs) {
            String v = g.get(f);
            if (v == null) continue;
            int n = votes.merge(v, 1, Integer::sum);
            if (n > bn) { bn = n; best = v; }
        }
        return best;
    }

    // ---------------------------------------------------------------- 家庭编号

    /** 找「الرقم العائلي / ژمارەی خێزانی」标签行；找不到返回 -1。 */
    private int findFamilyLabel(List<OcrCore.Line> src) {
        for (int i = 0; i < src.size(); i++) {
            String t = src.get(i).text;
            if (!OcrCore.hasArabic(t)) continue;
            String norm = normArabic(t);
            if (norm.contains("عائلي") || norm.contains("عايلي") || norm.contains("عايل")
                    || nearWord(norm, "عائلي", 2) || nearWord(norm, "خزاني", 2)
                    || norm.contains("خزان") || norm.contains("خيزان")) {
                familyLabel = stripColon(t.trim());
                return i;
            }
        }
        return -1;
    }

    /** 阿拉伯字母归一：去附加符/连字，统一 ی/ي ک/ك ە/ه。 */
    static String normArabic(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x064B && c <= 0x065F) continue;          // 变音符
            if (c == 0x0640) continue;                          // tatweel
            switch (c) {
                case 'أ': case 'إ': case 'آ': case 'ٱ': c = 'ا'; break;
                case 'ى': case 'ئ': case 'ێ': case 'ی': c = 'ي'; break;   // ی 是波斯/库尔德文耶
                case 'ک': c = 'ك'; break;
                case 'ە': case 'ھ': c = 'ه'; break;
                case 'ژ': c = 'ز'; break;
                case 'ۆ': c = 'و'; break;
                case 'ڕ': c = 'ر'; break;
                case 'ڵ': c = 'ل'; break;
                case 'ڤ': c = 'ف'; break;
                default: break;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 归一后的文本里是否有一个词与 target 的编辑距离 ≤ d（阿拉伯词形变化多，必须模糊）。 */
    private static boolean nearWord(String text, String target, int d) {
        int i = 0;
        while (i < text.length()) {
            while (i < text.length() && !isArabicLetter(text.charAt(i))) i++;
            int j = i;
            while (j < text.length() && isArabicLetter(text.charAt(j))) j++;
            if (j > i) {
                String w = text.substring(i, j);
                if (Math.abs(w.length() - target.length()) <= d && lev(w, target) <= d) return true;
            }
            i = j + 1;
        }
        return false;
    }

    private static boolean isArabicLetter(char c) {
        return (c >= 0x0620 && c <= 0x064A) || (c >= 0x066E && c <= 0x06D3);
    }

    private static int lev(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    private static String stripColon(String s) {
        String t = s.replace(":", "").replace("：", "").replace(".", "").trim();
        return t;
    }

    /**
     * 这一行是不是 MRZ（包括小图上只认出半截的情况）。
     *
     * <p>三种特征任一即可：含 {@code <<}；≥24 字符且 90% 落在 [0-9A-Z&lt;]；
     * 或"6 位数字 + 第 8 位是 M/F/&lt;"（TD1 第 2 行被截断成 18 位时就是这种）。
     * 户号格式是 4 数字 + 1 字母 + 13 位字母数字，前 6 位不可能全是数字，不会误伤。
     */
    private static boolean looksLikeMrzLine(String s) {
        if (s == null) return false;
        String t = s.replace(" ", "").trim();
        if (t.isEmpty()) return false;
        if (t.contains("<<")) return true;
        if (t.length() >= 24) {
            int ok = 0;
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || c == '<') ok++;
            }
            if (ok >= t.length() * 0.9) return true;
        }
        if (t.length() >= 8 && digits(t.substring(0, 6)) != null) {
            char c = t.charAt(7);
            if (c == 'M' || c == 'F' || c == '<') return true;
        }
        return false;
    }

    private void findFamilyNo(List<OcrCore.Line> src, int labelIdx) {
        Cand best = null;
        List<Cand> all = new ArrayList<>();
        for (int i = 0; i < src.size(); i++) {
            OcrCore.Line l = src.get(i);
            // MRZ 行（含被截断的片段）不参与户号提取：30 位 alnum 里截 18 位会拼出一个
            // "看着合法"的假户号。卡太小、户号行没检出来时尤其容易踩到。
            if (looksLikeMrzLine(l.text)) continue;
            Cand c = bestInLine(l);
            if (c == null) continue;
            if (labelIdx >= 0) {
                OcrCore.Line lab = src.get(labelIdx);
                float dy = Math.abs(l.cy - lab.cy);
                boolean sameRow = dy < Math.max(l.h, lab.h) * 0.9f;
                // RTL：值在标签左边；库尔德文标签常在编号下方一行，所以也接受"很近的下一行"
                boolean left = l.cx < lab.cx;
                if (sameRow && left) c.bonus = 2.0f;
                else if (sameRow) c.bonus = 1.4f;
                else if (dy < 90 && Math.abs(l.cx - lab.cx) < 600) c.bonus = 0.8f;
            }
            all.add(c);
            if (best == null || c.rank() > best.rank()) best = c;
        }
        if (best == null) return;
        familyNo = best.value;
        familyConf = best.conf;
        buildAlternatives(all, best);
    }

    /** 一行的编号候选。 */
    private static final class Cand {
        String value;
        int cost;
        float conf;
        float bonus;
        /** 逐位次优字符（可能为 null）。 */
        String[] alt;
        String raw;

        float rank() { return bonus * 10f + conf * 2f - cost * 1.5f; }
    }

    /** 候选字符串里每个字符对应的置信度/次优值。 */
    private static final class Chars {
        final StringBuilder text = new StringBuilder();
        final List<Float> conf = new ArrayList<>();
        final List<String> alt = new ArrayList<>();

        String s() { return text.toString(); }

        float[] confArr() {
            float[] a = new float[conf.size()];
            for (int i = 0; i < a.length; i++) a[i] = conf.get(i);
            return a;
        }

        String[] altArr(int from, int len) {
            String[] a = new String[len];
            for (int i = 0; i < len && from + i < alt.size(); i++) a[i] = alt.get(from + i);
            return a;
        }
    }

    private static Chars compact(OcrCore.Line l) {
        Chars c = new Chars();
        String up = l.text.toUpperCase(Locale.US);
        for (int i = 0; i < up.length(); i++) {
            char ch = up.charAt(i);
            if (ch == ' ' || ch == '-' || ch == '_' || ch == ':' || ch == '.') continue;
            c.text.append(ch);
            c.conf.add(l.charConf != null && i < l.charConf.length ? l.charConf[i] : 0.9f);
            c.alt.add(l.charAlt != null && i < l.charAlt.length ? l.charAlt[i] : "");
        }
        return c;
    }

    private static Cand bestInLine(OcrCore.Line l) {
        Chars cs = compact(l);
        String t = cs.s();
        Cand best = null;
        for (int i = 0; i + 18 <= t.length(); i++) {
            if (i > 0 && Character.isDigit(t.charAt(i - 1))) continue;
            if (i + 18 < t.length() && Character.isDigit(t.charAt(i + 18))) continue;
            String s = t.substring(i, i + 18);
            float[] cfg = new float[18];
            for (int k = 0; k < 18; k++) cfg[k] = cs.conf.get(i + k);
            int[] cost = new int[1];
            String v = normalizeFamily(s, cfg, cost);
            if (v == null) continue;
            Cand c = new Cand();
            c.value = v;
            c.cost = cost[0];
            float sum = 0;
            for (float f : cfg) sum += f;
            c.conf = sum / 18f;
            c.raw = s;
            c.alt = cs.altArr(i, 18);
            if (best == null || c.rank() > best.rank()) best = c;
        }
        return best;
    }

    /**
     * 位置规则（两个真实样例反推出来）：
     * <pre>
     *   1002 E 6996574360150     4 数字 + 1 字母 + 13 字母数字
     *   1002 L 00M9770006805                          ↑ 尾部可以再带字母
     * </pre>
     * 前 4 位必须是数字；第 5 位必须是字母；其余是"字母数字"，但尾部字母只有在**不像数字**时才保留
     * （M/C/F/H/J/K/N/R/U/V/W/X/Y），像数字的字母（O/I/S/B/Z/G/D/Q/T/A/E/P）按置信度决定是否转成数字。
     */
    static String normalizeFamily(String s, float[] charConf, int[] costOut) {
        if (s == null || s.length() != 18) return null;
        StringBuilder sb = new StringBuilder(18);
        int cost = 0, digits = 0, letters = 0;
        for (int i = 0; i < 18; i++) {
            char c = s.charAt(i);
            boolean isDigit = c >= '0' && c <= '9';
            boolean isLetter = c >= 'A' && c <= 'Z';
            if (!isDigit && !isLetter) return null;
            float cf = charConf != null && i < charConf.length ? charConf[i] : 0.9f;

            if (i < 4) {                                  // 前 4 位：数字
                if (isDigit) { sb.append(c); digits++; continue; }
                char v = toDigit(c);
                if (v == '?' || cf >= 0.97f) return null;  // 高置信度的真字母 → 不是户号
                sb.append(v);
                digits++;
                cost++;
            } else if (i == 4) {                          // 第 5 位：字母
                if (isLetter) { sb.append(c); letters++; continue; }
                char v = toLetter(c);
                if (v == '?') return null;
                sb.append(v);
                letters++;
                cost++;
            } else {                                      // 尾部：字母数字
                if (isDigit) { sb.append(c); digits++; continue; }
                if (DIGIT_LOOKALIKE.indexOf(c) >= 0) {
                    if (cf >= 0.97f) { sb.append(c); letters++; }
                    else { sb.append(toDigit(c)); digits++; cost++; }
                } else {
                    if (cf < 0.55f) return null;          // 低置信度的"非形近字母"多半是噪声
                    sb.append(c);
                    letters++;
                }
            }
        }
        if (cost > 3) return null;
        if (digits < 13 || letters < 1 || letters > 5) return null;
        String tail = sb.substring(5);
        boolean same = true;
        for (int i = 1; i < tail.length(); i++) if (tail.charAt(i) != tail.charAt(0)) { same = false; break; }
        if (same) return null;
        if (costOut != null && costOut.length > 0) costOut[0] = cost;
        return sb.toString();
    }

    /** 人工输入/候选值走同一条校验（大写、去分隔符、按位置规则修）。界面直接用这个。 */
    public static String sanitizeFamily(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder();
        String up = s.toUpperCase(Locale.US);
        for (int i = 0; i < up.length(); i++) {
            char c = up.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z')) sb.append(c);
        }
        String t = sb.toString();
        if (t.length() != 18) return null;
        float[] cfg = new float[18];
        java.util.Arrays.fill(cfg, 0.9f);
        return normalizeFamily(t, cfg, new int[1]);
    }

    /** 兼容签名：不带置信度时按"模型输出可信"处理。 */
    static String normalizeFamily(String s, int cost) {
        float[] cfg = new float[s.length()];
        java.util.Arrays.fill(cfg, 0.9f);
        int[] out = new int[1];
        return normalizeFamily(s, cfg, out);
    }

    /**
     * 把歧义位置（次优字符也是字母数字、且置信度不高）的几种写法列出来，界面给人工核对。
     * 户号没有校验位，算法只能给"最可能"，多给一两个候选比假装确定更诚实。
     */
    private void buildAlternatives(List<Cand> all, Cand best) {
        familyAlternatives.clear();
        familyAlternatives.add(best.value);
        // 优先用"这一位识别时给出的次优字符"造候选（例如 4 的次优是 M）
        if (best.raw != null && best.alt != null && best.alt.length == 18) {
            for (int i = 0; i < 18; i++) {
                String a = best.alt[i];
                if (a == null || a.isEmpty()) continue;
                char ac = a.charAt(0);
                if (!((ac >= '0' && ac <= '9') || (ac >= 'A' && ac <= 'Z'))) continue;
                if (best.raw.charAt(i) == ac) continue;
                float[] cfg = new float[18];
                java.util.Arrays.fill(cfg, 0.9f);
                String v = normalizeFamily(flip(best.raw, i, ac), cfg, new int[1]);
                if (v != null && !familyAlternatives.contains(v)) familyAlternatives.add(v);
                if (familyAlternatives.size() >= 3) return;
            }
        }
        // 再补充"别的行识别出的、只差 1~2 位的"值
        for (Cand c : all) {
            if (c == best) continue;
            if (diff(c.value, best.value) > 2) continue;
            if (!familyAlternatives.contains(c.value)) familyAlternatives.add(c.value);
            if (familyAlternatives.size() >= 3) return;
        }
    }

    private static int diff(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return 99;
        int d = 0;
        for (int i = 0; i < a.length(); i++) if (a.charAt(i) != b.charAt(i)) d++;
        return d;
    }

    private static String flip(String s, int i, char c) {
        char[] ch = s.toCharArray();
        ch[i] = c;
        return new String(ch);
    }

    // ---------------------------------------------------------------- MRZ

    /** MRZ：找最像机读区的两行（长度 ≥ 28、只含 A-Z0-9<）。 */
    private void findMrz(List<OcrCore.Line> src) {
        List<OcrCore.Line> cand = new ArrayList<>();
        for (OcrCore.Line l : src) {
            String t = sanitizeMrz(l.text);
            if (t.length() < 26) continue;
            int okChars = 0;
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '<') okChars++;
            }
            if (okChars < t.length() * 0.85) continue;
            if (!t.contains("<") && !t.startsWith("I") && !t.startsWith("A") && !t.startsWith("C")) continue;
            cand.add(new OcrCore.Line(t, l.conf, l.cx, l.cy, l.w, l.h, l.angle, l.box, l.rtl));
        }
        if (cand.size() < 2) return;
        // TD1 是三行：第一行证件类型(D)、第二行日期/性别、第三行姓名。姓名行几乎没有数字，
        // 先剔掉，否则会被当成日期行，docNumber/dob/expiry 全错。
        List<OcrCore.Line> withDigits = new ArrayList<>();
        for (OcrCore.Line l : cand) {
            int d = 0;
            for (int i = 0; i < l.text.length(); i++) if (Character.isDigit(l.text.charAt(i))) d++;
            if (d >= 5) withDigits.add(l);
        }
        if (withDigits.size() >= 2) cand = withDigits;

        OcrCore.Line l1 = null, l2 = null;
        for (OcrCore.Line l : cand) {
            String t = l.text;
            if (l1 == null && (t.startsWith("ID") || t.startsWith("I<") || t.startsWith("AC")
                    || t.startsWith("A<") || t.startsWith("C<"))) {
                l1 = l;
            }
        }
        for (OcrCore.Line l : cand) {
            if (l == l1 || l.text.length() < 8) continue;
            char sex = l.text.charAt(7);
            boolean dateish = digits(l.text.substring(0, 6)) != null && (sex == 'M' || sex == 'F' || sex == '<');
            if (dateish) { l2 = l; break; }
        }
        if (l2 == null) {
            for (OcrCore.Line l : cand) {
                if (l == l1 || l.text.length() < 8) continue;
                if (digits(l.text.substring(0, 6)) != null) { l2 = l; break; }
            }
        }
        if (l1 == null || l2 == null) {
            cand.sort((a, b) -> Float.compare(a.cy, b.cy));
            if (l1 == null) l1 = cand.get(0);
            if (l2 == null) l2 = cand.get(cand.get(0) == l1 ? 1 : 0);
        }
        mrzLine1 = padTo(l1.text, 30);
        mrzLine2 = padTo(l2.text, 30);
        mrzFound = true;

        // TD1（第 2、3 行）：证件号 5..13 + 校验位 14；出生 0..5 + 6；有效期 8..13 + 14
        docNumber = clean(mrzLine1.substring(5, 14));
        String docChk = String.valueOf(mrzLine1.charAt(14));
        dob = fixNumeric(mrzLine2.substring(0, 6), mrzLine2.charAt(6));
        String dobChk = String.valueOf(mrzLine2.charAt(6));
        sex = String.valueOf(mrzLine2.charAt(7));
        expiry = fixNumeric(mrzLine2.substring(8, 14), mrzLine2.charAt(14));
        String expChk = String.valueOf(mrzLine2.charAt(14));
        nationality = clean(mrzLine2.substring(15, 18));

        if (docNumber != null && !docChk.equals(String.valueOf(checkDigit(docNumber)))) {
            String fixed = fixToCheckDigit(docNumber, docChk);
            if (fixed != null) docNumber = fixed;
        }
        checkDocOk = docNumber != null && docChk.equals(String.valueOf(checkDigit(docNumber)));
        checkDobOk = dob != null && dobChk.equals(String.valueOf(checkDigit(dob)));
        checkExpiryOk = expiry != null && expChk.equals(String.valueOf(checkDigit(expiry)));
        if (dob != null && expiry != null) {
            String composite = mrzLine1.substring(5, 30) + mrzLine2.substring(0, 7)
                    + mrzLine2.substring(8, 15) + mrzLine2.substring(18, 29);
            checkCompositeOk = String.valueOf(mrzLine2.charAt(29)).equals(String.valueOf(checkDigit(composite)));
        }
    }

    /** 只保留 MRZ 允许的字符，并把 OCR 常见的竖线/感叹号还原成 I。 */
    static String sanitizeMrz(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toUpperCase(s.charAt(i));
            if (c == '|' || c == '!' || c == '¦' || c == 'I' || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '<') {
                if (c == '|' || c == '!' || c == '¦') c = 'I';
                sb.append(c);
            } else if (c == ' ') {
                continue;
            } else if (c == '/') {
                continue;
            }
        }
        return sb.toString();
    }

    /** 6 位日期按数字修（O→0、I→1、S→5…），并用校验位挑一个能自洽的变体。 */
    private static String fixNumeric(String s, char printedChk) {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < s.length(); i++) {
            char v = toDigit(s.charAt(i));
            if (v == '?') return null;
            sb.append(v);
        }
        String base = sb.toString();
        String fixed = fixToCheckDigit(base, String.valueOf(printedChk));
        return fixed == null ? base : fixed;
    }

    /**
     * 校验位反解：在字形混淆对（0↔O、1↔I、5↔S、8↔B、2↔Z、6↔G、4↔A、7↔T、3↔E、9↔P）里
     * 找至多 2 处替换，使 ICAO 7-3-1 校验位等于印刷值。
     *
     * <p>字母数字混合的证件号（如 AE0644356）**不能**整体改成数字——只换混淆对里的字符，
     * 其它字母原样保留，checkDigit 本身就把 A=10…Z=35 算进去了。
     */
    static String fixToCheckDigit(String s, String printedChk) {
        if (s == null || printedChk == null || printedChk.length() != 1) return null;
        char target = printedChk.charAt(0);
        if (!(target >= '0' && target <= '9')) return null;
        String base = cleanAlnum(s);
        if (base == null) return null;
        if (checkDigit(base) == target - '0') return base;
        String one = trySubst(base, 1, target);
        if (one != null) return one;
        String two = trySubst(base, 2, target);
        if (two != null) return two;
        return base;
    }

    private static String cleanAlnum(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toUpperCase(s.charAt(i));
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) sb.append(c);
            else if (c == '<') break;
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static final char[][] SWAP = {
            {'0', 'O'}, {'O', '0'}, {'1', 'I'}, {'I', '1'}, {'5', 'S'}, {'S', '5'},
            {'8', 'B'}, {'B', '8'}, {'2', 'Z'}, {'Z', '2'}, {'6', 'G'}, {'G', '6'},
            {'4', 'A'}, {'A', '4'}, {'7', 'T'}, {'T', '7'}, {'3', 'E'}, {'E', '3'},
            {'9', 'P'}, {'P', '9'}, {'D', '0'}, {'Q', '0'}
    };

    private static char[] alt(char c) {
        List<Character> out = new ArrayList<>();
        for (char[] p : SWAP) if (p[0] == c) out.add(p[1]);
        char[] r = new char[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    private static String trySubst(String base, int maxChanges, char target) {
        int n = base.length();
        char[] cur = base.toCharArray();
        if (maxChanges >= 1) {
            for (int i = 0; i < n; i++) {
                for (char a : alt(cur[i])) {
                    char old = cur[i];
                    cur[i] = a;
                    if (checkDigit(new String(cur)) == target - '0') return new String(cur);
                    cur[i] = old;
                }
            }
        }
        if (maxChanges >= 2) {
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    char oi = cur[i], oj = cur[j];
                    for (char a : alt(cur[i])) {
                        cur[i] = a;
                        for (char b : alt(cur[j])) {
                            cur[j] = b;
                            if (checkDigit(new String(cur)) == target - '0') return new String(cur);
                        }
                        cur[j] = oj;
                    }
                    cur[i] = oi;
                }
            }
        }
        return null;
    }

    private static String padTo(String s, int n) {
        if (s.length() >= n) return s.substring(0, n);
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) sb.append('<');
        return sb.toString();
    }

    private static String digits(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return null;
        return s;
    }

    private static String clean(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') break;
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) sb.append(c);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** ICAO 9303 校验位：权重 7-3-1 循环，'<' 计 0，字母 A=10…Z=35。 */
    public static int checkDigit(String s) {
        int[] w = {7, 3, 1};
        int sum = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int v;
            if (c >= '0' && c <= '9') v = c - '0';
            else if (c >= 'A' && c <= 'Z') v = c - 'A' + 10;
            else v = 0;
            sum += v * w[i % 3];
        }
        return sum % 10;
    }

    /** BAC/PACE 用的 MRZ 信息：证件号+校验位+出生+校验位+有效期+校验位（校验位重算）。 */
    public String mrzInfo() {
        if (docNumber == null || dob == null || expiry == null) return null;
        String a = docNumber + checkDigit(docNumber);
        String b = dob + checkDigit(dob);
        String c = expiry + checkDigit(expiry);
        return padToDoc(a) + b + c;
    }

    private static String padToDoc(String s) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < 10) sb.append('<');
        return sb.length() > 10 ? sb.substring(0, 10) : sb.toString();
    }

    public boolean mrzUsable() {
        return mrzFound && docNumber != null && dob != null && expiry != null;
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("家庭编号=").append(familyNo == null ? "未识别" : familyNo)
                .append("  MRZ=").append(mrzUsable() ? "可用" : "不可用");
        if (mrzUsable()) {
            sb.append("  证件号=").append(docNumber)
                    .append(" 出生=").append(dob)
                    .append(" 有效期=").append(expiry)
                    .append("  校验位(doc/dob/exp/composite)=")
                    .append(checkDocOk ? "✓" : "✗")
                    .append(checkDobOk ? "✓" : "✗")
                    .append(checkExpiryOk ? "✓" : "✗")
                    .append(checkCompositeOk ? "✓" : "✗");
        }
        return sb.toString();
    }
}
