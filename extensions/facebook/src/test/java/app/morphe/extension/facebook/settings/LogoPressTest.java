/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

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
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowLooper;

/**
 * A long press on the Facebook logo at the top of the home feed opens the Hushfacebook screen. The
 * settings patch sends Facebook's call that gives the logo its touch listener to
 * {@link SettingsEntry#setLogoTouchListener}, right after Facebook gives the logo its tap. These
 * build the logo the way Facebook does, a view with Facebook's click listener, then make that call.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class LogoPressTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final SettingsEntry.OpenWhenResumed watcher = new SettingsEntry.OpenWhenResumed();
    private ActivityController<Activity> feed;
    /** Taps Facebook's own click listener saw. */
    private int taps;

    @Before public void openFeed() {
        RuntimeEnvironment.getApplication().registerActivityLifecycleCallbacks(watcher);
        feed = Robolectric.buildActivity(Activity.class).setup();
        ShadowLog.clear();
    }

    @After public void closeFeed() {
        RuntimeEnvironment.getApplication().unregisterActivityLifecycleCallbacks(watcher);
        PauseForTests.resume();
        Settings.SETTINGS_LOGO_PRESS.resetToDefault();
        Settings.SETTINGS_SHORTCUT.resetToDefault();
        Settings.SETTINGS_MENU_ROW.resetToDefault();
        PatchFamily.inBuildForTests = null;
        feed.pause().stop().destroy();
    }

    /** PausedHooksTest's probe: a finger put down on the logo is timed for a long press. */
    static boolean aPressIsTimed() {
        View logo = new View(RuntimeEnvironment.getApplication());
        SettingsEntry.LogoPress press = new SettingsEntry.LogoPress();
        press.onTouch(logo, MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1, 1, 0));
        boolean timing = press.timing();
        press.onTouch(logo, MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 1, 1, 0));
        return timing;
    }

    /**
     * Switched off, the long press is Facebook's again (#146): nothing opens, no touch is taken
     * from the logo, and the hold ends in Facebook's tap, as it does without Hushfacebook.
     */
    @Test public void withTheSwitchOffALongPressIsFacebooks() {
        Settings.SETTINGS_LOGO_PRESS.save(false);
        View logo = logo();
        SettingsEntry.setLogoTouchListener(logo, null);

        press(logo, longPress());

        assertNull("a long press opened the screen with its switch off", screen());
        assertEquals("the hold wasn't Facebook's tap", 1, taps);
        assertFalse(aPressIsTimed());
    }

    /** Switched back on, the same logo opens the screen again, with no new listener. */
    @Test public void theSwitchTakesEffectOnTheLogoAlreadyOnScreen() {
        View logo = logo();
        SettingsEntry.setLogoTouchListener(logo, null);
        Settings.SETTINGS_LOGO_PRESS.save(false);
        press(logo, longPress());
        assertNull(screen());

        Settings.SETTINGS_LOGO_PRESS.save(true);
        press(logo, longPress());
        assertNotNull("on again didn't open the screen", screen());
    }

    /** With every way in stored off, as a backup could leave it, the logo still opens the settings. */
    @Test public void withEveryWayInStoredOffTheLongPressStillOpens() {
        Settings.SETTINGS_LOGO_PRESS.save(false);
        Settings.SETTINGS_SHORTCUT.save(false);
        Settings.SETTINGS_MENU_ROW.save(false);
        View logo = logo();
        SettingsEntry.setLogoTouchListener(logo, null);

        press(logo, longPress());

        assertNotNull("no way in was left", screen());
    }

    /** The switch is kept while paused: the long press still opens the settings, where a pause is lifted. */
    @Test public void aPauseLeavesTheLongPressAsItsSwitchSays() {
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertTrue("a pause took the way back to the settings", aPressIsTimed());
        Settings.SETTINGS_LOGO_PRESS.save(false);
        assertFalse("a pause brought a switched-off long press back", aPressIsTimed());
    }

    /** The logo as Facebook builds it, in the feed's activity: a view with Facebook's tap. */
    private View logo() {
        return logo(feed.get());
    }

    private View logo(android.content.Context context) {
        View logo = new View(context);
        logo.setOnClickListener(v -> taps++);
        FrameLayout bar = new FrameLayout(feed.get());
        bar.addView(logo, new FrameLayout.LayoutParams(360, 90));
        feed.get().setContentView(bar);
        ShadowLooper.idleMainLooper();
        assertTrue("the logo isn't on screen", logo.isAttachedToWindow() && logo.getWidth() == 360);
        return logo;
    }

    private static MotionEvent event(int action, long downTime, float x, float y) {
        return MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
    }

    /** A finger held on the logo for [heldMs], then lifted. */
    private static void press(View logo, long heldMs) {
        long down = SystemClock.uptimeMillis();
        logo.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, down, 180, 45));
        ShadowLooper.idleMainLooper(heldMs, TimeUnit.MILLISECONDS);
        logo.dispatchTouchEvent(event(MotionEvent.ACTION_UP, down, 180, 45));
        ShadowLooper.idleMainLooper();
    }

    private static long longPress() {
        return ViewConfiguration.getLongPressTimeout() + 100;
    }

    private Object screen() {
        return feed.get().getFragmentManager().findFragmentByTag("hushfacebook_settings");
    }

    private static List<String> logged() {
        List<String> lines = new ArrayList<>();
        for (ShadowLog.LogItem item : ShadowLog.getLogsForTag("morphe: SettingsEntry")) lines.add(item.msg);
        return lines;
    }

    @Test public void aLongPressOpensTheScreenOverTheFeed() {
        View logo = logo();
        SettingsEntry.setLogoTouchListener(logo, null);

        press(logo, longPress());

        assertNotNull("a long press on the logo didn't open the screen", screen());
        assertEquals("a long press gave no long-press vibration",
                HapticFeedbackConstants.LONG_PRESS, shadowOf(logo).lastHapticFeedbackPerformed());
        assertEquals("letting go after the long press was also a tap on the logo", 0, taps);
        assertTrue("no line said the logo was watched: " + logged(),
                logged().contains("Settings entry: a long press on the Facebook logo opens the settings"));
        assertTrue("no line said the long press asked for the screen: " + logged(),
                logged().contains("Settings requested by a long press on the Facebook logo"));
    }

    @Test public void aTapIsStillFacebooks() {
        View logo = logo();
        SettingsEntry.setLogoTouchListener(logo, null);

        press(logo, 80);

        assertEquals("the tap didn't reach Facebook's click listener", 1, taps);
        assertNull("a tap opened the screen", screen());
    }

    @Test public void slidingOffTheLogoLetsGo() {
        View logo = logo();
        SettingsEntry.setLogoTouchListener(logo, null);
        long down = SystemClock.uptimeMillis();

        logo.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, down, 180, 45));
        logo.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, down, 180, 900));
        ShadowLooper.idleMainLooper(longPress(), TimeUnit.MILLISECONDS);
        logo.dispatchTouchEvent(event(MotionEvent.ACTION_UP, down, 180, 900));
        ShadowLooper.idleMainLooper();

        assertNull("a finger that slid off the logo still opened the screen", screen());
    }

    /**
     * Facebook gives the logo an accessibility delegate that puts back the long-press state it saw
     * before the logo had listeners, false, every time an accessibility service reads the screen.
     * A long-click listener would go dead after the first read, so the press is timed instead.
     */
    @Test public void aScreenReaderReadingTheLogoDoesntStopIt() {
        View logo = logo();
        logo.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                host.setLongClickable(false);
            }
        });
        SettingsEntry.setLogoTouchListener(logo, null);

        logo.createAccessibilityNodeInfo().recycle();
        press(logo, longPress());

        assertNotNull("the long press stopped working once the logo was read", screen());
        assertEquals(0, taps);
    }

    /**
     * Facebook passes a listener of its own when it reads the logo's gestures itself: its World
     * Cup mode took a double tap and a long press there. That long press stays Facebook's.
     */
    @Test public void facebooksOwnListenerKeepsItsLongPress() {
        View logo = logo();
        List<Integer> seen = new ArrayList<>();
        View.OnTouchListener facebooks = (v, event) -> {
            seen.add(event.getActionMasked());
            return true;
        };
        SettingsEntry.setLogoTouchListener(logo, facebooks);

        press(logo, longPress());

        assertNull("the long press was taken from Facebook's own listener", screen());
        assertEquals("Facebook's listener didn't get the whole press",
                java.util.Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), seen);
        assertTrue("no line said the long press stays Facebook's: " + logged(), logged().contains(
                "Settings entry: Facebook reads the logo's long press itself here, so it stays Facebook's"));
    }

    /** A long-click listener Facebook gives the logo itself is left to run. */
    @Test public void aLongPressFacebookGaveTheLogoStaysFacebooks() {
        View logo = logo();
        int[] facebooksLongPresses = {0};
        logo.setOnLongClickListener(v -> {
            facebooksLongPresses[0]++;
            return true;
        });
        SettingsEntry.setLogoTouchListener(logo, null);

        press(logo, longPress());

        assertEquals("Facebook's own long press didn't run", 1, facebooksLongPresses[0]);
        assertNull("the long press was taken from Facebook", screen());
    }

    /** No switch reads it, so it's still the way back to the switch while Hushfacebook is paused. */
    @Test public void pauseLeavesItWorking() {
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        View logo = logo();
        SettingsEntry.setLogoTouchListener(logo, null);

        press(logo, longPress());

        assertNotNull("Pause turned off the way back to its own switch", screen());
    }

    /**
     * A logo with no activity behind it can't show the screen. The long press then does what it
     * does without Hushfacebook: letting go is Facebook's tap. Nothing throws.
     */
    @Test public void aLongPressThatCantOpenTheScreenIsFacebooksTap() {
        View logo = logo(RuntimeEnvironment.getApplication());
        SettingsEntry.setLogoTouchListener(logo, null);

        press(logo, longPress());

        assertNull(screen());
        assertEquals("letting go wasn't Facebook's tap after the screen couldn't open", 1, taps);
        assertTrue("no line said why: " + logged(), logged().contains(
                "Settings entry: the Facebook logo isn't in an activity, so its long press is Facebook's"));
    }

    /** A press the listener can't follow is left to the logo, and doesn't throw into Facebook. */
    @Test public void aPressItCantFollowIsTheLogos() {
        View logo = logo();

        assertFalse("a touch it failed on wasn't left to the logo", new SettingsEntry.LogoPress().onTouch(logo, null));
    }

    /** Touches the bar itself got, past the logo. */
    private final List<Integer> barTouches = new ArrayList<>();

    /**
     * The logo as 583 leaves it on its path with no tap (#12, taken in Arabic), after the patch's
     * stand-in for that path's accessibility call: Facebook had disabled it and gives it no
     * listener. Touches go to the bar, which hands them on the way the phone does.
     */
    private FrameLayout barWithLogoWithoutTap() {
        View logo = new View(feed.get());
        logo.setEnabled(false);
        FrameLayout bar = new FrameLayout(feed.get());
        bar.setOnTouchListener((v, event) -> barTouches.add(event.getActionMasked()));
        bar.addView(logo, new FrameLayout.LayoutParams(360, 90));
        feed.get().setContentView(bar);
        ShadowLooper.idleMainLooper();
        SettingsEntry.setLogoWithoutTap(logo, View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return bar;
    }

    @Test public void aLogoWithNoTapOpensTheScreenOnALongPress() {
        FrameLayout bar = barWithLogoWithoutTap();
        View logo = bar.getChildAt(0);

        press(bar, longPress());

        assertNotNull("a long press on a logo with no tap didn't open the screen", screen());
        assertEquals("Facebook's importance wasn't kept", View.IMPORTANT_FOR_ACCESSIBILITY_NO, logo.getImportantForAccessibility());
        assertTrue("no line said the logo was watched: " + logged(), logged().contains(
                "Settings entry: Facebook gave the logo no tap here, so a long press on it opens the settings"));
    }

    /** The logo hears the finger lift, so a tap doesn't run out the long-press time and open the screen. */
    @Test public void aTapOnALogoWithNoTapOpensNothing() {
        FrameLayout bar = barWithLogoWithoutTap();

        press(bar, 80);
        ShadowLooper.idleMainLooper(longPress(), TimeUnit.MILLISECONDS);

        assertNull("a tap on a logo with no tap opened the screen", screen());
    }

    /** Taps on the title bar's menu button. */
    private int menuTaps;

    /**
     * A tab's title bar as the patch hands it over (#374): the bar, then a menu button with
     * Facebook's tap and the tab's title, a plain text view, side by side. [clickable] is whether
     * Facebook made the bar itself clickable.
     */
    private FrameLayout titleBar(boolean clickable) {
        FrameLayout bar = new FrameLayout(feed.get());
        bar.setClickable(clickable);
        View menu = new View(feed.get());
        menu.setOnClickListener(v -> menuTaps++);
        bar.addView(menu, new FrameLayout.LayoutParams(120, 90));
        TextView title = new TextView(feed.get());
        FrameLayout.LayoutParams titleAt = new FrameLayout.LayoutParams(400, 90);
        titleAt.leftMargin = 200;
        bar.addView(title, titleAt);
        feed.get().setContentView(bar);
        ShadowLooper.idleMainLooper();
        SettingsEntry.watchTitleBar(bar);
        return bar;
    }

    /** A finger held at [x] on the bar for [heldMs], then lifted. */
    private static void pressAt(View bar, float x, long heldMs) {
        long down = SystemClock.uptimeMillis();
        bar.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, down, x, 45));
        ShadowLooper.idleMainLooper(heldMs, TimeUnit.MILLISECONDS);
        bar.dispatchTouchEvent(event(MotionEvent.ACTION_UP, down, x, 45));
        ShadowLooper.idleMainLooper();
    }

    private void assertALongPressOnTheTitleOpens(boolean clickable) {
        FrameLayout bar = titleBar(clickable);

        pressAt(bar, 400, longPress());

        assertNotNull("a long press on the title didn't open the screen", screen());
        assertTrue("no line said the title bar was watched: " + logged(), logged().contains(
                "Settings entry: a long press on a tab's title bar opens the settings"));
        assertTrue("no line said the long press was on a title bar: " + logged(),
                logged().contains("Settings entry: a long press on a tab's title bar"));
    }

    /** A bar Facebook didn't make clickable has the whole touch taken, or it wouldn't hear the rest of it. */
    @Test public void aLongPressOnATabsTitleOpensTheScreen() {
        assertALongPressOnTheTitleOpens(false);
    }

    @Test public void aLongPressOnAClickableTitleBarOpensTheScreen() {
        assertALongPressOnTheTitleOpens(true);
    }

    /** The bar's buttons keep their taps, and a long press on one is the button's, not the bar's. */
    @Test public void theTitleBarsButtonsStayFacebooks() {
        FrameLayout bar = titleBar(false);

        pressAt(bar, 60, 80);
        assertEquals("the menu button lost its tap", 1, menuTaps);
        pressAt(bar, 60, longPress());

        assertNull("a long press on the menu button opened the screen", screen());
    }

    @Test public void aTapOnATabsTitleOpensNothing() {
        FrameLayout bar = titleBar(false);

        pressAt(bar, 400, 80);
        ShadowLooper.idleMainLooper(longPress(), TimeUnit.MILLISECONDS);

        assertNull("a tap on the title opened the screen", screen());
    }

    /** Switched off, the title bar is Facebook's again: a bar it didn't make clickable takes no touch. */
    @Test public void switchedOffATitleBarIsFacebooks() {
        Settings.SETTINGS_LOGO_PRESS.save(false);
        FrameLayout bar = titleBar(false);

        assertFalse("the bar took the touch with the switch off",
                bar.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, SystemClock.uptimeMillis(), 400, 45)));
        ShadowLooper.idleMainLooper(longPress(), TimeUnit.MILLISECONDS);
        assertNull(screen());
    }

    /** Switched off, the touch goes past the logo with no tap to the bar, as it does without Hushfacebook. */
    @Test public void switchedOffTheTouchGoesPastALogoWithNoTap() {
        Settings.SETTINGS_LOGO_PRESS.save(false);
        FrameLayout bar = barWithLogoWithoutTap();

        press(bar, longPress());

        assertNull("a long press opened the screen with its switch off", screen());
        assertEquals("the bar didn't get the touch",
                java.util.Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), barTouches);
    }
}
