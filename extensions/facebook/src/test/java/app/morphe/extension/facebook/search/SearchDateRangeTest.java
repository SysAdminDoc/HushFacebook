/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.search;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Search date range (#279): with its switch on, the Posted date filter answers the date range
 * kind, the bottom sheet flag answers off for that same tap, and the filter sheet's own tap
 * callback is skipped for it. Every other filter, and every
 * answer with the switch off, paused, or before the settings are ready, is Facebook's own.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SearchDateRangeTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for Facebook's filter kind enum. */
    public enum Kind {
        MULTI_CHOICE, TOGGLE, DATE_RANGE_PICKER
    }

    /** Stands in for a filter: public, with the getName its interface keeps. */
    public static final class Filter {
        private final String name;

        public Filter(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    /** Stands in for a filter state Facebook keeps, with the two methods the patch gives it. */
    public static final class State implements SearchDateRange.FilterState {
        private final String name;
        private final String value;

        public State(String name, String value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public String hushfacebookFilterName() {
            return name;
        }

        @Override
        public String hushfacebookFilterValue() {
            return value;
        }
    }

    private static final Filter POSTED = new Filter(SearchDateRange.FILTER);

    private static final String MARCH_TO_MAY = SearchDateRangeForTests.MARCH_TO_MAY;

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.SEARCH_DATE_RANGE.resetToDefault();
        HookStatus.clear();
        // Leave no remapped tap, picked day or sent range behind for another class.
        SearchDateRange.bottomSheet(true);
        SearchDateRange.fromDay = 1;
        SearchDateRange.toDay = 31;
        SearchDateRange.label(null, null);
        SearchDateRange.forgetLabels();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.SEARCH_DATE_RANGE + ":")) return line;
        }
        return null;
    }

    @Test
    public void onThePostedDateFilterOpensAsARangeAndTheSheetFlagGoesOff() {
        Settings.SEARCH_DATE_RANGE.save(true);
        assertSame(Kind.DATE_RANGE_PICKER, SearchDateRange.kind(POSTED, Kind.MULTI_CHOICE));
        assertFalse("the bottom sheet stayed on for the remapped tap", SearchDateRange.bottomSheet(true));
        assertTrue("the flag stayed off for the next tap", SearchDateRange.bottomSheet(true));
        assertEquals(FamilyNames.SEARCH_DATE_RANGE + ": invoked 1, 2 found, 0 missing. Counted: "
                + SearchDateRange.OPENED + " 1", statusLine());
    }

    @Test
    public void onAnotherFilterKeepsItsKindAndTheFlag() {
        Settings.SEARCH_DATE_RANGE.save(true);
        Filter other = new Filter("rp_author");
        assertSame(Kind.MULTI_CHOICE, SearchDateRange.kind(other, Kind.MULTI_CHOICE));
        assertTrue(SearchDateRange.bottomSheet(true));
        assertFalse(SearchDateRange.bottomSheet(false));
    }

    @Test
    public void anObjectWithNoNameKeepsItsKind() {
        Settings.SEARCH_DATE_RANGE.save(true);
        assertSame(Kind.TOGGLE, SearchDateRange.kind(new Object(), Kind.TOGGLE));
        assertSame(Kind.TOGGLE, SearchDateRange.kind(null, Kind.TOGGLE));
    }

    @Test
    public void aDateRangeKindIsLeftAlone() {
        Settings.SEARCH_DATE_RANGE.save(true);
        assertSame(Kind.DATE_RANGE_PICKER, SearchDateRange.kind(POSTED, Kind.DATE_RANGE_PICKER));
        assertTrue("the flag changed for a filter that was already a range", SearchDateRange.bottomSheet(true));
    }

    @Test
    public void offFacebooksOwnAnswersComeBack() {
        assertSame(Kind.MULTI_CHOICE, SearchDateRange.kind(POSTED, Kind.MULTI_CHOICE));
        assertTrue(SearchDateRange.bottomSheet(true));
    }

    @Test
    public void pausedFacebooksOwnAnswersComeBack() {
        Settings.SEARCH_DATE_RANGE.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertSame(Kind.MULTI_CHOICE, SearchDateRange.kind(POSTED, Kind.MULTI_CHOICE));
        assertTrue(SearchDateRange.bottomSheet(true));
    }

    @Test
    public void beforeTheSettingsAreReadyFacebooksOwnAnswersComeBack() {
        Settings.SEARCH_DATE_RANGE.save(true);
        SettingsContextRule.withoutContext(() -> {
            assertSame(Kind.MULTI_CHOICE, SearchDateRange.kind(POSTED, Kind.MULTI_CHOICE));
            assertTrue(SearchDateRange.bottomSheet(true));
        });
    }

    @Test
    public void inTheFilterSheetThePostedDateFilterSkipsTheSheetsOwnCallback() {
        Settings.SEARCH_DATE_RANGE.save(true);
        Object callback = new Object();
        assertNull("the date filter stayed in the sheet", SearchDateRange.callback(POSTED, Kind.MULTI_CHOICE, callback));
        assertSame(callback, SearchDateRange.callback(new Filter("rp_author"), Kind.MULTI_CHOICE, callback));
        assertSame(callback, SearchDateRange.callback(POSTED, Kind.DATE_RANGE_PICKER, callback));
        assertNull("a tap with no callback gained one", SearchDateRange.callback(POSTED, Kind.MULTI_CHOICE, null));
    }

    @Test
    public void offOrPausedTheSheetKeepsItsCallback() {
        Object callback = new Object();
        assertSame(callback, SearchDateRange.callback(POSTED, Kind.MULTI_CHOICE, callback));
        Settings.SEARCH_DATE_RANGE.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertSame(callback, SearchDateRange.callback(POSTED, Kind.MULTI_CHOICE, callback));
    }

    @Test
    public void aKindThatIsNotAnEnumComesBack() {
        Settings.SEARCH_DATE_RANGE.save(true);
        Object notAnEnum = "kind";
        assertSame(notAnEnum, SearchDateRange.kind(POSTED, notAnEnum));
    }

    @Test
    public void daysAreHeldToTheirMonthsAndWholeMonthsStayAsFacebookSendsThem() {
        assertArrayEquals(new int[] {2026, 3, 5, 2026, 5, 20}, SearchDateRange.range(MARCH_TO_MAY, 5, 20));
        assertNull("the first of the start month to the last of the end month",
                SearchDateRange.range(MARCH_TO_MAY, 1, 31));
        assertArrayEquals("April has 30 days and 2024 is a leap year", new int[] {2024, 2, 29, 2024, 4, 30},
                SearchDateRange.range(SearchDateRangeForTests.months("2024-02", "2024-04"), 31, 30));
        assertNull(SearchDateRange.range(SearchDateRangeForTests.months("2024-02", "2024-04"), 1, 30));
        assertArrayEquals("2025 isn't", new int[] {2025, 2, 28, 2025, 2, 28},
                SearchDateRange.range(SearchDateRangeForTests.months("2025-02", "2025-02"), 30, 31));
        assertArrayEquals("days the wrong way round in one month swap", new int[] {2026, 3, 5, 2026, 3, 20},
                SearchDateRange.range(SearchDateRangeForTests.months("2026-03", "2026-03"), 20, 5));
        assertNull(SearchDateRange.range(SearchDateRange.field("start_year", "2026"), 5, 20));
        assertNull(SearchDateRange.range(SearchDateRangeForTests.months("2026-13", "2026-05"), 5, 20));
    }

    @Test
    public void theMonthsAreReadOnlyAsFacebookQuotesThemInsideItsArgs() {
        // Facebook formats them as "{\"start_month\":\"2026-03\",...}" inside the args string of
        // {"name":"creation_time","args":"{%s}"}: a bare quote would end that string.
        assertEquals("\\\"start_day\\\":\\\"2026-03-05\\\"", SearchDateRange.field("start_day", "2026-03-05"));
        assertNull(SearchDateRange.range("\"start_month\":\"2026-03\",\"end_month\":\"2026-05\"", 5, 20));
    }

    @Test
    public void onARangeGoesOutWithItsDaysAndTheChipShowsThem() {
        Settings.SEARCH_DATE_RANGE.save(true);
        SearchDateRange.fromDay = 5;
        SearchDateRange.toDay = 20;
        assertEquals(MARCH_TO_MAY + ",\\\"start_day\\\":\\\"2026-03-05\\\",\\\"end_day\\\":\\\"2026-05-20\\\"",
                SearchDateRange.days(MARCH_TO_MAY));
        SimpleDateFormat months = new SimpleDateFormat("MMM yyyy", Locale.US);
        assertEquals("Mar 5, 2026 to May 20, 2026", SearchDateRange.label("Mar 2026 to May 2026", months));
        assertEquals("the days are used once", "Mar 2026 to May 2026",
                SearchDateRange.label("Mar 2026 to May 2026", months));
        String line = statusLine();
        assertTrue(line, line.contains(SearchDateRange.DAYS_SENT + " 1"));
    }

    @Test
    public void aLabelItCannotTakeApartStays() {
        Settings.SEARCH_DATE_RANGE.save(true);
        SearchDateRange.fromDay = 5;
        SearchDateRange.days(MARCH_TO_MAY);
        assertEquals("Spring", SearchDateRange.label("Spring", new SimpleDateFormat("MMM yyyy", Locale.US)));
        SearchDateRange.days(MARCH_TO_MAY);
        assertEquals("Mar 2026 to May 2026", SearchDateRange.label("Mar 2026 to May 2026", "not a format"));
    }

    @Test
    public void wholeMonthsOffOrPausedTheRangeAndLabelAreFacebooks() {
        Settings.SEARCH_DATE_RANGE.save(true);
        SimpleDateFormat months = new SimpleDateFormat("MMM yyyy", Locale.US);
        assertSame(MARCH_TO_MAY, SearchDateRange.days(MARCH_TO_MAY));
        assertEquals("Mar 2026 to May 2026", SearchDateRange.label("Mar 2026 to May 2026", months));
        SearchDateRange.fromDay = 5;
        Settings.SEARCH_DATE_RANGE.save(false);
        assertSame(MARCH_TO_MAY, SearchDateRange.days(MARCH_TO_MAY));
        Settings.SEARCH_DATE_RANGE.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertSame(MARCH_TO_MAY, SearchDateRange.days(MARCH_TO_MAY));
        assertEquals("Mar 2026 to May 2026", SearchDateRange.label("Mar 2026 to May 2026", months));
        assertNull(SearchDateRange.days(null));
    }

    @Test
    public void aRangeKeepsTheLabelItWasAppliedWithWhenTheServersAnswerComesBack() {
        Settings.SEARCH_DATE_RANGE.save(true);
        String value = "{\"name\":\"creation_time\",\"args\":\"{" + MARCH_TO_MAY + "}\"}";
        String applied = "Mar 5, 2026 to May 20, 2026";
        assertEquals(applied, SearchDateRange.stateLabel(SearchDateRange.FILTER, applied, value));
        assertEquals("the server's text for the range was shown", applied,
                SearchDateRange.stateLabel(SearchDateRange.FILTER, "Any date", value));
        assertTrue(String.valueOf(statusLine()).contains(SearchDateRange.LABEL_KEPT + " 1"));
        String june = value.replace("2026-05", "2026-06");
        assertEquals("another range took this one's label", "Any date",
                SearchDateRange.stateLabel(SearchDateRange.FILTER, "Any date", june));
    }

    @Test
    public void otherFiltersYearsOffOrPausedTheLabelIsFacebooks() {
        Settings.SEARCH_DATE_RANGE.save(true);
        String value = "{\"name\":\"creation_time\",\"args\":\"{" + MARCH_TO_MAY + "}\"}";
        SearchDateRange.stateLabel(SearchDateRange.FILTER, "Mar 2026 to May 2026", value);
        assertEquals("Anyone", SearchDateRange.stateLabel("rp_author", "Anyone", value));
        String year = "{\"name\":\"creation_time\",\"args\":\"{\\\"start_year\\\":\\\"2025\\\"}\"}";
        assertEquals("2025", SearchDateRange.stateLabel(SearchDateRange.FILTER, "2025", year));
        assertEquals("Any date", SearchDateRange.stateLabel(SearchDateRange.FILTER, "Any date", null));
        Settings.SEARCH_DATE_RANGE.save(false);
        assertEquals("off", "Any date", SearchDateRange.stateLabel(SearchDateRange.FILTER, "Any date", value));
        Settings.SEARCH_DATE_RANGE.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals("paused", "Any date", SearchDateRange.stateLabel(SearchDateRange.FILTER, "Any date", value));
    }

    @Test
    public void anAppliedRangeStaysThroughTheServersAnswer() {
        Settings.SEARCH_DATE_RANGE.save(true);
        State range = new State(SearchDateRange.FILTER, "{\"name\":\"creation_time\",\"args\":\"{" + MARCH_TO_MAY + "}\"}");
        Map<String, List<Object>> kept = new HashMap<>();
        kept.put(SearchDateRange.FILTER, Collections.singletonList(range));
        kept.put("rp_author", Collections.singletonList(new State("rp_author", "friends")));
        State author = new State("rp_author", "anyone");
        State anyDate = new State(SearchDateRange.FILTER, "");
        List<Object> states = SearchDateRange.keptStates(kept, Arrays.asList(author, anyDate));
        assertEquals("the server's Any date went and the range stayed", Arrays.asList(author, range), states);
        assertTrue(String.valueOf(statusLine()).contains(SearchDateRange.RANGE_KEPT + " 1"));
    }

    @Test
    public void noRangeKeptOneSentBackOffOrPausedLeaveTheAnswerAlone() {
        Settings.SEARCH_DATE_RANGE.save(true);
        String value = "{\"name\":\"creation_time\",\"args\":\"{" + MARCH_TO_MAY + "}\"}";
        Map<String, Object> kept = new HashMap<>();
        kept.put(SearchDateRange.FILTER, Collections.singletonList(new State(SearchDateRange.FILTER, value)));
        List<Object> incoming = Collections.singletonList(new State("rp_author", "anyone"));
        Map<String, Object> year = Collections.singletonMap(SearchDateRange.FILTER, Collections.singletonList(
                new State(SearchDateRange.FILTER, "{\"name\":\"creation_time\",\"args\":\"{\\\"start_year\\\":\\\"2025\\\"}\"}")));
        assertNull("a year kept", SearchDateRange.keptStates(year, incoming));
        assertNull("nothing kept", SearchDateRange.keptStates(new HashMap<>(), incoming));
        assertNull("not a list", SearchDateRange.keptStates(Collections.singletonMap(SearchDateRange.FILTER, "x"), incoming));
        assertNull("no map", SearchDateRange.keptStates(null, incoming));
        assertNull("sent back", SearchDateRange.keptStates(kept,
                Collections.singletonList(new State(SearchDateRange.FILTER, value))));
        Settings.SEARCH_DATE_RANGE.save(false);
        assertNull("off", SearchDateRange.keptStates(kept, incoming));
        Settings.SEARCH_DATE_RANGE.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertNull("paused", SearchDateRange.keptStates(kept, incoming));
    }

    @Test
    public void onThePageKeepsItsHeadingOnTopWithTheDayWheelsUnderIt() {
        Settings.SEARCH_DATE_RANGE.save(true);
        SearchDateRange.fromDay = 5;
        View picker = new View(RuntimeEnvironment.getApplication());
        View shown = SearchDateRange.dayRow(picker);
        assertTrue(shown instanceof LinearLayout);
        LinearLayout column = (LinearLayout) shown;
        assertEquals(2, column.getChildCount());
        assertSame("Facebook's page is kept, over the wheels", picker, column.getChildAt(0));
        LinearLayout row = (LinearLayout) column.getChildAt(1);
        assertTrue("the row paints its own background", row.getBackground() instanceof ColorDrawable);
        NumberPicker from = (NumberPicker) ((LinearLayout) row.getChildAt(0)).getChildAt(1);
        NumberPicker to = (NumberPicker) ((LinearLayout) row.getChildAt(1)).getChildAt(1);
        assertEquals("From day", ((TextView) ((LinearLayout) row.getChildAt(0)).getChildAt(0)).getText().toString());
        assertEquals(5, from.getValue());
        assertEquals(31, to.getValue());
        assertEquals(1, from.getMinValue());
        assertEquals(31, from.getMaxValue());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void theDayRowTakesTheColourThePagePaints() {
        View page = new View(RuntimeEnvironment.getApplication());
        page.setBackgroundColor(0xFF123456);
        page.layout(0, 0, 200, 100);
        View row = new View(RuntimeEnvironment.getApplication());
        row.setBackgroundColor(0xFF000001);
        SearchDateRange.paintLike(row, page);
        assertEquals(0xFF123456, ((ColorDrawable) row.getBackground()).getColor());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aPageThatPaintsNothingOrIsNotLaidOutLeavesTheRowsColour() {
        View row = new View(RuntimeEnvironment.getApplication());
        row.setBackgroundColor(0xFF000001);
        View clear = new View(RuntimeEnvironment.getApplication());
        clear.layout(0, 0, 200, 100);
        SearchDateRange.paintLike(row, clear);
        View unplaced = new View(RuntimeEnvironment.getApplication());
        unplaced.setBackgroundColor(0xFF123456);
        SearchDateRange.paintLike(row, unplaced);
        assertEquals(0xFF000001, ((ColorDrawable) row.getBackground()).getColor());
    }

    @Test
    public void offOrPausedThePickerIsFacebooksAlone() {
        View picker = new View(RuntimeEnvironment.getApplication());
        assertSame(picker, SearchDateRange.dayRow(picker));
        Settings.SEARCH_DATE_RANGE.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertSame(picker, SearchDateRange.dayRow(picker));
        assertNull(SearchDateRange.dayRow(null));
    }

    @Test
    public void theProbesAnswerOnlyWithTheSwitchOn() {
        assertFalse(SearchDateRangeForTests.sendsDays());
        assertFalse(SearchDateRangeForTests.wrapsThePicker());
        Settings.SEARCH_DATE_RANGE.save(true);
        assertTrue(SearchDateRangeForTests.sendsDays());
        assertTrue(SearchDateRangeForTests.wrapsThePicker());
    }
}
