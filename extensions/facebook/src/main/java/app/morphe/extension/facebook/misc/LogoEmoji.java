/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The emoji after the Facebook logo (#360). {@link #clean} says what a saved string shows, and
 * {@link Tail} draws it right after the header's wordmark (or the words or picture that replace
 * it) at the wordmark's height, in the emoji's own colors: the color filter and tint the header
 * gives its wordmark go to the wordmark alone.
 */
public final class LogoEmoji {
    /** The most code points the setting keeps: one emoji with its joiners and variation marks fits. */
    public static final int MAX_CODE_POINTS = 8;

    /** Zero width joiner, which glues family and profession emoji together. */
    private static final int JOINER = 0x200D;

    private LogoEmoji() {
    }

    /**
     * What the header shows for [typed]: one line, trimmed, with no control or invisible
     * formatting characters (the joiner stays, emoji need it) and at most {@link #MAX_CODE_POINTS}
     * code points. Empty means no emoji.
     */
    public static String clean(@Nullable String typed) {
        if (typed == null) return "";
        StringBuilder line = new StringBuilder();
        int count = 0;
        for (int at = 0; at < typed.length() && count < MAX_CODE_POINTS; ) {
            int point = typed.codePointAt(at);
            at += Character.charCount(point);
            int type = Character.getType(point);
            if (Character.isWhitespace(point) || Character.isSpaceChar(point)) continue;
            if (type == Character.CONTROL || type == Character.UNASSIGNED
                    || type == Character.PRIVATE_USE || type == Character.SURROGATE) {
                continue;
            }
            if (type == Character.FORMAT && point != JOINER) continue;
            // A joiner opening the text or doubling up joins nothing.
            if (point == JOINER && (line.length() == 0 || line.codePointBefore(line.length()) == JOINER)) continue;
            line.appendCodePoint(point);
            count++;
        }
        // A joiner left at the end joins nothing either.
        while (line.length() > 0 && line.codePointBefore(line.length()) == JOINER) {
            line.setLength(line.length() - 1);
        }
        return line.toString();
    }

    /** Whether [typed] is saved as typed, with nothing for {@link #clean} to take out. */
    public static boolean isClean(@Nullable String typed) {
        return typed != null && clean(typed).equals(typed);
    }

    /**
     * [base] with [emoji] drawn right after it. The tail is as tall as [base] and as wide as both
     * with a small gap. Color filters and tints reach [base] only.
     */
    static final class Tail extends Drawable implements Drawable.Callback {
        final Drawable base;
        final String emoji;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Rect ink = new Rect();
        private final int height;
        private final int baseWidth;
        private final int gap;
        private final int emojiWidth;

        Tail(Drawable base, String emoji, int height) {
            this.base = base;
            this.emoji = emoji;
            this.height = Math.max(1, height);
            int intrinsic = base.getIntrinsicWidth();
            // A wordmark with no width of its own takes the room that's left once the emoji has its own.
            this.baseWidth = intrinsic > 0 ? intrinsic : -1;
            this.gap = Math.max(1, this.height / 6);
            paint.setColor(Color.BLACK);
            // Sized the way the header's words are: the emoji's ink height fills the wordmark's.
            paint.setTextSize(100f);
            paint.getTextBounds(emoji, 0, emoji.length(), ink);
            float size = ink.height() > 0 ? 100f * this.height / ink.height() : this.height;
            paint.setTextSize(size);
            paint.getTextBounds(emoji, 0, emoji.length(), ink);
            float measured = paint.measureText(emoji);
            this.emojiWidth = Math.max(1, Math.round(measured > 0 ? measured : this.height));
            base.setCallback(this);
        }

        /** The text drawn after the base, for a test to read. */
        String emoji() {
            return emoji;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            int baseRight = baseWidth > 0 ? bounds.left + baseWidth : bounds.right - gap - emojiWidth;
            base.setBounds(bounds.left, bounds.top, Math.max(bounds.left, baseRight), bounds.bottom);
            base.draw(canvas);
            float y = ink.height() > 0
                    ? bounds.exactCenterY() - (ink.top + ink.bottom) / 2f
                    : bounds.exactCenterY() - (paint.ascent() + paint.descent()) / 2f;
            canvas.drawText(emoji, baseRight + gap, y, paint);
        }

        @Override
        public int getIntrinsicWidth() {
            return baseWidth > 0 ? baseWidth + gap + emojiWidth : -1;
        }

        @Override
        public int getIntrinsicHeight() {
            return height;
        }

        @Override
        public void setAlpha(int alpha) {
            base.setAlpha(alpha);
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public int getAlpha() {
            return paint.getAlpha();
        }

        /** The wordmark's color goes to the wordmark. The emoji keeps its own colors. */
        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            base.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Nullable
        @Override
        public ColorFilter getColorFilter() {
            return base.getColorFilter();
        }

        @Override
        public void setTintList(@Nullable ColorStateList tint) {
            base.setTintList(tint);
        }

        @Override
        public boolean isStateful() {
            return base.isStateful();
        }

        @Override
        protected boolean onStateChange(@NonNull int[] state) {
            return base.setState(state);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public void invalidateDrawable(@NonNull Drawable who) {
            invalidateSelf();
        }

        @Override
        public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
            scheduleSelf(what, when);
        }

        @Override
        public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
            unscheduleSelf(what);
        }
    }
}
