/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.theme.DarkMode;
import app.morphe.extension.facebook.theme.DarkModeForTests;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.SettingReadsForTests;

/**
 * Light bars on the Reels tab (#423): with the switch on and Facebook in light mode, the Reels tab's
 * id reads as no tab's for the tab bar and the status bar, its own dark bars are skipped and its
 * status bar is the other tabs'. Facebook's answers stand in dark mode, before Facebook has said
 * which mode it's in, with the switch off, paused, before the settings are ready and when the
 * switch can't be read, and every other tab's id goes through untouched.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class LightReelsTabBarsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final long FEED_TAB = 0xacdac0374854L;

    @Before
    public void start() {
        HookStatus.clear();
        DarkModeForTests.forget();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.LIGHT_REELS_TAB_BARS.resetToDefault();
        DarkModeForTests.forget();
        HookStatus.clear();
    }

    private static void lightMode() {
        DarkMode.answer(false);
    }

    private static void assertFacebooksAnswers(String why) {
        assertEquals(why, Long.valueOf(LightReelsTabBars.VIDEO_TAB), LightReelsTabBars.tabBarTab(LightReelsTabBars.VIDEO_TAB));
        assertEquals(why, LightReelsTabBars.VIDEO_TAB, LightReelsTabBars.reelsTabBarTab(LightReelsTabBars.VIDEO_TAB));
        assertEquals(why, LightReelsTabBars.VIDEO_TAB, LightReelsTabBars.barsTab(LightReelsTabBars.VIDEO_TAB));
        assertTrue(why, LightReelsTabBars.darkBars(true));
        assertFalse(why, LightReelsTabBars.lightStatusBar());
    }

    @Test
    public void theSwitchStartsOffInTheTabBarFamilyAndNeedsNoRestart() {
        assertFalse(Settings.LIGHT_REELS_TAB_BARS.defaultValue);
        assertFalse("a change shows on the next visit to the tab", Settings.LIGHT_REELS_TAB_BARS.rebootApp);
        assertTrue(PatchFamily.BOTTOM_TAB_BAR.switches.contains(Settings.LIGHT_REELS_TAB_BARS));
        lightMode();
        assertFacebooksAnswers("the Reels tab went light with the switch off");
    }

    @Test
    public void theReelsTabGetsTheOtherTabsBarsInLightMode() {
        Settings.LIGHT_REELS_TAB_BARS.save(true);
        lightMode();
        assertNull("the tab bar kept the Reels tab's dark colours", LightReelsTabBars.tabBarTab(LightReelsTabBars.VIDEO_TAB));
        assertEquals("the Reels tab still asked for a dark tab bar", 0L, LightReelsTabBars.reelsTabBarTab(LightReelsTabBars.VIDEO_TAB));
        assertEquals("the status bar kept the Reels tab's dark look", 0L, LightReelsTabBars.barsTab(LightReelsTabBars.VIDEO_TAB));
        assertFalse("the Reels tab's own dark bars were painted", LightReelsTabBars.darkBars(true));
        assertTrue("the Reels tab built its own status bar", LightReelsTabBars.lightStatusBar());
    }

    @Test
    public void otherTabsAndFacebooksNoGoThroughUntouched() {
        Settings.LIGHT_REELS_TAB_BARS.save(true);
        lightMode();
        assertEquals(Long.valueOf(FEED_TAB), LightReelsTabBars.tabBarTab(FEED_TAB));
        assertNull(LightReelsTabBars.tabBarTab(null));
        assertEquals(FEED_TAB, LightReelsTabBars.barsTab(FEED_TAB));
        assertEquals(FEED_TAB, LightReelsTabBars.reelsTabBarTab(FEED_TAB));
        assertFalse(LightReelsTabBars.darkBars(false));
        assertNull("an untouched answer was counted", line());
    }

    @Test
    public void darkModeKeepsFacebooksBars() {
        Settings.LIGHT_REELS_TAB_BARS.save(true);
        assertFacebooksAnswers("the Reels tab went light before Facebook said which mode it's in");
        DarkMode.answer(true);
        assertFacebooksAnswers("the Reels tab went light in dark mode");
        lightMode();
        assertTrue("light mode after dark didn't take", LightReelsTabBars.lightStatusBar());
    }

    @Test
    public void facebooksBarsStandWhilePausedOrBeforeTheSettingsAreReady() {
        Settings.LIGHT_REELS_TAB_BARS.save(true);
        lightMode();
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFacebooksAnswers("the Reels tab went light while paused");
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFacebooksAnswers("the Reels tab went light before the settings were ready"));
    }

    @Test
    public void eachChangeIsCounted() {
        Settings.LIGHT_REELS_TAB_BARS.save(true);
        lightMode();
        LightReelsTabBars.tabBarTab(LightReelsTabBars.VIDEO_TAB);
        LightReelsTabBars.reelsTabBarTab(LightReelsTabBars.VIDEO_TAB);
        LightReelsTabBars.barsTab(LightReelsTabBars.VIDEO_TAB);
        LightReelsTabBars.lightStatusBar();
        LightReelsTabBars.darkBars(true);
        LightReelsTabBars.darkBars(true);
        String line = line();
        assertNotNull("no " + FamilyNames.BOTTOM_TAB_BAR + " line: " + HookStatus.report(), line);
        assertTrue(line, line.contains(LightReelsTabBars.TAB_BAR + " 2"));
        assertTrue(line, line.contains(LightReelsTabBars.STATUS_BAR + " 2"));
        assertTrue(line, line.contains(LightReelsTabBars.DARK_BARS + " 2"));
    }

    /** A switch that can't be read leaves Facebook's bars, and the report names the hook. */
    @Test
    public void aSwitchThatCantBeReadLeavesFacebooksBars() {
        lightMode();
        SettingReadsForTests.breakReads(Settings.LIGHT_REELS_TAB_BARS);
        try {
            assertFacebooksAnswers("the Reels tab went light with a switch that can't be read");
        } finally {
            SettingReadsForTests.mend(Settings.LIGHT_REELS_TAB_BARS);
        }
        String missing = HookStatus.missing(FamilyNames.BOTTOM_TAB_BAR).toString();
        assertTrue(missing, missing.contains("a working '" + LightReelsTabBars.HOOK + "' hook (it threw "
                + NullPointerException.class.getName() + ")"));
    }

    private static String line() {
        for (String line : HookStatus.report()) {
            if (!line.startsWith(FamilyNames.BOTTOM_TAB_BAR + ":")) continue;
            if (line.contains(LightReelsTabBars.TAB_BAR) || line.contains(LightReelsTabBars.STATUS_BAR)
                    || line.contains(LightReelsTabBars.DARK_BARS)) {
                return line;
            }
        }
        return null;
    }
}
