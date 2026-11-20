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

import app.morphe.extension.facebook.media.BufferSize;
import app.morphe.extension.shared.SettingsContextRule;

/**
 * Video buffer's row (#526): a list under the Video quality heading of Video and audio, after the
 * quality rows, at Facebook's choice to start, with a summary that says what each size does. The
 * choice goes in a settings file as its file value and comes back only as one this build offers.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class VideoBufferSettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PLAYBACK_QUALITY, PatchFamily.VIDEO_BUFFER,
                PatchFamily.HDR_BRIGHTNESS);
    }

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        Settings.VIDEO_BUFFER.resetToDefault();
    }

    @Test
    public void theRowFollowsTheQualityRowsAndStartsAtFacebooksChoice() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = page(controller);
            List<Preference> rows = rowsOf(page);
            int stories = indexOfKey(rows, Settings.STORIES_PLAYBACK_QUALITY.key);
            assertTrue("Stories quality is missing", stories >= 0);
            assertEquals(stories + 1, indexOfKey(rows, Settings.VIDEO_BUFFER.key));
            assertEquals("HDR brightness comes after the buffer", stories + 2,
                    indexOfKey(rows, Settings.TURN_OFF_HDR_BRIGHTNESS.key));
            ValueRows.VideoBufferRow row = (ValueRows.VideoBufferRow) rows.get(stories + 1);
            assertEquals("Video and audio", String.valueOf(categoryOf(page, row)));
            assertEquals("Video buffer", String.valueOf(row.getTitle()));
            assertEquals(Arrays.asList("Facebook's choice", "Low", "Medium", "Maximum"), texts(row.getEntries()));
            assertEquals(Arrays.asList("FACEBOOK", "LOW", "MEDIUM", "MAXIMUM"), texts(row.getEntryValues()));
            assertEquals(BufferSize.FACEBOOK, Settings.VIDEO_BUFFER.get());
            assertEquals("FACEBOOK", row.getValue());
            assertEquals("Facebook decides how much of a video loads before and while it plays.",
                    String.valueOf(row.getSummary()));

            row.setValue("LOW");
            ShadowLooper.idleMainLooper();
            assertEquals(BufferSize.LOW, Settings.VIDEO_BUFFER.savedValue());
            assertEquals("Videos start once half a second has loaded, and load less ahead.",
                    String.valueOf(row.getSummary()));
            row.setValue("MEDIUM");
            ShadowLooper.idleMainLooper();
            assertEquals(BufferSize.MEDIUM, Settings.VIDEO_BUFFER.savedValue());
            assertEquals("Videos wait for 1.5 seconds to load before they start, and load more ahead.",
                    String.valueOf(row.getSummary()));
            row.setValue("MAXIMUM");
            ShadowLooper.idleMainLooper();
            assertEquals(BufferSize.MAXIMUM, Settings.VIDEO_BUFFER.savedValue());
            assertEquals("Videos wait for 3 seconds to load before they start, and load up to a minute ahead.",
                    String.valueOf(row.getSummary()));
        }
    }

    @Test
    public void aSavedSizeShowsWhenThePageOpens() {
        Settings.VIDEO_BUFFER.save(BufferSize.MAXIMUM);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            Preference row = rows.get(indexOfKey(rows, Settings.VIDEO_BUFFER.key));
            assertEquals("MAXIMUM", ((ValueRows.VideoBufferRow) row).getValue());
            assertEquals("Videos wait for 3 seconds to load before they start, and load up to a minute ahead.",
                    String.valueOf(row.getSummary()));
        }
    }

    @Test
    public void aloneInTheBuildTheRowStillShowsUnderVideoQuality() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_BUFFER);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = page(controller);
            List<Preference> rows = rowsOf(page);
            int at = indexOfKey(rows, Settings.VIDEO_BUFFER.key);
            assertTrue("Video buffer is missing", at >= 0);
            assertEquals("Video and audio", String.valueOf(categoryOf(page, rows.get(at))));
            assertTrue(rows.get(at - 1) instanceof SettingsRows.Subheading);
            assertEquals("Video quality", String.valueOf(rows.get(at - 1).getTitle()));
        }
    }

    @Test
    public void withoutThePatchInTheBuildTheRowDoesntShow() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PLAYBACK_QUALITY);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            assertTrue(indexOfKey(rows, Settings.PLAYBACK_QUALITY.key) >= 0);
            assertEquals(-1, indexOfKey(rows, Settings.VIDEO_BUFFER.key));
        }
    }

    /** Each choice goes out as its file value and comes back as itself. */
    @Test
    public void eachChoiceRoundTripsThroughASettingsFile() throws Exception {
        assertEquals("video_buffer keeps its key", "hushfacebook_video_buffer", SettingsBackup.VIDEO_BUFFER.key);
        List<BufferSize> choices = Arrays.asList(BufferSize.values());
        for (BufferSize choice : choices) {
            Settings.VIDEO_BUFFER.save(choice);
            String file = SettingsBackup.create();
            JSONObject settings = new JSONObject(file).getJSONObject("settings");
            assertEquals(choice.fileValue, settings.get(SettingsBackup.VIDEO_BUFFER.key));
            BufferSize other = choices.get((choices.indexOf(choice) + 1) % choices.size());
            Settings.VIDEO_BUFFER.save(other);

            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
            assertEquals(choice, snapshot.videoBuffer);
            assertEquals(choice, snapshot.videoBufferChange());
            assertEquals(Collections.singletonMap(SettingsBackup.VIDEO_BUFFER, choice), snapshot.changes());
            assertEquals(1, SettingsBackup.apply(snapshot));
            assertEquals(choice, Settings.VIDEO_BUFFER.savedValue());
            assertNull("the choice is already the file's", SettingsBackup.parse(file).videoBufferChange());
            assertEquals("a file read back is the file", file, SettingsBackup.create());
        }
    }

    /** A value this build doesn't offer refuses the whole file, and a file from before the list leaves it alone. */
    @Test
    public void onlyAChoiceThisBuildOffersComesBack() throws Exception {
        Settings.VIDEO_BUFFER.save(BufferSize.MEDIUM);
        String file = SettingsBackup.create();
        for (Object refused : new Object[]{"MEDIUM", "Medium", "high", "huge", "0", "", 2, true, JSONObject.NULL}) {
            JSONObject hostile = new JSONObject(file);
            hostile.getJSONObject("settings").put(SettingsBackup.VIDEO_BUFFER.key, refused);
            try {
                SettingsBackup.parse(hostile.toString());
                fail("a file with video_buffer = " + refused + " was read");
            } catch (SettingsBackup.Rejected rejected) {
                assertEquals("video_buffer = " + refused, SettingsBackup.Reason.VALUE, rejected.reason);
            }
        }
        assertEquals("a refused file wrote something", BufferSize.MEDIUM, Settings.VIDEO_BUFFER.savedValue());

        JSONObject older = new JSONObject(file);
        older.getJSONObject("settings").remove(SettingsBackup.VIDEO_BUFFER.key);
        Settings.VIDEO_BUFFER.save(BufferSize.LOW);
        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(older.toString());
        assertNull(snapshot.videoBuffer);
        assertNull(snapshot.videoBufferChange());
        SettingsBackup.apply(snapshot);
        assertEquals(BufferSize.LOW, Settings.VIDEO_BUFFER.savedValue());

        // A preview kept across a rebuild keeps it, and only one this build offers comes back.
        Bundle state = SettingsBackup.parse(file).toBundle();
        assertEquals(BufferSize.MEDIUM, SettingsBackup.Snapshot.fromBundle(state).videoBuffer);
        state.putString("video_buffer", "MEDIUM");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).videoBuffer);
    }

    @Test
    public void theImportSentencesNameTheChoice() {
        assertEquals("Facebook will decide how much of a video loads.",
                SettingsBackupPreference.videoBufferSentence(BufferSize.FACEBOOK));
        assertEquals("Video buffer will be set to Low.", SettingsBackupPreference.videoBufferSentence(BufferSize.LOW));
        assertEquals("Video buffer will be set to Medium.", SettingsBackupPreference.videoBufferSentence(BufferSize.MEDIUM));
        assertEquals("Video buffer will be set to Maximum.",
                SettingsBackupPreference.videoBufferSentence(BufferSize.MAXIMUM));
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
