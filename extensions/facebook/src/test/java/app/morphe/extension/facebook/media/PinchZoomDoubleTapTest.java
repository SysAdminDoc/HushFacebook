/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Double tap to zoom (#220): two quick taps on a profile photo zoom it to twice its size about the
 * tap, and a second double tap puts it back. A profile's photos open on a tap, so the first tap is
 * held from Facebook until the double tap time runs out, then reaches it as it came. A drag, a second
 * finger or a hold goes on to Facebook at once, and so does a finger stopping a fling. The story viewer, the switch off and Hushfacebook
 * paused leave every tap to Facebook untouched.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PinchZoomDoubleTapTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** A Facebook screen: its dispatch asks the hook first, as FbFragmentActivity's does, and notes what gets past. */
    public static class Hooked extends PinchZoomTest.BackCounter {
        final List<Integer> received = new ArrayList<>();
        final List<Float> receivedX = new ArrayList<>();
        boolean focused = true;

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (PinchZoom.touch(this, event)) return true;
            received.add(event.getActionMasked());
            receivedX.add(event.getX());
            return true;
        }

        @Override
        public boolean hasWindowFocus() {
            return focused;
        }
    }

    private Hooked activity;
    private ImageView photo;
    private long clock;

    @Before
    public void setUp() {
        HookStatus.clear();
        PinchZoom.resetForTests();
        Settings.PINCH_TO_ZOOM.save(true);
        Settings.PINCH_TO_ZOOM_DOUBLE_TAP.save(true);
        activity = Robolectric.buildActivity(Hooked.class).setup().get();
        profileWithPhoto(activity);
        PinchZoom.snapshots = (screen, target, area, done) ->
                done.accept(Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888));
        clock = 1000;
    }

    @After
    public void restore() {
        PauseForTests.resume();
        PinchZoom.resetForTests();
        Settings.PINCH_TO_ZOOM.resetToDefault();
        Settings.PINCH_TO_ZOOM_KEEP.resetToDefault();
        Settings.PINCH_TO_ZOOM_DOUBLE_TAP.resetToDefault();
        HookStatus.clear();
    }

    private void profileWithPhoto(Activity screen) {
        FrameLayout root = new FrameLayout(screen);
        photo = new ImageView(screen);
        photo.setImageDrawable(new ColorDrawable(Color.RED));
        // A profile's photos open on a tap.
        photo.setOnClickListener(view -> { });
        root.addView(photo, new FrameLayout.LayoutParams(600, 600));
        screen.setContentView(root);
        layOut(screen);
        // As ProfileFragment.onViewCreated hands its root over.
        PinchZoom.profile(root);
    }

    private static void layOut(Activity screen) {
        View decor = screen.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 1080, 1920);
    }

    /** One event of a single finger at ([x], [y]), [at] milliseconds into the test's clock. */
    private static MotionEvent finger(int action, long downAt, long at, float x, float y) {
        return MotionEvent.obtain(downAt, at, action, x, y, 0);
    }

    private static void send(Activity screen, int action, long downAt, long at, float x, float y) {
        MotionEvent event = finger(action, downAt, at, x, y);
        screen.dispatchTouchEvent(event);
        event.recycle();
    }

    /** A tap that lifts 50 ms after it lands, through [screen]'s dispatch. */
    private static void tap(Activity screen, long landsAt, float x, float y) {
        send(screen, MotionEvent.ACTION_DOWN, landsAt, landsAt, x, y);
        send(screen, MotionEvent.ACTION_UP, landsAt, landsAt + 50, x, y);
    }

    private void tap(long landsAt, float x, float y) {
        tap(activity, landsAt, x, y);
    }

    /** Lets the double tap time run out. */
    private static void waitPastDoubleTap() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
    }

    private static List<Integer> actions(int... actions) {
        List<Integer> list = new ArrayList<>();
        for (int action : actions) list.add(action);
        return list;
    }

    @Test
    public void defaultsOff() {
        assertFalse(Settings.PINCH_TO_ZOOM_DOUBLE_TAP.defaultValue);
    }

    @Test
    public void aDoubleTapZoomsTwiceAboutTheTapAndFacebookGetsNeitherTap() {
        tap(clock, 250, 300);
        assertNull("one tap zoomed", PinchZoom.zoom());
        tap(clock + 150, 255, 305);
        PinchZoom.Zoom zoom = PinchZoom.zoom();
        assertNotNull("a double tap on a profile photo didn't zoom", zoom);
        assertEquals(PinchZoom.DOUBLE_TAP_SCALE, zoom.scale, 0f);
        assertEquals(PinchZoom.DOUBLE_TAP_SCALE, zoom.copy.getScaleX(), 0f);
        assertTrue("it springs back by itself", zoom.parked);
        assertTrue(zoom.shown());
        // About the second tap: the pivot is the point of the photo under the finger, in the photo's own coordinates.
        int[] origin = new int[2];
        photo.getLocationInWindow(origin);
        assertEquals(255f - origin[0], zoom.copy.getPivotX(), 1f);
        assertEquals(305f - origin[1], zoom.copy.getPivotY(), 1f);
        waitPastDoubleTap();
        assertTrue("Facebook got a tap of the double tap, which opens the photo", activity.received.isEmpty());
    }

    @Test
    public void aLoneTapReachesFacebookOnceTheDoubleTapTimeRunsOut() {
        tap(clock, 250, 300);
        assertTrue("the first tap wasn't held", activity.received.isEmpty());
        waitPastDoubleTap();
        assertEquals(actions(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), activity.received);
        assertNull(PinchZoom.zoom());
    }

    @Test
    public void aDragGoesToFacebookAtOnceFromWhereItLanded() {
        send(activity, MotionEvent.ACTION_DOWN, clock, clock, 250, 300);
        send(activity, MotionEvent.ACTION_MOVE, clock, clock + 20, 252, 300);
        assertTrue(activity.received.isEmpty());
        send(activity, MotionEvent.ACTION_MOVE, clock, clock + 40, 250, 500);
        assertEquals(actions(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_MOVE), activity.received);
        assertEquals(250f, activity.receivedX.get(0), 0f);
        send(activity, MotionEvent.ACTION_UP, clock, clock + 60, 250, 500);
        assertEquals(MotionEvent.ACTION_UP, (int) activity.received.get(3));
        waitPastDoubleTap();
        assertEquals(4, activity.received.size());
        assertNull(PinchZoom.zoom());
    }

    /** A flick down the profile from its photo, which Facebook gets once it drags. */
    private void fling() {
        long now = SystemClock.uptimeMillis();
        send(activity, MotionEvent.ACTION_DOWN, now, now, 250, 300);
        send(activity, MotionEvent.ACTION_MOVE, now, now + 20, 250, 700);
        send(activity, MotionEvent.ACTION_UP, now, now + 40, 250, 900);
        activity.received.clear();
    }

    /** The profile's list moves a frame, as a list marks its window each frame of a fling. */
    private void listScrolls() {
        ReflectionHelpers.callInstanceMethod(activity.getWindow().getDecorView().getViewTreeObserver(),
                "dispatchOnScrollChanged");
    }

    @Test
    public void aFingerStoppingAFlingGoesToFacebookAtOnce() {
        fling();
        listScrolls();
        long landsAt = SystemClock.uptimeMillis() + 16;
        send(activity, MotionEvent.ACTION_DOWN, landsAt, landsAt, 250, 300);
        assertEquals("a finger stopping a fling was held, so the profile slid on under it",
                actions(MotionEvent.ACTION_DOWN), activity.received);
        send(activity, MotionEvent.ACTION_UP, landsAt, landsAt + 50, 250, 300);
        assertEquals(actions(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), activity.received);
        waitPastDoubleTap();
        assertEquals("the stop reached Facebook twice", 2, activity.received.size());
        assertNull(PinchZoom.zoom());
    }

    @Test
    public void aDoubleTapStillZoomsOnceTheProfileStops() {
        fling();
        listScrolls();
        waitPastDoubleTap();
        long now = SystemClock.uptimeMillis();
        tap(now, 250, 300);
        assertTrue("a tap on a settled profile wasn't held", activity.received.isEmpty());
        tap(now + 150, 255, 305);
        assertNotNull("a double tap after the profile stopped didn't zoom", PinchZoom.zoom());
        waitPastDoubleTap();
        assertTrue(activity.received.isEmpty());
    }

    @Test
    public void aHoldGoesToFacebookOnceTheDoubleTapTimeRunsOut() {
        send(activity, MotionEvent.ACTION_DOWN, clock, clock, 250, 300);
        assertTrue(activity.received.isEmpty());
        waitPastDoubleTap();
        assertEquals("a held finger never reached Facebook, so its hold menu can't open",
                actions(MotionEvent.ACTION_DOWN), activity.received);
        send(activity, MotionEvent.ACTION_UP, clock, clock + 900, 250, 300);
        assertEquals(actions(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), activity.received);
        tap(clock + 1000, 250, 300);
        assertNull("a hold and a tap zoomed", PinchZoom.zoom());
    }

    @Test
    public void aSecondTapThatDragsGoesToFacebookFromItsOwnStart() {
        tap(clock, 250, 300);
        send(activity, MotionEvent.ACTION_DOWN, clock + 150, clock + 150, 255, 305);
        assertTrue(activity.received.isEmpty());
        send(activity, MotionEvent.ACTION_MOVE, clock + 150, clock + 170, 255, 600);
        assertEquals(actions(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE), activity.received);
        assertEquals(255f, activity.receivedX.get(0), 0f);
        send(activity, MotionEvent.ACTION_UP, clock + 150, clock + 190, 255, 600);
        waitPastDoubleTap();
        assertEquals("the first tap reached Facebook after a drag it led into",
                actions(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), activity.received);
        assertNull(PinchZoom.zoom());
    }

    @Test
    public void aTapElsewhereSendsTheHeldOneFirst() {
        tap(clock, 100, 100);
        tap(clock + 150, 560, 560);
        assertEquals(actions(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), activity.received);
        assertEquals(100f, activity.receivedX.get(0), 0f);
        waitPastDoubleTap();
        assertEquals(4, activity.received.size());
        assertEquals(560f, activity.receivedX.get(2), 0f);
        assertNull("taps far apart zoomed", PinchZoom.zoom());
    }

    @Test
    public void aSlowSecondTapIsNoDoubleTap() {
        tap(clock, 250, 300);
        tap(clock + 400, 250, 300);
        assertNull("taps 350 ms apart zoomed", PinchZoom.zoom());
        waitPastDoubleTap();
        assertEquals(4, activity.received.size());
    }

    @Test
    public void aHeldTapIsDroppedWhenAnotherWindowHasTheFocus() {
        tap(clock, 250, 300);
        activity.focused = false;
        waitPastDoubleTap();
        assertTrue("a tap went to a screen behind a dialog", activity.received.isEmpty());
    }

    @Test
    public void aSecondFingerHandsTheHeldOneOnAndPinches() {
        send(activity, MotionEvent.ACTION_DOWN, clock, clock, 250, 300);
        MotionEvent second = twoFingers(clock, clock + 30);
        activity.dispatchTouchEvent(second);
        assertEquals("Facebook didn't get the first finger before the pinch's cancel",
                actions(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), activity.received);
        assertNotNull(PinchZoom.zoom());
        assertEquals(1f, PinchZoom.zoom().scale, 0f);
    }

    @Test
    public void aSecondDoubleTapPutsItBackAndFacebookNeverSeesItsTaps() {
        tap(clock, 250, 300);
        tap(clock + 150, 250, 300);
        PinchZoom.Zoom zoom = PinchZoom.zoom();
        assertNotNull(zoom);
        // The first tap of the second pair does nothing, and none of it reaches Facebook.
        tap(clock + 1500, 400, 400);
        assertFalse("a lone tap let go of it", zoom.settling);
        tap(clock + 1650, 400, 400);
        assertTrue("the second double tap didn't put it back", zoom.settling);
        assertEquals(1f, zoom.scale, 0f);
        waitPastDoubleTap();
        assertTrue(activity.received.isEmpty());
    }

    @Test
    public void backPutsADoubleTapZoomBack() {
        tap(clock, 250, 300);
        tap(clock + 150, 250, 300);
        PinchZoom.Zoom zoom = PinchZoom.zoom();
        assertNotNull(zoom);
        layOut(activity);
        zoom.root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        zoom.root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
        assertTrue("Back didn't put it back", zoom.settling);
        assertEquals(0, activity.backs);
    }

    @Test
    public void aDragSteersTheZoomAndIsNotATap() {
        tap(clock, 250, 300);
        tap(clock + 150, 250, 300);
        PinchZoom.Zoom zoom = PinchZoom.zoom();
        assertNotNull(zoom);
        long at = clock + 2000;
        send(activity, MotionEvent.ACTION_DOWN, at, at, 300, 300);
        float before = zoom.copy.getTranslationX();
        send(activity, MotionEvent.ACTION_MOVE, at, at + 30, 200, 300);
        send(activity, MotionEvent.ACTION_UP, at, at + 60, 200, 300);
        assertEquals(before - 100f, zoom.copy.getTranslationX(), 1f);
        assertFalse(zoom.settling);
        assertTrue(activity.received.isEmpty());
    }

    @Test
    public void switchOffLeavesEveryTapToFacebookAtOnce() {
        Settings.PINCH_TO_ZOOM_DOUBLE_TAP.save(false);
        tap(clock, 250, 300);
        tap(clock + 150, 250, 300);
        assertEquals(4, activity.received.size());
        assertNull(PinchZoom.zoom());
    }

    @Test
    public void pinchToZoomOffLeavesEveryTapToFacebookAtOnce() {
        Settings.PINCH_TO_ZOOM.save(false);
        tap(clock, 250, 300);
        tap(clock + 150, 250, 300);
        assertEquals(4, activity.received.size());
        assertNull(PinchZoom.zoom());
    }

    @Test
    public void pausedLeavesEveryTapToFacebookAtOnce() {
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        tap(clock, 250, 300);
        tap(clock + 150, 250, 300);
        assertEquals(4, activity.received.size());
        assertNull(PinchZoom.zoom());
    }

    @Test
    public void somethingThatIsNotMediaKeepsItsTaps() {
        PinchZoom.places = (screen, root, x, y) -> null;
        tap(clock, 250, 300);
        assertEquals(2, activity.received.size());
        tap(clock + 150, 250, 300);
        assertNull(PinchZoom.zoom());
    }

    @Test
    public void theStoryViewerKeepsItsTapsSinceATapMovesTheStoryOn() {
        Activity story = Robolectric.buildActivity(PinchZoomTest.HookedViewer.class).setup().get();
        profileWithPhoto(story);
        // Even where the place is a photo, a story keeps its taps, at once.
        PinchZoom.places = (screen, decor, x, y) -> photo;
        for (long at : Arrays.asList(clock, clock + 150)) {
            MotionEvent down = finger(MotionEvent.ACTION_DOWN, at, at, 250, 300);
            MotionEvent up = finger(MotionEvent.ACTION_UP, at, at + 50, 250, 300);
            assertFalse(PinchZoom.touch(story, down));
            assertFalse(PinchZoom.touch(story, up));
        }
        assertNull("a story zoomed on a double tap", PinchZoom.zoom());
    }

    /** The second finger landing at 350,300 beside a first at 250,300. */
    private static MotionEvent twoFingers(long downAt, long at) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = 250 + 100 * i;
            coords[i].y = 300;
            coords[i].pressure = 1f;
            coords[i].size = 1f;
        }
        return MotionEvent.obtain(downAt, at,
                MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0);
    }
}
