/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.util.concurrent.TimeUnit;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Video buffer (#526): each player's start check and loading check ask with the number Facebook
 * settled on, in microseconds. Low caps each one at its size's number, Medium and Maximum raise each
 * one to theirs, and Facebook's choice, a pause or settings that aren't ready yet hand every number
 * back as it came. The high loading mark never ends up under the low one.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class VideoBufferTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Facebook's DefaultLoadControl numbers: 1 s to start, 2 s after a stall, 50 s kept ahead. */
    private static final long START = 1_000_000L;
    private static final long AFTER_STALL = 2_000_000L;
    private static final long AHEAD = 50_000_000L;

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.VIDEO_BUFFER.resetToDefault();
        HookStatus.clear();
        VideoBuffer.resetForTests();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.VIDEO_BUFFER + ":")) return line;
        }
        return null;
    }

    @Test
    public void theListStartsAtFacebooksChoiceAndBelongsToItsPatch() {
        assertEquals(BufferSize.FACEBOOK, Settings.VIDEO_BUFFER.defaultValue);
        assertEquals("hushfacebook_video_buffer", Settings.VIDEO_BUFFER.key);
        assertEquals("Video buffer", PatchFamily.VIDEO_BUFFER.patchName);
        assertTrue("a choice family has no switches to pause", PatchFamily.VIDEO_BUFFER.switches.isEmpty());
    }

    @Test
    public void facebooksChoiceHandsEveryNumberBack() {
        assertEquals(START, VideoBuffer.startAt(START, false));
        assertEquals(AFTER_STALL, VideoBuffer.startAt(AFTER_STALL, true));
        assertEquals(AHEAD, VideoBuffer.aheadLow(AHEAD));
        assertEquals(AHEAD, VideoBuffer.aheadHigh(AHEAD, AHEAD));
        assertEquals(7L, VideoBuffer.startAt(7L, false));
        String line = statusLine();
        assertFalse("a number left to Facebook was counted: " + line, line != null && line.contains("Counted"));
    }

    @Test
    public void lowCapsEachNumberAndLeavesOneAlreadyUnderIt() {
        Settings.VIDEO_BUFFER.save(BufferSize.LOW);
        assertEquals("the wait to start wasn't half a second", 500_000L, VideoBuffer.startAt(START, false));
        assertEquals("the wait after a stall wasn't a second", 1_000_000L, VideoBuffer.startAt(AFTER_STALL, true));
        assertEquals("a wait already under the cap was raised", 300_000L, VideoBuffer.startAt(300_000L, false));
        assertEquals("the low mark wasn't 5 seconds", 5_000_000L, VideoBuffer.aheadLow(AHEAD));
        assertEquals("the high mark wasn't 10 seconds", 10_000_000L, VideoBuffer.aheadHigh(5_000_000L, AHEAD));
        assertEquals("a low mark already under the cap was raised", 2_000_000L, VideoBuffer.aheadLow(2_000_000L));
        assertEquals(FamilyNames.VIDEO_BUFFER + ": invoked 5, 2 found, 0 missing. Counted: "
                        + VideoBuffer.START_CHANGED + " 2, " + VideoBuffer.AHEAD_CHANGED + " 1",
                statusLine());
    }

    @Test
    public void mediumRaisesEachNumberAndKeepsOneFacebookSetsHigher() {
        Settings.VIDEO_BUFFER.save(BufferSize.MEDIUM);
        assertEquals(1_500_000L, VideoBuffer.startAt(START, false));
        assertEquals(3_000_000L, VideoBuffer.startAt(AFTER_STALL, true));
        assertEquals("a longer wait of Facebook's was cut", 4_000_000L, VideoBuffer.startAt(4_000_000L, false));
        assertEquals(15_000_000L, VideoBuffer.aheadLow(10_000_000L));
        assertEquals("Facebook's 50 seconds ahead was cut", AHEAD, VideoBuffer.aheadLow(AHEAD));
        assertEquals(30_000_000L, VideoBuffer.aheadHigh(15_000_000L, 20_000_000L));
        assertEquals(AHEAD, VideoBuffer.aheadHigh(AHEAD, AHEAD));
    }

    @Test
    public void maximumWaitsLongerAndLoadsUpToAMinuteAhead() {
        Settings.VIDEO_BUFFER.save(BufferSize.MAXIMUM);
        assertEquals(3_000_000L, VideoBuffer.startAt(START, false));
        assertEquals(5_000_000L, VideoBuffer.startAt(AFTER_STALL, true));
        assertEquals(AHEAD, VideoBuffer.aheadLow(AHEAD));
        assertEquals(30_000_000L, VideoBuffer.aheadLow(8_000_000L));
        assertEquals("the high mark wasn't a minute", 60_000_000L, VideoBuffer.aheadHigh(AHEAD, AHEAD));
    }

    @Test
    public void theHighMarkNeverEndsUpUnderTheLowOne() {
        Settings.VIDEO_BUFFER.save(BufferSize.LOW);
        assertEquals("the player would stop loading under the mark where it always loads", 12_000_000L,
                VideoBuffer.aheadHigh(12_000_000L, AHEAD));
        Settings.VIDEO_BUFFER.save(BufferSize.MEDIUM);
        assertEquals(40_000_000L, VideoBuffer.aheadHigh(40_000_000L, 20_000_000L));
    }

    @Test
    public void pausedOrColdThePlayerGetsItsOwnNumbers() {
        Settings.VIDEO_BUFFER.save(BufferSize.MAXIMUM);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertEquals("paused by " + reason + ", the wait to start changed", START, VideoBuffer.startAt(START, false));
            assertEquals(AFTER_STALL, VideoBuffer.startAt(AFTER_STALL, true));
            assertEquals(8_000_000L, VideoBuffer.aheadLow(8_000_000L));
            assertEquals(AHEAD, VideoBuffer.aheadHigh(8_000_000L, AHEAD));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> {
            assertEquals("the wait changed before the settings were ready", START, VideoBuffer.startAt(START, false));
            assertEquals(8_000_000L, VideoBuffer.aheadLow(8_000_000L));
            assertEquals(AHEAD, VideoBuffer.aheadHigh(8_000_000L, AHEAD));
        });
        String line = statusLine();
        assertFalse("a number handed back was counted: " + line, line != null && line.contains("Counted"));
        assertEquals("on again after the pause, the wait wasn't raised", 3_000_000L, VideoBuffer.startAt(START, false));
    }

    @Test
    public void eachSizeKeepsItsFileValueAndOnlyLowCaps() {
        assertEquals("facebook", BufferSize.FACEBOOK.fileValue);
        assertEquals("low", BufferSize.LOW.fileValue);
        assertEquals("medium", BufferSize.MEDIUM.fileValue);
        assertEquals("maximum", BufferSize.MAXIMUM.fileValue);
        for (BufferSize size : BufferSize.values()) {
            assertEquals(size, BufferSize.fromFile(size.fileValue));
            assertEquals(size == BufferSize.LOW, size.caps());
            assertTrue(size + " waits less after a stall than at first", size.afterStallMs >= size.startMs);
            assertTrue(size + " stops loading under the mark where it always loads", size.aheadHighMs >= size.aheadLowMs);
        }
        assertEquals(null, BufferSize.fromFile("LOW"));
        assertEquals(null, BufferSize.fromFile(1));
        assertEquals(null, BufferSize.fromFile(null));
    }

    /** A run of a player's work on [handler], as its playback thread gets it. */
    private static Message work(Handler handler) {
        return Message.obtain(handler, VideoBuffer.DO_SOME_WORK);
    }

    @Test
    public void aPausedVideosWorkRunsOnceASecondWhileFacebookIsLeft() {
        VideoBuffer.watchingForTests();
        Handler handler = new Handler(Looper.getMainLooper());
        assertFalse("the first run waited", VideoBuffer.rest(work(handler), false));
        ShadowSystemClock.advanceBy(10, TimeUnit.MILLISECONDS);
        assertTrue("a run 10 ms later didn't wait", VideoBuffer.rest(work(handler), false));
        assertTrue("the run that waited wasn't sent again", handler.hasMessages(VideoBuffer.DO_SOME_WORK));
        ShadowSystemClock.advanceBy(10, TimeUnit.MILLISECONDS);
        assertTrue(VideoBuffer.rest(work(handler), false));
        ShadowSystemClock.advanceBy(VideoBuffer.REST_MS, TimeUnit.MILLISECONDS);
        assertFalse("a run a second later waited", VideoBuffer.rest(work(handler), false));
        assertTrue("a run counted as held back: " + statusLine(),
                statusLine().contains(VideoBuffer.RESTED + " 2"));
    }

    @Test
    public void aPlayerMeantToPlayOtherMessagesAndAStartedScreenRunAsFacebookWroteThem() {
        VideoBuffer.watchingForTests();
        Handler handler = new Handler(Looper.getMainLooper());
        assertFalse(VideoBuffer.rest(work(handler), false));
        assertFalse("a video meant to play waited", VideoBuffer.rest(work(handler), true));
        assertFalse("another message waited", VideoBuffer.rest(Message.obtain(handler, 1), false));
        VideoBuffer.screenStarted();
        assertFalse("a run with a Facebook screen started waited", VideoBuffer.rest(work(handler), false));
        VideoBuffer.screenStopped();
        assertTrue("left again, the run didn't wait", VideoBuffer.rest(work(handler), false));
        assertFalse(VideoBuffer.rest(null, false));
    }

    @Test
    public void withNoScreenCountPausedOrColdNothingWaits() {
        Handler handler = new Handler(Looper.getMainLooper());
        assertFalse(VideoBuffer.rest(work(handler), false));
        assertFalse("a run waited with no screen count kept", VideoBuffer.rest(work(handler), false));
        VideoBuffer.watchingForTests();
        assertFalse(VideoBuffer.rest(work(handler), false));
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("a run waited while paused", VideoBuffer.rest(work(handler), false));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
                assertFalse("a run waited before the settings were ready", VideoBuffer.rest(work(handler), false)));
        String line = statusLine();
        assertFalse("a run that went ahead was counted: " + line, line != null && line.contains(VideoBuffer.RESTED));
    }
}
