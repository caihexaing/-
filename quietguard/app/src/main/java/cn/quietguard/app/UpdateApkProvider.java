package cn.quietguard.app;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.List;

/** Shares only a specifically named verified APK with a temporary installer read grant. */
public final class UpdateApkProvider extends ContentProvider {
    public static final String MIME = "application/vnd.android.package-archive";
    @Override public boolean onCreate() { return true; }
    private File file(Uri uri) throws FileNotFoundException {
        List<String> parts = uri.getPathSegments();
        if (!"content".equals(uri.getScheme()) || !getContext().getPackageName().concat(".updates").equals(uri.getAuthority())
                || parts.size() != 2 || !parts.get(0).equals("apk") || !parts.get(1).matches("QuietGuard-[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}-[0-9]+\\.apk"))
            throw new FileNotFoundException("Invalid update URI");
        File f = new File(new File(getContext().getCacheDir(), "updates"), parts.get(1));
        if (!f.isFile()) throw new FileNotFoundException("Update APK no longer available");
        return f;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Updates are read-only");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) { return MIME; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        try {
            File f = file(uri);
            String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns); Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = f.getName();
                else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = f.length();
            }
            cursor.addRow(values); return cursor;
        } catch (FileNotFoundException e) { return null; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
