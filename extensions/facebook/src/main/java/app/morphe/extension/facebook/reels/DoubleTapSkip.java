/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.view.View;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What the Double tap to skip patch asks each time Facebook reads whether its own double-tap skip
 * is on (issue #225).
 *
 * <p>Facebook's reel and video players carry a double-tap skip of their own: a double tap on the
 * left or right fifth of the player skips back or forward, 10 seconds on a video of 30 seconds or
 * more and 5 on a shorter one, and the tap goes no further, so it doesn't like the video. One of
 * Facebook's server tests turns it on, and most accounts don't get it. The patch hands every read
 * of that test to {@link #flag}, which answers yes while the switch is on. A double tap in the
 * middle of the player still does what it did.
 *
 * <p>Each skip shows the seconds over the player, "+5s" or "-10s", in a label the player's gesture
 * view keeps. The patch hands each of Facebook's setVisibility calls on that label to {@link #label},
 * which keeps it hidden while Hide the skip seconds is on (#476), whichever of the two turned the
 * skip on.
 *
 * <p>Off, paused, settings that aren't ready yet or a failure in here, and Facebook's own answer
 * goes on.
 */
public final class DoubleTapSkip {
    /** Counted each time a read of Facebook's test is answered yes in its place. */
    static final String TURNED_ON = "double-tap skip turned on";

    /** Counted each time the skip label is kept hidden where Facebook showed it. */
    static final String SECONDS_HIDDEN = "skip seconds hidden";

    private DoubleTapSkip() {
    }

    /**
     * Injection point, right after Facebook reads whether its double-tap skip is on: the answer to
     * go on with. Never throws.
     */
    public static boolean flag(boolean facebooks) {
        try {
            HookStatus.invoked(FamilyNames.DOUBLE_TAP_SKIP);
            HookStatus.bound(FamilyNames.DOUBLE_TAP_SKIP, "double-tap skip test");
            if (facebooks || !Utils.settingsReady() || !Settings.DOUBLE_TAP_SKIP.get()) return facebooks;
            HookStatus.counted(FamilyNames.DOUBLE_TAP_SKIP, TURNED_ON);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DOUBLE_TAP_SKIP, "double-tap skip test", failure);
            return facebooks;
        }
    }

    /**
     * Injection point, in place of Facebook's setVisibility on the skip label: shows or hides it as
     * Facebook asked, except that a label Facebook shows stays hidden while the switch is on. The
     * call to the label itself stays outside the guard, so it does what Facebook's call did.
     */
    public static void label(View label, int visibility) {
        int shown = visibility;
        try {
            HookStatus.invoked(FamilyNames.DOUBLE_TAP_SKIP);
            HookStatus.bound(FamilyNames.DOUBLE_TAP_SKIP, "skip seconds label");
            if (visibility == View.VISIBLE && Utils.settingsReady() && Settings.HIDE_SKIP_SECONDS.get()) {
                shown = View.GONE;
                HookStatus.counted(FamilyNames.DOUBLE_TAP_SKIP, SECONDS_HIDDEN);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DOUBLE_TAP_SKIP, "skip seconds label", failure);
            shown = visibility;
        }
        label.setVisibility(shown);
    }
}
