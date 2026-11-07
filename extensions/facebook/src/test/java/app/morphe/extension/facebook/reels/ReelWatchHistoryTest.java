/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The hook in place of the Reels batcher's hand-over to its executor: it holds back Facebook's
 * seen-state send while the switch is on, and every other time hands the executor exactly what
 * Facebook handed it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReelWatchHistoryTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** A runnable Redex named for another helper: the Reels footer's comment query. */
    public static final class OtherHelperRunnable implements Runnable {
        @SuppressWarnings("unused")
        public static final String __redex_internal_original_name =
                "FbShortsViewerFooterHotCommentHelper$createHotCommentQueryRunnable$1";

        @Override
        public void run() {
        }
    }

    /** The send's name in a field that isn't the static one Redex writes. */
    public static final class InstanceNamedRunnable implements Runnable {
        @SuppressWarnings("unused")
        public final String __redex_internal_original_name = ReelWatchHistory.SEEN_STATE_SEND;

        @Override
        public void run() {
        }
    }

    /** An executor that keeps what it's handed. */
    private static final class Recorder implements Executor {
        final List<Runnable> handed = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            handed.add(command);
        }
    }

    /** The patch is in Morphe Manager's default selection with its switch off; these tests turn it on. */
    @Before
    public void turnTheSwitchOn() {
        Settings.DONT_SEND_REEL_WATCH_HISTORY.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.DONT_SEND_REEL_WATCH_HISTORY.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private static String counterLine() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(ReelWatchHistory.ROUTE + ":")) return line;
        }
        return null;
    }

    /** The counter line after [batches] batches with [held] of them held back. */
    private static String heldBack(int batches, int held) {
        return ReelWatchHistory.ROUTE + ": " + batches + " lists, " + batches + " items, " + held + " removed. Last reason: "
                + ReelWatchHistory.HELD_BACK + ". Removed: " + ReelWatchHistory.HELD_BACK + " " + held;
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.REEL_WATCH_HISTORY + ":")) return line;
        }
        return null;
    }

    @Test
    public void theSwitchStartsOffAndOnHoldsTheSendBack() {
        assertFalse("the switch starts off", Settings.DONT_SEND_REEL_WATCH_HISTORY.defaultValue);
        Recorder executor = new Recorder();
        ReelWatchHistory.send(executor, new SeenStateSendForTests());
        assertTrue("the batch reached the executor", executor.handed.isEmpty());
        assertEquals(heldBack(1, 1), counterLine());
        assertEquals(FamilyNames.REEL_WATCH_HISTORY + ": invoked 1, 1 found, 0 missing", statusLine());
    }

    @Test
    public void offTheSendGoesToTheExecutorAsItCame() {
        Settings.DONT_SEND_REEL_WATCH_HISTORY.save(false);
        Recorder executor = new Recorder();
        SeenStateSendForTests send = new SeenStateSendForTests();
        ReelWatchHistory.send(executor, send);
        assertEquals(1, executor.handed.size());
        assertSame(send, executor.handed.get(0));
        assertFalse("the hook ran the send itself", send.ran);
        // Recognised with the switch off too, so the report shows the hook works before anyone
        // turns it on.
        assertEquals(FamilyNames.REEL_WATCH_HISTORY + ": invoked 1, 1 found, 0 missing", statusLine());
        assertEquals("Reel watch history: 1 lists, 1 items, 0 removed", counterLine());
    }

    @Test
    public void pausedTheSendGoesOut() {
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(SeenStateSendForTests.heldBack());
        PauseForTests.pause(HushfacebookPause.Reason.CRASH_LOOP);
        assertFalse(SeenStateSendForTests.heldBack());
        PauseForTests.resume();
        assertTrue(SeenStateSendForTests.heldBack());
    }

    /** Fail open: a runnable the hook doesn't recognise is sent, and the report names it. */
    @Test
    public void aRunnableItDoesNotRecogniseGoesOutAndIsReported() {
        Recorder executor = new Recorder();
        Runnable unnamed = () -> { };
        ReelWatchHistory.send(executor, unnamed);
        ReelWatchHistory.send(executor, new OtherHelperRunnable());
        ReelWatchHistory.send(executor, new InstanceNamedRunnable());
        assertEquals("a runnable it didn't recognise was held back", 3, executor.handed.size());
        assertSame(unnamed, executor.handed.get(0));

        List<String> missing = HookStatus.missing(FamilyNames.REEL_WATCH_HISTORY);
        assertEquals(missing.toString(), 3, missing.size());
        assertTrue(missing.get(1), missing.get(1).contains(OtherHelperRunnable.class.getName()));
        assertTrue(missing.get(1), missing.get(1).contains(ReelWatchHistory.SEEN_STATE_SEND));
        assertEquals("Reel watch history: 3 lists, 3 items, 0 removed", counterLine());
    }

    /** A second batch in a session is held back as well, however many there are. */
    @Test
    public void everyBatchIsHeldBackNotOnlyTheFirst() {
        Recorder executor = new Recorder();
        for (int i = 0; i < 3; i++) ReelWatchHistory.send(executor, new SeenStateSendForTests());
        assertTrue(executor.handed.isEmpty());
        assertEquals(heldBack(3, 3), counterLine());
        assertEquals(FamilyNames.REEL_WATCH_HISTORY + ": invoked 3, 1 found, 0 missing", statusLine());

        // A diagnostic clear starts the report over, and the next batch is found again.
        HookStatus.clear();
        ReelWatchHistory.send(executor, new SeenStateSendForTests());
        assertEquals(FamilyNames.REEL_WATCH_HISTORY + ": invoked 1, 1 found, 0 missing", statusLine());
    }

    /** What Facebook's executor throws is Facebook's, and reaches the batcher as it did before. */
    @Test
    public void whatTheExecutorThrowsStillReachesFacebook() {
        Settings.DONT_SEND_REEL_WATCH_HISTORY.save(false);
        Executor full = command -> {
            throw new RejectedExecutionException("full");
        };
        assertThrows(RejectedExecutionException.class, () -> ReelWatchHistory.send(full, new SeenStateSendForTests()));
    }

    /** A batch held back never reaches the executor, so nothing is queued there for later. */
    @Test
    public void aHeldBackSendNeverTouchesTheExecutor() {
        Executor refuses = command -> {
            throw new AssertionError("the executor was handed a batch the switch held back");
        };
        ReelWatchHistory.send(refuses, new SeenStateSendForTests());
    }

    /**
     * #408: the watched-position mutation and the view-time events go out from feed videos, stories
     * and reels outside the viewer. The switch holds both back, and the report counts each kind.
     */
    @Test
    public void theSwitchHoldsBackTheWatchedPositionAndTheViewTimeToo() {
        assertTrue(ReelWatchHistory.holdWatchedPosition());
        assertTrue(ReelWatchHistory.holdViewTime());
        assertTrue(ReelWatchHistory.holdViewTime());
        assertEquals(ReelWatchHistory.ROUTE + ": 3 lists, 3 items, 3 removed. Last reason: " + ReelWatchHistory.VIEW_TIME
                + ". Removed: " + ReelWatchHistory.VIEW_TIME + " 2, " + ReelWatchHistory.POSITION + " 1. Kinds: "
                + ReelWatchHistory.VIEW_TIME + " 2, " + ReelWatchHistory.POSITION + " 1", counterLine());
        assertEquals(FamilyNames.REEL_WATCH_HISTORY + ": invoked 3, 0 found, 0 missing", statusLine());
    }

    /** Off, paused or before settings are ready, both go out, and the report still shows them seen. */
    @Test
    public void offOrPausedTheWatchedPositionAndTheViewTimeGoOut() {
        Settings.DONT_SEND_REEL_WATCH_HISTORY.save(false);
        assertFalse(ReelWatchHistory.holdWatchedPosition());
        assertFalse(ReelWatchHistory.holdViewTime());
        assertEquals(ReelWatchHistory.ROUTE + ": 2 lists, 2 items, 0 removed. Kinds: "
                + ReelWatchHistory.VIEW_TIME + " 1, " + ReelWatchHistory.POSITION + " 1", counterLine());

        Settings.DONT_SEND_REEL_WATCH_HISTORY.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(ReelWatchHistory.holdWatchedPosition());
        assertFalse(ReelWatchHistory.holdViewTime());
        PauseForTests.resume();
        assertTrue(ReelWatchHistory.holdWatchedPosition());
    }

    /** A failure inside the hook is reported, and the send goes out as Facebook handed it over. */
    @Test
    public void aFailureInsideTheHookLeavesTheSendToFacebook() {
        boolean[] ran = {false};
        // Facebook never hands over null. Here it makes the hook itself throw, which is the case
        // being tested: the executor still gets exactly what it was handed.
        Executor executor = command -> ran[0] = command == null;
        ReelWatchHistory.send(executor, null);
        assertTrue("the executor didn't get the send after the hook threw", ran[0]);
        List<String> missing = HookStatus.missing(FamilyNames.REEL_WATCH_HISTORY);
        assertEquals(missing.toString(), 1, missing.size());
        assertTrue(missing.get(0), missing.get(0).contains("threw"));
    }
}
