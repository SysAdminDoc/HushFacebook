/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Built on SysAdminDoc/HushGram's Burn-in guard (GPL-3.0).
 */
package app.morphe.extension.facebook.reels;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;

/**
 * Burn-in guard (#288), HushGram's ported to Facebook: keeps a reel's fixed controls from sitting on
 * the same pixels of an OLED screen for hours.
 *
 * <p>The Reels screen: the window in front has focus, and its largest shown video surface, a
 * TextureView or SurfaceView, shows over at least {@link #REELS_SHARE} of it ({@link #reelOf}).
 * Facebook's views carry no ids to know a reel by, and the Reels tab and a reel or video opened full
 * screen are built differently, but each plays on one surface that fills the screen. A reel takes
 * about 87% of an S22's window with the tab bar showing, a tall video in the feed about 58% and a
 * landscape one about 26%. A video opened from the feed plays on a page of Facebook's full-screen
 * viewer sized to the video, so a wide one there is small too, but its page is the screen's size
 * ({@link #onAFullPage}, #370). Facebook's camera and story editor fill the screen with a preview
 * too, and their shutter and tools are never dimmed ({@link #camera}).
 *
 * <p>The dim: once the Reels screen has gone the Dim after choice ({@link BurnInWait}) without a
 * touch, what shows beside the video is drawn at {@link #IDLE_LEVEL} of what Facebook draws it at,
 * and the next touch brings it back. Going up from each shown surface to the content frame, the
 * shown views at each level that hold no surface are dimmed: the reel's buttons, author and caption,
 * the top bar and the tab bar. A surface's own branch never is, so a reel the pager holds ready
 * beside the one playing isn't dimmed whole. Only alpha changes, so a dimmed button still takes its
 * tap. A finger held on the screen counts as a touch until it lifts. Hushfacebook's own buttons on the
 * window's decor view, outside the content frame, dim with them: the story eye ({@link #overlay}).
 *
 * <p>What a touch is: a finger going down on something that takes taps other than the video itself
 * ({@link #quietAt}), or moving as a swipe does. A tap on the video (to pause or play, a double tap
 * to like) leaves the controls dimmed and the wait running. With Stay dim when I tap on ({@link
 * #stayDim}), no finger on the Reels screen is a touch, swipes to the next reel included: the wait
 * runs from arriving on Reels, and once dimmed the controls stay dimmed until you leave it.
 *
 * <p>The shift moves the window's content around a small square, {@link #STEP_DP} at a time, every
 * {@link #SHIFT_EVERY_MS}. It's one view's translation, the content frame's, which Facebook doesn't
 * animate, where each control's would be fought over with Facebook's own animations. A window the
 * screen under it shows through, the story viewer's, is never moved ({@link #seeThrough}, #369).
 *
 * <p>Both run only on the Reels screen, with the choice on, Hushfacebook not paused and the activity
 * in front. While guarding, a look that doesn't find the Reels screen is made again {@link
 * #SETTLE_MS} later before it counts as leaving, since a swipe between reels shows half of each for
 * a moment. Anywhere else nothing dims and the content goes back where it was. A view whose alpha,
 * or a content frame whose translation, something else changed after Hushfacebook wrote it is left
 * the way that left it. The window getting focus back counts as a touch, so coming back from the
 * settings or another screen doesn't land on dimmed controls.
 *
 * <p>No layout listener runs for nothing: with the choice off an activity gets only a window focus
 * listener, which hears the choice turned on once the settings close. While it's on, a layout asks
 * for a look at most every {@link #LOOK_MS}, and while the controls are dimmed a look comes every
 * {@link #KEEP_MS} to dim what Facebook brought back on its own. A draw listener dims a dimmed view
 * again before the frame that would show Facebook's alpha ({@link #redim}), and a layout while dimmed
 * dims what it brought in at once, so a reel scrolled to is dimmed before its first frame.
 */
public final class BurnInGuard {
    private static final String FAMILY = FamilyNames.REEL_DECLUTTER;

