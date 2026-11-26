/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import static app.morphe.extension.facebook.feed.FriendsTabSuggestionsForTests.header;
import static app.morphe.extension.facebook.feed.FriendsTabSuggestionsForTests.row;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The Friends tab hook (#600): with Hide "People you may know" in the Friends tab on, the People you
 * may know header and every row under it leave the tab's list, friend requests and anything under
 * another header stay, and the list comes back as Facebook made it with the switch off, while
 * paused, before the settings are ready, and for rows it can't read.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FriendsTabSuggestionsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** A row with no tree getters at all, which no row of the tab is. */
    public static final class NotATree {
    }

    /** A row whose type getter fails, as a released tree's native call can. */
    public static final class BrokenTree {
        public String getTypeName() {
            throw new IllegalStateException("released");
        }

        public String getString(int key) {
            return null;
        }
    }

    @Before
    public void startClean() {
        FriendsTabSuggestionsForTests.newProcess();
        FeedFilterCounters.clear();
        HookStatus.clear();
        Settings.HIDE_FRIENDS_TAB_PEOPLE_YOU_MAY_KNOW.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_FRIENDS_TAB_PEOPLE_YOU_MAY_KNOW.resetToDefault();
        FriendsTabSuggestionsForTests.newProcess();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    /** The section type key is the one the 588 tab reads a header's type with (0x368f3a, "type".hashCode()). */
    @Test
    public void theHeaderIsReadByTheTabsOwnKey() {
        assertEquals(0x368f3a, FriendsTabSuggestions.SECTION_TYPE_KEY);
    }

    /** The S22's tab on 588: the request and its header stay, People you may know and its people go. */
    @Test
    public void theSuggestionsLeaveAndTheRequestsStay() {
        List<Object> tab = FriendsTabSuggestionsForTests.tab();
        List<?> kept = FriendsTabSuggestions.rows(tab);
        assertEquals(Arrays.asList(tab.get(0), tab.get(1)), kept);
        assertTrue("the hook says it's live", HookStatus.missing(FamilyNames.SUGGESTED_POSTS_FRIENDS_TAB).isEmpty());
    }

    /** Everything under the suggestions' header goes until the next header, whose rows come back. */
    @Test
    public void aLaterSectionComesBack() {
        Object friends = header("FRIENDS_LIST");
        Object friend = row("FriendFriendingTabRow");
        Object footer = row("BloksFriendingTabRow");
        List<Object> tab = Arrays.asList(header(FriendsTabSuggestions.SECTION_TYPE), row(FriendsTabSuggestions.PERSON),
                footer, friends, friend);
        assertEquals(Arrays.asList(friends, friend), FriendsTabSuggestions.rows(tab));
    }

    /** A suggested person's row goes even where no header opened the suggestions. */
    @Test
    public void aStraySuggestionGoesToo() {
        Object requests = header("FRIEND_REQUESTS");
        Object request = row("FriendRequestsFriendingTabRow");
        List<Object> tab = Arrays.asList(row(FriendsTabSuggestions.PERSON), requests, request);
        assertEquals(Arrays.asList(requests, request), FriendsTabSuggestions.rows(tab));
    }

    /** A tab with nothing to take out is handed back as the same list, so ImmutableList.copyOf keeps Facebook's. */
    @Test
    public void nothingToTakeOutIsFacebooksList() {
        List<Object> tab = Arrays.asList(header("FRIEND_REQUESTS"), row("FriendRequestsFriendingTabRow"));
        assertSame(tab, FriendsTabSuggestions.rows(tab));
    }

    @Test
    public void switchedOffTheTabIsFacebooks() {
        Settings.HIDE_FRIENDS_TAB_PEOPLE_YOU_MAY_KNOW.save(false);
        List<Object> tab = FriendsTabSuggestionsForTests.tab();
        assertSame(tab, FriendsTabSuggestions.rows(tab));
    }

    @Test
    public void pausedTheSuggestionsComeBack() {
        for (HushfacebookPause.Reason why : HushfacebookPause.Reason.values()) {
            if (why == HushfacebookPause.Reason.NONE) continue;
            PauseForTests.pause(why);
            assertFalse("paused by " + why, FriendsTabSuggestionsForTests.dropsTheSuggestions());
        }
        PauseForTests.resume();
        assertTrue(FriendsTabSuggestionsForTests.dropsTheSuggestions());
    }

    /** Until the settings are ready, the tab is Facebook's. */
    @Test
    public void untilTheSettingsAreReadyTheTabIsFacebooks() {
        boolean[] dropped = {true, true};
        SettingsContextRule.withoutContext(() -> dropped[0] = FriendsTabSuggestionsForTests.dropsTheSuggestions());
        SettingsContextRule.beforeThePauseIsDecided(() -> dropped[1] = FriendsTabSuggestionsForTests.dropsTheSuggestions());
        assertFalse(dropped[0]);
        assertFalse(dropped[1]);
        assertTrue(FriendsTabSuggestionsForTests.dropsTheSuggestions());
    }

    /** Rows it can't read leave the whole list as it came, and the report says why. */
    @Test
    public void unreadableRowsLeaveTheTabAlone() {
        List<Object> odd = Arrays.asList(header(FriendsTabSuggestions.SECTION_TYPE), new NotATree());
        assertSame(odd, FriendsTabSuggestions.rows(odd));
        assertFalse("the miss is reported", HookStatus.missing(FamilyNames.SUGGESTED_POSTS_FRIENDS_TAB).isEmpty());
        List<Object> broken = Arrays.asList(new BrokenTree(), row(FriendsTabSuggestions.PERSON));
        assertSame(broken, FriendsTabSuggestions.rows(broken));
    }

    @Test
    public void noListIsNoList() {
        assertEquals(null, FriendsTabSuggestions.rows(null));
        List<Object> empty = Arrays.asList();
        assertSame(empty, FriendsTabSuggestions.rows(empty));
    }
}
