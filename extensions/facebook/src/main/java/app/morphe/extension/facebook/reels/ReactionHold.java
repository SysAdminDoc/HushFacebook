/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
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
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Hold a post to react (#454): a hold on a feed post's photo or video does what a hold on its Like
 * button does. {@link PostLongPress} times the hold and finds the post's Like, the first button of
 * its footer line.
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

    /** How many reactions Facebook's tray lists: Like, Love, Care, Haha, Wow, Sad and Angry. */
    static final int REACTIONS = 7;
    /** How many more times, and how many milliseconds apart, the tray is looked for before a choice is clicked. */
    private static final int SHOW_TRIES = 10;
    private static final long SHOW_WAIT = 50L;
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
    private static Handler main;

    private ReactionHold() {
    }

    /** Whether Hold a post to react is on. */
    static boolean on() {
        return Settings.HOLD_POST_TO_REACT.get();
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
     * press on Like does, and says whether it opened. With a reaction chosen it's then clicked.
     */
    static boolean start(View likeButton, View row) {
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
            opening = choice == HoldReaction.PICK;
        }
        if (!took) {
            opening = false;
            counted(NO_LONG_PRESS);
            return false;
        }
        counted(OPENED);
        if (choice == HoldReaction.PICK) {
            like = new WeakReference<>(host);
            forwarding = true;
        } else {
            chooseOnceShown(choice, hold, SHOW_TRIES);
        }
        return true;
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
     * touch. Nothing when no hold left one to pick from. The lift or a cancel ends it.
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
        if (host == null || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) ended();
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
    }
}
