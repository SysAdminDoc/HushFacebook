/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

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

import com.facebook.litho.LithoView;
import com.facebook.navigation.tabbar.ui.TabBarContainerLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.Duration;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The Reels header under a tab bar at the top (#390). The tree copies the S22's Reels tab on 587
 * with the tabs at the top (2026-11-16): the tab bar, 124 px, over a page that starts at the top of
 * the screen because the reel runs under the bar, with the header, a LithoView with its title row
 * at 45 to 146, at the top of the page.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w384dp-h823dp-xxhdpi")
public class ReelsHeaderClearTest {
    private static final int TAB_BAR_HEIGHT = 124;
    private static final int PAGE_HEIGHT = 2199;

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Activity activity;
    private FrameLayout screen;
    private FrameLayout page;
    private LithoView header;
    private TabBarContainerLayout tabBar;

    @Before
    public void setUp() {
        ReelsHeaderClear.resetForTests();
        HookStatus.clear();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        screen = new FrameLayout(activity);
        page = new FrameLayout(activity);
        place(screen, page, 0, 0, 1080, PAGE_HEIGHT);
        FrameLayout reels = new FrameLayout(activity);
        place(page, reels, 0, 0, 1080, PAGE_HEIGHT);
        header = new LithoView(activity);
        place(page, header, 0, 0, 1080, 338);
        activity.setContentView(screen);
        layOut();
    }

    @After
    public void tearDown() {
        PauseForTests.resume();
        ReelsHeaderClear.resetForTests();
        HookStatus.clear();
    }

    @Test
    public void theHeaderGoesBelowATabBarAtTheTop() {
        addTabBar(0);

        ReelsHeaderClear.headerMade(header);
        frame();

        assertEquals(TAB_BAR_HEIGHT, top(header));
        assertEquals("the reel still starts at the top of the screen", 0, page.getChildAt(0).getTop());
        assertTrue(HookStatus.report().toString().contains(ReelsHeaderClear.CLEARED + " 1"));
    }

    @Test
    public void itFollowsTheBarAndGoesBackWhenTheBarIsHidden() {
        addTabBar(0);
        ReelsHeaderClear.headerMade(header);
        frame();

        tabBar.setTranslationY(-50);
        frame();
        assertEquals("a bar slid partway up", TAB_BAR_HEIGHT - 50, top(header));

        tabBar.setVisibility(View.GONE);
        frame();
        assertEquals("a hidden bar gives the header back to Facebook", 0, top(header));
    }

    @Test
    public void facebooksOwnMarginStays() {
        ((ViewGroup.MarginLayoutParams) header.getLayoutParams()).topMargin = 30;
        header.requestLayout();
        layOut();
        addTabBar(0);

        ReelsHeaderClear.headerMade(header);
        frame();
        assertEquals(TAB_BAR_HEIGHT, top(header));

        tabBar.setVisibility(View.GONE);
        frame();
        assertEquals(30, top(header));
    }

    @Test
    public void aTabBarAtTheBottomLeavesTheHeaderAlone() {
        addTabBar(2075);

        ReelsHeaderClear.headerMade(header);
        frame();

        assertEquals(0, top(header));
        assertFalse(HookStatus.report().toString().contains(ReelsHeaderClear.CLEARED));
    }

    /** With Facebook's padding the page starts below the bar, so the header is clear already. */
    @Test
    public void aPagePaddedBelowTheBarLeavesTheHeaderAlone() {
        page.setPadding(0, TAB_BAR_HEIGHT, 0, 0);
        addTabBar(0);

        ReelsHeaderClear.headerMade(header);
        frame();

        assertEquals(TAB_BAR_HEIGHT, top(header));
        assertEquals(0, ((ViewGroup.MarginLayoutParams) header.getLayoutParams()).topMargin);
    }

    @Test
    public void pausedHushLeavesTheHeaderAlone() {
        addTabBar(0);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);

        ReelsHeaderClear.headerMade(header);
        frame();

        assertEquals(0, top(header));
    }

    @Test
    public void aSecondHookForTheSameHeaderAddsNothing() {
        addTabBar(0);

        ReelsHeaderClear.headerMade(header);
        ReelsHeaderClear.headerMade(header);
        frame();

        assertEquals(TAB_BAR_HEIGHT, top(header));
    }

    @Test
    public void noHeaderIsNoFailure() {
        ReelsHeaderClear.headerMade(null);
        assertFalse(HookStatus.report().toString().contains("threw"));
    }

    private int top(View view) {
        return view.getTop();
    }

    private void addTabBar(int top) {
        tabBar = new TabBarContainerLayout(activity);
        place(screen, tabBar, 0, top, 1080, top + TAB_BAR_HEIGHT);
        layOut();
    }

    /** A frame: the pre-draw pass, then the layout it asked for. */
    private void frame() {
        screen.getViewTreeObserver().dispatchOnPreDraw();
        layOut();
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
