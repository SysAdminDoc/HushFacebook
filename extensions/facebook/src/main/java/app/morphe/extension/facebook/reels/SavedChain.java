/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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
 * ({@link #ended}) or a second reel taking its screen ({@link #bound}, a swipe up) closes it. Once
 * the Saved page is back, the card after the one played (by its label when it has one, else its
 * place in the grid, left to right and then down) is scrolled into view if it has to be and tapped,
 * and the viewer that opens starts the chain again. The last saved video closes as well, and with
 * no card after it the chain ends on Saved. Back from the viewer, another viewer opening from anywhere but Saved, the
 * Saved page closing, a page that doesn't come back within {@link #RETURN_WINDOW_MS}, the switch
 * going off and Pause each end the chain.
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

        Chain(Card card, long openedAt) {
            this.card = card;
            this.openedAt = openedAt;
        }
    }

    @Nullable private static Card lastCard;
    @Nullable private static Chain chain;
    private static WeakReference<Activity> resumed = NO_SCREEN;
    private static WeakReference<Activity> lastCreated = NO_SCREEN;
    private static long lastCreatedAt;
    private static boolean watching;

    private SavedChain() {
    }

    /**
     * Injection point, first thing in Facebook's screens' touch dispatch: keeps the card a finger
     * press lands on, for a viewer that opens from it. Never throws.
     */
    public static void touched(@Nullable Activity activity, @Nullable MotionEvent event) {
        try {
            if (activity == null || event == null || event.getActionMasked() != MotionEvent.ACTION_DOWN) return;
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, TOUCH_HOOK);
            if (!on()) return;
            Card card = cardAt(activity, event.getX(), event.getY());
            synchronized (SavedChain.class) {
                lastCard = card;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, TOUCH_HOOK, failure);
        }
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
                Activity viewer;
                synchronized (SavedChain.class) {
                    if (chain != current || current.moving) return;
                    viewer = current.viewer.get();
                }
                if (viewer == null || viewer.isFinishing()) return;
                if (!viewer.hasWindowFocus()) {
                    Logger.printDebug(() -> "Saved videos in a row: " + why + " with something over the video, so it stays");
                    return;
                }
                if (current.card.list.get() == null) {
                    disarm();
                    return;
                }
                synchronized (SavedChain.class) {
                    current.moving = true;
                }
                Logger.printDebug(() -> "Saved videos in a row: " + why + ", back to Saved for the next one");
                viewer.finish();
                Utils.runOnMainThreadDelayed(() -> giveUp(current), RETURN_WINDOW_MS);
            } catch (Throwable failure) {
                disarm();
                HookStatus.threw(FAMILY, CHAIN_HOOK, failure);
            }
        });
    }

    /** Ends a chain whose Saved page never came back. */
    private static void giveUp(Chain current) {
        synchronized (SavedChain.class) {
            if (chain != current || current.returned) return;
            chain = null;
        }
        Logger.printDebug(() -> "Saved videos in a row: the Saved page didn't come back");
    }

    /** Taps the card after the chain's, once the Saved page is back, scrolling to it first when it has to. */
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
            View next = nextTile(list, card);
            if (next == null) {
                if (current.scrolls < MAX_SCROLLS && scroll(list, card, Math.max(1, list.getHeight() / 2))) {
                    current.scrolls++;
                    Utils.runOnMainThreadDelayed(() -> tapNext(current), SCROLL_SETTLE_MS);
                    return;
                }
                disarm();
                Logger.printDebug(() -> "Saved videos in a row: no card after the last one played, so it ends on Saved");
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
            HookStatus.counted(FAMILY, NEXT);
            Logger.printDebug(() -> "Saved videos in a row: tapped the next card, at " + left + "," + top);
        } catch (Throwable failure) {
            disarm();
            HookStatus.threw(FAMILY, CHAIN_HOOK, failure);
        }
    }

    /** The card after [card] among [list]'s children, or null when none of them is. */
    @Nullable
    static View nextTile(ViewGroup list, Card card) {
        List<View> tiles = tiles(list, card.width);
        int at = -1;
        if (card.label != null) {
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
        }
        if (at >= 0) return at + 1 < tiles.size() ? tiles.get(at + 1) : null;
        for (View tile : tiles) {
            int top = tile.getTop();
            if (top > card.top + SAME_PX) return tile;
            if (Math.abs(top - card.top) <= SAME_PX && tile.getLeft() > card.left + SAME_PX) return tile;
        }
        return null;
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
        }
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
                if (current == null || activity != current.card.page.get()) return;
                if (current.moving && !current.returned) {
                    current.returned = true;
                    back = current;
                } else if (!current.moving) {
                    chain = null; // Back from the viewer.
                }
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
