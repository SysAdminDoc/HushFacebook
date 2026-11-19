/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.drawable.ShapeDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.SeekBar;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Tap the bar to seek (#125) on a real SeekBar, laid out 1000 px wide with a 40 px thumb and the
 * track starting 20 px in, so x maps to progress as (x - 20) / 960 of 1000. Each touch goes to the
 * hook first, as the patch has it, and only a touch the hook leaves goes on to the bar, which
 * stands in for Facebook's handler. The bar's own handler is what the hook plays a tap into.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class TapToSeekTest {
    private static final int Y = 30;

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private long downTime;

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TapToSeek.LIFT_DELAY_MS));
        PauseForTests.resume();
        Settings.TAP_TO_SEEK.resetToDefault();
        HookStatus.clear();
    }

    private static SeekBar bar(int max, int progress) {
        SeekBar bar = new SeekBar(RuntimeEnvironment.getApplication());
        ShapeDrawable thumb = new ShapeDrawable();
        thumb.setIntrinsicWidth(40);
        thumb.setIntrinsicHeight(40);
        bar.setThumb(thumb);
        bar.setThumbOffset(20);
        bar.setPadding(20, 0, 20, 0);
        bar.setMax(max);
        bar.setProgress(progress);
        bar.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(60, View.MeasureSpec.EXACTLY));
        bar.layout(0, 0, 1000, 60);
        return bar;
    }

    /** The touch through the hook, then on to the bar when the hook leaves it. Answers the hook. */
    private boolean touch(SeekBar bar, int action, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN) downTime += 1000;
        MotionEvent event = MotionEvent.obtain(downTime, downTime + 10, action, x, y, 0);
        try {
            boolean taken = TapToSeek.takesTouch(bar, event);
            if (!taken) bar.onTouchEvent(event);
            return taken;
        } finally {
            event.recycle();
        }
    }

    private static int progressAt(float x) {
        return Math.round((x - 20) / 960f * 1000);
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.PROGRESS_BAR + ":")) return line;
        }
        return null;
    }

    private static void assertNear(String message, int expected, int actual) {
        assertTrue(message + ": expected about " + expected + ", got " + actual, Math.abs(expected - actual) <= 25);
    }

    @Test
    public void aTapOffTheThumbJumpsThereAsADragFromTheThumb() {
        Settings.TAP_TO_SEEK.save(true);
        SeekBar bar = bar(1000, 100);
        assertTrue("the press off the thumb went on to Facebook", touch(bar, MotionEvent.ACTION_DOWN, 800, Y));
        assertEquals("the bar moved before the finger lifted", 100, bar.getProgress());
        assertTrue("the lift went on to Facebook", touch(bar, MotionEvent.ACTION_UP, 800, Y));
        assertNear("the tap didn't seek there", progressAt(800), bar.getProgress());

        assertTrue(touch(bar, MotionEvent.ACTION_DOWN, 200, Y));
        assertTrue(touch(bar, MotionEvent.ACTION_UP, 200, Y));
        assertNear("a tap back behind the thumb didn't seek there", progressAt(200), bar.getProgress());
        String line = statusLine();
        assertTrue(line, line != null && line.contains(TapToSeek.TAPPED + " 2"));
    }

    /**
     * 583's listener can post its start of a scrub as the drag starts, and a lift played before
     * that ran seeked first, then the start put the video back (#120). The lift waits for it.
     */
    @Test
    public void theLiftWaitsForWhatFacebookPostsAsTheDragStarts() {
        Settings.TAP_TO_SEEK.save(true);
        SeekBar bar = bar(1000, 100);
        List<String> steps = new ArrayList<>();
        int[] seekedTo = {-1};
        Handler main = new Handler(Looper.getMainLooper());
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                steps.add("drag");
                main.post(() -> steps.add("scrub started"));
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                steps.add("seek");
                seekedTo[0] = seekBar.getProgress();
            }
        });
        assertTrue(touch(bar, MotionEvent.ACTION_DOWN, 800, Y));
        assertTrue(touch(bar, MotionEvent.ACTION_UP, 800, Y));
        assertEquals("the drag lifted before Facebook's start ran", Arrays.asList("drag"), steps);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TapToSeek.LIFT_DELAY_MS));
        assertEquals(Arrays.asList("drag", "scrub started", "seek"), steps);
        assertNear("the lift didn't seek where the tap was", progressAt(800), seekedTo[0]);
    }

    /** A press that comes before the last tap's lift finishes that drag first, where it was going. */
    @Test
    public void aPressBeforeTheLiftFinishesTheDragFirst() {
        Settings.TAP_TO_SEEK.save(true);
        SeekBar bar = bar(1000, 100);
        List<Integer> seeks = new ArrayList<>();
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                seeks.add(seekBar.getProgress());
            }
        });
        assertTrue(touch(bar, MotionEvent.ACTION_DOWN, 800, Y));
        assertTrue(touch(bar, MotionEvent.ACTION_UP, 800, Y));
        assertTrue("the drag finished before its lift", seeks.isEmpty());
        assertTrue(touch(bar, MotionEvent.ACTION_DOWN, 200, Y));
        assertEquals("the next press didn't finish the last drag", 1, seeks.size());
        assertNear("the finished drag didn't seek where its tap was", progressAt(800), seeks.get(0));
        assertTrue(touch(bar, MotionEvent.ACTION_UP, 200, Y));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TapToSeek.LIFT_DELAY_MS));
        assertEquals("each drag lifted once", 2, seeks.size());
        assertNear("the second tap didn't seek there", progressAt(200), seeks.get(1));
    }

    @Test
    public void offAPressOffTheThumbIsFacebooks() {
        assertFalse("Tap the bar to seek doesn't start off", Settings.TAP_TO_SEEK.get());
        SeekBar bar = bar(1000, 100);
        assertFalse("off, the press was held", touch(bar, MotionEvent.ACTION_DOWN, 800, Y));
        assertFalse("off, the lift was held", touch(bar, MotionEvent.ACTION_UP, 800, Y));
        String line = statusLine();
        assertFalse("off, something was counted: " + line, line != null && line.contains("Counted"));
    }

    @Test
    public void aPressOnTheThumbIsFacebooks() {
        Settings.TAP_TO_SEEK.save(true);
        SeekBar bar = bar(1000, 500);
        float thumb = bar.getThumb().getBounds().exactCenterX();
        assertFalse("a press on the thumb was held", touch(bar, MotionEvent.ACTION_DOWN, thumb, Y));
        assertFalse("its drag was held", touch(bar, MotionEvent.ACTION_MOVE, thumb + 200, Y));
        assertFalse("its lift was held", touch(bar, MotionEvent.ACTION_UP, thumb + 200, Y));
    }

    /**
     * 588's saved video viewer (S22, 2026-11-19) runs the bar's 118 px touch area up over the
     * bottom of the reel's three dots, so a tap on the dots seeked instead of opening the menu.
     * Here the bar's 60 px sit at 300 on a 1000 x 400 page, a button 135 x 50 at 865, 280 runs
     * down over its top 30, and the reel's own full width press area runs under the bar's top.
     * The window is made wide enough to hold the page, as a phone's screen holds the viewer.
     */
    @Test
    @Config(qualifiers = "w1100dp-h800dp-mdpi")
    public void aPressOnAButtonUnderTheBarIsTheButtons() {
        Settings.TAP_TO_SEEK.save(true);
        SeekBar bar = bar(1000, 100);
        Activity screen = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout page = new FrameLayout(screen);
        View reel = new View(page.getContext());
        reel.setClickable(true);
        place(page, reel, 0, 0, 1000, 330);
        View dots = new View(page.getContext());
        dots.setClickable(true);
        place(page, dots, 865, 280, 135, 50);
        View time = new View(page.getContext());
        place(page, time, 20, 290, 200, 30);
        place(page, bar, 0, 300, 1000, 60);
        // Only views in a window have a place on the screen; a detached one reads 0, 0.
        screen.setContentView(page, new ViewGroup.LayoutParams(1000, 400));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue("the page never reached a window", bar.isAttachedToWindow());
        assertEquals("the bar wasn't laid out at 1000 x 60", 1000, bar.getWidth());
        assertTrue("the window is narrower than the page", bar.getRootView().getWidth() >= 1000);

        assertFalse("a press on the dots was held for the bar", touch(bar, MotionEvent.ACTION_DOWN, 930, 15));
        assertFalse(touch(bar, MotionEvent.ACTION_UP, 930, 15));
        // The stand-in for Facebook's handler drags from anywhere, so put the thumb back.
        bar.setProgress(100);

        assertTrue("a press on the line under the dots wasn't held", touch(bar, MotionEvent.ACTION_DOWN, 930, 45));
        assertTrue(touch(bar, MotionEvent.ACTION_UP, 930, 45));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TapToSeek.LIFT_DELAY_MS));
        assertNear("the line under the dots didn't seek there", progressAt(930), bar.getProgress());

        assertTrue("the reel's own press area kept the bar from seeking", touch(bar, MotionEvent.ACTION_DOWN, 500, 15));
        assertTrue(touch(bar, MotionEvent.ACTION_UP, 500, 15));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TapToSeek.LIFT_DELAY_MS));
        assertNear("a press over the reel's area didn't seek there", progressAt(500), bar.getProgress());

        assertTrue("a label that takes no press kept the bar from seeking", touch(bar, MotionEvent.ACTION_DOWN, 150, 5));
        assertTrue(touch(bar, MotionEvent.ACTION_UP, 150, 5));

        dots.setVisibility(View.GONE);
        assertTrue("dots Facebook hid kept the bar from seeking", touch(bar, MotionEvent.ACTION_DOWN, 930, 15));
        assertTrue(touch(bar, MotionEvent.ACTION_UP, 930, 15));
        // That tap took the thumb to 930, so put it back where a press there misses it.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TapToSeek.LIFT_DELAY_MS));
        bar.setProgress(100);
        dots.setVisibility(View.VISIBLE);
        dots.setAlpha(0f);
        assertTrue("dots faded out kept the bar from seeking", touch(bar, MotionEvent.ACTION_DOWN, 930, 15));
        assertTrue(touch(bar, MotionEvent.ACTION_UP, 930, 15));
    }

    private static void place(FrameLayout page, View child, int left, int top, int width, int height) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height, Gravity.LEFT | Gravity.TOP);
        params.leftMargin = left;
        params.topMargin = top;
        page.addView(child, params);
    }

    @Test
    public void aSidewaysDragOffTheThumbIsHandedToTheBarFromItsThumb() {
        Settings.TAP_TO_SEEK.save(true);
        SeekBar bar = bar(1000, 100);
        int slop = ViewConfiguration.get(bar.getContext()).getScaledTouchSlop();
        assertTrue(touch(bar, MotionEvent.ACTION_DOWN, 800, Y));
        assertTrue("a move inside the touch slop wasn't held", touch(bar, MotionEvent.ACTION_MOVE, 800 - slop / 2f, Y));
        assertFalse("the drag wasn't handed on", touch(bar, MotionEvent.ACTION_MOVE, 800 - 3 * slop, Y));
        assertFalse(touch(bar, MotionEvent.ACTION_MOVE, 600, Y));
        assertFalse("the drag's lift was held", touch(bar, MotionEvent.ACTION_UP, 600, Y));
        assertNear("the drag didn't end where the finger lifted", progressAt(600), bar.getProgress());
        String line = statusLine();
        assertTrue(line, line != null && line.contains(TapToSeek.DRAGGED + " 1"));
        assertFalse("the drag was counted as a tap: " + line, line.contains(TapToSeek.TAPPED));
    }

    @Test
    public void aSwipeUpOrDownFromTheBarSeeksNothing() {
        Settings.TAP_TO_SEEK.save(true);
        SeekBar bar = bar(1000, 100);
        assertTrue(touch(bar, MotionEvent.ACTION_DOWN, 800, Y));
        assertTrue("the swipe went to the bar", touch(bar, MotionEvent.ACTION_MOVE, 805, Y - 200));
        assertTrue(touch(bar, MotionEvent.ACTION_MOVE, 810, Y - 400));
        assertTrue("the swipe's lift went to the bar", touch(bar, MotionEvent.ACTION_UP, 810, Y - 400));
        assertEquals("the swipe moved the bar", 100, bar.getProgress());

        // The pager taking the swipe away ends it the same way.
        assertTrue(touch(bar, MotionEvent.ACTION_DOWN, 800, Y));
        assertTrue(touch(bar, MotionEvent.ACTION_CANCEL, 800, Y));
        assertEquals("a cancelled press moved the bar", 100, bar.getProgress());
        String line = statusLine();
        assertFalse("a swipe was counted: " + line, line != null && line.contains("Counted"));
    }

    @Test
    public void aBarThatCantSeekIsFacebooks() {
        Settings.TAP_TO_SEEK.save(true);
        assertFalse("a bar with no length yet held the press", touch(bar(0, 0), MotionEvent.ACTION_DOWN, 800, Y));
        SeekBar off = bar(1000, 100);
        off.setEnabled(false);
        assertFalse("a bar Facebook turned off held the press", touch(off, MotionEvent.ACTION_DOWN, 800, Y));
        SeekBar bare = bar(1000, 100);
        bare.setThumb(null);
        assertFalse("a bar with no thumb held the press", touch(bare, MotionEvent.ACTION_DOWN, 800, Y));
        assertFalse("no bar held the press", TapToSeek.takesTouch(null, null));
    }

    @Test
    public void pausedOrNotReadyAPressIsFacebooks() {
        Settings.TAP_TO_SEEK.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertFalse("a Hushfacebook paused by " + reason + " held the press",
                    touch(bar(1000, 100), MotionEvent.ACTION_DOWN, 800, Y));
            PauseForTests.resume();
        }
        SeekBar bar = bar(1000, 100);
        SettingsContextRule.withoutContext(() ->
                assertFalse("the press was held before the settings were ready", touch(bar, MotionEvent.ACTION_DOWN, 800, Y)));
        assertTrue("on again after the pause, the press wasn't held", touch(bar(1000, 100), MotionEvent.ACTION_DOWN, 800, Y));
    }
}
