package iq.digitalration.faceverify;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 现场验证清单：用例定义 + 证据汇总 + 报告生成。
 *
 * <p>用例只覆盖「这台手机 + 这个 APK 自己能证明」的部分（人脸、活体、卡背 OCR、NFC 芯片、
 * 离线、环境、完整性、性能），需要在服务端 / 业主样本 / 另一台设备上做的条目不在这里，
 * 报告末尾会单列。
 *
 * <p>证据不是手填的：{@link VerifyActivity} 与 {@link IdCardActivity} 每次跑完都会把结果写进
 * {@link ChecklistStore}，本类只负责把这些结果整理成人能读的行。
 */
public final class Checklist {

    public static final String PASS = "pass";
    public static final String PARTIAL = "partial";
    public static final String FAIL = "fail";
    public static final String NA = "na";
    public static final String NONE = "";

    private Checklist() {}

    public static String label(String v) {
        if (PASS.equals(v)) return "可行";
        if (PARTIAL.equals(v)) return "有条件可行";
        if (FAIL.equals(v)) return "不可行";
        if (NA.equals(v)) return "不适用";
        return "未测";
    }

    // ------------------------------------------------------------ 用例定义

    public static final class Case {
        public final String id, tv, title, criteria, howto;

        Case(String id, String tv, String title, String criteria, String howto) {
            this.id = id;
            this.tv = tv;
            this.title = title;
            this.criteria = criteria;
            this.howto = howto;
        }
    }

    public static final Case[] CASES = {
            new Case("ENV-01", "TV-21 / TV-30", "设备与运行环境",
                    "能读出机型、系统版本、ABI、内存、存储、相机、NFC、屏幕，且与系统设置一致",
                    "进入本页自动采集；可与「设置 → 关于手机」逐项对照"),
            new Case("INTEG-01", "TV-04（部分）", "安装包完整性与签名",
                    "签名指纹固定可核对；不申请网络权限；Root / 模拟器如实记录",
                    "自动读取 PackageManager 的签名、权限、安装来源；改包重签后指纹必然变化"),
            new Case("FACE-01", "TV-03", "1:1 人脸比对",
                    "同人相似度 ≥ 阈值（R50 0.45 / lite 0.58），单次比对 ≤ 2s",
                    "主界面点「② 开始核验」，通过后自动登记相似度、阈值、帧数与耗时"),
            new Case("LIVE-01", "TV-03", "活体挑战",
                    "随机 3 个动作全部通过；静态照片与简单视频不能通过",
                    "与核验同一次完成，自动登记动作序列与四个峰值"),
            new Case("OCR-01", "TV-01", "卡背家庭编号与 MRZ",
                    "户号逐字符正确（≥99.5%）、MRZ 三项校验位通过、单张 ≤1.5s",
                    "证件核验 → ① 拍摄识别；有歧义位时用「改正家庭编号」，结果自动登记"),
            new Case("NFC-01", "TV-02 / TV-29", "NFC 读芯片与被动认证",
                    "读卡成功、DG1/DG2/DG11 可读、EF.SOD 四项判定给出、单次 ≤15s",
                    "MRZ 就绪后把证件贴到 NFC 区域，自动读并登记 APDU 数、耗时与判定"),
            new Case("OFFLINE-01", "TV-25 / TV-30", "离线可用",
                    "飞行模式下全流程可跑通；安装包不申请任何网络权限",
                    "打开飞行模式完整跑一次核验；本页自动核对权限清单"),
            new Case("PERF-01", "TV-30", "首启与推理耗时",
                    "首启可用 ≤30s、建模 ≤3s、比对 ≤1s（中端机 R50 允许 ≤2s）",
                    "看本页的 initMs、建模耗时、模型与安装包体积"),
            new Case("SPOOF-01", "TV-28", "被动反欺诈（打印 / 屏幕 / 视频重放）",
                    "打印与屏幕翻拍 APCER ≤1%，高质量视频回放 ≤5%，BPCER ≤3%",
                    "本版未集成被动模型（内置 MiniFASNetV2 输出恒定已弃用），如实记录为缺口"),
            new Case("RTL-01", "TV-22", "阿语 / 库语 RTL 与本地化",
                    "RTL 镜像布局、数字与日期本地化、长文案不截断",
                    "本应用界面为中文，RTL 属正式 App（WebView 版）范畴；如实记录是否适用"),
    };

