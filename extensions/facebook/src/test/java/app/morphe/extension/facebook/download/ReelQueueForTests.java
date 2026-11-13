/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.content.Context;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The reel queue for tests outside its package: reels that join it as a tap would add them, and
 * turns that wait until they're let go or stopped.
 */
public final class ReelQueueForTests {
    private ReelQueueForTests() {}

    private static volatile CountDownLatch gate;

    /** Every turn from now waits until {@link #letGo} or a stop, then saves. */
    public static void holdEveryTurn() {
        gate = new CountDownLatch(1);
        ReelQueue.saverForTests = (app, reel, turn) -> {
            CountDownLatch now = gate;
            if (now != null && !now.await(10, TimeUnit.SECONDS)) throw new AssertionError("a held turn never ended");
            return Downloader.Status.OK;
        };
    }

    /** Adds reel [videoId], posted by [owner] or by someone the tap didn't name, quietly. */
    public static boolean add(Context context, String videoId, String owner) {
        PostDetails details = owner == null ? PostDetails.NONE : new PostDetails(videoId, owner, null);
        return ReelQueue.add(context, videoId, new MediaDownload.ReelSave(
            Collections.singletonList("https://video.example/" + videoId), null, DownloadQuality.BEST, details), true);
    }

    /** Lets every turn that waits, and every one after it, save. */
    public static void letGo() {
        CountDownLatch now = gate;
        gate = null;
        if (now != null) now.countDown();
    }

    /** Back to an empty queue. */
    public static void reset() throws InterruptedException {
        letGo();
        ReelQueue.resetForTests();
    }
}
