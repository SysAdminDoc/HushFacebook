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
import com.facebook.marketplace.tab.MarketplaceTab;
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

import app.morphe.extension.facebook.navigation.TabOrder.Tab;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Tab order over the shown tabs Facebook's tab bar builder hands it before it keeps them (#341),
 * and the order the Tab order row lists and saves.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class TabOrderTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final Object home = new FeedTab();
    private final Object video = new WatchTab();
    private final Object friends = new FriendRequestsTab();
    private final Object marketplace = new MarketplaceTab();
    private final Object notifications = new NotificationsTab();
    private final Object menu = new BookmarkTab();
    /** A tab this build doesn't know, which keeps its place. */
    private final Object unknown = new Object();

    private final List<Object> bar = Collections.unmodifiableList(
            Arrays.asList(home, video, friends, unknown, marketplace, notifications, menu));

    @Before
    public void inBuild() {
        HiddenTabs.inBuildForTests = Boolean.TRUE;
        TabOrder.clearForTests();
    }

    @After
    public void restore() {
        HiddenTabs.inBuildForTests = null;
        TabOrder.clearForTests();
        PauseForTests.resume();
        Settings.TAB_ORDER.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
    }

    @Test
    public void theSavedTabsGoFirstInTheirOrderAndAnUnknownTabKeepsItsPlace() {
        Settings.TAB_ORDER.save("friends,menu,home");
        List<?> ordered = TabOrder.order(bar);
        assertEquals(Arrays.asList(friends, menu, home, unknown, video, marketplace, notifications), ordered);
        String status = String.join("\n", HookStatus.report());
        assertTrue("the hook's report doesn't count the order going in: " + status,
                status.contains(FamilyNames.HIDDEN_TABS + ": ") && status.contains(" 1 found"));
        assertEquals("Facebook's list was changed", Arrays.asList(home, video, friends, unknown, marketplace,
                notifications, menu), bar);
    }

    /**
     * A bar that opens on Reels, as Meta tests with Home second (2026), and one that opens on a tab
     * this build doesn't know: the saved tabs still go first in their order, the unknown tab keeps
     * its place, and Debug names each first tab that isn't Home once, saved order or not. A bar
     * built while Debug was off doesn't use that once up.
     */
    @Test
    public void aBarThatDoesNotStartWithHomeKeepsTheSavedOrderAndIsLoggedOnce() {
        List<Object> reelsFirst = Arrays.asList(video, home, friends, marketplace, notifications, menu);
        List<Object> unknownFirst = Arrays.asList(unknown, home, video, friends, menu);
        BaseSettings.DEBUG.save(false);
        assertSame("no order saved", reelsFirst, TabOrder.order(reelsFirst));
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        assertSame("no order saved", reelsFirst, TabOrder.order(reelsFirst));

        Settings.TAB_ORDER.save("friends,home");
        assertEquals(Arrays.asList(friends, home, video, marketplace, notifications, menu), TabOrder.order(reelsFirst));
        assertEquals(Arrays.asList(unknown, friends, home, video, menu), TabOrder.order(unknownFirst));
        TabOrder.order(reelsFirst);
        TabOrder.order(unknownFirst);
        TabOrder.order(bar);

        String report = LogBufferManager.buildExportText();
        assertEquals(report, 1, occurrences(report, "Tab order: Facebook built a tab bar whose first tab is "
                + FacebookTabs.VIDEO_CLASS + ", not Home."));
        assertEquals(report, 1, occurrences(report, "Tab order: Facebook built a tab bar whose first tab is "
                + Object.class.getName() + ", not Home, a tab this build doesn't know, so it keeps its place."));
        assertEquals("a bar starting with Home was logged: " + report, 2, occurrences(report, "whose first tab is"));
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) count++;
        return count;
    }

    @Test
    public void theProfileTabTakesMenusPlace() {
        Object profile = new TimelineTab();
        Settings.TAB_ORDER.save("menu,home");
        assertEquals(Arrays.asList(profile, home, friends), TabOrder.order(Arrays.asList(home, friends, profile)));
    }

    @Test
    public void withNothingToMoveTheBarIsFacebooksOwnList() {
        assertSame("no order saved", bar, TabOrder.order(bar));
        Settings.TAB_ORDER.save("home,video");
        assertSame("the saved order is the bar's own", bar, TabOrder.order(bar));
        Settings.TAB_ORDER.save("dating,jobs");
        assertSame("the saved tabs aren't on the bar", bar, TabOrder.order(bar));
        assertNull(TabOrder.order(null));
        List<Object> empty = new ArrayList<>();
        assertSame(empty, TabOrder.order(empty));
    }

    @Test
    public void pausedOrOutOfTheBuildTheBarIsFacebooks() {
        Settings.TAB_ORDER.save("menu,friends");
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertSame(bar, TabOrder.order(bar));
        PauseForTests.resume();
        HiddenTabs.inBuildForTests = Boolean.FALSE;
        assertSame(bar, TabOrder.order(bar));
        HiddenTabs.inBuildForTests = Boolean.TRUE;
        assertEquals(menu, TabOrder.order(bar).get(0));
    }

    @Test
    public void aSavedOrderReadsBackCleanOnly() {
        assertEquals(Arrays.asList(Tab.FRIENDS, Tab.HOME), TabOrder.parse("friends, home,reels,friends,,"));
        assertEquals(Collections.emptyList(), TabOrder.parse(null));
        assertEquals("friends,home", TabOrder.clean("friends, home,reels,friends"));
        assertTrue(TabOrder.isClean("friends,home"));
        assertTrue(TabOrder.isClean(""));
        assertFalse(TabOrder.isClean("friends,home,"));
        assertFalse(TabOrder.isClean("Friends"));
        assertFalse(TabOrder.isClean(null));
        for (Tab tab : Tab.values()) assertSame(tab, Tab.forKey(tab.key));
    }

    @Test
    public void theRowListsTheBarInTheOrderItWillHave() {
        // Before Facebook builds a bar here, the saved tabs and the usual ones.
        assertEquals(Arrays.asList(Tab.DATING, Tab.MENU, Tab.HOME, Tab.VIDEO, Tab.FRIENDS, Tab.MARKETPLACE,
                Tab.NOTIFICATIONS), TabOrder.rowTabs(Arrays.asList(Tab.DATING, Tab.MENU)));

        // After, the bar's own tabs only, even with no order saved.
        TabOrder.order(Arrays.asList(home, marketplace, menu));
        assertEquals(Arrays.asList(Tab.HOME, Tab.MARKETPLACE, Tab.MENU), TabOrder.rowTabs(Collections.emptyList()));
        assertEquals(Arrays.asList(Tab.MENU, Tab.HOME, Tab.MARKETPLACE),
                TabOrder.rowTabs(Arrays.asList(Tab.DATING, Tab.MENU)));

        // A saved tab that's off the bar for now keeps its place after the ones the row listed.
        assertEquals(Arrays.asList(Tab.HOME, Tab.MENU, Tab.MARKETPLACE, Tab.DATING),
                TabOrder.merged(Arrays.asList(Tab.HOME, Tab.MENU, Tab.MARKETPLACE), Arrays.asList(Tab.DATING, Tab.MENU)));
        assertEquals("home,menu,marketplace,dating", TabOrder.stored(TabOrder.merged(
                Arrays.asList(Tab.HOME, Tab.MENU, Tab.MARKETPLACE), Arrays.asList(Tab.DATING, Tab.MENU))));
    }
}