    // ------------------------------------------------------------ 汇总

    public static final class Row {
        public final Case c;

        Row(Case c) {
            this.c = c;
        }

        public String verdict = NONE;
        public String note = "";
        public final List<String> ev = new ArrayList<>();
        /** 是否已有自动采集的数据（只有 SPOOF / RTL 这类可能没有）。 */
        public boolean hasData;
        /** 给现场人的一句自动判断，不替代人工结论。 */
        public String hint = "";
    }

    public static List<Row> rows(Context ctx, JSONObject dev) {
        List<Row> list = new ArrayList<>();
        JSONObject res = ChecklistStore.results(ctx);
        JSONObject face = res.optJSONObject("face");
        JSONObject ocr = res.optJSONObject("ocr");
        JSONObject nfc = res.optJSONObject("nfc");

        for (Case c : CASES) {
            Row r = new Row(c);
            r.verdict = ChecklistStore.verdict(ctx, c.id);
            r.note = ChecklistStore.note(ctx, c.id);
            switch (c.id) {
                case "ENV-01": env(r, dev); break;
                case "INTEG-01": integ(r, dev); break;
                case "FACE-01": face(r, face); break;
                case "LIVE-01": live(r, face); break;
                case "OCR-01": ocr(r, ocr); break;
                case "NFC-01": nfc(r, nfc); break;
                case "OFFLINE-01": offline(r, dev); break;
                case "PERF-01": perf(r, dev, ctx); break;
                case "SPOOF-01": spoof(r, dev, face); break;
                case "RTL-01": rtl(r); break;
                default: break;
            }
            list.add(r);
        }
        return list;
    }

    public static int done(List<Row> rows) {
        int n = 0;
        for (Row r : rows) if (r.verdict != null && r.verdict.length() > 0) n++;
        return n;
    }

    // ------------------------------------------------------------ 各用例证据

    private static void env(Row r, JSONObject dev) {
        JSONObject d = dev.optJSONObject("device");
        JSONObject m = dev.optJSONObject("memory");
        JSONObject st = dev.optJSONObject("storage");
        JSONObject dp = dev.optJSONObject("display");
        JSONObject cam = dev.optJSONObject("camera");
        JSONObject nfc = dev.optJSONObject("nfc");
        if (d == null) { r.ev.add("设备信息采集失败"); return; }
        r.hasData = true;
        r.ev.add(d.optString("manufacturer") + " " + d.optString("model")
                + " · Android " + d.optString("release") + " (API " + d.optInt("sdk") + ")");
        r.ev.add("ABI " + d.optString("abi") + " · 核心 " + d.optInt("cores")
                + " · 安全补丁 " + d.optString("securityPatch", "—"));
        if (m != null) {
            r.ev.add("内存 总 " + DeviceInfo.humanBytes(m.optLong("totalBytes"))
                    + " / 可用 " + DeviceInfo.humanBytes(m.optLong("availBytes"))
                    + (m.optBoolean("lowRamDevice") ? " · 低内存机型" : ""));
        }
        if (st != null) {
            r.ev.add("存储 总 " + DeviceInfo.humanBytes(st.optLong("totalBytes"))
                    + " / 可用 " + DeviceInfo.humanBytes(st.optLong("freeBytes"))
                    + " · 应用占用 " + DeviceInfo.humanBytes(st.optLong("appFilesBytes")));
        }
        if (dp != null) r.ev.add("屏幕 " + dp.optInt("widthPx") + "×" + dp.optInt("heightPx")
                + " @" + dp.optInt("densityDpi") + "dpi · " + dp.optDouble("refreshRate", 0) + "Hz");
        if (cam != null) r.ev.add("相机 " + cam.optInt("count") + " 个（后置 " + cam.optInt("back")
                + " / 前置 " + cam.optInt("front") + "）");
        if (nfc != null) r.ev.add("NFC " + (!nfc.optBoolean("supported") ? "不支持（本机不做芯片验证）"
                : nfc.optBoolean("enabled") ? "支持且已开启" : "支持但未开启（请到系统设置打开）"));
        r.hint = "自动采集完成，与系统设置页对照即可";
    }

