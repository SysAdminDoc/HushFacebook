/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceGroup;

import org.junit.After;
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
import java.util.EnumSet;
import java.util.List;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;

/**
 * Header text font (#532): a list right under Home header text that picks the font the words draw
 * in, each choice saying what it looks like. Your font is offered only when the build can pick a
 * font file, and a change brings up the restart row.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class HeaderFontRowTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        Settings.HEADER_TEXT_FONT.resetToDefault();
    }

    @Test
    public void theListFollowsTheHeaderTextAndSaysWhatEachFontLooksLike() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HEADER_TEXT, PatchFamily.SYSTEM_FONT);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = page(controller);
            List<Preference> rows = rowsOf(page);
            int text = indexOfKey(rows, Settings.HEADER_TEXT.key);
            assertTrue("Home header text is missing", text >= 0);
            assertEquals("right under Home header text", text + 1, indexOfKey(rows, Settings.HEADER_TEXT_FONT.key));
            Preference words = rows.get(text);
            Preference found = rows.get(text + 1);
            assertTrue(found instanceof ValueRows.HeaderFontRow);
            ValueRows.HeaderFontRow row = (ValueRows.HeaderFontRow) found;
            assertEquals("in the same section", words.getParent(), row.getParent());
            assertEquals("Header text font", String.valueOf(row.getTitle()));
            assertEquals("Header text font", String.valueOf(row.getDialogTitle()));
            assertEquals(Arrays.asList("Bold", "Regular", "Serif", "Script", "Monospace", "Your font"),
                    texts(row.getEntries()));
            assertEquals(Arrays.asList("BOLD", "REGULAR", "SERIF", "SCRIPT", "MONOSPACE", "YOUR_FONT"),
                    texts(row.getEntryValues()));
            assertEquals(HeaderFont.BOLD, Settings.HEADER_TEXT_FONT.get());
            assertEquals("BOLD", row.getValue());
            assertEquals("The bold font the words have always had.", String.valueOf(row.getSummary()));

            row.setValue("SCRIPT");
            ShadowLooper.idleMainLooper();
            assertEquals(HeaderFont.SCRIPT, Settings.HEADER_TEXT_FONT.savedValue());
            assertEquals("Joined-up handwriting.", String.valueOf(row.getSummary()));
            assertTrue("Facebook draws the header once, so the restart row comes up",
                    AbstractPreferenceFragment.restartPending.contains(Settings.HEADER_TEXT_FONT.key));

            row.setValue("YOUR_FONT");
            ShadowLooper.idleMainLooper();
            assertEquals(HeaderFont.YOUR_FONT, Settings.HEADER_TEXT_FONT.savedValue());
            assertEquals("The font file picked under Font file, in bold. Bold until one is picked.",
                    String.valueOf(row.getSummary()));
        }
    }

    @Test
    public void withoutTheFontFilePatchYourFontIsntOffered() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HEADER_TEXT);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            int font = indexOfKey(rows, Settings.HEADER_TEXT_FONT.key);
            assertTrue("Header text font is missing", font >= 0);
            ValueRows.HeaderFontRow row = (ValueRows.HeaderFontRow) rows.get(font);
            assertEquals(Arrays.asList("Bold", "Regular", "Serif", "Script", "Monospace"), texts(row.getEntries()));
        }
    }

    @Test
    public void withoutTheHeaderTextPatchItDoesntShow() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.SYSTEM_FONT);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            assertEquals(-1, indexOfKey(rowsOf(page(controller)), Settings.HEADER_TEXT_FONT.key));
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
