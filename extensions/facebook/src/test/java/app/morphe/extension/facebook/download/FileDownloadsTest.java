/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.DownloadManager;
import android.net.Uri;
import android.os.Environment;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Save files to Downloads (#590): the hook Facebook's file download request goes through, right
 * after it's made. On, the request points at the public Downloads folder under the file's own name.
 * Off, paused, before settings are ready, or on Android 9 without the storage permission, the
 * request is left as Facebook made it, and a failure in the hook is recorded and swallowed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FileDownloadsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** A file attachment's address on Facebook's file host: the name it was posted with comes last. */
    private static final Uri FILE = Uri.parse(
            "https://cdn.fbsbx.com/v/t59.2708-21/123456789_n.pdf/Quarterly%20report.pdf?_nc_cat=104&dl=1");

    @After
    public void restore() {
        HookStatus.clear();
        PauseForTests.resume();
        Settings.SAVE_FILES_TO_DOWNLOADS.resetToDefault();
    }

    private static Uri destinationOf(DownloadManager.Request request) {
        return shadowOf(request).getDestination();
    }

    private static Uri downloads(String name) {
        return Uri.withAppendedPath(Uri.fromFile(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)), name);
    }

    /** Facebook's request for [uri] after the hook. */
    private static DownloadManager.Request hooked(Uri uri) {
        DownloadManager.Request request = new DownloadManager.Request(uri);
        FileDownloads.toDownloads(request, uri);
        return request;
    }

    private static void assertNothingMissing() {
        List<String> missing = HookStatus.missing(FamilyNames.FILE_DOWNLOADS);
        assertTrue(missing.toString(), missing.isEmpty());
    }

    @Test
    public void onByDefaultAFileGoesToDownloadsUnderTheNameItWasPostedWith() {
        assertTrue(Settings.SAVE_FILES_TO_DOWNLOADS.get());
        Uri destination = destinationOf(hooked(FILE));
        assertEquals(downloads("Quarterly report.pdf"), destination);
        assertEquals("Quarterly report.pdf", destination.getLastPathSegment());
        assertNothingMissing();
    }

    @Test
    public void aNameAFatFolderCantHoldIsCleaned() {
        Uri file = Uri.parse("https://cdn.fbsbx.com/v/t59.2708-21/1_n.txt/notes%3C1%3E%7Cdraft.txt?dl=1");
        assertEquals(downloads("notes_1_draft.txt"), destinationOf(hooked(file)));
        Uri climbing = Uri.parse("https://cdn.fbsbx.com/v/t59.2708-21/1_n.txt/..%2F..%2Fpasswd");
        assertEquals(downloads("passwd"), destinationOf(hooked(climbing)));
    }

    @Test
    public void anAddressWithNoNameGetsTheFallback() {
        assertEquals(downloads(FileDownloads.FALLBACK), destinationOf(hooked(Uri.parse("https://cdn.fbsbx.com"))));
    }

    @Test
    public void offTheRequestIsLeftAsFacebookMadeIt() {
        Settings.SAVE_FILES_TO_DOWNLOADS.save(false);
        assertNull(destinationOf(hooked(FILE)));
        assertNothingMissing();
    }

    @Test
    public void pausedTheRequestIsLeftAsFacebookMadeIt() {
        for (HushfacebookPause.Reason why : HushfacebookPause.Reason.values()) {
            if (why == HushfacebookPause.Reason.NONE) continue;
            PauseForTests.pause(why);
            assertNull(why.name(), destinationOf(hooked(FILE)));
            PauseForTests.resume();
        }
        assertEquals(downloads("Quarterly report.pdf"), destinationOf(hooked(FILE)));
    }

    @Test
    public void beforeSettingsAreReadyTheRequestIsLeftAsFacebookMadeIt() {
        SettingsContextRule.withoutContext(() -> assertNull("no context", destinationOf(hooked(FILE))));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertNull("pause undecided", destinationOf(hooked(FILE))));
        assertEquals(downloads("Quarterly report.pdf"), destinationOf(hooked(FILE)));
    }

    @Test
    public void nothingToPointIsLeftAlone() {
        FileDownloads.toDownloads(null, FILE);
        DownloadManager.Request request = new DownloadManager.Request(FILE);
        FileDownloads.toDownloads(request, null);
        assertNull(destinationOf(request));
        assertNothingMissing();
    }

    @Test
    public void aFailureInTheHookIsRecordedAndTheDownloadGoesOn() {
        DownloadManager.Request refusing = new DownloadManager.Request(FILE) {
            @Override
            public DownloadManager.Request setDestinationInExternalPublicDir(String dirType, String subPath) {
                throw new IllegalStateException("no public folder");
            }
        };
        FileDownloads.toDownloads(refusing, FILE);
        assertNull(destinationOf(refusing));
        List<String> missing = HookStatus.missing(FamilyNames.FILE_DOWNLOADS);
        assertEquals(1, missing.size());
        assertTrue(missing.get(0), missing.get(0).contains("file download"));
        assertTrue(missing.get(0), missing.get(0).contains(IllegalStateException.class.getName()));
    }

    /** Before Android 10, DownloadManager refuses a public folder to an app without the storage permission. */
    @Test
    @Config(sdk = 28)
    public void onAndroid9ItTakesTheStoragePermission() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        assertNull("without the permission", destinationOf(hooked(FILE)));
        assertNothingMissing();
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        assertEquals(downloads("Quarterly report.pdf"), destinationOf(hooked(FILE)));
    }
}
