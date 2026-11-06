/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
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
import app.morphe.extension.facebook.download.SendLink;
import app.morphe.extension.facebook.media.ProgressBar;
import app.morphe.extension.facebook.media.ResumePlayback;
import app.morphe.extension.facebook.media.TapToPlay;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.facebook.stories.StoryProgressBar;
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
 * Two more switches can lower the bar and those buttons together, and fade the bar and the speaker
 * while the video plays untouched (#386). A video story in the story viewer can get the seek bar
 * alone, along its card's bottom, with a fade switch of its own (#386). A live video gets play/pause and the full player button but no cast button or seek bar, having
 * no file to hand over and no end to seek to. The full player button, next to play, opens the video
 * in Facebook's own player with all its controls, a wide one in the full screen player that turns
 * sideways rather than the upright reels viewer (#239). Like that player, a feed video can also
 * get buttons that jump back or ahead 10 seconds and one that steps its speed, and the seek bar can
 * show the time so far and the time left at its ends (#239). A live video gets none of those.
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
    /** The most Lower the seek bar moves the bar and those buttons down, keeping {@link #MARGIN_DP} under the lowest button (#386). */
    static final int LOWER_DP = 20;
    /** What counts as one of those buttons: at most this big, and this near one of the video's sides. */
    static final int CORNER_BUTTON_DP = 64, CORNER_DP = 80;
    /** How long the bar and the speaker stay bright after the video starts or a touch, how faint they get, and how long that takes (#386). */
    static final long FADE_AFTER_MS = 3000, FADE_ANIMATION_MS = 300;
    static final float FADED_ALPHA = 0.3f;
    /**
     * A story's seek bar: its touch height, how far its bottom sits above the story card's, which
     * puts its line where Progress bar at the bottom draws, and its gap from the card's sides (#386).
     */
    static final int STORY_BAR_DP = 28, STORY_ABOVE_DP = 7, STORY_SIDE_DP = 8;
    private static final long TICK_MS = 250;
    /** Facebook's link handler, which opens a watch link in its own video player. */
    static final String URI_HANDLER = "com.facebook.katana.IntentUriHandler";
    /** The full screen video player's own link, which takes the video id. */
    static final String FULL_SCREEN_LINK = "fb://fullscreen_video/";
    /** What a [Button] does. */
    static final int PLAY = 0, CAST = 1, OPEN = 2, BACK = 3, AHEAD = 4, SPEED = 5;
    /** How far the skip buttons jump, as Facebook's own player does (#239). */
    static final int SKIP_MS = 10000;
    /** The speeds the speed button steps through, a tap at a time, from 1x back round to it (#239). */
    static final float[] SPEEDS = {1f, 1.25f, 1.5f, 2f, 0.5f};
    /** How long the bar waits for a seek to land, and how near the spot counts as landed. */
    static final long LANDING_MS = 3000;
    private static final int LANDED_WITHIN_MS = 1000;
    private static final Map<Object, Control> players = new WeakHashMap<>();
    private static Handler main;
    /** The seek bar last put on a story, which Progress bar at the bottom makes way for while it shows. */
    private static volatile WeakReference<Scrub> storyBar = new WeakReference<>(null);

    interface Players {
        ViewGroup view(Object player);
        Object params(Object player);
        Object origin(Object player);
        String videoId(Object params);
        boolean eligible(Object params);
        boolean live(Object params);
        boolean playing(Object player);
        void command(Object player, boolean play);
        void cast(View from, String videoId);
        /** [wide] is a video wider than it's tall, which goes to the full screen player that turns sideways. */
        void open(View from, String videoId, boolean wide);
        int position(Object player);
        int length(Object player, Object params);
        void seek(Object player, int positionMs);
        /** The player's speed, or NaN when the build can't read it. */
        float speed(Object player);
        void speed(Object player, float speed);
    }

    private static final Players PATCHED = new Players() {
        public ViewGroup view(Object player) { return playerView(player); }
        public Object params(Object player) { return playerParams(player); }
        public Object origin(Object player) { return playerOrigin(player); }
        public String videoId(Object params) { return mediaId(params); }
        public boolean eligible(Object params) { return ordinaryVideo(params); }
        public boolean live(Object params) { return liveNow(params); }
        public boolean playing(Object player) { return isPlaying(player); }
        public void command(Object player, boolean play) { commandPlayer(player, play); }
        public void cast(View from, String videoId) { OpenInAnotherApp.cast(from.getContext(), videoId); }
        public void open(View from, String videoId, boolean wide) {
            // With Tap to play on, the player this tap opens plays, as one a tapped feed video opens does.
            TapToPlay.feedVideoOpening();
            openFullPlayer(from.getContext(), videoId, wide);
        }
        public int position(Object player) { return playerPosition(player); }
        public int length(Object player, Object params) {
            int length = playerLength(player);
            return length > 0 ? length : paramsLength(params);
        }
        public void seek(Object player, int positionMs) { seekPlayer(player, null, positionMs); }
        // Hold a reel for 2x fills both: the getter for reels and feed videos alike, the setter for a feed video's hold.
        public float speed(Object player) { return ReelHold.playerSpeed(player); }
        public void speed(Object player, float speed) { FeedVideoHold.setPlayerSpeed(player, speed); }
    };
    static volatile Players access = PATCHED;
    @Nullable static volatile Boolean inBuildForTests;
    @Nullable static volatile Boolean videoPatchInBuildForTests;
    @Nullable static volatile Boolean speedInBuildForTests;
    @Nullable static volatile WindowInsets insetsForTests;

    private FeedVideoControls() { }

    // Filled by the patch with checked, public native members. Only their declared types enter them.
    @Nullable public static ViewGroup playerView(Object player) { return null; }
    @Nullable public static Object playerParams(Object player) { return null; }
    @Nullable public static Object playerOrigin(Object player) { return null; }
    @Nullable public static String mediaId(Object params) { return null; }
    /** Not an ad, audio or a GIF. A live video counts, and {@link #liveNow} says which parts it gets. */
    public static boolean ordinaryVideo(Object params) {
        return !sponsored(params) && !audioOnly(params) && !animatedGif(params);
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

    /**
     * Opens [videoId] in Facebook's own player through its link handler, as Recently watched does.
     * A watch link lands in the upright reels viewer, which letterboxes a [wide] video (#239). So a
     * wide one gets the full screen player's own link instead (DeprecatedFullscreenVideoPlayerActivity
     * in 585, comments under the video), which plays it across the screen when the phone turns
     * sideways. Facebook's link map only knows that link with a loop value
     * (`fullscreen_video/{video_id}?loop={loop}`) and drops anything else on Home, and its video
     * link interceptor sends it to the reels viewer too unless it carries force_fs=1.
     */
    static void openFullPlayer(Context context, String videoId, boolean wide) {
        String link = SendLink.videoLink(videoId);
        if (link == null) return;
        if (wide) link = FULL_SCREEN_LINK + videoId + "?loop=false&force_fs=1";
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClassName(context.getPackageName(), URI_HANDLER);
        if (!(context instanceof Activity)) view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(view);
    }

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

    private static boolean fadeOn() {
        return seekOn() && Settings.FEED_VIDEO_SEEK_BAR_FADE.get();
    }

    private static boolean lowerOn() {
        return seekOn() && Settings.FEED_VIDEO_SEEK_BAR_LOWER.get();
    }

    private static boolean openOn() {
        return inBuild() && Settings.FEED_VIDEO_FULL_PLAYER_BUTTON.get();
    }

    private static boolean skipOn() {
        return inBuild() && Settings.FEED_VIDEO_SKIP_BUTTONS.get();
    }

    /** The speed button needs Hold a reel for 2x, whose patch reaches the player's speed. */
    private static boolean speedOn() {
        if (!inBuild()) return false;
        Boolean forced = speedInBuildForTests;
        return (forced != null ? forced : SettingsStatus.reelHold()) && Settings.FEED_VIDEO_SPEED_BUTTON.get();
    }

    private static boolean timesOn() {
        return seekOn() && Settings.FEED_VIDEO_SEEK_BAR_TIMES.get();
    }

    private static boolean storySeekOn() {
        return inBuild() && Settings.STORY_SEEK_BAR.get();
    }

    private static boolean storyFadeOn() {
        return storySeekOn() && Settings.STORY_SEEK_BAR_FADE.get();
    }

    /** Whether [player] plays a story, which gets the seek bar and none of the feed's buttons. */
    private static boolean storyPlayer(Object player) {
        Object origin = access.origin(player);
        return origin != null && TapToPlay.isStoryOrigin(origin.toString());
    }

    private static void refresh(Control control) {
        try {
            Object player = control.player.get();
            ViewGroup container = control.expected.get();
            boolean story = player != null && storyPlayer(player);
            control.story = story;
            boolean play = !story && playOn();
            boolean cast = !story && castOn();
            boolean seek = story ? storySeekOn() : seekOn();
            boolean open = !story && openOn();
            boolean skip = !story && skipOn();
            boolean speed = !story && speedOn();
            if ((!play && !cast && !seek && !open && !skip && !speed) || control.released || player == null
                    || container == null || access.view(player) != container) {
                control.remove(true);
                return;
            }
            control.observe(container);
            if (!container.isAttachedToWindow()) {
                control.remove(false);
                return;
            }
            Target target = story ? storyTarget(control) : target(control, play, open, cast, skip, speed);
            if (target == null) {
                control.remove(false);
                return;
            }
            // A live video has no end to skip toward and plays at its own pace, so it gets neither.
            skip &= !target.live;
            speed &= !target.live;
            // Play first, then the skips round it as in Facebook's player, speed, and the full player last (#239).
            int left = target.left;
            control.button = place(control, control.button, play ? target : null, PLAY, left);
            if (play) left += target.step;
            control.back = place(control, control.back, skip ? target : null, BACK, left);
            if (skip) left += target.step;
            control.ahead = place(control, control.ahead, skip ? target : null, AHEAD, left);
            if (skip) left += target.step;
            control.speed = place(control, control.speed, speed ? target : null, SPEED, left);
            if (speed) left += target.step;
            control.open = place(control, control.open, open ? target : null, OPEN, left);
            control.cast = place(control, control.cast, cast && !target.live ? target : null, CAST, target.right);
            if (control.button != null) control.button.state(access.playing(player));
            if (control.speed != null) control.speed.rate(access.speed(player));
            control.bar = placeBar(control, control.bar, seek ? target : null, player);
            dim(control, player);
        } catch (Throwable failure) {
            control.remove(true);
            HookStatus.threw(FAMILY, "inline feed button", failure);
        }
    }

    /** Keeps, makes or drops one button for [target]: null takes it off. Answers what's showing. */
    @Nullable
    private static Button place(Control control, @Nullable Button current, @Nullable Target target, int kind, int left) {
        if (current != null && (target == null || current.generation != control.generation
                || current.params.get() != target.params || current.surface.get() != target.surface
                || !current.videoId.equals(target.videoId)
                || current.getParent() != target.content)) {
            detach(current);
            current = null;
        }
        if (target == null) return null;
        if (current == null) {
            current = new Button(control, target, kind);
            target.content.addView(current, new FrameLayout.LayoutParams(target.size, target.size,
                    Gravity.LEFT | Gravity.TOP));
            HookStatus.bound(FAMILY, kind == CAST ? "inline cast button"
                    : kind == OPEN ? "inline full player button" : kind == BACK || kind == AHEAD ? "inline skip buttons"
                    : kind == SPEED ? "inline speed button" : "inline feed button");
        }
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
            HookStatus.bound(FAMILY, control.story ? "story seek bar" : "feed video seek bar");
            current.update(player);
            control.wake();
            control.tick();
        }
        if (control.story && storyBar.get() != current) storyBar = new WeakReference<>(current);
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

    /**
     * Where the buttons go on the video [control] shows, or null when none fit. It needs room for
     * each of [play], [open], [cast], the two [skip] buttons and [speed] that's on and the video
     * gets, and always for one.
     */
    @Nullable
    private static Target target(Control control, boolean play, boolean open, boolean cast, boolean skip, boolean speed) {
        Object player = control.player.get();
        ViewGroup container = control.expected.get();
        if (player == null || container == null || control.released || access.view(player) != container
                || !container.isAttachedToWindow() || !container.isShown() || !container.hasWindowFocus()) return null;
        Object params = access.params(player);
        Object origin = access.origin(player);
        String id = params == null ? null : access.videoId(params);
        if (params == null || id == null || id.isEmpty() || !access.eligible(params)) {
            return miss(control, "no video id, or an ad, audio or GIF");
        }
        boolean live = access.live(params);
        // Any feed counts, so a page's, a group's or a profile's videos get them too (#239).
        if (origin == null || !FeedVideoAlone.fromFeed(origin.toString())) return miss(control, "origin", origin);
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
        int buttons = Math.max(1, (play ? 1 : 0) + (open ? 1 : 0) + (cast && !live ? 1 : 0)
                + (skip && !live ? 2 : 0) + (speed && !live ? 1 : 0));
        int across = size * buttons + margin * (buttons + 1);
        if (visible.width() < across || visible.height() < down + size + margin) return miss(control, "too little of it shows");
        int[] at = new int[2];
        // A surface can be taller than its container, which crops it, so the video ends at the higher edge of the two.
        surface.getLocationInWindow(at);
        Rect video = new Rect(at[0], at[1], at[0] + surface.getWidth(), at[1] + surface.getHeight());
        container.getLocationInWindow(at);
        video.bottom = Math.min(video.bottom, at[1] + container.getHeight());
        corners(control, row, video, density, live);
        Rect bar = !seekOn() ? null : live ? noBar(control, "a live video has no end to seek to", null)
                : barArea(control, video, list, content, visible, down + size + margin, density);
        content.getLocationInWindow(at);
        // Margins count from inside the padding, which holds the status bar on an edge to edge screen.
        at[0] += content.getPaddingLeft();
        at[1] += content.getPaddingTop();
        if (bar != null) bar.offset(-at[0], -at[1]);
        control.missed = null;
        int left = visible.left - at[0] + margin;
        return new Target(params, id, surface, (FrameLayout) content, size, left, size + margin,
                visible.right - at[0] - margin - size, visible.top - at[1] + down, bar, live);
    }

    /** Whether a bar ending at window position [bottom] reaches the strip Android keeps for its home swipe. No app can claim touches there, so the bar would look live and never get a drag. */
    private static boolean inGestureStrip(View content, int bottom) {
        return bottom > gestureTop(content);
    }

    /** Where that strip starts in the window, or no limit while the insets aren't known. Android 9 keeps no such strip. */
    @SuppressWarnings("deprecation") // Android 10's way to the strip, replaced in 11.
    private static int gestureTop(View content) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Integer.MAX_VALUE;
        WindowInsets insets = insetsForTests != null ? insetsForTests : content.getRootWindowInsets();
        if (insets == null) return Integer.MAX_VALUE;
        int strip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom
                : insets.getMandatorySystemGestureInsets().bottom;
        return content.getRootView().getHeight() - strip;
    }

    /**
     * Where the seek bar goes on the story [control] shows, or null when it has none. It runs along
     * the bottom of the story's card where Progress bar at the bottom draws, which makes way for it,
     * and never sits in the home swipe's strip. A story opened from the feed's tray plays in the
     * viewer, while the tray's own small preview never gets one (#386).
     */
    @Nullable
    private static Target storyTarget(Control control) {
        Object player = control.player.get();
        ViewGroup container = control.expected.get();
        if (player == null || container == null || control.released || access.view(player) != container
                || !container.isAttachedToWindow() || !container.isShown() || !container.hasWindowFocus()) return null;
        control.restoreCorners();
        Object params = access.params(player);
        String id = params == null ? null : access.videoId(params);
        if (params == null || id == null || id.isEmpty() || !access.eligible(params)) {
            return miss(control, "no video id, or an ad, audio or GIF");
        }
        View content = container.getRootView().findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout) || !descendant(container, content)) return miss(control, "outside the screen's content");
        View card = StoryProgressBar.cardOf(container);
        if (card == null || !descendant(card, content)) return miss(control, "no story card");
        List<View> surfaces = new ArrayList<>(2);
        surfaces(container, surfaces);
        if (surfaces.size() > 1) return miss(control, "more than one video surface");
        View surface = surfaces.isEmpty() ? container : surfaces.get(0);
        Rect visible = new Rect();
        Rect clip = new Rect();
        Rect shown = new Rect();
        if (!card.getGlobalVisibleRect(visible) || !content.getGlobalVisibleRect(clip) || !visible.intersect(clip)
                || !surface.getGlobalVisibleRect(shown)) {
            return miss(control, "scrolled out of view");
        }
        // A card part way through a swipe to the next person's stories shows only part of its width.
        if (visible.width() < card.getWidth() - 1) return miss(control, "its card is moving");
        // The feed's row of stories plays narrow previews. A wide video in the viewer is short but spans the card.
        if (shown.width() * 2 < visible.width()) return miss(control, "too small for the story viewer");
        float density = container.getResources().getDisplayMetrics().density;
        boolean live = access.live(params);
        Rect bar = live ? noBar(control, "a live story has no end to seek to", null)
                : storyBarArea(control, visible, content, density);
        int[] at = new int[2];
        content.getLocationInWindow(at);
        at[0] += content.getPaddingLeft();
        at[1] += content.getPaddingTop();
        if (bar != null) bar.offset(-at[0], -at[1]);
        control.missed = null;
        return new Target(params, id, surface, (FrameLayout) content, 0, 0, 0, 0, 0, bar, live);
    }

    /** The story bar's spot in the window along the bottom of [card], the card's visible part. */
    @Nullable
    private static Rect storyBarArea(Control control, Rect card, View content, float density) {
        int bottom = Math.min(card.bottom - Math.round(STORY_ABOVE_DP * density), gestureTop(content));
        int height = Math.round(STORY_BAR_DP * density);
        int side = Math.round(STORY_SIDE_DP * density);
        if (bottom - height < card.centerY() || card.width() <= 2 * side) return noBar(control, "too little of its card shows", null);
        control.missedBar = null;
        return new Rect(card.left + side, bottom - height, card.right - side, bottom);
    }

    /**
     * Where the seek bar goes in the window, {@link #BAR_LIFT_DP} above [video]'s bottom, less what
     * Lower the seek bar moved Facebook's corner buttons down, or null when that spot isn't showing
     * in [visible], or something on top of the feed lies across it. The buttons keep [room] of the height.
     */
    @Nullable
    private static Rect barArea(Control control, Rect video, View list, View content, Rect visible, int room, float density) {
        int bottom = video.bottom - Math.round(BAR_LIFT_DP * density) + control.lowered;
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
            String what = control.story ? "Story seek bar" : "Feed video seek bar";
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE,
                    () -> what + ": none, " + why + (detail == null ? "" : " " + detail));
        }
        return null;
    }

    /**
     * Finds Facebook's own buttons in [video]'s bottom corners inside [row], the speaker and the
     * tagged people, at most once a tick, and moves them down for Lower the seek bar (#386). They all
     * move by the same amount, which the bar takes too, so it stays just clear of them. With both
     * switches off, or on a live video, they're put back as they were.
     */
    private static void corners(Control control, View row, Rect video, float density, boolean live) {
        boolean lower = lowerOn();
        if (live || (!lower && !fadeOn())) {
            control.restoreCorners();
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (control.scanned != 0 && now - control.scanned < TICK_MS) return;
        control.scanned = now;
        List<View> found = new ArrayList<>(4);
        findCorners(control, row, video, Math.round(BAR_LIFT_DP * density), Math.round(CORNER_BUTTON_DP * density),
                Math.round(CORNER_DP * density), found);
        for (Map.Entry<View, Corner> known : new ArrayList<>(control.corners.entrySet())) {
            if (!found.contains(known.getKey())) {
                control.corners.remove(known.getKey());
                known.getValue().restore();
            }
        }
        int room = Math.round(LOWER_DP * density);
        int gap = Math.round(MARGIN_DP * density);
        int side = Math.round(CORNER_DP * density);
        boolean rtl = row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        StringBuilder seen = new StringBuilder();
        for (View view : found) {
            Corner corner = control.corners.get(view);
            if (corner == null) {
                corner = new Corner(control, view);
                control.corners.put(view, corner);
            }
            Rect base = base(control, view);
            corner.speakerSide = rtl ? base.left - video.left <= side : video.right - base.right <= side;
            room = Math.min(room, Math.max(0, video.bottom - base.bottom - gap));
            seen.append(seen.length() == 0 ? "" : ", ").append(view.getClass().getName())
                    .append(corner.speakerSide ? " speaker side " : " ").append(view.getContentDescription());
        }
        int shift = lower ? room : 0;
        for (Corner corner : control.corners.values()) corner.shift(shift);
        control.lowered = shift;
        String summary = seen.length() == 0 ? "none" : seen.toString();
        if (!summary.equals(control.cornersSaid)) {
            control.cornersSaid = summary;
            if (!found.isEmpty()) HookStatus.bound(FAMILY, "feed video corner buttons");
            if (BaseSettings.DEBUG.get()) {
                Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE,
                        () -> "Feed video corner buttons: " + summary + ", moved down " + shift + " px");
            }
        }
    }

    /**
     * Collects into [found] what sits in [video]'s bottom corners inside [view]: small, ending in the
     * [strip] the bar leaves under it, below the video's middle and within [side] of a side, and
     * something you tap, something a screen reader names, or a single view. A frame that counts moves
     * with everything in it. The bar and buttons this adds never count, nor does a bare spacer.
     */
    private static void findCorners(Control control, View view, Rect video, int strip, int small, int side, List<View> found) {
        if (found.size() >= 6 || view instanceof Button || view instanceof Scrub || !view.isShown() || view.getAlpha() <= 0f) return;
        Rect rect = base(control, view);
        if (rect.bottom < video.bottom - strip || rect.top > video.bottom || rect.right < video.left || rect.left > video.right) return;
        boolean frame = view instanceof ViewGroup && ((ViewGroup) view).getChildCount() > 0;
        boolean fits = view.getClass() != View.class && rect.width() > 0 && rect.height() > 0
                && rect.width() <= small && rect.height() <= small
                && rect.bottom <= video.bottom && rect.top >= video.centerY()
                && (rect.left - video.left <= side || video.right - rect.right <= side);
        if (fits && (!frame || view.isClickable() || view.getContentDescription() != null)) {
            found.add(view);
            return;
        }
        if (!frame) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) findCorners(control, group.getChildAt(i), video, strip, small, side, found);
    }

    /** [view]'s spot in the window as Facebook placed it, before Lower the seek bar moved it. */
    private static Rect base(Control control, View view) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        Corner moved = control.corners.get(view);
        int shifted = moved == null ? 0 : moved.shifted;
        return new Rect(at[0], at[1] - shifted, at[0] + view.getWidth(), at[1] - shifted + view.getHeight());
    }

    /**
     * Fades the bar, and the corner buttons on the speaker's side, while the video plays and nothing
     * touched them for {@link #FADE_AFTER_MS}. Starting, pausing, a touch on the bar or a tap on the
     * speaker brings them back (#386).
     */
    private static void dim(Control control, @Nullable Object player) {
        Scrub bar = control.bar;
        boolean playing = bar != null && player != null && access.playing(player);
        if (playing != control.lastPlaying) {
            control.lastPlaying = playing;
            control.wake();
        }
        // A tap on the speaker changes what it says, and brings the faded bar and speaker back. Read on
        // the bar's tick, since that tap needn't redraw anything the next scan waits for.
        StringBuilder speaker = new StringBuilder();
        for (Corner corner : new ArrayList<>(control.corners.values())) {
            View view = corner.view.get();
            if (corner.speakerSide && view != null) speaker.append(view.getContentDescription()).append('|');
        }
        String said = speaker.toString();
        if (control.speakerSaid != null && !control.speakerSaid.equals(said)) control.wake();
        control.speakerSaid = said;
        boolean dim = playing && (control.story ? storyFadeOn() : fadeOn()) && !bar.dragging
                && SystemClock.uptimeMillis() >= control.wakeUntil;
        if (bar != null) bar.dimmer.to(dim);
        for (Corner corner : new ArrayList<>(control.corners.values())) corner.dimmer.to(dim && corner.speakerSide);
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
            String what = control.story ? "Story seek bar" : "Inline feed button";
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE,
                    () -> what + ": none, " + why + (detail == null ? "" : " " + detail));
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
            int kind = button.kind;
            boolean on = kind == CAST ? castOn() : kind == OPEN ? openOn() : kind == BACK || kind == AHEAD ? skipOn()
                    : kind == SPEED ? speedOn() : playOn();
            Button current = kind == CAST ? control.cast : kind == OPEN ? control.open : kind == BACK ? control.back
                    : kind == AHEAD ? control.ahead : kind == SPEED ? control.speed : control.button;
            if (!on || current != button || control.generation != button.generation) return;
            Object player = control.player.get();
            if (player == null || !stillOver(control, button, button.params, button.videoId, button.surface)) {
                control.remove(false);
                return;
            }
            if (kind == BACK || kind == AHEAD) {
                skip(control, player, button, kind == AHEAD ? SKIP_MS : -SKIP_MS);
                return;
            }
            if (kind == SPEED) {
                float next = nextSpeed(access.speed(player));
                access.speed(player, next);
                button.rate(next);
                HookStatus.counted(FAMILY, "inline speed");
                return;
            }
            if (kind == CAST) {
                access.cast(button, button.videoId);
                HookStatus.counted(FAMILY, "inline cast");
                return;
            }
            if (button.kind == OPEN) {
                // The feed's copy stops, so the two never play over each other.
                if (access.playing(player)) {
                    access.command(player, false);
                    refresh(control);
                }
                View surface = button.surface.get();
                boolean wide = surface != null && surface.getWidth() > surface.getHeight();
                access.open(button, button.videoId, wide);
                HookStatus.counted(FAMILY, wide ? "inline full player, wide" : "inline full player");
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

    /**
     * Moves [player] [by] milliseconds, kept inside the video. A tap before the last skip landed
     * counts from where that one asked for, since the player reports its old spot until then, so
     * three quick taps jump 30 seconds.
     */
    private static void skip(Control control, Object player, Button button, int by) {
        Object params = button.params.get();
        int length = params == null ? 0 : access.length(player, params);
        if (length <= 0) return;
        long now = SystemClock.uptimeMillis();
        int position = Math.max(0, Math.min(length, access.position(player)));
        int from = control.skipTo >= 0 && now < control.skipUntil && Math.abs(position - control.skipTo) > LANDED_WITHIN_MS
                ? control.skipTo : position;
        int to = Math.max(0, Math.min(length, from + by));
        ResumePlayback.placedByYou(player);
        access.seek(player, to);
        control.skipTo = to;
        control.skipUntil = now + LANDING_MS;
        Scrub bar = control.bar;
        if (bar != null && !bar.dragging) {
            bar.landing = to;
            bar.landingUntil = now + LANDING_MS;
            bar.setProgress(to);
        }
        control.wake();
        HookStatus.counted(FAMILY, "inline skip");
    }

    /** The speed after [now] in {@link #SPEEDS}. One off the list goes back to 1x. */
    static float nextSpeed(float now) {
        if (Float.isNaN(now) || now <= 0f) now = 1f;
        for (int i = 0; i < SPEEDS.length; i++) {
            if (Math.abs(SPEEDS[i] - now) < 0.01f) return SPEEDS[(i + 1) % SPEEDS.length];
        }
        return 1f;
    }

    /** How the speed button writes [speed]: 1x, 1.25x, 0.5x. */
    static String speedLabel(float speed) {
        if (Float.isNaN(speed) || speed <= 0f) speed = 1f;
        String number = Math.abs(speed - Math.round(speed)) < 0.005f ? Integer.toString(Math.round(speed))
                : String.format(java.util.Locale.US, "%.2f", speed).replaceAll("0+$", "");
        return number + "x";
    }

    /** Whether [view], made for [videoId]'s [params] on [surface], still sits on that video. */
    private static boolean stillOver(Control control, View view, WeakReference<Object> params, String videoId,
            WeakReference<View> surface) {
        Target current = control.story ? storyTarget(control) : target(control, false, false, false, false, false);
        return current != null && current.params == params.get() && videoId.equals(current.videoId)
                && current.surface == surface.get() && view.getParent() == current.content;
    }

    /** The bar was let go, or moved by a screen reader, at [bar]'s progress. */
    private static void seek(Scrub bar) {
        Control control = bar.control;
        String what = control.story ? "story seek" : "feed video seek";
        try {
            if (!(control.story ? storySeekOn() : seekOn()) || control.bar != bar || control.generation != bar.generation) return;
            Object player = control.player.get();
            if (player == null || !stillOver(control, bar, bar.params, bar.videoId, bar.surface)) {
                control.remove(false);
                return;
            }
            ResumePlayback.placedByYou(player);
            access.seek(player, bar.getProgress());
            bar.landing = bar.getProgress();
            bar.landingUntil = SystemClock.uptimeMillis() + LANDING_MS;
            // A skip tapped before this lands counts from here, not the old spot the player still reports.
            control.skipTo = bar.landing;
            control.skipUntil = bar.landingUntil;
            HookStatus.counted(FAMILY, what);
        } catch (Throwable failure) {
            control.remove(true);
            HookStatus.threw(FAMILY, what, failure);
        }
    }

    /**
     * A drag on a story's bar holds the story where it is, so it can't run out and move on to the
     * next one before the bar is let go. Answers whether this paused it, which a let go undoes.
     */
    private static boolean hold(Scrub bar) {
        Control control = bar.control;
        try {
            if (!control.story || control.bar != bar || control.generation != bar.generation) return false;
            Object player = control.player.get();
            if (player == null || !access.playing(player)) return false;
            access.command(player, false);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "story seek", failure);
            return false;
        }
    }

    /** Plays the story [hold] paused again, once [bar] was let go and still sits on it. */
    private static void resume(Scrub bar) {
        Control control = bar.control;
        try {
            Object player = control.player.get();
            if (player == null || control.bar != bar || control.generation != bar.generation) return;
            access.command(player, true);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "story seek", failure);
        }
    }

    private static final class Target {
        final Object params;
        final String videoId;
        final View surface;
        final FrameLayout content;
        /** The first button's spot, how far along each next one in its row sits, and cast's across from them. */
        final int size, left, step, right, top;
        /** Where the seek bar goes in [content], or null when it has no place. */
        @Nullable final Rect bar;
        /** A live video, which gets no cast button or seek bar. */
        final boolean live;
        Target(Object params, String videoId, View surface, FrameLayout content, int size, int left, int step, int right,
                int top, @Nullable Rect bar, boolean live) {
            this.params = params; this.videoId = videoId; this.surface = surface; this.content = content;
            this.size = size; this.left = left; this.step = step; this.right = right; this.top = top; this.bar = bar;
            this.live = live;
        }
    }

    private static final class Control implements ViewTreeObserver.OnPreDrawListener,
            View.OnAttachStateChangeListener {
        final WeakReference<Object> player;
        volatile WeakReference<ViewGroup> expected = new WeakReference<>(null);
        volatile long generation;
        volatile boolean released;
        /** A story's player, which gets only the story seek bar. */
        volatile boolean story;
        /** The last reason the debug log gave for no button, or null since one showed. */
        @Nullable String missed;
        /** The same for the seek bar, which can miss while the buttons show. */
        @Nullable String missedBar;
        private WeakReference<ViewGroup> observed = new WeakReference<>(null);
        private WeakReference<ViewTreeObserver> observer = new WeakReference<>(null);
        @Nullable Button button;
        @Nullable Button open;
        @Nullable Button cast;
        @Nullable Button back;
        @Nullable Button ahead;
        @Nullable Button speed;
        @Nullable Scrub bar;
        /** Where the last skip asked the player to go, and until when it may still be on its way. */
        int skipTo = -1;
        long skipUntil;
        /** Facebook's corner buttons the two seek bar switches moved or dimmed, put back when this lets go of them (#386). */
        final Map<View, Corner> corners = new WeakHashMap<>();
        /** How far Lower the seek bar moved them down, which the bar moves too. */
        int lowered;
        /** When they were last looked for, at most once a tick. */
        long scanned;
        /** Until when the bar and the speaker stay bright, the video having started or been touched. */
        long wakeUntil;
        boolean lastPlaying;
        /** What the speaker side's buttons said, and the corners the debug log last named. */
        @Nullable String speakerSaid;
        @Nullable String cornersSaid;
        /** Moves the bar along. Read on this tick rather than on each drawn frame, which comes far more often. */
        private final Runnable mover = this::move;
        Control(Object player) { this.player = new WeakReference<>(player); }
        void wake() { wakeUntil = SystemClock.uptimeMillis() + FADE_AFTER_MS; }
        void restoreCorners() {
            for (Corner corner : new ArrayList<>(corners.values())) corner.restore();
            corners.clear();
            lowered = 0;
            scanned = 0;
            speakerSaid = null;
        }
        private void move() {
            Scrub showing = bar;
            Object owner = player.get();
            if (showing == null || owner == null) return;
            try {
                showing.update(owner);
                dim(this, owner);
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
            restoreCorners();
            lastPlaying = false;
            if (button != null) detach(button);
            if (open != null) detach(open);
            if (cast != null) detach(cast);
            if (back != null) detach(back);
            if (ahead != null) detach(ahead);
            if (speed != null) detach(speed);
            if (bar != null) {
                detach(bar);
                if (main != null) main.removeCallbacks(mover);
            }
            button = null;
            open = null;
            cast = null;
            back = null;
            ahead = null;
            speed = null;
            bar = null;
            skipTo = -1;
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
     * speaker icon: play or pause, the cast one, or the full player one, by [kind].
     */
    static final class Button extends View {
        final Control control;
        final int kind;
        final long generation;
        final WeakReference<Object> params;
        final String videoId;
        final WeakReference<View> surface;
        final Look look = new Look();
        boolean playing;
        /** What the speed button shows, set from the player. */
        String speed = speedLabel(1f);
        Button(Control control, Target target, int kind) {
            super(target.content.getContext());
            this.control = control;
            this.kind = kind;
            generation = control.generation;
            params = new WeakReference<>(target.params);
            videoId = target.videoId;
            surface = new WeakReference<>(target.surface);
            setClickable(true);
            setFocusable(true);
            setOnClickListener(view -> click(this));
            if (kind == PLAY) {
                state(false);
            } else {
                setContentDescription(L10n.t(kind == CAST ? "Cast this video" : kind == BACK ? "Back 10 seconds"
                        : kind == AHEAD ? "Ahead 10 seconds" : kind == SPEED ? "Playback speed" : "Open in the full player"));
                setTooltipText(getContentDescription());
                if (kind == SPEED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setStateDescription(speed);
            }
        }
        /** The speed button shows [value], the player's speed. */
        void rate(float value) {
            String label = speedLabel(value);
            if (kind != SPEED || label.equals(speed)) return;
            speed = label;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setStateDescription(label);
            invalidate();
        }
        void state(boolean value) {
            if (kind != PLAY || getContentDescription() != null && playing == value) return;
            playing = value;
            setContentDescription(L10n.t(value ? "Pause feed video" : "Play feed video"));
            setTooltipText(getContentDescription());
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            look.draw(canvas, getWidth(), getHeight(), getResources().getDisplayMetrics().density, kind, playing, speed);
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
        final Dimmer dimmer = new Dimmer(this);
        boolean dragging;
        boolean cancelled;
        /** The times show at the line's ends. */
        boolean times;
        /** A drag on a story's bar paused it, and the let go plays it again. */
        boolean held;
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
            setContentDescription(L10n.t(control.story ? "Story position" : "Feed video position"));
            paint.setTextAlign(Paint.Align.CENTER);
            setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    if (fromUser && !dragging) seek(Scrub.this);
                }
                @Override public void onStartTrackingTouch(SeekBar bar) {
                    dragging = true;
                    held = hold(Scrub.this);
                    invalidate();
                }
                @Override public void onStopTrackingTouch(SeekBar bar) {
                    dragging = false;
                    if (!cancelled) seek(Scrub.this);
                    if (held) {
                        held = false;
                        resume(Scrub.this);
                    }
                    control.wake();
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
            times(!control.story && timesOn());
            int position = Math.max(0, Math.min(length, access.position(player)));
            // The player reports the old spot until a seek lands, which would spring the bar back.
            if (landing >= 0 && Math.abs(position - landing) > LANDED_WITHIN_MS && SystemClock.uptimeMillis() < landingUntil) return;
            landing = -1;
            setProgress(position);
        }
        /**
         * With Times on the seek bar on, the time so far shows at the line's left end and the time
         * left at its right, as in Facebook's player (#239). The line makes room for them, and a
         * SeekBar maps a touch between its paddings, so the time under a finger stays the one it gets.
         */
        void times(boolean on) {
            int room = 0;
            if (on && getMax() > 0) {
                paint.setTextSize(timeSize());
                room = Math.round(paint.measureText("-" + ProgressBar.time(getMax()))
                        + 8 * getResources().getDisplayMetrics().density);
            }
            boolean changed = on != times;
            times = on;
            if (getPaddingLeft() != room || getPaddingRight() != room) {
                setPadding(room, 0, room, 0);
            } else if (changed) {
                invalidate();
            }
        }
        private float timeSize() {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, getResources().getDisplayMetrics());
        }
        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            // A SeekBar only claims the spot around a thumb drawable, and this one draws its own, so a drag starting near the screen's side went to Back.
            // Android 9 has no side swipe for Back.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, right - left, bottom - top)));
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            // A cancel stops the tracking too, and isn't a let go.
            cancelled = event.getActionMasked() == MotionEvent.ACTION_CANCEL;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && control.bar == this) {
                // A faded bar comes back the moment it's touched, along with the speaker.
                control.wake();
                dim(control, control.player.get());
            }
            return super.onTouchEvent(event);
        }
        @Override protected synchronized void onDraw(Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            float y = getHeight() - 6 * density;
            float half = 1.5f * density;
            float radius = (dragging ? 8 : 6) * density;
            // The line runs between the paddings, which hold the times when they show.
            float start = getPaddingLeft(), end = getWidth() - getPaddingRight();
            float x = getMax() <= 0 ? start : start + Math.max(0, end - start) * getProgress() / getMax();
            paint.setColor(0x4D000000);
            canvas.drawRect(start, y - half - density, end, y + half + density, paint);
            paint.setColor(0x80FFFFFF);
            canvas.drawRect(start, y - half, end, y + half, paint);
            paint.setColor(0xFFFFFFFF);
            canvas.drawRect(start, y - half, x, y + half, paint);
            canvas.drawCircle(Math.max(radius, Math.min(getWidth() - radius, x)), y, radius, paint);
            if (times && getMax() > 0) drawTimes(canvas, y, density);
            if (dragging) drawTime(canvas, x, y - radius - 2 * density, density);
        }
        /** The time so far at the left end and the time left at the right, white with a dark edge so they read on any video. */
        private void drawTimes(Canvas canvas, float y, float density) {
            paint.setTextSize(timeSize());
            paint.setShadowLayer(2 * density, 0, 0, 0xCC000000);
            float base = y - (paint.ascent() + paint.descent()) / 2;
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(ProgressBar.time(getProgress()), 2 * density, base, paint);
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText("-" + ProgressBar.time(getMax() - getProgress()), getWidth() - 2 * density, base, paint);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.clearShadowLayer();
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

    /**
     * One of Facebook's buttons in a video's bottom corners, and what the seek bar switches changed on
     * it, which comes off when the row leaves the screen so a recycled view never keeps it (#386).
     */
    private static final class Corner implements View.OnAttachStateChangeListener {
        final Control control;
        final WeakReference<View> view;
        final Dimmer dimmer;
        /** What this added to the view's own translation, so Facebook's stays its own. */
        int shifted;
        boolean speakerSide;
        Corner(Control control, View view) {
            this.control = control;
            this.view = new WeakReference<>(view);
            dimmer = new Dimmer(view);
            view.addOnAttachStateChangeListener(this);
        }
        void shift(int by) {
            View target = view.get();
            if (target == null || by == shifted) return;
            target.setTranslationY(target.getTranslationY() - shifted + by);
            shifted = by;
        }
        void restore() {
            shift(0);
            dimmer.reset();
            View target = view.get();
            if (target != null) target.removeOnAttachStateChangeListener(this);
        }
        @Override public void onViewAttachedToWindow(View attached) { }
        @Override public void onViewDetachedFromWindow(View detached) {
            restore();
            control.corners.remove(detached);
        }
    }

    /**
     * Fades one view to {@link #FADED_ALPHA} and back with an animation of its own, so any Facebook
     * runs on the same view stays untouched. Only a view at full strength fades, and only what this
     * faded comes back, so a button Facebook hid stays hidden.
     */
    static final class Dimmer {
        final WeakReference<View> view;
        @Nullable private ValueAnimator running;
        boolean dimmed;
        Dimmer(View view) { this.view = new WeakReference<>(view); }
        void to(boolean dim) {
            View target = view.get();
            if (target == null) return;
            boolean animating = running != null && running.isStarted();
            // Facebook set it back to full strength itself.
            if (dimmed && !animating && target.getAlpha() >= 1f) dimmed = false;
            if (dim == dimmed) return;
            float from = target.getAlpha();
            if (dim ? from < 1f : from < FADED_ALPHA - 0.01f) {
                dimmed = false;
                return;
            }
            dimmed = dim;
            stop();
            ValueAnimator animator = ValueAnimator.ofFloat(from, dim ? FADED_ALPHA : 1f);
            animator.setDuration(FADE_ANIMATION_MS);
            animator.addUpdateListener(update -> {
                View shown = view.get();
                if (shown != null) shown.setAlpha((float) update.getAnimatedValue());
            });
            running = animator;
            animator.start();
        }
        private void stop() {
            ValueAnimator was = running;
            running = null;
            if (was != null) was.cancel();
        }
        /** Back to full strength at once. */
        void reset() {
            stop();
            View target = view.get();
            if (dimmed && target != null && target.getAlpha() >= FADED_ALPHA - 0.01f) target.setAlpha(1f);
            dimmed = false;
        }
    }

    /** What a [Button] draws, kept apart so it can be drawn without a player. */
    static final class Look {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rect = new RectF();
        final Path path = new Path();
        void draw(Canvas canvas, int width, int height, float density, boolean cast, boolean playing) {
            draw(canvas, width, height, density, cast ? CAST : PLAY, playing);
        }
        void draw(Canvas canvas, int width, int height, float density, int kind, boolean playing) {
            draw(canvas, width, height, density, kind, playing, speedLabel(1f));
        }
        void draw(Canvas canvas, int width, int height, float density, int kind, boolean playing, String speed) {
            float cx = width / 2f, cy = height / 2f;
            float r = Math.min(Math.min(cx, cy), DISC_DP / 2f * density);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x66000000);
            if (kind == SPEED) {
                // A pill as wide as the button, so 1.25x fits at a size that reads.
                float half = Math.min(cx, r * 1.3f);
                rect.set(cx - half, cy - r * .8f, cx + half, cy + r * .8f);
                canvas.drawRoundRect(rect, r * .8f, r * .8f, paint);
                paint.setColor(0xFFFFFFFF);
                drawLabel(canvas, speed, cx, cy, Math.min(r * .75f, (half * 2 - 6 * density) / Math.max(1, speed.length()) * 1.6f));
                return;
            }
            canvas.drawCircle(cx, cy, r, paint);
            paint.setColor(0xFFFFFFFF);
            // The symbol's square box, centered on the disc.
            float s = r * 1.7f, x = cx - s / 2, y = cy - s / 2;
            if (kind == BACK || kind == AHEAD) {
                drawSkip(canvas, x, y, s, kind == AHEAD);
            } else if (kind == CAST) {
                drawCast(canvas, x, y, s);
            } else if (kind == OPEN) {
                drawCorners(canvas, x, y, s);
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
        /**
         * Facebook's own skip symbol in the [s] box at [left], [top]: a circle open at its top with an
         * arrowhead pointing [ahead] or back, and 10 inside it.
         */
        private void drawSkip(Canvas canvas, float left, float top, float s, boolean ahead) {
            float cx = left + s / 2, cy = top + s / 2, r = s * .36f, stroke = s * .07f, dir = ahead ? 1 : -1;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setStrokeCap(Paint.Cap.ROUND);
            rect.set(cx - r, cy - r, cx + r, cy + r);
            // Clockwise from the gap's far side, round the bottom, to just short of the top, where the
            // arrowhead goes on into the gap: upper right for ahead, upper left for back.
            canvas.drawArc(rect, ahead ? 325 : 275, 300, false, paint);
            paint.setStyle(Paint.Style.FILL);
            path.reset();
            float x = cx - dir * r * .09f, y = cy - r;
            path.moveTo(x + dir * s * .14f, y);
            path.lineTo(x, y - s * .1f);
            path.lineTo(x, y + s * .1f);
            path.close();
            canvas.drawPath(path, paint);
            drawLabel(canvas, "10", cx, cy, r);
        }
        /** [text] centered on [cx], [cy], [size] pixels tall, in white bold. */
        private void drawLabel(Canvas canvas, String text, float cx, float cy, float size) {
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(size);
            paint.setFakeBoldText(true);
            canvas.drawText(text, cx, cy - (paint.ascent() + paint.descent()) / 2, paint);
            paint.setFakeBoldText(false);
        }
        /** The usual full screen symbol in the [s] box at [left], [top]: a bracket in each corner. */
        private void drawCorners(Canvas canvas, float left, float top, float s) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(s * .08f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            float near = s * .28f, far = s * .72f, arm = s * .14f;
            path.reset();
            for (float[] corner : new float[][] {{near, near, 1, 1}, {far, near, -1, 1}, {near, far, 1, -1}, {far, far, -1, -1}}) {
                float x = left + corner[0], y = top + corner[1];
                path.moveTo(x + corner[2] * arm, y);
                path.lineTo(x, y);
                path.lineTo(x, y + corner[3] * arm);
            }
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    /** Whether [player] plays in a feed, by the origin Facebook gave it: any feed, a page's, a group's or a profile's. */
    public static boolean feedPlayer(@Nullable Object player) {
        if (player == null) return false;
        Object origin = access.origin(player);
        return origin != null && FeedVideoAlone.fromFeed(origin.toString());
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

    /**
     * Whether a story's seek bar shows, so Progress bar at the bottom of the stories package leaves
     * it the spot and the two never draw over each other. Cheap, since that bar asks on each frame.
     */
    public static boolean storyBarShowing() {
        Scrub bar = storyBar.get();
        return bar != null && bar.control.story && bar.control.bar == bar && bar.isShown();
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

    @Nullable static View showingOpen(Object player) {
        synchronized (players) {
            Control control = players.get(player);
            return control == null ? null : control.open;
        }
    }

    @Nullable static View showingSkip(Object player, boolean ahead) {
        synchronized (players) {
            Control control = players.get(player);
            return control == null ? null : ahead ? control.ahead : control.back;
        }
    }

    @Nullable static View showingSpeed(Object player) {
        synchronized (players) {
            Control control = players.get(player);
            return control == null ? null : control.speed;
        }
    }

    static void reset() {
        for (Control control : snapshot()) control.remove(true);
        synchronized (players) {
            if (main != null) main.removeCallbacksAndMessages(null);
            players.clear();
        }
        access = PATCHED;
        storyBar = new WeakReference<>(null);
        inBuildForTests = null;
        videoPatchInBuildForTests = null;
        speedInBuildForTests = null;
        insetsForTests = null;
    }
}
