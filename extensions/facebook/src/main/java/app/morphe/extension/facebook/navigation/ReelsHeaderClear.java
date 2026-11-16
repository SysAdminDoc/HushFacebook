/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;

/**
 * Keeps the Reels tab's header, its "Reels" title with the search and profile buttons, below a tab
 * bar at the top (#390). With the tabs at the top the reel runs under the bar ({@link
 * TabBarScrollAway#pagesTop}), and the header, which Facebook places by the same padding, went up
 * under the bar with it and showed through a see-through bar. Each frame this measures where the
 * tab bar ends and pushes the header down to there by its top margin, so the reel still fills the
 * screen behind the bar. A tab bar at the bottom, hidden, or already above the header leaves the
 * header where Facebook put it, and so does Hush while it's paused.
 */
public final class ReelsHeaderClear implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
    private static final String FAMILY = FamilyNames.BOTTOM_TAB_BAR;
    static final String HOOK = "Reels header below a top tab bar";
    /** Counted each time the header is moved down to clear the tab bar. */
    static final String CLEARED = "Reels header kept below the top tab bar";
    private static final String TAB_BAR = "com.facebook.navigation.tabbar.ui.TabBarContainerLayout";
    /** How deep under the window's root to look for the tab bar. */
    private static final int TAB_BAR_LEVELS = 20;

    /** The headers being kept clear, each with its listener, which holds the header weakly. */
    private static final Map<View, ReelsHeaderClear> held = new WeakHashMap<>();

    private final WeakReference<View> header;
    @Nullable
    private ViewTreeObserver tree;
    @Nullable
    private WeakReference<View> heldTabBar;
    /** Whether the window was searched for a tab bar since the header was attached. */
    private boolean searched;
    /** What this added to the header's top margin, which Facebook may set as well. */
    private int added;
    private final int[] at = new int[2];

    private ReelsHeaderClear(View header) {
        this.header = new WeakReference<>(header);
    }

    /** The hook right after VideoHomeRootFragment stores the Reels header it found. Never throws. */
    public static void headerMade(@Nullable View header) {
        try {
            if (header == null) return;
            HookStatus.invoked(FAMILY);
            ReelsHeaderClear clear;
            synchronized (held) {
                if (held.containsKey(header)) return;
                clear = new ReelsHeaderClear(header);
                held.put(header, clear);
            }
            header.addOnAttachStateChangeListener(clear);
            if (header.isAttachedToWindow()) clear.onViewAttachedToWindow(header);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
        }
    }

    static void resetForTests() {
        synchronized (held) {
            held.clear();
        }
    }

    @Override
    public void onViewAttachedToWindow(View view) {
        stop();
        searched = false;
        tree = view.getViewTreeObserver();
        tree.addOnPreDrawListener(this);
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
        stop();
    }

    private void stop() {
        if (tree != null && tree.isAlive()) tree.removeOnPreDrawListener(this);
        tree = null;
    }

    @Override
    public boolean onPreDraw() {
        try {
            View view = header.get();
            if (view == null) {
                stop();
                return true;
            }
            if (!(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return true;
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            int wanted = wanted(view, params);
            if (wanted == added) return true;
            params.topMargin += wanted - added;
            added = wanted;
            view.setLayoutParams(params);
            if (wanted > 0) {
                HookStatus.bound(FAMILY, HOOK);
                HookStatus.counted(FAMILY, CLEARED);
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
            return true;
        }
    }

    /** How far below where Facebook puts it the header has to go to clear a tab bar at the top: 0 when it's clear already. */
    private int wanted(View view, ViewGroup.MarginLayoutParams params) {
        if (Setting.isPaused()) return 0;
        ViewParent parent = view.getParent();
        if (!(parent instanceof View)) return 0;
        View tabBar = tabBar(view);
        if (tabBar == null || !tabBar.isShown() || tabBar.getAlpha() == 0f) return 0;
        tabBar.getLocationInWindow(at);
        int tabTop = at[1];
        if (tabTop >= view.getRootView().getHeight() / 2) return 0;
        int tabBottom = tabTop + tabBar.getHeight();
        View page = (View) parent;
        page.getLocationInWindow(at);
        int natural = at[1] + page.getPaddingTop() + params.topMargin - added;
        return Math.max(0, tabBottom - natural);
    }

    /** The tab bar in the header's window, looked for again once it leaves; a window without one is searched once per attach. */
    @Nullable
    private View tabBar(View view) {
        View tabBar = heldTabBar == null ? null : heldTabBar.get();
        if (tabBar != null && tabBar.isAttachedToWindow()) return tabBar;
        if (searched && tabBar == null) return null;
        searched = true;
        tabBar = tabBarIn(view.getRootView(), TAB_BAR_LEVELS);
        heldTabBar = tabBar == null ? null : new WeakReference<>(tabBar);
        return tabBar;
    }

    @Nullable
    private static View tabBarIn(View view, int levels) {
        if (TAB_BAR.equals(view.getClass().getName())) return view;
        if (levels == 0 || !(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            View found = tabBarIn(group.getChildAt(index), levels - 1);
            if (found != null) return found;
        }
        return null;
    }
}
