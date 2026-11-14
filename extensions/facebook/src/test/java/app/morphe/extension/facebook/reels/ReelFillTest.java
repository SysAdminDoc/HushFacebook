/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.res.Resources;
import android.util.DisplayMetrics;

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
 * Fill the screen with reels (#453): right before Facebook sizes a player view, the hook is asked
 * whether to fill the container instead of fitting the video inside it. Once the switch is on, a
 * portrait video in a portrait container about the screen's size fills when that cuts off a quarter
 * of the picture or less, and so does a full-screen landscape video (#466). Feed-sized players,
 * landscape videos in a portrait player, square and 4:5 posts, Facebook's own
 * fill or keep-size modes, a paused Hushfacebook, settings that aren't ready and a switch that can't
 * be read all keep Facebook's fit.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReelFillTest {
    /** A 19.5:9 phone, the reporter's shape. */
    private static final int SCREEN_WIDTH = 1440;
    private static final int SCREEN_HEIGHT = 3120;

    /** A 9:16 reel. */
    private static final double REEL = 9.0 / 16.0;

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.FILL_REELS.resetToDefault();
        HookStatus.clear();
    }

    @Test
    public void facebookFitsUntilTheSwitchIsOn() {
        assertFalse("the switch starts on", Settings.FILL_REELS.defaultValue);
        assertFalse("a reel filled with the switch off", ReelFillForTests.fillsAFullScreenReel());
        Settings.FILL_REELS.save(true);
        assertTrue("a full-screen reel still fit with the switch on", ReelFillForTests.fillsAFullScreenReel());
        Settings.FILL_REELS.save(false);
        assertFalse("turning the switch off kept filling", ReelFillForTests.fillsAFullScreenReel());
    }

    /** A 9:16 reel on the reporter's 19.5:9 screen fills, whether the bar sits at the top or the bottom. */
    @Test
    public void aNineBySixteenReelFillsATallScreen() {
        assertTrue("a reel in the whole screen didn't fill",
                ReelFill.worthFilling(REEL, SCREEN_WIDTH, SCREEN_HEIGHT, SCREEN_WIDTH, SCREEN_HEIGHT));
        assertTrue("a reel above a tab bar didn't fill",
                ReelFill.worthFilling(REEL, SCREEN_WIDTH, SCREEN_HEIGHT - 200, SCREEN_WIDTH, SCREEN_HEIGHT));
        // Taller than the container: filling cuts a little off the top and bottom instead of the sides.
        assertTrue("a reel narrower than its container didn't fill",
                ReelFill.worthFilling(0.42, SCREEN_WIDTH, SCREEN_HEIGHT, SCREEN_WIDTH, SCREEN_HEIGHT));
    }

    /**
     * A landscape video in a landscape player about the size of the turned screen fills too (#466),
     * whichever way up the screen reports itself. A 4:3 one would lose too much, and a feed-sized
     * landscape player keeps the whole picture.
     */
    @Test
    public void aFullScreenLandscapeVideoFills() {
        double wide = 16.0 / 9.0;
        assertTrue("a full-screen landscape video didn't fill",
                ReelFill.worthFilling(wide, SCREEN_HEIGHT, SCREEN_WIDTH, SCREEN_HEIGHT, SCREEN_WIDTH));
        assertTrue("a full-screen landscape video didn't fill while the screen reported itself upright",
                ReelFill.worthFilling(wide, SCREEN_HEIGHT, SCREEN_WIDTH, SCREEN_WIDTH, SCREEN_HEIGHT));
        assertTrue("a landscape video beside the navigation bar didn't fill",
                ReelFill.worthFilling(wide, SCREEN_HEIGHT - 150, SCREEN_WIDTH, SCREEN_HEIGHT, SCREEN_WIDTH));
        assertFalse("a 4:3 video filled the turned screen",
                ReelFill.worthFilling(4.0 / 3.0, SCREEN_HEIGHT, SCREEN_WIDTH, SCREEN_HEIGHT, SCREEN_WIDTH));
        assertFalse("a feed-sized landscape player filled",
                ReelFill.worthFilling(wide, SCREEN_WIDTH, SCREEN_WIDTH * 9 / 16, SCREEN_WIDTH, SCREEN_HEIGHT));
        assertFalse("a portrait reel filled a landscape player",
                ReelFill.worthFilling(REEL, SCREEN_HEIGHT, SCREEN_WIDTH, SCREEN_HEIGHT, SCREEN_WIDTH));
    }

    /** What isn't a full-screen portrait reel keeps its whole picture. */
    @Test
    public void everythingElseKeepsItsWholePicture() {
        assertFalse("a 4:5 post filled the screen",
                ReelFill.worthFilling(0.8, SCREEN_WIDTH, SCREEN_HEIGHT, SCREEN_WIDTH, SCREEN_HEIGHT));
        assertFalse("a square video filled the screen",
                ReelFill.worthFilling(1.0, SCREEN_WIDTH, SCREEN_HEIGHT, SCREEN_WIDTH, SCREEN_HEIGHT));
        assertFalse("a landscape video filled the screen",
                ReelFill.worthFilling(16.0 / 9.0, SCREEN_WIDTH, SCREEN_HEIGHT, SCREEN_WIDTH, SCREEN_HEIGHT));
        assertFalse("a feed-sized player filled",
                ReelFill.worthFilling(REEL, SCREEN_WIDTH, SCREEN_HEIGHT / 2, SCREEN_WIDTH, SCREEN_HEIGHT));
        assertFalse("a narrow player filled",
                ReelFill.worthFilling(REEL, SCREEN_WIDTH / 2, SCREEN_HEIGHT, SCREEN_WIDTH, SCREEN_HEIGHT));
        assertFalse("a landscape container filled",
                ReelFill.worthFilling(REEL, SCREEN_HEIGHT, SCREEN_WIDTH, SCREEN_HEIGHT, SCREEN_WIDTH));
        assertFalse("a reel that already fits filled",
                ReelFill.worthFilling((double) SCREEN_WIDTH / SCREEN_HEIGHT, SCREEN_WIDTH, SCREEN_HEIGHT, SCREEN_WIDTH, SCREEN_HEIGHT));
        assertFalse("an unknown aspect filled", ReelFill.worthFilling(Double.NaN, SCREEN_WIDTH, SCREEN_HEIGHT, SCREEN_WIDTH, SCREEN_HEIGHT));
        assertFalse("an empty container filled", ReelFill.worthFilling(REEL, 0, 0, SCREEN_WIDTH, SCREEN_HEIGHT));
    }

    /** Only Facebook's fit mode turns into fill: its own fill and keep-size modes are left alone. */
    @Test
    public void onlyTheFitModeIsChanged() {
        Settings.FILL_REELS.save(true);
        DisplayMetrics screen = Resources.getSystem().getDisplayMetrics();
        double aspect = 1.1 * screen.widthPixels / screen.heightPixels;
        assertTrue(ReelFill.fills(aspect, screen.widthPixels, screen.heightPixels, ReelFill.FIT, 0));
        assertFalse("fill mode was answered", ReelFill.fills(aspect, screen.widthPixels, screen.heightPixels, ReelFill.FILL, 0));
        assertFalse("keep-size mode was answered", ReelFill.fills(aspect, screen.widthPixels, screen.heightPixels, 3, 0));
        assertNull("a mode left alone was counted", lineCounting(2));
    }

    @Test
    public void facebookFitsWhilePausedOrBeforeTheSettingsAreReady() {
        Settings.FILL_REELS.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("a reel filled while paused", ReelFillForTests.fillsAFullScreenReel());
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
                assertFalse("a reel filled before the settings were ready", ReelFillForTests.fillsAFullScreenReel()));
    }

    @Test
    public void eachFilledReelIsCounted() {
        Settings.FILL_REELS.save(true);
        ReelFillForTests.fillsAFullScreenReel();
        ReelFillForTests.fillsAFullScreenReel();
        String line = lineCounting(2);
        assertNotNull("no " + FamilyNames.REEL_DECLUTTER + " line: " + HookStatus.report(), line);
    }

    /** A switch that can't be read keeps Facebook's fit, and the report names the hook. */
    @Test
    public void aSwitchThatCantBeReadKeepsFacebooksFit() {
        SettingReadsForTests.breakReads(Settings.FILL_REELS);
        try {
            assertFalse(ReelFillForTests.fillsAFullScreenReel());
        } finally {
            SettingReadsForTests.mend(Settings.FILL_REELS);
        }
        String missing = HookStatus.missing(FamilyNames.REEL_DECLUTTER).toString();
        assertTrue(missing, missing.contains("a working '" + ReelFill.HOOK + "' hook (it threw "
                + NullPointerException.class.getName() + ")"));
    }

    private static String lineCounting(int count) {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.REEL_DECLUTTER + ":") && line.contains(ReelFill.FILLED + " " + count)) return line;
        }
        return null;
    }
}
