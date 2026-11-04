/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Story viewer taps (#380): a play and pause button that a tap in the middle of a story shows, like
 * Reels, and stories that change only on a double tap.
 *
 * <p>The patch asks {@link #onTap} in front of each of the two navigations of Facebook's story tap
 * controller, back and forward, and skips the navigation when it answers true. Facebook's gesture
 * listener takes a quick second tap as a double tap and drops it, so the patch also gives the
 * listener a double tap callback that hands the second tap's lift to the same tap code ({@link
 * #secondTap}). The pause is Facebook's own, under the reason its own play and pause toggle pauses
 * with, made through stubs the patch fills in. Main thread. Never throws.
 */
public final class StoryTaps {
    /** What a double tap that changed stories is counted under. */
    static final String DOUBLE_TAPS = "double taps";
    /** What a pause or play from a tap or the button is counted under. */
    static final String TOGGLES = "pauses and plays";
    /** What the button showing is counted under. */
    static final String SHOWN = "play button shown";

    /** How long the button stays after the last tap while the story plays. */
    static final long HIDE_AFTER_MS = 2500;
    /** How often a showing button reads the story's state again. */
    private static final long REFRESH_MS = 400;
    private static final long FADE_MS = 150;
    private static final int SIZE_DP = 72;
    private static final int DISC_DP = 60;
    /** Android's default double tap slop and touch slop, for when there's no window to ask. */
    private static final int DOUBLE_TAP_SLOP_DP = 100;
    private static final int TOUCH_SLOP_DP = 8;
    static final String TAG = "hushfacebook_story_play_button";

    /** What the {@link #pauserOf} and {@link #pauseReason} stubs answer in tests, where no patch fills them in. */
    @Nullable
    static Object pauserForTests, reasonForTests;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> resumed = new WeakReference<>(null);
    private static WeakReference<Object> pauser = new WeakReference<>(null);
    /** Whether a pause from here is in place, for a pauser whose reasons can't be read. */
    private static boolean pausedHere;
    /** The down time of the last tap {@link #onTap} answered, and its answer. */
    private static long answeredDown = Long.MIN_VALUE;
    private static boolean answer;
    /** The lift of the single tap a second one would pair with, 0 when there's none, and where it was. */
    private static long firstUp;
    private static float firstX, firstY;
    /** Where the second tap of a double tap went down, and whether its touch down held back the first tap. */
    private static long secondDown;
    private static float secondX, secondY;
    private static boolean firstHeldBack;
    /** When the button was last shown or tapped, for hiding it. */
    private static long touched;
    /** The pauser's class whose enum sets were found, and those fields. */
    @Nullable
    private static Class<?> readType;
    private static List<Field> readFields = new ArrayList<>();

    private static final Runnable single = StoryTaps::singleTap;
    private static final Runnable refresh = StoryTaps::refresh;

    private StoryTaps() {
    }

    /**
     * Asked in front of each of Facebook's tap navigations in the story viewer: true skips that
     * navigation. With double tap on, every single tap is held and only the second tap of a double
     * tap moves on, to the side Facebook reads from that tap. With only the play button on, a tap
     * in the middle third shows or hides the button instead. Both off, settings not ready or
     * Hushfacebook paused, Facebook navigates as it always does.
     */
    public static boolean onTap(Object controller, MotionEvent event) {
        try {
            HookStatus.invoked(FamilyNames.STORY_TAPS);
            if (event == null || !Utils.settingsReady()) return false;
            boolean button = Settings.STORY_PLAY_BUTTON.get();
            boolean twice = Settings.STORY_DOUBLE_TAP.get();
            if (!button && !twice) return false;
            Object found = pauserOf(controller);
            if (found != null) pauser = new WeakReference<>(found);
            // A tap that reaches the controller by two routes gets the same answer both times.
            if (event.getDownTime() == answeredDown) return answer;
            answeredDown = event.getDownTime();
            answer = twice ? holdUnlessSecond(event) : middleShowsButton(event);
            return answer;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_TAPS, "Story tap", failure);
            return false;
        }
    }

    /**
     * Asked by the gesture listener for each event of a double tap's second tap: true on its lift,
     * when double tap is on and the finger stayed put, so the listener hands the lift to its own
     * single tap code and on to {@link #onTap}. Its touch down tells the first tap's single tap to
     * wait for it, and a second tap that turns out not to be one lets the first tap's single tap go
     * ahead. False leaves the listener as Facebook made it, dropping the second tap.
     */
    public static boolean secondTap(MotionEvent event) {
        try {
            if (event == null || !Utils.settingsReady() || !Settings.STORY_DOUBLE_TAP.get()) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    secondDown = event.getDownTime();
                    secondX = event.getRawX();
                    secondY = event.getRawY();
                    firstHeldBack = pairs(event.getDownTime(), secondX, secondY);
                    if (firstHeldBack) main.removeCallbacks(single);
                    return false;
                case MotionEvent.ACTION_UP:
                    float slop = touchSlop();
                    float dx = event.getRawX() - secondX, dy = event.getRawY() - secondY;
                    boolean tapped = event.getDownTime() == secondDown && dx * dx + dy * dy <= slop * slop
                            && event.getEventTime() - event.getDownTime() < ViewConfiguration.getLongPressTimeout();
                    if (!tapped) letFirstTapGo();
                    firstHeldBack = false;
                    return tapped;
                case MotionEvent.ACTION_CANCEL:
                    letFirstTapGo();
                    return false;
                default:
                    return false;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_TAPS, "Story second tap", failure);
            return false;
        }
    }

    /** The story viewer, or whatever window Facebook shows a story in, comes to the front. */
    public static void activityResumed(Activity activity) {
        resumed = new WeakReference<>(activity);
    }

    /** It leaves: the button goes away, a held tap is forgotten and a pause from here comes off. */
    public static void activityPaused(Activity activity) {
        try {
            if (resumed.get() != activity) return;
            main.removeCallbacks(single);
            firstUp = 0;
            firstHeldBack = false;
            letStoryPlay();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_TAPS, "Story viewer paused", failure);
        }
    }

    /** Filled in by the patch: the tap controller's pauser. Answers null until filled, outside tests. */
    @Nullable
    public static Object pauserOf(Object controller) {
        return pauserForTests;
    }

    /** Filled in by the patch: Facebook's pause under [reason]. Does nothing until filled. */
    public static void pauseWith(Object pauser, Object reason) {
    }

    /** Filled in by the patch: Facebook's resume from [reason]. Does nothing until filled. */
    public static void resumeWith(Object pauser, Object reason) {
    }

    /** Filled in by the patch: the reason Facebook's own play and pause toggle pauses with. Null until filled. */
    @Nullable
    public static Object pauseReason() {
        return reasonForTests;
    }

    /** Whether there's a pauser and a reason to pause the story with. */
    private static boolean canPause() {
        return pauser.get() != null && pauseReason() != null;
    }

    /**
     * Facebook is about to change stories, or the viewer leaves: a pause from here comes off, so
     * the next story plays, and the button goes away.
     */
    private static void letStoryPlay() {
        Object target = pauser.get();
        Object reason = pauseReason();
        if (target != null && reason != null && paused()) resumeWith(target, reason);
        pausedHere = false;
        hide(false);
    }

    /** A second tap that wasn't one after all: the first tap's single tap goes ahead now. */
    private static void letFirstTapGo() {
        if (!firstHeldBack) return;
        firstHeldBack = false;
        firstUp = 0;
        main.post(single);
    }

    /** Double tap on: a second tap lets Facebook move on, any other tap is held for a moment. */
    private static boolean holdUnlessSecond(MotionEvent event) {
        float x = event.getRawX(), y = event.getRawY();
        if (pairs(event.getDownTime(), x, y)) {
            firstUp = 0;
            main.removeCallbacks(single);
            // Whatever the first tap did before the second came is undone, and the next story plays.
            letStoryPlay();
            HookStatus.counted(FamilyNames.STORY_TAPS, DOUBLE_TAPS);
            return false;
        }
        firstUp = event.getEventTime();
        firstX = x;
        firstY = y;
        main.removeCallbacks(single);
        main.postDelayed(single, ViewConfiguration.getDoubleTapTimeout());
        return true;
    }

    /** Whether a tap that went down at [down], at [x], [y], is the second of a double tap. */
    static boolean pairs(long down, float x, float y) {
        if (firstUp == 0) return false;
        long gap = down - firstUp;
        float slop = doubleTapSlop();
        float dx = x - firstX, dy = y - firstY;
        return gap >= 0 && gap <= ViewConfiguration.getDoubleTapTimeout() && dx * dx + dy * dy <= slop * slop;
    }

    /** A held tap that no second tap came for: the play button when that's on, else a pause or play. */
    private static void singleTap() {
        try {
            if (!Utils.settingsReady() || !Settings.STORY_DOUBLE_TAP.get() || !canPause()) return;
            if (Settings.STORY_PLAY_BUTTON.get()) {
                show();
            } else {
                setPaused(!paused());
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_TAPS, "Story single tap", failure);
        }
    }

    /**
     * Play button alone: the middle third shows the button, or hides it while the story plays. A
     * tap on a side lets Facebook change stories with the pause from here taken off, and so does
     * a tap in the middle when there's nothing to pause with.
     */
    private static boolean middleShowsButton(MotionEvent event) {
        float across = across(event);
        if (across < 1f / 3f || across >= 2f / 3f || !canPause()) {
            letStoryPlay();
            return false;
        }
        PlayButton shown = button(false);
        if (shown != null && shown.getVisibility() == View.VISIBLE && !paused()) {
            hide(true);
            return true;
        }
        return show();
    }

    /**
     * How far across the story's window [event] is, 0 at its left edge and 1 at its right, so a
     * window in split screen measures from its own edge. Negative when there's no width to measure.
     */
    private static float across(MotionEvent event) {
        Activity activity = resumed.get();
        float left = 0, width = 0;
        if (activity != null) {
            View decor = activity.getWindow().getDecorView();
            int[] at = new int[2];
            decor.getLocationOnScreen(at);
            left = at[0];
            width = decor.getWidth();
        }
        if (width <= 0) width = Resources.getSystem().getDisplayMetrics().widthPixels;
        return width > 0 ? (event.getRawX() - left) / width : -1f;
    }

    /** The touch slop and double tap slop of the window in front, as its gesture detector reads them. */
    private static float touchSlop() {
        Activity activity = resumed.get();
        return activity != null ? ViewConfiguration.get(activity).getScaledTouchSlop() : TOUCH_SLOP_DP * density();
    }

    private static float doubleTapSlop() {
        Activity activity = resumed.get();
        return activity != null ? ViewConfiguration.get(activity).getScaledDoubleTapSlop() : DOUBLE_TAP_SLOP_DP * density();
    }

    private static float density() {
        return Resources.getSystem().getDisplayMetrics().density;
    }

    /** Whether the story is paused for the reason the button pauses with. */
    static boolean paused() {
        Object target = pauser.get();
        if (target == null) return false;
        Boolean held;
        try {
            held = reasonsHold(target, pauseReason());
        } catch (IllegalAccessException | RuntimeException unreadable) {
            held = null;
        }
        return held != null ? held : pausedHere;
    }

    /**
     * Whether one of [target]'s sets holds [reason]: Facebook's pauser keeps the reasons it's
     * paused for in an enum set. Null when there's no reason or no such set to read.
     */
    @Nullable
    static Boolean reasonsHold(Object target, @Nullable Object reason) throws IllegalAccessException {
        if (reason == null) return null;
        if (readType != target.getClass()) {
            List<Field> fields = new ArrayList<>();
            for (Class<?> type = target.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || !Set.class.isAssignableFrom(field.getType())) continue;
                    field.setAccessible(true);
                    fields.add(field);
                }
            }
            readFields = fields;
            readType = target.getClass();
        }
        Boolean held = null;
        for (Field field : readFields) {
            Object value = field.get(target);
            if (!(value instanceof EnumSet)) continue;
            if (((EnumSet<?>) value).contains(reason)) return true;
            held = false;
        }
        return held;
    }

    /** Pauses or plays the story through Facebook's pauser. False when there's no pauser to ask. */
    private static boolean setPaused(boolean pause) {
        Object target = pauser.get();
        Object reason = pauseReason();
        if (target == null || reason == null) return false;
        if (pause) {
            pauseWith(target, reason);
        } else {
            resumeWith(target, reason);
        }
        pausedHere = pause;
        HookStatus.counted(FamilyNames.STORY_TAPS, TOGGLES);
        return true;
    }

    /** The button on the window in front, made when [create] and it isn't there yet. */
    @Nullable
    static PlayButton button(boolean create) {
        Activity activity = resumed.get();
        if (activity == null || activity.isFinishing()) return null;
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof FrameLayout)) return null;
        View found = decor.findViewWithTag(TAG);
        if (found instanceof PlayButton) return (PlayButton) found;
        if (!create) return null;
        PlayButton made = new PlayButton(activity);
        made.setTag(TAG);
        made.setClickable(true);
        made.setFocusable(true);
        made.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        made.setOnClickListener(view -> buttonTapped());
        made.setVisibility(View.GONE);
        int size = Math.round(SIZE_DP * activity.getResources().getDisplayMetrics().density);
        ((ViewGroup) decor).addView(made, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        return made;
    }

    /** Shows the button with the story's state. False when there's no window to show it on. */
    static boolean show() {
        PlayButton shown = button(true);
        if (shown == null) return false;
        shown.playing(!paused());
        shown.animate().cancel();
        if (shown.getVisibility() != View.VISIBLE) {
            shown.setAlpha(0f);
            shown.setVisibility(View.VISIBLE);
            HookStatus.counted(FamilyNames.STORY_TAPS, SHOWN);
        }
        shown.animate().alpha(1f).setDuration(FADE_MS);
        touched = SystemClock.uptimeMillis();
        main.removeCallbacks(refresh);
        main.postDelayed(refresh, REFRESH_MS);
        return true;
    }

    private static void hide(boolean fade) {
        main.removeCallbacks(refresh);
        PlayButton shown = button(false);
        if (shown == null || shown.getVisibility() != View.VISIBLE) return;
        shown.animate().cancel();
        if (!fade) {
            shown.setVisibility(View.GONE);
            return;
        }
        shown.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> shown.setVisibility(View.GONE));
    }

    /** Keeps a showing button's symbol right, and hides it a while after the last tap once the story plays. */
    private static void refresh() {
        try {
            PlayButton shown = button(false);
            if (shown == null || shown.getVisibility() != View.VISIBLE) return;
            boolean playing = !paused();
            shown.playing(playing);
            if (playing && SystemClock.uptimeMillis() - touched >= HIDE_AFTER_MS) {
                hide(true);
                return;
            }
            main.postDelayed(refresh, REFRESH_MS);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_TAPS, "Story play button", failure);
        }
    }

    private static void buttonTapped() {
        try {
            boolean pause = !paused();
            if (!setPaused(pause)) return;
            touched = SystemClock.uptimeMillis();
            PlayButton shown = button(false);
            if (shown != null) shown.playing(!pause);
            main.removeCallbacks(refresh);
            main.postDelayed(refresh, REFRESH_MS);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_TAPS, "Story play button tap", failure);
        }
    }

    /** Forgets every tap, pauser and window, for tests. */
    static void forgetForTests() {
        main.removeCallbacks(single);
        main.removeCallbacks(refresh);
        resumed = new WeakReference<>(null);
        pauser = new WeakReference<>(null);
        pausedHere = false;
        answeredDown = Long.MIN_VALUE;
        answer = false;
        firstUp = 0;
        secondDown = 0;
        firstHeldBack = false;
        pauserForTests = null;
        reasonForTests = null;
        readType = null;
        readFields = new ArrayList<>();
    }

    /** The play and pause button: a dark disc with a white symbol, pause bars while the story plays. */
    static final class PlayButton extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        boolean playing;

        PlayButton(Context context) {
            super(context);
            playing(true);
        }

        void playing(boolean now) {
            if (playing == now && getContentDescription() != null) return;
            playing = now;
            setContentDescription(now ? L10n.t("Pause story") : L10n.t("Play story"));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(Math.min(cx, cy), DISC_DP / 2f * density);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x66000000);
            canvas.drawCircle(cx, cy, r, paint);
            paint.setColor(0xFFFFFFFF);
            // The symbol's square box, centered on the disc.
            float s = r * 1.7f, x = cx - s / 2, y = cy - s / 2;
            if (playing) {
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
    }
}
