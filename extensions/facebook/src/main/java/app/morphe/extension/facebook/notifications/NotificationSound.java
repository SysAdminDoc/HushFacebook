/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.notifications;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;

/**
 * Puts Facebook's own notification chime in the phone's notification sounds.
 *
 * <p>Android keeps a notification category's sound with the category, and an app can't change it
 * once the category exists. A category that ended up as None (#83: a fresh re-signed install on
 * a phone that had kept an older copy's categories) offers "App provided sound" only while it
 * still points at the app's resource, so from None there's no way back to Facebook's chime inside
 * Facebook's settings or Android's. The chime is a raw resource of the APK, though. Written once
 * into the Notifications folder through MediaStore, it's in Android's sound picker for any
 * category, by name.
 *
 * <p>Nothing here needs a permission from Android 10 on: an app may insert media it creates, and
 * the row is created pending and published only after the last byte, like a save. Android 9
 * needs storage access to write the file itself ({@link #saveFile}).
 */
public final class NotificationSound {

    /** The source every event of this class carries in the diagnostic report. */
    private static final String SOURCE = "NotificationSound";

    /** The chime's resource name in Facebook's APK, under a {@code raw} type. */
    static final String RESOURCE_NAME = "new_facebook_ringtone_7";

    /** The types the chime is looked up under: Facebook splits its raw type (581 keeps it in {@code raw.2}). */
    static final List<String> RESOURCE_TYPES = Arrays.asList("raw.2", "raw", "raw.1", "raw.3");

    /** The name Android's sound picker shows, and the file's name without its extension. */
    static final String TITLE = "Facebook notification";

    /** No leading slash: MediaStore checks this against its own directory names. */
    static final String FOLDER = "Notifications/";

    /** An MP4 up to this size is held whole so its title tag can be dropped; the stock chime is 32 KB. */
    static final int MAX_UNTITLED = 4 * 1024 * 1024;

    /** Android 9's storage access request, told apart from Facebook's own by this code. */
    private static final int STORAGE_REQUEST = 0x4847;

    /** What a save of the chime came to. */
    public enum Outcome {
        /** Written now; {@link Result#name} is the file's name. */
        SAVED,
        /** A file of that name was already in the folder, so nothing was written. */
        ALREADY_THERE,
        /** The APK has no chime under a name this class knows. */
        NO_SOUND,
        /** MediaStore refused, or the copy failed part way; the pending row was removed. */
        FAILED
    }

    public static final class Result {
        public final Outcome outcome;
        /** The file's display name, for {@link Outcome#SAVED} and {@link Outcome#ALREADY_THERE}; null otherwise. */
        @Nullable
        public final String name;

        Result(Outcome outcome, @Nullable String name) {
            this.outcome = outcome;
            this.name = name;
        }
    }

    /** Where the chime's bytes come from: the APK's resource, or what a test hands in. */
    public interface Source {
        /** Null when the APK has no chime. */
        @Nullable
        InputStream open() throws IOException;
    }

    /** Set by tests, which run with no resources. */
    @Nullable
    public static Source sourceForTests;

    private NotificationSound() {
    }

