/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.util.Rational;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsDialog;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Picture-in-picture's hold: the reel on screen pausing turns auto-enter off, and a reel playing
 * turns it back on. The reel on screen is the player ReelsPipUtil armed the window for until
 * another starts, as a swipe does. A reel swiped away, a disarm, an open window, the switch off or
 * a pause leave Facebook's auto-enter alone, and Facebook's own arming ends a hold. The Hushfacebook
 * settings hold it too, from showing over the armed screen until they close. And the viewer's new
 * view id: one a view on the screen holds is swapped for a free one.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PictureInPictureHoldTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Records the auto-enter of each PictureInPictureParams handed to it. */
    public static final class Screen extends Activity {
        final List<Boolean> autoEnter = new ArrayList<>();

        /** Whether the screen floats in the window, which Robolectric can't take back once entered. */
        boolean inWindow;

        @Override
        public void setPictureInPictureParams(PictureInPictureParams params) {
            autoEnter.add(params.isAutoEnterEnabled());
        }

        @Override
        public boolean isInPictureInPictureMode() {
            return inWindow || super.isInPictureInPictureMode();
        }
    }

    /** The window opens, then is opened full screen again: the screen pauses into it and resumes. */
    private void floatAndComeBack() {
        PictureInPicture.Arrivals arrivals = new PictureInPicture.Arrivals();
        screen.inWindow = true;
        arrivals.onActivityPaused(screen);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PictureInPicture.FLOAT_CHECK_MS));
        screen.inWindow = false;
        arrivals.onActivityResumed(screen);
    }

    private void waitForFacebooksArming() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PictureInPicture.REARM_AFTER_WINDOW_MS));
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
        PictureInPicture.views = PictureInPicture::playerView;
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
    public void theSettingsKeepAutoEnterOffUntilTheyClose() {
        // Pressing Home with the settings up over a playing reel floated the settings page in the window.
        PictureInPicture.settingsShown(screen);
        assertEquals("nothing armed yet", Collections.emptyList(), screen.autoEnter);
        PictureInPicture.armed(screen, player, null);
        shadowOf(Looper.getMainLooper()).idle();
        PictureInPicture.playerPaused(player);
        PictureInPicture.playerPlaying(player);
        PictureInPicture.settingsClosed();
        assertEquals("Facebook's arming, the pause and the play leave it off, closing turns it on",
                Arrays.asList(false, false, false, true), screen.autoEnter);

        PictureInPicture.settingsShown(screen);
        PictureInPicture.playerPlaying(other);
        PictureInPicture.settingsClosed();
        assertEquals("shown over the armed screen, then the next reel, then closed",
                Arrays.asList(false, false, false, true, false, true), screen.autoEnter);
        assertTrue(statusLine(), statusLine().contains(PictureInPicture.UNDER_SETTINGS + " 2"));
    }

    @Test
    @SuppressWarnings("deprecation") // The settings page is a framework dialog fragment.
    public void theSettingsPageHoldsAutoEnterFromShowingToClosing() {
        PictureInPicture.armed(screen, player, null);
        SettingsDialog page = new SettingsDialog();
        page.show(screen.getFragmentManager(), "hushfacebook_settings");
        screen.getFragmentManager().executePendingTransactions();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("the page showing", Collections.singletonList(false), screen.autoEnter);
        page.dismiss();
        screen.getFragmentManager().executePendingTransactions();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("the page closed", Arrays.asList(false, true), screen.autoEnter);
    }

    @Test
    public void closingTheSettingsLeavesAPauseUnarmedScreenOrSwitchOffAlone() {
        PictureInPicture.armed(screen, player, null);
        PictureInPicture.settingsShown(screen);
        PictureInPicture.playerPaused(player);
        PictureInPicture.settingsClosed();
        assertEquals("the paused reel stays held", Arrays.asList(false, false), screen.autoEnter);
        PictureInPicture.playerPlaying(player);
        assertEquals("its play turns it back on", Arrays.asList(false, false, true), screen.autoEnter);

        PictureInPicture.disarmed();
        PictureInPicture.settingsShown(screen);
        PictureInPicture.settingsClosed();
        Settings.PICTURE_IN_PICTURE.save(false);
        PictureInPicture.armed(screen, player, null);
        PictureInPicture.settingsShown(screen);
        shadowOf(Looper.getMainLooper()).idle();
        PictureInPicture.settingsClosed();
        assertEquals("disarmed, or the switch off, the settings change nothing",
                Arrays.asList(false, false, true), screen.autoEnter);
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

    /**
     * #126: a swipe to a reel the window doesn't take, one wider than 2.39 to one, arms nothing. Arming again
     * says no for it (the stub says no until the patch fills it in), so auto-enter goes off and stays
     * off through its pause and play, and its More sheet gets no row. Facebook's next arming ends it.
     */
    @Test
    public void aReelTheWindowDoesntTakeStaysOutOfIt() {
        shadowOf(screen.getPackageManager()).setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, true);
        PictureInPicture.armed(screen, player, new Object[0]);
        assertNotNull("the armed reel's More sheet", PictureInPictureRow.forSheet(screen));
        PictureInPicture.playerPlaying(other);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("the wide reel left auto-enter on", Collections.singletonList(false), screen.autoEnter);
        assertNull("the wide reel's More sheet has a row", PictureInPictureRow.forSheet(screen));
        PictureInPicture.playerPaused(other);
        PictureInPicture.playerPlaying(other);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("its pause and play turned auto-enter back on", Arrays.asList(false, false), screen.autoEnter);
        assertTrue(statusLine(), statusLine().contains(PictureInPicture.KEPT_OUT + " 1"));

        PictureInPicture.armed(screen, other, new Object[0]);
        assertNotNull("after Facebook's arming, the More sheet", PictureInPictureRow.forSheet(screen));
        PictureInPicture.playerPaused(other);
        PictureInPicture.playerPlaying(other);
        assertEquals("after Facebook's arming, pause and play", Arrays.asList(false, false, false, true), screen.autoEnter);
    }

    /**
     * #126: Facebook readies the reel next to the one on screen by playing it for a moment while its
     * video is off screen. That play leaves the window with the reel on screen, whose pause still holds.
     */
    @Test
    public void aPlayerPlayingOffScreenLeavesTheWindowWithTheReelOnScreen() {
        View offScreen = new View(screen);
        PictureInPicture.views = played -> played == other ? offScreen : null;
        PictureInPicture.armed(screen, player, null);
        PictureInPicture.playerPlaying(other);
        PictureInPicture.playerPaused(other);
        assertEquals("the readied reel took the window", Collections.emptyList(), screen.autoEnter);
        PictureInPicture.playerPaused(player);
        PictureInPicture.playerPlaying(player);
        assertEquals("the reel on screen's pause and play", Arrays.asList(false, true), screen.autoEnter);
        assertTrue(statusLine(), statusLine().contains(PictureInPicture.OFF_SCREEN + " 1"));
    }

    /**
     * Once the reel viewer floats, Facebook opened from the launcher shows Home full screen beside it.
     * A Home video playing there neither releases the floating reel's hold nor arms the window for
     * itself; the armed reel's own play still does.
     */
    @Test
    public void aPlayerOnAnotherScreenLeavesTheWindowWithTheArmedScreensReel() {
        Activity home = Robolectric.buildActivity(Activity.class).setup().get();
        View homeVideo = new View(home);
        PictureInPicture.views = played -> played == other ? homeVideo : null;
        PictureInPicture.armed(screen, player, new Object[0]);
        PictureInPicture.playerPaused(player);
        PictureInPicture.playerPlaying(other);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Home's video released the hold or armed the window", Collections.singletonList(false), screen.autoEnter);
        PictureInPicture.playerPaused(other);
        PictureInPicture.playerPlaying(player);
        assertEquals("the armed reel's own play", Arrays.asList(false, true), screen.autoEnter);
        assertTrue(statusLine(), statusLine().contains(PictureInPicture.OTHER_SCREEN + " 1"));
        assertFalse(statusLine(), statusLine().contains(PictureInPicture.KEPT_OUT));
    }

    @Test
    public void withNoViewToReadEveryPlayerThatPlaysIsTheReelOnScreen() {
        PictureInPicture.views = played -> null;
        PictureInPicture.armed(screen, player, null);
        PictureInPicture.playerPlaying(other);
        PictureInPicture.playerPaused(player);
        PictureInPicture.playerPaused(other);
        assertEquals("the next reel's pause held", Collections.singletonList(false), screen.autoEnter);
    }

    /**
     * #126: opened full screen, the window is sometimes not armed again by Facebook, and its last
     * arming then opened the window on Facebook's whole screen with a purple block. It's armed again
     * for the reel on screen; the stub can't arm here, so the reel is kept out as one it doesn't take.
     */
    @Test
    public void aWindowFacebookDoesntArmAgainAfterItClosesIsArmedOrKeptClosed() {
        PictureInPicture.armed(screen, player, new Object[0]);
        floatAndComeBack();
        assertEquals("before Facebook's moment to arm", Collections.emptyList(), screen.autoEnter);
        waitForFacebooksArming();
        assertEquals("the stale arming left auto-enter on", Collections.singletonList(false), screen.autoEnter);
        assertTrue(statusLine(), statusLine().contains(PictureInPicture.KEPT_OUT + " 1"));
    }

    @Test
    public void facebookArmingAgainAfterTheWindowClosesIsLeftAlone() {
        PictureInPicture.armed(screen, player, new Object[0]);
        floatAndComeBack();
        PictureInPicture.armed(screen, player, new Object[0]);
        waitForFacebooksArming();
        assertEquals("auto-enter changed after Facebook's own arming", Collections.emptyList(), screen.autoEnter);
    }

    @Test
    public void aPauseAndResumeOutsideTheWindowChangesNothing() {
        PictureInPicture.Arrivals arrivals = new PictureInPicture.Arrivals();
        PictureInPicture.armed(screen, player, new Object[0]);
        arrivals.onActivityPaused(screen);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PictureInPicture.FLOAT_CHECK_MS));
        arrivals.onActivityResumed(screen);
        waitForFacebooksArming();
        assertEquals("a resume outside the window changed auto-enter", Collections.emptyList(), screen.autoEnter);
    }

    /** The window's shape for a reel, as Facebook works it out when it arms, from 1 by 2.39 up to 2.39 by 1 (#418). */
    @Test
    public void theShapeFollowsAndroidsOwnBounds() {
        assertEquals(new Rational(562, 1000), PictureInPicture.shape(9 / 16.0));
        assertEquals(new Rational(1000, 1000), PictureInPicture.shape(1.0));
        assertEquals(new Rational(419, 1000), PictureInPicture.shape(0.4195));
        assertNull("narrower than 1 by 2.39", PictureInPicture.shape(0.418));
        assertEquals("landscape", new Rational(1777, 1000), PictureInPicture.shape(16 / 9.0));
        assertEquals("a little wider than square", new Rational(1002, 1000), PictureInPicture.shape(1.0025));
        assertEquals("Android's widest", new Rational(2390, 1000), PictureInPicture.shape(2.39));
        assertNull("wider than 2.39 by 1", PictureInPicture.shape(2.4));
        assertEquals("no known shape", new Rational(9, 16), PictureInPicture.shape(0));
        assertEquals("no known shape", new Rational(9, 16), PictureInPicture.shape(Double.NaN));
    }

    /**
     * #418: Facebook's shape check stops at square, so the extension says yes past it up to
     * Android's widest, and leaves every other shape, and every shape with the switch off, to it.
     */
    @Test
    public void aLandscapeReelFitsFacebooksShapeCheckWithTheSwitchOn() {
        assertTrue("16 by 9", PictureInPicture.wideFits(1777));
        assertTrue("a little wider than square", PictureInPicture.wideFits(1001));
        assertTrue("Android's widest", PictureInPicture.wideFits(2390));
        assertFalse("square is Facebook's", PictureInPicture.wideFits(1000));
        assertFalse("tall is Facebook's", PictureInPicture.wideFits(562));
        assertFalse("no shape is Facebook's", PictureInPicture.wideFits(0));
        assertFalse("wider than 2.39 by 1", PictureInPicture.wideFits(2391));
        String line = statusLine();
        assertNotNull("no status line", line);
        assertTrue(line, line.contains(PictureInPicture.WIDE + " 3"));

        Settings.PICTURE_IN_PICTURE.save(false);
        assertFalse("switch off", PictureInPicture.wideFits(1777));
    }

    /**
     * #418: a window wider than tall turns Facebook's screen landscape and brings its video modal
     * up over the reel, so a wide shape is built square, and every other shape, and every shape with
     * the switch off, is built as it came.
     */
    @Test
    public void aLandscapeReelFloatsInASquareWindow() {
        assertEquals("16 by 9", new Rational(1, 1), PictureInPicture.window(new Rational(1777, 1000)));
        assertEquals("Android's widest", new Rational(1, 1), PictureInPicture.window(new Rational(239, 100)));
        Rational tall = new Rational(9, 16);
        assertSame("tall", tall, PictureInPicture.window(tall));
        Rational square = new Rational(1, 1);
        assertSame("square", square, PictureInPicture.window(square));
        assertNull("no shape", PictureInPicture.window(null));
        String line = statusLine();
        assertNotNull("no status line", line);
        assertTrue(line, line.contains(PictureInPicture.SQUARE + " 2"));

        Settings.PICTURE_IN_PICTURE.save(false);
        Rational wide = new Rational(1777, 1000);
        assertSame("switch off", wide, PictureInPicture.window(wide));
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
