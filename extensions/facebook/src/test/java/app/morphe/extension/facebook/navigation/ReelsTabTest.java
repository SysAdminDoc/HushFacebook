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

import com.facebook.bookmark.tab.BookmarkTab;
import com.facebook.feed.tab.FeedTab;
import com.facebook.friending.tab.FriendRequestsTab;
import com.facebook.katana.activity.FbMainTabActivity;
import com.facebook.marketplace.tab.MarketplaceTab;
import com.facebook.navigation.tabbar.state.model.TabTag;
import com.facebook.notifications.tab.NotificationsTab;
import com.facebook.timeline.dashboard.tab.TimelineTab;
import com.facebook.video.videohome.tab.WatchTab;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Hide the Reels tab over Facebook's tab bar as its list builder asks the tab bar filter, one tab
 * at a time after Facebook's own hidden-tab set has answered, and the start tab it keeps off a
 * hidden Reels tab.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReelsTabTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** The test account's tab bar, in order, as Facebook configures it. */
    private final List<TabTag> configured = Arrays.asList(new FeedTab(), new WatchTab(), new FriendRequestsTab(),
            new MarketplaceTab(), new NotificationsTab(), new TimelineTab());

    private final List<String> stock = Arrays.asList("FeedTab", "WatchTab", "FriendRequestsTab", "MarketplaceTab",
            "NotificationsTab", "TimelineTab");

    @Before
    public void inBuild() {
        ReelsTabForTests.inBuild(Boolean.TRUE);
        ReelsTab.forget();
        TabBarFilter.clearForTests();
        // The patch is in Morphe Manager's default selection with its switch off; these tests run with it on.
        Settings.HIDE_REELS_TAB.save(true);
    }

    @After
    public void restore() {
        ReelsTabForTests.inBuild(null);
        ReelsTab.forget();
        TabBarFilter.clearForTests();
        MarketplaceOnlyForTests.inBuild(null);
        MarketplaceOnly.forgetLogged();
        StartTabRoute.settled();
        PauseForTests.resume();
        Settings.HIDE_REELS_TAB.resetToDefault();
        Settings.MARKETPLACE_ONLY.resetToDefault();
        Settings.OPEN_ON_CHOSEN_TAB.resetToDefault();
        Settings.START_TAB.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
    }

    /** The tabs Facebook's builder would add, asking the tab bar filter after the hidden set for each. */
    private static List<String> shown(List<? extends TabTag> configured, Set<String> hiddenIds) {
        List<String> shown = new ArrayList<>();
        for (TabTag tab : configured) {
            boolean hidden = hiddenIds.contains(String.valueOf(tab.id));
            if (!TabBarFilter.hidesTab(hidden, tab, configured, hiddenIds)) shown.add(tab.getClass().getSimpleName());
        }
        return shown;
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.REELS_TAB + ":")) return line;
        }
        return null;
    }

    @Test
    public void theSwitchStartsOff() {
        assertFalse(Settings.HIDE_REELS_TAB.defaultValue);
        assertTrue("Facebook builds the bar once, so a change waits for a restart", Settings.HIDE_REELS_TAB.rebootApp);
    }

    @Test
    public void theTabBarLosesOnlyTheReelsTab() {
        assertEquals(Arrays.asList("FeedTab", "FriendRequestsTab", "MarketplaceTab", "NotificationsTab", "TimelineTab"),
                shown(configured, Collections.emptySet()));
        assertEquals(FamilyNames.REELS_TAB + ": invoked 6, 1 found, 0 missing", statusLine());

        // An account with Menu in place of the profile keeps Menu.
        List<TabTag> withMenu = Arrays.asList(new FeedTab(), new WatchTab(), new MarketplaceTab(),
                new NotificationsTab(), new BookmarkTab());
        assertEquals(Arrays.asList("FeedTab", "MarketplaceTab", "NotificationsTab", "BookmarkTab"),
                shown(withMenu, Collections.emptySet()));
    }

    /** Facebook's own Hide stays in charge: the tab stays off, and nothing here claims it. */
    /** The Menu's Reels shortcut and a link to the Reels tab open Reels on its own screen while the tab is off. */
    @Test
    public void theReelsTabsLinksOpenReelsOnItsOwnScreen() {
        shown(configured, Collections.emptySet());
        WatchTab reels = new WatchTab();
        assertNull(TabBarFilter.launchedTab(reels));
        assertFalse(TabBarFilter.configuresTab(true, reels));
        MarketplaceTab marketplace = new MarketplaceTab();
        assertSame(marketplace, TabBarFilter.launchedTab(marketplace));

        TabBarFilter.clearForTests();
        ReelsTab.forget();
        Settings.HIDE_REELS_TAB.save(false);
        shown(configured, Collections.emptySet());
        assertSame("with the switch off the tab is there to switch to", reels, TabBarFilter.launchedTab(reels));
    }

    @Test
    public void aReelsTabFacebookAlreadyHidesIsLeftToFacebook() {
        BaseSettings.DEBUG.save(true);
        Set<String> hidden = Collections.singleton(String.valueOf(FacebookTabs.VIDEO_ID));
        List<String> withoutReels = Arrays.asList("FeedTab", "FriendRequestsTab", "MarketplaceTab", "NotificationsTab",
                "TimelineTab");
        assertEquals(withoutReels, shown(configured, hidden));
        String line = statusLine();
        assertTrue(line, line.contains("0 found, 0 missing"));
        String report = LogBufferManager.buildExportText();
        assertEquals(report, 0, occurrences(report, "took WatchTab off the tab bar"));
        assertEquals(report, 1, occurrences(report, "Facebook's own tab bar settings already hide the Reels tab"));

        // Switched off, Facebook's Hide still hides it: this never puts back a tab Facebook took off.
        Settings.HIDE_REELS_TAB.save(false);
        assertEquals(withoutReels, shown(configured, hidden));
        assertFalse("a start is kept off a tab this didn't take off", ReelsTab.offTheBar());
    }

    @Test
    public void offPausedOrNotInTheBuildTheTabBarIsFacebooks() {
        Settings.HIDE_REELS_TAB.save(false);
        assertEquals(stock, shown(configured, Collections.emptySet()));
        Settings.HIDE_REELS_TAB.save(true);

        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            assertEquals(why.name(), stock, shown(configured, Collections.emptySet()));
        }
        PauseForTests.resume();

        ReelsTabForTests.inBuild(Boolean.FALSE);
        assertEquals(stock, shown(configured, Collections.emptySet()));
        ReelsTabForTests.inBuild(Boolean.TRUE);

        boolean[] hides = {true};
        SettingsContextRule.withoutContext(() -> hides[0] = ReelsTabForTests.hidesTheTab());
        assertFalse("a tab bar built before the context lost the Reels tab", hides[0]);
        assertTrue(ReelsTabForTests.hidesTheTab());
        assertFalse(ReelsTab.hidesTab(false, null));
        assertTrue("Facebook's own answer for a missing tab", ReelsTab.hidesTab(true, null));
    }

    /** With Marketplace only on as well, the tab bar is Marketplace only's, and both rules agree on Video. */
    @Test
    public void marketplaceOnlyAndTheReelsTabShareOneTabBar() {
        MarketplaceOnlyForTests.inBuild(Boolean.TRUE);
        Settings.MARKETPLACE_ONLY.save(true);
        assertEquals(Arrays.asList("MarketplaceTab", "NotificationsTab", "TimelineTab"),
                shown(configured, Collections.emptySet()));
        Settings.HIDE_REELS_TAB.save(false);
        MarketplaceOnly.forgetLogged();
        assertEquals(Arrays.asList("MarketplaceTab", "NotificationsTab", "TimelineTab"),
                shown(configured, Collections.emptySet()));
        Settings.MARKETPLACE_ONLY.save(false);
        Settings.HIDE_REELS_TAB.save(true);
        assertEquals(Arrays.asList("FeedTab", "FriendRequestsTab", "MarketplaceTab", "NotificationsTab", "TimelineTab"),
                shown(configured, Collections.emptySet()));
    }

    @Test
    public void debugLoggingSaysOnceThatTheTabWentOff() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        shown(configured, Collections.emptySet());
        shown(configured, Collections.emptySet());
        String report = LogBufferManager.buildExportText();
        assertEquals(report, 1, occurrences(report, "Reels tab: took WatchTab off the tab bar."));
    }

    /**
     * A start sent to a chosen Reels tab goes to Home while the tab is off the bar, because of the
     * switch or because the bar this process built lost it, and to the Reels tab otherwise.
     */
    @Test
    public void aChosenReelsTabOpensHomeWhileTheSwitchKeepsItOffTheBar() {
        Settings.OPEN_ON_CHOSEN_TAB.save(true);
        Settings.START_TAB.save(StartTab.VIDEO);
        BaseSettings.DEBUG.save(true);
        assertEquals("no bar built yet: the switch decides", FacebookTabs.HOME_ID, askedFor());
        String report = LogBufferManager.buildExportText();
        assertEquals(report, 1, occurrences(report, "Hide the Reels tab keeps video off the tab bar, so asked for home"));

        Settings.HIDE_REELS_TAB.save(false);
        assertEquals(FacebookTabs.VIDEO_ID, askedFor());
        ReelsTabForTests.inBuild(Boolean.FALSE);
        Settings.HIDE_REELS_TAB.save(true);
        assertEquals("a build without the patch", FacebookTabs.VIDEO_ID, askedFor());
        ReelsTabForTests.inBuild(Boolean.TRUE);

        // The bar this process built lost the tab: it stays off until Facebook restarts, whatever
        // the switch says now.
        shown(configured, Collections.emptySet());
        Settings.HIDE_REELS_TAB.save(false);
        assertEquals(FacebookTabs.HOME_ID, askedFor());

        // The bar this process built kept it: the tab is there until Facebook restarts.
        ReelsTab.forget();
        shown(configured, Collections.emptySet());
        Settings.HIDE_REELS_TAB.save(true);
        assertEquals(FacebookTabs.VIDEO_ID, askedFor());

        // Other tabs aren't touched.
        ReelsTab.forget();
        Settings.START_TAB.save(StartTab.FRIENDS);
        assertEquals(FacebookTabs.FRIENDS_ID, askedFor());
    }

    /**
     * A bar that opens on Reels, as Meta tests with Home second (2026), one that opens on a tab this
     * build doesn't know, and one with Reels in Home's place. With this switch and every Hide tabs
     * switch on, the bar keeps a tab to open, Home where it has one, and the unknown tab stays put.
     * A start chosen for a tab that went off asks for Home, never the hidden tab.
     */
    @Test
    public void aBarThatOpensOnReelsNeverEndsUpEmptyOrOpensOnAHiddenTab() {
        HiddenTabs.inBuildForTests = Boolean.TRUE;
        try {
            for (HiddenTabs.Tab tab : HiddenTabs.Tab.values()) tab.setting().save(true);
            Settings.OPEN_ON_CHOSEN_TAB.save(true);
            List<TabTag> reelsFirst = Arrays.asList(new WatchTab(), new FeedTab(), new FriendRequestsTab(),
                    new MarketplaceTab(), new NotificationsTab(), new TimelineTab());
            assertEquals(Arrays.asList("FeedTab", "NotificationsTab", "TimelineTab"),
                    shown(reelsFirst, Collections.emptySet()));
            Settings.START_TAB.save(StartTab.VIDEO);
            assertEquals(FacebookTabs.HOME_ID, askedFor());
            Settings.START_TAB.save(StartTab.FRIENDS);
            assertEquals(FacebookTabs.HOME_ID, askedFor());
            Settings.START_TAB.save(StartTab.HOME);
            assertEquals(FacebookTabs.HOME_ID, askedFor());

            ReelsTab.forget();
            HiddenTabs.clearForTests();
            TabBarFilter.clearForTests();
            List<TabTag> unknownFirst = Arrays.asList(new StartTabRouteForTests.UnknownTab(), new FeedTab(),
                    new WatchTab(), new FriendRequestsTab(), new BookmarkTab());
            assertEquals(Arrays.asList("UnknownTab", "FeedTab", "BookmarkTab"), shown(unknownFirst, Collections.emptySet()));

            ReelsTab.forget();
            HiddenTabs.clearForTests();
            TabBarFilter.clearForTests();
            List<TabTag> noHome = Arrays.asList(new WatchTab(), new FriendRequestsTab(), new NotificationsTab(),
                    new BookmarkTab());
            assertEquals(Arrays.asList("NotificationsTab", "BookmarkTab"), shown(noHome, Collections.emptySet()));
            Settings.START_TAB.save(StartTab.VIDEO);
            assertEquals(FacebookTabs.HOME_ID, askedFor());
        } finally {
            for (HiddenTabs.Tab tab : HiddenTabs.Tab.values()) tab.setting().resetToDefault();
            HiddenTabs.inBuildForTests = null;
            HiddenTabs.clearForTests();
        }
    }

    @Test
    public void pausedTheChosenReelsTabIsNotAskedForAtAll() {
        Settings.OPEN_ON_CHOSEN_TAB.save(true);
        Settings.START_TAB.save(StartTab.VIDEO);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals(-1, askedFor());
    }

    /** The tab the main screen started from the launcher icon asks Facebook for, or -1. */
    private static long askedFor() {
        FbMainTabActivity screen = StartTabRouteForTests.screen(StartTabRouteForTests.launcherStart());
        StartTabRoute.onActivityCreate(screen, null);
        StartTabRoute.settled();
        return screen.getIntent().getLongExtra(FacebookTabs.TARGET_TAB_ID, -1);
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) count++;
        return count;
    }
}
