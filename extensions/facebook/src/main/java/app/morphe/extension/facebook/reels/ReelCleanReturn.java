/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;

/**
 * Back to Clean mode (#519): a reel whose buttons were brought back goes back into Clean mode on
 * its own, with Always use Clean mode or the Clean mode button on.
 *
 * <p>The overlay hears Clean mode switch through {@link ReelCleanMode#markLeftClean}. A no while
 * either switch is on, with the store kept, arms the return, and a yes (the person's own, or this
 * one's) ends it. With When a sheet closes, the window in front losing focus while armed means a
 * sheet or another screen opened over the reel, and its getting focus back returns to Clean mode.
 * A sheet already open when the return arms doesn't count, so turning Clean mode off from
 * Facebook's three-dot menu doesn't turn it back on as the menu closes. With a time, the reel goes
 * back once the screen has gone that long with no touch: a finger held down, or a sheet open,
 * holds the wait, and it starts over when the finger lifts or the sheet closes.
 *
 * <p>The return goes through the store the way the sidebar's Clean mode button does, so it also
 * holds for the reels that follow. Touches and focus come from {@link BurnInGuard}'s watch on the
 * window in front. Everything here runs on the main thread. Off, paused, without a store, or when
 * anything fails, nothing happens.
 */
public final class ReelCleanReturn {
    private static final String FAMILY = FamilyNames.REEL_DECLUTTER;

    /** Counted each time a reel goes back into Clean mode on its own. */
    static final String RETURNED = "Reel put back in Clean mode";

    private static final Runnable DUE = ReelCleanReturn::due;

    private static Handler handler;
    /** A reel left Clean mode, and nothing has put it back yet. */
    private static boolean armed;
    /** The window in front lost focus since the return armed. */
    private static boolean sawSheet;
    /** The window in front has focus, as far as the last word on it said. */
    private static boolean focused = true;
    /** A finger is on the window in front. */
    private static boolean fingerDown;
    /** The timed return is posted. */
    private static boolean waiting;

    private ReelCleanReturn() {
    }

    /** The choice now: off until the settings are read and while Hushfacebook is paused. */
    static CleanReturn choice() {
        if (!Utils.settingsReady() || Setting.isPaused()) return CleanReturn.OFF;
        CleanReturn choice = Settings.REEL_CLEAN_RETURN.get();
        return choice == null ? CleanReturn.OFF : choice;
    }

    private static boolean cleanSwitchOn() {
        return Settings.REEL_CLEAN_MODE.get() || Settings.REEL_CLEAN_BUTTON.get();
    }

    /**
     * From {@link ReelCleanMode#markLeftClean}, on whatever thread the overlay's update runs: the
     * overlay heard Clean mode switch, to [clean]. Never throws.
     */
    static void heard(boolean clean) {
        try {
            if (!clean && (choice() == CleanReturn.OFF || ReelCleanMode.keptStore() == null)) return;
            if (Looper.myLooper() == Looper.getMainLooper()) {
                switched(clean);
            } else {
                handler().post(() -> switched(clean));
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "Back to Clean mode: could not hear Clean mode switch", failure);
        }
    }

    private static void switched(boolean clean) {
        try {
            if (clean) {
                disarm();
                return;
            }
            // Each reel the viewer holds hears the same switch, so only the first no arms.
            if (armed) return;
            CleanReturn choice = choice();
            if (choice == CleanReturn.OFF || !cleanSwitchOn()) return;
            armed = true;
            sawSheet = false;
            BurnInGuard.watchFront();
            if (choice.timed()) restart(choice);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "Back to Clean mode", failure);
        }
    }

    /** A finger on the window in front, [action] its MotionEvent action. Main thread. */
    static void touched(int action) {
        if (action == MotionEvent.ACTION_DOWN) {
            fingerDown = true;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            fingerDown = false;
        } else {
            return;
        }
        if (!armed) return;
        CleanReturn choice = choice();
        if (!choice.timed()) return;
        if (fingerDown) {
            hold();
        } else {
            restart(choice);
        }
    }

    /** The window in front getting or losing focus, or the activity leaving the front. Main thread. */
    static void focused(boolean hasFocus) {
        boolean was = focused;
        focused = hasFocus;
        if (!armed) return;
        if (!hasFocus) {
            sawSheet = true;
            fingerDown = false;
            hold();
            return;
        }
        BurnInGuard.watchFront();
        CleanReturn choice = choice();
        if (choice == CleanReturn.OFF) return;
        if (choice.timed()) {
            restart(choice);
        } else if (sawSheet && !was) {
            backToClean();
        }
    }

    private static void restart(CleanReturn choice) {
        hold();
        if (fingerDown || !focused) return;
        waiting = true;
        handler().postDelayed(DUE, choice.millis);
    }

    private static void hold() {
        if (!waiting) return;
        waiting = false;
        handler().removeCallbacks(DUE);
    }

    private static void due() {
        waiting = false;
        try {
            if (armed && choice().timed() && !fingerDown && focused) backToClean();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "Back to Clean mode", failure);
        }
    }

    private static void backToClean() {
        Object store = ReelCleanMode.keptStore();
        disarm();
        HookStatus.invoked(FAMILY);
        try {
            if (store == null || !cleanSwitchOn()) return;
            ReelCleanMode.switchStore(store, null, true);
            HookStatus.bound(FAMILY, "Back to Clean mode");
            HookStatus.counted(FAMILY, RETURNED);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "Back to Clean mode", failure);
            Logger.printException(() -> "Back to Clean mode: could not put the reel back", failure);
        }
    }

    private static void disarm() {
        armed = false;
        sawSheet = false;
        hold();
    }

    private static Handler handler() {
        Looper main = Looper.getMainLooper();
        if (handler == null || handler.getLooper() != main) handler = new Handler(main);
        return handler;
    }

    static boolean armedForTests() {
        return armed;
    }

    static void resetForTests() {
        if (handler != null) handler.removeCallbacks(DUE);
        armed = false;
        sawSheet = false;
        focused = true;
        fingerDown = false;
        waiting = false;
    }
}
