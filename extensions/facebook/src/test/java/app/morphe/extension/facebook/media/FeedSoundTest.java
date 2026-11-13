/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.PowerManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Keep feed videos muted: with its switch on, a new session's sound starts off, and a sound-on
 * that isn't a tap or the volume up key leaves the flag as it is. Mutes, the user's own sound-on
 * and everything while off, paused or before the settings are ready go through as Facebook asked.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FeedSoundTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stand-ins for Facebook's enum of sound change reasons, whose names it keeps. */
    private enum Reason { BY_USER, BY_USER_GESTURE, BY_ANDROID, BY_PLAYER, BY_NF_TO_VH_DEEPLINK }

    /**
     * A stand-in for Facebook's sound state with the patch's calls in both setters: the session
     * start Facebook runs on a cold start or after a long time away, and the reasoned setter.
     */
    public static final class SoundState {
        boolean on;
        int sessions;
        /** The flag Facebook's own sound setting and the headphones pick for a new session. */
        boolean pick = true;

        public void startNewSession() {
            sessions++;
            boolean answer = FeedSound.sessionSound(pick);
            FeedSound.soundSet(this, Reason.BY_ANDROID, answer);
            on = answer;
        }

        void set(Object reason, boolean asked) {
            boolean answer = FeedSound.soundChange(reason, asked, on);
            FeedSound.soundSet(this, reason, answer);
            on = answer;
        }
    }

    /** A screen that can be rebuilt for a rotation. */
    public static final class Screen extends Activity {
        boolean rotating;

        @Override
        public boolean isChangingConfigurations() {
            return rotating;
        }
    }

    @Before
    public void start() {
        HookStatus.clear();
        FeedSound.forget();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.KEEP_FEED_VIDEOS_MUTED.resetToDefault();
        Settings.RESTORE_FEED_SOUND.resetToDefault();
        BackgroundPlay.session = false;
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(PowerManager.class)).setIsInteractive(true);
        FeedSound.forget();
        HookStatus.clear();
    }

    private static Screen screen() {
        return Robolectric.buildActivity(Screen.class).setup().get();
    }

    /** Facebook goes to the background from [screen] and comes back to it. */
    private static void awayAndBack(Screen screen) {
        FeedSound.stopped(screen);
        FeedSound.started(screen);
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.KEEP_FEED_VIDEOS_MUTED + ":")) return line;
        }
        return null;
    }

    /** A volume up key reaches Facebook through an activity's onKeyUp. */
    private static boolean onKeyUp(Object reason) {
        return FeedSound.soundChange(reason, true, false);
    }

    /** A volume change from outside the app reaches the sound through this kept name. */
    private static boolean maybeUnmuteOnDeviceVolumeIncrease(Object reason) {
        return FeedSound.soundChange(reason, true, false);
    }

    @Test
    public void theSwitchStartsOffAndBelongsToItsPatch() {
        assertFalse("Keep feed videos muted doesn't start off", Settings.KEEP_FEED_VIDEOS_MUTED.defaultValue);
        assertEquals(FamilyNames.KEEP_FEED_VIDEOS_MUTED, PatchFamily.KEEP_FEED_VIDEOS_MUTED.patchName);
        assertTrue(PatchFamily.KEEP_FEED_VIDEOS_MUTED.switches.contains(Settings.KEEP_FEED_VIDEOS_MUTED));
    }

    @Test
    public void onEachSessionStartsMutedAndIsCounted() {
        Settings.KEEP_FEED_VIDEOS_MUTED.save(true);
        assertFalse("a new session started with sound", FeedSound.sessionSound(true));
        assertFalse("a muted session start turned the sound on", FeedSound.sessionSound(false));
        String line = statusLine();
        assertTrue(line, line.contains("Counted: " + FeedSound.SESSION_MUTED + " 1"));
    }

    @Test
    public void onASoundOnThatIsntYoursKeepsTheFlag() {
        Settings.KEEP_FEED_VIDEOS_MUTED.save(true);
        assertFalse("Facebook's start turned the feed's sound on", FeedSound.soundChange(Reason.BY_ANDROID, true, false));
        assertFalse("the player turned the feed's sound on", FeedSound.soundChange(Reason.BY_PLAYER, true, false));
        assertFalse("opening a feed video turned the sound on",
                FeedSound.soundChange(Reason.BY_NF_TO_VH_DEEPLINK, true, false));
        assertFalse("a reason that isn't an enum turned the sound on", FeedSound.soundChange(null, true, false));
        String line = statusLine();
        assertTrue(line, line.contains(FeedSound.KEPT_MUTED + " 4"));
    }

    @Test
    public void onYourTapsAndTheVolumeKeyStillTurnTheSoundOn() {
        Settings.KEEP_FEED_VIDEOS_MUTED.save(true);
        assertTrue("a tap on the speaker was kept muted", FeedSound.soundChange(Reason.BY_USER, true, false));
        assertTrue("a gesture was kept muted", FeedSound.soundChange(Reason.BY_USER_GESTURE, true, false));
        assertTrue("the volume up key was kept muted", onKeyUp(Reason.BY_ANDROID));
        assertTrue("a volume change was kept muted", maybeUnmuteOnDeviceVolumeIncrease(Reason.BY_ANDROID));
        String line = statusLine();
        assertFalse("the user's own sound-on was counted: " + line, line != null && line.contains("Counted"));
    }

    @Test
    public void onMutesAndASoundAlreadyOnGoThrough() {
        Settings.KEEP_FEED_VIDEOS_MUTED.save(true);
        assertFalse("a mute was turned into sound", FeedSound.soundChange(Reason.BY_ANDROID, false, true));
        assertFalse("a mute of a muted feed was turned into sound", FeedSound.soundChange(Reason.BY_PLAYER, false, false));
        assertTrue("a sound the user turned on was switched off", FeedSound.soundChange(Reason.BY_ANDROID, true, true));
        String line = statusLine();
        assertFalse("a flag left to Facebook was counted: " + line, line != null && line.contains("Counted"));
    }

    @Test
    public void onASoundYouTurnedOnGoesOffWhenFacebookComesBack() {
        // #465: a reel unmuted with a tap, Home, then back a minute later, long before Facebook's
        // own new session.
        Settings.KEEP_FEED_VIDEOS_MUTED.save(true);
        SoundState sound = new SoundState();
        sound.startNewSession();
        assertFalse("the cold start's session had sound", sound.on);
        Screen screen = screen();
        FeedSound.started(screen);
        sound.set(Reason.BY_USER, true);
        assertTrue("the tap on the speaker", sound.on);

        awayAndBack(screen);
        assertFalse("back in Facebook, the sound was still on", sound.on);
        assertEquals("the state's own session start ran once more", 2, sound.sessions);
        String line = statusLine();
        assertTrue(line, line.contains(FeedSound.RETURN_MUTED + " 1"));

        awayAndBack(screen);
        assertEquals("a feed already muted got another session", 2, sound.sessions);
        sound.set(Reason.BY_USER, true);
        sound.set(Reason.BY_USER, false);
        awayAndBack(screen);
        assertEquals("a sound you turned off again got another session", 2, sound.sessions);
    }

    @Test
    public void onARotationTheScreenGoingOffOrAnotherScreenKeepTheSound() {
        Settings.KEEP_FEED_VIDEOS_MUTED.save(true);
        SoundState sound = new SoundState();
        Screen screen = screen();
        FeedSound.started(screen);
        sound.set(Reason.BY_USER, true);

        screen.rotating = true;
        awayAndBack(screen);
        screen.rotating = false;
        assertTrue("a rotation muted the sound", sound.on);

        Screen viewer = screen();
        FeedSound.started(viewer);
        FeedSound.stopped(screen);
        FeedSound.started(screen);
        FeedSound.stopped(viewer);
        assertTrue("a video viewer opening and closing over the feed muted the sound", sound.on);

        PowerManager power = screen.getSystemService(PowerManager.class);
        shadowOf(power).setIsInteractive(false);
        FeedSound.stopped(screen);
        shadowOf(power).setIsInteractive(true);
        FeedSound.started(screen);
        assertTrue("the screen going off and on with Facebook in front muted the sound", sound.on);
        assertEquals(0, sound.sessions);
    }

    @Test
    public void aVideoPlayingInTheBackgroundKeepsItsSoundOnReturn() {
        Settings.KEEP_FEED_VIDEOS_MUTED.save(true);
        SoundState sound = new SoundState();
        Screen screen = screen();
        FeedSound.started(screen);
        sound.set(Reason.BY_USER, true);
        BackgroundPlay.session = true;
        awayAndBack(screen);
        assertTrue("coming back to the video playing in the background muted it", sound.on);
        BackgroundPlay.session = false;
        awayAndBack(screen);
        assertFalse("the next return, with nothing in the background, kept the sound", sound.on);
    }

    @Test
    public void offPausedOrColdTheSoundStaysOnReturn() {
        SoundState sound = new SoundState();
        Screen screen = screen();
        FeedSound.started(screen);
        sound.set(Reason.BY_USER, true);
        awayAndBack(screen);
        assertTrue("off, a return muted the sound", sound.on);

        Settings.KEEP_FEED_VIDEOS_MUTED.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        awayAndBack(screen);
        assertTrue("paused, a return muted the sound", sound.on);
        PauseForTests.resume();

        FeedSound.stopped(screen);
        SettingsContextRule.withoutContext(() -> FeedSound.started(screen));
        assertTrue("a return before the settings were ready muted the sound", sound.on);
        assertEquals(0, sound.sessions);
        String line = statusLine();
        assertFalse("a sound left to Facebook was counted: " + line, line != null && line.contains("Counted"));
    }

    @Test
    public void aStateWithoutASessionStartReportsAndLeavesTheSound() {
        Settings.KEEP_FEED_VIDEOS_MUTED.save(true);
        Object moved = new Object();
        Screen screen = screen();
        FeedSound.started(screen);
        FeedSound.soundSet(moved, Reason.BY_USER, true);
        awayAndBack(screen);
        String line = statusLine();
        assertTrue(line, line.contains("return to Facebook"));
        assertFalse("a failed return was counted: " + line, line.contains(FeedSound.RETURN_MUTED));
    }

    @Test
    public void restoreFeedSoundStartsOffAndBelongsToTheSamePatch() {
        assertFalse("Restore feed sound after opened videos doesn't start off", Settings.RESTORE_FEED_SOUND.defaultValue);
        assertTrue(PatchFamily.KEEP_FEED_VIDEOS_MUTED.switches.contains(Settings.RESTORE_FEED_SOUND));
    }

    @Test
    public void restoreFeedSoundOnALongWatchWithSoundIsUnderEveryThreshold() {
        Settings.RESTORE_FEED_SOUND.save(true);
        // Keep feed videos muted stays off, as in #350.
        for (long watched : new long[] {0L, 4_000L, 600_000L, Long.MAX_VALUE}) {
            // Facebook turns the feed's sound on only for a time over its threshold, which nothing is under.
            assertEquals("a " + watched + " ms watch with sound came back over some threshold",
                    Long.MIN_VALUE, FeedSound.viewerSoundTime(watched));
        }
        String line = statusLine();
        assertTrue(line, line.contains(FeedSound.SOUND_PUT_BACK + " 4"));
    }

    @Test
    public void restoreFeedSoundLeavesAVideoThatNeverPlayedWithSoundAlone() {
        Settings.RESTORE_FEED_SOUND.save(true);
        assertEquals(-1L, FeedSound.viewerSoundTime(-1L));
        String line = statusLine();
        assertFalse("a close without sound was counted: " + line, line != null && line.contains("Counted"));
    }

    @Test
    public void restoreFeedSoundOffPausedOrColdFacebooksTimeGoesThrough() {
        assertEquals("off, a long watch's time changed", 600_000L, FeedSound.viewerSoundTime(600_000L));
        Settings.RESTORE_FEED_SOUND.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertEquals("a Hushfacebook paused by " + reason + " changed a long watch's time",
                    600_000L, FeedSound.viewerSoundTime(600_000L));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertEquals(
                "a long watch's time changed before the settings were ready", 600_000L, FeedSound.viewerSoundTime(600_000L)));
        String line = statusLine();
        assertFalse("a time left to Facebook was counted: " + line, line != null && line.contains("Counted"));
        assertTrue("on again after the pause, a long watch's time went through", FeedSound.viewerSoundTime(600_000L) < 0);
    }

    @Test
    public void offPausedOrColdFacebooksSoundGoesThrough() {
        assertTrue("off, a session started muted", FeedSound.sessionSound(true));
        assertTrue("off, Facebook's own sound-on was kept off", FeedSound.soundChange(Reason.BY_ANDROID, true, false));

        Settings.KEEP_FEED_VIDEOS_MUTED.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertTrue("a Hushfacebook paused by " + reason + " muted a session", FeedSound.sessionSound(true));
            assertTrue("a Hushfacebook paused by " + reason + " kept a sound off",
                    FeedSound.soundChange(Reason.BY_ANDROID, true, false));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertTrue(
                "a session started muted before the settings were ready", FeedSound.sessionSound(true)));

        String line = statusLine();
        assertFalse("a flag left to Facebook was counted: " + line, line != null && line.contains("Counted"));
        assertFalse("on again after the pause, a session started with sound", FeedSound.sessionSound(true));
    }
}
