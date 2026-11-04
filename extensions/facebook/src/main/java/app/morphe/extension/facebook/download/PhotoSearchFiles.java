/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Serves the photo a Search photo tap fetched ({@link PhotoSearch}) to the app picked in the
 * chooser. The patch declares it unexported with grantUriPermissions, so only an app the chooser's
 * intent granted can read, and only the one address it was granted.
 *
 * <p>It answers {@code content://[package].hushfacebook.photosearch/photo/[number]} and nothing
 * else: no query or fragment, a number of digits only, a file that's there. It reads, never writes.
 */
public final class PhotoSearchFiles extends ContentProvider {

    /** The first part of every address it serves. */
    static final String PHOTO = "photo";

    /** Enough of a file's start to tell what kind of picture it holds. */
    private static final int HEAD = 16;

    @Override
    public boolean onCreate() {
        return true;
    }

    /** The photo [uri] names, or null for any other address or a photo that isn't there. */
    @Nullable
    static File photoOf(@Nullable Context context, @Nullable Uri uri) {
        if (context == null || uri == null || !"content".equals(uri.getScheme())
                || uri.getQuery() != null || uri.getFragment() != null) return null;
        List<String> path = uri.getPathSegments();
        if (path.size() != 2 || !PHOTO.equals(path.get(0))) return null;
        String digits = path.get(1);
        if (digits.isEmpty() || digits.length() > 18) return null;
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') return null;
        }
        File file = PhotoSearch.file(context, Long.parseLong(digits));
        return file != null && file.isFile() ? file : null;
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("photos are served for reading only");
        File file = photoOf(getContext(), uri);
        if (file == null) throw new FileNotFoundException("no such photo");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        File file = photoOf(getContext(), uri);
        return file == null ? null : typeOf(file);
    }

    /** What picture [file] holds, by its first bytes, or null when it isn't one of these. */
    @Nullable
    static String typeOf(File file) {
        byte[] head = new byte[HEAD];
        int read = 0;
        try (InputStream in = new FileInputStream(file)) {
            while (read < HEAD) {
                int n = in.read(head, read, HEAD - read);
                if (n < 0) break;
                read += n;
            }
        } catch (IOException e) {
            return null;
        }
        if (read >= 3 && (head[0] & 0xff) == 0xff && (head[1] & 0xff) == 0xd8 && (head[2] & 0xff) == 0xff) return PhotoFormat.JPEG;
        if (read >= 4 && (head[0] & 0xff) == 0x89 && ascii(head, 1, "PNG")) return "image/png";
        if (read >= 12 && ascii(head, 0, "RIFF") && ascii(head, 8, "WEBP")) return "image/webp";
        if (read >= 4 && ascii(head, 0, "GIF8")) return "image/gif";
        if (read >= 12 && ascii(head, 4, "ftyp")) {
            String brand = new String(head, 8, 4, StandardCharsets.US_ASCII);
            if (brand.equals("avif") || brand.equals("avis")) return PhotoFormat.AVIF;
            if (brand.startsWith("hei") || brand.startsWith("hev")) return "image/heic";
        }
        return null;
    }

    private static boolean ascii(byte[] bytes, int at, String text) {
        for (int i = 0; i < text.length(); i++) {
            if (bytes[at + i] != (byte) text.charAt(i)) return false;
        }
        return true;
    }

    /** The name the picked app shows for [file], with the ending its type has. */
    static String nameOf(File file) {
        String type = typeOf(file);
        String ending = type == null ? "" : "." + (type.equals(PhotoFormat.JPEG) ? "jpg" : type.substring(type.indexOf('/') + 1));
        return file.getName() + ending;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        File file = photoOf(getContext(), uri);
        if (file == null) return null;
        String[] asked = projection == null ? new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE } : projection;
        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (String column : asked) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                columns.add(column);
                values.add(nameOf(file));
            } else if (OpenableColumns.SIZE.equals(column)) {
                columns.add(column);
                values.add(file.length());
            }
        }
        MatrixCursor cursor = new MatrixCursor(columns.toArray(new String[0]), 1);
        cursor.addRow(values.toArray());
        return cursor;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        return 0;
    }
}
