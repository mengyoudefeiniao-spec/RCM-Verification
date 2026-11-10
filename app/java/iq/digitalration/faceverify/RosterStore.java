package iq.digitalration.faceverify;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * 离线花名册下发与本地库（TV-09）。
 *
 * <p>验证的是"能不能把 1,500 户塞进手机、加密落盘、查得快、断电不坏"：
 * <ul>
 *   <li>落盘 = AES-256-GCM（密钥在 Android Keystore，文件被拖走也解不开）；</li>
 *   <li>换版 = 写临时文件 → fsync → 原子 rename，旧版留 .bak，半截文件永远不生效；</li>
 *   <li>索引 = 内存 HashMap（户号 → 记录），并提供 p50/p95 查询耗时；</li>
 *   <li>自测 = 造 1,500 户 → 导入 → 查询 → 故意写坏主文件 → 必须回退 .bak。</li>
 * </ul>
 *
 * <p>正式实现会换成 SQLite/SQLCipher；这里用"加密 JSON + 内存索引"证明指标与恢复策略，
 * 不引入新依赖（工程里没有 SQLCipher）。
 */
public final class RosterStore {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String F_MAIN = "roster.bin";
    private static final String F_BAK = "roster.bak";
    private static final String F_CSV = "roster_test.csv";
    private static final String PREFS = "lab";

    private static Map<String, JSONObject> index;
    private static int version;

    private RosterStore() {}

    // ------------------------------------------------------------ 造数据

    public static File generateCsv(Context c, int households) throws Exception {
        Random rnd = new Random(20261110L);
        String[] centers = {"卡尔赫", "拉希德", "曼苏尔", "阿德哈米亚", "卡迪米亚", "杜拉"};
        StringBuilder sb = new StringBuilder();
        // 第 7 列 member：空 = 户主行；M1..M5 = 该户的成员行（同一个 familyNo，才能归到一户）
        sb.append("familyNo,name,members,center,address,phone,member\n");
        for (int i = 0; i < households; i++) {
            String fam = String.format(Locale.US, "%04d%c%013d", 1000 + (i % 8999),
                    (char) ('A' + rnd.nextInt(26)), 1000000000000L + rnd.nextInt(999999));
            sb.append(fam).append(',')
                    .append("أسرة ").append(i + 1).append(',')
                    .append(2 + rnd.nextInt(9)).append(',')
                    .append(centers[rnd.nextInt(centers.length)]).append(',')
                    .append("محلة ").append(100 + rnd.nextInt(300)).append(" / زقاق ")
                    .append(10 + rnd.nextInt(60)).append(',')
                    .append("07").append(700000000L + rnd.nextInt(99999999)).append(',')
                    .append('\n');
            // 每户补 5 个成员，把单户体积撑到接近真实下发包（约 2.5KB/户）
            for (int m = 0; m < 5; m++) {
                sb.append(fam).append(',')
                        .append("فرد ").append(i + 1).append('-').append(m + 1).append(',')
                        .append(1).append(',').append(centers[0]).append(',')
                        .append("بطاقة ").append(1000000000L + rnd.nextInt(999999999))
                        .append(",—,M").append(m + 1).append('\n');
            }
        }
        File f = LabStore.file(c, F_CSV);
        FileOutputStream out = new FileOutputStream(f);
        out.write(sb.toString().getBytes(UTF8));
        out.flush();
        out.getFD().sync();
        out.close();
        return f;
    }

    // ------------------------------------------------------------ 导入

