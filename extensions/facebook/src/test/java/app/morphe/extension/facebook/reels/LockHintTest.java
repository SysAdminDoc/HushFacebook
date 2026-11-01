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

import app.morphe.extension.facebook.reels.ReelHoldForTests.Pill;
import app.morphe.extension.facebook.reels.ReelHoldForTests.ReelPill;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Hide the pull down to lock hint (#289): with the switch on, a 2x pill made with one of the hints
 * is drawn as the plain pill and counted. The states a pull itself shows are kept. Off, paused,
 * before the settings are ready, or in a build without Hold a reel for 2x, the state is Facebook's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class LockHintTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
        ReelHold.holdInBuildForTests = true;
    }

    @After
    public void restore() {
        PauseForTests.resume();
        ReelHold.holdInBuildForTests = null;
        Settings.HIDE_2X_LOCK_HINT.resetToDefault();
        Settings.HIDE_2X_POPUPS.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.HOLD_REEL_FOR_2X + ":")) return line;
        }
        return null;
    }

    @Test
    public void onTheHintsBecomeThePlainPillAndAreCounted() {
        Settings.HIDE_2X_LOCK_HINT.save(true);
        assertSame("the pull down hint stayed", Pill.NORMAL, LockHint.pill(Pill.LOCK_HINT));
        assertSame("the pull up hint stayed", Pill.NORMAL, LockHint.pill(Pill.UNLOCK_HINT));
        assertSame("the first-time line stayed", Pill.NORMAL, LockHint.pill(Pill.NUX_PROMPT));
        assertEquals(FamilyNames.HOLD_REEL_FOR_2X + ": invoked 3, 1 found, 0 missing. Counted: "
                + LockHint.HINT_HIDDEN + " 3", statusLine());
    }

    @Test
    public void aPullStillShowsWhatItDoes() {
        Settings.HIDE_2X_LOCK_HINT.save(true);
        for (Pill kept : new Pill[] {Pill.NORMAL, Pill.READY_TO_LOCK, Pill.LOCKED, Pill.READY_TO_UNLOCK}) {
            assertSame(kept + " was changed", kept, LockHint.pill(kept));
        }
        assertNull("a missing state was filled in", LockHint.pill(null));
        String line = statusLine();
        assertFalse("a kept state was counted: " + line, line != null && line.contains("Counted"));
    }

    @Test
    public void offPausedOrWithoutTheHoldFacebookDecides() {
        assertFalse("the switch doesn't start off", Settings.HIDE_2X_LOCK_HINT.get());
        assertSame("off, the hint was hidden", Pill.LOCK_HINT, LockHint.pill(Pill.LOCK_HINT));

        Settings.HIDE_2X_LOCK_HINT.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertSame("a Hushfacebook paused by " + reason + " hid the hint", Pill.LOCK_HINT, LockHint.pill(Pill.LOCK_HINT));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() ->
                assertSame("the hint was hidden before the settings were ready", Pill.LOCK_HINT, LockHint.pill(Pill.LOCK_HINT)));
        ReelHold.holdInBuildForTests = false;
        assertSame("a build without Hold a reel for 2x hid the hint", Pill.LOCK_HINT, LockHint.pill(Pill.LOCK_HINT));
        ReelHold.holdInBuildForTests = true;

        String line = statusLine();
        assertFalse("an answer left to Facebook was counted: " + line, line != null && line.contains("Counted"));
        assertSame("on again, the hint stayed", Pill.NORMAL, LockHint.pill(Pill.LOCK_HINT));
    }

    @Test
    public void onTheReelsPillDropsItsHintsWhereTheyReadLock() {
        Settings.HIDE_2X_LOCK_HINT.save(true);
        for (ReelPill hint : new ReelPill[] {ReelPill.UNLOCKED_2X_SPEED, ReelPill.LOCKED_WITH_FOOTER, ReelPill.ORIGINAL_SPEED}) {
            assertFalse("the Reels pill kept its hint in " + hint, LockHint.reelPill(hint, true));
        }
        assertFalse("a Reels pill Facebook made without hints got them", LockHint.reelPill(ReelPill.UNLOCKED_2X_SPEED, false));
        assertEquals(FamilyNames.HOLD_REEL_FOR_2X + ": invoked 4, 1 found, 0 missing. Counted: "
                + LockHint.HINT_HIDDEN + " 3", statusLine());
    }

    @Test
    public void aPullOnAReelStillSaysWhatItDoes() {
        Settings.HIDE_2X_LOCK_HINT.save(true);
        for (ReelPill kept : new ReelPill[] {ReelPill.INITIATING_LOCK, ReelPill.INITIATING_RESTORE, ReelPill.UNLOCKED_ORIGINAL_SPEED}) {
            assertTrue(kept + " lost its text", LockHint.reelPill(kept, true));
        }
        assertTrue("a missing state lost its hints", LockHint.reelPill(null, true));
        String line = statusLine();
        assertFalse("a kept state was counted: " + line, line != null && line.contains("Counted"));
    }

    @Test
    public void offPausedOrWithoutTheHoldTheReelsPillIsFacebooks() {
        assertTrue("off, the Reels hint was hidden", LockHint.reelPill(ReelPill.UNLOCKED_2X_SPEED, true));

        Settings.HIDE_2X_LOCK_HINT.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertTrue("a paused Hushfacebook hid the Reels hint", LockHint.reelPill(ReelPill.UNLOCKED_2X_SPEED, true));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertTrue("the Reels hint was hidden before the settings were ready",
                LockHint.reelPill(ReelPill.UNLOCKED_2X_SPEED, true)));
        ReelHold.holdInBuildForTests = false;
        assertTrue("a build without Hold a reel for 2x hid the Reels hint", LockHint.reelPill(ReelPill.UNLOCKED_2X_SPEED, true));

        String line = statusLine();
        assertFalse("an answer left to Facebook was counted: " + line, line != null && line.contains("Counted"));
    }

    @Test
    public void thePopupsGoOnlyWithTheirSwitch() {
        assertFalse("the switch doesn't start off", Settings.HIDE_2X_POPUPS.get());
        assertFalse("off, a pill went away", LockHint.hidesPill());
        Settings.HIDE_2X_LOCK_HINT.save(true);
        assertFalse("the lock hint's switch took the whole pill", LockHint.hidesPill());
        String off = statusLine();
        assertFalse("an answer left to Facebook was counted: " + off, off != null && off.contains("Counted"));

        Settings.HIDE_2X_LOCK_HINT.save(false);
        Settings.HIDE_2X_POPUPS.save(true);
        assertTrue("on, a pill drew", LockHint.hidesPill());
        assertTrue(statusLine(), statusLine().contains("Counted: " + LockHint.PILL_HIDDEN + " 1"));

        ReelHold.holdInBuildForTests = false;
        assertFalse("a build without Hold a reel for 2x hid the pill", LockHint.hidesPill());
        ReelHold.holdInBuildForTests = true;

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("paused, a pill went away", LockHint.hidesPill());
    }

    @Test
    public void thePopupsSwitchNeedsNoRestartAndTravelsWithTheHold() {
        assertFalse("a new pill reads the switch as it's drawn", Settings.HIDE_2X_POPUPS.rebootApp);
        assertTrue("Pause and the report don't know the switch",
                PatchFamily.REEL_HOLD.switches.contains(Settings.HIDE_2X_POPUPS));
    }

    @Test
    public void theSwitchNeedsNoRestartAndTravelsWithTheHold() {
        assertFalse("a new pill reads the switch as it's made", Settings.HIDE_2X_LOCK_HINT.rebootApp);
        assertTrue("Pause and the report don't know the switch",
                PatchFamily.REEL_HOLD.switches.contains(Settings.HIDE_2X_LOCK_HINT));
    }
}
