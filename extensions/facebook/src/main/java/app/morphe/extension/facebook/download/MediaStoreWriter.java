/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/extensions/extension/src/main/java/app/andrewliang/extension/MediaStoreWriter.java
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.extension.facebook.download;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;

/**
 * Writes a fetched file into the gallery of the device.
 *
 * <p>From Android 10 scoped storage is the only storage there is and MediaStore is the only way in.
 * Nothing there needs a permission: an app can insert media that it creates, and needs no
 * permission for it.
 *
 * <p>Facebook's Android 9+ build runs on Android 9 too (#102), where MediaStore takes no folder and
 * no pending flag. There the file is written straight into the public folder, with the storage
 * permission Facebook declares, and goes into the gallery once its last byte is in
 * ({@link #openFile}). A save without the permission asks for it and fails, and the next one works.
 *
 * <p>The entry is created with {@code IS_PENDING} set, and it is published only after the last
 * byte arrives. So a failed fetch never leaves a playable looking file of the wrong length in the
 * gallery. A save that the system stops half way through the copy leaves a pending row; the next
 * start of Facebook removes it ({@link SaveLeftovers}), and the platform would after about a week.
 */
final class MediaStoreWriter implements Downloader.Sink {

    /** The source every gallery event carries in the diagnostic report. */
    private static final String SOURCE = "MediaStoreWriter";

    /** Android 9's request for the storage permission. Facebook's screen gets the answer and ignores it. */
    private static final int STORAGE_REQUEST = 0x4846;

    private final Context context;
    private final boolean video;

    /** What the save knows of the post, for the file name's tokens. Never null. */
    private final PostDetails details;

    private Uri item;
    /** Android 9's file in the public folder, and the collection its row goes into once it's whole. */
    private File file;
    private Uri fileCollection;
    private OutputStream stream;
    private String location;
    private String mediaMime;
    private boolean committed;

    MediaStoreWriter(Context applicationContext, boolean video) {
        this(applicationContext, video, PostDetails.NONE);
    }

    /** A writer knowing the video's id on Facebook, or null, and nothing else of the post. */
    MediaStoreWriter(Context applicationContext, boolean video, String videoId) {
        this(applicationContext, video, PostDetails.of(videoId));
    }

    MediaStoreWriter(Context applicationContext, boolean video, PostDetails details) {
        this.context = applicationContext;
        this.video = video;
        this.details = details == null ? PostDetails.NONE : details;
    }

    /** What the message names: {@link #location}, after the card's name when the save went to an SD card. */
    private String shownLocation;

    /**
     * {@code Movies/Facebook} or {@code Pictures/Facebook}, or the top folder and the folder the
     * person chose in their place, such as {@code Download/Clips}, for the message to the user. A
     * save on an SD card starts with the card's name, such as {@code SanDisk SD card/Movies/Facebook}.
     */
    String savedLocation() {
        return shownLocation;
    }

    /** Only a successfully committed gallery row may be offered to another app. */
    Uri publishedUri() {
        return committed ? item : null;
    }

    String publishedMime() {
        return committed ? mediaMime : null;
    }

