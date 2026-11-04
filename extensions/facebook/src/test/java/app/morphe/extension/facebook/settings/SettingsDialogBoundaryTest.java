/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.graphics.Insets;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.TextView;

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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.EnumSet;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The settings window at its edges, at twice the text size: the system bars and the keyboard
 * inset the content, the bar's back target keeps 48 dp, and in a right-to-left language the bar
 * mirrors with the name after the arrow.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {30, 37}, qualifiers = "w320dp-h640dp-xhdpi")
@SuppressWarnings("deprecation")
public class SettingsDialogBoundaryTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private SettingsDialog dialog;

    @Before public void host() {
        ApplicationInfo info = RuntimeEnvironment.getApplication().getApplicationInfo();
        info.flags |= ApplicationInfo.FLAG_SUPPORTS_RTL;
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        PatchFamily.inBuildForTests.remove(PatchFamily.MATERIAL_YOU_THEME);
        PauseForTests.resume();
        RuntimeEnvironment.setFontScale(2f);
    }

    @After public void close() {
        if (dialog != null) dialog.dismissAllowingStateLoss();
        if (controller != null) controller.close();
        PatchFamily.inBuildForTests = null;
        PauseForTests.resume();
        RuntimeEnvironment.setFontScale(1f);
    }

    @Test public void theSystemBarsStayOffTheContent() {
        ViewGroup root = open(false);
        root.dispatchApplyWindowInsets(new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.systemBars(), Insets.of(11, 24, 13, 30)).build());
        layout(root);
        assertEquals(11, root.getPaddingLeft());
        assertEquals(24, root.getPaddingTop());
        assertEquals(13, root.getPaddingRight());
        assertEquals(30, root.getPaddingBottom());
        assertTrue("the page runs under the gesture bar", shownBottom(root) <= root.getHeight() - root.getPaddingBottom());
    }

    /** An edge to edge window isn't resized for the keyboard, so its inset lifts the page instead. */
    @Test public void theKeyboardLiftsThePageAndGivesTheRoomBack() {
        ViewGroup root = open(false);
        root.dispatchApplyWindowInsets(new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 24, 0, 30))
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 500))
                .setVisible(WindowInsets.Type.ime(), true).build());
        assertEquals("the keyboard is taller than the gesture bar", 500, root.getPaddingBottom());
        layout(root);
        assertTrue("the page ends under the keyboard", shownBottom(root) <= 1280 - 500);

        root.dispatchApplyWindowInsets(new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 24, 0, 30)).build());
        assertEquals("the keyboard closed", 30, root.getPaddingBottom());
    }

    @Test public void theBarKeepsItsBackTargetAndWholeNameAtTwiceTheTextSize() {
        checkBar(false);
    }

    @Test @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp-xhdpi")
    public void theBarMirrorsInARightToLeftLanguage() {
        checkBar(true);
    }

    private void checkBar(boolean rtl) {
        ViewGroup root = open(rtl);
        layout(root);
        ViewGroup bar = (ViewGroup) root.getChildAt(0);
        View back = bar.getChildAt(0);
        TextView title = findText(bar, "Hushfacebook");
        assertNotNull("the bar has no name", title);
        assertEquals(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR, bar.getLayoutDirection());
        assertTrue("the back target is under 48 dp", back.getWidth() >= 96 && back.getHeight() >= 96);
        assertEquals("the bar's name is cut off", 0, title.getLayout().getEllipsisCount(0));
        int[] backAt = new int[2];
        int[] titleAt = new int[2];
        back.getLocationInWindow(backAt);
        title.getLocationInWindow(titleAt);
        assertTrue("the name isn't after the arrow", rtl ? titleAt[0] + title.getWidth() <= backAt[0]
                : backAt[0] + back.getWidth() <= titleAt[0]);
    }

    private ViewGroup open(boolean rtl) {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        dialog = SettingsL10nTest.show(controller.get());
        assertEquals(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR,
                controller.get().getResources().getConfiguration().getLayoutDirection());
        // The test host's window isn't Facebook's mirrored one: give it the direction Facebook's has.
        dialog.getDialog().getWindow().getDecorView().setLayoutDirection(
                rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        ShadowLooper.idleMainLooper();
        ViewGroup root = (ViewGroup) dialog.getView();
        assertNotNull(root);
        return root;
    }

    /**
     * Where the list ends on screen once the header has scrolled up under the bar, which is when
     * the list reaches furthest down: the column scrolls, so its own bottom is past the window.
     */
    private static int shownBottom(ViewGroup root) {
        HeaderColumn column = (HeaderColumn) root;
        column.collapse();
        assertTrue("the header didn't scroll away", column.getScrollY() > 0);
        return root.findViewById(SettingsDialog.CONTAINER_ID).getBottom() - column.getScrollY();
    }

    private static void layout(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 640, 1280);
    }

    private static TextView findText(View view, String wanted) {
        if (view instanceof TextView && wanted.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), wanted);
                if (found != null) return found;
            }
        }
        return null;
    }
}
