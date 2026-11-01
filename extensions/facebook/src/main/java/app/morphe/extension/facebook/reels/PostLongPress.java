/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.facebook.media.PinchZoom;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Long press opens comments, on a feed post (issue #179). Reels have a long-press handler to hook
 * (see {@link ReelGestures}); a feed post has none, so this one times the press itself.
 *
 * <p>Every touch on a Facebook screen comes here first, from FbFragmentActivity's dispatchTouchEvent.
 * With the switch on, a finger that goes down starts a timer of the system's long-press length, and
 * moving past the touch slop, a second finger, a lift or a cancel stops it. When it runs out, the
 * press must sit in a feed post: a row of a list that scrolls up and down, not a full-size page like
 * a reel's, with no carousel, pager or video's bar in the way. Litho draws a post's parts as plain hosts, and a
 * host lists the text it draws (ComponentHost.getTextContent, which 583 keeps by name), so a long
 * press of its own on the way that draws text, or holds a host that does, is the post text, which
 * copies itself, and stays Facebook's. Anything else is judged by how wide it's drawn. The S22's
 * feed photo spans the row edge to edge and its long press shows nothing, so it counts as the
 * photo, and its pending long press is cancelled before the click. A shared post's photo sits
 * inside the shared box's side margins, as the text does (the text's host hangs past them, so
 * only the clipped width shows), so a Litho host that draws no text counts as the photo when it
 * shows across nine tenths of the row; a host whose text can't be read keeps the edge-to-edge
 * rule. A profile picture, Like or grid tile is narrower still: those, and a long press inside
 * another, stay Facebook's.
 *
 * <p>The row's Comment button is found by the footer's order, since its label is translated and
 * 583 has no resource names: the lowest line of three or more clickable views sharing one top and
 * bottom, the first three touching, in reading order (right to left in RTL). Like comes first and
 * Comment second. The innermost clickable views count, since the S22 draws the footer as one
 * clickable bar holding its buttons, and nested lists and scrollers aren't searched, so a People
 * You May Know carousel's Add friend buttons are never candidates. Litho mounts only what's on
 * screen, so a post running past the bottom of the feed has no footer yet: the feed is scrolled up
 * by what's under and the footer looked for again over the next frames. The button is clicked, and
 * the rest of the gesture goes to Facebook as a cancel so the lift doesn't also open the photo it
 * landed on. A post without that footer line is left to Facebook. The button opens the sheet to
 * write, focusing the comment box with the keyboard up; a hold opens it to read, so the first sheet
 * made right after the click keeps the keyboard down ({@link #keyboardOnFirstLoad}).
 *
 * <p>A post whose author turned comments off has no Comment button, so its second footer button is
 * whatever follows Like there (Share or Send), which opens its own sheet and posts nothing. With the
 * switch off, with Hushfacebook paused or when anything in here fails, touches go through untouched.
 * A press on a Pinch to zoom copy that stays up is the zoom's, so it starts no timer. A hold on a
 * feed video is Hold a feed video for 2x's while that switch is on ({@link FeedVideoHold#takes}).
 *
 * <p>The same timer serves Select part of a post's text (#294): every hold that reaches the time
 * arms {@link PostTextSelect}, which opens the text Facebook's own long press copies. With only
 * that switch on, the hold itself is always left to Facebook.
 *
 * <p>A held press left to Facebook counts why, and with Debug logging on logs the view under the
 * finger, so a phone's report says which check passed the hold over.
 */
public final class PostLongPress {
    private static final String FAMILY = FamilyNames.REEL_GESTURES;

    /** Counted for each press held on a feed post. */
    static final String HELD = "feed post held";
    /** Counted for each feed post whose comments a held press opened. */
    static final String OPENED = "feed post comments opened";
    /** Counted for each held post with no footer line to find the Comment button in. */
    static final String NO_BUTTON = "no Comment button in the row";
    /** Counted for each held post scrolled up to bring its unmounted footer on screen. */
    static final String SCROLLED = "feed post scrolled to its footer";
    /** Counted for each comment sheet a held press opened whose keyboard was kept down. */
    static final String KEYBOARD_DOWN = "feed post comments opened without the keyboard";
    /** Counted for each press held to the long-press time, before anything about it is checked. */
    static final String TIMED = "feed press held to the time";
    /** Counted for a press lifted after the long-press time whose timer hadn't run yet. */
    static final String LIFTED_LATE = "feed press lifted before its timer ran";
    // Why a held press was left to Facebook.
    static final String LOST_FOCUS = "feed hold passed: window not focused";
    static final String OWN_LONG_PRESS = "feed hold passed: its own long press, inside another";
    static final String NO_LIST = "feed hold passed: in no feed list";
    static final String STILL_LIST = "feed hold passed: list doesn't scroll up and down";
    static final String FULL_PAGE = "feed hold passed: full-size page";
    static final String NARROW_LONG_PRESS = "feed hold passed: its own long press, narrower than the row";
    static final String ON_TEXT = "feed hold passed: its own long press, on text";
    static final String SCROLLER = "feed hold passed: in a scroller or pager";
    static final String ON_FOOTER = "feed hold passed: on or under the footer";
    static final String ON_VIDEO = "feed hold passed: on a feed video the hold speeds up";
    static final String ON_BAR = "feed hold passed: on a video's bar";

    /** The kept name of the list every feed is drawn in; Facebook's own lists extend it. */
    static final String LIST = "androidx.recyclerview.widget.RecyclerView";
    private static final String PAGER = "androidx.viewpager.widget.ViewPager";

    /** How far apart, in pixels, two footer buttons may sit and still count as touching. */
    private static final int TOUCHING = 2;
    /** The fewest buttons a footer line has: Like, Comment and Share. */
    private static final int FOOTER = 3;
    /** How many more times, and how many milliseconds apart, a scrolled post's footer is looked for. */
    private static final int SHOW_TRIES = 3;
    private static final long SHOW_WAIT = 50L;
    /** How long after a hold clicks Comment the sheet it opens may still be made, in milliseconds. */
    private static final long SHEET_WAIT = 2000L;

    private static Handler main;
    private static WeakReference<View> pressedRoot = new WeakReference<>(null);
    private static float downX;
    private static float downY;
    private static int slop;
    private static boolean timing;
    /** The press opened comments, so every later event of the gesture becomes a cancel. */
    private static boolean swallowing;
    /** Counts finger-downs, so a footer looked for after a scroll is only clicked for the same press. */
    private static int presses;
    /** Until when, in uptime, the next comment sheet made comes from a hold's click; 0 when none is due. */
    private static volatile long sheetDue;
    /** Why the last {@link #rowAt} found no row, one of the passed labels above. */
    @Nullable static String passedOver;
    private static final Runnable FIRE = PostLongPress::fire;

    private PostLongPress() {
    }

    /** Injection point, first thing in FbFragmentActivity.dispatchTouchEvent. */
    public static void touch(@Nullable Activity activity, @Nullable MotionEvent event) {
        try {
            if (activity == null || event == null) return;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) PostTextSelect.pressedOn(activity);
            Window window = activity.getWindow();
            touch(window == null ? null : window.getDecorView(), event);
        } catch (Throwable failure) {
            stop();
            swallowing = false;
            HookStatus.threw(FAMILY, "feed long press", failure);
        }
    }

    /** One touch on the window whose top view is [root]. */
    static void touch(@Nullable View root, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            presses++;
            swallowing = false;
            stop();
            if (root == null || !Utils.settingsReady()) return;
            boolean select = Settings.SELECT_POST_TEXT.get();
            if (!select && !Settings.LONG_PRESS_OPENS_COMMENTS.get()) return;
            if (PinchZoom.covers(root)) return;
            HookStatus.invoked(FAMILY);
            if (select) PostTextSelect.listen(root.getContext());
            pressedRoot = new WeakReference<>(root);
            downX = event.getRawX();
            downY = event.getRawY();
            slop = ViewConfiguration.get(root.getContext()).getScaledTouchSlop();
            timing = true;
            main().postDelayed(FIRE, ViewConfiguration.getLongPressTimeout());
            return;
        }
        if (swallowing) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) swallowing = false;
            event.setAction(MotionEvent.ACTION_CANCEL);
            return;
        }
        if (!timing) return;
        if (action == MotionEvent.ACTION_MOVE) {
            if (Math.hypot(event.getRawX() - downX, event.getRawY() - downY) > slop) stop();
        } else {
            if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                    && event.getEventTime() - event.getDownTime() >= ViewConfiguration.getLongPressTimeout()) {
                HookStatus.counted(FAMILY, LIFTED_LATE);
                Logger.printDebug(() -> "Feed long press: " + LIFTED_LATE);
            }
            stop();
        }
    }

    private static void fire() {
        if (!timing) return;
        timing = false;
        View root = pressedRoot.get();
        pressedRoot = new WeakReference<>(null);
        try {
            if (root == null) return;
            boolean comments = Settings.LONG_PRESS_OPENS_COMMENTS.get();
            boolean select = Settings.SELECT_POST_TEXT.get();
            if (!comments && !select) return;
            HookStatus.counted(FAMILY, TIMED);
            // A call, a task switch or a dialog that took the window mid-press leaves it alone.
            if (!root.isAttachedToWindow() || !root.hasWindowFocus()) {
                passed(LOST_FOCUS, root);
                return;
            }
            // Facebook copies a post's text on its own long press. Whatever a hold copies opens to
            // select once the copy lands, and a hold that copies nothing opens nothing.
            if (select) PostTextSelect.armed();
            if (!comments) return;
            if (FeedVideoHold.takes(root, Math.round(downX), Math.round(downY))) {
                passed(ON_VIDEO, root);
                return;
            }
            View row = rowAt(root, Math.round(downX), Math.round(downY));
            if (row == null) {
                passed(passedOver, root);
                return;
            }
            View comment = commentButton(row);
            // The photo and the name line sit above the footer, so a hold on the footer's own
            // buttons or on anything under it stays Facebook's.
            if (comment != null && Math.round(downY) >= topOf(comment)) {
                passed(ON_FOOTER, root);
                return;
            }
            HookStatus.counted(FAMILY, HELD);
            if (comment == null) {
                // Litho mounts only what's on screen, so a post running past the bottom of the feed
                // has no footer yet. Scrolling it up by what's under brings the footer in.
                View list = (View) row.getParent();
                int under = under(row);
                if (under <= 0 || !list.canScrollVertically(1)) {
                    noButton(row);
                    return;
                }
                cancelLongPresses(root, row);
                swallowing = true;
                list.scrollBy(0, under);
                HookStatus.counted(FAMILY, SCROLLED);
                Logger.printDebug(() -> "Feed long press: " + SCROLLED + ", " + under + " px");
                openOnceShown(row, presses, SHOW_TRIES);
                return;
            }
            cancelLongPresses(root, row);
            if (open(comment)) swallowing = true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "feed long press", failure);
        }
    }

    /** The photo's own long press was timed from the same touch, so it would run next: cancels it. */
    private static void cancelLongPresses(View root, View row) {
        for (View on = hit(root, Math.round(downX), Math.round(downY)); on != null && on != row.getParent();
                on = on.getParent() instanceof View ? (View) on.getParent() : null) {
            on.cancelLongPress();
        }
    }

    /** Clicks [comment], counting it, and says whether it took the click. */
    private static boolean open(View comment) {
        sheetDue = SystemClock.uptimeMillis() + SHEET_WAIT;
        if (!comment.performClick()) {
            sheetDue = 0L;
            HookStatus.threw(FAMILY, "feed long press", new IllegalStateException("the Comment button took no click"));
            return false;
        }
        HookStatus.counted(FAMILY, OPENED);
        Logger.printDebug(() -> "Feed long press: " + OPENED);
        return true;
    }

    /**
     * Injection point, at each return of the constructor that builds the comment sheet's parameters:
     * whether the sheet raises the keyboard when it first loads, [asked] being what Facebook set. The
     * first sheet that asks for it within a moment of a hold clicking Comment gets no keyboard, so
     * it opens to read; every other sheet keeps Facebook's answer.
     */
    public static boolean keyboardOnFirstLoad(boolean asked) {
        try {
            long due = sheetDue;
            if (!asked || due == 0L) return asked;
            sheetDue = 0L;
            if (SystemClock.uptimeMillis() > due) return true;
            HookStatus.counted(FAMILY, KEYBOARD_DOWN);
            Logger.printDebug(() -> "Feed long press: " + KEYBOARD_DOWN);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "feed long press keyboard", failure);
            return asked;
        }
    }

    /**
     * Clicks the Comment button of [row], just scrolled up, once its footer is mounted, looking again
     * [tries] more times on later frames. A new press or a row taken off the screen stops it.
     */
    private static void openOnceShown(View row, int press, int tries) {
        try {
            if (press != presses || !row.isAttachedToWindow()) return;
            View comment = commentButton(row);
            if (comment != null) {
                open(comment);
            } else if (tries > 0) {
                main().postDelayed(() -> openOnceShown(row, press, tries - 1), SHOW_WAIT);
            } else {
                noButton(row);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "feed long press", failure);
        }
    }

    /** Counts a held post with no footer line and logs what the row held, for a phone's report. */
    private static void noButton(View row) {
        HookStatus.counted(FAMILY, NO_BUTTON);
        List<View> clickables = new ArrayList<>();
        collect(row, clickables);
        int under = under(row);
        Logger.printDebug(() -> "Feed long press: " + NO_BUTTON + ", " + clickables.size() + " clickable, row "
                + row.getHeight() + " px tall, " + Math.max(0, under) + " px under the feed");
    }

    /** How far [row] runs past the bottom of its list, in pixels; zero or less when it ends on screen. */
    private static int under(View row) {
        View list = (View) row.getParent();
        return topOf(row) + row.getHeight() - topOf(list) - list.getHeight();
    }

    /** Counts [why] the hold was left to Facebook and logs it with the view under the finger in [root]. */
    private static void passed(String why, View root) {
        HookStatus.counted(FAMILY, why);
        View under = hit(root, Math.round(downX), Math.round(downY));
        Logger.printDebug(() -> "Feed long press: " + why + ", at "
                + (under == null ? "nothing" : under.getClass().getName() + " " + under.getWidth() + "x" + under.getHeight()
                + (under.isLongClickable() ? " long-clickable" : "")));
    }

    private static void stop() {
        timing = false;
        pressedRoot = new WeakReference<>(null);
        if (main != null) main.removeCallbacks(FIRE);
    }

    private static Handler main() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }

    /**
     * The feed row under the screen point ([x], [y]) inside [root], or null when the press there is
     * Facebook's: on something with its own long press other than the photo, inside a carousel,
     * pager or scroller, on a full-size page, or in no list at all. A null leaves the reason in
     * {@link #passedOver}.
     */
    @Nullable
    static View rowAt(View root, int x, int y) {
        passedOver = null;
        View view = hit(root, x, y);
        if (view instanceof SeekBar) return pass(ON_BAR);
        View picture = null;
        while (view != null) {
            if (view.isLongClickable()) {
                // On the S22 the post's photo has a long press that shows nothing. It's still the
                // photo when it's the only one on the path, draws no text and shows as wide as the
                // row, or nearly so inside a shared post's box.
                if (picture != null) return pass(OWN_LONG_PRESS);
                picture = view;
            }
            ViewParent parent = view.getParent();
            if (!(parent instanceof View)) return pass(NO_LIST);
            View up = (View) parent;
            if (isNamed(up, LIST)) {
                if (!up.canScrollVertically(1) && !up.canScrollVertically(-1)) return pass(STILL_LIST);
                if (isPage(view, (ViewGroup) up)) return pass(FULL_PAGE);
                if (picture != null) {
                    Boolean text = drawsText(picture);
                    if (Boolean.TRUE.equals(text)) return pass(ON_TEXT);
                    int shown = shownWidth(picture, view);
                    boolean inset = Boolean.FALSE.equals(text) && shown * 10 >= view.getWidth() * 9;
                    if (shown < view.getWidth() && !inset) return pass(NARROW_LONG_PRESS);
                }
                return view;
            }
            if (isScroller(up)) return pass(SCROLLER);
            view = up;
        }
        return pass(NO_LIST);
    }

    /**
     * Whether [row] is a page of [list], not a post. A page fills the list, or on the Reels tab
     * leaves a strip of the next reel showing under it: nearly as tall as the list and the very
     * same size as the row next to it.
     */
    private static boolean isPage(View row, ViewGroup list) {
        if (row.getWidth() != list.getWidth()) return false;
        if (row.getHeight() == list.getHeight()) return true;
        if (row.getHeight() > list.getHeight() || row.getHeight() * 10 < list.getHeight() * 9) return false;
        for (int i = 0; i < list.getChildCount(); i++) {
            View other = list.getChildAt(i);
            if (other != row && other.getWidth() == row.getWidth() && other.getHeight() == row.getHeight()) return true;
        }
        return false;
    }

    @Nullable
    private static View pass(String why) {
        passedOver = why;
        return null;
    }

    /**
     * Whether [view] or a view inside it draws text: true when a Litho host there lists text it
     * draws or a TextView is there, false when [view] is a Litho host and nothing inside draws
     * text, and null when [view]'s text can't be read.
     */
    @Nullable
    static Boolean drawsText(View view) {
        Boolean own = textContent(view);
        if (own == null) return null;
        return own || holdsText(view);
    }

    /** Whether a view inside [view] draws text, as {@link #drawsText} reads it. */
    private static boolean holdsText(View view) {
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView || Boolean.TRUE.equals(textContent(child)) || holdsText(child)) return true;
        }
        return false;
    }

    /** Whether the Litho host [view] lists text it draws, or null when it isn't one or the list can't be read. */
    @Nullable
    private static Boolean textContent(View view) {
        try {
            Object content = view.getClass().getMethod("getTextContent").invoke(view);
            return content instanceof List ? !((List<?>) content).isEmpty() : null;
        } catch (ReflectiveOperationException | SecurityException e) {
            return null;
        }
    }

    /**
     * How wide [view] shows inside [row]: its span cut to each parent's on the way up. The post
     * text's host is as wide as the row but hangs past the margins of the box it sits in.
     */
    private static int shownWidth(View view, View row) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        int left = at[0];
        int right = at[0] + view.getWidth();
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent()) {
            View up = (View) parent;
            up.getLocationOnScreen(at);
            left = Math.max(left, at[0]);
            right = Math.min(right, at[0] + up.getWidth());
            if (up == row) break;
        }
        return Math.max(0, right - left);
    }

    /**
     * The view at the screen point ([x], [y]) in [view] that the window gives the touch to: the last
     * drawn first, passing over whatever takes no touches, as Android does. On the S22 an empty frame
     * as big as the feed is drawn over it and takes nothing, so the deepest view there is never the
     * post. With nothing there that takes a touch, the deepest view.
     */
    @Nullable
    static View hit(View view, int x, int y) {
        View taker = taker(view, x, y);
        return taker != null ? taker : deepest(view, x, y);
    }

    /** The view at ([x], [y]) in [view] that takes the touch, or null when nothing there does. */
    @Nullable
    private static View taker(View view, int x, int y) {
        if (view.getVisibility() != View.VISIBLE || !contains(view, x, y)) return null;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View found = taker(group.getChildAt(i), x, y);
                if (found != null) return found;
            }
        }
        // A bar reads its touches itself, with neither flag set.
        if (view.isClickable() || view.isLongClickable() || view instanceof SeekBar) return view;
        // A list or scroller takes a press anywhere in it to drag, so its own view under the finger counts.
        return isNamed(view, LIST) || isScroller(view) ? deepest(view, x, y) : null;
    }

    /** The deepest shown view at ([x], [y]) in [view], the last drawn first. */
    @Nullable
    private static View deepest(View view, int x, int y) {
        if (view.getVisibility() != View.VISIBLE || !contains(view, x, y)) return null;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View found = deepest(group.getChildAt(i), x, y);
                if (found != null) return found;
            }
        }
        return view;
    }

    /** [row]'s Comment button: the second in reading order of its lowest footer line, or null. */
    @Nullable
    static View commentButton(View row) {
        List<View> clickables = new ArrayList<>();
        collect(row, clickables);
        Map<Long, List<View>> lines = new LinkedHashMap<>();
        for (View view : clickables) {
            int[] at = new int[2];
            view.getLocationOnScreen(at);
            long key = ((long) at[1] << 32) | ((at[1] + view.getHeight()) & 0xffffffffL);
            List<View> line = lines.get(key);
            if (line == null) lines.put(key, line = new ArrayList<>());
            line.add(view);
        }
        boolean rtl = row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        View found = null;
        int lowest = Integer.MIN_VALUE;
        for (List<View> line : lines.values()) {
            if (line.size() < FOOTER) continue;
            List<int[]> spans = new ArrayList<>();
            for (View view : line) {
                int[] at = new int[2];
                view.getLocationOnScreen(at);
                spans.add(new int[] {at[0], at[0] + view.getWidth(), at[1] + view.getHeight(), spans.size()});
            }
            spans.sort((a, b) -> rtl ? Integer.compare(b[1], a[1]) : Integer.compare(a[0], b[0]));
            boolean touching = true;
            for (int i = 1; i < FOOTER && touching; i++) {
                int gap = rtl ? spans.get(i - 1)[0] - spans.get(i)[1] : spans.get(i)[0] - spans.get(i - 1)[1];
                touching = Math.abs(gap) <= TOUCHING;
            }
            int bottom = spans.get(0)[2];
            if (touching && bottom > lowest) {
                lowest = bottom;
                found = line.get(spans.get(1)[3]);
            }
        }
        return found;
    }

    /**
     * The shown clickable views under [group], innermost: a clickable view holding clickable views
     * gives way to them, as the S22's footer bar does to its buttons. Nested lists and scrollers
     * aren't searched.
     */
    private static void collect(View group, List<View> into) {
        if (!(group instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) group;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getWidth() <= 0 || child.getHeight() <= 0) continue;
            if (isScroller(child) || isNamed(child, LIST)) continue;
            int before = into.size();
            collect(child, into);
            if (child.isClickable() && into.size() == before) into.add(child);
        }
    }

    private static int topOf(View view) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return at[1];
    }

    private static boolean contains(View view, int x, int y) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return x >= at[0] && x < at[0] + view.getWidth() && y >= at[1] && y < at[1] + view.getHeight();
    }

    static boolean isScroller(View view) {
        return view instanceof ScrollView || view instanceof HorizontalScrollView || isNamed(view, PAGER);
    }

    /** Whether [view]'s class or one it extends is named [name]. */
    static boolean isNamed(View view, String name) {
        for (Class<?> type = view.getClass(); type != null && type != View.class; type = type.getSuperclass()) {
            if (type.getName().equals(name)) return true;
        }
        return false;
    }

    /** For tests: forgets the press being timed and the gesture being cancelled. */
    static void reset() {
        stop();
        swallowing = false;
        passedOver = null;
        sheetDue = 0L;
    }
}
