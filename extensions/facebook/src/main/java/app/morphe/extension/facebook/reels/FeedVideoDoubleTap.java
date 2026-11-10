/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Double tap the right or left side of a feed video to jump it 10 seconds ahead or back, live
 * videos too while Facebook gives them a length to rewind in (#432). Facebook opens a feed video on
 * one tap, so a double tap opened it before the second tap came.
 *
 * <p>The Tap to play patch hands every touch here first, before the tap clock and Facebook. While
 * the switch is on, a tap on a side of a started feed video is held back for the double tap timeout:
 * its lift reaches Facebook as a cancel. A second tap on the same video in that time jumps it, and so
 * does each quick tap after that, the way the skip buttons add up. A lone tap goes on to Facebook as
 * a fresh tap at the same spot once the time is up, so it still opens the video, a moment late. The
 * middle of the video, the buttons on it, its seek bar, ads, a video that hasn't started and a press
 * that stops a moving feed get their touches at once, as before.
 */
public final class FeedVideoDoubleTap {
    private static final String FAMILY = FamilyNames.TAP_TO_PLAY;
    /** How much of a video's width on each side a tap jumps from. The middle is left alone. */
    static final float SIDE = 0.4f;
    /** A view that takes taps and is at most this big both ways is a button, whose taps pass at once. */
    static final int BUTTON_DP = FeedVideoControls.CORNER_BUTTON_DP;
    /** How recently the window scrolled for a press to be stopping a moving feed, which opens nothing. */
    static final long SCROLLED_WITHIN_MS = 100;

    @Nullable static volatile Boolean inBuildForTests;

    private static final Runnable REPLAY = FeedVideoDoubleTap::replay;
    private static final ViewTreeObserver.OnScrollChangedListener SCROLLED =
            () -> lastScroll = SystemClock.uptimeMillis();
    private static final Set<ViewTreeObserver> watched = Collections.newSetFromMap(new WeakHashMap<>());
    private static Handler main;
    private static volatile long lastScroll = Long.MIN_VALUE / 2;

    /** A finger down on a side of a video, and whether it follows a tap on that same video. */
    private static boolean pressing, follows;
    private static int pressSide, slop;
    private static float pressX, pressY, pressRawX, pressRawY;
    private static long pressTime;
    private static WeakReference<Object> pressed = new WeakReference<>(null);

    /** The video the last tap was held back or jumped on, and when that tap lifted, on the event clock. */
    private static WeakReference<Object> last = new WeakReference<>(null);
    private static long lastUp;
    /** A lone tap held back, which goes on to Facebook at the same point in its window when its time is up. */
    private static boolean waiting;
    private static WeakReference<Activity> waitingIn = new WeakReference<>(null);
    private static float waitX, waitY;
    /** True while the held tap is handed on, so this lets it through. */
    private static boolean replaying;

    private FeedVideoDoubleTap() { }

    /** The hook, first thing in FbFragmentActivity.dispatchTouchEvent. Never throws. */
    public static void touch(@Nullable Activity activity, @Nullable MotionEvent event) {
        if (replaying || activity == null || event == null) return;
        try {
            Window window = activity.getWindow();
            touch(activity, window == null ? null : window.getDecorView(), event);
        } catch (Throwable failure) {
            forget();
            HookStatus.threw(FAMILY, "feed video double tap", failure);
        }
    }

    /** One touch on [activity]'s window, whose top view is [root]. */
    static void touch(Activity activity, @Nullable View root, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            down(root, event);
            return;
        }
        if (!pressing) return;
        boolean moved = Math.hypot(event.getRawX() - pressRawX, event.getRawY() - pressRawY) > slop;
        if (action == MotionEvent.ACTION_MOVE) {
            if (moved) pressing = false;
            return;
        }
        pressing = false;
        // A second finger, a cancel, a drag or a long press stays Facebook's.
        if (action != MotionEvent.ACTION_UP || moved
                || event.getEventTime() - pressTime >= ViewConfiguration.getLongPressTimeout()) return;
        Object player = pressed.get();
        if (player == null) return;
        event.setAction(MotionEvent.ACTION_CANCEL);
        last = new WeakReference<>(player);
        lastUp = event.getEventTime();
        if (follows) {
            FeedVideoControls.skipBy(player, pressSide * FeedVideoControls.SKIP_MS);
            return;
        }
        waiting = true;
        waitingIn = new WeakReference<>(activity);
        waitX = pressX;
        waitY = pressY;
        main().postDelayed(REPLAY, ViewConfiguration.getDoubleTapTimeout());
    }

    private static void down(@Nullable View root, MotionEvent event) {
        pressing = false;
        long at = event.getEventTime();
        // Only a press on the same video in time follows the last tap. Any press drops a tap still held back.
        Object before = at - lastUp <= ViewConfiguration.getDoubleTapTimeout() ? last.get() : null;
        last = new WeakReference<>(null);
        if (waiting) {
            waiting = false;
            waitingIn = new WeakReference<>(null);
            main().removeCallbacks(REPLAY);
        }
        if (root == null || !on("feed video double tap")) return;
        watchScrolls(root);
        int x = Math.round(event.getRawX());
        int y = Math.round(event.getRawY());
        Object player = videoAt(root, x, y);
        if (player == null) return;
        int side = side(FeedVideoControls.access.view(player), x);
        if (side == 0 || !FeedVideoControls.skippable(player)) return;
        boolean again = player == before;
        if (!again) {
            // A video waiting for Tap to play, or not drawn yet, takes its tap at once.
            if (!started(player)) return;
            if (SystemClock.uptimeMillis() - lastScroll <= SCROLLED_WITHIN_MS) {
                HookStatus.counted(FAMILY, "double tap skip, feed still moving");
                return;
            }
        }
        pressing = true;
        follows = again;
        pressSide = side;
        pressed = new WeakReference<>(player);
        pressX = event.getX();
        pressY = event.getY();
        pressRawX = event.getRawX();
        pressRawY = event.getRawY();
        pressTime = at;
        slop = ViewConfiguration.get(root.getContext()).getScaledTouchSlop();
    }

    /** The held tap's time is up with no second tap: Facebook gets it now, as a tap at the same spot. */
    private static void replay() {
        if (!waiting) return;
        waiting = false;
        // The tap has gone on to open the video, so no later press follows it.
        last = new WeakReference<>(null);
        Activity activity = waitingIn.get();
        waitingIn = new WeakReference<>(null);
        // A dialog or another screen that came up since gets nothing.
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || !activity.hasWindowFocus()) return;
        long now = SystemClock.uptimeMillis();
        MotionEvent down = tap(now, MotionEvent.ACTION_DOWN);
        MotionEvent up = tap(now, MotionEvent.ACTION_UP);
        replaying = true;
        try {
            activity.dispatchTouchEvent(down);
            activity.dispatchTouchEvent(up);
            HookStatus.counted(FAMILY, "double tap skip, single tap handed on");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "double tap skip, single tap", failure);
        } finally {
            replaying = false;
            down.recycle();
            up.recycle();
        }
    }

    /** A finger's touch at the held tap's point, as the screen sends one. */
    private static MotionEvent tap(long time, int action) {
        MotionEvent.PointerProperties finger = new MotionEvent.PointerProperties();
        finger.id = 0;
        finger.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords point = new MotionEvent.PointerCoords();
        point.x = waitX;
        point.y = waitY;
        point.pressure = 1f;
        point.size = 1f;
        return MotionEvent.obtain(time, time, action, 1, new MotionEvent.PointerProperties[]{finger},
                new MotionEvent.PointerCoords[]{point}, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    /**
     * The player of the feed video at the screen point ([x], [y]) inside [root], or null: a
     * player that plays in a feed, not an ad, shown in [root]'s window with the point inside it, and
     * no button or seek bar there. The smallest wins, should two overlap.
     */
    @Nullable
    static Object videoAt(View root, int x, int y) {
        if (FeedVideoControls.barAt(root, x, y) || buttonAt(root, x, y)) return null;
        FeedVideoControls.Players read = FeedVideoControls.access;
        Object found = null;
        long smallest = Long.MAX_VALUE;
        for (Object player : FeedVideoControls.knownPlayers()) {
            ViewGroup view = read.view(player);
            if (view == null || !view.isAttachedToWindow() || !view.isShown() || view.getRootView() != root) continue;
            if (!contains(view, x, y) || !FeedVideoControls.feedPlayer(player)) continue;
            Object params = read.params(player);
            if (params == null || !read.eligible(params)) continue;
            long area = (long) view.getWidth() * view.getHeight();
            if (area < smallest) {
                smallest = area;
                found = player;
            }
        }
        return found;
    }

    /** -1 for the left of [view] at screen x [x], 1 for the right, 0 for the middle or no view. */
    static int side(@Nullable View view, int x) {
        if (view == null || view.getWidth() <= 0) return 0;
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        float across = (x - at[0]) / (float) view.getWidth();
        return across < SIDE ? -1 : across >= 1f - SIDE ? 1 : 0;
    }

    /** Whether a button takes a tap at ([x], [y]): Facebook's speaker or tagged people, or a feed video button. */
    private static boolean buttonAt(View root, int x, int y) {
        View taker = PostLongPress.hit(root, x, y);
        if (taker == null || !(taker.isClickable() || taker.isLongClickable())) return false;
        float most = BUTTON_DP * root.getResources().getDisplayMetrics().density;
        return taker.getWidth() <= most && taker.getHeight() <= most;
    }

    private static boolean started(Object player) {
        FeedVideoControls.Players read = FeedVideoControls.access;
        return read.playing(player) || read.position(player) > 0;
    }

    private static boolean contains(View view, int x, int y) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return x >= at[0] && x < at[0] + view.getWidth() && y >= at[1] && y < at[1] + view.getHeight();
    }

    /** Notes each scroll in [root]'s window, once per window, so a press on a moving feed is known. */
    private static void watchScrolls(View root) {
        ViewTreeObserver observer = root.getViewTreeObserver();
        if (!observer.isAlive() || watched.contains(observer)) return;
        observer.addOnScrollChangedListener(SCROLLED);
        watched.add(observer);
    }

    /** Whether the switch is on, with [where] bound. Never throws: a failure is reported and reads off. */
    private static boolean on(String where) {
        try {
            if (!inBuild() || !Utils.settingsReady() || !Settings.FEED_VIDEO_DOUBLE_TAP_SKIP.get()) return false;
            HookStatus.bound(FAMILY, where);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, where, failure);
            return false;
        }
    }

    private static boolean inBuild() {
        Boolean forced = inBuildForTests;
        return forced != null ? forced : SettingsStatus.tapToPlay();
    }

    private static Handler main() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }

    /** Drops the press, the last tap and a tap still held back. */
    static void forget() {
        pressing = false;
        follows = false;
        pressed = new WeakReference<>(null);
        last = new WeakReference<>(null);
        waiting = false;
        waitingIn = new WeakReference<>(null);
        replaying = false;
        if (main != null) main.removeCallbacks(REPLAY);
    }

    /** For tests: forgets everything, the windows watched for scrolls too, and goes back to the build's answer. */
    static void reset() {
        forget();
        synchronized (watched) {
            for (ViewTreeObserver observer : watched) {
                if (observer.isAlive()) observer.removeOnScrollChangedListener(SCROLLED);
            }
            watched.clear();
        }
        lastScroll = Long.MIN_VALUE / 2;
        lastUp = 0;
        inBuildForTests = null;
    }
}
