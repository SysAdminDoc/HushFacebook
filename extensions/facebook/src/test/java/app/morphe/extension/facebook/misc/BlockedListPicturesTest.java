/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.facebook.katana.activity.react.ImmersiveReactActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.Duration;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Hide pictures on the Blocked list (#361). The page copies the S22's Blocking page on 585 as
 * dumpsys has it (2026-11-02): the plus beside Add to blocked list is a 67 by 68 px image in a
 * 113 px square, and each blocked person's picture is a 113 px square image beside the name.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w384dp-h823dp-xxhdpi")
public class BlockedListPicturesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void setUp() {
        BlockedListPictures.forgetForTests();
        Settings.HIDE_BLOCKED_LIST_PICTURES.resetToDefault();
    }

    @After
    public void tearDown() {
        PauseForTests.resume();
        Settings.HIDE_BLOCKED_LIST_PICTURES.resetToDefault();
        BlockedListPictures.forgetForTests();
    }

    @Test
    public void startsOff() {
        assertFalse(Settings.HIDE_BLOCKED_LIST_PICTURES.defaultValue);
    }

    @Test
    public void hidesThePicturesAndKeepsThePlusAndTheNames() {
        Settings.HIDE_BLOCKED_LIST_PICTURES.save(true);
        Page page = new Page(blocking("route_name", BlockedListPictures.ROUTE));

        BlockedListPictures.resumed(page.activity);

        assertEquals(View.INVISIBLE, page.first.getVisibility());
        assertEquals(View.INVISIBLE, page.second.getVisibility());
        assertEquals("the plus went", View.VISIBLE, page.plus.getVisibility());
        assertEquals("a name went", View.VISIBLE, page.name.getVisibility());
    }

    @Test
    public void aRowThatScrollsInIsHiddenAtTheNextLayout() {
        Settings.HIDE_BLOCKED_LIST_PICTURES.save(true);
        Page page = new Page(blocking("route_name", BlockedListPictures.ROUTE));
        BlockedListPictures.resumed(page.activity);

        ImageView third = page.row(560);
        layOut(page.activity);

        assertEquals(View.INVISIBLE, third.getVisibility());
    }

    @Test
    public void aRowReactMountsLateIsHiddenAtTheNextDraw() {
        Settings.HIDE_BLOCKED_LIST_PICTURES.save(true);
        Page page = new Page(blocking("route_name", BlockedListPictures.ROUTE));
        BlockedListPictures.resumed(page.activity);

        // React Native sets a mounted view's frame itself and its groups skip requestLayout, so no
        // layout pass runs for the new row (the S22 showed exactly that). The next draw still comes.
        ReactGroup row = new ReactGroup(page.activity);
        page.list.addView(row);
        row.layout(0, 680, 1080, 800);
        ImageView late = new ImageView(page.activity);
        row.addView(late);
        late.layout(42, 4, 155, 117);
        page.activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw();

        assertEquals(View.INVISIBLE, late.getVisibility());
    }

    @Test
    public void theUriTellsThePageApartToo() {
        Settings.HIDE_BLOCKED_LIST_PICTURES.save(true);
        Page page = new Page(blocking("uri", "/privacy_blocking/"));

        BlockedListPictures.resumed(page.activity);

        assertEquals(View.INVISIBLE, page.first.getVisibility());
    }

    @Test
    public void anotherReactPageKeepsItsPictures() {
        Settings.HIDE_BLOCKED_LIST_PICTURES.save(true);
        Page page = new Page(blocking("route_name", "FriendsCenterRoute"));

        BlockedListPictures.resumed(page.activity);
        layOut(page.activity);

        assertEquals(View.VISIBLE, page.first.getVisibility());
    }

    @Test
    public void anotherScreenWithTheRouteKeepsItsPictures() {
        Settings.HIDE_BLOCKED_LIST_PICTURES.save(true);
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), Activity.class)
                .putExtra("route_name", BlockedListPictures.ROUTE);
        Page page = new Page(Robolectric.buildActivity(Activity.class, intent).setup().get());

        BlockedListPictures.resumed(page.activity);

        assertEquals(View.VISIBLE, page.first.getVisibility());
    }

    @Test
    public void switchedOffTheyComeBackWhenThePageReturns() {
        Settings.HIDE_BLOCKED_LIST_PICTURES.save(true);
        Page page = new Page(blocking("route_name", BlockedListPictures.ROUTE));
        BlockedListPictures.resumed(page.activity);
        assertEquals(View.INVISIBLE, page.first.getVisibility());

        Settings.HIDE_BLOCKED_LIST_PICTURES.save(false);
        BlockedListPictures.resumed(page.activity);

        assertEquals(View.VISIBLE, page.first.getVisibility());
        assertEquals(View.VISIBLE, page.second.getVisibility());
    }

    @Test
    public void offOrPausedTheyStay() {
        Page page = new Page(blocking("route_name", BlockedListPictures.ROUTE));
        BlockedListPictures.resumed(page.activity);
        assertEquals("hidden with the switch off", View.VISIBLE, page.first.getVisibility());

        Settings.HIDE_BLOCKED_LIST_PICTURES.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        BlockedListPictures.resumed(page.activity);
        assertEquals("hidden while paused", View.VISIBLE, page.first.getVisibility());
    }

    @Test
    public void aNestedRouteCounts() {
        Bundle args = new Bundle();
        args.putString("route_name", BlockedListPictures.ROUTE);
        assertTrue(BlockedListPictures.blockingRoute(new Intent().putExtra("fragment_args", args)));
        assertFalse(BlockedListPictures.blockingRoute(new Intent()));
        assertFalse(BlockedListPictures.blockingRoute(null));
    }

    /** For PausedHooksTest: with the switch on, the Blocking page's pictures go. */
    public static boolean aBlockingPageHides() {
        BlockedListPictures.forgetForTests();
        try {
            Page page = new Page(blocking("route_name", BlockedListPictures.ROUTE));
            BlockedListPictures.resumed(page.activity);
            return page.first.getVisibility() == View.INVISIBLE;
        } finally {
            BlockedListPictures.forgetForTests();
        }
    }

    private static Activity blocking(String key, String value) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), ImmersiveReactActivity.class)
                .putExtra(key, value)
                .putExtra("title", "Blocking");
        return Robolectric.buildActivity(ImmersiveReactActivity.class, intent).setup().get();
    }

    /** The Blocking page: the Add to blocked list row and two blocked people. */
    private static final class Page {
        final Activity activity;
        final FrameLayout list;
        final ImageView plus;
        final ImageView first;
        final ImageView second;
        TextView name;

        Page(Activity activity) {
            this.activity = activity;
            FrameLayout root = new FrameLayout(activity);
            list = new FrameLayout(activity);
            place(root, list, 0, 0, 1080, 2181);
            FrameLayout square = new FrameLayout(activity);
            place(list, square, 42, 300, 155, 413);
            plus = new ImageView(activity);
            place(square, plus, 23, 22, 90, 90);
            first = row(440);
            second = row(560);
            activity.setContentView(root);
            layOut(activity);
        }

        ImageView row(int top) {
            FrameLayout row = new FrameLayout(activity);
            place(list, row, 0, top, 1080, top + 120);
            ImageView picture = new ImageView(activity);
            place(row, picture, 42, 4, 155, 117);
            name = new TextView(activity);
            name.setText("Someone");
            place(row, name, 190, 30, 800, 90);
            return picture;
        }
    }

    /** A React Native view group: Yoga sets its children's frames, and requestLayout does nothing. */
    private static final class ReactGroup extends FrameLayout {
        ReactGroup(Activity activity) {
            super(activity);
        }

        @Override
        public void requestLayout() {
        }
    }

    private static void place(ViewGroup parent, View child, int left, int top, int right, int bottom) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(right - left, bottom - top, Gravity.LEFT | Gravity.TOP);
        params.leftMargin = left;
        params.topMargin = top;
        parent.addView(child, params);
    }

    private static void layOut(Activity screen) {
        screen.getWindow().getDecorView().requestLayout();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
    }
}
