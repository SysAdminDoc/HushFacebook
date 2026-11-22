/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.Arrays;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import com.facebook.feed.tab.FeedTab;
import com.facebook.katana.activity.FbMainTabActivity;
import com.facebook.navigation.tabbar.ui.TabBarContainerLayout;
import com.facebook.notifications.tab.NotificationsTab;
import com.facebook.video.videohome.tab.WatchTab;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.ShimmerTitleTest;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

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
        Settings.HIDE_STATUS_BAR.resetToDefault();
        TabBarScrollAway.topBarsTakenBack();
        TabBarScrollAway.mainScreenCreated(null);
        TabBarScrollAway.forgetBuilt();
        TabBarScrollAway.Stored.ROW_MADE.resetToDefault();
        TabBarScrollAway.Stored.LIST_HEIGHT.resetToDefault();
        TabBarScrollAway.Stored.ROW_SETUP.resetToDefault();
        TabBarScrollAway.Stored.WIDE_ROW_MADE.resetToDefault();
        TabBarScrollAway.Stored.WIDE_LIST_HEIGHT.resetToDefault();
        TabBarScrollAway.Stored.WIDE_ROW_SETUP.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        LogBufferManager.clearLogBuffer();
        HookStatus.clear();
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) count++;
        return count;
    }

    @Test
    public void theReelUnderTheBarsIsLoggedOnceEachTimeItStarts() {
        // Facebook asks for the padding on every layout pass of the Reels tab. A debug line on each
        // answer put 1,202 copies of it in one S22 log export.
        TabBarScrollAway.forgetBuilt();
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        Settings.TOP_TAB_BAR.save(true);
        for (int visit = 0; visit < 2; visit++) {
            onTab(new WatchTab());
            TabBarScrollAway.topBarsLetGo();
            for (int pass = 0; pass < 5; pass++) assertEquals(0, TabBarScrollAway.pagesTop(150));
            TabBarScrollAway.topBarsTakenBack();
            onTab(new FeedTab());
            assertEquals("back on the feed", 150, TabBarScrollAway.pagesTop(150));
        }
        String log = LogBufferManager.buildExportText();
        assertEquals("one line per visit to the Reels tab: " + log, 2,
                occurrences(log, "Top bars: the reel runs under them"));
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

    /** A list standing in for the feed, which can or can't still scroll up. */
    private static View feed(boolean canScrollUp) {
        return new androidx.recyclerview.widget.RecyclerView(RuntimeEnvironment.getApplication(), true) {
            @Override
            public boolean canScrollVertically(int direction) {
                return direction < 0 && canScrollUp;
            }
        };
    }

    @Test
    public void onlyAListAtItsTopBringsTheBarsAllTheWayBack() {
        // Facebook checks the view is a list before it treats it as one, and a view that can't
        // scroll would read as always at its top.
        View notAList = new View(RuntimeEnvironment.getApplication());
        assertEquals("a view that isn't a list changed the distance", -40, TabBarScrollAway.shownAtTop(notAList, -40));
        // The hook takes an Object (with no manager it gets null), so anything but a view is left alone.
        assertEquals("something that isn't a view changed the distance", -40, TabBarScrollAway.shownAtTop(new Object(), -40));
        String line = statusLine();
        assertFalse("a distance left to Facebook was counted: " + line, line != null && line.contains("Counted"));
    }

    @Test
    public void theBarsComeAllTheWayBackOnceTheFeedReachesItsTop() {
        // Whatever the switches: a bar Facebook slides away on its own stops short the same way.
        assertEquals("at the feed's top the bars got only Facebook's distance", TabBarScrollAway.ALL_THE_WAY_BACK,
                TabBarScrollAway.shownAtTop(feed(false), -40));
        assertTrue("the distance doesn't beat a tall bar", TabBarScrollAway.ALL_THE_WAY_BACK < -10_000);
        assertEquals("below the feed's top the distance changed", -40, TabBarScrollAway.shownAtTop(feed(true), -40));
        assertEquals("a scroll down was changed", 40, TabBarScrollAway.shownAtTop(feed(false), 40));
        assertEquals("with no view the distance changed", -40, TabBarScrollAway.shownAtTop(null, -40));
        String line = statusLine();
        assertTrue(line, line != null && line.endsWith("Counted: " + TabBarScrollAway.BARS_BACK_AT_TOP + " 1"));
    }

    @Test
    public void pausedOrNotReadyTheBarsComeBackAsFacebookBringsThem() {
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertEquals("a Hushfacebook paused by " + reason + " changed the distance", -40,
                    TabBarScrollAway.shownAtTop(feed(false), -40));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertEquals("the distance changed before the settings were ready",
                -40, TabBarScrollAway.shownAtTop(feed(false), -40)));
        String line = statusLine();
        assertFalse("a distance left to Facebook was counted: " + line, line != null && line.contains("Counted"));
    }

    @Test
    public void theOpeningNudgeIsSkippedOnlyWhileTheSwitchSlidesTheBar() {
        Settings.TAB_BAR_SCROLL_AWAY.save(true);
        assertFalse("skipped before Facebook asked whether the bar slides", TabBarScrollAway.skipsOpeningNudge());
        assertTrue(TabBarScrollAway.slidesAway());
        assertTrue("the nudge ran with the switch sliding the bar", TabBarScrollAway.skipsOpeningNudge());

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("a paused Hushfacebook skipped the nudge", TabBarScrollAway.skipsOpeningNudge());
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
                assertFalse("skipped before the settings were ready", TabBarScrollAway.skipsOpeningNudge()));
        Settings.TAB_BAR_SCROLL_AWAY.save(false);
        assertFalse("skipped with the switch off", TabBarScrollAway.skipsOpeningNudge());
    }

    @Test
    public void aKeptSeeThroughBarOrFacebooksOwnAnswerLeavesTheNudge() {
        // A kept see-through bar takes the scroll-away layout too, but it never slides.
        Settings.KEEP_TAB_BAR.save(true);
        Settings.TRANSLUCENT_BARS.save(true);
        assertTrue(TabBarScrollAway.slidesAway());
        assertFalse("a kept bar skipped the nudge", TabBarScrollAway.skipsOpeningNudge());

        // Off, Facebook's server settings decide the slide, and its nudge stays with it.
        Settings.KEEP_TAB_BAR.resetToDefault();
        Settings.TRANSLUCENT_BARS.resetToDefault();
        TabBarScrollAway.forgetBuilt();
        assertFalse(TabBarScrollAway.slidesAway());
        assertFalse("Facebook's own slide skipped the nudge", TabBarScrollAway.skipsOpeningNudge());
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
        onTab(new FeedTab());
        assertEquals("the feed keeps its padding", 150, TabBarScrollAway.pagesTop(150));
        onTab(new WatchTab());
        TabBarScrollAway.topBarsLetGo();
        assertEquals("on the Reels tab the top bar's padding is dropped", 0, TabBarScrollAway.pagesTop(150));
        assertEquals("a zero stays zero", 0, TabBarScrollAway.pagesTop(0));
        String line = statusLine();
        assertTrue(line, line != null && line.contains(TabBarScrollAway.REEL_UNDER_BAR + " 1"));
        TabBarScrollAway.topBarsTakenBack();
        onTab(new FeedTab());
        assertEquals("back on the feed", 150, TabBarScrollAway.pagesTop(150));
    }

    @Test
    public void reelsFirstOnTheBarRunsUnderTheTopBarUnlessTheBarIsKept() {
        Settings.TOP_TAB_BAR.save(true);
        // Reels first on the bar: Facebook treats it as its feed, so scroll-away stays on there (#390).
        onTab(new WatchTab());
        assertEquals("the title bar slides off the reels, so no band is left where it was", 0,
                TabBarScrollAway.pagesTop(150));
        Settings.KEEP_TOP_BAR.save(true);
        assertEquals("a kept title bar stays, so the reel starts below it", 150, TabBarScrollAway.pagesTop(150));
        TabBarScrollAway.topBarsLetGo();
        assertEquals("Reels further along the bar, where Facebook hides the title bar itself", 0,
                TabBarScrollAway.pagesTop(150));
        TabBarScrollAway.topBarsTakenBack();
        Settings.KEEP_TOP_BAR.save(false);
        onTab(new FeedTab());
        assertEquals("Home with the title bar free to slide keeps its padding", 150, TabBarScrollAway.pagesTop(150));
        onTab(null);
        assertEquals("a main screen with no tab yet keeps it too", 150, TabBarScrollAway.pagesTop(150));
    }

    @Test
    public void reelsFirstRunsUnderTheTitleBarWhereverTheTabBarSits() {
        // Reels first with the bar at the bottom: scroll-away stays on and Facebook pads the reel by the title bar (#390).
        Settings.BOTTOM_TAB_BAR.save(true);
        onTab(new WatchTab());
        assertEquals("tab bar at the bottom", 0, TabBarScrollAway.pagesTop(150));
        Settings.BOTTOM_TAB_BAR.save(false);
        assertEquals("tab bar where Facebook has it", 0, TabBarScrollAway.pagesTop(150));
        Settings.KEEP_TOP_BAR.save(true);
        assertEquals("a kept title bar stays, so the reel starts below it", 150, TabBarScrollAway.pagesTop(150));
        Settings.KEEP_TOP_BAR.save(false);
        Settings.BOTTOM_TAB_BAR.save(true);
        TabBarScrollAway.topBarsLetGo();
        assertEquals("Reels further along a bottom bar keeps Facebook's padding", 150, TabBarScrollAway.pagesTop(150));
        TabBarScrollAway.topBarsTakenBack();
        onTab(new FeedTab());
        assertEquals("Home under a bottom bar keeps its padding", 150, TabBarScrollAway.pagesTop(150));
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

    /** A new main screen showing [tab], its tab bar showing [bar] in order. */
    private void onTabOf(Object tab, Object... bar) {
        onTab(tab);
        StartTabRouteForTests.tabBar(screen, StartTabRouteForTests.tabs(bar), null);
    }

    @Test
    public void aReelsRowMadeAheadOnHomeGetsTheTabBarPaddingBack() {
        // #466 on a cold start: Facebook made the Reels list while Home showed, each row 2075 tall,
        // the 2199 page less the 124 tab bar, and kept those rows once Reels showed with the reel run
        // from the top of the page, so the next reel showed in the last 124.
        Settings.TOP_TAB_BAR.save(true);
        onTabOf(new FeedTab(), new FeedTab(), new WatchTab(), new NotificationsTab());
        assertEquals("made ahead on Home, Reels further along a top bar", 2199, TabBarScrollAway.rowFor(2075, 124, 0));
        String line = statusLine();
        assertTrue(line, line != null && line.contains(TabBarScrollAway.REEL_ROW_FILLED + " 1"));
        TabBarScrollAway.topBarsLetGo();
        onTabOf(new WatchTab(), new FeedTab(), new WatchTab(), new NotificationsTab());
        assertEquals("made again on the Reels tab", 2199, TabBarScrollAway.rowFor(2075, 124, 0));
        assertEquals("with the status bar hidden the tab bar's padding takes it in too", 2274,
                TabBarScrollAway.rowFor(2075, 199, 0));
        assertEquals("no padding read and nothing dropped yet", 2075, TabBarScrollAway.rowFor(2075, 0, 0));
        assertEquals(0, TabBarScrollAway.pagesTop(124));
        assertEquals("the dropped padding when the maker's can't be read", 2199, TabBarScrollAway.reelRow(2075, new Object()));
        assertEquals("no row yet", 0, TabBarScrollAway.reelRow(0, null));
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals("paused", 2075, TabBarScrollAway.reelRow(2075, null));
    }

    @Test
    public void aReelsRowTakesTheListsHeightAndRowsMadeAheadTakeTheLastOneSeen() {
        // #466 on the S22: the maker's tab padding read -1 on every row, Facebook worked the row out
        // once as 2075 while Home showed, before the Reels page was laid out, and pagesTop dropped
        // 126 where the list it ran under was 2199 (2075 + 126 left the next reel 2 px short).
        Settings.TOP_TAB_BAR.save(true);
        onTabOf(new FeedTab(), new FeedTab(), new WatchTab(), new NotificationsTab());
        assertEquals("a cold start, nothing seen yet", 2075, TabBarScrollAway.rowFor(2075, -1, 0));
        TabBarScrollAway.topBarsLetGo();
        onTabOf(new WatchTab(), new FeedTab(), new WatchTab(), new NotificationsTab());
        assertEquals(0, TabBarScrollAway.pagesTop(126));
        assertEquals("on the Reels tab the row is the list's height, not the dropped padding's", 2199,
                TabBarScrollAway.rowFor(2075, -1, 2199));
        assertEquals("a list still padded below the bars", 2199, TabBarScrollAway.rowFor(2075, -1, 2073));

        TabBarScrollAway.forgetBuilt();
        TabBarScrollAway.topBarsTakenBack();
        onTabOf(new FeedTab(), new FeedTab(), new WatchTab(), new NotificationsTab());
        assertEquals("the next cold start, made ahead on Home", 2199, TabBarScrollAway.rowFor(2075, -1, 0));
        assertEquals("a row Facebook worked out another way isn't the one seen", 2150, TabBarScrollAway.rowFor(2150, -1, 0));
        Settings.KEEP_TOP_BAR.save(true);
        Settings.TOP_TAB_BAR.save(false);
        assertEquals("a kept top bar leaves the list padded below it, and the height seen without it stays behind",
                2075, TabBarScrollAway.rowFor(2075, -1, 2073));
        assertEquals("made ahead under the kept bar", 2075, TabBarScrollAway.rowFor(2075, -1, 0));
        Settings.KEEP_TOP_BAR.save(false);
        Settings.TOP_TAB_BAR.save(true);

        // The status bar hidden: the list runs 75 taller, seen once, then made ahead to match.
        TabBarScrollAway.topBarsLetGo();
        onTabOf(new WatchTab(), new FeedTab(), new WatchTab(), new NotificationsTab());
        assertEquals(2274, TabBarScrollAway.rowFor(2075, -1, 2274));
        onTabOf(new FeedTab(), new FeedTab(), new WatchTab(), new NotificationsTab());
        assertEquals(2274, TabBarScrollAway.rowFor(2075, -1, 0));
        assertEquals("a list shorter than the row is passed over for the one seen", 2274, TabBarScrollAway.rowFor(2075, -1, 1800));
    }

    @Test
    public void aReelsRowFillsTheListWithTheTabsAtTheBottomToo() {
        // #494: tabs at the bottom, see-through and kept, the status bar hidden, on a 2362 tall
        // screen. Reels sits second, so it runs off the feed and nothing runs under the top bars,
        // yet Facebook made each row 2071 while the list ran the whole 2362, so the next reel
        // showed below a black band.
        Settings.BOTTOM_TAB_BAR.save(true);
        Settings.KEEP_TAB_BAR.save(true);
        Settings.TRANSLUCENT_BARS.save(true);
        Settings.HIDE_STATUS_BAR.save(true);
        TabBarScrollAway.topBarsLetGo();
        onTabOf(new WatchTab(), new FeedTab(), new WatchTab(), new NotificationsTab());
        assertEquals("nothing dropped and no list yet", 2071, TabBarScrollAway.rowFor(2071, -1, 0));
        assertEquals("a list as tall as the row", 2071, TabBarScrollAway.rowFor(2071, -1, 2071));
        assertEquals("on the Reels tab the row is the list's height", 2362, TabBarScrollAway.rowFor(2071, -1, 2362));

        TabBarScrollAway.forgetBuilt();
        TabBarScrollAway.topBarsTakenBack();
        onTabOf(new FeedTab(), new FeedTab(), new WatchTab(), new NotificationsTab());
        assertEquals("the next start, made ahead on Home", 2362, TabBarScrollAway.rowFor(2071, -1, 0));
        Settings.HIDE_STATUS_BAR.save(false);
        assertEquals("the status bar back: the height seen with it hidden stays behind", 2071,
                TabBarScrollAway.rowFor(2071, -1, 0));
        assertEquals("until the list is seen again", 2287, TabBarScrollAway.rowFor(2071, -1, 2287));
        assertEquals(2287, TabBarScrollAway.rowFor(2071, -1, 0));
    }

    @Test
    public void aReelsRowFillsTheWholeListUnderATabBarAlongTheBottom() throws Exception {
        // #466 on 588 (S22, 2026-11-19): tabs at the bottom with See-through bars, Rounded tab bar
        // and the status bar hidden run the Reels list the whole screen down under the bar. Rows
        // that stopped at the bar's top (#503) left the reel's video ending there over black, so
        // it never ran under the bar and the gesture bar. A row fills the list again, and
        // ReelDetailsLift holds the reel's name, caption and buttons above the bar.
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout window = new FrameLayout(activity);
        View list = new View(activity);
        place(window, list, 0, 2400);
        TabBarContainerLayout bar = new TabBarContainerLayout(activity);
        place(window, bar, 2190, 2400);
        activity.setContentView(window);
        layOut(activity);
        TabBarScrollAway.reelsList = new WeakReference<>(list);
        Settings.BOTTOM_TAB_BAR.save(true);
        Settings.TRANSLUCENT_BARS.save(true);
        Settings.HIDE_STATUS_BAR.save(true);
        TabBarScrollAway.topBarsLetGo();
        onTabOf(new WatchTab(), new FeedTab(), new WatchTab(), new NotificationsTab());
        ShimmerTitleTest.appVisible(list, true);
        assertTrue("the list is in the window", list.isAttachedToWindow());
        assertEquals("and the window shows", View.VISIBLE, list.getWindowVisibility());
        assertEquals(2400, list.getHeight());
        assertEquals("the list's whole height, the part under the bar too", 2400, TabBarScrollAway.reelsListHeight());
        assertEquals("the row fills the list", 2400, TabBarScrollAway.rowFor(2062, -1, TabBarScrollAway.reelsListHeight()));
        onTabOf(new FeedTab(), new FeedTab(), new WatchTab(), new NotificationsTab());
        assertEquals("and rows made ahead on Home take the same", 2400, TabBarScrollAway.rowFor(2062, -1, 0));
        list.setPadding(0, 30, 0, 50);
        assertEquals("inside the list's padding", 2320, TabBarScrollAway.reelsListHeight());
    }

    @Test
    public void aReelsRowWorkedOutForTheScreenHeldTheOtherWayFitsTheTurnedList() {
        // #531 on a 3:2 tablet (S22 set to 1600x2400 with Facebook let turn, 588, 2026-11-22):
        // Facebook keeps the row height it worked out when the screen turns, so a row worked out
        // upright ran past the bottom of the screen on its side, and one worked out on its side
        // stopped short upright with the next reel below it.
        Settings.BOTTOM_TAB_BAR.save(true);
        TabBarScrollAway.topBarsLetGo();
        onTabOf(new WatchTab(), new FeedTab(), new WatchTab(), new NotificationsTab());
        assertEquals("on its side, a row worked out upright is taller than the window", 1600,
                TabBarScrollAway.rowFor(2198, -1, 1600, 1600));
        assertEquals("upright, a row worked out on its side is short of the list", 2400,
                TabBarScrollAway.rowFor(1398, -1, 2400, 2400));
        assertEquals("a list shorter than a row that fits the window is still passed over", 2400,
                TabBarScrollAway.rowFor(1398, -1, 1200, 2400));
        assertEquals("with the window unknown nothing is taken for a turn", 2500,
                TabBarScrollAway.rowFor(2500, -1, 1300, 0));
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals("paused", 2198, TabBarScrollAway.reelRow(2198, null));
    }

    @Test
    public void theRowHeightsSeenAreKeptForEachWayUp() {
        // #531: a tablet started on its side after a start upright made its rows ahead with the
        // upright heights, which didn't match, so the reels came out short.
        Settings.BOTTOM_TAB_BAR.save(true);
        Settings.TRANSLUCENT_BARS.save(true);
        TabBarScrollAway.topBarsLetGo();
        onTabOf(new WatchTab(), new FeedTab(), new WatchTab(), new NotificationsTab());
        assertEquals("upright, seen once", 2400, TabBarScrollAway.rowFor(2198, -1, 2400));
        RuntimeEnvironment.setQualifiers("+land");
        onTabOf(new WatchTab(), new FeedTab(), new WatchTab(), new NotificationsTab());
        assertTrue("the main screen is on its side", TabBarScrollAway.wide());
        assertEquals("on its side, seen once", 1600, TabBarScrollAway.rowFor(1398, -1, 1600));
        assertEquals("made ahead on its side", 1600, TabBarScrollAway.rowFor(1398, -1, 0));
        RuntimeEnvironment.setQualifiers("+port");
        onTabOf(new FeedTab(), new FeedTab(), new WatchTab(), new NotificationsTab());
        assertFalse(TabBarScrollAway.wide());
        assertEquals("made ahead upright, the upright heights still there", 2400,
                TabBarScrollAway.rowFor(2198, -1, 0));
    }

    /** A main screen that says whether it shows in a picture in picture window. */
    public static class WindowedMainTab extends FbMainTabActivity {
        boolean inWindow;

        @Override
        public boolean isInPictureInPictureMode() {
            return inWindow;
        }
    }

    @Test
    public void aReelsRowInAPictureInPictureWindowKeepsFacebooksHeight() {
        // #495 on 588 (emulator 1080x2400, 2026-11-18): after unlocking with a reel in the window,
        // Facebook made the Reels rows again for the 451 px window while the list still read its
        // full 2062, and a row grown to that ran the reel mostly outside the window, so Facebook
        // let its video go and the sound stopped.
        Settings.BOTTOM_TAB_BAR.save(true);
        Settings.TRANSLUCENT_BARS.save(true);
        Settings.HIDE_STATUS_BAR.save(true);
        TabBarScrollAway.topBarsLetGo();
        WindowedMainTab windowed = Robolectric.buildActivity(WindowedMainTab.class).get();
        windowed.currentTab = new WatchTab();
        screen = windowed;
        TabBarScrollAway.mainScreenCreated(windowed);
        StartTabRouteForTests.tabBar(windowed,
                StartTabRouteForTests.tabs(new FeedTab(), new WatchTab(), new NotificationsTab()), null);
        assertEquals("full screen, the row fills the list", 2062, TabBarScrollAway.rowFor(1900, -1, 2062));
        windowed.inWindow = true;
        assertEquals("in the window the row stays Facebook's", 451, TabBarScrollAway.rowFor(451, -1, 2062));
        windowed.inWindow = false;
        assertEquals("full screen again, the row seen before the window is still the one kept", 2062,
                TabBarScrollAway.rowFor(1900, -1, 0));
    }

    @Test
    public void aReelsRowForAScreenOverTheMainOneKeepsFacebooksHeight() {
        // On 588 (S22, 2026-11-19, tabs at the top): a saved video opened from the Reels tab's
        // library plays in a screen of its own over the main one, which still shows the Reels tab
        // behind it. That screen's rows of 2105 got the 126 the Reels tab drops under the top bars,
        // so the bottom of the reel's overlay, Clear mode's way out included, ran under the
        // comment box below the reel.
        Settings.TOP_TAB_BAR.save(true);
        TabBarScrollAway.topBarsLetGo();
        ActivityController<FbMainTabActivity> main = Robolectric.buildActivity(FbMainTabActivity.class).setup();
        screen = main.get();
        screen.currentTab = new WatchTab();
        TabBarScrollAway.mainScreenCreated(screen);
        StartTabRouteForTests.tabBar(screen, StartTabRouteForTests.tabs(new FeedTab(), new FeedTab(), new WatchTab()), null);
        assertEquals(0, TabBarScrollAway.pagesTop(126));
        assertEquals("on the Reels tab the row gets the dropped padding back", 2231, TabBarScrollAway.rowFor(2105, -1, 0));

        main.pause();
        ActivityController<Activity> viewer = Robolectric.buildActivity(Activity.class).setup();
        assertEquals("a saved video's screen over the main one", 2105, TabBarScrollAway.rowFor(2105, -1, 0));
        assertEquals("nor the list's height seen for the Reels tab", 2105, TabBarScrollAway.rowFor(2105, -1, 2231));

        viewer.pause();
        main.resume();
        viewer.stop().destroy();
        assertEquals("back on the main screen", 2231, TabBarScrollAway.rowFor(2105, -1, 0));
    }

    private static void place(FrameLayout parent, View child, int top, int bottom) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1080, bottom - top, Gravity.LEFT | Gravity.TOP);
        params.topMargin = top;
        parent.addView(child, params);
    }

    private static void layOut(Activity activity) {
        activity.getWindow().getDecorView().requestLayout();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
    }

    @Test
    public void aReelsRowMadeAheadKeepsItsHeightWhereReelsWontRunUnderTheBars() {
        Settings.TOP_TAB_BAR.save(true);
        assertEquals("no main screen", 2075, TabBarScrollAway.rowFor(2075, 124, 0));
        onTabOf(new FeedTab(), new FeedTab(), new NotificationsTab());
        assertEquals("no Reels on the bar", 2075, TabBarScrollAway.rowFor(2075, 124, 0));
        onTab(new FeedTab());
        assertEquals("a bar that can't be read", 2075, TabBarScrollAway.rowFor(2075, 124, 0));
        Settings.TOP_TAB_BAR.save(false);
        onTabOf(new FeedTab(), new FeedTab(), new WatchTab());
        assertEquals("Reels further along a bar at the bottom", 2075, TabBarScrollAway.rowFor(2075, 124, 0));
    }

    @Test
    public void aReelsRowGetsBackThePaddingDroppedOverIt() {
        // Reels first with the bar at the bottom: the title bar's padding goes, so the row gets it
        // back, never a tab padding Facebook gives for a bar that isn't at the top.
        Settings.BOTTOM_TAB_BAR.save(true);
        onTabOf(new WatchTab(), new WatchTab(), new FeedTab());
        assertEquals("nothing dropped yet", 2000, TabBarScrollAway.rowFor(2000, 120, 0));
        assertEquals(0, TabBarScrollAway.pagesTop(150));
        assertEquals("the dropped padding goes back on the row", 2150, TabBarScrollAway.rowFor(2000, 120, 0));
        Settings.KEEP_TOP_BAR.save(true);
        assertEquals("a kept title bar keeps its padding, and the row its height", 2000, TabBarScrollAway.rowFor(2000, 120, 0));
        Settings.KEEP_TOP_BAR.save(false);
        TabBarScrollAway.topBarsLetGo();
        assertEquals("Reels further along a bottom bar keeps Facebook's row", 2000, TabBarScrollAway.rowFor(2000, 120, 0));
    }

    @Test
    public void reelsFirstMadeAheadRunsUnderTheBarsUnlessTheTopBarIsKept() {
        // Reels first, its list made before the first tab shows: Facebook leaves scroll-away on there.
        Settings.TOP_TAB_BAR.save(true);
        onTabOf(new FeedTab(), new WatchTab(), new FeedTab());
        assertEquals(2199, TabBarScrollAway.rowFor(2075, 124, 0));
        Settings.KEEP_TOP_BAR.save(true);
        assertEquals("a kept top bar", 2075, TabBarScrollAway.rowFor(2075, 124, 0));
    }

    @Test
    public void aReelsRowFollowsTheReelsTabNotTheTabShowing() {
        Settings.TOP_TAB_BAR.save(true);
        TabBarScrollAway.topBarsLetGo();
        onTabOf(new NotificationsTab(), new FeedTab(), new WatchTab(), new NotificationsTab());
        assertEquals("another tab off the feed, Reels further along a top bar", 2199, TabBarScrollAway.rowFor(2075, 124, 0));
        Settings.TOP_TAB_BAR.save(false);
        onTabOf(new WatchTab(), new FeedTab(), new WatchTab());
        assertEquals("Reels further along with the top bar off", 2075, TabBarScrollAway.rowFor(2075, 124, 0));
    }

    @Test
    public void thePaddingStaysWithoutATopBarOrWhilePaused() {
        onTab(new WatchTab());
        TabBarScrollAway.topBarsLetGo();
        assertEquals("Reels further along with the top bar off", 150, TabBarScrollAway.pagesTop(150));
        Settings.TOP_TAB_BAR.save(true);
        Settings.BOTTOM_TAB_BAR.save(true);
        assertEquals("the bottom switch wins", 150, TabBarScrollAway.pagesTop(150));
        Settings.BOTTOM_TAB_BAR.save(false);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals("paused", 150, TabBarScrollAway.pagesTop(150));
    }

    @Test
    public void reelsFurtherAlongABarFacebookPutAtTheTopRunsUnderIt() {
        // Neither position switch is on: Facebook put the tabs at the top itself, so the top bar's
        // scroll-away entry it moves carries them (#390).
        onTab(new FeedTab());
        assertEquals(-40, TabBarScrollAway.topBarOffset(TabBarScrollAway.TITLE_AND_TABS, -40));
        TabBarScrollAway.topBarsLetGo();
        onTab(new WatchTab());
        assertEquals("Reels second on a bar Facebook put at the top kept a band where the title bar was", 0,
                TabBarScrollAway.pagesTop(150));
        onTab(new NotificationsTab());
        assertEquals("Notifications under that bar lost its padding", 150, TabBarScrollAway.pagesTop(150));

        // The same account with its bar at the bottom: the top entry is the title bar alone.
        TabBarScrollAway.topBarsTakenBack();
        assertEquals(0.5f, TabBarScrollAway.topBarFade(TabBarScrollAway.APP_HEADER, 0.5f), 0f);
        TabBarScrollAway.topBarsLetGo();
        onTab(new WatchTab());
        assertEquals("Reels further along a bar at the bottom lost Facebook's padding", 150, TabBarScrollAway.pagesTop(150));

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        TabBarScrollAway.topBarOffset(TabBarScrollAway.TITLE_AND_TABS, 0);
        assertEquals("paused", 150, TabBarScrollAway.pagesTop(150));
    }
}
