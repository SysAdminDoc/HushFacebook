/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.app.Activity;
import android.app.Application;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.settings.Setting;

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
     * off as it opens the Reels tab, another tab that isn't Home or a full-screen page and hides the
     * bars itself, then back on as it returns to the feed. Between the two, a kept top bar lets
     * Facebook's moves through, so it goes with the feed instead of sitting over a reel (issue #159).
     */
    private static volatile boolean offTheFeed;

    /** The main screen, for the tab it shows. */
    private static volatile WeakReference<Activity> mainScreen = new WeakReference<>(null);

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
        slidOnOurSwitch = false;
        tabsInTopEntry = false;
        droppedTop = 0;
        loggedUnder = false;
        reelsList = new WeakReference<>(null);
    }

    /**
     * Whether the top bar's scroll-away entry carries the tabs as well, which is how Facebook lays
     * the bars out with the tab bar at the top. That covers a bar Facebook put there itself, on the
     * accounts its server setting gives a top bar, as well as one Put the tab bar at the top moved
     * (#390). Read from the entries Facebook moves, so false until one has moved.
     */
    private static volatile boolean tabsInTopEntry;

    /** Notes which top bar entry Facebook moves: the one holding the tabs, or the title bar alone. */
    private static void noteTopEntry(@Nullable String entry) {
        if (TITLE_AND_TABS.equals(entry)) tabsInTopEntry = true;
        else if (APP_HEADER.equals(entry)) tabsInTopEntry = false;
    }

    /** A new main screen, as it's created. */
    static void mainScreenCreated(Activity screen) {
        mainScreen = new WeakReference<>(screen);
        frontScreen = new WeakReference<>(screen);
        if (screen != null) watchScreens(screen.getApplication());
    }

    /** The Facebook screen last made or brought back, held weakly: the one in front. */
    private static volatile WeakReference<Activity> frontScreen = new WeakReference<>(null);

    /** The application the screen watch went on, so a second main screen adds none. */
    private static volatile WeakReference<Application> watching = new WeakReference<>(null);

    private static synchronized void watchScreens(@Nullable Application application) {
        if (application == null || watching.get() == application) return;
        application.registerActivityLifecycleCallbacks(new FrontScreen());
        watching = new WeakReference<>(application);
    }

    /** Notes the screen in front as each one is made or comes back. */
    private static final class FrontScreen implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityCreated(Activity activity, @Nullable Bundle state) { frontScreen = new WeakReference<>(activity); }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityResumed(Activity activity) { frontScreen = new WeakReference<>(activity); }
        @Override public void onActivityPaused(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        @Override public void onActivityDestroyed(Activity activity) { }
    }

    /**
     * Whether another of Facebook's screens covers the main one, such as a saved video opened from
     * the Reels tab's library. Its reels come from the same code as the Reels tab's, in a screen of
     * its own with no tab or top bars, while the main screen behind it still shows the Reels tab.
     */
    private static boolean behindAnotherScreen() {
        Activity main = mainScreen.get();
        Activity front = frontScreen.get();
        return main != null && front != null && front != main && !front.isFinishing() && !front.isDestroyed();
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

    /** Counted each time the top padding under the top bars is dropped so the reel fills the screen. */
    static final String REEL_UNDER_BAR = "Reel run under the top bars";

    /**
     * The hook at the return of each method that answers how far down Facebook pads a page's
     * content for the top bars (#264). With the tab bar at the top, the Reels tab started its reel
     * below the bar and the video's letterbox read as a black strip. While the main screen shows the
     * Reels tab the answer is 0, so the reel fills the screen behind the bar. Off the feed alone
     * isn't enough, since Facebook turns scroll-away off on Notifications, Groups and Feeds too, and
     * those need the padding or the bar covers their first rows (#328). With Reels first on the bar,
     * Facebook treats it as its feed and leaves scroll-away on there, so the title bar slides off as
     * the reels move and a pad by its height left a black band over every reel after the first
     * (#390). That happens wherever the tab bar sits, since only the title bar slides there, so the
     * answer is 0 for it whatever the bar's position, unless Keep the top bar on screen holds the
     * bar. Reels further along a bar that isn't at the top keeps Facebook's number: Facebook hides
     * the title bar there itself. A bar Facebook put at the top on its own counts as at the top, so
     * Reels further along it runs under it too instead of leaving a black band where the title bar
     * was (#390). Anywhere else, paused, before the settings are ready or on a failure, it's
     * Facebook's own number.
     */
    public static int pagesTop(int padding) {
        try {
            if (padding == 0) return padding;
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || Setting.isPaused()) return padding;
            if (!reelUnderBars(tabsOnTop())) {
                loggedUnder = false;
                return padding;
            }
            if (padding > droppedTop) droppedTop = padding;
            HookStatus.bound(FAMILY, "reel under the top bar");
            HookStatus.counted(FAMILY, REEL_UNDER_BAR);
            if (!loggedUnder) {
                loggedUnder = true;
                Logger.printDebug(() -> "Top bars: the reel runs under them");
            }
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reel under the top bar", failure);
            return padding;
        }
    }

    /** The largest top padding {@link #pagesTop} has dropped, which Facebook took off each Reels row. */
    private static volatile int droppedTop;

    /**
     * Whether {@link #pagesTop} has logged the reel running under the bars since it last kept
     * Facebook's padding. Facebook asks on every layout pass, so it's logged once per stretch.
     */
    private static volatile boolean loggedUnder;

    /** Counted each time a Reels row gets back the top padding the reel runs under. */
    static final String REEL_ROW_FILLED = "Reels row made the page's height";

    /** Counted each time a Reels row worked out for the screen held the other way is fitted to the window (#531). */
    static final String REEL_ROW_TURNED = "Reels row fitted to the turned screen";

    /**
     * The hook on the height Facebook gives each row of the Reels tab, as it makes the tab's list
     * (#466). Facebook sizes a row to the room left below the top bars, so a reel that {@link
     * #pagesTop} runs under them ended that much short of the page's bottom, and the next reel
     * showed in the gap. Facebook works the height out once per list and hands every row that, and
     * it makes the list ahead, often while Home shows and before the Reels page is laid out, then
     * keeps the rows it made then. The same short row shows with the tabs at the bottom, where Hide
     * the status bar and see-through bars let the list run taller than the room Facebook counted
     * (#494). Each row is a whole page of that list, so the row gets, in order: the Reels list's own
     * height ({@link #reelsListHeight}) once it's laid out taller
     * than the row, as Facebook itself reads it, wherever the bars sit; the height last seen that
     * way for a row of this height under the same bar switches, kept across starts for the rows
     * made before the list is laid out; where the Reels tab runs its reel
     * under the bars once it shows, with the tabs at the top, the padding Facebook handed the list
     * for the tab bar ({@link #tabPadding}), or on the Reels tab, the padding {@link #pagesTop}
     * dropped. Anywhere else, in a picture in picture window, for a screen over the main one such as
     * a saved video opened from the Reels tab's library ({@link #behindAnotherScreen}), paused,
     * before the settings are ready or on a failure, it's Facebook's own height.
     *
     * <p>A tablet or an unfolded phone that turns Facebook (#531) keeps the list and the height
     * Facebook worked out for it, so a row made after the turn is the list's height whenever the
     * height Facebook hands it is taller than the window, as one worked out upright is in a window
     * turned on its side, and a short one gets the list's height as above. The heights seen are kept
     * for each way up, so the rows made ahead at the next start fit whichever way it's held. Pages
     * made before a turn keep their height: 588 hands every row the height from its list section
     * (a Litho prop), so a page's layout params don't carry it and resizing them changes nothing
     * (S22 as a tablet, 2026-11-22); the rows made after the turn fit.
     *
     * @param height the row height Facebook worked out.
     * @param maker  the list's maker, which holds the tab bar padding.
     */
    public static int reelRow(int height, @Nullable Object maker) {
        try {
            if (height <= 0) return height;
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || Setting.isPaused()) return height;
            return rowFor(height, maker == null ? 0 : tabPadding(maker), reelsListHeight(), windowHeight());
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "Reels row under the top bar", failure);
            return height;
        }
    }

    /** {@link #rowFor(int, int, int, int)} with the window's height unknown. */
    static int rowFor(int height, int tabPad, int list) {
        return rowFor(height, tabPad, list, 0);
    }

    /**
     * {@link #reelRow} once the settings are ready and Hushfacebook isn't paused, with the Reels
     * list's height inside its padding, or 0 when it isn't laid out, and the main screen's window
     * height, or 0 when it isn't known.
     */
    static int rowFor(int height, int tabPad, int list, int window) {
        if (inWindow()) {
            Logger.printDebug(() -> "Top bars: a Reels row of " + height
                    + " in a picture in picture window, left as Facebook made it");
            return height;
        }
        if (behindAnotherScreen()) {
            Logger.printDebug(() -> "Top bars: a Reels row of " + height
                    + " for a screen over the main one, left as Facebook made it");
            return height;
        }
        boolean tabsOnTop = tabsOnTop();
        boolean shown = onReelsTab();
        boolean under = shown ? reelUnderBars(tabsOnTop) : reelsWillRunUnderBars(tabsOnTop);
        int dropped = droppedTop;
        // A list not laid out yet runs down past the bar Hide the tab bar on the Reels tab takes
        // away (#566); a list laid out says how tall it is.
        int freed = list > 0 || tabsOnTop ? 0 : ReelsTabBarHidden.roomFreed();
        Logger.printDebug(() -> "Top bars: a Reels row of " + height + (shown ? " on the Reels tab" : " made ahead")
                + ", under the bars " + under + ", list " + list + ", tab padding " + tabPad + ", padding dropped " + dropped
                + ", room the hidden tab bar frees " + freed);
        if (list > 0 && window > 0 && height > window) {
            Logger.printDebug(() -> "Top bars: a Reels row of " + height + " is taller than the window's "
                    + window + ", worked out for the screen held the other way, made " + list);
            HookStatus.bound(FAMILY, "Reels row under the top bar");
            HookStatus.counted(FAMILY, REEL_ROW_TURNED);
            return list;
        }
        int setup = barSetup();
        boolean wide = wide();
        IntegerSetting rowMade = wide ? Stored.WIDE_ROW_MADE : Stored.ROW_MADE;
        IntegerSetting listSeen = wide ? Stored.WIDE_LIST_HEIGHT : Stored.LIST_HEIGHT;
        IntegerSetting setupSeen = wide ? Stored.WIDE_ROW_SETUP : Stored.ROW_SETUP;
        int wanted;
        if (list > height) {
            wanted = list;
            if (rowMade.get() != height) rowMade.save(height);
            if (listSeen.get() != list) listSeen.save(list);
            if (setupSeen.get() != setup) setupSeen.save(setup);
        } else if (rowMade.get() == height && listSeen.get() > height
                && (setupSeen.get() == setup || sameListUnderTabBar(setupSeen.get(), setup))) {
            wanted = listSeen.get();
        } else if (under || freed > 0) {
            int grown = height + (!under ? 0 : tabsOnTop && tabPad > 0 ? tabPad : shown ? dropped : 0) + freed;
            if (under && list <= 0 && window > 0 && ReelsTabBarHidden.on() && !Settings.KEEP_TOP_BAR.get()) {
                // The list a reel under the bars runs in once the tab bar is hidden on Reels: the
                // window down to the navigation bar its page keeps. With no list seen for this
                // setup yet, as on the first start after the switch goes on, Facebook's height
                // stopped short of it by the tab bar it was worked out for (#566).
                grown = Math.max(grown, window - ReelsTabBarHidden.navigationStrip());
            }
            wanted = grown;
        } else {
            return height;
        }
        if (wanted <= height) return height;
        HookStatus.bound(FAMILY, "Reels row under the top bar");
        HookStatus.counted(FAMILY, REEL_ROW_FILLED);
        Logger.printDebug(() -> "Top bars: a Reels row of " + height + " made " + wanted);
        return wanted;
    }

    /**
     * Whether the main screen shows in a picture in picture window. Facebook makes the Reels rows
     * again for the window's height there while the list still reads its full screen height, so a
     * row grown to it ran the reel mostly outside the window and Facebook let its video go (#495).
     * It also kept the window's row as the one seen, so the next start's rows came out short.
     */
    private static boolean inWindow() {
        Activity screen = mainScreen.get();
        return screen != null && screen.isInPictureInPictureMode();
    }

    /**
     * The top padding for the tab bar that Facebook handed the Reels list's maker, which it takes off
     * each row. The patch fills this in with a read of the maker's field; until then it's 0.
     */
    public static int tabPadding(Object maker) {
        return 0;
    }

    /**
     * The view id of the Reels list, the one Facebook reads the list's height from as it works the
     * row height out. The patch fills this in; until then it's 0.
     */
    public static int reelsListId() {
        return 0;
    }

    /** The Reels list last found, held weakly, so each row doesn't search the main screen again. */
    static volatile WeakReference<View> reelsList = new WeakReference<>(null);

    /** The Reels list last found while it's shown, or null. */
    @Nullable
    static View reelsListShown() {
        View list = reelsList.get();
        return list != null && list.isAttachedToWindow() && list.isShown() ? list : null;
    }

    /**
     * The Reels list's height inside its padding, or 0 when there's no main screen, no list id, no
     * list, a list not laid out or a main screen out of sight behind another screen, which may be
     * making rows of its own. See-through bars and Rounded tab bar run the Reels page down under
     * the tab bar, and a row that filled all of it put the reel's name, caption and buttons behind
     * the bar (#503). The row fills it still, so the video runs under the bar and the gesture bar
     * (#466), and {@link ReelDetailsLift} holds the name, caption and buttons above the bar. Where
     * the strip behind the status bar closes over the Reels tab ({@link ReelsStatusBar}), the list
     * runs that much taller there, so a row made while the strip is open, on Home most of all, gets
     * that room as well and the next reel doesn't show under the see-through tab bar.
     * Facebook makes rows off the main thread too and reads the list there itself, so a search that
     * meets the view tree mid-change answers 0 for that row.
     */
    static int reelsListHeight() {
        View list = reelsList.get();
        if (list == null || !list.isAttachedToWindow()) {
            int id = reelsListId();
            Activity screen = mainScreen.get();
            if (id == 0 || screen == null) return 0;
            try {
                list = screen.findViewById(id);
            } catch (RuntimeException midChange) {
                Logger.printDebug(() -> "Top bars: the Reels list couldn't be read: " + midChange);
                return 0;
            }
            if (list == null) return 0;
            reelsList = new WeakReference<>(list);
        }
        if (list.getWindowVisibility() != View.VISIBLE) return 0;
        int height = list.getHeight() - list.getPaddingTop() - list.getPaddingBottom();
        return height <= 0 ? 0 : height + ReelsStatusBar.roomAbove(list);
    }

    /** The main screen's window height, or 0 when there's no main screen or it isn't laid out. */
    static int windowHeight() {
        Activity screen = mainScreen.get();
        Window window = screen == null ? null : screen.getWindow();
        View decor = window == null ? null : window.peekDecorView();
        return decor == null ? 0 : decor.getHeight();
    }

    /** Whether the main screen is held on its side, so the row heights seen that way are the ones to use. */
    static boolean wide() {
        Activity screen = mainScreen.get();
        return screen != null
                && screen.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    /** The {@link #barSetup} bits that only say whether the tab bar slides away or stays on screen. */
    static final int TAB_BAR_SHOWING = (1 << 2) | (1 << 4);

    /**
     * Whether a list height seen under {@code seen} fits rows made under {@code setup}: the two
     * differ only in whether the tab bar slides away or stays, and pages run under the tab bar
     * ({@link TranslucentBars#stretchesPages}), so the Reels list is the whole page either way.
     * Choosing Stays on screen used to leave the rows made ahead at the next start short, with the
     * next reel showing under the bar, until a start after the list was seen again (#552).
     */
    static boolean sameListUnderTabBar(int seen, int setup) {
        return seen >= 0 && ((seen ^ setup) & ~TAB_BAR_SHOWING) == 0 && TranslucentBars.stretchesPages();
    }

    /**
     * Set in every {@link #barSetup}, so a list height kept before the setup counted Rounded tab bar
     * and Hide the tab bar on the Reels tab matches none, and is seen again once (#551).
     */
    static final int COUNTS_REELS_BAR_SWITCHES = 1 << 10;

    /**
     * The bar switches that change how tall the Reels list runs while Facebook's row stays the same,
     * as one number, so a list height seen under one setup isn't handed to rows under another.
     * Rounded tab bar and Hide the tab bar on the Reels tab both run the list down to the screen's
     * bottom. Left out, the height seen with either on stayed the setup's after it was turned off,
     * so every start grew Facebook's rows by the tab bar's height and the reel's name, caption and
     * buttons sat behind the bar (#551; emulator 2026-11-24: rows of 2062 made 2272 in a 2062 list).
     */
    static int barSetup() {
        int bits = COUNTS_REELS_BAR_SWITCHES;
        if (Settings.TOP_TAB_BAR.get()) bits |= 1;
        if (Settings.BOTTOM_TAB_BAR.get()) bits |= 1 << 1;
        if (Settings.TAB_BAR_SCROLL_AWAY.get()) bits |= 1 << 2;
        if (Settings.KEEP_TOP_BAR.get()) bits |= 1 << 3;
        if (Settings.KEEP_TAB_BAR.get()) bits |= 1 << 4;
        if (Settings.TRANSLUCENT_BARS.get()) bits |= 1 << 5;
        if (Settings.HIDE_STATUS_BAR.get()) bits |= 1 << 6;
        // The Reels list runs up under the status bar too (#466), so a height seen before doesn't fit.
        if (ReelsStatusBar.wanted()) bits |= 1 << 7;
        if (Settings.ROUNDED_TAB_BAR.get()) bits |= 1 << 8;
        if (Settings.HIDE_REELS_TAB_BAR.get()) bits |= 1 << 9;
        return bits;
    }

    /**
     * The row height Facebook last worked out for the Reels list, that list's height, seen on a laid
     * out list, and the {@link #barSetup} it was seen under. Hushfacebook's own state: kept while
     * paused and never in a settings file. A class of its own, so nothing here loads a setting
     * before the context is set. The list's height is back under the key 0.23.0 kept it in, since
     * rows fill the list again (#466); the short room main kept for a while under another key isn't
     * read.
     */
    static final class Stored {
        static final IntegerSetting ROW_MADE = new IntegerSetting("hushfacebook_reels_row_made", 0, false, false);
        static final IntegerSetting LIST_HEIGHT = new IntegerSetting("hushfacebook_reels_list_height", 0, false, false);
        static final IntegerSetting ROW_SETUP = new IntegerSetting("hushfacebook_reels_row_setup", -1, false, false);
        /** The same three seen with the screen on its side ({@link #wide}), on a tablet or an unfolded phone (#531). */
        static final IntegerSetting WIDE_ROW_MADE = new IntegerSetting("hushfacebook_reels_wide_row_made", 0, false, false);
        static final IntegerSetting WIDE_LIST_HEIGHT = new IntegerSetting("hushfacebook_reels_wide_list_height", 0, false, false);
        static final IntegerSetting WIDE_ROW_SETUP = new IntegerSetting("hushfacebook_reels_wide_row_setup", -1, false, false);
        /**
         * The room the tab bar takes above the phone's navigation bar, held upright and on its side,
         * last read off a laid out bar ({@link ReelsTabBarHidden#roomFreed}). Facebook makes the first
         * Reels rows as it starts, before its bar is laid out (#566).
         */
        static final IntegerSetting BAR_ROOM = new IntegerSetting("hushfacebook_reels_bar_room", 0, false, false);
        static final IntegerSetting WIDE_BAR_ROOM = new IntegerSetting("hushfacebook_reels_wide_bar_room", 0, false, false);

        static {
            Setting.keepWhenPaused(ROW_MADE, LIST_HEIGHT, ROW_SETUP, WIDE_ROW_MADE, WIDE_LIST_HEIGHT, WIDE_ROW_SETUP,
                    BAR_ROOM, WIDE_BAR_ROOM);
        }

        private Stored() {
        }
    }

    /** Whether the tabs sit at the top: in the top bar's entry, or moved there by the switch. */
    static boolean tabsOnTop() {
        return tabsInTopEntry || (Settings.TOP_TAB_BAR.get() && !Settings.BOTTOM_TAB_BAR.get());
    }

    /** Whether the main screen's Reels tab runs its reel under the top bars, as {@link #pagesTop} decides. */
    private static boolean reelUnderBars(boolean tabsOnTop) {
        if (offTheFeed && !tabsOnTop) return false;
        if (!offTheFeed && Settings.KEEP_TOP_BAR.get()) return false;
        return onReelsTab();
    }

    /**
     * Whether the Reels tab will run its reel under the top bars once it shows, for a list made while
     * another tab shows, as {@link #reelUnderBars} will decide there. Facebook keeps scroll-away on
     * for the first tab alone, so Reels further along the bar runs off the feed, under the bars only
     * with the tabs at the top. False when the main screen's tabs can't be read or have no Reels.
     */
    private static boolean reelsWillRunUnderBars(boolean tabsOnTop) {
        Activity screen = mainScreen.get();
        List<Object> tabs = screen == null ? null : StartTabRoute.TabBar.tabs(screen);
        if (tabs == null || tabs.isEmpty() || !StartTabRoute.TabBar.has(tabs, StartTab.VIDEO)) return false;
        boolean reelsFirst = StartTab.VIDEO.isTab(tabs.get(0).getClass().getName());
        return reelsFirst ? !Settings.KEEP_TOP_BAR.get() : tabsOnTop;
    }

    /** Whether the main screen shows the tab called Reels or Video. */
    static boolean onReelsTab() {
        Activity screen = mainScreen.get();
        Object tab = screen == null ? null : StartTabRoute.TabBar.currentTab(screen);
        return tab != null && StartTab.VIDEO.isTab(tab.getClass().getName());
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
                slidOnOurSwitch = true;
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

    /** Whether {@link #slidesAway()} answered yes for the switch in this process, so the bar at the bottom slides away. */
    private static volatile boolean slidOnOurSwitch;

    /** Counted each time the bars are brought all the way back as the feed reaches its top. */
    static final String BARS_BACK_AT_TOP = "Bars brought all the way back at the top";
    /** Counted each time Facebook's nudge of the feed after a cold start is skipped. */
    static final String NUDGE_SKIPPED = "Opening nudge of the feed skipped";

    /**
     * A distance that brings any bar all the way back. Facebook takes it off each bar's hidden part
     * and stops at 0, fully shown, so the size only has to beat the tallest bar.
     */
    static final int ALL_THE_WAY_BACK = -(1 << 20);

    /**
     * The hook at the start of the step of Facebook's scroll-away that brings the bars back as the
     * feed scrolls up, with the view the bars scroll with and the distance, below 0. When that view
     * can't scroll up any more, the answer brings every bar all the way back. A short scroll near the
     * top hides the bars, and Facebook then snaps them all fully out of sight, the bottom one by its
     * whole height, while the feed itself moved less. Scrolling back to the top then gave back only
     * as much as the feed moved, and the bottom tabs stopped short, over the gesture handle. Facebook's
     * own snap back works by scrolling the feed up, which can't happen at the top. Any other distance,
     * paused, before the settings are ready or on a failure, it's Facebook's own.
     *
     * <p>The view comes in as an Object. When Facebook has no manager the register still holds that
     * null manager, and ART's verifier merges the two paths to Object, so a View parameter had it
     * reject the whole class and Facebook didn't start (S22, 588, 2026-11-22). Only a list counts,
     * as Facebook checks before it treats the view as one: a view that can't scroll at all would
     * read as always at its top and pop the bars back on every scroll up.
     */
    public static int shownAtTop(@Nullable Object content, int distance) {
        try {
            if (distance >= 0 || !(content instanceof View)) return distance;
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || HushfacebookPause.isPaused()) return distance;
            View list = (View) content;
            if (!TranslucentBars.isList(list) || list.canScrollVertically(-1)) return distance;
            HookStatus.bound(FAMILY, "bars back at the top");
            HookStatus.counted(FAMILY, BARS_BACK_AT_TOP);
            Logger.printDebug(() -> "Top bars: the feed is at its top, every bar comes all the way back");
            return ALL_THE_WAY_BACK;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "bars back at the top", failure);
            return distance;
        }
    }

    /**
     * The hook at the start of Facebook's nudge of the feed a few seconds after a cold start, which
     * scrolls Home down a little on its own. True skips it. With the bar at the bottom set to slide
     * away, that scroll took the top bar and the tab bar off screen before anything was touched, so
     * Home opened scrolled with no tabs. Only while Hide the tab bar while scrolling slides the bar in
     * this process; otherwise, paused, before the settings are ready or on a failure, the nudge runs.
     */
    public static boolean skipsOpeningNudge() {
        try {
            HookStatus.invoked(FAMILY);
            if (!slidOnOurSwitch || !Utils.settingsReady() || !Settings.TAB_BAR_SCROLL_AWAY.get()) return false;
            HookStatus.bound(FAMILY, "opening nudge");
            HookStatus.counted(FAMILY, NUDGE_SKIPPED);
            Logger.printDebug(() -> "Top bars: Facebook's opening nudge of the feed skipped, the tab bar slides away");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "opening nudge", failure);
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
            noteTopEntry(entry);
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
            noteTopEntry(entry);
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
                        pager.getRootView().getHeight(), RoundedTabBar.navigationBottom(insets));
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
