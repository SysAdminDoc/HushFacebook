/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import com.facebook.feed.tab.FeedTab;
import com.facebook.katana.activity.FbMainTabActivity;
import com.facebook.notifications.tab.NotificationsTab;
import com.facebook.video.videohome.tab.WatchTab;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Hide the tab bar while scrolling: with the switch on, Facebook's check of whether the bar at the
 * bottom slides away answers yes, every time it's asked, and each yes is counted. Off, paused, or
 * before the settings are ready, it answers no, so Facebook goes on to its own server settings.
 * The slide itself is Facebook's code, which this check turns on.
 *
 * Keep the tab bar on screen answers no to the same check before Facebook's server settings get a
 * say, unless the slide is switched on, which wins. Keep the top bar on screen hands back 0 for the
 * distance the top bar's entry is asked to move, and every other entry's distance untouched.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class TabBarScrollAwayTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private FbMainTabActivity screen;

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.TAB_BAR_SCROLL_AWAY.resetToDefault();
        Settings.KEEP_TAB_BAR.resetToDefault();
        Settings.KEEP_TOP_BAR.resetToDefault();
        Settings.BOTTOM_TAB_BAR.resetToDefault();
        Settings.TOP_TAB_BAR.resetToDefault();
        Settings.TRANSLUCENT_BARS.resetToDefault();
        TabBarScrollAway.topBarsTakenBack();
        TabBarScrollAway.mainScreenCreated(null);
        TabBarScrollAway.forgetBuilt();
        HookStatus.clear();
    }

    @Test
    public void aKeptBarsSeeThroughChangeWaitsForARestartUntilItIsPutBack() {
        Settings.KEEP_TAB_BAR.save(true);
        assertFalse("a restart was owed before the bar was built", TabBarScrollAway.seeThroughRestartOwed());
        // The main screen starts: the bar is built kept, without see-through.
        assertTrue(TabBarScrollAway.staysPut());
        assertFalse("a restart was owed for the layout the bar was built with", TabBarScrollAway.seeThroughRestartOwed());

        Settings.TRANSLUCENT_BARS.save(true);
        assertTrue("the kept bar's see-through change didn't wait for a restart", TabBarScrollAway.seeThroughRestartOwed());
        // The floating button asks again later: the bar keeps the layout it was built with.
        assertFalse(TabBarScrollAway.staysPut());
        assertTrue(TabBarScrollAway.slidesAway());
        assertTrue("a later ask forgot the built layout", TabBarScrollAway.seeThroughRestartOwed());

        Settings.TRANSLUCENT_BARS.save(false);
        assertFalse("a change put back still waited", TabBarScrollAway.seeThroughRestartOwed());
    }

    @Test
    public void seeThroughShowsRightAwayOnABarThatIsntKept() {
        // Built with the bar left to Facebook.
        assertFalse(TabBarScrollAway.staysPut());
        assertFalse(TabBarScrollAway.slidesAway());
        Settings.TRANSLUCENT_BARS.save(true);
        assertFalse("see-through waited on a bar that isn't kept", TabBarScrollAway.seeThroughRestartOwed());
        // Keeping the bar now waits under its own switch, not under See-through bars.
        Settings.KEEP_TAB_BAR.save(true);
        assertFalse("see-through waited under its own key for a bar kept after the start", TabBarScrollAway.seeThroughRestartOwed());

        // A bar built kept and see-through, then set to slide away: that switch says its own restart.
        TabBarScrollAway.forgetBuilt();
        assertFalse(TabBarScrollAway.staysPut());
        assertTrue(TabBarScrollAway.slidesAway());
        Settings.TAB_BAR_SCROLL_AWAY.save(true);
        Settings.TRANSLUCENT_BARS.save(false);
        assertFalse("see-through waited for a bar set to slide away", TabBarScrollAway.seeThroughRestartOwed());
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.BOTTOM_TAB_BAR + ":")) return line;
        }
        return null;
    }

    @Test
    public void onTheBarSlidesAwayEachTimeFacebookAsks() {
        Settings.TAB_BAR_SCROLL_AWAY.save(true);
        // The start, the floating button and the mini player each ask.
        for (int asked = 0; asked < 3; asked++) {
            assertTrue("the check answered no with the switch on", TabBarScrollAway.slidesAway());
        }
        assertEquals(FamilyNames.BOTTOM_TAB_BAR + ": invoked 3, 1 found, 0 missing. Counted: "
                + TabBarScrollAway.SLIDES_AWAY + " 3", statusLine());
    }

    @Test
    public void theSwitchWorksWithoutTabBarAtTheBottomsOwnSwitch() {
        // Accounts Facebook gives the bar at the bottom don't need the other switch.
        assertFalse(Settings.BOTTOM_TAB_BAR.get());
        Settings.TAB_BAR_SCROLL_AWAY.save(true);
        assertTrue("the slide waited for Tab bar at the bottom's switch", TabBarScrollAway.slidesAway());
    }

    @Test
    public void offOrPausedFacebookDecides() {
        assertFalse("the switch doesn't start off", Settings.TAB_BAR_SCROLL_AWAY.get());
        assertFalse("off, the bar slid away", TabBarScrollAway.slidesAway());

        Settings.TAB_BAR_SCROLL_AWAY.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertFalse("a Hushfacebook paused by " + reason + " slid the bar away", TabBarScrollAway.slidesAway());
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() ->
                assertFalse("the bar slid away before the settings were ready", TabBarScrollAway.slidesAway()));

        String line = statusLine();
        assertFalse("an answer that left it to Facebook was counted: " + line, line != null && line.contains("Counted"));
        assertTrue("on again after the pause, the bar didn't slide away", TabBarScrollAway.slidesAway());
    }

    @Test
    public void theSwitchAsksForARestartAndTravelsWithTheBarsFamily() {
        assertEquals(true, Settings.TAB_BAR_SCROLL_AWAY.rebootApp);
        assertNull("nothing asks before the switch changes", Settings.TAB_BAR_SCROLL_AWAY.userDialogMessage);
        assertTrue("Pause and the report don't know the switch",
                PatchFamily.BOTTOM_TAB_BAR.switches.contains(Settings.TAB_BAR_SCROLL_AWAY));
    }

    @Test
    public void aKeptBarStaysPutEachTimeFacebookAsks() {
        assertFalse("the kept bar's switch doesn't start off", Settings.KEEP_TAB_BAR.get());
        assertFalse("off, the bar was kept", TabBarScrollAway.staysPut());

        Settings.KEEP_TAB_BAR.save(true);
        for (int asked = 0; asked < 2; asked++) {
            assertTrue("on, the bar wasn't kept", TabBarScrollAway.staysPut());
        }
        String line = statusLine();
        assertTrue(line, line != null && line.contains("Counted: " + TabBarScrollAway.STAYS_PUT + " 2"));
    }

    @Test
    public void hidingWhileScrollingWinsOverAKeptBar() {
        Settings.KEEP_TAB_BAR.save(true);
        assertTrue("the kept bar's switch is greyed out with the slide off", Settings.KEEP_TAB_BAR.isAvailable());
        Settings.TAB_BAR_SCROLL_AWAY.save(true);
        assertFalse("the kept bar's switch isn't greyed out under the slide", Settings.KEEP_TAB_BAR.isAvailable());
        assertFalse("both on, the bar was kept", TabBarScrollAway.staysPut());
        assertTrue("both on, the bar didn't slide away", TabBarScrollAway.slidesAway());
    }

    @Test
    public void aKeptSeeThroughBarTakesFacebooksScrollAwayLayoutAndIsHeldInIt() {
        // A kept bar alone answers Facebook's check no, so its pages are padded up to it, and
        // the bar's entry is left to Facebook as before.
        Settings.KEEP_TAB_BAR.save(true);
        assertTrue(TabBarScrollAway.staysPut());
        assertFalse(TabBarScrollAway.slidesAway());
        assertEquals("the bar's entry was held without see-through", 96,
                TabBarScrollAway.topBarOffset(TabBarScrollAway.BOTTOM_TABS, 96));
        assertEquals("the bar's entry was kept from fading without see-through", 0.5f,
                TabBarScrollAway.topBarFade(TabBarScrollAway.BOTTOM_TABS, 0.5f), 0f);

        // With See-through bars on it answers yes instead, so Facebook builds the layout with the
        // pages under the bar (#250), then holds the bar's own entry where it rests, fully drawn.
        Settings.TRANSLUCENT_BARS.save(true);
        assertFalse("the see-through bar was kept by a no", TabBarScrollAway.staysPut());
        assertTrue("the see-through bar didn't take the scroll-away layout", TabBarScrollAway.slidesAway());
        assertEquals("the see-through bar's entry slid away", 0, TabBarScrollAway.topBarOffset(TabBarScrollAway.BOTTOM_TABS, 96));
        assertEquals("the see-through bar's entry faded", 0f, TabBarScrollAway.topBarFade(TabBarScrollAway.BOTTOM_TABS, 0.5f), 0f);
        assertEquals("the top bar was held by the bar's switches", -120,
                TabBarScrollAway.topBarOffset(TabBarScrollAway.APP_HEADER, -120));
        assertEquals("another entry was held", -50, TabBarScrollAway.topBarOffset("some_other_entry", -50));

        // Hide the tab bar while scrolling still wins over both.
        Settings.TAB_BAR_SCROLL_AWAY.save(true);
        assertTrue(TabBarScrollAway.slidesAway());
        assertEquals("the sliding bar's entry was held", 96, TabBarScrollAway.topBarOffset(TabBarScrollAway.BOTTOM_TABS, 96));

        String line = statusLine();
        assertTrue(line, line != null && line.contains(TabBarScrollAway.STAYS_PUT + " 1"));
        assertTrue(line, line.contains(TabBarScrollAway.PAGES_RUN_UNDER + " 1"));
        assertTrue(line, line.contains(TabBarScrollAway.BAR_HELD + " 1"));
        assertTrue(line, line.contains(TabBarScrollAway.SLIDES_AWAY + " 1"));
        assertFalse("a top bar move was counted as held: " + line, line.contains(TabBarScrollAway.TOP_BAR_HELD));
    }

    @Test
    public void aKeptSeeThroughBarGoesWithFacebooksOwnHideOffTheFeed() {
        Settings.KEEP_TAB_BAR.save(true);
        Settings.TRANSLUCENT_BARS.save(true);
        assertEquals("on the feed, the bar's entry slid away", 0, TabBarScrollAway.topBarOffset(TabBarScrollAway.BOTTOM_TABS, 96));

        TabBarScrollAway.topBarsLetGo();
        assertEquals("the bar was held over a reel", 96, TabBarScrollAway.topBarOffset(TabBarScrollAway.BOTTOM_TABS, 96));
        assertEquals("the bar was kept from fading over a reel", 1f,
                TabBarScrollAway.topBarFade(TabBarScrollAway.BOTTOM_TABS, 1f), 0f);

        TabBarScrollAway.topBarsTakenBack();
        assertEquals("back on the feed, the bar's entry slid away", 0, TabBarScrollAway.topBarOffset(TabBarScrollAway.BOTTOM_TABS, 96));
        String line = statusLine();
        assertTrue(line, line != null && line.contains(TabBarScrollAway.BAR_HELD + " 2"));
        assertTrue(line, line.contains(TabBarScrollAway.BAR_LET_GO + " 1"));
    }

    @Test
    public void aKeptSeeThroughBarsPagesGetNoPaddingListener() {
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        Settings.KEEP_TAB_BAR.save(true);
        Settings.TRANSLUCENT_BARS.save(true);
        android.view.View pager = new android.widget.FrameLayout(context);
        TabBarScrollAway.padsPager(pager, 0, 0, 0, 147);
        assertEquals("Facebook's padding wasn't set first", 147, pager.getPaddingBottom());
        assertNull("a see-through bar's pager got the kept bar's listener", TabBarScrollAway.CLEARED_PAGERS.get(pager));
    }

    @Test
    public void aKeptTopBarHoldsStillAndTheOtherEntriesMove() {
        assertFalse("the top bar's switch doesn't start off", Settings.KEEP_TOP_BAR.get());
        assertEquals("off, the top bar was held", -120, TabBarScrollAway.topBarOffset(TabBarScrollAway.APP_HEADER, -120));

        Settings.KEEP_TOP_BAR.save(true);
        assertEquals("the top bar slid away", 0, TabBarScrollAway.topBarOffset(TabBarScrollAway.APP_HEADER, -120));
        assertEquals("the top bar with the tabs in it slid away", 0,
                TabBarScrollAway.topBarOffset(TabBarScrollAway.TITLE_AND_TABS, -80));
        // A move of nothing passes straight through, so it isn't counted: the count below stays 2.
        assertEquals("a top bar back home moved", 0, TabBarScrollAway.topBarOffset(TabBarScrollAway.APP_HEADER, 0));
        assertEquals("the bottom bar's entry was held", 96, TabBarScrollAway.topBarOffset(TabBarScrollAway.BOTTOM_TABS, 96));
        assertEquals("an entry with no name was held", -50, TabBarScrollAway.topBarOffset(null, -50));
        String line = statusLine();
        assertTrue(line, line != null && line.contains("Counted: " + TabBarScrollAway.TOP_BAR_HELD + " 2"));
    }

    @Test
    public void aKeptTopBarDoesNotFadeOutAndTheOtherEntriesStillDo() {
        assertEquals("off, the top bar was kept from fading", 0.75f,
                TabBarScrollAway.topBarFade(TabBarScrollAway.APP_HEADER, 0.75f), 0f);

        Settings.KEEP_TOP_BAR.save(true);
        assertEquals("the top bar faded", 0f, TabBarScrollAway.topBarFade(TabBarScrollAway.APP_HEADER, 0.75f), 0f);
        assertEquals("the top bar with the tabs in it faded", 0f,
                TabBarScrollAway.topBarFade(TabBarScrollAway.TITLE_AND_TABS, 1f), 0f);
        // Nothing hidden passes straight through, so it isn't counted: the count below stays 2.
        assertEquals("a top bar in full view faded", 0f, TabBarScrollAway.topBarFade(TabBarScrollAway.APP_HEADER, 0f), 0f);
        assertEquals("the bottom bar's entry was kept from fading", 0.5f,
                TabBarScrollAway.topBarFade(TabBarScrollAway.BOTTOM_TABS, 0.5f), 0f);
        assertEquals("an entry with no name was kept from fading", 0.5f, TabBarScrollAway.topBarFade(null, 0.5f), 0f);
        String line = statusLine();
        assertTrue(line, line != null && line.contains("Counted: " + TabBarScrollAway.TOP_BAR_UNFADED + " 2"));
    }

    @Test
    public void aKeptTopBarGoesWithFacebooksOwnHideOffTheFeedAndComesBackWithIt() {
        Settings.KEEP_TOP_BAR.save(true);
        assertEquals("on the feed, the top bar slid away", 0, TabBarScrollAway.topBarOffset(TabBarScrollAway.APP_HEADER, -120));

        // The main screen opens the Reels tab: Facebook turns scroll-away off and hides the bars itself.
        TabBarScrollAway.topBarsLetGo();
        assertEquals("the top bar was held over a reel", -120, TabBarScrollAway.topBarOffset(TabBarScrollAway.APP_HEADER, -120));
        assertEquals("the tabs in the top bar were held over a reel", -80,
                TabBarScrollAway.topBarOffset(TabBarScrollAway.TITLE_AND_TABS, -80));
        assertEquals("the top bar was kept from fading over a reel", 1f,
                TabBarScrollAway.topBarFade(TabBarScrollAway.APP_HEADER, 1f), 0f);
        assertEquals("the bottom bar's entry was held", 96, TabBarScrollAway.topBarOffset(TabBarScrollAway.BOTTOM_TABS, 96));

        // Back on the feed: Facebook turns scroll-away on again and shows the bars, then the feed's scrolls hold it.
        TabBarScrollAway.topBarsTakenBack();
        assertEquals("back on the feed, the top bar slid away", 0, TabBarScrollAway.topBarOffset(TabBarScrollAway.APP_HEADER, -120));
        assertEquals("back on the feed, the top bar faded", 0f, TabBarScrollAway.topBarFade(TabBarScrollAway.APP_HEADER, 0.5f), 0f);
        String line = statusLine();
        assertTrue(line, line != null && line.contains(TabBarScrollAway.TOP_BAR_LET_GO + " 2"));
        assertTrue(line, line.contains(TabBarScrollAway.TOP_BAR_HELD + " 2"));
        assertTrue(line, line.contains(TabBarScrollAway.TOP_BAR_UNFADED + " 1"));
    }

    @Test
    public void offTheFeedWithTheSwitchOffNothingIsCounted() {
        TabBarScrollAway.topBarsLetGo();
        assertEquals(-120, TabBarScrollAway.topBarOffset(TabBarScrollAway.APP_HEADER, -120));
        String line = statusLine();
        assertFalse("a move left to Facebook was counted: " + line, line != null && line.contains("Counted"));
    }

    @Test
    public void pausedOrNotReadyBothBarsAreFacebooks() {
        Settings.KEEP_TAB_BAR.save(true);
        Settings.KEEP_TOP_BAR.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertFalse("a Hushfacebook paused by " + reason + " kept the bar", TabBarScrollAway.staysPut());
            assertEquals("a Hushfacebook paused by " + reason + " held the top bar", -120,
                    TabBarScrollAway.topBarOffset(TabBarScrollAway.APP_HEADER, -120));
            assertEquals("a Hushfacebook paused by " + reason + " kept the top bar from fading", 1f,
                    TabBarScrollAway.topBarFade(TabBarScrollAway.APP_HEADER, 1f), 0f);
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> {
            assertFalse("the bar was kept before the settings were ready", TabBarScrollAway.staysPut());
            assertEquals("the top bar was held before the settings were ready", -120,
                    TabBarScrollAway.topBarOffset(TabBarScrollAway.APP_HEADER, -120));
        });

        String line = statusLine();
        assertFalse("an answer that left it to Facebook was counted: " + line, line != null && line.contains("Counted"));
        assertTrue("on again after the pause, the bar wasn't kept", TabBarScrollAway.staysPut());
        assertEquals("on again after the pause, the top bar moved", 0,
                TabBarScrollAway.topBarOffset(TabBarScrollAway.APP_HEADER, -120));
    }

    @Test
    public void onlyTheKeptBarAsksForARestartAndBothTravelWithTheBarsFamily() {
        assertEquals("the bar's entry is built as Facebook starts", true, Settings.KEEP_TAB_BAR.rebootApp);
        assertEquals("the top bar is asked on every move", false, Settings.KEEP_TOP_BAR.rebootApp);
        assertTrue("Pause and the report don't know the switches", PatchFamily.BOTTOM_TAB_BAR.switches.containsAll(
                Arrays.asList(Settings.KEEP_TAB_BAR, Settings.KEEP_TOP_BAR)));
    }

    @Test
    public void aKeptBarsPagesClearTheNavigationBarTheyReachUnder() {
        // The Android 16 emulator: the pager runs to the bottom of a 2400 px window, the bar is
        // 147 px and the navigation bar under it 63 px.
        assertEquals(210, TabBarScrollAway.keptPadding(147, 147, -1, 2400, 2400, 63));
        assertEquals("the padding already set here was changed", 210, TabBarScrollAway.keptPadding(210, 147, 210, 2400, 2400, 63));
        assertEquals("a window that stops above the navigation bar got more", 147, TabBarScrollAway.keptPadding(147, 147, -1, 2337, 2400, 63));
        assertEquals("part of the navigation bar", 147 + 33, TabBarScrollAway.keptPadding(147, 147, -1, 2370, 2400, 63));
        assertEquals("no navigation bar", 147, TabBarScrollAway.keptPadding(147, 147, -1, 2400, 2400, 0));
        assertEquals("a padding Facebook set since was taken over", -1, TabBarScrollAway.keptPadding(0, 147, 210, 2400, 2400, 63));
    }

    @Test
    public void thePagerGetsFacebooksPaddingFirstWhateverTheSwitches() {
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        for (boolean kept : new boolean[] {false, true}) {
            Settings.KEEP_TAB_BAR.save(kept);
            android.view.View pager = new android.widget.FrameLayout(context);
            TabBarScrollAway.padsPager(pager, 0, 0, 0, 147);
            // Not in a window, so there are no insets to clear yet.
            pager.layout(0, 0, 1080, 2272);
            assertEquals("kept " + kept, 147, pager.getPaddingBottom());
            assertEquals("kept " + kept, 0, pager.getPaddingTop());
        }
        TabBarScrollAway.padsPager(null, 0, 0, 0, 147);
        String line = statusLine();
        assertTrue(line, line != null && line.contains("invoked 2,"));
        assertFalse("a padding was counted without a window: " + line, line.contains(TabBarScrollAway.PAGES_CLEARED));
    }

    @Test
    public void aPagerSetUpAgainKeepsOneListenerAndOnlyAKeptBarsPaddingGetsOne() {
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        Settings.KEEP_TAB_BAR.save(true);
        android.view.View pager = new android.widget.FrameLayout(context);
        TabBarScrollAway.padsPager(pager, 0, 0, 0, 147);
        TabBarScrollAway.ClearsNavigationBar first = TabBarScrollAway.CLEARED_PAGERS.get(pager);
        assertTrue("a kept bar's pager got no listener", first != null);
        TabBarScrollAway.padsPager(pager, 0, 0, 0, 147);
        assertTrue("a second set-up added another listener", first == TabBarScrollAway.CLEARED_PAGERS.get(pager));

        android.view.View unpadded = new android.widget.FrameLayout(context);
        TabBarScrollAway.padsPager(unpadded, 0, 0, 0, 0);
        assertNull("a pager Facebook gives no bottom padding got a listener", TabBarScrollAway.CLEARED_PAGERS.get(unpadded));

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        android.view.View paused = new android.widget.FrameLayout(context);
        TabBarScrollAway.padsPager(paused, 0, 0, 0, 147);
        assertNull("a paused Hushfacebook gave a pager a listener", TabBarScrollAway.CLEARED_PAGERS.get(paused));
        assertEquals(147, paused.getPaddingBottom());
    }

    @Test
    public void theReelRunsUnderATopBarOnlyOnTheReelsTab() {
        Settings.TOP_TAB_BAR.save(true);
        onTab(new WatchTab());
        assertEquals("the feed keeps its padding", 150, TabBarScrollAway.pagesTop(150));
        TabBarScrollAway.topBarsLetGo();
        assertEquals("on the Reels tab the top bar's padding is dropped", 0, TabBarScrollAway.pagesTop(150));
        assertEquals("a zero stays zero", 0, TabBarScrollAway.pagesTop(0));
        String line = statusLine();
        assertTrue(line, line != null && line.contains(TabBarScrollAway.REEL_UNDER_BAR + " 1"));
        TabBarScrollAway.topBarsTakenBack();
        assertEquals("back on the feed", 150, TabBarScrollAway.pagesTop(150));
    }

    @Test
    public void theOtherTabsKeepTheirPaddingUnderATopBar() {
        Settings.TOP_TAB_BAR.save(true);
        TabBarScrollAway.topBarsLetGo();
        onTab(new NotificationsTab());
        assertEquals("Notifications", 150, TabBarScrollAway.pagesTop(150));
        onTab(new FeedTab());
        assertEquals("Home", 150, TabBarScrollAway.pagesTop(150));
        onTab(new WatchTab());
        assertEquals("then Reels", 0, TabBarScrollAway.pagesTop(150));
        onTab(new NotificationsTab());
        assertEquals("and back to Notifications", 150, TabBarScrollAway.pagesTop(150));
    }

    @Test
    public void thePaddingStaysWhenTheTabCantBeRead() {
        Settings.TOP_TAB_BAR.save(true);
        TabBarScrollAway.topBarsLetGo();
        assertEquals("no main screen", 150, TabBarScrollAway.pagesTop(150));
        onTab(null);
        assertEquals("a main screen with no tab yet", 150, TabBarScrollAway.pagesTop(150));
        onTab("not a tab");
        assertEquals("something that isn't a tab", 150, TabBarScrollAway.pagesTop(150));
    }

    /** A new main screen showing [tab], kept so the weak hold on it lasts the test. */
    private void onTab(Object tab) {
        screen = Robolectric.buildActivity(FbMainTabActivity.class).get();
        screen.currentTab = tab;
        TabBarScrollAway.mainScreenCreated(screen);
    }

    @Test
    public void thePaddingStaysWithoutATopBarOrWhilePaused() {
        onTab(new WatchTab());
        TabBarScrollAway.topBarsLetGo();
        assertEquals("top bar off", 150, TabBarScrollAway.pagesTop(150));
        Settings.TOP_TAB_BAR.save(true);
        Settings.BOTTOM_TAB_BAR.save(true);
        assertEquals("the bottom switch wins", 150, TabBarScrollAway.pagesTop(150));
        Settings.BOTTOM_TAB_BAR.save(false);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals("paused", 150, TabBarScrollAway.pagesTop(150));
    }
}
