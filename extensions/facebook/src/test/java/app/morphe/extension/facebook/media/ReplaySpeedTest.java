/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
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

/** Speed on finished live videos (#238): what the hook in the gear menu's builder answers. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReplaySpeedTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void restore() {
        PauseForTests.resume();
        Settings.SPEED_ON_REPLAYS.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        LogBufferManager.clearLogBuffer();
        HookStatus.clear();
    }

    @Test public void onByDefault() {
        assertTrue(Settings.SPEED_ON_REPLAYS.defaultValue);
        assertTrue(Settings.SPEED_ON_REPLAYS.get());
    }

    /** A video that isn't an ad gets the row, and the report counts it under Keep the reel speed. */
    @Test public void aVideoThatIsntAnAdGetsTheRow() {
        assertTrue(ReplaySpeed.offerSpeed(0));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FamilyNames.KEEP_REEL_SPEED + ": invoked 1"));
        assertTrue(report, report.contains(ReplaySpeed.OFFERED + " 1"));
    }

    /** An ad keeps Facebook's menu, whatever nonzero value its isSponsored arrives as. */
    @Test public void anAdKeepsFacebooksMenu() {
        assertFalse(ReplaySpeed.offerSpeed(1));
        assertFalse(ReplaySpeed.offerSpeed(-1));
        String report = String.join("\n", HookStatus.report());
        assertFalse(report, report.contains(ReplaySpeed.OFFERED));
    }

    @Test public void switchedOffFacebooksMenuStands() {
        Settings.SPEED_ON_REPLAYS.save(false);
        assertFalse(ReplaySpeed.offerSpeed(0));
    }

    @Test public void pausedFacebooksMenuStands() {
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(ReplaySpeed.offerSpeed(0));
        PauseForTests.resume();
        assertTrue("the row once running again", ReplaySpeed.offerSpeed(0));
    }

    /** With Debug logging on, each answer says what it did, with nothing that names a video or a person. */
    @Test public void eachAnswerIsLoggedWithDebugOn() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        ReplaySpeed.offerSpeed(0);
        ReplaySpeed.offerSpeed(1);
        String log = LogBufferManager.buildExportText();
        assertTrue(log, log.contains("Replay speed: the gear menu of a full-screen video that isn't live gets Playback speed"));
        assertTrue(log, log.contains("Replay speed: an ad's gear menu keeps Facebook's rows"));
    }
}
