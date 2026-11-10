/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;

import com.facebook.feed.tab.FeedTab;
import com.facebook.video.videohome.tab.WatchTab;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.SettingReadsForTests;

/**
 * Hide the tab bar on the Reels tab: with the switch on, selecting Reels hides the bar, the Reels
 * page loses the bar's bottom margin and its reels lose the bar's height, and selecting another tab
 * brings the bar back while the other pages keep their margin. The bar and the margin stay Facebook's with the switch off, paused, before
 * the settings are ready and when the switch can't be read.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReelsTabBarHiddenTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int BAR = 200;

    private View bar;

    @Before
    public void start() {
        HookStatus.clear();
        bar = new View(RuntimeEnvironment.getApplication());
    }

    @After
    public void restore() {
        ReelsTabBarHidden.tabShown(bar, new FeedTab());
        PauseForTests.resume();
        Settings.HIDE_REELS_TAB_BAR.resetToDefault();
        HookStatus.clear();
    }

    private void assertFacebooksBar(String why) {
        ReelsTabBarHidden.tabShown(bar, new WatchTab());
        assertEquals(why, View.VISIBLE, bar.getVisibility());
        assertEquals(why, BAR, ReelsTabBarHidden.pageBottomMargin(BAR, new WatchTab()));
        assertEquals(why, BAR, ReelsTabBarHidden.reelBottomBars(BAR));
    }

    @Test
    public void theSwitchStartsOffInTheTabBarFamilyAndNeedsARestart() {
        assertFalse(Settings.HIDE_REELS_TAB_BAR.defaultValue);
        assertTrue("Facebook sets the Reels page's margin as it's set up", Settings.HIDE_REELS_TAB_BAR.rebootApp);
        assertTrue(PatchFamily.BOTTOM_TAB_BAR.switches.contains(Settings.HIDE_REELS_TAB_BAR));
        assertFacebooksBar("the bar went with the switch off");
    }

    @Test
    public void theReelsTabLosesTheBarAndTheOtherTabsGetItBack() {
        Settings.HIDE_REELS_TAB_BAR.save(true);
        ReelsTabBarHidden.tabShown(bar, new WatchTab());
        assertEquals("the bar stayed on the Reels tab", View.GONE, bar.getVisibility());
        assertEquals("the Reels page kept the bar's margin", 0, ReelsTabBarHidden.pageBottomMargin(BAR, new WatchTab()));
        assertEquals("Home's page lost the bar's margin", BAR, ReelsTabBarHidden.pageBottomMargin(BAR, new FeedTab()));
        assertEquals("the reel stayed short of the hidden bar", 0, ReelsTabBarHidden.reelBottomBars(BAR));

        ReelsTabBarHidden.tabShown(bar, new FeedTab());
        assertEquals("Home didn't get the bar back", View.VISIBLE, bar.getVisibility());
        assertEquals("Home's page lost the bar's margin", BAR, ReelsTabBarHidden.pageBottomMargin(BAR, new FeedTab()));
    }

    /** Facebook sizes the Reels tab's reels at start, while Home is showing and the bar is up. */
    @Test
    public void reelsSizedBeforeTheReelsTabShowsRunToTheBottom() {
        Settings.HIDE_REELS_TAB_BAR.save(true);
        ReelsTabBarHidden.tabShown(bar, new FeedTab());
        assertEquals("Home lost its bar", View.VISIBLE, bar.getVisibility());
        assertEquals("a reel sized before Reels showed stopped short of the bar", 0, ReelsTabBarHidden.reelBottomBars(BAR));
    }

    @Test
    public void aBarFacebookHidItselfStaysHidden() {
        Settings.HIDE_REELS_TAB_BAR.save(true);
        bar.setVisibility(View.GONE);
        ReelsTabBarHidden.tabShown(bar, new FeedTab());
        assertEquals("a bar this didn't hide was shown", View.GONE, bar.getVisibility());
        ReelsTabBarHidden.tabShown(null, new WatchTab());
        ReelsTabBarHidden.tabShown(bar, null);
        assertEquals("a page with no tab lost its margin", BAR, ReelsTabBarHidden.pageBottomMargin(BAR, null));
        assertEquals("a page with no margin got one", 0, ReelsTabBarHidden.pageBottomMargin(0, new WatchTab()));
        assertEquals("a reel with no bars below got a height back", 0, ReelsTabBarHidden.reelBottomBars(0));
    }

    @Test
    public void facebooksBarStandsWhilePausedOrBeforeTheSettingsAreReady() {
        Settings.HIDE_REELS_TAB_BAR.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFacebooksBar("the bar went while paused");
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFacebooksBar("the bar went before the settings were ready"));
    }

    @Test
    public void eachChangeIsCounted() {
        Settings.HIDE_REELS_TAB_BAR.save(true);
        ReelsTabBarHidden.tabShown(bar, new WatchTab());
        ReelsTabBarHidden.pageBottomMargin(BAR, new WatchTab());
        ReelsTabBarHidden.pageBottomMargin(BAR, new WatchTab());
        ReelsTabBarHidden.pageBottomMargin(BAR, new FeedTab());
        ReelsTabBarHidden.reelBottomBars(BAR);
        ReelsTabBarHidden.reelBottomBars(BAR);
        ReelsTabBarHidden.reelBottomBars(BAR);
        String line = line();
        assertNotNull("no " + FamilyNames.BOTTOM_TAB_BAR + " line: " + HookStatus.report(), line);
        assertTrue(line, line.contains(ReelsTabBarHidden.HIDDEN + " 1"));
        assertTrue(line, line.contains(ReelsTabBarHidden.UNPADDED + " 2"));
        assertTrue(line, line.contains(ReelsTabBarHidden.REEL_SIZED + " 3"));
    }

    /** A switch that can't be read leaves Facebook's bar, and the report names the hook. */
    @Test
    public void aSwitchThatCantBeReadLeavesFacebooksBar() {
        SettingReadsForTests.breakReads(Settings.HIDE_REELS_TAB_BAR);
        try {
            assertFacebooksBar("the bar went with a switch that can't be read");
        } finally {
            SettingReadsForTests.mend(Settings.HIDE_REELS_TAB_BAR);
        }
        String missing = HookStatus.missing(FamilyNames.BOTTOM_TAB_BAR).toString();
        assertTrue(missing, missing.contains("a working '" + ReelsTabBarHidden.HOOK + "' hook (it threw "
                + NullPointerException.class.getName() + ")"));
    }

    private static String line() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.BOTTOM_TAB_BAR + ":") && line.contains(ReelsTabBarHidden.HIDDEN)) return line;
        }
        return null;
    }
}
