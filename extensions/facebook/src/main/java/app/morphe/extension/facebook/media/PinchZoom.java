/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.feed.PhotoCarousel;
import app.morphe.extension.facebook.reels.FeedVideoControls;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Pinch to zoom where Facebook has none of its own: a profile's photos, stories, feed videos and the
 * photos in Photo carousel's rows (#220).
 *
 * <p>Every touch on a Facebook screen comes here first, from FbFragmentActivity's dispatchTouchEvent.
 * With the switch on, a second finger landing over something {@link #places} says may be zoomed starts
 * a zoom: a copy of what's on screen there goes into a layer over the window, the pinch scales it about
 * the point under the fingers and moving them drags it, and once fewer than two fingers are down it
 * springs back over the photo and the layer goes away. Facebook's own views never move.
 *
 * <p>Only a story, a profile, a feed video or a photo in Photo carousel's row is zoomed. The news feed
 * has a pinch of its own for a post's photos, and the profile shares its activity with the feed, so a
 * profile is told apart by its view: ProfileFragment hands its root to {@link #profile} as it's made,
 * and a photo has to sit inside one of those. A feed video sits inside the view of a player Tap to play
 * knows whose origin is a feed, a page's, a group's or a profile's ({@link #feedVideos}), and a
 * carousel photo is an item {@link PhotoCarousel#isRowPhoto} names. A story is the story viewer's
 * activity. On 584 a profile's photo grid is Litho hosts with no child view, the picture being a
 * drawable mounted straight into the host, so a host that draws no text counts as a photo, the way
 * PostLongPress reads one.
 *
 * <p>Outside the story viewer, from the second finger on every event of the gesture reaches Facebook
 * as a cancel, so the first finger's tap, hold or swipe never fires and a photo doesn't open. In the
 * story viewer, unless the zoom follows the video (below), Facebook sees nothing of the gesture while
 * the zoom lasts, so to it the first finger is still held
 * where it was, its own hold that keeps the story paused, and the event that ends the zoom reaches it
 * as a cancel, so the story carries on from there without skipping ahead. A cancel straight away
 * would let the story play on under the zoom, and close the viewer when the last one ran out. On a
 * profile a hold that long could open a menu, so it gets the cancel at once. Facebook's own pinch in
 * a story (clear mode) doesn't see this one either way. Without two fingers over a zoomable view,
 * nothing changes and Facebook gets every event as it was.
 *
 * <p>The copy is read from the window's last frame, which holds a playing video as well as a photo
 * (a SurfaceView's from its own surface), so a video zooms on the frame it showed when the fingers
 * landed, unless Keep videos playing while zoomed is on. In a window Facebook put in HDR color mode,
 * the story viewer's, Android 14 and newer show HDR video above SDR white, so there the copy and the
 * frames a zoom reads from a SurfaceView are half floats in extended sRGB, which keep that light, and
 * the zoom is no dimmer than the story under it (#220). A video on a SurfaceView of its own lifts the
 * screen whatever the window's mode, and Android then dims the window's SDR parts, so a zoom on one
 * while the screen is lifted ({@link #lifted}) copies in half floats too and puts the window in HDR
 * color mode until it goes. A device whose half float copy fails gets the usual 8 bits from then on.
 *
 * <p>With Keep videos playing while zoomed on (#220), a zoom on a video surface, a TextureView or a
 * SurfaceView, follows it: about every {@link #FRAME_MILLIS} the copy
 * takes the video's newest frame, read from the view itself, so the video plays on under the
 * fingers. Frames go into two buffers in turn, so none is written while it shows. With Stay zoomed
 * on too, a finger held still on the kept zoom for the long-press time pauses the video, through the
 * player Tap to play knows for it, and a second hold plays it again. With no player known, the frame
 * just holds. Letting the zoom go plays a video a hold paused. A story's video is followed too, and
 * Facebook gets the cancel at once, so the story plays on under the zoom, which springs back once
 * the story moves on and its video surface goes. A story held before the second finger, past the
 * long-press time, stays the still copy Facebook paused it on, held as without the switch.
 *
 * <p>With Stay zoomed on (#220), dropping to one finger drags the copy instead of ending the zoom,
 * and a zoom past {@link #KEPT_LEAST} stays where the fingers left it when they all lift. A touch
 * on it then steers it, one finger dragging and two pinching, and Facebook gets none of that touch.
 * It springs back on a tap, on a touch that ends it near 1x, on Back, or when the window loses
 * focus. Back comes two ways: 584 targets Android 16, which hands Back to the window's back callbacks
 * and no view sees it as a key, so the kept zoom registers one above Facebook's; older Android hands
 * Back to the focused view as a key, so the layer takes focus too. A story's held finger stays held
 * while it's kept, so the story stays paused, and lets go as a cancel when the zoom does.
 *
 * <p>With Double tap to zoom on (#220), two quick taps on a photo {@link #places} names zoom it to
 * {@link #DOUBLE_TAP_SCALE} about the tap, as a kept zoom, and a second double tap on it lets it go.
 * A profile's photos open on a tap, so a finger landing on one is held from Facebook: when a second
 * tap comes within the double tap time, Facebook sees neither and the photo zooms. Otherwise the held
 * events reach Facebook as they came ({@link #replayHeld}), so a lone tap opens the photo a moment
 * later, a finger that moves past the slop or gains a second finger is handed on at once as a scroll
 * or a pinch, and one still down after the double tap time is handed on too, so a hold still opens
 * Facebook's menu. A second touch that turns out not to be a tap is handed on from its own start.
 * The story viewer is left out, where a tap moves the story on and mustn't wait. A lone tap on a
 * double tap zoom does nothing, so the second tap of the pair that lets it go never reaches Facebook.
 */
public final class PinchZoom {
    private static final String FAMILY = FamilyNames.PINCH_TO_ZOOM;
    /** The most a pinch zooms. */
    static final float MOST = 4f;
    /** How dark the window behind the copy gets at full zoom, with Dim around a zoom on. */
    static final float SCRIM = 0.75f;
    /** How long the spring back takes, before the system's animation scale. */
    static final long SETTLE_MILLIS = 200;
    /** With Stay zoomed on, a zoom no bigger than this when the fingers lift springs back anyway. */
    static final float KEPT_LEAST = 1.05f;
    /** How far a double tap zooms: twice the photo's size. */
    static final float DOUBLE_TAP_SCALE = 2f;
    /** A view smaller than this many dp either way, an icon or a button, isn't zoomed. */
    static final int SMALLEST_DP = 64;
    /** The story viewer's activity, a kept class name. 584 opens it as one of its subclasses too ({@link #storyViewer}). */
    static final String STORY_VIEWER = "com.facebook.stories.viewer.activity.StoryViewerActivity";
    /** How often a zoom that keeps a video playing takes its next frame: about 30 a second. */
    static final long FRAME_MILLIS = 33;
    /** A screen whose HDR to SDR ratio is past this shows light above SDR white. Android eases the ratio, so a hair over 1 is still SDR. */
    static final float LIFTED = 1.01f;

    /** The root views ProfileFragment made, weakly held. Main thread. */
    private static final Map<View, Boolean> profiles = new WeakHashMap<>();

    /** Copies what's on screen inside [area], window coordinates, and hands it to [done] on the main thread. */
    interface Snapshots {
        void take(Activity activity, View target, Rect area, Done done);
    }

    interface Done {
        void accept(@Nullable Bitmap copy);
    }

    /** The view to zoom under the fingers' midpoint at ([x], [y]) in [root], or null for none. */
    interface Places {
        @Nullable View target(Activity activity, View root, float x, float y);
    }

    /** Reads [source]'s frame on screen now into [zoom]'s copy, then runs [next] on the main thread. */
    interface Frames {
        void read(Zoom zoom, View source, Runnable next);
    }

    /** How far above SDR white the screen [view] is on shows light now, as its HDR to SDR ratio, 1 for not at all. */
    interface Headroom {
        float now(View view);
    }

    /** The players Tap to play knows, and pausing or playing one. */
    interface Players {
        List<Object> known();
        @Nullable View view(Object player);
        /** Whether [player] plays in a feed, a page's, a group's or a profile's included. */
        boolean inFeed(Object player);
        boolean playing(Object player);
        void command(Object player, boolean play);
    }

    private static final Players PATCHED_PLAYERS = new Players() {
        public List<Object> known() { return FeedVideoControls.knownPlayers(); }
        public View view(Object player) { return FeedVideoControls.playerView(player); }
        public boolean inFeed(Object player) { return FeedVideoControls.feedPlayer(player); }
        public boolean playing(Object player) { return FeedVideoControls.isPlaying(player); }
        public void command(Object player, boolean play) {
            // Tap to play holds back a start it didn't arm, so a video a hold paused is armed first.
            if (play) TapToPlay.armForResume(player);
            FeedVideoControls.commandPlayer(player, play);
        }
    };

    static volatile Snapshots snapshots = PinchZoom::snapshot;
    static volatile Places places = PinchZoom::mediaUnder;
    static volatile Frames frames = PinchZoom::frame;
    static volatile Players players = PATCHED_PLAYERS;
    static volatile Headroom headroom = PinchZoom::screenHeadroom;

    /** A half float copy failed on this device, so copies take the usual 8 bits from then on. Main thread. */
    private static boolean wideFailed;
    /** The gesture took the second finger, so every later event Facebook gets of it is a cancel. */
    private static boolean swallowing;
    /** A story zoom on a still copy: Facebook gets none of the gesture until the zoom ends, then a cancel. */
    private static boolean holding;
    /** This touch landed on a kept zoom and steers it, so Facebook gets none of it. */
    private static boolean steering;
    @Nullable private static Zoom zoom;
    /** Double tap to zoom: the first tap's events, held from Facebook until it's clear no second tap follows. */
    private static final List<MotionEvent> held = new ArrayList<>();
    @Nullable private static WeakReference<Activity> heldFrom;
    /** The held tap lifted, and waits for the double tap time to see whether a second one comes. */
    private static boolean heldUp;
    private static long heldUpTime;
    private static float firstX;
    private static float firstY;
    /** The second tap of a double tap is down, and Facebook gets none of it unless it stops being a tap. */
    @Nullable private static MotionEvent secondDown;
    /** Held events are going to Facebook, which this hands on untouched. */
    private static boolean replaying;
    private static final Handler later = new Handler(Looper.getMainLooper());
    private static final Runnable replayLater = PinchZoom::replayHeld;
    /**
     * A finger landing this soon after a list in the window last scrolled is stopping a fling, so it
     * goes to Facebook at once. Held, the list slid on under it for the double tap time.
     */
    static final long STOPS_A_FLING_MS = 150;
    /** When a view in a watched window last scrolled, in uptime millis. */
    private static long scrolledAt = Long.MIN_VALUE / 2;
    private static final ViewTreeObserver.OnScrollChangedListener scrolled =
            () -> scrolledAt = SystemClock.uptimeMillis();
    /** The windows whose scrolling [scrolled] hears, so each gets the listener once. */
    private static final Map<ViewTreeObserver, Boolean> watched = new WeakHashMap<>();

    private PinchZoom() {
    }

    /**
     * Injection point, first thing in FbFragmentActivity.dispatchTouchEvent. True keeps [event] from
     * Facebook, and the dispatch returns true without handing it on.
     */
    public static boolean touch(@Nullable Activity activity, @Nullable MotionEvent event) {
        try {
            if (activity == null || event == null) return false;
            return handle(activity, event);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "pinch", failure);
            // The layer goes, but a gesture already taken stays a cancel to its end, so Facebook
            // never sees the middle of a gesture it was kept from.
            drop();
            holding = false;
            forgetTaps();
            if ((swallowing || steering) && event != null) event.setAction(MotionEvent.ACTION_CANCEL);
            return false;
        }
    }

    /** Injection point, first thing in ProfileFragment.onViewCreated: [view] is a profile's root. */
    public static void profile(@Nullable View view) {
        try {
            if (view != null) profiles.put(view, Boolean.TRUE);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "profile", failure);
        }
    }

    /** True when Facebook shouldn't get [event] at all. */
    static boolean handle(Activity activity, MotionEvent event) {
        if (replaying) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            steering = false;
            Zoom kept = zoom;
            if (kept != null && kept.parked && !kept.settling) {
                Window window = activity.getWindow();
                if (window != null && window.getDecorView() == kept.root) {
                    forgetTaps();
                    steering = true;
                    kept.steer(event);
                    return true;
                }
                release();
            }
            // A new gesture. A layer still springing back from the last one goes at once.
            drop();
            swallowing = false;
            holding = false;
            return tapDown(activity, event);
        }
        if (steering) return steer(event, action);
        if (swallowing) {
            follow(event, action);
            Zoom live = zoom;
            if (live != null && live.parked) {
                // Kept: a story's held finger stays held, so the story stays paused under it.
                if (holding) return true;
                event.setAction(MotionEvent.ACTION_CANCEL);
                return false;
            }
            if (holding) {
                if (swallowing && live != null && !live.settling && !live.blank) return true;
                // The zoom ended with this event, or its copy came back empty: Facebook's held finger
                // lets go as a cancel, and the rest of the gesture stays one.
                holding = false;
            }
            event.setAction(MotionEvent.ACTION_CANCEL);
            return false;
        }
        if (secondDown != null) {
            if (followSecond(activity, event, action)) return true;
        } else if (!held.isEmpty() && !heldUp) {
            if (followHeld(activity, event, action)) return true;
        }
        if (action != MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() != 2) return false;
        if (!Utils.settingsReady() || !Settings.PINCH_TO_ZOOM.get()) return false;
        HookStatus.invoked(FAMILY);
        Window window = activity.getWindow();
        View root = window == null ? null : window.getDecorView();
        if (!(root instanceof ViewGroup)) return false;
        float x = (event.getX(0) + event.getX(1)) / 2f;
        float y = (event.getY(0) + event.getY(1)) / 2f;
        View target = places.target(activity, root, x, y);
        if (target == null) return false;
        Rect area = area(root, target);
        if (area == null) return false;
        HookStatus.bound(FAMILY, "pinch");
        Zoom started = new Zoom((ViewGroup) root, area, event);
        started.keep = Settings.PINCH_TO_ZOOM_KEEP.get();
        started.darkest = darkest();
        started.source = liveSource(target);
        started.story = storyViewer(activity);
        started.window = window;
        started.wide = wide(window, target);
        // A story held before the pinch, its first finger down past the long-press time, stays the
        // still frame Facebook paused it on, as without Keep videos playing while zoomed.
        if (started.story && started.source != null
                && event.getEventTime() - event.getDownTime() >= ViewConfiguration.getLongPressTimeout()) {
            started.source = null;
        }
        zoom = started;
        swallowing = true;
        holding = started.story && started.source == null;
        if (!holding) event.setAction(MotionEvent.ACTION_CANCEL);
        snapshots.take(activity, target, area, copy -> {
            try {
                if (zoom == started) started.show(copy);
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "pinch copy", failure);
                if (zoom == started) zoom = null;
                started.remove();
            }
        });
        return holding;
    }

    /**
     * One event of a gesture this took: the zoom follows the fingers until fewer than two are down,
     * or with Stay zoomed on until they all lift, and stays when that leaves it zoomed.
     */
    private static void follow(MotionEvent event, int action) {
        Zoom live = zoom;
        if (action == MotionEvent.ACTION_UP) {
            swallowing = false;
            if (live != null && live.keepable()) live.park();
            else settle();
            return;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            swallowing = false;
            settle();
            return;
        }
        if (live == null || live.settling) return;
        if (action == MotionEvent.ACTION_MOVE) {
            live.move(event, -1);
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            live.rebase(event, -1);
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            if (event.getPointerCount() <= 2 && !live.keep) settle();
            else live.rebase(event, event.getActionIndex());
        }
    }

    /**
     * One event of a touch on a kept zoom, none of which Facebook gets: one finger drags it, two
     * pinch it, and a tap or a lift near 1x lets it go. A cancel ends the touch and keeps the zoom.
     */
    private static boolean steer(MotionEvent event, int action) {
        Zoom kept = zoom;
        boolean ends = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
        if (kept == null || kept.settling || !kept.parked) {
            // It went mid touch. Facebook never saw this touch start, so it sees none of the rest.
            if (ends) steering = false;
            return true;
        }
        if (ends) {
            steering = false;
            kept.cancelHold();
            // With gesture navigation the system cancels an edge swipe before it sends Back, and
            // Back has to find the zoom still up, or it goes on to close Facebook's screen.
            if (action == MotionEvent.ACTION_CANCEL) return true;
            // The hold paused or played the video, so its lift lets nothing go.
            if (kept.heldDown) return true;
            boolean tapped = !kept.moved && !kept.pinched;
            // A double tap zoom is let go by a second double tap, so the first tap of it does nothing
            // and the second never reaches Facebook as a tap on the photo.
            if (tapped && kept.doubleTap && kept.scale > KEPT_LEAST) return kept.tappedTwice(event) ? letGo(event) : true;
            if (tapped || kept.scale <= KEPT_LEAST) return letGo(event);
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            kept.drag(event);
            if (kept.moved) kept.cancelHold();
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            kept.pinched = true;
            kept.cancelHold();
            kept.rebase(event, -1);
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            kept.rebase(event, event.getActionIndex());
        }
        return true;
    }

    private static boolean doubleTapOn() {
        return Utils.settingsReady() && Settings.PINCH_TO_ZOOM.get() && Settings.PINCH_TO_ZOOM_DOUBLE_TAP.get();
    }

    /** How dark the window behind a new zoom gets at full zoom (Dim around a zoom). */
    private static float darkest() {
        return Settings.PINCH_TO_ZOOM_DIM.get() ? SCRIM : 0f;
    }

    /** Drops any held tap and second tap without handing them on. */
    private static void forgetTaps() {
        later.removeCallbacks(replayLater);
        for (MotionEvent event : held) event.recycle();
        held.clear();
        heldFrom = null;
        heldUp = false;
        MotionEvent second = secondDown;
        secondDown = null;
        if (second != null) second.recycle();
    }

    /**
     * A finger landed. When it's the second tap of a double tap on a held photo, Facebook gets none
     * of it. A held tap it doesn't pair with goes to Facebook first. With Double tap to zoom on, a
     * finger on a photo outside the story viewer is held. True keeps [event] from Facebook.
     */
    private static boolean tapDown(Activity activity, MotionEvent event) {
        if (heldUp && heldFrom != null && heldFrom.get() == activity && pairs(activity, event)) {
            forgetTaps();
            secondDown = MotionEvent.obtain(event);
            return true;
        }
        replayHeld();
        forgetTaps();
        if (!doubleTapOn() || storyViewer(activity)) return false;
        Window window = activity.getWindow();
        View root = window == null ? null : window.getDecorView();
        if (!(root instanceof ViewGroup)) return false;
        watchScrolling(root);
        // Android's own lists take a finger on a moving list as a stop, never a tap.
        if (event.getEventTime() - scrolledAt <= STOPS_A_FLING_MS) return false;
        if (places.target(activity, root, event.getX(), event.getY()) == null) return false;
        HookStatus.invoked(FAMILY);
        held.add(MotionEvent.obtain(event));
        heldFrom = new WeakReference<>(activity);
        firstX = event.getX();
        firstY = event.getY();
        // Still down after the double tap time, it's no tap: Facebook gets it, and its hold opens a menu.
        later.postDelayed(replayLater, ViewConfiguration.getDoubleTapTimeout());
        return true;
    }

    /**
     * [root]'s window notes when anything in it scrolls. A list scrolling marks the window each frame
     * it moves, a fling's every frame included, and the window tells its scroll listeners as it draws.
     */
    private static void watchScrolling(View root) {
        ViewTreeObserver observer = root.getViewTreeObserver();
        if (!observer.isAlive() || watched.containsKey(observer)) return;
        observer.addOnScrollChangedListener(scrolled);
        watched.put(observer, Boolean.TRUE);
    }

    /** True when [event], a finger landing, is close enough in time and place to the held tap to be its second. */
    private static boolean pairs(Activity activity, MotionEvent event) {
        return event.getEventTime() - heldUpTime <= ViewConfiguration.getDoubleTapTimeout()
                && Math.hypot(event.getX() - firstX, event.getY() - firstY)
                <= ViewConfiguration.get(activity).getScaledDoubleTapSlop();
    }

    /**
     * One more event of the held finger. A move within the slop and the lift are held too, the lift
     * then waiting the double tap time for a second tap. A move past the slop or a second finger
     * hands the held events to Facebook, and [event] after them. True keeps [event] from Facebook.
     */
    private static boolean followHeld(Activity activity, MotionEvent event, int action) {
        if (action == MotionEvent.ACTION_CANCEL) {
            forgetTaps();
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1
                && Math.hypot(event.getX() - firstX, event.getY() - firstY)
                <= ViewConfiguration.get(activity).getScaledTouchSlop()) {
            held.add(MotionEvent.obtain(event));
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            held.add(MotionEvent.obtain(event));
            heldUp = true;
            heldUpTime = event.getEventTime();
            later.removeCallbacks(replayLater);
            later.postDelayed(replayLater, ViewConfiguration.getDoubleTapTimeout());
            return true;
        }
        replayHeld();
        return false;
    }

    /**
     * One more event of the second tap. Its lift zooms the photo, and Facebook gets nothing of either
     * tap. A move past the slop or a second finger makes it no tap: Facebook gets it from its own
     * start, and [event] after that. True keeps [event] from Facebook.
     */
    private static boolean followSecond(Activity activity, MotionEvent event, int action) {
        MotionEvent second = secondDown;
        if (second == null) return false;
        if (action == MotionEvent.ACTION_CANCEL) {
            forgetTaps();
            return true;
        }
        float x = second.getX();
        float y = second.getY();
        if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1
                && Math.hypot(event.getX() - x, event.getY() - y)
                <= ViewConfiguration.get(activity).getScaledTouchSlop()) {
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            forgetTaps();
            zoomAtTap(activity, x, y, event);
            return true;
        }
        secondDown = null;
        held.add(second);
        heldFrom = new WeakReference<>(activity);
        replayHeld();
        return false;
    }

    /**
     * The held events go to Facebook as they came, through the activity's own dispatch, which this
     * hands on untouched. Dropped instead when the activity is gone or another window has the focus.
     */
    static void replayHeld() {
        later.removeCallbacks(replayLater);
        if (held.isEmpty()) return;
        Activity activity = heldFrom == null ? null : heldFrom.get();
        List<MotionEvent> events = new ArrayList<>(held);
        held.clear();
        heldFrom = null;
        heldUp = false;
        try {
            if (activity == null || activity.isFinishing() || !activity.hasWindowFocus()) return;
            replaying = true;
            for (MotionEvent event : events) activity.dispatchTouchEvent(event);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "double tap replay", failure);
        } finally {
            replaying = false;
            for (MotionEvent event : events) event.recycle();
        }
    }

    /**
     * The second tap of a double tap lifted at ([x], [y]): the photo under it zooms to {@link
     * #DOUBLE_TAP_SCALE} about the tap and stays until a second double tap, Back or the window losing
     * focus, through the same kept zoom Stay zoomed uses.
     */
    private static void zoomAtTap(Activity activity, float x, float y, MotionEvent event) {
        Window window = activity.getWindow();
        View root = window == null ? null : window.getDecorView();
        if (!(root instanceof ViewGroup)) return;
        View target = places.target(activity, root, x, y);
        if (target == null) return;
        Rect area = area(root, target);
        if (area == null) return;
        HookStatus.bound(FAMILY, "double tap");
        Zoom started = new Zoom((ViewGroup) root, area, event);
        started.doubleTap = true;
        started.scale = DOUBLE_TAP_SCALE;
        started.darkest = darkest();
        started.source = liveSource(target);
        started.window = window;
        started.wide = wide(window, target);
        zoom = started;
        snapshots.take(activity, target, area, copy -> {
            try {
                if (zoom != started) return;
                started.show(copy);
                if (started.shown()) started.park();
                else if (started.blank) drop();
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "double tap copy", failure);
                if (zoom == started) zoom = null;
                started.remove();
            }
        });
    }

    /** A kept zoom springs back, and a story's held finger lets go with [event], a cancel to Facebook. */
    private static boolean letGo(MotionEvent event) {
        settle();
        if (!holding) return true;
        holding = false;
        event.setAction(MotionEvent.ACTION_CANCEL);
        return false;
    }

    /**
     * A kept zoom springs back outside a touch, on Back or when the window loses focus, and a story's
     * held finger lets go with a cancel of its own, so the story plays on.
     */
    static void release() {
        Zoom kept = zoom;
        if (kept == null || !kept.parked || kept.settling) return;
        kept.settle();
        if (!holding) return;
        holding = false;
        // The cancel comes back through touch(), which hands it on unless a steering touch would take it.
        boolean steered = steering;
        steering = false;
        long now = SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0);
        cancel.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            kept.root.dispatchTouchEvent(cancel);
        } finally {
            cancel.recycle();
            steering = steered;
        }
    }

    private static void settle() {
        Zoom live = zoom;
        if (live != null) live.settle();
    }

    /** Takes the layer away now, mid spring or not. */
    private static void drop() {
        Zoom live = zoom;
        zoom = null;
        if (live == null) return;
        try {
            live.remove();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "pinch", failure);
        }
    }

    /**
     * True for the story viewer. 584 opens it as StoryViewerActivity or as one of its empty
     * subclasses (DefaultNavBar, BlackNavBar, Floating), so the kept name is looked for up the class's
     * parents.
     */
    static boolean storyViewer(Activity activity) {
        for (Class<?> type = activity.getClass(); type != null; type = type.getSuperclass()) {
            if (STORY_VIEWER.equals(type.getName())) return true;
        }
        return false;
    }

    /**
     * With Keep videos playing while zoomed on, [target] when it's a video surface, a TextureView or
     * a SurfaceView, the view whose frames the zoom follows. Otherwise null, and the copy stays still.
     */
    @Nullable
    static View liveSource(View target) {
        if (!Settings.PINCH_TO_ZOOM_LIVE.get()) return null;
        return target instanceof TextureView || target instanceof SurfaceView ? target : null;
    }

    /** The player Tap to play knows whose view is [source] or holds it, or null for none. */
    @Nullable
    static Object playerOf(@Nullable View source) {
        if (source == null) return null;
        for (Object player : players.known()) {
            View view = players.view(player);
            if (view != null && within(source, view)) return player;
        }
        return null;
    }

    /** True when [view] is [ancestor] or sits inside it. */
    private static boolean within(View view, View ancestor) {
        for (Object at = view; at instanceof View; at = ((View) at).getParent()) {
            if (at == ancestor) return true;
        }
        return false;
    }

    /**
     * Reads [source]'s frame on screen now into [zoom]'s next buffer and shows it, then runs [next].
     * A TextureView hands its frame back before its own transform, so the transform places it; a
     * SurfaceView's comes from its surface, cut to the zoom's area. A frame that can't be read leaves
     * the last one showing.
     */
    static void frame(Zoom zoom, View source, Runnable next) {
        if (source instanceof TextureView) {
            TextureView texture = (TextureView) source;
            Bitmap buffer = texture.isAvailable() ? zoom.spare(texture.getWidth(), texture.getHeight()) : null;
            if (buffer != null) {
                texture.getBitmap(buffer);
                zoom.showFrame(buffer, placement(texture, zoom.area));
            }
            next.run();
            return;
        }
        Rect surface = source instanceof SurfaceView ? surfaceArea((SurfaceView) source, zoom.area) : null;
        Bitmap buffer = surface == null ? null : zoom.spare(zoom.area.width(), zoom.area.height(), zoom.wide);
        if (buffer == null) {
            next.run();
            return;
        }
        PixelCopy.request((SurfaceView) source, surface, buffer, result -> {
            try {
                if (result == PixelCopy.SUCCESS) zoom.showFrame(buffer, null);
                // This device copies no half floats, so the next frame takes the usual 8 bits.
                else if (buffer.getConfig() == Bitmap.Config.RGBA_F16) wideFailed = true;
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "zoom frame", failure);
            } finally {
                next.run();
            }
        }, later);
    }

    /** Where a TextureView's frame, before its transform, goes in a copy over [area]. */
    static Matrix placement(TextureView texture, Rect area) {
        Matrix place = texture.getTransform(null);
        int[] at = new int[2];
        texture.getLocationInWindow(at);
        place.postTranslate(at[0] - area.left, at[1] - area.top);
        return place;
    }

    /**
     * [target]'s bounds in [root]'s window, cut to each view holding it that clips what it holds and
     * to what [root] shows, or null when that's empty. A profile video's TextureView runs past the
     * post that clips it, and copying all of it took in the posts above and below (#220).
     */
    @Nullable
    static Rect area(View root, View target) {
        int[] at = new int[2];
        Rect area = bounds(target, at);
        for (ViewParent parent = target.getParent(); parent instanceof ViewGroup && parent != root; parent = parent.getParent()) {
            ViewGroup holder = (ViewGroup) parent;
            if (holder.getClipChildren() && !area.intersect(bounds(holder, at))) return null;
        }
        if (!area.intersect(bounds(root, at)) || area.isEmpty()) return null;
        return area;
    }

    /** [view]'s bounds in its window. */
    private static Rect bounds(View view, int[] at) {
        view.getLocationInWindow(at);
        return new Rect(at[0], at[1], at[0] + view.getWidth(), at[1] + view.getHeight());
    }

    /**
     * The topmost shown view under ([x], [y]) that draws media, a view {@link #isMedia} names at least
     * {@link #SMALLEST_DP} each way, in the story viewer or inside a profile, a feed video's player or
     * a carousel photo, and the deepest such one where they nest. Inside those, a view on top that
     * holds none, like the story viewer's clear overlay for taps, is looked past to the views under
     * it, and a Litho host on top yields to the media right under it only when that's a picture
     * spanning all of the host, like the band a profile's cover draws over its photo. In the story
     * viewer any media spanning the host wins, like the story under the clear strips Facebook keeps at
     * each side for taps to the previous and next story, which count as media where 64dp is no wider
     * than a strip (#220). Above them a view is looked past only when it draws nothing at the point,
     * like the empty layer Facebook keeps over every screen, so a screen Facebook opens over a profile,
     * leaving the profile shown under it, never hands a pinch to a photo it covers. Anywhere else, null.
     */
    @Nullable
    static View mediaUnder(Activity activity, View root, float x, float y) {
        int smallest = Math.round(SMALLEST_DP * root.getResources().getDisplayMetrics().density);
        boolean story = storyViewer(activity);
        Map<View, Boolean> feedVideos = story ? new IdentityHashMap<>() : feedVideos(root);
        return mediaUnder(root, x, y, story, story, feedVideos, smallest, new int[2], new boolean[1]);
    }

    /** The views of the feed videos Tap to play knows in [root]'s window, each a place a pinch zooms. */
    private static Map<View, Boolean> feedVideos(View root) {
        Map<View, Boolean> found = new IdentityHashMap<>();
        for (Object player : players.known()) {
            View view = players.view(player);
            if (view != null && view.getRootView() == root && players.inFeed(player)) found.put(view, Boolean.TRUE);
        }
        return found;
    }

    /**
     * [open] comes back true when the view found is a Litho host the next media under it still decides
     * on. [story] is true in the story viewer.
     */
    @Nullable
    private static View mediaUnder(View view, float x, float y, boolean zoomable, boolean story,
            Map<View, Boolean> feedVideos, int smallest, int[] at, boolean[] open) {
        open[0] = false;
        if (!zoomable && (profiles.containsKey(view) || feedVideos.containsKey(view) || PhotoCarousel.isRowPhoto(view))) {
            zoomable = true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            // An open host found on top, kept until the next media under it decides.
            View host = null;
            // The child drawn last is on top.
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child = group.getChildAt(i);
                if (!under(child, x, y, at)) continue;
                View found = mediaUnder(child, x, y, zoomable, story, feedVideos, smallest, at, open);
                if (found == null) {
                    if (!zoomable && covers(child, x, y, at)) return null;
                    continue;
                }
                if (!zoomable) return found;
                if (host == null) {
                    if (!open[0]) return found;
                    host = found;
                    continue;
                }
                open[0] = false;
                return (story || isPicture(found)) && holds(found, host, at) ? found : host;
            }
            if (host != null) {
                open[0] = true;
                return host;
            }
        }
        if (zoomable && isMedia(view) && view.getWidth() >= smallest && view.getHeight() >= smallest) {
            open[0] = !isPicture(view);
            return view;
        }
        return null;
    }

    /** True when [view] is shown and ([x], [y]) is inside it. */
    private static boolean under(View view, float x, float y, int[] at) {
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0f) return false;
        view.getLocationInWindow(at);
        return x >= at[0] && x < at[0] + view.getWidth() * view.getScaleX()
                && y >= at[1] && y < at[1] + view.getHeight() * view.getScaleY();
    }

    /** True when [outer] spans all of [inner] on screen. */
    private static boolean holds(View outer, View inner, int[] at) {
        inner.getLocationInWindow(at);
        float left = at[0], top = at[1];
        float right = left + inner.getWidth() * inner.getScaleX(), bottom = top + inner.getHeight() * inner.getScaleY();
        outer.getLocationInWindow(at);
        return at[0] <= left && at[1] <= top
                && at[0] + outer.getWidth() * outer.getScaleX() >= right
                && at[1] + outer.getHeight() * outer.getScaleY() >= bottom;
    }

    /**
     * True when [view] draws anything at ([x], [y]), which hides whatever lies under it there: a
     * background or foreground that isn't clear, a view that isn't a bare {@link View}, a layout that
     * draws itself (Litho's hosts do), or a shown child under the point that does.
     */
    static boolean covers(View view, float x, float y, int[] at) {
        if (!clear(view.getBackground()) || !clear(view.getForeground())) return true;
        if (!(view instanceof ViewGroup)) return view.getClass() != View.class;
        if (!view.willNotDraw()) return true;
        ViewGroup group = (ViewGroup) view;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (under(child, x, y, at) && covers(child, x, y, at)) return true;
        }
        return false;
    }

    /** No drawable, or a color with no alpha. */
    private static boolean clear(@Nullable Drawable drawable) {
        return drawable == null || drawable instanceof ColorDrawable && ((ColorDrawable) drawable).getAlpha() == 0;
    }

    /** A video surface or an ImageView with a picture: media that is a view of its own. */
    static boolean isPicture(View view) {
        if (view instanceof TextureView || view instanceof SurfaceView) return true;
        return view instanceof ImageView && ((ImageView) view).getDrawable() != null;
    }

    /** A video surface, an ImageView with a picture, or a Litho host with no child view that draws no text. */
    static boolean isMedia(View view) {
        if (isPicture(view)) return true;
        if (view instanceof ImageView) return false;
        if (!(view instanceof ViewGroup) || ((ViewGroup) view).getChildCount() != 0) return false;
        try {
            Object content = view.getClass().getMethod("getTextContent").invoke(view);
            return content instanceof List && ((List<?>) content).isEmpty();
        } catch (ReflectiveOperationException | SecurityException e) {
            return false;
        }
    }

    /**
     * Reads [area] off the window's last frame, or a SurfaceView's own surface, or draws [target]
     * where neither can be read.
     */
    static void snapshot(Activity activity, View target, Rect area, Done done) {
        Window window = activity.getWindow();
        boolean wide = wide(window, target) && !wideFailed;
        Bitmap copy = copyBitmap(area.width(), area.height(), wide);
        Handler main = new Handler(Looper.getMainLooper());
        PixelCopy.OnPixelCopyFinishedListener finished = result -> {
            if (result == PixelCopy.SUCCESS) {
                if (wide && Build.VERSION.SDK_INT >= 34) logBrightest(copy, headroom.now(target));
                done.accept(copy);
            } else if (wide) {
                // This device copies no half floats, so it takes the usual 8 bits from here on.
                wideFailed = true;
                snapshot(activity, target, area, done);
            } else {
                done.accept(drawn(target, area, copy));
            }
        };
        // A SurfaceView's picture is a surface of its own, which the window's frame shows as a hole.
        Rect surface = target instanceof SurfaceView ? surfaceArea((SurfaceView) target, area) : null;
        if (surface != null) {
            PixelCopy.request((SurfaceView) target, surface, copy, finished, main);
            return;
        }
        if (window != null && target.isHardwareAccelerated()) {
            PixelCopy.request(window, area, copy, finished, main);
            return;
        }
        done.accept(drawn(target, area, copy));
    }

    /**
     * Whether [window] is in HDR color mode, as Facebook puts the story viewer's (HdrBrightness).
     * There Android 14 and newer show an HDR story above SDR white, and a copy that stops at SDR
     * white looked dimmer than the story it covered, on the panel only, since a screen recording
     * maps both to SDR (#220).
     */
    static boolean hdr(@Nullable Window window) {
        return Build.VERSION.SDK_INT >= 34 && window != null && window.getColorMode() == ActivityInfo.COLOR_MODE_HDR;
    }

    /** Whether a zoom on [target] copies in half floats: in an HDR window, or on a video SurfaceView while the screen is lifted. */
    static boolean wide(@Nullable Window window, View target) {
        return hdr(window) || target instanceof SurfaceView && lifted(target);
    }

    /**
     * Whether the screen [view] is on shows light above SDR white now, Android 14 and newer, with Turn
     * off HDR brightness off. Facebook can draw a story's HDR video on a SurfaceView of its own,
     * which lifts the screen with the window left at the default color mode (#220).
     */
    static boolean lifted(View view) {
        return Build.VERSION.SDK_INT >= 34 && !Settings.TURN_OFF_HDR_BRIGHTNESS.get() && headroom.now(view) > LIFTED;
    }

    /** The HDR to SDR ratio of the screen [view] is on, or 1 where Android can't say. */
    private static float screenHeadroom(View view) {
        if (Build.VERSION.SDK_INT < 34) return 1f;
        Display display = view.getDisplay();
        return display != null && display.isHdrSdrRatioAvailable() ? display.getHdrSdrRatio() : 1f;
    }

    /** A [width] by [height] bitmap to copy into: half floats in extended sRGB where [wide], which keep the light above SDR white, or the usual 8 bits. */
    static Bitmap copyBitmap(int width, int height, boolean wide) {
        if (!wide) return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        return Bitmap.createBitmap(width, height, Bitmap.Config.RGBA_F16, true, ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB));
    }

    /** With Debug logging on, how far past SDR white a wide copy reaches, and the screen's HDR to SDR ratio. */
    @RequiresApi(34)
    private static void logBrightest(Bitmap copy, float ratio) {
        Logger.printDebug(() -> String.format(Locale.US,
                "Pinch to zoom: wide copy, its brightest light is %.2fx SDR white, the screen's ratio %.2f",
                brightest(copy), ratio));
    }

    /** The brightest channel over a grid across [copy], 1 being SDR white. */
    @RequiresApi(29)
    static float brightest(Bitmap copy) {
        float most = 0f;
        int step = Math.max(1, Math.max(copy.getWidth(), copy.getHeight()) / 64);
        for (int y = 0; y < copy.getHeight(); y += step) {
            for (int x = 0; x < copy.getWidth(); x += step) {
                Color color = copy.getColor(x, y);
                most = Math.max(most, Math.max(color.red(), Math.max(color.green(), color.blue())));
            }
        }
        return most;
    }

    /** [area], window coordinates, in [view]'s surface, or null when it has no surface to read. */
    @Nullable
    static Rect surfaceArea(SurfaceView view, Rect area) {
        if (!view.getHolder().getSurface().isValid() || view.getWidth() <= 0 || view.getHeight() <= 0) return null;
        Rect frame = view.getHolder().getSurfaceFrame();
        if (frame.isEmpty()) return null;
        int[] at = new int[2];
        view.getLocationInWindow(at);
        // The surface can be sized apart from the view and stretched over it.
        float wide = frame.width() / (float) view.getWidth();
        float high = frame.height() / (float) view.getHeight();
        Rect surface = new Rect(Math.round((area.left - at[0]) * wide), Math.round((area.top - at[1]) * high),
                Math.round((area.right - at[0]) * wide), Math.round((area.bottom - at[1]) * high));
        return surface.intersect(frame) && !surface.isEmpty() ? surface : null;
    }

    /** [target] drawn into [copy], shifted so [area]'s corner is the copy's. */
    @Nullable
    static Bitmap drawn(View target, Rect area, Bitmap copy) {
        try {
            int[] at = new int[2];
            target.getLocationInWindow(at);
            Canvas canvas = new Canvas(copy);
            canvas.translate(at[0] - area.left, at[1] - area.top);
            target.draw(canvas);
            return copy;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "pinch copy", failure);
            return null;
        }
    }

    static boolean active() {
        return swallowing;
    }

    /**
     * Whether a zoom is up over the window whose top view is [root]. The other hooks in Facebook's
     * touch dispatch may run before this one, and a touch there is the zoom's, not the screen's
     * under it. Never throws.
     */
    public static boolean covers(@Nullable View root) {
        Zoom live = zoom;
        return root != null && live != null && !live.settling && live.root == root;
    }

    @Nullable
    static Zoom zoom() {
        return zoom;
    }

    static void resetForTests() {
        drop();
        swallowing = false;
        holding = false;
        steering = false;
        forgetTaps();
        replaying = false;
        profiles.clear();
        for (ViewTreeObserver observer : watched.keySet()) {
            if (observer.isAlive()) observer.removeOnScrollChangedListener(scrolled);
        }
        watched.clear();
        scrolledAt = Long.MIN_VALUE / 2;
        snapshots = PinchZoom::snapshot;
        places = PinchZoom::mediaUnder;
        frames = PinchZoom::frame;
        players = PATCHED_PLAYERS;
        headroom = PinchZoom::screenHeadroom;
        wideFailed = false;
    }

    /** One zoom: the layer over the window and where the fingers started. */
    static final class Zoom {
        final ViewGroup root;
        final Rect area;
        final FrameLayout layer;
        final View scrim;
        final ImageView copy;
        /** Where the copy sits over the photo: its offset in the layer, which lies over [root] at [originX], [originY]. */
        final float homeX;
        final float homeY;
        private final float originX;
        private final float originY;
        boolean settling;
        /** The copy came back empty, so nothing shows and a story isn't held for it. */
        boolean blank;
        /** Stay zoomed was on when the zoom started. */
        boolean keep;
        /** How dark the window behind gets at full zoom: {@link PinchZoom#SCRIM}, or 0 with Dim around a zoom off. */
        float darkest = SCRIM;
        /** The fingers lifted and the zoom stayed. */
        boolean parked;
        /** The video surface the copy follows (Keep videos playing while zoomed), or null for a still copy. */
        @Nullable View source;
        /** A zoom in the story viewer, which springs back once the story it follows moves on. */
        boolean story;
        /** The copy and the frames read from a SurfaceView keep the light above SDR white ({@link PinchZoom#wide}). */
        boolean wide;
        /** The window the zoom lies over. */
        @Nullable Window window;
        /** The color mode {@link #raise} took the window from, or -1 while it's Facebook's. */
        private int raisedFrom = -1;
        /** The finger on the kept zoom stayed still for the long-press time, and paused or played the video. */
        boolean heldDown;
        /** The player a hold paused, which plays again when the zoom springs back. */
        @Nullable Object pausedPlayer;
        /** A hold with no player known stopped the copy on its frame. */
        boolean frozen;
        /** The copy is taking the video's frames. */
        private boolean ticking;
        /** The two frame buffers, and which one shows now, or -1 for neither. */
        private final Bitmap[] buffers = new Bitmap[2];
        private int shownBuffer = -1;
        private final Runnable tick = this::tick;
        private final Runnable nextTick = () -> {
            if (ticking && !settling) later.postDelayed(this.tick, FRAME_MILLIS);
        };
        private final Runnable hold = this::held;
        /** Where a touch on the kept zoom landed, and whether it has since dragged or pinched. */
        private float downX;
        private float downY;
        boolean moved;
        boolean pinched;
        /** A double tap started this zoom (Double tap to zoom), so a second double tap lets it go. */
        boolean doubleTap;
        /** When and where the last tap on the kept zoom lifted, or 0 for none. */
        private long lastTapUp;
        private float lastTapDownX;
        private float lastTapDownY;
        /** What had focus before the kept zoom took it for Back. */
        @Nullable private View focusedBefore;
        /** The back callback the kept zoom registered, an OnBackInvokedCallback, and its dispatcher (Android 13 and newer). */
        @Nullable Object backCallback;
        @Nullable private Object backDispatcher;
        float scale = 1f;
        private float baseScale = 1f;
        private float startSpan;
        private float startX;
        private float startY;
        private float baseX;
        private float baseY;
        /** A window that closes mid zoom lets go of it, so the copy and the screen behind aren't held. */
        final View.OnAttachStateChangeListener detached = new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                try {
                    if (zoom == Zoom.this) {
                        zoom = null;
                        swallowing = false;
                        holding = false;
                    }
                    remove();
                } catch (Throwable failure) {
                    HookStatus.threw(FAMILY, "pinch", failure);
                }
            }
        };
        /** Back while kept lets go of the zoom and goes no further. */
        final View.OnKeyListener back = (view, code, key) -> {
            if (code != KeyEvent.KEYCODE_BACK) return false;
            if (key.getAction() == KeyEvent.ACTION_UP && !key.isCanceled()) backPressed();
            return true;
        };

        /** Back reached the kept zoom, as a key or through its back callback. */
        void backPressed() {
            try {
                if (zoom == this) release();
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "pinch back", failure);
            }
        }
        /** The window losing focus, to the shade, another app or a dialog, lets go of a kept zoom. */
        final ViewTreeObserver.OnWindowFocusChangeListener focusLost = hasFocus -> {
            try {
                if (!hasFocus && zoom == this) release();
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "pinch", failure);
            }
        };

        Zoom(ViewGroup root, Rect area, MotionEvent event) {
            this.root = root;
            this.area = area;
            Context context = root.getContext();
            layer = new FrameLayout(context);
            layer.setClickable(false);
            layer.setFocusable(false);
            layer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            scrim = new View(context);
            scrim.setBackgroundColor(Color.BLACK);
            scrim.setAlpha(0f);
            layer.addView(scrim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            copy = new ImageView(context);
            copy.setScaleType(ImageView.ScaleType.FIT_XY);
            int[] rootAt = new int[2];
            root.getLocationInWindow(rootAt);
            originX = rootAt[0];
            originY = rootAt[1];
            // The copy is laid out at the layer's corner and moved over the photo, so the spring back
            // eases it to this offset, never to the corner.
            homeX = area.left - originX;
            homeY = area.top - originY;
            copy.setTranslationX(homeX);
            copy.setTranslationY(homeY);
            layer.addView(copy, new FrameLayout.LayoutParams(area.width(), area.height()));
            rebase(event, -1);
            root.addOnAttachStateChangeListener(detached);
        }

        /** The copy arrived: the layer goes over the window. With no copy there's nothing to show. */
        void show(@Nullable Bitmap picture) {
            if (picture == null) blank = true;
            if (picture == null || settling) return;
            if (wide) raise();
            copy.setImageBitmap(picture);
            root.addView(layer, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            apply();
            if (source != null) {
                ticking = true;
                later.postDelayed(tick, FRAME_MILLIS);
            }
        }

        /** Takes the video's newest frame, unless a hold froze the copy, and comes back for the next. */
        private void tick() {
            View from = source;
            if (!ticking || settling || from == null) return;
            try {
                if (!from.isAttachedToWindow() || story && !from.isShown()) {
                    ticking = false;
                    // A story that moved on takes its zoom with it. A video scrolled away keeps its last frame.
                    if (story) settle();
                    return;
                }
                if (frozen) nextTick.run();
                else frames.read(this, from, nextTick);
            } catch (Throwable failure) {
                ticking = false;
                HookStatus.threw(FAMILY, "zoom frame", failure);
            }
        }

        /**
         * The buffer the next frame goes into, [width] by [height]: the one not showing, so a frame is
         * never written while it's drawn. Null for an empty size.
         */
        @Nullable
        Bitmap spare(int width, int height) {
            return spare(width, height, false);
        }

        /** {@link #spare(int, int)}, in half floats where [wide] and this device still copies them. */
        @Nullable
        Bitmap spare(int width, int height, boolean wide) {
            if (width <= 0 || height <= 0) return null;
            int index = shownBuffer == 0 ? 1 : 0;
            Bitmap buffer = buffers[index];
            boolean half = wide && !wideFailed;
            Bitmap.Config config = half ? Bitmap.Config.RGBA_F16 : Bitmap.Config.ARGB_8888;
            if (buffer == null || buffer.getWidth() != width || buffer.getHeight() != height || buffer.getConfig() != config) {
                buffer = copyBitmap(width, height, half);
                // Drawn pixel for pixel, so a placement matrix maps the frame as the video lays it out.
                buffer.setDensity(Bitmap.DENSITY_NONE);
                buffers[index] = buffer;
            }
            return buffer;
        }

        /** Shows [frame] in the copy, placed by [place] where it's a TextureView's, or stretched over it. */
        void showFrame(Bitmap frame, @Nullable Matrix place) {
            if (settling) return;
            if (place != null) {
                copy.setScaleType(ImageView.ScaleType.MATRIX);
                copy.setImageMatrix(place);
            } else {
                copy.setScaleType(ImageView.ScaleType.FIT_XY);
            }
            copy.setImageBitmap(frame);
            shownBuffer = frame == buffers[0] ? 0 : frame == buffers[1] ? 1 : -1;
        }

        /** The finger on the kept zoom moved, pinched or lifted before the long-press time. */
        void cancelHold() {
            later.removeCallbacks(hold);
        }

        /**
         * The finger stayed still on the kept zoom for the long-press time. A video a hold paused
         * plays again. Otherwise the player Tap to play knows for it pauses, or plays when it was
         * already paused, and with no player known the copy stops on its frame or goes on again.
         */
        private void held() {
            try {
                if (!parked || settling || moved || pinched || source == null) return;
                heldDown = true;
                layer.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                Object paused = pausedPlayer;
                if (paused != null) {
                    pausedPlayer = null;
                    frozen = false;
                    players.command(paused, true);
                    return;
                }
                if (frozen) {
                    frozen = false;
                    return;
                }
                Object player = playerOf(source);
                if (player == null) {
                    frozen = true;
                } else if (players.playing(player)) {
                    players.command(player, false);
                    pausedPlayer = player;
                } else {
                    players.command(player, true);
                }
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "zoom hold", failure);
            }
        }

        /** No more frames or holds. */
        private void stopTicks() {
            ticking = false;
            later.removeCallbacks(tick);
            later.removeCallbacks(hold);
        }

        /**
         * The fingers changed, so the next move measures from here, at the scale reached so far. The
         * copy now grows about the point of it under the fingers' new middle, and the move that keeps
         * it where it is on screen goes into its translation.
         */
        void rebase(MotionEvent event, int leaving) {
            float[] focus = focus(event, leaving);
            startX = focus[0];
            startY = focus[1];
            startSpan = span(event, leaving, startX, startY);
            float pivotX = copy.getPivotX();
            float pivotY = copy.getPivotY();
            float translationX = copy.getTranslationX();
            float translationY = copy.getTranslationY();
            float underX = pivotX + (startX - originX - translationX - pivotX) / scale;
            float underY = pivotY + (startY - originY - translationY - pivotY) / scale;
            copy.setPivotX(underX);
            copy.setPivotY(underY);
            copy.setTranslationX(translationX + (scale - 1f) * (underX - pivotX));
            copy.setTranslationY(translationY + (scale - 1f) * (underY - pivotY));
            baseScale = scale;
            baseX = copy.getTranslationX();
            baseY = copy.getTranslationY();
        }

        void move(MotionEvent event, int leaving) {
            float[] focus = focus(event, leaving);
            float span = span(event, leaving, focus[0], focus[1]);
            if (startSpan > 0f) scale = Math.max(1f, Math.min(MOST, baseScale * span / startSpan));
            copy.setTranslationX(baseX + focus[0] - startX);
            copy.setTranslationY(baseY + focus[1] - startY);
            apply();
        }

        private void apply() {
            copy.setScaleX(scale);
            copy.setScaleY(scale);
            scrim.setAlpha(darkest * Math.min(1f, (scale - 1f) / (MOST - 1f) * 3f));
        }

        /** With Stay zoomed on, whether the zoom stays when the fingers lift: it shows, zoomed past {@link PinchZoom#KEPT_LEAST}. */
        boolean keepable() {
            return keep && !settling && !blank && shown() && scale > KEPT_LEAST;
        }

        /**
         * The fingers lifted and the zoom stays. A back callback above Facebook's takes Back where the
         * window hands it to callbacks, and the layer takes focus, so Back reaches it where it comes
         * as a key. Parking a kept zoom again changes nothing: a second callback would outlive it and
         * eat Back, and the layer would be remembered as the focus to give back.
         */
        void park() {
            if (parked) return;
            parked = true;
            focusedBefore = root.findFocus();
            layer.setFocusable(true);
            layer.setFocusableInTouchMode(true);
            layer.setOnKeyListener(back);
            layer.requestFocus();
            root.getViewTreeObserver().addOnWindowFocusChangeListener(focusLost);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) BackCallbacks.register(this);
        }

        /** No longer kept: Back and focus go back to Facebook's views. */
        private void unpark() {
            if (!parked) return;
            parked = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) BackCallbacks.unregister(this);
            layer.setOnKeyListener(null);
            ViewTreeObserver observer = root.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnWindowFocusChangeListener(focusLost);
            layer.setFocusableInTouchMode(false);
            layer.setFocusable(false);
            View before = focusedBefore;
            focusedBefore = null;
            if (before != null && before.isAttachedToWindow()) before.requestFocus();
        }

        /** A touch landed on the kept zoom, and steers it from here. */
        void steer(MotionEvent event) {
            downX = event.getX();
            downY = event.getY();
            moved = false;
            pinched = false;
            heldDown = false;
            cancelHold();
            // A finger held still on a kept zoom that follows a video pauses it or plays it.
            if (source != null && parked) later.postDelayed(hold, ViewConfiguration.getLongPressTimeout());
            rebase(event, -1);
        }

        /** A tap on the kept zoom lifted with [event]: true when it is the second of a double tap. */
        boolean tappedTwice(MotionEvent event) {
            boolean second = lastTapUp != 0
                    && event.getDownTime() - lastTapUp <= ViewConfiguration.getDoubleTapTimeout()
                    && Math.hypot(downX - lastTapDownX, downY - lastTapDownY)
                    <= ViewConfiguration.get(root.getContext()).getScaledDoubleTapSlop();
            lastTapUp = second ? 0 : event.getEventTime();
            lastTapDownX = downX;
            lastTapDownY = downY;
            return second;
        }

        /** A move of a touch on the kept zoom, which stops counting as a tap once past the touch slop. */
        void drag(MotionEvent event) {
            if (!moved) {
                int slop = ViewConfiguration.get(root.getContext()).getScaledTouchSlop();
                moved = Math.hypot(event.getX() - downX, event.getY() - downY) > slop;
            }
            move(event, -1);
        }

        /** Springs back to where the photo sits, then takes the layer away. */
        void settle() {
            if (settling) return;
            settling = true;
            unpark();
            stopTicks();
            // The video a hold paused plays on once the zoom goes.
            Object paused = pausedPlayer;
            pausedPlayer = null;
            if (paused != null) {
                try {
                    players.command(paused, true);
                } catch (Throwable failure) {
                    HookStatus.threw(FAMILY, "zoom hold", failure);
                }
            }
            scale = 1f;
            if (layer.getParent() == null) {
                if (zoom == this) zoom = null;
                remove();
                return;
            }
            Runnable gone = () -> {
                if (zoom == this) zoom = null;
                try {
                    remove();
                } catch (Throwable failure) {
                    HookStatus.threw(FAMILY, "pinch", failure);
                }
            };
            scrim.animate().alpha(0f).setDuration(SETTLE_MILLIS).start();
            copy.animate().scaleX(1f).scaleY(1f).translationX(homeX).translationY(homeY)
                    .setDuration(SETTLE_MILLIS).withEndAction(gone).start();
        }

        /** Takes the layer away now and lets go of the copy. */
        void remove() {
            settling = true;
            unpark();
            stopTicks();
            pausedPlayer = null;
            root.removeOnAttachStateChangeListener(detached);
            copy.animate().cancel();
            scrim.animate().cancel();
            if (layer.getParent() == root) root.removeView(layer);
            copy.setImageDrawable(null);
            buffers[0] = null;
            buffers[1] = null;
            shownBuffer = -1;
            lower();
        }

        /**
         * Puts a window Facebook left at another color mode in HDR one while the zoom shows, so a wide
         * copy of a video that lifted the screen shows its light above SDR white as the video does.
         */
        private void raise() {
            Window over = window;
            if (Build.VERSION.SDK_INT < 34 || over == null || hdr(over)) return;
            raisedFrom = over.getColorMode();
            over.setColorMode(ActivityInfo.COLOR_MODE_HDR);
        }

        /** Hands the window back the color mode {@link #raise} took it from, while it's still the HDR one. */
        private void lower() {
            Window over = window;
            int before = raisedFrom;
            raisedFrom = -1;
            if (before >= 0 && over != null && over.getColorMode() == ActivityInfo.COLOR_MODE_HDR) over.setColorMode(before);
        }

        boolean shown() {
            return layer.getParent() == root;
        }

        /** The pointers' middle, leaving out the one at index [leaving] (lifting), or none for -1. */
        private static float[] focus(MotionEvent event, int leaving) {
            float x = 0f;
            float y = 0f;
            int counted = 0;
            for (int i = 0; i < event.getPointerCount(); i++) {
                if (i == leaving) continue;
                x += event.getX(i);
                y += event.getY(i);
                counted++;
            }
            if (counted == 0) return new float[] {0f, 0f};
            return new float[] {x / counted, y / counted};
        }

        /** How far apart the pointers are: twice their mean distance from ([x], [y]), as ScaleGestureDetector reads it. */
        private static float span(MotionEvent event, int leaving, float x, float y) {
            float total = 0f;
            int counted = 0;
            for (int i = 0; i < event.getPointerCount(); i++) {
                if (i == leaving) continue;
                float dx = event.getX(i) - x;
                float dy = event.getY(i) - y;
                total += (float) Math.hypot(dx, dy);
                counted++;
            }
            return counted == 0 ? 0f : 2f * total / counted;
        }
    }

    /**
     * A kept zoom's back callback, on Android 13 and newer. Overlay priority puts it above every
     * callback of Facebook's, so Back lets go of the zoom and closes nothing. Where the window hands
     * Back to views as a key instead, the dispatcher doesn't take the callback, and the layer's key
     * listener answers. Its own class, so the callback types load only where they exist.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static final class BackCallbacks {
        static void register(Zoom kept) {
            OnBackInvokedDispatcher dispatcher = kept.root.findOnBackInvokedDispatcher();
            if (dispatcher == null) return;
            OnBackInvokedCallback callback = kept::backPressed;
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback);
            kept.backDispatcher = dispatcher;
            kept.backCallback = callback;
        }

        static void unregister(Zoom kept) {
            Object dispatcher = kept.backDispatcher;
            Object callback = kept.backCallback;
            kept.backDispatcher = null;
            kept.backCallback = null;
            if (dispatcher == null || callback == null) return;
            ((OnBackInvokedDispatcher) dispatcher).unregisterOnBackInvokedCallback((OnBackInvokedCallback) callback);
        }
    }
}
