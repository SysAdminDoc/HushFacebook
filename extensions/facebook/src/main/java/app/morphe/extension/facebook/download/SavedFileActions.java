/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import app.morphe.extension.facebook.misc.AppIcons;
import app.morphe.extension.facebook.misc.AppLock;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Local file actions from a completed notification. They enter through the existing launcher
 * activity, so Android 12's notification restrictions never require a receiver to start a screen.
 * A tap is consumed before Facebook reads the intent. It can be delivered to a resumed screen
 * for at most 30 seconds; no file list or durable handle is written by this class. The one thing it
 * keeps is a random key each button carries, so a request another app sends is never taken for a tap.
 *
 * <p>While Facebook is locked ({@link AppLock#covering}) nothing opens or shares: a tap waits, with
 * the same 30 seconds, and is delivered once the lock's check passes. If the lock outlasts that,
 * the tap is dropped and the file stays closed.
 */
public final class SavedFileActions {
    static final String TAG = "hushfacebook-completed:";
    static final String OPEN = "app.morphe.extension.facebook.OPEN_SAVED_FILE";
    static final String SHARE = "app.morphe.extension.facebook.SHARE_SAVED_FILE";
    /** The extra a button's intent carries this install's key in. */
    static final String KEY = "app.morphe.extension.facebook.SAVED_FILE_KEY";
    /** Where the key is kept, in storage Android never backs up, so it survives a process restart. */
    static final String KEY_FILE = "hushfacebook-saved-file-key";
    private static final int KEY_BYTES = 16;
    private static final long LIFETIME_MS = 30_000;
    private static final AtomicReference<Request> pending = new AtomicReference<>();
    private static volatile WeakReference<Activity> resumed = new WeakReference<>(null);
    @Nullable
    private static volatile String key;

    private SavedFileActions() { }

    static PendingIntent button(Context application, Uri uri, String mime, boolean share) {
        // The launcher entry that's on now: a picked app icon turns the default one off (#119).
        Intent entry = new Intent(share ? SHARE : OPEN)
                .setComponent(AppIcons.launcher(application))
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Facebook's launcher activity is open to every app, so a tap proves it came from this
        // install's own button by carrying its key. Extras stay out of PendingIntent identity.
        entry.putExtra(KEY, key(application));
        // Data and action participate in PendingIntent identity; extras alone do not.
        return PendingIntent.getActivity(application, 0, entry,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * Called by the existing activity intent hooks, including after a process restart. Only an
     * intent carrying this install's key is a tap: another app can send the same action and a
     * guessed gallery address to any exported Facebook screen, and that's left as it came.
     */
    public static void receive(Intent intent) {
        if (intent == null || (!OPEN.equals(intent.getAction()) && !SHARE.equals(intent.getAction()))) return;
        if (!fromOwnButton(intent)) {
            Logger.printInfo(() -> "Saved file actions: an open or share request without this install's key was ignored");
            return;
        }
        Request request = new Request(intent.getData(), intent.getType(), SHARE.equals(intent.getAction()));
        intent.setAction(Intent.ACTION_MAIN).setDataAndType(null, null);
        intent.setClipData(null);
        intent.removeExtra(KEY);
        pending.set(request);
    }

    /** Whether [intent] carries this install's key. Any doubt, a key that can't be read included, is no. */
    private static boolean fromOwnButton(Intent intent) {
        String given = intent.getStringExtra(KEY);
        Context context = Utils.getContext();
        if (given == null || context == null) return false;
        if (same(given, key(context))) return true;
        // Another Facebook process can keep its own key after this one read it, and its buttons carry that one.
        String kept = readKey(new File(context.getNoBackupFilesDir(), KEY_FILE));
        return kept != null && same(given, kept);
    }

    private static boolean same(String given, String own) {
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.US_ASCII), own.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * This install's key: random, made once and kept in no-backup storage. When it can't be read or
     * kept, a new one serves until the process ends, so buttons made meanwhile still work.
     */
    static String key(Context context) {
        String known = key;
        if (known != null) return known;
        synchronized (SavedFileActions.class) {
            if (key != null) return key;
            File file = new File(context.getNoBackupFilesDir(), KEY_FILE);
            String kept = readKey(file);
            if (kept == null) {
                byte[] random = new byte[KEY_BYTES];
                new SecureRandom().nextBytes(random);
                StringBuilder hex = new StringBuilder(KEY_BYTES * 2);
                for (byte b : random) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
                kept = hex.toString();
                writeKey(file, kept);
                // A process that made its key at the same moment may have written last; both then use that one.
                String written = readKey(file);
                if (written != null) kept = written;
            }
            key = kept;
            return kept;
        }
    }

    @Nullable
    private static String readKey(File file) {
        if (!file.isFile() || file.length() != KEY_BYTES * 2) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[KEY_BYTES * 2];
            int read = 0;
            while (read < bytes.length) {
                int count = in.read(bytes, read, bytes.length - read);
                if (count < 0) return null;
                read += count;
            }
            String text = new String(bytes, StandardCharsets.US_ASCII);
            return text.matches("[0-9a-f]{" + (KEY_BYTES * 2) + "}") ? text : null;
        } catch (Exception failure) {
            return null;
        }
    }

    private static void writeKey(File file, String value) {
        File part = new File(file.getPath() + ".part");
        try {
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write(value.getBytes(StandardCharsets.US_ASCII));
            }
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            if (!part.renameTo(file)) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }
        } catch (Exception failure) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            Logger.printException(() -> "Saved file actions: could not keep the key; buttons work until Facebook restarts", failure);
        }
    }

    /** Forgets the key read into memory, as a process restart does. For tests. */
    static void forgetKey() {
        key = null;
    }

    /** The launcher may hand over to another Facebook activity, so delivery waits for resume. */
    public static void onResumed(Activity activity) {
        resumed = new WeakReference<>(activity);
        Request request = pending.get();
        if (request == null) return;
        if (request.expired()) {
            pending.compareAndSet(request, null);
            return;
        }
        if (locked()) return; // Waits for the lock's check, or for its 30 seconds to run out.
        if (!pending.compareAndSet(request, null)) return;
        Context application = activity.getApplicationContext();
        if (!Utils.runOnBackgroundThread(() -> {
            String mime = readableMime(application, request);
            Utils.runOnMainThread(() -> {
                if (request.expired()) return;
                if (locked()) {
                    // Facebook locked while the file was being read: hold the tap for the unlock.
                    pending.compareAndSet(null, request);
                    return;
                }
                Activity host = resumed.get();
                if (host == null || host.isFinishing() || host.isDestroyed()) {
                    if (!request.expired()) pending.compareAndSet(null, request);
                    return;
                }
                if (mime == null) {
                    Feedback.show(application, L10n.t(application, "That saved file is no longer available."), true);
                } else {
                    launch(host, request, mime);
                }
            });
        })) {
            Feedback.show(application, L10n.t(application, "Couldn't open or share that saved file. Try again."), true);
        }
    }

    /** The lock's check passed over [activity]: a tap that waited for it is delivered now. */
    public static void onUnlocked(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        onResumed(activity);
    }

    /** Whether Facebook is locked. A lock that can't be read counts as locked. */
    private static boolean locked() {
        try {
            return AppLock.covering();
        } catch (Throwable failure) {
            return true;
        }
    }

    public static void onPaused(Activity activity) {
        if (resumed.get() == activity) resumed = new WeakReference<>(null);
    }

    /** No filename, post fields or source address are requested, logged or added to either intent. */
    private static String readableMime(Context application, Request request) {
        if (!mediaItem(request.uri) || request.mime == null
                || !(request.mime.startsWith("video/") || request.mime.startsWith("image/"))) return null;
        // Owner and pending columns came in Android 10 and trash in 11. Asking for one an older
        // MediaStore lacks fails the whole query, so Android 9 checks only the type of its own save.
        boolean owned = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
        boolean trash = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
        String[] columns = trash
                ? new String[]{MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                        MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.IS_TRASHED}
                : owned
                ? new String[]{MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                        MediaStore.MediaColumns.IS_PENDING}
                : new String[]{MediaStore.MediaColumns.MIME_TYPE};
        try (Cursor row = application.getContentResolver().query(request.uri, columns, null, null, null)) {
            if (row == null || !row.moveToFirst()) return null;
            if (owned && (!application.getPackageName().equals(row.getString(1)) || row.getInt(2) != 0)) return null;
            if (trash && row.getInt(3) != 0) return null;
            // MediaStore can normalize its MIME after indexing. Use that current type at the tap.
            String mime = row.getString(0);
            if (mime == null || !(mime.startsWith("video/") || mime.startsWith("image/"))) return null;
            try (ParcelFileDescriptor file = application.getContentResolver().openFileDescriptor(request.uri, "r")) {
                return file == null ? null : mime;
            }
        } catch (Exception failure) {
            return null; // A deleted, unreadable or no longer owned row exposes no file action.
        }
    }

    private static boolean mediaItem(Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme()) || !MediaStore.AUTHORITY.equals(uri.getAuthority())
                || uri.getQuery() != null || uri.getFragment() != null) return false;
        List<String> path = uri.getPathSegments();
        if (path.size() < 3 || !("external".equals(path.get(0)) || "external_primary".equals(path.get(0)))) return false;
        boolean collection = path.size() == 3 && "downloads".equals(path.get(1))
                || path.size() == 4 && ("video".equals(path.get(1)) || "images".equals(path.get(1)))
                && "media".equals(path.get(2))
                // Android 9 keeps a Download save in the files table (SaveTo.collection).
                || path.size() == 3 && "file".equals(path.get(1)) && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q;
        if (!collection) return false;
        try { return Long.parseLong(path.get(path.size() - 1)) > 0; }
        catch (NumberFormatException invalid) { return false; }
    }

    private static void launch(Activity activity, Request request, String mime) {
        if (locked()) {
            pending.compareAndSet(null, request);
            return;
        }
        Intent target = new Intent(request.share ? Intent.ACTION_SEND : Intent.ACTION_VIEW)
                .setType(mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        target.setClipData(ClipData.newRawUri(mime.startsWith("video/")
                ? L10n.t(activity, "Video saved") : L10n.t(activity, "Photo saved"), request.uri));
        if (request.share) target.putExtra(Intent.EXTRA_STREAM, request.uri);
        else target.setDataAndType(request.uri, mime);
        try {
            activity.startActivity(request.share ? Intent.createChooser(target, L10n.t(activity, "Share")) : target);
        } catch (ActivityNotFoundException missing) {
            Feedback.show(activity.getApplicationContext(),
                    L10n.t(activity, "There's no app here that can open or share this saved file."), true);
        } catch (Exception failure) {
            Feedback.show(activity.getApplicationContext(),
                    L10n.t(activity, "Couldn't open or share that saved file. Try again."), true);
        }
    }

    private static final class Request {
        final Uri uri;
        final String mime;
        final boolean share;
        final long at = SystemClock.elapsedRealtime();
        Request(Uri uri, String mime, boolean share) {
            this.uri = uri;
            this.mime = mime;
            this.share = share;
        }
        boolean expired() { return SystemClock.elapsedRealtime() - at > LIFETIME_MS; }
    }
}
