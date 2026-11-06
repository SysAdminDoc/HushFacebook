/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Home header text (#230): words of your own, or a picture of your own ({@link HeaderPicture}), at
 * the top of Home in place of Facebook's wordmark. The header calls {@link #dress} right after
 * Facebook loads its wordmark. The text is drawn at the wordmark's height and takes the same theme
 * color Facebook gives the wordmark, so it follows dark mode. A picture is drawn at that height in
 * its own colors, its see-through border left out, and takes the place of the words when both are
 * saved. Blank text with no picture,
 * Pause and every failure leave Facebook's wordmark alone.
 */
public final class HeaderText {
    /** The most characters the header takes. */
    public static final int MAX_LENGTH = 30;

    /** The text's color until the header hands over its theme color: Facebook's blue. */
    static final int FACEBOOK_BLUE = 0xFF0866FF;

    /** The text's height when the wordmark gave none, in dp. */
    private static final int FALLBACK_HEIGHT_DP = 24;

    /** The widest the text gets, as a share of the screen's width. Wider text gets smaller. */
    private static final float MAX_WIDTH_SHARE = 0.55f;

    /** A pixel of a picture this see-through or more counts as empty border, out of 255. */
    static final int CLEAR_ALPHA = 8;

    private HeaderText() {
    }

    /**
     * Puts the saved picture, or else the saved text, in place of the wordmark Facebook just loaded
     * into [logo], and puts it back whenever Facebook loads its wordmark again. Never throws.
     */
    public static void dress(@Nullable View logo) {
        try {
            HookStatus.invoked(FamilyNames.HEADER_TEXT);
            if (!(logo instanceof ImageView) || !Utils.settingsReady()) return;
            ImageView image = (ImageView) logo;
            Bitmap picture = HeaderPicture.load(image.getContext());
            String text = picture != null ? "" : clean(Settings.HEADER_TEXT.get());
            String emoji = LogoEmoji.clean(Settings.LOGO_EMOJI.get());
            Dressing dressing = new Dressing(picture, text, emoji);
            Drawable shown = dressing.apply(image, -1);
            if (shown == null) return;
            image.getViewTreeObserver().addOnPreDrawListener(new Keeper(image, shown, dressing));
            if (picture != null) {
                Logger.printDebug(() -> "Header picture: put a " + picture.getWidth() + "x" + picture.getHeight()
                        + " picture at the top of Home");
            } else if (!text.isEmpty()) {
                Logger.printDebug(() -> "Header text: put " + text.length() + " characters at the top of Home");
            }
            if (!emoji.isEmpty()) {
                Logger.printDebug(() -> "Header emoji: put " + emoji.codePointCount(0, emoji.length())
                        + " code points after the logo at the top of Home");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HEADER_TEXT, "header text", failure);
            Logger.printException(() -> "Header text: could not put the text at the top of Home", failure);
        }
    }

    /**
     * Whether a picture or text takes the header's place, so the picked app icon stays out of it: the
     * patch is in this build and a picture's copy or text is saved. Never throws.
     */
    public static boolean replacesLogo() {
        try {
            return Utils.settingsReady() && PatchFamily.HEADER_TEXT.inBuild()
                    && (!clean(Settings.HEADER_TEXT.get()).isEmpty() || HeaderPicture.chosen(Utils.getContext()));
        } catch (Throwable failure) {
            Logger.printException(() -> "Header text: could not read the header text", failure);
            return false;
        }
    }

    /**
     * What the header shows for [typed]: one line, without control or invisible formatting
     * characters, trimmed, and at most {@link #MAX_LENGTH} characters. Empty keeps the wordmark.
     */
    public static String clean(@Nullable String typed) {
        if (typed == null) return "";
        StringBuilder line = new StringBuilder();
        boolean space = false;
        int count = 0;
        for (int at = 0; at < typed.length() && count < MAX_LENGTH; ) {
            int point = typed.codePointAt(at);
            at += Character.charCount(point);
            int type = Character.getType(point);
            if (Character.isWhitespace(point) || Character.isSpaceChar(point)) {
                space = line.length() > 0;
                continue;
            }
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.UNASSIGNED
                    || type == Character.PRIVATE_USE || type == Character.SURROGATE) {
                continue;
            }
            if (space) {
                // A space needs room for the character after it, so the text never ends on one.
                if (count + 1 >= MAX_LENGTH) break;
                line.append(' ');
                count++;
                space = false;
            }
            line.appendCodePoint(point);
            count++;
        }
        return line.toString();
    }

    /** Whether [typed] is saved as typed, with nothing for {@link #clean} to take out. */
    public static boolean isClean(@Nullable String typed) {
        return typed != null && clean(typed).equals(typed);
    }

    /**
     * What goes at the top of Home: the picture, else the text, else Facebook's own wordmark, with the
     * emoji after it when one is saved (#360).
     */
    static final class Dressing {
        @Nullable final Bitmap picture;
        /** The part of {@link #picture} that shows, see-through borders left out. */
        @Nullable final Rect drawn;
        final String text;
        final String emoji;
        /** The height the last mark was given, so a reload keeps it. */
        int slot = -1;

        Dressing(@Nullable Bitmap picture, String text, String emoji) {
            this.picture = picture;
            this.drawn = picture == null ? null : shownPart(picture);
            this.text = text;
            this.emoji = emoji;
        }

        /**
         * Puts it in [image], sized from the wordmark's height or [previous] (the height the last mark
         * was given, -1 for none). Returns what [image] shows now, or null when there's nothing to put:
         * no picture, text or emoji, or no wordmark to put the emoji after.
         */
        @Nullable
        Drawable apply(ImageView image, int previous) {
            Drawable mark;
            int height = height(image, previous);
            if (picture != null) {
                mark = new PictureMark(picture, drawn, height, maxWidth(image));
            } else if (!text.isEmpty()) {
                mark = new TextMark(text, height, maxWidth(image));
            } else if (!emoji.isEmpty()) {
                // Facebook's own wordmark stays, with the emoji after it.
                mark = image.getDrawable();
                if (mark == null || mark instanceof LogoEmoji.Tail) return null;
                // Placed the way Facebook places its wordmark (#399): the header stretches the logo's
                // view past the wordmark and draws it from the start, so centering it there pushed the
                // logo right.
                Drawable shown = new LogoEmoji.Tail(mark, emoji, height, centers(image.getScaleType()));
                slot = height;
                image.setImageDrawable(shown);
                return shown;
            } else {
                return null;
            }
            Drawable shown = emoji.isEmpty() ? mark : new LogoEmoji.Tail(mark, emoji, height, true);
            slot = height;
            show(image, shown);
            return shown;
        }
    }

    private static void show(ImageView image, Drawable mark) {
        image.setImageDrawable(mark);
        image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
    }

    /** Whether [type] puts a drawable smaller than the view at the view's center across. */
    static boolean centers(@Nullable ImageView.ScaleType type) {
        return type == ImageView.ScaleType.CENTER || type == ImageView.ScaleType.CENTER_INSIDE
                || type == ImageView.ScaleType.CENTER_CROP || type == ImageView.ScaleType.FIT_CENTER;
    }

    /**
     * The height the header's wordmark takes: its own, else [previous] (the height the last mark was
     * given, -1 for none), else the view's, else {@link #FALLBACK_HEIGHT_DP}.
     */
    private static int height(ImageView image, int previous) {
        Drawable wordmark = image.getDrawable();
        int height = wordmark == null ? -1 : wordmark.getIntrinsicHeight();
        if (height <= 0) height = previous;
        if (height <= 0) height = image.getMeasuredHeight() - image.getPaddingTop() - image.getPaddingBottom();
        if (height <= 0 && image.getLayoutParams() != null && image.getLayoutParams().height > 0) {
            height = image.getLayoutParams().height - image.getPaddingTop() - image.getPaddingBottom();
        }
        float density = image.getResources().getDisplayMetrics().density;
        if (height <= 0) height = Math.round(FALLBACK_HEIGHT_DP * density);
        return height;
    }

    private static int maxWidth(ImageView image) {
        return Math.round(image.getResources().getDisplayMetrics().widthPixels * MAX_WIDTH_SHARE);
    }

    /**
     * The part of [picture] that shows: the box around every pixel less see-through than
     * {@link #CLEAR_ALPHA}, so a logo saved with an empty border fills the header the way the logo
     * alone would (#230 follow-up). The saved copy stays as picked. The whole picture when no pixel
     * shows.
     */
    static Rect shownPart(Bitmap picture) {
        int width = picture.getWidth();
        int height = picture.getHeight();
        int[] row = new int[width];
        int left = width;
        int top = -1;
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < height; y++) {
            picture.getPixels(row, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                if (Color.alpha(row[x]) < CLEAR_ALPHA) continue;
                if (top < 0) top = y;
                bottom = y;
                left = Math.min(left, x);
                right = Math.max(right, x);
            }
        }
        if (top < 0) return new Rect(0, 0, width, height);
        return new Rect(left, top, right + 1, bottom + 1);
    }

    /**
     * Facebook loads its wordmark again on its own (a theme change reloads it), so before each draw
     * the header gets the picture or text back if its wordmark came back. Holds the header weakly.
     */
    private static final class Keeper implements ViewTreeObserver.OnPreDrawListener {
        private final WeakReference<ImageView> header;
        private final Dressing dressing;
        private Drawable mark;

        Keeper(ImageView image, Drawable mark, Dressing dressing) {
            this.header = new WeakReference<>(image);
            this.mark = mark;
            this.dressing = dressing;
        }

        @Override
        public boolean onPreDraw() {
            ImageView image = header.get();
            if (image == null || image.getDrawable() == mark) return true;
            try {
                Drawable again = dressing.apply(image, dressing.slot);
                if (again == null) return true;
                mark = again;
                Logger.printDebug(() -> "Header text: Facebook loaded its wordmark again, put yours back");
                // This pass would draw Facebook's wordmark, so the next one draws yours.
                return false;
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.HEADER_TEXT, "header text again", failure);
                Logger.printException(() -> "Header text: could not put yours back", failure);
                return true;
            }
        }
    }

    /**
     * The picture's shown part ({@link #shownPart}) as a drawable at the wordmark's height, its shape
     * kept. A picture wider than {@link #MAX_WIDTH_SHARE} of the screen gets smaller. It keeps its own
     * colors, so the color filter and tint the header gives its wordmark don't reach it.
     */
    static final class PictureMark extends Drawable {
        final Bitmap picture;
        /** The part of {@link #picture} drawn. */
        final Rect part;
        /** The wordmark's height, which the picture fills unless it's too wide. */
        final int slot;
        private final int width;
        private final int height;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF place = new RectF();

        PictureMark(Bitmap picture, @Nullable Rect part, int slot, int maxWidth) {
            this.picture = picture;
            this.part = part != null ? part : new Rect(0, 0, picture.getWidth(), picture.getHeight());
            this.slot = slot;
            int wide = Math.max(1, Math.round(this.part.width() * slot / (float) this.part.height()));
            int high = slot;
            if (maxWidth > 0 && wide > maxWidth) {
                high = Math.max(1, Math.round(high * maxWidth / (float) wide));
                wide = maxWidth;
            }
            width = wide;
            height = high;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            float scale = Math.min(bounds.width() / (float) part.width(), bounds.height() / (float) part.height());
            float halfWidth = part.width() * scale / 2f;
            float halfHeight = part.height() * scale / 2f;
            place.set(bounds.exactCenterX() - halfWidth, bounds.exactCenterY() - halfHeight,
                    bounds.exactCenterX() + halfWidth, bounds.exactCenterY() + halfHeight);
            canvas.drawBitmap(picture, part, place, paint);
        }

        @Override
        public int getIntrinsicWidth() {
            return width;
        }

        @Override
        public int getIntrinsicHeight() {
            return height;
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public int getAlpha() {
            return paint.getAlpha();
        }

        /** The header's wordmark color would paint the whole picture one color, so it's left out. */
        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public void setTintList(@Nullable ColorStateList tint) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * The text as a drawable at the wordmark's height. The header colors its wordmark through a color
     * filter, which this takes, so the text gets Facebook's own color for light and dark mode.
     */
    static final class TextMark extends Drawable {
        final String text;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Rect ink = new Rect();
        private final int height;
        private final int width;
        private final float inkTop;
        private final float inkBottom;
        @Nullable private ColorStateList tint;
        private int color = FACEBOOK_BLUE;
        private int alpha = 255;

        TextMark(String text, int height, int maxWidth) {
            this.text = text;
            this.height = height;
            paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            paint.setColor(FACEBOOK_BLUE);
            // The text and a capital letter together set the size, so short words without capitals
            // or with descenders still sit at the wordmark's height.
            paint.setTextSize(100f);
            Rect reach = reach();
            float size = reach.height() > 0 ? 100f * height / reach.height() : height;
            paint.setTextSize(size);
            paint.getTextBounds(text, 0, text.length(), ink);
            if (maxWidth > 0 && ink.width() > maxWidth) {
                paint.setTextSize(size * maxWidth / ink.width());
                paint.getTextBounds(text, 0, text.length(), ink);
            }
            Rect fitted = reach();
            inkTop = fitted.top;
            inkBottom = fitted.bottom;
            width = Math.max(1, ink.width());
        }

        /** The text's ink with a capital H's, at the paint's size. */
        private Rect reach() {
            Rect union = new Rect();
            paint.getTextBounds(text, 0, text.length(), union);
            Rect capital = new Rect();
            paint.getTextBounds("H", 0, 1, capital);
            union.union(capital);
            return union;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            float x = bounds.left + (bounds.width() - width) / 2f - ink.left;
            float y = bounds.exactCenterY() - (inkTop + inkBottom) / 2f;
            canvas.drawText(text, x, y, paint);
        }

        @Override
        public int getIntrinsicWidth() {
            return width;
        }

        @Override
        public int getIntrinsicHeight() {
            return height;
        }

        @Override
        public void setAlpha(int alpha) {
            this.alpha = alpha;
            paint();
        }

        @Override
        public int getAlpha() {
            return alpha;
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Nullable
        @Override
        public ColorFilter getColorFilter() {
            return paint.getColorFilter();
        }

        @Override
        public void setTintList(@Nullable ColorStateList tint) {
            this.tint = tint;
            applyTint(getState());
        }

        @Override
        public boolean isStateful() {
            return tint != null && tint.isStateful();
        }

        @Override
        protected boolean onStateChange(@NonNull int[] state) {
            return applyTint(state);
        }

        private boolean applyTint(int[] state) {
            int tinted = tint == null ? FACEBOOK_BLUE : tint.getColorForState(state, tint.getDefaultColor());
            if (tinted == color) return false;
            color = tinted;
            paint();
            return true;
        }

        /** The paint takes the tint's color at the drawable's alpha. */
        private void paint() {
            paint.setColor(color);
            paint.setAlpha(Color.alpha(color) * alpha / 255);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
