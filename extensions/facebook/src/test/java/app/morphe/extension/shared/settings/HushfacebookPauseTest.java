/*
 * Modified for Hushfacebook (Facebook), 2026.
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.shared.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Looper;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivityManager;
import org.robolectric.shadows.ShadowSystemClock;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Three crashed starts in a row, the marker file and the way back, for Pause Hushfacebook. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class HushfacebookPauseTest {
    private Context context;
    private File record;
    private File streak;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        // The first start in this JVM files the last deaths in the background, as Facebook's does:
        // done before the test records any.
        Utils.awaitBackgroundTasksForTests();
        HushfacebookPause.resetForTests();
        record = new File(context.getFilesDir(), HushfacebookPause.START_RECORD_NAME);
        streak = new File(context.getFilesDir(), HushfacebookPause.CRASH_STREAK_NAME);
        record.delete();
        streak.delete();
        new File(context.getFilesDir(), HushfacebookPause.DEATHS_FILED_NAME).delete();
        File marker = HushfacebookPause.markerFile(context);
        if (marker != null) marker.delete();
        File media = HushfacebookPause.mediaMarkerFile(context);
        if (media != null) media.delete();
        BaseSettings.SAFE_MODE.save(false);
        BaseSettings.PAUSED.save(false);
    }

    @After public void tearDown() throws Exception {
        setUp();
        HushfacebookPause.resetForTests();
    }

    /** One start that ended the way [lastRecord] says, then this start. */
    private void start(String lastRecord) {
        HushfacebookPause.resetForTests();
        if (lastRecord == null) record.delete();
        else HushfacebookPause.write(record, lastRecord);
        HushfacebookPause.onProcessStart(context);
    }

    @Test public void threeStartsThatCrashedInTheirFirstMinuteTurnSafeModeOn() {
        start(null);
        assertFalse(Setting.isPaused());

        start("4101 1000 crashed");
        assertEquals("1", HushfacebookPause.read(streak));
        start("4102 2000 crashed");
        assertEquals("2", HushfacebookPause.read(streak));
        assertFalse("two crashes are not yet a loop", Setting.isPaused());

        start("4103 3000 crashed");
        assertTrue("the fourth start runs in safe mode", Setting.isPaused());
        assertEquals(HushfacebookPause.Reason.CRASH_LOOP, HushfacebookPause.reason());
        assertTrue(BaseSettings.SAFE_MODE.savedValue());
        assertEquals("the count starts again once safe mode is on", "0", HushfacebookPause.read(streak));

        // Safe mode holds for every later start until someone turns Hushfacebook back on.
        start(null);
        assertEquals(HushfacebookPause.Reason.CRASH_LOOP, HushfacebookPause.reason());
    }

    @Test public void aStartThatDiedWithoutACrashEndsTheStreak() {
        start("4101 1000 crashed");
        start("4102 2000 crashed");
        assertEquals("2", HushfacebookPause.read(streak));

        // Swiped away inside its first minute: no crash mark, and the streak is over.
        start("4103 3000");
        assertEquals("0", HushfacebookPause.read(streak));
        start("4104 4000 crashed");
        assertEquals("1", HushfacebookPause.read(streak));
        assertFalse(Setting.isPaused());
    }

    @Test public void aStartThatRanForAMinuteClearsItsRecordAndTheStreak() {
        start("4101 1000 crashed");
        assertTrue("this start left its own record", record.exists());
        shadowOf(Looper.getMainLooper()).idleFor(HushfacebookPause.START_WINDOW_MS + 1, TimeUnit.MILLISECONDS);
        assertFalse("the record of a start that survived is gone", record.exists());
        assertEquals("0", HushfacebookPause.read(streak));
    }

    @Test public void aCrashAndroidHoldsOpenPastTheMinuteStillCounts() {
        start("4101 1000 crashed");
        start("4102 2000 crashed");
        assertEquals("2", HushfacebookPause.read(streak));

        // This start crashes off the main thread, and Android holds it open behind "keeps
        // stopping" while the main looper runs on past the minute.
        HushfacebookPause.markCrash();
        HushfacebookPause.crashState = () -> true;
        shadowOf(Looper.getMainLooper()).idleFor(HushfacebookPause.START_WINDOW_MS + 1, TimeUnit.MILLISECONDS);
        assertTrue("the minute cleared the record of a start that crashed", record.exists());
        assertEquals("2", HushfacebookPause.read(streak));

        // The next start judges the record this one left: the third crash in a row.
        HushfacebookPause.resetForTests();
        HushfacebookPause.onProcessStart(context);
        assertEquals(HushfacebookPause.Reason.CRASH_LOOP, HushfacebookPause.reason());
    }

    @Test public void anExceptionAHandlerSwallowedDoesNotEndTheStart() {
        start("4101 1000 crashed");
        assertEquals("1", HushfacebookPause.read(streak));
        HushfacebookPause.markCrash();
        HushfacebookPause.crashState = () -> false;
        shadowOf(Looper.getMainLooper()).idleFor(HushfacebookPause.START_WINDOW_MS + 1, TimeUnit.MILLISECONDS);
        assertFalse("a start still running after the minute kept its record", record.exists());
        assertEquals("0", HushfacebookPause.read(streak));
    }

    @Test public void onlyACrashInsideTheFirstMinuteMarksTheStart() {
        start(null);
        ShadowSystemClock.advanceBy(HushfacebookPause.START_WINDOW_MS - 1000, TimeUnit.MILLISECONDS);
        HushfacebookPause.markCrash();
        assertTrue("a crash inside the minute was not marked",
                HushfacebookPause.read(record).trim().endsWith("crashed"));

        // The clock runs past the minute before the looper gets to clear the record: a crash
        // this late is not a start crash.
        start(null);
        ShadowSystemClock.advanceBy(HushfacebookPause.START_WINDOW_MS + 1000, TimeUnit.MILLISECONDS);
        HushfacebookPause.markCrash();
        assertTrue(record.exists());
        assertFalse("a crash after the minute was marked",
                HushfacebookPause.read(record).trim().endsWith("crashed"));
    }

    /** An exit Android wrote down for the pid at the wall-clock moment [at]. */
    private void exited(int pid, int reason, long at) {
        exited(pid, reason, at, null);
    }

    /** The same, with the description Android gave it, for a process on screen. */
    private void exited(int pid, int reason, long at, String description) {
        exited(pid, reason, at, description, ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
    }

    /** The same, for a process that was as important to Android as [importance] says. */
    private void exited(int pid, int reason, long at, String description, int importance) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        shadowOf(manager).addApplicationExitInfo(ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setProcessName(context.getPackageName())
                .setPid(pid)
                .setReason(reason)
                .setDescription(description)
                .setImportance(importance)
                .setStatus(reason == ApplicationExitInfo.REASON_CRASH ? 1 : 0)
                .setTimestamp(at)
                .build());
    }

    /**
     * Android 17's memory limiter ends a process with REASON_OTHER and a description that starts
     * MemoryLimiter (#528). That counts while Facebook is in view, and nothing else under
     * REASON_OTHER does. Trimmed in the background, it's Android making room, not a crash.
     */
    @Test @Config(sdk = 30)
    public void android11CountsAMemoryLimiterKillButNothingElseRecordedAsOther() {
        exited(5201, ApplicationExitInfo.REASON_OTHER, 11_000, "MemoryLimiter:AnonSwap");
        exited(5202, ApplicationExitInfo.REASON_OTHER, 12_000, "MemoryLimiter:FileSwap");
        exited(5203, ApplicationExitInfo.REASON_OTHER, 13_000, "remove task");
        exited(5204, ApplicationExitInfo.REASON_OTHER, 14_000, null);
        exited(5205, ApplicationExitInfo.REASON_OTHER, 15_000, "a note: MemoryLimiter:AnonSwap");

        assertTrue(HushfacebookPause.diedYoungFromACrash(context, "5201 1000"));
        assertTrue(HushfacebookPause.diedYoungFromACrash(context, "5202 1000"));
        assertFalse(HushfacebookPause.diedYoungFromACrash(context, "5203 1000"));
        assertFalse(HushfacebookPause.diedYoungFromACrash(context, "5204 1000"));
        assertFalse(HushfacebookPause.diedYoungFromACrash(context, "5205 1000"));
        exited(5207, ApplicationExitInfo.REASON_OTHER, 16_000, "MemoryLimiter:AnonSwap",
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED);
        assertFalse("a kill in the background counted", HushfacebookPause.diedYoungFromACrash(context, "5207 1000"));
        exited(5208, ApplicationExitInfo.REASON_OTHER, 17_000, "MemoryLimiter:AnonSwap",
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE);
        assertTrue("a kill in view didn't count", HushfacebookPause.diedYoungFromACrash(context, "5208 1000"));
        // A kill after the minute is no young death, whatever the reason.
        exited(5206, ApplicationExitInfo.REASON_OTHER, 1000 + 120_000, "MemoryLimiter:AnonSwap");
        assertFalse(HushfacebookPause.diedYoungFromACrash(context, "5206 1000"));
    }

    /** Three memory-limiter kills in a row inside their first minute turn safe mode on. */
    @Test @Config(sdk = 30)
    public void threeMemoryLimiterKillsInARowTurnSafeModeOn() {
        exited(5301, ApplicationExitInfo.REASON_OTHER, 11_000, "MemoryLimiter:AnonSwap");
        exited(5302, ApplicationExitInfo.REASON_OTHER, 22_000, "MemoryLimiter:AnonSwap");
        exited(5303, ApplicationExitInfo.REASON_OTHER, 33_000, "MemoryLimiter:AnonSwap");
        start("5301 10000");
        start("5302 21000");
        assertFalse("two kills are not yet a loop", Setting.isPaused());
        start("5303 32000");
        assertEquals(HushfacebookPause.Reason.CRASH_LOOP, HushfacebookPause.reason());
    }

    /**
     * What Android 17 hands back for a limiter kill (#528). Its getDescription() puts the kill's
     * subreason in front whenever there is one, so the kill of a process in view reads
     * "[KILL PID] MemoryLimiter:AnonSwap" (seen on the 37.0 image, reason 13, subreason 12). The
     * 37.2 SDK gives the limiter a reason of its own, REASON_MEMORY_LIMITER = 17. Both count in
     * view and neither does in the background, and a subreason doesn't make any other kill one.
     */
    @Test @Config(sdk = 30)
    public void android17sLimiterKillCountsWithItsSubreasonInFrontAndUnderItsOwnReason() {
        int memoryLimiter = 17;
        exited(5401, ApplicationExitInfo.REASON_OTHER, 11_000, "[KILL PID] MemoryLimiter:AnonSwap");
        exited(5402, memoryLimiter, 12_000, "AnonSwap");
        exited(5403, memoryLimiter, 13_000, null);
        exited(5404, ApplicationExitInfo.REASON_OTHER, 14_000, "[KILL PID] remove task");
        exited(5405, ApplicationExitInfo.REASON_OTHER, 15_000, "[KILL PID] a note: MemoryLimiter:AnonSwap");
        exited(5406, ApplicationExitInfo.REASON_OTHER, 16_000, "[KILL PID] MemoryLimiter:AnonSwap",
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED);
        exited(5407, memoryLimiter, 17_000, "AnonSwap", ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED);
        exited(5408, 18, 18_000, "MemoryLimiter:AnonSwap");

        assertTrue("the kill read with its subreason in front didn't count",
                HushfacebookPause.diedYoungFromACrash(context, "5401 1000"));
        assertTrue("a kill under the limiter's own reason didn't count",
                HushfacebookPause.diedYoungFromACrash(context, "5402 1000"));
        assertTrue("a kill under the limiter's own reason with no description didn't count",
                HushfacebookPause.diedYoungFromACrash(context, "5403 1000"));
        assertFalse(HushfacebookPause.diedYoungFromACrash(context, "5404 1000"));
        assertFalse(HushfacebookPause.diedYoungFromACrash(context, "5405 1000"));
        assertFalse("a kill in the background counted", HushfacebookPause.diedYoungFromACrash(context, "5406 1000"));
        assertFalse("a kill in the background counted", HushfacebookPause.diedYoungFromACrash(context, "5407 1000"));
        assertFalse("another reason counted for its description", HushfacebookPause.diedYoungFromACrash(context, "5408 1000"));

        exited(5409, ApplicationExitInfo.REASON_OTHER, 33_000, "[KILL PID] MemoryLimiter:AnonSwap");
        start("5401 10000");
        start("5402 11000");
        assertFalse("two kills are not yet a loop", Setting.isPaused());
        start("5409 32000");
        assertEquals(HushfacebookPause.Reason.CRASH_LOOP, HushfacebookPause.reason());
    }

    @Test @Config(sdk = 30)
    public void android11CountsCrashesNativeCrashesAndHangsButNotBeingSwipedAway() {
        exited(5101, ApplicationExitInfo.REASON_CRASH, 11_000);
        exited(5102, ApplicationExitInfo.REASON_CRASH_NATIVE, 12_000);
        exited(5103, ApplicationExitInfo.REASON_ANR, 13_000);
        exited(5104, ApplicationExitInfo.REASON_USER_REQUESTED, 14_000);
        exited(5105, ApplicationExitInfo.REASON_LOW_MEMORY, 15_000);

        assertTrue(HushfacebookPause.diedYoungFromACrash(context, "5101 1000"));
        assertTrue(HushfacebookPause.diedYoungFromACrash(context, "5102 1000"));
        assertTrue(HushfacebookPause.diedYoungFromACrash(context, "5103 1000"));
        // Android's own reason wins over the handler's mark: a force stop is not a crash.
        assertFalse(HushfacebookPause.diedYoungFromACrash(context, "5104 1000 crashed"));
        assertFalse(HushfacebookPause.diedYoungFromACrash(context, "5105 1000"));
        // No reason on record for the process, so the handler's mark decides.
        assertTrue(HushfacebookPause.diedYoungFromACrash(context, "5199 1000 crashed"));
        assertFalse(HushfacebookPause.diedYoungFromACrash(context, "5199 1000"));
        assertFalse(HushfacebookPause.diedYoungFromACrash(context, "not a pid"));
    }

    @Test @Config(sdk = 30)
    public void android11ReadsTheCrashOffTheFirstRecordSinceTheStart() {
        // Held open behind "keeps stopping": Android wrote the crash down at 30 s and the kill
        // that ended the dialog five minutes later. The first record is the crash.
        exited(6101, ApplicationExitInfo.REASON_CRASH, 1000 + 30_000);
        exited(6101, ApplicationExitInfo.REASON_CRASH, 1000 + 330_000);
        assertTrue(HushfacebookPause.diedYoungFromACrash(context, "6101 1000"));

        // The device slept before the process saw a minute of uptime, so the record was still
        // there when it crashed two minutes of wall time after its start: not a young crash.
        exited(6102, ApplicationExitInfo.REASON_CRASH, 1000 + 120_000);
        assertFalse(HushfacebookPause.diedYoungFromACrash(context, "6102 1000 crashed"));

        // A pid handed out again: the crash on record is older than this start, so it belonged
        // to another process, and only the handler's mark can speak for this one.
        exited(6103, ApplicationExitInfo.REASON_CRASH, 500);
        assertFalse(HushfacebookPause.diedYoungFromACrash(context, "6103 1000"));
        assertTrue(HushfacebookPause.diedYoungFromACrash(context, "6103 1000 crashed"));
    }

    @Test public void theMarkerFilePausesEveryStartUntilItIsGone() throws Exception {
        File marker = HushfacebookPause.markerFile(context);
        assertTrue(marker.getParentFile().exists() || marker.getParentFile().mkdirs());
        assertTrue(marker.createNewFile());

        start(null);
        assertEquals(HushfacebookPause.Reason.MARKER_FILE, HushfacebookPause.reason());
        assertTrue(Setting.isPaused());

        assertEquals("turning back on could not remove the marker", HushfacebookPause.Reason.NONE,
                HushfacebookPause.turnBackOn(context));
        assertFalse(marker.exists());
        start(null);
        assertEquals(HushfacebookPause.Reason.NONE, HushfacebookPause.reason());
    }

    /** Android 11's adb can't write in Android/data, so the same file in Android/media pauses too. */
    @Test public void theMarkerInFacebooksMediaFolderPausesTooAndResumeRemovesBoth() throws Exception {
        File media = HushfacebookPause.mediaMarkerFile(context);
        assertTrue(media.getPath(), media.getPath().replace('\\', '/')
                .endsWith("/Android/media/" + context.getPackageName() + "/" + HushfacebookPause.MARKER_FILE_NAME));
        assertTrue(media.getParentFile().exists() || media.getParentFile().mkdirs());
        assertTrue(media.createNewFile());

        start(null);
        assertEquals(HushfacebookPause.Reason.MARKER_FILE, HushfacebookPause.reason());
        assertEquals(media, HushfacebookPause.presentMarker(context));

        File data = HushfacebookPause.markerFile(context);
        assertTrue(data.getParentFile().exists() || data.getParentFile().mkdirs());
        assertTrue(data.createNewFile());
        assertEquals("the files folder's marker is named first", data, HushfacebookPause.presentMarker(context));

        BaseSettings.PAUSED.save(true);
        assertEquals(HushfacebookPause.Reason.NONE, HushfacebookPause.turnBackOn(context));
        assertFalse(media.exists());
        assertFalse(data.exists());
        assertFalse(BaseSettings.PAUSED.savedValue());
        start(null);
        assertEquals(HushfacebookPause.Reason.NONE, HushfacebookPause.reason());
    }

    /** A media marker that can't be removed holds the switches the same way. */
    @Test public void aMediaMarkerThatCanNotBeRemovedLeavesThePauseSwitchOn() throws Exception {
        File media = HushfacebookPause.mediaMarkerFile(context);
        File held = new File(media, "held");
        // A folder with something in it can't be deleted.
        assertTrue(media.mkdirs() && held.createNewFile());
        try {
            BaseSettings.PAUSED.save(true);
            assertEquals(HushfacebookPause.Reason.MARKER_FILE, HushfacebookPause.turnBackOn(context));
            assertTrue("the Pause switch went while the file stayed", BaseSettings.PAUSED.savedValue());
        } finally {
            held.delete();
            media.delete();
        }
    }

    @Test public void thePauseSwitchPausesTheNextStartAndTurningBackOnUndoesEverything() {
        BaseSettings.PAUSED.save(true);
        start(null);
        assertEquals(HushfacebookPause.Reason.SWITCH, HushfacebookPause.reason());

        BaseSettings.SAFE_MODE.save(true);
        HushfacebookPause.write(streak, "2");
        assertEquals(HushfacebookPause.Reason.NONE, HushfacebookPause.turnBackOn(context));
        assertFalse(BaseSettings.PAUSED.savedValue());
        assertFalse(BaseSettings.SAFE_MODE.savedValue());
        assertEquals("0", HushfacebookPause.read(streak));
        start(null);
        assertFalse(Setting.isPaused());
    }

    /**
     * Safe mode and the Pause switch go off together or not at all, so a failed write can't leave
     * the next start paused by one of them after the other went. Whatever happened, the switches,
     * the store and the next start agree.
     */
    @Test public void resumeTurnsBothSwitchesOffTogetherOrNeither() {
        FailingStore.Fault[][] scripts = {
                {FailingStore.Fault.NONE, FailingStore.Fault.COMMIT_THROWS},
                {FailingStore.Fault.COMMIT_THROWS, FailingStore.Fault.COMMIT_THROWS},
                {FailingStore.Fault.COMMIT_FALSE, FailingStore.Fault.LOST}};
        for (FailingStore.Fault[] script : scripts) {
            BaseSettings.SAFE_MODE.save(true);
            BaseSettings.PAUSED.save(true);
            HushfacebookPause.Reason still;
            try (FailingStore ignored = FailingStore.install(script)) {
                still = HushfacebookPause.turnBackOn(context);
            }
            String name = java.util.Arrays.toString(script);
            assertEquals(name + " answered " + still, BaseSettings.PAUSED.savedValue(), still != HushfacebookPause.Reason.NONE);
            assertEquals(name + " turned one switch off", BaseSettings.SAFE_MODE.savedValue(),
                    BaseSettings.PAUSED.savedValue());
            assertEquals(name, BaseSettings.SAFE_MODE.savedValue(), stored(BaseSettings.SAFE_MODE));
            assertEquals(name, BaseSettings.PAUSED.savedValue(), stored(BaseSettings.PAUSED));
            assertEquals(name, BaseSettings.PAUSED.savedValue(), HushfacebookPause.pausesNextStart(context));
        }
    }

    /**
     * A marker that can't be removed leaves the switches on: the pause they hold still applies
     * once the file is gone, and nothing says it won't.
     */
    @Test public void aMarkerThatCanNotBeRemovedLeavesThePauseSwitchOn() throws Exception {
        File marker = HushfacebookPause.markerFile(context);
        File held = new File(marker, "held");
        // A folder with something in it can't be deleted.
        assertTrue(marker.mkdirs() && held.createNewFile());
        try {
            BaseSettings.PAUSED.save(true);
            assertEquals(HushfacebookPause.Reason.MARKER_FILE, HushfacebookPause.turnBackOn(context));
            assertTrue("the Pause switch went while the file stayed", BaseSettings.PAUSED.savedValue());
            assertTrue(marker.exists());
            assertTrue(HushfacebookPause.pausesNextStart(context));
        } finally {
            held.delete();
            marker.delete();
        }
    }

    private static boolean stored(BooleanSetting setting) {
        return Setting.preferences.preferences.getBoolean(setting.key, setting.defaultValue);
    }

    @Test public void theCrashHandlerKeepsTheTraceAndHandsTheCrashOn() {
        Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        List<Throwable> handedOn = new ArrayList<>();
        try {
            Thread.setDefaultUncaughtExceptionHandler((thread, crash) -> handedOn.add(crash));
            LogBufferManager.clearLogBuffer();
            start(null);
            Throwable crash = new IllegalStateException("a start that crashed");
            Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), crash);

            assertEquals("the crash didn't reach the handler that was there before",
                    Collections.singletonList(crash), handedOn);
            String kept = LogBufferManager.readCrashReport(context);
            assertTrue("the trace wasn't kept for the report: " + kept,
                    kept.contains("java.lang.IllegalStateException: a start that crashed"));
            assertTrue("the start wasn't marked", HushfacebookPause.read(record).trim().endsWith("crashed"));
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before);
            LogBufferManager.clearLogBuffer();
        }
    }

    @Test public void aScreenPutsTheCrashHandlerBackInFrontOfOneFacebookAdded() {
        Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        try {
            // A process that never installed it, a side process, is left as it is.
            Thread.UncaughtExceptionHandler facebooks = (thread, crash) -> { };
            Thread.setDefaultUncaughtExceptionHandler(facebooks);
            HushfacebookPause.keepCrashMarkOnTop();
            assertSame(facebooks, Thread.getDefaultUncaughtExceptionHandler());

            start(null);
            Thread.UncaughtExceptionHandler ours = Thread.getDefaultUncaughtExceptionHandler();
            assertEquals("CrashMark", ours.getClass().getSimpleName());
            Thread.setDefaultUncaughtExceptionHandler(facebooks);
            HushfacebookPause.keepCrashMarkOnTop();
            Thread.UncaughtExceptionHandler front = Thread.getDefaultUncaughtExceptionHandler();
            assertEquals("the handler wasn't put back in front", "CrashMark", front.getClass().getSimpleName());

            // Already in front, it isn't wrapped again.
            HushfacebookPause.keepCrashMarkOnTop();
            assertSame(front, Thread.getDefaultUncaughtExceptionHandler());
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before);
        }
    }

    /** A handler that puts itself back in front each time gets the front after a bounded number of moves. */
    @Test public void theCrashHandlerMovesToTheFrontABoundedNumberOfTimes() {
        Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        try {
            start(null);
            Thread.UncaughtExceptionHandler pushy = (thread, crash) -> { };
            int moves = 0;
            for (int screen = 0; screen < HushfacebookPause.CRASH_MARK_MOVES_MAX + 5; screen++) {
                Thread.setDefaultUncaughtExceptionHandler(pushy);
                HushfacebookPause.keepCrashMarkOnTop();
                if (Thread.getDefaultUncaughtExceptionHandler() != pushy) moves++;
            }
            assertEquals(HushfacebookPause.CRASH_MARK_MOVES_MAX, moves);
            assertSame("past the bound it stays behind", pushy, Thread.getDefaultUncaughtExceptionHandler());
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before);
        }
    }

    /** An exception off the main thread that a later handler swallowed leaves the earlier crash in the report. */
    @Test public void aSwallowedExceptionOffTheMainThreadLeavesTheEarlierCrash() throws Exception {
        Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        try {
            Thread.setDefaultUncaughtExceptionHandler((thread, crash) -> { });
            LogBufferManager.clearLogBuffer();
            LogBufferManager.persistCrashReport(context, "an earlier start's crash");
            start(null);
            Thread.getDefaultUncaughtExceptionHandler()
                    .uncaughtException(new Thread("CombinedTP3"), new IllegalStateException("swallowed"));
            String kept = LogBufferManager.readCrashReport(context);
            assertTrue(kept, kept.contains("an earlier start's crash"));
            assertFalse(kept, kept.contains("swallowed"));
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before);
            LogBufferManager.clearLogBuffer();
        }
    }

    @Test public void theStreakFileSurvivesNonsense() {
        HushfacebookPause.write(streak, "not a number");
        assertEquals(0, HushfacebookPause.parseCount(HushfacebookPause.read(streak)));
        assertEquals(0, HushfacebookPause.parseCount("-4"));
        assertEquals(0, HushfacebookPause.parseCount(null));
        assertNull(HushfacebookPause.read(new File(context.getFilesDir(), "never-written")));
    }
}
