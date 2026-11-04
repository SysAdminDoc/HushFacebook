/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.preference.Preference;
import android.util.TypedValue;
import android.view.View;
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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.shared.settings.preference.ReopenSettingsAfterRestart;

/**
 * The restart bar: a change that waits on a restart puts a button at the top of the list, on the
 * 18 dp line with page above and below it, outlined in the heading colour, and a tap restarts
 * Facebook back onto the same page in the same view.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w390dp-h844dp-night-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SuppressWarnings("deprecation")
public class RestartNowTest {
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
        ReopenSettingsAfterRestart.setRestarterForTests(null);
        PauseForTests.resume();
    }

    @Test public void aChangeThatWaitsOnARestartShowsTheBarAtTheTop() {
        AbstractPreferenceFragment.restartPending.add(Settings.MARKETPLACE_ONLY.key);
        page.navigation.navigate("");
        assertEquals(SettingsNavigation.RESTART, item(0).getKey());
        assertTrue("the bar can't be tapped", list().getAdapter().isEnabled(0));
        View bar = list().getAdapter().getView(0, null, list());
        assertTrue(bar.getBackground() instanceof RippleDrawable);
        Drawable layer = ((RippleDrawable) bar.getBackground()).getDrawable(0);
        assertTrue(layer instanceof InsetDrawable);
        GradientDrawable card = (GradientDrawable) ((InsetDrawable) layer).getDrawable();
        assertEquals(dp(12), card.getCornerRadius(), 0.01);
        assertTrue("the bar is under 56 dp with its 8 dp of page above and below", bar.getMinimumHeight() >= dp(72));
        TextView title = bar.findViewById(android.R.id.title);
        assertEquals("Restart Facebook to apply this change", title.getText().toString());
        assertEquals(ScreenColors.current().title, title.getCurrentTextColor());
    }

    @Test public void theBarStandsOnThe18DpLineWithItsOutline() {
        ScreenColors colors = ScreenColors.current();
        AbstractPreferenceFragment.restartPending.add(Settings.MARKETPLACE_ONLY.key);
        page.navigation.navigate("");
        Bitmap drawn = draw(0, colors.background);
        int w = drawn.getWidth();
        int h = drawn.getHeight();
        int line = dp(18);
        assertPixel("the page past the bar's start", colors.background, drawn.getPixel(line - 1, h / 2));
        assertPixel("the bar's outline", colors.heading, drawn.getPixel(line, h / 2));
        assertPixel("inside the bar", colors.card, drawn.getPixel(line + dp(1) + 1, h / 2));
        assertPixel("the bar's end", colors.heading, drawn.getPixel(w - line - 1, h / 2));
        assertPixel("the page past the bar's end", colors.background, drawn.getPixel(w - line, h / 2));
        assertPixel("the page above the bar", colors.background, drawn.getPixel(w / 2, dp(8) - 1));
        assertPixel("the bar's top", colors.heading, drawn.getPixel(w / 2, dp(8)));
        assertPixel("the bar's bottom", colors.heading, drawn.getPixel(w / 2, h - dp(8) - 1));
        assertPixel("the page under the bar", colors.background, drawn.getPixel(w / 2, h - dp(8)));
    }

    @Test public void theBarRestartsIntoTheListViewItWasOn() {
        List<Context> restarted = new ArrayList<>();
        ReopenSettingsAfterRestart.setRestarterForTests(restarted::add);
        page.navigation.showAsList(true);
        AbstractPreferenceFragment.restartPending.add(Settings.MARKETPLACE_ONLY.key);
        page.navigation.navigate("Downloads");
        int at = 0;
        assertEquals(SettingsNavigation.RESTART, item(at).getKey());
        View row = list().getAdapter().getView(at, null, list());
        assertTrue(list().performItemClick(row, at, list().getAdapter().getItemId(at)));
        ShadowLooper.idleMainLooper();
        assertEquals(1, restarted.size());
        String state = ReopenSettingsAfterRestart.takeFresh(Setting.preferences.preferences,
                android.os.SystemClock.elapsedRealtime());
        assertNotNull("restart did not save where settings was", state);
        AbstractPreferenceFragment.restartPending.clear();
        dialog.dismissAllowingStateLoss();
        controller.get().getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
        dialog = SettingsDialog.onPage(state);
        dialog.show(controller.get().getFragmentManager(), "hushfacebook_settings");
        controller.get().getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
        page = SettingsL10nTest.pageOf(dialog);
        assertTrue("the restart came back in the other view", page.navigation.listMode());
        assertTrue(page.navigation.isExpanded("Downloads"));
    }

    private static void assertPixel(String where, int expected, int actual) {
        boolean close = Math.abs(Color.red(expected) - Color.red(actual)) <= 2
                && Math.abs(Color.green(expected) - Color.green(actual)) <= 2
                && Math.abs(Color.blue(expected) - Color.blue(actual)) <= 2;
        assertTrue(where + ": expected #" + Integer.toHexString(expected) + ", drew #" + Integer.toHexString(actual), close);
    }

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

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                controller.get().getResources().getDisplayMetrics()));
    }
}
