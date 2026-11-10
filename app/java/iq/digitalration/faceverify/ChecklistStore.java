package iq.digitalration.faceverify;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 现场验证模块的本地存储。
 *
 * <p>一个 JSON 文件装三样东西：
 * <pre>
 * {
 *   "meta":     {"tester":"","site":"","notes":"","updated":0},
 *   "results":  {"face":{…},"ocr":{…},"nfc":{…}},   // 各功能最近一次的真实结果
 *   "verdicts": {"ENV-01":{"v":"pass","note":"…","ts":0}, …}
 * }
 * </pre>
 *
 * 全部落在 <code>filesDir/checklist/</code>，不上传、不需要存储权限；
 * 导出时把报告与调试图打成 ZIP 放到 <code>外部 files/checklist-export/</code>。
 */
public final class ChecklistStore {

    private static final String TAG = "ChecklistStore";
    private static final String DIR = "checklist";
    private static final String FILE = "state.json";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Object LOCK = new Object();

    private ChecklistStore() {}

    // ------------------------------------------------------------ paths

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File stateFile(Context c) {
        return new File(dir(c), FILE);
    }

    public static File exportDir(Context c) {
        File base = c.getExternalFilesDir(null);
        if (base == null) base = c.getFilesDir();
        File d = new File(base, "checklist-export");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    // ------------------------------------------------------------ state io

    public static JSONObject state(Context c) {
        synchronized (LOCK) {
            JSONObject o = read(c);
            if (o == null) o = new JSONObject();
            try {
                if (!o.has("meta")) o.put("meta", new JSONObject());
                if (!o.has("results")) o.put("results", new JSONObject());
                if (!o.has("verdicts")) o.put("verdicts", new JSONObject());
            } catch (Throwable ignored) {
            }
            return o;
        }
    }

    private static JSONObject read(Context c) {
        File f = new File(dir(c), FILE);
        if (!f.isFile()) return null;
        try {
            byte[] buf = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) break;
                n += r;
            }
            in.close();
            return new JSONObject(new String(buf, 0, n, UTF8));
        } catch (Throwable t) {
            Log.w(TAG, "read state failed: " + t);
            return null;
        }
    }

    private static void write(Context c, JSONObject o) {
        synchronized (LOCK) {
            File f = new File(dir(c), FILE);
            File tmp = new File(dir(c), FILE + ".tmp");
            try {
                FileOutputStream out = new FileOutputStream(tmp);
                out.write(o.toString().getBytes(UTF8));
                out.flush();
                out.close();
                if (f.exists() && !f.delete()) Log.w(TAG, "delete old state failed");
                if (!tmp.renameTo(f)) {
                    // rename 失败（极少数机型跨挂载点）就退回直接覆盖
                    FileOutputStream o2 = new FileOutputStream(f);
                    o2.write(o.toString().getBytes(UTF8));
                    o2.close();
                    tmp.delete();
                }
            } catch (Throwable t) {
                Log.w(TAG, "write state failed: " + t);
            }
        }
    }

    // ------------------------------------------------------------ results

    /** 存某功能最近一次的结果（face / ocr / nfc）。 */
    public static void putResult(Context c, String key, JSONObject value) {
        if (value == null) return;
        synchronized (LOCK) {
            JSONObject o = state(c);
            try {
                value.put("ts", System.currentTimeMillis());
                value.put("time", DeviceInfo.now());
                o.getJSONObject("results").put(key, value);
                write(c, o);
            } catch (Throwable t) {
                Log.w(TAG, "putResult " + key + " failed: " + t);
            }
        }
    }

    public static JSONObject result(Context c, String key) {
        JSONObject o = state(c);
        JSONObject r = o.optJSONObject("results");
        return r == null ? null : r.optJSONObject(key);
    }

    public static JSONObject results(Context c) {
        JSONObject r = state(c).optJSONObject("results");
        return r == null ? new JSONObject() : r;
    }

    // ------------------------------------------------------------ meta / verdicts

    public static String meta(Context c, String key) {
        JSONObject m = state(c).optJSONObject("meta");
        return m == null ? "" : m.optString(key, "");
    }

    public static void setMeta(Context c, String tester, String site, String notes) {
        synchronized (LOCK) {
            JSONObject o = state(c);
            try {
                JSONObject m = o.getJSONObject("meta");
                m.put("tester", tester == null ? "" : tester);
                m.put("site", site == null ? "" : site);
                m.put("notes", notes == null ? "" : notes);
                m.put("updated", System.currentTimeMillis());
                write(c, o);
            } catch (Throwable ignored) {
            }
        }
    }

    public static String verdict(Context c, String caseId) {
        JSONObject v = verdicts(c).optJSONObject(caseId);
        return v == null ? "" : v.optString("v", "");
    }

    public static String note(Context c, String caseId) {
        JSONObject v = verdicts(c).optJSONObject(caseId);
        return v == null ? "" : v.optString("note", "");
    }

    private static JSONObject verdicts(Context c) {
        JSONObject v = state(c).optJSONObject("verdicts");
        return v == null ? new JSONObject() : v;
    }

    public static void setVerdict(Context c, String caseId, String verdict, String note) {
        synchronized (LOCK) {
            JSONObject o = state(c);
            try {
                JSONObject item = new JSONObject();
                item.put("v", verdict == null ? "" : verdict);
                item.put("note", note == null ? "" : note);
                item.put("ts", System.currentTimeMillis());
                o.getJSONObject("verdicts").put(caseId, item);
                write(c, o);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 清空采集结果与结论，保留执行人/现场（下次现场还能用）。 */
    public static void clearRecords(Context c) {
        synchronized (LOCK) {
            JSONObject o = state(c);
            try {
                o.put("results", new JSONObject());
                o.put("verdicts", new JSONObject());
                write(c, o);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void clearAll(Context c) {
        synchronized (LOCK) {
            write(c, new JSONObject());
            deleteRecursive(dir(c));
            dir(c);
        }
    }

    // ------------------------------------------------------------ export

    /**
     * 把「报告 + 调试图 + 芯片人像」打成一个 ZIP，返回文件。
     *
     * @param reportMd   人读的报告
     * @param reportJson 机读的完整快照
     * @param csv        逐项证据表
     * @param device     设备快照（单独再存一份 device.json）
     */
    public static File exportZip(Context c, String reportMd, String reportJson, String csv, JSONObject device) {
        File out = new File(exportDir(c), "faceverify-verify-" + stamp() + ".zip");
        ZipOutputStream zip = null;
        try {
            OutputStream os = new FileOutputStream(out);
            zip = new ZipOutputStream(os);
            zip.setLevel(9);
            put(zip, "report.md", reportMd);
            put(zip, "report.json", reportJson);
            put(zip, "evidence.csv", csv);
            put(zip, "device.json", device == null ? "{}" : device.toString(2));

            // 调试图：最多带最近 8 张（每张 ~40KB，太多没必要）
            File dbg = new File(c.getExternalFilesDir(null) == null ? c.getFilesDir() : c.getExternalFilesDir(null), "debug");
            File[] pngs = dbg.listFiles((d, n) -> n.startsWith("verify_") && n.endsWith(".png"));
            if (pngs != null && pngs.length > 0) {
                Arrays.sort(pngs, new Comparator<File>() {
                    @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
                });
                int take = Math.min(8, pngs.length);
                for (int i = 0; i < take; i++) addFile(zip, "debug/" + pngs[i].getName(), pngs[i]);
            }

            File chip = new File(dir(c), "chip_face.jpg");
            if (chip.isFile()) addFile(zip, "chip/chip_face.jpg", chip);

            // 采集原始记录（便于我方复核，不含模型）
            File stateFile = stateFile(c);
            if (stateFile.isFile()) addFile(zip, "state.json", stateFile);
        } catch (Throwable t) {
            Log.w(TAG, "export failed: " + t);
        } finally {
            if (zip != null) {
                try { zip.close(); } catch (Throwable ignored) {}
            }
        }
        return out;
    }

    private static void put(ZipOutputStream zip, String name, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write((text == null ? "" : text).getBytes(UTF8));
        zip.closeEntry();
    }

    private static void addFile(ZipOutputStream zip, String name, File f) {
        FileInputStream in = null;
        try {
            zip.putNextEntry(new ZipEntry(name));
            in = new FileInputStream(f);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
            zip.closeEntry();
        } catch (Throwable t) {
            Log.w(TAG, "add " + name + " failed: " + t);
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    /** 芯片 DG2 人像（JPEG）另存一份，作为报告里的实物证据。 */
    public static void saveChipFace(Context c, byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) return;
        try {
            File f = new File(dir(c), "chip_face.jpg");
            FileOutputStream out = new FileOutputStream(f);
            out.write(jpeg);
            out.close();
        } catch (Throwable t) {
            Log.w(TAG, "save chip face failed: " + t);
        }
    }

    private static String stamp() {
        java.text.SimpleDateFormat sdf =
                new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US);
        return sdf.format(new java.util.Date());
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    public static List<File> listExports(Context c) {
        List<File> out = new ArrayList<>();
        File[] fs = exportDir(c).listFiles((d, n) -> n.endsWith(".zip"));
        if (fs != null) {
            Arrays.sort(fs, new Comparator<File>() {
                @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
            });
            out.addAll(Arrays.asList(fs));
        }
        return out;
    }

    /** 读任意文本文件（报告预览用）。 */
    public static String readText(File f, int maxBytes) {
        try {
            if (f == null || !f.isFile()) return "";
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && bo.size() < maxBytes) bo.write(buf, 0, n);
            in.close();
            return bo.toString("UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }
}
