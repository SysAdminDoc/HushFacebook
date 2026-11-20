/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.CrashFile;

/**
 * Hands the shared crash handler its switch as Facebook starts (#528). The switch otherwise comes
 * when {@link Settings} loads, which only a hook reading a setting does. A start that stays on the
 * login screen runs none, so the deaths it found in Android's exit records waited for a switch that
 * never came, and a Java crash there got no file either.
 */
public final class CrashFileSwitch {
    private CrashFileSwitch() {
    }

    /**
     * Injection point, at the start of the application's onCreate() right after the context goes in.
     * Loads the settings off the main thread, so Facebook's start doesn't wait for them, and in a
     * class of its own, so settings that fail to load can't take the start down with them.
     */
    public static void handOver() {
        try {
            Utils.runOnBackgroundThread(CrashFileSwitch::load);
        } catch (Throwable failure) {
            Logger.printException(() -> "Crash file: could not hand the switch over at start", failure);
        }
    }

    static void load() {
        try {
            CrashFile.useSwitch(Settings.SAVE_CRASH_FILE);
        } catch (Throwable failure) {
            Logger.printException(() -> "Crash file: the settings didn't load at start", failure);
        }
    }
}
