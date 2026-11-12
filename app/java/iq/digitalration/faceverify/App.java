package iq.digitalration.faceverify;

import android.app.Application;
import android.content.res.AssetManager;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** 全局引擎持有者：启动时把模型从 assets 复制到 filesDir 并加载。 */
public class App extends Application {

    private static final String TAG = "App";
    public static App I;

    public static final String M_LANDMARKER = "face_landmarker.task";
    public static final String M_ARCFACE = "arcface.onnx";
    public static final String M_ANTISPOOF = "minifasnet_v2.onnx";

    public volatile FaceEngine face;
    public volatile RecogEngine recog;
    public volatile SpoofEngine spoof;
    /** 卡背 OCR 引擎：15MB 模型，不随主流程初始化，用到时才加载（避免拖慢启动）。 */
    public volatile iq.digitalration.faceverify.ocr.OcrEngine ocr;
    private volatile String ocrError;
    public volatile boolean ready;
    public volatile String error;
    public volatile String status = "准备中…";
    public volatile long initMs;
    /** 当前构建所用的识别模型标识（来自 assets/model.conf），换模型后参照特征必须重建。 */
    public volatile String modelId = "unknown";
    public volatile String modelDesc = "";

    private final Object lock = new Object();
    private boolean starting;
    private final List<Runnable> waiting = new ArrayList<>();
    private final List<Runnable> progressListeners = new ArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        I = this;
        installCrashLogger();
        loadCsca();
    }

    /**
     * 装入 CSCA 信任锚：把 ICAO PKD MasterList 导出的 PEM（或边检下发的证书包）丢进
     * assets/csca/ 即可，支持多张串在一个文件里。没有文件时库为空，界面如实显示"CSCA 未收录"，
     * 只做"自洽"判定——绝不把自签名当成可信签发。
     */
    private void loadCsca() {
        try {
            android.content.res.AssetManager am = getAssets();
            String[] files = am.list("csca");
            if (files == null || files.length == 0) return;
            int n = 0;
            for (String f : files) {
                String lower = f.toLowerCase(java.util.Locale.US);
                if (!(lower.endsWith(".pem") || lower.endsWith(".cer") || lower.endsWith(".crt"))) continue;
                n += iq.digitalration.faceverify.card.Csca.loadPem(am.open("csca/" + f));
            }
            if (n > 0) Log.i(TAG, "CSCA 库装入 " + n + " 张证书");
        } catch (Throwable t) {
            Log.w(TAG, "CSCA 装载失败：" + t.getMessage());
        }
    }

    /** 没有 USB 时抓不到 logcat，把未捕获异常写到 filesDir/last_crash.txt 供界面显示。 */
    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                sw.write("time=" + new java.util.Date() + "\nthread=" + t.getName()
                        + "\nabi=" + Build.SUPPORTED_ABIS[0] + " sdk=" + Build.VERSION.SDK_INT
                        + " model=" + Build.MANUFACTURER + " " + Build.MODEL + "\n\n");
                e.printStackTrace(pw);
                java.io.FileWriter fw = new java.io.FileWriter(new File(getFilesDir(), "last_crash.txt"));
                fw.write(sw.toString());
                fw.close();
            } catch (Throwable ignored) {}
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    public String lastCrash() {
        try {
            File f = new File(getFilesDir(), "last_crash.txt");
            if (!f.exists()) return null;
            byte[] b = new byte[(int) Math.min(f.length(), 8000)];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(b);
            in.close();
            return new String(b, 0, Math.max(0, n), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    public void clearCrash() {
        //noinspection ResultOfMethodCallIgnored
        new File(getFilesDir(), "last_crash.txt").delete();
    }

    /** 懒加载 OCR（首次复制 15MB 模型并建两个 onnx 会话，2~5 秒），失败会记住原因。 */
    public synchronized iq.digitalration.faceverify.ocr.OcrEngine ocrEngine() throws Exception {
        if (ocr != null) return ocr;
        if (ocrError != null) throw new IllegalStateException(ocrError);
        try {
            setStatus("加载卡背 OCR 模型（首次约 21MB，检测+英文/数字+阿拉伯文）…");
            ocr = new iq.digitalration.faceverify.ocr.OcrEngine(this);
            setStatus("OCR 就绪");
            return ocr;
        } catch (Exception e) {
            ocrError = e.getClass().getSimpleName() + ": " + e.getMessage();
            throw e;
        }
    }

    public void setStatus(String s) {
        status = s;
        List<Runnable> copy;
        synchronized (lock) { copy = new ArrayList<>(progressListeners); }
        for (Runnable r : copy) r.run();
    }

    /** 进度回调会一直保留（用于刷新界面），onReady 只在初始化结束后跑一次。 */
    public void ensureInit(Runnable onReady, Runnable onProgress) {
        synchronized (lock) {
            if (onProgress != null) progressListeners.add(onProgress);
            if (ready || error != null) {
                starting = false;
            } else if (!starting) {
                starting = true;
                new Thread(this::initInternal, "init").start();
            }
            if (onReady != null) {
                if (ready || error != null) {
                    new Thread(() -> onReady.run()).start();
                } else {
                    waiting.add(onReady);
                }
            }
        }
    }

    private void initInternal() {
        long t0 = System.currentTimeMillis();
        try {
            setStatus("复制模型到本地（约 166MB，仅首次）…");
            File arc = copyAsset(M_ARCFACE);

            setStatus("加载人脸网格模型…");
            face = new FaceEngine(this);

            readModelConf();

            setStatus("加载 ArcFace 特征模型…");
            int threads = Math.min(4, Runtime.getRuntime().availableProcessors());
            recog = new RecogEngine(arc.getAbsolutePath(), threads);

            setStatus("加载反欺诈模型…");
            // 说明：内置 MiniFASNetV2 ONNX 实测输出恒定（无区分度），故不启用；
            // 反欺诈改由「活体挑战-响应」承担。SpoofEngine 保留作为接入真实模型的位置。
            spoof = null;

            initMs = System.currentTimeMillis() - t0;
            setStatus("就绪 · " + Build.SUPPORTED_ABIS[0] + " · 加载 " + initMs + "ms");
        } catch (Throwable t) {
            Log.e(TAG, "init failed", t);
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            setStatus("初始化失败：" + error);
        } finally {
            List<Runnable> run;
            synchronized (lock) {
                ready = error == null;
                starting = false;
                run = new ArrayList<>(waiting);
                waiting.clear();
            }
            for (Runnable r : run) {
                try { r.run(); } catch (Throwable ignored) {}
            }
        }
    }

    /** 读取打包进 assets 的模型说明，并设置默认阈值（只设一次，不覆盖用户手动改过的值）。 */
    private void readModelConf() {
        try {
            InputStream in = getAssets().open("model.conf");
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                if (k.equals("id")) modelId = v;
                else if (k.equals("desc")) modelDesc = v;
                else if (k.equals("default_threshold")) {
                    android.content.SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
                    if (!sp.contains("thr")) {
                        try { sp.edit().putFloat("thr", Float.parseFloat(v)).apply(); } catch (Exception ignored) {}
                    }
                }
            }
            br.close();
        } catch (Throwable ignored) {
        }
    }

    /** assets -> filesDir，已存在且非空则跳过。 */
    private File copyAsset(String name) throws Exception {
        File out = new File(getFilesDir(), name);
        if (out.exists() && out.length() > 1024) return out;
        File tmp = new File(getFilesDir(), name + ".part");
        AssetManager am = getAssets();
        InputStream in = am.open(name);
        long total = in.available();
        FileOutputStream fos = new FileOutputStream(tmp);
        byte[] buf = new byte[1 << 20];
        long done = 0;
        int n, lastPct = -1;
        while ((n = in.read(buf)) > 0) {
            fos.write(buf, 0, n);
            done += n;
            int pct = total > 0 ? (int) (done * 100 / total) : -1;
            if (pct / 10 != lastPct / 10) {
                lastPct = pct;
                setStatus("复制 " + name + " " + (pct >= 0 ? pct + "%" : "") + " (" + (done >> 20) + "MB)");
            }
        }
        fos.close();
        in.close();
        if (!tmp.renameTo(out)) throw new java.io.IOException("rename failed: " + name);
        return out;
    }
}
