/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.shared.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.MediaStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivityManager;
import org.robolectric.shadows.ShadowContentResolver;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * A Java crash that ends the app also lands in Download/Hushfacebook as a text file holding the
 * report's redacted crash section, so it can be sent when Facebook won't open. At most five are
 * kept, nothing is written with the switch off or while paused, and an exception the process lived
 * through takes its file back. A death the Java handler never sees gets its file from the next start,
 * paused or not.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30)
public class CrashFileTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Context context;
    private Downloads downloads;
    private final Map<Long, ByteArrayOutputStream> bodies = new TreeMap<>();

    @Before
    public void start() throws Exception {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        // The first start in this JVM files the last deaths in the background, from whichever test
        // ran first. Whatever it left waiting for the switch would land in this test's Downloads.
        Utils.awaitBackgroundTasksForTests();
        CrashFile.resetForTests();
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
        LogBufferManager.clearLogBuffer();
        JavaCrashReport.resetForTests();
        // Loads Facebook's settings, which hand the crash file its switch, as a start does.
        Settings.SAVE_CRASH_FILE.resetToDefault();
        downloads = Robolectric.setupContentProvider(Downloads.class, MediaStore.AUTHORITY);
        ShadowContentResolver resolver = Shadows.shadowOf(context.getContentResolver());
        for (long id = 1; id <= 10; id++) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            bodies.put(id, body);
            resolver.registerOutputStream(downloads.uriFor(id), body);
        }
    }

    @After
    public void finish() {
        PauseForTests.resume();
        Settings.SAVE_CRASH_FILE.resetToDefault();
        LogBufferManager.clearLogBuffer();
        JavaCrashReport.resetForTests();
    }

    private String body(long id) throws Exception {
        return bodies.get(id).toString(StandardCharsets.UTF_8.name());
    }

    @Test
    public void aCrashGoesToDownloadsWithTheReportsRedactedText() throws Exception {
        assertTrue("the switch doesn't start on", Settings.SAVE_CRASH_FILE.get());
        Throwable crash = new IllegalStateException("outer, see https://example.com/private?id=12345",
                new NullPointerException("inner cause"));
        JavaCrashReport.save(Thread.currentThread(), crash);

        assertEquals("not one file", 1, downloads.ids().size());
        ContentValues row = downloads.row(1);
        assertEquals(CrashFile.FOLDER, row.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
        assertEquals("text/plain", row.getAsString(MediaStore.MediaColumns.MIME_TYPE));
        String name = row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME);
        assertTrue(name, name.matches("crash-\\d{4}-\\d\\d-\\d\\d-\\d{4}\\.txt"));

        String file = body(1);
        assertEquals("the file isn't the report's crash section", LogBufferManager.readCrashReport(context), file);
        assertTrue(file, file.contains("app_version: ") && file.contains("morphe_version: "));
        assertTrue(file, file.contains("app_version_code: "));
        assertTrue(file, file.contains("abi: app "));
        assertTrue(file, file.contains("patch_build: unknown\n"));
        assertTrue(file, file.contains("Caused by: java.lang.NullPointerException: inner cause"));
        assertFalse("the address went in as it was: " + file, file.contains("example.com/private"));
    }

    @Test
    public void theNewestFiveAreKept() throws Exception {
        for (int i = 1; i <= 7; i++) {
            assertNotNull("crash " + i + " wasn't written", CrashFile.write(context, "crash " + i, 1_000_000L * i));
        }
        List<Long> left = downloads.ids();
        assertEquals("the oldest weren't let go", java.util.Arrays.asList(3L, 4L, 5L, 6L, 7L), left);
        assertEquals("crash 7", body(7));

        // Another app's file in the same folder, or one of this install's with another name, isn't counted.
        downloads.add("notes.txt", CrashFile.FOLDER);
        downloads.add("crash-2026-01-01-0000.txt", "Download/Other/");
        CrashFile.write(context, "crash 8", 9_000_000L);
        assertEquals(java.util.Arrays.asList(4L, 5L, 6L, 7L, 8L, 9L, 10L), downloads.ids());
    }

    @Test
    public void offOrPausedNothingIsWritten() {
        Settings.SAVE_CRASH_FILE.save(false);
        JavaCrashReport.save(Thread.currentThread(), new IllegalStateException("with the switch off"));
        assertTrue("a file was written with the switch off", downloads.ids().isEmpty());
        assertTrue("the report lost the crash", LogBufferManager.readCrashReport(context).contains("with the switch off"));

        Settings.SAVE_CRASH_FILE.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        JavaCrashReport.save(Thread.currentThread(), new IllegalStateException("while paused"));
        assertTrue("a file was written while paused", downloads.ids().isEmpty());
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() ->
                JavaCrashReport.save(Thread.currentThread(), new IllegalStateException("before settings were ready")));
        assertTrue("a file was written before the settings were ready", downloads.ids().isEmpty());

        JavaCrashReport.save(Thread.currentThread(), new IllegalStateException("the control"));
        assertEquals("the control: on and not paused, the crash is written", 1, downloads.ids().size());
    }

    /**
     * An exception off the main thread that Facebook swallowed ended nothing, so its file goes with
     * it. One on the main thread stays, as its report section does.
     */
    @Test
    public void aSwallowedExceptionOffTheMainThreadTakesItsFileBack() {
        Thread worker = new Thread("CombinedTP3");
        JavaCrashReport.Saved before = JavaCrashReport.save(worker, new IllegalStateException("swallowed"));
        assertEquals(1, downloads.ids().size());
        JavaCrashReport.survived(worker, before);
        assertTrue("the swallowed exception's file stayed", downloads.ids().isEmpty());

        JavaCrashReport.Saved main = JavaCrashReport.save(Thread.currentThread(), new IllegalStateException("on the main thread"));
        assertEquals(1, downloads.ids().size());
        JavaCrashReport.survived(Thread.currentThread(), main);
        assertEquals("the control: on the main thread the file stays", 1, downloads.ids().size());

        // A later swallowed exception that wrote no file has nothing to take back.
        Settings.SAVE_CRASH_FILE.save(false);
        JavaCrashReport.Saved quiet = JavaCrashReport.save(worker, new IllegalStateException("swallowed with the switch off"));
        JavaCrashReport.survived(worker, quiet);
        assertEquals("a swallowed exception with no file of its own took another's", 1, downloads.ids().size());
    }

    /**
     * A swallowed exception takes back only its own file: another thread's crash written after it
     * keeps its file, and two that both end nothing leave neither.
     */
    @Test
    public void aSwallowedExceptionTakesBackOnlyItsOwnFile() throws Exception {
        Thread worker = new Thread("CombinedTP3");
        JavaCrashReport.Saved swallowed = JavaCrashReport.save(worker, new IllegalStateException("swallowed"));
        Throwable fatal = new IllegalArgumentException("the fatal one");
        JavaCrashReport.save(new Thread("Background"), fatal);
        assertEquals(java.util.Arrays.asList(1L, 2L), downloads.ids());
        JavaCrashReport.survived(worker, swallowed);
        assertEquals("the newer crash's file went with the swallowed one", java.util.Collections.singletonList(2L), downloads.ids());
        assertTrue(body(2), body(2).contains("the fatal one"));
        JavaCrashReport.save(new Thread("Background"), fatal);
        assertEquals("the same crash handed back got a second file", java.util.Collections.singletonList(2L), downloads.ids());

        JavaCrashReport.resetForTests();
        Thread one = new Thread("CombinedTP4");
        Thread two = new Thread("CombinedTP5");
        JavaCrashReport.Saved first = JavaCrashReport.save(one, new IllegalStateException("first swallowed"));
        JavaCrashReport.Saved second = JavaCrashReport.save(two, new IllegalStateException("second swallowed"));
        assertEquals(java.util.Arrays.asList(2L, 3L, 4L), downloads.ids());
        JavaCrashReport.survived(one, first);
        JavaCrashReport.survived(two, second);
        assertEquals("a swallowed exception's file stayed", java.util.Collections.singletonList(2L), downloads.ids());
    }

    /** When the deaths below are filed up to, as a start before them left it. */
    private static final long MARK = 1_791_727_000_000L;

    /** A death Android recorded for Facebook's main process on screen, or for [process] when it's given. */
    private void died(int reason, long at, String description, String process, String trace) {
        died(reason, at, description, process, trace, ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
    }

    /** The same, for a process that was as important to Android as [importance] says. */
    private void died(int reason, long at, String description, String process, String trace, int importance) {
        ShadowActivityManager.ApplicationExitInfoBuilder exit = ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setProcessName(process == null ? context.getPackageName() : process)
                .setPid(7000 + (int) ((at - MARK) / 1000))
                .setReason(reason)
                .setDescription(description)
                .setImportance(importance)
                .setTimestamp(at)
                .setPss(812_345)
                .setRss(901_234);
        if (trace != null) exit.setTraceInputStream(new ByteArrayInputStream(trace.getBytes(StandardCharsets.UTF_8)));
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        Shadows.shadowOf(manager).addApplicationExitInfo(exit.build());
    }

    private void markFiledUpTo(long at) throws Exception {
        Files.write(PauseForTests.deathsFiledMark(context).toPath(), Long.toString(at).getBytes(StandardCharsets.US_ASCII));
    }

    private String mark() throws Exception {
        return new String(Files.readAllBytes(PauseForTests.deathsFiledMark(context).toPath()), StandardCharsets.US_ASCII).trim();
    }

    /** This process starts again, not paused, the way the next Facebook start does. */
    private void nextStart() {
        PauseForTests.newStart(context);
        PauseForTests.resume();
        // Installed long before any death here, unless a test says otherwise.
        installedAt(0L);
    }

    private void installedAt(long at) {
        Shadows.shadowOf(context.getPackageManager())
                .getInternalMutablePackageInfo(context.getPackageName()).lastUpdateTime = at;
    }

    /**
     * A native crash, a hang and a kill by Android 17's memory limiter never reach the Java
     * handler (#528). The next start finds each in Android's exit records and files it, named for
     * when it happened and saying what it was. A Java crash, a swipe-away, a limiter kill in the
     * background, another process and anything already filed get nothing, and the start after
     * that files nothing twice.
     */
    @Test
    public void deathsTheJavaHandlerNeverSeesGetAFileFromTheNextStart() throws Exception {
        nextStart();
        markFiledUpTo(MARK);
        died(ApplicationExitInfo.REASON_CRASH_NATIVE, MARK - 5_000, null, null, null);
        died(ApplicationExitInfo.REASON_CRASH_NATIVE, MARK + 1_000, null, null, null);
        died(ApplicationExitInfo.REASON_ANR, MARK + 2_000, "Input dispatching timed out", null,
                "\"main\" prio=5 tid=1 Blocked\n  at com.facebook.Feed.load(Feed.java:10)\n"
                        + "  see https://example.com/private?id=12345\n");
        died(ApplicationExitInfo.REASON_OTHER, MARK + 3_000, "MemoryLimiter:AnonSwap", null, null);
        died(ApplicationExitInfo.REASON_OTHER, MARK + 3_500, "MemoryLimiter:AnonSwap", null, null,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED);
        died(ApplicationExitInfo.REASON_OTHER, MARK + 4_000, "remove task", null, null);
        died(ApplicationExitInfo.REASON_CRASH, MARK + 5_000, null, null, null);
        died(ApplicationExitInfo.REASON_USER_REQUESTED, MARK + 6_000, null, null, null);
        died(ApplicationExitInfo.REASON_CRASH_NATIVE, MARK + 7_000, null, context.getPackageName() + ":browser", null);

        PauseForTests.fileLastDeaths();

        assertEquals("not one file for each death", Arrays.asList(1L, 2L, 3L), downloads.ids());
        assertEquals(CrashFile.FOLDER, downloads.row(1).getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
        assertEquals(CrashFile.fileName(MARK + 1_000), downloads.row(1).getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        String nativeCrash = body(1);
        assertTrue(nativeCrash, nativeCrash.startsWith("schema: 1\ncomplete: true\n"));
        assertTrue(nativeCrash, nativeCrash.contains("death: native crash\n"));
        assertTrue(nativeCrash, nativeCrash.contains("timestamp_utc: " + Instant.ofEpochMilli(MARK + 1_000) + "\n"));
        assertTrue(nativeCrash, nativeCrash.contains("app_version: ") && nativeCrash.contains("patch_build: unknown\n"));
        String hang = body(2);
        assertTrue(hang, hang.contains("death: not responding (ANR)\n"));
        assertTrue(hang, hang.contains("exit_description: Input dispatching timed out\n"));
        assertTrue(hang, hang.contains("[ANR TRACE]\n\"main\" prio=5 tid=1 Blocked\n  at com.facebook.Feed.load(Feed.java:10)"));
        assertFalse("the address went in as it was: " + hang, hang.contains("example.com/private"));
        String limiter = body(3);
        assertTrue(limiter, limiter.contains("death: killed by Android's memory limiter\n"));
        assertTrue(limiter, limiter.contains("exit_description: MemoryLimiter:AnonSwap\n"));
        assertTrue(limiter, limiter.contains("exit_pss_kb: 812345\nexit_rss_kb: 901234\n"));
        assertFalse(limiter, limiter.contains("[ANR TRACE]"));
        assertEquals("the mark isn't at the newest death filed", Long.toString(MARK + 3_000), mark());

        nextStart();
        PauseForTests.fileLastDeaths();
        assertEquals("a death was filed twice", Arrays.asList(1L, 2L, 3L), downloads.ids());
    }

    /**
     * Android 17 reads a limiter kill back with its subreason in front, "[KILL PID]
     * MemoryLimiter:AnonSwap" on the 37.0 image, and the 37.2 SDK records one under a reason of
     * its own, 17 (#528). Each gets its file in view, and the one in the background still doesn't.
     */
    @Test
    public void android17sLimiterKillsGetAFileInEitherShape() throws Exception {
        nextStart();
        markFiledUpTo(MARK);
        died(ApplicationExitInfo.REASON_OTHER, MARK + 1_000, "[KILL PID] MemoryLimiter:AnonSwap", null, null);
        died(17, MARK + 2_000, "AnonSwap", null, null);
        died(17, MARK + 3_000, "AnonSwap", null, null, ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED);
        died(ApplicationExitInfo.REASON_OTHER, MARK + 4_000, "[KILL PID] remove task", null, null);

        PauseForTests.fileLastDeaths();

        assertEquals("not one file for each limiter kill in view", Arrays.asList(1L, 2L), downloads.ids());
        String prefixed = body(1);
        assertTrue(prefixed, prefixed.contains("death: killed by Android's memory limiter\n"));
        assertTrue(prefixed, prefixed.contains("exit_description: [KILL PID] MemoryLimiter:AnonSwap\n"));
        String ownReason = body(2);
        assertTrue(ownReason, ownReason.contains("death: killed by Android's memory limiter\n"));
        assertTrue(ownReason, ownReason.contains("exit_reason: 17\n"));
        assertEquals("the mark isn't at the newest death filed", Long.toString(MARK + 2_000), mark());
    }

    /**
     * With the switch off a death gets no file and is done with. A start that runs paused after
     * three crashes still files the deaths that paused it, by the switch as it was saved, since
     * those files are what a report needs.
     */
    @Test
    public void theSwitchOffFilesNothingAndAPausedStartStillFilesByTheSavedSwitch() throws Exception {
        nextStart();
        markFiledUpTo(MARK);
        died(ApplicationExitInfo.REASON_CRASH_NATIVE, MARK + 1_000, null, null, null);
        Settings.SAVE_CRASH_FILE.save(false);
        PauseForTests.fileLastDeaths();
        assertTrue("a file was written with the switch off", downloads.ids().isEmpty());
        assertEquals("the switch off kept the death waiting", Long.toString(MARK + 1_000), mark());

        Settings.SAVE_CRASH_FILE.save(true);
        died(ApplicationExitInfo.REASON_CRASH_NATIVE, MARK + 2_000, null, null, null);
        died(ApplicationExitInfo.REASON_OTHER, MARK + 3_000, "MemoryLimiter:AnonSwap", null, null);
        PauseForTests.pause(HushfacebookPause.Reason.CRASH_LOOP);
        assertFalse("paused, the switch still answers on", Settings.SAVE_CRASH_FILE.get());
        PauseForTests.fileLastDeaths();
        assertEquals("a paused start didn't file the deaths that paused it", Arrays.asList(1L, 2L), downloads.ids());
        assertEquals(CrashFile.fileName(MARK + 2_000), downloads.row(1).getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertTrue(body(2), body(2).contains("exit_description: MemoryLimiter:AnonSwap\n"));
        assertEquals(Long.toString(MARK + 3_000), mark());
    }

    /**
     * Found before Facebook's settings hand the crash file its switch, the deaths wait for it and
     * are filed the moment it comes, off the thread that loads the settings.
     */
    @Test
    public void deathsFoundBeforeTheSwitchIsHandedOverWaitForIt() throws Exception {
        nextStart();
        markFiledUpTo(MARK);
        died(ApplicationExitInfo.REASON_CRASH_NATIVE, MARK + 1_000, null, null, null);
        CrashFile.forgetSwitchForTests();
        try {
            PauseForTests.fileLastDeaths();
            assertTrue("a file was written before the switch was known", downloads.ids().isEmpty());
            assertEquals("the mark moved before the death was filed", Long.toString(MARK), mark());
        } finally {
            CrashFile.useSwitch(Settings.SAVE_CRASH_FILE);
        }
        Utils.awaitBackgroundTasksForTests();
        assertEquals(java.util.Collections.singletonList(1L), downloads.ids());
        assertEquals(Long.toString(MARK + 1_000), mark());
    }

    /**
     * A start that stays on the login screen runs no hook that reads a setting, so the switch never
     * came and the limiter kill it found waited for nothing (#528). Facebook's start hands the switch
     * over by itself, and the death gets its file with nothing else loading the settings.
     */
    @Test
    public void aSignedOutStartStillFilesTheDeathsItFound() throws Exception {
        nextStart();
        markFiledUpTo(MARK);
        died(ApplicationExitInfo.REASON_OTHER, MARK + 1_000, "MemoryLimiter:AnonSwap", null, null);
        CrashFile.forgetSwitchForTests();
        try {
            PauseForTests.fileLastDeaths();
            assertTrue("a file was written before the switch was known", downloads.ids().isEmpty());

            app.morphe.extension.facebook.settings.CrashFileSwitch.handOver();
            Utils.awaitBackgroundTasksForTests();
            assertEquals("the start didn't hand the switch over", java.util.Collections.singletonList(1L), downloads.ids());
            assertTrue(body(1), body(1).contains("death: killed by Android's memory limiter\n"));
            assertEquals(Long.toString(MARK + 1_000), mark());
        } finally {
            CrashFile.useSwitch(Settings.SAVE_CRASH_FILE);
            Utils.awaitBackgroundTasksForTests();
        }
    }

    /**
     * A file that can't be written stops the run there: the mark stays at the last death that got
     * one, and the next start files the rest, each once.
     */
    @Test
    public void aFileThatCanNotBeWrittenLeavesItAndTheRestForTheNextStart() throws Exception {
        nextStart();
        markFiledUpTo(MARK);
        died(ApplicationExitInfo.REASON_CRASH_NATIVE, MARK + 1_000, null, null, null);
        died(ApplicationExitInfo.REASON_CRASH_NATIVE, MARK + 2_000, null, null, null);
        died(ApplicationExitInfo.REASON_CRASH_NATIVE, MARK + 3_000, null, null, null);
        downloads.insertsLeft = 1;
        PauseForTests.fileLastDeaths();
        assertEquals(java.util.Collections.singletonList(1L), downloads.ids());
        assertEquals("the mark went past a death with no file", Long.toString(MARK + 1_000), mark());

        downloads.insertsLeft = Integer.MAX_VALUE;
        nextStart();
        PauseForTests.fileLastDeaths();
        assertEquals("the rest weren't filed, or one was filed twice", Arrays.asList(1L, 2L, 3L), downloads.ids());
        assertEquals(CrashFile.fileName(MARK + 2_000), downloads.row(2).getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertEquals(Long.toString(MARK + 3_000), mark());
    }

    /**
     * Facebook's start files the last start's deaths by itself, in the background, as soon as it
     * knows whether it runs paused, so a start that crashes as its first screen opens still files
     * the one before it.
     */
    @Test
    public void aStartFilesTheLastDeathsByItself() throws Exception {
        nextStart();
        markFiledUpTo(MARK);
        died(ApplicationExitInfo.REASON_CRASH_NATIVE, MARK + 1_000, null, null, null);
        PauseForTests.newStartFilingDeaths(context);
        Utils.awaitBackgroundTasksForTests();
        assertEquals(java.util.Collections.singletonList(1L), downloads.ids());
        assertTrue(body(1), body(1).contains("death: native crash\n"));
        assertEquals(Long.toString(MARK + 1_000), mark());
    }

    /**
     * A hang's trace opens with Android's header and every other thread can come before the main
     * one. The file keeps the subject line and the main thread's stack, not the header's bulk.
     */
    @Test
    public void aHangKeepsItsSubjectAndTheMainThreadsStack() throws Exception {
        nextStart();
        markFiledUpTo(MARK);
        StringBuilder trace = new StringBuilder("----- pid 7001 at 2026-11-19 18:00:00 -----\n")
                .append("Cmd line: com.facebook.katana\n")
                .append("Subject: Input dispatching timed out (Waiting to send key event)\n");
        for (int i = 0; i < 3_000; i++) trace.append("Build fingerprint and other header bulk ").append(i).append('\n');
        trace.append("\"Signal Catcher\" daemon prio=10 tid=7 Runnable\n  at nothing.Useful(Signal.java:1)\n\n")
                .append("\"main\" prio=5 tid=1 Blocked\n  at com.facebook.Feed.load(Feed.java:10)\n")
                .append("  - waiting to lock <0x0abc> held by thread 42\n");
        died(ApplicationExitInfo.REASON_ANR, MARK + 1_000, "Input dispatching timed out", null, trace.toString());
        PauseForTests.fileLastDeaths();

        String hang = body(1);
        assertTrue(hang, hang.contains("[ANR TRACE]\nSubject: Input dispatching timed out (Waiting to send key event)\n\n"
                + "\"main\" prio=5 tid=1 Blocked\n  at com.facebook.Feed.load(Feed.java:10)\n"));
        assertTrue(hang, hang.contains("waiting to lock <0x0abc>"));
        assertFalse("the header's bulk went in: " + hang, hang.contains("header bulk"));
        assertFalse("a thread from before the main one went in: " + hang, hang.contains("Signal Catcher"));
    }

    /**
     * A new install or an update has no mark yet, so its start files from the install or update
     * on: nothing from before it, and every death of a crash loop that began right after it, even
     * one so quick that no start lived long enough to set a mark.
     */
    @Test
    public void aStartWithNoMarkFilesFromTheLastInstallOrUpdateOn() throws Exception {
        nextStart();
        assertTrue(!PauseForTests.deathsFiledMark(context).exists() || PauseForTests.deathsFiledMark(context).delete());
        installedAt(MARK);
        died(ApplicationExitInfo.REASON_CRASH_NATIVE, MARK - 60_000, null, null, null);
        died(ApplicationExitInfo.REASON_CRASH_NATIVE, MARK + 1_000, null, null, null);
        died(ApplicationExitInfo.REASON_CRASH_NATIVE, MARK + 2_000, null, null, null);
        PauseForTests.fileLastDeaths();
        assertEquals("not each death since the update, or one from before it", Arrays.asList(1L, 2L), downloads.ids());
        assertEquals(CrashFile.fileName(MARK + 1_000), downloads.row(1).getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertEquals(Long.toString(MARK + 2_000), mark());
    }

    /**
     * A reinstall or update between a death and the next start, a first thing to try in a crash
     * loop, still leaves that death its file. This build's versions could be wrong for it, so the
     * file leaves them unknown and says when the install was.
     */
    @Test
    public void aDeathFromBeforeAnUpdateGetsItsFileWithoutThisBuildsVersions() throws Exception {
        nextStart();
        markFiledUpTo(MARK);
        installedAt(MARK + 1_500);
        died(ApplicationExitInfo.REASON_CRASH_NATIVE, MARK + 1_000, null, null, null);
        died(ApplicationExitInfo.REASON_CRASH_NATIVE, MARK + 2_000, null, null, null);
        PauseForTests.fileLastDeaths();
        assertEquals("not a file for each death", Arrays.asList(1L, 2L), downloads.ids());
        String before = body(1);
        assertTrue(before, before.contains("app_version: unknown\nmorphe_version: unknown\n"));
        assertTrue(before, before.contains("app_version_code: unknown\npatch_build: unknown\n"));
        assertTrue(before, before.contains("installed_utc: " + Instant.ofEpochMilli(MARK + 1_500) + "\n"));
        assertFalse(body(2), body(2).contains("installed_utc: "));
        assertEquals(Long.toString(MARK + 2_000), mark());
    }

    /** A long run of deaths files the newest ones, as many as the folder keeps. */
    @Test
    public void aLongRunOfDeathsFilesOnlyAsManyAsTheFolderKeeps() throws Exception {
        nextStart();
        markFiledUpTo(MARK);
        for (int i = 1; i <= 7; i++) died(ApplicationExitInfo.REASON_CRASH_NATIVE, MARK + i * 60_000L, null, null, null);
        PauseForTests.fileLastDeaths();
        assertEquals(Arrays.asList(1L, 2L, 3L, 4L, 5L), downloads.ids());
        assertEquals(CrashFile.fileName(MARK + 3 * 60_000L), downloads.row(1).getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertEquals(CrashFile.fileName(MARK + 7 * 60_000L), downloads.row(5).getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertEquals(Long.toString(MARK + 7 * 60_000L), mark());
    }

    @Test
    public void aFileIsNamedForThePhonesTime() {
        TimeZone before = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            // 2026-10-11 14:05 UTC.
            assertEquals("crash-2026-10-11-1405.txt", CrashFile.fileName(1_791_727_500_000L));
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            assertEquals("crash-2026-10-11-1005.txt", CrashFile.fileName(1_791_727_500_000L));
        } finally {
            TimeZone.setDefault(before);
        }
    }

    /** MediaStore's Downloads table, as much of it as a crash file touches. */
    public static final class Downloads extends ContentProvider {
        private final Map<Long, ContentValues> rows = new TreeMap<>();
        private long nextId = 1;
        /** How many more files it takes before it answers null, as a full or locked store does. */
        int insertsLeft = Integer.MAX_VALUE;

        Uri uriFor(long id) {
            return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
        }

        ContentValues row(long id) {
            ContentValues row = rows.get(id);
            assertNotNull("no Downloads entry " + id, row);
            return row;
        }

        List<Long> ids() {
            return new ArrayList<>(rows.keySet());
        }

        void add(String name, String folder) {
            ContentValues row = new ContentValues();
            row.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            row.put(MediaStore.MediaColumns.RELATIVE_PATH, folder);
            rows.put(nextId++, row);
        }

        @Override public boolean onCreate() {
            return true;
        }

        @Override public Uri insert(Uri uri, ContentValues values) {
            if (insertsLeft <= 0) return null;
            insertsLeft--;
            long id = nextId++;
            rows.put(id, new ContentValues(values));
            return uriFor(id);
        }

        /**
         * The collection, filtered the way the crash file asks: a folder, and a name LIKE
         * "crash-%.txt". Newest first, which here is the highest id, as DATE_ADDED then _ID sorts.
         */
        @Override public Cursor query(Uri uri, String[] projection, String selection,
                String[] selectionArgs, String sortOrder) {
            assertEquals(MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME
                    + " LIKE ?", selection);
            assertEquals(CrashFile.PREFIX + "%" + CrashFile.SUFFIX, selectionArgs[1]);
            MatrixCursor cursor = new MatrixCursor(projection);
            List<Long> newestFirst = ids();
            java.util.Collections.reverse(newestFirst);
            for (long id : newestFirst) {
                ContentValues row = rows.get(id);
                String name = row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME);
                if (!selectionArgs[0].equals(row.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
                        || !name.startsWith(CrashFile.PREFIX) || !name.endsWith(CrashFile.SUFFIX)) continue;
                cursor.addRow(new Object[]{id});
            }
            return cursor;
        }

        @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            return 0;
        }

        @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
            return rows.remove(ContentUris.parseId(uri)) == null ? 0 : 1;
        }

        @Override public String getType(Uri uri) {
            return "text/plain";
        }
    }
}
