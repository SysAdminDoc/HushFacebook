/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.Build;

import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import app.morphe.extension.facebook.settings.SettingsEntry;
import app.morphe.extension.shared.L10n;

/**
 * Queue reel saves (#449): with the switch on, a reel's Download button adds the reel to this queue
 * instead of saving it at once, and the queue saves one reel at a time in the background.
 *
 * <p>Every reel in it was tapped. Nothing here reads the feed or picks a reel of its own: the tap
 * takes down what the save needs ({@link MediaDownload.ReelSave}), the quality picked on that tap
 * included, and the queue starts that save when its turn comes. A turn waits for a free save slot
 * where a tap would give up with "too many saves at once", and goes through the same save as a tap,
 * with its own notification and Cancel.
 *
 * <p>The queue's own card counts what's saved, waiting and failed, with Pause and Cancel. Pause lets
 * the reel that's saving finish and holds the rest until Resume. Cancel stops that reel and drops the
 * rest. Downloads in the settings shows the same counts and buttons. A reel already waiting or saved
 * in this run is skipped, a failed one gets one more try at the back of the queue, and the run ends
 * when the queue empties.
 *
 * <p>A tap on the card opens Downloads at the queue (#462), which lists every reel in it with its own
 * Pause or Resume and Cancel. A paused reel keeps its place and the ones behind it go on. Pausing
 * the reel that's saving stops its save and puts it back at the front, to start over on Resume.
 * Cancel takes just that reel out.
 *
 * <p>All of it is in memory and gone when Facebook's process ends, like the list of running saves:
 * no reel's address, name or number outlives the run it was queued in.
 */
public final class ReelQueue {

    private ReelQueue() {}

    /** The queue's card is posted under this tag, apart from the saves' own, which a stale sweep takes down. */
    static final String TAG = "hushfacebook-reel-queue";
    static final String ACTION_PAUSE = "app.morphe.extension.facebook.REEL_QUEUE_PAUSE";
    static final String ACTION_RESUME = "app.morphe.extension.facebook.REEL_QUEUE_RESUME";
    static final String ACTION_CANCEL = "app.morphe.extension.facebook.REEL_QUEUE_CANCEL";
    static final String EXTRA_TOKEN = "app.morphe.extension.facebook.REEL_QUEUE_TOKEN";
    /** The key of the queue's row in Downloads, which a tap on its card opens the settings at (#462). */
    public static final String ROW_KEY = "reel_queue";
    /** Unguessable, and new in every process, as a save's Cancel is. */
    private static final String TOKEN = UUID.randomUUID().toString();

    /**
     * The most reels that wait at once. A reel's manifest can run to tens of kilobytes, and the
     * queue holds each in Facebook's memory until its turn.
     */
    public static final int MOST_WAITING = 50;

    /** One save of a queued reel. Tests set their own; on a phone it's {@link MediaDownload#saveQueued}. */
    interface Saver {
        @Nullable
        Downloader.Status save(Context application, MediaDownload.ReelSave reel, MediaDownload.Turn turn)
                throws InterruptedException;
    }

    static volatile Saver saverForTests;

    private static final class Entry {
        /** Its number in this run, which its row on the queue page goes by. */
        final int number;
        @Nullable final String videoId;
        final MediaDownload.ReelSave reel;
        boolean retried;
        /** Paused on the queue page: it keeps its place, and its turn waits until it's resumed. */
        boolean held;
        /** Paused while it saved: its turn was stopped, and it goes back to the front of the queue. */
        boolean putBack;
        /** Cancelled on the queue page while it saved: its turn was stopped, and it goes. */
        boolean dropped;

        Entry(int number, @Nullable String videoId, MediaDownload.ReelSave reel) {
            this.number = number;
            this.videoId = videoId;
            this.reel = reel;
        }
    }

    private static final Object LOCK = new Object();
    /** Held while the card goes up, so the last state read is the last one posted. Taken before LOCK, never inside it. */
    private static final Object CARD_LOCK = new Object();

