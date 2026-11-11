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

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.stories.SuggestedStoriesForTests.Copy;
import app.morphe.extension.facebook.stories.SuggestedStoriesForTests.Tile;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Issue #445: Hide Create story takes the Create story tile out of the tray's tile list and keeps
 * every other tile in order. Off, paused, a stub the patch didn't fill, a list it can't copy or any
 * failure leaves the list Facebook made.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class CreateStoryTileTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void startClean() {
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_CREATE_STORY.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.SUGGESTED_STORIES + ":")) return line;
        }
        return null;
    }

    private static String counterLine() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(CreateStoryTile.ROUTE + ":")) return line;
        }
        return null;
    }

    @Test
    public void theCreateStoryTileLeavesWithItsSwitchOn() {
        assertFalse("the switch starts off", Settings.HIDE_CREATE_STORY.get());
        Tile create = new Tile(true);
        Tile friend = new Tile(false);
        Tile page = new Tile(false);
        List<Tile> tray = Arrays.asList(create, friend, page);
        assertSame("off, the tray keeps its tiles", tray, SuggestedStoriesForTests.keptTiles(tray));

        Settings.HIDE_CREATE_STORY.save(true);
        Object kept = SuggestedStoriesForTests.keptTiles(tray);
        assertTrue("an ImmutableList copy takes the list's place", kept instanceof Copy);
        assertEquals(Arrays.asList(friend, page), kept);
        assertTrue(counterLine(), counterLine().contains(CreateStoryTile.CREATE_STORY));
        assertEquals(FamilyNames.SUGGESTED_STORIES + ": invoked 2, 1 found, 0 missing", statusLine());
        assertTrue(SuggestedStoriesForTests.hidesCreateStory());
    }

    /** A tray without the tile, or with nothing in it, stays the list Facebook made. */
    @Test
    public void aTrayWithoutTheTileStaysAsItIs() {
        Settings.HIDE_CREATE_STORY.save(true);
        List<Tile> stories = Arrays.asList(new Tile(false), new Tile(false));
        assertSame(stories, SuggestedStoriesForTests.keptTiles(stories));
        List<Tile> empty = Collections.emptyList();
        assertSame(empty, SuggestedStoriesForTests.keptTiles(empty));
        assertNull(SuggestedStoriesForTests.keptTiles(null));
    }

    @Test
    public void aPauseLeavesTheTile() {
        Settings.HIDE_CREATE_STORY.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        List<Tile> tray = Arrays.asList(new Tile(true), new Tile(false));
        assertSame("paused", tray, SuggestedStoriesForTests.keptTiles(tray));
        PauseForTests.resume();
        assertEquals("resumed", 1, ((List<?>) SuggestedStoriesForTests.keptTiles(tray)).size());
    }

    /** The real stub before the patch fills it: the tray stays whole and the status names what's missing. */
    @Test
    public void anUnfilledStubOrCopyLeavesTheTray() {
        Settings.HIDE_CREATE_STORY.save(true);
        List<Tile> tray = Arrays.asList(new Tile(true), new Tile(false));
        assertSame(tray, CreateStoryTile.keptTiles(tray));
        assertTrue(statusLine(), statusLine().contains("1 missing"));

        HookStatus.clear();
        CreateStoryTile.Tiles noCopy = new CreateStoryTile.Tiles() {
            @Override
            public int createStory(Object tile) {
                return SuggestedStoriesForTests.TILES.createStory(tile);
            }

            @Override
            public Object copy(List<Object> kept) {
                return null;
            }
        };
        assertSame(tray, CreateStoryTile.keptTiles(tray, noCopy));
        assertTrue(statusLine(), statusLine().contains("ImmutableList"));
    }

    @Test
    public void aFailureLeavesTheTray() {
        Settings.HIDE_CREATE_STORY.save(true);
        CreateStoryTile.Tiles throwing = new CreateStoryTile.Tiles() {
            @Override
            public int createStory(Object tile) {
                throw new IllegalStateException("tile");
            }

            @Override
            public Object copy(List<Object> kept) {
                return new Copy(kept);
            }
        };
        List<Tile> tray = Arrays.asList(new Tile(true), new Tile(false));
        assertSame(tray, CreateStoryTile.keptTiles(tray, throwing));
        assertTrue(statusLine(), statusLine().contains("threw"));
    }
}
