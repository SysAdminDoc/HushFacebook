package app.hushfacebook.unpacker;

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

/**
 * Lends the finished copy to Morphe Manager. Whatever the path, the answer is the one unpacked file
 * in the work folder, read only, and only an app the share granted it to can open it.
 */
public final class UnpackedProvider extends ContentProvider {
    static final String AUTHORITY = "app.hushfacebook.unpacker.files";

    /** The address the copy is shared under, ending in the name Morphe Manager shows for it. */
    static Uri uriFor(String displayName) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(displayName).build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    /** The one finished copy in the work folder, or null when there's none. */
    static File finishedCopy(Context context) {
        File[] files = new File(context.getCacheDir(), "unpack").listFiles((dir, name) -> name.startsWith("unpacked."));
        return files == null || files.length != 1 ? null : files[0];
    }

    private File copy() throws FileNotFoundException {
        File file = finishedCopy(getContext());
        if (file == null) throw new FileNotFoundException("no unpacked copy");
        return file;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("the copy is read only");
        return ParcelFileDescriptor.open(copy(), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String[] columns = projection != null ? projection : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        File file;
        try {
            file = copy();
        } catch (FileNotFoundException gone) {
            return cursor;
        }
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = uri.getLastPathSegment();
            else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return MainActivity.mimeType(String.valueOf(uri.getLastPathSegment()));
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }
}