    private static final ArrayDeque<Entry> WAITING = new ArrayDeque<>();
    /** Reels waiting or saving in this run, by video number. A reel with none is never skipped. */
    private static final Set<String> QUEUED = new HashSet<>();
    /** Reels this run saved, by video number, until the run ends. */
    private static final Set<String> SAVED = new HashSet<>();
    @Nullable private static Entry current;
    @Nullable private static MediaDownload.Turn currentTurn;
    @Nullable private static Thread worker;
    /** The latest run's thread, kept past {@link #endRun} until it has put its last card up. For tests. */
    @Nullable private static Thread lastWorker;
    private static boolean paused;
    private static boolean cancelled;
    private static int saved;
    private static int failed;
    /** The number the last reel to join this run got. */
    private static int numbered;
    @Nullable private static Context application;
    /** The application the receiver is registered on: one per process, and one per test. */
    @Nullable private static Context listeningOn;

    /** The queue as the settings and its card show it. */
    public static final class Status {
        public final int saved;
        public final int waiting;
        public final int failed;
        public final boolean saving;
        public final boolean paused;

        Status(int saved, int waiting, int failed, boolean saving, boolean paused) {
            this.saved = saved;
            this.waiting = waiting;
            this.failed = failed;
            this.saving = saving;
            this.paused = paused;
        }
    }

    /**
     * The queue now, or null when it holds no reel. It reads as paused, too, when nothing saves and
     * every reel left was paused on its own, since nothing goes until one is resumed.
     */
    @Nullable
    public static Status status() {
        synchronized (LOCK) {
            if (current == null && WAITING.isEmpty()) return null;
            boolean allHeld = current == null;
            for (Entry entry : WAITING) allHeld &= entry.held;
            return new Status(saved, WAITING.size(), failed, current != null, paused || allHeld);
        }
    }

    /** One reel in the queue, as its row on the queue page shows it (#462). */
    public static final class Item {
        public enum State { SAVING, WAITING, PAUSED }

        /** Its number in this run, which {@link #pause(int)}, {@link #resume(int)} and {@link #cancel(int)} take. */
        public final int number;
        /** Who posted it, or null when the tap didn't say. */
        @Nullable public final String owner;
        public final State state;

        Item(int number, @Nullable String owner, State state) {
            this.number = number;
            this.owner = owner;
            this.state = state;
        }
    }

    /** Every reel in the queue, the one saving first and then the rest in the order they'll go. */
    public static List<Item> items() {
        synchronized (LOCK) {
            List<Item> items = new ArrayList<>();
            // A reel cancelled while it saved is on its way out, and a paused one on its way back.
            if (current != null && !current.dropped) {
                items.add(item(current, current.held ? Item.State.PAUSED : Item.State.SAVING));
            }
            for (Entry entry : WAITING) items.add(item(entry, entry.held ? Item.State.PAUSED : Item.State.WAITING));
            return items;
        }
    }

    private static Item item(Entry entry, Item.State state) {
        String owner = entry.reel.details.owner;
        return new Item(entry.number, owner == null || owner.trim().isEmpty() ? null : owner.trim(), state);
    }

    /** "Reel from Ana" or, when the tap didn't say who posted it, "Reel 3", in the phone's language. */
    public static String name(Item item) {
        return item.owner != null ? L10n.f("Reel from %1$s", L10n.isolate(item.owner)) : L10n.f("Reel %1$d", item.number);
    }

    /** "Saving now", "Waiting" or "Paused", in the phone's language. */
    public static String state(Item item) {
        switch (item.state) {
            case SAVING: return L10n.t("Saving now");
            case PAUSED: return L10n.t("Paused");
            default: return L10n.t("Waiting");
        }
    }

    /** Whether a reel waits or saves, so Close Facebook when you leave it waits for the queue too. */
    static boolean holdsReels() {
        synchronized (LOCK) {
            return current != null || !WAITING.isEmpty();
        }
    }

    /** "Saving queued reels" or "Reel queue paused", in the phone's language. */
    public static String title(Status status) {
        return status.paused ? L10n.t("Reel queue paused") : L10n.t("Saving queued reels");
    }

