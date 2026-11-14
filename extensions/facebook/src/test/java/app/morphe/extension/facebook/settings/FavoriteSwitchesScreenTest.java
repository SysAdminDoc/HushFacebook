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
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListAdapter;
import android.widget.ListView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.BooleanSetting;

/**
 * The Switches that are on row (#353): the list holds exactly the switches that are on, each with its
 * working switch, and turning one off there stores it off for the page's own row too. A switch turned
 * off there keeps its place while the list is open, then waits under Turned off lately, newest first
 * and at most twenty, until it's on again (#470).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class FavoriteSwitchesScreenTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Settings a test turned off through the list, put back afterwards. */
    private final List<BooleanSetting> touched = new ArrayList<>();

    @Before
    public void start() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        ShadowAlertDialog.reset();
        store().edit().clear().commit();
    }

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        Settings.HIDE_SUGGESTED_GROUPS.resetToDefault();
        Settings.HIDE_PROMOTED_POSTS.resetToDefault();
        for (BooleanSetting setting : touched) setting.resetToDefault();
        store().edit().clear().commit();
    }

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }

    private static SharedPreferences store() {
        return context().getSharedPreferences(FavoriteSwitchesScreen.OFF_STORE, Context.MODE_PRIVATE);
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
                && ((ListView) root).getAdapter().getItem(0) instanceof Preference) return (ListView) root;
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

    private static Preference item(ListView list, int position) {
        return (Preference) list.getAdapter().getItem(position);
    }

    /** Where the Turned off lately heading sits in the list, or -1 when it has none. */
    private static int heading(ListView list) {
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            if (!(item(list, i) instanceof SwitchPreference)) return i;
        }
        return -1;
    }

    /** The keys of the switches above the heading: the ones that were on when the list opened. */
    private static Set<String> keysIn(ListView list) {
        Set<String> keys = new TreeSet<>();
        int end = heading(list) < 0 ? list.getAdapter().getCount() : heading(list);
        for (int i = 0; i < end; i++) keys.add(item(list, i).getKey());
        return keys;
    }

    /** The keys under Turned off lately, in the list's order. */
    private static List<String> lately(ListView list) {
        List<String> keys = new ArrayList<>();
        int start = heading(list);
        if (start < 0) return keys;
        for (int i = start + 1; i < list.getAdapter().getCount(); i++) {
            Preference row = item(list, i);
            assertTrue("a row under the heading isn't a switch", row instanceof SwitchPreference);
            assertFalse(row.getKey() + " is listed as turned off lately but shows on",
                    ((SwitchPreference) row).isChecked());
            keys.add(row.getKey());
        }
        return keys;
    }

    private static int positionOf(ListView list, String key) {
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            if (key.equals(item(list, i).getKey())) return i;
        }
        return -1;
    }

    private static void tap(ListView list, int position) {
        list.performItemClick(null, position, 0);
        ShadowLooper.idleMainLooper();
    }

    /** Turns [setting] on, opens the list and turns it off there, leaving the list open. */
    private ListView turnOffThere(HushfacebookPreferenceFragment page, BooleanSetting setting) {
        setting.save(true);
        touched.add(setting);
        ListView list = listOf(open(page));
        int position = positionOf(list, setting.key);
        assertTrue(setting.key + " is on and isn't listed", position >= 0);
        tap(list, position);
        assertFalse(setting.savedValue());
        return list;
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
            assertEquals("nothing was turned off there, yet a Turned off lately group shows", -1, heading(list));
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
            assertTrue(((SwitchPreference) rowWithKey(page, Settings.HIDE_SUGGESTED_GROUPS.key)).isChecked());
            ListView list = listOf(open(page));
            int count = list.getAdapter().getCount();
            int position = positionOf(list, Settings.HIDE_SUGGESTED_GROUPS.key);
            assertTrue("the switch is on and isn't listed", position >= 0);
            tap(list, position);

            assertFalse(Settings.HIDE_SUGGESTED_GROUPS.savedValue());
            // It keeps its place, off, while the list is open, so a wrong tap is one tap to undo.
            assertEquals(count, list.getAdapter().getCount());
            assertEquals(Settings.HIDE_SUGGESTED_GROUPS.key, item(list, position).getKey());
            assertFalse(((SwitchPreference) item(list, position)).isChecked());
            assertFalse("the page's row stayed on",
                    ((SwitchPreference) rowWithKey(page, Settings.HIDE_SUGGESTED_GROUPS.key)).isChecked());
            // Open again: it's off, so it's no longer with the switches that are on, and waits under
            // Turned off lately instead.
            ListView again = listOf(open(page));
            assertFalse(keysIn(again).contains(Settings.HIDE_SUGGESTED_GROUPS.key));
            assertEquals(Collections.singletonList(Settings.HIDE_SUGGESTED_GROUPS.key), lately(again));
        }
    }

    @Test
    public void theHeadingNamesTheGroupAndTakesNoTap() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = page(controller);
            turnOffThere(page, Settings.HIDE_SUGGESTED_GROUPS);
            ListView list = listOf(open(page));
            int heading = heading(list);
            assertTrue("no Turned off lately heading", heading >= 0);
            assertEquals("Turned off lately", String.valueOf(item(list, heading).getTitle()));
            ListAdapter adapter = list.getAdapter();
            assertFalse("the heading takes a tap", adapter.isEnabled(heading));
            assertTrue(adapter.isEnabled(heading + 1));
            assertNotNull(adapter.getView(heading, null, list));
            assertNotNull(adapter.getView(heading + 1, null, list));

            tap(list, heading);
            assertFalse("a tap on the heading turned a switch on", Settings.HIDE_SUGGESTED_GROUPS.savedValue());
            assertFalse(((SwitchPreference) item(list, heading + 1)).isChecked());
        }
    }

    @Test
    public void turningOneBackOnThereTakesItOutOfTheGroup() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = page(controller);
            turnOffThere(page, Settings.HIDE_SUGGESTED_GROUPS);
            ListView list = listOf(open(page));
            int position = positionOf(list, Settings.HIDE_SUGGESTED_GROUPS.key);
            assertEquals(heading(list) + 1, position);
            tap(list, position);

            assertTrue("one tap didn't turn it back on", Settings.HIDE_SUGGESTED_GROUPS.savedValue());
            assertTrue(((SwitchPreference) item(list, position)).isChecked());
            assertEquals("it moved while the list was open", position, positionOf(list, Settings.HIDE_SUGGESTED_GROUPS.key));
            assertTrue("the page's row stayed off",
                    ((SwitchPreference) rowWithKey(page, Settings.HIDE_SUGGESTED_GROUPS.key)).isChecked());
            assertEquals(new ArrayList<String>(), FavoriteSwitchesScreen.turnedOff(context()));

            ListView again = listOf(open(page));
            assertTrue(keysIn(again).contains(Settings.HIDE_SUGGESTED_GROUPS.key));
            assertEquals("it's on again, yet the group still shows", -1, heading(again));
        }
    }

    @Test
    public void aSwitchTurnedOnElsewhereLeavesTheGroup() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = page(controller);
            turnOffThere(page, Settings.HIDE_SUGGESTED_GROUPS);
            turnOffThere(page, Settings.HIDE_PROMOTED_POSTS);
            assertEquals(Arrays.asList(Settings.HIDE_PROMOTED_POSTS.key, Settings.HIDE_SUGGESTED_GROUPS.key),
                    FavoriteSwitchesScreen.turnedOff(context()));

            // On again from its own page or a settings file, not from the list.
            Settings.HIDE_SUGGESTED_GROUPS.save(true);
            ListView list = listOf(open(page));
            assertTrue(keysIn(list).contains(Settings.HIDE_SUGGESTED_GROUPS.key));
            assertEquals(Collections.singletonList(Settings.HIDE_PROMOTED_POSTS.key), lately(list));
            assertEquals("the file still holds a switch that's on",
                    Collections.singletonList(Settings.HIDE_PROMOTED_POSTS.key), FavoriteSwitchesScreen.turnedOff(context()));
        }
    }

    @Test
    public void theGroupListsTheNewestFirstAndKeepsTwenty() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = page(controller);
            List<Preference> rows = new ArrayList<>();
            collect(page.getPreferenceScreen(), rows);
            Set<String> seen = new LinkedHashSet<>();
            List<SettingsRows.Toggle> off = new ArrayList<>();
            for (Preference row : rows) {
                BooleanSetting setting = FavoriteSwitchesScreen.settingOf(row);
                if (!(row instanceof SettingsRows.Toggle) || setting == null || setting.savedValue()) continue;
                if (!seen.add(setting.key)) continue;
                off.add((SettingsRows.Toggle) row);
                touched.add(setting);
                if (off.size() == FavoriteSwitchesScreen.LATELY + 2) break;
            }
            assertEquals("the page has too few switches that are off", FavoriteSwitchesScreen.LATELY + 2, off.size());
            for (SettingsRows.Toggle row : off) FavoriteSwitchesScreen.flip(row, false);

            List<String> newestFirst = new ArrayList<>();
            for (int i = off.size() - 1; i >= 2; i--) newestFirst.add(off.get(i).getKey());
            assertEquals(newestFirst, FavoriteSwitchesScreen.turnedOff(context()));
            assertEquals(newestFirst, lately(listOf(open(page))));

            // Turned off again, one already in the group moves back to the top, still once.
            FavoriteSwitchesScreen.flip(off.get(5), false);
            List<String> stored = FavoriteSwitchesScreen.turnedOff(context());
            assertEquals(FavoriteSwitchesScreen.LATELY, stored.size());
            assertEquals(off.get(5).getKey(), stored.get(0));
            assertEquals(stored.indexOf(off.get(5).getKey()), stored.lastIndexOf(off.get(5).getKey()));
        }
    }

    @Test
    public void aStoredValueThatIsNoListStartsTheGroupAgain() {
        assertTrue(store().edit().putInt(FavoriteSwitchesScreen.OFF_KEYS, 7).commit());
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = page(controller);
            assertEquals(-1, heading(listOf(open(page))));
            turnOffThere(page, Settings.HIDE_SUGGESTED_GROUPS);
            assertEquals(Collections.singletonList(Settings.HIDE_SUGGESTED_GROUPS.key), lately(listOf(open(page))));
        }
    }

    @Test
    public void theGroupIsNotInASettingsFileAndAnImportLeavesItAlone() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = page(controller);
            turnOffThere(page, Settings.HIDE_SUGGESTED_GROUPS);
            String text = SettingsBackup.create();
            assertFalse(text, text.contains(FavoriteSwitchesScreen.OFF_STORE));
            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(text);
            assertEquals(0, snapshot.unknown);

            SettingsBackup.apply(snapshot);
            assertEquals("an import changed the group",
                    Collections.singletonList(Settings.HIDE_SUGGESTED_GROUPS.key), FavoriteSwitchesScreen.turnedOff(context()));
            store().edit().clear().commit();
            SettingsBackup.apply(snapshot);
            assertFalse("an import wrote the group", store().contains(FavoriteSwitchesScreen.OFF_KEYS));
        }
    }
}
