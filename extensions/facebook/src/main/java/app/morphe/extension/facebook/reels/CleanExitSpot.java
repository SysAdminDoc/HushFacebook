/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Puts Facebook's button out of Clean mode right where the sidebar's Clean mode button was (#391),
 * so the same spot turns Clean mode on and off.
 *
 * <p>The S22's Reels tab on 588 (2026-11-19) holds each reel in a LithoView page 1080x2041 under the
 * list of reels. With the sidebar showing, the Clean mode button sits at 945,1519-1080,1691 of the
 * page, seven hosts down. In Clean mode the sidebar is gone and the page's top host holds one small
 * clickable host, Facebook's way out, at 939,1726-1063,1850: in the same column, 183px lower. A reel
 * opened from a post lays its sidebar out another way, so no fixed offset fits every viewer.
 *
 * <p>So the sidebar button's tap ({@link #tapped}) keeps where it sat in its page, by the page's
 * size, and watches the list holding the page. Each frame, every page of that size whose top few
 * levels hold exactly one small clickable host in the page's lower right gets that host moved onto
 * the kept spot. A page showing its sidebar has none at those levels, and anything that stops
 * qualifying goes back to where Facebook had it. Off or paused, every host moved goes back.
 *
 * <p>Faint Clean mode button (#519): the way out sits over the reel's lower right, where a video's
 * own text often runs, and covered it. With the switch on, the list of every reel page the scrubber
 * mounts in ({@link #sawPage}, from {@link CaptionRoom}) or Burn-in guard finds playing on the Reels
 * screen ({@link #sawReel}) is watched the same way, and each way out
 * found draws at {@link #FAINT} of Facebook's opacity. It still takes taps. An alpha Facebook sets
 * since is its own again, faded from there, and off, paused or gone it goes back.
 */
final class CleanExitSpot {
    static final String HOOK = "Clean mode exit spot";
    /** Counted for each host moved onto the sidebar button's spot. */
    static final String MOVED = "Clean mode exit button moved to the sidebar button's spot";
    /** Counted for each way out made faint (#519). */
    static final String FAINTED = "Clean mode exit button made faint";
    /** How much of Facebook's opacity a faint way out keeps (#519). */
    static final float FAINT = 0.35f;

    /** How many levels under a page the way out is looked for. Two on 588's Reels tab. */
    static final int FIND_DEPTH = 3;
    /** The most page sizes a spot is kept for. */
    static final int MOST_SPOTS = 4;

    private static final String FAMILY = FamilyNames.REEL_DECLUTTER;

    /** The sidebar button's center in its page, by the page's size. */
    static final Map<Long, int[]> spots = new LinkedHashMap<Long, int[]>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, int[]> eldest) {
            return size() > MOST_SPOTS;
        }
    };
    /** The lists watched. */
    static final Map<View, Watch> watched = new WeakHashMap<>();
    /** Each host moved here: Facebook's own translation, then the one set here, x and y. */
    static final Map<View, float[]> moved = new WeakHashMap<>();
    /** Each way out made faint here: Facebook's own alpha, then the one set here (#519). */
    static final Map<View, float[]> fainted = new WeakHashMap<>();

    /** Faint Clean mode button is on (#519): off until the settings are read and while paused. */
    static boolean faintOn() {
        return Utils.settingsReady() && Settings.REEL_CLEAN_EXIT_FAINT.get();
    }

    /**
     * From {@link CaptionRoom} as it looks through a reel's page: with Faint Clean mode button on,
     * the list holding [page] is watched, so its ways out of Clean mode fade (#519). Never throws.
     */
    static void sawPage(@Nullable View page) {
        try {
            if (page == null || !faintOn() || !(page.getParent() instanceof ViewGroup)) return;
            watch((ViewGroup) page.getParent());
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
        }
    }

    /**
     * From Burn-in guard's look, with [surface] the video filling the Reels screen: its reel page goes
     * to {@link #sawPage}. The scrubber's mount hook never ran on 588's Reels tab (S22, 2026-11-23), so
     * this is what finds the page there. The page is the LithoView over [surface] that sits right in a
     * RecyclerView, or with none, the nearest one. Never throws.
     */
    static void sawReel(@Nullable View surface) {
        try {
            if (surface == null || !faintOn()) return;
            View nearest = null;
            for (ViewParent up = surface.getParent(); up instanceof View; up = up.getParent()) {
                View view = (View) up;
                if (!CaptionRoom.PAGE.equals(view.getClass().getName())) continue;
                if (nearest == null) nearest = view;
                if (view.getParent() instanceof View && PostLongPress.isNamed((View) view.getParent(), PostLongPress.LIST)) {
                    sawPage(view);
                    return;
                }
            }
            sawPage(nearest);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
        }
    }

    private CleanExitSpot() {
    }

    /** From the sidebar button's tap, with the button. Never throws. */
    static void tapped(@Nullable View button) {
        try {
            if (button == null) return;
            View page = CaptionRoom.pageOf(button);
            if (!(page instanceof ViewGroup) || !(page.getParent() instanceof ViewGroup)) {
                Logger.printDebug(() -> "Reel Clean mode: the sidebar button is in no reel page");
                return;
            }
            int[] spot = centerIn(button, page);
            if (!inTheCorner(spot, page) || button.getWidth() * 4 > page.getWidth()) {
                Logger.printDebug(() -> "Reel Clean mode: the tapped view isn't the sidebar button");
                return;
            }
            spots.put(sizeOf(page), spot);
            Logger.printDebug(() -> "Reel Clean mode: the sidebar button sat at " + spot[0] + "," + spot[1]
                    + " of a " + page.getWidth() + "x" + page.getHeight() + " page");
            watch((ViewGroup) page.getParent());
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
        }
    }

    private static void watch(ViewGroup list) {
        if (watched.containsKey(list)) return;
        Watch watch = new Watch(list);
        watched.put(list, watch);
        list.addOnAttachStateChangeListener(watch);
        if (list.isAttachedToWindow()) watch.onViewAttachedToWindow(list);
    }

    /** Draws each frame of one list's window while the list is in it. */
    static final class Watch implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        private final WeakReference<ViewGroup> held;
        @Nullable private ViewTreeObserver tree;

        Watch(ViewGroup list) {
            held = new WeakReference<>(list);
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            stop();
            tree = view.getViewTreeObserver();
            if (tree.isAlive()) tree.addOnPreDrawListener(this);
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            stop();
            if (view instanceof ViewGroup) place((ViewGroup) view, false, false);
        }

        private void stop() {
            if (tree != null && tree.isAlive()) tree.removeOnPreDrawListener(this);
            tree = null;
        }

        @Override
        public boolean onPreDraw() {
            try {
                ViewGroup list = held.get();
                if (list == null) {
                    stop();
                    return true;
                }
                place(list, ReelCleanMode.sidebarButtonOn(), faintOn());
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, HOOK, failure);
            }
            return true;
        }
    }

    /**
     * One frame of [list]: with [on], each of its pages with a kept spot and one way out has it moved
     * there, and with [faint] each page's one way out fades (#519). Every other host moved or faded
     * in those pages goes back. Answers how many are moved.
     */
    static int place(ViewGroup list, boolean on, boolean faint) {
        Map<View, int[]> shifts = new HashMap<>();
        List<View> exits = new ArrayList<>();
        List<View> pages = new ArrayList<>();
        for (int i = 0; i < list.getChildCount(); i++) {
            View page = list.getChildAt(i);
            if (!CaptionRoom.PAGE.equals(page.getClass().getName())) continue;
            pages.add(page);
            int[] spot = on ? spots.get(sizeOf(page)) : null;
            View exit = spot == null && !faint ? null : exitIn((ViewGroup) page);
            if (exit == null) continue;
            if (faint) exits.add(exit);
            if (spot == null) continue;
            int[] center = centerIn(exit, page);
            shifts.put(exit, new int[] {spot[0] - center[0], spot[1] - center[1]});
        }
        Iterator<Map.Entry<View, float[]>> faded = fainted.entrySet().iterator();
        while (faded.hasNext()) {
            Map.Entry<View, float[]> entry = faded.next();
            View host = entry.getKey();
            if (host == null || exits.contains(host)) continue;
            if (host.isAttachedToWindow() && !inAny(host, pages)) continue;
            if (host.getAlpha() == entry.getValue()[1]) host.setAlpha(entry.getValue()[0]);
            faded.remove();
        }
        for (View exit : exits) fade(exit);
        Iterator<Map.Entry<View, float[]>> each = moved.entrySet().iterator();
        while (each.hasNext()) {
            Map.Entry<View, float[]> entry = each.next();
            View host = entry.getKey();
            if (host == null || shifts.containsKey(host)) continue;
            if (host.isAttachedToWindow() && !inAny(host, pages)) continue;
            giveBack(host, entry.getValue());
            each.remove();
        }
        for (Map.Entry<View, int[]> shift : shifts.entrySet()) {
            move(shift.getKey(), shift.getValue()[0], shift.getValue()[1]);
        }
        return shifts.size();
    }

    /**
     * The way out of Clean mode in [page]: the one visible, clickable Litho host within {@link
     * #FIND_DEPTH} levels that's at most a fifth of the page wide and tall, centered in its right
     * three tenths and lower half. Null when there's none or more than one.
     */
    @Nullable
    static View exitIn(ViewGroup page) {
        List<View> found = new ArrayList<>();
        look(page, page, 1, found);
        return found.size() == 1 ? found.get(0) : null;
    }

    private static void look(ViewGroup group, View page, int depth, List<View> found) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (child.isClickable() && CaptionRoom.HOST.equals(child.getClass().getName())
                    && child.getWidth() > 0 && child.getHeight() > 0
                    && child.getWidth() * 5 <= page.getWidth() && child.getHeight() * 5 <= page.getWidth()
                    && inTheCorner(centerIn(child, page), page)) {
                found.add(child);
                continue;
            }
            if (depth < FIND_DEPTH && child instanceof ViewGroup) look((ViewGroup) child, page, depth + 1, found);
        }
    }

    /** Whether [center] is in [page]'s right three tenths and lower half. */
    private static boolean inTheCorner(int[] center, View page) {
        return center[0] * 10 >= page.getWidth() * 7 && center[0] <= page.getWidth()
                && center[1] * 2 >= page.getHeight() && center[1] <= page.getHeight();
    }

    /** [view]'s center where its page lays it out, before any translation. */
    static int[] centerIn(View view, View page) {
        int x = view.getWidth() / 2;
        int y = view.getHeight() / 2;
        View at = view;
        while (at != page && at != null) {
            x += at.getLeft();
            y += at.getTop();
            at = at.getParent() instanceof View ? (View) at.getParent() : null;
        }
        return new int[] {x, y};
    }

    private static boolean inAny(View host, List<View> pages) {
        View page = CaptionRoom.pageOf(host);
        return page != null && pages.contains(page);
    }

    private static long sizeOf(View page) {
        return ((long) page.getWidth() << 32) | (page.getHeight() & 0xFFFFFFFFL);
    }

    /** Moves [host] by [dx], [dy] from where Facebook has it, keeping any move Facebook made since. */
    private static void move(View host, int dx, int dy) {
        float[] held = moved.get(host);
        if (held == null) {
            held = new float[] {host.getTranslationX(), host.getTranslationY(), Float.NaN, Float.NaN};
            moved.put(host, held);
            HookStatus.bound(FAMILY, HOOK);
            HookStatus.counted(FAMILY, MOVED);
            Logger.printDebug(() -> "Reel Clean mode: moved the way out by " + dx + "," + dy);
        } else {
            if (host.getTranslationX() != held[2]) held[0] = host.getTranslationX();
            if (host.getTranslationY() != held[3]) held[1] = host.getTranslationY();
        }
        held[2] = held[0] + dx;
        held[3] = held[1] + dy;
        if (host.getTranslationX() != held[2]) host.setTranslationX(held[2]);
        if (host.getTranslationY() != held[3]) host.setTranslationY(held[3]);
    }

    /** Draws [host] at {@link #FAINT} of Facebook's alpha, taking an alpha Facebook set since as its own. */
    private static void fade(View host) {
        float[] held = fainted.get(host);
        float now = host.getAlpha();
        if (held != null && now == held[1]) return;
        if (held == null) {
            held = new float[2];
            fainted.put(host, held);
            HookStatus.bound(FAMILY, HOOK);
            HookStatus.counted(FAMILY, FAINTED);
        }
        held[0] = now;
        held[1] = now * FAINT;
        host.setAlpha(held[1]);
    }

    /** [host] back where Facebook has it, unless Facebook moved it since. */
    private static void giveBack(View host, float[] held) {
        if (host.getTranslationX() == held[2]) host.setTranslationX(held[0]);
        if (host.getTranslationY() == held[3]) host.setTranslationY(held[1]);
    }

    /** Forgets every spot, list and move, for tests. */
    static void reset() {
        spots.clear();
        watched.clear();
        moved.clear();
        fainted.clear();
    }
}
