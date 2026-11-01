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

import android.content.Context;
import android.os.SystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.Collections;
import java.util.List;

import app.morphe.extension.facebook.media.ResumePlaybackForTests.Params;
import app.morphe.extension.facebook.media.ResumePlaybackForTests.Player;
import app.morphe.extension.facebook.media.ResumePlaybackForTests.Trigger;
import app.morphe.extension.facebook.media.ResumePlaybackForTests.TriggerWithoutPlayer;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * The rule Resume long videos holds every player to: a long video stopped partway is saved, its
 * next first start in a player seeks back there once, and a seek someone else makes first, a start
 * Facebook already placed, a new video and the end of a video all start where Facebook starts them.
 * Off, paused, before the settings are ready, or when a stub isn't filled in, nothing is saved and
 * nothing seeks.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ResumePlaybackTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int MINUTE = 60_000;

    @Before
    public void start() {
        SystemClock.sleep(60_000);
        ResumePlaybackForTests.install();
        HookStatus.clear();
        FeedFilterCounters.clear();
        Settings.RESUME_LONG_VIDEOS.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.RESUME_LONG_VIDEOS.resetToDefault();
        ResumePlaybackForTests.forget();
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    private static Params longVideo(String id) {
        return new Params(id, 40 * MINUTE);
    }

    /** A player of [params] stopped at [at]. */
    private static void leftAt(Params params, int at) {
        Player player = new Player(params);
        player.position = at;
        ResumePlayback.stopped(player, Trigger.BY_USER);
    }

    /** A new player of [params] started at 0, with the resume it posts run. */
    private static Player opened(Params params) {
        Player player = new Player(params);
        ResumePlayback.started(player, Trigger.BY_USER);
        ResumePlaybackForTests.runLater();
        return player;
    }

    private static String report() {
        return String.join("\n", FeedFilterCounters.report());
    }

    @Test
    public void theSwitchStartsOff() {
        Settings.RESUME_LONG_VIDEOS.resetToDefault();
        assertFalse("nothing is saved or moved until it's turned on", Settings.RESUME_LONG_VIDEOS.get());
    }

    @Test
    public void aLongVideoLeftPartwayResumesThereOnce() {
        Params params = longVideo("111111111");
        leftAt(params, 12 * MINUTE + 34_000);
        Player player = opened(params);
        assertEquals(Collections.singletonList("BY_PLAYER@754000"), player.seeks);

        // Facebook's later starts of the same video in the same player, after a pause or a
        // buffer, are its own.
        player.position = 20 * MINUTE;
        ResumePlayback.started(player, Trigger.BY_USER);
        ResumePlaybackForTests.runLater();
        assertEquals("it resumed a second time", 1, player.seeks.size());
        String report = report();
        assertTrue(report, report.contains("point saved 1") && report.contains("resumed 1"));
    }

    @Test
    public void aNewVideoStartsAtZero() {
        leftAt(longVideo("111111111"), 10 * MINUTE);
        Player other = opened(longVideo("222222222"));
        assertEquals("a video nobody left partway moved", Collections.emptyList(), other.seeks);
        assertTrue(report(), report().contains("no point yet 1"));
    }

    @Test
    public void aStopNearTheStartSavesNothingAndKeepsAnEarlierPoint() {
        Params params = longVideo("111111111");
        leftAt(params, ResumePlayback.MIN_SAVED_MS - 1);
        assertTrue("a glance saved a point", opened(params).seeks.isEmpty());

        leftAt(params, 9 * MINUTE);
        leftAt(params, 2_000);
        assertEquals("a stop at the start wiped the point", Collections.singletonList("BY_PLAYER@540000"),
                opened(params).seeks);
    }

    @Test
    public void theEndOfTheVideoForgetsItsPoint() {
        Params params = longVideo("111111111");
        leftAt(params, 30 * MINUTE);
        leftAt(params, params.length - ResumePlayback.END_MARGIN_MS);
        assertTrue("a finished video resumed", opened(params).seeks.isEmpty());
        assertTrue(report(), report().contains("point cleared at the end 1"));
    }

    @Test
    public void thePlayersOwnLengthWinsOverTheParams() {
        // The params can say 0; Facebook's remaining time reads the player's own length first.
        Params unknown = new Params("111111111", 0);
        Player player = new Player(unknown);
        player.length = 30 * MINUTE;
        player.position = 7 * MINUTE;
        ResumePlayback.stopped(player, Trigger.BY_USER);
        Player next = new Player(unknown);
        next.length = 30 * MINUTE;
        assertEquals(Collections.singletonList("BY_PLAYER@420000"), openedAfter(next));

        // A player that doesn't know its length yet goes by the params'.
        Params known = longVideo("222222222");
        leftAt(known, 8 * MINUTE);
        assertEquals(Collections.singletonList("BY_PLAYER@480000"), opened(known).seeks);

        // And the player's own length says when a video is short, whatever the params say.
        Params clip = longVideo("333333333");
        Player shortOne = new Player(clip);
        shortOne.length = 90_000;
        shortOne.position = 60_000;
        ResumePlayback.stopped(shortOne, Trigger.BY_USER);
        assertTrue(opened(clip).seeks.isEmpty());
    }

    @Test
    public void shortVideosReelsLiveAdsAndLoopsAreLeftAlone() {
        Params clip = new Params("1", ResumePlayback.MIN_DURATION_MS - 1);
        leftAt(clip, 60_000);
        assertTrue(opened(clip).seeks.isEmpty());

        Params reel = new Params("2", ResumePlayback.MIN_DURATION_MS - 1);
        reel.reel = true;
        Params live = longVideo("3");
        live.live = true;
        Params ad = longVideo("4");
        ad.sponsored = true;
        Params loop = longVideo("5");
        loop.loop = true;
        Params gif = longVideo("6");
        gif.gif = true;
        Params song = longVideo("7");
        song.audio = true;
        for (Params params : new Params[]{reel, live, ad, loop, gif, song}) {
            leftAt(params, 10 * MINUTE);
            assertTrue(params.id, opened(params).seeks.isEmpty());
        }
        String report = report();
        assertFalse("something was saved: " + report, report.contains("point saved"));
        for (String kind : new String[]{"under two minutes 1", "reel 1", "live 1", "ad 1", "loops 1", "GIF 1",
                "audio only 1"}) {
            assertTrue(kind + " in " + report, report.contains(kind));
        }
    }

    /** A reel is left alone, and Debug logging says where it began, for reels that open partway (issue #90). */
    @Test
    public void debugLoggingSaysWhereAReelStarted() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        try {
            Params params = new Params("8", 15_000);
            params.reel = true;
            Player player = new Player(params);
            player.position = 4_250;
            ResumePlayback.started(player, Trigger.BY_USER);
            ResumePlaybackForTests.runLater();
            assertTrue("a reel moved: " + player.seeks, player.seeks.isEmpty());
            String report = LogBufferManager.buildExportText();
            assertTrue(report, report.contains(
                    "Resume long videos: left a start alone, reel, at 4250 ms of 15000 ms, Facebook's start 0 ms, BY_USER"));
        } finally {
            BaseSettings.DEBUG.resetToDefault();
            LogBufferManager.clearLogBuffer();
        }
    }

    /** Since 581 a long video opened full screen plays in the reel viewer, which loops it. It resumes. */
    @Test
    public void aLongVideoInTheReelViewerResumes() {
        Params documentary = longVideo("444444444");
        documentary.reel = true;
        documentary.loop = true;
        leftAt(documentary, 3 * MINUTE);
        assertEquals(Collections.singletonList("BY_PLAYER@180000"), opened(documentary).seeks);
        assertTrue(report(), report().contains("point saved"));
    }

    @Test
    public void aSeekBeforeTheResumeWins() {
        Params params = longVideo("111111111");
        leftAt(params, 10 * MINUTE);

        // Your drag of the seek bar back to the start, before the resume runs.
        Player dragged = new Player(params);
        ResumePlayback.started(dragged, Trigger.BY_USER);
        ResumePlayback.seeking(Trigger.BY_SEEKBAR_CONTROLLER, dragged, 0);
        ResumePlaybackForTests.runLater();
        assertTrue("the resume ran over a drag", dragged.seeks.isEmpty());

        // Facebook carrying the feed's position over, before the start.
        Player carried = new Player(params);
        ResumePlayback.seeking(Trigger.BY_ABSOLUTE_SEEK_BY_TRANSITION, carried, 95_000);
        ResumePlayback.started(carried, Trigger.BY_USER);
        ResumePlaybackForTests.runLater();
        assertTrue("the resume ran over Facebook's own position", carried.seeks.isEmpty());

        // Facebook's own seek to the start, which isn't someone placing the video, lets it resume.
        Player reset = new Player(params);
        ResumePlayback.seeking(Trigger.BY_INITIAL_FRAME_PERFECT_SEEK, reset, 0);
        assertEquals(Collections.singletonList("BY_PLAYER@600000"), openedAfter(reset));
        assertTrue(report(), report().contains("seek before the resume 2"));

        // The seek bar on feed videos back to the start, with the same exact trigger as that reset.
        Player placed = new Player(params);
        ResumePlayback.started(placed, Trigger.BY_USER);
        ResumePlayback.placedByYou(placed);
        ResumePlayback.seeking(Trigger.BY_INITIAL_FRAME_PERFECT_SEEK, placed, 0);
        ResumePlaybackForTests.runLater();
        assertTrue("the resume ran over the feed bar", placed.seeks.isEmpty());
    }

    private static List<String> openedAfter(Player player) {
        ResumePlayback.started(player, Trigger.BY_USER);
        ResumePlaybackForTests.runLater();
        return player.seeks;
    }

    @Test
    public void aStartFacebookPlacedStaysWhereItIs() {
        Params params = longVideo("111111111");
        leftAt(params, 10 * MINUTE);

        Player carried = new Player(params);
        carried.position = ResumePlayback.START_WINDOW_MS + 1;
        assertTrue("a start past the start moved", openedAfter(carried).isEmpty());

        Params linked = longVideo("111111111");
        linked.start = 42_000;
        assertTrue("a link to a moment moved", opened(linked).seeks.isEmpty());
        assertTrue(report(), report().contains("started past the start 1")
                && report().contains("Facebook's own start point 1"));
    }

    @Test
    public void thePlayerMovingOnBeforeTheResumeRunsStopsIt() {
        Params params = longVideo("111111111");
        leftAt(params, 10 * MINUTE);
        Player player = new Player(params);
        ResumePlayback.started(player, Trigger.BY_USER);
        player.params = longVideo("333333333");
        ResumePlaybackForTests.runLater();
        assertTrue("the next video was sought to the last one's point", player.seeks.isEmpty());
        assertTrue(report(), report().contains("moved on before the resume 1"));
    }

    @Test
    public void theResumesOwnSeekSavesNothingAndStopsNothing() {
        Params params = longVideo("111111111");
        leftAt(params, 10 * MINUTE);
        Player player = new Player(params);
        player.position = 800;
        // Facebook's seek of a playing video pauses it first, which runs the stop hook at the old
        // position, and restarts it, which runs the start hook.
        player.duringSeek = () -> {
            ResumePlayback.stopped(player, Trigger.BY_PLAYER);
            ResumePlayback.started(player, Trigger.BY_PLAYER);
        };
        assertEquals(Collections.singletonList("BY_PLAYER@600000"), openedAfter(player));
        assertTrue(report(), report().contains("point saved 1") && report().contains("resumed 1"));
        // The point is still 10:00.
        assertEquals(Collections.singletonList("BY_PLAYER@600000"), opened(params).seeks);
    }

    @Test
    public void aNewBindStartsThePlayerOverUnlessItJustStarted() {
        Params params = longVideo("111111111");
        leftAt(params, 10 * MINUTE);
        Player player = new Player(params);
        assertEquals(1, openedAfter(player).size());

        // A bind right after the start is the one Facebook put off, not a new video.
        ResumePlayback.rebound(player);
        player.position = 0;
        assertEquals("a put-off bind resumed again", 1, openedAfter(player).size());

        // Later, the player given the same video again starts over. Its bind saves where it was.
        SystemClock.sleep(ResumePlayback.BIND_GRACE_MS + 1);
        player.position = 11 * MINUTE;
        ResumePlayback.rebound(player);
        player.position = 0;
        assertEquals(Collections.singletonList("BY_PLAYER@660000"), openedAfter(player).subList(1, 2));
    }

    @Test
    public void aReleaseSavesAndStartsThePlayerOver() {
        Params params = longVideo("111111111");
        Player player = new Player(params);
        ResumePlayback.started(player, Trigger.BY_USER);
        assertEquals(1, ResumePlayback.playersKnown());
        player.position = 25 * MINUTE;
        ResumePlayback.released(player, Trigger.BY_USER);
        assertEquals("the released player is still known", 0, ResumePlayback.playersKnown());
        assertEquals(Collections.singletonList("BY_PLAYER@1500000"), opened(params).seeks);
    }

    @Test
    public void thePointsOutliveTheProcess() {
        Params params = longVideo("111111111");
        leftAt(params, 10 * MINUTE);
        // A new process: nothing cached, the file read again.
        ResumePlaybackForTests.install();
        ShadowLooper.idleMainLooper();
        assertEquals(Collections.singletonList("BY_PLAYER@600000"), opened(params).seeks);
        assertEquals(1, RuntimeEnvironment.getApplication()
                .getSharedPreferences(ResumePoints.FILE, Context.MODE_PRIVATE).getAll().size());
    }

    @Test
    public void offOrPausedNothingIsSavedOrMoved() {
        Params params = longVideo("111111111");
        Settings.RESUME_LONG_VIDEOS.save(false);
        leftAt(params, 10 * MINUTE);
        Settings.RESUME_LONG_VIDEOS.save(true);
        assertTrue("a stop saved with the switch off", opened(params).seeks.isEmpty());

        leftAt(params, 10 * MINUTE);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertTrue("a paused start moved", opened(params).seeks.isEmpty());
        PauseForTests.resume();

        Settings.RESUME_LONG_VIDEOS.save(false);
        assertTrue("a start with the switch off moved", opened(params).seeks.isEmpty());
        Player late = new Player(params);
        Settings.RESUME_LONG_VIDEOS.save(true);
        ResumePlayback.started(late, Trigger.BY_USER);
        Settings.RESUME_LONG_VIDEOS.save(false);
        ResumePlaybackForTests.runLater();
        assertTrue("the switch turned off before the resume ran, and it moved", late.seeks.isEmpty());
    }

    @Test
    public void unfilledStubsAndOddPlayersFailOpen() {
        Params params = longVideo("111111111");
        leftAt(params, 10 * MINUTE);

        // The app's own stubs, as they are before the patch fills them.
        ResumePlayback.access = ResumePlayback.PATCHED;
        Player player = new Player(params);
        ResumePlayback.started(player, Trigger.BY_USER);
        ResumePlayback.stopped(player, Trigger.BY_USER);
        ResumePlaybackForTests.runLater();
        assertTrue(player.seeks.isEmpty());
        List<String> missing = HookStatus.missing(FamilyNames.RESUME_LONG_VIDEOS);
        assertTrue(missing.toString(), missing.toString().contains("the params getter"));
        assertEquals(ResumePlayback.NOT_PATCHED_TIME, ResumePlayback.position(player));
        assertEquals(ResumePlayback.NOT_PATCHED_TIME, ResumePlayback.duration(player));
        assertSame(ResumePlayback.NOT_PATCHED, ResumePlayback.playerParams(player));
        assertFalse(ResumePlayback.seekPlayer(player, Trigger.BY_PLAYER, 1));
        assertNull(ResumePlayback.paramFields());

        // A trigger enum without the seek's constant, a player with no params, a null player.
        ResumePlaybackForTests.install();
        Player odd = new Player(params);
        ResumePlayback.started(odd, TriggerWithoutPlayer.BY_USER);
        ResumePlaybackForTests.runLater();
        assertTrue(odd.seeks.isEmpty());
        assertTrue(HookStatus.missing(FamilyNames.RESUME_LONG_VIDEOS).toString().contains("BY_PLAYER"));
        ResumePlayback.started(new Player(null), Trigger.BY_USER);
        ResumePlayback.stopped(null, Trigger.BY_USER);
        ResumePlayback.seeking(null, null, 5);
        ResumePlayback.released(null, null);
        ResumePlayback.rebound(null);

        // A player whose params aren't the kind the names were read from.
        Player stranger = new Player(null);
        ResumePlayback.access = new ResumePlayback.Player() {
            @Override public int position(Object p) { return 0; }
            @Override public int duration(Object p) { return 0; }
            @Override public Object params(Object p) { return "not params"; }
            @Override public boolean seek(Object p, Object t, int ms) { throw new AssertionError("sought"); }
            @Override public String fields() { return ResumePlaybackForTests.FIELDS; }
        };
        ResumePlayback.started(stranger, Trigger.BY_USER);
        assertTrue(HookStatus.missing(FamilyNames.RESUME_LONG_VIDEOS).toString().contains("java.lang.String"));
    }

    @Test
    public void aThrowInsideLeavesThePlayerAlone() {
        Params params = longVideo("111111111");
        leftAt(params, 10 * MINUTE);
        ResumePlayback.access = new ResumePlayback.Player() {
            @Override public int position(Object p) { throw new IllegalStateException("position"); }
            @Override public int duration(Object p) { return 0; }
            @Override public Object params(Object p) { return ((Player) p).params; }
            @Override public boolean seek(Object p, Object t, int ms) { throw new IllegalStateException("seek"); }
            @Override public String fields() { return ResumePlaybackForTests.FIELDS; }
        };
        Player player = new Player(params);
        ResumePlayback.started(player, Trigger.BY_USER);
        ResumePlayback.stopped(player, Trigger.BY_USER);
        ResumePlayback.released(player, Trigger.BY_USER);
        ResumePlaybackForTests.runLater();
        assertTrue(player.seeks.isEmpty());
        String missing = HookStatus.missing(FamilyNames.RESUME_LONG_VIDEOS).toString();
        for (String hook : new String[]{"player start", "player stop", "player release"}) {
            assertTrue(missing, missing.contains("'" + hook + "' hook (it threw java.lang.IllegalStateException)"));
        }
        assertEquals("the release that threw kept the player", 0, ResumePlayback.playersKnown());
    }

    @Test
    public void positionsReadAsAPlayerShowsThem() {
        assertEquals("0:00", ResumePlayback.clock(0));
        assertEquals("12:34", ResumePlayback.clock(754_000));
        assertEquals("125:09", ResumePlayback.clock(125 * MINUTE + 9_999));
        assertEquals("0:00", ResumePlayback.clock(-5));
    }
}
