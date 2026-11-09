package iq.digitalration.faceverify.card;

import java.util.Locale;

/**
 * 机读区（MRZ）解析：TD1（身份证/ID 卡，3×30）、TD2（2×36）、TD3（护照，2×44）。
 *
 * <p>BAC/PACE 的密码只用到三组字段：证件号、出生日期、有效期，加上它们各自的校验位
 * （MRZi，固定 24 字符）。这里同时保留其它字段供界面展示。
 *
 * <p>校验位一律用 ICAO 7-3-1 权重重算：OCR 把校验位认错时，重算值才是芯片真正用的值。
 */
public final class Mrz {

    public String raw = "";
    public String format = "";           // TD1 / TD2 / TD3
    public String docCode = "";
    public String issuingState = "";
    public String docNumber = "";
    public String nationality = "";
    public String dob = "";              // YYMMDD
    public String sex = "";
    public String expiry = "";           // YYMMDD
    public String optionalData = "";
    public String name = "";             // 姓<<名（'<' 分隔）

    public boolean docNumberOk, dobOk, expiryOk, compositeOk;
    public String error;

    public static Mrz parse(String text) {
        Mrz m = new Mrz();
        if (text == null) {
            m.error = "空 MRZ";
            return m;
        }
        String t = text.toUpperCase(Locale.US).replaceAll("[^A-Z0-9<]", "");
        m.raw = t;
        if (t.length() >= 88) {
            m.format = "TD1";
            m.parseTd1(t);
        } else if (t.length() >= 72) {
            m.format = "TD2";
            m.parseTd2(t);
        } else if (t.length() >= 60) {
            m.format = "TD1";
            m.parseTd1(pad(t, 90));
        } else {
            m.error = "MRZ 长度不足（" + t.length() + "）";
        }
        return m;
    }

    /** 由已知三组字段直接构造（界面上允许手输）。 */
    public static Mrz fromFields(String docNumber, String dob, String expiry) {
        Mrz m = new Mrz();
        m.format = "MRZi";
        m.docNumber = norm(docNumber, 9);
        m.dob = norm(dob, 6);
        m.expiry = norm(expiry, 6);
        m.checkFields();
        return m;
    }

    private static String norm(String s, int n) {
        String v = s == null ? "" : s.toUpperCase(Locale.US).replaceAll("[^A-Z0-9<]", "");
        if (v.length() > n) return v.substring(0, n);
        StringBuilder sb = new StringBuilder(v);
        while (sb.length() < n) sb.append('<');
        return sb.toString();
    }

    private static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) sb.append('<');
        return sb.toString();
    }

    private void parseTd1(String t) {
        String l1 = t.substring(0, 30), l2 = t.substring(30, 60), l3 = t.substring(60, 90);
        docCode = l1.substring(0, 2);
        issuingState = l1.substring(2, 5);
        docNumber = l1.substring(5, 14);
        String docChk = l1.substring(14, 15);
        optionalData = l1.substring(15, 30);
        dob = l2.substring(0, 6);
        String dobChk = l2.substring(6, 7);
        sex = l2.substring(7, 8);
        expiry = l2.substring(8, 14);
        String expChk = l2.substring(14, 15);
        nationality = l2.substring(15, 18);
        name = l3;

        docNumberOk = docChk.equals(String.valueOf(checkDigit(docNumber)));
        dobOk = dobChk.equals(String.valueOf(checkDigit(dob)));
        expiryOk = expChk.equals(String.valueOf(checkDigit(expiry)));
        String composite = l1.substring(5, 30) + l2.substring(0, 7) + l2.substring(8, 15) + l2.substring(18, 29);
        compositeOk = l2.substring(29, 30).equals(String.valueOf(checkDigit(composite)));
    }

    private void parseTd2(String t) {
        String l1 = t.substring(0, 36), l2 = t.substring(36, 72);
        docCode = l1.substring(0, 2);
        issuingState = l1.substring(2, 5);
        name = l1.substring(5, 36);
        docNumber = l2.substring(0, 9);
        dob = l2.substring(13, 19);
        sex = l2.substring(20, 21);
        expiry = l2.substring(21, 27);
        nationality = l2.substring(10, 13);

        docNumberOk = l2.substring(9, 10).equals(String.valueOf(checkDigit(docNumber)));
        dobOk = l2.substring(19, 20).equals(String.valueOf(checkDigit(dob)));
        expiryOk = l2.substring(27, 28).equals(String.valueOf(checkDigit(expiry)));
        compositeOk = l2.substring(35, 36).equals(String.valueOf(checkDigit(l2.substring(0, 10) + l2.substring(13, 20) + l2.substring(21, 28) + l2.substring(28, 35))));
    }

    private void checkFields() {
        docNumberOk = true;
        dobOk = true;
        expiryOk = true;
        compositeOk = true;
    }

    /** MRZi（24 字符）：证件号9+校验1 + 出生6+校验1 + 有效期6+校验1。 */
    public String mrzInfo() {
        String d = norm(docNumber, 9);
        String b = norm(dob, 6);
        String e = norm(expiry, 6);
        return d + checkDigit(d) + b + checkDigit(b) + e + checkDigit(e);
    }

    public boolean usable() {
        return docNumber != null && !docNumber.trim().isEmpty()
                && dob != null && dob.length() == 6 && isDigits(dob)
                && expiry != null && expiry.length() == 6 && isDigits(expiry);
    }

    private static boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        return s.length() > 0;
    }

    /** 姓名：'<<' 分开姓与名，单 '<' 是空格。 */
    public String nameDisplay() {
        if (name == null || name.isEmpty()) return "";
        return name.replace("<<", " / ").replace('<', ' ').trim();
    }

    /** YYMMDD → YYYY-MM-DD（世纪按 ICAO 的规则：出生 > 当前年份则算 19xx）。 */
    public static String fmtDate(String yymmdd) {
        if (yymmdd == null || yymmdd.length() != 6 || !isDigits(yymmdd)) return yymmdd == null ? "" : yymmdd;
        int yy = Integer.parseInt(yymmdd.substring(0, 2));
        int mm = Integer.parseInt(yymmdd.substring(2, 4));
        int dd = Integer.parseInt(yymmdd.substring(4, 6));
        java.util.Calendar c = java.util.Calendar.getInstance();
        int nowYy = c.get(java.util.Calendar.YEAR) % 100;
        int year = yy <= nowYy + 10 ? 2000 + yy : 1900 + yy;
        return String.format(Locale.US, "%04d-%02d-%02d", year, mm, dd);
    }

    /** ICAO 9303 校验位：权重 7-3-1，'<' 计 0，A=10…Z=35。 */
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
}
