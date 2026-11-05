/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class StoryAdvanceTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void clearCounts() {
        HookStatus.clear();
        // The patch is in Morphe Manager's default selection with its switch off; these tests run with it on.
        Settings.BLOCK_STORY_AUTO_ADVANCE.save(true);
    }

    @After public void restore() {
        StoryAdvance.forgetForTests();
        PauseForTests.resume();
        Settings.BLOCK_STORY_AUTO_ADVANCE.resetToDefault();
        Settings.LOOP_STORIES.resetToDefault();
        HookStatus.clear();
    }

    @Test public void switchAndPauseLeaveFacebooksTimingAlone() {
        assertTrue(StoryAdvance.waitForTap());
        Settings.BLOCK_STORY_AUTO_ADVANCE.save(false);
        assertFalse(StoryAdvance.waitForTap());
        Settings.BLOCK_STORY_AUTO_ADVANCE.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(StoryAdvance.waitForTap());
    }

    @Test public void loopStartsAHeldStoryAgainAndCountsIt() {
        assertFalse("Loop stories starts off", StoryAdvance.loop());
        Settings.LOOP_STORIES.save(true);
        assertTrue(StoryAdvance.loop());
        assertTrue(line().contains("Counted: " + StoryAdvance.LOOPED + " 1"));
    }

    @Test public void loopNeedsStopStoryAutoAdvanceAndStopsWhilePaused() {
        Settings.LOOP_STORIES.save(true);
        Settings.BLOCK_STORY_AUTO_ADVANCE.save(false);
        assertFalse(StoryAdvance.loop());
        Settings.BLOCK_STORY_AUTO_ADVANCE.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(StoryAdvance.loop());
        PauseForTests.resume();
        Settings.LOOP_STORIES.save(false);
        assertFalse(StoryAdvance.loop());
        assertFalse(line().contains(StoryAdvance.LOOPED));
    }

    @Test public void aHeldStoryStaysFinishedUntilACardPlaysAgain() {
        Object callback = new Object();
        StoryAdvance.held(callback);
        assertTrue(StoryAdvance.finished());
        StoryAdvance.progressed(StoryAdvance.COMPLETE);
        assertTrue("still on its last frame", StoryAdvance.finished());
        StoryAdvance.progressed(0);
        assertFalse("the next card started", StoryAdvance.finished());

        StoryAdvance.held(callback);
        Settings.BLOCK_STORY_AUTO_ADVANCE.save(false);
        assertFalse("nothing holds it with the switch off", StoryAdvance.finished());
        Settings.BLOCK_STORY_AUTO_ADVANCE.save(true);
        StoryAdvance.progressed(10);

        Settings.LOOP_STORIES.save(true);
        StoryAdvance.held(callback);
        assertFalse("Loop stories starts it again by itself", StoryAdvance.finished());
    }

    @Test public void replayRestartsTheHeldStoryOnceAndCountsIt() {
        assertFalse("nothing held", StoryAdvance.replay());
        Object callback = new Object();
        StoryAdvance.held(callback);
        assertFalse("no restart to make", StoryAdvance.replay());
        assertTrue("still held for the next try", StoryAdvance.finished());

        int[] restarts = {0};
        StoryAdvance.restartForTests = () -> restarts[0]++;
        assertTrue(StoryAdvance.replay());
        assertEquals(1, restarts[0]);
        assertFalse(StoryAdvance.finished());
        assertTrue(line().contains("Counted: " + StoryAdvance.REPLAYED + " 1"));
        assertFalse("used up", StoryAdvance.replay());
        assertEquals(1, restarts[0]);
    }

    @Test public void aRestartThatThrowsLeavesTheStoryWhereItIs() {
        Object callback = new Object();
        StoryAdvance.held(callback);
        StoryAdvance.restartForTests = () -> {
            throw new IllegalStateException("viewer detached");
        };
        assertFalse(StoryAdvance.replay());
        assertFalse(line().contains(StoryAdvance.REPLAYED));
    }

    /** Stands in for Facebook's navigation reasons, which keep their names. */
    private enum NavigationReason { TAP_FORWARD, AUTO_ADVANCE, SWIPE_FORWARD }

    @Test public void aForwardTapOnTheLastCardMovesOnWhileTheSwitchIsOn() {
        StoryAdvance.held(new Object());
        assertTrue(StoryAdvance.lastCardTap(NavigationReason.TAP_FORWARD));
        assertFalse("the held story is let go", StoryAdvance.finished());
        assertTrue(line().contains("Counted: " + StoryAdvance.LAST_CARD_TAPS + " 1"));
        assertTrue("a last story still playing moves on too", StoryAdvance.lastCardTap(NavigationReason.TAP_FORWARD));
        Settings.LOOP_STORIES.save(true);
        assertTrue("and one Loop stories keeps playing", StoryAdvance.lastCardTap(NavigationReason.TAP_FORWARD));

        assertFalse("the completion keeps its own reason", StoryAdvance.lastCardTap(NavigationReason.AUTO_ADVANCE));
        assertFalse(StoryAdvance.lastCardTap(NavigationReason.SWIPE_FORWARD));
        assertFalse(StoryAdvance.lastCardTap(null));
        assertFalse("only Facebook's enum, never a string", StoryAdvance.lastCardTap("TAP_FORWARD"));

        Settings.BLOCK_STORY_AUTO_ADVANCE.save(false);
        assertFalse("Facebook's own last card with the switch off", StoryAdvance.lastCardTap(NavigationReason.TAP_FORWARD));
        Settings.BLOCK_STORY_AUTO_ADVANCE.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(StoryAdvance.lastCardTap(NavigationReason.TAP_FORWARD));
    }

    /** The diagnostic report's line for the patch, or empty when it has none. */
    private static String line() {
        List<String> report = HookStatus.report();
        for (String line : report) if (line.startsWith(FamilyNames.STORY_AUTO_ADVANCE)) return line;
        return "";
    }
}
