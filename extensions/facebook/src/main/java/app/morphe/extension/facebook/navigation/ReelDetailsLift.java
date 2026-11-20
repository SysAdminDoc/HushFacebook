/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Each reel's video runs under the tab bar and the gesture bar while its name, caption and buttons
 * stay above the bar (#466). See-through bars and Rounded tab bar run the Reels page down under the
 * tab bar, and a reel's row fills that whole page ({@link TabBarScrollAway#reelRow}), so its video
 * shows through the bar down to the screen's bottom edge. Seen on the S22 with 588 (2026-11-19), a
 * row is a LithoView whose first host with more than one child holds the video beside the rest:
 *
 * <pre>
 *   LithoView 0,0-1080,2241                 the reel's row
 *     ComponentHost 0,0-1080,2241
 *       ComponentHost 0,0-1080,2241         the first one with more than one child
 *         ComponentHost 0,0-1080,2241       tap layers, the whole row
 *         ComponentHost 0,-142-1080,2241    the details: name, caption, buttons down the side
 *         X.05kO 0,0-1080,2241              the video's holder
 *           SurfaceView 0,0-1149,2241
 * </pre>
 *
 * <p>Each frame, every child there with a button smaller than a quarter of the row, and no video
 * under it, moves up by as much as the row's bottom is under the top of the tab bar's rows on
 * screen once the row is paged in at the list's top, wherever it is now, so a reel's details travel
 * with it through a swipe. A row shorter than the list, one Facebook's height was left on, ends
 * above the bar and isn't moved. The tap layers, the video and its cover stay where Facebook has
 * them. The details ride with the bar as it slides away and back, and a part no longer found goes
 * back where Facebook had it.
 */
final class ReelDetailsLift {
    /** Counted each time a reel's details are lifted above the tab bar. */
    static final String LIFTED = "Reel details lifted above the tab bar";

    /** How many single-child levels down a row its first branch may be. Two on 588. */
    static final int BRANCH_DEPTH = 6;

    /** How deep under a part a button is looked for. Six levels on 588. */
    static final int BUTTON_DEPTH = 8;

    /** How deep under a part a video surface is looked for. Two levels on 588. */
    static final int VIDEO_DEPTH = 4;

    private static final String FAMILY = FamilyNames.BOTTOM_TAB_BAR;

    /** Each part held, by the part: how far this has moved it up. */
    final Map<View, int[]> held = new WeakHashMap<>();

    /** The parts this frame lifts and by how much, reused from frame to frame. */
    private final Map<View, Integer> wanted = new IdentityHashMap<>();

    /**
     * One frame: the details of each reel in [list], the Reels list, moved up by as much as their
     * row's bottom is under [cover], the top of the tab bar's rows on screen, once the row is paged
     * in, or {@link Integer#MAX_VALUE} with no rows there. A null list or one not shown gives every
     * part back.
     */
    void place(@Nullable View list, int cover) {
        wanted.clear();
        if (list instanceof ViewGroup && cover != Integer.MAX_VALUE && list.isShown()) {
            ViewGroup rows = (ViewGroup) list;
            int top = TranslucentBars.laidOutTop(list) + list.getPaddingTop();
            int bottom = TranslucentBars.laidOutTop(list) + list.getHeight() - list.getPaddingBottom();
            for (int i = 0; i < rows.getChildCount(); i++) {
                View row = rows.getChildAt(i);
                if (row.getVisibility() != View.VISIBLE || row.getHeight() <= 0) continue;
                int lift = Math.max(0, Math.min(top + row.getHeight(), bottom) - cover);
                if (lift == 0) continue;
                ViewGroup branch = branchOf(row);
                if (branch == null) continue;
                for (int k = 0; k < branch.getChildCount(); k++) {
                    View part = branch.getChildAt(k);
                    if (lifts(part, branch)) wanted.put(part, lift);
                }
            }
        }
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

    /** Every part back where Facebook has it. */
    void giveBack() {
        if (held.isEmpty()) return;
        for (Map.Entry<View, int[]> entry : held.entrySet()) {
            if (entry.getKey() != null) move(entry.getKey(), entry.getValue(), 0);
        }
        held.clear();
    }

    /** The first view down from [row] with more than one child, through single-child hosts only, or null. */
    @Nullable
    static ViewGroup branchOf(View row) {
        View at = row;
        for (int depth = 0; depth <= BRANCH_DEPTH && at instanceof ViewGroup; depth++) {
            ViewGroup group = (ViewGroup) at;
            if (group.getChildCount() > 1) return group;
            if (group.getChildCount() == 0) return null;
            at = group.getChildAt(0);
        }
        return null;
    }

    /** Whether [part], a child of [branch], is details to lift: shown, with a small button and no video under it. */
    static boolean lifts(View part, ViewGroup branch) {
        if (part.getVisibility() != View.VISIBLE || part.getHeight() <= 0) return false;
        long quarter = (long) branch.getWidth() * branch.getHeight() / 4;
        return holdsButton(part, quarter, BUTTON_DEPTH) && !holdsVideo(part, VIDEO_DEPTH);
    }

    /** Whether [view] is or holds, within [depth] levels, a shown clickable view smaller than [area]. */
    static boolean holdsButton(View view, long area, int depth) {
        if (view.getVisibility() != View.VISIBLE) return false;
        if (view.isClickable() && (long) view.getWidth() * view.getHeight() < area && view.getWidth() > 0) return true;
        if (depth <= 0 || !(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (holdsButton(group.getChildAt(i), area, depth - 1)) return true;
        }
        return false;
    }

    /** Whether [view] is or holds, within [depth] levels, a video surface. */
    static boolean holdsVideo(View view, int depth) {
        if (view instanceof SurfaceView || view instanceof TextureView) return true;
        if (depth <= 0 || !(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (holdsVideo(group.getChildAt(i), depth - 1)) return true;
        }
        return false;
    }

    /** Moves [part] up by [lift] in all, on top of whatever Facebook's own move is. */
    private static void move(View part, int[] applied, int lift) {
        ViewLift.move(part, lift);
        applied[0] = lift;
    }
}
