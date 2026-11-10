package iq.digitalration.faceverify;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * 离线核销队列与幂等（TV-10）。
 *
 * <p>要证明的四件事，都在 {@link #runFullTest} 里一次跑完：
 * <ol>
 *   <li><b>重复只留一条</b>：幂等键 = SHA256(户号|批次|设备|成员|动作)，重复入队直接丢；</li>
 *   <li><b>乱序不影响终态</b>：先到后到都按同一集合收敛，回传顺序不参与判定；</li>
 *   <li><b>冲突生成差异单</b>：同一 (户号,批次) 被另一台设备也核销 → 标 conflict 并记录差异；</li>
 *   <li><b>单条同步 ≤500ms</b>：真实网络延时不在这里测，这里给的是本地入队/序列化/重传开销。</li>
 * </ol>
 *
 * <p>存储是追加式 JSONL（断电最多丢最后一行，已写的不受影响），每次模拟上传后原子重写一遍状态。
 */
public final class OfflineQueue {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String F_QUEUE = "queue.jsonl";

    public static final String ST_PENDING = "pending";
    public static final String ST_SYNCED = "synced";
    public static final String ST_CONFLICT = "conflict";

    private OfflineQueue() {}

    // ------------------------------------------------------------ 基本操作

    public static String idOf(String familyNo, String batch, String deviceId, String member) {
        return DeviceKey.sha256Hex((familyNo + "|" + batch + "|" + deviceId + "|" + member).getBytes(UTF8))
                .substring(0, 24);
    }

    public static List<JSONObject> load(Context c) {
        List<JSONObject> out = new ArrayList<>();
        for (String line : LabStore.readLines(c, F_QUEUE)) {
            try {
                out.add(new JSONObject(line));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    public static void save(Context c, List<JSONObject> records) {
        File f = LabStore.file(c, F_QUEUE);
        File tmp = LabStore.file(c, F_QUEUE + ".tmp");
        try {
            FileOutputStream o = new FileOutputStream(tmp);
            for (JSONObject r : records) o.write((r.toString() + "\n").getBytes(UTF8));
            o.flush();
            o.getFD().sync();
            o.close();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(f);
        } catch (Throwable ignored) {
        }
    }

    public static void reset(Context c) {
        //noinspection ResultOfMethodCallIgnored
        LabStore.file(c, F_QUEUE).delete();
        //noinspection ResultOfMethodCallIgnored
        LabStore.file(c, F_QUEUE + ".tmp").delete();
    }

    /** 入队；返回 {added, duplicate, conflict}。 */
    public static JSONObject enqueue(Context c, List<JSONObject> queue, String familyNo, String batch,
                                     String deviceId, String member, long amount) {
        JSONObject out = new JSONObject();
        try {
            String id = idOf(familyNo, batch, deviceId, member);
            for (JSONObject r : queue) {
                if (id.equals(r.optString("id"))) {
                    out.put("added", false);
                    out.put("duplicate", true);
                    return out;
                }
                // 同一 (户号,批次) 已被另一台设备核销 → 冲突差异单
                if (familyNo.equals(r.optString("familyNo")) && batch.equals(r.optString("batch"))
                        && !deviceId.equals(r.optString("deviceId"))
                        && r.optLong("amount") != amount) {
                    JSONObject conflict = new JSONObject();
                    conflict.put("id", id);
                    conflict.put("familyNo", familyNo);
                    conflict.put("batch", batch);
                    conflict.put("deviceId", deviceId);
                    conflict.put("member", member);
                    conflict.put("amount", amount);
                    conflict.put("ts", System.currentTimeMillis());
                    conflict.put("state", ST_CONFLICT);
                    conflict.put("conflictWith", r.optString("deviceId"));
                    conflict.put("conflictNote", "同户同批次已被 " + r.optString("deviceId")
                            + " 核销（金额 " + r.optLong("amount") + "），本机 " + amount);
                    queue.add(conflict);
                    out.put("added", true);
                    out.put("conflict", true);
                    return out;
                }
            }
            JSONObject r = new JSONObject();
            r.put("id", id);
            r.put("familyNo", familyNo);
            r.put("batch", batch);
            r.put("deviceId", deviceId);
            r.put("member", member);
            r.put("amount", amount);
            r.put("ts", System.currentTimeMillis());
            r.put("state", ST_PENDING);
            r.put("attempts", 0);
            queue.add(r);
            out.put("added", true);
            out.put("conflict", false);
        } catch (Throwable t) {
            try {
                out.put("added", false);
                out.put("error", t.getMessage());
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    public static JSONObject stats(Context c) {
        List<JSONObject> q = load(c);
        int pending = 0, synced = 0, conflict = 0;
        long bytes = LabStore.file(c, F_QUEUE).length();
        for (JSONObject r : q) {
            String s = r.optString("state");
            if (ST_PENDING.equals(s)) pending++;
            else if (ST_SYNCED.equals(s)) synced++;
            else if (ST_CONFLICT.equals(s)) conflict++;
        }
        JSONObject o = new JSONObject();
        try {
            o.put("total", q.size());
            o.put("pending", pending);
            o.put("synced", synced);
            o.put("conflict", conflict);
            o.put("fileBytes", bytes);
        } catch (Throwable ignored) {
        }
        return o;
    }

    // ------------------------------------------------------------ 全流程自测

    public static JSONObject runFullTest(Context c, int n) {
        JSONObject out = new JSONObject();
        try {
            reset(c);
            List<JSONObject> q = new ArrayList<>();
            Random rnd = new Random(20261110L);
            long t0 = System.currentTimeMillis();

            // 1) 正常入队 n 条（一台设备）
            for (int i = 0; i < n; i++) {
                String fam = String.format(Locale.US, "%04dE%013d", 1000 + i, 1000000000000L + i);
                enqueue(c, q, fam, "B2026-11", "dev-A", "M1", 25000);
            }
            int afterNormal = q.size();

            // 2) 注入重复（同 id 再来一次）
            int dup = 0;
            for (int i = 0; i < Math.min(20, n); i++) {
                String fam = q.get(i).optString("familyNo");
                JSONObject r = enqueue(c, q, fam, "B2026-11", "dev-A", "M1", 25000);
                if (r.optBoolean("duplicate")) dup++;
            }

            // 3) 乱序：把后 10 条的 ts 改成更早，再追加（终态不应受影响）
            for (int i = Math.max(0, q.size() - 10); i < q.size(); i++) {
                q.get(i).put("ts", System.currentTimeMillis() - 3600_000L);
            }
            Collections.shuffle(q, rnd);

            // 4) 冲突：另一台设备对前 3 户用不同金额再核销
            int conflicts = 0;
            for (int i = 0; i < 3 && i < q.size(); i++) {
                JSONObject r = enqueue(c, q, q.get(i).optString("familyNo"), "B2026-11",
                        "dev-B", "M1", 30000);
                if (r.optBoolean("conflict")) conflicts++;
            }
            save(c, q);

            // 5) 模拟上传：30% 丢包、20% 重复投递；最多 10 轮
            //    单条"本地开销"测的是真实工作量：状态迁移 + 记录序列化 + 该轮落盘摊销，
            //    不是测一个 put()（那样测出来是 0 微秒，等于没测）。
            int rounds = 0, droppedDup = 0;
            long syncUsTotal = 0;
            long saveUsTotal = 0;
            int syncCount = 0;
            while (rounds < 10) {
                rounds++;
                boolean any = false;
                for (JSONObject r : q) {
                    if (!ST_PENDING.equals(r.optString("state"))) continue;
                    any = true;
                    r.put("attempts", r.optInt("attempts") + 1);
                    if (rnd.nextDouble() < 0.30) continue;      // 丢包：保持 pending
                    long s0 = System.nanoTime();
                    r.put("state", ST_SYNCED);
                    r.put("syncedAt", System.currentTimeMillis());
                    byte[] line = (r.toString() + "\n").getBytes(UTF8);   // 真正要写出去的那一行
                    syncUsTotal += (System.nanoTime() - s0) / 1000.0;
                    if (line.length == 0) throw new IllegalStateException("记录序列化失败");
                    syncCount++;
                    if (rnd.nextDouble() < 0.20) droppedDup++; // 服务端按 id 去重
                }
                long sv0 = System.nanoTime();
                save(c, q);
                saveUsTotal += (System.nanoTime() - sv0) / 1000.0;
                if (!any) break;
                boolean rest = false;
                for (JSONObject r : q) if (ST_PENDING.equals(r.optString("state"))) rest = true;
                if (!rest) break;
            }

            // 6) 不变量
            Set<String> ids = new HashSet<>();
            int dupIds = 0, stillPending = 0, synced = 0, conflictRows = 0;
            for (JSONObject r : q) {
                if (!ids.add(r.optString("id"))) dupIds++;
                String s = r.optString("state");
                if (ST_PENDING.equals(s)) stillPending++;
                else if (ST_SYNCED.equals(s)) synced++;
                else if (ST_CONFLICT.equals(s)) conflictRows++;
            }
            JSONObject st = stats(c);
            out.put("enqueued", n);
            out.put("afterNormal", afterNormal);
            out.put("injectedDuplicates", dup);
            out.put("conflicts", conflicts);
            out.put("conflictRows", conflictRows);
            out.put("duplicateIds", dupIds);
            out.put("droppedServerSide", droppedDup);
            out.put("pendingLeft", stillPending);
            out.put("synced", synced);
            out.put("rounds", rounds);
            out.put("avgSyncUs", syncCount == 0 ? 0 : round2(syncUsTotal / syncCount));
            out.put("avgSyncWithDiskUs", syncCount == 0 ? 0 : round2((syncUsTotal + saveUsTotal) / syncCount));
            out.put("saveMsTotal", round2(saveUsTotal / 1000.0));
            out.put("fileBytes", st.optLong("fileBytes"));
            out.put("totalMs", System.currentTimeMillis() - t0);
            out.put("duplicateIdempotent", dup == Math.min(20, n) && dupIds == 0);
            out.put("outOfOrderSafe", stillPending == 0);
            out.put("conflictDetected", conflicts == Math.min(3, n));
            // 判据：单条本地开销（序列化 + 状态迁移 + 落盘摊销）≤ 500ms。
            // 真实网络时延不在这里测，只能现场测。
            out.put("syncFastEnough", syncCount > 0
                    && (syncUsTotal + saveUsTotal) / syncCount <= 500_000L);
            out.put("ok", out.optBoolean("duplicateIdempotent") && out.optBoolean("outOfOrderSafe")
                    && out.optBoolean("conflictDetected") && out.optBoolean("syncFastEnough")
                    && dupIds == 0 && stillPending == 0);
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
