/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.view.View;
import android.view.WindowInsets;

import androidx.annotation.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Whether the tab bar at the bottom slides away while the feed scrolls down. Facebook has this
 * built in: as its main screen starts, it adds the bottom bar to the views that scroll away with
 * the feed, the way the top bar does, when one check answers yes. That check passes only with
 * scroll-away running on the phone and the bar at the bottom, and then asks two server settings
 * that turn the experiment on for some accounts. The hook sits between those two parts. Its yes
 * stands in for the server settings, and Facebook's own code then slides the bar out scrolling
 * down, brings it back scrolling up or at the top, and keeps the feed clear of it. The feed's
 * floating button, the mini player and the screens that make room for the bar ask the same check,
 * so they move with it. A kept bar answers no there instead, so it stays put on the accounts the
 * server settings give the experiment to.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, the answer is no and
 * Facebook asks its server settings as before. With the bar at the top the hook isn't reached. The
 * bar's entry is made as the main screen starts, so a change shows after a restart.
 *
 * <p>The top bar is a scroll-away entry of its own, added whatever the server settings say. Each
 * move of an entry asks {@link #topBarOffset}, which keeps a kept top bar where it rests, and
 * {@link #topBarFade}, which keeps it from fading out as it would have gone. Its entry keeps its
 * place in Facebook's insets, so the feed still starts below it.
 *
 * <p>A kept bar with See-through bars on is kept another way (#250). Answering the check no has
 * Facebook pad the pages up to the bar, so nothing is drawn under it and the see-through showed
 * black. With both on the check is answered yes instead, so Facebook builds its scroll-away layout,
 * the one that runs the pages under the bar, and the bar's own entry is then held where it rests
 * the way a kept top bar is. Off the feed it goes with Facebook's own hide, like the top bar.
 * That layout is built as the main screen starts, so on a kept bar a See-through bars change waits
 * for a restart, and {@link #seeThroughRestartOwed} tells the settings page to say so.
 */
public final class TabBarScrollAway {
    /** Counted under the patch's name each time the check is answered yes. */
    static final String SLIDES_AWAY = "Tab bar set to slide away";
    /** Counted under the patch's name each time the check is answered no for a kept bar. */
    static final String STAYS_PUT = "Tab bar kept on screen";
    /** Counted under the patch's name each time a kept top bar is held where it rests. */
    static final String TOP_BAR_HELD = "Top bar held on screen";
    /** Counted each time a kept top bar is kept from fading as Facebook moves it. */
    static final String TOP_BAR_UNFADED = "Top bar kept from fading";
    /** Counted each time a kept bar's pages are padded past the navigation bar under it. */
    static final String PAGES_CLEARED = "Pages kept clear of the kept tab bar";
    /** Counted each time a kept top bar lets a move through because Facebook left the bars' surface. */
    static final String TOP_BAR_LET_GO = "Top bar let go off the feed";
    /** Counted each time the check is answered yes for a kept see-through bar, so its pages run under it. */
    static final String PAGES_RUN_UNDER = "Tab bar kept with its pages under it";
    /** Counted each time a kept see-through bar's entry is held where it rests inside Facebook's scroll-away. */
    static final String BAR_HELD = "Tab bar held on screen";
    /** Counted each time a kept see-through bar lets a move through because Facebook left the bars' surface. */
    static final String BAR_LET_GO = "Tab bar let go off the feed";

    /** The name Facebook's main screen gives the top bar's scroll-away entry. */
    static final String APP_HEADER = "app_header_container";
    /** The entry's name when the tabs sit at the top, under the top bar, and move with it. */
    static final String TITLE_AND_TABS = "title_bar_tab_bar_container";
    /** The name Facebook's main screen gives the bottom bar's scroll-away entry. */
    static final String BOTTOM_TABS = "bottom_tabs_container";

    private static final String FAMILY = FamilyNames.BOTTOM_TAB_BAR;

    /**
     * Whether the main screen has left the surface the bars belong on. Facebook turns scroll-away
     * off as it opens the Reels tab or a full-screen page and hides the bars itself, then back on as
     * it returns to the feed. Between the two, a kept top bar lets Facebook's moves through, so it
     * goes with the feed instead of sitting over a reel (issue #159).
     */
    private static volatile boolean offTheFeed;

    /**
     * Whether the bar was kept, and whether it was see-through, as Facebook's check first ran in
     * this process: the layout the bar was built with, whatever the settings say now. Null until
     * the check runs.
     */
    @Nullable
    private static volatile Boolean builtKept;
    @Nullable
    private static volatile Boolean builtSeeThrough;

    private TabBarScrollAway() {
    }

    /** The first run of Facebook's check builds the bar's layout: remembered, so a later change can be compared with it. */
    private static void noteBuilt() {
        if (builtKept != null) return;
        builtSeeThrough = Settings.TRANSLUCENT_BARS.get();
        builtKept = !Settings.TAB_BAR_SCROLL_AWAY.get() && Settings.KEEP_TAB_BAR.get();
    }

    /**
     * Whether a See-through bars change waits for a restart: the bar was built kept and is still
     * kept, and the see-through switch differs from the one it was built with. A bar that isn't
     * kept, or one not built yet, shows the change right away, and a kept bar's own switches say
     * their restart themselves. Never throws.
     */
    public static boolean seeThroughRestartOwed() {
        try {
            Boolean kept = builtKept;
            Boolean seeThrough = builtSeeThrough;
            if (kept == null || seeThrough == null || !kept || !Utils.settingsReady()) return false;
            if (Settings.TAB_BAR_SCROLL_AWAY.get() || !Settings.KEEP_TAB_BAR.get()) return false;
            boolean seeThroughNow = Settings.TRANSLUCENT_BARS.get();
            return seeThrough != seeThroughNow;
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not tell whether See-through bars waits for a restart", failure);
            return false;
        }
    }

    /** Forgets the layout the bar was built with, for a test that builds it again. */
    public static void forgetBuilt() {
        builtKept = null;
        builtSeeThrough = null;
    }

    /**
     * The hook at the start of Facebook's deactivate of scroll-away: the main screen is leaving the
     * bars' surface and is about to hide them. Never throws.
     */
    public static void topBarsLetGo() {
        offTheFeed = true;
    }

    /** The hook at the start of Facebook's reactivate of scroll-away: the main screen is back on the feed. */
    public static void topBarsTakenBack() {
        offTheFeed = false;
    }

    /** Counted each time the top padding under a top tab bar is dropped so the reel fills the screen. */
    static final String REEL_UNDER_BAR = "Reel run under the top tab bar";

    /**
     * The hook at the return of each method that answers how far down Facebook pads a page's
     * content for the top bars (#264). With the tab bar at the top, the Reels tab started its reel
     * below the bar and the video's letterbox read as a black strip. While the main screen is off
     * the feed (Facebook has deactivated scroll-away for the Reels tab or a full-screen page) the
     * answer is 0, so the reel fills the screen behind the bar. Anywhere else, with the top bar
     * off, with the bottom bar on, paused, before the settings are ready or on a failure, it's
     * Facebook's own number.
     */
    public static int pagesTop(int padding) {
        try {
            if (padding == 0 || !offTheFeed) return padding;
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || Settings.BOTTOM_TAB_BAR.get() || !Settings.TOP_TAB_BAR.get()) return padding;
            HookStatus.bound(FAMILY, "reel under the top bar");
            HookStatus.counted(FAMILY, REEL_UNDER_BAR);
            Logger.printDebug(() -> "Tab bar at the top: the reel runs under the bar");
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reel under the top bar", failure);
            return padding;
        }
    }

    /**
     * The hook, right after Facebook's check finds the tab bar at the bottom. True lets the bar
     * slide away; false leaves the answer to Facebook's server settings.
     */
    public static boolean slidesAway() {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady()) return false;
            noteBuilt();
            if (Settings.TAB_BAR_SCROLL_AWAY.get()) {
                HookStatus.bound(FAMILY, "tab bar scroll-away");
                HookStatus.counted(FAMILY, SLIDES_AWAY);
                Logger.printDebug(() -> "Tab bar scroll-away: the bar at the bottom slides away");
                return true;
            }
            if (keptUnderSeeThrough()) {
                HookStatus.bound(FAMILY, "tab bar kept see-through");
                HookStatus.counted(FAMILY, PAGES_RUN_UNDER);
                Logger.printDebug(() -> "Tab bar scroll-away: the kept see-through bar takes the layout with the pages under it");
                return true;
            }
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "tab bar scroll-away", failure);
            return false;
        }
    }

    /**
     * Asked just ahead of {@link #slidesAway()}. True answers Facebook's check no outright, so the
     * bar keeps its place whatever the server settings say. Hide the tab bar while scrolling wins
     * when both are on, the way the settings page greys this switch out while that one is on. A
     * kept bar with See-through bars on answers yes there instead and is held in the layout that
     * gives (#250), so this is false for it.
     */
    public static boolean staysPut() {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady()) return false;
            noteBuilt();
            if (Settings.TAB_BAR_SCROLL_AWAY.get() || !Settings.KEEP_TAB_BAR.get()) return false;
            if (Settings.TRANSLUCENT_BARS.get()) return false;
            HookStatus.bound(FAMILY, "tab bar kept");
            HookStatus.counted(FAMILY, STAYS_PUT);
            Logger.printDebug(() -> "Tab bar scroll-away: the bar at the bottom stays put");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "tab bar kept", failure);
            return false;
        }
    }

    /**
     * Whether the kept bar is held inside Facebook's scroll-away layout rather than kept by a no:
     * Keep the tab bar on screen and See-through bars on, Hide the tab bar while scrolling off. The
     * settings are read, so the caller checks they're ready first.
     */
    static boolean keptUnderSeeThrough() {
        return !Settings.TAB_BAR_SCROLL_AWAY.get() && Settings.KEEP_TAB_BAR.get() && Settings.TRANSLUCENT_BARS.get();
    }

    /**
     * The hook at the start of each method that moves a scroll-away view, with the entry's name and
     * the distance Facebook is about to move it from where it rests. A kept top bar gets 0, so it
     * stays where it rests, and so does the bottom bar's entry for a kept see-through bar; every
     * other entry, and each bar while its switch is off, keeps Facebook's distance. So does a kept
     * bar while the main screen is off the feed, where Facebook hides the bars itself.
     */
    public static int topBarOffset(@Nullable String entry, int offset) {
        try {
            if (offset == 0) return offset;
            boolean topBar = APP_HEADER.equals(entry) || TITLE_AND_TABS.equals(entry);
            if (!topBar && !BOTTOM_TABS.equals(entry)) return offset;
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady()) return offset;
            if (topBar ? !Settings.KEEP_TOP_BAR.get() : !keptUnderSeeThrough()) return offset;
            HookStatus.bound(FAMILY, topBar ? "top bar kept" : "tab bar kept see-through");
            if (offTheFeed) {
                HookStatus.counted(FAMILY, topBar ? TOP_BAR_LET_GO : BAR_LET_GO);
                return offset;
            }
            HookStatus.counted(FAMILY, topBar ? TOP_BAR_HELD : BAR_HELD);
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "top bar kept", failure);
            return offset;
        }
    }

    /**
     * The hook beside {@link #topBarOffset} in the same moves, with how far out of sight the move
     * takes the entry, 0 to 1. Facebook fades some entries by it as they go (an entry over a
     * see-through navigation bar, and one part of the top bar on some accounts), which a held
     * distance doesn't stop. A kept top bar gets 0, so it stays fully drawn, and so does the bottom
     * bar's entry for a kept see-through bar.
     */
    public static float topBarFade(@Nullable String entry, float hidden) {
        try {
            if (hidden == 0f) return hidden;
            boolean topBar = APP_HEADER.equals(entry) || TITLE_AND_TABS.equals(entry);
            if (!topBar && !BOTTOM_TABS.equals(entry)) return hidden;
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || offTheFeed) return hidden;
            if (topBar ? !Settings.KEEP_TOP_BAR.get() : !keptUnderSeeThrough()) return hidden;
            HookStatus.bound(FAMILY, topBar ? "top bar kept drawn" : "tab bar kept see-through");
            if (topBar) HookStatus.counted(FAMILY, TOP_BAR_UNFADED);
            return 0f;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "top bar kept drawn", failure);
            return hidden;
        }
    }

    /**
     * The tab pager's padding call as the main screen starts. Facebook's padding is set first,
     * always. With the bar staying put, that's the bar's height, but on a phone drawing under its
     * navigation bar the bar's holder covers the navigation bar too, so the bottom of each page, a
     * reel's progress bar with it, sat behind the tab bar. For a kept bar the pager then adds that
     * overlap each time it's laid out with Facebook's padding, and leaves any other padding alone.
     * One listener per pager, however often Facebook sets it up. A kept see-through bar's pages run
     * under it by design, so they get no listener either.
     */
    public static void padsPager(@Nullable View pager, int left, int top, int right, int bottom) {
        if (pager == null) return;
        pager.setPadding(left, top, right, bottom);
        try {
            HookStatus.invoked(FAMILY);
            if (bottom <= 0 || !Utils.settingsReady() || Settings.TAB_BAR_SCROLL_AWAY.get() || !Settings.KEEP_TAB_BAR.get()) return;
            if (Settings.TRANSLUCENT_BARS.get()) return;
            HookStatus.bound(FAMILY, "tab bar kept pages");
            ClearsNavigationBar listening;
            synchronized (CLEARED_PAGERS) {
                listening = CLEARED_PAGERS.get(pager);
                if (listening == null) {
                    listening = new ClearsNavigationBar(bottom);
                    CLEARED_PAGERS.put(pager, listening);
                    pager.addOnLayoutChangeListener(listening);
                }
            }
            listening.facebooks = bottom;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "tab bar kept pages", failure);
        }
    }

    /** The pagers given a listener, so a second set-up doesn't add another. */
    static final Map<View, ClearsNavigationBar> CLEARED_PAGERS = new WeakHashMap<>();

    /**
     * The bottom padding a kept bar's pager wants: [facebooks], plus as much of the navigation bar
     * as the pager reaches under, with the pager ending at [pagerBottom] in a window [windowBottom]
     * tall. -1 leaves [current] alone, as Facebook set it to something other than its own or [ours].
     */
    static int keptPadding(int current, int facebooks, int ours, int pagerBottom, int windowBottom, int navigationBar) {
        if (current != facebooks && current != ours) return -1;
        int under = navigationBar <= 0 ? 0 : Math.max(0, Math.min(navigationBar, pagerBottom - (windowBottom - navigationBar)));
        return facebooks + under;
    }

    static final class ClearsNavigationBar implements View.OnLayoutChangeListener {
        private volatile int facebooks;
        private int ours = -1;

        ClearsNavigationBar(int facebooks) {
            this.facebooks = facebooks;
        }

        @Override
        public void onLayoutChange(View pager, int left, int top, int right, int bottom,
                                   int oldLeft, int oldTop, int oldRight, int oldBottom) {
            try {
                WindowInsets insets = pager.getRootWindowInsets();
                if (insets == null) return;
                int[] at = new int[2];
                pager.getLocationInWindow(at);
                int current = pager.getPaddingBottom();
                int facebooks = this.facebooks;
                int wanted = keptPadding(current, facebooks, ours, at[1] + pager.getHeight(),
                        pager.getRootView().getHeight(), insets.getInsets(WindowInsets.Type.navigationBars()).bottom);
                // A padding of Facebook's own for another kind of page stays, and the overlap comes
                // back once Facebook puts the bar's padding back.
                if (wanted < 0 || wanted == current) return;
                ours = wanted;
                // Not in the middle of this layout: the padding change asks for the next one.
                pager.post(() -> {
                    if (pager.getPaddingBottom() != current) return;
                    pager.setPadding(pager.getPaddingLeft(), pager.getPaddingTop(), pager.getPaddingRight(), wanted);
                    if (wanted != facebooks) HookStatus.counted(FAMILY, PAGES_CLEARED);
                });
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "tab bar kept pages", failure);
                pager.removeOnLayoutChangeListener(this);
            }
        }
    }
}
