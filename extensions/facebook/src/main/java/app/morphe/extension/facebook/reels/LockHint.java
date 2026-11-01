/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Keeps Facebook's "Pull down to lock 2x speed" hint off the 2x pill a held video shows, for the
 * third switch of Hold a reel for 2x (#289).
 *
 * <p>While a video plays at 2x under a finger, Facebook draws a small pill over it, and the pill's
 * state says what it reads: NORMAL is the plain 2x pill, LOCK_HINT and UNLOCK_HINT ask the user to
 * pull down or up, and NUX_PROMPT is the first-time teaching line. The patch hands each state to
 * {@link #pill} as the pill is made. While the switch is on, the three hints become NORMAL. Reels
 * draw their own pill, which {@link #reelPill} turns plain through its hints flag. The
 * states a pull itself shows (ready to lock, locked, ready to unlock) are kept, so pulling down
 * still locks 2x and says so. Off, paused, before the settings are ready, or when anything here
 * fails, the state is Facebook's own.
 *
 * <p>Hide the 2x popups goes further: {@link #hidesPill} is asked first in both pills' renders, and a
 * yes draws nothing, whatever the state. The hold, the lock and what they hide on the reel stay
 * Facebook's, since only the pill's drawing is skipped.
 */
public final class LockHint {
    /** Counted under the patch's name each time a hint is drawn as the plain pill. */
    static final String HINT_HIDDEN = "2x lock hint shown as the plain pill";
    /** Counted under the patch's name each time a pill draws nothing. */
    static final String PILL_HIDDEN = "2x pill hidden";

    private static final String FAMILY = FamilyNames.HOLD_REEL_FOR_2X;

    private LockHint() {
    }

    /**
     * Injection point, first in the 2x pill's constructor. Answers the state the pill is drawn in,
     * Facebook's pill state enum in both directions. Never throws.
     *
     * @param state the state Facebook made the pill with.
     */
    public static Enum<?> pill(Enum<?> state) {
        try {
            HookStatus.invoked(FAMILY);
            if (state == null || !hint(state.name())) return state;
            if (!ReelHold.holdInBuild() || !Utils.settingsReady() || !Settings.HIDE_2X_LOCK_HINT.get()) return state;
            Enum<?> plain = plain(state);
            HookStatus.bound(FAMILY, "2x lock hint");
            HookStatus.counted(FAMILY, HINT_HIDDEN);
            return plain;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "2x lock hint", failure);
            Logger.printException(() -> "2x lock hint: could not swap the pill's state", failure);
            return state;
        }
    }

    /**
     * Injection point, first in the Reels 2x pill's constructor, a separate component from the one
     * {@link #pill} sees. That pill is made with its state and a flag saying whether it speaks in
     * hints at all. With the flag on, a held reel's pill reads "Pull down to lock 2x speed" or
     * "Slide up to lock 2x speed"; with it off, the pill shows the plain speed, as Facebook draws it
     * where it has no lock. Answers the flag: off for the states that show the lock hint while the
     * switch is on, so the states a pull itself shows keep their text. Never throws.
     *
     * @param state the Reels pill's state enum, as Facebook made the pill with it.
     * @param hints the flag Facebook made the pill with.
     */
    public static boolean reelPill(Enum<?> state, boolean hints) {
        try {
            HookStatus.invoked(FAMILY);
            if (!hints || state == null || !reelHint(state.name())) return hints;
            if (!ReelHold.holdInBuild() || !Utils.settingsReady() || !Settings.HIDE_2X_LOCK_HINT.get()) return hints;
            HookStatus.bound(FAMILY, "Reels 2x lock hint");
            HookStatus.counted(FAMILY, HINT_HIDDEN);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "Reels 2x lock hint", failure);
            Logger.printException(() -> "2x lock hint: could not read the Reels pill's flag", failure);
            return hints;
        }
    }

    /**
     * Injection point, first in the render of either 2x pill. True has the pill draw nothing, the
     * empty result Litho takes from any component. Never throws.
     */
    public static boolean hidesPill() {
        try {
            HookStatus.invoked(FAMILY);
            if (!ReelHold.holdInBuild() || !Utils.settingsReady() || !Settings.HIDE_2X_POPUPS.get()) return false;
            HookStatus.bound(FAMILY, "2x popups");
            HookStatus.counted(FAMILY, PILL_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "2x popups", failure);
            Logger.printException(() -> "2x popups: could not read the switch", failure);
            return false;
        }
    }

    /** Whether a Reels pill state by this name draws the lock hint while its hints are on. */
    static boolean reelHint(String name) {
        return "UNLOCKED_2X_SPEED".equals(name) || "LOCKED_WITH_FOOTER".equals(name) || "ORIGINAL_SPEED".equals(name);
    }

    /** Whether a pill state by this name is one of the hints the switch hides. */
    static boolean hint(String name) {
        return "LOCK_HINT".equals(name) || "UNLOCK_HINT".equals(name) || "NUX_PROMPT".equals(name);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Enum<?> plain(Enum<?> state) {
        return Enum.valueOf((Class) state.getDeclaringClass(), "NORMAL");
    }
}
