package iq.digitalration.faceverify;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.StatFs;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 现场验证用的设备 / 安装包自证信息。
 *
 * <p>全部靠系统 API 直接读，不做任何网络查询（本应用也没有 INTERNET 权限）。
 * 输出一份纯数据 JSON：界面显示、报告导出、离线复核都用同一份。
 */
public final class DeviceInfo {

    private DeviceInfo() {}

    public static JSONObject collect(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            o.put("collectedAt", System.currentTimeMillis());
            o.put("time", fmt(new Date()));
            o.put("device", device());
            o.put("app", app(ctx));
            o.put("memory", memory(ctx));
            o.put("storage", storage(ctx));
            o.put("display", display(ctx));
            o.put("camera", camera(ctx));
            o.put("nfc", nfc(ctx));
            o.put("integrity", integrity());
            o.put("runtime", runtime(ctx));
        } catch (Throwable ignored) {
        }
        return o;
    }

    // ------------------------------------------------------------ blocks

    private static JSONObject device() throws Exception {
        JSONObject o = new JSONObject();
        o.put("manufacturer", s(Build.MANUFACTURER));
        o.put("brand", s(Build.BRAND));
        o.put("model", s(Build.MODEL));
        o.put("device", s(Build.DEVICE));
        o.put("hardware", s(Build.HARDWARE));
        o.put("board", s(Build.BOARD));
        o.put("sdk", Build.VERSION.SDK_INT);
        o.put("release", s(Build.VERSION.RELEASE));
        if (Build.VERSION.SDK_INT >= 23) o.put("securityPatch", s(Build.VERSION.SECURITY_PATCH));
        o.put("abi", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "");
        JSONArray abis = new JSONArray();
        for (String a : Build.SUPPORTED_ABIS) abis.put(a);
        o.put("abis", abis);
        o.put("cores", Runtime.getRuntime().availableProcessors());
        o.put("locale", Locale.getDefault().toString());
        return o;
    }

    private static JSONObject app(Context ctx) throws Exception {
        JSONObject o = new JSONObject();
        PackageManager pm = ctx.getPackageManager();
        PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(),
                PackageManager.GET_SIGNATURES | PackageManager.GET_PERMISSIONS);
        ApplicationInfo ai = ctx.getApplicationInfo();
        o.put("id", ctx.getPackageName());
        o.put("versionName", s(pi.versionName));
        o.put("versionCode", Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode);
        o.put("firstInstall", fmt(new Date(pi.firstInstallTime)));
        o.put("lastUpdate", fmt(new Date(pi.lastUpdateTime)));
        o.put("apkPath", s(ai.sourceDir));
        o.put("apkBytes", ai.sourceDir == null ? 0 : new File(ai.sourceDir).length());
        o.put("debuggable", (ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        o.put("installer", installer(ctx, pm));
        o.put("signerSha256", signer(pi));
        String[] perms = pi.requestedPermissions == null ? new String[0] : pi.requestedPermissions;
        JSONArray pa = new JSONArray();
        boolean network = false;
        for (String p : perms) {
            pa.put(p);
            if ("android.permission.INTERNET".equals(p)
                    || "android.permission.ACCESS_NETWORK_STATE".equals(p)) network = true;
        }
        o.put("permissions", pa);
        o.put("networkPermission", network);
        return o;
    }

    private static String installer(Context ctx, PackageManager pm) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                String v = pm.getInstallSourceInfo(ctx.getPackageName()).getInstallingPackageName();
                return v == null ? "" : v;
            }
            String v = pm.getInstallerPackageName(ctx.getPackageName());
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String signer(PackageInfo pi) {
        try {
            Signature[] sigs = pi.signatures;
            if (sigs == null || sigs.length == 0) return "";
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(sigs[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format(Locale.US, "%02X", d[i]));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static JSONObject memory(Context ctx) throws Exception {
        JSONObject o = new JSONObject();
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        o.put("totalBytes", mi.totalMem);
        o.put("availBytes", mi.availMem);
        o.put("lowMemory", mi.lowMemory);
        o.put("heapMaxBytes", Runtime.getRuntime().maxMemory());
        if (Build.VERSION.SDK_INT >= 19) o.put("lowRamDevice", am.isLowRamDevice());
        return o;
    }

    private static JSONObject storage(Context ctx) throws Exception {
        JSONObject o = new JSONObject();
        StatFs fs = new StatFs(ctx.getFilesDir().getAbsolutePath());
        o.put("totalBytes", fs.getBlockCountLong() * fs.getBlockSizeLong());
        o.put("freeBytes", fs.getAvailableBlocksLong() * fs.getBlockSizeLong());
        o.put("appFilesBytes", dirSize(ctx.getFilesDir()));
        return o;
    }

    private static JSONObject display(Context ctx) throws Exception {
        JSONObject o = new JSONObject();
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        Display d = wm.getDefaultDisplay();
        d.getRealMetrics(dm);
        o.put("widthPx", dm.widthPixels);
        o.put("heightPx", dm.heightPixels);
        o.put("densityDpi", dm.densityDpi);
        o.put("density", dm.density);
        o.put("refreshRate", d.getRefreshRate());
        return o;
    }

    private static JSONObject camera(Context ctx) throws Exception {
        JSONObject o = new JSONObject();
        int back = 0, front = 0, other = 0;
        JSONArray ids = new JSONArray();
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            String[] list = cm.getCameraIdList();
            for (String id : list) {
                ids.put(id);
                Integer facing = null;
                try {
                    facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                } catch (Throwable ignored) {
                }
                if (facing == null || facing == CameraCharacteristics.LENS_FACING_EXTERNAL) other++;
                else if (facing == CameraCharacteristics.LENS_FACING_BACK) back++;
                else front++;
            }
        } catch (Throwable ignored) {
        }
        o.put("count", back + front + other);
        o.put("back", back);
        o.put("front", front);
        o.put("ids", ids);
        return o;
    }

    private static JSONObject nfc(Context ctx) throws Exception {
        JSONObject o = new JSONObject();
        NfcAdapter a = NfcAdapter.getDefaultAdapter(ctx);
        o.put("supported", a != null);
        o.put("enabled", a != null && a.isEnabled());
        return o;
    }

    private static JSONObject integrity() throws Exception {
        JSONObject o = new JSONObject();
        JSONArray root = new JSONArray();
        String[] suPaths = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
                "/data/local/su", "/data/local/bin/su", "/system/sd/xbin/su",
        };
        for (String p : suPaths) if (new File(p).exists()) root.put(p);
        o.put("rootSigns", root);
        o.put("rootSuspected", root.length() > 0);

        JSONArray emu = new JSONArray();
        String fp = s(Build.FINGERPRINT).toLowerCase(Locale.US);
        String model = s(Build.MODEL).toLowerCase(Locale.US);
        String hw = s(Build.HARDWARE).toLowerCase(Locale.US);
        String product = s(Build.PRODUCT).toLowerCase(Locale.US);
        if (fp.startsWith("generic") || fp.contains("unknown") || fp.contains("test-keys")) emu.put("fingerprint=" + Build.FINGERPRINT);
        if (model.contains("sdk") || model.contains("emulator") || model.contains("android sdk")) emu.put("model=" + Build.MODEL);
        if (hw.contains("goldfish") || hw.contains("ranchu") || hw.contains("vbox")) emu.put("hardware=" + Build.HARDWARE);
        if (product.contains("sdk") || product.contains("emulator") || product.contains("simulator")) emu.put("product=" + Build.PRODUCT);
        o.put("emulatorSigns", emu);
        o.put("emulatorSuspected", emu.length() > 0);
        return o;
    }

    private static JSONObject runtime(Context ctx) throws Exception {
        JSONObject o = new JSONObject();
        App app = App.I;
        if (app != null) {
            o.put("initMs", app.initMs);
            o.put("modelId", s(app.modelId));
            o.put("modelDesc", s(app.modelDesc));
            o.put("engineReady", app.ready);
            o.put("engineError", s(app.error));
            o.put("spoofEnabled", app.spoof != null);
        }
        o.put("threshold", RefStore.threshold(ctx));
        o.put("flipTta", RefStore.flipTta(ctx));
        o.put("hasRef", RefStore.has(ctx));
        o.put("refModel", s(RefStore.modelTag));
        o.put("refGenMs", RefStore.genMs);
        o.put("modelBytes", new File(ctx.getFilesDir(), App.M_ARCFACE).length());
        o.put("landmarkerBytes", new File(ctx.getFilesDir(), App.M_LANDMARKER).length());
        return o;
    }

    // ------------------------------------------------------------ helpers

    private static String s(String v) {
        return v == null ? "" : v;
    }

    public static String fmt(Date d) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(d);
    }

    public static String now() {
        return fmt(new Date());
    }

    public static long dirSize(File dir) {
        if (dir == null || !dir.exists()) return 0;
        if (dir.isFile()) return dir.length();
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        long n = 0;
        for (File f : kids) n += dirSize(f);
        return n;
    }

    public static String humanBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    /** 从 JSON 里取若干字段拼一行，缺失的键直接跳过。 */
    public static String pick(JSONObject o, String... keys) {
        if (o == null) return "";
        List<String> parts = new ArrayList<>();
        for (String k : keys) {
            if (!o.has(k) || o.isNull(k)) continue;
            parts.add(k + "=" + o.optString(k, ""));
        }
        return join(parts, " · ");
    }

    public static String join(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(items.get(i));
        }
        return sb.toString();
    }
}
