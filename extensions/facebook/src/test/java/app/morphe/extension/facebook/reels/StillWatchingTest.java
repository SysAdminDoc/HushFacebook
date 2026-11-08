/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.SettingReadsForTests;

/**
 * "Are you still watching?" (#427): the auto-advance controller's idle check hands its answer over,
 * and once Skip "Are you still watching?" is on, an idle answer comes back not idle so the next video
 * isn't held back. A not-idle answer, a paused Hushfacebook, settings that aren't ready and a switch
 * that can't be read all keep Facebook's answer.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class StillWatchingTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.SKIP_STILL_WATCHING.resetToDefault();
        HookStatus.clear();
    }

    @Test
    public void facebookAsksUntilTheSwitchIsOn() {
        assertFalse("the switch starts on", Settings.SKIP_STILL_WATCHING.defaultValue);
        assertTrue("an idle answer was dropped with the switch off", StillWatching.idle(1));
        Settings.SKIP_STILL_WATCHING.save(true);
        assertFalse("an idle answer still held the next video back with the switch on", StillWatching.idle(1));
        Settings.SKIP_STILL_WATCHING.save(false);
        assertTrue("turning the switch off kept dropping idle answers", StillWatching.idle(1));
    }

    /** Not idle stays not idle, whatever the switch says, and isn't counted. */
    @Test
    public void notIdleIsLeftAlone() {
        assertFalse(StillWatching.idle(0));
        Settings.SKIP_STILL_WATCHING.save(true);
        assertFalse(StillWatching.idle(0));
        assertNull("a not-idle answer was counted", line());
    }

    @Test
    public void facebookAnswersWhilePausedOrBeforeTheSettingsAreReady() {
        Settings.SKIP_STILL_WATCHING.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertTrue("an idle answer was dropped while paused", StillWatching.idle(1));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
                assertTrue("an idle answer was dropped before the settings were ready", StillWatching.idle(1)));
    }

    @Test
    public void eachSkippedPromptIsCounted() {
        Settings.SKIP_STILL_WATCHING.save(true);
        StillWatching.idle(1);
        StillWatching.idle(1);
        String line = line();
        assertNotNull("no " + FamilyNames.REEL_DECLUTTER + " line: " + HookStatus.report(), line);
        assertTrue(line, line.contains(StillWatching.SKIPPED + " 2"));
    }

    /** A switch that can't be read keeps Facebook's answer, and the report names the hook. */
    @Test
    public void aSwitchThatCantBeReadKeepsFacebooksAnswer() {
        SettingReadsForTests.breakReads(Settings.SKIP_STILL_WATCHING);
        try {
            assertTrue(StillWatching.idle(1));
        } finally {
            SettingReadsForTests.mend(Settings.SKIP_STILL_WATCHING);
        }
        String missing = HookStatus.missing(FamilyNames.REEL_DECLUTTER).toString();
        assertTrue(missing, missing.contains("a working '" + StillWatching.HOOK + "' hook (it threw "
                + NullPointerException.class.getName() + ")"));
    }

    private static String line() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.REEL_DECLUTTER + ":") && line.contains(StillWatching.SKIPPED)) return line;
        }
        return null;
    }
}
