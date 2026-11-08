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
 * A video tapped in Saved (#404): Facebook names the channel it opens in, and once Open saved
 * videos in the video player is on, Saved's channel stays in the player instead of opening Reels.
 * Every other channel, a missing one, a paused Hushfacebook, settings that aren't ready and a
 * switch that can't be read all leave Facebook to decide.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SavedVideosTest {
    private static final String SAVED = "vod_watchfeed_saved";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.SAVED_VIDEOS_IN_PLAYER.resetToDefault();
        HookStatus.clear();
    }

    @Test
    public void savedVideosOpenReelsUntilTheSwitchIsOn() {
        assertFalse("the switch starts on", Settings.SAVED_VIDEOS_IN_PLAYER.defaultValue);
        assertFalse("a saved video stayed in the player with the switch off", SavedVideos.staysInPlayer(SAVED));
        Settings.SAVED_VIDEOS_IN_PLAYER.save(true);
        assertTrue("a saved video still opens Reels with the switch on", SavedVideos.staysInPlayer(SAVED));
        Settings.SAVED_VIDEOS_IN_PLAYER.save(false);
        assertFalse("turning the switch off kept saved videos in the player", SavedVideos.staysInPlayer(SAVED));
    }

    /** The feed, Watch again and the other channels the same ask names are Facebook's to decide. */
    @Test
    public void otherChannelsAreLeftAlone() {
        Settings.SAVED_VIDEOS_IN_PLAYER.save(true);
        for (String channel : new String[]{"vod_newsfeed_unit_end_card_watch_again",
                "vod_newsfeed_unit_end_card_watch_more", "permalink_vod_multi_media_video",
                "group_vod_multi_media_video", "", null}) {
            assertFalse(channel + " stayed in the player", SavedVideos.staysInPlayer(channel));
        }
        assertNull("a channel left alone was counted", line());
    }

    @Test
    public void facebookDecidesWhilePausedOrBeforeTheSettingsAreReady() {
        Settings.SAVED_VIDEOS_IN_PLAYER.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("a saved video stayed in the player while paused", SavedVideos.staysInPlayer(SAVED));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
                assertFalse("a saved video stayed in the player before the settings were ready",
                        SavedVideos.staysInPlayer(SAVED)));
    }

    @Test
    public void eachKeptVideoIsCounted() {
        Settings.SAVED_VIDEOS_IN_PLAYER.save(true);
        SavedVideos.staysInPlayer(SAVED);
        SavedVideos.staysInPlayer(SAVED);
        String line = line();
        assertNotNull("no " + FamilyNames.REEL_DECLUTTER + " line: " + HookStatus.report(), line);
        assertTrue(line, line.contains(SavedVideos.KEPT + " 2"));
    }

    /** A switch that can't be read leaves Facebook to decide, and the report names the hook. */
    @Test
    public void aSwitchThatCantBeReadLeavesFacebookToDecide() {
        SettingReadsForTests.breakReads(Settings.SAVED_VIDEOS_IN_PLAYER);
        try {
            assertFalse(SavedVideos.staysInPlayer(SAVED));
        } finally {
            SettingReadsForTests.mend(Settings.SAVED_VIDEOS_IN_PLAYER);
        }
        String missing = HookStatus.missing(FamilyNames.REEL_DECLUTTER).toString();
        assertTrue(missing, missing.contains("a working '" + SavedVideos.HOOK + "' hook (it threw "
                + NullPointerException.class.getName() + ")"));
    }

    private static String line() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.REEL_DECLUTTER + ":") && line.contains(SavedVideos.KEPT)) return line;
        }
        return null;
    }
}
