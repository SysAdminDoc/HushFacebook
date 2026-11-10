/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Photo carousel (#345): a post with several photos or videos shows them in the row Facebook swipes
 * through, one at a time with a count in the corner, instead of a grid. Facebook picks how such a
 * post looks by asking its album layouts in turn whether each applies. The swipeable row
 * (MultiMediaAttachmentHScrollPlugin) applies only in the feeds and tests Facebook turns it on for,
 * and three layouts a poster can choose, columns, a banner and a frame, are asked before it. With
 * the switch on, the row's feed check answers yes and those three answer no, so the row takes every
 * post it can draw. A post it can't draw gets Facebook's grid as before, and the layouts for
 * sensitive photos and private tagged albums keep their place ahead of it.
 *
 * <p>Facebook's row draws no count and no dots, so {@link #drawCount} adds both. The row is Litho's
 * RecyclerView, and each photo in it a LithoView whose root is {@code MultimediaHScrollPhotoComponent}
 * ({@code X.0cNH} in 585), which LithoView's own text ends with. The patch fills in that class name
 * and calls {@link #drawCount} last thing in every RecyclerView's draw. The numbers are the ones the
 * row reports to accessibility: the adapter's item count and the first item shown.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, every answer is
 * Facebook's and nothing is drawn.
 */
public final class PhotoCarousel {
    /** Counted each time the row's feed check is answered yes. */
    static final String LET_IN = "Carousel let in";

    /** Counted each time a layout the poster chose is passed over. */
    static final String PASSED_OVER = "Poster's layout passed over";

    /** Counted each time the count over a row changes to a new photo. */
    static final String COUNT_SHOWN = "Photo count shown";

    /** The most dots drawn under a row. A longer row shows a window of them that moves along. */
    static final int MAX_DOTS = 7;

    /** The current photo's dot, Facebook's blue. */
    static final int CURRENT_DOT = 0xFF0866FF;

    /** Each item of a row already looked at, and whether it was the row's photo. Main thread only. */
    private static final Map<View, Boolean> PHOTOS = new WeakHashMap<>();

    /** Main thread only, as drawing is. */
    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint TEXT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF PILL = new RectF();

    static {
        TEXT.setColor(0xFFFFFFFF);
        TEXT.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    private static String lastCount = "";

    /** The album layouts a poster picks, passed over for the row, by the end of their class names. */
    static final String[] POSTER_LAYOUTS = {
            ".PhotoLayoutColumnsPlugin",
            ".PhotoLayoutBannerPlugin",
            ".PhotoLayoutFramePlugin",
    };

    private static final String FAMILY = FamilyNames.PHOTO_CAROUSEL;

    private static volatile boolean logged;

    private PhotoCarousel() {
    }

    /** Whether the row takes the posts: the switch on, nothing paused, the settings ready. */
    static boolean on() {
        return Utils.settingsReady() && Settings.PHOTO_CAROUSEL.get();
    }

    /**
     * The hook right after Facebook asks whether the swipeable row may show in this feed, handed the
     * answer as an int: a boolean method may leave a register the verifier types as int, and a
     * boolean parameter wouldn't take it. Answers yes while the switch is on, Facebook's answer
     * otherwise.
     */
    public static boolean carousel(int allowed) {
        boolean facebooks = allowed != 0;
        try {
            HookStatus.invoked(FAMILY);
            if (facebooks || !on()) return facebooks;
            HookStatus.bound(FAMILY, "carousel check");
            HookStatus.counted(FAMILY, LET_IN);
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Photo carousel: a post's photos go in the swipeable row");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "carousel check", failure);
            return facebooks;
        }
    }

    /**
     * The hook at the start of Facebook's check of whether an album layout applies, handed the
     * layout's class name. True makes the check answer no, for the three layouts a poster picks while
     * the switch is on. False, every other time, lets Facebook's check run.
     */
    public static boolean skipLayout(String plugin) {
        try {
            if (plugin == null || !on()) return false;
            for (String layout : POSTER_LAYOUTS) {
                if (plugin.endsWith(layout)) {
                    HookStatus.bound(FAMILY, "layout check");
                    HookStatus.counted(FAMILY, PASSED_OVER);
                    return true;
                }
            }
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "layout check", failure);
            return false;
        }
    }

    /**
     * Filled in by the patch: the class name of the photo in the swipeable row, {@code X.0cNH} in
     * 585. Null, as here, leaves every row without a count.
     */
    @Nullable
    public static String photoClass() {
        return null;
    }

    /**
     * The hook last thing in each RecyclerView's draw, over everything it drew. Each photo shown in a
     * row of the carousel's photos gets its number out of how many at its own top right, so the count
     * slides along with it. The dots stay put at the bottom middle of the row and only the blue one
     * moves (#442). Every other list draws as Facebook drew it: one that doesn't scroll sideways costs
     * a settings read and a scroll check, and a row's items are each looked at once.
     */
    public static void drawCount(ViewGroup row, Canvas canvas) {
        drawCount(row, canvas, photoClass());
    }

    /** {@link #drawCount(ViewGroup, Canvas)} with the row's photo class handed in, for the tests. */
    static void drawCount(ViewGroup row, Canvas canvas, @Nullable String photo) {
        try {
            if (!on()) return;
            if (!row.canScrollHorizontally(1) && !row.canScrollHorizontally(-1)) return;
            if (photo == null) return;

            int width = row.getWidth();
            int count = row.getChildCount();
            int[] lefts = new int[count];
            int[] rights = new int[count];
            int shown = 0;
            boolean photos = false;
            for (int i = 0; i < count; i++) {
                View item = row.getChildAt(i);
                if (item == null || item.getVisibility() != View.VISIBLE) continue;
                if (item.getRight() <= 0 || item.getLeft() >= width) continue;
                lefts[shown] = item.getLeft();
                rights[shown] = item.getRight();
                shown++;
                photos |= isPhoto(item, photo);
            }
            if (!photos) return;
            HookStatus.invoked(FAMILY);

            @SuppressWarnings("deprecation")
            AccessibilityEvent event = AccessibilityEvent.obtain();
            int total;
            int first;
            try {
                row.onInitializeAccessibilityEvent(event);
                total = event.getItemCount();
                first = event.getFromIndex();
            } finally {
                //noinspection deprecation
                event.recycle();
            }
            int current = current(lefts, rights, shown, width, first, total);
            if (current < 0) return;

            float density = row.getResources().getDisplayMetrics().density;
            Integer[] order = byLeft(lefts, shown);
            for (int rank = 0; rank < shown && first + rank < total; rank++) {
                int item = order[rank];
                drawCount(canvas, density, rights[item], first + rank, total);
            }
            drawDots(canvas, density, width, row.getHeight(), current, total);
            String label = label(current, total);
            if (!label.equals(lastCount)) {
                lastCount = label;
                HookStatus.bound(FAMILY, "photo count");
                HookStatus.counted(FAMILY, COUNT_SHOWN);
                Logger.printDebug(() -> "Photo carousel: drew the count " + label + " over a row");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "photo count", failure);
        }
    }

    /**
     * Whether [view] is a photo in a row of the carousel's photos, with the switch on: an item of a list
     * that scrolls sideways, whose LithoView text names the row's photo. Pinch to zoom zooms these.
     * Read fresh each time, since the row hands an item on to another post as it scrolls. Never throws.
     */
    public static boolean isRowPhoto(@Nullable View view) {
        try {
            if (view == null || !on()) return false;
            String photo = photoClass();
            if (photo == null || !(view.getParent() instanceof ViewGroup)) return false;
            ViewGroup row = (ViewGroup) view.getParent();
            if (!row.canScrollHorizontally(1) && !row.canScrollHorizontally(-1)) return false;
            return isPhotoText(String.valueOf(view), photo);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "row photo", failure);
            return false;
        }
    }

    /** Whether [item] is the row's photo, by the class name LithoView's text ends with. */
    private static boolean isPhoto(View item, String photo) {
        Boolean known = PHOTOS.get(item);
        if (known == null) {
            known = isPhotoText(String.valueOf(item), photo);
            PHOTOS.put(item, known);
        }
        return known;
    }

    /**
     * Whether a LithoView's [text] names [photo] as its root. LithoView adds its root's simple name
     * after the view's own text, and Redex writes a renamed class's simple name as
     * {@code <cls>X.0cNH</cls>}.
     */
    static boolean isPhotoText(String text, String photo) {
        return text.endsWith("<cls>" + photo + "</cls>") || text.endsWith("}" + photo);
    }

    /**
     * Which item of the row is current, from 0: the first item the row reports shown, plus where the
     * item most of the row shows sits among the [shown] items in [lefts] and [rights]. -1 when the row
     * reports fewer than two items or none shown.
     */
    static int current(int[] lefts, int[] rights, int shown, int width, int first, int total) {
        if (total < 2 || first < 0 || shown == 0) return -1;
        Integer[] order = byLeft(lefts, shown);
        int best = 0;
        int most = -1;
        for (int i = 0; i < shown; i++) {
            int item = order[i];
            int seen = Math.min(rights[item], width) - Math.max(lefts[item], 0);
            if (seen > most) {
                most = seen;
                best = i;
            }
        }
        return Math.min(first + best, total - 1);
    }

    /** The first [shown] places in [lefts], left to right. */
    static Integer[] byLeft(int[] lefts, int shown) {
        Integer[] order = new Integer[shown];
        for (int i = 0; i < shown; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Integer.compare(lefts[a], lefts[b]));
        return order;
    }

    /** The count as it reads over the row: {@code 4/10} for [current] 3 of [total] 10. */
    static String label(int current, int total) {
        return (current + 1) + "/" + total;
    }

    /** The first of the dots shown, keeping [current] near the middle of at most [max]. */
    static int firstDot(int current, int total, int max) {
        int shown = Math.min(total, max);
        return Math.max(0, Math.min(current - shown / 2, total - shown));
    }

    /** Item [current]'s count in a pill at the top right of the item, which ends at [itemRight]. */
    private static void drawCount(Canvas canvas, float density, int itemRight, int current, int total) {
        float margin = 12 * density;
        String label = label(current, total);

        TEXT.setTextSize(13 * density);
        Paint.FontMetrics metrics = TEXT.getFontMetrics();
        float padX = 10 * density;
        float padY = 4 * density;
        float height = metrics.descent - metrics.ascent + 2 * padY;
        float right = itemRight - margin;
        PILL.set(right - TEXT.measureText(label) - 2 * padX, margin, right, margin + height);
        FILL.setColor(0x99000000);
        canvas.drawRoundRect(PILL, height / 2, height / 2, FILL);
        canvas.drawText(label, PILL.left + padX, PILL.top + padY - metrics.ascent, TEXT);
    }

    /**
     * The row's dots in their pill at the bottom middle of a row [rowWidth] wide and [rowHeight] high,
     * item [current]'s blue.
     */
    private static void drawDots(Canvas canvas, float density, int rowWidth, int rowHeight, int current,
            int total) {
        float margin = 12 * density;
        float padX = 10 * density;
        float padY = 4 * density;
        int dots = Math.min(total, MAX_DOTS);
        int start = firstDot(current, total, MAX_DOTS);
        float radius = 3 * density;
        float step = 2 * radius + 6 * density;
        float dotsWidth = (dots - 1) * step;
        float centreY = rowHeight - margin - radius - padY;
        float left = rowWidth / 2f - dotsWidth / 2;
        PILL.set(left - padX, centreY - radius - padY, left + dotsWidth + padX, centreY + radius + padY);
        FILL.setColor(0x66000000);
        canvas.drawRoundRect(PILL, PILL.height() / 2, PILL.height() / 2, FILL);
        for (int i = 0; i < dots; i++) {
            int item = start + i;
            boolean moreBeyond = (i == 0 && start > 0) || (i == dots - 1 && start + dots < total);
            FILL.setColor(item == current ? CURRENT_DOT : 0xCCFFFFFF);
            canvas.drawCircle(left + i * step, centreY, moreBeyond ? radius * 0.6f : radius, FILL);
        }
    }
}
