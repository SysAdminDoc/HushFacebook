/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import app.morphe.extension.facebook.download.PostDetails;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;

/**
 * Recently watched (#183): a list on the phone of the reels, videos and stories you watched, newest
 * first, so one a feed refresh took away can be found and opened again. Nothing on it leaves the
 * phone; it's kept in {@link WatchedList}.
 *
 * <p>It adds no hook of its own. Reels and videos come from Resume long videos' hook first thing
 * in FbGrootPlayer's maybeTrackVideoStart ({@link #started}), which a start runs once the video is
 * playing. The player's VideoPlayerParams give the video's id and say whether it's a reel. A start
 * counts as a watch when the same player still has that video {@link #WATCHED_AFTER_MS} later and
 * has played on by {@link #MIN_PLAYED_MS}, so a video you scroll straight past isn't listed. Ads,
 * GIFs and sound-only players never are. Stories come from View stories anonymously's hook in the
 * story viewer's queue of the cards you viewed ({@link #storyViewed}), with the card's poster when
 * the card names one. The params say nothing of a video's poster or caption, so a reel or video is
 * listed by its kind, length and time.
 *
 * <p>Off, paused, before the settings are ready, or when anything here throws, nothing is added and
 * the player and the story viewer go on as Facebook runs them. What's listed stays for the time
 * {@link Settings#RECENTLY_WATCHED_KEEP} says, switch on or off: the list drops what's past it each
 * time it's read or written, and the first start in a run reads it. The oldest also goes once there
 * are {@link WatchedList#MAX_ENTRIES}. One entry can be taken off on its own and put back by Undo.
 */
public final class RecentlyWatched {
    public static final String REEL = "reel";
    public static final String VIDEO = "video";
    public static final String STORY = "story";

