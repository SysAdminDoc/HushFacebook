/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.animation.ValueAnimator;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;

/**
 * Glass tab bar (#596), with Rounded tab bar on. The pill gets a rim of light, bright along its top
 * and faint at its bottom, the way glass catches light from above, and a soft shadow under and
 * around it. The rim is drawn in the bar's own overlay, so the pill's clip keeps it on the pill's
 * edge. The shadow can't be: everything the bar draws is clipped to the pill, so it's drawn in the
 * overlay of the bar's parent, around the pill and never inside it, so a see-through bar doesn't
 * show it through itself. Both are brought up to date as each frame draws, so they follow the bar's
 * scale, its slide while scrolling and its fade.
 *
 * <p>The selected tab's spot becomes a bubble of glass with the same rim and a sheen, drawn in the
 * overlay of the row of tabs, over the tabs rather than behind one, so it can slide from the tab
 * you left to the one you pick. Its end in front leads and its end behind follows, so it stretches
 * a little on the way, like a drop. Nothing is laid out and no tab's background is touched, so a tap
 * goes where it always did and the press ripple stays (#412). With the phone's animations off it
 * moves at once.
 */
final class GlassLook {
    /** The rim's width, in dp, before the pill's scale. */
    static final float RIM_DP = 1.2f;
    /** The rim's light along the pill's top and along its bottom. */
    static final int RIM_TOP = 0x8CFFFFFF;
    static final int RIM_BOTTOM = 0x1AFFFFFF;
    /** How far the shadow spreads past the pill, and how far below it it falls, in dp. */
    static final float SHADOW_DP = 10f;
    static final float SHADOW_DROP_DP = 3f;
    static final int SHADOW_COLOR = 0x47000000;
    /** The bubble's sheen, from its top to its middle, over the spot's own colour. */
    static final int SHEEN_TOP = 0x33FFFFFF;
    static final int SHEEN_BOTTOM = 0x00FFFFFF;

    /** How long the bubble takes to slide to the next tab, in ms, timed by the clock rather than by frames. */
    static final long SLIDE_MS = 280;
    /** The share of the slide each end of the bubble moves in: the end in front first, the end behind last. */
    static final float LEAD = 0.75f;
    /** A gap between two frames of a slide longer than this, in ms, moves it one frame on, not the whole gap. */
    static final long SLIDE_GAP_MS = 50;
    static final long FRAME_MS = 16;

    final Rim rim = new Rim();
    final Shadow shadow = new Shadow();
    final Bubble bubble = new Bubble();
    /** Scratch for the bubble's place in its tab. */
    final Rect scratch = new Rect();
    private WeakReference<View> bar = new WeakReference<>(null);
    private WeakReference<ViewGroup> parent = new WeakReference<>(null);
    private WeakReference<ViewGroup> row = new WeakReference<>(null);
    private final RectF box = new RectF();
    private final Rect place = new Rect();

    /** Whether the bar gets the glass look: the switch on and the settings ready. Rounded tab bar is asked first. */
    static boolean on() {
        return Utils.settingsReady() && Settings.GLASS_TAB_BAR.get();
    }

    /** Puts the rim and the shadow on [bar] around [pill], in the bar's coordinates, or brings them up to date. */
    void follow(View bar, Rect pill) {
        ViewGroup parent = bar.getParent() instanceof ViewGroup ? (ViewGroup) bar.getParent() : null;
        if (this.bar.get() != bar || this.parent.get() != parent) removeEdges();
        if (parent == null) return;
        float density = bar.getResources().getDisplayMetrics().density;
        if (this.bar.get() == null) {
            this.bar = new WeakReference<>(bar);
            this.parent = new WeakReference<>(parent);
            rim.density = density;
            shadow.density = density;
            bar.getOverlay().add(rim);
            parent.getOverlay().add(shadow);
        }
        rim.setBounds(0, 0, bar.getWidth(), bar.getHeight());
        if (!rim.pill.equals(pill)) {
            rim.pill.set(pill);
            rim.invalidateSelf();
        }
        shadow.setBounds(0, 0, parent.getWidth(), parent.getHeight());
        // Where the pill shows in the parent: the bar's scale, pivot and slide applied.
        box.set(pill);
        bar.getMatrix().mapRect(box);
        box.offset(bar.getLeft(), bar.getTop());
        int shown = bar.getVisibility() == View.VISIBLE ? Math.round(255 * Math.max(0f, Math.min(1f, bar.getAlpha()))) : 0;
        if (!box.equals(shadow.box) || shown != shadow.shown) {
            shadow.box.set(box);
            shadow.radius = box.height() / 2f;
            shadow.shown = shown;
            shadow.invalidateSelf();
        }
    }

