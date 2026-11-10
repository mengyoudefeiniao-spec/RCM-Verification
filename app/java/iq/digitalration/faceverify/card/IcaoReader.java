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
        public boolean ok;
        public String error;
        /** "PACE-ECDH-GM-AES-128 · brainpoolP256r1(参数 13)" 或 "BAC（3DES 安全报文）"。 */
        public String accessControl = "";
        public final DocData doc = new DocData();
        public final Map<Integer, byte[]> dgs = new LinkedHashMap<>();
        public PassiveAuth.Result sod;
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

    /** @param mrzi 24 字符 MRZi（{@link Mrz#mrzInfo()}） */
    public static Result read(CardSession.Transport transport, String mrzi, Progress progress) {
        Result res = new Result();
        long t0 = System.currentTimeMillis();
        CardSession session = new CardSession(transport);
        try {
            step(progress, res, "选择 eMRTD 应用…");
            Apdu.Resp sel = Apdu.parse(transport.transceive(Apdu.selectAid()));
            if (!sel.ok()) {
                throw new IllegalStateException("选择 ICAO 应用失败（" + sel.describe()
                        + "）。请确认贴的是证件芯片一侧，且手机 NFC 天线对准芯片位置");
            }
            res.log.add("应用 A0000002471001 已选中");

            // ---- 先试 BAC（ICAO 9303-11 的老方案，兼容面最广）
            //
            // 现场教训（HONOR BVL-N49 / 2.8-lab-lite）：**先读 EF.CardAccess 会把会话弄死**。
            // 日志显示芯片先回 6982（不开放 EF.CardAccess），紧接着下一条 APDU 就 TagLost，
            // 全程只有 2 条 APDU / 349ms —— 也就是说"为了探测 PACE 先读 CardAccess"这一步
            // 本身触发了芯片复位，后面的 BAC 根本没机会发出去。
            // 所以改成 BAC 优先，PACE 只在 BAC 失败后再探测。
            boolean secured = false;
            String bacError = null;
            step(progress, res, "BAC 认证…");
            try {
                res.accessControl = Bac.establish(session, mrzi);
                secured = true;
            } catch (Exception e) {
                bacError = e.getClass().getSimpleName() + "：" + e.getMessage();
                boolean lost = e.getClass().getSimpleName().contains("TagLost")
                        || (e.getMessage() != null && e.getMessage().contains("Tag was lost"));
                if (lost) throw e;      // 物理断开，换通道也没意义，让上层提示重贴
                res.log.add("BAC 失败（" + bacError + "），改试 PACE");
                // 芯片可能停在半路，重选应用清状态
                Apdu.Resp resel = Apdu.parse(transport.transceive(Apdu.selectAid()));
                if (!resel.ok()) {
                    throw new IllegalStateException("BAC 失败后无法重新选中应用：" + resel.describe());
                }
            }

            if (!secured) {
                step(progress, res, "读取 EF.CardAccess（探测 PACE）…");
                byte[] cardAccess = null;
                try {
                    // optional=true：芯片用 6982/6A82 回绝这个文件时当"不支持 PACE"，不显示成失败
                    cardAccess = session.readFile(EF_CARD_ACCESS, true);
                    if (cardAccess == null) {
                        res.log.add("芯片不开放 EF.CardAccess（6A82/6982），无法走 PACE");
                    }
                } catch (Exception e) {
                    res.log.add("EF.CardAccess 读取异常（" + e.getMessage() + "）");
                }
                List<Pace.Info> paceInfos = Pace.parseCardAccess(cardAccess);
                for (Pace.Info i : paceInfos) res.log.add("芯片声明：" + i);
                Pace.Info chosen = Pace.pick(paceInfos);
                if (chosen == null) {
                    throw new IllegalStateException("BAC 与 PACE 都不可用（BAC：" + bacError + "）");
                }
                step(progress, res, "PACE 认证（" + chosen.oidText + "）…");
                res.accessControl = Pace.establish(session, chosen, mrzi);
                secured = true;
            }
            res.log.add(res.accessControl);
            if (!secured) throw new IllegalStateException("无法建立安全通道");

            // ---- EF.COM
            step(progress, res, "读取 EF.COM…");
            byte[] com = session.readFile(EF_COM);
            res.doc.parseCom(com);

            // ---- DG1 / DG2 / DG11
            List<Integer> want = new ArrayList<>();
            if (!res.doc.dgList.isEmpty()) {
                for (Integer dg : res.doc.dgList) {
                    if (dg == 1 || dg == 2 || dg == 11) want.add(dg);
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

            res.ok = res.doc.mrz != null || res.doc.faceImage != null;
            if (!res.ok) res.error = "已建立安全通道，但没有读到 DG1/DG2（可能是卡片未按 ICAO 组织数据）";
            res.apduCount = session.apduCount;
            res.ms = System.currentTimeMillis() - t0;
            step(progress, res, res.ok ? "完成" : "完成（无数据）");
        } catch (Throwable e) {
            long ms = System.currentTimeMillis() - t0;
            String simple = e.getClass().getSimpleName();
            String raw = simple + "：" + e.getMessage();
            String where = ms + "ms / " + session.apduCount + " 条 APDU";
            if (simple.contains("TagLost") || (e.getMessage() != null && e.getMessage().contains("Tag was lost"))) {
                // 关键：把"实际读了多久"写进提示。现场看到 349ms/2 条 APDU 就不会怀疑"没贴够 10 秒"
                res.error = "读卡中断（TagLost）：本次只走了 " + where + " 就断了——"
                        + "不是贴卡时间不够，是芯片侧会话被复位（选文件被拒、存在性探测、卡片移动都会这样）。"
                        + "请把证件背面贴牢、读取过程中不要移动，重贴会自动再试一次。原始：" + raw;
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
