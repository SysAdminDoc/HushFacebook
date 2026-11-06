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
 * Watch again at the end of a feed video (#403): Facebook asks whether the video opens somewhere
 * else, and once Replay feed videos in place is on the answer is no, so it plays again where it is.
 * Facebook's own answer stands while the switch is off, paused, before the settings are ready and
 * when the switch can't be read, and a no from Facebook stays a no.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class WatchAgainTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.WATCH_AGAIN_IN_PLACE.resetToDefault();
        HookStatus.clear();
    }

    @Test
    public void watchAgainOpensReelsUntilTheSwitchIsOn() {
        assertFalse("the switch starts on", Settings.WATCH_AGAIN_IN_PLACE.defaultValue);
        assertTrue("Facebook's yes changed with the switch off", WatchAgain.opensElsewhere(true));
        Settings.WATCH_AGAIN_IN_PLACE.save(true);
        assertFalse("Watch again still opens Reels with the switch on", WatchAgain.opensElsewhere(true));
        Settings.WATCH_AGAIN_IN_PLACE.save(false);
        assertTrue("turning the switch off kept the replay", WatchAgain.opensElsewhere(true));
    }

    @Test
    public void facebooksNoStaysNo() {
        assertFalse(WatchAgain.opensElsewhere(false));
        Settings.WATCH_AGAIN_IN_PLACE.save(true);
        assertFalse("a video Facebook replays in place was sent to Reels", WatchAgain.opensElsewhere(false));
        assertNull("a no Facebook gave itself was counted", line());
    }

    @Test
    public void facebooksAnswerStandsWhilePausedOrBeforeTheSettingsAreReady() {
        Settings.WATCH_AGAIN_IN_PLACE.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertTrue("Watch again replayed while paused", WatchAgain.opensElsewhere(true));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
                assertTrue("Watch again replayed before the settings were ready", WatchAgain.opensElsewhere(true)));
    }

    @Test
    public void eachReplayIsCounted() {
        Settings.WATCH_AGAIN_IN_PLACE.save(true);
        WatchAgain.opensElsewhere(true);
        WatchAgain.opensElsewhere(true);
        String line = line();
        assertNotNull("no " + FamilyNames.REEL_DECLUTTER + " line: " + HookStatus.report(), line);
        assertTrue(line, line.contains(WatchAgain.REPLAYED + " 2"));
    }

    /** A switch that can't be read leaves Facebook's answer, and the report names the hook. */
    @Test
    public void aSwitchThatCantBeReadLeavesFacebooksAnswer() {
        SettingReadsForTests.breakReads(Settings.WATCH_AGAIN_IN_PLACE);
        try {
            assertTrue(WatchAgain.opensElsewhere(true));
        } finally {
            SettingReadsForTests.mend(Settings.WATCH_AGAIN_IN_PLACE);
        }
        String missing = HookStatus.missing(FamilyNames.REEL_DECLUTTER).toString();
        assertTrue(missing, missing.contains("a working '" + WatchAgain.HOOK + "' hook (it threw "
                + NullPointerException.class.getName() + ")"));
    }

    private static String line() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.REEL_DECLUTTER + ":") && line.contains(WatchAgain.REPLAYED)) return line;
        }
        return null;
    }
}
