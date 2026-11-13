/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Loads the next page of Home's Stories tray when the tray filters leave its row short (#317).
 *
 * <p>While the tray has a next page, its row ends in three gray placeholder tiles. Facebook loads
 * that page when one of the tray's last buckets comes into view, and when a placeholder comes into
 * view only behind a server flag that's off for some accounts. With Pages', watched or suggested
 * stories filtered out, a tray of 15 buckets can come down to one, which comes into view before the
 * tray knows it has a next page, the placeholders take the rest of the row, and the next page never
 * loads.
 *
 * <p>The patch hands {@link #placeholderPages} that flag's answer, and while the filters are leaving
 * buckets out it answers yes, so a placeholder coming into view loads the next page the way it does
 * for accounts with the flag on. Facebook still loads nothing while a fetch is running or when the
 * tray has no next page. A page whose buckets are all filtered out changes nothing in view, so no
 * placeholder comes into view again: once such a page settles with a placeholder still in view,
 * {@link #check} shows the tray's tile handler that placeholder again. Ten asks while the kept
 * count stays the same stop it, so a tray of nothing but suggestions isn't paged forever. Nothing
 * changes while the filters keep every bucket.
 *
 * <p>The handler skips a placeholder without a word while the tray's fetch is still running, and a
 * cold start's first page often settles before that fetch does, so the gray tiles stayed (S22,
 * 2026-10-31). While Facebook doesn't get as far as the flag, {@link #check} looks again every
 * {@link #RETRY_MS}, {@link #MAX_RETRIES} times a page.
 */
public final class StoriesTrayPaging {
    /** Counted under its own name each time the tray is asked for its next page. */
    static final String ROUTE = "Stories tray paging";
    static final String NEXT_PAGE = "next page";
    static final String GAVE_UP = "stopped, no kept stories";

    /** The bucket id the tray's placeholder tiles hand the tile handler. */
    static final String PLACEHOLDER = "placeholder_bucket_id";

    /**
     * How long a filtered page gets to settle before the row is checked, and how long after an ask
     * the other placeholders coming into view with it ask nothing more.
     */
    static final long SETTLE_MS = 600;

    /** Asks that may add no kept story before paging stops. */
    static final int MAX_EMPTY_PAGES = 10;

    /** How long after a placeholder shown again went nowhere it's shown again, and how many times a page. */
    static final long RETRY_MS = 1000;
    static final int MAX_RETRIES = 8;

    /** The tray's tile handler, with the method the patch adds to it. */
    public interface Pager {
        /** Hands the handler a placeholder at {@code position} coming into view, as the tile does. */
        void hushShowPlaceholder(int position);
    }

    private static WeakReference<Pager> pager = new WeakReference<>(null);

    /** The filters have left buckets out this session. */
    private static volatile boolean filtered;

    /** Placeholders in view, and the position of the last one to come into view. */
    private static int placeholdersInView;
    private static int lastPlaceholder = -1;

    /** The tray's kept bucket count, and the asks since it last changed. */
    private static int kept = -1;
    private static int asks;
    private static boolean gaveUp;
    private static long askedAt = Long.MIN_VALUE / 2;

    /** The extension is showing the handler a placeholder itself. */
    private static boolean showing;

    /** The handler got as far as the flag while the extension showed it a placeholder. */
    private static boolean flagRead;

    /** Placeholders shown again since the last page that went nowhere. */
    private static int retries;

    @Nullable private static Handler main;
    private static final Runnable CHECK = StoriesTrayPaging::check;

    private StoriesTrayPaging() {
    }

    /**
     * Injection point, first thing in the tray's tile handler when a tile comes into view.
     * Remembers the handler, counts the placeholders in view, and hands a story's tile to Hide
     * stories you scrolled past (#463). Never throws.
     *
     * @param handler  the tile handler, made a {@link Pager} by the patch.
     * @param bucketId the tile's bucket id, {@link #PLACEHOLDER} for a placeholder.
     * @param position the tile's position in the row.
     */
    public static void tileShown(@Nullable Object handler, @Nullable String bucketId, int position) {
        try {
            if (!PLACEHOLDER.equals(bucketId)) PassedStories.shown(bucketId);
            synchronized (StoriesTrayPaging.class) {
                if (showing) return;
                if (handler instanceof Pager && pager.get() != handler) pager = new WeakReference<>((Pager) handler);
                if (!PLACEHOLDER.equals(bucketId)) return;
                if (placeholdersInView++ == 0 && filtered) {
                    Logger.printDebug(() -> "Stories tray: gray tiles in view from " + position);
                }
                lastPlaceholder = position;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "tray paging tile", failure);
        }
    }

    /** Injection point, first thing when a placeholder tile leaves view. Never throws. */
    public static void placeholderHidden() {
        try {
            synchronized (StoriesTrayPaging.class) {
                if (placeholdersInView > 0) placeholdersInView--;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "tray paging tile", failure);
        }
    }

    /**
     * Injection point, the tile handler's server flag for loading the next page when a placeholder
     * comes into view, read after Facebook's check that no fetch is running. Answers yes while the
     * filters are leaving buckets out, once per placeholder coming into view and at most
     * {@link #MAX_EMPTY_PAGES} times while the kept count stays the same. Never throws.
     *
     * @param flag the server's answer, which stands when it's yes.
     */
    public static boolean placeholderPages(boolean flag) {
        if (flag) {
            synchronized (StoriesTrayPaging.class) {
                if (showing) flagRead = true;
            }
            if (filtered) Logger.printDebug(() -> "Stories tray: Facebook loads the next page for a gray tile itself");
            return true;
        }
        try {
            synchronized (StoriesTrayPaging.class) {
                if (showing) flagRead = true;
                if (!filtered) return false;
                long now = SystemClock.uptimeMillis();
                // The placeholders come into view together, and the first one asked.
                if (!showing && now - askedAt < SETTLE_MS) return false;
                if (asks >= MAX_EMPTY_PAGES) {
                    if (!gaveUp) {
                        gaveUp = true;
                        FeedFilterCounters.sawKind(ROUTE, GAVE_UP);
                        Logger.printDebug(() -> "Stories tray: " + MAX_EMPTY_PAGES + " pages added no kept stories, so it stopped paging");
                    }
                    return false;
                }
                asks++;
                askedAt = now;
                final int count = kept;
                FeedFilterCounters.sawList(ROUTE, 1);
                FeedFilterCounters.sawKind(ROUTE, NEXT_PAGE);
                Logger.printDebug(() -> "Stories tray: " + count + " kept, a gray tile in view, asked for its next page");
                return true;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "tray paging", failure);
            return false;
        }
    }

    /**
     * The tray filter went through the tray, which holds every page loaded so far. A kept count
     * that changed starts the ask count again; a page that left buckets out gets the row checked
     * once it settles. Never throws.
     */
    static void sawTray(int keptBuckets, int droppedBuckets) {
        try {
            synchronized (StoriesTrayPaging.class) {
                if (droppedBuckets > 0) filtered = true;
                if (keptBuckets != kept) {
                    kept = keptBuckets;
                    asks = 0;
                    gaveUp = false;
                }
                retries = 0;
            }
            if (filtered) schedule(SETTLE_MS);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "tray paging", failure);
        }
    }

    private static void schedule(long delayMs) {
        Handler handler = main;
        if (handler == null) main = handler = new Handler(Looper.getMainLooper());
        handler.removeCallbacks(CHECK);
        handler.postDelayed(CHECK, delayMs);
    }

    /**
     * Shows the handler the last placeholder again while one is in view, and again after
     * {@link #RETRY_MS} while the handler stops short of the flag.
     */
    static void check() {
        try {
            Pager handler;
            int position;
            synchronized (StoriesTrayPaging.class) {
                handler = pager.get();
                position = lastPlaceholder;
                if (handler == null || placeholdersInView <= 0) {
                    retries = 0;
                    final boolean noTray = handler == null;
                    Logger.printDebug(() -> "Stories tray: " + (noTray ? "no tray yet" : "no gray tile in view"));
                    return;
                }
                showing = true;
                flagRead = false;
            }
            boolean again;
            boolean lastTry;
            try {
                handler.hushShowPlaceholder(position);
            } finally {
                synchronized (StoriesTrayPaging.class) {
                    showing = false;
                    again = !flagRead && retries < MAX_RETRIES;
                    lastTry = !flagRead && !again;
                    retries = again ? retries + 1 : 0;
                }
            }
            if (again) {
                Logger.printDebug(() -> "Stories tray: still loading, so it looks at the gray tiles again in "
                        + RETRY_MS + " ms");
                schedule(RETRY_MS);
            } else if (lastTry) {
                Logger.printDebug(() -> "Stories tray: Facebook still won't load a next page for the gray tiles");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "tray paging", failure);
            Logger.printException(() -> "Stories tray: could not ask for the next page", failure);
        }
    }

    /** Forgets the tray and its counts, for tests. */
    static void reset() {
        synchronized (StoriesTrayPaging.class) {
            pager = new WeakReference<>(null);
            filtered = false;
            placeholdersInView = 0;
            lastPlaceholder = -1;
            kept = -1;
            asks = 0;
            gaveUp = false;
            askedAt = Long.MIN_VALUE / 2;
            showing = false;
            flagRead = false;
            retries = 0;
        }
        if (main != null) main.removeCallbacks(CHECK);
    }
}
