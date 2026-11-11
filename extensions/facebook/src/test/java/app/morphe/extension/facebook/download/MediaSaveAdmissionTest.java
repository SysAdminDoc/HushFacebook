/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.net.InetAddress;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import app.morphe.extension.shared.SettingsContextRule;

/** Every entry point shares three slots, held through worker cleanup rather than UI cancellation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MediaSaveAdmissionTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String FIRST = "https://first.fbcdn.net/photo.jpg";
    private static final String SECOND = "https://second.fbcdn.net/photo.jpg";
    private static final String REEL = "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/clip_720p.mp4?oh=1&oe=2";
    private final List<Thread> threads = new CopyOnWriteArrayList<>();
    private final List<Throwable> uncaught = new CopyOnWriteArrayList<>();
    private final List<CountDownLatch> releases = new ArrayList<>();
    private final AtomicInteger maximum = new AtomicInteger();
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        assertEquals("another test left a save running", 0, MediaDownload.savesInFlight());
        MediaDownload.threadsForTests = this::thread;
        // No socket opens. A photo reaches DNS, then fails the direct-route private-address check.
        MediaDownload.policyForTests = new MediaUrlPolicy(host -> privateAddress());
        SaveLeftovers.forgetSweepForTests();
    }

    @After
    public void tearDown() throws InterruptedException {
        for (Thread thread : threads) {
            if (thread.getName().equals("hushfacebook-photo-saves")) thread.interrupt();
        }
        for (CountDownLatch release : releases) release.countDown();
        try {
            int joined;
            do {
                List<Thread> snapshot = new ArrayList<>(threads);
                joined = snapshot.size();
                for (Thread thread : snapshot) finish(thread);
            } while (joined != threads.size());
            assertEquals("a worker leaked its slot", 0, MediaDownload.savesInFlight());
            assertTrue("a thread escaped its error handler: " + uncaught, uncaught.isEmpty());
            assertTrue("more than three slots were held", maximum.get() <= 3);
            assertTrue(SaveControl.running().isEmpty());
        } finally {
            MediaDownload.detailsForTests = null;
            MediaDownload.threadsForTests = null;
            MediaDownload.policyForTests = null;
        }
    }

    private Thread thread(Runnable work, String name) {
        observeSlots();
        Thread thread = new Thread(work, name);
        thread.setUncaughtExceptionHandler((ignored, failure) -> uncaught.add(failure));
        threads.add(thread);
        return thread;
    }

    private void observeSlots() {
        int held = MediaDownload.savesInFlight();
        assertTrue("negative slot count", held >= 0);
        maximum.accumulateAndGet(held, Math::max);
    }

    private CountDownLatch latch() {
        CountDownLatch latch = new CountDownLatch(1);
        releases.add(latch);
        return latch;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue("the expected step never ran", latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw new AssertionError(stopped);
        }
    }

    private static void finish(Thread thread) throws InterruptedException {
        assertNotNull(thread);
        thread.join(10_000);
        assertFalse("the thread never ended: " + thread.getName(), thread.isAlive());
    }

    private static InetAddress[] privateAddress() throws java.net.UnknownHostException {
        return new InetAddress[] { InetAddress.getByName("10.9.8.7") };
    }

    private static Downloader.Result finished() {
        return Downloader.Result.fail(Downloader.Status.CANCELLED, "test job ended");
    }

    private Thread hold(CountDownLatch release) {
        CountDownLatch entered = latch();
        Thread worker = MediaDownload.start(context, false, (writer, progress) -> {
            observeSlots();
            entered.countDown();
            await(release);
            return finished();
        });
        assertNotNull(worker);
        await(entered);
        return worker;
    }

    @Test
    public void emptyAndFullCapacityRefuseWithoutQueuingThenCompletionRestoresAllSlots() throws Exception {
        assertNull(MediaDownload.savePhotos(context, Collections.emptyList(), Collections.emptyList()));
        assertNull(MediaDownload.savePhotos(context, Collections.singletonList("https://example.com/photo.jpg"),
                Collections.emptyList()));
        assertEquals(0, MediaDownload.savesInFlight());
        CountDownLatch release = latch();
        Thread one = hold(release);
        Thread two = hold(release);
        Thread three = hold(release);
        assertEquals(3, MediaDownload.savesInFlight());
        assertNull(MediaDownload.start(context, false, (writer, progress) -> finished()));
        assertFalse(MediaDownload.savePhoto(context, FIRST, PostDetails.NONE));
        assertNull(MediaDownload.savePhotos(context, Collections.singletonList(FIRST), Collections.emptyList()));
        assertEquals(3, MediaDownload.savesInFlight());
        release.countDown();
        finish(one);
        finish(two);
        finish(three);
        assertEquals(0, MediaDownload.savesInFlight());
        finish(MediaDownload.start(context, false, (writer, progress) -> finished()));
        assertEquals(0, MediaDownload.savesInFlight());
    }

    @Test
    public void twoChainsAndAnIndividualCannotClaimTheSameLastSlot() throws Exception {
        CountDownLatch heldJobs = latch();
        hold(heldJobs);
        hold(heldJobs);
        CountDownLatch firstStarting = latch();
        CountDownLatch allowFirstStart = latch();
        CountDownLatch firstDns = latch();
        CountDownLatch releaseDns = latch();
        PostDetails firstDetails = PostDetails.of("1");
        MediaDownload.detailsForTests = details -> {
            if (details == firstDetails) {
                firstStarting.countDown();
                await(allowFirstStart);
            }
        };
        MediaDownload.policyForTests = new MediaUrlPolicy(host -> {
            observeSlots();
            if (host.equals("first.fbcdn.net")) firstDns.countDown();
            await(releaseDns);
            return privateAddress();
        });
        Thread firstChain = MediaDownload.savePhotos(context, Collections.singletonList(FIRST),
                Collections.singletonList(firstDetails));
        await(firstStarting);
        CountDownLatch race = latch();
        AtomicReference<Thread> otherChain = new AtomicReference<>();
        AtomicBoolean individual = new AtomicBoolean();
        Thread chainTap = thread(() -> {
            await(race);
            otherChain.set(MediaDownload.savePhotos(context, Collections.singletonList(SECOND),
                    Collections.emptyList()));
        }, "test-chain-tap");
        Thread individualTap = thread(() -> {
            await(race);
            individual.set(MediaDownload.savePhoto(context, SECOND, PostDetails.NONE));
        }, "test-individual-tap");
        chainTap.start();
        individualTap.start();
        race.countDown();
        finish(chainTap);
        finish(individualTap);
        allowFirstStart.countDown();
        await(firstDns);
        assertNull("the other chain must refuse immediately", otherChain.get());
        assertFalse("the individual save must refuse immediately", individual.get());
        assertEquals(3, MediaDownload.savesInFlight());
        releaseDns.countDown();
        finish(firstChain);
    }

    @Test
    public void cancelAndRepeatedUiCleanupDoNotReleaseAStillRunningWorker() throws Exception {
        CountDownLatch release = latch();
        AtomicReference<SaveControl.Save> active = new AtomicReference<>();
        CountDownLatch entered = latch();
        Thread cancelled = MediaDownload.start(context, false, (writer, progress) -> {
            active.set((SaveControl.Save) progress);
            entered.countDown();
            await(release);
            return finished();
        });
        await(entered);
        hold(release);
        hold(release);
        assertTrue(active.get().cancel());
        assertFalse(active.get().cancel());
        active.get().end();
        active.get().end();
        assertEquals(2, SaveControl.running().size());
        assertEquals(3, MediaDownload.savesInFlight());
        assertNull(MediaDownload.start(context, false, (writer, progress) -> finished()));
        release.countDown();
        finish(cancelled);
    }

    @Test
    public void interruptingAChainWaitingForItsChildLeavesTheChildSlotHeld() throws Exception {
        CountDownLatch dnsEntered = latch();
        CountDownLatch releaseDns = latch();
        AtomicInteger photos = new AtomicInteger();
        MediaDownload.policyForTests = new MediaUrlPolicy(host -> {
            photos.incrementAndGet();
            dnsEntered.countDown();
            await(releaseDns);
            return privateAddress();
        });
        Thread chain = MediaDownload.savePhotos(context, Arrays.asList(FIRST, SECOND), Collections.emptyList());
        await(dnsEntered);
        chain.interrupt();
        finish(chain);
        assertTrue(chain.isInterrupted());
        assertEquals(1, photos.get());
        assertEquals("the active child still owns its slot", 1, MediaDownload.savesInFlight());
        assertEquals(1, SaveControl.running().size());
        releaseDns.countDown();
    }

    @Test
    public void cancellingAnAlbumPhotoStopsTheRemainingPhotos() throws Exception {
        CountDownLatch firstEntered = latch();
        CountDownLatch releaseFirst = latch();
        AtomicInteger photos = new AtomicInteger();
        MediaDownload.policyForTests = new MediaUrlPolicy(host -> {
            if (photos.incrementAndGet() == 1) {
                firstEntered.countDown();
                await(releaseFirst);
            }
            return privateAddress();
        });
        Thread chain = MediaDownload.savePhotos(context, Arrays.asList(FIRST, SECOND), Collections.emptyList());
        await(firstEntered);
        assertEquals(1, SaveControl.running().size());

        assertTrue("the explicit cancel was refused", SaveControl.cancel(SaveControl.running().get(0).id));
        releaseFirst.countDown();
        finish(chain);

        assertEquals("Cancel started the next photo of the album", 1, photos.get());
        assertEquals(0, MediaDownload.savesInFlight());
        assertTrue(SaveControl.running().isEmpty());
    }

    @Test
    public void aLaterPhotoWaitsInterruptiblyWhenOtherSavesFillTheFreedSlot() throws Exception {
        CountDownLatch heldJobs = latch();
        hold(heldJobs);
        hold(heldJobs);
        CountDownLatch nextDetails = latch();
        CountDownLatch allowNext = latch();
        AtomicInteger photos = new AtomicInteger();
        MediaDownload.policyForTests = new MediaUrlPolicy(host -> {
            photos.incrementAndGet();
            return privateAddress();
        });
        List<PostDetails> details = new AbstractList<PostDetails>() {
            @Override public int size() { return 2; }
            @Override public PostDetails get(int index) {
                if (index == 1) {
                    nextDetails.countDown();
                    await(allowNext);
                }
                return PostDetails.NONE;
            }
        };
        Thread chain = MediaDownload.savePhotos(context, Arrays.asList(FIRST, SECOND), details);
        await(nextDetails);
        hold(heldJobs);
        allowNext.countDown();
        waitForSemaphore(chain);
        chain.interrupt();
        finish(chain);
        assertTrue(chain.isInterrupted());
        assertEquals(1, photos.get());
        assertEquals("waiting never owns a fourth slot", 3, MediaDownload.savesInFlight());
    }

    /** #449: the reel queue's turn waits for a slot where a tap gives up, and holds none while it waits. */
    @Test
    public void aQueuedReelWaitsForASlotWhereATapGivesUp() throws Exception {
        CountDownLatch release = latch();
        hold(release);
        hold(release);
        hold(release);
        MediaDownload.ReelSave reel = new MediaDownload.ReelSave(Collections.singletonList(REEL), null,
                DownloadQuality.BEST, PostDetails.NONE);
        MediaDownload.Turn turn = new MediaDownload.Turn();
        AtomicReference<Downloader.Status> ended = new AtomicReference<>();
        Thread queue = thread(() -> {
            try {
                ended.set(MediaDownload.saveQueued(context, reel, turn));
            } catch (InterruptedException stopped) {
                throw new AssertionError(stopped);
            }
        }, "test-reel-queue");
        queue.start();
        waitForSemaphore(queue);
        assertEquals("a waiting turn began a save", -1, turn.saveId);
        assertEquals(3, MediaDownload.savesInFlight());
        assertNull("a tap must still give up while every slot is held",
                MediaDownload.start(context, true, (writer, progress) -> finished()));

        release.countDown();
        finish(queue);
        assertTrue("the turn never began its save", turn.saveId >= 0);
        assertNotNull("the turn didn't say how its save ended", ended.get());
    }

    /** #449: the queue's Cancel stops a turn still waiting for a slot, and it takes none. */
    @Test
    public void aQueuedReelStoppedWhileWaitingTakesNoSlot() throws Exception {
        CountDownLatch release = latch();
        hold(release);
        hold(release);
        hold(release);
        MediaDownload.ReelSave reel = new MediaDownload.ReelSave(Collections.singletonList(REEL), null,
                DownloadQuality.BEST, PostDetails.NONE);
        MediaDownload.Turn turn = new MediaDownload.Turn();
        AtomicBoolean stopped = new AtomicBoolean();
        Thread queue = thread(() -> {
            try {
                MediaDownload.saveQueued(context, reel, turn);
            } catch (InterruptedException expected) {
                stopped.set(true);
            }
        }, "test-reel-queue");
        queue.start();
        waitForSemaphore(queue);
        queue.interrupt();
        finish(queue);
        assertTrue("a stopped turn didn't say it was stopped", stopped.get());
        assertEquals(-1, turn.saveId);
        assertNull(turn.worker);
        assertEquals("a stopped turn took a slot", 3, MediaDownload.savesInFlight());
        assertEquals(3, SaveControl.running().size());
    }

    private static void waitForSemaphore(Thread chain) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            for (StackTraceElement frame : chain.getStackTrace()) {
                if (frame.getClassName().equals("java.util.concurrent.Semaphore")
                        && frame.getMethodName().equals("acquire")) return;
            }
            assertTrue("the chain ended before waiting for capacity", chain.isAlive());
            Thread.sleep(1);
        }
        throw new AssertionError("the later photo never waited for a slot");
    }

    @Test
    public void preJobAndJobFailuresReleaseTheirSlots() throws Exception {
        MediaDownload.detailsForTests = details -> { throw new IllegalStateException("test setup failed"); };
        assertThrows(IllegalStateException.class,
                () -> MediaDownload.start(context, false, (writer, progress) -> finished()));
        assertEquals(0, MediaDownload.savesInFlight());
        assertTrue(SaveControl.running().isEmpty());
        MediaDownload.detailsForTests = null;
        finish(MediaDownload.start(context, false, (writer, progress) -> {
            throw new IllegalStateException("test job failed");
        }));
        assertEquals(0, MediaDownload.savesInFlight());
    }

    @Test
    public void workerAndChainThreadStartFailuresReleaseExactlyOnce() throws Exception {
        MediaDownload.threadsForTests = (work, name) -> new Thread(work, name) {
            @Override public synchronized void start() { throw new SecurityException("test start refused"); }
        };
        for (int attempt = 0; attempt < 4; attempt++) {
            assertThrows(SecurityException.class,
                    () -> MediaDownload.start(context, false, (writer, progress) -> finished()));
            assertThrows(SecurityException.class, () -> MediaDownload.savePhotos(context,
                    Collections.singletonList(FIRST), Collections.emptyList()));
            assertEquals(0, MediaDownload.savesInFlight());
            assertTrue(SaveControl.running().isEmpty());
        }
        MediaDownload.threadsForTests = this::thread;
        finish(MediaDownload.start(context, false, (writer, progress) -> finished()));
        assertEquals(0, MediaDownload.savesInFlight());
    }

    @Test
    public void aFailedPhotoWorkerStartDoesNotLeakOrSkipTheNextPhoto() throws Exception {
        AtomicBoolean failFirst = new AtomicBoolean(true);
        AtomicInteger photos = new AtomicInteger();
        MediaDownload.policyForTests = new MediaUrlPolicy(host -> {
            photos.incrementAndGet();
            return privateAddress();
        });
        MediaDownload.threadsForTests = (work, name) -> {
            if (name.equals("hushfacebook-save") && failFirst.getAndSet(false)) {
                return new Thread(work, name) {
                    @Override public synchronized void start() { throw new SecurityException("test start refused"); }
                };
            }
            return thread(work, name);
        };
        finish(MediaDownload.savePhotos(context, Arrays.asList(FIRST, SECOND), Collections.emptyList()));
        assertEquals(1, photos.get());
        assertEquals(0, MediaDownload.savesInFlight());
        assertTrue(SaveControl.running().isEmpty());
    }
}
