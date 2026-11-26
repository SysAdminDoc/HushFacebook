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
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Search posts tab (#339): with its switch on, the row of chips over search results gains a Posts
 * chip after Reels, labelled as Facebook's own table labels its posts tab, so a tap opens the
 * posts results. A row that already has Posts, a table with no posts tab, and every row with the
 * switch off or paused stay as they came.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SearchPostsTabTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for Facebook's tab enum. */
    public enum Tab {
        UNSET_OR_UNRECOGNIZED_ENUM_VALUE, BLENDED, BLENDED_STORIES, REELS, USERS, GROUPS, BLENDED_PHOTOS,
        BLENDED_SHOWS_HOME
    }

    /** Stands in for a chip: selected, then its id and label, as Facebook makes them. */
    public static final class Chip {
        final String id;
        final String label;
        final boolean selected;

        public Chip(boolean selected, String id, String label) {
            this.selected = selected;
            this.id = id;
            this.label = label;
        }
    }

    /** Facebook's table, label to tab, as the tap handler reads it. */
    private static Map<String, Tab> labels() {
        Map<String, Tab> out = new LinkedHashMap<>();
        out.put("All", Tab.BLENDED);
        out.put("Posts", Tab.BLENDED_STORIES);
        out.put("People", Tab.USERS);
        out.put("Groups", Tab.GROUPS);
        out.put("Reels", Tab.REELS);
        out.put("Photos", Tab.BLENDED_PHOTOS);
        out.put("Videos", Tab.BLENDED_SHOWS_HOME);
        return out;
    }

    @Before
    public void start() {
        HookStatus.clear();
        SearchPostsTab.testLabels = labels();
    }

    @After
    public void restore() {
        SearchPostsTab.testLabels = null;
        PauseForTests.resume();
        Settings.SEARCH_POSTS_TAB.resetToDefault();
        Settings.SEARCH_VIDEOS_TAB.resetToDefault();
        SearchPostsTab.tapped = null;
        HookStatus.clear();
    }

    private static List<Chip> row(String... labels) {
        List<Chip> out = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) out.add(new Chip(i == 0, labels[i], labels[i]));
        return out;
    }

    private static List<String> labelsOf(List<?> chips) {
        List<String> out = new ArrayList<>();
        for (Object chip : chips) out.add(((Chip) chip).label);
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
        List<Chip> served = row("All", "Reels", "People", "Groups");
        List<?> out = SearchPostsTab.chips(served);
        assertEquals(Arrays.asList("All", "Reels", "Posts", "People", "Groups"), labelsOf(out));
        for (int i = 0; i < served.size(); i++) {
            assertSame("Facebook's own chips stay, in order", served.get(i), out.get(i < 2 ? i : i + 1));
        }
        Chip posts = (Chip) out.get(2);
        assertEquals("the id is the label, as Facebook's chips have it", "Posts", posts.id);
        assertFalse(posts.selected);
        String line = statusLine();
        assertNotNull(line);
        assertTrue(line, line.contains(SearchPostsTab.ADDED));
    }

    @Test
    public void postsIsMarkedWhenNoChipIs() {
        List<Chip> served = row("All", "Reels", "People");
        for (int i = 0; i < served.size(); i++) served.set(i, new Chip(false, served.get(i).id, served.get(i).label));
        List<?> out = SearchPostsTab.chips(served);
        assertEquals(Arrays.asList("All", "Reels", "Posts", "People"), labelsOf(out));
        assertTrue("the posts results' row marks Posts", ((Chip) out.get(2)).selected);

        List<Chip> reelsOpen = row("All", "Reels", "People");
        reelsOpen.set(0, new Chip(false, "All", "All"));
        reelsOpen.set(1, new Chip(true, "Reels", "Reels"));
        assertFalse("another tab's row leaves Posts unmarked", ((Chip) SearchPostsTab.chips(reelsOpen).get(2)).selected);
    }

    @Test
    public void withoutReelsPostsGoesAfterTheFirstChip() {
        assertEquals(Arrays.asList("All", "Posts", "People", "Groups"),
                labelsOf(SearchPostsTab.chips(row("All", "People", "Groups"))));
        assertEquals(Arrays.asList("All", "Posts"), labelsOf(SearchPostsTab.chips(row("All"))));
    }

    @Test
    public void theTableIsReadEitherWayRound() {
        Map<Tab, String> byTab = new LinkedHashMap<>();
        byTab.put(Tab.REELS, "Reels");
        byTab.put(Tab.BLENDED_STORIES, "Posts");
        SearchPostsTab.testLabels = byTab;
        assertEquals(Arrays.asList("All", "Reels", "Posts", "People"),
                labelsOf(SearchPostsTab.chips(row("All", "Reels", "People"))));
        assertEquals("Posts", SearchPostsTab.labelOf(labels(), SearchPostsTab.POSTS_TAB));
        assertNull(SearchPostsTab.labelOf(null, SearchPostsTab.POSTS_TAB));
    }

    @Test
    public void aRowWithPostsOrATableWithoutItStays() {
        List<Chip> withPosts = row("All", "Reels", "Posts", "People");
        assertSame(withPosts, SearchPostsTab.chips(withPosts));
        Map<String, Tab> noPosts = labels();
        noPosts.remove("Posts");
        SearchPostsTab.testLabels = noPosts;
        List<Chip> served = row("All", "Reels");
        assertSame(served, SearchPostsTab.chips(served));
        List<Chip> empty = new ArrayList<>();
        assertSame(empty, SearchPostsTab.chips(empty));
        assertNull(SearchPostsTab.chips(null));
        assertFalse("nothing was added", String.valueOf(statusLine()).contains(SearchPostsTab.ADDED));
    }

    @Test
    public void aChipKindWithoutTheConstructorStays() {
        List<String> notChips = Arrays.asList("All", "Reels");
        assertSame(notChips, SearchPostsTab.chips(notChips));
    }

    @Test
    public void offOrPausedTheRowStays() {
        List<Chip> served = row("All", "Reels", "People");
        Settings.SEARCH_POSTS_TAB.save(false);
        assertSame(served, SearchPostsTab.chips(served));
        Settings.SEARCH_POSTS_TAB.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertSame(served, SearchPostsTab.chips(served));
        PauseForTests.resume();
        assertEquals(4, SearchPostsTab.chips(served).size());
    }

    @Test
    public void unpatchedThereIsNoTableAndTheRowStays() {
        SearchPostsTab.testLabels = null;
        assertNull(SearchPostsTab.tabLabels(RuntimeEnvironment.getApplication()));
        List<Chip> served = row("All", "Reels");
        assertSame(served, SearchPostsTab.chips(served));
    }

    private static List<Chip> unmarked(String... labels) {
        List<Chip> out = new ArrayList<>();
        for (String label : labels) out.add(new Chip(false, label, label));
        return out;
    }

    /**
     * Videos (#483) follows Posts, labelled as the table labels its tab, and both are counted. The
     * table's Photos label gets no chip: Facebook answers its photo tabs with nothing.
     */
    @Test
    public void videosGoesInAfterPosts() {
        Settings.SEARCH_VIDEOS_TAB.save(true);
        List<Chip> served = row("All", "Reels", "People");
        List<?> out = SearchPostsTab.chips(served);
        assertEquals(Arrays.asList("All", "Reels", "Posts", "Videos", "People"), labelsOf(out));
        assertSame("Facebook's own chips stay", served.get(2), out.get(4));
        assertEquals("the id is the label", "Videos", ((Chip) out.get(3)).id);
        String line = statusLine();
        assertNotNull(line);
        for (String counted : Arrays.asList(SearchPostsTab.ADDED, SearchPostsTab.VIDEOS_ADDED)) {
            assertTrue(line, line.contains(counted + " 1"));
        }
    }

    @Test
    public void eachChipAnswersToItsOwnSwitch() {
        Settings.SEARCH_POSTS_TAB.save(false);
        Settings.SEARCH_VIDEOS_TAB.save(true);
        assertEquals("with Posts off, Videos goes after Reels", Arrays.asList("All", "Reels", "Videos", "People"),
                labelsOf(SearchPostsTab.chips(row("All", "Reels", "People"))));
        assertEquals(Arrays.asList("All", "Videos", "People"), labelsOf(SearchPostsTab.chips(row("All", "People"))));
        Settings.SEARCH_VIDEOS_TAB.save(false);
        List<Chip> served = row("All", "Reels");
        assertSame("both off, the row stays", served, SearchPostsTab.chips(served));
    }

    @Test
    public void aChipTheRowHasIsNotAddedAgainAndTheNextFollowsIt() {
        Settings.SEARCH_VIDEOS_TAB.save(true);
        assertEquals(Arrays.asList("All", "Posts", "Videos", "Reels"),
                labelsOf(SearchPostsTab.chips(row("All", "Posts", "Reels"))));
        List<Chip> full = row("All", "Reels", "Posts", "Videos");
        assertSame(full, SearchPostsTab.chips(full));
    }

    @Test
    public void aTableWithoutVideosStillAddsPosts() {
        Settings.SEARCH_VIDEOS_TAB.save(true);
        Map<String, Tab> noVideos = labels();
        noVideos.remove("Videos");
        SearchPostsTab.testLabels = noVideos;
        assertEquals(Arrays.asList("All", "Reels", "Posts"), labelsOf(SearchPostsTab.chips(row("All", "Reels"))));
    }

    /**
     * A tap rebuilds the row from Facebook's own chips, so the added tab it opened comes with none
     * marked. With one chip added that one is marked, and with both the one tapped last.
     */
    @Test
    public void withNoneMarkedTheTappedChipIsMarked() {
        Settings.SEARCH_VIDEOS_TAB.save(true);
        SearchPostsTab.tapped("Videos");
        List<?> out = SearchPostsTab.chips(unmarked("All", "Reels", "People"));
        assertEquals(Arrays.asList("All", "Reels", "Posts", "Videos", "People"), labelsOf(out));
        assertFalse("Posts was marked", ((Chip) out.get(2)).selected);
        assertTrue("the tapped Videos wasn't marked", ((Chip) out.get(3)).selected);

        SearchPostsTab.tapped("Posts");
        out = SearchPostsTab.chips(unmarked("All", "Reels", "People"));
        assertTrue("the tapped Posts wasn't marked", ((Chip) out.get(2)).selected);
        assertFalse("Videos was marked", ((Chip) out.get(3)).selected);

        SearchPostsTab.tapped("People");
        for (Object chip : SearchPostsTab.chips(unmarked("All", "Reels", "People"))) {
            assertFalse("a tap on Facebook's own chip marked an added one", ((Chip) chip).selected);
        }

        SearchPostsTab.tapped("Videos");
        List<?> facebookMarked = SearchPostsTab.chips(row("All", "Reels", "People"));
        assertFalse("a row Facebook marked got an added chip marked", ((Chip) facebookMarked.get(3)).selected);

        Settings.SEARCH_VIDEOS_TAB.save(false);
        assertTrue("with Posts alone it's marked whatever was tapped",
                ((Chip) SearchPostsTab.chips(unmarked("All", "Reels")).get(2)).selected);
    }

    @Test
    public void theTapHookKeepsTheLabel() {
        SearchPostsTab.tapped("Videos");
        assertEquals("Videos", SearchPostsTab.tapped);
        SearchPostsTab.tapped(null);
        assertNull(SearchPostsTab.tapped);
        String line = statusLine();
        assertNotNull(line);
        assertTrue("the report doesn't count the tap hook as found: " + line, line.contains("1 found"));
    }

    @Test
    public void videosIsOffByDefault() {
        assertFalse(Settings.SEARCH_VIDEOS_TAB.get());
    }

    @Test
    public void onByDefault() {
        assertTrue(Settings.SEARCH_POSTS_TAB.get());
    }
}
