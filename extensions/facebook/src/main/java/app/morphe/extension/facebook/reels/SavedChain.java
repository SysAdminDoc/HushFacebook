/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.VelocityTracker;
import android.view.ViewParent;
import android.view.Window;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;

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
 * after it the chain ends on Saved. Back from the viewer, another viewer opening from anywhere but
 * Saved, the Saved page closing, a page that doesn't come back within {@link #RETURN_WINDOW_MS},
 * the switch going off and Pause each end the chain.
 *
 * <p>Facebook's own swipe showed a suggested reel before the viewer closed, and a swipe down had
 * nothing to go back to (#404, a reporter's video). So in the chain's viewer an up or down drag is
 * taken from Facebook once it passes the touch slop: Facebook gets a cancel, the video follows the
 * finger, and a long enough or quick enough swipe slides it off and plays the next saved video, or
 * the one before for a swipe down, while anything shorter slides back. A swipe down on the first
 * card slides back too. While the viewer closes and Saved taps the next card, a black cover lies
 * over the Saved page, taken off once the next video's screen is in front or the chain ends. With
 * Stay on a saved video when it ends on, an end leaves the video where Facebook does and only a
 * swipe moves on.
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

        Chain(Card card, long openedAt) {
            this.card = card;
            this.openedAt = openedAt;
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

    @Nullable private static Card lastCard;
    @Nullable private static Chain chain;
    @Nullable private static Drag drag;
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
    private static WeakReference<View> cover = new WeakReference<>(null);
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
        ViewGroup list = current.card.list.get();
        return list != null && (previousTile(list, current.card) != null || list.canScrollVertically(-1));
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
            synchronized (SavedChain.class) {
                card = lastCard;
                if (card == null && chain != null) return; // The same viewer asking again.
                lastCard = null;
                chain = null;
            }
            if (!on()) return;
            long at = now();
            if (card == null || at - card.at > TOUCH_WINDOW_MS || card.page.get() == null) {
                Logger.printDebug(() -> "Saved videos in a row: a video opened from Saved with no card pressed just before it");
                return;
            }
            HookStatus.bound(FAMILY, CHAIN_HOOK);
            Chain started = new Chain(card, at);
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
     * Lays black over [page]'s content, so it doesn't show between one saved video and the next.
     * Main thread. Touches pass through it, and {@link #cardAt} looks past it.
     */
    static void coverPage(@Nullable Activity page) {
        if (page == null) return;
        View content = page.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;
        uncover();
        View black = new View(page);
        black.setBackgroundColor(Color.BLACK);
        black.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ((ViewGroup) content).addView(black, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cover = new WeakReference<>(black);
        int token = ++coverToken;
        Utils.runOnMainThreadDelayed(() -> {
            if (coverToken == token) uncover();
        }, COVER_MS);
    }

    /** Takes the cover off the Saved page, if one is on. Main thread. */
    static void uncover() {
        View black = cover.get();
        cover = new WeakReference<>(null);
        coverToken++;
        if (black == null) return;
        ViewParent parent = black.getParent();
        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(black);
    }

    /** Takes the cover off after [delay], unless another one went on meanwhile. Main thread. */
    private static void uncoverAfter(long delay) {
        if (cover.get() == null) return;
        int token = coverToken;
        Utils.runOnMainThreadDelayed(() -> {
            if (coverToken == token) uncover();
        }, delay);
    }

    private static boolean isCover(View view) {
        return view == cover.get();
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
            synchronized (SavedChain.class) {
                if (chain != current) return;
                chain = null; // The viewer the tap opens starts the next chain.
            }
            int left = next.getLeft();
            int top = next.getTop();
            tap(page, point[0], point[1]);
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
                if (child == null || child.getVisibility() != View.VISIBLE || isCover(child)) continue;
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
        }
        if (cover.get() != null) uncoverAfter(0);
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.SAVED_VIDEOS_IN_ORDER.get();
    }

    private static long now() {
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
            synchronized (SavedChain.class) {
                lastCreated = new WeakReference<>(activity);
                lastCreatedAt = now();
                Chain current = chain;
                if (current != null && !current.moving && current.viewer.get() == null
                        && activity != current.card.page.get() && now() - current.openedAt <= SCREEN_WINDOW_MS) {
                    current.viewer = new WeakReference<>(activity);
                }
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
                if (black.getContext() == activity) uncover();
                else uncoverAfter(UNCOVER_MS);
            }
            if (back != null) {
                Chain next = back;
                Utils.runOnMainThreadDelayed(() -> tapNext(next), SETTLE_MS);
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
        if (drag != null) drag.forget();
        drag = null;
        readEvent = null;
        cover = new WeakReference<>(null);
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
