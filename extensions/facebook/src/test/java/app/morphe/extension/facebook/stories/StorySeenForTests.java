/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.facebook.media.RecentlyWatchedForTests;

/** Drives View stories anonymously and its Mark as seen button the way the patched sender and seen helper do. */
public final class StorySeenForTests {
    /** Stands in for FbUserSession: like Facebook's, it writes its user ID first. */
    public static final class Session implements Parcelable {
        final String user;

        public Session(String user) {
            this.user = user;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel parcel, int flags) {
            parcel.writeString(user);
        }
    }

    public static final Session ACCOUNT = new Session("100");

    private StorySeenForTests() {
    }

    public static Set<String> cards(String... ids) {
        return new LinkedHashSet<>(Arrays.asList(ids));
    }

    /** What the hook answers the sender for [ids] on [session]'s account. */
    public static Set<?> send(Object session, Set<?> ids) {
        return StorySeen.toSend(new Object(), new Object(), session, "first", "second", "third", null, ids, false);
    }

    /** Whether a batch of two cards is held back whole. Forgets what it held. */
    public static boolean holdsABatch() {
        try {
            return send(ACCOUNT, cards("a", "b")) == null;
        } finally {
            reset();
        }
    }

    /** Whether, with a card of the batch marked, only that card goes out. Forgets the mark. */
    public static boolean sendsOnlyTheMarked() {
        try {
            StorySeen.MARKS.toggle(ACCOUNT.user, "marked");
            Set<?> sent = send(ACCOUNT, cards("marked", "other"));
            return sent != null && sent.size() == 1 && sent.contains("marked");
        } finally {
            reset();
        }
    }

    /** Whether the seen helper's card hook and the controllers' active card hook point the button at the card. Forgets the card. */
    public static boolean pointsTheButton() {
        try {
            StorySeenButton.cardIds = card -> (String) card;
            StorySeenButton.onCard(ACCOUNT, null, "card");
            boolean counted = StorySeenButton.shown() != null;
            StorySeenButton.onActive("card");
            return counted && StorySeenButton.shown() != null;
        } finally {
            reset();
        }
    }

    /**
     * Stands in for Facebook's story classes: every bucket is "b1" with no cards, so View stories
     * anonymously keeps nothing of it, and a card is its own id.
     */
    static final StorySeen.Stories ONE_BUCKET = new StorySeen.Stories() {
        @Override public String bucketId(Object bucket) { return "b1"; }
        @Override public List<?> cards(Object bucket) { return Collections.emptyList(); }
        @Override public String storyId(Object card) { return (String) card; }
        @Override public boolean hasUnwatched(Object bucket) { return false; }
        @Override public void publishWatched(Object bucket, String bucketId) { }
    };

    /**
     * For the pause probe: the seen helper queues a story card you viewed. True when it went on the
     * Recently watched list, which a paused Hushfacebook must never do.
     */
    public static boolean keepsAViewedStory() {
        RecentlyWatchedForTests.install();
        StorySeen.useForTests(ONE_BUCKET);
        try {
            StorySeen.viewed(new Object(), "s1", new Object(), null);
            return !RecentlyWatchedForTests.entries().isEmpty();
        } finally {
            StorySeen.useForTests(null);
            RecentlyWatchedForTests.forget();
        }
    }

    /** Forgets every mark, the card on screen and the button. */
    public static void reset() {
        StorySeen.MARKS.clear();
        StorySeenButton.resetForTests();
    }
}
