/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Search posts tab (#339): with its switch on, a search answer's entries gain one naming Posts
 * after Reels, which answers Posts through the same interface call Facebook reads the tab with,
 * and nothing through the rest. An answer without tabs, one that already has Posts, and every
 * answer with the switch off or paused stay as they came.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SearchPostsTabTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for Facebook's tab enum. */
    public enum Tab {
        UNSET_OR_UNRECOGNIZED_ENUM_VALUE, TOP, POSTS, REELS, PEOPLE, GROUPS, EVENTS
    }

    /** Stands in for an entry's interface: the tab, and other answers Facebook reads from a filter. */
    public interface Entry {
        Tab tab();

        Object filter();

        boolean selected();

        int position();
    }

    /** An answer's entry naming [tab], or none for a chip like All. */
    public static final class Served implements Entry {
        private final Tab tab;

        public Served(Tab tab) {
            this.tab = tab;
        }

        @Override
        public Tab tab() {
            return tab;
        }

        @Override
        public Object filter() {
            return "filter";
        }

        @Override
        public boolean selected() {
            return true;
        }

        @Override
        public int position() {
            return 7;
        }
    }

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.SEARCH_POSTS_TAB.resetToDefault();
        HookStatus.clear();
    }

    private static List<Entry> chips(Tab... tabs) {
        List<Entry> out = new ArrayList<>();
        for (Tab tab : tabs) out.add(new Served(tab));
        return out;
    }

    private static List<Tab> tabsOf(List<?> entries) {
        List<Tab> out = new ArrayList<>();
        for (Object entry : entries) out.add(((Entry) entry).tab());
        return out;
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.SEARCH_POSTS_TAB + ":")) return line;
        }
        return null;
    }

    @Test
    public void postsGoesInAfterReels() {
        List<Entry> served = chips(null, Tab.REELS, Tab.PEOPLE, Tab.GROUPS, Tab.EVENTS);
        List<?> out = SearchPostsTab.edges(served);
        assertEquals(Arrays.asList(null, Tab.REELS, Tab.POSTS, Tab.PEOPLE, Tab.GROUPS, Tab.EVENTS), tabsOf(out));
        for (int i = 0; i < served.size(); i++) {
            assertTrue("Facebook's own entries stay, in order", out.get(i < 2 ? i : i + 1) == served.get(i));
        }
        Entry posts = (Entry) out.get(2);
        assertNull("the added entry has no filter of its own", posts.filter());
        assertFalse(posts.selected());
        assertEquals(0, posts.position());
        assertFalse("the added entry is only itself", posts.equals(served.get(1)));
        assertTrue(posts.equals(posts));
        String line = statusLine();
        assertNotNull(line);
        assertTrue(line, line.contains(SearchPostsTab.ADDED));
    }

    @Test
    public void withoutReelsPostsGoesBeforeTheFirstTab() {
        assertEquals(Arrays.asList(null, Tab.POSTS, Tab.PEOPLE, Tab.GROUPS),
                tabsOf(SearchPostsTab.edges(chips(null, Tab.PEOPLE, Tab.GROUPS))));
        assertEquals(Arrays.asList(Tab.TOP, Tab.POSTS, Tab.PEOPLE),
                tabsOf(SearchPostsTab.edges(chips(Tab.TOP, Tab.PEOPLE))));
        assertEquals(Arrays.asList(Tab.UNSET_OR_UNRECOGNIZED_ENUM_VALUE, Tab.POSTS, Tab.GROUPS),
                tabsOf(SearchPostsTab.edges(chips(Tab.UNSET_OR_UNRECOGNIZED_ENUM_VALUE, Tab.GROUPS))));
    }

    @Test
    public void answersWithPostsOrWithoutTabsStay() {
        List<Entry> withPosts = chips(null, Tab.REELS, Tab.POSTS, Tab.PEOPLE);
        assertSame(withPosts, SearchPostsTab.edges(withPosts));
        List<Entry> filters = chips(null, null, Tab.UNSET_OR_UNRECOGNIZED_ENUM_VALUE);
        assertSame(filters, SearchPostsTab.edges(filters));
        List<Entry> empty = new ArrayList<>();
        assertSame(empty, SearchPostsTab.edges(empty));
        assertNull(SearchPostsTab.edges(null));
        List<String> notEntries = Arrays.asList("a", "b");
        assertSame(notEntries, SearchPostsTab.edges(notEntries));
    }

    @Test
    public void offOrPausedTheAnswerStays() {
        List<Entry> served = chips(null, Tab.REELS, Tab.PEOPLE);
        Settings.SEARCH_POSTS_TAB.save(false);
        assertSame(served, SearchPostsTab.edges(served));
        Settings.SEARCH_POSTS_TAB.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertSame(served, SearchPostsTab.edges(served));
        PauseForTests.resume();
        assertEquals(4, SearchPostsTab.edges(served).size());
    }

    @Test
    public void onByDefault() {
        assertTrue(Settings.SEARCH_POSTS_TAB.get());
    }

    @Test
    public void theTabIsReadThroughTheOneMethodAnsweringTheEnum() throws Exception {
        assertEquals("tab", SearchPostsTab.tabMethod(new Served(Tab.REELS)).getName());
        assertNull(SearchPostsTab.tabMethod("not an entry"));
        assertTrue(SearchPostsTab.isTabEnum(Tab.class));
        assertFalse(SearchPostsTab.isTabEnum(HushfacebookPause.Reason.class));
    }
}
