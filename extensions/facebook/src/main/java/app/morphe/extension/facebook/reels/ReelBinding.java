/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.settings.BaseSettings;

/**
 * With Debug logging on, whether a reel that ends is on the player the Reels auto-advance
 * controller holds, the evidence an Auto-next switch needs before it can trust that controller.
 * Facebook's Reels controller keeps one player at a time, and its binder sets it as a reel takes
 * the screen. The video player's end-of-video step hands its player here as a video ends.
 *
 * <p>A binding is "bound" for a new player, "rebound to the same player" when the controller is
 * handed the player it already held, "unbound" when it's handed none, and "player moved between
 * controllers" when another controller held it. An end is "ended on the bound player" while a
 * controller holds it, "ended on a player bound before" once none does any more, and "ended on a
 * player never bound" for a player no controller held this run, such as a feed video's. Players
 * and controllers are compared by identity and held weakly, so nothing here keeps them alive.
 *
 * <p>Only those fixed labels and a running count are written: each label once, then every 50th
 * time it repeats. Off, before the settings are ready, or when anything here fails, nothing is
 * kept and Facebook's player goes on as it was. Pause leaves it on, since it only watches.
 */
public final class ReelBinding {
    /** The name the log lines go under, which is also their logcat tag after Morphe's prefix. */
    static final String SOURCE = "ReelBinding";

    /** What every line starts with, for a person reading the log. */
    static final String PREFIX = "Reel binding: ";

    static final String BOUND = "bound";
    static final String REBOUND = "rebound to the same player";
    static final String UNBOUND = "unbound";
    static final String MOVED = "player moved between controllers";
    static final String ENDED_BOUND = "ended on the bound player";
    static final String ENDED_STALE = "ended on a player bound before";
    static final String ENDED_UNBOUND = "ended on a player never bound";

    /** Controllers and players kept a run. Past them, the oldest is dropped. */
    static final int MAX_TRACKED = 32;

    /** A controller and the player it holds, both weak. */
    private static final class Binding {
        final WeakReference<Object> controller;
        @Nullable WeakReference<Object> player;

        Binding(Object controller) {
            this.controller = new WeakReference<>(controller);
        }

        @Nullable
        Object player() {
            return player == null ? null : player.get();
        }
    }

    private static final List<Binding> bindings = new ArrayList<>();
    /** Every player a controller has held this run. */
    private static final List<WeakReference<Object>> everBound = new ArrayList<>();
    private static final Map<String, Integer> counts = new HashMap<>();

    private ReelBinding() {
    }

    /**
     * Injection point, right after the Reels controller's player field is set. Any setting, a player
     * or none, is a reel taking the screen, which Open shared reels on their own waits for, with Debug
     * logging on or off: none comes for a reel Facebook plays with a player of its own, a live link's
     * among them (#421). A second player in a viewer opened from Saved is a swipe, which Play saved
     * videos in a row turns into the next saved video (#404).
     */
    public static void bound(@Nullable Object controller, @Nullable Object player) {
        FeedVideoAlone.reelOnScreen();
        SavedChain.bound(player);
        try {
            if (controller == null || !debug()) return;
            log(bind(controller, player));
        } catch (Throwable failure) {
            // Never into Facebook's Reels controller.
        }
    }

    /**
     * Injection point, right after the end-of-video step reads its player. In a viewer opened from
     * Saved, Play saved videos in a row goes on to the next saved video (#404).
     */
    public static void ended(@Nullable Object player) {
        SavedChain.ended(player);
        try {
            if (player == null || !debug()) return;
            log(end(player));
        } catch (Throwable failure) {
            // Never into Facebook's player.
        }
    }

    private static boolean debug() {
        // Logger can build a debug message before the settings have a context. This can't.
        return Utils.settingsReady() && BaseSettings.DEBUG.get();
    }

    private static synchronized String bind(Object controller, @Nullable Object player) {
        prune();
        Binding binding = null;
        boolean elsewhere = false;
        for (Binding candidate : bindings) {
            if (candidate.controller.get() == controller) binding = candidate;
            else if (player != null && candidate.player() == player) {
                elsewhere = true;
                candidate.player = null;
            }
        }
        Object previous = binding == null ? null : binding.player();
        String label;
        if (player == null) label = UNBOUND;
        else if (previous == player) label = REBOUND;
        else if (elsewhere) label = MOVED;
        else label = BOUND;

        if (binding == null) {
            binding = new Binding(controller);
            bindings.add(binding);
            if (bindings.size() > MAX_TRACKED) bindings.remove(0);
        }
        binding.player = player == null ? null : new WeakReference<>(player);
        if (player != null && !held(everBound, player)) {
            everBound.add(new WeakReference<>(player));
            if (everBound.size() > MAX_TRACKED) everBound.remove(0);
        }
        return label;
    }

    private static synchronized String end(Object player) {
        prune();
        for (Binding binding : bindings) {
            if (binding.player() == player) return ENDED_BOUND;
        }
        return held(everBound, player) ? ENDED_STALE : ENDED_UNBOUND;
    }

    private static boolean held(List<WeakReference<Object>> references, Object wanted) {
        for (WeakReference<Object> reference : references) {
            if (reference.get() == wanted) return true;
        }
        return false;
    }

    /** Drops what the garbage collector already took. */
    private static void prune() {
        for (Iterator<Binding> it = bindings.iterator(); it.hasNext(); ) {
            Binding binding = it.next();
            if (binding.controller.get() == null) it.remove();
            else if (binding.player != null && binding.player() == null) binding.player = null;
        }
        for (Iterator<WeakReference<Object>> it = everBound.iterator(); it.hasNext(); ) {
            if (it.next().get() == null) it.remove();
        }
    }

    private static void log(String label) {
        String message = count(label);
        if (message != null) Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> PREFIX + message);
    }

    /** The line to write for [label], or null when it's a repeat between samples. */
    @Nullable
    private static synchronized String count(String label) {
        Integer previous = counts.get(label);
        int count = previous == null ? 1 : previous == Integer.MAX_VALUE ? previous : previous + 1;
        counts.put(label, count);
        return count == 1 || count % 50 == 0 ? label + " samples=" + count : null;
    }

    /** Forgets this run's bindings and counts, as a new Facebook process would. For tests. */
    static synchronized void resetForTests() {
        bindings.clear();
        everBound.clear();
        counts.clear();
    }
}
