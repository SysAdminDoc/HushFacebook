/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.search;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.facebook.reels.PostTextSelect;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.ui.Dim;

/**
 * Search date range (#279): the Posted date filter opens Facebook's own from and to month picker
 * in place of the list of years, with a day wheel for each end under it.
 *
 * <p>Facebook's filter screen is built from the kind each filter declares, and it already has a
 * date range picker for that kind: two month wheels that send the range to the search as a start
 * and end month. The Posted date filter ({@value #FILTER}) declares a list of choices instead. The
 * patch hands this class the filter and its kind where a tap on a filter reads the kind. With the
 * switch on, that one filter answers the date range kind, and everything else keeps its own.
 * Facebook shows the newer filter screen as a bottom sheet behind a server flag, and that sheet
 * only knows lists and toggles, so the flag is answered false for the same tap and Facebook takes
 * the screen that has the picker.
 *
 * <p>The search tabs' "Filter results" sheet drills into a filter's choices inside the sheet. It
 * hands the tap handler its own callback, and a tap with a callback goes to it and never reaches
 * the kind. For the date filter with the switch on, {@link #callback} answers no callback, so the
 * tap carries on to the kind and the picker.
 *
 * <p>The picker only asks for months, and Facebook Lite asks for days. The search takes a day too:
 * the same {@code creation_time} filter Facebook's website sends carries {@code start_day} and
 * {@code end_day} beside the months. {@link #dayRow} puts a From day and a To day wheel under the
 * picker, {@link #days} adds the two days to the range Facebook sends, clamped to each month's
 * length, and {@link #label} puts them in the chip Facebook shows for the range. The first of the
 * start month to the last of the end month is the range Facebook sends anyway, so it goes as it is.
 *
 * <p>The Date posted row read "Any date" after a range was applied. The server's answer to a search
 * replaces the states Facebook keeps for its filters with the ones the server sends back, and it
 * never sends a range back, so the range was gone from the filter screen and from the next search a
 * filter change ran. {@link #keptStates} keeps the applied range through that answer. Should the
 * server ever send a range back, it would come with the server's own text, so {@link #stateLabel}
 * gives a range's state the label it was applied with.
 */
public final class SearchDateRange {
    /** The name Facebook gives the Posted date filter. */
    static final String FILTER = "rp_creation_time";

    /** The kind whose screen is the from and to picker. */
    static final String DATE_RANGE = "DATE_RANGE_PICKER";

    /** The member the report names once a filter's kind has been asked about. */
    static final String FILTER_KIND = "filter kind";

    /** The member the report names once the bottom sheet flag has been asked about. */
    static final String SHEET_FLAG = "bottom sheet flag";

    /** The member the report names once a sheet's own tap callback has been asked about. */
    static final String SHEET_CALLBACK = "sheet tap callback";

    /** What the report counts each time the date filter opens as a range. */
    static final String OPENED = "Date filter opened as a range";

    /** The member the report names once the picker's view has been asked about. */
    static final String DAY_ROW = "day wheels";

    /** The member the report names once a range Facebook sends has been asked about. */
    static final String DAY_ARGS = "range days";

    /** The member the report names once the range's chip label has been asked about. */
    static final String DAY_LABEL = "range label";

    /** What the report counts each time a range goes out with its days. */
    static final String DAYS_SENT = "Range sent with days";

    /** The member the report names once a filter state's label has been asked about. */
    static final String STATE_LABEL = "filter state label";

    /** What the report counts each time a range's state gets back the label it was applied with. */
    static final String LABEL_KEPT = "Range label kept";

    /** The member the report names once the server's answer has handed over the states it brings. */
    static final String KEPT_STATES = "filter states kept";

    /** What the report counts each time an applied range stays through the server's answer. */
    static final String RANGE_KEPT = "Range kept through the server's answer";

    /** How many ranges' labels are kept, the newest last. */
    private static final int KEPT_LABELS = 16;