    private static void integ(Row r, JSONObject dev) {
        JSONObject a = dev.optJSONObject("app");
        JSONObject it = dev.optJSONObject("integrity");
        if (a == null) { r.ev.add("安装包信息读取失败"); return; }
        r.hasData = true;
        r.ev.add(a.optString("id") + " · " + a.optString("versionName")
                + " (" + a.optLong("versionCode") + ")");
        r.ev.add("签名 SHA-256：" + a.optString("signerSha256", "—"));
        r.ev.add("安装来源：" + (a.optString("installer").isEmpty() ? "未知（侧载/文件管理器）" : a.optString("installer"))
                + " · debuggable=" + a.optBoolean("debuggable"));
        r.ev.add("申请权限：" + (a.optJSONArray("permissions") == null ? "—"
                : a.optJSONArray("permissions").length() + " 项")
                + " · 含网络权限：" + (a.optBoolean("networkPermission") ? "是" : "否"));
        if (it != null) {
            r.ev.add("Root 迹象：" + (it.optBoolean("rootSuspected")
                    ? it.optJSONArray("rootSigns").toString() : "未发现（仅常见 su 路径探测）"));
            r.ev.add("模拟器迹象：" + (it.optBoolean("emulatorSuspected")
                    ? it.optJSONArray("emulatorSigns").toString() : "未发现"));
        }
        r.hint = "签名指纹与交付记录一致即说明未改包";
    }

    private static void face(Row r, JSONObject f) {
        if (f == null) { r.ev.add("尚无记录：先在主界面完成一次核验"); return; }
        r.hasData = true;
        float sim = (float) f.optDouble("sim", 0);
        float thr = (float) f.optDouble("thr", 0);
        boolean pass = f.optBoolean("pass");
        r.ev.add(String.format(Locale.US, "相似度 %.3f / 阈值 %.2f → %s", sim, thr, pass ? "通过" : "不通过"));
        r.ev.add("融合 " + f.optInt("frames") + " 帧 · 特征耗时 " + f.optLong("ms")
                + "ms · TTA " + (f.optBoolean("tta") ? "开" : "关"));
        r.ev.add("模型 " + f.optString("model", "—") + " · " + f.optString("time"));
        String montage = f.optString("montage", "");
        if (!montage.isEmpty()) r.ev.add("调试图：" + new java.io.File(montage).getName());
        r.hint = pass ? "本次同人比对通过（样本 1 对）" : "本次未通过：确认是否同一人 / 阈值是否偏低";
    }

    private static void live(Row r, JSONObject f) {
        if (f == null) { r.ev.add("尚无记录：核验通过后自动登记"); return; }
        r.hasData = true;
        r.ev.add(String.format(Locale.US, "峰值 眨眼 %.2f · 转头 %.2f · 张嘴 %.2f · 微笑 %.2f",
                f.optDouble("blink", 0), f.optDouble("yaw", 0), f.optDouble("jaw", 0), f.optDouble("smile", 0)));
        r.ev.add("动作序列：" + f.optString("actions", "—"));
        r.ev.add("机制：MediaPipe blendshape 挑战-响应（随机 3 个动作，顺序随机）");
        r.hint = "只记录了一次挑战；建议至少 3 次不同人/不同光照";
    }

