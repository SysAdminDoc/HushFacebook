/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.profile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Hands the friends list hooks a section and pages shaped like Facebook's 588 ones. */
public final class FriendListForTests {
    /** The 588 names of the section's own-list and empty-search flags, which the patch fills in. */
    public static final String OWN = "A07";
    public static final String NO_SEARCH = "A08";

    private FriendListForTests() {
    }

    /** Stands in for the All friends section: the two flags the extension reads, under their 588 names. */
    public static final class Section {
        @SuppressWarnings("unused") private final boolean A07;
        @SuppressWarnings("unused") private final boolean A08;
        @SuppressWarnings("unused") private final boolean A06 = false;

        Section(boolean own, boolean noSearch) {
            this.A07 = own;
            this.A08 = noSearch;
        }
    }

    /** Stands in for TreeJNI: the kept public getString(int) a friend's row inherits. */
    public static class Tree {
        private final String id;
        private final String name;

        Tree(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getString(int key) {
            if (key == "id".hashCode()) return id;
            if (key == "name".hashCode()) return name;
            return null;
        }
    }

    /** The row class a friend comes as, a subclass of the tree. */
    public static final class Friend extends Tree {
        Friend(String id, String name) {
            super(id, name);
        }
    }

    /** The paginated list's base, which keeps the rows in Facebook. */
    public static class Paginated {
        public List<Object> list;
        public boolean hasNextPage;
        public boolean isLoadingNext;
        public boolean paginationLoadHadError;
    }

    /** What the section reads off the load, a subclass with the same fields inherited. */
    public static final class PaginableList extends Paginated {
    }

    public static Section yours() {
        return new Section(true, true);
    }

    public static Section someoneElses() {
        return new Section(false, true);
    }

    public static Section searched() {
        return new Section(true, false);
    }

    public static Friend friend(String id, String name) {
        return new Friend(id, name);
    }

    /** A page holding [friends], with more to load when [more]. */
    public static PaginableList page(boolean more, Object... friends) {
        PaginableList page = new PaginableList();
        page.list = new ArrayList<>(Arrays.asList(friends));
        page.hasNextPage = more;
        return page;
    }

    /** The section's case: the section after its cast, then the page after it's read. */
    public static void load(Section section, PaginableList page) {
        FriendList.section(section);
        FriendList.allFriendsPage(page, OWN, NO_SEARCH);
    }

    /** True when your own list, read to its end, is kept. Starts from nothing kept. */
    public static boolean keepsAList() {
        FriendList.runner = Runnable::run;
        FriendList.forget();
        load(yours(), page(false, friend("100001", "Ann Lee"), friend("100002", "Bo Park")));
        boolean kept = FriendList.keptCount() == 2;
        FriendList.forget();
        return kept;
    }

    /** Forgets the kept list and everything stored, as a fresh install would start. */
    public static void newInstall() {
        FriendList.runner = Runnable::run;
        FriendList.forget();
    }
}
