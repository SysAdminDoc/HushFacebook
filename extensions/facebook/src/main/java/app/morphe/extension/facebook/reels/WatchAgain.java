/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Watch again at the end of a feed video (#403).
 *
 * <p>When a video in the feed ends, Facebook shows Watch again over it. A tap asks Facebook whether
 * that video should open somewhere else, and a yes opens it in the Reels viewer, with more reels to
 * swipe to after it, instead of starting it again where it is. The patch hands every one of those
 * answers to {@link #opensElsewhere}, which says no while the switch is on, so the tap replays the
 * video in the feed. Facebook's More videos button at the end of a video isn't touched.
 *
 * <p>Off, paused, settings that aren't ready yet or a failure in here, and Facebook's own answer
 * goes on.
 */
public final class WatchAgain {
    /** Counted each time a Watch again tap stays on the video instead of opening it elsewhere. */
    static final String REPLAYED = "Watch again replayed in place";

    /** The hook's name in the diagnostics report. */
    static final String HOOK = "Watch again";

    private WatchAgain() {
    }

    /**
     * Injection point, right after Facebook decides whether a Watch again tap opens the video
     * somewhere else: the answer to go on with. Never throws.
     */
    public static boolean opensElsewhere(boolean opens) {
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            HookStatus.bound(FamilyNames.REEL_DECLUTTER, HOOK);
            if (!opens || !Utils.settingsReady() || !Settings.WATCH_AGAIN_IN_PLACE.get()) return opens;
            HookStatus.counted(FamilyNames.REEL_DECLUTTER, REPLAYED);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, HOOK, failure);
            return opens;
        }
    }
}
