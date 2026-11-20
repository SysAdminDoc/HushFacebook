/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * #466: with See-through bars or Rounded tab bar on, each reel's row fills the Reels page, so its
 * video runs under the tab bar and the gesture bar down to the screen's edge. The reel's name,
 * caption and buttons sit above the bar instead of behind it, move with their reel through a swipe,
 * and go back where Facebook has them when the tabs go away or the switches go off.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReelDetailsLiftTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.TRANSLUCENT_BARS.resetToDefault();
        Settings.ROUNDED_TAB_BAR.resetToDefault();
        TabBarScrollAway.forgetBuilt();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.BOTTOM_TAB_BAR + ":")) return line;
        }
        return null;
    }

    private static int bottomOnScreen(View view) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        return at[1] + view.getHeight();
    }

    /**
     * One reel's row the way 588 lays it out on the S22: a host with one child, then the branch
     * holding the full-size tap layers, the details (name, caption, the buttons down the side) and
     * the video's holder with its surface.
     */
    private static final class Reel {
        final FrameLayout row;
        final FrameLayout taps;
        final FrameLayout details;
        final Button like;
        final FrameLayout video;

        Reel(TranslucentBarsForTests screen, FrameLayout list, int top, int height) {
            row = new FrameLayout(screen.activity);
            FrameLayout host = new FrameLayout(screen.activity);
            row.addView(host, new FrameLayout.LayoutParams(MATCH, MATCH));
            FrameLayout branch = new FrameLayout(screen.activity);
            host.addView(branch, new FrameLayout.LayoutParams(MATCH, MATCH));

            taps = new FrameLayout(screen.activity);
            taps.setClickable(true);
            branch.addView(taps, new FrameLayout.LayoutParams(MATCH, MATCH));

            details = new FrameLayout(screen.activity);
            TextView name = new TextView(screen.activity);
            name.setText("A Page");
            details.addView(name, new FrameLayout.LayoutParams(400, 60, Gravity.BOTTOM | Gravity.START));
            like = new Button(screen.activity);
            details.addView(like, new FrameLayout.LayoutParams(96, 96, Gravity.BOTTOM | Gravity.END));
            branch.addView(details, new FrameLayout.LayoutParams(MATCH, MATCH));

            video = new FrameLayout(screen.activity);
            video.addView(new SurfaceView(screen.activity), new FrameLayout.LayoutParams(MATCH, MATCH));
            View mute = new View(screen.activity);
            mute.setClickable(true);
            video.addView(mute, new FrameLayout.LayoutParams(80, 80, Gravity.TOP | Gravity.END));
            branch.addView(video, new FrameLayout.LayoutParams(MATCH, MATCH));

            FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(MATCH, height, Gravity.TOP);
            at.topMargin = top;
            list.addView(row, at);
        }

        void scroll(int by) {
            row.offsetTopAndBottom(by);
        }
    }

    /** The Reels page: its list over the whole page, one reel paged in and the next below it. */
    private static final class Reels {
        final FrameLayout list;
        final Reel shown;
        final Reel next;

        Reels(TranslucentBarsForTests screen, int rowHeight) {
            list = new FrameLayout(screen.activity);
            shown = new Reel(screen, list, 0, rowHeight);
            next = new Reel(screen, list, rowHeight, rowHeight);
            screen.page.addView(list, new FrameLayout.LayoutParams(MATCH, MATCH));
            screen.layOut();
            TabBarScrollAway.reelsList = new WeakReference<>(list);
        }
    }

    @Test
    @Config(qualifiers = "w720dp-h1800dp")
    public void aReelsDetailsSitAboveTheTabBarWhileItsVideoRunsUnderIt() {
        Settings.TRANSLUCENT_BARS.save(true);
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        Reels reels = new Reels(screen, 1600);
        assertNotNull("the list is shown", TabBarScrollAway.reelsListShown());
        assertSame(reels.shown.details.getParent(), ReelDetailsLift.branchOf(reels.shown.row));
        int[] at = new int[2];
        screen.tabBar.getLocationInWindow(at);
        int tabsTop = at[1];

        screen.bars.update();
        assertEquals("the buttons end where the tabs start", tabsTop, bottomOnScreen(reels.shown.like));
        assertEquals(-160f, reels.shown.details.getTranslationY(), 0f);
        assertEquals("the video runs on under the bar", 0f, reels.shown.video.getTranslationY(), 0f);
        assertEquals("down to the screen's bottom", bottomOnScreen(screen.tabHolder), bottomOnScreen(reels.shown.video));
        assertEquals("the taps stay the whole reel", 0f, reels.shown.taps.getTranslationY(), 0f);
        assertEquals("the next reel's come up with it", -160f, reels.next.details.getTranslationY(), 0f);
        assertTrue(statusLine(), statusLine().contains(ReelDetailsLift.LIFTED + " 2"));

        screen.bars.update();
        assertEquals("one frame's lift, not two", -160f, reels.shown.details.getTranslationY(), 0f);

        reels.shown.scroll(-700);
        reels.next.scroll(-700);
        screen.bars.update();
        assertEquals("through a swipe, each reel's details move with it", -160f, reels.shown.details.getTranslationY(), 0f);
        assertEquals(-160f, reels.next.details.getTranslationY(), 0f);
        reels.shown.scroll(700);
        reels.next.scroll(700);

        screen.tabHolder.setVisibility(View.GONE);
        screen.bars.update();
        assertEquals("back at the bottom with the tabs gone", 0f, reels.shown.details.getTranslationY(), 0f);

        screen.tabHolder.setVisibility(View.VISIBLE);
        screen.layOut();
        screen.bars.update();
        assertEquals("above them again when they come back", -160f, reels.shown.details.getTranslationY(), 0f);

        Settings.TRANSLUCENT_BARS.save(false);
        screen.bars.update();
        assertEquals("Facebook's place back with the switch off", 0f, reels.shown.details.getTranslationY(), 0f);
        assertEquals(0f, reels.next.details.getTranslationY(), 0f);
        assertTrue(screen.bars.details.held.isEmpty());
    }

    @Test
    @Config(qualifiers = "w720dp-h1800dp")
    public void roundedTabBarLiftsThemTooAndKeepsFacebooksOwnMove() {
        Settings.ROUNDED_TAB_BAR.save(true);
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        Reels reels = new Reels(screen, 1600);
        reels.shown.details.setTranslationY(-20f);

        screen.bars.update();
        assertEquals("on top of where Facebook moved them", -180f, reels.shown.details.getTranslationY(), 0f);

        Settings.ROUNDED_TAB_BAR.save(false);
        screen.bars.update();
        assertEquals("Facebook's own move is left", -20f, reels.shown.details.getTranslationY(), 0f);
    }

    @Test
    @Config(qualifiers = "w720dp-h1800dp")
    public void whatLithoSlidesInsideTheDetailsStaysAboveTheTabBar() {
        // 588 slides a reel's name and caption up 98 px when its comment preview comes in.
        Settings.TRANSLUCENT_BARS.save(true);
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        Reels reels = new Reels(screen, 1600);
        View name = reels.shown.details.getChildAt(0);
        screen.bars.update();
        assertEquals(-160f, reels.shown.details.getTranslationY(), 0f);

        ImmersiveEdgeTest.lithoSetsY(name, reels.shown.row, name.getTop() - 98);
        assertEquals("Litho's slide lands where it meant, under the lift", -98f, name.getTranslationY(), 0f);
        screen.bars.update();
        assertEquals(-98f, name.getTranslationY(), 0f);
        assertEquals(-160f, reels.shown.details.getTranslationY(), 0f);
    }

    @Test
    @Config(qualifiers = "w720dp-h1800dp")
    public void aRowThatEndsAboveTheBarIsLeftAlone() {
        // A row left at Facebook's height, shorter than the list, already ends above the tabs.
        Settings.TRANSLUCENT_BARS.save(true);
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        Reels reels = new Reels(screen, 1400);

        screen.bars.update();
        assertEquals(0f, reels.shown.details.getTranslationY(), 0f);
        assertTrue(screen.bars.details.held.isEmpty());
    }

    @Test
    @Config(qualifiers = "w720dp-h1800dp")
    public void aListOutOfSightGivesTheDetailsBack() {
        Settings.TRANSLUCENT_BARS.save(true);
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        Reels reels = new Reels(screen, 1600);
        screen.bars.update();
        assertEquals(-160f, reels.shown.details.getTranslationY(), 0f);

        reels.list.setVisibility(View.GONE);
        screen.bars.update();
        assertEquals("another tab shown", 0f, reels.shown.details.getTranslationY(), 0f);
    }

    @Test
    public void onlyAPartWithASmallButtonAndNoVideoIsLifted() {
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        Reels reels = new Reels(screen, 1600);
        ViewGroup branch = ReelDetailsLift.branchOf(reels.shown.row);
        assertNotNull(branch);
        assertTrue("name, caption and buttons", ReelDetailsLift.lifts(reels.shown.details, branch));
        assertFalse("taps the size of the reel", ReelDetailsLift.lifts(reels.shown.taps, branch));
        assertFalse("the video, though it has a small button too", ReelDetailsLift.lifts(reels.shown.video, branch));
        reels.shown.details.setVisibility(View.GONE);
        assertFalse("details Facebook hides, as Clean mode does", ReelDetailsLift.lifts(reels.shown.details, branch));
    }
}
