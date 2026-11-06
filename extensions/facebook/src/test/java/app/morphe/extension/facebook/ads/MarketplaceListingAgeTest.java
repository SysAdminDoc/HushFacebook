/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.ads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.util.TypedValue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * A listing's own page says when the listing went up (#303): 585's MarketplacePDPSurfaceQuery answer
 * carries creation_time beside location_text, and the place line gets "Listed 3 weeks ago (date)"
 * after it, whether the answer comes whole or in pieces. Off, paused, or with no time to read, the
 * answer is Facebook's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MarketplaceListingAgeTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String PAGE = "MarketplacePDPSurfaceQuery";
    private static final long DAY = 24 * 60 * 60;

    @Before
    public void inBuild() {
        MarketplaceAdFilterForTests.inBuild(Boolean.TRUE);
        MarketplaceAdFilterForTests.forget();
        MarketplaceListingAge.forgetPricesForTests();
        HookStatus.clear();
    }

    @After
    public void restore() {
        MarketplaceAdFilterForTests.inBuild(null);
        MarketplaceAdFilter.failNextForTests = null;
        MarketplaceAdFilterForTests.forget();
        MarketplaceListingAge.forgetPricesForTests();
        PauseForTests.resume();
        Settings.MARKETPLACE_LISTING_DATE.resetToDefault();
        HookStatus.clear();
    }

    /** The page's answer as 585 shapes it, the listing put up [seconds] since 1970, at [place]. */
    private static String answer(long seconds, String place) {
        return "{\"data\":{\"viewer\":{\"marketplace_pdp\":{\"listing\":{\"__typename\":\"GroupCommerceProductItem\","
                + "\"id\":\"1234\",\"primary_mp_ent\":{\"__typename\":\"GroupCommerceProductItem\",\"id\":\"1234\","
                + "\"creation_time\":" + seconds + ",\"location_text\":{\"text\":\"" + place + "\"},"
                + "\"if_gk_pdp_date_listed_metadata\":{\"__typename\":\"GroupCommerceProductItem\",\"id\":\"1234\","
                + "\"creation_time\":" + seconds + "},\"marketplace_listing_title\":\"Polaroid Camera\"}}},"
                + "\"uri_redirect\":null},\"listing\":{\"__typename\":\"GroupCommerceProductItem\",\"id\":\"1234\","
                + "\"location_text\":{\"text\":\"" + place + "\"},\"marketplace_listing_title\":\"Polaroid Camera\"}},"
                + "\"extensions\":{\"is_final\":true}}";
    }

    private static String date(long seconds) {
        return DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.US).format(new Date(seconds * 1000));
    }

    @Test
    public void bothPlaceLinesOfTheListingGetWhenItWasListed() {
        long now = 1_793_500_000L;
        long listed = now - 21 * DAY;
        String dated = new MarketplaceListingAge.Answer(now * 1000).read(answer(listed, "Moncton, NB"));
        String line = "Moncton, NB" + MarketplaceListingAge.DOT + "Listed 3 weeks ago (" + date(listed) + ")";
        assertEquals(answer(listed, "Moncton, NB").replace("\"text\":\"Moncton, NB\"", "\"text\":\"" + line + "\""), dated);
    }

    @Test
    public void theAgeIsInItsLargestWholeUnit() {
        assertEquals("30 minutes ago", MarketplaceListingAge.ago(30 * 60, Locale.US));
        assertEquals("1 minute ago", MarketplaceListingAge.ago(10, Locale.US));
        assertEquals("19 hours ago", MarketplaceListingAge.ago(19 * 3600 + 59 * 60, Locale.US));
        assertEquals("3 days ago", MarketplaceListingAge.ago(3 * DAY + 100, Locale.US));
        assertEquals("2 weeks ago", MarketplaceListingAge.ago(20 * DAY, Locale.US));
        assertEquals("4 months ago", MarketplaceListingAge.ago(125 * DAY, Locale.US));
        assertEquals("2 years ago", MarketplaceListingAge.ago(800 * DAY, Locale.US));
    }

    @Test
    public void aPlaceWithEscapesKeepsThemAndAnEmptyOneGetsOnlyTheDate() {
        long now = 1_793_500_000L;
        String place = "Caf\\u00e9 \\\"Nord\\\"";
        String dated = new MarketplaceListingAge.Answer(now * 1000).read(answer(now - 3 * DAY, place));
        assertTrue(dated, dated.contains("\"text\":\"" + place + MarketplaceListingAge.DOT + "Listed 3 days ago ("));

        String empty = new MarketplaceListingAge.Answer(now * 1000).read(answer(now - 3 * DAY, ""));
        assertTrue(empty, empty.contains("\"text\":\"Listed 3 days ago ("));
    }

    @Test
    public void aTimeThatCantBeAListingsLeavesTheAnswerAlone() {
        long now = 1_793_500_000L;
        for (long seconds : new long[] {0, 1_000_000_000L, now + 3 * DAY}) {
            String answer = answer(seconds, "Moncton, NB");
            assertSame(answer, new MarketplaceListingAge.Answer(now * 1000).read(answer));
        }
        String noTime = answer(now - DAY, "Moncton, NB").replace("\"creation_time\":" + (now - DAY), "\"creation_time\":null");
        assertSame(noTime, new MarketplaceListingAge.Answer(now * 1000).read(noTime));
    }

    @Test
    public void anAnswerInPiecesIsDatedOnceEachPayloadFinishes() {
        long listed = System.currentTimeMillis() / 1000 - 10 * DAY;
        String answer = answer(listed, "Moncton, NB");
        Object request = new Object();
        int cut = answer.indexOf("creation_time") + 5;
        String first = MarketplaceAdFilterForTests.responsePiece(PAGE, answer.substring(0, cut), request);
        String second = MarketplaceAdFilterForTests.responsePiece(PAGE, answer.substring(cut) + "\r\n", request);
        assertEquals("the unfinished payload waits", "", first);
        assertTrue(second, second.contains("Moncton, NB" + MarketplaceListingAge.DOT + "Listed 1 week ago (" + date(listed) + ")"));
        assertTrue(second, second.endsWith("}\r\n"));
        assertNull("nothing waits at the end", MarketplaceAdFilter.responseEnd(request));
    }

    @Test
    public void aWholeAnswerIsDatedAndOtherQueriesArentRead() {
        long listed = System.currentTimeMillis() / 1000 - 2 * DAY;
        String answer = answer(listed, "Moncton, NB");
        String dated = MarketplaceAdFilterForTests.responseWhole(PAGE, answer);
        assertTrue(dated, dated.contains("Moncton, NB" + MarketplaceListingAge.DOT + "Listed 2 days ago ("));
        assertSame(answer, MarketplaceAdFilterForTests.responseWhole("MarketplaceListingAvailabilityQuery", answer));
        assertFalse(MarketplaceListingAge.listingPage("RelayFBNetwork_MarketplaceSearchFeedPaginationQuery"));
        assertTrue(MarketplaceListingAge.listingPage("RelayFBNetwork_MarketplaceProductDetailsPageAppQuery"));
    }

    @Test
    public void offOrPausedTheAnswerIsFacebooks() {
        String answer = answer(System.currentTimeMillis() / 1000 - 2 * DAY, "Moncton, NB");
        Settings.MARKETPLACE_LISTING_DATE.save(false);
        assertSame(answer, MarketplaceAdFilterForTests.responseWhole(PAGE, answer));
        Object request = new Object();
        assertSame(answer, MarketplaceAdFilterForTests.responsePiece(PAGE, answer, request));
        assertNull(MarketplaceAdFilter.responseEnd(request));

        Settings.MARKETPLACE_LISTING_DATE.resetToDefault();
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertSame(answer, MarketplaceAdFilterForTests.responseWhole(PAGE, answer));
    }

    /** [answer] with data.listing priced [amount] (amount_with_offset), as 584 and 585 send it. */
    private static String priced(String answer, String amount) {
        String listing = "\"uri_redirect\":null},\"listing\":{\"__typename\":\"GroupCommerceProductItem\",\"id\":\"1234\",";
        assertTrue(answer.contains(listing));
        return answer.replace(listing, listing + "\"listing_price\":{\"amount_with_offset\":\"" + amount
                + "\",\"currency\":\"USD\"},");
    }

    /** "$150" drawn the way React builds a price: its own size and colour over all of it. */
    private static SpannableString price(String shown, int size) {
        SpannableString price = new SpannableString(shown);
        price.setSpan(new AbsoluteSizeSpan(size), 0, shown.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        price.setSpan(new ForegroundColorSpan(0xFFE4E6EB), 0, shown.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return price;
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.SPONSORED_MARKETPLACE + ":")) return line;
        }
        return null;
    }

    private static int lineSize() {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, MarketplaceListingAge.LINE_SP,
                Resources.getSystem().getDisplayMetrics()));
    }

    @Test
    public void thePriceRightUnderTheTitleGetsTheDateOnALineOfItsOwn() {
        long listed = System.currentTimeMillis() / 1000 - 21 * DAY;
        MarketplaceAdFilterForTests.responseWhole(PAGE, priced(answer(listed, "Moncton, NB"), "15000"));

        SpannableString title = new SpannableString("Polaroid Camera");
        assertSame(title, MarketplaceListingAge.priceLine(title));
        String before = statusLine();
        Spannable line = MarketplaceListingAge.priceLine(price("$150", 60));
        assertNotEquals("the price line is counted as bound", before, statusLine());

        String date = "Listed 3 weeks ago (" + date(listed) + ")";
        assertEquals("$150\n" + date, line.toString());
        AbsoluteSizeSpan[] sizes = line.getSpans(0, line.length(), AbsoluteSizeSpan.class);
        assertEquals(2, sizes.length);
        for (AbsoluteSizeSpan size : sizes) {
            if (size.getSize() == 60) {
                assertEquals("the price's size stops at the price", 4, line.getSpanEnd(size));
            } else {
                assertEquals(lineSize(), size.getSize());
                assertEquals(5, line.getSpanStart(size));
                assertEquals(line.length(), line.getSpanEnd(size));
            }
        }
        MarketplaceListingAge.LineColour[] colours = line.getSpans(0, line.length(), MarketplaceListingAge.LineColour.class);
        assertEquals(1, colours.length);
        TextPaint paint = new TextPaint();
        colours[0].updateDrawState(paint);
        assertEquals("the price's colour, faded", 0xB3E4E6EB, paint.getColor());

        SpannableString again = price("$150", 60);
        assertSame("one line per title", again, MarketplaceListingAge.priceLine(again));
    }

    @Test
    public void aPriceNotRightUnderItsTitleOrAnotherAmountStaysAsItIs() {
        long listed = System.currentTimeMillis() / 1000 - 3 * DAY;
        MarketplaceAdFilterForTests.responseWhole(PAGE, priced(answer(listed, "Moncton, NB"), "15000"));

        SpannableString alone = price("$150", 60);
        assertSame("no title before it", alone, MarketplaceListingAge.priceLine(alone));

        MarketplaceListingAge.priceLine(new SpannableString("Polaroid Camera"));
        for (int i = 0; i < MarketplaceListingAge.MAX_BETWEEN; i++) {
            MarketplaceListingAge.priceLine(new SpannableString("Text " + i));
        }
        SpannableString late = price("$150", 60);
        assertSame("too far under the title", late, MarketplaceListingAge.priceLine(late));

        MarketplaceListingAge.priceLine(new SpannableString("Polaroid Camera"));
        SpannableString other = price("$140", 60);
        assertSame("another listing's price", other, MarketplaceListingAge.priceLine(other));

        MarketplaceListingAge.priceLine(new SpannableString("Polaroid Camera"));
        MarketplaceListingAge.priceLine(new SpannableString("Text"));
        Spannable unsized = MarketplaceListingAge.priceLine(new SpannableString("$150.00"));
        assertEquals("$150.00\nListed 3 days ago (" + date(listed) + ")", unsized.toString());
        AbsoluteSizeSpan[] sizes = unsized.getSpans(0, unsized.length(), AbsoluteSizeSpan.class);
        assertEquals(lineSize(), sizes[0].getSize());
    }

    /** [shown] drawn as React builds an old price: {@link #price} crossed out. */
    private static SpannableString struck(String shown, int size) {
        SpannableString old = price(shown, size);
        old.setSpan(new StrikethroughSpan(), 0, shown.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        return old;
    }

    @Test
    public void anOldPriceBesideTheDatedPriceStaysLevelAndThePlaceLineGoesBackToFacebooks() {
        long listed = System.currentTimeMillis() / 1000 - 21 * DAY;
        String dated = MarketplaceAdFilterForTests.responseWhole(PAGE, priced(answer(listed, "Moncton, NB"), "17500"));
        String date = "Listed 3 weeks ago (" + date(listed) + ")";
        String place = "Moncton, NB" + MarketplaceListingAge.DOT + date;
        assertTrue("the answer's place line has the date", dated.contains(place));

        SpannableString early = new SpannableString(place);
        assertSame("no price got the date yet", early, MarketplaceListingAge.priceLine(early));

        MarketplaceListingAge.priceLine(new SpannableString("Polaroid Camera"));
        assertEquals("$175\n" + date, MarketplaceListingAge.priceLine(price("$175", 60)).toString());

        SpannableString plain = price("$200", 48);
        assertSame("a price that isn't crossed out", plain, MarketplaceListingAge.priceLine(plain));
        Spannable old = MarketplaceListingAge.priceLine(struck("$200", 48));
        assertEquals("$200\n" + MarketplaceListingAge.BLANK, old.toString());
        StrikethroughSpan[] lines = old.getSpans(0, old.length(), StrikethroughSpan.class);
        assertEquals(1, lines.length);
        assertEquals("the line through stops at the old price", 4, old.getSpanEnd(lines[0]));
        int blankSizes = 0;
        for (AbsoluteSizeSpan size : old.getSpans(0, old.length(), AbsoluteSizeSpan.class)) {
            if (old.getSpanStart(size) == 5) {
                assertEquals("the empty line is as tall as the date line", lineSize(), size.getSize());
                assertEquals(old.length(), old.getSpanEnd(size));
                blankSizes++;
            } else {
                assertEquals("the old price keeps its size", 48, size.getSize());
                assertEquals(4, old.getSpanEnd(size));
            }
        }
        assertEquals(1, blankSizes);
        assertTrue(statusLine().contains(MarketplaceListingAge.OLD_PRICE_LEVEL));
        SpannableString again = struck("$200", 48);
        assertSame("one empty line per dated price", again, MarketplaceListingAge.priceLine(again));

        SpannableString later = new SpannableString(place);
        later.setSpan(new ForegroundColorSpan(0xFFB0B3B8), 0, place.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Spannable undated = MarketplaceListingAge.priceLine(later);
        assertEquals("the date shows once, under the price", "Moncton, NB", undated.toString());
        ForegroundColorSpan[] colours = undated.getSpans(0, undated.length(), ForegroundColorSpan.class);
        assertEquals("Facebook's colour stays", 1, colours.length);
        assertEquals(undated.length(), undated.getSpanEnd(colours[0]));
        assertTrue(statusLine().contains(MarketplaceListingAge.PLACE_UNDATED));
    }

    /** How 585's React crosses text out: a span that draws its own line, not Android's. */
    abstract static class DrawnDecoration {
        public abstract void draw(Canvas canvas, Layout layout, int start, int end);
    }

    static final class DrawnLineThrough extends DrawnDecoration {
        @Override
        public void draw(Canvas canvas, Layout layout, int start, int end) {
        }
    }

    @Test
    public void anOldPriceReactCrossesOutWithALineItDrawsGetsTheEmptyLineToo() {
        long listed = System.currentTimeMillis() / 1000 - 21 * DAY;
        MarketplaceAdFilterForTests.responseWhole(PAGE, priced(answer(listed, "Moncton, NB"), "17500"));
        MarketplaceListingAge.priceLine(new SpannableString("Polaroid Camera"));
        MarketplaceListingAge.priceLine(price("$175", 60));

        assertFalse(MarketplaceListingAge.drawsLine(ForegroundColorSpan.class));
        SpannableString old = price("$200", 48);
        old.setSpan(new DrawnLineThrough(), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Spannable level = MarketplaceListingAge.priceLine(old);
        assertEquals("$200\n" + MarketplaceListingAge.BLANK, level.toString());
        DrawnLineThrough[] lines = level.getSpans(0, level.length(), DrawnLineThrough.class);
        assertEquals(1, lines.length);
        assertEquals("the line through stops at the old price", 4, level.getSpanEnd(lines[0]));
    }

    @Test
    public void aCrossedOutPriceAwayFromADatedPriceAndAPlaceLineWithoutOneStayAsTheyAre() {
        long listed = System.currentTimeMillis() / 1000 - 21 * DAY;
        String answer = priced(answer(listed, "Moncton, NB"), "17500");
        MarketplaceAdFilterForTests.responseWhole(PAGE, answer);
        String place = "Moncton, NB" + MarketplaceListingAge.DOT + "Listed 3 weeks ago (" + date(listed) + ")";

        MarketplaceListingAge.priceLine(new SpannableString("Polaroid Camera"));
        SpannableString lone = struck("$200", 48);
        assertSame("no price got the date before it", lone, MarketplaceListingAge.priceLine(lone));
        MarketplaceListingAge.priceLine(price("$175", 60));
        for (int i = 0; i < MarketplaceListingAge.MAX_BETWEEN; i++) {
            MarketplaceListingAge.priceLine(new SpannableString("Text " + i));
        }
        SpannableString far = struck("$200", 48);
        assertSame("too far from the dated price", far, MarketplaceListingAge.priceLine(far));

        MarketplaceListingAge.forgetPricesForTests();
        MarketplaceAdFilterForTests.responseWhole(PAGE, answer);
        SpannableString kept = new SpannableString(place);
        assertSame("its price got no date, so the place line keeps it", kept, MarketplaceListingAge.priceLine(kept));
    }

    /** [answer] with [fields] after data.listing's price. */
    private static String withFields(String answer, String fields) {
        String price = "\"currency\":\"USD\"},";
        assertTrue(answer.contains(price));
        return answer.replace(price, price + fields + ",");
    }

    @Test
    public void aVehicleGetsNoLineUnderItsPriceSinceFacebookDatesItThere() {
        long listed = System.currentTimeMillis() / 1000 - 21 * DAY;
        String camera = priced(answer(listed, "Moncton, NB"), "15000");
        MarketplaceAdFilterForTests.responseWhole(PAGE, withFields(camera,
                "\"vehicle_odometer_data\":{\"unit\":null,\"value\":null},\"vehicle_make_display_name\":\"\","
                        + "\"vehicle_features\":[],\"vehicle_condition\":null,\"vehicle_is_new\":false,"
                        + "\"partnership_context\":{\"vehicle_odometer_data\":{\"unit\":null,\"value\":null}}"));
        MarketplaceListingAge.priceLine(new SpannableString("Polaroid Camera"));
        assertEquals("empty vehicle fields aren't a vehicle", "$150\nListed 3 weeks ago (" + date(listed) + ")",
                MarketplaceListingAge.priceLine(price("$150", 60)).toString());

        String car = withFields(camera, "\"vehicle_odometer_data\":{\"unit\":\"MILES\",\"value\":120000}");
        String dated = MarketplaceAdFilterForTests.responseWhole(PAGE, car);
        assertTrue("its place still gets the date", dated.contains("Moncton, NB" + MarketplaceListingAge.DOT + "Listed 3 weeks ago ("));
        MarketplaceListingAge.priceLine(new SpannableString("Polaroid Camera"));
        SpannableString shown = price("$150", 60);
        assertSame("a vehicle seen after its page answered once is forgotten", shown, MarketplaceListingAge.priceLine(shown));

        MarketplaceListingAge.forgetPricesForTests();
        String later = "{\"data\":{\"node\":{\"id\":\"1234\",\"vehicle_info\":{\"vehicle_make_display_name\":\"Honda\"}}}}";
        MarketplaceAdFilterForTests.responseWhole(PAGE, camera + "\r\n" + later);
        MarketplaceListingAge.priceLine(new SpannableString("Polaroid Camera"));
        SpannableString late = price("$150", 60);
        assertSame("a vehicle field in a later payload, a level down", late, MarketplaceListingAge.priceLine(late));
    }

    @Test
    public void aPriceIsItsDigitsWithOrWithoutTheCents() {
        assertTrue(MarketplaceListingAge.pricedAs("$150", "15000"));
        assertTrue(MarketplaceListingAge.pricedAs("$150.00", "15000"));
        assertTrue(MarketplaceListingAge.pricedAs("CA$1,500", "150000"));
        assertTrue(MarketplaceListingAge.pricedAs("150,50 €", "15050"));
        assertTrue(MarketplaceListingAge.pricedAs("¥1500", "1500"));
        assertTrue(MarketplaceListingAge.pricedAs("$15", "1500"));
        assertFalse(MarketplaceListingAge.pricedAs("$150", "1500"));
        assertFalse(MarketplaceListingAge.pricedAs("Free", "0"));
        assertFalse(MarketplaceListingAge.pricedAs("Listed 150 days", "15000"));
        assertFalse(MarketplaceListingAge.pricedAs("$150\n$200", "15000"));
    }

    @Test
    public void offOrPausedThePriceStaysAsItIs() {
        long listed = System.currentTimeMillis() / 1000 - 3 * DAY;
        MarketplaceAdFilterForTests.responseWhole(PAGE, priced(answer(listed, "Moncton, NB"), "15000"));

        Settings.MARKETPLACE_LISTING_DATE.save(false);
        MarketplaceListingAge.priceLine(new SpannableString("Polaroid Camera"));
        SpannableString off = price("$150", 60);
        assertSame(off, MarketplaceListingAge.priceLine(off));

        Settings.MARKETPLACE_LISTING_DATE.resetToDefault();
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        MarketplaceListingAge.priceLine(new SpannableString("Polaroid Camera"));
        SpannableString paused = price("$150", 60);
        assertSame(paused, MarketplaceListingAge.priceLine(paused));
    }

    @Test
    public void aFailurePassesWhatWaitedOnAndTheRestUnread() {
        long listed = System.currentTimeMillis() / 1000 - 2 * DAY;
        String answer = answer(listed, "Moncton, NB");
        Object request = new Object();
        MarketplaceAdFilter.failNextForTests = new IllegalStateException("test");
        assertSame(answer, MarketplaceAdFilterForTests.responsePiece(PAGE, answer, request));
        assertSame("the rest of that answer goes on unread", answer,
                MarketplaceAdFilterForTests.responsePiece(PAGE, answer, request));
        assertNull(MarketplaceAdFilter.responseEnd(request));
    }
}
