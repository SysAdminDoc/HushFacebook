/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Don't save search history (#196): with its switch on, every search Facebook would save is
 * answered as one it doesn't, and counted. A search Facebook already keeps out stays out and isn't
 * counted. Off, paused, or before the settings are ready, Facebook's own answer comes back. The
 * same goes for the Recent list: on, a search doesn't go to its top; otherwise Facebook's put runs.
 * And for profile visits (#221): on, the visit event isn't logged and every other event is. A
 * profile opened from a story stays out while View stories anonymously is on as well.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SearchHistoryTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.DONT_SAVE_SEARCH_HISTORY.resetToDefault();
        Settings.VIEW_STORIES_ANONYMOUSLY.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.SEARCH_HISTORY + ":")) return line;
        }
        return null;
    }

    @Test
    public void onEverySearchIsKeptOutAndCounted() {
        Settings.DONT_SAVE_SEARCH_HISTORY.save(true);
        assertTrue("a search was saved", SearchHistory.skipSave(false));
        assertTrue("a search Facebook keeps out was saved", SearchHistory.skipSave(true));
        assertEquals(FamilyNames.SEARCH_HISTORY + ": invoked 2, 1 found, 0 missing. Counted: "
                + SearchHistory.KEPT_OUT + " 1", statusLine());
    }

    @Test
    public void offFacebooksOwnAnswerComesBack() {
        assertFalse("off, a search was kept out", SearchHistory.skipSave(false));
        assertTrue("off, a search Facebook keeps out was saved", SearchHistory.skipSave(true));
        assertFalse("off, a search was counted", String.valueOf(statusLine()).contains(SearchHistory.KEPT_OUT));
    }

    @Test
    public void pausedFacebooksOwnAnswerComesBack() {
        Settings.DONT_SAVE_SEARCH_HISTORY.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("paused, a search was kept out", SearchHistory.skipSave(false));
        assertTrue("paused, a search Facebook keeps out was saved", SearchHistory.skipSave(true));
    }

    @Test
    public void beforeTheSettingsAreReadyFacebooksOwnAnswerComesBack() {
        Settings.DONT_SAVE_SEARCH_HISTORY.save(true);
        SettingsContextRule.withoutContext(() -> {
            assertFalse("before the settings were ready, a search was kept out", SearchHistory.skipSave(false));
            assertTrue(SearchHistory.skipSave(true));
        });
    }

    @Test
    public void onTheRecentListStaysAsItWas() {
        Settings.DONT_SAVE_SEARCH_HISTORY.save(true);
        Map<Object, Object> recent = new HashMap<>();
        recent.put("filter", "before");
        SearchHistory.keepRecent(recent, "filter", "with the search on top");
        assertEquals("before", recent.get("filter"));
        assertTrue(String.valueOf(statusLine()).contains(SearchHistory.KEPT_OFF_RECENT + " 1"));
    }

    @Test
    public void offTheRecentListTakesTheSearch() {
        Map<Object, Object> recent = new HashMap<>();
        recent.put("filter", "before");
        SearchHistory.keepRecent(recent, "filter", "with the search on top");
        assertEquals("with the search on top", recent.get("filter"));
        assertFalse(String.valueOf(statusLine()).contains(SearchHistory.KEPT_OFF_RECENT));
    }

    @Test
    public void pausedOrBeforeTheSettingsAreReadyTheRecentListTakesTheSearch() {
        Settings.DONT_SAVE_SEARCH_HISTORY.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        Map<Object, Object> paused = new HashMap<>();
        SearchHistory.keepRecent(paused, "filter", "list");
        assertEquals("paused", "list", paused.get("filter"));
        PauseForTests.resume();
        Map<Object, Object> early = new HashMap<>();
        SettingsContextRule.withoutContext(() -> SearchHistory.keepRecent(early, "filter", "list"));
        assertEquals("before the settings were ready", "list", early.get("filter"));
    }

    @Test
    public void aMapThatRefusesThePutStillRefusesIt() {
        Map<Object, Object> refuses = Collections.unmodifiableMap(new HashMap<>());
        assertThrows(UnsupportedOperationException.class, () -> SearchHistory.keepRecent(refuses, "filter", "list"));
    }

    @Test
    public void onAProfileVisitIsKeptOutAndCounted() {
        Settings.DONT_SAVE_SEARCH_HISTORY.save(true);
        assertTrue("a profile visit was logged", SearchHistory.keepVisitOut(SearchHistory.PROFILE_VISIT));
        assertTrue(String.valueOf(statusLine()).contains(SearchHistory.KEPT_VISIT_OUT + " 1"));
    }

    @Test
    public void onAProfileOpenedFromAStoryIsKeptOutToo() {
        Settings.DONT_SAVE_SEARCH_HISTORY.save(true);
        assertTrue("a profile opened from a story was logged", SearchHistory.keepVisitOut(SearchHistory.STORY_PROFILE_VISIT));
        assertTrue(String.valueOf(statusLine()).contains(SearchHistory.KEPT_VISIT_OUT + " 1"));
    }

    @Test
    public void withAnonymousStoriesAProfileOpenedFromAStoryIsKeptOut() {
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
        assertTrue("a profile opened from an anonymous story was logged",
                SearchHistory.keepVisitOut(SearchHistory.STORY_PROFILE_VISIT));
        assertFalse("anonymous stories kept an ordinary profile visit out",
                SearchHistory.keepVisitOut(SearchHistory.PROFILE_VISIT));
        assertTrue(String.valueOf(statusLine()).contains(SearchHistory.KEPT_VISIT_OUT + " 1"));
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("paused", SearchHistory.keepVisitOut(SearchHistory.STORY_PROFILE_VISIT));
    }

    @Test
    public void onEveryOtherEventIsLogged() {
        Settings.DONT_SAVE_SEARCH_HISTORY.save(true);
        assertFalse(SearchHistory.keepVisitOut("profile_engagement_events"));
        assertFalse(SearchHistory.keepVisitOut("merlin_unified_protocol_event"));
        assertFalse(SearchHistory.keepVisitOut(null));
        assertNull("another event was reported", statusLine());
    }

    @Test
    public void offPausedOrBeforeTheSettingsAreReadyAProfileVisitIsLogged() {
        assertFalse("off", SearchHistory.keepVisitOut(SearchHistory.PROFILE_VISIT));
        Settings.DONT_SAVE_SEARCH_HISTORY.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("paused", SearchHistory.keepVisitOut(SearchHistory.PROFILE_VISIT));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
                assertFalse("before the settings were ready", SearchHistory.keepVisitOut(SearchHistory.PROFILE_VISIT)));
        assertFalse(String.valueOf(statusLine()).contains(SearchHistory.KEPT_VISIT_OUT));
    }

    @Test
    public void startsOff() {
        Settings.DONT_SAVE_SEARCH_HISTORY.resetToDefault();
        assertFalse(Settings.DONT_SAVE_SEARCH_HISTORY.get());
        assertNull("nothing was reported before a search", statusLine());
    }
}
