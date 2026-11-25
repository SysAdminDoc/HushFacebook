/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.Manifest;
import android.app.DownloadManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Where a file attachment from a post lands, for Save files to Downloads (#590).
 *
 * <p>A PDF, document or other file under a post downloads through Android's DownloadManager, and
 * Facebook never gives the request a destination. DownloadManager then keeps the file in its own
 * private cache, where its notification can open it once and no file manager ever finds it. The
 * patch hands each such request here right after Facebook makes it, and while the switch is on it
 * goes to the phone's public Downloads folder under the file's own name. DownloadManager adds a
 * number when a file of that name is already there.
 *
 * <p>Off, paused, settings that aren't ready yet, Android 9 without the storage permission, or a
 * failure in here, and the request stays as Facebook made it.
 */
public final class FileDownloads {
    /** The name a file gets when its address carries none worth keeping. */
    static final String FALLBACK = "facebook-file";

    /**
     * The longest name kept, in bytes of UTF-8. A name can take 255 on the phone's storage, and the
     * rest is room for the number DownloadManager adds to a name that's taken.
     */
    static final int MAX_NAME_BYTES = 200;

    /** The longest ending kept whole when a long name is cut, the dot included (".docx" is five). */
    static final int MAX_EXTENSION_CHARS = 10;

    private FileDownloads() {
    }

    /**
     * Injection point, right after Facebook makes the DownloadManager request for a file attachment:
     * points [request] at the public Downloads folder, named after [uri].
     */
    public static void toDownloads(DownloadManager.Request request, Uri uri) {
        try {
            HookStatus.invoked(FamilyNames.FILE_DOWNLOADS);
            if (request == null || uri == null) return;
            if (!Utils.settingsReady() || !Settings.SAVE_FILES_TO_DOWNLOADS.get()) return;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !canWriteStorage()) {
                Logger.printDebug(() -> "Save files to Downloads: no storage permission, so the file stays where Facebook puts it");
                return;
            }
            String name = fileName(uri.getLastPathSegment());
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            Logger.printDebug(() -> "Save files to Downloads: " + name);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FILE_DOWNLOADS, "file download", failure);
        }
    }

    /** Before Android 10, DownloadManager refuses a public folder to an app without the storage permission. */
    private static boolean canWriteStorage() {
        Context context = Utils.getContext();
        return context != null
                && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * The file name for the last [segment] of a file's address, decoded: on Facebook's file host
     * that's the name the file was posted with. Nothing left once it's cleaned, and it's
     * {@link #FALLBACK}. Cleaning is the save folder's: separators and the characters a FAT name
     * can't hold become an underscore, invisible ones go, and dots and spaces go from both ends so
     * the file can't hide. A name longer than {@link #MAX_NAME_BYTES} is cut from the end of its
     * base, so its ending stays and the file still opens in the right app.
     */
    static String fileName(String segment) {
        String name = SaveFolder.clean(segment, Integer.MAX_VALUE);
        return name.isEmpty() ? FALLBACK : fit(name);
    }

    private static String fit(String name) {
        if (utf8Length(name) <= MAX_NAME_BYTES) return name;
        int dot = name.lastIndexOf('.');
        String ending = dot > 0 && name.length() - dot <= MAX_EXTENSION_CHARS ? name.substring(dot) : "";
        String base = SaveFolder.trim(headWithin(name.substring(0, name.length() - ending.length()),
                MAX_NAME_BYTES - utf8Length(ending)));
        return (base.isEmpty() ? FALLBACK : base) + ending;
    }

    /** The longest start of [text] that fits in [maxBytes] of UTF-8, never half a character. */
    private static String headWithin(String text, int maxBytes) {
        int bytes = 0;
        int end = 0;
        while (end < text.length()) {
            int codePoint = text.codePointAt(end);
            int size = utf8Size(codePoint);
            if (bytes + size > maxBytes) break;
            bytes += size;
            end += Character.charCount(codePoint);
        }
        return text.substring(0, end);
    }

    static int utf8Length(String text) {
        int bytes = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            bytes += utf8Size(codePoint);
            i += Character.charCount(codePoint);
        }
        return bytes;
    }

    private static int utf8Size(int codePoint) {
        if (codePoint < 0x80) return 1;
        if (codePoint < 0x800) return 2;
        if (codePoint < 0x10000) return 3;
        return 4;
    }
}
