/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.app.Activity;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Next reel in the background (D#524). Facebook's background player plays the reel you left on
 * over and over. Just before it ends, the reel the Reels viewer had ready after it goes to the
 * extension's fullscreen handler as if you had just left on that one, so Facebook builds its
 * background player and notification for it, and the one that ended is let go.
 *
 * Facebook's background player can only be built for a reel the Reels viewer has bound to a
 * player (its builder looks the reel up among the bound ones), and the viewer binds only the reel
 * on screen, as you swipe to it (S22, 588, 2026-11-22). So with no reel bound after the one
 * ending, the viewer in the screen you left is moved on a reel: its pager is scrolled by a page,
 * which binds a page RecyclerView had built ahead even with the screen stopped, and when the scroll
 * had to build the page, the stopped screen is laid out by hand so Facebook binds it. The reel it
 * binds is handed on to, and so is one the viewer binds by itself (Facebook's Scroll to next reel
 * automatically), so you come back to Facebook on the last reel played. With none, the pager goes
 * back a page.
 *
 * Facebook loads more reels only when the viewer's loading page, the page after the last reel loaded,
 * shows (588's "pagination component visible "), and a stopped screen shows nothing, so a run used to
 * end on the few reels Facebook had loaded (emulator, 588, 2026-11-23: two). The patch hands over the
 * handler Facebook runs when that page shows, and with no reel ready the run runs it, as Facebook would
 * if you had scrolled there, then moves the viewer on onto what loads. The viewer's surface paused with
 * its screen and keeps what loads to itself, so the run resumes it first, as coming back would.
 */
public final class NextReel {
    /** How often the background reel is looked at while it plays. */
    static final long LOOK_MS = 500;
    /** How often it's looked at while paused from its notification. */
    static final long PAUSED_LOOK_MS = 2000;
    /** How many paused looks in a row before the looks stop, about 15 minutes. */
    static final int PAUSED_LOOKS = 450;
    /** How close to its end the reel playing in the background hands on to the next. */
    static final int END_LEAD_MS = 400;
    /** How far back a look must find the reel to count it as started over by Facebook's loop. */
    static final int LOOPED_MS = 1000;
    /** The most reels kept from the Reels viewer's binds; the oldest goes first. */
    static final int KEPT = 12;
    /** The most played reels remembered; the oldest goes first. */
    static final int PLAYED_KEPT = 200;
    /** How long the Reels viewer gets to bind the reel it was scrolled on to. */
    static final long SCROLL_WAIT_MS = 1500;
    /** How long it gets after its screen was laid out by hand. */
    static final long LAID_OUT_WAIT_MS = 2500;
    /** The most views looked at for the Reels viewer's pager. */
    static final int MAX_VIEWS = 4000;
    /** How many times one reel's end moves the viewer on before it just plays again. */
    static final int MOVES = 3;
    /** How many loading pages one reel's end may run, so a page Facebook renders again and again can't ask on and on. */
    static final int ASKS = 2;
    /** The most views counted in one page for the log. */
    private static final int PAGE_VIEWS = 500;

    static final String VIDEO_PLAYER_PARAMS = "com.facebook.video.engine.api.VideoPlayerParams";
    static final String LIST = "androidx.recyclerview.widget.RecyclerView";
    /** Litho's component tree, which keeps its name: a surface and the views it renders into each hold one. */
    static final String COMPONENT_TREE = "com.facebook.litho.ComponentTree";
    private static final String PLAYER_ORIGIN = "PlayerOrigin";

    private static final String SOURCE = "BackgroundPlay";
    private static final String HOOK = "next reel";
    private static final String HOOK_SURFACE = "surface pause";

    /** A reel the Reels viewer bound to a player, in the order bound. */
    static final class Bound {
        final WeakReference<Object> params;
        final WeakReference<Object> origin;
        final String place;

        Bound(Object params, Object origin) {
            this.params = new WeakReference<>(params);
            this.origin = new WeakReference<>(origin);
            this.place = String.valueOf(origin);
        }
    }

    /** One reel to hand on to, with what its record needs. */
    static final class Next {
        final Object params;
        final Object origin;
        final String video;
        final int waiting;

        Next(Object params, Object origin, String video, int waiting) {
            this.params = params;
            this.origin = origin;
            this.video = video;
            this.waiting = waiting;
        }
    }

    private static final List<Bound> BOUND = new ArrayList<>();
    /** The reels that started in the Reels viewer or played in the background, oldest first. */
    private static final Set<String> PLAYED = new LinkedHashSet<>();

    /** The session and the last record the extension's handler was asked about. */
    @Nullable
    private static volatile Object session;
    @Nullable
    private static volatile Object template;
    /** True while the extension hands the next reel to its handler. */
    static volatile boolean advancing;
    /** Which round of looks is the live one, so an old round stops by itself. */
    private static volatile int round;
    private static int lastAt = -1;
    private static int pausedLooks;
    /** How many binds the binder told of with the switch on, in Reels or not. For the log. */
    private static volatile int binds;
    /** The Facebook screen that stopped last, the one you left. */
    private static WeakReference<Activity> stoppedScreen = new WeakReference<>(null);
    /** The round whose Reels viewer was moved on, and how far: 0 not moved, 1 scrolled, 2 back and on, 3 laid out. */
    private static int movedRound = -1;
    private static int moveStage;
    /** How many times the reel playing in the background moved the viewer on. */
    private static int moves;
    private static WeakReference<ViewGroup> movedPager = new WeakReference<>(null);
    /** How far the move scrolled: one page, which can be taller than the pager when a reel runs under the tab bar. */
    private static int movedBy;
    /**
     * The round whose reel came to its end with no reel ready: the next reel the viewer binds is
     * handed on to, whether the run moved it or Facebook did (its Scroll to next reel automatically).
     */
    private static int waitingRound = -1;
    /** The handler Facebook runs when the Reels viewer's loading page shows, from the page's last render. */
    private static WeakReference<Object> loadingShown = new WeakReference<>(null);
    /** The last of those the run ran: Facebook runs each once, when its page shows. */
    private static WeakReference<Object> ranShown = new WeakReference<>(null);
    /** True while the reel playing in the background has come to its end with no reel ready after it. */
    private static volatile boolean wantMore;
    /** The round whose reel ran a loading page, and how many it ran. */
    private static int askedRound = -1;
    private static int asks;
    /**
     * Facebook's surfaces that paused with their screen and weren't resumed here. A paused surface
     * keeps each new list of reels it hears about without showing it, so the Reels viewer only gets
     * the reels Facebook loads once its surface resumes.
     */
    private static final List<WeakReference<Object>> PAUSED_SURFACES = new ArrayList<>();
    /** Makes Facebook's runnable that resumes a surface, found from the first surface that paused. */
    @Nullable
    private static volatile Constructor<?> resumer;

    @Nullable
    private static Field paramsField;
    @Nullable
    private static Field idField;

    private NextReel() { }

    // Filled in by the patch.

    /** The name of the player's release taking only a trigger. The patch replaces this body. */
    @Nullable
    public static String releaseMethod() {
        return null;
    }

    // Hooks.

    /** First thing in the fullscreen handler's check: the handler, the session and the record. */
    public static void checking(Object asking, Object user, Object record) {
        try {
            if (asking == null || asking != BackgroundPlay.handler || record == null) return;
            session = user;
            template = record;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, HOOK, failure);
        }
    }

    /** First thing in the player's binder: the player, its origin and the reel's rich params. */
    public static void bound(Object player, Object origin, Object params) {
        try {
            if (params == null || origin == null || !on()) return;
            binds++;
            if (!BackgroundPlay.reelsOrigin(origin)) return;
            HookStatus.bound(FamilyNames.BACKGROUND_PLAY, "reel bind");
            int kept;
            synchronized (BOUND) {
                purge();
                for (Bound each : BOUND) {
                    if (each.params.get() == params) return;
                }
                if (BOUND.size() >= KEPT) BOUND.remove(0);
                BOUND.add(new Bound(params, origin));
                kept = BOUND.size();
            }
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> "Background play: Reels bound "
                    + readable(params) + " in " + origin + " (" + kept + " kept)");
            int look = round;
            if (waitingRound == look) Utils.runOnMainThread(() -> afterBind(look));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "reel bind", failure);
        }
    }

    /**
     * The Reels viewer's loading page made the handler Facebook runs when the page shows, the one that
     * asks for more reels. Pages render off the main thread too.
     */
    public static void loadingPage(Object shown) {
        try {
            if (shown == null) return;
            loadingShown = new WeakReference<>(shown);
            HookStatus.bound(FamilyNames.BACKGROUND_PLAY, "loading page");
            if (wantMore) Utils.runOnMainThread(NextReel::askForMore);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "loading page", failure);
        }
    }

    /** First thing in one of Facebook's surfaces pausing with its screen, with the class of the runnable that resumes one. */
    public static void surfacePaused(Object surface, Class<?> resume) {
        try {
            if (surface == null || resume == null) return;
            HookStatus.bound(FamilyNames.BACKGROUND_PLAY, HOOK_SURFACE);
            if (resumer == null) resumer = resumerOf(resume, surface.getClass());
            synchronized (PAUSED_SURFACES) {
                dropSurface(surface);
                if (PAUSED_SURFACES.size() >= KEPT) PAUSED_SURFACES.remove(0);
                PAUSED_SURFACES.add(new WeakReference<>(surface));
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, HOOK_SURFACE, failure);
        }
    }

    /** The constructor of [resume] that takes just a [surface], when [resume] is a Runnable; otherwise null. */
    @Nullable
    static Constructor<?> resumerOf(Class<?> resume, Class<?> surface) {
        if (!Runnable.class.isAssignableFrom(resume)) return null;
        for (Constructor<?> each : resume.getDeclaredConstructors()) {
            Class<?>[] takes = each.getParameterTypes();
            if (takes.length == 1 && takes[0].isAssignableFrom(surface)) {
                each.setAccessible(true);
                return each;
            }
        }
        return null;
    }

    /** Drops [surface] and any surface let go from the paused ones. Under PAUSED_SURFACES. */
    private static void dropSurface(Object surface) {
        for (Iterator<WeakReference<Object>> each = PAUSED_SURFACES.iterator(); each.hasNext(); ) {
            Object held = each.next().get();
            if (held == null || held == surface) each.remove();
        }
    }

    // What the background player hooks call.

    /** Facebook's background player took a reel on: from now on it's looked at until it nears its end. */
    static void carried() {
        try {
            int look = ++round;
            lastAt = -1;
            pausedLooks = 0;
            moves = 0;
            if (!on()) return;
            Object record = template;
            if (record == null || !BackgroundPlay.reelsOrigin(originOf(record))) return;
            played(videoOf(record));
            lookLater(look, LOOK_MS);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, HOOK, failure);
        }
    }

    /**
     * A player started: a reel the Reels viewer plays has been seen, so it isn't one to hand on to.
     * One starting while Facebook is in the background is the viewer's own, moved on by the run.
     */
    static void started(Object player) {
        try {
            if (!on() || BackgroundPlay.session || !BackgroundPlay.inReels(player)) return;
            played(BackgroundPlay.reader.videoId(player));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, HOOK, failure);
        }
    }

    /** The handler's return to Facebook ran: the run is over. */
    static void stop() {
        round++;
        lastAt = -1;
        moveStage = 0;
        waitingRound = -1;
        wantMore = false;
    }

    /** A Facebook screen stopped: the last one is the one you left, with the Reels viewer in it. */
    static void stopped(Activity screen) {
        stoppedScreen = new WeakReference<>(screen);
    }

    private static void played(@Nullable String video) {
        if (video == null) return;
        synchronized (BOUND) {
            PLAYED.remove(video);
            PLAYED.add(video);
            Iterator<String> oldest = PLAYED.iterator();
            while (PLAYED.size() > PLAYED_KEPT) {
                oldest.next();
                oldest.remove();
            }
        }
    }

    // The looks.

    private static void lookLater(int look, long delay) {
        Utils.runOnMainThreadDelayed(() -> look(look), delay);
    }

    static void look(int look) {
        try {
            if (look != round || !BackgroundPlay.session || !on()) return;
            Object player = BackgroundPlay.currentBackgroundPlayer();
            if (player == null) {
                lookLater(look, LOOK_MS);
                return;
            }
            BackgroundPlay.PlayerReader reader = BackgroundPlay.reader;
            int at = reader.position(player);
            int length = reader.length(player);
            if (!reader.playing(player)) {
                lastAt = at;
                if (++pausedLooks <= PAUSED_LOOKS) lookLater(look, PAUSED_LOOK_MS);
                return;
            }
            pausedLooks = 0;
            boolean looped = lastAt >= 0 && at >= 0 && at + LOOPED_MS < lastAt;
            boolean ending = length > 0 && at >= 0 && length - at <= END_LEAD_MS;
            int before = lastAt;
            lastAt = at;
            if (ending || looped) {
                handOn(look, player, looped ? before : at, length);
                return;
            }
            long wait = LOOK_MS;
            if (length > 0 && at >= 0) wait = Math.max(50, Math.min(LOOK_MS, length - at - END_LEAD_MS));
            lookLater(look, wait);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, HOOK, failure);
        }
    }

    /**
     * Hands the next reel to the extension's handler with a record of its own, as Facebook's manager
     * would have on a leave from it. Facebook's handler builds the background player and notification
     * for it and keeps it as its own, so its return lets go of that one; the one that ended goes here.
     */
    private static void handOn(int look, Object ended, int at, int length) throws Exception {
        Object record = template;
        if (record == null || session == null || BackgroundPlay.handler == null) return;
        Next next = next(record);
        if (next == null) {
            waitingRound = look;
            wantMore = true;
            askForMore();
            boolean moved = moves < MOVES && moveOn(look);
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> "Background play: the reel came to its end at "
                    + at + " of " + length + " with no reel ready after it, "
                    + (moved ? "so the Reels viewer was scrolled on a reel" : "so it plays again until the viewer binds one"));
            return;
        }
        handOnTo(ended, next, at, length);
    }

    /** A reel the viewer bound while the run waited for one: it's handed on to. */
    static void afterBind(int look) {
        try {
            if (look != round || waitingRound != look || !BackgroundPlay.session || !on()) return;
            Object record = template;
            Object player = BackgroundPlay.currentBackgroundPlayer();
            if (record == null || player == null) return;
            Next next = next(record);
            if (next == null) return;
            int stage = movedRound == look ? moveStage : 0;
            waitingRound = -1;
            moveStage = 0;
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> "Background play: the Reels viewer bound "
                    + next.video + (stage == 1 ? " after the scroll" : stage == 2 ? " once moved back and on"
                    : stage == 3 ? " once laid out" : " by itself"));
            BackgroundPlay.PlayerReader reader = BackgroundPlay.reader;
            handOnTo(player, next, reader.position(player), reader.length(player));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, HOOK, failure);
        }
    }

    /** Hands [next] to the handler; the reel playing in the background, [ended], is let go once it took. */
    private static void handOnTo(Object ended, Next next, int at, int length) throws Exception {
        Object record = template;
        Object user = session;
        Object handler = BackgroundPlay.handler;
        if (record == null || user == null || handler == null) return;
        Object made = recordFor(record, next);
        boolean took = false;
        Throwable failed = null;
        advancing = true;
        try {
            took = Boolean.TRUE.equals(checkOf(handler, user, made).invoke(handler, user, made));
        } catch (InvocationTargetException thrown) {
            failed = thrown.getCause() != null ? thrown.getCause() : thrown;
        } finally {
            advancing = false;
            BackgroundPlay.carrying = false;
        }
        if (!took) {
            // The one that ended goes on as before, so its session does too.
            BackgroundPlay.session = true;
            String why = failed == null ? "Facebook's handler said no" : failed.toString();
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE,
                    () -> "Background play: the next reel didn't start (" + why + "), the one that ended plays again");
            return;
        }
        wantMore = false;
        Object started = BackgroundPlay.currentBackgroundPlayer();
        if (started != ended) release(ended);
        Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> "Background play: the reel came to its end at "
                + at + " of " + length + ", the next one plays (" + (next.waiting - 1) + " more ready)");
    }

    // Asking Facebook for more reels.

    /**
     * Runs the loading page's shown handler, as Facebook does when the page shows, so Facebook loads
     * more reels for the stopped screen. Each handler runs once, since a render makes a new one, and
     * one reel's ends run at most {@link #ASKS}. The viewer's paused surface resumes first, or the
     * reels Facebook loads would reach the viewer only once you came back.
     */
    static void askForMore() {
        try {
            if (!wantMore || !BackgroundPlay.session || !on()) return;
            resumeSurface();
            Object shown = loadingShown.get();
            if (shown == null || shown == ranShown.get()) return;
            int look = round;
            if (askedRound != look) {
                askedRound = look;
                asks = 0;
            }
            if (asks >= ASKS) return;
            asks++;
            ranShown = new WeakReference<>(shown);
            shown.getClass().getMethod("invoke", Object.class).invoke(shown, (Object) null);
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE,
                    () -> "Background play: no reel ready, so Facebook was asked for more reels");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "loading page", failure);
        }
    }

    /**
     * Resumes the paused surface that renders the Reels viewer, with Facebook's own runnable, as the
     * screen starting would: it shows the reels Facebook loaded meanwhile and then each new list as it
     * comes. Facebook resuming it again when you come back finds it resumed and leaves it.
     */
    static void resumeSurface() {
        Constructor<?> make = resumer;
        Activity screen = stoppedScreen.get();
        if (make == null || screen == null) return;
        ViewGroup pager = pagerIn(screen);
        if (pager == null) return;
        Set<Object> above = treesAbove(pager);
        List<Object> viewers = new ArrayList<>();
        synchronized (PAUSED_SURFACES) {
            for (WeakReference<Object> each : PAUSED_SURFACES) {
                Object held = each.get();
                if (held != null && above.contains(treeOf(held))) viewers.add(held);
            }
        }
        for (Object surface : viewers) {
            synchronized (PAUSED_SURFACES) {
                dropSurface(surface);
            }
            try {
                ((Runnable) make.newInstance(surface)).run();
                Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> "Background play: the Reels viewer's "
                        + "surface was resumed, so it shows the reels Facebook loads");
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.BACKGROUND_PLAY, HOOK_SURFACE, failure);
            }
        }
    }

    /** The component trees [view] and the views above it hold. */
    static Set<Object> treesAbove(View view) {
        Set<Object> trees = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object at = view; at instanceof View; at = ((View) at).getParent()) {
            Object tree = treeOf(at);
            if (tree != null) trees.add(tree);
        }
        return trees;
    }

    /** The component tree [owner] holds in a field of its own or a superclass's, or null. */
    @Nullable
    static Object treeOf(Object owner) {
        for (Class<?> type = owner.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !COMPONENT_TREE.equals(field.getType().getName())) continue;
                try {
                    field.setAccessible(true);
                    Object tree = field.get(owner);
                    if (tree != null) return tree;
                } catch (Throwable unreadable) {
                    // Another field may hold it.
                }
            }
        }
        return null;
    }

    // Moving the Reels viewer on.

    /**
     * Scrolls the Reels viewer in the screen you left on by a page, so it binds the next reel. The
     * bind that follows hands on ({@link #afterBind}), and with none {@link #afterMove} lays the
     * screen out. False when there's no viewer to move.
     */
    static boolean moveOn(int look) {
        Activity screen = stoppedScreen.get();
        if (screen == null || screen.isFinishing()) return false;
        ViewGroup pager = pagerIn(screen);
        if (pager == null) {
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE,
                    () -> "Background play: no Reels viewer to move on in " + screen.getClass().getName());
            return false;
        }
        movedRound = look;
        moveStage = 1;
        moves++;
        movedPager = new WeakReference<>(pager);
        movedBy = pageHeight(pager);
        String before = pages(pager);
        pager.scrollBy(0, movedBy);
        Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> "Background play: scrolled the Reels viewer "
                + pager.getWidth() + "x" + pager.getHeight() + " a " + movedBy + " page from " + before + " to " + pages(pager));
        Utils.runOnMainThreadDelayed(() -> afterMove(look, 1), SCROLL_WAIT_MS);
        return true;
    }

    /**
     * The wait after a move ran out with no new reel bound ({@link #afterBind} takes one that is).
     * After the scroll, the pager goes back a page and on again, so the page the scroll had to build,
     * laid out by now, is put back as one built ahead is: Facebook bound the next reel inside a scroll
     * only when RecyclerView had built its page ahead (S22, 588, 2026-11-22). After that, the stopped
     * screen is laid out as Android would if it showed. With still nothing, the pager goes back a
     * page, so you come back to the reel that played, and it plays again until the viewer binds one.
     */
    static void afterMove(int look, int stage) {
        try {
            if (look != round || waitingRound != look || movedRound != look || moveStage != stage
                    || !BackgroundPlay.session || !on()) {
                return;
            }
            ViewGroup pager = movedPager.get();
            if (pager == null) {
                moveStage = 0;
                return;
            }
            if (stage == 1) {
                moveStage = 2;
                pager.scrollBy(0, -movedBy);
                String back = pages(pager);
                pager.scrollBy(0, movedBy);
                Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> "Background play: moved the Reels viewer back to "
                        + back + " and on again to " + pages(pager));
                Utils.runOnMainThreadDelayed(() -> afterMove(look, 2), SCROLL_WAIT_MS);
                return;
            }
            if (stage == 2) {
                moveStage = 3;
                layOut(pager);
                Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE,
                        () -> "Background play: laid the Reels viewer out by hand, " + pages(pager));
                Utils.runOnMainThreadDelayed(() -> afterMove(look, 3), LAID_OUT_WAIT_MS);
                return;
            }
            moveStage = 0;
            pager.scrollBy(0, -movedBy);
            boolean again = moves < MOVES;
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> "Background play: the Reels viewer bound no "
                    + "reel after it moved, so it went back to " + pages(pager) + " and the reel plays again"
                    + (again ? ", to move the viewer on again when it ends" : ""));
            if (again) {
                // The page this move built is one built ahead the next time, and Facebook may have loaded more reels.
                lastAt = -1;
                lookLater(look, LOOK_MS);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, HOOK, failure);
        }
    }

    /**
     * The pager's attached pages for the log: each one's top, height and how many views it holds (a
     * page still loading holds few), and the pager's scroll offset.
     */
    private static String pages(ViewGroup pager) {
        StringBuilder found = new StringBuilder().append(offset(pager)).append(" [");
        for (int i = 0; i < pager.getChildCount(); i++) {
            View page = pager.getChildAt(i);
            found.append(i == 0 ? "" : ", ").append(page.getTop()).append('+').append(page.getHeight())
                    .append(':').append(viewsIn(page));
        }
        return found.append(']').toString();
    }

    private static int viewsIn(View page) {
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(page);
        int seen = 0;
        while (!pending.isEmpty() && seen < PAGE_VIEWS) {
            View view = pending.poll();
            seen++;
            if (!(view instanceof ViewGroup)) continue;
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) pending.add(group.getChildAt(i));
        }
        return seen;
    }

    /**
     * Measures and lays out the screen [pager] is in at its own size, as a traversal would, then
     * scrolls the pager a pixel there and back so its scroll listeners run on the new layout.
     */
    static void layOut(ViewGroup pager) {
        View root = pager.getRootView();
        root.measure(View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(root.getHeight(), View.MeasureSpec.EXACTLY));
        root.layout(root.getLeft(), root.getTop(), root.getRight(), root.getBottom());
        pager.scrollBy(0, 1);
        pager.scrollBy(0, -1);
    }

    /**
     * The Reels viewer's pager in [screen]: a RecyclerView ({@link #LIST}) holding a page its own
     * size, with a page after it to go to.
     */
    @Nullable
    static ViewGroup pagerIn(Activity screen) {
        Window window = screen.getWindow();
        View decor = window == null ? null : window.peekDecorView();
        if (decor == null) return null;
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(decor);
        int seen = 0;
        while (!pending.isEmpty() && seen++ < MAX_VIEWS) {
            View view = pending.poll();
            // A stopped screen's decor is INVISIBLE (S22, 588), so only a gone view is passed over.
            if (view.getVisibility() == View.GONE || !(view instanceof ViewGroup)) continue;
            ViewGroup group = (ViewGroup) view;
            if (group.getHeight() > 0 && isList(group) && group.canScrollVertically(1) && holdsAPage(group)) return group;
            for (int i = 0; i < group.getChildCount(); i++) pending.add(group.getChildAt(i));
        }
        return null;
    }

    private static boolean isList(View view) {
        for (Class<?> type = view.getClass(); type != null && type != View.class; type = type.getSuperclass()) {
            if (type.getName().equals(LIST)) return true;
        }
        return false;
    }

    /**
     * Whether [pager] holds a page: as wide as it and at least as tall. A reel runs under the tab bar
     * in some Reels layouts, so its page is taller than the pager (emulator, 588, 2026-11-23: 2272 in 2062).
     */
    private static boolean holdsAPage(ViewGroup pager) {
        return pageHeight(pager) > 0;
    }

    /** The height of [pager]'s pages, from the tallest one it holds, or 0 with none. */
    static int pageHeight(ViewGroup pager) {
        int tallest = 0;
        for (int i = 0; i < pager.getChildCount(); i++) {
            View page = pager.getChildAt(i);
            if (page.getWidth() == pager.getWidth() && page.getHeight() >= pager.getHeight()) {
                tallest = Math.max(tallest, page.getHeight());
            }
        }
        return tallest;
    }

    /** The pager's scroll offset, for the log, or -1. RecyclerView makes View's protected one public. */
    private static int offset(View pager) {
        try {
            Object value = pager.getClass().getMethod("computeVerticalScrollOffset").invoke(pager);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable unreadable) {
            return -1;
        }
    }

    /** The first reel bound after the one you left on, or after any played since, that hasn't played. */
    @Nullable
    static Next next(Object record) throws Exception {
        Object leftOrigin = originOf(record);
        String place = String.valueOf(leftOrigin);
        List<Object[]> ready = new ArrayList<>();
        StringBuilder found = new StringBuilder();
        synchronized (BOUND) {
            purge();
            int after = -1;
            for (int i = 0; i < BOUND.size(); i++) {
                Bound each = BOUND.get(i);
                Object params = each.params.get();
                String video = params == null ? null : videoOfParams(params);
                found.append(i == 0 ? "" : ", ").append(video).append(PLAYED.contains(video) ? " (played)" : "")
                        .append(place.equals(each.place) ? "" : " in " + each.place);
                if (params == null || !place.equals(each.place)) continue;
                if (video != null && PLAYED.contains(video)) after = i;
            }
            Set<String> seen = new HashSet<>();
            for (int i = after + 1; i < BOUND.size(); i++) {
                Bound each = BOUND.get(i);
                Object params = each.params.get();
                Object origin = each.origin.get();
                if (params == null || origin == null || !place.equals(each.place)) continue;
                String video = videoOfParams(params);
                if (video == null || PLAYED.contains(video) || !seen.add(video)) continue;
                ready.add(new Object[] {params, origin, video});
            }
        }
        if (ready.isEmpty()) {
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> "Background play: no reel ready after "
                    + videoOfSafely(record) + " in " + place + ", bound: [" + found + "] of " + binds + " binds");
            return null;
        }
        Object[] first = ready.get(0);
        return new Next(first[0], first[1], (String) first[2], ready.size());
    }

    // Reading Facebook's objects.

    /** A record like [record] for [next]: a pause just now, so the handler's check takes it. */
    static Object recordFor(Object record, Next next) throws Exception {
        Constructor<?> made = null;
        for (Constructor<?> each : record.getClass().getDeclaredConstructors()) {
            if (each.getParameterTypes().length >= 5) {
                if (made != null) throw new IllegalStateException("the record has more than one constructor");
                made = each;
            }
        }
        if (made == null) throw new IllegalStateException("the record has no constructor to copy it with");
        made.setAccessible(true);
        Class<?>[] types = made.getParameterTypes();
        Object[] values = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            if (type == String.class) {
                values[i] = next.video;
            } else if (type == long.class) {
                // Only Facebook's own manager reads it, and it isn't asking.
                values[i] = SystemClock.elapsedRealtime();
            } else if (type == boolean.class) {
                // The first says a play rather than a pause, which the check turns down.
                values[i] = false;
            } else if (type.isInstance(next.origin)) {
                values[i] = next.origin;
            } else if (type.isInstance(next.params)) {
                values[i] = next.params;
            } else {
                values[i] = fieldOfType(record.getClass(), type).get(record);
            }
        }
        return made.newInstance(values);
    }

    /** The handler's check, (session, record)Z. */
    static Method checkOf(Object handler, Object user, Object record) throws NoSuchMethodException {
        for (Method each : handler.getClass().getMethods()) {
            Class<?>[] types = each.getParameterTypes();
            if (each.getReturnType() == boolean.class && types.length == 2 && types[0].isInstance(user)
                    && types[1].isInstance(record)) {
                return each;
            }
        }
        throw new NoSuchMethodException(handler.getClass().getName() + " has no check of " + record.getClass().getName());
    }

    /** Pauses and releases [player], as the handler's return does with its own. */
    static void release(Object player) {
        try {
            BackgroundPlay.reader.release(player, BackgroundPlay.BACKGROUND_TRIGGER);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "next reel release", failure);
        }
    }

    @Nullable
    static Object originOf(Object record) throws Exception {
        for (Field each : record.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(each.getModifiers()) || each.getType().isPrimitive() || each.getType().isEnum()) continue;
            if (each.getType().getSimpleName().equals(PLAYER_ORIGIN)) {
                each.setAccessible(true);
                return each.get(record);
            }
        }
        return null;
    }

    /** The record's video id, its one String. */
    @Nullable
    static String videoOf(Object record) throws Exception {
        Object id = fieldOfType(record.getClass(), String.class).get(record);
        return id instanceof String ? (String) id : null;
    }

    /**
     * The video id the rich params [params] hold, in their VideoPlayerParams. Which of its Strings
     * that is comes from the record the handler was asked about, whose video id it holds.
     */
    @Nullable
    static String videoOfParams(Object params) throws Exception {
        Object player = playerParams(params);
        if (player == null) return null;
        // Reel speed's patch fills this in when it's in the bundle.
        String patched = ReelSpeed.videoId(player);
        if (patched != null) return patched;
        Field id = idField;
        if (id == null || !id.getDeclaringClass().isInstance(player)) {
            id = findIdField(player);
            if (id == null) return null;
            idField = id;
        }
        Object value = id.get(player);
        return value instanceof String ? (String) value : null;
    }

    @Nullable
    private static Object playerParams(Object params) throws IllegalAccessException {
        Field found = paramsField;
        if (found == null || !found.getDeclaringClass().isInstance(params)) {
            found = null;
            for (Class<?> type = params.getClass(); type != null && found == null; type = type.getSuperclass()) {
                for (Field each : type.getDeclaredFields()) {
                    if (!Modifier.isStatic(each.getModifiers()) && each.getType().getName().equals(VIDEO_PLAYER_PARAMS)) {
                        each.setAccessible(true);
                        found = each;
                        break;
                    }
                }
            }
            if (found == null) return null;
            paramsField = found;
        }
        return found.get(params);
    }

    @Nullable
    private static Field findIdField(Object player) throws Exception {
        Object record = template;
        if (record == null) return null;
        String video = videoOf(record);
        if (video == null) return null;
        Object left = null;
        for (Field each : record.getClass().getDeclaredFields()) {
            Class<?> type = each.getType();
            if (Modifier.isStatic(each.getModifiers()) || type.isPrimitive() || type.isEnum() || type == String.class
                    || type.getSimpleName().equals(PLAYER_ORIGIN)) {
                continue;
            }
            each.setAccessible(true);
            Object value = each.get(record);
            left = value == null ? null : playerParams(value);
            if (left != null) break;
        }
        if (left == null) return null;
        for (Class<?> type = left.getClass(); type != null; type = type.getSuperclass()) {
            for (Field each : type.getDeclaredFields()) {
                if (Modifier.isStatic(each.getModifiers()) || each.getType() != String.class) continue;
                each.setAccessible(true);
                if (video.equals(each.get(left)) && each.getDeclaringClass().isInstance(player)) return each;
            }
        }
        return null;
    }

    /** [params]' video id for a log line, or what's known of them when it can't be read yet. */
    private static String readable(Object params) {
        try {
            Object player = playerParams(params);
            String patched = player == null ? null : ReelSpeed.videoId(player);
            if (patched != null) return patched;
            String video = template == null ? null : videoOfParams(params);
            return video != null ? video : params.getClass().getName();
        } catch (Throwable unreadable) {
            return params.getClass().getName();
        }
    }

    @Nullable
    private static String videoOfSafely(Object record) {
        try {
            return videoOf(record);
        } catch (Throwable unreadable) {
            return null;
        }
    }

    private static Field fieldOfType(Class<?> owner, Class<?> type) {
        Field found = null;
        for (Field each : owner.getDeclaredFields()) {
            if (Modifier.isStatic(each.getModifiers()) || each.getType() != type) continue;
            if (found != null) throw new IllegalStateException(owner.getName() + " has more than one " + type.getName());
            found = each;
        }
        if (found == null) throw new IllegalStateException(owner.getName() + " has no " + type.getName());
        found.setAccessible(true);
        return found;
    }

    private static void purge() {
        Iterator<Bound> each = BOUND.iterator();
        while (each.hasNext()) {
            if (each.next().params.get() == null) each.remove();
        }
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.KEEP_PLAYING_IN_BACKGROUND.get()
                && Settings.NEXT_REEL_IN_BACKGROUND.get();
    }

    /** Forgets every bound reel, the run and what was found. For tests. */
    static void forget() {
        synchronized (BOUND) {
            BOUND.clear();
            PLAYED.clear();
        }
        session = null;
        template = null;
        advancing = false;
        round++;
        lastAt = -1;
        pausedLooks = 0;
        paramsField = null;
        idField = null;
        binds = 0;
        stoppedScreen = new WeakReference<>(null);
        movedPager = new WeakReference<>(null);
        movedRound = -1;
        moveStage = 0;
        moves = 0;
        waitingRound = -1;
        loadingShown = new WeakReference<>(null);
        ranShown = new WeakReference<>(null);
        wantMore = false;
        askedRound = -1;
        asks = 0;
        synchronized (PAUSED_SURFACES) {
            PAUSED_SURFACES.clear();
        }
        resumer = null;
    }
}
