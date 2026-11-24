/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import android.os.Build;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.HushfacebookPause;

/**
 * Whether the Material You theme runs (D#527): in the build, and its switch on. Off, every route
 * answers Facebook's colour, or AMOLED's with that theme in the build, and the colours its resources
 * changed go back to Facebook's as Facebook starts ({@link AccentResources#recolour}), so turning it
 * off needs no new patch. Paused reads off, as every switch does.
 *
 * <p>The switch is read once per start, the first time it's asked after the settings are ready:
 * resources taken at the start and routes asked later must agree, so a change waits for the restart
 * its row offers. Before the settings are ready it answers off without keeping the answer, as every
 * hook takes Facebook's own path then, and {@link MaterialYouTheme#load} asks again right after.
 */
public final class MaterialYouSwitch {

    private MaterialYouSwitch() {}

    /** A test's answer for whether the theme is in the build, in place of {@link SettingsStatus}'s. */
    @Nullable
    static volatile Boolean inBuildForTests;

    /** 0 until the switch is read, then 1 for on and 2 for off. */
    private static volatile int read;

    /** Whether the Material You patch is in this build. */
    public static boolean inBuild() {
        Boolean forced = inBuildForTests;
        return forced != null ? forced : SettingsStatus.materialYouTheme();
    }

    /** Whether the theme is in the build and switched on. */
    public static boolean on() {
        return inBuild() && switchedOn();
    }

    /** Whether the theme is in the build and switched off, so its resources go back to Facebook's. */
    public static boolean off() {
        return inBuild() && !switchedOn();
    }

    /**
     * Whether the theme's switch is on, the build aside. MaterialYouTheme asks this alone. Before
     * Android 11 it's on unless Hushfacebook is paused: the resources can only go back through a
     * resource loader, which came in Android 11, and routes off over resources on would leave half
     * a theme, which only a pause accepts.
     */
    public static boolean switchedOn() {
        int known = read;
        if (known != 0) return known == 1;
        if (!Utils.settingsReady()) return false;
        boolean on = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                ? !HushfacebookPause.isPaused()
                : Settings.MATERIAL_YOU.get();
        read = on ? 1 : 2;
        return on;
    }

    /** Forgets the switch's answer and any build a test gave. */
    static void forgetForTests() {
        read = 0;
        inBuildForTests = null;
    }
}
