/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import static app.morphe.extension.facebook.profile.FriendListForTests.friend;
import static app.morphe.extension.facebook.profile.FriendListForTests.load;
import static app.morphe.extension.facebook.profile.FriendListForTests.page;
import static app.morphe.extension.facebook.profile.FriendListForTests.searched;
import static app.morphe.extension.facebook.profile.FriendListForTests.someoneElses;
import static app.morphe.extension.facebook.profile.FriendListForTests.yours;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.facebook.profile.FriendList.Gone;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Friends no longer on your list (#597): with Keep track of your friends list on, your own All
 * friends list read to its end is kept, and anyone on the kept copy who isn't on the next one read
 * to its end is listed with both dates and named in a toast. Someone else's list, a searched one,
 * one with pages left, and anything while off, paused or before the settings are ready leave
 * everything as it was. A read missing most of the list is set aside until it's read again later.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FriendListTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final long DAY = 24 * 60 * 60 * 1000L;

    @Before
    public void startClean() {
        FriendListForTests.newInstall();
        HookStatus.clear();
        ShadowToast.reset();
        Settings.FRIEND_LIST_WATCH.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.FRIEND_LIST_WATCH.resetToDefault();
        FriendListForTests.newInstall();
        HookStatus.clear();
    }

    private static Map<String, String> friends(String... idsAndNames) {
        Map<String, String> friends = new LinkedHashMap<>();
        for (int index = 0; index < idsAndNames.length; index += 2) friends.put(idsAndNames[index], idsAndNames[index + 1]);
        return friends;
    }

    private static List<String> names(List<Gone> gone) {
        List<String> names = new ArrayList<>();
        for (Gone entry : gone) names.add(entry.name);
        return names;
    }

    /** The keys a 588 tree answers a friend's id and name with. */
    @Test
    public void theRowsAreReadByTheirFieldNames() {
        assertEquals(3355, FriendList.ID_KEY);
        assertEquals(3373707, FriendList.NAME_KEY);
    }

    /** The first time your list is read to its end it's kept, and the toast says so. */
    @Test
    public void theFirstReadToTheEndIsKept() {
        load(yours(), page(false, friend("1", "Ann Lee"), friend("2", "Bo Park"), friend("3", "Cy Diaz")));
        assertEquals(3, FriendList.keptCount());
        assertTrue(FriendList.keptAt() > 0);
        assertTrue(FriendList.gone().isEmpty());
        assertTrue(ShadowToast.getTextOfLatestToast().contains("3 friends"));
        assertTrue("the hook says it's live", HookStatus.missing(FamilyNames.FRIEND_LIST).isEmpty());
    }

    /** Someone on the kept list and not on the next read is listed, with the dates of both reads. */
    @Test
    public void aFriendMissingFromTheNextReadIsListed() {
        FriendList.kept(friends("1", "Ann Lee", "2", "Bo Park", "3", "Cy Diaz"), 10 * DAY);
        ShadowToast.reset();
        FriendList.kept(friends("1", "Ann Lee", "3", "Cy Diaz"), 12 * DAY);

        List<Gone> gone = FriendList.gone();
        assertEquals(1, gone.size());
        assertEquals("2", gone.get(0).id);
        assertEquals("Bo Park", gone.get(0).name);
        assertEquals(10 * DAY, gone.get(0).lastSeenAt);
        assertEquals(12 * DAY, gone.get(0).noticedAt);
        assertTrue(ShadowToast.getTextOfLatestToast().contains("Bo Park"));
        assertEquals(2, FriendList.keptCount());
        assertEquals(12 * DAY, FriendList.keptAt());
    }

    /** A new friend on the next read is no change worth a toast, and nobody is listed. */
    @Test
    public void aNewFriendListsNobody() {
        FriendList.kept(friends("1", "Ann Lee"), DAY);
        ShadowToast.reset();
        FriendList.kept(friends("1", "Ann Lee", "2", "Bo Park"), 2 * DAY);
        assertTrue(FriendList.gone().isEmpty());
        assertNull(ShadowToast.getLatestToast());
        assertEquals(2, FriendList.keptCount());
    }

    /** A list with a page left to load, loading one, or whose last load failed, isn't read to its end. */
    @Test
    public void aListNotLoadedToItsEndIsntKept() {
        load(yours(), page(true, friend("1", "Ann Lee")));
        FriendListForTests.PaginableList loading = page(false, friend("1", "Ann Lee"));
        loading.isLoadingNext = true;
        load(yours(), loading);
        FriendListForTests.PaginableList failed = page(false, friend("1", "Ann Lee"));
        failed.paginationLoadHadError = true;
        load(yours(), failed);
        assertEquals(0, FriendList.keptCount());
    }

    /** Someone else's All friends list goes through the same section and is never kept. */
    @Test
    public void someoneElsesListIsLeftAlone() {
        load(someoneElses(), page(false, friend("1", "Ann Lee")));
        assertEquals(0, FriendList.keptCount());
    }

    /** With a name typed in the list's search, what loads is a match, not the list, so it isn't kept. */
    @Test
    public void aSearchedListIsLeftAlone() {
        FriendList.kept(friends("1", "Ann Lee", "2", "Bo Park"), DAY);
        load(searched(), page(false, friend("1", "Ann Lee")));
        assertEquals(2, FriendList.keptCount());
        assertTrue(FriendList.gone().isEmpty());
    }

    @Test
    public void switchedOffNothingIsKept() {
        Settings.FRIEND_LIST_WATCH.save(false);
        load(yours(), page(false, friend("1", "Ann Lee")));
        assertEquals(0, FriendList.keptCount());
    }

    @Test
    public void pausedNothingIsKept() {
        for (HushfacebookPause.Reason why : HushfacebookPause.Reason.values()) {
            if (why == HushfacebookPause.Reason.NONE) continue;
            PauseForTests.pause(why);
            assertFalse("paused by " + why, FriendListForTests.keepsAList());
        }
        PauseForTests.resume();
        assertTrue(FriendListForTests.keepsAList());
    }

    @Test
    public void untilTheSettingsAreReadyNothingIsKept() {
        boolean[] kept = {true, true};
        SettingsContextRule.withoutContext(() -> kept[0] = FriendListForTests.keepsAList());
        SettingsContextRule.beforeThePauseIsDecided(() -> kept[1] = FriendListForTests.keepsAList());
        assertFalse(kept[0]);
        assertFalse(kept[1]);
        assertTrue(FriendListForTests.keepsAList());
    }

    /** Facebook draws the same page several times; the same list read again isn't compared again. */
    @Test
    public void theSameListReadAgainIsKeptOnce() {
        load(yours(), page(false, friend("1", "Ann Lee"), friend("2", "Bo Park")));
        long first = FriendList.keptAt();
        ShadowToast.reset();
        load(yours(), page(false, friend("1", "Ann Lee"), friend("2", "Bo Park")));
        assertEquals(first, FriendList.keptAt());
        assertNull(ShadowToast.getLatestToast());
    }

    /** The page hook takes the section the cast handed it once; a page with no section first reads nothing. */
    @Test
    public void aPageWithoutItsSectionIsLeftAlone() {
        FriendList.allFriendsPage(page(false, friend("1", "Ann Lee")), FriendListForTests.OWN, FriendListForTests.NO_SEARCH);
        assertEquals(0, FriendList.keptCount());
        assertFalse("the miss is reported", HookStatus.missing(FamilyNames.FRIEND_LIST).isEmpty());
    }

    /** A flag name the section doesn't have, as an unpatched build hands in, keeps nothing and says why. */
    @Test
    public void aFlagItCantReadKeepsNothing() {
        FriendList.section(yours());
        FriendList.allFriendsPage(page(false, friend("1", "Ann Lee")), "", "");
        assertEquals(0, FriendList.keptCount());
        FriendList.section(yours());
        FriendList.allFriendsPage(page(false, friend("1", "Ann Lee")), "A99", FriendListForTests.NO_SEARCH);
        assertEquals(0, FriendList.keptCount());
        assertFalse("the miss is reported", HookStatus.missing(FamilyNames.FRIEND_LIST).isEmpty());
    }

    /** Rows with no getString(int) leave the list unread and say so. */
    @Test
    public void rowsItCantReadKeepNothing() {
        load(yours(), page(false, new Object()));
        assertEquals(0, FriendList.keptCount());
        assertFalse("the miss is reported", HookStatus.missing(FamilyNames.FRIEND_LIST).isEmpty());
    }

    /** A read missing most of a big list is set aside, and counts only when read the same way later. */
    @Test
    public void aPartlyLoadedReadIsSetAside() {
        Map<String, String> all = new LinkedHashMap<>();
        for (int index = 0; index < 30; index++) all.put(String.valueOf(index), "Friend " + index);
        FriendList.kept(all, DAY);
        Map<String, String> few = new LinkedHashMap<>();
        for (int index = 0; index < 5; index++) few.put(String.valueOf(index), "Friend " + index);

        FriendList.kept(few, 2 * DAY);
        assertTrue("set aside the first time", FriendList.gone().isEmpty());
        assertEquals(30, FriendList.keptCount());
        FriendList.kept(few, 2 * DAY + 60_000);
        assertTrue("a redraw a minute later doesn't confirm it", FriendList.gone().isEmpty());
        FriendList.kept(few, 2 * DAY + FriendList.CONFIRM_AFTER_MS);
        assertEquals("read the same way later, it counts", 25, FriendList.gone().size());
        assertEquals(5, FriendList.keptCount());
    }

    /** A few friends gone at once, even most of a small list, is taken as it is. */
    @Test
    public void aSmallDropIsntSuspect() {
        assertFalse(FriendList.suspect(3, 4));
        assertFalse(FriendList.suspect(10, 12));
        assertFalse(FriendList.suspect(11, 30));
        assertTrue(FriendList.suspect(11, 21));
    }

    @Test
    public void theToastNamesUpToThreeAndCountsTheRest() {
        Gone ann = new Gone("1", "Ann Lee", 0, 1);
        Gone bo = new Gone("2", "Bo Park", 0, 1);
        Gone cy = new Gone("3", "Cy Diaz", 0, 1);
        Gone di = new Gone("4", "Di Fox", 0, 1);
        assertNull(FriendList.toast(Collections.emptyList()));
        assertTrue(FriendList.toast(Arrays.asList(ann)).contains("Ann Lee"));
        String three = FriendList.toast(Arrays.asList(ann, bo, cy));
        assertTrue(three.contains("Ann Lee") && three.contains("Bo Park") && three.contains("Cy Diaz"));
        String four = FriendList.toast(Arrays.asList(ann, bo, cy, di));
        assertFalse(four.contains("Di Fox"));
        assertTrue(four.contains("1 more"));
    }

    /** Found again, someone moves to the top with the new dates instead of showing twice; the list is capped. */
    @Test
    public void theListIsNewestFirstOncePerPersonAndCapped() {
        List<Gone> older = new ArrayList<>();
        for (int index = 0; index < FriendList.MAX_GONE; index++) older.add(new Gone("old" + index, "Old " + index, 0, 1));
        older.add(0, new Gone("7", "Gus Ng", 0, 1));
        List<Gone> merged = FriendList.merge(Arrays.asList(new Gone("7", "Gus Ng", 5, 6)), older);
        assertEquals(FriendList.MAX_GONE, merged.size());
        assertEquals("7", merged.get(0).id);
        assertEquals(6, merged.get(0).noticedAt);
        assertEquals("Old 0", merged.get(1).name);
    }

    /** Names keep their own text through the store, without the tabs and line breaks it splits on. */
    @Test
    public void theStoreKeepsWhatItWasGiven() {
        Map<String, String> friends = friends("1", "Ann Lee", "2", FriendList.clean("Bo\tPark\n"));
        assertEquals(friends, FriendList.parseFriends(FriendList.formatFriends(friends)));
        assertEquals("Bo Park", friends.get("2"));
        List<Gone> gone = Arrays.asList(new Gone("2", "Bo Park", 10, 20));
        Gone back = FriendList.parseGone(FriendList.formatGone(gone)).get(0);
        assertEquals("2", back.id);
        assertEquals("Bo Park", back.name);
        assertEquals(10, back.lastSeenAt);
        assertEquals(20, back.noticedAt);
        assertTrue(FriendList.parseGone("broken line\n1\tA\tx\t2\n").isEmpty());
    }

    @Test
    public void removeAndClearTakeEntriesOff() {
        FriendList.kept(friends("1", "Ann Lee", "2", "Bo Park", "3", "Cy Diaz"), DAY);
        FriendList.kept(friends("3", "Cy Diaz"), 2 * DAY);
        assertEquals(Arrays.asList("Ann Lee", "Bo Park"), names(FriendList.gone()));
        FriendList.remove(FriendList.gone().get(0));
        assertEquals(Arrays.asList("Bo Park"), names(FriendList.gone()));
        FriendList.clear();
        assertTrue(FriendList.gone().isEmpty());
        assertEquals("the kept copy stays for the next read", 1, FriendList.keptCount());
    }
}
