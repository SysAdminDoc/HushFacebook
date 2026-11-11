/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import android.app.Activity;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What the Hide suggested stories patch does to the buckets of a Stories tray before Facebook
 * keeps them.
 *
 * <p>A tray holds one bucket per person or Page with a story. Every answer of the tray's fetch,
 * from the network or the cache, becomes one tray data object, and both of Facebook's trays, the
 * classic one and the unified one, draw from it. The hook goes first in that object's one
 * constructor and hands its bucket list to {@link #keptBuckets}. Home's tray comes inside the feed
 * instead, as does a single story shown in the feed, and Facebook's tray parser builds what those
 * draw, so a second hook goes first in that parser and hands it the same kind of list (#313, #317).
 *
 * <p>A bucket is a suggestion when Facebook marks it as one: its is_story_bucket_suggested flag is
 * true, or its first label is SUGGESTED. Those are the two things the tray's own card reads before
 * it writes "Suggested" under the name.
 *
 * <p>Some buckets aren't stories at all but cards: the tray's tile dispatcher picks a bucket's card
 * by its story_bucket_type, and draws a PYMK_STORY or PYMK_PROFILE_FORWARD_STORY bucket as a
 * "People you may know" card with an Add button, and a CONTACT_IMPORTER_STORY bucket as the "Find
 * friends from contacts" card. The type decides the card, so it's read first. The People you may
 * know cards go under that switch, which belongs to Hide suggested and promoted posts, so they
 * only go when that patch is in too. The contacts card has a switch of its own.
 *
 * <p>Two more switches, both off until you turn them on, go by who posted a story and whether you
 * watched it. A Page's bucket is one whose story_bucket_owner is a Page, or whose type is PAGE_STORY,
 * SHARED_PAGE_STORY or one of a profile-style Page's two (#313). A seen bucket is one whose is_bucket_seen_by_viewer flag is true, the
 * flag that greys the tray's ring, and which View stories anonymously sets on this phone for the
 * stories it watches (#317). The cards above aren't stories, and neither is the Add to story tile,
 * so none of them goes as seen. That flag comes from the servers, which never hear of a story
 * watched with View stories anonymously on, so a bucket whose flag is false also goes as seen when
 * it was watched to the end on this phone after its newest story was posted, by its id and
 * expiration_time ({@link StorySeen#trayWatchedHere}, #218), or when an earlier tray sent it as
 * watched with the same expiration_time, so no story came since (#340). Show only watched stories turns that
 * round: a story whose flag reads false and that wasn't watched here goes, and Hide seen stories
 * waits while it's on (#340). A bucket whose flag can't be read stays either way. While a story
 * viewer is open, a bucket a tray kept doesn't leave as seen, so the viewer keeps the one it's
 * playing, and it leaves the first tray after the viewer closes (#438).
 *
 * <p>Nothing else counts, so a friend's story, a Page you follow, your own story, a friend
 * request and anything that isn't a bucket or whose type isn't one of those stay.
 *
 * <p>The cards beside Create story that suggest a story to make aren't buckets. The server sends
 * them in a list of their own, which the tray's fetch can ask it to leave out, and
 * {@link #skipPromptCards} answers that question first.
 *
 * <p>The bucket interface, its accessors and Facebook's label helper are Redex names that change
 * every build, so the patch fills in the nine stubs below. It fails open: switch off, a pause,
 * settings that aren't ready, a bucket it can't read, a list it can't copy, or any failure in
 * here, and the tray keeps every bucket Facebook sent.
 */
public final class SuggestedStories {
    /** The diagnostic counter route: each tray list, each bucket's kind, and the buckets left out. */
    static final String ROUTE = "Stories tray buckets";

    /** The GraphQL flag and the label constant the tray's card reads, as the report names them. */
    static final String SUGGESTED_FLAG = "is_story_bucket_suggested";
    static final String SUGGESTED_LABEL = "SUGGESTED";

    /** The GraphQL field the tray's card dispatcher reads, and the types of it that are cards. */
    static final String BUCKET_TYPE_FIELD = "story_bucket_type";
    static final String PYMK_TYPE = "PYMK_STORY";
    static final String PYMK_PROFILE_FORWARD_TYPE = "PYMK_PROFILE_FORWARD_STORY";
    static final String CONTACT_IMPORTER_TYPE = "CONTACT_IMPORTER_STORY";

    /** The types a Page's stories come as, and the Add to story tile's, which never goes as seen. */
    static final String PAGE_TYPE = "PAGE_STORY";
    static final String SHARED_PAGE_TYPE = "SHARED_PAGE_STORY";
    /**
     * A profile-style Page is an additional profile ("Profile Plus" inside the app) whose owner reads
     * as a User, not a Page. The S22's tray on 2026-10-31 kept National Geographic's story with Hide
     * stories from Pages on: its one kept bucket was ADMINED_ADDITIONAL_PROFILE_STORY.
     * PROFESSIONAL_STORY is a person in professional mode, so it stays.
     */
    static final String ADDITIONAL_PROFILE_PAGE_TYPE = "ADMINED_ADDITIONAL_PROFILE_STORY";
    static final String PROFILE_PLUS_PAGE_TYPE = "PROFILE_PLUS_STORY";
    static final String ADD_TO_STORY_TYPE = "ADD_TO_STORY";

    /** The GraphQL fields of who posted a bucket and whether you've watched it, and a Page's type name. */
    static final String OWNER_FIELD = "story_bucket_owner";
    static final String SEEN_FIELD = "is_bucket_seen_by_viewer";
    static final String PAGE_OWNER = "Page";

    /** The fields a bucket watched on this phone is looked up by: its id, and when it expires. */
    static final String ID_FIELD = "id";
    static final String EXPIRATION_FIELD = "expiration_time";

    /** What a bucket counts as. Only the first five can leave the tray, each behind its switch. */
    static final String SUGGESTED = "suggested";
    static final String LABELLED_SUGGESTED = "labelled suggested";
    static final String PEOPLE_YOU_MAY_KNOW = "people you may know";
    static final String CONTACT_IMPORT = "contact import card";
    static final String PAGE_STORY = "Page story";
    static final String NOT_SUGGESTED = "not suggested";
    static final String NOT_A_BUCKET = "not a bucket";
    static final String NOT_PATCHED = "reader not patched";
    static final String READ_FAILED = "read failed";

    /** What a story that left the tray because you watched it is counted under. */
    static final String SEEN = "seen";

    /** What a story that left the tray because you haven't watched it yet is counted under (#340). */
    static final String UNWATCHED = "not watched yet";

    /** Debug intake is bounded and never expands an enum constant into a person's bucket fields. */
    private static final int TYPE_DIAGNOSTIC_ITEMS = 256;
    private static final int TYPE_DIAGNOSTIC_NAMES = 16;
    private static final java.util.regex.Pattern TYPE_NAME = java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]{0,95}");

    /** What {@link #suggested}, {@link #label} and {@link #bucketType} answer until the patch fills them in. */
    static final Object UNPATCHED = new Object();

    /**
     * Whether Hide suggested and promoted posts, which owns the People you may know switch, is in
     * this build, when a test says so instead of {@link SettingsStatus}.
     */
    @Nullable
    static volatile Boolean peopleYouMayKnowInBuildForTests;

    /**
     * How many story viewers are open, from the activity callbacks (#438). While one is, a bucket a
     * tray kept doesn't leave as seen: watching it marks it seen on this phone, its tray answers
     * again, and the viewer would lose the bucket it's playing and jump to the next one. Guarded by
     * {@link #trayIds}.
     */
    private static int viewersOpen;

    /** Ids of the buckets the trays kept with Hide seen stories on, oldest first, at most {@link #TRAY_IDS}. */
    private static final LinkedHashSet<String> trayIds = new LinkedHashSet<>();

    /** Ids kept only because a story viewer was open, which leave the first tray after it closes. Guarded by {@link #trayIds}. */
    private static final Set<String> heldIds = new HashSet<>();

    /** A tray holds a few dozen buckets, so this covers many trays without growing for the whole session. */
    private static final int TRAY_IDS = 512;

    /** A tray's buckets, read through the stubs the patch filled in or through a test's stand-in. */
    interface Buckets {
        boolean isBucket(Object item);

        /** The bucket's story_bucket_type, an enum constant or null, or {@link #UNPATCHED}. */
        @Nullable
        Object type(Object bucket);

        /** The bucket's flag as a Boolean, or {@link #UNPATCHED}. */
        @Nullable
        Object suggested(Object bucket);

        /** The bucket's first label, an enum constant or null, or {@link #UNPATCHED}. */
        @Nullable
        Object label(Object bucket);

        /** An ImmutableList of [kept], or null when there's nothing to make one with. */
        @Nullable
        Object copy(List<Object> kept);

        /** The bucket's is_bucket_seen_by_viewer flag as a Boolean, or {@link #UNPATCHED}. */
        @Nullable
        default Object seen(Object bucket) {
            return UNPATCHED;
        }

        /** The type name of the bucket's owner, "User" or "Page", null for none, or {@link #UNPATCHED}. */
        @Nullable
        default Object ownerType(Object bucket) {
            return UNPATCHED;
        }

        /** The bucket's id, null for none, or {@link #UNPATCHED}. */
        @Nullable
        default Object id(Object bucket) {
            return UNPATCHED;
        }

        /** The bucket's expiration_time in seconds since 1970 as a Long, or {@link #UNPATCHED}. */
        @Nullable
        default Object expires(Object bucket) {
            return UNPATCHED;
        }
    }

    static final Buckets PATCHED = new Buckets() {
        @Override
        public boolean isBucket(Object item) {
            return SuggestedStories.isBucket(item);
        }

        @Nullable
        @Override
        public Object type(Object bucket) {
            return SuggestedStories.bucketType(bucket);
        }

        @Nullable
        @Override
        public Object suggested(Object bucket) {
            return SuggestedStories.suggested(bucket);
        }

        @Nullable
        @Override
        public Object label(Object bucket) {
            return SuggestedStories.label(bucket);
        }

        @Nullable
        @Override
        public Object copy(List<Object> kept) {
            return immutableCopy(kept);
        }

        @Nullable
        @Override
        public Object seen(Object bucket) {
            return SuggestedStories.seen(bucket);
        }

        @Nullable
        @Override
        public Object ownerType(Object bucket) {
            return SuggestedStories.ownerType(bucket);
        }

        @Nullable
        @Override
        public Object id(Object bucket) {
            return SuggestedStories.bucketId(bucket);
        }

        @Nullable
        @Override
        public Object expires(Object bucket) {
            return SuggestedStories.expires(bucket, 0L);
        }
    };

    /** Which kinds leave the tray, read once for each tray. */
    static final class Switches {
        /** Before the settings are ready, or when they can't be read: every bucket stays. */
        static final Switches NONE = new Switches(false, false, false, false, false, false, false);

        final boolean suggested;
        /** Whether the People you may know switch is in this build at all. */
        final boolean peopleYouMayKnowInBuild;
        final boolean peopleYouMayKnow;
        final boolean contactImport;
        final boolean pageStories;
        /** Hide seen stories, which waits while Show only watched stories is on. */
        final boolean seenStories;
        /** Show only watched stories (#340). */
        final boolean onlyWatched;

        Switches(boolean suggested, boolean peopleYouMayKnowInBuild, boolean peopleYouMayKnow, boolean contactImport,
                boolean pageStories, boolean seenStories, boolean onlyWatched) {
            this.suggested = suggested;
            this.peopleYouMayKnowInBuild = peopleYouMayKnowInBuild;
            this.peopleYouMayKnow = peopleYouMayKnowInBuild && peopleYouMayKnow;
            this.contactImport = contactImport;
            this.pageStories = pageStories;
            this.seenStories = seenStories && !onlyWatched;
            this.onlyWatched = onlyWatched;
        }

        /** Whether a bucket of this kind leaves the tray. */
        boolean leaves(String kind) {
            if (SUGGESTED.equals(kind) || LABELLED_SUGGESTED.equals(kind)) return suggested;
            if (PEOPLE_YOU_MAY_KNOW.equals(kind)) return peopleYouMayKnow;
            if (CONTACT_IMPORT.equals(kind)) return contactImport;
            if (PAGE_STORY.equals(kind)) return pageStories;
            return false;
        }

        /** Whether a bucket of this kind leaves once you've watched it: a story, whoever posted it. */
        boolean leavesSeen(String kind) {
            return seenStories && story(kind);
        }

        /** Whether a bucket of this kind leaves while you haven't watched it, with Show only watched stories on. */
        boolean leavesUnwatched(String kind) {
            return onlyWatched && story(kind);
        }

        private static boolean story(String kind) {
            return SUGGESTED.equals(kind) || LABELLED_SUGGESTED.equals(kind) || PAGE_STORY.equals(kind)
                    || NOT_SUGGESTED.equals(kind);
        }

        /**
         * " (off: suggested, contact import card)", naming the switches this build has that are off, or "";
         * " (switches not read)" when they weren't, so the line doesn't call them off.
         */
        String offNote() {
            if (this == NONE) return " (switches not read)";
            List<String> off = new ArrayList<>();
            if (!suggested) off.add(SUGGESTED);
            if (peopleYouMayKnowInBuild && !peopleYouMayKnow) off.add(PEOPLE_YOU_MAY_KNOW);
            if (!contactImport) off.add(CONTACT_IMPORT);
            return off.isEmpty() ? "" : " (off: " + String.join(", ", off) + ")";
        }
    }

    private SuggestedStories() {
    }

    /**
     * Injection point, first in the constructor of the tray data and first in the tray parser that
     * Home's tray and a story shown in the feed go through (#313, #317). Answers the list it was handed,
     * or an ImmutableList of the same buckets in the same order without the ones whose switch is
     * on, which takes the list's place. Never throws.
     *
     * @param buckets the tray's bucket list, an ImmutableList.
     */
    @Nullable
    public static Object keptBuckets(@Nullable List<?> buckets) {
        return keptBuckets(buckets, PATCHED);
    }

    /** {@link #keptBuckets(List)} with the bucket reads passed in, so a test can stand in for the stubs. */
    @Nullable
    static Object keptBuckets(@Nullable List<?> buckets, Buckets access) {
        try {
            HookStatus.invoked(FamilyNames.SUGGESTED_STORIES);
            if (buckets == null) return null;
            FeedFilterCounters.sawList(ROUTE, buckets.size());
            if (buckets.isEmpty()) return buckets;

            Switches switches = switches();
            List<String> kinds = new ArrayList<>(buckets.size());
            List<String> left = new ArrayList<>();
            ArrayList<Object> kept = null;
            int held = 0;
            for (int index = 0; index < buckets.size(); index++) {
                Object bucket = buckets.get(index);
                String kind = kindOf(bucket, access, switches.pageStories);
                kinds.add(kind);
                FeedFilterCounters.sawKind(ROUTE, kind);
                String leaving = switches.leaves(kind) ? kind
                        : switches.leavesSeen(kind) && watched(bucket, access) ? SEEN
                        : switches.leavesUnwatched(kind) && unwatched(bucket, access) ? UNWATCHED : null;
                if (switches.seenStories && (leaving == null || SEEN.equals(leaving))
                        && keptForViewer(bucket, access, leaving != null)) {
                    leaving = null;
                    held++;
                }
                if (leaving != null) {
                    if (kept == null) {
                        kept = new ArrayList<>(buckets.size());
                        kept.addAll(buckets.subList(0, index));
                    }
                    left.add(leaving);
                } else if (kept != null) {
                    kept.add(bucket);
                }
            }

            Object answer = buckets;
            if (kept != null) {
                Object copy = access.copy(kept);
                if (copy == null) {
                    HookStatus.missingMember(FamilyNames.SUGGESTED_STORIES, "method", "ImmutableList", "copyOf(Collection)");
                    left.clear();
                } else {
                    answer = copy;
                    for (String kind : left) FeedFilterCounters.removed(ROUTE, 1, kind);
                }
            }

            // What a tray held, as kinds and how many of each, never whose stories they are.
            final int shown = buckets.size() - left.size();
            StoriesTrayPaging.sawTray(shown, left.size());
            final String off = switches.offNote();
            final String playing = held == 0 ? "" : ". " + held + " seen kept while a story plays.";
            Logger.printDebug(() -> "Stories tray: " + buckets.size() + " buckets, " + shown + " kept. Kinds: "
                    + summary(kinds) + off + playing);
            // Observe the actual delivery, including a refused copy. Diagnostics must not undo a filter.
            final Object delivered = answer;
            try {
                Logger.printDebug(() -> keptTypeEvidence(delivered, access));
            } catch (Throwable ignored) {
                // A diagnostic failure leaves the already selected delivery intact.
            }
            return answer;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "tray bucket filter", failure);
            Logger.printException(() -> "Hide suggested stories: could not read the tray's buckets", failure);
            return buckets;
        }
    }

    /**
     * Notes what a tray did with a bucket for the story viewer (#438) and answers whether a bucket
     * leaving as seen stays: one a tray kept stays while a viewer is open, and leaves the first tray
     * after it closes. A bucket already seen when the viewer opened still leaves. Only Hide seen
     * stories asks, and a bucket whose id can't be read goes by its flags. Never throws.
     */
    private static boolean keptForViewer(Object bucket, Buckets access, boolean leavingSeen) {
        Object id;
        try {
            id = access.id(bucket);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "bucket id and expiration", failure);
            return false;
        }
        if (!(id instanceof String)) return false;
        synchronized (trayIds) {
            if (leavingSeen) {
                if (viewersOpen > 0 && trayIds.contains(id)) {
                    heldIds.add((String) id);
                    return true;
                }
                trayIds.remove(id);
                return false;
            }
            trayIds.remove(id);
            trayIds.add((String) id);
            if (trayIds.size() > TRAY_IDS) {
                Iterator<String> oldest = trayIds.iterator();
                oldest.next();
                oldest.remove();
            }
            return false;
        }
    }

    /** From the activity callbacks: a story viewer opened. Never throws. */
    public static void activityCreated(Activity activity) {
        try {
            if (!StorySeenButton.isViewer(activity)) return;
            synchronized (trayIds) {
                viewersOpen++;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "story viewer opened", failure);
        }
    }

    /**
     * From the activity callbacks: a story viewer closed. Once none is open, the buckets kept only
     * for one leave the next tray. A viewer rebuilt for a rotation keeps them. Never throws.
     */
    public static void activityDestroyed(Activity activity) {
        try {
            if (!StorySeenButton.isViewer(activity)) return;
            synchronized (trayIds) {
                if (viewersOpen > 0) viewersOpen--;
                if (viewersOpen > 0 || activity.isChangingConfigurations()) return;
                trayIds.removeAll(heldIds);
                heldIds.clear();
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "story viewer closed", failure);
        }
    }

    /** Forgets the open viewers and the kept ids, as a fresh start of the app would. */
    static void resetViewersForTests() {
        synchronized (trayIds) {
            viewersOpen = 0;
            trayIds.clear();
            heldIds.clear();
        }
    }

    /**
     * Names/counts of kept types other than STORY and the cards already counted by friendly kind.
     * Other types can still hold friends' stories. This identifies no music prompt and makes no new
     * removal decision. No labels, owner fields, IDs, enum toString(), exception values or class names
     * are read or logged.
     * Built lazily by Logger only with Debug on. Missing or omitted evidence is explicit.
     */
    private static String keptTypeEvidence(Object delivered, Buckets access) {
        String prefix = "Stories tray kept unclassified types: ";
        try {
            if (!(delivered instanceof List)) return prefix + "unavailable. Complete false.";
            List<?> items = (List<?>) delivered;
            int size = items.size();
            int visited = Math.min(size, TYPE_DIAGNOSTIC_ITEMS);
            int unknown = 0;
            int omitted = size - visited;
            Map<String, Integer> names = new LinkedHashMap<>();
            for (int index = 0; index < visited; index++) {
                try {
                    Object item = items.get(index);
                    if (item == null || !access.isBucket(item)) { unknown++; continue; }
                    Object type = access.type(item);
                    if (!(type instanceof Enum)) { unknown++; continue; }
                    String name = ((Enum<?>) type).name();
                    if (!TYPE_NAME.matcher(name).matches()) { unknown++; continue; }
                    if ("STORY".equals(name) || PYMK_TYPE.equals(name)
                            || PYMK_PROFILE_FORWARD_TYPE.equals(name) || CONTACT_IMPORTER_TYPE.equals(name)) continue;
                    Integer count = names.get(name);
                    if (count != null) names.put(name, count + 1);
                    else if (names.size() < TYPE_DIAGNOSTIC_NAMES) names.put(name, 1);
                    else omitted++;
                } catch (Throwable ignored) {
                    unknown++; // The reader's exception can contain private fields, so keep only the count.
                }
            }
            StringBuilder summary = new StringBuilder(prefix);
            for (Map.Entry<String, Integer> entry : names.entrySet()) {
                if (summary.length() > prefix.length()) summary.append(", ");
                summary.append(entry.getKey()).append(' ').append(entry.getValue());
            }
            if (names.isEmpty()) summary.append("none");
            return summary.append(". Complete ").append(unknown == 0 && omitted == 0)
                    .append(", unknown ").append(unknown).append(", omitted ").append(omitted).append('.').toString();
        } catch (Throwable ignored) {
            return prefix + "unavailable. Complete false.";
        }
    }

    /**
     * What one item of the list counts as. A story counts as a Page's only when [pages] asks, so
     * the owner is read only with that switch on. Never throws.
     */
    static String kindOf(@Nullable Object item, Buckets access, boolean pages) {
        try {
            if (item == null || !access.isBucket(item)) return NOT_A_BUCKET;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "bucket check", failure);
            return READ_FAILED;
        }
        // The type picks the card the tray draws, so a card is a card whatever its flags say.
        String card = cardKind(item, access);
        if (card != null) return card;
        // A Page's story goes with that switch on whether or not Facebook suggests it.
        if (pages && pageStory(item, access)) return PAGE_STORY;

        Object flag;
        try {
            flag = access.suggested(item);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "bucket flag", failure);
            return READ_FAILED;
        }
        if (flag == UNPATCHED) {
            HookStatus.missingMember(FamilyNames.SUGGESTED_STORIES, "method", "tray bucket", SUGGESTED_FLAG);
            return NOT_PATCHED;
        }
        if (!(flag instanceof Boolean)) return READ_FAILED;
        HookStatus.bound(FamilyNames.SUGGESTED_STORIES, "tray bucket#" + SUGGESTED_FLAG);
        if ((Boolean) flag) return SUGGESTED;

        Object label;
        try {
            label = access.label(item);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "bucket label", failure);
            return READ_FAILED;
        }
        if (label == UNPATCHED) {
            HookStatus.missingMember(FamilyNames.SUGGESTED_STORIES, "method", "tray bucket", "first label");
            return NOT_PATCHED;
        }
        if (label == null) return NOT_SUGGESTED;
        if (!(label instanceof Enum)) return READ_FAILED;
        HookStatus.bound(FamilyNames.SUGGESTED_STORIES, "tray bucket#first label");
        return SUGGESTED_LABEL.equals(((Enum<?>) label).name()) ? LABELLED_SUGGESTED : NOT_SUGGESTED;
    }

    /**
     * Whether a bucket holds a Page's stories: its type is one a Page's stories come as, or its
     * owner is a Page. Never throws.
     */
    static boolean pageStory(Object bucket, Buckets access) {
        try {
            Object type = access.type(bucket);
            if (type instanceof Enum) {
                String name = ((Enum<?>) type).name();
                if (PAGE_TYPE.equals(name) || SHARED_PAGE_TYPE.equals(name)
                        || ADDITIONAL_PROFILE_PAGE_TYPE.equals(name) || PROFILE_PLUS_PAGE_TYPE.equals(name)) return true;
            }
        } catch (Throwable ignored) {
            // cardKind already reported this read, and the owner still answers.
        }
        Object owner;
        try {
            owner = access.ownerType(bucket);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "bucket owner", failure);
            return false;
        }
        if (owner == UNPATCHED) {
            HookStatus.missingMember(FamilyNames.SUGGESTED_STORIES, "method", "tray bucket", OWNER_FIELD);
            return false;
        }
        if (!(owner instanceof String)) return false;
        HookStatus.bound(FamilyNames.SUGGESTED_STORIES, "tray bucket#" + OWNER_FIELD);
        return PAGE_OWNER.equals(owner);
    }

    /**
     * Whether you've watched a bucket's stories, by its is_bucket_seen_by_viewer flag, or when that's
     * false, by {@link #watchedHere}. The Add to story tile is never watched, and a flag it can't read
     * says no. Never throws.
     */
    static boolean watched(Object bucket, Buckets access) {
        return Boolean.TRUE.equals(watchedOrNot(bucket, access));
    }

    /**
     * Whether you haven't watched a bucket's stories yet, for Show only watched stories (#340): its
     * flag reads false and it wasn't watched here. The Add to story tile and a bucket whose flag
     * can't be read aren't, so they stay. Never throws.
     */
    static boolean unwatched(Object bucket, Buckets access) {
        return Boolean.FALSE.equals(watchedOrNot(bucket, access));
    }

    /** {@link #watched}'s answer, or null for the Add to story tile and a flag it can't read. */
    @Nullable
    private static Boolean watchedOrNot(Object bucket, Buckets access) {
        try {
            Object type = access.type(bucket);
            if (type instanceof Enum && ((Enum<?>) type).name().startsWith(ADD_TO_STORY_TYPE)) return null;
        } catch (Throwable ignored) {
            // cardKind already reported this read, and the flag still answers.
        }
        Object seen;
        try {
            seen = access.seen(bucket);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "bucket seen flag", failure);
            return null;
        }
        if (seen == UNPATCHED) {
            HookStatus.missingMember(FamilyNames.SUGGESTED_STORIES, "method", "tray bucket", SEEN_FIELD);
            return null;
        }
        if (!(seen instanceof Boolean)) return null;
        HookStatus.bound(FamilyNames.SUGGESTED_STORIES, "tray bucket#" + SEEN_FIELD);
        if (!(Boolean) seen) return watchedHere(bucket, access);
        sentWatched(bucket, access);
        return true;
    }

    /**
     * A bucket sent as watched, by its id and expiration_time, kept with View stories anonymously
     * on so the next tray that sends it unwatched still reads it as watched (#340). A stub the patch
     * couldn't fill is reported by {@link #watchedHere} once something is kept. Never throws.
     */
    private static void sentWatched(Object bucket, Buckets access) {
        if (!StorySeen.holdsViewsBack()) return;
        try {
            Object id = access.id(bucket);
            Object expires = access.expires(bucket);
            if (id instanceof String && expires instanceof Long) StorySeen.trayReadWatched((String) id, (Long) expires);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "bucket id and expiration", failure);
        }
    }

    /**
     * Whether a bucket the servers send as unwatched was watched to the end on this phone with View
     * stories anonymously on, which keeps them from hearing of it, so its flag stays false after
     * every refresh and restart (#218). Its id and expiration_time are read only when something is
     * kept to look them up in. Never throws.
     */
    static boolean watchedHere(Object bucket, Buckets access) {
        if (!StorySeen.keepsTrayWatched()) return false;
        Object id;
        Object expires;
        try {
            id = access.id(bucket);
            expires = access.expires(bucket);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "bucket id and expiration", failure);
            return false;
        }
        if (id == UNPATCHED || expires == UNPATCHED) {
            HookStatus.missingMember(FamilyNames.SUGGESTED_STORIES, "method", "tray bucket",
                    id == UNPATCHED ? ID_FIELD : EXPIRATION_FIELD);
            return false;
        }
        if (!(id instanceof String) || !(expires instanceof Long)) return false;
        HookStatus.bound(FamilyNames.SUGGESTED_STORIES, "tray bucket#" + EXPIRATION_FIELD);
        return StorySeen.trayWatchedHere((String) id, (Long) expires);
    }

    /**
     * The card a bucket's type makes it, {@link #PEOPLE_YOU_MAY_KNOW} or {@link #CONTACT_IMPORT},
     * or null for a story and for a type it can't read, which then goes by its flags. Never throws.
     */
    @Nullable
    static String cardKind(Object bucket, Buckets access) {
        Object type;
        try {
            type = access.type(bucket);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "bucket type", failure);
            return null;
        }
        if (type == UNPATCHED) {
            HookStatus.missingMember(FamilyNames.SUGGESTED_STORIES, "method", "tray bucket", BUCKET_TYPE_FIELD);
            return null;
        }
        if (!(type instanceof Enum)) return null;
        HookStatus.bound(FamilyNames.SUGGESTED_STORIES, "tray bucket#" + BUCKET_TYPE_FIELD);
        String name = ((Enum<?>) type).name();
        if (PYMK_TYPE.equals(name) || PYMK_PROFILE_FORWARD_TYPE.equals(name)) return PEOPLE_YOU_MAY_KNOW;
        if (CONTACT_IMPORTER_TYPE.equals(name)) return CONTACT_IMPORT;
        return null;
    }

    /**
     * Injection point, filled in by the patch: whether an item is a tray bucket. The patch replaces
     * this body with an instance-of the bucket interface, whose name changes every build.
     */
    public static boolean isBucket(Object item) {
        return false;
    }

    /**
     * Injection point, filled in by the patch: the bucket's story_bucket_type, a constant of
     * Facebook's bucket type enum or null. The patch replaces this body with a call of the
     * interface's accessor, the one the tray's card dispatcher calls. Only an item {@link #isBucket}
     * took may be passed.
     */
    public static Object bucketType(Object bucket) {
        return UNPATCHED;
    }

    /**
     * Injection point, filled in by the patch: the bucket's is_story_bucket_suggested flag, as a
     * Boolean. The patch replaces this body with a call of the interface's accessor. Only an item
     * {@link #isBucket} took may be passed.
     */
    public static Object suggested(Object bucket) {
        return UNPATCHED;
    }

    /**
     * Injection point, filled in by the patch: the bucket's first label, a constant of Facebook's
     * label enum or null. The patch replaces this body with a call of Facebook's own label helper.
     * Only an item {@link #isBucket} took may be passed.
     */
    public static Object label(Object bucket) {
        return UNPATCHED;
    }

    /**
     * Injection point, filled in by the patch: an ImmutableList of [kept], through
     * ImmutableList.copyOf, so the tray data keeps the type it stores. Null until then.
     */
    public static Object immutableCopy(List<?> kept) {
        return null;
    }

    /**
     * Injection point, filled in by the patch: the bucket's is_bucket_seen_by_viewer flag, as a
     * Boolean. The patch replaces this body with a call of the interface's accessor, the one its tree
     * class answers with that field. Only an item {@link #isBucket} took may be passed.
     */
    public static Object seen(Object bucket) {
        return UNPATCHED;
    }

    /**
     * Injection point, filled in by the patch: the type name of the bucket's story_bucket_owner,
     * such as "User" or "Page", or null when it has none. The patch replaces this body with the
     * interface's owner accessor and the owner's getTypeName(). Only an item {@link #isBucket} took
     * may be passed.
     */
    public static Object ownerType(Object bucket) {
        return UNPATCHED;
    }

    /**
     * Injection point, filled in by the patch: the bucket's id, through the interface's getId().
     * Only an item {@link #isBucket} took may be passed.
     */
    public static Object bucketId(Object bucket) {
        return UNPATCHED;
    }

    /**
     * Injection point, filled in by the patch: the bucket's expiration_time, in seconds since 1970,
     * as a Long. The patch replaces this body with a call of the interface's accessor, the one its
     * tree class answers with that field. That long takes a register pair, and {@code spare} is
     * there so the filled body can use its pair: compiled, this stub keeps no register of its own.
     * Only an item {@link #isBucket} took may be passed.
     */
    public static Object expires(Object bucket, long spare) {
        return UNPATCHED;
    }

    /** "not suggested 9, suggested 3", in the order each kind first came. */
    static String summary(List<String> kinds) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String kind : kinds) {
            Integer seen = counts.get(kind);
            counts.put(kind, seen == null ? 1 : seen + 1);
        }
        StringBuilder line = new StringBuilder();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (line.length() > 0) line.append(", ");
            line.append(entry.getKey()).append(' ').append(entry.getValue());
        }
        return line.toString();
    }

    /**
     * Injection point, right before each tray fetch sets skip_srtt_item_list, the query variable
     * asking the server to leave out the cards beside Create story: "Share music you love", a text
     * story, ready-made stories and the camera (issue #21). Facebook asks that for the Video tab's
     * tray. With Hide story prompts on, every tray asks it.
     *
     * @param facebook what Facebook was about to send
     * @return true when the switch is on, otherwise {@code facebook}. Never throws: switched off, a
     * pause, settings that aren't ready or any failure in here, and Facebook's own value goes.
     */
    public static boolean skipPromptCards(boolean facebook) {
        try {
            HookStatus.invoked(FamilyNames.SUGGESTED_STORIES);
            if (facebook || !Utils.settingsReady() || !Settings.HIDE_STORY_PROMPTS.get()) return facebook;
            Logger.printDebug(() -> "Stories tray: the fetch asks the server to leave out the story prompt cards");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "story prompt switch", failure);
            return facebook;
        }
    }

    /** Whether the People you may know switch is in this build: its patch is. */
    static boolean peopleYouMayKnowInBuild() {
        Boolean forced = peopleYouMayKnowInBuildForTests;
        return forced != null ? forced : SettingsStatus.suggestedPosts();
    }

    /** The switches. Off, unreadable, or asked before the settings are ready, the tray stays whole. */
    private static Switches switches() {
        try {
            if (!Utils.settingsReady()) return Switches.NONE;
            return new Switches(Settings.HIDE_SUGGESTED_STORIES.get(), peopleYouMayKnowInBuild(),
                    Settings.HIDE_PEOPLE_YOU_MAY_KNOW.get(), Settings.HIDE_CONTACT_IMPORT_CARD.get(),
                    Settings.HIDE_PAGE_STORIES.get(), Settings.HIDE_SEEN_STORIES.get(),
                    Settings.SHOW_ONLY_WATCHED_STORIES.get());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "switch read", failure);
            return Switches.NONE;
        }
    }
}
