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

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.facebook.profile.ProfileNotesForTests.Node;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The hook before every return of the Bloks node's child getters: with the switch on, a node whose
 * own attributes name a note action is left out of the list, and every other node, the profile
 * picture first of all, stays where Facebook put it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ProfileNotesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void startClean() {
        ProfileNotes.forgetForTests();
        FeedFilterCounters.clear();
        HookStatus.clear();
        Settings.HIDE_PROFILE_NOTES.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_PROFILE_NOTES.resetToDefault();
        Settings.HIDE_PROFILE_GIFTS.resetToDefault();
        ProfileNotes.forgetForTests();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private static String counterLine() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(ProfileNotes.ROUTE + ":")) return line;
        }
        return null;
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.PROFILE_NOTES + ":")) return line;
        }
        return null;
    }

    @Test
    public void theSwitchStartsOff() {
        Settings.HIDE_PROFILE_NOTES.resetToDefault();
        assertFalse(Settings.HIDE_PROFILE_NOTES.get());
        assertFalse(ProfileNotesForTests.hidesANote());
    }

    @Test
    public void onYourOwnBubbleIsLeftOutAndTheRestKeepTheirOrder() {
        Node first = ProfileNotesForTests.picture();
        Node bubble = ProfileNotesForTests.ownBubble();
        Node last = ProfileNotesForTests.picture();
        List<?> kept = ProfileNotes.children(Arrays.asList(first, bubble, last));
        assertEquals(Arrays.asList(first, last), kept);
        // Only removals are counted: the getters run for every Bloks list on every layout pass.
        assertEquals(ProfileNotes.ROUTE + ": 0 lists, 0 items, 1 removed. Last reason: note bubble. Removed: "
                + "note bubble 1", counterLine());
        assertTrue(statusLine(), statusLine().startsWith(FamilyNames.PROFILE_NOTES + ": invoked 1"));
    }

    /** The node that loads a bubble's content goes too, a subclass of the node class included. */
    @Test
    public void theBubbleFetchIsLeftOut() {
        List<?> kept = ProfileNotes.children(Collections.singletonList(ProfileNotesForTests.bubbleFetch()));
        assertTrue(kept.isEmpty());
    }

    /** Negative control: a list with no note is handed back as it came, the same list. */
    @Test
    public void aListWithNoNoteIsHandedBackAsItIs() {
        List<Node> list = Arrays.asList(ProfileNotesForTests.picture(), ProfileNotesForTests.picture());
        assertSame(list, ProfileNotes.children(list));
        assertNull("nothing removed, nothing counted", counterLine());
    }

    /**
     * Only a node's own attributes count: a parent whose attributes hold another node, the stack
     * the bubble sits in, is kept.
     */
    @Test
    public void aParentHoldingTheBubbleIsKept() {
        Node parent = new Node().with(0x2e, Collections.singletonList(ProfileNotesForTests.ownBubble()));
        List<Node> list = Collections.singletonList(parent);
        assertSame(list, ProfileNotes.children(list));
    }

    @Test
    public void textThatOnlyMentionsNotesIsKept() {
        Node caption = new Node().with(0x24, "Drop a thought").with(0x25, "profile_notes_bubble");
        List<Node> list = Collections.singletonList(caption);
        assertSame(list, ProfileNotes.children(list));
    }

    @Test
    public void childrenThatArentNodesAreKept() {
        List<Object> list = Arrays.asList("text", 7, null);
        assertSame(list, ProfileNotes.children(list));
    }

    @Test
    public void emptyAndMissingListsPassThrough() {
        assertNull(ProfileNotes.children(null));
        List<Object> empty = Collections.emptyList();
        assertSame(empty, ProfileNotes.children(empty));
    }

    @Test
    public void offTheBubbleStays() {
        Settings.HIDE_PROFILE_NOTES.save(false);
        assertFalse(ProfileNotesForTests.hidesANote());
        assertNull(counterLine());
    }

    @Test
    public void pausedTheBubbleStays() {
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(ProfileNotesForTests.hidesANote());
    }

    /** A list that throws is recorded and handed back: the hook fails open. */
    @Test
    public void aFailureHandsTheListBack() {
        List<Object> broken = new AbstractList<Object>() {
            @Override
            public Object get(int index) {
                throw new IllegalStateException("no child " + index);
            }

            @Override
            public int size() {
                return 1;
            }
        };
        assertSame(broken, ProfileNotes.children(broken));
        assertTrue(statusLine(), statusLine().contains("threw"));
    }

    @Test
    public void theGiftSwitchStartsOff() {
        Settings.HIDE_PROFILE_GIFTS.resetToDefault();
        assertFalse(Settings.HIDE_PROFILE_GIFTS.get());
    }

    /**
     * With the gift switch on (#576), a node whose tap opens a gift address, runs a Bloks app named
     * for gifts or names a profile gift entry point is left out, and the rest keep their order.
     */
    @Test
    public void onTheGiftButtonIsLeftOutAndTheRestKeepTheirOrder() {
        Settings.HIDE_PROFILE_NOTES.save(false);
        Settings.HIDE_PROFILE_GIFTS.save(true);
        Node picture = ProfileNotesForTests.picture();
        Node bubble = ProfileNotesForTests.ownBubble();
        Node other = ProfileNotesForTests.picture();
        List<?> kept = ProfileNotes.children(Arrays.asList(picture, ProfileNotesForTests.giftButton(), bubble,
                ProfileNotesForTests.giftApp(), other, ProfileNotesForTests.giftEntry()));
        assertEquals("only the gift buttons go, the note stays with its switch off", Arrays.asList(picture, bubble, other), kept);
        assertEquals(ProfileNotes.ROUTE + ": 0 lists, 0 items, 3 removed. Last reason: gift button. Removed: "
                + "gift button 3", counterLine());
    }

    /** Gift words that don't name what a tap opens stay: a label, a gift card, a profile script with no gift. */
    @Test
    public void giftWordsThatOpenNoGiftAreKept() {
        Settings.HIDE_PROFILE_GIFTS.save(true);
        List<Node> nodes = Arrays.asList(
                new Node().with(0x2d, "Send a gift"),
                new Node().with(0x2d, "(bk.action.bloks.AsyncAction \"com.bloks.www.fb.profile.actions\" "
                        + "(bk.action.map.Make (bk.action.array.Make \"entrypoint\") (bk.action.array.Make \"profile_header\")))"),
                new Node().with(0x2d, "(bk.action.bloks.AsyncAction \"com.bloks.www.marketplace.checkout\" "
                        + "(bk.action.map.Make (bk.action.array.Make \"surface\") (bk.action.array.Make \"checkout_gift_toggle\")))"));
        assertEquals(nodes, ProfileNotes.children(nodes));
        assertNull(counterLine());
    }

    @Test
    public void withBothSwitchesOnBothGoAndAreCountedApart() {
        Settings.HIDE_PROFILE_GIFTS.save(true);
        Node picture = ProfileNotesForTests.picture();
        List<?> kept = ProfileNotes.children(Arrays.asList(ProfileNotesForTests.ownBubble(), picture,
                ProfileNotesForTests.giftButton()));
        assertEquals(Collections.singletonList(picture), kept);
        String line = counterLine();
        assertTrue(line, line.contains(" 2 removed") && line.contains("note bubble 1") && line.contains("gift button 1"));
    }

    @Test
    public void withOnlyNotesOnTheGiftButtonStays() {
        Node gift = ProfileNotesForTests.giftButton();
        List<Node> nodes = Arrays.asList(ProfileNotesForTests.picture(), gift);
        assertEquals(nodes, ProfileNotes.children(nodes));
    }

    @Test
    public void pausedTheGiftButtonStays() {
        Settings.HIDE_PROFILE_GIFTS.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        List<Node> nodes = Arrays.asList(ProfileNotesForTests.picture(), ProfileNotesForTests.giftButton());
        assertEquals(nodes, ProfileNotes.children(nodes));
    }

    /** A node's answer is kept, so a layout pass over the same node doesn't read it again. */
    @Test
    public void aNodesAnswerIsKept() {
        Node bubble = ProfileNotesForTests.ownBubble();
        assertTrue(ProfileNotes.children(Collections.singletonList(bubble)).isEmpty());
        bubble.with(0x2d, "nothing");
        assertTrue("the first answer stands", ProfileNotes.children(Collections.singletonList(bubble)).isEmpty());
    }
}
