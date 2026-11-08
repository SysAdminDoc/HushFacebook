/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.os.SystemClock;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;

/**
 * A return after all Facebook UI was hidden, and the one answer every feed refresh check of that
 * return gets.
 *
 * <p>Facebook checks more than once as it comes back: FeedRefreshTriggerController's resume
 * callback, the feed's warm-start check, which NewsFeedFragment's onResume reaches and which
 * refreshes a feed left alone for a few minutes, and NewsFeedFragment's foreground auto-scroll. The
 * first check after the UI was hidden decides, and every check within {@link #SAME_RETURN_MS} of it
 * gets the same answer. The same three run again when the feed comes back from another screen
 * inside the app, a post's own page or its comments on some accounts, and outside a return they
 * keep the feed while the switch is on (#378).
 *
 * <p>Inside the app, a tab switch back to Home or the feed back from another screen reaches four
 * more: the feed's hot-start check, its stale-post executor (from the visibility change, and from
 * the worker it posts on pause), NewsFeedTabDataFetch's AUTO_REFRESH and, on accounts with the
 * friendly feed, the HOT_LOAD NewsFeedFragment's onTabEntered starts once Home has been left long
 * enough. Those keep the feed while the switch is on, and never use a return up
 * ({@link #decideInApp}). The hot-start check keeps it through the warm-start check it asks, past
 * that check's empty-feed load, so an empty feed loads.
 *
 * <p>While Facebook is away it also tears the feed's stories down once it has been gone as long as
 * the warm-start threshold, and a torn-down feed loads new posts on the return whatever the checks
 * say. With the switch on that teardown is skipped: see {@link #keepFeedWhileAway()}.
 */
public final class ReturnRefresh {
    private static final long HOLD_MS = 10 * 60 * 1000L;
    /** How long after the first check of a return a later check still belongs to it. */
    static final long SAME_RETURN_MS = 10_000L;
    /**
     * How long after the hot-start check starts a warm-start check still counts as the one it asks.
     * It asks straight away, on the same call, so this only has to outlast a pause in that call.
     */
    static final long HOT_START_ASKS_MS = 1_000L;
    static final String FEED_RESUME = "feed resume";
    static final String WARM_START = "warm start";
    static final String AUTO_SCROLL = "foreground auto-scroll";
    static final String HOT_START = "hot start";
    static final String STALE_POST = "stale-post refresh";
    static final String TAB_AUTO_REFRESH = "tab auto refresh";
    static final String TAB_ENTRY_HOT_LOAD = "Home tab hot load";
    private static final String KEPT_ON_RESUME = "kept the feed on resume";
    private static final String KEPT_ON_WARM_START = "kept the feed at warm start";
    private static final String KEPT_ON_AUTO_SCROLL = "kept the feed from the foreground auto-scroll";
    private static final String KEPT_ON_HOT_START = "kept the feed at hot start";
    private static final String KEPT_ON_STALE_POST = "kept the feed from a stale-post refresh";
    private static final String KEPT_ON_TAB_AUTO_REFRESH = "kept the feed from the tab's auto refresh";
    private static final String KEPT_ON_TAB_ENTRY_HOT_LOAD = "kept the feed from the Home tab's hot load";
    private static final String KEPT_WHILE_AWAY = "kept the feed loaded while away";
    /** Prefix for a refusal's count label; one label per fixed reason from refreshBecause()/offBecause(). */
    private static final String LET_FACEBOOK_REFRESH = "let Facebook refresh: ";
    private static long hiddenAt = -1;
    private static long decidedAt = -1;
    /** Why the first check of the current return let Facebook refresh, or null while it's holding. */
    private static String decidedBecause;
    private static boolean registered;
    /** When the hot-start check last started, until the warm-start check it asks takes it. */
    private static long hotStartAt = -1;

    private ReturnRefresh() { }

