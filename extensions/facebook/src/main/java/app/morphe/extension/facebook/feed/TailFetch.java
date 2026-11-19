/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.ref.WeakReference;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;

/**
 * Keeps Home asking for posts when the feed guard has left it at the end of its list.
 *
 * <p>Home's scroll fetcher asks Facebook's feed loader for the next page only when the post on
 * screen has moved past the one it last asked at. When the feed guard turns away most or all of a
 * page, nothing new reaches the list, so the post on screen never moves on and the fetcher never
 * asks again: Home sits on its loading row however far it's scrolled, while the fetcher still runs
 * on every swipe and says "at the end, don't fetch".
 *
 * <p>So the fetcher's "don't fetch" branch asks here first and makes the ask it would have made when
 * told to. That happens only with the end of the list on screen, after the guard has turned at least
 * one edge away in this process, and at most once every {@link #RETRY_MS}. The loader itself says
 * whether a load is already running or the feed has ended, as it does for its own asks. Paused, or
 * before anything was hidden, the fetcher decides as it ships.
 *
 * <p>With the end of the list on screen it also lets {@link FeedEndHint} say why Home stops there,
 * before the wait between asks, so a page of hidden suggestions landing between two asks still counts.
 *
 * <p>It also asks ahead, while the end is still {@link #AHEAD_ROWS} rows off (#139). Hidden edges
 * never become rows, so a page the guard thinned adds only a few, and the fetcher's own ask comes
 * two posts from the end: the next posts were still loading when the last ones went by, and arrived
 * two or three at a time while Home showed its waiting row. One ask ahead per length of the list is
 * enough, since the loader runs one load at a time and says so, and only new rows make another ask
 * worth it.
 *
 * <p>The fetcher only runs when the list scrolls or its rows on screen change (#493). On a cold
 * start the guard can empty the first pages with the end of the list already on screen, the
 * stories tray, the Reels shelf and the loading row, and then nothing runs it: Home waited for a
 * swipe. So the patch hands this the fetcher and what it was given each time it runs
 * ({@link #scrolled}), and once a burst of hidden edges has settled for {@link #RERUN_MS} with the
 * end of the list on screen at its last run, this runs it again with the same, the way Facebook's
 * own scroll to the top of the feed does. That run reaches the branch above and asks. It waits out
 * {@link #RETRY_MS} after the last ask, and stops after {@link #RERUNS} runs in a row until a run
 * of the fetcher's own, so a feed of nothing but hidden posts doesn't keep loading on its own.
 */
public final class TailFetch {
    /** The diagnostic report's count of the asks this added. */
    static final String ASKED_AGAIN = "Home asked for more posts at the end of the list";

    /** The diagnostic report's count of the asks this made before the end of the list. */
    static final String ASKED_AHEAD = "Home asked for more posts ahead of the end of the list";

    /** The diagnostic report's count of the times this ran the scroll fetcher with no scroll. */
    static final String RAN_AGAIN = "Home's scroll fetcher ran again after a hidden page";

    /** The shortest time between two asks this adds. */
    static final long RETRY_MS = 2000;

    /** How many rows short of the end of the list Home asks ahead. */
    static final int AHEAD_ROWS = 8;

    /** How long hidden edges stop arriving before the fetcher runs again. */
    static final long RERUN_MS = 400;

    /** How many times in a row the fetcher runs again with no run of its own between. */
    static final int RERUNS = 5;

    /**
     * What the patch makes Home's scroll fetcher implement: its onScroll, run with [list] and the
     * first row on screen, the rows on screen and the rows in the list.
     */
    public interface Scroller {
        void hushfacebookScroll(Object list, int first, int count, int rows);
    }

    private static volatile boolean hidAny;
    private static long askedAtMs = Long.MIN_VALUE;
    private static int askedAheadAtRows = -1;

    // The fetcher's last run, main thread only.
    private static volatile boolean haveFetcher;
    private static WeakReference<Scroller> fetcher = new WeakReference<>(null);
    private static WeakReference<Object> list = new WeakReference<>(null);
    private static int first, count, rows;
    private static boolean rerunning;
    private static int reruns;

    private static Handler handler; // guarded by TailFetch.class
    private static final Runnable RERUN = TailFetch::rerun;

    private TailFetch() {
    }

    /** The feed guard turned an edge away, so the list can stop growing while the feed goes on. */
    static void hidEdge() {
        hidAny = true;
        if (!haveFetcher) return;
        Handler main = handler();
        main.removeCallbacks(RERUN);
        main.postDelayed(RERUN, RERUN_MS);
    }

