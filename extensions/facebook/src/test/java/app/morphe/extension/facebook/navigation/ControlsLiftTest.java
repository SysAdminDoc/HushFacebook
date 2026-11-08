/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * #412: with Rounded tab bar or See-through bars on, the Reels tab's page runs under the tab bar,
 * and the time and seek bar Facebook pins to the viewer's bottom went behind the pill with it.
 * They sit where the pill starts now, drop back when the tabs go away, and get Facebook's place back
 * with the switches off. A feed video's own controls stay with its video.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ControlsLiftTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.TRANSLUCENT_BARS.resetToDefault();
        Settings.ROUNDED_TAB_BAR.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.BOTTOM_TAB_BAR + ":")) return line;
        }
        return null;
    }

    private static int bottomOnScreen(View view) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        return at[1] + view.getHeight();
    }

    private static int topOnScreen(View view) {
        Rect drawn = new Rect();
        assertTrue(view.getGlobalVisibleRect(drawn));
        return drawn.top;
    }

    /** The Reels viewer seen on 585: the list of reels filling it, and beside it the controls' host pinned to its bottom. */
    private static final class Reels {
        final View list;
        final FrameLayout host;
        final SeekBar bar;

        Reels(TranslucentBarsForTests screen) {
            FrameLayout viewer = new FrameLayout(screen.activity);
            list = new View(screen.activity);
            viewer.addView(list, new FrameLayout.LayoutParams(MATCH, MATCH));
            host = new FrameLayout(screen.activity);
            bar = new SeekBar(screen.activity);
            host.addView(bar, new FrameLayout.LayoutParams(MATCH, 48, Gravity.BOTTOM));
            viewer.addView(host, new FrameLayout.LayoutParams(MATCH, 480, Gravity.BOTTOM));
            screen.page.addView(viewer, new FrameLayout.LayoutParams(MATCH, MATCH));
            screen.layOut();
        }
    }

    @Test
    @Config(qualifiers = "w720dp-h1800dp")
    public void aReelsSeekBarSitsWhereTheRoundedTabBarStarts() {
        Settings.ROUNDED_TAB_BAR.save(true);
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        RoundedTabBar.Shaper shaper = new RoundedTabBar.Shaper(screen.window, TranslucentBarsForTests.TabBar.class.getName());
        Reels reels = new Reels(screen);
        shaper.update();
        int pillTop = topOnScreen(screen.tabBar);
        assertTrue("the seek bar starts out behind the pill", bottomOnScreen(reels.bar) > pillTop);
        assertSame(reels.host, ControlsLift.hostOf(reels.bar, screen.window.getHeight()));

        screen.bars.update();
        assertEquals("the controls end where the pill starts", pillTop, bottomOnScreen(reels.host));
        assertEquals(pillTop, bottomOnScreen(reels.bar));
        assertEquals("the reels themselves stay put", 0f, reels.list.getTranslationY(), 0f);
        assertTrue(statusLine(), statusLine().contains(ControlsLift.LIFTED + " 1"));

        screen.tabHolder.setVisibility(View.GONE);
        screen.bars.update();
        assertEquals("back at the bottom with the tabs gone", 0f, reels.host.getTranslationY(), 0f);

        screen.tabHolder.setVisibility(View.VISIBLE);
        screen.layOut();
        screen.bars.update();
        assertEquals("above them again when they come back", pillTop, bottomOnScreen(reels.host));

        Settings.ROUNDED_TAB_BAR.save(false);
        screen.bars.update();
        assertEquals("Facebook's place back with the switch off", 0f, reels.host.getTranslationY(), 0f);
    }

    @Test
    @Config(qualifiers = "w720dp-h1800dp")
    public void seeThroughBarsLiftThemToo() {
        Settings.TRANSLUCENT_BARS.save(true);
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        Reels reels = new Reels(screen);
        int tabsTop = topOnScreen(screen.tabBar);

        screen.bars.update();
        assertEquals(tabsTop, bottomOnScreen(reels.host));
        assertEquals("by the tab bar's height", -160f, reels.host.getTranslationY(), 0f);

        Settings.TRANSLUCENT_BARS.save(false);
        screen.bars.update();
        assertEquals(0f, reels.host.getTranslationY(), 0f);
    }

    @Test
    @Config(qualifiers = "w720dp-h1800dp")
    public void aFeedVideosControlsStayWithItsVideo() {
        Settings.TRANSLUCENT_BARS.save(true);
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        LinearLayout feed = new LinearLayout(screen.activity);
        feed.setOrientation(LinearLayout.VERTICAL);
        feed.addView(new View(screen.activity), new LinearLayout.LayoutParams(MATCH, 1100));
        FrameLayout player = new FrameLayout(screen.activity);
        player.addView(new View(screen.activity), new FrameLayout.LayoutParams(MATCH, MATCH));
        FrameLayout controls = new FrameLayout(screen.activity);
        SeekBar bar = new SeekBar(screen.activity);
        controls.addView(bar, new FrameLayout.LayoutParams(MATCH, 48, Gravity.BOTTOM));
        player.addView(controls, new FrameLayout.LayoutParams(MATCH, 120, Gravity.BOTTOM));
        feed.addView(player, new LinearLayout.LayoutParams(MATCH, 405));
        screen.page.addView(feed, new FrameLayout.LayoutParams(MATCH, MATCH));
        screen.layOut();
        assertTrue("the video's bar is under the tab bar", bottomOnScreen(bar) > topOnScreen(screen.tabBar));

        screen.bars.update();
        assertNull(ControlsLift.hostOf(bar, screen.window.getHeight()));
        assertEquals(0f, controls.getTranslationY(), 0f);
        assertEquals(0f, player.getTranslationY(), 0f);
    }
}
