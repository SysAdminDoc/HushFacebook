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
 * "Are you still watching?" (#427).
 *
 * <p>Facebook's Reels auto-advance controller asks one check whether you've gone idle: its idle timer
 * ran out, or enough videos went by since you last touched the screen. A yes holds the next video back
 * and shows the prompt, which pauses playback until you tap it. The patch hands every answer of that
 * check to {@link #idle}, which says not idle while the switch is on, so the next video comes as it
 * would for anyone touching the screen.
 *
 * <p>Off, paused, settings that aren't ready yet or a failure in here, and Facebook's answer stands.
 */
public final class StillWatching {
    /** Counted each time Facebook would have held a video back to ask. */
    static final String SKIPPED = "Still watching prompt skipped";

    /** The hook's name in the diagnostics report. */
    static final String HOOK = "Still watching";

    private StillWatching() {
    }

    /**
     * Injection point, at each return of the controller's idle check: what it answers instead. An int,
     * since the check may return a register typed as one. Never throws.
     */
    public static boolean idle(int idle) {
        return idle(idle != 0);
    }

    static boolean idle(boolean idle) {
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            HookStatus.bound(FamilyNames.REEL_DECLUTTER, HOOK);
            if (!idle || !Utils.settingsReady() || !Settings.SKIP_STILL_WATCHING.get()) return idle;
            HookStatus.counted(FamilyNames.REEL_DECLUTTER, SKIPPED);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, HOOK, failure);
            return idle;
        }
    }
}
