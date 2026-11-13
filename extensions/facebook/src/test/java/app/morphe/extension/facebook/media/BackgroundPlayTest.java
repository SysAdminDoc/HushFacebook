/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.facebook.media.BackgroundPlayForTests.Player;
import app.morphe.extension.facebook.media.TapToPlayForTests.Trigger;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The rule Keep playing in the background holds its handler to: it takes on a video only when a tap
 * started it, a pause Facebook recorded found it playing, that pause came just before Facebook asked
 * and didn't follow a tap, and the screen wasn't closing. Then, and only then, Facebook's own checks
 * are skipped, the notification gets a title and its server flag reads on until the return. Facebook's
 * own handlers keep their answers. Off, paused, before the settings are ready, or when it throws,
 * the extension's handler takes nothing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class BackgroundPlayTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final Object handler = new Object();

    @Before
    public void start() {
        SystemClock.sleep(60_000);
        BackgroundPlayForTests.forget();
        BackgroundPlay.useHandler(handler);
        HookStatus.clear();
        FeedFilterCounters.clear();
        Settings.KEEP_PLAYING_IN_BACKGROUND.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.KEEP_PLAYING_IN_BACKGROUND.resetToDefault();
        BackgroundPlayForTests.forget();
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    /** A tap, then the player's start with [trigger], [played] ms of playing, and Facebook's pause. */
    private Player playThenLeave(Trigger trigger, long played) {
        Player player = new Player();
        TapToPlayForTests.tapEnded(80);
        BackgroundPlay.started(player, trigger);
        SystemClock.sleep(played);
        BackgroundPlay.pausing(player, Trigger.BY_PLAYER);
        return player;
    }

    private boolean asked(Player player) {
        return BackgroundPlay.deciding(handler, player.video, Trigger.BY_PLAYER);
    }

    private static List<String> kinds() {
        List<String> lines = new ArrayList<>();
        for (String line : FeedFilterCounters.report()) {
            if (line.contains(BackgroundPlay.ROUTE)) lines.add(line);
        }
        return lines;
    }

    @Test
    public void theSwitchStartsOff() {
        Settings.KEEP_PLAYING_IN_BACKGROUND.resetToDefault();
        assertFalse("keeping a player going out of sight is a choice", Settings.KEEP_PLAYING_IN_BACKGROUND.get());
    }

    @Test
    public void aVideoYouStartedIsCarriedOnThroughTheReturn() {
        Player player = playThenLeave(Trigger.BY_USER, 3000);
        assertFalse("the handler stopped a video you started", asked(player));
        assertTrue(BackgroundPlay.skipCheck());
        assertEquals("Facebook video", BackgroundPlay.title(null));
        assertEquals("Facebook video", BackgroundPlay.title(""));
        assertEquals("a video with a title keeps it", "Evening news", BackgroundPlay.title("Evening news"));
        assertEquals("", BackgroundPlay.subtitle(null));
        assertEquals("A Page", BackgroundPlay.subtitle("A Page"));
        assertTrue("the notification's flag", BackgroundPlay.notificationAllowed());
        assertTrue("Facebook's background player", BackgroundPlay.allowsStart(BackgroundPlay.BACKGROUND_TRIGGER));

        BackgroundPlay.answered(true);
        assertFalse("the checks after the answer", BackgroundPlay.skipCheck());
        assertTrue("the notification until the return", BackgroundPlay.notificationAllowed());
        assertTrue("the notification's play", BackgroundPlay.allowsStart(BackgroundPlay.BACKGROUND_TRIGGER));
        assertFalse("another trigger", BackgroundPlay.allowsStart("BY_AUTOPLAY"));

        // Facebook's own handler in its own manager doesn't start a second player for it.
        assertTrue(BackgroundPlay.deciding(new Object(), player.video, Trigger.BY_PLAYER));

        BackgroundPlay.returning(handler);
        assertTrue("the flag while the return hides the notification", BackgroundPlay.notificationAllowed());
        BackgroundPlay.returned();
        assertFalse(BackgroundPlay.notificationAllowed());
        assertFalse(BackgroundPlay.allowsStart(BackgroundPlay.BACKGROUND_TRIGGER));
        assertTrue(kinds().toString(), kinds().toString().contains(BackgroundPlay.CONTINUED));
    }

    @Test
    public void aTappedStoryStartCountsAndAnAutoplayOneDoesNot() {
        assertFalse(asked(playThenLeave(Trigger.BY_AUTOPLAY, 3000)));
        BackgroundPlay.answered(false);

        Player played = new Player();
        TapToPlayForTests.tapEnded(5000);
        BackgroundPlay.started(played, Trigger.BY_AUTOPLAY);
        SystemClock.sleep(3000);
        BackgroundPlay.pausing(played, Trigger.BY_PLAYER);
        assertTrue("autoplay with no tap", asked(played));
        assertFalse(BackgroundPlay.skipCheck());

        Player shown = new Player();
        TapToPlayForTests.tapEnded(80);
        BackgroundPlay.started(shown, Trigger.BY_SHORT_FORM_VIDEO_FULLY_VISIBLE);
        SystemClock.sleep(3000);
        BackgroundPlay.pausing(shown, Trigger.BY_PLAYER);
        assertTrue("a reel that came into view", asked(shown));
        assertTrue(kinds().toString(), kinds().toString().contains(BackgroundPlay.NOT_YOURS));
    }

    @Test
    public void facebookStartingItAgainOnItsOwnAfterAPauseEndsIt() {
        Player player = playThenLeave(Trigger.BY_USER, 3000);
        BackgroundPlay.started(player, Trigger.BY_AUTOPLAY);
        SystemClock.sleep(3000);
        BackgroundPlay.pausing(player, Trigger.BY_PLAYER);
        assertTrue(asked(player));

        // A restart while it plays, such as a seek, keeps it yours.
        Player seeking = new Player();
        TapToPlayForTests.tapEnded(80);
        BackgroundPlay.started(seeking, Trigger.BY_USER);
        SystemClock.sleep(3000);
        BackgroundPlay.started(seeking, Trigger.BY_SEEKBAR_CONTROLLER);
        SystemClock.sleep(3000);
        BackgroundPlay.pausing(seeking, Trigger.BY_PLAYER);
        assertFalse(asked(seeking));
    }

    @Test
    public void aVideoYouPausedOrPausedWithATapStaysPaused() {
        // Your own pause found it playing, and the leave's pause found it paused.
        Player paused = new Player();
        TapToPlayForTests.tapEnded(80);
        BackgroundPlay.started(paused, Trigger.BY_USER);
        SystemClock.sleep(3000);
        TapToPlayForTests.tapEnded(40);
        BackgroundPlay.pausing(paused, Trigger.BY_USER);
        paused.playing = false;
        SystemClock.sleep(600);
        BackgroundPlay.pausing(paused, Trigger.BY_PLAYER);
        assertTrue("a video you paused before leaving", asked(paused));
        assertTrue(kinds().toString(), kinds().toString().contains(BackgroundPlay.TAPPED));

        // The same, but the leave's pause is the one Facebook recorded with your trigger.
        Player tapped = new Player();
        TapToPlayForTests.tapEnded(80);
        BackgroundPlay.started(tapped, Trigger.BY_USER);
        SystemClock.sleep(3000);
        TapToPlayForTests.tapEnded(40);
        BackgroundPlay.pausing(tapped, Trigger.BY_USER);
        assertTrue(BackgroundPlay.deciding(handler, tapped.video, Trigger.BY_USER));
    }

    @Test
    public void aPauseLongBeforeFacebookAskedOrForAnotherVideoIsNotTheLeave() {
        Player early = playThenLeave(Trigger.BY_USER, 3000);
        SystemClock.sleep(BackgroundPlay.LEAVE_WINDOW_MS + 1);
        assertTrue(asked(early));
        assertTrue(kinds().toString(), kinds().toString().contains(BackgroundPlay.TOO_EARLY));

        Player other = playThenLeave(Trigger.BY_USER, 3000);
        assertTrue("another video's record", BackgroundPlay.deciding(handler, "2002", Trigger.BY_PLAYER));
        assertTrue("another trigger's record", BackgroundPlay.deciding(handler, other.video, Trigger.BY_AUTOPLAY));
        assertTrue("no video id", BackgroundPlay.deciding(handler, null, Trigger.BY_PLAYER));
        assertFalse(asked(other));
    }

    @Test
    public void aClosingScreenIsNotALeave() {
        Player player = playThenLeave(Trigger.BY_USER, 3000);
        BackgroundPlay.closing = true;
        assertTrue(asked(player));
        assertTrue(kinds().toString(), kinds().toString().contains(BackgroundPlay.CLOSING));
    }

    @Test
    public void facebooksOwnHandlersKeepTheirAnswers() {
        Player player = playThenLeave(Trigger.BY_USER, 3000);
        assertFalse(BackgroundPlay.deciding(new Object(), player.video, Trigger.BY_PLAYER));
        assertFalse("Facebook's checks", BackgroundPlay.skipCheck());
        assertNull("Facebook's title", BackgroundPlay.title(null));
        assertFalse(BackgroundPlay.notificationAllowed());
        // Its yes leaves the extension's handler out for that video.
        BackgroundPlay.answered(true);
        assertTrue(asked(player));
        assertFalse(BackgroundPlay.notificationAllowed());
    }

    @Test
    public void offPausedUnreadyOrThrowingTheHandlerTakesNothing() {
        Player player = playThenLeave(Trigger.BY_USER, 3000);
        Settings.KEEP_PLAYING_IN_BACKGROUND.save(false);
        assertTrue(asked(player));
        assertFalse(BackgroundPlay.skipCheck());
        assertTrue(kinds().toString(), kinds().toString().contains(BackgroundPlay.SWITCH_OFF));
        Settings.KEEP_PLAYING_IN_BACKGROUND.save(true);

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertTrue(asked(player));
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertTrue(asked(player)));

        BackgroundPlay.failNext = new IllegalStateException("test");
        assertTrue(asked(player));
        assertFalse(BackgroundPlay.skipCheck());
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains(FamilyNames.BACKGROUND_PLAY));
        BackgroundPlay.failNext = new IllegalStateException("test");
        assertFalse("Facebook's own handler when it throws", BackgroundPlay.deciding(new Object(), "1", Trigger.BY_PLAYER));

        // Starts and pauses the switch doesn't want aren't followed.
        Settings.KEEP_PLAYING_IN_BACKGROUND.save(false);
        Player unseen = new Player();
        TapToPlayForTests.tapEnded(80);
        BackgroundPlay.started(unseen, Trigger.BY_USER);
        Settings.KEEP_PLAYING_IN_BACKGROUND.save(true);
        SystemClock.sleep(3000);
        BackgroundPlay.pausing(unseen, Trigger.BY_PLAYER);
        unseen.video = "3003";
        assertTrue(BackgroundPlay.deciding(handler, "3003", Trigger.BY_PLAYER));
    }

    @Test
    public void aPlayerItCantReadIsLeftAlone() {
        BackgroundPlay.reader = new BackgroundPlay.PlayerReader() {
            @Override
            public boolean playing(Object player) {
                throw new IllegalStateException("unreadable");
            }

            @Override
            public String videoId(Object player) {
                return "1001";
            }
        };
        Player player = playThenLeave(Trigger.BY_USER, 3000);
        assertTrue(asked(player));
    }

    @Test
    public void thePatchedReaderReflectsTheKeptAccessors() throws Exception {
        Player player = new Player();
        assertTrue(BackgroundPlay.PATCHED.playing(player));
        player.playing = false;
        assertFalse(BackgroundPlay.PATCHED.playing(player));
        // Unpatched, the accessor's name is missing and the reader says so.
        assertNull(BackgroundPlay.PATCHED.videoId(player));
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("video id"));
    }

    @Test
    public void theManagersHandlersAreItsOneList() throws Exception {
        OneList one = new OneList();
        List<Object> handlers = BackgroundPlay.handlersOf(one);
        assertSame(one.handlers, handlers);
        assertThrows(IllegalStateException.class, () -> BackgroundPlay.handlersOf(new TwoLists()));
        assertThrows(IllegalStateException.class, () -> BackgroundPlay.handlersOf(new Object()));
    }

    @Test
    public void anUnpatchedHandlerMakesNoManager() throws Exception {
        BackgroundPlayForTests.forget();
        BackgroundPlay.makeManager(RuntimeEnvironment.getApplication());
        assertNull(BackgroundPlay.handler);
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("fullscreen handler"));
    }

    @SuppressWarnings("unused")
    private static final class OneList {
        private final List<Object> handlers = new ArrayList<>();
        private boolean registered;
    }

    @SuppressWarnings("unused")
    private static final class TwoLists {
        private final List<Object> first = new ArrayList<>();
        private final List<Object> second = new ArrayList<>();
    }
}
