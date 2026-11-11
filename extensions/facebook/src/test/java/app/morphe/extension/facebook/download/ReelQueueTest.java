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

import app.morphe.extension.shared.SettingsContextRule;

/**
 * Queue reel saves (#449): tapped reels save one at a time in the order they were tapped, a reel
 * already waiting or saved isn't queued twice, a failed one gets one more try, Pause and Resume
 * from the card hold and release the next turn, Cancel stops the turn and drops the rest, and the
 * card counts it all.
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
