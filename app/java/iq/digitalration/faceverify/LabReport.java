package iq.digitalration.faceverify;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 技术验证实验室的条目定义与报告生成。
 *
 * <p>条目表是**唯一的事实来源**：界面按它排列、结论按它判定、报告按它生成，
 * 避免"界面一套、报告一套"。每一条都写明：
 * <ul>
 *   <li>{@code key}：在 {@link LabStore} 里的结果键（本机 JSON，不上传）；</li>
 *   <li>{@code tv}：对应的技术验证条目编号；</li>
 *   <li>{@code fields}：要摆到报告里给人看的关键字段（不是把整个 JSON 贴上去）。</li>
 * </ul>
 *
 * <p>结论只有三种："通过"（自测 ok=true）、"未通过"（跑了但 ok=false 或报错）、"未跑"（本机没有记录）。
 * 有些条目（设备信息、可信性探测）不做通过/不通过判定，只标"已采集"——它们是判据的输入，不是结论。
 */
public final class LabReport {

    public static final String SCHEMA = "rcm-lab@1";

    private LabReport() {}

    /** 一条技术验证条目。 */
    public static final class Item {
        public final String key;
        public final String tv;
        public final String name;
        public final String hint;
        public final boolean judgeable;
        final String[] fields;

        Item(String key, String tv, String name, String hint, boolean judgeable, String... fields) {
            this.key = key;
            this.tv = tv;
            this.name = name;
            this.hint = hint;
            this.judgeable = judgeable;
            this.fields = fields;
        }
    }

    private static List<Item> items;

    public static synchronized List<Item> items() {
        if (items == null) {
            List<Item> l = new ArrayList<>();
            l.add(new Item("env", "TV-21 / TV-30", "设备与运行环境",
                    "机型、系统、ABI、核心数、内存、存储、相机、NFC（系统 API 直读）", false,
                    "device", "app", "memory", "storage", "camera", "nfc", "integrity"));
            l.add(new Item("trust", "TV-04", "端侧结果可信性",
                    "改包/Root/模拟器迹象 + 设备密钥证明（云端判决需要联网，本包没有网络权限）", false,
                    "verdict", "api", "deviceKey"));
            l.add(new Item("receipt", "TV-11", "终端回执与防篡改",
                    "结果签名 + 单调计数器；改 payload / 改时间 / 改计数器、原样重放都必须被识破", true,
                    "cleanPass", "payloadTamperRejected", "timeTamperRejected", "counterTamperRejected",
                    "replayDetected", "counter", "verifyMs"));
            l.add(new Item("bio", "TV-05", "本机生物识别登录",
                    "强模态（指纹/3D 人脸）绑定 Keystore 密钥，未认证必须签不出名；"
                            + "「系统认证」指纹 / 人脸 / 密码任一通过即签发凭据（模态由系统决定，应用不能指定）；"
                            + "「人脸登录」走前置相机做活体挑战 + 端侧 1:1 比对（能证明是这张脸）", true,
                    "capabilities", "auth", "systemAuth", "faceAuth", "weakAuth", "enforcement"));
            l.add(new Item("code", "TV-08", "60 秒动态码与离线匹配",
                    "正确码通过、重复码判重放、过期/未来码拒绝、篡改码拒绝", true,
                    "rounds", "accepted", "replayRejected", "expiredRejected", "futureRejected",
                    "tamperRejected", "ms"));
            l.add(new Item("roster", "TV-09", "离线花名册本地库",
                    "1,500 户（9,000 行）导入 + 加密落盘 + 查询耗时 + 换版留 .bak + 主文件损坏回退", true,
                    "households", "rows", "plainBytes", "sealedBytes", "coldImportMs", "versionUpgradeMs",
                    "queryP50Us", "queryP95Us", "hasBackup", "corruptFallback", "corruptRecovered",
                    "truncatedTmpIgnored", "totalMs"));
            l.add(new Item("queue", "TV-10", "离线核销队列与幂等",
                    "重复只留一条、乱序不影响终态、跨设备冲突出差异单、单条本地开销（序列化+落盘摊销）", true,
                    "enqueued", "afterNormal", "injectedDuplicates", "duplicateIds", "conflicts",
                    "conflictRows", "droppedServerSide", "rounds", "avgSyncUs", "avgSyncWithDiskUs",
                    "fileBytes", "totalMs"));
            items = l;
        }
        return items;
    }

    public static Item item(String key) {
        for (Item i : items()) if (i.key.equals(key)) return i;
        return null;
    }

