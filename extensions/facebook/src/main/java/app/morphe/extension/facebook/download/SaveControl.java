/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsEntry;
import app.morphe.extension.shared.L10n;

/**
 * A running save as the person saving sees it: a notification that shows how far it has got and
 * has a Cancel button, for as long as the save runs.
 *
 * <p>A save used to say "Saving..." when it started and nothing more until it ended, so a large
 * video looked stuck and nothing could stop one. The notification goes when the save ends, and the
 * toast that says how it ended stays as it was. Successful publication leaves generic file actions.
 * A video WhatsApp may refuse leaves one note of its
 * own behind, with a button to the switch that avoids it ({@link #showRefused}).
 *
 * <p>Cancel is a broadcast to a receiver registered in Facebook's process, not a component added to
 * its manifest, since a patch that adds one changes what other apps can reach. From Android 13 the
 * receiver isn't exported. Below that a receiver registered at run time always is, so every Cancel
 * carries a token this process made up, and a broadcast without it is ignored.
 *
 * <p>With Facebook's notifications off, or this channel switched off, a save shows a toast at each
 * end and no notification, and the first toast says where to cancel it. Hushfacebook's settings
 * list every running save under Downloads, with what it's doing and a Cancel of its own
 * ({@link #running}, {@link #watch}), so a save can be stopped either way. Nothing of a save outlives it there: no address, no name, no history.
 */
public final class SaveControl {

    private SaveControl() {}

    static final String ACTION_CANCEL = "app.morphe.extension.facebook.CANCEL_SAVE";
    static final String EXTRA_ID = "app.morphe.extension.facebook.SAVE_ID";
    static final String EXTRA_TOKEN = "app.morphe.extension.facebook.SAVE_TOKEN";
    static final String CHANNEL = "hushfacebook_saves";
    /** Every notification here is posted under this tag, so its id can't replace one of Facebook's. */
    static final String TAG = "hushfacebook-save";
    /**
     * The tag of the note a finished save leaves ({@link #showRefused}). Not {@link #TAG}: a save
     * ending takes its own number down there, and {@link #removeStale} everything that isn't running.
     */
    static final String SAVED_TAG = "hushfacebook-saved";
    /** One note at a time: the next save's replaces it rather than stacking up. */
    static final int SAVED_ID = 1;
    /**
     * The most finished-save cards ({@link #showCompleted}) kept up at once. Android lets an app hold
     * about fifty notifications and drops the next without a word, so after a run of saves nobody
     * cleared, the next save's progress card, Cancel and all, would never show. Facebook's own count
     * towards the same fifty.
     */
    static final int COMPLETED_KEPT = 20;
    /** Which finished-save card came first, for {@link #trimCompleted} when two were posted in the same instant. */
    static final String EXTRA_COMPLETED_ORDER = "app.morphe.extension.facebook.COMPLETED_ORDER";
    private static final AtomicInteger COMPLETED_ORDER = new AtomicInteger();

    /** Unguessable, and new in every process. */
    private static final String TOKEN = UUID.randomUUID().toString();

    /** The shortest gap between two updates of a notification. The system drops faster ones anyway. */
    private static final long UPDATE_GAP_NANOS = 500_000_000L;

    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
    private static final Map<Integer, Save> RUNNING = new ConcurrentHashMap<>();
    private static final Set<Watcher> WATCHERS = new CopyOnWriteArraySet<>();

    /** What a running save is doing. */
    public enum Phase { DOWNLOADING, JOINING, SAVING, PUBLISHING }

    enum State { ACTIVE, PUBLISHING, SUCCEEDED, FAILED, CANCELLED }

    /**
     * Told when a save starts, moves on to its next phase or ends, and at most twice a second while
     * it downloads. On the save's own thread, or the one that cancelled it.
     */
    public interface Watcher {
        void savesChanged();
    }

    public static void watch(Watcher watcher) {
        WATCHERS.add(watcher);
    }

    public static void unwatch(Watcher watcher) {
        WATCHERS.remove(watcher);
    }

