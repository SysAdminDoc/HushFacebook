/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.Executor;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What the Don't send reel watch history patch asks before Facebook sends the reels you watched.
 *
 * <p>Facebook's Reels viewer queues the id of every reel you watch and sends the queue in batches
 * as one GraphQL mutation, FbShortsSeenStateMutation, whose input is those ids and nothing else. It
 * names no viewer and nobody else ever sees it: it's the record Facebook ranks your Reels feed with.
 * The batcher empties its queue, builds the mutation and hands a runnable to an executor, and that
 * runnable is what gives the request to Facebook's GraphQL layer. The patch puts {@link #send} in
 * place of that hand-over, so a batch held back here has already left the queue and isn't kept
 * for a later send. The switch is read when a batch goes out, so reels queued before it's turned
 * off go out with the next batch.
 *
 * <p>It fails open. The runnable is recognised by the name Redex keeps on its class, and anything
 * else goes to the executor as Facebook handed it over: a runnable this doesn't recognise, the
 * switch off, a pause, settings that aren't ready yet, or a failure in here.
 *
 * <p>"Videos you've watched" in your activity log comes from none of those sends (#408). Every
 * video player logs a {@value #PLAYBACK_EVENTS} analytics event as it plays, and that event is what
 * puts the video in the log (S22, 585, 2026-11-08: holding only those events kept watched reels
 * out of it, holding every other candidate didn't). Every analytics event is made by one factory,
 * where {@code AnalyticsEvents} asks {@link #holdPlaybackEvent}; while the switch is on those
 * events come back as Facebook's own "not logged" event, so they're never sent.
 */
public final class ReelWatchHistory {
    /** The name Redex keeps on the runnable Facebook's seen-state helper hands its executor. */
    static final String SEEN_STATE_SEND = "FbShortsSeenStateMutationHelper$sendFbShortsSeenStateMutation$1";

    /** The static field Redex leaves on a class it renamed, holding the class's name from before. */
    static final String REDEX_NAME = "__redex_internal_original_name";

    /** The diagnostic counter route: each batch Facebook went to send, and the ones held back. */
    static final String ROUTE = "Reel watch history";

    /** What a batch held back is counted under. */
    static final String HELD_BACK = "watched reels";

    /** What a held back watched-position mutation is counted under (#408). */
    static final String POSITION = "watched position";

    /** What a held back view-time event is counted under (#408). */
    static final String VIEW_TIME = "view time";

    /** What a held back playback event is counted under (#408). */
    static final String PLAYBACK = "playback event";

    /**
     * How the analytics events a video player logs as it plays start: media_playback_compound and
     * its _fast, _debug and _second_channel forms, the only names that start this way (585).
     */
    static final String PLAYBACK_EVENTS = "media_playback_compound";

    /** The send's class once it has been recognised, so later batches skip the lookup. */
    private static volatile Class<?> recognised;

    private ReelWatchHistory() {
    }

    /**
     * Injection point, in place of the batcher's {@code executor.execute(send)}, with the same two
     * values. Hands the send to the executor unless the switch holds it back. Whatever the executor
     * throws reaches Facebook as it did before.
     */
    public static void send(Executor executor, Runnable send) {
        if (holdBack(send)) return;
        executor.execute(send);
    }

    /** True when [send] is Facebook's seen-state send and the switch is on. Never throws. */
    static boolean holdBack(Runnable send) {
        try {
            HookStatus.invoked(FamilyNames.REEL_WATCH_HISTORY);
            FeedFilterCounters.sawList(ROUTE, 1);
            // Recognised whatever the switch says, so the report shows the hook works before
            // anyone turns it on.
            if (!isSeenStateSend(send)) return false;
            if (!Utils.settingsReady() || !Settings.DONT_SEND_REEL_WATCH_HISTORY.get()) return false;
            FeedFilterCounters.removed(ROUTE, 1, HELD_BACK);
            Logger.printDebug(() -> "Reel watch history: held back a batch of watched reels");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_WATCH_HISTORY, "reel watch history send", failure);
            return false;
        }
    }

    /**
     * Injection point, first in the method that sends SfdVideoPositionWatchedMutation: the furthest
     * point you reached in a video you watched 10 seconds or more of, wherever it played. True
     * returns before anything is built. Never throws.
     */
    public static boolean holdWatchedPosition() {
        return hold(POSITION, "watched position send");
    }

    /**
     * Injection point, first in the VOD/Reels view-time logger: the video's id, how long you
     * watched it and the feed it came from. True returns before the event is built. Never throws.
     */
    public static boolean holdViewTime() {
        return hold(VIEW_TIME, "view time event");
    }

    /**
     * Injection point, through {@code AnalyticsEvents}, first where Facebook makes an analytics
     * event, handed its name. True for a {@link #PLAYBACK_EVENTS} event while the switch is on, so
     * the video stays out of "Videos you've watched". Every event the app logs comes through here,
     * so the name is checked first, and other events answer false without being counted. Never throws.
     */
    public static boolean holdPlaybackEvent(String event) {
        if (event == null || !event.startsWith(PLAYBACK_EVENTS)) return false;
        return hold(PLAYBACK, "playback event");
    }

    /** True when the switch is on, counted under [what]. Never throws. */
    private static boolean hold(String what, String hook) {
        try {
            HookStatus.invoked(FamilyNames.REEL_WATCH_HISTORY);
            FeedFilterCounters.sawList(ROUTE, 1);
            FeedFilterCounters.sawKind(ROUTE, what);
            if (!Utils.settingsReady() || !Settings.DONT_SEND_REEL_WATCH_HISTORY.get()) return false;
            FeedFilterCounters.removed(ROUTE, 1, what);
            Logger.printDebug(() -> "Reel watch history: held back a " + what);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_WATCH_HISTORY, hook, failure);
            return false;
        }
    }

    /**
     * Whether [send] is an instance of the class Redex named {@link #SEEN_STATE_SEND}, reported
     * under the patch's name either way. A repeat of either report costs a hash lookup, and one
     * made after a diagnostic clear is recorded again.
     */
    private static boolean isSeenStateSend(Runnable send) {
        Class<?> type = send.getClass();
        if (type != recognised && !SEEN_STATE_SEND.equals(redexName(type))) {
            HookStatus.missingMember(FamilyNames.REEL_WATCH_HISTORY, "Redex name", type.getName(), SEEN_STATE_SEND);
            return false;
        }
        recognised = type;
        HookStatus.bound(FamilyNames.REEL_WATCH_HISTORY, SEEN_STATE_SEND);
        return true;
    }

    /** The name Redex left on [type], or null when it left none. */
    private static String redexName(Class<?> type) {
        try {
            Field field = type.getDeclaredField(REDEX_NAME);
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) return null;
            field.setAccessible(true);
            return (String) field.get(null);
        } catch (NoSuchFieldException | IllegalAccessException | SecurityException none) {
            return null;
        }
    }
}
