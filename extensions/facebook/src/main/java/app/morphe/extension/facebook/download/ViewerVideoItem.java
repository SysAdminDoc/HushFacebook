/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.content.Context;
import android.view.Menu;
import android.view.MenuItem;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The Download to phone row in the three-dot menu of Facebook's photo and video viewer (#509).
 *
 * <p>Tapping a video in a post with several things in it opens that viewer, not the Reels player,
 * and its menu often holds nothing but Report. Facebook rebuilds the menu for each item the viewer
 * shows, and the patch calls {@link #add} at the end of that, so the row follows the item on
 * screen. It goes on videos only: a photo there has Save photo. The tap is the post menu's, so
 * Ask before downloading, Choose quality and Send to app all work the same here.
 */
public final class ViewerVideoItem {

    private ViewerVideoItem() {}

    /**
     * Adds the row to [menu] when [media], the item the viewer is showing, is a video. [fragment]
     * is the viewer's screen, [story] the post the item belongs to when the viewer knows it, and
     * [icon] the drawable of Facebook's own Save photo row. Never throws: this runs inside
     * Facebook's menu code, and returning leaves Facebook's own menu.
     */
    public static void add(Menu menu, Object fragment, Object story, Object media, int icon) {
        try {
            if (!Utils.settingsReady() || !Settings.DOWNLOAD_VIDEOS.get() || menu == null) return;
            HookStatus.invoked(FamilyNames.VIDEO_DOWNLOAD);
            if (!PostDetails.isLiveTree(media)) return;
            if (!VideoMenuItem.VIDEO_TYPE.equals(VideoMenuItem.typeName(media))) return;

            VideoMenuItem.Video video = VideoMenuItem.Video.ofTree(media, story);
            Context context = contextOf(fragment);
            String title = SendLink.sending() ? L10n.t("Send to app") : L10n.t("Download to phone");
            MenuItem entry = MenuTop.add(menu, title);
            if (entry == null) return;
            if (icon != 0) entry.setIcon(icon);
            entry.setOnMenuItemClickListener(tapped -> {
                VideoMenuItem.tap(context, video);
                return true;
            });
            HookStatus.bound(FamilyNames.VIDEO_DOWNLOAD, "viewer video item");
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "viewer video menu", t);
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, VideoMenuItem.SOURCE,
                () -> "could not add Download to phone to the viewer's menu", t);
        }
    }

    /**
     * The screen [fragment] is on, through the name AndroidX keeps. Null only costs the question
     * its anchor, since the tap then falls back to the current screen.
     */
    private static Context contextOf(Object fragment) {
        if (fragment == null) return null;
        try {
            Object context = fragment.getClass().getMethod("getContext").invoke(fragment);
            return context instanceof Context ? (Context) context : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
