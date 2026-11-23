/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static android.os.Looper.getMainLooper;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.facebook.misc.AppLockForTests;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Play saved videos in a row (#404): a reel opened from a card on the Saved page, once it ends or
 * is swiped past, closes and the card after it is tapped, scrolled into view first when it's below
 * the fold, and so on until the last card, where the chain ends on Saved. A swipe up or down in
 * that viewer is taken from Facebook and opens the next or previous card, with Saved covered in
 * black until the next video is in front. Settling players, an early end, something over the video,
 * Back, another origin, a stale press, a short slow drag, a side drag, the switch off and Pause each
 * leave Facebook's viewer alone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w360dp-h800dp")
public class SavedChainTest {
    private static final String SAVED = "saved::fb_shorts_viewer";
    private static final int CARD_W = 150;
    private static final int CARD_H = 120;
    private static final int LIST_H = 400;

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final SavedChain.Screens screens = new SavedChain.Screens();
    private ActivityController<SavedPage> pageController;
    private SavedPage page;
    private final List<ActivityController<ReelsViewer>> viewers = new ArrayList<>();

    /** Saved's page: a grid of equal cards in a RecyclerView-like list, its touches read first as the patch does. */
    public static final class SavedPage extends Activity {
        GridRecyclerView list;

        @Override
        protected void onCreate(Bundle state) {
            super.onCreate(state);
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            FrameLayout root = new FrameLayout(this);
            list = new GridRecyclerView(this, getIntent().getIntExtra("cards", 6), getIntent().getBooleanExtra("labels", true));
            root.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, LIST_H));
            setContentView(root);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (SavedChain.touched(this, event)) return true;
            return super.dispatchTouchEvent(event);
        }
    }

    /**
     * The Reels viewer a card opens, its touches read first as the patch does, keeping what reaches
     * Facebook's video. With "reads" 2 it's Facebook 587's: the screen's dispatch hands each event to
     * its delegate, which runs the base screen's dispatch with the same event, so both hooks read it.
     */
    public static final class ReelsViewer extends Activity {
        final List<Integer> facebook = new ArrayList<>();
        int reads = 1;
        TextureView player;

        @Override
        protected void onCreate(Bundle state) {
            super.onCreate(state);
            reads = getIntent().getIntExtra("reads", 1);
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            FrameLayout video = new FrameLayout(this);
            video.setOnTouchListener((view, event) -> facebook.add(event.getActionMasked()));
            if (getIntent().getBooleanExtra("video", false)) {
                // Where Facebook 588 first lays a new viewer's video out: fitted to the width, under
                // the status bar (S22 2026-11-22).
                player = new TextureView(this);
                FrameLayout.LayoutParams fitted = new FrameLayout.LayoutParams(360, 640);
                fitted.topMargin = 24;
                video.addView(player, fitted);
            }
            setContentView(video);
        }

        /**
         * Facebook filling the screen with the video a moment later, up under the status bar: its
         * viewer draws edge to edge, so the video's top is the window's top (0,0 on the S22).
         */
        void fill() {
            int[] content = new int[2];
            content().getLocationInWindow(content);
            FrameLayout.LayoutParams full = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            full.topMargin = -content[1];
            player.setLayoutParams(full);
        }

        /**
         * Facebook giving the video its own shape, wider than the window and centered, its top a
         * pixel below the window's (1185x2105 at -52,1 on the S22).
         */
        void cropToFill() {
            int[] content = new int[2];
            content().getLocationInWindow(content);
            FrameLayout.LayoutParams crop = new FrameLayout.LayoutParams(400, 700);
            crop.leftMargin = -20;
            crop.topMargin = 1 - content[1];
            player.setLayoutParams(crop);
        }

        /** Facebook laying the video out again on its way, still under the status bar. */
        void refit() {
            FrameLayout.LayoutParams fitted = new FrameLayout.LayoutParams(360, 600);
            fitted.topMargin = 40;
            player.setLayoutParams(fitted);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            for (int read = 0; read < reads; read++) {
                if (SavedChain.touched(this, event)) return true;
            }
            return super.dispatchTouchEvent(event);
        }

        View content() {
            return findViewById(android.R.id.content);
        }
    }

    /** Two columns of cards that scroll by moving the cards, as a RecyclerView does, and keep every touch they get. */
    static final class GridRecyclerView extends ViewGroup {
        final List<List<Integer>> touches = new ArrayList<>();
        int offset;

        GridRecyclerView(Context context, int cards, boolean labels) {
            super(context);
            for (int i = 0; i < cards; i++) {
                FrameLayout card = new FrameLayout(context);
                card.addView(new View(context), new FrameLayout.LayoutParams(CARD_W, CARD_H));
                if (labels) card.setContentDescription("card " + i);
                List<Integer> got = new ArrayList<>();
                touches.add(got);
                card.setOnTouchListener((view, event) -> got.add(event.getActionMasked()));
                addView(card);
            }
        }

        int contentHeight() {
            return (getChildCount() + 1) / 2 * CARD_H;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            for (int i = 0; i < getChildCount(); i++) {
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(CARD_W, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(CARD_H, MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(getDefaultSize(0, widthSpec), getDefaultSize(0, heightSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            for (int i = 0; i < getChildCount(); i++) {
                int left = i % 2 * CARD_W;
                int top = i / 2 * CARD_H - offset;
                getChildAt(i).layout(left, top, left + CARD_W, top + CARD_H);
            }
        }

        @Override
        public boolean canScrollVertically(int direction) {
            return direction > 0 ? offset + getHeight() < contentHeight() : offset > 0;
        }

        @Override
        public void scrollBy(int x, int y) {
            int dy = Math.max(-offset, Math.min(y, contentHeight() - getHeight() - offset));
            offset += dy;
            for (int i = 0; i < getChildCount(); i++) getChildAt(i).offsetTopAndBottom(-dy);
        }
    }

    @Before
    public void start() {
        forgetEarlierTouches();
        HookStatus.clear();
        SavedChain.resetForTests();
        // A fresh process: the window list is read from this test's window manager, not an earlier test's.
        AppLockForTests.disarm();
        RuntimeEnvironment.getApplication().registerActivityLifecycleCallbacks(screens);
        Settings.SAVED_VIDEOS_IN_ORDER.save(true);
    }

    /**
     * Robolectric keeps VelocityTracker's pool of two from test to test, and its clear() leaves each
     * of a tracker's 20 slots holding its place and time, while the clock starts over every test. So
     * a pooled tracker can still hold an earlier test's touches timed after this test's, and the
     * speed reads as theirs. Both pooled trackers get every slot at time 0, out of any later window.
     */
    private static void forgetEarlierTouches() {
        VelocityTracker[] pooled = {VelocityTracker.obtain(), VelocityTracker.obtain()};
        for (VelocityTracker tracker : pooled) {
            for (int i = 0; i < 21; i++) {
                MotionEvent old = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 0, 0, 0);
                tracker.addMovement(old);
                old.recycle();
            }
        }
        for (VelocityTracker tracker : pooled) tracker.recycle();
    }

    @After
    public void restore() {
        RuntimeEnvironment.getApplication().unregisterActivityLifecycleCallbacks(screens);
        PauseForTests.resume();
        Settings.SAVED_VIDEOS_IN_ORDER.resetToDefault();
        Settings.SAVED_VIDEOS_STAY_ON_END.resetToDefault();
        Settings.SAVED_POSTS_IN_ROW.resetToDefault();
        SavedChain.resetForTests();
        HookStatus.clear();
    }

    private void openSaved(int cards, boolean labels) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), SavedPage.class)
                .putExtra("cards", cards).putExtra("labels", labels);
        pageController = Robolectric.buildActivity(SavedPage.class, intent).setup();
        page = pageController.get();
        idle(0);
    }

    private View card(int index) {
        return page.list.getChildAt(index);
    }

    /** A finger pressing and lifting off a card, down through the page's window. */
    private void press(int index) {
        int[] at = new int[2];
        card(index).getLocationInWindow(at);
        float x = at[0] + CARD_W / 2f;
        float y = at[1] + 10;
        long down = SystemClock.uptimeMillis();
        MotionEvent press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        page.dispatchTouchEvent(press);
        press.recycle();
        MotionEvent lift = MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_UP, x, y, 0);
        page.dispatchTouchEvent(lift);
        lift.recycle();
        for (List<Integer> got : page.list.touches) got.clear();
    }

    /** The viewer opening from [origin] over the Saved page, in front with nothing over it. */
    private ReelsViewer openViewer(String origin) {
        return openViewer(origin, 1);
    }

    /** The same, its touches read [reads] times each. */
    private ReelsViewer openViewer(String origin, int reads) {
        return openViewer(origin, reads, false);
    }

    /** The same, showing its video in a TextureView when [video] is set. */
    private ReelsViewer openViewer(String origin, int reads, boolean video) {
        pageController.pause();
        SavedChain.viewerOpening(origin);
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), ReelsViewer.class)
                .putExtra("reads", reads).putExtra("video", video);
        ActivityController<ReelsViewer> viewer = Robolectric.buildActivity(ReelsViewer.class, intent).setup();
        viewer.windowFocusChanged(true);
        viewers.add(viewer);
        idle(0);
        return viewer.get();
    }

    /** The viewer closing and the Saved page back in front. */
    private void backToSaved() {
        ActivityController<ReelsViewer> viewer = viewers.get(viewers.size() - 1);
        viewer.pause();
        pageController.resume();
        viewer.stop().destroy();
    }

    private static void idle(long millis) {
        shadowOf(getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    /** A frame on [screen]: its layout settles and it's about to draw. */
    private static void frame(Activity screen) {
        idle(20);
        screen.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw();
    }

    /** Whether [screen] is black over everything it draws. */
    private static boolean black(Activity screen) {
        return SettleCover.blackedOut(screen.getWindow().getDecorView());
    }

    private static boolean black(Dialog window) {
        return SettleCover.blackedOut(window.getWindow().getDecorView());
    }

    private List<Integer> touchesOf(int index) {
        return page.list.touches.get(index);
    }

    private static final List<Integer> TAP = List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP);

    /**
     * A finger on [viewer] from [fromY] to [toY] across the middle (or from the middle to [toX] when
     * it's set), in [steps] moves [stepMs] apart, lifting a few milliseconds after the last move right
     * where it was, as a phone reports it.
     */
    private static void drag(ReelsViewer viewer, float fromY, float toY, float toX, int steps, long stepMs) {
        float x = 180;
        long down = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, fromY, 0);
        viewer.dispatchTouchEvent(event);
        event.recycle();
        float lastX = x;
        float lastY = fromY;
        for (int i = 1; i <= steps; i++) {
            lastX = Float.isNaN(toX) ? x : x + (toX - x) * i / steps;
            lastY = Float.isNaN(toX) ? fromY + (toY - fromY) * i / steps : fromY;
            event = MotionEvent.obtain(down, down + i * stepMs, MotionEvent.ACTION_MOVE, lastX, lastY, 0);
            viewer.dispatchTouchEvent(event);
            event.recycle();
        }
        event = MotionEvent.obtain(down, down + steps * stepMs + 4, MotionEvent.ACTION_UP, lastX, lastY, 0);
        viewer.dispatchTouchEvent(event);
        event.recycle();
    }

    private static void swipe(ReelsViewer viewer, float fromY, float toY) {
        drag(viewer, fromY, toY, Float.NaN, 10, 16);
    }

    private static final List<Integer> TAKEN = List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL);

    /** Whether the Saved page is covered in black. */
    private boolean savedCovered() {
        return black(page);
    }

    /**
     * Whether the middle of [screen]'s window comes out black when it draws, everything over its
     * views included. Needs native graphics.
     */
    private static boolean drawsBlack(Activity screen) {
        View decor = screen.getWindow().getDecorView();
        Bitmap shot = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        decor.draw(new Canvas(shot));
        return shot.getPixel(decor.getWidth() / 2, decor.getHeight() / 4) == Color.BLACK;
    }

    private ReelsViewer playing(int card) {
        return playing(card, 1);
    }

    private ReelsViewer playing(int card, int reads) {
        press(card);
        ReelsViewer viewer = openViewer(SAVED, reads);
        SavedChain.bound(new Object());
        idle(SavedChain.OPEN_GUARD_MS + 100);
        return viewer;
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.REEL_DECLUTTER + ":")) return line;
        }
        return "";
    }

    @Test
    public void eachSavedReelThatEndsOpensTheNextCard() {
        assertFalse("the switch starts on", Settings.SAVED_VIDEOS_IN_ORDER.defaultValue);
        openSaved(6, true);
        press(0);
        for (int next = 1; next <= 2; next++) {
            ReelsViewer viewer = openViewer(SAVED);
            SavedChain.Chain chain = SavedChain.chainForTests();
            assertNotNull("a reel opened from card " + (next - 1) + " started no chain", chain);
            assertEquals("the chain's card", "card " + (next - 1), chain.card.label);
            assertEquals("the chain's viewer", viewer, chain.viewer.get());
            Object player = new Object();
            SavedChain.bound(player);

            SavedChain.ended(player);
            idle(0);
            assertFalse("a reel ending right as the viewer opened closed it", viewer.isFinishing());

            idle(SavedChain.OPEN_GUARD_MS + 100);
            SavedChain.ended(player);
            idle(0);
            assertTrue("the saved reel ended and the viewer stayed", viewer.isFinishing());

            backToSaved();
            idle(SavedChain.SETTLE_MS - 50);
            assertTrue("the next card was tapped before Saved settled", touchesOf(next).isEmpty());
            idle(100 + SavedChain.PRESS_MS);
            assertEquals("card " + next + " wasn't tapped", TAP, touchesOf(next));
            for (int other = 0; other < 6; other++) {
                if (other != next) assertTrue("card " + other + " was tapped", touchesOf(other).isEmpty());
            }
            touchesOf(next).clear();
            assertTrue(statusLine(), statusLine().contains(SavedChain.NEXT + " " + next));
        }
    }

    @Test
    public void aSwipeOpensTheNextCardOnceThePlayerSettles() {
        openSaved(6, true);
        press(2);
        ReelsViewer viewer = openViewer(SAVED);
        Object first = new Object();
        Object settled = new Object();
        SavedChain.bound(first);
        SavedChain.bound(settled);
        idle(0);
        assertFalse("a second player as the viewer settled in counted as a swipe", viewer.isFinishing());

        idle(SavedChain.SWIPE_GUARD_MS + 100);
        SavedChain.bound(settled);
        idle(0);
        assertFalse("the same player binding again counted as a swipe", viewer.isFinishing());

        SavedChain.bound(new Object());
        idle(0);
        assertTrue("a swipe to the next reel kept Facebook's suggestion", viewer.isFinishing());
        backToSaved();
        idle(SavedChain.SETTLE_MS + SavedChain.PRESS_MS + 50);
        assertEquals("the card after the swiped one", TAP, touchesOf(3));
    }

    /** The reporter's video: Facebook's own swipe flashed a suggested reel and then Saved before the next saved one. */
    @Test
    public void aSwipeUpIsTakenFromFacebookAndSavedStaysCoveredUntilTheNextVideo() {
        openSaved(6, true);
        ReelsViewer viewer = playing(1);
        swipe(viewer, 600, 300);
        assertEquals("Facebook's video went on with the swipe", TAKEN, viewer.facebook);
        assertEquals("the video didn't follow the finger", -300f, viewer.content().getTranslationY(), 0.5f);
        assertFalse("the viewer closed before the video slid off", viewer.isFinishing());

        idle(SavedChain.SLIDE_MS + 50);
        assertTrue("a swipe up stayed in the viewer", viewer.isFinishing());
        assertTrue("Saved showed between one saved video and the next", savedCovered());

        backToSaved();
        idle(SavedChain.SETTLE_MS + SavedChain.PRESS_MS + 50);
        assertEquals("the tap under the cover didn't reach card 2", TAP, touchesOf(2));
        assertTrue("card 0 was tapped", touchesOf(0).isEmpty());
        assertTrue(statusLine(), statusLine().contains(SavedChain.NEXT + " 1"));
        assertTrue("the cover came off before the next video opened", savedCovered());

        openViewer(SAVED);
        assertEquals("the next chain's card", "card 2", SavedChain.chainForTests().card.label);
        assertTrue("the cover came off as the next video opened, before it drew", savedCovered());
        idle(SavedChain.UNCOVER_MS + 50);
        assertFalse("the cover stayed on Saved", savedCovered());
    }

    /**
     * On the S22 the Saved grid showed for 80 ms as the chain's tap opened a saved post, and again
     * as the post closed: Facebook laid its page back out over the black the chain had put after it
     * (2026-11-22, a frame-by-frame recording). Whatever Facebook adds or moves, Saved stays black.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void savedStaysBlackWhenFacebookPutsItsPageBackOverTheCover() {
        openSaved(6, true);
        page.list.setBackgroundColor(Color.WHITE);
        ReelsViewer viewer = playing(1);
        swipe(viewer, 600, 300);
        idle(SavedChain.SLIDE_MS + 50);
        backToSaved();
        idle(0);
        assertTrue("Saved showed between one saved video and the next", drawsBlack(page));

        ViewGroup content = page.findViewById(android.R.id.content);
        View facebooks = content.getChildAt(0);
        content.removeView(facebooks);
        content.addView(facebooks);
        idle(0);
        assertTrue("Saved showed once Facebook put its page back on top", drawsBlack(page));
    }

    /**
     * Facebook 587 (#404): both hooks read every event in the viewer, and the second read of the move
     * that was taken, a cancel by then, ended the takeover, so Facebook's pager scrolled a suggested
     * reel in under the saved one before the next saved video.
     */
    @Test
    public void aSwipeReadTwiceIsStillTakenFromFacebook() {
        openSaved(6, true);
        ReelsViewer viewer = playing(1, 2);
        swipe(viewer, 600, 300);
        assertEquals("Facebook's video went on with the swipe", TAKEN, viewer.facebook);
        assertEquals("the video didn't follow the finger", -300f, viewer.content().getTranslationY(), 0.5f);

        idle(SavedChain.SLIDE_MS + 50);
        assertTrue("a swipe up stayed in the viewer", viewer.isFinishing());
        backToSaved();
        idle(SavedChain.SETTLE_MS + SavedChain.PRESS_MS + 50);
        assertEquals("card 2, the one after", TAP, touchesOf(2));

        ReelsViewer next = openViewer(SAVED, 2);
        SavedChain.bound(new Object());
        idle(SavedChain.OPEN_GUARD_MS + 100);
        drag(next, 400, 340, Float.NaN, 20, 100);
        idle(SavedChain.SLIDE_MS + 100);
        assertFalse("a short slow drag read twice left the video", next.isFinishing());
        assertEquals("the video didn't slide back", 0f, next.content().getTranslationY(), 0.5f);
    }

    @Test
    public void aSwipeDownOpensTheSavedVideoBefore() {
        openSaved(6, true);
        ReelsViewer viewer = playing(3);
        swipe(viewer, 300, 600);
        assertEquals(TAKEN, viewer.facebook);
        idle(SavedChain.SLIDE_MS + 50);
        assertTrue("a swipe down stayed in the viewer", viewer.isFinishing());

        backToSaved();
        idle(SavedChain.SETTLE_MS + SavedChain.PRESS_MS + 50);
        assertEquals("card 2, the one before", TAP, touchesOf(2));
        assertTrue("card 4 was tapped", touchesOf(4).isEmpty());
        assertTrue(statusLine(), statusLine().contains(SavedChain.PREVIOUS + " 1"));
        assertFalse(statusLine(), statusLine().contains(SavedChain.NEXT + " "));
    }

    @Test
    public void aShortSlowDragSlidesBack() {
        openSaved(6, true);
        ReelsViewer viewer = playing(1);
        drag(viewer, 400, 340, Float.NaN, 20, 100);
        assertEquals("Facebook didn't get a cancel once the drag was taken",
                MotionEvent.ACTION_CANCEL, (int) viewer.facebook.get(viewer.facebook.size() - 1));
        assertFalse("Facebook got the lift of a taken drag", viewer.facebook.contains(MotionEvent.ACTION_UP));
        idle(SavedChain.SLIDE_MS + 100);
        assertFalse("a short slow drag left the video", viewer.isFinishing());
        assertEquals("the video didn't slide back", 0f, viewer.content().getTranslationY(), 0.5f);
        assertNotNull(SavedChain.chainForTests());
        assertFalse(SavedChain.chainForTests().moving);
        assertFalse(savedCovered());
    }

    @Test
    public void aQuickFlickIsASwipeHoweverShort() {
        openSaved(6, true);
        ReelsViewer viewer = playing(1);
        drag(viewer, 400, 340, Float.NaN, 4, 8);
        idle(SavedChain.SLIDE_MS + 50);
        assertTrue("a quick flick up stayed in the viewer", viewer.isFinishing());
        backToSaved();
        idle(SavedChain.SETTLE_MS + SavedChain.PRESS_MS + 50);
        assertEquals(TAP, touchesOf(2));
    }

    @Test
    public void aSwipeDownOnTheFirstSavedVideoStays() {
        openSaved(6, true);
        ReelsViewer viewer = playing(0);
        swipe(viewer, 300, 600);
        idle(SavedChain.SLIDE_MS + 100);
        assertFalse("a swipe down on the first saved video left it", viewer.isFinishing());
        assertEquals(0f, viewer.content().getTranslationY(), 0.5f);
        assertNotNull(SavedChain.chainForTests());
        assertFalse(savedCovered());
    }

    @Test
    public void facebookKeepsSideDragsAndSwipesUnderASheet() {
        openSaved(6, true);
        ReelsViewer viewer = playing(1);
        drag(viewer, 400, 400, 330, 10, 16);
        assertFalse("a side drag was taken", viewer.facebook.contains(MotionEvent.ACTION_CANCEL));
        assertEquals((int) MotionEvent.ACTION_UP, (int) viewer.facebook.get(viewer.facebook.size() - 1));

        viewer.facebook.clear();
        viewers.get(0).windowFocusChanged(false);
        swipe(viewer, 600, 300);
        assertFalse("a swipe under a sheet was taken", viewer.facebook.contains(MotionEvent.ACTION_CANCEL));
        idle(SavedChain.SLIDE_MS + 100);
        assertFalse(viewer.isFinishing());
        assertEquals(0f, viewer.content().getTranslationY(), 0.5f);
    }

    @Test
    public void stayOnEndKeepsAnEndedVideoButASwipeStillMovesOn() {
        assertFalse("Stay on a saved video when it ends starts on", Settings.SAVED_VIDEOS_STAY_ON_END.defaultValue);
        Settings.SAVED_VIDEOS_STAY_ON_END.save(true);
        openSaved(6, true);
        ReelsViewer viewer = playing(1);
        SavedChain.ended(new Object());
        idle(SavedChain.SLIDE_MS + 50);
        assertFalse("the saved video that ended moved on", viewer.isFinishing());
        assertNotNull(SavedChain.chainForTests());

        swipe(viewer, 600, 300);
        idle(SavedChain.SLIDE_MS + 50);
        assertTrue("a swipe up stayed in the viewer", viewer.isFinishing());
    }

    /** A card below the fold is scrolled up into view and then tapped, found by its place when cards have no labels. */
    @Test
    public void theNextCardIsScrolledIntoViewFirst() {
        openSaved(10, false);
        assertTrue("the test's list can't scroll", page.list.canScrollVertically(1));
        press(6);
        ReelsViewer viewer = openViewer(SAVED);
        Object player = new Object();
        SavedChain.bound(player);
        idle(SavedChain.OPEN_GUARD_MS + 100);
        SavedChain.ended(player);
        idle(0);
        assertTrue(viewer.isFinishing());

        backToSaved();
        idle(SavedChain.SETTLE_MS + 10);
        assertTrue("the list didn't scroll", page.list.offset > 0);
        assertTrue("card 7 was tapped below the fold", touchesOf(7).isEmpty());
        idle(SavedChain.SCROLL_SETTLE_MS + SavedChain.PRESS_MS + 50);
        assertEquals("card 7, scrolled into view", TAP, touchesOf(7));
        assertTrue("card 8 was tapped", touchesOf(8).isEmpty());
    }

    @Test
    public void theLastCardEndsBackOnSaved() {
        openSaved(6, true);
        assertFalse("the test's list scrolls", page.list.canScrollVertically(1));
        press(5);
        ReelsViewer viewer = openViewer(SAVED);
        Object player = new Object();
        SavedChain.bound(player);
        idle(SavedChain.OPEN_GUARD_MS + 100);
        SavedChain.ended(player);
        idle(0);
        assertTrue("the last saved reel went on to Facebook's suggestions", viewer.isFinishing());
        assertTrue(savedCovered());

        backToSaved();
        idle(SavedChain.SETTLE_MS + SavedChain.SCROLL_SETTLE_MS + SavedChain.PRESS_MS + 50);
        assertNull("the chain went on past the last card", SavedChain.chainForTests());
        assertFalse("Saved stayed covered with nothing to play", savedCovered());
        for (int card = 0; card < 6; card++) {
            assertTrue("card " + card + " was tapped after the last one", touchesOf(card).isEmpty());
        }
        assertFalse(statusLine(), statusLine().contains(SavedChain.NEXT + " "));
    }

    /** The device's case: a card at the end of a list that still scrolls, with nothing below it. */
    @Test
    public void aListThatStillScrollsEndsOnSavedToo() {
        openSaved(10, false);
        page.list.scrollBy(0, 100);
        assertTrue("the test's list can't scroll any more", page.list.canScrollVertically(1));
        press(9);
        ReelsViewer viewer = openViewer(SAVED);
        Object player = new Object();
        SavedChain.bound(player);
        idle(SavedChain.SWIPE_GUARD_MS + 100);
        SavedChain.bound(new Object());
        idle(0);
        assertTrue("a swipe on the last saved reel stayed in the viewer", viewer.isFinishing());

        backToSaved();
        idle(SavedChain.SETTLE_MS + 5 * SavedChain.SCROLL_SETTLE_MS + SavedChain.PRESS_MS + 50);
        assertNull("the chain went on past the last card", SavedChain.chainForTests());
        for (int card = 0; card < 10; card++) {
            assertTrue("card " + card + " was tapped after the last one", touchesOf(card).isEmpty());
        }
    }

    @Test
    public void somethingOverTheVideoKeepsIt() {
        openSaved(6, true);
        press(0);
        ReelsViewer viewer = openViewer(SAVED);
        Object player = new Object();
        SavedChain.bound(player);
        idle(SavedChain.OPEN_GUARD_MS + 100);
        viewers.get(0).windowFocusChanged(false);
        SavedChain.ended(player);
        idle(0);
        assertFalse("the viewer closed under a sheet or menu", viewer.isFinishing());
        assertNotNull("the chain ended with something over the video", SavedChain.chainForTests());

        viewers.get(0).windowFocusChanged(true);
        SavedChain.ended(player);
        idle(0);
        assertTrue("the next end, with the video in front again", viewer.isFinishing());
    }

    @Test
    public void backFromTheViewerEndsTheChain() {
        openSaved(6, true);
        press(0);
        ReelsViewer viewer = openViewer(SAVED);
        assertNotNull(SavedChain.chainForTests());
        backToSaved();
        assertNull("Back from the viewer kept the chain", SavedChain.chainForTests());
        SavedChain.ended(new Object());
        idle(SavedChain.SETTLE_MS + SavedChain.PRESS_MS + 50);
        for (int i = 0; i < 6; i++) assertTrue("card " + i + " was tapped after Back", touchesOf(i).isEmpty());
        assertFalse(viewer.isFinishing());
    }

    @Test
    public void onlyAViewerFromSavedRightAfterAPressStartsAChain() {
        openSaved(6, true);
        press(0);
        openViewer("video_home::feed_story");
        assertNull("a viewer opening from the feed started a chain", SavedChain.chainForTests());
        backToSaved();

        press(0);
        idle(SavedChain.TOUCH_WINDOW_MS + 100);
        openViewer(SAVED);
        assertNull("a press long before the viewer started a chain", SavedChain.chainForTests());
        backToSaved();

        press(0);
        openViewer(SAVED);
        assertNotNull(SavedChain.chainForTests());
        SavedChain.viewerOpening("video_home::feed_story");
        assertNull("a viewer from the feed left the chain going", SavedChain.chainForTests());

        assertTrue(SavedChain.fromSaved(SAVED));
        assertTrue(SavedChain.fromSaved("saved"));
        assertFalse(SavedChain.fromSaved("saved_collection::fb_shorts_viewer"));
        assertFalse(SavedChain.fromSaved(""));
    }

    @Test
    public void offOrPausedNothingChains() {
        Settings.SAVED_VIDEOS_IN_ORDER.save(false);
        openSaved(6, true);
        press(0);
        ReelsViewer viewer = openViewer(SAVED);
        assertNull("off, a chain started", SavedChain.chainForTests());
        backToSaved();

        Settings.SAVED_VIDEOS_IN_ORDER.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        press(0);
        openViewer(SAVED);
        assertNull("paused, a chain started", SavedChain.chainForTests());
        backToSaved();
        PauseForTests.resume();

        press(0);
        ReelsViewer armed = openViewer(SAVED);
        Object player = new Object();
        SavedChain.bound(player);
        idle(SavedChain.OPEN_GUARD_MS + 100);
        Settings.SAVED_VIDEOS_IN_ORDER.save(false);
        SavedChain.ended(player);
        idle(0);
        assertFalse("turned off mid-video, the viewer still closed", armed.isFinishing());
        assertNull(SavedChain.chainForTests());
        assertFalse(viewer.isFinishing());
        assertFalse("a chain that never ran counted a next card", statusLine().contains(SavedChain.NEXT));
    }

    /** A saved post's page: a screen a card opens over Saved that isn't the Reels viewer. */
    public static final class PostPage extends Activity {
        @Override
        protected void onCreate(Bundle state) {
            super.onCreate(state);
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            setContentView(new FrameLayout(this));
        }
    }

    /** A saved post's page opening over Saved, in front, with no Reels viewer saying where it's from. */
    private ActivityController<PostPage> openPost() {
        pageController.pause();
        ActivityController<PostPage> post = Robolectric.buildActivity(PostPage.class).setup();
        post.windowFocusChanged(true);
        idle(0);
        return post;
    }

    /** The post's page closing and Saved back in front. */
    private void postClosed(ActivityController<PostPage> post) {
        post.pause();
        pageController.resume();
        post.stop().destroy();
    }

    /** A chain on card 0 whose video ended, with Saved back and card 1 just tapped by the chain. */
    private void chainTappedCardOne() {
        openSaved(6, true);
        playing(0);
        SavedChain.ended(new Object());
        idle(0);
        backToSaved();
        idle(SavedChain.SETTLE_MS + SavedChain.PRESS_MS + 50);
        assertEquals("card 1 wasn't tapped", TAP, touchesOf(1));
        touchesOf(1).clear();
    }

    @Test
    public void aPostTheChainOpenedIsClosedForTheCardAfterIt() {
        chainTappedCardOne();
        ActivityController<PostPage> post = openPost();
        assertTrue("the post showed while the chain made sure it wasn't a video", black(post.get()));
        idle(SavedChain.POST_CHECK_MS - 100);
        assertFalse("the post closed before a slow video had its chance", post.get().isFinishing());
        idle(200);
        assertTrue("the chain stopped on a saved post", post.get().isFinishing());
        SavedChain.Chain chain = SavedChain.chainForTests();
        assertNotNull("closing the post ended the chain", chain);
        assertEquals("the chain's card", "card 1", chain.card.label);
        assertEquals(1, chain.postsSkipped);
        assertTrue("Saved showed while the post closed", savedCovered());
        assertTrue(statusLine(), statusLine().contains(SavedChain.POST_SKIPPED + " 1"));

        postClosed(post);
        idle(SavedChain.SETTLE_MS + SavedChain.PRESS_MS + 50);
        assertEquals("the card after the post wasn't tapped", TAP, touchesOf(2));
        for (int other : new int[]{0, 1, 3, 4, 5}) assertTrue("card " + other + " was tapped", touchesOf(other).isEmpty());

        // Card 2 is a video: its viewer says it's from Saved, and the chain carries on from there.
        ReelsViewer viewer = openViewer(SAVED);
        idle(SavedChain.POST_CHECK_MS + 100);
        assertFalse("a saved video was closed as a post", viewer.isFinishing());
        assertEquals("card 2", SavedChain.chainForTests().card.label);
        assertEquals("a video started the post count over", 0, SavedChain.chainForTests().postsSkipped);
    }

    @Test
    public void aVideoThatSaysWhereItsFromLateStays() {
        chainTappedCardOne();
        ActivityController<PostPage> slow = openPost();
        idle(SavedChain.POST_CHECK_MS / 2);
        SavedChain.viewerOpening(SAVED);
        idle(SavedChain.POST_CHECK_MS);
        assertFalse("a slow saved video was closed as a post", slow.get().isFinishing());
        assertNotNull(SavedChain.chainForTests());
        assertFalse(SavedChain.chainForTests().moving);
        assertFalse(statusLine().contains(SavedChain.POST_SKIPPED));
    }

    @Test
    public void aPostOpenedByHandOrWithTheSwitchOffStays() {
        openSaved(6, true);
        press(1);
        ActivityController<PostPage> mine = openPost();
        idle(SavedChain.POST_CHECK_MS + 500);
        assertFalse("a post the user opened was closed", mine.get().isFinishing());
        assertNull(SavedChain.chainForTests());
        postClosed(mine);

        chainTappedCardOne();
        Settings.SAVED_VIDEOS_IN_ORDER.save(false);
        ActivityController<PostPage> off = openPost();
        idle(SavedChain.POST_CHECK_MS + 500);
        assertFalse("off, the chain closed a post", off.get().isFinishing());
        assertFalse(statusLine().contains(SavedChain.POST_SKIPPED));
    }

    @Test
    public void aRunOfPostsEndsTheChainOnTheLastOneAllowed() {
        chainTappedCardOne();
        SavedChain.Chain chain = null;
        ActivityController<PostPage> post = openPost();
        idle(SavedChain.POST_CHECK_MS + 100);
        chain = SavedChain.chainForTests();
        assertNotNull(chain);
        // Pretend the chain already closed the most it may: the next post it opens stays.
        chain.postsSkipped = SavedChain.MAX_POSTS_SKIPPED;
        postClosed(post);
        idle(SavedChain.SETTLE_MS + SavedChain.PRESS_MS + 50);
        assertEquals(TAP, touchesOf(2));
        ActivityController<PostPage> last = openPost();
        idle(SavedChain.POST_CHECK_MS + 100);
        assertFalse("the chain kept closing posts past its limit", last.get().isFinishing());
        assertNull(SavedChain.chainForTests());
        assertFalse("the post the chain left open stayed black", black(last.get()));
    }

    /**
     * Facebook 588's saved post page: a window over the Saved page's own screen, with no screen of
     * its own. As on the S22 (2026-11-22), a Back key handed to its views is swallowed with nothing
     * closing, since Back reaches it through Android's back callbacks, which end in its own Back.
     */
    static final class PostWindow extends Dialog {
        final boolean keepsBack;
        int backs;
        int escapes;

        PostWindow(Activity page, boolean keepsBack) {
            super(page);
            this.keepsBack = keepsBack;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
                // Android 14 and later: a dialog cancels on Escape.
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    escapes++;
                    cancel();
                }
                return true;
            }
            return event.getKeyCode() == KeyEvent.KEYCODE_BACK || super.dispatchKeyEvent(event);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onBackPressed() {
            backs++;
            if (!keepsBack) super.onBackPressed();
        }
    }

    /** A saved post opening as a window over Saved, which loses the focus to it. */
    private PostWindow openPostWindow() {
        return openPostWindow(false);
    }

    /**
     * What Facebook 588 hands a dialog's window as its callback on the S22 (2026-11-22): a proxy
     * whose handler passes each call on to the dialog it holds in a plain field.
     */
    static final class Forwarder implements InvocationHandler {
        private final Object target;

        Forwarder(Object target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException thrown) {
                throw thrown.getCause();
            }
        }
    }

    private PostWindow openPostWindow(boolean keepsBack) {
        PostWindow post = new PostWindow(page, keepsBack);
        post.getWindow().setCallback((Window.Callback) Proxy.newProxyInstance(Window.Callback.class.getClassLoader(),
                new Class<?>[]{Window.Callback.class}, new Forwarder(post)));
        post.setContentView(new FrameLayout(page));
        post.show();
        pageController.windowFocusChanged(false);
        idle(0);
        return post;
    }

    @Test
    public void aPostShownAsAWindowOverSavedGetsBackAndTheCardAfterItPlays() {
        chainTappedCardOne();
        PostWindow post = openPostWindow();
        idle(50);
        assertTrue("the post showed while the chain made sure it wasn't a video", black(post));
        idle(SavedChain.POST_CHECK_MS - 150);
        assertTrue("the post closed before a slow video had its chance", post.isShowing());
        idle(200);
        assertFalse("the chain stopped on a saved post shown over Saved", post.isShowing());
        assertEquals("one Back", 1, post.backs);
        SavedChain.Chain chain = SavedChain.chainForTests();
        assertNotNull("closing the post ended the chain", chain);
        assertEquals("card 1", chain.card.label);
        assertEquals(1, chain.postsSkipped);
        assertTrue(statusLine(), statusLine().contains(SavedChain.POST_SKIPPED + " 1"));

        pageController.windowFocusChanged(true);
        idle(SavedChain.SETTLE_MS + SavedChain.PRESS_MS + 50);
        assertEquals("the card after the post wasn't tapped", TAP, touchesOf(2));
        for (int other : new int[]{0, 1, 3, 4, 5}) assertTrue("card " + other + " was tapped", touchesOf(other).isEmpty());

        ReelsViewer viewer = openViewer(SAVED);
        idle(SavedChain.POST_CHECK_MS + 100);
        assertFalse("a saved video was closed as a post", viewer.isFinishing());
        assertEquals("card 2", SavedChain.chainForTests().card.label);
    }

    @Test
    public void aPostWindowThatKeepsItsBackIsDismissedAndTheChainGoesOn() {
        chainTappedCardOne();
        PostWindow post = openPostWindow(true);
        idle(SavedChain.POST_CHECK_MS + 100);
        assertTrue("the post wasn't given its Back first", post.isShowing());
        assertEquals(1, post.backs);
        idle(SavedChain.SETTLE_MS);
        assertFalse("a post that kept its Back stayed open", post.isShowing());

        pageController.windowFocusChanged(true);
        idle(SavedChain.SETTLE_MS + SavedChain.PRESS_MS + 50);
        assertEquals("the card after the post wasn't tapped", TAP, touchesOf(2));
    }

    @Test
    public void aPostWindowWhoseDialogCantBeReadGetsEscapeAndTheChainGoesOn() {
        SavedChain.readWindows = false;
        try {
            chainTappedCardOne();
            PostWindow post = openPostWindow();
            idle(SavedChain.POST_CHECK_MS + 100);
            assertFalse("a post window with no dialog to read stayed open", post.isShowing());
            assertEquals(1, post.escapes);
            assertEquals(0, post.backs);

            pageController.windowFocusChanged(true);
            idle(SavedChain.SETTLE_MS + SavedChain.PRESS_MS + 50);
            assertEquals("the card after the post wasn't tapped", TAP, touchesOf(2));
        } finally {
            SavedChain.readWindows = true;
        }
    }

    @Test
    public void aWindowAlreadyOverSavedOrOpenedByHandStays() {
        openSaved(6, true);
        press(1);
        PostWindow mine = openPostWindow();
        idle(50);
        assertFalse("a post the user opened went black", black(mine));
        idle(SavedChain.TOUCH_WINDOW_MS + 500);
        assertTrue("a post the user opened was closed", mine.isShowing());
        assertNull(SavedChain.chainForTests());
        mine.dismiss();
        pageController.windowFocusChanged(true);

        playing(0);
        SavedChain.ended(new Object());
        idle(0);
        PostWindow early = new PostWindow(page, false);
        early.setContentView(new FrameLayout(page));
        early.show();
        backToSaved();
        idle(SavedChain.SETTLE_MS + SavedChain.PRESS_MS + SavedChain.TOUCH_WINDOW_MS);
        assertTrue("a window up before the chain's tap was closed", early.isShowing());
        assertFalse("a window up before the chain's tap went black", black(early));
        assertEquals(0, early.backs);
        assertEquals(0, early.escapes);
        assertFalse(statusLine().contains(SavedChain.POST_SKIPPED));
    }

    @Test
    public void aWindowThatIsntThePostGetsItsBlackLiftedAndTheLastPostAllowedShows() {
        chainTappedCardOne();
        PostWindow other = openPostWindow();
        idle(50);
        assertTrue(black(other));
        SavedChain.viewerOpening(SAVED); // A saved video after all.
        idle(50);
        assertTrue(other.isShowing());
        assertFalse("a window over Saved stayed black once a saved video started", black(other));
        other.dismiss();
        idle(0);

        SavedChain.Chain chain = SavedChain.chainForTests();
        assertNotNull(chain);
        chain.postsSkipped = SavedChain.MAX_POSTS_SKIPPED;
        pageController.pause();
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), ReelsViewer.class);
        ActivityController<ReelsViewer> viewer = Robolectric.buildActivity(ReelsViewer.class, intent).setup();
        viewer.windowFocusChanged(true);
        viewers.add(viewer);
        SavedChain.bound(new Object());
        idle(SavedChain.OPEN_GUARD_MS + 100);
        SavedChain.ended(new Object());
        idle(0);
        backToSaved();
        pageController.windowFocusChanged(true);
        idle(SavedChain.SETTLE_MS + SavedChain.PRESS_MS + 50);
        assertEquals("card 2 wasn't tapped", TAP, touchesOf(2));
        PostWindow last = openPostWindow();
        idle(SavedChain.POST_CHECK_MS + 100);
        assertTrue("the chain closed a post past its limit", last.isShowing());
        assertFalse("the post the chain left open stayed black", black(last));
    }

    /** A saved post's content: what Facebook's views get of each touch, and whether it can still scroll down. */
    static final class PostContent extends FrameLayout {
        final List<Integer> touches = new ArrayList<>();
        boolean scrollsDown;

        PostContent(Context context) {
            super(context);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            touches.add(event.getActionMasked());
            return true;
        }

        @Override
        public boolean canScrollVertically(int direction) {
            return direction > 0 && scrollsDown;
        }
    }

    /** A saved post window filling the screen, its content a {@link PostContent}. */
    private PostWindow openFullPostWindow() {
        PostWindow post = openPostWindow();
        post.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        post.setContentView(new PostContent(page));
        idle(0);
        return post;
    }

    private static PostContent contentOf(PostWindow post) {
        return (PostContent) ((ViewGroup) post.findViewById(android.R.id.content)).getChildAt(0);
    }

    /** A finger on [post]'s window from [fromY] to [toY], in ten moves 16 ms apart, as Android hands it to the window. */
    private static void swipe(PostWindow post, float fromY, float toY) {
        View decor = post.getWindow().getDecorView();
        float x = decor.getWidth() / 2f;
        long down = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, fromY, 0);
        decor.dispatchTouchEvent(event);
        event.recycle();
        for (int i = 1; i <= 10; i++) {
            event = MotionEvent.obtain(down, down + i * 16L, MotionEvent.ACTION_MOVE, x, fromY + (toY - fromY) * i / 10, 0);
            decor.dispatchTouchEvent(event);
            event.recycle();
        }
        event = MotionEvent.obtain(down, down + 164, MotionEvent.ACTION_UP, x, toY, 0);
        decor.dispatchTouchEvent(event);
        event.recycle();
    }

    /** With Show saved posts in the row on, the chain on card 0 tapped card 1, and card 1's post stopped the row. */
    private PostWindow postInTheRow() {
        Settings.SAVED_POSTS_IN_ROW.save(true);
        chainTappedCardOne();
        PostWindow post = openFullPostWindow();
        idle(SavedChain.POST_CHECK_MS + 100);
        assertTrue("the post was closed", post.isShowing());
        return post;
    }

    @Test
    public void aSavedPostInTheRowStaysOnScreen() {
        assertFalse("posts in the row start on", Settings.SAVED_POSTS_IN_ROW.defaultValue);
        PostWindow post = postInTheRow();
        idle(SavedChain.TOUCH_WINDOW_MS + 500);
        assertTrue("a post in the row was closed", post.isShowing());
        assertEquals("the post got a Back", 0, post.backs);
        assertEquals(0, post.escapes);
        assertFalse("the post stayed black", black(post));
        assertFalse("Saved stayed covered", savedCovered());
        assertNull("the chain didn't stop on the post", SavedChain.chainForTests());
        for (int other = 0; other < 6; other++) assertTrue("card " + other + " was tapped", touchesOf(other).isEmpty());
        assertTrue(statusLine(), statusLine().contains(SavedChain.POST_SHOWN + " 1"));
        assertFalse(statusLine(), statusLine().contains(SavedChain.POST_SKIPPED));
    }

    @Test
    public void aSwipeUpPastASavedPostSlidesItOffAndTheNextCardPlays() {
        PostWindow post = postInTheRow();
        View decor = post.getWindow().getDecorView();
        assertTrue("the post window wasn't laid out", decor.getHeight() > 200);
        swipe(post, decor.getHeight() * 0.7f, decor.getHeight() * 0.2f);
        assertEquals("Facebook kept the swipe", TAKEN, contentOf(post).touches);
        idle(SavedChain.SLIDE_MS + 50);
        assertEquals("one Back closed the post", 1, post.backs);
        assertFalse(post.isShowing());
        assertTrue("Saved showed while the post closed", savedCovered());
        SavedChain.Chain chain = SavedChain.chainForTests();
        assertNotNull("the swipe started no chain", chain);
        assertEquals("card 1", chain.card.label);

        pageController.windowFocusChanged(true);
        idle(SavedChain.SETTLE_MS + SavedChain.PRESS_MS + 50);
        assertEquals("the card after the post wasn't tapped", TAP, touchesOf(2));
        for (int other : new int[]{0, 1, 3, 4, 5}) assertTrue("card " + other + " was tapped", touchesOf(other).isEmpty());
        ReelsViewer viewer = openViewer(SAVED);
        idle(SavedChain.POST_CHECK_MS + 100);
        assertFalse("the next saved video was closed", viewer.isFinishing());
        assertEquals("card 2", SavedChain.chainForTests().card.label);
    }

    @Test
    public void aSwipeDownPastASavedPostPlaysTheOneBefore() {
        PostWindow post = postInTheRow();
        View decor = post.getWindow().getDecorView();
        swipe(post, decor.getHeight() * 0.2f, decor.getHeight() * 0.7f);
        idle(SavedChain.SLIDE_MS + 50);
        assertEquals(1, post.backs);
        assertFalse(post.isShowing());
        pageController.windowFocusChanged(true);
        idle(SavedChain.SETTLE_MS + SavedChain.PRESS_MS + 50);
        assertEquals("the card before the post wasn't tapped", TAP, touchesOf(0));
        for (int other : new int[]{1, 2, 3, 4, 5}) assertTrue("card " + other + " was tapped", touchesOf(other).isEmpty());
    }

    @Test
    public void aSavedPostThatStillScrollsKeepsTheSwipeAndAShortDragSlidesBack() {
        PostWindow post = postInTheRow();
        PostContent content = contentOf(post);
        View decor = post.getWindow().getDecorView();
        float height = decor.getHeight();
        content.scrollsDown = true;
        swipe(post, height * 0.7f, height * 0.2f);
        assertEquals("the post's own scroll lost the swipe", MotionEvent.ACTION_UP,
                (int) content.touches.get(content.touches.size() - 1));
        assertFalse(content.touches.contains(MotionEvent.ACTION_CANCEL));
        idle(SavedChain.SLIDE_MS + SavedChain.SETTLE_MS + 100);
        assertTrue("a swipe the post could still scroll closed it", post.isShowing());
        assertEquals(0, post.backs);

        content.scrollsDown = false;
        content.touches.clear();
        // Under an eighth of the screen, slowly: not a swipe.
        View moved = post.findViewById(android.R.id.content);
        long down = SystemClock.uptimeMillis();
        float x = decor.getWidth() / 2f;
        float from = height * 0.6f;
        float to = from - height * SavedChain.SWIPE_SHARE * 0.5f;
        MotionEvent event = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, from, 0);
        decor.dispatchTouchEvent(event);
        event.recycle();
        for (int i = 1; i <= 10; i++) {
            event = MotionEvent.obtain(down, down + i * 100L, MotionEvent.ACTION_MOVE, x, from + (to - from) * i / 10, 0);
            decor.dispatchTouchEvent(event);
            event.recycle();
        }
        assertTrue("the post didn't follow the finger", moved.getTranslationY() < 0);
        event = MotionEvent.obtain(down, down + 1300, MotionEvent.ACTION_UP, x, to, 0);
        decor.dispatchTouchEvent(event);
        event.recycle();
        idle(SavedChain.SLIDE_MS + SavedChain.SETTLE_MS + 100);
        assertTrue("a short drag closed the post", post.isShowing());
        assertEquals(0, post.backs);
        assertEquals("the post didn't slide back", 0f, moved.getTranslationY(), 0.5f);
        for (int other = 0; other < 6; other++) assertTrue("card " + other + " was tapped", touchesOf(other).isEmpty());
    }

    @Test
    public void withPostsInTheRowOffASwipeOnAPostIsFacebooks() {
        PostWindow post = postInTheRow();
        Settings.SAVED_POSTS_IN_ROW.save(false);
        View decor = post.getWindow().getDecorView();
        swipe(post, decor.getHeight() * 0.7f, decor.getHeight() * 0.2f);
        idle(SavedChain.SLIDE_MS + SavedChain.SETTLE_MS + 100);
        assertTrue(post.isShowing());
        assertEquals(0, post.backs);
        assertFalse(contentOf(post).touches.contains(MotionEvent.ACTION_CANCEL));
    }

    @Test
    public void theNextVideoStaysBlackUntilFacebookMovesItIntoPlace() {
        openSaved(6, true);
        playing(0);
        assertFalse("a video opened by hand went black", black(viewers.get(0).get()));
        SavedChain.ended(new Object());
        idle(0);
        backToSaved();
        idle(SavedChain.SETTLE_MS + SavedChain.PRESS_MS + 50);
        ReelsViewer next = openViewer(SAVED, 1, true);
        frame(next);
        assertTrue("the next video showed before Facebook moved it into place", black(next));
        idle(SettleCover.HOLD_MS - 200);
        frame(next);
        assertTrue("the next video showed before Facebook moved it into place", black(next));
        next.fill();
        frame(next);
        assertTrue("the next video showed as Facebook moved it", black(next));
        idle(SettleCover.MOVED_HOLD_MS + 50);
        frame(next);
        assertFalse("the next video stayed black once in place", black(next));
    }

    /**
     * On the S22 the black came off as Facebook first laid the video out again, still fitted under
     * the status bar, so the jump to the full screen showed after all (2026-11-22, two frames of
     * it). Each move starts a short hold over, so the video shows only once it stops moving.
     */
    @Test
    public void eachMoveStartsTheHoldOverSoTheNextVideoShowsOnceItStopsMoving() {
        chainTappedCardOne();
        ReelsViewer next = openViewer(SAVED, 1, true);
        frame(next);
        idle(SettleCover.HOLD_MS - 300);
        next.refit();
        frame(next);
        assertTrue("the next video showed as Facebook moved it", black(next));
        idle(SettleCover.MOVED_HOLD_MS - 100);
        frame(next);
        assertTrue("the next video showed between two of Facebook's layouts", black(next));
        next.fill();
        frame(next);
        idle(SettleCover.MOVED_HOLD_MS - 100);
        frame(next);
        assertTrue("the hold didn't start over at the second move", black(next));
        idle(150);
        frame(next);
        assertFalse("the next video stayed black once it stopped moving", black(next));
    }

    /**
     * On the S22 the full-screen video's top sits a pixel below the window's (2026-11-22, 1185x2105
     * at -52,1), so where it lands can't tell that it's in place, and every saved video waited out
     * the long hold. It shows a short hold after Facebook's last move instead.
     */
    @Test
    public void aVideoFacebookMovedShowsShortlyAfterItsLastMove() {
        chainTappedCardOne();
        ReelsViewer next = openViewer(SAVED, 1, true);
        frame(next);
        idle(250);
        next.cropToFill();
        frame(next);
        idle(SettleCover.MOVED_HOLD_MS + 50);
        frame(next);
        assertFalse("the next video waited out the long hold after Facebook moved it", black(next));
    }

    @Test
    public void aNextVideoFacebookLeavesWhereItIsShowsAfterAShortHold() {
        chainTappedCardOne();
        ReelsViewer next = openViewer(SAVED, 1, true);
        frame(next);
        assertTrue(black(next));
        idle(SettleCover.HOLD_MS + 50);
        assertFalse("a video Facebook never moved stayed black", black(next));
    }

    @Test
    public void aNextScreenWithNoVideoShowsAfterTheLongestWait() {
        chainTappedCardOne();
        ReelsViewer next = openViewer(SAVED);
        frame(next);
        assertTrue(black(next));
        idle(SettleCover.MAX_MS - 300);
        frame(next);
        assertTrue(black(next));
        idle(300);
        assertFalse("a screen with no video stayed black", black(next));
    }
}
