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
 * Long press to save Marketplace photos' row (#515): under Downloads, right after Search photo in
 * post menus, whenever Download any photo is in the build, and off to start. A build without that
 * patch has no row.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class ListingPhotoSettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        Settings.MARKETPLACE_PHOTO_SAVE.resetToDefault();
    }

    @Test
    public void theRowSitsUnderDownloadsAfterSearchPhotoAndStartsOff() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PHOTO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = page(controller);
            List<Preference> rows = new ArrayList<>();
            collect(page.getPreferenceScreen(), rows);
            int search = indexOfKey(rows, Settings.POST_MENU_PHOTO_SEARCH.key);
            int at = indexOfKey(rows, Settings.MARKETPLACE_PHOTO_SAVE.key);
            assertTrue("Long press to save Marketplace photos is missing", at >= 0);
            assertEquals("not right after Search photo in post menus", search + 1, at);
            SwitchPreference row = (SwitchPreference) rows.get(at);
            assertEquals("Long press to save Marketplace photos", String.valueOf(row.getTitle()));
            assertEquals("With Save any photo on, a long press on a photo you open from a Marketplace listing saves it.",
                    String.valueOf(row.getSummary()));
            assertEquals("Downloads", String.valueOf(holder(page.getPreferenceScreen(), row)));
            assertFalse(row.isChecked());
            assertFalse(Settings.MARKETPLACE_PHOTO_SAVE.get());
            assertTrue(PatchFamily.PHOTO_DOWNLOAD.switches.contains(Settings.MARKETPLACE_PHOTO_SAVE));
        }
    }

    @Test
    public void withoutDownloadAnyPhotoInTheBuildThereIsNoRow() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = new ArrayList<>();
            collect(page(controller).getPreferenceScreen(), rows);
            assertEquals(-1, indexOfKey(rows, Settings.MARKETPLACE_PHOTO_SAVE.key));
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
