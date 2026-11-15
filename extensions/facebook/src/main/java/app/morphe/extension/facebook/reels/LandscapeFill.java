/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * A landscape video opened full screen fills the turned screen too, with Fill the screen with reels
 * on (#466). Facebook's landscape player sizes its video box to the video before the size helper
 * {@link ReelFill} hooks is asked, so a 16:9 video on a 19.5:9 screen keeps bars at both sides
 * however the helper answers (S22, 587, 2026-11-15: a 1922x1080 box in a 2316x1080 window).
 *
 * <p>So after each layout of a turned screen, the biggest video surface whose box is about the
 * screen's size is scaled up from its center until it reaches every edge, as long as no more than
 * {@link ReelFill#MOST_CUT} of the picture goes past them. A video a pinch is keeping whole
 * ({@link ReelFitPinch}) is left to it. Off, paused, a screen held upright, or a failure here, and
 * every video this filled goes back to its own size.
 */
public final class LandscapeFill {
    /** The hook's name in the diagnostics report. */
    static final String HOOK = "Fill full-screen landscape video";

    /** Counted each time a landscape video is scaled to fill the turned screen. */
    static final String FILLED = "Landscape video filled the screen";

    /** A scale closer than this to the one a view has is no change. */
    static final float SAME = 0.01f;

    /** The screens watched for layouts. */
    private static final Map<Activity, Boolean> watched = new WeakHashMap<>();

    /** The videos scaled here, each set back to its own size once it no longer should fill. */
    private static final Map<View, Boolean> filled = new WeakHashMap<>();

    /** The application this process's callbacks went on, so a second start adds none. */
    private static WeakReference<Application> watching = new WeakReference<>(null);

    private LandscapeFill() {
    }

    /** From the settings entry's application hook, in the main process, with Clean up Reels in the build. */
    public static synchronized void watch(Context context) {
        if (!(context instanceof Application)) return;
        Application application = (Application) context;
        if (watching.get() == application) return;
        application.registerActivityLifecycleCallbacks(new Watcher());
        watching = new WeakReference<>(application);
    }

    static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityCreated(Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityResumed(Activity activity) { resumed(activity); }
        @Override public void onActivityPaused(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        @Override public void onActivityDestroyed(Activity activity) { watched.remove(activity); }
    }

    /** A screen in front is looked at after each of its layouts. */
    static void resumed(Activity activity) {
        try {
            if (watched.containsKey(activity)) return;
            Window window = activity.getWindow();
            View decor = window == null ? null : window.peekDecorView();
            if (decor == null) return;
            ViewTreeObserver tree = decor.getViewTreeObserver();
            if (!tree.isAlive()) return;
            watched.put(activity, Boolean.TRUE);
            WeakReference<View> held = new WeakReference<>(decor);
            tree.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    View shown = held.get();
                    if (shown == null) {
                        if (tree.isAlive()) tree.removeOnGlobalLayoutListener(this);
                        return;
                    }
                    laidOut(shown);
                }
            });
            laidOut(decor);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, HOOK, failure);
        }
    }

    /**
     * After a layout of the window [decor] holds: fills its landscape player while the switch is on
     * and the screen is turned, and puts back any video of this window it filled that shouldn't be.
     * Never throws.
     */
    static void laidOut(View decor) {
        try {
            if (filled.isEmpty() && !(decor.getWidth() > decor.getHeight())) return;
            boolean on = Utils.settingsReady() && Settings.FILL_REELS.get();
            View video = on && decor.getWidth() > decor.getHeight() ? player(decor) : null;
            for (View earlier : new ArrayList<>(filled.keySet())) {
                if (earlier != null && earlier != video && earlier.getRootView() == decor) unfill(earlier);
            }
            if (video == null || ReelFitPinch.keeping(video)) return;
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            HookStatus.bound(FamilyNames.REEL_DECLUTTER, HOOK);
            float scale = scale(video.getWidth(), video.getHeight(), decor.getWidth(), decor.getHeight());
            if (scale <= 1f) {
                if (filled.containsKey(video)) unfill(video);
                return;
            }
            if (Math.abs(video.getScaleX() - scale) < SAME && Math.abs(video.getScaleY() - scale) < SAME) return;
            video.setScaleX(scale);
            video.setScaleY(scale);
            if (filled.put(video, Boolean.TRUE) == null) {
                HookStatus.counted(FamilyNames.REEL_DECLUTTER, FILLED);
                int width = video.getWidth();
                int height = video.getHeight();
                Logger.printDebug(() -> "Landscape fill: " + width + "x" + height + " video scaled " + scale
                        + " to fill " + decor.getWidth() + "x" + decor.getHeight());
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, HOOK, failure);
        }
    }

    /**
     * The scale that takes a {@code width} by {@code height} landscape video to every edge of a
     * {@code screenWidth} by {@code screenHeight} turned screen, or 1 when it already reaches them,
     * either is held upright, or filling would cut off more than {@link ReelFill#MOST_CUT}.
     */
    static float scale(int width, int height, int screenWidth, int screenHeight) {
        if (width <= 0 || height <= 0 || width <= height || screenWidth <= screenHeight) return 1f;
        float scale = Math.max((float) screenWidth / width, (float) screenHeight / height);
        if (scale < 1f + SAME) return 1f;
        return 1 - 1 / scale <= ReelFill.MOST_CUT ? scale : 1f;
    }

    /**
     * The biggest video surface, a SurfaceView or TextureView, shown in [decor] whose own box is
     * landscape and about the size of the turned screen, or null.
     */
    @Nullable
    static View player(View decor) {
        View[] best = {null};
        find(decor, decor.getWidth(), decor.getHeight(), best);
        return best[0];
    }

    private static void find(View view, int screenWidth, int screenHeight, View[] best) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) find(group.getChildAt(i), screenWidth, screenHeight, best);
            return;
        }
        if (!(view instanceof SurfaceView) && !(view instanceof TextureView)) return;
        int width = view.getWidth();
        int height = view.getHeight();
        if (!ReelFill.fullScreen(false, width, height, screenWidth, screenHeight)) return;
        View found = best[0];
        if (found == null || (long) width * height > (long) found.getWidth() * found.getHeight()) best[0] = view;
    }

    private static void unfill(View video) {
        filled.remove(video);
        if (ReelFitPinch.keeping(video)) return;
        video.setScaleX(1f);
        video.setScaleY(1f);
    }

    static void resetForTests() {
        for (View video : new ArrayList<>(filled.keySet())) {
            if (video == null) continue;
            video.setScaleX(1f);
            video.setScaleY(1f);
        }
        filled.clear();
        watched.clear();
    }
}
