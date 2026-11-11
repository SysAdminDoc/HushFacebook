/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.facebook.litho.ComponentHost;
import com.facebook.litho.LithoView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.Duration;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Facebook's seek preview clears a kept name and caption. The tree copies the S22's Reels viewer
 * mid-drag on 586 (2026-11-10): the footer block from 1771 on screen, and the scrubber's host beside
 * the list of reels with the preview inflated into its frame on the first drag, its box from 1614 to
 * 2024.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w384dp-h823dp-xxhdpi")
public class SeekPreviewRoomTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Activity activity;
    private ComponentHost overlay;
    private FrameLayout frame;
    private FrameLayout label;
    private FrameLayout preview;
    private LinearLayout box;

    @Before
    public void setUp() {
        SeekPreviewRoom.reset();
        HookStatus.clear();
        Settings.KEEP_REEL_OVERLAY.save(true);
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        LithoView viewer = new LithoView(activity);
        place(root, viewer, 0, 0, 1080, 2200);
        FrameLayout list = new FrameLayout(activity);
        place(viewer, list, 0, 0, 1080, 2200);
        LithoView reel = new LithoView(activity);
        place(list, reel, 0, 0, 1080, 2200);
        overlay = new ComponentHost(activity);
        place(reel, overlay, 0, 916, 1080, 2105);
        ComponentHost row = new ComponentHost(activity);
        place(overlay, row, 0, 0, 1080, 1091);
        ComponentHost column = new ComponentHost(activity);
        place(row, column, 0, 872, 945, 1057);
        place(column, new ComponentHost(activity), 0, -17, 945, 202);
        ComponentHost actions = new ComponentHost(activity);
        place(row, actions, 945, 0, 1080, 1057);
        FrameLayout host = new FrameLayout(activity);
        place(viewer, host, 0, 1085, 1080, 2165);
        frame = new FrameLayout(activity);
        place(host, frame, 0, 0, 1080, 1080);
        label = new FrameLayout(activity);
        place(frame, label, 0, 918, 291, 1080);
        place(frame, new FrameLayout(activity), 0, 984, 1080, 1080);
        activity.setContentView(root);
        layOut();
    }

    @After
    public void tearDown() {
        Settings.KEEP_REEL_OVERLAY.resetToDefault();
        SeekPreviewRoom.reset();
        HookStatus.clear();
    }

    @Test
    public void aKeptPressRaisesThePreviewOffTheFooterUntilTheBoxGoes() {
        SeekPreviewRoom.updated(label);
        frame();

        showPreview();
        frame();

        float lift = 2024 + 8 * activity.getResources().getDisplayMetrics().density - 1771;
        assertEquals(-lift, preview.getTranslationY(), 0f);
        frame();
        assertEquals("and it doesn't keep rising", -lift, preview.getTranslationY(), 0f);
        assertTrue(HookStatus.report().toString().contains(SeekPreviewRoom.LIFTED + " 1"));

        box.setVisibility(View.GONE);
        frame();
        assertEquals("the release puts it back", 0f, preview.getTranslationY(), 0f);

        SeekPreviewRoom.pressed();
        box.setVisibility(View.VISIBLE);
        layOut();
        frame();
        assertEquals("and the next press raises it again", -lift, preview.getTranslationY(), 0f);
    }

    @Test
    public void aTranslationFacebookSetsMidPressGetsTheRiseOnTopAndStaysAfter() {
        SeekPreviewRoom.updated(label);
        showPreview();
        preview.setTranslationY(-10);
        frame();

        float lift = 2024 + 8 * activity.getResources().getDisplayMetrics().density - 1771;
        assertEquals(-10 - lift, preview.getTranslationY(), 0f);

        box.setVisibility(View.GONE);
        frame();
        assertEquals(-10f, preview.getTranslationY(), 0f);
    }

    @Test
    public void aPreviewWithNoKeptPressStaysWhereFacebookPutsIt() {
        SeekPreviewRoom.updated(label);
        showPreviewWithoutPress();
        frame();

        assertEquals(0f, preview.getTranslationY(), 0f);
        assertFalse(HookStatus.report().toString().contains(SeekPreviewRoom.LIFTED));
    }

    @Test
    public void withTheSwitchOffThePreviewStays() {
        Settings.KEEP_REEL_OVERLAY.save(false);
        SeekPreviewRoom.updated(label);
        showPreview();
        frame();

        assertEquals(0f, preview.getTranslationY(), 0f);
    }

    @Test
    public void aFooterAboveTheBoxLeavesItAlone() {
        // A footer laid out higher: it ends above where the box starts.
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) overlay.getLayoutParams();
        params.topMargin -= 500;
        overlay.setLayoutParams(params);
        layOut();
        SeekPreviewRoom.updated(label);
        showPreview();
        frame();

        assertEquals(0f, preview.getTranslationY(), 0f);
    }

    /** Facebook inflates the preview from its stub at the press, then lays it out. */
    private void showPreview() {
        SeekPreviewRoom.pressed();
        showPreviewWithoutPress();
    }

    private void showPreviewWithoutPress() {
        preview = new FrameLayout(activity);
        place(frame, preview, 45, 529, 1035, 1080);
        box = new LinearLayout(activity);
        place(preview, box, 349, 0, 630, 410);
        layOut();
    }

    private void frame() {
        label.getViewTreeObserver().dispatchOnPreDraw();
    }

    private static void place(ViewGroup parent, View child, int left, int top, int right, int bottom) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(right - left, bottom - top, Gravity.LEFT | Gravity.TOP);
        params.leftMargin = left;
        params.topMargin = top;
        parent.addView(child, params);
    }

    private void layOut() {
        activity.getWindow().getDecorView().requestLayout();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
    }
}
