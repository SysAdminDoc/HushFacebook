/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.pm.PackageManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Facebook asking whether the account may use its Facebook Plus app icons: yes while the switch is
 * on, for the app icon benefit alone, and Facebook's own answer otherwise.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AppIconsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** The patch is in Morphe Manager's default selection with its switch off; these tests turn it on. */
    @Before
    public void turnTheSwitchOn() {
        Settings.UNLOCK_APP_ICONS.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.UNLOCK_APP_ICONS.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private static String counterLine() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(AppIcons.ROUTE + ":")) return line;
        }
        return null;
    }

    @Test
    public void theSwitchStartsOffAndOnAnswersBothChecksYes() {
        assertFalse("the switch starts off", Settings.UNLOCK_APP_ICONS.defaultValue);
        assertEquals("the benefit name Facebook 582 checks", "CUSTOM_APP_ICON", AppIcons.BENEFIT);
        assertTrue(AppIcons.unlocked(AppIcons.BENEFIT));
        assertTrue(AppIcons.entitled(false));
        String line = counterLine();
        assertTrue(line, line.startsWith(AppIcons.ROUTE + ": 2 lists, 2 items, 2 removed. Last reason: picker's benefit set"));
        assertTrue(line, line.contains("benefit check 1"));
        assertTrue(HookStatus.report("").toString(), HookStatus.report("").toString()
                .contains(FamilyNames.APP_ICONS + ": invoked"));
    }

    /** The provider answers every other benefit, Facebook's paid ones included, as Facebook does. */
    @Test
    public void everyOtherBenefitIsFacebooksAnswer() {
        assertFalse(AppIcons.unlocked("CUSTOM_STICKERS"));
        assertFalse(AppIcons.unlocked("custom_app_icon"));
        assertFalse(AppIcons.unlocked(""));
        assertFalse(AppIcons.unlocked(null));
        assertNull("other benefits aren't counted", counterLine());
    }

    /** An account that already has the benefit keeps it, whatever the switch says. */
    @Test
    public void facebooksYesStandsOffOrPaused() {
        Settings.UNLOCK_APP_ICONS.save(false);
        assertTrue(AppIcons.entitled(true));
        Settings.UNLOCK_APP_ICONS.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertTrue(AppIcons.entitled(true));
        assertNull("Facebook's own yes isn't counted", counterLine());
    }

    @Test
    public void offOrPausedFacebooksNoStands() {
        Settings.UNLOCK_APP_ICONS.save(false);
        assertFalse(AppIcons.unlocked(AppIcons.BENEFIT));
        assertFalse(AppIcons.entitled(false));
        assertEquals(AppIcons.ROUTE + ": 2 lists, 2 items, 0 removed", counterLine());
        Settings.UNLOCK_APP_ICONS.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(AppIcons.unlocked(AppIcons.BENEFIT));
        assertFalse(AppIcons.entitled(false));
        PauseForTests.resume();
        assertTrue(AppIcons.unlocked(AppIcons.BENEFIT));
    }

    private static ComponentName component(String className) {
        return new ComponentName(RuntimeEnvironment.getApplication().getPackageName(), className);
    }

    /** The update reset keeps the launcher entry and every alias of it, and nothing else. */
    @Test
    public void theUpdateResetKeepsTheLauncherEntryAndItsAliases() {
        assertEquals("Facebook 582's launcher entry", "com.facebook.katana.LoginActivity", AppIcons.LAUNCHER);
        assertTrue(AppIcons.keepsIcon(component(AppIcons.LAUNCHER)));
        assertTrue(AppIcons.keepsIcon(component(AppIcons.LAUNCHER + ".vaporwave_ic")));
        assertTrue(AppIcons.keepsIcon(component(AppIcons.LAUNCHER + ".blue_fb")));
        assertTrue(AppIcons.keepsIcon(component(AppIcons.LAUNCHER + ".katana")));
        String line = counterLine();
        assertTrue(line, line.startsWith(AppIcons.ROUTE + ": 4 lists, 4 items, 4 removed. Last reason: update reset"));

        assertFalse(AppIcons.keepsIcon(component(AppIcons.LAUNCHER + "Alias")));
        assertFalse(AppIcons.keepsIcon(component("com.facebook.katana.activity.FbMainTabActivity")));
        assertFalse(AppIcons.keepsIcon(component("com.facebook.composer.shareintent.AddToStoryAlias")));
        assertFalse(AppIcons.keepsIcon(null));
        assertTrue("other components aren't counted", counterLine().startsWith(AppIcons.ROUTE + ": 4 lists, 4 items, 4 removed"));
    }

    /** Off or paused, the manager's reset reaches the icons as it does in Facebook. Other components always reach it. */
    @Test
    public void theManagersCallGoesThroughUnlessAnIconIsKept() {
        PackageManager manager = RuntimeEnvironment.getApplication().getPackageManager();
        ComponentName alias = component(AppIcons.LAUNCHER + ".vaporwave_ic");
        ComponentName other = component("com.facebook.composer.shareintent.AddToStoryAlias");
        int off = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;

        AppIcons.componentState(manager, alias, off, PackageManager.DONT_KILL_APP);
        AppIcons.componentState(manager, other, off, PackageManager.DONT_KILL_APP);
        assertEquals("a picked icon stays", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, manager.getComponentEnabledSetting(alias));
        assertEquals("another component is set", off, manager.getComponentEnabledSetting(other));

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        AppIcons.componentState(manager, alias, off, PackageManager.DONT_KILL_APP);
        assertEquals("paused, Facebook resets the icon", off, manager.getComponentEnabledSetting(alias));
        PauseForTests.resume();

        ComponentName entry = component(AppIcons.LAUNCHER);
        Settings.UNLOCK_APP_ICONS.save(false);
        AppIcons.componentState(manager, entry, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        assertEquals("off, Facebook resets the launcher entry", PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                manager.getComponentEnabledSetting(entry));
    }
}
