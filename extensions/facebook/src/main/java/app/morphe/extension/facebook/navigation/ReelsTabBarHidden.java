/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

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
 * <p>The same method fades out the thin line Facebook draws along the bar: on Reels whenever the
 * bar goes, and on every tab while {@link Settings#HIDE_TAB_BAR_LINE} is on (#521).
 *
 * <p>The switch off, settings that aren't ready yet or a failure in here, and the bar, the line and
 * the margin stay Facebook's.
 */
public final class ReelsTabBarHidden {
    /** Counted each time the tab bar is hidden for the Reels tab. */
    static final String HIDDEN = "Tab bar hidden on the Reels tab";
    /** Counted each time the Reels page is sized without the tab bar's height. */
    static final String UNPADDED = "Reels page sized without the tab bar";
    /** Counted each time a reel is sized without the hidden tab bar's height. */
    static final String REEL_SIZED = "Reel sized without the tab bar";

    /** Counted each time the line along the tab bar is faded out. */
    static final String LINE_HIDDEN = "Line along the tab bar hidden";

    /** The hook's name in the diagnostics report. */
    static final String HOOK = "Hide the tab bar on the Reels tab";
    /** The line switch's hook name in the diagnostics report. */
    static final String LINE_HOOK = "Hide the line along the tab bar";

    private static final String FAMILY = FamilyNames.BOTTOM_TAB_BAR;

    /** How far up from the bar its divider can sit, and how thick a divider can be. */
    private static final int DIVIDER_LEVELS = 3;
    private static final int DIVIDER_MAX_DP = 4;

    /** The bar this class hid, so only that one is shown again. */
    private static WeakReference<View> hidden = new WeakReference<>(null);

    /** The divider this class faded out, and the alpha it goes back to. */
    private static WeakReference<View> divider = new WeakReference<>(null);
    private static float dividerAlpha = 1f;

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
        if (bar == null || tab == null) return;
        boolean barGoes = false;
        try {
            HookStatus.invoked(FAMILY);
            barGoes = StartTab.VIDEO.isTab(tab.getClass().getName()) && on();
            if (barGoes) {
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
        try {
            if (barGoes || lineOff()) {
                if (!barGoes) HookStatus.bound(FAMILY, LINE_HOOK);
                // The header can still be filling in the first time the tab shows.
                if (!hideDivider(bar)) bar.post(() -> dividerLater(bar));
            } else {
                showDivider();
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, barGoes ? HOOK : LINE_HOOK, failure);
        }
    }

    /** Whether the line along the bar goes on every tab: its switch on and the settings ready. */
    static boolean lineOff() {
        return Utils.settingsReady() && Settings.HIDE_TAB_BAR_LINE.get();
    }

    private static void dividerLater(View bar) {
        try {
            if (hidden.get() == bar || lineOff()) hideDivider(bar);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, LINE_HOOK, failure);
        }
    }

    /**
     * Fades out the thin line Facebook keeps beside the bar. With the tabs on top it stayed behind as
     * a grey line across the Reels header once the bar had gone (#508). Facebook shows and hides that
     * line itself, so this leaves its visibility alone. Answers whether there was one.
     */
    private static boolean hideDivider(View bar) {
        View found = dividerBeside(bar);
        if (found == null) return false;
        if (found == divider.get()) return true;
        showDivider();
        dividerAlpha = found.getAlpha();
        found.setAlpha(0f);
        divider = new WeakReference<>(found);
        HookStatus.counted(FAMILY, LINE_HIDDEN);
        return true;
    }

    private static void showDivider() {
        View line = divider.get();
        divider = new WeakReference<>(null);
        if (line != null) line.setAlpha(dividerAlpha);
    }

    /** The first thin full-width view beside [bar] or beside one of its nearest parents. */
    @Nullable
    private static View dividerBeside(View bar) {
        int most = Math.round(DIVIDER_MAX_DP * bar.getResources().getDisplayMetrics().density);
        View from = bar;
        for (int level = 0; level < DIVIDER_LEVELS; level++) {
            ViewParent parent = from.getParent();
            if (!(parent instanceof ViewGroup)) return null;
            ViewGroup group = (ViewGroup) parent;
            for (int index = 0; index < group.getChildCount(); index++) {
                View child = group.getChildAt(index);
                if (child != from && isDivider(child, group, most)) return child;
            }
            from = group;
        }
        return null;
    }

    private static boolean isDivider(View child, ViewGroup parent, int most) {
        if (child instanceof ViewGroup || child.getVisibility() == View.GONE) return false;
        ViewGroup.LayoutParams params = child.getLayoutParams();
        int height = child.getHeight() > 0 ? child.getHeight() : params == null ? 0 : params.height;
        if (height <= 0 || height > most) return false;
        if (params != null && params.width == ViewGroup.LayoutParams.MATCH_PARENT) return true;
        return parent.getWidth() > 0 && child.getWidth() * 10 >= parent.getWidth() * 9;
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
