/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.icu.text.MeasureFormat;
import android.icu.util.Measure;
import android.icu.util.MeasureUnit;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Locale;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * How far a double tap on a feed video jumped, shown over the side that was tapped (#432): +10s on
 * the right, -10s on the left, adding up while the taps keep coming, so three quick taps read +30s.
 * It fades out a moment after the last one. It's drawn in the video's own overlay, so it moves with
 * the video and leaves Facebook's views and touches alone. Main thread only.
 */
final class SkipLabel extends Drawable {
    /** How long the label stays fully drawn after the last jump. */
    static final long SHOWN_MS = 650;
    /** How long it then takes to fade out. */
    static final long FADE_MS = 200;

    private static final String FAMILY = FamilyNames.TAP_TO_PLAY;

    /** The label on screen, if any. */
    @Nullable private static SkipLabel showing;

    private final WeakReference<ViewGroup> host;
    private final int side;
    private final float density;
    private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint words = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF shape = new RectF();
    private final ValueAnimator fade = ValueAnimator.ofInt(255, 0);
    /** Starts the fade once the label has been up {@link #SHOWN_MS} since the last jump. */
    private final Runnable fadeOut = fade::start;
    private int total;
    private String text = "";
    private int alpha = 255;
    private boolean faded;

    private SkipLabel(ViewGroup video, int side) {
        host = new WeakReference<>(video);
        this.side = side;
        DisplayMetrics metrics = video.getResources().getDisplayMetrics();
        density = metrics.density;
        pill.setColor(0x99000000);
        words.setColor(0xFFFFFFFF);
        words.setTypeface(Typeface.DEFAULT_BOLD);
        words.setTextAlign(Paint.Align.CENTER);
        words.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15, metrics));
        fade.setDuration(FADE_MS);
        fade.addUpdateListener(step -> {
            alpha = (Integer) step.getAnimatedValue();
            invalidateSelf();
        });
        fade.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationStart(Animator animation) {
                cancelled = false;
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!cancelled) remove();
            }
        });
    }

    /**
     * A double tap jumped [video] [by] milliseconds, ahead on the right ([side] 1) or back on the
     * left (-1). The label on that side of that video adds it to its total; any other is replaced.
     * Never throws.
     */
    static void jumped(@Nullable ViewGroup video, int side, int by) {
        if (video == null || side == 0 || by <= 0) return;
        try {
            SkipLabel label = showing;
            if (label == null || label.faded || label.side != side || label.host.get() != video) {
                if (label != null) label.remove();
                label = new SkipLabel(video, side);
                video.getOverlay().add(label);
                showing = label;
            }
            label.add(by);
            HookStatus.counted(FAMILY, "double tap skip, amount shown");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "double tap skip, amount shown", failure);
            clear();
        }
    }

    /** The label's words for a total of [ms] on [side], in [locale]: +10s, -20s and so on. */
    static String words(int side, int ms, Locale locale) {
        int seconds = Math.round(ms / 1000f);
        String amount;
        try {
            amount = MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.NARROW)
                    .format(new Measure(seconds, MeasureUnit.SECOND));
        } catch (Throwable failure) {
            amount = seconds + "s";
        }
        return (side > 0 ? "+" : "-") + amount;
    }

    /** The label on screen, for tests. */
    @Nullable
    static SkipLabel showing() {
        return showing;
    }

    /** Takes the label off the screen at once. */
    static void clear() {
        SkipLabel label = showing;
        if (label != null) label.remove();
        showing = null;
    }

    String text() {
        return text;
    }

    int side() {
        return side;
    }

    @Nullable
    ViewGroup host() {
        return host.get();
    }

    private void add(int by) {
        ViewGroup video = host.get();
        if (video == null) {
            remove();
            return;
        }
        total += by;
        text = words(side, total, video.getResources().getConfiguration().getLocales().get(0));
        float textWidth = words.measureText(text);
        Paint.FontMetrics font = words.getFontMetrics();
        int width = Math.round(textWidth + 24 * density);
        int height = Math.round(font.descent - font.ascent + 12 * density);
        int centerX = Math.round(video.getWidth() * (side > 0 ? 1f - FeedVideoDoubleTap.SIDE / 2 : FeedVideoDoubleTap.SIDE / 2));
        int centerY = video.getHeight() / 2;
        setBounds(centerX - width / 2, centerY - height / 2, centerX - width / 2 + width, centerY - height / 2 + height);
        fade.cancel();
        video.removeCallbacks(fadeOut);
        alpha = 255;
        invalidateSelf();
        video.postDelayed(fadeOut, SHOWN_MS);
    }

    private void remove() {
        faded = true;
        fade.cancel();
        ViewGroup video = host.get();
        if (video == null) {
            if (showing == this) showing = null;
            return;
        }
        video.removeCallbacks(fadeOut);
        video.getOverlay().remove(this);
        if (showing == this) showing = null;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty() || alpha == 0) return;
        shape.set(bounds);
        float radius = bounds.height() / 2f;
        pill.setAlpha(0x99 * alpha / 255);
        canvas.drawRoundRect(shape, radius, radius, pill);
        words.setAlpha(alpha);
        Paint.FontMetrics font = words.getFontMetrics();
        float baseline = bounds.exactCenterY() - (font.ascent + font.descent) / 2;
        canvas.drawText(text, bounds.exactCenterX(), baseline, words);
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter filter) {
        pill.setColorFilter(filter);
        words.setColorFilter(filter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    /** The current alpha, 255 fully drawn, 0 gone. */
    int shownAlpha() {
        return alpha;
    }
}
