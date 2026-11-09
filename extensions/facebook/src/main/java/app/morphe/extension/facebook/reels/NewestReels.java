/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.ToLongBiFunction;
import java.util.function.ToLongFunction;

import app.morphe.extension.facebook.download.PostDetails;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Sorts the reels Facebook has loaded in the Reels tab, for Newest reels first (#134): newest
 * posted first, or most liked first when the Reels order row says so.
 *
 * <p>The Reels tab is ranked on the phone. Facebook loads a pool of reels, and each time it places
 * the next one it reranks what's left of the pool and takes the first. The ranking signal it builds
 * for each reel carries none of these values, but the reel's own model does: the edge's {@code node}
 * is the reel's story. Its {@code creation_time} is when it was posted, in seconds, and its
 * {@code feedback.unified_reactors.count} is its reaction count, the path Facebook's own reel
 * buttons read. Play counts aren't offered: on 584 the Reels tab's stories don't carry
 * {@code short_form_video_context}'s {@code play_count} (none of 111 reels did on a phone). So the patch hands each new signal here with its model's feed
 * type and edge, and a reel from one of the Reels feeds has the chosen value kept by its signal. At
 * the end of every rerank the list comes here, and the reels with a value are put in their own slots
 * highest first. Anything without one, such as an item Facebook drops in for what you just did or a
 * story that doesn't carry the count, keeps its place. Ties keep Facebook's order.
 *
 * <p>Only reels already loaded can move, so this is the newest or most liked of what Facebook picked
 * for you, never of all of Facebook. Off, paused, before the settings are ready, or when anything
 * here fails, the ranking is Facebook's own.
 */
public final class NewestReels {
    /** Counted under the patch's name each time a rerank's order changed here, by order. */
    static final String SORTED = "Reels put newest first";
    static final String SORTED_LIKED = "Reels put most liked first";

    /** Counted for a reel whose edge Facebook hadn't loaded, which keeps Facebook's place. */
    static final String UNLOADED = "Reels with no loaded edge";

    /** Counted for a reel whose story didn't carry its like count, which keeps Facebook's place. */
    static final String NO_LIKES = "Reels with no like count";

    /** Facebook's own message when a model's lazy edge isn't there yet. */
    private static final String EDGE_NOT_LOADED = "Inflated edge is null";

    private static final String FAMILY = FamilyNames.NEWEST_REELS_FIRST;

    /** The Reels tab's feed type is fb_shorts_tab, and its other feeds start the same way. */
    private static final String REELS_FEEDS = "fb_shorts";

    /** The fields read below, as the Java hash of their GraphQL names. */
    private static final int NODE = "node".hashCode();
    private static final int FEEDBACK = "feedback".hashCode();
    private static final int UNIFIED_REACTORS = "unified_reactors".hashCode();
    private static final int COUNT = "count".hashCode();

    /** Past this many kept values the oldest go, since a long session keeps making signals. */
    private static final int MOST = 4000;

    /** Each reel's value for the order picked when its signal was made. A signal dropped by Facebook drops out. */
    private static final Map<Object, Kept> kept = Collections.synchronizedMap(new WeakHashMap<>());

    /** The list the rerank running on this thread was handed, until it returns. */
    private static final ThreadLocal<List<Object>> started = new ThreadLocal<>();

    /** Stands in for {@link #postedSeconds} when a test sets it. */
    @Nullable
    static volatile ToLongFunction<Object> timeForTests;

    /** Stands in for the like count read when a test sets it, -1 for none. */
    @Nullable
    static volatile ToLongBiFunction<ReelsOrder, Object> countForTests;

    private static volatile boolean logged;

    private NewestReels() {
    }

    /** A reel's value and the order it was read for, so a change of order never mixes the two. */
    private static final class Kept {
        final ReelsOrder order;
        final long value;

        Kept(ReelsOrder order, long value) {
            this.order = order;
            this.value = value;
        }
    }

    /**
     * Injection point, as Facebook's ranker makes a signal: whether the hook should read the model's
     * edge and hand it on. Only for one of the Reels feeds with the switch on. Never throws.
     *
     * @param feedType the model's FeedType, whose string is the feed's name.
     */
    public static boolean records(@Nullable Object feedType) {
        try {
            HookStatus.invoked(FAMILY);
            // Settings mustn't load before the extension has its context.
            if (!Utils.settingsReady() || !Settings.NEWEST_REELS_FIRST.get()) return false;
            return reelsFeed(feedType);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reel signal", failure);
            return false;
        }
    }

    /**
     * Records an edge read that failed, without interrupting Facebook's signal creation. Facebook
     * throws "Inflated edge is null" for a reel whose edge it hasn't loaded yet, which some feeds
     * serve on every cold start (#234, #246). That reel only goes untimed, so it is counted rather
     * than reported as a broken hook. Anything else still is.
     */
    public static void edgeFailed(@Nullable Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        if (message != null && message.contains(EDGE_NOT_LOADED)) {
            HookStatus.counted(FAMILY, UNLOADED);
            return;
        }
        HookStatus.threw(FAMILY, "reel edge", failure);
    }

    /**
     * Injection point, as Facebook's ranker makes a reel's signal. Keeps the reel's value for the
     * order picked when its feed is one of the Reels feeds. Never throws.
     *
     * @param feedType the model's FeedType, whose string is the feed's name.
     * @param edge     the model's edge, a GraphQL tree.
     * @param signal   the signal Facebook made, which the rerank's list holds.
     */
    public static void posted(@Nullable Object feedType, @Nullable Object edge, @Nullable Object signal) {
        try {
            if (feedType == null || edge == null || signal == null) return;
            if (!Utils.settingsReady() || !Settings.NEWEST_REELS_FIRST.get()) return;
            if (!reelsFeed(feedType)) return;
            ReelsOrder order = Settings.REELS_ORDER.get();
            long value = valueOf(order, edge);
            if (order == ReelsOrder.NEWEST ? value <= 0 : value < 0) {
                if (order != ReelsOrder.NEWEST) HookStatus.counted(FAMILY, NO_LIKES);
                return;
            }
            HookStatus.bound(FAMILY, order == ReelsOrder.NEWEST ? "reel posting time" : "reel like count");
            synchronized (kept) {
                if (kept.size() >= MOST) kept.clear();
                // An equal signal made again for the same reel becomes the one kept.
                kept.remove(signal);
                kept.put(signal, new Kept(order, value));
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reel signal", failure);
        }
    }

    /**
     * Injection point, as Facebook's Reels ranker starts a rerank: keeps the list it was handed for
     * {@link #reranked}. 586's rerank reuses the list's register before some of its returns, so the
     * list comes here first and the returns need no register. Never throws.
     *
     * @param candidates the rerank's list, which it sorts in place and Facebook takes the next reel from.
     */
    public static void reranking(@Nullable List<Object> candidates) {
        try {
            started.set(candidates);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reels ranker", failure);
        }
    }

    /**
     * Injection point, as Facebook's Reels ranker returns: sorts the list this thread's rerank started
     * with ({@link #sort}), once. Never throws.
     */
    public static void reranked() {
        List<Object> candidates;
        try {
            candidates = started.get();
            started.remove();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reels ranker", failure);
            return;
        }
        sort(candidates);
    }

    /**
     * Puts the reels in [candidates] with a value for the order picked in their own slots, highest
     * first. Never throws, and leaves the list as it was when it can't finish.
     *
     * @param candidates the rerank's list, which Facebook takes the next reel from.
     */
    static void sort(@Nullable List<Object> candidates) {
        try {
            HookStatus.invoked(FAMILY);
            if (candidates == null || !Utils.settingsReady() || !Settings.NEWEST_REELS_FIRST.get()) return;
            HookStatus.bound(FAMILY, "reels ranker");
            int size = candidates.size();
            if (size < 2) return;
            ReelsOrder order = Settings.REELS_ORDER.get();
            List<Integer> slots = new ArrayList<>();
            List<Object> reels = new ArrayList<>();
            List<Long> values = new ArrayList<>();
            for (int at = 0; at < size; at++) {
                Object item = candidates.get(at);
                Kept value = item == null ? null : kept.get(item);
                // A value read for another order is left out, as if the reel had none.
                if (value == null || value.order != order) continue;
                slots.add(at);
                reels.add(item);
                values.add(value.value);
            }
            if (reels.size() < 2) return;
            List<Integer> sorted = new ArrayList<>();
            for (int at = 0; at < reels.size(); at++) sorted.add(at);
            // A List sort is a stable merge sort, so equal values keep Facebook's order.
            sorted.sort((a, b) -> Long.compare(values.get(b), values.get(a)));
            boolean moved = false;
            for (int at = 0; at < sorted.size(); at++) {
                if (sorted.get(at) != at) {
                    moved = true;
                    break;
                }
            }
            if (!moved) return;
            for (int at = 0; at < sorted.size(); at++) candidates.set(slots.get(at), reels.get(sorted.get(at)));
            HookStatus.counted(FAMILY, order == ReelsOrder.NEWEST ? SORTED : SORTED_LIKED);
            logOnce(order, size, reels.size());
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reels sort", failure);
        }
    }

    private static boolean reelsFeed(@Nullable Object feedType) {
        return feedType != null && String.valueOf(feedType).startsWith(REELS_FEEDS);
    }

    /** [edge]'s value for [order]: seconds for the newest, 0 when unknown, or a count, -1 when unknown. */
    private static long valueOf(ReelsOrder order, Object edge) {
        if (order == ReelsOrder.NEWEST) {
            ToLongFunction<Object> forced = timeForTests;
            return forced != null ? forced.applyAsLong(edge) : postedSeconds(edge);
        }
        ToLongBiFunction<ReelsOrder, Object> forced = countForTests;
        if (forced != null) return forced.applyAsLong(order, edge);
        return likes(edge);
    }

    /** When the story [edge] holds as its node was posted, in seconds, or 0 when it doesn't say. */
    static long postedSeconds(Object edge) {
        return PostDetails.postedSecondsOf(edge, NODE);
    }

    /** How many reactions the story [edge] holds has, or -1 when it doesn't say. */
    static long likes(Object edge) {
        Object feedback = PostDetails.tree(PostDetails.tree(edge, NODE), FEEDBACK);
        return PostDetails.count(PostDetails.tree(feedback, UNIFIED_REACTORS), COUNT);
    }

    private static void logOnce(ReelsOrder order, int size, int sorted) {
        if (logged) return;
        logged = true;
        Logger.printDebug(() -> "Newest reels first: sorted " + sorted + " of " + size + " loaded reels by " + order.fileValue);
    }

    /** Forgets the kept values, the one-time log line and any stand-in value. Tests only. */
    static void forgetForTests() {
        kept.clear();
        started.remove();
        logged = false;
        timeForTests = null;
        countForTests = null;
    }
}
