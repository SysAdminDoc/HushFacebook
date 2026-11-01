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
import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;

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
 * Long press opens comments on a feed post (#179): a press held on a post's photo or name line
 * clicks the second button of its footer and cancels the rest of the gesture, the photo's own long
 * press included; the text, a profile picture, a grid tile, a carousel, a full-size page and a post
 * with no footer are left to Facebook, as is everything with the switch off or Hushfacebook paused.
 * The rows copy the S22's Home feed on 583 as its real view tree has it (dumpsys, not uiautomator):
 * the photo and the text are plain hosts, the text's host hangs past the margins of its box, and
 * the footer is one clickable bar holding its buttons. The comment sheet a hold opens keeps its
 * keyboard down; every other sheet keeps Facebook's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w1080dp-h2400dp-mdpi")
public class PostLongPressTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final List<String> clicked = new ArrayList<>();
    /** What the window's own handling of a touch ran on the photo. */
    private final List<String> facebook = new ArrayList<>();
    private ActivityController<Activity> window;
    private Activity activity;
    private FrameLayout root;
    private RecyclerView list;
    private FrameLayout row;
    private View avatar;
    private View text;
    private View name;
    private View photo;
    private FrameLayout footer;
    private View comment;
    private View addFriend;

    @Before
    public void setUp() {
        PostLongPress.reset();
        Settings.LONG_PRESS_OPENS_COMMENTS.save(true);
        window = Robolectric.buildActivity(Activity.class).setup();
        activity = window.get();
        root = new FrameLayout(activity);
        list = new RecyclerView(activity, true);
        place(root, list, 0, 100, 1080, 2100);
        row = post(list, 0);
        activity.setContentView(root);
        layOut();
        window.windowFocusChanged(true);
        waitFor(0);
    }

    @After
    public void restore() {
        PostLongPress.reset();
        PauseForTests.resume();
        Settings.LONG_PRESS_OPENS_COMMENTS.resetToDefault();
        HookStatus.clear();
    }

    /** A feed post at [top] in [into]: name line, text, photo, footer, then a People You May Know carousel. */
    private FrameLayout post(ViewGroup into, int top) {
        FrameLayout post = new FrameLayout(activity);
        place(into, post, 0, top, 1080, top + 1000);
        FrameLayout header = new FrameLayout(activity);
        header.setClickable(true);
        place(post, header, 0, 0, 1080, 181);
        avatar = new ImageView(activity);
        avatar.setOnClickListener(v -> clicked.add("avatar"));
        avatar.setLongClickable(true);
        place(header, avatar, 34, 34, 147, 147);
        name = new View(activity);
        place(header, name, 170, 35, 807, 84);
        place(header, button("header more"), 830, 0, 954, 118);
        place(header, button("header close"), 962, 0, 1080, 118);
        // The text copies itself on a long press. Its host is as wide as the row but hangs past the
        // margins of its box, so only 1012 px of it shows.
        FrameLayout textBox = new FrameLayout(activity);
        place(post, textBox, 34, 181, 1046, 304);
        text = button("text");
        text.setLongClickable(true);
        place(textBox, text, -34, -10, 1046, 113);
        // On the S22 the photo has a tap and a long press of its own, and the long press shows nothing.
        // Litho draws it as a plain host, not an ImageView.
        photo = new FrameLayout(activity);
        photo.setOnClickListener(v -> facebook.add("photo tap"));
        photo.setOnLongClickListener(v -> facebook.add("photo long"));
        place(post, photo, 0, 304, 1080, 688);
        footer = new FrameLayout(activity);
        place(post, footer, 34, 688, 1046, 812);
        FrameLayout bar = new FrameLayout(activity);
        bar.setClickable(true);
        place(footer, bar, -34, 0, 1046, 124);
        View like = button("like");
        like.setLongClickable(true);
        place(bar, like, 0, 0, 162, 124);
        comment = button("comment");
        place(bar, comment, 162, 0, 324, 124);
        place(bar, button("share"), 324, 0, 448, 124);
        place(bar, button("react"), 989, 0, 1046, 124);
        RecyclerView carousel = new RecyclerView(activity, false);
        place(post, carousel, 0, 812, 1080, 1000);
        addFriend = button("add friend");
        place(carousel, addFriend, 0, 0, 300, 120);
        place(carousel, button("add friend 2"), 300, 0, 600, 120);
        place(carousel, button("add friend 3"), 600, 0, 900, 120);
        return post;
    }

    private View button(String label) {
        View view = new View(activity);
        view.setOnClickListener(v -> clicked.add(label));
        return view;
    }

    private static void place(ViewGroup parent, View child, int left, int top, int right, int bottom) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(right - left, bottom - top, Gravity.LEFT | Gravity.TOP);
        params.leftMargin = left;
        params.topMargin = top;
        parent.addView(child, params);
    }

    /**
     * Lets the window lay itself out at the S22's size. A layout done by hand here was undone by the
     * window's own pass the first time a test let time run, which moved every view off the press.
     */
    private void layOut() {
        activity.getWindow().getDecorView().requestLayout();
        waitFor(100);
    }

    private static int[] middleOf(View view) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return new int[] {at[0] + view.getWidth() / 2, at[1] + view.getHeight() / 2};
    }

    private MotionEvent event(int action, int[] at) {
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, action, at[0], at[1], 0);
    }

    /** Sends [event] the way the hook does and hands it back. */
    private MotionEvent send(MotionEvent event) {
        PostLongPress.touch(activity, event);
        return event;
    }

    /** Sends [event] the way the hook does, then on through the window, and hands it back. */
    private MotionEvent deliver(MotionEvent event) {
        PostLongPress.touch(activity, event);
        activity.getWindow().getDecorView().dispatchTouchEvent(event);
        return event;
    }

    private static void waitFor(long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    private static void holdLongEnough() {
        waitFor(ViewConfiguration.getLongPressTimeout() + 50L);
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.REEL_GESTURES + ":")) return line;
        }
        return "";
    }

    @Test
    public void aPressHeldOnAPostsPhotoClicksItsCommentButtonAndCancelsTheLift() {
        int[] at = middleOf(photo);
        assertSame("the window finds the row", row, PostLongPress.rowAt(activity.getWindow().getDecorView(), at[0], at[1]));
        send(event(MotionEvent.ACTION_DOWN, at));
        holdLongEnough();
        assertEquals(List.of("comment"), clicked);
        assertEquals("a move after the click went on as a cancel", MotionEvent.ACTION_CANCEL,
                send(event(MotionEvent.ACTION_MOVE, at)).getActionMasked());
        assertEquals("the lift went on as a cancel", MotionEvent.ACTION_CANCEL,
                send(event(MotionEvent.ACTION_UP, at)).getActionMasked());
        assertTrue(statusLine(), statusLine().contains(PostLongPress.OPENED + " 1"));
        assertTrue(statusLine(), statusLine().contains(PostLongPress.HELD + " 1"));

        // The next gesture is Facebook's again.
        assertEquals(MotionEvent.ACTION_DOWN, send(event(MotionEvent.ACTION_DOWN, at)).getActionMasked());
        assertEquals(MotionEvent.ACTION_UP, send(event(MotionEvent.ACTION_UP, at)).getActionMasked());
        holdLongEnough();
        assertEquals("a tap opens nothing of ours", 1, clicked.size());
    }

    @Test
    public void theSheetAHoldOpensKeepsTheKeyboardDownOnceAndOnlyRightAfterTheClick() {
        assertTrue("with no hold the sheet keeps Facebook's keyboard", PostLongPress.keyboardOnFirstLoad(true));
        send(event(MotionEvent.ACTION_DOWN, middleOf(photo)));
        holdLongEnough();
        send(event(MotionEvent.ACTION_UP, middleOf(photo)));
        assertEquals(List.of("comment"), clicked);
        assertFalse("a sheet that never asked stays without one", PostLongPress.keyboardOnFirstLoad(false));
        assertFalse("the hold's sheet opens to read", PostLongPress.keyboardOnFirstLoad(true));
        assertTrue("the next sheet is Facebook's again", PostLongPress.keyboardOnFirstLoad(true));
        assertTrue(statusLine(), statusLine().contains(PostLongPress.KEYBOARD_DOWN + " 1"));

        // A sheet made long after the click isn't the one the hold opened.
        send(event(MotionEvent.ACTION_DOWN, middleOf(photo)));
        holdLongEnough();
        send(event(MotionEvent.ACTION_UP, middleOf(photo)));
        assertEquals(2, clicked.size());
        waitFor(2100L);
        assertTrue("a late sheet keeps the keyboard", PostLongPress.keyboardOnFirstLoad(true));
        assertTrue("and the wait is spent", PostLongPress.keyboardOnFirstLoad(true));
        assertTrue(statusLine(), statusLine().contains(PostLongPress.KEYBOARD_DOWN + " 1"));
    }

    @Test
    public void theNameLineOpensCommentsButTheTextAndProfilePictureKeepFacebooksLongPress() {
        assertSame(row, PostLongPress.rowAt(root, middleOf(name)[0], middleOf(name)[1]));
        assertNull("text", PostLongPress.rowAt(root, middleOf(text)[0], middleOf(text)[1]));
        assertNull("profile picture", PostLongPress.rowAt(root, middleOf(avatar)[0], middleOf(avatar)[1]));
        send(event(MotionEvent.ACTION_DOWN, middleOf(text)));
        holdLongEnough();
        assertTrue(clicked.isEmpty());
    }

    @Test
    public void aCarouselInsideAPostIsNeitherPressedNorSearched() {
        assertNull(PostLongPress.rowAt(root, middleOf(addFriend)[0], middleOf(addFriend)[1]));
        send(event(MotionEvent.ACTION_DOWN, middleOf(addFriend)));
        holdLongEnough();
        assertTrue(clicked.isEmpty());
        assertSame("the footer, not the carousel's three buttons below it", comment, PostLongPress.commentButton(row));
    }

    @Test
    public void rightToLeftTheCommentButtonIsSecondFromTheRight() {
        list.removeAllViews();
        FrameLayout post = new FrameLayout(activity);
        post.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        place(list, post, 0, 0, 1080, 400);
        FrameLayout footer = new FrameLayout(activity);
        place(post, footer, 0, 200, 1080, 324);
        place(footer, button("react"), 34, 0, 91, 124);
        place(footer, button("share"), 632, 0, 756, 124);
        View rtlComment = button("comment");
        place(footer, rtlComment, 756, 0, 918, 124);
        place(footer, button("like"), 918, 0, 1046, 124);
        layOut();
        assertSame(rtlComment, PostLongPress.commentButton(post));
    }

    @Test
    public void aPostWithoutAFooterLineIsLeftToFacebook() {
        list.removeAllViews();
        FrameLayout post = new FrameLayout(activity);
        place(list, post, 0, 0, 1080, 600);
        View bare = new View(activity);
        place(post, bare, 0, 0, 1080, 400);
        // Three buttons on one line that don't touch aren't a footer.
        place(post, button("one"), 0, 400, 100, 500);
        place(post, button("two"), 300, 400, 400, 500);
        place(post, button("three"), 600, 400, 700, 500);
        layOut();
        assertNull(PostLongPress.commentButton(post));
        int[] at = middleOf(bare);
        send(event(MotionEvent.ACTION_DOWN, at));
        holdLongEnough();
        assertTrue(clicked.isEmpty());
        assertEquals("the lift is Facebook's", MotionEvent.ACTION_UP, send(event(MotionEvent.ACTION_UP, at)).getActionMasked());
        assertTrue(statusLine(), statusLine().contains(PostLongPress.NO_BUTTON + " 1"));
    }

    /**
     * Litho mounts only what's on screen, so a post running past the bottom of the feed has no
     * footer yet: the feed is scrolled up by what's under, and the button is clicked once it shows.
     */
    @Test
    public void aPostRunningPastTheFeedIsScrolledUpToItsFooter() {
        List<Integer> scrolls = new ArrayList<>();
        RecyclerView feed = new RecyclerView(activity, true) {
            @Override
            public void scrollBy(int x, int y) {
                super.scrollBy(x, y);
                scrolls.add(y);
                // Litho mounts the footer on a later frame.
                this.post(() -> footer.setVisibility(View.VISIBLE));
            }
        };
        root.removeAllViews();
        place(root, feed, 0, 100, 1080, 2100);
        FrameLayout tall = post(feed, 1500);
        footer.setVisibility(View.INVISIBLE);
        layOut();
        int[] at = middleOf(name);
        assertSame(tall, PostLongPress.rowAt(root, at[0], at[1]));
        send(event(MotionEvent.ACTION_DOWN, at));
        holdLongEnough();
        waitFor(200);
        assertEquals("the 500 px under the feed", List.of(500), scrolls);
        assertEquals(List.of("comment"), clicked);
        assertEquals("the lift went on as a cancel", MotionEvent.ACTION_CANCEL,
                send(event(MotionEvent.ACTION_UP, at)).getActionMasked());
        String status = statusLine();
        assertTrue(status, status.contains(PostLongPress.SCROLLED + " 1"));
        assertTrue(status, status.contains(PostLongPress.OPENED + " 1"));
        assertFalse(status, status.contains(PostLongPress.NO_BUTTON));
    }

    /** At the end of the feed there's nothing to scroll, so a post whose footer isn't mounted is left alone. */
    @Test
    public void aPostAtTheEndOfTheFeedIsNotScrolled() {
        List<Integer> scrolls = new ArrayList<>();
        RecyclerView feed = new RecyclerView(activity, true) {
            @Override
            public boolean canScrollVertically(int direction) {
                return direction < 0;
            }

            @Override
            public void scrollBy(int x, int y) {
                scrolls.add(y);
            }
        };
        root.removeAllViews();
        place(root, feed, 0, 100, 1080, 2100);
        post(feed, 1500);
        footer.setVisibility(View.INVISIBLE);
        layOut();
        int[] at = middleOf(name);
        send(event(MotionEvent.ACTION_DOWN, at));
        holdLongEnough();
        waitFor(200);
        assertTrue(scrolls.toString(), scrolls.isEmpty());
        assertTrue(clicked.toString(), clicked.isEmpty());
        assertEquals("the lift is Facebook's", MotionEvent.ACTION_UP, send(event(MotionEvent.ACTION_UP, at)).getActionMasked());
        assertTrue(statusLine(), statusLine().contains(PostLongPress.NO_BUTTON + " 1"));
    }

    @Test
    public void aFullSizePageLikeAReelsAndASidewaysListAreNotFeedRows() {
        list.removeAllViews();
        View page = new View(activity);
        place(list, page, 0, 0, 1080, 2000);
        layOut();
        assertNull(PostLongPress.rowAt(root, middleOf(page)[0], middleOf(page)[1]));

        root.removeAllViews();
        RecyclerView sideways = new RecyclerView(activity, false);
        place(root, sideways, 0, 100, 1080, 2100);
        FrameLayout post = post(sideways, 0);
        layOut();
        assertNull(PostLongPress.rowAt(root, middleOf(photo)[0], middleOf(photo)[1]));
        assertSame(comment, PostLongPress.commentButton(post));
    }

    @Test
    public void movingLiftingOrASecondFingerBeforeTheTimeIsUpLeavesThePressToFacebook() {
        int[] at = middleOf(photo);
        send(event(MotionEvent.ACTION_DOWN, at));
        send(event(MotionEvent.ACTION_MOVE, new int[] {at[0], at[1] + 200}));
        holdLongEnough();
        send(event(MotionEvent.ACTION_DOWN, at));
        waitFor(100);
        send(event(MotionEvent.ACTION_UP, at));
        holdLongEnough();
        send(event(MotionEvent.ACTION_DOWN, at));
        send(event(MotionEvent.ACTION_POINTER_DOWN, at));
        holdLongEnough();
        assertTrue(clicked.isEmpty());

        // A small wobble within the touch slop still counts as holding.
        send(event(MotionEvent.ACTION_DOWN, at));
        send(event(MotionEvent.ACTION_MOVE, new int[] {at[0] + 1, at[1] + 1}));
        holdLongEnough();
        assertEquals(List.of("comment"), clicked);
    }

    /** A hold on a footer button, Share here, is that button's: the photo and name line are above it. */
    @Test
    public void aHoldOnTheFooterItselfIsLeftToFacebook() {
        View share = PostLongPress.hit(root, middleOf(comment)[0] + 162, middleOf(comment)[1]);
        assertSame("the footer's row is still found", row, PostLongPress.rowAt(root, middleOf(comment)[0] + 162, middleOf(comment)[1]));
        int[] at = middleOf(share);
        send(event(MotionEvent.ACTION_DOWN, at));
        holdLongEnough();
        assertTrue(clicked.toString(), clicked.isEmpty());
        assertEquals("the lift is Facebook's", MotionEvent.ACTION_UP, send(event(MotionEvent.ACTION_UP, at)).getActionMasked());
        assertFalse(statusLine(), statusLine().contains(PostLongPress.HELD));
    }

    /** The photo's own long press, timed by the window from the same touch, never runs after the click. */
    @Test
    public void thePhotosOwnLongPressGivesWayToComments() {
        int[] at = middleOf(photo);
        Settings.LONG_PRESS_OPENS_COMMENTS.save(false);
        deliver(event(MotionEvent.ACTION_DOWN, at));
        holdLongEnough();
        deliver(event(MotionEvent.ACTION_UP, at));
        assertEquals("with the switch off the window runs the photo's long press", List.of("photo long"), facebook);
        facebook.clear();

        Settings.LONG_PRESS_OPENS_COMMENTS.save(true);
        deliver(event(MotionEvent.ACTION_DOWN, at));
        holdLongEnough();
        assertEquals(List.of("comment"), clicked);
        assertEquals("the lift went on as a cancel", MotionEvent.ACTION_CANCEL,
                deliver(event(MotionEvent.ACTION_UP, at)).getActionMasked());
        assertTrue("the photo ran nothing: " + facebook, facebook.isEmpty());
    }

    /** A picture narrower than the row, like a grid tile, or one inside another long press stays Facebook's. */
    @Test
    public void aNarrowPictureOrOneInsideAnotherLongPressIsFacebooks() {
        ViewGroup post = (ViewGroup) photo.getParent();
        post.removeView(photo);
        ImageView tile = new ImageView(activity);
        tile.setLongClickable(true);
        place(post, tile, 0, 304, 540, 688);
        layOut();
        assertNull("grid tile", PostLongPress.rowAt(root, middleOf(tile)[0], middleOf(tile)[1]));
        assertEquals(PostLongPress.NARROW_LONG_PRESS, PostLongPress.passedOver);
        send(event(MotionEvent.ACTION_DOWN, middleOf(tile)));
        holdLongEnough();
        assertTrue(clicked.toString(), clicked.isEmpty());

        post.removeView(tile);
        FrameLayout wrapper = new FrameLayout(activity);
        wrapper.setLongClickable(true);
        place(post, wrapper, 0, 304, 1080, 688);
        ImageView wide = new ImageView(activity);
        wide.setLongClickable(true);
        place(wrapper, wide, 0, 0, 1080, 384);
        layOut();
        assertNull("inside another long press", PostLongPress.rowAt(root, middleOf(wide)[0], middleOf(wide)[1]));
        assertEquals(PostLongPress.OWN_LONG_PRESS, PostLongPress.passedOver);
        wrapper.setLongClickable(false);
        assertSame("the same picture on its own is the photo", row, PostLongPress.rowAt(root, middleOf(wide)[0], middleOf(wide)[1]));
    }

    /** Stands in for Litho's ComponentHost, which lists the text drawn in it. */
    public static final class LithoHost extends FrameLayout {
        final List<Object> drawn = new ArrayList<>();

        public LithoHost(Context context) {
            super(context);
        }

        public List<Object> getTextContent() {
            return drawn;
        }
    }

    /**
     * A shared post draws the post it shares in a box inside the side margins, so its photo shows
     * no wider than the text does. On the S22 that photo's host was 1080 px wide and showed 1012 px.
     * A Litho host drawing no text is the photo there; one drawing text, or holding a host that
     * does, is text wherever it sits, a text post on a colored background included; a narrow one
     * is still a tile.
     */
    @Test
    public void aSharedPostsPhotoInsideItsBoxOpensCommentsAndTextStillCopies() {
        ViewGroup post = (ViewGroup) photo.getParent();
        post.removeView(photo);
        FrameLayout box = new FrameLayout(activity);
        place(post, box, 34, 304, 1046, 688);
        LithoHost inset = new LithoHost(activity);
        inset.setOnLongClickListener(v -> facebook.add("photo long"));
        place(box, inset, -34, 0, 1046, 384);
        layOut();
        int[] at = middleOf(box);
        assertSame("the shared post's photo", row, PostLongPress.rowAt(root, at[0], at[1]));
        deliver(event(MotionEvent.ACTION_DOWN, at));
        holdLongEnough();
        assertEquals(List.of("comment"), clicked);
        deliver(event(MotionEvent.ACTION_UP, at));
        assertTrue("the photo ran nothing: " + facebook, facebook.isEmpty());

        inset.drawn.add("text");
        assertNull("a host drawing text", PostLongPress.rowAt(root, at[0], at[1]));
        assertEquals(PostLongPress.ON_TEXT, PostLongPress.passedOver);
        inset.drawn.clear();
        LithoHost line = new LithoHost(activity);
        line.drawn.add("text");
        place(inset, line, 34, 0, 1046, 100);
        layOut();
        assertNull("a host holding one that draws text", PostLongPress.rowAt(root, at[0], at[1]));
        assertEquals(PostLongPress.ON_TEXT, PostLongPress.passedOver);

        box.removeView(inset);
        LithoHost tile = new LithoHost(activity);
        tile.setLongClickable(true);
        place(box, tile, 0, 0, 506, 384);
        layOut();
        assertNull("a tile in the box", PostLongPress.rowAt(root, middleOf(tile)[0], middleOf(tile)[1]));
        assertEquals(PostLongPress.NARROW_LONG_PRESS, PostLongPress.passedOver);

        post.removeView(box);
        LithoHost colored = new LithoHost(activity);
        colored.setLongClickable(true);
        colored.drawn.add("text");
        place(post, colored, 0, 304, 1080, 688);
        layOut();
        assertNull("a text post on a colored background", PostLongPress.rowAt(root, middleOf(colored)[0], middleOf(colored)[1]));
        assertEquals(PostLongPress.ON_TEXT, PostLongPress.passedOver);
    }

    /**
     * On the S22 an empty frame as big as the feed is drawn over it. The window's touches go past a
     * view that takes none, so a hold there is still the post's; a frame that takes touches keeps them.
     */
    @Test
    public void anEmptyFrameDrawnOverTheFeedIsPassedOverLikeTheWindowDoes() {
        FrameLayout cover = new FrameLayout(activity);
        place(root, cover, 0, 100, 1080, 1953);
        layOut();
        int[] at = middleOf(name);
        assertSame("the name line's row under the frame", row, PostLongPress.rowAt(root, at[0], at[1]));
        assertSame("the photo under the frame", photo, PostLongPress.hit(root, middleOf(photo)[0], middleOf(photo)[1]));
        send(event(MotionEvent.ACTION_DOWN, at));
        holdLongEnough();
        assertEquals(List.of("comment"), clicked);
        assertEquals("the lift went on as a cancel", MotionEvent.ACTION_CANCEL,
                send(event(MotionEvent.ACTION_UP, at)).getActionMasked());

        cover.setClickable(true);
        assertNull("a frame that takes touches", PostLongPress.rowAt(root, at[0], at[1]));
        assertEquals(PostLongPress.NO_LIST, PostLongPress.passedOver);
    }

    /** A window that lost focus before the time was up, to a call or a task switch, opens nothing. */
    @Test
    public void aWindowThatLostFocusMidPressOpensNothing() {
        int[] at = middleOf(photo);
        send(event(MotionEvent.ACTION_DOWN, at));
        window.windowFocusChanged(false);
        holdLongEnough();
        assertTrue(clicked.toString(), clicked.isEmpty());
        window.windowFocusChanged(true);
        send(event(MotionEvent.ACTION_DOWN, at));
        holdLongEnough();
        assertEquals("with the focus back the hold works", List.of("comment"), clicked);
    }

    /** Each hold left to Facebook counts why, so a phone's report names the check that passed it over. */
    @Test
    public void eachHoldLeftToFacebookCountsWhy() {
        send(event(MotionEvent.ACTION_DOWN, middleOf(text)));
        holdLongEnough();
        send(event(MotionEvent.ACTION_DOWN, middleOf(addFriend)));
        holdLongEnough();
        send(event(MotionEvent.ACTION_DOWN, new int[] {middleOf(comment)[0] + 162, middleOf(comment)[1]}));
        holdLongEnough();
        send(event(MotionEvent.ACTION_DOWN, middleOf(photo)));
        window.windowFocusChanged(false);
        holdLongEnough();
        window.windowFocusChanged(true);
        assertTrue(clicked.toString(), clicked.isEmpty());
        String status = statusLine();
        assertTrue(status, status.contains(PostLongPress.TIMED + " 4"));
        assertTrue("the text: " + status, status.contains(PostLongPress.NARROW_LONG_PRESS + " 1"));
        assertTrue("a sideways carousel: " + status, status.contains(PostLongPress.STILL_LIST + " 1"));
        assertTrue(status, status.contains(PostLongPress.ON_FOOTER + " 1"));
        assertTrue(status, status.contains(PostLongPress.LOST_FOCUS + " 1"));
        assertFalse(status, status.contains(PostLongPress.HELD));

        list.removeAllViews();
        View page = new View(activity);
        place(list, page, 0, 0, 1080, 2000);
        layOut();
        assertNull(PostLongPress.rowAt(root, middleOf(page)[0], middleOf(page)[1]));
        assertEquals(PostLongPress.FULL_PAGE, PostLongPress.passedOver);
    }

    /**
     * The Reels tab leaves a strip of the next reel under the one playing, with the bar drawn over
     * it. A hold there used to read as a tall post running off the screen and scrolled the next
     * reel in.
     */
    @Test
    public void aHoldOnAReelsBarOrTheNextReelsStripStaysOnTheReel() {
        list.removeAllViews();
        View playing = new View(activity);
        View next = new View(activity);
        place(list, playing, 0, 0, 1080, 1940);
        place(list, next, 0, 1940, 1080, 3880);
        SeekBar bar = new SeekBar(activity);
        place(root, bar, 0, 2050, 1080, 2090);
        layOut();

        int[] onBar = middleOf(bar);
        assertNull(PostLongPress.rowAt(root, onBar[0], onBar[1]));
        assertEquals(PostLongPress.ON_BAR, PostLongPress.passedOver);
        send(event(MotionEvent.ACTION_DOWN, onBar));
        holdLongEnough();
        String status = statusLine();
        assertTrue(status, status.contains(PostLongPress.ON_BAR + " 1"));
        assertFalse(status, status.contains(PostLongPress.SCROLLED));
        send(event(MotionEvent.ACTION_UP, onBar));

        bar.setVisibility(View.GONE);
        assertNull("the strip with no bar over it", PostLongPress.rowAt(root, onBar[0], onBar[1]));
        assertEquals(PostLongPress.FULL_PAGE, PostLongPress.passedOver);
        int[] onReel = middleOf(playing);
        assertNull("the reel itself", PostLongPress.rowAt(root, onReel[0], onReel[1]));
        assertEquals(PostLongPress.FULL_PAGE, PostLongPress.passedOver);
    }

    /** Posts running off the bottom keep their hold, however tall or alike they are. */
    @Test
    public void aTallPostIsNotAPage() {
        list.removeAllViews();
        View alike = new View(activity);
        View peeking = new View(activity);
        place(list, alike, 0, 0, 1080, 1700);
        place(list, peeking, 0, 1700, 1080, 3400);
        layOut();
        int[] peekingAt = new int[2];
        peeking.getLocationOnScreen(peekingAt);
        assertSame("same size, well short of the list", peeking, PostLongPress.rowAt(root, 300, peekingAt[1] + 50));

        list.removeAllViews();
        View tall = new View(activity);
        View shorter = new View(activity);
        place(list, tall, 0, 0, 1080, 1940);
        place(list, shorter, 0, 1940, 1080, 2600);
        layOut();
        int[] onTall = middleOf(tall);
        assertSame("nearly as tall as the list, with nothing its size", tall, PostLongPress.rowAt(root, onTall[0], onTall[1]));
    }

    /** A lift that comes after the long-press time while the timer still waits is counted, not opened. */
    @Test
    public void aLiftAfterTheTimeBeforeTheTimerRanIsCounted() {
        int[] at = middleOf(photo);
        long down = SystemClock.uptimeMillis();
        send(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, at[0], at[1], 0));
        long late = down + ViewConfiguration.getLongPressTimeout() + 10L;
        assertEquals("the lift is Facebook's", MotionEvent.ACTION_UP,
                send(MotionEvent.obtain(down, late, MotionEvent.ACTION_UP, at[0], at[1], 0)).getActionMasked());
        holdLongEnough();
        assertTrue(clicked.toString(), clicked.isEmpty());
        assertTrue(statusLine(), statusLine().contains(PostLongPress.LIFTED_LATE + " 1"));

        send(event(MotionEvent.ACTION_DOWN, at));
        waitFor(100);
        send(event(MotionEvent.ACTION_UP, at));
        assertTrue("a quick tap isn't late: " + statusLine(), statusLine().contains(PostLongPress.LIFTED_LATE + " 1"));
    }

    @Test
    public void offOrPausedEveryTouchIsFacebooks() {
        int[] at = middleOf(photo);
        Settings.LONG_PRESS_OPENS_COMMENTS.save(false);
        send(event(MotionEvent.ACTION_DOWN, at));
        holdLongEnough();
        assertEquals(MotionEvent.ACTION_UP, send(event(MotionEvent.ACTION_UP, at)).getActionMasked());
        Settings.LONG_PRESS_OPENS_COMMENTS.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        send(event(MotionEvent.ACTION_DOWN, at));
        holdLongEnough();
        assertTrue(clicked.isEmpty());
        assertFalse(statusLine(), statusLine().contains(PostLongPress.HELD));
    }
}
