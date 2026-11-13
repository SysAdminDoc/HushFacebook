/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.os.SystemClock;
import android.view.MotionEvent;

/** What tests outside this package need of Tap to play: its triggers, a tap and a clean start. */
public final class TapToPlayForTests {
    /** Stands in for Facebook's EventTriggerType: only the constant names matter to the rule. */
    public enum Trigger {
        BY_USER, BY_AUTOPLAY, BY_PLAYER, BY_USER_SWIPE,
        BY_SHORT_FORM_VIDEO_FULLY_VISIBLE, BY_FB_SHORTS_IN_FEED_UNIT_VISIBLE, BY_COLLECTIONS_STORIES_VISIBLE,
        BY_SHORT_FORM_VIDEO_ONRESUME, BY_CURATED_PROMPTS_H_SCROLL_ONRESUME,
        BY_SURFACE_ON_RESUME, BY_FB_SHORTS_INTEREST_PICKER_ON_RESUME, BY_FRAGMENT_RESUME, BY_FLYOUT,
        BY_MEDIA_SESSION_CONTROLS, BY_SEEKBAR_CONTROLLER, BY_MUSIC_PLAYER, BY_ANDROID
    }

    /** Stands in for Facebook's Autoplay setting enum. */
    public enum Autoplay { ON, OFF, WIFI_ONLY, DEFAULT }

    private TapToPlayForTests() { }

    /** A tap that ended [msAgo] milliseconds before now on the clock the players' starts read. */
    public static void tapEnded(long msAgo) {
        long up = SystemClock.uptimeMillis() - msAgo;
        TapClock.record(MotionEvent.ACTION_DOWN, 100, 100, up - 60, 8);
        TapClock.record(MotionEvent.ACTION_UP, 102, 101, up, 8);
    }

    /** Forgets every tap, armed player and log count. */
    public static void forget() {
        TapClock.forget();
        TapClock.slopForTests = -1;
        TapToPlay.forget();
    }
}
