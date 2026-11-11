/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What Hide Create story does to the tiles of Home's Stories tray (#445).
 *
 * <p>The tray Facebook draws is one list of tiles: the Create story tile first, then a tile for each
 * bucket of stories, then whatever ends the row. The Create story tile isn't a bucket, so the bucket
 * filter of {@link SuggestedStories} never sees it. Facebook's tile builder makes it as one of two
 * tile kinds, which its own tile namer calls ADD_TO_STORY_TILE and ADD_TO_STORY_SPLIT_TILE. The hook
 * goes right after the builder makes its list, before the tray stores it, and hands the list to
 * {@link #keptTiles}.
 *
 * <p>The tile interface and the kinds are Redex names and numbers that change every build, so the
 * patch fills in {@link #createStoryTile}. It fails open: switch off, a pause, settings that aren't
 * ready, a tile it can't read, a list it can't copy, or any failure in here, and the tray keeps every
 * tile Facebook made.
 */
public final class CreateStoryTile {
    /** The diagnostic counter route: each tile list and the Create story tiles left out. */
    static final String ROUTE = "Stories tray tiles";

    /** What a tile left out is counted under. */
    static final String CREATE_STORY = "Create story tile";

    /** What {@link #createStoryTile} answers until the patch fills it in. */
    static final int UNPATCHED = -1;

    /** What the filled stub answers for anything else, a bucket's tile included, and for a Create story tile. */
    static final int OTHER_TILE = 0;
    static final int CREATE_STORY_TILE = 1;

    /** A tray's tiles, read through the stub the patch filled in or through a test's stand-in. */
    interface Tiles {
        /** {@link #CREATE_STORY_TILE}, {@link #OTHER_TILE} or {@link #UNPATCHED}. */
        int createStory(Object tile);

        /** An ImmutableList of [kept], or null when there's nothing to make one with. */
        @Nullable
        Object copy(List<Object> kept);
    }

    static final Tiles PATCHED = new Tiles() {
        @Override
        public int createStory(Object tile) {
            return createStoryTile(tile, 0);
        }

        @Nullable
        @Override
        public Object copy(List<Object> kept) {
            return SuggestedStories.immutableCopy(kept);
        }
    };

    private CreateStoryTile() {
    }

    /**
     * Injection point, right after the tray's tile builder makes its list and before the tray stores
     * it. Answers the list it was handed, or an ImmutableList of the same tiles in the same order
     * without the Create story tile while Hide Create story is on, which takes the list's place.
     * Never throws.
     *
     * @param tiles the tray's tile list, an ImmutableList.
     */
    @Nullable
    public static Object keptTiles(@Nullable List<?> tiles) {
        return keptTiles(tiles, PATCHED);
    }

    /** {@link #keptTiles(List)} with the tile reads passed in, so a test can stand in for the stub. */
    @Nullable
    static Object keptTiles(@Nullable List<?> tiles, Tiles access) {
        try {
            HookStatus.invoked(FamilyNames.SUGGESTED_STORIES);
            if (tiles == null || tiles.isEmpty()) return tiles;
            if (!Utils.settingsReady() || !Settings.HIDE_CREATE_STORY.get()) return tiles;
            FeedFilterCounters.sawList(ROUTE, tiles.size());

            ArrayList<Object> kept = null;
            for (int index = 0; index < tiles.size(); index++) {
                Object tile = tiles.get(index);
                int kind = access.createStory(tile);
                if (kind == UNPATCHED) {
                    HookStatus.missingMember(FamilyNames.SUGGESTED_STORIES, "method", "tray tile", "kind");
                    return tiles;
                }
                if (kind == CREATE_STORY_TILE) {
                    if (kept == null) {
                        kept = new ArrayList<>(tiles.size());
                        kept.addAll(tiles.subList(0, index));
                    }
                } else if (kept != null) {
                    kept.add(tile);
                }
            }
            HookStatus.bound(FamilyNames.SUGGESTED_STORIES, "tray tile#kind");
            if (kept == null) return tiles;

            Object copy = access.copy(kept);
            if (copy == null) {
                HookStatus.missingMember(FamilyNames.SUGGESTED_STORIES, "method", "ImmutableList", "copyOf(Collection)");
                return tiles;
            }
            final int left = tiles.size() - kept.size();
            FeedFilterCounters.removed(ROUTE, left, CREATE_STORY);
            Logger.printDebug(() -> "Stories tray: " + tiles.size() + " tiles, Create story left out (" + left + ")");
            return copy;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "tray tile filter", failure);
            Logger.printException(() -> "Hide Create story: could not read the tray's tiles", failure);
            return tiles;
        }
    }

    /**
     * Injection point, filled in by the patch: {@link #CREATE_STORY_TILE} when the item is a tray tile
     * of a kind Facebook's tile namer calls ADD_TO_STORY_TILE or ADD_TO_STORY_SPLIT_TILE, otherwise
     * {@link #OTHER_TILE}. The patch replaces this body with an instance-of the tile interface, a call
     * of its kind method and a compare with the kinds. {@code spare} gives the filled body a second
     * register it can name, so the stub's own register count doesn't matter.
     */
    public static int createStoryTile(Object tile, int spare) {
        return UNPATCHED;
    }
}
