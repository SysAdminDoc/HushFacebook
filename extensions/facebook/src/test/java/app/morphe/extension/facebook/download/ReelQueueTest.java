/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.facebook.settings.SettingsEntry;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.SettingsContextRule;

/**
 * Queue reel saves (#449): tapped reels save one at a time in the order they were tapped, a reel
 * already waiting or saved isn't queued twice, a failed one gets one more try, Pause and Resume
 * from the card hold and release the next turn, Cancel stops the turn and drops the rest, and the
 * card counts it all. Each reel's own Pause, Resume and Cancel (#462) act on that reel alone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReelQueueTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Context context;
    /** Each turn's reel, in the order the queue started them. */
    private final List<String> turns = new CopyOnWriteArrayList<>();
    /** How a reel's turn ends; OK when it isn't listed. */
    private final Map<String, Downloader.Status> endings = new ConcurrentHashMap<>();
    /** A latch a reel's turn counts down as it starts, and one it waits on before it ends. */
    private final Map<String, CountDownLatch> started = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> holds = new ConcurrentHashMap<>();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger most = new AtomicInteger();

    @Before
    public void setUp() throws InterruptedException {
        context = RuntimeEnvironment.getApplication();
        ReelQueue.resetForTests();
        ReelQueue.saverForTests = (app, reel, turn) -> {
            String name = reel.urls.get(0);
            most.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                turns.add(name);
                // Made here too, so a worker that wins the race to a turn still marks it.
                startedOf(name).countDown();
                CountDownLatch hold = holds.get(name);
                if (hold != null) assertTrue("a held turn never ended", hold.await(10, TimeUnit.SECONDS));
                Downloader.Status ending = endings.get(name);
                return ending == null ? Downloader.Status.OK : ending;
            } finally {
                active.decrementAndGet();
            }
        };
    }

    @After
    public void tearDown() throws InterruptedException {
        for (CountDownLatch hold : holds.values()) hold.countDown();
        ReelQueue.resetForTests();
        assertNull(ReelQueue.workerForTests());
    }

    private static MediaDownload.ReelSave reel(String name) {
        return new MediaDownload.ReelSave(Collections.singletonList(name), null, DownloadQuality.BEST, PostDetails.NONE);
    }

    private CountDownLatch startedOf(String name) {
        return started.computeIfAbsent(name, ignored -> new CountDownLatch(1));
    }

    private CountDownLatch holdOf(String name) {
        return holds.computeIfAbsent(name, ignored -> new CountDownLatch(1));
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue("the expected turn never started", latch.await(10, TimeUnit.SECONDS));
    }

    private static void finishRun() throws InterruptedException {
        Thread worker = ReelQueue.workerForTests();
        if (worker != null) {
            worker.join(10_000);
            assertFalse("the queue never emptied", worker.isAlive());
        }
        ShadowLooper.idleMainLooper();
    }

    private String toast() {
        ShadowLooper.idleMainLooper();
        return String.valueOf(ShadowToast.getTextOfLatestToast());
    }

    private Notification card() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return Shadows.shadowOf(manager).getNotification(ReelQueue.TAG, 0);
    }

    private static String text(Notification card, String key) {
        return String.valueOf(card.extras.getCharSequence(key));
    }

    private static void waitUntil(String what, java.util.function.BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertTrue(what, System.nanoTime() < until);
            Thread.sleep(2);
        }
    }

    @Test
    public void tappedReelsSaveOneAtATimeInTheOrderTappedAndTheCardCountsThem() throws Exception {
        holdOf("a");
        assertTrue(ReelQueue.add(context, "1", reel("a")));
        await(startedOf("a"));
        assertTrue(ReelQueue.add(context, "2", reel("b")));
        assertTrue(ReelQueue.add(context, "3", reel("c")));

        ReelQueue.Status now = ReelQueue.status();
        assertNotNull(now);
        assertEquals(2, now.waiting);
        assertTrue(now.saving);
        assertTrue("a reel waiting must keep Close Facebook when you leave it waiting", MediaDownload.saving());
        Notification running = card();
        assertNotNull("the queue has no card while it runs", running);
        assertEquals("Saving queued reels", text(running, Notification.EXTRA_TITLE));
        assertEquals("0 saved, 2 waiting, 0 failed", text(running, Notification.EXTRA_TEXT));
        assertEquals("Pause", String.valueOf(running.actions[0].title));
        assertEquals("Cancel", String.valueOf(running.actions[1].title));

        holds.get("a").countDown();
        finishRun();

        assertEquals(Arrays.asList("a", "b", "c"), turns);
        assertEquals("two reels saved at once", 1, most.get());
        assertNull(ReelQueue.status());
        Notification done = card();
        assertNotNull(done);
        assertEquals("Reel queue done", text(done, Notification.EXTRA_TITLE));
        assertEquals("3 saved, 0 waiting, 0 failed", text(done, Notification.EXTRA_TEXT));
        assertEquals("a finished queue's card has buttons", 0, done.actions == null ? 0 : done.actions.length);
        assertEquals("Reel queue done\n3 saved, 0 waiting, 0 failed", toast());
    }

    @Test
    public void aReelAlreadyWaitingOrSavedInThisRunIsSkippedAndAReelWithNoNumberNeverIs() throws Exception {
        // Held until "b" waits, so the run doesn't end with "a" and forget it.
        CountDownLatch holdA = holdOf("a");
        holdOf("b");
        assertTrue(ReelQueue.add(context, "1", reel("a")));
        await(startedOf("a"));
        assertTrue(ReelQueue.add(context, "2", reel("b")));
        holdA.countDown();
        await(startedOf("b"));

        assertFalse(ReelQueue.add(context, "2", reel("b again")));
        assertEquals("Already in the reel queue", toast());
        assertFalse(ReelQueue.add(context, "1", reel("a again")));
        assertEquals("That reel is already saved", toast());
        assertTrue(ReelQueue.add(context, null, reel("x")));
        assertTrue(ReelQueue.add(context, null, reel("y")));
        assertEquals("Added to the reel queue", toast());

        holds.get("b").countDown();
        finishRun();
        assertEquals(Arrays.asList("a", "b", "x", "y"), turns);

        // The run is over, and with it what the queue knew of its reels.
        assertTrue(ReelQueue.add(context, "1", reel("a")));
        finishRun();
        assertEquals(Arrays.asList("a", "b", "x", "y", "a"), turns);
    }

    @Test
    public void aFailedReelGetsOneMoreTryAtTheBackAndThenCountsAsFailed() throws Exception {
        holdOf("x");
        endings.put("x", Downloader.Status.NETWORK_ERROR);
        assertTrue(ReelQueue.add(context, "1", reel("x")));
        await(startedOf("x"));
        assertTrue(ReelQueue.add(context, "2", reel("y")));
        holds.get("x").countDown();
        finishRun();

        assertEquals(Arrays.asList("x", "y", "x"), turns);
        Notification done = card();
        assertNotNull(done);
        assertEquals("1 saved, 0 waiting, 1 failed", text(done, Notification.EXTRA_TEXT));
        assertEquals("Reel queue done\n1 saved, 0 waiting, 1 failed", toast());
    }

    @Test
    public void pauseFromTheCardLetsTheReelFinishAndHoldsTheNextUntilResume() throws Exception {
        CountDownLatch holdA = holdOf("a");
        assertTrue(ReelQueue.add(context, "1", reel("a")));
        await(startedOf("a"));
        assertTrue(ReelQueue.add(context, "2", reel("b")));

        context.sendBroadcast(ReelQueue.intent(context, ReelQueue.ACTION_PAUSE));
        ShadowLooper.idleMainLooper();
        ReelQueue.Status paused = ReelQueue.status();
        assertNotNull(paused);
        assertTrue("the card's Pause didn't pause the queue", paused.paused);
        Notification card = card();
        assertEquals("Reel queue paused", text(card, Notification.EXTRA_TITLE));
        assertEquals("Resume", String.valueOf(card.actions[0].title));

        holdA.countDown();
        waitUntil("the paused queue's reel never finished", () -> {
            ReelQueue.Status now = ReelQueue.status();
            return now != null && !now.saving && now.saved == 1;
        });
        Thread.sleep(100);
        assertEquals("the next reel started while the queue was paused", Collections.singletonList("a"), turns);

        // A broadcast without the card's token is anyone's, and does nothing.
        context.sendBroadcast(new Intent(ReelQueue.ACTION_RESUME).setPackage(context.getPackageName()));
        ShadowLooper.idleMainLooper();
        Thread.sleep(50);
        assertTrue(ReelQueue.status().paused);
        assertEquals(Collections.singletonList("a"), turns);

        context.sendBroadcast(ReelQueue.intent(context, ReelQueue.ACTION_RESUME));
        ShadowLooper.idleMainLooper();
        finishRun();
        assertEquals(Arrays.asList("a", "b"), turns);
        assertEquals("2 saved, 0 waiting, 0 failed", text(card(), Notification.EXTRA_TEXT));
    }

    @Test
    public void cancelStopsTheReelSavingAndDropsTheRest() throws Exception {
        // This turn ends only when the queue's Cancel interrupts it.
        holds.put("a", new CountDownLatch(1) {
            @Override public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
                super.await();
                return true;
            }
        });
        assertTrue(ReelQueue.add(context, "1", reel("a")));
        await(startedOf("a"));
        assertTrue(ReelQueue.add(context, "2", reel("b")));
        assertTrue(ReelQueue.add(context, "3", reel("c")));
        assertNotNull(card());

        ReelQueue.cancel();
        finishRun();

        assertEquals("a dropped reel still saved", Collections.singletonList("a"), turns);
        assertNull(ReelQueue.status());
        assertFalse(MediaDownload.saving());
        assertNull("a cancelled queue left its card up", card());
        assertEquals("Reel queue canceled", toast());

        // A reel the cancelled run held can be queued again.
        assertTrue(ReelQueue.add(context, "2", reel("b")));
        finishRun();
        assertEquals(Arrays.asList("a", "b"), turns);
    }

    /** A turn that ends only when something stops it: the queue's Cancel, or a reel's own Pause or Cancel. */
    private void holdUntilStopped(String name) {
        holds.put(name, new CountDownLatch(1) {
            @Override public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
                super.await();
                return true;
            }
        });
    }

    private static String names() {
        StringBuilder listed = new StringBuilder();
        for (ReelQueue.Item item : ReelQueue.items()) {
            if (listed.length() > 0) listed.append(", ");
            listed.append(ReelQueue.name(item)).append(' ').append(ReelQueue.state(item));
        }
        return listed.toString();
    }

    /** #462: the card opens Downloads at the queue, which lists each reel by who posted it, or by its number. */
    @Test
    public void theCardOpensTheQueuePageWhichListsEveryReelInOrder() throws Exception {
        holdOf("a");
        assertTrue(ReelQueue.add(context, "1", reel("a")));
        await(startedOf("a"));
        MediaDownload.ReelSave fromAna = new MediaDownload.ReelSave(Collections.singletonList("b"), null,
            DownloadQuality.BEST, new PostDetails("2", "Ana", null));
        assertTrue(ReelQueue.add(context, "2", fromAna));
        assertTrue(ReelQueue.add(context, "3", reel("c")));

        assertEquals("Reel 1 Saving now, Reel from " + L10n.isolate("Ana") + " Waiting, Reel 3 Waiting", names());
        Notification running = card();
        assertNotNull("a running queue's card opens nothing", running.contentIntent);
        Intent open = Shadows.shadowOf(running.contentIntent).getSavedIntent();
        assertTrue("the card's tap doesn't open the settings", open.getBooleanExtra(SettingsEntry.EXTRA_OPEN_SETTINGS, false));
        assertEquals(ReelQueue.ROW_KEY, open.getStringExtra(SettingsEntry.EXTRA_SHOW_SETTING));

        holds.get("a").countDown();
        finishRun();
        assertTrue("a finished queue still lists reels", ReelQueue.items().isEmpty());
    }

    /** #462: a waiting reel paused on its own keeps its place, the ones behind it go on, and Resume lets it go. */
    @Test
    public void aWaitingReelPausedOnItsOwnKeepsItsPlaceWhileTheRestGoOn() throws Exception {
        CountDownLatch holdA = holdOf("a");
        assertTrue(ReelQueue.add(context, "1", reel("a")));
        await(startedOf("a"));
        assertTrue(ReelQueue.add(context, "2", reel("b")));
        assertTrue(ReelQueue.add(context, "3", reel("c")));

        ReelQueue.pause(2);
        assertEquals("Reel 1 Saving now, Reel 2 Paused, Reel 3 Waiting", names());
        assertFalse("one paused reel paused the whole queue", ReelQueue.status().paused);

        holdA.countDown();
        // The card goes up last, after the counts change.
        waitUntil("the reel behind the paused one never went", () -> {
            Notification now = card();
            return turns.contains("c") && now != null && "Reel queue paused".equals(text(now, Notification.EXTRA_TITLE));
        });
        Thread.sleep(50);
        assertEquals("the paused reel went", Arrays.asList("a", "c"), turns);
        ReelQueue.Status held = ReelQueue.status();
        assertNotNull("the run ended with a paused reel still in it", held);
        assertEquals(1, held.waiting);
        assertTrue("with only paused reels left, the queue doesn't read as paused", held.paused);
        assertEquals("Resume", String.valueOf(card().actions[0].title));

        ReelQueue.resume(2);
        finishRun();
        assertEquals(Arrays.asList("a", "c", "b"), turns);
        assertEquals("3 saved, 0 waiting, 0 failed", text(card(), Notification.EXTRA_TEXT));
    }

    /** #462: pausing the reel that's saving stops its save and puts it back at the front, to start over on Resume. */
    @Test
    public void pausingTheReelSavingStopsItAndPutsItBackAtTheFront() throws Exception {
        holdUntilStopped("a");
        assertTrue(ReelQueue.add(context, "1", reel("a")));
        await(startedOf("a"));
        assertTrue(ReelQueue.add(context, "2", reel("b")));

        ReelQueue.pause(1);
        waitUntil("the stopped reel never let the next one go", () -> {
            ReelQueue.Status now = ReelQueue.status();
            return now != null && now.saved == 1 && !now.saving;
        });
        assertEquals(Arrays.asList("a", "b"), turns);
        assertEquals("Reel 1 Paused", names());
        assertEquals("a stopped reel counted as failed", 0, ReelQueue.status().failed);

        holds.remove("a");
        ReelQueue.resume(1);
        finishRun();
        assertEquals("the paused reel didn't start over", Arrays.asList("a", "b", "a"), turns);
        assertEquals("2 saved, 0 waiting, 0 failed", text(card(), Notification.EXTRA_TEXT));
    }

    /** #462: Resume on the queue's own card lets every reel paused on its own go, too. */
    @Test
    public void theQueuesResumeLetsEveryPausedReelGo() throws Exception {
        CountDownLatch holdA = holdOf("a");
        assertTrue(ReelQueue.add(context, "1", reel("a")));
        await(startedOf("a"));
        assertTrue(ReelQueue.add(context, "2", reel("b")));
        assertTrue(ReelQueue.add(context, "3", reel("c")));
        ReelQueue.pause(2);
        ReelQueue.pause(3);
        holdA.countDown();
        waitUntil("the queue never stopped at its paused reels", () -> {
            ReelQueue.Status now = ReelQueue.status();
            return now != null && now.saved == 1 && !now.saving;
        });

        context.sendBroadcast(ReelQueue.intent(context, ReelQueue.ACTION_RESUME));
        ShadowLooper.idleMainLooper();
        finishRun();
        assertEquals(Arrays.asList("a", "b", "c"), turns);
    }

    /** #462: Cancel on one reel takes out that reel alone, whether it waits or saves, and frees it to be queued again. */
    @Test
    public void cancelOnOneReelTakesOutThatReelAlone() throws Exception {
        holdUntilStopped("a");
        assertTrue(ReelQueue.add(context, "1", reel("a")));
        await(startedOf("a"));
        assertTrue(ReelQueue.add(context, "2", reel("b")));
        assertTrue(ReelQueue.add(context, "3", reel("c")));

        ReelQueue.cancel(2);
        assertEquals("Reel 1 Saving now, Reel 3 Waiting", names());
        assertTrue("a reel taken out stayed in the queue's memory", ReelQueue.add(context, "2", reel("b again")));

        ReelQueue.cancel(1);
        finishRun();
        assertEquals(Arrays.asList("a", "c", "b again"), turns);
        assertEquals("a reel taken out counted", "2 saved, 0 waiting, 0 failed", text(card(), Notification.EXTRA_TEXT));
    }

    /** #462: every reel taken out by hand ends the run with its card gone and nothing to say, as the queue's Cancel does. */
    @Test
    public void takingOutEveryReelByHandEndsTheRunQuietly() throws Exception {
        holdUntilStopped("a");
        assertTrue(ReelQueue.add(context, "1", reel("a")));
        await(startedOf("a"));
        assertTrue(ReelQueue.add(context, "2", reel("b")));
        ShadowLooper.idleMainLooper();
        ShadowToast.reset();

        ReelQueue.cancel(2);
        ReelQueue.cancel(1);
        finishRun();
        assertEquals(Collections.singletonList("a"), turns);
        assertNull(ReelQueue.status());
        assertNull("an emptied queue left its card up", card());
        assertNull("an emptied queue said it was done", ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void aFullQueueTurnsTheNextReelAway() throws Exception {
        holdOf("first");
        assertTrue(ReelQueue.add(context, null, reel("first")));
        await(startedOf("first"));
        for (int i = 0; i < ReelQueue.MOST_WAITING; i++) {
            assertTrue("reel " + i + " was turned away", ReelQueue.add(context, null, reel("r" + i)));
        }
        assertEquals(ReelQueue.MOST_WAITING, ReelQueue.status().waiting);
        assertFalse(ReelQueue.add(context, null, reel("one too many")));
        assertEquals("The reel queue is full. Try again when it's shorter.", toast());
        ReelQueue.cancel();
        finishRun();
        assertFalse(turns.contains("one too many"));
    }

    @Test
    public void withNotificationsOffTheTapSaysWhereToPauseOrCancel() throws Exception {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Shadows.shadowOf(manager).setNotificationsEnabled(false);
        holdOf("a");
        assertTrue(ReelQueue.add(context, "1", reel("a")));
        assertEquals("Added to the reel queue. To pause or cancel it, open Downloads in Hushfacebook settings.", toast());
        assertNull(card());
        holds.get("a").countDown();
        finishRun();
        assertEquals("Reel queue done\n1 saved, 0 waiting, 0 failed", toast());
    }

    @Test
    public void aTapWithNothingToSaveSaysTheDownloadFailedAndQueuesNothing() throws Exception {
        assertFalse(ReelQueue.add(context, "1", null));
        assertEquals("Download failed. Try again in a moment.", toast());
        assertNull(ReelQueue.status());
        assertNull(ReelQueue.workerForTests());
    }
}
