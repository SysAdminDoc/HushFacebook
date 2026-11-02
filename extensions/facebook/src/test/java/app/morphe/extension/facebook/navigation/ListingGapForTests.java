/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;

/** Remove the gap above Marketplace listings, asked from tests in other packages. */
public final class ListingGapForTests {
    /** Where the page starts below the top of the window, as a listing does on Android 17. */
    static final int PAGE_TOP = 136;

    /** A React Native root view's tag, and the tag of the first view JavaScript made in it. */
    static final int ROOT_TAG = 11;
    static final int CHILD_TAG = 412;

    private ListingGapForTests() {
    }

    /** androidx's Insets as the extension reads them: four public sides and a static of. */
    public static final class Insets {
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        private Insets(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public static Insets of(int left, int top, int right, int bottom) {
            return new Insets(left, top, right, bottom);
        }
    }

    /**
     * A shown activity whose content is a page [pageTop] below its content frame, holding a React
     * Native root with one child, laid out.
     */
    static ActivityController<Activity> listing(int pageTop, int rootTag) {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        Activity activity = controller.get();
        FrameLayout page = new FrameLayout(activity);
        FrameLayout.LayoutParams below = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        below.topMargin = pageTop;
        FrameLayout root = new FrameLayout(activity);
        root.setId(rootTag);
        View child = new View(activity);
        child.setId(CHILD_TAG);
        root.addView(child, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        page.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        activity.setContentView(page, below);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return controller;
    }

    /** The root view in [controller]'s listing. */
    static View root(ActivityController<Activity> controller) {
        return controller.get().getWindow().getDecorView().findViewById(ROOT_TAG);
    }

    /** Whether a listing below the status bar is handed less than the window's top inset. */
    public static boolean trimsAListing() {
        try (ActivityController<Activity> controller = listing(PAGE_TOP, ROOT_TAG)) {
            View decor = controller.get().getWindow().getDecorView();
            int[] at = new int[2];
            root(controller).getLocationInWindow(at);
            Insets whole = Insets.of(0, at[1], 0, 0);
            return ListingGap.insets(whole, decor) != whole;
        }
    }
}
