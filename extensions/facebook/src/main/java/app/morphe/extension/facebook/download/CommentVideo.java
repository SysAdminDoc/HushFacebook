/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.content.Context;

import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;
import kotlin.jvm.functions.Function1;

/**
 * The Save video row Download any video adds to a comment's long-press sheet (#318).
 *
 * <p>Facebook's sheet has no way to save a video someone posted in a comment. The patch asks
 * {@link #clickFor} for a tap action right after the sheet's rows are built, where Save GIF goes
 * ({@link CommentGif}), and adds a row of Facebook's own kind with {@link #title} when it gets one.
 * The video is read then, while the comment's model is live: the first attachment whose media says
 * it's a Video, names a video file, or is one Facebook built a player for. A GIF's media, which has
 * an animated image, is Save GIF's and gets no video row. A tap saves the video the way a post's
 * Download to phone does ({@link MediaDownload#saveFeedVideo}): the player's DASH manifest at the
 * quality set in Downloads first, the single files the media names after. While links are sent
 * instead of saved ({@link SendLink}), the row is Send to app and hands over the video's link.
 *
 * <p>The file name never names the commenter. The save knows the video's id and nothing of who
 * posted it, so {@code {owner}} and {@code {owner_id}} are left out of the name, and
 * {@code {video_id}} keeps two comments' videos apart.
 *
 * <p>Nothing here names a class or member Facebook renames. The comment is a kept GraphQL model and
 * its parts are read through the tree accessors Facebook keeps.
 */
public final class CommentVideo implements Function1<Object, Object> {

    /** The source every event of the row carries in the diagnostic report. */
    static final String SOURCE = "CommentVideo";

    static final int PLAYABLE_URL = "playable_url".hashCode();
    static final int HD_PLAYABLE_URL = "hd_playable_url".hashCode();
    static final int PLAYABLE_URL_QUALITY_HD = "playable_url_quality_hd".hashCode();

    /** The video's id on Facebook, or null when its media names none. */
    final String id;

    /** The single HD file the media names, or null. */
    final String hd;

    /** The single SD file the media names, or null. */
    final String sd;

    CommentVideo(String id, String hd, String sd) {
        this.id = id;
        this.hd = hd;
        this.sd = sd;
    }

    /**
     * The tap action of a Save video row for [comment], or null when it gets none: the switch is
     * off, Hushfacebook is paused or not ready, or the comment holds no video. Never throws: this
     * runs inside Facebook's sheet code.
     */
    public static Function1<Object, Object> clickFor(Object comment) {
        try {
            if (!enabled() || comment == null) return null;
            HookStatus.invoked(FamilyNames.VIDEO_DOWNLOAD);
            CommentVideo row = of(comment);
            if (row == null) return null;
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "comment sheet: a video, "
                + (row.id == null ? "no id" : "an id") + ", HD " + MediaDownload.describe(row.hd)
                + ", SD " + MediaDownload.describe(row.sd) + ", player " + (PlayerSources.byId(row.id) == null ? "none" : "built"));
            HookStatus.bound(FamilyNames.VIDEO_DOWNLOAD, "comment sheet video row");
            return row;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "comment sheet video row", t);
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE,
                () -> "could not add Save video to a comment's sheet", t);
            return null;
        }
    }

    /** The row's title: Save video, or Send to app while links are sent instead. */
    public static String title() {
        return SendLink.sending() ? L10n.t("Send to app") : L10n.t("Save video");
    }

    /** The switch on, Hushfacebook running and its settings ready. */
    static boolean enabled() {
        return Utils.settingsReady() && Settings.DOWNLOAD_VIDEOS.get();
    }

    /** The first video of [comment]'s attachments, or null when it holds none. */
    static CommentVideo of(Object comment) {
        List<?> attachments = PostDetails.trees(comment, CommentGif.ATTACHMENTS);
        if (attachments == null) return null;
        for (Object attachment : attachments) {
            Object media = PostDetails.tree(attachment, CommentGif.MEDIA);
            // A released tree's reads go to native code with nothing behind it, so it's skipped unread.
            if (!PostDetails.isLiveTree(media) || PostDetails.tree(media, CommentGif.ANIMATED_IMAGE) != null) continue;
            String id = PostDetails.string(media, PostDetails.ID);
            String hd = PostDetails.string(media, HD_PLAYABLE_URL);
            if (!RenditionPicker.isHttpUrl(hd)) hd = PostDetails.string(media, PLAYABLE_URL_QUALITY_HD);
            String sd = PostDetails.string(media, PLAYABLE_URL);
            if (VideoMenuItem.VIDEO_TYPE.equals(typeName(media)) || RenditionPicker.isHttpUrl(hd)
                    || RenditionPicker.isHttpUrl(sd) || PlayerSources.byId(id) != null) {
                return new CommentVideo(id, hd, sd);
            }
        }
        return null;
    }

    /** The GraphQL type name of a live [model], or null. */
    private static String typeName(Object model) {
        if (!PostDetails.isLiveTree(model)) return null;
        try {
            Object name = model.getClass().getMethod("getTypeName").invoke(model);
            return name instanceof String ? (String) name : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * A tap on the row: the save, or a message saying it didn't start. Answers null, which is all
     * the sheet's rows answer with that matters here.
     */
    @Override
    public Object invoke(Object tapped) {
        try {
            // A sheet can stay open while the switch goes off. The tap then does nothing, quietly.
            if (!enabled()) {
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                    () -> "Save video tapped after its switch went off");
                return null;
            }
            Context application = Utils.getContext();
            if (application == null) return null;
            if (SendLink.sending()) {
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "Send to app tapped on a comment's sheet");
                String link = SendLink.videoLink(id);
                if (link != null) {
                    SendLink.send(application, link);
                    return null;
                }
                Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE,
                    () -> "the comment's video names no id, so it has no link", null);
                Feedback.show(application, L10n.t(application, "Couldn't find this video's link"), true);
                return null;
            }
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "Save video tapped on a comment's sheet");
            if (MediaDownload.busy(application)) return null;
            // The video's id and nothing of the commenter, so their name never reaches the file name.
            if (!MediaDownload.saveFeedVideo(application, PostDetails.of(id), hd, sd)) {
                Feedback.show(application,
                    L10n.t(application, "Couldn't save this video. Play it for a moment, then try again."), true);
            }
        } catch (Throwable t) {
            // It runs inside Facebook's click dispatch, on the thread that draws the app.
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "comment sheet video tap", t);
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "the Save video tap failed", t);
        } finally {
            // Facebook's own rows close the sheet once tapped.
            CommentSheet.close(tapped);
        }
        return null;
    }
}
