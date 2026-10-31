/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;

/**
 * Open in another app (#182): hands a feed or Watch video's file to whatever app can play or cast
 * it, such as Web Video Caster, VLC or LocalCast. Facebook's own build has no cast code, so the
 * cast happens in the other app.
 *
 * <p>The file is the one a save would take: the best single file the video's player or post names,
 * on Meta's media servers. A video whose only source is a DASH manifest has no one address to
 * hand over (the manifest is kept as text, with video and audio apart), so it says so instead.
 * The address goes out as the intent's data and is never written to the report.
 */
public final class OpenInAnotherApp {
    private static final String SOURCE = "OpenInAnotherApp";

    /** What the data is, so players pick it up. */
    static final String VIDEO_TYPE = "video/mp4";

    private OpenInAnotherApp() {
    }

    /** The video's best single file on Meta's servers, or null when it has none. */
    @Nullable
    static String bestFile(@Nullable String videoId, @Nullable String hd, @Nullable String sd) {
        List<String> urls = new ArrayList<>();
        PlayerSources.Source source = PlayerSources.byId(videoId);
        if (source != null) add(urls, source.hdUrl);
        add(urls, hd);
        add(urls, sd);
        return RenditionPicker.bestVideo(urls, DownloadQuality.BEST);
    }

    private static void add(List<String> urls, @Nullable String url) {
        if (url != null && RenditionPicker.isHttpUrl(url) && MediaUrlPolicy.shapeRefusal(url) == null) urls.add(url);
    }

    /** The chooser over every app that plays [url]. */
    static Intent chooser(String url) {
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(Uri.parse(url), VIDEO_TYPE);
        Intent chooser = Intent.createChooser(view, L10n.t("Open the video with"));
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return chooser;
    }

    /**
     * Opens the chooser for the video, or says why it couldn't. A tap after the switch went off
     * does nothing. Never throws.
     */
    static void open(@Nullable Context context, VideoMenuItem.Video video) {
        try {
            if (!Utils.settingsReady() || !Settings.OPEN_IN_ANOTHER_APP.get()) {
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                    () -> "Open in another app tapped after its switch went off");
                return;
            }
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "Open in another app tapped");
            handOff(context, bestFile(video.id, video.hd, video.sd));
        } catch (Throwable t) {
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "Open in another app failed", t);
        }
    }

    /**
     * The feed cast button's tap (#182): opens the chooser for the video with [videoId], from the
     * file its player recorded, or says why it couldn't. Only that id's file is ever handed over,
     * so a button left from a scrolled-away video can't send it. A tap after the switch went off
     * does nothing. Never throws.
     */
    public static void cast(@Nullable Context context, @Nullable String videoId) {
        try {
            if (!Utils.settingsReady() || !Settings.FEED_VIDEO_CAST_BUTTON.get()) {
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                    () -> "cast button tapped after its switch went off");
                return;
            }
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "cast button tapped");
            handOff(context, bestFile(videoId, null, null));
        } catch (Throwable t) {
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "cast button failed", t);
        }
    }

    /**
     * The reel Cast button's tap (#182): opens the chooser for the reel with [videoId], from the
     * file its player recorded or the [hd] and [sd] addresses its own player holds, or says why it
     * couldn't. A tap after the switch went off does nothing. Never throws.
     */
    static void castReel(@Nullable Context context, @Nullable String videoId, @Nullable String hd, @Nullable String sd) {
        try {
            if (!Utils.settingsReady() || !Settings.REEL_CAST_BUTTON.get()) {
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                    () -> "reel cast button tapped after its switch went off");
                return;
            }
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "reel cast button tapped");
            handOff(context, bestFile(videoId, hd, sd));
        } catch (Throwable t) {
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "reel cast button failed", t);
        }
    }

    private static void handOff(@Nullable Context context, @Nullable String url) {
        Context application = context == null ? Utils.getContext() : context.getApplicationContext();
        if (application == null) return;
        if (url == null) {
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE,
                () -> "no single file of this video was found to hand over", null);
            Feedback.show(application,
                L10n.t(application, "Couldn't find a file for this video. Play it for a moment, then try again."), true);
            return;
        }
        try {
            application.startActivity(chooser(url));
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "opened the chooser for the video");
        } catch (ActivityNotFoundException none) {
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "no app takes a video file", none);
            Feedback.show(application, L10n.t(application, "No app on this phone can open this video"), true);
        }
    }
}
