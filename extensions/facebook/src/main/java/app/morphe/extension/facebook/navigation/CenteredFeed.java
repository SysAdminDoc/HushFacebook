/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Center the feed on big screens (#531): on a tablet or an unfolded phone, Facebook's phone layout
 * stretches the feed and its other lists from edge to edge, so a post's text runs across the whole
 * screen and a photo fills it. Facebook's own big screen layout is its servers' call per account (a
 * web of rollout flags read in 588's {@code LX/01Bu;}), so this works on the lists themselves: a
 * list that scrolls up and down, or whose rows all fit and are stacked down it, and is wider than
 * {@link #COLUMN_DP} is kept in a column down the middle. That's on the main screen and on the
 * screen Facebook opens Menu, Notifications from a link, profiles, groups and Pages in
 * ({@link #PAGE_SCREEN}).
 *
 * <p>The side padding goes on the frame that holds the list and nothing else ({@link #hostOf}), not
 * on the list: Menu's, Notifications' and a profile's rows are sized from the width Facebook's
 * layout gave the list, so padding the list alone only pushed them right and cut them off (seen on
 * 588). A frame is a FrameLayout, which hands its padding down. The climb to it stops below anything
 * else on the screen, so the top bar, a page's title bar and the tab bar keep the whole width, and
 * at the screen's content frame. A list with no such frame gets the padding itself, unless its rows
 * still run past it once it's laid out, and then it's left wide. A list's frame only moves up: a
 * pull-to-refresh spinner showing beside the list for a moment doesn't move its padding lower.
 *
 * <p>A list that pages, as the Reels viewer does, keeps the whole screen, and so does a list that
 * scrolls sideways. A list inside a centered one is left as it is, since it's in the column already.
 * Facebook's own side padding stays when it's wider than the column's. Facebook setting its padding
 * again is followed, and a frame it keeps setting it on is left as Facebook has it for a while, so
 * the two never fight over its layout. The lists are looked through as each screen lays itself out,
 * resumes and gets its focus back, so a change shows when the settings close. Off or paused,
 * each frame gets Facebook's padding back. Any failure gives the padding back and stops the checks
 * of that screen.
 */
public final class CenteredFeed {
    /** Facebook's main screen, a manifest component, the one screen with the feed and the tab bar. */
    static final String MAIN_SCREEN = TranslucentBars.MAIN_SCREEN;
    /**
     * The screen Facebook opens over the main one for Menu, Notifications from a link, profiles,
     * groups, Pages and reels from a post. Its reels page, so they keep the whole screen.
     */
    static final String PAGE_SCREEN = StartTabRoute.PAGE_SCREEN;
    /** The column's width, in dp: a small tablet's, and about what Facebook's web feed keeps to. */
    static final int COLUMN_DP = 600;
    /** How far below the window's top view a list can sit. The Home feed is about twenty down. */
    static final int MAX_DEPTH = 40;
    /** How soon after a centering Facebook setting its padding again counts as it wanting its own. */
    static final long PUT_BACK_MS = 3000;
    /** How many put-backs in a row leave a list as Facebook has it. */
    static final int MAX_PUT_BACKS = 3;
    /** How long a list left as Facebook has it waits before it's centered again. */
    static final long LEFT_ALONE_MS = 10_000;

    /** Counted each time a list's padding is set to center it. */
    static final String CENTERED = "List centered on a big screen";
    /** Counted each time a centered list gets Facebook's padding back. */
    static final String GIVEN_BACK = "Centered list given back";
    /** Counted each time Facebook kept setting a list's padding and it was left alone. */
    static final String LEFT_ALONE = "Centered list left as Facebook has it";
    /** Counted each time a list padded itself and its rows ran past the padding, so it was left wide. */
    static final String ROWS_STAY_WIDE = "Centered list left wide, its rows ignore padding";

    private static final String FAMILY = FamilyNames.BOTTOM_TAB_BAR;

    private static boolean watching;

    /** Each main or page screen's window and its columns. */
    static final Map<View, Columns> WINDOWS = new WeakHashMap<>();

    private CenteredFeed() {
    }

    /**
     * Called once per Facebook start, from the settings entry's onApplicationCreate, with the tab bar
     * patch in the build. Each main or page screen gets its checks as it resumes; the switch is asked at each.
     */
    public static synchronized void watch(Context context) {
        try {
            if (watching || !(context instanceof Application)) return;
            ((Application) context).registerActivityLifecycleCallbacks(new Screens());
            watching = true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "centered feed", failure);
        }
    }

    /** Whether lists are centered now: the switch on, nothing paused, the settings ready. */
    public static boolean on() {
        return Utils.settingsReady() && Settings.CENTER_FEED_ON_BIG_SCREENS.get();
    }

    /** The columns of [window], made once and asked again at each check. */
    static Columns attach(View window) {
        synchronized (WINDOWS) {
            Columns columns = WINDOWS.get(window);
            if (columns == null) {
                columns = new Columns(window);
                WINDOWS.put(window, columns);
                ViewTreeObserver observer = window.getViewTreeObserver();
                observer.addOnGlobalLayoutListener(columns);
                observer.addOnWindowFocusChangeListener(columns);
            }
            return columns;
        }
    }

    /**
     * Whether [list] is centered through [host], the view whose padding keeps it to [column] pixels:
     * the list runs down ({@link #runsDown}), [host] is wider than the column, and the list spans
     * nearly all of [host] inside its padding, so a narrow list beside something else is left as it is.
     */
    static boolean centers(View list, View host, int column) {
        if (!runsDown(list) || host.getWidth() <= column) return false;
        int inside = host.getWidth() - host.getPaddingLeft() - host.getPaddingRight();
        return list.getWidth() * 10 >= inside * 9;
    }

    /**
     * Whether [view] is a list whose rows run down it: a RecyclerView that scrolls up and down and
     * doesn't page, or one whose rows all fit and are stacked down it. None of it hangs on the
     * list's width, so it reads the same before and after the column narrows it.
     */
    static boolean runsDown(View view) {
        if (!TranslucentBars.isList(view)) return false;
        if (view.canScrollVertically(1) || view.canScrollVertically(-1)) return !TranslucentBars.pages(view, 0);
        return stacksRows(view);
    }

    /**
     * The view whose side padding keeps [list] in the column: the highest FrameLayout above it that
     * holds nothing else with a size, stopping at the screen's content frame and below [window].
     * Facebook sizes most rows from the width its layout gives the list, so a frame above the list
     * narrows them where the list's own padding only moves them. [list] itself when there's none.
     */
    static View hostOf(View list, View window) {
        View host = list;
        View child = list;
        ViewParent parent = list.getParent();
        for (int depth = 0; depth < MAX_DEPTH && parent instanceof ViewGroup && parent != window; depth++) {
            ViewGroup group = (ViewGroup) parent;
            if (!holdsOnly(group, child)) break;
            if (group instanceof FrameLayout) host = group;
            if (group.getId() == android.R.id.content) break;
            child = group;
            parent = group.getParent();
        }
        return host;
    }

    /** Whether [child] is the only one of [group]'s children that takes up any room. */
    static boolean holdsOnly(ViewGroup group, View child) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View other = group.getChildAt(i);
            if (other == child || other.getVisibility() == View.GONE) continue;
            if (other.getWidth() > 0 && other.getHeight() > 0) return false;
        }
        return true;
    }

    /** Whether [outer] is [view] or holds it somewhere below. */
    static boolean holds(View outer, View view) {
        for (Object at = view; at instanceof View; at = ((View) at).getParent()) {
            if (at == outer) return true;
        }
        return false;
    }

    /**
     * Whether a row of [list] runs past its right padding, as a row sized from the width Facebook's
     * layout gave the list does once the list pads itself: pushed right and cut off.
     */
    static boolean rowsIgnorePadding(View view) {
        ViewGroup list = (ViewGroup) view;
        int edge = list.getWidth() - list.getPaddingRight();
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            if (row.getVisibility() == View.GONE || row.getWidth() == 0) continue;
            if (row.getRight() > edge + 2) return true;
        }
        return false;
    }

    /**
     * Whether [view], a list that doesn't scroll, holds rows stacked down it, as Menu does on a
     * tablet where all of it fits. Each row spans nearly the list's width and none fills its
     * height, so a row of cards side by side or a single page is left as it is. Judged against
     * the width inside the padding, so it reads the same before and after centering.
     */
    static boolean stacksRows(View view) {
        if (view.canScrollHorizontally(1) || view.canScrollHorizontally(-1)) return false;
        ViewGroup list = (ViewGroup) view;
        int across = list.getWidth() - list.getPaddingLeft() - list.getPaddingRight();
        int down = list.getHeight() - list.getPaddingTop() - list.getPaddingBottom();
        int rows = 0;
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            if (row.getVisibility() == View.GONE) continue;
            if (row.getWidth() * 10 < across * 9 || Math.abs(row.getHeight() - down) <= 2) return false;
            rows++;
        }
        return rows > 0;
    }

    /** The column's width in [view]'s pixels. */
    static int columnPixels(View view) {
        return Math.round(COLUMN_DP * view.getResources().getDisplayMetrics().density);
    }

    /** The lists in [view] and below it, to [depth] down, not looking inside a list. */
    static void listsIn(View view, int depth, List<View> found) {
        if (view.getVisibility() == View.GONE) return;
        if (TranslucentBars.isList(view)) {
            found.add(view);
            return;
        }
        if (depth >= MAX_DEPTH || !(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            listsIn(group.getChildAt(i), depth + 1, found);
        }
    }

    /** Whether a screen of class [name] gets its lists centered: the main screen or the page screen. */
    static boolean watches(String name) {
        return MAIN_SCREEN.equals(name) || PAGE_SCREEN.equals(name);
    }

    /** Gives each main or page screen its checks as it resumes, and runs one. */
    static final class Screens implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityResumed(Activity activity) {
            try {
                if (!watches(activity.getClass().getName()) || activity.getWindow() == null) return;
                attach(activity.getWindow().getDecorView()).check();
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "centered feed", failure);
            }
        }

        @Override
        public void onActivityCreated(Activity activity, @Nullable Bundle state) {
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityPaused(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle state) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }
    }

    /** One frame's side padding, or a list's: Facebook's, and what this set. */
    static final class Column {
        int facebookLeft;
        int facebookRight;
        int left = -1;
        int right = -1;
        long setAt;
        int putBacks;
        boolean leftAlone;
        long leftAt;
        /** A list that padded itself and whose rows ran past the padding: left wide from then on. */
        boolean rowsStayWide;

        Column(View view) {
            facebookLeft = view.getPaddingLeft();
            facebookRight = view.getPaddingRight();
        }

        /** Whether [view]'s side padding is still what this set. */
        boolean ours(View view) {
            return view.getPaddingLeft() == left && view.getPaddingRight() == right;
        }

        /** Facebook's side padding back on [view], where nothing has set it since. */
        void giveBack(View view) {
            if (left >= 0 && ours(view)) {
                view.setPadding(facebookLeft, view.getPaddingTop(), facebookRight, view.getPaddingBottom());
                HookStatus.counted(FAMILY, GIVEN_BACK);
            }
            left = -1;
            right = -1;
        }
    }

    /** One main or page screen's lists, the frames that keep them in the column, and their padding. */
    static final class Columns implements ViewTreeObserver.OnGlobalLayoutListener,
            ViewTreeObserver.OnWindowFocusChangeListener {
        private final WeakReference<View> window;
        /** Each frame or list padded, by the view. */
        final Map<View, Column> padded = new WeakHashMap<>();
        /** Each list's frame as last used, so it only moves up. Weak both ways: the frame holds the list. */
        final Map<View, WeakReference<View>> hosts = new WeakHashMap<>();
        boolean stopped;

        Columns(View window) {
            this.window = new WeakReference<>(window);
        }

        @Override
        public void onGlobalLayout() {
            check();
        }

        /** The settings page closing hands the window its focus back. */
        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            if (hasFocus) check();
        }

        void check() {
            check(SystemClock.uptimeMillis());
        }

        /** Centers each wide list while on, or gives every frame its padding back once when off. */
        void check(long now) {
            if (stopped) return;
            try {
                if (!on()) {
                    giveBackAll();
                    return;
                }
                View window = this.window.get();
                if (window == null) return;
                List<View> found = new ArrayList<>();
                listsIn(window, 0, found);
                for (View list : found) {
                    View host = hostFor(list, window);
                    Column column = padded.get(host);
                    int width = columnPixels(list);
                    if (!centers(list, host, width)) {
                        if (column != null) column.giveBack(host);
                        continue;
                    }
                    if (column == null) {
                        column = new Column(host);
                        padded.put(host, column);
                    }
                    if (column.rowsStayWide) continue;
                    if (host == list && column.left >= 0 && column.ours(list) && !list.isLayoutRequested()
                            && rowsIgnorePadding(list)) {
                        // Laid out with the padding and its rows still run past it: they're cut off.
                        column.giveBack(list);
                        column.rowsStayWide = true;
                        HookStatus.counted(FAMILY, ROWS_STAY_WIDE);
                        continue;
                    }
                    center(host, column, width, now);
                }
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "centered feed", failure);
                stopped = true;
                try {
                    giveBackAll();
                    forget();
                } catch (Throwable ignored) {
                    // Already failing; the checks stay stopped either way.
                }
            }
        }

        /**
         * [list]'s frame ({@link #hostOf}), kept where it was while that one still holds the one
         * found now: something showing beside the list for a moment stops the climb lower, and
         * padding a frame inside the padded one would narrow the list twice. A frame found higher,
         * or one that no longer holds the list, gives the old one its padding back first.
         */
        private View hostFor(View list, View window) {
            View found = hostOf(list, window);
            WeakReference<View> kept = hosts.get(list);
            View before = kept == null ? null : kept.get();
            if (before != null && before != found) {
                if (holds(before, found)) return before;
                Column old = padded.remove(before);
                if (old != null) old.giveBack(before);
            }
            hosts.put(list, new WeakReference<>(found));
            return found;
        }

        /** Side padding on [view], a list's frame or the list, that keeps it [width] pixels wide inside. */
        private void center(View view, Column column, int width, long now) {
            if (column.leftAlone) {
                if (now - column.leftAt < LEFT_ALONE_MS) return;
                column.leftAlone = false;
                column.putBacks = 0;
                column.left = -1;
                column.right = -1;
            }
            if (column.left >= 0 && !column.ours(view)) {
                // Facebook set its own padding since: that's its padding now.
                if (now - column.setAt < PUT_BACK_MS) {
                    if (++column.putBacks >= MAX_PUT_BACKS) {
                        column.leftAlone = true;
                        column.leftAt = now;
                        column.left = -1;
                        column.right = -1;
                        HookStatus.counted(FAMILY, LEFT_ALONE);
                        return;
                    }
                } else {
                    column.putBacks = 0;
                }
                column.facebookLeft = view.getPaddingLeft();
                column.facebookRight = view.getPaddingRight();
            } else if (column.left < 0) {
                column.facebookLeft = view.getPaddingLeft();
                column.facebookRight = view.getPaddingRight();
            }
            int side = (view.getWidth() - width) / 2;
            int left = Math.max(column.facebookLeft, side);
            int right = Math.max(column.facebookRight, side);
            if (view.getPaddingLeft() != left || view.getPaddingRight() != right) {
                HookStatus.invoked(FAMILY);
                view.setPadding(left, view.getPaddingTop(), right, view.getPaddingBottom());
                column.setAt = now;
                HookStatus.counted(FAMILY, CENTERED);
                Logger.printDebug(() -> "Centered feed: " + view.getClass().getName() + " " + view.getWidth()
                        + " px wide kept to " + width);
            }
            column.left = left;
            column.right = right;
        }

        private void giveBackAll() {
            hosts.clear();
            if (padded.isEmpty()) return;
            for (Map.Entry<View, Column> entry : new ArrayList<>(padded.entrySet())) {
                entry.getValue().giveBack(entry.getKey());
            }
            padded.clear();
        }

        private void forget() {
            View window = this.window.get();
            if (window == null) return;
            ViewTreeObserver observer = window.getViewTreeObserver();
            if (!observer.isAlive()) return;
            observer.removeOnGlobalLayoutListener(this);
            observer.removeOnWindowFocusChangeListener(this);
        }
    }
}
