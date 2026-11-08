/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.theme.DarkMode;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Light bars on the Reels tab (#423).
 *
 * <p>In light mode every tab has a light status bar, top bar and tab bar except the Reels tab
 * (Facebook's Video tab), which turns all three dark. Facebook picks each of them by the tab: the tab
 * bar's colours by the selected tab's id, the main screen's status bar by the same id, and the
 * Reels tab's own status bar and top bar by whether its reels are shown in the tab. The patch hands
 * each of those to this class, which, while the switch is on and Facebook's dark mode is off, answers
 * as if another tab were selected, so the Reels tab gets the bars the other tabs have. The reels
 * themselves stay as they are.
 *
 * <p>Dark mode, the switch off, a pause, settings that aren't ready yet or a failure in here, and
 * Facebook's own answer goes on.
 */
public final class LightReelsTabBars {
    /** Facebook's id for the Video tab, the one its tab bar shows as Reels. */
    static final long VIDEO_TAB = 0x8ea18579L;

    /** Counted each time the tab bar is given the other tabs' colours on the Reels tab. */
    static final String TAB_BAR = "Reels tab bar kept light";
    /** Counted each time the status bar is given the other tabs' look on the Reels tab. */
    static final String STATUS_BAR = "Reels status bar kept light";
    /** Counted each time the Reels tab's own dark status bar or top bar is skipped. */
    static final String DARK_BARS = "Reels dark bars skipped";

    /** The hook's name in the diagnostics report. */
    static final String HOOK = "Light bars on the Reels tab";

    private static final String FAMILY = FamilyNames.BOTTOM_TAB_BAR;

    private LightReelsTabBars() {
    }

    /** Whether the Reels tab gets light bars now: the switch on, nothing paused, Facebook in light mode. */
    public static boolean on() {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, HOOK);
            return Utils.settingsReady() && Settings.LIGHT_REELS_TAB_BARS.get()
                && DarkMode.hasAnswered() && !DarkMode.on();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
            return false;
        }
    }

    /**
     * Injection point, first thing where Facebook's tab bar picks its colours by the selected tab:
     * the tab to pick them for. The Video tab's id becomes null, which Facebook takes for any other
     * tab. Never throws.
     */
    @Nullable
    public static Long tabBarTab(@Nullable Long tab) {
        if (tab == null || tab != VIDEO_TAB || !on()) return tab;
        HookStatus.counted(FAMILY, TAB_BAR);
        return null;
    }

    /**
     * Injection point, where the Reels tab reads the selected tab's id to answer whether the tab bar
     * goes dark on it, the answer the tab bar takes over its colour picker's: the id to compare. The
     * Video tab's id becomes 0, no tab's, so the answer is no. Never throws.
     */
    public static long reelsTabBarTab(long tab) {
        if (tab != VIDEO_TAB || !on()) return tab;
        HookStatus.counted(FAMILY, TAB_BAR);
        return 0L;
    }

    /**
     * Injection point, where the main screen reads the selected tab's id to pick the status bar for
     * it: the id to compare. The Video tab's id becomes 0, no tab's, so the status bar goes the way
     * of the other tabs. Never throws.
     */
    public static long barsTab(long tab) {
        if (tab != VIDEO_TAB || !on()) return tab;
        HookStatus.counted(FAMILY, STATUS_BAR);
        return 0L;
    }

    /**
     * Injection point, where the Reels tab asks whether its reels show in the tab before it paints
     * the status bar or the top bar dark: the answer to go on with. Never throws.
     */
    public static boolean darkBars(boolean dark) {
        if (!dark || !on()) return dark;
        HookStatus.counted(FAMILY, DARK_BARS);
        return false;
    }

    /**
     * Injection point, first thing where the Reels tab builds its own status bar: whether to hand
     * back the status bar the other tabs get instead. Never throws.
     */
    public static boolean lightStatusBar() {
        if (!on()) return false;
        HookStatus.counted(FAMILY, STATUS_BAR);
        return true;
    }
}
