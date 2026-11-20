/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.recyclerview.widget.RecyclerView;

import com.facebook.katana.activity.FbMainTabActivity;
import com.facebook.katana.immersiveactivity.ImmersiveActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Center the feed on big screens (#531): on a window 1000 dp wide, the feed keeps to a column 600
 * dp wide down the middle, with Facebook's top and bottom padding kept, and so does a list whose
 * rows all fit (Menu on a tablet). The padding goes on the frame holding the list and nothing else,
 * up to the screen's content frame, so a title bar beside it keeps the whole width; a spinner
 * showing beside the list for a moment doesn't move it. A list with no such frame pads itself, and
 * is left wide when its rows ignore the padding. A list that pages (the Reels viewer), a list that
 * scrolls sideways, cards side by side, a list narrower than the column and a list inside the feed
 * are left as they are. Facebook's own wider padding stays, Facebook setting its padding again is
 * followed, and a list it keeps setting it on is left alone for a while. Off, paused or failing,
 * the feed gets Facebook's padding back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w1000dp-h1400dp-mdpi")
public class CenteredFeedTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Activity activity;
    private FrameLayout window;

    @Before
    public void start() {
        HookStatus.clear();
        CenteredFeed.WINDOWS.clear();
        Settings.CENTER_FEED_ON_BIG_SCREENS.save(true);
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        window = new FrameLayout(activity);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.CENTER_FEED_ON_BIG_SCREENS.resetToDefault();
        CenteredFeed.WINDOWS.clear();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.BOTTOM_TAB_BAR + ":")) return line;
        }
        return "";
    }

    /** A list of [count] rows [tall] pixels each, filling the window's width unless [wide] says. */
    private RecyclerView listOf(boolean vertical, int count, int tall, int wide) {
        return fill(new RecyclerView(activity, vertical), count, tall, wide);
    }

    /** [list] with [count] rows [tall] pixels each, in the window, filling its width unless [wide] says. */
    private RecyclerView fill(RecyclerView list, int count, int tall, int wide) {
        for (int i = 0; i < count; i++) {
            list.addView(new View(activity), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tall));
        }
        window.addView(list, new FrameLayout.LayoutParams(wide, ViewGroup.LayoutParams.MATCH_PARENT));
        return list;
    }

    private void layOut() {
        window.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1400, View.MeasureSpec.EXACTLY));
        window.layout(0, 0, 1000, 1400);
    }

    private static void assertPadding(String what, View list, int left, int top, int right, int bottom) {
        assertEquals(what + " left", left, list.getPaddingLeft());
        assertEquals(what + " top", top, list.getPaddingTop());
        assertEquals(what + " right", right, list.getPaddingRight());
        assertEquals(what + " bottom", bottom, list.getPaddingBottom());
    }

    @Test
    public void theSwitchStartsOffInTheTabBarFamilyAndNeedsNoRestart() {
        Settings.CENTER_FEED_ON_BIG_SCREENS.resetToDefault();
        assertFalse(Settings.CENTER_FEED_ON_BIG_SCREENS.get());
        assertFalse(CenteredFeed.on());
        assertFalse("a change shows when the settings close", Settings.CENTER_FEED_ON_BIG_SCREENS.rebootApp);
        assertTrue(PatchFamily.BOTTOM_TAB_BAR.switches.contains(Settings.CENTER_FEED_ON_BIG_SCREENS));
        assertEquals("the main screen is the see-through bars' one", TranslucentBars.MAIN_SCREEN, CenteredFeed.MAIN_SCREEN);
    }

    @Test
    public void aWideFeedKeepsToAColumnDownTheMiddleAndOtherListsStayAsTheyAre() {
        RecyclerView feed = listOf(true, 4, 300, ViewGroup.LayoutParams.MATCH_PARENT);
        feed.setPadding(8, 50, 8, 120);
        RecyclerView inside = new RecyclerView(activity, true);
        inside.addView(new View(activity), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100));
        feed.addView(inside, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 300));
        RecyclerView sideways = fill(new Sideways(activity), 3, 200, ViewGroup.LayoutParams.MATCH_PARENT);
        RecyclerView narrow = listOf(true, 3, 300, 500);
        RecyclerView reels = listOf(true, 3, 1400, ViewGroup.LayoutParams.MATCH_PARENT);
        layOut();

        CenteredFeed.Columns columns = new CenteredFeed.Columns(window);
        columns.check(0);
        layOut();
        assertPadding("the feed", feed, 200, 50, 200, 120);
        assertEquals("its rows are the column's width", 600, feed.getChildAt(0).getWidth());
        assertPadding("a list inside the feed", inside, 0, 0, 0, 0);
        assertPadding("a list that scrolls sideways", sideways, 0, 0, 0, 0);
        assertPadding("a list narrower than the column", narrow, 0, 0, 0, 0);
        assertPadding("the Reels viewer", reels, 0, 0, 0, 0);
        assertTrue(statusLine(), statusLine().contains(CenteredFeed.CENTERED + " 1"));

        columns.check(100);
        assertTrue("set once", statusLine().contains(CenteredFeed.CENTERED + " 1"));
        assertPadding("the feed", feed, 200, 50, 200, 120);
    }

    @Test
    public void facebooksOwnWiderPaddingStays() {
        RecyclerView feed = listOf(true, 4, 300, ViewGroup.LayoutParams.MATCH_PARENT);
        feed.setPadding(250, 0, 260, 0);
        layOut();
        new CenteredFeed.Columns(window).check(0);
        assertPadding("the feed", feed, 250, 0, 260, 0);
        assertFalse(statusLine(), statusLine().contains(CenteredFeed.CENTERED));
    }

    @Test
    public void offOrPausedTheFeedGetsFacebooksPaddingBack() {
        RecyclerView feed = listOf(true, 4, 300, ViewGroup.LayoutParams.MATCH_PARENT);
        feed.setPadding(8, 50, 8, 120);
        layOut();
        CenteredFeed.Columns columns = new CenteredFeed.Columns(window);
        columns.check(0);
        assertPadding("centered", feed, 200, 50, 200, 120);

        Settings.CENTER_FEED_ON_BIG_SCREENS.save(false);
        columns.check(100);
        assertPadding("off", feed, 8, 50, 8, 120);
        assertTrue(statusLine(), statusLine().contains(CenteredFeed.GIVEN_BACK + " 1"));
        columns.check(200);
        assertTrue("given back once", statusLine().contains(CenteredFeed.GIVEN_BACK + " 1"));

        Settings.CENTER_FEED_ON_BIG_SCREENS.save(true);
        columns.check(300);
        assertPadding("on again", feed, 200, 50, 200, 120);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        columns.check(400);
        assertPadding("paused", feed, 8, 50, 8, 120);
    }

    @Test
    public void facebookSettingItsPaddingIsFollowedAndAListItKeepsSettingIsLeftAlone() {
        RecyclerView feed = listOf(true, 4, 300, ViewGroup.LayoutParams.MATCH_PARENT);
        layOut();
        CenteredFeed.Columns columns = new CenteredFeed.Columns(window);
        columns.check(0);
        assertPadding("centered", feed, 200, 0, 200, 0);

        // Long after, Facebook pads the feed for something of its own: that's its padding now.
        feed.setPadding(16, 40, 16, 0);
        columns.check(10_000);
        assertPadding("centered again, Facebook's top kept", feed, 200, 40, 200, 0);
        Settings.CENTER_FEED_ON_BIG_SCREENS.save(false);
        columns.check(10_050);
        assertPadding("Facebook's new padding back", feed, 16, 40, 16, 0);
        Settings.CENTER_FEED_ON_BIG_SCREENS.save(true);
        columns.check(10_100);

        // Then it puts its padding back right after each centering.
        feed.setPadding(16, 40, 16, 0);
        columns.check(10_200);
        feed.setPadding(16, 40, 16, 0);
        columns.check(10_300);
        assertPadding("still centered after two", feed, 200, 40, 200, 0);
        feed.setPadding(16, 40, 16, 0);
        columns.check(10_400);
        assertPadding("left as Facebook has it", feed, 16, 40, 16, 0);
        assertTrue(statusLine(), statusLine().contains(CenteredFeed.LEFT_ALONE + " 1"));
        columns.check(10_500);
        assertPadding("still left alone", feed, 16, 40, 16, 0);

        columns.check(10_400 + CenteredFeed.LEFT_ALONE_MS);
        assertPadding("tried again after a while", feed, 200, 40, 200, 0);
    }

    @Test
    public void aListFoundToPageLaterGetsItsPaddingBack() {
        RecyclerView list = listOf(true, 1, 300, ViewGroup.LayoutParams.MATCH_PARENT);
        layOut();
        CenteredFeed.Columns columns = new CenteredFeed.Columns(window);
        columns.check(0);
        assertPadding("centered", list, 200, 0, 200, 0);

        list.getChildAt(0).setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1400));
        layOut();
        columns.check(100);
        assertPadding("a reel gets the whole width", list, 0, 0, 0, 0);
        assertTrue(statusLine(), statusLine().contains(CenteredFeed.GIVEN_BACK + " 1"));
    }

    @Test
    public void aListWhoseRowsAllFitIsCenteredToo() {
        // Menu on a tablet: every row fits, so the list doesn't scroll, but its rows still stretch.
        RecyclerView menu = listOf(false, 6, 60, ViewGroup.LayoutParams.MATCH_PARENT);
        RecyclerView cards = new RecyclerView(activity, false);
        for (int i = 0; i < 3; i++) {
            cards.addView(new View(activity), new FrameLayout.LayoutParams(300, 200));
        }
        window.addView(cards, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 200));
        RecyclerView page = listOf(false, 1, 1400, ViewGroup.LayoutParams.MATCH_PARENT);
        RecyclerView empty = listOf(false, 0, 60, ViewGroup.LayoutParams.MATCH_PARENT);
        layOut();

        CenteredFeed.Columns columns = new CenteredFeed.Columns(window);
        columns.check(0);
        layOut();
        assertPadding("Menu", menu, 200, 0, 200, 0);
        assertEquals("its rows are the column's width", 600, menu.getChildAt(0).getWidth());
        assertPadding("cards side by side", cards, 0, 0, 0, 0);
        assertPadding("a single page", page, 0, 0, 0, 0);
        assertPadding("a list with no rows yet", empty, 0, 0, 0, 0);

        columns.check(100);
        assertPadding("still centered once its rows are the column's width", menu, 200, 0, 200, 0);
        assertTrue(statusLine(), statusLine().contains(CenteredFeed.CENTERED + " 1"));
        assertFalse(statusLine(), statusLine().contains(CenteredFeed.GIVEN_BACK));
    }

    /** [child] in [parent], filling it unless [tall] says. */
    private <T extends View> T put(ViewGroup parent, T child, int tall) {
        parent.addView(child, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tall));
        return child;
    }

    /** A list of [count] rows [tall] pixels each, in [parent], filling it. */
    private RecyclerView listIn(ViewGroup parent, boolean vertical, int count, int tall) {
        RecyclerView list = put(parent, new RecyclerView(activity, vertical), ViewGroup.LayoutParams.MATCH_PARENT);
        for (int i = 0; i < count; i++) {
            put(list, new View(activity), tall);
        }
        return list;
    }

    /** A FrameLayout with the screen's content frame id, as each of Facebook's tab pages is (588). */
    private FrameLayout contentFrame(ViewGroup parent) {
        FrameLayout page = put(parent, new FrameLayout(activity), ViewGroup.LayoutParams.MATCH_PARENT);
        page.setId(android.R.id.content);
        return page;
    }

    @Test
    public void theFrameHoldingTheListIsNarrowedAndTheBarBesideItKeepsTheWholeWidth() {
        // Menu on the page screen (588): a title bar beside the frame Facebook shows the page in,
        // which holds a layout that isn't a FrameLayout, which holds the list and an empty view.
        FrameLayout page = contentFrame(window);
        View titleBar = put(page, new View(activity), 55);
        FrameLayout holder = put(page, new FrameLayout(activity), ViewGroup.LayoutParams.MATCH_PARENT);
        holder.setPadding(0, 55, 0, 0);
        LinearLayout layout = put(holder, new LinearLayout(activity),
                ViewGroup.LayoutParams.MATCH_PARENT);
        layout.setOrientation(LinearLayout.VERTICAL);
        View empty = new View(activity);
        layout.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));
        RecyclerView menu = new RecyclerView(activity, false);
        for (int i = 0; i < 6; i++) {
            put(menu, new View(activity), 60);
        }
        layout.addView(menu, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1300));
        layOut();

        CenteredFeed.Columns columns = new CenteredFeed.Columns(window);
        columns.check(0);
        layOut();
        assertPadding("the frame holding Menu", holder, 200, 55, 200, 0);
        assertPadding("Menu itself", menu, 0, 0, 0, 0);
        assertEquals("Menu is the column's width", 600, menu.getWidth());
        assertEquals("so are its rows", 600, menu.getChildAt(0).getWidth());
        assertPadding("the screen's frame, which holds the title bar too", page, 0, 0, 0, 0);
        assertEquals("the title bar keeps the whole width", 1000, titleBar.getWidth());

        columns.check(100);
        layOut();
        assertPadding("still centered", holder, 200, 55, 200, 0);
        assertTrue(statusLine(), statusLine().contains(CenteredFeed.CENTERED + " 1"));
        assertFalse(statusLine(), statusLine().contains(CenteredFeed.GIVEN_BACK));

        Settings.CENTER_FEED_ON_BIG_SCREENS.save(false);
        columns.check(200);
        assertPadding("off, Facebook's padding back", holder, 0, 55, 0, 0);
    }

    @Test
    public void theClimbStopsAtTheScreensContentFrameAndAReelsTabKeepsTheWholeWidth() {
        // Two of Facebook's tab pages side by side in the pager: the feed's and the Reels tab's.
        LinearLayout pager = put(window, new LinearLayout(activity),
                ViewGroup.LayoutParams.MATCH_PARENT);
        FrameLayout outer = new FrameLayout(activity);
        pager.addView(outer, new LinearLayout.LayoutParams(1000, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout feedPage = contentFrame(outer);
        RecyclerView feed = listIn(feedPage, true, 4, 300);
        FrameLayout reelsPage = new FrameLayout(activity);
        reelsPage.setId(android.R.id.content);
        pager.addView(reelsPage, new LinearLayout.LayoutParams(1000, ViewGroup.LayoutParams.MATCH_PARENT));
        RecyclerView reels = listIn(reelsPage, true, 3, 1400);
        layOut();

        assertEquals("the feed's frame is its tab page", feedPage, CenteredFeed.hostOf(feed, window));
        CenteredFeed.Columns columns = new CenteredFeed.Columns(window);
        columns.check(0);
        layOut();
        assertPadding("the feed's tab page", feedPage, 200, 0, 200, 0);
        assertPadding("the frame above the tab page", outer, 0, 0, 0, 0);
        assertPadding("the feed itself", feed, 0, 0, 0, 0);
        assertEquals("the feed's rows are the column's width", 600, feed.getChildAt(0).getWidth());
        assertPadding("the Reels tab page", reelsPage, 0, 0, 0, 0);
        assertPadding("the Reels viewer", reels, 0, 0, 0, 0);
    }

    @Test
    public void aSpinnerShowingBesideTheListForAMomentDoesNotMoveItsPadding() {
        FrameLayout page = contentFrame(window);
        LinearLayout refresher = put(page, new LinearLayout(activity),
                ViewGroup.LayoutParams.MATCH_PARENT);
        refresher.setOrientation(LinearLayout.VERTICAL);
        View spinner = new View(activity);
        spinner.setVisibility(View.GONE);
        refresher.addView(spinner, new LinearLayout.LayoutParams(40, 40));
        RecyclerView feed = new RecyclerView(activity, true);
        for (int i = 0; i < 4; i++) {
            put(feed, new View(activity), 300);
        }
        refresher.addView(feed, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1300));
        layOut();

        CenteredFeed.Columns columns = new CenteredFeed.Columns(window);
        columns.check(0);
        layOut();
        assertPadding("centered through its page", page, 200, 0, 200, 0);

        // Pulled to refresh: the spinner shows beside the feed, so the climb stops at the feed.
        spinner.setVisibility(View.VISIBLE);
        layOut();
        columns.check(100);
        layOut();
        assertPadding("the page keeps the padding", page, 200, 0, 200, 0);
        assertPadding("the feed isn't padded on top of it", feed, 0, 0, 0, 0);
        spinner.setVisibility(View.GONE);
        layOut();
        columns.check(200);
        assertPadding("still the page's", page, 200, 0, 200, 0);
        assertTrue(statusLine(), statusLine().contains(CenteredFeed.CENTERED + " 1"));
        assertFalse(statusLine(), statusLine().contains(CenteredFeed.GIVEN_BACK));
    }

    @Test
    public void aListFirstPaddedItselfMovesItsPaddingUpOnceItsFrameHoldsOnlyIt() {
        FrameLayout page = contentFrame(window);
        LinearLayout refresher = put(page, new LinearLayout(activity),
                ViewGroup.LayoutParams.MATCH_PARENT);
        refresher.setOrientation(LinearLayout.VERTICAL);
        View spinner = new View(activity);
        refresher.addView(spinner, new LinearLayout.LayoutParams(40, 40));
        RecyclerView feed = new RecyclerView(activity, true);
        for (int i = 0; i < 4; i++) {
            put(feed, new View(activity), 300);
        }
        refresher.addView(feed, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1300));
        layOut();

        CenteredFeed.Columns columns = new CenteredFeed.Columns(window);
        columns.check(0);
        layOut();
        assertPadding("the spinner showing: the feed pads itself", feed, 200, 0, 200, 0);

        spinner.setVisibility(View.GONE);
        layOut();
        columns.check(100);
        layOut();
        assertPadding("the feed's padding given back", feed, 0, 0, 0, 0);
        assertPadding("its page padded instead", page, 200, 0, 200, 0);
        assertEquals("the feed is the column's width once", 600, feed.getWidth());
    }

    @Test
    public void aListWhoseRowsIgnoreItsPaddingIsLeftWide() {
        // A list with no frame of its own whose rows Facebook sized from the list's width (Litho, 588).
        RecyclerView list = new RecyclerView(activity, true);
        for (int i = 0; i < 4; i++) {
            list.addView(new View(activity), new FrameLayout.LayoutParams(1000, 300));
        }
        window.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layOut();

        CenteredFeed.Columns columns = new CenteredFeed.Columns(window);
        columns.check(0);
        assertPadding("tried once", list, 200, 0, 200, 0);
        layOut();
        columns.check(100);
        assertPadding("its rows ran past the padding, so it's left wide", list, 0, 0, 0, 0);
        assertTrue(statusLine(), statusLine().contains(CenteredFeed.ROWS_STAY_WIDE + " 1"));
        layOut();
        columns.check(200);
        assertPadding("and stays wide", list, 0, 0, 0, 0);
        assertTrue(statusLine(), statusLine().contains(CenteredFeed.CENTERED + " 1"));
    }

    @Test
    public void aFailureGivesThePaddingBackAndStopsThatScreensChecks() {
        RecyclerView feed = listOf(true, 4, 300, ViewGroup.LayoutParams.MATCH_PARENT);
        Failing failing = new Failing(activity);
        window.addView(failing, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 300));
        layOut();
        CenteredFeed.Columns columns = new CenteredFeed.Columns(window);
        columns.check(0);
        assertPadding("centered", feed, 200, 0, 200, 0);

        failing.fail = true;
        columns.check(100);
        assertTrue(columns.stopped);
        assertPadding("given back", feed, 0, 0, 0, 0);
        assertTrue(statusLine(), statusLine().contains("threw"));
        failing.fail = false;
        columns.check(200);
        assertPadding("the checks stay stopped", feed, 0, 0, 0, 0);
    }

    @Test
    public void onlyFacebooksMainAndPageScreensGetChecks() {
        Activity other = Robolectric.buildActivity(Activity.class).setup().get();
        new CenteredFeed.Screens().onActivityResumed(other);
        assertNull(CenteredFeed.WINDOWS.get(other.getWindow().getDecorView()));

        Activity main = Robolectric.buildActivity(FbMainTabActivity.class).setup().get();
        new CenteredFeed.Screens().onActivityResumed(main);
        assertNotNull(CenteredFeed.WINDOWS.get(main.getWindow().getDecorView()));

        // Menu, Notifications from a link, profiles, groups and Pages open on this one (seen on 588).
        Activity page = Robolectric.buildActivity(ImmersiveActivity.class).setup().get();
        new CenteredFeed.Screens().onActivityResumed(page);
        assertNotNull(CenteredFeed.WINDOWS.get(page.getWindow().getDecorView()));
        assertFalse(statusLine(), statusLine().contains("threw"));
    }

    /** A list that scrolls sideways, like a row of cards. */
    static final class Sideways extends RecyclerView {
        Sideways(Context context) {
            super(context, false);
        }

        @Override
        public boolean canScrollHorizontally(int direction) {
            return true;
        }
    }

    /** A list that fails as it's asked whether it scrolls, once told to. */
    static final class Failing extends RecyclerView {
        boolean fail;

        Failing(Context context) {
            super(context, true);
        }

        @Override
        public boolean canScrollVertically(int direction) {
            if (fail) throw new IllegalStateException("for this test");
            return super.canScrollVertically(direction);
        }
    }
}