    private static void ocr(Row r, JSONObject o) {
        if (o == null) { r.ev.add("尚无记录：证件核验 → ① 拍摄识别后自动登记"); return; }
        r.hasData = true;
        String fam = o.optString("familyNo", "");
        r.ev.add("家庭编号：" + (fam.isEmpty() ? "未识别" : fam)
                + "（置信 " + String.format(Locale.US, "%.0f%%", o.optDouble("familyConf", 0) * 100) + "）");
        String label = o.optString("familyLabel", "");
        if (!label.isEmpty()) r.ev.add("命中的标签：" + label);
        JSONArray alt = o.optJSONArray("alternatives");
        if (alt != null && alt.length() > 1) r.ev.add("歧义候选：" + alt.toString().replace("\"", ""));
        r.ev.add("证件号 " + o.optString("docNumber", "—")
                + " · 出生 " + o.optString("dob", "—")
                + " · 有效期 " + o.optString("expiry", "—"));
        r.ev.add("校验位 证件号" + (o.optBoolean("checkDoc") ? "✓" : "✗")
                + " 出生" + (o.optBoolean("checkDob") ? "✓" : "✗")
                + " 有效期" + (o.optBoolean("checkExpiry") ? "✓" : "✗")
                + " · MRZ 可用 " + (o.optBoolean("mrzUsable") ? "是" : "否"));
        r.ev.add(o.optInt("frames") + " 帧 · " + o.optLong("ms") + "ms · " + o.optString("time"));
        r.hint = o.optBoolean("mrzUsable") ? "MRZ 三项校验通过，可继续贴卡读芯片" : "MRZ 不完整：可手输补全后再读芯片";
    }

    private static void nfc(Row r, JSONObject n) {
        if (n == null) { r.ev.add("尚无记录：贴卡读芯片成功后自动登记"); return; }
        r.hasData = true;
        r.ev.add(n.optBoolean("ok") ? "读取成功 · " + n.optString("accessControl")
                : "读取失败：" + n.optString("error", "—"));
        if (n.optBoolean("ok")) {
            r.ev.add(n.optInt("apduCount") + " 条 APDU · " + n.optLong("ms") + "ms");
            JSONObject dg1 = n.optJSONObject("dg1");
            if (dg1 != null) r.ev.add("DG1 证件号 " + dg1.optString("docNumber", "—")
                    + " · 有效期 " + dg1.optString("expiry", "—")
                    + " · 校验 " + (dg1.optBoolean("checks") ? "✓" : "✗"));
            JSONObject dg2 = n.optJSONObject("dg2");
            if (dg2 != null) r.ev.add("DG2 人像 " + dg2.optString("format", "—")
                    + " " + DeviceInfo.humanBytes(dg2.optLong("bytes"))
                    + (dg2.optBoolean("decoded") ? "（已解码 " + dg2.optInt("w") + "×" + dg2.optInt("h") + "）" : "（无法解码）"));
            JSONObject dg11 = n.optJSONObject("dg11");
            if (dg11 != null && !dg11.optString("personalNumber", "").isEmpty()) {
                r.ev.add("DG11 家庭编号：" + dg11.optString("personalNumber"));
            }
            JSONObject sod = n.optJSONObject("sod");
            if (sod != null) {
                r.ev.add("SOD 签名 " + (sod.optBoolean("signatureOk") ? "✓" : "✗")
                        + " · 数据组哈希 " + sod.optInt("dgMatched") + "/" + sod.optInt("dgVerified")
                        + (sod.optBoolean("dgHashesOk") ? " ✓" : " ✗")
                        + " · 链自洽 " + (sod.optBoolean("chainOk") ? "✓" : "—")
                        + " · 证书有效 " + (sod.optBoolean("certValidOk") ? "✓" : "✗"));
                r.ev.add("摘要算法 " + sod.optString("hashAlg", "—")
                        + " · CSCA 锚定：未做（缺业主主列表）");
            }
            JSONObject cmp = n.optJSONObject("compare");
            if (cmp != null) {
                if (cmp.has("familyMatch")) {
                    r.ev.add("光学 vs 芯片 家庭编号：" + (cmp.optBoolean("familyMatch") ? "一致" : "不一致")
                            + (cmp.optString("familyDiff").isEmpty() ? "" : "（" + cmp.optString("familyDiff") + "，以芯片为准）"));
                }
                if (cmp.has("docMatch")) {
                    r.ev.add("光学 vs 芯片 证件号：" + (cmp.optBoolean("docMatch") ? "一致" : "不一致"));
                }
            }
        }
        r.ev.add("时间：" + n.optString("time"));
        r.hint = n.optBoolean("ok") ? "芯片数据可读且与 SOD 自洽（未锚定 CSCA）" : "贴卡位置/时长/批次都可能是原因，重新贴一次再记";
    }