    /** "2 saved, 3 waiting, 0 failed", in the phone's language. */
    public static String counts(Status status) {
        return L10n.f("%1$d saved, %2$d waiting, %3$d failed", status.saved, status.waiting, status.failed);
    }

    /**
     * Adds a tapped reel, [reel] as {@link MediaDownload#takeReel} took it down, and says on screen
     * what became of it. [videoId] skips a reel already waiting or saved in this run. Answers whether
     * it joined. Never throws: it runs inside Facebook's click handling.
     */
    static boolean add(Context context, @Nullable String videoId, @Nullable MediaDownload.ReelSave reel) {
        return add(context, videoId, reel, false);
    }

    /**
     * {@link #add}, where [quiet] leaves out the toasts that say how one reel went, for a batch of
     * reels (#449), which says how the whole batch went. The card still counts each one.
     */
    static boolean add(Context context, @Nullable String videoId, @Nullable MediaDownload.ReelSave reel, boolean quiet) {
        Context app = null;
        try {
            app = context == null ? null : context.getApplicationContext();
            if (app == null) app = context;
            if (app == null) return false;
            if (reel == null) {
                if (!quiet) Feedback.show(app, L10n.t(app, "Download failed. Try again in a moment."), true);
                return false;
            }
            String refused = null;
            String why = null;
            synchronized (LOCK) {
                if (videoId != null && QUEUED.contains(videoId)) {
                    why = "already waiting or saving";
                    refused = L10n.t(app, "Already in the reel queue");
                } else if (videoId != null && SAVED.contains(videoId)) {
                    why = "already saved in this run";
                    refused = L10n.t(app, "That reel is already saved");
                } else if (WAITING.size() >= MOST_WAITING) {
                    why = "the queue is full";
                    refused = L10n.t(app, "The reel queue is full. Try again when it's shorter.");
                } else {
                    application = app;
                    WAITING.addLast(new Entry(++numbered, videoId, reel));
                    if (videoId != null) QUEUED.add(videoId);
                    if (worker == null) startWorker();
                }
            }
            if (refused != null) {
                final String reason = why;
                MediaDownload.info(() -> "a reel was not queued: " + reason);
                if (!quiet) Feedback.show(app, refused, false);
                return false;
            }
            MediaDownload.info(() -> "a reel joined the queue");
            boolean carded = changed();
            if (quiet) return true;
            if (!carded) {
                Feedback.show(app, L10n.t(app,
                    "Added to the reel queue. To pause or cancel it, open Downloads in Hushfacebook settings."), true);
            } else if (!MediaDownload.quietSaves()) {
                Feedback.show(app, L10n.t(app, "Added to the reel queue"), false);
            }
            return true;
        } catch (Throwable t) {
            MediaDownload.failure(() -> "could not queue the reel", t);
            if (app != null) Feedback.show(app, L10n.t(app, "Download failed. Try again in a moment."), true);
            return false;
        }
    }

    /** Lets the reel that's saving finish and holds the rest until {@link #resume}. */
    public static void pause() {
        synchronized (LOCK) {
            if (current == null && WAITING.isEmpty()) return;
            paused = true;
        }
        MediaDownload.info(() -> "the reel queue paused");
        changed();
    }

    /** Starts the next reel's turn again after {@link #pause}, and lets every reel paused on its own go too. */
    public static void resume() {
        synchronized (LOCK) {
            boolean any = paused;
            for (Entry entry : WAITING) {
                any |= entry.held;
                entry.held = false;
            }
            if (current != null) {
                any |= current.held;
                current.held = false;
            }
            if (!any) return;
            paused = false;
            LOCK.notifyAll();
        }
        MediaDownload.info(() -> "the reel queue resumed");
        changed();
    }

    /**
     * Pauses one reel from the queue page (#462). Waiting, it keeps its place and the reels behind it
     * go on. Saving, its save stops and it goes back to the front, to start over on Resume.
     */
    public static void pause(int number) {
        synchronized (LOCK) {
            Entry entry = find(number);
            if (entry == null || entry.held) return;
            entry.held = true;
            if (entry == current) {
                entry.putBack = true;
                stopTurn();
            }
        }
        MediaDownload.info(() -> "a queued reel was paused");
        changed();
    }

