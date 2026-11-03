/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The people whose stories were watched to the end with View stories anonymously on, by story
 * bucket id: the id of the newest story each had then, at most {@link #MAX_BUCKETS} of them, none
 * older than {@link #KEEP_MS}.
 *
 * <p>Facebook isn't told about those views, so its servers keep sending the bucket as unwatched.
 * What's kept here lets the phone mark it watched again each time it arrives, until the person
 * posts a story that wasn't watched yet, which changes the newest id. The ids live in a preferences
 * file of their own, so they never reach the settings export or the diagnostic report. The file is
 * read once, the first time it's needed, and every change after that writes only the keys it
 * changes. The oldest bucket goes first once there are too many, and one past its age is dropped
 * when it's read.
 *
 * <p>A second one, in {@link #CARDS_FILE}, keeps each story card watched by its own id, with its
 * bucket's id in the newest story's place, so the viewer can start a bucket at the first card that
 * wasn't watched (#218).
 *
 * <p>A third, in {@link #TRAY_FILE}, keeps each tray bucket Facebook sent as watched by its id, with
 * its expiration_time in the newest story's place, so a later tray that sends it unwatched again
 * with no new story is still read as watched (#340).
 */
final class SeenBuckets {
    /** The preferences file, in Facebook's own preferences folder. Only this class writes it. */
    static final String FILE = "hushfacebook_seen_story_buckets";

    /** The cards' preferences file. */
    static final String CARDS_FILE = "hushfacebook_seen_story_cards";

    /** The tray buckets' preferences file. */
    static final String TRAY_FILE = "hushfacebook_seen_tray_buckets";

    /** How many buckets are remembered. The least recently watched goes first. */
    static final int MAX_BUCKETS = 500;

    /**
     * How many cards are remembered: a hundred or more buckets' worth. Kept well under what makes
     * the file slow to write, since Android writes all of it for each card.
     */
    static final int MAX_CARDS = 1000;

    /** How long a bucket is kept after it was watched: 48 hours, twice a story's life. */
    static final long KEEP_MS = 48L * 60 * 60 * 1000;

    private static final class Entry {
        final String newestStory;
        final long watchedAt;

        Entry(String newestStory, long watchedAt) {
            this.newestStory = newestStory;
            this.watchedAt = watchedAt;
        }
    }

    private final SharedPreferences store;
    private final int max;
    /** In the order they were watched, oldest first. */
    private final LinkedHashMap<String, Entry> buckets = new LinkedHashMap<>();
    private boolean loaded;

    SeenBuckets(SharedPreferences store) {
        this(store, MAX_BUCKETS);
    }

    /** Keeps at most [max] of them. */
    SeenBuckets(SharedPreferences store, int max) {
        this.store = store;
        this.max = max;
    }

    /** The newest story [bucketId] had when it was watched to the end, or null. */
    @Nullable
    synchronized String newestWatched(String bucketId, long now) {
        load(now);
        Entry entry = buckets.get(bucketId);
        if (entry == null) return null;
        if (expired(entry, now)) {
            buckets.remove(bucketId);
            store.edit().remove(bucketId).apply();
            return null;
        }
        return entry.newestStory;
    }

    /** When [bucketId] was last watched to the end, in milliseconds since 1970, or -1 when it isn't kept. */
    synchronized long watchedAt(String bucketId, long now) {
        return newestWatched(bucketId, now) == null ? -1 : buckets.get(bucketId).watchedAt;
    }

    /** Remembers that [bucketId] was watched through [newestStory], and drops the oldest past the limit. */
    synchronized void watched(String bucketId, String newestStory, long now) {
        load(now);
        buckets.remove(bucketId);
        buckets.put(bucketId, new Entry(newestStory, now));
        SharedPreferences.Editor edit = store.edit().putString(bucketId, encode(newestStory, now));
        Iterator<String> oldest = buckets.keySet().iterator();
        while (buckets.size() > max && oldest.hasNext()) {
            String gone = oldest.next();
            oldest.remove();
            edit.remove(gone);
        }
        edit.apply();
    }

    /** Forgets every bucket. */
    synchronized void clear() {
        buckets.clear();
        loaded = true;
        store.edit().clear().apply();
    }

    synchronized boolean isEmpty(long now) {
        load(now);
        return buckets.isEmpty();
    }

    synchronized int size(long now) {
        load(now);
        return buckets.size();
    }

    /**
     * Reads the file the first time. A value that doesn't parse, a bucket past its age, and the
     * oldest buckets past the limit are removed from it.
     */
    private void load(long now) {
        if (loaded) return;
        loaded = true;
        List<String> dropped = new ArrayList<>();
        List<Map.Entry<String, Entry>> read = new ArrayList<>();
        for (Map.Entry<String, ?> stored : store.getAll().entrySet()) {
            Entry entry = stored.getValue() instanceof String ? decode((String) stored.getValue()) : null;
            if (entry == null || expired(entry, now)) {
                dropped.add(stored.getKey());
                continue;
            }
            read.add(new AbstractMap.SimpleImmutableEntry<>(stored.getKey(), entry));
        }
        Collections.sort(read, (a, b) -> Long.compare(a.getValue().watchedAt, b.getValue().watchedAt));
        int surplus = Math.max(0, read.size() - max);
        for (int index = 0; index < read.size(); index++) {
            Map.Entry<String, Entry> entry = read.get(index);
            if (index < surplus) dropped.add(entry.getKey());
            else buckets.put(entry.getKey(), entry.getValue());
        }
        if (dropped.isEmpty()) return;
        SharedPreferences.Editor edit = store.edit();
        for (String key : dropped) edit.remove(key);
        edit.apply();
    }

    private static boolean expired(Entry entry, long now) {
        return now - entry.watchedAt > KEEP_MS;
    }

    /** The time first, since a story id could hold any character. */
    static String encode(String newestStory, long watchedAt) {
        return watchedAt + ":" + newestStory;
    }

    @Nullable
    private static Entry decode(String value) {
        int colon = value.indexOf(':');
        if (colon <= 0 || colon == value.length() - 1) return null;
        try {
            return new Entry(value.substring(colon + 1), Long.parseLong(value.substring(0, colon)));
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
