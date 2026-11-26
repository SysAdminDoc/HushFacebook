/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Insets;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.facebook.feed.tab.FeedTab;
import com.facebook.video.videohome.tab.WatchTab;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.SettingReadsForTests;

/**
 * Hide the tab bar on the Reels tab: with the switch on, selecting Reels hides the bar, the Reels
 * page and its reels keep only the room the phone's navigation bar takes (#516), and selecting
 * another tab brings the bar back while the other pages keep their margin. The bar and the margin
 * stay Facebook's with the switch off, paused, before the settings are ready and when the switch
 * can't be read.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReelsTabBarHiddenTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int BAR = 200;
    /** The phone's navigation bar along the bottom, which Facebook's bar height includes. */
    private static final int STRIP = 63;

    private View bar;

    @Before
    public void start() {
        HookStatus.clear();
        bar = new View(RuntimeEnvironment.getApplication());
        ReelsTabBarHidden.insetsForTests = navigationBar(STRIP);
    }

    @After
    public void restore() {
        Settings.HIDE_TAB_BAR_LINE.resetToDefault();
        ReelsTabBarHidden.tabShown(bar, new FeedTab());
        PauseForTests.resume();
        Settings.HIDE_REELS_TAB_BAR.resetToDefault();
        ReelsTabBarHidden.insetsForTests = null;
        ReelsTabBarHidden.forgetWindow();
        TabBarScrollAway.Stored.BAR_ROOM.resetToDefault();
        TabBarScrollAway.Stored.WIDE_BAR_ROOM.resetToDefault();
        HookStatus.clear();
    }

    private static WindowInsets navigationBar(int height) {
        return new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, height))
                .build();
    }

    private void assertFacebooksBar(String why) {
        ReelsTabBarHidden.tabShown(bar, new WatchTab());
        assertEquals(why, View.VISIBLE, bar.getVisibility());
        assertEquals(why, BAR, ReelsTabBarHidden.pageBottomMargin(BAR, new WatchTab()));
        assertEquals(why, BAR, ReelsTabBarHidden.reelBottomBars(BAR));
    }

    @Test
    public void theSwitchStartsOffInTheTabBarFamilyAndNeedsARestart() {
        assertFalse(Settings.HIDE_REELS_TAB_BAR.defaultValue);
        assertTrue("Facebook sets the Reels page's margin as it's set up", Settings.HIDE_REELS_TAB_BAR.rebootApp);
        assertTrue(PatchFamily.BOTTOM_TAB_BAR.switches.contains(Settings.HIDE_REELS_TAB_BAR));
        assertFacebooksBar("the bar went with the switch off");
    }

    @Test
    public void theReelsTabLosesTheBarAndTheOtherTabsGetItBack() {
        Settings.HIDE_REELS_TAB_BAR.save(true);
        ReelsTabBarHidden.tabShown(bar, new WatchTab());
        assertEquals("the bar stayed on the Reels tab", View.GONE, bar.getVisibility());
        assertEquals("the Reels page kept the bar's margin", STRIP, ReelsTabBarHidden.pageBottomMargin(BAR, new WatchTab()));
        assertEquals("Home's page lost the bar's margin", BAR, ReelsTabBarHidden.pageBottomMargin(BAR, new FeedTab()));
        assertEquals("the reel stayed short of the hidden bar", STRIP, ReelsTabBarHidden.reelBottomBars(BAR));

        ReelsTabBarHidden.tabShown(bar, new FeedTab());
        assertEquals("Home didn't get the bar back", View.VISIBLE, bar.getVisibility());
        assertEquals("Home's page lost the bar's margin", BAR, ReelsTabBarHidden.pageBottomMargin(BAR, new FeedTab()));
    }

    /** Facebook sizes the Reels tab's reels at start, while Home is showing and the bar is up. */
    @Test
    public void reelsSizedBeforeTheReelsTabShowsRunToTheBottom() {
        Settings.HIDE_REELS_TAB_BAR.save(true);
        ReelsTabBarHidden.tabShown(bar, new FeedTab());
        assertEquals("Home lost its bar", View.VISIBLE, bar.getVisibility());
        assertEquals("a reel sized before Reels showed stopped short of the bar", STRIP, ReelsTabBarHidden.reelBottomBars(BAR));
    }

    /**
     * #516: Facebook's bar height includes the phone's navigation bar, so answering 0 ran the reel,
     * its time and its seek bar down under the gesture bar, where they couldn't be tapped or held.
     */
    @Test
    public void theReelStopsAboveThePhonesNavigationBar() {
        Settings.HIDE_REELS_TAB_BAR.save(true);
        ReelsTabBarHidden.tabShown(bar, new WatchTab());
        assertEquals("the reel ran under the navigation bar", STRIP, ReelsTabBarHidden.reelBottomBars(BAR));
        assertEquals("the page ran under the navigation bar", STRIP, ReelsTabBarHidden.pageBottomMargin(BAR, new WatchTab()));
        assertEquals("a bar shorter than the strip grew", 40, ReelsTabBarHidden.reelBottomBars(40));

        ReelsTabBarHidden.insetsForTests = navigationBar(0);
        assertEquals("a phone with no navigation bar kept a gap", 0, ReelsTabBarHidden.reelBottomBars(BAR));
    }

    /** A strip already read stands for a call that comes before the window can say. */
    @Test
    public void theLastStripReadStandsUntilTheWindowCanSay() {
        Settings.HIDE_REELS_TAB_BAR.save(true);
        ReelsTabBarHidden.insetsForTests = null;
        assertEquals("a reel sized before any window was known kept a gap", 0, ReelsTabBarHidden.reelBottomBars(BAR));
        ReelsTabBarHidden.insetsForTests = navigationBar(STRIP);
        ReelsTabBarHidden.reelBottomBars(BAR);
        ReelsTabBarHidden.insetsForTests = null;
        ReelsTabBarHidden.tabShown(bar, new WatchTab());
        assertEquals("the strip was forgotten", STRIP, ReelsTabBarHidden.reelBottomBars(BAR));
    }

    /** A window that already stops above the navigation bar (no edge to edge) needs no room for it. */
    @Test
    public void aWindowThatStopsAboveTheNavigationBarGetsNoRoomForIt() {
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout root = new FrameLayout(context);
        FrameLayout content = new FrameLayout(context);
        content.setId(android.R.id.content);
        root.addView(content);
        content.addView(bar);
        root.layout(0, 0, 1080, 2400);
        content.layout(0, 0, 1080, 2400 - STRIP);
        Settings.HIDE_REELS_TAB_BAR.save(true);
        ReelsTabBarHidden.tabShown(bar, new WatchTab());
        assertEquals("a window above the navigation bar left a gap", 0, ReelsTabBarHidden.reelBottomBars(BAR));

        content.layout(0, 0, 1080, 2400);
        assertEquals("an edge to edge window ran under the navigation bar", STRIP, ReelsTabBarHidden.reelBottomBars(BAR));
    }

    @Test
    public void aBarFacebookHidItselfStaysHidden() {
        Settings.HIDE_REELS_TAB_BAR.save(true);
        bar.setVisibility(View.GONE);
        ReelsTabBarHidden.tabShown(bar, new FeedTab());
        assertEquals("a bar this didn't hide was shown", View.GONE, bar.getVisibility());
        ReelsTabBarHidden.tabShown(null, new WatchTab());
        ReelsTabBarHidden.tabShown(bar, null);
        assertEquals("a page with no tab lost its margin", BAR, ReelsTabBarHidden.pageBottomMargin(BAR, null));
        assertEquals("a page with no margin got one", 0, ReelsTabBarHidden.pageBottomMargin(0, new WatchTab()));
        assertEquals("a reel with no bars below got a height back", 0, ReelsTabBarHidden.reelBottomBars(0));
    }

    @Test
    public void facebooksBarStandsWhilePausedOrBeforeTheSettingsAreReady() {
        Settings.HIDE_REELS_TAB_BAR.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFacebooksBar("the bar went while paused");
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFacebooksBar("the bar went before the settings were ready"));
    }

    @Test
    public void eachChangeIsCounted() {
        Settings.HIDE_REELS_TAB_BAR.save(true);
        ReelsTabBarHidden.tabShown(bar, new WatchTab());
        ReelsTabBarHidden.pageBottomMargin(BAR, new WatchTab());
        ReelsTabBarHidden.pageBottomMargin(BAR, new WatchTab());
        ReelsTabBarHidden.pageBottomMargin(BAR, new FeedTab());
        ReelsTabBarHidden.reelBottomBars(BAR);
        ReelsTabBarHidden.reelBottomBars(BAR);
        ReelsTabBarHidden.reelBottomBars(BAR);
        String line = line();
        assertNotNull("no " + FamilyNames.BOTTOM_TAB_BAR + " line: " + HookStatus.report(), line);
        assertTrue(line, line.contains(ReelsTabBarHidden.HIDDEN + " 1"));
        assertTrue(line, line.contains(ReelsTabBarHidden.UNPADDED + " 2"));
        assertTrue(line, line.contains(ReelsTabBarHidden.REEL_SIZED + " 3"));
    }

    /** A switch that can't be read leaves Facebook's bar, and the report names the hook. */
    @Test
    public void aSwitchThatCantBeReadLeavesFacebooksBar() {
        SettingReadsForTests.breakReads(Settings.HIDE_REELS_TAB_BAR);
        try {
            assertFacebooksBar("the bar went with a switch that can't be read");
        } finally {
            SettingReadsForTests.mend(Settings.HIDE_REELS_TAB_BAR);
        }
        String missing = HookStatus.missing(FamilyNames.BOTTOM_TAB_BAR).toString();
        assertTrue(missing, missing.contains("a working '" + ReelsTabBarHidden.HOOK + "' hook (it threw "
                + NullPointerException.class.getName() + ")"));
    }

    /** The bar as Facebook nests it with its tabs on top, inside a holder under the page's header. */
    private FrameLayout headerAroundTheBar() {
        Context context = RuntimeEnvironment.getApplication();
        LinearLayout inner = new LinearLayout(context);
        inner.addView(bar);
        FrameLayout holder = new FrameLayout(context);
        holder.addView(inner);
        FrameLayout header = new FrameLayout(context);
        header.addView(holder, ViewGroup.LayoutParams.MATCH_PARENT, 116);
        return header;
    }

    private static View addLine(ViewGroup to) {
        View line = new View(to.getContext());
        to.addView(line, ViewGroup.LayoutParams.MATCH_PARENT, 2);
        return line;
    }

    /** #508: with the tabs on top, Facebook's divider stayed behind as a grey line under the title. */
    @Test
    public void theLineUnderTheTitleGoesWithTheBarAndComesBackWithIt() {
        View line = addLine(headerAroundTheBar());
        line.setAlpha(0.5f);
        Settings.HIDE_REELS_TAB_BAR.save(true);
        ReelsTabBarHidden.tabShown(bar, new WatchTab());
        assertEquals("the line stayed on the Reels tab", 0f, line.getAlpha(), 0f);
        assertEquals("Facebook's own visibility was changed", View.VISIBLE, line.getVisibility());

        ReelsTabBarHidden.tabShown(bar, new WatchTab());
        ReelsTabBarHidden.tabShown(bar, new FeedTab());
        assertEquals("Home didn't get its line back as it was", 0.5f, line.getAlpha(), 0f);
    }

    @Test
    public void aLineInsideTheBarsOwnHolderGoesToo() {
        FrameLayout header = headerAroundTheBar();
        View line = addLine((ViewGroup) header.getChildAt(0));
        Settings.HIDE_REELS_TAB_BAR.save(true);
        ReelsTabBarHidden.tabShown(bar, new WatchTab());
        assertEquals(0f, line.getAlpha(), 0f);
    }

    @Test
    public void onlyAThinFullWidthLineGoesNotWhatElseSitsNearTheBar() {
        FrameLayout header = headerAroundTheBar();
        Context context = header.getContext();
        View tall = new View(context);
        header.addView(tall, ViewGroup.LayoutParams.MATCH_PARENT, 116);
        View narrow = new View(context);
        header.addView(narrow, 40, 2);
        FrameLayout thinGroup = new FrameLayout(context);
        header.addView(thinGroup, ViewGroup.LayoutParams.MATCH_PARENT, 2);
        Settings.HIDE_REELS_TAB_BAR.save(true);
        ReelsTabBarHidden.tabShown(bar, new WatchTab());
        assertEquals("a tall view went", 1f, tall.getAlpha(), 0f);
        assertEquals("a short narrow view went", 1f, narrow.getAlpha(), 0f);
        assertEquals("a thin layout went", 1f, thinGroup.getAlpha(), 0f);
    }

    @Test
    public void theLineStaysFacebooksWithTheSwitchOffAndComesBackOnAPause() {
        View line = addLine(headerAroundTheBar());
        ReelsTabBarHidden.tabShown(bar, new WatchTab());
        assertEquals("the line went with the switch off", 1f, line.getAlpha(), 0f);

        Settings.HIDE_REELS_TAB_BAR.save(true);
        ReelsTabBarHidden.tabShown(bar, new WatchTab());
        assertEquals(0f, line.getAlpha(), 0f);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        ReelsTabBarHidden.tabShown(bar, new WatchTab());
        assertEquals("a pause left the line hidden", 1f, line.getAlpha(), 0f);
    }

    /** #521: the line along the bar goes on every tab with its own switch, and comes back with it off. */
    @Test
    public void theLineAlongTheBarGoesOnEveryTabWithItsOwnSwitch() {
        View line = addLine(headerAroundTheBar());
        line.setAlpha(0.5f);
        assertFalse(Settings.HIDE_TAB_BAR_LINE.defaultValue);
        assertFalse("the next tab tapped shows the change", Settings.HIDE_TAB_BAR_LINE.rebootApp);
        assertTrue(PatchFamily.BOTTOM_TAB_BAR.switches.contains(Settings.HIDE_TAB_BAR_LINE));
        ReelsTabBarHidden.tabShown(bar, new FeedTab());
        assertEquals("the line went with the switch off", 0.5f, line.getAlpha(), 0f);

        Settings.HIDE_TAB_BAR_LINE.save(true);
        ReelsTabBarHidden.tabShown(bar, new FeedTab());
        assertEquals("Home kept the line", 0f, line.getAlpha(), 0f);
        assertEquals("Facebook's own visibility was changed", View.VISIBLE, line.getVisibility());
        ReelsTabBarHidden.tabShown(bar, new WatchTab());
        assertEquals("Reels kept the line", 0f, line.getAlpha(), 0f);
        assertEquals("the bar went on Reels with only the line's switch on", View.VISIBLE, bar.getVisibility());

        Settings.HIDE_TAB_BAR_LINE.save(false);
        ReelsTabBarHidden.tabShown(bar, new FeedTab());
        assertEquals("the line didn't come back as it was", 0.5f, line.getAlpha(), 0f);
    }

    /** With both switches on, the bar comes back off Reels and the line stays gone. */
    @Test
    public void theLineStaysGoneWhenTheReelsBarComesBack() {
        View line = addLine(headerAroundTheBar());
        Settings.HIDE_REELS_TAB_BAR.save(true);
        Settings.HIDE_TAB_BAR_LINE.save(true);
        ReelsTabBarHidden.tabShown(bar, new WatchTab());
        ReelsTabBarHidden.tabShown(bar, new FeedTab());
        assertEquals("Home didn't get the bar back", View.VISIBLE, bar.getVisibility());
        assertEquals("the line came back with the bar", 0f, line.getAlpha(), 0f);
    }

    @Test
    public void theLineStaysFacebooksWhilePausedOrBeforeTheSettingsAreReady() {
        View line = addLine(headerAroundTheBar());
        Settings.HIDE_TAB_BAR_LINE.save(true);
        ReelsTabBarHidden.tabShown(bar, new FeedTab());
        assertEquals(0f, line.getAlpha(), 0f);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        ReelsTabBarHidden.tabShown(bar, new FeedTab());
        assertEquals("a pause left the line hidden", 1f, line.getAlpha(), 0f);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> {
            ReelsTabBarHidden.tabShown(bar, new FeedTab());
            assertEquals("the line went before the settings were ready", 1f, line.getAlpha(), 0f);
        });
    }

    @Test
    public void aFadedLineIsCountedOnce() {
        addLine(headerAroundTheBar());
        Settings.HIDE_TAB_BAR_LINE.save(true);
        ReelsTabBarHidden.tabShown(bar, new FeedTab());
        ReelsTabBarHidden.tabShown(bar, new FeedTab());
        String found = null;
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.BOTTOM_TAB_BAR + ":") && line.contains(ReelsTabBarHidden.LINE_HIDDEN)) found = line;
        }
        assertNotNull("no faded line counted: " + HookStatus.report(), found);
        assertTrue(found, found.contains(ReelsTabBarHidden.LINE_HIDDEN + " 1"));
    }

    /** #521: Facebook doesn't restyle the bar as it starts, so the line stayed until a tab was tapped. */
    @Test
    public void theLineGoesAtTheBarsLayoutBeforeAnyTabIsTapped() {
        View line = addLine(headerAroundTheBar());
        line.setAlpha(0.5f);
        ReelsTabBarHidden.barLaidOut(bar);
        assertEquals("the line went with the switch off", 0.5f, line.getAlpha(), 0f);

        Settings.HIDE_TAB_BAR_LINE.save(true);
        ReelsTabBarHidden.barLaidOut(null);
        ReelsTabBarHidden.barLaidOut(bar);
        assertEquals("the line stayed until a tab was tapped", 0f, line.getAlpha(), 0f);
        assertEquals("Facebook's own visibility was changed", View.VISIBLE, line.getVisibility());
        ReelsTabBarHidden.barLaidOut(bar);
        boolean countedOnce = false;
        for (String reported : HookStatus.report()) {
            if (reported.startsWith(FamilyNames.BOTTOM_TAB_BAR + ":") && reported.contains(ReelsTabBarHidden.LINE_HIDDEN + " 1")) {
                countedOnce = true;
            }
        }
        assertTrue("a second layout faded the line again: " + HookStatus.report(), countedOnce);

        Settings.HIDE_TAB_BAR_LINE.save(false);
        ReelsTabBarHidden.tabShown(bar, new FeedTab());
        assertEquals("the line didn't come back as it was", 0.5f, line.getAlpha(), 0f);
    }

    @Test
    public void theBarsLayoutLeavesTheLineWhilePausedOrBeforeTheSettingsAreReady() {
        View line = addLine(headerAroundTheBar());
        Settings.HIDE_TAB_BAR_LINE.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        ReelsTabBarHidden.barLaidOut(bar);
        assertEquals("the line went while paused", 1f, line.getAlpha(), 0f);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> {
            ReelsTabBarHidden.barLaidOut(bar);
            assertEquals("the line went before the settings were ready", 1f, line.getAlpha(), 0f);
        });
    }

    private static String line() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.BOTTOM_TAB_BAR + ":") && line.contains(ReelsTabBarHidden.HIDDEN)) return line;
        }
        return null;
    }
}
