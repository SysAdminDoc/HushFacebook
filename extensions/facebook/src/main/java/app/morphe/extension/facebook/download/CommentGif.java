/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.content.Context;

import java.net.URI;
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
 * The Save GIF row Download any photo adds to a comment's long-press sheet (issue #226).
 *
 * <p>Facebook's sheet offers Save photo only for an attachment styled as a photo, so a GIF comment
 * gets nothing. The patch asks {@link #clickFor} for a tap action right after the sheet's rows are
 * built, and adds a row of Facebook's own kind with {@link #title} when it gets one. The GIF's
 * address is read then, while the comment's model is live: each attachment's media, its
 * {@code animated_image}, that image's {@code uri}. A comment holding no GIF gets no row.
 *
 * <p>Nothing here names a class or member Facebook renames. The comment is a kept GraphQL model and
 * its parts are read through the tree accessors Facebook keeps.
 */
public final class CommentGif implements Function1<Object, Object> {

    /** The source every event of the row carries in the diagnostic report. */
    static final String SOURCE = "CommentGif";

    static final int ATTACHMENTS = "attachments".hashCode();
    static final int MEDIA = "media".hashCode();
    static final int ANIMATED_IMAGE = "animated_image".hashCode();
    static final int URI = "uri".hashCode();

    /** The GIF's address, read when the sheet was built. */
    final String url;

    /** What the file name can say of it. */
    final PostDetails details;

    CommentGif(String url, PostDetails details) {
        this.url = url;
        this.details = details;
    }

    /**
     * The tap action of a Save GIF row for [comment], or null when it gets none: the switch is off,
     * Hushfacebook is paused or not ready, or the comment holds no GIF. Never throws: this runs
     * inside Facebook's sheet code.
     */
    public static Function1<Object, Object> clickFor(Object comment) {
        try {
            if (!enabled() || comment == null) return null;
            HookStatus.invoked(FamilyNames.PHOTO_DOWNLOAD);
            CommentGif row = of(comment);
            if (row == null) return null;
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                () -> "comment sheet: a GIF, " + MediaDownload.describe(row.url) + ", " + whereIs(row.url));
            HookStatus.bound(FamilyNames.PHOTO_DOWNLOAD, "comment sheet GIF row");
            return row;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.PHOTO_DOWNLOAD, "comment sheet GIF row", t);
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE,
                () -> "could not add Save GIF to a comment's sheet", t);
            return null;
        }
    }

    /** The row's title. */
    public static String title() {
        return L10n.t("Save GIF");
    }

    /** The switch on, Hushfacebook running and its settings ready. */
    static boolean enabled() {
        return Utils.settingsReady() && Settings.DOWNLOAD_PHOTOS.get();
    }

    /** Whether [url] is on Meta's media servers, or else its host, for the report. */
    static String whereIs(String url) {
        String host;
        try {
            host = new URI(url).getHost();
        } catch (Throwable t) {
            host = null;
        }
        if (host == null) return "no host";
        return MediaUrlPolicy.isMetaMediaHost(host) ? "on Meta's media servers" : "on " + host;
    }

    /** The first GIF of [comment]'s attachments, or null when it holds none. */
    static CommentGif of(Object comment) {
        List<?> attachments = PostDetails.trees(comment, ATTACHMENTS);
        if (attachments == null) return null;
        for (Object attachment : attachments) {
            Object media = PostDetails.tree(attachment, MEDIA);
            String url = PostDetails.string(PostDetails.tree(media, ANIMATED_IMAGE), URI);
            if (url != null && !url.isEmpty()) {
                return new CommentGif(url, PostDetails.read(PostDetails.string(media, PostDetails.ID), media, comment));
            }
        }
        return null;
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
                    () -> "Save GIF tapped after its switch went off");
                return null;
            }
            Context application = Utils.getContext();
            if (application == null) return null;
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "Save GIF tapped on a comment's sheet");
            if (MediaDownload.busy(application)) return null;
            if (!MediaDownload.savePhoto(application, url, details)) {
                Feedback.show(application, L10n.t(application, "Couldn't save this GIF. Try again in a moment."), true);
            }
        } catch (Throwable t) {
            // It runs inside Facebook's click dispatch, on the thread that draws the app.
            HookStatus.threw(FamilyNames.PHOTO_DOWNLOAD, "comment sheet GIF tap", t);
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "the Save GIF tap failed", t);
        } finally {
            // Facebook's own rows close the sheet once tapped.
            CommentSheet.close(tapped);
        }
        return null;
    }
}
