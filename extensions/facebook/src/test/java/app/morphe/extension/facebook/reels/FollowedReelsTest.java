/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.graphql.model.GraphQLStory;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.facebook.reels.FollowedReelsForTests.FollowStatus;
import app.morphe.extension.facebook.reels.FollowedReelsForTests.Item;
import app.morphe.extension.facebook.reels.FollowedReelsForTests.Model;
import app.morphe.extension.facebook.reels.ReelMidCardsForTests.Section;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Only reels from accounts I follow, in Clean up Reels (#349): on, a reel whose author Facebook
 * would offer to follow comes off its page, while a followed author's reel, one that can't be
 * followed, a card between reels and anything unreadable stay, each counted under why. Off, paused,
 * unpatched or failing, the page goes on as Facebook sent it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FollowedReelsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** A holder pairing a reel's model with its story, the way some Reels items keep the model. */
    public static final class Holder {
        final Model model;
        final GraphQLStory story = new GraphQLStory();

        public Holder(Model model) {
            this.model = model;
        }
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.FOLLOWED_REELS_ONLY.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private static String line(String route) {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(route + ": ")) return line;
        }
        return null;
    }

    private static List<Object> page(Object... items) {
        return Arrays.asList(items);
    }

    /** The Reels tab fills slower with it on, so it waits to be asked for. */
    @Test
    public void theSwitchStartsOffAndOffEveryReelStays() {
        assertFalse(Settings.FOLLOWED_REELS_ONLY.defaultValue);
        List<Object> page = page(FollowedReelsForTests.reel(FollowStatus.CAN_SUBSCRIBE));

        assertSame(page, FollowedReelsForTests.followedOnly(page));
    }

    /** On, only the reel Facebook would offer to follow goes, the rest keep their order, and each read is counted. */
    @Test
    public void onOnlyAReelYouCouldFollowComesOff() {
        Settings.FOLLOWED_REELS_ONLY.save(true);
        Item followed = FollowedReelsForTests.reel(FollowStatus.IS_SUBSCRIBED);
        Item notFollowed = FollowedReelsForTests.reel(FollowStatus.CAN_SUBSCRIBE);
        Item cantFollow = FollowedReelsForTests.reel(FollowStatus.CANNOT_SUBSCRIBE);
        Item unset = FollowedReelsForTests.reel(FollowStatus.UNSET_OR_UNRECOGNIZED_ENUM_VALUE);
        Item noStatus = FollowedReelsForTests.reel(null);
        Object card = new Object();

        Collection<?> kept = FollowedReelsForTests.followedOnly(page(followed, notFollowed, cantFollow, unset, noStatus, card));

        assertEquals(page(followed, cantFollow, unset, noStatus, card), new ArrayList<>(kept));
        String items = line(FollowedReels.ITEMS_ROUTE);
        assertNotNull(FeedFilterCounters.report().toString(), items);
        assertTrue(items, items.contains("Removed: " + FollowedReels.NOT_FOLLOWED + " 1"));
        assertTrue(items, items.contains(FollowedReels.FOLLOWED + " 1"));
        assertTrue(items, items.contains(FollowedReels.CANT_FOLLOW + " 1"));
        assertTrue(items, items.contains(FollowedReels.OTHER_STATUS + "UNSET_OR_UNRECOGNIZED_ENUM_VALUE 1"));
        assertTrue(items, items.contains(FollowedReels.NO_STATUS + " 1"));
        assertTrue(items, items.contains(FollowedReels.NO_MODEL + " 1"));
        String pages = line(FollowedReels.PAGES_ROUTE);
        assertNotNull(FeedFilterCounters.report().toString(), pages);
        assertTrue(pages, pages.contains("1 removed"));
    }

    /** A page with nothing to drop goes on as the very same collection. */
    @Test
    public void aPageWithNothingToDropIsTheSamePage() {
        Settings.FOLLOWED_REELS_ONLY.save(true);
        List<Object> page = page(FollowedReelsForTests.reel(FollowStatus.IS_SUBSCRIBED), new Object());

        assertSame(page, FollowedReelsForTests.followedOnly(page));
    }

    /** The model is found when the item keeps it in a holder next to its story, as the AI filter finds it. */
    @Test
    public void aModelInAHolderIsRead() {
        Settings.FOLLOWED_REELS_ONLY.save(true);
        Item held = new Item(new Holder(new Model(FollowStatus.CAN_SUBSCRIBE)));

        assertTrue(FollowedReelsForTests.followedOnly(page(held)).isEmpty());
    }

    /** Sections lose the reels of authors you don't follow, and a section left empty leaves the page. */
    @Test
    public void sectionsLoseTheirNotFollowedReels() {
        Settings.FOLLOWED_REELS_ONLY.save(true);
        Item followed = FollowedReelsForTests.reel(FollowStatus.IS_SUBSCRIBED);
        Item notFollowed = FollowedReelsForTests.reel(FollowStatus.CAN_SUBSCRIBE);
        Section mixed = new Section(new ArrayList<>(Arrays.asList(followed, notFollowed)));
        Section onlyNotFollowed = new Section(new ArrayList<>(Collections.singletonList(
                FollowedReelsForTests.reel(FollowStatus.CAN_SUBSCRIBE))));

        List<?> kept = FollowedReelsForTests.followedSections(Arrays.asList(mixed, onlyNotFollowed));

        assertEquals(Collections.singletonList(mixed), kept);
        assertEquals(Collections.singletonList(followed), mixed.items);
        String sections = line(FollowedReels.SECTIONS_ROUTE);
        assertNotNull(FeedFilterCounters.report().toString(), sections);
        assertTrue(sections, sections.contains("2 removed"));
    }

    /** The listener walk's copy of an insert is filtered the same, but its items aren't counted a second time. */
    @Test
    public void anAnnouncementIsFilteredWithoutCountingItsItemsAgain() {
        Settings.FOLLOWED_REELS_ONLY.save(true);
        Item followed = FollowedReelsForTests.reel(FollowStatus.IS_SUBSCRIBED);

        Collection<?> kept = FollowedReels.followedOnly(page(followed, FollowedReelsForTests.reel(FollowStatus.CAN_SUBSCRIBE)),
                FollowedReelsForTests.MODEL, FollowedReelsForTests.STATUSES, FollowedReels.ANNOUNCED_ROUTE);

        assertEquals(Collections.singletonList(followed), new ArrayList<>(kept));
        assertNull("an announcement doesn't count items", line(FollowedReels.ITEMS_ROUTE));
        String announced = line(FollowedReels.ANNOUNCED_ROUTE);
        assertNotNull(FeedFilterCounters.report().toString(), announced);
        assertTrue(announced, announced.contains("1 removed"));
    }

    /** Paused, the page is Facebook's own whatever is saved. */
    @Test
    public void pausedEveryReelStays() {
        Settings.FOLLOWED_REELS_ONLY.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        List<Object> page = page(FollowedReelsForTests.reel(FollowStatus.CAN_SUBSCRIBE));

        assertSame(page, FollowedReelsForTests.followedOnly(page));
    }

    /** Until the patch fills the stub in, no status can be read, and every reel stays. */
    @Test
    public void anUnpatchedStatusReaderKeepsEveryReel() {
        Settings.FOLLOWED_REELS_ONLY.save(true);
        List<Object> page = page(FollowedReelsForTests.reel(FollowStatus.CAN_SUBSCRIBE));

        assertSame(page, FollowedReels.followedOnly(page, FollowedReelsForTests.MODEL));
        String items = line(FollowedReels.ITEMS_ROUTE);
        assertTrue(items, items.contains(FollowedReels.NOT_PATCHED + " 1"));
    }

    /** A status read that throws keeps the reel, and the page goes on. */
    @Test
    public void aReadThatThrowsKeepsTheReel() {
        Settings.FOLLOWED_REELS_ONLY.save(true);
        List<Object> page = page(FollowedReelsForTests.reel(FollowStatus.CAN_SUBSCRIBE));

        assertSame(page, FollowedReels.followedOnly(page, FollowedReelsForTests.MODEL, model -> {
            throw new IllegalStateException("released tree");
        }, FollowedReels.PAGES_ROUTE));
        String items = line(FollowedReels.ITEMS_ROUTE);
        assertTrue(items, items.contains(FollowedReels.READ_FAILED + " 1"));
    }

    /** A model class this build doesn't have leaves every item without a model, and every item stays. */
    @Test
    public void aMissingModelClassKeepsEveryReel() {
        Settings.FOLLOWED_REELS_ONLY.save(true);
        List<Object> page = page(FollowedReelsForTests.reel(FollowStatus.CAN_SUBSCRIBE));

        assertSame(page, FollowedReels.followedOnly(page, "no.such.ReelModel",
                FollowedReelsForTests.STATUSES, FollowedReels.PAGES_ROUTE));
        String items = line(FollowedReels.ITEMS_ROUTE);
        assertTrue(items, items.contains(FollowedReels.NO_MODEL + " 1"));
    }
}
