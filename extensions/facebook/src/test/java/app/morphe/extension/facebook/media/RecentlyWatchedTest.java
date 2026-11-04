/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.List;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

import app.morphe.extension.facebook.media.RecentlyWatched.Entry;
import app.morphe.extension.facebook.media.ResumePlaybackForTests.Params;
import app.morphe.extension.facebook.media.ResumePlaybackForTests.Player;
import app.morphe.extension.facebook.media.ResumePlaybackForTests.Trigger;
import app.morphe.extension.facebook.misc.BreakTimerForTests;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * What Recently watched keeps (#183): a reel or a video that's still playing three seconds after it
 * started and has played on, and a story card the viewer queued as viewed. Ads, GIFs, sound-only
 * players and videos scrolled straight past aren't kept. Off, paused, or when something in it
 * throws, nothing is kept and the player goes on as Facebook runs it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class RecentlyWatchedTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        SystemClock.sleep(60_000);
        ResumePlaybackForTests.install();
        RecentlyWatchedForTests.install();
        HookStatus.clear();
        Settings.RECENTLY_WATCHED.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.RECENTLY_WATCHED.resetToDefault();
        Settings.RESUME_LONG_VIDEOS.resetToDefault();
        RecentlyWatchedForTests.forget();
        ResumePlaybackForTests.forget();
        HookStatus.clear();
    }

    /** A player of [id] that Facebook started at [at]. */
    private static Player started(String id, boolean reel, int at) {
        Params params = new Params(id, 30_000);
        params.reel = reel;
        Player player = new Player(params);
        player.position = at;
        ResumePlayback.started(player, Trigger.BY_USER);
        return player;
    }

    /** [player] three seconds on, at [at], with the check run. */
    private static void playedTo(Player player, int at) {
        player.position = at;
        RecentlyWatchedForTests.runChecks();
    }

    private static List<Entry> entries() {
        return RecentlyWatchedForTests.entries();
    }

    private static String failures() {
        return HookStatus.missing(FamilyNames.RESUME_LONG_VIDEOS).toString();
    }

    @Test
    public void theSwitchStartsOff() {
        Settings.RECENTLY_WATCHED.resetToDefault();
        assertFalse(Settings.RECENTLY_WATCHED.get());
        playedTo(started("1", true, 0), 3_000);
        assertTrue(entries().isEmpty());
        assertTrue("a check waited on a switch that's off", RecentlyWatchedForTests.CHECKS.isEmpty());
    }

    @Test
    public void aReelThatPlaysOnIsKept() {
        Player player = started("2233445566", true, 0);
        assertTrue("kept before the check", entries().isEmpty());
        playedTo(player, 3_000);
        List<Entry> entries = entries();
        assertEquals(1, entries.size());
        Entry reel = entries.get(0);
        assertEquals(RecentlyWatched.REEL, reel.kind);
        assertEquals("2233445566", reel.id);
        assertEquals(30_000, reel.durationMs);
        assertNull(reel.owner);
        assertNull(reel.parent);
        assertTrue(Math.abs(System.currentTimeMillis() - reel.watchedAt) < 60_000);
        assertEquals("https://www.facebook.com/reel/2233445566", reel.link());
    }

    @Test
    public void aFeedVideoIsKeptAsAVideo() {
        playedTo(started("998877", false, 10_000), 13_000);
        Entry video = entries().get(0);
        assertEquals(RecentlyWatched.VIDEO, video.kind);
        assertEquals("https://www.facebook.com/watch/?v=998877", video.link());
    }

    @Test
    public void adsGifsAndSoundOnlyPlayersAreNotKept() {
        for (int i = 0; i < 3; i++) {
            Params params = new Params("p" + i, 30_000);
            params.sponsored = i == 0;
            params.gif = i == 1;
            params.audio = i == 2;
            Player player = new Player(params);
            ResumePlayback.started(player, Trigger.BY_USER);
            playedTo(player, 5_000);
        }
        assertTrue(entries().isEmpty());
    }

    /** Take breaks' reel count (#248) shares the played-on check, with Recently watched off. */
    @Test
    public void takeBreaksCountsReelsThatPlayOnButNotFeedVideosAdsOrLive() {
        Settings.RECENTLY_WATCHED.save(false);
        try (ActivityController<Activity> screen = BreakTimerForTests.countingReels()) {
            for (int i = 0; i < 7; i++) {
                Params params = new Params("c" + i, 30_000);
                params.reel = i != 1;
                params.sponsored = i == 2;
                params.live = i == 3;
                params.gif = i == 4;
                params.audio = i == 5;
                Player player = new Player(params);
                ResumePlayback.started(player, Trigger.BY_USER);
                // The last one is scrolled past, so it never plays on.
                if (i != 6) player.position = 3_000;
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
            }
            assertEquals("only the reel that played on counts", 1, BreakTimerForTests.reelsCounted());
        } finally {
            BreakTimerForTests.stopCountingReels(RuntimeEnvironment.getApplication());
        }
    }

    @Test
    public void aVideoScrolledPastOrSwappedIsNotKept() {
        playedTo(started("still", false, 0), 1_000);
        assertTrue("one that played under two seconds was kept", entries().isEmpty());

        Player swapped = started("first", false, 0);
        swapped.params = new Params("second", 30_000);
        playedTo(swapped, 3_000);
        assertTrue("the player's next video was kept as the first", entries().isEmpty());

        Player emptied = started("third", false, 0);
        emptied.params = null;
        playedTo(emptied, 3_000);
        assertTrue(entries().isEmpty());
    }

    @Test
    public void aReelThatLoopedOrAPositionThePatchCantReadCounts() {
        playedTo(started("looped", true, 28_000), 1_000);
        playedTo(started("unread", true, ResumePlayback.NOT_PATCHED_TIME), ResumePlayback.NOT_PATCHED_TIME);
        assertEquals(2, entries().size());
        assertEquals("unread", entries().get(0).id);
    }

    @Test
    public void aSecondWatchMovesItUp() {
        playedTo(started("a", true, 0), 3_000);
        playedTo(started("b", true, 0), 3_000);
        playedTo(started("a", true, 0), 3_000);
        List<Entry> entries = entries();
        assertEquals(2, entries.size());
        assertEquals("a", entries.get(0).id);
        assertEquals("b", entries.get(1).id);
    }

    @Test
    public void pausedOrTurnedOffKeepsNothing() {
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        playedTo(started("paused", true, 0), 3_000);
        PauseForTests.pause(HushfacebookPause.Reason.CRASH_LOOP);
        playedTo(started("crashed", true, 0), 3_000);
        PauseForTests.resume();
        assertTrue(entries().isEmpty());

        Player pausedMidway = started("midway", true, 0);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        playedTo(pausedMidway, 3_000);
        PauseForTests.resume();
        Player offMidway = started("off", true, 0);
        Settings.RECENTLY_WATCHED.save(false);
        playedTo(offMidway, 3_000);
        assertTrue(entries().isEmpty());

        Settings.RECENTLY_WATCHED.save(true);
        playedTo(started("on again", true, 0), 3_000);
        assertEquals(1, entries().size());
    }

    /** Resume long videos shares the hook, and a Recently watched that throws never stops its seek. */
    @Test
    public void aThrowingTimerLeavesTheResumeAlone() {
        Settings.RESUME_LONG_VIDEOS.save(true);
        RecentlyWatched.timer = (delayMs, task) -> {
            throw new IllegalStateException("no looper");
        };
        Params params = new Params("1234567890", 20 * 60_000);
        Player first = new Player(params);
        first.position = 5 * 60_000;
        ResumePlayback.stopped(first, Trigger.BY_USER);
        Player second = new Player(params);
        ResumePlayback.started(second, Trigger.BY_USER);
        ResumePlaybackForTests.runLater();
        assertFalse("the resume didn't seek", second.seeks.isEmpty());
        assertTrue(failures(), failures().contains(RecentlyWatched.PLAYER_HOOK));
        assertTrue(entries().isEmpty());
    }

    @Test
    public void aThrowingWriteIsRecordedAndNothingElseBreaks() {
        RecentlyWatched.writer = task -> {
            throw new IllegalStateException("no thread");
        };
        Player player = started("x", true, 0);
        playedTo(player, 3_000);
        assertTrue(failures(), failures().contains(RecentlyWatched.PLAYER_HOOK));

        HookStatus.clear();
        RecentlyWatched.writer = task -> {
            throw new IllegalStateException("no thread");
        };
        RecentlyWatched.storyViewed("s1", "b1", null);
        assertTrue(failures(), failures().contains(RecentlyWatched.STORY_HOOK));
    }

    @Test
    public void aViewedStoryIsKeptUnderItsBucket() {
        RecentlyWatched.storyViewed("2010", "1001", new Object());
        Entry story = entries().get(0);
        assertEquals(RecentlyWatched.STORY, story.kind);
        assertEquals("2010", story.id);
        assertEquals("1001", story.parent);
        assertNull("a card of another shape named a poster", story.owner);
        assertEquals("https://www.facebook.com/stories/1001/2010/", story.link());
    }

    @Test
    public void aStoryWithoutAnIdOrABucketIsNotKept() {
        RecentlyWatched.storyViewed(null, "b", null);
        RecentlyWatched.storyViewed("", "b", null);
        RecentlyWatched.storyViewed("s", null, null);
        RecentlyWatched.storyViewed("s", "", null);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        RecentlyWatched.storyViewed("s", "b", null);
        PauseForTests.resume();
        assertTrue(entries().isEmpty());
    }

    @Test
    public void eachKindLinksToItsOwnPage() {
        assertEquals("https://www.facebook.com/reel/1", new Entry(RecentlyWatched.REEL, "1", null, null, null, 0, 0).link());
        assertEquals("https://www.facebook.com/watch/?v=2", new Entry(RecentlyWatched.VIDEO, "2", null, null, null, 0, 0).link());
        assertEquals("https://www.facebook.com/stories/9/3/", new Entry(RecentlyWatched.STORY, "3", null, null, "9", 0, 0).link());
        assertNull("a story with no bucket has nowhere to open", new Entry(RecentlyWatched.STORY, "3", null, null, null, 0, 0).link());
        assertEquals("an id with a slash stayed one path segment",
                "https://www.facebook.com/reel/a%2Fb%3Fc", new Entry(RecentlyWatched.REEL, "a/b?c", null, null, null, 0, 0).link());
        assertNull(new Entry("photo", "4", null, null, null, 0, 0).link());
    }

    @Test
    public void clearForgetsEveryEntry() {
        playedTo(started("a", true, 0), 3_000);
        RecentlyWatched.storyViewed("s", "b", null);
        assertEquals(2, RecentlyWatched.entries().size());
        RecentlyWatched.clear();
        assertTrue(RecentlyWatched.entries().isEmpty());
        assertFalse(RecentlyWatchedForTests.store().contains(WatchedList.KEY));
    }

    private static void runWrites(Deque<Runnable> writes) {
        Runnable write;
        while ((write = writes.poll()) != null) write.run();
    }

    @Test
    public void clearDiscardsQueuedStoriesAndOlderVideoChecks() {
        Deque<Runnable> writes = new ArrayDeque<>();
        RecentlyWatched.writer = writes::add;
        RecentlyWatched.storyViewed("old story", "bucket", null);
        Player video = started("old video", true, 0);
        RecentlyWatched.clear();
        playedTo(video, 3_000);
        runWrites(writes);
        assertTrue(RecentlyWatched.entries().isEmpty());
        assertTrue(new WatchedList(RecentlyWatchedForTests.store()).newestFirst().isEmpty());
        assertFalse(RecentlyWatchedForTests.store().contains(WatchedList.KEY));

        RecentlyWatched.storyViewed("new story", "bucket", null);
        runWrites(writes);
        assertEquals("new story", entries().get(0).id);
    }

    @Test
    public void turningRecordingOffThenOnDiscardsAlreadyQueuedWork() {
        Deque<Runnable> writes = new ArrayDeque<>();
        RecentlyWatched.writer = writes::add;
        RecentlyWatched.storyViewed("old story", "bucket", null);
        Player video = started("old video", true, 0);
        Settings.RECENTLY_WATCHED.save(false);
        Settings.RECENTLY_WATCHED.save(true);
        playedTo(video, 3_000);
        runWrites(writes);
        assertTrue(entries().isEmpty());
        RecentlyWatched.storyViewed("new story", "bucket", null);
        runWrites(writes);
        assertEquals("new story", entries().get(0).id);
    }

    @Test
    public void aBatchRecordingChangeAlsoInvalidatesQueuedWork() throws Exception {
        Deque<Runnable> writes = new ArrayDeque<>();
        RecentlyWatched.writer = writes::add;
        RecentlyWatched.storyViewed("old story", "bucket", null);
        Player video = started("old video", false, 0);
        Setting.saveAll(Map.of(Settings.RECENTLY_WATCHED, false));
        Setting.saveAll(Map.of(Settings.RECENTLY_WATCHED, true));
        playedTo(video, 3_000);
        runWrites(writes);
        assertTrue(entries().isEmpty());
    }

    @Test
    public void aPreferenceBindingChangeAlsoInvalidatesQueuedWork() {
        Deque<Runnable> writes = new ArrayDeque<>();
        RecentlyWatched.writer = writes::add;
        RecentlyWatched.storyViewed("old story", "bucket", null);
        Player video = started("old video", false, 0);
        BooleanSetting.privateSetValue(Settings.RECENTLY_WATCHED, false);
        BooleanSetting.privateSetValue(Settings.RECENTLY_WATCHED, true);
        playedTo(video, 3_000);
        runWrites(writes);
        assertTrue(entries().isEmpty());
    }

    @Test
    public void savingTheSameRecordingValueDoesNotDiscardANewWatch() throws Exception {
        Deque<Runnable> writes = new ArrayDeque<>();
        RecentlyWatched.writer = writes::add;
        RecentlyWatched.storyViewed("current story", "bucket", null);
        Settings.RECENTLY_WATCHED.save(true);
        Setting.saveAll(Map.of(Settings.RECENTLY_WATCHED, true));
        BooleanSetting.privateSetValue(Settings.RECENTLY_WATCHED, true);
        runWrites(writes);
        assertEquals("current story", entries().get(0).id);
    }

    @Test
    public void aCardThatCantBeReadNamesNoPoster() {
        assertNull(RecentlyWatched.posterOf(null));
        assertNull(RecentlyWatched.posterOf(new Object()));
        assertNull(RecentlyWatched.posterOf("a string"));
    }
}