    private static void offline(Row r, JSONObject dev) {
        JSONObject a = dev.optJSONObject("app");
        JSONObject rt = dev.optJSONObject("runtime");
        r.hasData = true;
        if (a != null) {
            JSONArray p = a.optJSONArray("permissions");
            r.ev.add("申请权限（" + (p == null ? 0 : p.length()) + " 项）："
                    + (p == null ? "—" : p.toString().replace("\"", "")));
            r.ev.add("网络权限：" + (a.optBoolean("networkPermission") ? "有（需说明用途）" : "无 —— 应用层不可能联网"));
        }
        if (rt != null) r.ev.add("模型 " + rt.optString("modelId") + " 与 OCR 资源全部在 APK 内，运行时从私有目录加载");
        r.ev.add("现场自证：打开飞行模式后完整跑一次核验（本项由人工确认）");
        r.hint = "无 INTERNET 权限是强证据；飞行模式实测请勾在备注里";
    }

    private static void perf(Row r, JSONObject dev, Context ctx) {
        JSONObject a = dev.optJSONObject("app");
        JSONObject rt = dev.optJSONObject("runtime");
        JSONObject st = dev.optJSONObject("storage");
        if (a == null) { r.ev.add("性能数据读取失败"); return; }
        r.hasData = true;
        r.ev.add("安装包 " + DeviceInfo.humanBytes(a.optLong("apkBytes"))
                + " · 应用数据 " + (st == null ? "—" : DeviceInfo.humanBytes(st.optLong("appFilesBytes"))));
        if (rt != null) {
            r.ev.add("模型体积 arcface " + DeviceInfo.humanBytes(rt.optLong("modelBytes"))
                    + " + landmarker " + DeviceInfo.humanBytes(rt.optLong("landmarkerBytes")));
            r.ev.add("引擎初始化 " + rt.optLong("initMs") + "ms · 阈值 " + String.format(Locale.US, "%.2f", rt.optDouble("threshold", 0)));
            if (rt.optBoolean("hasRef")) {
                r.ev.add("参照建模耗时 " + rt.optLong("refGenMs") + "ms（当前参照）");
            } else {
                r.ev.add("尚未建模：先选一张参照照片再回来看建模耗时");
            }
        }
        r.hint = "首启复制模型耗时约 5—20s，属于一次性成本";
    }

    private static void spoof(Row r, JSONObject dev, JSONObject f) {
        JSONObject rt = dev.optJSONObject("runtime");
        r.hasData = rt != null;
        boolean enabled = rt != null && rt.optBoolean("spoofEnabled");
        r.ev.add("被动反欺诈模型：" + (enabled ? "已启用" : "未启用（App.spoof = null）"));
        r.ev.add("内置 MiniFASNetV2 对真脸与屏幕翻拍输出恒定（0.99 同一类），已弃用，不作为能力");
        if (f != null) {
            r.ev.add("本次会话只运行挑战式活体：" + f.optString("actions", "—"));
            r.ev.add("→ 能拦静态照片与简单视频，拦不住高质量视频重放（如实记录）");
        } else {
            r.ev.add("建议实测：打印照片、手机屏幕翻拍、视频回放各 5 次，记录是否被挑战式活体拦下");
        }
        r.hint = "这是已知缺口，结论通常为「不可行」，需回写基线";
    }

