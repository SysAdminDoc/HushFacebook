/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.reels.FeedVideoControls;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Pause reels while comments are open (#266): the reel playing on screen stops when its comment
 * sheet opens and goes on from where it stopped when the sheet closes.
 *
 * <p>Open comments at full height already hands {@link #sheetOpened} the views of every comment
 * sheet as Facebook sets them up. With the switch on, the reel playing on screen then is paused
 * through Tap to play's player bridge, the same BY_USER pause a feed video's play/pause button
 * sends. While a sheet that paused one is open, {@link #holdsStart} keeps any reel from starting by
 * itself: the Reels tab's sheet keeps its reel behind it, and Facebook starts it again as the sheet
 * settles. A start you ask for (BY_USER) still plays. When the sheet's views leave the window and
 * don't come back, the reel that was paused starts again, armed in Tap to play first so its gate
 * doesn't hold a start no tap made.
 *
 * <p>Off, or without Tap to play's bridge in the build, nothing is paused or held.
 *
 * <p>Tap a reel above its comments plays or pauses the reel under the Reels tab's sheet through
 * {@link #tapReel}, the same bridge, whether this switch is on or not.
 */
public final class PauseUnderComments {
    /** The member the report names once a comment sheet has been handed over. */
    static final String SHEET = "comment sheet opened";

    /** What the report counts each time a reel is paused for its comments. */
    static final String PAUSED = "Reel paused for its comments";

    /** What the report counts each time a reel plays on after its comments close. */
    static final String RESUMED = "Reel played on after its comments";

    /** What the report counts each time a reel's own start is held while its comments are open. */
    static final String HELD = "Reel start held while comments are open";

    /** How long a sheet's views may be away from the window before the sheet counts as closed. */
    static final long CLOSE_SETTLE_MS = 300;

    private static final String FAMILY = FamilyNames.FULL_HEIGHT_COMMENTS;
    private static final String BY_USER = "BY_USER";

    /** What a sheet's player work goes through. The tests hand in their own. */
    interface Players {
        List<Object> known();
        boolean playing(Object player);
        boolean reel(Object player);
        @Nullable View view(Object player);
        void command(Object player, boolean play);
    }

    private static final Players PATCHED = new Players() {
        public List<Object> known() { return FeedVideoControls.knownPlayers(); }
        public boolean playing(Object player) { return FeedVideoControls.isPlaying(player); }
        public boolean reel(Object player) {
            Object params = FeedVideoControls.playerParams(player);
            if (params != null && TapToPlay.fbShorts(params)) return true;
            Object origin = FeedVideoControls.playerOrigin(player);
            return origin != null && origin.toString().contains("fb_shorts");
        }
        @Nullable public View view(Object player) { return FeedVideoControls.playerView(player); }
        public void command(Object player, boolean play) { FeedVideoControls.commandPlayer(player, play); }
    };
    static volatile Players access = PATCHED;

    /** The comment sheets open now, by their views, weakly. */
    private static final Map<View, Sheet> OPEN = Collections.synchronizedMap(new WeakHashMap<>());

    /** The reel a sheet paused, weakly, until the last open sheet closes. */
    private static volatile WeakReference<Object> paused = new WeakReference<>(null);

    /** The reel a tap above the comments paused, weakly, so the next tap plays that one again (#617). */
    private static volatile WeakReference<Object> tapped = new WeakReference<>(null);

    @Nullable private static Handler main;

    private PauseUnderComments() {
    }

    /**
     * Called first thing where Facebook sets up a comment sheet's views, whatever Open comments at
     * full height says. With the switch on, the reel playing on screen is paused, and the sheet is
     * watched until it closes.
     */
    public static void sheetOpened(@Nullable View content) {
        try {
            // Off, it leaves Open comments at full height's report as it was.
            if (content == null || !on()) return;
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, SHEET);
            if (OPEN.containsKey(content)) return;
            Sheet sheet = new Sheet(content);
            OPEN.put(content, sheet);
            content.addOnAttachStateChangeListener(sheet);
            if (paused.get() != null) return;
            Object reel = playingReel();
            if (reel == null) return;
            paused = new WeakReference<>(reel);
            access.command(reel, false);
            HookStatus.counted(FAMILY, PAUSED);
            Logger.printDebug(() -> "Pause reels while comments are open: paused the reel under a comment sheet");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, SHEET, failure);
        }
    }

    /**
     * Whether a start of [player] with [trigger] waits because a sheet that paused a reel is still
     * open. Only a reel's start waits, and only one Facebook made by itself: BY_USER plays.
     */
    public static boolean holdsStart(@Nullable Object player, @Nullable Object trigger) {
        try {
            if (player == null || paused.get() == null || OPEN.isEmpty() || !on()) return false;
            String name = trigger instanceof Enum ? ((Enum<?>) trigger).name() : null;
            if (BY_USER.equals(name)) {
                // You played it yourself, so closing the sheet has nothing to give back.
                if (player == paused.get()) paused = new WeakReference<>(null);
                return false;
            }
            if (player != paused.get() && !access.reel(player)) return false;
            HookStatus.counted(FAMILY, HELD);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reel start", failure);
            return false;
        }
    }

    /**
     * A tap on the reel above an open comment sheet (#315), whatever this switch says: the reel
     * playing on screen pauses, and with none playing, the one a sheet paused or else the one shown
     * plays, armed in Tap to play like a reel played on after its comments. The reel a tap paused
     * comes first: the next reel waits shown under the sheet, and playing it brought its sound on
     * (#617). A reel you play or pause this way is yours, so closing the sheet leaves it as it is.
     * False when Tap to play's bridge shows no reel, or for a throw.
     */
    public static boolean tapReel() {
        try {
            Object playing = playingReel();
            if (playing != null) {
                access.command(playing, false);
                tapped = new WeakReference<>(playing);
                Logger.printDebug(() -> "Tap a reel above its comments: paused the reel under the sheet");
                return true;
            }
            Object reel = paused.get();
            if (reel == null) reel = stillShown(tapped.get());
            if (reel == null) reel = shownReel();
            if (reel == null) return false;
            paused = new WeakReference<>(null);
            tapped = new WeakReference<>(null);
            TapToPlay.armForResume(reel);
            access.command(reel, true);
            Logger.printDebug(() -> "Tap a reel above its comments: played the reel under the sheet");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reel tap", failure);
            return false;
        }
    }

    /** The reel playing on screen, or null. One whose view is shown wins over one that isn't. */
    @Nullable
    static Object playingReel() {
        Object hidden = null;
        for (Object player : access.known()) {
            if (!access.playing(player) || !access.reel(player)) continue;
            View view = access.view(player);
            if (view != null && view.isShown()) return player;
            if (hidden == null) hidden = player;
        }
        return hidden;
    }

    /**
     * A reel whose view is shown, playing or not, or null. The one with the most of its view on the
     * screen wins, since the next reel is shown too, laid out below (#617). With none measured yet,
     * the first one shown.
     */
    @Nullable
    static Object shownReel() {
        Object first = null;
        Object best = null;
        long bestArea = 0;
        Rect visible = new Rect();
        for (Object player : access.known()) {
            if (!access.reel(player)) continue;
            View view = access.view(player);
            if (view == null || !view.isShown()) continue;
            if (first == null) first = player;
            if (!view.getGlobalVisibleRect(visible)) continue;
            long area = (long) visible.width() * visible.height();
            if (area > bestArea) {
                bestArea = area;
                best = player;
            }
        }
        return best != null ? best : first;
    }

    /** [player] while Tap to play's bridge still has it as a reel whose view is shown, or null. */
    @Nullable
    private static Object stillShown(@Nullable Object player) {
        if (player == null || !access.reel(player) || !access.known().contains(player)) return null;
        View view = access.view(player);
        return view != null && view.isShown() ? player : null;
    }

    /** [content]'s sheet has closed: once none is open, the reel it paused plays on. */
    static void sheetClosed(View content) {
        Sheet sheet = OPEN.remove(content);
        if (sheet != null) content.removeOnAttachStateChangeListener(sheet);
        if (!OPEN.isEmpty()) return;
        Object reel = paused.get();
        paused = new WeakReference<>(null);
        if (reel == null || !on() || access.playing(reel)) return;
        // Tap to play holds a start no tap made unless the player is armed.
        TapToPlay.armForResume(reel);
        access.command(reel, true);
        HookStatus.counted(FAMILY, RESUMED);
        Logger.printDebug(() -> "Pause reels while comments are open: the reel played on after its comments closed");
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.PAUSE_REELS_UNDER_COMMENTS.get();
    }

    private static void post(Runnable task, long delay) {
        Handler handler = main;
        if (handler == null) handler = main = new Handler(Looper.getMainLooper());
        handler.postDelayed(task, delay);
    }

    /** Forgets every sheet and the paused reel. For tests. */
    static void reset() {
        synchronized (OPEN) {
            OPEN.clear();
        }
        paused = new WeakReference<>(null);
        tapped = new WeakReference<>(null);
        access = PATCHED;
    }

    static boolean pausedOne() {
        return paused.get() != null;
    }

    /** One open sheet's views. Away from the window for {@link #CLOSE_SETTLE_MS}, the sheet has closed. */
    private static final class Sheet implements View.OnAttachStateChangeListener {
        private final WeakReference<View> content;

        Sheet(View content) {
            this.content = new WeakReference<>(content);
        }

        @Override
        public void onViewAttachedToWindow(View view) {
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            post(() -> {
                try {
                    View sheet = content.get();
                    if (sheet != null && !sheet.isAttachedToWindow()) sheetClosed(sheet);
                } catch (Throwable failure) {
                    HookStatus.threw(FAMILY, "comment sheet closed", failure);
                }
            }, CLOSE_SETTLE_MS);
        }
    }
}
