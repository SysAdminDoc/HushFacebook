/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A Stories tray's bucket list as the filter sees one, with the stubs the patch fills in played by
 * a stand-in bucket class.
 */
public final class SuggestedStoriesForTests {
    private SuggestedStoriesForTests() {
    }

    /** Stands in for Facebook's bucket label enum: only the constant names matter to the filter. */
    public enum Label {
        UNSET_OR_UNRECOGNIZED_ENUM_VALUE, FAMILY, NEW, NEWFRIEND, POPULAR, SUGGESTED, TAGGED, TRENDING
    }

    /**
     * Stands in for Facebook's bucket type enum, GraphQLCameraPostTypesEnum, with a few of its
     * constants: only the names matter to the filter.
     */
    public enum Type {
        UNSET_OR_UNRECOGNIZED_ENUM_VALUE, ADD_TO_STORY, ADD_TO_STORY_SELF_VIEW, ADMINED_ADDITIONAL_PROFILE_STORY,
        CONTACT_IMPORTER_STORY, FRIEND_REQUEST_STORY, NEW_FRIENDSHIP_STORY, PAGE_STORY, PROFESSIONAL_STORY,
        PROFILE_PLUS_STORY, PYMK_GENERATED_STORY, PYMK_PROFILE_FORWARD_STORY,
        PYMK_STORY, SC_INDIA_FRIENDING_CTA_STORY, SHARED_PAGE_STORY, STORY
    }

    /**
     * Stands in for a bucket: its is_story_bucket_suggested flag, its first label, or null for a
     * bucket with none, its story_bucket_type, which is STORY unless a test says otherwise, its
     * is_bucket_seen_by_viewer flag and its owner's type name, null for a bucket with no owner.
     */
    public static final class Bucket {
        public final boolean suggested;
        public final Object label;
        public final Object type;
        public final boolean seen;
        public final Object owner;

        public Bucket(boolean suggested, Object label) {
            this(suggested, label, Type.STORY);
        }

        public Bucket(boolean suggested, Object label, Object type) {
            this(suggested, label, type, false, null);
        }

        private Bucket(boolean suggested, Object label, Object type, boolean seen, Object owner) {
            this.suggested = suggested;
            this.label = label;
            this.type = type;
            this.seen = seen;
            this.owner = owner;
        }

        /** A bucket of [type] that no flag or label marks. */
        public static Bucket of(Object type) {
            return new Bucket(false, null, type);
        }

        /** This bucket, watched to its last story. */
        public Bucket watched() {
            return new Bucket(suggested, label, type, true, owner);
        }

        /** This bucket, posted by an owner whose type name is [typeName], such as "User" or "Page". */
        public Bucket ownedBy(Object typeName) {
            return new Bucket(suggested, label, type, seen, typeName);
        }

        @Override
        public String toString() {
            return "Bucket(" + suggested + ", " + label + ", " + type + ", " + seen + ", " + owner + ")";
        }
    }

    /**
     * An ImmutableList as the copy stub hands one back: a new, unmodifiable list, never the one it
     * was given.
     */
    static final class Copy extends ArrayList<Object> {
        Copy(List<Object> kept) {
            super(kept);
        }
    }

    /**
     * The stubs as the patch fills them: an instance-of the bucket interface, reads of its type, its
     * flags and its owner's type name, a call of Facebook's label helper and ImmutableList.copyOf.
     */
    static final SuggestedStories.Buckets STAND_IN = new SuggestedStories.Buckets() {
        @Override
        public boolean isBucket(Object item) {
            return item instanceof Bucket;
        }

        @Override
        public Object type(Object bucket) {
            return ((Bucket) bucket).type;
        }

        @Override
        public Object suggested(Object bucket) {
            return ((Bucket) bucket).suggested;
        }

        @Override
        public Object label(Object bucket) {
            return ((Bucket) bucket).label;
        }

        @Override
        public Object copy(List<Object> kept) {
            return Collections.unmodifiableList(new Copy(kept));
        }

        @Override
        public Object seen(Object bucket) {
            return ((Bucket) bucket).seen;
        }

        @Override
        public Object ownerType(Object bucket) {
            return ((Bucket) bucket).owner;
        }
    };

