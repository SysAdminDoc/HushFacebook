/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import com.facebook.feed.tab.FeedTab;
import com.facebook.friending.tab.FriendRequestsTab;

import java.util.Arrays;
import java.util.List;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;

public final class HiddenTabsForTests {
    private HiddenTabsForTests() {
    }

    /**
     * Asks the hook about the Friends tab, which Facebook's own settings don't hide, with the patch
     * in the build. True when it takes the tab off, which is a switch changing what Facebook would
     * have done.
     */
    public static boolean hidesTheTab() {
        Boolean before = HiddenTabs.inBuildForTests;
        HiddenTabs.inBuildForTests = Boolean.TRUE;
        try {
            return HiddenTabs.hidesTab(false, new FriendRequestsTab());
        } finally {
            HiddenTabs.inBuildForTests = before;
            HiddenTabs.clearForTests();
        }
    }

    /**
     * Hands the tab order hook a bar of Home then Friends with the patch in the build and, once the
     * settings are ready, Friends saved first (#341). True when the bar it hands back is in a new
     * order. Before the settings are ready nothing here touches them, as the hook mustn't either.
     */
    public static boolean reordersTheBar() {
        Boolean before = HiddenTabs.inBuildForTests;
        HiddenTabs.inBuildForTests = Boolean.TRUE;
        boolean ready = Utils.settingsReady();
        try {
            if (ready) Settings.TAB_ORDER.save("friends");
            List<Object> bar = Arrays.asList(new FeedTab(), new FriendRequestsTab());
            return TabOrder.order(bar) != bar;
        } finally {
            if (ready) Settings.TAB_ORDER.resetToDefault();
            HiddenTabs.inBuildForTests = before;
            TabOrder.clearForTests();
        }
    }
}
