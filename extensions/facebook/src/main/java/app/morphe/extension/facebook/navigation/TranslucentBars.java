/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * See-through bars (#124). Facebook's feed list already runs the full height of its main screen,
 * under the top bar and the tab bar, and pads its first and last posts clear of them, so each bar
 * only has to stop hiding what's under it. Both are found by classes Redex keeps: the top bar's
 * NavigationBar and the tab bar's TabBarContainerLayout, which draws the selected tab's line in its
 * onDraw. Each bar is the highest view above one of those that is still bar sized, and every
 * background in it that spans the bar (most of its width and at least half the height of its row)
 * is a fill: Facebook's own drawable, in the colour the theme, Material You or AMOLED gave it, with
 * its alpha scaled down. A row is the NavigationBar's or the TabBarContainerLayout's own height,
 * since with the tab bar at the top one holder stacks both rows and neither fill is half of it
 * (#171). Small backgrounds, such as a badge or a round button, and a tab's touch ripple keep
 * theirs, and nothing is laid out again, so the icons, the selected tab's line, the touch targets
 * and the insets stay as they were.
 *
 * <p>Facebook sets a bar's colour and alpha again when the theme changes, a page swaps the top
 * bar's content, or its own header fades, so the check runs as each frame of the main screen
 * draws. A fill whose colour or alpha isn't what this left on it has been changed by Facebook, and
 * what it shows now becomes Facebook's alpha, the one scaled and the one given back. Off, paused,
 * or with the settings not ready, every fill gets Facebook's alpha back on the next frame, and a
 * drawable Facebook takes out of a bar gets it back at once, in case Facebook puts it somewhere
 * else.
 *
 * <p>On Android 12 and newer each bar also gets frost: the view drawn just before the bar, the one
 * under it, records the part of itself the bar covers into a RenderNode each frame, and draws that
 * blurred in its overlay, so the blur sits between the feed and the bar's tint. RenderEffect only
 * blurs a view's own content, which is why the copy is needed. It costs one recording and one blur
 * of a bar sized area per bar per frame, and only while a frame draws anyway. The frost fades and
 * moves with the bar, so a bar sliding away takes its frost along. It stays inside the bar's rows,
 * so a holder taller than its tabs can't blur a band of the feed above them, and a SurfaceView is
 * left out of the copy: drawing one a second time moves or drops its video surface, and the reel
 * went black (#171). The copy is drawn around it and cleared where it is, so the video shows
 * through the tint the way it shows through its own hole. A page showing one used to get no frost
 * at all, and a feed video scrolling into view turned both bars from frost to a dark strip (#250).
 * Only one on screen counts: Reels' page waiting beside Home with its video kept every tab plain
 * once Reels had been opened, which read as the frost coming and going (#188).
 *
 * <p>Any failure gives every fill back and stops the bars of that screen, which then look stock.
 */
public final class TranslucentBars {
    /** Facebook's main screen, a manifest component, the one screen with both bars over the feed. */
    static final String MAIN_SCREEN = "com.facebook.katana.activity.FbMainTabActivity";
    /** The top bar's own view, a class Redex keeps. */
    static final String TOP_BAR = "com.facebook.navigation.navbar.NavigationBar";
    /** The tab bar's row of tabs, a class Redex keeps. */
    static final String TAB_BAR = "com.facebook.navigation.tabbar.ui.TabBarContainerLayout";

    /** How much of a bar's own alpha stays with nothing blurred under it. */
    static final int PLAIN_ALPHA = 0xC0;
    /** How much stays over frost, which takes the busy detail out of what shows through. */
    static final int FROSTED_ALPHA = 0xA6;
    /** The frost's blur radius, in dp. */
    static final float FROST_RADIUS_DP = 18f;
    /** How long a screen without both bars waits before it looks for them again. */
    static final long RESCAN_MS = 1000;
    /** How far below a bar's top view a fill can sit. The top bar's NavigationBar is seven down. */
    static final int MAX_DEPTH = 12;

    /** Counted each time a fill of Facebook's is made see-through, not each frame. */
    static final String MADE_SEE_THROUGH = "Bar background made see-through";
    /** Counted each time a bar gets its frost. */
    static final String FROSTED = "Bar frosted";
    /** Counted each time a bar is frosted from a view that holds its rows on screen, not its whole box. */
    static final String UNDER_ITS_ROWS = "Bar frosted from the view under its rows";
    /** Counted each time a bar finds no view under it, which leaves it the plain dark strip (#250). */
    static final String NOTHING_UNDER = "Bar with no view under it";
    /** Counted each time the switch going off or a pause gives the fills back. */
    static final String GIVEN_BACK = "Bar backgrounds given back";

    private static final String FAMILY = FamilyNames.BOTTOM_TAB_BAR;

    private static boolean watching;

    /** Each main screen's window and its bars. */
    static final Map<View, Bars> WINDOWS = new WeakHashMap<>();

    private TranslucentBars() {
    }

    /**
     * Called once per Facebook start, from the settings entry's onApplicationCreate, with the tab bar
     * patch in the build. Each main screen gets its bars as it resumes; the switch is asked per frame.
     */
    public static synchronized void watch(Context context) {
        try {
            if (watching || !(context instanceof Application)) return;
            ((Application) context).registerActivityLifecycleCallbacks(new Screens());
            watching = true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "see-through bars", failure);
        }
    }

    /** Whether the bars are see-through now: the switch on, nothing paused, the settings ready. */
    public static boolean on() {
        return Utils.settingsReady() && Settings.TRANSLUCENT_BARS.get();
    }

    /** The alpha a fill gets for [facebooks], Facebook's own alpha on it, with or without frost under it. */
    static int seeThrough(int facebooks, boolean frosted) {
        return facebooks * (frosted ? FROSTED_ALPHA : PLAIN_ALPHA) / 255;
    }

    /**
     * The bar [anchor] is part of: its highest ancestor still bar sized, one whose parent is more
     * than a third of [windowHeight] tall. [anchor] itself when its parent already is.
     */
    static View barOf(View anchor, int windowHeight) {
        View bar = anchor;
        ViewParent parent = anchor.getParent();
        while (parent instanceof View) {
            View up = (View) parent;
            if (up.getHeight() * 3 > windowHeight) break;
            bar = up;
            parent = up.getParent();
        }
        return bar;
    }

    /**
     * Whether [view]'s background [drawn] fills [bar], whose rows are [row] pixels tall: it spans
     * nine tenths of the bar's width and half a row's height, and isn't a touch ripple, which is
     * clear until a tab is pressed.
     */
    static boolean fills(View view, @Nullable Drawable drawn, View bar, int row) {
        if (drawn == null || drawn instanceof RippleDrawable) return false;
        return bar.getWidth() > 0 && view.getWidth() * 10 >= bar.getWidth() * 9 && view.getHeight() * 2 >= row;
    }

    /**
     * Keeps the frost [area] inside [rows], the bar's rows on screen in a window [windowHeight]
     * tall, on the feed's side: a bar in the lower half is frosted from its rows down, one in the
     * upper half from its rows up. The status bar and gesture insets past the rows stay frosted.
     */
    static void keepInside(Rect area, Rect rows, int windowHeight) {
        if (rows.isEmpty()) return;
        if (rows.centerY() * 2 > windowHeight) {
            area.top = Math.max(area.top, rows.top);
        } else {
            area.bottom = Math.min(area.bottom, rows.bottom);
        }
    }

    /**
     * Whether [host] is a SurfaceView or shows one: a visible one somewhere inside its box. One that's
     * hidden, or laid out or scrolled outside the box, isn't on screen to be moved, the way Facebook
     * 583 keeps each opened tab's page beside the one shown in a pager (#188). [box] is scratch space.
     */
    static boolean holdsSurface(View host, Rect box) {
        if (host instanceof SurfaceView) return true;
        box.set(0, 0, host.getWidth(), host.getHeight());
        return surfaceInside(host, box);
    }

    /**
     * Draws [host] on [canvas], which starts at its top left, for the frost: all of it but the
     * SurfaceViews on screen in it. A branch holding one is drawn child by child around it, only
     * the children reaching into [area], in [host]'s coordinates, and the copy is cleared where
     * the video is. [box] is scratch space.
     */
    static void record(View host, Canvas canvas, Rect area, Rect box) {
        if (host instanceof SurfaceView) {
            canvas.drawRect(0, 0, host.getWidth(), host.getHeight(), CLEAR);
        } else if (holdsSurface(host, box)) {
            drawAround((ViewGroup) host, canvas, area, box);
        } else {
            canvas.translate(-host.getScrollX(), -host.getScrollY());
            host.draw(canvas);
        }
    }

    /** [group]'s background, then each of its children that reaches into [area], the way record does it. */
    private static void drawAround(ViewGroup group, Canvas canvas, Rect area, Rect box) {
        Drawable background = group.getBackground();
        if (background != null) background.draw(canvas);
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getAlpha() <= 0f) continue;
            int dx = child.getLeft() - group.getScrollX();
            int dy = child.getTop() - group.getScrollY();
            int left = dx + Math.round(child.getTranslationX());
            int top = dy + Math.round(child.getTranslationY());
            if (!area.intersects(left, top, left + child.getWidth(), top + child.getHeight())) continue;
            int saved = canvas.save();
            canvas.translate(dx, dy);
            if (!child.getMatrix().isIdentity()) canvas.concat(child.getMatrix());
            area.offset(-left, -top);
            try {
                record(child, canvas, area, box);
            } finally {
                area.offset(left, top);
                canvas.restoreToCount(saved);
            }
        }
    }

    /** Clears what's drawn under it, where a video is left out of the frost. */
    private static final Paint CLEAR = clear();

    private static Paint clear() {
        Paint paint = new Paint();
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        return paint;
    }

    /** Whether a visible child of [view] is or holds a SurfaceView inside [box], in [view]'s coordinates. */
    private static boolean surfaceInside(View view, Rect box) {
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            int dx = child.getLeft() + Math.round(child.getTranslationX()) - group.getScrollX();
            int dy = child.getTop() + Math.round(child.getTranslationY()) - group.getScrollY();
            box.offset(-dx, -dy);
            boolean shows = box.intersects(0, 0, child.getWidth(), child.getHeight())
                    && (child instanceof SurfaceView || surfaceInside(child, box));
            box.offset(dx, dy);
            if (shows) return true;
        }
        return false;
    }

    /**
     * The view under [bar]: the last visible one its parent draws before it whose box holds the bar's
     * box, or null. The feed's page for the tab bar, and the page and tab bar's holder for the top bar.
     */
    @Nullable
    static View under(View bar) {
        ViewParent parent = bar.getParent();
        if (!(parent instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) parent;
        for (int i = group.indexOfChild(bar) - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getWidth() <= 0 || child.getHeight() <= 0) continue;
            if (child.getLeft() <= bar.getLeft() && child.getRight() >= bar.getRight()
                    && child.getTop() <= bar.getTop() && child.getBottom() >= bar.getBottom()) {
                return child;
            }
        }
        return null;
    }

    /**
     * The view under [bar] when none in its parent holds its whole box: the last visible one drawn
     * before it, in its parent or further up, that holds [rows], the bar's rows on screen in the
     * window, or null. A page can stop short of a holder that reaches past the rows, or sit a level
     * up, and still show under the bar, which then read as a dark strip with the feed sharp behind
     * it (#250). [origin] is scratch space.
     */
    @Nullable
    static View reachingUnder(View bar, Rect rows, int[] origin) {
        if (rows.isEmpty()) return null;
        View above = bar;
        ViewParent parent = bar.getParent();
        while (parent instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) parent;
            for (int i = group.indexOfChild(above) - 1; i >= 0; i--) {
                View child = group.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE || child.getWidth() <= 0 || child.getHeight() <= 0) continue;
                child.getLocationInWindow(origin);
                if (origin[0] <= rows.left && origin[1] <= rows.top
                        && origin[0] + child.getWidth() >= rows.right && origin[1] + child.getHeight() >= rows.bottom) {
                    return child;
                }
            }
            above = group;
            parent = group.getParent();
        }
        return null;
    }

    /** What shows whether a fill changed: its colour for a plain colour, its alpha for anything else. */
    static int reading(Drawable drawn) {
        if (drawn instanceof android.graphics.drawable.ColorDrawable) {
            return ((android.graphics.drawable.ColorDrawable) drawn).getColor();
        }
        return drawn.getAlpha();
    }

    /** The bars of [window], made once and asked every frame. */
    static Bars attach(View window) {
        synchronized (WINDOWS) {
            Bars bars = WINDOWS.get(window);
            if (bars == null) {
                bars = new Bars(window, TOP_BAR, TAB_BAR);
                WINDOWS.put(window, bars);
                ViewTreeObserver observer = window.getViewTreeObserver();
                observer.addOnPreDrawListener(bars);
                observer.addOnWindowFocusChangeListener(bars);
            }
            return bars;
        }
    }

    /** Gives each main screen its bars as it resumes. */
    static final class Screens implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityResumed(Activity activity) {
            try {
                if (!MAIN_SCREEN.equals(activity.getClass().getName()) || activity.getWindow() == null) return;
                attach(activity.getWindow().getDecorView());
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "see-through bars", failure);
            }
        }

        @Override
        public void onActivityCreated(Activity activity, @Nullable Bundle state) {
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityPaused(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle state) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }
    }

    /** A fill made see-through: Facebook's alpha, what it read afterwards, and the last frame it was seen. */
    static final class Fill {
        int facebooks;
        int left;
        boolean frosted;
        int frame;
    }

    /** One window's bars, asked as each frame draws. */
    static final class Bars implements ViewTreeObserver.OnPreDrawListener, ViewTreeObserver.OnWindowFocusChangeListener {
        private final WeakReference<View> window;
        private final String topBar;
        private final String tabBar;
        /** Every top bar and tab bar view the last look found. */
        private final List<WeakReference<View>> anchors = new ArrayList<>();
        /** Each fill made see-through, by the drawable itself. */
        final Map<Drawable, Fill> fills = new IdentityHashMap<>();
        /** Each bar's frost, by the bar. */
        private final Map<View, Object> frosts = new IdentityHashMap<>();
        /**
         * The part of one bar's fills on screen this frame, in the window, one fill's or anchor's
         * part (also the surface check's scratch box), and the bar's rows.
         */
        private final Rect seen = new Rect();
        private final Rect visible = new Rect();
        private final Rect rows = new Rect();
        private final int[] origin = new int[2];
        /** The bars with no view holding their box, by the bar: whether one holds their rows. */
        private final Map<View, Boolean> shortOfBox = new IdentityHashMap<>();
        private boolean describedNothingUnder;
        private long nextLook;
        private int frame;
        private boolean stopped;

        Bars(View window, String topBar, String tabBar) {
            this.window = new WeakReference<>(window);
            this.topBar = topBar;
            this.tabBar = tabBar;
        }

        @Override
        public boolean onPreDraw() {
            try {
                update();
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "see-through bars", failure);
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

        /** One frame: the fills made see-through or given back, and the frost brought up to date. */
        void update() {
            View window = this.window.get();
            if (window == null || stopped) return;
            if (!on()) {
                giveBack();
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (lookAgain() && now >= nextLook) {
                nextLook = now + RESCAN_MS;
                look(window);
            }
            frame++;
            int height = window.getHeight();
            boolean blurs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && window.isHardwareAccelerated();
            for (int i = 0; i < anchors.size(); i++) {
                View anchor = shown(i);
                if (anchor == null) continue;
                View bar = barOf(anchor, height);
                if (bar.getWidth() <= 0 || bar.getHeight() <= 0 || barBefore(bar, i, height)) continue;
                // With the tab bar at the top, both anchors share one holder: one pass, both rows.
                int row = bar.getHeight();
                rows.setEmpty();
                for (int k = i; k < anchors.size(); k++) {
                    View other = shown(k);
                    if (other == null || barOf(other, height) != bar) continue;
                    if (other.getHeight() > 0) row = Math.min(row, other.getHeight());
                    if (other.getGlobalVisibleRect(visible)) rows.union(visible);
                }
                View under = blurs ? underNow(bar, rows) : null;
                seen.setEmpty();
                float shown = keep(bar, bar, row, under != null, 1f, 0);
                if (under != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    keepInside(seen, rows, height);
                    if (shown > 0f && !seen.isEmpty()) {
                        frost(bar, under, seen, shown);
                    } else {
                        // Slid or faded out of sight: its frost waits, empty, for it to come back.
                        Frost waiting = (Frost) frosts.get(bar);
                        if (waiting != null) waiting.empty(frame);
                    }
                }
            }
            forgetUnseen();
            dropStaleFrost();
        }

        /**
         * The view under [bar] this frame, with [rows] its rows on screen. The report counts each
         * change to a view that only holds the rows or to none, and once says what the layout was,
         * so a dark strip on a phone nobody here has can be read from its diagnostic report.
         */
        @Nullable
        View underNow(View bar, Rect rows) {
            View holdingBox = under(bar);
            if (holdingBox != null) {
                shortOfBox.remove(bar);
                return holdingBox;
            }
            View holdingRows = reachingUnder(bar, rows, origin);
            Boolean found = holdingRows != null;
            if (!found.equals(shortOfBox.put(bar, found))) {
                HookStatus.counted(FAMILY, found ? UNDER_ITS_ROWS : NOTHING_UNDER);
                if (!found && !describedNothingUnder && !rows.isEmpty()) {
                    describedNothingUnder = true;
                    HookStatus.counted(FAMILY, describe(bar, rows));
                }
            }
            return holdingRows;
        }

        /** A bar's box, its rows and the boxes of what its parent draws before it, for the report. */
        private static String describe(View bar, Rect rows) {
            StringBuilder text = new StringBuilder("No view under bar ").append(box(bar))
                    .append(" rows ").append(rows.toShortString());
            ViewParent parent = bar.getParent();
            if (parent instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) parent;
                for (int i = Math.min(group.indexOfChild(bar), 4) - 1; i >= 0; i--) {
                    text.append(" before ").append(box(group.getChildAt(i)));
                }
            }
            return text.toString();
        }

        private static String box(View view) {
            return view.getLeft() + "," + view.getTop() + "-" + view.getRight() + "," + view.getBottom();
        }

        /** Anchor [index], or null when it's gone or not on screen. */
        @Nullable
        private View shown(int index) {
            View anchor = anchors.get(index).get();
            return anchor == null || !anchor.isShown() ? null : anchor;
        }

        /** Whether an anchor before [index] has [bar] as its bar, so this frame already did it. */
        private boolean barBefore(View bar, int index, int windowHeight) {
            for (int j = 0; j < index; j++) {
                View earlier = shown(j);
                if (earlier != null && barOf(earlier, windowHeight) == bar) return true;
            }
            return false;
        }

        /**
         * Whether to look for the bars again: none found yet, one Facebook took out of the window,
         * as a page that rebuilds its top bar does, or none on screen.
         */
        private boolean lookAgain() {
            boolean shown = false;
            for (WeakReference<View> reference : anchors) {
                View anchor = reference.get();
                if (anchor == null || !anchor.isAttachedToWindow()) return true;
                shown |= anchor.isShown();
            }
            return !shown;
        }

        /** Finds every top bar and tab bar view in [window]. */
        private void look(View window) {
            HookStatus.invoked(FAMILY);
            anchors.clear();
            boolean[] found = new boolean[2];
            collect(window, found);
            if (found[0]) HookStatus.bound(FAMILY, "see-through top bar");
            if (found[1]) {
                HookStatus.bound(FAMILY, "see-through tab bar");
                HookStatus.recoveredViewId(FAMILY, tabBar);
            } else if (window.getHeight() > 0) {
                // A main screen always has its tab bar, at the top or the bottom.
                HookStatus.missingViewId(FAMILY, tabBar);
            }
            Logger.printDebug(() -> "See-through bars: found " + anchors.size() + " bar views");
        }

        private void collect(View view, boolean[] found) {
            String name = view.getClass().getName();
            if (name.equals(topBar)) {
                anchors.add(new WeakReference<>(view));
                found[0] = true;
                return;
            }
            if (name.equals(tabBar)) {
                anchors.add(new WeakReference<>(view));
                found[1] = true;
                return;
            }
            if (!(view instanceof ViewGroup)) return;
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), found);
        }

        /**
         * Makes each fill in [view] and under it, down to {@link #MAX_DEPTH}, see-through, and adds
         * the part of each fill on screen to {@link #seen}. [row] is the height of the bar's rows,
         * and [alpha] is how drawn [view]'s parents in the bar are. Answers how drawn the most drawn
         * fill on screen is, 0 for none, which the frost takes. A bar Facebook slides or fades away
         * moves a view inside it, so the frost follows the fills rather than the bar's top view.
         */
        private float keep(View view, View bar, int row, boolean frosted, float alpha, int depth) {
            if (view.getVisibility() != View.VISIBLE) return 0f;
            float drawn = alpha * view.getAlpha();
            float most = 0f;
            Drawable background = view.getBackground();
            if (fills(view, background, bar, row)) {
                makeSeeThrough(background, frosted);
                if (frosted && drawn > 0f && view.getGlobalVisibleRect(visible)) {
                    seen.union(visible);
                    most = drawn;
                }
            }
            if (depth < MAX_DEPTH && view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    most = Math.max(most, keep(group.getChildAt(i), bar, row, frosted, drawn, depth + 1));
                }
            }
            return most;
        }

        /** Scales [drawn]'s alpha, unless it still shows what this left on it. */
        void makeSeeThrough(Drawable drawn, boolean frosted) {
            Fill fill = fills.get(drawn);
            int now = reading(drawn);
            if (fill != null && fill.left == now && fill.frosted == frosted) {
                fill.frame = frame;
                return;
            }
            if (fill == null) {
                fill = new Fill();
                fills.put(drawn, fill);
                HookStatus.counted(FAMILY, MADE_SEE_THROUGH);
                // Its own state, so a colour Facebook shares between views changes only here.
                drawn.mutate();
                fill.facebooks = drawn.getAlpha();
            } else if (fill.left != now) {
                // Facebook set it again since: what it shows now is Facebook's.
                fill.facebooks = drawn.getAlpha();
            }
            fill.frosted = frosted;
            drawn.setAlpha(seeThrough(fill.facebooks, frosted));
            fill.left = reading(drawn);
            fill.frame = frame;
        }

        /** Gives back the alpha of each fill no bar showed this frame, which Facebook took out or hid. */
        private void forgetUnseen() {
            Iterator<Map.Entry<Drawable, Fill>> each = fills.entrySet().iterator();
            while (each.hasNext()) {
                Map.Entry<Drawable, Fill> entry = each.next();
                if (entry.getValue().frame == frame) continue;
                giveBack(entry.getKey(), entry.getValue());
                each.remove();
            }
        }

        /** Every fill gets Facebook's alpha back and the frost goes. */
        void giveBack() {
            if (fills.isEmpty() && frosts.isEmpty()) return;
            for (Map.Entry<Drawable, Fill> entry : fills.entrySet()) giveBack(entry.getKey(), entry.getValue());
            if (!fills.isEmpty()) HookStatus.counted(FAMILY, GIVEN_BACK);
            fills.clear();
            for (Object frost : frosts.values()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ((Frost) frost).remove();
            }
            frosts.clear();
            Logger.printDebug(() -> "See-through bars: gave the bars back");
        }

        /** Facebook's alpha back on [drawn], unless Facebook changed it since. */
        private static void giveBack(Drawable drawn, Fill fill) {
            if (reading(drawn) == fill.left) drawn.setAlpha(fill.facebooks);
        }

        /** Stops this window's bars for good after a failure, giving everything back first. */
        void stop() {
            try {
                giveBack();
            } catch (Throwable ignored) {
                // Already failing; the window stays as it is.
            }
            stopped = true;
            View window = this.window.get();
            if (window == null) return;
            ViewTreeObserver observer = window.getViewTreeObserver();
            if (!observer.isAlive()) return;
            observer.removeOnPreDrawListener(this);
            observer.removeOnWindowFocusChangeListener(this);
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private void frost(View bar, View under, Rect area, float shown) {
            Frost frost = (Frost) frosts.get(bar);
            if (frost != null && frost.host != under) {
                frost.remove();
                frost = null;
            }
            if (frost == null) {
                float radius = FROST_RADIUS_DP * bar.getResources().getDisplayMetrics().density;
                frost = new Frost(under, radius);
                frosts.put(bar, frost);
                HookStatus.counted(FAMILY, FROSTED);
            }
            frost.update(area, shown, frame);
        }

        /** Takes away the frost of each bar that wasn't drawn this frame. */
        private void dropStaleFrost() {
            if (frosts.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
            Iterator<Object> each = frosts.values().iterator();
            while (each.hasNext()) {
                Frost frost = (Frost) each.next();
                if (frost.frame == frame) continue;
                frost.remove();
                each.remove();
            }
        }
    }

    /**
     * A bar's frost, in the overlay of the view under it: the part of that view the bar covers,
     * recorded before each frame and drawn blurred, so it lands over the feed and under the bar.
     * The recording draws the view, overlay included, and the frost skips itself while it does.
     * The blur sits inside [node], which clips it and fades it: a blurred node drawn at less than
     * full alpha spreads its clamped edge a blur's reach past its own box, the strip left above a
     * tab bar sliding away (#250).
     */
    @RequiresApi(Build.VERSION_CODES.S)
    static final class Frost extends Drawable {
        final View host;
        final RenderNode node = new RenderNode("Hushfacebook frost");
        final RenderNode blur = new RenderNode("Hushfacebook frost blur");
        private final int[] origin = new int[2];
        /** The recorded part of the host, in its coordinates, and record's scratch box. */
        private final Rect inside = new Rect();
        private final Rect box = new Rect();
        private boolean recording;
        int frame;

        Frost(View host, float radius) {
            this.host = host;
            blur.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
            blur.setClipToBounds(true);
            node.setClipToBounds(true);
            setBounds(0, 0, host.getWidth(), host.getHeight());
            host.getOverlay().add(this);
        }

        /** Records what's under [area], the bar's fills on screen in the window, drawn as much as [shown]. */
        void update(Rect area, float shown, int frame) {
            this.frame = frame;
            if (getBounds().width() != host.getWidth() || getBounds().height() != host.getHeight()) {
                setBounds(0, 0, host.getWidth(), host.getHeight());
            }
            host.getLocationInWindow(origin);
            int left = area.left - origin[0];
            int top = area.top - origin[1];
            int width = area.width();
            int height = area.height();
            node.setPosition(left, top, left + width, top + height);
            node.setAlpha(shown);
            blur.setPosition(0, 0, width, height);
            RecordingCanvas canvas = blur.beginRecording(width, height);
            recording = true;
            try {
                canvas.translate(-left, -top);
                inside.set(left, top, left + width, top + height);
                record(host, canvas, inside, box);
            } finally {
                recording = false;
                blur.endRecording();
            }
            node.beginRecording(width, height).drawRenderNode(blur);
            node.endRecording();
        }

        /** Draws nothing this frame, for a bar out of sight. */
        void empty(int frame) {
            this.frame = frame;
            node.setPosition(0, 0, 0, 0);
            node.beginRecording(0, 0);
            node.endRecording();
            blur.discardDisplayList();
        }

        void remove() {
            host.getOverlay().remove(this);
            node.discardDisplayList();
            blur.discardDisplayList();
        }

        @Override
        public void draw(Canvas canvas) {
            if (recording || !canvas.isHardwareAccelerated()) return;
            canvas.drawRenderNode(node);
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
