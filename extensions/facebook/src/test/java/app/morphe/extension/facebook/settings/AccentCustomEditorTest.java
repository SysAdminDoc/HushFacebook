/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import java.util.EnumSet;

import app.morphe.extension.facebook.theme.AccentColor;
import app.morphe.extension.facebook.theme.AccentColorForTests;
import app.morphe.extension.shared.SettingsContextRule;

/**
 * The Accent color row's Custom choice (#355): the sliders and the color code follow each other, Save
 * keeps what {@link AccentColor} reads, and picking Custom in the row's list opens the editor.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class AccentCustomEditorTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.ACCENT_COLOR);
        ShadowAlertDialog.reset();
    }

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        Settings.ACCENT_COLOR.resetToDefault();
        Settings.ACCENT_CUSTOM.resetToDefault();
    }

    private static HushfacebookPreferenceFragment page(ActivityController<Activity> controller) {
        HushfacebookPreferenceFragment page = new HushfacebookPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        return page;
    }

    private static AccentCustomEditor.Picker picker(Activity activity, int rgb) {
        return new AccentCustomEditor.Picker(activity, ScreenColors.current(), rgb);
    }

    @Test
    public void aSliderRewritesTheCode() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            AccentCustomEditor.Picker picker = picker(controller.get(), 0x0866FF);
            assertEquals("#0866FF", picker.code.getText().toString());
            picker.bars[0].setProgress(0x2E);
            assertEquals("#2E66FF", picker.code.getText().toString());
            picker.bars[2].setProgress(0);
            assertEquals("#2E6600", picker.code.getText().toString());
            assertEquals(0x2E6600, picker.rgb());
        }
    }

    @Test
    public void aTypedCodeMovesTheSlidersAndAHalfTypedOneLeavesThemAlone() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            AccentCustomEditor.Picker picker = picker(controller.get(), 0x0866FF);
            picker.code.setText("#2ecc71");
            assertEquals(0x2E, picker.bars[0].getProgress());
            assertEquals(0xCC, picker.bars[1].getProgress());
            assertEquals(0x71, picker.bars[2].getProgress());
            assertEquals("#2ECC71", picker.stored());
            // The field keeps what was typed while it's half a code, and the sliders stay put.
            picker.code.setText("#FF");
            assertEquals("#FF", picker.code.getText().toString());
            assertEquals(0x2ECC71, picker.rgb());
            assertNull(picker.stored());
        }
    }

    @Test
    public void saveKeepsTheColorTheAccentCodeReads() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = page(controller);
            AlertDialog dialog = AccentCustomEditor.show(page, controller.get());
            assertNotNull(dialog);
            AccentCustomEditor.Picker picker = pickerOf(dialog);
            picker.bars[0].setProgress(0xE5);
            picker.bars[1].setProgress(0x39);
            picker.bars[2].setProgress(0x35);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();

            assertEquals("#E53935", Settings.ACCENT_CUSTOM.savedValue());
            assertEquals(AccentColor.Preset.CUSTOM, Settings.ACCENT_COLOR.savedValue());
            assertEquals(Integer.valueOf(0xFFE53935), AccentColor.customColour());
            assertEquals(AccentColor.Preset.CUSTOM, AccentColorForTests.chosen());
            int themed = AccentColorForTests.accentFor(AccentColor.Preset.CUSTOM, 0xFF0866FF);
            assertTrue("Facebook's blue became the custom red", ((themed >> 16) & 0xFF) > (themed & 0xFF));
        }
    }

    @Test
    public void aCustomChoiceWithNoColorSavedKeepsFacebooksBlue() {
        Settings.ACCENT_COLOR.save(AccentColor.Preset.CUSTOM);
        assertNull(AccentColor.customColour());
        assertNull(AccentColorForTests.chosen());
    }

    @Test
    public void pickingCustomInTheListOpensTheEditor() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = page(controller);
            ValueRows.AccentRow row = (ValueRows.AccentRow) page.findPreference(Settings.ACCENT_COLOR.key);
            assertNotNull("the page has no Accent color row", row);
            CharSequence[] entries = row.getEntries();
            assertEquals("Custom color", String.valueOf(entries[entries.length - 1]));
            // Picking a preset only closes the list.
            ShadowAlertDialog.reset();
            closeListOn(row, 1);
            assertEquals("TEAL", row.getValue());
            assertNull("a preset opened the editor", ShadowAlertDialog.getLatestAlertDialog());
            // Picking Custom opens the editor.
            closeListOn(row, entries.length - 1);
            assertEquals("CUSTOM", row.getValue());
            AlertDialog editor = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(editor);
            assertTrue(editor.isShowing());
            assertNotNull(findPicker(editor.getWindow().getDecorView()));
        }
    }

    /** What the list does when the person taps entry [index]: it remembers the entry and closes with OK. */
    private static void closeListOn(ValueRows.AccentRow row, int index) {
        try {
            java.lang.reflect.Field clicked = android.preference.ListPreference.class
                    .getDeclaredField("mClickedDialogEntryIndex");
            clicked.setAccessible(true);
            clicked.setInt(row, index);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        row.onDialogClosed(true);
    }

    private static AccentCustomEditor.Picker pickerOf(AlertDialog dialog) {
        // The dialog's content is the picker's view; rebuild the Picker's handles from it.
        View content = dialog.getWindow().getDecorView();
        AccentCustomEditor.Picker found = findPicker(content);
        assertNotNull(found);
        return found;
    }

    private static AccentCustomEditor.Picker findPicker(View root) {
        Object tag = root.getTag();
        if (tag instanceof AccentCustomEditor.Picker) return (AccentCustomEditor.Picker) tag;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                AccentCustomEditor.Picker found = findPicker(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