    private static void tell() {
        for (Watcher watcher : WATCHERS) {
            try {
                watcher.savesChanged();
            } catch (Throwable t) {
                MediaDownload.failure(() -> "could not tell the settings about a save", t);
            }
        }
    }

    /** One running save as the settings list it: its number, kind, phase and bytes so far. */
    public static final class Running {
        public final int id;
        public final boolean video;
        public final Phase phase;
        public final long done;
        public final long total;
        public final boolean canCancel;

        Running(Save save) {
            id = save.id;
            video = save.video;
            phase = save.phase;
            done = save.done;
            total = save.total;
            canCancel = save.state() == State.ACTIVE;
        }
    }

    /** The saves running now, oldest first. */
    public static List<Running> running() {
        List<Running> running = new ArrayList<>();
        for (Save save : RUNNING.values()) running.add(new Running(save));
        running.sort((one, other) -> Integer.compare(one.id, other.id));
        return running;
    }

    /** What [save] is doing, and how far it has got while it downloads, in the phone's language. */
    public static String status(Running save) {
        String phase;
        switch (save.phase) {
            case JOINING:
                phase = L10n.t("Joining the picture and sound");
                break;
            case SAVING:
            case PUBLISHING:
                phase = L10n.t("Copying to the gallery");
                break;
            default:
                phase = L10n.t("Downloading");
        }
        String progress = save.phase == Phase.DOWNLOADING ? progressText(save.done, save.total) : null;
        return progress == null ? phase : phase + "\n" + progress;
    }
    /** The application the receiver is registered on: one per process, and one per test. */
    private static Context listeningOn;

    /** Starts watching a save, and shows its notification when one can be shown. Never throws. */
    static Save begin(Context application, boolean video) {
        NotificationManager manager = notifications(application);
        Save save = new Save(application, NEXT_ID.getAndIncrement(), video, manager);
        RUNNING.put(save.id, save);
        if (save.manager != null) listen(application);
        save.show(-1, 0, -1);
        tell();
        return save;
    }

    /**
     * Takes down the notifications of saves that aren't running in this process, and answers how
     * many went. Android leaves a notification up when it ends the process that posted it, so a
     * save it stopped half way stayed on screen, ongoing, with a Cancel nothing answered, until a
     * later process happened to reuse its number.
     */
    static int removeStale(Context application) {
        try {
            NotificationManager manager = application.getSystemService(NotificationManager.class);
            if (manager == null) return 0;
            int removed = 0;
            for (StatusBarNotification shown : manager.getActiveNotifications()) {
                if (!TAG.equals(shown.getTag()) || RUNNING.containsKey(shown.getId())) continue;
                manager.cancel(TAG, shown.getId());
                removed++;
            }
            return removed;
        } catch (Throwable t) {
            MediaDownload.failure(() -> "could not take down a stopped save's notification", t);
            return 0;
        }
    }

    /** Answers whether cancellation won before this save claimed publication. */
    public static boolean cancel(int id) {
        Save save = RUNNING.get(id);
        if (save == null) return false;
        return save.cancel();
    }

