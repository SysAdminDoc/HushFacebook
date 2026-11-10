/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w1080dp-h2400dp-mdpi")
public class FeedVideoDoubleTapTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private ActivityController<Activity> window;
    private Activity activity;
    private FrameLayout root;
    private final Player player = new Player();
    private final List<Integer> seeks = new ArrayList<>();
    private int clicks;

    private static final class Media { boolean ordinary = true; boolean live; }
    private static final class Player {
        ViewGroup view;
        Media params = new Media();
        String origin = "newsfeed::feed_story";
        boolean playing = true;
        int position = 30000, length = 120000;
    }

    @Before public void setUp() {
        FeedVideoControls.reset();
        FeedVideoDoubleTap.reset();
        FeedVideoControls.inBuildForTests = true;
        FeedVideoDoubleTap.inBuildForTests = true;
        FeedVideoControls.access = new FeedVideoControls.Players() {
            public ViewGroup view(Object of) { return ((Player) of).view; }
            public Object params(Object of) { return ((Player) of).params; }
            public Object origin(Object of) { return ((Player) of).origin; }
            public String videoId(Object params) { return "video"; }
            public boolean eligible(Object params) { return ((Media) params).ordinary; }
            public boolean live(Object params) { return ((Media) params).live; }
            public boolean playing(Object of) { return ((Player) of).playing; }
            public void command(Object of, boolean play) { }
            public void cast(View from, String videoId) { }
            public void open(View from, String videoId, boolean wide) { }
            public int position(Object of) { return ((Player) of).position; }
            public int length(Object of, Object params) { return ((Player) of).length; }
            public void seek(Object of, int positionMs) {
                assertEquals(Looper.getMainLooper(), Looper.myLooper());
                assertTrue(of == player);
                seeks.add(positionMs);
            }
            public float speed(Object of) { return 1f; }
            public void speed(Object of, float speed) { }
        };
        Settings.TAP_TO_PLAY.save(false);
        Settings.INLINE_FEED_VIDEO_CONTROLS.save(false);
        Settings.FEED_VIDEO_DOUBLE_TAP_SKIP.save(true);
        window = Robolectric.buildActivity(Activity.class).setup();
        activity = window.get();
        root = new FrameLayout(activity);
        RecyclerView list = new RecyclerView(activity, true);
        place(root, list, 0, 100, 1080, 2100);
        FrameLayout row = new FrameLayout(activity);
        place(list, row, 0, 0, 1080, 1100);
        FrameLayout container = new FrameLayout(activity);
        place(row, container, 0, 300, 1080, 600);
        place(container, new SurfaceView(activity), 0, 0, 1080, 600);
        // Facebook opens the video on a tap anywhere on it.
        container.setOnClickListener(v -> clicks++);
        player.view = container;
        activity.setContentView(root);
        layout();
        window.windowFocusChanged(true);
        FeedVideoControls.attached(player.view, player);
        drain();
    }

    @After public void restore() {
        FeedVideoDoubleTap.reset();
        FeedVideoControls.reset();
        Settings.FEED_VIDEO_DOUBLE_TAP_SKIP.resetToDefault();
        Settings.INLINE_FEED_VIDEO_CONTROLS.resetToDefault();
        Settings.TAP_TO_PLAY.resetToDefault();
        HookStatus.clear();
        if (window != null) window.pause().stop().destroy();
    }

    private static void place(ViewGroup parent, View child, int left, int top, int width, int height) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height, Gravity.LEFT | Gravity.TOP);
        params.leftMargin = left;
        params.topMargin = top;
        parent.addView(child, params);
    }

    private void layout() {
        activity.getWindow().getDecorView().requestLayout();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
    }

    private static void drain() { shadowOf(Looper.getMainLooper()).idle(); }

    /** Past the double tap timeout, when a held tap goes on to Facebook. */
    private static void waitOut() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350)); }

    /** The screen point [across] of the way along the video and halfway down it. */
    private int[] point(float across) {
        int[] at = new int[2];
        player.view.getLocationOnScreen(at);
        return new int[]{at[0] + Math.round(player.view.getWidth() * across), at[1] + player.view.getHeight() / 2};
    }

    /** One touch as the patched dispatchTouchEvent sees it: the hook first, then Facebook. Answers the action Facebook got. */
    private int send(long down, long time, int action, int[] at) {
        MotionEvent event = MotionEvent.obtain(down, time, action, at[0], at[1], 0);
        FeedVideoDoubleTap.touch(activity, event);
        activity.dispatchTouchEvent(event);
        int got = event.getActionMasked();
        event.recycle();
        return got;
    }

    /** A quick tap at [across] of the way along the video. Answers the action Facebook got for the lift. */
    private int tap(float across) {
        long now = SystemClock.uptimeMillis();
        int[] at = point(across);
        send(now, now, MotionEvent.ACTION_DOWN, at);
        int lift = send(now, now + 50, MotionEvent.ACTION_UP, at);
        drain();
        return lift;
    }

    private static String report() { return String.join("\n", HookStatus.report()); }

    @Test public void aDoubleTapOnTheRightJumpsAheadAndFacebookNeverGetsATap() {
        assertEquals(MotionEvent.ACTION_CANCEL, tap(0.9f));
        assertEquals(MotionEvent.ACTION_CANCEL, tap(0.9f));
        assertEquals(List.of(40000), seeks);
        waitOut();
        drain();
        assertEquals("neither tap opened the video", 0, clicks);
        assertTrue(report(), report().contains("double tap skip"));
    }

    @Test public void aDoubleTapOnTheLeftGoesBack() {
        tap(0.1f);
        tap(0.1f);
        assertEquals(List.of(20000), seeks);
        waitOut();
        drain();
        assertEquals(0, clicks);
    }

    @Test public void eachQuickTapAfterTheSecondJumpsAgainFromWhereTheLastLanded() {
        tap(0.9f);
        tap(0.9f);
        tap(0.9f);
        assertEquals(List.of(40000, 50000), seeks);
    }

    @Test public void aLoneSideTapStillOpensTheVideoOnceTheWaitIsOver() {
        assertEquals(MotionEvent.ACTION_CANCEL, tap(0.9f));
        assertEquals("held while a second tap can come", 0, clicks);
        waitOut();
        drain();
        assertEquals(1, clicks);
        assertTrue(seeks.isEmpty());
        assertTrue(report(), report().contains("single tap handed on"));
    }

    @Test public void aHeldTapIsDroppedWhenAnotherScreenTookOverMeanwhile() {
        tap(0.9f);
        window.windowFocusChanged(false);
        waitOut();
        drain();
        assertEquals(0, clicks);
    }

    @Test public void theMiddleOfTheVideoOpensItAtOnce() {
        assertEquals(MotionEvent.ACTION_UP, tap(0.5f));
        assertEquals(1, clicks);
        assertTrue(seeks.isEmpty());
    }

    @Test public void aVideoThatHasntStartedOpensAtOnce() {
        player.playing = false;
        player.position = 0;
        assertEquals(MotionEvent.ACTION_UP, tap(0.9f));
        assertEquals(1, clicks);
    }

    @Test public void aVideoWithNoLengthToJumpInOpensAtOnce() {
        player.length = 0;
        assertEquals(MotionEvent.ACTION_UP, tap(0.9f));
        assertEquals(1, clicks);
        assertTrue(seeks.isEmpty());
    }

    @Test public void theSwitchOffLeavesEveryTapToFacebook() {
        Settings.FEED_VIDEO_DOUBLE_TAP_SKIP.save(false);
        assertEquals(MotionEvent.ACTION_UP, tap(0.9f));
        assertEquals(MotionEvent.ACTION_UP, tap(0.9f));
        assertEquals(2, clicks);
        assertTrue(seeks.isEmpty());
    }

    @Test public void aBuildWithoutTheTapToPlayPatchLeavesEveryTapToFacebook() {
        FeedVideoDoubleTap.inBuildForTests = false;
        assertEquals(MotionEvent.ACTION_UP, tap(0.9f));
        assertEquals(1, clicks);
    }

    @Test public void aLiveVideoThatCanBeRewoundJumpsToo() {
        player.params.live = true;
        tap(0.1f);
        tap(0.1f);
        assertEquals(List.of(20000), seeks);
        assertTrue(report(), report().contains("double tap skip, live"));
    }

    @Test public void anAdIsLeftAlone() {
        player.params.ordinary = false;
        assertEquals(MotionEvent.ACTION_UP, tap(0.9f));
        assertEquals(1, clicks);
    }

    @Test public void aSmallButtonOverASideTakesItsTapAtOnce() {
        int[] clicked = new int[1];
        View speaker = new View(activity);
        speaker.setOnClickListener(v -> clicked[0]++);
        place(player.view, speaker, 1000, 280, 40, 40);
        layout();
        assertEquals(MotionEvent.ACTION_UP, tap(1020f / 1080f));
        assertEquals(1, clicked[0]);
        assertEquals(0, clicks);
        assertTrue(seeks.isEmpty());
    }

    @Test public void aPressThatStopsAMovingFeedIsFacebooks() throws Exception {
        // The first press starts watching the window's scrolls.
        tap(0.5f);
        Method scrolled = ViewTreeObserver.class.getDeclaredMethod("dispatchOnScrollChanged");
        scrolled.setAccessible(true);
        scrolled.invoke(activity.getWindow().getDecorView().getViewTreeObserver());
        assertEquals(MotionEvent.ACTION_UP, tap(0.9f));
        assertEquals(2, clicks);
        assertTrue(seeks.isEmpty());
    }

    @Test public void aLongPressOrADragOnASideStaysFacebooks() {
        long now = SystemClock.uptimeMillis();
        int[] at = point(0.9f);
        send(now, now, MotionEvent.ACTION_DOWN, at);
        assertEquals("a long press", MotionEvent.ACTION_UP, send(now, now + 1000, MotionEvent.ACTION_UP, at));
        int[] away = point(0.6f);
        send(now + 2000, now + 2000, MotionEvent.ACTION_DOWN, at);
        send(now + 2000, now + 2040, MotionEvent.ACTION_MOVE, away);
        assertEquals("a drag", MotionEvent.ACTION_UP, send(now + 2000, now + 2080, MotionEvent.ACTION_UP, away));
        waitOut();
        drain();
        assertTrue(seeks.isEmpty());
    }

    @Test public void aTapLateAfterTheLastIsAFreshTapNotASecondOne() {
        tap(0.9f);
        waitOut();
        drain();
        assertEquals(1, clicks);
        assertEquals("held again, not jumped", MotionEvent.ACTION_CANCEL, tap(0.9f));
        assertTrue(seeks.isEmpty());
    }
}
