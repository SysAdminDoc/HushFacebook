/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.SeekBar;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.download.OpenInAnotherApp;
import app.morphe.extension.facebook.media.ProgressBar;
import app.morphe.extension.facebook.media.ResumePlayback;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;

/**
 * A separate play/pause button on a feed video's own visible surface, and a cast button across
 * from it (#182). A video Tap to play holds may not have drawn yet, so with no surface the buttons
 * sit on the player's container, where the video will show (#239). Facebook keeps its video taps and fullscreen controls. The Tap to play patch
 * supplies these bridges independently of its autoplay switch and of Hold a feed video for 2x.
 * The cast button also needs the video patch, whose player record holds the file it hands over.
 * A seek bar can run just above the video's bottom edge too, clear of Facebook's own buttons there (#326).
 */
public final class FeedVideoControls {
    private static final String FAMILY = FamilyNames.TAP_TO_PLAY;
    private static final String SOURCE = "FeedVideoControls";
    /** Touch target, its gap from the video's sides, and the disc drawn in it, sized like Facebook's speaker icon (#301). */
    static final int SIZE_DP = 40, MARGIN_DP = 4, DISC_DP = 28;
    /** How far below the video's top the buttons sit, clear of the channel picture on a reel style post, which takes their taps otherwise. */
    static final int TOP_DP = 54;
    /** The seek bar's touch height, short because a feed video's caption sits right above it, and the room a drag's time takes above it. */
    static final int BAR_DP = 20, TIME_DP = 28;
    /** The room the seek bar leaves under it for what Facebook puts in the video's bottom corners: the speaker, and the tagged people (#326). */
    static final int BAR_LIFT_DP = 40;
    private static final long TICK_MS = 250;
    /** How long the bar waits for a seek to land, and how near the spot counts as landed. */
    static final long LANDING_MS = 3000;
    private static final int LANDED_WITHIN_MS = 1000;
    private static final Map<Object, Control> players = new WeakHashMap<>();
    private static Handler main;

    interface Players {
        ViewGroup view(Object player);
        Object params(Object player);
        Object origin(Object player);
        String videoId(Object params);
        boolean eligible(Object params);
        boolean playing(Object player);
        void command(Object player, boolean play);
        void cast(View from, String videoId);
        int position(Object player);
        int length(Object player, Object params);
        void seek(Object player, int positionMs);
    }

    private static final Players PATCHED = new Players() {
        public ViewGroup view(Object player) { return playerView(player); }
        public Object params(Object player) { return playerParams(player); }
        public Object origin(Object player) { return playerOrigin(player); }
        public String videoId(Object params) { return mediaId(params); }
        public boolean eligible(Object params) { return ordinaryVideo(params); }
        public boolean playing(Object player) { return isPlaying(player); }
        public void command(Object player, boolean play) { commandPlayer(player, play); }
        public void cast(View from, String videoId) { OpenInAnotherApp.cast(from.getContext(), videoId); }
        public int position(Object player) { return playerPosition(player); }
        public int length(Object player, Object params) {
            int length = playerLength(player);
            return length > 0 ? length : paramsLength(params);
        }
        public void seek(Object player, int positionMs) { seekPlayer(player, null, positionMs); }
    };
    static volatile Players access = PATCHED;
    @Nullable static volatile Boolean inBuildForTests;
    @Nullable static volatile Boolean videoPatchInBuildForTests;
    @Nullable static volatile WindowInsets insetsForTests;

    private FeedVideoControls() { }

    // Filled by the patch with checked, public native members. Only their declared types enter them.
    @Nullable public static ViewGroup playerView(Object player) { return null; }
    @Nullable public static Object playerParams(Object player) { return null; }
    @Nullable public static Object playerOrigin(Object player) { return null; }
    @Nullable public static String mediaId(Object params) { return null; }
    public static boolean ordinaryVideo(Object params) {
        return !liveNow(params) && !sponsored(params) && !audioOnly(params) && !animatedGif(params);
    }
    public static boolean liveNow(Object params) { return true; }
    public static boolean sponsored(Object params) { return true; }
    public static boolean audioOnly(Object params) { return true; }
    public static boolean animatedGif(Object params) { return true; }
    public static boolean isPlaying(Object player) { return false; }
    public static void commandPlayer(Object player, boolean play) { }
    public static int playerPosition(Object player) { return 0; }
    public static int playerLength(Object player) { return 0; }
    public static int paramsLength(Object params) { return 0; }
    /** [slot] only lends the patch a register for Facebook's trigger. */
    public static void seekPlayer(Object player, @Nullable Object slot, int positionMs) { }

