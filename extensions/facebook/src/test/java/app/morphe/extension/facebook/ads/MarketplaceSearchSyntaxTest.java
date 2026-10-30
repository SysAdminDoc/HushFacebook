/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.ads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
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

import java.net.URLDecoder;
import java.util.Arrays;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Marketplace's advanced search: quoted phrases a title must hold, minus words it mustn't, the
 * query Facebook gets in their place, and the listings that leave an answer for breaking a rule.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MarketplaceSearchSyntaxTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String HEAD = "MarketplaceSearchApp_MarketplaceSearchFeedHeadQuery";

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
        MarketplaceAdFilterForTests.forget();
        Settings.HIDE_SPONSORED_MARKETPLACE_LISTINGS.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    @Test
    public void plainTextHasNoRules() {
        assertNull(MarketplaceSearchSyntax.parse("ddr4 ram 16gb"));
        assertNull(MarketplaceSearchSyntax.parse("wi-fi router"));
        assertNull(MarketplaceSearchSyntax.parse(""));
        assertNull(MarketplaceSearchSyntax.parse(" - "));
    }

    @Test
    public void quotesAndMinusWordsBecomeRules() {
        MarketplaceSearchSyntax.Parsed parsed = MarketplaceSearchSyntax.parse("\"RAM\" ddr4 -PC -dell -\"gaming laptop\"");
        assertNotNull(parsed);
        assertEquals("RAM ddr4", parsed.plain);
        assertEquals(Arrays.asList("ram"), parsed.filter.required);
        assertEquals(Arrays.asList("pc", "dell", "gaming laptop"), parsed.filter.excluded);
    }

    @Test
    public void onlyMinusWordsLeaveTheTextAsTyped() {
        MarketplaceSearchSyntax.Parsed parsed = MarketplaceSearchSyntax.parse("-dell");
        assertNotNull(parsed);
        assertEquals("", parsed.plain);
        assertEquals(Arrays.asList("dell"), parsed.filter.excluded);
    }

    @Test
    public void anUnclosedQuoteRunsToTheEnd() {
        MarketplaceSearchSyntax.Parsed parsed = MarketplaceSearchSyntax.parse("\"16gb ram");
        assertNotNull(parsed);
        assertEquals(Arrays.asList("16gb ram"), parsed.filter.required);
    }

    @Test
    public void termsMatchAnywhereIgnoringCase() {
        MarketplaceSearchSyntax.Filter filter = MarketplaceSearchSyntax.parse("\"RAM\" -PC -dell -1500 -128").filter;
        assertNull(filter.broken("Corsair 16GB RAM DDR4"));
        assertNotNull(filter.broken("ram stick, PCIe card included"));
        assertNotNull(filter.broken("Gaming PC with 32GB RAM"));
        assertNotNull(filter.broken("DELL OptiPlex RAM"));
        assertNotNull(filter.broken("2021 RAM 1500 laramie"));
        assertNotNull(filter.broken("128GB (4 x 32GB) ADATA RAM"));
        assertNotNull(filter.broken("Monitor"));
    }

    @Test
    public void spacesInQuotesMakeATermExact() {
        MarketplaceSearchSyntax.Parsed parsed = MarketplaceSearchSyntax.parse("ram \"128 \" -\" pc \"");
        assertEquals(Arrays.asList("128 "), parsed.filter.required);
        assertEquals(Arrays.asList(" pc "), parsed.filter.excluded);
        assertEquals("ram 128", parsed.plain);
        assertNull(parsed.filter.broken("Server RAM 128 GB"));
        assertNull(parsed.filter.broken("RAM kit 128"));
        assertNotNull(parsed.filter.broken("128GB RAM"));
        assertNull(parsed.filter.broken("RAM 128 PCIe card"));
        assertNotNull(parsed.filter.broken("RAM 128 for gaming PC"));
    }

    @Test
    public void punctuationCountsAsASpace() {
        assertTrue(MarketplaceSearchSyntax.holds("16gb-ram kit", "16gb ram"));
        assertTrue(MarketplaceSearchSyntax.holds("16gb  ram", "16gb ram"));
        assertTrue(MarketplaceSearchSyntax.holds("ram (128)", "128 "));
        assertFalse(MarketplaceSearchSyntax.holds("16gbram", "16gb ram"));
    }

    @Test
    public void savedTermsInQuotesKeepTheirSpaces() {
        assertEquals(Arrays.asList("pc", "128 ", " dell "), MarketplaceSearchSyntax.terms("PC, \"128 \", -\" Dell \""));
    }

    @Test
    public void everyTitleFieldIsRead() throws Exception {
        String edge = "{\"node\":{\"listing\":{\"marketplace_listing_title\":\"2021 RAM\",\"custom_title\":\"2021 RAM 1500 laramie\","
                + "\"custom_sub_titles_with_rendering_flags\":[{\"subtitle\":\"61K km\"}]}}}";
        assertEquals("2021 RAM | 2021 RAM 1500 laramie",
                MarketplaceSearchSyntax.title(edge, MarketplaceSearchAds.Parser.whole(edge)));
        String text = "{\"node\":{\"title\":{\"text\":\"Desk\"}}}";
        assertEquals("Desk", MarketplaceSearchSyntax.title(text, MarketplaceSearchAds.Parser.whole(text)));
    }

    @Test
    public void onlyResultQueriesCarryTheRules() {
        assertTrue(MarketplaceAdFilter.resultsQuery("MarketplaceSearchApp_MarketplaceSearchFeedHeadQuery"));
        assertTrue(MarketplaceAdFilter.resultsQuery("MarketplaceSearchFeedPaginationQuery"));
        assertFalse(MarketplaceAdFilter.resultsQuery("MarketplaceSearchTypeaheadQuery"));
        assertFalse(MarketplaceAdFilter.resultsQuery("MarketplaceSearchSuggestionsQuery"));
    }

    private static String searchBody(String variablesJson) throws Exception {
        return "doc_id=1&variables=" + java.net.URLEncoder.encode(variablesJson, "UTF-8") + "&fb_api_req_friendly_name=x";
    }

    private static String variables(String body) throws Exception {
        int[] range = MarketplaceAdFilter.variablesParameter(body);
        return URLDecoder.decode(body.substring(range[0], range[1]), "UTF-8");
    }

    @Test
    public void theRequestGetsThePlainWords() throws Exception {
        String body = searchBody("{\"count\":24,\"params\":{\"bqf\":{\"callsite\":\"X\",\"query\":\"\\\"RAM\\\" -PC -dell\"}}}");
        String sent = MarketplaceSearchSyntax.request(HEAD, body);
        assertEquals("{\"count\":24,\"params\":{\"bqf\":{\"callsite\":\"X\",\"query\":\"RAM" + spaces("\"RAM\" -PC -dell") + "\"}}}", variables(sent));
        assertTrue(sent.endsWith("&fb_api_req_friendly_name=x"));
        assertEquals("\"ram\" -pc -dell", MarketplaceSearchSyntax.active().toString());
    }

    @Test
    public void aRequestWithoutRulesGoesAsItWasAndClearsThem() throws Exception {
        MarketplaceSearchSyntax.request(HEAD, searchBody("{\"query\":\"-dell laptop\"}"));
        assertNotNull(MarketplaceSearchSyntax.active());
        String body = searchBody("{\"query\":\"laptop\"}");
        assertSame(body, MarketplaceSearchSyntax.request(HEAD, body));
        assertNull(MarketplaceSearchSyntax.active());
    }

    @Test
    public void otherVariablesStayEvenWithQuotes() throws Exception {
        String body = searchBody("{\"cursor\":\"-abc \\\"x\\\"\",\"query\":\"monitor\"}");
        assertSame(body, MarketplaceSearchSyntax.request(HEAD, body));
    }

    private static String titled(int id, String title) {
        return "{\"node\":{\"__typename\":\"MarketplaceFeedListingStoryObject\",\"listing\":{\"id\":\"" + id
                + "\",\"marketplace_listing_title\":\"" + title + "\"}},\"cursor\":\"c" + id + "\"}";
    }

    @Test
    public void anAnswerLosesTheListingsThatBreakARule() {
        MarketplaceSearchSyntax.Filter filter = MarketplaceSearchSyntax.parse("\"RAM\" -PC").filter;
        MarketplaceSearchAds.Answer answer = new MarketplaceSearchAds.Answer(HEAD, false, filter);
        String text = MarketplaceSearchAdsTest.answer(
                titled(1, "16GB RAM"), titled(2, "Gaming PC 16GB RAM"), titled(3, "Monitor"), titled(4, "DDR4 RAM kit"),
                MarketplaceSearchAdsTest.adStory(5));
        String read = answer.read(text);
        assertTrue(read.contains("16GB RAM\""));
        assertTrue(read.contains("DDR4 RAM kit"));
        assertFalse(read.contains("Gaming PC"));
        assertFalse(read.contains("Monitor"));
        // With Hide sponsored Marketplace listings off, an ad still goes when its title breaks a rule.
        assertFalse(read.contains("MarketplaceFeedAdStory"));
    }

    @Test
    public void adsAndTitlesComeOutTogether() {
        MarketplaceSearchSyntax.Filter filter = MarketplaceSearchSyntax.parse("-dell").filter;
        MarketplaceSearchAds.Answer answer = new MarketplaceSearchAds.Answer(HEAD, true, filter);
        String read = answer.read(MarketplaceSearchAdsTest.answer(
                titled(1, "Dell monitor"), titled(2, "LG monitor"), MarketplaceSearchAdsTest.adStory(3)));
        assertFalse(read.contains("Dell monitor"));
        assertTrue(read.contains("LG monitor"));
        assertFalse(read.contains("MarketplaceFeedAdStory"));
    }

    @Test
    public void aStreamedListingThatBreaksARuleLeavesNoGap() {
        MarketplaceSearchSyntax.Filter filter = MarketplaceSearchSyntax.parse("-dell").filter;
        MarketplaceSearchAds.Answer answer = new MarketplaceSearchAds.Answer(HEAD, false, filter);
        answer.read(MarketplaceSearchAdsTest.answer(titled(0, "LG monitor")));
        String gone = answer.read(MarketplaceSearchAdsTest.streamed(1, titled(1, "Dell monitor")));
        assertTrue(gone.startsWith("{\"data\":null"));
        String moved = answer.read(MarketplaceSearchAdsTest.streamed(2, titled(2, "Asus monitor")));
        assertTrue(moved.contains("\"edges\",1]"));
    }

    @Test
    public void aRequestWithNoQueryTextKeepsTheRules() throws Exception {
        MarketplaceSearchSyntax.request(HEAD, searchBody("{\"query\":\"ram -corsair\"}"));
        String body = searchBody("{\"count\":24,\"cursor\":\"abc\",\"searchSessionID\":\"s-1\"}");
        assertSame(body, MarketplaceSearchSyntax.request(HEAD, body));
        assertEquals("-corsair", MarketplaceSearchSyntax.active().toString());
    }

    @Test
    public void unquotedWordsWithMinusWordsGoOutPlain() throws Exception {
        String sent = MarketplaceSearchSyntax.request(HEAD, searchBody("{\"params\":{\"query\":\"ram -corsair\"}}"));
        assertEquals("{\"params\":{\"query\":\"ram" + spaces("ram -corsair") + "\"}}", variables(sent));
    }

    private static String spaces(String typed) {
        return MarketplaceSearchSyntax.spaces(MarketplaceSearchSyntax.parse(typed).filter);
    }

    @Test
    public void eachSetOfRulesGoesOutWithItsOwnSpaces() throws Exception {
        String one = variables(MarketplaceSearchSyntax.request(HEAD, searchBody("{\"query\":\"ram -corsair\"}")));
        String two = variables(MarketplaceSearchSyntax.request(HEAD, searchBody("{\"query\":\"ram -corsair -ddr4\"}")));
        assertTrue(one.startsWith("{\"query\":\"ram "));
        assertTrue(two.startsWith("{\"query\":\"ram "));
        assertNotEquals(one, two);
        assertEquals(one, variables(MarketplaceSearchSyntax.request(HEAD, searchBody("{\"query\":\"ram -corsair\"}"))));
        String plain = searchBody("{\"query\":\"ram\"}");
        assertSame(plain, MarketplaceSearchSyntax.request(HEAD, plain));
    }


    @Test
    public void searchIdsAndKindsAreNotQueryText() {
        assertTrue(MarketplaceSearchSyntax.queryText("query"));
        assertTrue(MarketplaceSearchSyntax.queryText("rawQuery"));
        assertTrue(MarketplaceSearchSyntax.queryText("query_text"));
        assertFalse(MarketplaceSearchSyntax.queryText("searchSessionID"));
        assertFalse(MarketplaceSearchSyntax.queryText("search_type"));
        assertFalse(MarketplaceSearchSyntax.queryText("queryID"));
    }

    @Test
    public void savedListsSplitByCommas() {
        assertEquals(Arrays.asList("ram", "ddr4", "gaming pc"), MarketplaceSearchSyntax.terms(" RAM, ddr4,\n-\"Gaming  PC\", ram"));
        assertTrue(MarketplaceSearchSyntax.terms(" , ").isEmpty());
    }

    @Test
    public void theDialogWritesItsListsAsRules() {
        assertEquals("ram \"ddr5\" -corsair -\"gaming pc\" in:melbourne in:\"weir views\"",
                MarketplaceSearchSyntax.compose("ram", "DDR5", "corsair, Gaming PC", "Melbourne, Weir Views"));
        // The rules it held give way to the lists; its own words stay.
        assertEquals("ram -dell", MarketplaceSearchSyntax.compose("ram -corsair \"ddr4\" in:vic", "", "dell", ""));
        assertEquals("ram", MarketplaceSearchSyntax.compose("ram -corsair", "", "", ""));
        assertEquals("ram \"128 \"", MarketplaceSearchSyntax.compose("ram", "\"128 \"", "", ""));
    }

    @Test
    public void theDialogReadsTheListsOutOfTheSearch() {
        String[] lists = MarketplaceSearchSyntax.lists("ram \"ddr5\" \"128 \" -corsair -\"gaming pc\" in:melbourne");
        assertEquals("ddr5, \"128 \"", lists[0]);
        assertEquals("corsair, gaming pc", lists[1]);
        assertEquals("melbourne", lists[2]);
        assertEquals(5, MarketplaceSearchSyntax.ruleCount("ram \"ddr5\" \"128 \" -corsair -\"gaming pc\" in:melbourne"));
        assertEquals(0, MarketplaceSearchSyntax.ruleCount("ram"));
    }

    @Test
    public void placesAreRulesThatGoNoFurther() throws Exception {
        String sent = variables(MarketplaceSearchSyntax.request(HEAD, searchBody("{\"query\":\"ram in:melbourne\"}")));
        assertEquals("{\"query\":\"ram" + spaces("in:melbourne") + "\"}", sent);
        assertEquals(Arrays.asList("melbourne"), MarketplaceSearchSyntax.active().places);
        assertNull(MarketplaceSearchSyntax.parse("ram within: reach"));
    }

    private static String placed(int id, String title, String city, String state) {
        return "{\"node\":{\"__typename\":\"MarketplaceFeedListingStoryObject\",\"listing\":{\"id\":\"" + id
                + "\",\"marketplace_listing_title\":\"" + title + "\",\"location\":{\"reverse_geocode\":{\"city\":\""
                + city + "\",\"state\":\"" + state + "\"}}}},\"cursor\":\"c" + id + "\"}";
    }

    @Test
    public void placesKeepOnlyListingsThere() {
        MarketplaceSearchSyntax.Filter filter = new MarketplaceSearchSyntax.Filter(
                Arrays.<String>asList(), Arrays.<String>asList(), Arrays.asList("melbourne", "vic"));
        MarketplaceSearchAds.Answer answer = new MarketplaceSearchAds.Answer(HEAD, false, filter);
        String read = answer.read(MarketplaceSearchAdsTest.answer(
                placed(1, "Desk A", "Melbourne", "VIC"), placed(2, "Desk B", "Sydney", "NSW"),
                placed(3, "Desk C", "Geelong", "VIC"), titled(4, "Desk D")));
        assertTrue(read.contains("Desk A"));
        assertFalse(read.contains("Desk B"));
        assertTrue(read.contains("Desk C"));
        // A listing with no location keeps its place.
        assertTrue(read.contains("Desk D"));
    }

    @Test
    public void locationReadsCityAndState() throws Exception {
        String edge = placed(1, "Desk", "North Melbourne", "VIC");
        MarketplaceSearchAds.Value value = MarketplaceSearchAds.Parser.whole(edge);
        assertEquals("North Melbourne, VIC", MarketplaceSearchSyntax.location(edge, value));
        assertNull(MarketplaceSearchSyntax.location(titled(2, "Desk"), MarketplaceSearchAds.Parser.whole(titled(2, "Desk"))));
    }

    @Test
    public void listingsWithoutTitlesStay() {
        MarketplaceSearchSyntax.Filter filter = MarketplaceSearchSyntax.parse("\"RAM\"").filter;
        MarketplaceSearchAds.Answer answer = new MarketplaceSearchAds.Answer(HEAD, false, filter);
        String text = MarketplaceSearchAdsTest.answer("{\"node\":{\"__typename\":\"MarketplaceSearchModule\"}}");
        assertSame(text, answer.read(text));
    }
}
