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

import app.morphe.extension.facebook.comments.CommentsHeight;
import app.morphe.extension.shared.SettingsContextRule;

/**
 * Open comments at (#179): a list under Comments and writing, Facebook's height to start, then full
 * or half height. It goes in a settings file as its file value and comes back only as one this build
 * offers. A file from before the list holds the old Open comments at full height switch instead:
 * true comes back as full height and false as Facebook's height, and the list's own key wins when a
 * file holds both.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class CommentsHeightSettingsTest {
    private static final String FACEBOOK_SUMMARY = "Comments open where Facebook stops them, part way up on reels and videos.";
    private static final String HALF_SUMMARY = "Comments open on a sheet that covers half the screen, and the reel or "
            + "video keeps playing above it. On reels and videos, Comment opens that sheet in place of the split view.";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.FULL_HEIGHT_COMMENTS);
    }

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        Settings.COMMENTS_HEIGHT.resetToDefault();
    }

    @Test
    public void theListOffersEachHeightAndSaysWhatItDoes() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            int at = indexOfKey(rows, Settings.COMMENTS_HEIGHT.key);
            assertTrue("Open comments at is missing", at >= 0);
            ValueRows.CommentsHeightRow row = (ValueRows.CommentsHeightRow) rows.get(at);
            assertEquals("Open comments at", String.valueOf(row.getTitle()));
            assertEquals(Arrays.asList("Facebook's height", "Full height", "Half height"), texts(row.getEntries()));
            assertEquals(Arrays.asList("FACEBOOK", "FULL", "HALF"), texts(row.getEntryValues()));
            assertEquals(CommentsHeight.FACEBOOK, Settings.COMMENTS_HEIGHT.get());
            assertEquals("FACEBOOK", row.getValue());
            assertEquals(FACEBOOK_SUMMARY, String.valueOf(row.getSummary()));

            row.setValue("HALF");
            ShadowLooper.idleMainLooper();
            assertEquals(CommentsHeight.HALF, Settings.COMMENTS_HEIGHT.savedValue());
            assertEquals(HALF_SUMMARY, String.valueOf(row.getSummary()));

            row.setValue("FULL");
            ShadowLooper.idleMainLooper();
            assertEquals(CommentsHeight.FULL, Settings.COMMENTS_HEIGHT.savedValue());
            assertTrue(String.valueOf(row.getSummary()).startsWith("Comments open on a sheet that fills the screen"));
        }
    }

    @Test
    public void withoutThePatchInTheBuildItDoesntShow() {
        PatchFamily.inBuildForTests = EnumSet.noneOf(PatchFamily.class);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            assertEquals(-1, indexOfKey(rowsOf(page(controller)), Settings.COMMENTS_HEIGHT.key));
        }
    }

    /** Each choice goes out as its file value and comes back as itself. */
    @Test
    public void eachChoiceRoundTripsThroughASettingsFile() throws Exception {
        for (CommentsHeight choice : CommentsHeight.values()) {
            Settings.COMMENTS_HEIGHT.save(choice);
            String file = SettingsBackup.create();
            assertEquals(choice.fileValue, new JSONObject(file).getJSONObject("settings").get(SettingsBackup.COMMENTS_HEIGHT.key));
            Settings.COMMENTS_HEIGHT.save(choice == CommentsHeight.HALF ? CommentsHeight.FULL : CommentsHeight.HALF);

            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
            assertEquals(choice, snapshot.commentsHeight);
            assertEquals(choice, snapshot.commentsHeightChange());
            assertEquals(Collections.singletonMap(SettingsBackup.COMMENTS_HEIGHT, choice), snapshot.changes());
            assertEquals(1, SettingsBackup.apply(snapshot));
            assertEquals(choice, Settings.COMMENTS_HEIGHT.savedValue());
            assertEquals("a file read back is the file", file, SettingsBackup.create());
        }
    }

    /** A value this build doesn't offer refuses the whole file, and a file holding neither key leaves it alone. */
    @Test
    public void onlyAChoiceThisBuildOffersComesBack() throws Exception {
        Settings.COMMENTS_HEIGHT.save(CommentsHeight.HALF);
        String file = SettingsBackup.create();
        for (Object refused : new Object[]{"HALF", "Half height", "quarter", "", 1, 0.5, true, JSONObject.NULL}) {
            refuses(file, SettingsBackup.COMMENTS_HEIGHT.key, refused);
        }
        assertEquals("a refused file wrote something", CommentsHeight.HALF, Settings.COMMENTS_HEIGHT.savedValue());

        JSONObject older = new JSONObject(file);
        older.getJSONObject("settings").remove(SettingsBackup.COMMENTS_HEIGHT.key);
        Settings.COMMENTS_HEIGHT.save(CommentsHeight.FULL);
        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(older.toString());
        assertNull(snapshot.commentsHeight);
        assertNull(snapshot.commentsHeightChange());
        SettingsBackup.apply(snapshot);
        assertEquals(CommentsHeight.FULL, Settings.COMMENTS_HEIGHT.savedValue());

        // A preview kept across a rebuild keeps it, and only one this build offers comes back.
        Bundle state = SettingsBackup.parse(file).toBundle();
        assertEquals(CommentsHeight.HALF, SettingsBackup.Snapshot.fromBundle(state).commentsHeight);
        state.putString("comments_height", "HALF");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).commentsHeight);
    }

    /** A backup from before the list: the old switch on is full height, off is Facebook's height. */
    @Test
    public void anOldBackupsSwitchComesBackAsItsHeight() throws Exception {
        for (boolean on : new boolean[]{true, false}) {
            Settings.COMMENTS_HEIGHT.save(CommentsHeight.HALF);
            JSONObject older = new JSONObject(SettingsBackup.create());
            older.getJSONObject("settings").remove(SettingsBackup.COMMENTS_HEIGHT.key);
            older.getJSONObject("settings").put(CommentsHeightSetting.LEGACY_KEY, on);

            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(older.toString());
            CommentsHeight expected = on ? CommentsHeight.FULL : CommentsHeight.FACEBOOK;
            assertEquals(expected, snapshot.commentsHeight);
            assertEquals(Collections.singletonMap(SettingsBackup.COMMENTS_HEIGHT, expected), snapshot.changes());
            assertEquals(1, SettingsBackup.apply(snapshot));
            assertEquals(expected, Settings.COMMENTS_HEIGHT.savedValue());
            assertEquals(expected.fileValue, new JSONObject(SettingsBackup.create()).getJSONObject("settings")
                    .get(SettingsBackup.COMMENTS_HEIGHT.key));
        }
    }

    @Test
    public void theListsOwnKeyWinsOverTheOldSwitchInOneFile() throws Exception {
        Settings.COMMENTS_HEIGHT.save(CommentsHeight.HALF);
        JSONObject both = new JSONObject(SettingsBackup.create());
        both.getJSONObject("settings").put(CommentsHeightSetting.LEGACY_KEY, true);
        Settings.COMMENTS_HEIGHT.save(CommentsHeight.FACEBOOK);

        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(both.toString());
        assertEquals(CommentsHeight.HALF, snapshot.commentsHeight);
        SettingsBackup.apply(snapshot);
        assertEquals(CommentsHeight.HALF, Settings.COMMENTS_HEIGHT.savedValue());
    }

    @Test
    public void anOldSwitchThatIsntTrueOrFalseRefusesTheFile() throws Exception {
        String file = SettingsBackup.create();
        for (Object refused : new Object[]{"true", 1, "full", JSONObject.NULL}) {
            refuses(file, CommentsHeightSetting.LEGACY_KEY, refused);
        }
    }

    @Test
    public void theImportSentencesNameTheChoice() {
        assertEquals("Comments will open at full height.",
                SettingsBackupPreference.commentsHeightSentence(CommentsHeight.FULL));
        assertEquals("Comments will open at half height.",
                SettingsBackupPreference.commentsHeightSentence(CommentsHeight.HALF));
        assertEquals("Comments will open where Facebook stops them.",
                SettingsBackupPreference.commentsHeightSentence(CommentsHeight.FACEBOOK));
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
