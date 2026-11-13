/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import app.morphe.extension.facebook.reels.PostTextSelect;
import app.morphe.extension.facebook.settings.ShimmerTitle;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Save a batch of reels (#449): with Queue reel saves on, a long press on a reel's Download button
 * asks how many reels to save, that one and the ones after it, and then moves the viewer on by
 * itself, putting each reel it reaches in the reel queue until it has that many.
 *
 * <p>The reels come from the Download buttons Facebook builds. Each reel's sidebar builds its own
 * Download handler ({@link ReelDownload}) with that reel's player, before or as the reel comes on
 * screen, and every handler of the tap slot is noted here in the order it was built
 * ({@link #built}). The batch takes the handlers built after the one held, each reel once, so a reel
 * already behind the one held, or one its sidebar draws again, never joins. Nothing reads the
 * viewer's list or its views.
 *
 * <p>Moving on is a swipe up, sent to the screen's window as a finger's would be, so Facebook's own
 * viewer handles it the same way. After each swipe the batch waits for the next reel's buttons, and
 * three swipes in a row that bring no new reel end it: the reels ran out or the viewer isn't there
 * anymore. It waits while the queue is paused or full, or while something else is over the viewer,
 * and stops when the queue is canceled, when the screen it started on closes, or after
 * {@link #MOST_IDLE_MS} without a swipe. One batch runs at a time.
 */
public final class ReelBatch {

    private ReelBatch() {}

    /** The batch sizes offered, the held reel included. */
    static final int[] SIZES = {10, 25, 50, 100, 150};

    /** How long after a swipe the batch looks for the reels it brought. */
    static final long SETTLE_MS = 1500;
    /** How often a batch that waits looks again. */
    static final long WAIT_MS = 1000;
    /** Swipes in a row that bring no new reel before the batch gives up. */
    static final int MOST_EMPTY = 3;
    /** How long a batch may wait without swiping, paused, full or covered, before it stops. */
    static final long MOST_IDLE_MS = 10 * 60 * 1000L;
    /** The handlers noted at once: a few reels ahead and behind, and their redraws. */
    static final int NOTED = 16;

    /** The swipe: its moves, the time between them, and where it starts and ends, as parts of the window. */
    static final int SWIPE_STEPS = 8;
    static final long SWIPE_STEP_MS = 16;
    static final float SWIPE_X = 0.5f;
    static final float SWIPE_FROM = 0.65f;
    static final float SWIPE_TO = 0.2f;

    /** A Download handler as it was built, numbered in building order. Weak: it holds Facebook's screen. */
    static final class Noted {
        final long order;
        final WeakReference<ReelDownload> handler;

        Noted(long order, ReelDownload handler) {
            this.order = order;
            this.handler = new WeakReference<>(handler);
        }
    }

    private static final Object LOCK = new Object();
    private static final ArrayDeque<Noted> NOTES = new ArrayDeque<>();
    private static long nextOrder;

    /** The running batch, or null. Read and written on the main thread. */
    @Nullable static Run running;

    /** Swipes go here in a test, which has no window to send them to. */
    @Nullable static volatile Runnable swipeForTests;

    /** One batch: where it runs, how many it wants and has, and what it has seen. */
    static final class Run {
        final WeakReference<Activity> screen;
        final int total;
        @Nullable final DownloadQuality quality;
        /** The reels this batch has put in the queue or left behind, by video number or, with none, by player. */
        final Set<Object> seen = new HashSet<>();
        /** The last handler's order the batch has read past. */
        long readTo;
        int added;
        /** Swipes in a row that brought no new reel. */
        int empty;
        /** A swipe went out since the last turn. */
        boolean swiped;
        long idleSince;
        boolean stopped;

        Run(Activity screen, int total, @Nullable DownloadQuality quality, long readTo) {
            this.screen = new WeakReference<>(screen);
            this.total = total;
            this.quality = quality;
            this.readTo = readTo;
            this.idleSince = SystemClock.uptimeMillis();
        }
    }

    /**
     * Notes [handler], a reel's Download tap handler, as Facebook builds it. Called from its
     * constructor, on whatever thread draws the sidebar. Never throws.
     */
    static void built(ReelDownload handler) {
        try {
            synchronized (LOCK) {
                NOTES.addLast(new Noted(nextOrder++, handler));
                while (NOTES.size() > NOTED) NOTES.removeFirst();
            }
        } catch (Throwable ignored) {
            // Noting a reel is never worth a sidebar.
        }
    }

    /** The order [playerParams]'s newest noted handler was built in, or -1. */
    static long orderOf(Object playerParams) {
        synchronized (LOCK) {
            long found = -1;
            for (Noted noted : NOTES) {
                ReelDownload handler = noted.handler.get();
                if (handler != null && handler.playerParams() == playerParams) found = noted.order;
            }
            return found;
        }
    }

    /** What tells one reel from another in [run]'s seen set: its video number, or its player with none. */
    static Object keyOf(ReelDownload handler) {
        String id = handler.videoId();
        return id != null ? id : handler.playerParams();
    }

    /**
     * The handlers built after [run] last read, in building order, each reel once and none it has
     * seen, at most [room] of them. Marks what it read.
     */
    static List<ReelDownload> fresh(Run run, int room) {
        List<ReelDownload> found = new ArrayList<>();
        synchronized (LOCK) {
            for (Noted noted : NOTES) {
                if (noted.order <= run.readTo) continue;
                if (found.size() >= room) break;
                run.readTo = noted.order;
                ReelDownload handler = noted.handler.get();
                if (handler == null || !run.seen.add(keyOf(handler))) continue;
                found.add(handler);
            }
        }
        return found;
    }

    /** Whether a batch runs. */
    public static boolean busy() {
        return running != null;
    }

    /**
     * The long press on [held]'s Download button: asks how many reels to save on the screen
     * [context] belongs to. Answers whether the list is showing. Never throws: it runs inside
     * Facebook's touch handling.
     */
    static boolean ask(@Nullable Context context, ReelDownload held) {
        try {
            Activity activity = activityOf(context);
            if (activity == null) activity = Utils.getActivity();
            if (activity == null || activity.isFinishing()) return false;
            if (running != null) {
                stop(L10n.t(activity, "Stopped going through reels"));
                return true;
            }
            String[] labels = new String[SIZES.length];
            for (int i = 0; i < SIZES.length; i++) labels[i] = L10n.f(activity, "%1$d reels", SIZES[i]);
            final Activity screen = activity;
            AlertDialog dialog = new AlertDialog.Builder(activity, PostTextSelect.dark(activity)
                    ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                    : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                    .setTitle(L10n.t(activity, "Save this reel and the ones after it"))
                    .setItems(labels, (box, which) -> {
                        box.dismiss();
                        picked(screen, held, SIZES[which]);
                    })
                    .setNegativeButton(L10n.t(activity, "Cancel"), null)
                    .create();
            dialog.show();
            ShimmerTitle.wearTitleOf(dialog);
            return true;
        } catch (Throwable failure) {
            Logger.printException(() -> "Reel batch: the list could not open", failure);
            return false;
        }
    }

    /** [total] picked for [held] on [screen]: the quality list first when it asks on each save, then the batch. */
    static void picked(Activity screen, ReelDownload held, int total) {
        try {
            QualityChooser.Save start = () -> start(screen, held, total, MediaDownload.quality());
            if (!QualityChooser.ask(screen, start)) start.start();
        } catch (Throwable failure) {
            Logger.printException(() -> "Reel batch: could not start", failure);
        }
    }

    /**
     * Starts a batch of [total] on [screen] at [quality], [held] first. Reels built before it, the
     * ones behind it, count as seen. Answers whether it started.
     */
    static boolean start(Activity screen, ReelDownload held, int total, @Nullable DownloadQuality quality) {
        if (running != null) return false;
        long heldAt = orderOf(held.playerParams());
        Run run = new Run(screen, total, quality, heldAt);
        if (heldAt >= 0) {
            synchronized (LOCK) {
                for (Noted noted : NOTES) {
                    if (noted.order >= heldAt) break;
                    ReelDownload behind = noted.handler.get();
                    if (behind != null) run.seen.add(keyOf(behind));
                }
            }
        }
        run.seen.add(keyOf(held));
        running = run;
        Logger.printDebug(() -> "Reel batch: started for " + total + " reels");
        if (held.queueForBatch(quality)) run.added++;
        Feedback.show(screen.getApplicationContext(), L10n.f(screen,
                "Going through %1$d reels for the reel queue. Hold Download again or cancel the queue to stop.", total), true);
        later(run, SETTLE_MS);
        return true;
    }

    private static void later(Run run, long delay) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> step(run), delay);
    }

    /** One turn of [run]: take the reels the last swipe brought, then end, wait or swipe again. */
    static void step(Run run) {
        try {
            if (run.stopped || running != run) return;
            Activity screen = run.screen.get();
            if (screen == null || screen.isFinishing() || screen.isDestroyed()) {
                end(run, null);
                return;
            }
            int before = run.added;
            ReelQueue.Status ahead = ReelQueue.status();
            int room = Math.min(run.total - run.added,
                    ReelQueue.MOST_WAITING - (ahead == null ? 0 : ahead.waiting));
            for (ReelDownload handler : fresh(run, Math.max(0, room))) {
                if (handler.queueForBatch(run.quality)) run.added++;
            }
            if (run.added >= run.total) {
                end(run, L10n.f(screen, "Put %1$d reels in the reel queue", run.added));
                return;
            }
            // Only a turn right after a swipe says whether the swipe brought a reel.
            if (run.swiped) {
                run.swiped = false;
                run.empty = run.added > before ? 0 : run.empty + 1;
                if (run.empty >= MOST_EMPTY) {
                    end(run, L10n.f(screen, "No more reels came. %1$d went in the reel queue.", run.added));
                    return;
                }
            }
            long now = SystemClock.uptimeMillis();
            ReelQueue.Status queue = ReelQueue.status();
            boolean waits = (queue != null && (queue.paused || queue.waiting >= ReelQueue.MOST_WAITING))
                    || !screen.hasWindowFocus();
            if (waits) {
                if (now - run.idleSince > MOST_IDLE_MS) {
                    end(run, L10n.t(screen, "Stopped going through reels"));
                    return;
                }
                later(run, WAIT_MS);
                return;
            }
            run.idleSince = now;
            run.swiped = true;
            swipe(screen);
            later(run, SETTLE_MS);
        } catch (Throwable failure) {
            Logger.printException(() -> "Reel batch: a step failed", failure);
            end(run, null);
        }
    }

    /** Stops the running batch, saying [why] when there is something to say. Never throws. */
    public static void stop(@Nullable String why) {
        try {
            Run run = running;
            if (run != null) end(run, why);
        } catch (Throwable failure) {
            Logger.printException(() -> "Reel batch: could not stop", failure);
        }
    }

    private static void end(Run run, @Nullable String why) {
        run.stopped = true;
        if (running == run) running = null;
        Logger.printDebug(() -> "Reel batch: ended with " + run.added + " of " + run.total + " reels queued");
        Activity screen = run.screen.get();
        Context app = screen != null ? screen.getApplicationContext() : Utils.getContext();
        if (why != null && app != null) Feedback.show(app, why, false);
    }

    /** Sends a swipe up to [screen]'s window, a finger's down, moves and lift, one frame apart. */
    static void swipe(Activity screen) {
        Runnable test = swipeForTests;
        if (test != null) {
            test.run();
            return;
        }
        View window = screen.getWindow().getDecorView();
        swipe(window, new Handler(Looper.getMainLooper()));
    }

    /** The swipe up on [window], posted on [handler]. */
    static void swipe(View window, Handler handler) {
        float x = window.getWidth() * SWIPE_X;
        float from = window.getHeight() * SWIPE_FROM;
        float to = window.getHeight() * SWIPE_TO;
        long down = SystemClock.uptimeMillis();
        send(window, down, down, MotionEvent.ACTION_DOWN, x, from);
        for (int step = 1; step <= SWIPE_STEPS; step++) {
            final int at = step;
            handler.postDelayed(() -> {
                long now = SystemClock.uptimeMillis();
                float y = from + (to - from) * at / SWIPE_STEPS;
                send(window, down, now, MotionEvent.ACTION_MOVE, x, y);
                if (at == SWIPE_STEPS) send(window, down, now, MotionEvent.ACTION_UP, x, y);
            }, step * SWIPE_STEP_MS);
        }
    }

    private static void send(View window, long down, long now, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, now, action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            window.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    /** The activity a context wraps, or null. The depth guards against a wrapper that wraps itself. */
    @Nullable
    private static Activity activityOf(@Nullable Context context) {
        for (int depth = 0; context != null && depth < 20; depth++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /** Forgets every noted handler and any batch. */
    static void resetForTests() {
        synchronized (LOCK) {
            NOTES.clear();
            nextOrder = 0;
        }
        running = null;
        swipeForTests = null;
    }

    /** The handlers noted now, oldest first. */
    static List<ReelDownload> notedForTests() {
        List<ReelDownload> all = new ArrayList<>();
        synchronized (LOCK) {
            for (Iterator<Noted> it = NOTES.iterator(); it.hasNext(); ) {
                ReelDownload handler = it.next().handler.get();
                if (handler != null) all.add(handler);
            }
        }
        return all;
    }
}
