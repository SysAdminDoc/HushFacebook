/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Always use Clean mode: with the switch on, each part of the Reels viewer that reads Facebook's
 * remembered Clean mode flag as it's made gets a yes, and each one is counted. A yes from Facebook
 * passes as it came. Off, paused, or before the settings are ready, Facebook's answer is kept.
 * The Clean mode button (#304) takes the sticky way, and a reel taken out keeps its button while
 * an ad's plain no doesn't.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReelCleanModeTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
        ReelCleanMode.store = null;
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.REEL_CLEAN_MODE.resetToDefault();
        Settings.REEL_CLEAN_BUTTON.resetToDefault();
        ReelCleanMode.store = null;
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.REEL_DECLUTTER + ":")) return line;
        }
        return null;
    }

    @Test
    public void onEveryPartStartsCleanAndIsCounted() {
        Settings.REEL_CLEAN_MODE.save(true);
        // The overlay, the footer and the unified player's controls read the flag as a boolean,
        // the video controls through Facebook's helper that boxes it.
        assertTrue("the overlay started with its buttons", ReelCleanMode.startClean(false));
        assertTrue("the footer started outside Clean mode", ReelCleanMode.startClean(false));
        assertTrue("the player controls started outside Clean mode", ReelCleanMode.startClean(false));
        assertSame("the boxed read wasn't turned into a yes", Boolean.TRUE, ReelCleanMode.startClean(Boolean.FALSE));
        assertEquals(FamilyNames.REEL_DECLUTTER + ": invoked 4, 1 found, 0 missing. Counted: "
                + ReelCleanMode.STARTED_CLEAN + " 4", statusLine());
    }

    @Test
    public void facebooksYesPassesAsItCame() {
        // An account with sticky Clean mode that left it on: nothing to change, nothing counted.
        Boolean facebooks = Boolean.TRUE;
        assertTrue(ReelCleanMode.startClean(true));
        assertSame(facebooks, ReelCleanMode.startClean(facebooks));
        Settings.REEL_CLEAN_MODE.save(true);
        assertTrue(ReelCleanMode.startClean(true));
        assertSame(facebooks, ReelCleanMode.startClean(facebooks));
        String line = statusLine();
        assertFalse("a yes Facebook gave was counted: " + line, line != null && line.contains("Counted"));
    }

    @Test
    public void offOrPausedFacebookDecides() {
        assertFalse("the switch doesn't start off", Settings.REEL_CLEAN_MODE.get());
        assertFalse("off, a reel started clean", ReelCleanMode.startClean(false));
        assertSame("off, the boxed read changed", Boolean.FALSE, ReelCleanMode.startClean(Boolean.FALSE));
        assertNull("off, a missing answer was filled in", ReelCleanMode.startClean((Boolean) null));

        Settings.REEL_CLEAN_MODE.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertFalse("a Hushfacebook paused by " + reason + " started a reel clean", ReelCleanMode.startClean(false));
            assertSame("a Hushfacebook paused by " + reason + " changed the boxed read",
                    Boolean.FALSE, ReelCleanMode.startClean(Boolean.FALSE));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() ->
                assertFalse("a reel started clean before the settings were ready", ReelCleanMode.startClean(false)));

        String line = statusLine();
        assertFalse("an answer that left it to Facebook was counted: " + line, line != null && line.contains("Counted"));
        assertTrue("on again after the pause, the reel didn't start clean", ReelCleanMode.startClean(false));
    }

    @Test
    public void onTheButtonTakesTheStickyWayAndIsCounted() {
        Settings.REEL_CLEAN_MODE.save(true);
        // The tap and the store's setter each ask the sticky check once.
        assertTrue("the tap didn't take the sticky way", ReelCleanMode.stickyClean(false));
        assertTrue("the setter didn't take the sticky way", ReelCleanMode.stickyClean(false));
        assertTrue("an account with sticky Clean mode lost it", ReelCleanMode.stickyClean(true));
        assertEquals(FamilyNames.REEL_DECLUTTER + ": invoked 3, 1 found, 0 missing. Counted: "
                + ReelCleanMode.SWITCHED_BY_BUTTON + " 2", statusLine());
    }

    @Test
    public void onAReelTakenOutKeepsItsButtonAndAnAdDoesNot() {
        Settings.REEL_CLEAN_MODE.save(true);
        Object[] left = {Boolean.FALSE};
        assertSame("the listener's update changed", left, ReelCleanMode.markLeftClean(left));
        assertSame("a reel taken out wasn't marked", ReelCleanMode.LEFT_CLEAN, left[0]);
        assertEquals("the mark doesn't read as a no to Facebook", Boolean.FALSE, left[0]);
        assertSame("the button left a reel the person took out", Boolean.TRUE, ReelCleanMode.cleanButtonState(left[0]));

        Object[] back = {Boolean.TRUE};
        ReelCleanMode.markLeftClean(back);
        assertSame("a reel put back in Clean mode was marked", Boolean.TRUE, back[0]);
        assertSame("a clean reel's value changed", Boolean.TRUE, ReelCleanMode.cleanButtonState(Boolean.TRUE));

        // An ad starts with Facebook's own no and never hears the event, so it keeps no button.
        assertSame("an ad got the button", Boolean.FALSE, ReelCleanMode.cleanButtonState(Boolean.FALSE));
        assertNull("a missing value was filled in", ReelCleanMode.cleanButtonState(null));
        Object[] other = {Boolean.FALSE, Boolean.FALSE};
        ReelCleanMode.markLeftClean(other);
        assertSame("an update with more than the value was marked", Boolean.FALSE, other[0]);
        assertNull("a missing update broke", ReelCleanMode.markLeftClean(null));
    }

    @Test
    public void aKeptButtonIsLaidOverTheDetailsAndNoOtherIs() throws InterruptedException {
        Settings.REEL_CLEAN_MODE.save(true);
        Object[] left = {Boolean.FALSE};
        ReelCleanMode.markLeftClean(left);
        ReelCleanMode.cleanButtonState(left[0]);
        assertTrue("a kept button stayed in the column under the details (#329)", ReelCleanMode.floatCleanButton());
        assertFalse("the answer carried over to the next button", ReelCleanMode.floatCleanButton());

        // A clean reel's button is Facebook's own, in the column, even right after a kept one's read.
        ReelCleanMode.cleanButtonState(Boolean.TRUE);
        assertFalse("a clean reel's button moved", ReelCleanMode.floatCleanButton());
        ReelCleanMode.cleanButtonState(left[0]);
        ReelCleanMode.cleanButtonState(Boolean.TRUE);
        assertFalse("an earlier build's kept button moved a clean reel's", ReelCleanMode.floatCleanButton());

        // Another thread making another reel's button doesn't see this one's.
        ReelCleanMode.cleanButtonState(left[0]);
        boolean[] elsewhere = {true};
        Thread other = new Thread(() -> elsewhere[0] = ReelCleanMode.floatCleanButton());
        other.start();
        other.join();
        assertFalse("a button on another thread was moved", elsewhere[0]);
        assertTrue("this thread's kept button was lost", ReelCleanMode.floatCleanButton());
        String line = statusLine();
        assertTrue("the kept buttons weren't counted: " + line, line != null && line.contains(ReelCleanMode.FLOATED_BUTTON + " 2"));

        // Off, or paused, the button is Facebook's again and nothing moves.
        Settings.REEL_CLEAN_MODE.save(false);
        ReelCleanMode.cleanButtonState(left[0]);
        assertFalse("off, a button moved", ReelCleanMode.floatCleanButton());
        Settings.REEL_CLEAN_MODE.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        ReelCleanMode.cleanButtonState(left[0]);
        assertFalse("paused, a button moved", ReelCleanMode.floatCleanButton());
    }

    @Test
    public void offOrPausedTheButtonIsFacebooks() {
        assertFalse("off, the button took the sticky way", ReelCleanMode.stickyClean(false));
        Object[] left = {Boolean.FALSE};
        ReelCleanMode.markLeftClean(left);
        assertSame("off, a reel taken out was marked", Boolean.FALSE, left[0]);

        // A reel marked while on, then the switch goes off: Facebook's no again, and its button goes.
        Settings.REEL_CLEAN_MODE.save(true);
        ReelCleanMode.markLeftClean(left);
        Settings.REEL_CLEAN_MODE.save(false);
        assertSame("off, a marked reel kept its button", ReelCleanMode.LEFT_CLEAN, ReelCleanMode.cleanButtonState(left[0]));

        Settings.REEL_CLEAN_MODE.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertFalse("a Hushfacebook paused by " + reason + " took the sticky way", ReelCleanMode.stickyClean(false));
            Object[] paused = {Boolean.FALSE};
            ReelCleanMode.markLeftClean(paused);
            assertSame("a Hushfacebook paused by " + reason + " marked a reel", Boolean.FALSE, paused[0]);
            assertSame("a Hushfacebook paused by " + reason + " kept a marked reel's button",
                    ReelCleanMode.LEFT_CLEAN, ReelCleanMode.cleanButtonState(left[0]));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() ->
                assertFalse("the button took the sticky way before the settings were ready", ReelCleanMode.stickyClean(false)));

        String line = statusLine();
        assertFalse("an answer that left it to Facebook was counted: " + line, line != null && line.contains("Counted"));
    }

    @Test
    public void theSwitchNeedsNoRestartAndTravelsWithCleanUpReels() {
        assertFalse("a new reel reads the switch as it's made", Settings.REEL_CLEAN_MODE.rebootApp);
        assertTrue("Pause and the report don't know the switch",
                PatchFamily.REEL_DECLUTTER.switches.contains(Settings.REEL_CLEAN_MODE));
        assertFalse("a new sidebar reads the button's switch as it's made", Settings.REEL_CLEAN_BUTTON.rebootApp);
        assertTrue("Pause and the report don't know the button's switch",
                PatchFamily.REEL_DECLUTTER.switches.contains(Settings.REEL_CLEAN_BUTTON));
    }

    /**
     * Issue #391: the sidebar's Clean mode button turns Clean mode on through the store, and only
     * once the store was kept, which a build without Clean up Reels never does.
     */
    @Test
    public void theSidebarButtonNeedsItsSwitchAndTheStore() {
        assertFalse("the button's switch doesn't start off", Settings.REEL_CLEAN_BUTTON.get());
        Object facebooks = new Object();
        ReelCleanMode.keepStore(facebooks);
        assertFalse("off, the sidebar got the button", ReelCleanMode.cleanButton());

        Settings.REEL_CLEAN_BUTTON.save(true);
        assertTrue("on with a store, the sidebar got no button", ReelCleanMode.cleanButton());
        ReelCleanMode.store = null;
        assertFalse("without a store, the sidebar got a button that does nothing", ReelCleanMode.cleanButton());
        ReelCleanMode.keepStore(null);
        assertFalse("a missing store was kept", ReelCleanMode.cleanButton());

        ReelCleanMode.keepStore(facebooks);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("a paused Hushfacebook showed the button", ReelCleanMode.cleanButton());
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
                assertFalse("the button showed before the settings were ready", ReelCleanMode.cleanButton()));
        assertEquals("Clean mode", ReelCleanMode.cleanLabel());
    }

    @Test
    public void aTapOnTheSidebarButtonIsCountedOnlyWithAStore() {
        Settings.REEL_CLEAN_BUTTON.save(true);
        ReelCleanMode.enterClean();
        String line = statusLine();
        assertFalse("a tap with no store was counted: " + line, line != null && line.contains("Counted"));

        Object facebooks = new Object();
        ReelCleanMode.keepStore(facebooks);
        ReelCleanMode.enterClean();
        assertEquals(FamilyNames.REEL_DECLUTTER + ": invoked 2, 1 found, 0 missing. Counted: "
                + ReelCleanMode.ENTERED_FROM_SIDEBAR + " 1", statusLine());

        Settings.REEL_CLEAN_BUTTON.save(false);
        ReelCleanMode.enterClean();
        assertTrue("a tap with the switch off was counted: " + statusLine(),
                statusLine().endsWith(ReelCleanMode.ENTERED_FROM_SIDEBAR + " 1"));
    }

    /**
     * With only the sidebar button on, Facebook's own button over a clean reel still takes the
     * sticky way, so it turns the choice the sidebar button set back off. A reel it takes out isn't
     * marked, since that's Always use Clean mode's, so it's Facebook's plain no and the sidebar
     * comes back.
     */
    @Test
    public void onlyTheSidebarButtonOnFacebooksButtonStillTurnsItOff() {
        Settings.REEL_CLEAN_BUTTON.save(true);
        assertTrue("Facebook's button didn't take the sticky way", ReelCleanMode.stickyClean(false));
        assertFalse("a new reel started clean without Always use Clean mode", ReelCleanMode.startClean(false));
        Object[] left = {Boolean.FALSE};
        ReelCleanMode.markLeftClean(left);
        assertSame("a reel taken out was marked without Always use Clean mode", Boolean.FALSE, left[0]);
        assertSame(Boolean.FALSE, ReelCleanMode.cleanButtonState(left[0]));
    }
}
