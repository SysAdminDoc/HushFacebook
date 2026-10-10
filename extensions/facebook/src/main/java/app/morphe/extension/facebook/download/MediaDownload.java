/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/extensions/extension/src/main/java/app/andrewliang/extension/MediaDownload.java
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.extension.facebook.download;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Saves the picture or the video that the app is showing, without asking the app to do it.
 *
 * <p>Facebook ships a save feature and refuses to run it for most content. The refusal is not
 * about the address: the story or the reel is on the screen, so the app holds an address that it
 * can fetch. This takes that address and fetches it.
 *
 * <p>For a story that is the point of the whole thing. Facebook's own save runs a check for
 * licensed music first. On a story that has any, it shows a warning and then saves nothing,
 * whatever the user answers (issue #110). The patch replaces the body of that handler, so the
 * check never runs and the video arrives complete, with its sound.
 *
 * <h2>What the patch hands over</h2>
 *
 * <p>The object that holds the media, and nothing else. Each entry point is called with the object
 * of the item that the user tapped, so the file saved is always the item on the screen. There is
 * deliberately <b>no static holding the last address seen</b>. Facebook prepares the next reels
 * while the current one plays, and a measurement counted six of them built in about fifteen
 * seconds of scrolling. Anything remembered rather than passed in saves the wrong video, and
 * still reports success.
 *
 * <p>Every step goes to the diagnostic report under Downloads, and to logcat as
 * {@code morphe: MediaDownload}. Addresses are logged as kind of file and quality only.
 */
public final class MediaDownload {

    private MediaDownload() {}

    /** The source every save event carries in the diagnostic report. */
    static final String SOURCE = "MediaDownload";

    /** A step of a save, always kept: a save is rare and each one is worth a line. */
    static void info(Logger.LogMessage message) {
        Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, message);
    }

    /** A save that ended without a file, or a step that failed. */
    static void failure(Logger.LogMessage message, Throwable cause) {
        Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, message, cause);
    }

    /** A guard against a rapid tap, not a work queue. */
    private static final int MAX_IN_FLIGHT = 3;

    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

    /** Saves still running. A test waits on this for the ones it started through a hook. */
    static int savesInFlight() {
        return IN_FLIGHT.get();
    }

    /**
     * Save the media of the story that is open.
     *
     * <p>[host] is the story card. Its address is read by value, because the fields that hold it
     * are renamed on every release of the app while the addresses in them keep their shape.
     *
     * @return whether a download started. {@code false} lets the caller fall back to the app.
     */
    /**
     * The answer the story viewer's menu gets when it asks whether a story can be saved, where
     * Facebook's own [facebooks] means "it's yours". With Save any story on, every story can be.
     * Off, paused, or before the settings are ready, Facebook's answer stands, so only your own
     * stories offer Save. Never throws.
     */
    public static boolean offersSave(boolean facebooks) {
        if (facebooks) return true;
        try {
            return Utils.settingsReady() && Settings.DOWNLOAD_STORIES.get();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.STORY_DOWNLOAD, "save item", t);
            failure(() -> "could not decide whether the story menu offers Save", t);
            return false;
        }
    }

    public static boolean saveStory(Context context, Object host) {
        HookStatus.invoked(FamilyNames.STORY_DOWNLOAD);
        try {
            // Off, or before the settings are ready, Facebook's own save runs, as it
            // would unpatched.
            if (!Utils.settingsReady() || !Settings.DOWNLOAD_STORIES.get()) return false;

            // getMedia is one of the few names on the card that Facebook keeps, so it's worth trying
            // before a walk. When it answers, the walks start from the media rather than from the
            // card, which keeps them away from everything else the card holds.
            Object media = call(host, "getMedia");
            Object from = media != null ? media : host;
            int depth = media != null ? 1 : 2;
            List<String> urls = RenditionPicker.harvest(from, depth);

            // The card holds one video address, and it is 360p. The player of the same video can
            // hold a better one. So the save tries the recorded source of the player first. The
            // id it was recorded under is the video's, for the file name, and the card's own tree
            // says who posted the story and when.
            PlayerSources.Source source = PlayerSources.find(host);
            PostDetails details = PostDetails.ofCard(source == null ? null : source.videoId, host);
            if (source != null) addIfUsable(urls, source.hdUrl);
            String manifest = source == null ? null : source.manifest;

            // No player was recorded when it was built before Save any story was turned on, or
            // before the recorder ran. The media carries the manifest as text anyway: Facebook
            // builds the story's player from the media's own playlist (577 and 580).
            if (manifest == null) {
                manifest = RenditionPicker.manifestIn(from, depth);
                final String why = source == null ? "no player of the story was recorded"
                    : "its player was recorded without a manifest";
                if (manifest != null) {
                    info(() -> why + ", so the save reads the manifest its card carries, the one its player plays from");
                } else if (RenditionPicker.bestVideo(metaOnly(urls), DownloadQuality.BEST) != null) {
                    info(() -> why + " and its card carries no manifest, so the save takes the card's own file, "
                        + "the one its player plays");
                }
            }
            if (manifest != null) return beginDash(context, "the story video", manifest, urls, details);

            return begin(context, urls, true, details);
        } catch (Throwable t) {
            // Throwable and not Exception. A renamed field surfaces as NoSuchFieldError, and a
            // reflective call on a changed class surfaces as a LinkageError. Neither is an
            // Exception, and either one that reaches Facebook's click handler ends the app.
            HookStatus.threw(FamilyNames.STORY_DOWNLOAD, "story save", t);
            failure(() -> "the story save could not start", t);
            return false;
        }
    }

    /**
     * Save the video that the player is streaming.
     *
     * <p>[hdField] and [sdField] are the real names of the two fields of the source that hold a
     * single file address. [manifestField] is the real name of the field that holds the DASH
     * manifest. The patch reads those names out of the app while patching, so this file names no
     * field of its own and neither does the patch. The save tries the manifest first, because it
     * can list a better track than the two single files.
     *
     * <p>Asking by name matters here in a way that it does not for a story. The source carries a
     * third address of the same type, and it holds the subtitles. So "the first address on the
     * object" is a real way to save the wrong thing.
     *
     * @return whether a download started. {@code false} lets the caller fall back to the app.
     */
    public static boolean saveVideo(
        Context context,
        Object host,
        String hdField,
        String sdField,
        String manifestField
    ) {
        return saveVideo(context, host, hdField, sdField, manifestField, PostDetails.NONE);
    }

    /** The same, with the video's id on Facebook for the file name, or null when it isn't known. */
    static boolean saveVideo(
        Context context,
        Object host,
        String hdField,
        String sdField,
        String manifestField,
        String videoId
    ) {
        return saveVideo(context, host, hdField, sdField, manifestField, PostDetails.of(videoId));
    }

    /** The same, with everything the save knows of the post for the file name. */
    static boolean saveVideo(
        Context context,
        Object host,
        String hdField,
        String sdField,
        String manifestField,
        PostDetails details
    ) {
        try {
            List<String> urls = collectVideoUrls(host, hdField, sdField);

            String manifest = RenditionPicker.fieldValue(host, manifestField);
            if (manifest != null) return beginDash(context, "the reel", manifest, urls, details);

            return begin(context, urls, true, details);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel save", t);
            failure(() -> "the video save could not start", t);
            return false;
        }
    }

    /**
     * Save the video of a post in the feed or in Watch: the item the video patch adds to the
     * post's menu calls this when it's tapped.
     *
     * <p>[videoId] is the id the post's media carries, and the player Facebook built for the same
     * video recorded its source under it ({@link PlayerSources#rememberVideo}). That source holds
     * the DASH manifest, which lists better tracks than the single files, so it goes first, as
     * for a story. [hdUrl] and [sdUrl] are the single files the post itself names. They're the
     * fallback, and the whole answer when Facebook never built a player for the video.
     *
     * <p>Only a video is saved. The post also reaches its thumbnail, and saving a picture from a
     * tap on a video item would look like it worked.
     *
     * @return whether a download started. {@code false} means nothing was saved, and the report
     *     says why.
     */
    static boolean saveFeedVideo(Context context, String videoId, String hdUrl, String sdUrl) {
        return saveFeedVideo(context, PostDetails.of(videoId), hdUrl, sdUrl);
    }

    /** The same, with everything the post's menu read of the post for the file name. */
    static boolean saveFeedVideo(Context context, PostDetails details, String hdUrl, String sdUrl) {
        try {
            // The item was added while the switch was on; the menu can stay open past a change.
            if (!Utils.settingsReady() || !Settings.DOWNLOAD_VIDEOS.get()) return false;
            if (details == null) details = PostDetails.NONE;

            List<String> urls = new ArrayList<>();
            PlayerSources.Source source = PlayerSources.byId(details.videoId);
            if (source != null) addIfUsable(urls, source.hdUrl);
            addIfUsable(urls, hdUrl);
            addIfUsable(urls, sdUrl);

            if (source == null && urls.isEmpty()) {
                failure(() -> "nothing to save: no player of this video was recorded and the post names no file", null);
                return false;
            }

            if (source != null && source.manifest != null) {
                return beginDash(context, "the video", source.manifest, urls, details);
            }

            return begin(context, urls, false, details);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "video save", t);
            failure(() -> "the video save could not start", t);
            return false;
        }
    }

    /**
     * Saves the photo at [url], the biggest image the photo viewer's photo holds, as a story's
     * photo saves. Answers whether the save started; false lets Facebook's own save run.
     *
     * <p>It skips {@link RenditionPicker}'s ranking: the caller already chose by the image's own
     * width and height, and a full-size photo's address can carry the size marker the ranking
     * reads as a thumbnail's. The address still has to be on Meta's media servers.
     */
    static boolean savePhoto(Context context, String url, PostDetails details) {
        if (url == null || metaOnly(Collections.singletonList(url)).isEmpty()) {
            failure(() -> "nothing to save: the photo's image wasn't on Meta's media servers", null);
            return false;
        }
        Context safe = ready(context);
        if (safe == null) return false;
        info(() -> "saving the photo's biggest image, a " + describe(url));
        start(safe, false, details == null ? PostDetails.NONE : details, fileJob(safe, url, Downloader.Kind.IMAGE));
        return true;
    }

    /**
     * Saves the photos at [urls], each as {@link #savePhoto} saves one and named from the same
     * index of [details], one after another: each save is the usual one, with its notification and
     * Cancel button, and the next starts when it ends and a save slot is free, so a post's photos
     * never crowd out other saves. Addresses off Meta's media servers are skipped. Answers the
     * thread running them, or null when nothing could start.
     */
    static Thread savePhotos(Context context, List<String> urls, List<PostDetails> details) {
        List<Integer> usable = new ArrayList<>();
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            if (url != null && !metaOnly(Collections.singletonList(url)).isEmpty()) usable.add(i);
        }
        if (usable.isEmpty()) {
            failure(() -> "nothing to save: no photo of the post was on Meta's media servers", null);
            return null;
        }
        Context safe = ready(context);
        if (safe == null) return null;
        int count = usable.size();
        info(() -> "saving " + count + " photo(s) of a post, one after another");
        Thread chain = new Thread(() -> {
            for (int i : usable) {
                PostDetails known = i < details.size() && details.get(i) != null ? details.get(i) : PostDetails.NONE;
                try {
                    while (IN_FLIGHT.get() >= MAX_IN_FLIGHT) Thread.sleep(250);
                    start(safe, false, known, fileJob(safe, urls.get(i), Downloader.Kind.IMAGE)).join();
                } catch (InterruptedException stop) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable t) {
                    // Nothing can leave this thread; the next photo still gets its turn.
                    failure(() -> "a photo of the post could not start", t);
                }
            }
        }, "hushfacebook-photo-saves");
        chain.setDaemon(true);
        chain.setPriority(Thread.NORM_PRIORITY - 1);
        chain.start();
        return chain;
    }

    // ---------------------------------------------------------------- internals

    /** The named fields first, then whatever else the object can reach. */
    private static List<String> collectVideoUrls(Object host, String hdField, String sdField) {
        List<String> urls = new ArrayList<>();

        addIfUsable(urls, RenditionPicker.fieldValue(host, hdField));
        addIfUsable(urls, RenditionPicker.fieldValue(host, sdField));

        // Either the object is not the source itself, or the release moved the fields. Ranking
        // by value still answers, and it is the reason this is not only a pair of reads.
        if (urls.isEmpty()) urls.addAll(RenditionPicker.harvest(host, 1));

        return urls;
    }

    private static void addIfUsable(List<String> urls, String url) {
        if (url != null && RenditionPicker.isHttpUrl(url)) urls.add(url);
    }

    private static Object call(Object host, String method) {
        if (host == null) return null;

        try {
            return host.getClass().getMethod(method).invoke(host);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Pick the best address of those found and fetch it.
     *
     * <p>This ranks the item both ways and keeps the better answer. It does not read the type of
     * the item from the app. The app records that type in an enum whose constants move between
     * releases, and one constant mistaken for another saves the wrong file without a word.
     *
     * <p>[imagesToo] is false for a caller that knows the item is a video, so a thumbnail can't
     * stand in for a video it couldn't find. [details] is what the save knows of the post for the
     * file name.
     */
    private static boolean begin(Context context, List<String> urls, boolean imagesToo, PostDetails details) {
        if (urls == null || urls.isEmpty()) {
            failure(() -> "nothing to save: the item carried no address", null);
            return false;
        }

        // The walk collects any address the object can reach, a link in a caption included. Only
        // Meta's media servers are candidates, so a foreign address can't outrank a real one.
        final int found = urls.size();
        urls = metaOnly(urls);
        if (urls.isEmpty()) {
            failure(() -> "nothing to save: none of the " + found + " addresses was on Meta's media servers", null);
            return false;
        }

        DownloadQuality quality = quality();
        String video = RenditionPicker.bestVideo(urls, quality);
        String image = imagesToo ? RenditionPicker.bestOf(urls, false) : null;

        boolean isVideo = video != null;
        String chosen = isVideo ? video : image;

        if (chosen == null) {
            final int candidates = urls.size();
            final String kind = imagesToo ? "a file" : "a video file";
            failure(() -> "nothing to save: none of the " + candidates + " addresses was " + kind, null);
            return false;
        }

        Context safe = ready(context);
        if (safe == null) return false;

        saving(isVideo, chosen, urls, quality, Dash.SINGLE_FILE, null);
        Downloader.Kind kind = isVideo ? Downloader.Kind.VIDEO : Downloader.Kind.IMAGE;
        start(safe, isVideo, details, fileJob(safe, chosen, kind));
        return true;
    }

    /**
     * The report line of a single file's save: [chosen] and every candidate of [urls]. Every one,
     * so a saved file that is smaller than expected can be told apart from a ranking that chose
     * badly. Each as its kind of file and quality, never its name or address: a whole address is a
     * signed, working handle to the user's content, a CDN file name carries the object's id, and
     * the report is pasted into public issues. [better] is the manifest's picture the file is
     * below, or null.
     */
    private static void saving(boolean isVideo, String chosen, List<String> urls, DownloadQuality quality, Dash dash,
            DashManifest.Track better) {
        StringBuilder all = new StringBuilder();
        for (String url : urls) {
            if (all.length() > 0) all.append(", ");
            all.append(describe(url));
        }

        final int candidates = urls.size();
        info(() -> "saving " + (isVideo ? "video" : "image")
            + " " + describe(chosen)
            + " from " + candidates + " candidate(s): " + all
            + (isVideo ? qualityNote(quality) + singleFileNote(dash) + belowNote(better) : ""));
    }

    /** What the report adds to a save line whose picture is below the manifest's [better], or nothing. */
    private static String belowNote(DashManifest.Track better) {
        return better == null ? "" : ", below the manifest's " + better + ", the best it offers within the Download quality";
    }

    /**
     * The largest picture of [tracks] that fits [quality] and is larger than a saved one of quality
     * [saved], whatever its format, or null: the picture Facebook's player can show that the save
     * didn't keep. AV1 on a phone with no AV1 decoder is one, and so is any picture of a DASH save
     * that failed and fell back to the single file. For
     * the smallest file there's never one, and a saved quality nobody stated has none either. Purely
     * informational: this doesn't ask whether the phone could have written [better], only whether
     * the manifest offered it. {@link #noticeablyLower} is what decides whether the person saving
     * is told.
     */
    static DashManifest.Track better(List<DashManifest.Track> tracks, int saved, DownloadQuality quality) {
        DashManifest.Track better = null;
        for (DashManifest.Track track : tracks) {
            if (!track.isVideo()) continue;
            int picture = picture(track, quality);
            if (saved <= 0 || picture <= saved || picture > quality.ceiling) continue;
            if (better == null || picture > picture(better, quality)) better = track;
        }
        return better;
    }

    /** A track's quality as [quality] weighs it: its short side at the best, else its label. */
    static int picture(DashManifest.Track track, DownloadQuality quality) {
        return quality == DownloadQuality.BEST ? track.shortSide() : track.quality();
    }

    /**
     * Whether a saved picture of [saved] pixels on its short side is far enough below [best], the
     * manifest's best within the Download quality, that the person saving should be told. Below
     * 720, or below two thirds of [best]: a 720p save whose only bigger rendition is a codec nothing
     * on the phone could turn into a file isn't told, since 720 is what the phone would have saved
     * either way, but a 360p save with a real 1080p rendition on offer is. The gap is what's judged,
     * never whether the phone could write [best]: {@link #better} already found it regardless of
     * that, and the report names it either way.
     */
    static boolean noticeablyLower(int saved, int best) {
        return saved > 0 && best > saved && (saved < 720 || (long) saved * 3 < (long) best * 2);
    }

    /**
     * The quality the save starting now asks for. Read once per save, when it starts, so a save
     * already running keeps the one it began with. Never throws: before the settings are ready,
     * or when they can't be read, it's the best, as every save was before the setting existed.
     */
    static DownloadQuality quality() {
        try {
            if (!Utils.settingsReady()) return DownloadQuality.BEST;
            DownloadQuality chosen = Settings.DOWNLOAD_QUALITY.get();
            return chosen == null ? DownloadQuality.BEST : chosen;
        } catch (Throwable t) {
            return DownloadQuality.BEST;
        }
    }

    /** What the report adds to a save line for a quality below the best. */
    private static String qualityNote(DownloadQuality quality) {
        return quality == DownloadQuality.BEST ? "" : ", quality setting " + quality.fileValue;
    }

    /**
     * Whether the save starting now keeps to files other apps can open
     * ({@link Settings#DOWNLOAD_COMPATIBLE}). Read when the save starts, like the quality. Never
     * throws: before the settings are ready, or when they can't be read, it's off, as every save
     * was before the switch existed.
     */
    static boolean compatibleSaves() {
        try {
            return Utils.settingsReady() && Settings.DOWNLOAD_COMPATIBLE.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /** What the report adds to a DASH save line whose tracks were kept to what other apps can open. */
    private static String compatibleNote(boolean compatible) {
        return compatible ? ", kept to files other apps can open" : "";
    }

    /**
     * What the report adds to a DASH save line whose sound is xHE-AAC ({@code mp4a.40.42}).
     * {@link DashManifest#bestAudio} takes it only when the manifest offers no AAC-LC or HE-AAC, as
     * a 580 reel with AV1 pictures and four xHE-AAC tracks did, and some players can't play it (#14).
     */
    private static String soundNote(DashManifest.Track audio, boolean reencode) {
        if (audio == null || !AacReencode.isXhe(audio.codecs)) return "";
        return reencode
            ? ", the sound is xHE-AAC because the manifest offers no AAC-LC or HE-AAC, made AAC-LC for apps that turn xHE-AAC down"
            : ", the sound is xHE-AAC because the manifest offers no AAC-LC or HE-AAC, and some players can't play xHE-AAC";
    }

    /** What the report adds to a DASH save line whose picture is made H.264 before the join. */
    private static String pictureNote(DashManifest.Track video, boolean transcode) {
        return transcode
            ? ", the manifest offers no H.264, so the picture is made H.264 from " + video.codecs + " for apps that take "
                + "only H.264"
            : "";
    }

    /**
     * What the report adds to a single file's save line when saves other apps can open took it over
     * tracks the manifest would otherwise have saved. The single file's own formats aren't read, so
     * the line says why it was taken, not what it holds.
     */
    private static String singleFileNote(Dash dash) {
        return dash == Dash.SINGLE_FILE_FOR_OTHER_APPS
            ? ", taken over the manifest's better tracks, which aren't H.264 with AAC-LC or HE-AAC sound"
            : "";
    }

    /** Why a save with a manifest took the single file. */
    enum Dash {
        /** As it would with saves other apps can open off. */
        SINGLE_FILE,
        /** It's left to the single file because saves other apps can open ruled out the tracks that beat it. */
        SINGLE_FILE_FOR_OTHER_APPS
    }

    /** The save of one single file at [url]: the job every save of a single file runs. */
    static Job fileJob(Context application, String url, Downloader.Kind kind) {
        DownloadQuality quality = quality();
        return (writer, progress) -> saveFile(application, url, kind, null, java.util.Collections.emptyList(), null,
            quality, writer, progress);
    }

    /**
     * One checked file, fetched into the cache and then published. Its work file keeps, with those
     * of every other running save, to the free space ({@link DashSave#fetchWork}). A saved video's
     * report line says what the file holds, and [why] when a manifest's tracks were passed over
     * for it (bounded; null when the file was simply the pick). [offered] is what the person saving
     * is told against, once the file is down: the saved picture is measured from the file itself,
     * never guessed from its address, and named in the report whenever it beats what was saved,
     * whatever wrote it. [writable] is the one track, if any, the phone is known to be able to
     * write, such as the manifest's own pick passed over for this file: a shortfall against it, by
     * its measured pixels rather than a label, is flagged without {@link #noticeablyLower}'s
     * tolerance for a picture nothing could have written, since that excuse doesn't apply here, but
     * only when the file was actually read back, [writable] itself fits [quality]'s ceiling, and the
     * gap clears a small pixel margin a rounding or a container quirk could otherwise trip.
     * [quality] is the setting read when the save began, so a change while a long fetch runs can't
     * move what this file is judged against.
     */
    private static Downloader.Result saveFile(Context application, String url, Downloader.Kind kind, String why,
            List<DashManifest.Track> offered, DashManifest.Track writable, DownloadQuality quality,
            MediaStoreWriter writer, Downloader.Progress progress) {
        java.io.File folder = DashSave.workFolder(application);
        if (folder == null) return Downloader.Result.fail(Downloader.Status.WRITE_ERROR, "no cache folder");
        java.io.File file = null;
        try {
            file = java.io.File.createTempFile(kind.name().toLowerCase(Locale.US), ".part", folder);
            Downloader.Result fetched = DashSave.fetchWork(url, kind, file, policyFor(application), cap(), progress);
            if (!fetched.ok()) return fetched;
            // A photo the CDN sent as AVIF goes to the gallery as a JPEG, like Facebook's own saves.
            String mime = kind == Downloader.Kind.IMAGE ? PhotoFormat.jpegFromAvif(file, fetched.mime) : fetched.mime;
            Downloader.Result published = Downloader.publish(file, mime, writer, progress);
            boolean lower = false;
            boolean refused = false;
            if (published.ok() && kind == Downloader.Kind.VIDEO) {
                DashSave.ReadBack holds = DashSave.savedFormat(file);
                refused = holds.refused;
                info(() -> "the saved file holds " + holds.text + (why == null ? "" : ". Saved in place of the manifest's "
                    + "tracks because " + bounded(why)));
                if (!offered.isEmpty() || writable != null) {
                    int savedShortSide = DashSave.savedVideoShortSide(file);
                    DashManifest.Track better = offered.isEmpty() ? null : better(offered, savedShortSide, quality);
                    if (better != null) {
                        final DashManifest.Track shown = better;
                        info(() -> "the save measures " + savedShortSide + "p, below the manifest's " + shown
                            + ", the best it offers within the Download quality");
                        lower = noticeablyLower(savedShortSide, better.shortSide());
                    }
                    // A file the phone couldn't read back measures 0, never a shortfall. A track
                    // above the ceiling isn't a real alternative for this setting, whatever its
                    // picture. And the compare itself is pixels on both sides: writable.quality()
                    // is picture()'s label below the best, which an unusually shaped video can
                    // carry far above what it actually measures. A small gap, kept to 16 pixels,
                    // is a rounding or a container quirk, not a shortfall worth a nag.
                    if (!lower && writable != null && quality != DownloadQuality.SMALLEST && savedShortSide > 0
                            && writable.quality() <= quality.ceiling
                            && writable.shortSide() - savedShortSide > 16) {
                        lower = true;
                    }
                }
            }
            Downloader.Result saved = published.ok() && lower ? published.lower() : published;
            return refused ? saved.refused() : saved;
        } catch (Throwable t) {
            return Downloader.Result.fail(Downloader.Status.WRITE_ERROR, "the cache could not hold the file");
        } finally {
            DashSave.discard(file);
        }
    }

    /** The policy every save a test drives uses in place of the real one. Never set on a phone. */
    static volatile MediaUrlPolicy policyForTests;

    /**
     * Meta's address rules, with the lookup fence up only while the socket goes straight to the
     * answer: no proxy for the address and no VPN on the network. See {@link MediaUrlPolicy}.
     */
    static MediaUrlPolicy policyFor(Context application) {
        MediaUrlPolicy forced = policyForTests;
        if (forced != null) return forced;
        return new MediaUrlPolicy(MediaUrlPolicy.DNS, url -> !MediaUrlPolicy.proxied(url) && !onVpn(application));
    }

    /**
     * The cap every save a test drives holds its files to in place of the real one, so a test can
     * reach it with small files. Never set on a phone.
     */
    static volatile long capForTests;

    /** The most one save may put in the gallery: {@link Downloader#MAX_BYTES} on a phone. */
    static long cap() {
        long forced = capForTests;
        return forced > 0 ? forced : Downloader.MAX_BYTES;
    }

    /**
     * Whether the network a save would use runs through a VPN, a fake-IP proxy client's among
     * them. Facebook holds ACCESS_NETWORK_STATE. No answer keeps the fence up.
     */
    private static boolean onVpn(Context context) {
        try {
            android.net.ConnectivityManager manager =
                (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return false;
            android.net.Network active = manager.getActiveNetwork();
            android.net.NetworkCapabilities capabilities = active == null ? null : manager.getNetworkCapabilities(active);
            return capabilities != null && capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN);
        } catch (Throwable t) {
            return false;
        }
    }

    private static List<String> metaOnly(List<String> urls) {
        List<String> kept = new ArrayList<>();
        for (String url : urls) {
            if (MediaUrlPolicy.shapeRefusal(url) == null) kept.add(url);
        }
        return kept;
    }

    /**
     * Save the best video track and audio track of a DASH manifest, if the video track is larger
     * than all single-file addresses of the item.
     *
     * <p>The manifest can list tracks that no single file has. A story card and its player hold
     * only 360p files, but the manifest lists tracks up to 1080p. The device joins the two tracks
     * into one file. If this fails, the save gets the best single file, so the user still gets a
     * file.
     *
     * <p>Below the best quality, the track and the single file are each the one that suits the
     * setting ({@link DashManifest#pickVideo}, {@link RenditionPicker#bestVideo}), and the manifest
     * is used only when its track suits it better than the file does. On a tie the single file
     * wins: one fetch and no join.
     *
     * <p>With saves other apps can open on ({@link #compatibleSaves}), a known H.264 and AAC-LC or
     * HE-AAC pair ({@link DashManifest#pick}) wins over the unchecked single file, even if that
     * file has a higher quality label. A manifest with no such pair leaves the
     * save to the single file, Facebook's own MP4, whose formats aren't read here. With no single
     * file either, the save takes the tracks it would take with the switch off and the report says
     * so, since a file some apps turn down beats no file.
     *
     * <p>All of that happens on the save's own worker. Read on the tap, the manifest held the thread
     * that draws Facebook for as long as the parse took. One over {@link DashManifest#withinLimits}
     * isn't read at all, and the single file is saved.
     *
     * @return whether a save started. With a manifest there's always something to try, so only too
     *     many saves at once stops one. A manifest that turns out to offer nothing, beside no single
     *     file, then ends as a failed save rather than in Facebook's own handling.
     */
    private static boolean beginDash(Context context, String label, String manifest, List<String> urls,
            PostDetails details) {
        Context safe = ready(context);
        if (safe == null) return false;
        List<String> candidates = new ArrayList<>(urls);
        start(safe, true, details, (writer, progress) -> saveDash(safe, label, manifest, candidates, writer, progress));
        return true;
    }

    /**
     * {@link #beginDash}'s save, on the worker: the manifest's tracks or the single file. The
     * quality setting is read once, here at the start, and passed to every helper this calls rather
     * than read again by each: {@link #saveSingleVideo} and {@link #dashJob} used to read it a
     * second time on the same save, so a setting changed between those reads, however unlikely on
     * one thread with no I/O in between, could judge the fallback against a quality other than the
     * one that picked it.
     */
    private static Downloader.Result saveDash(Context application, String label, String manifest, List<String> urls,
            MediaStoreWriter writer, Downloader.Progress progress) {
        DownloadQuality quality = quality();
        if (!DashManifest.withinLimits(manifest)) {
            info(() -> "the manifest of " + label + " is over the limits a save reads (" + manifest.length()
                + " characters), saving the single file");
            return saveSingleVideo(application, urls, java.util.Collections.emptyList(), Dash.SINGLE_FILE,
                "the manifest is over the limits a save reads", null, quality, writer, progress);
        }
        List<DashManifest.Track> tracks = new ArrayList<>();
        for (DashManifest.Track track : DashManifest.parse(manifest)) {
            if (MediaUrlPolicy.shapeRefusal(track.url) == null) tracks.add(track);
        }
        urls = metaOnly(urls);
        boolean compatible = compatibleSaves();
        boolean allowAv1 = DashSave.canWriteAv1();
        // What a save below is weighed against when it tells the person it's lower. Not with saves
        // other apps can open on: that switch passes better pictures over by choice, and its own
        // report lines say so.
        List<DashManifest.Track> offered = compatible ? java.util.Collections.emptyList() : tracks;

        // What the pick below chose from, as each track's type, codec, size and bitrate. Never its
        // address: the report is pasted into public issues.
        Logger.diagnosticDebug(DiagnosticCategory.DOWNLOADS, SOURCE,
            () -> "the manifest of " + label + " offers " + tracks.size() + " track(s): " + tracks);

        String fallback = RenditionPicker.bestVideo(urls, quality);
        int fallbackQuality = fallback == null ? 0 : RenditionPicker.qualityOf(fallback);
        // With the switch on, a phone that can make xHE-AAC sound AAC-LC keeps the H.264 picture
        // of a video that has no other sound, and one that can make VP9, AV1 or H.265 H.264 keeps
        // the picture of a video that has no H.264 track (#77).
        DashManifest.Pick converted = DashManifest.pick(tracks, allowAv1, quality, compatible,
            compatible && AacReencode.available(), compatible ? VideoTranscode::canConvert : null);
        // Converting takes a while, so it's only worth it for a picture better than the single file.
        DashManifest.Pick kept = converted != null && converted.transcodeVideo
            && !beatsFile(converted.video, fallback, fallbackQuality, quality) ? null : converted;
        DashManifest.Pick pick = kept;
        // What the switch off would pick: when that beats the single file and the kept pick
        // doesn't, the switch is why the single file is saved.
        DashManifest.Pick usual = compatible ? DashManifest.pick(tracks, allowAv1, quality, false) : kept;
        Dash leftToFile = compatible && usual != null && beatsFile(usual.video, fallback, fallbackQuality, quality)
            ? Dash.SINGLE_FILE_FOR_OTHER_APPS : Dash.SINGLE_FILE;

        if (kept == null && compatible) {
            if (usual != null && fallback != null) {
                info(() -> "the manifest of " + label + " has no H.264 video with AAC-LC or HE-AAC sound, "
                    + "saving the single file instead");
                return saveSingleVideo(application, urls, offered, leftToFile,
                    "the manifest has no H.264 video with AAC-LC or HE-AAC sound", null, quality, writer, progress);
            }
            if (usual != null) {
                info(() -> "nothing of " + label + " is in a format other apps can open, saving it as the switch "
                    + "off would: " + usual.video + (usual.audio == null ? "" : " + " + usual.audio));
                pick = usual;
            }
        }

        if (pick == null) {
            info(() -> "the manifest of " + label + " has no track to save: " + tracks);
            return saveSingleVideo(application, urls, offered, Dash.SINGLE_FILE, "the manifest has no track to save",
                null, quality, writer, progress);
        }

        DashManifest.Track video = pick.video;
        DashManifest.Track audio = pick.audio;
        // Sound is xHE-AAC only when the manifest offers no AAC-LC or HE-AAC, and Messenger, gallery
        // players and many editors turn it down (HushMessenger #38, #14). So a phone that can make
        // it AAC-LC does, with saves other apps can open off too. A re-encode that fails fails the
        // join, and the single file follows as with the switch on.
        boolean reencodeSound = pick.reencodeSound
            || (audio != null && AacReencode.isXhe(audio.codecs) && AacReencode.available());
        boolean transcodeVideo = pick.transcodeVideo;
        boolean keptCompatible = kept != null && compatible;

        if (!keptCompatible && !beatsFile(video, fallback, fallbackQuality, quality)) {
            // video already passed the writability check that picked it: if the fallback's address
            // overstated its own quality, the shortfall against video is held to a plain test, not
            // noticeablyLower's tolerance for a picture nothing could have written.
            return saveSingleVideo(application, urls, offered, Dash.SINGLE_FILE, null, video, quality, writer,
                progress);
        }

        // A track the muxer can't write can be a larger picture than the one saved.
        DashManifest.Track better = better(offered, picture(video, quality), quality);
        info(() -> "saving " + label + " from its DASH manifest: " + video
            + (audio == null ? ", no sound track" : " + " + audio)
            + ", instead of " + (fallback == null ? "nothing" : describe(fallback))
            + qualityNote(quality) + compatibleNote(keptCompatible) + pictureNote(video, transcodeVideo)
            + soundNote(audio, reencodeSound) + belowNote(better));

        Downloader.Result result = dashJob(application, video, audio, transcodeVideo, reencodeSound, fallback, quality)
            .run(writer, progress);
        boolean lower = better != null && noticeablyLower(picture(video, quality), better.shortSide());
        return result.ok() && lower ? result.lower() : result;
    }

    /**
     * The best single video file of [urls], for a save whose manifest didn't win: [dash] says why
     * for the save line, and [why] for the line of what was saved, or null when the file was simply
     * the better pick. [writable] is the manifest's own picked track when this file was chosen over
     * it by a guess rather than for a deliberate compatibility trade-off: {@link #saveFile} holds a
     * shortfall against it to a plain test, since the phone could write it. A video only, since the
     * save was started as one. The save line below reports against a guess of [video]'s own
     * quality, since nothing has been fetched yet to measure; once the file is down, {@link
     * #saveFile} weighs the person saving's "lower" note against what it actually measures, not
     * this guess. [quality] is what {@link #saveDash} already read for this save, not read again
     * here.
     */
    private static Downloader.Result saveSingleVideo(Context application, List<String> urls,
            List<DashManifest.Track> tracks, Dash dash, String why, DashManifest.Track writable,
            DownloadQuality quality, MediaStoreWriter writer, Downloader.Progress progress) {
        List<String> meta = metaOnly(urls);
        String video = RenditionPicker.bestVideo(meta, quality);
        if (video == null) {
            final int found = urls.size();
            failure(() -> "nothing to save: the manifest gave no track and none of the " + found
                + " addresses was a video file on Meta's media servers", null);
            return Downloader.Result.fail(Downloader.Status.WRITE_ERROR, "nothing to save");
        }
        DashManifest.Track better = better(tracks, RenditionPicker.qualityOf(video), quality);
        saving(true, video, meta, quality, dash, better);
        return saveFile(application, video, Downloader.Kind.VIDEO, why, tracks, writable, quality, writer, progress);
    }

    /**
     * Whether [video] suits [quality] better than the single file [fallback] of quality
     * [fallbackQuality]: at the best quality a larger picture, below it a nearer fit. On a tie the
     * single file wins, one fetch and no join.
     */
    private static boolean beatsFile(DashManifest.Track video, String fallback, int fallbackQuality,
            DownloadQuality quality) {
        if (quality == DownloadQuality.BEST) return video.shortSide() > fallbackQuality;
        return fallback == null || quality.compare(video.quality(), fallbackQuality) < 0;
    }

    /**
     * The DASH save of [video] and [audio], then the single file [fallback] when that fails. Not
     * when the person cancelled it, though: the fallback would start the save over. A fallback
     * below [video]'s picture says so, held to a plain test: [video] already passed the writability
     * check that picked it, so there's no picture-nothing-could-write excuse to make room for here.
     * The quality setting is read once, when this job is built, so a change while the DASH save
     * runs can't move what the fallback is judged against.
     */
    static Job dashJob(Context application, DashManifest.Track video, DashManifest.Track audio, String fallback) {
        return dashJob(application, video, audio, false, false, fallback, quality());
    }

    /**
     * As above, with [quality] already read by {@link #saveDash} rather than read again here,
     * [transcodeVideo] when [video] is to be made H.264 first, and [reencodeSound] when the
     * xHE-AAC [audio] is to be made AAC-LC first.
     */
    private static Job dashJob(Context application, DashManifest.Track video, DashManifest.Track audio,
            boolean transcodeVideo, boolean reencodeSound, String fallback, DownloadQuality quality) {
        return (writer, progress) -> {
            Downloader.Result result = DashSave.save(application, video, audio, transcodeVideo, reencodeSound, writer,
                policyFor(application), cap(), progress);
            if (result.ok() || fallback == null || result.status == Downloader.Status.CANCELLED) return result;
            // A failed gallery publication is terminal. A fallback can repair a fetch or join,
            // but mustn't start another fetch after this save's publication state has failed.
            if (progress instanceof SaveControl.Save
                    && ((SaveControl.Save) progress).state() == SaveControl.State.FAILED) return result;

            DashManifest.Track better = better(java.util.Collections.singletonList(video),
                RenditionPicker.qualityOf(fallback), quality);
            failure(() -> "the DASH save ended with " + result + ", saving " + describe(fallback) + belowNote(better),
                null);
            return saveFile(application, fallback, Downloader.Kind.VIDEO, "the DASH save ended with " + result,
                java.util.Collections.singletonList(video), video, quality, writer, progress);
        };
    }

    /**
     * The context to save with, or {@code null} when a save cannot start now.
     *
     * <p>Never the Activity. A download outlives the screen that started it, and holding the
     * Activity across it is a leak, and Facebook's own tooling reports it.
     */
    private static Context ready(Context context) {
        if (context == null) return null;

        if (IN_FLIGHT.get() >= MAX_IN_FLIGHT) {
            failure(() -> "too many saves at once", null);
            return null;
        }

        Context application = context.getApplicationContext();
        return application != null ? application : context;
    }

    /**
     * One save on the worker thread. It writes through [writer], tells [progress] how far it has
     * got, stops when [progress] says it was cancelled, and returns the result.
     */
    interface Job {
        Downloader.Result run(MediaStoreWriter writer, Downloader.Progress progress);
    }

    /**
     * Runs [job] on its own worker thread, and hands the thread back so a test can wait for it.
     * The save shows a notification with its progress and a Cancel button while it runs.
     */
    static Thread start(Context application, boolean video, Job job) {
        return start(application, video, PostDetails.NONE, job);
    }

    /** As above, naming a video from [videoId] when the file name asks for it. */
    static Thread start(Context application, boolean video, String videoId, Job job) {
        return start(application, video, PostDetails.of(videoId), job);
    }

    /** Hands each save's details to a test, which can't see the name of a save that fails. Never set on a phone. */
    static volatile java.util.function.Consumer<PostDetails> detailsForTests;

    /** As above, naming the video from whatever of the post [details] holds and the file name asks for. */
    static Thread start(Context application, boolean video, PostDetails details, Job job) {
        final PostDetails known = details == null ? PostDetails.NONE : details;
        java.util.function.Consumer<PostDetails> watching = detailsForTests;
        if (watching != null) watching.accept(known);
        IN_FLIGHT.incrementAndGet();
        // Until the worker runs, whatever throws here gives back the slot and the save it began, or
        // enough such failures would block every save.
        SaveControl.Save begun = null;
        boolean started = false;
        try {
            final SaveControl.Save save = SaveControl.begin(application, video);
            begun = save;
            // With no notification to cancel it from, the list of saves in the settings is the only way
            // to stop it, so the start says where that is, for long enough to read.
            if (save.manager != null) {
                Feedback.show(application, L10n.t(application, "Saving..."), false);
            } else {
                Feedback.show(application,
                    L10n.t(application, "Saving... To cancel, open Downloads in Hushfacebook settings."), true);
            }

            Thread worker = new Thread(() -> {
                MediaStoreWriter writer = new MediaStoreWriter(application, video, known);

                try {
                    // What a save in a process Android ended left behind goes before this one makes
                    // anything. It runs once per process.
                    SaveLeftovers.sweepOnce(application);

                    Downloader.Result result = job.run(writer, save);
                    boolean cancelled = result.status == Downloader.Status.CANCELLED;
                    if (result.ok() || cancelled) info(() -> "save finished: " + result);
                    else failure(() -> "save finished: " + result, null);
                    String text = message(application, result.status, writer.savedLocation(), result.lower);
                    if (result.ok()) SaveControl.showCompleted(save, writer);
                    if (result.ok() && result.refused && !compatibleSaves()) {
                        info(() -> "the saved file has a track WhatsApp and some editors refuse, with Save videos "
                            + "other apps can open off");
                        Feedback.show(application, refusedMessage(application, SaveControl.showRefused(application, text)),
                            true);
                    } else {
                        Feedback.show(application, text, !result.ok() && !cancelled);
                    }
                } catch (Throwable t) {
                    // Nothing can leave this thread. Facebook installs its own handler for uncaught
                    // exceptions and reports them as its own crashes.
                    failure(() -> "the save failed", t);
                    Feedback.show(application, L10n.t(application, "Download failed. Try again in a moment."), true);
                } finally {
                    save.end();
                    IN_FLIGHT.decrementAndGet();
                }
            }, "hushfacebook-save");

            // A thread that ends when the copy ends leaves nothing behind in a process that is not
            // ours. A pool parks a thread there for as long as Facebook runs.
            worker.setDaemon(true);
            worker.setPriority(Thread.NORM_PRIORITY - 1);
            worker.start();
            started = true;
            return worker;
        } finally {
            if (!started) {
                if (begun != null) begun.end();
                IN_FLIGHT.decrementAndGet();
            }
        }
    }

    /** A reason for the report, cut to 160 characters. */
    private static String bounded(String reason) {
        return reason.length() <= 160 ? reason : reason.substring(0, 157) + "...";
    }

    /**
     * What the toast at the end of a save says, in the phone's language. [lower] when the saved
     * picture is below the best one Facebook offered within the quality setting.
     */
    static String message(Context application, Downloader.Status status, String location, boolean lower) {
        switch (status) {
            case OK:
                if (lower) {
                    return location == null
                        ? L10n.t(application, "Saved to the gallery in lower quality than on Facebook")
                        : L10n.f(application, "Saved to %1$s in lower quality than on Facebook", L10n.isolate(location));
                }
                return location == null
                    ? L10n.t(application, "Saved to the gallery")
                    : L10n.f(application, "Saved to %1$s", L10n.isolate(location));
            case EXPIRED:
                return L10n.t(application, "Link expired. Reopen the item and try again.");
            case REFUSED:
                return L10n.t(application, "Not saved: that isn't a Facebook photo or video");
            case TOO_LARGE:
                return L10n.t(application, "Not saved: the file is over 512 MB");
            case CANCELLED:
                return L10n.t(application, "Save canceled");
            default:
                return L10n.t(application, "Download failed. Try again in a moment.");
        }
    }

    /**
     * The toast at the end of a save WhatsApp and some editors may refuse, with Save videos other
     * apps can open off. [notified] when the notification with the button to that switch is up;
     * without it the toast says where the switch is. Two lines at most either way, since Android 12
     * cuts a toast there.
     */
    static String refusedMessage(Context application, boolean notified) {
        return notified
            ? L10n.t(application, "Saved, but WhatsApp and some editors may not accept it")
            : L10n.t(application, "Saved, but WhatsApp may not accept it. Open Downloads in Hushfacebook settings to fix that.");
    }

    /**
     * Enough of an address to tell one candidate from another in a report: its kind of file and
     * its quality, and no more.
     *
     * <p>A whole address is a signed, working handle to the content of the user, and the log can
     * be read by anything else on the device. The file name isn't safe either: on Meta's CDN it
     * carries the object's own id ({@code 475148478_1134540631592283_..._n.jpg}), and the report
     * is pasted into public issues.
     */
    static String describe(String url) {
        int query = url.indexOf('?');
        String withoutQuery = query < 0 ? url : url.substring(0, query);

        int slash = withoutQuery.lastIndexOf('/');
        String file = slash < 0 ? withoutQuery : withoutQuery.substring(slash + 1);
        int dot = file.lastIndexOf('.');
        String extension = dot < 0 ? "" : file.substring(dot + 1).toLowerCase(Locale.US);
        if (!extension.matches("[a-z0-9]{1,5}")) extension = "file";

        int quality = RenditionPicker.qualityOf(url);
        return extension + " (" + (quality > 0 ? quality + "p" : "unknown") + ")";
    }
}
