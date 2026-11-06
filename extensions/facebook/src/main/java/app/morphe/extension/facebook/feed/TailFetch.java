/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

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
 */
public final class TailFetch {
    /** The diagnostic report's count of the asks this added. */
    static final String ASKED_AGAIN = "Home asked for more posts at the end of the list";

    /** The shortest time between two asks this adds. */
    static final long RETRY_MS = 2000;

    private static volatile boolean hidAny;
    private static long askedAtMs = Long.MIN_VALUE;

    private TailFetch() {
    }

    /** The feed guard turned an edge away, so the list can stop growing while the feed goes on. */
    static void hidEdge() {
        hidAny = true;
    }

    /**
     * Injection point, in the scroll fetcher's branch that doesn't fetch. Whether to fetch anyway.
     * Never throws: false leaves the fetcher's own answer.
     *
     * @param lastRow the last row on screen.
     * @param rows    how many rows the list has, its loading row included.
     */
    public static boolean askAgain(int lastRow, int rows) {
        return askAgain(lastRow, rows, System.nanoTime() / 1_000_000L);
    }

    static boolean askAgain(int lastRow, int rows, long nowMs) {
        try {
            if (!hidAny || !Utils.settingsReady() || Setting.isPaused()) return false;
            // Not at the end of the list: the fetcher's own rule moves on with the next scroll.
            if (rows <= 0 || lastRow < rows - 1) return false;
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

    /** The family the guard's own counts go under first. */
    static String family() {
        return SettingsStatus.suggestedPosts() ? FamilyNames.SUGGESTED_POSTS : FamilyNames.SPONSORED_POSTS;
    }

    static void resetForTests() {
        hidAny = false;
        synchronized (TailFetch.class) {
            askedAtMs = Long.MIN_VALUE;
        }
    }
}
