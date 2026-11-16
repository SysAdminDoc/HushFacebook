/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.profile;

import static org.junit.Assert.assertEquals;
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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.facebook.profile.LocalProfileCountsForTests.Screen;
import app.morphe.extension.facebook.profile.LocalProfileCountsForTests.SessionScreen;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class LocalProfileCountsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Locale locale;

    @Before
    public void startClean() {
        locale = Locale.getDefault();
        Locale.setDefault(Locale.US);
        LocalProfileCounts.forgetForTests();
        HookStatus.clear();
        Settings.LOCAL_PROFILE_COUNTS.save(true);
        Settings.LOCAL_FRIENDS.save("1234");
        Settings.LOCAL_FOLLOWERS.save("56");
        Settings.LOCAL_FOLLOWING.save("");
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.LOCAL_PROFILE_COUNTS.resetToDefault();
        Settings.LOCAL_FRIENDS.resetToDefault();
        Settings.LOCAL_FOLLOWERS.resetToDefault();
        Settings.LOCAL_FOLLOWING.resetToDefault();
        LocalProfileCounts.forgetForTests();
        HookStatus.clear();
        Locale.setDefault(locale);
    }

    @Test
    public void swapsTheNumberBeforeEachWordAndKeepsTheWord() {
        assertEquals("1,234 friends", LocalProfileCounts.rewrite("812 friends", "1234", "56", ""));
        assertEquals("56 followers", LocalProfileCounts.rewrite("1.2K followers", "1234", "56", ""));
        assertEquals("1,234 friends · 56 followers",
                LocalProfileCounts.rewrite("812 friends · 3 followers", "1234", "56", ""));
    }

    @Test
    public void aBlankCountLeavesItsNumberAlone() {
        assertEquals("77 following", LocalProfileCounts.rewrite("77 following", "1234", "56", ""));
    }

    @Test
    public void readsTheWordsOfOtherLanguages() {
        assertEquals("1,234 Freunde", LocalProfileCounts.rewrite("812 Freunde", "1234", "56", ""));
        assertEquals("56 seguidores", LocalProfileCounts.rewrite("3 seguidores", "1234", "56", ""));
        assertEquals("1,234 amis", LocalProfileCounts.rewrite("12 amis", "1234", "56", ""));
    }

    @Test
    public void leavesOtherTextAlone() {
        for (String text : new String[] {"Friends", "Add friend", "12 mutual connections", "4 friendship requests",
                "Joined 2019"}) {
            assertEquals(text, LocalProfileCounts.rewrite(text, "1234", "56", "9"));
        }
    }

    @Test
    public void cleanKeepsDigitsOnly() {
        assertEquals("1234", LocalProfileCounts.clean(" 1234 "));
        assertEquals("5", LocalProfileCounts.clean("0005"));
        assertEquals("0", LocalProfileCounts.clean("000"));
        assertEquals("", LocalProfileCounts.clean("12a"));
        assertEquals("", LocalProfileCounts.clean("-5"));
        assertEquals("", LocalProfileCounts.clean("12345678901"));
        assertEquals("", LocalProfileCounts.clean(""));
        assertEquals("", LocalProfileCounts.clean(null));
    }

    @Test
    public void ownProfileGetsTheTypedNumber() {
        LocalProfileCounts.opened(LocalProfileCountsForTests.own());
        assertEquals("1,234 friends", LocalProfileCounts.text("812 friends"));
        String status = null;
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.LOCAL_PROFILE_COUNTS + ":")) status = line;
        }
        assertTrue(String.valueOf(status), status != null && status.contains("0 missing"));
    }

    @Test
    public void aLongLineIsNeverTouched() {
        LocalProfileCounts.opened(LocalProfileCountsForTests.own());
        String longLine = "812 followers of the page group and many other words that make a long line, past eighty";
        assertEquals(longLine, LocalProfileCounts.text(longLine));
    }

    @Test
    public void aProfileWithNoIdIsYours() {
        LocalProfileCounts.opened(new Screen(null, true, LocalProfileCountsForTests.VIEWER));
        assertEquals("1,234 friends", LocalProfileCounts.text("812 friends"));
    }

    @Test
    public void anotherPersonsProfileIsNeverTouched() {
        LocalProfileCounts.opened(new Screen(555L, true, LocalProfileCountsForTests.VIEWER));
        assertEquals("812 friends", LocalProfileCounts.text("812 friends"));
    }

    @Test
    public void aScreenBehindAnotherIsLeftAlone() {
        LocalProfileCounts.opened(new Screen(Long.parseLong(LocalProfileCountsForTests.VIEWER), false,
                LocalProfileCountsForTests.VIEWER));
        assertEquals("812 friends", LocalProfileCounts.text("812 friends"));
    }

    @Test
    public void anUnknownViewerShowsNothingAsYours() {
        LocalProfileCounts.opened(new Screen(null, true, null));
        assertEquals("812 friends", LocalProfileCounts.text("812 friends"));
        LocalProfileCounts.opened(new Object());
        assertEquals("812 friends", LocalProfileCounts.text("812 friends"));
        LocalProfileCounts.forgetForTests();
        assertEquals("812 friends", LocalProfileCounts.text("812 friends"));
    }

    @Test
    public void theViewerComesFromTheSessionWhenTheViewerContextIsEmpty() {
        String viewer = LocalProfileCountsForTests.VIEWER;
        LocalProfileCounts.opened(new SessionScreen(Long.parseLong(viewer), viewer));
        assertEquals("1,234 friends", LocalProfileCounts.text("812 friends"));
        LocalProfileCounts.opened(new SessionScreen(-1L, viewer));
        assertEquals("1,234 friends", LocalProfileCounts.text("812 friends"));
    }

    @Test
    public void aSessionNeverMakesAnotherPersonsProfileYours() {
        String viewer = LocalProfileCountsForTests.VIEWER;
        LocalProfileCounts.opened(new SessionScreen(555L, viewer));
        assertEquals("812 friends", LocalProfileCounts.text("812 friends"));
        LocalProfileCounts.opened(new SessionScreen(null, null));
        assertEquals("812 friends", LocalProfileCounts.text("812 friends"));
        LocalProfileCounts.opened(new SessionScreen(null, ""));
        assertEquals("812 friends", LocalProfileCounts.text("812 friends"));
    }

    @Test
    public void offShowsTheRealOnes() {
        Settings.LOCAL_PROFILE_COUNTS.save(false);
        LocalProfileCounts.opened(LocalProfileCountsForTests.own());
        assertEquals("812 friends", LocalProfileCounts.text("812 friends"));
    }

    @Test
    public void allBlankShowsTheRealOnes() {
        Settings.LOCAL_FRIENDS.save("");
        Settings.LOCAL_FOLLOWERS.save("");
        LocalProfileCounts.opened(LocalProfileCountsForTests.own());
        assertEquals("812 friends", LocalProfileCounts.text("812 friends"));
    }

    @Test
    public void aBadSavedValueShowsTheRealOne() {
        Settings.LOCAL_FRIENDS.save("12x");
        Settings.LOCAL_FOLLOWERS.save("");
        LocalProfileCounts.opened(LocalProfileCountsForTests.own());
        assertEquals("812 friends", LocalProfileCounts.text("812 friends"));
    }

    @Test
    public void pausedShowsTheRealOnes() {
        LocalProfileCounts.opened(LocalProfileCountsForTests.own());
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals("812 friends", LocalProfileCounts.text("812 friends"));
    }

    @Test
    public void nullAndLongTextPass() {
        assertNull(LocalProfileCounts.text(null));
        assertEquals("no digits friends", LocalProfileCounts.text("no digits friends"));
        LocalProfileCounts.opened(LocalProfileCountsForTests.own());
        String longLine = "812 friends and a very long line that goes past the limit of eighty characters, for sure";
        assertEquals(longLine, LocalProfileCounts.text(longLine));
    }

    /** A Bloks text node holding [text] the way a parsed layout holds it. */
    private static ProfileNotesForTests.Node textNode(String text) {
        return new ProfileNotesForTests.Node().with(0x24, text).with(0x31, "bold");
    }

    @Test
    public void aCountSplitIntoItsNumberAndItsWordGetsTheTypedNumber() {
        // Facebook 586 and 587 draw "121M followers" as two nodes, the number and " followers" (#488).
        LocalProfileCounts.opened(LocalProfileCountsForTests.own());
        String friends = new String("812");
        String followers = new String("1.2K");
        List<Object> line = Arrays.asList(textNode(friends), textNode(" friends"), textNode(" · "),
                textNode(followers), textNode(" followers"));

        assertSame(line, LocalProfileCounts.children(line));

        assertEquals("1,234", LocalProfileCounts.text(friends));
        assertEquals("56", LocalProfileCounts.text(followers));
        assertEquals(" friends", LocalProfileCounts.text(" friends"));
    }

    @Test
    public void aSplitCountInsideAWrapperIsFoundToo() {
        LocalProfileCounts.opened(LocalProfileCountsForTests.own());
        String friends = new String("812");
        ProfileNotesForTests.Node number = new ProfileNotesForTests.Node().with(0x26,
                Collections.singletonList(new ProfileNotesForTests.Node().with(0x26, textNode(friends))));
        ProfileNotesForTests.Node word = new ProfileNotesForTests.Node().with(0x26,
                Collections.singletonList(textNode(" Freunde")));

        LocalProfileCounts.children(Arrays.asList(number, word));

        assertEquals("1,234", LocalProfileCounts.text(friends));
    }

    /**
     * One piece of a count line the way Facebook 587 builds it: the text node under key 32 next to a
     * "..." node under key 44 and an empty node under key 133, with its alignment and colors.
     */
    private static ProfileNotesForTests.Node piece(String text) {
        ProfileNotesForTests.Node label = new ProfileNotesForTests.Node().with(0x23, "sans-serif").with(0x26, text)
                .with(0x2a, "13.00sp").with(0x2b, "bold");
        ProfileNotesForTests.Node more = new ProfileNotesForTests.Node().with(0x26, "...").with(0x2a, "13.00sp");
        return new ProfileNotesForTests.Node().with(0x20, Collections.singletonList(label)).with(0x2a, "start")
                .with(0x2c, Collections.singletonList(more))
                .with(0x85, Collections.singletonList(new ProfileNotesForTests.Node()));
    }

    @Test
    public void aSplitCountWhosePiecesHoldMoreThanTheirTextIsFoundToo() {
        // Facebook 587 gives each piece of "121M followers" three child lists, so no piece has a lone child (#488).
        LocalProfileCounts.opened(LocalProfileCountsForTests.own());
        String followers = new String("121M");

        LocalProfileCounts.children(Arrays.asList(piece(followers), piece(" followers")));

        assertEquals("56", LocalProfileCounts.text(followers));
    }

    @Test
    public void aWholeCountRowIsNeverTakenForTheNextRowsNumber() {
        LocalProfileCounts.opened(LocalProfileCountsForTests.own());
        Settings.LOCAL_FOLLOWING.save("77");
        String followers = new String("121M");
        String following = new String("5");
        ProfileNotesForTests.Node followersRow = new ProfileNotesForTests.Node().with(0x20,
                Arrays.asList(piece(followers), piece(" followers")));
        ProfileNotesForTests.Node followingRow = new ProfileNotesForTests.Node().with(0x20,
                Arrays.asList(piece(following), piece(" following")));

        LocalProfileCounts.children(Arrays.asList(followersRow, followingRow));

        assertEquals("a followers count never gets the following number", "121M", LocalProfileCounts.text(followers));
        assertEquals("5", LocalProfileCounts.text(following));
    }

    @Test
    public void aPieceHoldingTwoNumbersIsNoCount() {
        LocalProfileCounts.opened(LocalProfileCountsForTests.own());
        String first = new String("12");
        ProfileNotesForTests.Node two = new ProfileNotesForTests.Node().with(0x20,
                Arrays.asList(textNode(first), textNode(new String("34"))));

        LocalProfileCounts.children(Arrays.asList(two, piece(" friends")));

        assertEquals("12", LocalProfileCounts.text(first));
    }

    @Test
    public void onlyTheNumberBeforeTheWordIsSwapped() {
        LocalProfileCounts.opened(LocalProfileCountsForTests.own());
        String count = new String("812");
        String sameDigitsElsewhere = new String("812");
        String likes = new String("40");
        LocalProfileCounts.children(Arrays.asList(textNode(count), textNode(" friends")));
        LocalProfileCounts.children(Arrays.asList(textNode(likes), textNode(" likes")));
        LocalProfileCounts.children(Arrays.asList(textNode(" friends"), textNode(new String("9"))));

        assertEquals("a like count with the same digits keeps its number", "812", LocalProfileCounts.text(sameDigitsElsewhere));
        assertEquals("40", LocalProfileCounts.text(likes));
        assertEquals("1,234", LocalProfileCounts.text(count));
    }

    @Test
    public void aSplitCountOnAnotherProfileOrSwitchedOffKeepsItsNumber() {
        String count = new String("812");
        LocalProfileCounts.opened(new Screen(555L, true, LocalProfileCountsForTests.VIEWER));
        LocalProfileCounts.children(Arrays.asList(textNode(count), textNode(" friends")));
        assertEquals("812", LocalProfileCounts.text(count));

        LocalProfileCounts.opened(LocalProfileCountsForTests.own());
        Settings.LOCAL_PROFILE_COUNTS.save(false);
        LocalProfileCounts.children(Arrays.asList(textNode(count), textNode(" friends")));
        assertEquals("812", LocalProfileCounts.text(count));

        Settings.LOCAL_PROFILE_COUNTS.save(true);
        Settings.LOCAL_FRIENDS.save("");
        LocalProfileCounts.children(Arrays.asList(textNode(count), textNode(" friends")));
        assertEquals("a blank count keeps Facebook's", "812", LocalProfileCounts.text(count));
    }

    @Test
    public void childListsPassThroughUntouched() {
        assertNull(LocalProfileCounts.children(null));
        List<Object> empty = Collections.emptyList();
        assertSame(empty, LocalProfileCounts.children(empty));
        List<Object> odd = Arrays.asList("not a node", null, 7);
        LocalProfileCounts.opened(LocalProfileCountsForTests.own());
        assertSame(odd, LocalProfileCounts.children(odd));
    }

    @Test
    public void isOwnNeedsAKnownViewer() {
        assertTrue(LocalProfileCounts.isOwn(0, "7"));
        assertTrue(LocalProfileCounts.isOwn(7, "7"));
        assertFalse(LocalProfileCounts.isOwn(8, "7"));
        assertFalse(LocalProfileCounts.isOwn(7, null));
        assertFalse(LocalProfileCounts.isOwn(0, ""));
    }
}
