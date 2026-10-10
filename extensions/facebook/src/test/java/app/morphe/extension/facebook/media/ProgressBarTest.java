/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.util.concurrent.TimeUnit;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Keep the progress bar: with the switch on, the Reels viewer's bar is kept full size each time
 * Facebook would shrink it, and a full-screen video's fade timer isn't set, each counted. Off,
 * paused, or before the settings are ready, both answer no, so Facebook shrinks the bar and fades
 * the controls as before.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ProgressBarTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.KEEP_PROGRESS_BAR.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.PROGRESS_BAR + ":")) return line;
        }
        return null;
    }

    @Test
    public void onTheBarAndTheControlsAreKeptEachTimeAndCounted() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        // A reel's controls hide, then it plays on: Facebook shrinks the bar twice.
        assertTrue("the reel's bar shrank with the switch on", ProgressBar.keepsReelBar());
        assertTrue("the reel's bar shrank with the switch on", ProgressBar.keepsReelBar());
        // A full-screen video's controls show once and are touched once.
        assertTrue("a fade timer was set with the switch on", ProgressBar.keepsControls());
        assertTrue("a fade timer was set with the switch on", ProgressBar.keepsControls());
        assertTrue("a fade timer was set with the switch on", ProgressBar.keepsControls());
        assertEquals(FamilyNames.PROGRESS_BAR + ": invoked 5, 2 found, 0 missing. Counted: "
                + ProgressBar.REEL_BAR_KEPT + " 2, " + ProgressBar.CONTROLS_KEPT + " 3", statusLine());
    }

    @Test
    public void offOrPausedFacebookShrinksAndFades() {
        assertFalse("the switch doesn't start off", Settings.KEEP_PROGRESS_BAR.get());
        assertFalse("off, the reel's bar was kept", ProgressBar.keepsReelBar());
        assertFalse("off, the controls were kept", ProgressBar.keepsControls());

        Settings.KEEP_PROGRESS_BAR.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertFalse("a Hushfacebook paused by " + reason + " kept the reel's bar", ProgressBar.keepsReelBar());
            assertFalse("a Hushfacebook paused by " + reason + " kept the controls", ProgressBar.keepsControls());
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> {
            assertFalse("the reel's bar was kept before the settings were ready", ProgressBar.keepsReelBar());
            assertFalse("the controls were kept before the settings were ready", ProgressBar.keepsControls());
        });

        String line = statusLine();
        assertFalse("an answer that left it to Facebook was counted: " + line, line != null && line.contains("Counted"));
        assertTrue("on again after the pause, the reel's bar wasn't kept", ProgressBar.keepsReelBar());
        assertTrue("on again after the pause, the controls weren't kept", ProgressBar.keepsControls());
    }

    @Test
    public void theSwitchNeedsNoRestartAndTravelsWithItsFamily() {
        assertFalse("each shrink and timer asks again, so no restart is needed", Settings.KEEP_PROGRESS_BAR.rebootApp);
        assertNull("nothing asks before the switch changes", Settings.KEEP_PROGRESS_BAR.userDialogMessage);
        assertTrue("Pause and the report don't know the switch",
                PatchFamily.PROGRESS_BAR.switches.contains(Settings.KEEP_PROGRESS_BAR));
    }

    @Test
    public void theTimeLabelIsHiddenAfterTheActiveLookAndCountedOnce() {
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        label.setVisibility(View.VISIBLE);
        ProgressBar.hideTimeLabel(label);
        assertEquals("the label stayed on screen", View.INVISIBLE, label.getVisibility());
        // Already hidden: nothing to do, nothing counted again.
        ProgressBar.hideTimeLabel(label);
        assertEquals(View.INVISIBLE, label.getVisibility());
        assertEquals(FamilyNames.PROGRESS_BAR + ": invoked 0, 0 found, 0 missing. Counted: "
                + ProgressBar.TIME_LABEL_HIDDEN + " 1", statusLine());
    }

    @Test
    public void aMissingTimeLabelIsLeftAlone() {
        ProgressBar.hideTimeLabel(null);
        assertNull("a missing label was reported", statusLine());
    }

    @Test
    public void onShortReelsGetTheBarAndItsRoomAndTheBarMovesEveryFrame() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        assertTrue("a short reel was left without a bar", ProgressBar.barsEveryReel());
        assertEquals("a short reel's caption kept Facebook's layout without room for the bar",
                Integer.MAX_VALUE, ProgressBar.reelLength(7));
        assertTrue("the bar kept Facebook's 100 ms wait", ProgressBar.smoothsBar());
        assertEquals(FamilyNames.PROGRESS_BAR + ": invoked 3, 3 found, 0 missing. Counted: "
                + ProgressBar.SHORT_REEL_BAR + " 1, " + ProgressBar.SHORT_REEL_ROOM + " 1, "
                + ProgressBar.BAR_SMOOTHED + " 1", statusLine());
    }

    @Test
    public void offOrPausedShortReelsAndTheBarStayFacebooks() {
        assertFalse("off, a short reel was given a bar", ProgressBar.barsEveryReel());
        assertEquals("off, a reel's length was changed", 7, ProgressBar.reelLength(7));
        assertFalse("off, the bar's wait was changed", ProgressBar.smoothsBar());

        Settings.KEEP_PROGRESS_BAR.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("paused, a short reel was given a bar", ProgressBar.barsEveryReel());
        assertEquals("paused, a reel's length was changed", 7, ProgressBar.reelLength(7));
        assertFalse("paused, the bar's wait was changed", ProgressBar.smoothsBar());
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> {
            assertFalse("a short reel was given a bar before the settings were ready", ProgressBar.barsEveryReel());
            assertEquals(7, ProgressBar.reelLength(7));
            assertFalse(ProgressBar.smoothsBar());
        });
        String line = statusLine();
        assertFalse("an answer that left it to Facebook was counted: " + line, line != null && line.contains("Counted"));
    }

    @Test
    public void withTheTimeSwitchTheLabelStaysAndRefreshesAFewTimesASecond() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        assertTrue("the time switch doesn't start on", Settings.KEEP_PROGRESS_BAR_TIME.get());
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        label.setVisibility(View.INVISIBLE);
        ProgressBar.hideTimeLabel(label);
        assertEquals("the time label was hidden with the time switch on", View.VISIBLE, label.getVisibility());

        assertTrue("a shown label wasn't refreshed", ProgressBar.refreshesTime(label));
        assertFalse("the label was refreshed again within " + ProgressBar.TIME_REFRESH_MS + " ms",
                ProgressBar.refreshesTime(label));
        ShadowSystemClock.advanceBy(ProgressBar.TIME_REFRESH_MS, TimeUnit.MILLISECONDS);
        assertTrue("the label wasn't refreshed after " + ProgressBar.TIME_REFRESH_MS + " ms",
                ProgressBar.refreshesTime(label));

        LinearLayout next = new LinearLayout(RuntimeEnvironment.getApplication());
        next.setVisibility(View.VISIBLE);
        assertTrue("the next reel's label waited on the last one's refresh", ProgressBar.refreshesTime(next));
        next.setVisibility(View.INVISIBLE);
        ShadowSystemClock.advanceBy(ProgressBar.TIME_REFRESH_MS, TimeUnit.MILLISECONDS);
        assertFalse("a label off screen was refreshed", ProgressBar.refreshesTime(next));
        assertFalse("a missing label was refreshed", ProgressBar.refreshesTime(null));
    }

    @Test
    public void withoutTheTimeSwitchTheLabelHidesAndIsNeverRefreshed() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        Settings.KEEP_PROGRESS_BAR_TIME.save(false);
        try {
            LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
            label.setVisibility(View.VISIBLE);
            ProgressBar.hideTimeLabel(label);
            assertEquals("the label stayed with the time switch off", View.INVISIBLE, label.getVisibility());
            label.setVisibility(View.VISIBLE);
            assertFalse("the label was refreshed with the time switch off", ProgressBar.refreshesTime(label));
        } finally {
            Settings.KEEP_PROGRESS_BAR_TIME.resetToDefault();
        }
        Settings.KEEP_PROGRESS_BAR.save(false);
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        label.setVisibility(View.VISIBLE);
        assertFalse("the label was refreshed with the bar not kept", ProgressBar.refreshesTime(label));
    }

    @Test
    public void theTimeSwitchNeedsNoRestartAndTravelsWithItsFamily() {
        assertFalse(Settings.KEEP_PROGRESS_BAR_TIME.rebootApp);
        assertNull(Settings.KEEP_PROGRESS_BAR_TIME.userDialogMessage);
        assertTrue("Pause and the report don't know the time switch",
                PatchFamily.PROGRESS_BAR.switches.contains(Settings.KEEP_PROGRESS_BAR_TIME));
    }
}
