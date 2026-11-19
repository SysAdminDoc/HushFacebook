/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;

/**
 * A see-through status bar on the Reels tab (#466). With the tab bar at the bottom and See-through
 * bars on, the reel runs up behind the status bar, the time and the battery, instead of stopping
 * under the solid strip Facebook paints there. On the other tabs that strip is frosted from the top
 * bar ({@link TranslucentBars}, #507), but the Reels tab has no top bar and a reel's video is a
 * surface no frost can copy, so there the strip is closed: Facebook's padding for the status bar is
 * answered 0 on the main screen while the Reels tab shows ({@link HiddenStatusBar#topInset}), the
 * Reels page runs up to the top edge of the screen, and {@link ReelsHeaderClear} keeps the Reels
 * header below the status bar. Another tab, the switch off, the tabs at the top or Hushfacebook
 * paused open the strip again. Facebook only pads for the status bar again when the window's insets
 * are handed out, so each change asks for that once, from the tab bar as a tab shows and from the
 * see-through bars' frame pass for a switch flipped or a pause.
 */
public final class ReelsStatusBar {
    private static final String FAMILY = FamilyNames.BOTTOM_TAB_BAR;
    static final String HOOK = "Reels under the status bar";
    /** Counted each time Facebook's strip behind the status bar is closed over the Reels tab. */
    static final String CLOSED = "Status bar strip opened over a reel";

    /** Whether the main screen shows the Reels tab, as the tab bar was last restyled for. */
    private static volatile boolean reels;
    /**
     * Whether the tab bar has been restyled for a tab yet. Facebook holds its first restyle back
     * as the app starts, so until then the tab is read off the main screen each frame.
     */
    private static volatile boolean told;
    /** Whether the strip is closed over the Reels tab now, as the main screen's padding was last answered. */
    private static volatile boolean closed;
    /** The state the window's insets were last asked again for, so each change asks once. */
    private static volatile boolean asked;
    /** The status bar's height, in place of the window's, for tests; -1 reads the window. */
    static volatile int statusBarForTests = -1;

    private ReelsStatusBar() {
    }

    /**
     * Whether the switches let the reel run under the status bar: See-through bars on, Tab bar at the
     * bottom on, nothing paused. The bottom switch is read itself, since it wins over the top one:
     * with the tabs moved down Facebook still names its top bar's entry for the title and the tabs,
     * so {@link TabBarScrollAway#tabsOnTop} reads true there (seen on the S22, 588).
     */
    static boolean wanted() {
        return Utils.settingsReady() && !Setting.isPaused() && Settings.TRANSLUCENT_BARS.get()
                && Settings.BOTTOM_TAB_BAR.get();
    }

    /** Whether the strip behind the status bar is closed over the Reels tab now. */
    static boolean closed() {
        return closed;
    }

    /**
     * Called from {@link ReelsTabBarHidden#tabShown} each time Facebook restyles the tab bar [bar]
     * for the tab it shows, [tab]: the strip closes with the Reels tab and opens with the others.
     */
    static void tabShown(View bar, Object tab) {
        boolean was = reels;
        reels = StartTab.VIDEO.isTab(tab.getClass().getName());
        if (!told || was != reels) {
            boolean reelsNow = reels;
            Logger.printDebug(() -> "Reels status bar: the tab bar shows " + tab.getClass().getName()
                    + (reelsNow ? ", the Reels tab" : "") + "; see-through " + Settings.TRANSLUCENT_BARS.get()
                    + ", tabs at the bottom " + Settings.BOTTOM_TAB_BAR.get() + ", paused " + Setting.isPaused());
        }
        told = true;
        refresh(bar);
    }

    /**
     * Called from the see-through bars' frame pass over the main screen's [window]: a switch flipped
     * or a pause opens or closes the strip, and so does the tab the app started on.
     */
    static void frame(@Nullable View window) {
        if (!told) reels = TabBarScrollAway.onReelsTab();
        refresh(window);
    }

    /**
     * Asks [window]'s insets to be handed out again when the strip should close or open and hasn't
     * been asked to yet, so Facebook pads the main screen again. Once per change, so a build where
     * the padding isn't hooked never asks every frame.
     */
    static void refresh(@Nullable View window) {
        boolean now = reels && wanted();
        if (now == asked || window == null) return;
        asked = now;
        window.getRootView().requestApplyInsets();
        Logger.printDebug(() -> "Reels status bar: the strip " + (now ? "closes" : "opens") + " as the window's insets come again");
    }

    /**
     * The main screen's answer for the top inset [top] Facebook pads [content] by, once Hide the
     * status bar hasn't closed the strip already: 0 while the Reels tab shows and the switches let
     * the reel run under the status bar, otherwise [top]. A window over the main screen, a sheet or
     * a dialog, keeps its own padding.
     */
    static int topInset(int top, @Nullable Activity screen, View content) {
        Window window = screen == null ? null : screen.getWindow();
        if (window == null || !TranslucentBars.MAIN_SCREEN.equals(screen.getClass().getName())
                || content.getRootView() != window.peekDecorView()) {
            return top;
        }
        boolean close = reels && wanted();
        if (close != closed) {
            Logger.printDebug(() -> "Reels status bar: Facebook's " + top + " px for the status bar answered "
                    + (close ? "0" : "as it is"));
        }
        closed = close;
        if (!close) return top;
        HookStatus.invoked(FAMILY);
        HookStatus.bound(FAMILY, HOOK);
        HookStatus.counted(FAMILY, CLOSED);
        return 0;
    }

    /**
     * The strip isn't closed for the Reels tab: Hide the status bar closed it itself with the bar
     * gone, or the switches leave it open. Either way the Reels header needn't make room for a bar.
     */
    static void notClosed() {
        closed = false;
    }

    /**
     * How tall the status bar over [view] is while it shows, in pixels, or 0 when it's gone or
     * Android can't say.
     */
    static int statusBar(View view) {
        if (statusBarForTests >= 0) return statusBarForTests;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0;
        WindowInsets insets = view.getRootWindowInsets();
        if (insets == null || !insets.isVisible(WindowInsets.Type.statusBars())) return 0;
        return insets.getInsets(WindowInsets.Type.statusBars()).top;
    }

    /**
     * How much taller the Reels list [list] gets once the strip closes over it: the room between
     * the top of the window and the list's top, up to the status bar's height, while the strip is
     * open and the switches will close it on the Reels tab; 0 otherwise.
     */
    static int roomAbove(View list) {
        if (closed || !wanted()) return 0;
        int bar = statusBar(list);
        if (bar <= 0) return 0;
        int[] at = new int[2];
        list.getLocationInWindow(at);
        return Math.max(0, Math.min(at[1], bar));
    }

    static void resetForTests() {
        reels = false;
        told = false;
        closed = false;
        asked = false;
        statusBarForTests = -1;
    }
}
