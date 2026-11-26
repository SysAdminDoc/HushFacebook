/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import static app.morphe.extension.facebook.stories.StorySeenForTests.ACCOUNT;
import static app.morphe.extension.facebook.stories.StorySeenForTests.cards;
import static app.morphe.extension.facebook.stories.StorySeenForTests.send;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Set;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The question first thing in the story viewer's seen sender: while the switch is on a batch of
 * viewed stories stays on the phone, or only the cards marked with Mark as seen go, and every other
 * time the batch goes out as Facebook sends it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class StorySeenTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        StorySeenForTests.reset();
        // The patch is in Morphe Manager's default selection with its switch off; these tests run with it on.
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.VIEW_STORIES_ANONYMOUSLY.resetToDefault();
        Settings.MARK_STORIES_SEEN.resetToDefault();
        StorySeenForTests.reset();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private static String counterLine() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(StorySeen.ROUTE + ":")) return line;
        }
        return null;
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.STORY_SEEN + ":")) return line;
        }
        return null;
    }

    @Test
    public void theSwitchStartsOffAndOnKeepsEveryBatchBack() {
        assertFalse("the switch starts off", Settings.VIEW_STORIES_ANONYMOUSLY.defaultValue);
        assertFalse("the button's switch starts on", Settings.MARK_STORIES_SEEN.get());
        for (int i = 0; i < 3; i++) assertNull("batch " + (i + 1) + " was sent", send(ACCOUNT, cards("a", "b")));
        assertEquals(StorySeen.ROUTE + ": 3 lists, 3 items, 3 removed. Last reason: " + StorySeen.HELD_BACK
                + ". Removed: " + StorySeen.HELD_BACK + " 3", counterLine());
        assertEquals(FamilyNames.STORY_SEEN + ": invoked 3, 0 found, 0 missing", statusLine());
    }

    /** Off, the views go out, and the report still shows the sender asking, so the hook can be seen working. */
    @Test
    public void offTheViewsGoOutAndTheReportStillCountsThem() {
        Settings.VIEW_STORIES_ANONYMOUSLY.save(false);
        Set<String> batch = cards("a", "b");
        assertSame(batch, send(ACCOUNT, batch));
        assertEquals(StorySeen.ROUTE + ": 1 lists, 1 items, 0 removed", counterLine());
        assertEquals(FamilyNames.STORY_SEEN + ": invoked 1, 0 found, 0 missing", statusLine());
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
        assertNull("turning the switch back on didn't take effect at the next send", send(ACCOUNT, batch));
    }

    @Test
    public void pausedTheViewsGoOut() {
        Settings.MARK_STORIES_SEEN.save(true);
        Set<String> batch = cards("a", "b");
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertSame(batch, send(ACCOUNT, batch));
        PauseForTests.pause(HushfacebookPause.Reason.CRASH_LOOP);
        assertSame(batch, send(ACCOUNT, batch));
        PauseForTests.resume();
        assertNull(send(ACCOUNT, batch));
    }

    @Test
    public void onlyTheMarkedCardsGoAndTheRestStayHeld() {
        Settings.MARK_STORIES_SEEN.save(true);
        assertEquals(StoryMarks.State.MARKED, StorySeen.MARKS.mark("100", "c1"));
        assertEquals(cards("c1"), send(ACCOUNT, cards("c1", "c2")));
        assertEquals(StoryMarks.State.SENT, StorySeen.MARKS.state("100", "c1"));

        // c2 was held and counted by Facebook, so it never comes back in a batch: marked now, it
        // goes with the next send, and c3 of that batch stays held.
        assertEquals(StoryMarks.State.MARKED, StorySeen.MARKS.mark("100", "c2"));
        assertEquals(cards("c2"), send(ACCOUNT, cards("c3")));
        assertNull("a sent card went again", send(ACCOUNT, cards("c1")));

        // A second tap before the send keeps the mark, and it goes (#557).
        StorySeen.MARKS.mark("100", "c4");
        assertEquals(StoryMarks.State.MARKED, StorySeen.MARKS.mark("100", "c4"));
        assertEquals(cards("c4"), send(ACCOUNT, cards("c4")));
    }

    @Test
    public void withTheButtonOffAMarkChangesNothing() {
        StorySeen.MARKS.mark("100", "c1");
        assertNull(send(ACCOUNT, cards("c1", "c2")));
    }

    @Test
    public void aMarkNeverGoesOutForAnotherAccount() {
        Settings.MARK_STORIES_SEEN.save(true);
        StorySeen.MARKS.mark("100", "c1");
        assertNull(send(new StorySeenForTests.Session("200"), cards("c1")));
        assertNull("a session with no user ID sent a card", send(new Object(), cards("c1")));
        assertEquals(cards("c1"), send(ACCOUNT, cards("c1")));
    }

    @Test
    public void aHeldCardMarkedLaterGoesOutThroughTheSendThatHeldIt() {
        Settings.MARK_STORIES_SEEN.save(true);
        Object sender = new Object();
        Object listener = new Object();
        assertNull(StorySeen.toSend(sender, listener, ACCOUNT, "a", "b", "c", null, cards("c5"), false));
        assertFalse("a card that was never held was sent", StorySeen.sendHeld("100", "c6"));
        StorySeen.MARKS.mark("100", "c5");
        assertTrue("the held card had no send to go through", StorySeen.sendHeld("100", "c5"));
        // The patched sender calls the hook again with an empty set; a peek's send isn't one of these.
        assertNull(StorySeen.toSend(sender, listener, ACCOUNT, "a", "b", "c", null, cards(), true));
        assertEquals(cards("c5"), StorySeen.toSend(sender, listener, ACCOUNT, "a", "b", "c", null, cards(), false));
    }

    @Test
    public void theAccountIsTheSessionsUserId() {
        assertEquals("100", StorySeen.account(ACCOUNT));
        assertNull(StorySeen.account(new StorySeenForTests.Session("")));
        assertNull(StorySeen.account("100"));
        assertNull(StorySeen.account(null));
    }
}
