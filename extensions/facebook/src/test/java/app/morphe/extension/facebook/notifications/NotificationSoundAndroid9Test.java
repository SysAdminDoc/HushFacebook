/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.notifications;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Application;
import android.content.ContentValues;
import android.os.Environment;
import android.provider.MediaStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;

import app.morphe.extension.facebook.notifications.NotificationSound.Outcome;
import app.morphe.extension.facebook.notifications.NotificationSound.Result;

/**
 * Android 9 has no pending rows or folder column, so the chime is written into the public
 * Notifications folder and its row names the file (#102, #83).
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class NotificationSoundAndroid9Test {
    private static final String NAME = "Facebook notification.m4a";

    private Application context;
    private NotificationSoundTest.Sounds store;
    private File file;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        store = Robolectric.setupContentProvider(NotificationSoundTest.Sounds.class, MediaStore.AUTHORITY);
        file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_NOTIFICATIONS), NAME);
    }

    @After
    public void tearDown() {
        NotificationSound.sourceForTests = null;
        if (file.exists()) assertTrue(file.delete());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void withStorageAccessTheChimeIsWrittenToTheFolderOnceAndItsRowNamesTheFile() throws Exception {
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        byte[] chime = Mp4TitleTest.tagged("FB_FBPN_min7p8db");
        NotificationSound.sourceForTests = () -> new ByteArrayInputStream(chime.clone());

        Result saved = NotificationSound.save(context);
        assertEquals(Outcome.SAVED, saved.outcome);
        assertEquals(NAME, saved.name);
        assertArrayEquals(Mp4Title.untitled(chime.clone()), Files.readAllBytes(file.toPath()));
        assertEquals(1, store.rows.size());
        ContentValues row = store.rows.values().iterator().next();
        assertEquals(file.getAbsolutePath(), row.getAsString(MediaStore.MediaColumns.DATA));
        assertEquals(NAME, row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertEquals(Integer.valueOf(1), row.getAsInteger(MediaStore.Audio.AudioColumns.IS_NOTIFICATION));
        assertFalse("Android 9 has no pending column", row.containsKey(MediaStore.MediaColumns.IS_PENDING));

        Result again = NotificationSound.save(context);
        assertEquals(Outcome.ALREADY_THERE, again.outcome);
        assertEquals(1, store.rows.size());
    }

    @Test
    public void withoutStorageAccessNothingIsWritten() {
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        NotificationSound.sourceForTests = () -> new ByteArrayInputStream(Mp4TitleTest.tagged("x"));

        assertEquals(Outcome.FAILED, NotificationSound.save(context).outcome);
        assertFalse(file.exists());
        assertTrue(store.rows.isEmpty());
    }

    @Test
    public void aRefusedRowTakesTheFileBackOut() {
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        store.refuseInsert = true;
        NotificationSound.sourceForTests = () -> new ByteArrayInputStream(Mp4TitleTest.tagged("x"));

        assertEquals(Outcome.FAILED, NotificationSound.save(context).outcome);
        assertFalse(file.exists());
    }
}
