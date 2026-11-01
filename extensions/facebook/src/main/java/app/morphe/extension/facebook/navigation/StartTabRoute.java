/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicIntegerArray;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsEntry;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What Open on a chosen tab does when Facebook's main screen is created: it asks Facebook to open
 * on the tab chosen in the settings, the same way Facebook's own tab shortcuts and notifications
 * ask for one.
 *
 * <p>Facebook picks the tab its main screen opens on from the intent that started it. A shortcut or
 * a notification meant for a tab carries the tab's id as {@link FacebookTabs#TARGET_TAB_ID}, and
 * Facebook opens that tab when its tab bar has it and its first tab, Home, when it doesn't. A
 * start from the launcher icon carries nothing, so Facebook opens where it chooses, usually Home.
 * The patch calls {@link #onActivityCreate} first thing in every Facebook activity's
 * {@code onCreate}. When that's the main screen started from the launcher icon, the screen gets a
 * copy of its intent with the chosen tab's id in it, before any of Facebook's own start code reads
 * it. The intent it was started with isn't touched, since Facebook's start-up prediction can be
 * reading it on another thread; all that prediction decides is what to fetch early. While
 * {@link MarketplaceOnly} is on, the tab is Marketplace (or Events, for Events only), whatever the
 * chosen tab and its switch, and
 * a chosen Reels tab that {@link ReelsTab} keeps off the bar, or a tab {@link HiddenTabs} keeps off it,
 * is asked for as Home.
 *
 * <p>Feeds has a page of its own, the one the Menu's Feeds row opens when the tab bar has no Feeds
 * tab. So a start that chose Feeds, on a bar without it, opens that page over Home once Facebook
 * has built the main screen, on the screen Facebook keeps for pages that aren't tabs, the way the
 * Menu's Feeds row starts it, and Back comes back to Home. It's skipped when the screen has moved on
 * from Home or isn't in front by then. The chosen filter is asked for there too, through
 * {@link FeedsSubtabRoute}. Facebook's own links for the page can't be used for it: on 582 its link
 * map hands both {@code fb://feeds} and {@code fb://recent_feed} Home's launch link, so Facebook
 * switches to Home instead of opening the page.
 *
 * <p>On a cold start Facebook's own start-up would still drop the request twice. It replaces the
 * intent of a start another app sent, a launcher included, with a copy that keeps no tab, and its
 * tab bar only uses the tab a start asked for when one of Facebook's server-side settings says so.
 * Three hooks in that start-up ask this class while the screen it asked a tab for is being built:
 * {@link #setSanitizedIntent} puts the tab back in Facebook's copy, and {@link #startOnAskedTab} and
 * {@link #keepAskedStartTab} answer yes where Facebook asks whether to use it. Outside that window
 * they pass Facebook's own intent and answers through. On a cold start Facebook builds the screen
 * behind a splash, after Android has already created and resumed it, so the window lasts until the
 * screen is built rather than until it first shows.
 *
 * <p>Everything else keeps its own destination: a link, a notification, a shortcut or anything else
 * that carries one of Facebook's routes, and a screen restored after Android put it away. Nothing
 * here acts once Facebook has built the main screen, so tapping another tab, leaving Facebook and
 * coming back all stay as they were. It fails open: with the switch off, Hushfacebook paused, the
 * settings not ready yet, or a failure in here, the start is Facebook's own.
 *
 * <p>With Debug logging on it logs the start it saw and the tab it asked for, one line per start
 * from each start-up hook, and a moment after the screen first shows and is built, the tab
 * Facebook opened and the tabs in its tab bar, read through names Facebook keeps. A tab this
 * account's tab bar hasn't got is logged too, as Facebook opening Home.
 */
public final class StartTabRoute {
    /**
     * The extras Facebook's main screen routes a start by: a tab, a screen to open inside one, a
     * link, or a fragment. A start carrying any of them already has somewhere to go.
     */
    static final String[] FACEBOOK_ROUTES = {
            FacebookTabs.TARGET_TAB_ID, "tabbar_target_intent", "extra_launch_uri", "target_fragment", "fragment_type",
    };

    /** Marks a start this asked a tab for, with the tab's file value. Facebook reads no such extra. */
    static final String ROUTED = "app.morphe.extension.facebook.START_TAB";

    /** How long after the screen first shows the tab it opened on is read. */
    static final long LANDING_CHECK_MS = 2000;

    /** The name the log lines go under, which is also their logcat tag after Morphe's prefix. */
    static final String SOURCE = "StartTabRoute";

    /** What every line of this hook starts with, for a person reading the log. */
    static final String PREFIX = "Start tab: ";

    /** How many more times the landing check waits for a main screen Facebook hasn't built yet. */
    static final int LANDING_ATTEMPTS = 6;

    /**
     * Facebook's link for its Feeds page, which the page's intent carries as its data. The intent
     * names {@link #PAGE_SCREEN} itself, so Facebook's link map never reads it. The source names the
     * way in for Facebook's logging; the Menu's Feeds row is a bookmark.
     */
    static final String FEEDS_PAGE_LINK = "fb://feeds?source=bookmark";

    /** Facebook's screen for a page that isn't a tab, the one the Menu's Feeds row opens the page on. */
    static final String PAGE_SCREEN = "com.facebook.katana.immersiveactivity.ImmersiveActivity";

    /** The fragment Facebook's link map gives the Feeds page ({@code fb://recent_feed}) on that screen. */
    static final int FEEDS_FRAGMENT = 1055;

    /**
     * The launch link the Menu's Feeds row hands the page. No tab answers to it, so Facebook's
     * check for a tab that owns the page finds none and leaves the page open.
     */
    static final String FEEDS_LAUNCH_LINK = "fb://recent_feed?source=tab";

    /** The start-up hooks, by the index their log lines are counted under. */
    static final int SANITIZE_HOOK = 0, POSITION_HOOK = 1, KEEP_HOOK = 2;

    /** What each start-up hook's log line starts with. */
    private static final String[] HOOK_NAMES = {"sanitize hook", "tab bar start position hook", "main screen start tab hook"};

    /** A start this asked a tab for: the main screen and the tab. */
    private static final class Routed {
        final WeakReference<Activity> screen;
        final StartTab tab;

        Routed(Activity screen, StartTab tab) {
            this.screen = new WeakReference<>(screen);
            this.tab = tab;
        }
    }

    /**
     * The start this asked a tab for while Facebook builds its main screen: set once the screen's
     * intent asks for the tab, and cleared when the landing check has read the built tab bar, when
     * the screen goes away, and whenever another main screen starts being created. Null the rest of
     * the time. Only the start-up hooks read it, through {@link #pending()}.
     *
     * <p>It doesn't end when the screen first shows. On a cold start Facebook's main screen hands
     * its onCreate to a stand-in that queues the work behind a splash screen until the app is ready,
     * so Android creates and resumes the screen first, and Facebook's own start-up steps, the three
     * hooks included, run from that queue afterwards.
     */
    @Nullable
    private static volatile Routed pending;

    /** How many main screens have started being created; each start-up hook logs one line per one. */
    private static volatile int starts;

    /** The start each start-up hook last logged a line for, by hook. */
    private static final AtomicIntegerArray loggedFor = new AtomicIntegerArray(new int[]{-1, -1, -1});

    /** Thrown by the next start-up hook that reads {@link #pending}, then cleared: how a test reaches the fail-open path. */
    @Nullable
    static volatile RuntimeException failNextStartUpHook;

    private StartTabRoute() {
    }

    /**
     * Injection point, first thing in the {@code onCreate} of every Facebook activity. Acts only on
     * the main screen, and only when it's started from the launcher icon with nothing saved to
     * restore. Never throws.
     */
    public static void onActivityCreate(@Nullable Activity activity, @Nullable Bundle savedState) {
        try {
            if (activity == null || !FacebookTabs.MAIN_TAB_ACTIVITY.equals(activity.getClass().getName())) return;
            TabBarScrollAway.mainScreenCreated(activity);
            // A new main screen: whatever an earlier one asked for isn't this one's.
            starts++;
            settled();
            FeedsSubtabRoute.disarm();
            HookStatus.invoked(FamilyNames.START_TAB);
            // Settings first: before the context is set, reading a switch would break Facebook's start.
            if (!Utils.settingsReady()) return;
            // Marketplace only opens on Marketplace whatever tab is chosen, and whether or not the
            // chosen tab's own switch is on.
            StartTab only = MarketplaceOnly.keptTab();
            if (only == null && !Settings.OPEN_ON_CHOSEN_TAB.get()) return;
            StartTab chosen = only != null ? only : Settings.START_TAB.get();
            // A Reels tab Hide the Reels tab keeps off the bar isn't there to open, nor one Hide tabs keeps off.
            String keptOff = chosen == StartTab.VIDEO && ReelsTab.offTheBar() ? FamilyNames.REELS_TAB
                    : HiddenTabs.offTheBar(chosen) ? FamilyNames.HIDDEN_TABS : null;
            StartTab tab = keptOff != null ? StartTab.HOME : chosen;
            Intent intent = activity.getIntent();
            String leftAlone = whyLeftAlone(intent, savedState);
            if (leftAlone != null) {
                debug(() -> "left Facebook's own start alone: " + leftAlone + ". " + describe(intent));
                return;
            }
            activity.setIntent(routed(intent, tab));
            if (tab != chosen) {
                debug(() -> keptOff + " keeps " + chosen.fileValue + " off the tab bar, so asked for "
                        + tab.fileValue + " instead.");
            }
            debug(() -> "asked Facebook to open on " + tab.fileValue + " (tab " + tab.tabId + "). " + describe(intent));
            // Only once something will clear it again, when the screen is built or goes away.
            Landing.watch(activity, tab, chosen);
            pending = new Routed(activity, tab);
            // Feeds kept off the bar still opens, as a page of its own, so its filter is asked for too.
            if (chosen == StartTab.FEEDS) {
                FeedsSubtab subtab = Settings.FEEDS_SUBTAB.get();
                FeedsSubtabRoute.arm(activity, subtab);
                if (subtab != FeedsSubtab.ALL) debug(() -> "asked the Feeds tab for " + subtab.fileValue + ".");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.START_TAB, "start tab", failure);
        }
    }

    /** Forgets the start being built. Nothing asks Facebook for a tab after this until the next one. */
    static void settled() {
        pending = null;
    }

    /** Forgets the start being built when it's [screen]'s, and leaves a later main screen's alone. */
    static void settled(@Nullable Activity screen) {
        Routed routed = pending;
        if (routed != null && screen != null && routed.screen.get() == screen) pending = null;
    }

    /**
     * The start the main screen being built asked a tab for, or null: while that screen is there,
     * not destroyed, and its intent still asks for the tab. Decided from the screen itself each
     * time, since Facebook can build it well after Android created and resumed it.
     */
    @Nullable
    private static Routed pending() {
        RuntimeException failure = failNextStartUpHook;
        if (failure != null) {
            failNextStartUpHook = null;
            throw failure;
        }
        Routed routed = pending;
        if (routed == null) return null;
        Activity screen = routed.screen.get();
        if (screen == null || screen.isDestroyed()) return null;
        Intent intent = screen.getIntent();
        return intent != null && routed.tab.fileValue.equals(intent.getStringExtra(ROUTED)) ? routed : null;
    }

    /** Logs [message] under [hook]'s name, once per main screen start. */
    private static void once(int hook, Logger.LogMessage message) {
        int start = starts;
        if (loggedFor.getAndSet(hook, start) != start) {
            debug(() -> HOOK_NAMES[hook] + ": " + message.buildMessageString());
        }
    }

    /**
     * Injection point in place of the {@code Activity.setIntent} of Facebook's start-up step that
     * sanitizes the main screen's intent. For a start another app sent, which a launcher is, the step
     * builds a new intent that keeps only the action, the link and three extras, so the tab this
     * asked for goes with everything else. While the screen it asked a tab for is being built, the
     * copy gets the tab back. Every other start gets Facebook's copy as it is. The screen always gets
     * an intent, and this never throws where Facebook's own call wouldn't.
     */
    public static void setSanitizedIntent(Activity activity, Intent sanitized) {
        Intent intent = sanitized;
        try {
            Routed routed = pending();
            if (routed == null || routed.screen.get() != activity) {
                once(SANITIZE_HOOK, () -> "nothing pending.");
            } else if (sanitized == null || sanitized.hasExtra(FacebookTabs.TARGET_TAB_ID)) {
                once(SANITIZE_HOOK, () -> "Facebook's copy already asks for a tab; left it as it is.");
            } else {
                StartTab tab = routed.tab;
                intent = routed(sanitized, tab);
                HookStatus.bound(FamilyNames.START_TAB, "sanitized start intent");
                once(SANITIZE_HOOK, () -> "Facebook's start-up kept no tab in the screen's intent; asked again for "
                        + tab.fileValue + ".");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.START_TAB, "start tab intent", failure);
            intent = sanitized;
        }
        activity.setIntent(intent);
    }

    /**
     * Injection point after the tab bar reads the server-side setting that decides whether it opens
     * on the tab its start asked for. [original] is that setting. On an account where it's off the
     * tab bar opens on its first tab whatever the start asked for. While the screen this asked a tab
     * for is being built, the answer is yes, and Facebook goes on to its own lookup of the tab in the
     * tab bar, which still falls back to the first tab when the bar hasn't got it. Never throws.
     */
    public static boolean startOnAskedTab(boolean original) {
        return answer(original, POSITION_HOOK, "tab bar start position");
    }

    /**
     * Injection point at the return of the main screen's check that it keeps the start tab its
     * intent asked for as the tab it started on. Answered like {@link #startOnAskedTab}, so the
     * screen's idea of where it started matches the tab its tab bar shows. Never throws.
     */
    public static boolean keepAskedStartTab(boolean original) {
        return answer(original, KEEP_HOOK, "main screen start tab");
    }

    private static boolean answer(boolean original, int hook, String gate) {
        try {
            Routed routed = pending();
            if (routed == null) {
                once(hook, () -> "nothing pending; Facebook's " + (original ? "yes" : "no") + " stands.");
                return original;
            }
            HookStatus.bound(FamilyNames.START_TAB, gate);
            String tab = routed.tab.fileValue;
            once(hook, () -> original ? "Facebook already said yes for " + tab + "."
                    : "Facebook said no; asked it to use " + tab + ".");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.START_TAB, gate, failure);
            return original;
        }
    }

    /**
     * Why a start of the main screen keeps Facebook's own destination, or null when it's a plain
     * start from the launcher icon, the only kind this changes.
     */
    @Nullable
    static String whyLeftAlone(@Nullable Intent intent, @Nullable Bundle savedState) {
        if (savedState != null) return "the screen is being restored";
        if (intent == null) return "the screen has no intent";
        if (!Intent.ACTION_MAIN.equals(intent.getAction()) || !intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            return "it isn't a start from the launcher icon";
        }
        if (intent.getData() != null) return "it opens a link";
        for (String route : FACEBOOK_ROUTES) {
            if (intent.hasExtra(route)) return "Facebook routes it by " + route;
        }
        return null;
    }

    /** A copy of [intent] that asks for [tab]. */
    static Intent routed(Intent intent, StartTab tab) {
        return new Intent(intent)
                .putExtra(FacebookTabs.TARGET_TAB_ID, tab.tabId)
                .putExtra(ROUTED, tab.fileValue);
    }

    /**
     * A start's intent for the log: its action, categories and flags, whether it carries a link, and
     * the names of its extras. Never the link or a value, which can name a person or a post.
     */
    static String describe(@Nullable Intent intent) {
        if (intent == null) return "No intent.";
        StringBuilder text = new StringBuilder("Intent: action ").append(intent.getAction());
        Set<String> categories = intent.getCategories();
        text.append(", categories ").append(categories == null || categories.isEmpty()
                ? "none" : String.join(" ", new TreeSet<>(categories)));
        text.append(", flags 0x").append(Integer.toHexString(intent.getFlags()));
        text.append(intent.getData() == null ? ", no link" : ", a link");
        Bundle extras = intent.getExtras();
        text.append(", extras ").append(extras == null || extras.isEmpty()
                ? "none" : String.join(" ", new TreeSet<>(extras.keySet())));
        return text.append('.').toString();
    }

    private static void debug(Logger.LogMessage message) {
        Logger.diagnosticDebug(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE,
                () -> PREFIX + message.buildMessageString());
    }

    /**
     * Reads which tab the main screen opened on, once, a moment after it first shows and Facebook
     * has built it, which ends the start being built; and ends it too when the screen goes away. What
     * it reads goes to the log and Hook status. The one thing it changes on screen is the Feeds page
     * a start that chose Feeds gets when the bar has no Feeds tab.
     */
    static final class Landing implements Application.ActivityLifecycleCallbacks {
        /** The tab the start asked Facebook for. */
        final StartTab asked;
        /** The tab chosen in the settings, which a switch can keep off the bar. */
        final StartTab chosen;
        private boolean shown;
        /** Whether the screen is resumed, so in front of the person. */
        volatile boolean inFront;

        private Landing(StartTab asked, StartTab chosen) {
            this.asked = asked;
            this.chosen = chosen;
        }

        static void watch(Activity activity, StartTab asked, StartTab chosen) {
            activity.registerActivityLifecycleCallbacks(new Landing(asked, chosen));
        }

        @Override
        public void onActivityPostResumed(@NonNull Activity activity) {
            inFront = true;
            if (shown) return;
            shown = true;
            WeakReference<Activity> screen = new WeakReference<>(activity);
            Utils.runOnMainThreadDelayed(() -> land(screen, this, LANDING_ATTEMPTS), LANDING_CHECK_MS);
        }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {
            activity.unregisterActivityLifecycleCallbacks(this);
            settled(activity);
        }

        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedState) {
        }

        @Override
        public void onActivityStarted(@NonNull Activity activity) {
        }

        @Override
        public void onActivityResumed(@NonNull Activity activity) {
        }

        @Override
        public void onActivityPaused(@NonNull Activity activity) {
            inFront = false;
        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
        }
    }

    /**
     * The landing check, once Facebook has built the main screen. Behind a splash screen Facebook can
     * build it well after it first shows, and until then the screen has no current tab to read, so
     * it's tried again [attempts] times at most before the check goes ahead. The start being built
     * ends there either way. A screen with a tab to read has had its start decided. One still
     * without a tab after the last try is given up on, so a tab bar Facebook builds later, or builds
     * again when it reloads its tabs, gets Facebook's own answers rather than the asked tab. A built
     * screen then gets the Feeds page when its start chose Feeds and the bar has none.
     */
    static void land(WeakReference<Activity> screen, Landing landing, int attempts) {
        Activity activity = screen.get();
        boolean built = activity != null && TabBar.currentTab(activity) != null;
        boolean live = activity != null && !activity.isFinishing() && !activity.isDestroyed();
        if (!built && attempts > 1 && live) {
            Utils.runOnMainThreadDelayed(() -> land(screen, landing, attempts - 1), LANDING_CHECK_MS);
            return;
        }
        if (!built && live) {
            debug(() -> "gave up waiting for the main screen to be built after " + LANDING_ATTEMPTS
                    + " tries; Facebook's start-up keeps its own answers from here on.");
        }
        // Only this screen's start: a later main screen's is its own to end.
        if (activity != null) settled(activity);
        check(activity, landing.asked);
        if (built) openFeedsPage(activity, landing.asked, landing.chosen, landing.inFront);
    }

    /**
     * For a start that chose Feeds, once the main screen is built: when its tab bar has no Feeds tab,
     * opens Facebook's Feeds page on its own screen over Home, the way the Menu's Feeds row does,
     * so Back comes back to Home. A bar that has Feeds, or can't be read while Facebook was asked
     * for Feeds, is left alone, and so is a screen that has moved on from Home or isn't in front,
     * since the person has gone somewhere already. Never throws.
     */
    static void openFeedsPage(@Nullable Activity activity, StartTab asked, StartTab chosen, boolean inFront) {
        try {
            if (chosen != StartTab.FEEDS || activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            Object current = TabBar.currentTab(activity);
            if (current == null) return;
            // Asked for Home, a switch keeps Feeds off the bar. Asked for Feeds, the bar has to say it hasn't got it.
            if (asked == StartTab.FEEDS) {
                List<Object> tabs = TabBar.tabs(activity);
                if (tabs == null || TabBar.has(tabs, StartTab.FEEDS)) return;
            }
            if (!inFront || !StartTab.HOME.isTab(current.getClass().getName())) {
                debug(() -> "the tab bar has no feeds tab, but the main screen had already left Home,"
                        + " so the Feeds page stays closed.");
                return;
            }
            // The restart row lands back on the settings screen, which shows over this one.
            if (SettingsEntry.upOrComing(activity)) {
                debug(() -> "the settings screen is up, so the Feeds page stays closed.");
                return;
            }
            // The Menu row's extras, so the screen shows the page instead of switching to Home.
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(FEEDS_PAGE_LINK))
                    .setPackage(activity.getPackageName())
                    .setClassName(activity.getPackageName(), PAGE_SCREEN)
                    .putExtra("target_fragment", FEEDS_FRAGMENT)
                    .putExtra("feed_type", "most_recent")
                    .putExtra("extra_launch_uri", FEEDS_LAUNCH_LINK)
                    .putExtra("presentation_type", "BOOKMARK")
                    .putExtra("should_show_nav_bar", true));
            HookStatus.bound(FamilyNames.START_TAB, "feeds page");
            debug(() -> "the tab bar has no feeds tab, so opened the Feeds page over Home.");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.START_TAB, "feeds page", failure);
        }
    }

    /**
     * Compares the tab the main screen shows with the one it was asked for. The same tab is the
     * route working. A tab bar without the asked tab is the account's, not a fault: Facebook
     * opened Home, as it does for a notification about such a tab. Another tab while the bar has
     * the asked one is a route Facebook didn't take, or a tap in those two seconds. A screen that
     * shows no tab has no tab bar to read yet, and the check says nothing about one.
     */
    static void check(@Nullable Activity activity, StartTab asked) {
        try {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            Object current = TabBar.currentTab(activity);
            if (current == null) {
                HookStatus.missingMember(FamilyNames.START_TAB, "start on tab", FacebookTabs.MAIN_TAB_ACTIVITY,
                        asked.fileValue);
                debug(() -> "asked for " + asked.fileValue + ", and the main screen shows no tab yet.");
                return;
            }
            List<Object> tabs = TabBar.tabs(activity);
            String opened = TabBar.name(current);
            String bar = TabBar.describe(tabs);
            if (asked.isTab(current.getClass().getName())) {
                HookStatus.bound(FamilyNames.START_TAB, asked.fileValue + " tab");
                debug(() -> "Facebook opened " + opened + ", as asked. Tab bar: " + bar);
            } else if (tabs != null && !TabBar.has(tabs, asked)) {
                Logger.diagnosticInfo(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE, () -> PREFIX + "this tab bar has no "
                        + asked.fileValue + " tab, so Facebook opened " + opened + ". Tab bar: " + bar);
            } else {
                HookStatus.missingMember(FamilyNames.START_TAB, "start on tab", FacebookTabs.MAIN_TAB_ACTIVITY,
                        asked.fileValue);
                debug(() -> "asked for " + asked.fileValue + ", Facebook opened " + opened + ". Tab bar: " + bar);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.START_TAB, "start tab landing", failure);
        }
    }

    /** The class name of the tab the main screen [activity] shows, or null. Never throws. */
    @Nullable
    public static String shownTab(Activity activity) {
        try {
            Object tab = TabBar.currentTab(activity);
            return tab == null ? null : tab.getClass().getName();
        } catch (Throwable unreadable) {
            return null;
        }
    }

    /** Whether [activity] is the Feeds page the Menu's Feeds row opens. Never throws. */
    public static boolean feedsPage(Activity activity) {
        try {
            Intent intent = activity.getIntent();
            return PAGE_SCREEN.equals(activity.getClass().getName()) && intent != null
                    && intent.getIntExtra("target_fragment", -1) == FEEDS_FRAGMENT;
        } catch (Throwable unreadable) {
            return false;
        }
    }

    /**
     * Facebook's tab bar, read through the names Redex keeps: the main screen's
     * {@code getCurrentTab()}, its delegate's lazy tab bar state, the {@code TabTag} class and its
     * one long, the tab's id. Every read answers null rather than throw.
     *
     * <p>The lazy value resolves the account's one tab bar state. The delegate itself only asks for
     * it when a start carries a tab, a shortcut or a new intent, so after a plain start from the
     * launcher icon the lazy value is unasked, and asking it can build that state. So it's asked only
     * once the main screen shows a tab, when Facebook has built its tab bar from that state, and
     * never ahead of Facebook.
     */
    static final class TabBar {
        private TabBar() {
        }

        /** The tab the main screen shows, or null. */
        @Nullable
        static Object currentTab(Activity activity) {
            Object tab = call(activity, "getCurrentTab");
            return isTab(tab) ? tab : null;
        }

        /**
         * The tabs the tab bar shows, in order: the tab bar state's own list, which leaves out the
         * tabs hidden in Facebook's settings, or else its configuration's. Null when neither can
         * be read, and null without a read while the main screen shows no tab.
         */
        @Nullable
        static List<Object> tabs(Activity activity) {
            if (currentTab(activity) == null) return null;
            Object delegate = mainTabDelegate(activity);
            if (delegate == null) return null;
            Object lazy = field(delegate, FacebookTabs.TAB_BAR_STATE);
            if (lazy == null) return null;
            Object state = call(lazy, "getValue");
            if (state == null) return null;
            List<Object> shown = tabList(state);
            if (shown != null) return shown;
            Object config = fieldOfType(state, FacebookTabs.NAVIGATION_CONFIG);
            return config == null ? null : tabList(config);
        }

        /** Whether [tabs] has a tab that is [tab]. */
        static boolean has(List<Object> tabs, StartTab tab) {
            for (Object each : tabs) {
                if (tab.isTab(each.getClass().getName())) return true;
            }
            return false;
        }

        /** "MarketplaceTab 1606854132932955" for a tab, "no tab" for none. */
        static String name(@Nullable Object tab) {
            if (tab == null) return "no tab";
            long id = tabId(tab);
            return tab.getClass().getSimpleName() + (id == -1 ? "" : " " + id);
        }

        /** Every tab of [tabs] by [name], or "unreadable". */
        static String describe(@Nullable List<Object> tabs) {
            if (tabs == null) return "unreadable";
            List<String> names = new ArrayList<>();
            for (Object tab : tabs) names.add(name(tab));
            return names.isEmpty() ? "empty" : String.join(", ", names);
        }

        /** The tab's id, the one long its TabTag holds, or -1. */
        static long tabId(Object tab) {
            for (Class<?> type = tab.getClass(); type != null; type = type.getSuperclass()) {
                if (!FacebookTabs.TAB_TAG.equals(type.getName())) continue;
                Field id = null;
                for (Field field : type.getDeclaredFields()) {
                    if (field.getType() != long.class || Modifier.isStatic(field.getModifiers())) continue;
                    if (id != null) return -1;
                    id = field;
                }
                if (id == null) return -1;
                try {
                    id.setAccessible(true);
                    return id.getLong(tab);
                } catch (ReflectiveOperationException | RuntimeException unreadable) {
                    return -1;
                }
            }
            return -1;
        }

        /** Whether [value] extends Facebook's TabTag. */
        static boolean isTab(@Nullable Object value) {
            if (value == null) return false;
            for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
                if (FacebookTabs.TAB_TAG.equals(type.getName())) return true;
            }
            return false;
        }

        /**
         * The delegate Facebook's main screen hands its work to. The screen's
         * {@code getFragmentActivityDelegate()} gives it, or a wrapper holding it.
         */
        @Nullable
        static Object mainTabDelegate(Activity activity) {
            Object delegate = call(activity, "getFragmentActivityDelegate");
            if (delegate == null) return null;
            if (FacebookTabs.MAIN_TAB_DELEGATE.equals(delegate.getClass().getName())) return delegate;
            for (Class<?> type = delegate.getClass(); ownClass(type); type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                    Object value = read(field, delegate);
                    if (value != null && FacebookTabs.MAIN_TAB_DELEGATE.equals(value.getClass().getName())) return value;
                }
            }
            return null;
        }

        /** The first list field of [owner] that holds tabs and nothing else, copied, or null. */
        @Nullable
        static List<Object> tabList(Object owner) {
            for (Class<?> type = owner.getClass(); ownClass(type); type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || !List.class.isAssignableFrom(field.getType())) continue;
                    Object value = read(field, owner);
                    if (!(value instanceof List) || ((List<?>) value).isEmpty()) continue;
                    List<Object> tabs = new ArrayList<>((List<?>) value);
                    boolean allTabs = true;
                    for (Object each : tabs) allTabs &= isTab(each);
                    if (allTabs) return tabs;
                }
            }
            return null;
        }

        @Nullable
        private static Object fieldOfType(Object owner, String typeName) {
            for (Class<?> type = owner.getClass(); ownClass(type); type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) && typeName.equals(field.getType().getName())) {
                        return read(field, owner);
                    }
                }
            }
            return null;
        }

        @Nullable
        private static Object field(Object owner, String name) {
            for (Class<?> type = owner.getClass(); ownClass(type); type = type.getSuperclass()) {
                try {
                    return read(type.getDeclaredField(name), owner);
                } catch (NoSuchFieldException | RuntimeException notHere) {
                    // Try the superclass.
                }
            }
            return null;
        }

        /** Calls a public method of [target] that takes nothing, by name, or answers null. */
        @Nullable
        private static Object call(Object target, String name) {
            try {
                Method method = target.getClass().getMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (ReflectiveOperationException | RuntimeException unreadable) {
                return null;
            }
        }

        @Nullable
        private static Object read(Field field, Object owner) {
            try {
                field.setAccessible(true);
                return field.get(owner);
            } catch (ReflectiveOperationException | RuntimeException unreadable) {
                return null;
            }
        }

        /** Facebook's own classes and the app's, not Android's or Java's, whose fields are theirs. */
        private static boolean ownClass(@Nullable Class<?> type) {
            if (type == null || type == Object.class) return false;
            String name = type.getName();
            return !name.startsWith("android.") && !name.startsWith("androidx.") && !name.startsWith("java.")
                    && !name.startsWith("kotlin.");
        }
    }
}
