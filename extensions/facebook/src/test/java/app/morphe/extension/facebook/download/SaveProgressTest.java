/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Looper;
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
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * A save the person can watch and stop: from the moment it starts, a notification with how far it
 * has got and a Cancel button; gone when the save ends; and after a cancel, or after Android ended
 * the process half way, nothing left in the cache or the gallery.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SaveProgressTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final long MIB = 1024L * 1024L;

    /** An ftyp box the sniff reads as an MP4 video; the made-up bytes that follow don't matter. */
    private static final byte[] MP4_HEAD = {
        0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2',
        0, 0, 0, 0, 'm', 'p', '4', '2', 'i', 's', 'o', 'm',
    };

    private final CountDownLatch release = new CountDownLatch(1);
    private LocalServer server;
    private MediaUrlPolicy policy;
    private Context context;
    private Gallery gallery;
    private final CountingStream published = new CountingStream();

    @Before
    public void setUp() throws IOException {
        server = new LocalServer();
        int port = server.port();
        policy = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") }) {
            @Override
            Refusal refusal(URL url) {
                if (url.getHost().equals("127.0.0.1") && url.getPort() == port) return null;
                return super.refusal(url);
            }
        };
        context = RuntimeEnvironment.getApplication();
        // A save of one file reads its policy through MediaDownload, as every save does.
        MediaDownload.policyForTests = policy;
        gallery = Robolectric.setupContentProvider(Gallery.class, MediaStore.AUTHORITY);
        LogBufferManager.clearLogBuffer();
        // Every save here is the first of its process, as a save after a restart would be.
        SaveLeftovers.forgetSweepForTests();
    }

    @After
    public void tearDown() throws IOException {
        release.countDown();
        // First, so a server that fails to close can't leave the fake clock to the next class.
        SavesForTests.endAll();
        MediaDownload.policyForTests = null;
        LogBufferManager.clearLogBuffer();
        server.close();
    }

    /** A save on the feature's own worker, as a tap starts one, of [path] into the gallery. */
    private Thread save(String path) {
        return save(path, new CompletableFuture<>());
    }

    /** The same, completing [ended] with the save's result as it ends. */
    private Thread save(String path, CompletableFuture<Downloader.Result> ended) {
        MediaDownload.Job job = MediaDownload.fileJob(context, server.origin() + path, Downloader.Kind.VIDEO);
        return MediaDownload.start(context, true, (writer, progress) -> {
            Downloader.Result result = job.run(writer, progress);
            ended.complete(result);
            return result;
        });
    }

    private void finish(Thread worker) throws InterruptedException {
        worker.join(30_000);
        assertFalse("the save never finished", worker.isAlive());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static NotificationManager notifications() {
        return RuntimeEnvironment.getApplication().getSystemService(NotificationManager.class);
    }

    /** The running save's progress notification, or null after it ends. */
    private static Notification saveNotification() {
        for (android.service.notification.StatusBarNotification shown : notifications().getActiveNotifications()) {
            if (SaveControl.TAG.equals(shown.getTag())) return shown.getNotification();
        }
        return null;
    }

    private static Notification waitForNotification(Predicate<Notification> wanted, String what)
            throws InterruptedException {
        long until = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < until) {
            Notification shown = saveNotification();
            if (shown != null && wanted.test(shown)) return shown;
            Thread.sleep(50);
        }
        throw new AssertionError("the notification never showed " + what + ": " + describe(saveNotification()));
    }

    private static String describe(Notification shown) {
        if (shown == null) return "none";
        return shown.extras.getCharSequence(Notification.EXTRA_TITLE) + " | "
                + shown.extras.getCharSequence(Notification.EXTRA_TEXT) + " | "
                + shown.extras.getInt(Notification.EXTRA_PROGRESS) + "/" + shown.extras.getInt(Notification.EXTRA_PROGRESS_MAX)
                + (shown.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) ? " indeterminate" : "");
    }

    private static boolean moving(Notification shown) {
        return !shown.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
                && shown.extras.getInt(Notification.EXTRA_PROGRESS) > 0;
    }

    private int workFiles() {
        String[] left = DashSave.workFolder(context).list();
        return left == null ? 0 : left.length;
    }

    private static int saveId(Notification shown) {
        Intent sent = Shadows.shadowOf(shown.actions[0].actionIntent).getSavedIntent();
        return sent.getIntExtra(SaveControl.EXTRA_ID, -1);
    }

    @Test public void everyPhaseRefreshesTheNotificationAtOnce() {
        SavesForTests.fakeClock();
        SaveControl.Save save = SaveControl.begin(context, true);
        try {
            save.transferred(MIB, 4 * MIB);
            save.joining();
            Notification joining = saveNotification();
            assertEquals("Joining the picture and sound\n0:00 elapsed", joining.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
            assertTrue(joining.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE));
            assertEquals(SaveControl.status(SaveControl.running().get(0)),
                    joining.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
            save.saving();
            Notification copying = saveNotification();
            assertEquals("Copying to the gallery\n0:00 elapsed", copying.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
            assertTrue(copying.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE));
            assertEquals(1, copying.actions.length);
            assertTrue(save.publishing());
            Notification publishing = saveNotification();
            assertTrue(publishing.actions == null || publishing.actions.length == 0);
            assertFalse(save.cancel());
            save.published(true);
        } finally { save.end(); }
        assertEquals(SaveControl.Outcome.VIDEO_SAVED, SaveControl.lastOutcome());
    }

    @Test public void lateCleanupCannotReplaceANewerGenericOutcome() {
        Shadows.shadowOf(notifications()).setNotificationsEnabled(false);
        SaveControl.Save cancelled = SaveControl.begin(context, true);
        assertTrue(cancelled.cancel());
        assertEquals(SaveControl.Outcome.CANCELLED, SaveControl.lastOutcome());
        SaveControl.Save latest = SaveControl.begin(context, false);
        assertTrue(latest.publishing());
        latest.published(true);
        latest.end();
        cancelled.end();
        assertEquals(SaveControl.Outcome.PHOTO_SAVED, SaveControl.lastOutcome());
        assertEquals("Photo saved", SaveControl.outcomeText(SaveControl.lastOutcome()));
        SaveControl.Save failed = SaveControl.begin(context, true);
        failed.end();
        assertEquals("The video wasn't saved. Try again.", SaveControl.outcomeText(SaveControl.lastOutcome()));
        assertTrue(SaveControl.running().isEmpty());
    }

    @Test
    public void aSaveShowsItsProgressAtOnceAndCancelStopsIt() throws Exception {
        cancelFromTheNotification();
    }

    /** From Android 13 the receiver isn't exported: the button still reaches it. */
    @Test
    @Config(sdk = 34)
    public void cancelReachesTheSaveOnAndroid14Too() throws Exception {
        cancelFromTheNotification();
    }

    private void cancelFromTheNotification() throws Exception {
        server.serveGenerated("/big.mp4", "video/mp4", MP4_HEAD, 100 * MIB, 4 * MIB, release);
        Thread worker = save("/big.mp4");

        // Straight away, before a byte arrives: nothing queues a save.
        Notification started = saveNotification();
        assertNotNull("no notification when the save started", started);
        assertEquals("Saving a video", String.valueOf(started.extras.getCharSequence(Notification.EXTRA_TITLE)));
        assertEquals(1, started.actions.length);
        assertEquals("Cancel", String.valueOf(started.actions[0].title));

        // Then how far it has got, against the 100 MiB the server announced.
        Notification moving = waitForNotification(SaveProgressTest::moving, "progress");
        String text = String.valueOf(moving.extras.getCharSequence(Notification.EXTRA_TEXT));
        assertTrue(text, text.contains(" of 100 MB\n") && text.endsWith(" elapsed"));
        assertEquals(100, moving.extras.getInt(Notification.EXTRA_PROGRESS_MAX));

        // A Cancel without this process's token is somebody else's broadcast and changes nothing.
        int id = saveId(moving);
        context.sendBroadcast(new Intent(SaveControl.ACTION_CANCEL).setPackage(context.getPackageName())
                .putExtra(SaveControl.EXTRA_ID, id));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Thread.sleep(400);
        assertTrue("a Cancel with no token stopped the save", worker.isAlive());

        // The button.
        moving.actions[0].actionIntent.send();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        finish(worker);

        assertEquals("Save canceled", ShadowToast.getTextOfLatestToast());
        assertNull("the notification outlived the save: " + describe(saveNotification()), saveNotification());
        assertEquals("a cancelled save made a gallery row", 0, gallery.inserts.size());
        assertEquals("a cancelled save left work files", 0, workFiles());
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("save " + id + " cancelled from its notification"));
        assertTrue(report, report.contains("save finished: CANCELLED"));
        assertFalse("a cancel was logged as an error:\n" + report, report.contains("ERROR | save finished: CANCELLED"));
    }

    /**
     * Each save's button stops that save. Given one request code for every button, Android updates
     * the one intent to the newest save's number, and the first notification's Cancel stops the
     * second save while the first goes on.
     */
    @Test
    public void cancelOnOneOfTwoSavesStopsThatOne() throws Exception {
        server.serveGenerated("/one.mp4", "video/mp4", MP4_HEAD, 16 * MIB, MIB, release);
        server.serveGenerated("/two.mp4", "video/mp4", MP4_HEAD, 16 * MIB, MIB, release);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(1), published);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(2), published);
        CompletableFuture<Downloader.Result> firstEnded = new CompletableFuture<>();
        CompletableFuture<Downloader.Result> secondEnded = new CompletableFuture<>();
        Thread first = save("/one.mp4", firstEnded);
        Thread second = save("/two.mp4", secondEnded);
        List<Integer> ids = new ArrayList<>();
        for (android.service.notification.StatusBarNotification up : notifications().getActiveNotifications()) {
            if (SaveControl.TAG.equals(up.getTag())) ids.add(up.getId());
        }
        java.util.Collections.sort(ids);
        assertEquals("two saves, two notifications: " + ids, 2, ids.size());
        Notification firstShown = Shadows.shadowOf(notifications()).getNotification(SaveControl.TAG, ids.get(0));
        assertEquals("the first save's button names another save", (int) ids.get(0), saveId(firstShown));
        assertEquals((int) ids.get(1),
                saveId(Shadows.shadowOf(notifications()).getNotification(SaveControl.TAG, ids.get(1))));

        firstShown.actions[0].actionIntent.send();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(shown(SaveControl.TAG, ids.get(0)));
        assertTrue("the second save's notification went with the first", shown(SaveControl.TAG, ids.get(1)));

        // The server sends everything it held back, so a save that Cancel didn't stop runs to the
        // end and saves. What decides the test is how each save ended, not how soon: a join of a
        // fixed length once failed a save that did stop, only later than a busy machine allowed.
        release.countDown();
        assertEquals("Cancel on the first save's notification didn't stop it",
                Downloader.Status.CANCELLED, firstEnded.get(60, TimeUnit.SECONDS).status);
        assertEquals("Cancel on the first save's notification stopped the second",
                Downloader.Status.OK, secondEnded.get(60, TimeUnit.SECONDS).status);
        finish(first);
        finish(second);
    }

    /**
     * A Cancel pressed as the save opens its connection. Its close came before the connection
     * existed and closed nothing, on the JDK and on Android alike, so the save went on to wait for
     * an answer for as long as the read timeout allowed. The test server answers one connection at
     * a time, and here it's busy with another fetch, the way it was when two saves ran at once and
     * the second got in first.
     */
    @Test
    public void aCancelAsTheConnectionOpensStopsASaveTheServerHasNotAnswered() throws Exception {
        server.serveHeld("/busy.mp4", "video/mp4", MP4_HEAD, 2 * MIB, MIB, release);
        server.serveGenerated("/late.mp4", "video/mp4", MP4_HEAD, 2 * MIB, Long.MAX_VALUE, null);
        // No read timeout: a save that waits for the answer waits until the server is free.
        Downloader.readTimeoutMs = 0;
        File other = File.createTempFile("busy", ".part", context.getCacheDir());
        Thread busy = new Thread(() -> Downloader.fetch(server.origin() + "/busy.mp4", Downloader.Kind.VIDEO, other,
                policy, Downloader.MAX_BYTES, Downloader.SILENT));
        busy.setDaemon(true);
        try {
            busy.start();
            long until = System.currentTimeMillis() + 20_000;
            while (server.hits("/busy.mp4") == 0 && System.currentTimeMillis() < until) Thread.sleep(20);
            assertEquals("the server never took the other fetch", 1, server.hits("/busy.mp4"));

            CompletableFuture<Downloader.Result> ended = new CompletableFuture<>();
            MediaDownload.Job job = MediaDownload.fileJob(context, server.origin() + "/late.mp4", Downloader.Kind.VIDEO);
            Thread worker = MediaDownload.start(context, true, (writer, progress) -> {
                SaveControl.Save save = (SaveControl.Save) progress;
                CountDownLatch closed = new CountDownLatch(1);
                Downloader.Result result = job.run(writer, new Downloader.Progress() {
                    @Override public void transferred(long done, long total) {
                        save.transferred(done, total);
                    }

                    // Cancel is pressed here, and its close runs before the connection opens.
                    @Override public void reading(Runnable close) {
                        save.reading(() -> {
                            close.run();
                            closed.countDown();
                        });
                        SaveControl.cancel(save.id);
                        try {
                            closed.await(20, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    @Override public boolean cancelled() {
                        return save.cancelled();
                    }
                });
                ended.complete(result);
                return result;
            });

            assertEquals("a save cancelled as its connection opened waited for the server",
                    Downloader.Status.CANCELLED, ended.get(30, TimeUnit.SECONDS).status);
            finish(worker);
            assertEquals("Save canceled", ShadowToast.getTextOfLatestToast());
            assertEquals(0, gallery.inserts.size());
        } finally {
            release.countDown();
            busy.join(30_000);
            Downloader.readTimeoutMs = 20_000;
            Downloader.delete(other);
        }
    }

    /**
     * From Android 13 the Cancel receiver is registered as not exported. Facebook targets a newer
     * Android than 14, where a receiver registered with no flag throws, and every Cancel button
     * then did nothing.
     *
     * <p>Android 13 itself is checked by setting SDK_INT here rather than with an sdk 33 config:
     * that sandbox is ColdStartHooksTest's alone, and a class that shares it sets the context
     * before that test runs.
     */
    @Test
    @Config(sdk = 34)
    public void fromAndroid13TheCancelReceiverIsNotExported() {
        assertEquals(java.util.Collections.singletonList(Context.RECEIVER_NOT_EXPORTED), receiverRegistrations());
        int sdk = android.os.Build.VERSION.SDK_INT;
        try {
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION.class, "SDK_INT", 33);
            assertEquals(java.util.Collections.singletonList(Context.RECEIVER_NOT_EXPORTED), receiverRegistrations());
        } finally {
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION.class, "SDK_INT", sdk);
        }
    }

    /** Below Android 13 there's no flag to give, and the token keeps other apps' broadcasts out. */
    @Test
    public void belowAndroid13TheCancelReceiverIsRegisteredWithNoFlag() {
        assertEquals(java.util.Collections.singletonList(-1), receiverRegistrations());
    }

    /** The flags each receiver a save registers is given, -1 for a registration with none. */
    private List<Integer> receiverRegistrations() {
        List<Integer> flags = new ArrayList<>();
        Context recording = new android.content.ContextWrapper(context) {
            @Override
            public Intent registerReceiver(android.content.BroadcastReceiver receiver, android.content.IntentFilter filter) {
                flags.add(-1);
                return super.registerReceiver(receiver, filter);
            }

            @Override
            public Intent registerReceiver(android.content.BroadcastReceiver receiver, android.content.IntentFilter filter,
                    int given) {
                flags.add(given);
                return super.registerReceiver(receiver, filter, given);
            }
        };
        SaveControl.begin(recording, true).end();
        return flags;
    }

    @Test
    public void aFinishedSaveTakesItsNotificationAwayAndLeavesNoRowPending() throws Exception {
        long size = 8 * MIB;
        server.serveGenerated("/whole.mp4", "video/mp4", MP4_HEAD, size, Long.MAX_VALUE, null);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(1), published);

        finish(save("/whole.mp4"));

        assertEquals("Saved to " + L10n.isolate("Movies/Facebook"), ShadowToast.getTextOfLatestToast());
        assertNull("the notification outlived the save", saveNotification());
        assertEquals(size, published.count.get());
        assertEquals(Integer.valueOf(0), gallery.rows.get(1L).getAsInteger(MediaStore.MediaColumns.IS_PENDING));
        assertTrue("a published row is still on the pending list", pendingList().isEmpty());
        assertEquals(0, workFiles());
    }

    @Test
    public void withNotificationsOffASaveRunsWithItsToasts() throws Exception {
        Shadows.shadowOf(notifications()).setNotificationsEnabled(false);
        server.serveGenerated("/whole.mp4", "video/mp4", MP4_HEAD, 256 * 1024, Long.MAX_VALUE, null);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(1), published);

        Thread worker = save("/whole.mp4");
        assertNull("a notification was posted with notifications off", saveNotification());
        finish(worker);

        assertEquals("Saved to " + L10n.isolate("Movies/Facebook"), ShadowToast.getTextOfLatestToast());
        assertEquals(0, Shadows.shadowOf(notifications()).getAllNotifications().size());
    }

    /**
     * A save that can't show its notification says where else it can be cancelled as it starts:
     * with Facebook's notifications off, or with only the saves channel switched off. With its
     * notification showing, the start says only that it's saving.
     */
    @Test
    public void aSaveWithNoNotificationSaysWhereToCancelIt() throws Exception {
        String elsewhere = "Saving... To cancel, open Downloads in Hushfacebook settings.";
        assertEquals("Saving...", startMessage());

        Shadows.shadowOf(notifications()).setNotificationsEnabled(false);
        assertEquals(elsewhere, startMessage());

        Shadows.shadowOf(notifications()).setNotificationsEnabled(true);
        notifications().createNotificationChannel(new NotificationChannel(SaveControl.CHANNEL, "Hushfacebook saves",
                NotificationManager.IMPORTANCE_NONE));
        assertEquals(elsewhere, startMessage());
    }

    /**
     * With Quiet saves on (issue #142), a save says nothing as it starts, finishes or is canceled,
     * with its notification or without one. A save that fails still says why.
     */
    @Test
    public void quietSavesKeepOnlyTheFailureToast() throws Exception {
        Settings.DOWNLOAD_QUIET.save(true);
        try {
            assertNull("the start toasted", startMessage());
            Shadows.shadowOf(notifications()).setNotificationsEnabled(false);
            assertNull("the start toasted with no notification", startMessage());

            server.serveGenerated("/whole.mp4", "video/mp4", MP4_HEAD, 256 * 1024, Long.MAX_VALUE, null);
            Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(1), published);
            ShadowToast.reset();
            finish(save("/whole.mp4"));
            assertEquals("the save didn't finish", 256 * 1024, published.count.get());
            assertNull("the finished save toasted", ShadowToast.getTextOfLatestToast());

            finish(MediaDownload.start(context, true, (writer, progress) ->
                    Downloader.Result.fail(Downloader.Status.HTTP_ERROR, Downloader.Cause.NETWORK, "offline")));
            assertEquals("The connection failed. Check your internet and try again.",
                    ShadowToast.getTextOfLatestToast());
        } finally {
            Settings.DOWNLOAD_QUIET.resetToDefault();
        }
    }

    /** What a save says as it starts. It runs until that's been read, then ends cancelled. */
    private String startMessage() throws InterruptedException {
        ShadowToast.reset();
        CountDownLatch read = new CountDownLatch(1);
        Thread worker = MediaDownload.start(context, true, (writer, progress) -> {
            try {
                read.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Downloader.Result.fail(Downloader.Status.CANCELLED, "cancelled");
        });
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        String said = ShadowToast.getTextOfLatestToast();
        read.countDown();
        finish(worker);
        return said;
    }

    /**
     * What a process Android ended mid-save leaves: work files, a row still pending, and a row it
     * had published but not yet crossed off. The next process's first save removes the first two
     * and keeps the finished file; a later save in the same process doesn't sweep again.
     */
    @Test
    public void whatAStoppedSaveLeftIsRemovedByTheNextOne() throws Exception {
        File folder = DashSave.workFolder(context);
        assertTrue(new File(folder, "video1.part").createNewFile());
        assertTrue(new File(folder, "joined2.mp4").createNewFile());
        Uri pending = row(1);
        Uri finished = row(0);
        SaveLeftovers.pending(context, pending);
        SaveLeftovers.pending(context, finished);
        // Retire an allocated id so it cannot be reused by the next save. A hard-coded 42 can
        // equal the process counter after other tests, replacing this notice before the sweep.
        SaveControl.Save stopped = SaveControl.begin(context, true);
        int stoppedId = stopped.id;
        stopped.end();
        // The stopped save's notification, which Android leaves up when it ends the process, and
        // one of Facebook's own under another tag, which isn't this sweep's to touch.
        notifications().notify(SaveControl.TAG, stoppedId, new Notification.Builder(context, SaveControl.CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Saving a video")
                .setOngoing(true).build());
        notifications().notify("facebook", stoppedId, new Notification.Builder(context, "facebook")
                .setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("A message").build());

        server.serveGenerated("/next.mp4", "video/mp4", MP4_HEAD, 64 * 1024, Long.MAX_VALUE, null);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(3), published);
        finish(save("/next.mp4"));

        assertFalse("the pending row a stopped save left is still there", gallery.rows.containsKey(ContentUris.parseId(pending)));
        assertTrue("a finished file was taken for a leftover", gallery.rows.containsKey(ContentUris.parseId(finished)));
        assertEquals(0, workFiles());
        assertTrue(pendingList().isEmpty());
        assertFalse("the stopped save's notification is still up", shown(SaveControl.TAG, stoppedId));
        assertTrue("Facebook's own notification was taken down", shown("facebook", stoppedId));
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("removed what a stopped save left: 2 work file(s), 1 pending gallery "
                + "row(s), 1 notification(s)"));

        // Once per process: a file there now belongs to this process's own saves.
        assertTrue(new File(folder, "video9.part").createNewFile());
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(4), published);
        finish(save("/next.mp4"));
        assertTrue("a second save in the same process swept again", new File(folder, "video9.part").exists());
    }

    /**
     * Android ended Facebook in the middle of a save, and Facebook is opened again with no save
     * after it. Its start removes what that save left, on a worker: the start doesn't wait on the
     * gallery, a save tapped meanwhile waits for the sweep, and that save's own notification and
     * work file stay, through a second start too. A finished file, the rest of the cache and a
     * start in another of Facebook's processes are left alone.
     */
    @Test
    public void startingFacebookRemovesWhatAStoppedSaveLeft() throws Exception {
        File folder = DashSave.workFolder(context);
        File partial = new File(folder, "video1.part");
        File joined = new File(folder, "joined2.mp4");
        assertTrue(partial.createNewFile());
        assertTrue(joined.createNewFile());
        File unrelated = new File(context.getCacheDir(), "image_cache.bin");
        assertTrue(unrelated.createNewFile());
        Uri pending = row(1);
        Uri finished = row(0);
        SaveLeftovers.pending(context, pending);
        SaveLeftovers.pending(context, finished);
        SaveControl.Save stopped = SaveControl.begin(context, true);
        int stoppedId = stopped.id;
        stopped.end();
        notifications().notify(SaveControl.TAG, stoppedId, new Notification.Builder(context, SaveControl.CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Saving a video")
                .setOngoing(true).build());

        // A start in another of Facebook's processes leaves it all: only the main one saves.
        android.content.pm.ApplicationInfo info = context.getApplicationInfo();
        String mainProcess = info.processName;
        info.processName = context.getPackageName() + ":adnw";
        try {
            app.morphe.extension.facebook.settings.SettingsEntry.onApplicationCreate(context);
            app.morphe.extension.shared.Utils.awaitBackgroundTasksForTests();
        } finally {
            info.processName = mainProcess;
        }
        assertTrue("a start in another process swept", gallery.rows.containsKey(ContentUris.parseId(pending)));
        assertTrue(partial.exists());

        gallery.holdDeletes = new CountDownLatch(1);
        long before = System.nanoTime();
        app.morphe.extension.facebook.settings.SettingsEntry.onApplicationCreate(context);
        long tookMs = (System.nanoTime() - before) / 1_000_000;
        assertTrue("starting Facebook left what the stopped save left",
                gallery.deleteAsked.await(10, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue("Facebook's start waited " + tookMs + " ms on the gallery", tookMs < 2_000);

        server.serveGenerated("/next.mp4", "video/mp4", MP4_HEAD, 100 * MIB, 4 * MIB, release);
        Thread worker = save("/next.mp4");
        int runningId = -1;
        for (android.service.notification.StatusBarNotification up : notifications().getActiveNotifications()) {
            if (SaveControl.TAG.equals(up.getTag()) && up.getId() != stoppedId) runningId = up.getId();
        }
        assertNotEquals("the new save showed no notification", -1, runningId);
        Thread.sleep(300);
        assertEquals("a save tapped during the sweep didn't wait for it", 0, server.hits("/next.mp4"));

        gallery.holdDeletes.countDown();
        long until = System.currentTimeMillis() + 20_000;
        while (server.hits("/next.mp4") == 0 && System.currentTimeMillis() < until) Thread.sleep(50);
        assertEquals("the save never went on after the sweep", 1, server.hits("/next.mp4"));

        assertFalse("the pending row a stopped save left is still there", gallery.rows.containsKey(ContentUris.parseId(pending)));
        assertTrue("a finished file was taken for a leftover", gallery.rows.containsKey(ContentUris.parseId(finished)));
        assertFalse(partial.exists());
        assertFalse(joined.exists());
        assertTrue("a cache file of Facebook's was removed", unrelated.exists());
        assertTrue(pendingList().isEmpty());
        assertFalse("the stopped save's notification is still up", shown(SaveControl.TAG, stoppedId));
        assertTrue("the running save's notification was taken for a leftover", shown(SaveControl.TAG, runningId));
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("removed what a stopped save left: 2 work file(s), 1 pending gallery "
                + "row(s), 1 notification(s)"));

        // A second start in the same process sweeps nothing: what's there now is the running save's.
        until = System.currentTimeMillis() + 20_000;
        while (workFiles() == 0 && System.currentTimeMillis() < until) Thread.sleep(50);
        assertEquals(1, workFiles());
        app.morphe.extension.facebook.settings.SettingsEntry.onApplicationCreate(context);
        app.morphe.extension.shared.Utils.awaitBackgroundTasksForTests();
        assertEquals("a second start took the running save's work file", 1, workFiles());
        assertTrue("a second start took the running save's notification", shown(SaveControl.TAG, runningId));

        assertTrue(SaveControl.cancel(runningId));
        finish(worker);
        assertEquals(0, workFiles());
    }

    /** Whether a notification with [tag] and [id] is up. */
    private static boolean shown(String tag, int id) {
        for (android.service.notification.StatusBarNotification up : notifications().getActiveNotifications()) {
            if (tag.equals(up.getTag()) && up.getId() == id) return true;
        }
        return false;
    }
    /** A row as a stopped save's writer would have left it. */
    private Uri row(int pendingFlag) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "FB_VID_20260925_010203.mp4");
        values.put(MediaStore.MediaColumns.IS_PENDING, pendingFlag);
        return context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
    }

    private java.util.Set<String> pendingList() {
        return context.getSharedPreferences("hushfacebook_saves", Context.MODE_PRIVATE)
                .getStringSet("pending_rows", new java.util.HashSet<>());
    }

    /** A cancel while the finished file is copied into the gallery removes the row it opened. */
    @Test
    public void aCancelDuringTheCopyIntoTheGalleryLeavesNoRow() throws IOException {
        File file = File.createTempFile("whole", ".mp4", DashSave.workFolder(context));
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(new byte[512 * 1024]);
        }
        AtomicInteger asked = new AtomicInteger();
        Downloader.Progress cancelsOnceCopying = new Downloader.Progress() {
            @Override public void transferred(long done, long total) {
            }

            @Override public void reading(Runnable close) {
            }

            // The first ask is before the gallery opens; the second is after the first block.
            @Override public boolean cancelled() {
                return asked.incrementAndGet() > 1;
            }
        };
        RecordingSink sink = new RecordingSink();

        Downloader.Result result = Downloader.publish(file, "video/mp4", sink, cancelsOnceCopying);

        assertEquals(result.toString(), Downloader.Status.CANCELLED, result.status);
        assertTrue("the gallery was never opened, so nothing was tested", sink.opened);
        assertTrue("the opened row was left behind", sink.abandoned);
        assertFalse(sink.committed);
        assertTrue(file.delete());
    }

    /** The last write and flush still belong to the cancellable part of the save. */
    @Test public void cancellingTheFinalWriteOrFlushNeverPublishesOrReportsSuccess() throws Exception {
        for (String boundary : new String[] { "write", "flush", "flush failure" }) {
            PublicationBarrier stream = new PublicationBarrier(boundary.startsWith("flush") ? "flush" : "write",
                    boundary.endsWith("failure"));
            File file = publicationFile();
            Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(gallery.nextId), stream);
            CompletableFuture<SaveControl.Save> control = new CompletableFuture<>();
            CompletableFuture<Downloader.Result> result = new CompletableFuture<>();
            Thread worker = MediaDownload.start(context, true, (writer, progress) -> {
                control.complete((SaveControl.Save) progress);
                Downloader.Result saved = Downloader.publish(file, "video/mp4", writer, Downloader.after(0, progress));
                result.complete(saved);
                return saved;
            });
            try {
                assertTrue(boundary, stream.entered.await(20, TimeUnit.SECONDS));
                SaveControl.Save save = control.get(20, TimeUnit.SECONDS);
                assertTrue("cancel wasn't accepted before publication at " + boundary, SaveControl.cancel(save.id));
                assertFalse("a repeated cancel was accepted", SaveControl.cancel(save.id));
                assertFalse(shown(SaveControl.TAG, save.id));
                stream.leave.countDown();
                finish(worker);
                assertEquals(boundary, Downloader.Status.CANCELLED, result.get(20, TimeUnit.SECONDS).status);
                assertEquals(SaveControl.State.CANCELLED, save.state());
                assertEquals("Save canceled", ShadowToast.getTextOfLatestToast());
                assertEquals("cancellation published a row", 0, gallery.updates);
                assertTrue("cancellation left a gallery row", gallery.rows.isEmpty());
                assertTrue(pendingList().isEmpty());
            } finally {
                stream.leave.countDown();
                finish(worker);
                Downloader.delete(file);
            }
        }
    }

    /** Once commit starts, a late Cancel must leave the running feedback intact. */
    @Test public void publicationDeclinesLateCancellationUntilTheGalleryCommitSucceeds() throws Exception {
        PublicationBarrier stream = new PublicationBarrier("close", false);
        File file = publicationFile();
        Uri row = gallery.videoUri(gallery.nextId);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(row, stream);
        CompletableFuture<SaveControl.Save> control = new CompletableFuture<>();
        CompletableFuture<Downloader.Result> result = new CompletableFuture<>();
        Thread worker = MediaDownload.start(context, true, (writer, progress) -> {
            control.complete((SaveControl.Save) progress);
            Downloader.Result saved = Downloader.publish(file, "video/mp4", writer, Downloader.after(0, progress));
            result.complete(saved);
            return saved;
        });
        try {
            assertTrue(stream.entered.await(20, TimeUnit.SECONDS));
            SaveControl.Save save = control.get(20, TimeUnit.SECONDS);
            assertEquals(SaveControl.State.PUBLISHING, save.state());
            assertFalse("publication was claimed twice", save.publishing());
            assertFalse("Cancel was accepted after publication claimed the save", SaveControl.cancel(save.id));
            assertFalse("a repeated late cancel was accepted", SaveControl.cancel(save.id));
            assertTrue("the publishing save disappeared", SaveControl.running().stream().anyMatch(s -> s.id == save.id));
            assertTrue("the publishing notification disappeared", shown(SaveControl.TAG, save.id));
            Notification note = saveNotification();
            assertTrue("a publishing notification still offers Cancel", note.actions == null || note.actions.length == 0);
            stream.leave.countDown();
            finish(worker);
            assertEquals(Downloader.Status.OK, result.get(20, TimeUnit.SECONDS).status);
            assertEquals(SaveControl.State.SUCCEEDED, save.state());
            assertFalse("a finished save could publish twice", save.publishing());
            assertEquals(Integer.valueOf(0), gallery.rows.get(ContentUris.parseId(row)).getAsInteger(MediaStore.MediaColumns.IS_PENDING));
            assertEquals("publication ran more than once", 1, gallery.updates);
            assertFalse(SaveControl.cancel(save.id));
            assertTrue(pendingList().isEmpty());
        } finally {
            stream.leave.countDown();
            finish(worker);
            Downloader.delete(file);
        }
    }

    /** A claimed publication can still fail while closing or updating the pending row. */
    @Test public void postClaimCloseAndUpdateFailuresReportFailureAndRemoveTheRow() throws Exception {
        for (boolean failClose : new boolean[] { true, false }) {
            PublicationBarrier stream = new PublicationBarrier("close", failClose);
            File file = publicationFile();
            Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(gallery.nextId), stream);
            gallery.refuseUpdate = !failClose;
            CompletableFuture<SaveControl.Save> control = new CompletableFuture<>();
            CompletableFuture<Downloader.Result> result = new CompletableFuture<>();
            Thread worker = MediaDownload.start(context, true, (writer, progress) -> {
                control.complete((SaveControl.Save) progress);
                Downloader.Result saved = Downloader.publish(file, "video/mp4", writer, progress);
                result.complete(saved);
                return saved;
            });
            try {
                assertTrue(stream.entered.await(20, TimeUnit.SECONDS));
                SaveControl.Save save = control.get(20, TimeUnit.SECONDS);
                assertFalse("a claimed publication accepted Cancel", SaveControl.cancel(save.id));
                stream.leave.countDown();
                finish(worker);
                assertEquals(Downloader.Status.WRITE_ERROR, result.get(20, TimeUnit.SECONDS).status);
                assertEquals(SaveControl.State.FAILED, save.state());
                assertEquals("The file couldn't be copied into your phone's gallery. Try again in a moment.",
                        ShadowToast.getTextOfLatestToast());
                assertEquals(Downloader.Cause.GALLERY, SaveControl.lastCause());
                assertTrue("failed publication left a gallery row", gallery.rows.isEmpty());
                assertTrue(pendingList().isEmpty());
            } finally {
                stream.leave.countDown();
                finish(worker);
                gallery.refuseUpdate = false;
                Downloader.delete(file);
            }
        }
    }

    @Test public void aFailedFlushMarksTheSaveFailedAndRemovesItsPendingRow() throws Exception {
        File file = publicationFile();
        SaveControl.Save save = SaveControl.begin(context, true);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(gallery.nextId),
                new OutputStream() {
                    @Override public void write(int value) { }
                    @Override public void flush() throws IOException { throw new IOException("gallery refused flush"); }
                });
        try {
            Downloader.Result result = Downloader.publish(file, "video/mp4", new MediaStoreWriter(context, true), save);
            assertEquals(Downloader.Status.WRITE_ERROR, result.status);
            assertEquals(SaveControl.State.FAILED, save.state());
            assertFalse(save.publishing());
            assertFalse(SaveControl.cancel(save.id));
            assertEquals(0, gallery.updates);
            assertTrue(gallery.rows.isEmpty());
            assertTrue(pendingList().isEmpty());
        } finally {
            save.end();
            Downloader.delete(file);
        }
    }

    private File publicationFile() throws IOException {
        File file = File.createTempFile("publication", ".mp4", context.getCacheDir());
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(MP4_HEAD);
        }
        return file;
    }

    private static final class PublicationBarrier extends OutputStream {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch leave = new CountDownLatch(1);
        private final String boundary;
        private final boolean fail;

        PublicationBarrier(String boundary, boolean fail) {
            this.boundary = boundary;
            this.fail = fail;
        }

        @Override public void write(int value) throws IOException { stopAt("write"); }
        @Override public void write(byte[] bytes, int offset, int count) throws IOException { stopAt("write"); }
        @Override public void flush() throws IOException { stopAt("flush"); }
        @Override public void close() throws IOException { stopAt("close"); }

        private void stopAt(String here) throws IOException {
            if (!here.equals(boundary)) return;
            entered.countDown();
            try {
                if (!leave.await(20, TimeUnit.SECONDS)) throw new IOException("publication barrier timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            if (fail) throw new IOException("gallery rejected " + here);
        }
    }

    /**
     * A DASH save that's cancelled stays cancelled. It used to be a failed DASH save like any other,
     * and the single file behind it started the save over.
     */
    @Test
    public void aCancelledDashSaveDoesNotFallBackToTheSingleFile() throws Exception {
        MediaDownload.policyForTests = policy;
        server.serveGenerated("/track.mp4", "video/mp4", MP4_HEAD, 50 * MIB, 2 * MIB, release);
        server.serveGenerated("/single.mp4", "video/mp4", MP4_HEAD, 64 * 1024, Long.MAX_VALUE, null);
        DashManifest.Track track = new DashManifest.Track("video/mp4", "avc1.64001f", 1920, 1080, 4_000_000,
                server.origin() + "/track.mp4");

        Thread worker = MediaDownload.start(context, true,
                MediaDownload.dashJob(context, track, null, server.origin() + "/single.mp4"));
        Notification moving = waitForNotification(SaveProgressTest::moving, "progress");
        assertTrue(SaveControl.cancel(saveId(moving)));
        finish(worker);

        assertEquals("Save canceled", ShadowToast.getTextOfLatestToast());
        assertEquals("the single file was fetched after the cancel", 0, server.hits("/single.mp4"));
        // The fallback would reuse the cancelled progress and stop before a request, so the log
        // is what shows whether it was tried at all.
        String report = LogBufferManager.buildExportText();
        assertFalse("a cancelled DASH save went on to the single file:\n" + report,
                report.contains("the DASH save ended with"));
        assertEquals(0, gallery.inserts.size());
        assertEquals(0, workFiles());
    }

    @Test public void aFailedDashPublicationDoesNotFetchTheSingleFileAgain() throws Exception {
        FragmentedMp4ForTests picture = FragmentedMp4ForTests.picture("vp09", 720, 1280, 1000);
        picture.fragment(0L).add(32, 1000, FragmentedMp4ForTests.SYNC, 0);
        byte[] body = picture.build();
        server.serve("/joined.mp4", 200, "video/mp4", body, body.length);
        server.serve("/fallback.mp4", 200, "video/mp4", MP4_HEAD, MP4_HEAD.length);
        DashManifest.Track video = new DashManifest.Track("video/mp4", "vp09.00.40.08", 720, 1280, 1000,
                server.origin() + "/joined.mp4");
        gallery.refuseUpdate = true;
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(gallery.nextId), published);
        Thread worker = MediaDownload.start(context, true,
                MediaDownload.dashJob(context, video, null, server.origin() + "/fallback.mp4"));
        finish(worker);
        assertEquals("The file couldn't be copied into your phone's gallery. Try again in a moment.",
                ShadowToast.getTextOfLatestToast());
        assertEquals(Downloader.Cause.GALLERY, SaveControl.lastCause());
        assertEquals("a terminal gallery failure fetched the fallback", 0, server.hits("/fallback.mp4"));
        assertEquals(1, gallery.updates);
        assertTrue(gallery.rows.isEmpty());
        assertTrue(pendingList().isEmpty());
        assertEquals(0, workFiles());
    }

    /**
     * The flag alone stops a fetch between two reads, with no connection closed under it: the
     * close is what ends a read that's waiting, and each is tested apart from the other.
     */
    @Test
    public void aCancelSeenBetweenReadsStopsTheFetchAndDeletesItsFile() throws IOException {
        server.serveGenerated("/two.mp4", "video/mp4", MP4_HEAD, 2 * MIB, Long.MAX_VALUE, null);
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger closers = new AtomicInteger();
        Downloader.Progress cancelsAfterThreeReads = new Downloader.Progress() {
            @Override public void transferred(long done, long total) {
                reads.incrementAndGet();
            }

            @Override public void reading(Runnable close) {
                if (close != null) closers.incrementAndGet();
            }

            @Override public boolean cancelled() {
                return reads.get() >= 3;
            }
        };
        File into = File.createTempFile("video", ".part", DashSave.workFolder(context));

        Downloader.Result result = Downloader.fetch(server.origin() + "/two.mp4", Downloader.Kind.VIDEO, into, policy,
                Downloader.MAX_BYTES, cancelsAfterThreeReads);

        assertEquals(result.toString(), Downloader.Status.CANCELLED, result.status);
        assertFalse("a cancelled fetch left its file", into.exists());
        assertTrue("the fetch read on long after the cancel: " + reads.get(), reads.get() < 10);
        // What a cancel needs to end a read waiting on the network, on Android.
        assertEquals("the fetch never handed over a way to close its connection", 1, closers.get());
    }

    /**
     * A read waiting on a server that went quiet sees no flag. On Android the cancel closes the
     * connection under it and it ends at once; the JDK these tests run on waits out the read
     * timeout instead, so it's short here. Either way the notification goes the moment Cancel is
     * pressed, and the save ends cancelled, not as a network failure.
     */
    @Test
    public void aCancelDuringAStalledReadTakesTheNotificationAwayAtOnce() throws Exception {
        Downloader.readTimeoutMs = 2_000;
        try {
            server.serveHeld("/quiet.mp4", "video/mp4", MP4_HEAD, 10 * MIB, MIB, release);
            Thread worker = save("/quiet.mp4");
            Notification started = saveNotification();
            assertNotNull(started);
            Thread.sleep(700);
            assertTrue("the save ended before the server went quiet", worker.isAlive());

            assertTrue(SaveControl.cancel(saveId(started)));
            assertNull("the notification stayed after Cancel", saveNotification());
            finish(worker);

            assertEquals("Save canceled", ShadowToast.getTextOfLatestToast());
            assertEquals(0, workFiles());
            String report = LogBufferManager.buildExportText();
            assertTrue(report, report.contains("save finished: CANCELLED"));
        } finally {
            Downloader.readTimeoutMs = 20_000;
        }
    }

    /** Cancel closes the connection being read, and one opened after the cancel as soon as it's handed over. */
    @Test
    public void cancelClosesTheConnectionTheSaveIsReading() throws Exception {
        SaveControl.Save save = SaveControl.begin(context, true);
        Probe before = new Probe();
        save.reading(before);

        assertTrue(SaveControl.cancel(save.id));
        assertTrue("cancel left the connection open", before.closed.await(5, java.util.concurrent.TimeUnit.SECONDS));

        Probe after = new Probe();
        save.reading(after);
        assertTrue("a connection handed over after the cancel stayed open",
                after.closed.await(5, java.util.concurrent.TimeUnit.SECONDS));
        save.end();
    }

    /** A connection's closer that only records being run. */
    private static final class Probe implements Runnable {
        final CountDownLatch closed = new CountDownLatch(1);

        @Override public void run() {
            closed.countDown();
        }
    }

    /** The writer lists its row as soon as it's inserted, and crosses it off once it's settled. */
    @Test
    public void theWriterListsItsRowWhileItIsPending() throws IOException {
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(1), published);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(2), published);

        MediaStoreWriter kept = new MediaStoreWriter(context, true);
        kept.open("video/mp4");
        assertEquals(java.util.Collections.singleton(gallery.videoUri(1).toString()), pendingList());
        kept.commit();
        assertTrue(pendingList().isEmpty());

        MediaStoreWriter dropped = new MediaStoreWriter(context, true);
        dropped.open("video/mp4");
        assertEquals(java.util.Collections.singleton(gallery.videoUri(2).toString()), pendingList());
        dropped.abandon();
        assertTrue(pendingList().isEmpty());
        assertFalse(gallery.rows.containsKey(2L));
    }

    @Test
    public void aPendingRowTheGalleryRefusesToDeleteStaysOnTheRetryList() throws IOException {
        Uri row = gallery.videoUri(1);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(row, published);
        MediaStoreWriter writer = new MediaStoreWriter(context, true);
        writer.open("video/mp4");
        gallery.refuseDeletion = true;

        writer.abandon();

        assertTrue("the failed deletion took the row off the retry list", pendingList().contains(row.toString()));
        assertTrue("the test gallery removed the row", gallery.rows.containsKey(1L));
        gallery.refuseDeletion = false;
        SaveLeftovers.sweepOnce(context);
        assertFalse("a later sweep did not remove the row", gallery.rows.containsKey(1L));
        assertTrue(pendingList().isEmpty());
    }

    @Test
    public void aPendingRowSweepRetriesAfterTheGalleryThrows() {
        Uri row = row(1);
        SaveLeftovers.pending(context, row);
        gallery.throwOnDelete = true;

        SaveLeftovers.sweepOnce(context);

        assertTrue("the failed sweep forgot the row", pendingList().contains(row.toString()));
        assertTrue(gallery.rows.containsKey(ContentUris.parseId(row)));
        gallery.throwOnDelete = false;
        SaveLeftovers.forgetSweepForTests();
        SaveLeftovers.sweepOnce(context);
        assertFalse("the next process did not remove the row", gallery.rows.containsKey(ContentUris.parseId(row)));
        assertTrue(pendingList().isEmpty());
    }

    @Test
    public void progressTextSaysHowMuchOfHowMuch() {
        assertNull(SaveControl.progressText(0, 100 * MIB));
        assertEquals("4.2 MB of 100 MB", SaveControl.progressText((long) (4.2 * MIB), 100 * MIB));
        assertEquals("12 MB so far", SaveControl.progressText(12 * MIB, -1));
    }

    @Test
    public void timeAndRateReadPlainly() {
        assertEquals("0:00 elapsed", SaveControl.elapsedText(-5));
        assertEquals("0:42 elapsed", SaveControl.elapsedText(42_999));
        assertEquals("59:59 elapsed", SaveControl.elapsedText(3_599_000));
        assertEquals("1:00:00 elapsed", SaveControl.elapsedText(3_600_000));
        assertEquals("1 KB a second", SaveControl.rateText(1));
        assertEquals("850 KB a second", SaveControl.rateText(850 * 1024));
        assertEquals("1023 KB a second", SaveControl.rateText(1023 * 1024));
        // Just under a megabyte rounds to 1024 KB, so it's said as a megabyte.
        assertEquals("1.0 MB a second", SaveControl.rateText(MIB - 400));
        assertEquals("1.3 MB a second", SaveControl.rateText((long) (1.3 * MIB)));
        assertEquals("12 MB a second", SaveControl.rateText(12 * MIB));
    }

    /** Two megabytes in two seconds, of a file of no announced length: the same line in both places. */
    @Test public void theNotificationAndTheListGiveTheSameRateAndTime() {
        SavesForTests.FakeClock clock = SavesForTests.fakeClock();
        SaveControl.Save save = SaveControl.begin(context, true);
        try {
            for (int quarter = 0; quarter <= 8; quarter++) {
                if (quarter > 0) clock.advanceMillis(250);
                save.transferred(quarter * MIB / 4, -1);
            }
            String shown = String.valueOf(saveNotification().extras.getCharSequence(Notification.EXTRA_TEXT));
            assertEquals("Downloading\n2.0 MB so far\n1.0 MB a second, 0:02 elapsed", shown);
            assertEquals(shown, SaveControl.status(SaveControl.running().get(0)));
        } finally { save.end(); }
    }

    /** The expanded card shows every line, so the download's rate and time aren't cut after the second. */
    @Test public void theExpandedNotificationKeepsTheRateAndTimeLine() {
        SavesForTests.FakeClock clock = SavesForTests.fakeClock();
        SaveControl.Save save = SaveControl.begin(context, true);
        try {
            for (int quarter = 0; quarter <= 8; quarter++) {
                if (quarter > 0) clock.advanceMillis(250);
                save.transferred(quarter * MIB / 4, 4 * MIB);
            }
            Notification shown = saveNotification();
            assertEquals(Notification.BigTextStyle.class.getName(), shown.extras.getString(Notification.EXTRA_TEMPLATE));
            assertEquals("Downloading\n2.0 MB of 4.0 MB\n1.0 MB a second, 0:02 elapsed",
                    String.valueOf(shown.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)));
            save.joining();
            assertEquals("Joining the picture and sound\n0:02 elapsed",
                    String.valueOf(saveNotification().extras.getCharSequence(Notification.EXTRA_BIG_TEXT)));
        } finally { save.end(); }
    }

    /** Joining and copying move no bytes over the line, so they give no rate, and the time goes on. */
    @Test public void aJoinOrACopyClaimsNoRateAndTheTickKeepsTheTimeGoing() {
        SavesForTests.FakeClock clock = SavesForTests.fakeClock();
        SaveControl.Save save = SaveControl.begin(context, true);
        try {
            for (int quarter = 0; quarter <= 8; quarter++) {
                if (quarter > 0) clock.advanceMillis(250);
                save.transferred(quarter * MIB / 4, 2 * MIB);
            }
            save.joining();
            assertEquals("Joining the picture and sound\n0:02 elapsed", text(saveNotification()));
            clock.advanceMillis(1_000);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
            assertEquals("Joining the picture and sound\n0:03 elapsed", text(saveNotification()));
            assertEquals(text(saveNotification()), SaveControl.status(SaveControl.running().get(0)));
            save.saving();
            assertEquals("Copying to the gallery\n0:03 elapsed", text(saveNotification()));
        } finally { save.end(); }
    }

    /** A tick right after a download's own tell waits its turn, so the list is told at most twice a second. */
    @Test public void theTickKeepsToTheGapAfterADownloadTold() {
        SavesForTests.FakeClock clock = SavesForTests.fakeClock();
        SaveControl.Save save = SaveControl.begin(context, true);
        AtomicInteger told = new AtomicInteger();
        SaveControl.Watcher counting = told::incrementAndGet;
        SaveControl.watch(counting);
        try {
            clock.advanceMillis(900);
            save.transferred(MIB, 4 * MIB);
            int afterDownload = told.get();
            // The tick comes while the fake clock still reads the moment of that tell.
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
            assertEquals("the tick told again straight after the download did", afterDownload, told.get());
            clock.advanceMillis(1_000);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
            assertEquals("the tick stopped telling once the gap had passed", afterDownload + 1, told.get());
        } finally {
            save.end();
            SaveControl.unwatch(counting);
        }
    }

    private static String text(Notification shown) {
        return String.valueOf(shown.extras.getCharSequence(Notification.EXTRA_TEXT));
    }

    /** The end of a save takes its tick away: nothing is told or shown for it afterwards. */
    @Test public void theTickEndsWithTheSave() {
        SaveControl.Save save = SaveControl.begin(context, true);
        AtomicInteger told = new AtomicInteger();
        SaveControl.Watcher counting = told::incrementAndGet;
        SaveControl.watch(counting);
        try {
            assertTrue("a running save has no tick", save.ticks.hasCallbacks(save.tick));
            save.end();
            int ended = told.get();
            assertFalse("the tick outlived the save", save.ticks.hasCallbacks(save.tick));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5));
            assertEquals("a save that ended still told the settings about itself", ended, told.get());
            assertNull("a save that ended showed its notification again", saveNotification());
        } finally {
            // A failed check above still ends it, or it would run on into the next test.
            save.end();
            SaveControl.unwatch(counting);
        }
    }

    /**
     * The single file fetched after a join fails starts from nothing, and the rate starts over with
     * it instead of reading the drop from 4 MB as a negative speed.
     */
    @Test public void theSingleFileAfterAFailedJoinIsMeasuredAfresh() {
        SavesForTests.FakeClock clock = SavesForTests.fakeClock();
        SaveControl.Save save = SaveControl.begin(context, true);
        try {
            for (int quarter = 0; quarter <= 4; quarter++) {
                if (quarter > 0) clock.advanceMillis(250);
                save.transferred(quarter * MIB, 4 * MIB);
            }
            save.joining();
            clock.advanceMillis(250);
            save.transferred(MIB / 2, 10 * MIB);
            assertEquals("Downloading\n0.5 MB of 10 MB\n0:01 elapsed", SaveControl.status(SaveControl.running().get(0)));
            clock.advanceMillis(1_000);
            save.transferred(3 * MIB / 2, 10 * MIB);
            assertEquals("Downloading\n1.5 MB of 10 MB\n1.0 MB a second, 0:02 elapsed", SaveControl.status(SaveControl.running().get(0)));
        } finally { save.end(); }
    }

    /**
     * A DASH save fetches the picture and then the sound, the sound counted on from the picture's
     * bytes (Downloader.after), so the rate goes straight on rather than start over or jump.
     */
    @Test public void theSoundTrackCarriesOnThePicturesRate() {
        SavesForTests.FakeClock clock = SavesForTests.fakeClock();
        SaveControl.Save save = SaveControl.begin(context, true);
        try {
            for (int quarter = 0; quarter <= 8; quarter++) {
                if (quarter > 0) clock.advanceMillis(250);
                save.transferred(quarter * MIB / 2, 4 * MIB);
            }
            Downloader.Progress sound = Downloader.after(4 * MIB, save);
            for (int quarter = 1; quarter <= 4; quarter++) {
                clock.advanceMillis(250);
                sound.transferred(quarter * MIB / 2, 2 * MIB);
            }
            assertEquals("Downloading\n6.0 MB of 6.0 MB\n2.0 MB a second, 0:03 elapsed",
                    SaveControl.status(SaveControl.running().get(0)));
        } finally { save.end(); }
    }

    /** Counts what the gallery was given, so a large save needs no array of its size. */
    static final class CountingStream extends OutputStream {
        final java.util.concurrent.atomic.AtomicLong count = new java.util.concurrent.atomic.AtomicLong();

        @Override public void write(int b) {
            count.incrementAndGet();
        }

        @Override public void write(byte[] bytes, int offset, int length) {
            count.addAndGet(length);
        }
    }

    private static final class RecordingSink implements Downloader.Sink {
        boolean opened;
        boolean committed;
        boolean abandoned;

        @Override public OutputStream open(String mime) {
            opened = true;
            return new CountingStream();
        }

        @Override public void commit() {
            committed = true;
        }

        @Override public void abandon() {
            abandoned = true;
        }
    }

    /** MediaStore's video table, as a save and the leftover sweep use it. */
    public static final class Gallery extends ContentProvider {
        /** Read on the test's thread while a sweep deletes from it on a worker. */
        final Map<Long, ContentValues> rows = new java.util.concurrent.ConcurrentHashMap<>();
        final List<Uri> inserts = new ArrayList<>();
        boolean refuseDeletion;
        boolean throwOnDelete;
        boolean refuseUpdate;
        int updates;
        /** While set, a deletion waits for it: a gallery that's slow while Facebook starts. */
        volatile CountDownLatch holdDeletes;
        final CountDownLatch deleteAsked = new CountDownLatch(1);
        private long nextId = 1;

        Uri videoUri(long id) {
            return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
        }

        @Override public boolean onCreate() {
            return true;
        }

        @Override public Uri insert(Uri uri, ContentValues values) {
            long id = nextId++;
            rows.put(id, new ContentValues(values));
            Uri item = ContentUris.withAppendedId(uri, id);
            inserts.add(item);
            return item;
        }

        @Override public Cursor query(Uri uri, String[] projection, String selection,
                String[] selectionArgs, String sortOrder) {
            return new MatrixCursor(projection == null ? new String[0] : projection);
        }

        @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            updates++;
            if (refuseUpdate) return 0;
            ContentValues row = rows.get(ContentUris.parseId(uri));
            if (row == null) return 0;
            row.putAll(values);
            return 1;
        }

        /** Honours the one selection the sweep uses, the way MediaStore does for a row named by id. */
        @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
            deleteAsked.countDown();
            CountDownLatch hold = holdDeletes;
            if (hold != null) {
                try {
                    hold.await(20, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (throwOnDelete) throw new IllegalStateException("gallery unavailable");
            if (refuseDeletion) return 0;
            long id = ContentUris.parseId(uri);
            ContentValues row = rows.get(id);
            if (row == null) return 0;
            if ((MediaStore.MediaColumns.IS_PENDING + "=1").equals(selection)
                    && !Integer.valueOf(1).equals(row.getAsInteger(MediaStore.MediaColumns.IS_PENDING))) {
                return 0;
            }
            rows.remove(id);
            return 1;
        }

        @Override public String getType(Uri uri) {
            return "video/mp4";
        }
    }
}
