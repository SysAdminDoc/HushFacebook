/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Looper;

import com.facebook.graphql.model.GraphQLPagesYouMayLikeFeedUnit;
import com.facebook.graphql.model.GraphQLStory;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The bottom of Home's hint (#258, Discussion #371): once the feed guard has hidden ten suggestions
 * in a row since the last post it kept, the end of Home's list on screen (or Facebook's bottom marker
 * coming into view) says why Home ends, once. Fewer, a kept post since, other kinds of hidden posts,
 * rows above the end, the marker leaving view, paused, or before the settings are ready, it says
 * nothing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FeedEndHintTest {
    /** Stands in for GraphQLFeedStoryCategory: only the constant names matter to the guard. */
    private enum Category { ORGANIC, SPONSORED }

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        FeedEndHint.resetForTests();
        TailFetch.resetForTests();
        ShadowToast.reset();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_SUGGESTED_POSTS.resetToDefault();
        Settings.HIDE_SUGGESTED_FOR_YOU.resetToDefault();
        Settings.HIDE_SPONSORED_POSTS.resetToDefault();
        FeedEndHint.resetForTests();
        TailFetch.resetForTests();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    /** The feed guard hides this many "Pages you may like" units, the way it would on Home. */
    private static void hideSuggestions(int count) {
        for (int i = 0; i < count; i++) {
            assertTrue(FeedGuardForTests.hides(Category.ORGANIC, new GraphQLPagesYouMayLikeFeedUnit()));
        }
    }

    /** The bottom of Home comes into view, and the toast it showed, if any. */
    private static String reachTheBottom() {
        FeedEndHint.endVisible(true);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return ShadowToast.getTextOfLatestToast();
    }

    /** Home's scroll fetcher runs with this row the last one on screen, and the toast it showed, if any. */
    private static String scrollTo(int lastRow, int rows, long nowMs) {
        TailFetch.askAgain(lastRow, rows, nowMs);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return ShadowToast.getTextOfLatestToast();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.SUGGESTED_POSTS + ":")) return line;
        }
        return "";
    }

    @Test
    public void tenSuggestionsHiddenInARowSayWhyHomeEnds() {
        hideSuggestions(FeedEndHint.SHOWN_AFTER);
        assertEquals("Hush hid the suggested posts after this, so Home ends here.", reachTheBottom());
        String line = statusLine();
        assertTrue(line, line.contains(FeedEndHint.SHOWN + " 1"));
    }

    @Test
    public void theEndOfTheListSaysWhyWithoutFacebooksMarker() {
        // On a phone Facebook kept paging suggestions and its bottom marker never mounted.
        hideSuggestions(FeedEndHint.SHOWN_AFTER);
        assertEquals("Hush hid the suggested posts after this, so Home ends here.", scrollTo(9, 10, 1_000));
        assertTrue(statusLine(), statusLine().contains(FeedEndHint.SHOWN + " 1"));
        ShadowToast.reset();
        assertNull("once per start, whichever end says it", reachTheBottom());
    }

    @Test
    public void rowsAboveTheEndOfTheListSayNothing() {
        hideSuggestions(FeedEndHint.SHOWN_AFTER);
        assertNull(scrollTo(7, 10, 1_000));
        assertTrue(scrollTo(9, 10, 1_000) != null);
    }

    @Test
    public void theWaitBetweenAsksDoesntHoldItBack() {
        hideSuggestions(FeedEndHint.SHOWN_AFTER - 1);
        assertNull(scrollTo(9, 10, 1_000));
        // The page Facebook sent for that ask was more hidden suggestions, and the next swipe comes
        // sooner than the fetcher may ask again.
        hideSuggestions(1);
        assertTrue(scrollTo(9, 10, 1_000 + TailFetch.RETRY_MS / 2) != null);
    }

    @Test
    public void theEndOfTheListAfterAKeptPostSaysNothing() {
        hideSuggestions(FeedEndHint.SHOWN_AFTER);
        assertFalse(FeedGuardForTests.hides(Category.ORGANIC, new GraphQLStory()));
        assertNull(scrollTo(9, 10, 1_000));
    }

    @Test
    public void itSaysSoOncePerStart() {
        hideSuggestions(FeedEndHint.SHOWN_AFTER);
        reachTheBottom();
        ShadowToast.reset();
        hideSuggestions(FeedEndHint.SHOWN_AFTER);
        assertNull(reachTheBottom());
        assertTrue(statusLine(), statusLine().contains(FeedEndHint.SHOWN + " 1"));
    }

    @Test
    public void fewerSaysNothing() {
        hideSuggestions(FeedEndHint.SHOWN_AFTER - 1);
        assertNull(reachTheBottom());
        assertFalse(statusLine().contains(FeedEndHint.SHOWN));
        // It isn't spent: more suggestions hidden before the next time the bottom shows still count.
        hideSuggestions(1);
        assertTrue(reachTheBottom() != null);
    }

    @Test
    public void suggestionsFlaggedForYouCountToo() {
        Settings.HIDE_SUGGESTED_FOR_YOU.save(true);
        for (int i = 0; i < FeedEndHint.SHOWN_AFTER; i++) {
            assertTrue(FeedGuardForTests.hidesRecommended(Category.ORGANIC, new GraphQLStory(),
                    FeedGuardForTests.recommendationContext(true)));
        }
        assertTrue(reachTheBottom() != null);
    }

    @Test
    public void aKeptPostStartsTheCountOver() {
        hideSuggestions(FeedEndHint.SHOWN_AFTER);
        assertFalse(FeedGuardForTests.hides(Category.ORGANIC, new GraphQLStory()));
        hideSuggestions(FeedEndHint.SHOWN_AFTER - 1);
        assertNull(reachTheBottom());
    }

    @Test
    public void hiddenAdsNeitherCountNorStartItOver() {
        hideSuggestions(FeedEndHint.SHOWN_AFTER - 1);
        for (int i = 0; i < FeedEndHint.SHOWN_AFTER; i++) {
            assertTrue(FeedGuardForTests.hides(Category.SPONSORED, new Object()));
        }
        assertNull(reachTheBottom());
        hideSuggestions(1);
        assertTrue(reachTheBottom() != null);
    }

    @Test
    public void suggestionsKeptWithTheSwitchOffNeverCount() {
        Settings.HIDE_SUGGESTED_POSTS.save(false);
        for (int i = 0; i < FeedEndHint.SHOWN_AFTER; i++) {
            assertFalse(FeedGuardForTests.hides(Category.ORGANIC, new GraphQLPagesYouMayLikeFeedUnit()));
        }
        assertNull(reachTheBottom());
    }

    @Test
    public void theMarkerLeavingViewSaysNothing() {
        hideSuggestions(FeedEndHint.SHOWN_AFTER);
        FeedEndHint.endVisible(false);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(ShadowToast.getTextOfLatestToast());
        assertTrue(reachTheBottom() != null);
    }

    @Test
    public void pausedSaysNothing() {
        hideSuggestions(FeedEndHint.SHOWN_AFTER);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertNull(reachTheBottom());
        PauseForTests.resume();
        assertTrue(reachTheBottom() != null);
    }

    @Test
    public void beforeTheSettingsAreReadySaysNothing() {
        hideSuggestions(FeedEndHint.SHOWN_AFTER);
        SettingsContextRule.withoutContext(() -> FeedEndHint.endVisible(true));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(ShadowToast.getTextOfLatestToast());
        assertTrue(reachTheBottom() != null);
    }
}
