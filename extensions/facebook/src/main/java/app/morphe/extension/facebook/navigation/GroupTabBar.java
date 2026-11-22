/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Picture;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.PictureDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Keep the tab bar in groups (#503). Facebook opens a group on a screen of its own, over the main
 * screen, and only the main screen has a tab bar, so a group shows none and the way to another tab
 * is Back. Pages, profiles and the rest open the same way; groups are the ones asked about.
 *
 * <p>With the switch on, a group's page gets a copy of the main screen's tab bar along its bottom,
 * placed the way the main screen has it (on 588 the bar runs down under the gesture bar, with its
 * own room for it), and the page ends where the copy starts. The copy is a recording of
 * the bar as the main screen last drew it, so it has the same tabs, the tab you came from marked, the
 * same badges and the same colours, and it's recorded again each time the group comes to the front.
 * Over each tab sits a button with that tab's own label. A tap closes the screens over the main
 * screen and taps the same tab there once it's back in front; the tab that was already showing is
 * just shown again, as it was. Back still goes back one screen.
 *
 * <p>Only while the main screen's bar is at the bottom; a bar Facebook keeps at the top scrolls with
 * the page instead. The page's bottom margin, which Facebook sets for the gesture bar, grows by the
 * copy's height, and is put back when the keyboard takes that room or the switch is off. Off, paused,
 * before the settings are ready, without a main screen, or on a throw, the group is as Facebook
 * shows it.
 */
public final class GroupTabBar {
    private GroupTabBar() {
    }

    /** The extra Facebook starts a group's page with: the group's id. */
    static final String GROUP_EXTRA = "group_feed_id";
    /** Facebook's tab bar, a class Redex keeps. */
    static final String TAB_BAR = TranslucentBars.TAB_BAR;
    /** How deep in a screen's views the tab bar is looked for. It's about a dozen down. */
    static final int TAB_BAR_LEVELS = 30;
    /** How long a tab tapped in a group waits for the main screen to come back. */
    static final long TAP_WAIT_MS = 3000;
    /** A margin past this share of the page's height is the keyboard's room, and the copy steps aside. */
    static final int KEYBOARD_SHARE = 4;
    /** Counted each time a group's page gets the tab bar. */
    static final String SHOWN = "Tab bar shown in a group";
    /** Counted each time a tab is tapped in a group. */
    static final String TAPPED = "Tab tapped in a group";
    private static final String FAMILY = FamilyNames.BOTTOM_TAB_BAR;
    private static final String WHAT = "tab bar in groups";

    /** Facebook's screens, oldest first, so a tab can close those over the main screen. Main thread. */
    private static final List<WeakReference<Activity>> screens = new ArrayList<>();
    private static WeakReference<Activity> mainScreen = new WeakReference<>(null);
    private static WeakReference<View> mainBar = new WeakReference<>(null);
    /** A tab tapped in a group, to tap on the main screen when it comes back, and when. */
    private static WeakReference<View> pendingTab = new WeakReference<>(null);
    private static long pendingAt;
    private static boolean watching;
    private static volatile boolean logged;

    /** Follows Facebook's screens. Called once the Application is created, with the tab bar patch in the build. */
    public static synchronized void watch(Context context) {
        if (watching || !(context instanceof Application)) return;
        ((Application) context).registerActivityLifecycleCallbacks(new Screens());
        watching = true;
    }

    /** The switch, on and ready. */
    static boolean on() {
        return Utils.settingsReady() && Settings.TAB_BAR_IN_GROUPS.get();
    }

    /** Whether [intent] opens a group's page. */
    static boolean opensGroup(@Nullable Intent intent) {
        return intent != null && intent.hasExtra(GROUP_EXTRA);
    }

    /** Forgets every screen and tap, for tests. */
    static synchronized void forget() {
        screens.clear();
        mainScreen = new WeakReference<>(null);
        mainBar = new WeakReference<>(null);
        pendingTab = new WeakReference<>(null);
        pendingAt = 0;
        watching = false;
        logged = false;
    }

    /** The tab bar under [view], looked for [levels] deep. */
    @Nullable
    static View tabBarIn(@Nullable View view, int levels) {
        if (view == null) return null;
        if (TAB_BAR.equals(view.getClass().getName())) return view;
        if (levels == 0 || !(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            View found = tabBarIn(group.getChildAt(index), levels - 1);
            if (found != null) return found;
        }
        return null;
    }

    /** Whether [bar] sits in the bottom half of its screen. */
    static boolean atBottom(View bar) {
        View root = bar.getRootView();
        if (root == null || root.getHeight() == 0) return false;
        int[] at = new int[2];
        bar.getLocationInWindow(at);
        return at[1] + bar.getHeight() / 2 > root.getHeight() / 2;
    }

    /**
     * The tabs in [bar], left to right: each clickable view that holds no clickable view of its own,
     * at least half the bar's height.
     */
    static List<View> tabsIn(View bar) {
        List<View> found = new ArrayList<>();
        collectTabs(bar, bar, found);
        Collections.sort(found, (a, b) -> Integer.compare(leftIn(bar, a), leftIn(bar, b)));
        return found;
    }

    private static boolean collectTabs(View bar, View view, List<View> found) {
        boolean holds = false;
        if (view instanceof ViewGroup && view.getVisibility() == View.VISIBLE) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                if (collectTabs(bar, group.getChildAt(index), found)) holds = true;
            }
        }
        if (view == bar || holds || view.getVisibility() != View.VISIBLE) return holds;
        if (view.isClickable() && view.getWidth() > 0 && view.getHeight() * 2 >= bar.getHeight()) {
            found.add(view);
            return true;
        }
        return false;
    }

    /** How far [view] is from [bar]'s left edge. */
    static int leftIn(View bar, View view) {
        int[] barAt = new int[2];
        int[] viewAt = new int[2];
        bar.getLocationInWindow(barAt);
        view.getLocationInWindow(viewAt);
        return viewAt[0] - barAt[0];
    }

    /** The main screen's tab bar, while it's still laid out. */
    @Nullable
    private static View liveMainBar() {
        View bar = mainBar.get();
        Activity main = mainScreen.get();
        if (bar != null && main != null && !main.isFinishing() && bar.isAttachedToWindow()
                && bar.getWidth() > 0 && bar.getHeight() > 0) {
            return bar;
        }
        // The main screen may have built its bar after it came to the front, or come back new.
        for (int index = screens.size() - 1; index >= 0; index--) {
            Activity screen = screens.get(index).get();
            if (screen == null || screen.isFinishing() || screen.getWindow() == null) continue;
            View found = tabBarIn(screen.getWindow().getDecorView(), TAB_BAR_LEVELS);
            if (found != null && found.getWidth() > 0 && found.getHeight() > 0) {
                mainScreen = new WeakReference<>(screen);
                mainBar = new WeakReference<>(found);
                return found;
            }
        }
        return null;
    }

    /** A screen came to the front: note the main screen, and give a group's page the bar. */
    static void resumed(Activity activity) {
        try {
            if (activity.getWindow() == null) return;
            View decor = activity.getWindow().getDecorView();
            View bar = tabBarIn(decor, TAB_BAR_LEVELS);
            if (bar != null) {
                mainScreen = new WeakReference<>(activity);
                mainBar = new WeakReference<>(bar);
                tapPending(bar);
                return;
            }
            if (!opensGroup(activity.getIntent())) return;
            HookStatus.invoked(FAMILY);
            ViewGroup content = activity.findViewById(android.R.id.content);
            // On the window's own root, over everything: 588 pads the page's frame for the gesture
            // bar, and a copy inside the frame couldn't reach down to it the way the bar does.
            if (content == null || !(decor instanceof FrameLayout)) return;
            ViewGroup holder = (ViewGroup) decor;
            Mirror shown = mirrorIn(holder);
            View main = on() ? liveMainBar() : null;
            Activity mainActivity = mainScreen.get();
            // A group opened from a notification can be in a task of its own, and closing that
            // task's screens for a tab would leave Facebook altogether.
            if (main == null || !atBottom(main) || mainActivity == null
                    || mainActivity.getTaskId() != activity.getTaskId()) {
                if (shown != null) shown.remove();
                return;
            }
            if (shown == null) {
                shown = new Mirror(activity, content, holder);
                holder.addView(shown, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, main.getHeight(), Gravity.BOTTOM));
                holder.getViewTreeObserver().addOnPreDrawListener(shown);
            }
            boolean copied = false;
            try {
                copied = shown.copy(main);
            } finally {
                if (!copied) shown.remove();
            }
            if (!copied) return;
            HookStatus.bound(FAMILY, WHAT);
            HookStatus.counted(FAMILY, SHOWN);
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Tab bar in groups: a group's page got the tab bar");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, WHAT, failure);
        }
    }

    /** The copy of the bar already on [holder], a window's root, if any. */
    @Nullable
    static Mirror mirrorIn(ViewGroup holder) {
        for (int index = 0; index < holder.getChildCount(); index++) {
            View child = holder.getChildAt(index);
            if (child instanceof Mirror) return (Mirror) child;
        }
        return null;
    }

    /**
     * A tab tapped in [group]'s copy of the bar: the screens over the main screen close, and [tab]
     * is tapped there once it's back, unless it's the tab that was showing.
     */
    static void tapped(Activity group, @Nullable View tab) {
        try {
            Activity main = mainScreen.get();
            if (tab == null || main == null || main.isFinishing()) return;
            HookStatus.counted(FAMILY, TAPPED);
            if (!tab.isSelected()) {
                pendingTab = new WeakReference<>(tab);
                pendingAt = SystemClock.elapsedRealtime();
            }
            int task = group.getTaskId();
            for (int index = screens.size() - 1; index >= 0; index--) {
                Activity screen = screens.get(index).get();
                if (screen == null || screen == main || screen.isFinishing() || screen.getTaskId() != task) continue;
                screen.finish();
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, WHAT, failure);
        }
    }

    /** The main screen is in front again: a tab tapped in a group gets its tap there. */
    private static void tapPending(View bar) {
        View tab = pendingTab.get();
        boolean fresh = SystemClock.elapsedRealtime() - pendingAt <= TAP_WAIT_MS;
        pendingTab = new WeakReference<>(null);
        if (tab == null || !fresh) return;
        // After this resume finishes, so Facebook's own fragments are in front again too.
        bar.post(() -> {
            try {
                tap(tab);
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, WHAT, failure);
            }
        });
    }

    /** Taps [tab] the way a finger would, or through its click listener when it has one. */
    static void tap(View tab) {
        if (tab.hasOnClickListeners()) {
            tab.performClick();
            return;
        }
        View root = tab.getRootView();
        int[] at = new int[2];
        tab.getLocationInWindow(at);
        float x = at[0] + tab.getWidth() / 2f;
        float y = at[1] + tab.getHeight() / 2f;
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0);
        try {
            root.dispatchTouchEvent(down);
            root.dispatchTouchEvent(up);
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    /**
     * The copy of the main screen's tab bar on a group's page: the bar's last drawing over its own
     * colour, and a button with each tab's label over each tab. Before each frame it keeps the page
     * clear of itself.
     */
    static final class Mirror extends FrameLayout implements ViewTreeObserver.OnPreDrawListener {
        private final WeakReference<Activity> screen;
        /** The page's frame, whose biggest child is the page. */
        private final ViewGroup content;
        /** The window's root, which holds the copy. */
        private final ViewGroup holder;
        /** The page's bottom margin as Facebook set it, and the margin this set, or -1 for none. */
        int facebooksMargin = -1;
        int setMargin = -1;
        /**
         * Whether the main screen's bar runs down to the bottom of its screen, under the gesture bar,
         * as it does on 588 with its own padding for it. The copy then does too, over the page's
         * strip for the gesture bar; otherwise it sits right above the room Facebook leaves there.
         */
        boolean toTheBottom;

        Mirror(Activity activity, ViewGroup content, ViewGroup holder) {
            super(activity);
            this.screen = new WeakReference<>(activity);
            this.content = content;
            this.holder = holder;
            setClickable(true);
        }

        /** Records [bar] and puts a button over each of its tabs. False when there's nothing to show. */
        boolean copy(View bar) {
            int width = bar.getWidth();
            int height = bar.getHeight();
            List<View> tabs = tabsIn(bar);
            if (tabs.isEmpty()) return false;
            Picture picture = new Picture();
            Canvas canvas = picture.beginRecording(width, height);
            try {
                bar.draw(canvas);
            } finally {
                picture.endRecording();
            }
            Drawable drawing = new PictureDrawable(picture);
            setBackground(new LayerDrawable(new Drawable[]{new ColorDrawable(colourOf(bar)), drawing}));
            // Where it shows: a bar slid away by scrolling is lower still, and still at the bottom.
            View root = bar.getRootView();
            int[] at = new int[2];
            bar.getLocationInWindow(at);
            int windowHeight = root == null ? 0 : root.getHeight();
            toTheBottom = windowHeight > 0 && at[1] + height >= windowHeight;
            Logger.printDebug(() -> "Tab bar in groups: copied a " + width + "x" + height + " tab bar at " + at[1]
                    + " in a window " + windowHeight + " tall, " + tabs.size() + " tabs"
                    + (toTheBottom ? ", down to the bottom" : ""));
            ViewGroup.LayoutParams own = getLayoutParams();
            if (own != null && own.height != height) {
                own.height = height;
                setLayoutParams(own);
            }
            removeAllViews();
            for (View tab : tabs) {
                View button = new View(getContext());
                button.setContentDescription(tab.getContentDescription());
                button.setSelected(tab.isSelected());
                button.setBackground(ripple(getContext()));
                WeakReference<View> held = new WeakReference<>(tab);
                button.setOnClickListener(v -> {
                    Activity activity = screen.get();
                    if (activity != null) tapped(activity, held.get());
                });
                // The page can be wider than the main screen was, after a turn of the phone.
                float scale = content.getWidth() > 0 ? (float) content.getWidth() / width : 1f;
                LayoutParams params = new LayoutParams(Math.round(tab.getWidth() * scale), LayoutParams.MATCH_PARENT);
                params.leftMargin = Math.round(leftIn(bar, tab) * scale);
                addView(button, params);
            }
            return true;
        }

        /** Takes the copy off the page and gives the page its margin back. */
        void remove() {
            holder.getViewTreeObserver().removeOnPreDrawListener(this);
            View page = pageIn(content, this);
            if (page != null && setMargin >= 0
                    && page.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) page.getLayoutParams();
                if (margins.bottomMargin == setMargin) {
                    margins.bottomMargin = facebooksMargin;
                    page.setLayoutParams(margins);
                }
            }
            holder.removeView(this);
        }

        @Override
        public boolean onPreDraw() {
            try {
                fit();
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, WHAT, failure);
            }
            return true;
        }

        /**
         * Keeps the page's bottom clear of the copy, and the copy where the main screen has its bar:
         * down to the bottom, or right above the room Facebook leaves under the page.
         */
        void fit() {
            View page = pageIn(content, this);
            if (page == null || !(page.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
            if (holder.getHeight() == 0 || content.getHeight() == 0) return;
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) page.getLayoutParams();
            // A margin this didn't set is Facebook's own.
            int facebooks = margins.bottomMargin == setMargin && setMargin >= 0 ? facebooksMargin : margins.bottomMargin;
            // The room Facebook leaves under the page, by its margin or its frame's padding: the
            // gesture bar's, or the keyboard's.
            int[] holderAt = new int[2];
            int[] contentAt = new int[2];
            holder.getLocationInWindow(holderAt);
            content.getLocationInWindow(contentAt);
            int contentBottom = contentAt[1] - holderAt[1] + content.getHeight();
            int room = holder.getHeight() - (contentBottom - content.getPaddingBottom() - facebooks);
            boolean keyboard = room * KEYBOARD_SHARE > holder.getHeight();
            int below = toTheBottom ? 0 : Math.max(0, room);
            int want = keyboard ? facebooks : facebooks + Math.max(0, below + getLayoutParams().height - room);
            if (margins.bottomMargin != want) {
                margins.bottomMargin = want;
                page.setLayoutParams(margins);
            }
            facebooksMargin = facebooks;
            setMargin = want;
            int visibility = keyboard ? GONE : VISIBLE;
            if (getVisibility() != visibility) setVisibility(visibility);
            LayoutParams own = (LayoutParams) getLayoutParams();
            int ownMargin = Math.max(0, below - holder.getPaddingBottom());
            if (own.bottomMargin != ownMargin) {
                own.bottomMargin = ownMargin;
                setLayoutParams(own);
            }
        }

        /** The page Facebook put in [content]: its tallest view other than [mirror]. */
        @Nullable
        static View pageIn(ViewGroup content, View mirror) {
            View page = null;
            for (int index = 0; index < content.getChildCount(); index++) {
                View child = content.getChildAt(index);
                if (child == mirror || child.getVisibility() == GONE) continue;
                if (page == null || child.getHeight() > page.getHeight()) page = child;
            }
            return page;
        }

        /** The bar's own colour, made solid, or the screen's background colour when the bar has none. */
        static int colourOf(View bar) {
            for (View at = bar; at != null; ) {
                Drawable background = at.getBackground();
                if (background instanceof ColorDrawable && Color.alpha(((ColorDrawable) background).getColor()) > 0) {
                    return ((ColorDrawable) background).getColor() | 0xFF000000;
                }
                Object parent = at.getParent();
                at = parent instanceof View ? (View) parent : null;
            }
            TypedArray values = bar.getContext().obtainStyledAttributes(new int[]{android.R.attr.colorBackground});
            try {
                return values.getColor(0, Color.WHITE) | 0xFF000000;
            } finally {
                values.recycle();
            }
        }

        @Nullable
        private static Drawable ripple(Context context) {
            TypedArray values = context.obtainStyledAttributes(new int[]{android.R.attr.selectableItemBackgroundBorderless});
            try {
                return values.getDrawable(0);
            } finally {
                values.recycle();
            }
        }
    }

    private static final class Screens implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) {
            screens.add(new WeakReference<>(activity));
        }

        @Override
        public void onActivityResumed(@NonNull Activity activity) {
            resumed(activity);
        }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {
            for (int index = screens.size() - 1; index >= 0; index--) {
                Activity screen = screens.get(index).get();
                if (screen == null || screen == activity) screens.remove(index);
            }
        }

        @Override
        public void onActivityStarted(@NonNull Activity activity) {
        }

        @Override
        public void onActivityPaused(@NonNull Activity activity) {
        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) {
        }
    }
}
