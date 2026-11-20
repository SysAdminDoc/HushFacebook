/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;

/**
 * Reels and videos opened from a post run under the gesture bar (#466). They open on Facebook's
 * full-screen player screen, which pads its content up off the gesture bar and paints that band
 * (S22, 588: the content 0,0-1080,2316, the viewer stopping at 2274, a 42 px band under it). With
 * See-through bars and Hide the video comment box on there's nothing left to keep off the band, so
 * Facebook's bottom inset for that padding is answered 0 ({@link #bottomInset}), the viewer runs to
 * the screen's bottom edge, and the band's strip goes off the screen with the padding it filled.
 *
 * <p>The player screen hosts other pages too (the Feeds page among them), so only a screen whose
 * video viewer made its header ({@link #viewerMade}, from {@link ReelsHeaderClear#headerMade}) is
 * opened, and only while that header is on the screen and the viewer's paging list shows. Each frame
 * then lifts what would end up behind the gesture bar: each reel's name, caption and buttons
 * ({@link ReelDetailsLift}) and the small views Facebook pins to the viewer's bottom edge, the Up
 * next card among them. A viewer whose reels stay short of the taller list, so the next one would
 * show under the bar, is given its band back for good.
 *
 * <p>Off, paused, another page shown, or any failure gives the band back and every view back where
 * Facebook has it.
 */
public final class ImmersiveEdge {
    /** Facebook's full-screen player screen, a manifest component. */
    static final String PAGE_SCREEN = StartTabRoute.PAGE_SCREEN;

    private static final String FAMILY = FamilyNames.BOTTOM_TAB_BAR;
    static final String HOOK = "Videos under the gesture bar";
    /** Counted each time Facebook's band under a video viewer is answered 0. */
    static final String OPENED = "Gesture bar band opened under a video";
    /** Counted for each view pinned to the viewer's bottom that's lifted above the gesture bar. */
    static final String LIFTED = "Video viewer part lifted above the gesture bar";
    /** Counted each time a viewer whose reels didn't follow the taller list gets its band back. */
    static final String LEFT_ALONE = "Gesture bar band given back to a viewer";

    /** How long a viewer just made has to show its paging list, in ms. */
    static final long GRACE_MS = 4000;
    /** How long a shown viewer's list can be missing before the band comes back, in ms. */
    static final long MISSING_MS = 1000;

    /** Each player screen's content view with a viewer made in it, and its edge. */
    static final Map<View, Edge> EDGES = new WeakHashMap<>();

    /** The clock, in place of the system's, for tests; -1 reads the system's. */
    static volatile long nowForTests = -1;

    private ImmersiveEdge() {
    }

    /** Whether the switches let a viewer run under the gesture bar: See-through bars and Hide the video comment box on, nothing paused. */
    static boolean switches() {
        return Utils.settingsReady() && !Setting.isPaused() && Settings.TRANSLUCENT_BARS.get()
                && Settings.HIDE_VIDEO_COMMENT_COMPOSER.get();
    }

