/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.facebook.download.ListingPhotoForTests.Source;
import app.morphe.extension.facebook.download.ListingPhotoForTests.Sources;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Long press to save Marketplace photos (#515): which of the sources React Native hands a listing's
 * photo is kept, and which presses on that photo save it. Touches go through the activity's window
 * to a plain image view inside the listing's pressable, as on a listing, under a screen that hears
 * every touch first and keeps hearing them the way React Native's root view does, handing each to
 * ListingPhoto as the patched touch dispatcher does. A press held still on a big photo saves it and
 * ends the press for the screen, so the lift doesn't also open the photo, even once React Native
 * has taken the press from the photo. One that moves, lifts early, gains a second finger or loses
 * the window saves nothing, and so does every press on a small image, one the root never heard,
 * and any with either switch off or with Hushfacebook paused. The photo never takes the finger.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ListingPhotoTest {
    private static final String FULL = ListingPhotoForTests.FULL;
    private static final String SMALL = "https://scontent-mia3-1.xx.fbcdn.net/v/t45.5328-4/1_n.jpg?stp=dst-jpg_s320x320_tt6&oh=00_B&oe=6A1B2C3D";
    private static final String MIDDLE = "https://scontent-mia3-1.xx.fbcdn.net/v/t45.5328-4/1_n.jpg?stp=dst-jpg_s960x960_tt6&oh=00_C&oe=6A1B2C3D";
    /** Where a finger lands from the photo's corner: inside the default 320 by 470 window. */
    private static final float X = 150;
    private static final float Y = 200;

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Saves started, counted the moment each begins. */
    private final AtomicInteger saves = new AtomicInteger();
    private ActivityController<Activity> window;
    private Screen screen;
    private Pressable pressable;
    private ImageView photo;
    private long downTime;

    /**
     * React Native's root view: it hears every touch before the views under it, and since it
     * ignores a view's request to stop intercepting, it keeps hearing them after a view under it
     * takes the finger. It hands each touch to ListingPhoto, as the patched touch dispatcher does
     * from the root's onInterceptTouchEvent and onTouchEvent, and takes any finger nothing took.
     */
    static final class Screen extends FrameLayout {
        final List<Integer> heard = new ArrayList<>();
        /** Off for a screen whose touches never reach the patched dispatcher. */
        boolean dispatching = true;

        Screen(Context context) {
            super(context);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            hear(event);
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            hear(event);
            return true;
        }

        @Override
        public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
            // React Native's roots keep hearing every touch.
        }

        private void hear(MotionEvent event) {
            heard.add(event.getActionMasked());
            if (dispatching) ListingPhoto.root(event);
        }
    }

    /**
     * The pressable around a listing's photo, a React Native view that takes any finger the photo
     * leaves it. Once React Native picks it to answer the press, it takes the press from anything
     * under it on the next touch but a lift, as React Native's responder does, so a photo holding
     * the finger would hear a cancel while the finger is still down.
     */
    static final class Pressable extends FrameLayout {
        final List<Integer> taken = new ArrayList<>();
        boolean responder;

        Pressable(Context context) {
            super(context);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            return responder && action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            taken.add(event.getActionMasked());
            return true;
        }
    }

    @Before
    public void setUp() throws Exception {
        ListingPhoto.reset();
        ShadowToast.reset();
        Settings.DOWNLOAD_PHOTOS.save(true);
        Settings.MARKETPLACE_PHOTO_SAVE.save(true);
        MediaDownload.detailsForTests = details -> saves.incrementAndGet();
        // Every Meta name answers a private address, so each save stops before it connects.
        MediaDownload.policyForTests = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") });
        window = Robolectric.buildActivity(Activity.class).setup();
        Activity activity = window.get();
        screen = new Screen(activity);
        pressable = new Pressable(activity);
        photo = new ImageView(activity);
        pressable.addView(photo, new FrameLayout.LayoutParams(1080, 1500));
        screen.addView(pressable, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        activity.setContentView(screen);
        window.windowFocusChanged(true);
        waitFor(100);
    }

    @After
    public void restore() throws Exception {
        waitFor(ViewConfiguration.getLongPressTimeout() + 50L);
        waitForSaves();
        MediaDownload.detailsForTests = null;
        MediaDownload.policyForTests = null;
        ListingPhoto.reset();
        PauseForTests.resume();
        Settings.DOWNLOAD_PHOTOS.resetToDefault();
        Settings.MARKETPLACE_PHOTO_SAVE.resetToDefault();
        HookStatus.clear();
        ShadowToast.reset();
    }

    private static void waitFor(long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    private static void holdLongEnough() {
        waitFor(ViewConfiguration.getLongPressTimeout() + 50L);
    }

    private static void waitForSaves() throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (MediaDownload.savesInFlight() > 0) {
            assertTrue("a save never finished", System.nanoTime() < deadline);
            Thread.sleep(10);
        }
    }

    /** Where the photo's top left corner sits in the window. */
    private int[] corner() {
        int[] corner = new int[2];
        photo.getLocationInWindow(corner);
        return corner;
    }

    /**
     * Sends [action] through the activity's window as a finger would, at ([x], [y]) from the
     * photo's top left corner.
     */
    private void touch(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) downTime = now;
        int[] corner = corner();
        MotionEvent event = MotionEvent.obtain(downTime, now, action, corner[0] + x, corner[1] + y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        send(event);
    }

    /** A second finger landing on the photo while the first stays down. */
    private void secondFinger() {
        int[] corner = corner();
        MotionEvent.PointerProperties[] fingers = { finger(0), finger(1) };
        MotionEvent.PointerCoords[] at = { point(corner[0] + X, corner[1] + Y), point(corner[0] + X + 60, corner[1] + Y + 80) };
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, fingers, at, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        send(event);
    }

    private static MotionEvent.PointerProperties finger(int id) {
        MotionEvent.PointerProperties finger = new MotionEvent.PointerProperties();
        finger.id = id;
        finger.toolType = MotionEvent.TOOL_TYPE_FINGER;
        return finger;
    }

    private static MotionEvent.PointerCoords point(float x, float y) {
        MotionEvent.PointerCoords point = new MotionEvent.PointerCoords();
        point.x = x;
        point.y = y;
        point.pressure = 1f;
        point.size = 1f;
        return point;
    }

    private void send(MotionEvent event) {
        try {
            window.get().getWindow().getDecorView().dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private void listing(Source... sources) {
        ListingPhoto.source(photo, new Sources(sources));
    }

    private int savedAfterSaves() throws InterruptedException {
        waitForSaves();
        return saves.get();
    }

    /** The photo at [width] by [height], laid out again. */
    private void resize(int width, int height) {
        photo.setLayoutParams(new FrameLayout.LayoutParams(width, height));
        waitFor(100);
    }

    // ---- The address kept -------------------------------------------------------------------------

    @Test
    public void theBiggestSourceIsKept() {
        listing(new Source(SMALL, 320, 240), new Source(FULL, 2048, 1536), new Source(MIDDLE, 960, 720));
        assertEquals(FULL, ListingPhoto.addressOf(photo));
    }

    @Test
    public void withNoSizesTheFirstIsKept() {
        listing(new Source(MIDDLE), new Source(FULL));
        assertEquals(MIDDLE, ListingPhoto.addressOf(photo));
    }

    @Test
    public void aSingleSourceWithNoSizeIsKept() {
        // Facebook reads a source's size only when there's more than one.
        listing(new Source(FULL));
        assertEquals(FULL, ListingPhoto.addressOf(photo));
    }

    @Test
    public void onlyAWebAddressIsKept() {
        listing(new Source("file:///storage/emulated/0/DCIM/Camera/listing.jpg", 4000, 3000), new Source(FULL, 2048, 1536));
        assertEquals("a photo on the phone is a seller's own, still being listed", FULL, ListingPhoto.addressOf(photo));
        listing(new Source("content://media/external/images/media/12", 4000, 3000), new Source(""), new Source(null),
                new Source("data:image/png;base64,iVBORw0KGgo="));
        assertNull(ListingPhoto.addressOf(photo));
    }

    @Test
    public void anythingButAnArrayOfSourcesKeepsNothing() {
        ListingPhoto.source(photo, null);
        assertNull(ListingPhoto.addressOf(photo));
        ListingPhoto.source(photo, "photo");
        assertNull(ListingPhoto.addressOf(photo));
        ListingPhoto.source(photo, new Sources());
        assertNull(ListingPhoto.addressOf(photo));
        ListingPhoto.source(null, new Sources(new Source(FULL, 2048, 1536)));
    }

    @Test
    public void aNewPhotoReplacesTheOld() {
        listing(new Source(FULL, 2048, 1536));
        listing(new Source(MIDDLE, 960, 720));
        assertEquals(MIDDLE, ListingPhoto.addressOf(photo));
        listing(new Source("file:///storage/emulated/0/listing.jpg"));
        assertNull("the last photo's address outlived it", ListingPhoto.addressOf(photo));
    }

    @Test
    public void aSourceWithNoUsableSizeCountsAsNone() {
        Source halfSized = new Source(FULL);
        halfSized.values.put("width", 2048.0);
        listing(halfSized, new Source(SMALL, 320, 240));
        assertEquals("a width with no height", SMALL, ListingPhoto.addressOf(photo));
        Source unreadable = new Source(FULL);
        unreadable.values.put("width", "2048");
        unreadable.values.put("height", "1536");
        listing(unreadable, new Source(SMALL, 320, 240));
        assertEquals("a size that isn't a number", SMALL, ListingPhoto.addressOf(photo));
    }

    // ---- The photo's own touches ------------------------------------------------------------------

    @Test
    public void aKeptPhotoListensForPressesAndGivesThemUpWithItsAddress() {
        assertNull(shadowOf(photo).getOnTouchListener());
        listing(new Source(FULL, 2048, 1536));
        assertNotNull("a plain image view has no touch handling of its own to hook", shadowOf(photo).getOnTouchListener());
        listing(new Source("file:///storage/emulated/0/listing.jpg"));
        assertNull("a photo with nothing to save still listened", shadowOf(photo).getOnTouchListener());

        listing(new Source(FULL, 2048, 1536));
        Settings.MARKETPLACE_PHOTO_SAVE.save(false);
        listing(new Source(FULL, 2048, 1536));
        assertNull("turned off, the next photo still listened", shadowOf(photo).getOnTouchListener());
    }

    // ---- The press --------------------------------------------------------------------------------

    @Test
    public void aPressHeldStillSavesThePhotoOnce() throws Exception {
        listing(new Source(SMALL, 320, 240), new Source(FULL, 2048, 1536));
        touch(MotionEvent.ACTION_DOWN, X, Y);
        holdLongEnough();
        assertEquals(1, savedAfterSaves());
        touch(MotionEvent.ACTION_UP, X, Y);
        holdLongEnough();
        assertEquals("the lift saved it again", 1, savedAfterSaves());
    }

    @Test
    public void aHeldPressEndsForTheScreenSoTheLiftOpensNothing() throws Exception {
        listing(new Source(FULL, 2048, 1536));
        touch(MotionEvent.ACTION_DOWN, X, Y);
        holdLongEnough();
        assertEquals(1, savedAfterSaves());
        touch(MotionEvent.ACTION_MOVE, X + 2, Y);
        touch(MotionEvent.ACTION_UP, X, Y);
        assertEquals("the screen heard the press start, then end in a cancel, never a lift",
                List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), screen.heard);
        assertEquals("the listing's pressable heard the press end in a cancel",
                List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), pressable.taken);
    }

    @Test
    public void aTapStillReachesTheListing() throws Exception {
        listing(new Source(FULL, 2048, 1536));
        touch(MotionEvent.ACTION_DOWN, X, Y);
        waitFor(100);
        touch(MotionEvent.ACTION_UP, X, Y);
        holdLongEnough();
        assertEquals(0, savedAfterSaves());
        assertEquals("the screen heard the whole tap", List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), screen.heard);
        assertEquals("the pressable took the whole tap", List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), pressable.taken);
    }

    @Test
    public void aKeptPhotoNeverTakesTheFingerFromTheListing() {
        listing(new Source(FULL, 2048, 1536));
        touch(MotionEvent.ACTION_DOWN, X, Y);
        assertEquals("the photo kept the finger from the listing's pressable", List.of(MotionEvent.ACTION_DOWN), pressable.taken);
    }

    // ---- When React Native takes the press --------------------------------------------------------

    @Test
    public void aHoldStillSavesOnceReactNativeTakesThePressFromThePhoto() throws Exception {
        listing(new Source(FULL, 2048, 1536));
        touch(MotionEvent.ACTION_DOWN, X, Y);
        pressable.responder = true;
        touch(MotionEvent.ACTION_MOVE, X + 1, Y);
        holdLongEnough();
        assertEquals("React Native taking the press stopped the hold", 1, savedAfterSaves());
        assertEquals("the press didn't end for the listing", MotionEvent.ACTION_CANCEL, (int) pressable.taken.get(pressable.taken.size() - 1));
    }

    @Test
    public void aTapOrASwipeReactNativeTookSavesNothing() throws Exception {
        listing(new Source(FULL, 2048, 1536));
        touch(MotionEvent.ACTION_DOWN, X, Y);
        pressable.responder = true;
        touch(MotionEvent.ACTION_MOVE, X + 1, Y);
        waitFor(100);
        touch(MotionEvent.ACTION_UP, X + 1, Y);
        holdLongEnough();
        assertEquals("a tap saved", 0, savedAfterSaves());
        assertEquals("the tap reached the screen whole",
                List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), screen.heard);

        pressable.responder = false;
        int slop = ViewConfiguration.get(photo.getContext()).getScaledTouchSlop();
        touch(MotionEvent.ACTION_DOWN, X, Y);
        pressable.responder = true;
        touch(MotionEvent.ACTION_MOVE, X + slop + 10, Y);
        holdLongEnough();
        assertEquals("a swipe saved", 0, savedAfterSaves());
    }

    @Test
    public void aPressTheRootNeverHandedOverSavesNothing() throws Exception {
        // Nothing would hear the rest of such a press, so a swipe would save.
        screen.dispatching = false;
        listing(new Source(FULL, 2048, 1536));
        touch(MotionEvent.ACTION_DOWN, X, Y);
        holdLongEnough();
        assertEquals(0, savedAfterSaves());
    }

    @Test
    public void aSmallWobbleStillSaves() throws Exception {
        listing(new Source(FULL, 2048, 1536));
        int slop = ViewConfiguration.get(photo.getContext()).getScaledTouchSlop();
        touch(MotionEvent.ACTION_DOWN, X, Y);
        touch(MotionEvent.ACTION_MOVE, X + slop / 2f, Y);
        holdLongEnough();
        assertEquals(1, savedAfterSaves());
    }

    @Test
    public void aPressThatMovesSavesNothing() throws Exception {
        listing(new Source(FULL, 2048, 1536));
        int slop = ViewConfiguration.get(photo.getContext()).getScaledTouchSlop();
        touch(MotionEvent.ACTION_DOWN, X, Y);
        touch(MotionEvent.ACTION_MOVE, X + slop + 10, Y);
        holdLongEnough();
        assertEquals("a swipe to the next photo saved this one", 0, savedAfterSaves());
    }

    @Test
    public void aSecondFingerOrACancelSavesNothing() throws Exception {
        listing(new Source(FULL, 2048, 1536));
        touch(MotionEvent.ACTION_DOWN, X, Y);
        secondFinger();
        holdLongEnough();
        assertEquals("a pinch saved the photo", 0, savedAfterSaves());

        touch(MotionEvent.ACTION_DOWN, X, Y);
        touch(MotionEvent.ACTION_CANCEL, X, Y);
        holdLongEnough();
        assertEquals(0, savedAfterSaves());
    }

    @Test
    public void aPressAfterTheWindowLostFocusSavesNothing() throws Exception {
        listing(new Source(FULL, 2048, 1536));
        touch(MotionEvent.ACTION_DOWN, X, Y);
        window.windowFocusChanged(false);
        holdLongEnough();
        assertEquals("a call or a dialog took the window", 0, savedAfterSaves());
    }

    @Test
    public void aPhotoWithNoAddressLeavesTheFingerToTheListing() throws Exception {
        touch(MotionEvent.ACTION_DOWN, X, Y);
        holdLongEnough();
        assertEquals(0, savedAfterSaves());
        assertNull(ShadowToast.getTextOfLatestToast());
        assertEquals(List.of(MotionEvent.ACTION_DOWN), pressable.taken);
    }

    @Test
    public void aSmallImageIsNeverSaved() throws Exception {
        // An icon, a seller's picture or a thumbnail beside the photos is a React Native image too.
        resize(100, 100);
        listing(new Source(FULL, 2048, 1536));
        touch(MotionEvent.ACTION_DOWN, 50, 50);
        holdLongEnough();
        assertEquals("an icon saved", 0, savedAfterSaves());
        assertEquals("the icon kept the finger from the listing", List.of(MotionEvent.ACTION_DOWN), pressable.taken);

        touch(MotionEvent.ACTION_UP, 50, 50);
        resize(300, 50);
        touch(MotionEvent.ACTION_DOWN, 150, 25);
        holdLongEnough();
        assertEquals("a strip across the screen saved", 0, savedAfterSaves());
    }

    @Test
    public void anAddressOffMetaSaysItCouldNotSave() throws Exception {
        listing(new Source("https://example.com/listing.jpg", 2048, 1536));
        touch(MotionEvent.ACTION_DOWN, X, Y);
        holdLongEnough();
        assertEquals(0, savedAfterSaves());
        assertEquals("Couldn't save this photo. Try again in a moment.", ShadowToast.getTextOfLatestToast());
    }

    // ---- Off and paused ---------------------------------------------------------------------------

    @Test
    public void offTheListingKeepsNoAddressAndAPressSavesNothing() throws Exception {
        Settings.MARKETPLACE_PHOTO_SAVE.save(false);
        listing(new Source(FULL, 2048, 1536));
        assertNull(ListingPhoto.addressOf(photo));
        touch(MotionEvent.ACTION_DOWN, X, Y);
        holdLongEnough();
        assertEquals(0, savedAfterSaves());
    }

    @Test
    public void saveAnyPhotoOffKeepsNoAddress() {
        Settings.DOWNLOAD_PHOTOS.save(false);
        listing(new Source(FULL, 2048, 1536));
        assertNull(ListingPhoto.addressOf(photo));
    }

    @Test
    public void turnedOffAfterThePhotoCameThePressIsLeftAlone() throws Exception {
        listing(new Source(FULL, 2048, 1536));
        Settings.MARKETPLACE_PHOTO_SAVE.save(false);
        touch(MotionEvent.ACTION_DOWN, X, Y);
        holdLongEnough();
        assertEquals(0, savedAfterSaves());
        assertEquals("off, the photo kept the finger from the listing", List.of(MotionEvent.ACTION_DOWN), pressable.taken);
    }

    @Test
    public void turnedOffMidPressSavesNothing() throws Exception {
        listing(new Source(FULL, 2048, 1536));
        touch(MotionEvent.ACTION_DOWN, X, Y);
        Settings.MARKETPLACE_PHOTO_SAVE.save(false);
        holdLongEnough();
        assertEquals(0, savedAfterSaves());
        assertFalse("a press that saved nothing still ended for the screen", screen.heard.contains(MotionEvent.ACTION_CANCEL));
    }

    @Test
    public void pausedKeepsNoAddressAndAPressSavesNothing() throws Exception {
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        listing(new Source(FULL, 2048, 1536));
        assertNull(ListingPhoto.addressOf(photo));
        touch(MotionEvent.ACTION_DOWN, X, Y);
        holdLongEnough();
        assertEquals(0, savedAfterSaves());
    }

    @Test
    public void pausedMidPressSavesNothing() throws Exception {
        listing(new Source(FULL, 2048, 1536));
        touch(MotionEvent.ACTION_DOWN, X, Y);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        holdLongEnough();
        assertEquals(0, savedAfterSaves());
    }
}
