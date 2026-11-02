/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;

import com.facebook.katana.immersiveactivity.ImmersiveActivity;
import com.facebook.litho.ComponentHost;
import com.facebook.litho.LithoView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.Duration;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Search and the button beside it at the top of an opened video (#311). The bar copies the S22's
 * on 585 as dumpsys has it (2026-10-31): the LithoView 0,0-1080,338, its row 0,0-1080,199, Back's
 * clickable host four levels down at 0,89-113,202, Search's at 838,98-939,199 and the Reels profile
 * button's at 962,98-1063,199.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w384dp-h823dp-xxhdpi")
public class ViewerTopBarTest {
    private static final int BAR_ID = 0x7f0a22e7;

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Activity activity;
    private LithoView bar;
    private ComponentHost row;
    private ComponentHost back;
    private ComponentHost search;
    private ComponentHost profile;

    @Before
    public void setUp() {
        ViewerTopBar.reset();
        HookStatus.clear();
        ViewerTopBar.barIdForTests = BAR_ID;
        activity = Robolectric.buildActivity(ImmersiveActivity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        place(root, new FrameLayout(activity), 0, 0, 1080, 2181);
        bar = new LithoView(activity);
        bar.setId(BAR_ID);
        place(root, bar, 0, 0, 1080, 338);
        ComponentHost column = new ComponentHost(activity);
        place(bar, column, 0, 0, 1080, 338);
        row = new ComponentHost(activity);
        place(column, row, 0, 0, 1080, 199);
        ComponentHost backSlot = new ComponentHost(activity);
        place(row, backSlot, 17, 112, 131, 186);
        ComponentHost backIcon = new ComponentHost(activity);
        place(backSlot, backIcon, 6, 0, 74, 68);
        ComponentHost backTouch = new ComponentHost(activity);
        place(backIcon, backTouch, -28, -28, 96, 96);
        back = button(backTouch, 5, 5, 118, 118);
        search = button(row, 838, 98, 939, 199);
        profile = button(row, 962, 98, 1063, 199);
        activity.setContentView(root);
        layOut();
    }

    @After
    public void tearDown() {
        PauseForTests.resume();
        Settings.HIDE_VIEWER_TOP_BUTTONS.resetToDefault();
        Settings.HIDE_REELS_TAB_TITLE.resetToDefault();
        ViewerTopBar.reset();
        HookStatus.clear();
    }

    @Test
    public void hidesSearchAndTheButtonBesideItAndKeepsBack() {
        Settings.HIDE_VIEWER_TOP_BUTTONS.save(true);

        assertEquals(2, ViewerTopBar.tidy(bar));

        assertEquals(View.VISIBLE, back.getVisibility());
        assertEquals(View.INVISIBLE, search.getVisibility());
        assertEquals(View.INVISIBLE, profile.getVisibility());
        assertTrue(reported(ViewerTopBar.HIDDEN));
    }

    /** #362: the text of the tab's title sits in a host of its own beside Menu. */
    @Test
    public void theReelsTitleGoesOnTheTabAndNothingElseDoes() {
        ComponentHost title = new ComponentHost(activity);
        place(row, title, 160, 98, 360, 199);
        layOut();
        assertEquals(0, ViewerTopBar.tidy(bar, true));
        assertEquals("the title went with its switch off", View.VISIBLE, title.getVisibility());

        Settings.HIDE_REELS_TAB_TITLE.save(true);
        assertEquals("a bar that isn't the tab's lost its title", 0, ViewerTopBar.tidy(bar));
        assertEquals(View.VISIBLE, title.getVisibility());

        assertEquals("only the buttons count", 0, ViewerTopBar.tidy(bar, true));
        assertEquals(View.INVISIBLE, title.getVisibility());
        assertEquals("Menu went with the title", View.VISIBLE, back.getVisibility());
        assertEquals("Search went with the title", View.VISIBLE, search.getVisibility());
        assertEquals(View.VISIBLE, profile.getVisibility());
        assertTrue(reported(ViewerTopBar.TITLE_HIDDEN));

        Settings.HIDE_REELS_TAB_TITLE.save(false);
        ViewerTopBar.tidy(bar, true);
        assertEquals("the title stayed away with the switch off", View.VISIBLE, title.getVisibility());
    }

    @Test
    public void theOpenScreenTakesTheButtonsAfterALayout() {
        Settings.HIDE_VIEWER_TOP_BUTTONS.save(true);

        ViewerTopBar.resumed(activity);
        layOut();

        assertEquals(View.VISIBLE, back.getVisibility());
        assertEquals(View.INVISIBLE, search.getVisibility());
        assertEquals(View.INVISIBLE, profile.getVisibility());
    }

    @Test
    public void theReelsTabsHeaderLosesSearchAndTheProfileButtonAndKeepsMenu() {
        // #346: the tab's header is the same bar inside the tab pages, Menu where Back would be.
        Settings.HIDE_VIEWER_TOP_BUTTONS.save(true);
        Activity main = Robolectric.buildActivity(Activity.class).setup().get();
        ComponentHost[] header = reelsTab(main);

        ViewerTopBar.resumed(main);
        layOut(main);

        assertEquals(View.VISIBLE, header[0].getVisibility());
        assertEquals(View.INVISIBLE, header[1].getVisibility());
        assertEquals(View.INVISIBLE, header[2].getVisibility());

        Settings.HIDE_VIEWER_TOP_BUTTONS.save(false);
        layOut(main);

        assertEquals(View.VISIBLE, header[1].getVisibility());
        assertEquals(View.VISIBLE, header[2].getVisibility());
    }

    @Test
    public void aFeedVideoOpenedOverTheMainScreenTakesTheButtons() {
        // S22, 585: the player is its own layer of the content view, beside the tabs, and its bar
        // has the id the Reels tab's header has inside the pages (#311).
        Settings.HIDE_VIEWER_TOP_BUTTONS.save(true);
        Activity main = Robolectric.buildActivity(Activity.class).setup().get();
        ComponentHost[] header = reelsTab(main);
        FrameLayout player = new FrameLayout(main);
        main.addContentView(player, new FrameLayout.LayoutParams(1080, 2181));
        LithoView playerBar = new LithoView(main);
        playerBar.setId(BAR_ID);
        place(player, playerBar, 0, 0, 1080, 338);
        ComponentHost playerBack = button(playerBar, 23, 112, 91, 180);
        ComponentHost playerSearch = button(playerBar, 838, 98, 939, 199);
        ComponentHost playerMore = button(playerBar, 962, 98, 1063, 199);

        ViewerTopBar.resumed(main);
        layOut(main);

        assertEquals(View.VISIBLE, playerBack.getVisibility());
        assertEquals(View.INVISIBLE, playerSearch.getVisibility());
        assertEquals(View.INVISIBLE, playerMore.getVisibility());
        assertEquals(View.VISIBLE, header[0].getVisibility());
        assertEquals(View.INVISIBLE, header[1].getVisibility());
    }

    @Test
    public void switchingOffBringsThemBack() {
        Settings.HIDE_VIEWER_TOP_BUTTONS.save(true);
        ViewerTopBar.tidy(bar);
        Settings.HIDE_VIEWER_TOP_BUTTONS.save(false);

        assertEquals(0, ViewerTopBar.tidy(bar));

        assertEquals(View.VISIBLE, search.getVisibility());
        assertEquals(View.VISIBLE, profile.getVisibility());
    }

    @Test
    public void offOrPausedLeavesTheBarAsItIs() {
        assertEquals(0, ViewerTopBar.tidy(bar));
        assertEquals(View.VISIBLE, search.getVisibility());

        Settings.HIDE_VIEWER_TOP_BUTTONS.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);

        assertEquals(0, ViewerTopBar.tidy(bar));
        assertEquals(View.VISIBLE, search.getVisibility());
        assertEquals(View.VISIBLE, profile.getVisibility());
    }

    @Test
    public void aHostLithoTakesAwayComesBackVisible() {
        Settings.HIDE_VIEWER_TOP_BUTTONS.save(true);
        ViewerTopBar.tidy(bar);

        row.removeView(search);

        assertEquals(View.VISIBLE, search.getVisibility());
        assertEquals(View.INVISIBLE, profile.getVisibility());
    }

    @Test
    public void aButtonBelowBacksRowStays() {
        Settings.HIDE_VIEWER_TOP_BUTTONS.save(true);
        ComponentHost lower = button((ViewGroup) row.getParent(), 900, 220, 1060, 320);
        layOut();

        ViewerTopBar.tidy(bar);

        assertEquals(View.VISIBLE, lower.getVisibility());
        assertEquals(View.INVISIBLE, search.getVisibility());
    }

    @Test
    public void aBarWithNothingOnBacksSideIsLeftAlone() {
        Settings.HIDE_VIEWER_TOP_BUTTONS.save(true);
        back.setClickable(false);

        assertEquals(0, ViewerTopBar.tidy(bar));

        assertEquals(View.VISIBLE, search.getVisibility());
        assertEquals(View.VISIBLE, profile.getVisibility());
    }

    @Test
    public void rightToLeftHidesTheButtonsOnTheLeft() {
        Settings.HIDE_VIEWER_TOP_BUTTONS.save(true);
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        ComponentHost backSlot = (ComponentHost) row.getChildAt(0);
        row.removeView(backSlot);
        place(row, backSlot, 949, 112, 1063, 186);
        row.removeView(search);
        row.removeView(profile);
        ComponentHost leftSearch = button(row, 141, 98, 242, 199);
        ComponentHost leftProfile = button(row, 17, 98, 118, 199);
        layOut();

        assertEquals(2, ViewerTopBar.tidy(bar));

        assertEquals(View.VISIBLE, back.getVisibility());
        assertEquals(View.INVISIBLE, leftSearch.getVisibility());
        assertEquals(View.INVISIBLE, leftProfile.getVisibility());
    }

    private boolean reported(String counted) {
        for (String line : HookStatus.report()) {
            if (line.contains(FamilyNames.REEL_DECLUTTER) && line.contains(counted)) return true;
        }
        return false;
    }

    /**
     * The main screen with the Reels tab showing, as 585 lays it out: its header, Menu, Search and
     * the profile button, inside the tab pages that slide sideways. Answers those three buttons.
     */
    private static ComponentHost[] reelsTab(Activity main) {
        HorizontalScrollView tabs = new HorizontalScrollView(main);
        main.setContentView(tabs);
        FrameLayout page = new FrameLayout(main);
        tabs.addView(page, new FrameLayout.LayoutParams(1080, 2106));
        LithoView header = new LithoView(main);
        header.setId(BAR_ID);
        place(page, header, 0, 0, 1080, 338);
        return new ComponentHost[]{
                button(header, 11, 45, 135, 146),
                button(header, 838, 45, 939, 146),
                button(header, 962, 45, 1063, 146)};
    }

    private static ComponentHost button(ViewGroup parent, int left, int top, int right, int bottom) {
        ComponentHost host = new ComponentHost(parent.getContext());
        host.setClickable(true);
        place(parent, host, left, top, right, bottom);
        return host;
    }

    private static void place(ViewGroup parent, View child, int left, int top, int right, int bottom) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(right - left, bottom - top, Gravity.LEFT | Gravity.TOP);
        params.leftMargin = left;
        params.topMargin = top;
        parent.addView(child, params);
    }

    private void layOut() {
        layOut(activity);
    }

    private static void layOut(Activity screen) {
        screen.getWindow().getDecorView().requestLayout();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
    }
}
