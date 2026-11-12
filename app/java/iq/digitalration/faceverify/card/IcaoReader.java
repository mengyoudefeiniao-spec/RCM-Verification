package iq.digitalration.faceverify.card;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ICAO 9303 电子证件读取流程：选应用 → （PACE / BAC）→ 读 EF.COM / DG1 / DG2 / DG11 / EF.SOD → 被动认证。
 *
 * <p>与 Android 无关：传输层是 {@link CardSession.Transport}，因此可以用"假芯片"在 PC 上跑通全流程
 * （tools/validate/NfcTest.java 就是这么做的）。
 */
public final class IcaoReader {

    public interface Progress {
        void step(String text);
    }

    public static final class Result {
        /** 失败类型：上层必须靠这个（而不是解析 error 字符串）决定下一轮怎么重试。 */
        public static final int FAIL_NONE = 0;
        /** 通信中断/超时（IOException、TagLostException、APDU 超时）——芯片没给状态码，重贴后应**继续 BAC**。 */
        public static final int FAIL_TRANSPORT = 1;
        /** 芯片明确用状态码拒绝（6300 密钥错、6A81 不支持、6982 安全状态不满足…）——换通道才有意义。 */
        public static final int FAIL_PROTOCOL = 2;

        public boolean ok;
        public String error;
        public int failureKind = FAIL_NONE;
        /** 失败发生在哪一阶段："SELECT" / "BAC" / "PACE" / "READ"（成功时为空）。 */
        public String failStage = "";
        /** "PACE-ECDH-GM-AES-128 · brainpoolP256r1(参数 13)" 或 "BAC（3DES 安全报文）"。 */
        public String accessControl = "";
        public final DocData doc = new DocData();
        public final Map<Integer, byte[]> dgs = new LinkedHashMap<>();
        public PassiveAuth.Result sod;
        /** 主动认证（DG15 + INTERNAL AUTHENTICATE）：证明芯片本体不是复制品。 */
        public ActiveAuth.Result aa;
        public final List<String> log = new ArrayList<>();
        public int apduCount;
        public long ms;

        public String faceInfo() {
            if (doc.faceImage == null) return "无";
            return String.format(Locale.US, "%s · %d 字节", doc.faceFormat, doc.faceImage.length);
        }
    }

    private static final int EF_COM = 0x011E;
    private static final int EF_SOD = 0x011D;
    private static final int EF_CARD_ACCESS = 0x011C;

    private IcaoReader() {}

    /** 自动：先 BAC，失败再 PACE。 */
    public static final int MODE_AUTO = 0;
    /** 跳过 BAC，直接 PACE（含拿不到 EF.CardAccess 时的盲试）——用于"BAC 超时"后的第二次尝试。 */
    public static final int MODE_PACE_ONLY = 1;

    /** @param mrzi 24 字符 MRZi（{@link Mrz#mrzInfo()}） */
    public static Result read(CardSession.Transport transport, String mrzi, Progress progress) {
        return read(transport, mrzi, progress, MODE_AUTO, null);
    }

    public static Result read(CardSession.Transport transport, String mrzi, Progress progress, int mode) {
        return read(transport, mrzi, progress, mode, null);
    }

