/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import com.facebook.video.engine.api.VideoPlayerParams;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.shared.SettingsContextRule;

/**
 * Save a batch of reels (#449): a long press on Download, with Queue reel saves on, picks how many
 * reels to save. The held reel joins the reel queue, then the batch swipes the viewer on and queues
 * each new reel its sidebar brings, never one behind the held reel or one drawn twice, until it has
 * them all, the reels run out, or the queue is canceled. It waits while the queue is paused or full.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReelBatchTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private Activity activity;
    /** Each queue turn's reel, by the name in its address, in the order the queue started them. */
    private final List<String> turns = new CopyOnWriteArrayList<>();
    /** A latch a reel's queue turn waits on before it ends. */
    private final Map<String, CountDownLatch> holds = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> started = new ConcurrentHashMap<>();
    private int swipes;
    /** What each swipe brings: the reels whose sidebars it builds. */
    private final List<List<String>> comingNext = new ArrayList<>();
    private int nextId = 1;
    private final Map<String, String> ids = new ConcurrentHashMap<>();

    @Before
    public void setUp() throws InterruptedException {
        controller = Robolectric.buildActivity(Activity.class).setup().windowFocusChanged(true);
        activity = controller.get();
        ShadowToast.reset();
        ReelBatch.resetForTests();
        ReelQueue.resetForTests();
        ReelQueue.saverForTests = (app, reel, turn) -> {
            String url = reel.urls.get(0);
            String name = url.substring(url.lastIndexOf('/') + 1, url.length() - ".mp4".length());
            turns.add(name);
            startedOf(name).countDown();
            CountDownLatch hold = holds.get(name);
            if (hold != null) hold.await(10, TimeUnit.SECONDS);
            return Downloader.Status.OK;
        };
        ReelBatch.swipeForTests = () -> {
            swipes++;
            if (!comingNext.isEmpty()) {
                for (String name : comingNext.remove(0)) sidebar(name);
            }
        };
    }

    @After
    public void tearDown() throws InterruptedException {
        for (CountDownLatch hold : holds.values()) hold.countDown();
        ReelBatch.resetForTests();
        ReelQueue.resetForTests();
    }

    /** One reel's player params, as its sidebar holds them: one per reel, kept across redraws. */
    private final Map<String, Object> players = new ConcurrentHashMap<>();

    private Object player(String name) {
        return players.computeIfAbsent(name, ignored -> {
            String id = ids.computeIfAbsent(name, n -> Long.toString(2233445566000000L + nextId++));
            String clip = "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/" + name + ".mp4";
            return new FileNameTemplateTest.ReelSourceParams(new VideoPlayerParams("VideoId: " + id,
                    new FileNameTemplateTest.ReelSource(clip)));
        });
    }

    /** Facebook drawing [name]'s sidebar: its Download button's tap handler, and its touch handler. */
    private ReelDownload sidebar(String name) {
        ReelDownload tap = new ReelDownload(player(name), activity, "hd", "sd", "manifest", 1, true);
        new ReelDownload(player(name), activity, "hd", "sd", "manifest", 2, false);
        return tap;
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

    /** Lets the batch take [count] turns, a settle apart, the queue catching up between them. */
    private static void turns(int count) {
        for (int i = 0; i < count; i++) ShadowLooper.idleMainLooper(ReelBatch.SETTLE_MS, TimeUnit.MILLISECONDS);
    }

    private void finishQueue() throws InterruptedException {
        for (CountDownLatch hold : holds.values()) hold.countDown();
        Thread worker = ReelQueue.workerForTests();
        if (worker != null) {
            worker.join(10_000);
            assertFalse("the queue never emptied", worker.isAlive());
        }
        ShadowLooper.idleMainLooper();
    }

    private static void waitFor(int waiting) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            ReelQueue.Status status = ReelQueue.status();
            if (status != null && status.waiting == waiting) return;
            Thread.sleep(10);
        }
        ReelQueue.Status status = ReelQueue.status();
        assertEquals("reels waiting", waiting, status == null ? 0 : status.waiting);
    }

    // ---- Noting reels ----------------------------------------------------------------------------

    /** Only a reel's tap handler is noted, in building order, and only the latest few are kept. */
    @Test
    public void eachReelsTapHandlerIsNotedInTheOrderItWasBuilt() {
        ReelDownload first = sidebar("first");
        ReelDownload second = sidebar("second");
        new ReelDownload(player("cast"), activity, "hd", "sd", "manifest", 1, true, null, true);
        assertEquals(Arrays.asList(first, second), ReelBatch.notedForTests());

        for (int i = 0; i < ReelBatch.NOTED + 4; i++) sidebar("reel" + i);
        List<ReelDownload> noted = ReelBatch.notedForTests();
        assertEquals(ReelBatch.NOTED, noted.size());
        assertEquals(player("reel" + (ReelBatch.NOTED + 3)), noted.get(noted.size() - 1).playerParams());
        assertEquals(player("reel4"), noted.get(0).playerParams());
    }

    // ---- A batch ---------------------------------------------------------------------------------

    /**
     * The held reel goes first, then the reels built after it, one per swipe: never a reel behind it,
     * never one its sidebar drew again. It ends at the count picked.
     */
    @Test
    public void aBatchQueuesTheHeldReelAndEachReelTheSwipesBring() throws InterruptedException {
        sidebar("behind");
        ReelDownload held = sidebar("held");
        sidebar("next");
        comingNext.add(Arrays.asList("next", "third"));
        comingNext.add(Arrays.asList("behind", "held", "fourth"));
        comingNext.add(Arrays.asList("fifth"));
        comingNext.add(Arrays.asList("sixth"));

        assertTrue(ReelBatch.start(activity, held, 5, null));
        assertTrue(ReelBatch.busy());
        ShadowLooper.idleMainLooper();
        assertEquals("nothing moves before the reel settles", 0, swipes);
        assertTrue(ShadowToast.showedToast(
                "Going through 5 reels for the reel queue. Hold Download again or cancel the queue to stop."));
        turns(6);

        assertFalse(ReelBatch.busy());
        assertEquals(3, swipes);
        assertTrue(ShadowToast.showedToast("Put 5 reels in the reel queue"));
        finishQueue();
        assertEquals(Arrays.asList("held", "next", "third", "fourth", "fifth"), turns);
    }

    /** Three swipes in a row that bring no new reel end it: the reels ran out. */
    @Test
    public void threeSwipesThatBringNothingEndIt() throws InterruptedException {
        ReelDownload held = sidebar("held");
        comingNext.add(Arrays.asList("held"));

        assertTrue(ReelBatch.start(activity, held, 10, null));
        turns(3);
        assertTrue(ReelBatch.busy());
        turns(1);

        assertFalse(ReelBatch.busy());
        assertEquals(ReelBatch.MOST_EMPTY, swipes);
        assertTrue(ShadowToast.showedToast("No more reels came. 1 went in the reel queue."));
        finishQueue();
        assertEquals(Arrays.asList("held"), turns);
    }

    /**
     * A paused queue holds the batch where it is. It swipes again once the queue resumes, and a
     * canceled queue ends it.
     */
    @Test
    public void aPausedQueueHoldsItAndACanceledOneEndsIt() throws InterruptedException {
        holdOf("held");
        ReelDownload held = sidebar("held");
        sidebar("next");
        comingNext.add(Arrays.asList("third"));

        assertTrue(ReelBatch.start(activity, held, 10, null));
        await(startedOf("held"));
        ReelQueue.pause();
        turns(1);
        waitFor(1);
        ShadowLooper.idleMainLooper(30, TimeUnit.SECONDS);
        assertEquals("a paused queue holds the swipes", 0, swipes);
        assertTrue(ReelBatch.busy());

        ReelQueue.resume();
        ShadowLooper.idleMainLooper(ReelBatch.WAIT_MS, TimeUnit.MILLISECONDS);
        assertEquals(1, swipes);
        turns(1);
        waitFor(2);
        assertEquals(2, swipes);

        ReelQueue.cancel();
        ShadowLooper.idleMainLooper();
        assertFalse(ReelBatch.busy());
        turns(3);
        assertEquals("nothing moves after a cancel", 2, swipes);
    }

    /**
     * A full queue takes only the reels it has room for. The rest stay where they are, to join
     * once a turn ends, rather than be turned away.
     */
    @Test
    public void aFullQueueTakesOnlyWhatFitsAndTheRestJoinLater() throws InterruptedException {
        holdOf("first");
        assertTrue(ReelQueue.add(activity, "1", new MediaDownload.ReelSave(
                Arrays.asList("https://video.xx.fbcdn.net/v/first.mp4"), null, DownloadQuality.BEST, PostDetails.NONE)));
        await(startedOf("first"));
        for (int i = 0; i < ReelQueue.MOST_WAITING - 2; i++) {
            assertTrue(ReelQueue.add(activity, null, new MediaDownload.ReelSave(
                    Arrays.asList("https://video.xx.fbcdn.net/v/filler" + i + ".mp4"), null,
                    DownloadQuality.BEST, PostDetails.NONE), true));
        }
        ReelDownload held = sidebar("held");
        sidebar("next");
        sidebar("after");

        assertTrue(ReelBatch.start(activity, held, 10, null));
        turns(1);
        waitFor(ReelQueue.MOST_WAITING);
        ShadowLooper.idleMainLooper(30, TimeUnit.SECONDS);
        assertEquals("a full queue holds the swipes", 0, swipes);

        finishQueue();
        turns(1);
        finishQueue();
        List<String> batch = new ArrayList<>(turns);
        batch.removeIf(name -> !name.equals("held") && !name.equals("next") && !name.equals("after"));
        assertEquals(Arrays.asList("held", "next", "after"), batch);
    }

    /** A screen that closes ends the batch without a word. */
    @Test
    public void aClosedScreenEndsIt() {
        ReelDownload held = sidebar("held");
        assertTrue(ReelBatch.start(activity, held, 10, null));
        controller.pause().stop().destroy();
        turns(1);
        assertFalse(ReelBatch.busy());
        assertEquals(0, swipes);
    }

    // ---- The long press --------------------------------------------------------------------------

    /** The long press lists the sizes, and picking one starts that batch at Download quality. */
    @Test
    public void theLongPressListsTheSizesAndAPickStartsTheBatch() throws InterruptedException {
        ReelDownload held = sidebar("held");
        assertTrue(ReelBatch.ask(activity, held));
        AlertDialog list = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(list);
        ShadowAlertDialog shadow = org.robolectric.Shadows.shadowOf(list);
        assertEquals("Save this reel and the ones after it", String.valueOf(shadow.getTitle()));
        CharSequence[] items = shadow.getItems();
        assertEquals(ReelBatch.SIZES.length, items.length);
        assertEquals("10 reels", String.valueOf(items[0]));
        assertEquals("150 reels", String.valueOf(items[items.length - 1]));

        shadow.clickOnItem(1);
        ShadowLooper.idleMainLooper();
        assertTrue(ReelBatch.busy());
        assertEquals(25, ReelBatch.running.total);
        finishQueue();
        assertEquals(Arrays.asList("held"), turns);
    }

    /** Holding Download again while a batch runs stops it. */
    @Test
    public void holdingAgainStopsIt() {
        ReelDownload held = sidebar("held");
        assertTrue(ReelBatch.start(activity, held, 10, null));
        assertTrue(ReelBatch.ask(activity, sidebar("other")));
        ShadowLooper.idleMainLooper();
        assertFalse(ReelBatch.busy());
        assertTrue(ShadowToast.showedToast("Stopped going through reels"));
        turns(3);
        assertEquals(0, swipes);
    }

    // ---- The swipe -------------------------------------------------------------------------------

    /** The swipe is a finger on the touch screen: down low in the middle, moving up, then lifted. */
    @Test
    public void theSwipeIsAFingerMovingUp() {
        List<Integer> actions = new ArrayList<>();
        List<Float> xs = new ArrayList<>();
        List<Float> ys = new ArrayList<>();
        List<Integer> sources = new ArrayList<>();
        View window = new View(activity) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent event) {
                actions.add(event.getActionMasked());
                xs.add(event.getX());
                ys.add(event.getY());
                sources.add(event.getSource());
                return true;
            }
        };
        window.layout(0, 0, 1000, 2000);

        ReelBatch.swipe(window, new Handler(Looper.getMainLooper()));
        ShadowLooper.idleMainLooper(ReelBatch.SWIPE_STEPS * ReelBatch.SWIPE_STEP_MS, TimeUnit.MILLISECONDS);

        assertEquals(ReelBatch.SWIPE_STEPS + 2, actions.size());
        assertEquals(MotionEvent.ACTION_DOWN, (int) actions.get(0));
        for (int i = 1; i <= ReelBatch.SWIPE_STEPS; i++) assertEquals(MotionEvent.ACTION_MOVE, (int) actions.get(i));
        assertEquals(MotionEvent.ACTION_UP, (int) actions.get(actions.size() - 1));
        assertEquals(1300f, ys.get(0), 0.01f);
        assertEquals(400f, ys.get(ys.size() - 1), 0.01f);
        for (int i = 1; i < ys.size() - 1; i++) assertTrue("the finger moves up", ys.get(i) < ys.get(i - 1));
        for (float x : xs) assertEquals(500f, x, 0.01f);
        for (int source : sources) assertEquals(InputDevice.SOURCE_TOUCHSCREEN, source);
    }
}
