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

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

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
import org.robolectric.shadows.ShadowToast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Search photo (#245, #248): the tap fetches the photo from Meta's servers only, into its own
 * folder, the provider serves that one file for reading and nothing else, and the chooser grants
 * the picked app a read of that one address.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30)
public class PhotoSearchTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String COULD_NOT_GET = "Couldn't get this photo. Try again in a moment.";

    private LocalServer server;
    private Context context;

    @Before
    public void setUp() throws IOException {
        server = new LocalServer();
        int port = server.port();
        context = RuntimeEnvironment.getApplication();
        // Every Meta name answers a private address, so only this test's own server is fetched.
        MediaDownload.policyForTests = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") }) {
            @Override
            Refusal refusal(URL url) {
                if (url.getHost().equals("127.0.0.1") && url.getPort() == port) return null;
                return super.refusal(url);
            }
        };
        Settings.DOWNLOAD_PHOTOS.save(true);
        Settings.POST_MENU_PHOTO_SEARCH.save(true);
        ShadowToast.reset();
    }

    @After
    public void tearDown() throws IOException {
        server.close();
        MediaDownload.policyForTests = null;
        PauseForTests.resume();
        Settings.DOWNLOAD_PHOTOS.resetToDefault();
        Settings.POST_MENU_PHOTO_SEARCH.resetToDefault();
    }

    private static byte[] picture(int... head) {
        byte[] body = new byte[4096];
        for (int i = 0; i < head.length; i++) body[i] = (byte) head[i];
        for (int i = head.length; i < body.length; i++) body[i] = (byte) (i * 7);
        return body;
    }

    private static byte[] picture(String head) {
        byte[] ascii = head.getBytes(StandardCharsets.ISO_8859_1);
        int[] each = new int[ascii.length];
        for (int i = 0; i < ascii.length; i++) each[i] = ascii[i] & 0xff;
        return picture(each);
    }

    private static final byte[] JPEG = picture(0xff, 0xd8, 0xff, 0xe0);

    private static File write(File file, byte[] body) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(body);
        }
        return file;
    }

    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        for (int n; (n = in.read(chunk)) >= 0; ) all.write(chunk, 0, n);
        return all.toByteArray();
    }

    private PhotoSearchFiles provider() {
        return Robolectric.setupContentProvider(PhotoSearchFiles.class, context.getPackageName() + PhotoSearch.AUTHORITY_SUFFIX);
    }

    /** The photo arrives whole, typed, named, for reading only, and the next search drops it. */
    @Test
    public void aFetchedPhotoIsServedToThePickedAppForReadingOnly() throws IOException {
        server.serve("/photo.jpg", "image/jpeg", JPEG);
        PhotoSearch.Fetched first = PhotoSearch.fetch(context, server.origin() + "/photo.jpg");
        assertNotNull("the photo wasn't fetched", first);
        assertEquals(PhotoFormat.JPEG, first.mime);

        PhotoSearchFiles files = provider();
        Uri uri = PhotoSearch.uri(context, first.number);
        assertEquals("content://" + context.getPackageName() + ".hushfacebook.photosearch/photo/" + first.number,
                uri.toString());
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(files.openFile(uri, "r"))) {
            assertArrayEquals(JPEG, read(in));
        }
        assertEquals(PhotoFormat.JPEG, files.getType(uri));
        try (Cursor row = files.query(uri, null, null, null, null)) {
            assertNotNull(row);
            assertTrue(row.moveToFirst());
            assertEquals(PhotoSearch.PREFIX + first.number + ".jpg",
                    row.getString(row.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)));
            assertEquals(JPEG.length, row.getLong(row.getColumnIndexOrThrow(OpenableColumns.SIZE)));
        }
        for (String mode : new String[] { "w", "rw", "wt", "rwt" }) {
            assertThrows("opened for " + mode, FileNotFoundException.class, () -> files.openFile(uri, mode));
        }
        assertNull("the provider took an insert", files.insert(uri, null));
        assertEquals(0, files.delete(uri, null, null));

        PhotoSearch.Fetched second = PhotoSearch.fetch(context, server.origin() + "/photo.jpg");
        assertNotNull(second);
        assertTrue("the second search reused the first one's number", second.number > first.number);
        assertFalse("the first search's photo stayed", PhotoSearch.file(context, first.number).exists());
        assertNull("the first search's address still served", PhotoSearchFiles.photoOf(context, uri));
        assertEquals(1, PhotoSearch.folder(context).listFiles().length);
    }

    @Test
    public void everyOtherAddressIsRefused() throws IOException {
        File folder = PhotoSearch.folder(context);
        assertNotNull(folder);
        write(PhotoSearch.file(context, 77), JPEG);
        String authority = "content://" + context.getPackageName() + PhotoSearch.AUTHORITY_SUFFIX;
        assertNotNull("the photo itself", PhotoSearchFiles.photoOf(context, Uri.parse(authority + "/photo/77")));

        String[] refused = {
            authority + "/photo/77?x=1",
            authority + "/photo/77#top",
            authority + "/photo",
            authority + "/photo/77/more",
            authority + "/other/77",
            authority + "/photo/7a",
            authority + "/photo/-77",
            authority + "/photo/0",
            authority + "/photo/1111111111111111111",
            authority + "/photo/78",
            authority + "/photo/..%2F..%2Fshared_prefs",
            "file://" + PhotoSearch.file(context, 77).getPath(),
        };
        for (String address : refused) {
            assertNull(address, PhotoSearchFiles.photoOf(context, Uri.parse(address)));
        }
        assertNull(PhotoSearchFiles.photoOf(null, Uri.parse(authority + "/photo/77")));
        assertNull(PhotoSearchFiles.photoOf(context, null));
        assertNull("a refused address got a row", provider().query(Uri.parse(authority + "/photo/78"), null, null, null, null));
        assertThrows(FileNotFoundException.class, () -> provider().openFile(Uri.parse(authority + "/photo/78"), "r"));
    }

    @Test
    public void theTypeComesFromThePictureItself() throws IOException {
        File folder = PhotoSearch.folder(context);
        assertEquals("image/jpeg", PhotoSearchFiles.typeOf(write(new File(folder, "a"), JPEG)));
        assertEquals("image/png", PhotoSearchFiles.typeOf(write(new File(folder, "b"),
                picture(0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a))));
        assertEquals("image/webp", PhotoSearchFiles.typeOf(write(new File(folder, "c"), picture("RIFF\0\0\0\0WEBP"))));
        assertEquals("image/gif", PhotoSearchFiles.typeOf(write(new File(folder, "d"), picture("GIF89a"))));
        assertEquals("image/avif", PhotoSearchFiles.typeOf(write(new File(folder, "e"),
                picture(0, 0, 0, 0x1c, 'f', 't', 'y', 'p', 'a', 'v', 'i', 'f'))));
        assertEquals("image/heic", PhotoSearchFiles.typeOf(write(new File(folder, "f"),
                picture(0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'))));
        assertNull("a page passed for a picture", PhotoSearchFiles.typeOf(write(new File(folder, "g"), picture("<html>"))));
        assertNull("a short file", PhotoSearchFiles.typeOf(write(new File(folder, "h"), new byte[] { (byte) 0xff })));
        assertNull("no file", PhotoSearchFiles.typeOf(new File(folder, "none")));
        assertEquals("b.png", PhotoSearchFiles.nameOf(new File(folder, "b")));
        assertEquals("a.jpg", PhotoSearchFiles.nameOf(new File(folder, "a")));
        assertEquals("g", PhotoSearchFiles.nameOf(new File(folder, "g")));
    }

    @Test
    public void theChooserGrantsTheOnePhotoForReading() {
        PhotoSearch.Fetched photo = new PhotoSearch.Fetched(42, PhotoFormat.JPEG);
        Uri uri = PhotoSearch.uri(context, 42);
        Intent chooser = PhotoSearch.chooser(context, photo);
        assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
        assertEquals("Search photo with", String.valueOf(chooser.getCharSequenceExtra(Intent.EXTRA_TITLE)));
        assertTrue("the chooser can't start from the app's context", (chooser.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);

        Intent send = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        assertNotNull(send);
        assertEquals(Intent.ACTION_SEND, send.getAction());
        assertEquals(PhotoFormat.JPEG, send.getType());
        assertEquals(uri, send.getParcelableExtra(Intent.EXTRA_STREAM));
        assertTrue("no read grant", (send.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        assertEquals("a write grant", 0, send.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        assertEquals("a lasting grant", 0, send.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        ClipData clip = send.getClipData();
        assertNotNull("no ClipData, so the grant reaches no app", clip);
        assertEquals(1, clip.getItemCount());
        assertEquals(uri, clip.getItemAt(0).getUri());
    }

    @Test
    public void aTapWithTheSwitchOffOrPausedDoesNothing() {
        Settings.POST_MENU_PHOTO_SEARCH.save(false);
        assertNull(PhotoSearch.tap(context, PhotoMenuItemForTests.image(1)));
        Settings.POST_MENU_PHOTO_SEARCH.save(true);
        Settings.DOWNLOAD_PHOTOS.save(false);
        assertNull(PhotoSearch.tap(context, PhotoMenuItemForTests.image(1)));
        Settings.DOWNLOAD_PHOTOS.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertNull(PhotoSearch.tap(context, PhotoMenuItemForTests.image(1)));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull("a tap that did nothing said something", ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void anAddressOffMetasServersIsRefusedBeforeAnyFetch() {
        assertNull(PhotoSearch.tap(context, server.origin() + "/photo.jpg"));
        assertNull(PhotoSearch.tap(context, null));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(COULD_NOT_GET, ShadowToast.getTextOfLatestToast());
        assertEquals(0, server.hits("/photo.jpg"));
    }

    @Test
    public void aRefusedFetchSaysSoAndOpensNothing() throws InterruptedException {
        Thread fetch = PhotoSearch.tap(context, PhotoMenuItemForTests.image(1));
        assertNotNull("the fetch didn't start", fetch);
        fetch.join(20_000);
        assertFalse("the fetch never ended", fetch.isAlive());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(COULD_NOT_GET, ShadowToast.getTextOfLatestToast());
        assertNull("a chooser opened", Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
    }
}
