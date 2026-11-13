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
import android.app.Dialog;
import android.view.View;
import android.view.Window;

import com.facebook.katana.activity.FbMainTabActivity;

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
 * Hide the status bar (#124 leftover): on, the main screen's bar is asked away and asked away again
 * whenever it shows; off or paused, a bar this hid comes back once, and a bar Facebook hid itself
 * is left alone; a failure stops that screen's checks. Facebook's strip behind the bar closes only
 * on the main screen with its bar gone, or brought back by a window over it while this has it asked
 * away. A real window's insets are run once to show they're reachable without failing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class HiddenStatusBarTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
        Settings.HIDE_STATUS_BAR.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_STATUS_BAR.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.BOTTOM_TAB_BAR + ":")) return line;
        }
        return null;
    }

    /** A status bar that shows until asked away, as Android's does. */
    static final class FakeBar implements HiddenStatusBar.Bar {
        Boolean showing = true;
        int hides;
        int shows;
        boolean forgotten;
        RuntimeException failure;

        @Override
        public Boolean showing() {
            if (failure != null) throw failure;
            return showing;
        }

        @Override
        public void hide() {
            hides++;
            showing = false;
        }

        @Override
        public void show() {
            shows++;
            showing = true;
        }

        @Override
        public void forget(HiddenStatusBar.Checks checks) {
            forgotten = true;
        }
    }

    @Test
    public void theSwitchStartsOffAndBelongsToTheTabBarFamily() {
        Settings.HIDE_STATUS_BAR.resetToDefault();
        assertFalse(Settings.HIDE_STATUS_BAR.get());
        assertFalse("a change shows when the settings close, without a restart", Settings.HIDE_STATUS_BAR.rebootApp);
        assertTrue(PatchFamily.BOTTOM_TAB_BAR.switches.contains(Settings.HIDE_STATUS_BAR));
        assertFalse(HiddenStatusBar.on());
    }

    @Test
    public void onTheBarGoesAndGoesAgainEachTimeItShows() {
        FakeBar bar = new FakeBar();
        HiddenStatusBar.Checks checks = new HiddenStatusBar.Checks(bar);

        checks.check();
        assertFalse(bar.showing);
        assertTrue(checks.hid);
        checks.onGlobalLayout();
        checks.onWindowFocusChanged(true);
        assertEquals("a bar already gone isn't asked again", 1, bar.hides);
        assertTrue(statusLine(), statusLine().contains(HiddenStatusBar.HIDDEN + " 1"));

        // Facebook leaves a full screen of its own and shows the bar: the next layout hides it again.
        bar.showing = true;
        checks.onGlobalLayout();
        assertFalse(bar.showing);
        assertEquals(2, bar.hides);
        assertTrue(statusLine(), statusLine().contains(HiddenStatusBar.HIDDEN + " 2"));

        bar.showing = true;
        checks.onWindowFocusChanged(false);
        assertTrue("losing focus to a sheet checks nothing", bar.showing);
        checks.onWindowFocusChanged(true);
        assertFalse(bar.showing);
        assertEquals(0, bar.shows);
    }

    @Test
    public void offOrPausedTheBarComesBackOnceAndOnAgainItGoesAgain() {
        FakeBar bar = new FakeBar();
        HiddenStatusBar.Checks checks = new HiddenStatusBar.Checks(bar);
        checks.check();

        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(why);
            checks.onWindowFocusChanged(true);
            assertTrue("paused by " + why, bar.showing);
            assertFalse(checks.hid);
            PauseForTests.resume();
            checks.onWindowFocusChanged(true);
            assertFalse("running again after " + why, bar.showing);
        }

        Settings.HIDE_STATUS_BAR.save(false);
        checks.onWindowFocusChanged(true);
        assertTrue(bar.showing);
        checks.onGlobalLayout();
        assertEquals("given back once", 3, bar.shows);
        assertTrue(statusLine(), statusLine().contains(HiddenStatusBar.GIVEN_BACK + " 3"));

        // Off, a bar Facebook hid for its own full screen stays hidden.
        bar.showing = false;
        checks.onGlobalLayout();
        assertFalse(bar.showing);
        assertEquals(3, bar.shows);
    }

    @Test
    public void aFailureStopsThatScreensChecks() {
        FakeBar bar = new FakeBar();
        HiddenStatusBar.Checks checks = new HiddenStatusBar.Checks(bar);
        bar.failure = new IllegalStateException("no insets");

        checks.check();
        assertTrue(bar.forgotten);
        assertTrue(statusLine(), statusLine().contains("threw"));

        bar.failure = null;
        checks.check();
        assertEquals("stopped for good", 0, bar.hides);
        assertTrue(bar.showing);
    }

    @Test
    public void aGoneWindowIsLeftAlone() {
        FakeBar bar = new FakeBar();
        bar.showing = null;
        HiddenStatusBar.Checks checks = new HiddenStatusBar.Checks(bar);
        checks.check();
        assertEquals(0, bar.hides);
        assertFalse(checks.hid);
    }

    @Test
    public void onlyFacebooksMainScreenGetsChecks() {
        Activity other = Robolectric.buildActivity(Activity.class).setup().get();
        new HiddenStatusBar.Screens().onActivityResumed(other);
        assertNull(HiddenStatusBar.WINDOWS.get(other.getWindow()));
        assertEquals("the main screen is the see-through bars' one", TranslucentBars.MAIN_SCREEN, HiddenStatusBar.MAIN_SCREEN);
    }

    @Test
    public void facebooksStripClosesOnlyOnTheMainScreenWithItsBarGone() {
        Activity main = Robolectric.buildActivity(FbMainTabActivity.class).get();
        Activity other = Robolectric.buildActivity(Activity.class).get();

        assertEquals(0, HiddenStatusBar.topInset(75, main, false, true));
        assertEquals("a bar Facebook hid itself", 0, HiddenStatusBar.topInset(75, main, false, false));
        assertTrue(statusLine(), statusLine().contains(HiddenStatusBar.STRIP_CLOSED + " 2"));
        assertEquals("a bar this never asked away keeps its strip", 75, HiddenStatusBar.topInset(75, main, true, false));
        assertEquals("no insets yet", 75, HiddenStatusBar.topInset(75, main, null, true));
        assertEquals("another screen", 75, HiddenStatusBar.topInset(75, other, false, true));
        assertEquals("no screen", 75, HiddenStatusBar.topInset(75, null, false, true));
        assertTrue(statusLine(), statusLine().contains(HiddenStatusBar.STRIP_CLOSED + " 2"));
    }

    /**
     * #456: the comment sheet over a reel is a window of its own with the bar back, which makes the
     * main screen's insets report the bar too. Opening the strip then re-padded the reel as the
     * sheet slid up and left a black band above the top bar as it closed.
     */
    @Test
    public void aBarAWindowOverTheMainScreenBringsBackKeepsItsStripClosed() {
        Activity main = Robolectric.buildActivity(FbMainTabActivity.class).setup().get();
        View content = main.findViewById(android.R.id.content);
        assertFalse("not asked away yet", HiddenStatusBar.askedAway(content, main));

        HiddenStatusBar.Checks checks = HiddenStatusBar.attach(main.getWindow());
        checks.check();
        assertTrue(checks.hid);
        assertTrue(HiddenStatusBar.askedAway(content, main));
        assertEquals(0, HiddenStatusBar.topInset(75, main, true, HiddenStatusBar.askedAway(content, main)));

        Dialog sheet = new Dialog(main);
        sheet.setContentView(new View(main));
        sheet.show();
        View sheetContent = sheet.findViewById(android.R.id.content);
        assertFalse("the sheet's own window pads for its own bar", HiddenStatusBar.askedAway(sheetContent, main));
        assertEquals(75, HiddenStatusBar.topInset(75, main, true, HiddenStatusBar.askedAway(sheetContent, main)));
        sheet.dismiss();

        Settings.HIDE_STATUS_BAR.save(false);
        checks.check();
        assertFalse("given back", HiddenStatusBar.askedAway(content, main));
        assertEquals(75, HiddenStatusBar.topInset(content, 75));
    }

    @Test
    public void offPausedOrWithoutAViewFacebooksInsetStays() {
        Activity main = Robolectric.buildActivity(FbMainTabActivity.class).setup().get();
        View content = main.findViewById(android.R.id.content);

        Settings.HIDE_STATUS_BAR.save(false);
        assertEquals(75, HiddenStatusBar.topInset(content, 75));
        Settings.HIDE_STATUS_BAR.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals(75, HiddenStatusBar.topInset(content, 75));
        PauseForTests.resume();
        assertEquals(75, HiddenStatusBar.topInset(null, 75));
        assertEquals(0, HiddenStatusBar.topInset(content, 0));
        // Robolectric's window never reports its bar gone, so the strip stays, and nothing fails.
        assertEquals(75, HiddenStatusBar.topInset(content, 75));
        String line = statusLine();
        assertTrue(String.valueOf(line), line == null || !line.contains("threw"));
    }

    @Test
    public void aRealWindowsInsetsAreAskedWithoutFailing() {
        Window window = Robolectric.buildActivity(Activity.class).setup().get().getWindow();
        assertNotNull(window.getInsetsController());
        HiddenStatusBar.Checks checks = HiddenStatusBar.attach(window);
        assertSame("one set of checks per window", checks, HiddenStatusBar.attach(window));
        checks.check();
        Settings.HIDE_STATUS_BAR.save(false);
        checks.check();
        assertFalse(checks.hid);
        String line = statusLine();
        assertTrue(String.valueOf(line), line == null || !line.contains("threw"));
    }
}
