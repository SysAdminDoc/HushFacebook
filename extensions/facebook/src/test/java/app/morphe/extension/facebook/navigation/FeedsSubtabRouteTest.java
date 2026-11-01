/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import com.facebook.api.feedtype.FeedType;
import com.facebook.feed.fragment.FeedFiltersFragment;
import com.facebook.katana.activity.FbMainTabActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The Feeds tab's filter after a start the start tab sends to Feeds (#56): the Feeds tab is asked
 * for the chosen filter the first time it resumes, through the handler Facebook already has for
 * one, and every other start, resume and filter keeps Facebook's own.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FeedsSubtabRouteTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void optIn() {
        Settings.OPEN_ON_CHOSEN_TAB.save(true);
        Settings.START_TAB.save(StartTab.FEEDS);
    }

    @After
    public void restore() {
        StartTabRoute.settled();
        FeedsSubtabRoute.disarm();
        Settings.OPEN_ON_CHOSEN_TAB.resetToDefault();
        Settings.START_TAB.resetToDefault();
        Settings.FEEDS_SUBTAB.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.START_TAB + ":")) return line;
        }
        return null;
    }

    /** The main screen a start from the launcher icon creates, after the hook has seen it. */
    private static FbMainTabActivity launched() {
        FbMainTabActivity screen = StartTabRouteForTests.screen(StartTabRouteForTests.launcherStart());
        StartTabRoute.onActivityCreate(screen, null);
        return screen;
    }

    /** The Feeds tab most accounts have, showing All. */
    private static FeedFiltersFragment feeds() {
        FeedFiltersFragment feeds = new FeedFiltersFragment();
        feeds.filters.addAll(Arrays.asList(FeedType.TOP_STORIES, FeedType.FAVORITES, FeedType.MOST_RECENT));
        return feeds;
    }

    /** The Feeds tab that opens on the most recent posts, showing All. */
    private static FeedFiltersFragment recentFeeds() {
        FeedFiltersFragment feeds = new FeedFiltersFragment();
        feeds.filters.addAll(Arrays.asList(FeedType.MOST_RECENT_ALL, FeedType.MOST_RECENT_FAVORITES,
                FeedType.MOST_RECENT_FRIEND, FeedType.MOST_RECENT_GROUP, FeedType.MOST_RECENT_PAGE));
        return feeds;
    }

    @Test
    public void aStartOnFeedsOpensTheChosenFilterTheFirstTimeOnly() {
        Settings.FEEDS_SUBTAB.save(FeedsSubtab.FAVORITES);
        launched();
        FeedFiltersFragment feeds = feeds();

        feeds.onResume();

        assertEquals("Favorites wasn't picked", 1, feeds.picked);
        // Asked by its name on the most recent posts tab first, which this tab hasn't got.
        assertEquals(2, feeds.handled);
        assertEquals(FamilyNames.START_TAB + ": invoked 1, 1 found, 0 missing", statusLine());

        // Back from another tab, or after the person picked another filter: Facebook's own.
        feeds.picked = -1;
        feeds.onResume();
        assertEquals(-1, feeds.picked);
        assertEquals("the handler was called again", 2, feeds.handled);
    }

    @Test
    public void theMostRecentPostsTabIsAskedForItsOwnFilters() {
        FeedsSubtab[] asked = {FeedsSubtab.FAVORITES, FeedsSubtab.FRIENDS, FeedsSubtab.GROUPS, FeedsSubtab.PAGES};
        for (int i = 0; i < asked.length; i++) {
            Settings.FEEDS_SUBTAB.save(asked[i]);
            launched();
            FeedFiltersFragment feeds = recentFeeds();
            feeds.onResume();
            assertEquals(asked[i].name(), i + 1, feeds.picked);
            assertEquals(asked[i].name(), 1, feeds.handled);
        }
    }

    /**
     * Friends, Groups and Pages are only on the most recent posts tab. The tab most accounts have
     * is asked once, hasn't got them, and stays as it opened.
     */
    @Test
    public void aFilterTheFeedsTabHasntGotLeavesItAsItOpened() {
        for (FeedsSubtab subtab : new FeedsSubtab[]{FeedsSubtab.FRIENDS, FeedsSubtab.GROUPS, FeedsSubtab.PAGES}) {
            Settings.FEEDS_SUBTAB.save(subtab);
            launched();
            FeedFiltersFragment feeds = feeds();
            feeds.onResume();
            assertEquals(subtab.name(), 1, feeds.handled);
            assertEquals(subtab.name(), -1, feeds.picked);
            assertNull("the ask outlived the call", FeedsSubtabRoute.feedType(null));
        }
        assertEquals(FamilyNames.START_TAB + ": invoked 3, 0 found, 0 missing", statusLine());
    }

    @Test
    public void allAndEveryOtherStartAskForNothing() {
        // All is what Facebook opens on.
        launched();
        FeedFiltersFragment feeds = feeds();
        feeds.onResume();
        assertEquals(0, feeds.handled);

        // Another start tab.
        Settings.FEEDS_SUBTAB.save(FeedsSubtab.FAVORITES);
        Settings.START_TAB.save(StartTab.MARKETPLACE);
        launched();
        feeds.onResume();
        assertEquals(0, feeds.handled);

        // The switch off.
        Settings.START_TAB.save(StartTab.FEEDS);
        Settings.OPEN_ON_CHOSEN_TAB.save(false);
        launched();
        feeds.onResume();
        assertEquals(0, feeds.handled);
        Settings.OPEN_ON_CHOSEN_TAB.save(true);

        // A notification's start after one from the icon: the new main screen forgets the old ask.
        launched();
        Intent notification = StartTabRouteForTests.launcherStart()
                .putExtra(FacebookTabs.TARGET_TAB_ID, FacebookTabs.NOTIFICATIONS_ID);
        StartTabRoute.onActivityCreate(StartTabRouteForTests.screen(notification), null);
        feeds.onResume();
        assertEquals(0, feeds.handled);

        // A screen Android restores.
        StartTabRoute.onActivityCreate(StartTabRouteForTests.screen(StartTabRouteForTests.launcherStart()),
                new android.os.Bundle());
        feeds.onResume();
        assertEquals(0, feeds.handled);
    }

    @Test
    public void aMainScreenThatsGoneAsksForNothing() {
        Settings.FEEDS_SUBTAB.save(FeedsSubtab.FAVORITES);
        ActivityController<FbMainTabActivity> controller = Robolectric.buildActivity(FbMainTabActivity.class,
                StartTabRouteForTests.launcherStart());
        StartTabRoute.onActivityCreate(controller.get(), null);
        controller.create().destroy();
        FeedFiltersFragment feeds = feeds();

        feeds.onResume();

        assertEquals(0, feeds.handled);
    }

    @Test
    public void theHandlerReadsItsOwnFeedTypeTheRestOfTheTime() {
        assertSame(FeedType.FAVORITES, FeedsSubtabRoute.feedType(FeedType.FAVORITES));
        assertNull(FeedsSubtabRoute.feedType(null));
        // A link the main screen hands the tab picks what Facebook last showed.
        Settings.FEEDS_SUBTAB.save(FeedsSubtab.FAVORITES);
        launched();
        FeedFiltersFragment feeds = feeds();
        feeds.last = FeedType.MOST_RECENT;
        feeds.handleDeeplinkFromMainActivity(new Intent());
        assertEquals(2, feeds.picked);
        // That link came first, so it wins: the tab isn't asked for Favorites when it resumes.
        feeds.onResume();
        assertEquals("the link's filter was replaced", 2, feeds.picked);
        assertEquals(1, feeds.handled);
        assertEquals(FamilyNames.START_TAB + ": invoked 1, 0 found, 0 missing", statusLine());
        // The lookup's answer goes back as it came.
        assertEquals(5, FeedsSubtabRoute.filterFound(5));
        assertEquals(-1, FeedsSubtabRoute.filterFound(-1));
    }

    @Test
    public void theFeedTypeIsFacebooksOwnConstantByName() {
        assertSame(FeedType.FAVORITES, FeedsSubtabRoute.feedTypeNamed(FeedType.class, "favorites"));
        assertSame(FeedType.MOST_RECENT_PAGE, FeedsSubtabRoute.feedTypeNamed(FeedType.class, "most_recent_page"));
        assertNull("a name Facebook hasn't got", FeedsSubtabRoute.feedTypeNamed(FeedType.class, "most_recent_events"));
        assertNull("another class", FeedsSubtabRoute.feedTypeNamed(String.class, "favorites"));
    }

    /** Time enough for the looks at the Feeds tab to run. */
    private static void waitForTheLooks(int looks) {
        ShadowLooper.idleMainLooper(looks * 100L, TimeUnit.MILLISECONDS);
    }

    /** Time enough for [asks] asks of the chosen filter's page. */
    private static void waitForTheAsks(int asks) {
        ShadowLooper.idleMainLooper(asks * FeedsSubtabRoute.ASK_MS, TimeUnit.MILLISECONDS);
    }

    /** A filter picked through the handler never asks for its posts by itself, so it's told to (#330). */
    @Test
    public void thePickedFiltersPageIsToldToLoadOnceItShows() {
        Settings.FEEDS_SUBTAB.save(FeedsSubtab.FRIENDS);
        launched();
        FeedFiltersFragment feeds = recentFeeds();
        feeds.pageSwapped = false;
        feeds.listLoaded = false;

        feeds.onResume();
        assertEquals(2, feeds.picked);
        waitForTheLooks(3);
        assertEquals("All is still showing", 0, feeds.reselected);

        feeds.pageSwapped = true;
        waitForTheLooks(3);
        assertEquals("the new page has no list yet", 0, feeds.reselected);

        feeds.listLoaded = true;
        waitForTheLooks(1);
        assertEquals(1, feeds.reselected);
    }

    /** Facebook drops the ask for a while on a start on the Feeds tab, so it goes on until the request does. */
    @Test
    public void thePageIsAskedUntilTheRequestGoesOut() {
        Settings.FEEDS_SUBTAB.save(FeedsSubtab.FRIENDS);
        launched();
        FeedFiltersFragment feeds = recentFeeds();

        feeds.onResume();
        waitForTheLooks(1);
        assertEquals(1, feeds.reselected);
        assertTrue(FeedsSubtabRoute.awaitingPosts());
        waitForTheAsks(3);
        assertEquals("asked each second", 4, feeds.reselected);

        FeedsSubtabRoute.filterPostsRequested();
        assertFalse(FeedsSubtabRoute.awaitingPosts());
        waitForTheAsks(FeedsSubtabRoute.ASKS);
        assertEquals("asked again after the request", 4, feeds.reselected);
    }

    /** A page that never asks is left alone in the end. */
    @Test
    public void theAskingRunsOut() {
        Settings.FEEDS_SUBTAB.save(FeedsSubtab.FRIENDS);
        launched();
        FeedFiltersFragment feeds = recentFeeds();

        feeds.onResume();
        waitForTheLooks(1);
        waitForTheAsks(FeedsSubtabRoute.ASKS * 2);
        assertEquals(FeedsSubtabRoute.ASKS, feeds.reselected);
        assertFalse(FeedsSubtabRoute.awaitingPosts());
    }

    /** The person picked another filter, whose page has its own posts. */
    @Test
    public void theAskingStopsWhenThePageIsNoLongerShowing() {
        Settings.FEEDS_SUBTAB.save(FeedsSubtab.FRIENDS);
        launched();
        FeedFiltersFragment feeds = recentFeeds();

        feeds.onResume();
        waitForTheLooks(1);
        assertEquals(1, feeds.reselected);

        feeds.pageSwapped = false;
        waitForTheAsks(FeedsSubtabRoute.ASKS);
        assertEquals("asked while another page showed", 1, feeds.reselected);
        assertFalse(FeedsSubtabRoute.awaitingPosts());
    }

    /** A new start stops what the last one was asking for. */
    @Test
    public void disarmingStopsTheAsking() {
        Settings.FEEDS_SUBTAB.save(FeedsSubtab.FRIENDS);
        launched();
        FeedFiltersFragment feeds = recentFeeds();

        feeds.onResume();
        waitForTheLooks(1);
        FeedsSubtabRoute.disarm();
        waitForTheAsks(FeedsSubtabRoute.ASKS);
        assertEquals(1, feeds.reselected);
    }

    /** Another filter got there first, by a link or a tap, and has its own posts. */
    @Test
    public void aPageThatNeverShowsIsLeftAlone() {
        Settings.FEEDS_SUBTAB.save(FeedsSubtab.FRIENDS);
        launched();
        FeedFiltersFragment feeds = recentFeeds();
        feeds.pageSwapped = false;

        feeds.onResume();
        waitForTheLooks(60);
        assertEquals(0, feeds.reselected);

        // Looking has stopped by now.
        feeds.pageSwapped = true;
        waitForTheLooks(60);
        assertEquals(0, feeds.reselected);
    }

    @Test
    public void whatPicksNothingLoadsNothing() {
        // All is what Facebook opens on, and it loads by itself.
        launched();
        FeedFiltersFragment feeds = recentFeeds();
        feeds.onResume();
        waitForTheLooks(60);
        assertEquals(0, feeds.reselected);

        // A filter the tab hasn't got.
        Settings.FEEDS_SUBTAB.save(FeedsSubtab.FRIENDS);
        launched();
        FeedFiltersFragment other = feeds();
        other.onResume();
        waitForTheLooks(60);
        assertEquals(0, other.reselected);
    }

    @Test
    public void aFailedLoadFailsOpen() {
        Settings.FEEDS_SUBTAB.save(FeedsSubtab.FRIENDS);
        launched();
        FeedFiltersFragment feeds = recentFeeds();
        feeds.failReselect = new IllegalStateException("no second tap for this test");

        feeds.onResume();
        waitForTheLooks(1);

        assertEquals("the filter stays picked", 2, feeds.picked);
        assertEquals(1, feeds.reselected);
        String line = statusLine();
        assertTrue(line, line.contains("'feeds filter' hook (it threw java.lang.IllegalStateException)"));
        waitForTheAsks(FeedsSubtabRoute.ASKS);
        assertEquals("told again after a throw", 1, feeds.reselected);
        assertFalse(FeedsSubtabRoute.awaitingPosts());
    }

    /** A Feeds tab whose handler throws opens as Facebook chooses, and the status says so. */
    @Test
    public void aFailureFailsOpen() {
        Settings.FEEDS_SUBTAB.save(FeedsSubtab.FAVORITES);
        launched();
        FeedFiltersFragment feeds = feeds();
        feeds.failHandle = new IllegalStateException("no filters for this test");

        feeds.onResume();

        assertEquals("asked again after a throw", 1, feeds.handled);
        assertEquals(-1, feeds.picked);
        assertNull("the ask outlived the call", FeedsSubtabRoute.feedType(null));
        String line = statusLine();
        assertTrue(line, line.contains("'feeds filter' hook (it threw java.lang.IllegalStateException)"));
        // Asked once, even then.
        feeds.failHandle = null;
        feeds.onResume();
        assertEquals(1, feeds.handled);
    }
}
