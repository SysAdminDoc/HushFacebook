/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.TwoStatePreference;

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

import app.morphe.extension.facebook.reels.GrayOver;
import app.morphe.extension.shared.SettingsContextRule;

/**
 * Turn gray over, right under Show Reels in gray: at once to start, greyed out while the switch is
 * off and saying why, each choice saying what it does. It goes in a settings file as its file value
 * and comes back only as one this build offers.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class GrayOverSettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DECLUTTER, PatchFamily.HDR_BRIGHTNESS);
    }

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        Settings.GRAY_REELS.resetToDefault();
        Settings.GRAY_REELS_OVER.resetToDefault();
        Settings.REELS_MINUTES.resetToDefault();
    }

    /** Show time in Reels sits under Turn gray over, off to start, and works without the gray. */
    @Test
    public void theTimeSwitchSitsUnderTurnGrayOverAndStartsOff() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            int over = indexOfKey(rows, Settings.GRAY_REELS_OVER.key);
            assertTrue("Turn gray over is missing", over >= 0);
            assertEquals(over + 1, indexOfKey(rows, Settings.REELS_MINUTES.key));
            TwoStatePreference time = (TwoStatePreference) rows.get(over + 1);
            assertEquals("Show time in Reels", String.valueOf(time.getTitle()));
            assertFalse(time.isChecked());
            assertFalse(Settings.GRAY_REELS.get());
            assertTrue("Show time in Reels waits on the gray switch", time.isEnabled());
            time.setChecked(true);
            ShadowLooper.idleMainLooper();
            assertTrue("the switch didn't save", Settings.REELS_MINUTES.get());
        }
    }

    @Test
    public void theRowFollowsTheGraySwitchAndStartsAtOnce() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            int gray = indexOfKey(rows, Settings.GRAY_REELS.key);
            assertTrue("Show Reels in gray is missing", gray >= 0);
            assertEquals(gray + 1, indexOfKey(rows, Settings.GRAY_REELS_OVER.key));

            ValueRows.BreakRow row = (ValueRows.BreakRow) rows.get(gray + 1);
            assertEquals("Turn gray over", String.valueOf(row.getTitle()));
            assertEquals(Arrays.asList("At once", "5 minutes", "10 minutes", "15 minutes", "30 minutes"),
                    texts(row.getEntries()));
            assertEquals(Arrays.asList("AT_ONCE", "FIVE_MINUTES", "TEN_MINUTES", "FIFTEEN_MINUTES", "THIRTY_MINUTES"),
                    texts(row.getEntryValues()));
            assertEquals(GrayOver.AT_ONCE, Settings.GRAY_REELS_OVER.get());
            assertEquals("AT_ONCE", row.getValue());

            assertFalse("Turn gray over is open with Show Reels in gray off", row.isEnabled());
            String greyed = String.valueOf(row.getSummary());
            assertTrue("the greyed row doesn't say why: " + greyed,
                    greyed.startsWith("Reels are gray from the start. Turn on")
                            && greyed.contains(SwitchLabels.title(Settings.GRAY_REELS))
                            && greyed.endsWith("to use this."));

            ((TwoStatePreference) rows.get(gray)).setChecked(true);
            ShadowLooper.idleMainLooper();
            assertTrue("Turn gray over stayed greyed with the switch on", row.isEnabled());
            assertEquals("Reels are gray from the start.", String.valueOf(row.getSummary()));

            row.setValue("TEN_MINUTES");
            ShadowLooper.idleMainLooper();
            assertEquals(GrayOver.TEN_MINUTES, Settings.GRAY_REELS_OVER.savedValue());
            assertEquals("Reels lose their color over 10 minutes of watching. 15 minutes away starts them in color "
                    + "again.", String.valueOf(row.getSummary()));
        }
    }

    @Test
    public void withoutTheHdrHookNeitherShows() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DECLUTTER);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            assertEquals(-1, indexOfKey(rows, Settings.GRAY_REELS.key));
            assertEquals(-1, indexOfKey(rows, Settings.GRAY_REELS_OVER.key));
            assertEquals(-1, indexOfKey(rows, Settings.REELS_MINUTES.key));
        }
    }

    /** Each choice goes out as its file value and comes back as itself. */
    @Test
    public void eachChoiceRoundTripsThroughASettingsFile() throws Exception {
        for (GrayOver choice : GrayOver.values()) {
            Settings.GRAY_REELS_OVER.save(choice);
            String file = SettingsBackup.create();
            assertEquals(choice.fileValue, new JSONObject(file).getJSONObject("settings").get(SettingsBackup.GRAY_OVER.key));
            Settings.GRAY_REELS_OVER.save(choice == GrayOver.AT_ONCE ? GrayOver.THIRTY_MINUTES : GrayOver.AT_ONCE);

            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
            assertEquals(choice, snapshot.grayOver);
            assertEquals(choice, snapshot.grayOverChange());
            assertEquals(Collections.singletonMap(SettingsBackup.GRAY_OVER, choice), snapshot.changes());
            assertEquals(1, SettingsBackup.apply(snapshot));
            assertEquals(choice, Settings.GRAY_REELS_OVER.savedValue());
            assertEquals("a file read back is the file", file, SettingsBackup.create());
        }
    }

    /** A value this build doesn't offer refuses the whole file, and a file from before the list leaves it alone. */
    @Test
    public void onlyAChoiceThisBuildOffersComesBack() throws Exception {
        Settings.GRAY_REELS_OVER.save(GrayOver.FIFTEEN_MINUTES);
        String file = SettingsBackup.create();
        for (Object refused : new Object[]{"FIFTEEN_MINUTES", "15 minutes", "15", "20m", "", 15, 900_000, true,
                JSONObject.NULL}) {
            refuses(file, SettingsBackup.GRAY_OVER.key, refused);
        }
        assertEquals("a refused file wrote something", GrayOver.FIFTEEN_MINUTES, Settings.GRAY_REELS_OVER.savedValue());

        JSONObject older = new JSONObject(file);
        older.getJSONObject("settings").remove(SettingsBackup.GRAY_OVER.key);
        Settings.GRAY_REELS_OVER.save(GrayOver.FIVE_MINUTES);
        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(older.toString());
        assertNull(snapshot.grayOver);
        assertNull(snapshot.grayOverChange());
        SettingsBackup.apply(snapshot);
        assertEquals(GrayOver.FIVE_MINUTES, Settings.GRAY_REELS_OVER.savedValue());

        // A preview kept across a rebuild keeps it, and only one this build offers comes back.
        Bundle state = SettingsBackup.parse(file).toBundle();
        assertEquals(GrayOver.FIFTEEN_MINUTES, SettingsBackup.Snapshot.fromBundle(state).grayOver);
        state.putString("gray_reels_over", "FIFTEEN_MINUTES");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).grayOver);
    }

    @Test
    public void theImportSentenceNamesTheChoice() {
        assertEquals("Show Reels in gray will make reels gray from the start.",
                SettingsBackupPreference.grayOverSentence(GrayOver.AT_ONCE));
        assertEquals("Show Reels in gray will take reels from color to gray over 30 minutes.",
                SettingsBackupPreference.grayOverSentence(GrayOver.THIRTY_MINUTES));
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
