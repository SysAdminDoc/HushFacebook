/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * An open reel caption gets a dark rounded box behind its text, like subtitles, so a long caption
 * stays readable over a bright video (#419). A folded one gets the same box from its own switch
 * (#472).
 *
 * <p>The caption under a reel (FbShortsViewerDescriptionComponent) is a Litho text component whose
 * builder holds the caption's line limit. Facebook starts that limit at Integer.MAX_VALUE and sets a
 * few lines only while the caption is folded, so an open caption is one still at MAX_VALUE when it's
 * built, and a folded one is one with a limit of a line or more below that. Right before the build,
 * the patch hands the limit here and, given a drawable back, sets it as the component's background
 * through Litho's own style, with no padding, so the text keeps its place.
 *
 * <p>With the switch for its kind off, paused, before the settings are ready, or when anything here
 * fails, the caption keeps Facebook's look.
 */
public final class CaptionBackdrop {
    /** The member the report names once a caption's line limit has gone through here. */
    static final String BACKDROP = "reel caption backdrop";

    /** Counted each time an open caption gets the box. */
    static final String DIMMED = "open reel caption dimmed";

    /** Counted each time a folded caption gets the box (#472). */
    static final String FOLDED_DIMMED = "folded reel caption dimmed";

    /** About 70% black, close to the box behind video subtitles. */
    static final int COLOR = 0xB3000000;

    /** The box's corner radius, in dp. */
    static final float CORNER_DP = 8f;

    private static final String FAMILY = FamilyNames.FULL_POST_TEXT;

    private static volatile boolean logged;
    private static volatile boolean loggedFolded;

    private CaptionBackdrop() {
    }

    /**
     * Whether this build carries the hook. The patch answers true here once it's in; a Facebook build
     * missing one of its anchors leaves this false, so the settings page doesn't offer the switch.
     */
    public static boolean inBuild() {
        return false;
    }

    /**
     * The hook, right before a reel caption's text component is built, handed its line limit. Answers
     * the box for an open caption while Dark box behind open captions is on, and for a folded one
     * while Dark box behind folded captions is, otherwise null, which keeps Facebook's look.
     */
    public static Drawable forCaption(int maxLines) {
        try {
            HookStatus.bound(FAMILY, BACKDROP);
            boolean open = maxLines == Integer.MAX_VALUE;
            // A folded caption shows a few lines; a limit of none shows no text to put a box behind.
            boolean folded = !open && maxLines > 0;
            if (!(open || folded) || !Utils.settingsReady()) return null;
            if (open ? !Settings.DIM_OPEN_REEL_CAPTIONS.get() : !Settings.DIM_FOLDED_REEL_CAPTIONS.get()) return null;
            HookStatus.invoked(FAMILY);
            if (open) {
                HookStatus.counted(FAMILY, DIMMED);
                if (!logged) {
                    logged = true;
                    Logger.printDebug(() -> "Full post text: an open reel caption got its dark box");
                }
            } else {
                HookStatus.counted(FAMILY, FOLDED_DIMMED);
                if (!loggedFolded) {
                    loggedFolded = true;
                    Logger.printDebug(() -> "Full post text: a folded reel caption got its dark box, "
                            + maxLines + " lines");
                }
            }
            // A drawable of its own each time: Litho mounts a background per component.
            GradientDrawable box = new GradientDrawable();
            box.setColor(COLOR);
            box.setCornerRadius(CORNER_DP * Resources.getSystem().getDisplayMetrics().density);
            return box;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, BACKDROP, failure);
            return null;
        }
    }
}
