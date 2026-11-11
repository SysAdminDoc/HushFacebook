/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.content.Context;
import android.view.accessibility.AccessibilityManager;

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
 * The Save photo row Download any photo adds to a photo comment's long-press sheet (issue #440).
 *
 * <p>Facebook builds a Save photo row in that sheet only while a screen reader is on (TalkBack's
 * touch exploration, or the {@code is_accessibility_enabled} system property), so everyone else has
 * to open the photo to save it. The patch asks {@link #clickFor} for a tap action right after the
 * sheet's rows are built, the way it asks {@link CommentGif}, and adds a row of Facebook's own kind
 * with {@link #title} when it gets one. The photo's address is read then, while the comment's model
 * is live, the way Save photo on a post reads it ({@link PhotoSave#saveAddress}).
 *
 * <p>A comment holding no photo gets no row, and neither does one whose sheet already has
 * Facebook's own. A GIF or a video is left to its own row.
 */
public final class CommentPhoto implements Function1<Object, Object> {

    /** The source every event of the row carries in the diagnostic report. */
    static final String SOURCE = "CommentPhoto";

    /** The system property Facebook's own Save photo row also shows for. */
    static final String ACCESSIBILITY_PROPERTY = "is_accessibility_enabled";

    /** The photo's address, read when the sheet was built. */
    final String url;

    /** What the file name can say of it. */
    final PostDetails details;

    CommentPhoto(String url, PostDetails details) {
        this.url = url;
        this.details = details;
    }

    /**
     * The tap action of a Save photo row for [comment], or null when it gets none: the switch is
     * off, Hushfacebook is paused or not ready, the comment holds no photo, or Facebook adds its own
     * Save photo to this sheet. Never throws: this runs inside Facebook's sheet code.
     */
    public static Function1<Object, Object> clickFor(Object comment) {
        try {
            if (!enabled() || comment == null) return null;
            HookStatus.invoked(FamilyNames.PHOTO_DOWNLOAD);
            CommentPhoto row = of(comment);
            if (row == null) return null;
            if (facebookOffersSave(Utils.getContext())) {
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                    () -> "comment sheet: a photo, and Facebook's own Save photo is on this sheet, so none is added");
                return null;
            }
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                () -> "comment sheet: a photo, " + MediaDownload.describe(row.url) + ", " + CommentGif.whereIs(row.url));
            HookStatus.bound(FamilyNames.PHOTO_DOWNLOAD, "comment sheet photo row");
            return row;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.PHOTO_DOWNLOAD, "comment sheet photo row", t);
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE,
                () -> "could not add Save photo to a comment's sheet", t);
            return null;
        }
    }

    /** The row's title. */
    public static String title() {
        return L10n.t("Save photo");
    }

    /** The switch on, Hushfacebook running and its settings ready. */
    static boolean enabled() {
        return Utils.settingsReady() && Settings.DOWNLOAD_PHOTOS.get();
    }

    /**
     * Whether Facebook builds its own Save photo row in a comment's sheet: the same test its sheet
     * makes, the system property or touch exploration.
     */
    static boolean facebookOffersSave(Context context) {
        if (Boolean.getBoolean(ACCESSIBILITY_PROPERTY)) return true;
        if (context == null) return false;
        Object service = context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        return service instanceof AccessibilityManager && ((AccessibilityManager) service).isTouchExplorationEnabled();
    }

    /** The first photo of [comment]'s attachments, or null when it holds none. */
    static CommentPhoto of(Object comment) {
        List<?> attachments = PostDetails.trees(comment, CommentGif.ATTACHMENTS);
        if (attachments == null) return null;
        for (Object attachment : attachments) {
            Object media = PostDetails.tree(attachment, CommentGif.MEDIA);
            // A released tree's reads go to native code with nothing behind it, so it's skipped unread.
            if (!PostDetails.isLiveTree(media) || PostDetails.tree(media, CommentGif.ANIMATED_IMAGE) != null) continue;
            if (!PhotoMenuItem.PHOTO_TYPE.equals(typeName(media))) continue;
            Logger.diagnosticDebug(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "comment photo images: " + PhotoSave.sizes(media));
            String url = PhotoSave.saveAddress(media);
            if (url != null && !url.isEmpty()) {
                return new CommentPhoto(url, PostDetails.read(PostDetails.string(media, PostDetails.ID), media, comment));
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
                    () -> "Save photo tapped after its switch went off");
                return null;
            }
            Context application = Utils.getContext();
            if (application == null) return null;
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "Save photo tapped on a comment's sheet");
            if (MediaDownload.busy(application)) return null;
            if (!MediaDownload.savePhoto(application, url, details)) {
                Feedback.show(application, L10n.t(application, "Couldn't save this photo. Try again in a moment."), true);
            }
        } catch (Throwable t) {
            // It runs inside Facebook's click dispatch, on the thread that draws the app.
            HookStatus.threw(FamilyNames.PHOTO_DOWNLOAD, "comment sheet photo tap", t);
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "the Save photo tap failed", t);
        } finally {
            // Facebook's own rows close the sheet once tapped.
            CommentSheet.close(tapped);
        }
        return null;
    }
}