    /**
     * Injection point, first thing in the scroll fetcher's onScroll: the fetcher and what it was
     * given, kept for a run with no scroll. Main thread. Never throws.
     */
    public static void scrolled(Object fetcher, Object list, int first, int count, int rows) {
        try {
            if (!(fetcher instanceof Scroller)) return;
            if (TailFetch.fetcher.get() != fetcher) TailFetch.fetcher = new WeakReference<>((Scroller) fetcher);
            if (TailFetch.list.get() != list) TailFetch.list = new WeakReference<>(list);
            TailFetch.first = first;
            TailFetch.count = count;
            TailFetch.rows = rows;
            if (!rerunning) reruns = 0;
            haveFetcher = true;
        } catch (Throwable failure) {
            HookStatus.threw(family(), "tail fetch scroll", failure);
        }
    }

    /**
     * Runs the fetcher again with what it was last given, when the end of the list was on screen
     * then. Main thread.
     */
    private static void rerun() {
        try {
            if (!Utils.settingsReady() || Setting.isPaused()) return;
            Scroller target = fetcher.get();
            Object on = list.get();
            if (target == null || on == null) return;
            if (rows <= 0 || first < 0 || first + count < rows) return;
            if (reruns >= RERUNS) return;
            long wait;
            synchronized (TailFetch.class) {
                wait = askedAtMs == Long.MIN_VALUE ? 0L : askedAtMs + RETRY_MS - SystemClock.uptimeMillis();
            }
            if (wait > 0L) {
                handler().postDelayed(RERUN, wait);
                return;
            }
            reruns++;
            final int at = first, seen = count, of = rows;
            HookStatus.counted(family(), RAN_AGAIN);
            Logger.printDebug(() -> "Feed filter: " + RAN_AGAIN + " (rows " + at + " to " + (at + seen - 1)
                    + " of " + of + ", run " + reruns + ")");
            rerunning = true;
            try {
                target.hushfacebookScroll(on, at, seen, of);
            } finally {
                rerunning = false;
            }
        } catch (Throwable failure) {
            HookStatus.threw(family(), "tail fetch run", failure);
        }
    }

    /**
     * Injection point, in the scroll fetcher's branch that doesn't fetch. Whether to fetch anyway.
     * Never throws: false leaves the fetcher's own answer.
     *
     * @param lastRow the last row on screen.
     * @param rows    how many rows the list has, its loading row included.
     */
    public static boolean askAgain(int lastRow, int rows) {
        return askAgain(lastRow, rows, SystemClock.uptimeMillis());
    }

    static boolean askAgain(int lastRow, int rows, long nowMs) {
        try {
            if (!hidAny || !Utils.settingsReady() || Setting.isPaused()) return false;
            if (rows <= 0) return false;
            if (lastRow < rows - 1) return askAhead(lastRow, rows);
            FeedEndHint.atEndOfList();
            synchronized (TailFetch.class) {
                if (askedAtMs != Long.MIN_VALUE && nowMs - askedAtMs < RETRY_MS) return false;
                askedAtMs = nowMs;
            }
            HookStatus.counted(family(), ASKED_AGAIN);
            Logger.printDebug(() -> "Feed filter: " + ASKED_AGAIN + " (row " + lastRow + " of " + rows + ")");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(family(), "tail fetch", failure);
            return false;
        }
    }

    /**
     * Before the end of the list: asks once for each length of the list when the last row on screen
     * is within {@link #AHEAD_ROWS} of the end, and leaves the fetcher's answer further up.
     */
    private static boolean askAhead(int lastRow, int rows) {
        if (lastRow < rows - 1 - AHEAD_ROWS) return false;
        synchronized (TailFetch.class) {
            if (askedAheadAtRows == rows) return false;
            askedAheadAtRows = rows;
        }
        HookStatus.counted(family(), ASKED_AHEAD);
        Logger.printDebug(() -> "Feed filter: " + ASKED_AHEAD + " (row " + lastRow + " of " + rows + ")");
        return true;
    }

    /** The family the guard's own counts go under first. */
    static String family() {
        return SettingsStatus.suggestedPosts() ? FamilyNames.SUGGESTED_POSTS : FamilyNames.SPONSORED_POSTS;
    }

    /** The main thread's handler. The guard can hide an edge on a loader thread, so it's made under a lock. */
    private static synchronized Handler handler() {
        Looper main = Looper.getMainLooper();
        Handler current = handler;
        if (current == null || current.getLooper() != main) handler = current = new Handler(main);
        return current;
    }

    static void resetForTests() {
        hidAny = false;
        synchronized (TailFetch.class) {
            askedAtMs = Long.MIN_VALUE;
            askedAheadAtRows = -1;
        }
        handler().removeCallbacks(RERUN);
        haveFetcher = false;
        fetcher = new WeakReference<>(null);
        list = new WeakReference<>(null);
        first = count = rows = 0;
        rerunning = false;
        reruns = 0;
    }
}
