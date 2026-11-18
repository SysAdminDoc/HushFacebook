/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;

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
}
