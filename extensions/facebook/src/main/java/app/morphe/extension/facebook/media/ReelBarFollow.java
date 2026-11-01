/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Lets a kept reel bar and its time travel with the reel while a finger drags the reel away (#120).
 * Facebook has one scrubber for the whole Reels viewer, in a host beside the list of reels, not in
 * a reel's page. Seen mid-drag on the S22 with 585 (2026-11-01):
 *
 * <pre>
 *   LithoView 0,0-1080,2041
 *     X.01UW 0,0-1080,2041
 *       X.038A 0,0-1080,2041            the list of reels
 *         LithoView 0,-416-1080,1625    the reel leaving
 *         LithoView 0,1625-1080,3666    the reel coming
 *     X.0Az0 0,986-1080,2066            the scrubber's host, which stays put
 *       X.0iF4 0,0-1080,1080
 *         X.02DN 0,918-291,1080         the time
 *         X.0AyQ 0,984-1080,1080        the bar
 * </pre>
 *
 * <p>So the caption and buttons left with the reel while the bar and time stayed on screen over the
 * next one. Each frame this moves the host by as much as the page the bar belongs to has moved. Its
 * values turn into the next reel's when Facebook hands the scrubber over, partway through a drag,
 * and from then on it rides on the page coming in, which brings it into place.
 *
 * <p>Only while Keep the progress bar is on. Otherwise the bar isn't up during a drag anyway, and
 * the host goes back to where Facebook has it.
 *
 * <p>The same move lifts the bar when its line would sit behind the tab bar (#282). One reporter's
 * Reels tab runs the whole reel about 60 px lower than ours, so Facebook's own line and the kept
 * bar's thumb both went under the tabs. On the S22 and the emulator the line clears the tab bar by
 * 8 dp or more, so nothing moves there.
 */
final class ReelBarFollow {
    private static final String FAMILY = FamilyNames.PROGRESS_BAR;
    private static final String HOOK = "reel bar follow";
    /** A reel's page is a plain LithoView, whose name Redex keeps. */
    private static final String PAGE = "com.facebook.litho.LithoView";
    static final String FOLLOWED = "reel bar followed its reel";
    static final String LIFTED = "reel bar lifted above the tab bar";
    private static final String TAB_BAR = "com.facebook.navigation.tabbar.ui.TabBarContainerLayout";
    /** How far above the tab bar the line has to sit for the whole thumb to show. */
    private static final int LINE_CLEARANCE_DP = 6;
    /** How deep under the window's root to look for the tab bar. */
    private static final int TAB_BAR_LEVELS = 20;
    /** The host is two levels above the time label on 585. */
    private static final int LEVELS_UP = 4;
    /** The list is one level inside the host's sibling on 585. */
    private static final int LEVELS_DOWN = 3;

    /** The labels being followed, the follows holding nothing but weak references. */
    private static final Map<View, Follow> follows = new WeakHashMap<>();
    /** Labels with no list of reels beside them, as under a feed video, so an update doesn't look again. */
    private static final Set<View> noReels = Collections.newSetFromMap(new WeakHashMap<>());

    private ReelBarFollow() {
    }

    /** As Facebook mounts a scrubber, which may be in another viewer by now: look again if nothing matched. */
    static void mounted(@Nullable View label) {
        if (label == null) return;
        noReels.remove(label);
        updated(label);
    }

    /** From each drawing update of the scrubber, many times a second, so anything already looked at ends here. */
    static void updated(@Nullable View label) {
        try {
            if (label == null || follows.containsKey(label) || noReels.contains(label)) return;
            if (!keeps() || !label.isAttachedToWindow()) return;
            start(label);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
        }
    }

    /** Facebook gave the scrubber to another reel, so the bar belongs to the other page now. */
    static void handedOver(@Nullable View label) {
        if (label == null) return;
        Follow follow = follows.get(label);
        if (follow != null) follow.handed = true;
    }

    static void resetForTests() {
        follows.clear();
        noReels.clear();
    }

    private static boolean keeps() {
        return Utils.settingsReady() && Settings.KEEP_PROGRESS_BAR.get();
    }

    /** Walks up from the label to the view with a list of reels beside it, the one to move. */
    private static void start(View label) {
        View up = label;
        for (int level = 0; level < LEVELS_UP; level++) {
            ViewParent above = up.getParent();
            if (!(above instanceof ViewGroup)) break;
            ViewGroup parent = (ViewGroup) above;
            for (int index = 0; index < parent.getChildCount(); index++) {
                View beside = parent.getChildAt(index);
                if (beside == up) continue;
                ViewGroup pager = pagerIn(beside, LEVELS_DOWN);
                if (pager != null) {
                    Follow follow = new Follow(label, up, pager);
                    follows.put(label, follow);
                    follow.tree.addOnPreDrawListener(follow);
                    HookStatus.bound(FAMILY, HOOK);
                    Logger.printDebug(() -> "Reel bar: following the reel beside " + pager.getClass().getName());
                    return;
                }
            }
            up = parent;
        }
        noReels.add(label);
    }

    @Nullable
    private static ViewGroup pagerIn(View view, int levels) {
        if (!(view instanceof ViewGroup) || view.getVisibility() != View.VISIBLE) return null;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            if (isPage(group.getChildAt(index), group)) return group;
        }
        if (levels == 0) return null;
        for (int index = 0; index < group.getChildCount(); index++) {
            ViewGroup pager = pagerIn(group.getChildAt(index), levels - 1);
            if (pager != null) return pager;
        }
        return null;
    }

    /** A feed's rows are shorter than their list, so only a viewer of whole-screen pages matches. */
    private static boolean isPage(View child, ViewGroup group) {
        return child.getVisibility() == View.VISIBLE
                && PAGE.equals(child.getClass().getName())
                && child.getWidth() > 0
                && child.getWidth() == group.getWidth()
                && child.getHeight() == group.getHeight();
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

    /** The scrubber's line, the full-width view beside the time. */
    @Nullable
    private static View barBeside(View label) {
        ViewParent above = label.getParent();
        if (!(above instanceof ViewGroup)) return null;
        ViewGroup plugin = (ViewGroup) above;
        View lowest = null;
        for (int index = 0; index < plugin.getChildCount(); index++) {
            View child = plugin.getChildAt(index);
            if (child == label || child.getWidth() != plugin.getWidth() || child.getHeight() >= plugin.getHeight()) continue;
            if (lowest == null || child.getBottom() > lowest.getBottom()) lowest = child;
        }
        return lowest;
    }

    private static final class Follow implements ViewTreeObserver.OnPreDrawListener {
        private final WeakReference<View> heldLabel;
        private final WeakReference<View> heldMover;
        private final WeakReference<ViewGroup> heldPager;
        final ViewTreeObserver tree;
        @Nullable
        private final WeakReference<View> heldTabBar;
        @Nullable
        private WeakReference<View> heldPage;
        /** What this added to the host's own translation, which Facebook may set as well. */
        private int applied;
        private int lastFollow;
        private int lastLift;
        boolean handed;
        private boolean stopped;

        Follow(View label, View mover, ViewGroup pager) {
            heldLabel = new WeakReference<>(label);
            heldMover = new WeakReference<>(mover);
            heldPager = new WeakReference<>(pager);
            tree = pager.getViewTreeObserver();
            View tabBar = tabBarIn(label.getRootView(), TAB_BAR_LEVELS);
            heldTabBar = tabBar == null ? null : new WeakReference<>(tabBar);
        }

        @Override
        public boolean onPreDraw() {
            try {
                if (!stopped) place();
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, HOOK, failure);
            }
            return true;
        }

        private void place() {
            View mover = heldMover.get();
            ViewGroup pager = heldPager.get();
            if (mover == null || pager == null || !pager.isAttachedToWindow()) {
                stop(mover);
                return;
            }
            boolean keeping = keeps();
            int follow = keeping ? offsetIn(pager) : 0;
            int lift = keeping ? liftOver() : 0;
            if (follow != 0 && lastFollow == 0) HookStatus.counted(FAMILY, FOLLOWED);
            if (lift != 0 && lastLift == 0) HookStatus.counted(FAMILY, LIFTED);
            lastFollow = follow;
            lastLift = lift;
            int offset = follow - lift;
            if (offset == applied) return;
            mover.setTranslationY(mover.getTranslationY() - applied + offset);
            applied = offset;
        }

        /** How far the line, where it would be without this class's move, has to rise to clear the tab bar. */
        private int liftOver() {
            View tabBar = heldTabBar == null ? null : heldTabBar.get();
            View label = heldLabel.get();
            if (tabBar == null || label == null || !tabBar.isShown()) return 0;
            View bar = barBeside(label);
            if (bar == null) return 0;
            int[] at = new int[2];
            tabBar.getLocationInWindow(at);
            int tabTop = at[1];
            if (tabTop < tabBar.getRootView().getHeight() / 2) return 0;
            bar.getLocationInWindow(at);
            int line = at[1] + bar.getHeight() / 2 - applied;
            int clearance = Math.round(LINE_CLEARANCE_DP * bar.getResources().getDisplayMetrics().density);
            return Math.max(0, line + clearance - tabTop);
        }

        /** How far the page the bar belongs to is from its place, and 0 once a reel has settled. */
        private int offsetIn(ViewGroup pager) {
            View followed = heldPage == null ? null : heldPage.get();
            boolean followedThere = false;
            View nearest = null;
            for (int index = 0; index < pager.getChildCount(); index++) {
                View child = pager.getChildAt(index);
                if (!isPage(child, pager)) continue;
                if (child.getTop() == 0) {
                    heldPage = new WeakReference<>(child);
                    handed = false;
                    return 0;
                }
                if (child == followed) {
                    followedThere = true;
                    if (handed) continue;
                }
                if (nearest == null || Math.abs(child.getTop()) < Math.abs(nearest.getTop())) nearest = child;
            }
            if (handed || !followedThere) {
                if (nearest != null) {
                    followed = nearest;
                    heldPage = new WeakReference<>(followed);
                    handed = false;
                } else if (!followedThere) {
                    return 0;
                }
            }
            return followed == null ? 0 : followed.getTop();
        }

        /** Gives up on a viewer that's gone, so the label's next update looks again. */
        private void stop(@Nullable View mover) {
            stopped = true;
            if (mover != null && applied != 0) mover.setTranslationY(mover.getTranslationY() - applied);
            applied = 0;
            if (tree.isAlive()) tree.removeOnPreDrawListener(this);
            View label = heldLabel.get();
            if (label != null) follows.remove(label);
        }
    }
}