    /** The Cancel broadcast for save [id], as the notification's button sends it. For tests. */
    static Intent cancelIntent(Context application, int id) {
        return new Intent(ACTION_CANCEL)
            .setPackage(application.getPackageName())
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_TOKEN, TOKEN);
    }

    /**
     * After a save WhatsApp and some editors may refuse, with Save videos other apps can open off:
     * a note that says so under [saved], the usual end-of-save text, with a button that opens the
     * settings at that switch. A toast can't carry a button (#11, #14). Answers whether it's up.
     */
    static boolean showRefused(Context application, String saved) {
        NotificationManager manager = notifications(application);
        if (manager == null) return false;
        try {
            Intent open = SettingsEntry.settingIntent(application, Settings.DOWNLOAD_COMPATIBLE.key);
            PendingIntent button = PendingIntent.getActivity(application, SAVED_ID, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            String text = saved + "\n" + L10n.f(application, "Turn on %1$s to save videos that WhatsApp and these editors accept.",
                L10n.isolate(L10n.t(application, "Save videos other apps can open")));
            Notification note = new Notification.Builder(application, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(L10n.t(application, "WhatsApp and some editors may not accept this video"))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(button)
                .setAutoCancel(true)
                .addAction(new Notification.Action.Builder((Icon) null,
                    L10n.t(application, "Open the setting"), button).build())
                .build();
            manager.notify(SAVED_TAG, SAVED_ID, note);
            return true;
        } catch (Throwable t) {
            MediaDownload.failure(() -> "could not show the note about the saved format", t);
            return false;
        }
    }

    /** Finished file actions require both the atomic success state and the writer's committed row. */
    static void showCompleted(Save save, MediaStoreWriter writer) {
        if (save.state() != State.SUCCEEDED || writer.publishedUri() == null || writer.publishedMime() == null) return;
        NotificationManager manager = notifications(save.application);
        if (manager == null) return;
        try {
            PendingIntent open = SavedFileActions.button(save.application, writer.publishedUri(), writer.publishedMime(), false);
            PendingIntent share = SavedFileActions.button(save.application, writer.publishedUri(), writer.publishedMime(), true);
            Bundle order = new Bundle(1);
            order.putInt(EXTRA_COMPLETED_ORDER, COMPLETED_ORDER.getAndIncrement());
            Notification note = new Notification.Builder(save.application, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(save.video ? L10n.t(save.application, "Video saved") : L10n.t(save.application, "Photo saved"))
                .setCategory(Notification.CATEGORY_STATUS)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder((Icon) null, L10n.t(save.application, "Open"), open).build())
                .addAction(new Notification.Action.Builder((Icon) null, L10n.t(save.application, "Share"), share).build())
                .addExtras(order)
                .build();
            // URI identity also survives a process restarting its numeric running-save counter.
            String tag = SavedFileActions.TAG + writer.publishedUri();
            manager.notify(tag, 0, note);
            trimCompleted(manager, tag);
        } catch (Throwable failure) {
            // An exception's message may contain the local URI. Report only its class.
            String kind = failure.getClass().getSimpleName();
            MediaDownload.failure(() -> "could not show completed save actions (" + kind + ")", null);
        }
    }

    /**
     * Takes down the oldest finished-save cards so that, with [posted] (the one just sent, which
     * the system may not list yet), {@link #COMPLETED_KEPT} stay up. Never throws.
     */
    static void trimCompleted(NotificationManager manager, String posted) {
        try {
            List<StatusBarNotification> others = new ArrayList<>();
            for (StatusBarNotification shown : manager.getActiveNotifications()) {
                String tag = shown.getTag();
                if (tag != null && tag.startsWith(SavedFileActions.TAG) && !tag.equals(posted)) others.add(shown);
            }
            int keep = COMPLETED_KEPT - 1;
            if (others.size() <= keep) return;
            // Oldest first: by when the system took it, then by the order this process posted them
            // in, since the system's clock has a coarser grain than two saves ending together.
            others.sort((a, b) -> {
                int byTime = Long.compare(a.getPostTime(), b.getPostTime());
                return byTime != 0 ? byTime : Integer.compare(order(a), order(b));
            });
            for (int i = 0; i < others.size() - keep; i++) {
                manager.cancel(others.get(i).getTag(), others.get(i).getId());
            }
        } catch (Throwable failure) {
            String kind = failure.getClass().getSimpleName();
            MediaDownload.failure(() -> "could not take down old finished-save cards (" + kind + ")", null);
        }
    }

    private static int order(StatusBarNotification card) {
        Bundle extras = card.getNotification().extras;
        return extras == null ? -1 : extras.getInt(EXTRA_COMPLETED_ORDER, -1);
    }

    /** Facebook's notification service, when this channel may post, else {@code null}. */
    private static NotificationManager notifications(Context application) {
        try {
            NotificationManager manager = application.getSystemService(NotificationManager.class);
            if (manager == null || !manager.areNotificationsEnabled()) return null;

            NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
            if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                // The person switched this channel off in Facebook's notification settings.
                return null;
            }
            String name = L10n.t(application, "Hushfacebook saves");
            if (channel == null || !name.contentEquals(channel.getName())) {
                // Creating it again renames it in the phone's current language and changes
                // nothing the person set for it.
                NotificationChannel named = new NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_LOW);
                named.setDescription(L10n.t(application,
                    "How far a photo or video you're saving has got, with a button to cancel it"));
                named.setShowBadge(false);
                manager.createNotificationChannel(named);
            }
            return manager;
        } catch (Throwable t) {
            MediaDownload.failure(() -> "could not reach the notification service", t);
            return null;
        }
    }

    private static synchronized void listen(Context application) {
        if (listeningOn == application) return;
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        if (intent == null || !TOKEN.equals(intent.getStringExtra(EXTRA_TOKEN))) return;
                        int id = intent.getIntExtra(EXTRA_ID, -1);
                        if (cancel(id)) MediaDownload.info(() -> "save " + id + " cancelled from its notification");
                    } catch (Throwable t) {
                        MediaDownload.failure(() -> "could not cancel a save", t);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_CANCEL);
            if (Build.VERSION.SDK_INT >= 33) {
                application.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                application.registerReceiver(receiver, filter);
            }
            listeningOn = application;
        } catch (Throwable t) {
            MediaDownload.failure(() -> "could not listen for Cancel", t);
        }
    }

    /**
     * "4.2 MB of 100 MB", "12 MB so far", or {@code null} before any byte. The sentence is in
     * the phone's language and the numbers are written its way.
     */
    public static String progressText(long done, long total) {
        if (done <= 0) return null;
        return total > 0
            ? L10n.f("%1$s of %2$s", megabytes(done), megabytes(total))
            : L10n.f("%1$s so far", megabytes(done));
    }

    private static String megabytes(long bytes) {
        double megabytes = bytes / (1024.0 * 1024.0);
        Locale locale = L10n.locale();
        return megabytes < 10
            ? String.format(locale, "%.1f MB", megabytes)
            : String.format(locale, "%d MB", Math.round(megabytes));
    }

    /** One save: how far it has got, whether it was cancelled, and its notification. */
    static final class Save implements Downloader.Progress {
        final int id;
        final NotificationManager manager;
        private final Context application;
        private final boolean video;
        private final PendingIntent cancel;

        private final AtomicReference<State> state = new AtomicReference<>(State.ACTIVE);
        private volatile Phase phase = Phase.DOWNLOADING;
        private volatile long done;
        private volatile long total = -1;
        /** When the watchers were last told how far this save has got. */
        private long toldAt;
        /** Closes the connection the save is reading, handed over by Downloader. */
        private volatile Runnable closeReading;
        /** When the notification last changed; set by the first show, which begin() makes. */
        private long shownAt;
        private int shownPercent = -2;
        private boolean ended;

        Save(Context application, int id, boolean video, NotificationManager manager) {
            this.application = application;
            this.id = id;
            this.video = video;
            PendingIntent button = null;
            if (manager != null) {
                try {
                    // Its own request code, so two saves' buttons are two intents, not one updated.
                    button = PendingIntent.getBroadcast(application, id, cancelIntent(application, id),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                } catch (Throwable t) {
                    MediaDownload.failure(() -> "could not make the Cancel button", t);
                }
            }
            this.cancel = button;
            this.manager = button == null ? null : manager;
        }

        @Override
        public void transferred(long done, long total) {
            this.done = done;
            this.total = total;
            long now = System.nanoTime();
            // A download after a failed join is the single file, fetched from the start.
            if (phase != Phase.DOWNLOADING || now - toldAt >= UPDATE_GAP_NANOS) {
                phase = Phase.DOWNLOADING;
                toldAt = now;
                tell();
            }
            if (manager == null) return;
            int percent = total > 0 ? (int) Math.min(100L, done * 100L / total) : -1;
            if (System.nanoTime() - shownAt < UPDATE_GAP_NANOS) return;
            if (percent >= 0 && percent == shownPercent) return;
            show(percent, done, total);
        }

        @Override
        public void reading(Runnable close) {
            closeReading = close;
            // A cancel that came before this connection existed ends it now.
            if (cancelled()) close(close);
        }

        @Override
        public boolean cancelled() {
            return state() == State.CANCELLED;
        }

        State state() {
            return state.get();
        }

        @Override
        public void joining() {
            phase = Phase.JOINING;
            tell();
        }

        @Override
        public void saving() {
            phase = Phase.SAVING;
            tell();
        }

        @Override
        public boolean publishing() {
            if (!state.compareAndSet(State.ACTIVE, State.PUBLISHING)) return false;
            phase = Phase.PUBLISHING;
            tell();
            show(-1, 0, -1);
            return true;
        }

        @Override
        public void published(boolean success) {
            state.compareAndSet(State.PUBLISHING, success ? State.SUCCEEDED : State.FAILED);
            if (!success) state.compareAndSet(State.ACTIVE, State.FAILED);
        }

        /**
         * Stops the save. On Android, closing its connection ends a read that's waiting at once.
         * Checked with app_process on the API 36 emulator, 2026-09-25, against a server that sent
         * the headers and then nothing: over plain HTTP disconnect() returned in 0 ms and the read
         * threw "Socket closed" 6 ms later. Over HTTPS through Conscrypt (TLS 1.3, the path every
         * save takes) disconnect() ended the read within 1 ms in eight of nine runs and 12.5 ms in
         * the ninth. Closing the response stream from another thread instead threw "Unbalanced
         * enter/exit" and left the read waiting out its whole 20 s timeout, which is why the
         * closer is disconnect() (see Downloader). The notification goes straight away either
         * way, so the person sees the cancel take.
         */
        boolean cancel() {
            if (!state.compareAndSet(State.ACTIVE, State.CANCELLED)) return false;
            Runnable close = closeReading;
            if (close != null) close(close);
            end();
            return true;
        }

        /** The save is over, however it ended, and its notification goes. */
        void end() {
            state.compareAndSet(State.ACTIVE, State.FAILED);
            synchronized (this) {
                ended = true;
            }
            if (RUNNING.remove(id) != null) tell();
            if (manager == null) return;
            try {
                manager.cancel(TAG, id);
            } catch (Throwable t) {
                MediaDownload.failure(() -> "could not remove the save's notification", t);
            }
        }

        synchronized void show(int percent, long done, long total) {
            if (manager == null || ended) return;
            shownAt = System.nanoTime();
            shownPercent = percent;
            try {
                Notification.Builder builder = new Notification.Builder(application, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(video
                        ? L10n.t(application, "Saving a video")
                        : L10n.t(application, "Saving a photo"))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setCategory(Notification.CATEGORY_PROGRESS)
                    .setProgress(100, Math.max(0, percent), percent < 0);
                if (state() == State.ACTIVE) {
                    builder.addAction(new Notification.Action.Builder((Icon) null,
                        L10n.t(application, "Cancel"), cancel).build());
                }
                String text = phase == Phase.PUBLISHING ? L10n.t(application, "Copying to the gallery")
                    : progressText(done, total);
                if (text != null) builder.setContentText(text);
                manager.notify(TAG, id, builder.build());
            } catch (Throwable t) {
                MediaDownload.failure(() -> "could not show the save's progress", t);
            }
        }

        /**
         * Closing the connection is what ends a read waiting on the network. It's done off the
         * thread that asked, since a Cancel arrives on the one that draws Facebook.
         */
        private static void close(Runnable close) {
            Thread closer = new Thread(() -> {
                try {
                    close.run();
                } catch (Throwable ignored) {
                    // The read ends on its own at the timeout, and the flag stops it then.
                }
            }, "hushfacebook-save-cancel");
            closer.setDaemon(true);
            closer.start();
        }
    }
}
