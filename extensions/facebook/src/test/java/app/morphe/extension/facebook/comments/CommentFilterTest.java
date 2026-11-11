/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.facebook.graphql.model.GraphQLComment;
import com.facebook.graphql.model.GraphQLStory;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Hide comments with shop links and Hide comments with your words (#118): the comment section
 * asks about each comment before drawing it, and a comment whose words link to a shop, or hold a
 * phrase from Words to hide and none from the keep list, is left out while its switch is on. Off,
 * paused, a comment with no words, or anything that isn't a comment, and it's drawn. With Show only
 * comments that match on, a comment without the searched words is left out, a GIF or sticker with
 * no words included, new words build every comment list again and make the lists and comments drawn
 * under the old ones count as changed, and the lists don't page on by themselves while the box
 * holds words.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class CommentFilterTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** A comment list's section as the patch leaves it, with the helper that queues the filter's update. */
    public static final class ListSection {
        static final List<Object> QUEUED = new ArrayList<>();

        /** Queues on a context that's a String, as a live one would; anything else has lost its section. */
        public static boolean hushfacebookRefreshList(Object context) {
            QUEUED.add(context);
            return context instanceof String;
        }
    }

    @Before
    public void start() {
        HookStatus.clear();
        CommentText.cachedMembers = null;
        CommentText.wordsRead = false;
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_SHOP_LINK_COMMENTS.resetToDefault();
        Settings.HIDE_COMMENTS_WITH_WORDS.resetToDefault();
        Settings.HIDDEN_WORDS.resetToDefault();
        Settings.KEPT_WORDS.resetToDefault();
        Settings.POST_WORDS_WHOLE_WORDS.resetToDefault();
        Settings.SEARCH_COMMENTS.resetToDefault();
        Settings.FILTER_COMMENT_SEARCH.resetToDefault();
        CommentSearch.clearFilter();
        ListSection.QUEUED.clear();
        CommentText.cachedMembers = null;
        CommentText.wordsRead = false;
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.COMMENT_SHEET_OPTIONS + ":")) return line;
        }
        return null;
    }

    @Test
    public void shopLinksAreHostsNamingAShopWithOrWithoutTheirScheme() {
        assertTrue(ShopLinks.holds("Buy here https://s.shopee.vn/AbC123 cheap"));
        assertTrue(ShopLinks.holds("shopee.co.id/product/1/2"));
        assertTrue(ShopLinks.holds("Link: www.lazada.com.ph/products/x.html"));
        assertTrue(ShopLinks.holds("https://amzn.to/3xYz"));
        assertTrue(ShopLinks.holds("SHP.EE/abc"));
        assertTrue(ShopLinks.holds("go.shop.tiktok.com/view/1"));
        assertFalse("a shop named as a word isn't a link", ShopLinks.holds("I got it on Shopee, so good"));
        assertFalse("another site", ShopLinks.holds("see https://example.com/shopee"));
        assertFalse("a mail address", ShopLinks.holds("write to help@shopee.com"));
        assertFalse("a name inside a longer label", ShopLinks.holds("myshopeeblog.com"));
        assertFalse("no words", ShopLinks.holds(null));
        assertFalse("an abbreviation", ShopLinks.holds("e.g. this one"));
    }

    /**
     * Amazon's share links (a.co/d/...) and Shopee's affiliate ones (shope.ee/...) count too. A shop
     * written with no space after a full stop isn't a link: "Shopee.Fast" read as a host before.
     * A bare host with a real ending, in one case, still counts.
     */
    @Test
    public void shortLinksCountAndAMissingSpaceDoesNot() {
        assertTrue(ShopLinks.holds("got mine here https://a.co/d/0aBcDeF"));
        assertTrue(ShopLinks.holds("a.co/d/0aBcDeF"));
        assertTrue(ShopLinks.holds("shope.ee/9zKsAbC"));
        assertTrue("a bare host with a real ending", ShopLinks.holds("on shopee.vn now"));
        assertTrue("a bare host in capitals", ShopLinks.holds("ON SHOPEE.PH NOW"));
        assertFalse("no space after a full stop", ShopLinks.holds("Got mine on Shopee.Fast shipping"));
        assertFalse("a capital after the stop", ShopLinks.holds("Ordered from Lazada.It came today"));
        assertFalse("not a link ending", ShopLinks.holds("loved temu.never again"));
    }

    @Test
    public void theTypeTagIsTextWithEntitiesNamesMd5() throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest("TextWithEntities".getBytes("UTF-8"));
        int tag = ((digest[0] & 0xff) << 24) | ((digest[1] & 0xff) << 16) | ((digest[2] & 0xff) << 8) | (digest[3] & 0xff);
        assertEquals(tag, CommentText.TEXT_TYPE_TAG);
        assertEquals("582's body accessor asks for this key", 0x2e39a2, CommentText.BODY_KEY);
    }

    @Test
    public void offNothingIsLeftOutAndNoCommentIsRead() {
        assertFalse(CommentSheetOptions.hidesComment(new GraphQLComment("https://s.shopee.vn/x")));
        // Only the hook itself is found: the comment's text reader was never asked.
        assertTrue(statusLine(), statusLine().contains("invoked 1, 1 found, 0 missing"));
    }

    @Test
    public void onACommentLinkingToAShopIsLeftOutAndCounted() {
        Settings.HIDE_SHOP_LINK_COMMENTS.save(true);
        assertTrue(CommentSheetOptions.hidesComment(new GraphQLComment("Mua ở đây https://s.shopee.vn/9zKs")));
        assertFalse("a comment without a link", CommentSheetOptions.hidesComment(new GraphQLComment("So cute!")));
        assertFalse("a comment with no body", CommentSheetOptions.hidesComment(new GraphQLComment(null)));
        assertFalse("a post isn't a comment", CommentSheetOptions.hidesComment(new GraphQLStory()));
        assertFalse("nothing handed over", CommentSheetOptions.hidesComment(null));
        String line = statusLine();
        assertTrue("the hook and the text reader are found: " + line, line.contains("invoked 5, 2 found, 0 missing"));
        assertTrue(line, line.contains(CommentSheetOptions.SHOP_LINK_HIDDEN + " 1"));
        assertFalse(line, line.contains(CommentSheetOptions.WORDS_HIDDEN));
    }

    @Test
    public void onACommentWithAWordToHideIsLeftOutUnlessAKeepWordIsThere() {
        Settings.HIDE_COMMENTS_WITH_WORDS.save(true);
        Settings.HIDDEN_WORDS.save("spoiler");
        Settings.KEPT_WORDS.save("no worries");
        assertTrue(CommentSheetOptions.hidesComment(new GraphQLComment("Huge SPOILER: he wins")));
        assertFalse("a keep word wins", CommentSheetOptions.hidesComment(new GraphQLComment("spoiler, no worries")));
        assertFalse("another comment", CommentSheetOptions.hidesComment(new GraphQLComment("Loved it")));
        assertFalse("the shop switch is off", CommentSheetOptions.hidesComment(new GraphQLComment("shopee.vn/x")));
        assertTrue(statusLine(), statusLine().contains(CommentSheetOptions.WORDS_HIDDEN + " 1"));

        Settings.HIDDEN_WORDS.save("");
        assertFalse("an empty list hides nothing", CommentSheetOptions.hidesComment(new GraphQLComment("spoiler")));
    }

    @Test
    public void searchedWordsLeaveOutTheCommentsWithoutThemAndBuildEveryListAgain() {
        Settings.SEARCH_COMMENTS.save(true);
        Settings.FILTER_COMMENT_SEARCH.save(true);
        ListSection list = new ListSection();
        ListSection replies = new ListSection();
        Object lost = new Object();
        CommentSearch.listDrawn(list, "context");
        CommentSearch.listDrawn(replies, lost);
        GraphQLComment match = new GraphQLComment("Loved the ENDING");
        GraphQLComment other = new GraphQLComment("So cute!");
        assertFalse("no words, every comment is drawn", CommentSheetOptions.hidesComment(match));
        assertFalse(CommentSheetOptions.hidesComment(other));
        assertFalse("nothing has changed yet", CommentSearch.redraws(match, other));
        assertFalse(CommentSearch.redraws(list, replies));

        CommentSearch.filterBy("ending");
        assertEquals("both lists are asked to build again", 2, ListSection.QUEUED.size());
        assertTrue(ListSection.QUEUED.containsAll(Arrays.asList("context", lost)));
        assertTrue("a comment drawn under older words draws again", CommentSearch.redraws(match, null));
        assertTrue("on either side", CommentSearch.redraws(null, other));
        assertTrue("so does a list", CommentSearch.redraws(new Object(), list));
        assertFalse("something never drawn doesn't", CommentSearch.redraws(new Object(), null));
        assertFalse(CommentSheetOptions.hidesComment(match));
        assertTrue("a comment without the words is left out", CommentSheetOptions.hidesComment(other));
        assertTrue("so is one with no words, only a GIF or a sticker", CommentSheetOptions.hidesComment(new GraphQLComment(null)));
        assertFalse("drawn under the new words, it's the same", CommentSearch.redraws(match, other));
        CommentSearch.listDrawn(list, "context");
        assertFalse(CommentSearch.redraws(list, null));
        String line = statusLine();
        assertTrue(line, line.contains(CommentSearch.FILTERED + " 1"));
        assertTrue(line, line.contains(CommentSheetOptions.SEARCH_HIDDEN + " 2"));

        ListSection.QUEUED.clear();
        CommentSearch.filterBy("ending");
        assertTrue("the same words build nothing again", ListSection.QUEUED.isEmpty());
        CommentSearch.filterBy("");
        assertEquals("clearing the box builds both again", 2, ListSection.QUEUED.size());
        assertFalse("and every comment is drawn", CommentSheetOptions.hidesComment(other));
    }

    /**
     * A comment with no body is left out while the box holds words once some comment's words have
     * been read. Before that the body's key may have moved, and a build missing a reader can't read
     * any comment, so those keep every comment.
     */
    @Test
    public void aCommentWithNoWordsIsLeftOutOnceWordsHaveBeenRead() {
        Settings.SEARCH_COMMENTS.save(true);
        Settings.FILTER_COMMENT_SEARCH.save(true);
        CommentSearch.filterBy("ending");
        GraphQLComment gif = new GraphQLComment(null);
        assertFalse("no comment's words read yet", CommentSheetOptions.hidesComment(gif));
        assertFalse(CommentSheetOptions.hidesComment(new GraphQLComment("Loved the ending")));
        assertTrue("then a comment with no body has no words", CommentSheetOptions.hidesComment(gif));
        assertEquals("", CommentText.body(gif));

        CommentText.Members found = CommentText.members();
        CommentText.cachedMembers = new CommentText.Members(found.comment, found.text, null, found.cachedString);
        assertFalse("text that can't be read is kept", CommentSheetOptions.hidesComment(new GraphQLComment("So cute!")));
        assertFalse(CommentSheetOptions.hidesComment(gif));
        CommentText.cachedMembers = found;
        CommentSearch.filterBy("");
        assertFalse("an empty box draws it", CommentSheetOptions.hidesComment(gif));
    }

    /** A comment list pages on by itself unless the filter is on and the box holds words. */
    @Test
    public void pagingIsHeldOnlyWhileTheBoxHoldsWords() {
        assertFalse("off", CommentSearch.pagingHeld());
        Settings.SEARCH_COMMENTS.save(true);
        assertFalse("search without the filter", CommentSearch.pagingHeld());
        Settings.FILTER_COMMENT_SEARCH.save(true);
        assertFalse("an empty box", CommentSearch.pagingHeld());
        CommentSearch.filterBy("ending");
        assertTrue("words in the box", CommentSearch.pagingHeld());
        CommentSearch.filterBy("");
        assertFalse("the box cleared", CommentSearch.pagingHeld());
        CommentSearch.filterBy("ending");
        Settings.FILTER_COMMENT_SEARCH.save(false);
        assertFalse("the filter switched off with words left", CommentSearch.pagingHeld());
        String line = statusLine();
        assertTrue("asked three times while filtering, the hook found: " + line, line.contains("invoked 3, 1 found, 0 missing"));
        assertTrue(line, line.contains(CommentSearch.PAGING_HELD + " 1"));
    }

    @Test
    public void searchFilterOffNothingIsNotedOrBuiltAgain() {
        Settings.SEARCH_COMMENTS.save(true);
        ListSection list = new ListSection();
        CommentSearch.listDrawn(list, "context");
        CommentSearch.filterBy("ending");
        assertTrue(ListSection.QUEUED.isEmpty());
        GraphQLComment other = new GraphQLComment("So cute!");
        assertFalse(CommentSheetOptions.hidesComment(other));
        assertFalse(CommentSearch.redraws(list, other));
    }

    @Test
    public void pausedEveryCommentIsDrawn() {
        Settings.HIDE_SHOP_LINK_COMMENTS.save(true);
        Settings.HIDE_COMMENTS_WITH_WORDS.save(true);
        Settings.HIDDEN_WORDS.save("spoiler");
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(CommentSheetOptions.hidesComment(new GraphQLComment("shopee.vn/x")));
        assertFalse(CommentSheetOptions.hidesComment(new GraphQLComment("a spoiler")));
    }

    @Test
    public void aBuildMissingAReaderDrawsEveryCommentAndSaysWhatIsMissing() {
        Settings.HIDE_SHOP_LINK_COMMENTS.save(true);
        CommentText.Members found = CommentText.members();
        CommentText.cachedMembers = new CommentText.Members(found.comment, found.text, null, found.cachedString);
        assertNull(CommentText.body(new GraphQLComment("shopee.vn/x")));
        assertFalse(CommentSheetOptions.hidesComment(new GraphQLComment("shopee.vn/x")));
        assertTrue(statusLine(), statusLine().contains("getCachedModel"));
    }
}
