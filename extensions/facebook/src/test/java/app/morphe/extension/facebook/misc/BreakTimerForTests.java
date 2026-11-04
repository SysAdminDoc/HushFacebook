/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.os.PowerManager;

import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;

import java.time.Duration;

import app.morphe.extension.facebook.settings.Settings;

/** Take breaks as another package's test sees it. */
public final class BreakTimerForTests {
    private BreakTimerForTests() {
    }

    /**
     * Whether the chosen use time's worth of browsing on a feed screen, and a second more, put a
     * break over it. Any longer and a short break could be over again. Back to a fresh process, with
     * nothing on disk, after.
     */
    public static boolean aBreakCovers() {
        BreakTimer.forgetForTests();
        BreakTimer.feedScreen = activity -> true;
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            shadowOf(activity.getSystemService(PowerManager.class)).setIsInteractive(true);
            try {
                BreakTimer.resumed(activity);
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(Settings.BREAK_AFTER.get().minutes).plusSeconds(1));
                return BreakTimer.covering();
            } finally {
                BreakTimer.forgetForTests();
                activity.getSharedPreferences(BreakTimer.STORE, Context.MODE_PRIVATE).edit().clear().commit();
            }
        }
    }

    /**
     * A screen in front with Take breaks counting reels, for a test that starts players. Close it,
     * then call {@link #stopCountingReels}.
     */
    public static ActivityController<Activity> countingReels() {
        BreakTimer.forgetForTests();
        Settings.TAKE_BREAKS.save(true);
        Settings.BREAK_AFTER_REELS.save(true);
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        shadowOf(controller.get().getSystemService(PowerManager.class)).setIsInteractive(true);
        BreakTimer.resumed(controller.get());
        return controller;
    }

    /** How many reels count toward the next break. */
    public static int reelsCounted() {
        return BreakTimer.reelsCounted();
    }

    /** Back to a fresh process with Take breaks off and nothing on disk. */
    public static void stopCountingReels(Context context) {
        BreakTimer.forgetForTests();
        Settings.TAKE_BREAKS.resetToDefault();
        Settings.BREAK_AFTER_REELS.resetToDefault();
        context.getSharedPreferences(BreakTimer.STORE, Context.MODE_PRIVATE).edit().clear().commit();
    }
}
