/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowInsets;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Show time in Reels: the minutes counted on the Reels screen ({@link GrayReels}, the same count Turn
 * gray over uses) in a small dark pill at the top middle of the screen, level with Facebook's own
 * top buttons. It's drawn by the window's decor view overlay, so Facebook's views, their layout and
 * their touches stay as they were, and it sits over the content frame's gray layer, not in it.
 * {@link GrayReels} puts it on and takes it off with its looks; main thread only.
 */
final class ReelsClock extends Drawable {
    static final String COUNT_SHOWN = "Reels time shown";

    static final int BACK_COLOR = 0xA0000000;
    static final int TEXT_COLOR = Color.WHITE;

    private static final float TEXT_SP = 13f;
    private static final float PAD_X_DP = 10f;
    private static final float PAD_Y_DP = 4f;
    /** How far under the status bar the pill's middle sits, about level with the top buttons. */
    static final float MIDDLE_DP = 16f;
    /** The status bar's height when the window doesn't give one. */
    private static final float BAR_DP = 24f;

    /** The decor view the pill is on, while it's on one. */
    private static WeakReference<View> on = new WeakReference<>(null);
    private static ReelsClock clock;

    private final Paint back = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect next = new Rect();
    private String label = "";
    private float radius;

    private ReelsClock() {
        back.setColor(BACK_COLOR);
        text.setColor(TEXT_COLOR);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
    }

    /** The pill's text after [watched] on the Reels screen: whole minutes, from 0. */
    static String label(long watched) {
        return L10n.f("%1$d min", (int) (Math.max(0L, watched) / 60_000L));
    }

    /** Shows [watched] on [decor], moving the pill there first when it's elsewhere or off. */
    static void show(View decor, long watched) {
        if (on.get() != decor) {
            hide();
            if (clock == null) clock = new ReelsClock();
            decor.getOverlay().add(clock);
            on = new WeakReference<>(decor);
            HookStatus.counted(FamilyNames.REEL_DECLUTTER, COUNT_SHOWN);
        }
        clock.update(decor, label(watched));
    }

    /** Takes the pill off the decor view it's on, if it's on one. */
    static void hide() {
        View decor = on.get();
        on = new WeakReference<>(null);
        if (decor != null && clock != null) decor.getOverlay().remove(clock);
    }

    static boolean shown() {
        return on.get() != null;
    }

    private void update(View decor, String now) {
        DisplayMetrics metrics = decor.getResources().getDisplayMetrics();
        text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP, metrics));
        Paint.FontMetrics font = text.getFontMetrics();
        float height = font.descent - font.ascent + 2 * PAD_Y_DP * metrics.density;
        float width = text.measureText(now) + 2 * PAD_X_DP * metrics.density;
        float middle = barHeight(decor, metrics.density) + MIDDLE_DP * metrics.density;
        int left = Math.round((decor.getWidth() - width) / 2f);
        int top = Math.round(middle - height / 2f);
        next.set(left, top, left + Math.round(width), top + Math.round(height));
        if (now.equals(label) && next.equals(getBounds())) return;
        label = now;
        radius = height / 2f;
        setBounds(next);
        invalidateSelf();
    }

    /** The status bar's height over [decor]: what the window gives, else a usual one. */
    @SuppressWarnings("deprecation")
    private static float barHeight(View decor, float density) {
        WindowInsets insets = decor.getRootWindowInsets();
        int top = insets == null ? 0 : insets.getStableInsetTop();
        return top > 0 ? top : BAR_DP * density;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect at = getBounds();
        if (at.isEmpty() || label.isEmpty()) return;
        canvas.drawRoundRect(at.left, at.top, at.right, at.bottom, radius, radius, back);
        Paint.FontMetrics font = text.getFontMetrics();
        canvas.drawText(label, at.exactCenterX(), at.exactCenterY() - (font.ascent + font.descent) / 2f, text);
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

    /** The pill's text while it's on a window, else null. */
    @Nullable
    static String shownForTests() {
        return shown() ? clock.label : null;
    }

    /** Where the pill is on its window. */
    static Rect boundsForTests() {
        return clock == null ? new Rect() : new Rect(clock.getBounds());
    }
}
