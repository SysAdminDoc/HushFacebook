/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;

import com.facebook.katana.activity.FbMainTabActivity;
import com.facebook.katana.immersiveactivity.ImmersiveActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Open feed videos on their own (#161): the first new reels feed to ask for more soon after a feed
 * video tap or the reels viewer opening from a feed surface, or from the full-screen player that
 * opening created, is that video's player and gets no, every time; so is a feed or data source first
 * asking while a Warion viewer that opening created is open, until that viewer closes, though a held
 * data source's first seed check keeps Facebook's answer so the video starts loading; a feed that
 * asked before, one asking long after the tap, one asking from the main screen, and one asking in a
 * viewer or player screen no feed video opened (a link, the Watch page) keeps Facebook's answer; off
 * or paused, nothing is held back.
 */
@RunWith(RobolectricTestRunner.class)
public class FeedVideoAloneTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final AtomicLong clock = new AtomicLong(1_000_000);

    @Before
    public void start() {
        HookStatus.clear();
        FeedVideoAlone.forgetForTests();
        FeedVideoAlone.clockForTests = clock::get;
        Settings.FEED_VIDEOS_ALONE.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.FEED_VIDEOS_ALONE.resetToDefault();
        Settings.SHARED_REELS_ALONE.resetToDefault();
        FeedVideoAlone.forgetForTests();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.REEL_DECLUTTER + ":")) return line;
        }
        return null;
    }

    /** A feed video tap opening [viewer], then long enough that only the viewer can hold anything. */
    private void warionFromFeed(Object viewer) {
        FeedVideoAlone.feedVideoTapped();
        FeedVideoAlone.warionOpening(viewer, true);
        clock.addAndGet(FeedVideoAlone.WINDOW_MS + 1);
    }

    /** A feed reel opening [player] the way the reshare unit does on 583, past the tap's window after. */
    private void playerFromFeed(FeedVideoAlone.Screens screens, Activity player) {
        FeedVideoAlone.reelsViewerOpening(origin("newsfeed::fb_shorts_reshare_feed_unit"));
        clock.addAndGet(40);
        screens.onActivityCreated(player, null);
        clock.addAndGet(FeedVideoAlone.WINDOW_MS + 1);
    }

    @Test
    public void theSwitchStartsOffAndBelongsToCleanUpReels() {
        Settings.FEED_VIDEOS_ALONE.resetToDefault();
        assertFalse(Settings.FEED_VIDEOS_ALONE.get());
        assertFalse("asked as each player asks, so no restart", Settings.FEED_VIDEOS_ALONE.rebootApp);
        assertTrue(PatchFamily.REEL_DECLUTTER.switches.contains(Settings.FEED_VIDEOS_ALONE));
    }

    @Test
    public void theTappedVideosPlayerGetsNoMoreReelsEveryTime() {
        Object reelsTab = new Object();
        assertFalse("a feed asking with no tap keeps Facebook's answer", FeedVideoAlone.holdsBack(reelsTab));

        FeedVideoAlone.feedVideoTapped();
        clock.addAndGet(800);
        assertFalse("the Reels tab asked before the tap", FeedVideoAlone.holdsBack(reelsTab));
        Object player = new Object();
        assertTrue(FeedVideoAlone.holdsBack(player));
        clock.addAndGet(60_000);
        assertTrue("later asks of the same player", FeedVideoAlone.holdsBack(player));
        assertFalse(FeedVideoAlone.holdsBack(reelsTab));

        String line = statusLine();
        assertTrue(line, line.contains(FeedVideoAlone.OPENED + " 1"));
        assertTrue(line, line.contains(FeedVideoAlone.HELD_BACK + " 2"));
    }

    @Test
    public void oneTapMakesOnePlayer() {
        FeedVideoAlone.feedVideoTapped();
        assertTrue(FeedVideoAlone.holdsBack(new Object()));
        assertFalse("the tap was used by the first player", FeedVideoAlone.holdsBack(new Object()));

        FeedVideoAlone.feedVideoTapped();
        assertTrue("a second tap opens a second player alone", FeedVideoAlone.holdsBack(new Object()));
    }

    /** Equal to every other one, the way a Facebook class with its own equals might be. */
    private static final class EqualFeed {
        @Override
        public boolean equals(Object other) {
            return other instanceof EqualFeed;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }

    @Test
    public void feedsAreToldApartByIdentityNotEquals() {
        Object reelsTab = new EqualFeed();
        assertFalse(FeedVideoAlone.holdsBack(reelsTab));
        FeedVideoAlone.feedVideoTapped();
        Object player = new EqualFeed();
        assertTrue("an equal but different feed is a new feed", FeedVideoAlone.holdsBack(player));
        assertFalse("the Reels tab keeps its own answer", FeedVideoAlone.holdsBack(reelsTab));
        assertTrue(FeedVideoAlone.holdsBack(player));
    }

    @Test
    public void aFeedFirstAskingFromTheFullScreenPlayerIsThatPlayers() {
        Activity main = Robolectric.buildActivity(FbMainTabActivity.class).get();
        Activity player = Robolectric.buildActivity(ImmersiveActivity.class).get();
        FeedVideoAlone.Screens screens = new FeedVideoAlone.Screens();

        screens.onActivityResumed(main);
        Object reelsTab = new Object();
        assertFalse("the Reels tab asks from the main screen", FeedVideoAlone.holdsBack(reelsTab));

        playerFromFeed(screens, player);
        Object playersFeed = new Object();
        assertTrue("no Warion viewer, the feed's player in front is enough", FeedVideoAlone.holdsBack(playersFeed));
        assertFalse("a feed that asked before keeps its answer", FeedVideoAlone.holdsBack(reelsTab));

        screens.onActivityResumed(main);
        assertTrue("back on the main screen the player's feed is still held", FeedVideoAlone.holdsBack(playersFeed));
        assertFalse("a new feed asking from the main screen", FeedVideoAlone.holdsBack(new Object()));

        screens.onActivityResumed(player);
        player.finish();
        assertFalse("a player on its way out", FeedVideoAlone.holdsBack(new Object()));
        assertTrue(statusLine(), statusLine().contains(FeedVideoAlone.OPENED + " 1"));
    }

    @Test
    public void offTheFullScreenPlayerHoldsNothingBack() {
        Activity player = Robolectric.buildActivity(ImmersiveActivity.class).get();
        playerFromFeed(new FeedVideoAlone.Screens(), player);
        Settings.FEED_VIDEOS_ALONE.save(false);
        assertFalse(FeedVideoAlone.holdsBack(new Object()));
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        Settings.FEED_VIDEOS_ALONE.save(true);
        assertFalse("paused", FeedVideoAlone.holdsBack(new Object()));
        PauseForTests.resume();
        assertTrue("on again, the feed's player still holds", FeedVideoAlone.holdsBack(new Object()));
    }

    @Test
    public void aPlayerScreenNoFeedVideoOpenedKeepsFacebooksAnswer() {
        FeedVideoAlone.Screens screens = new FeedVideoAlone.Screens();
        Activity watch = Robolectric.buildActivity(ImmersiveActivity.class).get();
        screens.onActivityCreated(watch, null);
        assertFalse("the Watch page opened on its own screen", FeedVideoAlone.holdsBack(new Object()));
        screens.onActivityResumed(watch);
        assertFalse("and back in front later", FeedVideoAlone.seedOnly(new Object()));

        FeedVideoAlone.feedVideoTapped();
        clock.addAndGet(FeedVideoAlone.WINDOW_MS + 1);
        Activity late = Robolectric.buildActivity(ImmersiveActivity.class).get();
        screens.onActivityCreated(late, null);
        assertFalse("a player screen created long after a tap", FeedVideoAlone.holdsBack(new Object()));

        FeedVideoAlone.feedVideoTapped();
        FeedVideoAlone.reelsViewerOpening(origin("video_home::feed_story"));
        Activity watchVideo = Robolectric.buildActivity(ImmersiveActivity.class).get();
        screens.onActivityCreated(watchVideo, null);
        assertFalse("a tap that opened something other than a feed's video", FeedVideoAlone.holdsBack(new Object()));

        Activity player = Robolectric.buildActivity(ImmersiveActivity.class).get();
        playerFromFeed(screens, player);
        assertTrue("a feed video's player screen holds", FeedVideoAlone.holdsBack(new Object()));
        assertTrue(statusLine(), statusLine().contains(FeedVideoAlone.OPENED + " 1"));
    }

    @Test
    public void aWarionViewerNoFeedVideoOpenedKeepsFacebooksAnswer() {
        Object link = new Object();
        FeedVideoAlone.warionOpening(link, true);
        assertFalse("the reels viewer opened from a link", FeedVideoAlone.seedOnly(new Object()));
        assertFalse(FeedVideoAlone.holdsBack(new Object()));

        FeedVideoAlone.feedVideoTapped();
        FeedVideoAlone.reelsViewerOpening(origin("unknown::fb_shorts_viewer"));
        Object linkAfterTap = new Object();
        FeedVideoAlone.warionOpening(linkAfterTap, true);
        assertFalse("a link opening the reels viewer right after a feed tap", FeedVideoAlone.seedOnly(new Object()));

        FeedVideoAlone.feedVideoTapped();
        clock.addAndGet(FeedVideoAlone.WINDOW_MS + 1);
        Object late = new Object();
        FeedVideoAlone.warionOpening(late, true);
        assertFalse("a viewer opening long after a tap", FeedVideoAlone.seedOnly(new Object()));

        FeedVideoAlone.reelsViewerOpening(origin("newsfeed::fb_shorts_native_in_feed_unit"));
        Object feedReel = new Object();
        FeedVideoAlone.warionOpening(feedReel, true);
        clock.addAndGet(FeedVideoAlone.WINDOW_MS + 1);
        assertTrue("a feed reel's viewer holds", FeedVideoAlone.seedOnly(new Object()));
        assertTrue("and so does its feed asking later", FeedVideoAlone.holdsBack(new Object()));
        assertTrue(statusLine(), statusLine().contains(FeedVideoAlone.OPENED + " 1"));
    }

    @Test
    public void aWarionViewersFeedAndDataSourceKeepItsVideoAlone() {
        Object reelsTab = new Object();
        assertFalse("asked before any viewer opened", FeedVideoAlone.holdsBack(reelsTab));
        Object other = new Object();
        FeedVideoAlone.warionOpening(other, false);
        assertFalse("a video fragment that isn't a Warion viewer arms nothing", FeedVideoAlone.seedOnly(new Object()));

        Object viewer = new Object();
        warionFromFeed(viewer);
        Object source = new Object();
        Object loader = new Object();
        assertTrue("the viewer's data source shows the video alone", FeedVideoAlone.seedOnly(source));
        clock.addAndGet(60_000);
        assertTrue("its loader asks after the swipe, long after any tap", FeedVideoAlone.holdsBack(loader));
        assertTrue(FeedVideoAlone.seedOnly(source));
        assertFalse("the Reels tab keeps its answer", FeedVideoAlone.holdsBack(reelsTab));

        String line = statusLine();
        assertTrue("one video opened, though two of its objects asked: " + line, line.contains(FeedVideoAlone.OPENED + " 1"));
        assertTrue(line, line.contains(FeedVideoAlone.HELD_BACK + " 3"));
    }

    @Test
    public void aHeldDataSourcesFirstSeedCheckKeepsFacebooksAnswer() {
        assertFalse("asked with no viewer open", FeedVideoAlone.seedCheck(new Object()));

        Object viewer = new Object();
        warionFromFeed(viewer);
        Object source = new Object();
        assertFalse("its first ask starts loading the video", FeedVideoAlone.seedCheck(source));
        assertTrue("the next ask holds the reels after it", FeedVideoAlone.seedCheck(source));
        assertTrue("and every one after that", FeedVideoAlone.seedCheck(source));
        assertTrue("its other checks hold from the start", FeedVideoAlone.seedOnly(source));

        FeedVideoAlone.warionClosed(viewer);
        Object next = new Object();
        warionFromFeed(next);
        assertFalse("reused for a second viewer, its first ask there starts the video", FeedVideoAlone.seedCheck(source));
        assertTrue(FeedVideoAlone.seedCheck(source));
        Object another = new Object();
        assertFalse("another source in that viewer asks first too", FeedVideoAlone.seedCheck(another));
        assertTrue(FeedVideoAlone.seedCheck(another));

        FeedVideoAlone.warionClosed(next);
        assertFalse("reused with no viewer open", FeedVideoAlone.seedCheck(source));
        assertFalse("that answer sticks", FeedVideoAlone.seedCheck(source));
        assertFalse("nobody asking", FeedVideoAlone.seedCheck(null));
    }

    @Test
    public void closingTheViewerLetsItsObjectsBeDecidedAgain() {
        Object viewer = new Object();
        warionFromFeed(viewer);
        Object source = new Object();
        assertTrue(FeedVideoAlone.seedOnly(source));

        FeedVideoAlone.warionClosed(new Object());
        assertTrue("another fragment closing changes nothing", FeedVideoAlone.seedOnly(source));
        FeedVideoAlone.warionClosed(viewer);
        assertFalse("reused for the Reels tab after the viewer closed", FeedVideoAlone.seedOnly(source));
        assertFalse("a new one asking with no viewer open", FeedVideoAlone.holdsBack(new Object()));
        assertFalse("that answer sticks", FeedVideoAlone.seedOnly(source));

        Object next = new Object();
        warionFromFeed(next);
        assertTrue("a second viewer gets its video alone too", FeedVideoAlone.seedOnly(new Object()));
        assertFalse("but an object that asked from the main screen keeps its answer", FeedVideoAlone.seedOnly(source));
        assertTrue(statusLine(), statusLine().contains(FeedVideoAlone.OPENED + " 2"));
    }

    @Test
    public void offOrPausedAWarionViewerHoldsNothingBack() {
        Settings.FEED_VIDEOS_ALONE.save(false);
        FeedVideoAlone.feedVideoTapped();
        FeedVideoAlone.warionOpening(new Object(), true);
        Settings.FEED_VIDEOS_ALONE.save(true);
        assertFalse("a viewer opened while off arms nothing", FeedVideoAlone.seedOnly(new Object()));

        Object viewer = new Object();
        warionFromFeed(viewer);
        Object source = new Object();
        assertTrue(FeedVideoAlone.seedOnly(source));
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("paused", FeedVideoAlone.seedOnly(source));
        PauseForTests.resume();
        Settings.FEED_VIDEOS_ALONE.save(false);
        assertFalse("off", FeedVideoAlone.seedOnly(source));
        Settings.FEED_VIDEOS_ALONE.save(true);
        assertTrue("on again, still that viewer's", FeedVideoAlone.seedOnly(source));
        FeedVideoAlone.warionOpening(null, true);
        FeedVideoAlone.warionClosed(null);
        assertFalse("nobody asking is left alone", FeedVideoAlone.seedOnly(null));
    }

    /** Stands in for Facebook's PlayerOrigin, which reads as "surface::subsurface". */
    private static Object origin(String text) {
        return new Object() {
            @Override
            public String toString() {
                return text;
            }
        };
    }

    @Test
    public void aReelsViewerOpeningFromAFeedKeepsItsVideoAlone() {
        Object reelsTab = new Object();
        FeedVideoAlone.reelsViewerOpening(origin("fb_shorts_tab::fb_shorts_viewer"));
        assertFalse("the Reels tab opening its viewer", FeedVideoAlone.seedOnly(reelsTab));

        FeedVideoAlone.reelsViewerOpening(origin("newsfeed::fb_shorts_native_in_feed_unit"));
        Object source = new Object();
        assertTrue("a feed reel's data source asking inside the entry", FeedVideoAlone.seedOnly(source));
        clock.addAndGet(60_000);
        assertTrue("and every time after", FeedVideoAlone.seedOnly(source));
        assertFalse("one opening is one player", FeedVideoAlone.seedOnly(new Object()));
        assertFalse("the Reels tab keeps its answer", FeedVideoAlone.seedOnly(reelsTab));

        for (String feed : new String[]{"group::fb_shorts_reshare_feed_unit", "user_timeline", "permalink::shared_video_feed_story",
                "friendly_feed::fb_shorts_reshare_feed_unit"}) {
            FeedVideoAlone.reelsViewerOpening(origin(feed));
            assertTrue(feed, FeedVideoAlone.seedOnly(new Object()));
        }
        for (String elsewhere : new String[]{"profile_reels_tab::fb_shorts_profile_viewer", "search::fb_shorts_viewer",
                "notifications", "video_home::feed_story", "newsfeeds::feed_story", ""}) {
            FeedVideoAlone.reelsViewerOpening(origin(elsewhere));
            assertFalse(elsewhere, FeedVideoAlone.seedOnly(new Object()));
        }
        assertTrue(statusLine(), statusLine().contains(FeedVideoAlone.OPENED + " 5"));
    }

    /**
     * #237: a page's video post opens the reels viewer, then the Warion viewer, and only then does the
     * viewer's data source ask its seed check (584 emulator 2026-10-27). Its first ask keeps
     * Facebook's answer so the video loads, as a reel's does, and every later one gets the yes: with
     * Facebook's answer on every ask, a group's video post opened from the home feed, and an anonymous
     * member's in a group, led to the next video a swipe away (2026-11-02).
     */
    @Test
    public void aFeedsOrdinaryVideoGetsTheSeedCheckLikeAReel() {
        Object reels = new Object();
        FeedVideoAlone.originForTests = params -> params == reels ? origin("user_timeline::fb_shorts_viewer") : null;
        FeedVideoAlone.reelsViewerOpening(origin("user_timeline::feed_story"));
        clock.addAndGet(60);
        Object viewer = new Object();
        FeedVideoAlone.warionOpening(viewer, true);
        clock.addAndGet(50);
        Object source = new Object();
        assertFalse("the video's data source starts loading it on its first ask", FeedVideoAlone.seedCheck(source));
        assertTrue("its next ask keeps the video alone", FeedVideoAlone.seedCheck(source));
        clock.addAndGet(150);
        Object feed = new Object();
        assertTrue("its feed gets no more reels", FeedVideoAlone.holdsBack(feed));
        assertFalse("and its list no loading page", FeedVideoAlone.moreReelsComing(true, reels));
        clock.addAndGet(60_000);
        assertTrue("the data source asking later, the viewer still open", FeedVideoAlone.seedCheck(source));
        assertTrue(FeedVideoAlone.holdsBack(feed));
        assertTrue(statusLine(), statusLine().contains(FeedVideoAlone.OPENED + " 1"));

        for (String post : new String[]{"newsfeed::feed_story", "newsfeed::multimedia_post", "group::feed_story",
                "group::multimedia_post", "group::fb_shorts_creation_feed_unit", "group::fb_shorts_reshare_feed_unit"}) {
            FeedVideoAlone.warionClosed(viewer);
            clock.addAndGet(FeedVideoAlone.WINDOW_MS + 1);
            FeedVideoAlone.reelsViewerOpening(origin(post));
            viewer = new Object();
            FeedVideoAlone.warionOpening(viewer, true);
            Object postSource = new Object();
            assertFalse(post + ": the first ask loads the video", FeedVideoAlone.seedCheck(postSource));
            assertTrue(post + ": the next one keeps it alone", FeedVideoAlone.seedCheck(postSource));
            assertTrue(post + ": its feed gets no more reels", FeedVideoAlone.holdsBack(new Object()));
        }

        FeedVideoAlone.warionClosed(viewer);
        clock.addAndGet(FeedVideoAlone.WINDOW_MS + 1);
        FeedVideoAlone.reelsViewerOpening(origin("newsfeed::feed_story"));
        FeedVideoAlone.warionOpening(new Object(), true);
        clock.addAndGet(FeedVideoAlone.WINDOW_MS + 1);
        assertTrue("a data source first asking long after the video opened is decided by its viewer",
                FeedVideoAlone.seedOnly(new Object()));
    }

    @Test
    public void offOrPausedTheViewersOriginArmsNothing() {
        Settings.FEED_VIDEOS_ALONE.save(false);
        FeedVideoAlone.reelsViewerOpening(origin("newsfeed::feed_story"));
        Settings.FEED_VIDEOS_ALONE.save(true);
        assertFalse("opened while off", FeedVideoAlone.seedOnly(new Object()));

        FeedVideoAlone.reelsViewerOpening(origin("newsfeed::feed_story"));
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("paused", FeedVideoAlone.seedOnly(new Object()));
        FeedVideoAlone.reelsViewerOpening(origin("newsfeed::feed_story"));
        PauseForTests.resume();
        assertFalse("an opening while paused arms nothing", FeedVideoAlone.seedOnly(new Object()));

        FeedVideoAlone.reelsViewerOpening(null);
        assertFalse("no origin", FeedVideoAlone.seedOnly(new Object()));
    }

    @Test
    public void aFeedReelsViewerShowsNoLoadingPage() {
        Object home = new Object();
        Object reelsTab = new Object();
        FeedVideoAlone.originForTests = params -> params == home ? origin("newsfeed::feed_story")
                : params == reelsTab ? origin("fb_shorts_tab::fb_shorts_viewer") : null;
        assertFalse("from the home feed", FeedVideoAlone.moreReelsComing(true, home));
        assertFalse("asked again", FeedVideoAlone.moreReelsComing(true, home));
        assertTrue("the Reels tab keeps its loading page", FeedVideoAlone.moreReelsComing(true, reelsTab));
        assertTrue("params with no origin", FeedVideoAlone.moreReelsComing(true, new Object()));
        assertTrue("no params", FeedVideoAlone.moreReelsComing(true, null));
        assertFalse("none coming stays none", FeedVideoAlone.moreReelsComing(false, reelsTab));
        assertTrue("one viewer, counted once: " + statusLine(), statusLine().contains(FeedVideoAlone.NO_LOADING_PAGE + " 1"));
    }

    @Test
    public void offOrPausedEveryViewerKeepsItsLoadingPage() {
        Object home = new Object();
        FeedVideoAlone.originForTests = params -> origin("newsfeed::feed_story");
        Settings.FEED_VIDEOS_ALONE.save(false);
        assertTrue("off", FeedVideoAlone.moreReelsComing(true, home));
        Settings.FEED_VIDEOS_ALONE.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertTrue("paused", FeedVideoAlone.moreReelsComing(true, home));
        PauseForTests.resume();
        FeedVideoAlone.originForTests = params -> {
            throw new IllegalStateException("no origin field");
        };
        assertTrue("a failure keeps Facebook's answer", FeedVideoAlone.moreReelsComing(true, home));
        FeedVideoAlone.originForTests = null;
        assertTrue("unpatched, the origin reads as none", FeedVideoAlone.moreReelsComing(true, home));
    }

    @Test
    public void aFeedFirstAskingLongAfterTheTapKeepsFacebooksAnswer() {
        FeedVideoAlone.feedVideoTapped();
        clock.addAndGet(FeedVideoAlone.WINDOW_MS + 1);
        assertFalse(FeedVideoAlone.holdsBack(new Object()));
    }

    @Test
    public void offOrPausedNothingIsHeldBack() {
        Object player = new Object();
        FeedVideoAlone.feedVideoTapped();
        assertTrue(FeedVideoAlone.holdsBack(player));

        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(why);
            assertFalse("paused by " + why, FeedVideoAlone.holdsBack(player));
            FeedVideoAlone.feedVideoTapped();
            PauseForTests.resume();
            assertFalse("a tap while paused arms nothing", FeedVideoAlone.holdsBack(new Object()));
        }

        Settings.FEED_VIDEOS_ALONE.save(false);
        assertFalse(FeedVideoAlone.holdsBack(player));
        FeedVideoAlone.feedVideoTapped();
        Settings.FEED_VIDEOS_ALONE.save(true);
        assertFalse("a tap while off arms nothing", FeedVideoAlone.holdsBack(new Object()));
        assertTrue("on again, the player is still held", FeedVideoAlone.holdsBack(player));
        assertFalse("nobody asking is left alone", FeedVideoAlone.holdsBack(null));
    }

    @Test
    public void theSharedReelsSwitchStartsOffAndBelongsToCleanUpReels() {
        assertFalse(Settings.SHARED_REELS_ALONE.get());
        assertFalse("asked as each viewer opens, so no restart", Settings.SHARED_REELS_ALONE.rebootApp);
        assertTrue(PatchFamily.REEL_DECLUTTER.switches.contains(Settings.SHARED_REELS_ALONE));
    }

    @Test
    public void aReelFromALinkOrAChatPlaysAloneWithTheSharedSwitch() {
        Settings.FEED_VIDEOS_ALONE.save(false);
        Settings.SHARED_REELS_ALONE.save(true);
        for (String where : new String[]{"external_deeplink::fb_shorts_viewer", "messaging::xma_shared_video",
                "messaging::thread_video_message_media_viewer", "unknown::fb_shorts_viewer"}) {
            FeedVideoAlone.reelsViewerOpening(origin(where));
            Object source = new Object();
            assertFalse(where + ", before its reel is on screen", FeedVideoAlone.seedOnly(source));
            ReelBinding.bound(new Object(), new Object());
            assertTrue(where, FeedVideoAlone.seedOnly(source));
            clock.addAndGet(60_000);
            assertTrue(where + ", asked again", FeedVideoAlone.seedOnly(source));
        }

        FeedVideoAlone.feedVideoTapped();
        assertFalse("a feed tap is the other switch's", FeedVideoAlone.holdsBack(new Object()));
        for (String where : new String[]{"newsfeed::fb_shorts_native_in_feed_unit", "fb_shorts_tab::reels_tab",
                "unknown::watch_feed", "messenger_story::fb_shorts_viewer"}) {
            FeedVideoAlone.reelsViewerOpening(origin(where));
            assertFalse(where, FeedVideoAlone.seedOnly(new Object()));
        }

        Object link = new Object();
        Object home = new Object();
        FeedVideoAlone.originForTests = params -> params == link ? origin("external_deeplink::fb_shorts_viewer")
                : params == home ? origin("newsfeed::feed_story") : null;
        assertFalse("a link's viewer shows no loading page", FeedVideoAlone.moreReelsComing(true, link));
        assertTrue("a feed's keeps it with the feed switch off", FeedVideoAlone.moreReelsComing(true, home));
    }

    /**
     * #421: a reel link from another app opens the reels viewer with no video in it yet. Its reels
     * feed's fetch and the burst of seed checks its data source asks as it starts are what bring the
     * linked reel in (S22 2026-11-08: holding the fetch, and then every seed check after the first,
     * each left the viewer black). They keep Facebook's answers until the reel is on screen; the ask a
     * second later, which brings Facebook's "Up next" reel, and every fetch after are held. A chat's
     * reel goes the same way, and a feed video is held from its first fetch, reel on screen or not.
     */
    @Test
    public void aSharedReelGetsFacebooksAnswersUntilItIsOnScreen() {
        Settings.SHARED_REELS_ALONE.save(true);
        FeedVideoAlone.reelsViewerOpening(origin("external_deeplink::fb_shorts_external_link"));
        clock.addAndGet(126);
        Object viewer = new Object();
        FeedVideoAlone.warionOpening(viewer, true);
        clock.addAndGet(63);
        Object feed = new Object();
        Object source = new Object();
        assertFalse("the fetch that loads the linked reel", FeedVideoAlone.holdsBack(feed));
        for (int ask = 1; ask <= 4; ask++) {
            assertFalse("the data source's start, ask " + ask, FeedVideoAlone.seedCheck(source));
        }
        clock.addAndGet(152);
        ReelBinding.bound(new Object(), new Object());
        clock.addAndGet(73);
        assertTrue("the ask after the reel is on screen keeps it alone", FeedVideoAlone.seedCheck(source));
        clock.addAndGet(995);
        assertTrue("the ask a second later, the one that brings Up next", FeedVideoAlone.seedCheck(source));
        assertTrue("the feed gets nothing more", FeedVideoAlone.holdsBack(feed));
        clock.addAndGet(60_000);
        assertTrue("a swipe's fetch long after", FeedVideoAlone.holdsBack(feed));
        assertTrue("only the asks after the reel was on screen are held: " + statusLine(),
                statusLine().contains(FeedVideoAlone.HELD_BACK + " 4"));

        FeedVideoAlone.warionClosed(viewer);
        clock.addAndGet(FeedVideoAlone.WINDOW_MS + 1);
        FeedVideoAlone.reelsViewerOpening(origin("messaging::xma_shared_video"));
        FeedVideoAlone.warionOpening(viewer = new Object(), true);
        Object chatSource = new Object();
        Object chatFeed = new Object();
        assertFalse("a chat reel's data source starts its video", FeedVideoAlone.seedCheck(chatSource));
        assertFalse("and its feed's fetch loads it", FeedVideoAlone.holdsBack(chatFeed));
        ReelBinding.bound(new Object(), new Object());
        assertTrue("its feed is held once the reel is on screen", FeedVideoAlone.holdsBack(chatFeed));
        assertTrue("and so is its data source", FeedVideoAlone.seedCheck(chatSource));

        FeedVideoAlone.warionClosed(viewer);
        clock.addAndGet(FeedVideoAlone.WINDOW_MS + 1);
        FeedVideoAlone.feedVideoTapped();
        Object tapped = new Object();
        assertTrue("a feed video's player is held from its first fetch", FeedVideoAlone.holdsBack(tapped));
        ReelBinding.bound(new Object(), new Object());
        assertTrue("a reel on screen after a feed tap changes nothing", FeedVideoAlone.holdsBack(tapped));
    }

    /**
     * #421: a live link loads its reel by itself, and Facebook plays it with a player of its own, so
     * the Reels controller is handed no player as it takes the screen (S22 2026-11-08, a NASA reel:
     * that empty binding 0.4 s in, no player ever after, and the asks a second later brought pages of
     * Facebook's next reels only, one of them a swipe away). That empty binding starts the hold too.
     */
    @Test
    public void aLiveLinksReelIsOnScreenWhenTheControllerIsHandedNoPlayer() {
        Settings.SHARED_REELS_ALONE.save(true);
        FeedVideoAlone.reelsViewerOpening(origin("unknown::fb_shorts_viewer"));
        FeedVideoAlone.reelsViewerOpening(origin("external_deeplink::fb_shorts_external_link"));
        Object handler = new Object();
        assertFalse("the link handler's data source starts", FeedVideoAlone.seedCheck(handler));
        clock.addAndGet(35);
        Object viewer = new Object();
        FeedVideoAlone.warionOpening(viewer, true);
        clock.addAndGet(112);
        Object source = new Object();
        assertFalse("the viewer's data source starts", FeedVideoAlone.seedCheck(source));
        clock.addAndGet(134);
        ReelBinding.bound(new Object(), null);
        clock.addAndGet(1262);
        assertTrue("the viewer's ask a second later", FeedVideoAlone.seedCheck(source));
        assertTrue("the link handler's", FeedVideoAlone.seedCheck(handler));
        Object late = new Object();
        assertFalse("a data source starting late keeps its first ask", FeedVideoAlone.seedCheck(late));
        assertTrue("and is held after", FeedVideoAlone.seedCheck(late));
        assertTrue("a swipe's fetch", FeedVideoAlone.holdsBack(new Object()));
        List<String> page = new ArrayList<>(List.of("Up next"));
        FeedVideoAlone.vending(source, page);
        assertEquals("a page after the reel is on screen goes by the held seed check", List.of("Up next"), page);

        FeedVideoAlone.warionClosed(viewer);
        clock.addAndGet(FeedVideoAlone.WINDOW_MS + 1);
        FeedVideoAlone.reelsViewerOpening(origin("fb_shorts_tab::reels_tab"));
        Object tab = new Object();
        ReelBinding.bound(new Object(), null);
        assertFalse("an empty binding in the Reels tab holds nothing", FeedVideoAlone.seedCheck(tab));
        assertFalse(FeedVideoAlone.seedCheck(tab));
    }

    /**
     * #421: the page the linked reel's fetch brings has Facebook's next reels after the linked one
     * (S22 2026-11-08: "Up next: Gb Flowers" under the reel, and a swipe went on to it). While the
     * shared reel's viewer loads its reel, each page its loader hands over keeps the first reel only.
     * Once the reel is on screen, and for a feed video or the Reels tab, pages are Facebook's.
     */
    @Test
    public void aSharedReelsPageKeepsOnlyTheLinkedReel() {
        Settings.SHARED_REELS_ALONE.save(true);
        FeedVideoAlone.reelsViewerOpening(origin("external_deeplink::fb_shorts_external_link"));
        Object viewer = new Object();
        FeedVideoAlone.warionOpening(viewer, true);
        Object feed = new Object();
        Object source = new Object();
        assertFalse(FeedVideoAlone.holdsBack(feed));
        assertFalse(FeedVideoAlone.seedCheck(source));
        List<String> page = new ArrayList<>(List.of("linked reel", "Gb Flowers", "third"));
        FeedVideoAlone.vending(source, page);
        assertEquals(List.of("linked reel"), page);
        assertTrue("two reels counted as left out: " + statusLine(),
                statusLine().contains(FeedVideoAlone.LEFT_OUT + " 2"));
        List<String> one = new ArrayList<>(List.of("linked reel"));
        FeedVideoAlone.vending(source, one);
        assertEquals("a page of one stays", List.of("linked reel"), one);
        List<String> other = new ArrayList<>(List.of("a", "b"));
        FeedVideoAlone.vending(new Object(), other);
        assertEquals("a data source that never asked is Facebook's", List.of("a", "b"), other);

        ReelBinding.bound(new Object(), new Object());
        List<String> later = new ArrayList<>(List.of("x", "y"));
        FeedVideoAlone.vending(source, later);
        assertEquals("once the reel is on screen the seed check holds pages back itself", List.of("x", "y"), later);

        FeedVideoAlone.warionClosed(viewer);
        clock.addAndGet(FeedVideoAlone.WINDOW_MS + 1);
        FeedVideoAlone.feedVideoTapped();
        Object tapped = new Object();
        assertFalse("a feed video's first seed check", FeedVideoAlone.seedCheck(tapped));
        List<String> feedPage = new ArrayList<>(List.of("tapped", "next"));
        FeedVideoAlone.vending(tapped, feedPage);
        assertEquals("a feed video's pages are the feed switch's business", List.of("tapped", "next"), feedPage);

        clock.addAndGet(FeedVideoAlone.WINDOW_MS + 1);
        FeedVideoAlone.reelsViewerOpening(origin("fb_shorts_tab::reels_tab"));
        Object tab = new Object();
        assertFalse(FeedVideoAlone.seedCheck(tab));
        List<String> tabPage = new ArrayList<>(List.of("r1", "r2"));
        FeedVideoAlone.vending(tab, tabPage);
        assertEquals("the Reels tab keeps its reels", List.of("r1", "r2"), tabPage);

        List<String> fixed = List.of("p", "q");
        FeedVideoAlone.vending(null, fixed);
        FeedVideoAlone.vending(source, "not a list");
        Settings.SHARED_REELS_ALONE.save(false);
        Settings.FEED_VIDEOS_ALONE.save(false);
        List<String> off = new ArrayList<>(List.of("m", "n"));
        FeedVideoAlone.vending(source, off);
        assertEquals("off, nothing is left out", List.of("m", "n"), off);
    }

    /**
     * #421: a reel link opened inside the app gives the viewer's entry the link's origin, but the
     * viewer's list reads "unknown::unknown" (S22 2026-11-08), and its loading page never filled. The
     * first such list read soon after a shared opening is that viewer's and shows none, then and
     * later, even behind a viewer opened over it. Another list, one read only after the window, one
     * after another viewer opened, and any with Open shared reels on their own off keep Facebook's
     * answer.
     */
    @Test
    public void aSharedReelsListWithNoOriginOfItsOwnShowsNoLoadingPage() {
        Settings.FEED_VIDEOS_ALONE.save(false);
        Settings.SHARED_REELS_ALONE.save(true);
        FeedVideoAlone.originForTests = params -> origin(FeedVideoAlone.NO_ORIGIN);
        Object list = new Object();
        assertTrue("before any shared opening", FeedVideoAlone.moreReelsComing(true, new Object()));

        FeedVideoAlone.reelsViewerOpening(origin("unknown::fb_shorts_viewer"));
        clock.addAndGet(200);
        assertFalse("the shared reel's list", FeedVideoAlone.moreReelsComing(true, list));
        assertTrue("another list", FeedVideoAlone.moreReelsComing(true, new Object()));
        clock.addAndGet(60_000);
        assertFalse("its list drawn again later", FeedVideoAlone.moreReelsComing(true, list));
        assertFalse("none coming stays none", FeedVideoAlone.moreReelsComing(false, list));
        assertTrue(statusLine(), statusLine().contains(FeedVideoAlone.NO_LOADING_PAGE + " 1"));

        FeedVideoAlone.reelsViewerOpening(origin("external_deeplink::fb_shorts_viewer"));
        clock.addAndGet(FeedVideoAlone.WINDOW_MS + 1);
        assertTrue("a list read only after the window", FeedVideoAlone.moreReelsComing(true, new Object()));

        FeedVideoAlone.reelsViewerOpening(origin("fb_shorts_tab::reels_tab"));
        assertTrue("a list after the Reels tab opened", FeedVideoAlone.moreReelsComing(true, new Object()));
        // Its fetch stays held, so behind the Reels tab or a profile's reel it still has no next page.
        assertFalse("the shared viewer's list behind another viewer", FeedVideoAlone.moreReelsComing(true, list));

        Settings.SHARED_REELS_ALONE.save(false);
        assertTrue("its list with the switch turned off", FeedVideoAlone.moreReelsComing(true, list));
        Settings.FEED_VIDEOS_ALONE.save(true);
        FeedVideoAlone.reelsViewerOpening(origin("unknown::fb_shorts_viewer"));
        assertTrue("feed switch only", FeedVideoAlone.moreReelsComing(true, new Object()));
    }

    /**
     * #421: a list with no origin that was on screen before a shared reel opened, the Reels tab's
     * say, is never taken as the shared viewer's, even when it's drawn again first inside the
     * window. The shared viewer's own list, read after it, is. A second shared link over the first
     * takes its own list and the first keeps its own.
     */
    @Test
    public void aListReadBeforeASharedOpeningIsNeverTakenForIt() {
        Settings.FEED_VIDEOS_ALONE.save(false);
        Settings.SHARED_REELS_ALONE.save(true);
        FeedVideoAlone.originForTests = params -> origin(FeedVideoAlone.NO_ORIGIN);
        Object reelsTab = new Object();
        FeedVideoAlone.reelsViewerOpening(origin("fb_shorts_tab::reels_tab"));
        assertTrue("the Reels tab's list", FeedVideoAlone.moreReelsComing(true, reelsTab));

        FeedVideoAlone.reelsViewerOpening(origin("unknown::fb_shorts_viewer"));
        clock.addAndGet(100);
        assertTrue("the Reels tab's list drawn again first", FeedVideoAlone.moreReelsComing(true, reelsTab));
        Object first = new Object();
        assertFalse("the shared viewer's list", FeedVideoAlone.moreReelsComing(true, first));
        assertTrue("the Reels tab's list after it", FeedVideoAlone.moreReelsComing(true, reelsTab));

        FeedVideoAlone.reelsViewerOpening(origin("external_deeplink::fb_shorts_viewer"));
        clock.addAndGet(100);
        Object second = new Object();
        assertFalse("the second shared viewer's list", FeedVideoAlone.moreReelsComing(true, second));
        assertTrue("one list per opening", FeedVideoAlone.moreReelsComing(true, new Object()));
        assertFalse("the first shared viewer's list under it", FeedVideoAlone.moreReelsComing(true, first));
        assertTrue("the Reels tab's list still", FeedVideoAlone.moreReelsComing(true, reelsTab));
    }

    @Test
    public void theFeedSwitchAloneLeavesLinksAndChatsToFacebook() {
        for (String where : new String[]{"external_deeplink::fb_shorts_viewer", "messaging::xma_shared_video"}) {
            FeedVideoAlone.reelsViewerOpening(origin(where));
            assertFalse(where, FeedVideoAlone.seedOnly(new Object()));
        }
        Object link = new Object();
        FeedVideoAlone.originForTests = params -> origin("external_deeplink::fb_shorts_viewer");
        assertTrue(FeedVideoAlone.moreReelsComing(true, link));

        Settings.SHARED_REELS_ALONE.save(true);
        FeedVideoAlone.reelsViewerOpening(origin("messaging::xma_shared_video"));
        Object chat = new Object();
        assertFalse("both on, a chat's reel before it's on screen", FeedVideoAlone.seedOnly(chat));
        ReelBinding.bound(new Object(), new Object());
        assertTrue("both on, a chat's reel", FeedVideoAlone.seedOnly(chat));
        FeedVideoAlone.feedVideoTapped();
        assertTrue("both on, a feed tap", FeedVideoAlone.holdsBack(new Object()));
        assertFalse(FeedVideoAlone.moreReelsComing(true, link));
    }
}
