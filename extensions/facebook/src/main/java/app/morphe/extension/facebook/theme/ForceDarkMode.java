/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Force dark mode: Facebook's dark mode controller answers dark while the switch is on (#64).
 *
 * <p>Facebook's Settings page comes from its server, and on some tablets it has no Dark mode row,
 * so there's no way to turn dark mode on there, and the AMOLED and Material You themes only show in
 * dark mode. Each answer the controller gives passes through here on its way to {@link DarkMode},
 * which keeps it, so the themes see the forced answer too. Meta's own end-to-end tests force dark
 * mode at the same place: the controller answers dark before it reads the setting.
 *
 * <p>Off, paused, before the settings are ready, in a build without the patch, or when anything
 * here fails, the answer is Facebook's own. Facebook asks as each screen applies its theme, so a
 * change shows fully once Facebook restarts.
 */
public final class ForceDarkMode {
    /** Counted under the patch's name each time a light answer is turned dark. */
    static final String FORCED = "Dark mode answer forced";

    private static final String FAMILY = FamilyNames.FORCE_DARK_MODE;

    /** Whether a test says the patch is in this build, instead of asking {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean inBuildForTests;

    private ForceDarkMode() {
    }

    /** Dark while the switch is on, else [facebooks], the controller's own answer. */
    static boolean answer(boolean facebooks) {
        if (!inBuild()) return facebooks;
        try {
            HookStatus.invoked(FAMILY);
            if (facebooks || !Utils.settingsReady() || !Settings.FORCE_DARK_MODE.get()) return facebooks;
            HookStatus.bound(FAMILY, "dark mode answer");
            HookStatus.counted(FAMILY, FORCED);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "dark mode answer", failure);
            return facebooks;
        }
    }

    /**
     * Whether the switch is on in a build with the patch, and Hushfacebook isn't paused. The
     * settings page opened from a forced dark Facebook reads it to go dark too, since it otherwise
     * follows the phone's night mode, and on a tablet whose Facebook has no Dark mode row the phone
     * is often light (#259).
     */
    public static boolean forcing() {
        return inBuild() && Utils.settingsReady() && Settings.FORCE_DARK_MODE.get();
    }

    /**
     * Whether Force dark mode is in this build. The controller's answer also goes through here when
     * only a theme hooked it, and then the switch isn't on the screen, so it isn't read.
     */
    static boolean inBuild() {
        Boolean forced = inBuildForTests;
        return forced != null ? forced : SettingsStatus.forceDarkMode();
    }
}
