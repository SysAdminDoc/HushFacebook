/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.stories.SuggestedStoriesForTests.Type;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Hide seen stories with View stories anonymously on (#218): the servers never hear of those views,
 * so each tray they send has the watched buckets' seen flag false again. A bucket watched to the end
 * on this phone after its newest story was posted leaves the tray anyway, found by its id and its
 * expiration_time. One watched partway, one with a story posted since, one past what's kept, and
 * every bucket with either switch off or a pause stay as Facebook sent them.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SeenStoriesWatchedHereTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final long HOUR_MS = 60L * 60 * 1000;

    /** Facebook's story bucket, the one the viewer plays: its id and its cards, oldest first. */
    private static final class Played {
        final String id;
        final List<String> cards;

        Played(String id, String... cards) {
            this.id = id;
            this.cards = Arrays.asList(cards);
        }
    }

    /** A bucket of the tray's list: its id, its expiration_time in seconds and its seen flag. */
    private static final class Tree {
        final Object id;
        final Object expires;
        final boolean seen;

        Tree(Object id, Object expires, boolean seen) {
            this.id = id;
            this.expires = expires;
            this.seen = seen;
        }

        @Override
        public String toString() {
            return "Tree(" + id + ", " + expires + ", " + seen + ")";
        }
    }

    private static final StorySeen.Stories PLAYED = new StorySeen.Stories() {
        @Override public String bucketId(Object bucket) { return ((Played) bucket).id; }
        @Override public List<?> cards(Object bucket) { return ((Played) bucket).cards; }
        @Override public String storyId(Object card) { return (String) card; }
        @Override public boolean hasUnwatched(Object bucket) { return true; }
        @Override public void publishWatched(Object bucket, String bucketId) { }
    };

    /** The tray's stubs as the patch fills them, a friend's story each, with the two new reads. */
    private static final SuggestedStories.Buckets TREES = new SuggestedStories.Buckets() {
        @Override public boolean isBucket(Object item) { return item instanceof Tree; }
        @Override public Object type(Object bucket) { return Type.STORY; }
        @Override public Object suggested(Object bucket) { return false; }
        @Override public Object label(Object bucket) { return null; }
        @Override public Object copy(List<Object> kept) { return Collections.unmodifiableList(new ArrayList<>(kept)); }
        @Override public Object seen(Object bucket) { return ((Tree) bucket).seen; }
        @Override public Object ownerType(Object bucket) { return "User"; }
        @Override public Object id(Object bucket) { return ((Tree) bucket).id; }
        @Override public Object expires(Object bucket) { return ((Tree) bucket).expires; }
    };

    /** The same stubs in a build where the patch couldn't fill the id and expiration ones. */
    private static final SuggestedStories.Buckets UNFILLED = new SuggestedStories.Buckets() {
        @Override public boolean isBucket(Object item) { return TREES.isBucket(item); }
        @Override public Object type(Object bucket) { return TREES.type(bucket); }
        @Override public Object suggested(Object bucket) { return TREES.suggested(bucket); }
        @Override public Object label(Object bucket) { return TREES.label(bucket); }
        @Override public Object copy(List<Object> kept) { return TREES.copy(kept); }
        @Override public Object seen(Object bucket) { return TREES.seen(bucket); }
        @Override public Object ownerType(Object bucket) { return TREES.ownerType(bucket); }
    };

    private final Object helper = new Object();

    @Before
    public void start() {
        RuntimeEnvironment.getApplication().getSharedPreferences(SeenBuckets.FILE, Context.MODE_PRIVATE).edit().clear().commit();
        RuntimeEnvironment.getApplication().getSharedPreferences(SeenBuckets.CARDS_FILE, Context.MODE_PRIVATE).edit().clear().commit();
        StorySeen.useForTests(PLAYED);
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
        Settings.HIDE_SEEN_STORIES.save(true);
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    @After
    public void restore() {
        StorySeen.useForTests(null);
        PauseForTests.resume();
        Settings.VIEW_STORIES_ANONYMOUSLY.resetToDefault();
        Settings.HIDE_SEEN_STORIES.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    /** A tray bucket whose newest story was posted [hoursAgo] hours ago, so it expires a day after that. */
    private static Tree postedHoursAgo(String id, double hoursAgo) {
        long posted = System.currentTimeMillis() - (long) (hoursAgo * HOUR_MS);
        return new Tree(id, (posted + StorySeen.STORY_LIFE_MS) / 1000, false);
    }

    /** Plays [cards] of [bucket] in the viewer, with the views held back. */
    private void watch(Played bucket, String... cards) {
        for (String card : cards) StorySeen.viewed(bucket, card, helper, false);
    }

    private static Object kept(List<Tree> tray) {
        return SuggestedStories.keptBuckets(tray, TREES);
    }

    private static String line(String route, List<String> lines) {
        for (String line : lines) {
            if (line.startsWith(route + ":")) return line;
        }
        return null;
    }

    @Test
    public void aBucketWatchedToTheEndHereLeavesEachTrayAfterward() {
        watch(new Played("b1", "s1", "s2", "s3"), "s1", "s2", "s3");
        Tree watched = postedHoursAgo("b1", 3);
        Tree fresh = postedHoursAgo("b2", 3);
        List<Tree> tray = Arrays.asList(watched, fresh);

        assertEquals("the watched bucket stayed", Collections.singletonList(fresh), kept(tray));
        // A refresh or a restart sends it again as unwatched, and it goes again.
        assertEquals(Collections.singletonList(fresh), kept(Arrays.asList(postedHoursAgo("b1", 3), fresh)));
        String counters = line(SuggestedStories.ROUTE, FeedFilterCounters.report());
        assertTrue(counters, counters.contains("Removed: " + SuggestedStories.SEEN + " 2."));
        String status = line(FamilyNames.STORY_SEEN, HookStatus.report());
        assertTrue(status, status.contains(StorySeen.TRAY_WATCHED + " 2"));
        assertTrue(line(FamilyNames.SUGGESTED_STORIES, HookStatus.report()),
                line(FamilyNames.SUGGESTED_STORIES, HookStatus.report()).endsWith(" 0 missing"));
    }

    /** With Show only watched stories on (#340), a bucket watched to the end here is one it keeps. */
    @Test
    public void aBucketWatchedToTheEndHereStaysWithOnlyWatchedOn() {
        watch(new Played("b1", "s1", "s2"), "s1", "s2");
        watch(new Played("b2", "s1", "s2"), "s1");
        Tree watched = postedHoursAgo("b1", 3);
        List<Tree> tray = Arrays.asList(postedHoursAgo("b2", 3), watched, postedHoursAgo("b3", 3));
        Settings.SHOW_ONLY_WATCHED_STORIES.save(true);
        try {
            assertEquals(Collections.singletonList(watched), kept(tray));
            String counters = line(SuggestedStories.ROUTE, FeedFilterCounters.report());
            assertTrue(counters, counters.contains("Removed: " + SuggestedStories.UNWATCHED + " 2."));
        } finally {
            Settings.SHOW_ONLY_WATCHED_STORIES.resetToDefault();
        }
    }

    @Test
    public void aBucketWatchedPartwayStays() {
        watch(new Played("b1", "s1", "s2", "s3"), "s1", "s2");
        List<Tree> tray = Collections.singletonList(postedHoursAgo("b1", 3));
        assertSame("a bucket with a story not watched left", tray, kept(tray));
    }

    @Test
    public void aStoryPostedAfterTheWatchBringsTheBucketBack() {
        watch(new Played("b1", "s1"), "s1");
        // The friend posted again ten minutes from now, after the watch.
        List<Tree> later = Collections.singletonList(postedHoursAgo("b1", -10.0 / 60));
        assertSame("a bucket with a new story left", later, kept(later));
        // A story posted a moment before the watch ended may not have been in the viewer.
        List<Tree> close = Collections.singletonList(postedHoursAgo("b1", 0.5 / 60));
        assertSame("a story posted just before the watch left", close, kept(close));
    }

    @Test
    public void aWatchPastWhatIsKeptStays() {
        long longAgo = System.currentTimeMillis() - SeenBuckets.KEEP_MS - HOUR_MS;
        RuntimeEnvironment.getApplication().getSharedPreferences(SeenBuckets.FILE, Context.MODE_PRIVATE).edit()
                .putString("b1", SeenBuckets.encode("s1", longAgo)).putString("b2", SeenBuckets.encode("s9", System.currentTimeMillis()))
                .commit();
        StorySeen.useForTests(PLAYED);
        List<Tree> tray = Collections.singletonList(postedHoursAgo("b1", 60));
        assertSame("a watch past the 48 hours kept counted", tray, kept(tray));
    }

    @Test
    public void aBucketItCannotIdentifyStays() {
        watch(new Played("b1", "s1"), "s1");
        Tree noId = new Tree(null, postedHoursAgo("b1", 3).expires, false);
        Tree noTime = new Tree("b1", null, false);
        Tree zeroTime = new Tree("b1", 0L, false);
        List<Tree> tray = Arrays.asList(noId, noTime, zeroTime);
        assertSame(tray, kept(tray));
    }

    @Test
    public void eitherSwitchOffOrAPauseKeepsFacebooksTray() {
        watch(new Played("b1", "s1"), "s1");
        List<Tree> tray = Collections.singletonList(postedHoursAgo("b1", 3));

        Settings.HIDE_SEEN_STORIES.save(false);
        assertSame("Hide seen stories off took it out", tray, kept(tray));
        Settings.HIDE_SEEN_STORIES.save(true);

        Settings.VIEW_STORIES_ANONYMOUSLY.save(false);
        assertSame("with views sent, the phone's record took it out", tray, kept(tray));
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);

        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertSame("paused by " + reason + ", it left", tray, kept(tray));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertSame("it left before the settings were ready", tray, kept(tray)));
        String counters = line(SuggestedStories.ROUTE, FeedFilterCounters.report());
        assertFalse(counters, counters != null && counters.contains("Removed"));

        assertEquals("on again, it goes", Collections.emptyList(), kept(tray));
    }

    @Test
    public void theServersFlagStillCountsWithNothingKeptHere() {
        Tree seenThere = new Tree("b7", postedHoursAgo("b7", 3).expires, true);
        Tree fresh = postedHoursAgo("b8", 3);
        assertEquals(Collections.singletonList(fresh), kept(Arrays.asList(seenThere, fresh)));
        assertTrue(line(FamilyNames.SUGGESTED_STORIES, HookStatus.report()),
                line(FamilyNames.SUGGESTED_STORIES, HookStatus.report()).endsWith(" 0 missing"));
    }

    /** A build without the id or expiration stub: Hook status says so once something is kept, and the bucket stays. */
    @Test
    public void unfilledStubsAreReportedAndTheBucketStays() {
        List<Tree> tray = Collections.singletonList(postedHoursAgo("b1", 3));
        assertSame(tray, SuggestedStories.keptBuckets(tray, UNFILLED));
        assertTrue("nothing kept here, yet the stubs were asked",
                line(FamilyNames.SUGGESTED_STORIES, HookStatus.report()).endsWith(" 0 missing"));

        watch(new Played("b1", "s1"), "s1");
        assertSame(tray, SuggestedStories.keptBuckets(tray, UNFILLED));
        String status = line(FamilyNames.SUGGESTED_STORIES, HookStatus.report());
        assertTrue(status, status.endsWith(" 1 missing. First missing: method tray bucket#" + SuggestedStories.ID_FIELD));
    }
}