    public static JSONObject importCsv(Context c, File csv) throws Exception {
        long t0 = System.currentTimeMillis();
        JSONArray lines = new JSONArray();
        Map<String, JSONObject> idx = new HashMap<>();
        List<String> raw = readAll(csv);
        for (int i = 1; i < raw.size(); i++) {
            String line = raw.get(i);
            if (line.trim().isEmpty()) continue;
            String[] f = line.split(",", -1);
            if (f.length < 6) continue;
            String fam = f[0].trim();
            boolean isMember = f.length >= 7 && !f[6].trim().isEmpty();
            JSONObject hh = idx.get(fam);
            if (!isMember) {
                JSONObject o = new JSONObject();
                o.put("familyNo", fam);
                o.put("name", f[1]);
                o.put("members", parseInt(f[2], 1));
                o.put("center", f[3]);
                o.put("address", f[4]);
                o.put("phone", f[5]);
                o.put("memberList", new JSONArray());
                lines.put(o);
                idx.put(fam, o);
            } else {
                if (hh == null) {                    // 成员行先出现：先占位，后到的户主行会覆盖
                    hh = new JSONObject();
                    hh.put("familyNo", fam);
                    hh.put("name", "");
                    hh.put("members", 0);
                    hh.put("center", "");
                    hh.put("address", "");
                    hh.put("phone", "");
                    hh.put("memberList", new JSONArray());
                    lines.put(hh);
                    idx.put(fam, hh);
                }
                JSONObject m = new JSONObject();
                m.put("no", f[6].trim());
                m.put("name", f[1]);
                m.put("card", f[4]);
                hh.getJSONArray("memberList").put(m);
            }
        }
        JSONObject payload = new JSONObject();
        payload.put("version", version + 1);
        payload.put("count", lines.length());
        payload.put("rows", lines);
        // 校验和跟着数据走（写在密文内容里），而不是只存在 SharedPreferences：
        // 否则回退 .bak 时，旧版本的校验和永远和 prefs 里"当前版本"的对不上，恢复必然失败。
        String checksum = DeviceKey.sha256Hex(lines.toString().getBytes(UTF8));
        payload.put("checksum", checksum);
        String plain = payload.toString();
        byte[] sealed = DeviceKey.seal(plain.getBytes(UTF8));

        File main = LabStore.file(c, F_MAIN);
        File bak = LabStore.file(c, F_BAK);
        File tmp = LabStore.file(c, F_MAIN + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        out.write(sealed);
        out.flush();
        out.getFD().sync();          // 断电前必须落盘
        out.close();

        if (main.isFile()) {
            if (bak.exists()) //noinspection ResultOfMethodCallIgnored
                bak.delete();
            //noinspection ResultOfMethodCallIgnored
            main.renameTo(bak);
        }
        boolean renamed = tmp.renameTo(main);
        if (!renamed) throw new IllegalStateException("rename 失败，旧版本保持不变");

        version = version + 1;
        index = idx;
        prefs(c).edit()
                .putInt("roster.version", version)
                .putInt("roster.count", lines.length())
                .putString("roster.sha256", checksum)
                .putLong("roster.size", sealed.length)
                .apply();

        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("rows", lines.length());
        r.put("plainBytes", plain.getBytes(UTF8).length);
        r.put("sealedBytes", sealed.length);
        r.put("version", version);
        r.put("checksum", checksum.substring(0, 16));
        r.put("importMs", System.currentTimeMillis() - t0);
        r.put("csvBytes", csv.length());
        return r;
    }

    // ------------------------------------------------------------ 加载 / 查询

    public static synchronized JSONObject ensureLoaded(Context c) throws Exception {
        if (index != null) {
            JSONObject o = new JSONObject();
            o.put("source", "memory");
            o.put("count", index.size());
            o.put("version", version);
            return o;
        }
        JSONObject main = tryLoad(c, LabStore.file(c, F_MAIN));
        if (main != null) {
            JSONObject o = new JSONObject();
            o.put("source", "main");
            o.put("count", index.size());
            o.put("version", version);
            o.put("checksumOk", true);
            o.put("backup", false);
            return o;
        }
        JSONObject bak = tryLoad(c, LabStore.file(c, F_BAK));
        JSONObject o = new JSONObject();
        if (bak != null) {
            o.put("source", "backup");
            o.put("count", index.size());
            o.put("version", version);
            o.put("checksumOk", true);
            o.put("backup", true);
        } else {
            o.put("source", "none");
            o.put("count", 0);
            o.put("backup", false);
        }
        return o;
    }

    private static JSONObject tryLoad(Context c, File f) {
        if (!f.isFile()) return null;
        try {
            byte[] sealed = readAllBytes(f);
            byte[] plain = DeviceKey.open(sealed);          // AES-GCM：密文被改/被截断这里就抛
            String s = new String(plain, UTF8);
            JSONObject payload = new JSONObject(s);
            JSONArray rows = payload.optJSONArray("rows");
            if (rows == null) return null;
            // 内容校验和写在密文里，跟着版本走 —— .bak 是旧版本也能自证完整
            String expect = payload.optString("checksum", "");
            if (!expect.isEmpty() && !expect.equals(DeviceKey.sha256Hex(rows.toString().getBytes(UTF8)))) {
                return null;
            }
            Map<String, JSONObject> idx = new HashMap<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject o = rows.optJSONObject(i);
                if (o == null) continue;
                idx.put(o.optString("familyNo"), o);
            }
            index = idx;
            version = payload.optInt("version", 1);
            return payload;
        } catch (Throwable t) {
            return null;
        }
    }

    public static JSONObject query(Context c, String familyNo) {
        JSONObject r = new JSONObject();
        long t0 = System.nanoTime();
        try {
            ensureLoaded(c);
            JSONObject hit = index == null ? null : index.get(familyNo);
            r.put("hit", hit != null);
            r.put("ms", (System.nanoTime() - t0) / 1000.0);   // 微秒
            if (hit != null) r.put("row", hit);
        } catch (Throwable t) {
            try {
                r.put("hit", false);
                r.put("error", t.getMessage());
            } catch (Throwable ignored) {
            }
        }
        return r;
    }

