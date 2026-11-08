/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

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
        Settings.TAB_COLOR.resetToDefault();
        Settings.TAB_BAR_WIDTH.resetToDefault();
        Settings.TAB_BAR_HEIGHT.resetToDefault();
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
        assertEquals("a line above the row is clipped away, not faded", 1f, screen.line.getAlpha(), 0f);

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

    /** #425: Tab bar width scales the whole pill evenly, and Tab bar height trims it around the icons. */
    @Test
    public void widthScalesThePillEvenlyAndHeightTrimsIt() {
        RoundedTabBarForTests screen = new RoundedTabBarForTests();
        Settings.TAB_BAR_WIDTH.save("80");
        Settings.TAB_BAR_HEIGHT.save("70");
        screen.shaper.update();

        assertEquals(0.8f, screen.holder.getScaleX(), 0f);
        assertEquals("both ways, so the icons keep their shape", 0.8f, screen.holder.getScaleY(), 0f);
        float[] radius = new float[1];
        // 30% of the row's 112 pixels, 17 off its top and 17 off its bottom.
        assertEquals(new Rect(0, 19, 720, 97), outlineOf(screen, radius));
        assertEquals("its ends are still half its height", 39f, radius[0], 0.01f);
        float rowBottom = screen.holder.getPivotY() + 0.8f * (114 - screen.holder.getPivotY());
        assertEquals("the row's bottom still lands 8 dp over it", 106f, rowBottom, 0.01f);
        assertEquals("the row of tabs itself keeps its size", 1f, screen.tabBar.getScaleX(), 0f);
        Rect pill = new Rect();
        RoundedTabBar.pillInWindow(screen.holder, pill);
        Rect expected = new Rect(72, 1454, 648, 1516);
        expected.offset(0, screen.windowTop());
        assertEquals("the frost follows the trimmed pill, 576 of 720 pixels wide", expected, pill);

        // Full width: nothing scaled, so nothing lifted, and the whole row.
        Settings.TAB_BAR_WIDTH.save("100");
        Settings.TAB_BAR_HEIGHT.save("");
        screen.shaper.update();
        assertEquals(1f, screen.holder.getScaleX(), 0f);
        assertEquals(1f, screen.holder.getScaleY(), 0f);
        assertEquals(new Rect(0, 2, 720, 114), outlineOf(screen, radius));

        // A number past either end is held to it, and blank is the stock pill again.
        assertEquals(RoundedTabBar.MIN_WIDTH, RoundedTabBar.widthPercent("20"));
        assertEquals(RoundedTabBar.MAX_HEIGHT, RoundedTabBar.heightPercent("150"));
        assertEquals(RoundedTabBar.MIN_HEIGHT, RoundedTabBar.heightPercent("0"));
        assertEquals(RoundedTabBar.DEFAULT_WIDTH, RoundedTabBar.widthPercent("x"));
        Settings.TAB_BAR_WIDTH.save("");
        screen.shaper.update();
        assertEquals(RoundedTabBar.SCALE, screen.holder.getScaleX(), 0f);
        assertEquals(34f, screen.holder.getPivotY(), 0.01f);
    }

    /** #383: the selected tab gets a spot behind it that follows the selection and goes with the pill. */
    @Test
    public void theSelectedTabGetsASpotThatFollowsItAndGoesWithThePill() {
        RoundedTabBarForTests screen = new RoundedTabBarForTests();
        screen.tabBar.removeAllViews();
        FrameLayout[] tabs = new FrameLayout[6];
        for (int i = 0; i < tabs.length; i++) {
            tabs[i] = new FrameLayout(screen.activity);
            tabs[i].addView(new View(screen.activity), new FrameLayout.LayoutParams(48, 48, Gravity.CENTER));
            screen.tabBar.addView(tabs[i], new LinearLayout.LayoutParams(120, 112));
        }
        Drawable ripple = new ColorDrawable(0x00000000);
        tabs[0].setBackground(ripple);
        tabs[0].setSelected(true);
        screen.layOut();
        screen.shaper.update();

        assertTrue("selecting the tab selected its icon too", tabs[0].getChildAt(0).isSelected());
        assertTrue("a layer over the tab's own background", tabs[0].getBackground() instanceof LayerDrawable);
        LayerDrawable layers = (LayerDrawable) tabs[0].getBackground();
        assertSame(ripple, layers.getDrawable(0));
        RoundedTabBar.Spot spot = (RoundedTabBar.Spot) layers.getDrawable(1);
        assertEquals("4 px in at each side, 7 px in above and below, at mdpi", new Rect(4, 7, 116, 105), spot.area);
        assertNull("the icon keeps its own", tabs[0].getChildAt(0).getBackground());
        assertTrue("the other tabs wear a spot too", tabs[1].getBackground() instanceof RoundedTabBar.Spot);
        assertTrue("an empty one", ((RoundedTabBar.Spot) tabs[1].getBackground()).area.isEmpty());

        tabs[0].setSelected(false);
        tabs[3].setSelected(true);
        screen.shaper.update();
        assertSame("the tab left behind keeps its layers", layers, tabs[0].getBackground());
        assertTrue("with its spot emptied", spot.area.isEmpty());
        assertTrue("a tab with none gets the spot alone", tabs[3].getBackground() instanceof RoundedTabBar.Spot);
        assertFalse(((RoundedTabBar.Spot) tabs[3].getBackground()).area.isEmpty());

        tabs[3].setSelected(false);
        screen.shaper.update();
        assertTrue("nothing selected, no spot", ((RoundedTabBar.Spot) tabs[3].getBackground()).area.isEmpty());

        Settings.ROUNDED_TAB_BAR.save(false);
        screen.shaper.update();
        assertSame("off, each tab is Facebook's again", ripple, tabs[0].getBackground());
        assertNull(tabs[3].getBackground());
        assertFalse(screen.holder.getClipToOutline());
    }

    /**
     * #412: the tab's own background is Facebook's press ripple. Putting the layers on the tab makes
     * the view let go of the background they replace, which is that same ripple, so it has to be
     * hooked back into the layers or its fade can't redraw the tab and it freezes as a grey square.
     */
    @Test
    public void theTabsOwnRippleStillRedrawsTheTabUnderTheSpot() {
        RoundedTabBarForTests screen = new RoundedTabBarForTests();
        screen.tabBar.removeAllViews();
        FrameLayout[] tabs = new FrameLayout[5];
        Drawable[] ripples = new Drawable[tabs.length];
        for (int i = 0; i < tabs.length; i++) {
            tabs[i] = new FrameLayout(screen.activity);
            ripples[i] = new ColorDrawable(0x22000000);
            tabs[i].setBackground(ripples[i]);
            screen.tabBar.addView(tabs[i], new LinearLayout.LayoutParams(144, 112));
        }
        tabs[1].setSelected(true);
        screen.layOut();
        screen.shaper.update();

        LayerDrawable layers = (LayerDrawable) tabs[1].getBackground();
        assertSame(ripples[1], layers.getDrawable(0));
        assertSame("the ripple under the spot redraws through the layers", layers, ripples[1].getCallback());
        assertSame("and the layers through the tab", tabs[1], layers.getCallback());
        assertEquals("the ripple shows whenever the layers holding it do", layers.isVisible(), ripples[1].isVisible());

        // Every tab got its layers at once. Going along the row and back swaps none of them, since a
        // swap as a tap selects a tab left its press highlight behind as a grey square.
        Drawable[] worn = new Drawable[tabs.length];
        for (int i = 0; i < tabs.length; i++) {
            worn[i] = tabs[i].getBackground();
            assertTrue("tab " + i + " wears the layers", worn[i] instanceof LayerDrawable);
            assertSame("tab " + i + "'s ripple redraws through them", worn[i], ripples[i].getCallback());
        }
        for (int next : new int[]{2, 3, 4, 0, 1}) {
            for (FrameLayout tab : tabs) tab.setSelected(false);
            tabs[next].setSelected(true);
            screen.shaper.update();
            for (int i = 0; i < tabs.length; i++) {
                assertSame("after selecting tab " + next + ", tab " + i + " keeps its layers", worn[i], tabs[i].getBackground());
                assertSame("and its ripple redraws through them", worn[i], ripples[i].getCallback());
                RoundedTabBar.Spot spot = (RoundedTabBar.Spot) ((LayerDrawable) worn[i]).getDrawable(1);
                assertEquals("tab " + i + "'s spot shows only while it's selected", i == next, !spot.area.isEmpty());
            }
        }

        Settings.ROUNDED_TAB_BAR.save(false);
        screen.shaper.update();
        for (int i = 0; i < tabs.length; i++) {
            assertSame("off, tab " + i + " has its ripple back", ripples[i], tabs[i].getBackground());
            assertSame("and it redraws the tab", tabs[i], ripples[i].getCallback());
        }
    }

    /**
     * #383: 585 draws its 2 pixel line over the top of the row of tabs, inside the pill, as a hard
     * edge. The pill fades it, a frame that puts it back fades it again, and off it's Facebook's.
     */
    @Test
    public void theLineFacebookDrawsOverTheRowFadesWithThePillAndComesBack() {
        RoundedTabBarForTests screen = RoundedTabBarForTests.lineOverTheRow();
        screen.line.setAlpha(0.8f);
        screen.shaper.update();

        float[] radius = new float[1];
        assertEquals("the pill is the row, with the line inside it", new Rect(0, 0, 720, 112), outlineOf(screen, radius));
        assertEquals("the line is faded", 0f, screen.line.getAlpha(), 0f);
        assertEquals("the strip under the row isn't a line", 1f, screen.strip.getAlpha(), 0f);

        screen.line.setAlpha(0.8f);
        screen.shaper.update();
        assertEquals("a frame after Facebook set it again fades it again", 0f, screen.line.getAlpha(), 0f);

        Settings.ROUNDED_TAB_BAR.save(false);
        screen.shaper.update();
        assertFalse(screen.holder.getClipToOutline());
        assertEquals("off, the line has Facebook's alpha back", 0.8f, screen.line.getAlpha(), 0f);
    }

    /** #383: the spot takes a picked Selected tab color over the theme's blue, at the spot's own alpha. */
    @Test
    public void theSpotTakesThePickedSelectedTabColor() {
        int themed = 0x3300897B;
        assertEquals("nothing picked, the theme's blue", themed, RoundedTabBar.spotColor(themed, true));
        Settings.TAB_COLOR.save("#FF8800");
        assertEquals("picked, its colour at the spot's alpha", 0x33FF8800, RoundedTabBar.spotColor(themed, true));
        assertEquals("without Badge and logo colors in the build, the pick is ignored", themed,
                RoundedTabBar.spotColor(themed, false));
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals("paused, the theme's blue", themed, RoundedTabBar.spotColor(themed, true));
        PauseForTests.resume();

        RoundedTabBar.Spot spot = new RoundedTabBar.Spot();
        spot.area.set(0, 0, 40, 20);
        spot.draw(new Canvas(Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)));
        assertEquals("a build with neither theme nor marks draws Facebook's faint blue", RoundedTabBar.SPOT_COLOR, spot.paintColor());
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
