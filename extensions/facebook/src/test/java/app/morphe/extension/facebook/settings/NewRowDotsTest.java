/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The dot beside a setting added since the settings were last opened (issue #16): none on a first
 * open, one on the row and on its section's home row when the stored set lacks it, gone once the
 * page has been left, inside the row in either direction at twice the text size, and no harm done
 * by a stored value that isn't a set.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SuppressWarnings("deprecation")
public class NewRowDotsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Read once a test has a context: Settings can't load before the rule gives it one. */
    private static String key() {
        return Settings.TAP_TO_PLAY.key;
    }
    private ActivityController<Activity> controller;
    private HushfacebookPreferenceFragment page;

    @Before
    public void everyPatchIn() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        PatchFamily.inBuildForTests.remove(PatchFamily.MATERIAL_YOU_THEME);
        PauseForTests.resume();
    }

    @After
    public void restore() {
        if (controller != null) controller.close();
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        RuntimeEnvironment.setFontScale(1f);
        NewRowDots.nowForTests = 0;
        Settings.NEW_ROW_MARK.resetToDefault();
    }

    /** Opens the settings on a fresh screen, as after the app was closed. */
    private void open() {
        if (controller != null) controller.close();
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        page = SettingsL10nTest.pageOf(SettingsL10nTest.show(controller.get()));
    }

    private SharedPreferences store() {
        return RuntimeEnvironment.getApplication().getSharedPreferences(NewRowDots.FILE, 0);
    }

    private ListView list() {
        return page.getView().findViewById(android.R.id.list);
    }

    private PreferenceCategory sectionOfKey() {
        Preference row = page.findPreference(key());
        assertNotNull(row);
        return (PreferenceCategory) row.getParent();
    }

    /** The views the list draws now, in order. */
    private List<View> views() {
        ListView list = list();
        List<View> views = new ArrayList<>();
        for (int i = 0; i < list.getAdapter().getCount(); i++) views.add(list.getAdapter().getView(i, null, list));
        return views;
    }

    private static boolean dotted(View row) {
        TextView title = row.findViewById(android.R.id.title);
        return title != null && NewRowDots.TAG.equals(title.getTag());
    }

    private List<String> dottedTitles() {
        List<String> titles = new ArrayList<>();
        for (View row : views()) {
            if (dotted(row)) titles.add(String.valueOf(((TextView) row.findViewById(android.R.id.title)).getText()));
        }
        return titles;
    }

    private void forget(String key) {
        Set<String> seen = new HashSet<>(store().getStringSet(NewRowDots.SEEN, new HashSet<>()));
        assertTrue(key + " was never seen", seen.remove(key));
        assertTrue(store().edit().putStringSet(NewRowDots.SEEN, seen).commit());
    }

    @Test
    public void aFirstOpenShowsNoDotAnywhere() {
        open();
        assertTrue(store().getStringSet(NewRowDots.SEEN, null).contains(key()));
        assertEquals(new ArrayList<String>(), dottedTitles());
        for (Preference section : page.sections()) {
            assertTrue(page.navigation.open(section));
            assertEquals(String.valueOf(section.getTitle()), new ArrayList<String>(), dottedTitles());
            while (page.navigation.back()) { }
        }
        assertEquals(new ArrayList<String>(), dottedTitles());
    }

    @Test
    public void aKeyMissingFromTheStoredSetDotsItsRowUntilTheSectionIsCollapsed() {
        open();
        forget(key());
        open();
        PreferenceCategory section = sectionOfKey();
        assertEquals("only the section's home row", java.util.Collections.singletonList(String.valueOf(section.getTitle())), dottedTitles());

        assertTrue(page.navigation.open(section));
        Preference row = page.findPreference(key());
        assertEquals("only the new row", java.util.Collections.singletonList(String.valueOf(row.getTitle())), dottedTitles());
        TextView title = null;
        for (View view : views()) if (dotted(view)) title = view.findViewById(android.R.id.title);
        assertNotNull(title);
        assertTrue(title.getContentDescription().toString().endsWith(", New"));
        // Opening it doesn't clear it: the dot shows for the whole visit.
        assertFalse(store().getStringSet(NewRowDots.SEEN, null).contains(key()));

        Preference header = null;
        for (int i = 0; i < page.navigation.getCount(); i++) {
            Preference item = page.navigation.getItem(i);
            if (("section_" + page.navigation.sectionId(section)).equals(item.getKey())) header = item;
        }
        assertNotNull(header);
        assertTrue(header.getOnPreferenceClickListener().onPreferenceClick(header));
        assertFalse(page.navigation.isExpanded(page.navigation.sectionId(section)));
        assertTrue(store().getStringSet(NewRowDots.SEEN, null).contains(key()));
        assertEquals("the home row is clear once the page was seen", new ArrayList<String>(), dottedTitles());
        assertTrue(page.navigation.open(section));
        assertEquals(new ArrayList<String>(), dottedTitles());
    }

    @Test
    public void closingTheSettingsOnAPageCountsItAsSeen() {
        open();
        forget(key());
        open();
        assertTrue(page.navigation.open(sectionOfKey()));
        open();
        assertEquals(new ArrayList<String>(), dottedTitles());
    }

    @Test
    public void leavingTheHomePageWithoutOpeningTheSectionKeepsItsDot() {
        open();
        forget(key());
        open();
        open();
        assertEquals(1, dottedTitles().size());
        assertFalse(store().getStringSet(NewRowDots.SEEN, null).contains(key()));
    }

    @Test
    public void searchResultsShowNoDots() {
        open();
        forget(key());
        open();
        page.navigation.search(String.valueOf(page.findPreference(key()).getTitle()));
        assertTrue(list().getAdapter().getCount() > 0);
        assertEquals(new ArrayList<String>(), dottedTitles());
        page.navigation.search("");
        assertEquals(1, dottedTitles().size());
    }

    @Test
    public void aStoredValueThatIsNotASetFailsOpenAndIsStartedAgain() {
        open();
        assertTrue(store().edit().remove(NewRowDots.SEEN).putString(NewRowDots.SEEN, "garbage").commit());
        open();
        assertEquals(new ArrayList<String>(), dottedTitles());
        assertTrue(store().getStringSet(NewRowDots.SEEN, null).contains(key()));
        assertTrue(page.navigation.open(sectionOfKey()));
        assertEquals(new ArrayList<String>(), dottedTitles());
    }

    @Test
    public void theSeenSetIsNotInABackupAndNoUnknownEntryIsCounted() throws Exception {
        open();
        forget(key());
        String text = SettingsBackup.create();
        assertFalse(text, text.contains(NewRowDots.FILE));
        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(text);
        assertEquals(0, snapshot.unknown);
        SettingsBackup.apply(snapshot);
        assertFalse("an import wrote the seen set", store().getStringSet(NewRowDots.SEEN, new HashSet<>()).contains(key()));
    }

    @Test
    public void aNumberOfDaysKeepsTheDotAfterThePageIsLeftUntilTheyPass() {
        long start = 1_800_000_000_000L;
        Settings.NEW_ROW_MARK.save(NewRowMark.THREE_DAYS);
        NewRowDots.nowForTests = start;
        open();
        forget(key());
        open();
        assertTrue(page.navigation.open(sectionOfKey()));
        open();
        assertFalse(store().getStringSet(NewRowDots.SEEN, null).contains(key()));
        assertEquals(start, store().getLong(NewRowDots.FOUND + key(), 0L));
        assertEquals("the section's home row keeps its dot", 1, dottedTitles().size());

        NewRowDots.nowForTests = start + 2 * NewRowDots.DAY;
        open();
        assertEquals(1, dottedTitles().size());
        assertTrue(page.navigation.open(sectionOfKey()));
        assertEquals(java.util.Collections.singletonList(String.valueOf(page.findPreference(key()).getTitle())), dottedTitles());
        open();
        assertEquals("a later visit doesn't start the days again", start, store().getLong(NewRowDots.FOUND + key(), 0L));

        NewRowDots.nowForTests = start + 3 * NewRowDots.DAY;
        open();
        assertEquals(new ArrayList<String>(), dottedTitles());
        assertTrue(page.navigation.open(sectionOfKey()));
        assertEquals(new ArrayList<String>(), dottedTitles());
        open();
        assertTrue(store().getStringSet(NewRowDots.SEEN, null).contains(key()));
        assertFalse(store().contains(NewRowDots.FOUND + key()));
    }

    @Test
    public void offShowsNoDotAndStillCountsALeftPageAsSeen() {
        Settings.NEW_ROW_MARK.save(NewRowMark.OFF);
        open();
        forget(key());
        open();
        assertEquals(new ArrayList<String>(), dottedTitles());
        assertTrue(page.navigation.open(sectionOfKey()));
        assertEquals(new ArrayList<String>(), dottedTitles());
        open();
        assertTrue(store().getStringSet(NewRowDots.SEEN, null).contains(key()));
        // Turned back on, it doesn't dot what was passed while it was off.
        Settings.NEW_ROW_MARK.save(NewRowMark.ONE_VISIT);
        open();
        assertEquals(new ArrayList<String>(), dottedTitles());
    }

    @Test
    public void theRowUnderAboutSaysHowLongTheDotStays() {
        open();
        Preference row = page.findPreference(Settings.NEW_ROW_MARK.key);
        assertTrue(row instanceof ValueRows.NewRowMarkRow);
        assertEquals("Mark new settings", String.valueOf(row.getTitle()));
        assertEquals("A dot marks each setting an update adds, until you leave the page it's on.", String.valueOf(row.getSummary()));
        ((ValueRows.NewRowMarkRow) row).setValue(NewRowMark.THREE_DAYS.name());
        assertEquals("A dot marks each setting an update adds, for 3 days from the first time you open its page.",
                String.valueOf(row.getSummary()));
        ((ValueRows.NewRowMarkRow) row).setValue(NewRowMark.OFF.name());
        assertEquals("Settings an update adds get no dot.", String.valueOf(row.getSummary()));
    }

    @Test
    public void atTwiceTheTextSizeTheDotStaysInsideItsRow() {
        RuntimeEnvironment.setFontScale(2f);
        assertInside(false);
    }

    @Test
    @Config(qualifiers = "+ar-rXB-ldrtl")
    public void inRightToLeftAtTwiceTheTextSizeTheDotStaysInsideItsRow() {
        RuntimeEnvironment.setFontScale(2f);
        assertInside(true);
    }

    private void assertInside(boolean rightToLeft) {
        open();
        forget(key());
        open();
        assertTrue(page.navigation.open(sectionOfKey()));
        assertEquals(rightToLeft, controller.get().getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
        int width = controller.get().getResources().getDisplayMetrics().widthPixels;
        int checked = 0;
        for (View row : views()) {
            if (!dotted(row)) continue;
            row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            row.layout(0, 0, width, row.getMeasuredHeight());
            TextView title = row.findViewById(android.R.id.title);
            Drawable[] sides = title.getCompoundDrawablesRelative();
            Drawable dot = sides[2];
            assertNotNull("the dot is not after the title", dot);
            assertNull(sides[0]);
            int size = Math.round(8 * controller.get().getResources().getDisplayMetrics().density);
            assertEquals(size, dot.getBounds().width());
            assertEquals(size, dot.getBounds().height());
            int left = 0;
            for (View at = title; at != row; at = (View) at.getParent()) left += at.getLeft();
            int right = left + title.getWidth();
            assertTrue("the title and its dot leave the row: " + left + ".." + right + " of " + width,
                    left >= 0 && right <= width);
            assertTrue(title.getLayout() != null && title.getLayout().getEllipsisCount(0) == 0);
            checked++;
        }
        assertEquals(1, checked);
    }
}
