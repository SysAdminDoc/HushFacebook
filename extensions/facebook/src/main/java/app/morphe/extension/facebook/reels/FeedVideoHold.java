/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Hold a feed video for 2x (issue #229): a video playing in a feed, Home's or Watch's, plays at
 * double speed while you hold it, and goes back to the speed it had when you let go.
 *
 * <p>Facebook's own hold lives in its full-screen video player and in Reels' controls; a video
 * playing in a feed has no long press at all. So this one times the press itself, the way
 * {@link PostLongPress} does for feed posts. Every touch on a Facebook screen comes here first, from
 * FbFragmentActivity's dispatchTouchEvent. With the switch on, a finger going down starts a timer of
 * the system's long-press length, and moving past the touch slop, a second finger, a lift or a
 * cancel stops it.
 *
 * <p>The video under the finger is found through the players themselves. FbGrootPlayer hands
 * {@link #attached} each player as it attaches its view container, and {@link #playerView} reads the
 * container a player has now, so a player Facebook moved to another video is judged where it is.
 * When the timer runs out, the press must sit inside a shown container of a player whose nearest
 * list scrolls up and down, as a feed does, on a row smaller than that list: a reel's full-size page
 * has Hold a reel for 2x, and a video in a carousel, a pager or the feed's sideways Reels row is left
 * alone. Then the player's speed is read, the player goes to 2x, the phone gives the long-press
 * buzz, and the rest of the gesture goes to Facebook as a cancel, so the lift doesn't also open the
 * video. The lift or a cancel puts back the speed it had. A video already at 2x or faster, and a
 * press anywhere else, are Facebook's.
 *
 * <p>Facebook 585 has a hold of its own on feed videos, and its timer can run out first (#387). So
 * the press notes the speed of the video under it as it goes down, and a video that only went fast
 * during the press is still this hold's, at the speed it had before. Facebook's hold puts its own
 * speed back when the rest of the gesture reaches it as a cancel, so after each cancel this one
 * plays the held video at 2x again ({@link #reassert}).
 *
 * <p>Pulling down before the lift keeps the video at 2x with no finger on it (#229's follow-up). A tap
 * on that video puts its speed back without opening it. A scroll that starts on it still scrolls. The
 * lock ends with the video: when its container leaves the window, when its player moves to another
 * container, when the window loses focus, when another video is held, or at the next touch once the
 * switch is off or paused. It only ever sets the player, so the saved video speed never hears of it.
 *
 * <p>With Long press opens comments on too, a hold on a feed video is this one's, and the rest of
 * the post opens comments ({@link #takes}). Off, paused, before the settings are ready, without the
 * patch in the build, or when anything here fails, touches go through untouched. A hold that already
 * sped a video up always puts its speed back.
 */
public final class FeedVideoHold {
    private static final String FAMILY = FamilyNames.HOLD_REEL_FOR_2X;

    /** Counted for each feed video a hold sped up. */
    static final String HELD = "feed video held";

    /** Counted for each hold on a feed video that already played at the hold speed or faster. */
    static final String ALREADY_FAST = "feed video already at 2x or faster";

    /** Counted for each feed video a pull down kept at 2x. */
    static final String KEPT = "feed video kept at 2x";

    /** Counted for each hold that found Facebook's own hold had sped the video up during the press. */
    static final String TAKEN_FROM_FACEBOOK = "feed video hold taken from Facebook's";

    /** How far down a hold's finger goes, in dp, before its lift keeps the video at 2x. */
    static final int PULL_TO_KEEP_DP = 64;

    /** The speed a hold plays a feed video at. */
    static final float DOUBLE_SPEED = 2f;

    /** The kept name of the list every feed is drawn in; Facebook's own lists extend it. */
    private static final String LIST = PostLongPress.LIST;

    /** Every player Facebook attached a view container to, weakly, so a released one goes. */
    private static final Set<Object> players = Collections.newSetFromMap(new WeakHashMap<>());

    /** Reads and sets players. {@link #PATCHED} goes through the patch's stubs; tests stand in. */
    interface Players {
        @Nullable
        ViewGroup view(Object player);

        float speed(Object player);

        void speed(Object player, float speed);
    }

    static final Players PATCHED = new Players() {
        @Nullable
        @Override
        public ViewGroup view(Object player) {
            return playerView(player);
        }

        @Override
        public float speed(Object player) {
            return ReelHold.playerSpeed(player);
        }

        @Override
        public void speed(Object player, float speed) {
            setPlayerSpeed(player, speed);
        }
    };

    static volatile Players access = PATCHED;

    /** Whether the build carries Hold a reel for 2x, when a test says so instead of {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean inBuildForTests;

    private static Handler main;
    private static WeakReference<View> pressedRoot = new WeakReference<>(null);
    private static float downX;
    private static float downY;
    private static int slop;
    private static boolean timing;
    /** A hold sped a video up, so every later event of the gesture becomes a cancel. */
    private static boolean holding;
    /** The player a hold sped up, weakly, and the speed it played at before. */
    private static WeakReference<Object> heldPlayer = new WeakReference<>(null);
    private static float before = 1f;
    /** The video under the finger as it went down, weakly, and the speed it played at then. */
    private static WeakReference<Object> downPlayer = new WeakReference<>(null);
    private static float downSpeed = Float.NaN;
    /** The hold's finger is past the pull, so its lift keeps the speed. */
    private static boolean pulled;
    private static float pullPixels;
    /** A pull kept {@link #heldPlayer} fast after its lift, with no finger on it. */
    private static boolean kept;
    /** A finger went down on the kept video and hasn't moved, so its lift puts the speed back. */
    private static boolean tapping;
    private static WeakReference<View> keptRoot = new WeakReference<>(null);
    private static WeakReference<ViewGroup> keptView = new WeakReference<>(null);
    private static final Runnable FIRE = FeedVideoHold::fire;
    private static final Runnable REASSERT = FeedVideoHold::reassert;
    private static final ViewTreeObserver.OnWindowFocusChangeListener FOCUS = focused -> {
        if (!focused) letGo();
    };
    private static final View.OnAttachStateChangeListener DETACH = new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(View view) {
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            letGo();
        }
    };

    private FeedVideoHold() {
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled in by the patch: FbGrootPlayer's player view container. Only a player may be passed. */
    @Nullable
    public static ViewGroup playerView(Object player) {
        return null;
    }

    /** Filled in by the patch: FbGrootPlayer's speed setter. Only a player may be passed. */
    public static void setPlayerSpeed(Object player, float speed) {
    }

    // ------------------------------------------------------------------ hooks

    /**
     * The hook, first thing in FbGrootPlayer's attachPlayerViewInternal: [player] is attaching its
     * view to [container]. Noted whatever the switch says, so a switch turned on mid-scroll finds
     * the videos already on screen. Never throws.
     */
    public static void attached(@Nullable ViewGroup container, @Nullable Object player) {
        try {
            if (player == null) return;
            synchronized (players) {
                players.add(player);
            }
            // A player keeps its speed when Facebook moves it, and the next video isn't the one that was locked.
            if (kept && player == heldPlayer.get() && container != keptView.get()) letGo();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "feed video attach", failure);
        }
    }

    /** The hook, first thing in FbFragmentActivity.dispatchTouchEvent. */
    public static void touch(@Nullable Activity activity, @Nullable MotionEvent event) {
        try {
            if (activity == null || event == null) return;
            Window window = activity.getWindow();
            touch(window == null ? null : window.getDecorView(), event);
        } catch (Throwable failure) {
            stop();
            letGo();
            HookStatus.threw(FAMILY, "feed video hold", failure);
        }
    }

    /** One touch on the window whose top view is [root]. */
    static void touch(@Nullable View root, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            stop();
            tapping = false;
            // A lift this never heard leaves the last hold's speed up; the next finger puts it back.
            if (holding) letGo();
            if (root == null || !on("press")) {
                letGo();
                return;
            }
            downX = event.getRawX();
            downY = event.getRawY();
            slop = ViewConfiguration.get(root.getContext()).getScaledTouchSlop();
            pullPixels = PULL_TO_KEEP_DP * root.getResources().getDisplayMetrics().density;
            if (kept) {
                Object keptPlayer = heldPlayer.get();
                if (keptPlayer == null) {
                    letGo();
                } else if (videoAt(root, Math.round(downX), Math.round(downY)) == keptPlayer) {
                    tapping = true;
                    return;
                }
            }
            notePressedVideo(root);
            pressedRoot = new WeakReference<>(root);
            timing = true;
            main().postDelayed(FIRE, ViewConfiguration.getLongPressTimeout());
            return;
        }
        if (tapping) {
            if (action == MotionEvent.ACTION_UP) {
                letGo();
                event.setAction(MotionEvent.ACTION_CANCEL);
            } else if (action != MotionEvent.ACTION_MOVE
                    || Math.hypot(event.getRawX() - downX, event.getRawY() - downY) > slop) {
                tapping = false;
            }
            return;
        }
        if (holding) {
            if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) pull(root, event);
            if (action == MotionEvent.ACTION_UP && pulled) {
                keep(root);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                letGo();
            }
            event.setAction(MotionEvent.ACTION_CANCEL);
            // Runs once Facebook has had the cancel, which ends its own hold and its speed with it.
            if (holding || kept) {
                main().removeCallbacks(REASSERT);
                main().post(REASSERT);
            }
            return;
        }
        if (!timing) return;
        if (action == MotionEvent.ACTION_MOVE) {
            if (Math.hypot(event.getRawX() - downX, event.getRawY() - downY) > slop) stop();
        } else {
            stop();
        }
    }

    private static void fire() {
        if (!timing) return;
        timing = false;
        View root = pressedRoot.get();
        pressedRoot = new WeakReference<>(null);
        try {
            if (root == null || !on("hold")) return;
            // A call, a task switch or a dialog that took the window mid-press leaves it alone.
            if (!root.isAttachedToWindow() || !root.hasWindowFocus()) return;
            int x = Math.round(downX);
            int y = Math.round(downY);
            Object player = videoAt(root, x, y);
            if (player == null) return;
            // Another video was held, so the one locked before it goes back first.
            if (kept) letGo();
            Players players = access;
            float speed = players.speed(player);
            if (Float.isNaN(speed) || speed <= 0f) speed = 1f;
            if (speed >= DOUBLE_SPEED - ReelHold.SAME) {
                float atDown = downPlayer.get() == player ? downSpeed : Float.NaN;
                if (!(atDown < DOUBLE_SPEED - ReelHold.SAME)) {
                    HookStatus.counted(FAMILY, ALREADY_FAST);
                    float was = speed;
                    Logger.printDebug(() -> "Feed video hold: the video already plays at " + was + "x");
                    return;
                }
                // Facebook's own hold got there first; the press is still this one's.
                HookStatus.counted(FAMILY, TAKEN_FROM_FACEBOOK);
                speed = atDown;
            }
            before = speed;
            heldPlayer = new WeakReference<>(player);
            holding = true;
            pulled = false;
            players.speed(player, DOUBLE_SPEED);
            cancelLongPresses(root, x, y);
            ViewGroup view = players.view(player);
            if (view != null) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            HookStatus.counted(FAMILY, HELD);
            float was = speed;
            Logger.printDebug(() -> "Feed video hold: " + DOUBLE_SPEED + "x, the video was at " + was + "x");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "feed video hold", failure);
        }
    }

    /** Notes the feed video under a finger going down on [root], if any, and the speed it plays at. */
    private static void notePressedVideo(View root) {
        downPlayer = new WeakReference<>(null);
        downSpeed = Float.NaN;
        Object player = videoAt(root, Math.round(downX), Math.round(downY));
        if (player == null) return;
        float speed = access.speed(player);
        downPlayer = new WeakReference<>(player);
        downSpeed = Float.isNaN(speed) || speed <= 0f ? 1f : speed;
    }

    /**
     * After a cancel Facebook heard during a hold or a lock: a hold of Facebook's own that ran in the
     * same press puts its speed back on that cancel, so the held video goes back to 2x. Never throws.
     */
    private static void reassert() {
        if (!holding && !kept) return;
        Object player = heldPlayer.get();
        if (player == null) return;
        try {
            Players players = access;
            if (Math.abs(players.speed(player) - DOUBLE_SPEED) < ReelHold.SAME) return;
            players.speed(player, DOUBLE_SPEED);
            Logger.printDebug(() -> "Feed video hold: back at " + DOUBLE_SPEED + "x after Facebook's own hold let go");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "feed video hold", failure);
        }
    }

    /** Notes whether the hold's finger is past the pull, with the buzz of the long press as it crosses. */
    private static void pull(@Nullable View root, MotionEvent event) {
        boolean past = event.getRawY() - downY > pullPixels;
        if (past && !pulled && root != null) root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        pulled = past;
    }

    /** The lift of a pulled hold: the video stays fast until a tap on it, or until it stops being the same video. */
    private static void keep(@Nullable View root) {
        Object player = heldPlayer.get();
        ViewGroup view = player == null ? null : access.view(player);
        if (root == null || view == null || !view.isAttachedToWindow()) {
            letGo();
            return;
        }
        holding = false;
        pulled = false;
        kept = true;
        keptRoot = new WeakReference<>(root);
        keptView = new WeakReference<>(view);
        root.getViewTreeObserver().addOnWindowFocusChangeListener(FOCUS);
        view.addOnAttachStateChangeListener(DETACH);
        HookStatus.counted(FAMILY, KEPT);
        Logger.printDebug(() -> "Feed video hold: kept at " + DOUBLE_SPEED + "x");
        if (!Settings.HIDE_2X_POPUPS.get()) Utils.showToastShort(L10n.t("Locked at 2x. Tap the video to go back."));
    }

    /** Stops listening for the end of a lock. */
    private static void unwatch() {
        View root = keptRoot.get();
        keptRoot = new WeakReference<>(null);
        if (root != null) {
            ViewTreeObserver observer = root.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnWindowFocusChangeListener(FOCUS);
        }
        View view = keptView.get();
        keptView = new WeakReference<>(null);
        if (view != null) view.removeOnAttachStateChangeListener(DETACH);
    }

    /** Puts back the speed the held or kept video had, if a hold sped one up. Never throws. */
    private static void letGo() {
        if (!holding && !kept) return;
        holding = false;
        pulled = false;
        kept = false;
        tapping = false;
        Object player = heldPlayer.get();
        heldPlayer = new WeakReference<>(null);
        if (player == null) {
            unwatch();
            return;
        }
        float back = before;
        try {
            unwatch();
            access.speed(player, back);
            Logger.printDebug(() -> "Feed video hold: back to " + back + "x");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "feed video let go", failure);
        }
    }

    /** The views under the press had their long press timed from the same touch, so it would run next: cancels it. */
    private static void cancelLongPresses(View root, int x, int y) {
        for (View on = PostLongPress.hit(root, x, y); on != null;
                on = on.getParent() instanceof View ? (View) on.getParent() : null) {
            on.cancelLongPress();
        }
    }

    /**
     * Whether a hold at the screen point ([x], [y]) inside [root] is this one's: the switch is on and a
     * feed video plays there. Long press opens comments asks first, so a hold on a feed video plays it
     * fast instead of opening comments. Never throws.
     */
    public static boolean takes(@Nullable View root, int x, int y) {
        try {
            return root != null && on("feed post hold") && videoAt(root, x, y) != null;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "feed post hold", failure);
            return false;
        }
    }

    /**
     * The player whose feed video shows at the screen point ([x], [y]) inside [root], or null: its
     * container is shown in [root]'s window with the point inside it and inside its list, and the
     * list scrolls up and down with the container's row smaller than it, with no scroller or pager
     * between. The smallest such container wins, should two overlap.
     */
    @Nullable
    static Object videoAt(View root, int x, int y) {
        if (FeedVideoControls.barAt(root, x, y)) return null;
        List<Object> attached;
        synchronized (players) {
            attached = new ArrayList<>(players);
        }
        Players read = access;
        Object found = null;
        long smallest = Long.MAX_VALUE;
        for (Object player : attached) {
            ViewGroup view = read.view(player);
            if (view == null || !view.isAttachedToWindow() || !view.isShown() || view.getRootView() != root) continue;
            if (!contains(view, x, y)) continue;
            View list = feedListOf(view);
            if (list == null || !contains(list, x, y)) continue;
            long area = (long) view.getWidth() * view.getHeight();
            if (area < smallest) {
                smallest = area;
                found = player;
            }
        }
        return found;
    }

    /** The feed list [view] sits in: its nearest list, when that scrolls up and down and [view]'s row is smaller than it. */
    @Nullable
    private static View feedListOf(View view) {
        View row = view;
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent()) {
            View up = (View) parent;
            if (PostLongPress.isNamed(up, LIST)) {
                if (!up.canScrollVertically(1) && !up.canScrollVertically(-1)) return null;
                if (row.getWidth() == up.getWidth() && row.getHeight() == up.getHeight()) return null;
                return up;
            }
            if (PostLongPress.isScroller(up)) return null;
            row = up;
        }
        return null;
    }

    private static boolean contains(View view, int x, int y) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return x >= at[0] && x < at[0] + view.getWidth() && y >= at[1] && y < at[1] + view.getHeight();
    }

    /** Whether the switch is on, with [where] bound. Never throws: a failure is reported and reads off. */
    private static boolean on(String where) {
        try {
            if (!inBuild() || !Utils.settingsReady() || !Settings.HOLD_FEED_VIDEO_FOR_2X.get()) return false;
            HookStatus.bound(FAMILY, where);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, where, failure);
            return false;
        }
    }

    private static boolean inBuild() {
        Boolean forced = inBuildForTests;
        return forced != null ? forced : SettingsStatus.reelHold();
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

    /** For tests: whether a press is being timed. */
    static boolean timingForTests() {
        return timing;
    }

    /** For tests: forgets the players, the press being timed and the hold, and goes back to the patch's stubs. */
    static void reset() {
        stop();
        if (main != null) main.removeCallbacks(REASSERT);
        unwatch();
        downPlayer = new WeakReference<>(null);
        downSpeed = Float.NaN;
        holding = false;
        pulled = false;
        kept = false;
        tapping = false;
        heldPlayer = new WeakReference<>(null);
        before = 1f;
        synchronized (players) {
            players.clear();
        }
        access = PATCHED;
        inBuildForTests = null;
    }
}
