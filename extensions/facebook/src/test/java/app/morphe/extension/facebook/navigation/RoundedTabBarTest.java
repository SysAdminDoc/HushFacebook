/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.Rect;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Rounded tab bar (#344): the tab bar at the bottom is clipped to a pill around its row of tabs and
 * drawn 90% of its size with the pill's bottom 8 dp over the row's, and off, paused, before the
 * settings or with the bar at the top it's Facebook's own shape. The screen is a 720 by 1600 phone
 * at mdpi with a 176 pixel holder at the bottom: a 2 pixel line, the 112 pixel row of tabs and the
 * 62 pixels it keeps over the navigation bar.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class RoundedTabBarTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
        Settings.ROUNDED_TAB_BAR.save(true);
    }

    @After
    public void restore() {
        RoundedTabBar.insetsForTests = null;
        PauseForTests.resume();
        Settings.ROUNDED_TAB_BAR.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.BOTTOM_TAB_BAR + ":")) return line;
        }
        return "";
    }

    private static Rect outlineOf(RoundedTabBarForTests screen, float[] radius) {
        Outline outline = new Outline();
        screen.holder.getOutlineProvider().getOutline(screen.holder, outline);
        Rect box = new Rect();
        assertTrue("the outline is a rounded box", outline.getRect(box));
        radius[0] = outline.getRadius();
        return box;
    }

    @Test
    public void theSwitchStartsOffShowsWithoutARestartAndBelongsToTheTabBarFamily() {
        Settings.ROUNDED_TAB_BAR.resetToDefault();
        assertFalse(Settings.ROUNDED_TAB_BAR.get());
        assertFalse("a change shows on the next frame", Settings.ROUNDED_TAB_BAR.rebootApp);
        assertTrue(PatchFamily.BOTTOM_TAB_BAR.switches.contains(Settings.ROUNDED_TAB_BAR));
        assertFalse(RoundedTabBar.on());
    }

    @Test
    public void theBarAtTheBottomBecomesAPillAroundItsRowAndGetsItsShapeBack() {
        RoundedTabBarForTests screen = new RoundedTabBarForTests();
        ViewOutlineProvider facebooks = screen.holder.getOutlineProvider();
        screen.shaper.update();

        assertTrue("the holder is clipped", screen.holder.getClipToOutline());
        float[] radius = new float[1];
        assertEquals("the pill is the row of tabs, not the line or the strip under it",
                new Rect(0, 2, 720, 114), outlineOf(screen, radius));
        assertEquals("its ends are half its height", 56f, radius[0], 0.01f);
        assertEquals(RoundedTabBar.SCALE, screen.holder.getScaleX(), 0f);
        assertEquals(RoundedTabBar.SCALE, screen.holder.getScaleY(), 0f);
        assertEquals("scaled about the row's middle", 360f, screen.holder.getPivotX(), 0.01f);
        // 114 - 8 / 0.1: the row's bottom, 114, lands at 106, 8 dp over it.
        assertEquals(34f, screen.holder.getPivotY(), 0.01f);
        float bottom = screen.holder.getPivotY() + RoundedTabBar.SCALE * (114 - screen.holder.getPivotY());
        assertEquals(106f, bottom, 0.01f);
        assertEquals("the row of tabs itself keeps its size", 1f, screen.tabBar.getScaleX(), 0f);

        Rect pill = new Rect();
        assertEquals(50.5f, RoundedTabBar.pillInWindow(screen.holder, pill), 0.6f);
        Rect expected = new Rect(36, 1429, 684, 1530);
        expected.offset(0, screen.windowTop());
        assertEquals("648 of the row's 720 pixels, in the middle", expected, pill);
        assertTrue(statusLine(), statusLine().contains(RoundedTabBar.ROUNDED + " 1"));

        // More frames keep it, counted once.
        screen.shaper.update();
        screen.shaper.update();
        assertTrue(statusLine(), statusLine().contains(RoundedTabBar.ROUNDED + " 1"));

        Settings.ROUNDED_TAB_BAR.save(false);
        screen.shaper.update();
        assertFalse(screen.holder.getClipToOutline());
        assertSame(facebooks, screen.holder.getOutlineProvider());
        assertEquals(1f, screen.holder.getScaleX(), 0f);
        assertEquals(1f, screen.holder.getScaleY(), 0f);
        assertFalse("the pivot is the middle again", screen.holder.isPivotSet());
        assertEquals(0f, RoundedTabBar.pillInWindow(screen.holder, pill), 0f);
        assertTrue(statusLine(), statusLine().contains(RoundedTabBar.GIVEN_BACK + " 1"));

        Settings.ROUNDED_TAB_BAR.save(true);
        screen.shaper.update();
        assertTrue("on again, it's a pill again", screen.holder.getClipToOutline());
    }

    @Test
    public void aRowThatMovesInItsHolderMovesThePill() {
        RoundedTabBarForTests screen = new RoundedTabBarForTests();
        screen.shaper.update();
        screen.line.getLayoutParams().height = 10;
        screen.line.requestLayout();
        screen.layOut();
        screen.shaper.update();
        float[] radius = new float[1];
        assertEquals(new Rect(0, 10, 720, 122), outlineOf(screen, radius));
        assertEquals(122f - 80f, screen.holder.getPivotY(), 0.01f);
    }

    private static WindowInsets navigationBar(int height) {
        return new WindowInsets.Builder().setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, height)).build();
    }

    @Test
    public void aRowLaidOutOverTheNavigationBarEndsAboveIt() {
        RoundedTabBar.insetsForTests = navigationBar(62);
        RoundedTabBarForTests screen = new RoundedTabBarForTests(true);
        screen.shaper.update();
        float[] radius = new float[1];
        assertEquals("the pill ends where the navigation bar starts, the feed shows around the handle",
                new Rect(0, 2, 720, 114), outlineOf(screen, radius));
        assertEquals(56f, radius[0], 0.01f);
        assertEquals("8 dp over the end of the tabs, not the end of the row", 34f, screen.holder.getPivotY(), 0.01f);

        screen.holder.setTranslationY(120);
        screen.shaper.update();
        assertEquals("scroll-away sliding the bar down keeps the pill's size",
                new Rect(0, 2, 720, 114), outlineOf(screen, radius));

        RoundedTabBar.insetsForTests = navigationBar(0);
        screen.shaper.update();
        assertEquals("no navigation bar, the whole row", new Rect(0, 2, 720, 176), outlineOf(screen, radius));
    }

    @Test
    public void seeThroughFrostStaysInsideThePill() {
        RoundedTabBarForTests screen = new RoundedTabBarForTests();
        int top = screen.windowTop();
        Rect pill = new Rect();
        Rect area = new Rect(0, top + 1424, 720, top + 1600);
        assertEquals("not a pill yet, the frost keeps its box", 0f,
                TranslucentBars.keepInPill(area, screen.holder, pill), 0f);
        assertEquals(new Rect(0, top + 1424, 720, top + 1600), area);

        screen.shaper.update();
        assertEquals("the frost's corners are the pill's", 50.5f,
                TranslucentBars.keepInPill(area, screen.holder, pill), 0.6f);
        assertEquals(new Rect(36, top + 1429, 684, top + 1530), area);

        Rect above = new Rect(0, top, 720, top + 100);
        TranslucentBars.keepInPill(above, screen.holder, pill);
        assertTrue("frost away from the pill draws nothing", above.isEmpty());
    }

    @Test
    public void pausedBeforeTheSettingsOrAtTheTopTheBarIsFacebooks() {
        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            RoundedTabBarForTests screen = new RoundedTabBarForTests();
            screen.shaper.update();
            PauseForTests.pause(why);
            screen.shaper.update();
            assertFalse("paused by " + why, screen.holder.getClipToOutline());
            assertEquals("paused by " + why, 1f, screen.holder.getScaleX(), 0f);
            PauseForTests.resume();
        }

        RoundedTabBarForTests early = new RoundedTabBarForTests();
        SettingsContextRule.withoutContext(early.shaper::update);
        assertFalse(early.holder.getClipToOutline());
        assertEquals(1f, early.holder.getScaleX(), 0f);

        HookStatus.clear();
        TranslucentBarsForTests.TabBarOnTop top = new TranslucentBarsForTests.TabBarOnTop();
        RoundedTabBar.Shaper shaper = new RoundedTabBar.Shaper(top.window, TranslucentBarsForTests.TabBar.class.getName());
        shaper.update();
        assertFalse("a tab bar at the top stays Facebook's", top.header.getClipToOutline());
        assertEquals(1f, top.header.getScaleX(), 0f);
        assertFalse(statusLine(), statusLine().contains(RoundedTabBar.ROUNDED));
    }
}