    static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** {@link Settings#RECENTLY_WATCHED_KEEP}'s key, which the settings' first load needs before the setting exists. */
    public static final String KEEP_KEY = "hushfacebook_recently_watched_keep";

    /** How long an entry stays on the list. */
    public enum Keep {
        SEVEN_DAYS(7, "7_days"),
        THIRTY_DAYS(30, "30_days"),
        UNTIL_CLEARED(0, "until_cleared");

        /** Days an entry stays after it's watched, or 0 when it stays until the list is cleared. */
        public final int days;

        /** What a settings file holds for this choice. It never changes once written. */
        public final String fileValue;

        Keep(int days, String fileValue) {
            this.days = days;
            this.fileValue = fileValue;
        }

        /** The same in milliseconds, 0 for no limit. */
        long ms() {
            return days * DAY_MS;
        }

        /** The choice a settings file names, or null when it names none this build knows. */
        @Nullable
        public static Keep fromFile(@Nullable Object value) {
            if (!(value instanceof String)) return null;
            for (Keep keep : values()) {
                if (keep.fileValue.equals(value)) return keep;
            }
            return null;
        }
    }

    /** How long after a start the player has to still have the video for it to count as watched. */
    static final long WATCHED_AFTER_MS = 3_000;

    /** How far the video has to have played on by then. A pause or a stalled start stays put. */
    static final int MIN_PLAYED_MS = 2_000;

    /** The Hook status names: a failure under each, and the count of entries kept. */
    static final String PLAYER_HOOK = "recently watched video";
    static final String STORY_HOOK = "recently watched story";
    static final String KEPT = "recently watched";

    private static final String FAMILY = FamilyNames.RESUME_LONG_VIDEOS;
    private static final String WEB = "https://www.facebook.com/";

    /** One reel, video or story on the list. */
    public static final class Entry {
        /** {@link #REEL}, {@link #VIDEO} or {@link #STORY}. */
        public final String kind;
        /** The video's id, or the story card's. */
        public final String id;
        /** Who posted it, when that's known. */
        @Nullable public final String owner;
        /** The start of its caption, when that's known. */
        @Nullable public final String caption;
        /** A story's bucket, the person's or Page's stories, which its link needs. Null otherwise. */
        @Nullable public final String parent;
        /** When it was last watched, in milliseconds since the epoch. */
        public final long watchedAt;
        /** Its length, or 0 when unknown. */
        public final int durationMs;

        Entry(String kind, String id, @Nullable String owner, @Nullable String caption, @Nullable String parent,
              long watchedAt, int durationMs) {
            this.kind = kind;
            this.id = id;
            this.owner = owner;
            this.caption = caption;
            this.parent = parent;
            this.watchedAt = watchedAt;
            this.durationMs = durationMs;
        }

        /** This watch of what [earlier] was too, keeping what [earlier] knew and this one doesn't. */
        Entry over(Entry earlier) {
            return new Entry(kind, id, owner != null ? owner : earlier.owner,
                    caption != null ? caption : earlier.caption, parent != null ? parent : earlier.parent,
                    watchedAt, durationMs > 0 ? durationMs : earlier.durationMs);
        }

        /**
         * The facebook.com address it opens at: a reel's, a video's watch link, or a story's under
         * its bucket. Null for a story with no bucket.
         */
        @Nullable
        public String link() {
            switch (kind) {
                case REEL:
                    return WEB + "reel/" + Uri.encode(id);
                case VIDEO:
                    return WEB + "watch/?v=" + Uri.encode(id);
                case STORY:
                    return parent == null ? null : WEB + "stories/" + Uri.encode(parent) + "/" + Uri.encode(id) + "/";
                default:
                    return null;
            }
        }
    }

    /** Runs the watch check later. {@link #ON_THIS_LOOPER} is the app's; tests stand in. */
    interface Timer {
        /** False when it can't run [task]. */
        boolean after(long delayMs, Runnable task);
    }

    /** On the looper of the thread that started the player, which is the player's own. */
    static final Timer ON_THIS_LOOPER = (delayMs, task) -> {
        Looper looper = Looper.myLooper();
        return new Handler(looper != null ? looper : Looper.getMainLooper()).postDelayed(task, delayMs);
    };

    /** Writes the list off the player's thread, since the whole array goes out on each change. */
    static final Executor IN_THE_BACKGROUND = Utils::runOnBackgroundThread;

    /** The time watches are stamped and aged by. Tests stand in. */
    static final LongSupplier SYSTEM_CLOCK = System::currentTimeMillis;

    static volatile Timer timer = ON_THIS_LOOPER;
    static volatile Executor writer = IN_THE_BACKGROUND;
    static volatile LongSupplier clock = SYSTEM_CLOCK;
    @Nullable
    static volatile WatchedList listForTests;

    private static final Object LIST_LOCK = new Object();
    /** Clear and recording changes retire every event captured before them. Guarded by LIST_LOCK. */
    private static long generation;
    @Nullable
    private static WatchedList list;
    /** Set once the first start in this run has had the list aged. */
    private static volatile boolean agedThisRun;

    private RecentlyWatched() {
    }

    /** Whether watches are kept: the switch on, not paused, and the settings ready. */
    public static boolean keeping() {
        return Utils.settingsReady() && Settings.RECENTLY_WATCHED.get();
    }

    /** From every live recording-setting write path, including imports and preference binding. */
    public static void recordingChanged() {
        synchronized (LIST_LOCK) {
            generation++;
        }
    }

    /**
     * How long entries stay: the saved choice, which a pause leaves as it is so nothing goes early,
     * or no limit before the settings are ready.
     */
    static Keep keepTime() {
        return Utils.settingsReady() ? Settings.RECENTLY_WATCHED_KEEP.savedValue() : Keep.UNTIL_CLEARED;
    }

    /**
     * From every write of {@link Settings#RECENTLY_WATCHED_KEEP}, imports included: what's past the
     * new time leaves the list in the background. Quick, as a setting's change callback has to be.
     */
    public static void keepChanged() {
        writer.execute(RecentlyWatched::age);
    }

    /**
     * From the settings' first load, before {@link Settings#RECENTLY_WATCHED_KEEP} reads its value:
     * a list an earlier build kept, when entries never expired, goes on staying until it's cleared
     * unless a time is already saved. It works on the two files as they are, so no list or setting
     * lock is taken. Main process only. Never throws.
     */
    public static void keepOlderListAsItWas() {
        try {
            Context context = Utils.getContext();
            if (context == null || !Utils.isMainProcess()) return;
            synchronized (Setting.class) {
                if (keepOlderListAsItWas(Setting.preferences.preferences,
                        context.getSharedPreferences(WatchedList.FILE, Context.MODE_PRIVATE))) {
                    Logger.printDebug(() -> "Recently watched: a list from before entries expired stays until it's cleared");
                }
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "Recently watched: the older list's time couldn't be settled", failure);
        }
    }

    /** The same on [settings] and [listStore]. True when [listStore] held an older list. */
    static boolean keepOlderListAsItWas(SharedPreferences settings, SharedPreferences listStore) {
        if (!WatchedList.olderList(listStore)) return false;
        if (!settings.contains(KEEP_KEY)
                && !settings.edit().putString(KEEP_KEY, Keep.UNTIL_CLEARED.name()).commit()) {
            return false;
        }
        WatchedList.markCurrent(listStore);
        return true;
    }

    /** Drops what's past its time, and writes the list back when anything went. Never throws. */
    static void age() {
        try {
            synchronized (LIST_LOCK) {
                WatchedList kept = list();
                if (kept != null) kept.size();
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "Recently watched: the list couldn't be aged", failure);
        }
    }

    // ------------------------------------------------------------------ what the hooks hand over

    /**
     * From {@link ResumePlayback#started}: [player] started playing its video. The check
     * {@link #WATCHED_AFTER_MS} later keeps it. Never throws.
     */
    static void started(@Nullable Object player) {
        try {
            if (player == null) return;
            if (!agedThisRun && Utils.settingsReady()) {
                // The list ages on the run's first start, recording on or off.
                agedThisRun = true;
                writer.execute(RecentlyWatched::age);
            }
            final long observedGeneration;
            synchronized (LIST_LOCK) {
                if (!keeping()) return;
                observedGeneration = generation;
            }
            ResumePlayback.Facts facts = ResumePlayback.facts(player);
            if (facts == null || facts.ad || facts.gif || facts.audioOnly) return;
            String kind = facts.reel ? REEL : VIDEO;
            String videoId = facts.videoId;
            int length = facts.durationMs;
            int startedAt = ResumePlayback.access.position(player);
            WeakReference<Object> held = new WeakReference<>(player);
            timer.after(WATCHED_AFTER_MS, () -> stillPlaying(held, kind, videoId, length, startedAt, observedGeneration));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, PLAYER_HOOK, failure);
        }
    }

