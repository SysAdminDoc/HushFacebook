/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.morphe.extension.facebook.reels.PostLongPress;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

import static org.robolectric.Shadows.shadowOf;

/**
 * A second finger over a photo in a profile starts a zoom on a copy of it, the pinch scales and
 * drags the copy, and it springs back once fewer than two fingers are down. Every event from that
 * second finger on reaches Facebook as a cancel; in the story viewer Facebook gets none of them
 * until the zoom ends, then a cancel. One finger, a pinch over something that isn't
 * media, an icon-sized picture, a photo outside both a profile and the story viewer (the news
 * feed's, which Facebook zooms itself), the switch off or Hushfacebook paused all leave every event
 * as Facebook handed it over. With Stay zoomed on, a zoom stays when the fingers lift, a touch on it
 * steers it unseen by Facebook, and a tap, a pinch back to 1x, Back or the window losing focus lets
 * it go. A cancelled touch keeps it, and Long press opens comments leaves a press on it alone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PinchZoomTest {
    /** What Long press opens comments counts when a press is held to the long-press time. */
    private static final String TIMED = "feed press held to the time";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Activity activity;
    private ImageView photo;
    private View plain;
    private ImageView icon;
    private final List<Bitmap> copies = new ArrayList<>();
    private boolean noCopy;

    @Before
    public void setUp() {
        HookStatus.clear();
        PinchZoom.resetForTests();
        Settings.PINCH_TO_ZOOM.save(true);
        openProfile();
        PinchZoom.snapshots = (screen, target, area, done) -> {
            if (noCopy) {
                done.accept(null);
                return;
            }
            Bitmap copy = Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888);
            copies.add(copy);
            done.accept(copy);
        };
    }

    /** A screen with a photo, a plain view and an icon, whose root is handed over as ProfileFragment does. */
    private void openProfile() {
        activity = Robolectric.buildActivity(BackCounter.class).setup().get();
        FrameLayout content = new FrameLayout(activity);
        photo = new ImageView(activity);
        photo.setImageDrawable(new ColorDrawable(Color.RED));
        content.addView(photo, new FrameLayout.LayoutParams(600, 600));
        plain = new View(activity);
        FrameLayout.LayoutParams below = new FrameLayout.LayoutParams(600, 400);
        below.topMargin = 700;
        content.addView(plain, below);
        icon = new ImageView(activity);
        icon.setImageDrawable(new ColorDrawable(Color.BLUE));
        FrameLayout.LayoutParams small = new FrameLayout.LayoutParams(40, 40);
        small.topMargin = 1200;
        content.addView(icon, small);
        activity.setContentView(content);
        layOut(activity);
        // As ProfileFragment.onViewCreated hands its root over.
        PinchZoom.profile(content);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        PinchZoom.resetForTests();
        Settings.PINCH_TO_ZOOM.resetToDefault();
        Settings.PINCH_TO_ZOOM_KEEP.resetToDefault();
        Settings.PINCH_TO_ZOOM_DIM.resetToDefault();
        Settings.PINCH_TO_ZOOM_LIVE.resetToDefault();
        Settings.LONG_PRESS_OPENS_COMMENTS.resetToDefault();
        HookStatus.clear();
    }

    /** A screen that counts the Back presses reaching it, each of which closes a screen in Facebook. */
    public static class BackCounter extends Activity {
        int backs;

        @Override
        public void onBackPressed() {
            backs++;
        }
    }

    /**
     * A story viewer whose touches go through the hook first, as FbFragmentActivity's do, noting the
     * actions of the ones Facebook gets.
     */
    public static class HookedViewer extends com.facebook.stories.viewer.activity.StoryViewerActivity {
        final List<Integer> handed = new ArrayList<>();
        int backs;

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (PinchZoom.touch(this, event)) return true;
            handed.add(event.getActionMasked());
            return true;
        }

        @Override
        public void onBackPressed() {
            backs++;
        }
    }

    private static void back(View root) {
        root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
    }

    /** As the window gaining or losing focus tells its views. */
    private static void windowFocus(View root, boolean focused) throws ReflectiveOperationException {
        Method dispatch = ViewTreeObserver.class.getDeclaredMethod("dispatchOnWindowFocusChange", boolean.class);
        dispatch.setAccessible(true);
        dispatch.invoke(root.getViewTreeObserver(), focused);
    }

    /** With Stay zoomed on, pinches the profile photo to twice its size and lifts both fingers. */
    private PinchZoom.Zoom keptZoom() {
        Settings.PINCH_TO_ZOOM_KEEP.save(true);
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}}));
        touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{250, 300}, {350, 300}}));
        PinchZoom.Zoom zoom = PinchZoom.zoom();
        assertNotNull("no zoom started", zoom);
        touch(event(MotionEvent.ACTION_MOVE, new float[][] {{200, 300}, {400, 300}}));
        touch(event(pointer(MotionEvent.ACTION_POINTER_UP, 1), new float[][] {{200, 300}, {400, 300}}));
        assertEquals(MotionEvent.ACTION_CANCEL, touch(event(MotionEvent.ACTION_UP, new float[][] {{200, 300}})));
        assertTrue("the zoom wasn't kept", zoom.parked);
        // A frame lays the layer out on a phone. Keys only reach a view that has been.
        layOut(activity);
        return zoom;
    }

    private static void layOut(Activity screen) {
        View decor = screen.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 1080, 1920);
    }

    /** Stands in for Litho's ComponentHost, which lists the text drawn in it. */
    public static final class LithoHost extends FrameLayout {
        final List<Object> drawn = new ArrayList<>();

        public LithoHost(android.content.Context context) {
            super(context);
        }

        public List<Object> getTextContent() {
            return drawn;
        }
    }

    private static MotionEvent event(int action, float[][] points) {
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
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, action, count, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0);
    }

    private static int pointer(int action, int index) {
        return action | (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
    }

    /** Hands [event] to the hook and gives back the action Facebook would see. A profile's events all reach it. */
    private int touch(MotionEvent event) {
        assertFalse("an event outside the story viewer was kept from Facebook", PinchZoom.touch(activity, event));
        return event.getActionMasked();
    }

    /** The story viewer showing one story card the size of the screen. */
    private static Activity storyViewer() {
        return storyViewer(com.facebook.stories.viewer.activity.StoryViewerActivity.class);
    }

    /** [kind] of story viewer showing one story card the size of the screen. */
    private static Activity storyViewer(Class<? extends Activity> kind) {
        Activity viewer = Robolectric.buildActivity(kind).setup().get();
        FrameLayout card = new FrameLayout(viewer);
        ImageView story = new ImageView(viewer);
        story.setImageDrawable(new ColorDrawable(Color.GREEN));
        card.addView(story, new FrameLayout.LayoutParams(1080, 1500));
        viewer.setContentView(card);
        layOut(viewer);
        return viewer;
    }

    private ViewGroup decor() {
        return (ViewGroup) activity.getWindow().getDecorView();
    }

    /** Where the copy draws its own point [u] across, in the window: the layer and the decor sit at its corner. */
    private static float drawnX(View copy, float u) {
        return copy.getLeft() + copy.getTranslationX() + copy.getPivotX() + copy.getScaleX() * (u - copy.getPivotX());
    }

    private static float drawnY(View copy, float u) {
        return copy.getTop() + copy.getTranslationY() + copy.getPivotY() + copy.getScaleY() * (u - copy.getPivotY());
    }

    /** The copy's own point drawn at [x] across in the window. */
    private static float underX(View copy, float x) {
        return copy.getPivotX() + (x - copy.getLeft() - copy.getTranslationX() - copy.getPivotX()) / copy.getScaleX();
    }

    private static float underY(View copy, float y) {
        return copy.getPivotY() + (y - copy.getTop() - copy.getTranslationY() - copy.getPivotY()) / copy.getScaleY();
    }

    @Test
    public void theSwitchStartsOff() {
        assertFalse(Settings.PINCH_TO_ZOOM.defaultValue);
    }

    @Test
    public void aPinchOverAPhotoZoomsACopyAndSpringsBack() {
        int children = decor().getChildCount();
        assertEquals(MotionEvent.ACTION_DOWN, touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}})));
        assertEquals("the second finger reached Facebook", MotionEvent.ACTION_CANCEL,
                touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{250, 300}, {350, 300}})));
        PinchZoom.Zoom zoom = PinchZoom.zoom();
        assertNotNull("no zoom started", zoom);
        assertTrue("the copy isn't over the window", zoom.shown());
        assertEquals(children + 1, decor().getChildCount());
        assertEquals("the copy wasn't the photo's size", 600, copies.get(0).getWidth());
        int[] at = new int[2];
        photo.getLocationInWindow(at);
        assertEquals("the copy isn't where the photo is", at[0], zoom.area.left);
        assertEquals("the copy isn't where the photo is", at[1], zoom.area.top);
        assertEquals("the copy isn't where the photo is", at[1], zoom.copy.getY(), 0.01f);
        assertEquals("the copy doesn't grow about the fingers", 300f, zoom.copy.getPivotX(), 0.01f);

        // Twice as far apart: twice the size, and the fingers' middle didn't move.
        assertEquals("a move reached Facebook", MotionEvent.ACTION_CANCEL,
                touch(event(MotionEvent.ACTION_MOVE, new float[][] {{200, 300}, {400, 300}})));
        assertEquals(2f, zoom.copy.getScaleX(), 0.01f);
        assertEquals(0f, zoom.copy.getTranslationX(), 0.01f);
        assertTrue("the window behind didn't darken", zoom.scrim.getAlpha() > 0f);

        // Dragged 50 to the right, and spread far past the most it zooms.
        touch(event(MotionEvent.ACTION_MOVE, new float[][] {{0, 300}, {700, 300}}));
        assertEquals(PinchZoom.MOST, zoom.copy.getScaleX(), 0.01f);
        assertEquals(50f, zoom.copy.getTranslationX(), 0.01f);

        // One finger lifts: the copy springs back and the layer goes.
        assertEquals(MotionEvent.ACTION_CANCEL,
                touch(event(pointer(MotionEvent.ACTION_POINTER_UP, 1), new float[][] {{0, 300}, {700, 300}})));
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
        assertFalse("the layer stayed after the spring back", zoom.shown());
        assertEquals(children, decor().getChildCount());
        assertNull(PinchZoom.zoom());
        // The last finger's own moves and lift stay cancels, so nothing under it fires.
        assertEquals(MotionEvent.ACTION_CANCEL, touch(event(MotionEvent.ACTION_MOVE, new float[][] {{10, 300}})));
        assertEquals(MotionEvent.ACTION_CANCEL, touch(event(MotionEvent.ACTION_UP, new float[][] {{10, 300}})));
        assertFalse(PinchZoom.active());

        // The next tap is Facebook's.
        assertEquals(MotionEvent.ACTION_DOWN, touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}})));
        assertEquals(MotionEvent.ACTION_UP, touch(event(MotionEvent.ACTION_UP, new float[][] {{250, 300}})));
    }

    @Test
    public void dimAroundAZoomStartsOn() {
        assertTrue(Settings.PINCH_TO_ZOOM_DIM.defaultValue);
    }

    @Test
    public void withDimAroundAZoomOffTheWindowBehindStaysAsItIs() {
        Settings.PINCH_TO_ZOOM_DIM.save(false);
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}}));
        touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{250, 300}, {350, 300}}));
        PinchZoom.Zoom zoom = PinchZoom.zoom();
        assertNotNull("no zoom started", zoom);
        assertTrue("the copy isn't over the window", zoom.shown());

        touch(event(MotionEvent.ACTION_MOVE, new float[][] {{0, 300}, {700, 300}}));
        assertEquals("the zoom didn't grow", PinchZoom.MOST, zoom.copy.getScaleX(), 0.01f);
        assertEquals("the window behind darkened", 0f, zoom.scrim.getAlpha(), 0.001f);

        // Back on, the next zoom darkens again.
        touch(event(pointer(MotionEvent.ACTION_POINTER_UP, 1), new float[][] {{0, 300}, {700, 300}}));
        touch(event(MotionEvent.ACTION_UP, new float[][] {{0, 300}}));
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
        assertNull(PinchZoom.zoom());
        Settings.PINCH_TO_ZOOM_DIM.save(true);
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}}));
        touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{250, 300}, {350, 300}}));
        touch(event(MotionEvent.ACTION_MOVE, new float[][] {{0, 300}, {700, 300}}));
        PinchZoom.Zoom next = PinchZoom.zoom();
        assertNotNull("no second zoom started", next);
        assertEquals("the window behind didn't darken", PinchZoom.SCRIM, next.scrim.getAlpha(), 0.001f);
    }

    @Test
    public void aThirdFingerCarriesOnFromTheScaleReached() {
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}}));
        touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{250, 300}, {350, 300}}));
        PinchZoom.Zoom zoom = PinchZoom.zoom();
        touch(event(MotionEvent.ACTION_MOVE, new float[][] {{200, 300}, {400, 300}}));
        assertEquals(2f, zoom.copy.getScaleX(), 0.01f);
        float cornerX = drawnX(zoom.copy, 0f);
        float cornerY = drawnY(zoom.copy, 0f);
        assertEquals(MotionEvent.ACTION_CANCEL, touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 2),
                new float[][] {{200, 300}, {400, 300}, {300, 500}})));
        assertEquals("a third finger landing changed the scale", 2f, zoom.copy.getScaleX(), 0.01f);
        assertEquals("a third finger landing moved the copy", cornerX, drawnX(zoom.copy, 0f), 0.01f);
        assertEquals("a third finger landing moved the copy", cornerY, drawnY(zoom.copy, 0f), 0.01f);
        // The three spread about the same middle: the part of the photo under it stays under it.
        float middleY = (300f + 300f + 500f) / 3f;
        float heldX = underX(zoom.copy, 300f);
        float heldY = underY(zoom.copy, middleY);
        touch(event(MotionEvent.ACTION_MOVE, new float[][] {{150, 300}, {450, 300}, {300, 500}}));
        assertTrue("spreading three fingers didn't zoom", zoom.copy.getScaleX() > 2.1f);
        assertEquals("the photo slid out from under the fingers", 300f, drawnX(zoom.copy, heldX), 0.01f);
        assertEquals("the photo slid out from under the fingers", middleY, drawnY(zoom.copy, heldY), 0.01f);
        // The third finger lifts and two stay: still zoomed, still following from the scale reached.
        float reached = zoom.copy.getScaleX();
        touch(event(pointer(MotionEvent.ACTION_POINTER_UP, 2), new float[][] {{150, 300}, {450, 300}, {300, 500}}));
        assertTrue(zoom.shown());
        assertFalse(zoom.settling);
        assertEquals("the third finger lifting changed the scale", reached, zoom.copy.getScaleX(), 0.01f);
        touch(event(MotionEvent.ACTION_MOVE, new float[][] {{75, 300}, {525, 300}}));
        assertEquals(reached * 1.5f, zoom.copy.getScaleX(), 0.01f);
        assertEquals("the photo slid out from under the fingers", 300f, drawnX(zoom.copy, underX(zoom.copy, 300f)), 0.01f);
    }

    /** Off the window's corner, the copy starts over the photo and springs back there, not to the corner. */
    @Test
    public void theCopySpringsBackToWhereThePhotoSits() {
        FrameLayout.LayoutParams moved = (FrameLayout.LayoutParams) photo.getLayoutParams();
        moved.leftMargin = 120;
        moved.topMargin = 40;
        photo.setLayoutParams(moved);
        layOut(activity);
        int[] at = new int[2];
        photo.getLocationInWindow(at);
        assertEquals(120, at[0]);
        float x = at[0] + 200f;
        float y = at[1] + 200f;
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{x, y}}));
        touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{x, y}, {x + 100, y}}));
        PinchZoom.Zoom zoom = PinchZoom.zoom();
        assertNotNull(zoom);
        assertEquals("the copy isn't over the photo", at[0], drawnX(zoom.copy, 0f), 0.01f);
        assertEquals("the copy isn't over the photo", at[1], drawnY(zoom.copy, 0f), 0.01f);
        touch(event(MotionEvent.ACTION_MOVE, new float[][] {{x - 100, y + 30}, {x + 200, y + 30}}));
        assertEquals(3f, zoom.copy.getScaleX(), 0.01f);
        touch(event(MotionEvent.ACTION_UP, new float[][] {{x - 100, y + 30}}));
        assertTrue(zoom.settling);
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
        assertFalse("the layer stayed after the spring back", zoom.shown());
        assertEquals("the copy sprang back somewhere else", at[0], drawnX(zoom.copy, 0f), 0.01f);
        assertEquals("the copy sprang back somewhere else", at[1], drawnY(zoom.copy, 0f), 0.01f);
        assertEquals(1f, zoom.copy.getScaleX(), 0.01f);
        assertNull("the copy was held after the layer went", zoom.copy.getDrawable());
    }

    /** A window closing mid zoom takes the zoom with it, and nothing keeps the copy. */
    @Test
    public void aWindowClosingMidZoomLetsGoOfIt() {
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}}));
        touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{250, 300}, {350, 300}}));
        PinchZoom.Zoom zoom = PinchZoom.zoom();
        assertNotNull(zoom);
        assertTrue(zoom.shown());
        assertTrue("the window isn't open", decor().isAttachedToWindow());
        activity.getWindowManager().removeViewImmediate(decor());
        assertNull("the zoom outlived its window", PinchZoom.zoom());
        assertFalse(PinchZoom.active());
        assertFalse(zoom.shown());
        assertNull(zoom.copy.getDrawable());
    }

    @Test
    public void aNewTouchTakesASpringingLayerAwayAtOnce() {
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}}));
        touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{250, 300}, {350, 300}}));
        PinchZoom.Zoom zoom = PinchZoom.zoom();
        touch(event(MotionEvent.ACTION_MOVE, new float[][] {{200, 300}, {400, 300}}));
        touch(event(MotionEvent.ACTION_UP, new float[][] {{200, 300}}));
        assertTrue("the spring back didn't start", zoom.settling);
        assertEquals(MotionEvent.ACTION_DOWN, touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}})));
        assertFalse("the layer could take the new touch", zoom.shown());
        assertNull(PinchZoom.zoom());
    }

    @Test
    public void withNoCopyNothingShowsButTheGestureStaysCancelled() {
        noCopy = true;
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}}));
        assertEquals(MotionEvent.ACTION_CANCEL,
                touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{250, 300}, {350, 300}})));
        PinchZoom.Zoom zoom = PinchZoom.zoom();
        assertNotNull(zoom);
        assertFalse(zoom.shown());
        assertEquals(MotionEvent.ACTION_CANCEL, touch(event(MotionEvent.ACTION_MOVE, new float[][] {{200, 300}, {400, 300}})));
        assertEquals(MotionEvent.ACTION_CANCEL, touch(event(MotionEvent.ACTION_UP, new float[][] {{200, 300}})));
        assertNull(PinchZoom.zoom());
    }

    @Test
    public void oneFingerIsLeftAlone() {
        assertEquals(MotionEvent.ACTION_DOWN, touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}})));
        assertEquals(MotionEvent.ACTION_MOVE, touch(event(MotionEvent.ACTION_MOVE, new float[][] {{260, 330}})));
        assertEquals(MotionEvent.ACTION_UP, touch(event(MotionEvent.ACTION_UP, new float[][] {{260, 330}})));
        assertNull(PinchZoom.zoom());
        assertTrue(copies.isEmpty());
    }

    @Test
    public void aPinchOverSomethingThatIsntMediaIsLeftAlone() {
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 800}}));
        assertEquals(MotionEvent.ACTION_POINTER_DOWN,
                touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{250, 800}, {350, 800}})));
        assertEquals(MotionEvent.ACTION_MOVE, touch(event(MotionEvent.ACTION_MOVE, new float[][] {{200, 800}, {400, 800}})));
        assertNull(PinchZoom.zoom());
    }

    @Test
    public void anIconSizedPictureIsLeftAlone() {
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{10, 1210}}));
        assertEquals(MotionEvent.ACTION_POINTER_DOWN,
                touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{10, 1210}, {30, 1210}})));
        assertNull(PinchZoom.zoom());
    }

    @Test
    public void thePhotoUnderTheFingersIsTheDeepestMediaThere() {
        assertSame(photo, PinchZoom.mediaUnder(activity, decor(), 300, 300));
        assertNull(PinchZoom.mediaUnder(activity, decor(), 300, 800));
        assertNull("an icon counted", PinchZoom.mediaUnder(activity, decor(), 20, 1220));
        photo.setVisibility(View.INVISIBLE);
        assertNull("a hidden photo counted", PinchZoom.mediaUnder(activity, decor(), 300, 300));
    }

    /** A clear view over the photo, like the story viewer's layer for taps, is looked past to the photo. */
    @Test
    public void aViewOverThePhotoThatDrawsNoMediaIsLookedPast() {
        FrameLayout content = (FrameLayout) photo.getParent();
        FrameLayout veil = new FrameLayout(activity);
        ImageView badge = new ImageView(activity);
        badge.setImageDrawable(new ColorDrawable(Color.WHITE));
        veil.addView(badge, new FrameLayout.LayoutParams(40, 40));
        content.addView(veil, new FrameLayout.LayoutParams(600, 600));
        layOut(activity);
        assertSame("the photo under a clear view was missed", photo, PinchZoom.mediaUnder(activity, decor(), 300, 300));
        // A picture of its own on top is what shows, so it's what zooms.
        ImageView cover = new ImageView(activity);
        cover.setImageDrawable(new ColorDrawable(Color.BLACK));
        veil.addView(cover, new FrameLayout.LayoutParams(600, 600));
        layOut(activity);
        assertSame(cover, PinchZoom.mediaUnder(activity, decor(), 300, 300));
    }

    /**
     * A screen Facebook opens over a profile in the same window, a post say, can leave the profile
     * shown under it. A pinch there is the screen's, never a profile photo's it covers.
     */
    @Test
    public void aScreenOverAProfileHidesItsPhotos() {
        ViewGroup frame = (ViewGroup) ((View) photo.getParent()).getParent();
        FrameLayout post = new FrameLayout(activity);
        post.setBackgroundColor(Color.WHITE);
        post.addView(new View(activity), new FrameLayout.LayoutParams(600, 200));
        frame.addView(post, new FrameLayout.LayoutParams(1080, 1920));
        layOut(activity);
        assertNull("a photo under another screen counted", PinchZoom.mediaUnder(activity, decor(), 300, 300));
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}}));
        assertEquals(MotionEvent.ACTION_POINTER_DOWN,
                touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{250, 300}, {350, 300}})));
        assertNull(PinchZoom.zoom());

        // A screen Litho draws, with no background of its own, hides it just the same.
        frame.removeView(post);
        LithoHost drawn = new LithoHost(activity);
        drawn.setWillNotDraw(false);
        frame.addView(drawn, new FrameLayout.LayoutParams(1080, 1920));
        layOut(activity);
        assertNull("a photo under a Litho screen counted", PinchZoom.mediaUnder(activity, decor(), 300, 300));
    }

    /**
     * Facebook keeps an empty layer over every screen, a frame whose only child is gone, so a
     * profile always sits under one. It draws nothing there, so the photo under it is what shows.
     */
    @Test
    public void anEmptyLayerOverAProfileIsLookedPast() {
        ViewGroup frame = (ViewGroup) ((View) photo.getParent()).getParent();
        FrameLayout layer = new FrameLayout(activity);
        View gone = new View(activity);
        gone.setVisibility(View.GONE);
        layer.addView(gone);
        frame.addView(layer, new FrameLayout.LayoutParams(1080, 1700));
        layOut(activity);
        assertSame("the photo under an empty layer was missed", photo, PinchZoom.mediaUnder(activity, decor(), 300, 300));
        layer.setBackgroundColor(Color.TRANSPARENT);
        View bare = new View(activity);
        layer.addView(bare, new FrameLayout.LayoutParams(1080, 1700));
        layOut(activity);
        assertSame("a clear background or a bare view counted", photo, PinchZoom.mediaUnder(activity, decor(), 300, 300));
        TextView hidden = new TextView(activity);
        hidden.setText("Hidden");
        hidden.setVisibility(View.INVISIBLE);
        layer.addView(hidden, new FrameLayout.LayoutParams(1080, 1700));
        View faded = new ImageView(activity);
        faded.setAlpha(0f);
        layer.addView(faded, new FrameLayout.LayoutParams(1080, 1700));
        layOut(activity);
        assertSame("a hidden or faded view in the layer counted", photo, PinchZoom.mediaUnder(activity, decor(), 300, 300));
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}}));
        assertEquals(MotionEvent.ACTION_CANCEL,
                touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{250, 300}, {350, 300}})));
        assertNotNull(PinchZoom.zoom());

        // Text shown in the layer over the photo hides it.
        hidden.setVisibility(View.VISIBLE);
        layOut(activity);
        assertNull("a photo under shown text counted", PinchZoom.mediaUnder(activity, decor(), 300, 300));
        // So does a foreground drawn over a clear frame.
        layer.removeView(hidden);
        layer.setForeground(new ColorDrawable(0x80000000));
        layOut(activity);
        assertNull("a photo under a dark foreground counted", PinchZoom.mediaUnder(activity, decor(), 300, 300));
    }

    /**
     * A cover photo's Litho band drawn over part of its ImageView yields to the picture under it,
     * which holds all of it. A host the picture doesn't hold, a grid cell over a smaller one say, stays.
     */
    @Test
    public void aBandDrawnOverAPictureYieldsToThePicture() {
        FrameLayout content = (FrameLayout) photo.getParent();
        LithoHost band = new LithoHost(activity);
        FrameLayout.LayoutParams across = new FrameLayout.LayoutParams(600, 200);
        across.topMargin = 200;
        content.addView(band, across);
        layOut(activity);
        assertSame("the band over the cover won", photo, PinchZoom.mediaUnder(activity, decor(), 300, 300));
        content.removeView(band);
        content.addView(band, new FrameLayout.LayoutParams(700, 200));
        layOut(activity);
        assertSame("a host wider than the picture under it lost", band, PinchZoom.mediaUnder(activity, decor(), 300, 100));

        // Only the media right under the band decides: a smaller picture between them keeps the band.
        content.removeView(band);
        ImageView between = new ImageView(activity);
        between.setImageDrawable(new ColorDrawable(Color.GREEN));
        FrameLayout.LayoutParams middle = new FrameLayout.LayoutParams(300, 300);
        middle.leftMargin = 150;
        middle.topMargin = 150;
        content.addView(between, middle);
        content.addView(band, across);
        layOut(activity);
        assertSame("the photo under a picture under the band won", band, PinchZoom.mediaUnder(activity, decor(), 300, 300));
        content.removeView(between);

        // A band Litho nests a level deeper still yields to the picture under it.
        content.removeView(band);
        FrameLayout wrap = new FrameLayout(activity);
        wrap.addView(band, across);
        content.addView(wrap, new FrameLayout.LayoutParams(1080, 1920));
        layOut(activity);
        assertSame("a nested band over the cover won", photo, PinchZoom.mediaUnder(activity, decor(), 300, 300));
    }

    /**
     * The news feed shares its activity with a profile and zooms its own photos, so a photo that
     * isn't inside a root ProfileFragment handed over is left to Facebook.
     */
    @Test
    public void aPhotoOutsideAProfileIsLeftToFacebook() {
        PinchZoom.resetForTests();
        assertNull("a photo outside a profile counted", PinchZoom.mediaUnder(activity, decor(), 300, 300));
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}}));
        assertEquals(MotionEvent.ACTION_POINTER_DOWN,
                touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{250, 300}, {350, 300}})));
        assertNull(PinchZoom.zoom());
        PinchZoom.profile(plain);
        assertNull("a profile beside the photo counted", PinchZoom.mediaUnder(activity, decor(), 300, 300));
        PinchZoom.profile((View) photo.getParent());
        assertSame(photo, PinchZoom.mediaUnder(activity, decor(), 300, 300));
    }

    @Test
    public void inTheStoryViewerAStoryZoomsWithNoProfile() {
        PinchZoom.resetForTests();
        Activity viewer = storyViewer();
        ViewGroup card = (ViewGroup) ((ViewGroup) viewer.findViewById(android.R.id.content)).getChildAt(0);
        assertSame(card.getChildAt(0), PinchZoom.mediaUnder(viewer, viewer.getWindow().getDecorView(), 540, 700));
        assertNull("an activity that isn't the story viewer counted", PinchZoom.mediaUnder(activity, decor(), 300, 300));
    }

    /** 584 opens the story viewer as one of its empty subclasses as well, and that one holds Facebook's finger too. */
    @Test
    public void aStoryViewerSubclassIsTheStoryViewer() {
        PinchZoom.resetForTests();
        Activity viewer = storyViewer(com.facebook.stories.viewer.activity.StoryViewerDefaultNavBarActivity.class);
        ViewGroup card = (ViewGroup) ((ViewGroup) viewer.findViewById(android.R.id.content)).getChildAt(0);
        assertSame(card.getChildAt(0), PinchZoom.mediaUnder(viewer, viewer.getWindow().getDecorView(), 540, 700));
        assertFalse(PinchZoom.touch(viewer, event(MotionEvent.ACTION_DOWN, new float[][] {{500, 700}})));
        assertTrue("the second finger reached Facebook", PinchZoom.touch(viewer,
                event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{500, 700}, {600, 700}})));
        assertNotNull(PinchZoom.zoom());
    }

    /**
     * In the story viewer Facebook gets nothing of a zoom, so to it the first finger is still held,
     * its own hold that keeps the story paused. The event that ends the zoom reaches it as a cancel,
     * which a story doesn't take as a tap, and the next tap is Facebook's again.
     */
    @Test
    public void inTheStoryViewerFacebookHoldsTheFirstFingerUntilTheZoomEnds() {
        Activity viewer = storyViewer();
        MotionEvent down = event(MotionEvent.ACTION_DOWN, new float[][] {{500, 700}});
        assertFalse("the first finger was kept from Facebook", PinchZoom.touch(viewer, down));
        assertEquals(MotionEvent.ACTION_DOWN, down.getActionMasked());
        MotionEvent second = event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{500, 700}, {600, 700}});
        assertTrue("the second finger reached Facebook", PinchZoom.touch(viewer, second));
        PinchZoom.Zoom zoom = PinchZoom.zoom();
        assertNotNull("no zoom started", zoom);
        assertTrue("the copy isn't over the window", zoom.shown());

        MotionEvent spread = event(MotionEvent.ACTION_MOVE, new float[][] {{450, 700}, {650, 700}});
        assertTrue("a move reached Facebook", PinchZoom.touch(viewer, spread));
        assertEquals(2f, zoom.copy.getScaleX(), 0.01f);
        // A third finger landing and lifting doesn't end the zoom, and Facebook sees neither.
        assertTrue("a third finger reached Facebook", PinchZoom.touch(viewer, event(
                pointer(MotionEvent.ACTION_POINTER_DOWN, 2), new float[][] {{450, 700}, {650, 700}, {550, 900}})));
        assertTrue("a third finger's lift reached Facebook", PinchZoom.touch(viewer, event(
                pointer(MotionEvent.ACTION_POINTER_UP, 2), new float[][] {{450, 700}, {650, 700}, {550, 900}})));
        assertTrue("the zoom ended with two fingers still down", zoom.shown());

        // Down to one finger: the zoom ends, and Facebook's held finger lets go as a cancel.
        MotionEvent lift = event(pointer(MotionEvent.ACTION_POINTER_UP, 1), new float[][] {{450, 700}, {650, 700}});
        assertFalse("the zoom's end was kept from Facebook", PinchZoom.touch(viewer, lift));
        assertEquals(MotionEvent.ACTION_CANCEL, lift.getActionMasked());
        // The last finger's own moves and lift stay cancels, so the story doesn't skip ahead.
        MotionEvent move = event(MotionEvent.ACTION_MOVE, new float[][] {{460, 700}});
        assertFalse(PinchZoom.touch(viewer, move));
        assertEquals(MotionEvent.ACTION_CANCEL, move.getActionMasked());
        MotionEvent up = event(MotionEvent.ACTION_UP, new float[][] {{460, 700}});
        assertFalse(PinchZoom.touch(viewer, up));
        assertEquals(MotionEvent.ACTION_CANCEL, up.getActionMasked());
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
        assertNull(PinchZoom.zoom());
        assertFalse(PinchZoom.active());

        // The next tap is Facebook's.
        MotionEvent tap = event(MotionEvent.ACTION_DOWN, new float[][] {{500, 700}});
        assertFalse(PinchZoom.touch(viewer, tap));
        assertEquals(MotionEvent.ACTION_DOWN, tap.getActionMasked());
        MotionEvent tapUp = event(MotionEvent.ACTION_UP, new float[][] {{500, 700}});
        assertFalse(PinchZoom.touch(viewer, tapUp));
        assertEquals(MotionEvent.ACTION_UP, tapUp.getActionMasked());
    }

    /**
     * A story zoom whose copy never came, or that the system calls off, lets go of Facebook's held
     * finger at once, as a cancel.
     */
    @Test
    public void inTheStoryViewerAZoomThatEndsEarlyLetsGoAsACancel() {
        Activity viewer = storyViewer();
        noCopy = true;
        PinchZoom.touch(viewer, event(MotionEvent.ACTION_DOWN, new float[][] {{500, 700}}));
        PinchZoom.touch(viewer, event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{500, 700}, {600, 700}}));
        MotionEvent move = event(MotionEvent.ACTION_MOVE, new float[][] {{450, 700}, {650, 700}});
        assertFalse("a move with no copy shown was kept from Facebook", PinchZoom.touch(viewer, move));
        assertEquals(MotionEvent.ACTION_CANCEL, move.getActionMasked());
        MotionEvent after = event(MotionEvent.ACTION_MOVE, new float[][] {{440, 700}, {660, 700}});
        assertFalse(PinchZoom.touch(viewer, after));
        assertEquals(MotionEvent.ACTION_CANCEL, after.getActionMasked());

        noCopy = false;
        PinchZoom.touch(viewer, event(MotionEvent.ACTION_DOWN, new float[][] {{500, 700}}));
        assertTrue(PinchZoom.touch(viewer, event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{500, 700}, {600, 700}})));
        MotionEvent called = event(MotionEvent.ACTION_CANCEL, new float[][] {{500, 700}, {600, 700}});
        assertFalse("the system's cancel was kept from Facebook", PinchZoom.touch(viewer, called));
        assertEquals(MotionEvent.ACTION_CANCEL, called.getActionMasked());
        assertFalse(PinchZoom.active());
    }

    /**
     * A profile's photo grid on 584 is Litho hosts with no child view, each drawing its picture as
     * a mounted drawable. A host that draws no text is a photo; one drawing text, or holding a view
     * of its own, isn't.
     */
    @Test
    public void aLithoHostDrawingNoTextIsAPhoto() {
        LithoHost cell = new LithoHost(activity);
        assertTrue("a grid cell isn't a photo", PinchZoom.isMedia(cell));
        cell.drawn.add("Edit profile");
        assertFalse("a host drawing text counted", PinchZoom.isMedia(cell));
        cell.drawn.clear();
        cell.addView(new View(activity));
        assertFalse("a host holding a view counted", PinchZoom.isMedia(cell));
        assertFalse("a plain empty frame counted", PinchZoom.isMedia(new FrameLayout(activity)));

        LithoHost grid = new LithoHost(activity);
        FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(355, 352);
        at.topMargin = 1300;
        ((ViewGroup) photo.getParent()).addView(grid, at);
        layOut(activity);
        int[] where = new int[2];
        grid.getLocationInWindow(where);
        assertSame(grid, PinchZoom.mediaUnder(activity, decor(), where[0] + 170, where[1] + 170));
    }

    @Test
    public void switchOffOrPausedLeavesThePinchToFacebook() {
        Settings.PINCH_TO_ZOOM.save(false);
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}}));
        assertEquals(MotionEvent.ACTION_POINTER_DOWN,
                touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{250, 300}, {350, 300}})));
        assertNull(PinchZoom.zoom());

        Settings.PINCH_TO_ZOOM.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}}));
        assertEquals(MotionEvent.ACTION_POINTER_DOWN,
                touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{250, 300}, {350, 300}})));
        assertNull(PinchZoom.zoom());
    }

    @Test
    public void stayZoomedStartsOff() {
        assertFalse(Settings.PINCH_TO_ZOOM_KEEP.defaultValue);
    }

    /**
     * With Stay zoomed on, one finger left drags the copy, and the zoom stays when it lifts. A touch
     * on the kept zoom then drags or pinches it, none of it reaching Facebook, and a tap lets it go.
     */
    @Test
    public void withStayZoomedTheZoomStaysUntilATap() {
        Settings.PINCH_TO_ZOOM_KEEP.save(true);
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}}));
        touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{250, 300}, {350, 300}}));
        PinchZoom.Zoom zoom = PinchZoom.zoom();
        assertNotNull(zoom);
        touch(event(MotionEvent.ACTION_MOVE, new float[][] {{200, 300}, {400, 300}}));
        assertEquals(2f, zoom.copy.getScaleX(), 0.01f);

        // One finger lifts: the zoom carries on, and the finger left drags it.
        assertEquals(MotionEvent.ACTION_CANCEL,
                touch(event(pointer(MotionEvent.ACTION_POINTER_UP, 1), new float[][] {{200, 300}, {400, 300}})));
        assertFalse("one finger lifting ended the zoom", zoom.settling);
        float cornerX = drawnX(zoom.copy, 0f);
        float cornerY = drawnY(zoom.copy, 0f);
        assertEquals(MotionEvent.ACTION_CANCEL, touch(event(MotionEvent.ACTION_MOVE, new float[][] {{260, 340}})));
        assertEquals("one finger zoomed", 2f, zoom.copy.getScaleX(), 0.01f);
        assertEquals("one finger didn't drag the copy", cornerX + 60f, drawnX(zoom.copy, 0f), 0.01f);
        assertEquals("one finger didn't drag the copy", cornerY + 40f, drawnY(zoom.copy, 0f), 0.01f);

        // The last finger lifts and the zoom stays, holding focus so Back reaches it.
        assertEquals(MotionEvent.ACTION_CANCEL, touch(event(MotionEvent.ACTION_UP, new float[][] {{260, 340}})));
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
        assertSame("the zoom went when the fingers lifted", zoom, PinchZoom.zoom());
        assertTrue(zoom.shown());
        assertTrue(zoom.parked);
        assertFalse(zoom.settling);
        assertFalse(PinchZoom.active());
        assertTrue("the kept zoom can't take Back", zoom.layer.isFocused());

        // A drag on it moves it, and Facebook gets none of the touch.
        cornerX = drawnX(zoom.copy, 0f);
        cornerY = drawnY(zoom.copy, 0f);
        assertTrue("a touch on the kept zoom reached Facebook",
                PinchZoom.touch(activity, event(MotionEvent.ACTION_DOWN, new float[][] {{500, 800}})));
        assertTrue(PinchZoom.touch(activity, event(MotionEvent.ACTION_MOVE, new float[][] {{450, 700}})));
        assertTrue(PinchZoom.touch(activity, event(MotionEvent.ACTION_UP, new float[][] {{450, 700}})));
        assertEquals("a drag didn't move the kept zoom", cornerX - 50f, drawnX(zoom.copy, 0f), 0.01f);
        assertEquals("a drag didn't move the kept zoom", cornerY - 100f, drawnY(zoom.copy, 0f), 0.01f);
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
        assertTrue("a drag let go of the kept zoom", zoom.parked);
        assertTrue(zoom.shown());

        // Two fingers pinch it further, from the scale it kept.
        assertTrue(PinchZoom.touch(activity, event(MotionEvent.ACTION_DOWN, new float[][] {{300, 300}})));
        assertTrue(PinchZoom.touch(activity, event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1),
                new float[][] {{300, 300}, {400, 300}})));
        assertTrue(PinchZoom.touch(activity, event(MotionEvent.ACTION_MOVE, new float[][] {{250, 300}, {450, 300}})));
        assertEquals(PinchZoom.MOST, zoom.copy.getScaleX(), 0.01f);
        assertTrue(PinchZoom.touch(activity, event(pointer(MotionEvent.ACTION_POINTER_UP, 1),
                new float[][] {{250, 300}, {450, 300}})));
        assertTrue(PinchZoom.touch(activity, event(MotionEvent.ACTION_UP, new float[][] {{250, 300}})));
        assertTrue("a pinch let go of the kept zoom", zoom.parked);

        // A tap lets it go, and Facebook doesn't get that tap either.
        assertTrue(PinchZoom.touch(activity, event(MotionEvent.ACTION_DOWN, new float[][] {{500, 800}})));
        assertTrue("the tap that let go reached Facebook",
                PinchZoom.touch(activity, event(MotionEvent.ACTION_UP, new float[][] {{500, 800}})));
        assertTrue("a tap didn't let go", zoom.settling);
        assertFalse(zoom.parked);
        assertFalse("the zoom kept focus after it let go", zoom.layer.isFocused());
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
        assertNull(PinchZoom.zoom());
        assertFalse(zoom.shown());

        // The next tap is Facebook's.
        assertEquals(MotionEvent.ACTION_DOWN, touch(event(MotionEvent.ACTION_DOWN, new float[][] {{500, 800}})));
        assertEquals(MotionEvent.ACTION_UP, touch(event(MotionEvent.ACTION_UP, new float[][] {{500, 800}})));
    }

    @Test
    public void aKeptZoomPinchedBackToItsSizeLetsGo() {
        PinchZoom.Zoom zoom = keptZoom();
        assertTrue(PinchZoom.touch(activity, event(MotionEvent.ACTION_DOWN, new float[][] {{200, 300}})));
        assertTrue(PinchZoom.touch(activity, event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1),
                new float[][] {{200, 300}, {400, 300}})));
        assertTrue(PinchZoom.touch(activity, event(MotionEvent.ACTION_MOVE, new float[][] {{250, 300}, {350, 300}})));
        assertEquals(1f, zoom.copy.getScaleX(), 0.01f);
        assertTrue(PinchZoom.touch(activity, event(pointer(MotionEvent.ACTION_POINTER_UP, 1),
                new float[][] {{250, 300}, {350, 300}})));
        assertTrue("the lift reached Facebook",
                PinchZoom.touch(activity, event(MotionEvent.ACTION_UP, new float[][] {{250, 300}})));
        assertTrue("a pinch back to 1x kept the zoom", zoom.settling);
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
        assertNull(PinchZoom.zoom());
    }

    @Test
    public void aZoomThatEndsAboutItsSizeIsntKept() {
        Settings.PINCH_TO_ZOOM_KEEP.save(true);
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}}));
        touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{250, 300}, {350, 300}}));
        PinchZoom.Zoom zoom = PinchZoom.zoom();
        touch(event(MotionEvent.ACTION_MOVE, new float[][] {{248, 300}, {352, 300}}));
        assertEquals(1.04f, zoom.copy.getScaleX(), 0.001f);
        touch(event(pointer(MotionEvent.ACTION_POINTER_UP, 1), new float[][] {{248, 300}, {352, 300}}));
        touch(event(MotionEvent.ACTION_UP, new float[][] {{248, 300}}));
        assertFalse("a zoom near 1x was kept", zoom.parked);
        assertTrue(zoom.settling);
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
        assertNull(PinchZoom.zoom());
    }

    /** Back lets go of a kept zoom and goes no further, and once it's gone Back is Facebook's again. */
    @Test
    public void backLetsGoOfAKeptZoom() {
        PinchZoom.Zoom zoom = keptZoom();
        BackCounter screen = (BackCounter) activity;
        back(decor());
        assertTrue("Back didn't let go of the kept zoom", zoom.settling);
        assertEquals("Back went on to the screen under the kept zoom", 0, screen.backs);
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
        assertNull(PinchZoom.zoom());
        back(decor());
        assertEquals("Back didn't reach the screen once the zoom went", 1, screen.backs);
    }

    /**
     * With gesture navigation the system cancels the edge swipe before it sends Back. The cancel ends
     * that touch and keeps the zoom, so the Back after it lets go of the zoom and closes nothing.
     */
    @Test
    public void aCancelledTouchLeavesTheZoomForTheBackAfterIt() {
        PinchZoom.Zoom zoom = keptZoom();
        BackCounter screen = (BackCounter) activity;
        assertTrue(PinchZoom.touch(activity, event(MotionEvent.ACTION_DOWN, new float[][] {{2, 900}})));
        assertTrue(PinchZoom.touch(activity, event(MotionEvent.ACTION_MOVE, new float[][] {{80, 900}})));
        assertTrue("the cancel reached Facebook",
                PinchZoom.touch(activity, event(MotionEvent.ACTION_CANCEL, new float[][] {{80, 900}})));
        assertTrue("a cancelled touch let go of the kept zoom", zoom.parked);
        assertTrue("the kept zoom lost focus to a cancel", zoom.layer.isFocused());
        back(decor());
        assertTrue("Back didn't let go of the kept zoom", zoom.settling);
        assertEquals("Back went on to the screen under the kept zoom", 0, screen.backs);
    }

    /**
     * 584 targets Android 16, which hands Back to the window's back callbacks, and no view sees it as
     * a key: on an Android 16 emulator Back closed the Page under a kept zoom. The kept zoom's
     * callback sits on top, so Back lets go of it and closes nothing, and once the zoom goes the
     * callback goes with it.
     */
    @Test
    @Config(sdk = 36)
    public void backThroughTheWindowsCallbacksLetsGoOfAKeptZoom() throws ReflectiveOperationException {
        backCallbacks(true);
        openProfile();
        PinchZoom.Zoom zoom = keptZoom();
        BackCounter screen = (BackCounter) activity;
        OnBackInvokedCallback top = topBackCallback();
        assertNotNull("the kept zoom registered no back callback", zoom.backCallback);
        assertSame("the kept zoom's back callback isn't the one Back goes to", zoom.backCallback, top);
        zoom.park();
        assertSame("parking the kept zoom again changed its back callback", top, zoom.backCallback);
        assertSame("parking the kept zoom again put another callback on top", top, topBackCallback());
        top.onBackInvoked();
        assertTrue("Back didn't let go of the kept zoom", zoom.settling);
        assertEquals("Back went on to the screen under the kept zoom", 0, screen.backs);
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
        assertNull(PinchZoom.zoom());
        assertNull(zoom.backCallback);
        assertNotSame("the zoom's back callback outlived it", top, topBackCallback());
    }

    /** Android 13 to 15 hand Facebook's Back to the focused view as a key, and the kept zoom takes it there. */
    @Test
    @Config(sdk = 34)
    public void backAsAKeyStillLetsGoWhereTheWindowTakesNoCallbacks() throws ReflectiveOperationException {
        backCallbacks(false);
        openProfile();
        PinchZoom.Zoom zoom = keptZoom();
        BackCounter screen = (BackCounter) activity;
        assertNotSame("a window with callbacks off took the kept zoom's back callback", zoom.backCallback, topBackCallback());
        back(decor());
        assertTrue("Back didn't let go of the kept zoom", zoom.settling);
        assertEquals("Back went on to the screen under the kept zoom", 0, screen.backs);
    }

    /** Whether this app's windows hand Back to back callbacks, as an app targeting Android 16 gets. */
    private static void backCallbacks(boolean on) throws ReflectiveOperationException {
        ApplicationInfo info = RuntimeEnvironment.getApplication().getApplicationInfo();
        Method set = ApplicationInfo.class.getDeclaredMethod("setEnableOnBackInvokedCallback", boolean.class);
        set.setAccessible(true);
        set.invoke(info, on);
    }

    /** The callback the window hands the next Back to. */
    private OnBackInvokedCallback topBackCallback() throws ReflectiveOperationException {
        OnBackInvokedDispatcher dispatcher = decor().findOnBackInvokedDispatcher();
        assertNotNull("the window has no back dispatcher", dispatcher);
        Method top = dispatcher.getClass().getMethod("getTopCallback");
        return (OnBackInvokedCallback) top.invoke(dispatcher);
    }

    @Test
    public void aKeptZoomCoversOnlyItsOwnWindow() {
        View root = decor();
        assertFalse(PinchZoom.covers(root));
        keptZoom();
        assertTrue("a kept zoom doesn't cover its window", PinchZoom.covers(root));
        assertFalse(PinchZoom.covers(null));
        assertFalse("a kept zoom covers another window", PinchZoom.covers(storyViewer().getWindow().getDecorView()));
        back(root);
        assertFalse("a zoom springing back still covers its window", PinchZoom.covers(root));
    }

    /**
     * Long press opens comments times a press in the same touch dispatch and may run first. A press
     * held on a kept zoom is the zoom's, so it isn't timed, and once the zoom goes the same press is.
     */
    @Test
    public void aPressHeldOnAKeptZoomOpensNothingUnderIt() {
        Settings.LONG_PRESS_OPENS_COMMENTS.save(true);
        keptZoom();
        long held = ViewConfiguration.getLongPressTimeout() + 50L;
        PostLongPress.touch(activity, event(MotionEvent.ACTION_DOWN, new float[][] {{300, 900}}));
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(held));
        assertFalse("a press on the kept zoom was timed", String.join("\n", HookStatus.report()).contains(TIMED));
        PostLongPress.touch(activity, event(MotionEvent.ACTION_UP, new float[][] {{300, 900}}));

        back(decor());
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
        assertNull(PinchZoom.zoom());
        PostLongPress.touch(activity, event(MotionEvent.ACTION_DOWN, new float[][] {{300, 900}}));
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(held));
        assertTrue("with the zoom gone the press wasn't timed", String.join("\n", HookStatus.report()).contains(TIMED));
        PostLongPress.touch(activity, event(MotionEvent.ACTION_UP, new float[][] {{300, 900}}));
    }

    @Test
    public void theWindowLosingFocusLetsGoOfAKeptZoom() throws ReflectiveOperationException {
        PinchZoom.Zoom zoom = keptZoom();
        windowFocus(decor(), true);
        assertTrue("the window gaining focus let go", zoom.parked);
        windowFocus(decor(), false);
        assertTrue("the window losing focus didn't let go", zoom.settling);
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
        assertNull(PinchZoom.zoom());
    }

    @Test
    public void aTouchOnAnotherScreenLetsGoOfAKeptZoom() {
        PinchZoom.Zoom zoom = keptZoom();
        Activity other = storyViewer();
        MotionEvent down = event(MotionEvent.ACTION_DOWN, new float[][] {{500, 700}});
        assertFalse("a touch on another screen was kept from Facebook", PinchZoom.touch(other, down));
        assertEquals(MotionEvent.ACTION_DOWN, down.getActionMasked());
        assertNull(PinchZoom.zoom());
        assertFalse(zoom.shown());
    }

    /**
     * A kept story zoom keeps Facebook's first finger held, so the story stays paused. A touch on it
     * steers it unseen, and the tap that lets it go reaches Facebook as a cancel, so the story plays
     * on from there without skipping ahead.
     */
    @Test
    public void inTheStoryViewerAKeptZoomHoldsTheStoryUntilATap() {
        Settings.PINCH_TO_ZOOM_KEEP.save(true);
        Activity viewer = storyViewer();
        assertFalse(PinchZoom.touch(viewer, event(MotionEvent.ACTION_DOWN, new float[][] {{500, 700}})));
        assertTrue(PinchZoom.touch(viewer, event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1),
                new float[][] {{500, 700}, {600, 700}})));
        PinchZoom.Zoom zoom = PinchZoom.zoom();
        assertNotNull(zoom);
        assertTrue(PinchZoom.touch(viewer, event(MotionEvent.ACTION_MOVE, new float[][] {{450, 700}, {650, 700}})));
        assertTrue("one finger lifting reached Facebook", PinchZoom.touch(viewer, event(
                pointer(MotionEvent.ACTION_POINTER_UP, 1), new float[][] {{450, 700}, {650, 700}})));
        assertTrue("the lift reached Facebook",
                PinchZoom.touch(viewer, event(MotionEvent.ACTION_UP, new float[][] {{450, 700}})));
        assertTrue(zoom.parked);

        assertTrue(PinchZoom.touch(viewer, event(MotionEvent.ACTION_DOWN, new float[][] {{300, 300}})));
        assertTrue(PinchZoom.touch(viewer, event(MotionEvent.ACTION_MOVE, new float[][] {{200, 200}})));
        assertTrue(PinchZoom.touch(viewer, event(MotionEvent.ACTION_UP, new float[][] {{200, 200}})));
        assertTrue("a drag let go of the kept story", zoom.parked);

        assertTrue(PinchZoom.touch(viewer, event(MotionEvent.ACTION_DOWN, new float[][] {{300, 300}})));
        MotionEvent up = event(MotionEvent.ACTION_UP, new float[][] {{300, 300}});
        assertFalse("the tap that let go was kept from Facebook", PinchZoom.touch(viewer, up));
        assertEquals("the story took the tap that let go", MotionEvent.ACTION_CANCEL, up.getActionMasked());
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
        assertNull(PinchZoom.zoom());

        MotionEvent tap = event(MotionEvent.ACTION_DOWN, new float[][] {{500, 700}});
        assertFalse(PinchZoom.touch(viewer, tap));
        assertEquals(MotionEvent.ACTION_DOWN, tap.getActionMasked());
    }

    /** Back on a kept story zoom lets Facebook's held finger go with a cancel of its own, and doesn't close the story. */
    @Test
    public void backOnAKeptStoryLetsFacebooksFingerGo() {
        Settings.PINCH_TO_ZOOM_KEEP.save(true);
        HookedViewer viewer = (HookedViewer) storyViewer(HookedViewer.class);
        View root = viewer.getWindow().getDecorView();
        root.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, new float[][] {{500, 700}}));
        root.dispatchTouchEvent(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{500, 700}, {600, 700}}));
        root.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, new float[][] {{450, 700}, {650, 700}}));
        root.dispatchTouchEvent(event(pointer(MotionEvent.ACTION_POINTER_UP, 1), new float[][] {{450, 700}, {650, 700}}));
        root.dispatchTouchEvent(event(MotionEvent.ACTION_UP, new float[][] {{450, 700}}));
        assertEquals("Facebook saw more than the first finger", Arrays.asList(MotionEvent.ACTION_DOWN), viewer.handed);
        PinchZoom.Zoom zoom = PinchZoom.zoom();
        assertNotNull(zoom);
        assertTrue(zoom.parked);
        layOut(viewer);

        back(root);
        assertTrue("Back didn't let go of the kept story", zoom.settling);
        assertEquals("Back closed the story under the kept zoom", 0, viewer.backs);
        assertEquals("Facebook's held finger never let go",
                Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), viewer.handed);
    }

    /** Stands in for a video Tap to play knows: whether it plays, and what it was told. */
    private static final class Video {
        boolean playing = true;
        final List<Boolean> told = new ArrayList<>();
    }

    private TextureView video;
    private final List<Bitmap> shownFrames = new ArrayList<>();

    /**
     * Keep videos playing while zoomed on, a video over the profile's photo, frames read from it
     * standing in for its own, and Tap to play knowing [known].
     */
    private void videoOnScreen(List<Object> known) {
        Settings.PINCH_TO_ZOOM_LIVE.save(true);
        video = new TextureView(activity);
        ((ViewGroup) photo.getParent()).addView(video, new FrameLayout.LayoutParams(600, 600));
        layOut(activity);
        PinchZoom.places = (screen, root, x, y) -> video;
        PinchZoom.frames = (zoom, source, next) -> {
            assertSame("a frame came from something other than the video", video, source);
            Bitmap frame = zoom.spare(source.getWidth(), source.getHeight());
            zoom.showFrame(frame, null);
            shownFrames.add(frame);
            next.run();
        };
        PinchZoom.players = new PinchZoom.Players() {
            public List<Object> known() { return known; }
            public View view(Object player) { return video; }
            public boolean playing(Object player) { return ((Video) player).playing; }
            public void command(Object player, boolean play) {
                ((Video) player).playing = play;
                ((Video) player).told.add(play);
            }
        };
    }

    private static void idle(long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(millis));
    }

    /** A finger held still on the kept zoom for the long-press time, then lifted. True when the lift kept it. */
    private boolean holdOnKept(PinchZoom.Zoom zoom) {
        assertTrue(PinchZoom.touch(activity, event(MotionEvent.ACTION_DOWN, new float[][] {{300, 300}})));
        idle(ViewConfiguration.getLongPressTimeout() + 10);
        assertTrue(PinchZoom.touch(activity, event(MotionEvent.ACTION_UP, new float[][] {{300, 300}})));
        return zoom.parked && !zoom.settling;
    }

    @Test
    public void keepVideosPlayingStartsOff() {
        assertFalse(Settings.PINCH_TO_ZOOM_LIVE.defaultValue);
    }

    @Test
    public void aZoomOnAVideoFollowsItsFramesInTwoBuffers() {
        videoOnScreen(new ArrayList<>());
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}}));
        touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{250, 300}, {350, 300}}));
        PinchZoom.Zoom zoom = PinchZoom.zoom();
        assertNotNull(zoom);
        assertSame(video, zoom.source);
        for (int i = 0; i < 3; i++) idle(PinchZoom.FRAME_MILLIS + 1);
        assertEquals("frames taken", 3, shownFrames.size());
        assertNotSame("a frame was written into the one showing", shownFrames.get(0), shownFrames.get(1));
        assertSame("more than two buffers", shownFrames.get(0), shownFrames.get(2));
        assertEquals("the copy isn't the video's size", 600, shownFrames.get(0).getWidth());
        assertSame("the copy doesn't show the newest frame", shownFrames.get(2),
                ((android.graphics.drawable.BitmapDrawable) zoom.copy.getDrawable()).getBitmap());

        // The fingers lift and the copy stops following the video.
        touch(event(pointer(MotionEvent.ACTION_POINTER_UP, 1), new float[][] {{250, 300}, {350, 300}}));
        int taken = shownFrames.size();
        idle(1000);
        assertEquals("frames went on after the zoom", taken, shownFrames.size());
        assertNull(PinchZoom.zoom());
    }

    @Test
    public void offAZoomOnAVideoStaysOnItsFirstFrame() {
        videoOnScreen(new ArrayList<>());
        Settings.PINCH_TO_ZOOM_LIVE.save(false);
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}}));
        touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{250, 300}, {350, 300}}));
        PinchZoom.Zoom zoom = PinchZoom.zoom();
        assertNotNull(zoom);
        assertNull(zoom.source);
        idle(500);
        assertEquals(0, shownFrames.size());
    }

    @Test
    public void onlyAVideoSurfaceOutsideTheStoryViewerIsFollowed() {
        Settings.PINCH_TO_ZOOM_LIVE.save(true);
        TextureView texture = new TextureView(activity);
        assertSame(texture, PinchZoom.liveSource(activity, texture));
        android.view.SurfaceView surface = new android.view.SurfaceView(activity);
        assertSame(surface, PinchZoom.liveSource(activity, surface));
        assertNull("a photo was followed", PinchZoom.liveSource(activity, photo));
        assertNull("a story was followed", PinchZoom.liveSource(storyViewer(), texture));
    }

    @Test
    public void aHoldOnAKeptVideoZoomPausesItAndTheNextPlaysIt() {
        Video playing = new Video();
        videoOnScreen(new ArrayList<>(Arrays.asList(playing)));
        PinchZoom.Zoom zoom = keptZoom();
        assertTrue("the hold's lift let go of the zoom", holdOnKept(zoom));
        assertEquals("the hold didn't pause the video", Arrays.asList(false), playing.told);
        assertTrue(holdOnKept(zoom));
        assertEquals("the second hold didn't play it", Arrays.asList(false, true), playing.told);

        // Paused by a hold, then the zoom goes: the video plays on.
        assertTrue(holdOnKept(zoom));
        assertTrue(PinchZoom.touch(activity, event(MotionEvent.ACTION_DOWN, new float[][] {{300, 300}})));
        assertTrue(PinchZoom.touch(activity, event(MotionEvent.ACTION_UP, new float[][] {{300, 300}})));
        assertTrue("a tap didn't let go", zoom.settling);
        assertEquals(Arrays.asList(false, true, false, true), playing.told);
        assertTrue(playing.playing);
    }

    @Test
    public void aVideoPausedBeforeTheZoomPlaysOnAHoldAndStaysPlaying() {
        Video paused = new Video();
        paused.playing = false;
        videoOnScreen(new ArrayList<>(Arrays.asList(paused)));
        PinchZoom.Zoom zoom = keptZoom();
        assertTrue(holdOnKept(zoom));
        assertEquals(Arrays.asList(true), paused.told);
        back(decor());
        assertTrue(zoom.settling);
        assertEquals("letting go paused it again", Arrays.asList(true), paused.told);
    }

    @Test
    public void aFingerThatMovesOrLiftsEarlyIsntAHold() {
        Video playing = new Video();
        videoOnScreen(new ArrayList<>(Arrays.asList(playing)));
        PinchZoom.Zoom zoom = keptZoom();
        assertTrue(PinchZoom.touch(activity, event(MotionEvent.ACTION_DOWN, new float[][] {{300, 300}})));
        assertTrue(PinchZoom.touch(activity, event(MotionEvent.ACTION_MOVE, new float[][] {{360, 380}})));
        idle(ViewConfiguration.getLongPressTimeout() + 10);
        assertTrue(PinchZoom.touch(activity, event(MotionEvent.ACTION_UP, new float[][] {{360, 380}})));
        assertTrue(zoom.parked);
        assertTrue("a drag paused the video", playing.told.isEmpty());
        assertTrue(PinchZoom.touch(activity, event(MotionEvent.ACTION_DOWN, new float[][] {{300, 300}})));
        idle(ViewConfiguration.getLongPressTimeout() / 2);
        assertTrue(PinchZoom.touch(activity, event(MotionEvent.ACTION_UP, new float[][] {{300, 300}})));
        idle(ViewConfiguration.getLongPressTimeout());
        assertTrue("a tap paused the video", playing.told.isEmpty());
        assertTrue("a tap kept the zoom", zoom.settling);
    }

    @Test
    public void withNoPlayerKnownAHoldStopsTheFrameAndTheNextGoesOn() {
        videoOnScreen(new ArrayList<>());
        PinchZoom.Zoom zoom = keptZoom();
        idle(PinchZoom.FRAME_MILLIS * 2);
        assertTrue("no frames before the hold", shownFrames.size() > 0);
        assertTrue(holdOnKept(zoom));
        assertTrue(zoom.frozen);
        int taken = shownFrames.size();
        idle(PinchZoom.FRAME_MILLIS * 5);
        assertEquals("the held frame moved on", taken, shownFrames.size());
        assertTrue(holdOnKept(zoom));
        assertFalse(zoom.frozen);
        idle(PinchZoom.FRAME_MILLIS * 3);
        assertTrue("frames didn't go on", shownFrames.size() > taken);
    }

    @Test
    public void aHoldOnAKeptPhotoDoesNothing() {
        Settings.PINCH_TO_ZOOM_LIVE.save(true);
        PinchZoom.Zoom zoom = keptZoom();
        assertNull(zoom.source);
        assertTrue(PinchZoom.touch(activity, event(MotionEvent.ACTION_DOWN, new float[][] {{300, 300}})));
        idle(ViewConfiguration.getLongPressTimeout() + 10);
        assertFalse(zoom.heldDown);
        assertTrue(PinchZoom.touch(activity, event(MotionEvent.ACTION_UP, new float[][] {{300, 300}})));
        assertTrue("a held tap on a photo zoom kept it", zoom.settling);
    }
}
