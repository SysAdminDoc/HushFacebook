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
import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.facebook.comments.CommentSheetOptions;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Hold a post to react (#454): a press held on a feed post's photo long-clicks the post's Like, the
 * first button of its footer, as Facebook's own long press on Like does. With Pick each time the rest
 * of the gesture goes to Like in Like's coordinates, screen ones kept, so sliding over the tray and
 * letting go picks; the post itself gets a cancel. With one reaction chosen, the tray Like opens is
 * clicked at that reaction through its accessibility helper once it lists all seven. Like only lets
 * the hold's tray through. The post copies the S22's Home feed: Like, Comment and Share touching in
 * one clickable bar.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w1080dp-h2400dp-mdpi")
public class ReactionHoldTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final List<String> clicked = new ArrayList<>();
    /** What Like got: its long press, and each touch as action, local x and y, then screen x and y. */
    private final List<String> like = new ArrayList<>();
    /** What Like only answered, asked as Like's long press ran. */
    private final List<Boolean> skipped = new ArrayList<>();
    /** The reactions clicked in the tray, by place. */
    private final List<Integer> picked = new ArrayList<>();
    private ActivityController<Activity> window;
    private Activity activity;
    private FrameLayout root;
    private View photo;
    private View likeButton;
    /** How many reactions the tray Like's long press shows lists; 0 shows none. */
    private int trayLists = 7;
    /** The last tray shown. */
    private View tray;

    @Before
    public void setUp() {
        PostLongPress.reset();
        Settings.HOLD_POST_TO_REACT.save(true);
        window = Robolectric.buildActivity(Activity.class).setup();
        activity = window.get();
        root = new FrameLayout(activity);
        RecyclerView list = new RecyclerView(activity, true);
        place(root, list, 0, 100, 1080, 2100);
        post(list);
        activity.setContentView(root);
        layOut();
        window.windowFocusChanged(true);
        waitFor(0);
    }

    @After
    public void restore() {
        PostLongPress.reset();
        Settings.HOLD_POST_TO_REACT.resetToDefault();
        Settings.HOLD_REACTION.resetToDefault();
        Settings.LONG_PRESS_OPENS_COMMENTS.resetToDefault();
        Settings.LIKE_ONLY.resetToDefault();
        HookStatus.clear();
    }

    /** A feed post: name line, photo, then the footer bar with Like, Comment and Share. */
    private void post(ViewGroup into) {
        FrameLayout post = new FrameLayout(activity);
        place(into, post, 0, 0, 1080, 1000);
        FrameLayout header = new FrameLayout(activity);
        header.setClickable(true);
        place(post, header, 0, 0, 1080, 181);
        photo = new FrameLayout(activity);
        photo.setOnClickListener(v -> clicked.add("photo tap"));
        photo.setOnLongClickListener(v -> clicked.add("photo long"));
        place(post, photo, 0, 181, 1080, 688);
        FrameLayout bar = new FrameLayout(activity);
        bar.setClickable(true);
        place(post, bar, 0, 688, 1080, 812);
        likeButton = button("like");
        likeButton.setOnLongClickListener(v -> {
            like.add("long");
            skipped.add(CommentSheetOptions.skipReactionPicker());
            if (trayLists > 0) showTray();
            return true;
        });
        likeButton.setOnTouchListener((v, event) -> {
            like.add(MotionEvent.actionToString(event.getActionMasked()) + " " + Math.round(event.getX()) + ","
                    + Math.round(event.getY()) + " " + Math.round(event.getRawX()) + "," + Math.round(event.getRawY()));
            return true;
        });
        place(bar, likeButton, 0, 0, 162, 124);
        place(bar, button("comment"), 162, 0, 324, 124);
        place(bar, button("share"), 324, 0, 448, 124);
    }

    /** What Facebook does on Like's long press: makes the tray, which the patch hands over, and shows it. */
    private void showTray() {
        tray = new Tray(activity, trayLists, picked);
        ReactionHold.dockMade(tray);
        place(root, tray, 100, 400, 1000, 560);
        layOut();
    }

    /** The reaction tray, listing [count] reactions to a screen reader and noting which one it clicks. */
    private static final class Tray extends View {
        private final int count;
        private final List<Integer> picked;

        Tray(Context context, int count, List<Integer> picked) {
            super(context);
            this.count = count;
            this.picked = picked;
        }

        @Override
        public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return new AccessibilityNodeProvider() {
                @Override
                public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
                    AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(Tray.this);
                    if (id == View.NO_ID) {
                        for (int i = 0; i < count; i++) info.addChild(Tray.this, i);
                    }
                    return info;
                }

                @Override
                public boolean performAction(int id, int action, Bundle arguments) {
                    if (action != AccessibilityNodeInfo.ACTION_CLICK || id < 0 || id >= count) return false;
                    picked.add(id);
                    return true;
                }
            };
        }
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

    private void layOut() {
        activity.getWindow().getDecorView().requestLayout();
        waitFor(100);
    }

    private static int[] middleOf(View view) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return new int[] {at[0] + view.getWidth() / 2, at[1] + view.getHeight() / 2};
    }

    private static MotionEvent event(int action, int x, int y) {
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, action, x, y, 0);
    }

    private MotionEvent send(MotionEvent event) {
        PostLongPress.touch(activity, event);
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
    public void aHoldOnThePhotoOpensLikesTrayAndHandsLikeTheRestOfTheGesture() {
        int[] at = middleOf(photo);
        send(event(MotionEvent.ACTION_DOWN, at[0], at[1]));
        holdLongEnough();
        assertEquals(List.of("long"), like);
        assertTrue("no comment, no photo: " + clicked, clicked.isEmpty());
        assertEquals("the footer's first button is Like", likeButton, PostLongPress.likeButton((View) photo.getParent()));

        int[] likeAt = new int[2];
        likeButton.getLocationInWindow(likeAt);
        int x = at[0] + 300;
        int y = at[1] - 200;
        assertEquals("the post gets a cancel", MotionEvent.ACTION_CANCEL,
                send(event(MotionEvent.ACTION_MOVE, x, y)).getActionMasked());
        assertEquals("the lift goes on as a cancel too", MotionEvent.ACTION_CANCEL,
                send(event(MotionEvent.ACTION_UP, x, y)).getActionMasked());
        assertEquals(List.of("long",
                "ACTION_MOVE " + (x - likeAt[0]) + "," + (y - likeAt[1]) + " " + x + "," + y,
                "ACTION_UP " + (x - likeAt[0]) + "," + (y - likeAt[1]) + " " + x + "," + y), like);
        assertTrue("Pick each time clicks nothing in the tray: " + picked, picked.isEmpty());
        assertTrue(statusLine(), statusLine().contains(ReactionHold.OPENED + " 1"));
        assertTrue(statusLine(), statusLine().contains(PostLongPress.HELD + " 1"));

        // The next gesture is Facebook's again, and Like gets none of it.
        assertEquals(MotionEvent.ACTION_DOWN, send(event(MotionEvent.ACTION_DOWN, at[0], at[1])).getActionMasked());
        assertEquals(MotionEvent.ACTION_MOVE, send(event(MotionEvent.ACTION_MOVE, at[0], at[1])).getActionMasked());
        assertEquals(MotionEvent.ACTION_UP, send(event(MotionEvent.ACTION_UP, at[0], at[1])).getActionMasked());
        holdLongEnough();
        assertEquals(3, like.size());
    }

    @Test
    public void oneReactionChosenIsClickedInTheTrayOnceItShows() {
        Settings.HOLD_REACTION.save(HoldReaction.LOVE);
        int[] at = middleOf(photo);
        send(event(MotionEvent.ACTION_DOWN, at[0], at[1]));
        holdLongEnough();
        waitFor(200);
        assertEquals("Love is second in the tray", List.of(1), picked);
        assertTrue(statusLine(), statusLine().contains(ReactionHold.REACTED + " 1"));
        assertEquals(MotionEvent.ACTION_CANCEL, send(event(MotionEvent.ACTION_MOVE, at[0] + 50, at[1])).getActionMasked());
        assertEquals(MotionEvent.ACTION_CANCEL, send(event(MotionEvent.ACTION_UP, at[0] + 50, at[1])).getActionMasked());
        assertEquals("with a reaction chosen Like gets no touches", List.of("long"), like);

        Settings.HOLD_REACTION.save(HoldReaction.ANGRY);
        send(event(MotionEvent.ACTION_DOWN, at[0], at[1]));
        holdLongEnough();
        send(event(MotionEvent.ACTION_UP, at[0], at[1]));
        waitFor(200);
        assertEquals(List.of(1, 6), picked);
    }

    @Test
    public void aTrayThatDoesntListSevenReactionsIsLeftOpenToPickFrom() {
        Settings.HOLD_REACTION.save(HoldReaction.WOW);
        trayLists = 6;
        int[] at = middleOf(photo);
        send(event(MotionEvent.ACTION_DOWN, at[0], at[1]));
        holdLongEnough();
        waitFor(1000);
        assertTrue(picked.toString(), picked.isEmpty());
        assertTrue(statusLine(), statusLine().contains(ReactionHold.OTHER_TRAY + " 1"));
        send(event(MotionEvent.ACTION_UP, at[0], at[1]));
        root.removeView(tray);

        trayLists = 0;
        send(event(MotionEvent.ACTION_DOWN, at[0], at[1]));
        holdLongEnough();
        waitFor(1000);
        send(event(MotionEvent.ACTION_UP, at[0], at[1]));
        assertTrue(statusLine(), statusLine().contains(ReactionHold.NO_TRAY + " 1"));
    }

    @Test
    public void aLaterHoldStopsTheEarlierOnesClick() {
        Settings.HOLD_REACTION.save(HoldReaction.SAD);
        trayLists = 0;
        int[] at = middleOf(photo);
        send(event(MotionEvent.ACTION_DOWN, at[0], at[1]));
        holdLongEnough();
        send(event(MotionEvent.ACTION_UP, at[0], at[1]));
        // A tray from somewhere else shows before the hold's wait runs out.
        trayLists = 7;
        Settings.HOLD_POST_TO_REACT.save(false);
        send(event(MotionEvent.ACTION_DOWN, at[0], at[1]));
        showTray();
        waitFor(1000);
        assertTrue("only the hold that opened a tray clicks in it: " + picked, picked.isEmpty());
    }

    @Test
    public void likeOnlyLetsTheHoldsTrayThroughAndNoOtherLongPress() {
        Settings.LIKE_ONLY.save(true);
        assertTrue("Like's own long press stays skipped", CommentSheetOptions.skipReactionPicker());
        int[] at = middleOf(photo);
        send(event(MotionEvent.ACTION_DOWN, at[0], at[1]));
        holdLongEnough();
        assertEquals("the hold's tray opens", List.of(false), skipped);
        assertFalse("and stays let through while the finger picks", CommentSheetOptions.skipReactionPicker());
        send(event(MotionEvent.ACTION_UP, at[0], at[1]));
        assertTrue("the lift ends it", CommentSheetOptions.skipReactionPicker());

        Settings.HOLD_REACTION.save(HoldReaction.CARE);
        send(event(MotionEvent.ACTION_DOWN, at[0], at[1]));
        holdLongEnough();
        waitFor(200);
        assertEquals(List.of(false, false), skipped);
        assertEquals(List.of(2), picked);
        assertTrue("the click's over", CommentSheetOptions.skipReactionPicker());
    }

    @Test
    public void onAFeedPostReactingComesBeforeOpeningComments() {
        Settings.LONG_PRESS_OPENS_COMMENTS.save(true);
        int[] at = middleOf(photo);
        send(event(MotionEvent.ACTION_DOWN, at[0], at[1]));
        holdLongEnough();
        assertEquals(List.of("long"), like);
        assertTrue(clicked.toString(), clicked.isEmpty());
        send(event(MotionEvent.ACTION_UP, at[0], at[1]));

        Settings.HOLD_POST_TO_REACT.save(false);
        send(event(MotionEvent.ACTION_DOWN, at[0], at[1]));
        holdLongEnough();
        assertEquals("with the switch off the hold opens comments again", List.of("comment"), clicked);
        assertEquals("Like got the first hold's lift and no second long press", 2, like.size());
    }

    @Test
    public void aLikeWithNoLongPressLeavesTheGestureToFacebook() {
        likeButton.setLongClickable(false);
        int[] at = middleOf(photo);
        send(event(MotionEvent.ACTION_DOWN, at[0], at[1]));
        holdLongEnough();
        assertTrue(like.toString(), like.isEmpty());
        assertEquals(MotionEvent.ACTION_UP, send(event(MotionEvent.ACTION_UP, at[0], at[1])).getActionMasked());
        assertTrue(statusLine(), statusLine().contains(ReactionHold.NO_LONG_PRESS + " 1"));
    }

    @Test
    public void withTheSwitchOffAHoldIsFacebooks() {
        Settings.HOLD_POST_TO_REACT.save(false);
        int[] at = middleOf(photo);
        send(event(MotionEvent.ACTION_DOWN, at[0], at[1]));
        holdLongEnough();
        assertEquals(MotionEvent.ACTION_UP, send(event(MotionEvent.ACTION_UP, at[0], at[1])).getActionMasked());
        assertTrue(like.toString(), like.isEmpty());
        assertTrue(clicked.toString(), clicked.isEmpty());
    }
}
