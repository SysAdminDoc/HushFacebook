/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import java.util.Arrays;
import java.util.List;

/** Hands the Friends tab hook rows shaped like Facebook's: GraphQL trees with a type and String fields by key. */
public final class FriendsTabSuggestionsForTests {
    private FriendsTabSuggestionsForTests() {
    }

    /** Stands in for TreeJNI: the kept public getTypeName() and getString(int) a row inherits. */
    public static class Tree {
        private final String typeName;
        private final String sectionType;

        Tree(String typeName, String sectionType) {
            this.typeName = typeName;
            this.sectionType = sectionType;
        }

        public String getTypeName() {
            return typeName;
        }

        /** A header's section type under its key, as a tree answers a String field; null for any other key. */
        public String getString(int key) {
            return key == FriendsTabSuggestions.SECTION_TYPE_KEY ? sectionType : null;
        }
    }

    /** The one row class Facebook builds every row of the tab from, a subclass of the tree. */
    public static final class Row extends Tree {
        Row(String typeName, String sectionType) {
            super(typeName, sectionType);
        }
    }

    /** A section header opening a section of [sectionType]. */
    public static Row header(String sectionType) {
        return new Row(FriendsTabSuggestions.HEADER, sectionType);
    }

    /** A row of the given GraphQL type, with no section type. */
    public static Row row(String typeName) {
        return new Row(typeName, null);
    }

    /** The tab as the S22 drew it on 588: one friend request, then People you may know. */
    public static List<Object> tab() {
        return Arrays.asList(header("FRIEND_REQUESTS"), row("FriendRequestsFriendingTabRow"),
                header(FriendsTabSuggestions.SECTION_TYPE), row(FriendsTabSuggestions.PERSON),
                row(FriendsTabSuggestions.PERSON));
    }

    /** True when the hook leaves the People you may know section out of the tab. */
    public static boolean dropsTheSuggestions() {
        List<Object> rows = tab();
        List<?> kept = FriendsTabSuggestions.rows(rows);
        return kept != null && kept.size() == 2;
    }

    /** Forgets the kept reader and the debug lines, as a new Facebook process would start without them. */
    public static void newProcess() {
        FriendsTabSuggestions.forget();
    }
}
