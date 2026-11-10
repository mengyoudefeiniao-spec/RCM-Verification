package iq.digitalration.faceverify.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Android 侧的 OCR 门面：把 assets 里的模型复制到私有目录并加载 {@link OcrCore}。
 *
 * <p>两个识别模型分工（详见 OcrCore 类注释）：英文/数字模型读编号与 MRZ，阿拉伯文模型读
 * 「الرقم العائلي / ژمارەی خێزانی」这类标签。检测模型是共用的。
 * 模型只在首次启动时复制（约 21 MB，耗时 1~3 秒），之后直接加载。
 */
public final class OcrEngine {

    private static final String TAG = "Ocr";

    public static final String DET = "ppocr_det.onnx";
    public static final String REC_EN = "ppocr_rec_en.onnx";
    public static final String KEY_EN = "ppocr_keys_en.txt";
    public static final String REC_AR = "ppocr_rec_ar.onnx";
    public static final String KEY_AR = "ppocr_keys_ar.txt";

    private final OcrCore core;
    private final Context appCtx;

    public OcrEngine(Context ctx) throws Exception {
        this.appCtx = ctx.getApplicationContext();
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        File det = copyAsset(DET);
        File recEn = copyAsset(REC_EN);
        File keyEn = copyAsset(KEY_EN);
        File recAr = copyAsset(REC_AR);
        File keyAr = copyAsset(KEY_AR);
        long t0 = System.currentTimeMillis();
        core = new OcrCore(det.getAbsolutePath(), recEn.getAbsolutePath(), keyEn.getAbsolutePath(),
                recAr.getAbsolutePath(), keyAr.getAbsolutePath(), threads);
        Log.i(TAG, "OCR 模型加载完成 " + (System.currentTimeMillis() - t0) + "ms");
    }

    public OcrCore.Scan scan(Bitmap bmp) {
        return scan(bmp, true);
    }

    /**
     * @param deep true = 卡背专用（MRZ 不足时放大补扫 + 条带兜底，慢）；
     *             false = 只做全图识别（快，用于第二帧/已确认户号的场景）
     */
    public OcrCore.Scan scan(Bitmap bmp, boolean deep) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        return deep ? core.recognizeCard(px, w, h) : core.recognizeAuto(px, w, h);
    }

    public CardFields readCard(Bitmap bmp) {
        return CardFields.parse(scan(bmp).lines);
    }

    public OcrCore core() {
        return core;
    }

    private File copyAsset(String name) throws Exception {
        File out = new File(appCtx.getFilesDir(), name);
        if (out.exists() && out.length() > 512) return out;
        File tmp = new File(appCtx.getFilesDir(), name + ".part");
        InputStream in = appCtx.getAssets().open(name);
        FileOutputStream fos = new FileOutputStream(tmp);
        byte[] buf = new byte[1 << 20];
        int n;
        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        fos.close();
        in.close();
        if (!tmp.renameTo(out)) throw new java.io.IOException("rename failed: " + name);
        return out;
    }
}
