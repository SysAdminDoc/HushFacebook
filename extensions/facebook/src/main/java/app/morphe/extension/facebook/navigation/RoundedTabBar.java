/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.facebook.theme.AccentColor;
import app.morphe.extension.facebook.theme.MarkColors;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Rounded tab bar (#344): the tab bar at the bottom is drawn as a pill a little smaller than the
 * bar, with round ends, a gap at each side and a gap under it. The bar is found the way See-through
 * bars finds it, the highest view above Facebook's row of tabs that is still bar sized, and as each
 * frame of the main screen draws it gets an outline around its row of tabs, clipped to that outline,
 * and a scale about a point that keeps the pill's bottom a few dp above where the row ended. The
 * part of the bar outside the row (the strip it keeps over the phone's navigation bar) is clipped
 * away with the rest, and so is the bottom of a row Facebook lays out down over the navigation bar,
 * so the feed shows around the gesture handle. Facebook 585 lays its thin line along the top of the
 * bar over the row, not above it, so the clip keeps it as a hard edge across the pill: that line is
 * faded while the bar is a pill and gets its alpha back with the bar's shape (#383).
 *
 * <p>The selected tab gets a spot behind its icon, a smaller pill in Facebook's blue, or the colour
 * Accent color or the Material You theme gives that blue, or the picked Selected tab color (#383).
 * It's a layer over that tab's own background, so Facebook's press ripple stays, and it moves to
 * the next tab Facebook marks selected. Every tab in the row gets the layers once and keeps them
 * while the bar is a pill, so moving the spot never swaps a background under a tap (#412).
 *
 * <p>Tab bar width sets the scale, evenly in both directions so the icons keep their shape, and
 * Tab bar height trims the outline above and below the icons (#425). Both are asked each frame.
 *
 * <p>Nothing is laid out again: the scale and the clip are drawing properties, so Facebook's pages
 * keep their padding, scroll-away keeps sliding the bar, and a tap goes to the tab drawn under the
 * finger. A tab bar at the top is left as Facebook draws it. Off, paused, before the settings are
 * ready or on a failure, the bar gets Facebook's outline, clip and scale back on the next frame.
 */
public final class RoundedTabBar {
    /** Counted each time a bar is made a pill, not each frame. */
    static final String ROUNDED = "Tab bar rounded";
    /** Counted each time the switch going off or a pause gives a bar its own shape back. */
    static final String GIVEN_BACK = "Tab bar given its shape back";

    /** How much of its size the pill keeps with no Tab bar width picked. */
    static final float SCALE = 0.9f;
    /** How far above the bottom of the row of tabs the pill's bottom sits at {@link #SCALE}, in dp. */
    static final float LIFT_DP = 8f;

    /**
     * Tab bar width (#425): the pill's width as a percent of the row's, the screen's. The whole pill
     * is scaled to it, icons too, so nothing gets squashed. Blank is {@link #SCALE}.
     */
    public static final int DEFAULT_WIDTH = Math.round(SCALE * 100);
    public static final int MIN_WIDTH = 70;
    public static final int MAX_WIDTH = 100;
    /**
     * Tab bar height (#425): how much of its height the pill keeps, in percent, trimmed evenly
     * above and below the icons, which keep their size. Blank keeps all of it.
     */
    public static final int DEFAULT_HEIGHT = 100;
    public static final int MIN_HEIGHT = 60;
    public static final int MAX_HEIGHT = 100;

    /** The selected tab's spot: Facebook's blue, faint enough that the tab's own blue icon reads on it. */
    static final int SPOT_COLOR = 0x330866FF;
    /** How far the spot stays inside its tab at each side, in dp. */
    static final float SPOT_ACROSS_DP = 4f;
    /** How far the spot stays inside the part of its tab the pill shows, above and below, in dp. */
    static final float SPOT_DOWN_DP = 7f;
    /** The thickest view along the top of the row still taken for Facebook's line, in dp. 585's is 2 px. */
    static final float LINE_MAX_DP = 2f;

    private static final String FAMILY = FamilyNames.BOTTOM_TAB_BAR;

    /** Each main screen's window and its shaper. */
    static final Map<View, Shaper> WINDOWS = new WeakHashMap<>();
    /** Each bar made a pill, with what it had before. */
    static final Map<View, Shape> SHAPED = new WeakHashMap<>();
    /** Each tab given the spot, with the background it had. */
    static final Map<View, Spotted> SPOTTED = new WeakHashMap<>();

    /** The window's insets in a test, which can't give a view a navigation bar. */
    @Nullable static volatile WindowInsets insetsForTests;

    private RoundedTabBar() {
    }

    /** Whether the bar is rounded now: the switch on, nothing paused, the settings ready. */
    static boolean on() {
        return Utils.settingsReady() && Settings.ROUNDED_TAB_BAR.get();
    }

    /** The shaper of [window], made once and asked every frame. Called as each main screen resumes. */
    static Shaper attach(View window) {
        synchronized (WINDOWS) {
            Shaper shaper = WINDOWS.get(window);
            if (shaper == null) {
                shaper = new Shaper(window, TranslucentBars.TAB_BAR);
                WINDOWS.put(window, shaper);
                ViewTreeObserver observer = window.getViewTreeObserver();
                observer.addOnPreDrawListener(shaper);
                observer.addOnWindowFocusChangeListener(shaper);
            }
            return shaper;
        }
    }

    /**
     * The pill of [bar] on screen, in the window, into [out], for See-through bars to keep its frost
     * inside: its corner radius, or 0 when the bar isn't a pill.
     */
    static float pillInWindow(View bar, Rect out) {
        Shape shape = SHAPED.get(bar);
        if (shape == null || !(bar.getParent() instanceof View)) return 0f;
        // Where the bar would be unscaled, from its parent, then each edge scaled about the pivot.
        int[] at = new int[2];
        ((View) bar.getParent()).getLocationInWindow(at);
        float left = at[0] + bar.getLeft() + bar.getTranslationX();
        float top = at[1] + bar.getTop() + bar.getTranslationY();
        float pivotX = bar.getPivotX();
        float pivotY = bar.getPivotY();
        float scaleX = bar.getScaleX();
        float scaleY = bar.getScaleY();
        Rect row = shape.pill.row;
        out.set(Math.round(left + pivotX + scaleX * (row.left - pivotX)),
                Math.round(top + pivotY + scaleY * (row.top - pivotY)),
                Math.round(left + pivotX + scaleX * (row.right - pivotX)),
                Math.round(top + pivotY + scaleY * (row.bottom - pivotY)));
        return out.height() / 2f;
    }

    /** The Tab bar width [saved] picks, in percent, held to its range: {@link #DEFAULT_WIDTH} when blank. */
    public static int widthPercent(@Nullable String saved) {
        return TranslucentBars.percent(saved, MIN_WIDTH, MAX_WIDTH, DEFAULT_WIDTH);
    }

    /** The Tab bar height [saved] picks, in percent, held to its range: {@link #DEFAULT_HEIGHT} when blank. */
    public static int heightPercent(@Nullable String saved) {
        return TranslucentBars.percent(saved, MIN_HEIGHT, MAX_HEIGHT, DEFAULT_HEIGHT);
    }

    /** How much of its size the pill keeps now, from the Tab bar width picked. */
    static float scale() {
        return widthPercent(Settings.TAB_BAR_WIDTH.get()) / 100f;
    }

    /**
     * How far the pill at [scale] sits above the row's bottom, in pixels at [density]:
     * {@link #LIFT_DP} up to {@link #SCALE}, then less, to nothing at full width, so a wider pill's
     * top never rises further over the feed than the stock one's does.
     */
    static float lift(float scale, float density) {
        float share = Math.min(1f, Math.max(0f, (1f - scale) / (1f - SCALE)));
        return LIFT_DP * density * share;
    }

    /**
     * The point [bar] is scaled about, so the pill around [row], in the bar's coordinates, keeps its
     * middle and has its bottom [lift] pixels above the row's bottom: y maps to p + [scale] * (y - p).
     * At full size nothing is scaled, so the bottom stays where it is.
     */
    static float pivotY(Rect row, float lift, float scale) {
        if (scale >= 1f) return row.bottom;
        return row.bottom - lift / (1f - scale);
    }

    /** Trims [row] evenly at its top and bottom to [percent] of its height. */
    static void trim(Rect row, int percent) {
        if (percent >= 100 || row.isEmpty()) return;
        int cut = Math.round(row.height() * (100 - percent) / 200f);
        row.top += cut;
        row.bottom -= cut;
    }

    /** [anchor]'s box in [bar]'s coordinates, into [out]. */
    static void rowIn(View bar, View anchor, Rect out) {
        out.set(0, 0, anchor.getWidth(), anchor.getHeight());
        if (bar != anchor && bar instanceof ViewGroup) ((ViewGroup) bar).offsetDescendantRectToMyCoords(anchor, out);
    }

    /** The phone's navigation bar at the bottom of [window], the gesture handle or the buttons, in pixels. */
    static int navigationInset(View window) {
        WindowInsets insets = insetsForTests != null ? insetsForTests : window.getRootWindowInsets();
        return insets == null ? 0 : navigationBottom(insets);
    }

    /**
     * The navigation bar's part of [insets] at the bottom, in pixels. Android 10 and older have no
     * inset types, and their stable insets are the bars whether they show or not, the keyboard left out.
     */
    @SuppressWarnings("deprecation") // The stable insets are all Android had before 11.
    static int navigationBottom(WindowInsets insets) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                : insets.getStableInsetBottom();
    }

    /**
     * Ends [row], in [bar]'s coordinates, at [floor], where the navigation bar starts in the window:
     * Facebook 585 lays its row of tabs out to the bottom of the screen and leaves the part over the
     * gesture handle empty. Measured from where the bar is laid out, not drawn, so the pill keeps its
     * size while scroll-away slides the bar. [at] is scratch space.
     */
    static void aboveNavigation(View bar, Rect row, int floor, int[] at) {
        if (!(bar.getParent() instanceof View)) return;
        ((View) bar.getParent()).getLocationInWindow(at);
        int bottom = floor - at[1] - bar.getTop();
        if (bottom > row.top && bottom < row.bottom) row.bottom = bottom;
    }

    /**
     * Makes [bar] a pill around [anchor], or brings its pill up to date, ending it at [floor] when
     * the row runs down over the navigation bar. True the first time.
     */
    static boolean shape(View bar, View anchor, int floor) {
        Shape shape = SHAPED.get(bar);
        boolean fresh = shape == null;
        if (fresh) {
            shape = new Shape(bar);
            SHAPED.put(bar, shape);
            bar.setOutlineProvider(shape.pill);
            bar.setClipToOutline(true);
        }
        Rect row = shape.scratch;
        rowIn(bar, anchor, row);
        aboveNavigation(bar, row, floor, shape.at);
        Rect pill = shape.trimmed;
        pill.set(row);
        trim(pill, heightPercent(Settings.TAB_BAR_HEIGHT.get()));
        if (!pill.equals(shape.pill.row)) {
            shape.pill.row.set(pill);
            bar.invalidateOutline();
        }
        float scale = scale();
        float lift = lift(scale, bar.getResources().getDisplayMetrics().density);
        float pivotX = row.exactCenterX();
        float pivotY = pivotY(row, lift, scale);
        if (bar.getPivotX() != pivotX) bar.setPivotX(pivotX);
        if (bar.getPivotY() != pivotY) bar.setPivotY(pivotY);
        if (bar.getScaleX() != scale) bar.setScaleX(scale);
        if (bar.getScaleY() != scale) bar.setScaleY(scale);
        fadeLine(bar, row, shape);
        return fresh;
    }

    /**
     * Facebook's line along the top of [bar] that crosses the top of [row], the pill, in the bar's
     * coordinates: a child of the bar with nothing in it, nearly as wide as the bar and at most
     * {@link #LINE_MAX_DP} tall. A line above the row is clipped away with the rest and isn't one.
     */
    @Nullable
    static View lineOver(View bar, Rect row) {
        if (!(bar instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) bar;
        int thickest = Math.round(LINE_MAX_DP * bar.getResources().getDisplayMetrics().density);
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (child instanceof ViewGroup && ((ViewGroup) child).getChildCount() > 0) continue;
            int height = child.getHeight();
            if (height <= 0 || height > thickest || child.getWidth() * 10 < bar.getWidth() * 9) continue;
            if (child.getTop() < row.top + thickest && child.getBottom() > row.top) return child;
        }
        return null;
    }

    /** Fades the line over the top of [bar]'s pill, keeping the alpha Facebook gave it for {@link Shape#restore}. */
    static void fadeLine(View bar, Rect row, Shape shape) {
        View line = shape.line.get();
        if (line == null || line.getParent() != bar) {
            line = lineOver(bar, row);
            if (line == null) return;
            shape.line = new WeakReference<>(line);
            shape.lineAlpha = line.getAlpha();
        }
        if (line.getAlpha() != 0f) line.setAlpha(0f);
    }

    /**
     * The selected tab in [view], [anchor]'s row of tabs: the outermost selected view at most half
     * as wide as the row. Selecting a tab selects what's in it too, and the row itself is wider.
     */
    @Nullable
    static View selectedTab(View view, int rowWidth) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view.isSelected() && view.getWidth() > 0 && view.getWidth() * 2 <= rowWidth) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = selectedTab(group.getChildAt(i), rowWidth);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Puts the layers on every tab in [tab]'s row, the views beside it at most half as wide as the
     * [rowWidth] row, that doesn't wear them yet, and empties the spot of each but [tab]. Every tab
     * keeps its layers until the pill goes, so a tap never swaps a background: swapped as the tap
     * selected it, a tab's press highlight was left behind, a grey square on each tab visited
     * (#412). [wrapped] collects the tabs given layers.
     */
    static void wrapRow(View tab, int rowWidth, List<WeakReference<View>> wrapped) {
        ViewParent parent = tab.getParent();
        if (!(parent instanceof ViewGroup)) {
            if (wrap(tab)) wrapped.add(new WeakReference<>(tab));
            return;
        }
        ViewGroup row = (ViewGroup) parent;
        StringBuilder backgrounds = null;
        for (int i = 0; i < row.getChildCount(); i++) {
            View each = row.getChildAt(i);
            if (each != tab && (each.getWidth() <= 0 || each.getWidth() * 2 > rowWidth)) continue;
            Drawable facebooks = each.getBackground();
            if (wrap(each)) {
                wrapped.add(new WeakReference<>(each));
                if (backgrounds == null) backgrounds = new StringBuilder();
                backgrounds.append(' ').append(facebooks == null ? "none" : facebooks.getClass().getSimpleName());
            }
            if (each == tab) continue;
            Spotted spotted = SPOTTED.get(each);
            if (spotted != null && !spotted.spot.area.isEmpty()) {
                spotted.spot.area.setEmpty();
                spotted.spot.invalidateSelf();
            }
        }
        if (backgrounds != null) {
            String said = backgrounds.toString();
            Logger.printDebug(() -> "Rounded tab bar: tabs given the spot's layers over" + said);
        }
    }

    /**
     * Puts the layers on [tab] unless it wears them already. A background Facebook set since is
     * the one kept. True when it got them now.
     */
    static boolean wrap(View tab) {
        Spotted spotted = SPOTTED.get(tab);
        if (spotted != null && tab.getBackground() == spotted.layers) return false;
        spotted = new Spotted(tab.getBackground());
        SPOTTED.put(tab, spotted);
        tab.setBackground(spotted.layers);
        // The view lets go of the background it replaces, which is the one now inside the layers:
        // it drops its callback and, when attached, hides it. Unhooked, Facebook's press ripple
        // can't redraw the tab and freezes as a grey square (#412).
        if (spotted.facebooks != null && spotted.layers instanceof LayerDrawable) {
            spotted.facebooks.setCallback((LayerDrawable) spotted.layers);
            spotted.facebooks.setVisible(spotted.layers.isVisible(), false);
        }
        spotted.layers.jumpToCurrentState();
        return true;
    }

    /**
     * Gives [tab] the spot, or brings it up to date: inside the part of the tab the pill around
     * [row], in [bar]'s coordinates, shows.
     */
    static void spot(View bar, View tab, Rect row) {
        wrap(tab);
        Spotted spotted = SPOTTED.get(tab);
        if (spotted == null) return;
        Rect box = spotted.scratch;
        box.set(0, 0, tab.getWidth(), tab.getHeight());
        if (bar != tab && bar instanceof ViewGroup) ((ViewGroup) bar).offsetDescendantRectToMyCoords(tab, box);
        int top = Math.max(0, row.top - box.top);
        int bottom = Math.min(tab.getHeight(), row.bottom - box.top);
        float density = tab.getResources().getDisplayMetrics().density;
        int across = Math.round(SPOT_ACROSS_DP * density);
        // A pill trimmed short by Tab bar height keeps a spot half its height (#425).
        int down = Math.min(Math.round(SPOT_DOWN_DP * density), Math.max(0, (bottom - top) / 4));
        box.set(across, top + down, tab.getWidth() - across, bottom - down);
        if (!box.equals(spotted.spot.area)) {
            spotted.spot.area.set(box);
            spotted.spot.invalidateSelf();
        }
    }

    /** Gives [tab] back the background it had before the spot. */
    static void unspot(View tab) {
        Spotted spotted = SPOTTED.remove(tab);
        if (spotted == null || tab.getBackground() != spotted.layers) return;
        tab.setBackground(spotted.facebooks);
        if (spotted.facebooks != null) spotted.facebooks.jumpToCurrentState();
    }

    /** Gives [bar] back what it had before it was made a pill. True when it was one. */
    static boolean giveBack(View bar) {
        Shape shape = SHAPED.remove(bar);
        if (shape == null) return false;
        shape.restore(bar);
        return true;
    }

    /** What a bar had before it was made a pill, and the pill's outline. */
    static final class Shape {
        @Nullable
        final ViewOutlineProvider facebooksOutline;
        final boolean facebooksClip;
        final float scaleX;
        final float scaleY;
        final boolean pivotSet;
        final float pivotX;
        final float pivotY;
        final Pill pill = new Pill();
        final Rect scratch = new Rect();
        /** The row trimmed to the Tab bar height picked, the pill's outline before it's kept. */
        final Rect trimmed = new Rect();
        final int[] at = new int[2];
        /** Facebook's line over the top of the pill, faded, and the alpha it had. Weak: it's the bar's child. */
        WeakReference<View> line = new WeakReference<>(null);
        float lineAlpha = 1f;

        Shape(View bar) {
            facebooksOutline = bar.getOutlineProvider();
            facebooksClip = bar.getClipToOutline();
            scaleX = bar.getScaleX();
            scaleY = bar.getScaleY();
            pivotSet = bar.isPivotSet();
            pivotX = bar.getPivotX();
            pivotY = bar.getPivotY();
        }

        void restore(View bar) {
            View faded = line.get();
            if (faded != null && faded.getParent() == bar) faded.setAlpha(lineAlpha);
            bar.setOutlineProvider(facebooksOutline);
            bar.setClipToOutline(facebooksClip);
            bar.setScaleX(scaleX);
            bar.setScaleY(scaleY);
            if (pivotSet) {
                bar.setPivotX(pivotX);
                bar.setPivotY(pivotY);
            } else {
                bar.resetPivot();
            }
        }
    }

    /** A tab with the spot: the background it had, and the spot over it. */
    static final class Spotted {
        @Nullable
        final Drawable facebooks;
        final Spot spot = new Spot();
        final Drawable layers;
        final Rect scratch = new Rect();

        Spotted(@Nullable Drawable facebooks) {
            this.facebooks = facebooks;
            layers = facebooks == null ? spot : new LayerDrawable(new Drawable[]{facebooks, spot});
        }
    }

    /**
     * The spot's colour: Facebook's faint blue as the theme in force gives it, then the picked
     * Selected tab color over that, the order the selected tab's icon gets them in ({@code SelectedTab}).
     */
    static int spotColor() {
        return spotColor(AccentColor.themedBlue(SPOT_COLOR), SettingsStatus.markColors());
    }

    /** [themed], the spot's themed blue, with the picked Selected tab color when [markColors] is in the build. */
    static int spotColor(int themed, boolean markColors) {
        if (!markColors || !Utils.settingsReady()) return themed;
        Integer picked = MarkColors.colour(Settings.TAB_COLOR.get());
        return picked == null ? themed : (themed & 0xFF000000) | (picked & 0x00FFFFFF);
    }

    /**
     * The spot: [area], in its tab's coordinates, with ends as round as it is tall, in
     * {@link #spotColor}, so it matches the selected tab's icon with Accent color, the Material You
     * theme or a picked Selected tab color too.
     */
    static final class Spot extends Drawable {
        final Rect area = new Rect();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF round = new RectF();
        private int alpha = 255;

        @Override
        public void draw(Canvas canvas) {
            if (area.isEmpty()) return;
            int color = spotColor();
            paint.setColor(color);
            paint.setAlpha((color >>> 24) * alpha / 255);
            round.set(area);
            float radius = round.height() / 2f;
            canvas.drawRoundRect(round, radius, radius, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            this.alpha = alpha;
            invalidateSelf();
        }

        /** The paint's colour as the last draw left it, for a test. */
        int paintColor() {
            return paint.getColor();
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

    /** The pill: the row of tabs, in the bar's coordinates, with ends as round as it is tall. */
    static final class Pill extends ViewOutlineProvider {
        final Rect row = new Rect();

        @Override
        public void getOutline(View view, Outline outline) {
            if (row.isEmpty()) {
                outline.setRect(0, 0, view.getWidth(), view.getHeight());
            } else {
                outline.setRoundRect(row, row.height() / 2f);
            }
        }
    }

    /** One window's tab bar, asked as each frame draws. */
    static final class Shaper implements ViewTreeObserver.OnPreDrawListener, ViewTreeObserver.OnWindowFocusChangeListener {
        private final WeakReference<View> window;
        private final String tabBar;
        private WeakReference<View> anchor = new WeakReference<>(null);
        private WeakReference<View> shaped = new WeakReference<>(null);
        /** Every tab wearing the spot's layers, kept until the pill goes. */
        private final List<WeakReference<View>> wrapped = new ArrayList<>();
        private final int[] at = new int[2];
        private long nextLook;
        private boolean stopped;

        Shaper(View window, String tabBar) {
            this.window = new WeakReference<>(window);
            this.tabBar = tabBar;
        }

        @Override
        public boolean onPreDraw() {
            try {
                update();
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "rounded tab bar", failure);
                stop();
            }
            return true;
        }

        /** The settings page closing hands the window its focus back, so a change there shows now. */
        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            View window = this.window.get();
            if (hasFocus && window != null && !stopped) window.invalidate();
        }

        /** One frame: the bar made a pill, kept one, or given its shape back. */
        void update() {
            View window = this.window.get();
            if (window == null || stopped) return;
            if (!on()) {
                letGo();
                return;
            }
            View anchor = this.anchor.get();
            if (anchor == null || !anchor.isAttachedToWindow()) {
                long now = SystemClock.uptimeMillis();
                if (now < nextLook) return;
                nextLook = now + TranslucentBars.RESCAN_MS;
                anchor = find(window);
                this.anchor = new WeakReference<>(anchor);
                if (anchor == null) {
                    letGo();
                    return;
                }
            }
            int height = window.getHeight();
            View bar = TranslucentBars.barOf(anchor, height);
            if (bar.getWidth() <= 0 || anchor.getHeight() <= 0) return;
            View before = shaped.get();
            if (before != null && before != bar) letGo();
            anchor.getLocationInWindow(at);
            if ((at[1] + anchor.getHeight() / 2) * 2 <= height) {
                // The tab bar at the top stays Facebook's.
                letGo();
                return;
            }
            HookStatus.bound(FAMILY, "rounded tab bar");
            shaped = new WeakReference<>(bar);
            window.getLocationInWindow(at);
            if (shape(bar, anchor, at[1] + height - navigationInset(window))) {
                HookStatus.counted(FAMILY, ROUNDED);
                String barName = bar.getClass().getName();
                int rowWidth = anchor.getWidth();
                int rowHeight = anchor.getHeight();
                Logger.printDebug(() -> "Rounded tab bar: the bar " + barName + " is a pill around its "
                        + rowWidth + "x" + rowHeight + " row");
            }
            View tab = selectedTab(anchor, anchor.getWidth());
            Shape shape = SHAPED.get(bar);
            if (tab != null && shape != null) {
                HookStatus.bound(FAMILY, "selected tab");
                letGoOutside(tab.getParent());
                wrapRow(tab, anchor.getWidth(), wrapped);
                spot(bar, tab, shape.pill.row);
            } else {
                // No tab selected, no spot: the tabs keep their layers, empty.
                for (WeakReference<View> each : wrapped) {
                    View wearing = each.get();
                    Spotted spotted = wearing == null ? null : SPOTTED.get(wearing);
                    if (spotted != null && !spotted.spot.area.isEmpty()) {
                        spotted.spot.area.setEmpty();
                        spotted.spot.invalidateSelf();
                    }
                }
            }
        }

        /** Gives back the background of each wrapped tab no longer in [row], a row Facebook replaced. */
        private void letGoOutside(@Nullable ViewParent row) {
            for (Iterator<WeakReference<View>> it = wrapped.iterator(); it.hasNext(); ) {
                View tab = it.next().get();
                if (tab != null && tab.getParent() == row) continue;
                it.remove();
                if (tab != null) unspot(tab);
            }
        }

        /** Gives the bar this shaper made a pill its own shape back, and every tab its background. */
        private void letGo() {
            for (WeakReference<View> each : wrapped) {
                View tab = each.get();
                if (tab != null) unspot(tab);
            }
            wrapped.clear();
            View bar = shaped.get();
            shaped = new WeakReference<>(null);
            if (bar != null && giveBack(bar)) HookStatus.counted(FAMILY, GIVEN_BACK);
        }

        @Nullable
        private View find(View window) {
            HookStatus.invoked(FAMILY);
            return collect(window);
        }

        @Nullable
        private View collect(View view) {
            if (view.getClass().getName().equals(tabBar)) return view;
            if (!(view instanceof ViewGroup)) return null;
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = collect(group.getChildAt(i));
                if (found != null) return found;
            }
            return null;
        }

        /** Gives the bar back and stops asking, after a failure. */
        void stop() {
            stopped = true;
            try {
                letGo();
            } catch (Throwable ignored) {
                // Already failing: the bar keeps what it has.
            }
            View window = this.window.get();
            if (window == null) return;
            ViewTreeObserver observer = window.getViewTreeObserver();
            observer.removeOnPreDrawListener(this);
            observer.removeOnWindowFocusChangeListener(this);
        }
    }
}
