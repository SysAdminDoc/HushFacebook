/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.app.Activity;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.facebook.media.PinchZoom;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * A pinch on a reel while Fill the screen with reels is on (#466). Pinch in and the reel under your
 * fingers shows its whole picture again, with bars where it's short. Pinch out and it fills the
 * screen, which also works on a reel Facebook kept whole because filling cuts more than
 * {@link ReelFill#MOST_CUT}. The reel goes back to the switch's way once it's off the screen, so the
 * next one fills as usual.
 *
 * <p>When a reel fills, Facebook sizes its video view bigger than the reel and centers it
 * ({@link ReelFill}). Scaling that view down by how far it hangs over shows all of it, and scaling a
 * whole one up until it reaches every edge fills it. The scale is worked out again each time
 * Facebook lays the view out anew, such as after the comments close, and goes back to 1 once the
 * view is off the screen or moves to another reel.
 *
 * <p>This gets the activity's first look at each touch. Once two fingers on a reel have spread or
 * closed far enough to count, and the pinch changes how the reel shows, Facebook gets a cancel and
 * none of the rest of that gesture. Until then it sees everything. Off, paused, settings that aren't
 * ready, the story viewer (Pinch to zoom has that one) or a failure here, and the touch goes to
 * Facebook as it was.
 */
public final class ReelFitPinch {
    /** The hook's name in the diagnostics report. */
    static final String HOOK = "Pinch a reel to fit or fill";

    /** Counted each time a pinch changes how a reel shows. */
    static final String PINCHED = "Reel pinched to fit or fill";

    /** How far the fingers' spread has to close, against where it started, to show the whole reel. */
    static final float PINCH_IN = 0.8f;

    /** How far it has to open to fill the screen. */
    static final float PINCH_OUT = 1.25f;

    /** A scale closer than this to the one a view has is no change. */
    static final float SAME = 0.01f;

    /** How long the reel takes to settle at its new size, in milliseconds. */
    static final long SETTLE_MS = 180;

    /** The video view two fingers came down on, until the pinch counts or the fingers lift. */
    @Nullable private static WeakReference<View> candidate;
    private static float startSpan;
    /** True from the event Facebook got as a cancel to the end of that gesture. */
    private static boolean taken;
    /** The reel a pinch changed, while it's still on the screen. */
    @Nullable private static Kept kept;

    private ReelFitPinch() {
    }

    /**
     * Injection point, first thing in FbFragmentActivity.dispatchTouchEvent. True keeps {@code event}
     * from Facebook, and the dispatch returns true without handing it on. Never throws.
     */
    public static boolean touch(@Nullable Activity activity, @Nullable MotionEvent event) {
        try {
            if (activity == null || event == null) return false;
            return handle(activity, event);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, HOOK, failure);
            candidate = null;
            // A gesture Facebook already had a cancel for stays away from it to its end.
            return taken;
        }
    }

    /** True when Facebook shouldn't get {@code event} at all. */
    static boolean handle(Activity activity, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            taken = false;
            candidate = null;
            Kept last = kept;
            if (last != null) last.check(false);
            return false;
        }
        if (taken) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) taken = false;
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            candidate = null;
            if (event.getPointerCount() != 2) return false;
            if (!Utils.settingsReady() || !Settings.FILL_REELS.get()) return false;
            if (PinchZoom.storyViewer(activity)) return false;
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            HookStatus.bound(FamilyNames.REEL_DECLUTTER, HOOK);
            Window window = activity.getWindow();
            if (window == null) return false;
            View root = window.getDecorView();
            float x = (event.getX(0) + event.getX(1)) / 2;
            float y = (event.getY(0) + event.getY(1)) / 2;
            View video = videoUnder(root, x, y, root.getWidth(), root.getHeight());
            float span = span(event);
            if (video == null || span < 1) return false;
            candidate = new WeakReference<>(video);
            startSpan = span;
            return false;
        }
        if (action == MotionEvent.ACTION_MOVE && candidate != null && event.getPointerCount() >= 2) {
            float spread = span(event) / startSpan;
            boolean fit;
            if (spread <= PINCH_IN) fit = true;
            else if (spread >= PINCH_OUT) fit = false;
            else return false;
            View video = candidate.get();
            candidate = null;
            if (video == null || Math.abs(target(video, fit) - video.getScaleX()) < SAME) return false;
            pinch(video, fit);
            HookStatus.counted(FamilyNames.REEL_DECLUTTER, PINCHED);
            Logger.printDebug(() -> "Reel fit pinch: " + (fit ? "whole picture" : "filling the screen"));
            taken = true;
            event.setAction(MotionEvent.ACTION_CANCEL);
            return false;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) candidate = null;
        return false;
    }

    /** Shows {@code video} whole ({@code fit}) or filling its reel, and keeps it that way while it's on the screen. */
    static void pinch(View video, boolean fit) {
        Kept last = kept;
        if (last != null && last.view.get() != video) last.back();
        Kept now = kept;
        if (now == null) {
            now = new Kept(video);
            kept = now;
        }
        now.fit = fit;
        now.show(video, true);
    }

    /**
     * The scale that shows {@code video} whole ({@code fit}) or filling its parent, from the sizes
     * Facebook laid them out at. Never shrinks to fill or grows to fit, so a reel already that way
     * gets 1.
     */
    static float target(View video, boolean fit) {
        ViewParent parent = video.getParent();
        if (!(parent instanceof View)) return 1f;
        float width = video.getWidth();
        float height = video.getHeight();
        float across = ((View) parent).getWidth();
        float down = ((View) parent).getHeight();
        if (width <= 0 || height <= 0 || across <= 0 || down <= 0) return 1f;
        return fit
                ? Math.min(1f, Math.min(across / width, down / height))
                : Math.max(1f, Math.max(across / width, down / height));
    }

    /**
     * The video surface, a SurfaceView or TextureView, that's shown at {@code x}, {@code y} in a
     * reel about the size of a {@code screenWidth} by {@code screenHeight} window, or null. Its
     * parent is the reel, since the view itself hangs over it when it fills.
     */
    @Nullable
    static View videoUnder(View view, float x, float y, int screenWidth, int screenHeight) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View found = videoUnder(group.getChildAt(i), x, y, screenWidth, screenHeight);
                if (found != null) return found;
            }
            return null;
        }
        if (!(view instanceof SurfaceView) && !(view instanceof TextureView)) return null;
        if (!(view.getParent() instanceof View)) return null;
        View reel = (View) view.getParent();
        int width = reel.getWidth();
        int height = reel.getHeight();
        if (!ReelFill.fullScreen(height > width, width, height, screenWidth, screenHeight)) return null;
        Rect shown = new Rect();
        return view.getGlobalVisibleRect(shown) && shown.contains((int) x, (int) y) ? view : null;
    }

    private static float span(MotionEvent event) {
        float across = event.getX(0) - event.getX(1);
        float down = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(across * across + down * down);
    }

    /** Whether a pinch is keeping {@code video} its way. For tests. */
    static boolean keeping(View video) {
        Kept now = kept;
        return now != null && now.view.get() == video;
    }

    static void resetForTests() {
        Kept now = kept;
        if (now != null) now.drop();
        kept = null;
        candidate = null;
        taken = false;
        startSpan = 0;
    }

    /**
     * A pinched reel's view, held weakly, and the parent it had then. Each layout of the view works
     * its scale out again, and a layout or scroll that finds it gone, off the screen or in another
     * parent sets it back to 1 and lets it go.
     */
    private static final class Kept implements View.OnLayoutChangeListener, ViewTreeObserver.OnScrollChangedListener {
        final WeakReference<View> view;
        final WeakReference<ViewParent> parent;
        final WeakReference<ViewTreeObserver> observer;
        boolean fit;

        Kept(View video) {
            view = new WeakReference<>(video);
            parent = new WeakReference<>(video.getParent());
            ViewTreeObserver tree = video.getViewTreeObserver();
            observer = new WeakReference<>(tree);
            video.addOnLayoutChangeListener(this);
            tree.addOnScrollChangedListener(this);
        }

        void show(View video, boolean settle) {
            float scale = target(video, fit);
            video.animate().cancel();
            if (settle) {
                video.animate().scaleX(scale).scaleY(scale).setDuration(SETTLE_MS).start();
            } else {
                video.setScaleX(scale);
                video.setScaleY(scale);
            }
        }

        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                   int oldLeft, int oldTop, int oldRight, int oldBottom) {
            check(true);
        }

        @Override
        public void onScrollChanged() {
            check(false);
        }

        /** After a layout ({@code laidOut}), a scroll or a new touch: still the pinched reel, on the screen? */
        void check(boolean laidOut) {
            try {
                View video = view.get();
                if (video == null) {
                    drop();
                    return;
                }
                if (!video.isAttachedToWindow() || video.getParent() != parent.get() || !onScreen(video)
                        || !Utils.settingsReady() || !Settings.FILL_REELS.get()) {
                    back();
                    return;
                }
                if (laidOut) show(video, false);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REEL_DECLUTTER, HOOK, failure);
                back();
            }
        }

        /** Sets the view back to its own size and lets it go. */
        void back() {
            View video = view.get();
            if (video != null) {
                video.animate().cancel();
                video.setScaleX(1f);
                video.setScaleY(1f);
            }
            drop();
        }

        void drop() {
            View video = view.get();
            if (video != null) video.removeOnLayoutChangeListener(this);
            ViewTreeObserver tree = observer.get();
            if (tree != null && tree.isAlive()) tree.removeOnScrollChangedListener(this);
            if (kept == this) kept = null;
        }

        private static boolean onScreen(View video) {
            Rect shown = new Rect();
            return video.getGlobalVisibleRect(shown) && !shown.isEmpty();
        }
    }
}
