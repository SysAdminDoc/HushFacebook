/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Hide the status bar (#124 leftover): Android's status bar, the strip with the time and the
 * battery, goes from Facebook's main screen, the one with the feed and the tab bar, so Facebook has
 * the whole screen. A swipe down from the top edge shows it for a moment, as in any full screen
 * app. The bar is the system's, not a Facebook view, so it's asked away through the main screen's
 * window, and only there: reels, videos and photos already go full screen on their own, and chats
 * and the composer keep it.
 *
 * <p>Facebook shows the bar again when it leaves a full screen of its own, and a window opened over
 * the main screen, such as the comment sheet, has its own bar, so the check runs as the main screen
 * resumes, gets its focus back and lays itself out again, which a bar coming back makes it do. A
 * bar still showing then is asked away again. Asking away a bar already asked away changes nothing,
 * so one the person swiped in stays until Android hides it. Off or paused, the bar comes back on
 * the next check, and only on a screen this hid it on.
 *
 * <p>On Android 15 and up Facebook draws a strip of its own behind the bar and pads the screen below
 * it, both sized from the window's system insets, which keep the bar's height after it's asked
 * away. {@link #topInset} closes that strip while the bar is gone.
 *
 * <p>Any failure leaves that screen's bar as it is and stops the checks there.
 */
public final class HiddenStatusBar {
    /** Facebook's main screen, a manifest component, the one screen with the feed and the tab bar. */
    static final String MAIN_SCREEN = TranslucentBars.MAIN_SCREEN;

    /** Counted each time a showing bar is asked away. */
    static final String HIDDEN = "Status bar hidden";
    /** Counted each time the switch going off or a pause brings the bar back. */
    static final String GIVEN_BACK = "Status bar given back";
    /** Counted each time Facebook's strip behind a hidden bar is closed up. */
    static final String STRIP_CLOSED = "Status bar strip closed";

    private static final String FAMILY = FamilyNames.BOTTOM_TAB_BAR;

    private static boolean watching;

    /** Each main screen's window and its checks. */
    static final Map<Window, Checks> WINDOWS = new WeakHashMap<>();

    private HiddenStatusBar() {
    }

    /**
     * Called once per Facebook start, from the settings entry's onApplicationCreate, with the tab bar
     * patch in the build. Each main screen gets its checks as it resumes; the switch is asked at each.
     */
    public static synchronized void watch(Context context) {
        try {
            if (watching || !(context instanceof Application)) return;
            ((Application) context).registerActivityLifecycleCallbacks(new Screens());
            watching = true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "hidden status bar", failure);
        }
    }

    /** Whether the bar goes now: the switch on, nothing paused, the settings ready. */
    public static boolean on() {
        return Utils.settingsReady() && Settings.HIDE_STATUS_BAR.get();
    }

    /**
     * Called first thing where Facebook pads its screens for the system bars (583
     * {@code LX/2Ns;->A00}), with the window's content view and the top inset it's about to size
     * its strip behind the status bar to and pad the content by. Facebook reads that inset off the
     * window's system insets, which keep the bar's height after it's asked away, so a bar this hid
     * left a strip of background above the top bar (S22, 2026-10-18: 75 px). On the main screen,
     * with the switch on and the bar gone, the answer is 0, so the strip closes and the top bar
     * moves up to the edge. Anything else keeps Facebook's inset, and so does a failure. A bar
     * swiped in for a moment doesn't change the insets, so it shows over the top bar.
     */
    public static int topInset(View content, int top) {
        try {
            if (top <= 0 || content == null || !on()) return top;
            WindowInsets insets = content.getRootWindowInsets();
            // Android 10 and older can't say, and Facebook draws no strip there anyway.
            Boolean showing = null;
            if (insets != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                showing = insets.isVisible(WindowInsets.Type.statusBars());
            }
            return topInset(top, screenOf(content.getContext()), showing);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "hidden status bar strip", failure);
            return top;
        }
    }

    /** [top], or 0 when [screen] is the main screen and its bar is known to be gone. */
    static int topInset(int top, @Nullable Activity screen, @Nullable Boolean showing) {
        if (screen == null || !MAIN_SCREEN.equals(screen.getClass().getName()) || !Boolean.FALSE.equals(showing)) {
            return top;
        }
        HookStatus.invoked(FAMILY);
        HookStatus.counted(FAMILY, STRIP_CLOSED);
        return 0;
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

    /** The checks of [window], made once and asked again at each check. */
    static Checks attach(Window window) {
        synchronized (WINDOWS) {
            Checks checks = WINDOWS.get(window);
            if (checks == null) {
                checks = new Checks(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? new WindowBar(window) : new FlagsBar(window));
                WINDOWS.put(window, checks);
                ViewTreeObserver observer = window.getDecorView().getViewTreeObserver();
                observer.addOnGlobalLayoutListener(checks);
                observer.addOnWindowFocusChangeListener(checks);
            }
            return checks;
        }
    }

    /** Gives each main screen its checks as it resumes, and runs one. */
    static final class Screens implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityResumed(Activity activity) {
            try {
                if (!MAIN_SCREEN.equals(activity.getClass().getName()) || activity.getWindow() == null) return;
                attach(activity.getWindow()).check();
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "hidden status bar", failure);
            }
        }

        @Override
        public void onActivityCreated(Activity activity, @Nullable Bundle state) {
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityPaused(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle state) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }
    }

    /** One window's status bar. */
    interface Bar {
        /** Whether the bar shows now; null once its window is gone. */
        @Nullable
        Boolean showing();

        /** Asks the bar away, a swipe from the edge showing it for a moment. */
        void hide();

        /** Brings the bar back. */
        void show();

        /** Stops calling [checks]. */
        void forget(Checks checks);
    }

    /** Stops [window]'s layout and focus calls to [checks]. */
    private static void unwatch(@Nullable Window window, Checks checks) {
        if (window == null) return;
        ViewTreeObserver observer = window.getDecorView().getViewTreeObserver();
        if (!observer.isAlive()) return;
        observer.removeOnGlobalLayoutListener(checks);
        observer.removeOnWindowFocusChangeListener(checks);
    }

    /**
     * A window's own status bar on Android 9 and 10, through the flags its decor view asks of the
     * system. The sticky kind lets a swipe show the bar for a moment and keeps the flags, so it's
     * still asked away after.
     */
    @SuppressWarnings("deprecation") // The flags are all Android had before 11.
    static final class FlagsBar implements Bar {
        static final int AWAY = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;

        private final WeakReference<Window> window;

        FlagsBar(Window window) {
            this.window = new WeakReference<>(window);
        }

        @Nullable
        @Override
        public Boolean showing() {
            Window window = this.window.get();
            if (window == null) return null;
            return (window.getDecorView().getSystemUiVisibility() & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0;
        }

        @Override
        public void hide() {
            Window window = this.window.get();
            if (window == null) return;
            View decor = window.getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility() | AWAY);
        }

        @Override
        public void show() {
            Window window = this.window.get();
            if (window == null) return;
            View decor = window.getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility() & ~AWAY);
        }

        @Override
        public void forget(Checks checks) {
            unwatch(window.get(), checks);
        }
    }

    /** A window's own status bar, through its insets. */
    @RequiresApi(Build.VERSION_CODES.R)
    static final class WindowBar implements Bar {
        private final WeakReference<Window> window;

        WindowBar(Window window) {
            this.window = new WeakReference<>(window);
        }

        @Nullable
        @Override
        public Boolean showing() {
            Window window = this.window.get();
            if (window == null) return null;
            WindowInsets insets = window.getDecorView().getRootWindowInsets();
            return insets == null || insets.isVisible(WindowInsets.Type.statusBars());
        }

        @Override
        public void hide() {
            WindowInsetsController controller = controller();
            if (controller == null) return;
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsets.Type.statusBars());
        }

        @Override
        public void show() {
            WindowInsetsController controller = controller();
            if (controller != null) controller.show(WindowInsets.Type.statusBars());
        }

        @Override
        public void forget(Checks checks) {
            unwatch(window.get(), checks);
        }

        @Nullable
        private WindowInsetsController controller() {
            Window window = this.window.get();
            return window == null ? null : window.getInsetsController();
        }
    }

    /** One main screen's checks. */
    static final class Checks implements ViewTreeObserver.OnGlobalLayoutListener,
            ViewTreeObserver.OnWindowFocusChangeListener {
        private final Bar bar;
        /** Whether this asked the bar away and hasn't given it back. */
        boolean hid;
        private boolean stopped;

        Checks(Bar bar) {
            this.bar = bar;
        }

        @Override
        public void onGlobalLayout() {
            check();
        }

        /** The settings page or a sheet closing hands the window its focus back, with its own bar gone. */
        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            if (hasFocus) check();
        }

        /** Asks the bar away while on, or gives it back once when off. */
        void check() {
            if (stopped) return;
            try {
                if (on()) {
                    if (!Boolean.TRUE.equals(bar.showing())) return;
                    HookStatus.invoked(FAMILY);
                    bar.hide();
                    hid = true;
                    HookStatus.counted(FAMILY, HIDDEN);
                    Logger.printDebug(() -> "Hidden status bar: asked the status bar away");
                } else if (hid) {
                    hid = false;
                    bar.show();
                    HookStatus.counted(FAMILY, GIVEN_BACK);
                    Logger.printDebug(() -> "Hidden status bar: gave the status bar back");
                }
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "hidden status bar", failure);
                stopped = true;
                try {
                    bar.forget(this);
                } catch (Throwable ignored) {
                    // Already failing; the checks stay stopped either way.
                }
            }
        }
    }
}
