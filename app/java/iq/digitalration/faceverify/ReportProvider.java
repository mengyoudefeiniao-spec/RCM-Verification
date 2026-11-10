package iq.digitalration.faceverify;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Locale;

/**
 * 只读的内容提供者，用来把导出的报告 ZIP 交给「分享」目标。
 *
 * <p>本工程没有 androidx，用不了 FileProvider，而 targetSdk 33 下 {@code file://} 会直接抛
 * FileUriExposedException，所以这里最小实现一个：authority 是
 * {@code <包名>.reports}，只暴露 lab-export 目录下的 .zip，且只读。
 *
 * <p>注意：manifest 里的 {@code android:authorities} 由 tools/build.ps1 按实际包名重写
 * （标准版/精简版包名不同，authority 也必须不同，否则两个包不能共存）。
 */
public class ReportProvider extends ContentProvider {

    public static String authority(Context c) {
        return c.getPackageName() + ".reports";
    }

    public static Uri uriFor(Context c, File f) {
        return new Uri.Builder()
                .scheme("content")
                .authority(authority(c))
                .appendPath(f.getName())
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        Context ctx = getContext();
        if (ctx == null) throw new FileNotFoundException("no context");
        String name = uri.getLastPathSegment();
        if (name == null) throw new FileNotFoundException("empty name");
        // 只允许导出目录里的 zip / txt（读卡日志），避免变成任意文件读取
        String lower = name.toLowerCase(Locale.US);
        boolean allowed = lower.endsWith(".zip") || lower.endsWith(".txt");
        if (!allowed || name.contains("/") || name.contains("..")) {
            throw new FileNotFoundException("not a report: " + name);
        }
        File dir = LabStore.exportDir(ctx);
        File f = new File(dir, name);
        if (!f.isFile()) throw new FileNotFoundException("missing: " + name);
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && mode.contains("w")) throw new FileNotFoundException("read-only provider");
        File f = resolve(uri);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String name = uri == null ? null : uri.getLastPathSegment();
        if (name != null && name.toLowerCase(Locale.US).endsWith(".txt")) return "text/plain";
        return "application/zip";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        String[] cols = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        MatrixCursor c = new MatrixCursor(cols, 1);
        try {
            File f = resolve(uri);
            Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
                else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
                else row[i] = null;
            }
            c.addRow(row);
        } catch (Throwable ignored) {
        }
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }
}
