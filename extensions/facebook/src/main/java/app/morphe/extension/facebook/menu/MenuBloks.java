/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.menu;

import android.util.SparseArray;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.profile.ProfileNotes;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What the Hide Menu promotions patch asks each time a Bloks node hands out its children (#445).
 *
 * <p>On some accounts Facebook draws the top of its Menu as one Bloks view, its server-sent layout:
 * the profile card, Create Facebook Page under it, then Your shortcuts. Every Bloks node keeps its
 * attributes in a SparseArray, as {@link ProfileNotes} reads them, and the patch hands every list the
 * node class's child getters return to {@link #children}, and every attribute the node's attribute
 * walk hands a visitor to {@link #visited}. 586's renderer reaches the profile card's lists only
 * through that walk. No title names the parts, but strings inside them do: the test key of the
 * profile row, the test key of the Create Facebook Page row, and the addresses Your shortcuts loads
 * its tiles from and each tile opens.
 *
 * <p>A child leaves a list only when something below it names a hidden part, nothing below it names
 * the profile row, and a child beside it holds the profile row. On 586 that's the Create Facebook Page
 * card next to the profile row in the switcher, and the Your shortcuts section next to the profile
 * section in the Menu's top column. A list higher up holds the profile row and the hidden part in one
 * child and stays whole, and a list lower down has no profile row in it, so the profile card and the
 * rest of the Menu draw as before. A node's answer is kept by the node, since the getters and the walk
 * run on every layout pass.
 *
 * <p>It fails open: both switches off, a pause, settings that aren't ready, or any failure in here,
 * and the list goes back as Facebook made it.
 */
public final class MenuBloks {
    /** The test key of the profile row at the top of the Menu, which every hidden part sits beside. */
    static final String PROFILE_ROW = "condensed_switcher_profile_test_key";

    /** The test key of the Create Facebook Page row. */
    static final String CREATE_PAGE = "condensed_switcher_create_another_profile_key";

    /** What Your shortcuts' placeholder loads its tiles from, and what each tile opens. */
    static final String[] SHORTCUTS = {
            "/product_engagement/load_shortcuts",
            "/product_engagement/shortcuts_bottom_sheet",
    };

    /** The counter route: the parts of the Menu's Bloks view left out. */
    static final String ROUTE = "Menu Bloks";

    /** What a left-out Create Facebook Page card is counted under. */
    static final String CREATE_PAGE_HIDDEN = "Create Facebook Page";

    /** The Hook status name of the hook. */
    static final String HOOK = "Menu Bloks children";

    /** How far below a list's child its strings are read. */
    static final int DEPTH = 40;

    /** What a node holds somewhere at or below it, as bits. */
    static final int HOLDS_PROFILE_ROW = 1;
    static final int HOLDS_CREATE_PAGE = 2;
    static final int HOLDS_SHORTCUTS = 4;

    /** Each node's bits. Bloks nodes keep Object's identity equality, so this is by node. */
    private static final Map<Object, Integer> HOLDS = Collections.synchronizedMap(new WeakHashMap<>());

    private MenuBloks() {
    }

    /**
     * Injection point, right after a Bloks node's attribute walk reads each attribute, before its
     * visitor gets it: the attribute, or a list attribute without a hidden part of the Menu. Never
     * throws.
     */
    public static Object visited(@Nullable Object value) {
        return value instanceof List ? children((List<?>) value) : value;
    }

    /**
     * Injection point, before every return of the Bloks node class's child getters: the list the
     * getter would return, or the same list without a hidden part of the Menu. Never throws.
     */
    public static List<?> children(@Nullable List<?> list) {
        try {
            HookStatus.invoked(FamilyNames.MENU_PROMOTIONS);
            if (list == null || list.size() < 2 || !Utils.settingsReady()) return list;
            int hidden = (Settings.HIDE_MENU_CREATE_PAGE.get() ? HOLDS_CREATE_PAGE : 0)
                    | (Settings.HIDE_MENU_SHORTCUTS.get() ? HOLDS_SHORTCUTS : 0);
            if (hidden == 0) return list;
            int size = list.size();
            int[] holds = new int[size];
            boolean profileRow = false;
            for (int index = 0; index < size; index++) {
                holds[index] = holds(list.get(index), 0);
                if ((holds[index] & HOLDS_PROFILE_ROW) != 0) profileRow = true;
            }
            if (!profileRow) return list;
            List<Object> kept = null;
            for (int index = 0; index < size; index++) {
                int part = (holds[index] & HOLDS_PROFILE_ROW) == 0 ? holds[index] & hidden : 0;
                if (part != 0) {
                    if (kept == null) kept = new ArrayList<>(list.subList(0, index));
                    String reason = (part & HOLDS_CREATE_PAGE) != 0 ? CREATE_PAGE_HIDDEN : MenuSections.SHORTCUTS_HIDDEN;
                    HookStatus.bound(FamilyNames.MENU_PROMOTIONS, HOOK);
                    FeedFilterCounters.removed(ROUTE, 1, reason);
                    Logger.printDebug(() -> "Menu Bloks: left out " + reason);
                } else if (kept != null) {
                    kept.add(list.get(index));
                }
            }
            return kept == null ? list : kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MENU_PROMOTIONS, HOOK, failure);
            Logger.printException(() -> "Menu Bloks: could not read a Bloks node's children", failure);
            return list;
        }
    }

    /** The bits [value] holds: a string by what it names, a Bloks node by everything below it. */
    static int holds(@Nullable Object value, int depth) throws IllegalAccessException {
        if (value instanceof String) return names((String) value);
        if (value == null || depth > DEPTH) return 0;
        Field field = ProfileNotes.attributes(value.getClass());
        if (field == null) return 0;
        Integer known = HOLDS.get(value);
        if (known != null) return known;
        SparseArray<?> attributes = (SparseArray<?>) field.get(value);
        int holds = 0;
        if (attributes != null) {
            for (int index = 0, size = attributes.size(); index < size; index++) {
                Object attribute = attributes.valueAt(index);
                if (attribute instanceof List) {
                    List<?> items = (List<?>) attribute;
                    for (int at = 0, count = items.size(); at < count; at++) {
                        holds |= holds(items.get(at), depth + 1);
                    }
                } else {
                    holds |= holds(attribute, depth + 1);
                }
            }
        }
        HOLDS.put(value, holds);
        return holds;
    }

    /** The bits one string names. */
    static int names(String text) {
        int names = 0;
        if (text.equals(PROFILE_ROW)) names |= HOLDS_PROFILE_ROW;
        if (text.equals(CREATE_PAGE)) names |= HOLDS_CREATE_PAGE;
        for (String address : SHORTCUTS) {
            if (text.contains(address)) {
                names |= HOLDS_SHORTCUTS;
                break;
            }
        }
        return names;
    }

    /** Forgets every node's answer, for tests. */
    static void forgetForTests() {
        HOLDS.clear();
    }
}
