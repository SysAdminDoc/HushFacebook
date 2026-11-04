/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

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

import java.util.EnumSet;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The toggle under the search: the main page opens on a button per section, and the toggle shows
 * every section's rows on one list under their headings instead, keeps that choice for the next
 * time the screen opens, and stays while a search is typed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w390dp-h844dp-night-xhdpi")
@SuppressWarnings("deprecation")
public class CategoryPagesSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private SettingsDialog dialog;
    private HushfacebookPreferenceFragment page;

    @Before public void open() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        PatchFamily.inBuildForTests.remove(PatchFamily.MATERIAL_YOU_THEME);
        PauseForTests.resume();
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        dialog = SettingsL10nTest.show(controller.get());
        page = SettingsL10nTest.pageOf(dialog);
    }

    @After public void close() {
        controller.close();
        PatchFamily.inBuildForTests = null;
        PauseForTests.resume();
    }

    @Test public void theToggleSitsUnderTheSearchAndOffersTheListFirst() {
        layout();
        LinearLayout toggle = toggle();
        ViewGroup root = (ViewGroup) dialog.getView();
        View searchBox = (View) search().getParent();
        assertEquals(View.VISIBLE, toggle.getVisibility());
        assertTrue("the toggle isn't between the search and the list",
                root.indexOfChild(searchBox) < root.indexOfChild(toggle)
                        && root.indexOfChild(toggle) < root.getChildCount() - 1);
        assertEquals("Show all settings as a list", label().getText().toString());
        assertFalse(page.navigation.listMode());
        assertTrue("the toggle is under 48 dp", toggle.getHeight() >= dp(48));
        assertEquals("the toggle doesn't run edge to edge", root.getWidth() - root.getPaddingLeft() - root.getPaddingRight(),
                toggle.getWidth());
        AccessibilityNodeInfo node = toggle.createAccessibilityNodeInfo();
        assertEquals(Button.class.getName(), String.valueOf(node.getClassName()));
        assertTrue(node.isClickable());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, icon().getImportantForAccessibility());
        assertTrue("the toggle has no icon", icon().getDrawable() != null);
        assertEquals(sp(15), label().getTextSize(), 0.01f);
        assertTrue("the main page lost its section buttons", position("section_News feed") >= 0);
    }

    @Test public void theListViewShowsEverySectionsRowsUnderItsHeading() {
        toggle().performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(page.navigation.listMode());
        assertEquals("Show settings by category", label().getText().toString());
        for (int i = 0; i < list().getCount(); i++) {
            String key = item(i).getKey();
            assertFalse("a section button stayed in the list view: " + key,
                    key != null && (key.startsWith("section_") || key.equals(SettingsNavigation.SUPPORT)));
        }
        for (Preference section : page.sections()) {
            PreferenceCategory category = (PreferenceCategory) section;
            if (category.getPreferenceCount() == 0) continue;
            int heading = indexOf(category);
            assertTrue("no heading for " + category.getTitle(), heading >= 0);
            for (int row = 0; row < category.getPreferenceCount(); row++) {
                assertSame(category.getTitle() + " row " + row, category.getPreference(row), item(heading + 1 + row));
            }
        }
        assertTrue(position(SettingsNavigation.REPORT) >= 0);
        assertEquals("Support Hushfacebook isn't the last row before the build details",
                list().getCount() - 2, position(HushfacebookPages.SUPPORT));
        assertEquals(HushfacebookPreferenceFragment.overviewBuildDetails(),
                String.valueOf(item(list().getCount() - 1).getSummary()));
    }

    @Test public void theChoiceOutlivesTheScreenAndTurnsBack() {
        toggle().performClick();
        ShadowLooper.idleMainLooper();
        Context context = controller.get();
        assertTrue(context.getSharedPreferences(SettingsNavigation.VIEW_STORE, Context.MODE_PRIVATE)
                .getBoolean(SettingsNavigation.LIST_VIEW, false));
        dialog.dismissAllowingStateLoss();
        controller.get().getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
        dialog = SettingsL10nTest.show(controller.get());
        page = SettingsL10nTest.pageOf(dialog);
        assertTrue("the list view didn't come back", page.navigation.listMode());
        assertEquals("Show settings by category", label().getText().toString());
        assertEquals(-1, position("section_News feed"));

        toggle().performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(page.navigation.listMode());
        assertEquals("Show all settings as a list", label().getText().toString());
        assertTrue(position("section_News feed") >= 0);
        assertFalse(context.getSharedPreferences(SettingsNavigation.VIEW_STORE, Context.MODE_PRIVATE)
                .getBoolean(SettingsNavigation.LIST_VIEW, true));
        // Every category, Support and the build details: the main page it always had.
        assertEquals(24, list().getCount());
    }

    @Test public void theToggleStaysWhileSearchingAndTheSearchIsTheSameInEitherView() {
        search().setText("download");
        ShadowLooper.idleMainLooper();
        assertEquals(View.VISIBLE, toggle().getVisibility());
        int found = list().getCount();
        assertTrue(found > 1);
        toggle().performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("download", search().getText().toString());
        assertEquals(found, list().getCount());
        search().setText("");
        ShadowLooper.idleMainLooper();
        assertEquals(-1, position("section_News feed"));
        assertTrue(indexOf(page.sections().get(1)) >= 0);
    }

    @Test public void aJumpInTheListViewLandsOnTheSectionsHeading() {
        toggle().performClick();
        ShadowLooper.idleMainLooper();
        Preference downloads = null;
        for (Preference section : page.sections()) {
            if ("Downloads".contentEquals(section.getTitle())) downloads = section;
        }
        assertTrue(downloads != null);
        page.navigation.navigate("Downloads");
        layout();
        assertSame(downloads, item(list().getFirstVisiblePosition()));
    }

    private LinearLayout toggle() {
        View toggle = dialog.getView().findViewWithTag(SettingsDialog.VIEW_TOGGLE);
        assertTrue("no view toggle", toggle instanceof LinearLayout);
        return (LinearLayout) toggle;
    }

    private TextView label() {
        LinearLayout toggle = toggle();
        for (int i = 0; i < toggle.getChildCount(); i++) {
            if (toggle.getChildAt(i) instanceof TextView) return (TextView) toggle.getChildAt(i);
        }
        throw new AssertionError("the toggle has no label");
    }

    private ImageView icon() {
        LinearLayout toggle = toggle();
        for (int i = 0; i < toggle.getChildCount(); i++) {
            if (toggle.getChildAt(i) instanceof ImageView) return (ImageView) toggle.getChildAt(i);
        }
        throw new AssertionError("the toggle has no icon");
    }

    private EditText search() {
        return findSearch(dialog.getView());
    }

    private static EditText findSearch(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = findSearch(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private ListView list() { return dialog.getView().findViewById(android.R.id.list); }

    private Preference item(int position) { return (Preference) list().getItemAtPosition(position); }

    private int indexOf(Preference preference) {
        for (int i = 0; i < list().getCount(); i++) if (item(i) == preference) return i;
        return -1;
    }

    private int position(String key) {
        for (int i = 0; i < list().getCount(); i++) if (key.equals(item(i).getKey())) return i;
        return -1;
    }

    private void layout() {
        View root = dialog.getView();
        root.measure(View.MeasureSpec.makeMeasureSpec(780, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1688, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 780, 1688);
        ShadowLooper.idleMainLooper();
        root.measure(View.MeasureSpec.makeMeasureSpec(780, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1688, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 780, 1688);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                controller.get().getResources().getDisplayMetrics()));
    }

    private float sp(int value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                controller.get().getResources().getDisplayMetrics());
    }
}
