/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.SeekBar;

import androidx.annotation.Nullable;

import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Facebook's own video controls, kept above the tab bar while pages run under it (#412). The Reels
 * tab on the S22 (585, 2026-11-08) shows a reel's time and seek bar in a host pinned to the bottom
 * of the viewer, beside the list of reels rather than in a reel's page:
 *
 * <pre>
 *   ViewGroup 0,75-1080,2316                the viewer
 *     ViewGroup 0,75-1080,2316              filling it
 *       RecyclerView 0,75-1080,2316         the list of reels
 *     RelativeLayout 0,1261-1080,2316       the controls' host
 *       RelativeLayout 0,1261-1080,2316
 *         LinearLayout 0,2179-291,2316      the time
 *         LinearLayout 0,2245-1080,2316
 *           SeekBar 0,2248-1080,2316
 * </pre>
 *
 * <p>Running the page under the tab bar takes the viewer's bottom, and the host with it, from the
 * tab bar's top down to the window's, so the time, the bar and its gray replay graph sat behind the
 * pill, played or paused. Each frame, every host held is moved up by as much as its bottom is under
 * the top of the tab bar's rows on screen, which is where Facebook had it before the stretch, and
 * it follows the bar as it slides away and back.
 *
 * <p>A host is the SeekBar's ancestor, a few levels up, whose parent is a viewer at least three
 * quarters of the window tall: the host is under two thirds of the viewer's height, doesn't scroll,
 * and sits beside a view that fills the viewer. A video in the feed sits in a player much shorter
 * than that, and the feed's rows don't fill their list, so feed videos keep their controls.
 */
final class ControlsLift {
    static final String LIFTED = "Video controls lifted above the tab bar";
    /** How far above a SeekBar its host may be. Three levels on 585. */
    static final int LEVELS_UP = 6;
    /** How deep under the page a SeekBar is looked for. Ten levels on 585. */
    static final int FIND_DEPTH = 24;
    private static final String FAMILY = FamilyNames.BOTTOM_TAB_BAR;

    /** Each host held, by the host: how far this has moved it up. */
    final Map<View, int[]> held = new WeakHashMap<>();
    private final int[] origin = new int[2];

    /**
     * Looks under [page] for a host whose bottom is under [edge], the tab bar's laid-out top, in a
     * window [height] tall, and holds each new one.
     */
    void find(@Nullable View page, int edge, int height) {
        if (page == null || height <= 0) return;
        page.getLocationInWindow(origin);
        search(page, origin[0], origin[1], edge, page.getRootView().getWidth(), height, 0);
    }

    /** [view] at [left], [top] in the window: views wholly above the edge or off the screen's sides are passed by. */
    private void search(View view, int left, int top, int edge, int width, int height, int depth) {
        if (view.getVisibility() != View.VISIBLE || view.getHeight() <= 0) return;
        if (top + view.getHeight() <= edge || left + view.getWidth() <= 0 || left >= width) return;
        if (view instanceof SeekBar) {
            View host = hostOf(view, height);
            if (host != null && !held.containsKey(host)) {
                held.put(host, new int[1]);
                Logger.printDebug(() -> "See-through bars: holding video controls above the tab bar, " + host.getClass().getName());
            }
            return;
        }
        if (depth >= FIND_DEPTH || !(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            search(child, left + child.getLeft() - group.getScrollX(), top + child.getTop() - group.getScrollY(),
                    edge, width, height, depth + 1);
        }
    }

    /** The host [bar] is pinned in, in a window [height] tall, or null when it isn't in a whole-screen viewer. */
    @Nullable
    static View hostOf(View bar, int height) {
        View up = bar;
        for (int level = 0; level < LEVELS_UP; level++) {
            ViewParent above = up.getParent();
            if (!(above instanceof ViewGroup)) return null;
            ViewGroup viewer = (ViewGroup) above;
            if (viewer.getHeight() * 4 >= height * 3) {
                boolean pinned = up.getHeight() * 3 < viewer.getHeight() * 2 && !up.canScrollVertically(1)
                        && !up.canScrollVertically(-1) && besideAFill(viewer, up);
                return pinned ? up : null;
            }
            up = viewer;
        }
        return null;
    }

    private static boolean besideAFill(ViewGroup viewer, View host) {
        for (int i = 0; i < viewer.getChildCount(); i++) {
            View child = viewer.getChildAt(i);
            if (child != host && child.getVisibility() == View.VISIBLE
                    && child.getWidth() == viewer.getWidth() && child.getHeight() == viewer.getHeight()) {
                return true;
            }
        }
        return false;
    }

    /**
     * One frame: each host moved up by as much as its bottom, where it's laid out, is under [cover],
     * the top of the tab bar's rows on screen, or {@link Integer#MAX_VALUE} with no rows there.
     */
    void place(int cover) {
        if (held.isEmpty()) return;
        Iterator<Map.Entry<View, int[]>> each = held.entrySet().iterator();
        while (each.hasNext()) {
            Map.Entry<View, int[]> entry = each.next();
            View host = entry.getKey();
            int[] applied = entry.getValue();
            if (host == null) continue;
            if (!host.isAttachedToWindow()) {
                move(host, applied, 0);
                each.remove();
                continue;
            }
            long under = (long) TranslucentBars.laidOutTop(host) + host.getHeight() - cover;
            int lift = (int) Math.max(0, under);
            if (lift != 0 && applied[0] == 0) HookStatus.counted(FAMILY, LIFTED);
            move(host, applied, lift);
        }
    }

    /** Every host back where Facebook has it. */
    void giveBack() {
        if (held.isEmpty()) return;
        for (Map.Entry<View, int[]> entry : held.entrySet()) {
            if (entry.getKey() != null) move(entry.getKey(), entry.getValue(), 0);
        }
        held.clear();
    }

    /** Moves [host] up by [lift] in all, on top of whatever Facebook's own move is. */
    private static void move(View host, int[] applied, int lift) {
        if (lift == applied[0]) return;
        host.setTranslationY(host.getTranslationY() + applied[0] - lift);
        applied[0] = lift;
    }
}
