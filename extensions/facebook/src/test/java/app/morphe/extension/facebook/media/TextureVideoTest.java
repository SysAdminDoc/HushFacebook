/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.navigation.TranslucentBarsForTests;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Fix washed-out video colors (#308): with the switch on, the player's must-use-a-SurfaceView
 * decision answers no and its TextureView preference answers yes, and a player kept off its
 * SurfaceView is counted. Off, paused or before the settings are ready both answers are
 * Facebook's own.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class TextureVideoTest {
    private static final HushfacebookPause.Reason[] PAUSES = {
            HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
            HushfacebookPause.Reason.MARKER_FILE};

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
        Settings.DRAW_VIDEOS_WITH_THE_SCREEN.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.DRAW_VIDEOS_WITH_THE_SCREEN.resetToDefault();
        Settings.TRANSLUCENT_BARS.resetToDefault();
        TranslucentBarsForTests.inBuild(null);
        HookStatus.clear();
    }

    /** A player origin as 585's PlayerOrigin prints itself: "A00::A01", or A00 alone when A01 is empty. */
    private static Object origin(String surface, String name) {
        return new Object() {
            @Override
            public String toString() {
                return name.isEmpty() ? surface : surface + "::" + name;
            }
        };
    }

    private static final Object FEED = origin("newsfeed", "");
    private static final Object REELS = origin("reels_tab", TextureVideo.REELS_VIEWER);
    private static final Object REELS_PIP = origin("reels_tab", TextureVideo.REELS_VIEWER + "_pip");

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.HDR_BRIGHTNESS + ":")) return line;
        }
        return "";
    }

    @Test
    public void onThePlayerDrawsIntoItsTextureView() {
        assertFalse("a reel Facebook kept on a SurfaceView stayed there", TextureVideo.surfaceView(true, REELS));
        assertFalse("a player Facebook kept off a SurfaceView went onto one", TextureVideo.surfaceView(false, FEED));
        assertTrue("the TextureView preference stayed off", TextureVideo.preferTextureView(false));
        assertTrue("the TextureView preference was turned off", TextureVideo.preferTextureView(true));
        String line = statusLine();
        assertTrue("the player kept off its SurfaceView wasn't counted once: " + line,
                line.contains(TextureVideo.DRAWN_WITH_THE_SCREEN + " 1"));
    }

    @Test
    public void offPausedOrNotReadyFacebookDecides() {
        Settings.DRAW_VIDEOS_WITH_THE_SCREEN.save(false);
        assertTrue("off, a SurfaceView player lost it", TextureVideo.surfaceView(true, FEED));
        assertFalse("off, the TextureView preference was turned on", TextureVideo.preferTextureView(false));
        Settings.DRAW_VIDEOS_WITH_THE_SCREEN.save(true);
        for (HushfacebookPause.Reason reason : PAUSES) {
            PauseForTests.pause(reason);
            assertTrue(reason.name() + ": a SurfaceView player lost it", TextureVideo.surfaceView(true, FEED));
            assertFalse(reason.name() + ": the TextureView preference was turned on", TextureVideo.preferTextureView(false));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> {
            assertTrue("a SurfaceView player lost it before the settings were ready", TextureVideo.surfaceView(true, FEED));
            assertFalse("the preference was turned on before the settings were ready", TextureVideo.preferTextureView(false));
        });
        String line = statusLine();
        assertFalse("an answer left to Facebook was counted: " + line, line.contains("Counted"));
    }

    /**
     * See-through bars (#250): a feed video on its own SurfaceView can't be frosted, so while the
     * bars are see-through a feed player is kept off its SurfaceView, even where Facebook's decision
     * says the feed origin must use one, and its TextureView preference is yes. A reel, from the
     * reels viewer, keeps its SurfaceView.
     */
    @Test
    @Config(sdk = 34)
    public void seeThroughBarsPutAFeedVideoOnItsTextureView() {
        Settings.DRAW_VIDEOS_WITH_THE_SCREEN.save(false);
        Settings.TRANSLUCENT_BARS.save(true);
        TranslucentBarsForTests.inBuild(true);
        assertFalse("a feed video Facebook put on a SurfaceView stayed there under see-through bars",
                TextureVideo.surfaceView(true, FEED));
        assertTrue("a feed video stayed on its SurfaceView under see-through bars", TextureVideo.preferTextureView(false));
        assertTrue("a feed video Facebook put on a TextureView left it", TextureVideo.preferTextureView(true));
        assertTrue("a reel lost its SurfaceView", TextureVideo.surfaceView(true, REELS));
        assertTrue("a reel in picture-in-picture lost its SurfaceView", TextureVideo.surfaceView(true, REELS_PIP));
        assertFalse("a feed video was put on a SurfaceView", TextureVideo.surfaceView(false, FEED));
        String line = statusLine();
        assertTrue("the feed video moved for the bars wasn't counted twice: " + line,
                line.contains(TextureVideo.DRAWN_UNDER_THE_BARS + " 2"));
        assertFalse("the bars counted as the washed-out colors switch: " + line,
                line.contains(TextureVideo.DRAWN_WITH_THE_SCREEN));
    }

    @Test
    @Config(sdk = 34)
    public void barsOffPausedOrOutOfTheBuildLeaveTheFeedVideo() {
        Settings.DRAW_VIDEOS_WITH_THE_SCREEN.save(false);
        Settings.TRANSLUCENT_BARS.save(true);
        TranslucentBarsForTests.inBuild(false);
        assertFalse("without the tab bar patch a feed video was moved", TextureVideo.preferTextureView(false));
        assertTrue("without the tab bar patch a feed video lost its SurfaceView", TextureVideo.surfaceView(true, FEED));
        TranslucentBarsForTests.inBuild(true);
        Settings.TRANSLUCENT_BARS.save(false);
        assertFalse("with the bars plain a feed video was moved", TextureVideo.preferTextureView(false));
        assertTrue("with the bars plain a feed video lost its SurfaceView", TextureVideo.surfaceView(true, FEED));
        Settings.TRANSLUCENT_BARS.save(true);
        for (HushfacebookPause.Reason reason : PAUSES) {
            PauseForTests.pause(reason);
            assertFalse(reason.name() + ": a feed video was moved", TextureVideo.preferTextureView(false));
            assertTrue(reason.name() + ": a feed video lost its SurfaceView", TextureVideo.surfaceView(true, FEED));
            PauseForTests.resume();
        }
        assertFalse("an answer left to Facebook was counted: " + statusLine(), statusLine().contains("Counted"));
    }

    /** Android 11 has no frost, so the bars leave the player's view alone there. */
    @Test
    public void noFrostBeforeAndroid12LeavesTheFeedVideo() {
        Settings.DRAW_VIDEOS_WITH_THE_SCREEN.save(false);
        Settings.TRANSLUCENT_BARS.save(true);
        TranslucentBarsForTests.inBuild(true);
        assertFalse("a feed video was moved on Android 11", TextureVideo.preferTextureView(false));
        assertTrue("a feed video lost its SurfaceView on Android 11", TextureVideo.surfaceView(true, FEED));
    }
}
