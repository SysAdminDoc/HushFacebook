/*
 * Modified for Hushfacebook (Facebook), 2026.
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.shared.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.SettingsContextRule;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30)
public class LogBufferManagerExportTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Test public void noExitOnRecordMeansNoLastExitSection() {
        // Android has kept no exit for this process yet, so the section has to be absent rather
        // than empty or guessed at.
        app.morphe.extension.shared.Utils.setContext(RuntimeEnvironment.getApplication());
        app.morphe.extension.shared.settings.BaseSettings.DEBUG_LOG_FILTERS.save("all");
        app.morphe.extension.shared.diagnostics.HookStatus.clear();
        LogBufferManager.clearLogBuffer();
        app.morphe.extension.shared.diagnostics.HookStatus.missingViewId("comments", "jlk");

        String report = LogBufferManager.buildExportText();
        assertNotEquals("nothing was reported at all", "", report);
        assertTrue("the report claimed to know why the process went away: " + report,
                report.indexOf("[LAST EXIT]") < 0);
    }

    @Test
    public void theReportSaysWhyTheProcessWentAwayLastTime() throws Exception {
        // A Java crash handler sees none of the ways the system ends an app. Android 17 kills one
        // that goes over a RAM-proportional limit and leaves only this behind.
        app.morphe.extension.shared.Utils.setContext(RuntimeEnvironment.getApplication());
        app.morphe.extension.shared.settings.BaseSettings.DEBUG_LOG_FILTERS.save("all");
        app.morphe.extension.shared.diagnostics.HookStatus.clear();
        LogBufferManager.clearLogBuffer();

        android.app.ApplicationExitInfo exit =
                org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setReason(13)
                .setTimestamp(1757260800000L)
                .setProcessName(RuntimeEnvironment.getApplication().getPackageName())
                .setDescription("MemoryLimiter:AnonSwap")
                .build();

        // Facebook runs several processes, and the most recent record is routinely a background
        // helper the system reaped. Reporting that as why the app went away is worse than saying
        // nothing, so the newer of the two here must be passed over.
        android.app.ApplicationExitInfo helper = org.robolectric.shadows.ShadowActivityManager
                .ApplicationExitInfoBuilder.newBuilder()
                .setReason(10)
                .setTimestamp(1757260900000L)
                .setProcessName(RuntimeEnvironment.getApplication().getPackageName() + ":push")
                .setDescription("a background helper nobody asked about")
                .build();
        android.app.ActivityManager manager = (android.app.ActivityManager) RuntimeEnvironment
                .getApplication().getSystemService(Context.ACTIVITY_SERVICE);
        org.robolectric.Shadows.shadowOf(manager).addApplicationExitInfo(exit);
        org.robolectric.Shadows.shadowOf(manager).addApplicationExitInfo(helper);

        // Every report asked for carries the line, a healthy one with nothing else found included.
        String report = LogBufferManager.buildExportText();
        assertTrue("the report does not say why the process went away: " + report,
                report.contains("[LAST EXIT]"));
        assertTrue("the reason was not named: " + report, report.contains("reason: OTHER"));
        assertTrue("the description the kill carried was dropped: " + report,
                report.contains("MemoryLimiter:AnonSwap"));
        assertTrue("a background helper's exit was reported as the app's: " + report,
                report.indexOf("a background helper nobody asked about") < 0);
        java.text.SimpleDateFormat utc =
                new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
        utc.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String when = utc.format(new java.util.Date(1757260800000L));
        assertTrue("the time it happened was left out or in another format: " + report,
                report.contains(when));
    }

    /** The report for a last exit Android recorded under [reason]. */
    private static String reportAfterAnExitFor(int reason) {
        app.morphe.extension.shared.Utils.setContext(RuntimeEnvironment.getApplication());
        app.morphe.extension.shared.settings.BaseSettings.DEBUG_LOG_FILTERS.save("all");
        app.morphe.extension.shared.diagnostics.HookStatus.clear();
        LogBufferManager.clearLogBuffer();
        android.app.ActivityManager manager = (android.app.ActivityManager) RuntimeEnvironment
                .getApplication().getSystemService(Context.ACTIVITY_SERVICE);
        org.robolectric.Shadows.shadowOf(manager).addApplicationExitInfo(
                org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                        .setReason(reason)
                        .setTimestamp(1757260800000L)
                        .setProcessName(RuntimeEnvironment.getApplication().getPackageName())
                        .build());
        return LogBufferManager.buildExportText();
    }

    /** The 37.2 SDK's REASON_MEMORY_LIMITER, 17, goes by its name (#528). */
    @Test
    public void android17sMemoryLimiterReasonIsNamed() {
        String report = reportAfterAnExitFor(17);
        assertTrue("reason 17 went unnamed: " + report, report.contains("reason: MEMORY_LIMITER\n"));
    }

    /** The 37.2 SDK's REASON_ANOMALY, 18, goes by its name. */
    @Test
    public void android17sAnomalyReasonIsNamed() {
        String report = reportAfterAnExitFor(18);
        assertTrue("reason 18 went unnamed: " + report, report.contains("reason: ANOMALY\n"));
    }

    /**
     * Facebook runs on Android 11 and newer, where the report goes through MediaStore into
     * Download/Hushfacebook, next to the crash files. The path handed back is the name MediaStore
     * gave the file, which differs from the one asked for when a file of that name is already there.
     */
    @Test public void repeatedExportsEachGetTheirOwnDownloadsEntry() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Downloads downloads = Robolectric.setupContentProvider(Downloads.class, MediaStore.AUTHORITY);
        ByteArrayOutputStream firstBody = new ByteArrayOutputStream();
        ByteArrayOutputStream secondBody = new ByteArrayOutputStream();
        ShadowContentResolver resolver = Shadows.shadowOf(context.getContentResolver());
        resolver.registerOutputStream(downloads.uriFor(1), firstBody);
        resolver.registerOutputStream(downloads.uriFor(2), secondBody);

        String report = "MORPHE DIAGNOSTIC REPORT\nschema: 1\n";
        String first = LogBufferManager.writeToFile(context, report);
        String second = LogBufferManager.writeToFile(context, report);

        String folder = Environment.DIRECTORY_DOWNLOADS + "/Hushfacebook/";
        assertTrue(first, first.startsWith(folder + "morphe-diagnostics-") && first.endsWith(".txt"));
        assertTrue(second, second.startsWith(folder + "morphe-diagnostics-") && second.endsWith(".txt"));
        assertNotEquals(first, second);
        assertEquals(folder + downloads.row(1).getAsString(MediaStore.MediaColumns.DISPLAY_NAME), first);
        assertEquals(folder + downloads.row(2).getAsString(MediaStore.MediaColumns.DISPLAY_NAME), second);
        for (int id = 1; id <= 2; id++) {
            assertEquals("entry " + id + " was left pending", Integer.valueOf(0),
                    downloads.row(id).getAsInteger(MediaStore.MediaColumns.IS_PENDING));
            assertEquals(folder,
                    downloads.row(id).getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
        }
        assertEquals(report, firstBody.toString(StandardCharsets.UTF_8.name()));
        assertEquals(report, secondBody.toString(StandardCharsets.UTF_8.name()));
    }

    /**
     * The file's place is a value set into a sentence, so the toast isolates it: a right-to-left
     * sentence then keeps "Download/Hushfacebook/..." in the order it was written.
     */
    @Test public void theSavedPathIsIsolatedInItsToast() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Downloads downloads = Robolectric.setupContentProvider(Downloads.class, MediaStore.AUTHORITY);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(downloads.uriFor(1), new ByteArrayOutputStream());
        app.morphe.extension.shared.Utils.setContext(context);
        app.morphe.extension.shared.settings.BaseSettings.DEBUG_LOG_FILTERS.save("all");
        app.morphe.extension.shared.diagnostics.HookStatus.clear();
        LogBufferManager.clearLogBuffer();
        app.morphe.extension.shared.diagnostics.HookStatus.missingViewId("comments", "jlk");
        org.robolectric.shadows.ShadowToast.reset();

        LogBufferManager.exportToFile();
        String toast = null;
        long until = System.currentTimeMillis() + 20_000;
        while (toast == null && System.currentTimeMillis() < until) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            toast = org.robolectric.shadows.ShadowToast.getTextOfLatestToast();
            if (toast == null) Thread.sleep(50);
        }

        assertNotNull("the export said nothing", toast);
        String saved = Environment.DIRECTORY_DOWNLOADS + "/Hushfacebook/" + downloads.row(1).getAsString(MediaStore.MediaColumns.DISPLAY_NAME);
        assertEquals("Full report saved to " + app.morphe.extension.shared.L10n.isolate(saved), toast);
    }

    /**
     * Both exports end to end, with the redactor's credential corpus in every place a report takes
     * text from: buffered events, the saved crash and a section the bundle registers. No synthetic
     * secret reaches the clipboard or the saved file, and the build lines, timestamps and stack
     * frames a maintainer reads the report for arrive intact.
     */
    @Test public void neitherExportCarriesASyntheticCredential() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        app.morphe.extension.shared.Utils.setContext(context);
        app.morphe.extension.shared.settings.BaseSettings.DEBUG_LOG_FILTERS.save("all");
        app.morphe.extension.shared.diagnostics.HookStatus.clear();
        LogBufferManager.clearLogBuffer();
        String[][] corpus = app.morphe.extension.shared.diagnostics.DiagnosticRedactorTest.CREDENTIAL_CORPUS;
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder crash = new StringBuilder("complete: true\njava.io.IOException: 401\n");
        for (String[] row : corpus) {
            LogBufferManager.appendEvent(app.morphe.extension.shared.diagnostics.DiagnosticCategory.DOWNLOADS,
                    "Probe", "INFO", row[0]);
            lines.add(row[0]);
            crash.append(row[0]).append('\n');
        }
        String frame = "\tat app.morphe.extension.facebook.download.Downloader.connect(Downloader.java:120)";
        crash.append(frame).append('\n');
        LogBufferManager.persistCrashReport(context, crash.toString());
        LogBufferManager.registerReportSection(new LogBufferManager.ReportSection() {
            @Override public String title() {
                return "PROBE";
            }

            @Override public java.util.List<String> lines() {
                return lines;
            }
        });
        // The redactor removes Meta hosts; the manifest exception must not widen other sections.
        lines.add("ordinary.facebook.com -> selected");
        LogBufferManager.registerReportSection(new LogBufferManager.ReportSection() {
            @Override public String title() { return "MANIFEST HOST PROBE"; }
            @Override public java.util.List<String> lines() {
                return java.util.Arrays.asList("declared.facebook.com -> selected", "unlisted.facebook.com -> none",
                        "https://declared.facebook.com/private?account_id=private-account-probe -> selected");
            }
            @Override public java.util.Set<String> declaredDomainHosts(java.util.List<String> snapshot) {
                return java.util.Collections.singleton("declared.facebook.com");
            }
        });
        try {
            android.content.ClipboardManager clipboard = context.getSystemService(android.content.ClipboardManager.class);
            LogBufferManager.exportToClipboard();
            app.morphe.extension.shared.Utils.awaitBackgroundTasksForTests();
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            String copied = String.valueOf(clipboard.getPrimaryClip().getItemAt(0).getText());

            Downloads downloads = Robolectric.setupContentProvider(Downloads.class, MediaStore.AUTHORITY);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            Shadows.shadowOf(context.getContentResolver()).registerOutputStream(downloads.uriFor(1), body);
            LogBufferManager.exportToFile();
            app.morphe.extension.shared.Utils.awaitBackgroundTasksForTests();
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            String saved = body.toString(StandardCharsets.UTF_8.name());

            String[][] exports = {{"clipboard", copied}, {"file", saved}};
            for (String[] export : exports) {
                String where = export[0];
                String text = export[1];
                assertTrue(where + " holds no report: " + text, text.startsWith("MORPHE DIAGNOSTIC REPORT\n"));
                assertTrue(where + " lost the declared domain state", text.contains("declared.facebook.com -> selected"));
                assertTrue(where + " exempted an ordinary section's host", !text.contains("ordinary.facebook.com"));
                assertTrue(where + " exempted an undeclared host", !text.contains("unlisted.facebook.com"));
                assertTrue(where + " leaked a visited path or account", !text.contains("/private") && !text.contains("private-account-probe"));
                for (String[] row : corpus) {
                    for (int i = 1; i < row.length; i++) {
                        assertTrue(row[i] + " reached the " + where + ":\n" + text, text.indexOf(row[i]) < 0);
                    }
                }
                assertTrue(where + " lost the app line: " + text,
                        text.contains("\napp: " + context.getPackageName() + " "));
                assertTrue(where + " lost the abi line: " + text, text.contains("\nabi: app "));
                assertTrue(where + " lost the report time: " + text,
                        java.util.regex.Pattern.compile("\ngenerated_utc: \\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z\n")
                                .matcher(text).find());
                assertTrue(where + " lost the event times: " + text,
                        java.util.regex.Pattern.compile("\ndownloads \\| \\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z \\| ")
                                .matcher(text).find());
                assertTrue(where + " lost the stack frame: " + text, text.contains(frame + "\n"));
                assertTrue(where + " lost the crash heading: " + text, text.contains("[LATEST JAVA CRASH]"));
                assertTrue(where + " lost the section: " + text, text.contains("[PROBE]"));
            }
        } finally {
            LogBufferManager.clearReportSectionsForTests();
            LogBufferManager.clearLogBuffer();
        }
    }

    /** MediaStore's Downloads table, as much of it as an export touches. */
    public static final class Downloads extends ContentProvider {
        private final Map<Long, ContentValues> rows = new HashMap<>();
        private long nextId = 1;

        Uri uriFor(long id) {
            return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
        }

        ContentValues row(long id) {
            ContentValues row = rows.get(id);
            assertTrue("no Downloads entry " + id, row != null);
            return row;
        }

        @Override public boolean onCreate() {
            return true;
        }

        @Override public Uri insert(Uri uri, ContentValues values) {
            ContentValues row = new ContentValues(values);
            // MediaStore keeps names in a folder unique by numbering the newcomer.
            String name = row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME);
            for (ContentValues other : rows.values()) {
                if (name.equals(other.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))) {
                    row.put(MediaStore.MediaColumns.DISPLAY_NAME, name.replace(".txt", " (1).txt"));
                }
            }
            long id = nextId++;
            rows.put(id, row);
            return uriFor(id);
        }

        @Override public Cursor query(Uri uri, String[] projection, String selection,
                String[] selectionArgs, String sortOrder) {
            MatrixCursor cursor = new MatrixCursor(projection);
            ContentValues row = rows.get(ContentUris.parseId(uri));
            if (row != null) {
                Object[] values = new Object[projection.length];
                for (int i = 0; i < projection.length; i++) values[i] = row.get(projection[i]);
                cursor.addRow(values);
            }
            return cursor;
        }

        @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            ContentValues row = rows.get(ContentUris.parseId(uri));
            if (row == null) return 0;
            row.putAll(values);
            return 1;
        }

        @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
            return rows.remove(ContentUris.parseId(uri)) == null ? 0 : 1;
        }

        @Override public String getType(Uri uri) {
            return "text/plain";
        }
    }
}
