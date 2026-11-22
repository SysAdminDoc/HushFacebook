/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.facebook.katana.activity.FbMainTabActivity;
import com.facebook.katana.immersiveactivity.ImmersiveActivity;
import com.facebook.navigation.tabbar.ui.TabBarContainerLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Keep the tab bar in groups (#503): a group's page, a screen of its own over the main screen with
 * no tab bar, gets a copy of the main screen's bar along its bottom, placed the way the main screen
 * has it, and the page ends where the copy starts. A tab in the copy closes the screens over the main screen and taps that tab
 * there; the tab already showing is only shown again. Other pages, a bar at the top, the switch off
 * or a pause leave the page as Facebook shows it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w360dp-h640dp-mdpi")
public class GroupTabBarTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    static final int BAR_HEIGHT = 56;
    /** Facebook's own bottom margin on a page, for the gesture bar. */
    static final int GESTURE_MARGIN = 24;

    /** One of the main screen's tabs, counting its taps. */
    static final class Tab extends View {
        int taps;

        Tab(Context context, String label) {
            super(context);
            setContentDescription(label);
            setOnClickListener(v -> taps++);
        }
    }

    private ActivityController<FbMainTabActivity> mainController;
    private final List<Tab> tabs = new ArrayList<>();

    @Before
    public void start() {
        GroupTabBar.forget();
        HookStatus.clear();
        GroupTabBar.watch(RuntimeEnvironment.getApplication());
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.TAB_BAR_IN_GROUPS.resetToDefault();
        GroupTabBar.forget();
        HookStatus.clear();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** The main screen, its tab bar down to the bottom (as on 588) or the top, the second tab showing. */
    private FbMainTabActivity mainScreen(boolean barAtBottom) {
        return mainScreen(barAtBottom, 0);
    }

    /** The main screen, its tab bar [gapBelow] above the bottom (or at the top), the second tab showing. */
    private FbMainTabActivity mainScreen(boolean barAtBottom, int gapBelow) {
        mainController = Robolectric.buildActivity(FbMainTabActivity.class).create();
        FbMainTabActivity main = mainController.get();
        FrameLayout root = new FrameLayout(main);
        TabBarContainerLayout bar = new TabBarContainerLayout(main);
        LinearLayout row = new LinearLayout(main);
        for (String label : new String[]{"Home, tab 1 of 3", "Groups, tab 2 of 3", "Profile, tab 3 of 3"}) {
            Tab tab = new Tab(main, label);
            tabs.add(tab);
            row.addView(tab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        tabs.get(1).setSelected(true);
        bar.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, BAR_HEIGHT,
                barAtBottom ? Gravity.BOTTOM : Gravity.TOP);
        barParams.bottomMargin = gapBelow;
        root.addView(bar, barParams);
        main.setContentView(root);
        mainController.start().resume().visible();
        idle();
        return main;
    }

    /** A page over the main screen, a group's when [group], with Facebook's margin for the gesture bar. */
    private static ActivityController<ImmersiveActivity> page(boolean group) {
        return page(group, false);
    }

    /**
     * A page over the main screen, a group's when [group]. Facebook leaves room for the gesture bar
     * under it by padding the page's frame when [paddedFrame], as 588 does, or by the page's margin.
     */
    private static ActivityController<ImmersiveActivity> page(boolean group, boolean paddedFrame) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), ImmersiveActivity.class);
        if (group) intent.putExtra(GroupTabBar.GROUP_EXTRA, "307304398324750");
        ActivityController<ImmersiveActivity> controller = Robolectric.buildActivity(ImmersiveActivity.class, intent).create();
        FrameLayout pageRoot = new FrameLayout(controller.get());
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        if (!paddedFrame) params.bottomMargin = GESTURE_MARGIN;
        controller.get().setContentView(pageRoot, params);
        if (paddedFrame) contentOf(controller.get()).setPadding(0, 0, 0, GESTURE_MARGIN);
        controller.start().resume().visible();
        idle();
        return controller;
    }

    private static ViewGroup contentOf(Activity activity) {
        return activity.findViewById(android.R.id.content);
    }

    /** The window's root, which holds the copy. */
    private static ViewGroup decorOf(Activity activity) {
        return (ViewGroup) activity.getWindow().getDecorView();
    }

    /** Where [view] starts or ends down its window. */
    private static int topInWindow(View view) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        return at[1];
    }

    private static int bottomInWindow(View view) {
        return topInWindow(view) + view.getHeight();
    }

    private static View pageRootOf(Activity activity) {
        return contentOf(activity).getChildAt(0);
    }

    private static int bottomMargin(View view) {
        return ((ViewGroup.MarginLayoutParams) view.getLayoutParams()).bottomMargin;
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.BOTTOM_TAB_BAR + ":")) return line;
        }
        return "";
    }

    @Test
    public void aGroupGetsACopyOfTheTabBarAboveTheGestureBar() {
        Settings.TAB_BAR_IN_GROUPS.save(true);
        mainScreen(true);
        ActivityController<ImmersiveActivity> group = page(true, true);

        GroupTabBar.Mirror copy = GroupTabBar.mirrorIn(decorOf(group.get()));
        assertNotNull("the group got no tab bar", copy);
        assertEquals("the copy isn't the bar's height", BAR_HEIGHT, copy.getHeight());
        assertEquals("the copy doesn't have a button for each tab", 3, copy.getChildCount());
        assertEquals("Groups, tab 2 of 3", copy.getChildAt(1).getContentDescription());
        assertTrue("the tab that was showing isn't marked", copy.getChildAt(1).isSelected());
        assertEquals("the buttons aren't over their tabs", tabs.get(1).getLeft(), copy.getChildAt(1).getLeft());
        // 588's bar runs down under the gesture bar, so the copy does too, past the frame's padding
        // for it, and the page gives up only the rest of the copy's height.
        assertEquals("the copy doesn't reach the bottom like the bar it copies",
                decorOf(group.get()).getHeight(), bottomInWindow(copy));
        assertEquals("the page doesn't end where the copy starts", topInWindow(copy), bottomInWindow(pageRootOf(group.get())));
        assertEquals("the page gave up more than the copy covers", BAR_HEIGHT - GESTURE_MARGIN,
                bottomMargin(pageRootOf(group.get())));
        assertTrue(statusLine(), statusLine().contains(GroupTabBar.SHOWN + " 1"));

        // Back from a post in the group: the copy is recorded again, not added twice.
        group.pause().resume();
        idle();
        assertEquals("the group got a second copy", 1, mirrors(decorOf(group.get())));
        assertEquals("the page's margin grew twice", BAR_HEIGHT - GESTURE_MARGIN, bottomMargin(pageRootOf(group.get())));
        assertFalse(statusLine(), statusLine().contains("threw"));
    }

    @Test
    public void aPageWithAMarginForTheGestureBarEndsWhereTheCopyStartsToo() {
        Settings.TAB_BAR_IN_GROUPS.save(true);
        mainScreen(true);
        ActivityController<ImmersiveActivity> group = page(true);

        GroupTabBar.Mirror copy = GroupTabBar.mirrorIn(decorOf(group.get()));
        assertNotNull("the group got no tab bar", copy);
        assertEquals("the copy doesn't reach the bottom like the bar it copies",
                decorOf(group.get()).getHeight(), bottomInWindow(copy));
        assertEquals("the page doesn't end where the copy starts", topInWindow(copy), bottomInWindow(pageRootOf(group.get())));
        assertEquals(BAR_HEIGHT, bottomMargin(pageRootOf(group.get())));
    }

    @Test
    public void aBarAboveItsOwnGestureStripIsCopiedAboveThePagesStrip() {
        Settings.TAB_BAR_IN_GROUPS.save(true);
        mainScreen(true, GESTURE_MARGIN);
        ActivityController<ImmersiveActivity> group = page(true);

        GroupTabBar.Mirror copy = GroupTabBar.mirrorIn(decorOf(group.get()));
        assertNotNull("the group got no tab bar", copy);
        assertEquals("the copy isn't right above the room left for the gesture bar",
                decorOf(group.get()).getHeight() - GESTURE_MARGIN, bottomInWindow(copy));
        assertEquals("the page still runs under the copy", GESTURE_MARGIN + BAR_HEIGHT, bottomMargin(pageRootOf(group.get())));
        assertEquals("the page doesn't end where the copy starts", topInWindow(copy), bottomInWindow(pageRootOf(group.get())));
    }

    private static int mirrors(ViewGroup content) {
        int count = 0;
        for (int index = 0; index < content.getChildCount(); index++) {
            if (content.getChildAt(index) instanceof GroupTabBar.Mirror) count++;
        }
        return count;
    }

    @Test
    public void aTabClosesTheScreensOverTheMainScreenAndIsTappedThere() {
        Settings.TAB_BAR_IN_GROUPS.save(true);
        FbMainTabActivity main = mainScreen(true);
        ActivityController<ImmersiveActivity> between = page(false);
        ActivityController<ImmersiveActivity> group = page(true);

        GroupTabBar.mirrorIn(decorOf(group.get())).getChildAt(2).performClick();
        assertTrue("the group stayed open", group.get().isFinishing());
        assertTrue("the page under the group stayed open", between.get().isFinishing());
        assertFalse("the main screen closed", main.isFinishing());
        assertEquals("the tab was tapped before the main screen was back", 0, tabs.get(2).taps);

        mainController.pause().resume();
        idle();
        assertEquals("the tab wasn't tapped once the main screen was back", 1, tabs.get(2).taps);
        mainController.pause().resume();
        idle();
        assertEquals("the tab was tapped again on a later return", 1, tabs.get(2).taps);
        assertTrue(statusLine(), statusLine().contains(GroupTabBar.TAPPED + " 1"));
    }

    @Test
    public void theTabThatWasShowingIsOnlyShownAgain() {
        Settings.TAB_BAR_IN_GROUPS.save(true);
        mainScreen(true);
        ActivityController<ImmersiveActivity> group = page(true);

        GroupTabBar.mirrorIn(decorOf(group.get())).getChildAt(1).performClick();
        assertTrue("the group stayed open", group.get().isFinishing());
        mainController.pause().resume();
        idle();
        assertEquals("the tab that was showing was tapped again", 0, tabs.get(1).taps);
    }

    @Test
    public void theCopyStepsAsideForTheKeyboard() {
        Settings.TAB_BAR_IN_GROUPS.save(true);
        mainScreen(true);
        ActivityController<ImmersiveActivity> group = page(true);
        View pageRoot = pageRootOf(group.get());
        GroupTabBar.Mirror copy = GroupTabBar.mirrorIn(decorOf(group.get()));

        int keyboard = contentOf(group.get()).getHeight() / 2;
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) pageRoot.getLayoutParams();
        margins.bottomMargin = keyboard;
        pageRoot.setLayoutParams(margins);
        idle();
        assertEquals("the copy stayed over the keyboard", View.GONE, copy.getVisibility());
        assertEquals("the page lost room to a hidden copy", keyboard, bottomMargin(pageRoot));

        margins.bottomMargin = GESTURE_MARGIN;
        pageRoot.setLayoutParams(margins);
        idle();
        assertEquals("the copy didn't come back with the keyboard gone", View.VISIBLE, copy.getVisibility());
        assertEquals(BAR_HEIGHT, bottomMargin(pageRoot));
    }

    @Test
    public void otherPagesABarAtTheTopTheSwitchOffAndAPauseLeaveTheGroupAlone() {
        assertFalse("the switch doesn't start off", Settings.TAB_BAR_IN_GROUPS.get());
        assertTrue(PatchFamily.BOTTOM_TAB_BAR.switches.contains(Settings.TAB_BAR_IN_GROUPS));
        mainScreen(true);
        ActivityController<ImmersiveActivity> off = page(true);
        assertNull("a group got the bar with the switch off", GroupTabBar.mirrorIn(decorOf(off.get())));
        assertEquals(GESTURE_MARGIN, bottomMargin(pageRootOf(off.get())));

        Settings.TAB_BAR_IN_GROUPS.save(true);
        assertNull("a page that isn't a group got the bar", GroupTabBar.mirrorIn(decorOf(page(false).get())));

        // Turned off while a group is open: its next return gives the page its room back.
        ActivityController<ImmersiveActivity> group = page(true);
        assertNotNull(GroupTabBar.mirrorIn(decorOf(group.get())));
        Settings.TAB_BAR_IN_GROUPS.save(false);
        group.pause().resume();
        idle();
        assertNull("the copy stayed with the switch off", GroupTabBar.mirrorIn(decorOf(group.get())));
        assertEquals("the page kept the copy's room", GESTURE_MARGIN, bottomMargin(pageRootOf(group.get())));

        Settings.TAB_BAR_IN_GROUPS.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertNull("a paused Hushfacebook gave a group the bar", GroupTabBar.mirrorIn(decorOf(page(true).get())));
        PauseForTests.resume();
        assertFalse(statusLine(), statusLine().contains("threw"));
    }

    @Test
    public void aBarAtTheTopIsNotCopied() {
        Settings.TAB_BAR_IN_GROUPS.save(true);
        mainScreen(false);
        assertNull("a bar at the top was copied to the bottom", GroupTabBar.mirrorIn(decorOf(page(true).get())));
    }
}
