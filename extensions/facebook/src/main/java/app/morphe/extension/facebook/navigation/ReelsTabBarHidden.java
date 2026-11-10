/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.view.View;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Hide the tab bar on the Reels tab only.
 *
 * <p>Facebook restyles its tab bar in one method each time a tab is selected, handed the bar and the
 * selected tab. The patch hands both to {@link #tabShown} there, which hides the bar while the
 * selected tab is Reels (Facebook's Video tab) and shows it again on any other tab. Facebook lifts
 * the Reels page above the tab bar with a bottom margin of the bar's height, and a hidden bar keeps
 * its height there, so the reel stopped one bar short of the screen's bottom. That margin goes
 * through {@link #pageBottomMargin} with the page's tab, which answers 0 for Reels. The Reels tab's
 * viewer then sizes each reel to the screen minus the same height, so it goes through
 * {@link #reelBottomBars} as well, which answers 0 with the switch on, and the reel runs down to
 * the bottom.
 *
 * <p>The switch off, settings that aren't ready yet or a failure in here, and the bar and the
 * margin stay Facebook's.
 */
public final class ReelsTabBarHidden {
    /** Counted each time the tab bar is hidden for the Reels tab. */
    static final String HIDDEN = "Tab bar hidden on the Reels tab";
    /** Counted each time the Reels page is sized without the tab bar's height. */
    static final String UNPADDED = "Reels page sized without the tab bar";
    /** Counted each time a reel is sized without the hidden tab bar's height. */
    static final String REEL_SIZED = "Reel sized without the tab bar";

    /** The hook's name in the diagnostics report. */
    static final String HOOK = "Hide the tab bar on the Reels tab";

    private static final String FAMILY = FamilyNames.BOTTOM_TAB_BAR;

    /** The bar this class hid, so only that one is shown again. */
    private static WeakReference<View> hidden = new WeakReference<>(null);

    private ReelsTabBarHidden() {
    }

    /** Whether the bar goes on the Reels tab: the switch on and the settings ready. */
    static boolean on() {
        return Utils.settingsReady() && Settings.HIDE_REELS_TAB_BAR.get();
    }

    /**
     * Injection point, where Facebook restyles the tab bar for the tab just selected: the bar and
     * that tab. On the Reels tab the bar is hidden; on any other tab a bar hidden here is shown
     * again. Never throws.
     */
    public static void tabShown(@Nullable View bar, @Nullable Object tab) {
        try {
            if (bar == null || tab == null) return;
            HookStatus.invoked(FAMILY);
            boolean reels = StartTab.VIDEO.isTab(tab.getClass().getName());
            if (reels && on()) {
                HookStatus.bound(FAMILY, HOOK);
                if (bar.getVisibility() != View.GONE) {
                    bar.setVisibility(View.GONE);
                    HookStatus.counted(FAMILY, HIDDEN);
                    Logger.printDebug(() -> "Reels tab bar: hidden");
                }
                hidden = new WeakReference<>(bar);
            } else if (hidden.get() == bar) {
                hidden = new WeakReference<>(null);
                bar.setVisibility(View.VISIBLE);
                Logger.printDebug(() -> "Reels tab bar: shown again");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
        }
    }

    /**
     * Injection point, where Facebook sets a tab's page bottom margin to the bottom bars' height: that
     * margin and the page's tab. 0 for the Reels tab, whose bar {@link #tabShown} hides, so the reel
     * runs down to the bottom. Never throws.
     */
    public static int pageBottomMargin(int margin, @Nullable Object tab) {
        try {
            if (margin == 0 || tab == null || !StartTab.VIDEO.isTab(tab.getClass().getName()) || !on()) return margin;
            HookStatus.counted(FAMILY, UNPADDED);
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
            return margin;
        }
    }

    /**
     * Injection point, where Facebook takes the bottom bars' height off a reel's height: that
     * height. Only the Reels tab's own viewer gets there, and Facebook sets that tab up at start,
     * before {@link #tabShown} first hides the bar, so this answers 0 whenever the switch is on and
     * the reel runs down to the bottom. Never throws.
     */
    public static int reelBottomBars(int height) {
        try {
            if (height == 0 || !on()) return height;
            HookStatus.counted(FAMILY, REEL_SIZED);
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
            return height;
        }
    }
}
