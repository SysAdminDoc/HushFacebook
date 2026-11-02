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
import android.preference.Preference;
import android.view.View;
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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import app.morphe.extension.shared.SettingsContextRule;

/**
 * Every category stays on the main list. A requested section expands in place and scrolls into
 * view; Back closes settings without walking through a category history.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w411dp-h891dp-xxhdpi")
@SuppressWarnings("deprecation")
public class SectionJumpTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private ActivityController<Activity> controller;

    @Before
    public void everyPatchIn() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
    }

    @After
    public void restore() {
        if (controller != null) controller.close();
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
    }

    @Test
    public void theMainListContainsEverySectionInOrderWithoutAMoreHub() {
        SettingsDialog dialog = show();
        HushfacebookPreferenceFragment page = page(dialog);
        ListView list = laidOut(dialog);

        List<String> titles = new ArrayList<>();
        for (int i = 0; i < page.sections().size(); i++) {
            Preference header = (Preference) list.getItemAtPosition(i + 1);
            assertTrue(header.getKey(), header.getKey().startsWith("section_"));
            titles.add(header.getTitle().toString());
        }
        List<String> expected = java.util.Arrays.asList("Opening Facebook", "News feed", "Stories", "Reels and Watch",
                "Video and audio", "Downloads", "Comments and writing", "Chats and Messenger", "Menu",
                "Meta's other products", "Search", "Marketplace", "Notifications", "Links and sharing", "Privacy", "Updates",
                "Tabs and navigation", "Appearance", "Built-in changes", "Pause, backup and diagnostics", "About");
        assertEquals(expected, titles);
        assertEquals(21, titles.size());
        assertEquals(SettingsNavigation.SUPPORT, ((Preference) list.getItemAtPosition(22)).getKey());
        assertEquals(24, list.getCount());
        assertFalse(titles.contains("More settings"));
        assertTrue(titles.contains("Downloads"));
        assertTrue(titles.contains("Links and sharing"));
    }

    @Test
    public void aSectionIsRevealedInTheMainListAndBackClosesSettings() {
        SettingsDialog dialog = show();
        HushfacebookPreferenceFragment page = page(dialog);
        ListView list = laidOut(dialog);
        assertEquals(0, list.getFirstVisiblePosition());

        Preference downloads = sectionTitled(page, "Downloads");
        assertTrue(page.jumpTo(downloads));
        relayout(list);
        assertTrue(page.navigation.isExpanded("Downloads"));
        assertEquals(24 + ((android.preference.PreferenceCategory) downloads).getPreferenceCount(), list.getCount());
        int header = -1;
        for (int i = 0; i < list.getCount(); i++) {
            if ("section_Downloads".equals(((Preference) list.getItemAtPosition(i)).getKey())) header = i;
        }
        assertTrue("the Downloads header is off screen", header >= list.getFirstVisiblePosition()
                && header <= list.getLastVisiblePosition());
        assertTrue(downloads == ((Preference) list.getItemAtPosition(header + 1)).getParent());
        assertFalse(page.backFromJump());
        dialog.getDialog().onBackPressed();
        assertFalse("Back should close settings directly", dialog.getDialog().isShowing());
        assertFalse("Back should leave Facebook running", controller.get().isFinishing());
    }

    @Test
    public void withoutAJumpBackClosesThePageAsBefore() {
        SettingsDialog dialog = show();
        laidOut(dialog);
        assertFalse(page(dialog).backFromJump());
        dialog.getDialog().onBackPressed();
        assertFalse(dialog.getDialog().isShowing());
    }

    private SettingsDialog show() {
        if (controller == null) controller = Robolectric.buildActivity(Activity.class).setup();
        return SettingsL10nTest.show(controller.get());
    }

    private static HushfacebookPreferenceFragment page(SettingsDialog dialog) {
        Object page = dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
        assertTrue("no settings page in the dialog", page instanceof HushfacebookPreferenceFragment);
        return (HushfacebookPreferenceFragment) page;
    }

    private static Preference sectionTitled(HushfacebookPreferenceFragment page, String title) {
        for (Preference section : page.sections()) {
            if (title.contentEquals(String.valueOf(section.getTitle()))) return section;
        }
        throw new AssertionError("no section titled " + title);
    }

    /** The list laid out a phone screen tall, so it has somewhere to scroll to. */
    private ListView laidOut(SettingsDialog dialog) {
        ListView list = dialog.getView().findViewById(android.R.id.list);
        assertNotNull("no list in the dialog", list);
        relayout(list);
        return list;
    }

    private void relayout(ListView list) {
        int width = controller.get().getResources().getDisplayMetrics().widthPixels;
        int height = controller.get().getResources().getDisplayMetrics().heightPixels;
        list.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, width, height);
    }
}
