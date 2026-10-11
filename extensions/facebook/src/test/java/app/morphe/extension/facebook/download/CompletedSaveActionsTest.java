/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.app.Activity;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContextWrapper;
import android.content.ActivityNotFoundException;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.service.notification.StatusBarNotification;
import android.view.View;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowToast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.facebook.misc.AppLockForTests;
import app.morphe.extension.facebook.settings.CompletedEntryForTests;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsEntry;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Only published successes expose a local file; every destination and grant remains specific. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {30, 36})
public class CompletedSaveActionsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private static final String COMPLETED = "hushfacebook-completed";
    private Context context;
    private Gallery gallery;
    private File file;
    private Application.ActivityLifecycleCallbacks watcher;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        gallery = Robolectric.setupContentProvider(Gallery.class, MediaStore.AUTHORITY);
        file = File.createTempFile("published", ".bin", context.getCacheDir());
        Files.write(file.toPath(), new byte[64]);
        gallery.file = file;
        watcher = CompletedEntryForTests.watcher();
        ((Application) context).registerActivityLifecycleCallbacks(watcher);
        LogBufferManager.clearLogBuffer();
        // Each test starts as a fresh process would, so a key kept in memory from an earlier test's
        // storage never stands in for this one's.
        SavedFileActions.forgetKey();
    }

    @After public void tearDown() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        shadowOf(Looper.getMainLooper()).idle();
        ((Application) context).unregisterActivityLifecycleCallbacks(watcher);
        Settings.DOWNLOAD_COMPATIBLE.resetToDefault();
        file.delete();
        for (SaveControl.Running running : SaveControl.running()) SaveControl.cancel(running.id);
        LogBufferManager.clearLogBuffer();
    }

    private NotificationManager notifications() { return context.getSystemService(NotificationManager.class); }

    private List<Notification> completed() {
        List<Notification> result = new ArrayList<>();
        for (StatusBarNotification note : notifications().getActiveNotifications()) {
            if (note.getTag() != null && note.getTag().startsWith(COMPLETED + ":")) result.add(note.getNotification());
        }
        return result;
    }

    private Notification save(boolean video, String mime) throws Exception {
        long next = gallery.next;
        Uri uri = ContentUris.withAppendedId(video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, next);
        shadowOf(context.getContentResolver()).registerOutputStream(uri, new ByteArrayOutputStream());
        finish(MediaDownload.start(context, video, PostDetails.of("private-source-id"),
                (writer, progress) -> Downloader.publish(file, mime, writer, progress)));
        List<Notification> notes = completed();
        assertFalse("a published save has no completion actions", notes.isEmpty());
        for (Notification note : notes) {
            if (uri.equals(entry(note, 0).getData())) return note;
        }
        throw new AssertionError("the completed file has no own notification");
    }

    private static void finish(Thread worker) throws Exception {
        worker.join(10_000);
        assertFalse("save didn't finish", worker.isAlive());
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static Intent entry(Notification note, int action) {
        assertEquals(2, note.actions.length);
        assertTrue(shadowOf(note.actions[action].actionIntent).isActivityIntent());
        assertTrue(shadowOf(note.actions[action].actionIntent).isImmutable());
        return new Intent(shadowOf(note.actions[action].actionIntent).getSavedIntent());
    }

    private static void settle() throws Exception {
        for (int i = 0; i < 3; i++) {
            shadowOf(Looper.getMainLooper()).idle();
            Utils.awaitBackgroundTasksForTests();
        }
        shadowOf(Looper.getMainLooper()).idle();
    }

    private ActivityController<Activity> receive(Intent intent) throws Exception {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class, intent).create();
        SettingsEntry.onActivityCreate(controller.get());
        controller.start().resume();
        settle();
        return controller;
    }

    @Test public void publishedVideoAndPhotoExposeOnlyGenericActions() throws Exception {
        Notification video = save(true, "video/mp4");
        Notification photo = save(false, "image/webp");
        assertEquals("Video saved", video.extras.getString(Notification.EXTRA_TITLE));
        assertEquals("Photo saved", photo.extras.getString(Notification.EXTRA_TITLE));
        assertEquals("Open", video.actions[0].title.toString());
        assertEquals("Share", video.actions[1].title.toString());
        assertFalse(video.extras.toString().contains("private-source-id"));
        assertFalse(video.extras.toString().contains("Movies/Facebook"));
        assertTrue(SaveControl.running().isEmpty());
        assertFalse((video.flags & Notification.FLAG_ONGOING_EVENT) != 0);
        assertEquals(2, completed().size());
        assertEquals("video/mp4", entry(video, 0).getType());
        assertEquals("image/webp", entry(photo, 0).getType());
    }

    @Test public void concurrentDestinationsHaveDifferentIdentityBeyondExtras() throws Exception {
        Uri firstUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, gallery.next);
        Uri secondUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, gallery.next + 1);
        shadowOf(context.getContentResolver()).registerOutputStream(firstUri, new ByteArrayOutputStream());
        shadowOf(context.getContentResolver()).registerOutputStream(secondUri, new ByteArrayOutputStream());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        MediaDownload.Job job = (writer, progress) -> {
            ready.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            return Downloader.publish(file, "video/mp4", writer, progress);
        };
        Thread firstWorker = MediaDownload.start(context, true, job);
        Thread secondWorker = MediaDownload.start(context, true, job);
        try { assertTrue(ready.await(5, TimeUnit.SECONDS)); }
        finally { release.countDown(); }
        finish(firstWorker);
        finish(secondWorker);
        List<Notification> notes = completed();
        assertEquals(2, notes.size());
        Notification first = notes.get(0);
        Notification second = notes.get(1);
        Intent one = entry(first, 0);
        Intent two = entry(second, 0);
        assertNotEquals(one.getData(), two.getData());
        assertFalse(one.filterEquals(two));
        assertFalse(one.filterEquals(entry(first, 1)));
        assertNotEquals(first.actions[0].actionIntent, second.actions[0].actionIntent);
        assertTrue(gallery.uris.contains(one.getData()));
        assertTrue(gallery.uris.contains(two.getData()));
        assertEquals(context.getPackageName(), one.getComponent().getPackageName());
        Uri expected = one.getData();
        // Starting the first after the second still opens the first file.
        try (ActivityController<Activity> controller = receive(one)) {
            assertEquals(expected, shadowOf(controller.get()).getNextStartedActivity().getData());
        }
    }

    @Test public void openAndShareCarryCorrectMimeAndTemporaryReadOnlyGrants() throws Exception {
        Notification note = save(false, "image/webp");
        for (int action = 0; action < 2; action++) {
            try (ActivityController<Activity> controller = receive(entry(note, action))) {
                Intent launched = shadowOf(controller.get()).getNextStartedActivity();
                assertNotNull(launched);
                Intent target = action == 0 ? launched : launched.getParcelableExtra(Intent.EXTRA_INTENT);
                assertEquals(action == 0 ? Intent.ACTION_VIEW : Intent.ACTION_SEND, target.getAction());
                assertEquals("image/webp", target.getType());
                assertEquals(gallery.uris.get(0), target.getClipData().getItemAt(0).getUri());
                assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, target.getFlags());
                assertNull(target.getStringExtra(Intent.EXTRA_TEXT));
                assertNull(target.getStringExtra(Intent.EXTRA_SUBJECT));
                if (action == 1) {
                    assertEquals(Intent.ACTION_CHOOSER, launched.getAction());
                    assertEquals(gallery.uris.get(0), target.getParcelableExtra(Intent.EXTRA_STREAM));
                    assertTrue((launched.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
                    assertEquals(gallery.uris.get(0), launched.getClipData().getItemAt(0).getUri());
                }
                assertEquals(Intent.ACTION_MAIN, controller.get().getIntent().getAction());
                assertNull(controller.get().getIntent().getData());
            }
        }
        assertNotEquals("file check ran on the UI thread", Looper.getMainLooper().getThread(), gallery.checkedBy);
    }

    @Test public void cancellationBeforePublicationAndPublicationFailureExposeNoFileActions() throws Exception {
        for (boolean cancel : new boolean[]{true, false}) {
            Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, gallery.next);
            shadowOf(context.getContentResolver()).registerOutputStream(uri, new ByteArrayOutputStream());
            gallery.failCommit = !cancel;
            finish(MediaDownload.start(context, true, (writer, progress) -> {
                if (cancel) ((SaveControl.Save) progress).cancel();
                return Downloader.publish(file, "video/mp4", writer, progress);
            }));
            assertTrue(completed().isEmpty());
            assertTrue(gallery.rows.isEmpty());
        }
    }

    @Test public void aClaimedPublicationCannotBeCancelledAndHasNoActionsBeforeCommit() throws Exception {
        gallery.entered = new CountDownLatch(1);
        gallery.release = new CountDownLatch(1);
        Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, gallery.next);
        shadowOf(context.getContentResolver()).registerOutputStream(uri, new ByteArrayOutputStream());
        Thread worker = MediaDownload.start(context, true,
                (writer, progress) -> Downloader.publish(file, "video/mp4", writer, progress));
        try {
            assertTrue(gallery.entered.await(5, TimeUnit.SECONDS));
            assertTrue(completed().isEmpty());
            assertEquals(1, SaveControl.running().size());
            assertFalse(SaveControl.cancel(SaveControl.running().get(0).id));
        } finally { gallery.release.countDown(); }
        finish(worker);
        assertEquals(1, completed().size());
    }

    @Test public void aJobClaimingSuccessWithoutPublicationExposesNoFileActions() throws Exception {
        finish(MediaDownload.start(context, true, (writer, progress) -> Downloader.Result.ok("video/mp4")));
        assertTrue(completed().isEmpty());
    }

    @Test public void notificationDenialAndDisabledChannelKeepSuccessFeedbackWithoutPermissionRequests() throws Exception {
        for (boolean appWide : new boolean[]{true, false}) {
            shadowOf(notifications()).setNotificationsEnabled(!appWide);
            if (!appWide) notifications().createNotificationChannel(new NotificationChannel(SaveControl.CHANNEL,
                    "Disabled", NotificationManager.IMPORTANCE_NONE));
            Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, gallery.next);
            shadowOf(context.getContentResolver()).registerOutputStream(uri, new ByteArrayOutputStream());
            finish(MediaDownload.start(context, true,
                    (writer, progress) -> Downloader.publish(file, "video/mp4", writer, progress)));
            assertTrue(completed().isEmpty());
            assertEquals("Saved to " + L10n.isolate("Movies/Facebook"), ShadowToast.getTextOfLatestToast());
            assertNull("a denied notification opened a permission screen",
                    shadowOf((Application) context).getNextStartedActivity());
        }
    }

    @Test public void deletedPendingForeignAndUnreadableRowsDoNotOpenOrShare() throws Exception {
        Notification note = save(true, "video/mp4");
        ContentValues original = new ContentValues(gallery.rows.get(1L));
        for (int failure = 0; failure < 5; failure++) {
            gallery.rows.put(1L, new ContentValues(original));
            gallery.unreadable = failure == 3;
            if (failure == 0) gallery.rows.clear();
            if (failure == 1) gallery.rows.get(1L).put(MediaStore.MediaColumns.IS_PENDING, 1);
            if (failure == 2) gallery.rows.get(1L).put(MediaStore.MediaColumns.OWNER_PACKAGE_NAME, "other.package");
            if (failure == 4) gallery.rows.get(1L).put(MediaStore.MediaColumns.IS_TRASHED, 1);
            for (int action = 0; action < 2; action++) {
                ShadowToast.reset();
                try (ActivityController<Activity> controller = receive(entry(note, action))) {
                    assertNull(shadowOf(controller.get()).getNextStartedActivity());
                    assertEquals("That saved file is no longer available.", ShadowToast.getTextOfLatestToast());
                }
            }
        }
        assertFalse(LogBufferManager.buildExportText().contains(gallery.uris.get(0).toString()));
    }

    @Test public void missingHandlersAndDeniedLaunchesGiveCleanFeedback() throws Exception {
        Notification note = save(true, "video/mp4");
        for (boolean missing : new boolean[]{true, false}) {
            for (int action = 0; action < 2; action++) {
                FailingActivity.missing = missing;
                try (ActivityController<FailingActivity> controller = Robolectric.buildActivity(FailingActivity.class,
                        entry(note, action)).create()) {
                    SettingsEntry.onActivityCreate(controller.get());
                    controller.start().resume();
                    settle();
                    assertEquals(missing ? "There's no app here that can open or share this saved file."
                            : "Couldn't open or share that saved file. Try again.", ShadowToast.getTextOfLatestToast());
                }
            }
        }
    }

    @Test @Config(qualifiers = "de") public void completionAndActionLabelsFollowTheCurrentLanguage() throws Exception {
        Notification note = save(true, "video/mp4");
        assertEquals(L10n.t(context, "Video saved"), note.extras.getString(Notification.EXTRA_TITLE));
        assertNotEquals("Video saved", note.extras.getString(Notification.EXTRA_TITLE));
        assertEquals(L10n.t(context, "Open"), note.actions[0].title.toString());
        assertEquals(L10n.t(context, "Share"), note.actions[1].title.toString());
    }

    @Test public void currentInstallIdentityAndFileDataKeepRecreatedButtonsDistinct() {
        Context clone = new ContextWrapper(context) {
            @Override public String getPackageName() { return "com.facebook.katana.hushfacebook"; }
        };
        Uri one = Uri.parse("content://media/external/video/media/71");
        Uri two = Uri.parse("content://media/external/video/media/72");
        Intent first = shadowOf(SavedFileActions.button(clone, one, "video/mp4", false)).getSavedIntent();
        Intent second = shadowOf(SavedFileActions.button(clone, two, "video/mp4", false)).getSavedIntent();
        assertEquals(clone.getPackageName(), first.getComponent().getPackageName());
        assertEquals("com.facebook.katana.LoginActivity", first.getComponent().getClassName());
        assertFalse(first.filterEquals(second));
        assertEquals(one, first.getData());
        assertEquals(two, second.getData());
    }

    /**
     * After a run of saves nobody cleared, the oldest cards go, so Android's limit on an app's
     * notifications never swallows the next save's own progress card.
     */
    @Test public void onlyTheNewestCompletedCardsStayUp() throws Exception {
        List<Uri> saved = new ArrayList<>();
        for (int i = 0; i < SaveControl.COMPLETED_KEPT + 3; i++) {
            saved.add(entry(save(true, "video/mp4"), 0).getData());
        }
        assertEquals(SaveControl.COMPLETED_KEPT, completed().size());
        for (int i = 0; i < saved.size(); i++) {
            boolean up = false;
            for (StatusBarNotification note : notifications().getActiveNotifications()) {
                if ((COMPLETED + ":" + saved.get(i)).equals(note.getTag())) up = true;
            }
            assertEquals("card " + i + " of " + saved.size(), i >= 3, up);
        }
    }

    @Test public void aPendingWriterAndAnUnclaimedSaveExposeNoFileHandle() throws Exception {
        MediaStoreWriter writer = new MediaStoreWriter(context, true);
        assertNull(writer.publishedUri());
        assertNull(writer.publishedMime());
        Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, gallery.next);
        shadowOf(context.getContentResolver()).registerOutputStream(uri, new ByteArrayOutputStream());
        writer.open("application/octet-stream").write(1);
        assertNull(writer.publishedUri());
        writer.commit();
        assertEquals(uri, writer.publishedUri());
        assertEquals("video/mp4", writer.publishedMime());
        SaveControl.Save save = SaveControl.begin(context, true);
        try {
            SaveControl.showCompleted(save, writer);
            assertTrue(completed().isEmpty());
        } finally { save.end(); }
    }

    @Test public void acceptedFinalFlushCancellationLeavesNoFileAction() throws Exception {
        CountDownLatch flush = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, gallery.next);
        shadowOf(context.getContentResolver()).registerOutputStream(uri, new ByteArrayOutputStream() {
            @Override public void flush() {
                flush.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            }
        });
        Thread worker = MediaDownload.start(context, true,
                (writer, progress) -> Downloader.publish(file, "video/mp4", writer, progress));
        try {
            assertTrue(flush.await(5, TimeUnit.SECONDS));
            assertTrue(SaveControl.cancel(SaveControl.running().get(0).id));
        } finally { release.countDown(); }
        finish(worker);
        assertTrue(completed().isEmpty());
        assertTrue(gallery.rows.isEmpty());
        assertEquals("Save canceled", ShadowToast.getTextOfLatestToast());
    }

    @Test public void theCompatibilityAdviceAndFileActionsCoexist() throws Exception {
        Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, gallery.next);
        shadowOf(context.getContentResolver()).registerOutputStream(uri, new ByteArrayOutputStream());
        finish(MediaDownload.start(context, true,
                (writer, progress) -> Downloader.publish(file, "video/mp4", writer, progress).refused()));
        assertEquals(1, completed().size());
        Notification advice = null;
        for (StatusBarNotification note : notifications().getActiveNotifications()) {
            if (SaveControl.SAVED_TAG.equals(note.getTag())) advice = note.getNotification();
        }
        assertNotNull(advice);
        assertEquals(1, advice.actions.length);
        assertEquals("Open the setting", advice.actions[0].title.toString());
        assertEquals(Settings.DOWNLOAD_COMPATIBLE.key,
                shadowOf(advice.actions[0].actionIntent).getSavedIntent().getStringExtra(SettingsEntry.EXTRA_SHOW_SETTING));
        assertEquals("Saved, but WhatsApp and some editors may not accept it", ShadowToast.getTextOfLatestToast());
    }

    @Test public void nonMediaAndExpiredTapRequestsDoNotReachAFileHandler() throws Exception {
        Notification note = save(true, "video/mp4");
        String source = "https://scontent.xx.fbcdn.net/private.mp4?token=private";
        try (ActivityController<Activity> controller = receive(entry(note, 0).setDataAndType(Uri.parse(source), "video/mp4"))) {
            assertNull(shadowOf(controller.get()).getNextStartedActivity());
        }
        assertFalse(LogBufferManager.buildExportText().contains(source));
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class, entry(note, 0)).create()) {
            SettingsEntry.onActivityCreate(controller.get());
            org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(31));
            controller.start().resume();
            settle();
            assertNull(shadowOf(controller.get()).getNextStartedActivity());
        }
    }

    /**
     * Facebook's launcher activity is exported, so any app can send the buttons' action with a
     * guessed address of a file Facebook saved. Without the button's key nothing opens or shares,
     * and the intent reaches Facebook as it came. The real button still works after a restart.
     */
    @Test public void aRequestFromAnotherAppOpensAndSharesNothing() throws Exception {
        Notification note = save(true, "video/mp4");
        for (int action = 0; action < 2; action++) {
            Intent real = entry(note, action);
            assertNotNull("the button carries no key", real.getStringExtra(SavedFileActions.KEY));
            Intent forged = new Intent(real.getAction()).setComponent(real.getComponent())
                    .setDataAndType(real.getData(), real.getType());
            Intent guessed = new Intent(forged).putExtra(SavedFileActions.KEY, "00000000000000000000000000000000");
            for (Intent request : new Intent[]{forged, guessed}) {
                try (ActivityController<Activity> controller = receive(request)) {
                    assertNull("another app's request reached a file handler",
                            shadowOf(controller.get()).getNextStartedActivity());
                    assertEquals(real.getAction(), controller.get().getIntent().getAction());
                }
            }
            // A process restart reads the same key back, so the notification's own tap still works.
            SavedFileActions.forgetKey();
            try (ActivityController<Activity> controller = receive(real)) {
                Intent launched = shadowOf(controller.get()).getNextStartedActivity();
                assertNotNull("the notification's own tap stopped working after a restart", launched);
                Intent target = action == 0 ? launched : launched.getParcelableExtra(Intent.EXTRA_INTENT);
                assertEquals(gallery.uris.get(0), action == 0 ? target.getData()
                        : target.getParcelableExtra(Intent.EXTRA_STREAM));
                assertNull("the key was left for Facebook to read",
                        controller.get().getIntent().getStringExtra(SavedFileActions.KEY));
            }
        }
    }

    /** A button another Facebook process made carries the key that process kept, which this one never read. */
    @Test public void aKeyAnotherProcessKeptStillOpens() throws Exception {
        Notification note = save(true, "video/mp4");
        Intent real = entry(note, 0);
        String other = "0123456789abcdef0123456789abcdef";
        assertNotEquals(other, real.getStringExtra(SavedFileActions.KEY));
        Files.write(new File(context.getNoBackupFilesDir(), SavedFileActions.KEY_FILE).toPath(),
                other.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        Intent fromOther = new Intent(real).putExtra(SavedFileActions.KEY, other);
        for (Intent tap : new Intent[]{fromOther, real}) {
            try (ActivityController<Activity> controller = receive(tap)) {
                assertNotNull("a tap on a button with a kept key opened nothing",
                        shadowOf(controller.get()).getNextStartedActivity());
            }
        }
    }

    @Test public void indexedMimeChangesUseTheGallerysCurrentTypeAtTheTap() throws Exception {
        Notification note = save(true, "video/mp4");
        gallery.rows.get(1L).put(MediaStore.MediaColumns.MIME_TYPE, "video/webm");
        for (int action = 0; action < 2; action++) {
            try (ActivityController<Activity> controller = receive(entry(note, action))) {
                Intent launched = shadowOf(controller.get()).getNextStartedActivity();
                Intent target = action == 0 ? launched : launched.getParcelableExtra(Intent.EXTRA_INTENT);
                assertEquals("video/webm", target.getType());
                assertEquals(gallery.uris.get(0), target.getClipData().getItemAt(0).getUri());
            }
        }
    }

    @Test public void notificationsTurnedOffDuringPublicationStillLeaveSuccessFeedback() throws Exception {
        gallery.entered = new CountDownLatch(1);
        gallery.release = new CountDownLatch(1);
        Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, gallery.next);
        shadowOf(context.getContentResolver()).registerOutputStream(uri, new ByteArrayOutputStream());
        Thread worker = MediaDownload.start(context, true,
                (writer, progress) -> Downloader.publish(file, "video/mp4", writer, progress));
        try {
            assertTrue(gallery.entered.await(5, TimeUnit.SECONDS));
            shadowOf(notifications()).setNotificationsEnabled(false);
        } finally { gallery.release.countDown(); }
        finish(worker);
        assertTrue(completed().isEmpty());
        assertEquals("Saved to " + L10n.isolate("Movies/Facebook"), ShadowToast.getTextOfLatestToast());
        assertEquals(Integer.valueOf(0), gallery.rows.get(1L).getAsInteger(MediaStore.MediaColumns.IS_PENDING));
    }

    /** A tap on Open or Share while Facebook is locked waits for the check; nothing opens before it. */
    @Test public void aLockedFacebookOpensAndSharesNothingUntilTheCheckPasses() throws Exception {
        Notification note = save(true, "video/mp4");
        Settings.APP_LOCK.save(true);
        try {
            for (int action = 0; action < 2; action++) {
                AppLockForTests.arm();
                try (ActivityController<Activity> controller =
                             Robolectric.buildActivity(Activity.class, entry(note, action)).create()) {
                    SettingsEntry.onActivityCreate(controller.get());
                    AppLockForTests.started(controller.get());
                    controller.start().resume();
                    AppLockForTests.resumed(controller.get());
                    settle();
                    assertNull("a locked Facebook opened a saved file",
                            shadowOf(controller.get()).getNextStartedActivity());

                    AppLockForTests.unlock();
                    settle();
                    Intent launched = shadowOf(controller.get()).getNextStartedActivity();
                    assertNotNull("the tap was lost with the unlock", launched);
                    Intent target = action == 0 ? launched : launched.getParcelableExtra(Intent.EXTRA_INTENT);
                    assertEquals(gallery.uris.get(0), action == 0 ? target.getData()
                            : target.getParcelableExtra(Intent.EXTRA_STREAM));
                }
            }
        } finally {
            AppLockForTests.disarm();
            Settings.APP_LOCK.resetToDefault();
        }
    }

    @Test public void aTapThatWaitedPastItsTimeOpensNothingAfterTheUnlock() throws Exception {
        Notification note = save(true, "video/mp4");
        Settings.APP_LOCK.save(true);
        try {
            AppLockForTests.arm();
            try (ActivityController<Activity> controller =
                         Robolectric.buildActivity(Activity.class, entry(note, 0)).create()) {
                SettingsEntry.onActivityCreate(controller.get());
                AppLockForTests.started(controller.get());
                controller.start().resume();
                AppLockForTests.resumed(controller.get());
                settle();
                org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(31));
                AppLockForTests.unlock();
                settle();
                assertNull(shadowOf(controller.get()).getNextStartedActivity());
            }
        } finally {
            AppLockForTests.disarm();
            Settings.APP_LOCK.resetToDefault();
        }
    }

    @Test public void aLauncherHandoffDeliversOnTheNextLiveScreen() throws Exception {
        Notification note = save(true, "video/mp4");
        try (ActivityController<Activity> launcher = Robolectric.buildActivity(Activity.class, entry(note, 0)).create()) {
            SettingsEntry.onActivityCreate(launcher.get());
            launcher.get().finish();
            try (ActivityController<Activity> next = Robolectric.buildActivity(Activity.class).setup()) {
                settle();
                assertEquals(gallery.uris.get(0), shadowOf(next.get()).getNextStartedActivity().getData());
            }
        }
    }

    @Test public void newIntentsDeliverOnceAndRecreationDoesNotReplayAConsumedTap() throws Exception {
        Notification note = save(true, "video/mp4");
        try (ActivityController<Activity> controller = receive(entry(note, 0))) {
            assertNotNull(shadowOf(controller.get()).getNextStartedActivity());
            controller.pause();
            Intent incoming = entry(note, 1);
            SettingsEntry.onNewIntent(controller.get(), incoming);
            controller.get().setIntent(incoming);
            controller.resume();
            settle();
            assertEquals(Intent.ACTION_CHOOSER, shadowOf(controller.get()).getNextStartedActivity().getAction());
            controller.recreate();
            SettingsEntry.onActivityCreate(controller.get());
            settle();
            assertNull("recreation shared the same file twice", shadowOf(controller.get()).getNextStartedActivity());
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 30, qualifiers = "w390dp-night-xhdpi")
    public void theFrameworkCompletionRendersBothGenericActions() throws Exception {
        Notification note = save(true, "video/mp4");
        View view = Notification.Builder.recoverBuilder(context, note).createBigContentView().apply(context, null);
        view.measure(View.MeasureSpec.makeMeasureSpec(780, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.AT_MOST));
        view.layout(0, 0, 780, view.getMeasuredHeight());
        Bitmap image = Bitmap.createBitmap(780, view.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        canvas.drawColor(0xff121212);
        view.draw(canvas);
        File report = new File("build/reports/completed-saves/expanded-video.png");
        assertTrue(report.getParentFile().isDirectory() || report.getParentFile().mkdirs());
        try (FileOutputStream output = new FileOutputStream(report)) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        image.recycle();
        assertTrue(view.getHeight() > 0);
    }

    public static class FailingActivity extends Activity {
        static boolean missing;
        @Override public void startActivity(Intent intent) {
            if (missing) throw new ActivityNotFoundException("private failure detail");
            throw new SecurityException("private failure detail");
        }
    }

    public static class Gallery extends ContentProvider {
        final Map<Long, ContentValues> rows = new HashMap<>();
        final List<Uri> uris = new ArrayList<>();
        long next = 1;
        File file;
        boolean failCommit;
        boolean unreadable;
        Thread checkedBy;
        CountDownLatch entered;
        CountDownLatch release;
        @Override public boolean onCreate() { return true; }
        @Override public synchronized Uri insert(Uri uri, ContentValues values) {
            long id = next++;
            ContentValues row = new ContentValues(values);
            row.put(MediaStore.MediaColumns.OWNER_PACKAGE_NAME, getContext().getPackageName());
            rows.put(id, row);
            Uri item = ContentUris.withAppendedId(uri, id);
            uris.add(item);
            return item;
        }
        @Override public synchronized int update(Uri uri, ContentValues values, String selection, String[] args) {
            if (entered != null) {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            }
            if (failCommit) return 0;
            rows.get(ContentUris.parseId(uri)).putAll(values);
            return 1;
        }
        @Override public synchronized int delete(Uri uri, String selection, String[] args) {
            return rows.remove(ContentUris.parseId(uri)) == null ? 0 : 1;
        }
        @Override public synchronized Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            MatrixCursor cursor = new MatrixCursor(projection);
            if (selection != null) return cursor; // Name collision check.
            checkedBy = Thread.currentThread();
            ContentValues row = rows.get(ContentUris.parseId(uri));
            if (row != null) {
                Object[] values = new Object[projection.length];
                for (int i = 0; i < projection.length; i++) values[i] = row.get(projection[i]);
                cursor.addRow(values);
            }
            return cursor;
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            checkedBy = Thread.currentThread();
            if (unreadable || !rows.containsKey(ContentUris.parseId(uri))) throw new FileNotFoundException();
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public String getType(Uri uri) { return "video/mp4"; }
    }
}
