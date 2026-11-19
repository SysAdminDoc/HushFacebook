/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.time.Duration;

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

    /**
     * #493: on a cold start the guard can hide a whole page with the end of the list already on
     * screen (stories, the Reels shelf and the loading row). Nothing scrolls, so nothing ran the
     * fetcher and Home waited for a swipe. Once a burst of hidden edges settles, the fetcher runs
     * again with what it was last given, and that run asks for more.
     */
    @Test
    public void aPageHiddenAtTheEndRunsTheFetcherAgainWithNoScroll() {
        Fetcher fetcher = new Fetcher();
        Object list = new Object();
        TailFetch.scrolled(fetcher, list, 0, 3, 3);
        for (int i = 0; i < 40; i++) TailFetch.hidEdge();
        idle(TailFetch.RERUN_MS - 1);
        assertEquals("not in the middle of the burst", 0, fetcher.runs);
        idle(1);
        assertEquals("once the burst settles", 1, fetcher.runs);
        assertSame("on the list it was given", list, fetcher.list);
        assertEquals("the rows it was given", "0 3 3", fetcher.given);
        assertTrue("and that run asks for more", fetcher.asked);
        idle(TailFetch.RETRY_MS * 3);
        assertEquals("nothing hidden since, nothing more", 1, fetcher.runs);
    }

    /** With the end of the list off screen nothing is stuck, so a hidden page runs nothing. */
    @Test
    public void theEndOffScreenRunsNothing() {
        Fetcher fetcher = new Fetcher();
        TailFetch.scrolled(fetcher, new Object(), 0, 5, 20);
        TailFetch.hidEdge();
        idle(TailFetch.RERUN_MS + TailFetch.RETRY_MS);
        assertEquals(0, fetcher.runs);
    }

    /** A run waits out what's left of RETRY_MS after the last ask, so the ask it makes isn't refused. */
    @Test
    public void aRunWaitsOutTheGapAfterAnAsk() {
        Fetcher fetcher = new Fetcher();
        TailFetch.scrolled(fetcher, new Object(), 0, 3, 3);
        TailFetch.hidEdge();
        assertTrue("a swipe's own ask", TailFetch.askAgain(2, 3));
        TailFetch.hidEdge();
        idle(TailFetch.RERUN_MS);
        assertEquals("inside the gap", 0, fetcher.runs);
        idle(TailFetch.RETRY_MS - TailFetch.RERUN_MS);
        assertEquals("the gap over", 1, fetcher.runs);
        assertTrue(fetcher.asked);
    }

    /**
     * A feed that keeps sending pages the guard empties runs the fetcher RERUNS times in a row at
     * most. A scroll of the user's own counts again from nothing.
     */
    @Test
    public void runsStopAfterAFewUntilAScroll() {
        Fetcher fetcher = new Fetcher();
        Object list = new Object();
        TailFetch.scrolled(fetcher, list, 0, 3, 3);
        for (int page = 0; page < TailFetch.RERUNS + 3; page++) {
            TailFetch.hidEdge();
            idle(TailFetch.RERUN_MS + TailFetch.RETRY_MS);
        }
        assertEquals(TailFetch.RERUNS, fetcher.runs);
        TailFetch.scrolled(fetcher, list, 0, 3, 3);
        TailFetch.hidEdge();
        idle(TailFetch.RERUN_MS + TailFetch.RETRY_MS);
        assertEquals("a scroll starts the count over", TailFetch.RERUNS + 1, fetcher.runs);
    }

    /** Paused, a hidden page runs nothing, and nor does one hidden before any fetcher ran. */
    @Test
    public void pausedOrNoFetcherRunsNothing() {
        TailFetch.hidEdge();
        idle(TailFetch.RERUN_MS + TailFetch.RETRY_MS);
        Fetcher fetcher = new Fetcher();
        TailFetch.scrolled(fetcher, new Object(), 0, 3, 3);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        TailFetch.hidEdge();
        idle(TailFetch.RERUN_MS + TailFetch.RETRY_MS);
        assertEquals(0, fetcher.runs);
    }

    private static void idle(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    /** Stands in for the patched scroll fetcher: its onScroll opens with the hook, then asks at the end. */
    static final class Fetcher implements TailFetch.Scroller {
        int runs;
        Object list;
        String given;
        boolean asked;

        @Override
        public void hushfacebookScroll(Object list, int first, int count, int rows) {
            runs++;
            this.list = list;
            given = first + " " + count + " " + rows;
            TailFetch.scrolled(this, list, first, count, rows);
            asked = TailFetch.askAgain(first + count - 1, rows);
        }
    }
}
