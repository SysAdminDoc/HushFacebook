/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Picture-in-picture's hold: the reel on screen pausing turns auto-enter off, and a reel playing
 * turns it back on. The reel on screen is the player ReelsPipUtil armed the window for until
 * another starts, as a swipe does. A reel swiped away, a disarm, an open window, the switch off or
 * a pause leave Facebook's auto-enter alone, and Facebook's own arming ends a hold. And the viewer's
 * new view id: one a view on the screen holds is swapped for a free one.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PictureInPictureHoldTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Records the auto-enter of each PictureInPictureParams handed to it. */
    public static final class Screen extends Activity {
        final List<Boolean> autoEnter = new ArrayList<>();

        @Override
        public void setPictureInPictureParams(PictureInPictureParams params) {
            autoEnter.add(params.isAutoEnterEnabled());
        }
    }

    private final Object player = new Object();
    private final Object other = new Object();
    private Screen screen;

    @Before
    public void start() {
        HookStatus.clear();
        PictureInPicture.forget();
        screen = Robolectric.buildActivity(Screen.class).setup().get();
        // The patch is in Morphe Manager's default selection with its switch off; these tests run with it on.
        Settings.PICTURE_IN_PICTURE.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.PICTURE_IN_PICTURE.resetToDefault();
        PictureInPicture.forget();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.PICTURE_IN_PICTURE + ":")) return line;
        }
        return null;
    }

    @Test
    public void theArmedPlayerPausingHoldsAutoEnterUntilItPlays() {
        PictureInPicture.armed(screen, player, null);
        PictureInPicture.playerPaused(player);
        PictureInPicture.playerPaused(player);
        PictureInPicture.playerPlaying(player);
        PictureInPicture.playerPlaying(player);
        PictureInPicture.playerPaused(player);
        assertEquals("pause, play, pause", Arrays.asList(false, true, false), screen.autoEnter);
        assertTrue(statusLine(), statusLine().endsWith("Counted: " + PictureInPicture.HELD + " 2"));
    }

    @Test
    public void aSwipeMovesTheHoldToTheNextReel() {
        // Facebook arms once as the viewer opens. A swipe pauses this reel and starts the next.
        PictureInPicture.armed(screen, player, null);
        PictureInPicture.playerPaused(player);
        PictureInPicture.playerPlaying(other);
        PictureInPicture.playerPaused(other);
        PictureInPicture.playerPaused(player);
        PictureInPicture.playerPlaying(other);
        assertEquals("pause, next plays, it pauses, it plays", Arrays.asList(false, true, false, true), screen.autoEnter);
    }

    @Test
    public void theNextReelStartingFirstLeavesTheLastOnesPauseAlone() {
        PictureInPicture.armed(screen, player, null);
        PictureInPicture.playerPlaying(other);
        PictureInPicture.playerPaused(player);
        assertEquals("the reel swiped away held auto-enter", Collections.emptyList(), screen.autoEnter);
        PictureInPicture.playerPaused(other);
        assertEquals("the reel on screen pausing", Collections.singletonList(false), screen.autoEnter);
    }

    @Test
    public void aDisarmLeavesFacebooksAutoEnter() {
        PictureInPicture.armed(screen, player, null);
        PictureInPicture.disarmed();
        PictureInPicture.playerPaused(player);
        PictureInPicture.playerPlaying(other);
        PictureInPicture.playerPaused(other);
        assertEquals("a player after a disarm set auto-enter", Collections.emptyList(), screen.autoEnter);
    }

    @Test
    public void facebooksOwnArmingEndsAHold() {
        PictureInPicture.armed(screen, player, null);
        PictureInPicture.playerPaused(player);
        PictureInPicture.armed(screen, player, null);
        PictureInPicture.playerPlaying(player);
        assertEquals("a play after Facebook's arming set auto-enter again", Collections.singletonList(false), screen.autoEnter);
    }

    @Test
    public void aHeldStartKeepsTheReelOutOfTheWindowUntilATapPlaysIt() {
        // #90: Facebook arms the window for the reel coming into view, then Tap to play holds its start.
        PictureInPicture.armed(screen, player, null);
        PictureInPicture.startHeld(player);
        assertEquals("the held reel left auto-enter on", Collections.singletonList(false), screen.autoEnter);
        PictureInPicture.playerPlaying(player);
        assertEquals("a tap's play turned it back on", Arrays.asList(false, true), screen.autoEnter);
        assertTrue(statusLine(), statusLine().endsWith("Counted: " + PictureInPicture.START_HELD + " 1"));
    }

    /** The triggers Facebook's player start names, as Tap to play reads them. */
    private enum Trigger { BY_AUTOPLAY, BY_USER }

    @Test
    public void tapToPlayHoldingAStartHoldsTheWindow() {
        Settings.TAP_TO_PLAY.save(true);
        TapToPlay.forget();
        TapClock.forget();
        try {
            PictureInPicture.armed(screen, player, null);
            assertFalse("the start went ahead", TapToPlay.allowStart(player, Trigger.BY_AUTOPLAY));
            assertEquals("Tap to play's hold left auto-enter on", Collections.singletonList(false), screen.autoEnter);
        } finally {
            Settings.TAP_TO_PLAY.resetToDefault();
        }
    }

    @Test
    public void aHeldStartOfTheNextReelHoldsTooAndItsPlayArmsItsOwnWindow() {
        // The window still armed for the last reel, a swipe's next reel is held at its start.
        PictureInPicture.armed(screen, player, null);
        PictureInPicture.startHeld(other);
        PictureInPicture.playerPlaying(other);
        assertEquals("held, then played", Arrays.asList(false, true), screen.autoEnter);
    }

    @Test
    public void aHeldStartWithNothingArmedAnOpenWindowOrTheSwitchOffHoldsNothing() {
        PictureInPicture.startHeld(player);
        PictureInPicture.armed(screen, player, null);
        Settings.PICTURE_IN_PICTURE.save(false);
        PictureInPicture.startHeld(player);
        Settings.PICTURE_IN_PICTURE.save(true);
        screen.enterPictureInPictureMode();
        PictureInPicture.startHeld(player);
        assertEquals("a held start set auto-enter", Collections.emptyList(), screen.autoEnter);
    }

    @Test
    public void anOpenWindowTheSwitchOffOrAPauseHoldNothing() {
        PictureInPicture.armed(screen, player, null);
        Settings.PICTURE_IN_PICTURE.save(false);
        PictureInPicture.playerPaused(player);
        Settings.PICTURE_IN_PICTURE.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        PictureInPicture.playerPaused(player);
        PauseForTests.resume();
        screen.enterPictureInPictureMode();
        PictureInPicture.playerPaused(player);
        assertEquals("a pause held auto-enter", Collections.emptyList(), screen.autoEnter);
    }

    @Test
    public void aHoldIsReleasedEvenWithTheSwitchTurnedOff() {
        PictureInPicture.armed(screen, player, null);
        PictureInPicture.playerPaused(player);
        Settings.PICTURE_IN_PICTURE.save(false);
        PictureInPicture.playerPlaying(player);
        assertEquals("the hold stayed", Arrays.asList(false, true), screen.autoEnter);
    }

    /** Puts a view holding [id] on the screen, the way Marketplace's React Native root holds 1. */
    private void holding(int id) {
        FrameLayout root = new FrameLayout(screen);
        View taken = new View(screen);
        taken.setId(id);
        root.addView(taken);
        screen.setContentView(root);
    }

    @Test
    public void aViewerIdAViewHoldsIsSwappedForAFreeOne() {
        holding(1);
        int id = PictureInPicture.viewerId(screen, 1);
        assertNotEquals("the viewer kept the taken id", 1, id);
        assertNull("the viewer got another taken id", screen.findViewById(id));
        assertEquals("a free id changed", 4242, PictureInPicture.viewerId(screen, 4242));
        assertTrue(statusLine(), statusLine().endsWith("Counted: " + PictureInPicture.VIEWER_ID + " 1"));
    }

    @Test
    public void offOrWithNoScreenTheViewerIdStaysFacebooks() {
        holding(1);
        Settings.PICTURE_IN_PICTURE.save(false);
        assertEquals("the switch off swapped the id", 1, PictureInPicture.viewerId(screen, 1));
        Settings.PICTURE_IN_PICTURE.save(true);
        assertEquals("no screen swapped the id", 1, PictureInPicture.viewerId(null, 1));
    }
}
