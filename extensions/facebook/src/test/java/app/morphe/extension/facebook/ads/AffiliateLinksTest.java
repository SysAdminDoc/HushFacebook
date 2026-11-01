/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.ads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Hide affiliate product links: with the switch on, each of the three places gets the answer it
 * gets for a post with no shop link, and each card is counted under its place; a post with no card
 * keeps Facebook's answer. Off or paused, every card stays.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AffiliateLinksTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final Object CARD = new Object();

    /** The reel overlay's call-to-action kinds, named as Facebook names them. */
    private enum Cta {
        AFFILIATE_BANNER, AFFILIATE_EYEBROW, PRODUCT_TAGGING, PRODUCT_TAGGING_V2, STOREFRONT, SHOP_SIMILAR,
        COMMUNITY_NOTES, BOOST_REEL, CHAT_ON_WHATSAPP
    }

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_AFFILIATE_LINKS.resetToDefault();
        Settings.HIDE_COMMISSION_LABEL.resetToDefault();
        HookStatus.clear();
    }

    /** The label has its own switch, off by default, whatever the product cards' switch says (#329). */
    @Test
    public void theCommissionLabelGoesOnlyWithItsOwnSwitch() {
        assertTrue("the label went with its switch off", AffiliateLinks.keepCommissionLabel(1));
        assertFalse(AffiliateLinks.keepCommissionLabel(0));

        Settings.HIDE_COMMISSION_LABEL.save(true);
        assertFalse("the label stayed with its switch on", AffiliateLinks.keepCommissionLabel(1));
        assertFalse(AffiliateLinks.keepCommissionLabel(0));

        Settings.HIDE_AFFILIATE_LINKS.save(false);
        assertFalse("the cards' switch brought the label back", AffiliateLinks.keepCommissionLabel(1));
        assertTrue(statusLine(), statusLine().contains(AffiliateLinks.COMMISSION_LABEL));
    }

    @Test
    public void pausedTheCommissionLabelStays() {
        Settings.HIDE_COMMISSION_LABEL.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertTrue("Pause didn't bring the label back", AffiliateLinks.keepCommissionLabel(1));
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.AFFILIATE_LINKS + ":")) return line;
        }
        return null;
    }

    @Test
    public void everyCardIsAnsweredAwayAndNoCardStaysNone() {
        assertTrue("the switch doesn't start on", Settings.HIDE_AFFILIATE_LINKS.get());
        assertFalse("a reel kept its product card", AffiliateLinks.keepReelCard(true));
        assertNull("a feed post kept its product footer", AffiliateLinks.keepFooter("affiliate_footer"));
        assertNull("the comment sheet kept its product card", AffiliateLinks.keepCommentCard(CARD));
        assertFalse("a reel without a card got one", AffiliateLinks.keepReelCard(false));
        assertNull(AffiliateLinks.keepFooter(null));
        assertNull(AffiliateLinks.keepCommentCard(null));
        assertEquals(FamilyNames.AFFILIATE_LINKS + ": invoked 6, 3 found, 0 missing. Counted: "
                + AffiliateLinks.REEL_CARD + " 1, " + AffiliateLinks.FEED_CARD + " 1, "
                + AffiliateLinks.COMMENT_CARD + " 1", statusLine());
    }

    @Test
    public void offOrPausedEveryCardStays() {
        Settings.HIDE_AFFILIATE_LINKS.save(false);
        assertTrue(AffiliateLinks.keepReelCard(true));
        assertEquals("affiliate_footer", AffiliateLinks.keepFooter("affiliate_footer"));
        assertSame(CARD, AffiliateLinks.keepCommentCard(CARD));
        Settings.HIDE_AFFILIATE_LINKS.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertTrue("a Hushfacebook paused by " + reason + " hid a reel's card", AffiliateLinks.keepReelCard(true));
            assertEquals("a Hushfacebook paused by " + reason + " hid a post's card",
                    "affiliate_footer", AffiliateLinks.keepFooter("affiliate_footer"));
            assertSame("a Hushfacebook paused by " + reason + " hid the comment sheet's card",
                    CARD, AffiliateLinks.keepCommentCard(CARD));
            PauseForTests.resume();
        }
    }

    /** The patch hands Facebook's answer over as an int, since a boolean method may return one. */
    @Test
    public void thePatchsIntEntryReadsNonZeroAsYes() {
        assertFalse("a reel's card handed over as 1 stayed", AffiliateLinks.keepReelCard(1));
        assertFalse(AffiliateLinks.keepReelCard(0));
        Settings.HIDE_AFFILIATE_LINKS.save(false);
        assertTrue("with the switch off, 1 lost Facebook's yes", AffiliateLinks.keepReelCard(1));
        assertFalse(AffiliateLinks.keepReelCard(0));
    }

    /** 585's floating card plugin answers a boolean, handed over as an int like the reel's. */
    @Test
    public void theCommentSheetsCardCheckIsAnsweredNo() {
        assertFalse("the comment sheet's card handed over as 1 stayed", AffiliateLinks.keepCommentCard(1));
        assertFalse("a post without a card got one", AffiliateLinks.keepCommentCard(0));
        assertEquals(FamilyNames.AFFILIATE_LINKS + ": invoked 2, 1 found, 0 missing. Counted: "
                + AffiliateLinks.COMMENT_CARD + " 1", statusLine());
        Settings.HIDE_AFFILIATE_LINKS.save(false);
        assertTrue("with the switch off, 1 lost Facebook's yes", AffiliateLinks.keepCommentCard(1));
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        Settings.HIDE_AFFILIATE_LINKS.save(true);
        assertTrue("a paused Hushfacebook hid the comment sheet's card", AffiliateLinks.keepCommentCard(true));
        PauseForTests.resume();
    }

    /**
     * #89: a reel's "Shop now" card for a creator's own product is a call-to-action kind of its own.
     * With no category left out, the filter answers null and the overlay would show the full list, so
     * the answer becomes the full list without the shop kinds.
     */
    @Test
    public void aReelsShopCardsLeaveItsCallToActionList() {
        List<Cta> all = Arrays.asList(Cta.COMMUNITY_NOTES, Cta.STOREFRONT, Cta.BOOST_REEL);
        assertEquals("the storefront card stayed on a reel",
                Arrays.asList(Cta.COMMUNITY_NOTES, Cta.BOOST_REEL), AffiliateLinks.keepReelCtas(null, all));
        assertEquals("the reel's own list changed", Arrays.asList(Cta.COMMUNITY_NOTES, Cta.STOREFRONT, Cta.BOOST_REEL), all);

        List<Cta> filtered = Arrays.asList(Cta.PRODUCT_TAGGING, Cta.PRODUCT_TAGGING_V2, Cta.CHAT_ON_WHATSAPP);
        assertEquals("tagged products stayed after Facebook's own filter",
                Collections.singletonList(Cta.CHAT_ON_WHATSAPP),
                AffiliateLinks.keepReelCtas(filtered, Arrays.asList(Cta.PRODUCT_TAGGING, Cta.PRODUCT_TAGGING_V2,
                        Cta.CHAT_ON_WHATSAPP, Cta.COMMUNITY_NOTES)));
        assertEquals("every shop kind didn't leave", Collections.emptyList(), AffiliateLinks.keepReelCtas(null,
                Arrays.asList(Cta.AFFILIATE_EYEBROW, Cta.SHOP_SIMILAR, Cta.AFFILIATE_BANNER)));
        assertEquals(FamilyNames.AFFILIATE_LINKS + ": invoked 3, 1 found, 0 missing. Counted: "
                + AffiliateLinks.REEL_SHOP_CARD + "STOREFRONT 1, "
                + AffiliateLinks.REEL_SHOP_CARD + "PRODUCT_TAGGING 1, "
                + AffiliateLinks.REEL_SHOP_CARD + "PRODUCT_TAGGING_V2 1, "
                + AffiliateLinks.REEL_SHOP_CARD + "AFFILIATE_EYEBROW 1, "
                + AffiliateLinks.REEL_SHOP_CARD + "SHOP_SIMILAR 1, "
                + AffiliateLinks.REEL_SHOP_CARD + "AFFILIATE_BANNER 1", statusLine());
    }

    /** A reel without a shop card keeps Facebook's own answer, null included, and so does anything odd. */
    @Test
    public void aReelWithoutAShopCardKeepsFacebooksAnswer() {
        assertNull("a reel without a shop card lost Facebook's null",
                AffiliateLinks.keepReelCtas(null, Arrays.asList(Cta.COMMUNITY_NOTES, Cta.BOOST_REEL)));
        List<Cta> filtered = Collections.singletonList(Cta.BOOST_REEL);
        assertSame(filtered, AffiliateLinks.keepReelCtas(filtered, Arrays.asList(Cta.BOOST_REEL, Cta.STOREFRONT)));
        assertNull(AffiliateLinks.keepReelCtas(null, null));
        assertNull(AffiliateLinks.keepReelCtas(null, Collections.emptyList()));
        assertNull("a kind that isn't an enum was read as a shop card",
                AffiliateLinks.keepReelCtas(null, Collections.singletonList("STOREFRONT")));
    }

    @Test
    public void offOrPausedAReelKeepsItsShopCards() {
        List<Cta> all = Arrays.asList(Cta.STOREFRONT, Cta.PRODUCT_TAGGING);
        Settings.HIDE_AFFILIATE_LINKS.save(false);
        assertNull(AffiliateLinks.keepReelCtas(null, all));
        Settings.HIDE_AFFILIATE_LINKS.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertNull("a Hushfacebook paused by " + reason + " hid a reel's shop card",
                    AffiliateLinks.keepReelCtas(null, all));
            PauseForTests.resume();
        }
    }
}
