/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Marking watched buckets on the phone (#61): a bucket watched to its newest story is published as
 * watched when its batch of views is held back, and again each time Facebook builds it with the
 * same newest story, until a new one arrives, a few times a window at most. A peek, a story short of the newest, the switch off
 * or a pause marks nothing, and the switch saved off forgets what was kept. Each card watched is
 * kept too, so a bucket watched partway opens past those cards after a restart (#218).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class StorySeenMarkingTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Facebook's bucket, as far as the code here sees it. */
    private static final class Bucket {
        final String id;
        final List<String> cards;
        boolean unwatched = true;

        Bucket(String id, String... cards) {
            this.id = id;
            this.cards = Arrays.asList(cards);
        }
    }

    /**
     * Facebook's classes: a card is its id, and publishing a bucket marks it watched, as the
     * consistency publish does. A bucket's answer asks the hook first, as the patched one does.
     */
    private final List<String> published = new ArrayList<>();
    /** Cards with Facebook's seen flag set, which its own start check turns down. */
    private final Set<String> flagged = new HashSet<>();
    /** Cards whose media hasn't loaded, which the check turns down too. */
    private final Set<String> noMedia = new HashSet<>();
    /** The stand-in buckets the viewer shows while a real one loads. */
    private final Set<Object> loading = new HashSet<>();
    private final StorySeen.Stories stories = new StorySeen.Stories() {
        @Override public String bucketId(Object bucket) { return ((Bucket) bucket).id; }
        @Override public List<?> cards(Object bucket) { return ((Bucket) bucket).cards; }
        @Override public String storyId(Object card) { return (String) card; }
        @Override public boolean hasMedia(Object card) { return !noMedia.contains(card); }
        @Override public boolean seenFlag(Object card) { return flagged.contains(card); }
        @Override public boolean isLoading(Object bucket) { return loading.contains(bucket); }
        @Override public boolean hasUnwatched(Object bucket) {
            return !StorySeen.readsWatched(bucket) && ((Bucket) bucket).unwatched;
        }
        @Override public void publishWatched(Object bucket, String bucketId) {
            published.add(bucketId);
            ((Bucket) bucket).unwatched = false;
        }
    };

    private final Object helper = new Object();

    @Before
    public void start() {
        RuntimeEnvironment.getApplication().getSharedPreferences(SeenBuckets.FILE, Context.MODE_PRIVATE).edit().clear().commit();
        RuntimeEnvironment.getApplication().getSharedPreferences(SeenBuckets.CARDS_FILE, Context.MODE_PRIVATE).edit().clear().commit();
        StorySeen.useForTests(stories);
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
    }

    @After
    public void restore() {
        StorySeen.useForTests(null);
        PauseForTests.resume();
        Settings.VIEW_STORIES_ANONYMOUSLY.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** Past a bucket's window of marks. */
    private static void later() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(StorySeen.REMARK_MS + 1));
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.STORY_SEEN + ":")) return line;
        }
        return null;
    }

    /** The patched sender's hook, handed a batch of one card: whether the batch was held back. */
    private static boolean heldBack() {
        return StorySeen.toSend(new Object(), new Object(), StorySeenForTests.ACCOUNT, "a", "b", "c", null,
                StorySeenForTests.cards("x"), false) == null;
    }

    /** Facebook built [bucket] again, the way it does each time the tray loads. */
    private static void built(Bucket bucket) {
        StorySeen.bucketMade(bucket);
        idle();
    }

    @Test
    public void aBucketWatchedToItsNewestStoryIsMarkedWatchedWhenItsViewsAreHeldBack() {
        Bucket friend = new Bucket("b1", "s1", "s2");
        StorySeen.viewed(friend, "s1", helper, false);
        StorySeen.viewed(friend, "s2", helper, false);
        assertTrue(published.isEmpty());
        assertTrue(heldBack());
        idle();
        assertEquals(Arrays.asList("b1"), published);
        assertFalse(friend.unwatched);
        assertTrue(statusLine(), statusLine().contains(StorySeen.MARKED));

        // The next batch held back doesn't mark it again.
        assertTrue(heldBack());
        idle();
        assertEquals(1, published.size());
    }

    @Test
    public void aBucketBuiltAgainIsMarkedAgainUntilANewStoryArrives() {
        Bucket friend = new Bucket("b1", "s1", "s2");
        StorySeen.viewed(friend, "s2", helper, false);
        heldBack();
        idle();
        assertEquals(1, published.size());

        // Facebook builds the bucket again from its servers, which still send it as unwatched. A
        // copy built moments after the mark carries that too and gets its own (#165: a cold start
        // builds the tray four times in under a second, and the last one came after the mark).
        Bucket reloaded = new Bucket("b1", "s1", "s2");
        built(reloaded);
        assertEquals(Arrays.asList("b1", "b1"), published);
        assertFalse(reloaded.unwatched);

        // Facebook started again: what was watched is read back from the file.
        StorySeen.useForTests(stories);
        built(new Bucket("b1", "s1", "s2"));
        assertEquals(3, published.size());

        // A new story makes it new again, and it stays so.
        later();
        Bucket newer = new Bucket("b1", "s1", "s2", "s3");
        built(newer);
        assertEquals(3, published.size());
        assertTrue(newer.unwatched);

        // A bucket Facebook already sends as watched isn't published over.
        later();
        Bucket seen = new Bucket("b1", "s1", "s2");
        seen.unwatched = false;
        built(seen);
        assertEquals(3, published.size());
    }

    @Test
    public void everyCopyOfATrayBuiltADozenTimesASecondIsMarked() {
        // 583's tray loader builds every bucket about a dozen times in its first second and again
        // with each refresh: 40 copies in a session's first minutes on a reporter's phone. The old
        // ceiling of eight a minute left the ninth copy on blue (#165, 0.15.0).
        Bucket friend = new Bucket("b1", "s1");
        StorySeen.viewed(friend, "s1", helper, false);
        heldBack();
        idle();
        for (int i = 0; i < 40; i++) built(new Bucket("b1", "s1"));
        assertEquals(41, published.size());
    }

    @Test
    public void aBucketBuiltUnwatchedWithoutEndIsCutOffFarAboveWhatATrayBuilds() {
        Bucket friend = new Bucket("b1", "s1");
        StorySeen.viewed(friend, "s1", helper, false);
        heldBack();
        idle();
        assertTrue(StorySeen.MARKS_PER_WINDOW >= 200);
        for (int i = 0; i < StorySeen.MARKS_PER_WINDOW * 3; i++) built(new Bucket("b1", "s1"));
        assertEquals(StorySeen.MARKS_PER_WINDOW, published.size());

        // Another bucket has its own window.
        StorySeen.viewed(new Bucket("b2", "t1"), "t1", helper, false);
        heldBack();
        idle();
        assertEquals(StorySeen.MARKS_PER_WINDOW + 1, published.size());

        later();
        built(new Bucket("b1", "s1"));
        assertEquals(StorySeen.MARKS_PER_WINDOW + 2, published.size());
    }

    @Test
    public void aPeekOrAStoryShortOfTheNewestMarksNothing() {
        Bucket friend = new Bucket("b1", "s1", "s2");
        StorySeen.viewed(friend, "s1", helper, false);
        StorySeen.viewed(friend, "s2", helper, true);
        StorySeen.viewed(new Bucket("b2"), "s1", helper, false);
        heldBack();
        idle();
        built(new Bucket("b1", "s1", "s2"));
        assertTrue(published.isEmpty());
        assertTrue(friend.unwatched);
    }

    @Test
    public void offOrPausedNothingIsMarked() {
        Settings.VIEW_STORIES_ANONYMOUSLY.save(false);
        Bucket friend = new Bucket("b1", "s1");
        StorySeen.viewed(friend, "s1", helper, false);
        assertFalse(heldBack());
        idle();
        built(new Bucket("b1", "s1"));
        assertTrue(published.isEmpty());

        // Watched with the switch on, then paused: the views go out, and nothing is marked here,
        // since Facebook marks them itself once its servers take them.
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
        StorySeen.viewed(friend, "s1", helper, false);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(heldBack());
        idle();
        built(new Bucket("b1", "s1"));
        assertTrue(published.isEmpty());

        // A pause keeps what was watched, for when it ends.
        PauseForTests.resume();
        built(new Bucket("b1", "s1"));
        assertEquals(Arrays.asList("b1"), published);
    }

    @Test
    public void theSwitchSavedOffForgetsWhatWasWatched() {
        StorySeen.viewed(new Bucket("b1", "s1"), "s1", helper, false);
        Settings.VIEW_STORIES_ANONYMOUSLY.save(false);
        built(new Bucket("b1", "s1"));
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
        built(new Bucket("b1", "s1"));
        assertTrue(published.isEmpty());
        assertTrue(RuntimeEnvironment.getApplication().getSharedPreferences(SeenBuckets.FILE, Context.MODE_PRIVATE)
                .getAll().isEmpty());
    }

    @Test
    public void aBucketKeptAsWatchedReadsWatchedTheFirstTimeTheTrayAsks() {
        Bucket friend = new Bucket("b1", "s1", "s2");
        StorySeen.viewed(friend, "s1", helper, false);
        StorySeen.viewed(friend, "s2", helper, false);
        heldBack();
        idle();

        // #165: Facebook started again and draws the tray before building a single bucket.
        StorySeen.useForTests(stories);
        HookStatus.clear();
        assertFalse("the ring came back blue", stories.hasUnwatched(new Bucket("b1", "s1", "s2")));
        assertTrue(statusLine(), statusLine().contains(StorySeen.READ_WATCHED));

        // Built, it's still published, since only Facebook's own answer counts for that.
        built(new Bucket("b1", "s1", "s2"));
        assertEquals(2, published.size());

        // A new story, a bucket never watched here, or one with no cards read as Facebook sends them.
        assertTrue(stories.hasUnwatched(new Bucket("b1", "s1", "s2", "s3")));
        assertTrue(stories.hasUnwatched(new Bucket("b2", "s2")));
        assertTrue(stories.hasUnwatched(new Bucket("b1")));
        Bucket sentWatched = new Bucket("b2", "t1");
        sentWatched.unwatched = false;
        assertFalse(stories.hasUnwatched(sentWatched));
        assertFalse(StorySeen.readsWatched(null));
    }

    @Test
    public void aLightweightBucketReadsWatchedWhenItsOneCardWasWatchedHere() {
        // A cold start's lightweight bucket holds one card, its preview story, which needn't be
        // the newest the bucket had when it was watched.
        Bucket friend = new Bucket("b1", "s1", "s2");
        StorySeen.viewed(friend, "s1", helper, false);
        StorySeen.viewed(friend, "s2", helper, false);
        StorySeen.useForTests(stories);
        assertFalse(stories.hasUnwatched(new Bucket("b1", "s2")));
        assertFalse(stories.hasUnwatched(new Bucket("b1", "s1")));

        // Its placeholder card, or a story not watched here, leaves Facebook's answer.
        assertTrue(stories.hasUnwatched(new Bucket("b1", "lightweight_loading_card_in_bucket_b1")));
        assertTrue(stories.hasUnwatched(new Bucket("b1", "s3")));

        // Watched partway isn't kept as watched at all.
        Bucket other = new Bucket("b2", "t1", "t2");
        StorySeen.viewed(other, "t1", helper, false);
        assertTrue(stories.hasUnwatched(new Bucket("b2", "t1")));
    }

    @Test
    public void offOrPausedABucketReadsAsFacebookSendsIt() {
        StorySeen.viewed(new Bucket("b1", "s1"), "s1", helper, false);
        Settings.VIEW_STORIES_ANONYMOUSLY.save(false);
        assertTrue(stories.hasUnwatched(new Bucket("b1", "s1")));
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertTrue(stories.hasUnwatched(new Bucket("b1", "s1")));
        PauseForTests.resume();
        assertFalse(stories.hasUnwatched(new Bucket("b1", "s1")));
    }

    @Test
    public void aFailureReadingABucketLeavesFacebooksAnswerAndIsReported() {
        StorySeen.viewed(new Bucket("b1", "s1"), "s1", helper, false);
        HookStatus.clear();
        StorySeen.useForTests(new StorySeen.Stories() {
            @Override public String bucketId(Object bucket) { return "b1"; }
            @Override public List<?> cards(Object bucket) { throw new IllegalStateException("renamed"); }
            @Override public String storyId(Object card) { return (String) card; }
            @Override public boolean hasUnwatched(Object bucket) { return true; }
            @Override public void publishWatched(Object bucket, String bucketId) { }
        });
        assertFalse(StorySeen.readsWatched(new Bucket("b1", "s1")));
        assertTrue(statusLine(), statusLine().contains("threw"));
    }

    @Test
    public void aBucketWatchedPartwayOpensPastTheCardsWatchedHereAfterARestart() {
        // #218: Facebook's start check passes over each card the hook says was watched here.
        Bucket friend = new Bucket("b1", "s1", "s2", "s3");
        StorySeen.viewed(friend, "s1", helper, false);
        StorySeen.viewed(friend, "s2", helper, false);
        heldBack();
        idle();
        assertTrue("watched partway isn't watched to the end", published.isEmpty());

        // Facebook started again: the cards are read back from their own file.
        StorySeen.useForTests(stories);
        assertTrue(StorySeen.watchedHere("s1"));
        assertTrue(StorySeen.watchedHere("s2"));
        assertFalse(StorySeen.watchedHere("s3"));
        assertFalse(StorySeen.watchedHere(null));
        assertTrue(statusLine(), statusLine().contains(StorySeen.PASSED_OVER));

        // A card that arrives later opens the bucket at it, once the rest were all watched.
        StorySeen.viewed(friend, "s3", helper, false);
        assertTrue(StorySeen.watchedHere("s3"));
        assertFalse(StorySeen.watchedHere("s4"));
    }

    @Test
    public void aPeekTheSwitchOffOrAPauseLeavesFacebooksOwnStart() {
        Bucket friend = new Bucket("b1", "s1", "s2", "s3");
        StorySeen.viewed(friend, "s1", helper, true);
        assertFalse("a peek counted", StorySeen.watchedHere("s1"));

        Settings.VIEW_STORIES_ANONYMOUSLY.save(false);
        StorySeen.viewed(friend, "s2", helper, false);
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
        assertFalse("a view Facebook heard about was kept", StorySeen.watchedHere("s2"));

        StorySeen.viewed(friend, "s3", helper, false);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(StorySeen.watchedHere("s3"));
        Settings.VIEW_STORIES_ANONYMOUSLY.save(false);
        PauseForTests.resume();
        assertFalse(StorySeen.watchedHere("s3"));
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
        assertTrue("a pause or the switch flipped back forgot the card", StorySeen.watchedHere("s3"));
    }

    @Test
    public void theSwitchSavedOffForgetsTheCardsToo() {
        StorySeen.viewed(new Bucket("b1", "s1", "s2"), "s1", helper, false);
        Settings.VIEW_STORIES_ANONYMOUSLY.save(false);
        built(new Bucket("b1", "s1", "s2"));
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
        assertFalse(StorySeen.watchedHere("s1"));
        assertTrue(RuntimeEnvironment.getApplication().getSharedPreferences(SeenBuckets.CARDS_FILE, Context.MODE_PRIVATE)
                .getAll().isEmpty());
    }

    @Test
    public void aFailureInFacebooksClassesSendsTheViewsAndIsReported() {
        StorySeen.useForTests(new StorySeen.Stories() {
            @Override public String bucketId(Object bucket) { throw new IllegalStateException("renamed"); }
            @Override public List<?> cards(Object bucket) { throw new IllegalStateException("renamed"); }
            @Override public String storyId(Object card) { throw new IllegalStateException("renamed"); }
            @Override public boolean hasUnwatched(Object bucket) { throw new IllegalStateException("renamed"); }
            @Override public void publishWatched(Object bucket, String bucketId) { throw new IllegalStateException("renamed"); }
        });
        StorySeen.viewed(new Bucket("b1", "s1"), "s1", helper, false);
        StorySeen.bucketMade(new Bucket("b1", "s1"));
        idle();
        assertTrue("the views stopped going out", heldBack());
        assertTrue(statusLine(), statusLine().contains("threw"));
    }

    @Test
    public void aFailureInTheStartCheckLeavesFacebooksOwnStartAndIsReported() {
        StorySeen.viewed(new Bucket("b1", "s1", "s2"), "s1", helper, false);
        assertTrue(StorySeen.watchedHere("s1"));
        HookStatus.clear();
        StorySeen.useForTests(new StorySeen.Stories() {
            @Override public String bucketId(Object bucket) { return "b1"; }
            @Override public List<?> cards(Object bucket) { return Arrays.asList("s1", "s2"); }
            @Override public String storyId(Object card) { throw new IllegalStateException("renamed"); }
            @Override public boolean hasUnwatched(Object bucket) { return true; }
            @Override public void publishWatched(Object bucket, String bucketId) { }
        });
        assertFalse("Facebook's own start was overruled", StorySeen.watchedHere("s1"));
        assertTrue(statusLine(), statusLine().contains("threw"));
    }

    private boolean openedLater() {
        String line = statusLine();
        return line != null && line.contains(StorySeen.OPENED_LATER);
    }

    @Test
    public void aBucketOpenedNamingNoCardOrFacebooksStartOpensAtTheFirstStoryNotWatchedHere() {
        // #218: a Page's picture opens its bucket naming no card, which skips the start check, and
        // the tray names the card Facebook starts at, which wins over it.
        Bucket page = new Bucket("b1", "s1", "s2", "s3", "s4");
        StorySeen.viewed(page, "s1", helper, false);
        StorySeen.viewed(page, "s2", helper, false);
        StorySeen.viewed(page, "s3", helper, false);
        StorySeen.useForTests(stories);
        assertEquals("s4", StorySeen.startCard(page, null));
        assertEquals("s4", StorySeen.startCard(page, ""));
        assertEquals("s4", StorySeen.startCard(page, "s1"));
        assertTrue(statusLine(), openedLater());
    }

    @Test
    public void aCardPickedByNameAFreshBucketOrOneWatchedThroughKeepsFacebooksStart() {
        Bucket page = new Bucket("b1", "s1", "s2", "s3");
        // Negative controls: nothing watched here yet.
        assertNull(StorySeen.startCard(page, null));
        assertEquals("s1", StorySeen.startCard(page, "s1"));
        StorySeen.viewed(page, "s1", helper, false);
        // A card the launch picked that isn't Facebook's start stands, watched here or not.
        assertEquals("s3", StorySeen.startCard(page, "s3"));
        assertEquals("s2", StorySeen.startCard(page, "s2"));
        assertEquals("a launch naming a card no longer in the bucket", "gone", StorySeen.startCard(page, "gone"));
        assertFalse("a pick was counted as moved", openedLater());
        // Every card watched here: Facebook's own start and its watched-bucket answer stand.
        StorySeen.viewed(page, "s2", helper, false);
        StorySeen.viewed(page, "s3", helper, false);
        assertNull(StorySeen.startCard(page, null));
        assertEquals("s1", StorySeen.startCard(page, "s1"));
        assertFalse(openedLater());
        assertNull("no bucket", StorySeen.startCard(null, null));
    }

    @Test
    public void facebooksOwnCheckSaysWhichCardItStartsAt() {
        // A first card Facebook's check turns down isn't its start, so naming the second one is.
        Bucket page = new Bucket("b1", "s1", "s2", "s3");
        flagged.add("s1");
        StorySeen.viewed(page, "s2", helper, false);
        assertEquals("s3", StorySeen.startCard(page, "s2"));
        assertEquals("s3", StorySeen.startCard(page, null));
        // Naming the card the check turns down is a pick of the launch's own.
        assertEquals("s1", StorySeen.startCard(page, "s1"));
        // A card after the start that the check turns down is passed over too.
        flagged.add("s3");
        assertEquals("s2", StorySeen.startCard(page, "s2"));
        assertNull(StorySeen.startCard(page, null));
    }

    @Test
    public void aCardPastTheStartWhoseMediaHasntLoadedIsStillWhereTheBucketOpens() {
        // #218 on a phone: a Page opened from its picture had media for its first three cards
        // only, so with those watched here the bucket still opened at the first one.
        Bucket page = new Bucket("b1", "s1", "s2", "s3", "s4", "s5");
        noMedia.add("s4");
        noMedia.add("s5");
        StorySeen.viewed(page, "s1", helper, false);
        StorySeen.viewed(page, "s2", helper, false);
        StorySeen.viewed(page, "s3", helper, false);
        assertEquals("s4", StorySeen.startCard(page, null));
        assertEquals("s4", StorySeen.startCard(page, "s1"));
        // A seen flag past the start still passes a card over, media or not.
        flagged.add("s4");
        assertEquals("s5", StorySeen.startCard(page, null));
        // Facebook's start itself needs its media: a first card without it isn't the start.
        noMedia.add("s1");
        assertEquals("a pick of the launch's own", "s1", StorySeen.startCard(page, "s1"));
        assertEquals("s5", StorySeen.startCard(page, "s2"));
        assertTrue(statusLine(), openedLater());
    }

    @Test
    public void aPageOpenedFromItsPictureOpensPastTheStoriesWatchedHereOnceItsBucketLoads() {
        // #218 on a phone: the viewer shows a loading bucket first, with one stand-in card for the
        // Page's first story (the same id), and the real bucket that replaces it stayed on that story.
        String first = new String("s1");
        Bucket standIn = new Bucket("b1", first);
        loading.add(standIn);
        Bucket page = new Bucket("b1", "s1", "s2", "s3", "s4");
        StorySeen.viewed(page, "s1", helper, false);
        StorySeen.viewed(page, "s2", helper, false);
        StorySeen.replacing(standIn, first);
        assertSame("s3", StorySeen.cardAfterLoading(page, first));
        assertTrue(statusLine(), openedLater());
        // For a posted story the viewer then moves by index, and it's the answer's, once.
        assertEquals(2, StorySeen.indexAfterLoading(page, "s3", 0));
        assertEquals("the index was taken", 0, StorySeen.indexAfterLoading(page, "s3", 0));
        assertSame("the next data change keeps the viewer's place", first, StorySeen.cardAfterLoading(page, first));
        assertEquals("nothing answered", 0, StorySeen.indexAfterLoading(page, first, 0));
        StorySeen.replacing(standIn, first);
        assertSame("s3", StorySeen.cardAfterLoading(page, first));
        assertEquals("another card", 1, StorySeen.indexAfterLoading(page, "s2", 1));
        StorySeen.replacing(standIn, first);
        assertSame("s3", StorySeen.cardAfterLoading(page, first));
        assertEquals("no bucket", 0, StorySeen.indexAfterLoading(null, "s3", 0));
        // Negative controls: a real copy of the bucket replaced, another card, or none keeps it too.
        StorySeen.replacing(new Bucket("b1", "s1", "s2", "s3", "s4"), "s1");
        assertSame("s1", StorySeen.cardAfterLoading(page, "s1"));
        StorySeen.replacing(null, first);
        assertSame(first, StorySeen.cardAfterLoading(page, first));
        StorySeen.replacing(standIn, first);
        String other = new String("s1");
        assertSame(other, StorySeen.cardAfterLoading(page, other));
        assertSame("the stand-in was forgotten", first, StorySeen.cardAfterLoading(page, first));
        StorySeen.replacing(standIn, first);
        assertSame(first, StorySeen.cardAfterLoading(null, first));
        StorySeen.replacing(standIn, null);
        assertNull(StorySeen.cardAfterLoading(page, null));
        StorySeen.replacing(standIn, first);
        assertSame("nothing watched here", first, StorySeen.cardAfterLoading(new Bucket("b2", "t1", "t2"), first));
        StorySeen.viewed(page, "s3", helper, false);
        StorySeen.viewed(page, "s4", helper, false);
        StorySeen.replacing(standIn, first);
        assertSame("every story watched here", first, StorySeen.cardAfterLoading(page, first));
        Bucket friend = new Bucket("b3", "u1", "u2");
        StorySeen.viewed(friend, "u1", helper, false);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        StorySeen.replacing(standIn, first);
        assertSame("paused", first, StorySeen.cardAfterLoading(friend, first));
        PauseForTests.resume();
        StorySeen.replacing(standIn, first);
        assertSame("u2", StorySeen.cardAfterLoading(friend, first));
    }

    @Test
    public void aFailureAfterTheBucketLoadsKeepsTheViewersPlaceAndIsReported() {
        HookStatus.clear();
        StorySeen.useForTests(new StorySeen.Stories() {
            @Override public String bucketId(Object bucket) { return "b1"; }
            @Override public List<?> cards(Object bucket) { return Arrays.asList("s1", "s2"); }
            @Override public String storyId(Object card) { return (String) card; }
            @Override public boolean isLoading(Object bucket) { throw new IllegalStateException("renamed"); }
            @Override public boolean hasUnwatched(Object bucket) { return true; }
            @Override public void publishWatched(Object bucket, String bucketId) { }
        });
        String card = new String("s1");
        StorySeen.replacing(new Bucket("b0", card), card);
        assertTrue(statusLine(), statusLine().contains("threw"));
        assertSame(card, StorySeen.cardAfterLoading(new Bucket("b1", "s1", "s2"), card));
    }

    @Test
    public void theSwitchOffOrAPauseLeavesTheCardTheLaunchNamed() {
        Bucket page = new Bucket("b1", "s1", "s2");
        StorySeen.viewed(page, "s1", helper, false);
        Settings.VIEW_STORIES_ANONYMOUSLY.save(false);
        assertNull(StorySeen.startCard(page, null));
        assertEquals("s1", StorySeen.startCard(page, "s1"));
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertNull(StorySeen.startCard(page, null));
        assertEquals("s1", StorySeen.startCard(page, "s1"));
        PauseForTests.resume();
        assertFalse(openedLater());
        assertEquals("the card was forgotten", "s2", StorySeen.startCard(page, null));
    }

    @Test
    public void aFailureFindingTheStartCardLeavesTheLaunchAloneAndIsReported() {
        StorySeen.viewed(new Bucket("b1", "s1", "s2"), "s1", helper, false);
        HookStatus.clear();
        StorySeen.useForTests(new StorySeen.Stories() {
            @Override public String bucketId(Object bucket) { return "b1"; }
            @Override public List<?> cards(Object bucket) { return Arrays.asList("s1", "s2"); }
            @Override public String storyId(Object card) { return (String) card; }
            @Override public boolean seenFlag(Object card) { throw new IllegalStateException("renamed"); }
            @Override public boolean hasUnwatched(Object bucket) { return true; }
            @Override public void publishWatched(Object bucket, String bucketId) { }
        });
        assertNull(StorySeen.startCard(new Bucket("b1", "s1", "s2"), null));
        assertEquals("s1", StorySeen.startCard(new Bucket("b1", "s1", "s2"), "s1"));
        assertTrue(statusLine(), statusLine().contains("threw"));
    }

    @Test
    public void aHighlightsBucketGoesOutUnderItsOwnTypeToo() {
        // Highlights tiles are XFBStoryHighlightDeliveryBucket trees on 585, which a
        // DirectMessageThreadBucket publish never reached, so their rings stayed lit (#165).
        assertEquals(Arrays.asList(StorySeen.BUCKET_TYPE, "XFBStoryHighlightDeliveryBucket"),
                StorySeen.typesFor("XFBStoryHighlightDeliveryBucket"));
        // Negative controls: a bucket of Facebook's own type, or one whose type couldn't be read,
        // goes out once, as before.
        assertEquals(Collections.singletonList(StorySeen.BUCKET_TYPE), StorySeen.typesFor(StorySeen.BUCKET_TYPE));
        assertEquals(Collections.singletonList(StorySeen.BUCKET_TYPE), StorySeen.typesFor(null));
        assertEquals(Collections.singletonList(StorySeen.BUCKET_TYPE), StorySeen.typesFor(""));
    }
}
