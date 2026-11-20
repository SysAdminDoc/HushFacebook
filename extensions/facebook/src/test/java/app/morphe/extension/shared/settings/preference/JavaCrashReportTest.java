/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.shared.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.ApplicationExitInfo;
import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowActivityManager;

import java.time.Instant;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;

/**
 * A Java crash is kept with its whole trace for the next diagnostic report (#94), once for each
 * exception, and nothing that isn't a crash takes its place: not a missing one, and not one off the
 * main thread that Facebook swallowed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30)
public class JavaCrashReportTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Context context;

    @Before
    public void start() {
        IdentityUtils.code = 475817393L;
        IdentityUtils.identity = "unknown";
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
        LogBufferManager.clearLogBuffer();
        JavaCrashReport.resetForTests();
    }

    @After
    public void finish() {
        IdentityUtils.code = 475817393L;
        IdentityUtils.identity = "unknown";
        LogBufferManager.clearLogBuffer();
        JavaCrashReport.resetForTests();
    }

    @Implements(Utils.class)
    public static class IdentityUtils {
        static long code = 475817393L;
        static String identity = "unknown";
        @Implementation public static long getAppVersionCode() { return code; }
        @Implementation public static String getAppVersionName() { return "584.0.0.51.56"; }
        @Implementation public static String getPatchesReleaseVersion() { return "same-release"; }
        @Implementation public static String getPatchesBuildIdentity() { return identity; }
    }

    @Test
    @Config(shadows = IdentityUtils.class)
    public void aStandaloneCrashDistinguishesSourceBuildsWithTheSameReleaseName() {
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        context.getApplicationInfo().nativeLibraryDir = "/data/app/test/lib/arm64";
        try {
            for (String source : new String[]{"a", "b"}) {
                IdentityUtils.identity = "sha256=" + source.repeat(64) + "; source=clean:" + source.repeat(40)
                        + "; tree=" + source.repeat(40) + "; inputs=" + source.repeat(64);
                String report = JavaCrashReport.build(context, Thread.currentThread(), new IllegalStateException("startup"));
                assertTrue(report, report.contains("app_version: 584.0.0.51.56\n"));
                assertTrue(report, report.contains("app_version_code: 475817393\n"));
                assertTrue(report, report.contains("abi: app arm64, process "));
                assertTrue(report, report.contains("morphe_version: same-release\n"));
                assertTrue(report, report.contains("patch_build: " + IdentityUtils.identity + "\n"));
                assertTrue(report, report.contains("android_api: 30\n"));
            }
        } finally {
            context.getApplicationInfo().nativeLibraryDir = nativeDir;
        }
    }

    @Test
    @Config(shadows = IdentityUtils.class)
    public void numericRunsInVerifiedBuildHashesSurviveWhileCrashContentIsRedacted() {
        String sha = "a" + "12345678901234567890" + "b".repeat(43);
        String commit = "c" + "123456789012345" + "d".repeat(24);
        String inputs = "e" + "1234567890123456" + "f".repeat(47);
        IdentityUtils.identity = "sha256=" + sha + "; source=dirty:" + commit + "; tree=" + commit + "; inputs=" + inputs;
        String report = JavaCrashReport.build(context, null,
                new IllegalStateException("post_id=12345678901234567890 https://example.com/private"));
        assertTrue(report, report.contains("patch_build: " + IdentityUtils.identity + "\n"));
        assertFalse(report, report.contains("post_id=12345678901234567890"));
        assertFalse(report, report.contains("example.com/private"));
    }

    /**
     * A death Android recorded from before the last install or update gets its file without this
     * build's versions, which could be wrong for it, and says when that install was (#528).
     */
    @Test
    @Config(shadows = IdentityUtils.class)
    public void aDeathFromBeforeTheInstallLeavesTheVersionsUnknown() {
        IdentityUtils.identity = "sha256=" + "a".repeat(64) + "; source=clean:" + "a".repeat(40)
                + "; tree=" + "a".repeat(40) + "; inputs=" + "a".repeat(64);
        long installed = 1_791_727_001_500L;
        String before = JavaCrashReport.buildForExit(context, nativeCrashAt(installed - 500), "native crash", installed);
        assertTrue(before, before.contains("app_version: unknown\nmorphe_version: unknown\n"));
        assertTrue(before, before.contains("app_version_code: unknown\npatch_build: unknown\n"));
        assertTrue(before, before.contains("installed_utc: " + Instant.ofEpochMilli(installed) + "\n"));
        String after = JavaCrashReport.buildForExit(context, nativeCrashAt(installed + 500), "native crash", installed);
        assertTrue(after, after.contains("app_version: 584.0.0.51.56\nmorphe_version: same-release\n"));
        assertTrue(after, after.contains("app_version_code: 475817393\npatch_build: " + IdentityUtils.identity + "\n"));
        assertFalse(after, after.contains("installed_utc: "));
        String noInstallTime = JavaCrashReport.buildForExit(context, nativeCrashAt(installed + 500), "native crash", -1L);
        assertTrue(noInstallTime, noInstallTime.contains("app_version: unknown\nmorphe_version: unknown\n"));
        assertTrue(noInstallTime, noInstallTime.contains("app_version_code: unknown\npatch_build: unknown\n"));
        assertTrue(noInstallTime, noInstallTime.contains("installed_utc: unknown\n"));
    }

    private ApplicationExitInfo nativeCrashAt(long at) {
        return ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setProcessName(context.getPackageName())
                .setReason(ApplicationExitInfo.REASON_CRASH_NATIVE)
                .setTimestamp(at)
                .build();
    }

    @Test
    @Config(shadows = IdentityUtils.class)
    public void aLongVersionCodeIsMetadataRatherThanAContentId() {
        IdentityUtils.code = Long.MAX_VALUE;
        String report = JavaCrashReport.build(context, null, new IllegalStateException("post_id=" + Long.MAX_VALUE));
        assertTrue(report, report.contains("app_version_code: " + Long.MAX_VALUE + "\n"));
        assertFalse(report, report.contains("post_id=" + Long.MAX_VALUE));
    }

    @Test
    @Config(shadows = IdentityUtils.class)
    public void unavailableCrashIdentityIsExplicitAndDoesNotLoseTheTrace() {
        IdentityUtils.code = -1;
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        context.getApplicationInfo().nativeLibraryDir = null;
        try {
            for (String missing : new String[]{null, "", " "}) {
                IdentityUtils.identity = missing;
                String report = JavaCrashReport.build(context, null, new IllegalStateException("startup"));
                assertTrue(report, report.contains("app_version_code: unknown\n"));
                assertTrue(report, report.contains("abi: app unknown, process "));
                assertTrue(report, report.contains("patch_build: unknown\n"));
                assertTrue(report, report.contains("java.lang.IllegalStateException: startup"));
                assertTrue(report, report.contains("thread: unknown\n"));
            }
        } finally {
            context.getApplicationInfo().nativeLibraryDir = nativeDir;
        }
    }

    @Test
    public void aCrashIsInTheNextReportWithItsWholeTrace() {
        assertEquals("a crash was already kept", "", LogBufferManager.readCrashReport(context));
        Throwable crash = new IllegalStateException("outer", new NullPointerException("inner cause"));
        JavaCrashReport.save(new Thread("CombinedTP7"), crash);

        String kept = LogBufferManager.readCrashReport(context);
        assertTrue(kept, kept.contains("exception: java.lang.IllegalStateException\n"));
        assertTrue(kept, kept.contains("thread: CombinedTP7\n"));
        assertTrue("the cause was left out: " + kept,
                kept.contains("Caused by: java.lang.NullPointerException: inner cause"));
        assertTrue("the frames were left out: " + kept,
                kept.contains("at " + JavaCrashReportTest.class.getName() + ".aCrashIsInTheNextReportWithItsWholeTrace"));

        String report = LogBufferManager.buildExportText();
        assertTrue("the report has no crash section: " + report, report.contains("[LATEST JAVA CRASH]"));
        assertTrue(report, report.contains("Caused by: java.lang.NullPointerException: inner cause"));
    }

    @Test
    public void theSameCrashHandedBackIsKeptOnceAndALaterOneReplacesIt() {
        Throwable first = new IllegalStateException("the first");
        assertEquals("nothing was kept before it", "", JavaCrashReport.save(Thread.currentThread(), first).before);
        // A handler Facebook put in front of ours can hand the same crash back to the one under it.
        assertNull("the same crash was written again", JavaCrashReport.save(Thread.currentThread(), first));
        assertTrue(LogBufferManager.readCrashReport(context).contains("java.lang.IllegalStateException: the first"));

        JavaCrashReport.save(Thread.currentThread(), new IllegalArgumentException("the second"));
        String kept = LogBufferManager.readCrashReport(context);
        assertTrue(kept, kept.contains("java.lang.IllegalArgumentException: the second"));
        assertFalse("the earlier crash is still there: " + kept, kept.contains("the first"));
    }

    /**
     * An exception off the main thread that one of Facebook's handlers swallowed ended nothing, so
     * the report goes back to the crash it held. One on the main thread stays: Facebook freezes.
     */
    @Test
    public void aSwallowedExceptionOffTheMainThreadPutsTheEarlierCrashBack() throws Exception {
        LogBufferManager.persistCrashReport(context, "an earlier start's crash");
        Thread worker = new Thread("CombinedTP3");
        JavaCrashReport.Saved before = JavaCrashReport.save(worker, new IllegalStateException("swallowed"));
        assertTrue("the exception wasn't written first", LogBufferManager.readCrashReport(context).contains("swallowed"));
        JavaCrashReport.survived(worker, before);
        String kept = LogBufferManager.readCrashReport(context);
        assertTrue(kept, kept.contains("an earlier start's crash"));
        assertFalse(kept, kept.contains("swallowed"));

        JavaCrashReport.Saved main = JavaCrashReport.save(Thread.currentThread(), new IllegalStateException("on the main thread"));
        JavaCrashReport.survived(Thread.currentThread(), main);
        assertTrue("the control: on the main thread the trace stays",
                LogBufferManager.readCrashReport(context).contains("java.lang.IllegalStateException: on the main thread"));
    }

    /**
     * Another thread's crash saved while a worker's exception waits on its handlers is the one that
     * ends the app, so the worker living on leaves that crash in the report.
     */
    @Test
    public void aSwallowedWorkerLeavesANewerCrashInPlace() throws Exception {
        LogBufferManager.persistCrashReport(context, "an earlier start's crash");
        Thread worker = new Thread("CombinedTP3");
        JavaCrashReport.Saved swallowed = JavaCrashReport.save(worker, new IllegalStateException("swallowed"));
        Throwable fatal = new IllegalArgumentException("the fatal one");
        JavaCrashReport.save(new Thread("Background"), fatal);
        JavaCrashReport.survived(worker, swallowed);
        String kept = LogBufferManager.readCrashReport(context);
        assertTrue("the newer crash was pushed out: " + kept, kept.contains("java.lang.IllegalArgumentException: the fatal one"));
        assertFalse(kept, kept.contains("an earlier start's crash"));
        assertNull("the same crash handed back was written again", JavaCrashReport.save(new Thread("Background"), fatal));
    }

    /** Two workers' exceptions that both end nothing leave the report as it was, whichever lives on first. */
    @Test
    public void twoSwallowedWorkersLeaveTheEarlierCrash() throws Exception {
        for (boolean newerFirst : new boolean[]{false, true}) {
            JavaCrashReport.resetForTests();
            LogBufferManager.persistCrashReport(context, "an earlier start's crash");
            Thread one = new Thread("CombinedTP3");
            Thread two = new Thread("CombinedTP4");
            JavaCrashReport.Saved first = JavaCrashReport.save(one, new IllegalStateException("first swallowed"));
            JavaCrashReport.Saved second = JavaCrashReport.save(two, new IllegalStateException("second swallowed"));
            if (newerFirst) {
                JavaCrashReport.survived(two, second);
                JavaCrashReport.survived(one, first);
            } else {
                JavaCrashReport.survived(one, first);
                JavaCrashReport.survived(two, second);
            }
            String kept = LogBufferManager.readCrashReport(context);
            assertTrue("newer first " + newerFirst + ": " + kept, kept.contains("an earlier start's crash"));
            assertFalse("newer first " + newerFirst + ": " + kept, kept.contains("swallowed"));
        }
    }

    /**
     * Clear diagnostic data empties the report, and a worker's exception saved before the clear
     * that lives on after it leaves it empty. It used to write the earlier crash back. A crash
     * after the clear still takes the place.
     */
    @Test
    public void aClearHoldsWhenAWorkerLivesOnAfterIt() throws Exception {
        LogBufferManager.persistCrashReport(context, "an earlier start's crash");
        Thread worker = new Thread("CombinedTP3");
        JavaCrashReport.Saved swallowed = JavaCrashReport.save(worker, new IllegalStateException("swallowed"));
        LogBufferManager.clearLogBuffer();
        JavaCrashReport.survived(worker, swallowed);
        assertEquals("the worker wrote a cleared report back", "", LogBufferManager.readCrashReport(context));

        JavaCrashReport.save(new Thread("Background"), new IllegalArgumentException("after the clear"));
        assertTrue(LogBufferManager.readCrashReport(context).contains("java.lang.IllegalArgumentException: after the clear"));
    }

    /** Undo after the worker lived on puts back what the report would hold without the clear. */
    @Test
    public void undoAfterTheWorkerLivesOnPutsTheEarlierCrashBack() throws Exception {
        LogBufferManager.persistCrashReport(context, "an earlier start's crash");
        Thread worker = new Thread("CombinedTP3");
        JavaCrashReport.Saved swallowed = JavaCrashReport.save(worker, new IllegalStateException("swallowed"));
        LogBufferManager.clearLogBuffer();
        JavaCrashReport.survived(worker, swallowed);
        assertEquals(LogBufferManager.UndoResult.RESTORED, LogBufferManager.undoClear());
        String kept = LogBufferManager.readCrashReport(context);
        assertTrue(kept, kept.contains("an earlier start's crash"));
        assertFalse("Undo put the swallowed exception back: " + kept, kept.contains("swallowed"));
    }

    /** Undo before the worker lives on puts its exception back, and it then takes back as it would have. */
    @Test
    public void undoBeforeTheWorkerLivesOnLetsItTakeBackAsBefore() throws Exception {
        LogBufferManager.persistCrashReport(context, "an earlier start's crash");
        Thread worker = new Thread("CombinedTP3");
        JavaCrashReport.Saved swallowed = JavaCrashReport.save(worker, new IllegalStateException("swallowed"));
        LogBufferManager.clearLogBuffer();
        assertEquals(LogBufferManager.UndoResult.RESTORED, LogBufferManager.undoClear());
        assertTrue(LogBufferManager.readCrashReport(context).contains("swallowed"));
        JavaCrashReport.survived(worker, swallowed);
        String kept = LogBufferManager.readCrashReport(context);
        assertTrue(kept, kept.contains("an earlier start's crash"));
        assertFalse(kept, kept.contains("swallowed"));
    }

    /**
     * A crash saved after the clear keeps the place through Undo, and the worker saved before the
     * clear living on leaves it there.
     */
    @Test
    public void aCrashAfterTheClearOutlastsUndoAndTheOldWorker() throws Exception {
        LogBufferManager.persistCrashReport(context, "an earlier start's crash");
        Thread worker = new Thread("CombinedTP3");
        JavaCrashReport.Saved swallowed = JavaCrashReport.save(worker, new IllegalStateException("swallowed"));
        LogBufferManager.clearLogBuffer();
        JavaCrashReport.save(new Thread("Background"), new IllegalArgumentException("the fatal one"));
        LogBufferManager.undoClear();
        JavaCrashReport.survived(worker, swallowed);
        String kept = LogBufferManager.readCrashReport(context);
        assertTrue("the newer crash was pushed out: " + kept, kept.contains("java.lang.IllegalArgumentException: the fatal one"));
        assertFalse(kept, kept.contains("an earlier start's crash"));
        assertFalse(kept, kept.contains("swallowed"));
    }

    /**
     * Undo while a worker's exception saved after the clear holds the place puts the cleared report
     * in under it, so when that worker and the one saved before the clear both live on, whichever
     * goes first, the report ends as it was. Undo used to leave the cleared report with the clear,
     * and the report ended empty.
     */
    @Test
    public void undoUnderAWorkerSavedSinceEndsWithTheEarlierCrashWhenBothLiveOn() throws Exception {
        for (boolean newerFirst : new boolean[]{false, true}) {
            JavaCrashReport.resetForTests();
            LogBufferManager.persistCrashReport(context, "an earlier start's crash");
            Thread one = new Thread("CombinedTP3");
            Thread two = new Thread("CombinedTP4");
            JavaCrashReport.Saved first = JavaCrashReport.save(one, new IllegalStateException("before the clear"));
            LogBufferManager.clearLogBuffer();
            JavaCrashReport.Saved second = JavaCrashReport.save(two, new IllegalStateException("after the clear"));
            assertEquals(LogBufferManager.UndoResult.RESTORED, LogBufferManager.undoClear());
            assertTrue("newer first " + newerFirst + ": the crash since the clear lost its place",
                    LogBufferManager.readCrashReport(context).contains("after the clear"));
            if (newerFirst) {
                JavaCrashReport.survived(two, second);
                assertTrue("the worker saved before the clear isn't back under it",
                        LogBufferManager.readCrashReport(context).contains("before the clear"));
                JavaCrashReport.survived(one, first);
            } else {
                JavaCrashReport.survived(one, first);
                JavaCrashReport.survived(two, second);
            }
            String kept = LogBufferManager.readCrashReport(context);
            assertTrue("newer first " + newerFirst + ": " + kept, kept.contains("an earlier start's crash"));
            assertFalse("newer first " + newerFirst + ": " + kept, kept.contains("the clear"));
        }
    }

    /**
     * Undo tapped again after a later step of the first one failed leaves the report as the first
     * put it back. The second tap took the report the first had restored for what the waiting
     * worker wrote over, so the worker living on left its own exception in place of the earlier crash.
     */
    @Test
    public void undoAgainAfterAPartlyFailedOneChangesNothing() throws Exception {
        LogBufferManager.persistCrashReport(context, "an earlier start's crash");
        Thread worker = new Thread("CombinedTP3");
        JavaCrashReport.Saved swallowed = JavaCrashReport.save(worker, new IllegalStateException("swallowed"));
        // What Clear diagnostic data does to the Java crash, and the first Undo's Java crash step.
        JavaCrashReport.Cleared cleared = JavaCrashReport.clear(context);
        LogBufferManager.persistCrashReport(context, "");
        JavaCrashReport.putBack(context, cleared);
        assertTrue(LogBufferManager.readCrashReport(context).contains("swallowed"));
        // A later step of that Undo failed, so the next tap runs it again.
        JavaCrashReport.putBack(context, cleared);
        JavaCrashReport.survived(worker, swallowed);
        String kept = LogBufferManager.readCrashReport(context);
        assertTrue(kept, kept.contains("an earlier start's crash"));
        assertFalse("the second Undo left the swallowed exception in: " + kept, kept.contains("swallowed"));
    }

    @Test
    public void nothingToKeepLeavesTheNextCrashItsPlace() {
        JavaCrashReport.save(Thread.currentThread(), null);
        assertEquals("", LogBufferManager.readCrashReport(context));

        JavaCrashReport.save(null, new IllegalStateException("after"));
        String kept = LogBufferManager.readCrashReport(context);
        assertTrue(kept, kept.contains("java.lang.IllegalStateException: after"));
        assertTrue(kept, kept.contains("thread: unknown\n"));
    }
}
