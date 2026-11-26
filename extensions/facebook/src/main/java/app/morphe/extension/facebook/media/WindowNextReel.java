/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Next reel in the window (#578). In the picture-in-picture window Facebook doesn't show its Reels
 * viewer: the window's viewer (ReelsPipFragment, which the patch finds by its container,
 * reels_pip_container) hides the whole screen and moves the armed player's video into a container
 * of its own, so the reel that ends there plays over and over.
 *
 * <p>With the switch on, as the reel in the window ends ({@link PictureInPicture#playerEnded}), the
 * Reels viewer hidden behind the window is scrolled on a page, as a swipe would, so Facebook binds
 * the next reel to a player and starts it. The first other reel's player that starts or is bound
 * after that, on the page the viewer is on now, becomes the reel in the window, or the one Facebook
 * bound for that page before, as it binds the reel a page ahead early: it's armed for the
 * window as a swipe arms it, and the window's viewer is closed and opened again for it, the way
 * Facebook closes it as the window closes, so the video it held goes back to its page and the next
 * reel's moves in, in that reel's own shape. When nothing is bound within {@link #WAIT_MS}, the viewer
 * goes back a page and the reel plays again as before.
 *
 * <p>Off, paused, with Picture-in-picture off, or when anything here fails, the window is left as
 * Facebook has it.
 */
public final class WindowNextReel {
    /** How long after the scroll a reel may be bound before the viewer goes back a page. */
    static final long WAIT_MS = 2500;
    /** Counted each time the window went on to the next reel. */
    static final String MOVED_ON = "window went on to the next reel";
    /** Counted each time the window's reel ended with no next reel bound, so it played again. */
    static final String NONE_BOUND = "no next reel for the window, the reel played again";
    /** Facebook's config for its window viewer, which keeps its name: an int, a string and the video's aspect. */
    static final String CONFIG = "com.facebook.video.reels.pip.ui.ReelsPipViewerConfig";
    /** The aspect the window viewer gets for a video of no known shape: 9 by 16, as Facebook's own window. */
    static final float PORTRAIT = 9f / 16f;

    private static final String FAMILY = FamilyNames.PICTURE_IN_PICTURE;
    private static final String HOOK = "next reel in the window";

    /** What a move on does to Facebook, apart from reading its views. Tests stand in for it. */
    interface Window {
        /** Arms the window for [player], the reel in it from now on. False when it can't. */
        boolean arm(Object player) throws Exception;

        /** Closes the window's viewer on [screen]. False when it had none. */
        boolean close(Activity screen) throws Exception;

        /** Opens the window's viewer on [screen] for the armed reel, its video [aspect] wide to one high. */
        boolean open(Activity screen, float aspect) throws Exception;

        /** Starts [player] when it isn't playing. */
        void play(Object player) throws Exception;
    }

    static volatile Window window = new Facebooks();

    /** How many of the reel players bound last are kept, for the one Facebook bound a page ahead. */
    static final int RECENT = 6;
    private static final ArrayDeque<WeakReference<Object>> recent = new ArrayDeque<>();

    private static WeakReference<Activity> screen = new WeakReference<>(null);
    private static WeakReference<Object> from = new WeakReference<>(null);
    private static WeakReference<ViewGroup> movedPager = new WeakReference<>(null);
    private static int movedBy;
    private static int round;
    /** The last move on that's over: a reel was taken for it, or the viewer went back. */
    private static int settled;
    private static boolean waiting;

    private WindowNextReel() {
    }

    // Filled in by the patch.

    /**
     * Filled in by the patch: closes the window viewer on [screen] as Facebook closes it with the
     * window, true when it had one. False until the patch fills it in.
     */
    public static boolean closeViewer(Activity screen) {
        return false;
    }

    /**
     * Filled in by the patch: opens the window viewer on [screen] with [config], a {@link #CONFIG},
     * for the reel the window is armed for, as Facebook opens it as the window opens. False until
     * the patch fills it in.
     */
    public static boolean openViewer(Activity screen, Object config) {
        return false;
    }

    // Hooks, through Picture-in-picture's.

    private static boolean on() {
        return Utils.settingsReady() && Settings.NEXT_REEL_IN_WINDOW.get();
    }

    /** [player]'s video ended. When it's the reel in the window, the viewer behind is moved on a page. */
    static void ended(Object player) {
        try {
            if (!on()) return;
            Activity floating = PictureInPicture.windowScreen(player);
            if (floating == null) return;
            HookStatus.bound(FAMILY, HOOK);
            Utils.runOnMainThread(() -> moveOn(floating, player));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
        }
    }

    /**
     * [player] started or was bound. True when it's taken for the window: the window waits for a
     * reel after a move on, and [player] isn't the one that ended. Kept either way with the switch
     * on, since Facebook binds the reel a page ahead while the one before it still plays.
     */
    static boolean seen(@Nullable Object player) {
        try {
            if (player == null || !on()) return false;
            int look;
            synchronized (WindowNextReel.class) {
                remember(player);
                if (!waiting || player == from.get()) return false;
                waiting = false;
                look = round;
            }
            Utils.runOnMainThread(() -> take(look, player));
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
            return false;
        }
    }

    // The move on.

    static void moveOn(Activity floating, Object ended) {
        try {
            if (!on() || PictureInPicture.windowScreen(ended) != floating) return;
            synchronized (WindowNextReel.class) {
                if (waiting) return;
            }
            ViewGroup pager = NextReel.pagerIn(floating, true);
            int by = pager == null ? 0 : NextReel.pageHeight(pager);
            if (pager == null || by <= 0) {
                Logger.printDebug(() -> "Picture-in-picture: the reel in the window ended with no Reels viewer behind it to move on");
                return;
            }
            int look;
            synchronized (WindowNextReel.class) {
                waiting = true;
                look = ++round;
                screen = new WeakReference<>(floating);
                from = new WeakReference<>(ended);
                movedPager = new WeakReference<>(pager);
                movedBy = by;
            }
            pager.scrollBy(0, by);
            Logger.printDebug(() -> "Picture-in-picture: the reel in the window ended, the Reels viewer behind it moved on a "
                    + by + " page");
            Utils.runOnMainThread(() -> boundAhead(look));
            Utils.runOnMainThreadDelayed(() -> gaveUp(look), WAIT_MS);
        } catch (Throwable failure) {
            synchronized (WindowNextReel.class) {
                waiting = false;
            }
            HookStatus.threw(FAMILY, HOOK, failure);
        }
    }

    /**
     * [player] came after the move on: when its video is on the page the viewer is on now, or it
     * shows nowhere yet, it's the reel in the window from now on. A player on another page, the
     * one Facebook gets ready after it, leaves the window waiting.
     */
    static void take(int look, Object player) {
        try {
            Activity floating;
            ViewGroup pager;
            synchronized (WindowNextReel.class) {
                if (look != round) return;
                floating = screen.get();
                pager = movedPager.get();
            }
            if (floating == null || pager == null || !floating.isInPictureInPictureMode() || !on()) return;
            if (!onTopPage(player, pager)) {
                synchronized (WindowNextReel.class) {
                    if (look == round && settled != look) waiting = true;
                }
                Logger.printDebug(() -> "Picture-in-picture: player " + System.identityHashCode(player)
                        + " is on another page, the window waits on");
                return;
            }
            synchronized (WindowNextReel.class) {
                if (look != round || settled == look) return;
                settled = look;
                waiting = false;
            }
            Window facebook = window;
            if (!facebook.arm(player)) {
                Logger.printDebug(() -> "Picture-in-picture: the next reel isn't one the window takes, the reel plays again");
                back(pager, look);
                return;
            }
            double aspect = PictureInPicture.lastAspect;
            float shape = aspect > 0 ? (float) aspect : PORTRAIT;
            boolean closed = facebook.close(floating);
            boolean opened = facebook.open(floating, shape);
            facebook.play(player);
            HookStatus.counted(FAMILY, MOVED_ON);
            Logger.printDebug(() -> "Picture-in-picture: the window went on to player " + System.identityHashCode(player)
                    + " (viewer closed " + closed + ", opened " + opened + ", " + shape + " wide)");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
        }
    }

    /**
     * Right after the move on: a reel Facebook bound before it, a page ahead, whose video shows on
     * the page now on top, is the reel in the window from now on. Nothing binds for that page after
     * the scroll, since Facebook bound it already (S22, 588: the second reel in the window played
     * again). With none, the window waits on for a bind.
     */
    static void boundAhead(int look) {
        try {
            ViewGroup pager;
            List<Object> kept = new ArrayList<>();
            synchronized (WindowNextReel.class) {
                if (look != round || settled == look) return;
                pager = movedPager.get();
                Object ended = from.get();
                for (Iterator<WeakReference<Object>> each = recent.descendingIterator(); each.hasNext(); ) {
                    Object player = each.next().get();
                    if (player != null && player != ended) kept.add(player);
                }
            }
            if (pager == null) return;
            Object found = null;
            for (Object player : kept) {
                View page = pageOf(player, pager);
                if (page != null && onTop(page, pager)) {
                    found = player;
                    break;
                }
            }
            if (found == null) return;
            Object ahead = found;
            Logger.printDebug(() -> "Picture-in-picture: player " + System.identityHashCode(ahead)
                    + " was bound a page ahead, on the page now on top");
            take(look, ahead);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
        }
    }

    /** No reel was taken within {@link #WAIT_MS}: the viewer goes back a page and the reel plays again. */
    static void gaveUp(int look) {
        try {
            ViewGroup pager;
            synchronized (WindowNextReel.class) {
                // Over even while a reel seen just now waits to be looked at, so it can't start the wait again.
                if (look != round || settled == look) return;
                settled = look;
                waiting = false;
                pager = movedPager.get();
            }
            HookStatus.counted(FAMILY, NONE_BOUND);
            Logger.printDebug(() -> "Picture-in-picture: no next reel was bound behind the window, the reel plays again");
            back(pager, look);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
        }
    }

    private static void back(@Nullable ViewGroup pager, int look) {
        int by;
        synchronized (WindowNextReel.class) {
            if (look != round) return;
            by = movedBy;
            movedBy = 0;
        }
        if (pager != null && by > 0) pager.scrollBy(0, -by);
    }

    /**
     * Whether [player]'s video shows on the page at the top of [pager], or nowhere yet: a player is
     * bound before its page lays out its video.
     */
    static boolean onTopPage(Object player, ViewGroup pager) {
        View page = pageOf(player, pager);
        return page == null || onTop(page, pager);
    }

    /** The page of [pager] [player]'s video shows on, or null when it shows nowhere known in [pager]. */
    @Nullable
    private static View pageOf(Object player, ViewGroup pager) {
        View video = PictureInPicture.videoViews.of(player);
        if (video == null) video = PictureInPicture.views.of(player);
        if (video == null) return null;
        View page = video;
        ViewParent parent = page.getParent();
        while (parent instanceof View && parent != pager) {
            page = (View) parent;
            parent = page.getParent();
        }
        return parent == pager ? page : null;
    }

    private static boolean onTop(View page, ViewGroup pager) {
        return Math.abs(page.getTop()) < Math.max(1, pager.getHeight()) / 2;
    }

    /** Keeps [player] as the newest of the reel players bound last. Called holding the lock. */
    private static void remember(Object player) {
        for (Iterator<WeakReference<Object>> each = recent.iterator(); each.hasNext(); ) {
            Object kept = each.next().get();
            if (kept == null || kept == player) each.remove();
        }
        recent.addLast(new WeakReference<>(player));
        while (recent.size() > RECENT) recent.removeFirst();
    }

    /** Forgets any move on and the players kept. */
    static synchronized void forget() {
        recent.clear();
        screen = new WeakReference<>(null);
        from = new WeakReference<>(null);
        movedPager = new WeakReference<>(null);
        movedBy = 0;
        waiting = false;
        round++;
    }

    /** Facebook's own: Picture-in-picture's arming, the patch's viewer stubs and the player reader. */
    static final class Facebooks implements Window {
        private volatile Constructor<?> config;

        @Override
        public boolean arm(Object player) {
            return PictureInPicture.armForWindow(player);
        }

        @Override
        public boolean close(Activity screen) {
            return closeViewer(screen);
        }

        @Override
        public boolean open(Activity screen, float aspect) throws Exception {
            Constructor<?> made = config;
            if (made == null) {
                made = Class.forName(CONFIG).getConstructor(int.class, String.class, float.class);
                config = made;
            }
            return openViewer(screen, made.newInstance(0, "", aspect));
        }

        @Override
        public void play(Object player) throws Exception {
            BackgroundPlay.PlayerReader reader = BackgroundPlay.reader;
            if (!reader.playing(player)) reader.play(player);
        }
    }
}