    /** The share of Facebook's own opacity the controls dim to. */
    static final float IDLE_LEVEL = 0.25f;
    /**
     * The least share of the window the largest video surface shows over on the Reels screen, and of
     * the video a view that takes taps covers for a tap on it to be a tap on the video.
     */
    static final float REELS_SHARE = 0.7f;
    /** The least share of a full-screen viewer's page on the screen for it to have settled there. */
    static final float PAGE_SHOWN = 0.95f;
    /** The package of Facebook's camera and story editor screens (585: InspirationCameraActivity). */
    static final String CAMERA_PACKAGE = "com.facebook.inspiration.";
    /** How long the content stays in one place. */
    static final long SHIFT_EVERY_MS = 120_000L;
    static final float STEP_DP = 2f;
    /** The most often a layout asks for a look. */
    static final long LOOK_MS = 250L;
    /**
     * How often a pass looks again while the controls are dimmed, for an alpha Facebook put back on
     * its own with no layout to say so. A look that finds nothing changed writes nothing.
     */
    static final long KEEP_MS = 1_000L;
    /**
     * How long a look that doesn't find the Reels screen waits for a second one before it counts as
     * leaving: mid-swipe, half of each reel shows and neither fills the screen.
     */
    static final long SETTLE_MS = 600L;
    /** The most views looked at in one search, so a strange tree can't make a long one. */
    static final int MAX_VIEWS = 4000;

