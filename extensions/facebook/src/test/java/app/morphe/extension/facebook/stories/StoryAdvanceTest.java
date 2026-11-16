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
import org.robolectric.shadows.ShadowLog;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.facebook.stories.model.StoryCard;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
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

    /** Stands in for Facebook's interrupt reasons, which keep their names. */
    private enum Interrupt { PHOTO_TO_VIDEO_CAPPED, ACTIVITY_PAUSED }

    /** Stands in for Facebook's interrupt manager, the reset's owner: its reasons are a set in a field. */
    private static final class Interrupts {
        final List<Object> listeners = new ArrayList<>();
        final EnumSet<Interrupt> reasons = EnumSet.noneOf(Interrupt.class);
        final AtomicBoolean paused = new AtomicBoolean();
    }

    /** Facebook's progress callback keeps its card in a field of a class it extends. */
    private static class CardHolder {
        StoryCard card;
    }

    private static final class Callback extends CardHolder {
        Callback(StoryCard.Media media) {
            card = new StoryCard(media);
        }
    }

    @Test public void aLoopLogsTheStoryTypeAndWhatHeldIt() {
        BaseSettings.DEBUG.save(true);
        try {
            Interrupts interrupts = new Interrupts();
            interrupts.reasons.add(Interrupt.PHOTO_TO_VIDEO_CAPPED);
            ShadowLog.clear();
            StoryAdvance.restarting(new Callback(StoryCard.Media.VIDEO), interrupts);
            assertEquals(List.of("Loop stories: restarting the story (type VIDEO) through Facebook's reset with "
                + "replay on, held by [PHOTO_TO_VIDEO_CAPPED]"), logged());
        } finally {
            BaseSettings.DEBUG.resetToDefault();
        }
    }

    @Test public void aReplayLogsAsTheReplayAndALoopAfterItAsTheLoop() {
        BaseSettings.DEBUG.save(true);
        try {
            Callback callback = new Callback(StoryCard.Media.PHOTO);
            Interrupts interrupts = new Interrupts();
            StoryAdvance.held(callback);
            StoryAdvance.restartForTests = () -> StoryAdvance.restarting(callback, interrupts);
            ShadowLog.clear();
            assertTrue(StoryAdvance.replay());
            StoryAdvance.restarting(callback, interrupts);
            assertEquals(List.of(
                "Story replay: restarting the story (type PHOTO) through Facebook's reset with replay on, held by []",
                "Loop stories: restarting the story (type PHOTO) through Facebook's reset with replay on, held by []"),
                logged());
        } finally {
            BaseSettings.DEBUG.resetToDefault();
        }
    }

    @Test public void aRestartItCannotReadIsStillLoggedAndNeverThrows() {
        BaseSettings.DEBUG.save(true);
        try {
            ShadowLog.clear();
            StoryAdvance.restarting(new Object(), new Object());
            StoryAdvance.restarting(null, null);
            String unread = "Loop stories: restarting the story (type unread) through Facebook's reset with replay "
                + "on, held by unread";
            assertEquals(List.of(unread, unread), logged());
        } finally {
            BaseSettings.DEBUG.resetToDefault();
        }
    }

    @Test public void aRestartLogsNothingWithDebugLoggingOff() {
        ShadowLog.clear();
        StoryAdvance.restarting(new Callback(StoryCard.Media.VIDEO), new Interrupts());
        assertEquals(List.of(), logged());
    }

    /** What StoryAdvance logged since the last clear. */
    private static List<String> logged() {
        List<String> lines = new ArrayList<>();
        for (ShadowLog.LogItem item : ShadowLog.getLogsForTag("morphe: StoryAdvance")) lines.add(item.msg);
        return lines;
    }

    /** The diagnostic report's line for the patch, or empty when it has none. */
    private static String line() {
        List<String> report = HookStatus.report();
        for (String line : report) if (line.startsWith(FamilyNames.STORY_AUTO_ADVANCE)) return line;
        return "";
    }
}
