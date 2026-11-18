/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.content.Context;

import java.util.Collections;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;
import kotlin.jvm.functions.Function1;

/**
 * The Download button Post action buttons adds to the row under a post holding a video or photos
 * (issue #151), beside Like, Comment and Share.
 *
 * <p>Facebook builds a post's action row as a list of items, each a label, an icon and a click
 * that's a {@code Function1}. The patch asks {@link #click} once the row's items are built, and
 * when it answers a button, builds an item shaped like Facebook's own Save, with Facebook's
 * Download icon, and adds it at the end. The button keeps the post it was built for, so a tap
 * always saves that post's media, however far the feed has scrolled.
 *
 * <p>A video post saves its video the way Download to phone does from the post's menu, so it
 * needs Download feed and Watch videos on and honors Send to app. A photo post saves its photo,
 * or every photo of an album, the way Save photo does, and needs Save any photo on. A post mixing
 * them, or holding several videos, saves all of it one after another (#509). A post with neither,
 * or with the switch off, paused, or before the settings are ready, gets Facebook's own row. The
 * post's getters are found by the GraphQL field each one reads and handed over by name.
 */
public final class PostDownloadButton implements Function1<Object, Object> {

    /** The source every event of the button carries in the diagnostic report. */
    static final String SOURCE = "PostDownloadButton";

    /** Stand-ins for the Download any video and Download any photo patches being in the build, for tests. */
    static volatile Boolean videoInBuildForTests;
    static volatile Boolean photoInBuildForTests;

    private final Context context;
    private final Object post;
    private final boolean video;
    private final String attachments;
    private final String media;
    private final String attachedStory;
    private final String subattachments;

    private PostDownloadButton(Context context, Object post, boolean video, String attachments, String media,
                               String attachedStory, String subattachments) {
        this.context = context;
        this.post = post;
        this.video = video;
        this.attachments = attachments;
        this.media = media;
        this.attachedStory = attachedStory;
        this.subattachments = subattachments;
    }

    /**
     * The button for [post]'s row, or null when it gets none. [context] is the row's. The four
     * strings are the real names of the post's attachment list, an attachment's media, the post a
     * share wraps and an attachment's own attachments. Never throws: null is Facebook's own row.
     */
    public static PostDownloadButton click(Object post, Context context, String attachments, String media,
                                           String attachedStory, String subattachments) {
        try {
            if (post == null || !Utils.settingsReady() || !Settings.POST_DOWNLOAD_BUTTON.get()) return null;
            HookStatus.invoked(FamilyNames.POST_ACTION_BUTTONS);
            PostDownloadButton button = null;
            if (savesVideos() && VideoMenuItem.holdsVideo(post, attachments, media, attachedStory, subattachments)) {
                button = new PostDownloadButton(context, post, true, attachments, media, attachedStory, subattachments);
            } else if (savesPhotos() && PhotoMenuItem.holdsPhoto(post, attachments, media, attachedStory, subattachments)) {
                button = new PostDownloadButton(context, post, false, attachments, media, attachedStory, subattachments);
            }
            if (button != null) HookStatus.bound(FamilyNames.POST_ACTION_BUTTONS, "post download button");
            return button;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.POST_ACTION_BUTTONS, "post download button", t);
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE,
                () -> "could not add Download under a post", t);
            return null;
        }
    }

    /** The button's label, Send to app while a video's link goes to another app instead. */
    public String label() {
        return video && SendLink.sending() ? L10n.t("Send to app") : L10n.t("Download");
    }

    /** A tap: the post's video or photos save. Never throws, it runs in Facebook's click dispatch. */
    @Override
    public Object invoke(Object view) {
        try {
            if (!Utils.settingsReady() || !Settings.POST_DOWNLOAD_BUTTON.get()) {
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                    () -> "Download under a post tapped after its switch went off");
                return null;
            }
            if (video) {
                List<VideoMenuItem.Video> videos =
                    VideoMenuItem.videosOf(post, attachments, media, attachedStory, subattachments);
                if (videos.isEmpty()) return null;
                // A link goes for a video only, so the post's photos come along only for a save.
                List<PhotoMenuItem.Photo> photos = savesPhotos() && !SendLink.sending()
                    ? PhotoMenuItem.photosOf(post, attachments, media, attachedStory, subattachments)
                    : Collections.emptyList();
                if (videos.size() == 1 && photos.isEmpty()) VideoMenuItem.tap(context, videos.get(0));
                else VideoMenuItem.tapAll(context, videos, photos);
                return null;
            }
            if (!savesPhotos()) return null;
            List<PhotoMenuItem.Photo> photos = PhotoMenuItem.photosOf(post, attachments, media, attachedStory, subattachments);
            if (photos.isEmpty()) return null;
            // With Ask before downloading on, only Continue starts the save (#511).
            if (!AskBeforeDownload.ask(context, () -> PhotoMenuItem.save(context, photos, "under a post"))) {
                PhotoMenuItem.save(context, photos, "under a post");
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.POST_ACTION_BUTTONS, "post download tap", t);
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "the Download tap under a post failed", t);
        }
        return null;
    }

    private static boolean savesVideos() {
        Boolean forced = videoInBuildForTests;
        return (forced != null ? forced : SettingsStatus.videoDownload()) && Settings.DOWNLOAD_VIDEOS.get();
    }

    private static boolean savesPhotos() {
        Boolean forced = photoInBuildForTests;
        return (forced != null ? forced : SettingsStatus.photoDownload()) && Settings.DOWNLOAD_PHOTOS.get();
    }
}
