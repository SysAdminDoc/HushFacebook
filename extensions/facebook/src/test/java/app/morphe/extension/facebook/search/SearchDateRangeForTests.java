/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.search;

import android.view.View;

import org.robolectric.RuntimeEnvironment;

import java.util.Collections;

/** Asks the range picker's day hooks what Facebook's picker and its Apply would. */
public final class SearchDateRangeForTests {
    /** The months Facebook writes for March to May 2026. */
    static final String MARCH_TO_MAY = months("2026-03", "2026-05");

    private SearchDateRangeForTests() {
    }

    /** The months as Facebook writes them, backslash before each quote: [start] and [end] as YYYY-MM. */
    static String months(String start, String end) {
        return "\\\"start_month\\\":\\\"" + start + "\\\",\\\"end_month\\\":\\\"" + end + "\\\"";
    }

    /** Whether a range sent with the From day on the 5th goes out with its days. */
    public static boolean sendsDays() {
        int before = SearchDateRange.fromDay;
        SearchDateRange.fromDay = 5;
        try {
            return !MARCH_TO_MAY.equals(SearchDateRange.days(MARCH_TO_MAY));
        } finally {
            SearchDateRange.fromDay = before;
            SearchDateRange.label(null, null);
        }
    }

    /** Whether the picker's view comes back under the day wheels. */
    public static boolean wrapsThePicker() {
        View picker = new View(RuntimeEnvironment.getApplication());
        return SearchDateRange.dayRow(picker) != picker;
    }

    /** Whether a range labelled at Apply gets that label back when the server labels it Any date. */
    public static boolean keepsTheLabel() {
        String value = "{\"name\":\"creation_time\",\"args\":\"{" + MARCH_TO_MAY + "}\"}";
        try {
            SearchDateRange.stateLabel(SearchDateRange.FILTER, "Mar 2026 to May 2026", value);
            return !"Any date".equals(SearchDateRange.stateLabel(SearchDateRange.FILTER, "Any date", value));
        } finally {
            SearchDateRange.forgetLabels();
        }
    }

    /** Whether a range kept before the server's answer stays in the states that answer brings. */
    public static boolean keepsTheRange() {
        String value = "{\"name\":\"creation_time\",\"args\":\"{" + MARCH_TO_MAY + "}\"}";
        SearchDateRangeTest.State range = new SearchDateRangeTest.State(SearchDateRange.FILTER, value);
        return SearchDateRange.keptStates(Collections.singletonMap(SearchDateRange.FILTER, Collections.singletonList(range)),
                Collections.emptyList()) != null;
    }
}