    /** 人脸/活体/OCR/NFC 由各自界面回写，报告里同样列出来（只读，不在这里运行）。 */
    private static final String[][] FIELD_RESULTS = {
            {"face", "TV-03", "1:1 人脸比对与活体（核验页自动登记）"},
            {"ocr", "TV-01", "卡背家庭编号与 MRZ（证件页自动登记）"},
            {"nfc", "TV-02 / TV-29", "读芯片与被动认证（证件页自动登记）"},
            {"spoof", "TV-28", "被动反欺诈（未集成专用模型）"}
    };

    // ------------------------------------------------------------ 结论

    public static String verdict(JSONObject r) {
        if (r == null) return "未跑";
        if (r.has("error")) return "出错";
        if (r.has("ok")) return r.optBoolean("ok") ? "通过" : "未通过";
        return "已采集";
    }

    public static int verdictColor(String v) {
        if ("通过".equals(v)) return 0xFF6BE39A;
        if ("未通过".equals(v) || "出错".equals(v)) return 0xFFFF9A9A;
        if ("已采集".equals(v)) return 0xFF7FB2FF;
        return 0xFF9AA6B8;
    }

    /** 一行关键数据，给界面用。 */
    public static String summary(Item it, JSONObject r) {
        if (it == null || r == null) return "";
        if ("env".equals(it.key)) {
            JSONObject d = r.optJSONObject("device");
            JSONObject a = r.optJSONObject("app");
            String model = d == null ? "" : d.optString("model");
            String abi = d == null ? "" : d.optString("abi");
            int sdk = d == null ? 0 : d.optInt("sdk");
            String ver = a == null ? "" : a.optString("versionName");
            return String.format(Locale.US, "%s · %s · sdk %d · v%s", model, abi, sdk, ver);
        }
        if ("trust".equals(it.key)) {
            JSONObject v = r.optJSONObject("verdict");
            if (v == null) return "";
            return String.format(Locale.US, "硬件密钥=%s · 等级=%s · Root=%s · 模拟器=%s",
                    v.optBoolean("hardwareBackedKey"), v.optString("keystoreLevel"),
                    v.optBoolean("rootSuspected"), v.optBoolean("emulatorSuspected"));
        }
        if ("bio".equals(it.key)) {
            JSONObject auth = r.optJSONObject("auth");
            JSONObject enf = r.optJSONObject("enforcement");
            JSONObject weak = r.optJSONObject("weakAuth");
            JSONObject face = r.optJSONObject("faceAuth");
            JSONObject sys = r.optJSONObject("systemAuth");
            String authS = auth == null ? "强=未登录" : (auth.optBoolean("ok")
                    ? "强=已签名 " + auth.optLong("ms") + "ms" : "强=失败：" + auth.optString("reason"));
            String sysS;
            if (sys == null) {
                sysS = "系统认证=未做";
            } else if (!sys.optBoolean("ok")) {
                sysS = "系统认证=失败" + (sys.has("reason") ? "：" + sys.optString("reason") : "");
            } else {
                sysS = "系统认证=" + (sys.optInt("authType", 1) == 2 ? "密码" : "生物识别")
                        + " " + sys.optLong("ms") + "ms";
            }
            String faceS;
            if (face == null) {
                faceS = "前置人脸=未做";
            } else if (!face.optBoolean("ok")) {
                faceS = "前置人脸=未通过" + (face.has("reason") ? "：" + face.optString("reason") : "");
            } else {
                faceS = "前置人脸=活体通过"
                        + (face.optBoolean("identityBound")
                        ? String.format(Locale.US, " 相似度%.2f/阈值%.2f", face.optDouble("similarity"),
                        face.optDouble("threshold"))
                        : "（未登记参照，仅活体）");
            }
            // 旧版（v2.7）的系统弱模态记录，有就照旧带上，没有就不占位置
            String weakS = weak == null ? "" : (" · 旧系统弹窗=" + (weak.optBoolean("ok")
                    ? weak.optLong("ms") + "ms" : "失败"));
            return String.format(Locale.US, "%s · %s · %s%s · 强制=%s", authS, sysS, faceS, weakS,
                    enf == null ? "未测" : (enf.optBoolean("blocked") ? "已拦截" : "未拦截"));
        }
        List<String> parts = new ArrayList<>();
        for (String f : it.fields) {
            if (!r.has(f) || r.isNull(f)) continue;
            Object v = r.opt(f);
            if (v instanceof JSONObject || v instanceof JSONArray) continue;
            parts.add(f + "=" + r.optString(f, ""));
        }
        return DeviceInfo.join(parts, " · ");
    }

    // ------------------------------------------------------------ 报告

