/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import android.view.View;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Hide the live video comment bar (#537). A live video's player puts one bar along its bottom: the
 * comment box, the row of reactions a tap sends to the stream, and a few buttons beside them. The
 * patch hands that bar here as it's built, and while the switch is on it's held gone for as long as
 * it lives, the same way Hide the comment box holds the post's box, since the live player shows it
 * again on its own. The comments, the tabs over them and the video stay.
 */
public final class LiveCommentBar {
    private LiveCommentBar() {
    }

    private static final String FAMILY = FamilyNames.COMMENT_SHEET_OPTIONS;
    static final String BAR = "live video comment bar";
    static final String BAR_HIDDEN = "Live video comment bar hidden";

    /**
     * The hook, last thing in the bar's constructor. Off, paused, before the settings are ready or
     * on a throw, the bar stays as Facebook built it. Turning the switch off lets go at the next
     * frame.
     */
    public static void barBuilt(@Nullable View bar) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, BAR);
            if (bar == null || !Utils.settingsReady() || !Settings.HIDE_LIVE_COMMENT_BAR.get()) return;
            if (CommentSheetOptions.holdGone(bar, Settings.HIDE_LIVE_COMMENT_BAR, BAR)) {
                HookStatus.counted(FAMILY, BAR_HIDDEN);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, BAR, failure);
        }
    }
}
