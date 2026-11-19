/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.List;

import app.morphe.extension.facebook.media.RecentlyWatched;
import app.morphe.extension.facebook.media.RecentlyWatchedForTests;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The seen helper's queue of viewed cards hands each one that isn't a peek to Recently watched
 * (#183), with View stories anonymously on or off, and a failure there never reaches the viewer.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class StorySeenRecentlyWatchedTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        StorySeenForTests.reset();
        RecentlyWatchedForTests.install();
        StorySeen.useForTests(StorySeenForTests.ONE_BUCKET);
        Settings.RECENTLY_WATCHED.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        StorySeen.useForTests(null);
        RecentlyWatchedForTests.forget();
        Settings.RECENTLY_WATCHED.resetToDefault();
        Settings.VIEW_STORIES_ANONYMOUSLY.resetToDefault();
        StorySeenForTests.reset();
        HookStatus.clear();
    }

    private static List<RecentlyWatched.Entry> entries() {
        return RecentlyWatchedForTests.entries();
    }

    @Test
    public void aViewedCardIsKeptUnderItsBucket() {
        StorySeen.viewed(new Object(), "s1", new Object(), null);
        assertEquals(1, entries().size());
        RecentlyWatched.Entry story = entries().get(0);
        assertEquals(RecentlyWatched.STORY, story.kind);
        assertEquals("s1", story.id);
        assertEquals("b1", story.parent);
        assertEquals("https://www.facebook.com/stories/b1/s1/", story.link());
    }

    @Test
    public void aPeekIsNot() {
        StorySeen.viewed(new Object(), "s1", new Object(), StorySeen.PEEK_SOURCE);
        assertTrue(entries().isEmpty());
    }

    @Test
    public void viewStoriesAnonymouslyOnOrOffMakesNoDifference() {
        Settings.VIEW_STORIES_ANONYMOUSLY.save(false);
        StorySeen.viewed(new Object(), "off", new Object(), null);
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
        StorySeen.viewed(new Object(), "on", new Object(), null);
        assertEquals(2, entries().size());
        assertEquals("on", entries().get(0).id);
    }

    @Test
    public void offOrPausedNothingIsKept() {
        Settings.RECENTLY_WATCHED.save(false);
        StorySeen.viewed(new Object(), "off", new Object(), null);
        Settings.RECENTLY_WATCHED.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        StorySeen.viewed(new Object(), "paused", new Object(), null);
        PauseForTests.resume();
        assertTrue(entries().isEmpty());
    }

    @Test
    public void aCardWithNoIdIsNotKept() {
        StorySeen.viewed(new Object(), null, new Object(), null);
        assertTrue(entries().isEmpty());
        assertTrue(HookStatus.missing(FamilyNames.RESUME_LONG_VIDEOS).isEmpty());
    }

    /** Story classes of another shape: the card isn't listed, the throw is in Hook status, and the viewer goes on. */
    @Test
    public void aThrowingStoryClassLeavesTheViewerAlone() {
        StorySeen.useForTests(new StorySeen.Stories() {
            @Override public String bucketId(Object bucket) { throw new IllegalStateException("renamed"); }
            @Override public List<?> cards(Object bucket) { return Collections.emptyList(); }
            @Override public String storyId(Object card) { return (String) card; }
            @Override public boolean hasUnwatched(Object bucket) { return false; }
            @Override public void publishWatched(Object bucket, String bucketId) { }
        });
        StorySeen.viewed(new Object(), "s1", new Object(), null);
        assertTrue(entries().isEmpty());
        String failures = HookStatus.missing(FamilyNames.RESUME_LONG_VIDEOS).toString();
        assertTrue(failures, failures.contains("recently watched story"));
    }
}