    /**
     * @param can 证件正面的 CAN 码（Card Access Number）。卡片把 PACE 密码设成 CAN 时必须提供，
     *            否则只能试 MRZ；为空表示没有 CAN。
     */
    public static Result read(CardSession.Transport transport, String mrzi, Progress progress,
                              int mode, String can) {
        Result res = new Result();
        long t0 = System.currentTimeMillis();
        CardSession session = new CardSession(transport);
        String stage = "SELECT";       // 失败时写进 failStage：上层据此判断"断在哪一步"
        try {
            boolean secured = false;
            String bacError = "";
            String paceError = "";
            boolean usedPace = false;
            boolean haveCan = can != null && !can.trim().isEmpty();

            // ---- 1) PACE 优先（§4.4：PACE 在主文件里确立安全环境）
            //
            // 现场（idcard-diag-20261111-081310 与更早的日志）：先发 BAC 的 `00 82` 有概率让芯片
            // 掉电复位，一旦 TagLost，整张贴卡就废了；而 PACE-ECDH-GM-AES-128 的运算量与功耗都低于
            // BAC 的 3DES 三向认证。所以顺序改成：
            //   贴卡 → SELECT MF 3F00 → 读 EF.CardAccess(011C) → MSE:Set AT + GA（都在 MF 下）
            //   → 只有 PACE 明确失败（状态码，不是断连）才 SELECT AID 回退 BAC。
            stage = "PACE";
            step(progress, res, "选择主文件并读取 EF.CardAccess…");
            byte[] cardAccess = null;
            try {
                // §9.2.11：EF.CardAccess 在**主文件**下，不在 eMRTD 应用 DF 里
                Apdu.Resp mf = session.send(0xA4, 0x00, 0x0C, new byte[]{0x3F, 0x00}, null);
                if (mf.ok()) {
                    cardAccess = session.readFile(EF_CARD_ACCESS, true);
                    if (cardAccess == null) {
                        res.log.add("芯片不开放 EF.CardAccess（6A82/6982），PACE 不可用");
                        paceError = "EF.CardAccess 被拒（6A82/6982）";
                    }
                } else {
                    res.log.add("选择主文件失败（" + mf.describe() + "），PACE 不可用");
                    paceError = "无法选择主文件（" + mf.describe() + "）";
                }
            } catch (Exception e) {
                boolean lost = e instanceof java.io.IOException
                        || (e.getMessage() != null && e.getMessage().contains("Tag was lost"));
                if (lost) throw e;
                res.log.add("EF.CardAccess 读取异常（" + e.getMessage() + "）");
                paceError = "EF.CardAccess 读取异常（" + e.getMessage() + "）";
            }

            if (!secured) {
                // 这里**不要** selectAid：§4.4 要求 PACE 在主文件里建立安全环境，
                // MSE:Set AT 与 GA 必须保持 MF（3F00）上下文。
                List<Pace.Info> paceInfos = Pace.parseCardAccess(cardAccess);
                for (Pace.Info i : paceInfos) res.log.add("芯片声明：" + i);
                Pace.Info chosen = Pace.pick(paceInfos);
                if (chosen != null) {
                    // 芯片声明了 PACE：**优先 MRZ**（OCR 的 MRZi 已过校验位，比人工输 CAN 更可靠，
                    // 且 AES-128 比 BAC 的 3DES 省电）；MRZ 被拒时才试 CAN。
                    int[] refs2 = (can == null || can.trim().isEmpty())
                            ? new int[]{Pace.PWD_MRZ}
                            : new int[]{Pace.PWD_MRZ, Pace.PWD_CAN};
                    for (int ref2 : refs2) {
                        String pwd2 = ref2 == Pace.PWD_CAN ? can.trim() : mrzi;
                        if (pwd2 == null || pwd2.isEmpty()) continue;
                        step(progress, res, "PACE 认证（" + chosen.oidText + " · 密码 " + Pace.pwdName(ref2) + "）…");
                        try {
                            res.accessControl = Pace.establish(session, chosen, pwd2, ref2);
                            secured = true;
                            usedPace = true;
                            break;
                        } catch (Exception e) {
                            String em = e.getMessage() == null ? "" : e.getMessage();
                            res.log.add("PACE（" + Pace.pwdName(ref2) + "）失败：" + em);
                            // 首个 GA 就 6800（密码还没参与运算）＝芯片的 PACE 实现根本不接受该命令，
                            // 换 CAN/MRZ 重试不可能有用，直接收手，别再耗贴卡时间。
                            if (em.contains("nonce") && em.contains("6800")) {
                                res.log.add("首个 General Authenticate 即 6800：判定该芯片 PACE 不可用，停止换密码重试");
                                paceError = "芯片对 GENERAL AUTHENTICATE 回 6800（PACE 实际不可用）";
                                break;
                            }
                            // 换密码重试仍在 MF 下进行（PACE 的安全环境在 MF）
                            Apdu.Resp re2 = session.send(0xA4, 0x00, 0x0C, new byte[]{0x3F, 0x00}, null);
                            if (!re2.ok()) re2 = selectAid(session);
                            if (!re2.ok()) throw new IllegalStateException("PACE 失败后无法重选文件系统：" + re2.describe());
                        }
                    }
                    if (!secured) paceError = "PACE（CAN/MRZ）均未通过";
                } else if (cardAccess != null) {
                    // 芯片把 EF.CardAccess 交出来了、但里面没有可用的 ECDH-GM 条目：
                    // §4.2 要求"按芯片声明的参数选择"，此时再去盲试未声明的曲线/算法既不合规、
                    // 现场也从未成功过（每次都是 Get Nonce 6800），直接判定 PACE 不可用。
                    res.log.add("芯片声明里没有可用的 ECDH-GM 参数，不再盲试未声明组合");
                    paceError = "芯片声明的 PACE 参数不含 ECDH-GM";
                } else if (mode == MODE_PACE_ONLY) {
                    // 只有显式"只走 PACE"时才盲试（上层已确认 BAC 不可用）。MODE_AUTO 下不盲试：
                    // 没有 EF.CardAccess 就直接回退 BAC，别在 14×2 组合上白耗贴卡时间。
                    List<Pace.Info> cands = Pace.defaultCandidates();
                    String canCode = can == null ? "" : can.trim();
                    int[] refs = canCode.isEmpty()
                            ? new int[]{Pace.PWD_MRZ}
                            : new int[]{Pace.PWD_MRZ, Pace.PWD_CAN};
                    step(progress, res, "PACE 盲试（芯片未提供 EF.CardAccess，共 " + cands.size() + " 种组合 × "
                            + refs.length + " 种密码" + (canCode.isEmpty() ? "：MRZ" : "：CAN + MRZ") + "）…");
                    boolean done = false;
                    boolean gaRejected6800 = false;   // 首个 General Authenticate 就被协议层 6800 拒绝
                    boolean anyGa = false;
                    for (Pace.Info info : cands) {
                        if (done || gaRejected6800) break;
                        for (int ref : refs) {
                            String pwd = ref == Pace.PWD_CAN ? canCode : mrzi;
                            if (pwd == null || pwd.isEmpty()) continue;
                            // 盲试同样要在主文件下做（PACE 的安全环境在 MF）：先选 MF，选不到才退回应用 DF
                            Apdu.Resp re = session.send(0xA4, 0x00, 0x0C, new byte[]{0x3F, 0x00}, null);
                            if (!re.ok()) re = selectAid(session);
                            if (!re.ok()) throw new IllegalStateException("盲试前重选文件系统失败：" + re.describe());
                            if (!Pace.probe(session, info, ref)) continue;
                            res.log.add("芯片接受 PACE 组合：" + info.oidText + " / 参数 " + info.paramId
                                    + " / 密码 " + Pace.pwdName(ref));
                            try {
                                res.accessControl = Pace.establish(session, info, pwd, ref);
                                secured = true;
                                usedPace = true;
                                done = true;
                                break;
                            } catch (Exception e) {
                                String em = e.getMessage() == null ? "" : e.getMessage();
                                res.log.add("PACE 建立失败：" + em);
                                // 现场日志（idcard-diag-20261110-143055）：MSE:Set AT 回 9000，
                                // 但紧接着的 Get Nonce 一律 6800——密码在这一步还没参与运算，
                                // 换曲线/换密码参考都不可能过。继续跑完 14×2 组合只是白等
                                // 33 条 APDU / 1.8 秒，手持贴卡最容易在这一段掉线。
                                if (!anyGa && em.contains("nonce") && em.contains("6800")) {
                                    gaRejected6800 = true;
                                }
                                anyGa = true;
                                if (gaRejected6800) break;
                            }
                        }
                    }
                    if (gaRejected6800) {
                        res.log.add("首个 General Authenticate 返回 6800：芯片在密码校验前就拒绝 PACE，"
                                + "判定不支持 PACE，停止盲试（不再试其它曲线/密码）");
                        paceError = "首个 General Authenticate 被 6800 拒绝，判定芯片不支持 PACE";
                    } else if (!secured) {
                        paceError = "常见 PACE 组合盲试均失败";
                    }
                } else {
                    res.log.add("没有 EF.CardAccess：PACE 无从下手，直接回退 BAC");
                    paceError = "无可用的 EF.CardAccess";
                }
            }

            // ---- 2) PACE 明确失败后，才回退 BAC（并且先选回 eMRTD 应用）
            if (!secured && mode == MODE_AUTO) {
                stage = "BAC";
                step(progress, res, "PACE 不可用，回退 BAC 认证（MRZ 密码）…");
                Apdu.Resp backApp = selectAid(session);
                if (!backApp.ok()) {
                    throw new IllegalStateException("回退 BAC 前无法选中 eMRTD 应用：" + backApp.describe());
                }
                try {
                    res.accessControl = Bac.establish(session, mrzi);
                    secured = true;
                } catch (Exception e) {
                    bacError = e.getClass().getSimpleName() + "：" + e.getMessage();
                    boolean lost = e.getClass().getSimpleName().contains("TagLost")
                            || (e.getMessage() != null && e.getMessage().contains("Tag was lost"));
                    if (lost) throw e;      // 物理断开，换通道也没意义，让上层提示重贴
                    res.log.add("BAC 失败（" + bacError + "）");
                }
            } else if (!secured) {
                bacError = "MODE_PACE_ONLY：未尝试 BAC";
            }
            if (!secured) {
                throw new IllegalStateException("PACE 与 BAC 都不可用（PACE："
                        + (paceError.isEmpty() ? "未建立" : paceError)
                        + "；BAC：" + (bacError.isEmpty() ? "未尝试" : bacError)
                        + (can == null || can.trim().isEmpty()
                                ? "；如果证件正面有 CAN 码，请先输入 CAN 再试" : "") + "）");
            }
            if (usedPace) {
                // §4.2 注：PACE 是在主文件里建立安全环境的，成功后当前 DF 是 MF；
                // 读 EF.COM / DG1 / DG2 之前必须选回 eMRTD 应用（此时已在 SM 下）。
                Apdu.Resp backApp = selectAid(session);
                if (!backApp.ok()) {
                    throw new IllegalStateException("PACE 后重新选择 eMRTD 应用失败：" + backApp.describe());
                }
            }
            res.log.add(res.accessControl);
            if (!secured) throw new IllegalStateException("无法建立安全通道");
            stage = "READ";

            // ---- EF.COM
            step(progress, res, "读取 EF.COM…");
            byte[] com = session.readFile(EF_COM);
            res.doc.parseCom(com);

            // ---- DG1 / DG2 / DG11
            List<Integer> want = new ArrayList<>();
            if (!res.doc.dgList.isEmpty()) {
                for (Integer dg : res.doc.dgList) {
                    // 15 是主动认证公钥（AA），多读一个几百字节的文件换"防克隆"能力，很划算
                    if (dg == 1 || dg == 2 || dg == 11 || dg == 15) want.add(dg);
                }
            } else {
                want.add(1);
                want.add(2);
                want.add(11);
            }
            int fid = 0x0101;
            for (Integer dg : want) {
                step(progress, res, "读取 DG" + dg + "…");
                int fileId = fid + dg - 1;
                try {
                    byte[] data = session.readFile(fileId);
                    if (data == null) {
                        res.log.add("DG" + dg + " 不存在，跳过");
                        continue;
                    }
                    res.dgs.put(dg, data);
                    res.log.add("DG" + dg + "：" + data.length + " 字节");
                } catch (Exception e) {
                    res.log.add("DG" + dg + " 读取失败：" + e.getMessage());
                }
            }
            res.doc.parseDg1(res.dgs.get(1));
            if (res.dgs.containsKey(2)) res.doc.parseDg2(res.dgs.get(2));
            if (res.dgs.containsKey(11)) res.doc.parseDg11(res.dgs.get(11));

            // ---- EF.SOD + 被动认证
            step(progress, res, "读取 EF.SOD 并做被动认证…");
            byte[] sod = null;
            try {
                sod = session.readFile(EF_SOD);
            } catch (Exception e) {
                res.log.add("EF.SOD 读取失败：" + e.getMessage());
            }
            res.sod = PassiveAuth.verify(sod, res.dgs);
            for (String d : res.sod.details) res.log.add("SOD：" + d);

            // ---- DG15 存在时做主动认证（§5.3）：随机挑战 + 芯片内部私钥签名。
            //      放在 SOD 之后是因为它失败也不影响已读到的数据；芯片没声明 DG15 就完全不发命令。
            if (res.dgs.containsKey(15)) {
                step(progress, res, "主动认证（AA）…");
                res.aa = ActiveAuth.run(session, res.dgs.get(15));
                res.log.add("AA：" + res.aa.summary());
                for (String d : res.aa.details) res.log.add("AA：" + d);
            } else {
                res.log.add("AA：芯片未声明 DG15，跳过（该证件不支持防克隆认证）");
            }

            res.ok = res.doc.mrz != null || res.doc.faceImage != null;
            if (!res.ok) res.error = "已建立安全通道，但没有读到 DG1/DG2（可能是卡片未按 ICAO 组织数据）";
            res.apduCount = session.apduCount;
            res.ms = System.currentTimeMillis() - t0;
            step(progress, res, res.ok ? "完成" : "完成（无数据）");
        } catch (Throwable e) {
            long ms = System.currentTimeMillis() - t0;
            String simple = e.getClass().getSimpleName();
            String msg = e.getMessage() == null ? "" : e.getMessage();
            String raw = simple + "：" + e.getMessage();
            String where = ms + "ms / " + session.apduCount + " 条 APDU";

            // 失败分类（上层重试策略的唯一依据）：
            //   通信异常/超时 —— 芯片压根没给状态码（TagLostException / IOException / APDU 超时），
            //   下一次贴卡必须**继续 BAC**；   明确状态码拒绝（IllegalStateException 里带 6300/6A81/6982…）
            //   才是"协议不支持/密钥错误"，那时换通道才有意义。
            boolean transportFail = e instanceof java.io.IOException
                    || simple.contains("TagLost") || simple.contains("IOException")
                    || simple.contains("Timeout") || simple.contains("SecurityException")
                    || msg.contains("Tag was lost")
                    || msg.toLowerCase(Locale.US).contains("timeout");
            res.failureKind = transportFail ? Result.FAIL_TRANSPORT : Result.FAIL_PROTOCOL;
            res.failStage = stage;

            if (simple.contains("TagLost") || msg.contains("Tag was lost")) {
                // 关键：把"实际读了多久"写进提示。现场看到 349ms/2 条 APDU 就不会怀疑"没贴够 10 秒"
                res.error = "读卡中断（TagLost）：本次只走了 " + where + " 就断了——"
                        + "不是贴卡时间不够，是芯片侧会话被复位（选文件被拒、存在性探测、卡片移动都会这样）。"
                        + "请把证件背面贴牢、读取过程中不要移动；重贴后会**继续从 BAC 开始**重试。原始：" + raw;
            } else if (simple.contains("IOException") || simple.contains("SecurityException")) {
                res.error = "读卡中断（" + where + "）：" + raw
                        + "（贴合不稳、手机壳太厚或卡片被复位都会这样；重贴再试一次）";
            } else {
                res.error = raw + "（" + where + "）";
            }
            res.apduCount = session.apduCount;
            res.ms = ms;
            // 原始错误始终留在日志里（现场截图就能定位），面向用户的提示放在 error 里
            res.log.add("失败：" + raw + " @ " + where);
            step(progress, res, "失败");
        }
        res.log.addAll(session.log);
        return res;
    }

    /**
     * 选 eMRTD 应用。**必须走 session**：这样 `apduCount` 与原始 APDU 日志的编号
     * 和现场说的"第 1 条 SELECT / 第 2 条 GET CHALLENGE / 第 3 条 EXTERNAL AUTHENTICATE"
     * 完全对应（以前这条绕过 session，导致日志里"2 条 APDU"其实已经发了 3 条）。
     */
    private static Apdu.Resp selectAid(CardSession session) throws Exception {
        return session.send(0xA4, 0x04, 0x0C, Tlv.unhex("A0000002471001"), null);
    }

    private static void step(Progress p, Result res, String text) {
        res.log.add(text);
        if (p != null) {
            try {
                p.step(text);
            } catch (Throwable ignored) {
            }
        }
    }
}
