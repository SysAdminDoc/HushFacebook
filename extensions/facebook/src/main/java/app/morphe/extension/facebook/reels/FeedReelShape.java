/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.content.res.Resources;
import android.util.DisplayMetrics;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Show feed reels uncropped (#581). A reel posted in the feed plays in a frame whose shape Facebook
 * works out from the story, held to a server-set least width over height and at most 16:9, so a
 * tall reel gets a wider frame and its picture is cut to fill it. Facebook hands the frame's shape to
 * the reel's cover and to its player from one helper, and the hook gets the helper's answer and the
 * video's own shape right before it returns. While the switch is on the frame takes the video's
 * shape, no taller than the screen has room for under the status bar and Facebook's top bar.
 */
public final class FeedReelShape {
    /** The member the report names once the helper has asked. */
    static final String HOOK = "Show feed reels uncropped";

    /** What the report counts each time a feed reel's frame takes the video's shape. */
    static final String SHOWN_WHOLE = "Feed reel shown uncropped";

    /** Facebook's top bar, which its own tallest feed frame leaves room for, in dp. */
    static final float TOP_BAR_DP = 52f;

    /** The status bar when the phone doesn't say, in dp. */
    static final float STATUS_BAR_DP = 24f;

    private FeedReelShape() {
    }

    /**
     * Injection point, right before Facebook's feed reel frame helper returns: the frame's width over
     * height it's about to answer, and the video's own width over height (0 when the story didn't
     * say). Answers the shape the frame gets. Never throws.
     */
    public static float shape(float facebooks, float video) {
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            HookStatus.bound(FamilyNames.REEL_DECLUTTER, HOOK);
            if (!Utils.settingsReady() || !Settings.UNCROPPED_FEED_REELS.get()) return facebooks;
            DisplayMetrics screen = Resources.getSystem().getDisplayMetrics();
            float room = screen.heightPixels - statusBar(screen) - TOP_BAR_DP * screen.density;
            float whole = whole(facebooks, video, screen.widthPixels, room);
            if (whole != facebooks) {
                HookStatus.counted(FamilyNames.REEL_DECLUTTER, SHOWN_WHOLE);
                Logger.printDebug(() -> "Feed reel frame: Facebook " + facebooks + ", video " + video + ", shown " + whole);
            }
            return whole;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, HOOK, failure);
            return facebooks;
        }
    }

    /**
     * The frame's width over height for a video of {@code video} on a screen {@code width} pixels
     * wide with {@code room} pixels of height for the feed: the video's own shape, or the screen's
     * room when the video is taller than that. Facebook's answer when it, the video's shape or the
     * screen's size is unknown.
     */
    static float whole(float facebooks, float video, float width, float room) {
        if (!(facebooks > 0) || !(video > 0) || !(width > 0) || !(room > 0)) return facebooks;
        return Math.max(video, width / room);
    }

    private static float statusBar(DisplayMetrics screen) {
        Resources system = Resources.getSystem();
        int id = system.getIdentifier("status_bar_height", "dimen", "android");
        if (id != 0) {
            int height = system.getDimensionPixelSize(id);
            if (height > 0) return height;
        }
        return STATUS_BAR_DP * screen.density;
    }
}
