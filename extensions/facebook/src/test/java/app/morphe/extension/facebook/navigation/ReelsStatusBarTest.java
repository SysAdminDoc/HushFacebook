/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import com.facebook.feed.tab.FeedTab;
import com.facebook.katana.activity.FbMainTabActivity;
import com.facebook.video.videohome.tab.WatchTab;

import java.lang.ref.WeakReference;
import java.time.Duration;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.ShimmerTitleTest;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * #466: with the tab bar at the bottom and See-through bars on, the Reels tab runs its reel up
 * behind the status bar. Facebook's padding for the bar is answered 0 on the main screen while the
 * Reels tab shows, and its own on every other tab, in a window over the main screen, with the
 * switch off, the tabs at the top or Hushfacebook paused. The window's insets are asked for again
 * once per change, the Reels header makes room for the bar, and a Reels row made while the strip
 * is open gets the room it closes over.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReelsStatusBarTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int BAR = 75;

    private Activity main;
    private View content;
    private Asks tabBar;

    /** A view counting the times its window's insets are asked for again. */
    static final class Asks extends View {
        int asked;

        Asks(Context context) {
            super(context);
        }

        @Override
        public void requestApplyInsets() {
            asked++;
            super.requestApplyInsets();
        }
    }

    @Before
    public void start() {
        HookStatus.clear();
        ReelsStatusBar.resetForTests();
        main = Robolectric.buildActivity(FbMainTabActivity.class).setup().get();
        content = main.findViewById(android.R.id.content);
        tabBar = new Asks(main);
        Settings.TRANSLUCENT_BARS.save(true);
        Settings.BOTTOM_TAB_BAR.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.TRANSLUCENT_BARS.resetToDefault();
        Settings.BOTTOM_TAB_BAR.resetToDefault();
        Settings.TOP_TAB_BAR.resetToDefault();
        Settings.HIDE_STATUS_BAR.resetToDefault();
        Settings.HIDE_REELS_TAB_BAR.resetToDefault();
        ReelsTabBarHidden.forgetWindow();
        TabBarScrollAway.Stored.BAR_ROOM.resetToDefault();
        TabBarScrollAway.Stored.WIDE_BAR_ROOM.resetToDefault();
        ReelsHeaderClear.resetForTests();
        TabBarScrollAway.mainScreenCreated(null);
        TabBarScrollAway.forgetBuilt();
        TabBarScrollAway.reelsList = new WeakReference<>(null);
        ReelsStatusBar.resetForTests();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.BOTTOM_TAB_BAR + ":")) return line;
        }
        return null;
    }

    @Test
    public void theReelsTabClosesTheStripAndTheOtherTabsOpenIt() {
        ReelsTabBarHidden.tabShown(tabBar, new WatchTab());
        assertEquals("the window's insets asked for again", 1, tabBar.asked);
        assertEquals("the reel runs up behind the status bar", 0, HiddenStatusBar.topInset(content, BAR));
        assertTrue(ReelsStatusBar.closed());
        String line = statusLine();
        assertTrue(line, line != null && line.contains(ReelsStatusBar.CLOSED + " 1"));

        ReelsTabBarHidden.tabShown(tabBar, new WatchTab());
        assertEquals("the same tab again asks nothing", 1, tabBar.asked);

        ReelsTabBarHidden.tabShown(tabBar, new FeedTab());
        assertEquals(2, tabBar.asked);
        assertEquals("Home keeps the strip", BAR, HiddenStatusBar.topInset(content, BAR));
        assertFalse(ReelsStatusBar.closed());
    }

    /**
     * On the S22 (588) Facebook still names its top bar's entry for the title and the tabs with the
     * tabs moved to the bottom, which reads as tabs on top, and the strip never closed.
     */
    @Test
    public void tabsMovedDownCloseItWhateverFacebookNamesItsTopEntry() {
        TabBarScrollAway.topBarFade(TabBarScrollAway.TITLE_AND_TABS, 0f);
        assertTrue("Facebook's entry reads as tabs on top", TabBarScrollAway.tabsOnTop());
        ReelsTabBarHidden.tabShown(tabBar, new WatchTab());
        assertEquals(1, tabBar.asked);
        assertEquals(0, HiddenStatusBar.topInset(content, BAR));
    }

    @Test
    public void theStripStaysWithTheSwitchOffTheTabsAtTheTopOrPaused() {
        Settings.TRANSLUCENT_BARS.save(false);
        ReelsTabBarHidden.tabShown(tabBar, new WatchTab());
        assertEquals("switch off", BAR, HiddenStatusBar.topInset(content, BAR));
        assertEquals(0, tabBar.asked);

        Settings.TRANSLUCENT_BARS.save(true);
        Settings.BOTTOM_TAB_BAR.save(false);
        Settings.TOP_TAB_BAR.save(true);
        ReelsStatusBar.frame(tabBar);
        assertEquals("tabs at the top", BAR, HiddenStatusBar.topInset(content, BAR));
        assertEquals(0, tabBar.asked);

        Settings.TOP_TAB_BAR.save(false);
        Settings.BOTTOM_TAB_BAR.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        ReelsStatusBar.frame(tabBar);
        assertEquals("paused", BAR, HiddenStatusBar.topInset(content, BAR));
        assertEquals(0, tabBar.asked);

        PauseForTests.resume();
        ReelsStatusBar.frame(tabBar);
        assertEquals("back on, the frame asks", 1, tabBar.asked);
        assertEquals(0, HiddenStatusBar.topInset(content, BAR));

        Settings.TRANSLUCENT_BARS.save(false);
        ReelsStatusBar.frame(tabBar);
        assertEquals("the switch off on the Reels tab opens it again", 2, tabBar.asked);
        assertEquals(BAR, HiddenStatusBar.topInset(content, BAR));
        assertFalse(ReelsStatusBar.closed());
    }

    @Test
    public void aWindowOverTheMainScreenOrAnotherScreenKeepsItsStrip() {
        ReelsTabBarHidden.tabShown(tabBar, new WatchTab());
        Dialog sheet = new Dialog(main);
        sheet.setContentView(new View(main));
        sheet.show();
        assertEquals("the comment sheet pads for its own bar", BAR,
                HiddenStatusBar.topInset(sheet.findViewById(android.R.id.content), BAR));
        sheet.dismiss();
        Activity other = Robolectric.buildActivity(Activity.class).setup().get();
        assertEquals("a reel opened full screen", BAR, HiddenStatusBar.topInset(other.findViewById(android.R.id.content), BAR));
        assertEquals("the main screen's own", 0, HiddenStatusBar.topInset(content, BAR));
    }

    @Test
    public void startingOnTheReelsTabClosesItBeforeATabIsPicked() {
        ((FbMainTabActivity) main).currentTab = new WatchTab();
        TabBarScrollAway.mainScreenCreated(main);
        ReelsStatusBar.frame(tabBar);
        assertEquals(1, tabBar.asked);
        assertEquals(0, HiddenStatusBar.topInset(content, BAR));

        ReelsTabBarHidden.tabShown(tabBar, new FeedTab());
        ReelsStatusBar.frame(tabBar);
        assertEquals("once a tab's picked, that's the one", 2, tabBar.asked);
        assertEquals(BAR, HiddenStatusBar.topInset(content, BAR));
    }

    @Test
    public void hideTheStatusBarStillClosesItsOwnStrip() {
        ReelsTabBarHidden.tabShown(tabBar, new WatchTab());
        Settings.HIDE_STATUS_BAR.save(true);
        HiddenStatusBar.Checks checks = HiddenStatusBar.attach(main.getWindow());
        checks.check();
        assertEquals("a bar asked away", 0, HiddenStatusBar.topInset(content, BAR));
        assertFalse("its header needn't make room", ReelsStatusBar.closed());
    }

    @Test
    @Config(qualifiers = "w720dp-h1800dp")
    public void theReelsHeaderGoesBelowTheStatusBarWhileTheStripIsClosed() {
        ReelsStatusBar.statusBarForTests = BAR;
        FrameLayout page = new FrameLayout(main);
        View header = new View(main);
        page.addView(header, new FrameLayout.LayoutParams(1080, 338, Gravity.TOP));
        ((FrameLayout) content).addView(page, new FrameLayout.LayoutParams(1080, 2000, Gravity.TOP));
        layOut();
        int[] at = new int[2];
        page.getLocationInWindow(at);
        int pageTop = at[1];
        ReelsHeaderClear.headerMade(header);
        frame();
        assertEquals("Home's strip open: Facebook's place", 0, header.getTop());

        ReelsTabBarHidden.tabShown(tabBar, new WatchTab());
        HiddenStatusBar.topInset(content, BAR);
        frame();
        assertEquals("below the time and the battery", Math.max(0, BAR - pageTop), header.getTop());

        ReelsTabBarHidden.tabShown(tabBar, new FeedTab());
        HiddenStatusBar.topInset(content, BAR);
        frame();
        assertEquals("back with the strip open", 0, header.getTop());
    }

    @Test
    @Config(qualifiers = "w720dp-h1800dp")
    public void aReelsRowMadeWithTheStripOpenGetsTheRoomItClosesOver() throws Exception {
        ReelsStatusBar.statusBarForTests = BAR;
        FrameLayout page = new FrameLayout(main);
        View list = new View(main);
        FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(1080, 1000, Gravity.TOP);
        at.topMargin = 200;
        page.addView(list, at);
        ((FrameLayout) content).addView(page, new FrameLayout.LayoutParams(1080, 1600, Gravity.TOP));
        layOut();
        ShimmerTitleTest.appVisible(list, true);
        TabBarScrollAway.reelsList = new WeakReference<>(list);
        assertEquals("on Home, the list and the strip it'll close over", 1000 + BAR, TabBarScrollAway.reelsListHeight());
        int setup = TabBarScrollAway.barSetup();

        ReelsTabBarHidden.tabShown(tabBar, new WatchTab());
        HiddenStatusBar.topInset(content, BAR);
        assertEquals("closed, the list has that room itself", 1000, TabBarScrollAway.reelsListHeight());

        Settings.TRANSLUCENT_BARS.save(false);
        HiddenStatusBar.topInset(content, BAR);
        assertEquals("switch off, the list as it is", 1000, TabBarScrollAway.reelsListHeight());
        assertNotEquals("a height seen before isn't handed to these rows", setup, TabBarScrollAway.barSetup());
    }

    private void frame() {
        content.getViewTreeObserver().dispatchOnPreDraw();
        layOut();
    }

    private void layOut() {
        main.getWindow().getDecorView().requestLayout();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
    }
}
