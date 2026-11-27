/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Ported from SysAdminDoc/HushGram's AppIconTest (GPL-3.0).
 */
package app.morphe.extension.facebook.misc;

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.DONT_KILL_APP;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.PackageManager;

import java.util.Arrays;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Pick the app icon (#467): the picked icon's alias goes on and Facebook's off as Facebook leaves the
 * screen, and Facebook's own calls can't bring its launcher entry back while one of Hushfacebook's
 * is on.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AppIconTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private Activity activity;
    private PackageManager manager;
    private String owner;

    @Before public void prepare() {
        Settings.APP_ICON.resetToDefault();
        PauseForTests.resume();
        AppIcon.forgetForTests();
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        manager = activity.getPackageManager();
        owner = activity.getPackageName();
    }

    @After public void restore() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        controller.close();
        AppIcon.forgetForTests();
        PauseForTests.resume();
        Settings.APP_ICON.resetToDefault();
    }

    private ComponentName alias(AppIconChoice icon) {
        return AppIcon.alias(owner, icon);
    }

    private int state(AppIconChoice icon) {
        return manager.getComponentEnabledSetting(alias(icon));
    }

    /** Facebook's screens leave the screen, and the icon changes behind them. */
    private void leave() throws Exception {
        AppIcon.started();
        AppIcon.stopped(activity);
        Utils.awaitBackgroundTasksForTests();
    }

    @Test public void theOneMorpheDrewChangesNothing() throws Exception {
        leave();
        for (AppIconChoice icon : AppIconChoice.values()) assertEquals(icon.name(), COMPONENT_ENABLED_STATE_DEFAULT, state(icon));
    }

    @Test public void thePickedIconTakesOverOnTheWayOut() throws Exception {
        Settings.APP_ICON.save(AppIconChoice.HUSHFACEBOOK);
        AppIcon.started();
        assertEquals("not while Facebook is on screen", COMPONENT_ENABLED_STATE_DEFAULT, state(AppIconChoice.HUSHFACEBOOK));
        AppIcon.stopped(activity);
        Utils.awaitBackgroundTasksForTests();
        assertEquals(COMPONENT_ENABLED_STATE_ENABLED, state(AppIconChoice.HUSHFACEBOOK));
        assertEquals(COMPONENT_ENABLED_STATE_DISABLED, state(AppIconChoice.AS_PATCHED));
        assertEquals(COMPONENT_ENABLED_STATE_DEFAULT, state(AppIconChoice.FACEBOOK));
        assertEquals(COMPONENT_ENABLED_STATE_DEFAULT, state(AppIconChoice.WHITE_ON_BLACK));
        assertEquals("Facebook keeps running", DONT_KILL_APP,
                shadowOf(manager).getComponentEnabledSettingFlags(alias(AppIconChoice.AS_PATCHED)));
        assertFalse("nothing left to change", AppIcon.apply(activity));
    }

    @Test public void oneScreenStillOpenIsntLeaving() throws Exception {
        Settings.APP_ICON.save(AppIconChoice.BLACK_ON_WHITE);
        AppIcon.started();
        AppIcon.started();
        AppIcon.stopped(activity);
        Utils.awaitBackgroundTasksForTests();
        assertEquals(COMPONENT_ENABLED_STATE_DEFAULT, state(AppIconChoice.BLACK_ON_WHITE));
        AppIcon.stopped(activity);
        Utils.awaitBackgroundTasksForTests();
        assertEquals(COMPONENT_ENABLED_STATE_ENABLED, state(AppIconChoice.BLACK_ON_WHITE));
    }

    @Test public void anotherPickSwapsHushfacebooksIcons() throws Exception {
        Settings.APP_ICON.save(AppIconChoice.HUSHFACEBOOK);
        leave();
        Settings.APP_ICON.save(AppIconChoice.FACEBOOK);
        leave();
        assertEquals(COMPONENT_ENABLED_STATE_ENABLED, state(AppIconChoice.FACEBOOK));
        assertEquals(COMPONENT_ENABLED_STATE_DEFAULT, state(AppIconChoice.HUSHFACEBOOK));
        assertEquals(COMPONENT_ENABLED_STATE_DISABLED, state(AppIconChoice.AS_PATCHED));
    }

    @Test public void theOneMorpheDrewComesBackAsShipped() throws Exception {
        Settings.APP_ICON.save(AppIconChoice.HUSHFACEBOOK_WHITE_ON_BLACK);
        leave();
        Settings.APP_ICON.save(AppIconChoice.AS_PATCHED);
        leave();
        for (AppIconChoice icon : AppIconChoice.values()) assertEquals(icon.name(), COMPONENT_ENABLED_STATE_DEFAULT, state(icon));
    }

    /** One of Facebook's own alternate icons turned its launcher entry off, and that's Facebook's to undo. */
    @Test public void facebooksAlternateIconStays() {
        manager.setComponentEnabledSetting(alias(AppIconChoice.AS_PATCHED), COMPONENT_ENABLED_STATE_DISABLED, DONT_KILL_APP);
        assertFalse(AppIcon.apply(activity));
        assertEquals(COMPONENT_ENABLED_STATE_DISABLED, state(AppIconChoice.AS_PATCHED));
    }

    @Test public void aPauseKeepsThePickedIcon() throws Exception {
        Settings.APP_ICON.save(AppIconChoice.HUSHFACEBOOK);
        leave();
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        leave();
        assertEquals(COMPONENT_ENABLED_STATE_ENABLED, state(AppIconChoice.HUSHFACEBOOK));
        assertEquals(COMPONENT_ENABLED_STATE_DISABLED, state(AppIconChoice.AS_PATCHED));
    }

    /** Facebook turns its launcher entry back on at some starts; with Hushfacebook's on, that call goes nowhere. */
    @Test public void facebookCantBringItsIconBackWhileHushfacebooksIsOn() throws Exception {
        Settings.APP_ICON.save(AppIconChoice.HUSHFACEBOOK);
        leave();
        AppIcon.setComponentEnabledSetting(manager, alias(AppIconChoice.AS_PATCHED), COMPONENT_ENABLED_STATE_ENABLED, DONT_KILL_APP);
        AppIcon.setComponentEnabledSetting(manager, alias(AppIconChoice.AS_PATCHED), COMPONENT_ENABLED_STATE_DEFAULT, DONT_KILL_APP);
        assertEquals(COMPONENT_ENABLED_STATE_DISABLED, state(AppIconChoice.AS_PATCHED));
        AppIcon.setComponentEnabledSetting(manager, alias(AppIconChoice.HUSHFACEBOOK), COMPONENT_ENABLED_STATE_DISABLED, DONT_KILL_APP);
        assertEquals("nor touch Hushfacebook's", COMPONENT_ENABLED_STATE_ENABLED, state(AppIconChoice.HUSHFACEBOOK));

        ComponentName other = new ComponentName(owner, "com.facebook.push.fbns.FbnsReceiver");
        AppIcon.setComponentEnabledSetting(manager, other, COMPONENT_ENABLED_STATE_DISABLED, DONT_KILL_APP);
        assertEquals("everything else goes through", COMPONENT_ENABLED_STATE_DISABLED, manager.getComponentEnabledSetting(other));
        ComponentName alternate = new ComponentName(owner, AppIcons.LAUNCHER + ".blue_fb");
        AppIcon.setComponentEnabledSetting(manager, alternate, COMPONENT_ENABLED_STATE_ENABLED, DONT_KILL_APP);
        assertEquals("Facebook's own alternate icons too", COMPONENT_ENABLED_STATE_ENABLED, manager.getComponentEnabledSetting(alternate));
    }

    @Test public void withTheOneMorpheDrewPickedFacebooksCallsGoThrough() {
        AppIcon.setComponentEnabledSetting(manager, alias(AppIconChoice.AS_PATCHED), COMPONENT_ENABLED_STATE_DISABLED, DONT_KILL_APP);
        assertEquals(COMPONENT_ENABLED_STATE_DISABLED, state(AppIconChoice.AS_PATCHED));
        AppIcon.setComponentEnabledSetting(manager, alias(AppIconChoice.AS_PATCHED), COMPONENT_ENABLED_STATE_ENABLED, DONT_KILL_APP);
        assertEquals(COMPONENT_ENABLED_STATE_ENABLED, state(AppIconChoice.AS_PATCHED));
    }

    @Test public void severalAtOnceLeavesOutOnlyTheOnesKeptOut() throws Exception {
        Settings.APP_ICON.save(AppIconChoice.WHITE_ON_BLACK);
        leave();
        ComponentName other = new ComponentName(owner, "com.facebook.push.fbns.FbnsReceiver");
        AppIcon.setComponentEnabledSettings(manager, Arrays.asList(
                new PackageManager.ComponentEnabledSetting(other, COMPONENT_ENABLED_STATE_DISABLED, DONT_KILL_APP),
                new PackageManager.ComponentEnabledSetting(alias(AppIconChoice.AS_PATCHED), COMPONENT_ENABLED_STATE_ENABLED, DONT_KILL_APP),
                new PackageManager.ComponentEnabledSetting(alias(AppIconChoice.WHITE_ON_BLACK), COMPONENT_ENABLED_STATE_DEFAULT, DONT_KILL_APP)));
        assertEquals(COMPONENT_ENABLED_STATE_DISABLED, manager.getComponentEnabledSetting(other));
        assertEquals(COMPONENT_ENABLED_STATE_DISABLED, state(AppIconChoice.AS_PATCHED));
        assertEquals(COMPONENT_ENABLED_STATE_ENABLED, state(AppIconChoice.WHITE_ON_BLACK));
    }
}