    public static JSONObject json(Context c, JSONObject device, JSONObject results) {
        JSONObject o = new JSONObject();
        try {
            o.put("schema", SCHEMA);
            o.put("generatedAt", System.currentTimeMillis());
            o.put("generatedTime", DeviceInfo.now());
            o.put("device", device == null ? new JSONObject() : device);
            o.put("results", results == null ? new JSONObject() : results);
            JSONArray list = new JSONArray();
            for (Item it : items()) {
                JSONObject row = new JSONObject();
                JSONObject r = results == null ? null : results.optJSONObject(it.key);
                row.put("key", it.key);
                row.put("tv", it.tv);
                row.put("name", it.name);
                row.put("verdict", verdict(r));
                row.put("summary", summary(it, r));
                list.put(row);
            }
            o.put("checklist", list);
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static String md(Context c, JSONObject device, JSONObject results) {
        StringBuilder sb = new StringBuilder();
        JSONObject dev = device == null ? new JSONObject() : device;
        JSONObject d = dev.optJSONObject("device");
        JSONObject app = dev.optJSONObject("app");
        JSONObject integ = dev.optJSONObject("integrity");

        sb.append("# 技术验证自测报告（本机实测）\n\n");
        sb.append("生成时间：").append(DeviceInfo.now()).append("\n\n");
        sb.append("| 项 | 值 |\n|---|---|\n");
        sb.append("| 应用 | ").append(app == null ? "" : app.optString("id")).append(' ')
                .append(app == null ? "" : app.optString("versionName")).append(" (")
                .append(app == null ? 0 : app.optLong("versionCode")).append(") |\n");
        sb.append("| 机型 | ").append(d == null ? "" : d.optString("manufacturer")).append(' ')
                .append(d == null ? "" : d.optString("model")).append(" / Android ")
                .append(d == null ? "" : d.optString("release")).append(" / sdk ")
                .append(d == null ? 0 : d.optInt("sdk")).append(" / ")
                .append(d == null ? "" : d.optString("abi")).append(" |\n");
        sb.append("| 安装包签名 | ").append(app == null ? "" : app.optString("signerSha256")).append(" |\n");
        sb.append("| 网络权限 | ").append(app != null && app.optBoolean("networkPermission") ? "有" : "无（全程离线）")
                .append(" |\n");
        sb.append("| Root/模拟器迹象 | ").append(integ == null ? "" :
                ("root=" + integ.optBoolean("rootSuspected") + " emulator=" + integ.optBoolean("emulatorSuspected")))
                .append(" |\n\n");

        sb.append("## 结论汇总\n\n");
        sb.append("| 条目 | 内容 | 结论 | 关键数据 |\n|---|---|---|---|\n");
        for (Item it : items()) {
            JSONObject r = results == null ? null : results.optJSONObject(it.key);
            sb.append("| ").append(it.tv).append(" | ").append(it.name).append(" | **")
                    .append(verdict(r)).append("** | ").append(oneLine(summary(it, r))).append(" |\n");
        }
        for (String[] f : FIELD_RESULTS) {
            JSONObject r = results == null ? null : results.optJSONObject(f[0]);
            sb.append("| ").append(f[1]).append(" | ").append(f[2]).append(" | ")
                    .append(verdict(r)).append(" | ")
                    .append(oneLine(r == null ? "" : DeviceInfo.pick(r,
                            "similarity", "threshold", "liveness", "familyNo", "passed", "access", "apduCount")))
                    .append(" |\n");
        }

        sb.append("\n## 逐项数据\n");
        for (Item it : items()) {
            JSONObject r = results == null ? null : results.optJSONObject(it.key);
            sb.append("\n### ").append(it.tv).append(' ').append(it.name).append(" — ")
                    .append(verdict(r)).append("\n\n");
            sb.append("判据做法：").append(it.hint).append("\n\n");
            if (r == null) {
                sb.append("本机没有这一项的记录（未运行）。\n");
                continue;
            }
            sb.append("关键数据：").append(oneLine(summary(it, r))).append("\n\n");
            sb.append("```json\n").append(pretty(r)).append("\n```\n");
        }

        sb.append("\n## 说明与边界\n\n");
        sb.append("- 本报告全部由**手机本机**跑出来，不联网；没有一行是手工填的。\n");
        sb.append("- 标「未跑」的条目表示这台机器上还没执行过对应自测，不代表不可行。\n");
        sb.append("- 芯片读卡、真实卡背 OCR、相机帧率依赖硬件与真卡，只能在现场确认（见 VERIFICATION.md 的未验证清单）。\n");
        sb.append("- 云端完整性判决（Play Integrity）需要 INTERNET 权限，本包没有申请，因此不提供。\n");
        return sb.toString();
    }

    /** 缩进 JSON（android.jar 里 toString(int) 声明了受检异常，这里统一兜住）。 */
    public static String pretty(JSONObject r) {
        if (r == null) return "";
        try {
            return r.toString(2);
        } catch (Throwable t) {
            return String.valueOf(r);
        }
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        return s.replace("|", "/").replace("\n", " ").trim();
    }
}
