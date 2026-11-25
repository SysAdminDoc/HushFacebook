/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.app.DownloadManager;
import android.net.Uri;

import org.robolectric.Shadows;

/** Save files to Downloads' hook, for tests in any package. */
public final class FileDownloadsForTests {
    private FileDownloadsForTests() {
    }

    /** A file attachment's address on Facebook's file host. */
    public static final String FILE = "https://cdn.fbsbx.com/v/t59.2708-21/123456789_n.pdf/Quarterly%20report.pdf?dl=1";

    /** A file download's request handed to the hook the way Facebook makes it: whether it was moved to Downloads. */
    public static boolean movesAFile() {
        Uri uri = Uri.parse(FILE);
        DownloadManager.Request request = new DownloadManager.Request(uri);
        FileDownloads.toDownloads(request, uri);
        return Shadows.shadowOf(request).getDestination() != null;
    }
}
