/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.facebook.media.RecentlyWatched;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What the View stories anonymously patch asks before Facebook reports the stories you've viewed.
 *
 * <p>The story viewer queues each story card you view and sends the queue as one GraphQL mutation,
 * DirectSeenMutation, which is what puts you on a story's viewer list. The patch asks
 * {@link #toSend} first thing in the method that builds and sends it, handing over everything that
 * method was handed, and sends the set of card ids it answers. Nothing is built or sent on null.
 * Facebook has already counted those cards as reported in a set it keeps in memory, so a card held
 * back isn't queued again until it restarts. The switch is read at each send, so turning it off lets
 * the next batch through.
 *
 * <p>With the second switch on, the cards you tapped Mark as seen on ({@link StorySeenButton})
 * still go out: the answer is then a new set holding those cards and nothing else, and the rest
 * stay held ({@link StoryMarks}). A card a batch already held back is sent at the next send, or
 * right away through the send that held it when that is still around.
 *
 * <p>When the servers take a report, Facebook publishes the bucket's new is_bucket_seen_by_viewer
 * to every copy of it the app holds, which is what turns its ring gray. A report held back gets no
 * answer, so the patch does that publishing itself (#61). {@link #viewed} hears each card the
 * viewer queues; when it's the newest card of its bucket, the bucket was watched to the end, and
 * {@link SeenBuckets} keeps its id with that card's. At the next send, the one held back, those
 * buckets are published as watched. The servers keep sending them as unwatched, so
 * {@link #bucketMade} hears each bucket Facebook builds, and one that's kept here with the same
 * newest card is published as watched again. A newer story changes the newest card, so it shows as
 * new. Nothing about a story leaves the phone.
 *
 * <p>A publish lands after the tray has drawn, and a cold start draws it first with lightweight
 * buckets that never reach {@link #bucketMade}, so those rings came back blue after each restart
 * until the full buckets arrived (#165). {@link #readsWatched} goes first in the bucket's own answer
 * to whether it has a story not watched yet, so a bucket kept here reads watched from the first
 * time the tray asks.
 *
 * <p>The viewer opens a bucket at its first card whose seen flag is false. Facebook sets that flag
 * as cards play, in memory only, and its servers set it from their view records, which a held-back
 * view never makes. So after a restart a bucket watched partway, or one that got a new card, opened
 * at card 1 again (#218). {@link #viewed} keeps every card it hears in a second {@link SeenBuckets},
 * and {@link #watchedHere} answers Facebook's start check for each one, so the viewer passes over
 * those and starts at the first card not watched. The flag itself isn't changed.
 *
 * <p>It fails open, like the other hooks: the switch off, a pause, settings that aren't ready yet,
 * or a failure reading the switch send the views as Facebook would, and leave the rings as Facebook
 * draws them. A failure picking out the marked cards holds the whole batch back, as the switch does
 * without marks.
 */
public final class StorySeen {
    /** The diagnostic counter route: each batch of views Facebook went to send, and the ones held back. */
    static final String ROUTE = "Story views";

    /** What a batch held back is counted under. */
    static final String HELD_BACK = "viewed stories";

    static final String SEND_HOOK = "story seen send";
    static final String MARKED_HOOK = "marked story send";

    /** The cards marked as seen, shared with the button. */
    static final StoryMarks MARKS = new StoryMarks(SystemClock::elapsedRealtime);

    /** What Hook status counts each bucket published as watched under. */
    static final String MARKED = "marked watched here";

    /** What Hook status counts each card the viewer passed over, picking where a bucket opens, under (#218). */
    static final String PASSED_OVER = "opened past a card watched here";

    /** What Hook status counts each time a bucket answered Facebook as watched, under (#165). */
    static final String READ_WATCHED = "read as watched here";

    /** The type and field of the tree Facebook publishes for a bucket when its servers take a report. */
    static final String BUCKET_TYPE = "DirectMessageThreadBucket";
    static final String SEEN_FIELD = "is_bucket_seen_by_viewer";

    /** Facebook's story bucket, which keeps its name. */
    private static final String REGULAR_BUCKET = "com.facebook.audience.snacks.model.RegularStoryBucket";

    /**
     * Each copy of a bucket Facebook builds carries what its servers sent, so a copy built moments
     * after the last mark still reads unwatched and needs its own: a cold start builds the tray four
     * times in under a second, the last from the network after the mark, and one mark for all of
     * them left the ring blue (#61, #165, S22 2026-10-18). A copy that already reads watched isn't
     * published. The only ceiling is a guard in case a publish ever makes Facebook build an
     * unwatched copy again: no bucket is published more than {@link #MARKS_PER_WINDOW} times in this
     * long. It has to sit far above what a Menu or feed visit builds, because the tray loader makes
     * a dozen copies of every bucket in its first second and more with each refresh, and a ceiling
     * of eight a minute left every copy after the eighth blue again (#165, 0.15.0, 2026-10-19).
     */
    static final long REMARK_MS = 60_000;
    static final int MARKS_PER_WINDOW = 240;

    /** What a stub answers until the patch fills it, so a missing one reads as missing. */
    private static final Object UNPATCHED = new Object();

    /** What the code here needs from Facebook's story classes. The stubs below, in the app. */
    interface Stories {
        @Nullable String bucketId(Object bucket);

        /** The bucket's cards, oldest first, or null. */
        @Nullable List<?> cards(Object bucket);

        @Nullable String storyId(Object card);

        /** The bucket's own answer: whether it has a story not watched yet. */
        boolean hasUnwatched(Object bucket);

        /** Publishes the bucket as watched to every copy of it the app holds. */
        void publishWatched(Object bucket, String bucketId);
    }

    private static final Stories PATCHED = new Stories() {
        @Override public String bucketId(Object bucket) { return text(bucketIdOf(bucket)); }
        @Override public List<?> cards(Object bucket) {
            Object cards = cardsOf(bucket);
            return cards instanceof List ? (List<?>) cards : null;
        }
        @Override public String storyId(Object card) { return text(storyIdOf(card)); }
        @Override public boolean hasUnwatched(Object bucket) { return hasUnwatchedOf(bucket); }
        @Override public void publishWatched(Object bucket, String bucketId) {
            Object session = sessionOf(bucket);
            String own = isRegular(bucket) ? text(bucketTypeOf(bucket)) : null;
            for (String type : typesFor(own)) publishSeen(session, bucketId, type, SEEN_FIELD, true);
            Logger.printDebug(() -> "Story views: published a " + own + " bucket as watched");
        }
    };

    /**
     * The tree types a bucket goes out as watched under: Facebook's own, and the bucket's when it
     * has another. A Highlights tile's bucket is no DirectMessageThreadBucket, so a publish under
     * that name alone never reached its ring, and it stayed lit however often it was watched (#165).
     */
    static List<String> typesFor(@Nullable String own) {
        List<String> types = new ArrayList<>(2);
        types.add(BUCKET_TYPE);
        if (own != null && !own.isEmpty() && !own.equals(BUCKET_TYPE)) types.add(own);
        return types;
    }

    /** Whether [bucket] is a RegularStoryBucket, the one kind whose tree the patch reads its type from. */
    private static boolean isRegular(Object bucket) {
        for (Class<?> type = bucket.getClass(); type != null; type = type.getSuperclass()) {
            if (REGULAR_BUCKET.equals(type.getName())) return true;
        }
        return false;
    }

    private static volatile Stories stories = PATCHED;

    /** Set while the code here asks a bucket for Facebook's own answer, or reads one itself. */
    private static final ThreadLocal<boolean[]> ASKING = ThreadLocal.withInitial(() -> new boolean[1]);
    private static volatile SeenBuckets store;
    /** Every card watched with the switch on, by its own id (#218). */
    private static volatile SeenBuckets cardStore;
    /** The buckets watched to the end since the last send, until it's held back. */
    private static final List<WeakReference<Object>> finished = new ArrayList<>();
    /** Each bucket's window: when it opened, by SystemClock.elapsedRealtime, and the marks in it. */
    private static final Map<String, long[]> published = new HashMap<>();

    /**
     * One call of Facebook's sender, kept with the cards it held back, so a card marked later can
     * go out through the same sender, callback and account. The sender and the session are held
     * weakly. The callback is held as it is: Facebook makes a new one for each batch and nothing
     * else keeps it, so a weak hold lost it at the next garbage collection and a mark waited for
     * a later batch. {@link StoryMarks} keeps at most {@link StoryMarks#MAX_HELD} held cards for 24
     * hours, so these stay few.
     */
    static final class Call {
        final WeakReference<Object> sender;
        @Nullable final Object listener;
        final WeakReference<Object> session;
        @Nullable final String first;
        @Nullable final String second;
        @Nullable final String third;
        final boolean peek;

        Call(@Nullable Object sender, @Nullable Object listener, @Nullable Object session, @Nullable String first,
             @Nullable String second, @Nullable String third, boolean peek) {
            this.sender = new WeakReference<>(sender);
            this.listener = listener;
            this.session = new WeakReference<>(session);
            this.first = first;
            this.second = second;
            this.third = third;
            this.peek = peek;
        }
    }

    private StorySeen() {
    }

    /**
     * Injection point, first thing in the seen sender, handed what the sender was: the sender, its
     * callback, the account's session, its three strings, the card filters, the set of card ids and
     * whether the cards were only peeked at. Answers the set to send: [ids] itself while the switch
     * is off, paused or before the settings are ready; a new set holding only the cards marked with
     * Mark as seen, which the patch sends without the card filters; or null, and the sender returns
     * before building anything. Never throws.
     */
    @Nullable
    public static Set<?> toSend(@Nullable Object sender, @Nullable Object listener, @Nullable Object session,
                                @Nullable String first, @Nullable String second, @Nullable String third,
                                @Nullable Map<?, ?> filters, @Nullable Set<?> ids, boolean peek) {
        try {
            HookStatus.invoked(FamilyNames.STORY_SEEN);
            FeedFilterCounters.sawList(ROUTE, 1);
        } catch (Throwable ignored) {
            // Counters are optional.
        }
        try {
            if (!Utils.settingsReady() || !Settings.VIEW_STORIES_ANONYMOUSLY.get()) return ids;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, SEND_HOOK, failure);
            return ids;
        }
        Set<String> marked = null;
        try {
            if (StorySeenButton.switchedOn()) {
                marked = MARKS.choose(account(session), ids, new Call(sender, listener, session, first, second, third, peek));
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, MARKED_HOOK, failure);
            marked = null;
        }
        publishFinishedLater();
        try {
            if (marked == null) {
                FeedFilterCounters.removed(ROUTE, 1, HELD_BACK);
                Logger.printDebug(() -> "Story views: held back a batch of viewed stories");
            } else {
                Logger.printDebug(() -> "Story views: sent only the stories marked as seen");
            }
        } catch (Throwable ignored) {
            // Counters are optional.
        }
        return marked;
    }

    /**
     * Sends [card], which a batch of [account]'s held back and which has been marked since, through
     * the sender that held it, right away. The sender's hook fills the empty set it's handed with
     * the marked cards that sender held. False when that sender, its callback or the session is
     * gone, and the next send carries the card instead. Never throws.
     */
    static boolean sendHeld(@Nullable String account, @Nullable String card) {
        try {
            Call call = MARKS.heldBy(account, card);
            if (call == null) return false;
            Object sender = call.sender.get();
            Object listener = call.listener;
            Object session = call.session.get();
            if (sender == null || listener == null || session == null) return false;
            send(sender, listener, session, call.first, call.second, call.third, null, new LinkedHashSet<String>(),
                    call.peek);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, MARKED_HOOK, failure);
            return false;
        }
    }

    /**
     * The user ID of the account [session] is for, Facebook's FbUserSession: the first thing it
     * writes to a Parcel. Null for anything else.
     */
    @Nullable
    public static String account(@Nullable Object session) {
        if (!(session instanceof Parcelable)) return null;
        Parcel parcel = Parcel.obtain();
        try {
            ((Parcelable) session).writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            String id = parcel.readString();
            return id == null || id.isEmpty() ? null : id;
        } catch (Throwable failure) {
            return null;
        } finally {
            parcel.recycle();
        }
    }

    // ------------------------------------------------------------------ watched on the phone (#61)

    /**
     * Injection point, first thing in the seen helper's method that queues a viewed card for the
     * next send. [peek] is the flag Facebook sends as is_story_peek_view: a glance from outside the
     * viewer, which doesn't count. Recently watched hears each card that isn't a peek here, with
     * this switch on or off. Never throws.
     */
    public static void viewed(Object bucket, Object card, Object helper, boolean peek) {
        if (!peek) remember(bucket, card);
        try {
            if (peek || !Utils.settingsReady() || !Settings.VIEW_STORIES_ANONYMOUSLY.get()) return;
            Stories access = stories;
            String story = access.storyId(card);
            String bucketId = access.bucketId(bucket);
            if (story == null || bucketId == null) return;
            long now = System.currentTimeMillis();
            SeenBuckets cards = cardStore();
            if (cards != null) cards.watched(story, bucketId, now);
            if (!story.equals(newestStory(bucket, access))) return;
            SeenBuckets seen = store();
            if (seen == null) return;
            seen.watched(bucketId, story, now);
            synchronized (finished) {
                finished.add(new WeakReference<>(bucket));
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, "story card viewed", failure);
        }
    }

    /** Hands a viewed card to Recently watched, which asks its own switch first. Never throws. */
    private static void remember(Object bucket, Object card) {
        try {
            if (!RecentlyWatched.keeping()) return;
            Stories access = stories;
            RecentlyWatched.storyViewed(access.storyId(card), access.bucketId(bucket), card);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.RESUME_LONG_VIDEOS, "recently watched story", failure);
        }
    }

    /**
     * Injection point, last thing in the constructor of Facebook's story bucket. One kept here as
     * watched is checked on the main thread, once it's built. With the switch off, and not just
     * paused, everything kept here is forgotten. Never throws.
     */
    public static void bucketMade(Object bucket) {
        try {
            if (!Utils.settingsReady()) return;
            SeenBuckets seen = store();
            if (seen == null) return;
            long now = System.currentTimeMillis();
            if (!Settings.VIEW_STORIES_ANONYMOUSLY.savedValue()) {
                if (!seen.isEmpty(now)) seen.clear();
                SeenBuckets cards = cardStore();
                if (cards != null && !cards.isEmpty(now)) cards.clear();
                return;
            }
            if (!Settings.VIEW_STORIES_ANONYMOUSLY.get()) return;
            // Buckets are built before the viewer opens one, so the cards' file is read here,
            // ahead of the start check that opens the viewer (#218).
            SeenBuckets cards = cardStore();
            if (cards != null) cards.isEmpty(now);
            if (seen.isEmpty(now)) return;
            String bucketId = stories.bucketId(bucket);
            if (bucketId == null || seen.newestWatched(bucketId, now) == null) return;
            Utils.runOnMainThread(() -> publishIfStillWatched(bucket, bucketId));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, "story bucket made", failure);
        }
    }

    /**
     * Injection point, first thing in the check the viewer makes of each card while it picks where
     * a bucket opens: true for a card watched on this phone with the switch on, so the check answers
     * no and the viewer starts past it (#218). Facebook's own answer stands for every other card, and
     * with the switch off or paused. Never throws.
     */
    public static boolean watchedHere(@Nullable Object card) {
        try {
            if (card == null || !Utils.settingsReady() || !Settings.VIEW_STORIES_ANONYMOUSLY.get()) return false;
            SeenBuckets cards = cardStore();
            if (cards == null) return false;
            String story = stories.storyId(card);
            if (story == null || cards.newestWatched(story, System.currentTimeMillis()) == null) return false;
            HookStatus.counted(FamilyNames.STORY_SEEN, PASSED_OVER);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, "story start", failure);
            return false;
        }
    }

    /**
     * Injection point, first thing in the story bucket's answer to whether it has a story not
     * watched yet: StoryBucket's own, which answers for the lightweight buckets a cold start draws
     * the tray with, and RegularStoryBucket's. True for a bucket watched to the end here whose
     * newest card is still the one it had then, or was watched here itself (a lightweight bucket's
     * one card is its preview story), so the answer is no and the ring is grey the first time it's
     * drawn (#165). Facebook's answer stands for every other bucket, with the switch off or paused,
     * and while the code here asks. Never throws.
     */
    public static boolean readsWatched(@Nullable Object bucket) {
        boolean[] asking = ASKING.get();
        if (bucket == null || asking[0]) return false;
        asking[0] = true;
        try {
            if (!Utils.settingsReady() || !Settings.VIEW_STORIES_ANONYMOUSLY.get()) return false;
            SeenBuckets seen = store();
            if (seen == null) return false;
            Stories access = stories;
            String bucketId = access.bucketId(bucket);
            long now = System.currentTimeMillis();
            String watched = bucketId == null ? null : seen.newestWatched(bucketId, now);
            if (watched == null) return false;
            String newest = newestStory(bucket, access);
            if (newest == null) return false;
            if (!watched.equals(newest)) {
                SeenBuckets cards = cardStore();
                if (cards == null || cards.newestWatched(newest, now) == null) return false;
            }
            HookStatus.counted(FamilyNames.STORY_SEEN, READ_WATCHED);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, "story bucket read", failure);
            return false;
        } finally {
            asking[0] = false;
        }
    }

    /** The bucket's own answer, Facebook's, past {@link #readsWatched}. */
    private static boolean facebooksAnswer(Object bucket, Stories access) {
        boolean[] asking = ASKING.get();
        boolean was = asking[0];
        asking[0] = true;
        try {
            return access.hasUnwatched(bucket);
        } finally {
            asking[0] = was;
        }
    }

    /**
     * The views are held back, so the buckets watched to the end since the last send go out as
     * watched, on the main thread. Never throws.
     */
    private static void publishFinishedLater() {
        try {
            List<Object> watched = new ArrayList<>();
            synchronized (finished) {
                for (WeakReference<Object> bucket : finished) {
                    Object live = bucket.get();
                    if (live != null) watched.add(live);
                }
                finished.clear();
            }
            if (!watched.isEmpty()) Utils.runOnMainThread(() -> publishFinished(watched));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, "watched story buckets", failure);
        }
    }

    private static void publishFinished(List<Object> buckets) {
        try {
            Stories access = stories;
            for (Object bucket : buckets) {
                String bucketId = access.bucketId(bucket);
                if (bucketId != null && facebooksAnswer(bucket, access)) publish(bucket, bucketId, access);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, "watched story buckets", failure);
        }
    }

    /** The bucket goes out as watched when it still has the newest card it had when it was watched. */
    private static void publishIfStillWatched(Object bucket, String bucketId) {
        try {
            if (!Utils.settingsReady() || !Settings.VIEW_STORIES_ANONYMOUSLY.get()) return;
            SeenBuckets seen = store();
            String watched = seen == null ? null : seen.newestWatched(bucketId, System.currentTimeMillis());
            Stories access = stories;
            if (watched == null || !facebooksAnswer(bucket, access) || !watched.equals(newestStory(bucket, access))) return;
            publish(bucket, bucketId, access);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, "watched story bucket", failure);
        }
    }

    private static void publish(Object bucket, String bucketId, Stories access) {
        long now = SystemClock.elapsedRealtime();
        synchronized (published) {
            long[] window = published.get(bucketId);
            if (window == null || now - window[0] >= REMARK_MS) {
                window = new long[] {now, 0};
                published.put(bucketId, window);
            }
            if (window[1] >= MARKS_PER_WINDOW) return;
            window[1]++;
        }
        access.publishWatched(bucket, bucketId);
        HookStatus.counted(FamilyNames.STORY_SEEN, MARKED);
        Logger.printDebug(() -> "Story views: marked a story bucket watched on this phone");
    }

    /** The id of the bucket's newest card, the last of its cards, or null. */
    @Nullable
    private static String newestStory(Object bucket, Stories access) {
        List<?> cards = access.cards(bucket);
        if (cards == null || cards.isEmpty()) return null;
        return access.storyId(cards.get(cards.size() - 1));
    }

    @Nullable
    private static SeenBuckets store() {
        SeenBuckets seen = store;
        if (seen != null) return seen;
        Context context = Utils.getContext();
        if (context == null) return null;
        synchronized (StorySeen.class) {
            if (store == null) {
                store = new SeenBuckets(context.getSharedPreferences(SeenBuckets.FILE, Context.MODE_PRIVATE));
            }
            return store;
        }
    }

    @Nullable
    private static SeenBuckets cardStore() {
        SeenBuckets cards = cardStore;
        if (cards != null) return cards;
        Context context = Utils.getContext();
        if (context == null) return null;
        synchronized (StorySeen.class) {
            if (cardStore == null) {
                cardStore = new SeenBuckets(context.getSharedPreferences(SeenBuckets.CARDS_FILE, Context.MODE_PRIVATE),
                        SeenBuckets.MAX_CARDS);
            }
            return cardStore;
        }
    }

    @Nullable
    private static String text(Object value) {
        return value instanceof String ? (String) value : null;
    }

    static void useForTests(@Nullable Stories access) {
        stories = access == null ? PATCHED : access;
        store = null;
        cardStore = null;
        synchronized (finished) {
            finished.clear();
        }
        synchronized (published) {
            published.clear();
        }
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled in by the patch: hands [ids] to Facebook's sender [sender] with the rest of its arguments. */
    public static void send(Object sender, Object listener, Object session, @Nullable String first,
                            @Nullable String second, @Nullable String third, @Nullable Map<?, ?> filters,
                            Set<?> ids, boolean peek) {
    }

    /**
     * Filled in by the patch: the bucket's id, StoryBucket.getId(). The stubs below each use only
     * their parameter registers, so their own register counts don't matter.
     */
    public static Object bucketIdOf(Object bucket) {
        return UNPATCHED;
    }

    /** Filled in by the patch: the bucket's cards, an ImmutableList of StoryCard. */
    public static Object cardsOf(Object bucket) {
        return UNPATCHED;
    }

    /** Filled in by the patch: the card's id, StoryCard.getId(). */
    public static Object storyIdOf(Object card) {
        return UNPATCHED;
    }

    /**
     * Filled in by the patch: the bucket's own answer to whether it has a story not watched yet, the
     * method Facebook's bucket answers from is_bucket_seen_by_viewer.
     */
    public static boolean hasUnwatchedOf(Object bucket) {
        return false;
    }

    /** Filled in by the patch: the FbUserSession the bucket was built for. */
    public static Object sessionOf(Object bucket) {
        return UNPATCHED;
    }

    /** Filled in by the patch: the GraphQL type of a RegularStoryBucket's tree, its getTypeName(). */
    public static Object bucketTypeOf(Object bucket) {
        return UNPATCHED;
    }

    /**
     * Filled in by the patch with the calls Facebook makes when its servers answer a report: the
     * session's GraphQL consistency, a [type] tree with the bucket's id and [field] set to [seen],
     * published with full consistency. Unfilled, nothing is published.
     */
    public static void publishSeen(Object session, String bucketId, String type, String field, boolean seen) {
    }
}
