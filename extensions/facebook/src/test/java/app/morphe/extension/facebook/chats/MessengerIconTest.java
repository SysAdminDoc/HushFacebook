/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.chats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.util.List;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The hook first in the Messenger icon's tap: while the switch is on and Messenger is installed, a
 * tap starts Messenger's launcher entry and Facebook's own Chats doesn't open. Every other time,
 * Facebook handles the tap.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MessengerIconTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final Context app = RuntimeEnvironment.getApplication();

    @Before
    public void startClean() {
        // Each test starts as a screen does after a gesture is called off, with nothing left from
        // another test's fingers or taps.
        long now = SystemClock.uptimeMillis();
        MessengerIcon.touch(MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0));
        MessengerIconForTests.uninstall();
        MessengerIconForTests.nextStarted();
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.OPEN_MESSENGER_APP.resetToDefault();
        MessengerIconForTests.uninstall();
        while (MessengerIconForTests.nextStarted() != null) {
            // Robolectric keeps every start; the next test begins with none.
        }
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.MESSENGER_ICON + ":")) return line;
        }
        return null;
    }

    /**
     * A finger on the icon for {@code heldMs}, lifted now, each event handed to the hook as the
     * screen's touch dispatch hands it on before Facebook sees it.
     */
    private static void press(long heldMs) {
        long down = SystemClock.uptimeMillis();
        MessengerIcon.touch(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, 40, 40, 0));
        ShadowSystemClock.advanceBy(heldMs, TimeUnit.MILLISECONDS);
        MessengerIcon.touch(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 40, 40, 0));
    }

    /** Pointer ids: a finger resting somewhere else on the screen, and one on the icon. */
    private static final int RESTING = 0;
    private static final int ON_ICON = 1;

    /**
     * Now, the finger at {@code index} of {@code ids} landing or lifting ({@code action}), with every
     * finger in {@code ids} on the screen, in a gesture whose first finger went down at {@code start}.
     */
    private static MotionEvent fingers(long start, int action, int index, int... ids) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[ids.length];
        for (int i = 0; i < ids.length; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = ids[i];
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = 40 + 300 * ids[i];
            coords[i].y = 40;
        }
        return MotionEvent.obtain(start, SystemClock.uptimeMillis(), action | index << MotionEvent.ACTION_POINTER_INDEX_SHIFT,
                ids.length, properties, coords, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    private static void advance(long ms) {
        ShadowSystemClock.advanceBy(ms, TimeUnit.MILLISECONDS);
    }

    /** Off until it's turned on: a tap with Messenger right there still opens Facebook's Chats. */
    @Test
    public void theSwitchStartsOffAndLeavesTheTapToFacebook() {
        assertFalse("the switch starts on", Settings.OPEN_MESSENGER_APP.get());
        MessengerIconForTests.install();
        assertFalse(MessengerIcon.open(app, false));
        assertNull("something was started", MessengerIconForTests.nextStarted());
        assertEquals(FamilyNames.MESSENGER_ICON + ": invoked 1, 0 found, 0 missing", statusLine());
    }

    /**
     * On, with Messenger installed, a tap starts Messenger's own launcher entry, the intent its home
     * screen icon sends, in a task of its own. The Messenger here is signed with another key than
     * this app, as Meta's Messenger is beside a re-signed Facebook, and that doesn't matter.
     */
    @Test
    public void onATapOpensMessengersLauncherEntry() {
        Settings.OPEN_MESSENGER_APP.save(true);
        MessengerIconForTests.install();
        PackageManager packages = app.getPackageManager();
        assertNotEquals("the test Messenger shares the app's key", PackageManager.SIGNATURE_MATCH,
                packages.checkSignatures(app.getPackageName(), MessengerCard.MESSENGER));

        assertTrue(MessengerIcon.open(app, false));
        Intent started = MessengerIconForTests.nextStarted();
        assertNotNull("nothing was started", started);
        assertEquals(MessengerIconForTests.HOME, started.getComponent());
        assertEquals(Intent.ACTION_MAIN, started.getAction());
        assertTrue(started.toString(), started.hasCategory(Intent.CATEGORY_LAUNCHER));
        assertTrue("Messenger doesn't get a task of its own",
                (started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertNull("the extras of a tap reached Messenger", started.getExtras());
        assertEquals(FamilyNames.MESSENGER_ICON + ": invoked 1, 0 found, 0 missing. Counted: "
                + MessengerIcon.OPENED + " 1", statusLine());
    }

    /** Without Messenger, Facebook's Chats opens as before. */
    @Test
    public void withoutMessengerTheTapIsFacebooks() {
        Settings.OPEN_MESSENGER_APP.save(true);
        assertFalse(MessengerIcon.open(app, false));
        assertNull(MessengerIconForTests.nextStarted());
        assertEquals(FamilyNames.MESSENGER_ICON + ": invoked 1, 0 found, 0 missing. Counted: "
                + MessengerIcon.NO_MESSENGER + " 1", statusLine());
    }

    /**
     * HushMessenger installed beside Meta's apps has another package name and Messenger's class
     * names. Without Meta's Messenger, a tap opens it in a task of its own (issue #116).
     */
    @Test
    public void aMessengerUnderAnotherPackageNameOpensWithoutMetas() {
        Settings.OPEN_MESSENGER_APP.save(true);
        MessengerIconForTests.installLauncher(MessengerIconForTests.OTHER_HOME);
        MessengerIconForTests.installLauncher(MessengerIconForTests.CLONE_HOME);

        assertTrue(MessengerIcon.open(app, false));
        Intent started = MessengerIconForTests.nextStarted();
        assertNotNull("nothing was started", started);
        assertEquals(MessengerIconForTests.CLONE_HOME, started.getComponent());
        assertEquals(Intent.ACTION_MAIN, started.getAction());
        assertTrue(started.toString(), started.hasCategory(Intent.CATEGORY_LAUNCHER));
        assertTrue("the copy doesn't get a task of its own", (started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertEquals(FamilyNames.MESSENGER_ICON + ": invoked 1, 0 found, 0 missing. Counted: "
                + MessengerIcon.OPENED + " 1", statusLine());
    }

    /** With Meta's Messenger and a copy both installed, Meta's opens. */
    @Test
    public void metasMessengerComesBeforeACopy() {
        Settings.OPEN_MESSENGER_APP.save(true);
        MessengerIconForTests.installLauncher(MessengerIconForTests.CLONE_HOME);
        MessengerIconForTests.install();
        assertTrue(MessengerIcon.open(app, false));
        assertEquals(MessengerIconForTests.HOME, MessengerIconForTests.nextStarted().getComponent());
    }

    /** Another app's launcher entry, or Facebook's own Messenger classes, never stand in for Messenger. */
    @Test
    public void onlyMessengersClassesInAnotherAppCount() {
        Settings.OPEN_MESSENGER_APP.save(true);
        MessengerIconForTests.installLauncher(MessengerIconForTests.OTHER_HOME);
        PackageManager packages = app.getPackageManager();
        assertNull(MessengerIcon.messengerLaunch(packages, app.getPackageName()));
        MessengerIconForTests.installLauncher(MessengerIconForTests.CLONE_HOME);
        assertNull("the running app's own entry was taken",
                MessengerIcon.messengerLaunch(packages, MessengerIconForTests.CLONE_PACKAGE));
        assertFalse(MessengerIcon.open(new ContextWrapper(app) {
            @Override
            public String getPackageName() {
                return MessengerIconForTests.CLONE_PACKAGE;
            }
        }, false));
        assertNull(MessengerIconForTests.nextStarted());
    }

    /** A Messenger with no home screen entry has nothing public to open, so Chats opens. */
    @Test
    public void aMessengerWithNoLauncherEntryIsLeftAlone() {
        Settings.OPEN_MESSENGER_APP.save(true);
        MessengerCardForTests.install(true);
        assertFalse(MessengerIcon.open(app, false));
        assertNull(MessengerIconForTests.nextStarted());
    }

    /** A long press is Facebook's own gesture, so it keeps doing what Facebook does with it. */
    @Test
    public void aLongPressIsFacebooks() {
        Settings.OPEN_MESSENGER_APP.save(true);
        MessengerIconForTests.install();
        assertFalse(MessengerIcon.open(app, true));
        assertNull(MessengerIconForTests.nextStarted());
        assertTrue(MessengerIcon.open(app, false));
    }

    /**
     * Where Facebook gives the icon no long-click listener of its own, which is behind a
     * MobileConfig flag, a press held on it reaches the tap as a plain tap when the finger lifts,
     * and Facebook opens Chats for it. That's still a long press, so it stays Facebook's, as it is
     * with the switch off. The next quick tap opens Messenger again.
     */
    @Test
    public void aPressHeldPastTheLongPressTimeStaysFacebooksWhenItArrivesAsATap() {
        Settings.OPEN_MESSENGER_APP.save(true);
        MessengerIconForTests.install();
        press(ViewConfiguration.getLongPressTimeout());
        assertFalse("a held press opened Messenger", MessengerIcon.open(app, false));
        assertNull("something was started", MessengerIconForTests.nextStarted());
        assertEquals(FamilyNames.MESSENGER_ICON + ": invoked 1, 1 found, 0 missing. Counted: "
                + MessengerIcon.LONG_PRESS + " 1", statusLine());

        press(100);
        assertTrue("a quick tap after a held press didn't open Messenger", MessengerIcon.open(app, false));
        assertNotNull(MessengerIconForTests.nextStarted());
    }

    /** Lifted just before the long-press time, it's a tap, and Messenger opens. */
    @Test
    public void aPressLiftedBeforeTheLongPressTimeIsATap() {
        Settings.OPEN_MESSENGER_APP.save(true);
        MessengerIconForTests.install();
        press(ViewConfiguration.getLongPressTimeout() - 1);
        assertTrue(MessengerIcon.open(app, false));
    }

    /**
     * A tap no finger made, well after a held press ended, an accessibility click say, isn't that
     * press's release, so it opens Messenger.
     */
    @Test
    public void aHeldPressLongOverDoesntHoldBackALaterTap() {
        Settings.OPEN_MESSENGER_APP.save(true);
        MessengerIconForTests.install();
        press(ViewConfiguration.getLongPressTimeout() + 200);
        ShadowSystemClock.advanceBy(MessengerIcon.RELEASE_WINDOW_MS + 1, TimeUnit.MILLISECONDS);
        assertTrue(MessengerIcon.open(app, false));
    }

    /** With the switch off, a held press is Facebook's like everything else, and nothing is counted. */
    @Test
    public void offAHeldPressIsFacebooksAndUncounted() {
        MessengerIconForTests.install();
        press(ViewConfiguration.getLongPressTimeout() + 200);
        assertFalse(MessengerIcon.open(app, false));
        assertNull(MessengerIconForTests.nextStarted());
        assertEquals(FamilyNames.MESSENGER_ICON + ": invoked 1, 1 found, 0 missing", statusLine());
    }

    /** The touch hook reads every touch on every screen: a null or odd event is passed over. */
    @Test
    public void theTouchHookTakesAnyEvent() {
        MessengerIcon.touch(null);
        long now = SystemClock.uptimeMillis();
        MessengerIcon.touch(MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1, 1, 0));
        MessengerIcon.touch(MotionEvent.obtain(now, now + 5_000, MotionEvent.ACTION_CANCEL, 1, 1, 0));
        assertEquals(List.of(), HookStatus.missing(FamilyNames.MESSENGER_ICON));
    }

    /**
     * A finger resting elsewhere doesn't make a quick tap on the icon a long press. When the tap's
     * finger lifts last, its lift carries the time the resting finger went down, so each finger is
     * timed from its own landing.
     */
    @Test
    public void aQuickTapWhileAnotherFingerRestsIsATap() {
        Settings.OPEN_MESSENGER_APP.save(true);
        MessengerIconForTests.install();
        long start = SystemClock.uptimeMillis();
        MessengerIcon.touch(fingers(start, MotionEvent.ACTION_DOWN, 0, RESTING));
        advance(ViewConfiguration.getLongPressTimeout() + 100);
        MessengerIcon.touch(fingers(start, MotionEvent.ACTION_POINTER_DOWN, 1, RESTING, ON_ICON));
        advance(40);
        MessengerIcon.touch(fingers(start, MotionEvent.ACTION_POINTER_UP, 0, RESTING, ON_ICON));
        advance(40);
        MessengerIcon.touch(fingers(start, MotionEvent.ACTION_UP, 0, ON_ICON));
        assertTrue("an 80 ms tap was taken as held", MessengerIcon.open(app, false));
        assertNotNull(MessengerIconForTests.nextStarted());
    }

    /**
     * A press held on the icon while another finger is down lifts as one pointer going up, not as
     * the gesture's end. It's still a long press, and stays Facebook's.
     */
    @Test
    public void aPressHeldOnTheIconWhileAnotherFingerIsDownStaysFacebooks() {
        Settings.OPEN_MESSENGER_APP.save(true);
        MessengerIconForTests.install();
        long start = SystemClock.uptimeMillis();
        MessengerIcon.touch(fingers(start, MotionEvent.ACTION_DOWN, 0, RESTING));
        advance(20);
        MessengerIcon.touch(fingers(start, MotionEvent.ACTION_POINTER_DOWN, 1, RESTING, ON_ICON));
        advance(ViewConfiguration.getLongPressTimeout() + 100);
        MessengerIcon.touch(fingers(start, MotionEvent.ACTION_POINTER_UP, 1, RESTING, ON_ICON));
        assertFalse("a held press opened Messenger", MessengerIcon.open(app, false));
        assertNull("something was started", MessengerIconForTests.nextStarted());
        assertEquals(FamilyNames.MESSENGER_ICON + ": invoked 1, 1 found, 0 missing. Counted: "
                + MessengerIcon.LONG_PRESS + " 1", statusLine());
    }

    /**
     * Where MobileConfig sends the tap on to the Messenger button handler, the handler's hook asks
     * again straight after and gets the same answer. That's still one long press, whether the tap
     * learns it from the lift or is told while the finger is down, and a tap that opens Messenger
     * after it counts once too.
     */
    @Test
    public void aLongPressThatAsksTwiceCountsOnce() {
        Settings.OPEN_MESSENGER_APP.save(true);
        MessengerIconForTests.install();
        press(ViewConfiguration.getLongPressTimeout() + 100);
        assertFalse(MessengerIcon.open(app, false));
        assertFalse("the handler's ask opened Messenger", MessengerIcon.open(app, false));
        assertEquals(FamilyNames.MESSENGER_ICON + ": invoked 2, 1 found, 0 missing. Counted: "
                + MessengerIcon.LONG_PRESS + " 1", statusLine());

        long down = SystemClock.uptimeMillis();
        MessengerIcon.touch(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, 40, 40, 0));
        advance(ViewConfiguration.getLongPressTimeout());
        assertFalse(MessengerIcon.open(app, true));
        assertFalse(MessengerIcon.open(app, true));
        advance(100);
        MessengerIcon.touch(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 40, 40, 0));
        assertNull("something was started", MessengerIconForTests.nextStarted());

        press(60);
        assertTrue(MessengerIcon.open(app, false));
        assertEquals(FamilyNames.MESSENGER_ICON + ": invoked 5, 1 found, 0 missing. Counted: "
                + MessengerIcon.LONG_PRESS + " 2, " + MessengerIcon.OPENED + " 1", statusLine());
    }

    /**
     * Without Messenger, the tap and the handler it goes on to each find none, and that's one tap.
     * A second tap straight after is a tap of its own.
     */
    @Test
    public void aTapThatAsksTwiceWithoutMessengerCountsOnce() {
        Settings.OPEN_MESSENGER_APP.save(true);
        press(60);
        assertFalse(MessengerIcon.open(app, false));
        assertFalse(MessengerIcon.open(app, false));
        press(60);
        assertFalse(MessengerIcon.open(app, false));
        assertFalse(MessengerIcon.open(app, false));
        assertEquals(FamilyNames.MESSENGER_ICON + ": invoked 4, 1 found, 0 missing. Counted: "
                + MessengerIcon.NO_MESSENGER + " 2", statusLine());
    }

    /**
     * Taps no finger made, accessibility clicks say, come with no touch to tell them apart. Two
     * further apart than one tap's two asks are two taps, each asking twice.
     */
    @Test
    public void tapsNoFingerMadeCountOnceEach() {
        Settings.OPEN_MESSENGER_APP.save(true);
        MessengerIconForTests.install();
        assertFalse(MessengerIcon.open(app, true));
        assertFalse(MessengerIcon.open(app, true));
        advance(MessengerIcon.SAME_TAP_MS + 1);
        assertFalse(MessengerIcon.open(app, true));
        assertFalse(MessengerIcon.open(app, true));
        assertEquals(FamilyNames.MESSENGER_ICON + ": invoked 4, 0 found, 0 missing. Counted: "
                + MessengerIcon.LONG_PRESS + " 2", statusLine());
    }

    @Test
    public void pausedTheTapIsFacebooks() {
        Settings.OPEN_MESSENGER_APP.save(true);
        MessengerIconForTests.install();
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(MessengerIcon.open(app, false));
        PauseForTests.pause(HushfacebookPause.Reason.CRASH_LOOP);
        assertFalse(MessengerIcon.open(app, false));
        assertNull(MessengerIconForTests.nextStarted());
        PauseForTests.resume();
        assertTrue(MessengerIcon.open(app, false));
    }

    /** Until the settings are ready, Facebook handles the tap and nothing is looked up. */
    @Test
    public void untilTheSettingsAreReadyTheTapIsFacebooks() {
        Settings.OPEN_MESSENGER_APP.save(true);
        MessengerIconForTests.install();
        boolean[] opened = {true};
        SettingsContextRule.withoutContext(() -> opened[0] = MessengerIcon.open(app, false));
        assertFalse(opened[0]);
        SettingsContextRule.beforeThePauseIsDecided(() -> opened[0] = MessengerIcon.open(app, false));
        assertFalse(opened[0]);
        assertNull(MessengerIconForTests.nextStarted());
        assertTrue(MessengerIcon.open(app, false));
    }

    /** No context to start from: Facebook handles the tap. */
    @Test
    public void noContextLeavesTheTapToFacebook() {
        Settings.OPEN_MESSENGER_APP.save(true);
        MessengerIconForTests.install();
        assertFalse(MessengerIcon.open(null, false));
    }

    /**
     * Messenger turning the start down leaves the tap to Facebook, so the tap still opens a chat
     * list. The report counts it; nothing in the hook failed.
     */
    @Test
    public void aStartMessengerTurnsDownLeavesTheTapToFacebook() {
        Settings.OPEN_MESSENGER_APP.save(true);
        MessengerIconForTests.install();
        Context refusing = new ContextWrapper(app) {
            @Override
            public void startActivity(Intent intent) {
                throw new SecurityException("Permission Denial: starting " + intent);
            }
        };
        assertFalse(MessengerIcon.open(refusing, false));
        assertEquals(FamilyNames.MESSENGER_ICON + ": invoked 1, 0 found, 0 missing. Counted: "
                + MessengerIcon.REFUSED + " 1", statusLine());
    }

    /** The handler MobileConfig sends the tap on to asks again and is turned down again: one tap. */
    @Test
    public void aStartTurnedDownTwiceInOneTapCountsOnce() {
        Settings.OPEN_MESSENGER_APP.save(true);
        MessengerIconForTests.install();
        Context refusing = new ContextWrapper(app) {
            @Override
            public void startActivity(Intent intent) {
                throw new ActivityNotFoundException("No Activity found to handle " + intent);
            }
        };
        press(60);
        assertFalse(MessengerIcon.open(refusing, false));
        assertFalse(MessengerIcon.open(refusing, false));
        assertEquals(FamilyNames.MESSENGER_ICON + ": invoked 2, 1 found, 0 missing. Counted: "
                + MessengerIcon.REFUSED + " 1", statusLine());
    }

    /** A failure anywhere else leaves the tap to Facebook, and the report names the hook. */
    @Test
    public void aFailureLeavesTheTapToFacebookAndTheReportSaysSo() {
        Settings.OPEN_MESSENGER_APP.save(true);
        MessengerIconForTests.install();
        Context broken = new ContextWrapper(app) {
            @Override
            public PackageManager getPackageManager() {
                throw new IllegalStateException("the package manager failed");
            }
        };
        assertFalse(MessengerIcon.open(broken, false));
        List<String> missing = HookStatus.missing(FamilyNames.MESSENGER_ICON);
        assertEquals(missing.toString(), 1, missing.size());
        assertTrue(missing.get(0), missing.get(0).contains(
                "'Messenger icon' hook (it threw " + IllegalStateException.class.getName() + ")"));
        assertTrue(MessengerIcon.open(app, false));
    }

    @Test
    public void theHookReportsUnderThePatchsName() {
        assertEquals("Open Messenger from the top bar", FamilyNames.MESSENGER_ICON);
    }
}
