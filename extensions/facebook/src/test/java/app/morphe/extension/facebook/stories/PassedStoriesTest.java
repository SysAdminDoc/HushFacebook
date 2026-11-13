/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import com.facebook.stories.viewer.activity.StoryViewerActivity;

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
 * Hide stories you scrolled past (#463): a story whose tile came into view in Home's tray leaves the
 * trays of your next visit to Facebook, watched or not, until its person posts again. Within the visit
 * it stays, a refresh included, and moving between Facebook's screens or a rotation isn't a new visit.
 * The Add to story tile, a tile no tray listed, the gray placeholders, the switch off and a pause keep
 * every bucket.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PassedStoriesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final long HOUR_MS = 60L * 60 * 1000;

    /** One of Facebook's screens, which can say it's being rebuilt for a rotation. */
    public static final class Screen extends Activity {
        boolean rotating;

        @Override
        public boolean isChangingConfigurations() {
            return rotating;
        }
    }

    /** A bucket of the tray's list: its id, its expiration_time in seconds and its type. */
    private static final class Tree {
        final Object id;
        final Object expires;
        final Type type;

        Tree(Object id, Object expires, Type type) {
            this.id = id;
            this.expires = expires;
            this.type = type;
        }

        @Override
        public String toString() {
            return "Tree(" + id + ", " + expires + ", " + type + ")";
        }
    }

    /** The tray's stubs as the patch fills them, a friend's unwatched story each unless its type says otherwise. */
    private static final SuggestedStories.Buckets TREES = new SuggestedStories.Buckets() {
        @Override public boolean isBucket(Object item) { return item instanceof Tree; }
        @Override public Object type(Object bucket) { return ((Tree) bucket).type; }
        @Override public Object suggested(Object bucket) { return false; }
        @Override public Object label(Object bucket) { return null; }
        @Override public Object copy(List<Object> kept) { return Collections.unmodifiableList(new ArrayList<>(kept)); }
        @Override public Object seen(Object bucket) { return false; }
        @Override public Object ownerType(Object bucket) { return "User"; }
        @Override public Object id(Object bucket) { return ((Tree) bucket).id; }
        @Override public Object expires(Object bucket) { return ((Tree) bucket).expires; }
    };

    private Screen home;

    @Before
    public void start() {
        RuntimeEnvironment.getApplication().getSharedPreferences(PassedStories.FILE, Context.MODE_PRIVATE).edit().clear().commit();
        PassedStories.resetForTests();
        StoriesTrayPaging.reset();
        SuggestedStories.resetViewersForTests();
        Settings.HIDE_PASSED_STORIES.save(true);
        FeedFilterCounters.clear();
        HookStatus.clear();
        home = Robolectric.buildActivity(Screen.class).get();
        SuggestedStories.activityStarted(home);
    }

    @After
    public void restore() {
        PassedStories.resetForTests();
        StoriesTrayPaging.reset();
        SuggestedStories.resetViewersForTests();
        PauseForTests.resume();
        Settings.HIDE_PASSED_STORIES.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    /** A friend's bucket whose newest story was posted [hoursAgo] hours ago, so it expires a day after that. */
    private static Tree postedHoursAgo(String id, double hoursAgo) {
        long posted = System.currentTimeMillis() - (long) (hoursAgo * HOUR_MS);
        return new Tree(id, (posted + StorySeen.STORY_LIFE_MS) / 1000, Type.STORY);
    }

    private static Object kept(List<Tree> tray) {
        return SuggestedStories.keptBuckets(tray, TREES);
    }

    /** The tile of [bucketId] comes into view at [position], as the tray's tile handler reports it. */
    private static void scrollPast(String bucketId, int position) {
        StoriesTrayPaging.tileShown(null, bucketId, position);
    }

    /** You leave Facebook from its last screen and open it again later. */
    private void nextVisit() {
        SuggestedStories.activityStopped(home);
        SuggestedStories.activityStarted(home);
    }

    private static String line(String route, List<String> lines) {
        for (String line : lines) {
            if (line.startsWith(route + ":")) return line;
        }
        return null;
    }

    @Test
    public void aStoryScrolledPastLeavesFromTheNextVisit() {
        Tree passed = postedHoursAgo("b1", 3);
        Tree later = postedHoursAgo("b2", 3);
        List<Tree> tray = Arrays.asList(passed, later);
        assertSame("nothing came into view yet", tray, kept(tray));

        scrollPast("b1", 1);
        assertSame("it left the tray you're scrolling", tray, kept(tray));
        List<Tree> refreshed = Arrays.asList(passed, later);
        assertSame("it left on a refresh in the same visit", refreshed, kept(refreshed));

        nextVisit();
        assertEquals(Collections.singletonList(later), kept(Arrays.asList(postedHoursAgo("b1", 3), later)));
        PassedStories.resetForTests();
        assertEquals("it came back after a restart", Collections.singletonList(later), kept(tray));

        String counters = line(SuggestedStories.ROUTE, FeedFilterCounters.report());
        assertTrue(counters, counters.contains("Removed: " + PassedStories.PASSED + " 2."));
        String status = line(FamilyNames.SUGGESTED_STORIES, HookStatus.report());
        assertTrue(status, status.contains(PassedStories.KEPT + " 1"));
        assertTrue(status, status.contains(" 0 missing"));
    }

    @Test
    public void aNewStoryBringsThePersonBack() {
        List<Tree> tray = Collections.singletonList(postedHoursAgo("b1", 3));
        kept(tray);
        scrollPast("b1", 1);
        nextVisit();
        assertEquals(Collections.emptyList(), kept(tray));

        List<Tree> newStory = Collections.singletonList(postedHoursAgo("b1", 0.25));
        assertSame("the new story left", newStory, kept(newStory));
        scrollPast("b1", 1);
        assertSame(newStory, kept(newStory));
        nextVisit();
        assertEquals("scrolled past again, it goes again", Collections.emptyList(), kept(newStory));
    }

    @Test
    public void anotherScreenOrARotationIsNotANewVisit() {
        List<Tree> tray = Collections.singletonList(postedHoursAgo("b1", 3));
        kept(tray);
        scrollPast("b1", 1);

        Screen profile = Robolectric.buildActivity(Screen.class).get();
        SuggestedStories.activityStarted(profile);
        SuggestedStories.activityStopped(home);
        SuggestedStories.activityStarted(home);
        SuggestedStories.activityStopped(profile);
        assertSame("it left after a trip to another screen", tray, kept(tray));

        home.rotating = true;
        SuggestedStories.activityStopped(home);
        Screen rotated = Robolectric.buildActivity(Screen.class).get();
        SuggestedStories.activityStarted(rotated);
        assertSame("it left after a rotation", tray, kept(tray));

        home = rotated;
        nextVisit();
        assertEquals(Collections.emptyList(), kept(tray));
    }

    @Test
    public void theSwitchOffOrAPauseKeepsFacebooksTray() {
        List<Tree> tray = Collections.singletonList(postedHoursAgo("b1", 3));
        kept(tray);
        scrollPast("b1", 1);
        nextVisit();

        Settings.HIDE_PASSED_STORIES.save(false);
        assertSame("off, it left", tray, kept(tray));
        Settings.HIDE_PASSED_STORIES.save(true);
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
    public void aTileThatCameIntoViewWithTheSwitchOffIsNotKept() {
        List<Tree> tray = Collections.singletonList(postedHoursAgo("b1", 3));
        kept(tray);
        Settings.HIDE_PASSED_STORIES.save(false);
        scrollPast("b1", 1);
        Settings.HIDE_PASSED_STORIES.save(true);
        nextVisit();
        assertSame(tray, kept(tray));
    }

    @Test
    public void addToStoryPlaceholdersAndUnreadableBucketsStay() {
        Tree yours = new Tree("self", postedHoursAgo("self", 3).expires, Type.ADD_TO_STORY);
        Tree noTime = new Tree("b2", null, Type.STORY);
        Tree zeroTime = new Tree("b3", 0L, Type.STORY);
        Tree noId = new Tree(null, postedHoursAgo("b4", 3).expires, Type.STORY);
        List<Tree> tray = Arrays.asList(yours, noTime, zeroTime, noId);
        kept(tray);
        scrollPast("self", 0);
        scrollPast("b2", 1);
        scrollPast("b3", 2);
        scrollPast(null, 3);
        scrollPast(StoriesTrayPaging.PLACEHOLDER, 4);
        scrollPast("never listed", 5);
        nextVisit();

        assertSame(tray, kept(tray));
        String status = line(FamilyNames.SUGGESTED_STORIES, HookStatus.report());
        assertFalse(status, status.contains(PassedStories.KEPT));
    }

    /** #438's hold: a bucket a tray kept stays while a story viewer is open, and leaves after it closes. */
    @Test
    public void theBucketOnScreenStaysWhileAViewerIsOpen() {
        Tree passed = postedHoursAgo("b1", 3);
        Tree later = postedHoursAgo("b2", 3);
        List<Tree> tray = Arrays.asList(passed, later);
        kept(tray);
        scrollPast("b1", 1);
        ActivityController<StoryViewerActivity> viewer = Robolectric.buildActivity(StoryViewerActivity.class).setup();
        SuggestedStories.activityCreated(viewer.get());
        nextVisit();
        assertEquals("the bucket on screen left", tray, kept(tray));

        viewer.pause().stop().destroy();
        SuggestedStories.activityDestroyed(viewer.get());
        assertEquals("it stayed after the viewer closed", Collections.singletonList(later), kept(tray));
    }
}