    /**
     * 花名册浏览：按户号模糊匹配（不区分大小写），返回前 limit 户的摘要 + 总数。
     * 查询走内存索引，不读磁盘；界面上"查看花名册"就是调这个。
     */
    public static JSONObject browse(Context c, String q, int limit) {
        JSONObject out = new JSONObject();
        try {
            ensureLoaded(c);
            JSONArray items = new JSONArray();
            String query = q == null ? "" : q.trim().toUpperCase(Locale.US);
            int total = index == null ? 0 : index.size();
            int matched = 0;
            if (index != null) {
                List<String> keys = new ArrayList<>(index.keySet());
                Collections.sort(keys);
                for (String k : keys) {
                    if (!query.isEmpty() && !k.toUpperCase(Locale.US).contains(query)) continue;
                    matched++;
                    if (items.length() >= limit) continue;
                    JSONObject o = index.get(k);
                    JSONArray ml = o.optJSONArray("memberList");
                    JSONObject brief = new JSONObject();
                    brief.put("familyNo", o.optString("familyNo"));
                    brief.put("name", o.optString("name"));
                    brief.put("members", o.optInt("members"));
                    brief.put("center", o.optString("center"));
                    brief.put("memberCount", ml == null ? 0 : ml.length());
                    items.put(brief);
                }
            }
            out.put("total", total);
            out.put("matched", matched);
            out.put("shown", items.length());
            out.put("query", query);
            out.put("items", items);
        } catch (Throwable t) {
            try {
                out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    // ------------------------------------------------------------ 自测

    /**
     * 一次性跑完 TV-09 关注的指标并返回，供界面显示与落盘。
     * 注意：会生成 ~1,500 户的测试 CSV 并覆盖当前花名册（只影响本机 lab 目录）。
     *
     * <p>导入跑**两次**：第一次模拟首次下发（此时没有旧库，也就不会有 .bak），
     * 第二次模拟版本切换（旧主库转成 .bak）。只有第二次之后，"主文件损坏 → 回退 .bak"
     * 才有可回退的东西；否则测出来一定是 {@code corruptFallback=none}（v2.5 首版真机就是这样）。
     */
    public static JSONObject runFullTest(Context c, int households) {
        JSONObject out = new JSONObject();
        try {
            long t0 = System.currentTimeMillis();
            File csv = generateCsv(c, households);
            long genMs = System.currentTimeMillis() - t0;

            // 先清干净，保证这是可复现的"首次下发"
            LabStore.delete(c, F_MAIN);
            LabStore.delete(c, F_BAK);
            LabStore.delete(c, F_MAIN + ".tmp");
            index = null;
            version = 0;

            long c1 = System.currentTimeMillis();
            JSONObject imp = importCsv(c, csv);
            long coldMs = System.currentTimeMillis() - c1;

            index = null;
            long c2 = System.currentTimeMillis();
            importCsv(c, csv);                       // 换版：旧主库 → .bak
            long upgradeMs = System.currentTimeMillis() - c2;

            int hhCount = index == null ? 0 : index.size();
            int memCount = 0;
            if (index != null) {
                for (JSONObject o : index.values()) {
                    JSONArray ml = o.optJSONArray("memberList");
                    if (ml != null) memCount += ml.length();
                }
            }
            out.put("csvBytes", csv.length());
            out.put("genCsvMs", genMs);
            out.put("households", hhCount);
            out.put("members", memCount);
            out.put("rows", hhCount + memCount);          // 实际数据行数：户主行 + 成员行
            out.put("plainBytes", imp.optLong("plainBytes"));
            out.put("sealedBytes", imp.optLong("sealedBytes"));
            out.put("coldImportMs", coldMs);
            out.put("versionUpgradeMs", upgradeMs);
            out.put("importMs", coldMs);
            out.put("hasBackup", LabStore.file(c, F_BAK).isFile());
            out.put("importOk", coldMs <= 30000 && upgradeMs <= 30000
                    && LabStore.file(c, F_BAK).isFile());

            // 查询基准：随机取 200 个已有户号
            List<String> keys = new ArrayList<>(index.keySet());
            Collections.shuffle(keys, new Random(7));
            int n = Math.min(200, keys.size());
            double[] us = new double[n];
            int hit = 0;
            for (int i = 0; i < n; i++) {
                long q0 = System.nanoTime();
                JSONObject h = index.get(keys.get(i));
                us[i] = (System.nanoTime() - q0) / 1000.0;
                if (h != null) hit++;
            }
            java.util.Arrays.sort(us);
            double p50 = us[n / 2];
            double p95 = us[Math.min(n - 1, (int) Math.round(n * 0.95))];
            out.put("querySamples", n);
            out.put("queryHitRate", hit * 1.0 / Math.max(1, n));
            out.put("queryP50Us", round2(p50));
            out.put("queryP95Us", round2(p95));
            out.put("queryOk", p95 <= 100000);   // 判据：≤100ms

            // 损坏演练：把主文件写成垃圾，必须回退 .bak
            File main = LabStore.file(c, F_MAIN);
            byte[] good = readAllBytes(main);
            RandomAccessFile raf = new RandomAccessFile(main, "rw");
            raf.setLength(0);
            raf.write(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
            raf.close();

            index = null;
            JSONObject afterCorrupt = ensureLoaded(c);
            out.put("corruptFallback", afterCorrupt.optString("source"));
            out.put("corruptRecovered", "backup".equals(afterCorrupt.optString("source"))
                    && afterCorrupt.optInt("count") > 0);

            // 把主文件恢复，免得影响后续离线包演示
            FileOutputStream fo = new FileOutputStream(main);
            fo.write(good);
            fo.flush();
            fo.close();
            index = null;
            ensureLoaded(c);

            // 半截临时文件：不应影响主库（必须清掉内存索引，否则读到的是缓存，测不到磁盘路径）
            File tmp = LabStore.file(c, F_MAIN + ".tmp");
            FileOutputStream tfo = new FileOutputStream(tmp);
            tfo.write(new byte[]{9, 9, 9});
            tfo.close();
            index = null;
            JSONObject afterTmp = ensureLoaded(c);
            out.put("truncatedTmpIgnored", "main".equals(afterTmp.optString("source"))
                    && afterTmp.optInt("count") > 0);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();

            out.put("ok", out.optBoolean("importOk") && out.optBoolean("queryOk")
                    && out.optBoolean("corruptRecovered") && out.optBoolean("truncatedTmpIgnored"));
            out.put("totalMs", System.currentTimeMillis() - t0);
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    // ------------------------------------------------------------ 小工具

    public static File csvFile(Context c) {
        return LabStore.file(c, F_CSV);
    }

    public static File dbFile(Context c) {
        return LabStore.file(c, F_MAIN);
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static List<String> readAll(File f) throws Exception {
        byte[] b = readAllBytes(f);
        String s = new String(b, UTF8);
        List<String> out = new ArrayList<>();
        String[] parts = s.split("\n");
        for (String p : parts) out.add(p);
        return out;
    }

    private static byte[] readAllBytes(File f) throws Exception {
        byte[] buf = new byte[(int) f.length()];
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        int n = 0;
        while (n < buf.length) {
            int r = in.read(buf, n, buf.length - n);
            if (r < 0) break;
            n += r;
        }
        in.close();
        return buf;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 供界面显示当前库状态；顺带把内存索引建起来（查看明细要用）。 */
    public static JSONObject status(Context c) {
        JSONObject o = new JSONObject();
        try {
            SharedPreferences p = prefs(c);
            o.put("version", p.getInt("roster.version", 0));
            o.put("count", p.getInt("roster.count", 0));
            o.put("size", p.getLong("roster.size", 0));
            o.put("hasFile", LabStore.file(c, F_MAIN).isFile());
            o.put("hasBackup", LabStore.file(c, F_BAK).isFile());
            o.put("mainBytes", LabStore.file(c, F_MAIN).length());
            o.put("backupBytes", LabStore.file(c, F_BAK).length());
            JSONObject loaded = ensureLoaded(c);
            o.put("source", loaded.optString("source"));
            o.put("count", loaded.optInt("count", o.optInt("count")));
            o.put("version", loaded.optInt("version", o.optInt("version")));
            o.put("checksumOk", loaded.optBoolean("checksumOk", false));
            if (index != null) {
                int mem = 0;
                for (JSONObject row : index.values()) {
                    JSONArray ml = row.optJSONArray("memberList");
                    if (ml != null) mem += ml.length();
                }
                o.put("members", mem);
            }
        } catch (Throwable t) {
            try {
                o.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            } catch (Throwable ignored) {
            }
        }
        return o;
    }

    /** 丢掉内存索引（导入/清空后强制下次重新读盘）。 */
    public static synchronized void resetCache() {
        index = null;
        version = 0;
    }

    /** 清空本机花名册文件与索引（不动结果库、不动测试 CSV）。 */
    public static void clearRoster(Context c) {
        resetCache();
        LabStore.delete(c, F_MAIN);
        LabStore.delete(c, F_BAK);
        LabStore.delete(c, F_MAIN + ".tmp");
        try {
            prefs(c).edit().remove("roster.version").remove("roster.count")
                    .remove("roster.size").remove("roster.sha256").apply();
        } catch (Throwable ignored) {
        }
    }
}
