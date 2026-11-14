/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.Nullable;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Hide stories you scrolled past (#463): a story whose tile came into view in a Stories tray counts
 * as seen from your next visit to Facebook, watched or not, and leaves each tray after that until
 * its person posts another story.
 *
 * <p>Each tile of Home's tray tells the tray's handler it came into view, by its bucket's id, and the
 * handler hands that to {@link StoriesTrayPaging#tileShown}. The id alone can't tell a new story from
 * an old one, so each tray's buckets are noted here by id with their expiration_time as they pass
 * through {@link SuggestedStories#keptBuckets}, and a tile that comes into view is kept with the
 * expiration_time its bucket had then. A story posted since moves that time, so the bucket comes
 * back. What's kept lives in a preferences file of its own, never in the settings export or the
 * diagnostic report, at most {@link SeenBuckets#MAX_BUCKETS} buckets for {@link SeenBuckets#KEEP_MS}.
 * A topic tile (a trend gathering public stories) gets a new id and expiration_time each time the
 * tray loads, so it's kept by its label, with its list of stories standing in for the time.
 *
 * <p>A bucket leaves only once you've left Facebook and come back since its tile came into view, so
 * a tray you're scrolling never loses a tile under your finger, a refresh in the same visit included.
 * A visit starts when Facebook's first screen starts after none were, and a screen rebuilt for a
 * rotation doesn't end one. Off, paused, settings that aren't ready, an id or time it can't read, or
 * a failure in here, and the bucket stays.
 */
final class PassedStories {
    /** The preferences file, in Facebook's own preferences folder. Only this class writes it. */
    static final String FILE = "hushfacebook_passed_story_buckets";

    /** What Hook status counts each bucket kept as scrolled past under. */
    static final String KEPT = "Tray story scrolled past";

    /** What a bucket that left a tray because you scrolled past it is counted under. */
    static final String PASSED = "scrolled past";

    /** How many buckets the trays listed are remembered by id, for the tiles that come into view. */
    static final int TRAY_BUCKETS = 512;

    /** How a topic tile's bucket id starts, and what one is kept under, ahead of its label. */
    static final String TOPIC = "vs_bucket:";

    /** The trays' buckets' expiration_time by id, the one a tray listed last at the end. */
    private static final LinkedHashMap<String, Long> expiresById = new LinkedHashMap<>();

    /** The buckets whose tile came into view this visit, which stay until the next one. */
    private static final Set<String> thisVisit = new HashSet<>();

    /** Facebook's started screens, and whether you left it since the last one started. */
    private static int started;
    private static boolean left;

    @Nullable
    private static SeenBuckets store;

    private PassedStories() {
    }

    /** Whether the switch is on, not paused, and settings are ready. Never throws. */
    static boolean on() {
        try {
            return Utils.settingsReady() && Settings.HIDE_PASSED_STORIES.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "scrolled past switch", failure);
            return false;
        }
    }

    /**
     * Notes a tray's bucket [bucketId] and the expiration_time it came with, for a tile that comes
     * into view later, and answers whether it leaves the tray: it came into view on an earlier visit
     * with the same expiration_time. Only asked with the switch on. Never throws.
     */
    static boolean listed(String bucketId, long expiresSeconds) {
        try {
            if (expiresSeconds <= 0) return false;
            String[] topic = topic(bucketId);
            String key = topic == null ? bucketId : TOPIC + topic[0];
            long time = topic == null ? expiresSeconds : storiesTime(topic[1]);
            synchronized (PassedStories.class) {
                expiresById.remove(key);
                expiresById.put(key, time);
                while (expiresById.size() > TRAY_BUCKETS) {
                    expiresById.remove(expiresById.keySet().iterator().next());
                }
                if (thisVisit.contains(key)) return false;
            }
            SeenBuckets kept = store();
            return kept != null
                    && String.valueOf(time).equals(kept.newestWatched(key, System.currentTimeMillis()));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "scrolled past story", failure);
            return false;
        }
    }

    /**
     * From {@link StoriesTrayPaging#tileShown}: the tile of the bucket [bucketId] came into view. It's
     * kept with the expiration_time its tray listed, once for each, with the switch on. A bucket no
     * tray listed, the gray placeholders among them, isn't kept. Never throws.
     */
    static void shown(@Nullable String bucketId) {
        try {
            if (bucketId == null || !on()) return;
            String[] topic = topic(bucketId);
            String key = topic == null ? bucketId : TOPIC + topic[0];
            Long expires;
            synchronized (PassedStories.class) {
                expires = expiresById.get(key);
                if (expires == null) return;
                thisVisit.add(key);
            }
            SeenBuckets kept = store();
            if (kept == null) return;
            long now = System.currentTimeMillis();
            String time = String.valueOf(expires);
            if (time.equals(kept.newestWatched(key, now))) return;
            kept.watched(key, time, now);
            HookStatus.bound(FamilyNames.SUGGESTED_STORIES, "tray tile in view");
            HookStatus.counted(FamilyNames.SUGGESTED_STORIES, KEPT);
            Logger.printDebug(() -> "Stories tray: a story came into view, it leaves the tray from the next visit");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "scrolled past story", failure);
        }
    }

    /**
     * A topic tile's label and list of story ids from its bucket id, or null for any other bucket.
     * Facebook builds the id from the fetch the tray came in, the tile's place in the row, then the
     * stories' ids and the label, so only those last two hold from one load of the tray to the next.
     */
    @Nullable
    static String[] topic(String bucketId) {
        if (!bucketId.startsWith(TOPIC)) return null;
        int label = bucketId.lastIndexOf(':');
        int stories = bucketId.lastIndexOf(':', label - 1);
        if (stories <= TOPIC.length() || label - stories < 2 || label == bucketId.length() - 1) return null;
        return new String[] {bucketId.substring(label + 1), bucketId.substring(stories + 1, label)};
    }

    /**
     * What stands in for a topic's expiration_time, which moves with every load of the tray: a number
     * from its list of story ids, so the topic comes back once a story joins it. Always above zero.
     */
    static long storiesTime(String stories) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < stories.length(); i++) {
            hash ^= stories.charAt(i);
            hash *= 0x100000001b3L;
        }
        hash &= Long.MAX_VALUE;
        return hash == 0 ? 1 : hash;
    }

    /** From the activity callbacks: one of Facebook's screens started. Never throws. */
    static void activityStarted(Activity activity) {
        try {
            synchronized (PassedStories.class) {
                started++;
                if (started != 1 || !left) return;
                left = false;
                thisVisit.clear();
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "back in Facebook", failure);
        }
    }

    /** From the activity callbacks: one of Facebook's screens stopped. Never throws. */
    static void activityStopped(Activity activity) {
        try {
            boolean rotating = activity.isChangingConfigurations();
            synchronized (PassedStories.class) {
                if (started > 0) started--;
                if (started == 0 && !rotating) left = true;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "leaving Facebook", failure);
        }
    }

    @Nullable
    private static SeenBuckets store() {
        SeenBuckets kept = store;
        if (kept != null) return kept;
        Context context = Utils.getContext();
        if (context == null) return null;
        synchronized (PassedStories.class) {
            if (store == null) store = new SeenBuckets(context.getSharedPreferences(FILE, Context.MODE_PRIVATE));
            return store;
        }
    }

    /** Forgets the trays, the visit and the loaded file, as a fresh start of the app would. */
    static void resetForTests() {
        synchronized (PassedStories.class) {
            expiresById.clear();
            thisVisit.clear();
            started = 0;
            left = false;
            store = null;
        }
    }
}
