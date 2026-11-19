/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewTreeObserver;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Back to Clean mode (#519): after the buttons come back over a reel, the reel goes back into
 * Clean mode when a sheet opened over it closes, or after the chosen time with no touch. The
 * overlay's update and the window in front's focus and touches reach it the way they do in
 * Facebook: through {@link ReelCleanMode#markLeftClean} and {@link BurnInGuard}'s listeners.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReelCleanReturnTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Facebook's store, held here the way Facebook holds it: ReelCleanMode keeps only a weak reference. */
    private final Object store = new Object();
    private ActivityController<Activity> controller;
    private Activity activity;

    @Before
    public void setUp() {
        PauseForTests.resume();
        BurnInGuard.resetForTests();
        ReelCleanReturn.resetForTests();
        HookStatus.clear();
        ReelCleanMode.keepStore(store);
        Settings.REEL_CLEAN_MODE.save(true);
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        controller.windowFocusChanged(true);
        BurnInGuard.resumed(activity);
        idle();
    }

    @After
    public void tearDown() {
        BurnInGuard.resetForTests();
        ReelCleanReturn.resetForTests();
        controller.close();
        PauseForTests.resume();
        Settings.REEL_CLEAN_MODE.resetToDefault();
        Settings.REEL_CLEAN_BUTTON.resetToDefault();
        Settings.REEL_CLEAN_RETURN.resetToDefault();
        ReelCleanMode.store = null;
        HookStatus.clear();
    }

    @Test
    public void offToStartAndASheetClosingChangesNothing() {
        assertSame(CleanReturn.OFF, Settings.REEL_CLEAN_RETURN.get());
        leaveClean();
        assertFalse("off, leaving Clean mode armed the return", ReelCleanReturn.armedForTests());
        sheetOpensAndCloses();
        assertReturned("off", 0);
    }

    @Test
    public void aSheetClosingPutsTheReelBackOnce() {
        Settings.REEL_CLEAN_RETURN.save(CleanReturn.WHEN_A_SHEET_CLOSES);
        leaveClean();
        assertTrue("leaving Clean mode didn't arm the return", ReelCleanReturn.armedForTests());
        after(60_000L);
        assertReturned("no sheet opened", 0);

        sheetOpensAndCloses();
        assertReturned("the sheet closed", 1);
        assertFalse("still armed after going back", ReelCleanReturn.armedForTests());
        sheetOpensAndCloses();
        assertReturned("a second sheet with the reel already clean", 1);
    }

    @Test
    public void everyReelHearingTheSameSwitchArmsOnce() {
        Settings.REEL_CLEAN_RETURN.save(CleanReturn.WHEN_A_SHEET_CLOSES);
        focus(false);
        // The three-dot menu's own Clean mode item: its sheet is open as the reel leaves Clean mode.
        leaveClean();
        leaveClean();
        focus(true);
        assertReturned("the menu that turned Clean mode off closing", 0);
        assertTrue("the menu closing ended the return", ReelCleanReturn.armedForTests());
        sheetOpensAndCloses();
        assertReturned("the comments closing after", 1);
    }

    @Test
    public void goingBackIntoCleanModeEndsTheReturn() {
        Settings.REEL_CLEAN_RETURN.save(CleanReturn.WHEN_A_SHEET_CLOSES);
        leaveClean();
        hear(true);
        assertFalse("a yes left the return armed", ReelCleanReturn.armedForTests());
        sheetOpensAndCloses();
        assertReturned("after the person put it back", 0);
    }

    @Test
    public void theTimeRunsFromTheLastTouch() {
        Settings.REEL_CLEAN_RETURN.save(CleanReturn.THREE_SECONDS);
        leaveClean();
        after(2_000L);
        send(MotionEvent.ACTION_DOWN);
        after(10_000L);
        assertReturned("a finger held down", 0);
        send(MotionEvent.ACTION_UP);
        after(2_900L);
        assertReturned("before the time since the lift", 0);
        after(200L);
        assertReturned("the time since the lift", 1);
        after(10_000L);
        assertReturned("once", 1);
    }

    @Test
    public void anOpenSheetHoldsTheTime() {
        Settings.REEL_CLEAN_RETURN.save(CleanReturn.FIVE_SECONDS);
        leaveClean();
        after(1_000L);
        focus(false);
        after(30_000L);
        assertReturned("with the comments open", 0);
        focus(true);
        after(4_900L);
        assertReturned("before the time since the sheet closed", 0);
        after(200L);
        assertReturned("the time since the sheet closed", 1);
    }

    @Test
    public void theButtonAloneArmsItToo() {
        Settings.REEL_CLEAN_MODE.save(false);
        Settings.REEL_CLEAN_BUTTON.save(true);
        Settings.REEL_CLEAN_RETURN.save(CleanReturn.TEN_SECONDS);
        Object[] state = leaveClean();
        assertSame("the button alone marked the reel", Boolean.FALSE, state[0]);
        after(10_100L);
        assertReturned("with the Clean mode button on", 1);
    }

    @Test
    public void noStoreNoSwitchPausedOrNotReadyAndNothingArms() {
        Settings.REEL_CLEAN_RETURN.save(CleanReturn.THREE_SECONDS);
        ReelCleanMode.store = null;
        leaveClean();
        assertFalse("armed without a store", ReelCleanReturn.armedForTests());

        ReelCleanMode.keepStore(store);
        Settings.REEL_CLEAN_MODE.save(false);
        leaveClean();
        assertFalse("armed with both Clean mode switches off", ReelCleanReturn.armedForTests());

        Settings.REEL_CLEAN_MODE.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        leaveClean();
        assertFalse("armed while paused", ReelCleanReturn.armedForTests());
        PauseForTests.resume();

        SettingsContextRule.withoutContext(this::leaveClean);
        assertFalse("armed before the settings were ready", ReelCleanReturn.armedForTests());
        after(10_000L);
        assertReturned("never armed", 0);
    }

    @Test
    public void anUpdateOffTheMainThreadArmsOnIt() throws InterruptedException {
        Settings.REEL_CLEAN_RETURN.save(CleanReturn.WHEN_A_SHEET_CLOSES);
        Thread layout = new Thread(() -> ReelCleanMode.markLeftClean(new Object[]{Boolean.FALSE}));
        layout.start();
        layout.join();
        idle();
        assertTrue("an update off the main thread didn't arm", ReelCleanReturn.armedForTests());
        sheetOpensAndCloses();
        assertReturned("off the main thread", 1);
    }

    @Test
    public void leavingTheScreenAndComingBackCountsAsASheet() {
        Settings.REEL_CLEAN_RETURN.save(CleanReturn.WHEN_A_SHEET_CLOSES);
        leaveClean();
        BurnInGuard.paused(activity);
        assertReturned("as the screen left", 0);
        BurnInGuard.resumed(activity);
        idle();
        assertReturned("coming back to the reel", 1);
    }

    private Object[] leaveClean() {
        return hear(false);
    }

    private Object[] hear(boolean clean) {
        Object[] state = {clean};
        assertSame(state, ReelCleanMode.markLeftClean(state));
        idle();
        return state;
    }

    private void sheetOpensAndCloses() {
        focus(false);
        after(500L);
        focus(true);
    }

    /** The window in front losing or getting focus, the way a sheet opening or closing over it does. */
    private void focus(boolean hasFocus) {
        ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
        ReflectionHelpers.callInstanceMethod(observer, "dispatchOnWindowFocusChange",
                ReflectionHelpers.ClassParameter.from(boolean.class, hasFocus));
        idle();
    }

    /** A finger event on the window, delivered to the decor view, which hands it to the window's callback. */
    private void send(int action) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, 100f, 100f, 0);
        try {
            activity.getWindow().getDecorView().dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
        idle();
    }

    private static void after(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void assertReturned(String when, int times) {
        String line = null;
        for (String each : HookStatus.report()) {
            if (each.startsWith(FamilyNames.REEL_DECLUTTER + ":")) line = each;
        }
        boolean counted = line != null && line.contains(ReelCleanReturn.RETURNED);
        if (times == 0) {
            assertFalse(when + ": went back to Clean mode: " + line, counted);
        } else {
            assertTrue(when + ": didn't go back to Clean mode: " + line, counted);
            assertTrue(when + ": " + line, line.contains(ReelCleanReturn.RETURNED + " " + times));
        }
    }
}
