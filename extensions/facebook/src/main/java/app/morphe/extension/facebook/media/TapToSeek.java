/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.SeekBar;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Tap the bar to seek (#125): a tap anywhere on a reel's or video's progress bar jumps there.
 *
 * <p>Facebook's bar (582 {@code LX/9A0}) runs one of two ways, and a server setting picks which
 * per account. Thumb-only turns away a press that misses the thumb. Drag-anywhere hands every
 * touch to Android's SeekBar, but a tap there still doesn't move the video, since Facebook passes
 * it on to the video as a tap. A drag that starts on the thumb seeks either way, so that's what a
 * tap becomes here.
 *
 * <p>The patch asks {@link #takesTouch} first in the bar's touch handler. A press that misses the
 * thumb is held. If it lifts without moving, Facebook's handler is played a drag from the thumb to
 * that spot. If it moves sideways, the handler gets a press on the thumb and then the real moves,
 * so the thumb follows the finger. A vertical swipe is left alone: the reel pager takes it from
 * the bar as it always did. A press on the thumb or on a button under the bar's touch area
 * ({@link #onButtonUnder}), a bar that's off or has no length yet, or the switch off, paused or
 * not ready all leave every touch to Facebook.
 *
 * <p>The played drag's lift waits {@link #LIFT_DELAY_MS}. On 583 the scrubber's listener
 * ({@code LX/9JU;}) can post its start of a scrub to a Handler as the drag starts, and a lift
 * played at once seeked before that start ran, which then put the video back (#120). A press that
 * comes before the lift finishes the drag first.
 */
public final class TapToSeek {
    /** Counted each time a tap is played to the bar as a drag from its thumb. */
    static final String TAPPED = "Seek bar tap played as a drag";
    /** Counted each time a drag that started off the thumb is handed to the bar. */
    static final String DRAGGED = "Seek bar drag started off the thumb";
    /** How long a played drag holds before it lifts, so work Facebook posts as it starts runs first. */
    static final long LIFT_DELAY_MS = 100;

    private static final String FAMILY = FamilyNames.PROGRESS_BAR;

    /** The bar a press is held for. Touches come one gesture at a time on the main thread. */
    @Nullable private static WeakReference<SeekBar> held;
    /** Set once the held press went to Facebook as a drag: the rest of the gesture is Facebook's. */
    private static boolean handedOver;
    /** Set once the held press moved up or down: it isn't a seek, so the rest is swallowed. */
    private static boolean leftAlone;
    /** Set while a touch made here runs through the bar's handler, so the hook stands aside. */
    private static boolean playing;
    /** The lift of the last played drag, until it's played. */
    @Nullable private static Lift lift;

    private static float downX;
    private static float downY;
    private static float rawOffsetX;
    private static float rawOffsetY;
    private static long downTime;

    private TapToSeek() {
    }

    /**
     * The hook at the top of the bar's touch handler. True means the touch was handled here and
     * the handler answers true without running. False runs Facebook's code on it.
     */
    public static boolean takesTouch(@Nullable SeekBar bar, @Nullable MotionEvent event) {
        try {
            if (bar == null || event == null || playing) return false;
            HookStatus.invoked(FAMILY);
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) return holdsPress(bar, event);
            if (held == null || held.get() != bar) return false;
            if (handedOver) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) release();
                return false;
            }
            if (leftAlone) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) release();
                return true;
            }
            switch (action) {
                case MotionEvent.ACTION_MOVE:
                    return follows(bar, event);
                case MotionEvent.ACTION_UP:
                    seeksTo(bar, event);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    release();
                    return true;
                default:
                    return true;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "seek bar touch", failure);
            release();
            return false;
        }
    }

    /** A press: held when it misses the thumb of a bar that can seek, and the switch is on. */
    private static boolean holdsPress(SeekBar bar, MotionEvent event) {
        finishLift();
        release();
        if (!Utils.settingsReady() || !Settings.TAP_TO_SEEK.get()) return false;
        if (!bar.isEnabled() || bar.getMax() <= 0) return false;
        Rect thumb = thumbBounds(bar);
        if (thumb == null) return false;
        float reach = thumb.width() / 2f + slop(bar);
        if (Math.abs(event.getX() - thumb.exactCenterX()) <= reach) return false;
        if (onButtonUnder(bar, event)) {
            Logger.printDebug(() -> "Tap the bar to seek: a press on a button under the bar went to the button");
            return false;
        }
        HookStatus.bound(FAMILY, "seek bar touch");
        held = new WeakReference<>(bar);
        downX = event.getX();
        downY = event.getY();
        downTime = event.getDownTime();
        rawOffsetX = event.getRawX() - event.getX();
        rawOffsetY = event.getRawY() - event.getY();
        return true;
    }

    /** A move of the held press. Within the touch slop it's still a tap; past it, one way or the other. */
    private static boolean follows(SeekBar bar, MotionEvent event) {
        float dx = Math.abs(event.getX() - downX);
        float dy = Math.abs(event.getY() - downY);
        int slop = slop(bar);
        if (dx <= slop && dy <= slop) return true;
        if (dy > dx) {
            leftAlone = true;
            return true;
        }
        Rect thumb = thumbBounds(bar);
        if (thumb == null) {
            leftAlone = true;
            return true;
        }
        // Facebook gets a press on its thumb, then this move and the rest as they come.
        play(bar, MotionEvent.ACTION_DOWN, thumb.exactCenterX(), event.getEventTime());
        handedOver = true;
        HookStatus.counted(FAMILY, DRAGGED);
        return false;
    }

    /** The held press lifted where it went down: a drag from the thumb to there, lifting a little later. */
    private static void seeksTo(SeekBar bar, MotionEvent event) {
        Rect thumb = thumbBounds(bar);
        float x = event.getX();
        long at = event.getEventTime();
        release();
        if (thumb == null) return;
        play(bar, MotionEvent.ACTION_DOWN, thumb.exactCenterX(), at);
        play(bar, MotionEvent.ACTION_MOVE, x, at);
        lift = new Lift(bar, x);
        Utils.runOnMainThreadDelayed(lift, LIFT_DELAY_MS);
        HookStatus.counted(FAMILY, TAPPED);
    }

    /** Plays the waiting lift now, if there is one. */
    private static void finishLift() {
        Lift waiting = lift;
        if (waiting == null) return;
        lift = null;
        SeekBar bar = waiting.bar.get();
        if (bar != null) play(bar, MotionEvent.ACTION_UP, waiting.x, SystemClock.uptimeMillis());
    }

    /** A played drag's lift, at [x] on its bar. Runs once, unless a press finished it first. */
    private static final class Lift implements Runnable {
        final WeakReference<SeekBar> bar;
        final float x;

        Lift(SeekBar bar, float x) {
            this.bar = new WeakReference<>(bar);
            this.x = x;
        }

        @Override
        public void run() {
            if (lift != this) return;
            try {
                finishLift();
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "seek bar lift", failure);
            }
        }
    }

    /**
     * Runs a touch at [x] on the held press's height through the bar's handler, with screen
     * coordinates where the real ones were, since Facebook reads those too.
     */
    private static void play(SeekBar bar, int action, float x, long eventTime) {
        MotionEvent touch = MotionEvent.obtain(downTime, eventTime, action, x + rawOffsetX, downY + rawOffsetY, 0);
        touch.offsetLocation(-rawOffsetX, -rawOffsetY);
        playing = true;
        try {
            bar.onTouchEvent(touch);
        } finally {
            playing = false;
            touch.recycle();
        }
    }

    /**
     * The thumb's bounds, in the frame Facebook's own thumb check measures a press in, or null for
     * a bar with no thumb drawn. Facebook hides the thumb on a reel's thin bar. 582's check takes the
     * drawable's own bounds, widened by a margin of its own, against the press's {@code getX()},
     * without the padding and thumb offset the bar draws the thumb at, so these are compared the same way.
     */
    @Nullable
    private static Rect thumbBounds(SeekBar bar) {
        Drawable thumb = bar.getThumb();
        if (thumb == null) return null;
        Rect bounds = thumb.getBounds();
        return bounds.isEmpty() ? null : bounds;
    }

    /**
     * Whether [event] lands on a button Facebook drew under the bar's touch area: a view that shows,
     * takes presses and is narrower than the bar. 588's saved video viewer runs the bar's 118 px
     * up over the bottom of the reel's three dots, and with the bar holding every press off its
     * thumb, a tap on the dots seeked. The reel's own press area spans the bar's width, so a press
     * over it still seeks. Facebook's own handler turns such a press away, so the button gets it.
     */
    private static boolean onButtonUnder(SeekBar bar, MotionEvent event) {
        int[] at = new int[2];
        bar.getLocationOnScreen(at);
        return buttonAt(bar.getRootView(), bar, at[0] + event.getX(), at[1] + event.getY(), at);
    }

    /** {@link #onButtonUnder} for [view] and what it holds, with [x], [y] on the screen. */
    private static boolean buttonAt(View view, SeekBar bar, float x, float y, int[] at) {
        if (view == bar || view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0f) return false;
        view.getLocationOnScreen(at);
        // A press outside a view never reaches what it holds either.
        if (x < at[0] || x >= at[0] + view.getWidth() || y < at[1] || y >= at[1] + view.getHeight()) return false;
        if ((view.isClickable() || view.isLongClickable()) && view.getWidth() < bar.getWidth()) return true;
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (buttonAt(group.getChildAt(i), bar, x, y, at)) return true;
        }
        return false;
    }

    private static int slop(SeekBar bar) {
        return ViewConfiguration.get(bar.getContext()).getScaledTouchSlop();
    }

    private static void release() {
        held = null;
        handedOver = false;
        leftAlone = false;
    }
}
