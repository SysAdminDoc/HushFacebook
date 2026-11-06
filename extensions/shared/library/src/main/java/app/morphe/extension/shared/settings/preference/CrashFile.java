/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.shared.settings.preference;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;

/**
 * The Java crash that ends the app, also written to Download/Hushfacebook as a text file, so a
 * Facebook that crashes before its settings open can still be reported. The bug form asks for the
 * newest one. The file holds what {@link JavaCrashReport} keeps for the diagnostic report, already
 * redacted, with the Facebook and Hushfacebook versions it came from at the top.
 *
 * <p>MediaStore's Downloads collection needs no storage permission on Android 10 and newer, and an
 * app sees only its own files there, so the limit counts only crash files this install wrote. Each
 * write removes the oldest past {@link #KEEP} minus one before adding the new one. Android 9 has
 * no Downloads collection, so there a crash gets no file and the diagnostic report still keeps it.
 *
 * <p>It runs inside the crash handler, so it does as little as it can: one query, the deletes, one
 * insert and one write. It never throws, and a failure leaves no half-written file behind.
 */
public final class CrashFile {
    /** Where the files go, as MediaStore keeps a relative path. */
    static final String FOLDER = Environment.DIRECTORY_DOWNLOADS + "/Hushfacebook/";

    static final String PREFIX = "crash-";
    static final String SUFFIX = ".txt";

    /** How many crash files are kept, the new one included. */
    static final int KEEP = 5;

    /** The app's switch for the file, handed over as its settings load. None yet means no file. */
    @Nullable
    private static volatile BooleanSetting toggle;

    private CrashFile() {
    }

    /** Called by the app's settings as they load: [setting] says whether crash files are written. */
    public static void useSwitch(BooleanSetting setting) {
        toggle = setting;
    }

    /**
     * Whether a crash now gets a file: the switch is known, the settings can be read, and it's on.
     * A paused Hushfacebook answers off, as every switch does.
     */
    static boolean wanted() {
        BooleanSetting setting = toggle;
        return setting != null && Utils.settingsReady() && setting.get();
    }

    /**
     * Writes [report] as a new crash file named for [now], after removing the oldest past the
     * limit. Answers the new file's address, for a crash the process lives through to take back
     * ({@link #takeBack}), or null when it couldn't be written. Never throws.
     */
    @Nullable
    static Uri write(Context context, String report, long now) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        Uri uri = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            prune(resolver);
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(now));
            values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER);
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return null;
            try (OutputStream output = resolver.openOutputStream(uri, "w")) {
                if (output == null) throw new IOException("No stream for the crash file");
                output.write(report.getBytes(StandardCharsets.UTF_8));
            }
            return uri;
        } catch (Throwable failure) {
            // Crash-time code must never get in the way of the handlers after this one.
            if (uri != null) delete(context, uri);
            return null;
        }
    }

    /** Removes [file], one {@link #write} made, for a crash the process lived through. Never throws. */
    static void takeBack(Context context, Uri file) {
        delete(context, file);
    }

    /** The name a crash at [now] gets, in the phone's time: crash-2026-10-11-1405.txt. */
    static String fileName(long now) {
        return PREFIX + new SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(new Date(now)) + SUFFIX;
    }

    /** Removes this install's crash files past the newest {@link #KEEP} minus one. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private static void prune(ContentResolver resolver) {
        List<Long> ids = new ArrayList<>();
        try (Cursor cursor = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                new String[]{FOLDER, PREFIX + "%" + SUFFIX},
                MediaStore.MediaColumns.DATE_ADDED + " DESC, " + MediaStore.MediaColumns._ID + " DESC")) {
            if (cursor == null) return;
            int column = cursor.getColumnIndex(MediaStore.MediaColumns._ID);
            if (column < 0) return;
            while (cursor.moveToNext()) ids.add(cursor.getLong(column));
        }
        for (int i = KEEP - 1; i < ids.size(); i++) {
            resolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ids.get(i)), null, null);
        }
    }

    private static void delete(Context context, Uri uri) {
        try {
            context.getContentResolver().delete(uri, null, null);
        } catch (Throwable ignored) {
            // The file stays, and the next write's limit counts it like any other.
        }
    }
}
