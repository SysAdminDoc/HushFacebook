/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/** Home's extra tail fetch: when it asks for the next page that the scroll fetcher wouldn't. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class TailFetchTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for GraphQLFeedStoryCategory: only the constant names matter to the guard. */
    enum Category { ORGANIC, SPONSORED }

    @Before
    public void startClean() {
        TailFetch.resetForTests();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        TailFetch.resetForTests();
        FeedFilterCounters.clear();
    }

    /** Before the guard has hidden anything the fetcher decides as it ships, end of the list or not. */
    @Test
    public void nothingHiddenAsksNothing() {
        assertFalse(TailFetch.askAgain(9, 10, 1_000));
        assertFalse(FeedGuardForTests.hides(Category.ORGANIC, new Object()));
        assertFalse("a kept edge isn't a hidden one", TailFetch.askAgain(9, 10, 1_000));
    }

    /**
     * Once the guard hides an edge, the end of the list on screen asks again: a row well above the
     * end, or a list with no rows, leaves the fetcher's answer.
     */
    @Test
    public void aHiddenEdgeMakesTheEndOfTheListAsk() {
        assertTrue(FeedGuardForTests.hides(Category.SPONSORED, new Object()));
        assertFalse("well above the end", TailFetch.askAgain(7, 40, 1_000));
        assertFalse("no rows yet", TailFetch.askAgain(0, 0, 1_000));
        assertTrue("the loading row on screen", TailFetch.askAgain(9, 10, 1_000));
    }

    /**
     * #139: within AHEAD_ROWS of the end it asks before the end comes, once for each length of the
     * list, so a load already running isn't asked for on every scroll, and new rows ask again.
     */
    @Test
    public void nearTheEndAsksAheadOncePerLength() {
        TailFetch.hidEdge();
        int rows = 30;
        int first = rows - 1 - TailFetch.AHEAD_ROWS;
        assertFalse("a row before the window", TailFetch.askAgain(first - 1, rows, 1_000));
        assertTrue("the window's first row", TailFetch.askAgain(first, rows, 1_000));
        assertFalse("the same length again", TailFetch.askAgain(first + 2, rows, 1_100));
        assertFalse("the same length, much later", TailFetch.askAgain(first + 3, rows, 60_000));
        assertFalse("the page landed, end out of reach", TailFetch.askAgain(first + 3, rows + 4, 60_100));
        assertTrue("the page landed, end in reach", TailFetch.askAgain(first + 4, rows + 4, 60_150));
        assertTrue("the end still asks on its own clock", TailFetch.askAgain(rows + 3, rows + 4, 60_200));
    }

    /** Before the guard has hidden anything, or paused, the window ahead asks nothing either. */
    @Test
    public void aheadNeedsAHiddenEdgeAndNoPause() {
        assertFalse(TailFetch.askAgain(25, 30, 1_000));
        TailFetch.hidEdge();
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(TailFetch.askAgain(25, 30, 1_000));
        PauseForTests.resume();
        assertTrue(TailFetch.askAgain(25, 30, 1_000));
    }

    /** At most one ask every RETRY_MS, however often the fetcher runs at the end of the list. */
    @Test
    public void asksAreRateLimited() {
        TailFetch.hidEdge();
        assertTrue(TailFetch.askAgain(9, 10, 5_000));
        assertFalse(TailFetch.askAgain(9, 10, 5_000 + TailFetch.RETRY_MS - 1));
        assertFalse(TailFetch.askAgain(10, 11, 5_000 + TailFetch.RETRY_MS / 2));
        assertTrue(TailFetch.askAgain(9, 10, 5_000 + TailFetch.RETRY_MS));
    }

    /** Paused, the fetcher decides as it ships even after the guard hid something. */
    @Test
    public void pausedAsksNothing() {
        TailFetch.hidEdge();
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(TailFetch.askAgain(9, 10, 1_000));
        PauseForTests.resume();
        assertTrue(TailFetch.askAgain(9, 10, 1_000));
    }
}
