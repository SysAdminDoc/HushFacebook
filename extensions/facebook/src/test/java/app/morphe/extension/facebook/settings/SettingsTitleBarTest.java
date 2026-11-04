/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.EnumSet;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PauseForTests;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;

/**
 * The main page opens on Facebook's icon, the name in the gradient and the version line, with the
 * bar over them leaving the name off. As the rows scroll, the three slide up under the bar, which
 * names the page again once the header's name has gone, and says "Search settings" while a search
 * is typed. The bar and its line stay where they are, and nothing that slides under them shows
 * through or takes a tap meant for the bar.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class SettingsTitleBarTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private SettingsDialog dialog;

    @Before public void open() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        PatchFamily.inBuildForTests.remove(PatchFamily.MATERIAL_YOU_THEME);
        PauseForTests.resume();
        // The test host has no version name of its own, so the header's line would have none to show.
        android.content.Context app = org.robolectric.RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(app.getPackageManager())
                .getInternalMutablePackageInfo(app.getPackageName()).versionName = "584.0.0.51.56";
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        dialog = SettingsL10nTest.show(controller.get());
        SettingsL10nTest.pageOf(dialog);
        layout();
    }

    @After public void close() {
        controller.close();
        PatchFamily.inBuildForTests = null;
        PauseForTests.resume();
    }

    @Test public void theMainPageOpensOnTheIconTheNameAndTheVersionLine() {
        HeaderColumn root = root();
        ImageView icon = root.findViewWithTag(SettingsHeader.ICON);
        TextView name = root.findViewWithTag(SettingsHeader.NAME);
        TextView version = root.findViewWithTag(SettingsHeader.VERSION);
        assertNotNull("no header icon", icon);
        assertNotNull("no header name", name);
        assertNotNull("no version line", version);
        View searchBox = (View) search().getParent();
        assertTrue("the header comes before the search",
                root.indexOfChild(icon) < root.indexOfChild(name) && root.indexOfChild(name) < root.indexOfChild(version)
                        && root.indexOfChild(version) < root.indexOfChild(searchBox));

        assertEquals(dp(64), icon.getWidth());
        assertEquals(dp(64), icon.getHeight());
        assertEquals(ImageView.ScaleType.FIT_CENTER, icon.getScaleType());
        assertNotNull("the icon draws nothing", icon.getDrawable());
        assertEquals("a screen reader skips the icon",
                View.IMPORTANT_FOR_ACCESSIBILITY_NO, icon.getImportantForAccessibility());
        assertEquals(dp(28), icon.getTop() - root.pinnedBottom());
        assertEquals("the icon is centred", root.getWidth() - icon.getRight(), icon.getLeft());

        assertEquals("Hushfacebook", name.getText().toString());
        assertEquals(sp(32), name.getTextSize(), 0.01f);
        assertTrue("the name is a heading", name.isAccessibilityHeading());
        assertTrue("the name wears the gradient", ShimmerTitle.wears(name));
        assertEquals(Gravity.CENTER, name.getGravity() & Gravity.CENTER);
        assertEquals(dp(12), name.getTop() - icon.getBottom());
        assertEquals("the name is centred", root.getWidth() - name.getRight(), name.getLeft(), 1);

        String text = version.getText().toString();
        assertTrue(text, text.startsWith("Version ") && text.contains(" for Facebook ")
                && text.contains(Utils.getAppVersionName()));
        assertEquals(sp(13), version.getTextSize(), 0.01f);
        assertEquals(ScreenColors.black().summary, version.getCurrentTextColor());
        assertEquals(dp(4), version.getTop() - name.getBottom());
        assertEquals(dp(20), ((LinearLayout.LayoutParams) version.getLayoutParams()).bottomMargin);

        for (View part : new View[]{icon, name, version}) {
            assertFalse("nothing on the header can be tapped", part.isClickable() || part.isLongClickable());
            assertFalse("nothing on the header takes the focus", part.isFocusable());
        }
    }

    @Test public void theBarLeavesTheNameOffUntilTheHeadersNameHasGone() {
        HeaderColumn root = root();
        TextView title = barTitle();
        TextView name = root.findViewWithTag(SettingsHeader.NAME);
        int barHeight = bar().getHeight();
        assertEquals("Hushfacebook", title.getText().toString());
        assertEquals("the bar repeats the name the header shows", View.INVISIBLE, title.getVisibility());
        assertTrue(title.isAccessibilityHeading());

        root.setOffset(name.getBottom() - root.pinnedBottom() - 1);
        assertEquals("a sliver of the name still shows", View.INVISIBLE, title.getVisibility());
        root.setOffset(name.getBottom() - root.pinnedBottom());
        assertEquals("the name has gone under the bar", View.VISIBLE, title.getVisibility());
        assertEquals("Hushfacebook", title.getText().toString());

        root.collapse();
        layout();
        assertEquals(View.VISIBLE, title.getVisibility());
        assertEquals("the bar keeps its height", barHeight, bar().getHeight());
        assertEquals("the bar stays where it was", root.getPaddingTop(),
                Math.round(bar().getTop() + bar().getTranslationY()) - root.getScrollY());
        View list = root.getChildAt(root.getChildCount() - 1);
        assertEquals("the rows start under the bar", root.pinnedBottom(), list.getTop() - root.getScrollY());

        root.expand();
        assertEquals(0, root.getScrollY());
        assertEquals(View.INVISIBLE, title.getVisibility());
    }

    @Test public void aTypedSearchIsWhatTheBarSays() {
        TextView title = barTitle();
        TextView name = root().findViewWithTag(SettingsHeader.NAME);
        search().setText("other apps");
        ShadowLooper.idleMainLooper();
        assertEquals("Search settings", title.getText().toString());
        assertEquals(View.VISIBLE, title.getVisibility());
        assertFalse("the search isn't the page's heading", title.isAccessibilityHeading());
        assertEquals("the header stays while searching", View.VISIBLE, name.getVisibility());
        assertTrue(name.isAccessibilityHeading());

        search().setText("");
        ShadowLooper.idleMainLooper();
        layout();
        assertEquals("Hushfacebook", title.getText().toString());
        assertEquals(View.INVISIBLE, title.getVisibility());
        assertTrue(title.isAccessibilityHeading());
    }

    @Test public void aPageOtherThanTheMainOneHasNoHeaderAndNamesItselfInTheBar() {
        dialog.showPage("Downloads", false, "");
        layout();
        for (String part : new String[]{SettingsHeader.ICON, SettingsHeader.NAME, SettingsHeader.VERSION}) {
            assertEquals(part, View.GONE, root().findViewWithTag(part).getVisibility());
        }
        assertEquals("Downloads", barTitle().getText().toString());
        assertEquals(View.VISIBLE, barTitle().getVisibility());

        dialog.showPage("Hushfacebook", true, "");
        layout();
        assertEquals(View.VISIBLE, root().findViewWithTag(SettingsHeader.NAME).getVisibility());
        assertEquals(View.INVISIBLE, barTitle().getVisibility());
    }

    @Test public void rowsMovingUpTakeTheTopWithThemFirstAndAPullAtTheFirstRowBringsItBack() {
        HeaderColumn root = root();
        ListView list = list();
        assertTrue("the list doesn't pass its drags on", list.isNestedScrollingEnabled());
        assertTrue(root.onStartNestedScroll(list, list, View.SCROLL_AXIS_VERTICAL));
        assertFalse(root.onStartNestedScroll(list, list, View.SCROLL_AXIS_HORIZONTAL));
        int range = root.range();
        assertTrue("nothing to slide away: " + range, range > dp(100));

        int[] consumed = new int[2];
        root.onNestedPreScroll(list, 0, 40, consumed);
        assertEquals(40, consumed[1]);
        assertEquals(40, root.getScrollY());
        consumed = new int[2];
        root.onNestedPreScroll(list, 0, 100000, consumed);
        assertEquals("the top only goes as far as it is tall", range - 40, consumed[1]);
        assertEquals(range, root.getScrollY());
        consumed = new int[2];
        root.onNestedPreScroll(list, 0, 50, consumed);
        assertEquals("with the top gone the rows scroll", 0, consumed[1]);

        consumed = new int[2];
        root.onNestedPreScroll(list, 0, -50, consumed);
        assertEquals("rows moving down scroll the list first", 0, consumed[1]);
        assertEquals(range, root.getScrollY());
        root.onNestedScroll(list, 0, 0, 0, -30);
        assertEquals(range - 30, root.getScrollY());
        root.onNestedScroll(list, 0, 0, 0, -100000);
        assertEquals(0, root.getScrollY());
        assertFalse("a fling stays the list's", root.onNestedPreFling(list, 0, 5000));
    }

    @Test public void aJumpThatMovesTheListOnTakesTheTopUp() {
        assertEquals(0, root().getScrollY());
        list().setSelection(20);
        layout();
        assertTrue("the list didn't move", list().getFirstVisiblePosition() > 0);
        assertEquals(root().range(), root().getScrollY());
        assertEquals(View.VISIBLE, barTitle().getVisibility());
        layout();
        assertEquals("a new layout keeps the top up", root().range(), root().getScrollY());
    }

    @Test public void aTapOnTheBarThatNoButtonTakesStaysOnTheBar() {
        HeaderColumn root = root();
        root.collapse();
        layout();
        TextView title = barTitle();
        int x = title.getRight() - dp(4);
        int y = bar().getHeight() / 2;
        View under = underTheBar(x, y);
        boolean listMode = SettingsL10nTest.pageOf(dialog).navigation.listMode();
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0);
        root.dispatchTouchEvent(down);
        root.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
        ShadowLooper.idleMainLooper();
        assertFalse("a tap on the bar reached the search under it", search().hasFocus());
        assertEquals("a tap on the bar reached " + under + " under it",
                listMode, SettingsL10nTest.pageOf(dialog).navigation.listMode());
        assertEquals(root.range(), root.getScrollY());
    }

    /**
     * The search or the view toggle, whichever the collapsed header leaves under the bar at
     * [x], [y]. Neither under it would prove nothing.
     */
    private View underTheBar(int x, int y) {
        HeaderColumn root = root();
        View searchBox = (View) search().getParent();
        View toggle = root.findViewWithTag(SettingsDialog.VIEW_TOGGLE);
        for (View candidate : new View[]{searchBox, toggle}) {
            if (candidate != null && candidate.getVisibility() == View.VISIBLE
                    && candidate.getTop() <= y + root.getScrollY() && y + root.getScrollY() < candidate.getBottom()
                    && candidate.getLeft() <= x && x < candidate.getRight()) return candidate;
        }
        throw new AssertionError("neither the search nor the view toggle is under the bar here, so this proves nothing");
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void nothingThatSlidesUnderTheBarShowsThroughIt() {
        HeaderColumn root = root();
        root.collapse();
        layout();
        int x = barTitle().getRight() - dp(4);
        int y = bar().getHeight() / 2;
        View under = underTheBar(x, y);
        Bitmap shot = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(shot);
        // Where the window draws it: the column's own scroll moves what it holds up.
        canvas.translate(-root.getScrollX(), -root.getScrollY());
        root.draw(canvas);
        ScreenColors colors = ScreenColors.black();
        assertEquals(under + " shows through the bar", hex(colors.background), hex(shot.getPixel(x, y)));
        assertEquals("the bar's line moved", hex(colors.outline), hex(shot.getPixel(x, root.pinnedBottom() - 1)));
        shot.recycle();
    }

    private HeaderColumn root() {
        return (HeaderColumn) dialog.getView();
    }

    private ViewGroup bar() {
        return (ViewGroup) root().getChildAt(0);
    }

    private TextView barTitle() {
        ViewGroup bar = bar();
        for (int index = 0; index < bar.getChildCount(); index++) {
            View child = bar.getChildAt(index);
            if (child instanceof TextView && !(child instanceof android.widget.Button)) return (TextView) child;
        }
        throw new AssertionError("the bar has no title");
    }

    private EditText search() {
        HeaderColumn root = root();
        for (int index = 0; index < root.getChildCount() - 1; index++) {
            View child = root.getChildAt(index);
            if (!(child instanceof ViewGroup) || child == bar()) continue;
            ViewGroup group = (ViewGroup) child;
            for (int inner = 0; inner < group.getChildCount(); inner++) {
                if (group.getChildAt(inner) instanceof EditText) return (EditText) group.getChildAt(inner);
            }
        }
        throw new AssertionError("no search box");
    }

    private ListView list() {
        return dialog.getView().findViewById(android.R.id.list);
    }

    private void layout() {
        View root = dialog.getView();
        root.measure(View.MeasureSpec.makeMeasureSpec(780, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1688, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 780, 1688);
        ShadowLooper.idleMainLooper();
        root.measure(View.MeasureSpec.makeMeasureSpec(780, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1688, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 780, 1688);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                dialog.getView().getResources().getDisplayMetrics()));
    }

    private float sp(int value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                dialog.getView().getResources().getDisplayMetrics());
    }

    private static String hex(int color) {
        return String.format("#%08X", color);
    }
}
