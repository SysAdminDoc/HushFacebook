/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.net.Uri;

import com.facebook.feed.tab.FeedTab;
import com.facebook.katana.activity.FbMainTabActivity;
import com.facebook.marketplace.tab.MarketplaceTab;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.SettingsEntry;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The Feeds page a start that chose Feeds opens over Home: started on Facebook's screen for pages
 * that aren't tabs, the way the Menu's Feeds row starts it. On 582 Facebook's link map hands the
 * page's own links Home's launch link, and Facebook switches to Home instead of opening the page.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FeedsPageScreenTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        HookStatus.clear();
    }

    @Test
    public void theFeedsPageOpensOnFacebooksPageScreenWithTheMenuRowsExtras() {
        ActivityController<FbMainTabActivity> controller =
                Robolectric.buildActivity(FbMainTabActivity.class, StartTabRouteForTests.launcherStart());
        FbMainTabActivity screen = controller.get();
        StartTabRouteForTests.tabBar(screen, StartTabRouteForTests.tabs(new FeedTab(), new MarketplaceTab()), null);
        screen.currentTab = new FeedTab();
        controller.create().start().resume().visible();

        StartTabRoute.openFeedsPage(screen, StartTab.FEEDS, StartTab.FEEDS, true);

        Intent page = shadowOf(screen).getNextStartedActivity();
        assertNotNull("no Feeds page", page);
        assertNotNull("the link map would pick the screen", page.getComponent());
        assertEquals(screen.getPackageName(), page.getComponent().getPackageName());
        assertEquals(StartTabRoute.PAGE_SCREEN, page.getComponent().getClassName());
        assertEquals(Uri.parse(StartTabRoute.FEEDS_PAGE_LINK), page.getData());
        assertEquals(StartTabRoute.FEEDS_FRAGMENT, page.getIntExtra("target_fragment", -1));
        assertEquals("most_recent", page.getStringExtra("feed_type"));
        assertEquals("BOOKMARK", page.getStringExtra("presentation_type"));
        assertTrue(page.getBooleanExtra("should_show_nav_bar", false));

        // Facebook's check for a tab that owns the page reads this; Home's link would switch to Home.
        String launch = page.getStringExtra("extra_launch_uri");
        assertEquals(StartTabRoute.FEEDS_LAUNCH_LINK, launch);
        assertNotEquals("fb://feed", Uri.parse(launch).buildUpon().clearQuery().build().toString());
    }

    /** The restart row lands back on the settings screen, and a page opened then would cover it. */
    @Test
    public void theFeedsPageStaysClosedWhileTheSettingsScreenIsOverTheStart() {
        ActivityController<FbMainTabActivity> controller =
                Robolectric.buildActivity(FbMainTabActivity.class, StartTabRouteForTests.launcherStart());
        FbMainTabActivity screen = controller.get();
        StartTabRouteForTests.tabBar(screen, StartTabRouteForTests.tabs(new FeedTab(), new MarketplaceTab()), null);
        screen.currentTab = new FeedTab();
        controller.create().start().resume().visible();

        assertTrue("the settings screen didn't open", SettingsEntry.open(screen));
        StartTabRoute.openFeedsPage(screen, StartTab.FEEDS, StartTab.FEEDS, true);

        assertNull("the Feeds page covered the settings screen", shadowOf(screen).getNextStartedActivity());
    }
}
