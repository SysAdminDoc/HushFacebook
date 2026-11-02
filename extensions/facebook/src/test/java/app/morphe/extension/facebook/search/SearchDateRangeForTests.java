/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.search;

import android.view.View;

import org.robolectric.RuntimeEnvironment;

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
}
