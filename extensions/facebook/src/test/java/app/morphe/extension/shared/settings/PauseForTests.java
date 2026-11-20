/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.shared.settings;

/**
 * Pauses and resumes the process for a test in another package. {@link HushfacebookPause} keeps
 * its test hooks package-private, and the hooks a pause has to reach live in the Facebook packages.
 */
public final class PauseForTests {
    private PauseForTests() {
    }

    public static void pause(HushfacebookPause.Reason why) {
        HushfacebookPause.pauseForTests(why);
    }

    public static void resume() {
        HushfacebookPause.pauseForTests(HushfacebookPause.Reason.NONE);
    }

    /** Runs [probe] inside the next first-of-process pause decision, or nothing when null. */
    public static void whileDeciding(Runnable probe) {
        HushfacebookPause.whileDecidingForTests = probe;
    }

    /**
     * A new process starting, as far as the pause module goes. The deaths are left for the test
     * to file ({@link #fileLastDeaths}), and whatever the last start still had running in the
     * background finishes first, so it can't write into this one.
     */
    public static void newStart(android.content.Context context) {
        settle();
        HushfacebookPause.resetForTests();
        HushfacebookPause.onProcessStart(context);
    }

    /** A new process starting that files the last deaths by itself, as Facebook's does. */
    public static void newStartFilingDeaths(android.content.Context context) {
        settle();
        HushfacebookPause.resetForTests();
        HushfacebookPause.fileDeathsAtStart = true;
        try {
            HushfacebookPause.onProcessStart(context);
        } finally {
            HushfacebookPause.fileDeathsAtStart = false;
        }
    }

    private static void settle() {
        try {
            app.morphe.extension.shared.Utils.awaitBackgroundTasksForTests();
        } catch (Exception stuck) {
            throw new IllegalStateException("The last start's background work never finished", stuck);
        }
    }

    /** What a start's background job does, here and now: files the deaths Android recorded since the mark. */
    public static void fileLastDeaths() {
        HushfacebookPause.fileLastDeaths();
    }

    /** The mark that says which of Android's exit records are filed already. */
    public static java.io.File deathsFiledMark(android.content.Context context) {
        return new java.io.File(context.getFilesDir(), HushfacebookPause.DEATHS_FILED_NAME);
    }
}
