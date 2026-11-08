/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

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

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The bar and time ride on the reel while a finger drags it (#120). The tree copies the S22's Reels
 * tab mid-drag on 585 (2026-11-01): a list of two whole-screen pages, with the scrubber's host beside
 * the list instead of in a page.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w384dp-h823dp-xxhdpi")
public class ReelBarFollowTest {
    private static final int PAGE_HEIGHT = 2041;

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Activity activity;
    private FrameLayout pager;
    private LithoView leaving;
    private LithoView coming;
    private LithoView viewer;
    private FrameLayout host;
    private FrameLayout plugin;
    private FrameLayout label;
    private FrameLayout screen;
    private TabBarContainerLayout tabBar;

    @Before
    public void setUp() {
        ReelBarFollow.resetForTests();
        HookStatus.clear();
        Settings.KEEP_PROGRESS_BAR.save(true);
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        viewer = new LithoView(activity);
        FrameLayout list = new FrameLayout(activity);
        place(viewer, list, 0, 0, 1080, PAGE_HEIGHT);
        pager = new FrameLayout(activity);
        place(list, pager, 0, 0, 1080, PAGE_HEIGHT);
        leaving = new LithoView(activity);
        place(pager, leaving, 0, 0, 1080, PAGE_HEIGHT);
        coming = new LithoView(activity);
        place(pager, coming, 0, PAGE_HEIGHT, 1080, 2 * PAGE_HEIGHT);
        host = new FrameLayout(activity);
        place(viewer, host, 0, 986, 1080, 2066);
        plugin = new FrameLayout(activity);
        place(host, plugin, 0, 0, 1080, 1080);
        label = new FrameLayout(activity);
        place(plugin, label, 0, 918, 291, 1080);
        FrameLayout line = new FrameLayout(activity);
        place(plugin, line, 0, 984, 1080, 1080);
        screen = new FrameLayout(activity);
        place(screen, viewer, 0, 0, 1080, PAGE_HEIGHT);
        activity.setContentView(screen);
        layOut();
    }

    @After
    public void tearDown() {
        PauseForTests.resume();
        Settings.KEEP_PROGRESS_BAR.resetToDefault();
        ReelBarFollow.resetForTests();
        HookStatus.clear();
    }

    @Test
    public void theBarRidesOnItsReelForTheWholeDragAndSettlesBack() {
        ReelBarFollow.updated(label);

        drag(-416);
        assertEquals(-416f, host.getTranslationY(), 0f);
        drag(-400);
        assertEquals(-816f, host.getTranslationY(), 0f);

        drag(816);
        assertEquals("a settled reel has the bar where Facebook puts it", 0f, host.getTranslationY(), 0f);
        assertTrue(HookStatus.report().toString().contains(ReelBarFollow.FOLLOWED + " 1"));
    }

    @Test
    public void atAHandOverTheBarGoesToTheReelComingInAndArrivesWithIt() {
        ReelBarFollow.updated(label);
        frame();
        drag(-1200);

        ReelBarFollow.handedOver(label);
        frame();
        assertEquals("the bar waits at the incoming page, off the screen", (float) PAGE_HEIGHT - 1200, host.getTranslationY(), 0f);

        drag(-(PAGE_HEIGHT - 1200));
        assertEquals("and is in place when that page is", 0f, host.getTranslationY(), 0f);
    }

    @Test
    public void aDragThatTurnsBackHandsTheBarBackToo() {
        ReelBarFollow.updated(label);
        frame();
        drag(-1200);
        ReelBarFollow.handedOver(label);
        frame();

        drag(500);
        ReelBarFollow.handedOver(label);
        frame();

        assertEquals("on the first reel again", -700f, host.getTranslationY(), 0f);
    }

    @Test
    public void aPageThatLeftTheListPassesTheBarToTheOneStillThere() {
        ReelBarFollow.updated(label);
        frame();
        drag(-1200);

        pager.removeView(leaving);
        frame();

        assertEquals((float) PAGE_HEIGHT - 1200, host.getTranslationY(), 0f);
    }

    @Test
    public void facebooksOwnTranslationOfTheHostStays() {
        host.setTranslationY(-50);
        ReelBarFollow.updated(label);

        drag(-416);
        assertEquals(-466f, host.getTranslationY(), 0f);
        drag(416);
        assertEquals(-50f, host.getTranslationY(), 0f);
    }

    @Test
    public void withTheSwitchOffOrPausedTheBarStaysWhereFacebookPutsIt() {
        Settings.KEEP_PROGRESS_BAR.save(false);
        ReelBarFollow.updated(label);
        drag(-416);
        assertEquals(0f, host.getTranslationY(), 0f);
        drag(416);

        Settings.KEEP_PROGRESS_BAR.save(true);
        ReelBarFollow.updated(label);
        drag(-416);
        assertEquals(-416f, host.getTranslationY(), 0f);

        Settings.KEEP_PROGRESS_BAR.save(false);
        frame();
        assertEquals("turning it off mid-drag puts the bar back", 0f, host.getTranslationY(), 0f);

        Settings.KEEP_PROGRESS_BAR.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        frame();
        assertEquals(0f, host.getTranslationY(), 0f);
    }

    @Test
    public void aFeedVideosBarHasNoReelsBesideItAndStays() {
        FrameLayout feedRoot = new FrameLayout(activity);
        FrameLayout feed = new FrameLayout(activity);
        place(feedRoot, feed, 0, 0, 1080, 2000);
        LithoView row = new LithoView(activity);
        place(feed, row, 0, 0, 1080, 600);
        FrameLayout rowHost = new FrameLayout(activity);
        place(feedRoot, rowHost, 0, 0, 1080, 600);
        FrameLayout feedLabel = new FrameLayout(activity);
        place(rowHost, feedLabel, 0, 500, 291, 600);
        activity.setContentView(feedRoot);
        layOut();

        ReelBarFollow.updated(feedLabel);
        row.offsetTopAndBottom(-300);
        frame();

        assertEquals(0f, rowHost.getTranslationY(), 0f);
        assertFalse(HookStatus.report().toString().contains("reel bar follow"));
    }

    @Test
    public void aLineClearOfTheTabBarStaysPut() {
        addTabBar(PAGE_HEIGHT);

        ReelBarFollow.updated(label);
        frame();

        assertEquals(0f, host.getTranslationY(), 0f);
        assertFalse(HookStatus.report().toString().contains(ReelBarFollow.LIFTED));
    }

    @Test
    public void aLineBehindTheTabBarRisesUntilTheThumbShows() {
        // The line sits at 2018, so a tab bar from 1980 covers it, as on the reporter's Reels tab.
        addTabBar(1980);

        ReelBarFollow.updated(label);
        frame();

        float lift = 2018 + 6 * activity.getResources().getDisplayMetrics().density - 1980;
        assertEquals(-lift, host.getTranslationY(), 0f);
        frame();
        assertEquals("and it doesn't keep rising", -lift, host.getTranslationY(), 0f);
        assertTrue(HookStatus.report().toString().contains(ReelBarFollow.LIFTED + " 1"));

        drag(-400);
        assertEquals("it still rides on its reel", -400 - lift, host.getTranslationY(), 0f);
        drag(400);
        assertEquals(-lift, host.getTranslationY(), 0f);
    }

    @Test
    public void theLiftEndsWhenTheTabBarSlidesAwayOrTheSwitchGoesOff() {
        addTabBar(1980);
        ReelBarFollow.updated(label);
        frame();
        assertTrue(host.getTranslationY() < 0f);

        tabBar.setTranslationY(400);
        frame();
        assertEquals(0f, host.getTranslationY(), 0f);

        tabBar.setTranslationY(0);
        frame();
        assertTrue(host.getTranslationY() < 0f);

        Settings.KEEP_PROGRESS_BAR.save(false);
        frame();
        assertEquals(0f, host.getTranslationY(), 0f);
    }

    @Test
    public void aScrubberFacebookMountsInANewHostIsLiftedThere() {
        // See-through bars stretched the page 126 px under the tab bar, and Facebook mounted the
        // scrubber in a new host that much lower (#395). The old host was the one still moving.
        addTabBar(1980);
        ReelBarFollow.updated(label);
        frame();
        assertTrue(host.getTranslationY() < 0f);

        FrameLayout newHost = new FrameLayout(activity);
        host.removeView(plugin);
        viewer.removeView(host);
        place(viewer, newHost, 0, 986 + 126, 1080, 2066 + 126);
        place(newHost, plugin, 0, 0, 1080, 1080);
        layOut();
        frame();
        frame();

        float lift = 2018 + 126 + 6 * activity.getResources().getDisplayMetrics().density - 1980;
        assertEquals("the line clears the tab bar again", -lift, newHost.getTranslationY(), 0f);
        assertEquals("the old host has its place back", 0f, host.getTranslationY(), 0f);
        frame();
        assertEquals("and stays there", -lift, newHost.getTranslationY(), 0f);
        assertTrue(HookStatus.report().toString().contains(ReelBarFollow.REHOSTED + " 1"));
    }

    /**
     * #412: Rounded tab bar or See-through bars stretch the reel under the tabs, so Facebook's own
     * scrubber, up while a reel is paused, sits behind them. With Keep the progress bar off it still
     * rises above the tabs, and only rises: a drag leaves it where Facebook has it.
     */
    @Test
    public void aReelStretchedUnderTheTabsLiftsFacebooksOwnScrubberWithTheBarNotKept() {
        Settings.KEEP_PROGRESS_BAR.save(false);
        addTabBar(1980);
        float lift = 2018 + 6 * activity.getResources().getDisplayMetrics().density - 1980;
        try {
            ReelBarFollow.updated(label);
            frame();
            assertEquals("with no switch that stretches the reel, Facebook's scrubber stays", 0f, host.getTranslationY(), 0f);

            ReelBarFollow.resetForTests();
            Settings.ROUNDED_TAB_BAR.save(true);
            ReelBarFollow.updated(label);
            frame();
            assertEquals("the line and its time clear the rounded bar", -lift, host.getTranslationY(), 0f);
            drag(-400);
            assertEquals("a drag doesn't move it, as the bar isn't kept", -lift, host.getTranslationY(), 0f);
            drag(400);
            assertTrue(HookStatus.report().toString().contains(ReelBarFollow.LIFTED + " 1"));

            Settings.ROUNDED_TAB_BAR.save(false);
            frame();
            assertEquals("turning the rounded bar off gives Facebook its place back", 0f, host.getTranslationY(), 0f);

            Settings.TRANSLUCENT_BARS.save(true);
            frame();
            assertEquals("see-through bars stretch the reel the same way", -lift, host.getTranslationY(), 0f);
        } finally {
            Settings.ROUNDED_TAB_BAR.resetToDefault();
            Settings.TRANSLUCENT_BARS.resetToDefault();
        }
    }

    @Test
    public void aTabBarFacebookBuildsAgainIsTheOneTheLineClears() {
        addTabBar(1980);
        ReelBarFollow.updated(label);
        frame();

        screen.removeView(tabBar);
        addTabBar(1900);
        frame();

        float lift = 2018 + 6 * activity.getResources().getDisplayMetrics().density - 1900;
        assertEquals(-lift, host.getTranslationY(), 0f);
    }

    @Test
    public void aTabBarAtTheTopLeavesTheLineAlone() {
        addTabBar(0);

        ReelBarFollow.updated(label);
        frame();

        assertEquals(0f, host.getTranslationY(), 0f);
    }

    private void addTabBar(int top) {
        tabBar = new TabBarContainerLayout(activity);
        place(screen, tabBar, 0, top, 1080, top + 200);
        layOut();
    }

    private void drag(int by) {
        leaving.offsetTopAndBottom(by);
        coming.offsetTopAndBottom(by);
        frame();
    }

    private void frame() {
        pager.getViewTreeObserver().dispatchOnPreDraw();
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
