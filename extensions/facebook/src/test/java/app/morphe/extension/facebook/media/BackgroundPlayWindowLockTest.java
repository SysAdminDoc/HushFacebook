/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.time.Duration;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.media.BackgroundPlayButtonTest.Screen;
import app.morphe.extension.facebook.media.BackgroundPlayForTests.Player;
import app.morphe.extension.facebook.media.TapToPlayForTests.Trigger;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * #495: locking the phone while a video plays in the picture-in-picture window. Android stops the
 * window's screen without pausing it, so Facebook's manager had no pause of that video to ask about
 * and the sound ended, though Keep playing in the background was on.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BackgroundPlayWindowLockTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final Object handler = new Object();
    private ActivityController<Screen> controller;
    private Screen screen;
    private Player player;

    @Before
    public void start() {
        SystemClock.sleep(60_000);
        BackgroundPlayForTests.forget();
        PictureInPicture.forget();
        BackgroundPlay.useHandler(handler);
        HookStatus.clear();
        FeedFilterCounters.clear();
        Settings.KEEP_PLAYING_IN_BACKGROUND.save(true);
        controller = Robolectric.buildActivity(Screen.class).setup();
        screen = controller.get();
        // A reel Facebook started on its own, floating in the window.
        player = new Player();
        BackgroundPlay.started(player, Trigger.BY_AUTOPLAY);
        player.activate();
        PictureInPicture.armed(screen, player, null);
        controller.pause();
    }

    @After
    public void restore() {
        Settings.KEEP_PLAYING_IN_BACKGROUND.resetToDefault();
        BackgroundPlayForTests.forget();
        PictureInPicture.forget();
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    private void screenOff() {
        PowerManager power = (PowerManager) screen.getSystemService(Context.POWER_SERVICE);
        shadowOf(power).setIsInteractive(false);
    }

    /** Whether the handler says no about a pause of the window's video by the window's own trigger. */
    private boolean refused() {
        return BackgroundPlay.deciding(handler, player.video, Trigger.valueOf(BackgroundPlay.WINDOW_PAUSE_TRIGGER));
    }

    @Test
    public void turningTheScreenOffCarriesTheWindowsVideoOn() {
        screenOff();
        controller.stop();
        assertFalse("its pause is what Facebook's manager asks about", player.playing);
        assertFalse("the handler stopped the window's video", refused());
        assertTrue(BackgroundPlay.skipCheck());
        assertTrue("the notification's flag", BackgroundPlay.notificationAllowed());
        BackgroundPlay.answered(true);
    }

    @Test
    public void aLockWithTheScreenStillOnCarriesItOnToo() {
        KeyguardManager keyguard = (KeyguardManager) screen.getSystemService(Context.KEYGUARD_SERVICE);
        shadowOf(keyguard).setKeyguardLocked(true);
        controller.stop();
        assertFalse(player.playing);
        assertFalse(refused());
    }

    @Test
    public void closingTheWindowWithThePhoneInUseEndsTheVideo() {
        controller.stop();
        assertTrue("closing the window is you stopping it", player.playing);
        assertTrue(refused());
    }

    @Test
    public void withTheSwitchOffTheLockChangesNothing() {
        Settings.KEEP_PLAYING_IN_BACKGROUND.save(false);
        screenOff();
        controller.stop();
        assertTrue(player.playing);
        assertTrue(refused());
    }

    @Test
    public void aVideoPausedInTheWindowStaysPaused() {
        player.playing = false;
        screenOff();
        controller.stop();
        assertTrue(refused());
    }

    @Test
    public void aScreenOutsideTheWindowIsLeftToItsOwnPause() {
        screen.inWindow = false;
        screenOff();
        controller.stop();
        assertTrue("Facebook pauses a full screen video itself", player.playing);
    }

    @Test
    public void anotherFacebookScreenStoppingIsNotTheWindow() {
        ActivityController<Screen> other = Robolectric.buildActivity(Screen.class).setup();
        screenOff();
        other.pause().stop();
        assertTrue(player.playing);
    }

    /** The trigger Facebook's background player starts with, by name as the hook reads it. */
    private enum BackgroundTrigger { BY_BACKGROUND_PLAY }

    /** The phone locks and Facebook's background player takes the window's video on. */
    private Player lockedAndCarriedOn() {
        screenOff();
        controller.stop();
        assertFalse(refused());
        BackgroundPlay.answered(true);
        // A player of its own for the same video, which starts as any player does.
        Player background = new Player();
        background.video = player.video;
        BackgroundPlay.started(background, BackgroundTrigger.BY_BACKGROUND_PLAY);
        background.activate();
        PictureInPicture.playerPlaying(background);
        return background;
    }

    private void phoneInUse() {
        PowerManager power = (PowerManager) screen.getSystemService(Context.POWER_SERVICE);
        shadowOf(power).setIsInteractive(true);
    }

    /** Runs out every later try at starting the window's video. */
    private void allTriesPass() {
        shadowOf(Looper.getMainLooper())
                .idleFor(Duration.ofMillis(BackgroundPlay.WINDOW_RESUME_MS * (BackgroundPlay.WINDOW_RESUME_TRIES + 1)));
    }

    /** Facebook's handler gives the video back: its player and notification go, then it returns. */
    private void handedBack(Player background) {
        BackgroundPlay.returning(handler);
        background.playing = false;
        BackgroundPlay.returned();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(BackgroundPlay.WINDOW_RESUME_MS));
    }

    @Test
    public void unlockingPlaysTheWindowsVideoOn() {
        Player background = lockedAndCarriedOn();
        phoneInUse();
        handedBack(background);
        assertEquals("the window came back paused", 1, player.plays);
        assertTrue(player.playing);

        handedBack(background);
        assertEquals("one lock, one start", 1, player.plays);
    }

    @Test
    public void itGoesOnWhenTheBackgroundPlayerIsTheWindowsOwnToo() {
        screenOff();
        controller.stop();
        assertFalse(refused());
        BackgroundPlay.answered(true);
        player.playing = true;
        BackgroundPlay.started(player, BackgroundTrigger.BY_BACKGROUND_PLAY);
        phoneInUse();
        handedBack(player);
        assertEquals(1, player.plays);
    }

    @Test
    public void aVideoPausedFromTheLockScreenStaysPaused() {
        Player background = lockedAndCarriedOn();
        background.playing = false;
        phoneInUse();
        handedBack(background);
        assertEquals(0, player.plays);
        assertFalse(player.playing);
    }

    @Test
    public void nothingStartsWhileThePhoneIsStillLocked() {
        handedBack(lockedAndCarriedOn());
        allTriesPass();
        assertEquals(0, player.plays);
    }

    /** Facebook hands the video back as the lock screen goes, and the phone reads locked a moment longer. */
    @Test
    public void itStartsOnceAnUnlockUnderWayIsDone() {
        handedBack(lockedAndCarriedOn());
        assertEquals(0, player.plays);
        phoneInUse();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(BackgroundPlay.WINDOW_RESUME_MS));
        assertEquals(1, player.plays);
    }

    @Test
    public void itStopsTryingAfterAWhile() {
        handedBack(lockedAndCarriedOn());
        allTriesPass();
        phoneInUse();
        allTriesPass();
        assertEquals(0, player.plays);
    }

    /** Facebook's background player plays no view, so it never becomes the window's video. */
    @Test
    public void theWindowKeepsItsOwnVideoWhileTheBackgroundPlayerRuns() {
        lockedAndCarriedOn();
        assertSame(player, PictureInPicture.currentPlayer());
    }

    /** That player ran on while the window's sat still, so the window picks up from there. */
    @Test
    public void theWindowPicksUpWhereTheBackgroundPlayerGotTo() {
        player.position = 12_000;
        Player background = lockedAndCarriedOn();
        background.position = 41_000;
        phoneInUse();
        handedBack(background);
        assertEquals(41_000, player.positionAtPlay);
    }

    @Test
    public void anotherVideosPlaceIsNotCarriedOver() {
        player.position = 12_000;
        Player background = lockedAndCarriedOn();
        background.video = "another video";
        background.position = 41_000;
        phoneInUse();
        handedBack(background);
        assertEquals(1, player.plays);
        assertEquals(12_000, player.positionAtPlay);
    }

    /** A player started after the background player's session is an ordinary reel again. */
    @Test
    public void afterTheHandBackThatPlayerCountsAsAnyOther() {
        Player background = lockedAndCarriedOn();
        phoneInUse();
        handedBack(background);
        assertFalse(BackgroundPlay.isBackgroundPlayer(background));
    }

    @Test
    public void aWindowClosedMeanwhileStaysQuiet() {
        Player background = lockedAndCarriedOn();
        phoneInUse();
        screen.inWindow = false;
        handedBack(background);
        allTriesPass();
        assertEquals(0, player.plays);
    }

    @Test
    public void aVideoAlreadyPlayingAgainIsLeftAlone() {
        Player background = lockedAndCarriedOn();
        phoneInUse();
        player.playing = true;
        handedBack(background);
        assertEquals(0, player.plays);
    }

    /**
     * #495 on 588: the window's own Pause and Play act on Facebook's current player, which after a
     * lock was still the background player, gone with its session, so neither button did a thing.
     */
    @Test
    public void theWindowsButtonsGoBackToItsVideoAfterALock() {
        Player background = lockedAndCarriedOn();
        assertSame("the background player took over", background, BackgroundPlayForTests.currentPlayer());
        phoneInUse();
        handedBack(background);
        assertSame(player, BackgroundPlayForTests.currentPlayer());
    }

    @Test
    public void aVideoPausedFromTheLockScreenGetsItsButtonsBackToo() {
        Player background = lockedAndCarriedOn();
        background.playing = false;
        phoneInUse();
        handedBack(background);
        assertSame("its Play has to reach it", player, BackgroundPlayForTests.currentPlayer());
        assertEquals(0, player.plays);
    }

    @Test
    public void aVideoAlreadyPlayingAgainGetsItsButtonsBack() {
        Player background = lockedAndCarriedOn();
        phoneInUse();
        player.playing = true;
        handedBack(background);
        assertSame(player, BackgroundPlayForTests.currentPlayer());
        assertEquals(0, player.plays);
    }

    @Test
    public void aWindowClosedMeanwhileKeepsFacebooksCurrentPlayer() {
        Player background = lockedAndCarriedOn();
        phoneInUse();
        screen.inWindow = false;
        handedBack(background);
        allTriesPass();
        assertSame(background, BackgroundPlayForTests.currentPlayer());
    }

    @Test
    public void turningTheSwitchOffMeanwhileLeavesItPaused() {
        Player background = lockedAndCarriedOn();
        phoneInUse();
        Settings.KEEP_PLAYING_IN_BACKGROUND.save(false);
        handedBack(background);
        assertEquals(0, player.plays);
    }

    /** The phone unlocked and the window's video playing again. */
    private void backInTheWindow() {
        Player background = lockedAndCarriedOn();
        phoneInUse();
        handedBack(background);
        assertEquals(1, player.plays);
    }

    private void looksPass(int looks) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(BackgroundPlay.WINDOW_KEEP_MS * looks));
    }

    /** Back from a lock, Facebook's restart at the end of the reel dies with no pause, every loop. */
    @Test
    public void aWindowVideoThatStopsByItselfAfterALockStartsAgain() {
        backInTheWindow();
        player.playing = false;
        looksPass(1);
        assertEquals("the loop ended the sound", 2, player.plays);
        assertTrue(player.playing);

        player.playing = false;
        looksPass(1);
        assertEquals("and so did the next one", 3, player.plays);
    }

    /**
     * #495 on 588: back from a lock, the window's reel played to its end and Facebook's loop never
     * came, so it sat on its last frame however often play was pressed.
     */
    @Test
    public void aWindowVideoThatStopsAtItsEndAfterALockStartsOver() {
        player.length = 9541;
        backInTheWindow();
        player.position = 9541;
        player.playing = false;
        looksPass(1);
        assertTrue("the reel didn't start over", player.playing);
        assertEquals("from its start", 0, player.positionAtPlay);
        looksPass(BackgroundPlay.WINDOW_KEEP_MISSES * 2);
        assertEquals(2, player.plays);

        player.position = 4000;
        player.playing = false;
        looksPass(1);
        assertEquals("a stop partway through goes on from there", 4000, player.positionAtPlay);
    }

    /**
     * #495 on 588, from the log: back from a lock the window's reel went on from 8744 of 9334, and at
     * its end Facebook put it at 0, stopped, then let the video go (0 of 0), so nothing could start
     * it. That's a player Facebook doesn't have as its current one, here because it made another one
     * current after the hand-back. It starts over just before the end instead, still playing, with no
     * press of play. The hand-back here is far enough from the end to go on from there.
     */
    @Test
    public void aWindowVideoNearItsEndAfterALockStartsOverBeforeItGetsThere() {
        player.length = 9334;
        Player background = lockedAndCarriedOn();
        background.position = 7000;
        phoneInUse();
        handedBack(background);
        assertEquals(1, player.plays);
        assertEquals(7000, player.positionAtPlay);
        new Player().activate();

        player.position = 9100;
        looksPass(1);
        assertEquals("the reel ran into its end", 0, player.position);
        assertTrue(player.playing);
        assertEquals("a seek, not a press of play", 1, player.plays);

        player.position = 9000;
        looksPass(2);
        assertEquals("and so does every loop after", 0, player.position);
        assertEquals(1, player.plays);

        player.position = 3000;
        looksPass(4);
        assertEquals("partway through it plays on", 3000, player.position);
    }

    /**
     * #495 on 588, from the log: the background player handed the window's reel back at 7551 of 8033.
     * The seek there landed on the frame before (6551), it buffered, and Facebook let the video go
     * (0 of 0) when its loop came. A hand-back that close to the end starts the reel over instead.
     */
    @Test
    public void aHandBackRightBeforeItsEndStartsTheReelOver() {
        player.length = 8033;
        Player background = lockedAndCarriedOn();
        background.position = 7551;
        phoneInUse();
        handedBack(background);
        assertEquals(1, player.plays);
        assertEquals("from its start", 0, player.positionAtPlay);
        assertTrue(player.playing);
    }

    /**
     * #495 on 588, from the log: with the window's buttons back on its player, Facebook looped the
     * reel itself at 8293 of 8520, and a second seek to the start 30 ms later lost the video (0 of 0).
     * While its player is Facebook's current one, the end is left to Facebook.
     */
    @Test
    public void aWindowVideoNearItsEndIsLeftToFacebooksOwnLoop() {
        player.length = 8520;
        Player background = lockedAndCarriedOn();
        background.position = 5770;
        phoneInUse();
        handedBack(background);
        assertSame(player, BackgroundPlayForTests.currentPlayer());

        player.position = 8293;
        looksPass(3);
        assertEquals("nothing but Facebook seeks it", 8293, player.position);
        assertEquals(1, player.plays);
    }

    @Test
    public void aVideoStillPlayingIsNotPressedAgain() {
        backInTheWindow();
        looksPass(4);
        assertEquals(1, player.plays);
    }

    /** #495 on 588: Facebook let the window's player go, and play was pressed on it twice a second for over a minute. */
    @Test
    public void aPlayerThatWontStartIsLeftAloneAfterAFewPresses() {
        backInTheWindow();
        player.stuck = true;
        player.playing = false;
        looksPass(BackgroundPlay.WINDOW_KEEP_MISSES * 3);
        assertEquals("a few presses, then it's left alone", 1 + BackgroundPlay.WINDOW_KEEP_MISSES, player.plays);
        assertFalse(player.playing);
    }

    @Test
    public void aVideoThatStartsBetweenStopsIsStillLookedAfter() {
        backInTheWindow();
        for (int loop = 0; loop < BackgroundPlay.WINDOW_KEEP_MISSES * 2; loop++) {
            player.playing = false;
            looksPass(1);
            assertTrue("loop " + loop + " started again", player.playing);
            // The reel plays to its end before the next restart, so a look sees it going.
            looksPass(1);
        }
        assertEquals(1 + BackgroundPlay.WINDOW_KEEP_MISSES * 2, player.plays);
    }

    @Test
    public void aVideoThatWasAlreadyPlayingIsLookedAfterToo() {
        Player background = lockedAndCarriedOn();
        phoneInUse();
        player.playing = true;
        handedBack(background);
        assertEquals(0, player.plays);
        player.playing = false;
        looksPass(1);
        assertEquals(1, player.plays);
    }

    @Test
    public void aPauseInTheWindowStaysAPause() {
        backInTheWindow();
        player.pause(Trigger.BY_USER);
        looksPass(4);
        assertEquals(1, player.plays);
        assertFalse(player.playing);
    }

    @Test
    public void aClosedWindowIsNotStartedAgainEvenIfItReopens() {
        backInTheWindow();
        screen.inWindow = false;
        player.playing = false;
        looksPass(2);
        screen.inWindow = true;
        looksPass(2);
        assertEquals(1, player.plays);
    }

    @Test
    public void anotherVideoInTheWindowEndsTheLookingAfter() {
        backInTheWindow();
        Player next = new Player();
        PictureInPicture.armed(screen, next, null);
        player.playing = false;
        looksPass(2);
        assertEquals(1, player.plays);
    }

    @Test
    public void turningTheSwitchOffEndsTheLookingAfter() {
        backInTheWindow();
        Settings.KEEP_PLAYING_IN_BACKGROUND.save(false);
        player.playing = false;
        looksPass(2);
        assertEquals(1, player.plays);
    }

    /** The headphones button leaves the window on purpose, so its video isn't brought back. */
    @Test
    public void leavingByTheHeadphonesButtonIsNotALock() throws Exception {
        BackgroundPlay.leaveWindow(player, screen);
        assertFalse(refused());
        BackgroundPlay.answered(true);
        Player background = new Player();
        BackgroundPlay.started(background, BackgroundTrigger.BY_BACKGROUND_PLAY);
        handedBack(background);
        assertEquals(0, player.plays);
    }
}
