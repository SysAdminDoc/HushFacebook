/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.IntegerSetting;

/**
 * Hide the tab bar on the Reels tab only.
 *
 * <p>Facebook restyles its tab bar in one method each time a tab is selected, handed the bar and the
 * selected tab. The patch hands both to {@link #tabShown} there, which hides the bar while the
 * selected tab is Reels (Facebook's Video tab) and shows it again on any other tab. Facebook lifts
 * the Reels page above the tab bar with a bottom margin of the bar's height, and a hidden bar keeps
 * its height there, so the reel stopped one bar short of the screen's bottom. That margin goes
 * through {@link #pageBottomMargin} with the page's tab. The Reels tab's viewer then sizes each
 * reel to the screen minus the same height, so it goes through {@link #reelBottomBars} as well.
 * Facebook's height includes the phone's navigation bar, which the tab bar pads its tabs up over,
 * so with the switch on both answer only that strip ({@link #navigationStrip}): the reel runs down
 * to the gesture bar, and its time and seek bar stay clear of it (#516).
 *
 * <p>The same method fades out the thin line Facebook draws along the bar: on Reels whenever the
 * bar goes, and on every tab while {@link Settings#HIDE_TAB_BAR_LINE} is on (#521). With that
 * switch on, the bar's own layout hands it to {@link #barLaidOut} as well, so the line is gone from
 * the start rather than from the first tab tapped.
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

    /** A view in Facebook's window (the tab bar), to read the phone's navigation bar from. */
    private static WeakReference<View> window = new WeakReference<>(null);
    /** The screen last created or resumed, for a reel sized before its window is laid out. */
    private static WeakReference<Activity> screen = new WeakReference<>(null);
    /** The navigation bar last read, for a call that comes before any window can say. */
    private static int lastStrip;
    /** The bar's height last read while it had one, for a reel sized while the bar is gone. */
    private static int lastBar;
    /** Whether that read was with the screen on its side, and whether its room is kept for the next start. */
    private static boolean lastWide;
    private static boolean roomKept;

    /** Insets standing in for the window's in tests. */
    @Nullable static volatile WindowInsets insetsForTests;

    private ReelsTabBarHidden() {
    }

    /** The screen Facebook just created or resumed, whose window the Reels page sits in. Never throws. */
    public static void activityShown(@Nullable Activity activity) {
        if (activity != null && screen.get() != activity) screen = new WeakReference<>(activity);
    }

    /** Forgets the window, the screen and the navigation bar read from them. */
    static void forgetWindow() {
        window = new WeakReference<>(null);
        screen = new WeakReference<>(null);
        lastStrip = 0;
        lastBar = 0;
        lastWide = false;
        roomKept = false;
    }

    private static void windowFrom(View view) {
        if (window.get() != view) window = new WeakReference<>(view);
    }

    /**
     * How much of the window's bottom the phone's navigation bar covers, the gesture bar or the
     * buttons. Facebook's bar height includes it, since the bar pads its tabs up over it, so a page
     * or reel without the bar keeps this much room and its time, caption and seek bar stay where a
     * finger can reach them (#516). A window that stops above the navigation bar answers 0.
     */
    static int navigationStrip() {
        View view = window.get();
        WindowInsets insets = insetsForTests;
        if (insets == null && view != null) insets = view.getRootWindowInsets();
        if (insets == null) insets = screenInsets();
        if (insets == null) return lastStrip;
        int strip = RoundedTabBar.navigationBottom(insets);
        if (view != null) strip -= belowContent(view);
        lastStrip = Math.max(0, strip);
        return lastStrip;
    }

    /**
     * How much taller the Reels list runs with the bar hidden there: the bar's height, which pads
     * its tabs up over the phone's navigation bar, less that navigation bar, which the page keeps
     * ({@link #pageBottomMargin}). Facebook makes the Reels tab's rows before the list is laid out,
     * at the height the bar leaves, so a row made then needs this much more to fill the list (#566).
     * Always the room kept: a bar laid out in its window updates it first, and a row made as
     * Facebook starts, before then, gets the one kept from an earlier start. Facebook lays the bar
     * out more than once as it starts, so its height of the moment can be short of the room it ends
     * up taking, and a row made from that stopped short of the bottom on an emulator. 0 with the
     * switch off, or before any bar was laid out in its window. Never throws.
     */
    static int roomFreed() {
        try {
            if (!on()) return 0;
            View bar = window.get();
            if (bar != null) noteBar(bar);
            boolean wide = TabBarScrollAway.wide();
            int room = (wide ? TabBarScrollAway.Stored.WIDE_BAR_ROOM : TabBarScrollAway.Stored.BAR_ROOM).get();
            Logger.printDebug(() -> "Reels tab bar: frees " + room + (wide ? " on its side" : "")
                    + ", the bar now " + lastBar + (roomKept ? " in its window" : ""));
            return room;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
            return 0;
        }
    }

    /**
     * Reads a laid out bar's height, and keeps the room it takes above the navigation bar for the
     * next start, with the switch off too, so the first start after it's turned on already has it.
     */
    private static void noteBar(View bar) {
        int height = bar.getHeight();
        if (height <= 0) return;
        boolean wide = sideways(bar);
        if (height == lastBar && wide == lastWide && roomKept) return;
        lastBar = height;
        lastWide = wide;
        if (!Utils.settingsReady() || (insetsForTests == null && bar.getRootWindowInsets() == null)) return;
        int room = Math.max(0, height - navigationStrip());
        IntegerSetting kept = wide ? TabBarScrollAway.Stored.WIDE_BAR_ROOM : TabBarScrollAway.Stored.BAR_ROOM;
        if (room > 0 && kept.get() != room) {
            kept.save(room);
            Logger.printDebug(() -> "Reels tab bar: takes " + room + " above the navigation bar" + (wide ? " on its side" : ""));
        }
        roomKept = true;
    }

    /** Whether the screen [view] is on is held on its side. */
    private static boolean sideways(View view) {
        return view.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    /**
     * The insets the screen's window gets, before it's laid out. Only from Android 15, where
     * Facebook's window always reaches the bottom of the screen.
     */
    @Nullable
    private static WindowInsets screenInsets() {
        if (Build.VERSION.SDK_INT < 35) return null;
        Activity activity = screen.get();
        if (activity == null) return null;
        return activity.getWindowManager().getCurrentWindowMetrics().getWindowInsets();
    }

    /** How far above the window's bottom its content stops. */
    private static int belowContent(View view) {
        View root = view.getRootView();
        View content = root.findViewById(android.R.id.content);
        if (content == null || content.getHeight() <= 0) return 0;
        int[] at = new int[2];
        content.getLocationInWindow(at);
        return Math.max(0, root.getHeight() - (at[1] + content.getHeight()));
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
            windowFrom(bar);
            noteBar(bar);
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
            ReelsStatusBar.tabShown(bar, tab);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, ReelsStatusBar.HOOK, failure);
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

    /**
     * Injection point, at the start of the tab bar's own layout: the bar. Facebook restyles the bar
     * only as a tab is picked (its start-up restyle waits on its own flags), so with the line switch
     * on, the line goes here as the app starts too, before any tab is tapped. Never throws.
     */
    public static void barLaidOut(@Nullable View bar) {
        if (bar == null) return;
        try {
            windowFrom(bar);
            noteBar(bar);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
        }
        try {
            if (!lineOff()) return;
            HookStatus.bound(FAMILY, LINE_HOOK);
            // The line beside the bar is laid out after it, so it may not have a size yet.
            if (!hideDivider(bar)) bar.post(() -> dividerLater(bar));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, LINE_HOOK, failure);
        }
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
     * margin and the page's tab. Only the phone's navigation bar for the Reels tab, whose bar
     * {@link #tabShown} hides, so the reel runs down to the gesture bar. Never throws.
     */
    public static int pageBottomMargin(int margin, @Nullable Object tab) {
        try {
            if (margin == 0 || tab == null || !StartTab.VIDEO.isTab(tab.getClass().getName()) || !on()) return margin;
            HookStatus.counted(FAMILY, UNPADDED);
            return Math.min(navigationStrip(), margin);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
            return margin;
        }
    }

    /**
     * Injection point, where Facebook takes the bottom bars' height off a reel's height: that
     * height. Only the Reels tab's own viewer gets there, and Facebook sets that tab up at start,
     * before {@link #tabShown} first hides the bar, so this answers only the phone's navigation bar
     * whenever the switch is on, and the reel runs down to the gesture bar. Never throws.
     */
    public static int reelBottomBars(int height) {
        try {
            if (height == 0 || !on()) return height;
            HookStatus.counted(FAMILY, REEL_SIZED);
            return Math.min(navigationStrip(), height);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
            return height;
        }
    }
}