    private static void rtl(Row r) {
        r.hasData = false;
        r.ev.add("本应用（离线核验）界面为中文，无多语言资源");
        r.ev.add("RTL / 三语属正式 App（WebView + i18n）范畴，不在本包内");
        r.hint = "该项通常选「不适用」；如已覆盖请在备注写明";
    }

    // ------------------------------------------------------------ 报告

    public static String markdown(Context ctx, JSONObject dev, List<Row> rows) {
        JSONObject a = dev.optJSONObject("app");
        JSONObject d = dev.optJSONObject("device");
        JSONObject rt = dev.optJSONObject("runtime");
        JSONObject it = dev.optJSONObject("integrity");
        StringBuilder sb = new StringBuilder();
        sb.append("# 离线人脸核验 App · 技术验证报告\n\n");
        sb.append("- 生成时间：").append(DeviceInfo.now()).append('\n');
        if (a != null) {
            sb.append("- 应用：").append(a.optString("id")).append(" · ").append(a.optString("versionName"))
                    .append(" (").append(a.optLong("versionCode")).append(")\n");
            sb.append("- 安装包：").append(DeviceInfo.humanBytes(a.optLong("apkBytes")))
                    .append(" · 安装来源 ").append(a.optString("installer", "未知")).append('\n');
            sb.append("- 签名 SHA-256：").append(a.optString("signerSha256", "—")).append('\n');
        }
        if (d != null) {
            sb.append("- 设备：").append(d.optString("manufacturer")).append(' ').append(d.optString("model"))
                    .append(" · Android ").append(d.optString("release"))
                    .append(" (API ").append(d.optInt("sdk")).append(") · ").append(d.optString("abi")).append('\n');
        }
        if (it != null) {
            sb.append("- 完整性：Root ").append(it.optBoolean("rootSuspected") ? "疑似" : "未发现")
                    .append(" · 模拟器 ").append(it.optBoolean("emulatorSuspected") ? "疑似" : "未发现").append('\n');
        }
        if (rt != null) {
            sb.append("- 模型：").append(rt.optString("modelId")).append(' ')
                    .append(rt.optString("modelDesc")).append(" · 阈值 ")
                    .append(String.format(Locale.US, "%.2f", rt.optDouble("threshold", 0)))
                    .append(" · 初始化 ").append(rt.optLong("initMs")).append("ms\n");
        }
        sb.append("- 执行人：").append(orDash(ChecklistStore.meta(ctx, "tester"))).append('\n');
        sb.append("- 现场：").append(orDash(ChecklistStore.meta(ctx, "site"))).append('\n');
        String notes = ChecklistStore.meta(ctx, "notes");
        if (!notes.isEmpty()) sb.append("- 现场备注：").append(notes).append('\n');
        sb.append("- 进度：").append(done(rows)).append('/').append(rows.size()).append('\n');

        sb.append("\n## 总览\n\n");
        sb.append("| 编号 | 清单条目 | 验证项 | 结论 | 关键证据 |\n");
        sb.append("|---|---|---|---|---|\n");
        for (Row r : rows) {
            sb.append("| ").append(r.c.id).append(" | ").append(r.c.tv).append(" | ")
                    .append(r.c.title).append(" | ").append(label(r.verdict)).append(" | ")
                    .append(oneLine(r)).append(" |\n");
        }

        sb.append("\n## 明细\n\n");
        for (Row r : rows) {
            sb.append("### ").append(r.c.id).append(' ').append(r.c.title)
                    .append("（").append(r.c.tv).append("）\n\n");
            sb.append("- 判据：").append(r.c.criteria).append('\n');
            sb.append("- 做法：").append(r.c.howto).append('\n');
            sb.append("- 结论：").append(label(r.verdict)).append('\n');
            if (!r.note.isEmpty()) sb.append("- 备注：").append(r.note).append('\n');
            if (!r.hint.isEmpty()) sb.append("- 自动判断：").append(r.hint).append('\n');
            sb.append("- 证据：\n");
            if (r.ev.isEmpty()) sb.append("  - （无记录）\n");
            for (String e : r.ev) sb.append("  - ").append(e).append('\n');
            sb.append('\n');
        }

        sb.append("## 本模块未覆盖的清单条目\n\n");
        sb.append("- TV-05 本机生物识别登录、TV-06 设备绑定换绑：属正式 App 的账号体系，本包没有登录态\n");
        sb.append("- TV-07 云核验兜底：历史试验已终止\n");
        sb.append("- TV-08 动态二维码、TV-09—TV-12 离线花名册/核销/回执/库存：需服务端与业务数据\n");
        sb.append("- TV-13—TV-16 支付、POS、短信、内政部 API：需业主与供应商配合\n");
        sb.append("- TV-17—TV-20、TV-24 数据共享、GIS、峰值、规则引擎、灾备：需服务端环境\n");
        sb.append("- TV-23 大模型本地推理、TV-26 合规与存储测算、TV-27 iOS 等价实现：不在本次范围\n");
        sb.append('\n');
        sb.append("## 声明\n\n");
        sb.append("- 本报告只记录现场采集与自动探测结果，不构成验收结论；阈值与结论由我方按清单判据离线复核后回写基线。\n");
        sb.append("- 证据只存本机，导出 ZIP 由现场人员通过即时通讯/邮件回传，未上传任何服务器。\n");
        sb.append("- 芯片「被动认证」只证明数据与 SOD 自洽，未做伊拉克 CSCA 锚定。\n");
        return sb.toString();
    }