    /**
     * Puts the bubble over [tab], at [inTab] in the tab's coordinates, sliding it there when it was
     * over another tab. False when [tab] isn't in a row of tabs it can be drawn over.
     */
    boolean bubble(View tab, Rect inTab) {
        ViewGroup row = tab.getParent() instanceof ViewGroup ? (ViewGroup) tab.getParent() : null;
        if (row == null || inTab.isEmpty()) {
            removeBubble();
            return false;
        }
        if (this.row.get() != row) {
            removeBubble();
            this.row = new WeakReference<>(row);
            row.getOverlay().add(bubble);
        }
        bubble.density = tab.getResources().getDisplayMetrics().density;
        bubble.setBounds(0, 0, row.getWidth(), row.getHeight());
        place.set(inTab);
        place.offset(tab.getLeft(), tab.getTop());
        bubble.moveTo(place, SystemClock.uptimeMillis(), ValueAnimator.areAnimatorsEnabled());
        return true;
    }

    /** Hides the bubble, with no tab picked or with the tab's own spot back. */
    void hideBubble() {
        removeBubble();
    }

    /** Takes the rim, the shadow and the bubble off the bar they were on. */
    void remove() {
        removeEdges();
        removeBubble();
    }

    private void removeEdges() {
        View had = bar.get();
        if (had != null) had.getOverlay().remove(rim);
        ViewGroup holder = parent.get();
        if (holder != null) holder.getOverlay().remove(shadow);
        bar = new WeakReference<>(null);
        parent = new WeakReference<>(null);
        rim.pill.setEmpty();
        shadow.box.setEmpty();
    }

    private void removeBubble() {
        ViewGroup had = row.get();
        if (had != null) had.getOverlay().remove(bubble);
        row = new WeakReference<>(null);
        bubble.hide();
    }

    /** Whether the rim and the shadow are on [bar] now, for a test. */
    boolean isOn(View bar) {
        return this.bar.get() == bar && rim.getCallback() != null && shadow.getCallback() != null;
    }

    /** Whether the bubble is over a tab in [row] now, for a test. */
    boolean bubbleIn(View row) {
        return this.row.get() == row && bubble.getCallback() != null && !bubble.to.isEmpty();
    }

    /** [t], 0 to 1 and held there, eased out. */
    static float ease(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        float rest = 1f - t;
        return 1f - rest * rest * rest;
    }

    /** A paint's top-to-bottom gradient from [top] to [bottom], made again only when its box moved. */
    static final class Gradient {
        private float top = Float.NaN;
        private float bottom = Float.NaN;

        void on(Paint paint, float top, float bottom, int from, int to) {
            if (top == this.top && bottom == this.bottom) return;
            this.top = top;
            this.bottom = bottom;
            paint.setShader(new LinearGradient(0, top, 0, bottom, from, to, Shader.TileMode.CLAMP));
        }
    }

    /** Strokes the edge of [round], with round ends, in [width] px with [paint], kept inside [round]. */
    static void stroke(Canvas canvas, RectF round, Paint paint, float width, RectF scratch) {
        paint.setStrokeWidth(width);
        float inset = width / 2f;
        scratch.set(round);
        scratch.inset(inset, inset);
        float radius = scratch.height() / 2f;
        canvas.drawRoundRect(scratch, radius, radius, paint);
    }

    /** The light along the pill's edge, in the bar's coordinates. */
    static final class Rim extends Drawable {
        final Rect pill = new Rect();
        float density = 1f;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Gradient light = new Gradient();
        private final RectF round = new RectF();
        private final RectF inner = new RectF();
        private int alpha = 255;

        Rim() {
            paint.setStyle(Paint.Style.STROKE);
        }

        @Override
        public void draw(Canvas canvas) {
            if (pill.isEmpty()) return;
            round.set(pill);
            light.on(paint, round.top, round.bottom, RIM_TOP, RIM_BOTTOM);
            paint.setAlpha(alpha);
            stroke(canvas, round, paint, RIM_DP * density, inner);
        }

