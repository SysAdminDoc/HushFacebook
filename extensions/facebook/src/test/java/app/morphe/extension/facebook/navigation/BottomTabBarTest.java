/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.facebook.common.util.TriState;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Tab bar at the bottom: with the switch on, every read of Facebook's own override answers YES,
 * whatever the account had, and is counted. With Tab bar at the top on instead it answers NO
 * (#166); the bottom switch wins and greys the top one out. Off or paused, each read keeps
 * Facebook's answer.
 * Phone layout on big screens answers Facebook's big screen gate no the same way (#149).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class BottomTabBarTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
        BottomTabBar.forgetLogged();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.BOTTOM_TAB_BAR.resetToDefault();
        Settings.TOP_TAB_BAR.resetToDefault();
        Settings.PHONE_LAYOUT_ON_BIG_SCREENS.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        LogBufferManager.clearLogBuffer();
        HookStatus.clear();
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) count++;
        return count;
    }

    @Test
    public void theOverrideIsLoggedOnceUntilItsAnswerChanges() {
        // Facebook reads the override on every layout of the main screen. A debug line on each read put
        // 1,213 copies of it in one S22 log export, and with the Reels padding line below it was nine
        // tenths of the export, crowding out the lines a report needs.
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        Settings.BOTTOM_TAB_BAR.save(true);
        for (int read = 0; read < 5; read++) BottomTabBar.override(TriState.NO.ordinal());
        Settings.BOTTOM_TAB_BAR.save(false);
        Settings.TOP_TAB_BAR.save(true);
        for (int read = 0; read < 5; read++) BottomTabBar.override(TriState.YES.ordinal());
        String log = LogBufferManager.buildExportText();
        assertEquals(log, 1, occurrences(log, "the override reads YES"));
        assertEquals(log, 1, occurrences(log, "the override reads NO"));
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.BOTTOM_TAB_BAR + ":")) return line;
        }
        return null;
    }

    @Test
    public void onEveryAnswerIsYes() {
        Settings.BOTTOM_TAB_BAR.save(true);
        for (TriState facebooks : TriState.values()) {
            assertEquals("the override read " + facebooks, TriState.YES.ordinal(), BottomTabBar.override(facebooks.ordinal()));
        }
        assertEquals(FamilyNames.BOTTOM_TAB_BAR + ": invoked 3, 1 found, 0 missing. Counted: "
                + BottomTabBar.PUT_AT_BOTTOM + " 3", statusLine());
    }

    @Test
    public void offOrPausedFacebooksAnswerStays() {
        assertFalse("the switch doesn't start off", Settings.BOTTOM_TAB_BAR.get());
        for (TriState facebooks : TriState.values()) {
            assertEquals("off, the override read " + facebooks, facebooks.ordinal(), BottomTabBar.override(facebooks.ordinal()));
        }
        Settings.BOTTOM_TAB_BAR.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertEquals("a Hushfacebook paused by " + reason + " moved the tab bar",
                    TriState.NO.ordinal(), BottomTabBar.override(TriState.NO.ordinal()));
            PauseForTests.resume();
        }
        String line = statusLine();
        assertFalse("a read that kept Facebook's answer was counted: " + line, line != null && line.contains("Counted"));
    }

    @Test
    public void topEveryAnswerIsNo() {
        Settings.TOP_TAB_BAR.save(true);
        for (TriState facebooks : TriState.values()) {
            assertEquals("the override read " + facebooks, TriState.NO.ordinal(), BottomTabBar.override(facebooks.ordinal()));
        }
        assertEquals(FamilyNames.BOTTOM_TAB_BAR + ": invoked 3, 1 found, 0 missing. Counted: "
                + BottomTabBar.PUT_AT_TOP + " 3", statusLine());
    }

    @Test
    public void theBottomSwitchWinsAndGreysTheTopOneOut() {
        assertFalse("the top switch doesn't start off", Settings.TOP_TAB_BAR.get());
        Settings.TOP_TAB_BAR.save(true);
        assertTrue("the top switch is greyed out with the bottom one off", Settings.TOP_TAB_BAR.isAvailable());
        Settings.BOTTOM_TAB_BAR.save(true);
        assertFalse("the top switch isn't greyed out under the bottom one", Settings.TOP_TAB_BAR.isAvailable());
        assertEquals(TriState.YES.ordinal(), BottomTabBar.override(TriState.NO.ordinal()));
    }

    @Test
    public void topPausedKeepsFacebooksAnswer() {
        Settings.TOP_TAB_BAR.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals("a paused Hushfacebook moved the tab bar",
                TriState.YES.ordinal(), BottomTabBar.override(TriState.YES.ordinal()));
        PauseForTests.resume();
        assertEquals(TriState.NO.ordinal(), BottomTabBar.override(TriState.YES.ordinal()));
    }

    @Test
    public void theSwitchIsOneThatAsksForARestart() {
        assertEquals(true, Settings.TOP_TAB_BAR.rebootApp);
        assertEquals(true, Settings.BOTTOM_TAB_BAR.rebootApp);
        assertNull("nothing asks before the switch changes", Settings.BOTTOM_TAB_BAR.userDialogMessage);
        assertEquals(true, Settings.PHONE_LAYOUT_ON_BIG_SCREENS.rebootApp);
        assertNull("nothing asks before the switch changes", Settings.PHONE_LAYOUT_ON_BIG_SCREENS.userDialogMessage);
    }

    @Test
    public void phoneLayoutOnBigScreensAnswersFacebooksGateNo() {
        Settings.PHONE_LAYOUT_ON_BIG_SCREENS.save(true);
        assertTrue("the big screen gate kept Facebook's answer", BottomTabBar.phoneLayout());
        assertTrue(BottomTabBar.phoneLayout());
        assertEquals(FamilyNames.BOTTOM_TAB_BAR + ": invoked 2, 1 found, 0 missing. Counted: "
                + BottomTabBar.PHONE_LAYOUT + " 2", statusLine());
    }

    @Test
    public void phoneLayoutOffOrPausedLeavesFacebooksGate() {
        assertFalse("the switch doesn't start off", Settings.PHONE_LAYOUT_ON_BIG_SCREENS.get());
        assertFalse("off, the big screen gate was answered", BottomTabBar.phoneLayout());
        Settings.PHONE_LAYOUT_ON_BIG_SCREENS.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertFalse("a Hushfacebook paused by " + reason + " answered the big screen gate", BottomTabBar.phoneLayout());
            PauseForTests.resume();
        }
        String line = statusLine();
        assertFalse("a gate that kept Facebook's answer was counted: " + line, line != null && line.contains("Counted"));
    }
}
