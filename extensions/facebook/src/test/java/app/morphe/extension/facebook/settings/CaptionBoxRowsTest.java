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
import org.junit.Before;
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
 * Dark box behind folded captions (#472) sits right under Dark box behind open captions on Reels and
 * Watch, off to start, and shows only in a build with the caption hook, as that one does.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class CaptionBoxRowsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.FULL_POST_TEXT);
    }

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        Settings.DIM_FOLDED_REEL_CAPTIONS.resetToDefault();
    }

    @Test
    public void theFoldedCaptionsRowFollowsTheOpenOnesAndStartsOff() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            int open = indexOfKey(rows, Settings.DIM_OPEN_REEL_CAPTIONS.key);
            int folded = indexOfKey(rows, Settings.DIM_FOLDED_REEL_CAPTIONS.key);
            assertTrue("Dark box behind open captions is missing", open >= 0);
            assertEquals(open + 1, folded);
            SwitchPreference row = (SwitchPreference) rows.get(folded);
            assertEquals("Dark box behind folded captions", String.valueOf(row.getTitle()));
            assertEquals("A folded reel caption, the short one with more at the end, sits on the same dark box. Turn on "
                    + "both to box every caption. A change shows from the next reel.", String.valueOf(row.getSummary()));
            assertFalse(row.isChecked());
            assertFalse(Settings.DIM_FOLDED_REEL_CAPTIONS.defaultValue);
        }
    }

    @Test
    public void withoutTheCaptionHookNeitherShows() {
        PatchFamily.inBuildForTests = EnumSet.noneOf(PatchFamily.class);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            assertEquals(-1, indexOfKey(rows, Settings.DIM_OPEN_REEL_CAPTIONS.key));
            assertEquals(-1, indexOfKey(rows, Settings.DIM_FOLDED_REEL_CAPTIONS.key));
        }
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