    @Override
    public OutputStream open(String mimeFromServer) throws IOException {
        String mime = mime(mimeFromServer);
        mediaMime = mime;
        committed = false;
        // Read here, per file, like the folder. The top folder decides the collection too: only
        // the Downloads one takes Download, and it takes both kinds.
        SaveTo to = SaveTo.current();
        // One folder name for both kinds, so a story's photos and its videos land side by side,
        // unless a kind has a subfolder of its own inside it. Both are read here, per file, and
        // cleaned where they're read: a slash or a dot segment in a setting can't turn this into a
        // path of the setting's choosing.
        location = to.directory(video) + "/" + SaveFolder.path(video);

        // The same folders on an SD card when one is picked and in the phone (#274), and the
        // message names the card first.
        SaveStorage.Card card = SaveStorage.current(context);
        shownLocation = card == null ? location : card.label + "/" + location;
        Uri collection = to.collection(video, card == null ? null : card.volume);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return openFile(mime, collection);

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name(mime, collection));
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        // No leading slash. MediaStore checks this against its own directory names.
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, location);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        ContentResolver resolver = context.getContentResolver();
        item = resolver.insert(collection, values);
        if (item == null) throw new IOException("the gallery refused a new entry");
        // Written down before any byte, so a process ended during the copy leaves a row the next
        // save can find and remove. See SaveLeftovers. A row the list couldn't hold goes again
        // before the copy starts: nothing would ever remove it.
        if (!SaveLeftovers.pending(context, item)) {
            Uri row = item;
            item = null;
            try {
                if (resolver.delete(row, null, null) <= 0) {
                    Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "the gallery kept an unfinished "
                            + "entry that isn't on the list of pending rows; Android removes it after about a week", null);
                }
            } catch (Throwable t) {
                Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE,
                        () -> "could not remove an unfinished entry that isn't on the list of pending rows", t);
            }
            throw new IOException("the list of pending gallery rows could not hold the new entry");
        }

        stream = resolver.openOutputStream(item, "w");
        if (stream == null) throw new IOException("the gallery gave no way to write");

        return stream;
    }

    /**
     * Android 9's way in: a new file in the public folder under {@link #location}, which needs
     * Facebook's storage permission. Without it, Android's dialog asking for it comes up over
     * Facebook and this save fails, so the one after the person allows it goes through.
     */
    @SuppressWarnings("deprecation") // The public folder is Android 9's only way in.
    private OutputStream openFile(String mime, Uri collection) throws IOException {
        if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            Activity front = Utils.getActivity();
            if (front != null) Utils.runOnMainThread(() -> front.requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_REQUEST));
            throw new IOException("Facebook has no storage permission yet");
        }
        File folder = new File(Environment.getExternalStorageDirectory(), location);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("the save folder couldn't be made");
        file = free(folder, name(mime, collection));
        if (file == null) throw new IOException("every numbered name was taken in the save folder");
        fileCollection = collection;
        stream = new FileOutputStream(file);
        return stream;
    }

    /** Android 9's file named [name] in [folder], numbered as MediaStore numbers one, or null past (31). */
    @Nullable
    static File free(File folder, String name) {
        File file = new File(folder, name);
        if (!file.exists()) return file;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String suffix = dot > 0 ? name.substring(dot) : "";
        for (int number = 1; number <= 31; number++) {
            file = new File(folder, stem + " (" + number + ")" + suffix);
            if (!file.exists()) return file;
        }
        return null;
    }

    /** Android 9's commit: the whole file goes into the gallery as a row of its own. */
    @SuppressWarnings("deprecation") // DATA is how Android 9 learns a file's place.
    private void commitFile() throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DATA, file.getAbsolutePath());
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, mediaMime);
        values.put(MediaStore.MediaColumns.SIZE, file.length());
        item = context.getContentResolver().insert(fileCollection, values);
        if (item == null) throw new IOException("the gallery refused the saved file");
        file = null;
        committed = true;
    }

    @Override
    public void commit() throws IOException {
        close();
        if (file != null) {
            commitFile();
            return;
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        if (context.getContentResolver().update(item, values, null, null) != 1) {
            throw new IOException("the gallery did not publish the pending entry");
        }
        SaveLeftovers.settled(context, item);
        committed = true;
    }

    @Override
    public void abandon() {
        committed = false;
        try {
            close();
        } catch (Throwable ignored) {
            // A failed close must not keep the pending row from being removed.
        }

        if (file != null) {
            // Android 9's half-written file, which no gallery row names yet.
            if (file.exists() && !file.delete()) {
                Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "the unfinished file stays in the save folder", null);
            }
            file = null;
            return;
        }
        if (item == null) return;
        Uri row = item;
        item = null;

        try {
            if (context.getContentResolver().delete(row, null, null) > 0) {
                SaveLeftovers.settled(context, row);
            } else {
                Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE,
                        () -> "the gallery did not remove the unfinished entry", null);
            }
        } catch (Throwable t) {
            // Left on the list, so the sweep when Facebook next starts tries again.
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "could not remove the unfinished entry", t);
        }
    }

    private void close() throws IOException {
        if (stream == null) return;

        try {
            stream.close();
        } finally {
            stream = null;
        }
    }

    /**
     * The type to record, taken from the server.
     *
     * <p>The type decides the name, and never the other way round. Facebook itself gets this
     * wrong: its own save writes AVIF bytes into a file called {@code .jpg}, which leaves the
     * gallery unable to draw a thumbnail for it. A copy of that behaviour copies the fault.
     */
    private String mime(String fromServer) {
        if (fromServer != null && EXTENSIONS.containsKey(fromServer)) {
            boolean isVideo = fromServer.startsWith("video/");
            // A video request that answers with a picture, or the reverse, is an error page or a
            // thumbnail. A save of it looks like success.
            if (isVideo == video) return fromServer;
        }

        return video ? "video/mp4" : "image/jpeg";
    }

    private String name(String mime, Uri collection) {
        String suffix = EXTENSIONS.get(mime);
        if (suffix == null) suffix = video ? ".mp4" : ".jpg";

        Date now = new Date();
        // The person's template for the kind, read here, per file, and cleaned where it's read. As
        // it ships it's Facebook's own FB_VID_ or FB_IMG_ name, so files from this patch and from
        // Facebook sit together.
        String template = video ? FileNameTemplate.current() : FileNameTemplate.currentPhoto();
        reportMissingTokens(template);
        String name = (video ? FileNameTemplate.videoName(template, now, details)
                : FileNameTemplate.photoName(template, now, details)) + suffix;
        if (!inSaveFolder(collection, name)) return name;
        // MediaStore would number it, up to (31), and then refuse the save.
        String taken = video ? FileNameTemplate.takenVideoName(template, now, details)
                : FileNameTemplate.takenPhotoName(template, now, details);
        if (taken == null) return name;
        Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                () -> "the file name was already in the save folder, so the time of the save went on the end");
        return taken + suffix;
    }

    /**
     * What {@link #inSaveFolder} asks MediaStore: a file of one name in one folder. MediaStore keeps
     * a folder with a slash on the end, so both spellings are asked for. Package-private so a test
     * gallery can answer exactly this and nothing else.
     */
    static final String SAME_NAME_IN_FOLDER = MediaStore.MediaColumns.DISPLAY_NAME + " = ? AND ("
            + MediaStore.MediaColumns.RELATIVE_PATH + " = ? OR " + MediaStore.MediaColumns.RELATIVE_PATH + " = ?)";

    /**
     * Whether a file named [displayName] is already in the save folder of [collection]. Only
     * published files count, as MediaStore leaves pending and trashed ones out of a query unless
     * asked: their files carry other names until they're published. A file this app can't see, or a
     * lookup MediaStore refuses, counts as not there, and MediaStore's own numbering still keeps the
     * save. The report gets the kind of failure only, never the name, which can hold the poster's.
     */
    @SuppressWarnings("deprecation") // Android 9's public folder.
    private boolean inSaveFolder(Uri collection, String displayName) {
        // Android 9's MediaStore keeps no folder to ask about, and the folder is right there.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return new File(new File(Environment.getExternalStorageDirectory(), location), displayName).exists();
        }
        try (Cursor cursor = context.getContentResolver().query(collection,
                new String[] { MediaStore.MediaColumns._ID }, SAME_NAME_IN_FOLDER,
                new String[] { displayName, location + "/", location }, null)) {
            return cursor != null && cursor.moveToFirst();
        } catch (Throwable t) {
            String kind = t.getClass().getSimpleName();
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                    () -> "could not look for the file name in the save folder (" + kind + ")");
            return false;
        }
    }

    /**
     * One line in the report when [template] asks for something this save doesn't know, saying
     * what the name does about it: left out when the name still tells saves apart, and the date
     * and time on the end when it wouldn't, since MediaStore refuses a name once it has numbered
     * it 31 times. Never the id or the poster's name: the report is pasted into public issues.
     */
    private void reportMissingTokens(String template) {
        List<String> missing = new ArrayList<>();
        boolean asksId = video ? FileNameTemplate.usesVideoId(template) : FileNameTemplate.usesPhotoId(template);
        if (asksId && !details.hasVideoId()) missing.add(video ? "the video id" : "the photo id");
        if (FileNameTemplate.usesOwner(template) && !details.hasOwner()) missing.add("the poster");
        if (FileNameTemplate.usesOwnerId(template) && !details.hasOwnerId()) missing.add("the poster's id");
        if (FileNameTemplate.usesPosted(template) && !details.hasPosted()) missing.add("the post date");
        if (missing.isEmpty()) return;

        boolean apart = video
            ? FileNameTemplate.keepsApart(template, details.hasVideoId(), details.hasOwner(),
                details.hasOwnerId(), details.hasPosted())
            : FileNameTemplate.keepsPhotosApart(template, details.hasVideoId(), details.hasOwner(),
                details.hasOwnerId(), details.hasPosted());
        String asked = missing.size() == 1 ? missing.get(0)
            : String.join(", ", missing.subList(0, missing.size() - 1)) + " and " + missing.get(missing.size() - 1);
        String has = missing.size() == 1 ? "none" : missing.size() == 2 ? "neither" : "none of them";
        String instead = !apart ? "the date and time go on the end"
            : missing.size() == 1 ? "it's left out" : "they're left out";
        final String line = "the file name asks for " + asked + " and this save has " + has + ", so " + instead;
        Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> line);
    }

    /**
     * The types worth recording, and the name that each one takes.
     *
     * <p>{@code MimeTypeMap} is not used. It answers nothing for {@code image/avif} on many
     * devices and its table is different from one ROM to the next.
     */
    private static final Map<String, String> EXTENSIONS = new HashMap<>();

    static {
        EXTENSIONS.put("video/mp4", ".mp4");
        EXTENSIONS.put("video/x-m4v", ".m4v");
        EXTENSIONS.put("video/quicktime", ".mov");
        EXTENSIONS.put("video/webm", ".webm");
        EXTENSIONS.put("video/3gpp", ".3gp");
        EXTENSIONS.put("image/jpeg", ".jpg");
        EXTENSIONS.put("image/png", ".png");
        EXTENSIONS.put("image/webp", ".webp");
        EXTENSIONS.put("image/heic", ".heic");
        EXTENSIONS.put("image/heif", ".heif");
        EXTENSIONS.put("image/avif", ".avif");
        EXTENSIONS.put("image/gif", ".gif");
    }
}
