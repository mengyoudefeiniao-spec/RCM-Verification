package iq.digitalration.faceverify;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/** 参照照片（特征 + 缩略图）持久化，以及参数设置。 */
public final class RefStore {

    public static volatile float[] emb;
    public static volatile Bitmap thumb;
    public static volatile String label = "";
    /** 最近一次建模的质量提示（不持久化）。 */
    public static volatile String quality = "";
    public static volatile boolean qualityWarn;
    /** 建模时使用的识别模型 id；与当前模型不一致时参照特征作废。 */
    public static volatile String modelTag;
    public static volatile long genMs;

    private RefStore() {}

    private static File embFile(Context c) { return new File(c.getFilesDir(), "ref_emb.bin"); }
    private static File thumbFile(Context c) { return new File(c.getFilesDir(), "ref_thumb.png"); }

    public static boolean has(Context c) {
        return emb != null && emb.length > 0 && thumbFile(c).exists();
    }

    public static void save(Context c) {
        try {
            float[] e = emb;
            if (e == null) return;
            DataOutputStream out = new DataOutputStream(new FileOutputStream(embFile(c)));
            out.writeInt(e.length);
            for (float v : e) out.writeFloat(v);
            out.writeLong(genMs);
            out.writeUTF(modelTag == null ? "" : modelTag);
            out.close();
            Bitmap t = thumb;
            if (t != null) {
                FileOutputStream fo = new FileOutputStream(thumbFile(c));
                t.compress(Bitmap.CompressFormat.PNG, 100, fo);
                fo.close();
            }
        } catch (Exception ignored) {}
    }

    public static void load(Context c) {
        try {
            if (thumb == null && thumbFile(c).exists()) {
                thumb = BitmapFactory.decodeFile(thumbFile(c).getAbsolutePath());
            }
            File f = embFile(c);
            if (emb == null && f.exists()) {
                DataInputStream in = new DataInputStream(new FileInputStream(f));
                int n = in.readInt();
                float[] e = new float[n];
                for (int i = 0; i < n; i++) e[i] = in.readFloat();
                genMs = in.readLong();
                try { modelTag = in.readUTF(); } catch (Exception ignored) { modelTag = null; }
                in.close();
                emb = e;
            }
        } catch (Exception ignored) {}
    }

    public static void clear(Context c) {
        emb = null; thumb = null; genMs = 0; label = ""; quality = ""; qualityWarn = false; modelTag = null;
        //noinspection ResultOfMethodCallIgnored
        embFile(c).delete();
        //noinspection ResultOfMethodCallIgnored
        thumbFile(c).delete();
    }

    // ---- 参数 ----
    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    public static float threshold(Context c) { return sp(c).getFloat("thr", 0.45f); }
    public static void setThreshold(Context c, float v) { sp(c).edit().putFloat("thr", v).apply(); }

    public static boolean flipTta(Context c) { return sp(c).getBoolean("tta", true); }
    public static void setFlipTta(Context c, boolean v) { sp(c).edit().putBoolean("tta", v).apply(); }

    public static boolean strictSpoof(Context c) { return sp(c).getBoolean("strict", false); }
    public static void setStrictSpoof(Context c, boolean v) { sp(c).edit().putBoolean("strict", v).apply(); }

    public static int rotOverride(Context c) { return sp(c).getInt("rot", -1); }
    public static void setRotOverride(Context c, int v) { sp(c).edit().putInt("rot", v).apply(); }
}