    /** The filter with the stand-in stubs: the very list when nothing goes, otherwise the copy. */
    public static Object keptBuckets(List<?> buckets) {
        return SuggestedStories.keptBuckets(buckets, STAND_IN);
    }

    /**
     * Says whether Hide suggested and promoted posts, the People you may know switch's patch, is in
     * the build, or hands the answer back to SettingsStatus with null.
     */
    public static void peopleYouMayKnowInBuild(Boolean inBuild) {
        SuggestedStories.peopleYouMayKnowInBuildForTests = inBuild;
    }

    /**
     * A tray of a friend's story, a suggested one and one labelled SUGGESTED, filtered the way the
     * patched tray data does. True when both suggestions came out, which is the switch changing
     * what Facebook would have drawn.
     */
    public static boolean hidesSuggestions() {
        Bucket friend = new Bucket(false, Label.NEWFRIEND);
        List<Bucket> tray = Arrays.asList(friend, new Bucket(true, null), new Bucket(false, Label.SUGGESTED));
        Object kept = keptBuckets(tray);
        return kept != tray && Collections.singletonList(friend).equals(kept);
    }

    /**
     * A tray of a friend's story and the two kinds of People you may know card, in a build with
     * that switch's patch. True when both cards came out.
     */
    public static boolean hidesPeopleYouMayKnow() {
        Boolean before = SuggestedStories.peopleYouMayKnowInBuildForTests;
        SuggestedStories.peopleYouMayKnowInBuildForTests = true;
        try {
            Bucket friend = new Bucket(false, Label.NEWFRIEND);
            List<Bucket> tray = Arrays.asList(Bucket.of(Type.PYMK_STORY), friend, Bucket.of(Type.PYMK_PROFILE_FORWARD_STORY));
            Object kept = keptBuckets(tray);
            return kept != tray && Collections.singletonList(friend).equals(kept);
        } finally {
            SuggestedStories.peopleYouMayKnowInBuildForTests = before;
        }
    }

    /** A tray of a friend's story and the "Find friends from contacts" card. True when the card came out. */
    public static boolean hidesContactImportCard() {
        Bucket friend = new Bucket(false, null);
        List<Bucket> tray = Arrays.asList(Bucket.of(Type.CONTACT_IMPORTER_STORY), friend);
        Object kept = keptBuckets(tray);
        return kept != tray && Collections.singletonList(friend).equals(kept);
    }

    /** A tray of a friend's story and a followed Page's. True when the Page's came out (#313). */
    public static boolean hidesPageStories() {
        Bucket friend = new Bucket(false, null).ownedBy("User");
        List<Bucket> tray = Arrays.asList(friend, new Bucket(false, null).ownedBy(SuggestedStories.PAGE_OWNER));
        Object kept = keptBuckets(tray);
        return kept != tray && Collections.singletonList(friend).equals(kept);
    }

    /** A tray of a story not watched yet and one watched to the end. True when the watched one came out (#317). */
    public static boolean hidesSeenStories() {
        Bucket fresh = new Bucket(false, null).ownedBy("User");
        List<Bucket> tray = Arrays.asList(fresh, new Bucket(false, null).ownedBy("User").watched());
        Object kept = keptBuckets(tray);
        return kept != tray && Collections.singletonList(fresh).equals(kept);
    }

    /** A tray of a story not watched yet and one watched to the end. True when only the watched one stayed (#340). */
    public static boolean keepsOnlyWatchedStories() {
        Bucket watched = new Bucket(false, null).ownedBy("User").watched();
        List<Bucket> tray = Arrays.asList(new Bucket(false, null).ownedBy("User"), watched);
        Object kept = keptBuckets(tray);
        return kept != tray && Collections.singletonList(watched).equals(kept);
    }
}
