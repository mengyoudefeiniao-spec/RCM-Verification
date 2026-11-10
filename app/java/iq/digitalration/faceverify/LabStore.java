package iq.digitalration.faceverify;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 验证实验室的本地结果库：一个 JSON 文件装所有模块的最近一次结果。
 *
 * <pre>
 * { "meta": {...}, "results": { "trust": {...}, "bio": {...}, "roster": {...}, … } }
 * </pre>
 *
 * 落在 <code>filesDir/lab/lab.json</code>，不上传；导出时打 ZIP 到外部 files/lab-export/。
 */
public final class LabStore {

    private static final String TAG = "LabStore";
    private static final String DIR = "lab";
    private static final String FILE = "lab.json";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Object LOCK = new Object();

    private LabStore() {}

    // ------------------------------------------------------------ paths

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File file(Context c, String name) {
        return new File(dir(c), name);
    }

    public static File exportDir(Context c) {
        File base = c.getExternalFilesDir(null);
        if (base == null) base = c.getFilesDir();
        File d = new File(base, "lab-export");
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
            Log.w(TAG, "read failed: " + t);
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
                if (f.exists() && !f.delete()) Log.w(TAG, "delete old failed");
                if (!tmp.renameTo(f)) {
                    FileOutputStream o2 = new FileOutputStream(f);
                    o2.write(o.toString().getBytes(UTF8));
                    o2.close();
                    tmp.delete();
                }
            } catch (Throwable t) {
                Log.w(TAG, "write failed: " + t);
            }
        }
    }

    // ------------------------------------------------------------ results

    public static void put(Context c, String key, JSONObject value) {
        if (value == null) return;
        synchronized (LOCK) {
            JSONObject o = state(c);
            try {
                value.put("ts", System.currentTimeMillis());
                value.put("time", DeviceInfo.now());
                o.getJSONObject("results").put(key, value);
                write(c, o);
            } catch (Throwable t) {
                Log.w(TAG, "put " + key + " failed: " + t);
            }
        }
    }

    public static JSONObject get(Context c, String key) {
        JSONObject r = state(c).optJSONObject("results");
        return r == null ? null : r.optJSONObject(key);
    }

    public static JSONObject all(Context c) {
        JSONObject r = state(c).optJSONObject("results");
        return r == null ? new JSONObject() : r;
    }

    public static String meta(Context c, String key) {
        JSONObject m = state(c).optJSONObject("meta");
        return m == null ? "" : m.optString(key, "");
    }

    public static void setMeta(Context c, String key, String value) {
        synchronized (LOCK) {
            JSONObject o = state(c);
            try {
                o.getJSONObject("meta").put(key, value == null ? "" : value);
                o.getJSONObject("meta").put("updated", System.currentTimeMillis());
                write(c, o);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void clear(Context c) {
        synchronized (LOCK) {
            JSONObject o = state(c);
            try {
                o.put("results", new JSONObject());
                write(c, o);
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------ export

    public static File exportZip(Context c, String reportMd, String reportJson, JSONObject device) {
        File out = new File(exportDir(c), "faceverify-lab-" + stamp() + ".zip");
        ZipOutputStream zip = null;
        try {
            zip = new ZipOutputStream(new FileOutputStream(out));
            zip.setLevel(9);
            put(zip, "report.md", reportMd);
            put(zip, "report.json", reportJson);
            put(zip, "device.json", device == null ? "{}" : device.toString(2));
            File st = new File(dir(c), FILE);
            if (st.isFile()) addFile(zip, "lab.json", st);

            File dbg = new File(c.getExternalFilesDir(null) == null ? c.getFilesDir() : c.getExternalFilesDir(null), "debug");
            File[] pngs = dbg.listFiles((d, n) -> n.startsWith("verify_") && n.endsWith(".png"));
            if (pngs != null && pngs.length > 0) {
                Arrays.sort(pngs, new Comparator<File>() {
                    @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
                });
                for (int i = 0; i < Math.min(6, pngs.length); i++) addFile(zip, "debug/" + pngs[i].getName(), pngs[i]);
            }

            File spoof = new File(dir(c), "spoof_samples.jsonl");
            if (spoof.isFile()) addFile(zip, "spoof/samples.jsonl", spoof);
            File chip = new File(dir(c), "chip_face.jpg");
            if (chip.isFile()) addFile(zip, "chip/chip_face.jpg", chip);

            // 读卡日志随报告一起走：现场只要导一次 ZIP，NFC 失败的那次也在里面
            File nfcLog = latestNfcLog(c);
            if (nfcLog != null && nfcLog.isFile()) addFile(zip, "nfc/" + nfcLog.getName(), nfcLog);
            // 卡背 OCR 日志同理（每行原文，用来分析"看着清楚却不识别"）
            File ocrLog = latestLog(c, "ocr-log-");
            if (ocrLog != null && ocrLog.isFile()) addFile(zip, "ocr/" + ocrLog.getName(), ocrLog);
        } catch (Throwable t) {
            Log.w(TAG, "export failed: " + t);
        } finally {
            if (zip != null) try { zip.close(); } catch (Throwable ignored) {}
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

    /**
     * 读卡日志：写到导出目录 {@code nfc-log-<时间戳>.txt}。
     *
     * <p>为什么单独存文件：现场读卡失败时，界面上的日志截不全（要滚动），
     * 而这份 txt 可以像验证报告一样直接分享出来，也随「导出验证报告」ZIP 一起带走。
     */
    public static File saveNfcLog(Context c, String text) {
        try {
            File f = new File(exportDir(c), "nfc-log-" + stamp() + ".txt");
            FileOutputStream out = new FileOutputStream(f);
            out.write((text == null ? "" : text).getBytes(UTF8));
            out.flush();
            out.close();
            return f;
        } catch (Throwable t) {
            Log.w(TAG, "save nfc log failed: " + t);
            return null;
        }
    }

    /** 通用文本日志（OCR / 诊断），写到导出目录。 */
    public static File saveTextLog(Context c, String prefix, String text) {
        try {
            File f = new File(exportDir(c), prefix + "-" + stamp() + ".txt");
            FileOutputStream out = new FileOutputStream(f);
            out.write((text == null ? "" : text).getBytes(UTF8));
            out.flush();
            out.close();
            return f;
        } catch (Throwable t) {
            Log.w(TAG, "save log failed: " + t);
            return null;
        }
    }

    /**
     * 把一份 txt 日志打成 zip（同名，放同目录）。
     *
     * <p>为什么不直接发 txt：微信对 {@code ACTION_SEND + text/plain + content://} 的接收不稳定，
     * zip 文件是所有分享目标都认的形态，也和「导出验证报告」保持一致。
     */
    public static File zipLog(Context c, File txt) {
        if (txt == null || !txt.isFile()) return null;
        ZipOutputStream zip = null;
        try {
            File out = new File(txt.getParentFile(), txt.getName().replaceAll("\\.txt$", "") + ".zip");
            zip = new ZipOutputStream(new FileOutputStream(out));
            zip.setLevel(9);
            zip.putNextEntry(new ZipEntry(txt.getName()));
            FileInputStream in = new FileInputStream(txt);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
            in.close();
            zip.closeEntry();
            zip.close();
            zip = null;
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "zip log failed: " + t);
            return null;
        } finally {
            if (zip != null) try { zip.close(); } catch (Throwable ignored) {}
        }
    }

    /** 最近一次某类日志（前缀匹配，如 {@code nfc-log-} / {@code ocr-log-}）；没有则返回 null。 */
    public static File latestLog(Context c, String prefix) {
        File[] fs = exportDir(c).listFiles((d, n) -> n.startsWith(prefix) && n.endsWith(".txt"));
        if (fs == null || fs.length == 0) return null;
        Arrays.sort(fs, new Comparator<File>() {
            @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
        return fs[0];
    }

    /** 最近一次读卡日志；没有则返回 null。 */
    public static File latestNfcLog(Context c) {
        return latestLog(c, "nfc-log-");
    }

    public static void saveChipFace(Context c, byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) return;
        try {
            FileOutputStream out = new FileOutputStream(new File(dir(c), "chip_face.jpg"));
            out.write(jpeg);
            out.close();
        } catch (Throwable t) {
            Log.w(TAG, "save chip face failed: " + t);
        }
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

    private static String stamp() {
        java.text.SimpleDateFormat sdf =
                new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US);
        return sdf.format(new java.util.Date());
    }

    /** 追加一行 JSONL（队列/样本用），失败返回 false。 */
    public static boolean appendLine(Context c, String fileName, String line) {
        try {
            FileOutputStream out = new FileOutputStream(file(c, fileName), true);
            out.write((line + "\n").getBytes(UTF8));
            out.flush();
            out.close();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "append " + fileName + " failed: " + t);
            return false;
        }
    }

    public static List<String> readLines(Context c, String fileName) {
        List<String> lines = new ArrayList<>();
        File f = file(c, fileName);
        if (!f.isFile()) return lines;
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
            String[] parts = new String(buf, 0, n, UTF8).split("\n");
            for (String p : parts) if (!p.trim().isEmpty()) lines.add(p.trim());
        } catch (Throwable t) {
            Log.w(TAG, "readLines failed: " + t);
        }
        return lines;
    }

    public static void delete(Context c, String fileName) {
        File f = file(c, fileName);
        if (f.exists()) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }
}
