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

import app.morphe.extension.facebook.feed.FoldLines;
import app.morphe.extension.shared.SettingsContextRule;

/**
 * Fold long text's rows (#497): Fold long posts right under Show full post text on the News feed
 * page, Fold long comments under Comments, both at Facebook's choice to start. Only the posts' row
 * says Show full post text comes first, and only when that switch is in the build. Each choice goes
 * in a settings file as its file value and comes back only as one this build offers.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class FoldLongTextSettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final List<String> ENTRIES = Arrays.asList("Facebook's choice", "1 line", "2 lines", "3 lines", "4 lines",
            "5 lines", "6 lines", "8 lines", "10 lines", "15 lines", "20 lines");

    @Before
    public void start() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.FULL_POST_TEXT, PatchFamily.FOLD_LONG_TEXT);
    }

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        Settings.FOLD_POST_TEXT.resetToDefault();
        Settings.FOLD_COMMENTS.resetToDefault();
        Settings.FOLD_REEL_CAPTIONS.resetToDefault();
    }

    @Test
    public void foldLongPostsFollowsShowFullPostTextAndStartsAtFacebooksChoice() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            int full = indexOfKey(rows, Settings.SHOW_FULL_POST_TEXT.key);
            assertTrue("Show full post text is missing", full >= 0);
            assertEquals(full + 1, indexOfKey(rows, Settings.FOLD_POST_TEXT.key));
            ValueRows.FoldLinesRow row = (ValueRows.FoldLinesRow) rows.get(full + 1);
            assertEquals("Fold long posts", String.valueOf(row.getTitle()));
            assertEquals(ENTRIES, texts(row.getEntries()));
            List<String> values = new ArrayList<>();
            for (FoldLines choice : FoldLines.values()) values.add(choice.name());
            assertEquals(values, texts(row.getEntryValues()));
            assertEquals(FoldLines.FACEBOOK, Settings.FOLD_POST_TEXT.get());
            assertEquals("FACEBOOK", row.getValue());
            assertEquals("Long posts fold where Facebook folds them.", String.valueOf(row.getSummary()));

            row.setValue("THREE");
            ShadowLooper.idleMainLooper();
            assertEquals(FoldLines.THREE, Settings.FOLD_POST_TEXT.savedValue());
            assertEquals("A long post shows 3 lines before See more. Show full post text comes first while it's on.",
                    String.valueOf(row.getSummary()));
            row.setValue("ONE");
            assertEquals("A long post shows 1 line before See more. Show full post text comes first while it's on.",
                    String.valueOf(row.getSummary()));
        }
    }

    @Test
    public void foldLongCommentsSitsUnderCommentsAndSaysNothingOfShowFullPostText() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            int at = indexOfKey(rows, Settings.FOLD_COMMENTS.key);
            assertTrue("Fold long comments is missing", at >= 0);
            ValueRows.FoldLinesRow row = (ValueRows.FoldLinesRow) rows.get(at);
            assertEquals("Fold long comments", String.valueOf(row.getTitle()));
            assertEquals("Comments and writing", String.valueOf(categoryOf(page(controller), row)));
            assertEquals(ENTRIES, texts(row.getEntries()));
            assertEquals(FoldLines.FACEBOOK, Settings.FOLD_COMMENTS.get());
            assertEquals("Long comments fold where Facebook folds them.", String.valueOf(row.getSummary()));

            row.setValue("TEN");
            ShadowLooper.idleMainLooper();
            assertEquals(FoldLines.TEN, Settings.FOLD_COMMENTS.savedValue());
            assertEquals(FoldLines.FACEBOOK, Settings.FOLD_POST_TEXT.savedValue());
            assertEquals("A long comment shows 10 lines before See more.", String.valueOf(row.getSummary()));
        }
    }

    /** #506: reel captions got a list of their own, right under Show full reel captions, apart from the posts'. */
    @Test
    public void foldReelCaptionsFollowsShowFullReelCaptionsAndStartsAtFacebooksChoice() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            int full = indexOfKey(rows, Settings.SHOW_FULL_REEL_CAPTIONS.key);
            assertTrue("Show full reel captions is missing", full >= 0);
            assertEquals(full + 1, indexOfKey(rows, Settings.FOLD_REEL_CAPTIONS.key));
            ValueRows.FoldLinesRow row = (ValueRows.FoldLinesRow) rows.get(full + 1);
            assertEquals("Fold reel captions", String.valueOf(row.getTitle()));
            assertEquals("Reels and Watch", String.valueOf(categoryOf(page(controller), row)));
            assertEquals(ENTRIES, texts(row.getEntries()));
            assertEquals(FoldLines.FACEBOOK, Settings.FOLD_REEL_CAPTIONS.get());
            assertEquals("Long reel captions fold where Facebook folds them.", String.valueOf(row.getSummary()));

            row.setValue("FOUR");
            ShadowLooper.idleMainLooper();
            assertEquals(FoldLines.FOUR, Settings.FOLD_REEL_CAPTIONS.savedValue());
            assertEquals(FoldLines.FACEBOOK, Settings.FOLD_POST_TEXT.savedValue());
            assertEquals("A long reel caption shows 4 lines before more.", String.valueOf(row.getSummary()));
        }
        // Without Show full post text in the build, the list still shows.
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.FOLD_LONG_TEXT);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            assertTrue(indexOfKey(rowsOf(page(controller)), Settings.FOLD_REEL_CAPTIONS.key) >= 0);
        }
    }

    @Test
    public void withoutShowFullPostTextInTheBuildThePostsRowSaysNothingOfIt() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.FOLD_LONG_TEXT);
        Settings.FOLD_POST_TEXT.save(FoldLines.TWENTY);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            assertEquals(-1, indexOfKey(rows, Settings.SHOW_FULL_POST_TEXT.key));
            Preference row = rows.get(indexOfKey(rows, Settings.FOLD_POST_TEXT.key));
            assertEquals("A long post shows 20 lines before See more.", String.valueOf(row.getSummary()));
        }
    }

    @Test
    public void withoutTheFoldInTheBuildNeitherRowShows() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.FULL_POST_TEXT);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            assertTrue(indexOfKey(rows, Settings.SHOW_FULL_POST_TEXT.key) >= 0);
            assertEquals(-1, indexOfKey(rows, Settings.FOLD_POST_TEXT.key));
            assertEquals(-1, indexOfKey(rows, Settings.FOLD_COMMENTS.key));
            assertEquals(-1, indexOfKey(rows, Settings.FOLD_REEL_CAPTIONS.key));
        }
    }

    /** Each choice goes out as its file value and comes back as itself, the two apart. */
    @Test
    public void eachChoiceRoundTripsThroughASettingsFile() throws Exception {
        assertEquals("fold_post_text and fold_comments keep their keys", "hushfacebook_fold_post_text",
                SettingsBackup.FOLD_POSTS.key);
        assertEquals("hushfacebook_fold_comments", SettingsBackup.FOLD_COMMENTS.key);
        assertEquals("hushfacebook_fold_reel_captions", SettingsBackup.FOLD_REEL_CAPTIONS.key);
        List<FoldLines> choices = Arrays.asList(FoldLines.values());
        for (FoldLines choice : choices) {
            FoldLines other = choices.get((choices.indexOf(choice) + 3) % choices.size());
            FoldLines caption = choices.get((choices.indexOf(choice) + 5) % choices.size());
            Settings.FOLD_POST_TEXT.save(choice);
            Settings.FOLD_COMMENTS.save(other);
            Settings.FOLD_REEL_CAPTIONS.save(caption);
            String file = SettingsBackup.create();
            JSONObject settings = new JSONObject(file).getJSONObject("settings");
            assertEquals(choice.fileValue, settings.get(SettingsBackup.FOLD_POSTS.key));
            assertEquals(other.fileValue, settings.get(SettingsBackup.FOLD_COMMENTS.key));
            assertEquals(caption.fileValue, settings.get(SettingsBackup.FOLD_REEL_CAPTIONS.key));
            Settings.FOLD_POST_TEXT.save(choice == FoldLines.TWO ? FoldLines.FIVE : FoldLines.TWO);

            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
            assertEquals(choice, snapshot.foldPosts);
            assertEquals(other, snapshot.foldComments);
            assertEquals(caption, snapshot.foldReelCaptions);
            assertNull("the captions' choice is already the file's", snapshot.foldReelCaptionsChange());
            assertEquals(choice, snapshot.foldPostsChange());
            assertNull("the comments' choice is already the file's", snapshot.foldCommentsChange());
            assertEquals(Collections.singletonMap(SettingsBackup.FOLD_POSTS, choice), snapshot.changes());
            assertEquals(1, SettingsBackup.apply(snapshot));
            assertEquals(choice, Settings.FOLD_POST_TEXT.savedValue());
            assertEquals(other, Settings.FOLD_COMMENTS.savedValue());
            assertEquals(caption, Settings.FOLD_REEL_CAPTIONS.savedValue());
            assertEquals("a file read back is the file", file, SettingsBackup.create());
        }
    }

    /** A value this build doesn't offer refuses the whole file, and a file from before the fold leaves it alone. */
    @Test
    public void onlyAChoiceThisBuildOffersComesBack() throws Exception {
        Settings.FOLD_POST_TEXT.save(FoldLines.FOUR);
        Settings.FOLD_COMMENTS.save(FoldLines.SIX);
        String file = SettingsBackup.create();
        for (Object refused : new Object[]{"FOUR", "four", "7", "0", "", 4, true, JSONObject.NULL}) {
            refuses(file, SettingsBackup.FOLD_POSTS.key, refused);
            refuses(file, SettingsBackup.FOLD_COMMENTS.key, refused);
            refuses(file, SettingsBackup.FOLD_REEL_CAPTIONS.key, refused);
        }
        assertEquals("a refused file wrote something", FoldLines.FOUR, Settings.FOLD_POST_TEXT.savedValue());
        assertEquals(FoldLines.SIX, Settings.FOLD_COMMENTS.savedValue());

        JSONObject older = new JSONObject(file);
        older.getJSONObject("settings").remove(SettingsBackup.FOLD_POSTS.key);
        older.getJSONObject("settings").remove(SettingsBackup.FOLD_COMMENTS.key);
        older.getJSONObject("settings").remove(SettingsBackup.FOLD_REEL_CAPTIONS.key);
        Settings.FOLD_POST_TEXT.save(FoldLines.EIGHT);
        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(older.toString());
        assertNull(snapshot.foldPosts);
        assertNull(snapshot.foldComments);
        assertNull(snapshot.foldReelCaptions);
        assertNull(snapshot.foldPostsChange());
        SettingsBackup.apply(snapshot);
        assertEquals(FoldLines.EIGHT, Settings.FOLD_POST_TEXT.savedValue());
        assertEquals(FoldLines.SIX, Settings.FOLD_COMMENTS.savedValue());

        // A preview kept across a rebuild keeps them, and only one this build offers comes back.
        Bundle state = SettingsBackup.parse(file).toBundle();
        assertEquals(FoldLines.FOUR, SettingsBackup.Snapshot.fromBundle(state).foldPosts);
        assertEquals(FoldLines.SIX, SettingsBackup.Snapshot.fromBundle(state).foldComments);
        state.putString("fold_post_text", "FOUR");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).foldPosts);
    }

    @Test
    public void theImportSentencesNameTheChoice() {
        assertEquals("Fold long posts will fold posts where Facebook does.",
                SettingsBackupPreference.foldPostsSentence(FoldLines.FACEBOOK));
        assertEquals("Fold long posts will show 2 lines before See more.",
                SettingsBackupPreference.foldPostsSentence(FoldLines.TWO));
        assertEquals("Fold long comments will fold comments where Facebook does.",
                SettingsBackupPreference.foldCommentsSentence(FoldLines.FACEBOOK));
        assertEquals("Fold long comments will show 1 line before See more.",
                SettingsBackupPreference.foldCommentsSentence(FoldLines.ONE));
        assertEquals("Fold reel captions will fold captions where Facebook does.",
                SettingsBackupPreference.foldReelCaptionsSentence(FoldLines.FACEBOOK));
        assertEquals("Fold reel captions will show 3 lines before more.",
                SettingsBackupPreference.foldReelCaptionsSentence(FoldLines.THREE));
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
        HushfacebookPreferenceFragment page = (HushfacebookPreferenceFragment)
                controller.get().getFragmentManager().findFragmentById(android.R.id.content);
        if (page != null) return page;
        page = new HushfacebookPreferenceFragment();
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

    /** The title of the group directly holding [row]. */
    private static CharSequence categoryOf(HushfacebookPreferenceFragment page, Preference row) {
        return holder(page.getPreferenceScreen(), row);
    }

    private static CharSequence holder(PreferenceGroup group, Preference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference child = group.getPreference(i);
            if (child == row) return group.getTitle();
            if (child instanceof PreferenceGroup) {
                CharSequence found = holder((PreferenceGroup) child, row);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static int indexOfKey(List<Preference> rows, String key) {
        for (int i = 0; i < rows.size(); i++) {
            if (key.equals(rows.get(i).getKey())) return i;
        }
        return -1;
    }
}
