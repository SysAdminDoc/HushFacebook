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

import app.morphe.extension.facebook.reels.CleanReturn;
import app.morphe.extension.shared.SettingsContextRule;

/**
 * Back to Clean mode under Reels (#519): right after the Clean mode rows, starting at Never, each
 * choice saying what it does. It goes in a settings file as its file value and comes back only as
 * one this build offers.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class ReelCleanReturnSettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DECLUTTER, PatchFamily.REEL_DOWNLOAD);
    }

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        Settings.REEL_CLEAN_RETURN.resetToDefault();
    }

    @Test
    public void theRowFollowsTheCleanModeButtonAndStartsAtNever() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            int button = indexOfKey(rows, Settings.REEL_CLEAN_BUTTON.key);
            int back = indexOfKey(rows, Settings.REEL_CLEAN_RETURN.key);
            assertTrue("Clean mode button on reels is missing", button >= 0);
            assertEquals(button + 1, back);
            assertEquals(back + 1, indexOfKey(rows, Settings.REEL_CLEAN_EXIT_FAINT.key));
            assertEquals(back + 2, indexOfKey(rows, Settings.PLAY_REELS_ONCE.key));

            ValueRows.CleanReturnRow row = (ValueRows.CleanReturnRow) rows.get(back);
            assertEquals("Back to Clean mode", String.valueOf(row.getTitle()));
            assertEquals(Arrays.asList("Never", "When the comments or a menu close", "After 3 seconds",
                    "After 5 seconds", "After 10 seconds"), texts(row.getEntries()));
            assertEquals(Arrays.asList("OFF", "WHEN_A_SHEET_CLOSES", "THREE_SECONDS", "FIVE_SECONDS", "TEN_SECONDS"),
                    texts(row.getEntryValues()));
            assertEquals(CleanReturn.OFF, Settings.REEL_CLEAN_RETURN.get());
            assertEquals("OFF", row.getValue());
            assertEquals("Never. A reel you take out of Clean mode stays out until you put it back.",
                    String.valueOf(row.getSummary()));

            row.setValue("WHEN_A_SHEET_CLOSES");
            ShadowLooper.idleMainLooper();
            assertEquals(CleanReturn.WHEN_A_SHEET_CLOSES, Settings.REEL_CLEAN_RETURN.savedValue());
            assertEquals("When the comments or a menu close. A reel you took out of Clean mode goes back into it "
                    + "once whatever you opened over it closes.", String.valueOf(row.getSummary()));

            row.setValue("FIVE_SECONDS");
            ShadowLooper.idleMainLooper();
            assertEquals(CleanReturn.FIVE_SECONDS, Settings.REEL_CLEAN_RETURN.savedValue());
            assertEquals("After 5 seconds. A reel you took out of Clean mode goes back into it once you haven't "
                    + "touched the screen for that long. An open sheet holds the time.", String.valueOf(row.getSummary()));
        }
    }

    /** Without the reel Download patch there's no Clean mode button, and the row follows Always use Clean mode. */
    @Test
    public void withoutTheButtonTheRowFollowsAlwaysUseCleanMode() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DECLUTTER);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            assertEquals(-1, indexOfKey(rows, Settings.REEL_CLEAN_BUTTON.key));
            assertEquals(indexOfKey(rows, Settings.REEL_CLEAN_MODE.key) + 1, indexOfKey(rows, Settings.REEL_CLEAN_RETURN.key));
        }
    }

    @Test
    public void withoutCleanUpReelsInTheBuildItDoesntShow() {
        PatchFamily.inBuildForTests = EnumSet.noneOf(PatchFamily.class);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            assertEquals(-1, indexOfKey(rowsOf(page(controller)), Settings.REEL_CLEAN_RETURN.key));
        }
    }

    /** Each choice goes out as its file value and comes back as itself. */
    @Test
    public void eachChoiceRoundTripsThroughASettingsFile() throws Exception {
        for (CleanReturn choice : CleanReturn.values()) {
            Settings.REEL_CLEAN_RETURN.save(choice);
            String file = SettingsBackup.create();
            assertEquals(choice.fileValue,
                    new JSONObject(file).getJSONObject("settings").get(SettingsBackup.CLEAN_RETURN.key));
            Settings.REEL_CLEAN_RETURN.save(choice == CleanReturn.OFF ? CleanReturn.TEN_SECONDS : CleanReturn.OFF);

            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
            assertEquals(choice, snapshot.cleanReturn);
            assertEquals(choice, snapshot.cleanReturnChange());
            assertEquals(Collections.singletonMap(SettingsBackup.CLEAN_RETURN, choice), snapshot.changes());
            assertEquals(1, SettingsBackup.apply(snapshot));
            assertEquals(choice, Settings.REEL_CLEAN_RETURN.savedValue());
            assertEquals("a file read back is the file", file, SettingsBackup.create());
        }
    }

    /** A value this build doesn't offer refuses the whole file, and a file from before the row leaves it alone. */
    @Test
    public void onlyAChoiceThisBuildOffersComesBack() throws Exception {
        Settings.REEL_CLEAN_RETURN.save(CleanReturn.THREE_SECONDS);
        String file = SettingsBackup.create();
        for (Object refused : new Object[]{"THREE_SECONDS", "3 seconds", "3", "", 3, 3_000, true, JSONObject.NULL}) {
            refuses(file, SettingsBackup.CLEAN_RETURN.key, refused);
        }
        assertEquals("a refused file wrote something", CleanReturn.THREE_SECONDS, Settings.REEL_CLEAN_RETURN.savedValue());

        JSONObject older = new JSONObject(file);
        older.getJSONObject("settings").remove(SettingsBackup.CLEAN_RETURN.key);
        Settings.REEL_CLEAN_RETURN.save(CleanReturn.WHEN_A_SHEET_CLOSES);
        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(older.toString());
        assertNull(snapshot.cleanReturn);
        assertNull(snapshot.cleanReturnChange());
        SettingsBackup.apply(snapshot);
        assertEquals(CleanReturn.WHEN_A_SHEET_CLOSES, Settings.REEL_CLEAN_RETURN.savedValue());

        // A preview kept across a rebuild keeps it, and only a choice this build offers comes back.
        Bundle state = SettingsBackup.parse(file).toBundle();
        assertEquals(CleanReturn.THREE_SECONDS, SettingsBackup.Snapshot.fromBundle(state).cleanReturn);
        state.putString("reel_clean_return", "THREE_SECONDS");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).cleanReturn);
    }

    @Test
    public void theImportSentencesNameTheChoice() {
        assertEquals("A reel you take out of Clean mode will stay out until you put it back.",
                SettingsBackupPreference.cleanReturnSentence(CleanReturn.OFF));
        assertEquals("A reel you take out of Clean mode will go back into it when the comments or a menu close.",
                SettingsBackupPreference.cleanReturnSentence(CleanReturn.WHEN_A_SHEET_CLOSES));
        assertEquals("A reel you take out of Clean mode will go back into it after 10 seconds without a touch.",
                SettingsBackupPreference.cleanReturnSentence(CleanReturn.TEN_SECONDS));
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
