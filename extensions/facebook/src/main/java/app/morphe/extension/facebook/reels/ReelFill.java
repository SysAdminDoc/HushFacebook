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
 * Fill the screen with reels (#453).
 *
 * <p>Facebook sizes every player view through one method, which asks a helper how big the video
 * should be in its container. The Reels tab and the full-screen viewers ask it to fit the video
 * inside, so a 9:16 reel on a taller screen gets black bars above and below. The patch asks
 * {@link #fills} first, and a yes asks the helper to fill the container instead, cutting a little off
 * the reel's sides.
 *
 * <p>Only a portrait video in a portrait container about the size of the screen fills, and only when
 * no more than {@link #MOST_CUT} of the picture would be cut off, so feed videos, landscape videos and
 * square or 4:5 posts keep their whole picture. Off, paused, settings that aren't ready yet or a
 * failure in here, and Facebook's fit stands.
 */
public final class ReelFill {
    /** Counted each time a reel fills its container instead of fitting inside it. */
    static final String FILLED = "Reel filled the screen";

    /** The hook's name in the diagnostics report. */
    static final String HOOK = "Fill the screen with reels";

    /** Facebook's helper mode that crops the video to fill its container. */
    static final int FILL = 1;

    /** Facebook's helper mode that fits the whole video inside its container, with bars where it's short. */
    static final int FIT = 2;

    /** The most of a reel's width (or height) filling may cut off. */
    static final double MOST_CUT = 0.25;

    /** How much of the screen's width a container needs to count as full screen. */
    static final double FULL_WIDTH = 0.9;

    /** How much of the screen's height a container needs to count as full screen. */
    static final double FULL_HEIGHT = 0.75;

    private ReelFill() {
    }

    /**
     * Injection point, right before Facebook asks its helper for a player view's size: the video's
     * aspect ratio (width over height), the container's width and height in pixels, the mode and the
     * flag the helper is about to get. True has the helper fill the container. Never throws.
     */
    public static boolean fills(double aspect, int width, int height, int mode, int flag) {
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            HookStatus.bound(FamilyNames.REEL_DECLUTTER, HOOK);
            if (mode != FIT) return false;
            DisplayMetrics screen = Resources.getSystem().getDisplayMetrics();
            if (!worthFilling(aspect, width, height, screen.widthPixels, screen.heightPixels)) return false;
            if (!Utils.settingsReady() || !Settings.FILL_REELS.get()) return false;
            HookStatus.counted(FamilyNames.REEL_DECLUTTER, FILLED);
            Logger.printDebug(() -> "Reel fill: " + aspect + " video filling " + width + "x" + height);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, HOOK, failure);
            return false;
        }
    }

    /**
     * Whether a video of {@code aspect} in a {@code width} by {@code height} container on a
     * {@code screenWidth} by {@code screenHeight} screen is a reel worth filling: portrait video,
     * portrait container about the screen's size, and a cut of more than nothing and at most
     * {@link #MOST_CUT}.
     */
    static boolean worthFilling(double aspect, int width, int height, int screenWidth, int screenHeight) {
        if (!(aspect > 0) || aspect >= 1 || width <= 0 || height <= width) return false;
        if (width < FULL_WIDTH * screenWidth || height < FULL_HEIGHT * screenHeight) return false;
        double box = (double) width / height;
        double cut = 1 - Math.min(aspect, box) / Math.max(aspect, box);
        return cut > 0.005 && cut <= MOST_CUT;
    }
}