    /** Copies the chime into the phone's notification sounds, unless it's there already. Blocking: call off the main thread. */
    public static Result save(Context context) {
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        InputStream in;
        try {
            in = open(context);
        } catch (IOException | RuntimeException e) {
            Logger.diagnosticError(DiagnosticCategory.SETTINGS, SOURCE, () -> "the chime could not be read from the APK", e);
            return new Result(Outcome.NO_SOUND, null);
        }
        if (in == null) {
            Logger.diagnosticInfo(DiagnosticCategory.SETTINGS, SOURCE, () -> "the APK has no " + RESOURCE_NAME);
            return new Result(Outcome.NO_SOUND, null);
        }
        try {
            // The container decides the extension and the type Android records; the stock chime is AAC in an MP4.
            byte[] head = new byte[12];
            int got = readFully(in, head);
            String mime = mime(head, got);
            String name = TITLE + extension(mime);

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return saveFile(context, resolver, mime, name, head, got, in);

            String existing = existing(resolver, collection, name);
            if (existing != null) return new Result(Outcome.ALREADY_THERE, existing);

            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.TITLE, TITLE);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER);
            values.put(MediaStore.Audio.AudioColumns.IS_NOTIFICATION, 1);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri item = resolver.insert(collection, values);
            if (item == null) {
                Logger.diagnosticError(DiagnosticCategory.SETTINGS, SOURCE, () -> "MediaStore refused a new notification sound", null);
                return new Result(Outcome.FAILED, null);
            }
            try (OutputStream out = resolver.openOutputStream(item, "w")) {
                if (out == null) throw new IOException("MediaStore gave no way to write");
                copy(out, mime, head, got, in);
            } catch (IOException | RuntimeException e) {
                remove(resolver, item);
                Logger.diagnosticError(DiagnosticCategory.SETTINGS, SOURCE, () -> "the chime could not be copied", e);
                return new Result(Outcome.FAILED, null);
            }
            ContentValues publish = new ContentValues();
            publish.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(item, publish, null, null) != 1) {
                remove(resolver, item);
                Logger.diagnosticError(DiagnosticCategory.SETTINGS, SOURCE, () -> "MediaStore did not publish the notification sound", null);
                return new Result(Outcome.FAILED, null);
            }
            Logger.diagnosticInfo(DiagnosticCategory.SETTINGS, SOURCE, () -> "saved " + name + " as " + mime);
            return new Result(Outcome.SAVED, name);
        } catch (IOException | RuntimeException e) {
            Logger.diagnosticError(DiagnosticCategory.SETTINGS, SOURCE, () -> "the chime could not be saved", e);
            return new Result(Outcome.FAILED, null);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // Read to the end or given up on; nothing to do about a close that fails.
            }
        }
    }

    /** Writes the chime, starting with the [got] bytes of [head] already read from [in]. */
    private static void copy(OutputStream out, String mime, byte[] head, int got, InputStream in) throws IOException {
        // An MP4's own title tag would become the row's title once the scanner reads the
        // file, and the picker shows that; the stock chime carries one. Small enough to
        // hold, the whole file is written without its tag, so the title is the name.
        byte[] read = "audio/mp4".equals(mime) ? readUpTo(head, got, in, MAX_UNTITLED) : null;
        if (read != null && read.length <= MAX_UNTITLED) {
            out.write(Mp4Title.untitled(read));
        } else {
            // Not MP4, or too big to hold: a straight copy, starting with whatever was read.
            if (read != null) out.write(read);
            else out.write(head, 0, got);
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
    }

    /**
     * Android 9's way in: MediaStore there has no folders or pending rows of its own, so the file
     * goes straight into the public Notifications folder and its row names that path. Writing
     * there needs storage access. Without it the first tap asks for it and fails, and the next
     * one saves.
     */
    @SuppressWarnings("deprecation")
    private static Result saveFile(Context context, ContentResolver resolver, String mime, String name,
            byte[] head, int got, InputStream in) throws IOException {
        if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            Activity front = Utils.getActivity();
            if (front != null) Utils.runOnMainThread(() -> front.requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_REQUEST));
            Logger.diagnosticInfo(DiagnosticCategory.SETTINGS, SOURCE, () -> "asked for the storage access Android 9 needs to add a sound");
            return new Result(Outcome.FAILED, null);
        }
        File folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_NOTIFICATIONS);
        File file = new File(folder, name);
        if (file.exists()) return new Result(Outcome.ALREADY_THERE, name);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("the Notifications folder couldn't be made");
        try (OutputStream out = new FileOutputStream(file)) {
            copy(out, mime, head, got, in);
        } catch (IOException | RuntimeException e) {
            removeFile(file);
            Logger.diagnosticError(DiagnosticCategory.SETTINGS, SOURCE, () -> "the chime could not be copied", e);
            return new Result(Outcome.FAILED, null);
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DATA, file.getAbsolutePath());
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.TITLE, TITLE);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.Audio.AudioColumns.IS_NOTIFICATION, 1);
        if (resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) == null) {
            removeFile(file);
            Logger.diagnosticError(DiagnosticCategory.SETTINGS, SOURCE, () -> "MediaStore refused a new notification sound", null);
            return new Result(Outcome.FAILED, null);
        }
        Logger.diagnosticInfo(DiagnosticCategory.SETTINGS, SOURCE, () -> "saved " + name + " as " + mime);
        return new Result(Outcome.SAVED, name);
    }

    private static void removeFile(File file) {
        if (file.exists() && !file.delete()) {
            Logger.diagnosticError(DiagnosticCategory.SETTINGS, SOURCE, () -> "could not remove the unfinished sound", null);
        }
    }

    /** The chime's bytes, or null when this APK carries none under a name this class knows. */
    @Nullable
    private static InputStream open(Context context) throws IOException {
        if (sourceForTests != null) return sourceForTests.open();
        int id = resourceId(context);
        if (id == 0) return null;
        return context.getResources().openRawResource(id);
    }

    /**
     * The chime's resource id, or 0. Looked up by name under each raw type, then, for a build that
     * renames it, through the sound a Facebook notification category still points at.
     */
    static int resourceId(Context context) {
        Resources resources = context.getResources();
        String pkg = context.getPackageName();
        for (String type : RESOURCE_TYPES) {
            int id = resources.getIdentifier(RESOURCE_NAME, type, pkg);
            if (id != 0) return id;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return 0;
        for (NotificationChannel channel : manager.getNotificationChannels()) {
            int id = resourceId(resources, pkg, channel.getSound());
            if (id != 0) return id;
        }
        return 0;
    }

    /** The resource an {@code android.resource://pkg/type/name} sound of this package names, or 0. */
    static int resourceId(Resources resources, String pkg, @Nullable Uri sound) {
        if (sound == null || !ContentResolver.SCHEME_ANDROID_RESOURCE.equals(sound.getScheme())) return 0;
        if (!pkg.equals(sound.getAuthority())) return 0;
        List<String> path = sound.getPathSegments();
        if (path.size() != 2) return 0;
        return resources.getIdentifier(path.get(1), path.get(0), pkg);
    }

    /** The display name of a file already in the folder under {@code name}, or null. */
    @Nullable
    private static String existing(ContentResolver resolver, Uri collection, String name) {
        String where = MediaStore.MediaColumns.DISPLAY_NAME + " = ? AND " + MediaStore.MediaColumns.RELATIVE_PATH + " = ?";
        try (Cursor cursor = resolver.query(collection, new String[] { MediaStore.MediaColumns.DISPLAY_NAME },
                where, new String[] { name, FOLDER }, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (RuntimeException e) {
            Logger.diagnosticError(DiagnosticCategory.SETTINGS, SOURCE, () -> "could not look for an earlier copy", e);
        }
        return null;
    }

    private static void remove(ContentResolver resolver, Uri item) {
        try {
            resolver.delete(item, null, null);
        } catch (RuntimeException e) {
            Logger.diagnosticError(DiagnosticCategory.SETTINGS, SOURCE, () -> "could not remove the unfinished sound", e);
        }
    }

    /**
     * The head already read plus the stream, read to its end or to just past {@code cap} bytes.
     * Longer than the cap means the file isn't held whole: the caller writes these bytes and
     * streams the rest.
     */
    private static byte[] readUpTo(byte[] head, int got, InputStream in, int cap) throws IOException {
        ByteArrayOutputStream whole = new ByteArrayOutputStream(64 * 1024);
        whole.write(head, 0, got);
        byte[] buffer = new byte[8192];
        int n;
        while (whole.size() <= cap && (n = in.read(buffer)) > 0) whole.write(buffer, 0, n);
        return whole.toByteArray();
    }

    private static int readFully(InputStream in, byte[] into) throws IOException {
        int got = 0;
        while (got < into.length) {
            int n = in.read(into, got, into.length - got);
            if (n < 0) break;
            got += n;
        }
        return got;
    }

    /** The container the first bytes name: MP4 (the stock chime), Ogg or MP3, else MP4. */
    static String mime(byte[] head, int length) {
        if (length >= 8 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') return "audio/mp4";
        if (length >= 4 && head[0] == 'O' && head[1] == 'g' && head[2] == 'g' && head[3] == 'S') return "audio/ogg";
        if (length >= 3 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') return "audio/mpeg";
        if (length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0) return "audio/mpeg";
        return "audio/mp4";
    }

    static String extension(String mime) {
        switch (mime) {
            case "audio/ogg": return ".ogg";
            case "audio/mpeg": return ".mp3";
            default: return ".m4a";
        }
    }
}