    public static synchronized void register(Context context) {
        if (registered || !(context instanceof Application)) return;
        ((Application) context).registerComponentCallbacks(new ComponentCallbacks2() {
            @Override public void onTrimMemory(int level) {
                if (level == TRIM_MEMORY_UI_HIDDEN) ReturnRefresh.uiHidden();
            }
            @Override public void onConfigurationChanged(Configuration configuration) { }
            @Override public void onLowMemory() { }
        });
        registered = true;
    }

    public static void uiHidden() {
        uiHidden(SystemClock.elapsedRealtime());
    }

    static void uiHidden(long now) {
        synchronized (ReturnRefresh.class) {
            hiddenAt = now;
            decidedAt = -1;
            decidedBecause = null;
        }
        Logger.printDebug(() -> "Return refresh: Facebook's screens were hidden");
    }

    /**
     * Called only from the feed's resume callback, never from swipe refresh or cold start. Counts
     * each call in the report, so a report can tell a callback that moved, which never calls, from
     * one that ran and let Facebook refresh. A failure lets Facebook refresh, as it would unpatched.
     */
    public static boolean skip() {
        try {
            // The feed is back from a screen over it, so a Back right now closed that screen (#378).
            HomeBack.cameBack();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.RETURN_REFRESH, FEED_RESUME, failure);
            return false;
        }
        return ask(FEED_RESUME, KEPT_ON_RESUME);
    }

    /**
     * Called from the feed's warm-start check once it knows the feed has stories, before it looks
     * at how long the feed sat idle. True skips the check the way Facebook's own skip does, so an
     * empty feed still loads. Asked by the hot-start check inside the app, with no return pending,
     * it gets the hot start's in-app answer, counted as the hot start's.
     */
    public static boolean holdWarmStart() {
        try {
            if (askedByHotStartInApp(SystemClock.elapsedRealtime())) {
                return askInApp(HOT_START, KEPT_ON_HOT_START, false);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.RETURN_REFRESH, WARM_START, failure);
            return false;
        }
        return ask(WARM_START, KEPT_ON_WARM_START);
    }

    /**
     * Called first thing in NewsFeedFragment's foreground auto-scroll decision, which on some
     * accounts moves the feed on a post after a return a few minutes long. True answers Facebook's
     * own "no scroll".
     */
    public static boolean holdAutoScroll() {
        return ask(AUTO_SCROLL, KEPT_ON_AUTO_SCROLL);
    }

    /**
     * Called first thing in the runnable Facebook posts as it leaves for the background, timed to
     * the warm-start threshold, which tears the feed's stories down if Facebook is still away. True
     * skips that teardown while the switch is on, since the return it clears the feed for may be one
     * to keep. How long Facebook will be away isn't known yet, so a return past the ten minutes still
     * gets new posts, through the warm-start check. It leaves the pending return alone.
     */
    public static boolean keepFeedWhileAway() {
        try {
            HookStatus.invoked(FamilyNames.RETURN_REFRESH);
            String off = offBecause();
            Logger.printDebug(() -> "Return refresh: feed teardown while away"
                    + (off == null ? ": skipped, keeping the feed" : ": Facebook tears the feed down, " + off));
            HookStatus.counted(FamilyNames.RETURN_REFRESH, off == null ? KEPT_WHILE_AWAY : LET_FACEBOOK_REFRESH + off);
            return off == null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.RETURN_REFRESH, "feed teardown", failure);
            return false;
        }
    }

    /**
     * Called first thing in the feed's hot-start check, which NewsFeedFragment's onSetUserVisibleHint
     * reaches as the Home tab comes back into view and its refreshForRevisit as the feed comes back
     * from another screen. It holds nothing itself: the check asks the warm-start check next, past
     * that check's empty-feed load, and {@link #holdWarmStart()} answers it for the app.
     */
    public static void hotStart() {
        try {
            HookStatus.invoked(FamilyNames.RETURN_REFRESH);
            long now = SystemClock.elapsedRealtime();
            hotStartAt(now);
            // The feed is back from another screen, so a Back right now closed that screen (#378).
            HomeBack.cameBackAt(now);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.RETURN_REFRESH, HOT_START, failure);
        }
    }

    static synchronized void hotStartAt(long now) {
        hotStartAt = now;
    }

    /**
     * Whether the warm-start check asking at [now] is the one the hot-start check just asked, with no
     * return from the background pending. Takes the hot start either way, so it answers once.
     */
    static synchronized boolean askedByHotStartInApp(long now) {
        boolean asked = hotStartAt >= 0 && now >= hotStartAt && now - hotStartAt <= HOT_START_ASKS_MS;
        hotStartAt = -1;
        if (!asked) return false;
        boolean returning = hiddenAt >= 0 || (decidedAt >= 0 && now >= decidedAt && now - decidedAt <= SAME_RETURN_MS);
        return !returning;
    }

    /**
     * Called first thing where the feed acts on its stale-post decision, a re-rank or a refresh that
     * clears what's on screen. The worker NewsFeedFragment posts as it pauses hands it one, and so
     * does its onSetUserVisibleHint. True does nothing, as Facebook does for a decision of no refresh.
     */
    public static boolean holdStalePost() {
        return askInApp(STALE_POST, KEPT_ON_STALE_POST, true);
    }

    /**
     * Called right before NewsFeedTabDataFetch forces an AUTO_REFRESH, which it does as the Home tab
     * comes back once its data is older than Facebook's limit. True goes straight on to the data
     * fetch's own callback, as Facebook does after the refresh.
     */
    public static boolean holdTabAutoRefresh() {
        return askInApp(TAB_AUTO_REFRESH, KEPT_ON_TAB_AUTO_REFRESH, true);
    }

    /**
     * Called right before NewsFeedFragment's onTabEntered reloads the friendly feed with a HOT_LOAD,
     * which it does as Home comes back once the tab has been left longer than Facebook's limit. The
     * first entry of a launch never gets there, so a fresh launch loads as it always has. True skips
     * the reload and goes on with the rest of the tab entry.
     */
    public static boolean holdTabEntryHotLoad() {
        return askInApp(TAB_ENTRY_HOT_LOAD, KEPT_ON_TAB_ENTRY_HOT_LOAD, true);
    }

    /**
     * Called first thing where Facebook writes down what its reset to feed decided on a return, to
     * log it. It changes nothing: it's there so a debug log says whether that reset ran.
     */
    public static void resetToFeed(String outcome, String destination) {
        try {
            Logger.printDebug(() -> "Return refresh: Facebook's reset to feed: " + outcome + ", to " + destination);
        } catch (Throwable ignored) {
            // A log line is not worth failing Facebook's call over.
        }
    }

    private static boolean ask(String check, String kept) {
        try {
            HookStatus.invoked(FamilyNames.RETURN_REFRESH);
            Decision decision = decide(SystemClock.elapsedRealtime(), check);
            if (decision.countable) {
                HookStatus.counted(FamilyNames.RETURN_REFRESH,
                        decision.hold ? kept : LET_FACEBOOK_REFRESH + decision.reason);
            }
            return decision.hold;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.RETURN_REFRESH, check, failure);
            return false;
        }
    }

    private static boolean askInApp(String check, String kept, boolean duringReturn) {
        try {
            HookStatus.invoked(FamilyNames.RETURN_REFRESH);
            Decision decision = decideInApp(SystemClock.elapsedRealtime(), check, duringReturn);
            if (decision.countable) {
                HookStatus.counted(FamilyNames.RETURN_REFRESH,
                        decision.hold ? kept : LET_FACEBOOK_REFRESH + decision.reason);
            }
            return decision.hold;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.RETURN_REFRESH, check, failure);
            return false;
        }
    }

    static boolean askInAppAt(long now, String check, boolean duringReturn) {
        return decideInApp(now, check, duringReturn).hold;
    }

    /**
     * Decides a check Facebook makes inside the app, a tab switch back to Home or the feed back from
     * another screen: it keeps the feed while the switch is on. A check while a return from the
     * background is pending, or within {@link #SAME_RETURN_MS} of its first check, never uses that
     * return up. With {@code duringReturn} it gets the answer the return gets, and without it Facebook
     * goes on, to a check of the return's own.
     */
    private static Decision decideInApp(long now, String check, boolean duringReturn) {
        final boolean returning;
        final String reason;
        synchronized (ReturnRefresh.class) {
            if (hiddenAt >= 0) {
                returning = true;
                reason = refreshBecause(now, hiddenAt);
            } else if (decidedAt >= 0 && now >= decidedAt && now - decidedAt <= SAME_RETURN_MS) {
                returning = true;
                reason = decidedBecause;
            } else {
                returning = false;
                reason = offBecause();
            }
        }
        if (returning && !duringReturn) {
            Logger.printDebug(() -> "Return refresh: " + check + " in a return: the warm-start check decides");
            return new Decision(false, false, null);
        }
        boolean hold = reason == null;
        Logger.printDebug(() -> "Return refresh: " + check + (returning ? " in a return" : " inside the app")
                + (hold ? ": keeping the feed" : ": Facebook goes ahead, " + reason));
        return new Decision(hold, true, reason);
    }

    static boolean skipAt(long now) {
        return decide(now, FEED_RESUME).hold;
    }

    static boolean askAt(long now, String check) {
        return decide(now, check).hold;
    }

    /**
     * Decides one check. A check outside any tracked return (a resume with nothing hidden, or past
     * {@link #SAME_RETURN_MS} of the last one) is the feed coming back from another screen inside
     * the app, and gets the in-app answer: it keeps the feed while the switch is on, however long
     * that screen was open (#378). It used to go to Facebook, uncounted, and on accounts whose post
     * page or comments leave the feed for another screen, Back from them reloaded it.
     */
    private static Decision decide(long now, String check) {
        final long away;
        final String reason;
        synchronized (ReturnRefresh.class) {
            long at = hiddenAt;
            if (at >= 0) {
                hiddenAt = -1;
                decidedAt = now;
                away = now - at;
                reason = refreshBecause(now, at);
                decidedBecause = reason;
            } else if (decidedAt >= 0 && now >= decidedAt && now - decidedAt <= SAME_RETURN_MS) {
                away = -1;
                reason = decidedBecause;
            } else {
                decidedAt = -1;
                away = -2;
                reason = offBecause();
            }
        }
        boolean hold = reason == null;
        String logReason = away == -1 && reason != null ? "as the first check of this return decided" : reason;
        Logger.printDebug(() -> "Return refresh: " + check
                + (away >= 0 ? " after " + away / 1000 + " s away" : away == -1 ? " in the same return" : " inside the app")
                + (hold ? ": keeping the feed" : ": Facebook goes ahead, " + logReason));
        return new Decision(hold, true, reason);
    }

    /** The outcome of one check: whether to hold, and whether it belongs to a count. */
    private static final class Decision {
        final boolean hold;
        final boolean countable;
        final String reason;

        Decision(boolean hold, boolean countable, String reason) {
            this.hold = hold;
            this.countable = countable;
            this.reason = reason;
        }
    }

    /** Why a return from {@code at} to {@code now} lets Facebook refresh, or null when it keeps the feed. */
    private static String refreshBecause(long now, long at) {
        if (now < at) return "the clock went back";
        String off = offBecause();
        if (off != null) return off;
        if (now - at > HOLD_MS && !Settings.RETURN_REFRESH_NO_LIMIT.get()) return "away over ten minutes";
        return null;
    }

    /** Why the switch doesn't apply now, or null when it's on. Reads no setting before they're ready. */
    private static String offBecause() {
        if (!Utils.settingsReady()) return "settings not ready";
        if (!Settings.BLOCK_RETURN_REFRESH.get()) return Setting.isPaused() ? "Hushfacebook paused" : "switch off";
        return null;
    }
}
