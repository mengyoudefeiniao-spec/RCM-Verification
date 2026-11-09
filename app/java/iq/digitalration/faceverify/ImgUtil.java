package iq.digitalration.faceverify;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.Image;
import android.util.Log;

import java.io.InputStream;
import java.nio.ByteBuffer;

/** 图像工具：YUV_420_888 -> 旋转后的 ARGB Bitmap、缩放、模糊度等。 */
public final class ImgUtil {
    private static final String TAG = "ImgUtil";

    private ImgUtil() {}

    /** 复用的像素缓冲，避免每帧分配 1.2MB 垃圾。 */
    private static int[] sBuf = new int[0];

    private static int[] buf(int n) {
        if (sBuf.length < n) sBuf = new int[n];
        return sBuf;
    }

    /**
     * Camera2 的 YUV_420_888 转 ARGB_8888，并同时应用旋转（0/90/180/270 顺时针）。
     * 直接写入旋转后的坐标系，避免二次拷贝。
     */
    public static Bitmap yuv420ToBitmap(Image image, int rotationDeg, boolean mirrorX) {
        final int w = image.getWidth();
        final int h = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int yRow = planes[0].getRowStride();
        int yPix = planes[0].getPixelStride();
        int uRow = planes[1].getRowStride();
        int uPix = planes[1].getPixelStride();
        int vRow = planes[2].getRowStride();
        int vPix = planes[2].getPixelStride();

        int rot = ((rotationDeg % 360) + 360) % 360;
        int dw = (rot == 90 || rot == 270) ? h : w;
        int dh = (rot == 90 || rot == 270) ? w : h;
        int[] px = buf(dw * dh);

        for (int y = 0; y < h; y++) {
            int yBase = y * yRow;
            int uvRow = (y >> 1);
            int uvBaseU = uvRow * uRow;
            int uvBaseV = uvRow * vRow;
            for (int x = 0; x < w; x++) {
                int Y = yBuf.get(yBase + x * yPix) & 0xFF;
                int uvCol = x >> 1;
                int U = (uBuf.get(uvBaseU + uvCol * uPix) & 0xFF) - 128;
                int V = (vBuf.get(uvBaseV + uvCol * vPix) & 0xFF) - 128;

                int r = Y + ((91881 * V) >> 16);
                int g = Y - ((22554 * U + 46802 * V) >> 16);
                int b = Y + ((116130 * U) >> 16);
                if (r < 0) r = 0; else if (r > 255) r = 255;
                if (g < 0) g = 0; else if (g > 255) g = 255;
                if (b < 0) b = 0; else if (b > 255) b = 255;

                int dx, dy;
                switch (rot) {
                    case 90:  dx = h - 1 - y; dy = x; break;
                    case 180: dx = w - 1 - x; dy = h - 1 - y; break;
                    case 270: dx = y; dy = w - 1 - x; break;
                    default:  dx = x; dy = y; break;
                }
                if (mirrorX) dx = dw - 1 - dx;
                px[dy * dw + dx] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return Bitmap.createBitmap(px, dw, dh, Bitmap.Config.ARGB_8888);
    }

    /** 从 assets 解码图片（带降采样）。 */
    public static Bitmap decodeAsset(android.content.res.AssetManager am, String name, int maxSide) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream in = am.open(name);
        BitmapFactory.decodeStream(in, null, o);
        in.close();
        int sample = 1;
        int longer = Math.max(o.outWidth, o.outHeight);
        while (longer / sample > maxSide * 2) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        in = am.open(name);
        Bitmap bmp = BitmapFactory.decodeStream(in, null, o2);
        in.close();
        if (bmp == null) throw new IllegalStateException("decode failed: " + name);
        if (Math.max(bmp.getWidth(), bmp.getHeight()) > maxSide) {
            float s = maxSide / (float) Math.max(bmp.getWidth(), bmp.getHeight());
            bmp = scale(bmp, s);
        }
        return bmp;
    }

    public static Bitmap scale(Bitmap src, float s) {
        if (Math.abs(s - 1f) < 1e-3f) return src;
        int w = Math.max(1, Math.round(src.getWidth() * s));
        int h = Math.max(1, Math.round(src.getHeight() * s));
        return Bitmap.createScaledBitmap(src, w, h, true);
    }

    public static Bitmap flipHorizontal(Bitmap src) {
        Matrix m = new Matrix();
        m.postScale(-1f, 1f);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    /** 计算裁剪区域并返回 Bitmap（不越界）。 */
    public static Bitmap crop(Bitmap src, float cx, float cy, float size) {
        int s = Math.max(8, Math.round(size));
        int x = Math.round(cx - s / 2f);
        int y = Math.round(cy - s / 2f);
        int x0 = Math.max(0, x), y0 = Math.max(0, y);
        int x1 = Math.min(src.getWidth(), x + s), y1 = Math.min(src.getHeight(), y + s);
        if (x1 - x0 < 4 || y1 - y0 < 4) return null;
        return Bitmap.createBitmap(src, x0, y0, x1 - x0, y1 - y0);
    }

    /** 拉普拉斯方差（越大越清晰），输入灰度 int[]。 */
    public static double laplacianVariance(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (w < 8 || h < 8) return 0;
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        int[] gray = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            gray[i] = (((p >> 16) & 0xFF) * 77 + ((p >> 8) & 0xFF) * 150 + (p & 0xFF) * 29) >> 8;
        }
        double sum = 0, sum2 = 0;
        int n = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                int lap = 4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - w] - gray[i + w];
                sum += lap; sum2 += (double) lap * lap; n++;
            }
        }
        if (n == 0) return 0;
        double mean = sum / n;
        return sum2 / n - mean * mean;
    }

    public static double meanLuma(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        double s = 0;
        for (int p : px) s += (((p >> 16) & 0xFF) * 77 + ((p >> 8) & 0xFF) * 150 + (p & 0xFF) * 29) >> 8;
        return s / px.length;
    }

    public static Bitmap drawThumb(Bitmap src, int targetW) {
        float s = targetW / (float) src.getWidth();
        Bitmap out = Bitmap.createScaledBitmap(src, targetW, Math.max(1, Math.round(src.getHeight() * s)), true);
        return out;
    }

    public static String abi() {
        return android.os.Build.SUPPORTED_ABIS.length > 0 ? android.os.Build.SUPPORTED_ABIS[0] : "?";
    }

    static void log(String m) { Log.i(TAG, m); }
    /** 从 content Uri 解码并限制最长边。 */
    public static Bitmap decodeUri(android.content.Context ctx, android.net.Uri uri, int maxSide) throws Exception {
        android.content.ContentResolver cr = ctx.getContentResolver();
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream in = cr.openInputStream(uri);
        if (in == null) return null;
        BitmapFactory.decodeStream(in, null, o);
        in.close();
        int sample = 1;
        int longer = Math.max(o.outWidth, o.outHeight);
        while (longer / sample > maxSide * 2) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        in = cr.openInputStream(uri);
        if (in == null) return null;
        Bitmap bmp = BitmapFactory.decodeStream(in, null, o2);
        in.close();
        if (bmp == null) return null;
        if (Math.max(bmp.getWidth(), bmp.getHeight()) > maxSide) {
            bmp = scale(bmp, maxSide / (float) Math.max(bmp.getWidth(), bmp.getHeight()));
        }
        return bmp;
    }

    /** content Uri 的文件名。 */
    public static String displayName(android.content.Context ctx, android.net.Uri uri) {
        try {
            String[] proj = {android.provider.OpenableColumns.DISPLAY_NAME};
            android.database.Cursor c = ctx.getContentResolver().query(uri, proj, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                        if (i >= 0) {
                            String n = c.getString(i);
                            if (n != null && !n.isEmpty()) return n;
                        }
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {}
        String p = uri.getLastPathSegment();
        return p == null ? "image" : p.substring(p.lastIndexOf('/') + 1);
    }
}