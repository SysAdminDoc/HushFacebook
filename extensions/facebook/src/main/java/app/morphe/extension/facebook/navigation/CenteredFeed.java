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
import android.view.ViewTreeObserver;

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
 * list that scrolls up and down and is wider than {@link #COLUMN_DP} gets side padding that keeps
 * its rows in a column down the middle. That's on the main screen and on the screen Facebook opens
 * Menu, Notifications from a link, profiles, groups and Pages in ({@link #PAGE_SCREEN}). The padding
 * sits inside the list, so a swipe beside the column still scrolls it, and the top bar and the tab
 * bar keep the whole width.
 *
 * <p>A list that pages, as the Reels viewer does, keeps the whole screen, and so does a list that
 * scrolls sideways. A list inside a centered one is left as it is, since it's in the column already.
 * Facebook's own side padding stays when it's wider than the column's. Facebook setting its padding
 * again is followed, and a list it keeps setting it on is left as Facebook has it for a while, so
 * the two never fight over its layout. The lists are looked through as each screen lays itself out,
 * resumes and gets its focus back, so a change shows when the settings close. Off or paused,
 * each list gets Facebook's padding back. Any failure gives the padding back and stops the checks
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
     * Whether [view] is a list this centers: a RecyclerView wider than the column that scrolls up
     * and down and doesn't page, or one whose rows all fit and are stacked down it.
     */
    static boolean centers(View view, int column) {
        if (!TranslucentBars.isList(view) || view.getWidth() <= column) return false;
        if (view.canScrollVertically(1) || view.canScrollVertically(-1)) return !TranslucentBars.pages(view, 0);
        return stacksRows(view);
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

    /** One list's side padding: Facebook's, and what this set. */
    static final class Column {
        int facebookLeft;
        int facebookRight;
        int left = -1;
        int right = -1;
        long setAt;
        int putBacks;
        boolean leftAlone;
        long leftAt;

        Column(View list) {
            facebookLeft = list.getPaddingLeft();
            facebookRight = list.getPaddingRight();
        }

        /** Whether [list]'s side padding is still what this set. */
        boolean ours(View list) {
            return list.getPaddingLeft() == left && list.getPaddingRight() == right;
        }

        /** Facebook's side padding back on [list], where nothing has set it since. */
        void giveBack(View list) {
            if (left >= 0 && ours(list)) {
                list.setPadding(facebookLeft, list.getPaddingTop(), facebookRight, list.getPaddingBottom());
                HookStatus.counted(FAMILY, GIVEN_BACK);
            }
            left = -1;
            right = -1;
        }
    }

    /** One main or page screen's lists and their columns. */
    static final class Columns implements ViewTreeObserver.OnGlobalLayoutListener,
            ViewTreeObserver.OnWindowFocusChangeListener {
        private final WeakReference<View> window;
        /** Each list seen, by the list. */
        final Map<View, Column> lists = new WeakHashMap<>();
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

        /** Centers each wide list while on, or gives every list its padding back once when off. */
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
                    Column column = lists.get(list);
                    int width = columnPixels(list);
                    if (!centers(list, width)) {
                        if (column != null) column.giveBack(list);
                        continue;
                    }
                    if (column == null) {
                        column = new Column(list);
                        lists.put(list, column);
                    }
                    center(list, column, width, now);
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

        /** Side padding on [list] that keeps its rows [width] pixels wide, down the middle. */
        private void center(View list, Column column, int width, long now) {
            if (column.leftAlone) {
                if (now - column.leftAt < LEFT_ALONE_MS) return;
                column.leftAlone = false;
                column.putBacks = 0;
                column.left = -1;
                column.right = -1;
            }
            if (column.left >= 0 && !column.ours(list)) {
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
                column.facebookLeft = list.getPaddingLeft();
                column.facebookRight = list.getPaddingRight();
            } else if (column.left < 0) {
                column.facebookLeft = list.getPaddingLeft();
                column.facebookRight = list.getPaddingRight();
            }
            int side = (list.getWidth() - width) / 2;
            int left = Math.max(column.facebookLeft, side);
            int right = Math.max(column.facebookRight, side);
            if (list.getPaddingLeft() != left || list.getPaddingRight() != right) {
                HookStatus.invoked(FAMILY);
                list.setPadding(left, list.getPaddingTop(), right, list.getPaddingBottom());
                column.setAt = now;
                HookStatus.counted(FAMILY, CENTERED);
                Logger.printDebug(() -> "Centered feed: a list " + list.getWidth() + " px wide kept to " + width);
            }
            column.left = left;
            column.right = right;
        }

        private void giveBackAll() {
            if (lists.isEmpty()) return;
            for (Map.Entry<View, Column> entry : new ArrayList<>(lists.entrySet())) {
                entry.getValue().giveBack(entry.getKey());
            }
            lists.clear();
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