    /** Lets one reel paused on the queue page take its turn again. */
    public static void resume(int number) {
        synchronized (LOCK) {
            Entry entry = find(number);
            if (entry == null || !entry.held) return;
            entry.held = false;
            LOCK.notifyAll();
        }
        MediaDownload.info(() -> "a queued reel was resumed");
        changed();
    }

    /** Takes one reel out of the queue from the queue page, stopping its save if it's the one saving. */
    public static void cancel(int number) {
        synchronized (LOCK) {
            Entry entry = find(number);
            if (entry == null) return;
            if (entry == current) {
                entry.dropped = true;
                stopTurn();
            } else {
                WAITING.remove(entry);
                if (entry.videoId != null) QUEUED.remove(entry.videoId);
                // The worker may be waiting on reels that were all paused, and this one was the last.
                LOCK.notifyAll();
            }
        }
        MediaDownload.info(() -> "a queued reel was cancelled");
        changed();
    }

    /** The reel with [number], saving or waiting, or null. Under LOCK. */
    @Nullable
    private static Entry find(int number) {
        if (current != null && current.number == number && !current.dropped) return current;
        for (Entry entry : WAITING) {
            if (entry.number == number) return entry;
        }
        return null;
    }

    /**
     * Stops the turn running now as its save's own Cancel would. A turn still waiting for a free
     * slot has no save yet, so the wait itself is what stops. Under LOCK.
     */
    private static void stopTurn() {
        MediaDownload.Turn turn = currentTurn;
        Thread running = worker;
        if (turn == null) return;
        if (turn.saveId >= 0) SaveControl.cancel(turn.saveId);
        else if (running != null) running.interrupt();
    }

