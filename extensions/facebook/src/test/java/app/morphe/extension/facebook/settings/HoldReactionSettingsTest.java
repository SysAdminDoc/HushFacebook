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

import app.morphe.extension.facebook.reels.HoldReaction;
import app.morphe.extension.shared.SettingsContextRule;

/**
 * Hold a post to react's rows on the feed page (#454): the switch, off to start, right under Select
 * part of a post's text, and its Reaction list under it, Pick one each time to start. The list goes
 * in a settings file as its file value and comes back only as one this build offers.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class HoldReactionSettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_GESTURES);
    }

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        Settings.HOLD_POST_TO_REACT.resetToDefault();
        Settings.HOLD_REACTION.resetToDefault();
    }

    @Test
    public void theSwitchAndItsListFollowSelectPartOfAPostsTextAndStartOff() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            int select = indexOfKey(rows, Settings.SELECT_POST_TEXT.key);
            int hold = indexOfKey(rows, Settings.HOLD_POST_TO_REACT.key);
            assertTrue("Select part of a post's text is missing", select >= 0);
            assertEquals(select + 1, hold);
            assertEquals(hold + 1, indexOfKey(rows, Settings.HOLD_REACTION.key));

            SwitchPreference toggle = (SwitchPreference) rows.get(hold);
            assertEquals("Hold a post to react", String.valueOf(toggle.getTitle()));
            assertEquals("Holding a post's photo or video opens its reactions, as holding Like does. On a feed post it "
                    + "comes before Long press opens comments.", String.valueOf(toggle.getSummary()));
            assertFalse(toggle.isChecked());
            assertFalse(Settings.HOLD_POST_TO_REACT.defaultValue);
            assertTrue(rows.get(hold + 1) instanceof ValueRows.HoldReactionRow);
        }
    }

    @Test
    public void withoutTheGesturesInTheBuildNeitherShows() {
        PatchFamily.inBuildForTests = EnumSet.noneOf(PatchFamily.class);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            assertEquals(-1, indexOfKey(rows, Settings.HOLD_POST_TO_REACT.key));
            assertEquals(-1, indexOfKey(rows, Settings.HOLD_REACTION.key));
        }
    }

    @Test
    public void theListOffersTheTrayAndEachReactionInFacebooksOrder() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            ValueRows.HoldReactionRow row = (ValueRows.HoldReactionRow) rows.get(indexOfKey(rows, Settings.HOLD_REACTION.key));
            assertEquals("Reaction", String.valueOf(row.getTitle()));
            assertEquals(Arrays.asList("Pick one each time", "Like", "Love", "Care", "Haha", "Wow", "Sad", "Angry"),
                    texts(row.getEntries()));
            assertEquals(Arrays.asList("PICK", "LIKE", "LOVE", "CARE", "HAHA", "WOW", "SAD", "ANGRY"),
                    texts(row.getEntryValues()));
            assertEquals(HoldReaction.PICK, Settings.HOLD_REACTION.get());
            assertEquals("PICK", row.getValue());
            assertEquals("Pick one each time. The reactions open over the post, so slide to one and let go, or let go "
                    + "and tap one.", String.valueOf(row.getSummary()));

            row.setValue("HAHA");
            ShadowLooper.idleMainLooper();
            assertEquals(HoldReaction.HAHA, Settings.HOLD_REACTION.savedValue());
            assertEquals("Haha. Holding a post gives it straight away.", String.valueOf(row.getSummary()));
        }
    }

    /** Each choice goes out as its file value and comes back as itself, along with the switch. */
    @Test
    public void bothRoundTripThroughASettingsFile() throws Exception {
        Settings.HOLD_POST_TO_REACT.save(true);
        for (HoldReaction choice : HoldReaction.values()) {
            Settings.HOLD_REACTION.save(choice);
            String file = SettingsBackup.create();
            JSONObject settings = new JSONObject(file).getJSONObject("settings");
            assertEquals(choice.fileValue, settings.get(SettingsBackup.HOLD_REACTION.key));
            assertEquals(true, settings.get(Settings.HOLD_POST_TO_REACT.key));
            Settings.HOLD_REACTION.save(choice == HoldReaction.LOVE ? HoldReaction.SAD : HoldReaction.LOVE);

            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
            assertEquals(choice, snapshot.holdReaction);
            assertEquals(choice, snapshot.holdReactionChange());
            assertEquals(Collections.singletonMap(SettingsBackup.HOLD_REACTION, choice), snapshot.changes());
            assertEquals(1, SettingsBackup.apply(snapshot));
            assertEquals(choice, Settings.HOLD_REACTION.savedValue());
            assertEquals("a file read back is the file", file, SettingsBackup.create());
        }
    }

    /** A value this build doesn't offer refuses the whole file, and a file from before the list leaves it alone. */
    @Test
    public void onlyAChoiceThisBuildOffersComesBack() throws Exception {
        Settings.HOLD_REACTION.save(HoldReaction.WOW);
        String file = SettingsBackup.create();
        for (Object refused : new Object[]{"WOW", "Wow", "thankful", "", 4, true, JSONObject.NULL}) {
            refuses(file, SettingsBackup.HOLD_REACTION.key, refused);
        }
        assertEquals("a refused file wrote something", HoldReaction.WOW, Settings.HOLD_REACTION.savedValue());

        JSONObject older = new JSONObject(file);
        older.getJSONObject("settings").remove(SettingsBackup.HOLD_REACTION.key);
        Settings.HOLD_REACTION.save(HoldReaction.ANGRY);
        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(older.toString());
        assertNull(snapshot.holdReaction);
        assertNull(snapshot.holdReactionChange());
        SettingsBackup.apply(snapshot);
        assertEquals(HoldReaction.ANGRY, Settings.HOLD_REACTION.savedValue());

        // A preview kept across a rebuild keeps it, and only one this build offers comes back.
        Bundle state = SettingsBackup.parse(file).toBundle();
        assertEquals(HoldReaction.WOW, SettingsBackup.Snapshot.fromBundle(state).holdReaction);
        state.putString("hold_reaction", "WOW");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).holdReaction);
    }

    @Test
    public void theImportSentencesNameTheChoice() {
        assertEquals("Hold a post to react will open the reactions to pick from.",
                SettingsBackupPreference.holdReactionSentence(HoldReaction.PICK));
        assertEquals("Hold a post to react will give Care.", SettingsBackupPreference.holdReactionSentence(HoldReaction.CARE));
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
