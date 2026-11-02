/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.graphics.Outline;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Rounded tab bar (#344): the tab bar at the bottom is drawn as a pill a little smaller than the
 * bar, with round ends, a gap at each side and a gap under it. The bar is found the way See-through
 * bars finds it, the highest view above Facebook's row of tabs that is still bar sized, and as each
 * frame of the main screen draws it gets an outline around its row of tabs, clipped to that outline,
 * and a scale about a point that keeps the pill's bottom a few dp above where the row ended. The
 * part of the bar outside the row (a line over it, the strip it keeps over the phone's navigation
 * bar) is clipped away with the rest, and so is the bottom of a row Facebook lays out down over the
 * navigation bar, so the feed shows around the gesture handle.
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

    /** How much of its size the pill keeps. */
    static final float SCALE = 0.9f;
    /** How far above the bottom of the row of tabs the pill's bottom sits, in dp. */
    static final float LIFT_DP = 8f;

    private static final String FAMILY = FamilyNames.BOTTOM_TAB_BAR;

    /** Each main screen's window and its shaper. */
    static final Map<View, Shaper> WINDOWS = new WeakHashMap<>();
    /** Each bar made a pill, with what it had before. */
    static final Map<View, Shape> SHAPED = new WeakHashMap<>();

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

    /**
     * The point [bar] is scaled about, so the pill around [row], in the bar's coordinates, keeps its
     * middle and has its bottom [lift] pixels above the row's bottom: y maps to p + SCALE * (y - p).
     */
    static float pivotY(Rect row, float lift) {
        return row.bottom - lift / (1f - SCALE);
    }

    /** [anchor]'s box in [bar]'s coordinates, into [out]. */
    static void rowIn(View bar, View anchor, Rect out) {
        out.set(0, 0, anchor.getWidth(), anchor.getHeight());
        if (bar != anchor && bar instanceof ViewGroup) ((ViewGroup) bar).offsetDescendantRectToMyCoords(anchor, out);
    }

    /** The phone's navigation bar at the bottom of [window], the gesture handle or the buttons, in pixels. */
    static int navigationInset(View window) {
        WindowInsets insets = insetsForTests != null ? insetsForTests : window.getRootWindowInsets();
        return insets == null ? 0 : insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
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
        if (!row.equals(shape.pill.row)) {
            shape.pill.row.set(row);
            bar.invalidateOutline();
        }
        float lift = LIFT_DP * bar.getResources().getDisplayMetrics().density;
        float pivotX = row.exactCenterX();
        float pivotY = pivotY(row, lift);
        if (bar.getPivotX() != pivotX) bar.setPivotX(pivotX);
        if (bar.getPivotY() != pivotY) bar.setPivotY(pivotY);
        if (bar.getScaleX() != SCALE) bar.setScaleX(SCALE);
        if (bar.getScaleY() != SCALE) bar.setScaleY(SCALE);
        return fresh;
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
        final int[] at = new int[2];

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
        }

        /** Gives the bar this shaper made a pill its own shape back. */
        private void letGo() {
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
