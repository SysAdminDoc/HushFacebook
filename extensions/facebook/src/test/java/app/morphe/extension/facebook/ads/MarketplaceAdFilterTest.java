/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.ads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.ads.MarketplaceAdFilterForTests.RequestData;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * The Marketplace ad filter as Facebook's Networking module asks it about a request's body: the
 * feed's query goes out asking the server to skip its ads, the ads-only queries don't go out, and
 * every other request, every byte a rule doesn't name, and anything it can't read stays Facebook's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MarketplaceAdFilterTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String FEED = "MarketplaceHomeFeedQueryRendererQuery";

    @Before
    public void inBuild() {
        MarketplaceAdFilterForTests.inBuild(Boolean.TRUE);
        MarketplaceAdFilterForTests.forget();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    @After
    public void restore() {
        MarketplaceAdFilterForTests.inBuild(null);
        MarketplaceAdFilter.failNextForTests = null;
        MarketplaceAdFilterForTests.forget();
        PauseForTests.resume();
        Settings.HIDE_SPONSORED_MARKETPLACE_LISTINGS.resetToDefault();
        Settings.MARKETPLACE_ADVANCED_SEARCH.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
    }

    private static String counterLine() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(MarketplaceAdFilter.ROUTE + ":")) return line;
        }
        return null;
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.SPONSORED_MARKETPLACE + ":")) return line;
        }
        return null;
    }

    /** A form body with [json] percent-encoded the way encodeURIComponent writes it. */
    private static String body(String json) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : json.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "-_.!~*'()".indexOf(c) >= 0) {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(String.format("%02X", c));
            }
        }
        return "doc_id=5&variables=" + encoded + "&server_timestamps=true";
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) count++;
        return count;
    }

    @Test
    public void theSwitchStartsOnAndTheFeedQueryAsksToSkipItsAds() {
        assertTrue("the switch starts off", Settings.HIDE_SPONSORED_MARKETPLACE_LISTINGS.get());
        String sent = MarketplaceAdFilterForTests.requestBody(FEED, MarketplaceAdFilterForTests.feedBody("false", "null"));
        assertEquals("only the two values change", MarketplaceAdFilterForTests.feedBody("true", "true"), sent);
        assertEquals(MarketplaceAdFilter.ROUTE + ": 1 lists, 1 items, 1 removed. Last reason: "
                + MarketplaceAdFilter.SKIPPED + ". Removed: " + MarketplaceAdFilter.SKIPPED + " 1. Kinds: " + FEED + " 1",
                counterLine());
        assertEquals(FamilyNames.SPONSORED_MARKETPLACE + ": invoked 1, 1 found, 0 missing", statusLine());
    }

    /** Advanced search starts on and sends a search's plain words; off, the search goes out as typed. */
    @Test
    public void advancedSearchHasItsOwnSwitch() {
        String query = "MarketplaceSearchFeedPaginationQuery";
        String typed = body("{\"query\":\"ram -corsair\"}");
        assertTrue("advanced search starts off", Settings.MARKETPLACE_ADVANCED_SEARCH.get());
        String sent = MarketplaceAdFilterForTests.requestBody(query, typed);
        assertFalse(sent.contains("corsair"));
        assertNotNull(MarketplaceSearchSyntax.active());
        Settings.MARKETPLACE_ADVANCED_SEARCH.save(false);
        MarketplaceAdFilterForTests.forget();
        assertSame(typed, MarketplaceAdFilterForTests.requestBody(query, typed));
        assertNull(MarketplaceSearchSyntax.active());
    }

    /** A video ad that reaches Marketplace's feed isn't drawn, and the report counts it. */
    @Test
    public void aVideoAdIsntDrawn() {
        assertTrue(MarketplaceAdFilter.hidesVideoAd());
        assertEquals(MarketplaceAdFilter.ROUTE + ": 1 lists, 1 items, 1 removed. Last reason: "
                + MarketplaceAdFilter.NOT_DRAWN + ". Removed: " + MarketplaceAdFilter.NOT_DRAWN + " 1. Kinds: "
                + MarketplaceAdFilter.VIDEO_AD + " 1", counterLine());
        assertEquals(FamilyNames.SPONSORED_MARKETPLACE + ": invoked 1, 1 found, 0 missing", statusLine());
    }

    /** Off or paused, Facebook draws its video ad, and the report still shows one came. */
    @Test
    public void offOrPausedAVideoAdIsDrawn() {
        Settings.HIDE_SPONSORED_MARKETPLACE_LISTINGS.save(false);
        assertFalse(MarketplaceAdFilter.hidesVideoAd());
        assertEquals(MarketplaceAdFilter.ROUTE + ": 1 lists, 1 items, 0 removed. Kinds: "
                + MarketplaceAdFilter.VIDEO_AD + " 1", counterLine());
        Settings.HIDE_SPONSORED_MARKETPLACE_LISTINGS.resetToDefault();
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(MarketplaceAdFilter.hidesVideoAd());
        PauseForTests.resume();
        assertTrue(MarketplaceAdFilter.hidesVideoAd());
    }

    /** Without the patch in the build, a video ad is drawn and nothing is counted. */
    @Test
    public void withoutThePatchAVideoAdIsDrawn() {
        MarketplaceAdFilterForTests.inBuild(Boolean.FALSE);
        assertFalse(MarketplaceAdFilter.hidesVideoAd());
        assertNull(counterLine());
    }

    /** Every one of Facebook's four ads-only queries is held back, and counted as that. */
    @Test
    public void theAdsOnlyQueriesDontGoOut() {
        String body = body("{\"count\":4,\"cursor\":null}");
        for (String query : MarketplaceAdFilter.ADS_ONLY_QUERIES) {
            assertNull(query, MarketplaceAdFilterForTests.requestBody(query, body));
        }
        assertTrue(counterLine(), counterLine().contains(" 4 removed. Last reason: " + MarketplaceAdFilter.HELD_BACK));
        assertEquals(4, MarketplaceAdFilter.ADS_ONLY_QUERIES.length);
        for (String query : MarketplaceAdFilter.ADS_ONLY_QUERIES) {
            assertTrue(query, query.startsWith("MarketplaceHomeFeed") && query.contains("Ads"));
        }
    }

    /**
     * The three queries behind a listing page's ad rows are held back too, and the log says so.
     * The page's own queries go out as they were, and off or paused so do the ad rows' queries.
     */
    @Test
    public void aListingPagesAdsDontGoOut() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        String body = body("{\"count\":4,\"targetId\":\"1\"}");
        for (String query : MarketplaceAdFilter.LISTING_ADS_QUERIES) {
            assertNull(query, MarketplaceAdFilterForTests.requestBody(query, body));
        }
        String[] pages = {"MarketplacePDPContainerQuery", "MarketplacePDPSurfaceQuery",
                "MarketplacePDPTailSectionsContainerQuery", "MarketplacePDPRelatedSearchesSectionQueryRendererQuery"};
        for (String query : pages) {
            assertSame(query, body, MarketplaceAdFilterForTests.requestBody(query, body));
        }
        assertEquals(3, MarketplaceAdFilter.LISTING_ADS_QUERIES.length);
        assertTrue(counterLine(), counterLine().startsWith(MarketplaceAdFilter.ROUTE
                + ": 7 lists, 7 items, 3 removed. Last reason: " + MarketplaceAdFilter.HELD_BACK));
        String report = LogBufferManager.buildExportText();
        for (String query : MarketplaceAdFilter.LISTING_ADS_QUERIES) {
            assertEquals(report, 1, occurrences(report, "Marketplace ads: held back " + query
                    + ", one of the listing page's ad rows."));
        }

        Settings.HIDE_SPONSORED_MARKETPLACE_LISTINGS.save(false);
        for (String query : MarketplaceAdFilter.LISTING_ADS_QUERIES) {
            assertSame("off: " + query, body, MarketplaceAdFilterForTests.requestBody(query, body));
        }
        Settings.HIDE_SPONSORED_MARKETPLACE_LISTINGS.resetToDefault();
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        for (String query : MarketplaceAdFilter.LISTING_ADS_QUERIES) {
            assertSame("paused: " + query, body, MarketplaceAdFilterForTests.requestBody(query, body));
        }
    }

    /** A variable the query doesn't name isn't added, and one already true stays as it is. */
    @Test
    public void onlyTheVariablesAQueryNamesChange() {
        String one = body("{\"shouldSkipAdRequest\":false,\"count\":8}");
        assertEquals(body("{\"shouldSkipAdRequest\":true,\"count\":8}"),
                MarketplaceAdFilterForTests.requestBody(FEED, one));

        String already = body("{\"shouldSkipAdRequest\":true,\"shouldSkipBoostedListingAdRequest\":true}");
        assertSame(already, MarketplaceAdFilterForTests.requestBody(FEED, already));

        String neither = body("{\"count\":8,\"localOnly\":false}");
        assertSame("a Marketplace query naming neither goes out as it was",
                neither, MarketplaceAdFilterForTests.requestBody("MarketplaceHomeQuery", neither));
        assertTrue(counterLine(), counterLine().contains(" 3 lists, 3 items, 1 removed"));
    }

    /** Negative control: the names inside another object, or spelled another way, aren't the query's own. */
    @Test
    public void nestedOrLookalikeNamesStay() {
        String nested = body("{\"injectedUnitConfig\":{\"shouldSkipAdRequest\":false},\"list\":[{\"shouldSkipAdRequest\":null}],"
                + "\"note\":\"\\\"shouldSkipAdRequest\\\":false\"}");
        assertSame(nested, MarketplaceAdFilterForTests.requestBody(FEED, nested));
        String lookalike = body("{\"shouldSkipAdRequests\":false,\"ShouldSkipAdRequest\":false,"
                + "\"should\\u0053kipAdRequest\":false}");
        assertSame(lookalike, MarketplaceAdFilterForTests.requestBody(FEED, lookalike));
        String notBoolean = body("{\"shouldSkipAdRequest\":0,\"shouldSkipBoostedListingAdRequest\":\"false\"}");
        assertSame(notBoolean, MarketplaceAdFilterForTests.requestBody(FEED, notBoolean));
        assertTrue(counterLine(), counterLine().contains(" 0 removed"));
    }

    /** Other surfaces' requests, and Relay queries that aren't Marketplace's, go out unread and uncounted. */
    @Test
    public void otherRequestsAreLeftAlone() {
        String body = MarketplaceAdFilterForTests.feedBody("false", "false");
        assertSame(body, MarketplaceAdFilter.requestBody(body, new RequestData("RelayFBNetwork_EventsBookmarkSurfaceQuery", body)));
        assertSame(body, MarketplaceAdFilter.requestBody(body, new RequestData("react_native", body)));
        assertSame(body, MarketplaceAdFilter.requestBody(body, new RequestData(null, body)));
        assertSame(body, MarketplaceAdFilter.requestBody(body, new RequestData("MarketplaceHomeFeedAdsQueryRendererQuery", body)));
        assertSame("data that isn't React Native's map", body, MarketplaceAdFilter.requestBody(body, "trackingName"));
        assertNull(MarketplaceAdFilter.requestBody(null, new RequestData("RelayFBNetwork_" + FEED, null)));
        assertNull("no request reached the counter", counterLine());
    }

    /** A body it can't read is sent as Facebook wrote it. */
    @Test
    public void aBodyItCantReadGoesOutAsItWas() {
        String[] bodies = {
                "doc_id=5&variables=%7B%22shouldSkipAdRequest%22%3Afalse%7",
                "doc_id=5&variables=%7B%22shouldSkipAdRequest%22%3Afalse%ZZ%7D",
                "variables=%7B%7D&variables=%7B%22shouldSkipAdRequest%22%3Afalse%7D",
                "doc_id=5&query_variables=%7B%22shouldSkipAdRequest%22%3Afalse%7D",
                "{\"variables\":{\"shouldSkipAdRequest\":false}}",
                body("[{\"shouldSkipAdRequest\":false}]"),
                body("{\"shouldSkipAdRequest\":false,\"shouldSkipAdRequest\":false}"),
                body("{\"shouldSkipAdRequest\":false"),
                body("{\"shouldSkipAdRequest\":false} {}"),
                body("{\"shouldSkipAdRequest\" false}"),
                "doc_id=5&variables=%7B%22shouldSkipAdRequest%22%3Afalse%7Dé",
        };
        for (String body : bodies) {
            assertSame(body, body, MarketplaceAdFilterForTests.requestBody(FEED, body));
        }
        assertTrue(counterLine(), counterLine().contains(" 0 removed"));
    }

    /** Whitespace, plus signs for spaces and percent-encoded letters are read, and only the value changes. */
    @Test
    public void otherWaysOfWritingTheBodyAreRead() {
        String spaced = "variables=+%7B+%22shouldSkipAdRequest%22+%3A+false+%2C%22name%22%3A%22a+b%22%7D+&x=1";
        assertEquals("variables=+%7B+%22shouldSkipAdRequest%22+%3A+true+%2C%22name%22%3A%22a+b%22%7D+&x=1",
                MarketplaceAdFilterForTests.requestBody(FEED, spaced));
        String escapedLetters = "variables=%7B%22shouldSkipAdRequest%22%3A%66%61lse%7D";
        assertEquals("variables=%7B%22shouldSkipAdRequest%22%3Atrue%7D",
                MarketplaceAdFilterForTests.requestBody(FEED, escapedLetters));
        String unicode = body("{\"title\":\"café ☃\",\"shouldSkipBoostedListingAdRequest\":null}");
        assertEquals(body("{\"title\":\"café ☃\",\"shouldSkipBoostedListingAdRequest\":true}"),
                MarketplaceAdFilterForTests.requestBody(FEED, unicode));
    }

    @Test
    public void offTheRequestsAreFacebooks() {
        Settings.HIDE_SPONSORED_MARKETPLACE_LISTINGS.save(false);
        String feed = MarketplaceAdFilterForTests.feedBody("false", "false");
        assertSame(feed, MarketplaceAdFilterForTests.requestBody(FEED, feed));
        String ads = body("{\"count\":4}");
        assertSame(ads, MarketplaceAdFilterForTests.requestBody(MarketplaceAdFilter.ADS_ONLY_QUERIES[0], ads));
        // Still counted, so a report says the hook is reached and which queries went by.
        assertTrue(counterLine(), counterLine().startsWith(MarketplaceAdFilter.ROUTE + ": 2 lists, 2 items, 0 removed"));
    }

    @Test
    public void pausedTheRequestsAreFacebooks() {
        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[] {HushfacebookPause.Reason.SWITCH,
                HushfacebookPause.Reason.CRASH_LOOP, HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            assertFalse(why.name(), MarketplaceAdFilterForTests.holdsBackAnAdsQuery());
            assertFalse(why.name(), MarketplaceAdFilterForTests.asksTheFeedToSkipAds());
        }
        PauseForTests.resume();
        assertTrue(MarketplaceAdFilterForTests.holdsBackAnAdsQuery());
        assertTrue(MarketplaceAdFilterForTests.asksTheFeedToSkipAds());
    }

    @Test
    public void withoutThePatchTheHookDoesNothing() {
        MarketplaceAdFilterForTests.inBuild(Boolean.FALSE);
        String feed = MarketplaceAdFilterForTests.feedBody("false", "false");
        assertSame(feed, MarketplaceAdFilterForTests.requestBody(FEED, feed));
        String ads = body("{}");
        assertSame(ads, MarketplaceAdFilterForTests.requestBody(MarketplaceAdFilter.ADS_ONLY_QUERIES[0], ads));
        assertNull(statusLine());
        assertNull(counterLine());
    }

    @Test
    public void aFailureSendsTheRequestAsItWas() {
        MarketplaceAdFilter.failNextForTests = new IllegalStateException("probe");
        String ads = body("{}");
        assertSame(ads, MarketplaceAdFilterForTests.requestBody(MarketplaceAdFilter.ADS_ONLY_QUERIES[0], ads));
        assertTrue(statusLine(), statusLine().contains("threw java.lang.IllegalStateException"));
        assertNull("the next one is held back again",
                MarketplaceAdFilterForTests.requestBody(MarketplaceAdFilter.ADS_ONLY_QUERIES[0], ads));
    }

    /**
     * With Debug logging on, each Marketplace request has a line naming its query and the ad
     * variables' values, up to forty, then one per fifty. No line quotes the body.
     */
    @Test
    public void debugLoggingNamesEachQueryAndWhatWasDone() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        MarketplaceAdFilterForTests.requestBody(FEED, MarketplaceAdFilterForTests.feedBody("false", "null"));
        MarketplaceAdFilterForTests.requestBody(MarketplaceAdFilter.ADS_ONLY_QUERIES[1], body("{}"));
        MarketplaceAdFilterForTests.requestBody("MarketplaceHomeQuery", body("{\"count\":8}"));
        String report = LogBufferManager.buildExportText();
        assertEquals(report, 1, occurrences(report, "Marketplace ads: " + FEED + " asked to skip its ads "
                + "(shouldSkipAdRequest false, shouldSkipBoostedListingAdRequest null, now true)."));
        assertEquals(report, 1, occurrences(report, "Marketplace ads: held back "
                + MarketplaceAdFilter.ADS_ONLY_QUERIES[1] + ", one of the feed's ads-only queries."));
        assertEquals(report, 1, occurrences(report,
                "Marketplace ads: MarketplaceHomeQuery went out as it was, it names neither ad variable."));
        assertEquals(report, 0, occurrences(report, "29362048826717980"));
        assertEquals(report, 0, occurrences(report, "2.625"));

        LogBufferManager.clearLogBuffer();
        for (int i = 3; i < MarketplaceAdFilter.LOGGED_ONE_BY_ONE + 2 * MarketplaceAdFilter.SUMMED_UP_BY; i++) {
            MarketplaceAdFilterForTests.requestBody("MarketplaceHomeQuery", body("{}"));
        }
        report = LogBufferManager.buildExportText();
        assertEquals(report, MarketplaceAdFilter.LOGGED_ONE_BY_ONE - 3,
                occurrences(report, "Marketplace ads: MarketplaceHomeQuery went out"));
        assertEquals(report, 1, occurrences(report, "Marketplace ads: 50 Marketplace requests so far."));
        assertEquals(report, 1, occurrences(report, "Marketplace ads: 100 Marketplace requests so far."));
    }
}
