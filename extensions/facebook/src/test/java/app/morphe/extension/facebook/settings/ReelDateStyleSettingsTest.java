/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceGroup;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import app.morphe.extension.facebook.reels.ReelDateStyle;
import app.morphe.extension.shared.SettingsContextRule;

/**
 * Show the post date as (#357): a list right under Show the post date, How long ago to start, each
 * choice shown with an example. It goes in a settings file as its file value and comes back only as
 * one this build offers.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class ReelDateStyleSettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DECLUTTER);
    }

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        Settings.REEL_POST_DATE_STYLE.resetToDefault();
    }

    @Test
    public void theListFollowsTheDateSwitchAndSaysWhatEachChoiceLooksLike() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            int date = indexOfKey(rows, Settings.SHOW_REEL_POST_DATE.key);
            assertTrue("Show the post date is missing", date >= 0);
            assertEquals(date + 1, indexOfKey(rows, Settings.REEL_POST_DATE_STYLE.key));

            ValueRows.ReelDateStyleRow row = (ValueRows.ReelDateStyleRow) rows.get(date + 1);
            assertEquals("Show the post date as", String.valueOf(row.getTitle()));
            assertEquals(Arrays.asList("How long ago", "The date"), texts(row.getEntries()));
            assertEquals(Arrays.asList("AGO", "DATE"), texts(row.getEntryValues()));
            assertEquals(ReelDateStyle.AGO, Settings.REEL_POST_DATE_STYLE.get());
            assertEquals("AGO", row.getValue());
            assertEquals("Like \"3 weeks ago\". A change shows from the next reel.", String.valueOf(row.getSummary()));

            row.setValue("DATE");
            ShadowLooper.idleMainLooper();
            assertEquals(ReelDateStyle.DATE, Settings.REEL_POST_DATE_STYLE.savedValue());
            String summary = String.valueOf(row.getSummary());
            assertTrue(summary, summary.matches("Like \"[A-Z][a-z]{2} \\d{1,2}(, \\d{4})?\"\\. A change shows from the next reel\\."));
        }
    }

    @Test
    public void withoutCleanUpReelsInTheBuildItDoesntShow() {
        PatchFamily.inBuildForTests = EnumSet.noneOf(PatchFamily.class);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            assertEquals(-1, indexOfKey(rowsOf(page(controller)), Settings.REEL_POST_DATE_STYLE.key));
        }
    }

    /** Each choice goes out as its file value and comes back as itself. */
    @Test
    public void eachChoiceRoundTripsThroughASettingsFile() throws Exception {
        for (ReelDateStyle choice : ReelDateStyle.values()) {
            Settings.REEL_POST_DATE_STYLE.save(choice);
            String file = SettingsBackup.create();
            assertEquals(choice.fileValue, new JSONObject(file).getJSONObject("settings").get(SettingsBackup.DATE_STYLE.key));
            Settings.REEL_POST_DATE_STYLE.save(choice == ReelDateStyle.AGO ? ReelDateStyle.DATE : ReelDateStyle.AGO);

            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
            assertEquals(choice, snapshot.dateStyle);
            assertEquals(choice, snapshot.dateStyleChange());
            assertEquals(Collections.singletonMap(SettingsBackup.DATE_STYLE, choice), snapshot.changes());
            assertEquals(1, SettingsBackup.apply(snapshot));
            assertEquals(choice, Settings.REEL_POST_DATE_STYLE.savedValue());
            assertEquals("a file read back is the file", file, SettingsBackup.create());
        }
    }

    /** A value this build doesn't offer refuses the whole file, and a file from before the list leaves it alone. */
    @Test
    public void onlyAChoiceThisBuildOffersComesBack() throws Exception {
        Settings.REEL_POST_DATE_STYLE.save(ReelDateStyle.DATE);
        String file = SettingsBackup.create();
        for (Object refused : new Object[]{"DATE", "The date", "weeks", "", 1, true, JSONObject.NULL}) {
            refuses(file, SettingsBackup.DATE_STYLE.key, refused);
        }
        assertEquals("a refused file wrote something", ReelDateStyle.DATE, Settings.REEL_POST_DATE_STYLE.savedValue());

        JSONObject older = new JSONObject(file);
        older.getJSONObject("settings").remove(SettingsBackup.DATE_STYLE.key);
        Settings.REEL_POST_DATE_STYLE.save(ReelDateStyle.AGO);
        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(older.toString());
        assertNull(snapshot.dateStyle);
        assertNull(snapshot.dateStyleChange());
        SettingsBackup.apply(snapshot);
        assertEquals(ReelDateStyle.AGO, Settings.REEL_POST_DATE_STYLE.savedValue());

        // A preview kept across a rebuild keeps it, and only one this build offers comes back.
        Bundle state = SettingsBackup.parse(file).toBundle();
        assertEquals(ReelDateStyle.DATE, SettingsBackup.Snapshot.fromBundle(state).dateStyle);
        state.putString("reel_post_date_style", "DATE");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).dateStyle);
    }

    @Test
    public void theImportSentencesNameTheChoice() {
        assertEquals("Reel post dates will show how long ago they were posted.",
                SettingsBackupPreference.dateStyleSentence(ReelDateStyle.AGO));
        assertEquals("Reel post dates will show the day they were posted.",
                SettingsBackupPreference.dateStyleSentence(ReelDateStyle.DATE));
    }

    private static void refuses(String file, String key, Object refused) throws Exception {
        JSONObject hostile = new JSONObject(file);
        hostile.getJSONObject("settings").put(key, refused);
        try {
            SettingsBackup.parse(hostile.toString());
            fail("a file with " + key + " = " + refused + " was read");
        } catch (SettingsBackup.Rejected rejected) {
            assertEquals(key + " = " + refused, SettingsBackup.Reason.VALUE, rejected.reason);
        }
    }

    private static List<String> texts(CharSequence[] items) {
        List<String> texts = new ArrayList<>();
        for (CharSequence item : items) texts.add(String.valueOf(item));
        return texts;
    }

    private static HushfacebookPreferenceFragment page(ActivityController<Activity> controller) {
        HushfacebookPreferenceFragment page = new HushfacebookPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        return page;
    }

    private static List<Preference> rowsOf(HushfacebookPreferenceFragment page) {
        List<Preference> rows = new ArrayList<>();
        collect(page.getPreferenceScreen(), rows);
        return rows;
    }

    private static void collect(PreferenceGroup group, List<Preference> rows) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference row = group.getPreference(i);
            if (row instanceof PreferenceGroup) collect((PreferenceGroup) row, rows);
            else rows.add(row);
        }
    }

    private static int indexOfKey(List<Preference> rows, String key) {
        for (int i = 0; i < rows.size(); i++) {
            if (key.equals(rows.get(i).getKey())) return i;
        }
        return -1;
    }
}