    /** Drops every waiting reel and stops the one saving, and says so on screen. */
    public static void cancel() {
        Context app;
        synchronized (LOCK) {
            if (current == null && WAITING.isEmpty()) return;
            cancelled = true;
            paused = false;
            for (Entry entry : WAITING) {
                if (entry.videoId != null) QUEUED.remove(entry.videoId);
            }
            WAITING.clear();
            stopTurn();
            LOCK.notifyAll();
            app = application;
        }
        MediaDownload.info(() -> "the reel queue was cancelled");
        // A batch going through reels (#449) stops with the queue it fills.
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> ReelBatch.stop(null));
        changed();
        if (app != null) Feedback.show(app, L10n.t(app, "Reel queue canceled"), false);
    }

    private static void startWorker() {
        Thread thread = new Thread(ReelQueue::work, "hushfacebook-reel-queue");
        // As a save's worker: nothing left parked in Facebook's process once the queue empties.
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        worker = thread;
        lastWorker = thread;
        thread.start();
    }

    /** The queue's thread: one reel's turn after another until none waits, then the run ends. */
    private static void work() {
        while (true) {
            Entry entry;
            MediaDownload.Turn turn = null;
            Context app;
            Status last = null;
            boolean wasCancelled = false;
            synchronized (LOCK) {
                // Paused, or with every reel left paused on its own, the run waits: they're still in it.
                while ((entry = paused ? null : nextReady()) == null && !WAITING.isEmpty()) {
                    try {
                        LOCK.wait();
                    } catch (InterruptedException e) {
                        // Only a stop of the turn that just ended interrupts this thread. Look again.
                    }
                }
                // A stop that landed as the last turn ended must not cut this one short. Stops come
                // under LOCK while a turn runs, so none can be on its way now.
                Thread.interrupted();
                app = application;
                if (entry == null) {
                    last = new Status(saved, 0, failed, false, false);
                    // Every reel taken out by hand leaves nothing to say, as a Cancel of the queue doesn't.
                    wasCancelled = cancelled || (saved == 0 && failed == 0);
                    endRun();
                } else {
                    WAITING.remove(entry);
                    turn = new MediaDownload.Turn();
                    current = entry;
                    currentTurn = turn;
                }
            }
            if (entry == null) {
                ended(app, last, wasCancelled);
                return;
            }
            changed();

            Downloader.Status ended = null;
            try {
                Saver saver = saverForTests;
                ended = saver != null
                    ? saver.save(app, entry.reel, turn)
                    : MediaDownload.saveQueued(app, entry.reel, turn);
            } catch (InterruptedException e) {
                // Cancel stopped a turn that was waiting for a slot, or caught one as its save began.
                if (turn.saveId >= 0) SaveControl.cancel(turn.saveId);
                ended = Downloader.Status.CANCELLED;
            } catch (Throwable t) {
                MediaDownload.failure(() -> "a queued reel's turn failed", t);
            }
            // An interrupt that came after the turn ended must not cut the next one short.
            Thread.interrupted();

            final Downloader.Status how = ended;
            synchronized (LOCK) {
                settle(entry, how);
                current = null;
                currentTurn = null;
            }
            MediaDownload.info(() -> "a queued reel's turn ended: " + how);
            changed();
        }
    }

    /** The first waiting reel not paused on its own, or null. Under LOCK. */
    @Nullable
    private static Entry nextReady() {
        for (Entry entry : WAITING) {
            if (!entry.held) return entry;
        }
        return null;
    }

    /**
     * Counts how [entry]'s turn [ended], and puts a failed reel back once. A reel paused while it
     * saved goes back to the front and one cancelled goes, unless it had saved by then. Under LOCK.
     */
    private static void settle(Entry entry, @Nullable Downloader.Status ended) {
        boolean putBack = entry.putBack;
        entry.putBack = false;
        if (ended == Downloader.Status.OK) {
            saved++;
            if (entry.videoId != null) {
                QUEUED.remove(entry.videoId);
                SAVED.add(entry.videoId);
            }
            return;
        }
        if (entry.dropped) {
            if (entry.videoId != null) QUEUED.remove(entry.videoId);
            return;
        }
        if (putBack && !cancelled) {
            WAITING.addFirst(entry);
            return;
        }
        // A save stopped from its own Cancel, or the queue's, is neither saved nor failed.
        boolean retry = ended != Downloader.Status.CANCELLED && !entry.retried && !cancelled;
        if (retry) {
            entry.retried = true;
            WAITING.addLast(entry);
            return;
        }
        if (ended != Downloader.Status.CANCELLED) failed++;
        if (entry.videoId != null) QUEUED.remove(entry.videoId);
    }

    /** Forgets the run that just ended: its counts and its reels. Under LOCK. */
    private static void endRun() {
        worker = null;
        saved = 0;
        failed = 0;
        numbered = 0;
        paused = false;
        cancelled = false;
        QUEUED.clear();
        SAVED.clear();
    }

    /** The run is over: the card says how it went, or goes when it was cancelled. */
    private static void ended(@Nullable Context app, Status last, boolean wasCancelled) {
        MediaDownload.info(() -> "the reel queue ended: " + last.saved + " saved, " + last.failed + " failed"
            + (wasCancelled ? ", cancelled" : ""));
        if (app == null) return;
        boolean carded = false;
        synchronized (CARD_LOCK) {
            // A reel tapped just as the run ended has started the next one, whose card stays.
            if (status() == null) {
                if (wasCancelled) takeDown(app);
                else carded = post(app, last, true);
            }
        }
        SaveControl.tell();
        if (wasCancelled) return;
        if (!carded || !MediaDownload.quietSaves() || last.failed > 0) {
            Feedback.show(app, L10n.t(app, "Reel queue done") + "\n" + counts(last), last.failed > 0);
        }
    }

    /** The queue changed: its card and the settings catch up. Answers whether the card is up. */
    private static boolean changed() {
        boolean carded = false;
        try {
            Context app;
            synchronized (LOCK) {
                app = application;
            }
            if (app != null) {
                synchronized (CARD_LOCK) {
                    Status now = status();
                    if (now != null) carded = post(app, now, false);
                }
            }
        } catch (Throwable t) {
            MediaDownload.failure(() -> "could not show the reel queue", t);
        }
        SaveControl.tell();
        return carded;
    }

    /** Puts the queue's card up: its counts, how far it's got, and Pause or Resume with Cancel while it runs. */
    private static boolean post(Context app, Status status, boolean done) {
        NotificationManager manager = SaveControl.notifications(app);
        if (manager == null) return false;
        try {
            int total = status.saved + status.failed + status.waiting + (status.saving ? 1 : 0);
            Notification.Builder card = new Notification.Builder(app, SaveControl.CHANNEL)
                .setSmallIcon(done ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_sys_download)
                .setContentTitle(done ? L10n.t(app, "Reel queue done") : title(status))
                .setContentText(counts(status))
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setOngoing(!done)
                .setAutoCancel(done);
            if (!done) {
                card.setProgress(Math.max(total, 1), status.saved + status.failed, false);
                // A tap opens Downloads at the queue, where each reel has its own buttons (#462).
                card.setContentIntent(PendingIntent.getActivity(app, 3, SettingsEntry.settingIntent(app, ROW_KEY),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
                listen(app);
                String toggle = status.paused ? ACTION_RESUME : ACTION_PAUSE;
                card.addAction(new Notification.Action.Builder((Icon) null,
                    status.paused ? L10n.t(app, "Resume") : L10n.t(app, "Pause"), button(app, toggle, 1)).build());
                card.addAction(new Notification.Action.Builder((Icon) null,
                    L10n.t(app, "Cancel"), button(app, ACTION_CANCEL, 2)).build());
            }
            manager.notify(TAG, 0, card.build());
            return true;
        } catch (Throwable t) {
            String kind = t.getClass().getSimpleName();
            MediaDownload.failure(() -> "could not show the reel queue's card (" + kind + ")", null);
            return false;
        }
    }

    /** Takes the queue's card down. */
    private static void takeDown(Context app) {
        try {
            NotificationManager manager = app.getSystemService(NotificationManager.class);
            if (manager != null) manager.cancel(TAG, 0);
        } catch (Throwable t) {
            MediaDownload.failure(() -> "could not take the reel queue's card down", t);
        }
    }

    private static PendingIntent button(Context app, String action, int request) {
        return PendingIntent.getBroadcast(app, request, intent(app, action),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** The broadcast a card button sends. For tests. */
    static Intent intent(Context app, String action) {
        return new Intent(action).setPackage(app.getPackageName()).putExtra(EXTRA_TOKEN, TOKEN);
    }

    private static synchronized void listen(Context app) {
        if (listeningOn == app) return;
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        if (intent == null || !TOKEN.equals(intent.getStringExtra(EXTRA_TOKEN))) return;
                        String action = intent.getAction();
                        if (ACTION_PAUSE.equals(action)) pause();
                        else if (ACTION_RESUME.equals(action)) resume();
                        else if (ACTION_CANCEL.equals(action)) cancel();
                    } catch (Throwable t) {
                        MediaDownload.failure(() -> "could not act on the reel queue's button", t);
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_PAUSE);
            filter.addAction(ACTION_RESUME);
            filter.addAction(ACTION_CANCEL);
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }
            listeningOn = app;
        } catch (Throwable t) {
            MediaDownload.failure(() -> "could not listen for the reel queue's buttons", t);
        }
    }

    /**
     * The latest run's thread, for a test to wait on. Not {@link #worker}: that clears before the run's
     * last card goes up, so a test reading it then would skip the wait and see the card before it.
     */
    @Nullable
    static Thread workerForTests() {
        synchronized (LOCK) {
            return lastWorker;
        }
    }

    /** Back to an empty queue between tests: drops every reel and waits for the thread to end. */
    static void resetForTests() throws InterruptedException {
        Thread running;
        synchronized (LOCK) {
            WAITING.clear();
            paused = false;
            LOCK.notifyAll();
            running = lastWorker;
        }
        if (running != null) {
            running.interrupt();
            running.join(5_000);
        }
        synchronized (LOCK) {
            current = null;
            currentTurn = null;
            application = null;
            lastWorker = null;
            endRun();
        }
        saverForTests = null;
        listeningOn = null;
    }
}
