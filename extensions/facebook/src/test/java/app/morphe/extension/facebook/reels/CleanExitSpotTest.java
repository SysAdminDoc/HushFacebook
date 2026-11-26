/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.facebook.litho.ComponentHost;
import com.facebook.litho.LithoView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.time.Duration;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Facebook's way out of Clean mode lands where the sidebar's Clean mode button was (#391). The
 * pages copy the S22's Reels tab on 588 as dumpsys has it (2026-11-19): pages 1080x2041 in the list,
 * the sidebar button at 945,1519-1080,1691 of its page, and in Clean mode the way out at
 * 939,1726-1063,1850 under the page's top host, 183px lower in the same column.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w384dp-h823dp-xxhdpi")
public class CleanExitSpotTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout list;
    private LithoView page;
    private ComponentHost top;
    private ComponentHost sidebar;
    private ComponentHost button;
    /** Facebook's Clean mode store, held here: ReelCleanMode keeps it weakly, and a GC lost a bare one. */
    private final Object store = new Object();

    @Before
    public void setUp() {
        CleanExitSpot.reset();
        HookStatus.clear();
        ReelCleanMode.keepStore(store);
        Settings.REEL_CLEAN_BUTTON.save(true);
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        FrameLayout root = new FrameLayout(activity);
        list = new FrameLayout(activity);
        place(root, list, 0, 0, 1080, 2241);
        page = new LithoView(activity);
        place(list, page, 0, 1, 1080, 2042);
        top = clickable(new ComponentHost(activity));
        place(page, top, 0, 0, 1080, 2047);
        place(top, new ComponentHost(activity), 0, 0, 1080, 2041);
        // The sidebar: overlay, its row, the column, the column's list, the button's slot, the button.
        sidebar = new ComponentHost(activity);
        place(top, sidebar, 0, 651, 1080, 2041);
        ComponentHost row = new ComponentHost(activity);
        place(sidebar, row, 0, 0, 1080, 1263);
        ComponentHost column = new ComponentHost(activity);
        place(row, column, 945, 0, 1080, 1229);
        ComponentHost buttons = new ComponentHost(activity);
        place(column, buttons, 0, 0, 135, 1229);
        for (int at = 0; at < 868; at += 174) place(buttons, clickable(new ComponentHost(activity)), 0, at, 135, at + 174);
        ComponentHost slot = new ComponentHost(activity);
        place(buttons, slot, 0, 868, 135, 1040);
        button = clickable(new ComponentHost(activity));
        place(slot, button, 0, 0, 135, 172);
        place(buttons, clickable(new ComponentHost(activity)), 6, 1035, 130, 1159);
        activity.setContentView(root);
        layOut();
    }

    @After
    public void tearDown() {
        BurnInGuard.resetForTests();
        PauseForTests.resume();
        Settings.REEL_CLEAN_BUTTON.resetToDefault();
        Settings.REEL_CLEAN_EXIT_FAINT.resetToDefault();
        Settings.BURN_IN_GUARD.resetToDefault();
        ReelCleanMode.store = null;
        CleanExitSpot.reset();
        HookStatus.clear();
    }

    @Test
    public void theWayOutLandsWhereTheSidebarButtonWas() {
        ReelCleanMode.enterClean(button);
        View exit = goClean(page, top);

        assertEquals(11f, exit.getTranslationX(), 0f);
        assertEquals(-183f, exit.getTranslationY(), 0f);
        assertTrue(reported(CleanExitSpot.MOVED + " 1"));

        // The next reel down the list is the same size, so its way out goes to the same spot.
        LithoView next = new LithoView(activity);
        place(list, next, 0, 2042, 1080, 4083);
        ComponentHost nextTop = clickable(new ComponentHost(activity));
        place(next, nextTop, 0, 0, 1080, 2047);
        View nextExit = goClean(next, nextTop);
        assertEquals(11f, nextExit.getTranslationX(), 0f);
        assertEquals(-183f, nextExit.getTranslationY(), 0f);
    }

    @Test
    public void aPageShowingItsSidebarOrTwoButtonsIsLeftAlone() {
        ReelCleanMode.enterClean(button);
        layOut();
        assertNull("a sidebar page had a way out", CleanExitSpot.exitIn(page));
        assertEquals(0f, button.getTranslationY(), 0f);

        ComponentHost exit = clickable(new ComponentHost(activity));
        place(top, exit, 939, 1726, 1063, 1850);
        ComponentHost other = clickable(new ComponentHost(activity));
        place(top, other, 939, 1500, 1063, 1624);
        layOut();
        assertEquals("one of two buttons in the corner moved", 0f, exit.getTranslationY(), 0f);
        assertEquals("one of two buttons in the corner moved", 0f, other.getTranslationY(), 0f);
    }

    @Test
    public void aPageOfAnotherSizeKeepsFacebooksSpot() {
        ReelCleanMode.enterClean(button);
        LithoView taller = new LithoView(activity);
        place(list, taller, 0, 2042, 1080, 4283);
        ComponentHost tallerTop = clickable(new ComponentHost(activity));
        place(taller, tallerTop, 0, 0, 1080, 2241);
        View exit = goClean(taller, tallerTop);
        assertEquals(0f, exit.getTranslationY(), 0f);
    }

    @Test
    public void offPausedOrGoneTheWayOutGoesBack() {
        ReelCleanMode.enterClean(button);
        View exit = goClean(page, top);
        assertEquals(-183f, exit.getTranslationY(), 0f);

        Settings.REEL_CLEAN_BUTTON.save(false);
        layOut();
        assertEquals("off, the way out stayed moved", 0f, exit.getTranslationY(), 0f);
        assertEquals(0f, exit.getTranslationX(), 0f);

        Settings.REEL_CLEAN_BUTTON.save(true);
        layOut();
        assertEquals(-183f, exit.getTranslationY(), 0f);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        layOut();
        assertEquals("paused, the way out stayed moved", 0f, exit.getTranslationY(), 0f);
        PauseForTests.resume();
        layOut();
        assertEquals(-183f, exit.getTranslationY(), 0f);

        // Back out of Clean mode the sidebar returns and Litho hands the host on: it goes back.
        sidebar.setVisibility(View.VISIBLE);
        top.removeView(exit);
        layOut();
        assertEquals("a host Litho took off the page stayed moved", 0f, exit.getTranslationY(), 0f);
    }

    @Test
    public void facebooksOwnMoveIsKept() {
        ReelCleanMode.enterClean(button);
        View exit = goClean(page, top);
        // Facebook sets its own 20px down after the move: that holds, and the move goes on top.
        exit.setTranslationY(20f);
        layOut();
        assertEquals(-163f, exit.getTranslationY(), 0f);
        layOut();
        assertEquals(-163f, exit.getTranslationY(), 0f);
        Settings.REEL_CLEAN_BUTTON.save(false);
        layOut();
        assertEquals("Facebook's own move was lost", 20f, exit.getTranslationY(), 0f);
    }

    @Test
    public void aTapOffTheSidebarKeepsNoSpot() {
        ComponentHost middle = clickable(new ComponentHost(activity));
        place(top, middle, 450, 931, 630, 1111);
        layOut();
        ReelCleanMode.enterClean(middle);
        ReelCleanMode.enterClean(page);
        ReelCleanMode.enterClean(new View(activity));
        ReelCleanMode.enterClean(null);
        assertTrue("a spot was kept from a view that isn't the sidebar button", CleanExitSpot.spots.isEmpty());
        View exit = goClean(page, top);
        assertEquals(0f, exit.getTranslationY(), 0f);
    }

    @Test
    public void withTheButtonOffTheTapKeepsNothing() {
        Settings.REEL_CLEAN_BUTTON.save(false);
        ReelCleanMode.enterClean(button);
        assertTrue(CleanExitSpot.spots.isEmpty());
        assertTrue(CleanExitSpot.watched.isEmpty());
    }

    @Test
    public void aDetachedListGivesEveryHostBack() {
        ReelCleanMode.enterClean(button);
        View exit = goClean(page, top);
        assertEquals(-183f, exit.getTranslationY(), 0f);
        ((ViewGroup) list.getParent()).removeView(list);
        assertEquals(0f, exit.getTranslationY(), 0f);
        assertNull(CleanExitSpot.moved.get(exit));
    }

    /**
     * Faint Clean mode button (#519): with no sidebar tap at all, as with Always use Clean mode, a
     * page the scrubber mounts in gets its list watched and its way out fades, still clickable and
     * where Facebook put it. Facebook's own alpha change is kept under the fade, and off, paused or
     * taken off the page the way out goes back.
     */
    @Test
    public void faintCleanModeButtonFadesTheWayOut() {
        Settings.REEL_CLEAN_BUTTON.save(false);
        Settings.REEL_CLEAN_EXIT_FAINT.save(true);
        CleanExitSpot.sawPage(page);
        View exit = goClean(page, top);
        assertEquals(CleanExitSpot.FAINT, exit.getAlpha(), 0.001f);
        assertTrue(exit.isClickable());
        assertEquals("a faint way out stays where Facebook put it", 0f, exit.getTranslationY(), 0f);
        assertTrue(reported(CleanExitSpot.FAINTED));

        exit.setAlpha(0.5f);
        layOut();
        assertEquals("Facebook's own alpha, faded", 0.5f * CleanExitSpot.FAINT, exit.getAlpha(), 0.001f);

        Settings.REEL_CLEAN_EXIT_FAINT.save(false);
        layOut();
        assertEquals("off", 0.5f, exit.getAlpha(), 0.001f);
        Settings.REEL_CLEAN_EXIT_FAINT.save(true);
        layOut();
        assertEquals(0.5f * CleanExitSpot.FAINT, exit.getAlpha(), 0.001f);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        layOut();
        assertEquals("paused", 0.5f, exit.getAlpha(), 0.001f);
        PauseForTests.resume();
        layOut();
        top.removeView(exit);
        layOut();
        assertEquals("taken off the page", 0.5f, exit.getAlpha(), 0.001f);
        assertNull(CleanExitSpot.fainted.get(exit));
    }

    /**
     * 588's Reels tab on the S22 (2026-11-23): the scrubber's mount hook never ran there, so nothing
     * handed the page over and the way out stayed solid. Burn-in guard's look runs with its own choice
     * off too, finds the reel playing and hands its page over.
     */
    @Test
    public void theReelPlayingOnTheReelsScreenFadesItsWayOut() {
        Settings.REEL_CLEAN_BUTTON.save(false);
        Settings.REEL_CLEAN_EXIT_FAINT.save(true);
        Settings.BURN_IN_GUARD.save(BurnInMode.OFF);
        controller.windowFocusChanged(true);
        BurnInGuard.resetForTests();
        BurnInGuard.resumed(activity);
        assertTrue("nothing plays yet", CleanExitSpot.watched.isEmpty());

        place(top, new TextureView(activity), 0, 0, 1080, 2041);
        layOut();
        assertFalse("the reel's list is watched", CleanExitSpot.watched.isEmpty());
        View exit = goClean(page, top);
        assertEquals(CleanExitSpot.FAINT, exit.getAlpha(), 0.001f);
        assertTrue(exit.isClickable());
    }

    @Test
    public void withFaintOffAPageWatchesNothing() {
        Settings.REEL_CLEAN_BUTTON.save(false);
        CleanExitSpot.sawPage(page);
        assertTrue(CleanExitSpot.watched.isEmpty());
        View exit = goClean(page, top);
        assertEquals(1f, exit.getAlpha(), 0f);
    }

    /** Hides [into]'s sidebar and puts the way out under its top host, as Clean mode does. */
    private View goClean(LithoView into, ComponentHost intoTop) {
        if (into == page) sidebar.setVisibility(View.GONE);
        ComponentHost exit = clickable(new ComponentHost(activity));
        place(intoTop, exit, 939, 1726, 1063, 1850);
        layOut();
        return exit;
    }

    private boolean reported(String counted) {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.REEL_DECLUTTER + ":") && line.contains(counted)) return true;
        }
        return false;
    }

    private static ComponentHost clickable(ComponentHost host) {
        host.setClickable(true);
        return host;
    }

    private static void place(ViewGroup parent, View child, int left, int top, int right, int bottom) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(right - left, bottom - top, Gravity.LEFT | Gravity.TOP);
        params.leftMargin = left;
        params.topMargin = top;
        parent.addView(child, params);
    }

    private void layOut() {
        activity.getWindow().getDecorView().requestLayout();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
    }
}
