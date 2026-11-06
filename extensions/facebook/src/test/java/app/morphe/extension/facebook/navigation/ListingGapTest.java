/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;

import app.morphe.extension.facebook.navigation.ListingGapForTests.Insets;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Remove the gap above Marketplace listings: each side of the safe area keeps only what overlaps
 * the React Native root, so a listing that starts below the status bar gets no top inset and a page
 * drawn edge to edge keeps them all. Before the root is placed, the frame the page fills stands in for
 * it. Off, paused, or with nothing laid out, the insets go to Facebook as they came, and an ask with
 * nothing laid out is counted. React Native's SafeAreaView gets the same trim to the root it sits in.
 */
@RunWith(RobolectricTestRunner.class)
public class ListingGapTest {
    private static final HushfacebookPause.Reason[] PAUSES = {
            HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
            HushfacebookPause.Reason.MARKER_FILE};

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.REMOVE_LISTING_GAP.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.LISTING_GAP + ":")) return line;
        }
        return null;
    }

    @Test
    public void eachSideKeepsOnlyWhatOverlapsThePage() {
        int[] window = {0, 136, 0, 63};
        // The listing on Android 17: below the status bar, ending above the navigation bar.
        assertArrayEquals(new int[] {0, 0, 0, 0}, ListingGap.trim(window, 0, 136, 1080, 2337, 1080, 2400));
        // Drawn edge to edge, as on the S22: nothing changes.
        assertArrayEquals(window, ListingGap.trim(window, 0, 0, 1080, 2400, 1080, 2400));
        // Partly under the status bar: the part that overlaps stays.
        assertArrayEquals(new int[] {0, 86, 0, 13}, ListingGap.trim(window, 0, 50, 1080, 2350, 1080, 2400));
        // A root past the window's edges never adds to an inset.
        assertArrayEquals(window, ListingGap.trim(window, -20, -40, 1100, 2450, 1080, 2400));
        int[] sides = {48, 0, 32, 0};
        assertArrayEquals(new int[] {0, 0, 2, 0}, ListingGap.trim(sides, 60, 0, 1050, 2400, 1080, 2400));
    }

    @Test
    public void aRootIsAViewGroupWithARootTagAndAReactChild() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        assertTrue(ListingGap.isReactRoot(group(context, 11, 412)));
        assertTrue(ListingGap.isReactRoot(group(context, 1, 2)));
        assertFalse("a child with a root tag", ListingGap.isReactRoot(group(context, 11, 21)));
        assertFalse("an even id", ListingGap.isReactRoot(group(context, 12, 412)));
        assertFalse("a resource id", ListingGap.isReactRoot(group(context, 0x7f0a1401, 412)));
        assertFalse("a child with a resource id", ListingGap.isReactRoot(group(context, 11, 0x7f0a1401)));
        assertFalse("no children", ListingGap.isReactRoot(new FrameLayout(context) {{ setId(11); }}));
        View lone = new View(context);
        lone.setId(11);
        assertFalse("not a view group", ListingGap.isReactRoot(lone));
    }

    private static FrameLayout group(android.content.Context context, int id, int childId) {
        FrameLayout group = new FrameLayout(context);
        group.setId(id);
        View child = new View(context);
        child.setId(childId);
        group.addView(child);
        return group;
    }

    @Test
    public void aListingBelowTheStatusBarGetsNoTopInset() {
        try (ActivityController<Activity> controller = ListingGapForTests.listing(ListingGapForTests.PAGE_TOP, ListingGapForTests.ROOT_TAG)) {
            View decor = controller.get().getWindow().getDecorView();
            View root = ListingGapForTests.root(controller);
            assertSame(root, ListingGap.reactRoot(decor));
            int[] at = new int[2];
            root.getLocationInWindow(at);
            assertTrue("the page sits " + at[1] + " below the window's top", at[1] >= ListingGapForTests.PAGE_TOP);
            Insets whole = Insets.of(0, at[1] + 10, 0, 0);
            Insets trimmed = (Insets) ListingGap.insets(whole, decor);
            assertEquals("the part of the top inset over the page", 10, trimmed.top);
            assertEquals(0, trimmed.left);
            assertEquals(0, trimmed.right);
            assertEquals(0, trimmed.bottom);
            assertEquals(FamilyNames.LISTING_GAP + ": invoked 1, 1 found, 0 missing. Counted: "
                    + ListingGap.TRIMMED + " 1", statusLine());
        }
    }

    @Test
    public void aSafeAreaViewInAListingBelowTheStatusBarPadsNothingOnTop() {
        // React Native's SafeAreaView pads by what Android hands it, without asking the module.
        try (ActivityController<Activity> controller = ListingGapForTests.listing(ListingGapForTests.PAGE_TOP, ListingGapForTests.ROOT_TAG)) {
            View decor = controller.get().getWindow().getDecorView();
            View safeArea = decor.findViewById(ListingGapForTests.CHILD_TAG);
            View root = ListingGapForTests.root(controller);
            assertSame(root, ListingGap.rootAbove(safeArea));
            int[] at = new int[2];
            root.getLocationInWindow(at);
            Insets whole = Insets.of(0, at[1] + 10, 0, 0);
            Insets trimmed = (Insets) ListingGap.viewInsets(whole, safeArea);
            assertEquals("the part of the top inset over the page", 10, trimmed.top);
            assertEquals(0, trimmed.bottom);
            Insets module = (Insets) ListingGap.insets(whole, decor);
            assertEquals("the module's answer agrees", 10, module.top);
            assertEquals("each place Facebook pads from is its own anchor",
                    FamilyNames.LISTING_GAP + ": invoked 2, 2 found, 0 missing. Counted: "
                    + ListingGap.VIEW_TRIMMED + " 1, " + ListingGap.TRIMMED + " 1", statusLine());
        }
    }

    @Test
    public void aSafeAreaViewKeepsTheInsetsItsPageSitsUnder() {
        try (ActivityController<Activity> controller = ListingGapForTests.listing(ListingGapForTests.PAGE_TOP, ListingGapForTests.ROOT_TAG)) {
            View safeArea = controller.get().getWindow().getDecorView().findViewById(ListingGapForTests.CHILD_TAG);
            // The page fills the window's width and runs to its bottom, so the sides and bottom stay.
            Insets sides = Insets.of(24, 0, 24, 48);
            assertSame(sides, ListingGap.viewInsets(sides, safeArea));
            Settings.REMOVE_LISTING_GAP.save(false);
            Insets under = Insets.of(0, 400, 0, 0);
            assertSame("off", under, ListingGap.viewInsets(under, safeArea));
        }
        Insets whole = Insets.of(0, 400, 0, 0);
        assertNull(ListingGap.viewInsets(null, new View(RuntimeEnvironment.getApplication())));
        assertSame("not a view", whole, ListingGap.viewInsets(whole, "view"));
    }

    @Test
    public void anAskBeforeThePageHasAFrameIsCounted() {
        // Nothing the page will fill is laid out, so the insets go out whole, and the export says so.
        android.content.Context context = RuntimeEnvironment.getApplication();
        Insets whole = Insets.of(0, 136, 0, 0);
        assertSame(whole, ListingGap.insets(whole, new FrameLayout(context)));
        FrameLayout detached = new FrameLayout(context);
        View safeArea = new View(context);
        detached.addView(safeArea);
        assertNull("not in a root", ListingGap.rootAbove(safeArea));
        assertSame(whole, ListingGap.viewInsets(whole, safeArea));
        assertEquals(FamilyNames.LISTING_GAP + ": invoked 2, 0 found, 0 missing. Counted: "
                + ListingGap.UNMEASURED + " 2", statusLine());
    }

    @Test
    public void insetsThePageDoesntSitClearOfGoOutAsTheyCame() {
        try (ActivityController<Activity> controller = ListingGapForTests.listing(ListingGapForTests.PAGE_TOP, ListingGapForTests.ROOT_TAG)) {
            View decor = controller.get().getWindow().getDecorView();
            // The page fills the window's width and runs to its bottom, so the sides and bottom stay.
            Insets sides = Insets.of(24, 0, 24, 48);
            assertSame(sides, ListingGap.insets(sides, decor));
            Insets none = Insets.of(0, 0, 0, 0);
            assertSame(none, ListingGap.insets(none, decor));
            assertEquals("nothing changed, so nothing was counted",
                    FamilyNames.LISTING_GAP + ": invoked 2, 1 found, 0 missing", statusLine());
        }
    }

    @Test
    public void offPausedOrUnplacedTheInsetsGoOutAsTheyCame() {
        try (ActivityController<Activity> controller = ListingGapForTests.listing(ListingGapForTests.PAGE_TOP, ListingGapForTests.ROOT_TAG)) {
            View decor = controller.get().getWindow().getDecorView();
            Insets whole = Insets.of(0, 400, 0, 0);
            Settings.REMOVE_LISTING_GAP.save(false);
            assertSame("off", whole, ListingGap.insets(whole, decor));
            Settings.REMOVE_LISTING_GAP.save(true);
            for (HushfacebookPause.Reason reason : PAUSES) {
                PauseForTests.pause(reason);
                assertSame("paused by " + reason, whole, ListingGap.insets(whole, decor));
                PauseForTests.resume();
            }
            assertFalse("the switch didn't come back after the pause", whole == ListingGap.insets(whole, decor));
        }
        assertNull(ListingGap.insets(null, new View(RuntimeEnvironment.getApplication())));
        Insets whole = Insets.of(0, 400, 0, 0);
        assertSame("not a view", whole, ListingGap.insets(whole, "decor"));
        assertSame("no view", whole, ListingGap.insets(whole, null));
        assertSame("nothing laid out", whole, ListingGap.insets(whole, new FrameLayout(RuntimeEnvironment.getApplication())));
    }

    @Test
    public void beforeItsRootIsPlacedTheFrameThePageFillsMeasuresIt() {
        // A listing opened from the Marketplace grid asks before its root has a React child.
        try (ActivityController<Activity> controller = ListingGapForTests.listing(ListingGapForTests.PAGE_TOP, 12)) {
            View decor = controller.get().getWindow().getDecorView();
            assertNull(ListingGap.reactRoot(decor));
            View frame = decor.findViewById(ListingGapForTests.CHILD_TAG);
            int[] at = new int[2];
            frame.getLocationInWindow(at);
            assertTrue("the frame sits " + at[1] + " below the window's top", at[1] >= ListingGapForTests.PAGE_TOP);
            assertArrayEquals(new int[] {at[0], at[1], at[0] + frame.getWidth(), at[1] + frame.getHeight()},
                    ListingGap.frameOf(decor));
            Insets whole = Insets.of(0, at[1] + 10, 0, 0);
            Insets trimmed = (Insets) ListingGap.insets(whole, decor);
            assertEquals("the part of the top inset over the frame", 10, trimmed.top);
            assertEquals(FamilyNames.LISTING_GAP + ": invoked 1, 1 found, 0 missing. Counted: "
                    + ListingGap.TRIMMED + " 1", statusLine());
        }
        try (ActivityController<Activity> controller = ListingGapForTests.listing(ListingGapForTests.PAGE_TOP, 12)) {
            // Two showing views side by side: the frame stops at the view holding both and takes the
            // room inside its padding. The window's action bar never stops it, since the walk starts
            // at the content frame.
            Activity activity = controller.get();
            FrameLayout content = activity.findViewById(android.R.id.content);
            content.addView(new View(activity));
            content.setPadding(0, 40, 0, 20);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            int[] at = new int[2];
            content.getLocationInWindow(at);
            assertArrayEquals(new int[] {at[0], at[1] + 40, at[0] + content.getWidth(), at[1] + content.getHeight() - 20},
                    ListingGap.frameOf(activity.getWindow().getDecorView()));
        }
    }

    @Test
    public void aContainerWaitingForItsFirstLayoutIsPlacedByItsMargins() {
        // A listing's first ask, when its page container is showing but not laid out yet, held clear
        // of the status and navigation bars by its margins.
        try (ActivityController<Activity> controller = ListingGapForTests.listing(ListingGapForTests.PAGE_TOP, 12)) {
            Activity activity = controller.get();
            View decor = activity.getWindow().getDecorView();
            FrameLayout content = activity.findViewById(android.R.id.content);
            content.removeAllViews();
            FrameLayout.LayoutParams clear = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            clear.topMargin = ListingGapForTests.PAGE_TOP;
            clear.bottomMargin = 63;
            FrameLayout container = new FrameLayout(activity);
            content.addView(container, clear);
            assertFalse(container.isLaidOut());
            int[] at = new int[2];
            content.getLocationInWindow(at);
            assertArrayEquals(new int[] {at[0], at[1] + ListingGapForTests.PAGE_TOP, at[0] + content.getWidth(),
                    at[1] + content.getHeight() - 63}, ListingGap.frameOf(decor));
            Insets whole = Insets.of(0, at[1] + ListingGapForTests.PAGE_TOP, 0, 0);
            assertEquals(0, ((Insets) ListingGap.insets(whole, decor)).top);
        }
    }
}
