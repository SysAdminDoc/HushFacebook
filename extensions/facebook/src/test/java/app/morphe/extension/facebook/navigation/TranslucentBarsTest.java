/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.recyclerview.widget.RecyclerView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * See-through bars (#124): each background that spans a bar gets its alpha scaled, small ones and
 * touch ripples keep theirs, Facebook setting a fill again is scaled again, and off, paused or a
 * hidden bar gives Facebook's own alpha back. With the tab bar at the top both stacked rows count
 * (#171). Android 11 here, so no frost: where it may go is checked on its own, and its nodes on
 * Android 14 (#250).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class TranslucentBarsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int SEE_THROUGH = TranslucentBars.seeThrough(255, TranslucentBars.PLAIN_ALPHA);

    @Before
    public void start() {
        HookStatus.clear();
        Settings.TRANSLUCENT_BARS.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.TRANSLUCENT_BARS.resetToDefault();
        Settings.ROUNDED_TAB_BAR.resetToDefault();
        Settings.SEE_THROUGH_OPACITY.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.BOTTOM_TAB_BAR + ":")) return line;
        }
        return null;
    }

    @Test
    public void theSwitchStartsOffAndBelongsToTheTabBarFamily() {
        Settings.TRANSLUCENT_BARS.resetToDefault();
        assertFalse(Settings.TRANSLUCENT_BARS.get());
        assertFalse("a change shows on the next frame, without a restart", Settings.TRANSLUCENT_BARS.rebootApp);
        assertTrue(PatchFamily.BOTTOM_TAB_BAR.switches.contains(Settings.TRANSLUCENT_BARS));
        assertFalse(TranslucentBars.on());
    }

    @Test
    public void eachBarIsItsHighestBarSizedViewAndTheFeedsPageIsUnderIt() {
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        assertSame(screen.topHolder, TranslucentBars.barOf(screen.topBar, 1600));
        assertSame(screen.tabHolder, TranslucentBars.barOf(screen.tabBar, 1600));
        assertSame(screen.page, TranslucentBars.under(screen.topHolder));
        assertSame(screen.page, TranslucentBars.under(screen.tabHolder));
        assertNull("nothing is drawn before the page", TranslucentBars.under(screen.page));
    }

    /** Rows from [top] to [bottom] of the screen, in the window, which starts the screen's own view lower down. */
    private static Rect onScreen(TranslucentBarsForTests screen, int top, int bottom) {
        int[] at = new int[2];
        screen.window.getLocationInWindow(at);
        return new Rect(at[0], at[1] + top, at[0] + 720, at[1] + bottom);
    }

    @Test
    public void aPageThatOnlyHoldsTheRowsOnScreenIsStillUnderTheBar() {
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        int[] origin = new int[2];
        // The tab holder ends at 1600, past the page, on a phone where it reaches under the navigation bar.
        screen.page.layout(0, 0, 720, 1560);
        assertNull(TranslucentBars.under(screen.tabHolder));
        assertSame(screen.page, TranslucentBars.reachingUnder(screen.tabHolder, onScreen(screen, 1440, 1552), origin));
        assertNull("rows past the page have nothing under them",
                TranslucentBars.reachingUnder(screen.tabHolder, onScreen(screen, 1440, 1600), origin));
        assertNull("no rows on screen", TranslucentBars.reachingUnder(screen.tabHolder, new Rect(), origin));

        screen.page.setVisibility(View.INVISIBLE);
        assertNull("a hidden page", TranslucentBars.reachingUnder(screen.tabHolder, onScreen(screen, 1440, 1552), origin));
    }

    /**
     * #250: Marketplace's React Native page sits in a FrameLayout whose bottom padding stops it at
     * the tab bar's top, so nothing showed through the bar. While the bars are see-through the gap
     * goes and the page runs under the bar. Switching them off gives the gap back. The display is
     * tall enough to show the whole screen, so the tab bar's rows are on it.
     */
    @Test
    @Config(qualifiers = "w720dp-h1800dp")
    public void aPageThatStopsAtTheTabBarRunsUnderIt() {
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        FrameLayout holder = new FrameLayout(screen.activity);
        holder.setPadding(0, 0, 0, 160);
        View market = new View(screen.activity);
        holder.addView(market, new FrameLayout.LayoutParams(720, FrameLayout.LayoutParams.MATCH_PARENT));
        screen.page.addView(holder, new FrameLayout.LayoutParams(720, 1600));
        View banner = new View(screen.activity);
        screen.page.addView(banner, new FrameLayout.LayoutParams(720, 200));
        screen.layOut();
        assertEquals("stops at the tab bar", 1440, market.getBottom());

        screen.bars.update();
        screen.layOut();
        assertEquals("under the tab bar", 1600, market.getBottom());
        assertEquals("a short view nearby", 200, banner.getBottom());
        assertTrue(statusLine(), statusLine().contains(TranslucentBars.STRETCHED + " 1"));

        Settings.TRANSLUCENT_BARS.save(false);
        screen.bars.update();
        screen.layOut();
        assertEquals("the gap back", 160, holder.getPaddingBottom());
        assertEquals(1440, market.getBottom());
    }

    /**
     * #412: with Rounded tab bar on and the bars not see-through, a page that stops at the tab bar's
     * top showed black around the pill. It runs under the pill now, every fill stays Facebook's, and
     * the gap comes back once the pill goes too. The pill draws the bar 90% of its size, so the edge
     * is where the row is laid out, not where it's drawn.
     */
    @Test
    @Config(qualifiers = "w720dp-h1800dp")
    public void aPageRunsUnderARoundedTabBarToo() {
        Settings.TRANSLUCENT_BARS.save(false);
        Settings.ROUNDED_TAB_BAR.save(true);
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        RoundedTabBar.Shaper shaper = new RoundedTabBar.Shaper(screen.window, TranslucentBarsForTests.TabBar.class.getName());
        FrameLayout holder = new FrameLayout(screen.activity);
        holder.setPadding(0, 0, 0, 160);
        View market = new View(screen.activity);
        holder.addView(market, new FrameLayout.LayoutParams(720, FrameLayout.LayoutParams.MATCH_PARENT));
        screen.page.addView(holder, new FrameLayout.LayoutParams(720, 1600));
        screen.layOut();
        shaper.update();
        View pill = TranslucentBars.barOf(screen.tabBar, screen.window.getHeight());
        assertEquals("the bar is a pill", RoundedTabBar.SCALE, pill.getScaleY(), 0f);
        int[] at = new int[2];
        market.getLocationInWindow(at);
        assertEquals("the page's edge is the row's laid-out top", at[1] + market.getHeight(),
                TranslucentBars.laidOutTop(screen.tabBar));

        screen.bars.update();
        screen.layOut();
        assertEquals("under the pill", 1600, market.getBottom());
        assertTrue(statusLine(), statusLine().contains(TranslucentBars.STRETCHED + " 1"));
        assertFalse(statusLine(), statusLine().contains(TranslucentBars.MADE_SEE_THROUGH));
        assertEquals("the tab bar's fill is Facebook's", TranslucentBarsForTests.TAB_COLOUR,
                ((ColorDrawable) screen.tabBar.getBackground()).getColor());

        Settings.ROUNDED_TAB_BAR.save(false);
        screen.bars.update();
        screen.layOut();
        assertEquals("the gap back", 160, holder.getPaddingBottom());
        assertEquals(1440, market.getBottom());
    }

    /**
     * #412: Marketplace lays out its gap inside the stretch's one-second wait and then draws nothing
     * more, so the black strip stayed around the pill until a touch, five seconds or more on a
     * phone. A frame that comes too soon books one more for when the wait ends, that frame
     * stretches the page, and it books none of its own.
     */
    @Test
    @Config(qualifiers = "w720dp-h1800dp")
    public void aGapLaidOutDuringTheWaitIsTakenWithoutATouch() {
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        FrameLayout holder = new FrameLayout(screen.activity);
        View market = new View(screen.activity);
        holder.addView(market, new FrameLayout.LayoutParams(720, FrameLayout.LayoutParams.MATCH_PARENT));
        screen.page.addView(holder, new FrameLayout.LayoutParams(720, 1600));
        screen.layOut();
        screen.bars.update();
        assertEquals("nothing stops at the tab bar yet", 1600, market.getBottom());

        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
        holder.setPadding(0, 0, 0, 160);
        screen.layOut();
        screen.bars.update();
        assertEquals("that frame came too soon to stretch", 1440, market.getBottom());

        shadowOf(screen.window).clearWasInvalidated();
        ShadowLooper.idleMainLooper(TranslucentBars.RESCAN_MS, TimeUnit.MILLISECONDS);
        assertTrue("a frame is booked for when the wait ends", shadowOf(screen.window).wasInvalidated());
        screen.bars.update();
        screen.layOut();
        assertEquals("under the tab bar", 1600, market.getBottom());

        shadowOf(screen.window).clearWasInvalidated();
        ShadowLooper.idleMainLooper(3 * TranslucentBars.RESCAN_MS, TimeUnit.MILLISECONDS);
        assertFalse("the booked frame books none", shadowOf(screen.window).wasInvalidated());
    }

    /** A page held off the tab bar by its own bottom margin, which Facebook keeps putting back, is left alone. */
    @Test
    @Config(qualifiers = "w720dp-h1800dp")
    public void aGapFacebookKeepsPuttingBackIsLeftAlone() {
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        View market = new View(screen.activity);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(720, FrameLayout.LayoutParams.MATCH_PARENT);
        params.bottomMargin = 160;
        screen.page.addView(market, params);
        screen.layOut();
        for (int stretch = 1; stretch <= TranslucentBars.MAX_PUT_BACKS; stretch++) {
            screen.bars.update();
            screen.layOut();
            assertEquals("stretch " + stretch, 1600, market.getBottom());
            params.bottomMargin = 160;
            market.setLayoutParams(params);
            screen.layOut();
            ShadowLooper.idleMainLooper(TranslucentBars.RESCAN_MS + 1, TimeUnit.MILLISECONDS);
        }
        screen.bars.update();
        screen.layOut();
        assertEquals("Facebook's gap kept", 1440, market.getBottom());
        assertTrue(statusLine(), statusLine().contains(TranslucentBars.LEFT_SHORT + " 1"));
    }

    /** A FrameLayout as tall as the screen whose bottom padding stops [page] at the tab bar. */
    private static FrameLayout holding(TranslucentBarsForTests screen, View page) {
        FrameLayout holder = new FrameLayout(screen.activity);
        holder.setPadding(0, 0, 0, 160);
        holder.addView(page, new FrameLayout.LayoutParams(720, FrameLayout.LayoutParams.MATCH_PARENT));
        screen.page.addView(holder, new FrameLayout.LayoutParams(720, 1600));
        return holder;
    }

    private static void nextLook(TranslucentBarsForTests screen) {
        ShadowLooper.idleMainLooper(TranslucentBars.RESCAN_MS + 1, TimeUnit.MILLISECONDS);
        screen.bars.update();
        screen.layOut();
    }

    /**
     * #412: a tab's page waiting beside the screen stops at the tab bar too. Every page found runs
     * under it, and one Facebook keeps putting its gap back on doesn't keep the next from running
     * under it: Marketplace kept a black strip after a trip to Home and back.
     */
    @Test
    @Config(qualifiers = "w720dp-h1800dp")
    public void everyPageAtTheTabBarRunsUnderItEvenPastOneLeftAlone() {
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        View waiting = new View(screen.activity);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(720, FrameLayout.LayoutParams.MATCH_PARENT);
        params.bottomMargin = 160;
        screen.page.addView(waiting, params);
        View market = new View(screen.activity);
        holding(screen, market);
        screen.layOut();

        screen.bars.update();
        screen.layOut();
        assertEquals("the first page under the bar", 1600, waiting.getBottom());
        assertEquals("and the second, in the same look", 1600, market.getBottom());

        // Facebook puts the first page's gap back each time until it's left alone.
        for (int stretch = 2; stretch <= TranslucentBars.MAX_PUT_BACKS + 1; stretch++) {
            params.bottomMargin = 160;
            waiting.setLayoutParams(params);
            screen.layOut();
            nextLook(screen);
        }
        assertEquals("Facebook's gap kept on the first", 1440, waiting.getBottom());
        assertTrue(statusLine(), statusLine().contains(TranslucentBars.LEFT_SHORT + " 1"));

        // Marketplace comes back with its gap, behind the page left alone.
        View back = new View(screen.activity);
        holding(screen, back);
        screen.layOut();
        assertEquals(1440, back.getBottom());
        nextLook(screen);
        assertEquals("the page past the one left alone runs under the bar", 1600, back.getBottom());
        assertEquals("the first still left alone", 1440, waiting.getBottom());
    }

    /** A list of [count] rows [tall] pixels each, the first row 1 pixel tall when [header]. */
    private static RecyclerView listOf(TranslucentBarsForTests screen, int count, int tall, boolean header) {
        RecyclerView list = new RecyclerView(screen.activity, true);
        for (int i = 0; i < count; i++) {
            list.addView(new View(screen.activity), new FrameLayout.LayoutParams(720, header && i == 0 ? 1 : tall));
        }
        return list;
    }

    /**
     * #412: the Reels viewer is a list that pages, each reel as tall as the list. Stretched, its
     * reels kept their height and the next one showed under the tab bar, and Facebook's gap showed
     * the window's grey before that. It keeps its gap with black under it, and gets neither once
     * both switches are off.
     */
    @Test
    @Config(qualifiers = "w720dp-h1800dp")
    public void aListThatPagesKeepsItsGapWithBlackUnderIt() {
        Settings.TRANSLUCENT_BARS.save(false);
        Settings.ROUNDED_TAB_BAR.save(true);
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        RecyclerView reels = listOf(screen, 3, 1440, true);
        FrameLayout holder = holding(screen, reels);
        screen.layOut();

        screen.bars.update();
        screen.layOut();
        assertEquals("the list keeps Facebook's gap", 1440, reels.getBottom());
        assertEquals(160, holder.getPaddingBottom());
        assertFalse(statusLine(), statusLine().contains(TranslucentBars.STRETCHED));
        assertTrue(statusLine(), statusLine().contains(TranslucentBars.BACKED + " 1"));
        TranslucentBars.Backdrop backdrop = screen.bars.backdrops.get(reels);
        assertNotNull(backdrop);
        assertNotNull("it's in the holder's overlay", backdrop.getCallback());
        Rect black = new Rect();
        assertTrue(backdrop.paints(black));
        assertEquals("black from the list's bottom to the holder's", new Rect(0, 1440, 720, 1600), black);
        assertEquals(new Rect(0, 0, 720, 1600), backdrop.getBounds());

        nextLook(screen);
        assertTrue("given once", statusLine().contains(TranslucentBars.BACKED + " 1"));

        Settings.ROUNDED_TAB_BAR.save(false);
        screen.bars.update();
        assertTrue(screen.bars.backdrops.isEmpty());
        assertNull("out of the overlay", backdrop.getCallback());
        assertEquals(1440, reels.getBottom());
    }

    /**
     * #412: a feed's rows are shorter than the list, so it still runs under the bar. A list with
     * no rows yet waits for them, and one stretched before its rows showed it pages gets its gap
     * back and the black instead.
     */
    @Test
    @Config(qualifiers = "w720dp-h1800dp")
    public void aFeedListRunsUnderTheBarAndAListFoundToPageLaterGetsItsGapBack() {
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        RecyclerView feed = listOf(screen, 4, 300, false);
        holding(screen, feed);
        RecyclerView empty = new RecyclerView(screen.activity, true);
        FrameLayout emptyHolder = holding(screen, empty);
        screen.layOut();

        screen.bars.update();
        screen.layOut();
        assertEquals("the feed runs under the bar", 1600, feed.getBottom());
        assertEquals("a list with no rows waits", 1440, empty.getBottom());
        assertTrue(screen.bars.backdrops.isEmpty());

        // The empty list gets one short row and is stretched, then its rows turn out to be pages.
        View first = new View(screen.activity);
        empty.addView(first, new FrameLayout.LayoutParams(720, 300));
        screen.layOut();
        nextLook(screen);
        assertEquals("stretched", 1600, empty.getBottom());
        first.setLayoutParams(new FrameLayout.LayoutParams(720, 1440));
        empty.addView(new View(screen.activity), new FrameLayout.LayoutParams(720, 1440));
        screen.layOut();
        nextLook(screen);
        assertEquals("its gap back", 160, emptyHolder.getPaddingBottom());
        assertEquals(1440, empty.getBottom());
        nextLook(screen);
        assertNotNull("and black under it", screen.bars.backdrops.get(empty));
        assertEquals("the feed stays under the bar", 1600, feed.getBottom());
    }

    /**
     * #412: Facebook keeps every tab's page in one holder, which stops at the tab bar, so it's
     * stretched on Home and the Reels viewer inside it grows with it. Its reels keep their height
     * and the next one showed under the pill. The holder stays stretched for the other tabs, and
     * the viewer gets black over the part its reel doesn't fill, only while its reels page short.
     */
    @Test
    @Config(qualifiers = "w720dp-h1800dp")
    public void aListThatPagesInsideAStretchedPageGetsBlackUnderItsReel() {
        Settings.TRANSLUCENT_BARS.save(false);
        Settings.ROUNDED_TAB_BAR.save(true);
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        FrameLayout tabs = new FrameLayout(screen.activity);
        FrameLayout reelsPage = new FrameLayout(screen.activity);
        RecyclerView reels = listOf(screen, 3, 1440, false);
        reelsPage.addView(reels, new FrameLayout.LayoutParams(720, FrameLayout.LayoutParams.MATCH_PARENT));
        tabs.addView(reelsPage, new FrameLayout.LayoutParams(720, FrameLayout.LayoutParams.MATCH_PARENT));
        FrameLayout holder = holding(screen, tabs);
        screen.layOut();

        screen.bars.update();
        screen.layOut();
        assertEquals("the tabs' holder runs under the bar", 0, holder.getPaddingBottom());
        assertEquals("and the viewer with it", 1600, reels.getBottom());
        assertTrue(statusLine(), statusLine().contains(TranslucentBars.STRETCHED + " 1"));

        nextLook(screen);
        TranslucentBars.Backdrop backdrop = screen.bars.backdrops.get(reels);
        assertNotNull("black under the reel", backdrop);
        assertNotNull("in the viewer's holder's overlay", backdrop.getCallback());
        Rect black = new Rect();
        assertTrue(backdrop.paints(black));
        assertEquals("over the next reel, the gap the stretch took", new Rect(0, 1440, 720, 1600), black);
        assertEquals("the holder stays stretched for the other tabs", 0, holder.getPaddingBottom());

        nextLook(screen);
        assertTrue("given once", statusLine().contains(TranslucentBars.BACKED + " 1"));

        // Reels sized to the taller viewer leave nothing of the next one showing, so no black.
        for (int i = 0; i < reels.getChildCount(); i++) {
            reels.getChildAt(i).setLayoutParams(new FrameLayout.LayoutParams(720, 1600));
        }
        screen.layOut();
        assertFalse("reels that fill the viewer", backdrop.paints(black));

        Settings.ROUNDED_TAB_BAR.save(false);
        screen.bars.update();
        screen.layOut();
        assertTrue(screen.bars.backdrops.isEmpty());
        assertNull("out of the overlay", backdrop.getCallback());
        assertEquals("the gap back", 160, holder.getPaddingBottom());
    }

    /** #412: a feed inside a stretched page scrolls rows shorter than itself, so it gets no black. */
    @Test
    @Config(qualifiers = "w720dp-h1800dp")
    public void aFeedInsideAStretchedPageGetsNoBlack() {
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        FrameLayout tabs = new FrameLayout(screen.activity);
        RecyclerView feed = listOf(screen, 4, 300, false);
        tabs.addView(feed, new FrameLayout.LayoutParams(720, FrameLayout.LayoutParams.MATCH_PARENT));
        holding(screen, tabs);
        screen.layOut();

        screen.bars.update();
        screen.layOut();
        nextLook(screen);
        assertEquals("the feed runs under the bar", 1600, feed.getBottom());
        assertTrue(screen.bars.backdrops.isEmpty());
        assertFalse(statusLine(), statusLine().contains(TranslucentBars.BACKED));
    }

    @Test
    public void aPageALevelUpIsStillUnderTheBar() {
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        FrameLayout wrapper = new FrameLayout(screen.activity);
        screen.window.removeView(screen.tabHolder);
        wrapper.addView(screen.tabHolder, new FrameLayout.LayoutParams(720, 160));
        screen.window.addView(wrapper, new FrameLayout.LayoutParams(720, 160, android.view.Gravity.BOTTOM));
        screen.layOut();

        assertNull("its parent draws nothing before it", TranslucentBars.under(screen.tabHolder));
        assertSame(screen.page, TranslucentBars.reachingUnder(screen.tabHolder, onScreen(screen, 1440, 1552), new int[2]));
    }

    @Test
    public void theReportSaysWhenABarLosesTheViewUnderIt() {
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        HookStatus.invoked(FamilyNames.BOTTOM_TAB_BAR);
        Rect rows = onScreen(screen, 1440, 1552);
        assertSame(screen.page, screen.bars.underNow(screen.tabHolder, rows));
        assertFalse("a page holding the box isn't news", statusLine().contains("Counted"));

        screen.page.layout(0, 0, 720, 1560);
        assertSame(screen.page, screen.bars.underNow(screen.tabHolder, rows));
        assertSame(screen.page, screen.bars.underNow(screen.tabHolder, rows));
        assertTrue(statusLine(), statusLine().contains(TranslucentBars.UNDER_ITS_ROWS + " 1"));

        screen.page.layout(0, 0, 720, 1500);
        assertNull(screen.bars.underNow(screen.tabHolder, rows));
        assertNull(screen.bars.underNow(screen.tabHolder, rows));
        assertTrue(statusLine(), statusLine().contains(TranslucentBars.NOTHING_UNDER + " 1"));
        assertTrue(statusLine(), statusLine().contains("No view under bar 0,1440-720,1600 rows " + rows.toShortString() + " before 0,0-720,88 before 0,0-720,1500 1"));

        screen.page.layout(0, 0, 720, 1600);
        assertSame(screen.page, screen.bars.underNow(screen.tabHolder, rows));
        screen.page.layout(0, 0, 720, 1500);
        assertNull(screen.bars.underNow(screen.tabHolder, rows));
        assertTrue("losing it again counts again", statusLine().contains(TranslucentBars.NOTHING_UNDER + " 2"));
    }

    @Test
    public void onTheFillsGoSeeThroughAndTheIconsBadgesAndRipplesStay() {
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        Drawable ripple = screen.tab.getBackground();
        int rippleAlpha = ripple.getAlpha();

        screen.bars.update();

        assertEquals(SEE_THROUGH, screen.topHolder.getBackground().getAlpha());
        assertEquals("the theme's colour stays, with less of it", TranslucentBarsForTests.TOP_COLOUR & 0xFFFFFF,
                ((ColorDrawable) screen.topHolder.getBackground()).getColor() & 0xFFFFFF);
        assertEquals(SEE_THROUGH, screen.tabBar.getBackground().getAlpha());
        assertEquals("a round button keeps its background", 255, screen.searchButton.getBackground().getAlpha());
        assertEquals("a badge keeps its dot", 255, screen.badge.getBackground().getAlpha());
        assertEquals("a tab's ripple is left alone", rippleAlpha, ripple.getAlpha());
        assertEquals("the page under the bars is left alone", 255, screen.page.getBackground().getAlpha());
        assertEquals(2, screen.bars.fills.size());

        String line = statusLine();
        assertNotNull(line);
        assertTrue(line, line.contains(TranslucentBars.MADE_SEE_THROUGH + " 2"));

        // A second frame changes nothing and counts nothing.
        screen.bars.update();
        assertEquals(SEE_THROUGH, screen.topHolder.getBackground().getAlpha());
        assertTrue(statusLine(), statusLine().contains(TranslucentBars.MADE_SEE_THROUGH + " 2"));
    }

    @Test
    public void withTheTabBarAtTheTopBothRowsOfTheOneHolderGoSeeThrough() {
        TranslucentBarsForTests.TabBarOnTop screen = new TranslucentBarsForTests.TabBarOnTop();
        assertSame("one holder for both rows", screen.header, TranslucentBars.barOf(screen.topBar, 1600));
        assertSame(screen.header, TranslucentBars.barOf(screen.tabBar, 1600));

        screen.bars.update();

        assertEquals(SEE_THROUGH, screen.topRow.getBackground().getAlpha());
        assertEquals(SEE_THROUGH, screen.tabBar.getBackground().getAlpha());
        assertEquals("the line under the rows keeps its colour", 255, screen.line.getBackground().getAlpha());
        assertEquals("the page under the header is left alone", 255, screen.page.getBackground().getAlpha());
        assertEquals(2, screen.bars.fills.size());
        assertTrue(statusLine(), statusLine().contains(TranslucentBars.MADE_SEE_THROUGH + " 2"));
    }

    @Test
    public void theFrostStaysInsideTheRowsOnTheFeedsSide() {
        // A tab bar at the bottom whose holder reaches 100 pixels above its tabs.
        Rect area = new Rect(0, 1340, 720, 1600);
        TranslucentBars.keepInside(area, new Rect(0, 1440, 720, 1600), 1600);
        assertEquals(new Rect(0, 1440, 720, 1600), area);

        // A header at the top: the status bar above its rows stays frosted, nothing below them.
        area = new Rect(0, 0, 720, 260);
        TranslucentBars.keepInside(area, new Rect(0, 48, 720, 226), 1600);
        assertEquals(new Rect(0, 0, 720, 226), area);

        area = new Rect(0, 1340, 720, 1600);
        TranslucentBars.keepInside(area, new Rect(), 1600);
        assertEquals("no rows on screen leaves it as it was", new Rect(0, 1340, 720, 1600), area);
    }

    @Test
    @Config(sdk = 34) // not 33: ColdStartHooksTest needs that sandbox to itself
    public void aFadingFrostIsClippedToItsBoxOutsideTheBlur() {
        // #250: a blurred node drawn half faded spread its clamped edge above a tab bar sliding away.
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        int[] origin = new int[2];
        screen.page.getLocationInWindow(origin);
        TranslucentBars.Frost frost = new TranslucentBars.Frost(screen.page, 47f);
        frost.update(new Rect(0, 1440, 720, 1600), 0.5f, 1, 0f);
        assertEquals("the fade is on the clipping node", 0.5f, frost.node.getAlpha(), 0f);
        assertEquals(1440 - origin[1], frost.node.getTop());
        assertEquals(1600 - origin[1], frost.node.getBottom());
        assertEquals("the blur itself is drawn whole", 1f, frost.blur.getAlpha(), 0f);
        assertEquals(0, frost.blur.getTop());
        assertEquals(160, frost.blur.getBottom());
        assertEquals(720, frost.blur.getRight());
        assertFalse("a square bar's frost is its box", frost.node.getClipToOutline());

        // #344: a rounded tab bar's frost gets the pill's round ends, and loses them with the pill.
        frost.update(new Rect(36, 1429, 684, 1530), 1f, 2, 50.5f);
        assertTrue(frost.node.getClipToOutline());
        frost.update(new Rect(0, 1440, 720, 1600), 1f, 3, 0f);
        assertFalse(frost.node.getClipToOutline());
        frost.remove();
    }

    @Test
    @Config(sdk = 34)
    public void theFrostOfAScrolledHostIsDrawnWhereTheHostShows() {
        // A host's overlay draws in its scrolled content, so a scrolled host put the frost off by
        // its scroll and left the bar plain over a sharp feed (#250).
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        int[] origin = new int[2];
        screen.page.getLocationInWindow(origin);
        screen.page.scrollTo(720, 0);
        TranslucentBars.Frost frost = new TranslucentBars.Frost(screen.page, 47f);
        frost.update(new Rect(0, 1440, 720, 1600), 1f, 1, 0f);
        Tracking canvas = new Tracking();
        canvas.hardware = true;
        // The host's own drawing: its overlay comes after the scroll is applied.
        canvas.translate(-720, 0);

        frost.draw(canvas);

        assertEquals(1, canvas.nodes.size());
        assertEquals("on screen, over the bar", new Rect(0, 1440 - origin[1], 720, 1600 - origin[1]), canvas.nodes.get(0));
        frost.remove();
    }

    @Test
    public void aPageHoldingASurfaceViewIsSeenAnywhereInIt() {
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        Rect box = new Rect();
        assertFalse(TranslucentBars.holdsSurface(screen.page, box));
        FrameLayout reel = new FrameLayout(screen.activity);
        reel.addView(new SurfaceView(screen.activity));
        FrameLayout pageOfReels = new FrameLayout(screen.activity);
        pageOfReels.addView(new View(screen.activity));
        pageOfReels.addView(reel);
        screen.page.addView(pageOfReels);
        screen.layOut();
        assertTrue(TranslucentBars.holdsSurface(screen.page, box));
        assertTrue(TranslucentBars.holdsSurface(new SurfaceView(screen.activity), box));
    }

    @Test
    public void theFrostIsCopiedAroundAFeedVideoAndClearedWhereItIs() {
        // #250: a feed video anywhere on Home kept both bars plain, which read as the frost turning
        // into a dark strip each time one scrolled into view. The copy now leaves the video out.
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        Drawn far = new Drawn(screen.activity);
        FrameLayout post = new FrameLayout(screen.activity);
        Drawn title = new Drawn(screen.activity);
        Video video = new Video(screen.activity);
        post.addView(title, new FrameLayout.LayoutParams(720, 100));
        FrameLayout.LayoutParams below = new FrameLayout.LayoutParams(720, 200);
        below.topMargin = 100;
        post.addView(video, below);
        screen.page.addView(far, new FrameLayout.LayoutParams(720, 600));
        FrameLayout.LayoutParams low = new FrameLayout.LayoutParams(720, 300);
        low.topMargin = 1350;
        screen.page.addView(post, low);
        screen.layOut();
        Rect tabBar = new Rect(0, 1400, 720, 1600);
        Tracking canvas = new Tracking();

        TranslucentBars.record(screen.page, canvas, tabBar, new Rect());

        assertEquals("the post's title under the bar is copied", 1, title.draws);
        assertEquals("the video itself never is", 0, video.draws);
        assertEquals("a post nowhere near the bar isn't drawn", 0, far.draws);
        assertEquals("the copy is cleared where the video is", 1, canvas.cleared.size());
        assertEquals(new Rect(0, 1450, 720, 1650), canvas.cleared.get(0));
        assertEquals("the area is given back as it was", new Rect(0, 1400, 720, 1600), tabBar);

        post.removeView(video);
        screen.layOut();
        canvas = new Tracking();
        TranslucentBars.record(screen.page, canvas, tabBar, new Rect());
        assertEquals("with no video the page is drawn whole", 1, far.draws);
        assertTrue(canvas.cleared.isEmpty());
    }

    /** A view that counts its draws. */
    private static final class Drawn extends View {
        int draws;

        Drawn(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            draws++;
        }
    }

    /** A video's SurfaceView that counts its draws. */
    private static final class Video extends SurfaceView {
        int draws;

        Video(Context context) {
            super(context);
        }

        @Override
        public void draw(Canvas canvas) {
            draws++;
            super.draw(canvas);
        }
    }

    /** A canvas that keeps its translation, each cleared box and each node drawn, in its starting coordinates. */
    private static final class Tracking extends Canvas {
        final List<Rect> cleared = new ArrayList<>();
        final List<Rect> nodes = new ArrayList<>();
        boolean hardware;
        private final Deque<float[]> stack = new ArrayDeque<>();
        private float x;
        private float y;

        @Override
        public int save() {
            stack.push(new float[]{x, y});
            return stack.size();
        }

        @Override
        public void restore() {
            float[] top = stack.pop();
            x = top[0];
            y = top[1];
        }

        @Override
        public void restoreToCount(int count) {
            while (stack.size() >= count) restore();
        }

        @Override
        public void translate(float dx, float dy) {
            x += dx;
            y += dy;
        }

        @Override
        public void drawRect(float left, float top, float right, float bottom, Paint paint) {
            if (paint.getXfermode() instanceof PorterDuffXfermode) {
                cleared.add(new Rect(Math.round(left + x), Math.round(top + y), Math.round(right + x), Math.round(bottom + y)));
            }
        }

        @Override
        public boolean isHardwareAccelerated() {
            return hardware;
        }

        @Override
        public void drawRenderNode(RenderNode node) {
            nodes.add(new Rect(Math.round(node.getLeft() + x), Math.round(node.getTop() + y),
                    Math.round(node.getRight() + x), Math.round(node.getBottom() + y)));
        }
    }

    @Test
    public void aHiddenPageHoldingASurfaceViewDoesntCount() {
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        FrameLayout reels = new FrameLayout(screen.activity);
        reels.addView(new SurfaceView(screen.activity));
        screen.page.addView(reels);
        screen.layOut();
        Rect box = new Rect();
        assertTrue(TranslucentBars.holdsSurface(screen.page, box));
        reels.setVisibility(View.GONE);
        assertFalse("a gone page", TranslucentBars.holdsSurface(screen.page, box));
        reels.setVisibility(View.INVISIBLE);
        assertFalse("an invisible page", TranslucentBars.holdsSurface(screen.page, box));
    }

    @Test
    public void anotherTabsPageWaitingBesideTheScreenDoesntCount() {
        // Facebook 583 lays each opened tab's page beside the next in one pager and scrolls it to
        // the one shown, so Reels waits beside Home with its video once it's been opened (#188).
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        LinearLayout pager = new LinearLayout(screen.activity);
        FrameLayout home = new FrameLayout(screen.activity);
        FrameLayout reels = new FrameLayout(screen.activity);
        reels.addView(new SurfaceView(screen.activity));
        pager.addView(home, new LinearLayout.LayoutParams(720, 1600));
        pager.addView(reels, new LinearLayout.LayoutParams(720, 1600));
        screen.page.addView(pager, new FrameLayout.LayoutParams(1440, 1600));
        screen.layOut();
        Rect box = new Rect();
        assertFalse("Home shown, Reels beside it", TranslucentBars.holdsSurface(screen.page, box));
        pager.scrollTo(720, 0);
        assertTrue("Reels shown", TranslucentBars.holdsSurface(screen.page, box));
        pager.scrollTo(360, 0);
        assertTrue("halfway between them", TranslucentBars.holdsSurface(screen.page, box));
        pager.scrollTo(0, 0);
        reels.setTranslationX(-720);
        assertTrue("slid over Home", TranslucentBars.holdsSurface(screen.page, box));
        reels.setTranslationX(0);
        assertFalse(TranslucentBars.holdsSurface(screen.page, box));
        assertEquals("the scratch box is the page's", new Rect(0, 0, 720, 1600), box);
    }

    @Test
    public void aColourFacebookSetsAgainIsMadeSeeThroughAgain() {
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        screen.bars.update();
        // A theme change: Facebook's own setBackgroundColor reuses the ColorDrawable, fully drawn.
        screen.topHolder.setBackgroundColor(0xFFFFFFFF);
        assertEquals(255, screen.topHolder.getBackground().getAlpha());
        screen.bars.update();
        assertEquals(SEE_THROUGH, screen.topHolder.getBackground().getAlpha());
        assertEquals(0xFFFFFF, ((ColorDrawable) screen.topHolder.getBackground()).getColor() & 0xFFFFFF);

        Settings.TRANSLUCENT_BARS.save(false);
        screen.bars.update();
        assertEquals("the new colour comes back whole", 0xFFFFFFFF, ((ColorDrawable) screen.topHolder.getBackground()).getColor());
    }

    @Test
    public void facebooksOwnFadeIsScaledAndItsAlphaIsWhatComesBack() {
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        GradientDrawable header = new GradientDrawable();
        header.setColor(0xFF1877F2);
        screen.topHolder.setBackground(header);
        screen.bars.update();
        assertEquals(SEE_THROUGH, header.getAlpha());

        // Facebook fades its header's drawable as it scrolls.
        header.setAlpha(0x80);
        screen.bars.update();
        assertEquals(TranslucentBars.seeThrough(0x80, false), header.getAlpha());

        Settings.TRANSLUCENT_BARS.save(false);
        screen.bars.update();
        assertEquals("Facebook's own fade comes back, not full", 0x80, header.getAlpha());
        assertTrue(screen.bars.fills.isEmpty());
    }

    @Test
    public void offOrPausedEveryFillGetsFacebooksAlphaBackAndOnAgainItGoesAgain() {
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        screen.bars.update();
        assertEquals(SEE_THROUGH, screen.tabBar.getBackground().getAlpha());

        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(why);
            screen.bars.update();
            assertEquals("paused by " + why, 255, screen.topHolder.getBackground().getAlpha());
            assertEquals("paused by " + why, 255, screen.tabBar.getBackground().getAlpha());
            assertTrue(screen.bars.fills.isEmpty());
            PauseForTests.resume();
            screen.bars.update();
            assertEquals("running again after " + why, SEE_THROUGH, screen.tabBar.getBackground().getAlpha());
        }

        Settings.TRANSLUCENT_BARS.save(false);
        screen.bars.update();
        assertEquals(255, screen.topHolder.getBackground().getAlpha());
        assertEquals(255, screen.tabBar.getBackground().getAlpha());
        assertEquals(TranslucentBarsForTests.TOP_COLOUR, ((ColorDrawable) screen.topHolder.getBackground()).getColor());
        assertTrue(statusLine(), statusLine().contains(TranslucentBars.GIVEN_BACK));
        // Off, frames leave Facebook's alpha alone.
        screen.topHolder.getBackground().setAlpha(0x40);
        int facebooks = screen.topHolder.getBackground().getAlpha();
        screen.bars.update();
        assertEquals(facebooks, screen.topHolder.getBackground().getAlpha());
    }

    @Test
    public void aDrawableFacebookTakesOutOrHidesGetsItsAlphaBack() {
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        screen.bars.update();
        Drawable old = screen.topHolder.getBackground();
        ColorDrawable replacement = new ColorDrawable(0xFF101010);
        screen.topHolder.setBackground(replacement);
        screen.bars.update();
        assertEquals("one Facebook took out may go somewhere else", 255, old.getAlpha());
        assertEquals(SEE_THROUGH, replacement.getAlpha());

        screen.tabHolder.setVisibility(View.GONE);
        screen.layOut();
        screen.bars.update();
        assertEquals("a hidden bar's fill comes back", 255, screen.tabBar.getBackground().getAlpha());
        assertEquals(SEE_THROUGH, replacement.getAlpha());
    }

    @Test
    public void aStoppedWindowGivesEverythingBackAndStopsAsking() {
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        screen.bars.update();
        screen.bars.stop();
        assertEquals(255, screen.topHolder.getBackground().getAlpha());
        screen.bars.update();
        assertEquals(255, screen.topHolder.getBackground().getAlpha());
    }

    @Test
    public void onlyTheMainScreenGetsBars() {
        Activity other = Robolectric.buildActivity(Activity.class).setup().get();
        new TranslucentBars.Screens().onActivityResumed(other);
        synchronized (TranslucentBars.WINDOWS) {
            assertFalse(TranslucentBars.WINDOWS.containsKey(other.getWindow().getDecorView()));
        }
        assertEquals("Facebook's main screen", "com.facebook.katana.activity.FbMainTabActivity", TranslucentBars.MAIN_SCREEN);
    }

    @Test
    public void theAlphaKeepsMostOfTheBarsColour() {
        assertEquals(0xC0, TranslucentBars.seeThrough(255, false));
        assertEquals(0xA6, TranslucentBars.seeThrough(255, true));
        assertEquals(0, TranslucentBars.seeThrough(0, true));
        assertTrue("frost lets more through", TranslucentBars.seeThrough(255, true) < TranslucentBars.seeThrough(255, false));
    }

    /** #425: Bar opacity picks how much of the bars' color stays, frost or not, and a new pick shows on the next frame. */
    @Test
    public void barOpacityPicksWhatStaysAndANewPickShowsOnTheNextFrame() {
        assertEquals("blank is the stock amount", 0xC0, TranslucentBars.keptAlpha(false));
        assertEquals(0xA6, TranslucentBars.keptAlpha(true));
        assertEquals(75, TranslucentBars.PLAIN_PERCENT);
        assertEquals(65, TranslucentBars.FROSTED_PERCENT);

        Settings.SEE_THROUGH_OPACITY.save("30");
        assertEquals(77, TranslucentBars.keptAlpha(false));
        assertEquals("the same over frost", 77, TranslucentBars.keptAlpha(true));
        assertEquals(77, TranslucentBars.seeThrough(255, true));
        Settings.SEE_THROUGH_OPACITY.save("0");
        assertEquals("nothing of the color", 0, TranslucentBars.keptAlpha(true));
        Settings.SEE_THROUGH_OPACITY.save("100");
        assertEquals("as solid as Facebook draws it", 255, TranslucentBars.keptAlpha(true));
        assertEquals(100, TranslucentBars.opacityPercent("250"));
        assertEquals(-1, TranslucentBars.opacityPercent("abc"));
        assertEquals(-1, TranslucentBars.opacityPercent(" "));
        assertEquals(-1, TranslucentBars.opacityPercent(null));

        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        GradientDrawable header = new GradientDrawable();
        header.setColor(0xFF1877F2);
        screen.topHolder.setBackground(header);
        Settings.SEE_THROUGH_OPACITY.save("");
        screen.bars.update();
        assertEquals(0xC0, header.getAlpha());

        Settings.SEE_THROUGH_OPACITY.save("40");
        screen.bars.update();
        assertEquals("a new pick shows on the next frame", 102, header.getAlpha());

        Settings.TRANSLUCENT_BARS.save(false);
        screen.bars.update();
        assertEquals("Facebook's own alpha comes back", 255, header.getAlpha());
    }
}
