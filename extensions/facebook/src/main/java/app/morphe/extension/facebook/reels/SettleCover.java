/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Black over a window the Saved chain opened, so nothing between one saved video and the next
 * shows (#404).
 *
 * <p>Facebook lays a new Reels viewer's video out fitted to the width under the status bar, then a
 * moment later fills the screen with it, up under the bar (S22 2026-11-22, a frame-by-frame
 * recording of a swipe). Opening one saved video by hand, that's barely seen. The chain opens a new
 * viewer for every saved video, though, so each swipe ended with the video jumping, the shake a
 * reporter saw. So the viewer the chain opens stays black until its video has stopped moving: it
 * shows {@link #MOVED_HOLD_MS} after Facebook's last move of it, or after {@link #HOLD_MS} when
 * Facebook never moves it, and never later than {@link #MAX_MS}. Facebook can lay the video out more
 * than once on its way to the full screen, so each move starts that short hold over. A saved post the
 * chain is about to close gets the same black from its first frame. Main thread only.
 */
final class SettleCover {
    /** How long a video may hold its first place before it shows anyway, on a phone where Facebook doesn't move it. */
    static final long HOLD_MS = 700;

    /** How long a video must stay put after Facebook moved it before it shows. */
    static final long MOVED_HOLD_MS = 200;

    /** The longest a screen the chain opened stays black, whatever happens. */
    static final long MAX_MS = 2500;

    /** Bigger than any screen, so the black covers the window whatever size it's laid out at. */
    private static final int BIG = 1 << 15;

    /** The black on each window that has one. */
    private static final Map<View, Drawable> covers = new WeakHashMap<>();

    private SettleCover() {
    }

    /** Lays black over the window whose top view is [root], over everything it draws. */
    static void blackOut(View root) {
        if (covers.containsKey(root)) return;
        ColorDrawable black = new ColorDrawable(Color.BLACK);
        black.setBounds(0, 0, BIG, BIG);
        root.getOverlay().add(black);
        covers.put(root, black);
    }

    /** Takes the black off [root]'s window, if it has some. */
    static void lift(@Nullable View root) {
        if (root == null) return;
        Drawable black = covers.remove(root);
        if (black != null) root.getOverlay().remove(black);
    }

    static boolean blackedOut(View root) {
        return covers.containsKey(root);
    }

    /** Keeps [screen] black until its video has settled. */
    static void untilSettled(Activity screen) {
        Window window = screen.getWindow();
        if (window == null) return;
        View decor = window.getDecorView();
        blackOut(decor);
        Watch watch = new Watch(decor, SavedChain.now());
        decor.getViewTreeObserver().addOnPreDrawListener(watch);
        Utils.runOnMainThreadDelayed(watch::check, MAX_MS);
    }

    /** Looks at a covered screen's video as it draws, and lifts the black once it's in place. */
    static final class Watch implements ViewTreeObserver.OnPreDrawListener {
        private final WeakReference<View> decor;
        private final long since;
        private WeakReference<View> video = new WeakReference<>(null);
        @Nullable private int[] place;
        private long placedAt;
        private boolean moved;
        private boolean done;

        Watch(View decor, long since) {
            this.decor = new WeakReference<>(decor);
            this.since = since;
        }

        @Override
        public boolean onPreDraw() {
            check();
            return true;
        }

        void check() {
            try {
                if (done) return;
                View root = decor.get();
                if (root == null || !blackedOut(root)) {
                    stop(root);
                    return;
                }
                long at = SavedChain.now();
                if (at - since >= MAX_MS) {
                    reveal(root, "after the longest wait", at);
                    return;
                }
                View found = biggestVideo(root);
                if (found == null) return;
                int[] now = placeOf(found);
                if (place == null) {
                    place = now;
                    placedAt = at;
                    video = new WeakReference<>(found);
                    Utils.runOnMainThreadDelayed(this::check, HOLD_MS);
                    return;
                }
                if (found != video.get() || !Arrays.equals(place, now)) {
                    int[] was = place;
                    Logger.printDebug(() -> "Saved videos in a row: the next video moved from " + where(was) + " to "
                            + where(now) + ", " + (at - since) + " ms after it opened");
                    // Facebook can lay it out more than once on its way to the full screen, a frame or
                    // two apart (S22 2026-11-22), so each move starts a short hold over.
                    place = now;
                    placedAt = at;
                    moved = true;
                    video = new WeakReference<>(found);
                    Utils.runOnMainThreadDelayed(this::check, MOVED_HOLD_MS);
                } else if (at - placedAt >= (moved ? MOVED_HOLD_MS : HOLD_MS)) {
                    reveal(root, moved ? "once it stopped moving" : "after it held its place", at);
                }
            } catch (Throwable failure) {
                View root = decor.get();
                lift(root);
                stop(root);
                Logger.printException(() -> "Saved videos in a row: the next video's cover failed", failure);
            }
        }

        private void reveal(View root, String why, long at) {
            lift(root);
            stop(root);
            Logger.printDebug(() -> "Saved videos in a row: the next video showed " + why + ", "
                    + (at - since) + " ms after it opened");
        }

        private void stop(@Nullable View root) {
            done = true;
            if (root == null) return;
            ViewTreeObserver observer = root.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(this);
        }
    }

    /** The video view taking up the most of [root]'s window, or null when none shows. */
    @Nullable
    static View biggestVideo(View root) {
        Best best = new Best();
        find(root, best, new Rect());
        return best.view;
    }

    private static final class Best {
        @Nullable View view;
        long area;
    }

    private static void find(View view, Best best, Rect visible) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextureView || view instanceof SurfaceView) {
            if (view.getWidth() > 0 && view.getHeight() > 0 && view.getGlobalVisibleRect(visible)) {
                long area = (long) visible.width() * visible.height();
                if (area > best.area) {
                    best.area = area;
                    best.view = view;
                }
            }
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) find(group.getChildAt(i), best, visible);
    }

    private static String where(int[] place) {
        return place[2] + "x" + place[3] + " at " + place[0] + "," + place[1];
    }

    /** Where [video] sits in its window and how big it is. */
    private static int[] placeOf(View video) {
        int[] at = new int[2];
        video.getLocationInWindow(at);
        return new int[]{at[0], at[1], video.getWidth(), video.getHeight()};
    }
}
