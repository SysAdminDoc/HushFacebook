/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.ads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/** Response evidence names known schema fields and their types, without changing response delivery. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MarketplaceResponseDiagnosticsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String THEMED = "MarketplaceHomeFeedAdsThemedQuery";
    private static final String RELATED = "MarketplaceProductDetailsPageRelatedAdsDetailQuery";
    private static final String SEARCH = "MarketplaceSearchApp_MarketplaceSearchFeedHeadQuery";
    private static final String SHAPE = "Marketplace shape: ";

    @Before
    public void prepare() {
        MarketplaceAdFilterForTests.inBuild(Boolean.TRUE);
        MarketplaceAdFilterForTests.forget();
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
    }

    @After
    public void restore() {
        MarketplaceAdFilterForTests.inBuild(null);
        MarketplaceAdFilterForTests.forget();
        BaseSettings.DEBUG.resetToDefault();
        Settings.HIDE_SPONSORED_MARKETPLACE_LISTINGS.resetToDefault();
        PauseForTests.resume();
        LogBufferManager.clearLogBuffer();
    }

    private static String response() {
        return "{\"data\":{\"edges\":[{\"node\":{\"__typename\":\"MarketplaceFeedGeneralListingObject\","
                + "\"listing\":{\"__typename\":\"GroupCommerceProductItem\",\"is_shipping_offered\":true,"
                + "\"shipping_offer_type\":\"SensitiveDeliveryChoice\",\"id\":\"987654321012345\","
                + "\"marketplace_listing_title\":\"SensitiveListingText\","
                + "\"location\":{\"latitude\":34.123456789,\"longitude\":-82.987654321,\"city\":\"SensitiveCity\"},"
                + "\"private_location_name\":\"SensitivePrivateValue\","
                + "\"https://private.example/key\":\"https://private.example/value\"}}},"
                + MarketplaceSearchAdsTest.adStory(2) + "]}}";
    }

    @Test
    public void theTwoUnprovenAdQueriesAreObservedWithoutChangingAByte() {
        String response = response();
        for (String query : new String[] {THEMED, RELATED}) {
            assertSame(response, MarketplaceAdFilterForTests.responseWhole(query, response));
        }
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains(SHAPE + THEMED));
        assertTrue(report, report.contains(SHAPE + RELATED));
        assertTrue(report, report.contains("model=GroupCommerceProductItem"));
        assertTrue(report, report.contains("model=MarketplaceFeedAdStory"));
        assertTrue(report, report.contains("is_shipping_offered:boolean"));
        assertTrue(report, report.contains("shipping_offer_type:string"));
        assertTrue(report, report.contains("capture=complete"));
        assertTrue(report, report.contains("MarketplaceFeedAdStory:1"));
        for (String privateText : new String[] {"Sensitive", "987654321012345", "34.123456789",
                "82.987654321", "private_location_name", "private.example"}) {
            assertFalse(privateText + " reached the diagnostic report", report.contains(privateText));
        }
    }

    /**
     * A listing's price, title, photo, delivery and seller fields that Facebook's own code names are
     * reported by name and type. A name the app's code doesn't show stays a count, and no value,
     * ID or link reaches the report.
     */
    @Test
    public void listingNamesFromFacebooksCodeAreNamedAndOthersStayUnknown() {
        String response = "{\"data\":{\"edges\":[{\"node\":{\"__typename\":\"MarketplaceFeedGeneralListingObject\","
                + "\"listing\":{\"__typename\":\"GroupCommerceProductItem\","
                + "\"formatted_price\":{\"text\":\"SensitivePrice\"},\"strikethrough_price\":null,"
                + "\"marketplace_listing_title\":\"SensitiveTitle\",\"description\":{\"text\":\"SensitiveText\"},"
                + "\"primary_listing_photo\":{\"image\":{\"uri\":\"https://private.example/photo\"}},"
                + "\"custom_sub_titles\":[\"SensitiveSubtitle\"],\"delivery_types\":[\"SENSITIVE_DELIVERY\"],"
                + "\"seller_id\":\"424242424242\",\"marketplace_listing_seller\":{\"name\":\"SensitiveSeller\"}}}}]}}";
        assertSame(response, MarketplaceAdFilterForTests.responseWhole(THEMED, response));
        String report = LogBufferManager.buildExportText();
        for (String named : new String[] {"formatted_price:object", "strikethrough_price:null",
                "marketplace_listing_title:string", "description:object", "primary_listing_photo:object",
                "custom_sub_titles:array", "delivery_types:array", "seller_id:string",
                "marketplace_listing_seller:object", "capture=complete"}) {
            assertTrue(named + " missing from " + report, report.contains(named));
        }
        for (String privateText : new String[] {"Sensitive", "SENSITIVE", "424242424242", "private.example"}) {
            assertFalse(privateText + " reached the diagnostic report", report.contains(privateText));
        }
    }

    /**
     * The seller, place, state and price parts Facebook's web Marketplace sends with a listing are
     * named as candidates, so a capture shows which of them Android gets too. A name outside the list
     * still stays a count, and no value reaches the report.
     */
    /** A listing's own page names its keys so the listing time can be found (#303), never a value. */
    @Test
    public void aListingPageNamesItsSchemaKeysButNoValue() {
        String response = "{\"data\":{\"viewer\":{\"marketplace_product_details_page\":{\"target\":{"
                + "\"__typename\":\"GroupCommerceProductItem\",\"id\":\"987654321012345\","
                + "\"listing_creation_time_label\":\"SensitiveListedAgo\",\"publish_time\":1761600000,"
                + "\"Weird Key\":\"SensitiveWeird\",\"https://private.example/key\":2,"
                + "\"a_key_that_runs_on_far_past_forty_eight_characters_long\":3}}}}}";
        for (String query : new String[] {"MarketplacePDPSurfaceQuery", "MarketplaceProductDetailsPageAppQuery"}) {
            assertSame(response, MarketplaceAdFilterForTests.responseWhole(query, response));
        }
        assertSame(response, MarketplaceAdFilterForTests.responseWhole(THEMED, response));
        String report = LogBufferManager.buildExportText();
        for (String query : new String[] {"MarketplacePDPSurfaceQuery", "MarketplaceProductDetailsPageAppQuery"}) {
            assertTrue(report, report.contains(SHAPE + query + " response.data.viewer.marketplace_product_details_page.target"
                    + " model=GroupCommerceProductItem fields={__typename:string,id:string,listing_creation_time_label:string,"
                    + "other_number:2,other_string:1,publish_time:number}"));
        }
        assertTrue(report, report.contains(SHAPE + THEMED + " response.data.other.other.other model=GroupCommerceProductItem"));
        assertFalse(report, report.contains(THEMED + " response.data.viewer"));
        for (String privateText : new String[] {"Sensitive", "987654321012345", "1761600000", "Weird", "private.example",
                "far_past"}) {
            assertFalse(privateText + " reached the diagnostic report", report.contains(privateText));
        }
    }

    @Test
    public void webListingNamesAreCandidatesAndTheirValuesStayPrivate() {
        String response = "{\"data\":{\"edges\":[{\"node\":{\"__typename\":\"MarketplaceFeedGeneralListingObject\","
                + "\"listing\":{\"__typename\":\"GroupCommerceProductItem\",\"id\":\"987654321012345\","
                + "\"listing_price\":{\"formatted_amount\":\"$SensitiveAmount\",\"amount\":\"13.37\"},"
                + "\"location\":{\"latitude\":40.71234,\"longitude\":-74.00567,"
                + "\"reverse_geocode\":{\"city\":\"SensitiveCity\",\"state\":\"SensitiveState\","
                + "\"city_page\":{\"display_name\":\"SensitivePage\"}}},"
                + "\"marketplace_listing_seller\":{\"__typename\":\"User\",\"name\":\"SensitiveSeller\"},"
                + "\"is_sold\":false,\"is_pending\":false,\"is_hidden\":false,\"is_live\":true,"
                + "\"custom_title\":null,\"marketplace_listing_category_id\":\"SensitiveCategory\","
                + "\"redacted_description\":{\"text\":\"SensitiveDescription\"},\"creation_time\":1760918400,"
                + "\"listing_inventory_type\":\"SensitiveInventory\","
                + "\"private_seller_note\":\"SensitiveNote\"}}}]}}";
        assertSame(response, MarketplaceAdFilterForTests.responseWhole(THEMED, response));
        String report = LogBufferManager.buildExportText();
        for (String named : new String[] {"id:string", "formatted_amount:string", "amount:string",
                "reverse_geocode:object", "city:string", "state:string", "city_page:object",
                "display_name:string", "marketplace_listing_seller:object", "name:string", "is_sold:boolean",
                "is_pending:boolean", "is_hidden:boolean", "is_live:boolean", "custom_title:null",
                "marketplace_listing_category_id:string", "redacted_description:object", "creation_time:number",
                "latitude:number", "longitude:number", "listing_inventory_type:string", "other_string:1",
                "capture=complete"}) {
            assertTrue(named + " missing from " + report, report.contains(named));
        }
        for (String privateText : new String[] {"Sensitive", "987654321012345", "13.37", "private_seller_note",
                "40.71234", "74.00567", "1760918400"}) {
            assertFalse(privateText + " reached the diagnostic report", report.contains(privateText));
        }
    }

    /**
     * The shape the S22's 2026-10-20 capture showed: a seller object named by its type, and the
     * guessed names inside listing_price and location_text, with no value in the report.
     */
    @Test
    public void aSellersTypeAndThePriceAndPlaceNamesAreReadWithoutTheirValues() {
        String response = "{\"data\":{\"edges\":[{\"node\":{\"__typename\":\"MarketplaceFeedGeneralListingObject\","
                + "\"listing\":{\"__typename\":\"GroupCommerceProductItem\",\"id\":\"123456789012345\","
                + "\"listing_price\":{\"formatted_amount_zeros_stripped\":\"$SensitivePrice\","
                + "\"amount_with_offset_in_currency\":\"SensitiveOffset\"},"
                + "\"location_text\":{\"text\":\"SensitiveTown\"},"
                + "\"marketplace_listing_seller\":{\"__typename\":\"Page\",\"id\":\"SensitiveSellerId\"}}}},"
                + "{\"node\":{\"__typename\":\"MarketplaceFeedGeneralListingObject\","
                + "\"listing\":{\"__typename\":\"GroupCommerceProductItem\","
                + "\"marketplace_listing_seller\":{\"__typename\":\"User\",\"id\":\"SensitiveUserId\"}}}}]}}";
        assertSame(response, MarketplaceAdFilterForTests.responseWhole(THEMED, response));
        String report = LogBufferManager.buildExportText();
        for (String named : new String[] {"formatted_amount_zeros_stripped:string",
                "amount_with_offset_in_currency:string", "location_text model=none fields={text:string}",
                "model=Page", "model=User", "Page:1", "User:1", "unrecognized_models=0", "capture=complete"}) {
            assertTrue(named + " missing from " + report, report.contains(named));
        }
        for (String privateText : new String[] {"Sensitive", "123456789012345"}) {
            assertFalse(privateText + " reached the diagnostic report", report.contains(privateText));
        }
    }

    /**
     * A search page of 40 listings, each with its price, photo, place and seller, reads in full: the
     * S22's search captures stopped at the old 256-object walk and said incomplete.
     */
    @Test
    public void aFullSearchPageOfListingsReadsComplete() {
        StringBuilder edges = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            if (i > 0) edges.append(',');
            edges.append("{\"node\":{\"__typename\":\"MarketplaceFeedGeneralListingObject\",\"listing\":")
                    .append("{\"__typename\":\"GroupCommerceProductItem\",\"listing_price\":{\"amount\":\"1\"},")
                    .append("\"primary_listing_photo\":{\"image\":{\"uri\":\"https://private.example/p\"}},")
                    .append("\"location\":{\"reverse_geocode\":{\"city_page\":{\"display_name\":\"x\"}}},")
                    .append("\"marketplace_listing_seller\":{\"name\":\"x\"},\"story\":{\"id\":\"1\"}}}}");
        }
        String response = "{\"data\":{\"marketplace_search\":{\"feed_units\":{\"edges\":[" + edges + "]}}}}";
        assertSame(response, MarketplaceAdFilterForTests.responseWhole(SEARCH, response));
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("capture=complete"));
        assertTrue(report, report.contains("MarketplaceFeedGeneralListingObject:40"));
        assertFalse(report, report.contains("shape traversal budget"));
    }

    /**
     * 584's search pages of 27 and 48 listings (emulator, 2026-10-23) nest ad story trees past
     * level 14 under node.story, and the old walk called both pages incomplete.
     */
    @Test
    public void aSearchPageWithDeepAdStoriesReadsComplete() {
        String story = "{\"other\":".repeat(20) + "{}" + "}".repeat(20);
        StringBuilder edges = new StringBuilder();
        for (int i = 0; i < 48; i++) {
            if (i > 0) edges.append(',');
            edges.append("{\"node\":{\"__typename\":\"MarketplaceFeedGeneralListingObject\",\"story\":").append(story)
                    .append(",\"listing\":{\"__typename\":\"GroupCommerceProductItem\",\"listing_price\":{\"amount\":\"1\"},")
                    .append("\"marketplace_listing_seller\":{\"name\":\"x\"}}}}");
        }
        String response = "{\"data\":{\"marketplace_search\":{\"feed_units\":{\"edges\":[" + edges + "]}}}}";
        assertSame(response, MarketplaceAdFilterForTests.responseWhole(SEARCH, response));
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("capture=complete"));
        assertTrue(report, report.contains("MarketplaceFeedGeneralListingObject:48"));
        assertFalse(report, report.contains("shape depth budget"));
    }

    @Test
    public void aShapeDeeperThanTheWalkSaysDepthNotTraversal() {
        int levels = MarketplaceResponseDiagnostics.MAX_LEVEL + 2;
        String response = "{\"data\":" + "{\"other\":".repeat(levels) + "{}" + "}".repeat(levels) + "}";
        assertSame(response, MarketplaceAdFilterForTests.responseWhole(SEARCH, response));
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("capture=incomplete(shape depth budget)"));
        assertFalse(report, report.contains("shape traversal budget"));
    }

    /**
     * A search page sent as one payload past 256 KB reads to the end. On the S22 one such page
     * stopped before its first payload and read payloads=0, incomplete.
     */
    @Test
    public void aSearchPagePastTheOldPayloadBudgetReadsComplete() {
        String title = "t".repeat(8_000);
        StringBuilder edges = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            if (i > 0) edges.append(',');
            edges.append("{\"node\":{\"__typename\":\"MarketplaceFeedGeneralListingObject\",\"listing\":")
                    .append("{\"__typename\":\"GroupCommerceProductItem\",\"marketplace_listing_title\":\"")
                    .append(title).append("\"}}}");
        }
        String response = "{\"data\":{\"marketplace_search\":{\"feed_units\":{\"edges\":[" + edges + "]}}}}";
        assertTrue(response.length() > 256 * 1024);
        assertSame(response, MarketplaceAdFilterForTests.responseWhole(SEARCH, response));
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("payloads=1 capture=complete"));
        assertTrue(report, report.contains("MarketplaceFeedGeneralListingObject:40"));
        assertFalse(report, report.contains("payload budget"));
        assertFalse(report, report.contains(title));
    }

    @Test
    public void everySplitIsPassedOnImmediatelyAndAnIncompleteTailIsNeverFlushedTwice() {
        String response = response();
        for (int at = 1; at < response.length(); at++) {
            Object request = new Object();
            String first = response.substring(0, at);
            String second = response.substring(at);
            assertSame(first, MarketplaceAdFilterForTests.responsePiece(THEMED, first, request));
            assertSame(second, MarketplaceAdFilterForTests.responsePiece(THEMED, second, request));
            assertNull(MarketplaceAdFilter.responseEnd(request));
        }
        Object unfinished = new Object();
        String tail = "{\"data\":{\"listing\":";
        assertSame(tail, MarketplaceAdFilterForTests.responsePiece(RELATED, tail, unfinished));
        assertNull(MarketplaceAdFilter.responseEnd(unfinished));
        assertNull(MarketplaceAdFilter.responseEnd(unfinished));
        assertTrue(LogBufferManager.buildExportText().contains("unfinished payload"));
    }

    @Test
    public void debugOffOtherQueriesAndAnAbsentPatchRemainUnread() {
        String response = response();
        BaseSettings.DEBUG.save(false);
        assertSame(response, MarketplaceAdFilterForTests.responseWhole(THEMED, response));
        BaseSettings.DEBUG.save(true);
        assertSame(response, MarketplaceAdFilterForTests.responseWhole("MarketplaceHomeFeedAdsPrivateUserNameQuery", response));
        assertSame(response, MarketplaceAdFilterForTests.responseWhole("EventsBookmarkSurfaceQuery", response));
        MarketplaceAdFilterForTests.inBuild(Boolean.FALSE);
        assertSame(response, MarketplaceAdFilterForTests.responseWhole(RELATED, response));
        assertFalse(LogBufferManager.buildExportText().contains(SHAPE));
    }

    @Test
    public void diagnosticsStillObserveAnUnfilteredComparisonAndSearchKeepsItsExistingFilter() {
        String response = MarketplaceSearchAdsTest.answer(MarketplaceSearchAdsTest.listing(1), MarketplaceSearchAdsTest.adStory(2));
        assertEquals(MarketplaceSearchAdsTest.answer(MarketplaceSearchAdsTest.listing(1)),
                MarketplaceAdFilterForTests.responseWhole(SEARCH, response));
        Settings.HIDE_SPONSORED_MARKETPLACE_LISTINGS.save(false);
        assertSame(response, MarketplaceAdFilterForTests.responseWhole(SEARCH, response));
        assertTrue(LogBufferManager.buildExportText().contains("model=MarketplaceFeedAdStory"));
    }

    @Test
    public void unknownModelValuesStayPrivateAndOversizedOrMalformedAnswersStillPassThrough() {
        String unknown = "{\"data\":{\"listing\":{\"__typename\":\"SensitivePrivateModel\",\"is_shipping_offered\":false}}}";
        assertSame(unknown, MarketplaceAdFilterForTests.responseWhole(THEMED, unknown));
        String oversized = "{\"data\":\"" + "x".repeat(MarketplaceResponseDiagnostics.MAX_PAYLOAD_CHARS) + "\"}";
        assertSame(oversized, MarketplaceAdFilterForTests.responseWhole(RELATED, oversized));
        String malformed = "{\"data\":{\"listing\":}}";
        assertSame(malformed, MarketplaceAdFilterForTests.responseWhole(THEMED, malformed));
        String report = LogBufferManager.buildExportText();
        assertFalse(report.contains("SensitivePrivateModel"));
        assertTrue(report, report.contains("model=unrecognized"));
        assertTrue(report, report.contains("payload budget"));
        assertTrue(report, report.contains("unreadable payload"));
        assertTrue(report, report.contains("capture=incomplete"));
        assertTrue(report, report.contains("unrecognized_models=1"));
    }

    @Test
    public void requestSelectionFieldsAreShapesOnlyAndNoLocalOrRadiusValueIsChanged() {
        String request = "variables=%7B%22localOnly%22%3Atrue%2C%22shippedOnly%22%3Anull%2C%22radius%22%3A12345"
                + "%2C%22location%22%3A%7B%22latitude%22%3A34.123456789%2C%22longitude%22%3A-82.987654321%7D%7D";
        assertSame(request, MarketplaceAdFilterForTests.requestBody("MarketplacePlainHomeAppQuery", request));
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("request variables"));
        assertTrue(report, report.contains("localOnly:boolean"));
        assertTrue(report, report.contains("shippedOnly:null"));
        assertTrue(report, report.contains("radius:number"));
        assertTrue(report, report.contains("location:object"));
        assertFalse(report.contains("12345"));
        assertFalse(report.contains("34.123456789"));
        assertFalse(report.contains("82.987654321"));
    }

    @Test
    public void bothFixtureBackedLocalHomeQueriesAlsoPassTheirResponsesOnAsTheyWere() {
        String response = response();
        for (String query : new String[] {"MarketplaceHomeQuery", "MarketplacePlainHomeAppQuery"}) {
            assertSame(response, MarketplaceAdFilterForTests.responseWhole(query, response));
            assertTrue(LogBufferManager.buildExportText().contains(SHAPE + query));
        }
    }

    @Test
    public void concurrentAnswersAndDepthAreBoundedWithoutChangingDelivery() {
        Object[] requests = new Object[20];
        String opening = "{";
        for (int i = 0; i < requests.length; i++) {
            requests[i] = new Object();
            assertSame(opening, MarketplaceAdFilterForTests.responsePiece(THEMED, opening, requests[i]));
        }
        for (Object request : requests) assertNull(MarketplaceAdFilter.responseEnd(request));
        String tooDeep = "[".repeat(65) + "0" + "]".repeat(65);
        assertSame(tooDeep, MarketplaceAdFilterForTests.responseWhole(RELATED, tooDeep));
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("observer request budget"));
        assertTrue(report, report.contains("depth budget"));
    }

    @Test
    public void searchShapeTrafficCannotUseUpEitherMissingQuerysEvidenceBudget() {
        Settings.HIDE_SPONSORED_MARKETPLACE_LISTINGS.save(false);
        for (int count = 1; count <= MarketplaceResponseDiagnostics.MAX_SHAPES + 20; count++) {
            String data = "\"private_field\":null,".repeat(count);
            String response = "{\"data\":{" + data + "\"tail\":null}}";
            assertSame(response, MarketplaceAdFilterForTests.responseWhole(SEARCH, response));
        }
        String response = response();
        assertSame(response, MarketplaceAdFilterForTests.responseWhole(THEMED, response));
        assertSame(response, MarketplaceAdFilterForTests.responseWhole(RELATED, response));
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains(SHAPE + THEMED));
        assertTrue(report, report.contains(SHAPE + RELATED));
        assertFalse(report.contains("private_field"));
        int lines = report.split(java.util.regex.Pattern.quote(SHAPE + SEARCH), -1).length - 1;
        assertTrue("unbounded diagnostic output: " + lines, lines <= MarketplaceResponseDiagnostics.MAX_SHAPES + 8);
    }

    @Test
    public void aPartialTraversalNeverClaimsToHaveCapturedEveryModel() {
        String item = "{\"__typename\":\"MarketplaceFeedAdStory\"}";
        String response = "{\"data\":[" + (item + ",").repeat(MarketplaceResponseDiagnostics.MAX_VISITS) + item + "]}";
        assertSame(response, MarketplaceAdFilterForTests.responseWhole(THEMED, response));
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("shape traversal budget"));
        assertTrue(report, report.contains("capture=incomplete(shape traversal budget)"));
        assertFalse(report.contains("capture=complete"));
    }

    /** A listing's own page is read too: Facebook's listing route times MarketplacePDPSurfaceQuery. */
    @Test
    public void aListingPagesQueriesAreRead() {
        String response = response();
        for (String query : new String[] {"MarketplacePDPSurfaceQuery", "MarketplaceProductDetailsPageAppQuery",
                "MarketplaceListingAvailabilityQuery"}) {
            assertSame(response, MarketplaceAdFilterForTests.responseWhole(query, response));
            String report = LogBufferManager.buildExportText();
            assertTrue(report, report.contains(SHAPE + query + " response"));
            assertTrue(report, report.contains(SHAPE + query + " response summary payloads=1 capture=complete"));
        }
        assertFalse(LogBufferManager.buildExportText().contains("SensitiveListingText"));
    }

    @Test
    public void repeatedShapesStayCompleteAtTheLimitButANewShapeMakesTheResponseIncomplete() {
        for (int fields = 1; fields <= MarketplaceResponseDiagnostics.MAX_SHAPES; fields++) {
            String response = privateShape(fields);
            assertSame(response, MarketplaceAdFilterForTests.responseWhole(THEMED, response));
        }
        String report = LogBufferManager.buildExportText();
        assertEquals(MarketplaceResponseDiagnostics.MAX_SHAPES, report.split("model=none", -1).length - 1);
        // No clear in between: a clear starts the budget over.

        Object repeated = new Object();
        String retained = privateShape(1);
        assertSame(retained, MarketplaceAdFilterForTests.responsePiece(THEMED, retained, repeated));
        assertSame(retained, MarketplaceAdFilterForTests.responsePiece(THEMED, retained, repeated));
        assertNull(MarketplaceAdFilter.responseEnd(repeated));
        report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("payloads=2 capture=complete"));
        assertFalse(report.contains("capture=incomplete"));

        Object omitted = new Object();
        String overflow = privateShape(MarketplaceResponseDiagnostics.MAX_SHAPES + 1);
        assertSame(overflow, MarketplaceAdFilterForTests.responsePiece(THEMED, overflow, omitted));
        assertNull(MarketplaceAdFilter.responseEnd(omitted));
        report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("payloads=1 capture=incomplete(shape budget)"));
        assertEquals(report, 1, report.split("capture=incomplete", -1).length - 1);
        assertFalse(report.contains("other_string:" + (MarketplaceResponseDiagnostics.MAX_SHAPES + 1)));
        assertPrivateShapeDataAbsent(report);
    }

    /** A report taken after a diagnostic clear still has the shapes of a response seen before it. */
    @Test
    public void aShapeLoggedBeforeADiagnosticClearLogsAgainAfterIt() {
        String field = "is_shipping_offered:boolean";
        MarketplaceAdFilterForTests.responseWhole(THEMED, response());
        int before = LogBufferManager.buildExportText().split(field, -1).length - 1;
        assertTrue("no shape line for the listing", before > 0);
        LogBufferManager.clearLogBuffer();
        assertFalse(LogBufferManager.buildExportText().contains(field));
        MarketplaceAdFilterForTests.responseWhole(THEMED, response());
        String report = LogBufferManager.buildExportText();
        assertEquals(report, before, report.split(field, -1).length - 1);
        // Without a clear the same shapes aren't written twice.
        MarketplaceAdFilterForTests.responseWhole(THEMED, response());
        report = LogBufferManager.buildExportText();
        assertEquals(report, before, report.split(field, -1).length - 1);
    }

    @Test
    public void newSummariesBeyondTheLimitEmitOneNoticeAndDuplicatesDoNotUseTheBudget() {
        String payload = privateShape(1);
        for (int payloads = 1; payloads <= 8; payloads++) {
            String response = payload.repeat(payloads);
            assertSame(response, MarketplaceAdFilterForTests.responseWhole(RELATED, response));
        }
        String report = LogBufferManager.buildExportText();
        assertEquals(8, report.split("response summary payloads=", -1).length - 1);
        // No clear in between: a clear starts the budget over.
        assertSame(payload, MarketplaceAdFilterForTests.responseWhole(RELATED, payload));
        report = LogBufferManager.buildExportText();
        assertEquals(report, 8, report.split("response summary payloads=", -1).length - 1);
        assertFalse(report.contains("summary omitted"));

        Object request = new Object();
        String overflow = payload.repeat(9);
        assertSame(overflow, MarketplaceAdFilterForTests.responsePiece(RELATED, overflow, request));
        assertNull(MarketplaceAdFilter.responseEnd(request));
        assertSame(overflow, MarketplaceAdFilterForTests.responseWhole(RELATED, overflow));
        report = LogBufferManager.buildExportText();
        assertEquals(report, 1, report.split("response summary omitted: summary budget", -1).length - 1);
        assertFalse(report.contains("response summary payloads=9"));
        assertPrivateShapeDataAbsent(report);

        LogBufferManager.clearLogBuffer();
        assertSame(overflow, MarketplaceAdFilterForTests.responseWhole(RELATED, overflow));
        report = LogBufferManager.buildExportText();
        assertTrue("a clear starts the budget over", report.contains("response summary payloads=9"));
        assertFalse(report.contains("summary omitted"));
        assertPrivateShapeDataAbsent(report);
    }

    private static String privateShape(int fields) {
        StringBuilder response = new StringBuilder("{");
        for (int field = 0; field < fields; field++) {
            if (field > 0) response.append(',');
            response.append("\"private_location_name_").append(field).append("\":")
                    .append("\"SensitivePrivateValue https://private.example/987654321012345 34.123456789\"");
        }
        return response.append('}').toString();
    }

    private static void assertPrivateShapeDataAbsent(String report) {
        for (String privateText : new String[] {"private_location_name", "SensitivePrivateValue",
                "private.example", "987654321012345", "34.123456789"}) {
            assertFalse(privateText + " reached the diagnostic report", report.contains(privateText));
        }
    }
}
