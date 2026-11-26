/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import static android.os.Looper.getMainLooper;

import android.app.Activity;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import com.facebook.litho.ComponentTree;
import com.facebook.litho.LithoView;
import com.facebook.video.engine.api.VideoPlayerParams;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.facebook.media.BackgroundPlayForTests.Player;
import app.morphe.extension.facebook.media.TapToPlayForTests.Trigger;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;

/**
 * Next reel in the background (D#524): a reel playing in Facebook's background player goes on, just
 * before its end, to the reel the Reels viewer bound after it that you haven't seen, through the
 * extension's handler with a record of its own, and the one that ended is let go. Off, or with no
 * reel left, the reel plays again as Facebook has it. The handler and its record stand in for 588's,
 * and Keep playing in the background's own rules run in between.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class NextReelTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String REELS = "fb_shorts_tab::reels_tab";

    /** The trigger Facebook's background player plays with. */
    enum BackgroundTrigger { BY_BACKGROUND_PLAY }

    /** Stands in for the player type the record keeps. */
    enum Kind { FULL_SCREEN_PLAYER }

    /** Stands in for PlayerOrigin, read by its simple name. */
    static final class PlayerOrigin {
        private final String name;

        PlayerOrigin(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** Stands in for the rich params the player's binder is handed, holding a VideoPlayerParams. */
    static final class RichParams {
        final VideoPlayerParams player;

        RichParams(String video) {
            player = new VideoPlayerParams(video, null);
        }
    }

    public static final class Session { }

    /** Stands in for 588's record: (type, trigger, origin, rich params, video id, J, Z, Z). */
    public static final class Record {
        final Kind type;
        final Trigger trigger;
        final PlayerOrigin origin;
        final RichParams params;
        final String video;
        final long at;
        final boolean played;
        final boolean other;

        public Record(Kind type, Trigger trigger, PlayerOrigin origin, RichParams params, String video, long at,
                boolean played, boolean other) {
            this.type = type;
            this.trigger = trigger;
            this.origin = origin;
            this.params = params;
            this.video = video;
            this.at = at;
            this.played = played;
            this.other = other;
        }
    }

    /**
     * Stands in for the patched fullscreen handler: its check tells Next reel, asks the extension, and
     * on a yes starts its own background player for the record's video.
     */
    public static final class Handler {
        final List<Record> asked = new ArrayList<>();
        final List<Player> started = new ArrayList<>();
        int length = 10_000;

        public boolean check(Session session, Record record) {
            NextReel.checking(this, session, record);
            asked.add(record);
            if (BackgroundPlay.deciding(this, record.video, record.trigger)) {
                BackgroundPlay.answered(false);
                return false;
            }
            Player player = new Player();
            player.video = record.video;
            player.length = length;
            started.add(player);
            BackgroundPlay.started(player, BackgroundTrigger.BY_BACKGROUND_PLAY);
            BackgroundPlay.answered(true);
            return true;
        }
    }

    private final Handler handler = new Handler();
    private final Session session = new Session();
    private final PlayerOrigin reels = new PlayerOrigin(REELS);
    private final List<Object> released = new ArrayList<>();

    @Before
    public void start() {
        SystemClock.sleep(60_000);
        BackgroundPlayForTests.forget();
        BackgroundPlay.useHandler(handler);
        BackgroundPlay.PlayerReader players = BackgroundPlayForTests.READER;
        BackgroundPlay.reader = new BackgroundPlay.PlayerReader() {
            @Override
            public boolean playing(Object player) throws Exception {
                return players.playing(player);
            }

            @Override
            public String videoId(Object player) throws Exception {
                return players.videoId(player);
            }

            @Override
            public void pause(Object player, String trigger) throws Exception {
                players.pause(player, trigger);
            }

            @Override
            public void play(Object player) throws Exception {
                players.play(player);
            }

            @Override
            public int position(Object player) throws Exception {
                return players.position(player);
            }

            @Override
            public void seek(Object player, int positionMs) throws Exception {
                players.seek(player, positionMs);
            }

            @Override
            public int length(Object player) throws Exception {
                return players.length(player);
            }

            @Override
            public void release(Object player, String trigger) {
                released.add(player);
            }
        };
        TapToPlay.origins = player -> REELS;
        Settings.KEEP_PLAYING_IN_BACKGROUND.save(true);
        Settings.NEXT_REEL_IN_BACKGROUND.save(true);
    }

    @After
    public void restore() {
        Settings.KEEP_PLAYING_IN_BACKGROUND.resetToDefault();
        Settings.NEXT_REEL_IN_BACKGROUND.resetToDefault();
        BackgroundPlayForTests.forget();
    }

    private static void idle(long millis) {
        shadowOf(getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    /** The Reels viewer binds the reels [videos] to players, in that order. */
    private void bind(String... videos) {
        for (String video : videos) NextReel.bound(new Object(), reels, new RichParams(video));
    }

    /** You watch [video] in the Reels viewer. */
    private Player watch(String video) {
        Player player = new Player();
        player.video = video;
        BackgroundPlay.started(player, Trigger.BY_USER_SWIPE);
        return player;
    }

    /** You leave Facebook on [video], which Facebook's background player takes on. Answers that player. */
    private Player leaveOn(String video) {
        Player watching = watch(video);
        SystemClock.sleep(3000);
        BackgroundPlay.pausing(watching, Trigger.BY_PLAYER);
        Record left = new Record(Kind.FULL_SCREEN_PLAYER, Trigger.BY_PLAYER, reels, new RichParams(video), video,
                SystemClock.elapsedRealtime(), false, false);
        assertTrue("the handler didn't take the reel you left on", handler.check(session, left));
        return handler.started.get(handler.started.size() - 1);
    }

    /** The background player's reel plays to [at] ms of its length, and the looks catch up. */
    private static void playTo(Player player, int at) {
        player.position = at;
        idle(NextReel.LOOK_MS + 50);
    }

    private List<String> askedVideos() {
        List<String> videos = new ArrayList<>();
        for (Record record : handler.asked) videos.add(record.video);
        return videos;
    }

    @Test
    public void theSwitchStartsOff() {
        Settings.NEXT_REEL_IN_BACKGROUND.resetToDefault();
        assertEquals(Boolean.FALSE, Settings.NEXT_REEL_IN_BACKGROUND.get());
    }

    @Test
    public void aReelNearItsEndGoesOnToTheNextOneYouHaventSeen() {
        bind("1", "2", "3", "4");
        watch("1");
        Player first = leaveOn("2");

        playTo(first, 5000);
        assertEquals("handed on before its end", List.of("2"), askedVideos());
        playTo(first, first.length - NextReel.END_LEAD_MS + 100);

        assertEquals("the reel after the one you left on", List.of("2", "3"), askedVideos());
        Record made = handler.asked.get(1);
        assertEquals("the next reel's own params", "3", made.params.player.toString());
        assertSame("the place it was bound in", reels, made.origin);
        assertEquals("a pause, as the check wants", false, made.played);
        assertEquals(Kind.FULL_SCREEN_PLAYER, made.type);
        assertEquals(Trigger.BY_PLAYER, made.trigger);
        Player second = handler.started.get(1);
        assertSame("Facebook's background player now plays the next reel", second, BackgroundPlay.currentBackgroundPlayer());
        assertEquals("the one that ended was let go", List.of(first), released);
        assertTrue("the notification stays", BackgroundPlay.notificationAllowed());

        playTo(second, second.length - 100);
        assertEquals(List.of("2", "3", "4"), askedVideos());
        Player third = handler.started.get(2);
        playTo(third, third.length - 100);
        assertEquals("nothing after the last reel Reels had ready", List.of("2", "3", "4"), askedVideos());
        assertSame("the last reel plays again", third, BackgroundPlay.currentBackgroundPlayer());
        assertEquals(List.of(first, second), released);
    }

    @Test
    public void aReelFacebookLoopedGoesOnToo() {
        bind("1", "2");
        Player first = leaveOn("1");
        playTo(first, 8000);
        playTo(first, 200);
        assertEquals("a reel that started over was handed on", List.of("1", "2"), askedVideos());
    }

    @Test
    public void reelsYouSawInReelsAreSkipped() {
        bind("1", "2", "3", "4", "5");
        watch("3");
        watch("4");
        Player left = leaveOn("2");
        playTo(left, left.length - 100);
        assertEquals("the next reel you haven't seen", List.of("2", "5"), askedVideos());
    }

    @Test
    public void offTheReelPlaysAgain() {
        Settings.NEXT_REEL_IN_BACKGROUND.save(false);
        bind("1", "2");
        Player first = leaveOn("1");
        playTo(first, first.length - 100);
        playTo(first, 200);
        NextReel.bound(new Object(), reels, new RichParams("3"));
        idle(50);
        assertEquals("handed on with the switch off", List.of("1"), askedVideos());
        assertEquals(List.of(), released);
    }

    @Test
    public void reelsBoundOutsideReelsAreNotHandedOn() {
        bind("1");
        NextReel.bound(new Object(), new PlayerOrigin("newsfeed"), new RichParams("feed"));
        Player first = leaveOn("1");
        playTo(first, first.length - 100);
        assertEquals("a feed video was handed on", List.of("1"), askedVideos());
    }

    @Test
    public void comingBackToFacebookEndsTheRun() {
        bind("1", "2");
        Player first = leaveOn("1");
        playTo(first, 3000);
        BackgroundPlay.returning(handler);
        BackgroundPlay.returned();
        playTo(first, first.length - 100);
        NextReel.bound(new Object(), reels, new RichParams("3"));
        idle(50);
        assertEquals("handed on after you came back", List.of("1"), askedVideos());
    }

    /**
     * Stands in for the Reels viewer's pager, a list of pages the screen's size or [pageHeight] tall:
     * [onScroll] is what Facebook binds when it's scrolled a page, a page it had built ahead, [onReturn]
     * what it binds when it's scrolled on again after a step back, and [onLaidOut] what it binds once a
     * page the scroll built is laid out and the pager nudged.
     */
    static final class ReelsPager extends RecyclerView {
        String onScroll;
        String onReturn;
        String onLaidOut;
        int scrolls;
        int nudges;
        int backs;
        private final PlayerOrigin origin;

        ReelsPager(Activity screen, PlayerOrigin origin, int pageHeight) {
            super(screen, true);
            this.origin = origin;
            addView(new View(screen), new LayoutParams(LayoutParams.MATCH_PARENT, pageHeight));
        }

        /** One page's height, the pager's own unless a reel runs under the tab bar. */
        int page() {
            return getChildAt(0).getHeight();
        }

        @Override
        public void scrollBy(int x, int y) {
            if (y == page()) {
                String binds = ++scrolls == 1 ? onScroll : backs > 0 ? onReturn : null;
                if (binds != null) NextReel.bound(new Object(), origin, new RichParams(binds));
            } else if (y == -page()) {
                backs++;
            } else if (y > 0) {
                nudges++;
                if (onLaidOut != null) NextReel.bound(new Object(), origin, new RichParams(onLaidOut));
            }
        }
    }

    /** The screen you leave on, holding the Reels viewer's pager, laid out and then stopped. */
    private ReelsPager leaveScreen() {
        return leaveScreen(ViewGroup.LayoutParams.MATCH_PARENT, null);
    }

    /**
     * The screen you leave on, its Reels viewer's pages [pageHeight] tall, the pager in a Litho view
     * rendering [tree] when there's one, laid out and then stopped.
     */
    private ReelsPager leaveScreen(int pageHeight, ComponentTree tree) {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        Activity screen = controller.get();
        ReelsPager pager = new ReelsPager(screen, reels, pageHeight);
        if (tree == null) {
            screen.setContentView(pager);
        } else {
            LithoView surface = new LithoView(screen);
            surface.tree = tree;
            surface.addView(pager);
            screen.setContentView(surface);
        }
        View decor = screen.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 720, 1600);
        // Android hides a stopped screen's decor (S22, 588), with the pager still in it.
        decor.setVisibility(View.INVISIBLE);
        NextReel.stopped(screen);
        return pager;
    }

    @Test
    public void aReelFacebookBindsByItselfAfterTheEndIsHandedOnTo() {
        bind("1");
        Player first = leaveOn("1");
        playTo(first, first.length - 100);
        assertEquals(List.of("1"), askedVideos());

        // Facebook's Scroll to next reel automatically moves the viewer on as the reel ends.
        NextReel.bound(new Object(), reels, new RichParams("2"));
        idle(50);

        assertEquals("the reel Facebook bound after the end", List.of("1", "2"), askedVideos());
        assertEquals(List.of(first), released);
    }

    @Test
    public void aPageTallerThanTheViewerMovesItOnByThePage() {
        // A reel can run under the tab bar, its page taller than the viewer (emulator, 588: 2272 in 2062).
        ReelsPager pager = leaveScreen(2000, null);
        assertTrue("the page is taller than the viewer", pager.page() > pager.getHeight());
        pager.onScroll = "2";
        bind("1");
        Player first = leaveOn("1");

        playTo(first, first.length - 100);
        idle(50);

        assertEquals("the viewer was scrolled a page", 1, pager.scrolls);
        assertEquals("the reel the viewer bound once scrolled", List.of("1", "2"), askedVideos());
        assertEquals("the one that ended was let go", List.of(first), released);
    }

    @Test
    public void withNoReelReadyTheReelsViewerIsScrolledOnAndTheReelItBindsPlays() {
        ReelsPager pager = leaveScreen();
        pager.onScroll = "2";
        bind("1");
        Player first = leaveOn("1");

        playTo(first, first.length - 100);
        idle(50);

        assertEquals("the viewer was scrolled a page", 1, pager.scrolls);
        assertEquals("the reel the viewer bound once scrolled", List.of("1", "2"), askedVideos());
        assertSame(handler.started.get(1), BackgroundPlay.currentBackgroundPlayer());
        assertEquals("the one that ended was let go", List.of(first), released);
        idle(2 * NextReel.SCROLL_WAIT_MS + NextReel.LAID_OUT_WAIT_MS);
        assertEquals("moved again after the scroll had done it", 1, pager.scrolls);
        assertEquals("laid out by hand after the scroll had done it", 0, pager.nudges);
        assertEquals("went back after the scroll had done it", 0, pager.backs);
    }

    @Test
    public void aPageTheScrollBuiltIsBoundWhenThePagerComesBackToIt() {
        ReelsPager pager = leaveScreen();
        pager.onReturn = "2";
        bind("1");
        Player first = leaveOn("1");

        playTo(first, first.length - 100);
        assertEquals("handed on before the viewer bound anything", List.of("1"), askedVideos());
        idle(NextReel.SCROLL_WAIT_MS + 50);

        assertEquals("the pager went back a page", 1, pager.backs);
        assertEquals("and on again", 2, pager.scrolls);
        assertEquals(List.of("1", "2"), askedVideos());
        assertEquals(List.of(first), released);
        idle(NextReel.SCROLL_WAIT_MS + NextReel.LAID_OUT_WAIT_MS);
        assertEquals("laid out by hand with a reel bound", 0, pager.nudges);
        assertEquals("went back with a reel bound", 1, pager.backs);
    }

    @Test
    public void aPageTheScrollBuiltIsBoundOnceTheScreenIsLaidOut() {
        ReelsPager pager = leaveScreen();
        pager.onLaidOut = "2";
        bind("1");
        Player first = leaveOn("1");

        playTo(first, first.length - 100);
        idle(2 * NextReel.SCROLL_WAIT_MS + 50);

        assertEquals("the screen was laid out once the scroll bound nothing", 1, pager.nudges);
        assertEquals(List.of("1", "2"), askedVideos());
        assertEquals(List.of(first), released);
        idle(NextReel.LAID_OUT_WAIT_MS);
        assertEquals("went back with a reel bound", 1, pager.backs);
    }

    @Test
    public void aViewerThatBindsNothingLeavesTheReelPlayingAgain() {
        ReelsPager pager = leaveScreen();
        bind("1");
        Player first = leaveOn("1");

        playTo(first, first.length - 100);
        idle(2 * NextReel.SCROLL_WAIT_MS + NextReel.LAID_OUT_WAIT_MS + 100);

        assertEquals(2, pager.scrolls);
        assertEquals(1, pager.nudges);
        assertEquals("the viewer went back to the reel that plays", 2, pager.backs);
        assertEquals("handed on with nothing bound", List.of("1"), askedVideos());
        assertEquals(List.of(), released);
        assertSame("the reel plays again", first, BackgroundPlay.currentBackgroundPlayer());

        NextReel.bound(new Object(), reels, new RichParams("2"));
        idle(50);
        assertEquals("a reel bound later is still handed on to", List.of("1", "2"), askedVideos());
    }

    @Test
    public void eachTimeTheReelEndsAgainTheViewerIsMovedOnAgainUpToALimit() {
        ReelsPager pager = leaveScreen();
        bind("1");
        Player first = leaveOn("1");

        for (int move = 1; move <= NextReel.MOVES; move++) {
            playTo(first, first.length - 100);
            // Facebook's loop starts the reel over while the viewer is moved.
            first.position = 1000;
            idle(2 * NextReel.SCROLL_WAIT_MS + NextReel.LAID_OUT_WAIT_MS + 100);
            assertEquals("the viewer moved on at end " + move, 2 * move, pager.scrolls);
            assertEquals("and went back at end " + move, 2 * move, pager.backs);
        }
        playTo(first, first.length - 100);
        idle(2 * NextReel.SCROLL_WAIT_MS + NextReel.LAID_OUT_WAIT_MS + 100);

        assertEquals("moved on past the limit", 2 * NextReel.MOVES, pager.scrolls);
        assertEquals(List.of("1"), askedVideos());
        assertSame("the reel plays again", first, BackgroundPlay.currentBackgroundPlayer());
    }

    /**
     * Stands in for the handler 588's loading page runs when it shows (a kotlin Function1 case of a
     * shared lambda class), which asks for more reels; [loads] is what Facebook does once asked.
     */
    public static final class ShownHandler {
        int runs;
        Runnable loads;

        public Object invoke(Object event) {
            runs++;
            if (loads != null) loads.run();
            return null;
        }
    }

    /** Stands in for one of Facebook's surfaces (588 SurfaceManager), which renders [tree] and was resumed [resumes] times. */
    static final class Surface {
        final ComponentTree tree;
        int resumes;

        Surface(ComponentTree tree) {
            this.tree = tree;
        }
    }

    /** Stands in for the surface's resume (588 SurfaceManager$6), the runnable made with just the surface. */
    static final class Resume implements Runnable {
        private final Surface surface;

        Resume(Surface surface) {
            this.surface = surface;
        }

        @Override
        public void run() {
            surface.resumes++;
        }
    }

    @Test
    public void withNoReelLoadedTheViewersPausedSurfaceResumesSoTheReelFacebookLoadsReachesIt() {
        ComponentTree tree = new ComponentTree();
        ReelsPager pager = leaveScreen(ViewGroup.LayoutParams.MATCH_PARENT, tree);
        Surface viewer = new Surface(tree);
        Surface elsewhere = new Surface(new ComponentTree());
        NextReel.surfacePaused(elsewhere, Resume.class);
        NextReel.surfacePaused(viewer, Resume.class);
        ShownHandler shown = new ShownHandler();
        // Facebook loads a reel once asked, but only a resumed surface shows it in the viewer.
        shown.loads = () -> {
            if (viewer.resumes > 0) pager.onReturn = "2";
        };
        NextReel.loadingPage(shown);
        bind("1");
        Player first = leaveOn("1");
        idle(50);
        assertEquals("resumed before the end", 0, viewer.resumes);

        playTo(first, first.length - 100);
        idle(NextReel.SCROLL_WAIT_MS + 50);

        assertEquals("the viewer's surface resumed", 1, viewer.resumes);
        assertEquals("another surface resumed", 0, elsewhere.resumes);
        assertEquals("the reel Facebook loaded", List.of("1", "2"), askedVideos());
        assertEquals(List.of(first), released);
        Player second = handler.started.get(1);
        playTo(second, second.length - 100);
        assertEquals("resumed again", 1, viewer.resumes);
    }

    @Test
    public void withNoReelLoadedAfterTheEndFacebookIsAskedForMoreAndTheReelItLoadsPlays() {
        ReelsPager pager = leaveScreen();
        ShownHandler shown = new ShownHandler();
        // Once asked, Facebook loads a reel onto the loading page, which binds when the pager comes back to it.
        shown.loads = () -> pager.onReturn = "2";
        NextReel.loadingPage(shown);
        bind("1");
        Player first = leaveOn("1");
        idle(50);
        assertEquals("asked before the end", 0, shown.runs);

        playTo(first, first.length - 100);
        assertEquals("Facebook was asked for more at the end", 1, shown.runs);
        idle(NextReel.SCROLL_WAIT_MS + 50);

        assertEquals("the reel Facebook loaded", List.of("1", "2"), askedVideos());
        assertEquals(List.of(first), released);
        Player second = handler.started.get(1);
        playTo(second, second.length - 100);
        assertEquals("the same loading page run twice", 1, shown.runs);
    }

    @Test
    public void aLoadingPageMadeAfterTheEndIsRunToo() {
        ReelsPager pager = leaveScreen();
        bind("1");
        Player first = leaveOn("1");
        playTo(first, first.length - 100);

        // The scroll built the loading page, off the main thread.
        ShownHandler shown = new ShownHandler();
        shown.loads = () -> pager.onReturn = "2";
        NextReel.loadingPage(shown);
        assertEquals("run before the main thread got to it", 0, shown.runs);
        idle(50);
        assertEquals(1, shown.runs);
        idle(NextReel.SCROLL_WAIT_MS + 50);
        assertEquals(List.of("1", "2"), askedVideos());

        // A reel ready again: a loading page made now waits for the next end with none.
        ShownHandler later = new ShownHandler();
        NextReel.loadingPage(later);
        idle(50);
        assertEquals("run with a reel playing on", 0, later.runs);
    }

    @Test
    public void eachEndRunsAFewLoadingPagesAtMostAndNoneInFacebookOrWithTheSwitchOff() {
        ShownHandler foreground = new ShownHandler();
        NextReel.loadingPage(foreground);
        idle(50);
        assertEquals("run with Facebook on screen", 0, foreground.runs);

        leaveScreen();
        bind("1");
        Player first = leaveOn("1");
        playTo(first, first.length - 100);
        assertEquals("the page made before you left", 1, foreground.runs);

        Settings.NEXT_REEL_IN_BACKGROUND.save(false);
        ShownHandler off = new ShownHandler();
        NextReel.loadingPage(off);
        idle(50);
        assertEquals("run with the switch off", 0, off.runs);
        Settings.NEXT_REEL_IN_BACKGROUND.save(true);

        List<ShownHandler> renders = new ArrayList<>();
        for (int render = 0; render < NextReel.ASKS + 1; render++) {
            ShownHandler again = new ShownHandler();
            renders.add(again);
            NextReel.loadingPage(again);
            idle(50);
        }
        int ran = foreground.runs;
        for (ShownHandler each : renders) ran += each.runs;
        assertEquals("loading pages one end ran", NextReel.ASKS, ran);
        assertEquals("the first page made again", 1, renders.get(0).runs);
    }

    @Test
    public void aPausedReelIsLeftAlone() {
        bind("1", "2");
        Player first = leaveOn("1");
        first.playing = false;
        playTo(first, first.length - 100);
        idle(NextReel.PAUSED_LOOK_MS * 3);
        assertEquals("a paused reel was handed on", List.of("1"), askedVideos());
        first.playing = true;
        idle(NextReel.PAUSED_LOOK_MS + 50);
        assertEquals("the reel played on to its end after the pause", List.of("1", "2"), askedVideos());
    }
}