    /** Where the content goes, in steps: where Facebook put it, then once around it. */
    private static final int[][] PLACES = {
            {0, 0}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}
    };

    static final String COUNT_DIMMED = "Reels controls dimmed while idle";
    static final String COUNT_SHIFTED = "Reels screen moved a step";
    static final String COUNT_QUIET = "Reels tap on the video left the controls dimmed";
    static final String COUNT_STAY_DIM = "Reels touch left the controls dimmed (Stay dim when I tap)";

    /** The application this process's callbacks went on, so a second start adds none. */
    private static WeakReference<Application> watching = new WeakReference<>(null);
    private static final Map<Window, Boolean> WRAPPED = new WeakHashMap<>();
    /** Views this class dimmed: the alpha they had, then the alpha written here. */
    private static final Map<View, float[]> DIMMED = new WeakHashMap<>();
    /** Content views this class moved: the [x, y] they had, then the [x, y] written here. */
    private static final Map<View, float[]> MOVED = new WeakHashMap<>();
    /** Hushfacebook's own buttons put on a window's decor view ({@link #overlay}). Main thread. */
    private static final Map<View, Boolean> OVERLAYS = new WeakHashMap<>();
    private static final Runnable TICK = BurnInGuard::tick;
    private static final Focus FOCUS = new Focus();
    private static final Layout LAYOUT = new Layout();
    private static final Draw DRAW = new Draw();

    private static Handler handler;
    /** The activity in front, the one every pass looks at. */
    private static WeakReference<Activity> front = new WeakReference<>(null);
    /** The activity in front lets the screen under it show through ({@link #seeThrough}). */
    private static boolean frontSeeThrough;
    /** The decor view whose observer has the listeners. */
    private static WeakReference<View> observed = new WeakReference<>(null);
    private static boolean layoutWatched;
    /** When a finger last touched the screen. */
    private static long touchedAt;
    /** A finger is on the screen: holding still is still a touch, and the wait starts when it lifts. */
    private static boolean fingerDown;
    /** The last pass found the Reels screen with the choice on. */
    private static boolean guarding;
    /** The last pass dimmed the controls, and nothing has brought them back since. */
    private static boolean dimming;
    /** The finger now down went down on the video itself, and hasn't swiped. */
    private static boolean quiet;
    /** Where on the screen that finger went down. */
    private static float quietX, quietY;
    /** When the next timed pass is due, or -1 when none is. */
    private static long tickAt = -1L;
    /** When a look made while guarding first didn't find the Reels screen, or -1 while none has. */
    private static long doubtSince = -1L;

    private BurnInGuard() {
    }

    /** From the settings entry's application hook, in the main process, with Clean up Reels in the build. */
    public static synchronized void install(Context context) {
        try {
            if (!(context instanceof Application)) return;
            Application application = (Application) context;
            if (watching.get() == application) return;
            application.registerActivityLifecycleCallbacks(new Lifecycle());
            watching = new WeakReference<>(application);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "burn-in guard start", failure);
        }
    }

    /**
     * From where Hushfacebook adds a button of its own to a window's decor view, beside the content
     * frame, where the dim's walk up from the video never reaches: the story eye. While it shows on
     * the window in front it dims and comes back with Facebook's controls. Main thread.
     */
    public static void overlay(View view) {
        OVERLAYS.put(view, Boolean.TRUE);
    }

    /** The choice now: off until the settings are read and while Hushfacebook is paused. */
    static BurnInMode mode() {
        if (!Utils.settingsReady() || Setting.isPaused()) return BurnInMode.OFF;
        BurnInMode mode = Settings.BURN_IN_GUARD.get();
        return mode == null ? BurnInMode.OFF : mode;
    }

    /** How long the screen goes untouched before the controls dim: the Dim after choice. */
    static long idleAfter() {
        BurnInWait wait = Settings.BURN_IN_WAIT.get();
        return (wait == null ? BurnInWait.FIVE_SECONDS : wait).millis;
    }

    /** Stay dim when I tap: no finger on the Reels screen brings the controls back. Off while paused. */
    static boolean stayDim() {
        return Settings.BURN_IN_STAY_DIM.get();
    }

    /** The place the content is in at [now], in steps of {@link #STEP_DP}. */
    static int[] placeAt(long now) {
        return PLACES[(int) ((now / SHIFT_EVERY_MS) % PLACES.length)];
    }

    /** As [activity] comes to the front: listens to it and looks once. */
    static void resumed(Activity activity) {
        try {
            leave();
            front = new WeakReference<>(activity);
            frontSeeThrough = seeThrough(activity);
            View decor = activity.getWindow() == null ? null : activity.getWindow().peekDecorView();
            if (decor == null) return;
            ViewTreeObserver observer = decor.getViewTreeObserver();
            if (!observer.isAlive()) return;
            observer.addOnWindowFocusChangeListener(FOCUS);
            observed = new WeakReference<>(decor);
            touchedAt = SystemClock.uptimeMillis();
            fingerDown = false;
            pass();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "burn-in guard watch", failure);
        }
    }

    /** As [activity] leaves the front: puts back what it changed and takes its listeners off. */
    static void paused(Activity activity) {
        try {
            if (front.get() == activity) leave();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "burn-in guard release", failure);
        }
    }

    private static void leave() {
        Activity activity = front.get();
        undim();
        if (activity != null) place(activity, false, 0L);
        watchLayouts(false);
        View decor = observed.get();
        if (decor != null && decor.getViewTreeObserver().isAlive()) {
            decor.getViewTreeObserver().removeOnWindowFocusChangeListener(FOCUS);
        }
        observed = new WeakReference<>(null);
        front = new WeakReference<>(null);
        guarding = false;
        fingerDown = false;
        quiet = false;
        doubtSince = -1L;
        schedule(Long.MAX_VALUE);
    }

    /**
     * Looks at the activity in front: dims or brings back its controls, moves its content or puts it
     * back, and asks for a pass at the moment either next changes, since nothing lays out on its own
     * while a reel just plays.
     */
    static void pass() {
        Activity activity = front.get();
        if (activity == null) return;
        try {
            Window window = activity.getWindow();
            View decor = window == null ? null : window.peekDecorView();
            BurnInMode mode = mode();
            watchLayouts(mode != BurnInMode.OFF);
            long now = SystemClock.uptimeMillis();
            List<View> surfaces = mode == BurnInMode.OFF || decor == null || camera(activity)
                    ? Collections.<View>emptyList() : surfaces(decor);
            boolean on = decor != null && reelOf(decor, surfaces) != null;
            if (!on && guarding && mode != BurnInMode.OFF && decor != null) {
                // Mid-swipe, or a moment without focus: what's dimmed stays so, and a second look decides.
                if (doubtSince < 0L) doubtSince = now;
                if (now - doubtSince < SETTLE_MS) {
                    schedule(doubtSince + SETTLE_MS);
                    return;
                }
            }
            doubtSince = -1L;
            if (on) {
                watch(window);
                // Coming onto the Reels screen starts the wait.
                if (!guarding) touchedAt = now;
            }
            guarding = on;
            long wait = idleAfter();
            boolean dim = on && !fingerDown && now - touchedAt >= wait;
            if (dim) {
                dimAll(decor, surfaces);
                dimming = true;
            } else {
                undim();
            }
            boolean shifting = on && mode == BurnInMode.DIM_AND_SHIFT && !frontSeeThrough;
            place(activity, shifting, now);
            long next = !on || fingerDown ? Long.MAX_VALUE : dim ? now + KEEP_MS : touchedAt + wait;
            schedule(Math.min(next,
                    shifting ? (now / SHIFT_EVERY_MS + 1) * SHIFT_EVERY_MS : Long.MAX_VALUE));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "burn-in guard", failure);
            try {
                undim();
                place(activity, false, 0L);
            } catch (Throwable again) {
                HookStatus.threw(FAMILY, "burn-in guard put back", again);
            }
        }
    }

    /** Whether [activity] is Facebook's camera or story editor, whose full-screen picture is a preview. */
    static boolean camera(Activity activity) {
        return activity.getClass().getName().startsWith(CAMERA_PACKAGE);
    }

    /**
     * Whether [activity]'s window lets the screen under it show through, as the story viewer's does
     * (585: StoryViewerActivity's theme is translucent on a clear background). Moved a step, its
     * content would uncover a strip of the feed beneath along one edge (#369), so it's never moved.
     */
    static boolean seeThrough(Activity activity) {
        TypedArray style = activity.obtainStyledAttributes(new int[]{android.R.attr.windowIsTranslucent});
        try {
            return style.getBoolean(0, false);
        } finally {
            style.recycle();
        }
    }

    /** The video the Reels screen in [decor] plays, or null when [decor] isn't showing it. */
    @Nullable
    static View reelOf(View decor) {
        return reelOf(decor, surfaces(decor));
    }

    /**
     * The largest of [surfaces] (the shown ones in [decor]) by the part of it on the screen, when the
     * window has focus and that part is at least {@link #REELS_SHARE} of the window or it plays on a
     * full-screen viewer's page ({@link #onAFullPage}). Null otherwise.
     */
    @Nullable
    private static View reelOf(View decor, List<View> surfaces) {
        int width = decor.getWidth();
        int height = decor.getHeight();
        if (surfaces.isEmpty() || width <= 0 || height <= 0 || !decor.hasWindowFocus()) return null;
        View largest = null;
        long most = 0L;
        Rect shown = new Rect();
        for (View surface : surfaces) {
            if (!surface.getGlobalVisibleRect(shown) || !shown.intersect(0, 0, width, height)) continue;
            long area = (long) shown.width() * shown.height();
            if (area > most) {
                most = area;
                largest = surface;
            }
        }
        if (largest == null) return null;
        return most >= REELS_SHARE * width * height || onAFullPage(largest, decor) ? largest : null;
    }

    /**
     * Whether [surface] plays on a page of a scrolling view that shows over at least {@link
     * #REELS_SHARE} of the window, the page exactly the scrolling view's size and settled on the
     * screen: Facebook's full-screen video viewer, where a video sits on a page the size of the screen
     * whatever its own shape (585 on a 720x1600 emulator: the pager and its page are 720x1453 of a
     * 720x1558 content frame, and the TextureView in it is the video's shape, 720x1280 for a tall one)
     * (#370). The pager is a RecyclerView ({@link PostLongPress#LIST}), which doesn't scroll while it
     * holds just the one video, so a list counts whether it scrolls or not. A post in the feed is a
     * page of a scrolling list too, but never exactly the list's own height. The nearest scrolling
     * view or list above [surface] decides.
     */
    private static boolean onAFullPage(View surface, View decor) {
        View content = decor.findViewById(android.R.id.content);
        Rect shown = new Rect();
        for (View page = surface; page != content; ) {
            View pager = parentOf(page);
            if (pager == null) return false;
            if (pager.canScrollVertically(1) || pager.canScrollVertically(-1)
                    || PostLongPress.isNamed(pager, PostLongPress.LIST)) {
                if (page.getWidth() != pager.getWidth() || page.getHeight() != pager.getHeight()) return false;
                if (!pager.getGlobalVisibleRect(shown) || !shown.intersect(0, 0, decor.getWidth(), decor.getHeight())
                        || (long) shown.width() * shown.height() < REELS_SHARE * decor.getWidth() * decor.getHeight()) {
                    return false;
                }
                return page.getGlobalVisibleRect(shown)
                        && (long) shown.width() * shown.height() >= PAGE_SHOWN * page.getWidth() * page.getHeight();
            }
            page = pager;
        }
        return false;
    }

    /** The shown TextureViews and SurfaceViews under [root], looking at no more than {@link #MAX_VIEWS} views. */
    private static List<View> surfaces(View root) {
        List<View> found = new ArrayList<>();
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(root);
        int seen = 0;
        while (!pending.isEmpty() && seen++ < MAX_VIEWS) {
            View view = pending.poll();
            if (view.getVisibility() != View.VISIBLE) continue;
            if (view instanceof TextureView || view instanceof SurfaceView) {
                found.add(view);
            } else if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) pending.add(group.getChildAt(i));
            }
        }
        return found;
    }

    /**
     * Dims what shows beside the video in [decor]: going up from each of [surfaces] in the content
     * frame to the frame itself, the shown views at each level that hold none of them. A view dimmed
     * before that no longer is one of those (a reel the pager moved on from) gets its alpha back.
     */
    private static void dimAll(View decor, List<View> surfaces) {
        View content = decor.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;
        Set<View> bright = Collections.newSetFromMap(new IdentityHashMap<>());
        List<View> path = new ArrayList<>();
        for (View surface : surfaces) {
            path.clear();
            View at = surface;
            while (at != null && at != content && !bright.contains(at)) {
                path.add(at);
                at = parentOf(at);
            }
            // Outside the content frame, and so not the reel's.
            if (at == null) continue;
            bright.addAll(path);
            bright.add(content);
        }
        Set<View> beside = Collections.newSetFromMap(new IdentityHashMap<>());
        for (View held : bright) {
            if (!(held instanceof ViewGroup)) continue;
            ViewGroup group = (ViewGroup) held;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() == View.VISIBLE && !bright.contains(child)) beside.add(child);
            }
        }
        for (View overlay : OVERLAYS.keySet()) {
            if (overlay != null && overlay.getVisibility() == View.VISIBLE && overlay.getParent() != null
                    && overlay.getRootView() == decor) {
                beside.add(overlay);
            }
        }
        for (Iterator<Map.Entry<View, float[]>> entries = DIMMED.entrySet().iterator(); entries.hasNext(); ) {
            Map.Entry<View, float[]> entry = entries.next();
            View view = entry.getKey();
            if (view != null && beside.contains(view)) continue;
            if (view != null && view.getAlpha() == entry.getValue()[1]) view.setAlpha(entry.getValue()[0]);
            entries.remove();
        }
        for (View view : beside) dim(view);
    }

    @Nullable
    private static View parentOf(View view) {
        ViewParent parent = view.getParent();
        return parent instanceof View ? (View) parent : null;
    }

    private static void dim(View view) {
        float alpha = view.getAlpha();
        float[] held = DIMMED.get(view);
        if (held == null) {
            held = new float[]{alpha, alpha * IDLE_LEVEL};
            DIMMED.put(view, held);
            HookStatus.counted(FAMILY, COUNT_DIMMED);
        } else if (alpha != held[1]) {
            // Facebook set its own since; that's what the dim is taken from now.
            held[0] = alpha;
            held[1] = alpha * IDLE_LEVEL;
        }
        if (alpha != held[1]) view.setAlpha(held[1]);
    }

    /** Brings back each view dimmed here, if its alpha is still the one written here. */
    private static void undim() {
        dimming = false;
        if (DIMMED.isEmpty()) return;
        for (Map.Entry<View, float[]> entry : DIMMED.entrySet()) {
            View view = entry.getKey();
            float[] held = entry.getValue();
            if (view != null && view.getAlpha() == held[1]) view.setAlpha(held[0]);
        }
        DIMMED.clear();
    }

    private static void place(Activity activity, boolean shifting, long now) {
        // peekDecorView, so an activity that never made its views doesn't get them made here as it leaves.
        Window window = activity.getWindow();
        View decor = window == null ? null : window.peekDecorView();
        View content = decor == null ? null : decor.findViewById(android.R.id.content);
        if (content == null) return;
        float[] held = MOVED.get(content);
        if (!shifting) {
            if (held != null) {
                MOVED.remove(content);
                if (content.getTranslationX() == held[2]) content.setTranslationX(held[0]);
                if (content.getTranslationY() == held[3]) content.setTranslationY(held[1]);
            }
            return;
        }
        float x = content.getTranslationX();
        float y = content.getTranslationY();
        if (held == null) {
            held = new float[]{x, y, x, y};
            MOVED.put(content, held);
        } else {
            // Something else wrote its own since the last pass; that's where the content is now.
            if (x != held[2]) held[0] = x;
            if (y != held[3]) held[1] = y;
        }
        int[] at = placeAt(now);
        float step = STEP_DP * content.getResources().getDisplayMetrics().density;
        held[2] = held[0] + at[0] * step;
        held[3] = held[1] + at[1] * step;
        if (x == held[2] && y == held[3]) return;
        content.setTranslationX(held[2]);
        content.setTranslationY(held[3]);
        HookStatus.counted(FAMILY, COUNT_SHIFTED);
    }

    private static void schedule(long next) {
        if (next == Long.MAX_VALUE) next = -1L;
        if (next == tickAt) return;
        Handler main = handler();
        main.removeCallbacks(TICK);
        tickAt = next;
        if (next >= 0L) main.postAtTime(TICK, next);
    }

    private static void tick() {
        tickAt = -1L;
        pass();
    }

    /**
     * A pass as soon as the main thread is free, in place of any timed one. It counts as due now, so
     * a layout before it runs leaves it be rather than putting it off by up to {@link #LOOK_MS}.
     */
    private static void passSoon() {
        Handler main = handler();
        main.removeCallbacks(TICK);
        tickAt = SystemClock.uptimeMillis();
        main.post(TICK);
    }

    /**
     * A finger on the window ([action] is its MotionEvent action, or -1 for the window getting focus
     * back): dimmed controls come back now, and a lift asks for a pass so the wait is timed from it.
     * The watcher stays on a window once added, so with the guard off a lift asks for nothing; the
     * focus coming back still does, which is how a choice made in the settings is seen. With it on, a
     * lift off the Reels screen asks too, since a swipe that settles on a reel needn't lay anything out.
     */
    static void touched(int action) {
        if (action < 0 && guarding && stayDim()) {
            // Stay dim: a sheet closing leaves the controls as they are, and the pass still sees a
            // choice changed in the settings.
            if (front.get() != null) passSoon();
            return;
        }
        touchedAt = SystemClock.uptimeMillis();
        boolean lifted = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || action < 0;
        if (action == MotionEvent.ACTION_DOWN) fingerDown = true;
        else if (lifted) fingerDown = false;
        undim();
        if (lifted && (guarding || action < 0 || mode() != BurnInMode.OFF) && front.get() != null) passSoon();
    }

    /**
     * A finger event on the window. While the guard is on, one that goes down on the video itself
     * isn't a touch ({@link #quietAt}) until it lifts, unless it moves farther than a tap does or a
     * second finger joins: a swipe to the next reel or a pinch counts from then. With Stay dim when I
     * tap on, no finger on the Reels screen is a touch at all.
     */
    static void seen(MotionEvent event) {
        int action = event.getActionMasked();
        if (guarding && stayDim()) {
            quiet = false;
            fingerDown = false;
            if (action == MotionEvent.ACTION_DOWN) HookStatus.counted(FAMILY, COUNT_STAY_DIM);
            return;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            quiet = guarding && quietAt(event.getRawX(), event.getRawY());
            if (quiet) {
                quietX = event.getRawX();
                quietY = event.getRawY();
                HookStatus.counted(FAMILY, COUNT_QUIET);
                return;
            }
        } else if (quiet) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                quiet = false;
                return;
            }
            if (action == MotionEvent.ACTION_MOVE && !swiped(event)) return;
            quiet = false;
            touched(MotionEvent.ACTION_DOWN);
            return;
        }
        touched(action);
    }

    /** The quiet finger moved farther from where it went down than a tap does. */
    private static boolean swiped(MotionEvent event) {
        Activity activity = front.get();
        int slop = activity == null ? 0 : ViewConfiguration.get(activity).getScaledTouchSlop();
        float dx = event.getRawX() - quietX;
        float dy = event.getRawY() - quietY;
        return dx * dx + dy * dy > (float) slop * slop;
    }

    /**
     * A finger going down at [x, y] on the screen lands on the video itself: the deepest view there
     * that takes taps, as the window hands the touch on, is none, the video or a view it sits in, or
     * one covering at least {@link #REELS_SHARE} of the video, the layer that takes the taps that
     * pause it. Anything smaller is one of the controls, and off the Reels screen it's a touch.
     */
    static boolean quietAt(float x, float y) {
        Activity activity = front.get();
        Window window = activity == null ? null : activity.getWindow();
        View decor = window == null ? null : window.peekDecorView();
        if (decor == null) return false;
        View video = reelOf(decor);
        if (video == null) return false;
        View hit = landsOn(decor, x, y, new int[1], new int[2]);
        if (hit == null) return true;
        for (View view = video; view != null; view = parentOf(view)) {
            if (view == hit) return true;
        }
        Rect hitBox = onScreen(hit);
        Rect videoBox = onScreen(video);
        long videoArea = (long) videoBox.width() * videoBox.height();
        return videoArea > 0 && hitBox.intersect(videoBox)
                && (long) hitBox.width() * hitBox.height() >= REELS_SHARE * videoArea;
    }

    private static Rect onScreen(View view) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return new Rect(at[0], at[1], at[0] + view.getWidth(), at[1] + view.getHeight());
    }

    /** The deepest view under [view] at [x, y] on the screen that takes taps, trying the topmost first, or null when none does. */
    @Nullable
    private static View landsOn(View view, float x, float y, int[] seen, int[] at) {
        if (seen[0]++ >= MAX_VIEWS || view.getVisibility() != View.VISIBLE) return null;
        view.getLocationOnScreen(at);
        if (x < at[0] || y < at[1] || x >= at[0] + view.getWidth() || y >= at[1] + view.getHeight()) return null;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View found = landsOn(group.getChildAt(i), x, y, seen, at);
                if (found != null) return found;
            }
        }
        return view.isClickable() || view.isLongClickable() ? view : null;
    }

    /** Adds or takes off the layout and draw listeners on the window in front. */
    private static void watchLayouts(boolean on) {
        if (on == layoutWatched) return;
        View decor = observed.get();
        if (on && decor == null) return;
        layoutWatched = on;
        if (decor == null || !decor.getViewTreeObserver().isAlive()) return;
        ViewTreeObserver observer = decor.getViewTreeObserver();
        if (on) {
            observer.addOnGlobalLayoutListener(LAYOUT);
            observer.addOnPreDrawListener(DRAW);
        } else {
            observer.removeOnGlobalLayoutListener(LAYOUT);
            observer.removeOnPreDrawListener(DRAW);
        }
    }

    /**
     * Dims again each dimmed view whose alpha Facebook changed since, from Facebook's new alpha. A
     * write is drawn on the next frame, so waiting for the {@link #KEEP_MS} look would let a control
     * flash bright for up to a second. Done before the frame is drawn, the flash never shows.
     */
    private static void redim() {
        if (DIMMED.isEmpty()) return;
        for (Map.Entry<View, float[]> entry : DIMMED.entrySet()) {
            View view = entry.getKey();
            if (view != null && view.getAlpha() != entry.getValue()[1]) dim(view);
        }
    }

    private static Handler handler() {
        Looper main = Looper.getMainLooper();
        if (handler == null || handler.getLooper() != main) handler = new Handler(main);
        return handler;
    }

    /** Wraps [window]'s callback once, to see touches. */
    private static void watch(@Nullable Window window) {
        if (window == null || WRAPPED.containsKey(window)) return;
        Window.Callback inner = window.getCallback();
        if (inner == null) return;
        window.setCallback((Window.Callback) Proxy.newProxyInstance(BurnInGuard.class.getClassLoader(),
                new Class<?>[]{Window.Callback.class}, new Watcher(inner)));
        WRAPPED.put(window, Boolean.TRUE);
    }

    /** Passes every call on to the callback it wraps, and looks at touches on the way. */
    private static final class Watcher implements InvocationHandler {
        final Window.Callback inner;

        Watcher(Window.Callback inner) {
            this.inner = inner;
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (args != null && args.length == 1 && args[0] instanceof MotionEvent
                    && "dispatchTouchEvent".equals(method.getName())) {
                try {
                    seen((MotionEvent) args[0]);
                } catch (Throwable failure) {
                    HookStatus.threw(FAMILY, "burn-in guard touch", failure);
                }
            }
            try {
                return method.invoke(inner, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause() == null ? failure : failure.getCause();
            }
        }
    }

    /** The window getting focus back: the settings closing, or a screen over it going away. */
    private static final class Focus implements ViewTreeObserver.OnWindowFocusChangeListener {
        @Override public void onWindowFocusChanged(boolean hasFocus) {
            if (!hasFocus) return;
            try {
                touched(-1);
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "burn-in guard focus", failure);
            }
        }
    }

    /** A frame about to be drawn: what Facebook brought back while dimmed is dimmed again first. */
    private static final class Draw implements ViewTreeObserver.OnPreDrawListener {
        @Override public boolean onPreDraw() {
            try {
                redim();
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "burn-in guard redim", failure);
            }
            return true;
        }
    }

    /**
     * A layout: a new reel, or another screen, so a look soon. While the controls are dimmed, what the
     * layout brought in (the next reel's buttons as it scrolls in) is dimmed now, before it's drawn.
     */
    private static final class Layout implements ViewTreeObserver.OnGlobalLayoutListener {
        private long lastLook = Long.MIN_VALUE / 2;

        @Override public void onGlobalLayout() {
            View decor = observed.get();
            if (dimming && decor != null) {
                try {
                    // Still on Reels: a layout that opened another screen is left to the pass, which brings them back.
                    List<View> surfaces = surfaces(decor);
                    if (reelOf(decor, surfaces) != null) dimAll(decor, surfaces);
                } catch (Throwable failure) {
                    HookStatus.threw(FAMILY, "burn-in guard layout dim", failure);
                }
            }
            long now = SystemClock.uptimeMillis();
            long due = Math.max(now, lastLook + LOOK_MS);
            if (tickAt >= 0L && tickAt <= due) return;
            lastLook = due;
            schedule(due);
        }
    }

    private static final class Lifecycle implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(@NonNull Activity activity) {
            resumed(activity);
        }

        @Override public void onActivityPaused(@NonNull Activity activity) {
            paused(activity);
        }

        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(@NonNull Activity activity) { }
        @Override public void onActivityStopped(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
        @Override public void onActivityDestroyed(@NonNull Activity activity) { }
    }

    static long nextPassAtForTests() {
        return tickAt;
    }

    static boolean watchingLayoutsForTests() {
        return layoutWatched;
    }

    static void resetForTests() {
        leave();
        if (handler != null) handler.removeCallbacks(TICK);
        frontSeeThrough = false;
        tickAt = -1L;
        touchedAt = 0L;
        doubtSince = -1L;
        LAYOUT.lastLook = Long.MIN_VALUE / 2;
        DIMMED.clear();
        MOVED.clear();
        OVERLAYS.clear();
        WRAPPED.clear();
    }
}
