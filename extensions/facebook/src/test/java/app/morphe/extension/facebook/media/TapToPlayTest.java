/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.ref.WeakReference;
import java.util.Collections;

import app.morphe.extension.facebook.media.TapToPlayForTests.Autoplay;
import app.morphe.extension.facebook.media.TapToPlayForTests.Trigger;
import app.morphe.extension.facebook.reels.FeedVideoAlone;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * The rule Tap to play holds every start to: a start goes ahead on an armed player, on a control
 * that isn't a tap, or within a second of a tap unless Facebook sent it because something came
 * into view or came back. Everything else is held. Off, paused, before the settings are ready, or
 * when the rule throws, every start goes ahead, and the Autoplay setting reads what was chosen.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class TapToPlayTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        // Moves the clock well past zero, so "a tap five seconds ago" is a time the clock has seen.
        SystemClock.sleep(60_000);
        TapToPlayForTests.forget();
        HookStatus.clear();
        // The patch is in Morphe Manager's default selection with its switch off; these tests run with it on.
        Settings.TAP_TO_PLAY.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.TAP_TO_PLAY.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        TapToPlayForTests.forget();
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
    }

    private static void tapAt(long upAt) {
        TapClock.record(MotionEvent.ACTION_DOWN, 50, 50, upAt - 90, 8);
        TapClock.record(MotionEvent.ACTION_UP, 51, 52, upAt, 8);
    }

    private static boolean decide(Object player, Trigger trigger, long now) {
        return TapToPlay.decide(player, trigger.name(), false, now, "");
    }

    /** A BY_AUTOPLAY start on a player whose params say it plays a reel, as 581's Reels tab sends. */
    private static boolean autoplayedReel(Object player) {
        return TapToPlay.decide(player, Trigger.BY_AUTOPLAY.name(), true, SystemClock.uptimeMillis(), "");
    }

    @Test
    public void facebooksBackgroundPlayerGoesAheadOnlyForAVideoKeepPlayingCarriesOn() {
        assertFalse("with nothing carried on", decide(new Object(), "BY_BACKGROUND_PLAY", SystemClock.uptimeMillis()));
        BackgroundPlay.carrying = true;
        try {
            assertTrue("Facebook's background player", decide(new Object(), "BY_BACKGROUND_PLAY", SystemClock.uptimeMillis()));
            assertFalse("another trigger", decide(new Object(), "BY_AUTOPLAY", SystemClock.uptimeMillis()));
        } finally {
            BackgroundPlay.forget();
        }
        BackgroundPlay.session = true;
        try {
            assertTrue("its notification's play", decide(new Object(), "BY_BACKGROUND_PLAY", SystemClock.uptimeMillis()));
        } finally {
            BackgroundPlay.forget();
        }
    }

    private static boolean decide(Object player, String trigger, long now) {
        return TapToPlay.decide(player, trigger, false, now, "");
    }

    private enum ReelControl { AUTOPLAY_OFF_INIT_STATE, PLAYING, PAUSED, PLAYBACK_COMPLETE, UNKNOWN }

    private enum PlayerState {
        UNINITIALIZED, UNPREPARED, PREPARED, ATTEMPT_TO_PLAY, PLAYING, SEEKING, ATTEMPT_TO_PAUSE, PAUSED,
        FINISH_ONE_LOOP, PLAYBACK_COMPLETE, ERROR
    }

    @Test
    public void aStartInAPictureInPictureWindowGoesAheadAndKeepsItsPlayerArmed() {
        long now = SystemClock.uptimeMillis();
        Activity screen = Robolectric.buildActivity(Activity.class).setup().get();
        TapToPlay.activityResumed(screen);
        Object reel = new Object();
        assertFalse("a reel coming into view on the screen", decide(reel, Trigger.BY_SHORT_FORM_VIDEO_FULLY_VISIBLE, now));
        screen.enterPictureInPictureMode();
        assertTrue("the same reel starting again in the window",
                decide(reel, Trigger.BY_SHORT_FORM_VIDEO_FULLY_VISIBLE, now + 100));
        assertTrue("its player stays armed for its own restarts", TapToPlay.armed(reel));
        TapToPlayForTests.forget();
        assertFalse("with no screen in front, nothing is in a window",
                decide(new Object(), Trigger.BY_SHORT_FORM_VIDEO_FULLY_VISIBLE, now + 200));
    }

    /** A Facebook screen whose picture-in-picture state the test sets, in and back out. */
    public static class WindowScreen extends Activity {
        boolean inWindow;

        @Override
        public boolean isInPictureInPictureMode() {
            return inWindow;
        }
    }

    /** A reel let through in the window of [screen], which then opens back out: Facebook pauses it. */
    private static Object reelPlayedInTheWindow(WindowScreen screen, long now) {
        TapToPlay.activityResumed(screen);
        Object reel = new Object();
        assertFalse("a reel coming into view on the screen", TapToPlay.decide(reel, Trigger.BY_AUTOPLAY.name(), true, now, ""));
        screen.inWindow = true;
        assertTrue("the reel in the window", decide(reel, Trigger.BY_SHORT_FORM_VIDEO_FULLY_VISIBLE, now + 100));
        return reel;
    }

    @Test
    public void aReelThatPlayedInTheWindowGoesOnWhenTheWindowOpensBackOutOnce() {
        long now = SystemClock.uptimeMillis();
        WindowScreen screen = Robolectric.buildActivity(WindowScreen.class).setup().get();
        Object reel = reelPlayedInTheWindow(screen, now);
        screen.inWindow = false;
        TapToPlay.paused(reel);
        TapToPlay.activityResumed(screen);
        assertFalse("another reel isn't the window's", TapToPlay.decide(new Object(), Trigger.BY_AUTOPLAY.name(), true, now + 400, ""));
        assertTrue("the window's reel starting again as the window opens back out (#90)",
                TapToPlay.decide(reel, Trigger.BY_AUTOPLAY.name(), true, now + 500, ""));

        TapToPlay.paused(reel);
        assertFalse("a pause after that waits for a tap, like any",
                TapToPlay.decide(reel, Trigger.BY_AUTOPLAY.name(), true, now + 900, ""));
    }

    @Test
    public void theWindowsReelWaitsWhenPausedInTheWindowClosedWithItsXOrStartedLate() {
        long now = SystemClock.uptimeMillis();
        WindowScreen screen = Robolectric.buildActivity(WindowScreen.class).setup().get();

        Object pausedThere = reelPlayedInTheWindow(screen, now);
        TapToPlay.paused(pausedThere);
        screen.inWindow = false;
        TapToPlay.activityResumed(screen);
        assertFalse("a reel paused in the window waits once it opens back out",
                TapToPlay.decide(pausedThere, Trigger.BY_AUTOPLAY.name(), true, now + 500, ""));

        Object closed = reelPlayedInTheWindow(screen, now + 1000);
        screen.inWindow = false;
        TapToPlay.paused(closed);
        TapToPlay.activityStopped(screen);
        TapToPlay.activityResumed(screen);
        assertFalse("a window closed with its X leaves its reel waiting",
                TapToPlay.decide(closed, Trigger.BY_AUTOPLAY.name(), true, now + 1500, ""));

        Object late = reelPlayedInTheWindow(screen, now + 2000);
        screen.inWindow = false;
        TapToPlay.paused(late);
        TapToPlay.activityResumed(screen);
        long back = SystemClock.uptimeMillis();
        assertFalse("a start long after the window opened back out waits",
                TapToPlay.decide(late, Trigger.BY_AUTOPLAY.name(), true, back + TapToPlay.BACK_FROM_WINDOW_MS + 1, ""));
    }

    @Test
    public void playingClearsTheHeldStartButtonEvenWhenFacebooksViewerFlagIsOff() {
        assertTrue(TapToPlay.showReelPlayButton(false));
        assertTrue("PLAYING must clear the forced initial overlay regardless of the viewer's flag",
                TapToPlay.clearReelPlayButton(false, ReelControl.AUTOPLAY_OFF_INIT_STATE));
        assertTrue("Facebook's own cleanup still runs",
                TapToPlay.clearReelPlayButton(true, ReelControl.AUTOPLAY_OFF_INIT_STATE));
        for (ReelControl control : ReelControl.values()) {
            if (control == ReelControl.AUTOPLAY_OFF_INIT_STATE) continue;
            assertFalse("ordinary controls stay native: " + control, TapToPlay.clearReelPlayButton(false, control));
            assertTrue(TapToPlay.clearReelPlayButton(true, control));
        }
        assertFalse(TapToPlay.clearReelPlayButton(false, null));
        assertFalse(TapToPlay.clearReelPlayButton(false, "AUTOPLAY_OFF_INIT_STATE"));
        assertTrue("a different reel still waits for its own tap", TapToPlay.showReelPlayButton(false));
    }

    @Test
    public void theReelOverlayCleanupKeepsTheNativeAnswerWhileOffPausedOrStarting() {
        Settings.TAP_TO_PLAY.save(false);
        assertFalse(TapToPlay.clearReelPlayButton(false, ReelControl.AUTOPLAY_OFF_INIT_STATE));
        assertTrue(TapToPlay.clearReelPlayButton(true, ReelControl.AUTOPLAY_OFF_INIT_STATE));
        Settings.TAP_TO_PLAY.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(TapToPlay.clearReelPlayButton(false, ReelControl.AUTOPLAY_OFF_INIT_STATE));
        assertTrue(TapToPlay.clearReelPlayButton(true, ReelControl.AUTOPLAY_OFF_INIT_STATE));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
                assertFalse(TapToPlay.clearReelPlayButton(false, ReelControl.AUTOPLAY_OFF_INIT_STATE)));
        SettingsContextRule.beforeThePauseIsDecided(() ->
                assertTrue(TapToPlay.clearReelPlayButton(true, ReelControl.AUTOPLAY_OFF_INIT_STATE)));
    }

    @Test
    public void aLateAutoplayDisabledEventLeavesAPlayingReelWithoutItsButton() {
        assertTrue("a playing reel keeps its button off (#91)", TapToPlay.keepReelPlaying(PlayerState.PLAYING));
        for (PlayerState state : PlayerState.values()) {
            if (state == PlayerState.PLAYING) continue;
            assertFalse("a reel that isn't playing gets its button: " + state, TapToPlay.keepReelPlaying(state));
        }
        assertFalse(TapToPlay.keepReelPlaying(null));
        assertFalse(TapToPlay.keepReelPlaying("PLAYING"));
        Settings.TAP_TO_PLAY.save(false);
        assertFalse("with the switch off Facebook's event goes through", TapToPlay.keepReelPlaying(PlayerState.PLAYING));
        Settings.TAP_TO_PLAY.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(TapToPlay.keepReelPlaying(PlayerState.PLAYING));
        PauseForTests.resume();
    }

    @Test
    public void theSwitchStartsOff() {
        assertFalse("the switch starts off", Settings.TAP_TO_PLAY.defaultValue);
    }

    @Test
    public void noStartGoesAheadWithoutATap() {
        for (Trigger trigger : Trigger.values()) {
            if (TapToPlay.CONTROLS.contains(trigger.name())) continue;
            assertFalse(trigger.name(), TapToPlay.allowStart(new Object(), trigger));
            assertFalse(trigger.name(), TapToPlay.allowLegacyStart(new Object(), trigger));
        }
        assertEquals("a held start arms nothing", 0, TapToPlay.armedCount());
    }

    /**
     * #59: a video a browser hands Facebook opens in a player that starts it with BY_USER and no
     * tap, and has no play button. The link lets that one start play, arming its player.
     */
    @Test
    public void aLinkFromAnotherAppPlaysTheFirstByUserStartOnly() {
        long now = 100_000;
        Object opened = new Object();
        TapToPlay.linkOpened(now - 3_000);
        assertTrue("the start the link asked for", decide(opened, Trigger.BY_USER, now));
        assertTrue(TapToPlay.armed(opened));
        assertTrue("the opened video's own restarts", decide(opened, Trigger.BY_PLAYER, now + 500));
        assertFalse("the next player's BY_USER start", decide(new Object(), Trigger.BY_USER, now + 600));
        assertFalse("an unrelated player's BY_USER start, an ad say", decide(new Object(), Trigger.BY_USER, now + 650));
        assertFalse("an unrelated player, an ad say", decide(new Object(), Trigger.BY_AUTOPLAY, now + 700));
        assertEquals(1, TapToPlay.armedCount());
    }

    /**
     * Only a BY_USER start takes the link. Starts Facebook makes for something coming into view or
     * coming back, an ad's autoplay among them, are held as ever and leave it for that start.
     */
    @Test
    public void aLinkLetsNothingButByUserThrough() {
        long now = 100_000;
        TapToPlay.linkOpened(now - 100);
        for (Trigger trigger : Trigger.values()) {
            if (trigger == Trigger.BY_USER || TapToPlay.CONTROLS.contains(trigger.name())) continue;
            assertFalse(trigger.name(), decide(new Object(), trigger, now));
        }
        assertFalse("no trigger at all", TapToPlay.decide(new Object(), null, false, now, ""));
        assertEquals("a held start arms nothing", 0, TapToPlay.armedCount());
        assertTrue("the link is still there for its start", decide(new Object(), Trigger.BY_USER, now + 200));
    }

    @Test
    public void aLinkCountsForFifteenSeconds() {
        long now = 100_000;
        TapToPlay.linkOpened(now - TapToPlay.LINK_WINDOW_MS);
        assertTrue("a link exactly fifteen seconds ago", decide(new Object(), Trigger.BY_USER, now));
        TapToPlay.linkOpened(now - TapToPlay.LINK_WINDOW_MS - 1);
        assertFalse("a link just over fifteen seconds ago", decide(new Object(), Trigger.BY_USER, now));
        assertFalse("and it's gone after that", decide(new Object(), Trigger.BY_USER, now - 2));
        TapToPlay.linkOpened(now + 5);
        assertFalse("a link the clock hasn't reached yet", decide(new Object(), Trigger.BY_USER, now));
    }

    /**
     * A link waiting goes to no player after a swipe, a tap or a control: the person moved on or
     * started something themselves, and the next player's BY_USER start is held.
     */
    @Test
    public void aSwipeATapOrAControlDropsAWaitingLink() {
        long now = 100_000;
        TapToPlay.linkOpened(now - 100);
        TapToPlay.nonTapGesture();
        assertFalse("the next item after a swipe", decide(new Object(), Trigger.BY_USER, now));

        TapToPlay.linkOpened(now - 100);
        tapAt(now - 10);
        assertTrue(decide(new Object(), Trigger.BY_AUTOPLAY, now));
        assertFalse("another player after a tapped start", decide(new Object(), Trigger.BY_USER, now + 2_000));

        TapToPlay.linkOpened(now + 3_000);
        assertTrue(decide(new Object(), Trigger.BY_MEDIA_SESSION_CONTROLS, now + 3_100));
        assertFalse("another player after a control", decide(new Object(), Trigger.BY_USER, now + 3_200));
    }

    /**
     * An armed player's own BY_USER restart leaves the link for the player it opened, and a start
     * whose clock was read before the link came doesn't take it.
     */
    @Test
    public void onlyAHeldStartTakesTheLink() {
        long now = 100_000;
        Object playing = new Object();
        tapAt(now - 10);
        assertTrue(decide(playing, Trigger.BY_USER, now));
        TapToPlay.linkOpened(now + 2_000);
        assertTrue("the armed player's restart", decide(playing, Trigger.BY_USER, now + 2_100));
        assertFalse("a start from before the link", decide(new Object(), Trigger.BY_USER, now + 1_999));
        assertTrue("the opened player still gets it", decide(new Object(), Trigger.BY_USER, now + 2_200));
    }

    /** A start a tap let through takes the link too, so a later start can't use it. */
    @Test
    public void aTappedStartTakesTheLink() {
        long now = 100_000;
        TapToPlay.linkOpened(now - 1_000);
        tapAt(now - 10);
        assertTrue(decide(new Object(), Trigger.BY_USER, now));
        assertFalse("a second later, past the tap", decide(new Object(), Trigger.BY_USER, now + TapToPlay.TAP_WINDOW_MS + 100));
    }

    /** The video after the one a link opened, in the same player, waits for its own tap. */
    @Test
    public void aLinkDoesntReachTheNextVideo() {
        Object player = new Object();
        TapToPlay.linkOpened(SystemClock.uptimeMillis());
        assertTrue(TapToPlay.allowStart(player, Trigger.BY_USER));
        SystemClock.sleep(TapToPlay.BIND_GRACE_MS + 1);
        TapToPlay.rebound(player);
        assertFalse(TapToPlay.allowStart(player, Trigger.BY_SHORT_FORM_VIDEO_FULLY_VISIBLE));
        assertFalse(TapToPlay.allowStart(player, Trigger.BY_USER));
    }

    private static Activity screen(Intent intent) {
        return Robolectric.buildActivity(Activity.class, intent).create().get();
    }

    private static Intent link(String referrer) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.facebook.com/watch/?v=1"));
        if (referrer != null) intent.putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://" + referrer));
        return intent;
    }

    /** Whether a BY_USER start with no tap plays right now, which takes any link waiting. */
    private static boolean byUserPlays() {
        return TapToPlay.allowStart(new Object(), Trigger.BY_USER);
    }

    @Test
    public void aLinkFromABrowserOrAnAppThatDoesntSayCounts() {
        TapToPlay.activityCreated(screen(link("com.android.chrome")), null);
        assertTrue("a browser's link", byUserPlays());
        assertFalse("once", byUserPlays());
        TapToPlay.activityCreated(screen(link(null)), null);
        assertTrue("a link with no referrer", byUserPlays());
    }

    /**
     * Facebook opening its own screens, a screen brought back, a screen that isn't a link's, and a
     * link while the switch is off or Hushfacebook is paused record nothing.
     */
    @Test
    public void onlyAFreshScreenForAnotherAppsLinkCounts() {
        Activity own = screen(link(null));
        TapToPlay.activityCreated(screen(link(own.getPackageName())), null);
        assertFalse("Facebook's own link", byUserPlays());
        TapToPlay.activityCreated(screen(link("com.android.chrome")), new Bundle());
        assertFalse("a screen brought back", byUserPlays());
        TapToPlay.activityCreated(screen(new Intent(Intent.ACTION_MAIN)), null);
        assertFalse("the launcher", byUserPlays());
        TapToPlay.activityCreated(screen(new Intent(Intent.ACTION_VIEW)), null);
        assertFalse("a view with no link", byUserPlays());
        Settings.TAP_TO_PLAY.save(false);
        TapToPlay.activityCreated(screen(link("com.android.chrome")), null);
        Settings.TAP_TO_PLAY.save(true);
        assertFalse("a link while the switch was off", byUserPlays());
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        TapToPlay.activityCreated(screen(link("com.android.chrome")), null);
        PauseForTests.resume();
        assertFalse("a link while paused", byUserPlays());
    }

    /** Named like Facebook's fullscreen player, which the rule knows by its simple name. */
    public static final class FullscreenVideoPlayerActivity extends Activity { }

    /**
     * #242: a video in a comment opens Facebook's fullscreen player from a sheet whose taps the tap
     * clock never hears, the last tap it saw seconds old, and the player starts it with BY_USER.
     * Held, it sat paused until Facebook was left and opened again. Opening fresh, that player lets
     * its first BY_USER start through, once, and only while the switch is on.
     */
    @Test
    public void facebooksFullscreenPlayerPlaysTheVideoItOpenedFor() {
        tapAt(SystemClock.uptimeMillis() - 3_189);
        assertFalse("the control: a BY_USER start seconds after the last tap", byUserPlays());
        TapToPlay.activityCreated(Robolectric.buildActivity(FullscreenVideoPlayerActivity.class).create().get(), null);
        assertTrue("the video the fullscreen player opened for", byUserPlays());
        assertFalse("once", byUserPlays());
        TapToPlay.activityCreated(Robolectric.buildActivity(FullscreenVideoPlayerActivity.class).create().get(), new Bundle());
        assertFalse("the player brought back, not opened", byUserPlays());
        Settings.TAP_TO_PLAY.save(false);
        TapToPlay.activityCreated(Robolectric.buildActivity(FullscreenVideoPlayerActivity.class).create().get(), null);
        Settings.TAP_TO_PLAY.save(true);
        assertFalse("opened while the switch was off", byUserPlays());
        TapToPlay.activityCreated(screen(new Intent(Intent.ACTION_MAIN)), null);
        assertFalse("any other screen", byUserPlays());
    }

    @Test
    public void aStartWithinASecondOfATapGoesAhead() {
        long now = 100_000;
        tapAt(now - TapToPlay.TAP_WINDOW_MS);
        assertTrue("a tap exactly a second ago", decide(new Object(), Trigger.BY_USER, now));
        tapAt(now - TapToPlay.TAP_WINDOW_MS - 1);
        assertFalse("a tap just over a second ago", decide(new Object(), Trigger.BY_USER, now));
        tapAt(now + 5);
        assertFalse("a tap the clock hasn't reached yet", decide(new Object(), Trigger.BY_USER, now));
    }

    /**
     * A Story you tap starts with BY_AUTOPLAY right after the tap, and a song or a video Facebook
     * starts for a tap on it can say BY_PLAYER. Both go ahead within the window.
     */
    @Test
    public void autoplayAndPlayerStartsRightAfterATapGoAhead() {
        long now = 200_000;
        tapAt(now - 300);
        assertTrue(decide(new Object(), Trigger.BY_AUTOPLAY, now));
        assertTrue(decide(new Object(), Trigger.BY_PLAYER, now));
        assertTrue(decide(new Object(), Trigger.BY_USER_SWIPE, now));
    }

    /**
     * What comes into view or comes back never rides a tap: the reel the Reels tab lands on starts
     * with BY_SHORT_FORM_VIDEO_FULLY_VISIBLE right after the tap on the tab.
     */
    @Test
    public void visibilityAndResumeStartsAreHeldEvenRightAfterATap() {
        long now = 300_000;
        tapAt(now - 50);
        Trigger[] held = {
                Trigger.BY_SHORT_FORM_VIDEO_FULLY_VISIBLE, Trigger.BY_FB_SHORTS_IN_FEED_UNIT_VISIBLE,
                Trigger.BY_COLLECTIONS_STORIES_VISIBLE, Trigger.BY_SHORT_FORM_VIDEO_ONRESUME,
                Trigger.BY_CURATED_PROMPTS_H_SCROLL_ONRESUME, Trigger.BY_SURFACE_ON_RESUME,
                Trigger.BY_FB_SHORTS_INTEREST_PICKER_ON_RESUME, Trigger.BY_FRAGMENT_RESUME, Trigger.BY_FLYOUT,
        };
        for (Trigger trigger : held) {
            assertTrue(trigger.name(), TapToPlay.visibilityDriven(trigger.name()));
            assertFalse(trigger.name(), decide(new Object(), trigger, now));
        }
        // The control: the same tap starts a plain one.
        assertTrue(decide(new Object(), Trigger.BY_USER, now));
        assertFalse(TapToPlay.visibilityDriven(null));
        assertFalse(TapToPlay.visibilityDriven("BY_AUTOPLAY"));
    }

    /** The media controls, the seek bar and the music picker's loop need no tap on the screen. */
    @Test
    public void startsOnlySomethingYouDidSendsNeedNoTap() {
        assertTrue(TapToPlay.allowStart(new Object(), Trigger.BY_MEDIA_SESSION_CONTROLS));
        assertTrue(TapToPlay.allowStart(new Object(), Trigger.BY_SEEKBAR_CONTROLLER));
        assertTrue(TapToPlay.allowStart(new Object(), Trigger.BY_MUSIC_PLAYER));
        assertEquals(3, TapToPlay.armedCount());
        assertEquals(3, TapToPlay.CONTROLS.size());
    }

    /** What a tap started keeps going through Facebook's own restarts, until it's paused. */
    @Test
    public void anArmedPlayerRestartsUntilItsPaused() {
        Object player = new Object();
        Object other = new Object();
        TapToPlayForTests.tapEnded(100);
        assertTrue(TapToPlay.allowStart(player, Trigger.BY_USER));
        assertTrue(TapToPlay.armed(player));
        TapClock.forget();

        assertTrue("a restart of the same player", TapToPlay.allowStart(player, Trigger.BY_PLAYER));
        assertTrue("even one Facebook sends on coming back", TapToPlay.allowStart(player, Trigger.BY_FRAGMENT_RESUME));
        assertFalse("another player", TapToPlay.allowStart(other, Trigger.BY_PLAYER));

        TapToPlay.paused(player);
        assertFalse(TapToPlay.armed(player));
        assertFalse("the delayed start after a pause", TapToPlay.allowStart(player, Trigger.BY_FRAGMENT_RESUME));
        assertFalse(TapToPlay.allowStart(player, Trigger.BY_AUTOPLAY));

        TapToPlayForTests.tapEnded(10);
        assertTrue("a tap plays it again", TapToPlay.allowStart(player, Trigger.BY_USER));
    }

    /**
     * A new video disarms, but not a bind inside the start the gate just let through: FbGrootPlayer
     * can bind from its own play, or replay a bind it put off.
     */
    @Test
    public void aNewVideoDisarmsButTheStartsOwnBindDoesnt() {
        Object player = new Object();
        TapToPlayForTests.tapEnded(20);
        assertTrue(TapToPlay.allowStart(player, Trigger.BY_USER));
        TapToPlay.rebound(player);
        assertTrue("the bind inside the start", TapToPlay.armed(player));

        SystemClock.sleep(TapToPlay.BIND_GRACE_MS + 1);
        TapToPlay.rebound(player);
        assertFalse("the next video", TapToPlay.armed(player));
        assertFalse(TapToPlay.allowStart(player, Trigger.BY_SHORT_FORM_VIDEO_FULLY_VISIBLE));
    }

    @Test
    public void aRapidSwipeCannotReuseThePreviousTapOrThePlayersBindGrace() {
        Object player = new Object();
        tapAt(SystemClock.uptimeMillis());
        assertTrue(TapToPlay.allowStart(player, Trigger.BY_USER));
        TapToPlay.rebound(player);
        assertTrue("the tap's own bind", TapToPlay.armed(player));

        SystemClock.sleep(10);
        long swipe = SystemClock.uptimeMillis();
        TapClock.record(MotionEvent.ACTION_DOWN, 50, 500, swipe, 8);
        TapClock.record(MotionEvent.ACTION_MOVE, 50, 300, swipe, 8);
        assertFalse("another player cannot borrow the previous tap during a swipe",
                TapToPlay.allowStart(new Object(), Trigger.BY_AUTOPLAY));
        assertTrue("scrolling alone doesn't interrupt the current video", TapToPlay.armed(player));
        TapToPlay.rebound(player);
        assertFalse("a new bind after the swipe cannot borrow the previous start", TapToPlay.armed(player));
        TapClock.record(MotionEvent.ACTION_UP, 50, 100, swipe, 8);
        assertFalse(TapToPlay.allowStart(player, Trigger.BY_SHORT_FORM_VIDEO_FULLY_VISIBLE));
        assertFalse(TapToPlay.allowLegacyStart(player, Trigger.BY_USER_SWIPE));

        SystemClock.sleep(1);
        tapAt(SystemClock.uptimeMillis());
        assertTrue("the next video's own tap works", TapToPlay.allowStart(player, Trigger.BY_USER));
        TapToPlay.rebound(player);
        assertTrue("that tap retains its own bind grace", TapToPlay.armed(player));
    }

    @Test
    public void aControlStartAfterADragKeepsItsOwnImmediateBind() {
        for (Trigger trigger : new Trigger[] { Trigger.BY_SEEKBAR_CONTROLLER,
                Trigger.BY_MEDIA_SESSION_CONTROLS, Trigger.BY_MUSIC_PLAYER }) {
            Object player = new Object();
            assertTrue(TapToPlay.allowStart(player, trigger));
            long drag = SystemClock.uptimeMillis();
            TapClock.record(MotionEvent.ACTION_DOWN, 50, 500, drag, 8);
            TapClock.record(MotionEvent.ACTION_MOVE, 200, 500, drag, 8);
            TapClock.record(MotionEvent.ACTION_UP, 200, 500, drag, 8);
            assertTrue(TapToPlay.allowStart(player, trigger));
            TapToPlay.rebound(player);
            assertTrue(trigger.name(), TapToPlay.armed(player));
        }
    }

    /** Players are told apart by identity, so one whose equals claims another is still itself. */
    @Test
    public void playersAreToldApartByIdentity() {
        Object player = new AlwaysEqual();
        Object twin = new AlwaysEqual();
        TapToPlayForTests.tapEnded(20);
        assertTrue(TapToPlay.allowStart(player, Trigger.BY_USER));
        TapClock.forget();
        assertFalse(TapToPlay.allowStart(twin, Trigger.BY_PLAYER));
        TapToPlay.paused(twin);
        assertTrue(TapToPlay.armed(player));
    }

    /** An armed player Facebook drops is forgotten with it: the gate holds no player in memory. */
    @Test
    public void aDroppedPlayerIsForgotten() throws InterruptedException {
        TapToPlayForTests.tapEnded(20);
        WeakReference<Object> dropped = armOne();
        for (int i = 0; i < 100 && dropped.get() != null; i++) {
            System.gc();
            Thread.sleep(10);
        }
        assertNull("the gate kept the player alive", dropped.get());
        assertEquals(0, TapToPlay.armedCount());
    }

    private static WeakReference<Object> armOne() {
        Object player = new Object();
        assertTrue(TapToPlay.allowStart(player, Trigger.BY_USER));
        assertEquals(1, TapToPlay.armedCount());
        return new WeakReference<>(player);
    }

    @Test
    public void offPausedOrNotReadyEveryStartGoesAhead() {
        Settings.TAP_TO_PLAY.save(false);
        assertTrue(TapToPlay.allowStart(new Object(), Trigger.BY_SHORT_FORM_VIDEO_FULLY_VISIBLE));
        assertTrue(TapToPlay.allowLegacyStart(new Object(), Trigger.BY_AUTOPLAY));
        Settings.TAP_TO_PLAY.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertTrue(TapToPlay.allowStart(new Object(), Trigger.BY_AUTOPLAY));
        PauseForTests.resume();
        assertFalse("the control: running, the same start is held", TapToPlay.allowStart(new Object(), Trigger.BY_AUTOPLAY));
        assertEquals("nothing armed while off or paused", 0, TapToPlay.armedCount());
    }

    @Test
    public void aStartBeforeTheSettingsAreReadyGoesAhead() {
        SettingsContextRule.withoutContext(() -> {
            assertTrue(TapToPlay.allowStart(new Object(), Trigger.BY_AUTOPLAY));
            assertSame(Autoplay.ON, TapToPlay.autoplaySetting(Autoplay.ON));
        });
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            assertTrue(TapToPlay.allowStart(new Object(), Trigger.BY_AUTOPLAY));
            assertSame(Autoplay.ON, TapToPlay.autoplaySetting(Autoplay.ON));
        });
        assertFalse("the control: ready, the same start is held", TapToPlay.allowStart(new Object(), Trigger.BY_AUTOPLAY));
    }

    @Test
    public void aFailureLetsTheStartGoAheadAndTheReportSaysSo() {
        TapToPlay.failNext = new IllegalStateException("the rule failed");
        assertTrue(TapToPlay.allowStart(new Object(), Trigger.BY_AUTOPLAY));
        assertEquals(Collections.singletonList("a working 'player start' hook (it threw "
                        + IllegalStateException.class.getName() + ")"),
                HookStatus.missing(FamilyNames.TAP_TO_PLAY));
        assertFalse("only the one start went ahead", TapToPlay.allowStart(new Object(), Trigger.BY_AUTOPLAY));

        TapToPlay.failNext = new IllegalStateException("the rule failed");
        assertTrue(TapToPlay.allowLegacyStart(new Object(), Trigger.BY_AUTOPLAY));
        assertTrue(String.join("\n", HookStatus.missing(FamilyNames.TAP_TO_PLAY)),
                HookStatus.missing(FamilyNames.TAP_TO_PLAY).contains("a working 'older player start' hook (it threw "
                        + IllegalStateException.class.getName() + ")"));
    }

    @Test
    public void eachHookCountsAndBindsInTheReport() {
        TapToPlay.allowStart(new Object(), Trigger.BY_AUTOPLAY);
        TapToPlay.allowLegacyStart(new Object(), Trigger.BY_AUTOPLAY);
        TapToPlay.paused(new Object());
        TapToPlay.rebound(new Object());
        TapToPlay.autoplaySetting(Autoplay.ON);
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FamilyNames.TAP_TO_PLAY + ": invoked 5, 5 found, 0 missing"));
    }

    @Test
    public void theAutoplaySettingReadsOffWhileTheSwitchIsOn() {
        for (Autoplay chosen : Autoplay.values()) {
            assertSame(chosen.name(), Autoplay.OFF, TapToPlay.autoplaySetting(chosen));
        }
        Settings.TAP_TO_PLAY.save(false);
        for (Autoplay chosen : Autoplay.values()) {
            assertSame("off, it reads what was chosen", chosen, TapToPlay.autoplaySetting(chosen));
        }
        Settings.TAP_TO_PLAY.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertSame("paused, it reads what was chosen", Autoplay.ON, TapToPlay.autoplaySetting(Autoplay.ON));
    }

    /**
     * The feed binds a player to a video only while autoplay is allowed, and the inline button
     * needs one (#239), so with that switch on the setting reads what was chosen while the gate
     * still holds the feed's start.
     */
    @Test
    public void inlineFeedControlsKeepTheChosenAutoplaySettingAndStartsStayHeld() {
        Settings.INLINE_FEED_VIDEO_CONTROLS.save(true);
        try {
            for (Autoplay chosen : Autoplay.values()) {
                assertSame(chosen.name(), chosen, TapToPlay.autoplaySetting(chosen));
            }
            String report = String.join("\n", HookStatus.report());
            assertTrue(report, report.contains(FamilyNames.TAP_TO_PLAY + ": invoked 4, 1 found, 0 missing"));
            assertFalse("the feed's own start still waits", TapToPlay.allowStart(new Object(), Trigger.BY_AUTOPLAY));
            Settings.INLINE_FEED_VIDEO_CONTROLS.save(false);
            assertSame(Autoplay.OFF, TapToPlay.autoplaySetting(Autoplay.ON));
        } finally {
            Settings.INLINE_FEED_VIDEO_CONTROLS.resetToDefault();
        }
    }

    /**
     * The Reels controls show their play button up front while the switch is on, so a held reel
     * starts with one tap. Facebook's own exclusion stands, and off, paused, before the settings are
     * ready or on a failure, Facebook's own check decides.
     */
    @Test
    public void theReelsShowTheirPlayButtonWhileTheSwitchIsOn() {
        assertTrue(TapToPlay.showReelPlayButton(false));
        assertFalse("Facebook's own exclusion stands", TapToPlay.showReelPlayButton(true));
        Settings.TAP_TO_PLAY.save(false);
        assertFalse(TapToPlay.showReelPlayButton(false));
        Settings.TAP_TO_PLAY.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(TapToPlay.showReelPlayButton(false));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse(TapToPlay.showReelPlayButton(false)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertFalse(TapToPlay.showReelPlayButton(false)));

        TapToPlay.failNext = new IllegalStateException("the check failed");
        assertFalse(TapToPlay.showReelPlayButton(false));
        assertTrue(String.join("\n", HookStatus.missing(FamilyNames.TAP_TO_PLAY)),
                HookStatus.missing(FamilyNames.TAP_TO_PLAY).contains("a working 'Reels play button' hook (it threw "
                        + IllegalStateException.class.getName() + ")"));
        assertTrue("the control: once the failure has passed", TapToPlay.showReelPlayButton(false));
    }

    @Test
    public void debugLoggingSaysOnceThatReelsShowTheirButton() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        TapToPlay.showReelPlayButton(false);
        TapToPlay.showReelPlayButton(false);
        TapToPlay.showReelPlayButton(true);
        String report = LogBufferManager.buildExportText();
        assertEquals(report, 1, occurrences(report, "Tap to play: Reels show their play button until a tap"));
    }

    /**
     * #90: a reel start that comes with its player already partway in is counted, every one, and
     * the first twenty are logged with the trigger and the decision. A start from the beginning, or
     * a build where the position reader wasn't found, counts nothing.
     */
    @Test
    public void reelStartsThatComePartwayInAreCountedAndTheFirstLogged() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        TapToPlay.notePosition(TapToPlay.NO_POSITION, "BY_AUTOPLAY", false);
        TapToPlay.notePosition(0, "BY_AUTOPLAY", false);
        TapToPlay.notePosition(TapToPlay.PARTWAY_MS - 1, "BY_AUTOPLAY", false);
        assertFalse(String.join("\n", HookStatus.report()), String.join("\n", HookStatus.report()).contains(TapToPlay.REEL_PARTWAY));

        TapToPlay.notePosition(4_200, "BY_SHORT_FORM_VIDEO_FULLY_VISIBLE", false);
        for (int start = 1; start <= TapToPlay.PARTWAY_LOGGED + 4; start++) TapToPlay.notePosition(9_000, null, true);
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(TapToPlay.REEL_PARTWAY + " " + (TapToPlay.PARTWAY_LOGGED + 5)));
        String log = LogBufferManager.buildExportText();
        assertEquals(log, 1, occurrences(log, "Tap to play: a reel's BY_SHORT_FORM_VIDEO_FULLY_VISIBLE start came 4200 ms in, held"));
        assertEquals(log, TapToPlay.PARTWAY_LOGGED - 1, occurrences(log, "Tap to play: a reel's start came 9000 ms in, allowed"));
    }

    /** No answer, an answer that isn't an enum, or an enum with no OFF: Facebook's own answer stands. */
    @Test
    public void anAnswerItCantReadStands() {
        assertNull(TapToPlay.autoplaySetting(null));
        Object text = "ON";
        assertSame(text, TapToPlay.autoplaySetting(text));
        assertSame(Trigger.BY_USER, TapToPlay.autoplaySetting(Trigger.BY_USER));
        assertTrue(String.join("\n", HookStatus.missing(FamilyNames.TAP_TO_PLAY)),
                HookStatus.missing(FamilyNames.TAP_TO_PLAY).contains("a working 'Autoplay setting' hook (it threw "
                        + IllegalArgumentException.class.getName() + ")"));
    }

    /** With Debug logging on, one line per decision for the first 40, then one line for every 50. */
    @Test
    public void debugLoggingSaysEachDecisionThenSumsThemUp() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        TapToPlayForTests.tapEnded(250);
        Object player = new Object();
        TapToPlay.allowStart(player, Trigger.BY_USER);
        TapToPlay.allowStart(player, Trigger.BY_PLAYER);
        TapClock.forget();
        TapToPlay.allowStart(new Object(), Trigger.BY_SHORT_FORM_VIDEO_FULLY_VISIBLE);
        TapToPlay.allowLegacyStart(new Object(), Trigger.BY_AUTOPLAY);
        TapToPlay.autoplaySetting(Autoplay.WIFI_ONLY);
        TapToPlay.autoplaySetting(Autoplay.WIFI_ONLY);

        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("Tap to play: allowed BY_USER 250 ms armed no"));
        assertTrue(report, report.contains("Tap to play: allowed BY_PLAYER 250 ms armed yes"));
        assertTrue(report, report.contains("Tap to play: held BY_SHORT_FORM_VIDEO_FULLY_VISIBLE no tap armed no"));
        assertTrue(report, report.contains("Tap to play: held BY_AUTOPLAY no tap armed no (older player)"));
        assertEquals(report, 1, occurrences(report, "Tap to play: Facebook's Autoplay setting WIFI_ONLY reads OFF"));

        // 4 so far: 36 more one by one, then 120 summed up in two lines, with 20 left over.
        for (int i = 0; i < 36 + 120; i++) TapToPlay.allowStart(new Object(), Trigger.BY_AUTOPLAY);
        report = LogBufferManager.buildExportText();
        assertEquals(TapToPlay.LOGGED_ONE_BY_ONE, occurrences(report, "Tap to play: allowed ")
                + occurrences(report, "Tap to play: held "));
        assertEquals(report, 2, occurrences(report, "Tap to play: 50 more starts, 0 allowed, 50 held"));
    }

    /** Plays the reel that was waiting with a tap on its play button, starting a run (#91). */
    private static Object playAWaitingReel() {
        Object reel = new Object();
        assertFalse("the reel waits", TapToPlay.allowStart(reel, Trigger.BY_SHORT_FORM_VIDEO_FULLY_VISIBLE));
        TapToPlayForTests.tapEnded(30);
        assertTrue("its play button's tap", TapToPlay.allowStart(reel, Trigger.BY_USER));
        TapClock.forget();
        return reel;
    }

    /** #91: once a tap plays a waiting reel, the reels swiped to after it play on their own. */
    @Test
    public void withOnlyTheFirstReelWaitingTheReelsAfterAPlayedOnePlay() {
        Settings.TAP_TO_PLAY_REELS_AFTER_FIRST.save(true);
        try {
            playAWaitingReel();
            Object next = new Object();
            assertTrue("the reel swiped to next", TapToPlay.allowStart(next, Trigger.BY_SHORT_FORM_VIDEO_FULLY_VISIBLE));
            assertTrue("it plays through Facebook's own restarts", TapToPlay.armed(next));
            TapToPlay.paused(next);
            assertTrue("a paused reel doesn't stop the next one",
                    TapToPlay.allowStart(new Object(), Trigger.BY_SHORT_FORM_VIDEO_FULLY_VISIBLE));
            for (Trigger trigger : Trigger.values()) {
                if (trigger == Trigger.BY_SHORT_FORM_VIDEO_FULLY_VISIBLE || TapToPlay.CONTROLS.contains(trigger.name())) continue;
                assertFalse("only a reel coming into view rides the run: " + trigger,
                        TapToPlay.allowStart(new Object(), trigger));
                playAWaitingReel();
            }
        } finally {
            Settings.TAP_TO_PLAY_REELS_AFTER_FIRST.resetToDefault();
        }
    }

    /** A held start ends the run, so the first reel waits on the next visit to Reels. */
    @Test
    public void anythingHeldMakesTheNextReelWaitAgain() {
        Settings.TAP_TO_PLAY_REELS_AFTER_FIRST.save(true);
        try {
            Object reel = playAWaitingReel();
            assertFalse("the feed trying a video", TapToPlay.allowStart(new Object(), Trigger.BY_AUTOPLAY));
            assertFalse("ends the run", TapToPlay.allowStart(new Object(), Trigger.BY_SHORT_FORM_VIDEO_FULLY_VISIBLE));

            reel = playAWaitingReel();
            TapToPlay.paused(reel);
            assertFalse("the reel coming back from another tab waits",
                    TapToPlay.allowStart(reel, Trigger.BY_SHORT_FORM_VIDEO_ONRESUME));
            TapToPlayForTests.tapEnded(30);
            assertTrue(TapToPlay.allowStart(reel, Trigger.BY_USER));
            TapClock.forget();
            assertTrue("a tap on the reel that came back starts a new run",
                    TapToPlay.allowStart(new Object(), Trigger.BY_SHORT_FORM_VIDEO_FULLY_VISIBLE));

            reel = new Object();
            assertFalse(TapToPlay.allowStart(reel, Trigger.BY_SHORT_FORM_VIDEO_ONRESUME));
            Object swipedTo = new Object();
            assertFalse("a reel swiped to from one that came back waits",
                    TapToPlay.allowStart(swipedTo, Trigger.BY_SHORT_FORM_VIDEO_FULLY_VISIBLE));
            TapToPlayForTests.tapEnded(30);
            assertTrue(TapToPlay.allowStart(swipedTo, Trigger.BY_USER));
            TapClock.forget();
            assertTrue("and a tap on it starts the run",
                    TapToPlay.allowStart(new Object(), Trigger.BY_SHORT_FORM_VIDEO_FULLY_VISIBLE));
        } finally {
            Settings.TAP_TO_PLAY_REELS_AFTER_FIRST.resetToDefault();
        }
    }

    /** Only a tap that plays a reel Facebook held starts a run: not a feed video's, not a link's. */
    @Test
    public void onlyATapOnAWaitingReelStartsARun() {
        Settings.TAP_TO_PLAY_REELS_AFTER_FIRST.save(true);
        try {
            Object video = new Object();
            assertFalse(TapToPlay.allowStart(video, Trigger.BY_AUTOPLAY));
            TapToPlayForTests.tapEnded(30);
            assertTrue("a feed video's tap", TapToPlay.allowStart(video, Trigger.BY_USER));
            TapClock.forget();
            assertFalse("starts no run", TapToPlay.allowStart(new Object(), Trigger.BY_SHORT_FORM_VIDEO_FULLY_VISIBLE));

            Object reel = new Object();
            assertFalse(TapToPlay.allowStart(reel, Trigger.BY_SHORT_FORM_VIDEO_FULLY_VISIBLE));
            TapToPlay.linkOpened(SystemClock.uptimeMillis());
            assertTrue("a link's start", TapToPlay.allowStart(reel, Trigger.BY_USER));
            assertFalse("starts no run either", TapToPlay.allowStart(new Object(), Trigger.BY_SHORT_FORM_VIDEO_FULLY_VISIBLE));
        } finally {
            Settings.TAP_TO_PLAY_REELS_AFTER_FIRST.resetToDefault();
        }
    }

    /** A run doesn't outlive Tap to play: a start that goes ahead with the switch off ends it. */
    @Test
    public void tapToPlayOffInBetweenEndsTheRun() {
        Settings.TAP_TO_PLAY_REELS_AFTER_FIRST.save(true);
        try {
            playAWaitingReel();
            Settings.TAP_TO_PLAY.save(false);
            assertTrue("off, a video starts as Facebook starts it", TapToPlay.allowStart(new Object(), Trigger.BY_AUTOPLAY));
            Settings.TAP_TO_PLAY.save(true);
            assertFalse("back on, the reel landed on waits",
                    TapToPlay.allowStart(new Object(), Trigger.BY_SHORT_FORM_VIDEO_FULLY_VISIBLE));
        } finally {
            Settings.TAP_TO_PLAY_REELS_AFTER_FIRST.resetToDefault();
        }
    }

    /** The control: off, its default, every reel waits after a played one, as before #91. */
    @Test
    public void withTheSwitchOffEveryReelWaits() {
        assertFalse(Settings.TAP_TO_PLAY_REELS_AFTER_FIRST.get());
        playAWaitingReel();
        assertFalse(TapToPlay.allowStart(new Object(), Trigger.BY_SHORT_FORM_VIDEO_FULLY_VISIBLE));
    }

    /**
     * #91 on 581, seen on a phone: the Reels tab starts each reel it lands on with BY_AUTOPLAY. The
     * tap on the tab doesn't let that through, as it does the Story a tap opens, and the reels after
     * a played one ride the run.
     */
    @Test
    public void aReelsAutoplayStartCountsAsTheReelComingIntoView() {
        Settings.TAP_TO_PLAY_REELS_AFTER_FIRST.save(true);
        try {
            TapToPlayForTests.tapEnded(100);
            assertTrue("a Story a tap opens still plays",
                    TapToPlay.decide(new Object(), Trigger.BY_AUTOPLAY.name(), false, SystemClock.uptimeMillis(), ""));
            Object first = new Object();
            assertFalse("the reel a tap on the Reels tab lands on waits", autoplayedReel(first));
            TapClock.forget();
            TapToPlayForTests.tapEnded(30);
            assertTrue("its play button's tap", TapToPlay.allowStart(first, Trigger.BY_USER));
            TapClock.forget();
            Object next = new Object();
            assertTrue("the reel swiped to next", autoplayedReel(next));
            assertTrue("it plays through Facebook's own restarts", TapToPlay.armed(next));
            assertFalse("a feed video's BY_AUTOPLAY is still held",
                    TapToPlay.decide(new Object(), Trigger.BY_AUTOPLAY.name(), false, SystemClock.uptimeMillis(), ""));
            assertFalse("and ends the run", autoplayedReel(new Object()));
        } finally {
            Settings.TAP_TO_PLAY_REELS_AFTER_FIRST.resetToDefault();
        }
    }

    /**
     * Seen on a phone: back on the Reels tab from Home, 581 starts the reel with BY_AUTOPLAY 45 ms
     * after the tab's tap. A swipe forgets the last tap, so a reel that starts right after one came
     * from that tap, not from swiping through Reels: it waits, and the run is over.
     */
    @Test
    public void aReelStartedRightAfterATapDoesntRideTheRun() {
        Settings.TAP_TO_PLAY_REELS_AFTER_FIRST.save(true);
        try {
            playAWaitingReel();
            TapToPlayForTests.tapEnded(45);
            assertFalse("the reel the Reels tab comes back to", autoplayedReel(new Object()));
            TapClock.forget();
            assertFalse("and the run is over", autoplayedReel(new Object()));

            playAWaitingReel();
            TapToPlayForTests.tapEnded(45);
            assertFalse("a reel coming into view right after a tap",
                    TapToPlay.allowStart(new Object(), Trigger.BY_SHORT_FORM_VIDEO_FULLY_VISIBLE));

            playAWaitingReel();
            TapToPlayForTests.tapEnded(300);
            long at = SystemClock.uptimeMillis() - 200;
            TapClock.record(MotionEvent.ACTION_DOWN, 500, 1500, at, 8);
            TapClock.record(MotionEvent.ACTION_MOVE, 500, 900, at + 60, 8);
            TapClock.record(MotionEvent.ACTION_UP, 500, 500, at + 120, 8);
            assertTrue("a swipe after a tap forgets it, and the reel swiped to plays", autoplayedReel(new Object()));
        } finally {
            Settings.TAP_TO_PLAY_REELS_AFTER_FIRST.resetToDefault();
        }
    }

    /** A reel start on [player] at [now]: BY_AUTOPLAY on a reel's player, as 582's reel screens send. */
    private static boolean reelAt(Object player, long now) {
        return TapToPlay.decide(player, Trigger.BY_AUTOPLAY.name(), true, now, "");
    }

    /** A fresh screen, brought to the front. */
    private static Activity frontScreen() {
        Activity screen = Robolectric.buildActivity(Activity.class).setup().get();
        TapToPlay.activityResumed(screen);
        return screen;
    }

    /**
     * #106: a tile in Home's Reels row opens its reel in a screen of its own. The tap on the tile is
     * the tap for that reel, so it plays at once, through Facebook's own restarts, whether the
     * screen starts it with BY_AUTOPLAY or as a reel coming into view. One reel takes it.
     */
    @Test
    public void aTileTapPlaysTheReelItsScreenOpens() {
        long now = 100_000;
        tapAt(now - 400);
        Activity viewer = Robolectric.buildActivity(Activity.class).setup().get();
        TapToPlay.screenCreated(viewer, now - 100);
        TapToPlay.activityResumed(viewer);
        Object reel = new Object();
        assertTrue("the reel the tile opened", reelAt(reel, now + 1_500));
        assertTrue("it plays through Facebook's own restarts", TapToPlay.armed(reel));
        assertFalse("another reel on the same screen with no tap of its own", reelAt(new Object(), now + 1_600));

        TapToPlayForTests.forget();
        tapAt(now + 20_000);
        TapToPlay.screenCreated(viewer, now + 20_300);
        TapToPlay.activityResumed(viewer);
        assertTrue("a reel coming into view on a tapped screen",
                decide(new Object(), Trigger.BY_SHORT_FORM_VIDEO_FULLY_VISIBLE, now + 20_400));
    }

    /**
     * The tap on the Reels tab opens no screen, so the reel it lands on still waits, and so do the
     * tiles in the feed and anything that isn't a reel. A screen made long after a tap, one that's
     * no longer in front, a reel past ten seconds and a swipe in between all leave the reel waiting.
     */
    @Test
    public void theReelsTabTheFeedsTilesAndLateReelsStillWait() {
        long now = 100_000;
        Activity home = frontScreen();
        tapAt(now - 30);
        assertFalse("the reel the Reels tab lands on", reelAt(new Object(), now));
        assertFalse("a tile in the feed", reelAt(new Object(), now + 50));

        Activity viewer = Robolectric.buildActivity(Activity.class).setup().get();
        TapToPlay.screenCreated(viewer, now + 100);
        TapToPlay.activityResumed(viewer);
        assertFalse("a Reels row tile coming into view on the tapped screen",
                decide(new Object(), Trigger.BY_FB_SHORTS_IN_FEED_UNIT_VISIBLE, now + 2_900));
        assertFalse("a feed video on the tapped screen isn't a reel",
                TapToPlay.decide(new Object(), Trigger.BY_AUTOPLAY.name(), false, now + 3_000, ""));
        TapToPlay.activityResumed(home);
        assertFalse("back on Home, a tile in the feed", reelAt(new Object(), now + 3_100));
        TapToPlay.activityResumed(viewer);
        assertTrue("the tapped screen's reel still has it", reelAt(new Object(), now + 3_200));

        tapAt(now + 20_000);
        TapToPlay.screenCreated(viewer, now + 20_000 + TapToPlay.SCREEN_AFTER_TAP_MS + 1);
        assertFalse("a screen made over two seconds after the tap", reelAt(new Object(), now + 22_100));

        tapAt(now + 30_000);
        TapToPlay.screenCreated(viewer, now + 30_100);
        assertFalse("a reel past ten seconds", reelAt(new Object(), now + 30_100 + TapToPlay.TAPPED_SCREEN_WINDOW_MS + 1));
        assertFalse("and it's gone after that", reelAt(new Object(), now + 30_200));

        tapAt(now + 40_000);
        TapToPlay.screenCreated(viewer, now + 40_100);
        TapToPlay.nonTapGesture();
        assertFalse("a reel after a swipe", reelAt(new Object(), now + 40_500));
    }

    /** A start on [player] at [now] that isn't a reel's: BY_AUTOPLAY on a plain video's player. */
    private static boolean plainVideoAt(Object player, long now) {
        return TapToPlay.decide(player, Trigger.BY_AUTOPLAY.name(), false, now, "");
    }

    /**
     * #239: a plain video tapped in the feed opens Facebook's player, which starts it once the viewer
     * has loaded, past the tap window, with BY_AUTOPLAY on a player that isn't a reel's. That start
     * plays, through Facebook's own restarts, whether or not a screen of its own opened, and one
     * video takes it.
     */
    @Test
    public void aFeedVideoTapPlaysThePlainVideoItsPlayerOpens() {
        long now = 100_000;
        tapAt(now - 300);
        TapToPlay.feedVideoOpened(now - 200);
        Object video = new Object();
        assertTrue("the plain video the tap opened", plainVideoAt(video, now + 1_500));
        assertTrue("it plays through Facebook's own restarts", TapToPlay.armed(video));
        assertFalse("another video after it with no tap of its own", plainVideoAt(new Object(), now + 1_600));

        tapAt(now + 20_000);
        TapToPlay.feedVideoOpened(now + 20_100);
        Activity viewer = Robolectric.buildActivity(Activity.class).setup().get();
        TapToPlay.screenCreated(viewer, now + 20_200);
        TapToPlay.activityResumed(viewer);
        assertTrue("a feed reel the tap opened in a screen of its own", reelAt(new Object(), now + 21_500));
        assertFalse("which leaves nothing for a second video", plainVideoAt(new Object(), now + 21_600));
    }

    /**
     * A player opening with no tap, or over two seconds after one, isn't a feed video someone tapped,
     * and a start past ten seconds, a feed coming back into view and a swipe in between all leave the
     * video waiting.
     */
    @Test
    public void aFeedVideoPlayerWithNoTapOrALateStartStillWaits() {
        long now = 100_000;
        TapToPlay.feedVideoOpened(now);
        assertFalse("a player opening with no tap", plainVideoAt(new Object(), now + 1_500));

        tapAt(now + 10_000);
        TapToPlay.feedVideoOpened(now + 10_000 + TapToPlay.SCREEN_AFTER_TAP_MS + 1);
        assertFalse("a player opening over two seconds after the tap", plainVideoAt(new Object(), now + 13_000));

        tapAt(now + 20_000);
        TapToPlay.feedVideoOpened(now + 20_100);
        assertFalse("a start past ten seconds",
                plainVideoAt(new Object(), now + 20_100 + TapToPlay.TAPPED_SCREEN_WINDOW_MS + 1));
        assertFalse("and it's gone after that", plainVideoAt(new Object(), now + 30_300));

        tapAt(now + 40_000);
        TapToPlay.feedVideoOpened(now + 40_100);
        assertFalse("the feed coming back isn't the video", decide(new Object(), Trigger.BY_FRAGMENT_RESUME, now + 41_500));
        assertTrue("the video's own start still has it", plainVideoAt(new Object(), now + 41_600));

        tapAt(now + 50_000);
        TapToPlay.feedVideoOpened(now + 50_100);
        TapToPlay.nonTapGesture();
        assertFalse("a start after a swipe", plainVideoAt(new Object(), now + 51_500));
    }

    /**
     * Clean up Reels' hooks tell it a feed video opened its player whatever that patch's own switch
     * says, and only for a video opened from a feed.
     */
    @Test
    public void cleanUpReelsTellsItAFeedVideoOpened() {
        TapToPlayForTests.tapEnded(300);
        FeedVideoAlone.reelsViewerOpening("user_timeline::feed_story");
        assertTrue("a page's video post opened from its timeline",
                plainVideoAt(new Object(), SystemClock.uptimeMillis() + 1_500));

        TapToPlayForTests.tapEnded(300);
        FeedVideoAlone.reelsViewerOpening("fb_shorts_tab::reels_tab");
        assertFalse("the reels viewer opened from the Reels tab",
                plainVideoAt(new Object(), SystemClock.uptimeMillis() + 1_500));

        TapToPlayForTests.tapEnded(300);
        FeedVideoAlone.feedVideoTapped();
        assertTrue("a video tapped in a feed, through the Warion step",
                plainVideoAt(new Object(), SystemClock.uptimeMillis() + 1_500));

        Settings.TAP_TO_PLAY.save(false);
        TapToPlayForTests.tapEnded(300);
        FeedVideoAlone.feedVideoTapped();
        Settings.TAP_TO_PLAY.save(true);
        assertFalse("nothing recorded while the switch was off",
                plainVideoAt(new Object(), SystemClock.uptimeMillis() + 1_500));
    }

    /** Only a fresh screen while the switch is on counts, through the activity watcher. */
    @Test
    public void onlyAFreshScreenWhileTheSwitchIsOnCountsAsTapped() {
        TapToPlayForTests.tapEnded(200);
        Activity brought = Robolectric.buildActivity(Activity.class).setup().get();
        TapToPlay.activityCreated(brought, new Bundle());
        TapToPlay.activityResumed(brought);
        assertFalse("a screen brought back", autoplayedReel(new Object()));

        Settings.TAP_TO_PLAY.save(false);
        Activity whileOff = Robolectric.buildActivity(Activity.class).setup().get();
        TapToPlay.activityCreated(whileOff, null);
        Settings.TAP_TO_PLAY.save(true);
        TapToPlay.activityResumed(whileOff);
        assertFalse("a screen made while the switch was off", autoplayedReel(new Object()));

        TapToPlayForTests.tapEnded(200);
        Activity fresh = Robolectric.buildActivity(Activity.class).setup().get();
        TapToPlay.activityCreated(fresh, null);
        TapToPlay.activityResumed(fresh);
        assertTrue("a fresh screen a tap opened", autoplayedReel(new Object()));
    }

    /**
     * #91: with Only the first reel waits on, the reel a tile opens is the first reel, so it waits
     * for its own tap, whether the tile's screen or the feed's player rule would have played it.
     * Once that tap plays it, the reels swiped to after it play.
     */
    @Test
    public void withOnlyTheFirstReelWaitingATilesReelWaitsThenStartsTheRun() {
        Settings.TAP_TO_PLAY_REELS_AFTER_FIRST.save(true);
        try {
            long now = 100_000;
            tapAt(now - 300);
            Activity viewer = Robolectric.buildActivity(Activity.class).setup().get();
            TapToPlay.screenCreated(viewer, now - 100);
            TapToPlay.activityResumed(viewer);
            Object first = new Object();
            assertFalse("the reel the tile opened", reelAt(first, now + 800));
            assertFalse("Facebook's restart of it", reelAt(first, now + 900));
            tapAt(now + 2_000);
            assertTrue("a tap on the waiting first reel", decide(first, Trigger.BY_USER, now + 2_050));
            TapToPlay.nonTapGesture();
            TapClock.forget();
            assertTrue("the reel swiped to next", decide(new Object(), Trigger.BY_SHORT_FORM_VIDEO_FULLY_VISIBLE, now + 3_000));

            TapToPlayForTests.forget();
            tapAt(now + 20_000);
            TapToPlay.feedVideoOpened(now + 20_100);
            assertFalse("a reel a tap opened through the feed's player", reelAt(new Object(), now + 21_500));
            assertFalse("which leaves nothing for a plain video after it", plainVideoAt(new Object(), now + 21_600));
        } finally {
            Settings.TAP_TO_PLAY_REELS_AFTER_FIRST.resetToDefault();
        }
    }

    /** The control: with Only the first reel waits off, every reel's BY_AUTOPLAY start waits. */
    @Test
    public void withTheSwitchOffEveryAutoplayedReelWaits() {
        Object reel = new Object();
        TapToPlayForTests.tapEnded(30);
        assertFalse("right after a tap", autoplayedReel(reel));
        assertTrue(TapToPlay.allowStart(reel, Trigger.BY_USER));
        TapClock.forget();
        assertFalse("after a played one", autoplayedReel(new Object()));
    }

    /** Unpatched, or where the patch couldn't fill the reel check, BY_AUTOPLAY reads as before. */
    @Test
    public void anUnfilledReelCheckLeavesAutoplayAsItWas() {
        assertNull(TapToPlay.playerParams(new Object()));
        TapToPlayForTests.tapEnded(30);
        assertTrue("a start right after a tap", TapToPlay.allowStart(new Object(), Trigger.BY_AUTOPLAY));
    }

    @Test
    public void debugLoggingSaysWhenAHeldStartWasAReels() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        Settings.TAP_TO_PLAY_REELS_AFTER_FIRST.save(true);
        try {
            Object first = new Object();
            autoplayedReel(first);
            TapToPlayForTests.tapEnded(30);
            TapToPlay.allowStart(first, Trigger.BY_USER);
            TapClock.forget();
            autoplayedReel(new Object());
            String report = LogBufferManager.buildExportText();
            assertTrue(report, report.contains("Tap to play: held BY_AUTOPLAY no tap armed no (a reel)"));
            assertTrue(report, report.contains("Tap to play: allowed BY_AUTOPLAY no tap armed no (a reel after one you played)"));
        } finally {
            Settings.TAP_TO_PLAY_REELS_AFTER_FIRST.resetToDefault();
        }
    }

    @Test
    public void debugLoggingSaysWhenAReelPlaysForTheRun() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        Settings.TAP_TO_PLAY_REELS_AFTER_FIRST.save(true);
        try {
            playAWaitingReel();
            TapToPlay.allowStart(new Object(), Trigger.BY_SHORT_FORM_VIDEO_FULLY_VISIBLE);
            String report = LogBufferManager.buildExportText();
            assertTrue(report, report.contains(
                    "Tap to play: allowed BY_SHORT_FORM_VIDEO_FULLY_VISIBLE no tap armed no (a reel after one you played)"));
        } finally {
            Settings.TAP_TO_PLAY_REELS_AFTER_FIRST.resetToDefault();
        }
    }

    @Test
    public void withoutDebugLoggingNothingIsLogged() {
        LogBufferManager.clearLogBuffer();
        TapToPlay.allowStart(new Object(), Trigger.BY_AUTOPLAY);
        TapToPlay.autoplaySetting(Autoplay.ON);
        String report = LogBufferManager.buildExportText();
        assertFalse(report, report.contains("Tap to play: held"));
        assertFalse(report, report.contains("Tap to play: Facebook's Autoplay setting"));
    }

    /** Answers the story viewer's PlayerOrigin for [story] and a feed's for every other player. */
    private static void storyPlayer(Object story) {
        TapToPlay.origins = player -> player == story ? "fb_stories_video_view::fb_stories_tray" : "newsfeed";
    }

    /** #261: the tap that opened a story doesn't play it. A touch on the story itself does. */
    @Test
    public void aVideoStoryWaitsForATapOfItsOwn() {
        Object story = new Object();
        storyPlayer(story);
        Settings.TAP_TO_PLAY_STORIES.save(true);
        try {
            TapToPlayForTests.tapEnded(100);
            assertFalse("the tap that opened the story played it", TapToPlay.allowStart(story, Trigger.BY_AUTOPLAY));
            assertFalse("the tap that opened it played it on the second ask", TapToPlay.allowStart(story, Trigger.BY_USER));
            SystemClock.sleep(2_000);
            TapToPlayForTests.tapEnded(10);
            assertTrue("a touch on the story didn't play it", TapToPlay.allowStart(story, Trigger.BY_AUTOPLAY));
            assertTrue(TapToPlay.armed(story));
            TapClock.forget();
            assertTrue("the story's own restart after you let go", TapToPlay.allowStart(story, Trigger.BY_AUTOPLAY));
            TapToPlayForTests.tapEnded(10);
            assertTrue("a feed video stopped following Tap to play", TapToPlay.allowStart(new Object(), Trigger.BY_USER));
        } finally {
            Settings.TAP_TO_PLAY_STORIES.resetToDefault();
        }
    }

    /** Answers a search results origin for [search] and a feed's for every other player. */
    private static void searchPlayer(Object search) {
        TapToPlay.origins = player -> player == search ? "search_results_page" : "newsfeed";
    }

    /** #363: a video in search results waits for a tap, with Tap to play off, and a feed video doesn't. */
    @Test
    public void searchVideosWaitWithTapToPlayOff() {
        Object search = new Object();
        searchPlayer(search);
        Settings.TAP_TO_PLAY.save(false);
        assertFalse("the switch doesn't start off", Settings.TAP_TO_PLAY_SEARCH.defaultValue);
        assertTrue("a search video waited with the switch off", TapToPlay.allowStart(search, Trigger.BY_AUTOPLAY));
        Settings.TAP_TO_PLAY_SEARCH.save(true);
        try {
            assertFalse("a search video played by itself", TapToPlay.allowStart(search, Trigger.BY_AUTOPLAY));
            assertTrue("a feed video waited", TapToPlay.allowStart(new Object(), Trigger.BY_AUTOPLAY));
            assertTrue("a media control was held", TapToPlay.allowStart(search, Trigger.BY_MEDIA_SESSION_CONTROLS));
            Object next = new Object();
            searchPlayer(next);
            assertFalse(TapToPlay.allowStart(next, Trigger.BY_AUTOPLAY));
            TapToPlayForTests.tapEnded(5);
            assertTrue("a tap on the search video didn't play it", TapToPlay.allowStart(next, Trigger.BY_USER));
            assertTrue(TapToPlay.armed(next));
        } finally {
            Settings.TAP_TO_PLAY_SEARCH.resetToDefault();
        }
    }

    @Test
    public void onlySearchOriginsCountAsSearch() {
        assertTrue(TapToPlay.isSearchOrigin("search_results_page"));
        assertTrue(TapToPlay.isSearchOrigin("SEARCH_RESULTS_BLOKS::feed"));
        assertFalse("a reel opened from search follows reel rules", TapToPlay.isSearchOrigin("fb_shorts_viewer::search_results_page"));
        assertFalse(TapToPlay.isSearchOrigin("newsfeed"));
        assertFalse(TapToPlay.isSearchOrigin(null));
    }

    @Test
    public void videoStoriesWaitWithTapToPlayOff() {
        Object story = new Object();
        storyPlayer(story);
        Settings.TAP_TO_PLAY.save(false);
        Settings.TAP_TO_PLAY_STORIES.save(true);
        try {
            assertTrue("with Tap to play off a feed video waited", TapToPlay.allowStart(new Object(), Trigger.BY_AUTOPLAY));
            assertFalse("a story played by itself", TapToPlay.allowStart(story, Trigger.BY_AUTOPLAY));
            assertTrue("a media control was held", TapToPlay.allowStart(story, Trigger.BY_MEDIA_SESSION_CONTROLS));
            Object next = new Object();
            storyPlayer(next);
            assertFalse(TapToPlay.allowStart(next, Trigger.BY_AUTOPLAY));
            SystemClock.sleep(300);
            TapToPlayForTests.tapEnded(5);
            assertTrue("a touch on the story didn't play it", TapToPlay.allowStart(next, Trigger.BY_AUTOPLAY));
        } finally {
            Settings.TAP_TO_PLAY_STORIES.resetToDefault();
        }
    }

    /** The story viewer binds the next story to the same player, and the tap that moved there doesn't count. */
    @Test
    public void theNextStoryOnTheSamePlayerWaitsAgain() {
        Object story = new Object();
        storyPlayer(story);
        Settings.TAP_TO_PLAY_STORIES.save(true);
        try {
            assertFalse(TapToPlay.allowStart(story, Trigger.BY_AUTOPLAY));
            SystemClock.sleep(TapToPlay.BIND_GRACE_MS + 500);
            TapToPlayForTests.tapEnded(20);
            TapToPlay.rebound(story);
            assertFalse("the tap that moved to the next story played it", TapToPlay.allowStart(story, Trigger.BY_AUTOPLAY));

            SystemClock.sleep(500);
            TapToPlayForTests.tapEnded(5);
            assertTrue(TapToPlay.allowStart(story, Trigger.BY_AUTOPLAY));
            SystemClock.sleep(TapToPlay.BIND_GRACE_MS + 500);
            TapToPlayForTests.tapEnded(20);
            TapToPlay.rebound(story);
            assertFalse("the story after one you played started by itself", TapToPlay.allowStart(story, Trigger.BY_AUTOPLAY));
        } finally {
            Settings.TAP_TO_PLAY_STORIES.resetToDefault();
        }
    }

    @Test
    public void offStoriesFollowTapToPlay() {
        Object story = new Object();
        storyPlayer(story);
        assertFalse(Settings.TAP_TO_PLAY_STORIES.defaultValue);
        TapToPlayForTests.tapEnded(100);
        assertTrue("the tap that opened a story stopped playing it", TapToPlay.allowStart(story, Trigger.BY_USER));
        Object other = new Object();
        storyPlayer(other);
        Settings.TAP_TO_PLAY.save(false);
        TapClock.forget();
        assertTrue("with both switches off a story waited", TapToPlay.allowStart(other, Trigger.BY_AUTOPLAY));
    }

    @Test
    public void onlyTheStoryViewersOriginsAreStories() {
        assertTrue(TapToPlay.isStoryOrigin("fb_stories_video_view"));
        assertTrue(TapToPlay.isStoryOrigin("FB_STORIES_VIDEO_VIEW::fb_stories_tray"));
        assertTrue("a story opened from the feed", TapToPlay.isStoryOrigin("newsfeed::fb_stories_video_view"));
        assertFalse("a reel opened from a story", TapToPlay.isStoryOrigin("newsfeed::fb_shorts_viewer"));
        assertFalse(TapToPlay.isStoryOrigin("fb_shorts_viewer::fb_stories_video_view"));
        assertFalse(TapToPlay.isStoryOrigin("newsfeed"));
        assertFalse(TapToPlay.isStoryOrigin(""));
        assertFalse(TapToPlay.isStoryOrigin(null));
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) count++;
        return count;
    }

    private static final class AlwaysEqual {
        @Override
        public boolean equals(Object other) {
            return other instanceof AlwaysEqual;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }
}