    private static String oneLine(Row r) {
        if (r.ev.isEmpty()) return "—";
        String s = r.ev.get(0).replace("|", "/");
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    private static String orDash(String s) {
        return s == null || s.isEmpty() ? "—" : s;
    }

    public static String csv(List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("caseId,tv,item,verdict,note,evidence\n");
        for (Row r : rows) {
            sb.append(csvCell(r.c.id)).append(',')
                    .append(csvCell(r.c.tv)).append(',')
                    .append(csvCell(r.c.title)).append(',')
                    .append(csvCell(label(r.verdict))).append(',')
                    .append(csvCell(r.note)).append(',')
                    .append(csvCell(DeviceInfo.join(r.ev, " | "))).append('\n');
        }
        return sb.toString();
    }

    private static String csvCell(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    public static String json(Context ctx, JSONObject dev, List<Row> rows) {
        JSONObject o = new JSONObject();
        try {
            o.put("schema", "rcm-faceverify-checklist@1");
            o.put("generatedAt", new Date().toString());
            o.put("meta", ChecklistStore.state(ctx).optJSONObject("meta"));
            o.put("device", dev);
            JSONArray arr = new JSONArray();
            for (Row r : rows) {
                JSONObject x = new JSONObject();
                x.put("id", r.c.id);
                x.put("tv", r.c.tv);
                x.put("title", r.c.title);
                x.put("criteria", r.c.criteria);
                x.put("howto", r.c.howto);
                x.put("verdict", r.verdict);
                x.put("verdictLabel", label(r.verdict));
                x.put("note", r.note);
                x.put("hint", r.hint);
                JSONArray ev = new JSONArray();
                for (String e : r.ev) ev.put(e);
                x.put("evidence", ev);
                arr.put(x);
            }
            o.put("cases", arr);
            o.put("results", ChecklistStore.results(ctx));
        } catch (Throwable ignored) {
        }
        return o.toString();
    }
}