    /** The label each range was first labelled with, by the value it sends. */
    private static final Map<String, String> RANGE_LABELS = new LinkedHashMap<String, String>(KEPT_LABELS, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > KEPT_LABELS;
        }
    };

    /**
     * A quote as the range text holds it. That text goes into the filter's {@code args}, a JSON
     * string, so each of its quotes is escaped with a backslash.
     */
    static final String QUOTE = "\\\"";

    /** The months Facebook puts in a range it sends, as year and month for each end. */
    private static final Pattern MONTHS = Pattern.compile(Pattern.quote(QUOTE + "start_month" + QUOTE + ":" + QUOTE)
            + "(\\d{4})-(\\d{1,2})" + Pattern.quote(QUOTE + "," + QUOTE + "end_month" + QUOTE + ":" + QUOTE)
            + "(\\d{4})-(\\d{1,2})" + Pattern.quote(QUOTE));

    /** The text colors of Facebook's dark and light screens. */
    private static final int DARK_TEXT = 0xFFE4E6EB;
    private static final int LIGHT_TEXT = 0xFF050505;

    /** Facebook's dark and light sheet colours, for the day row until it reads the sheet's own. */
    private static final int DARK_SHEET = 0xFF242526;
    private static final int LIGHT_SHEET = 0xFFFFFFFF;

    private static final String FAMILY = FamilyNames.SEARCH_DATE_RANGE;

    /** True between {@link #kind} remapping a filter and {@link #bottomSheet} answering for the same tap. */
    private static boolean remapped;

    /** The day the From day wheel shows, kept for the next time the picker opens. */
    static int fromDay = 1;

    /** The day the To day wheel shows; past the end month's last day, it means that last day. */
    static int toDay = 31;

    /**
     * The range {@link #days} last sent with days, as start year, month and day and end year,
     * month and day, until {@link #label} uses it; null when it went as whole months.
     */
    private static int[] sent;

    private SearchDateRange() {
    }

    /**
     * The hook where a tap on a filter reads its kind, handed the filter and the kind Facebook
     * read. The date range kind comes back for the Posted date filter while the switch is on;
     * otherwise, and for a throw, the kind Facebook read comes back.
     */
    public static Object kind(Object filter, Object facebookKind) {
        remapped = false;
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, FILTER_KIND);
            if (!(facebookKind instanceof Enum) || !Utils.settingsReady() || !Settings.SEARCH_DATE_RANGE.get()) {
                return facebookKind;
            }
            Enum<?> current = (Enum<?>) facebookKind;
            String name = filterName(filter);
            if (DATE_RANGE.equals(current.name()) || !FILTER.equals(name)) {
                Logger.printDebug(() -> "Search date range: filter " + name + " keeps " + current.name());
                return facebookKind;
            }
            for (Object constant : current.getDeclaringClass().getEnumConstants()) {
                if (DATE_RANGE.equals(((Enum<?>) constant).name())) {
                    remapped = true;
                    HookStatus.counted(FAMILY, OPENED);
                    Logger.printDebug(() -> "Search date range: the date filter opens as a range (was " + current.name() + ")");
                    return constant;
                }
            }
            return facebookKind;
        } catch (Throwable failure) {
            remapped = false;
            HookStatus.threw(FAMILY, FILTER_KIND, failure);
            return facebookKind;
        }
    }

    /**
     * The hook on Facebook's bottom sheet flag, handed what Facebook read. False for the tap
     * {@link #kind} just remapped, so the range picker opens; otherwise what Facebook read.
     */
    public static boolean bottomSheet(boolean facebookSheet) {
        boolean mine = remapped;
        remapped = false;
        try {
            HookStatus.bound(FAMILY, SHEET_FLAG);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, SHEET_FLAG, failure);
        }
        return !mine && facebookSheet;
    }

    /**
     * The hook where a tap on a filter checks for a sheet's own callback, handed the filter, the
     * kind Facebook read and the callback. No callback comes back for the Posted date filter while
     * the switch is on, so the tap reaches {@link #kind}; otherwise, and for a throw, the callback.
     */
    public static Object callback(Object filter, Object facebookKind, Object facebookCallback) {
        try {
            HookStatus.bound(FAMILY, SHEET_CALLBACK);
            if (facebookCallback == null || !(facebookKind instanceof Enum) || !Utils.settingsReady()
                    || !Settings.SEARCH_DATE_RANGE.get()) {
                return facebookCallback;
            }
            String name = filterName(filter);
            if (DATE_RANGE.equals(((Enum<?>) facebookKind).name()) || !FILTER.equals(name)) {
                Logger.printDebug(() -> "Search date range: the sheet keeps filter " + name);
                return facebookCallback;
            }
            Logger.printDebug(() -> "Search date range: the sheet hands the date filter on to the picker");
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, SHEET_CALLBACK, failure);
            return facebookCallback;
        }
    }

    /**
     * The hook where the range picker hands back its view, the whole Date posted page with its
     * heading: with the switch on, the same page over a row with the From day and To day wheels;
     * otherwise, and for a throw, Facebook's view. The page paints the sheet behind itself and the
     * sheet doesn't, so once the page is laid out the row takes the colour the page paints.
     */
    public static View dayRow(View facebookView) {
        try {
            HookStatus.bound(FAMILY, DAY_ROW);
            if (facebookView == null || !Utils.settingsReady() || !Settings.SEARCH_DATE_RANGE.get()) {
                return facebookView;
            }
            Context context = facebookView.getContext();
            boolean dark = PostTextSelect.dark(context);
            int text = dark ? DARK_TEXT : LIGHT_TEXT;
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_HORIZONTAL);
            row.setPadding(Dim.dp16, Dim.dp8, Dim.dp16, Dim.dp16);
            row.setBackgroundColor(dark ? DARK_SHEET : LIGHT_SHEET);
            LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(wheel(context, L10n.t(context, "From day"), fromDay, text, day -> fromDay = day), half);
            row.addView(wheel(context, L10n.t(context, "To day"), toDay, text, day -> toDay = day),
                    new LinearLayout.LayoutParams(half));
            LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            column.addView(facebookView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            column.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            facebookView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View page, int left, int top, int right, int bottom,
                                           int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    page.removeOnLayoutChangeListener(this);
                    page.post(() -> paintLike(row, page));
                }
            });
            Logger.printDebug(() -> "Search date range: day wheels at " + fromDay + " and " + toDay);
            return column;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, DAY_ROW, failure);
            return facebookView;
        }
    }

    /**
     * Gives [row] the colour [page] paints at the middle of its top edge, above the heading's text,
     * so the row matches the sheet in every theme. Keeps the row's colour when the page can't be read.
     */
    static void paintLike(View row, View page) {
        int width = page.getWidth();
        int height = page.getHeight();
        if (width <= 0 || height <= 0) return;
        try {
            Bitmap pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(pixel);
            canvas.translate(-(width / 2f), -Math.min(height - 1, Dim.dp4));
            page.draw(canvas);
            int colour = pixel.getPixel(0, 0);
            pixel.recycle();
            if (Color.alpha(colour) == 0xFF) row.setBackgroundColor(colour);
        } catch (Throwable unreadable) {
            Logger.printDebug(() -> "Search date range: the sheet's colour couldn't be read");
        }
    }

    /** Takes the day a wheel moved to. */
    interface DayPicked {
        void picked(int day);
    }

    /** A titled wheel of the days 1 to 31, starting at [day]. */
    private static View wheel(Context context, String title, int day, int textColor, DayPicked picked) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView label = new TextView(context);
        label.setText(title);
        label.setTextColor(textColor);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setGravity(Gravity.CENTER);
        column.addView(label);
        NumberPicker days = new NumberPicker(context);
        days.setMinValue(1);
        days.setMaxValue(31);
        days.setValue(Math.max(1, Math.min(31, day)));
        days.setWrapSelectorWheel(true);
        days.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) days.setTextColor(textColor);
        days.setContentDescription(title);
        days.setOnValueChangedListener((wheel, before, now) -> picked.picked(now));
        column.addView(days);
        return column;
    }

    /**
     * The hook after Facebook writes the months of a range it sends, handed that text. With the
     * switch on and a day picked, the same text with the start and end day after it; otherwise,
     * and for a throw, Facebook's text.
     */
    public static String days(String facebookArgs) {
        sent = null;
        try {
            HookStatus.bound(FAMILY, DAY_ARGS);
            if (facebookArgs == null || !Utils.settingsReady() || !Settings.SEARCH_DATE_RANGE.get()) {
                return facebookArgs;
            }
            int[] range = range(facebookArgs, fromDay, toDay);
            if (range == null) return facebookArgs;
            sent = range;
            HookStatus.counted(FAMILY, DAYS_SENT);
            String withDays = facebookArgs + "," + field("start_day", day(range, 0)) + "," + field("end_day", day(range, 3));
            Logger.printDebug(() -> "Search date range: " + withDays);
            return withDays;
        } catch (Throwable failure) {
            sent = null;
            HookStatus.threw(FAMILY, DAY_ARGS, failure);
            return facebookArgs;
        }
    }

    /**
     * The range [args] names with the days [from] and [to], each held to its month's length, as
     * start year, month and day and end year, month and day, the earlier first. Null when [args]
     * names no months, or the days are the whole months Facebook sends anyway.
     */
    static int[] range(String args, int from, int to) {
        Matcher months = MONTHS.matcher(args);
        if (!months.find()) return null;
        int startYear = Integer.parseInt(months.group(1));
        int startMonth = Integer.parseInt(months.group(2));
        int endYear = Integer.parseInt(months.group(3));
        int endMonth = Integer.parseInt(months.group(4));
        if (startMonth < 1 || startMonth > 12 || endMonth < 1 || endMonth > 12) return null;
        int endLast = lastDay(endYear, endMonth);
        int startDay = Math.max(1, Math.min(from, lastDay(startYear, startMonth)));
        int endDay = Math.max(1, Math.min(to, endLast));
        if (startDay == 1 && endDay == endLast) return null;
        int[] range = {startYear, startMonth, startDay, endYear, endMonth, endDay};
        // Facebook puts the earlier month first, so only days in one month can be the wrong way round.
        if (startYear == endYear && startMonth == endMonth && startDay > endDay) {
            range[2] = endDay;
            range[5] = startDay;
        }
        return range;
    }

    /** The day of [range] starting at [at], written as the search takes it. */
    private static String day(int[] range, int at) {
        return String.format(Locale.US, "%04d-%02d-%02d", range[at], range[at + 1], range[at + 2]);
    }

    /** [name] and [value] as one field of the range text, quoted as Facebook quotes its months. */
    static String field(String name, String value) {
        return QUOTE + name + QUOTE + ":" + QUOTE + value + QUOTE;
    }

    /** How many days [month] (1 to 12) of [year] has. */
    static int lastDay(int year, int month) {
        return new GregorianCalendar(year, month - 1, 1).getActualMaximum(Calendar.DAY_OF_MONTH);
    }

    private static Date date(int year, int month, int day) {
        return new GregorianCalendar(year, month - 1, day).getTime();
    }

    /**
     * The hook after Facebook writes the chip label for a range it sends, handed the label and the
     * date format it wrote the two ends with. When {@link #days} sent days, the same label with each
     * end written as its day; otherwise, and for a label it can't take apart, Facebook's label.
     */
    public static String label(String facebookLabel, Object facebookFormat) {
        int[] range = sent;
        sent = null;
        try {
            HookStatus.bound(FAMILY, DAY_LABEL);
            if (range == null || facebookLabel == null || !(facebookFormat instanceof DateFormat)) return facebookLabel;
            DateFormat format = (DateFormat) facebookFormat;
            // Facebook writes the first of the start month and the last of the end month.
            String first = format.format(date(range[0], range[1], 1));
            String last = format.format(date(range[3], range[4], lastDay(range[3], range[4])));
            if (first.length() + last.length() > facebookLabel.length() || !facebookLabel.startsWith(first)
                    || !facebookLabel.endsWith(last)) {
                Logger.printDebug(() -> "Search date range: the label " + facebookLabel + " stays");
                return facebookLabel;
            }
            String between = facebookLabel.substring(first.length(), facebookLabel.length() - last.length());
            Locale locale = Locale.getDefault();
            DateFormat days = new SimpleDateFormat(
                    android.text.format.DateFormat.getBestDateTimePattern(locale, "yMMMd"), locale);
            return days.format(date(range[0], range[1], range[2])) + between
                    + days.format(date(range[3], range[4], range[5]));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, DAY_LABEL, failure);
            return facebookLabel;
        }
    }

    /**
     * The hook first thing in the constructor of every filter state Facebook keeps, handed the
     * filter's name, the label it shows and the value it sends. Facebook builds a range's state at
     * Apply, labelled with its two ends, and again from the server's answer, labelled with the text
     * the server has for that value, which for a range on the Posted date filter is the "Any date"
     * of its list of years. The first label a range's value gets is kept and comes back for that
     * value afterwards. Every other filter, and a throw, keeps its label.
     */
    public static String stateLabel(String name, String facebookLabel, String value) {
        if (!FILTER.equals(name) || value == null || !value.contains("start_month")) return facebookLabel;
        try {
            HookStatus.bound(FAMILY, STATE_LABEL);
            if (!Utils.settingsReady() || !Settings.SEARCH_DATE_RANGE.get()) return facebookLabel;
            synchronized (RANGE_LABELS) {
                String kept = RANGE_LABELS.get(value);
                if (kept == null) {
                    if (facebookLabel != null && !facebookLabel.isEmpty()) RANGE_LABELS.put(value, facebookLabel);
                    Logger.printDebug(() -> "Search date range: " + value + " is labelled " + facebookLabel);
                    return facebookLabel;
                }
                if (!kept.equals(facebookLabel)) {
                    HookStatus.counted(FAMILY, LABEL_KEPT);
                    Logger.printDebug(() -> "Search date range: " + facebookLabel + " shows as " + kept);
                }
                return kept;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, STATE_LABEL, failure);
            return facebookLabel;
        }
    }

    /** A filter state Facebook keeps, made to answer its filter's name and the value it sends by the patch. */
    public interface FilterState {
        String hushfacebookFilterName();

        String hushfacebookFilterValue();
    }

    /**
     * The hook first thing where the server's answer to a search replaces the states Facebook keeps
     * for its filters, handed the states kept so far, a list of them for each filter, and the ones
     * the answer brings. The server never sends a range back, so a range applied to the Posted date
     * filter would be dropped, the filter screen would read "Any date" and the next search a filter
     * change runs would go out without it. While the switch is on and a range is kept but not
     * brought back, the answer's states come back with the range in place of any Posted date state
     * of the server's. Null, for everything else and a throw, lets the answer's states through.
     */
    public static List<Object> keptStates(Map<?, ?> kept, List<?> incoming) {
        if (kept == null || incoming == null) return null;
        try {
            HookStatus.bound(FAMILY, KEPT_STATES);
            if (!Utils.settingsReady() || !Settings.SEARCH_DATE_RANGE.get()) return null;
            Object range = null;
            for (Object states : kept.values()) {
                if (!(states instanceof Iterable)) continue;
                for (Object state : (Iterable<?>) states) {
                    if (isRange(state)) range = state;
                }
            }
            if (range == null) return null;
            List<Object> states = new ArrayList<>(incoming.size() + 1);
            for (Object state : incoming) {
                if (isRange(state)) return null;
                if (!(state instanceof FilterState) || !FILTER.equals(((FilterState) state).hushfacebookFilterName())) {
                    states.add(state);
                }
            }
            states.add(range);
            HookStatus.counted(FAMILY, RANGE_KEPT);
            String value = ((FilterState) range).hushfacebookFilterValue();
            Logger.printDebug(() -> "Search date range: " + value + " kept through the server's answer");
            return states;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, KEPT_STATES, failure);
            return null;
        }
    }

    /** Whether {@code state} is a range applied to the Posted date filter. */
    private static boolean isRange(Object state) {
        if (!(state instanceof FilterState)) return false;
        FilterState filter = (FilterState) state;
        String value = filter.hushfacebookFilterValue();
        return FILTER.equals(filter.hushfacebookFilterName()) && value != null && value.contains("start_month");
    }

    /** Forgets every range's label, for tests. */
    static void forgetLabels() {
        synchronized (RANGE_LABELS) {
            RANGE_LABELS.clear();
        }
    }

    /** The filter's name, from the getName its interface keeps; null when it has none. */
    static String filterName(Object filter) {
        if (filter == null) return null;
        try {
            Method name = filter.getClass().getMethod("getName");
            // Facebook's filter classes needn't be public, and a public method of one still
            // refuses a caller from another package.
            name.setAccessible(true);
            Object value = name.invoke(filter);
            return value instanceof String ? (String) value : null;
        } catch (ReflectiveOperationException | RuntimeException notAFilter) {
            return null;
        }
    }
}
