/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.robolectric.Robolectric;
import org.robolectric.shadows.ShadowLooper;

/**
 * A main screen laid out the way Facebook 582's is on a 720 by 1600 phone, for the see-through bars:
 * the feed's page the whole height, the top bar's holder over its top 88 pixels with the top bar
 * inside, and the tab bar's 160 pixel holder over its bottom with the row of tabs inside.
 */
public final class TranslucentBarsForTests {
    /** Stands in for Facebook's NavigationBar, found by its class name. */
    public static final class TopBar extends FrameLayout {
        public TopBar(Context context) {
            super(context);
        }
    }

    /** Stands in for Facebook's TabBarContainerLayout. */
    public static final class TabBar extends LinearLayout {
        public TabBar(Context context) {
            super(context);
        }
    }

    public static final int TOP_COLOUR = 0xFF242526;
    public static final int TAB_COLOUR = 0xFF18191A;

    public final Activity activity;
    public final FrameLayout window;
    public final FrameLayout page;
    public final FrameLayout topHolder;
    public final TopBar topBar;
    public final View searchButton;
    public final FrameLayout tabHolder;
    public final TabBar tabBar;
    public final View tab;
    public final View badge;
    public final TranslucentBars.Bars bars;

    public TranslucentBarsForTests() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        window = new FrameLayout(activity);
        page = new FrameLayout(activity);
        page.setBackground(new ColorDrawable(0xFF000000));
        window.addView(page, new FrameLayout.LayoutParams(720, 1600));

        topHolder = new FrameLayout(activity);
        topHolder.setBackground(new ColorDrawable(TOP_COLOUR));
        topBar = new TopBar(activity);
        searchButton = new View(activity);
        GradientDrawable round = new GradientDrawable();
        round.setColor(0xFF3A3B3C);
        searchButton.setBackground(round);
        topBar.addView(searchButton, new FrameLayout.LayoutParams(88, 88, Gravity.END));
        topHolder.addView(topBar, new FrameLayout.LayoutParams(720, 88));
        window.addView(topHolder, new FrameLayout.LayoutParams(720, 88, Gravity.TOP));

        tabHolder = new FrameLayout(activity);
        tabBar = new TabBar(activity);
        tabBar.setBackground(new ColorDrawable(TAB_COLOUR));
        tab = new View(activity);
        tab.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null));
        tabBar.addView(tab, new LinearLayout.LayoutParams(120, 112));
        badge = new View(activity);
        GradientDrawable dot = new GradientDrawable();
        dot.setColor(0xFFE41E3F);
        badge.setBackground(dot);
        tabBar.addView(badge, new LinearLayout.LayoutParams(20, 20));
        tabHolder.addView(tabBar, new FrameLayout.LayoutParams(720, 160));
        window.addView(tabHolder, new FrameLayout.LayoutParams(720, 160, Gravity.BOTTOM));

        activity.setContentView(window, new FrameLayout.LayoutParams(720, 1600));
        layOut();
        bars = new TranslucentBars.Bars(window, TopBar.class.getName(), TabBar.class.getName());
    }

    /** Lays the screen out at 720 by 1600, as a frame would. */
    public void layOut() {
        ShadowLooper.idleMainLooper();
        window.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY));
        window.layout(0, 0, 720, 1600);
    }

    /**
     * The main screen with the tab bar at the top, the way Facebook 583 stacks it: one header holder
     * over the page's top with the top bar's 88 pixel row, the tab bar's 88 pixel row and a 2 pixel
     * line under them, so neither row's fill is half the holder (#171).
     */
    public static final class TabBarOnTop {
        public final FrameLayout window;
        public final FrameLayout page;
        public final LinearLayout header;
        public final FrameLayout topRow;
        public final TopBar topBar;
        public final TabBar tabBar;
        public final View line;
        public final TranslucentBars.Bars bars;

        public TabBarOnTop() {
            Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
            window = new FrameLayout(activity);
            page = new FrameLayout(activity);
            page.setBackground(new ColorDrawable(0xFF000000));
            window.addView(page, new FrameLayout.LayoutParams(720, 1600));

            header = new LinearLayout(activity);
            header.setOrientation(LinearLayout.VERTICAL);
            topRow = new FrameLayout(activity);
            topRow.setBackground(new ColorDrawable(TOP_COLOUR));
            topBar = new TopBar(activity);
            topRow.addView(topBar, new FrameLayout.LayoutParams(720, 88));
            header.addView(topRow, new LinearLayout.LayoutParams(720, 88));
            tabBar = new TabBar(activity);
            tabBar.setBackground(new ColorDrawable(TAB_COLOUR));
            header.addView(tabBar, new LinearLayout.LayoutParams(720, 88));
            line = new View(activity);
            line.setBackground(new ColorDrawable(0xFF3E4042));
            header.addView(line, new LinearLayout.LayoutParams(720, 2));
            window.addView(header, new FrameLayout.LayoutParams(720, 178, Gravity.TOP));

            activity.setContentView(window, new FrameLayout.LayoutParams(720, 1600));
            ShadowLooper.idleMainLooper();
            window.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY));
            window.layout(0, 0, 720, 1600);
            bars = new TranslucentBars.Bars(window, TopBar.class.getName(), TabBar.class.getName());
        }
    }

    /**
     * One frame of a fresh screen. True when it made the top bar's background see-through, which is
     * the switch changing what Facebook draws.
     */
    public static boolean makesABarSeeThrough() {
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        screen.bars.update();
        return screen.topHolder.getBackground().getAlpha() < 255;
    }

    /** Says the tab bar patch is in the build, or with null, asks SettingsStatus again. */
    public static void inBuild(Boolean inBuild) {
        TranslucentBars.inBuildForTests = inBuild;
    }
}
