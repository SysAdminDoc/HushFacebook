/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import app.morphe.extension.shared.SettingsContextRule;

/**
 * Save files to Downloads' row (#590): under Downloads whenever its patch is in, and on to start.
 * A build with it and no media download shows only that row there, since the quality, folder and
 * name rows shape a video or photo save. A build without the patch has no row.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class FileDownloadsSettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String SUMMARY = "A PDF, document or other file you download from a post goes to your phone's "
            + "Downloads folder, where a file manager can open it. Off, Facebook keeps it where no file manager can reach it.";

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        Settings.SAVE_FILES_TO_DOWNLOADS.resetToDefault();
    }

    @Test
    public void aloneTheRowIsAllDownloadsShowsAndStartsOn() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.FILE_DOWNLOADS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = page(controller);
            List<Preference> rows = new ArrayList<>();
            collect(page.getPreferenceScreen(), rows);
            int at = indexOfKey(rows, Settings.SAVE_FILES_TO_DOWNLOADS.key);
            assertTrue("Save files to Downloads is missing", at >= 0);
            SwitchPreference row = (SwitchPreference) rows.get(at);
            assertEquals("Save files to Downloads", String.valueOf(row.getTitle()));
            assertEquals(SUMMARY, String.valueOf(row.getSummary()));
            assertEquals("Downloads", String.valueOf(holder(page.getPreferenceScreen(), row)));
            assertTrue(row.isChecked());
            assertTrue(Settings.SAVE_FILES_TO_DOWNLOADS.get());
            for (String media : new String[]{Settings.ASK_BEFORE_DOWNLOAD.key, Settings.DOWNLOAD_QUALITY.key,
                    Settings.SAVE_FOLDER.key, Settings.DOWNLOAD_COMPATIBLE.key}) {
                assertEquals(media + " shows with no media download in", -1, indexOfKey(rows, media));
            }
            assertTrue(PatchFamily.FILE_DOWNLOADS.switches.contains(Settings.SAVE_FILES_TO_DOWNLOADS));
            assertFalse("a file download isn't a media save", PatchFamily.DOWNLOADS.contains(PatchFamily.FILE_DOWNLOADS));
        }
    }

    @Test
    public void withAMediaDownloadItComesAfterTheirSwitchesAndBeforeTheSharedRows() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PHOTO_DOWNLOAD, PatchFamily.FILE_DOWNLOADS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = page(controller);
            List<Preference> rows = new ArrayList<>();
            collect(page.getPreferenceScreen(), rows);
            int at = indexOfKey(rows, Settings.SAVE_FILES_TO_DOWNLOADS.key);
            assertEquals("not right after Long press to save Marketplace photos",
                    indexOfKey(rows, Settings.MARKETPLACE_PHOTO_SAVE.key) + 1, at);
            assertEquals("the shared rows don't follow it", at + 1, indexOfKey(rows, Settings.ASK_BEFORE_DOWNLOAD.key));
            assertEquals("Downloads", String.valueOf(holder(page.getPreferenceScreen(), rows.get(at))));
        }
    }

    @Test
    public void withoutThePatchInTheBuildThereIsNoRow() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PHOTO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = new ArrayList<>();
            collect(page(controller).getPreferenceScreen(), rows);
            assertEquals(-1, indexOfKey(rows, Settings.SAVE_FILES_TO_DOWNLOADS.key));
        }
    }

    private static HushfacebookPreferenceFragment page(ActivityController<Activity> controller) {
        HushfacebookPreferenceFragment page = new HushfacebookPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        return page;
    }

    private static void collect(PreferenceGroup group, List<Preference> rows) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference row = group.getPreference(i);
            if (row instanceof PreferenceGroup) collect((PreferenceGroup) row, rows);
            else rows.add(row);
        }
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
