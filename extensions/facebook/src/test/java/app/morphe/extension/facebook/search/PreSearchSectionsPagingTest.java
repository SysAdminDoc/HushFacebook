/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.search;

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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * #235: a search screen whose every loaded row is hidden keeps its end in view and pages forever.
 * While a hide switch is on and every row is hidden, the section's list is handed over without a
 * next page. Any visible row, both switches off or Hushfacebook paused leaves the list alone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PreSearchSectionsPagingTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for Facebook's entry: answers its node through the accessor the patch names. */
    public static class Entry {
        private final PreSearchSectionsTest.Tree node;

        Entry(String modules) {
            node = modules == null ? null
                    : new PreSearchSectionsTest.Tree(PreSearchSections.NULL_STATE_NODE, true, modules);
        }

        public Object node() {
            return node;
        }
    }

    /** Stands in for Facebook's PaginableList. */
    public static class Paged {
        final List<Object> rows;
        final boolean next;

        Paged(boolean next, Object... rows) {
            this.next = next;
            this.rows = new ArrayList<>(Arrays.asList(rows));
        }

        public boolean hasNextPage() {
            return next;
        }

        public List<Object> underlyingList() {
            return rows;
        }

        public static Paged withoutPaging(List<Object> rows) {
            return new Paged(false, rows.toArray());
        }
    }

    @Before
    public void prepare() {
        PreSearchSections.forget();
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        PreSearchSections.forget();
        BaseSettings.DEBUG.resetToDefault();
        Settings.HIDE_SEARCH_FOR_YOU.resetToDefault();
        Settings.HIDE_SEARCH_PEOPLE_YOU_MAY_KNOW.resetToDefault();
        LogBufferManager.clearLogBuffer();
        HookStatus.clear();
    }

    private static Entry forYou() {
        return new Entry(PreSearchSections.FOR_YOU_MODULE);
    }

    private static Entry people() {
        return new Entry(PreSearchSections.PEOPLE_YOU_MAY_KNOW_MODULE);
    }

    private static Object page(Object list) {
        return PreSearchSections.list(list, "node");
    }

    @Test
    public void everyRowHiddenStopsPaging() {
        Settings.HIDE_SEARCH_FOR_YOU.save(true);
        Settings.HIDE_SEARCH_PEOPLE_YOU_MAY_KNOW.save(true);
        Paged list = new Paged(true, forYou(), people(), forYou());
        Paged answered = (Paged) page(list);
        assertFalse(answered.hasNextPage());
        assertTrue(answered.rows.equals(list.rows));
        assertTrue(LogBufferManager.buildExportText().contains(PreSearchSections.PAGING_STOPPED));
    }

    @Test
    public void anEntryWithNoNodeCountsAsHidden() {
        Settings.HIDE_SEARCH_FOR_YOU.save(true);
        assertFalse(((Paged) page(new Paged(true, forYou(), new Entry(null)))).hasNextPage());
    }

    @Test
    public void oneVisibleRowLeavesTheListAlone() {
        Settings.HIDE_SEARCH_FOR_YOU.save(true);
        Settings.HIDE_SEARCH_PEOPLE_YOU_MAY_KNOW.save(true);
        Paged list = new Paged(true, forYou(), new Entry("RECENT_SEARCHES"), people());
        assertSame(list, page(list));
        assertFalse(LogBufferManager.buildExportText().contains(PreSearchSections.PAGING_STOPPED));
    }

    @Test
    public void aRowItsOwnSwitchLeavesIsVisible() {
        Settings.HIDE_SEARCH_FOR_YOU.save(true);
        Paged list = new Paged(true, forYou(), people());
        assertSame("People you may know obeys its own switch", list, page(list));
        Settings.HIDE_SEARCH_FOR_YOU.save(false);
        Settings.HIDE_SEARCH_PEOPLE_YOU_MAY_KNOW.save(true);
        assertSame("For you obeys its own switch", list, page(list));
    }

    @Test
    public void switchesOffOrPausedLeaveTheListAlone() {
        Paged list = new Paged(true, forYou(), people());
        assertSame(list, page(list));
        Settings.HIDE_SEARCH_FOR_YOU.save(true);
        Settings.HIDE_SEARCH_PEOPLE_YOU_MAY_KNOW.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertSame(list, page(list));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertSame(list, page(list)));
        assertFalse(((Paged) page(list)).hasNextPage());
    }

    @Test
    public void aListWithNoNextPageOrNoRowsIsLeftAlone() {
        Settings.HIDE_SEARCH_FOR_YOU.save(true);
        Paged done = new Paged(false, forYou());
        assertSame(done, page(done));
        Paged empty = new Paged(true);
        assertSame(empty, page(empty));
        assertNull(page(null));
    }

    @Test
    public void anUnreadableListIsFacebooks() {
        Settings.HIDE_SEARCH_FOR_YOU.save(true);
        Object odd = new Object();
        assertSame(odd, page(odd));
        Paged list = new Paged(true, forYou());
        assertSame(list, PreSearchSections.list(list, "missing"));
        assertSame(list, PreSearchSections.list(list, null));
    }
}
