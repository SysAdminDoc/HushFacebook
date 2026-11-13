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

import android.app.Activity;
import android.app.RemoteAction;
import android.content.Intent;
import android.os.Looper;
import android.os.SystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
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
 * The picture-in-picture window's headphones button (#455): with its switch on it takes the place of
 * the window's last button when the window is full, or goes after Facebook's buttons, and its tap
 * sends the window away with the video carried on, even with Keep playing in the background off.
 * Off, paused, before the settings are ready, or when it throws, the window keeps Facebook's buttons.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BackgroundPlayButtonTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** A screen floating in the window, with the room for buttons it reports and a record of leaving. */
    public static final class Screen extends Activity {
        boolean inWindow = true;
        int most = 3;
        int sentBack;

        @Override
        public boolean isInPictureInPictureMode() {
            return inWindow;
        }

        @Override
        public int getMaxNumPictureInPictureActions() {
            return most;
        }

        @Override
        public boolean moveTaskToBack(boolean nonRoot) {
            sentBack++;
            return true;
        }
    }

    private final Object handler = new Object();
    private Screen screen;

    @Before
    public void start() {
        SystemClock.sleep(60_000);
        BackgroundPlayForTests.forget();
        PictureInPicture.forget();
        BackgroundPlay.useHandler(handler);
        HookStatus.clear();
        FeedFilterCounters.clear();
        screen = Robolectric.buildActivity(Screen.class).setup().get();
        Settings.BACKGROUND_PLAY_BUTTON.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.BACKGROUND_PLAY_BUTTON.resetToDefault();
        Settings.KEEP_PLAYING_IN_BACKGROUND.resetToDefault();
        BackgroundPlayForTests.forget();
        PictureInPicture.forget();
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    /** Facebook's three buttons for a video: rewind, play or pause, forward. */
    private static List<Object> facebooks() {
        return new ArrayList<>(Arrays.asList("rewind", "play", "forward"));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> built(List<Object> facebooks) {
        return BackgroundPlayButton.windowActions(facebooks);
    }

    private static RemoteAction headphones(List<Object> actions) {
        Object last = actions.get(actions.size() - 1);
        assertTrue("the last button is the headphones: " + actions, last instanceof RemoteAction);
        return (RemoteAction) last;
    }

    private static void tap(RemoteAction headphones) throws Exception {
        headphones.getActionIntent().send();
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static String report() {
        return HookStatus.report().toString();
    }

    @Test
    public void theSwitchStartsOff() {
        Settings.BACKGROUND_PLAY_BUTTON.resetToDefault();
        assertFalse("a new button in Facebook's window is a choice", Settings.BACKGROUND_PLAY_BUTTON.get());
    }

    @Test
    public void headphonesTakeTheForwardsPlaceInAFullWindow() {
        List<Object> facebooks = facebooks();
        List<Object> actions = built(facebooks);
        assertEquals(3, actions.size());
        assertEquals(Arrays.asList("rewind", "play"), actions.subList(0, 2));
        RemoteAction headphones = headphones(actions);
        assertEquals("Keep playing in the background", headphones.getTitle().toString());
        assertEquals("Keep playing in the background", headphones.getContentDescription().toString());
        assertEquals("Facebook's own list is left as it was", facebooks(), facebooks);
        assertTrue(report(), report().contains(FamilyNames.BACKGROUND_PLAY));
    }

    @Test
    public void headphonesGoAfterFacebooksButtonsWhenThereIsRoom() {
        List<Object> live = built(new ArrayList<>(Arrays.asList("live", null)));
        assertEquals("a live video's one button, with nulls dropped", 2, live.size());
        assertEquals("live", live.get(0));
        headphones(live);

        assertEquals("no list at all", 1, built(null).size());
        headphones(built(null));

        screen.most = 5;
        PictureInPicture.armed(screen, new Player(), null);
        List<Object> roomy = built(facebooks());
        assertEquals("a window with room for five keeps the forward", 4, roomy.size());
        assertEquals(facebooks(), roomy.subList(0, 3));
        headphones(roomy);

        screen.most = 0;
        assertEquals("a screen that can't say takes Android's usual three", 3, built(facebooks()).size());
    }

    @Test
    public void offPausedOrUnreadyTheWindowKeepsFacebooksButtons() {
        Settings.BACKGROUND_PLAY_BUTTON.save(false);
        List<Object> facebooks = facebooks();
        assertSame(facebooks, built(facebooks));
        Settings.BACKGROUND_PLAY_BUTTON.save(true);

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertSame(facebooks, built(facebooks));
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertSame(facebooks, built(facebooks)));
    }

    @Test
    public void aTapSendsTheWindowAwayAndCarriesItsVideoOn() throws Exception {
        assertFalse("the tap works with Keep playing in the background off", Settings.KEEP_PLAYING_IN_BACKGROUND.get());
        // A reel Facebook started on its own, looping in the window: nothing of yours recorded.
        Player player = new Player();
        BackgroundPlay.started(player, Trigger.BY_AUTOPLAY);
        PictureInPicture.armed(screen, player, null);
        tap(headphones(built(facebooks())));
        assertEquals("the window's screen went to the back", 1, screen.sentBack);
        assertTrue(report(), report().contains(BackgroundPlayButton.LEFT));
        assertFalse("the tap paused the window's video before its screen went", player.playing);

        // The window's screen stops without a pause of its own first, so Facebook's manager asks
        // at once about the pause the tap made.
        Trigger paused = Trigger.valueOf(BackgroundPlay.WINDOW_PAUSE_TRIGGER);
        assertFalse("the handler stopped the window's video", BackgroundPlay.deciding(handler, player.video, paused));
        assertTrue(BackgroundPlay.skipCheck());
        assertTrue("the notification's flag", BackgroundPlay.notificationAllowed());
        BackgroundPlay.answered(true);
        // Facebook's own pause as the screen stops comes after, on a video already paused.
        player.pause(Trigger.BY_PLAYER);
        BackgroundPlay.returning(handler);
        BackgroundPlay.returned();
        assertFalse(BackgroundPlay.notificationAllowed());
    }

    @Test
    public void theTapCarriesOnlyTheLeaveThatFollowsIt() throws Exception {
        Trigger paused = Trigger.valueOf(BackgroundPlay.WINDOW_PAUSE_TRIGGER);
        Player player = new Player();
        PictureInPicture.armed(screen, player, null);
        tap(headphones(built(facebooks())));
        SystemClock.sleep(BackgroundPlay.WINDOW_BUTTON_MS + 1000);
        assertTrue("an ask long after the tap", BackgroundPlay.deciding(handler, player.video, paused));
        BackgroundPlay.answered(false);

        player.playing = true;
        tap(headphones(built(facebooks())));
        Settings.BACKGROUND_PLAY_BUTTON.save(false);
        assertTrue("the button's switch turned off since", BackgroundPlay.deciding(handler, player.video, paused));
        BackgroundPlay.answered(false);
        Settings.BACKGROUND_PLAY_BUTTON.save(true);

        // A later tap, on the video that tap paused.
        SystemClock.sleep(100);
        tap(headphones(built(facebooks())));
        assertFalse("a video already paused in the window stays as it was", player.playing);
        player.pause(Trigger.BY_PLAYER);
        assertTrue("a video already paused in the window", BackgroundPlay.deciding(handler, player.video, Trigger.BY_PLAYER));
    }

    @Test
    public void aTapWithNoWindowOrAnotherAppsBroadcastLeavesNothing() throws Exception {
        Player player = new Player();
        PictureInPicture.armed(screen, player, null);
        RemoteAction headphones = headphones(built(facebooks()));

        screen.inWindow = false;
        tap(headphones);
        assertEquals("a screen that's out of the window stays", 0, screen.sentBack);
        assertTrue(report(), report().contains(BackgroundPlayButton.NO_WINDOW));

        screen.inWindow = true;
        Intent forged = new Intent(BackgroundPlayButton.ACTION)
                .setPackage(RuntimeEnvironment.getApplication().getPackageName())
                .putExtra(BackgroundPlayButton.EXTRA_TOKEN, "guessed");
        RuntimeEnvironment.getApplication().sendBroadcast(forged);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("a broadcast without this app's token", 0, screen.sentBack);

        PictureInPicture.forget();
        assertFalse("nothing armed", BackgroundPlayButton.tapped());
        assertTrue("no tap went through, so the video plays on", player.playing);
        BackgroundPlay.pausing(player, Trigger.BY_PLAYER);
        assertTrue("no tap went through, so nothing is carried on",
                BackgroundPlay.deciding(handler, player.video, Trigger.BY_PLAYER));
    }
}