    /**
     * The check after a start: the player still has [videoId] and has played on since [startedAt],
     * or wrapped around to an earlier point the way a looping reel does. A position the patch
     * can't read counts as played on. Never throws.
     */
    static void stillPlaying(WeakReference<Object> held, String kind, String videoId, int length, int startedAt,
                             long observedGeneration) {
        try {
            synchronized (LIST_LOCK) {
                if (generation != observedGeneration || !keeping()) return;
            }
            if (!keeping() || !playedOn(held, videoId, startedAt)) return;
            keep(new Entry(kind, videoId, null, null, null, clock.getAsLong(), Math.max(0, length)), observedGeneration);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, PLAYER_HOOK, failure);
        }
    }

    /**
     * The same played-on check for [player], which just started [videoId], to run later. Take
     * breaks counts reels with it, Recently watched on or off.
     */
    static BooleanSupplier playsOn(Object player, String videoId) {
        WeakReference<Object> held = new WeakReference<>(player);
        int startedAt = ResumePlayback.access.position(player);
        return () -> playedOn(held, videoId, startedAt);
    }

    /**
     * Whether the player in [held] still has [videoId] and has played on since [startedAt], or
     * wrapped around to an earlier point the way a looping reel does. A position the patch can't
     * read counts as played on.
     */
    static boolean playedOn(WeakReference<Object> held, String videoId, int startedAt) {
        Object player = held.get();
        if (player == null) return false;
        ResumePlayback.Facts facts = ResumePlayback.facts(player);
        if (facts == null || !videoId.equals(facts.videoId)) return false;
        int now = ResumePlayback.access.position(player);
        boolean known = now != ResumePlayback.NOT_PATCHED_TIME && startedAt != ResumePlayback.NOT_PATCHED_TIME;
        return !known || now < startedAt || now - startedAt >= MIN_PLAYED_MS;
    }

    /**
     * From the story viewer's queue of viewed cards: card [storyId] of bucket [bucketId] was viewed
     * (a peek isn't). Kept with its poster when [card] names one. Never throws.
     */
    public static void storyViewed(@Nullable String storyId, @Nullable String bucketId, @Nullable Object card) {
        try {
            if (storyId == null || storyId.isEmpty() || bucketId == null || bucketId.isEmpty()) return;
            final long observedGeneration;
            synchronized (LIST_LOCK) {
                if (!keeping()) return;
                observedGeneration = generation;
            }
            keep(new Entry(STORY, storyId, posterOf(card), null, bucketId, clock.getAsLong(), 0), observedGeneration);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, STORY_HOOK, failure);
        }
    }

    /** Who posted the story [card] holds, or null when it doesn't say or can't be read. */
    @Nullable
    static String posterOf(@Nullable Object card) {
        if (card == null) return null;
        try {
            return PostDetails.ofCard(null, card).owner;
        } catch (Throwable unreadable) {
            // A card of another shape: the story is still listed, by its kind.
            return null;
        }
    }

    private static void keep(Entry entry, long observedGeneration) {
        writer.execute(() -> {
            try {
                final boolean added;
                synchronized (LIST_LOCK) {
                    if (generation != observedGeneration || !keeping()) return;
                    WatchedList kept = list();
                    if (kept == null) return;
                    added = kept.add(entry);
                }
                HookStatus.counted(FAMILY, KEPT);
                Logger.printDebug(() -> "Recently watched: " + (added ? "added a " : "moved up a ") + entry.kind);
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, STORY.equals(entry.kind) ? STORY_HOOK : PLAYER_HOOK, failure);
            }
        });
    }

    // ------------------------------------------------------------------ what the page reads

    /** Every entry, the one watched last first. Empty outside Facebook's main process. */
    public static List<Entry> entries() {
        WatchedList kept = list();
        return kept == null ? Collections.emptyList() : kept.newestFirst();
    }

    /** Forgets every entry. */
    public static void clear() {
        synchronized (LIST_LOCK) {
            generation++;
            WatchedList kept = list();
            if (kept != null) kept.clear();
        }
        Logger.printDebug(() -> "Recently watched: cleared");
    }

    /**
     * Takes [entry]'s video or story off the list. The entry taken off, which {@link #restore} puts
     * back, or null when it was gone already.
     */
    @Nullable
    public static Entry remove(Entry entry) {
        synchronized (LIST_LOCK) {
            WatchedList kept = list();
            return kept == null ? null : kept.remove(entry.kind, entry.id);
        }
    }

    /**
     * Puts back an entry {@link #remove} took off, where its time places it. False, and nothing
     * changes, when the video or story was watched again since, or its time is up.
     */
    public static boolean restore(Entry entry) {
        synchronized (LIST_LOCK) {
            WatchedList kept = list();
            return kept != null && kept.restore(entry);
        }
    }

    static boolean isKind(String kind) {
        return REEL.equals(kind) || VIDEO.equals(kind) || STORY.equals(kind);
    }

    /** The list, read the first time it's needed. Null outside the main process. */
    @Nullable
    private static WatchedList list() {
        WatchedList forTests = listForTests;
        if (forTests != null) return forTests;
        synchronized (LIST_LOCK) {
            if (list == null) {
                Context context = Utils.getContext();
                if (context == null || !Utils.isMainProcess()) return null;
                list = new WatchedList(context.getSharedPreferences(WatchedList.FILE, Context.MODE_PRIVATE));
            }
            return list;
        }
    }

    /** Puts the app's own timer, writer, clock and list back. */
    static void forget() {
        synchronized (LIST_LOCK) {
            generation++;
            list = null;
        }
        listForTests = null;
        timer = ON_THIS_LOOPER;
        writer = IN_THE_BACKGROUND;
        clock = SYSTEM_CLOCK;
        agedThisRun = false;
    }
}
