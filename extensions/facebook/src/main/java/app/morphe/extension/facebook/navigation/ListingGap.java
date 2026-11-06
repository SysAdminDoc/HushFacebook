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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Remove the gap above Marketplace listings (#333). A listing opens in Facebook's immersive React
 * Native screen, whose header pads its top by the safe area Facebook's SafeArea module reports. The
 * module reads the window's insets off the decor view and hands them over whole, as if the page
 * started at the top of the window. On Android 17 the screen's native container already sits below
 * the status bar, so the top is padded twice, and an empty band as tall as the status bar shows
 * above the listing's close and share buttons. Stock Facebook does the same.
 *
 * <p>The module turns insets into the map JavaScript reads in one static method, for both the
 * value a page asks for and the change it is told about later. Before each, the insets come here
 * with the decor view they were read from, and each side keeps only the part that overlaps the
 * React Native root view: the top less how far the root sits below the window's top, the bottom
 * less how far it ends above the window's bottom, and so on, never below zero. A page drawn edge
 * to edge gets the same answer as before, so only pages Facebook already moved clear of a bar
 * change.
 *
 * <p>The root is found by its id. React Native gives a root view its root tag, which ends in 1
 * (1, 11, 21 and so on), and the views JavaScript makes inside it ids that don't. A listing opened
 * from the Marketplace grid asks for its insets before its root has children, and its header never
 * asks again, so until the root is placed the page is measured by the native frame it will fill:
 * the room inside the deepest laid out view the content frame reaches through views with one showing
 * child each, less the margins of a child still waiting for its first layout. The decor is also
 * asked to hand the insets out again after the next layout that places the root, which makes the
 * module send the page the root's answer. With neither laid out the insets go out whole, and that's
 * counted, since a page that kept that answer would show the band. The insets are androidx's, which
 * the extension doesn't compile against, so they're read and made by name. Nothing here may throw
 * into Facebook: the module is asked from the JavaScript thread as well as the main one.
 *
 * <p>React Native's own SafeAreaView doesn't ask the module. It reads the insets Android hands the
 * view and pads what it holds by them, so a page laid out with one gets the band back even with the
 * module's answer trimmed. Its insets come here too, with the view, and are trimmed the same way to
 * the root the view sits in.
 */
public final class ListingGap {
    /** Counted under the patch's name each time a page is handed less than the window's insets. */
    static final String TRIMMED = "safe area trimmed to the page";
    /** Counted each time a SafeAreaView is handed less than the window's insets. */
    static final String VIEW_TRIMMED = "safe area view trimmed to the page";
    /** Counted each time a page asks before anything it will fill is laid out. */
    static final String UNMEASURED = "asked before the page had a frame";

    private static final String FAMILY = FamilyNames.LISTING_GAP;
    private static final String ANCHOR = "safe area insets";
    private static final String VIEW_ANCHOR = "safe area view";

    /** Layouts to wait for a root to appear before the decor stops being watched. */
    static final int LAYOUTS_TO_WAIT = 20;

    /** The highest id a view can have without being one of an app's or Android's resources. */
    private static final int RESOURCE_IDS = 0x01000000;

    /** Decor views already waiting for a layout that places their root. */
    private static final Map<View, Boolean> waiting = Collections.synchronizedMap(new WeakHashMap<>());

    @Nullable private static volatile Class<?> insetsClass;
    @Nullable private static volatile Field[] sides;
    @Nullable private static volatile Method insetsOf;

    private ListingGap() {
    }

    /**
     * Injection point, before each call that turns [insets], androidx Insets read off [decor]'s
     * window insets, into the safe area map: the insets to use instead, [insets] itself when nothing
     * changes.
     */
    public static Object insets(@Nullable Object insets, @Nullable Object decor) {
        if (insets == null || !(decor instanceof View)) return insets;
        return answer(insets, (View) decor, null, ANCHOR, TRIMMED);
    }

    /**
     * Injection point, in the insets listener of React Native's SafeAreaView, after it reads [insets],
     * androidx Insets, and casts [view], the SafeAreaView: the insets the view pads by instead,
     * [insets] itself when nothing changes.
     */
    public static Object viewInsets(@Nullable Object insets, @Nullable Object view) {
        if (insets == null || !(view instanceof View)) return insets;
        View safeArea = (View) view;
        return answer(insets, safeArea.getRootView(), safeArea, VIEW_ANCHOR, VIEW_TRIMMED);
    }

    /**
     * [insets] trimmed to the page in [window]: the root [inside] sits in, or with no [inside] the
     * window's first root, or before that root is placed the frame it will fill.
     */
    private static Object answer(Object insets, View window, @Nullable View inside, String anchor, String counted) {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.REMOVE_LISTING_GAP.get()) return insets;
            String what = inside == null ? "safe area" : "safe area view";
            View root = inside == null ? reactRoot(window) : rootAbove(inside);
            int[] page;
            String measured;
            if (root != null && root.isLaidOut()) {
                int[] at = new int[2];
                root.getLocationInWindow(at);
                page = new int[] {at[0], at[1], at[0] + root.getWidth(), at[1] + root.getHeight()};
                measured = "root";
            } else {
                askAgainAfterLayout(window);
                page = frameOf(window);
                measured = "frame";
                if (page == null) {
                    HookStatus.counted(FAMILY, UNMEASURED);
                    Logger.printDebug(() -> "Remove the gap above Marketplace listings: " + what
                            + " asked for before the page had a frame, so it goes out whole");
                    return insets;
                }
            }
            HookStatus.bound(FAMILY, anchor);
            Field[] fields = sidesOf(insets.getClass());
            int[] whole = new int[4];
            for (int side = 0; side < 4; side++) whole[side] = fields[side].getInt(insets);
            int[] trimmed = trim(whole, page[0], page[1], page[2], page[3], window.getWidth(), window.getHeight());
            Logger.printDebug(() -> "Remove the gap above Marketplace listings: " + what + " " + Arrays.toString(whole)
                    + " to " + Arrays.toString(trimmed) + " for a page " + measured + " at " + page[0] + "," + page[1]);
            if (Arrays.equals(whole, trimmed)) return insets;
            Object answer = insetsOf.invoke(null, trimmed[0], trimmed[1], trimmed[2], trimmed[3]);
            HookStatus.counted(FAMILY, counted);
            return answer;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, anchor, failure);
            return insets;
        }
    }

    /**
     * Each side of [insets], left, top, right and bottom, less the room between the root's edge and
     * the window's on that side, never below zero.
     */
    static int[] trim(int[] insets, int rootLeft, int rootTop, int rootRight, int rootBottom, int width, int height) {
        return new int[] {
                Math.max(0, insets[0] - Math.max(0, rootLeft)),
                Math.max(0, insets[1] - Math.max(0, rootTop)),
                Math.max(0, insets[2] - Math.max(0, width - rootRight)),
                Math.max(0, insets[3] - Math.max(0, height - rootBottom)),
        };
    }

    /** The first showing React Native root view under [decor], breadth first, or null. */
    @Nullable
    static View reactRoot(View decor) {
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(decor);
        while (!queue.isEmpty()) {
            View view = queue.poll();
            if (view.getVisibility() != View.VISIBLE) continue;
            if (isReactRoot(view)) return view;
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View child = group.getChildAt(i);
                    if (child != null) queue.add(child);
                }
            }
        }
        return null;
    }

    /** The React Native root view [view] sits in: its nearest ancestor that is one, or null. */
    @Nullable
    static View rootAbove(View view) {
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (isReactRoot((View) parent)) return (View) parent;
        }
        return null;
    }

    /**
     * Where a page whose root isn't placed yet will sit, as left, top, right and bottom in [decor]'s
     * window, or null when nothing is laid out. The walk starts at the content frame and goes down
     * through view groups with exactly one showing child, to the deepest laid out one, and takes the
     * room inside its padding. A showing child that isn't laid out yet, as Facebook's page container
     * is when a listing first asks, also takes its margins off. The page lands inside that room, so it
     * never trims more than the root would.
     */
    @Nullable
    static int[] frameOf(View decor) {
        View content = decor.findViewById(android.R.id.content);
        View view = content != null ? content : decor;
        if (!view.isLaidOut()) return null;
        View unplaced = null;
        while (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            View only = null;
            int showing = 0;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child != null && child.getVisibility() == View.VISIBLE) {
                    showing++;
                    only = child;
                }
            }
            if (showing != 1) break;
            if (!only.isLaidOut()) {
                unplaced = only;
                break;
            }
            view = only;
        }
        int[] at = new int[2];
        view.getLocationInWindow(at);
        int[] room = {at[0] + view.getPaddingLeft(), at[1] + view.getPaddingTop(),
                at[0] + view.getWidth() - view.getPaddingRight(), at[1] + view.getHeight() - view.getPaddingBottom()};
        if (unplaced != null && unplaced.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) unplaced.getLayoutParams();
            room[0] += Math.max(0, margins.leftMargin);
            room[1] += Math.max(0, margins.topMargin);
            room[2] -= Math.max(0, margins.rightMargin);
            room[3] -= Math.max(0, margins.bottomMargin);
        }
        View stop = view;
        boolean waiting = unplaced != null;
        Logger.printDebug(() -> "Remove the gap above Marketplace listings: frame " + stop.getClass().getName()
                + " id " + Integer.toHexString(stop.getId()) + (waiting ? " with an unplaced child" : "")
                + ", room " + Arrays.toString(room));
        return room;
    }

    /** A view group with a root tag for its id whose first child has a React Native tag that isn't one. */
    static boolean isReactRoot(View view) {
        if (!(view instanceof ViewGroup) || !isRootTag(view.getId())) return false;
        ViewGroup group = (ViewGroup) view;
        if (group.getChildCount() == 0) return false;
        View first = group.getChildAt(0);
        return first != null && isReactTag(first.getId()) && !isRootTag(first.getId());
    }

    private static boolean isReactTag(int id) {
        return id > 0 && id < RESOURCE_IDS;
    }

    private static boolean isRootTag(int id) {
        return isReactTag(id) && id % 10 == 1;
    }

    /** The left, top, right and bottom fields of the insets class, found once, with its of(int, int, int, int). */
    private static Field[] sidesOf(Class<?> type) throws ReflectiveOperationException {
        Field[] found = sides;
        if (found != null && insetsClass == type) return found;
        found = new Field[] {type.getField("left"), type.getField("top"), type.getField("right"),
                type.getField("bottom")};
        insetsOf = type.getMethod("of", int.class, int.class, int.class, int.class);
        sides = found;
        insetsClass = type;
        return found;
    }

    /**
     * Watches [window]'s layouts, from the main thread, until one finds its root laid out, then asks
     * it to hand out its insets again. Gives up after {@link #LAYOUTS_TO_WAIT} layouts without one.
     */
    private static void askAgainAfterLayout(View window) {
        if (waiting.put(window, Boolean.TRUE) != null) return;
        Utils.runOnMainThreadNowOrLater(() -> {
            try {
                window.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                    private int layouts;

                    @Override
                    public void onGlobalLayout() {
                        try {
                            View root = reactRoot(window);
                            boolean placed = root != null && root.isLaidOut();
                            if (!placed && ++layouts < LAYOUTS_TO_WAIT) return;
                            window.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            waiting.remove(window);
                            if (placed) window.requestApplyInsets();
                        } catch (Throwable failure) {
                            waiting.remove(window);
                            HookStatus.threw(FAMILY, ANCHOR, failure);
                        }
                    }
                });
            } catch (Throwable failure) {
                waiting.remove(window);
                HookStatus.threw(FAMILY, ANCHOR, failure);
            }
        });
    }
}