        @Override
        public void setAlpha(int alpha) {
            this.alpha = alpha;
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** The soft shadow around the pill, in the parent's coordinates, with the pill itself left clear. */
    static final class Shadow extends Drawable {
        /** Where the pill shows in the parent, its round ends' radius and how much of the bar shows, 0 to 255. */
        final RectF box = new RectF();
        float radius;
        int shown = 255;
        float density = 1f;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path pill = new Path();
        private final RectF dropped = new RectF();
        private float blurred;
        private int alpha = 255;

        @Override
        public void draw(Canvas canvas) {
            if (box.isEmpty() || shown <= 0) return;
            float blur = SHADOW_DP * density;
            if (blurred != blur) {
                paint.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
                blurred = blur;
            }
            paint.setColor(SHADOW_COLOR);
            paint.setAlpha((SHADOW_COLOR >>> 24) * shown / 255 * alpha / 255);
            pill.reset();
            pill.addRoundRect(box, radius, radius, Path.Direction.CW);
            dropped.set(box);
            dropped.offset(0, SHADOW_DROP_DP * density);
            int saved = canvas.save();
            canvas.clipOutPath(pill);
            canvas.drawRoundRect(dropped, radius, radius, paint);
            canvas.restoreToCount(saved);
        }

        @Override
        public void setAlpha(int alpha) {
            this.alpha = alpha;
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * The glass bubble over the selected tab, in the row's coordinates: the spot's colour, a sheen
     * and the rim, with ends as round as it is tall. [to] is where it goes, [from] where its slide
     * started and [drawn] where the last frame put it.
     */
    static final class Bubble extends Drawable {
        final RectF from = new RectF();
        final RectF to = new RectF();
        final RectF drawn = new RectF();
        boolean sliding;
        /** When the slide started and when its last frame drew, on the uptime clock. */
        long started;
        private long lastFrame;
        float density = 1f;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Gradient sheenLight = new Gradient();
        private final Gradient rimLight = new Gradient();
        private final RectF inner = new RectF();
        private int alpha = 255;

        Bubble() {
            rim.setStyle(Paint.Style.STROKE);
        }

        /** Sends it to [target] from [now]: a slide from where it is, or at once when it's new or [slides] is false. */
        void moveTo(Rect target, long now, boolean slides) {
            if (to.left == target.left && to.top == target.top && to.right == target.right && to.bottom == target.bottom) return;
            if (to.isEmpty() || !slides) {
                to.set(target);
                drawn.set(to);
                sliding = false;
            } else {
                from.set(drawn.isEmpty() ? to : drawn);
                to.set(target);
                sliding = true;
                started = now;
                lastFrame = now;
            }
            invalidateSelf();
        }

        /** Empties it, slide and all. */
        void hide() {
            to.setEmpty();
            drawn.setEmpty();
            sliding = false;
            invalidateSelf();
        }

        /** Puts in [drawn] where it is at [now]. */
        void place(long now) {
            if (!sliding) {
                drawn.set(to);
                return;
            }
            // A long wait between frames (a busy main thread) carries the slide one frame on, so it isn't skipped.
            if (now - lastFrame > SLIDE_GAP_MS) started += now - lastFrame - FRAME_MS;
            lastFrame = now;
            float t = (now - started) / (float) SLIDE_MS;
            if (t >= 1f) {
                sliding = false;
                drawn.set(to);
                return;
            }
            boolean right = to.centerX() >= from.centerX();
            float lead = ease(t / LEAD);
            float trail = ease((t - (1f - LEAD)) / LEAD);
            float even = ease(t);
            float left = right ? trail : lead;
            float rightEnd = right ? lead : trail;
            drawn.set(from.left + (to.left - from.left) * left, from.top + (to.top - from.top) * even,
                    from.right + (to.right - from.right) * rightEnd, from.bottom + (to.bottom - from.bottom) * even);
        }

        @Override
        public void draw(Canvas canvas) {
            if (to.isEmpty()) return;
            place(SystemClock.uptimeMillis());
            float radius = drawn.height() / 2f;
            int color = RoundedTabBar.spotColor();
            fill.setColor(color);
            fill.setAlpha((color >>> 24) * alpha / 255);
            canvas.drawRoundRect(drawn, radius, radius, fill);
            sheenLight.on(sheen, drawn.top, drawn.centerY(), SHEEN_TOP, SHEEN_BOTTOM);
            sheen.setAlpha(alpha);
            canvas.drawRoundRect(drawn, radius, radius, sheen);
            rimLight.on(rim, drawn.top, drawn.bottom, RIM_TOP, RIM_BOTTOM);
            rim.setAlpha(alpha);
            stroke(canvas, drawn, rim, RIM_DP * density, inner);
            if (sliding) invalidateSelf();
        }

        @Override
        public void setAlpha(int alpha) {
            this.alpha = alpha;
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
            fill.setColorFilter(filter);
            sheen.setColorFilter(filter);
            rim.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
