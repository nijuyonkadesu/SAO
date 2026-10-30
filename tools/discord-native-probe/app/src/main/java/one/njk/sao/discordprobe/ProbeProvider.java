package one.njk.sao.discordprobe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.Map;

public class ProbeProvider extends ContentProvider {
    public static final Map<String,String> TYPES = Map.of("still.png","image/png", "motion.gif","image/gif", "motion.webm","video/webm", "motion.mp4","video/mp4");
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return TYPES.get(uri.getLastPathSegment()); }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        String name = uri.getLastPathSegment();
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int i=0;i<columns.length;i++) row[i] = columns[i].equals(OpenableColumns.DISPLAY_NAME) ? name : columns[i].equals(OpenableColumns.SIZE) ? new File(getContext().getFilesDir(),name).length() : null;
        cursor.addRow(row); return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode) || !TYPES.containsKey(uri.getLastPathSegment())) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(new File(getContext().getFilesDir(), uri.getLastPathSegment()), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
