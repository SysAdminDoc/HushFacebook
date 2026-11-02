/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Keep feed videos muted. Facebook keeps one sound flag for the videos that play by themselves in
 * the feed: off, each one starts silent, and on, each one starts with its sound. A tap on a
 * video's speaker button flips it, and so does a lot that isn't you. Every new app session sets it
 * again from Facebook's own pick ({@link #sessionSound}), which can turn it back on after you leave
 * Facebook for a while and come back, and Facebook asks for sound for reasons of its own as well
 * ({@link #soundChange}): on a phone, twice with BY_ANDROID as it starts, and with
 * BY_NF_TO_VH_DEEPLINK when a feed video is opened full screen.
 *
 * <p>While the switch is on, each session starts muted, and only you turn the sound on: a tap or
 * gesture on a video's sound (Facebook's reasons starting with {@link #BY_USER}) or the volume up
 * key, which Facebook handles inside an activity's onKeyUp or, for a volume change from outside the
 * app, in a method it names {@link #VOLUME_INCREASE}. Anything else asking for sound gets the flag
 * it already has, so a sound you turned on stays on and a muted feed stays muted. A video opened
 * from the feed starts muted too, its speaker showing: letting the open turn the sound on would
 * leave it on for the feed after it. Muting always goes through. Restore feed sound after opened
 * videos works apart from that switch ({@link #viewerSoundTime}). Nothing here may throw into
 * Facebook's player: off, paused, before the settings are ready or when anything here fails,
 * Facebook's own answer goes through.
 */
public final class FeedSound {
    /** Counted under the patch's name each time a new session's sound starts off instead of on. */
    static final String SESSION_MUTED = "Session started muted";

    /** Counted each time a video opened from the feed closed after playing with its sound on. */
    static final String SOUND_PUT_BACK = "Feed sound put back";

    /** Counted each time a sound-on that wasn't yours left the flag as it was. */
    static final String KEPT_MUTED = "Sound kept off";

    /** The start of the names of Facebook's reasons for a tap, swipe or gesture of yours. */
    static final String BY_USER = "BY_USER";

    /** The kept name of the method that turns the sound on after a volume change from outside the app. */
    static final String VOLUME_INCREASE = "maybeUnmuteOnDeviceVolumeIncrease";

    /** The activity method a volume key reaches Facebook through. */
    static final String KEY_UP = "onKeyUp";

    private static final String FAMILY = FamilyNames.KEEP_FEED_VIDEOS_MUTED;

    private FeedSound() {
    }

    /**
     * Injection point, first thing in the sound manager's session start, which sets the flag to
     * [asked]. Answers the flag it sets: off while the switch is on, else [asked].
     */
    public static boolean sessionSound(boolean asked) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, "session start");
            StorySound.newSession();
            if (!asked || !on()) return asked;
            HookStatus.counted(FAMILY, SESSION_MUTED);
            Logger.printDebug(() -> "Keep feed videos muted: a new session starts muted");
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "session start", failure);
            return asked;
        }
    }

    /**
     * Injection point, in the sound manager's setter right after it reads the flag it holds,
     * [current], which it then sets to [asked] for [reason], one of Facebook's enum of reasons.
     * Answers the flag it sets: while the switch is on, a sound-on that isn't yours gets
     * [current], which changes nothing. Everything else gets [asked]. While Mute stories is on, a
     * sound change of yours also tells {@link StorySound} whether you want stories heard.
     */
    public static boolean soundChange(@Nullable Object reason, boolean asked, boolean current) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, "sound change");
            boolean feed = asked && !current && on();
            boolean stories = StorySound.on();
            if (!feed && !stories) return asked;
            boolean yours = yours(reason);
            if (stories && yours) StorySound.yoursSound(asked);
            if (!feed || yours) return asked;
            HookStatus.counted(FAMILY, KEPT_MUTED);
            Logger.printDebug(() -> "Keep feed videos muted: kept the sound off for " + name(reason));
            return current;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "sound change", failure);
            return asked;
        }
    }

    /**
     * Injection point, in the step that runs as a video opened from the feed closes, right after it
     * takes how long, in milliseconds, the video played with its sound on: [soundOn], negative when
     * it never did. Facebook compares that with a time its servers set. Within it, the feed gets back
     * the sound it had before the video opened. Past it, Facebook turns the feed's sound on as if
     * you had tapped the speaker, and keeps it that way (#350). While Restore feed sound after opened
     * videos is on, a video that played with its sound answers a time no threshold is under, so the
     * feed always gets its own sound back. Everything else gets [soundOn].
     */
    public static long viewerSoundTime(long soundOn) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, "opened video close");
            if (soundOn < 0 || !Utils.settingsReady() || !Settings.RESTORE_FEED_SOUND.get()) return soundOn;
            HookStatus.counted(FAMILY, SOUND_PUT_BACK);
            Logger.printDebug(() -> "Restore feed sound: an opened video played " + soundOn
                    + " ms with sound, the feed gets its own sound back");
            return Long.MIN_VALUE;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "opened video close", failure);
            return soundOn;
        }
    }

    /** Whether feed videos are kept muted: the switch on, not paused, and the settings ready. */
    private static boolean on() {
        return Utils.settingsReady() && Settings.KEEP_FEED_VIDEOS_MUTED.get();
    }

    /** Whether a sound-on for [reason] is yours: a tap, swipe or gesture, or the volume up key. */
    static boolean yours(@Nullable Object reason) {
        String name = name(reason);
        if (name != null && name.startsWith(BY_USER)) return true;
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            String method = frame.getMethodName();
            if (KEY_UP.equals(method) || VOLUME_INCREASE.equals(method)) return true;
        }
        return false;
    }

    /** The enum constant's name of [reason], which Facebook keeps, or null when it isn't one. */
    @Nullable
    private static String name(@Nullable Object reason) {
        return reason instanceof Enum ? ((Enum<?>) reason).name() : null;
    }
}
