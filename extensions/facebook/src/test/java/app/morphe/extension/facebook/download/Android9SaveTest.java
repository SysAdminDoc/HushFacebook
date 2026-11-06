/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.KeyguardManager;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.facebook.misc.AppLock;

/**
 * Facebook's Android 9 build (#102): MediaStore there has no folder column and no pending rows, so
 * a save writes its file into the public folder and adds the row once the file is whole.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class Android9SaveTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private Application context;
    private Rows rows;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        rows = Robolectric.setupContentProvider(Rows.class, MediaStore.AUTHORITY);
    }

    @Test public void downloadsAndCardsFallBackToWhatAndroid9Has() {
        Uri files = MediaStore.Files.getContentUri("external");
        assertEquals(files, SaveTo.DOWNLOAD.collection(true));
        assertEquals(files, SaveTo.DOWNLOAD.collection(false, "1a2b-3c4d"));
        assertEquals(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, SaveTo.MOVIES_AND_PICTURES.collection(true, "1a2b-3c4d"));
        assertTrue(SaveStorage.cards(context).isEmpty());
    }

    @Test public void aTakenNameIsNumberedAsMediaStoreWould() throws IOException {
        File folder = temp.newFolder();
        assertEquals("clip.mp4", MediaStoreWriter.free(folder, "clip.mp4").getName());
        assertTrue(new File(folder, "clip.mp4").createNewFile());
        assertTrue(new File(folder, "clip (1).mp4").createNewFile());
        assertEquals("clip (2).mp4", MediaStoreWriter.free(folder, "clip.mp4").getName());
        for (int n = 2; n <= 31; n++) assertTrue(new File(folder, "clip (" + n + ").mp4").createNewFile());
        assertNull(MediaStoreWriter.free(folder, "clip.mp4"));
    }

    @Test public void withoutStorageAccessTheSaveFailsAndLeavesNothing() {
        shadowOf(context).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        MediaStoreWriter writer = new MediaStoreWriter(context, true);
        assertThrows(IOException.class, () -> writer.open("video/mp4"));
        writer.abandon();
        assertNull(writer.publishedUri());
        assertTrue(rows.added.isEmpty());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void aGrantedSaveWritesTheFileThenAddsARowNamingIt() throws IOException {
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        MediaStoreWriter writer = new MediaStoreWriter(context, true);
        writer.open("video/mp4").write(new byte[]{1, 2, 3});
        assertTrue("no row before the file is whole", rows.added.isEmpty());
        writer.commit();

        Uri published = writer.publishedUri();
        assertNotNull(published);
        assertEquals("video/mp4", writer.publishedMime());
        assertEquals(1, rows.added.size());
        ContentValues row = rows.added.get(0);
        File saved = new File(row.getAsString(MediaStore.MediaColumns.DATA));
        try {
            assertEquals(new File(Environment.getExternalStorageDirectory(), writer.savedLocation()), saved.getParentFile());
            assertEquals(saved.getName(), row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
            assertEquals(Long.valueOf(3), row.getAsLong(MediaStore.MediaColumns.SIZE));
            assertFalse("Android 9 has no pending column", row.containsKey(MediaStore.MediaColumns.IS_PENDING));
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(saved.toPath()));
        } finally {
            assertTrue(saved.delete());
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    public void anAbandonedSaveDeletesItsHalfWrittenFile() throws IOException {
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        MediaStoreWriter writer = new MediaStoreWriter(context, true);
        writer.open("video/mp4").write(1);
        File folder = new File(Environment.getExternalStorageDirectory(), writer.savedLocation());
        File[] during = folder.listFiles();
        assertNotNull(during);
        assertEquals(1, during.length);

        writer.abandon();
        File[] after = folder.listFiles();
        assertNotNull(after);
        assertEquals(0, after.length);
        assertTrue(rows.added.isEmpty());
        assertNull(writer.publishedUri());
    }

    @Test public void appLockStaysOffOnAndroid9EvenWithAScreenLock() {
        shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceSecure(true);
        assertFalse(AppLock.canLock(context));
    }

    /** MediaStore as far as a save asks it: rows added, nothing found. */
    public static final class Rows extends ContentProvider {
        final List<ContentValues> added = new ArrayList<>();

        @Override public boolean onCreate() {
            return true;
        }

        @Override public Uri insert(Uri uri, ContentValues values) {
            added.add(new ContentValues(values));
            return ContentUris.withAppendedId(uri, added.size());
        }

        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            return new MatrixCursor(projection == null ? new String[]{MediaStore.MediaColumns._ID} : projection);
        }

        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
            return 0;
        }

        @Override public int delete(Uri uri, String selection, String[] args) {
            return 0;
        }

        @Override public String getType(Uri uri) {
            return null;
        }
    }
}