    /** First in the native attach. The queued refresh reads the field after Facebook writes it. */
    public static void attached(@Nullable ViewGroup container, @Nullable Object player) {
        if (container == null || player == null) return;
        try {
            Control control;
            synchronized (players) {
                control = players.get(player);
                if (control == null) {
                    control = new Control(player);
                    players.put(player, control);
                }
                control.generation++;
                control.expected = new WeakReference<>(container);
                control.released = false;
            }
            post(control);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "inline attach", failure);
        }
    }

    /** A native play or pause is about to run. Refresh after that call has returned. */
    public static void changed(@Nullable Object player) { invalidate(player, false, false); }

    /** A new binding retires the old button, including queued clicks on it. */
    public static void rebound(@Nullable Object player) { invalidate(player, true, false); }

    /** A released player stays retired until a fresh attach, even if it still exposes old params. */
    public static void released(@Nullable Object player) { invalidate(player, true, true); }

    private static void invalidate(@Nullable Object player, boolean retire, boolean released) {
        try {
            Control control;
            synchronized (players) {
                control = players.get(player);
                if (control == null) return;
                if (retire) control.generation++;
                if (released) control.released = true;
            }
            post(control);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "inline state", failure);
        }
    }

    /** Also used by ordinary edits and imports, so switching off removes an existing button. */
    public static void settingsChanged() {
        for (Control control : snapshot()) post(control);
    }

    private static List<Control> snapshot() {
        synchronized (players) { return new ArrayList<>(players.values()); }
    }

    private static void post(Control control) {
        synchronized (players) {
            if (main == null) main = new Handler(Looper.getMainLooper());
            main.post(() -> refresh(control));
        }
    }

    private static boolean inBuild() {
        Boolean forced = inBuildForTests;
        return (forced != null ? forced : SettingsStatus.tapToPlay()) && Utils.settingsReady();
    }

    private static boolean playOn() {
        return inBuild() && Settings.INLINE_FEED_VIDEO_CONTROLS.get();
    }

    private static boolean castOn() {
        if (!inBuild()) return false;
        Boolean forced = videoPatchInBuildForTests;
        return (forced != null ? forced : SettingsStatus.videoDownload()) && Settings.FEED_VIDEO_CAST_BUTTON.get();
    }

    private static boolean seekOn() {
        return inBuild() && Settings.FEED_VIDEO_SEEK_BAR.get();
    }

    private static void refresh(Control control) {
        try {
            Object player = control.player.get();
            ViewGroup container = control.expected.get();
            boolean play = playOn();
            boolean cast = castOn();
            boolean seek = seekOn();
            if ((!play && !cast && !seek) || control.released || player == null || container == null
                    || access.view(player) != container) {
                control.remove(true);
                return;
            }
            control.observe(container);
            if (!container.isAttachedToWindow()) {
                control.remove(false);
                return;
            }
            Target target = target(control, play && cast);
            if (target == null) {
                control.remove(false);
                return;
            }
            control.button = place(control, control.button, play ? target : null, false);
            control.cast = place(control, control.cast, cast ? target : null, true);
            if (control.button != null) control.button.state(access.playing(player));
            control.bar = placeBar(control, control.bar, seek ? target : null, player);
        } catch (Throwable failure) {
            control.remove(true);
            HookStatus.threw(FAMILY, "inline feed button", failure);
        }
    }

    /** Keeps, makes or drops one button for [target]: null takes it off. Answers what's showing. */
    @Nullable
    private static Button place(Control control, @Nullable Button current, @Nullable Target target, boolean cast) {
        if (current != null && (target == null || current.generation != control.generation
                || current.params.get() != target.params || current.surface.get() != target.surface
                || !current.videoId.equals(target.videoId)
                || current.getParent() != target.content)) {
            detach(current);
            current = null;
        }
        if (target == null) return null;
        if (current == null) {
            current = new Button(control, target, cast);
            target.content.addView(current, new FrameLayout.LayoutParams(target.size, target.size,
                    Gravity.LEFT | Gravity.TOP));
            HookStatus.bound(FAMILY, cast ? "inline cast button" : "inline feed button");
        }
        int left = cast ? target.right : target.left;
        FrameLayout.LayoutParams layout = (FrameLayout.LayoutParams) current.getLayoutParams();
        if (layout.leftMargin != left || layout.topMargin != target.top) {
            layout.leftMargin = left;
            layout.topMargin = target.top;
            current.setLayoutParams(layout);
        }
        return current;
    }

    /** Keeps, makes or drops the seek bar for [target], which may have no room for one. Answers what's showing. */
    @Nullable
    private static Scrub placeBar(Control control, @Nullable Scrub current, @Nullable Target target, Object player) {
        Rect area = target == null ? null : target.bar;
        if (current != null && (area == null || current.generation != control.generation
                || current.params.get() != target.params || current.surface.get() != target.surface
                || !current.videoId.equals(target.videoId)
                || current.getParent() != target.content)) {
            detach(current);
            current = null;
        }
        if (area == null) return null;
        if (current == null) {
            current = new Scrub(control, target);
            target.content.addView(current, new FrameLayout.LayoutParams(area.width(), area.height(),
                    Gravity.LEFT | Gravity.TOP));
            HookStatus.bound(FAMILY, "feed video seek bar");
            current.update(player);
            control.tick();
        }
        // A drag's time needs room above the line, which stays put at the bottom.
        int lift = current.dragging ? Math.round(TIME_DP * current.getResources().getDisplayMetrics().density) : 0;
        FrameLayout.LayoutParams layout = (FrameLayout.LayoutParams) current.getLayoutParams();
        if (layout.leftMargin != area.left || layout.topMargin != area.top - lift
                || layout.width != area.width() || layout.height != area.height() + lift) {
            layout.leftMargin = area.left;
            layout.topMargin = area.top - lift;
            layout.width = area.width();
            layout.height = area.height() + lift;
            current.setLayoutParams(layout);
        }
        return current;
    }

    /**
     * Takes [button] off the screen. A feed list recycling a video row in its own layout pass gets
     * here while the content frame is laying out its children, from a count taken before the loop,
     * so then the button only hides and comes off once that pass is done.
     */
    private static void detach(View button) {
        ViewParent parent = button.getParent();
        if (!(parent instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) parent;
        if (!group.isInLayout()) {
            group.removeView(button);
            return;
        }
        button.setVisibility(View.GONE);
        group.post(() -> {
            if (button.getParent() == group) group.removeView(button);
        });
    }

    /** Where the buttons go on the video [control] shows, or null when none fit. [both] needs room for two. */
    @Nullable
    private static Target target(Control control, boolean both) {
        Object player = control.player.get();
        ViewGroup container = control.expected.get();
        if (player == null || container == null || control.released || access.view(player) != container
                || !container.isAttachedToWindow() || !container.isShown() || !container.hasWindowFocus()) return null;
        Object params = access.params(player);
        Object origin = access.origin(player);
        String id = params == null ? null : access.videoId(params);
        if (params == null || id == null || id.isEmpty() || !access.eligible(params)) {
            return miss(control, "no video id, or a live video, ad, audio or GIF");
        }
        if (origin == null || !origin.toString().startsWith("newsfeed::")) return miss(control, "origin", origin);
        View row = container;
        View list = null;
        for (ViewParent parent = container.getParent(); parent instanceof View; parent = parent.getParent()) {
            View view = (View) parent;
            if (PostLongPress.isNamed(view, PostLongPress.LIST)) {
                if ((!view.canScrollVertically(1) && !view.canScrollVertically(-1))
                        || (row.getWidth() == view.getWidth() && row.getHeight() == view.getHeight())) {
                    return miss(control, "its list doesn't scroll or the video fills it");
                }
                list = view;
                break;
            }
            if (PostLongPress.isScroller(view)) return miss(control, "inside a side scroller");
            row = view;
        }
        if (list == null) return miss(control, "not in the feed's list");
        List<View> surfaces = new ArrayList<>(2);
        surfaces(container, surfaces);
        if (surfaces.size() > 1) return miss(control, "more than one video surface");
        if (surfaces.isEmpty() && container.getAlpha() <= 0f) return miss(control, "no surface, container transparent");
        View surface = surfaces.isEmpty() ? container : surfaces.get(0);
        for (Control other : snapshot()) {
            Object owner = other.player.get();
            if (other == control || other.released || owner == null) continue;
            ViewGroup otherContainer = access.view(owner);
            if (otherContainer != null && otherContainer == other.expected.get()
                    && (descendant(surface, otherContainer)
                        || surface == container && descendant(otherContainer, container))) {
                return miss(control, "another player shares it");
            }
        }
        View content = container.getRootView().findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout) || !descendant(container, content)) return miss(control, "outside the screen's content");
        Rect visible = new Rect();
        Rect clip = new Rect();
        if (!surface.getGlobalVisibleRect(visible) || !list.getGlobalVisibleRect(clip) || !visible.intersect(clip)
                || !content.getGlobalVisibleRect(clip) || !visible.intersect(clip)) {
            return miss(control, "scrolled out of view");
        }
        float density = container.getResources().getDisplayMetrics().density;
        int size = Math.round(SIZE_DP * density);
        int margin = Math.round(MARGIN_DP * density);
        int down = Math.round(TOP_DP * density);
        int across = both ? size * 2 + margin * 3 : size + margin * 2;
        if (visible.width() < across || visible.height() < down + size + margin) return miss(control, "too little of it shows");
        int[] at = new int[2];
        Rect bar = seekOn() ? barArea(control, container, surface, list, content, visible, down + size + margin, density) : null;
        content.getLocationInWindow(at);
        // Margins count from inside the padding, which holds the status bar on an edge to edge screen.
        at[0] += content.getPaddingLeft();
        at[1] += content.getPaddingTop();
        if (bar != null) bar.offset(-at[0], -at[1]);
        control.missed = null;
        return new Target(params, id, surface, (FrameLayout) content, size,
                visible.left - at[0] + margin, visible.right - at[0] - margin - size, visible.top - at[1] + down, bar);
    }

    /**
     * Where the seek bar goes in the window, {@link #BAR_LIFT_DP} above the bottom of the video's
     * [visible] part, or null when that spot isn't showing, or something on top of the feed lies
     * across it. The buttons keep [room] of the height.
     */
    @Nullable
    /** Whether a bar ending at window position [bottom] reaches the strip Android keeps for its home swipe. No app can claim touches there, so the bar would look live and never get a drag. */
    private static boolean inGestureStrip(View content, int bottom) {
        WindowInsets insets = insetsForTests != null ? insetsForTests : content.getRootWindowInsets();
        return insets != null
                && bottom > content.getRootView().getHeight() - insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom;
    }

    private static Rect barArea(Control control, ViewGroup container, View surface, View list, View content,
            Rect visible, int room, float density) {
        // A surface can be taller than its container, which crops it, so the video ends at the higher edge of the two.
        int[] at = new int[2];
        surface.getLocationInWindow(at);
        int bottom = at[1] + surface.getHeight();
        container.getLocationInWindow(at);
        bottom = Math.min(bottom, at[1] + container.getHeight()) - Math.round(BAR_LIFT_DP * density);
        int height = Math.round(BAR_DP * density);
        if (visible.bottom < bottom || visible.height() < room + height) return noBar(control, "its bottom edge doesn't show", null);
        if (inGestureStrip(content, bottom)) return noBar(control, "its bottom edge is in the system's gesture strip", null);
        Rect area = new Rect(visible.left, bottom - height, visible.right, bottom);
        View over = over(list, content, area);
        if (over != null) return noBar(control, "something lies over its bottom edge", over.getClass().getName());
        control.missedBar = null;
        return area;
    }

    /** Puts why [control]'s video has no seek bar in the debug log, like {@link #miss}. Answers null. */
    @Nullable
    private static Rect noBar(Control control, String why, @Nullable Object detail) {
        if (!BaseSettings.DEBUG.get()) {
            control.missedBar = null;
        } else if (!why.equals(control.missedBar)) {
            control.missedBar = why;
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE,
                    () -> "Feed video seek bar: none, " + why + (detail == null ? "" : " " + detail));
        }
        return null;
    }

    /** The first thing drawn above [list] that lies across [area], such as the tab bar sliding back. */
    @Nullable
    private static View over(View list, View content, Rect area) {
        View child = list;
        for (ViewParent parent = list.getParent(); parent instanceof ViewGroup; parent = parent.getParent()) {
            ViewGroup group = (ViewGroup) parent;
            for (int i = group.indexOfChild(child) + 1; i < group.getChildCount(); i++) {
                View found = lying(group.getChildAt(i), area);
                if (found != null) return found;
            }
            if (group == content) break;
            child = group;
        }
        return null;
    }

    /** [view] or one inside it that shows across [area]. Frames only count by what they hold, and a bare spacer doesn't. */
    @Nullable
    private static View lying(View view, Rect area) {
        if (view instanceof Button || view instanceof Scrub || !view.isShown() || view.getAlpha() <= 0f) return null;
        Rect rect = new Rect();
        if (!view.getGlobalVisibleRect(rect) || !Rect.intersects(rect, area)) return null;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = lying(group.getChildAt(i), area);
                if (found != null) return found;
            }
            return null;
        }
        return view.getClass() == View.class && view.getBackground() == null ? null : view;
    }

    @Nullable
    private static Target miss(Control control, String why) { return miss(control, why, null); }

    /**
     * Puts why [control]'s video gets no button in the debug log, once until that changes, so
     * turning Debug logging on later still names a video already missing one. Answers null.
     */
    @Nullable
    private static Target miss(Control control, String why, @Nullable Object detail) {
        if (!BaseSettings.DEBUG.get()) {
            control.missed = null;
        } else if (!why.equals(control.missed)) {
            control.missed = why;
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE,
                    () -> "Inline feed button: none, " + why + (detail == null ? "" : " " + detail));
        }
        return null;
    }

    private static void surfaces(View view, List<View> found) {
        if (found.size() > 1 || !view.isAttachedToWindow() || !view.isShown() || view.getAlpha() <= 0f) return;
        if (view instanceof TextureView || view instanceof SurfaceView) {
            Rect visible = new Rect();
            if (view.getGlobalVisibleRect(visible) && !visible.isEmpty()) found.add(view);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && found.size() < 2; i++) surfaces(group.getChildAt(i), found);
        }
    }

    private static boolean descendant(View view, View ancestor) {
        for (View current = view; current != null;
                current = current.getParent() instanceof View ? (View) current.getParent() : null) {
            if (current == ancestor) return true;
        }
        return false;
    }

    private static void click(Button button) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post(button.control);
            return;
        }
        Control control = button.control;
        try {
            if (!(button.cast ? castOn() : playOn()) || (button.cast ? control.cast : control.button) != button
                    || control.generation != button.generation) return;
            Object player = control.player.get();
            if (player == null || !stillOver(control, button, button.params, button.videoId, button.surface)) {
                control.remove(false);
                return;
            }
            if (button.cast) {
                access.cast(button, button.videoId);
                HookStatus.counted(FAMILY, "inline cast");
                return;
            }
            access.command(player, !access.playing(player));
            HookStatus.counted(FAMILY, "inline feed control");
            refresh(control);
        } catch (Throwable failure) {
            control.remove(true);
            HookStatus.threw(FAMILY, "inline feed control", failure);
        }
    }

    /** Whether [view], made for [videoId]'s [params] on [surface], still sits on that video. */
    private static boolean stillOver(Control control, View view, WeakReference<Object> params, String videoId,
            WeakReference<View> surface) {
        Target current = target(control, false);
        return current != null && current.params == params.get() && videoId.equals(current.videoId)
                && current.surface == surface.get() && view.getParent() == current.content;
    }

    /** The bar was let go, or moved by a screen reader, at [bar]'s progress. */
    private static void seek(Scrub bar) {
        Control control = bar.control;
        try {
            if (!seekOn() || control.bar != bar || control.generation != bar.generation) return;
            Object player = control.player.get();
            if (player == null || !stillOver(control, bar, bar.params, bar.videoId, bar.surface)) {
                control.remove(false);
                return;
            }
            ResumePlayback.placedByYou(player);
            access.seek(player, bar.getProgress());
            bar.landing = bar.getProgress();
            bar.landingUntil = SystemClock.uptimeMillis() + LANDING_MS;
            HookStatus.counted(FAMILY, "feed video seek");
        } catch (Throwable failure) {
            control.remove(true);
            HookStatus.threw(FAMILY, "feed video seek", failure);
        }
    }

    private static final class Target {
        final Object params;
        final String videoId;
        final View surface;
        final FrameLayout content;
        final int size, left, right, top;
        /** Where the seek bar goes in [content], or null when it has no place. */
        @Nullable final Rect bar;
        Target(Object params, String videoId, View surface, FrameLayout content, int size, int left, int right, int top,
                @Nullable Rect bar) {
            this.params = params; this.videoId = videoId; this.surface = surface; this.content = content;
            this.size = size; this.left = left; this.right = right; this.top = top; this.bar = bar;
        }
    }

    private static final class Control implements ViewTreeObserver.OnPreDrawListener,
            View.OnAttachStateChangeListener {
        final WeakReference<Object> player;
        volatile WeakReference<ViewGroup> expected = new WeakReference<>(null);
        volatile long generation;
        volatile boolean released;
        /** The last reason the debug log gave for no button, or null since one showed. */
        @Nullable String missed;
        /** The same for the seek bar, which can miss while the buttons show. */
        @Nullable String missedBar;
        private WeakReference<ViewGroup> observed = new WeakReference<>(null);
        private WeakReference<ViewTreeObserver> observer = new WeakReference<>(null);
        @Nullable Button button;
        @Nullable Button cast;
        @Nullable Scrub bar;
        /** Moves the bar along. Read on this tick rather than on each drawn frame, which comes far more often. */
        private final Runnable mover = this::move;
        Control(Object player) { this.player = new WeakReference<>(player); }
        private void move() {
            Scrub showing = bar;
            Object owner = player.get();
            if (showing == null || owner == null) return;
            try {
                showing.update(owner);
                tick();
            } catch (Throwable failure) {
                remove(true);
                HookStatus.threw(FAMILY, "feed video seek bar", failure);
            }
        }
        void tick() {
            if (main == null) return;
            main.removeCallbacks(mover);
            main.postDelayed(mover, TICK_MS);
        }
        void observe(ViewGroup view) {
            ViewTreeObserver next = view.getViewTreeObserver();
            if (observed.get() == view && observer.get() == next) return;
            remove(true);
            observed = new WeakReference<>(view);
            observer = new WeakReference<>(next);
            view.addOnAttachStateChangeListener(this);
            next.addOnPreDrawListener(this);
        }
        void remove(boolean listeners) {
            if (button != null) detach(button);
            if (cast != null) detach(cast);
            if (bar != null) {
                detach(bar);
                if (main != null) main.removeCallbacks(mover);
            }
            button = null;
            cast = null;
            bar = null;
            if (!listeners) return;
            ViewGroup view = observed.get();
            if (view != null) view.removeOnAttachStateChangeListener(this);
            ViewTreeObserver tree = observer.get();
            if (tree != null && tree.isAlive()) tree.removeOnPreDrawListener(this);
            observed.clear();
            observer.clear();
        }
        public boolean onPreDraw() { refresh(this); return true; }
        public void onViewAttachedToWindow(View view) { post(this); }
        public void onViewDetachedFromWindow(View view) { generation++; remove(false); }
    }

    /**
     * A 40 dp target showing a small translucent disc with a white symbol, like Facebook's own
     * speaker icon: play or pause, or the cast one for [cast].
     */
    static final class Button extends View {
        final Control control;
        final boolean cast;
        final long generation;
        final WeakReference<Object> params;
        final String videoId;
        final WeakReference<View> surface;
        final Look look = new Look();
        boolean playing;
        Button(Control control, Target target, boolean cast) {
            super(target.content.getContext());
            this.control = control;
            this.cast = cast;
            generation = control.generation;
            params = new WeakReference<>(target.params);
            videoId = target.videoId;
            surface = new WeakReference<>(target.surface);
            setClickable(true);
            setFocusable(true);
            setOnClickListener(view -> click(this));
            if (cast) {
                setContentDescription(L10n.t("Cast this video"));
                setTooltipText(getContentDescription());
            } else {
                state(false);
            }
        }
        void state(boolean value) {
            if (cast || getContentDescription() != null && playing == value) return;
            playing = value;
            setContentDescription(L10n.t(value ? "Pause feed video" : "Play feed video"));
            setTooltipText(getContentDescription());
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            look.draw(canvas, getWidth(), getHeight(), getResources().getDisplayMetrics().density, cast, playing);
        }
    }

    /**
     * A thin white line with a thumb along the video's bottom edge, drawn here so it looks the same
     * under every Facebook theme. The list never sees a drag on it, since it sits above the list in
     * the screen's content, and being a SeekBar keeps Long press opens comments off it. A drag only
     * shows the time, and the video moves when it's let go.
     */
    static final class Scrub extends SeekBar {
        final Control control;
        final long generation;
        final WeakReference<Object> params;
        final String videoId;
        final WeakReference<View> surface;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rect = new RectF();
        boolean dragging;
        boolean cancelled;
        int landing = -1;
        long landingUntil;
        Scrub(Control control, Target target) {
            super(target.content.getContext());
            this.control = control;
            generation = control.generation;
            params = new WeakReference<>(target.params);
            videoId = target.videoId;
            surface = new WeakReference<>(target.surface);
            setBackground(null);
            setThumb(null);
            setProgressDrawable(null);
            setThumbOffset(0);
            setPadding(0, 0, 0, 0);
            setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            setContentDescription(L10n.t("Feed video position"));
            paint.setTextAlign(Paint.Align.CENTER);
            setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    if (fromUser && !dragging) seek(Scrub.this);
                }
                @Override public void onStartTrackingTouch(SeekBar bar) {
                    dragging = true;
                    invalidate();
                }
                @Override public void onStopTrackingTouch(SeekBar bar) {
                    dragging = false;
                    if (!cancelled) seek(Scrub.this);
                    invalidate();
                }
            });
        }
        void update(Object player) {
            if (dragging) return;
            Object of = params.get();
            int length = of == null ? 0 : access.length(player, of);
            setVisibility(length > 0 ? View.VISIBLE : View.GONE);
            if (length <= 0) return;
            if (getMax() != length) setMax(length);
            int position = Math.max(0, Math.min(length, access.position(player)));
            // The player reports the old spot until a seek lands, which would spring the bar back.
            if (landing >= 0 && Math.abs(position - landing) > LANDED_WITHIN_MS && SystemClock.uptimeMillis() < landingUntil) return;
            landing = -1;
            setProgress(position);
        }
        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            // A SeekBar only claims the spot around a thumb drawable, and this one draws its own, so a drag starting near the screen's side went to Back.
            setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, right - left, bottom - top)));
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            // A cancel stops the tracking too, and isn't a let go.
            cancelled = event.getActionMasked() == MotionEvent.ACTION_CANCEL;
            return super.onTouchEvent(event);
        }
        @Override protected synchronized void onDraw(Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            float y = getHeight() - 6 * density;
            float half = 1.5f * density;
            float radius = (dragging ? 8 : 6) * density;
            float x = getMax() <= 0 ? 0 : getWidth() * (float) getProgress() / getMax();
            paint.setColor(0x4D000000);
            canvas.drawRect(0, y - half - density, getWidth(), y + half + density, paint);
            paint.setColor(0x80FFFFFF);
            canvas.drawRect(0, y - half, getWidth(), y + half, paint);
            paint.setColor(0xFFFFFFFF);
            canvas.drawRect(0, y - half, x, y + half, paint);
            canvas.drawCircle(Math.max(radius, Math.min(getWidth() - radius, x)), y, radius, paint);
            if (dragging) drawTime(canvas, x, y - radius - 2 * density, density);
        }
        /** Where a let go would land, on a small dark pill just above the line, kept on the video. */
        private void drawTime(Canvas canvas, float x, float bottom, float density) {
            String time = ProgressBar.time(getProgress());
            paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, getResources().getDisplayMetrics()));
            float across = paint.measureText(time) / 2 + 6 * density;
            float middle = Math.max(across, Math.min(getWidth() - across, x));
            float top = Math.max(0, bottom - paint.getFontSpacing());
            rect.set(middle - across, top, middle + across, bottom);
            paint.setColor(0xCC000000);
            canvas.drawRoundRect(rect, 4 * density, 4 * density, paint);
            paint.setColor(0xFFFFFFFF);
            canvas.drawText(time, middle, (top + bottom - paint.ascent() - paint.descent()) / 2, paint);
        }
    }

    /** What a [Button] draws, kept apart so it can be drawn without a player. */
    static final class Look {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rect = new RectF();
        final Path path = new Path();
        void draw(Canvas canvas, int width, int height, float density, boolean cast, boolean playing) {
            float cx = width / 2f, cy = height / 2f;
            float r = Math.min(Math.min(cx, cy), DISC_DP / 2f * density);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x66000000);
            canvas.drawCircle(cx, cy, r, paint);
            paint.setColor(0xFFFFFFFF);
            // The symbol's square box, centered on the disc.
            float s = r * 1.7f, x = cx - s / 2, y = cy - s / 2;
            if (cast) {
                drawCast(canvas, x, y, s);
            } else if (playing) {
                canvas.drawRect(x + s * .32f, y + s * .28f, x + s * .44f, y + s * .72f, paint);
                canvas.drawRect(x + s * .56f, y + s * .28f, x + s * .68f, y + s * .72f, paint);
            } else {
                path.reset();
                path.moveTo(x + s * .38f, y + s * .26f);
                path.lineTo(x + s * .72f, y + s * .50f);
                path.lineTo(x + s * .38f, y + s * .74f);
                path.close();
                canvas.drawPath(path, paint);
            }
        }
        /** The usual cast symbol in the [s] box at [left], [top]: a screen outline open at its lower left, with signal arcs there. */
        private void drawCast(Canvas canvas, float left, float top, float s) {
            float stroke = s * .08f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setStrokeCap(Paint.Cap.ROUND);
            path.reset();
            path.moveTo(left + s * .26f, top + s * .40f);
            path.lineTo(left + s * .26f, top + s * .28f);
            path.lineTo(left + s * .76f, top + s * .28f);
            path.lineTo(left + s * .76f, top + s * .72f);
            path.lineTo(left + s * .56f, top + s * .72f);
            canvas.drawPath(path, paint);
            float x = left + s * .26f, y = top + s * .72f;
            for (float r : new float[] {s * .14f, s * .26f}) {
                rect.set(x - r, y - r, x + r, y + r);
                canvas.drawArc(rect, 270, 90, false, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x + stroke * .5f, y - stroke * .5f, stroke * .9f, paint);
        }
    }

    /** Every player that has attached a video and not been dropped yet, for Pause reels while comments are open. */
    public static List<Object> knownPlayers() {
        List<Object> found = new ArrayList<>();
        synchronized (players) {
            for (Object player : players.keySet()) if (player != null) found.add(player);
        }
        return found;
    }

    /** Whether the screen point [x], [y] is on a seek bar, so a press there only drags it. */
    static boolean barAt(View root, int x, int y) {
        for (Control control : snapshot()) {
            Scrub bar = control.bar;
            if (bar == null || !bar.isShown() || bar.getRootView() != root) continue;
            int[] at = new int[2];
            bar.getLocationOnScreen(at);
            if (x >= at[0] && x < at[0] + bar.getWidth() && y >= at[1] && y < at[1] + bar.getHeight()) return true;
        }
        return false;
    }

    @Nullable static SeekBar showingBar(Object player) {
        synchronized (players) {
            Control control = players.get(player);
            return control == null ? null : control.bar;
        }
    }

    @Nullable static View showing(Object player) {
        synchronized (players) {
            Control control = players.get(player);
            return control == null ? null : control.button;
        }
    }

    @Nullable static View showingCast(Object player) {
        synchronized (players) {
            Control control = players.get(player);
            return control == null ? null : control.cast;
        }
    }

    static void reset() {
        for (Control control : snapshot()) control.remove(true);
        synchronized (players) {
            if (main != null) main.removeCallbacksAndMessages(null);
            players.clear();
        }
        access = PATCHED;
        inBuildForTests = null;
        videoPatchInBuildForTests = null;
        insetsForTests = null;
    }
}
