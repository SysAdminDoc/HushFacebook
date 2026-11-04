/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The bar the Clean up the story viewer patch draws along the bottom of a story as it plays, like
 * the one on Reels (#380).
 *
 * <p>Facebook moves the segments at the top of a story every frame, through a view of segments on
 * some accounts and a drawable on others, and the patch hands each move here with the playing
 * story's progress. The bar goes in the overlay of the story's card, the nearest view around the
 * segments that's at least half the window tall, so a sheet over the story covers it, it never
 * takes a touch, and it goes away with the card. It lines up with the segments and fills as the
 * story plays.
 *
 * <p>Off, paused, settings that aren't ready yet or a failure in here, and a card keeps no bar.
 */
public final class StoryProgressBar {
    /** Counted each time a story's card gets its bar. */
    static final String ADDED = "story progress bar added";

    /** How thick the bar is and how far it sits above the card's bottom edge. */
    static final int THICKNESS_DP = 2;
    static final int ABOVE_BOTTOM_DP = 12;

    static final int TRACK_COLOR = 0x59FFFFFF;
    static final int FILL_COLOR = 0xFFFFFFFF;

    /** Each card's bar, by the card. Main thread only. */
    private static final Map<View, Bar> BARS = new WeakHashMap<>();

    /** Whether a bar was put on since the last one came off, so a switch left off costs nothing per frame. */
    private static volatile boolean anyBar;

    private StoryProgressBar() {
    }

    /**
     * Injection point, right after a progress listener moves the segments: the segments, a view or
     * a drawable, and the playing story's progress, 0 to 1000. Never throws.
     */
    public static void segmentsMoved(@Nullable Object segments, int progress) {
        try {
            HookStatus.invoked(FamilyNames.STORY_VIEWER_CLEANUP);
            HookStatus.bound(FamilyNames.STORY_VIEWER_CLEANUP, "story progress");
            if (!anyBar && !on()) return;
            View host;
            int left;
            int right;
            if (segments instanceof View) {
                host = (View) segments;
                left = host.getPaddingLeft();
                right = host.getWidth() - host.getPaddingRight();
            } else if (segments instanceof Drawable && ((Drawable) segments).getCallback() instanceof View) {
                host = (View) ((Drawable) segments).getCallback();
                Rect bounds = ((Drawable) segments).getBounds();
                left = bounds.left;
                right = bounds.right;
            } else {
                return;
            }
            if (Looper.myLooper() != Looper.getMainLooper()) {
                host.post(() -> segmentsMoved(segments, progress));
                return;
            }
            place(host, left, right, progress / 1000f);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_VIEWER_CLEANUP, "story progress", failure);
        }
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.STORY_PROGRESS_BAR.get();
    }

    /** Puts the bar on the card around [host], under the segments from [left] to [right] in [host]. */
    private static void place(View host, int left, int right, float progress) {
        View card = cardOf(host);
        if (card == null) return;
        Bar bar = BARS.get(card);
        if (!on()) {
            if (bar != null) bar.takeOff(card);
            // Also clears it once every card that had a bar is gone.
            anyBar = !BARS.isEmpty();
            return;
        }
        if (right <= left) return;
        if (bar == null) {
            bar = new Bar();
            card.getOverlay().add(bar);
            card.addOnAttachStateChangeListener(bar);
            BARS.put(card, bar);
            anyBar = true;
            HookStatus.counted(FamilyNames.STORY_VIEWER_CLEANUP, ADDED);
        }
        int shift = leftIn(card, host);
        float density = card.getResources().getDisplayMetrics().density;
        int thickness = Math.max(1, Math.round(THICKNESS_DP * density));
        // The overlay scrolls with the card's content, so its visible bottom is past the scroll.
        int bottom = card.getScrollY() + card.getHeight() - Math.round(ABOVE_BOTTOM_DP * density);
        bar.move(shift + left, bottom - thickness, shift + right, bottom, progress);
    }

    /** The story's card: [host] or the nearest view around it at least half its window tall. */
    @Nullable
    static View cardOf(View host) {
        int tall = host.getRootView().getHeight() / 2;
        if (tall <= 0) return null;
        View at = host;
        while (at.getHeight() < tall) {
            ViewParent parent = at.getParent();
            if (!(parent instanceof View)) return null;
            at = (View) parent;
        }
        return at;
    }

    /**
     * [host]'s left edge where [card]'s overlay draws, [card] being [host] or a view around it.
     * Walked through the views between them, so it holds whether or not they're in a window yet.
     * The card's own scroll moves its overlay along with its children, so only the views in
     * between scroll [host] out of line.
     */
    static int leftIn(View card, View host) {
        int x = 0;
        View at = host;
        while (at != card) {
            x += at.getLeft() + Math.round(at.getTranslationX());
            ViewParent parent = at.getParent();
            if (!(parent instanceof View)) break;
            at = (View) parent;
            if (at != card) x -= at.getScrollX();
        }
        return x;
    }

    /** The bar on [card], or null when it has none. For tests. */
    @Nullable
    static Bar barOn(View card) {
        return BARS.get(card);
    }

    /**
     * A track across the bar with the played part filled over it. It comes off when its card leaves
     * the window, since Facebook pools story cards and a reused one could turn up anywhere.
     */
    static final class Bar extends Drawable implements View.OnAttachStateChangeListener {
        private final Paint track = new Paint();
        private final Paint fill = new Paint();
        float progress;

        Bar() {
            track.setColor(TRACK_COLOR);
            fill.setColor(FILL_COLOR);
        }

        /** Takes this bar off [card]. Main thread only. */
        void takeOff(View card) {
            card.removeOnAttachStateChangeListener(this);
            card.getOverlay().remove(this);
            if (BARS.get(card) == this) BARS.remove(card);
        }

        @Override
        public void onViewAttachedToWindow(@NonNull View card) {
        }

        @Override
        public void onViewDetachedFromWindow(@NonNull View card) {
            try {
                takeOff(card);
                anyBar = !BARS.isEmpty();
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.STORY_VIEWER_CLEANUP, "story progress", failure);
            }
        }

        void move(int left, int top, int right, int bottom, float played) {
            float clamped = Math.max(0f, Math.min(1f, played));
            Rect at = getBounds();
            if (at.left == left && at.top == top && at.right == right && at.bottom == bottom && clamped == progress) return;
            progress = clamped;
            setBounds(left, top, right, bottom);
            invalidateSelf();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect at = getBounds();
            canvas.drawRect(at, track);
            canvas.drawRect(at.left, at.top, at.left + at.width() * progress, at.bottom, fill);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
