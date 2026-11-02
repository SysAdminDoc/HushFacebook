/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;

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
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import app.morphe.extension.shared.SettingsContextRule;

/**
 * The Switches that are on row (#353): the list holds exactly the switches that are on, each with its
 * working switch, and turning one off there stores it off for the page's own row too.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class FavoriteSwitchesScreenTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        ShadowAlertDialog.reset();
    }

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        Settings.HIDE_SUGGESTED_GROUPS.resetToDefault();
        Settings.HIDE_PROMOTED_POSTS.resetToDefault();
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

    private static Preference rowWithKey(HushfacebookPreferenceFragment page, String key) {
        List<Preference> rows = new ArrayList<>();
        collect(page.getPreferenceScreen(), rows);
        for (Preference row : rows) if (key.equals(row.getKey())) return row;
        return null;
    }

    /** The keys of the switches the page shows turned on. */
    private static Set<String> onOnThePage(HushfacebookPreferenceFragment page) {
        List<Preference> rows = new ArrayList<>();
        collect(page.getPreferenceScreen(), rows);
        Set<String> on = new TreeSet<>();
        for (Preference row : rows) {
            if (row instanceof SwitchPreference && ((SwitchPreference) row).isChecked()) on.add(row.getKey());
        }
        return on;
    }

    private static AlertDialog open(HushfacebookPreferenceFragment page) {
        Preference row = rowWithKey(page, FavoriteSwitchesScreen.ROW);
        assertNotNull("the page has no Switches that are on row", row);
        ShadowAlertDialog.reset();
        row.getOnPreferenceClickListener().onPreferenceClick(row);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("the row opened nothing", dialog);
        assertTrue(dialog.isShowing());
        return dialog;
    }

    private static ListView findList(View root) {
        if (root instanceof ListView && ((ListView) root).getAdapter() != null
                && ((ListView) root).getAdapter().getCount() > 0
                && ((ListView) root).getAdapter().getItem(0) instanceof SwitchPreference) return (ListView) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                ListView found = findList(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static ListView listOf(AlertDialog dialog) {
        ListView list = findList(dialog.getWindow().getDecorView());
        assertNotNull("the dialog holds no list of switches", list);
        return list;
    }

    private static Set<String> keysIn(ListView list) {
        Set<String> keys = new TreeSet<>();
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            keys.add(((Preference) list.getAdapter().getItem(i)).getKey());
        }
        return keys;
    }

    @Test
    public void theRowSitsRightUnderTheJumpRow() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = page(controller);
            Preference favorites = rowWithKey(page, FavoriteSwitchesScreen.ROW);
            assertNotNull(favorites);
            assertEquals("Switches that are on", String.valueOf(favorites.getTitle()));
            Preference before = page.getPreferenceScreen().getPreference(1);
            assertEquals("action_jump_to_section", before.getKey());
            assertEquals(FavoriteSwitchesScreen.ROW, page.getPreferenceScreen().getPreference(2).getKey());
        }
    }

    @Test
    public void theListHoldsExactlyTheSwitchesThatAreOn() {
        Settings.HIDE_SUGGESTED_GROUPS.save(true);
        Settings.HIDE_PROMOTED_POSTS.save(false);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = page(controller);
            Set<String> onPage = onOnThePage(page);
            assertTrue(onPage.contains(Settings.HIDE_SUGGESTED_GROUPS.key));
            assertFalse(onPage.contains(Settings.HIDE_PROMOTED_POSTS.key));

            ListView list = listOf(open(page));
            assertEquals(onPage, keysIn(list));
            for (int i = 0; i < list.getAdapter().getCount(); i++) {
                SwitchPreference row = (SwitchPreference) list.getAdapter().getItem(i);
                assertTrue(row.getKey() + " is listed but off", row.isChecked());
                assertNotNull(list.getAdapter().getView(i, null, list));
            }
        }
    }

    @Test
    public void turningOneOffThereStoresItOffAndTheRowFollows() {
        Settings.HIDE_SUGGESTED_GROUPS.save(true);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = page(controller);
            ListView list = listOf(open(page));
            int position = -1;
            for (int i = 0; i < list.getAdapter().getCount(); i++) {
                if (Settings.HIDE_SUGGESTED_GROUPS.key.equals(((Preference) list.getAdapter().getItem(i)).getKey())) {
                    position = i;
                }
            }
            assertTrue("the switch is on and isn't listed", position >= 0);
            list.performItemClick(null, position, 0);
            ShadowLooper.idleMainLooper();

            assertFalse(Settings.HIDE_SUGGESTED_GROUPS.savedValue());
            assertFalse(((SwitchPreference) list.getAdapter().getItem(position)).isChecked());
            assertFalse("the page's row stayed on",
                    ((SwitchPreference) rowWithKey(page, Settings.HIDE_SUGGESTED_GROUPS.key)).isChecked());
            // Open again: it's off, so it's no longer listed.
            assertFalse(keysIn(listOf(open(page))).contains(Settings.HIDE_SUGGESTED_GROUPS.key));
        }
    }
}
