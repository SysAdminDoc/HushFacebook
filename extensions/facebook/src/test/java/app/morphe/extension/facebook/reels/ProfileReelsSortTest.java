/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Most viewed first on a profile's Reels tab (#232): off, the list is Facebook's. On, the loaded
 * reel nodes are sorted in place by the play count Facebook's own reader gives, equal counts and
 * reels with no count keep their places, and a list the reader can't make sense of is left alone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ProfileReelsSortTest {
    /** What Facebook's reader takes: the reel's video story. */
    public interface Story {
    }

    /** The node's middle object, answering the story. */
    public interface Middle {
        Story story();
    }

    /** A list node, answering its middle object. */
    public static final class Node {
        final Integer views;
        final String tag;

        Node(String tag, Integer views) {
            this.tag = tag;
            this.views = views;
        }

        public Middle middle() {
            return () -> views == null ? null : new Counted(views);
        }

        @Override
        public String toString() {
            return tag;
        }
    }

    /** A story that remembers its count. */
    static final class Counted implements Story {
        final int views;

        Counted(int views) {
            this.views = views;
        }
    }

    /** Where the reader lives. */
    public static final class Reader {
        public static Integer other(Story story) {
            return 1;
        }

        public static Integer read(Story story) {
            return story instanceof Counted ? ((Counted) story).views : null;
        }
    }

    /** A node whose shape the sort can't follow. */
    public static final class Odd {
    }

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.PROFILE_REELS_MOST_VIEWED.resetToDefault();
        HookStatus.clear();
    }

    private static List<Object> nodes() {
        return new ArrayList<>(Arrays.<Object>asList(
                new Node("a", 5), new Node("none", null), new Node("b", 90), new Node("c", 5), new Node("d", 1200)));
    }

    private static String order(List<?> list) {
        StringBuilder out = new StringBuilder();
        for (Object item : list) out.append(item).append(' ');
        return out.toString().trim();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.NEWEST_REELS_FIRST + ":")) return line;
        }
        return "";
    }

    @Test
    public void offKeepsFacebooksOrder() {
        assertFalse(Settings.PROFILE_REELS_MOST_VIEWED.defaultValue);
        assertTrue(PatchFamily.NEWEST_REELS_FIRST.switches.contains(Settings.PROFILE_REELS_MOST_VIEWED));
        List<Object> list = nodes();
        ProfileReelsSort.sort(list, Reader.class, "read");
        assertEquals("a none b c d", order(list));
    }

    @Test
    public void onSortsByViewsAndKeepsTiesAndTheUnreadInPlace() {
        Settings.PROFILE_REELS_MOST_VIEWED.save(true);
        List<Object> list = nodes();
        ProfileReelsSort.sort(list, Reader.class, "read");
        assertEquals("d b a c none", order(list));
        assertTrue(statusLine().contains(ProfileReelsSort.SORTED + " 1"));
    }

    @Test
    public void theReaderIsPickedByName() {
        Settings.PROFILE_REELS_MOST_VIEWED.save(true);
        List<Object> list = nodes();
        ProfileReelsSort.sort(list, Reader.class, "other");
        assertEquals("a b c d none", order(list));
    }

    @Test
    public void aListItCannotReadIsLeftAlone() {
        Settings.PROFILE_REELS_MOST_VIEWED.save(true);
        List<Object> list = new ArrayList<>(Arrays.<Object>asList(new Odd(), new Odd()));
        Object first = list.get(0);
        ProfileReelsSort.sort(list, Reader.class, "read");
        assertTrue(list.get(0) == first);
        ProfileReelsSort.sort(nodes(), Reader.class, "missing");
        ProfileReelsSort.sort(null, Reader.class, "read");
        ProfileReelsSort.sort(new ArrayList<>(), null, null);
        List<Object> one = new ArrayList<>(Arrays.<Object>asList(new Node("x", 3)));
        ProfileReelsSort.sort(one, Reader.class, "read");
        assertEquals("x", order(one));
    }

    /** The profile's paginated list: its loaded rows plus a paging flag the copy must keep. */
    public static final class Paged {
        final List<Object> rows;
        final boolean more;

        Paged(List<Object> rows, boolean more) {
            this.rows = rows;
            this.more = more;
        }

        public List<Object> underlyingList() {
            return rows;
        }

        public static Paged withMetadata(List<Object> rows, Paged from) {
            return new Paged(rows, from.more);
        }
    }

    @Test
    public void listOffHandsBackFacebooksList() {
        Paged paged = new Paged(nodes(), true);
        assertTrue(ProfileReelsSort.list(paged, Reader.class, "read") == paged);
        assertFalse(statusLine().contains(ProfileReelsSort.SORTED));
    }

    @Test
    public void listOnHandsBackASortedCopyWithTheSamePaging() {
        Settings.PROFILE_REELS_MOST_VIEWED.save(true);
        Paged paged = new Paged(nodes(), true);
        Paged copy = (Paged) ProfileReelsSort.list(paged, Reader.class, "read");
        assertEquals("d b a c none", order(copy.rows));
        assertTrue(copy.more);
        assertEquals("Facebook's own list is left as it was", "a none b c d", order(paged.rows));
        assertTrue(statusLine().contains(ProfileReelsSort.SORTED + " 1"));
    }

    @Test
    public void listLeftAloneSaysWhy() {
        Settings.PROFILE_REELS_MOST_VIEWED.save(true);
        assertTrue(ProfileReelsSort.list(null, Reader.class, "read") == null);
        assertTrue(statusLine().contains(ProfileReelsSort.NO_LIST));

        Paged one = new Paged(new ArrayList<>(Arrays.<Object>asList(new Node("x", 3))), false);
        assertTrue(ProfileReelsSort.list(one, Reader.class, "read") == one);
        assertTrue(statusLine().contains(ProfileReelsSort.TOO_SHORT));

        Paged odd = new Paged(new ArrayList<>(Arrays.<Object>asList(new Odd(), new Odd())), false);
        assertTrue(ProfileReelsSort.list(odd, Reader.class, "read") == odd);
        assertTrue(statusLine().contains(ProfileReelsSort.NO_PATH));

        Paged ordered = new Paged(new ArrayList<>(Arrays.<Object>asList(new Node("p", 9), new Node("q", 2))), false);
        assertTrue(ProfileReelsSort.list(ordered, Reader.class, "read") == ordered);
        assertTrue(statusLine().contains(ProfileReelsSort.IN_ORDER));

        assertTrue(ProfileReelsSort.list(ordered, Reader.class, "missing") == ordered);
        assertTrue(statusLine().contains("threw"));
    }

    @Test
    public void theNodePathIsFoundThroughSeveralInterfaces() {
        assertEquals(2, ProfileReelsSort.search(Node.class, Story.class).length);
        assertTrue(ProfileReelsSort.search(Odd.class, Story.class) == null);
    }
}
