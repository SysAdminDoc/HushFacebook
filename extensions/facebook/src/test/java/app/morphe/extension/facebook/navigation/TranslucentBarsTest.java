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

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

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

    private static final int SEE_THROUGH = TranslucentBars.seeThrough(255, false);

    @Before
    public void start() {
        HookStatus.clear();
        Settings.TRANSLUCENT_BARS.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.TRANSLUCENT_BARS.resetToDefault();
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
        frost.update(new Rect(0, 1440, 720, 1600), 0.5f, 1);
        assertEquals("the fade is on the clipping node", 0.5f, frost.node.getAlpha(), 0f);
        assertEquals(1440 - origin[1], frost.node.getTop());
        assertEquals(1600 - origin[1], frost.node.getBottom());
        assertEquals("the blur itself is drawn whole", 1f, frost.blur.getAlpha(), 0f);
        assertEquals(0, frost.blur.getTop());
        assertEquals(160, frost.blur.getBottom());
        assertEquals(720, frost.blur.getRight());
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

    /** A canvas that keeps its translation and each cleared box, in its starting coordinates. */
    private static final class Tracking extends Canvas {
        final List<Rect> cleared = new ArrayList<>();
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
}
