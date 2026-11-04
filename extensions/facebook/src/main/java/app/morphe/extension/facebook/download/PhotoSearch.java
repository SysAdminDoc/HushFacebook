/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;

/**
 * Search photo (#245, #248): hands one photo of a post to the app picked in Android's chooser, such
 * as Google Lens, which looks up what's in it and picks out its text to copy.
 *
 * <p>The tap fetches the photo at the biggest size the post carries, from Meta's media servers
 * only, into a folder of its own in the cache ({@link #FOLDER}; not the saves' work folder, whose
 * cleanup could delete it while the other app still reads it). An AVIF is made a JPEG first, as a
 * save does, since few apps read AVIF. {@link PhotoSearchFiles} serves the file for reading only,
 * and only the app picked in the chooser gets the grant. Nothing leaves the phone before that pick,
 * and a tap only ever sends the one photo it was made for. Each search drops the photos earlier
 * ones left behind.
 */
public final class PhotoSearch {
    private PhotoSearch() {}

    static final String SOURCE = "PhotoSearch";

    /** The cache folder the photos wait in for the other app. */
    static final String FOLDER = "hushfacebook-photo-search";

    /** Each photo's file is this and a number, the number its address ends in. */
    static final String PREFIX = "photo-";

    /** The provider's authority after the package's name: [package].hushfacebook.photosearch. */
    public static final String AUTHORITY_SUFFIX = ".hushfacebook.photosearch";

    /** The most one photo may hold. Facebook's biggest photos are a few megabytes. */
    static final long MAX_BYTES = 40L * 1024L * 1024L;

    /** What the intent says the photo is when the fetch couldn't tell. */
    static final String ANY_IMAGE = "image/*";

    private static final AtomicLong NEXT = new AtomicLong(System.currentTimeMillis());

    /** Save any photo and this switch on, Hushfacebook running and its settings ready. */
    static boolean enabled() {
        return Utils.settingsReady() && Settings.DOWNLOAD_PHOTOS.get() && Settings.POST_MENU_PHOTO_SEARCH.get();
    }

    /**
     * A tap on Search photo: fetches [url] on a thread of its own, then opens the chooser, or says
     * why it couldn't. Answers that thread, or null when nothing started. A tap after the switch
     * went off does nothing. Never throws.
     */
    @Nullable
    static Thread tap(@Nullable Context context, @Nullable String url) {
        try {
            if (!enabled()) {
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "Search photo tapped after its switch went off");
                return null;
            }
            Context application = context == null ? Utils.getContext() : context.getApplicationContext();
            if (application == null) return null;
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "Search photo tapped");
            if (url == null || MediaUrlPolicy.shapeRefusal(url) != null) {
                Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE,
                    () -> "the photo's image wasn't on Meta's media servers", null);
                couldNotGet(application);
                return null;
            }
            Thread fetch = new Thread(() -> {
                Fetched photo = fetch(application, url);
                Utils.runOnMainThread(() -> {
                    if (photo == null) couldNotGet(application);
                    else open(application, photo);
                });
            }, "Hushfacebook photo search");
            fetch.start();
            return fetch;
        } catch (Throwable t) {
            // It runs inside Facebook's click dispatch, on the thread that draws the app.
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "the Search photo tap failed", t);
            return null;
        }
    }

    /** A fetched photo: its number in the provider's addresses and the type it holds. */
    static final class Fetched {
        final long number;
        final String mime;

        Fetched(long number, String mime) {
            this.number = number;
            this.mime = mime;
        }
    }

    /**
     * Fetches [url] into the search folder, after clearing what earlier searches left. Blocking.
     * Answers the photo, or null when it couldn't be fetched, with the reason in the report and
     * never the address. Never throws.
     */
    @Nullable
    static Fetched fetch(Context application, String url) {
        try {
            File folder = folder(application);
            if (folder == null) {
                Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "no cache folder for the photo", null);
                return null;
            }
            clear(folder);
            long number = NEXT.incrementAndGet();
            File file = new File(folder, PREFIX + number);
            Downloader.Result fetched = Downloader.fetch(url, Downloader.Kind.IMAGE, file,
                MediaDownload.policyFor(application), MAX_BYTES, Downloader.SILENT);
            if (!fetched.ok()) {
                Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "the photo wasn't fetched: " + fetched, null);
                return null;
            }
            String mime = PhotoFormat.jpegFromAvif(file, fetched.mime, MAX_BYTES);
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                () -> "fetched the photo, " + file.length() + " bytes of " + mime);
            return new Fetched(number, mime == null ? ANY_IMAGE : mime);
        } catch (Throwable t) {
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "fetching the photo failed", t);
            return null;
        }
    }

    /** The search folder, created when it isn't there, or null when the cache can't hold one. */
    @Nullable
    static File folder(Context context) {
        File folder = new File(context.getCacheDir(), FOLDER);
        // mkdirs() answers false when the folder is already there.
        return folder.mkdirs() || folder.isDirectory() ? folder : null;
    }

    /** The file of photo [number], or null when [number] isn't one a search could have made. */
    @Nullable
    static File file(Context context, long number) {
        if (number <= 0) return null;
        File folder = new File(context.getCacheDir(), FOLDER);
        return new File(folder, PREFIX + number);
    }

    /** Deletes every photo an earlier search left. */
    private static void clear(File folder) {
        File[] left = folder.listFiles();
        if (left == null) return;
        for (File old : left) Downloader.delete(old);
    }

    /** The address photo [number] is served at to the picked app. */
    static Uri uri(Context application, long number) {
        return new Uri.Builder()
            .scheme("content")
            .authority(application.getPackageName() + AUTHORITY_SUFFIX)
            .appendPath(PhotoSearchFiles.PHOTO)
            .appendPath(Long.toString(number))
            .build();
    }

    /** The chooser that hands [photo] to the app picked in it, and grants that app alone a read. */
    static Intent chooser(Context application, Fetched photo) {
        Uri uri = uri(application, photo.number);
        Intent send = new Intent(Intent.ACTION_SEND)
            .setType(photo.mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(L10n.t(application, "Search photo"), uri));
        Intent chooser = Intent.createChooser(send, L10n.t(application, "Search photo with"));
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return chooser;
    }

    private static void open(Context application, Fetched photo) {
        try {
            application.startActivity(chooser(application, photo));
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "opened the chooser for the photo");
        } catch (ActivityNotFoundException none) {
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "no app takes a photo", none);
            Feedback.show(application, L10n.t(application, "No app on this phone can search with a photo"), true);
        } catch (Throwable t) {
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "the chooser didn't open", t);
            couldNotGet(application);
        }
    }

    private static void couldNotGet(Context application) {
        Feedback.show(application, L10n.t(application, "Couldn't get this photo. Try again in a moment."), true);
    }
}
