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

import app.morphe.extension.facebook.misc.AppLockForTests;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Play the feed's Reels row in order (#599): a reel opened from a tile in a Reels row in the feed,
 * once it ends or is swiped up, closes and the next tile in that row is tapped, scrolled into view
 * sideways first when it's past the row's edge, and a swipe down taps the one before. The last tile
 * ends the chain on the feed. A reel posted in the feed on its own, the switch off, and Saved's own
 * switch each leave Facebook's viewer alone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w360dp-h800dp")
public class FeedReelsRowTest {
    private static final String FEED = "newsfeed::fb_shorts_viewer";
    private static final String SAVED = "saved::fb_shorts_viewer";
    private static final int TILE_W = 150;
    private static final int TILE_H = 200;
    private static final int POST_H = 150;
    private static final int FEED_H = 600;
    private static final List<Integer> TAP = List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP);

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final SavedChain.Screens screens = new SavedChain.Screens();
    private ActivityController<FeedPage> feedController;
    private FeedPage feed;
    private final List<ActivityController<SavedChainTest.ReelsViewer>> viewers = new ArrayList<>();

    /** The feed: a post, a Reels row of tiles going sideways, then more posts, its touches read first as the patch does. */
    public static final class FeedPage extends Activity {
        FeedRecyclerView list;
        RowRecyclerView row;
        View post;

        @Override
        protected void onCreate(Bundle state) {
            super.onCreate(state);
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            FrameLayout root = new FrameLayout(this);
            list = new FeedRecyclerView(this);
            post = new FrameLayout(this);
            post.setContentDescription("a reel posted on its own");
            list.addView(post, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, POST_H));
            FrameLayout holder = new FrameLayout(this);
            row = new RowRecyclerView(this, getIntent().getIntExtra("tiles", 6));
            holder.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, TILE_H));
            list.addView(holder, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, TILE_H));
            for (int i = 0; i < 4; i++) {
                FrameLayout more = new FrameLayout(this);
                more.setContentDescription("post " + i);
                list.addView(more, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, POST_H));
            }
            root.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, FEED_H));
            setContentView(root);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (SavedChain.touched(this, event)) return true;
            return super.dispatchTouchEvent(event);
        }
    }

    /** The feed's posts, one under the other, scrolling up and down by moving them as a RecyclerView does. */
    static final class FeedRecyclerView extends ViewGroup {
        int offset;

        FeedRecyclerView(Context context) {
            super(context);
        }

        int contentHeight() {
            int height = 0;
            for (int i = 0; i < getChildCount(); i++) height += getChildAt(i).getLayoutParams().height;
            return height;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = getDefaultSize(0, widthSpec);
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(child.getLayoutParams().height, MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(width, getDefaultSize(0, heightSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int top = -offset;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                int height = child.getLayoutParams().height;
                child.layout(0, top, r - l, top + height);
                top += height;
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

    /** A Reels row: tiles side by side that scroll left and right by moving them, keeping every touch they get. */
    static final class RowRecyclerView extends ViewGroup {
        final List<List<Integer>> touches = new ArrayList<>();
        int offset;

        RowRecyclerView(Context context, int tiles) {
            super(context);
            for (int i = 0; i < tiles; i++) {
                FrameLayout tile = new FrameLayout(context);
                tile.addView(new View(context), new FrameLayout.LayoutParams(TILE_W, TILE_H));
                tile.setContentDescription("View reel by author " + i);
                List<Integer> got = new ArrayList<>();
                touches.add(got);
                tile.setOnTouchListener((view, event) -> got.add(event.getActionMasked()));
                addView(tile);
            }
        }

        int contentWidth() {
            return getChildCount() * TILE_W;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            for (int i = 0; i < getChildCount(); i++) {
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(TILE_W, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(TILE_H, MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(getDefaultSize(0, widthSpec), getDefaultSize(0, heightSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            for (int i = 0; i < getChildCount(); i++) {
                int left = i * TILE_W - offset;
                getChildAt(i).layout(left, 0, left + TILE_W, TILE_H);
            }
        }

        @Override
        public boolean canScrollHorizontally(int direction) {
            return direction > 0 ? offset + getWidth() < contentWidth() : offset > 0;
        }

        @Override
        public void scrollBy(int x, int y) {
            int dx = Math.max(-offset, Math.min(x, contentWidth() - getWidth() - offset));
            offset += dx;
            for (int i = 0; i < getChildCount(); i++) getChildAt(i).offsetLeftAndRight(-dx);
        }
    }

    @Before
    public void start() {
        HookStatus.clear();
        SavedChain.resetForTests();
        AppLockForTests.disarm();
        RuntimeEnvironment.getApplication().registerActivityLifecycleCallbacks(screens);
        Settings.FEED_REELS_ROW_IN_ORDER.save(true);
    }

    @After
    public void restore() {
        RuntimeEnvironment.getApplication().unregisterActivityLifecycleCallbacks(screens);
        Settings.FEED_REELS_ROW_IN_ORDER.resetToDefault();
        Settings.SAVED_VIDEOS_IN_ORDER.resetToDefault();
        Settings.SAVED_VIDEOS_STAY_ON_END.resetToDefault();
        SavedChain.resetForTests();
        HookStatus.clear();
    }

    private void openFeed(int tiles) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), FeedPage.class).putExtra("tiles", tiles);
        feedController = Robolectric.buildActivity(FeedPage.class, intent).setup();
        feed = feedController.get();
        idle(0);
    }

    private View tile(int index) {
        return feed.row.getChildAt(index);
    }

    private List<Integer> touchesOf(int index) {
        return feed.row.touches.get(index);
    }

    /** A finger pressing and lifting off [view], [inX] pixels in from its left, down through the feed's window. */
    private void press(View view, float inX) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        float x = at[0] + inX;
        float y = at[1] + 10;
        long down = SystemClock.uptimeMillis();
        MotionEvent press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        feed.dispatchTouchEvent(press);
        press.recycle();
        MotionEvent lift = MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_UP, x, y, 0);
        feed.dispatchTouchEvent(lift);
        lift.recycle();
        for (List<Integer> got : feed.row.touches) got.clear();
    }

    private void press(int tile) {
        press(tile(tile), TILE_W / 2f);
    }

    /** Facebook's Reels viewer opening from [origin] over the feed, in front with nothing over it. */
    private SavedChainTest.ReelsViewer openViewer(String origin) {
        feedController.pause();
        SavedChain.viewerOpening(origin);
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), SavedChainTest.ReelsViewer.class);
        ActivityController<SavedChainTest.ReelsViewer> viewer =
                Robolectric.buildActivity(SavedChainTest.ReelsViewer.class, intent).setup();
        viewer.windowFocusChanged(true);
        viewers.add(viewer);
        idle(0);
        return viewer.get();
    }

    /** A reel opened from [origin] with its player bound, past the guard for an early end. */
    private SavedChainTest.ReelsViewer playing(String origin, Object player) {
        SavedChainTest.ReelsViewer viewer = openViewer(origin);
        SavedChain.bound(player);
        idle(SavedChain.OPEN_GUARD_MS + 100);
        return viewer;
    }

    /** The viewer closing and the feed back in front. */
    private void backToFeed() {
        ActivityController<SavedChainTest.ReelsViewer> viewer = viewers.get(viewers.size() - 1);
        viewer.pause();
        feedController.resume();
        viewer.stop().destroy();
    }

    private static void idle(long millis) {
        shadowOf(getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    /** A finger on [viewer] from [fromY] to [toY] down its middle, in ten moves 16 ms apart. */
    private static void swipe(SavedChainTest.ReelsViewer viewer, float fromY, float toY) {
        float x = 180;
        long down = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, fromY, 0);
        viewer.dispatchTouchEvent(event);
        event.recycle();
        for (int i = 1; i <= 10; i++) {
            event = MotionEvent.obtain(down, down + i * 16, MotionEvent.ACTION_MOVE, x, fromY + (toY - fromY) * i / 10, 0);
            viewer.dispatchTouchEvent(event);
            event.recycle();
        }
        event = MotionEvent.obtain(down, down + 164, MotionEvent.ACTION_UP, x, toY, 0);
        viewer.dispatchTouchEvent(event);
        event.recycle();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.REEL_DECLUTTER + ":")) return line;
        }
        return "";
    }

    private void noTileTapped() {
        for (int i = 0; i < feed.row.getChildCount(); i++) {
            assertTrue("tile " + i + " was tapped", touchesOf(i).isEmpty());
        }
    }

    @Test
    public void aReelFromTheRowThatEndsOpensTheNextTile() {
        assertFalse("the switch starts on", Settings.FEED_REELS_ROW_IN_ORDER.defaultValue);
        assertFalse("Saved's switch is on too", Settings.SAVED_VIDEOS_IN_ORDER.get());
        openFeed(6);
        press(0);
        Object player = new Object();
        SavedChainTest.ReelsViewer viewer = playing(FEED, player);
        SavedChain.Chain chain = SavedChain.chainForTests();
        assertNotNull("a reel opened from the row's first tile started no chain", chain);
        assertTrue("the chain isn't the feed's", chain.feed);
        assertTrue("the tile wasn't taken for a row", chain.card.row);
        assertEquals("the chain's tile", "View reel by author 0", chain.card.label);

        SavedChain.ended(player);
        idle(0);
        assertTrue("the reel ended and the viewer stayed", viewer.isFinishing());
        backToFeed();
        idle(SavedChain.SETTLE_MS + SavedChain.PRESS_MS + 100);
        assertEquals("the next tile wasn't tapped", TAP, touchesOf(1));
        touchesOf(1).clear();
        noTileTapped();
        assertEquals("the feed scrolled up or down", 0, feed.list.offset);
        assertTrue(statusLine(), statusLine().contains(SavedChain.ROW_NEXT + " 1"));
        assertFalse(statusLine(), statusLine().contains(SavedChain.NEXT + " "));
    }

    @Test
    public void theNextTilePastTheRowsEdgeIsScrolledIntoViewSideways() {
        openFeed(6);
        press(1);
        Object player = new Object();
        SavedChainTest.ReelsViewer viewer = playing(FEED, player);
        SavedChain.ended(player);
        idle(0);
        assertTrue("the reel ended and the viewer stayed", viewer.isFinishing());
        backToFeed();
        idle(SavedChain.SETTLE_MS + 50);
        assertTrue("the row wasn't scrolled sideways to the next tile", feed.row.offset > 0);
        assertTrue("the next tile was tapped while it was still past the row's edge", touchesOf(2).isEmpty());
        idle(SavedChain.SCROLL_SETTLE_MS + SavedChain.PRESS_MS + 100);
        assertEquals("the next tile wasn't tapped once in view", TAP, touchesOf(2));
        int[] at = new int[2];
        tile(2).getLocationInWindow(at);
        assertTrue("the next tile is still off the row: " + at[0], at[0] >= 0 && at[0] + TILE_W <= feed.row.getWidth());
        assertEquals("the feed scrolled up or down instead", 0, feed.list.offset);
    }

    @Test
    public void aSwipeUpOpensTheNextTileAndASwipeDownTheOneBefore() {
        openFeed(6);
        press(0);
        SavedChainTest.ReelsViewer viewer = playing(FEED, new Object());
        swipe(viewer, 600, 300);
        idle(SavedChain.SLIDE_MS + 50);
        assertTrue("a swipe up left the viewer", viewer.isFinishing());
        backToFeed();
        idle(SavedChain.SETTLE_MS + SavedChain.PRESS_MS + 100);
        assertEquals("a swipe up didn't tap the next tile", TAP, touchesOf(1));
        touchesOf(1).clear();

        viewer = playing(FEED, new Object());
        SavedChain.Chain chain = SavedChain.chainForTests();
        assertNotNull("the tile the chain tapped started no chain", chain);
        assertEquals("the chain's tile", "View reel by author 1", chain.card.label);
        swipe(viewer, 300, 600);
        idle(SavedChain.SLIDE_MS + 50);
        assertTrue("a swipe down left the viewer", viewer.isFinishing());
        backToFeed();
        idle(SavedChain.SETTLE_MS + SavedChain.PRESS_MS + 100);
        assertEquals("a swipe down didn't tap the tile before", TAP, touchesOf(0));
        assertTrue(statusLine(), statusLine().contains(SavedChain.ROW_PREVIOUS + " 1"));
    }

    @Test
    public void aSwipeDownOnTheRowsFirstTileStays() {
        openFeed(6);
        press(0);
        SavedChainTest.ReelsViewer viewer = playing(FEED, new Object());
        swipe(viewer, 300, 600);
        idle(SavedChain.SLIDE_MS + 50);
        assertFalse("a swipe down on the row's first tile left it", viewer.isFinishing());
    }

    @Test
    public void theLastTileEndsTheChainOnTheFeed() {
        openFeed(3);
        feed.row.scrollBy(90, 0);
        press(2);
        Object player = new Object();
        SavedChainTest.ReelsViewer viewer = playing(FEED, player);
        assertNotNull("the row's last tile started no chain", SavedChain.chainForTests());
        SavedChain.ended(player);
        idle(0);
        assertTrue("the last reel ended and the viewer stayed", viewer.isFinishing());
        backToFeed();
        idle(SavedChain.SETTLE_MS + SavedChain.SCROLL_SETTLE_MS * SavedChain.MAX_SCROLLS + 500);
        noTileTapped();
        assertNull("the chain went on past the last tile", SavedChain.chainForTests());
        assertEquals("the feed scrolled looking for more", 0, feed.list.offset);
        assertFalse("the feed was left covered", SettleCover.blackedOut(feed.getWindow().getDecorView()));
    }

    @Test
    public void aReelPostedOnItsOwnInTheFeedStartsNoChain() {
        openFeed(6);
        press(feed.post, 180);
        Object player = new Object();
        SavedChainTest.ReelsViewer viewer = playing(FEED, player);
        assertNull("a feed post's reel started a chain", SavedChain.chainForTests());
        SavedChain.ended(player);
        idle(SavedChain.SETTLE_MS);
        assertFalse("a feed post's reel was closed as it ended", viewer.isFinishing());
    }

    @Test
    public void withTheSwitchOffTheRowIsLeftToFacebook() {
        Settings.FEED_REELS_ROW_IN_ORDER.save(false);
        Settings.SAVED_VIDEOS_IN_ORDER.save(true);
        openFeed(6);
        press(0);
        Object player = new Object();
        SavedChainTest.ReelsViewer viewer = playing(FEED, player);
        assertNull("Saved's switch alone chained the feed's row", SavedChain.chainForTests());
        SavedChain.ended(player);
        idle(SavedChain.SETTLE_MS);
        assertFalse("the row's reel was closed with its switch off", viewer.isFinishing());
    }

    @Test
    public void theRowsSwitchAloneLeavesSavedToFacebook() {
        openFeed(6);
        press(0);
        playing(SAVED, new Object());
        assertNull("the row's switch alone chained a Saved video", SavedChain.chainForTests());
    }

    @Test
    public void stayingOnASavedVideoAtItsEndLeavesTheRowGoing() {
        Settings.SAVED_VIDEOS_IN_ORDER.save(true);
        Settings.SAVED_VIDEOS_STAY_ON_END.save(true);
        openFeed(6);
        press(0);
        Object player = new Object();
        SavedChainTest.ReelsViewer viewer = playing(FEED, player);
        SavedChain.ended(player);
        idle(0);
        assertTrue("Stay on a saved video when it ends held the row's reel", viewer.isFinishing());
    }
}
