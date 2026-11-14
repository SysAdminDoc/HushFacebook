/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.Duration;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * A pinch on a reel with Fill the screen with reels on (#466). Pinching in on a reel whose video
 * Facebook sized to hang over the sides scales it down until it all shows, and Facebook gets a
 * cancel and none of the rest of that gesture. Pinching out on one Facebook kept whole scales it up
 * to fill. A pinch that wouldn't change anything, one on a player smaller than the screen, the
 * switch off or Hushfacebook paused leave every event to Facebook. The reel keeps its pinched way
 * through a new layout and goes back to the switch's way once it's off the screen or gone.
 */
@RunWith(RobolectricTestRunner.class)
// The window's own size bounds what a view shows on the screen, so it's the test's screen.
@Config(sdk = 30, qualifiers = "w1080dp-h1920dp-mdpi")
public class ReelFitPinchTest {
    private static final int SCREEN_WIDTH = 1080;
    private static final int SCREEN_HEIGHT = 1920;
    /** How wide Facebook sizes a filled reel's video, hanging over both sides. */
    private static final int FILLED_WIDTH = 1300;

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Activity activity;
    private FrameLayout reel;
    private TextureView video;

    @Before
    public void setUp() {
        HookStatus.clear();
        ReelFitPinch.resetForTests();
        Settings.FILL_REELS.save(true);
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        reel = new FrameLayout(activity);
        video = new TextureView(activity);
        reel.addView(video, centered(FILLED_WIDTH, ViewGroup.LayoutParams.MATCH_PARENT));
        activity.setContentView(reel);
        layOut();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        ReelFitPinch.resetForTests();
        Settings.FILL_REELS.resetToDefault();
        HookStatus.clear();
    }

    @Test
    public void aPinchInShowsTheWholeReel() {
        float whole = (float) SCREEN_WIDTH / FILLED_WIDTH;
        assertEquals(whole, ReelFitPinch.target(video, true), 0.001f);
        long down = SystemClock.uptimeMillis();
        assertEquals("Facebook didn't get a cancel for the pinch", MotionEvent.ACTION_CANCEL, squeeze(0.6f, 960, down));
        assertTrue("the reel isn't kept whole", ReelFitPinch.keeping(video));
        assertTrue("the rest of the gesture reached Facebook",
                ReelFitPinch.touch(activity, event(MotionEvent.ACTION_MOVE, new float[][] {{500, 960}, {560, 960}}, down)));
        assertTrue("the lift reached Facebook",
                ReelFitPinch.touch(activity, event(MotionEvent.ACTION_UP, new float[][] {{500, 960}}, down)));
        settle();
        assertEquals(whole, video.getScaleX(), 0.001f);
        assertEquals(whole, video.getScaleY(), 0.001f);
        assertFalse("the next touch was kept from Facebook",
                ReelFitPinch.touch(activity, event(MotionEvent.ACTION_DOWN, new float[][] {{540, 960}})));
    }

    /** A reel Facebook kept whole, with bars above and below, fills on a pinch out. */
    @Test
    public void aPinchOutFillsAWholeReel() {
        video.setLayoutParams(centered(SCREEN_WIDTH, 1500));
        layOut();
        float fills = reel.getHeight() / 1500f;
        assertTrue("the reel already filled", fills > 1.01f);
        assertEquals(MotionEvent.ACTION_CANCEL, pinch(1.6f));
        settle();
        assertEquals(fills, video.getScaleX(), 0.001f);
        // And a pinch in puts it back as Facebook had it.
        assertEquals(MotionEvent.ACTION_CANCEL, pinch(0.6f));
        settle();
        assertEquals(1f, video.getScaleX(), 0.001f);
    }

    /** Pinching out on a reel that already fills changes nothing, so Facebook keeps the gesture. */
    @Test
    public void aPinchThatChangesNothingStaysFacebooks() {
        assertEquals(MotionEvent.ACTION_MOVE, pinch(1.6f));
        assertFalse(ReelFitPinch.keeping(video));
        assertEquals("a small pinch counted", MotionEvent.ACTION_MOVE, pinch(0.9f));
        assertEquals(1f, video.getScaleX(), 0.001f);
    }

    @Test
    public void aPlayerSmallerThanTheScreenIsntAReel() {
        reel.setLayoutParams(new FrameLayout.LayoutParams(SCREEN_WIDTH, 600));
        layOut();
        assertNull(ReelFitPinch.videoUnder(activity.getWindow().getDecorView(), 540, 300, SCREEN_WIDTH, SCREEN_HEIGHT));
        assertEquals(MotionEvent.ACTION_MOVE, pinch(0.6f, 300));
        assertEquals(1f, video.getScaleX(), 0.001f);
    }

    /** A SurfaceView is found the same way, and only under the fingers. */
    @Test
    public void aSurfaceUnderTheFingersIsFound() {
        reel.removeView(video);
        SurfaceView surface = new SurfaceView(activity);
        reel.addView(surface, centered(FILLED_WIDTH, ViewGroup.LayoutParams.MATCH_PARENT));
        layOut();
        View root = activity.getWindow().getDecorView();
        assertSame(surface, ReelFitPinch.videoUnder(root, 540, 960, SCREEN_WIDTH, SCREEN_HEIGHT));
        assertNull("a point off the reel found it", ReelFitPinch.videoUnder(root, 540, 5000, SCREEN_WIDTH, SCREEN_HEIGHT));
    }

    @Test
    public void facebookKeepsEveryTouchWithTheSwitchOffOrPaused() {
        Settings.FILL_REELS.save(false);
        assertEquals(MotionEvent.ACTION_MOVE, pinch(0.6f));
        Settings.FILL_REELS.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals(MotionEvent.ACTION_MOVE, pinch(0.6f));
        assertFalse(ReelFitPinch.keeping(video));
        assertEquals(1f, video.getScaleX(), 0.001f);
    }

    /** Facebook laying the video out again, as when the comments close, keeps the reel whole at the new size. */
    @Test
    public void aNewLayoutKeepsTheReelWhole() {
        pinch(0.6f);
        settle();
        video.setLayoutParams(centered(1400, ViewGroup.LayoutParams.MATCH_PARENT));
        layOut();
        assertEquals((float) SCREEN_WIDTH / 1400, video.getScaleX(), 0.001f);
    }

    /** Once the pinched reel is off the screen, it goes back to filling and is let go. */
    @Test
    public void aReelOffTheScreenFillsAgain() {
        pinch(0.6f);
        settle();
        reel.setTranslationY(-2 * SCREEN_HEIGHT);
        ReelFitPinch.touch(activity, event(MotionEvent.ACTION_DOWN, new float[][] {{540, 960}}));
        assertEquals(1f, video.getScaleX(), 0.001f);
        assertFalse(ReelFitPinch.keeping(video));
    }

    /** A reel whose video moved to another reel's player goes back to filling too. */
    @Test
    public void aVideoMovedOutOfItsReelFillsAgain() {
        pinch(0.6f);
        settle();
        reel.removeView(video);
        FrameLayout next = new FrameLayout(activity);
        next.addView(video, centered(FILLED_WIDTH, ViewGroup.LayoutParams.MATCH_PARENT));
        activity.setContentView(next);
        layOut();
        assertEquals(1f, video.getScaleX(), 0.001f);
        assertFalse(ReelFitPinch.keeping(video));
    }

    /** Two fingers come down around the middle of the screen and close or spread to {@code spread} of their start. */
    private int pinch(float spread) {
        return pinch(spread, 960);
    }

    private int pinch(float spread, float y) {
        long down = SystemClock.uptimeMillis();
        int seen = squeeze(spread, y, down);
        ReelFitPinch.touch(activity, event(MotionEvent.ACTION_UP, new float[][] {{540, y}}, down));
        return seen;
    }

    /** The pinch without the lift: what Facebook sees of the move that counts. */
    private int squeeze(float spread, float y, long down) {
        float half = 200;
        ReelFitPinch.touch(activity, event(MotionEvent.ACTION_DOWN, new float[][] {{540 - half, y}}, down));
        ReelFitPinch.touch(activity, event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1),
                new float[][] {{540 - half, y}, {540 + half, y}}, down));
        float moved = half * spread;
        MotionEvent move = event(MotionEvent.ACTION_MOVE, new float[][] {{540 - moved, y}, {540 + moved, y}}, down);
        boolean kept = ReelFitPinch.touch(activity, move);
        assertFalse("the counting move was kept from Facebook instead of cancelled", kept);
        return move.getActionMasked();
    }

    private static FrameLayout.LayoutParams centered(int width, int height) {
        return new FrameLayout.LayoutParams(width, height, Gravity.CENTER);
    }

    private void layOut() {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(SCREEN_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(SCREEN_HEIGHT, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
    }

    private static void settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ReelFitPinch.SETTLE_MS * 4));
    }

    private static MotionEvent event(int action, float[][] points) {
        return event(action, points, SystemClock.uptimeMillis());
    }

    private static MotionEvent event(int action, float[][] points, long downTime) {
        int count = points.length;
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = points[i][0];
            coords[i].y = points[i][1];
            coords[i].pressure = 1f;
            coords[i].size = 1f;
        }
        return MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, count, properties, coords,
                0, 0, 1f, 1f, 0, 0, 0, 0);
    }

    private static int pointer(int action, int index) {
        return action | (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
    }
}
