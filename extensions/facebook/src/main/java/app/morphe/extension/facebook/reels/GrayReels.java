/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Show Reels in gray: the Reels screen shows in black and white, its video and buttons alike, so
 * it pulls less. The feed, stories, photos and chats keep their colors.
 *
 * <p>The Reels screen is the one Burn-in guard finds ({@link BurnInGuard#reelOf}): a video filling
 * the window in front, which takes in a feed video opened full screen as well (#370). Facebook's
 * camera ({@link BurnInGuard#camera}) and the story viewer, whose window shows the screen under it
 * ({@link BurnInGuard#seeThrough}), are left out. While it shows, the content frame draws through a
 * hardware layer whose paint takes the color out ({@link #GRAY}), so everything in it comes out gray,
 * a video on a TextureView included. A video on a SurfaceView is put on the screen by the display
 * hardware, outside the window's drawing, so it would keep its color under gray buttons, and before
 * Android 12 a layer over it can cover it. The reels viewer's players draw into a TextureView while
 * the switch is on ({@link app.morphe.extension.facebook.media.TextureVideo}), and a screen whose
 * video is on a SurfaceView (the reel already playing when the switch went on, or a full-screen
 * player Facebook built that way) keeps its colors.
 *
 * <p>Looks: one as an activity comes to the front or its window gets focus back, one after a layout
 * at most every {@link BurnInGuard#LOOK_MS} while the switch is on, and one every {@link #KEEP_MS}
 * while gray. A swipe between reels shows half of each, and neither fills the screen, so while gray
 * a look that doesn't find the Reels screen is made again {@link BurnInGuard#SETTLE_MS} later before
 * the colors come back. While the window doesn't have focus (a sheet or dialog over it) nothing
 * changes, and the settle starts over once it's back. A content frame that already draws through a
 * layer of Facebook's own is left alone, and so is one Facebook gives a software layer while gray.
 * One whose gray layer was taken off gets it back. Off, paused, or before the settings are ready,
 * nothing is gray.
 *
 * <p>Turn gray over ({@link Settings#GRAY_REELS_OVER}) takes the color out a step at a time instead:
 * each look that finds the Reels screen adds the time since the last one, when that's at most
 * {@link #STEP_MAX_MS}, and the layer's saturation falls from 1 to 0 over the minutes picked, in
 * {@link #STEPS} steps so the paint changes only when the step does. Time with a sheet over the
 * screen, mid-swipe or away isn't counted, and {@link #AWAY_MS} away from the Reels screen, Facebook
 * in the background or the phone asleep included, starts the count over. The count lives in the
 * process, so a restart starts it over too.
 *
 * <p>Show time in Reels ({@link Settings#REELS_MINUTES}) shows that count in minutes on the Reels
 * screen ({@link ReelsClock}), with or without the gray. On its own it counts any reel filling the
 * window, a SurfaceView one too, since nothing needs drawing through a layer, and the looks run while
 * either switch is on.
 */
public final class GrayReels {
    private static final String FAMILY = FamilyNames.REEL_DECLUTTER;

    /** How often a look comes while gray, for leaving the Reels screen without a layout. */
    static final long KEEP_MS = 1_000L;

    /** The longest gap between two looks at the Reels screen that counts as watching. */
    static final long STEP_MAX_MS = 3_000L;

    /** How long away from the Reels screen starts Turn gray over's count over. */
    static final long AWAY_MS = 15 * 60_000L;

    /** How many steps Turn gray over takes from full color to gray. */
    static final int STEPS = 50;

    static final String COUNT_GRAYED = "Reels screen shown in gray";

    static final String COUNT_STEP = "Reels screen turned a step grayer";

    /** What the content frame's layer draws with: the colors at saturation 0. */
    static final Paint GRAY = grayPaint();

    /** What the layer draws with part way to gray, changed as the steps go. */
    static final Paint PART_GRAY = new Paint();

    /** The saturation the layer shows now, or -1 while there's none. */
    private static float shown = -1f;
    /** The time counted on the Reels screen toward Turn gray over. */
    private static long watchedMs;
    /** When a look last found the Reels screen, on the clock that runs while the phone sleeps, or -1. */
    private static long lastOnReels = -1L;

    /** The application this process's callbacks went on, so a second start adds none. */
    private static WeakReference<Application> watching = new WeakReference<>(null);
    private static final Runnable TICK = GrayReels::tick;
    private static final Focus FOCUS = new Focus();
    private static final Layout LAYOUT = new Layout();

    private static Handler handler;
    /** The activity in front, the one every look is at. */
    private static WeakReference<Activity> front = new WeakReference<>(null);
    /** The activity in front is the camera or the story viewer, never grayed. */
    private static boolean frontLeftOut;
    /** The decor view whose observer has the listeners. */
    private static WeakReference<View> observed = new WeakReference<>(null);
    /** The content frame grayed here, while it is. */
    private static WeakReference<View> grayed = new WeakReference<>(null);
    private static boolean layoutWatched;
    /** When the next timed look is due, or -1 when none is. */
    private static long tickAt = -1L;
    /** When a look made while gray first didn't find the Reels screen, or -1 while none has. */
    private static long doubtSince = -1L;

    private GrayReels() {
    }

    /**
     * From the settings entry's application hook, in the main process, with Clean up Reels and Turn
     * off HDR brightness (whose hook moves the reels onto a TextureView) in the build.
     */
    public static synchronized void install(Context context) {
        try {
            if (!(context instanceof Application)) return;
            Application application = (Application) context;
            if (watching.get() == application) return;
            application.registerActivityLifecycleCallbacks(new Lifecycle());
            watching = new WeakReference<>(application);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "gray reels start", failure);
        }
    }

    private static Paint grayPaint() {
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0f);
        Paint paint = new Paint();
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        return paint;
    }

    /** Either switch now: off until the settings are read and while Hushfacebook is paused. */
    static boolean on() {
        return Utils.settingsReady() && (Settings.GRAY_REELS.get() || Settings.REELS_MINUTES.get());
    }

    /**
     * The saturation the layer shows after [watched] on the Reels screen with [over] picked: 0 at
     * once, else falling from 1 to 0 over its minutes in {@link #STEPS} even steps.
     */
    static float saturation(long watched, GrayOver over) {
        if (over.minutes <= 0) return 0f;
        long span = over.minutes * 60_000L;
        if (watched >= span) return 0f;
        long left = span - Math.max(0L, watched);
        // Rounded up, so the first step comes after a whole step's time.
        int steps = (int) ((left * STEPS + span - 1) / span);
        return steps / (float) STEPS;
    }

    /** A look found the Reels screen at [now] on the clock that runs while the phone sleeps. */
    static void onReels(long now) {
        if (lastOnReels >= 0L) {
            long gap = now - lastOnReels;
            if (gap >= AWAY_MS) watchedMs = 0L;
            else if (gap > 0L && gap <= STEP_MAX_MS) watchedMs += gap;
        }
        lastOnReels = now;
    }

    /** The paint for [saturation]: {@link #GRAY} at 0, {@link #PART_GRAY} set to it otherwise. */
    private static Paint paintFor(float saturation) {
        if (saturation <= 0f) return GRAY;
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(saturation);
        PART_GRAY.setColorFilter(new ColorMatrixColorFilter(matrix));
        return PART_GRAY;
    }

    /** As [activity] comes to the front: listens to it and looks once. */
    static void resumed(Activity activity) {
        try {
            leave();
            front = new WeakReference<>(activity);
            frontLeftOut = BurnInGuard.camera(activity) || BurnInGuard.seeThrough(activity);
            View decor = activity.getWindow() == null ? null : activity.getWindow().peekDecorView();
            if (decor == null) return;
            ViewTreeObserver observer = decor.getViewTreeObserver();
            if (!observer.isAlive()) return;
            observer.addOnWindowFocusChangeListener(FOCUS);
            observed = new WeakReference<>(decor);
            look();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "gray reels watch", failure);
        }
    }

    /** As [activity] leaves the front: its colors come back and its listeners come off. */
    static void paused(Activity activity) {
        try {
            if (front.get() == activity) leave();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "gray reels release", failure);
        }
    }

    private static void leave() {
        color();
        ReelsClock.hide();
        watchLayouts(false);
        View decor = observed.get();
        if (decor != null && decor.getViewTreeObserver().isAlive()) {
            decor.getViewTreeObserver().removeOnWindowFocusChangeListener(FOCUS);
        }
        observed = new WeakReference<>(null);
        front = new WeakReference<>(null);
        frontLeftOut = false;
        doubtSince = -1L;
        schedule(-1L);
    }

    /** Looks at the activity in front: grays its content frame on the Reels screen, gives the colors back anywhere else. */
    static void look() {
        Activity activity = front.get();
        if (activity == null) return;
        try {
            boolean on = on();
            watchLayouts(on);
            Window window = activity.getWindow();
            View decor = window == null ? null : window.peekDecorView();
            if (!on || decor == null || frontLeftOut) {
                color();
                ReelsClock.hide();
                doubtSince = -1L;
                schedule(-1L);
                return;
            }
            long now = SystemClock.uptimeMillis();
            boolean held = grayed.get() != null || ReelsClock.shown();
            if (!decor.hasWindowFocus()) {
                // A sheet or dialog over the screen: it stays as it is until the focus comes back,
                // and a swipe it interrupted gets the whole settle again after.
                doubtSince = -1L;
                schedule(-1L);
                return;
            }
            View reel = BurnInGuard.reelOf(decor);
            boolean grayHere = Settings.GRAY_REELS.get() && reel != null && !(reel instanceof SurfaceView);
            boolean timeHere = Settings.REELS_MINUTES.get() && reel != null;
            boolean reels = grayHere || timeHere;
            if (!reels && held) {
                // Mid-swipe: the gray and the time stay, and a second look decides.
                if (doubtSince < 0L) doubtSince = now;
                if (now - doubtSince < BurnInGuard.SETTLE_MS) {
                    schedule(doubtSince + BurnInGuard.SETTLE_MS);
                    return;
                }
            }
            doubtSince = -1L;
            if (reels) onReels(SystemClock.elapsedRealtime());
            if (grayHere) {
                gray(decor.findViewById(android.R.id.content), saturation(watchedMs, Settings.GRAY_REELS_OVER.get()));
            } else {
                color();
            }
            if (timeHere) ReelsClock.show(decor, watchedMs);
            else ReelsClock.hide();
            schedule(grayed.get() != null || ReelsClock.shown() ? now + KEEP_MS : -1L);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "gray reels", failure);
            try {
                color();
                ReelsClock.hide();
            } catch (Throwable again) {
                HookStatus.threw(FAMILY, "gray reels put back", again);
            }
        }
    }

    /**
     * Draws [content] through the gray layer at [saturation], unless it already draws through a
     * layer of its own. Grayed already: the layer's paint follows a new step, a software layer
     * Facebook put on since stays and isn't ours to take off, and a frame left with no layer gets
     * the gray back.
     */
    private static void gray(@Nullable View content, float saturation) {
        if (content == null) return;
        if (grayed.get() == content) {
            int type = content.getLayerType();
            if (type == View.LAYER_TYPE_HARDWARE) {
                if (saturation == shown) return;
                content.setLayerPaint(paintFor(saturation));
                shown = saturation;
                HookStatus.counted(FAMILY, COUNT_STEP);
                return;
            }
            grayed = new WeakReference<>(null);
            if (type != View.LAYER_TYPE_NONE) return;
        } else {
            color();
        }
        if (content.getLayerType() != View.LAYER_TYPE_NONE) return;
        content.setLayerType(View.LAYER_TYPE_HARDWARE, paintFor(saturation));
        shown = saturation;
        grayed = new WeakReference<>(content);
        HookStatus.counted(FAMILY, COUNT_GRAYED);
    }

    /** Takes the gray layer off the content frame grayed here, if it still has a layer. */
    private static void color() {
        View view = grayed.get();
        grayed = new WeakReference<>(null);
        shown = -1f;
        if (view != null && view.getLayerType() == View.LAYER_TYPE_HARDWARE) {
            view.setLayerType(View.LAYER_TYPE_NONE, null);
        }
    }

    private static void schedule(long next) {
        if (next == tickAt) return;
        Handler main = handler();
        main.removeCallbacks(TICK);
        tickAt = next;
        if (next >= 0L) main.postAtTime(TICK, next);
    }

    private static void tick() {
        tickAt = -1L;
        look();
    }

    /** A look as soon as the main thread is free, in place of any timed one. */
    private static void lookSoon() {
        Handler main = handler();
        main.removeCallbacks(TICK);
        tickAt = SystemClock.uptimeMillis();
        main.post(TICK);
    }

    /** Adds or takes off the layout listener on the window in front. */
    private static void watchLayouts(boolean on) {
        if (on == layoutWatched) return;
        View decor = observed.get();
        if (on && decor == null) return;
        layoutWatched = on;
        if (decor == null || !decor.getViewTreeObserver().isAlive()) return;
        if (on) decor.getViewTreeObserver().addOnGlobalLayoutListener(LAYOUT);
        else decor.getViewTreeObserver().removeOnGlobalLayoutListener(LAYOUT);
    }

    private static Handler handler() {
        Looper main = Looper.getMainLooper();
        if (handler == null || handler.getLooper() != main) handler = new Handler(main);
        return handler;
    }

    /** The window getting focus back: the settings closing, or a sheet over it going away. */
    private static final class Focus implements ViewTreeObserver.OnWindowFocusChangeListener {
        @Override public void onWindowFocusChanged(boolean hasFocus) {
            if (!hasFocus || front.get() == null) return;
            try {
                lookSoon();
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "gray reels focus", failure);
            }
        }
    }

    /** A layout: a new reel, or another tab, so a look soon. */
    private static final class Layout implements ViewTreeObserver.OnGlobalLayoutListener {
        private long lastLook = Long.MIN_VALUE / 2;

        @Override public void onGlobalLayout() {
            long now = SystemClock.uptimeMillis();
            long due = Math.max(now, lastLook + BurnInGuard.LOOK_MS);
            if (tickAt >= 0L && tickAt <= due) return;
            lastLook = due;
            schedule(due);
        }
    }

    private static final class Lifecycle implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(@NonNull Activity activity) {
            resumed(activity);
        }

        @Override public void onActivityPaused(@NonNull Activity activity) {
            paused(activity);
        }

        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(@NonNull Activity activity) { }
        @Override public void onActivityStopped(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
        @Override public void onActivityDestroyed(@NonNull Activity activity) { }
    }

    static boolean grayForTests() {
        return grayed.get() != null;
    }

    static boolean watchingLayoutsForTests() {
        return layoutWatched;
    }

    static long watchedForTests() {
        return watchedMs;
    }

    static void resetForTests() {
        leave();
        if (handler != null) handler.removeCallbacks(TICK);
        tickAt = -1L;
        LAYOUT.lastLook = Long.MIN_VALUE / 2;
        watchedMs = 0L;
        lastOnReels = -1L;
    }
}
