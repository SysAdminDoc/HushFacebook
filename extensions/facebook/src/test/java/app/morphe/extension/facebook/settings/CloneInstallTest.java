/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.net.Uri;

import java.util.EnumSet;

import app.morphe.extension.facebook.misc.AppIcons;
import app.morphe.extension.shared.SettingsContextRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * Hushfacebook settings in a copy installed under another package name (#417). De-Vanced's
 * renamed Facebook installs, but its settings screen never opens. Here every way in goes by the
 * running package: the launcher shortcut and a note's button name the copy's own launcher entry,
 * so Android starts the copy and not the preinstalled Facebook, the screen opens over the
 * activity they start, and an icon change in the copy is the copy's to follow.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class CloneInstallTest {
    private static final String CLONE = "com.facebook.katana.hush";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final SettingsEntry.OpenWhenResumed watcher = new SettingsEntry.OpenWhenResumed();

    /** The application the way the copy sees it: everything Facebook's, under the copy's name. */
    private static Context copy() {
        return new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public String getPackageName() {
                return CLONE;
            }

            @Override public Context getApplicationContext() {
                return this;
            }
        };
    }

    @Before public void watch() {
        RuntimeEnvironment.getApplication().registerActivityLifecycleCallbacks(watcher);
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
    }

    @After public void stopWatching() {
        RuntimeEnvironment.getApplication().unregisterActivityLifecycleCallbacks(watcher);
        PatchFamily.inBuildForTests = null;
        Settings.SETTINGS_SHORTCUT.resetToDefault();
    }

    private static ShortcutInfo ours(ShortcutManager manager) {
        for (ShortcutInfo shortcut : manager.getDynamicShortcuts()) {
            if (SettingsEntry.SHORTCUT_ID.equals(shortcut.getId())) return shortcut;
        }
        return null;
    }

    @Test public void theShortcutAndANotesButtonOpenTheCopy() {
        Context app = copy();
        ShortcutManager manager = app.getSystemService(ShortcutManager.class);
        manager.removeAllDynamicShortcuts();
        SettingsEntry.publishShortcutNow(app);

        ShortcutInfo shortcut = ours(manager);
        assertNotNull("no settings shortcut in the copy", shortcut);
        ComponentName entry = new ComponentName(CLONE, AppIcons.LAUNCHER);
        assertEquals("the shortcut would start the preinstalled Facebook", entry, shortcut.getIntent().getComponent());
        assertTrue(shortcut.getIntent().getBooleanExtra(SettingsEntry.EXTRA_OPEN_SETTINGS, false));

        Intent button = SettingsEntry.settingIntent(app, Settings.DOWNLOAD_COMPATIBLE.key);
        assertEquals(entry, button.getComponent());
        assertEquals(Settings.DOWNLOAD_COMPATIBLE.key, button.getStringExtra(SettingsEntry.EXTRA_SHOW_SETTING));
    }

    /** Android starts the copy's launcher entry with the shortcut's intent, and the screen opens over it. */
    @Test public void theScreenOpensOverWhatTheCopysShortcutStarts() {
        Context app = copy();
        Intent shortcut = SettingsEntry.settingIntent(app, Settings.DOWNLOAD_COMPATIBLE.key);
        ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class, shortcut).create();
        try {
            SettingsEntry.onActivityCreate(activity.get());
            activity.start().resume();
            ShadowLooper.idleMainLooper();
            assertNotNull("the screen did not open in the copy",
                    activity.get().getFragmentManager().findFragmentByTag("hushfacebook_settings"));
        } finally {
            activity.pause().stop().destroy();
        }
    }

    /** A picked icon turns the copy's own alias on, and the shortcut follows it there. */
    @Test public void aPickedIconInTheCopyIsTheOneTheShortcutNames() {
        Context app = copy();
        PackageManager packages = RuntimeEnvironment.getApplication().getPackageManager();
        PackageInfo copy = new PackageInfo();
        copy.packageName = CLONE;
        copy.applicationInfo = new ApplicationInfo();
        copy.applicationInfo.packageName = CLONE;
        Shadows.shadowOf(packages).installPackage(copy);
        ComponentName icon = new ComponentName(CLONE, AppIcons.LAUNCHER + ".vaporwave_ic");
        IntentFilter main = new IntentFilter(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        Shadows.shadowOf(packages).addActivityIfNotPresent(icon);
        Shadows.shadowOf(packages).addIntentFilterForActivity(icon, main);

        assertEquals(icon, AppIcons.launcher(app));
        assertEquals(icon, SettingsEntry.settingIntent(app, Settings.DOWNLOAD_COMPATIBLE.key).getComponent());
    }

    @Test public void onlyTheCopysOwnLauncherChangeCounts() {
        String[] launcher = {AppIcons.LAUNCHER, AppIcons.LAUNCHER + ".vaporwave_ic"};
        assertTrue(SettingsEntry.namesLauncher(CLONE, packageChanged(CLONE, launcher)));
        assertTrue("the whole copy", SettingsEntry.namesLauncher(CLONE, packageChanged(CLONE, CLONE)));
        assertFalse("the preinstalled Facebook's", SettingsEntry.namesLauncher(CLONE, packageChanged("com.facebook.katana", launcher)));
        assertFalse(SettingsEntry.namesLauncher(CLONE, packageChanged(CLONE, CLONE + ".SomeService")));
    }

    private static Intent packageChanged(String packageName, String... components) {
        return new Intent(Intent.ACTION_PACKAGE_CHANGED, Uri.parse("package:" + packageName))
                .putExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST, components);
    }
}
