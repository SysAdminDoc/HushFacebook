/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
 * Show feed reels uncropped (#581): right before Facebook's feed reel frame helper returns, the hook
 * gets the frame's shape and the video's own. Once the switch is on, a 9:16 reel Facebook framed at
 * 4:5 gets a 9:16 frame, a reel taller than the screen's room gets the room's shape, and an unknown
 * shape keeps Facebook's. A switch that's off, a paused Hushfacebook, settings that aren't ready and a
 * switch that can't be read all keep Facebook's frame.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w411dp-h914dp-port-xxhdpi")
public class FeedReelShapeTest {
    /** A 9:16 reel. */
    private static final float REEL = 9f / 16f;

    /** The 4:5 frame the reporter's feed cut reels down to. */
    private static final float FOUR_BY_FIVE = 0.8f;

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.UNCROPPED_FEED_REELS.resetToDefault();
        HookStatus.clear();
    }

    @Test
    public void facebooksFrameUntilTheSwitchIsOn() {
        assertFalse("the switch starts on", Settings.UNCROPPED_FEED_REELS.defaultValue);
        assertFalse("asked as each frame is shaped, so no restart", Settings.UNCROPPED_FEED_REELS.rebootApp);
        assertEquals("a reel was reshaped with the switch off", FOUR_BY_FIVE, FeedReelShape.shape(FOUR_BY_FIVE, REEL), 0f);
        Settings.UNCROPPED_FEED_REELS.save(true);
        assertEquals("a 4:5 frame kept cutting a 9:16 reel with the switch on", REEL, FeedReelShape.shape(FOUR_BY_FIVE, REEL), 0f);
        Settings.UNCROPPED_FEED_REELS.save(false);
        assertEquals("turning the switch off kept reshaping", FOUR_BY_FIVE, FeedReelShape.shape(FOUR_BY_FIVE, REEL), 0f);
    }

    /** The frame takes the video's shape, but never taller than the screen's room for the feed. */
    @Test
    public void theFrameTakesTheVideosShapeWithinTheScreensRoom() {
        float width = 1080f;
        float room = 2100f;
        assertEquals("a 9:16 reel", REEL, FeedReelShape.whole(FOUR_BY_FIVE, REEL, width, room), 0f);
        assertEquals("a reel taller than the room", width / room, FeedReelShape.whole(FOUR_BY_FIVE, 0.4f, width, room), 1e-6f);
        assertEquals("a landscape video Facebook framed taller", 16f / 9f,
                FeedReelShape.whole(1f, 16f / 9f, width, room), 0f);
        assertEquals("a video already in its own frame", REEL, FeedReelShape.whole(REEL, REEL, width, room), 0f);
        assertEquals("an unknown video shape", FOUR_BY_FIVE, FeedReelShape.whole(FOUR_BY_FIVE, 0f, width, room), 0f);
        assertEquals("an endless video shape", FOUR_BY_FIVE, FeedReelShape.whole(FOUR_BY_FIVE, Float.NaN, width, room), 0f);
        assertEquals("an unknown frame", 0f, FeedReelShape.whole(0f, REEL, width, room), 0f);
        assertEquals("no room", FOUR_BY_FIVE, FeedReelShape.whole(FOUR_BY_FIVE, REEL, width, 0f), 0f);
    }

    /** On a 411 by 914 dp phone, the room under the status bar and the top bar fits a 9:16 reel whole. */
    @Test
    public void aPhoneScreenHasRoomForANineBySixteenReel() {
        Settings.UNCROPPED_FEED_REELS.save(true);
        assertEquals(REEL, FeedReelShape.shape(FOUR_BY_FIVE, REEL), 0f);
        float tallest = FeedReelShape.shape(FOUR_BY_FIVE, 0.1f);
        assertTrue("a very tall video's frame went past the screen: " + tallest, tallest > 0.45f && tallest < REEL);
    }

    @Test
    public void facebooksFrameWhilePausedOrBeforeTheSettingsAreReady() {
        Settings.UNCROPPED_FEED_REELS.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals("a reel was reshaped while paused", FOUR_BY_FIVE, FeedReelShape.shape(FOUR_BY_FIVE, REEL), 0f);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertEquals("a reel was reshaped before the settings were ready",
                FOUR_BY_FIVE, FeedReelShape.shape(FOUR_BY_FIVE, REEL), 0f));
    }

    @Test
    public void eachReshapedFrameIsCounted() {
        Settings.UNCROPPED_FEED_REELS.save(true);
        FeedReelShape.shape(FOUR_BY_FIVE, REEL);
        FeedReelShape.shape(FOUR_BY_FIVE, REEL);
        FeedReelShape.shape(REEL, REEL);
        String counted = null;
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.REEL_DECLUTTER + ":") && line.contains(FeedReelShape.SHOWN_WHOLE + " 2")) counted = line;
        }
        assertNotNull("no " + FamilyNames.REEL_DECLUTTER + " line counting 2: " + HookStatus.report(), counted);
    }

    /** A switch that can't be read keeps Facebook's frame, and the report names the hook. */
    @Test
    public void aSwitchThatCantBeReadKeepsFacebooksFrame() {
        SettingReadsForTests.breakReads(Settings.UNCROPPED_FEED_REELS);
        try {
            assertEquals(FOUR_BY_FIVE, FeedReelShape.shape(FOUR_BY_FIVE, REEL), 0f);
        } finally {
            SettingReadsForTests.mend(Settings.UNCROPPED_FEED_REELS);
        }
        String missing = HookStatus.missing(FamilyNames.REEL_DECLUTTER).toString();
        assertTrue(missing, missing.contains("a working '" + FeedReelShape.HOOK + "' hook (it threw "
                + NullPointerException.class.getName() + ")"));
    }
}
