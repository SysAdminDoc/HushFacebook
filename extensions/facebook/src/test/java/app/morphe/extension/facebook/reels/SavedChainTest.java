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
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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

        @Override
        protected void onCreate(Bundle state) {
            super.onCreate(state);
            reads = getIntent().getIntExtra("reads", 1);
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            FrameLayout video = new FrameLayout(this);
            video.setOnTouchListener((view, event) -> facebook.add(event.getActionMasked()));
            setContentView(video);
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
        pageController.pause();
        SavedChain.viewerOpening(origin);
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), ReelsViewer.class).putExtra("reads", reads);
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

    /** The black cover over the Saved page's content, or null when it's off. */
    private View coverOnSaved() {
        ViewGroup content = page.findViewById(android.R.id.content);
        return content.getChildCount() > 1 ? content.getChildAt(content.getChildCount() - 1) : null;
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
        View cover = coverOnSaved();
        assertNotNull("Saved showed between one saved video and the next", cover);
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, cover.getImportantForAccessibility());

        backToSaved();
        idle(SavedChain.SETTLE_MS + SavedChain.PRESS_MS + 50);
        assertEquals("the tap under the cover didn't reach card 2", TAP, touchesOf(2));
        assertTrue("card 0 was tapped", touchesOf(0).isEmpty());
        assertTrue(statusLine(), statusLine().contains(SavedChain.NEXT + " 1"));
        assertNotNull("the cover came off before the next video opened", coverOnSaved());

        openViewer(SAVED);
        assertEquals("the next chain's card", "card 2", SavedChain.chainForTests().card.label);
        assertNotNull("the cover came off as the next video opened, before it drew", coverOnSaved());
        idle(SavedChain.UNCOVER_MS + 50);
        assertNull("the cover stayed on Saved", coverOnSaved());
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
        assertNull(coverOnSaved());
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
        assertNull(coverOnSaved());
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
        assertNotNull(coverOnSaved());

        backToSaved();
        idle(SavedChain.SETTLE_MS + SavedChain.SCROLL_SETTLE_MS + SavedChain.PRESS_MS + 50);
        assertNull("the chain went on past the last card", SavedChain.chainForTests());
        assertNull("Saved stayed covered with nothing to play", coverOnSaved());
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
}
