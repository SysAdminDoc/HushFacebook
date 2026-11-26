/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;

/**
 * What the Hide suggested and promoted posts patch asks while Facebook lays out the Friends tab (#600).
 *
 * <p>The Friends tab is one paginated list of rows: a section header ("Friend requests", "People you
 * may know"), then that section's rows, then the next header. Each row is a GraphQL tree that answers
 * its type with {@code getTypeName()}: {@value #HEADER} for a header, {@value #PERSON} for a person
 * Facebook suggests, and other names for friend requests and friends. A header says which section it
 * opens in its {@code type} field, {@value #SECTION_TYPE} for the suggestions. Every time the tab's
 * rows change, the patch hands {@link #rows} the whole list right after the tab reads it, before
 * Facebook's own pass over it, and copies the answer back into an ImmutableList.
 *
 * <p>While Hide "People you may know" in the Friends tab is on, the suggestions' header goes, and so
 * does every row under it up to the next header, as well as any suggested person row anywhere else.
 * The suggestions are the tab's last section and page on without end, so nothing after them is lost.
 * It fails open: with the switch off, a pause, settings that aren't ready, rows it can't read or any
 * failure in here, the tab gets Facebook's list as it came.
 */
public final class FriendsTabSuggestions {
    /** The type name of a section header row. */
    static final String HEADER = "SectionHeaderFriendingTabRow";

    /** The type name of a suggested person's row. */
    static final String PERSON = "PeopleYouMayKnowFriendingTabRow";

    /** The section type a header of the suggestions carries (GraphQLFriendingTabSectionType). */
    static final String SECTION_TYPE = "PEOPLE_YOU_MAY_KNOW";

    /** A tree keys each field by its name's hashCode; a header names its section in "type". */
    static final int SECTION_TYPE_KEY = "type".hashCode();

    /** The tree's kept getters: its GraphQL type, and a String field by key. */
    static final String TYPE_NAME = "getTypeName";
    static final String STRING_FIELD = "getString";

    /** The counter route. */
    static final String ROUTE = "Friends tab";

    /** The Hook status name of the hook. */
    static final String HOOK = "Friends tab rows";

    /** Distinct debug lines kept, so a tab that pages on doesn't fill the log. */
    private static final int MAX_LOGGED = 24;

    /** The debug lines already written this process. */
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    /** A row class and its two getters, looked up once and kept together. */
    private static final class RowReader {
        final Class<?> type;
        final Method typeName;
        final Method string;

        RowReader(Class<?> type, Method typeName, Method string) {
            this.type = type;
            this.typeName = typeName;
            this.string = string;
        }
    }

    /** The reader of the last row class seen. Facebook builds every row from one tree class. */
    @Nullable
    private static volatile RowReader reader;

    private FriendsTabSuggestions() {
    }

    /**
     * Injection point, right after the Friends tab reads its rows, a list of GraphQL trees. Answers
     * [rows] without the People you may know section, or [rows] itself when nothing goes. The patch
     * copies the answer back into an ImmutableList. Never throws.
     */
    @Nullable
    public static List<?> rows(@Nullable List<?> rows) {
        try {
            HookStatus.invoked(FamilyNames.SUGGESTED_POSTS_FRIENDS_TAB);
            if (rows == null || rows.isEmpty()) return rows;
            // Ready first: Settings loads every switch, and it can't before the context is set.
            if (!Utils.settingsReady()) return rows;
            boolean hide = Settings.HIDE_FRIENDS_TAB_PEOPLE_YOU_MAY_KNOW.get();
            boolean debug = BaseSettings.DEBUG.get();
            if (!hide && !debug) return rows;
            List<Object> kept = null;
            Map<String, Integer> kinds = new TreeMap<>();
            boolean underSuggestions = false;
            boolean known = false;
            for (int index = 0; index < rows.size(); index++) {
                Object row = rows.get(index);
                RowReader found = row == null ? null : readerFor(row.getClass());
                if (found == null) return rows;
                Object name = found.typeName.invoke(row);
                String type = name == null ? "null" : name.toString();
                boolean drop;
                if (HEADER.equals(type)) {
                    Object section = found.string.invoke(row, SECTION_TYPE_KEY);
                    underSuggestions = SECTION_TYPE.equals(section);
                    type = HEADER + "(" + section + ")";
                    drop = underSuggestions;
                    known = true;
                } else {
                    if (PERSON.equals(type)) known = true;
                    drop = underSuggestions || PERSON.equals(type);
                }
                Integer seen = kinds.get(type);
                kinds.put(type, seen == null ? 1 : seen + 1);
                if (drop && hide) {
                    if (kept == null) kept = new ArrayList<>(rows.subList(0, index));
                } else if (kept != null) {
                    kept.add(row);
                }
            }
            if (known) HookStatus.bound(FamilyNames.SUGGESTED_POSTS_FRIENDS_TAB, HOOK);
            FeedFilterCounters.sawList(ROUTE, rows.size());
            int removed = kept == null ? 0 : rows.size() - kept.size();
            if (removed > 0) FeedFilterCounters.removed(ROUTE, removed, SECTION_TYPE);
            log(rows.size(), removed, kinds);
            return kept == null ? rows : kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_POSTS_FRIENDS_TAB, HOOK, failure);
            return rows;
        }
    }

    /**
     * The two getters of [type], kept for the next list. Null, with the miss reported, when the row
     * class has no public {@code getTypeName()} or {@code getString(int)}.
     */
    @Nullable
    private static RowReader readerFor(Class<?> type) {
        RowReader known = reader;
        if (known != null && known.type == type) return known;
        Method typeName;
        Method string;
        try {
            typeName = type.getMethod(TYPE_NAME);
            string = type.getMethod(STRING_FIELD, int.class);
        } catch (NoSuchMethodException absent) {
            HookStatus.missingMember(FamilyNames.SUGGESTED_POSTS_FRIENDS_TAB, "method", type.getName(),
                    TYPE_NAME + " or " + STRING_FIELD);
            return null;
        }
        known = new RowReader(type, typeName, string);
        reader = known;
        return known;
    }

    /** One debug line per distinct list shape: how many of each row type, and how many went. Never a name or id. */
    private static void log(int size, int removed, Map<String, Integer> kinds) {
        String line = "Friends tab: " + size + " rows " + kinds + ", " + removed + " hidden";
        if (LOGGED.size() >= MAX_LOGGED || !LOGGED.add(line)) return;
        Logger.printDebug(() -> FamilyNames.SUGGESTED_POSTS + ": " + line);
    }

    /** Forgets the kept reader and the debug lines, as a new Facebook process would start without them. Tests only. */
    static void forget() {
        reader = null;
        LOGGED.clear();
    }
}
