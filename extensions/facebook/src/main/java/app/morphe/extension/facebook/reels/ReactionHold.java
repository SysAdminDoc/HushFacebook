/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Hold a feed photo to react and Hold a feed video to react (#454): a hold on a feed post's photo or
 * video, each with its own switch, does what a hold on its Like button does. {@link PostLongPress}
 * times the hold, tells a video from a photo and finds the post's Like, the first button of its
 * footer line.
 *
 * <p>Facebook's long press on Like is a Litho long click on Like's host. It opens the reaction tray
 * (com.facebook.feedback.sharedcomponents.reactions.dock.RopeStyleUFIDockView, a kept name) in a
 * popup and marks the press as tracking (read from 587, arm64 and armv7). While it tracks, every
 * touch Like's host gets goes to the tray: sliding over a reaction picks it out, lifting there gives
 * it, and lifting without having moved leaves the tray open to tap. So the hold long-clicks Like
 * itself, and with Pick each time hands Like a copy of every touch left in the gesture, in Like's
 * own coordinates (the tray reads the screen ones, which a copy keeps). The post under the finger
 * gets a cancel, as when a hold opens comments.
 *
 * <p>With one reaction chosen, the tray is clicked through its accessibility helper, the one a
 * screen reader uses: a click on reaction n gives it and closes the tray. The patch hands every
 * tray made to {@link #dockMade}, and the one showing after the long click is clicked once it lists
 * its reactions. A tray that doesn't list seven, so that Facebook's order can't be counted on,
 * stays open to pick from.
 *
 * <p>Like only skips the tray a long press on Like opens. A hold asks for the tray, so while one
 * opens it {@link #opening} lets it through.
 *
 * <p>A tray to pick from opens where the finger is (#454): Facebook shows it in a popup window of its
 * own placed over Like, and the hold moves that window up by how far the finger is above Like, so it
 * sits by the finger as it sits by Like on Like's own long press. Facebook finds the reaction under a
 * finger from where the tray is on screen, so its picking moves with it.
 *
 * <p>The popup takes every touch on the screen while it shows, through a touch interceptor that
 * turns a press inside the tray's reach, which runs from the tray down toward Like, into a pick. A
 * press that missed the reactions could give one that way. For a tray a hold opened, the interceptor
 * asks {@link #trayTouched} first, with the tray's pill, the drawable behind the reactions that
 * Facebook tests a press against before stretching it toward Like, and a press off the pill closes
 * the tray as Back does, giving nothing. With Slide to pick one, letting go anywhere but on a
 * reaction closes it too, where Facebook would leave it open to tap one.
 */
public final class ReactionHold {
    private static final String FAMILY = FamilyNames.REEL_GESTURES;

    /** Counted for each hold that opened a post's reaction tray. */
    static final String OPENED = "feed post reactions opened";
    /** Counted for each hold that gave the chosen reaction. */
    static final String REACTED = "feed post reacted";
    // Why a hold's reaction didn't happen.
    static final String NO_LONG_PRESS = "feed post reactions: Like took no long press";
    static final String NO_TRAY = "feed post reactions: no tray showed";
    static final String OTHER_TRAY = "feed post reactions: the tray doesn't list seven reactions";
    static final String NOT_CLICKED = "feed post reactions: the tray took no click";
    /** Counted for each tray to pick from moved up to the finger. */
    static final String PLACED = "feed post reactions opened by the finger";
    /** Counted for each tray closed by a press off its reactions. */
    static final String CLOSED_BY_TAP = "feed post reactions closed by a tap off them";
    /** Counted for each tray Slide to pick one closed because the finger let go off its reactions. */
    static final String CLOSED_ON_LIFT = "feed post reactions closed on a lift off them";
    /** Counted when the tray's window didn't take the Back that closes it. */
    static final String NOT_CLOSED = "feed post reactions: the tray's window didn't close";

    /** How many reactions Facebook's tray lists: Like, Love, Care, Haha, Wow, Sad and Angry. */
    static final int REACTIONS = 7;
    /** How many more times, and how many milliseconds apart, the tray is looked for before a choice is clicked. */
    private static final int SHOW_TRIES = 10;
    private static final long SHOW_WAIT = 50L;
    /** How long after the lift Slide to pick one reads the tray, once Facebook has settled it. */
    private static final long LIFT_SETTLE = 120L;
    /** The most trays kept track of; Facebook makes about one per screen. */
    private static final int KEPT = 8;

    private static final List<WeakReference<View>> docks = new ArrayList<>();
    private static WeakReference<View> like = new WeakReference<>(null);
    /** The rest of the gesture goes to {@link #like}. */
    private static boolean forwarding;
    /** A hold is opening the tray, which Like only lets through. */
    private static volatile boolean opening;
    /** Counts holds, so a tray looked for later is only clicked for the same one. */
    private static int holds;
    /** The tray the last hold opened to pick from, while no finger has gone down on the screen under it since. */
    private static WeakReference<View> held = new WeakReference<>(null);
    /** The last hold's tray closes when the finger lets go off its reactions (Slide to pick one). */
    private static boolean slideOnly;
    /** A press on the tray's window closed it, so the rest of that gesture goes nowhere. */
    private static boolean closing;
    private static Handler main;

    private ReactionHold() {
    }

    /** Whether Hold a feed photo to react or Hold a feed video to react is on. */
    static boolean on() {
        return Settings.HOLD_PHOTO_TO_REACT.get() || Settings.HOLD_VIDEO_TO_REACT.get();
    }

    /** Whether a hold on a feed post's video, [video], or on its photo reacts. */
    static boolean on(boolean video) {
        return video ? Settings.HOLD_VIDEO_TO_REACT.get() : Settings.HOLD_PHOTO_TO_REACT.get();
    }

    /**
     * Injection point, at each return of the reaction tray's constructor: keeps [made], the tray, to
     * click a chosen reaction in once it shows. It takes an Object so the hook never depends on the
     * tray's class reaching View.
     */
    public static void dockMade(@Nullable Object made) {
        try {
            if (!(made instanceof View)) return;
            View dock = (View) made;
            for (Iterator<WeakReference<View>> each = docks.iterator(); each.hasNext(); ) {
                View kept = each.next().get();
                if (kept == null || kept == dock) each.remove();
            }
            if (docks.size() >= KEPT) docks.remove(0);
            docks.add(new WeakReference<>(dock));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reaction tray", failure);
        }
    }

    /** Whether the reaction tray opening now is one a hold asked for, which Like only lets through. */
    public static boolean opening() {
        return opening;
    }

    /** A finger went down: whatever the last hold left is over, a click still waiting for its tray included. */
    static void pressed() {
        holds++;
        held = new WeakReference<>(null);
        ended();
    }

    /** The hold's gesture is over. */
    private static void ended() {
        forwarding = false;
        opening = false;
        like = new WeakReference<>(null);
    }

    /**
     * Opens the reaction tray of the post whose Like button is [likeButton], inside [row], as a long
     * press on Like does, and says whether it opened. A tray to pick from is moved by the finger,
     * which is [fingerY] down the screen; with a reaction chosen it's clicked instead.
     */
    static boolean start(View likeButton, View row, float fingerY) {
        View host = longClickable(likeButton, row);
        if (host == null) {
            counted(NO_LONG_PRESS);
            return false;
        }
        HoldReaction choice = Settings.HOLD_REACTION.get();
        int hold = ++holds;
        opening = true;
        boolean took;
        try {
            took = host.performLongClick();
        } finally {
            opening = choice.picks();
        }
        if (!took) {
            opening = false;
            counted(NO_LONG_PRESS);
            return false;
        }
        counted(OPENED);
        if (choice.picks()) {
            like = new WeakReference<>(host);
            forwarding = true;
            slideOnly = choice == HoldReaction.SLIDE;
            placeOnceShown(host, fingerY, hold, SHOW_TRIES);
        } else {
            chooseOnceShown(choice, hold, SHOW_TRIES);
        }
        return true;
    }

    /**
     * Keeps the tray the hold opened to pick from and moves it by the finger, at once when Facebook
     * showed it while Like's long press ran, so it opens there, or on a later frame, looking again
     * [tries] more times. The next finger down stops it.
     */
    private static void placeOnceShown(View host, float fingerY, int hold, int tries) {
        try {
            if (hold != holds) return;
            View tray = newestInWindow();
            if (tray == null) {
                if (tries > 0) main().postDelayed(() -> placeOnceShown(host, fingerY, hold, tries - 1), SHOW_WAIT);
                return;
            }
            held = new WeakReference<>(tray);
            int by = moveBy(tray, host, fingerY);
            if (by != 0) {
                counted(PLACED);
                Logger.printDebug(() -> "Feed post reactions: the tray moved " + by + " px to the finger");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "feed post reactions' place", failure);
        }
    }

    /** The newest tray kept that sits in a popup window of its own, shown or about to be, or null. */
    @Nullable
    private static View newestInWindow() {
        for (int i = docks.size() - 1; i >= 0; i--) {
            View dock = docks.get(i).get();
            if (dock != null && windowOf(dock) != null) return dock;
        }
        return null;
    }

    /**
     * The layout of the popup window [tray] sits in, or null when it sits in none of its own: a
     * closed tray is taken out of its window, and one inside a screen isn't moved.
     */
    @Nullable
    static WindowManager.LayoutParams windowOf(View tray) {
        View top = tray.getRootView();
        if (top == tray) return null;
        ViewGroup.LayoutParams params = top.getLayoutParams();
        if (!(params instanceof WindowManager.LayoutParams)) return null;
        WindowManager.LayoutParams window = (WindowManager.LayoutParams) params;
        boolean popup = window.type >= WindowManager.LayoutParams.FIRST_SUB_WINDOW
                && window.type <= WindowManager.LayoutParams.LAST_SUB_WINDOW;
        return popup ? window : null;
    }

    /**
     * Moves [tray]'s window up by how far the finger, [fingerY] down the screen, is above the middle
     * of Like's [host], keeping its top under the status bar, and says by how many pixels. Facebook
     * places the window from the top of the screen.
     */
    static int moveBy(View tray, View host, float fingerY) {
        WindowManager.LayoutParams window = windowOf(tray);
        if (window == null || !host.isAttachedToWindow()) return 0;
        if ((window.gravity & Gravity.VERTICAL_GRAVITY_MASK) != Gravity.TOP) return 0;
        int[] at = new int[2];
        host.getLocationOnScreen(at);
        int by = Math.round(fingerY - (at[1] + host.getHeight() / 2f));
        Rect shown = new Rect();
        host.getWindowVisibleDisplayFrame(shown);
        by = Math.max(by, shown.top - window.y);
        if (by >= 0) return 0;
        window.y += by;
        View top = tray.getRootView();
        WindowManager windows = (WindowManager) top.getContext().getSystemService(Context.WINDOW_SERVICE);
        windows.updateViewLayout(top, window);
        return by;
    }

    /**
     * Injection point, first thing in the touch interceptor of the reaction tray's popup, which every
     * touch on the screen goes through while the tray shows; [window] is the popup's top view and
     * [pill] the tray's pill, or null. Says whether the touch is taken here. A press off the reactions
     * of a tray a hold opened closes the tray and takes the rest of that gesture; everything else
     * goes on to Facebook.
     */
    public static boolean trayTouched(@Nullable View window, @Nullable MotionEvent event, @Nullable Drawable pill) {
        try {
            if (window == null || event == null) return false;
            int action = event.getActionMasked();
            if (action != MotionEvent.ACTION_DOWN) {
                if (!closing) return false;
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) closing = false;
                return true;
            }
            closing = false;
            View tray = held.get();
            if (tray == null || tray.getRootView() != window || !Utils.settingsReady() || !on()) return false;
            Rect reactions = reactionsOf(tray, pill);
            int x = Math.round(event.getRawX());
            int y = Math.round(event.getRawY());
            if (reactions.contains(x, y)) return false;
            closing = true;
            Logger.printDebug(() -> "Feed post reactions: a press at " + x + "," + y + ", off the reactions at "
                    + reactions.toShortString() + ", closes the tray");
            counted(close(window) ? CLOSED_BY_TAP : NOT_CLOSED);
            return true;
        } catch (Throwable failure) {
            closing = false;
            HookStatus.threw(FAMILY, "feed post reactions' tap", failure);
            return false;
        }
    }

    /**
     * Where on the screen [tray]'s reactions are: its [pill], placed in the tray as Facebook draws
     * it, or the tray itself without one. Not from the tray's accessibility helper, which builds no
     * reaction's node while no screen reader runs (each has no label then, and the helper throws).
     */
    static Rect reactionsOf(View tray, @Nullable Drawable pill) {
        int[] at = new int[2];
        tray.getLocationOnScreen(at);
        Rect reactions = pill == null ? new Rect() : new Rect(pill.getBounds());
        if (reactions.isEmpty()) reactions.set(0, 0, tray.getWidth(), tray.getHeight());
        reactions.offset(at[0], at[1]);
        return reactions;
    }

    /**
     * Closes the popup whose top view is [window] as Back does, giving nothing, and says whether the
     * window took it. Popup windows close themselves on Back.
     */
    private static boolean close(View window) {
        long now = SystemClock.uptimeMillis();
        window.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0));
        return window.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0));
    }

    /**
     * Slide to pick one, a moment after hold [hold]'s finger let go: a tray still waiting for a tap
     * closes. Facebook reads every lift as a close and stops the tray taking touches, and a lift that
     * picked nothing opens it to tap again, so the tray is read once that has settled. One the lift
     * picked from stays untouchable as it closes, and is left to close. A finger down since stops it.
     */
    private static void closeIfWaiting(int hold) {
        try {
            if (hold != holds) return;
            View tray = held.get();
            WindowManager.LayoutParams window = tray == null ? null : windowOf(tray);
            if (window == null) {
                Logger.printDebug(() -> "Feed post reactions: the lift left no tray to close");
                return;
            }
            if ((window.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) {
                Logger.printDebug(() -> "Feed post reactions: the lift picked a reaction, so the tray closes itself");
                return;
            }
            counted(close(tray.getRootView()) ? CLOSED_ON_LIFT : NOT_CLOSED);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "feed post reactions' lift", failure);
        }
    }

    /** [view] or the nearest view holding it inside [row] that takes a long press, or null. */
    @Nullable
    private static View longClickable(View view, View row) {
        for (View on = view; on != null; on = on.getParent() instanceof View ? (View) on.getParent() : null) {
            if (on.isLongClickable()) return on;
            if (on == row) return null;
        }
        return null;
    }

    /**
     * Hands Like a copy of [event], a touch of the gesture whose hold opened the tray, as Like's own
     * touch. Nothing when no hold left one to pick from. The lift or a cancel ends it, and with Slide
     * to pick one a lift that picked nothing closes the tray.
     */
    static void forward(MotionEvent event) {
        if (!forwarding) return;
        View host = like.get();
        int action = event.getActionMasked();
        if (host != null && host.isAttachedToWindow()) {
            int[] at = new int[2];
            host.getLocationInWindow(at);
            MotionEvent copy = MotionEvent.obtain(event);
            try {
                copy.offsetLocation(-at[0], -at[1]);
                host.dispatchTouchEvent(copy);
            } finally {
                copy.recycle();
            }
        }
        if (host == null || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            ended();
            if (action == MotionEvent.ACTION_UP && slideOnly) {
                int hold = holds;
                main().postDelayed(() -> closeIfWaiting(hold), LIFT_SETTLE);
            }
        }
    }

    /**
     * Clicks [choice] in the tray once it shows and lists its reactions, looking again [tries] more
     * times on later frames. The next finger down stops it.
     */
    private static void chooseOnceShown(HoldReaction choice, int hold, int tries) {
        try {
            if (hold != holds) return;
            View dock = shownDock();
            AccessibilityNodeProvider provider = dock == null ? null : dock.getAccessibilityNodeProvider();
            int listed = reactions(provider);
            if (listed != REACTIONS) {
                if (tries > 0) {
                    main().postDelayed(() -> chooseOnceShown(choice, hold, tries - 1), SHOW_WAIT);
                } else {
                    counted(dock == null ? NO_TRAY : OTHER_TRAY);
                    Logger.printDebug(() -> "Feed post reactions: the tray lists " + listed);
                }
                return;
            }
            boolean clicked;
            opening = true;
            try {
                clicked = provider.performAction(choice.index, AccessibilityNodeInfo.ACTION_CLICK, null);
            } finally {
                opening = false;
            }
            counted(clicked ? REACTED : NOT_CLICKED);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "feed post reaction", failure);
        }
    }

    /** The newest tray kept that shows on screen, or null. */
    @Nullable
    static View shownDock() {
        for (int i = docks.size() - 1; i >= 0; i--) {
            View dock = docks.get(i).get();
            if (dock != null && dock.isAttachedToWindow() && dock.isShown() && dock.getWidth() > 0) return dock;
        }
        return null;
    }

    /** How many reactions the tray behind [provider] lists, or -1 when it can't say. */
    static int reactions(@Nullable AccessibilityNodeProvider provider) {
        if (provider == null) return -1;
        AccessibilityNodeInfo tray = provider.createAccessibilityNodeInfo(View.NO_ID);
        return tray == null ? -1 : tray.getChildCount();
    }

    private static void counted(String what) {
        HookStatus.counted(FAMILY, what);
        Logger.printDebug(() -> "Feed long press: " + what);
    }

    private static Handler main() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }

    /** For tests: forgets the trays, the hold and its gesture. */
    static void reset() {
        pressed();
        docks.clear();
        slideOnly = false;
        closing = false;
    }
}
