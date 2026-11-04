/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.preference.Preference;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.EnumSet;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Drawn pixels at the page's edges: the list fills the window on the black page, a main page row
 * has no hairline down either side and one line under it, and an open section's card stands on
 * the 18 dp line with the page showing past it. The list keeps room under its last row.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w390dp-h844dp-night-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SuppressWarnings("deprecation")
public class SettingsEdgeInsetsTest {
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

    @Test public void theListFillsTheWindowOnTheBlackPage() {
        ListView list = list();
        assertEquals(0, list.getPaddingLeft());
        assertEquals(0, list.getPaddingRight());
        int[] at = new int[2];
        list.getLocationInWindow(at);
        assertEquals("the list starts in from the window's edge", 0, at[0]);
        assertEquals("the list stops short of the window's edge", list.getRootView().getWidth(), list.getWidth());
        assertTrue(list.getBackground() instanceof ColorDrawable);
        assertEquals(Color.BLACK, ((ColorDrawable) list.getBackground()).getColor());
        assertEquals("the last row can't clear the gesture bar", dp(24), list.getPaddingBottom());
        assertFalse(list.getClipToPadding());
    }

    @Test public void aMainPageRowHasNoHairlineDownEitherEdge() {
        ScreenColors colors = ScreenColors.current();
        String key = "section_" + page.navigation.sectionId(page.sections().get(1));
        Bitmap drawn = draw(position(key), colors.background);
        int w = drawn.getWidth();
        int h = drawn.getHeight();
        assertPixel("left edge", colors.card, drawn.getPixel(0, h / 2));
        assertPixel("right edge", colors.card, drawn.getPixel(w - 1, h / 2));
        assertPixel("top edge", colors.card, drawn.getPixel(dp(4), 0));
        assertPixel("the line under the row", colors.outline, drawn.getPixel(dp(4), h - 1));
    }

    @Test public void anOpenSectionsCardStandsOnThe18DpLine() {
        ScreenColors colors = ScreenColors.current();
        Preference section = page.sections().get(1);
        String key = "section_" + page.navigation.sectionId(section);
        tap(key);
        int middle = position(key) + 2;
        while (item(middle - 1) instanceof SettingsRows.Subheading || item(middle) instanceof SettingsRows.Subheading
                || item(middle + 1) instanceof SettingsRows.Subheading) middle++;
        assertTrue("the section has no middle row", item(middle + 1).getParent() == section);
        Bitmap drawn = draw(middle, colors.background);
        int w = drawn.getWidth();
        int h = drawn.getHeight();
        int line = dp(18);
        assertPixel("the page past the card's start", colors.background, drawn.getPixel(line - 1, h / 2));
        assertPixel("the card's start", colors.outline, drawn.getPixel(line, h / 2));
        assertPixel("inside the card", colors.card, drawn.getPixel(line + dp(1) + 1, h / 2));
        assertPixel("the card's end", colors.outline, drawn.getPixel(w - line - 1, h / 2));
        assertPixel("the page past the card's end", colors.background, drawn.getPixel(w - line, h / 2));
        assertPixel("one line between rows, not two", colors.card, drawn.getPixel(line + dp(4), 0));
        assertPixel("the line under the row", colors.outline, drawn.getPixel(line + dp(4), h - 1));
    }

    @Test public void theSearchStandsOnThe18DpLineAndTheToggleRunsEdgeToEdge() {
        ScreenColors colors = ScreenColors.current();
        ViewGroup root = (ViewGroup) dialog.getView();
        root.measure(View.MeasureSpec.makeMeasureSpec(780, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1688, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 780, 1688);
        EditText search = findSearch(root);
        assertTrue(search != null);
        View box = (View) search.getParent();
        int start = root.getPaddingLeft();
        int end = root.getWidth() - root.getPaddingRight();
        assertEquals("the search's start", start + dp(18), box.getLeft());
        assertEquals("the search's end", end - dp(18), box.getRight());
        assertTrue(box.getBackground() instanceof GradientDrawable);
        assertEquals(dp(10), ((GradientDrawable) box.getBackground()).getCornerRadius(), 0.01);
        assertEquals(colors.hint, search.getCurrentHintTextColor());
        assertEquals(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16,
                controller.get().getResources().getDisplayMetrics()), search.getTextSize(), 0.01f);
        assertTrue("the search is under 48 dp", box.getHeight() >= dp(48));
        View toggle = root.findViewWithTag(SettingsDialog.VIEW_TOGGLE);
        assertEquals("the toggle's start", start, toggle.getLeft());
        assertEquals("the toggle's end", end, toggle.getRight());
    }

    private static EditText findSearch(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            EditText found = findSearch(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private static void assertPixel(String where, int expected, int actual) {
        boolean close = Math.abs(Color.red(expected) - Color.red(actual)) <= 2
                && Math.abs(Color.green(expected) - Color.green(actual)) <= 2
                && Math.abs(Color.blue(expected) - Color.blue(actual)) <= 2;
        assertTrue(where + ": expected #" + Integer.toHexString(expected) + ", drew #" + Integer.toHexString(actual), close);
    }

    /** The row at [position], drawn by the page's own list at the phone's width over [page]. */
    private Bitmap draw(int position, int page) {
        View row = list().getAdapter().getView(position, null, list());
        int width = controller.get().getResources().getDisplayMetrics().widthPixels;
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        row.layout(0, 0, width, row.getMeasuredHeight());
        Bitmap bitmap = Bitmap.createBitmap(width, row.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(page);
        row.draw(canvas);
        return bitmap;
    }

    private ListView list() { return dialog.getView().findViewById(android.R.id.list); }

    private Preference item(int position) { return (Preference) list().getItemAtPosition(position); }

    private int position(String key) {
        for (int i = 0; i < list().getCount(); i++) {
            if (key.equals(item(i).getKey())) return i;
        }
        throw new AssertionError("no visible row " + key);
    }

    private void tap(String key) {
        int at = position(key);
        View row = list().getAdapter().getView(at, null, list());
        assertTrue(list().performItemClick(row, at, list().getAdapter().getItemId(at)));
        ShadowLooper.idleMainLooper();
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                controller.get().getResources().getDisplayMetrics()));
    }
}
