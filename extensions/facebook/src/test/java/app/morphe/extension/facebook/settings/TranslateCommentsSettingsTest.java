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
 * Translate every comment's row (#522): under Comments and writing whenever Turn off auto-translation
 * is in the build, off to start, with Turn off auto-translation still on the News feed page. A build
 * without that patch has neither row.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class TranslateCommentsSettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        Settings.TRANSLATE_EVERY_COMMENT.resetToDefault();
    }

    @Test
    public void translateEveryCommentSitsUnderCommentsAndStartsOff() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.AUTO_TRANSLATION);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = page(controller);
            List<Preference> rows = new ArrayList<>();
            collect(page.getPreferenceScreen(), rows);
            assertTrue("Turn off auto-translation is missing", indexOfKey(rows, Settings.TURN_OFF_AUTO_TRANSLATION.key) >= 0);
            int at = indexOfKey(rows, Settings.TRANSLATE_EVERY_COMMENT.key);
            assertTrue("Translate every comment is missing", at >= 0);
            SwitchPreference row = (SwitchPreference) rows.get(at);
            assertEquals("Translate every comment", String.valueOf(row.getTitle()));
            assertEquals("Comments in another language show Facebook's translation as they load, as if you'd tapped "
                    + "See translation on each one.", String.valueOf(row.getSummary()));
            assertEquals("Comments and writing", String.valueOf(holder(page.getPreferenceScreen(), row)));
            assertFalse(row.isChecked());
            assertFalse(Settings.TRANSLATE_EVERY_COMMENT.get());
            assertTrue(PatchFamily.AUTO_TRANSLATION.switches.contains(Settings.TRANSLATE_EVERY_COMMENT));
        }
    }

    @Test
    public void withoutTurnOffAutoTranslationInTheBuildThereIsNoRow() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.FOLD_LONG_TEXT);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = new ArrayList<>();
            collect(page(controller).getPreferenceScreen(), rows);
            assertEquals(-1, indexOfKey(rows, Settings.TRANSLATE_EVERY_COMMENT.key));
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
