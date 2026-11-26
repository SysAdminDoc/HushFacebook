/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceGroup;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.BaseSettings;

/**
 * #587: with debug logging on, opening the Hush settings said "Preference key has no setting" for
 * the last chat's background row, as an error in the report and a toast. That row saves into the
 * chat's own line, not a setting, so it isn't a typo, and no row of any patch says it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class RowsWithoutSettingTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        BaseSettings.DEBUG.save(true);
        ShadowLog.clear();
    }

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        BaseSettings.DEBUG.resetToDefault();
        ShadowLog.clear();
    }

    @Test
    public void noRowOfAnyPatchReportsAMissingSetting() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = new HushfacebookPreferenceFragment();
            controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
            List<Preference> rows = new ArrayList<>();
            collect(page.getPreferenceScreen(), rows);
            Preference lastChat = null;
            for (Preference row : rows) {
                if (HushfacebookPreferenceFragment.LAST_CHAT_BACKGROUND_ROW.equals(row.getKey())) lastChat = row;
            }
            assertTrue("the last chat's background row is missing", lastChat instanceof EditTextPreference);
            assertFalse(lastChat.isPersistent());
            List<String> reported = new ArrayList<>();
            for (ShadowLog.LogItem line : ShadowLog.getLogs()) {
                if (line.msg != null && line.msg.contains("Preference key has no setting")) reported.add(line.msg);
            }
            assertEquals("rows reported without a setting", List.of(), reported);
        }
    }

    private static void collect(PreferenceGroup group, List<Preference> rows) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference row = group.getPreference(i);
            if (row instanceof PreferenceGroup) collect((PreferenceGroup) row, rows);
            else rows.add(row);
        }
    }
}
