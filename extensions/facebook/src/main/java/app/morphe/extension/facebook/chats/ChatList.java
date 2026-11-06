/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.chats;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What the Clean up Facebook's chat list patch asks while Facebook's own Chats builds its list.
 *
 * <p>Five hooks for three switches. {@link #notesTiles} sits where the state behind the row of notes and
 * active friends above the chats stores its tiles, and hands back no tiles while Hide the notes and active now row
 * is on. {@link #hidesNotesRow} sits first in the section that draws that row, and answers yes with the same
 * switch, which the patch turns into the section's own empty result, so your own note tile goes too.
 * Dating's Matches screen is the same inbox drawn with Dating's style, and its row of new matches is
 * that section and state, so both leave Dating's row alone.
 * {@link #hidesPromotion} sits first in the show question of each promotion banner at the
 * top of Chats, and answers yes while Hide chat list promotions is on, which the patch turns into
 * a no for Facebook. {@link #metaAiButton} takes the Meta AI button the search row just built and
 * hands back none while Hide the Meta AI button in Chats is on, and {@link #showsSearchMetaAiButton}
 * does the same for the search screen's button by answering its row's flag. All of them fail open: with the
 * switch off, a pause, settings that aren't ready or any failure in here, Facebook gets exactly
 * what it would have built.
 */
public final class ChatList {
    /** The diagnostic counter routes: each time Chats built the piece, and what was taken out. */
    static final String NOTES_ROUTE = "Chat notes tray";
    static final String NOTES_ROW_ROUTE = "Chat notes row";
    static final String PROMOTIONS_ROUTE = "Chat list promotions";
    static final String META_AI_ROUTE = "Chats Meta AI button";

    /** What a hidden piece is counted under. */
    static final String NOTES_HIDDEN = "Hide the notes and active now row";
    static final String PROMOTIONS_HIDDEN = "Hide chat list promotions";
    static final String META_AI_HIDDEN = "Hide the Meta AI button in Chats";

    private ChatList() {
    }

    /**
     * Injection point, right before the tile state stores its list. The tiles Facebook built, or
     * an empty list while the notes switch is on. Tiles from Dating stay: its Matches screen is the
     * same inbox, and its row of new matches goes through this state too (#337). Never throws.
     */
    @Nullable
    public static List<?> notesTiles(@Nullable List<?> tiles) {
        try {
            HookStatus.invoked(FamilyNames.CHAT_LIST);
            if (tiles == null || tiles.isEmpty()) return tiles;
            FeedFilterCounters.sawList(NOTES_ROUTE, tiles.size());
            if (!Utils.settingsReady() || !Settings.HIDE_CHAT_NOTES_TRAY.get()) return tiles;
            if (holdsDatingTile(tiles)) {
                FeedFilterCounters.sawKind(NOTES_ROUTE, DATING_KEPT);
                return tiles;
            }
            FeedFilterCounters.removed(NOTES_ROUTE, tiles.size(), NOTES_HIDDEN);
            return Collections.emptyList();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_LIST, "notes tray tiles", failure);
            return tiles;
        }
    }

    /**
     * Injection point, first thing in the render of the section that draws the notes row. True has
     * the section hand back its own empty result, so the row isn't drawn and leaves no gap. Dating's
     * Matches screen draws its new matches with the same section, so a section whose inbox has
     * Dating's style keeps its row (#337). Never throws.
     *
     * @param params the section's ThreadListParams, the inbox it draws for, or null.
     */
    public static boolean hidesNotesRow(@Nullable Object params) {
        try {
            HookStatus.invoked(FamilyNames.CHAT_LIST);
            FeedFilterCounters.sawList(NOTES_ROW_ROUTE, 1);
            if (!Utils.settingsReady() || !Settings.HIDE_CHAT_NOTES_TRAY.get()) return false;
            if (datingInbox(params)) {
                FeedFilterCounters.sawKind(NOTES_ROW_ROUTE, DATING_KEPT);
                return false;
            }
            FeedFilterCounters.removed(NOTES_ROW_ROUTE, 1, NOTES_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_LIST, "notes row", failure);
            return false;
        }
    }

    /** What a row left alone because it's Dating's is counted under. */
    static final String DATING_KEPT = "Dating new matches kept";

    /** The inbox style Facebook gives Dating's Matches screen, the same text its own section compares. */
    static final String DATING_STYLE = "mib_style_gemstone";

    /** Dating's models live in a package with this in its name, Facebook's own name for Dating. */
    static final String DATING_PACKAGE = ".gemstone.";

    /** Per class, the fields to read: the String fields of an inbox's params, the Dating-typed fields of a tile. */
    private static final ConcurrentHashMap<Class<?>, Field[]> STYLE_FIELDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Field[]> DATING_FIELDS = new ConcurrentHashMap<>();

    /** Whether [params], an inbox's ThreadListParams, has Dating's style in one of its text fields. */
    static boolean datingInbox(@Nullable Object params) throws IllegalAccessException {
        if (params == null) return false;
        for (Field field : fields(STYLE_FIELDS, params.getClass(), false)) {
            if (DATING_STYLE.equals(field.get(params))) return true;
        }
        return false;
    }

    /** Whether one of [tiles] carries a Dating match or conversation in a field typed from Dating's package. */
    static boolean holdsDatingTile(List<?> tiles) throws IllegalAccessException {
        for (Object tile : tiles) {
            if (tile == null) continue;
            for (Field field : fields(DATING_FIELDS, tile.getClass(), true)) {
                if (field.get(tile) != null) return true;
            }
        }
        return false;
    }

    private static Field[] fields(ConcurrentHashMap<Class<?>, Field[]> cache, Class<?> type, boolean dating) {
        Field[] known = cache.get(type);
        if (known != null) return known;
        List<Field> found = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            boolean wanted = dating
                    ? field.getType().getName().contains(DATING_PACKAGE)
                    : field.getType() == String.class;
            if (!wanted) continue;
            field.setAccessible(true);
            found.add(field);
        }
        Field[] read = found.toArray(new Field[0]);
        cache.put(type, read);
        return read;
    }

    /**
     * Injection point, first thing in a promotion banner's show question. True answers no for
     * Facebook, so the banner isn't shown. Never throws.
     */
    public static boolean hidesPromotion() {
        try {
            HookStatus.invoked(FamilyNames.CHAT_LIST);
            FeedFilterCounters.sawList(PROMOTIONS_ROUTE, 1);
            if (!Utils.settingsReady() || !Settings.HIDE_CHAT_PROMOTIONS.get()) return false;
            FeedFilterCounters.removed(PROMOTIONS_ROUTE, 1, PROMOTIONS_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_LIST, "promotion banner", failure);
            return false;
        }
    }

    /**
     * Injection point, right after the search row at the top of Chats builds its Meta AI button,
     * itself or through a helper it hands the button's builder to (584).
     * The button Facebook built, or null while the Meta AI switch is on, which the row already
     * takes as no button and leaves the search box alone. Never throws.
     */
    @Nullable
    public static Object metaAiButton(@Nullable Object button) {
        try {
            HookStatus.invoked(FamilyNames.CHAT_LIST);
            if (button == null) return null;
            FeedFilterCounters.sawList(META_AI_ROUTE, 1);
            if (!Utils.settingsReady() || !Settings.HIDE_CHAT_META_AI_BUTTON.get()) return button;
            FeedFilterCounters.removed(META_AI_ROUTE, 1, META_AI_HIDDEN);
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_LIST, "Meta AI button", failure);
            return button;
        }
    }

    /**
     * Injection point, right before the Chats search screen's row tests its Meta AI flag to build
     * the button, on every way there, so {@code shows} is often already false. False while the Meta
     * AI switch is on, so the button isn't built. The row has already given the search box the end
     * margin it keeps beside the button, so the box still stops short of the screen's edge. Never throws.
     */
    public static boolean showsSearchMetaAiButton(boolean shows) {
        try {
            HookStatus.invoked(FamilyNames.CHAT_LIST);
            if (!shows) return false;
            FeedFilterCounters.sawList(META_AI_ROUTE, 1);
            if (!Utils.settingsReady() || !Settings.HIDE_CHAT_META_AI_BUTTON.get()) return true;
            FeedFilterCounters.removed(META_AI_ROUTE, 1, META_AI_HIDDEN);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_LIST, "search screen Meta AI button", failure);
            return shows;
        }
    }
}