    /**
     * Called first thing where Facebook pads its screens for the system bars (588
     * {@code LX/02V3;->A00}), with the window's content view and the bottom inset it's about to pad
     * the content by and size its band under the screen to. 0 on a player screen whose viewer runs
     * under the gesture bar now; otherwise [bottom], and on a failure too.
     */
    public static int bottomInset(View content, int bottom) {
        try {
            if (bottom <= 0 || content == null) return bottom;
            Edge edge;
            synchronized (EDGES) {
                edge = EDGES.get(content);
            }
            if (edge == null) return bottom;
            edge.inset = bottom;
            boolean open = edge.open();
            if (open != edge.answered) {
                Logger.printDebug(() -> "Immersive edge: Facebook's " + bottom + " px for the gesture bar answered "
                        + (open ? "0" : "as it is"));
            }
            edge.answered = open;
            if (!open) return bottom;
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, HOOK);
            HookStatus.counted(FAMILY, OPENED);
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
            return bottom;
        }
    }

    /**
     * Called from {@link ReelsHeaderClear#headerMade} with a video viewer's [header] as its page is
     * made. On the player screen, its content view gets an edge, which asks the window's insets
     * again so Facebook pads the screen again.
     */
    static void viewerMade(@Nullable View header) {
        try {
            if (header == null) return;
            Activity screen = screenOf(header.getContext());
            Window window = screen == null ? null : screen.getWindow();
            if (window == null || !PAGE_SCREEN.equals(screen.getClass().getName())) return;
            View decor = window.peekDecorView();
            View content = decor == null ? null : decor.findViewById(android.R.id.content);
            if (content == null) return;
            Edge edge;
            synchronized (EDGES) {
                edge = EDGES.get(content);
                if (edge == null) {
                    edge = new Edge(content);
                    EDGES.put(content, edge);
                    content.getViewTreeObserver().addOnPreDrawListener(edge);
                }
            }
            edge.viewer(header);
            edge.frame();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
        }
    }

    @Nullable
    private static Activity screenOf(Context context) {
        for (int depth = 0; depth < 10 && context != null; depth++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    static long now() {
        long forced = nowForTests;
        return forced >= 0 ? forced : SystemClock.uptimeMillis();
    }

    /** Whether [view] is shown and as tall as 3/4 of [height], the screen's: a layer the viewer fills. */
    static boolean fills(View view, int height) {
        return view.getVisibility() == View.VISIBLE && view.getHeight() * 4L >= height * 3L;
    }

    /**
     * The viewer's paging list under [content]: a list as tall as a layer the viewer fills, with a
     * row as tall as 3/4 of it, found down through the layers the viewer fills. Null when it isn't
     * shown.
     */
    @Nullable
    static View pagingList(View content) {
        return pagingList(content, content.getHeight(), 0);
    }

    @Nullable
    private static View pagingList(View view, int height, int depth) {
        if (!(view instanceof ViewGroup) || depth > 24) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (!fills(child, height)) continue;
            if (TranslucentBars.isList(child)) {
                if (pageRow((ViewGroup) child) != null) return child;
                continue;
            }
            View found = pagingList(child, height, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    /** The row of [list] as tall as 3/4 of its inside and nearest its top, or null. */
    @Nullable
    static View pageRow(ViewGroup list) {
        int inside = list.getHeight() - list.getPaddingTop() - list.getPaddingBottom();
        View best = null;
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            if (row.getVisibility() != View.VISIBLE || row.getHeight() * 4L < inside * 3L) continue;
            if (best == null || Math.abs(row.getTop() - list.getPaddingTop()) < Math.abs(best.getTop() - list.getPaddingTop())) {
                best = row;
            }
        }
        return best;
    }

    /**
     * Whether the reel paged in on [list] stops short of the list's inside, so the next reel shows
     * under it: Facebook kept the rows at their height as the list grew. A list mid-swipe can't say.
     */
    static boolean rowsShort(ViewGroup list) {
        View row = pageRow(list);
        if (row == null || Math.abs(row.getTop() - list.getPaddingTop()) > 2) return false;
        int inside = list.getHeight() - list.getPaddingTop() - list.getPaddingBottom();
        return inside - row.getHeight() > 2;
    }

    /** One player screen's content view: whether its viewer runs under the gesture bar, and what's lifted. */
    static final class Edge implements ViewTreeObserver.OnPreDrawListener {
        final WeakReference<View> content;
        WeakReference<View> header = new WeakReference<>(null);
        /** When the viewer's header was last made. */
        long madeAt;
        /** When the viewer's list was last missing while its header showed, or -1. */
        long missingSince = -1;
        /** Whether the viewer showed at the last frame. */
        boolean showing;
        /** Whether this viewer's reels didn't follow the taller list, so it keeps the band. */
        boolean leftAlone;
        /** The bottom inset Facebook last handed over, the gesture bar's height. */
        int inset;
        /** Whether Facebook's padding was last answered 0. */
        boolean answered;
        /** The state the window's insets were last asked again for, so each change asks once. */
        boolean asked;
        /** When the band was last opened under the viewer, for the reels to follow. */
        long openedAt = -1;
        boolean stopped;
        final ReelDetailsLift details = new ReelDetailsLift();
        final Lifts pinned = new Lifts();

        Edge(View content) {
            this.content = new WeakReference<>(content);
        }

        /**
         * Whether the viewer's header has been seen on the screen. One Facebook never adds leaves
         * the paging list to say whether the viewer shows.
         */
        boolean headerShown;

        /** A viewer made its [made] header in this screen. */
        void viewer(View made) {
            header = new WeakReference<>(made);
            headerShown = false;
            madeAt = now();
            missingSince = -1;
            showing = true;
        }

        /** Whether the viewer runs under the gesture bar now. */
        boolean open() {
            return !stopped && !leftAlone && showing && switches();
        }

        @Override
        public boolean onPreDraw() {
            try {
                frame();
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, HOOK, failure);
                stop();
            }
            return true;
        }

        /** One frame: whether the viewer shows, the insets asked again on a change, and the lifts. */
        void frame() {
            View screen = content.get();
            if (screen == null || stopped) return;
            View head = header.get();
            View list = null;
            long at = now();
            boolean switchesOn = switches();
            if (head != null && head.isAttachedToWindow()) headerShown = true;
            if (!switchesOn || leftAlone || head == null) {
                showing = false;
            } else if (headerShown && !head.isAttachedToWindow()) {
                // The viewer's page went, another page in its place.
                showing = false;
            } else {
                list = pagingList(screen);
                if (list != null) {
                    missingSince = -1;
                    showing = true;
                } else {
                    if (missingSince < 0) missingSince = at;
                    if (at - madeAt >= GRACE_MS && at - missingSince >= MISSING_MS) showing = false;
                }
            }
            if (showing && list != null && answered && openedAt >= 0 && at - openedAt >= GRACE_MS
                    && rowsShort((ViewGroup) list)) {
                leftAlone = true;
                showing = false;
                HookStatus.counted(FAMILY, LEFT_ALONE);
                Logger.printDebug(() -> "Immersive edge: the reels stayed short of the taller list, the band comes back");
            }
            boolean want = showing && switchesOn && !leftAlone;
            if (want != asked) {
                asked = want;
                if (want) openedAt = at;
                screen.getRootView().requestApplyInsets();
                Logger.printDebug(() -> "Immersive edge: the band under the viewer " + (want ? "opens" : "closes")
                        + " as the window's insets come again");
            }
            if (!want || !answered || inset <= 0) {
                details.giveBack();
                pinned.giveBack();
                return;
            }
            int bottom = TranslucentBars.laidOutTop(screen) + screen.getHeight();
            int cover = bottom - inset;
            details.place(list, cover);
            pinned.place(screen, cover, bottom, inset);
        }

        /** Stops this screen's frames for good, its views back where Facebook has them. */
        void stop() {
            stopped = true;
            try {
                details.giveBack();
                pinned.giveBack();
                View screen = content.get();
                if (screen != null) {
                    screen.getViewTreeObserver().removeOnPreDrawListener(this);
                    if (answered) screen.getRootView().requestApplyInsets();
                }
            } catch (Throwable ignored) {
                // Already failing; HookStatus has the first failure.
            }
        }
    }

    /**
     * The views pinned to the viewer's bottom edge, lifted above the gesture bar: down through the
     * layers the viewer fills, past its paging list, each one under a third of the screen tall
     * whose laid-out bottom sits under the bar's top, and each taller one that hangs just past the
     * bottom edge, as 588's video controls do by 51 px with their seek bar ending at that edge.
     * A taller one ending at the bottom, a sheet Facebook sizes to the screen, and anything laid
     * out below the screen stay where Facebook has them.
     */
    static final class Lifts {
        /** A tall view is the controls only when it hangs past the bottom by under this part of the screen. */
        static final int HANG_PART = 20;

        /** Each view held, by the view: how far this has moved it up. */
        final Map<View, int[]> held = new WeakHashMap<>();
        private final Map<View, Integer> wanted = new IdentityHashMap<>();

        /** One frame over [content], [bottom] its bottom edge and [cover] the gesture bar's top. */
        void place(View content, int cover, int bottom, int inset) {
            wanted.clear();
            collect(content, content.getHeight(), cover, bottom, inset, 0);
            Iterator<Map.Entry<View, int[]>> each = held.entrySet().iterator();
            while (each.hasNext()) {
                Map.Entry<View, int[]> entry = each.next();
                View part = entry.getKey();
                if (part == null) continue;
                if (!wanted.containsKey(part) || !part.isAttachedToWindow()) {
                    move(part, entry.getValue(), 0);
                    each.remove();
                }
            }
            for (Map.Entry<View, Integer> entry : wanted.entrySet()) {
                int[] applied = held.get(entry.getKey());
                if (applied == null) {
                    applied = new int[1];
                    held.put(entry.getKey(), applied);
                    HookStatus.counted(FAMILY, LIFTED);
                }
                move(entry.getKey(), applied, entry.getValue());
            }
            wanted.clear();
        }

        private void collect(View view, int height, int cover, int bottom, int inset, int depth) {
            if (!(view instanceof ViewGroup) || depth > 24) return;
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE || child.getHeight() <= 0) continue;
                if (fills(child, height)) {
                    if (!TranslucentBars.isList(child)) collect(child, height, cover, bottom, inset, depth + 1);
                    continue;
                }
                int top = TranslucentBars.laidOutTop(child);
                int end = top + child.getHeight();
                if (end <= cover || top >= bottom) continue;
                if (child.getHeight() * 3L <= height) {
                    if (end > bottom + 2) continue;
                } else if (end <= bottom + 2 || end - bottom > height / HANG_PART) {
                    continue;
                }
                wanted.put(child, Math.min(end - cover, inset));
            }
        }

        /** Every view back where Facebook has it. */
        void giveBack() {
            if (held.isEmpty()) return;
            for (Map.Entry<View, int[]> entry : held.entrySet()) {
                if (entry.getKey() != null) move(entry.getKey(), entry.getValue(), 0);
            }
            held.clear();
        }

        /** Moves [part] up by [lift] in all, on top of whatever Facebook's own move is. */
        private static void move(View part, int[] applied, int lift) {
            ViewLift.move(part, lift);
            applied[0] = lift;
        }
    }

    static void resetForTests() {
        synchronized (EDGES) {
            EDGES.clear();
        }
        nowForTests = -1;
    }
}
