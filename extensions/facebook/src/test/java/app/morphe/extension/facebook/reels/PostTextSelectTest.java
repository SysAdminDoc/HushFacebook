/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.theme.DarkMode;
import app.morphe.extension.facebook.theme.DarkModeForTests;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Select part of a post's text (#294): Facebook's long press on a post's text copies all of it, and
 * with the switch on that copy opens in a box whose text can be selected. Only a copy that lands
 * right after a hold opens it; the hold itself stays Facebook's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w1080dp-h2400dp-mdpi")
public class PostTextSelectTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String POST = "I took my baby's senior pics today. How is he this old?";

    private final List<String> facebook = new ArrayList<>();
    private ActivityController<Activity> window;
    private Activity activity;
    private ClipboardManager clipboard;
    private View text;

    @Before
    public void setUp() {
        PostLongPress.reset();
        PostTextSelect.reset();
        Settings.SELECT_POST_TEXT.save(true);
        window = Robolectric.buildActivity(Activity.class).setup();
        activity = window.get();
        FrameLayout root = new FrameLayout(activity);
        text = new View(activity);
        text.setOnLongClickListener(v -> {
            facebook.add("copy");
            return true;
        });
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1012, 123, Gravity.LEFT | Gravity.TOP);
        params.leftMargin = 34;
        params.topMargin = 400;
        root.addView(text, params);
        activity.setContentView(root);
        activity.getWindow().getDecorView().requestLayout();
        waitFor(100);
        window.windowFocusChanged(true);
        clipboard = activity.getApplicationContext().getSystemService(ClipboardManager.class);
    }

    @After
    public void restore() {
        PostLongPress.reset();
        PostTextSelect.reset();
        DarkModeForTests.forget();
        PauseForTests.resume();
        Settings.SELECT_POST_TEXT.resetToDefault();
        Settings.LONG_PRESS_OPENS_COMMENTS.resetToDefault();
        HookStatus.clear();
    }

    private static void waitFor(long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    private MotionEvent event(int action) {
        int[] at = new int[2];
        text.getLocationOnScreen(at);
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, action, at[0] + text.getWidth() / 2f, at[1] + text.getHeight() / 2f, 0);
    }

    /** Sends [event] the way the hook does, then on through the window, and hands it back. */
    private MotionEvent deliver(MotionEvent event) {
        PostLongPress.touch(activity, event);
        activity.getWindow().getDecorView().dispatchTouchEvent(event);
        return event;
    }

    /** Holds the text past the long-press time, as Facebook's copy does on a phone, and lifts. */
    private MotionEvent hold() {
        deliver(event(MotionEvent.ACTION_DOWN));
        waitFor(ViewConfiguration.getLongPressTimeout() + 50L);
        return deliver(event(MotionEvent.ACTION_UP));
    }

    /** Facebook's copy of [what], made through the stand-in the patch puts in place of its clipboard writes. */
    private void copy(String what) {
        PostTextSelect.copy(clipboard, ClipData.newPlainText("post", what));
        waitFor(0);
    }

    private String onClipboard() {
        CharSequence held = PostTextSelect.textOf(clipboard.getPrimaryClip());
        return held == null ? null : held.toString();
    }

    /** The first text in [view] that can be selected, or null. */
    private static TextView selectable(View view) {
        if (view instanceof TextView && ((TextView) view).isTextSelectable()) return (TextView) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            TextView found = selectable(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.REEL_GESTURES + ":")) return line;
        }
        return "";
    }

    @Test
    @Config(sdk = 30, qualifiers = "w1080dp-h2400dp-night-mdpi")
    public void theBoxTakesItsThemeFromTheModeAndNeverFromFacebooksDialogTheme() {
        // The activity's own theme is the light one, and the phone's night mode says dark.
        activity.setTheme(android.R.style.Theme_DeviceDefault_Light);
        DarkModeForTests.forget();
        assertFalse("night mode with no answer from Facebook gave a light box", boxIsLight());

        PostTextSelect.reset();
        DarkMode.answer(false);
        assertTrue("Facebook's light mode gave a dark box", boxIsLight());

        PostTextSelect.reset();
        activity.setTheme(android.R.style.Theme_DeviceDefault);
        DarkMode.answer(true);
        assertFalse("Facebook's dark mode gave a light box", boxIsLight());
    }

    private boolean boxIsLight() {
        hold();
        copy(POST);
        AlertDialog box = PostTextSelect.shownDialog();
        assertNotNull(box);
        TextView shown = selectable(box.getWindow().getDecorView());
        android.content.res.TypedArray theme =
                shown.getContext().obtainStyledAttributes(new int[]{android.R.attr.isLightTheme});
        boolean light = theme.getBoolean(0, false);
        theme.recycle();
        return light;
    }

    @Test
    public void theCopyAHoldMakesOpensInABoxToSelectAndTheHoldStaysFacebooks() {
        assertFalse("starts off", Settings.SELECT_POST_TEXT.defaultValue);
        assertEquals("the lift is Facebook's", MotionEvent.ACTION_UP, hold().getActionMasked());
        assertEquals("Facebook's long press ran", List.of("copy"), facebook);
        copy(POST);
        AlertDialog box = PostTextSelect.shownDialog();
        assertNotNull("the copy opened the box", box);
        TextView shown = selectable(box.getWindow().getDecorView());
        assertNotNull("its text can be selected", shown);
        assertEquals(POST, shown.getText().toString());
        assertTrue(statusLine(), statusLine().contains(PostTextSelect.OPENED + " 1"));
        box.dismiss();

        copy("copied again with no hold");
        assertNull("a copy with no hold opens nothing", PostTextSelect.shownDialog());
        assertEquals("a copy with no hold reaches the clipboard", "copied again with no hold", onClipboard());
    }

    @Test
    public void theHoldsCopyOpensTheBoxWithoutReachingTheClipboard() {
        clipboard.setPrimaryClip(ClipData.newPlainText("earlier", "a link I copied earlier"));
        hold();
        copy(POST);
        assertNotNull("the copy opened the box", PostTextSelect.shownDialog());
        assertEquals("the clipboard keeps what it held (#342)", "a link I copied earlier", onClipboard());
        assertTrue(statusLine(), statusLine().contains(PostTextSelect.KEPT_OFF + " 1"));
    }

    /** Whether Facebook's "copied" popup after its copy shows, as the check the patch puts in front of it answers. */
    private boolean facebookPopupShows() {
        return !PostTextSelect.dropsPopup();
    }

    @Test
    public void facebooksCopiedPopupForAKeptOffCopyIsDroppedAndTheNextOneShows() {
        hold();
        copy(POST);
        assertFalse("the popup saying it copied is dropped", facebookPopupShows());
        assertTrue(statusLine(), statusLine().contains(PostTextSelect.POPUP_DROPPED + " 1"));

        assertTrue("only that one popup is dropped", facebookPopupShows());
    }

    @Test
    public void popupsWithNoKeptOffCopyOrPastTheWindowShow() {
        deliver(event(MotionEvent.ACTION_DOWN));
        waitFor(100);
        copy("picked from a menu");
        deliver(event(MotionEvent.ACTION_UP));
        assertTrue("a copy that reached the clipboard keeps its popup", facebookPopupShows());

        hold();
        copy(POST);
        waitFor(PostTextSelect.POPUP_WINDOW + 100L);
        assertTrue("a popup long after the kept-off copy shows", facebookPopupShows());
        assertFalse(statusLine(), statusLine().contains(PostTextSelect.POPUP_DROPPED));
    }

    @Test
    public void aCopyThatLandsBeforeTheHoldsTimerStillOpensTheBoxAndKeepsOffTheClipboard() {
        clipboard.setPrimaryClip(ClipData.newPlainText("earlier", "kept"));
        deliver(event(MotionEvent.ACTION_DOWN));
        waitFor(ViewConfiguration.getLongPressTimeout() - PostLongPress.HELD_SLACK + 10L);
        copy(POST);
        AlertDialog box = PostTextSelect.shownDialog();
        assertNotNull("Facebook's copy beat our timer and still opened the box", box);
        assertEquals("kept", onClipboard());

        waitFor(200);
        deliver(event(MotionEvent.ACTION_UP));
        assertFalse("the timer that ran out after didn't arm the box again", statusLine().contains(PostTextSelect.ARMED));
        box.dismiss();
        copy("copied from inside the box");
        assertNull(PostTextSelect.shownDialog());
        assertEquals("copied from inside the box", onClipboard());
    }

    @Test
    public void aShortTapsCopyGoesToTheClipboard() {
        deliver(event(MotionEvent.ACTION_DOWN));
        waitFor(100);
        copy("picked from a menu");
        assertNull(PostTextSelect.shownDialog());
        assertEquals("picked from a menu", onClipboard());
        deliver(event(MotionEvent.ACTION_UP));
    }

    @Test
    public void copyAllPutsTheWholeTextOnTheClipboardAndOpensNothingAgain() {
        hold();
        copy(POST);
        AlertDialog box = PostTextSelect.shownDialog();
        assertNotNull(box);
        assertTrue("the box has Copy all", box.getButton(AlertDialog.BUTTON_NEUTRAL).performClick());
        waitFor(0);
        assertEquals(POST, onClipboard());
        assertTrue(statusLine(), statusLine().contains(PostTextSelect.COPIED_ALL + " 1"));
        assertNull("Copy all closed the box and opened no other", PostTextSelect.shownDialog());
        assertTrue("opened once", statusLine().contains(PostTextSelect.OPENED + " 1"));
    }

    @Test
    public void aCopyThatSkipsTheStandInStillOpensTheBoxOnceItLands() {
        hold();
        clipboard.setPrimaryClip(ClipData.newPlainText("post", POST));
        waitFor(0);
        AlertDialog box = PostTextSelect.shownDialog();
        assertNotNull("the clipboard's listener opened the box", box);
        assertEquals(POST, selectable(box.getWindow().getDecorView()).getText().toString());
    }

    @Test
    public void aCopyLongAfterTheHoldOrWithTheSwitchOffOrPausedOpensNothing() {
        hold();
        waitFor(PostTextSelect.WINDOW + 100L);
        copy(POST);
        assertNull("a copy picked from a menu later", PostTextSelect.shownDialog());

        Settings.SELECT_POST_TEXT.save(false);
        hold();
        copy(POST);
        assertNull("switch off", PostTextSelect.shownDialog());
        assertTrue("only the first hold armed", statusLine().contains(PostTextSelect.ARMED + " 1"));

        Settings.SELECT_POST_TEXT.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        hold();
        copy("paused copy");
        assertNull("paused", PostTextSelect.shownDialog());
        assertEquals("a copy that opens nothing reaches the clipboard", "paused copy", onClipboard());
        assertEquals("Facebook's copy ran every time", List.of("copy", "copy", "copy"), facebook);
    }

    @Test
    public void aLongHoldLiftedOnTheTranslateLineIsTimedFromTheLift() {
        deliver(event(MotionEvent.ACTION_DOWN));
        waitFor(ViewConfiguration.getLongPressTimeout() + PostTextSelect.WINDOW + 100L);
        deliver(event(MotionEvent.ACTION_UP));
        PostTextSelect.heldThroughLink();
        copy(POST);
        AlertDialog box = PostTextSelect.shownDialog();
        assertNotNull("the post's long press the line handed on opened the box", box);
        assertEquals(POST, selectable(box.getWindow().getDecorView()).getText().toString());
    }

    @Test
    public void theTranslateLineArmsNothingWithoutAHold() {
        PostTextSelect.heldThroughLink();
        copy(POST);
        assertNull(PostTextSelect.shownDialog());
        assertEquals(POST, onClipboard());
    }

    @Test
    public void aKeptCopyNamesFacebooksMethodsThatMadeIt() {
        StackTraceElement[] stack = {
                new StackTraceElement("dalvik.system.VMStack", "getThreadStackTrace", null, -2),
                new StackTraceElement("java.lang.Thread", "getStackTrace", null, 1),
                new StackTraceElement(PostTextSelect.class.getName(), "opensInstead", null, 1),
                new StackTraceElement(PostTextSelect.class.getName(), "copy", null, 1),
                new StackTraceElement("app.morphe.extension.shared.Logger", "printDebug", null, 1),
                new StackTraceElement("X.0aBc", "A02", null, 1),
                new StackTraceElement("X.0dEf", "onLongClick", null, 1),
                new StackTraceElement("android.view.View", "performLongClick", null, 1),
                new StackTraceElement("X.0gHi", "run", null, 1),
                new StackTraceElement("android.os.Handler", "dispatchMessage", null, 1),
                new StackTraceElement("android.os.Looper", "loop", null, 1),
                new StackTraceElement("android.app.ActivityThread", "main", null, 1),
                new StackTraceElement("com.android.internal.os.ZygoteInit", "main", null, 1),
                new StackTraceElement("com.android.internal.os.RuntimeInit", "main", null, 1),
        };
        assertEquals("X.0aBc.A02 < X.0dEf.onLongClick < android.view.View.performLongClick < X.0gHi.run"
                        + " < android.os.Handler.dispatchMessage < android.os.Looper.loop"
                        + " < android.app.ActivityThread.main < com.android.internal.os.ZygoteInit.main",
                PostTextSelect.copiers(stack));
    }

    @Test
    public void withLongPressOpensCommentsOffTooAHoldOnAPostRunsNothingOfOurs() {
        Settings.LONG_PRESS_OPENS_COMMENTS.save(false);
        hold();
        assertTrue(statusLine(), statusLine().contains(PostTextSelect.ARMED + " 1"));
        assertFalse("no comments looked for", statusLine().contains(PostLongPress.HELD));
        assertFalse(statusLine().contains("feed hold passed"));
    }
}
