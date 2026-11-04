/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.preference.Preference;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.PauseForTests;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;

/**
 * Every row of the main list that opens something leads with a mark, as HushGram's overview rows do:
 * Switches that are on wears the page's own switch, and Support a help mark rather than About's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w390dp-h844dp-night-xhdpi")
@SuppressWarnings("deprecation")
public class SettingsOverviewMarksTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private SettingsDialog dialog;

    @Before public void open() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        PatchFamily.inBuildForTests.remove(PatchFamily.MATERIAL_YOU_THEME);
        PauseForTests.resume();
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        dialog = SettingsL10nTest.show(controller.get());
    }

    @After public void close() {
        controller.close();
        PatchFamily.inBuildForTests = null;
        PauseForTests.resume();
    }

    @Test public void switchesThatAreOnWearsTheSwitch() {
        assertSame(glyph(SettingsIcons.SWITCHES), markOf(row(FavoriteSwitchesScreen.ROW)));
    }

    @Test public void supportIsntASecondAbout() {
        Bitmap support = markOf(row(SettingsNavigation.SUPPORT));
        assertSame(glyph(SettingsIcons.HELP), support);
        assertNotSame("Support and About wear the same mark", markOf(row("section_About")), support);
    }

    @Test public void everyRowThatOpensSomethingHasAMark() {
        List<String> bare = new ArrayList<>();
        for (int i = 0; i < list().getCount(); i++) {
            Preference row = (Preference) list().getItemAtPosition(i);
            if (row.isSelectable() && row.getIcon() == null) bare.add(String.valueOf(row.getTitle()));
        }
        assertEquals(Collections.emptyList(), bare);
    }

    /** The glyph [encoded] decodes to; each is decoded once, so a row wearing it holds this bitmap. */
    private static Bitmap glyph(String encoded) {
        return ((BitmapDrawable) SettingsIcons.icon(RuntimeEnvironment.getApplication(), encoded, Color.WHITE)).getBitmap();
    }

    private static Bitmap markOf(Preference row) {
        assertNotNull(row.getTitle() + " has no mark", row.getIcon());
        return ((BitmapDrawable) row.getIcon()).getBitmap();
    }

    private Preference row(String key) {
        for (int i = 0; i < list().getCount(); i++) {
            Preference row = (Preference) list().getItemAtPosition(i);
            if (key.equals(row.getKey())) return row;
        }
        throw new AssertionError(key + " isn't on the main list");
    }

    private ListView list() { return dialog.getView().findViewById(android.R.id.list); }
}
