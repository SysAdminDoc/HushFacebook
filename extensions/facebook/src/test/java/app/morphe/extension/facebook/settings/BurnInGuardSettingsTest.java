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
import android.preference.SwitchPreference;

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

import app.morphe.extension.facebook.reels.BurnInMode;
import app.morphe.extension.facebook.reels.BurnInWait;
import app.morphe.extension.shared.SettingsContextRule;

/**
 * Burn-in guard's rows under Reels (#288): its list, Dim after and Stay dim when I tap, right after
 * Hide the top right buttons on opened videos, all off or at five seconds to start, each saying what
 * its choice does. The two lists go in a settings file as their file values and come back only as
 * ones this build offers.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class BurnInGuardSettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DECLUTTER);
    }

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        Settings.BURN_IN_GUARD.resetToDefault();
        Settings.BURN_IN_WAIT.resetToDefault();
        Settings.BURN_IN_STAY_DIM.resetToDefault();
        Settings.BURN_IN_LEVEL.resetToDefault();
        Settings.BURN_IN_FADE.resetToDefault();
    }

    @Test
    public void theRowsFollowTheTopButtonsSwitchUnderReelsAndStartOff() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            int top = indexOfKey(rows, Settings.HIDE_VIEWER_TOP_BUTTONS.key);
            int guard = indexOfKey(rows, Settings.BURN_IN_GUARD.key);
            int wait = indexOfKey(rows, Settings.BURN_IN_WAIT.key);
            int level = indexOfKey(rows, Settings.BURN_IN_LEVEL.key);
            int stay = indexOfKey(rows, Settings.BURN_IN_STAY_DIM.key);
            int fade = indexOfKey(rows, Settings.BURN_IN_FADE.key);
            assertTrue("Hide the top right buttons on opened videos is missing", top >= 0);
            assertEquals(top + 1, guard);
            assertEquals(guard + 1, wait);
            assertEquals(wait + 1, level);
            assertEquals(level + 1, stay);
            assertEquals(stay + 1, fade);
            assertEquals(fade + 1, indexOfKey(rows, Settings.HIDE_REEL_COUNTS.key));

            assertTrue(rows.get(guard) instanceof ValueRows.BurnInGuardRow);
            assertTrue(rows.get(wait) instanceof ValueRows.BurnInWaitRow);
            assertTrue(rows.get(stay) instanceof SwitchPreference);
            SwitchPreference stayDim = (SwitchPreference) rows.get(stay);
            assertEquals("Stay dim when I tap", String.valueOf(stayDim.getTitle()));
            assertEquals("Once Burn-in guard fades the buttons, they stay faded on Reels whatever you tap or swipe, "
                    + "the next reel too. They still work. Leaving Reels brings them back.",
                    String.valueOf(stayDim.getSummary()));
            assertFalse(stayDim.isChecked());
            assertFalse(Settings.BURN_IN_STAY_DIM.defaultValue);

            SwitchPreference smooth = (SwitchPreference) rows.get(fade);
            assertEquals("Smooth fade", String.valueOf(smooth.getTitle()));
            assertEquals("The buttons, caption and bars fade out slowly as they dim and fade back in when they return, "
                    + "rather than changing at once.", String.valueOf(smooth.getSummary()));
            assertFalse(smooth.isChecked());
            assertFalse(Settings.BURN_IN_FADE.defaultValue);
        }
    }

    /** Dim to (#523): blank is the quarter Burn-in guard always dimmed to, and a number is held to 0 to 100. */
    @Test
    public void dimToTakesAPercentAndSaysWhatItShows() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            ValueRows.PercentRow row = (ValueRows.PercentRow) rows.get(indexOfKey(rows, Settings.BURN_IN_LEVEL.key));
            assertEquals("Dim to", String.valueOf(row.getTitle()));
            assertEquals("How much of the buttons, caption and bars still shows once they dim, in percent, from 0 to "
                    + "100. Leave it blank for 25.", String.valueOf(row.getDialogMessage()));
            assertEquals("Once dimmed, the buttons, caption and bars show at 25 percent. Type a number to change it.",
                    String.valueOf(row.getSummary()));
            row.setText("40");
            assertEquals("Once dimmed, the buttons, caption and bars show at 40 percent.", String.valueOf(row.getSummary()));
            row.setText("250");
            assertEquals("Once dimmed, the buttons, caption and bars show at 100 percent.", String.valueOf(row.getSummary()));
            row.setText("0");
            assertEquals("Once dimmed, the buttons, caption and bars show at 0 percent.", String.valueOf(row.getSummary()));
            assertEquals("", Settings.BURN_IN_LEVEL.defaultValue);
        }
    }

    @Test
    public void withoutCleanUpReelsInTheBuildNoneShows() {
        PatchFamily.inBuildForTests = EnumSet.noneOf(PatchFamily.class);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            assertEquals(-1, indexOfKey(rows, Settings.BURN_IN_GUARD.key));
            assertEquals(-1, indexOfKey(rows, Settings.BURN_IN_WAIT.key));
            assertEquals(-1, indexOfKey(rows, Settings.BURN_IN_STAY_DIM.key));
            assertEquals(-1, indexOfKey(rows, Settings.BURN_IN_LEVEL.key));
            assertEquals(-1, indexOfKey(rows, Settings.BURN_IN_FADE.key));
        }
    }

    @Test
    public void theGuardsListOffersEachChoiceAndSaysWhatItDoes() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            ValueRows.BurnInGuardRow row = (ValueRows.BurnInGuardRow) rows.get(indexOfKey(rows, Settings.BURN_IN_GUARD.key));
            assertEquals("Burn-in guard", String.valueOf(row.getTitle()));
            assertEquals(Arrays.asList("Off", "Dim when idle", "Dim when idle and shift"), texts(row.getEntries()));
            assertEquals(Arrays.asList("OFF", "DIM", "DIM_AND_SHIFT"), texts(row.getEntryValues()));
            assertEquals(BurnInMode.OFF, Settings.BURN_IN_GUARD.get());
            assertEquals("OFF", row.getValue());
            assertEquals("Off. It can fade a reel's buttons while the screen sits untouched, so they don't burn into "
                    + "an OLED screen.", String.valueOf(row.getSummary()));

            row.setValue("DIM_AND_SHIFT");
            ShadowLooper.idleMainLooper();
            assertEquals(BurnInMode.DIM_AND_SHIFT, Settings.BURN_IN_GUARD.savedValue());
            assertEquals("On the Reels screen, the buttons, caption and bars fade when you leave them alone, and the "
                    + "screen moves a step every 2 minutes. Touching one of them brings them back.",
                    String.valueOf(row.getSummary()));

            row.setValue("DIM");
            ShadowLooper.idleMainLooper();
            assertEquals(BurnInMode.DIM, Settings.BURN_IN_GUARD.savedValue());
            assertEquals("On the Reels screen, the buttons, caption and bars fade when you leave them alone. Touching "
                    + "one of them brings them back.", String.valueOf(row.getSummary()));
        }
    }

    @Test
    public void dimAfterStartsAtFiveSecondsAndSaysWhatDoesntCount() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            ValueRows.BurnInWaitRow row = (ValueRows.BurnInWaitRow) rows.get(indexOfKey(rows, Settings.BURN_IN_WAIT.key));
            assertEquals("Dim after", String.valueOf(row.getTitle()));
            assertEquals(Arrays.asList("At once", "After 2 seconds", "After 5 seconds", "After 10 seconds"),
                    texts(row.getEntries()));
            assertEquals(Arrays.asList("AT_ONCE", "TWO_SECONDS", "FIVE_SECONDS", "TEN_SECONDS"), texts(row.getEntryValues()));
            assertEquals(BurnInWait.FIVE_SECONDS, Settings.BURN_IN_WAIT.get());
            assertEquals("FIVE_SECONDS", row.getValue());
            assertEquals("After 5 seconds. Tapping the video and double-tapping it to like don't count as a touch, so "
                    + "the buttons stay faded.", String.valueOf(row.getSummary()));

            row.setValue("AT_ONCE");
            ShadowLooper.idleMainLooper();
            assertEquals(BurnInWait.AT_ONCE, Settings.BURN_IN_WAIT.savedValue());
            assertTrue(String.valueOf(row.getSummary()), String.valueOf(row.getSummary()).startsWith("At once. Tapping"));
        }
    }

    /** Each choice of either list goes out as its file value and comes back as itself. */
    @Test
    public void bothListsRoundTripThroughASettingsFile() throws Exception {
        for (BurnInMode choice : BurnInMode.values()) {
            Settings.BURN_IN_GUARD.save(choice);
            String file = SettingsBackup.create();
            assertEquals(choice.fileValue, new JSONObject(file).getJSONObject("settings").get(SettingsBackup.BURN_IN.key));
            Settings.BURN_IN_GUARD.save(choice == BurnInMode.DIM ? BurnInMode.OFF : BurnInMode.DIM);

            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
            assertEquals(choice, snapshot.burnIn);
            assertEquals(choice, snapshot.burnInChange());
            assertEquals(Collections.singletonMap(SettingsBackup.BURN_IN, choice), snapshot.changes());
            assertEquals(1, SettingsBackup.apply(snapshot));
            assertEquals(choice, Settings.BURN_IN_GUARD.savedValue());
            assertEquals("a file read back is the file", file, SettingsBackup.create());
        }
        for (BurnInWait choice : BurnInWait.values()) {
            Settings.BURN_IN_WAIT.save(choice);
            String file = SettingsBackup.create();
            assertEquals(choice.fileValue, new JSONObject(file).getJSONObject("settings").get(SettingsBackup.BURN_IN_WAIT.key));
            Settings.BURN_IN_WAIT.save(choice == BurnInWait.AT_ONCE ? BurnInWait.TEN_SECONDS : BurnInWait.AT_ONCE);

            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
            assertEquals(choice, snapshot.burnInWait);
            assertEquals(choice, snapshot.burnInWaitChange());
            assertEquals(Collections.singletonMap(SettingsBackup.BURN_IN_WAIT, choice), snapshot.changes());
            assertEquals(1, SettingsBackup.apply(snapshot));
            assertEquals(choice, Settings.BURN_IN_WAIT.savedValue());
            assertEquals("a file read back is the file", file, SettingsBackup.create());
        }
    }

    /** A value this build doesn't offer refuses the whole file, and a file from before the lists leaves them alone. */
    @Test
    public void onlyAChoiceThisBuildOffersComesBack() throws Exception {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        Settings.BURN_IN_WAIT.save(BurnInWait.TEN_SECONDS);
        String file = SettingsBackup.create();
        for (Object refused : new Object[]{"DIM", "Dim when idle", "shift", "", 5, true, JSONObject.NULL}) {
            refuses(file, SettingsBackup.BURN_IN.key, refused);
        }
        for (Object refused : new Object[]{"TEN_SECONDS", "10 seconds", "3s", "", 10, 10_000, false, JSONObject.NULL}) {
            refuses(file, SettingsBackup.BURN_IN_WAIT.key, refused);
        }
        assertEquals("a refused file wrote something", BurnInMode.DIM, Settings.BURN_IN_GUARD.savedValue());
        assertEquals(BurnInWait.TEN_SECONDS, Settings.BURN_IN_WAIT.savedValue());

        JSONObject older = new JSONObject(file);
        older.getJSONObject("settings").remove(SettingsBackup.BURN_IN.key);
        older.getJSONObject("settings").remove(SettingsBackup.BURN_IN_WAIT.key);
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM_AND_SHIFT);
        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(older.toString());
        assertNull(snapshot.burnIn);
        assertNull(snapshot.burnInWait);
        assertNull(snapshot.burnInChange());
        SettingsBackup.apply(snapshot);
        assertEquals(BurnInMode.DIM_AND_SHIFT, Settings.BURN_IN_GUARD.savedValue());
        assertEquals(BurnInWait.TEN_SECONDS, Settings.BURN_IN_WAIT.savedValue());

        // A preview kept across a rebuild keeps both, and only ones this build offers come back.
        Bundle state = SettingsBackup.parse(file).toBundle();
        SettingsBackup.Snapshot kept = SettingsBackup.Snapshot.fromBundle(state);
        assertEquals(BurnInMode.DIM, kept.burnIn);
        assertEquals(BurnInWait.TEN_SECONDS, kept.burnInWait);
        state.putString("burn_in_guard", "DIM");
        state.putString("burn_in_wait", "TEN_SECONDS");
        kept = SettingsBackup.Snapshot.fromBundle(state);
        assertNull(kept.burnIn);
        assertNull(kept.burnInWait);
    }

    @Test
    public void theImportSentencesNameTheChoice() {
        assertEquals("Burn-in guard will be set to Dim when idle and shift.",
                SettingsBackupPreference.burnInSentence(BurnInMode.DIM_AND_SHIFT));
        assertEquals("Burn-in guard will be set to Off.", SettingsBackupPreference.burnInSentence(BurnInMode.OFF));
        assertEquals("Burn-in guard will dim the Reels screen as soon as you stop touching it.",
                SettingsBackupPreference.burnInWaitSentence(BurnInWait.AT_ONCE));
        assertEquals("Burn-in guard will dim the Reels screen after 10 seconds without a touch.",
                SettingsBackupPreference.burnInWaitSentence(BurnInWait.TEN_SECONDS));
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
