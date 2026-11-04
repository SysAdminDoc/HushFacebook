/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import android.os.SystemClock;
import android.view.MotionEvent;

/** Taps as the story tap controller hands them to {@link StoryTaps}. */
public final class StoryTapsForTests {
    private StoryTapsForTests() {
    }

    /** A tap at [x], [y] that went down at [downAt] and lifted 60 ms later. */
    public static MotionEvent tap(long downAt, float x, float y) {
        return MotionEvent.obtain(downAt, downAt + 60, MotionEvent.ACTION_UP, x, y, 0);
    }

    /** Whether a fresh tap is kept from Facebook's navigation, as a single tap is with double tap on. */
    public static boolean holdsATap() {
        StoryTaps.forgetForTests();
        MotionEvent event = tap(SystemClock.uptimeMillis(), 100f, 400f);
        try {
            return StoryTaps.onTap(new Object(), event);
        } finally {
            event.recycle();
            StoryTaps.forgetForTests();
        }
    }
}
