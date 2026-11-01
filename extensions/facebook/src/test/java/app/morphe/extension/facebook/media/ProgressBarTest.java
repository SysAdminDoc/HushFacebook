/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowSystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
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
        Settings.TAP_TO_PLAY.resetToDefault();
        ProgressBar.footerLiftForTests = null;
        ProgressBar.updateAgainForTests = null;
        ProgressBar.handOverAgainForTests = null;
        ProgressBar.forgetWatchesForTests();
        ProgressBar.forgetFootersForTests();
        ProgressBar.forgetHandOversForTests();
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
    public void whileKeptAReelFacebookGaveNoBarGetsOneOnceItsLengthIsKnown() {
        // Off: Facebook's answer stands either way.
        assertFalse("off, a reel without a bar got one", ProgressBar.hasBar(false, 30_000));
        assertTrue("off, a reel with a bar lost it", ProgressBar.hasBar(true, 30_000));

        Settings.KEEP_PROGRESS_BAR.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("a paused Hushfacebook gave a reel a bar", ProgressBar.hasBar(false, 30_000));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
                assertFalse("a reel got a bar before the settings were ready", ProgressBar.hasBar(false, 30_000)));
        String line = statusLine();
        assertFalse("an answer that left it to Facebook was counted: " + line, line != null && line.contains("Counted"));

        assertTrue("a reel Facebook had a bar for lost it", ProgressBar.hasBar(true, 30_000));
        assertTrue("a reel Facebook gave no bar didn't get one", ProgressBar.hasBar(false, 30_000));
        // A length not known yet (or a live video's) leaves Facebook's no, as the bar would have nothing to show.
        assertFalse("a reel of unknown length got a bar", ProgressBar.hasBar(false, 0));
        assertFalse("a reel of unknown length got a bar", ProgressBar.hasBar(false, -1));
        line = statusLine();
        assertTrue("only the reel given a bar was counted: " + line,
                line != null && line.endsWith("Counted: " + ProgressBar.NO_BAR_REEL + " 1"));
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
    public void onShortReelsKeepTheBarTheCaptionStaysAboveItAndTheBarMovesEveryFrame() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        assertTrue("a short reel was left without a bar", ProgressBar.barsEveryReel());
        assertTrue("a short reel was given the faded bar", ProgressBar.noFadedBar());
        assertTrue("a playing reel's caption went back down onto the bar", ProgressBar.liftsFooter());
        assertTrue("the bar kept Facebook's 100 ms wait", ProgressBar.smoothsBar());
        assertEquals(FamilyNames.PROGRESS_BAR + ": invoked 4, 4 found, 0 missing. Counted: "
                + ProgressBar.SHORT_REEL_BAR + " 1, " + ProgressBar.FADED_LOOK_SKIPPED + " 1, "
                + ProgressBar.FOOTER_LIFTED + " 1, " + ProgressBar.BAR_SMOOTHED + " 1", statusLine());
    }

    /** The next reel starts without the last one's graph and chapter marks only while the bar is kept. */
    @Test
    public void theNextReelIsClearedOnlyWhileTheBarIsKept() {
        assertFalse("off, the last reel's graph was cleared", ProgressBar.clearsLastReel());
        Settings.KEEP_PROGRESS_BAR.save(true);
        assertTrue("kept, the last reel's graph stayed for the next", ProgressBar.clearsLastReel());
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("paused, the last reel's graph was cleared", ProgressBar.clearsLastReel());
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse("cleared before the settings were ready", ProgressBar.clearsLastReel()));
        String line = statusLine();
        assertTrue("only the kept reel was counted: " + line, line.endsWith(", 1 found, 0 missing. Counted: "
                + ProgressBar.NEXT_REEL_CLEARED + " 1"));
    }

    /**
     * The scrubber takes the next reel with the last one's bar position and time (#90, #120). While
     * the bar is kept or Tap to play is on, the bar starts over and a time label that stays up hides
     * until the reel's own time is written.
     */
    @Test
    public void theNextReelStartsWithoutTheLastOnesPositionOrTime() {
        LinearLayout reel = new LinearLayout(RuntimeEnvironment.getApplication());
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        SeekBar bar = new SeekBar(RuntimeEnvironment.getApplication());
        reel.addView(label);
        reel.addView(bar);

        // Off: the last reel's position and time stay, as Facebook leaves them.
        bar.setProgress(47);
        ProgressBar.nextReel(label, bar);
        assertEquals("off, the bar started over", 47, bar.getProgress());
        assertEquals("off, the time label hid", View.VISIBLE, label.getVisibility());

        // Tap to play alone holds reels on Facebook's thin line, which has no time label up.
        Settings.TAP_TO_PLAY.save(true);
        ProgressBar.nextReel(label, bar);
        assertEquals("a held reel showed the last reel's position", 0, bar.getProgress());
        assertEquals("the time label hid with the bar not kept", View.VISIBLE, label.getVisibility());
        Settings.TAP_TO_PLAY.save(false);

        Settings.KEEP_PROGRESS_BAR.save(true);
        bar.setProgress(47);
        assertTrue(ProgressBar.refreshesTime(label, bar));
        ProgressBar.nextReel(label, bar);
        assertEquals("the kept bar showed the last reel's position", 0, bar.getProgress());
        assertEquals("the last reel's time stayed up", View.INVISIBLE, label.getVisibility());
        // Already at the start: nothing to do, nothing counted again.
        ProgressBar.nextReel(label, bar);
        assertEquals(0, bar.getProgress());
        // The reel plays: its first update brings the label back with its own time, without the
        // last reel's refresh holding it back.
        assertTrue("the next reel's time waited on the last reel's refresh", ProgressBar.refreshesTime(label, bar));
        assertEquals(View.VISIBLE, label.getVisibility());

        // Without the time switch the label isn't the patch's to hide.
        Settings.KEEP_PROGRESS_BAR_TIME.save(false);
        try {
            ProgressBar.nextReel(label, bar);
            assertEquals("the label hid with the time switch off", View.VISIBLE, label.getVisibility());
        } finally {
            Settings.KEEP_PROGRESS_BAR_TIME.resetToDefault();
        }

        // A missing label or bar is left alone.
        ProgressBar.nextReel(null, null);
        String line = statusLine();
        assertTrue("two start-overs weren't counted: " + line,
                line.contains(ProgressBar.NEXT_REEL_STARTED_OVER + " 2"));
        assertFalse("the hook threw: " + line, line.contains("threw"));
    }

    /**
     * Facebook builds the next reel's bar info right before the scrubber takes it (S22, #120), so
     * the time label shows 0:00 and that reel's length at once, as Facebook's writer puts them,
     * instead of hiding until the reel plays. A bar info that's old, taken already or from another
     * thread isn't the next reel's, and the label hides as before.
     */
    @Test
    public void theNextReelShowsItsStartAndLengthRightAway() throws InterruptedException {
        LinearLayout reel = new LinearLayout(RuntimeEnvironment.getApplication());
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        TextView elapsed = new TextView(RuntimeEnvironment.getApplication());
        TextView slash = new TextView(RuntimeEnvironment.getApplication());
        TextView total = new TextView(RuntimeEnvironment.getApplication());
        label.addView(elapsed);
        label.addView(slash);
        label.addView(total);
        elapsed.setText("0:41");
        slash.setText("/");
        total.setText("0:44");
        SeekBar bar = new SeekBar(RuntimeEnvironment.getApplication());
        reel.addView(label);
        reel.addView(bar);
        Locale was = Locale.getDefault();
        Locale.setDefault(Locale.US);
        try {
            Settings.KEEP_PROGRESS_BAR.save(true);
            bar.setProgress(47);
            assertTrue(ProgressBar.refreshesTime(label, bar));
            assertTrue("the bar info's answer changed", ProgressBar.hasBar(true, 808_320));
            ProgressBar.nextReel(label, bar);
            assertEquals(0, bar.getProgress());
            assertEquals("the time hid until the reel plays", View.VISIBLE, label.getVisibility());
            assertEquals("0:00", elapsed.getText().toString());
            assertEquals("/", slash.getText().toString());
            assertEquals("the length isn't written as Facebook writes it", "13:28", total.getText().toString());
            assertTrue("the reel's first update waited on the last reel's refresh", ProgressBar.refreshesTime(label, bar));

            // Taken once: the next reel without a new bar info hides its time as before.
            ProgressBar.nextReel(label, bar);
            assertEquals(View.INVISIBLE, label.getVisibility());

            // Built too long ago to be the next reel's.
            label.setVisibility(View.VISIBLE);
            ProgressBar.hasBar(true, 10_016);
            ShadowSystemClock.advanceBy(ProgressBar.BAR_INFO_FRESH_MS + 1, TimeUnit.MILLISECONDS);
            ProgressBar.nextReel(label, bar);
            assertEquals(View.INVISIBLE, label.getVisibility());
            assertEquals("an old bar info's length was shown", "13:28", total.getText().toString());

            // A label hidden before comes back with the bar up, and an hour long reel reads h:mm:ss.
            ProgressBar.hasBar(false, 10_892_365);
            ProgressBar.nextReel(label, bar);
            assertEquals(View.VISIBLE, label.getVisibility());
            assertEquals("3:01:32", total.getText().toString());

            // A label Facebook took out of the layout isn't the patch's to show.
            label.setVisibility(View.GONE);
            ProgressBar.hasBar(true, 22_000);
            ProgressBar.nextReel(label, bar);
            assertTrue("a gone label came back", label.getVisibility() != View.VISIBLE);
            label.setVisibility(View.VISIBLE);
            total.setText("3:01:32");

            // Built on another thread: some other player's.
            Thread other = new Thread(() -> ProgressBar.hasBar(true, 22_000));
            other.start();
            other.join();
            ProgressBar.nextReel(label, bar);
            assertEquals(View.INVISIBLE, label.getVisibility());
            assertEquals("another thread's bar info was shown", "3:01:32", total.getText().toString());

            // A label without its two times hides as before.
            LinearLayout bare = new LinearLayout(RuntimeEnvironment.getApplication());
            reel.addView(bare);
            ProgressBar.hasBar(true, 22_000);
            ProgressBar.nextReel(bare, bar);
            assertEquals(View.INVISIBLE, bare.getVisibility());

            assertEquals("0:00", ProgressBar.time(999));
            assertEquals("59:59", ProgressBar.time(3_599_999));
            assertEquals("1:00:00", ProgressBar.time(3_600_000));
            String line = statusLine();
            assertTrue("two shown times weren't counted: " + line,
                    line.contains(ProgressBar.NEXT_REEL_TIME_SHOWN + " 2"));
            assertFalse("the hook threw: " + line, line.contains("threw"));
        } finally {
            Locale.setDefault(was);
        }
    }

    @Test
    public void pausedOrNotReadyTheNextReelIsFacebooks() {
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        SeekBar bar = new SeekBar(RuntimeEnvironment.getApplication());
        Settings.KEEP_PROGRESS_BAR.save(true);
        Settings.TAP_TO_PLAY.save(true);
        bar.setProgress(47);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            ProgressBar.nextReel(label, bar);
            assertEquals("a Hushfacebook paused by " + reason + " moved the bar", 47, bar.getProgress());
            assertEquals("a Hushfacebook paused by " + reason + " hid the time", View.VISIBLE, label.getVisibility());
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> {
            ProgressBar.nextReel(label, bar);
            assertEquals("the bar moved before the settings were ready", 47, bar.getProgress());
        });
        String line = statusLine();
        assertFalse("an answer that left it to Facebook was counted: " + line, line != null && line.contains("Counted"));
    }

    /** The looks that leave no bar are counted while it's kept, and the others aren't. */
    @Test
    public void theLooksThatLeaveNoBarAreCountedWhileItsKept() {
        ProgressBar.look(ProgressBar.FADED);
        assertEquals("off, a look was counted", FamilyNames.PROGRESS_BAR + ": invoked 1, 0 found, 0 missing", statusLine());
        Settings.KEEP_PROGRESS_BAR.save(true);
        for (Integer look : new Integer[]{0, 1, 3, null}) ProgressBar.look(look);
        assertEquals("a look that shows the bar was counted",
                FamilyNames.PROGRESS_BAR + ": invoked 1, 0 found, 0 missing", statusLine());
        ProgressBar.look(ProgressBar.FADED);
        ProgressBar.look(ProgressBar.INVISIBLE);
        ProgressBar.look(ProgressBar.INVISIBLE);
        assertEquals(FamilyNames.PROGRESS_BAR + ": invoked 4, 1 found, 0 missing. Counted: "
                + ProgressBar.FADED_LOOK + " 1, " + ProgressBar.INVISIBLE_LOOK + " 2", statusLine());
    }

    /** A look that shows the bar takes the place of a hide Facebook parked, only while the bar is kept (#73). */
    @Test
    public void aLookThatShowsTheBarTakesAParkedHidesPlaceWhileKept() {
        assertFalse("off, a parked hide was replaced", ProgressBar.replacesParkedLook(0, ProgressBar.INVISIBLE));
        Settings.KEEP_PROGRESS_BAR.save(true);
        for (Integer look : new Integer[]{0, 1, 3}) {
            assertTrue("look " + look + " left a parked hide", ProgressBar.replacesParkedLook(look, ProgressBar.INVISIBLE));
            assertTrue("look " + look + " left a parked fade", ProgressBar.replacesParkedLook(look, ProgressBar.FADED));
        }
        assertFalse("a hide took a parked fade's place", ProgressBar.replacesParkedLook(ProgressBar.INVISIBLE, ProgressBar.FADED));
        assertFalse("a fade took a parked hide's place", ProgressBar.replacesParkedLook(ProgressBar.FADED, ProgressBar.INVISIBLE));
        assertFalse("a parked look that shows the bar was replaced", ProgressBar.replacesParkedLook(0, 1));
        assertFalse("with nothing parked the skip was passed", ProgressBar.replacesParkedLook(0, null));
        assertFalse(ProgressBar.replacesParkedLook(null, ProgressBar.INVISIBLE));
        String line = statusLine();
        assertTrue("the six replaced hides weren't counted: " + line, line.contains(ProgressBar.PARKED_HIDE_REPLACED + " 6"));
    }

    @Test
    public void offOrPausedShortReelsAndTheBarStayFacebooks() {
        assertFalse("off, a short reel was given a bar", ProgressBar.barsEveryReel());
        assertFalse("off, a short reel's faded bar was skipped", ProgressBar.noFadedBar());
        assertFalse("off, a playing reel's caption was lifted", ProgressBar.liftsFooter());
        assertFalse("off, the bar's wait was changed", ProgressBar.smoothsBar());

        Settings.KEEP_PROGRESS_BAR.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("paused, a short reel was given a bar", ProgressBar.barsEveryReel());
        assertFalse("paused, a short reel's faded bar was skipped", ProgressBar.noFadedBar());
        assertFalse("paused, a playing reel's caption was lifted", ProgressBar.liftsFooter());
        assertFalse("paused, the bar's wait was changed", ProgressBar.smoothsBar());
        assertFalse("paused, a parked hide was replaced", ProgressBar.replacesParkedLook(0, ProgressBar.INVISIBLE));
        ProgressBar.look(ProgressBar.FADED);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> {
            assertFalse("a short reel was given a bar before the settings were ready", ProgressBar.barsEveryReel());
            assertFalse(ProgressBar.noFadedBar());
            assertFalse(ProgressBar.liftsFooter());
            assertFalse(ProgressBar.smoothsBar());
            assertFalse(ProgressBar.replacesParkedLook(0, ProgressBar.INVISIBLE));
            ProgressBar.look(ProgressBar.INVISIBLE);
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

        assertTrue("a shown label wasn't refreshed", ProgressBar.refreshesTime(label, null));
        assertFalse("the label was refreshed again within " + ProgressBar.TIME_REFRESH_MS + " ms",
                ProgressBar.refreshesTime(label, null));
        ShadowSystemClock.advanceBy(ProgressBar.TIME_REFRESH_MS, TimeUnit.MILLISECONDS);
        assertTrue("the label wasn't refreshed after " + ProgressBar.TIME_REFRESH_MS + " ms",
                ProgressBar.refreshesTime(label, null));

        LinearLayout next = new LinearLayout(RuntimeEnvironment.getApplication());
        next.setVisibility(View.VISIBLE);
        assertTrue("the next reel's label waited on the last one's refresh", ProgressBar.refreshesTime(next, null));
        next.setVisibility(View.INVISIBLE);
        ShadowSystemClock.advanceBy(ProgressBar.TIME_REFRESH_MS, TimeUnit.MILLISECONDS);
        assertFalse("a label off screen was refreshed", ProgressBar.refreshesTime(next, null));
        assertFalse("a missing label was refreshed", ProgressBar.refreshesTime(null, null));
    }

    @Test
    public void twoReelsUpdatingTogetherEachRefreshAFewTimesASecond() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        LinearLayout shown = new LinearLayout(RuntimeEnvironment.getApplication());
        LinearLayout readied = new LinearLayout(RuntimeEnvironment.getApplication());
        assertTrue(ProgressBar.refreshesTime(shown, null));
        assertTrue(ProgressBar.refreshesTime(readied, null));
        assertFalse("the first label was refreshed again because a second one updated in between",
                ProgressBar.refreshesTime(shown, null));
        assertFalse(ProgressBar.refreshesTime(readied, null));
        ShadowSystemClock.advanceBy(ProgressBar.TIME_REFRESH_MS, TimeUnit.MILLISECONDS);
        assertTrue(ProgressBar.refreshesTime(shown, null));
        assertTrue(ProgressBar.refreshesTime(readied, null));
    }

    @Test
    public void aLabelInsideAHiddenReelIsNotRefreshed() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        LinearLayout reel = new LinearLayout(RuntimeEnvironment.getApplication());
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        reel.addView(label);
        reel.setVisibility(View.GONE);
        assertFalse("a label whose reel is hidden was refreshed", ProgressBar.refreshesTime(label, null));
        reel.setVisibility(View.VISIBLE);
        assertTrue(ProgressBar.refreshesTime(label, null));
    }

    @Test
    public void aTimeLabelFacebookLeftHiddenComesBackOnlyWhileTheBarShowsUndragged() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        LinearLayout reel = new LinearLayout(RuntimeEnvironment.getApplication());
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        SeekBar bar = new SeekBar(RuntimeEnvironment.getApplication());
        reel.addView(label);
        reel.addView(bar);
        // #73: the reel ended, or a drag showed preview frames, and Facebook left the label hidden.
        label.setVisibility(View.INVISIBLE);
        bar.setPressed(true);
        assertFalse("the label came back mid-drag", ProgressBar.refreshesTime(label, bar));
        assertEquals(View.INVISIBLE, label.getVisibility());
        bar.setPressed(false);
        bar.setVisibility(View.INVISIBLE);
        assertFalse("the label came back with no bar on screen", ProgressBar.refreshesTime(label, bar));
        assertEquals(View.INVISIBLE, label.getVisibility());
        assertFalse("the label came back without its bar", ProgressBar.refreshesTime(label, null));
        bar.setVisibility(View.VISIBLE);
        reel.setVisibility(View.GONE);
        assertFalse("the label came back in a hidden reel", ProgressBar.refreshesTime(label, bar));
        assertEquals(View.INVISIBLE, label.getVisibility());

        reel.setVisibility(View.VISIBLE);
        assertTrue("a hidden label wasn't put back and refreshed while the bar showed",
                ProgressBar.refreshesTime(label, bar));
        assertEquals(View.VISIBLE, label.getVisibility());
        // Shown: nothing to put back, nothing counted again.
        ShadowSystemClock.advanceBy(ProgressBar.TIME_REFRESH_MS, TimeUnit.MILLISECONDS);
        assertTrue(ProgressBar.refreshesTime(label, bar));
        // Gone is Facebook taking the label out of the layout, not a look leaving it hidden.
        label.setVisibility(View.GONE);
        ShadowSystemClock.advanceBy(ProgressBar.TIME_REFRESH_MS, TimeUnit.MILLISECONDS);
        assertFalse("a gone label was refreshed", ProgressBar.refreshesTime(label, bar));
        assertEquals("a gone label came back", View.GONE, label.getVisibility());
        assertEquals(FamilyNames.PROGRESS_BAR + ": invoked 0, 0 found, 0 missing. Counted: "
                + ProgressBar.TIME_LABEL_BACK + " 1", statusLine());
    }

    @Test
    public void withoutTheTimeSwitchAHiddenLabelStaysHidden() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        Settings.KEEP_PROGRESS_BAR_TIME.save(false);
        try {
            LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
            SeekBar bar = new SeekBar(RuntimeEnvironment.getApplication());
            label.setVisibility(View.INVISIBLE);
            assertFalse(ProgressBar.refreshesTime(label, bar));
            assertEquals("the label came back with the time switch off", View.INVISIBLE, label.getVisibility());
        } finally {
            Settings.KEEP_PROGRESS_BAR_TIME.resetToDefault();
        }
        Settings.KEEP_PROGRESS_BAR.save(false);
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        label.setVisibility(View.INVISIBLE);
        assertFalse(ProgressBar.refreshesTime(label, new SeekBar(RuntimeEnvironment.getApplication())));
        assertEquals("the label came back with the bar not kept", View.INVISIBLE, label.getVisibility());
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
            assertFalse("the label was refreshed with the time switch off", ProgressBar.refreshesTime(label, null));
        } finally {
            Settings.KEEP_PROGRESS_BAR_TIME.resetToDefault();
        }
        Settings.KEEP_PROGRESS_BAR.save(false);
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        label.setVisibility(View.VISIBLE);
        assertFalse("the label was refreshed with the bar not kept", ProgressBar.refreshesTime(label, null));
    }

    /** A reel's scrubber views: the bar and its time label in one parent, both on screen. */
    private static LinearLayout scrubberViews(LinearLayout label, SeekBar bar) {
        LinearLayout reel = new LinearLayout(RuntimeEnvironment.getApplication());
        reel.addView(label);
        reel.addView(bar);
        return reel;
    }

    @Test
    public void aHandOverFacebookLeavesOnTheLastReelHidesTheBarAndItsTimeUntilAReelTakesIt() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        SeekBar bar = new SeekBar(RuntimeEnvironment.getApplication());
        scrubberViews(label, bar);
        bar.setProgress(62);
        // #120: Up next plays reel b, and Facebook returns before it gives the scrubber b's player.
        ProgressBar.handsOver(label, bar, "a", "b");
        ProgressBar.leftOnLastReel();
        assertEquals("the last reel's bar stayed on screen", View.INVISIBLE, bar.getVisibility());
        assertEquals("the last reel's time stayed on screen", View.INVISIBLE, label.getVisibility());
        // The last reel's update, a player event or a touch path asks for a look that shows the bar.
        assertFalse("the last reel's time was written", ProgressBar.refreshesTime(label, bar));
        assertEquals("the time label came back over the last reel", View.INVISIBLE, label.getVisibility());
        for (int look : new int[] {0, 1, 3}) {
            assertNull("look " + look + " could show the last reel's bar", ProgressBar.lookFor(label, look));
        }
        assertEquals(View.INVISIBLE, bar.getVisibility());

        // Facebook hands the scrubber a reel at last: its light mount runs.
        ProgressBar.mounts(label, null);
        assertEquals("the bar didn't come back once a reel took it", View.VISIBLE, bar.getVisibility());
        Integer passive = 0;
        assertSame("a look was held back once a reel took the bar", passive, ProgressBar.lookFor(label, passive));
        assertTrue("the time didn't come back with the reel's update", ProgressBar.refreshesTime(label, bar));
        assertEquals(View.VISIBLE, label.getVisibility());
        String line = statusLine();
        assertTrue(line, line.contains(ProgressBar.LAST_REEL_HIDDEN + " 1"));
        assertTrue(line, line.contains(ProgressBar.LAST_REEL_LOOK + " 3"));
        assertTrue(line, line.contains(ProgressBar.LAST_REEL_BACK + " 1"));
    }

    @Test
    public void aHandOverFacebookFinishesLeavesTheBarAlone() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        SeekBar bar = new SeekBar(RuntimeEnvironment.getApplication());
        scrubberViews(label, bar);
        // A swipe: Facebook gives the scrubber the next reel's player, which runs the light mount.
        ProgressBar.handsOver(label, bar, "a", "b");
        ProgressBar.mounts(label, null);
        ProgressBar.leftOnLastReel();
        assertEquals(View.VISIBLE, bar.getVisibility());
        assertEquals(View.VISIBLE, label.getVisibility());
        Integer active = 1;
        assertSame(active, ProgressBar.lookFor(label, active));
        // The reel the bar already holds: Facebook skips the hand-over, and so does the extension.
        ProgressBar.handsOver(label, bar, "b", "b");
        ProgressBar.leftOnLastReel();
        assertEquals(View.VISIBLE, bar.getVisibility());
        String line = statusLine();
        assertFalse("a finished hand-over hid the bar: " + line, line != null && line.contains(ProgressBar.LAST_REEL_HIDDEN));
    }

    @Test
    public void aReelTheRegistryHasNoScrubberForHidesTheLastReelsBarUntilItsReelIsBack() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        SeekBar bar = new SeekBar(RuntimeEnvironment.getApplication());
        scrubberViews(label, bar);
        // Reel a is handed the scrubber and plays to 18%.
        ProgressBar.registryAsks(false, 1);
        ProgressBar.handsOver(label, bar, null, "a");
        ProgressBar.mounts(label, null);
        ProgressBar.leftOnLastReel();
        bar.setProgress(18);
        assertEquals(View.VISIBLE, bar.getVisibility());
        // #120: the registry has no scrubber for the next reel and returns before any hand-over.
        ProgressBar.registryAsks(false, 2);
        ProgressBar.noScrubber();
        assertEquals("the last reel's bar stayed on screen", View.INVISIBLE, bar.getVisibility());
        assertEquals("the last reel's time stayed on screen", View.INVISIBLE, label.getVisibility());
        assertNull("a look could show the last reel's bar", ProgressBar.lookFor(label, 1));
        // Back to a: the registry picks the scrubber, which still holds a.
        ProgressBar.registryAsks(false, 1);
        ProgressBar.handsOver(label, bar, "a", "a");
        assertEquals("the bar didn't come back with its own reel", View.VISIBLE, bar.getVisibility());
        Integer active = 1;
        assertSame("a look was held back with the bar's own reel back", active, ProgressBar.lookFor(label, active));
        String line = statusLine();
        assertTrue(line, line.contains(ProgressBar.NO_SCRUBBER_HIDDEN + " 1"));
        assertTrue(line, line.contains(ProgressBar.LAST_REEL_BACK + " 1"));
        assertFalse(line, line.contains(ProgressBar.REGISTRY_HID_EVERY_BAR));

        // Hidden again, then another reel takes the scrubber: its light mount brings the bar back.
        ProgressBar.noScrubber();
        assertEquals(View.INVISIBLE, bar.getVisibility());
        ProgressBar.handsOver(label, bar, "a", "c");
        ProgressBar.mounts(label, null);
        assertEquals("the bar didn't come back once a reel took it", View.VISIBLE, bar.getVisibility());
    }

    @Test
    public void aScrubberLoadingAReelEndsTheHoldOnItsBar() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        SeekBar bar = new SeekBar(RuntimeEnvironment.getApplication());
        scrubberViews(label, bar);
        ProgressBar.registryAsks(false, 1);
        ProgressBar.handsOver(label, bar, null, "a");
        ProgressBar.mounts(label, null);
        ProgressBar.leftOnLastReel();
        // #120: after a refresh the registry has nothing for the reel, then a scrubber loads it directly.
        ProgressBar.noScrubber();
        assertEquals(View.INVISIBLE, bar.getVisibility());
        assertNull("a look could show the last reel's bar", ProgressBar.lookFor(label, 1));
        List<Object> runs = new ArrayList<>();
        ProgressBar.updateAgainForTests = runs::add;
        Object scrubber = new Object();
        ProgressBar.loads(label, scrubber, "b");
        assertEquals("the hold outlived the load", View.VISIBLE, bar.getVisibility());
        Integer active = 1;
        assertSame("a look was held back after the load", active, ProgressBar.lookFor(label, active));
        idle(ProgressBar.UPDATE_CHECK_MS);
        assertEquals("the loaded reel's update wasn't run again", 1, runs.size());
        assertSame(scrubber, runs.get(0));
        String line = statusLine();
        assertTrue(line, line.contains(ProgressBar.LAST_REEL_BACK + " 1"));
    }

    /** Facebook's side of a hand-over run again: the scrubber's bar info, and every call made. */
    private static final class FakeRegistry implements ProgressBar.HandOverAgain {
        final List<Object[]> handOvers = new ArrayList<>();
        Object info;
        Object builds;

        @Override
        public Object swapBarInfo(Object scrubber, Object next) {
            Object was = info;
            info = next;
            return was;
        }

        @Override
        public void handOver(Object registry, Object context, Object session, Object reel, Object host, Object surface,
                             Object origin, Object store, Integer key, int index) {
            handOvers.add(new Object[] {registry, context, session, reel, host, surface, origin, store, key, index});
            assertNull("the registry would skip the reel the bar info holds", info);
            if (builds != null) info = builds;
        }
    }

    private static final Object[] HAND_OVER = {"registry", "context", "session", "reel", "host", "surface", "origin", "store", 7, 3};

    private static void missesHandOver() {
        ProgressBar.missesHandOver(HAND_OVER[0], HAND_OVER[1], HAND_OVER[2], HAND_OVER[3], HAND_OVER[4], HAND_OVER[5],
                HAND_OVER[6], HAND_OVER[7], (Integer) HAND_OVER[8], (Integer) HAND_OVER[9]);
    }

    @Test
    public void aHandOverWithNoScrubberRunsAgainOnceOneLoads() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        FakeRegistry registry = new FakeRegistry();
        registry.info = "loaded";
        registry.builds = "built";
        ProgressBar.handOverAgainForTests = registry;
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        missesHandOver();
        ProgressBar.loads(label, new Object(), "b");
        assertTrue("the hand-over ran inside the load", registry.handOvers.isEmpty());
        idle(0);
        assertEquals("the hand-over didn't run again once", 1, registry.handOvers.size());
        assertArrayEquals("the hand-over's arguments changed", HAND_OVER, registry.handOvers.get(0));
        assertEquals("the bar info the hand-over built was lost", "built", registry.info);
        String line = statusLine();
        assertTrue(line, line.contains(ProgressBar.HAND_OVER_AGAIN + " 1"));

        ProgressBar.loads(label, new Object(), "c");
        idle(0);
        assertEquals("a load with nothing missed handed over", 1, registry.handOvers.size());
    }

    @Test
    public void aHandOverThatStopsShortOrThrowsLeavesTheBarInfoTheLoadBuilt() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        FakeRegistry registry = new FakeRegistry();
        registry.info = "loaded";
        ProgressBar.handOverAgainForTests = registry;
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        missesHandOver();
        ProgressBar.loads(label, new Object(), "b");
        idle(0);
        assertEquals(1, registry.handOvers.size());
        assertEquals("the load's bar info didn't come back", "loaded", registry.info);

        ProgressBar.handOverAgainForTests = new ProgressBar.HandOverAgain() {
            @Override
            public Object swapBarInfo(Object scrubber, Object next) {
                return registry.swapBarInfo(scrubber, next);
            }

            @Override
            public void handOver(Object r, Object c, Object s, Object u, Object h, Object f, Object o, Object t, Integer k, int i) {
                throw new IllegalStateException("Facebook threw");
            }
        };
        missesHandOver();
        ProgressBar.loads(label, new Object(), "b");
        idle(0);
        assertEquals("the load's bar info didn't come back after a throw", "loaded", registry.info);
    }

    @Test
    public void aMissedHandOverWaitsOnlySoLongAndEndsWithARealOne() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        FakeRegistry registry = new FakeRegistry();
        ProgressBar.handOverAgainForTests = registry;
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        SeekBar bar = new SeekBar(RuntimeEnvironment.getApplication());
        scrubberViews(label, bar);

        missesHandOver();
        idle(ProgressBar.MISSED_HAND_OVER_MS);
        ProgressBar.loads(label, new Object(), "b");
        idle(0);
        assertTrue("a late load ran a hand-over that went stale", registry.handOvers.isEmpty());

        missesHandOver();
        ProgressBar.handsOver(label, bar, null, "b");
        ProgressBar.loads(label, new Object(), "b");
        idle(0);
        assertTrue("a hand-over that found a scrubber ran again", registry.handOvers.isEmpty());

        // A newer miss replaces the older one, and its wait isn't cut short by the older one's.
        missesHandOver();
        idle(ProgressBar.MISSED_HAND_OVER_MS - 1);
        missesHandOver();
        idle(1);
        ProgressBar.loads(label, new Object(), "b");
        idle(0);
        assertEquals("the newer miss was dropped with the older", 1, registry.handOvers.size());
    }

    @Test
    public void aMissedHandOverIsLeftToFacebookOffOrPaused() {
        FakeRegistry registry = new FakeRegistry();
        ProgressBar.handOverAgainForTests = registry;
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        Settings.KEEP_PROGRESS_BAR.save(false);
        missesHandOver();
        ProgressBar.loads(label, new Object(), "b");
        idle(0);
        assertTrue("the switch is off", registry.handOvers.isEmpty());

        Settings.KEEP_PROGRESS_BAR.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        missesHandOver();
        ProgressBar.loads(label, new Object(), "b");
        idle(0);
        assertTrue("paused", registry.handOvers.isEmpty());
        PauseForTests.resume();

        // Paused between the load and its run: the hand-over stays Facebook's.
        missesHandOver();
        ProgressBar.loads(label, new Object(), "b");
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        idle(0);
        assertTrue("paused before it ran", registry.handOvers.isEmpty());
    }

    @Test
    public void aLoadWithoutAHoldLeavesTheBarAlone() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        SeekBar bar = new SeekBar(RuntimeEnvironment.getApplication());
        scrubberViews(label, bar);
        bar.setVisibility(View.GONE);
        ProgressBar.loads(label, new Object(), "b");
        assertEquals("a load changed a bar nothing hid", View.GONE, bar.getVisibility());
        ProgressBar.loads(null, new Object(), "b");
    }

    @Test
    public void anOlderScrubbersUnloadLeavesTheNewerHolder() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        Object older = new Object();
        Object newer = new Object();
        assertTrue("the older unload took the newer holder", ProgressBar.keepsDefaultHolder(newer, older));
        String line = statusLine();
        assertTrue(line, line.contains(ProgressBar.DEFAULT_HOLDER_KEPT + " 1"));
        assertFalse("a scrubber's own holder stayed past its unload", ProgressBar.keepsDefaultHolder(newer, newer));
        assertFalse("nothing to keep", ProgressBar.keepsDefaultHolder(null, older));
        assertFalse(ProgressBar.keepsDefaultHolder(null, null));
    }

    @Test
    public void facebookClearsTheHolderWithTheSwitchOffOrPaused() {
        Object older = new Object();
        Object newer = new Object();
        Settings.KEEP_PROGRESS_BAR.save(false);
        assertFalse("the switch is off", ProgressBar.keepsDefaultHolder(newer, older));
        Settings.KEEP_PROGRESS_BAR.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("paused", ProgressBar.keepsDefaultHolder(newer, older));
    }

    @Test
    public void theRegistrysHideIsCountedAndNothingChangesOffOrPaused() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        ProgressBar.registryAsks(true, 3);
        String line = statusLine();
        assertTrue(line, line.contains(ProgressBar.REGISTRY_HID_EVERY_BAR + " 1"));
        // Before any hand-over there's no scrubber to hide.
        ProgressBar.noScrubber();
        assertFalse(statusLine().contains(ProgressBar.NO_SCRUBBER_HIDDEN));

        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        SeekBar bar = new SeekBar(RuntimeEnvironment.getApplication());
        scrubberViews(label, bar);
        ProgressBar.handsOver(label, bar, null, "a");
        ProgressBar.mounts(label, null);
        HookStatus.clear();
        Settings.KEEP_PROGRESS_BAR.save(false);
        ProgressBar.registryAsks(true, 3);
        ProgressBar.noScrubber();
        Settings.KEEP_PROGRESS_BAR.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        ProgressBar.registryAsks(true, 3);
        ProgressBar.noScrubber();
        PauseForTests.resume();
        assertEquals("the bar was hidden with the switch off or paused", View.VISIBLE, bar.getVisibility());
        assertEquals(View.VISIBLE, label.getVisibility());
        line = statusLine();
        assertFalse("counted with the switch off or paused: " + line, line != null
                && (line.contains(ProgressBar.REGISTRY_HID_EVERY_BAR) || line.contains(ProgressBar.NO_SCRUBBER_HIDDEN)));
    }

    private static void idle(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS);
    }

    /** #120: a press stops the bar's update, and a tap that doesn't seek never started it again. */
    @Test
    public void lettingGoOfAKeptBarRunsItsUpdateAgain() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        SeekBar bar = new SeekBar(RuntimeEnvironment.getApplication());
        List<Object> runs = new ArrayList<>();
        ProgressBar.updateAgainForTests = runs::add;
        Object scrubber = new Object();
        ProgressBar.barReleased(scrubber, bar);
        assertTrue("the update ran before a seek could land", runs.isEmpty());
        idle(ProgressBar.BAR_RELEASE_MS);
        assertEquals(1, runs.size());
        assertSame(scrubber, runs.get(0));
        idle(10 * ProgressBar.BAR_RELEASE_MS);
        assertEquals("one release runs it once, and the update goes on by itself", 1, runs.size());
        String line = statusLine();
        assertTrue(line, line.contains(ProgressBar.BAR_RELEASE + " 1"));
    }

    @Test
    public void aNewPressOnTheBarTakesOverFromALastRelease() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        SeekBar bar = new SeekBar(RuntimeEnvironment.getApplication());
        List<Object> runs = new ArrayList<>();
        ProgressBar.updateAgainForTests = runs::add;
        ProgressBar.barReleased(new Object(), bar);
        bar.setPressed(true);
        idle(ProgressBar.BAR_RELEASE_MS);
        assertTrue("the update ran under a new press", runs.isEmpty());
        bar.setPressed(false);
        Object scrubber = new Object();
        ProgressBar.barReleased(scrubber, bar);
        idle(ProgressBar.BAR_RELEASE_MS);
        assertEquals(1, runs.size());
        assertSame(scrubber, runs.get(0));
    }

    @Test
    public void lettingGoOfTheBarIsLeftToFacebookOffOrPaused() {
        SeekBar bar = new SeekBar(RuntimeEnvironment.getApplication());
        List<Object> runs = new ArrayList<>();
        ProgressBar.updateAgainForTests = runs::add;
        Settings.KEEP_PROGRESS_BAR.save(false);
        ProgressBar.barReleased(new Object(), bar);
        idle(ProgressBar.BAR_RELEASE_MS);
        assertTrue("the update ran with the switch off", runs.isEmpty());
        Settings.KEEP_PROGRESS_BAR.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        ProgressBar.barReleased(new Object(), bar);
        idle(ProgressBar.BAR_RELEASE_MS);
        assertTrue("the update ran under Pause", runs.isEmpty());
    }

    @Test
    public void aThrowingUpdateAfterAReleaseStaysOutOfFacebook() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        ProgressBar.updateAgainForTests = scrubber -> {
            throw new IllegalStateException("Facebook moved");
        };
        ProgressBar.barReleased(new Object(), new SeekBar(RuntimeEnvironment.getApplication()));
        idle(ProgressBar.BAR_RELEASE_MS);
    }

    @Test
    public void aReelNothingDrewSinceItsMountHasItsUpdateRunAgainUntilItDraws() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        SeekBar bar = new SeekBar(RuntimeEnvironment.getApplication());
        scrubberViews(label, bar);
        Object scrubber = new Object();
        List<Object> runs = new ArrayList<>();
        ProgressBar.updateAgainForTests = runs::add;
        // #120: a double swipe hands the scrubber reel c, and its update stops before it draws c.
        ProgressBar.handsOver(label, bar, "b", "c");
        ProgressBar.mounts(label, scrubber);
        idle(ProgressBar.UPDATE_CHECK_MS - 1);
        assertTrue("the update ran again before the first check", runs.isEmpty());
        idle(1);
        assertEquals("the stopped update didn't run again", Collections.singletonList(scrubber), runs);
        idle(ProgressBar.UPDATE_CHECK_MS);
        assertEquals("the update ran once per check until c drew", 2, runs.size());
        // The update finds c's position at last and moves the bar.
        ProgressBar.refreshesTime(label, bar);
        idle(10 * ProgressBar.UPDATE_CHECK_MS);
        assertEquals("the update ran again after c drew", 2, runs.size());
        String line = statusLine();
        assertTrue(line, line.contains(ProgressBar.UPDATE_AGAIN + " 2"));
    }

    @Test
    public void theReelBarsDebugLinesPairReelsWithoutTheirFacebookIds() {
        String held = "1134540631592283";
        String shown = "1316146539584337463";
        BaseSettings.DEBUG.save(true);
        ShadowLog.clear();
        try {
            Settings.KEEP_PROGRESS_BAR.save(true);
            LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
            SeekBar bar = new SeekBar(RuntimeEnvironment.getApplication());
            scrubberViews(label, bar);
            ProgressBar.updateAgainForTests = ran -> { };
            // Every line that names a reel: a hand-over left on the last reel, a held look, the
            // mount, a watch that runs the update again and the draw that ends it, then the reel
            // the scrubber holds coming back.
            ProgressBar.handsOver(label, bar, held, shown);
            ProgressBar.leftOnLastReel();
            ProgressBar.lookFor(label, 1);
            ProgressBar.noScrubber();
            ProgressBar.mounts(label, new Object());
            idle(ProgressBar.UPDATE_CHECK_MS);
            ProgressBar.refreshesTime(label, bar);
            ProgressBar.handsOver(label, bar, held, shown);
            ProgressBar.leftOnLastReel();
            ProgressBar.handsOver(label, bar, held, held);

            List<String> lines = new ArrayList<>();
            for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
                if (item.msg != null && item.msg.contains("Reel bar:")) lines.add(item.msg);
            }
            assertTrue("no reel bar line was logged", lines.size() >= 8);
            String joined = String.join("\n", lines);
            assertFalse("a reel's Facebook id reached logcat:\n" + joined, joined.contains(held) || joined.contains(shown));
            assertTrue(joined, joined.contains(ProgressBar.reelName(held)));
            assertTrue(joined, joined.contains(ProgressBar.reelName(shown)));
        } finally {
            BaseSettings.DEBUG.resetToDefault();
        }
        assertNotEquals("two reels got one name", ProgressBar.reelName(held), ProgressBar.reelName(shown));
        assertEquals("the same reel got two names", ProgressBar.reelName(held), ProgressBar.reelName(held));
        assertTrue(ProgressBar.reelName(held), ProgressBar.reelName(held).matches("reel [0-9a-f]{6}"));
        assertEquals("no reel", ProgressBar.reelName(null));
        assertEquals("no reel", ProgressBar.reelName(""));
    }

    @Test
    public void aRunThatDrawsEndsTheWatchAndAReelThatNeverDrawsIsLeftAfterTheLastCheck() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        SeekBar bar = new SeekBar(RuntimeEnvironment.getApplication());
        scrubberViews(label, bar);
        Object scrubber = new Object();
        List<Object> runs = new ArrayList<>();
        // The first run finds the reel's position, so Facebook's update moves the bar there and then.
        ProgressBar.updateAgainForTests = ran -> {
            runs.add(ran);
            ProgressBar.refreshesTime(label, bar);
        };
        ProgressBar.mounts(label, scrubber);
        idle(10 * ProgressBar.UPDATE_CHECK_MS);
        assertEquals("the update ran again after it drew", 1, runs.size());

        // A reel whose player never starts: the update runs at each check, then the watch gives up.
        runs.clear();
        ProgressBar.updateAgainForTests = runs::add;
        ProgressBar.mounts(label, scrubber);
        idle((ProgressBar.UPDATE_CHECKS + 10) * ProgressBar.UPDATE_CHECK_MS);
        assertEquals("the watch didn't stop after its last check", ProgressBar.UPDATE_CHECKS, runs.size());
    }

    @Test
    public void aReelThatDrawsOnItsOwnOrAMountWhileOffRunsNothingAgain() {
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        SeekBar bar = new SeekBar(RuntimeEnvironment.getApplication());
        scrubberViews(label, bar);
        Object scrubber = new Object();
        List<Object> runs = new ArrayList<>();
        ProgressBar.updateAgainForTests = runs::add;
        // Off, Facebook's bar is left to Facebook.
        ProgressBar.mounts(label, scrubber);
        idle(4 * ProgressBar.UPDATE_CHECK_MS);
        assertTrue("off, the update ran again", runs.isEmpty());

        Settings.KEEP_PROGRESS_BAR.save(true);
        // A swipe that goes as it should: the update draws the reel before the first check.
        ProgressBar.mounts(label, scrubber);
        ProgressBar.refreshesTime(label, bar);
        idle(4 * ProgressBar.UPDATE_CHECK_MS);
        assertTrue("a reel that drew had its update run again", runs.isEmpty());
        // A mount with no scrubber, then the next mount replaces the watch rather than adding one.
        ProgressBar.mounts(label, null);
        idle(4 * ProgressBar.UPDATE_CHECK_MS);
        assertTrue("a mount without its scrubber ran an update", runs.isEmpty());
        ProgressBar.mounts(label, scrubber);
        idle(ProgressBar.UPDATE_CHECK_MS / 2);
        ProgressBar.mounts(label, scrubber);
        idle(ProgressBar.UPDATE_CHECK_MS);
        assertEquals("the first mount's watch ran on past the second mount", 1, runs.size());
        // The switch goes off while the reel waits.
        Settings.KEEP_PROGRESS_BAR.save(false);
        idle(4 * ProgressBar.UPDATE_CHECK_MS);
        assertEquals("the update ran again with the switch off", 1, runs.size());
        String line = statusLine();
        assertTrue(line, line.contains(ProgressBar.UPDATE_AGAIN + " 1"));
    }

    @Test
    public void turnedOnWithReelsOpenTheThinBarTakesTheKeptLookOnce() {
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        // Off: Facebook's passive look ran, and the bar is its thin line.
        assertFalse("off, a thin bar was given the kept look", ProgressBar.looksAgain(label));
        assertFalse("off, Facebook's look was kept", ProgressBar.keepsReelBar());

        Settings.KEEP_PROGRESS_BAR.save(true);
        assertTrue("on, the thin bar stayed until a touch", ProgressBar.looksAgain(label));
        // The patch runs the passive look, whose hook runs the active one and then this.
        ProgressBar.hideTimeLabel(label);
        assertFalse("the kept look was asked for again on the next update", ProgressBar.looksAgain(label));
        assertFalse("the kept look was asked for again", ProgressBar.looksAgain(label));
        String line = statusLine();
        assertTrue(line, line.contains(ProgressBar.KEPT_LOOK_AGAIN + " 1"));
        assertFalse(line, line.contains(ProgressBar.FACEBOOK_LOOK_AGAIN));
    }

    /**
     * #73: the reel on screen as the switch goes on kept its playing footer, with the caption where
     * the kept bar and its time now go. The update that gives the bar the kept look lifts every
     * footer Facebook still holds, once, and the patch's lift changes only a playing one.
     */
    @Test
    public void turnedOnWithReelsOpenThePlayingReelsFooterIsLiftedOnce() {
        Object playing = new Object();
        Object paused = new Object();
        List<Object> asked = new ArrayList<>();
        ProgressBar.footerLiftForTests = footer -> {
            asked.add(footer);
            return footer == playing;
        };
        ProgressBar.footerMade(playing);
        ProgressBar.footerMade(paused);
        ProgressBar.footerMade(null);
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());

        assertFalse(ProgressBar.looksAgain(label));
        assertTrue("off, a footer was lifted: " + asked, asked.isEmpty());
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        Settings.KEEP_PROGRESS_BAR.save(true);
        assertFalse(ProgressBar.looksAgain(label));
        assertTrue("paused, a footer was lifted: " + asked, asked.isEmpty());
        PauseForTests.resume();

        assertTrue("on, the thin bar stayed until a touch", ProgressBar.looksAgain(label));
        assertEquals("every footer Facebook holds is asked, once each", 2, asked.size());
        assertTrue(asked.contains(playing) && asked.contains(paused));
        String line = statusLine();
        assertTrue(line, line.contains(ProgressBar.FOOTER_LIFTED_AGAIN + " 1"));

        // The patch runs the passive look, whose hook runs the active one; later updates lift nothing.
        ProgressBar.hideTimeLabel(label);
        assertFalse(ProgressBar.looksAgain(label));
        assertEquals("a footer was asked again", 2, asked.size());

        // Off again: Facebook's look comes back and its next player event sets the footer.
        Settings.KEEP_PROGRESS_BAR.save(false);
        assertTrue(ProgressBar.looksAgain(label));
        assertEquals("off, a footer was lifted", 2, asked.size());
    }

    /**
     * #73 on the S22 and the emulator: the switch went on in the settings opened over Reels, and as
     * the reel played on or looped after Back, Facebook's own look ran first, so the bar had the kept
     * look before its next progress update and that update lifted nothing. The footer stayed HIDDEN
     * and a play event changed nothing. Facebook's look giving a bar the kept look lifts the footers too.
     */
    @Test
    public void turnedOnUnderTheSettingsTheFooterLiftsWhenFacebooksLookGetsThereFirst() {
        Object playing = new Object();
        List<Object> asked = new ArrayList<>();
        ProgressBar.footerLiftForTests = footer -> {
            asked.add(footer);
            return footer == playing;
        };
        ProgressBar.footerMade(playing);
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        // Off: Facebook's passive look ran, so no bar has the kept look.
        assertFalse(ProgressBar.keepsReelBar());

        Settings.KEEP_PROGRESS_BAR.save(true);
        // The reel plays on: Facebook asks for its passive look, whose hook runs the active one and this.
        assertTrue(ProgressBar.keepsReelBar());
        ProgressBar.hideTimeLabel(label);
        assertEquals("the playing footer wasn't lifted", Collections.singletonList(playing), asked);
        // The next progress update finds the look kept and lifts nothing more, nor does the next look.
        assertFalse(ProgressBar.looksAgain(label));
        ProgressBar.hideTimeLabel(label);
        assertEquals("a footer was asked again", 1, asked.size());
        String line = statusLine();
        assertTrue(line, line.contains(ProgressBar.FOOTER_LIFTED_AGAIN + " 1"));
    }

    @Test
    public void aFooterLiftThatFailsIsReportedAndTheOthersStillLift() {
        Object first = new Object();
        Object second = new Object();
        ProgressBar.footerLiftForTests = footer -> {
            if (footer == first) throw new IllegalStateException("the lift failed");
            return true;
        };
        ProgressBar.footerMade(first);
        ProgressBar.footerMade(second);
        assertEquals(1, ProgressBar.liftFooters());
        assertTrue(HookStatus.missing(FamilyNames.PROGRESS_BAR).toString(),
                HookStatus.missing(FamilyNames.PROGRESS_BAR).get(0).contains("reel caption lift"));

        // Unpatched, the stub lifts nothing.
        ProgressBar.footerLiftForTests = null;
        assertFalse(ProgressBar.liftFooter(new Object()));
        assertEquals(0, ProgressBar.liftFooters());
    }

    @Test
    public void aBarThatGotTheKeptLookFromFacebooksOwnLookIsLeftAlone() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        // A cold start: Facebook's passive look runs and its hook gives the bar the kept look.
        assertTrue(ProgressBar.keepsReelBar());
        ProgressBar.hideTimeLabel(label);
        assertFalse("a bar that has the kept look was given it again", ProgressBar.looksAgain(label));
        String line = statusLine();
        assertFalse(line, line.contains(ProgressBar.KEPT_LOOK_AGAIN));
    }

    @Test
    public void turnedOffWithReelsOpenTheKeptBarGoesBackToFacebookOnce() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        ProgressBar.hideTimeLabel(label);
        Settings.KEEP_PROGRESS_BAR.save(false);
        // Off with the full size bar and its stopped time still up: Facebook's passive look runs, as the
        // hook in it now answers no.
        assertTrue("off, the kept bar stayed with its time stopped", ProgressBar.looksAgain(label));
        assertFalse("Facebook's look ran with the switch off", ProgressBar.keepsReelBar());
        assertFalse("Facebook's look was asked for again", ProgressBar.looksAgain(label));
        String line = statusLine();
        assertTrue(line, line.contains(ProgressBar.FACEBOOK_LOOK_AGAIN + " 1"));

        // On again: the bar Facebook's look shrank takes the kept look again.
        Settings.KEEP_PROGRESS_BAR.save(true);
        assertTrue("on again, the thin bar stayed", ProgressBar.looksAgain(label));
    }

    @Test
    public void facebooksOwnLookWhileOffMeansTheKeptLookIsGone() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        ProgressBar.hideTimeLabel(label);
        // Off, then a touch: Facebook's own passive look runs and shrinks the bar, before any update asks.
        Settings.KEEP_PROGRESS_BAR.save(false);
        assertFalse(ProgressBar.keepsReelBar());
        assertFalse("a bar Facebook's look already shrank was given it again", ProgressBar.looksAgain(label));
        Settings.KEEP_PROGRESS_BAR.save(true);
        assertTrue("on again, the bar Facebook shrank stayed thin", ProgressBar.looksAgain(label));
    }

    @Test
    public void pausedBeforeTheSettingsOrWhileTheBarHoldsTheLastReelTheLookStays() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        assertFalse("no label, a look was run", ProgressBar.looksAgain(null));
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("a paused Hushfacebook gave the bar the kept look", ProgressBar.looksAgain(label));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
                assertFalse("the bar got the kept look before the settings were ready", ProgressBar.looksAgain(label)));

        // #120: the scrubber still holds the last reel, so its bar and time stay hidden whatever the look.
        SeekBar bar = new SeekBar(RuntimeEnvironment.getApplication());
        scrubberViews(label, bar);
        ProgressBar.handsOver(label, bar, "a", "b");
        ProgressBar.leftOnLastReel();
        assertFalse("a bar hidden for the last reel was given a look", ProgressBar.looksAgain(label));
        ProgressBar.mounts(label, null);
        assertTrue("once a reel took the bar, it still had Facebook's look", ProgressBar.looksAgain(label));
    }

    @Test
    public void theReelTheBarHoldsComingBackOnScreenShowsTheBarAgain() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        SeekBar bar = new SeekBar(RuntimeEnvironment.getApplication());
        scrubberViews(label, bar);
        ProgressBar.handsOver(label, bar, "a", "b");
        ProgressBar.leftOnLastReel();
        // A reel with no id is no better: the scrubber still holds a.
        ProgressBar.handsOver(label, bar, "a", null);
        ProgressBar.leftOnLastReel();
        assertEquals(View.INVISIBLE, bar.getVisibility());
        // Swiping back to a: Facebook skips the hand-over, as the scrubber holds a.
        ProgressBar.handsOver(label, bar, "a", "a");
        ProgressBar.leftOnLastReel();
        assertEquals("the bar of the reel on screen stayed hidden", View.VISIBLE, bar.getVisibility());
        Integer passive = 0;
        assertSame(passive, ProgressBar.lookFor(label, passive));
    }

    @Test
    public void aLookThatHidesTheBarGoesThroughAndFacebookKeepsTheBarAfterwards() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        SeekBar bar = new SeekBar(RuntimeEnvironment.getApplication());
        scrubberViews(label, bar);
        ProgressBar.handsOver(label, bar, "a", "b");
        ProgressBar.leftOnLastReel();
        Integer invisible = ProgressBar.INVISIBLE;
        Integer faded = ProgressBar.FADED;
        assertSame(invisible, ProgressBar.lookFor(label, invisible));
        assertSame(faded, ProgressBar.lookFor(label, faded));
        // Facebook's own looks own the bar now, so the mount leaves it to them.
        ProgressBar.mounts(label, null);
        assertEquals("the bar Facebook hid came back", View.INVISIBLE, bar.getVisibility());
    }

    @Test
    public void offOrWithoutViewsAHandOverChangesNothing() {
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        SeekBar bar = new SeekBar(RuntimeEnvironment.getApplication());
        scrubberViews(label, bar);
        ProgressBar.handsOver(label, bar, "a", "b");
        ProgressBar.leftOnLastReel();
        assertEquals("off, the bar was hidden", View.VISIBLE, bar.getVisibility());
        Integer active = 1;
        assertSame(active, ProgressBar.lookFor(label, active));
        assertNull(ProgressBar.lookFor(label, null));
        assertSame(active, ProgressBar.lookFor(null, active));

        Settings.KEEP_PROGRESS_BAR.save(true);
        ProgressBar.handsOver(null, bar, "a", "b");
        ProgressBar.leftOnLastReel();
        ProgressBar.mounts(null, null);
        assertEquals("a scrubber without its label lost its bar", View.VISIBLE, bar.getVisibility());

        // Hidden, then the switch goes off: the next look puts the bar back and goes through.
        ProgressBar.handsOver(label, bar, "a", "b");
        ProgressBar.leftOnLastReel();
        assertEquals(View.INVISIBLE, bar.getVisibility());
        Settings.KEEP_PROGRESS_BAR.save(false);
        assertSame(active, ProgressBar.lookFor(label, active));
        assertEquals("the bar stayed hidden with the switch off", View.VISIBLE, bar.getVisibility());
    }

    @Test
    public void aPressOfTheBarKeepsTheOverlayOnlyWithItsSwitchOnAndNotPaused() {
        assertFalse("the overlay switch doesn't start off", Settings.KEEP_REEL_OVERLAY.get());
        assertFalse("Facebook's seek look went missing with the switch off", ProgressBar.keepsOverlayWhileSeeking());
        Settings.KEEP_REEL_OVERLAY.save(true);
        try {
            assertTrue("a press still clears the name and buttons", ProgressBar.keepsOverlayWhileSeeking());
            String line = statusLine();
            assertTrue("the kept press wasn't counted: " + line,
                    line != null && line.endsWith("Counted: " + ProgressBar.OVERLAY_KEPT + " 1"));
            PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
            assertFalse("Pause doesn't give the press back to Facebook", ProgressBar.keepsOverlayWhileSeeking());
        } finally {
            PauseForTests.resume();
            Settings.KEEP_REEL_OVERLAY.resetToDefault();
        }
    }

    @Test
    public void theOverlaySwitchNeedsNoRestartAndTravelsWithItsFamily() {
        assertFalse(Settings.KEEP_REEL_OVERLAY.rebootApp);
        assertNull(Settings.KEEP_REEL_OVERLAY.userDialogMessage);
        assertTrue("Pause and the report don't know the overlay switch",
                PatchFamily.PROGRESS_BAR.switches.contains(Settings.KEEP_REEL_OVERLAY));
    }

    @Test
    public void theTimeSwitchNeedsNoRestartAndTravelsWithItsFamily() {
        assertFalse(Settings.KEEP_PROGRESS_BAR_TIME.rebootApp);
        assertNull(Settings.KEEP_PROGRESS_BAR_TIME.userDialogMessage);
        assertTrue("Pause and the report don't know the time switch",
                PatchFamily.PROGRESS_BAR.switches.contains(Settings.KEEP_PROGRESS_BAR_TIME));
    }

    @Test
    public void tapTheBarToSeekNeedsNoRestartAndTravelsWithItsFamily() {
        assertFalse(Settings.TAP_TO_SEEK.rebootApp);
        assertNull(Settings.TAP_TO_SEEK.userDialogMessage);
        assertTrue("Pause and the report don't know Tap the bar to seek",
                PatchFamily.PROGRESS_BAR.switches.contains(Settings.TAP_TO_SEEK));
    }
}
