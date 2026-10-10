/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import android.content.ComponentName;
import android.content.pm.PackageManager;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What the Unlock app icons patch asks when Facebook checks whether the account may use its paid
 * app icons.
 *
 * <p>Facebook 582 ships every alternate launcher icon in the app, as disabled activity-aliases of
 * its launcher, and Settings and privacy > App icon switches between them on the phone with no
 * server call. Which ones the picker unlocks, and whether the start-up job puts the default icon
 * back, both come from the account's Facebook Plus benefits: the benefit provider's check of a
 * benefit name, and the picker's own look at each synced set of benefits. While the switch is on,
 * {@link #unlocked} answers the provider's check yes for the app icon benefit alone, and
 * {@link #entitled} answers the picker's look yes, so every icon applies the way a free one does and
 * stays. Every other benefit is Facebook's answer.
 *
 * <p>The first start after an update also runs Facebook's component manager, which puts every
 * component back to its manifest state: the launcher entry on and each icon alias off, so a picked
 * icon was lost on every repatch. While the switch is on, {@link #componentState} skips the launcher
 * entry and its aliases there and passes every other component through.
 *
 * <p>It fails closed to Facebook: the switch off, a pause, settings that aren't ready yet, or a
 * failure in here, and Facebook's own answer stands.
 */
public final class AppIcons {
    /** The benefit name Facebook gives its paid app icons. */
    public static final String BENEFIT = "CUSTOM_APP_ICON";

    /** The launcher entry. Every app icon is an activity-alias of it named {@code LAUNCHER + ".<icon>"}. */
    public static final String LAUNCHER = "com.facebook.katana.LoginActivity";

    /** The diagnostic counter route: each check Facebook made, and the ones answered yes. */
    static final String ROUTE = "App icons";

    private AppIcons() {
    }

    /**
     * Injection point, first in the benefit provider's check of one benefit. True answers the check
     * yes at once, for the app icon benefit only. Never throws.
     */
    public static boolean unlocked(@Nullable String benefit) {
        if (!BENEFIT.equals(benefit)) return false;
        return unlock("benefit check");
    }

    /**
     * Injection point, on the picker's look at a synced set of benefits. Answers [facebook], or true
     * while the switch unlocks the icons. Never throws.
     */
    public static boolean entitled(boolean facebook) {
        if (!facebook) return unlock("picker's benefit set");
        try {
            HookStatus.invoked(FamilyNames.APP_ICONS);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.APP_ICONS, "picker's benefit set", failure);
        }
        return true;
    }

    /**
     * Injection point, in place of the component manager's PackageManager.setComponentEnabledSetting
     * call. Makes that call unless {@link #keepsIcon} keeps the component as it is, so the call's own
     * errors still reach the manager.
     */
    public static void componentState(PackageManager manager, ComponentName component, int state, int flags) {
        if (keepsIcon(component)) return;
        manager.setComponentEnabledSetting(component, state, flags);
    }

    /**
     * Whether the component manager leaves [component] as it is: the launcher entry or one of its
     * icon aliases, while the switch is on. Never throws.
     */
    public static boolean keepsIcon(@Nullable ComponentName component) {
        try {
            String name = component == null ? null : component.getClassName();
            if (name == null || !(name.equals(LAUNCHER) || name.startsWith(LAUNCHER + "."))) return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.APP_ICONS, "update reset", failure);
            return false;
        }
        return unlock("update reset");
    }

    /** True while the switch unlocks the icons, counted under [hook]. Never throws. */
    private static boolean unlock(String hook) {
        try {
            HookStatus.invoked(FamilyNames.APP_ICONS);
            FeedFilterCounters.sawList(ROUTE, 1);
            if (!Utils.settingsReady() || !Settings.UNLOCK_APP_ICONS.get()) return false;
            FeedFilterCounters.removed(ROUTE, 1, hook);
            Logger.printDebug(() -> "App icons: unlocked for the " + hook);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.APP_ICONS, hook, failure);
            return false;
        }
    }
}
