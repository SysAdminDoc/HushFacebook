/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.extension.facebook.misc;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Pick the app icon" patch (#467): the icon on the home screen and in the app drawer,
 * picked in Hushfacebook's settings.
 *
 * <p>Android shows an icon for each enabled launcher activity. Facebook's is the alias
 * {@link AppIcons#LAUNCHER}, and the patch adds a copy of it per {@link AppIconChoice}, off, with its
 * own icon, named {@link #HUSHFACEBOOK_ALIAS} and the choice's key. {@link #apply} turns the picked
 * one on and Facebook's off when Facebook goes to the background, since a launcher activity switched
 * off under a running screen can close it. It changes only what differs, and puts the new icon on
 * before the old one comes off, so there's never a moment without one. Android then tells Facebook
 * its launcher changed, and the settings entry publishes its shortcuts again on the alias that's on
 * (#119).
 *
 * <p>Facebook's component manager puts its components back the way the manifest has them, on the
 * first start after an update among others: its launcher entry on, every alias off. The patch sends
 * every call Facebook makes to turn a component on or off here instead, and {@link #keepsOut} drops
 * the ones that would touch Hushfacebook's aliases or bring Facebook's entry back while one of
 * Hushfacebook's is on. It goes by what's on, not by the setting, so it holds before the settings are
 * read. Hushfacebook's aliases carry the same {@code default-state} meta-data as Facebook's own
 * alternate icons.
 *
 * <p>A build without the patch has no Hushfacebook aliases, and Android forgets the state of a
 * component that's gone, so Facebook's own start turns its entry back on there.
 */
public final class AppIcon {
    /** The start of each Hushfacebook alias's name, which ends in its choice's key. */
    static final String HUSHFACEBOOK_ALIAS = AppIcons.LAUNCHER + ".hushfacebook_";

    /** What's counted each time one of Facebook's calls is dropped, and each time the icon changes. */
    static final String KEPT_OUT = "Facebook's change to the launcher icon kept out";
    static final String CHANGED = "home screen icon changed";

    private static final int ON = PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    private static final int OFF = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    private static final int AS_SHIPPED = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;

    private static final Object LOCK = new Object();
    private static volatile boolean registered;
    /** Facebook's screens started and not yet stopped, on the main thread. */
    private static int started;

    private AppIcon() {
    }

    /** Called as the application starts. Watches Facebook's screens in a build with the patch. */
    public static void install(Context context) {
        try {
            if (registered || !(context instanceof Application) || !SettingsStatus.appIcon()
                    || !Utils.isMainProcess()) return;
            registered = true;
            ((Application) context).registerActivityLifecycleCallbacks(new Watcher());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.APP_ICON, "start", failure);
        }
    }

    // Facebook's own calls that turn a component on or off come here instead: the patch sends each
    // PackageManager call of these names to the method of the same name here, the manager first.

    public static void setComponentEnabledSetting(PackageManager manager, ComponentName component, int state, int flags) {
        if (keepsOut(manager, component, state)) return;
        manager.setComponentEnabledSetting(component, state, flags);
    }

    /** The same for several at once, which Android 13 added. The ones kept out are left out of the call. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public static void setComponentEnabledSettings(PackageManager manager,
                                                   List<PackageManager.ComponentEnabledSetting> settings) {
        List<PackageManager.ComponentEnabledSetting> kept = null;
        for (int at = 0; at < settings.size(); at++) {
            PackageManager.ComponentEnabledSetting setting = settings.get(at);
            boolean out = setting != null && keepsOut(manager, setting.getComponentName(), setting.getEnabledState());
            if (out && kept == null) kept = new ArrayList<>(settings.subList(0, at));
            else if (!out && kept != null) kept.add(setting);
        }
        if (kept == null) manager.setComponentEnabledSettings(settings);
        else if (!kept.isEmpty()) manager.setComponentEnabledSettings(kept);
    }

    /**
     * Whether Facebook's call setting [component] to [state] is dropped: any call on a Hushfacebook
     * alias, and a call that would turn Facebook's launcher entry on while a Hushfacebook alias is
     * on. Turning Facebook's entry off always goes through. Anything thrown lets the call go through.
     */
    static boolean keepsOut(PackageManager manager, @Nullable ComponentName component, int state) {
        try {
            if (component == null) return false;
            String name = component.getClassName();
            boolean ours = name.startsWith(HUSHFACEBOOK_ALIAS);
            if (!ours && (!AppIcons.LAUNCHER.equals(name) || state == OFF)) return false;
            HookStatus.invoked(FamilyNames.APP_ICON);
            if (!ours && turnedOn(manager, component.getPackageName()) == null) return false;
            HookStatus.counted(FamilyNames.APP_ICON, KEPT_OUT);
            return true;
        } catch (RuntimeException failure) {
            HookStatus.threw(FamilyNames.APP_ICON, "Facebook's icon change", failure);
            return false;
        }
    }

    /** The Hushfacebook icon whose alias is on in [owner], the package, or null when none is. */
    @Nullable
    static AppIconChoice turnedOn(PackageManager manager, String owner) {
        for (AppIconChoice choice : AppIconChoice.values()) {
            if (choice.key != null && manager.getComponentEnabledSetting(alias(owner, choice)) == ON) return choice;
        }
        return null;
    }

    static ComponentName alias(String owner, AppIconChoice choice) {
        return new ComponentName(owner, choice.key == null ? AppIcons.LAUNCHER : HUSHFACEBOOK_ALIAS + choice.key);
    }

    /**
     * Turns the picked icon's alias on and the others off, on the thread it's called on. Facebook's
     * entry comes back on only when it's a Hushfacebook alias going off, so one of Facebook's own
     * alternate icons stays as Facebook left it. Reads the saved pick, so a pause leaves the icon as
     * it is, the way it leaves the one Morphe drew. Answers whether anything changed. Does nothing
     * before the settings are read. Never throws.
     */
    static boolean apply(Context context) {
        try {
            if (!Utils.settingsReady()) return false;
            HookStatus.invoked(FamilyNames.APP_ICON);
            AppIconChoice wanted = Settings.APP_ICON.savedValue();
            PackageManager manager = context.getPackageManager();
            String owner = context.getPackageName();
            boolean changed = false;
            synchronized (LOCK) {
                if (wanted.key != null) {
                    changed = set(manager, alias(owner, wanted), ON);
                } else if (turnedOn(manager, owner) != null) {
                    changed = set(manager, alias(owner, AppIconChoice.AS_PATCHED), AS_SHIPPED);
                }
                for (AppIconChoice choice : AppIconChoice.values()) {
                    if (choice.key != null && choice != wanted
                            && manager.getComponentEnabledSetting(alias(owner, choice)) == ON) {
                        changed |= set(manager, alias(owner, choice), AS_SHIPPED);
                    }
                }
                if (wanted.key != null) changed |= set(manager, alias(owner, AppIconChoice.AS_PATCHED), OFF);
            }
            if (changed) HookStatus.counted(FamilyNames.APP_ICON, CHANGED);
            return changed;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.APP_ICON, "icon change", failure);
            return false;
        }
    }

    /** Sets [component] to [state] when it isn't already. Answers whether it changed. */
    private static boolean set(PackageManager manager, ComponentName component, int state) {
        if (manager.getComponentEnabledSetting(component) == state) return false;
        manager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP);
        return true;
    }

    static void started() {
        started++;
    }

    /**
     * One of Facebook's screens stopped. Once none is left, the icon changes on a background thread.
     * A screen turning sideways stops and starts again at once, which isn't leaving.
     */
    static void stopped(Activity activity) {
        if (started > 0) started--;
        if (started > 0 || activity.isChangingConfigurations()) return;
        Context app = activity.getApplicationContext() != null ? activity.getApplicationContext() : activity;
        Utils.runOnBackgroundThread(() -> apply(app));
    }

    /** Forgets what this process knows, as a new process would. */
    static void forgetForTests() {
        started = 0;
    }

    static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityStarted(@NonNull Activity activity) {
            started();
        }

        @Override public void onActivityStopped(@NonNull Activity activity) {
            stopped(activity);
        }

        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityResumed(@NonNull Activity activity) { }
        @Override public void onActivityPaused(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
        @Override public void onActivityDestroyed(@NonNull Activity activity) { }
    }
}
