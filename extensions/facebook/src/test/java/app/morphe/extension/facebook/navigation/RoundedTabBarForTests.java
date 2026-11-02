/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.robolectric.Robolectric;
import org.robolectric.shadows.ShadowLooper;

/**
 * A main screen with the tab bar at the bottom, the way Facebook 585 holds it on a 720 by 1600 phone,
 * for the rounded tab bar: the feed's page the whole height and, over its bottom, a 176 pixel holder
 * with a 2 pixel line, the 112 pixel row of tabs and the 62 pixels it keeps over the navigation bar.
 */
public final class RoundedTabBarForTests {
    public final Activity activity;
    public final FrameLayout window;
    public final FrameLayout page;
    public final LinearLayout holder;
    public final View line;
    public final TranslucentBarsForTests.TabBar tabBar;
    public final View strip;
    public final RoundedTabBar.Shaper shaper;

    public RoundedTabBarForTests() {
        this(false);
    }

    /**
     * With [overNavigation] the row of tabs is laid out down over the navigation bar and holds those
     * 62 pixels itself, with nothing under it, the way Facebook 585 lays it out over a gesture handle.
     */
    public RoundedTabBarForTests(boolean overNavigation) {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        window = new FrameLayout(activity);
        page = new FrameLayout(activity);
        page.setBackground(new ColorDrawable(0xFF000000));
        window.addView(page, new FrameLayout.LayoutParams(720, 1600));

        holder = new LinearLayout(activity);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setBackground(new ColorDrawable(TranslucentBarsForTests.TAB_COLOUR));
        line = new View(activity);
        line.setBackground(new ColorDrawable(0xFF3E4042));
        holder.addView(line, new LinearLayout.LayoutParams(720, 2));
        tabBar = new TranslucentBarsForTests.TabBar(activity);
        tabBar.addView(new View(activity), new LinearLayout.LayoutParams(120, 112));
        holder.addView(tabBar, new LinearLayout.LayoutParams(720, overNavigation ? 174 : 112));
        strip = new View(activity);
        holder.addView(strip, new LinearLayout.LayoutParams(720, overNavigation ? 0 : 62));
        window.addView(holder, new FrameLayout.LayoutParams(720, 176, Gravity.BOTTOM));

        activity.setContentView(window, new FrameLayout.LayoutParams(720, 1600));
        layOut();
        shaper = new RoundedTabBar.Shaper(window, TranslucentBarsForTests.TabBar.class.getName());
    }

    /** Lays the screen out at 720 by 1600, as a frame would. */
    public void layOut() {
        ShadowLooper.idleMainLooper();
        window.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY));
        window.layout(0, 0, 720, 1600);
    }

    /** Where the screen starts in the activity's window, under whatever bar the test activity puts over it. */
    public int windowTop() {
        int[] at = new int[2];
        window.getLocationInWindow(at);
        return at[1];
    }

    /** One frame of a fresh screen. True when it made the tab bar a pill, which is the switch changing what Facebook draws. */
    public static boolean roundsTheBar() {
        RoundedTabBarForTests screen = new RoundedTabBarForTests();
        screen.shaper.update();
        return screen.holder.getClipToOutline();
    }
}
