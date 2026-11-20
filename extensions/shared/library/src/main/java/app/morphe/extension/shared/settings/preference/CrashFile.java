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
import java.util.Map;
import java.util.TreeMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;

/**
 * The Java crash that ends the app, also written to Download/Hushfacebook as a text file, so a
 * Facebook that crashes before its settings open can still be reported. The bug form asks for the
 * newest one. The file holds what {@link JavaCrashReport} keeps for the diagnostic report, already
 * redacted, with the Facebook and Hushfacebook versions it came from at the top.
 *
 * <p>A death the Java handler never sees, a native crash, a hang or a kill by Android 17's memory
 * limiter (#528), gets its file from the next start, which finds it in Android's exit records
 * ({@link #fileLater}). That file names the reason and holds Android's own description of it. A
 * start that runs paused after a crash loop still files those, by the switch's saved value.
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

    /** Deaths the start found in Android's exit records, held until the switch is known. */
    private static final Object WAITING_LOCK = new Object();
    @Nullable private static TreeMap<Long, String> waiting;
    @Nullable private static Context waitingContext;
    @Nullable private static Filed whenFiled;

    /** Told how far the deaths {@link #fileLater} was given are done with. */
    public interface Filed {
        /** Every death up to and including the one at [when] has its file, or needs none. */
        void upTo(long when);
    }

    private CrashFile() {
    }

    /** Called by the app's settings as they load: [setting] says whether crash files are written. */
    public static void useSwitch(BooleanSetting setting) {
        toggle = setting;
        boolean deathsWaiting;
        synchronized (WAITING_LOCK) {
            deathsWaiting = waiting != null;
        }
        // Found before the settings loaded: filed now, off the thread loading them.
        if (deathsWaiting) Utils.runOnBackgroundThread(CrashFile::fileWaiting);
    }

    /**
     * Files the deaths in [reports], each keyed by when the process died, as crash files named for
     * that moment, oldest first and no more than the folder keeps. They wait for the switch when
     * the settings haven't handed it over yet. The switch's saved value decides, paused or not: a
     * start that runs paused after three crashes still files the deaths that paused it. With it
     * on, [done] hears of each death once its file is written, and a file that can't be written
     * ends the run, so the next start tries it again. With it off they get none and [done] hears
     * of the newest. Never throws.
     */
    public static void fileLater(Context context, TreeMap<Long, String> reports, Filed done) {
        synchronized (WAITING_LOCK) {
            TreeMap<Long, String> newest = new TreeMap<>(reports);
            while (newest.size() > KEEP) newest.pollFirstEntry();
            waiting = newest;
            waitingContext = context;
            whenFiled = done;
        }
        fileWaiting();
    }

    /** Files what {@link #fileLater} holds once the switch is known. */
    static void fileWaiting() {
        TreeMap<Long, String> reports;
        Context context;
        Filed done;
        BooleanSetting setting = toggle;
        synchronized (WAITING_LOCK) {
            // A switch handed over means the settings class loaded, so its saved value can be read.
            if (waiting == null) return;
            if (setting == null) {
                Logger.printDebug(() -> "Crash file: " + waiting.size() + " deaths wait for the settings to load");
                return;
            }
            reports = waiting;
            context = waitingContext;
            done = whenFiled;
            waiting = null;
            waitingContext = null;
            whenFiled = null;
        }
        try {
            if (context == null || done == null || reports.isEmpty()) return;
            if (!setting.savedValue()) {
                Logger.printDebug(() -> "Crash file: off, so " + reports.size() + " deaths get none");
                done.upTo(reports.lastKey());
                return;
            }
            for (Map.Entry<Long, String> death : reports.entrySet()) {
                Uri file;
                synchronized (JavaCrashReport.FILE_LOCK) {
                    file = write(context, death.getValue(), death.getKey());
                }
                long when = death.getKey();
                // Not written: the deaths from here on stay for the next start.
                if (file == null) {
                    Logger.printDebug(() -> "Crash file: the death at " + when + " wasn't written, the next start tries again");
                    return;
                }
                Logger.printDebug(() -> "Crash file: filed the death at " + when + " as " + file);
                done.upTo(when);
            }
        } catch (Throwable failure) {
            // The mark stays at the last death filed, and the next start finds the rest again.
            Logger.printException(() -> "Crash file: could not file the last deaths", failure);
        }
    }

    /** Takes the switch back, as before the settings load. A test hands it over again. */
    static void forgetSwitchForTests() {
        toggle = null;
    }

    /** Forgets the deaths waiting, as in a new process. */
    public static void resetForTests() {
        synchronized (WAITING_LOCK) {
            waiting = null;
            waitingContext = null;
            whenFiled = null;
        }
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
            if (uri == null) {
                Logger.printDebug(() -> "Crash file: Downloads gave no place for " + values);
                return null;
            }
            try (OutputStream output = resolver.openOutputStream(uri, "w")) {
                if (output == null) throw new IOException("No stream for the crash file");
                output.write(report.getBytes(StandardCharsets.UTF_8));
            }
            return uri;
        } catch (Throwable failure) {
            // Crash-time code must never get in the way of the handlers after this one.
            if (uri != null) delete(context, uri);
            try {
                Logger.printDebug(() -> "Crash file: not written: " + failure);
            } catch (Throwable ignored) {
                // Logging is a nicety here.
            }
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
