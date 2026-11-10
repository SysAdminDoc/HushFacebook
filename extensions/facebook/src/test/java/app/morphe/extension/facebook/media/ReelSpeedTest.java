/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
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
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.facebook.reels.ReelHold;
import app.morphe.extension.facebook.reels.ReelHoldForTests;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Keep the reel speed: a speed picked in a reel's menu is kept for the viewer the reel played in,
 * and the first start of each later video there gets it when the video is a reel that's neither an
 * ad nor live. A reel readied before the pick gets it when it starts again, the same reel started
 * again keeps whatever it's at, another viewer keeps its own speed, normal speed goes back to
 * Facebook's reset, and off, paused or failing, every reel starts as Facebook starts it. Keep the
 * video speed, the second switch, does the same for feed and Watch videos with one speed for all.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReelSpeedTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String REELS = "fb_shorts_viewer";
    /** Where accounts whose Reels live in the Video tab play them. */
    private static final String VIDEO_TAB = "video_home";
    private static final String IN_FEED = "fb_shorts_native_in_feed_unit";
    /** A feed video's viewer, for Keep the video speed. */
    private static final String FEED = "newsfeed";

    /** What a player's VideoPlayerParams say about the video it bound. */
    private static final class Video {
        final boolean reel;
        final boolean ad;
        final boolean live;

        Video(boolean reel, boolean ad, boolean live) {
            this.reel = reel;
            this.ad = ad;
            this.live = live;
        }
    }

    private static final Video REEL = new Video(true, false, false);
    private static final Video AD = new Video(true, true, false);
    private static final Video LIVE = new Video(true, false, true);
    private static final Video NOT_A_REEL = new Video(false, false, false);
    private static final Video VIDEO_AD = new Video(false, true, false);
    private static final Video LIVE_VIDEO = new Video(false, false, true);

    /** Stands in for FbGrootPlayer: its PlayerOrigin's toString, the params of the video it binds and the speeds set on it. */
    private static final class FakePlayers implements ReelSpeed.Player {
        final Map<Object, String> origins = new IdentityHashMap<>();
        final Map<Object, Video> kinds = new IdentityHashMap<>();
        final Map<Object, Object> params = new IdentityHashMap<>();
        final List<String> set = new ArrayList<>();
        /** The speed each player plays at, normal until one is set. */
        final Map<Object, Float> speeds = new IdentityHashMap<>();
        RuntimeException failure;

        /** A player from [origin] whose videos are reels that are neither ads nor live. */
        Object player(String origin) {
            return player(origin, REEL);
        }

        Object player(String origin, Video kind) {
            Object player = new Object();
            origins.put(player, origin);
            kinds.put(player, kind);
            return player;
        }

        /** The video id each params object reports, by identity; none for params bound without one. */
        final Map<Object, String> ids = new IdentityHashMap<>();

        /** [player] binds a new video: new params, as Facebook builds for every video. */
        void bind(Object player) {
            params.put(player, new Video[] {kinds.get(player)});
        }

        /** [player] binds the video [id]: new params reporting it. */
        void bind(Object player, String id) {
            bind(player);
            ids.put(params.get(player), id);
        }

        @Override
        public void setSpeed(Object player, float speed) {
            if (failure != null) throw failure;
            set.add(origins.get(player) + " " + speed);
            // Facebook's setter is hooked too, so the extension hears its own change.
            ReelSpeed.speedSet(player, speed);
            speeds.put(player, speed);
        }

        double speed(Object player) {
            Float speed = speeds.get(player);
            return speed == null ? ReelSpeed.NORMAL : speed;
        }

        @Override
        public Object origin(Object player) {
            String origin = origins.get(player);
            return origin == null ? null : new Object() {
                @Override
                public String toString() {
                    return origin;
                }
            };
        }

        @Override
        public Object params(Object player) {
            return params.get(player);
        }

        private static Video video(Object params) {
            return ((Video[]) params)[0];
        }

        @Override
        public boolean reel(Object params) {
            return video(params).reel;
        }

        @Override
        public boolean ad(Object params) {
            return video(params).ad;
        }

        @Override
        public boolean live(Object params) {
            return video(params).live;
        }

        @Override
        public String videoId(Object params) {
            return ids.get(params);
        }

        @Override
        public float currentSpeed(Object player) {
            return (float) speed(player);
        }
    }

    private FakePlayers players;

    @Before
    public void start() {
        SystemClock.sleep(60_000);
        ReelSpeed.forget();
        players = new FakePlayers();
        ReelSpeed.access = players;
        HookStatus.clear();
        // The patch is in Morphe Manager's default selection with its switch off; these tests run with it on.
        Settings.HOLD_REEL_FOR_2X.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.KEEP_REEL_SPEED.resetToDefault();
        Settings.KEEP_VIDEO_SPEED.resetToDefault();
        Settings.SLOWER_REEL_SPEEDS.resetToDefault();
        Settings.FASTER_REEL_SPEEDS.resetToDefault();
        Settings.POST_MENU_SPEED.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        LogBufferManager.clearLogBuffer();
        ReelSpeed.forget();
        ReelHoldForTests.forget();
        HookStatus.clear();
        Settings.HOLD_REEL_FOR_2X.resetToDefault();
    }

    /** A pick in the Reels menu as Facebook makes it: the speed set on the reel's player, then the toast. */
    private static void pick(Object player, float speed) {
        ReelSpeed.speedSet(player, speed);
        SystemClock.sleep(150);
        ReelSpeed.picked(speed);
    }

    /** A pick in the gear menu's sheet as Facebook makes it: the speed set on the player, with no toast after it. */
    private static void gearPick(Object player, float speed) {
        ReelSpeed.speedSet(player, speed);
        ReelSpeed.gearPicked(speed);
    }

    /** A reel coming on screen: its player binds the video, then starts playing it. */
    private void play(Object player) {
        players.bind(player);
        ReelSpeed.started(player);
    }

    /** The same video started again, after a pause or once it's on screen. */
    private static void resume(Object player) {
        ReelSpeed.started(player);
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.KEEP_REEL_SPEED + ":")) return line;
        }
        return null;
    }

    @Test
    public void aPickedSpeedCarriesToEveryNextReelOfTheViewer() {
        assertTrue("the switch starts off", Settings.KEEP_REEL_SPEED.get());
        Object first = players.player(REELS + "::reels_tab");
        play(first);
        assertEquals("a reel started before any pick was changed", 0, players.set.size());
        pick(first, 1.5f);
        assertEquals(1.5f, ReelSpeed.kept(REELS), 0f);

        Object second = players.player(REELS + "::reels_tab");
        play(second);
        // Pooled players come back for later reels, and a reel can start where another left off.
        play(first);
        Object third = players.player(REELS + "::feed_chaining");
        play(third);
        assertEquals(List.of(REELS + "::reels_tab 1.5", REELS + "::reels_tab 1.5", REELS + "::feed_chaining 1.5"),
                players.set);
        assertEquals(FamilyNames.KEEP_REEL_SPEED + ": invoked 9, 2 found, 0 missing. Counted: "
                + ReelSpeed.APPLIED + " 3", statusLine());
    }

    /**
     * Some accounts get their Reels in the Video tab, where reels play under video_home beside the
     * tab's other videos. A pick on a reel there carries to the tab's next reels, and the tab's
     * other videos, its ads and other viewers keep Facebook's speed.
     */
    @Test
    public void theVideoTabsReelsKeepTheirSpeedToo() {
        Object reel = players.player(VIDEO_TAB);
        play(reel);
        pick(reel, 2f);
        assertEquals(2f, ReelSpeed.kept(VIDEO_TAB), 0f);
        play(players.player(VIDEO_TAB));
        play(players.player(VIDEO_TAB + "::fb_shorts_in_watch_tab"));
        play(players.player(VIDEO_TAB, NOT_A_REEL));
        play(players.player(VIDEO_TAB, AD));
        play(players.player(REELS));
        assertEquals(List.of(VIDEO_TAB + " 2.0", VIDEO_TAB + "::fb_shorts_in_watch_tab 2.0"), players.set);
    }

    /**
     * Some accounts get Playback speed in a reel's More menu from the gear menu's sheet, which shows no
     * toast (issue #25). A pick there carries to the next reels like a pick in the Reels menu.
     */
    @Test
    public void aPickInTheGearSheetOnAReelCarriesToTheNextReels() {
        Object reel = players.player(VIDEO_TAB);
        play(reel);
        gearPick(reel, 1.5f);
        assertEquals(1.5f, ReelSpeed.kept(VIDEO_TAB), 0f);
        play(players.player(VIDEO_TAB));
        assertEquals(List.of(VIDEO_TAB + " 1.5"), players.set);
        gearPick(reel, 1f);
        play(players.player(VIDEO_TAB));
        assertEquals("normal speed in the gear sheet didn't go back to Facebook's reset",
                List.of(VIDEO_TAB + " 1.5"), players.set);
    }

    /**
     * On some accounts the gear sheet can't find a reel's player, so it checks Normal, and the checked
     * row can't be picked: a kept speed couldn't be undone there (#199). It checks the speed the reel
     * on screen plays at instead, so Normal can be picked, and that forgets the kept speed. A speed
     * the sheet found itself stays, and so does Normal with the switch off or nothing started yet.
     */
    @Test
    public void theGearSheetChecksTheSpeedTheReelOnScreenPlaysAt() {
        assertEquals("nothing started yet, and the sheet's normal speed changed", 1f, ReelSpeed.gearSheetSpeed(1f), 0f);
        Object reel = players.player(REELS);
        play(reel);
        players.setSpeed(reel, 1.5f);
        ReelSpeed.gearPicked(1.5f);
        assertEquals(1.5f, ReelSpeed.kept(REELS), 0f);
        Object next = players.player(REELS);
        play(next);
        assertEquals("the sheet didn't check the kept speed the reel on screen plays at", 1.5f, ReelSpeed.gearSheetSpeed(1f), 0f);
        assertEquals("a speed the sheet found was changed", 2f, ReelSpeed.gearSheetSpeed(2f), 0f);
        Settings.KEEP_REEL_SPEED.save(false);
        assertEquals("with the switch off, the sheet's normal speed changed", 1f, ReelSpeed.gearSheetSpeed(1f), 0f);
        Settings.KEEP_REEL_SPEED.save(true);

        players.setSpeed(next, 1f);
        ReelSpeed.gearPicked(1f);
        assertEquals("normal speed picked in the sheet didn't forget the kept speed", 1f, ReelSpeed.kept(REELS), 0f);
        assertEquals(1f, ReelSpeed.gearSheetSpeed(1f), 0f);
        play(players.player(REELS));
        assertEquals(List.of(REELS + " 1.5", REELS + " 1.5", REELS + " 1.0"), players.set);
    }

    /** A Watch video's gear menu has the same sheet; a speed picked there stays with that video. */
    @Test
    public void aGearPickOnAVideoThatIsntAReelIsntKept() {
        Object video = players.player(VIDEO_TAB, NOT_A_REEL);
        play(video);
        gearPick(video, 2f);
        assertEquals("a gear pick on a Watch video was kept", 1f, ReelSpeed.kept(VIDEO_TAB), 0f);
        play(players.player(VIDEO_TAB));
        assertEquals("a reel after a Watch video's gear pick was sped up", List.of(), players.set);
    }

    /**
     * A gear pick that isn't kept says why with Debug logging on: no player was just set to its speed,
     * its player's video couldn't be read, or the video isn't a reel. One line said "isn't a reel"
     * for all three.
     */
    @Test
    public void aGearPickThatIsntKeptSaysWhy() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        ReelSpeed.gearPicked(1.5f);
        gearPick(players.player(VIDEO_TAB), 1.25f);
        Object video = players.player(VIDEO_TAB, NOT_A_REEL);
        play(video);
        gearPick(video, 2f);
        assertEquals("a gear pick that should have stayed with its video was kept", 1f, ReelSpeed.kept(VIDEO_TAB), 0f);
        String log = LogBufferManager.buildExportText();
        assertTrue(log, log.contains("Reel speed: 1.5x picked in the gear menu, but no player found that was just set to it, so it isn't kept"));
        assertTrue(log, log.contains("Reel speed: 1.25x picked in the gear menu, but its player's video couldn't be read, so it isn't kept"));
        assertTrue(log, log.contains("Reel speed: 2.0x picked in the gear menu on a video that isn't a reel, it stays with that video"));
        assertFalse(log, log.contains("Reel speed: 1.5x picked in the gear menu on a video that isn't a reel"));
        assertFalse(log, log.contains("Reel speed: 1.25x picked in the gear menu on a video that isn't a reel"));
    }

    /**
     * Facebook readies the next reel before you get to it, and can start it early. It gets the speed
     * picked after that when it starts again on screen, once.
     */
    @Test
    public void aReelStartedBeforeThePickGetsItWhenItStartsAgain() {
        Object current = players.player(REELS);
        play(current);
        Object next = players.player(REELS);
        play(next);
        pick(current, 1.5f);
        resume(next);
        resume(next);
        resume(current);
        assertEquals("the reel readied before the pick didn't get it once, or the picked reel got it again",
                List.of(REELS + " 1.5"), players.set);
    }

    /** Paused and started again, a reel stays at what it's at: a 2x hold, say, lasts until the next reel. */
    @Test
    public void theSameReelStartedAgainKeepsItsSpeed() {
        Object reel = players.player(REELS);
        play(reel);
        pick(reel, 0.5f);
        Object next = players.player(REELS);
        play(next);
        ReelSpeed.speedSet(next, 2f);
        resume(next);
        resume(next);
        assertEquals(List.of(REELS + " 0.5"), players.set);
    }

    /**
     * With Hold a reel for 2x in too, a hold on a reel at the kept speed ends at that speed, and
     * neither its speed-up nor its lift is a pick. Both go through the player's setter, where Keep the
     * reel speed's hook runs first, as Morphe puts the two in: it hears the normal speed Facebook's
     * lift sets, and Hold a reel for 2x's hook then puts the kept speed back in its place.
     */
    @Test
    public void aHoldOnAReelAtTheKeptSpeedEndsThereAndPicksNothing() {
        Object reel = players.player(VIDEO_TAB);
        play(reel);
        pick(reel, 2f);
        Object next = players.player(VIDEO_TAB);
        play(next);
        ReelHoldForTests.holdAndLift(next, players::speed, (player, speed) -> {
            ReelSpeed.speedSet(player, speed);
            players.speeds.put(player, ReelHold.speedSet(player, speed));
        }, 1f);
        assertEquals("the held reel didn't go back to the kept speed", 2.0, players.speed(next), 0.0);
        SystemClock.sleep(150);
        assertEquals(2f, ReelSpeed.kept(VIDEO_TAB), 0f);
        play(players.player(VIDEO_TAB));
        assertEquals(List.of(VIDEO_TAB + " 2.0", VIDEO_TAB + " 2.0"), players.set);
    }

    /**
     * Facebook's Reels viewer plays ads and live videos between reels, and its speed menu may not
     * offer a speed there; a live video sped up runs into its live edge. They, and a video in the
     * viewer that isn't a reel, start at Facebook's speed while the next reel gets the kept one.
     */
    @Test
    public void anAdALiveVideoOrAnotherVideoInTheViewerStartsAtFacebooksSpeed() {
        Object reel = players.player(REELS);
        play(reel);
        pick(reel, 1.5f);
        play(players.player(REELS + "::reels_tab", AD));
        play(players.player(REELS, LIVE));
        play(players.player(REELS, NOT_A_REEL));
        assertEquals("an ad, a live video or a video that isn't a reel got the kept speed", 0, players.set.size());
        play(players.player(REELS));
        assertEquals(List.of(REELS + " 1.5"), players.set);
    }

    /**
     * Each viewer keeps its own speed: a pick on a reel in the feed carries to the feed's next reels
     * only, and doesn't take the place of, or forget, the Reels viewer's.
     */
    @Test
    public void aPickInOneViewerStaysThere() {
        Object inFeed = players.player(IN_FEED + "::newsfeed");
        play(inFeed);
        pick(inFeed, 2f);
        assertEquals(2f, ReelSpeed.kept(IN_FEED), 0f);
        assertEquals("an in-feed pick changed the Reels viewer's speed", 1f, ReelSpeed.kept(REELS), 0f);
        play(players.player(IN_FEED));
        play(players.player(REELS));
        assertEquals(List.of(IN_FEED + " 2.0"), players.set);

        Object reel = players.player(REELS);
        play(reel);
        pick(reel, 1.5f);
        pick(inFeed, 1f);
        assertEquals(1f, ReelSpeed.kept(IN_FEED), 0f);
        assertEquals("normal speed in the feed forgot the Reels viewer's speed", 1.5f, ReelSpeed.kept(REELS), 0f);
        play(players.player(IN_FEED));
        play(players.player(REELS));
        assertEquals(List.of(IN_FEED + " 2.0", REELS + " 1.5"), players.set);
    }

    /**
     * Normal picked on a video already at normal, an ad in the Reels viewer say, sets no speed, so
     * its toast has no player to go by. It forgets the speed of the viewer whose video started last,
     * the one on screen, and no other. With nothing started yet it forgets nothing.
     */
    @Test
    public void normalPickedOnAVideoAtNormalForgetsOnlyTheViewerOnScreen() {
        Object inFeed = players.player(IN_FEED + "::newsfeed");
        pick(inFeed, 2f);
        ReelSpeed.picked(1f);
        assertEquals("normal with nothing on screen forgot a speed", 2f, ReelSpeed.kept(IN_FEED), 0f);

        Object reel = players.player(REELS);
        play(reel);
        pick(reel, 1.5f);
        play(players.player(REELS, AD));
        ReelSpeed.picked(1f);
        assertEquals("normal in the Reels viewer kept its speed", 1f, ReelSpeed.kept(REELS), 0f);
        assertEquals("normal in the Reels viewer forgot the feed's speed", 2f, ReelSpeed.kept(IN_FEED), 0f);
        play(players.player(REELS));
        play(players.player(IN_FEED));
        assertEquals(List.of(IN_FEED + " 2.0"), players.set);
    }

    @Test
    public void aPlayerInAnotherViewerStartsAtFacebooksSpeed() {
        Object reel = players.player(REELS);
        play(reel);
        pick(reel, 1.5f);
        play(players.player("newsfeed"));
        play(players.player("video_home::fb_shorts_viewer"));
        Object noOrigin = new Object();
        players.bind(noOrigin);
        ReelSpeed.started(noOrigin);
        assertEquals(0, players.set.size());
    }

    @Test
    public void normalSpeedGoesBackToFacebooksReset() {
        Object reel = players.player(REELS);
        play(reel);
        pick(reel, 2f);
        pick(reel, 1f);
        assertEquals(1f, ReelSpeed.kept(REELS), 0f);
        play(players.player(REELS));
        assertEquals(0, players.set.size());
    }

    /** The toast names the change it follows; a toast with no such change just before keeps nothing. */
    @Test
    public void aToastWithoutItsSpeedChangeKeepsNothing() {
        Object reel = players.player(REELS);
        play(reel);
        ReelSpeed.speedSet(reel, 1.25f);
        ReelSpeed.picked(1.5f);
        assertEquals("a toast for another speed kept one", 1f, ReelSpeed.kept(REELS), 0f);
        ReelSpeed.speedSet(reel, 1.5f);
        SystemClock.sleep(ReelSpeed.PICK_WINDOW_MS + 1);
        ReelSpeed.picked(1.5f);
        assertEquals("a toast long after the change kept it", 1f, ReelSpeed.kept(REELS), 0f);
        ReelSpeed.picked(1.5f);
        assertEquals(1f, ReelSpeed.kept(REELS), 0f);
        play(players.player(REELS));
        assertEquals(0, players.set.size());
    }

    @Test
    public void offNothingIsKeptOrApplied() {
        Settings.KEEP_REEL_SPEED.save(false);
        Object reel = players.player(REELS);
        play(reel);
        pick(reel, 1.5f);
        Settings.KEEP_REEL_SPEED.save(true);
        play(players.player(REELS));
        assertEquals("a pick made while off was kept", 0, players.set.size());

        pick(reel, 1.5f);
        Settings.KEEP_REEL_SPEED.save(false);
        play(players.player(REELS));
        assertEquals("a reel started while off got the kept speed", 0, players.set.size());
    }

    @Test
    public void pausedEveryReelStartsAsFacebookStartsIt() {
        Object reel = players.player(REELS);
        play(reel);
        pick(reel, 1.5f);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            play(players.player(REELS));
            assertEquals(reason.name(), 0, players.set.size());
        }
        PauseForTests.resume();
        play(players.player(REELS));
        assertEquals(List.of(REELS + " 1.5"), players.set);
    }

    @Test
    public void aFailingSetterIsReportedAndTheReelPlaysOn() {
        Object reel = players.player(REELS);
        play(reel);
        pick(reel, 1.5f);
        players.failure = new IllegalStateException("the setter failed");
        play(players.player(REELS));
        assertTrue(HookStatus.missing(FamilyNames.KEEP_REEL_SPEED).get(0)
                .startsWith("a working 'player start' hook (it threw "));
    }

    /** Until the patch fills the stubs in, no player has a viewer, so nothing is kept. */
    @Test
    public void unpatchedStubsKeepNothing() {
        ReelSpeed.access = ReelSpeed.PATCHED;
        Object reel = new Object();
        pick(reel, 1.5f);
        assertEquals(1f, ReelSpeed.kept(REELS), 0f);
    }

    // ------------------------------------------------------------------ Keep the video speed

    /**
     * With Keep the video speed on, a speed picked in a video's gear menu carries to every next
     * video that isn't a reel, in any feed or Watch viewer. Reels, ads, live videos and stories
     * start at Facebook's speed.
     */
    @Test
    public void withKeepTheVideoSpeedAGearPickCarriesToTheNextVideos() {
        Settings.KEEP_VIDEO_SPEED.save(true);
        Object video = players.player(VIDEO_TAB, NOT_A_REEL);
        play(video);
        gearPick(video, 1.5f);
        assertEquals(1.5f, ReelSpeed.videoKept(), 0f);
        assertEquals("a video's pick was kept for the tab's reels", 1f, ReelSpeed.kept(VIDEO_TAB), 0f);

        play(players.player(FEED, NOT_A_REEL));
        play(players.player(VIDEO_TAB + "::watch_feed", NOT_A_REEL));
        // Pooled players come back for later videos.
        play(video);
        play(players.player(REELS));
        play(players.player(FEED, VIDEO_AD));
        play(players.player(FEED, LIVE_VIDEO));
        play(players.player("fb_stories_viewer", NOT_A_REEL));
        assertEquals(List.of(FEED + " 1.5", VIDEO_TAB + "::watch_feed 1.5", VIDEO_TAB + " 1.5"), players.set);
        assertEquals(FamilyNames.KEEP_REEL_SPEED + ": invoked 13, 2 found, 0 missing. Counted: "
                + ReelSpeed.VIDEO_APPLIED + " 3", statusLine());
    }

    // ------------------------------------------------------------------ through a restart

    /**
     * The kept speeds are saved, so after Facebook restarts the next reel and the next feed video
     * start at them with no new pick (#199). The file holds each viewer's origin and speed, nothing
     * about a video, and normal speed takes a viewer's speed back out of it.
     */
    @Test
    public void keptSpeedsLastThroughARestart() {
        Settings.KEEP_VIDEO_SPEED.save(true);
        Object reel = players.player(REELS);
        play(reel);
        pick(reel, 1.5f);
        Object video = players.player(FEED, NOT_A_REEL);
        play(video);
        gearPick(video, 1.25f);
        Object tab = players.player(VIDEO_TAB);
        play(tab);
        pick(tab, 2f);
        assertEquals(Map.of("reel:" + REELS, 1.5f, "reel:" + VIDEO_TAB, 2f, "video", 1.25f), savedFile().getAll());

        ReelSpeed.restart();
        players.set.clear();
        play(players.player(REELS));
        play(players.player(FEED, NOT_A_REEL));
        Object tabAgain = players.player(VIDEO_TAB);
        play(tabAgain);
        assertEquals(List.of(REELS + " 1.5", FEED + " 1.25", VIDEO_TAB + " 2.0"), players.set);

        pick(tabAgain, 1f);
        ReelSpeed.restart();
        players.set.clear();
        play(players.player(VIDEO_TAB));
        play(players.player(REELS));
        assertEquals(List.of(REELS + " 1.5"), players.set);
        assertEquals(Map.of("reel:" + REELS, 1.5f, "video", 1.25f), savedFile().getAll());
    }

    /**
     * After a restart the saved speeds stay put while the switches are off, and a saved value no
     * menu could have picked is skipped.
     */
    @Test
    public void savedSpeedsWaitForTheirSwitchAndSkipOddValues() {
        savedFile().edit()
                .putFloat("reel:" + REELS, 1.5f)
                .putFloat("reel:" + IN_FEED, 9f)
                .putFloat("reel:" + VIDEO_TAB, Float.NaN)
                .putString("video", "1.5")
                .commit();
        Settings.KEEP_REEL_SPEED.save(false);
        play(players.player(REELS));
        assertEquals(List.of(), players.set);

        Settings.KEEP_REEL_SPEED.save(true);
        Settings.KEEP_VIDEO_SPEED.save(true);
        play(players.player(REELS));
        play(players.player(IN_FEED));
        play(players.player(VIDEO_TAB));
        play(players.player(FEED, NOT_A_REEL));
        assertEquals(List.of(REELS + " 1.5"), players.set);
    }

    private static SharedPreferences savedFile() {
        return RuntimeEnvironment.getApplication().getSharedPreferences(ReelSpeed.FILE, Context.MODE_PRIVATE);
    }

    /** Keep the video speed starts off, and off, a gear pick on a video stays with that video. */
    @Test
    public void withKeepTheVideoSpeedOffEveryVideoStartsAsFacebookStartsIt() {
        assertFalse("Keep the video speed starts on", Settings.KEEP_VIDEO_SPEED.get());
        Object video = players.player(VIDEO_TAB, NOT_A_REEL);
        play(video);
        gearPick(video, 1.5f);
        assertEquals(1f, ReelSpeed.videoKept(), 0f);
        play(players.player(FEED, NOT_A_REEL));
        assertEquals(List.of(), players.set);

        Settings.KEEP_VIDEO_SPEED.save(true);
        gearPick(video, 1.5f);
        Settings.KEEP_VIDEO_SPEED.save(false);
        play(players.player(FEED, NOT_A_REEL));
        assertEquals("a video started while off got the kept speed", List.of(), players.set);
    }

    /** In the Video tab, where reels and Watch videos share a viewer, each keeps its own speed. */
    @Test
    public void reelsAndVideosKeepTheirOwnSpeeds() {
        Settings.KEEP_VIDEO_SPEED.save(true);
        Object reel = players.player(VIDEO_TAB);
        play(reel);
        gearPick(reel, 2f);
        Object video = players.player(VIDEO_TAB, NOT_A_REEL);
        play(video);
        gearPick(video, 1.25f);
        assertEquals(2f, ReelSpeed.kept(VIDEO_TAB), 0f);
        assertEquals(1.25f, ReelSpeed.videoKept(), 0f);
        play(players.player(VIDEO_TAB));
        play(players.player(VIDEO_TAB, NOT_A_REEL));
        assertEquals(List.of(VIDEO_TAB + " 2.0", VIDEO_TAB + " 1.25"), players.set);
    }

    /** With Keep the reel speed off and Keep the video speed on, reels start as Facebook starts them. */
    @Test
    public void withOnlyKeepTheVideoSpeedOnReelsStartAsFacebookStartsThem() {
        Settings.KEEP_REEL_SPEED.save(false);
        Settings.KEEP_VIDEO_SPEED.save(true);
        Object reel = players.player(REELS);
        play(reel);
        pick(reel, 2f);
        gearPick(reel, 2f);
        assertEquals(1f, ReelSpeed.kept(REELS), 0f);
        assertEquals("a reel's pick was kept for videos", 1f, ReelSpeed.videoKept(), 0f);
        Object video = players.player(VIDEO_TAB, NOT_A_REEL);
        play(video);
        gearPick(video, 1.5f);
        play(players.player(REELS));
        play(players.player(FEED, NOT_A_REEL));
        assertEquals(List.of(FEED + " 1.5"), players.set);
    }

    @Test
    public void normalSpeedInAVideosGearMenuGoesBackToFacebooksSpeed() {
        Settings.KEEP_VIDEO_SPEED.save(true);
        Object video = players.player(FEED, NOT_A_REEL);
        play(video);
        gearPick(video, 1.5f);
        Object next = players.player(FEED, NOT_A_REEL);
        play(next);
        gearPick(next, 1f);
        assertEquals(1f, ReelSpeed.videoKept(), 0f);
        play(players.player(FEED, NOT_A_REEL));
        assertEquals(List.of(FEED + " 1.5"), players.set);
    }

    /** A speed picked on a story, in a chat, on an ad or on a live video stays with that video. */
    @Test
    public void aVideoSpeedPickedOutsideTheFeedAndWatchIsntKept() {
        Settings.KEEP_VIDEO_SPEED.save(true);
        for (Object video : List.of(players.player("fb_stories_viewer", NOT_A_REEL),
                players.player("messenger_thread", NOT_A_REEL), players.player(FEED, VIDEO_AD),
                players.player(FEED, LIVE_VIDEO))) {
            play(video);
            gearPick(video, 2f);
            assertEquals(1f, ReelSpeed.videoKept(), 0f);
        }
        assertTrue(ReelSpeed.videoViewer("feed_story"));
        assertTrue(ReelSpeed.videoViewer(VIDEO_TAB + "::watch_feed"));
        assertFalse(ReelSpeed.videoViewer(null));
        assertFalse(ReelSpeed.videoViewer("story_viewer"));
        assertFalse(ReelSpeed.videoViewer("stories_tray"));
        assertFalse(ReelSpeed.videoViewer("composer_preview"));
        assertFalse(ReelSpeed.videoViewer("living_room"));
    }

    @Test
    public void pausedEveryVideoStartsAsFacebookStartsIt() {
        Settings.KEEP_VIDEO_SPEED.save(true);
        Object video = players.player(FEED, NOT_A_REEL);
        play(video);
        gearPick(video, 1.5f);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            play(players.player(FEED, NOT_A_REEL));
            assertEquals(reason.name(), 0, players.set.size());
        }
        PauseForTests.resume();
        play(players.player(FEED, NOT_A_REEL));
        assertEquals(List.of(FEED + " 1.5"), players.set);
    }

    /** #95: with the switch on, both Reels speed pickers offer 0.1x and 0.25x ahead of Facebook's speeds. */
    @Test
    public void theReelsMenuOffersSlowerSpeedsFirst() {
        List<Float> facebooks = Arrays.asList(0.5f, 1f, 2f, 2.5f, 3f);
        assertFalse("the switch starts off", Settings.SLOWER_REEL_SPEEDS.get());
        assertSame("off, Facebook's list stands", facebooks, ReelSpeed.speedChoices(facebooks));

        Settings.SLOWER_REEL_SPEEDS.save(true);
        assertEquals(Arrays.asList(0.1f, 0.25f, 0.5f, 1f, 2f, 2.5f, 3f), ReelSpeed.speedChoices(facebooks));
        assertEquals("the other list Facebook can offer", Arrays.asList(0.1f, 0.25f, 0.5f, 1f, 1.5f, 2f),
                ReelSpeed.speedChoices(Arrays.asList(0.5f, 1f, 1.5f, 2f)));
        assertEquals("a speed Facebook already offers isn't offered twice", Arrays.asList(0.1f, 0.25f, 1f),
                ReelSpeed.speedChoices(Arrays.asList(0.25f, 1f)));
        assertEquals(FamilyNames.KEEP_REEL_SPEED + ": invoked 4, 1 found, 0 missing. Counted: "
                + ReelSpeed.SLOWER_OFFERED + " 3", statusLine());
    }

    /** #430: with Faster speeds on, both Reels speed pickers offer 2.5x and 3x after Facebook's fastest. */
    @Test
    public void theReelsMenuOffersFasterSpeedsLast() {
        List<Float> facebooks = Arrays.asList(0.5f, 1f, 1.5f, 2f);
        assertFalse("the switch starts off", Settings.FASTER_REEL_SPEEDS.get());
        assertSame("off, Facebook's list stands", facebooks, ReelSpeed.speedChoices(facebooks));

        Settings.FASTER_REEL_SPEEDS.save(true);
        assertEquals(Arrays.asList(0.5f, 1f, 1.5f, 2f, 2.5f, 3f), ReelSpeed.speedChoices(facebooks));
        List<Float> other = Arrays.asList(0.5f, 1f, 2f, 2.5f, 3f);
        assertSame("the other list Facebook can offer already has both", other, ReelSpeed.speedChoices(other));
        assertEquals("only the speeds past Facebook's fastest are added", Arrays.asList(1f, 2.5f, 3f),
                ReelSpeed.speedChoices(Arrays.asList(1f, 2.5f)));

        Settings.SLOWER_REEL_SPEEDS.save(true);
        assertEquals("with Slower speeds on too, both ends get theirs",
                Arrays.asList(0.1f, 0.25f, 0.5f, 1f, 1.5f, 2f, 2.5f, 3f), ReelSpeed.speedChoices(facebooks));
        assertEquals(FamilyNames.KEEP_REEL_SPEED + ": invoked 5, 1 found, 0 missing. Counted: "
                + ReelSpeed.FASTER_OFFERED + " 3, " + ReelSpeed.SLOWER_OFFERED + " 1", statusLine());

        Settings.SLOWER_REEL_SPEEDS.save(false);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertSame("paused, Facebook's list stands", facebooks, ReelSpeed.speedChoices(facebooks));
        PauseForTests.resume();
        assertEquals("the control: running, the faster speeds come back", 6, ReelSpeed.speedChoices(facebooks).size());
    }

    @Test
    public void pausedOrUnreadableTheReelsMenuKeepsFacebooksSpeeds() {
        Settings.SLOWER_REEL_SPEEDS.save(true);
        List<Float> facebooks = Arrays.asList(0.5f, 1f, 1.5f, 2f);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertSame(reason.name(), facebooks, ReelSpeed.speedChoices(facebooks));
        }
        PauseForTests.resume();
        assertEquals("the control: running, the slower speeds come back", 6, ReelSpeed.speedChoices(facebooks).size());

        List<Object> unreadable = Arrays.asList("0.5", 1f);
        assertSame("a list that isn't speeds stands", unreadable, ReelSpeed.speedChoices(unreadable));
        assertTrue(statusLine(), HookStatus.missing(FamilyNames.KEEP_REEL_SPEED).contains("a working 'speed menu' hook (it threw "
                + ClassCastException.class.getName() + ")"));
    }

    /**
     * #95: the gear menu's speed sheet reads each speed from its float with the switch on, and gets
     * 0.1x and 0.25x ahead of its own speeds, each with its label, the two arrays still in step.
     */
    @Test
    public void theGearSheetOffersSlowerSpeedsWithTheirLabels() {
        float[] facebooks = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
        String[] labels = {"0.5", "0.75", "1", "1.25", "1.5", "1.75", "2"};
        assertFalse("off, Facebook's flag stands", ReelSpeed.gearValues(false));
        assertTrue("a flag Facebook set stays set", ReelSpeed.gearValues(true));
        assertSame("off, Facebook's speeds stand", facebooks, ReelSpeed.gearSpeeds(facebooks));
        assertSame("off, Facebook's labels stand", labels, ReelSpeed.gearLabels(labels));

        Settings.SLOWER_REEL_SPEEDS.save(true);
        assertTrue("on, the sheet reads its speeds from their floats", ReelSpeed.gearValues(false));
        float[] speeds = ReelSpeed.gearSpeeds(facebooks);
        String[] named = ReelSpeed.gearLabels(labels);
        assertEquals("[0.1, 0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0]", Arrays.toString(speeds));
        assertEquals(Arrays.asList("0.1", "0.25", "0.5", "0.75", "1", "1.25", "1.5", "1.75", "2"), Arrays.asList(named));
        assertSame("the labels were taken once", labels, ReelSpeed.gearLabels(labels));

        // A server list that already starts at 0.25x gets only 0.1x, and the labels get one too.
        float[] server = ReelSpeed.gearSpeeds(new float[] {0.25f, 1f, 2f});
        assertEquals("[0.1, 0.25, 1.0, 2.0]", Arrays.toString(server));
        assertEquals(Arrays.asList("0.1", "0.25", "1.0", "2.0"),
                Arrays.asList(ReelSpeed.gearLabels(new String[] {"0.25", "1.0", "2.0"})));

        // Seen on a phone in German: Facebook's own labels read "0,5x", and the added ones match them.
        ReelSpeed.gearSpeeds(facebooks);
        assertEquals(Arrays.asList("0,1x", "0,25x", "0,5x", "0,75x", "1x (Normal)"), Arrays.asList(ReelSpeed.gearLabels(
                new String[] {"0,5x", "0,75x", "1x (Normal)"})));
        assertEquals(FamilyNames.KEEP_REEL_SPEED + ": invoked 4, 1 found, 0 missing. Counted: "
                + ReelSpeed.GEAR_SLOWER_OFFERED + " 3", statusLine());
    }

    /**
     * #430: the gear sheet gets 2.5x and 3x after its own speeds with Faster speeds on, labelled like
     * the sheet's own, and Slower speeds still adds its two at the front.
     */
    @Test
    public void theGearSheetOffersFasterSpeedsWithTheirLabels() {
        float[] facebooks = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
        String[] labels = {"0.5", "0.75", "1", "1.25", "1.5", "1.75", "2"};
        Settings.FASTER_REEL_SPEEDS.save(true);
        assertTrue("on, the sheet reads its speeds from their floats", ReelSpeed.gearValues(false));
        assertEquals("[0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0]", Arrays.toString(ReelSpeed.gearSpeeds(facebooks)));
        assertEquals(Arrays.asList("0.5", "0.75", "1", "1.25", "1.5", "1.75", "2", "2.5", "3"),
                Arrays.asList(ReelSpeed.gearLabels(labels)));

        // A sheet that already goes to 2.5x gets only 3x, and one that goes to 3x keeps its own.
        assertEquals("[1.0, 2.5, 3.0]", Arrays.toString(ReelSpeed.gearSpeeds(new float[] {1f, 2.5f})));
        assertEquals(Arrays.asList("1", "2.5", "3"), Arrays.asList(ReelSpeed.gearLabels(new String[] {"1", "2.5"})));
        float[] fast = {1f, 2f, 3f};
        String[] fastLabels = {"1", "2", "3"};
        assertSame(fast, ReelSpeed.gearSpeeds(fast));
        assertSame(fastLabels, ReelSpeed.gearLabels(fastLabels));

        // In German, with Slower speeds on too: both ends written like the sheet's "0,5x".
        Settings.SLOWER_REEL_SPEEDS.save(true);
        assertEquals("[0.1, 0.25, 0.5, 1.0, 2.0, 2.5, 3.0]", Arrays.toString(ReelSpeed.gearSpeeds(new float[] {0.5f, 1f, 2f})));
        assertEquals(Arrays.asList("0,1x", "0,25x", "0,5x", "1x (Normal)", "2x", "2,5x", "3x"),
                Arrays.asList(ReelSpeed.gearLabels(new String[] {"0,5x", "1x (Normal)", "2x"})));
        assertEquals(FamilyNames.KEEP_REEL_SPEED + ": invoked 4, 1 found, 0 missing. Counted: "
                + ReelSpeed.GEAR_FASTER_OFFERED + " 3, " + ReelSpeed.GEAR_SLOWER_OFFERED + " 1", statusLine());
    }

    @Test
    public void anAddedSpeedIsWrittenLikeTheSheetsOwnLabels() {
        assertEquals("0.25", ReelSpeed.styled("0.25", "0.5"));
        assertEquals("0,25x", ReelSpeed.styled("0.25", "0,5x"));
        assertEquals("x0.1", ReelSpeed.styled("0.1", "x0.5"));
        assertEquals("no model", "0.1", ReelSpeed.styled("0.1", null));
        assertEquals("a model with no decimal in it", "0.1", ReelSpeed.styled("0.1", "1x (Normal)"));
        String arabicHalf = new String(new char[] {0x0660, 0x066b, 0x0665});
        assertEquals("digits [0-9] doesn't read", "0.1", ReelSpeed.styled("0.1", arabicHalf));
    }

    /**
     * Speeds of zero are a sheet still reading its labels, which a float added here would never
     * reach: nothing is added, so no label is either. Paused, the sheet is Facebook's.
     */
    @Test
    public void theGearSheetKeepsItsSpeedsWhenItReadsLabelsOrIsPaused() {
        Settings.SLOWER_REEL_SPEEDS.save(true);
        float[] zeros = new float[3];
        String[] labels = {"0.5", "1", "2"};
        assertSame(zeros, ReelSpeed.gearSpeeds(zeros));
        assertSame(labels, ReelSpeed.gearLabels(labels));

        float[] facebooks = {0.5f, 1f, 2f};
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertFalse(reason.name(), ReelSpeed.gearValues(false));
            assertSame(reason.name(), facebooks, ReelSpeed.gearSpeeds(facebooks));
            assertSame(reason.name(), labels, ReelSpeed.gearLabels(labels));
        }
        PauseForTests.resume();
        assertEquals("the control: running, the slower speeds come back", 5, ReelSpeed.gearSpeeds(facebooks).length);
        assertEquals("and their labels", 5, ReelSpeed.gearLabels(labels).length);
    }

    // ------------------------------------------------------------------ Playback speed in the post menu (#429)

    /**
     * A speed picked in a feed video's 3-dot menu goes on the player playing that video, not on the
     * one that started after it, and a live video takes one too.
     */
    @Test
    public void thePostMenuSetsTheSpeedOnThePlayerPlayingItsVideo() {
        Settings.POST_MENU_SPEED.save(true);
        assertTrue(PostMenuSpeed.offersRow());
        Object feed = players.player(FEED, NOT_A_REEL);
        Object live = players.player(FEED, LIVE_VIDEO);
        players.bind(feed, "101");
        players.bind(live, "202");
        ReelSpeed.started(feed);
        ReelSpeed.started(live);

        PostMenuSpeed.choose(null, "101", 2.5f);
        assertEquals(2.5, players.speed(feed), 0.001);
        assertEquals("the video that started after it keeps its speed", 1, players.speed(live), 0.001);
        PostMenuSpeed.choose(null, "202", 3f);
        assertEquals("a live video takes one too", 3, players.speed(live), 0.001);
        assertTrue(statusLine(), statusLine().contains(PostMenuSpeed.APPLIED + " 2"));
    }

    /**
     * A pick for a video no player plays yet, or one its player has moved on from, waits for that
     * video's start, and Keep the video speed's kept speed doesn't undo it there.
     */
    @Test
    public void aPostMenuPickWaitsForItsVideoToStart() {
        Settings.POST_MENU_SPEED.save(true);
        Settings.KEEP_VIDEO_SPEED.save(true);
        Object feed = players.player(FEED, NOT_A_REEL);
        players.bind(feed, "101");
        ReelSpeed.started(feed);
        gearPick(feed, 1.25f);
        // The player moves on to the next post's video, so 101 isn't playing anywhere.
        players.bind(feed, "102");
        ReelSpeed.started(feed);
        assertEquals("the control: the kept speed goes on the next video", 1.25, players.speed(feed), 0.001);

        PostMenuSpeed.choose(null, "101", 2f);
        assertEquals("nothing playing 101, nothing changes", 1.25, players.speed(feed), 0.001);
        players.bind(feed, "101");
        ReelSpeed.started(feed);
        assertEquals("101 starts at the speed picked for it", 2, players.speed(feed), 0.001);
        resume(feed);
        assertEquals("and keeps it when it starts again", 2, players.speed(feed), 0.001);
    }

    /**
     * A post menu pick counts as a gear menu pick: Keep the video speed keeps it for the next
     * videos. With no id read from any video, it goes on the player that started last.
     */
    @Test
    public void aPostMenuPickIsKeptLikeAGearPickAndFallsBackToTheVideoPlaying() {
        Settings.POST_MENU_SPEED.save(true);
        Settings.KEEP_VIDEO_SPEED.save(true);
        Object feed = players.player(FEED, NOT_A_REEL);
        players.bind(feed);
        ReelSpeed.started(feed);

        PostMenuSpeed.choose(null, "101", 1.5f);
        assertEquals("no id read, so the video playing takes it", 1.5, players.speed(feed), 0.001);
        assertEquals("and Keep the video speed keeps it", 1.5, ReelSpeed.videoKept(), 0.001);
        Object next = players.player(FEED, NOT_A_REEL);
        players.bind(next);
        ReelSpeed.started(next);
        assertEquals(1.5, players.speed(next), 0.001);
    }

    /** Off or paused, the menu gets no row, a pick sets nothing, and starts aren't remembered. */
    @Test
    public void offOrPausedThePostMenuIsFacebooks() {
        Object feed = players.player(FEED, NOT_A_REEL);
        players.bind(feed, "101");
        assertFalse("the switch starts off", Settings.POST_MENU_SPEED.get());
        assertFalse(PostMenuSpeed.offersRow());
        ReelSpeed.started(feed);
        Settings.POST_MENU_SPEED.save(true);
        PostMenuSpeed.choose(null, "101", 2f);
        assertEquals("a start while off isn't remembered, so 101 waits", 1, players.speed(feed), 0.001);

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(PostMenuSpeed.offersRow());
        ReelSpeed.started(feed);
        PostMenuSpeed.choose(null, "101", 3f);
        assertEquals("paused, nothing is set", 1, players.speed(feed), 0.001);
        PauseForTests.resume();
        ReelSpeed.started(feed);
        assertEquals("the control: running, the waiting 2x goes on", 2, players.speed(feed), 0.001);
    }

    /**
     * The row opens a list of 0.5x to 3x with the speed the video plays at checked, and a tap on a
     * speed sets it and closes the list.
     */
    @Test
    public void thePostMenuRowOpensTheSpeedsWithTheCurrentOneChecked() {
        Settings.POST_MENU_SPEED.save(true);
        Object feed = players.player(FEED, NOT_A_REEL);
        players.bind(feed, "101");
        ReelSpeed.started(feed);
        PostMenuSpeed.choose(null, "101", 1.5f);
        Activity screen = Robolectric.buildActivity(Activity.class).setup().get();

        PostMenuSpeed.open(screen, "101");
        ShadowLooper.idleMainLooper();
        AlertDialog list = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(list.isShowing());
        assertEquals(Arrays.asList("0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x", "2.5x", "3x"),
                Arrays.asList(PostMenuSpeed.labels()));
        assertEquals(8, list.getListView().getAdapter().getCount());
        assertEquals("1.5x", String.valueOf(list.getListView().getAdapter().getItem(4)));
        assertEquals("1.5x is checked", 4, list.getListView().getCheckedItemPosition());

        list.getListView().performItemClick(null, 7, 0);
        ShadowLooper.idleMainLooper();
        assertEquals(3, players.speed(feed), 0.001);
        assertFalse(list.isShowing());
        assertEquals("Playback speed: 3x", ShadowToast.getTextOfLatestToast());
    }
}
