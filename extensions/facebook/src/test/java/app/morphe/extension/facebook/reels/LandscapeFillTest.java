/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * A landscape video opened full screen with Fill the screen with reels on (#466). Facebook sizes
 * its player box to the video, 1922x1080 in the S22's 2316x1080 turned window, so the video is
 * scaled up until it reaches the sides. A video that would lose too much, a screen held upright, the
 * switch off or Hushfacebook paused keep Facebook's size, and a pinch in shows the video whole again.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w2316dp-h1080dp-land-mdpi")
public class LandscapeFillTest {
    private static final int SCREEN_WIDTH = 2316;
    private static final int SCREEN_HEIGHT = 1080;
    /** The S22's landscape player box for a 16:9 video. */
    private static final int PLAYER_WIDTH = 1922;

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Activity activity;
    private FrameLayout box;
    private SurfaceView video;

    @Before
    public void setUp() {
        HookStatus.clear();
        LandscapeFill.resetForTests();
        ReelFitPinch.resetForTests();
        Settings.FILL_REELS.save(true);
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout screen = new FrameLayout(activity);
        box = new FrameLayout(activity);
        video = new SurfaceView(activity);
        box.addView(video, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        screen.addView(box, new FrameLayout.LayoutParams(PLAYER_WIDTH, SCREEN_HEIGHT, Gravity.CENTER));
        activity.setContentView(screen);
        layOut(SCREEN_WIDTH, SCREEN_HEIGHT);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        LandscapeFill.resetForTests();
        ReelFitPinch.resetForTests();
        Settings.FILL_REELS.resetToDefault();
        HookStatus.clear();
    }

    @Test
    public void aLandscapeVideoFillsTheTurnedScreen() {
        LandscapeFill.laidOut(decor());
        float fills = (float) SCREEN_WIDTH / PLAYER_WIDTH;
        assertEquals(fills, video.getScaleX(), 0.001f);
        assertEquals(fills, video.getScaleY(), 0.001f);

        // The next layout keeps it.
        LandscapeFill.laidOut(decor());
        assertEquals(fills, video.getScaleX(), 0.001f);
    }

    @Test
    public void aVideoThatWouldLoseTooMuchKeepsItsBars() {
        // A 4:3 video would lose over a third of its height.
        assertEquals(1f, LandscapeFill.scale(1440, 1080, SCREEN_WIDTH, SCREEN_HEIGHT), 0.001f);
        assertEquals((float) SCREEN_WIDTH / PLAYER_WIDTH,
                LandscapeFill.scale(PLAYER_WIDTH, SCREEN_HEIGHT, SCREEN_WIDTH, SCREEN_HEIGHT), 0.001f);
        assertEquals("one that already reaches the edges", 1f,
                LandscapeFill.scale(SCREEN_WIDTH, SCREEN_HEIGHT, SCREEN_WIDTH, SCREEN_HEIGHT), 0.001f);
        assertEquals("a screen held upright", 1f, LandscapeFill.scale(1080, 608, 1080, 2340), 0.001f);
    }

    @Test
    public void offOrPausedTheVideoGoesBackToItsOwnSize() {
        LandscapeFill.laidOut(decor());
        assertTrue(video.getScaleX() > 1.1f);

        Settings.FILL_REELS.save(false);
        LandscapeFill.laidOut(decor());
        assertEquals(1f, video.getScaleX(), 0.001f);

        Settings.FILL_REELS.save(true);
        LandscapeFill.laidOut(decor());
        assertTrue(video.getScaleX() > 1.1f);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        LandscapeFill.laidOut(decor());
        assertEquals(1f, video.getScaleX(), 0.001f);
    }

    @Test
    public void aSmallPlayerIsntFilled() {
        box.setLayoutParams(new FrameLayout.LayoutParams(800, 450, Gravity.CENTER));
        layOut(SCREEN_WIDTH, SCREEN_HEIGHT);
        assertNull(LandscapeFill.player(decor()));
        LandscapeFill.laidOut(decor());
        assertEquals(1f, video.getScaleX(), 0.001f);
    }

    @Test
    public void thePlayerIsTheBiggestVideoOnTheScreen() {
        assertSame(video, LandscapeFill.player(decor()));
    }

    /** A pinch in shows the whole video and a pinch out fills the screen again. */
    @Test
    public void aPinchTakesTurnsWithTheFill() {
        LandscapeFill.laidOut(decor());
        float fills = (float) SCREEN_WIDTH / PLAYER_WIDTH;
        assertEquals(fills, video.getScaleX(), 0.001f);

        assertEquals(MotionEvent.ACTION_CANCEL, pinch(0.6f));
        video.animate().cancel();
        video.setScaleX(ReelFitPinch.target(video, true));
        video.setScaleY(ReelFitPinch.target(video, true));
        assertTrue(ReelFitPinch.keeping(video));
        LandscapeFill.laidOut(decor());
        assertEquals("the fill took the video back from the pinch", 1f, video.getScaleX(), 0.001f);

        assertEquals(fills, ReelFitPinch.target(video, false), 0.001f);
        assertEquals(MotionEvent.ACTION_CANCEL, pinch(1.6f));
    }

    private View decor() {
        return activity.getWindow().getDecorView();
    }

    private void layOut(int width, int height) {
        View decor = decor();
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, width, height);
    }

    /** Two fingers on the middle of the screen close or spread to {@code spread} of where they started. */
    private int pinch(float spread) {
        long down = SystemClock.uptimeMillis();
        float x = SCREEN_WIDTH / 2f;
        float y = SCREEN_HEIGHT / 2f;
        float half = 200;
        ReelFitPinch.touch(activity, event(MotionEvent.ACTION_DOWN, new float[][] {{x - half, y}}, down));
        ReelFitPinch.touch(activity, event(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                new float[][] {{x - half, y}, {x + half, y}}, down));
        float moved = half * spread;
        MotionEvent move = event(MotionEvent.ACTION_MOVE, new float[][] {{x - moved, y}, {x + moved, y}}, down);
        ReelFitPinch.touch(activity, move);
        ReelFitPinch.touch(activity, event(MotionEvent.ACTION_UP, new float[][] {{x, y}}, down));
        return move.getActionMasked();
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
}
