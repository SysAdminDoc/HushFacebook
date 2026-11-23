/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.VelocityTracker;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;

import app.morphe.extension.facebook.misc.AppLock;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Plays the videos in Saved one after another (#404).
 *
 * <p>A reel tapped on Facebook's Saved page opens in a Reels viewer of its own, and when it ends or
 * you swipe up, the viewer goes on to suggested reels ("Up next"), never the next thing you saved.
 * Facebook 585 has no route that plays Saved in order: its saved reels link bounces back to the
 * main screen, and both Saved and Your library's saved reels play the tapped one, then suggestions
 * (S22 2026-11-08). So this goes back to the Saved page and taps the next card for you.
 *
 * <p>The patch hands {@link #touched} each finger press on Facebook's screens, and this keeps the
 * card a press landed on: a child of a scrolling list (a RecyclerView, or anything that scrolls up
 * or down) with another child of the same width beside it, the shape of Saved's grid of cards. A
 * Reels viewer opening from Saved ({@link #viewerOpening}, origin "saved::fb_shorts_viewer") within
 * {@link #TOUCH_WINDOW_MS} of such a press starts a chain, and the screen created for it is the
 * chain's viewer. While that viewer is in front with nothing over it, a reel ending there
 * ({@link #ended}) or a second reel taking its screen ({@link #bound}) closes it. Once the Saved
 * page is back, the card after the one played (by its label when it has one, else its place in the
 * grid, left to right and then down) is scrolled into view if it has to be and tapped, and the
 * viewer that opens starts the chain again. The last saved video closes as well, and with no card
 * after it the chain ends on Saved. A card the chain taps can be a saved post, which opens a post
 * page instead and used to end the chain there (#404, a reporter's video): a screen that comes in
 * front from the chain's own tap with no saved video starting is closed again ({@link #closePost})
 * and the card after it tapped. Facebook 588 shows that post page as a window over Saved's own
 * screen instead ({@link #checkPostWindow}), which gets a Back the same way. Back from the viewer, another viewer opening from anywhere but
 * Saved, the Saved page closing, a page that doesn't come back within {@link #RETURN_WINDOW_MS},
 * the switch going off and Pause each end the chain.
 *
 * <p>Facebook's own swipe showed a suggested reel before the viewer closed, and a swipe down had
 * nothing to go back to (#404, a reporter's video). So in the chain's viewer an up or down drag is
 * taken from Facebook once it passes the touch slop: Facebook gets a cancel, the video follows the
 * finger, and a long enough or quick enough swipe slides it off and plays the next saved video, or
 * the one before for a swipe down, while anything shorter slides back. A swipe down on the first
 * card slides back too. While the viewer closes and Saved taps the next card, a black cover lies
 * over the Saved page, taken off once the next video's screen is in front or the chain ends. That
 * screen, and any window the chain's tap brings up over Saved, is black itself from its first frame
 * until its video settles into place or it's closed ({@link SettleCover}), so neither Facebook moving
 * the new video up under the status bar nor a post being skipped shows between two videos. With
 * Stay on a saved video when it ends on, an end leaves the video where Facebook does and only a
 * swipe moves on.
 *
 * <p>With Show saved posts in the row on, a saved post the chain's tap opens as a window over Saved
 * stays open instead ({@link #stopOnPost}, #404: the reporter wanted posts in the row too). Its
 * window's touches are watched ({@link #postTouched}): while the post can still scroll the way the
 * finger goes they're Facebook's, and past its end an up or down swipe slides it off and plays the
 * next saved item, or the one before, the way a swipe on a saved video does.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, Facebook's viewer
 * goes on as it was. Cards' labels are only compared, never written anywhere.
 */
public final class SavedChain {
    static final String FAMILY = FamilyNames.REEL_DECLUTTER;

    /** The press observer's name in the diagnostics report. */
    static final String TOUCH_HOOK = "Saved page taps";

    /** The chain's name in the diagnostics report. */
    static final String CHAIN_HOOK = "Saved videos in a row";

    /** Counted each time the next saved video is tapped open. */
    static final String NEXT = "Next saved video opened";

    /** The surface Facebook names in a player origin for a video opened from Saved. */
    static final String SAVED_SURFACE = "saved";

    /** How long after a press on a card the viewer opening from Saved counts as that card's. */
    static final long TOUCH_WINDOW_MS = 3000;

    /** How long after the viewer starts opening a new screen counts as the viewer. */
    static final long SCREEN_WINDOW_MS = 5000;

    /** A reel that ends this soon after its viewer opened is taken as a leftover, not the saved one. */
    static final long OPEN_GUARD_MS = 1000;

    /** A second reel taking the viewer's screen this soon after the first is Facebook settling in, not a swipe. */
    static final long SWIPE_GUARD_MS = 700;

    /** How long the Saved page has to come back after the viewer closes. */
    static final long RETURN_WINDOW_MS = 4000;

    /** How long the Saved page gets to settle after it's back, before the next card is tapped. */
    static final long SETTLE_MS = 350;

    /** How long a scroll gets to settle before the next card is looked for again. */
    static final long SCROLL_SETTLE_MS = 300;

    /** How long the made-up finger stays down. */
    static final long PRESS_MS = 60;

    /** Scrolls tried while looking for the next card. */
    static final int MAX_SCROLLS = 4;

    /** Where on a card the tap lands, down from its top: in the picture, clear of the menu button below it. */
    static final float TAP_DOWN = 0.35f;

    /** Widths within this many pixels count as the same. */
    static final int SAME_PX = 2;

    /** A card's tap point has to be this far inside the list's visible part, as a share of its height. */
    static final float EDGE_SHARE = 0.08f;

    /** The swipe takeover's name in the diagnostics report. */
    static final String SWIPE_HOOK = "Saved video swipes";

    /** Counted each time a swipe goes back to the saved video before. */
    static final String PREVIOUS = "Previous saved video opened";

    /** A drag past this share of the viewer's height is a swipe, however slow. */
    static final float SWIPE_SHARE = 0.12f;

    /** A drag moving faster than this, in dp a second, is a swipe, however short. */
    static final float FLING_DP_PER_SECOND = 350f;

    /** How long the video takes to slide off or back. */
    static final long SLIDE_MS = 160;

    /** How long the cover stays on Saved after the next video's screen is in front, while it opens. */
    static final long UNCOVER_MS = 400;

    /** The longest the cover stays on Saved, whatever happens. */
    static final long COVER_MS = RETURN_WINDOW_MS + 2000;

    /**
     * How long a screen a tapped card opened is in front, with no saved video starting, before it
     * counts as a post. Facebook's Reels viewer says where it opens from as it's built, before it's
     * in front, so this only waits out a slow one.
     */
    static final long POST_CHECK_MS = 500;

    /** How often, after the chain's tap, Saved is checked for a new window to keep black. Under a frame. */
    static final long WINDOW_POLL_MS = 4;

    /** How many posts in a row the chain closes before it gives up on Saved. */
    static final int MAX_POSTS_SKIPPED = 12;

    /** Counted each time a card the chain tapped opened a post, which it closed for the card after it. */
    static final String POST_SKIPPED = "Saved post skipped";

    /** Counted each time a saved post the chain's tap opened stays open, with Show saved posts in the row on. */
    static final String POST_SHOWN = "Saved post kept in the row";

    /** The clock for tests, or null for the uptime clock. */
    @Nullable static LongSupplier clockForTests;

    private static final WeakReference<Activity> NO_SCREEN = new WeakReference<>(null);
    private static final WeakReference<Object> NO_PLAYER = new WeakReference<>(null);

    /** A card a finger pressed: where it sat in its list and how big it is. Read and moved on the main thread. */
    static final class Card {
        final WeakReference<Activity> page;
        final WeakReference<ViewGroup> list;
        int left;
        int top;
        final int width;
        final int height;
        @Nullable final String label;
        final long at;

        Card(Activity page, ViewGroup list, View card, long at) {
            this.page = new WeakReference<>(page);
            this.list = new WeakReference<>(list);
            left = card.getLeft();
            top = card.getTop();
            width = card.getWidth();
            height = card.getHeight();
            label = labelOf(card);
            this.at = at;
        }
    }

    /** A viewer a saved card opened, and how far its trip back to the Saved page has got. */
    static final class Chain {
        final Card card;
        final long openedAt;
        WeakReference<Activity> viewer = NO_SCREEN;
        WeakReference<Object> player = NO_PLAYER;
        long boundAt;
        boolean moving;
        boolean returned;
        int scrolls;
        /** Which card Saved taps once it's back: 1 the one after, -1 the one before. */
        int direction = 1;
        /** How many posts in a row the chain has closed to get here (#404). */
        int postsSkipped;
        /** Whether the chain's own tap opened this viewer, which then stays black while its video settles. */
        boolean chained;

        Chain(Card card, long openedAt) {
            this.card = card;
            this.openedAt = openedAt;
        }
    }

    /** The card the chain just tapped on Saved, until a saved video opens from it or it's known to be a post. */
    static final class Tapped {
        final WeakReference<Activity> page;
        final long at;
        final int direction;
        final int postsSkipped;
        /** The dialog windows already over the Saved page when the chain tapped, which aren't the card's. */
        final List<WeakReference<View>> windowsBefore = new ArrayList<>();
        /** The windows the tap brought up over Saved, kept black until it's known what they are. Main thread. */
        final List<WeakReference<View>> blackedOut = new ArrayList<>();
        /** The post window the chain is closing, which keeps its black as it goes. Main thread. */
        WeakReference<View> closing = new WeakReference<>(null);

        Tapped(Activity page, long at, int direction, int postsSkipped, List<View> windowsBefore) {
            this.page = new WeakReference<>(page);
            this.at = at;
            this.direction = direction;
            this.postsSkipped = postsSkipped;
            for (View window : windowsBefore) this.windowsBefore.add(new WeakReference<>(window));
        }

        boolean hadBefore(View window) {
            for (WeakReference<View> old : windowsBefore) {
                if (old.get() == window) return true;
            }
            return false;
        }
    }

    /** A finger on the chain's viewer, watched for an up or down swipe. Main thread only. */
    static final class Drag {
        final WeakReference<Activity> viewer;
        final Chain chain;
        final float downX;
        final float downY;
        final int slop;
        boolean taken;
        boolean dropped;
        WeakReference<View> moved = new WeakReference<>(null);
        /** How fast the finger goes. A lift often lands right where the last move did, so two events can't tell. */
        @Nullable private VelocityTracker tracker = VelocityTracker.obtain();

        Drag(Activity viewer, Chain chain, MotionEvent down) {
            this.viewer = new WeakReference<>(viewer);
            this.chain = chain;
            downX = down.getX();
            downY = down.getY();
            slop = ViewConfiguration.get(viewer).getScaledTouchSlop();
            track(down);
        }

        void track(MotionEvent event) {
            if (tracker != null) tracker.addMovement(event);
        }

        /** The finger's speed up or down in pixels a second, down positive. */
        float speed() {
            if (tracker == null) return 0;
            tracker.computeCurrentVelocity(1000);
            return tracker.getYVelocity();
        }

        void forget() {
            if (tracker != null) tracker.recycle();
            tracker = null;
        }
    }

    /**
     * A saved post the chain's tap opened and left open (#404): its window over the Saved page, the
     * card it came from, and a finger on it. Main thread only.
     */
    static final class PostStop {
        final Card card;
        final WeakReference<Activity> page;
        final WeakReference<View> window;
        final WeakReference<Dialog> dialog;
        boolean pressed;
        boolean taken;
        boolean dropped;
        /** Sliding off to the next saved item, so no new finger is taken. */
        boolean leaving;
        /** Whether the post could still scroll down, and up, when the finger went down. */
        boolean scrollsDown;
        boolean scrollsUp;
        float downX;
        float downY;
        int slop;
        @Nullable private VelocityTracker tracker;

        PostStop(Card card, Activity page, View window, @Nullable Dialog dialog) {
            this.card = card;
            this.page = new WeakReference<>(page);
            this.window = new WeakReference<>(window);
            this.dialog = new WeakReference<>(dialog);
        }

        void press(MotionEvent down, View root) {
            forget();
            pressed = true;
            downX = down.getX();
            downY = down.getY();
            slop = ViewConfiguration.get(root.getContext()).getScaledTouchSlop();
            scrollsDown = postScrolls(root, root, 1);
            scrollsUp = postScrolls(root, root, -1);
            tracker = VelocityTracker.obtain();
            track(down);
        }

        void track(MotionEvent event) {
            if (tracker != null) tracker.addMovement(event);
        }

        /** The finger's speed up or down in pixels a second, down positive. */
        float speed() {
            if (tracker == null) return 0;
            tracker.computeCurrentVelocity(1000);
            return tracker.getYVelocity();
        }

        void forget() {
            pressed = false;
            taken = false;
            dropped = false;
            if (tracker != null) tracker.recycle();
            tracker = null;
        }

        /** What follows the finger: the post window's content, else the whole window. */
        @Nullable
        View moved() {
            View root = window.get();
            if (root == null) return null;
            View content = root.findViewById(android.R.id.content);
            return content != null ? content : root;
        }
    }

    @Nullable private static Card lastCard;
    @Nullable private static Chain chain;
    @Nullable private static Tapped tapped;
    @Nullable private static Drag drag;
    /** The saved post the chain stopped on, with Show saved posts in the row on (#404). Written holding the lock. */
    @Nullable private static volatile PostStop postStop;
    /**
     * The event {@link #touched} read last, with its times and its action as left after the read, and
     * the answer it got. Facebook reuses event objects, so the times and action tell a second read of
     * one event from the next event in the same object. Main thread only.
     */
    @Nullable private static MotionEvent readEvent;
    private static long readDownTime;
    private static long readEventTime;
    private static int readAction;
    private static boolean readAnswer;
    /** The window of the Saved page the chain covered in black, and that page. */
    private static WeakReference<View> cover = new WeakReference<>(null);
    private static WeakReference<Activity> coveredPage = NO_SCREEN;
    private static int coverToken;
    private static WeakReference<Activity> resumed = NO_SCREEN;
    private static WeakReference<Activity> lastCreated = NO_SCREEN;
    private static long lastCreatedAt;
    private static boolean watching;

    private SavedChain() {
    }

    /**
     * Injection point, first thing in Facebook's screens' touch dispatch: keeps the card a finger
     * press lands on, for a viewer that opens from it, and in the chain's viewer takes an up or down
     * swipe from Facebook. True keeps the event from Facebook. Never throws.
     *
     * Facebook 587's Saved page and viewer hand each event to a delegate that runs the base screen's
     * dispatch with the same event, so both hooks read it. The second read gets the first one's
     * answer: read again, the move a swipe was taken on is a cancel by then, which used to end the
     * swipe and leave the rest of it to Facebook's pager (#404).
     */
    public static boolean touched(@Nullable Activity activity, @Nullable MotionEvent event) {
        if (activity == null || event == null) return false;
        if (event == readEvent && event.getDownTime() == readDownTime && event.getEventTime() == readEventTime
                && event.getActionMasked() == readAction) {
            return readAnswer;
        }
        boolean answer = read(activity, event);
        readEvent = event;
        readDownTime = event.getDownTime();
        readEventTime = event.getEventTime();
        readAction = event.getActionMasked();
        readAnswer = answer;
        return answer;
    }

    /** One event's first read, for {@link #touched}. */
    private static boolean read(Activity activity, MotionEvent event) {
        try {
            int action = event.getActionMasked();
            if (action != MotionEvent.ACTION_DOWN) return dragged(activity, event, action);
            Drag old = drag;
            drag = null;
            if (old != null) old.forget();
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, TOUCH_HOOK);
            if (!on()) return false;
            Card card = cardAt(activity, event.getX(), event.getY());
            Chain current;
            synchronized (SavedChain.class) {
                lastCard = card;
                current = chain;
                if (current == null || current.moving || current.viewer.get() != activity || !viewerInFront(current)) {
                    current = null;
                }
            }
            if (current != null && activity.hasWindowFocus()) drag = new Drag(activity, current, event);
            return false;
        } catch (Throwable failure) {
            Drag lost = drag;
            drag = null;
            if (lost != null) {
                lost.forget();
                if (lost.taken) slideBack(lost);
            }
            HookStatus.threw(FAMILY, TOUCH_HOOK, failure);
            return false;
        }
    }

    /**
     * The rest of a finger's way across the chain's viewer. Until it passes the touch slop up or down
     * it's Facebook's. Then Facebook gets a cancel, the video follows the finger, and the lift
     * decides between the next saved video, the one before and staying.
     */
    private static boolean dragged(Activity activity, MotionEvent event, int action) {
        Drag current = drag;
        if (current == null || current.viewer.get() != activity) return false;
        boolean ends = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
        if (current.dropped) {
            if (ends) drag = null;
            return false;
        }
        current.track(event);
        float dx = event.getX() - current.downX;
        float dy = event.getY() - current.downY;
        if (!current.taken) {
            if (ends) {
                drag = null;
                current.forget();
                return false;
            }
            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                drop(current);
                return false;
            }
            if (action != MotionEvent.ACTION_MOVE) return false;
            if (Math.abs(dx) > current.slop && Math.abs(dx) >= Math.abs(dy)) {
                drop(current);
                return false;
            }
            if (Math.abs(dy) <= current.slop || Math.abs(dy) <= Math.abs(dx)) return false;
            synchronized (SavedChain.class) {
                if (chain != current.chain || current.chain.moving || !viewerInFront(current.chain)) {
                    drop(current);
                    return false;
                }
            }
            if (!on()) {
                drop(current);
                return false;
            }
            current.taken = true;
            current.moved = new WeakReference<>(activity.findViewById(android.R.id.content));
            HookStatus.bound(FAMILY, SWIPE_HOOK);
            follow(current, dy);
            event.setAction(MotionEvent.ACTION_CANCEL);
            return false;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            follow(current, dy);
        } else if (action == MotionEvent.ACTION_UP) {
            drag = null;
            follow(current, dy);
            release(current, dy);
        } else if (action == MotionEvent.ACTION_CANCEL) {
            drag = null;
            current.forget();
            slideBack(current);
        }
        return true;
    }

    /** Leaves the rest of the finger's way to Facebook. */
    private static void drop(Drag current) {
        current.dropped = true;
        current.forget();
    }

    /** Moves the video with the finger. */
    private static void follow(Drag current, float dy) {
        View moved = current.moved.get();
        if (moved != null) moved.setTranslationY(dy);
    }

    /** The finger lifted after a taken drag: slide off to the next or previous saved video, or back. */
    private static void release(Drag current, float dy) {
        float speed = current.speed();
        current.forget();
        View moved = current.moved.get();
        Activity viewer = current.viewer.get();
        if (moved == null || viewer == null) return;
        float height = Math.max(1, moved.getHeight());
        float fling = FLING_DP_PER_SECOND * viewer.getResources().getDisplayMetrics().density;
        int direction = 0;
        if (dy < 0 && (-dy > height * SWIPE_SHARE || -speed > fling)) direction = 1;
        else if (dy > 0 && (dy > height * SWIPE_SHARE || speed > fling)) direction = -1;
        if (direction == -1 && !hasPrevious(current.chain)) {
            Logger.printDebug(() -> "Saved videos in a row: a swipe down on the first saved video, so it stays");
            direction = 0;
        }
        if (direction == 0) {
            slideBack(current);
            return;
        }
        int way = direction;
        moved.animate().translationY(-way * height).setDuration(SLIDE_MS);
        Utils.runOnMainThreadDelayed(() -> {
            try {
                if (!leave(current.chain, way > 0 ? "a swipe up" : "a swipe down", way)) slideBack(current);
            } catch (Throwable failure) {
                slideBack(current);
                disarm();
                HookStatus.threw(FAMILY, SWIPE_HOOK, failure);
            }
        }, SLIDE_MS);
    }

    /** Puts the video back where Facebook had it. */
    private static void slideBack(Drag current) {
        View moved = current.moved.get();
        if (moved != null) moved.animate().translationY(0).setDuration(SLIDE_MS);
    }

    /** Whether Saved may hold a card before the chain's: one is shown above it, or the list scrolls up. */
    static boolean hasPrevious(Chain current) {
        return hasPrevious(current.card);
    }

    /** The same, for the chain's [card]. */
    static boolean hasPrevious(Card card) {
        ViewGroup list = card.list.get();
        return list != null && (previousTile(list, card) != null || list.canScrollVertically(-1));
    }

    /**
     * Called as Facebook's Reels viewer opens, with its player origin as text. One from Saved starts
     * a chain from the card pressed just before it. One from anywhere else ends any chain. Never throws.
     */
    public static void viewerOpening(@Nullable String origin) {
        try {
            if (origin == null || !fromSaved(origin)) {
                disarm();
                return;
            }
            Card card;
            boolean byChain;
            synchronized (SavedChain.class) {
                card = lastCard;
                if (card == null && chain != null) return; // The same viewer asking again.
                byChain = tapped != null;
                lastCard = null;
                chain = null;
                tapped = null; // A saved video, so the card the chain tapped was no post.
            }
            if (!on()) return;
            long at = now();
            if (card == null || at - card.at > TOUCH_WINDOW_MS || card.page.get() == null) {
                Logger.printDebug(() -> "Saved videos in a row: a video opened from Saved with no card pressed just before it");
                return;
            }
            HookStatus.bound(FAMILY, CHAIN_HOOK);
            Chain started = new Chain(card, at);
            started.chained = byChain;
            synchronized (SavedChain.class) {
                Activity created = lastCreated.get();
                if (created != null && created != card.page.get() && lastCreatedAt >= card.at) {
                    started.viewer = new WeakReference<>(created);
                }
                chain = started;
            }
            Logger.printDebug(() -> "Saved videos in a row: a video opened from the card at " + card.left + "," + card.top
                    + " (" + card.width + "x" + card.height + ")");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, CHAIN_HOOK, failure);
        }
    }

    /** Injection point's hand-off, as a video ends. In the chain's viewer, that plays the next saved video. Never throws. */
    public static void ended(@Nullable Object player) {
        try {
            Chain current;
            synchronized (SavedChain.class) {
                current = chain;
                if (current == null || current.moving || !viewerInFront(current)) return;
                if (now() - current.openedAt < OPEN_GUARD_MS) return;
            }
            if (Settings.SAVED_VIDEOS_STAY_ON_END.get()) return;
            advance(current, "the video ended");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, CHAIN_HOOK, failure);
        }
    }

    /**
     * Hand-off as a reel takes the Reels viewer's screen. The chain's first is the saved video, and
     * another one later is a swipe on to the next, which plays the next saved video instead. Never throws.
     */
    public static void bound(@Nullable Object player) {
        try {
            if (player == null) return;
            Chain current;
            synchronized (SavedChain.class) {
                current = chain;
                if (current == null || current.moving) return;
                Object held = current.player.get();
                long at = now();
                if (held == null || (held != player && at - current.boundAt < SWIPE_GUARD_MS)) {
                    current.player = new WeakReference<>(player);
                    current.boundAt = at;
                    return;
                }
                if (held == player || !viewerInFront(current)) return;
            }
            advance(current, "a swipe");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, CHAIN_HOOK, failure);
        }
    }

    /**
     * Closes the chain's viewer, on the main thread, so Saved can open the next card. The last saved
     * video closes too: a card after it may sit below the fold, so only Saved can tell, and when there's
     * none the chain ends there rather than in Facebook's suggestions.
     */
    private static void advance(Chain current, String why) {
        if (!on()) {
            disarm();
            return;
        }
        Utils.runOnMainThread(() -> {
            try {
                leave(current, why, 1);
            } catch (Throwable failure) {
                disarm();
                HookStatus.threw(FAMILY, CHAIN_HOOK, failure);
            }
        });
    }

    /**
     * On the main thread: covers the Saved page and closes the chain's viewer with no animation, so
     * Saved taps the card [direction] away from the chain's. False when the viewer stays.
     */
    private static boolean leave(Chain current, String why, int direction) {
        if (!on()) {
            disarm();
            return false;
        }
        Activity viewer;
        synchronized (SavedChain.class) {
            if (chain != current || current.moving) return false;
            viewer = current.viewer.get();
        }
        if (viewer == null || viewer.isFinishing()) return false;
        if (!viewer.hasWindowFocus()) {
            Logger.printDebug(() -> "Saved videos in a row: " + why + " with something over the video, so it stays");
            return false;
        }
        if (current.card.list.get() == null) {
            disarm();
            return false;
        }
        synchronized (SavedChain.class) {
            current.moving = true;
            current.direction = direction;
        }
        Logger.printDebug(() -> "Saved videos in a row: " + why + ", back to Saved for the "
                + (direction > 0 ? "next" : "previous") + " one");
        coverPage(current.card.page.get());
        viewer.finish();
        viewer.overridePendingTransition(0, 0);
        Utils.runOnMainThreadDelayed(() -> giveUp(current), RETURN_WINDOW_MS);
        return true;
    }

    /**
     * On the main thread, [POST_CHECK_MS] after [screen] came in front from a card the chain tapped:
     * with no saved video started from it and [screen] still in front, the card was a post or
     * something else that isn't a video (#404). The chain closes it with no animation, keeps Saved
     * covered, and taps the card after the post once Saved is back, or the one before it after a
     * swipe down. A card the user pressed is never closed, and after [MAX_POSTS_SKIPPED] posts in a
     * row the chain ends there.
     */
    static void closePost(Activity screen, Tapped opened) {
        try {
            Activity page = opened.page.get();
            synchronized (SavedChain.class) {
                if (tapped != opened) return;
                if (page == null || screen == page || screen.isFinishing() || screen != resumed.get()) {
                    tapped = null;
                    return;
                }
            }
            Chain skipped = skip(opened, page, screen);
            if (skipped == null) {
                synchronized (SavedChain.class) {
                    if (chain != null && chain.viewer.get() == screen) return; // A saved video after all.
                }
                SettleCover.lift(screen.getWindow().getDecorView());
                return;
            }
            skipped(opened);
            coverPage(page);
            screen.finish();
            screen.overridePendingTransition(0, 0);
            Utils.runOnMainThreadDelayed(() -> giveUp(skipped), RETURN_WINDOW_MS);
        } catch (Throwable failure) {
            disarm();
            HookStatus.threw(FAMILY, CHAIN_HOOK, failure);
        }
    }

    /**
     * On the main thread, [POST_CHECK_MS] after the chain's tap lifts, and again every [POST_CHECK_MS]
     * until [TOUCH_WINDOW_MS] has passed: with no saved video started from the card, a new dialog
     * window over the Saved page's own screen is the post Facebook opened from it. Facebook 588 shows
     * a saved post that way, in a window over Saved with no screen of its own, so {@link #closePost}
     * never hears of it (#404, S22 2026-11-22). That window's dialog gets the Back a finger on the
     * phone's Back would give it, and once it's gone the card after the post is tapped.
     */
    static void checkPostWindow(Tapped opened) {
        try {
            Activity page;
            synchronized (SavedChain.class) {
                if (tapped != opened || chain != null) return;
                page = opened.page.get();
            }
            if (page == null || page.isFinishing() || !on()) return;
            View post = newDialogOver(page, opened);
            if (post == null) {
                if (now() - opened.at + POST_CHECK_MS <= TOUCH_WINDOW_MS) {
                    Utils.runOnMainThreadDelayed(() -> checkPostWindow(opened), POST_CHECK_MS);
                }
                return;
            }
            if (Settings.SAVED_POSTS_IN_ROW.get() && stopOnPost(opened, page, post)) return;
            Chain skipped = skip(opened, page, page);
            if (skipped == null) return;
            opened.closing = new WeakReference<>(post);
            synchronized (SavedChain.class) {
                skipped.returned = true; // Saved never left: the post was a window over it.
            }
            skipped(opened);
            coverPage(page);
            Dialog dialog = dialogOf(post);
            if (dialog != null) {
                pressBack(dialog);
            } else {
                pressEscape(post);
            }
            long since = now();
            WeakReference<View> window = new WeakReference<>(post);
            WeakReference<Dialog> closing = new WeakReference<>(dialog);
            Utils.runOnMainThreadDelayed(() -> afterPostWindow(skipped, window, closing, since, false), SETTLE_MS);
        } catch (Throwable failure) {
            disarm();
            HookStatus.threw(FAMILY, CHAIN_HOOK, failure);
        }
    }

    /**
     * Taps the card after a post whose window got a Back or an Escape, once it's gone. A window still
     * up at the first look kept that, so its dialog, when there is one, is dismissed ([dismissed]).
     */
    private static void afterPostWindow(Chain skipped, WeakReference<View> window, WeakReference<Dialog> closing,
            long since, boolean dismissed) {
        try {
            synchronized (SavedChain.class) {
                if (chain != skipped) return;
            }
            View post = window.get();
            if (post != null && post.getWindowToken() != null && post.isShown()) {
                if (now() - since < RETURN_WINDOW_MS) {
                    Dialog dialog = closing.get();
                    if (!dismissed && dialog != null) dialog.dismiss();
                    Utils.runOnMainThreadDelayed(() -> afterPostWindow(skipped, window, closing, since, true), SETTLE_MS);
                } else {
                    disarm();
                    SettleCover.lift(post);
                    Logger.printDebug(() -> "Saved videos in a row: the post's window stayed open, so the chain ends there");
                }
                return;
            }
            tapNext(skipped);
        } catch (Throwable failure) {
            disarm();
            HookStatus.threw(FAMILY, CHAIN_HOOK, failure);
        }
    }

    /**
     * With Show saved posts in the row on, keeps the post [opened]'s tap brought up over [page] open
     * instead of closing it (#404): its black comes off, so does Saved's, and its window's touches go
     * to {@link #postTouched} first. False when the post isn't the chain's or its window can't be
     * watched, and then it's closed as before.
     */
    private static boolean stopOnPost(Tapped opened, Activity page, View post) {
        Window window;
        try {
            window = readWindows ? windowField(post) : null;
        } catch (Throwable unreadable) {
            window = null;
        }
        Window.Callback facebooks = window == null ? null : window.getCallback();
        if (facebooks == null) {
            Logger.printDebug(() -> "Saved videos in a row: the post's window can't be watched for a swipe, so it's skipped");
            return false;
        }
        Dialog dialog = dialogOf(post);
        PostStop stop;
        synchronized (SavedChain.class) {
            if (tapped != opened || chain != null) return false;
            Card card = lastCard;
            if (card == null || card.page.get() != page || card.at < opened.at) return false;
            tapped = null;
            lastCard = null;
            stop = new PostStop(card, page, post, dialog);
            postStop = stop;
        }
        if (!Proxy.isProxyClass(facebooks.getClass()) || !(Proxy.getInvocationHandler(facebooks) instanceof PostWatcher)) {
            window.setCallback((Window.Callback) Proxy.newProxyInstance(Window.Callback.class.getClassLoader(),
                    new Class<?>[]{Window.Callback.class}, new PostWatcher(facebooks)));
        }
        uncover();
        SettleCover.lift(post);
        HookStatus.bound(FAMILY, CHAIN_HOOK);
        HookStatus.counted(FAMILY, POST_SHOWN);
        Logger.printDebug(() -> "Saved videos in a row: the card opened a post, which stays until a swipe past its end");
        return true;
    }

    /** Hands a saved post window's touches to {@link #postTouched} first, and every call on to Facebook's callback. */
    static final class PostWatcher implements InvocationHandler {
        final Window.Callback facebooks;

        PostWatcher(Window.Callback facebooks) {
            this.facebooks = facebooks;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (args != null && args.length == 1 && args[0] instanceof MotionEvent
                    && "dispatchTouchEvent".equals(method.getName()) && postTouched((MotionEvent) args[0])) {
                return true;
            }
            try {
                return method.invoke(facebooks, args);
            } catch (InvocationTargetException thrown) {
                throw thrown.getCause();
            }
        }
    }

    /**
     * A finger on the saved post the chain stopped on (#404). While the post can still scroll the way
     * the finger goes, it's Facebook's. Past the post's end, an up or down drag is taken once it passes
     * the touch slop: Facebook gets a cancel, the post follows the finger, and a long or quick enough
     * swipe slides it off to the next saved item, or the one before, while anything shorter slides
     * back. True keeps the event from Facebook. Never throws.
     */
    static boolean postTouched(MotionEvent event) {
        PostStop stop = postStop;
        if (stop == null) return false;
        try {
            View root = stop.window.get();
            if (root == null || !root.isShown() || stop.leaving) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                stop.forget();
                if (!on() || !Settings.SAVED_POSTS_IN_ROW.get()) return false;
                stop.press(event, root);
                return false;
            }
            if (!stop.pressed) return false;
            boolean ends = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
            if (stop.dropped) {
                if (ends) stop.forget();
                return false;
            }
            stop.track(event);
            float dx = event.getX() - stop.downX;
            float dy = event.getY() - stop.downY;
            if (!stop.taken) {
                if (ends) {
                    stop.forget();
                    return false;
                }
                if (action == MotionEvent.ACTION_POINTER_DOWN) {
                    stop.dropped = true;
                    return false;
                }
                if (action != MotionEvent.ACTION_MOVE) return false;
                if (Math.abs(dx) > stop.slop && Math.abs(dx) >= Math.abs(dy)) {
                    stop.dropped = true;
                    return false;
                }
                if (Math.abs(dy) <= stop.slop || Math.abs(dy) <= Math.abs(dx)) return false;
                if (dy < 0 ? stop.scrollsDown : stop.scrollsUp) {
                    stop.dropped = true; // The post still scrolls that way: Facebook's.
                    return false;
                }
                stop.taken = true;
                HookStatus.bound(FAMILY, SWIPE_HOOK);
                followPost(stop, dy);
                event.setAction(MotionEvent.ACTION_CANCEL);
                return false;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                followPost(stop, dy);
            } else if (action == MotionEvent.ACTION_UP) {
                followPost(stop, dy);
                releasePost(stop, dy);
            } else if (action == MotionEvent.ACTION_CANCEL) {
                stop.forget();
                slidePostBack(stop);
            }
            return true;
        } catch (Throwable failure) {
            stop.forget();
            slidePostBack(stop);
            HookStatus.threw(FAMILY, SWIPE_HOOK, failure);
            return false;
        }
    }

    /** Whether a view in [root] that's a third of [window]'s height or more can still scroll [direction] (1 down, -1 up). */
    static boolean postScrolls(View root, View window, int direction) {
        if (!root.isShown()) return false;
        if (root.getHeight() * 3 >= window.getHeight() && root.canScrollVertically(direction)) return true;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (postScrolls(group.getChildAt(i), window, direction)) return true;
            }
        }
        return false;
    }

    /** Moves the post with the finger. */
    private static void followPost(PostStop stop, float dy) {
        View moved = stop.moved();
        if (moved != null) moved.setTranslationY(dy);
    }

    /** Puts the post back where Facebook had it. */
    private static void slidePostBack(PostStop stop) {
        View moved = stop.moved();
        if (moved != null) moved.animate().translationY(0).setDuration(SLIDE_MS);
    }

    /** The finger lifted after a taken drag on the post: slide off to the next or previous saved item, or back. */
    private static void releasePost(PostStop stop, float dy) {
        float speed = stop.speed();
        stop.forget();
        View moved = stop.moved();
        View root = stop.window.get();
        if (moved == null || root == null) return;
        float height = Math.max(1, moved.getHeight());
        float fling = FLING_DP_PER_SECOND * root.getResources().getDisplayMetrics().density;
        int direction = 0;
        if (dy < 0 && (-dy > height * SWIPE_SHARE || -speed > fling)) direction = 1;
        else if (dy > 0 && (dy > height * SWIPE_SHARE || speed > fling)) direction = -1;
        if (direction == -1 && !hasPrevious(stop.card)) {
            Logger.printDebug(() -> "Saved videos in a row: a swipe down on a post with nothing saved before it, so it stays");
            direction = 0;
        }
        if (direction == 0) {
            slidePostBack(stop);
            return;
        }
        int way = direction;
        stop.leaving = true;
        moved.animate().translationY(-way * height).setDuration(SLIDE_MS);
        Utils.runOnMainThreadDelayed(() -> {
            try {
                if (!leavePost(stop, way)) {
                    stop.leaving = false;
                    slidePostBack(stop);
                }
            } catch (Throwable failure) {
                stop.leaving = false;
                slidePostBack(stop);
                disarm();
                HookStatus.threw(FAMILY, SWIPE_HOOK, failure);
            }
        }, SLIDE_MS);
    }

    /**
     * Closes the saved post [stop] and, once it's gone, taps the card [direction] away from it: Saved
     * is covered, the post keeps black as it goes, and its dialog gets the Back the phone's Back would
     * give it. False when the post isn't the chain's any more.
     */
    private static boolean leavePost(PostStop stop, int direction) {
        Activity page = stop.page.get();
        View root = stop.window.get();
        if (page == null || page.isFinishing() || root == null || !root.isShown() || !on()) return false;
        Chain next = new Chain(stop.card, now());
        next.viewer = new WeakReference<>(page);
        next.moving = true;
        next.returned = true; // Saved never left: the post was a window over it.
        next.direction = direction;
        synchronized (SavedChain.class) {
            if (postStop != stop || chain != null) return false;
            postStop = null;
            chain = next;
        }
        Logger.printDebug(() -> "Saved videos in a row: a swipe " + (direction > 0 ? "up" : "down")
                + " past the post, back to Saved for the " + (direction > 0 ? "next" : "previous") + " one");
        coverPage(page);
        SettleCover.blackOut(root);
        Dialog dialog = stop.dialog.get();
        if (dialog != null) {
            pressBack(dialog);
        } else {
            pressEscape(root);
        }
        long since = now();
        WeakReference<View> window = new WeakReference<>(root);
        WeakReference<Dialog> closing = new WeakReference<>(dialog);
        Utils.runOnMainThreadDelayed(() -> afterPostWindow(next, window, closing, since, false), SETTLE_MS);
        return true;
    }

    /**
     * The chain that skips the post [opened]'s tap brought up over [page], shown by [screen], or null
     * when it isn't the chain's to close: a video started, the press wasn't the chain's own, the switch
     * is off, or [MAX_POSTS_SKIPPED] posts in a row were closed already, which ends the chain on this one.
     */
    @Nullable
    private static Chain skip(Tapped opened, Activity page, Activity screen) {
        synchronized (SavedChain.class) {
            if (tapped != opened) return null;
            tapped = null;
            if (chain != null) return null;
            Card card = lastCard;
            if (card == null || card.page.get() != page || card.at < opened.at) return null;
            if (!on()) return null;
            if (opened.postsSkipped >= MAX_POSTS_SKIPPED) {
                Logger.printDebug(() -> "Saved videos in a row: " + MAX_POSTS_SKIPPED
                        + " posts in a row, so it stops on this one");
                return null;
            }
            lastCard = null;
            Chain skipped = new Chain(card, now());
            skipped.viewer = new WeakReference<>(screen);
            skipped.moving = true;
            skipped.direction = opened.direction;
            skipped.postsSkipped = opened.postsSkipped + 1;
            chain = skipped;
            return skipped;
        }
    }

    private static void skipped(Tapped opened) {
        HookStatus.bound(FAMILY, CHAIN_HOOK);
        HookStatus.counted(FAMILY, POST_SKIPPED);
        Logger.printDebug(() -> "Saved videos in a row: the card opened a post, not a video, so it's closed for the "
                + (opened.direction > 0 ? "next" : "previous") + " one");
    }

    /**
     * Every {@link #WINDOW_POLL_MS} from the chain's tap until it's known what the card opened, lays
     * black over any new window over Saved, so a saved post doesn't show while the chain waits to
     * close it (#404: on the S22 one showed for 0.4 s between two videos, 2026-11-22). The checks are
     * asynchronous messages, which Android runs ahead of the frame a new window waits for, so one
     * lands before the post first draws. Once the tap is settled, every window but the post being
     * closed gets its black lifted. Main thread.
     */
    private static void blackOutNewWindows(Tapped opened) {
        Handler handler = asyncMain();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                boolean more = false;
                try {
                    more = blackOutNew(opened);
                } catch (Throwable failure) {
                    HookStatus.threw(FAMILY, CHAIN_HOOK, failure);
                }
                if (more) {
                    handler.postDelayed(this, WINDOW_POLL_MS);
                } else {
                    liftUnclosed(opened);
                }
            }
        }, WINDOW_POLL_MS);
    }

    @Nullable private static Handler asyncMain;

    /** A main-thread handler whose messages don't wait behind a frame. */
    private static Handler asyncMain() {
        Handler handler = asyncMain;
        if (handler == null) asyncMain = handler = Handler.createAsync(Looper.getMainLooper());
        return handler;
    }

    /** Blacks out each window that came over Saved after [opened]. False once that tap is settled. */
    private static boolean blackOutNew(Tapped opened) {
        Activity page;
        synchronized (SavedChain.class) {
            if (tapped != opened) return false;
            page = opened.page.get();
        }
        if (page == null || page.isFinishing() || !on() || now() - opened.at > TOUCH_WINDOW_MS) return false;
        for (View window : windowsOver(page, false)) {
            if (opened.hadBefore(window) || SettleCover.blackedOut(window)) continue;
            SettleCover.blackOut(window);
            opened.blackedOut.add(new WeakReference<>(window));
        }
        return true;
    }

    /** Lifts the black off the windows [opened]'s tap brought up, all but the post the chain is closing. */
    private static void liftUnclosed(Tapped opened) {
        View closing = opened.closing.get();
        for (WeakReference<View> held : opened.blackedOut) {
            View window = held.get();
            if (window != null && window != closing) SettleCover.lift(window);
        }
        opened.blackedOut.clear();
    }

    /** Whether [screen], just created, came from the chain's own tap: it stays black while it settles. Holding the lock. */
    private static boolean openedByChain(Activity screen) {
        Tapped card = tapped;
        if (card != null && card.page.get() != screen && now() - card.at <= TOUCH_WINDOW_MS) return true;
        Chain current = chain;
        return current != null && current.chained && !current.moving && current.viewer.get() == screen
                && screen != current.card.page.get();
    }

    /**
     * The dialog windows showing over [page]'s screen: windows of the plain application type that
     * belong to it, by its token or by the screen their views were made for. Empty when the phone
     * won't list its windows.
     */
    static List<View> dialogsOver(Activity page) {
        return windowsOver(page, true);
    }

    /** The same, with [shownOnly] false also a window Android hasn't laid out or drawn yet. */
    private static List<View> windowsOver(Activity page, boolean shownOnly) {
        List<View> found = new ArrayList<>();
        Window window = page.getWindow();
        List<View> roots = AppLock.windowsForDiagnostics();
        if (window == null || roots == null) return found;
        IBinder token = window.getAttributes().token;
        View base = window.getDecorView();
        for (View root : roots) {
            if (root == null || root == base) continue;
            if (shownOnly && (root.getWindowToken() == null || !root.isShown())) continue;
            ViewGroup.LayoutParams params = root.getLayoutParams();
            if (!(params instanceof WindowManager.LayoutParams)) continue;
            WindowManager.LayoutParams attributes = (WindowManager.LayoutParams) params;
            if (attributes.type != WindowManager.LayoutParams.TYPE_APPLICATION) continue;
            if ((token != null && attributes.token == token) || activityOf(root.getContext()) == page) found.add(root);
        }
        return found;
    }

    /** The screen [context] was made for, unwrapping wrappers, or null. */
    @Nullable
    private static Activity activityOf(@Nullable Context context) {
        for (int i = 0; context != null && i < 10; i++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /** The dialog window over [page] that came after the chain's tap and holds the focus, or null. */
    @Nullable
    private static View newDialogOver(Activity page, Tapped opened) {
        List<View> dialogs = dialogsOver(page);
        for (int i = dialogs.size() - 1; i >= 0; i--) {
            View dialog = dialogs.get(i);
            if (opened.hadBefore(dialog)) continue;
            if (dialog.hasWindowFocus() || !page.hasWindowFocus()) return dialog;
        }
        return null;
    }

    /**
     * Gives [dialog] the Back the phone's Back button would. Facebook's windows take Back through
     * Android's back callbacks, so a Back key handed to the window's views does nothing there (S22
     * 2026-11-22), and an app can't send keys through the phone's input.
     */
    @SuppressWarnings("deprecation")
    private static void pressBack(Dialog dialog) {
        dialog.onBackPressed();
    }

    /**
     * Hands the window whose top view is [root] a press and lift of Escape, for a post window whose
     * dialog can't be read. Since Android 14 a dialog cancels on Escape, a keyboard's way out, even
     * where Back goes through Android's back callbacks.
     */
    private static void pressEscape(View root) {
        long at = SystemClock.uptimeMillis();
        root.dispatchKeyEvent(new KeyEvent(at, at, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, 0));
        root.dispatchKeyEvent(new KeyEvent(at, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE, 0));
    }

    /** False to read no window's `mWindow`, as on a phone that refuses it. For tests. */
    static volatile boolean readWindows = true;

    /**
     * The dialog whose window [root] is the top view of, or null. Android keeps that window in the
     * top view's `mWindow`, the field LeakCanary reads for the same reason, and the window reports to
     * the dialog, sometimes through a wrapper that holds it. Why there's none is logged.
     */
    @Nullable
    static Dialog dialogOf(View root) {
        String why;
        try {
            Window window = readWindows ? windowField(root) : null;
            if (window == null) {
                why = "no window in its " + root.getClass().getName();
            } else {
                Object callback = window.getCallback();
                why = "its window reports to " + (callback == null ? "nothing" : callback.getClass().getName());
                for (int i = 0; callback != null && i < 4; i++) {
                    if (callback instanceof Dialog) return (Dialog) callback;
                    callback = heldCallback(callback);
                }
            }
        } catch (Throwable unreadable) {
            why = "its window couldn't be read: " + unreadable;
        }
        String reason = why;
        Logger.printDebug(() -> "Saved videos in a row: the post's window has no dialog to close, " + reason);
        return null;
    }

    @Nullable
    private static Window windowField(View root) throws IllegalAccessException {
        for (Class<?> type = root.getClass(); type != null && type != View.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField("mWindow");
                field.setAccessible(true);
                Object value = field.get(root);
                return value instanceof Window ? (Window) value : null;
            } catch (NoSuchFieldException notHere) {
                // A parent class may declare it, or the phone keeps it from apps.
            }
        }
        return null;
    }

    /**
     * What window callback [wrapper] passes things on to: a proxy's handler (Facebook 588 gives a
     * dialog's window one, S22 2026-11-22), a field holding a dialog, else one holding another
     * callback, or null.
     */
    @Nullable
    private static Object heldCallback(Object wrapper) throws IllegalAccessException {
        if (Proxy.isProxyClass(wrapper.getClass())) return Proxy.getInvocationHandler(wrapper);
        Object held = null;
        for (Class<?> type = wrapper.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                field.setAccessible(true);
                Object value = field.get(wrapper);
                if (value == null || value == wrapper) continue;
                if (value instanceof Dialog) return value;
                if (held == null && value instanceof Window.Callback) held = value;
            }
        }
        return held;
    }

    /** Ends a chain whose Saved page never came back. */
    private static void giveUp(Chain current) {
        synchronized (SavedChain.class) {
            if (chain != current || current.returned) return;
            chain = null;
        }
        uncover();
        Logger.printDebug(() -> "Saved videos in a row: the Saved page didn't come back");
    }

    /**
     * Lays black over everything [page]'s window draws, so it doesn't show between one saved video
     * and the next. Main thread. It's drawn over the window's views rather than being one of them,
     * so touches pass through it, and Facebook laying its page out again on top of a view added
     * after it doesn't show the page (S22 2026-11-22: the grid showed for 80 ms as a post opened).
     */
    static void coverPage(@Nullable Activity page) {
        if (page == null) return;
        Window window = page.getWindow();
        if (window == null) return;
        uncover();
        View decor = window.getDecorView();
        SettleCover.blackOut(decor);
        cover = new WeakReference<>(decor);
        coveredPage = new WeakReference<>(page);
        int token = ++coverToken;
        Utils.runOnMainThreadDelayed(() -> {
            if (coverToken == token) uncover();
        }, COVER_MS);
    }

    /** Takes the cover off the Saved page, if one is on. Main thread. */
    static void uncover() {
        View decor = cover.get();
        cover = new WeakReference<>(null);
        coveredPage = NO_SCREEN;
        coverToken++;
        SettleCover.lift(decor);
    }

    /** Takes the cover off after [delay], unless another one went on meanwhile. Main thread. */
    private static void uncoverAfter(long delay) {
        if (cover.get() == null) return;
        int token = coverToken;
        Utils.runOnMainThreadDelayed(() -> {
            if (coverToken == token) uncover();
        }, delay);
    }


    /**
     * Taps the card after the chain's, or the one before it after a swipe down, once the Saved page
     * is back, scrolling to it first when it has to.
     */
    static void tapNext(Chain current) {
        try {
            synchronized (SavedChain.class) {
                if (chain != current) return;
            }
            if (!on()) {
                disarm();
                return;
            }
            Card card = current.card;
            Activity page = card.page.get();
            ViewGroup list = card.list.get();
            if (page == null || list == null || page.isFinishing() || list.getWindowToken() == null) {
                disarm();
                return;
            }
            boolean back = current.direction < 0;
            View next = back ? previousTile(list, card) : nextTile(list, card);
            if (next == null) {
                int step = Math.max(1, list.getHeight() / 2);
                if (current.scrolls < MAX_SCROLLS && scroll(list, card, back ? -step : step)) {
                    current.scrolls++;
                    Utils.runOnMainThreadDelayed(() -> tapNext(current), SCROLL_SETTLE_MS);
                    return;
                }
                disarm();
                Logger.printDebug(() -> back
                        ? "Saved videos in a row: no card before the one played, so it ends on Saved"
                        : "Saved videos in a row: no card after the last one played, so it ends on Saved");
                return;
            }
            float[] point = tapPoint(next);
            Rect visible = new Rect();
            if (!list.getGlobalVisibleRect(visible)) {
                disarm();
                return;
            }
            float edge = visible.height() * EDGE_SHARE;
            boolean inView = point[1] >= visible.top + edge && point[1] <= visible.bottom - edge
                    && point[0] >= visible.left && point[0] <= visible.right;
            if (!inView) {
                int dy = Math.round(point[1] - visible.exactCenterY());
                if (dy != 0 && current.scrolls < MAX_SCROLLS && scroll(list, card, dy)) {
                    current.scrolls++;
                    Utils.runOnMainThreadDelayed(() -> tapNext(current), SCROLL_SETTLE_MS);
                    return;
                }
                if (!visible.contains(Math.round(point[0]), Math.round(point[1]))) {
                    disarm();
                    Logger.printDebug(() -> "Saved videos in a row: the next card couldn't be scrolled into view");
                    return;
                }
            }
            List<View> before = dialogsOver(page);
            Tapped opened;
            synchronized (SavedChain.class) {
                if (chain != current) return;
                chain = null; // The viewer the tap opens starts the next chain.
                opened = new Tapped(page, now(), back ? -1 : 1, current.postsSkipped, before);
                tapped = opened;
            }
            int left = next.getLeft();
            int top = next.getTop();
            tap(page, point[0], point[1]);
            blackOutNewWindows(opened);
            Utils.runOnMainThreadDelayed(() -> checkPostWindow(opened), PRESS_MS + POST_CHECK_MS);
            HookStatus.counted(FAMILY, back ? PREVIOUS : NEXT);
            Logger.printDebug(() -> "Saved videos in a row: tapped the " + (back ? "previous" : "next") + " card, at "
                    + left + "," + top);
        } catch (Throwable failure) {
            disarm();
            HookStatus.threw(FAMILY, CHAIN_HOOK, failure);
        }
    }

    /** The card after [card] among [list]'s children, or null when none of them is. */
    @Nullable
    static View nextTile(ViewGroup list, Card card) {
        List<View> tiles = tiles(list, card.width);
        int at = labelled(tiles, card);
        if (at >= 0) return at + 1 < tiles.size() ? tiles.get(at + 1) : null;
        for (View tile : tiles) {
            int top = tile.getTop();
            if (top > card.top + SAME_PX) return tile;
            if (Math.abs(top - card.top) <= SAME_PX && tile.getLeft() > card.left + SAME_PX) return tile;
        }
        return null;
    }

    /** The card before [card] among [list]'s children, or null when none of them is. */
    @Nullable
    static View previousTile(ViewGroup list, Card card) {
        List<View> tiles = tiles(list, card.width);
        int at = labelled(tiles, card);
        if (at >= 0) return at > 0 ? tiles.get(at - 1) : null;
        View before = null;
        for (View tile : tiles) {
            int top = tile.getTop();
            if (top < card.top - SAME_PX || (Math.abs(top - card.top) <= SAME_PX && tile.getLeft() < card.left - SAME_PX)) {
                before = tile;
            }
        }
        return before;
    }

    /** Where among [tiles] [card] is by its label, the nearest when several share it, or -1. */
    private static int labelled(List<View> tiles, Card card) {
        if (card.label == null) return -1;
        int at = -1;
        int nearest = Integer.MAX_VALUE;
        for (int i = 0; i < tiles.size(); i++) {
            View tile = tiles.get(i);
            if (!card.label.equals(labelOf(tile))) continue;
            int distance = Math.abs(tile.getTop() - card.top) + Math.abs(tile.getLeft() - card.left);
            if (distance < nearest) {
                nearest = distance;
                at = i;
            }
        }
        return at;
    }

    /** [list]'s shown children [width] wide, in reading order: down by top, then left to right. */
    static List<View> tiles(ViewGroup list, int width) {
        List<View> tiles = new ArrayList<>();
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child == null || child.getVisibility() != View.VISIBLE) continue;
            if (Math.abs(child.getWidth() - width) <= SAME_PX) tiles.add(child);
        }
        Collections.sort(tiles, (a, b) -> a.getTop() != b.getTop()
                ? Integer.compare(a.getTop(), b.getTop())
                : Integer.compare(a.getLeft(), b.getLeft()));
        return tiles;
    }

    /**
     * Scrolls [list], or the first view around it that can scroll that way, by [dy], and moves the
     * card's place in the list by however far the list's children actually moved.
     */
    static boolean scroll(ViewGroup list, Card card, int dy) {
        View scroller = scroller(list, dy > 0 ? 1 : -1);
        if (scroller == null) return false;
        View reference = list.getChildCount() > 0 ? list.getChildAt(0) : null;
        int before = reference == null ? 0 : reference.getTop();
        scroller.scrollBy(0, dy);
        int moved;
        if (reference != null && reference.getParent() == list) moved = before - reference.getTop();
        else moved = scroller == list ? dy : 0;
        card.top -= moved;
        Logger.printDebug(() -> "Saved videos in a row: scrolled " + dy + " to find the next card");
        return true;
    }

    /** [list] when it scrolls [direction] (1 down, -1 up), else the nearest view around it that does. */
    @Nullable
    static View scroller(View list, int direction) {
        View view = list;
        while (view != null) {
            if (view.canScrollVertically(direction)) return view;
            ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    /** Where in the window to tap [tile]: across its middle, {@link #TAP_DOWN} of the way down. */
    static float[] tapPoint(View tile) {
        int[] at = new int[2];
        tile.getLocationInWindow(at);
        return new float[]{at[0] + tile.getWidth() / 2f, at[1] + tile.getHeight() * TAP_DOWN};
    }

    /** A finger press and lift at ([x], [y]) in [page]'s window, through the same dispatch a real one takes. */
    static void tap(Activity page, float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent press = finger(down, down, MotionEvent.ACTION_DOWN, x, y);
        try {
            page.dispatchTouchEvent(press);
        } finally {
            press.recycle();
        }
        Utils.runOnMainThreadDelayed(() -> {
            MotionEvent lift = finger(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y);
            try {
                page.dispatchTouchEvent(lift);
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, CHAIN_HOOK, failure);
            } finally {
                lift.recycle();
            }
        }, PRESS_MS);
    }

    private static MotionEvent finger(long down, long at, int action, float x, float y) {
        MotionEvent.PointerProperties[] properties = {new MotionEvent.PointerProperties()};
        properties[0].id = 0;
        properties[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
        coords[0].x = x;
        coords[0].y = y;
        coords[0].pressure = 1f;
        coords[0].size = 1f;
        return MotionEvent.obtain(down, at, action, 1, properties, coords, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    /**
     * The card under ([x], [y]) in [activity]'s window: the deepest view on the way down that's a
     * child of a scrolling list with another shown child the same width. Null when there's none.
     */
    @Nullable
    static Card cardAt(Activity activity, float x, float y) {
        Window window = activity.getWindow();
        View view = window == null ? null : window.peekDecorView();
        if (view == null) return null;
        List<View> path = new ArrayList<>();
        path.add(view);
        float px = x;
        float py = y;
        while (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            View hit = null;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child = group.getChildAt(i);
                if (child == null || child.getVisibility() != View.VISIBLE) continue;
                float cx = px + group.getScrollX() - child.getLeft() - child.getTranslationX();
                float cy = py + group.getScrollY() - child.getTop() - child.getTranslationY();
                if (cx >= 0 && cy >= 0 && cx < child.getWidth() && cy < child.getHeight()) {
                    hit = child;
                    px = cx;
                    py = cy;
                    break;
                }
            }
            if (hit == null) break;
            path.add(hit);
            view = hit;
        }
        for (int i = path.size() - 2; i >= 0; i--) {
            View parent = path.get(i);
            View child = path.get(i + 1);
            if (parent instanceof ViewGroup && isList(parent) && hasTwin((ViewGroup) parent, child)) {
                return new Card(activity, (ViewGroup) parent, child, now());
            }
        }
        return null;
    }

    /** Whether [view] is a list: it scrolls up or down, or it's a RecyclerView, ScrollView or ListView of some kind. */
    static boolean isList(View view) {
        if (view.canScrollVertically(1) || view.canScrollVertically(-1)) return true;
        for (Class<?> type = view.getClass(); type != null && type != View.class; type = type.getSuperclass()) {
            String name = type.getSimpleName();
            if (name.endsWith("RecyclerView") || name.endsWith("ScrollView") || name.endsWith("ListView")) return true;
        }
        return false;
    }

    /** Whether [list] shows another child as wide as [child], somewhere else. */
    static boolean hasTwin(ViewGroup list, View child) {
        if (child.getWidth() <= 0) return false;
        for (int i = 0; i < list.getChildCount(); i++) {
            View other = list.getChildAt(i);
            if (other == null || other == child || other.getVisibility() != View.VISIBLE) continue;
            if (Math.abs(other.getWidth() - child.getWidth()) > SAME_PX) continue;
            if (other.getLeft() != child.getLeft() || other.getTop() != child.getTop()) return true;
        }
        return false;
    }

    /** [view]'s description, or the first one inside it. Only ever compared. */
    @Nullable
    static String labelOf(@Nullable View view) {
        if (view == null) return null;
        CharSequence text = view.getContentDescription();
        if (text != null && text.length() > 0) return text.toString();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                String inner = labelOf(group.getChildAt(i));
                if (inner != null) return inner;
            }
        }
        return null;
    }

    /** Whether [origin], a player origin as text, names Saved as its surface. */
    static boolean fromSaved(String origin) {
        int cut = origin.indexOf("::");
        return SAVED_SURFACE.equals(cut < 0 ? origin : origin.substring(0, cut));
    }

    /** Whether the chain's viewer is the screen in front. Called holding the lock. */
    private static boolean viewerInFront(Chain current) {
        Activity viewer = current.viewer.get();
        return viewer != null && viewer == resumed.get() && !viewer.isFinishing();
    }

    private static void disarm() {
        synchronized (SavedChain.class) {
            chain = null;
            tapped = null;
            postStop = null;
        }
        if (cover.get() != null) uncoverAfter(0);
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.SAVED_VIDEOS_IN_ORDER.get();
    }

    static long now() {
        LongSupplier clock = clockForTests;
        return clock != null ? clock.getAsLong() : SystemClock.uptimeMillis();
    }

    /**
     * Called once per Facebook start, from the settings entry's onApplicationCreate, with Clean up
     * Reels in the build: from then on it knows which screen is in front and when the Saved page comes back.
     */
    public static synchronized void watch(Context context) {
        try {
            if (watching || !(context instanceof Application)) return;
            ((Application) context).registerActivityLifecycleCallbacks(new Screens());
            watching = true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, CHAIN_HOOK, failure);
        }
    }

    /** Follows the chain's viewer and Saved page as screens open, come back and close. */
    static final class Screens implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity activity, @Nullable Bundle state) {
            boolean settle;
            synchronized (SavedChain.class) {
                lastCreated = new WeakReference<>(activity);
                lastCreatedAt = now();
                Chain current = chain;
                if (current != null && !current.moving && current.viewer.get() == null
                        && activity != current.card.page.get() && now() - current.openedAt <= SCREEN_WINDOW_MS) {
                    current.viewer = new WeakReference<>(activity);
                }
                settle = on() && openedByChain(activity);
            }
            if (!settle) return;
            try {
                SettleCover.untilSettled(activity);
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, CHAIN_HOOK, failure);
            }
        }

        @Override
        public void onActivityResumed(Activity activity) {
            Chain back = null;
            synchronized (SavedChain.class) {
                resumed = new WeakReference<>(activity);
                Chain current = chain;
                if (current != null && activity == current.card.page.get()) {
                    if (current.moving && !current.returned) {
                        current.returned = true;
                        back = current;
                    } else if (!current.moving) {
                        chain = null; // Back from the viewer.
                    }
                }
            }
            View black = cover.get();
            if (black != null && back == null) {
                // The next video's screen in front keeps the cover while it opens. Saved back with
                // nothing to tap shows at once.
                if (coveredPage.get() == activity) uncover();
                else uncoverAfter(UNCOVER_MS);
            }
            if (back != null) {
                Chain next = back;
                Utils.runOnMainThreadDelayed(() -> tapNext(next), SETTLE_MS);
            }
            Tapped card;
            synchronized (SavedChain.class) {
                card = tapped;
                if (card != null && (chain != null || activity == card.page.get() || now() - card.at > TOUCH_WINDOW_MS)) {
                    card = null;
                }
            }
            if (card != null) {
                Tapped opened = card;
                Utils.runOnMainThreadDelayed(() -> closePost(activity, opened), POST_CHECK_MS);
            }
        }

        @Override
        public void onActivityPaused(Activity activity) {
            synchronized (SavedChain.class) {
                if (resumed.get() == activity) resumed = NO_SCREEN;
            }
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle state) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            synchronized (SavedChain.class) {
                Chain current = chain;
                if (current == null) return;
                if (activity == current.card.page.get()) chain = null;
                else if (activity == current.viewer.get() && !current.moving) chain = null;
            }
        }
    }

    /** Forgets every press and chain, as a new Facebook process would. For tests. */
    static synchronized void resetForTests() {
        lastCard = null;
        chain = null;
        tapped = null;
        postStop = null;
        asyncMain = null;
        if (drag != null) drag.forget();
        drag = null;
        readEvent = null;
        cover = new WeakReference<>(null);
        coveredPage = NO_SCREEN;
        coverToken = 0;
        resumed = NO_SCREEN;
        lastCreated = NO_SCREEN;
        lastCreatedAt = 0;
    }

    /** The chain in progress, for tests. */
    @Nullable
    static synchronized Chain chainForTests() {
        return chain;
    }
}
