/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Refresh rate for Facebook's screens (#480): the screen's highest rate, or a fixed one it offers.
 *
 * <p>Each Facebook window asks for a display mode through its {@code preferredDisplayModeId}, set
 * as the activity is created and again as it resumes. Android turns that into a vote for the
 * mode's rate on the window's surface, held while Facebook draws, so a feed video's own lower vote
 * no longer pulls the screen down while you scroll past it. Only modes at the screen's current
 * resolution count, so a choice never changes the resolution. The phone's own refresh rate
 * setting (Samsung's Motion smoothness, for one) still sets the most a window can get.
 *
 * <p>Facebook as it ships asks for nothing. That choice, and a paused Hushfacebook, which reads
 * the default, put back Facebook's own value only on a window this class changed, and only while
 * that window still holds the mode set here.
 */
public final class RefreshRate {
    /** The choices the list offers. A fixed rate shows only on a screen with a mode near it. */
    public enum Rate {
        FACEBOOK(0, "facebook"),
        HIGHEST(0, "highest"),
        HZ60(60, "60"),
        HZ90(90, "90"),
        HZ120(120, "120"),
        HZ144(144, "144"),
        HZ165(165, "165");

        /** The rate asked for, or 0 for a choice that isn't one fixed rate. */
        public final int hertz;

        /** What a settings file holds for this choice. It never changes once written. */
        public final String fileValue;

        Rate(int hertz, String fileValue) {
            this.hertz = hertz;
            this.fileValue = fileValue;
        }

        /** The choice a settings file names, or null when it names none this build knows. */
        @Nullable
        public static Rate fromFile(@Nullable Object value) {
            if (!(value instanceof String)) return null;
            for (Rate rate : values()) {
                if (rate.fileValue.equals(value)) return rate;
            }
            return null;
        }

        /** A fixed rate as the phone writes numbers, such as 120 Hz. Null for the other choices. */
        @Nullable
        public String hertzLabel() {
            return hertz == 0 ? null : NumberFormat.getIntegerInstance().format(hertz) + " Hz";
        }
    }

    /**
     * Within this many hertz a mode counts as the fixed rate asked for. Screens report 60.000004,
     * 59.94 or 119.88 for their 60 and 120, and no two rates a screen offers sit this close.
     */
    static final float NEAR = 1.5f;

    /** The mode id each window was given here, so Facebook's own value comes back only where this class set one. */
    private static final Map<Window, Integer> given = Collections.synchronizedMap(new WeakHashMap<>());

    private RefreshRate() {
    }

    /**
     * The id of the mode [rate] asks for among [supported], at [current]'s resolution, or 0 for
     * none: Facebook's choice, no current mode, or a fixed rate the screen doesn't offer there.
     */
    static int pick(@Nullable Display.Mode[] supported, @Nullable Display.Mode current, Rate rate) {
        if (rate == Rate.FACEBOOK || supported == null || current == null) return 0;
        Display.Mode best = null;
        for (Display.Mode mode : supported) {
            if (mode == null || mode.getPhysicalWidth() != current.getPhysicalWidth()
                    || mode.getPhysicalHeight() != current.getPhysicalHeight()) continue;
            float hertz = mode.getRefreshRate();
            if (rate == Rate.HIGHEST) {
                if (best == null || hertz > best.getRefreshRate()) best = mode;
            } else if (Math.abs(hertz - rate.hertz) <= NEAR
                    && (best == null || Math.abs(hertz - rate.hertz) < Math.abs(best.getRefreshRate() - rate.hertz))) {
                best = mode;
            }
        }
        return best == null ? 0 : best.getModeId();
    }

    /**
     * The choices for a screen with [supported] modes, now at [current]: Facebook's and the highest
     * always, then each fixed rate the screen offers at that resolution, and [saved] wherever it
     * would fall when the screen doesn't offer it, so the list still shows what's set.
     */
    static List<Rate> offered(@Nullable Display.Mode[] supported, @Nullable Display.Mode current, Rate saved) {
        List<Rate> choices = new ArrayList<>();
        for (Rate rate : Rate.values()) {
            if (rate.hertz == 0 || rate == saved || pick(supported, current, rate) != 0) choices.add(rate);
        }
        return choices;
    }

    /** The choices the default screen offers, for the settings row. */
    public static List<Rate> offered(Context context, Rate saved) {
        Display display = null;
        try {
            DisplayManager displays = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (displays != null) display = displays.getDisplay(Display.DEFAULT_DISPLAY);
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Refresh rate: could not read the screen's modes", failure);
        }
        return display == null ? offered(null, null, saved) : offered(display.getSupportedModes(), display.getMode(), saved);
    }

    /**
     * Gives [window] the mode [rate] asks for on [display], or takes back the one set here when it
     * asks for none.
     *
     * @return whether the window's preferred mode changed
     */
    static boolean apply(Window window, Display display, Rate rate) {
        int wanted = pick(display.getSupportedModes(), display.getMode(), rate);
        WindowManager.LayoutParams attributes = window.getAttributes();
        if (wanted == 0) {
            Integer mine = given.remove(window);
            // Facebook's own value stays wherever this class set nothing, or something else changed it since.
            if (mine == null || attributes.preferredDisplayModeId != mine) return false;
        } else {
            given.put(window, wanted);
        }
        if (attributes.preferredDisplayModeId == wanted) return false;
        attributes.preferredDisplayModeId = wanted;
        window.setAttributes(attributes);
        return true;
    }

    /** A fresh process, for tests. */
    static void forgetForTests() {
        given.clear();
    }

    /** The choice in force, which is Facebook's until the settings are ready and while Hushfacebook is paused. */
    private static Rate chosen() {
        return Utils.settingsReady() ? Settings.REFRESH_RATE.get() : Rate.FACEBOOK;
    }

    /**
     * The screen [activity] shows on. An activity's window manager answers with the activity's
     * own display on every version, where Context.getDisplay() throws on a context it can't tie
     * to one.
     */
    @SuppressWarnings("deprecation")
    private static Display displayOf(Activity activity) {
        return activity.getWindowManager().getDefaultDisplay();
    }

    /**
     * From the settings entry's callbacks, as a Facebook activity is created, and again as it
     * resumes, which also catches a choice made in these settings and a resolution change. Never
     * throws: a failure leaves the window as Facebook had it.
     */
    public static void activity(Activity activity) {
        try {
            Window window = activity.getWindow();
            if (window == null) return;
            Display display = displayOf(activity);
            if (display != null) apply(window, display, chosen());
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Refresh rate: could not set the screen's refresh rate", failure);
        }
    }
}
