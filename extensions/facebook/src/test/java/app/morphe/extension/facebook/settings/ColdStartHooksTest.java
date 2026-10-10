/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.widget.TextView;

import com.facebook.graphql.model.GraphQLPagesYouMayLikeFeedUnit;
import com.facebook.graphql.model.GraphQLStory;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.facebook.ads.GameAds;
import app.morphe.extension.facebook.ads.MarketplaceAdFilterForTests;
import app.morphe.extension.facebook.ads.ProfileAdFilterForTests;
import app.morphe.extension.facebook.ads.ReelsAdFilter;
import app.morphe.extension.facebook.ads.SearchAdFilterForTests;
import app.morphe.extension.facebook.chats.ChatListForTests;
import app.morphe.extension.facebook.chats.MessengerCardForTests;
import app.morphe.extension.facebook.chats.MessengerIconForTests;
import app.morphe.extension.facebook.chats.OriginalChatMediaForTests;
import app.morphe.extension.facebook.composer.TagSuggestionsForTests;
import app.morphe.extension.facebook.download.ClipboardLinkForTests;
import app.morphe.extension.facebook.download.MediaDownload;
import app.morphe.extension.facebook.download.PhotoMenuItemForTests;
import app.morphe.extension.facebook.download.PlayerSourcesForTests;
import app.morphe.extension.facebook.download.ReelDownload;
import app.morphe.extension.facebook.download.VideoMenuItemForTests;
import app.morphe.extension.facebook.emoji.SystemEmoji;
import app.morphe.extension.facebook.feed.FeedFilter;
import app.morphe.extension.facebook.feed.SeenPostsForTests;
import app.morphe.extension.facebook.feed.FeedGuardForTests;
import app.morphe.extension.facebook.feed.ProfileSuggestionsForTests;
import app.morphe.extension.facebook.feed.ReturnRefresh;
import app.morphe.extension.facebook.feed.TypedFeedUnit;
import app.morphe.extension.facebook.font.OwnFont;
import app.morphe.extension.facebook.comments.DefaultCommentOrderForTests;
import app.morphe.extension.facebook.comments.CommentSheetOptions;
import app.morphe.extension.facebook.comments.MetaAiSummaries;
import app.morphe.extension.facebook.media.HdrBrightnessForTests;
import app.morphe.extension.facebook.media.PictureInPictureForTests;
import app.morphe.extension.facebook.media.ProgressBar;
import app.morphe.extension.facebook.media.QualityChoiceForTests;
import app.morphe.extension.facebook.media.ReelSpeedForTests;
import app.morphe.extension.facebook.media.ResumePlaybackForTests;
import app.morphe.extension.facebook.misc.AppLockForTests;
import app.morphe.extension.facebook.misc.TextSizeForTests;
import app.morphe.extension.facebook.theme.AccentColor;
import app.morphe.extension.facebook.media.TapToPlay;
import app.morphe.extension.facebook.media.TapToPlayForTests;
import app.morphe.extension.facebook.menu.MenuSectionsForTests;
import app.morphe.extension.facebook.misc.MetaUpsells;
import app.morphe.extension.facebook.menu.MenuSettingsRow;
import app.morphe.extension.facebook.misc.AnalyticsUploads;
import app.morphe.extension.facebook.misc.AppIcons;
import app.morphe.extension.facebook.misc.ScreenshotDetection;
import app.morphe.extension.facebook.misc.Haptics;
import app.morphe.extension.facebook.misc.ScreenTransitionsForTests;
import app.morphe.extension.facebook.misc.Screenshots;
import app.morphe.extension.facebook.misc.ShareSheetItems;
import app.morphe.extension.facebook.misc.ExternalBrowser;
import app.morphe.extension.facebook.misc.LinkCleaner;
import app.morphe.extension.facebook.navigation.MarketplaceOnlyForTests;
import app.morphe.extension.facebook.navigation.MarketplaceSellerProfileForTests;
import app.morphe.extension.facebook.navigation.HiddenTabsForTests;
import app.morphe.extension.facebook.navigation.ReelsTabForTests;
import app.morphe.extension.facebook.navigation.StartTabRouteForTests;
import app.morphe.extension.facebook.notifications.NotificationKindsForTests;
import app.morphe.extension.facebook.reels.DoubleTapLike;
import app.morphe.extension.facebook.reels.ReelHold;
import app.morphe.extension.facebook.reels.ReelHoldForTests;
import app.morphe.extension.facebook.reels.ReelCleanMode;
import app.morphe.extension.facebook.reels.ReelDeclutter;
import app.morphe.extension.facebook.reels.ReelMidCardsForTests;
import app.morphe.extension.facebook.reels.SeenStateSendForTests;
import app.morphe.extension.facebook.search.MetaAiSearchForTests;
import app.morphe.extension.facebook.stories.StoryAdvance;
import app.morphe.extension.facebook.stories.StorySeenForTests;
import app.morphe.extension.facebook.stories.SuggestedStoriesForTests;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * A start in which one of Facebook's early threads wins the race: every hook called before the
 * application hands Hushfacebook its context, and setContext after them.
 *
 * <p>A hook that reads a switch before its guard fails Setting's static initialiser, which needs
 * the context, and then the read setContext makes to decide the pause throws NoClassDefFoundError
 * out of the start. PausedHooksTest can't see that: its sandbox loaded the settings classes with a
 * context first, so a read there just answers. This is the only class at sdk 33, so it runs in a
 * sandbox of its own where nothing has loaded them yet, and it declares no SettingsContextRule,
 * which would load them. Give another class sdk 33 and this stops proving anything.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class ColdStartHooksTest {

    /** Stands in for GraphQLFeedStoryCategory: only the constant names matter to the guard. */
    enum Category { ORGANIC, SPONSORED, FB_SHORTS, SHOWCASE }

    /** Stands in for the showcase story type enum: only the constant names matter to the rule. */
    enum ShowcaseStoryType { SHOWCASE_SHORT_VIDEO }

    /** Stands in for Facebook's font family enum: only the constant's name matters to the swap. */
    enum FontFamily { OPTIMISTIC_TEXT_APP_BOLD }

    /** Stands in for the obfuscated ad item base class; the patch passes its binary name. */
    public static class AdBase {
    }

    public static final class VideoAd extends AdBase {
    }

    public static final class Reel {
    }

    public static final class Section {
        List<Object> items;

        Section(List<Object> items) {
            this.items = items;
        }
    }

    public static final class StoryCard {
        public Object getMedia() {
            return null;
        }
    }

    @Test
    public void everyHookBeforeTheContextLeavesTheSettingsUsable() {
        Context app = RuntimeEnvironment.getApplication();
        String ad = AdBase.class.getName();
        assertFalse("something set the context before this test", Utils.settingsReady());

        // Every hook a switch runs, with the arguments PausedHooksTest's probes use, and the
        // public feed guard as the patch calls it. Each has to take Facebook's path.
        assertFalse(FeedGuardForTests.hides(Category.SPONSORED, new Object()));
        assertFalse(FeedGuardForTests.hides(Category.ORGANIC, new GraphQLPagesYouMayLikeFeedUnit()));
        assertFalse(FeedGuardForTests.hidesRecommended(Category.ORGANIC, new GraphQLStory(),
                FeedGuardForTests.recommendationContext(true)));
        assertFalse(FeedGuardForTests.hides(Category.ORGANIC, TypedFeedUnit.peopleYouMayKnow()));
        assertFalse(FeedGuardForTests.hides(Category.ORGANIC, TypedFeedUnit.suggestedGroups()));
        assertFalse(FeedGuardForTests.hides(Category.ORGANIC, FeedGuardForTests.storiesRow(true)));
        assertEquals(1, FeedFilter.storiesTrayCount(new Object(), FeedFilter.LEGACY_TRAY, 1));
        assertEquals(1, FeedFilter.storiesTrayCount(new Object(), FeedFilter.UNIFIED_TRAY, 1));
        assertEquals(1, FeedFilter.storiesTrayCount(new Object(), FeedFilter.HOME_COMPOSER, 1));
        assertFalse(FeedGuardForTests.hidesReels(Category.FB_SHORTS, new Object()));
        assertFalse(FeedGuardForTests.hidesShowcaseReels(Category.SHOWCASE, ShowcaseStoryType.SHOWCASE_SHORT_VIDEO));
        assertFalse(FeedFilter.hidePreEofReels());
        ReturnRefresh.uiHidden();
        assertFalse("a return before the context kept the feed on resume", ReturnRefresh.skip());
        ReturnRefresh.uiHidden();
        assertFalse("a return before the context kept the feed at its warm start", ReturnRefresh.holdWarmStart());
        ReturnRefresh.uiHidden();
        assertFalse("a return before the context held the foreground auto-scroll", ReturnRefresh.holdAutoScroll());
        assertFalse("a feed left before the context skipped its teardown", ReturnRefresh.keepFeedWhileAway());
        assertFalse("a Home tab entry before the context held its hot load", ReturnRefresh.holdTabEntryHotLoad());
        assertFalse(FeedGuardForTests.hides(Category.ORGANIC, new GraphQLStory(), FeedGuardForTests.detectedInfo(true)));
        assertFalse(FeedGuardForTests.hidesLabelled(Category.ORGANIC, new GraphQLStory(),
                FeedGuardForTests.detectedInfo(false), FeedGuardForTests.selfDisclosureInfo(true)));
        assertFalse("a post with listed words before the context was hidden", FeedGuardForTests.hidesByWords(
                Category.ORGANIC, new GraphQLStory(), FeedGuardForTests.postText("Big SPOILER inside")));
        assertFalse("a photo post before the context was hidden", FeedGuardForTests.hidesPhotoPost(Category.ORGANIC));
        assertFalse("a popular post before the context was hidden", FeedGuardForTests.hidesPopularPost(Category.ORGANIC));
        assertFalse(FeedGuardForTests.hidesAiReel(new FeedGuardForTests.ReelItem(FeedGuardForTests.reelModel(true))));
        assertFalse("a seen post before the context was hidden", SeenPostsForTests.hidesBeforeTheContext());
        assertFalse("a start before the context locked Facebook", AppLockForTests.aStartLocks());
        assertFalse("a start before the context scaled Facebook's text", TextSizeForTests.aStartScales());
        assertEquals("a colour before the context took an accent", 0xFF0866FF,
                AccentColor.fds(0xFF0866FF, Thread.State.NEW));
        assertEquals("a Mig colour before the context took an accent", 0xFF0866FF,
                AccentColor.mig(0xFF0866FF, new Object()));
        FeedGuardForTests.ReelItem flaggedReel = new FeedGuardForTests.ReelItem(FeedGuardForTests.reelModel(true));
        Section reelSection = new Section(new ArrayList<>(Arrays.asList(new Reel(), flaggedReel)));
        FeedGuardForTests.aiReelSections(Collections.singletonList(reelSection));
        assertTrue(reelSection.items.contains(flaggedReel));
        assertFalse(FeedFilter.hideEdge(Category.SPONSORED, new Object()));
        assertFalse(FeedFilter.hideSponsoredStories());
        assertFalse("a Stories tray fetched before the context lost its suggestions",
                SuggestedStoriesForTests.hidesSuggestions());
        VideoAd reelAd = new VideoAd();
        assertTrue(ReelsAdFilter.withoutAds(Arrays.asList(new Reel(), reelAd), ad).contains(reelAd));
        VideoAd sectionAd = new VideoAd();
        Section section = new Section(new ArrayList<>(Arrays.asList(new Reel(), sectionAd)));
        ReelsAdFilter.withoutAdSections(Collections.singletonList(section), ad);
        assertTrue(section.items.contains(sectionAd));
        assertFalse("a search page built before the context lost its ad", SearchAdFilterForTests.dropsAnAd());
        assertFalse("a profile row drawn before the context was left out", ProfileAdFilterForTests.hidesASponsoredStory());
        assertFalse("a Marketplace feed query sent before the context skipped its ads",
                MarketplaceAdFilterForTests.asksTheFeedToSkipAds());
        assertFalse("a Marketplace ads query sent before the context was held back",
                MarketplaceAdFilterForTests.holdsBackAnAdsQuery());
        assertFalse("a Marketplace search answer read before the context lost its ad",
                MarketplaceAdFilterForTests.dropsASearchAd());
        Activity browser = Robolectric.buildActivity(Activity.class,
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/"))).create().get();
        assertFalse(ExternalBrowser.redirect(browser, browser.getIntent()));
        assertFalse(MediaDownload.saveStory(app, new StoryCard()));
        assertFalse(MediaDownload.offersSave(false));
        assertTrue("Facebook's own yes has to stand", MediaDownload.offersSave(true));
        assertFalse(ReelDownload.showsButton());
        assertNull(ReelDeclutter.filterChips(Arrays.asList(new TypedFeedUnit("XFBFBShortsRemixAttribution"))));
        assertFalse(ReelDeclutter.hideFollowButton());
        assertFalse(ReelDeclutter.hideFollowingButton());
        assertFalse(ReelDeclutter.skipHotComment());
        assertFalse(ReelDeclutter.skipSocialBubbles());
        assertFalse("a Threads card read before the context came off", ReelMidCardsForTests.dropsAThreadsCard());
        assertFalse("a reel made before the context started in Clean mode", ReelCleanMode.startClean(false));
        assertFalse("a batch of watched reels sent before the context was held back", SeenStateSendForTests.heldBack());
        assertFalse("an analytics upload before the context was held back", AnalyticsUploads.holdXAnalyticsUpload());
        assertNull("a game's ad before the context was answered with no ad",
                GameAds.heldPromise("{\"type\":\"loadadasync\",\"content\":{\"promiseID\":\"1\"}}"));
        assertTrue("a Papaya job before the context was held back", AnalyticsUploads.papayaOn(true));
        assertEquals("a window's secure flag before the context was taken out", 0x2000, Screenshots.layoutFlags(0x2000));
        assertTrue("a tab before the context lost its slide", ScreenTransitionsForTests.slides());
        assertFalse("the app icon benefit before the context was answered yes", AppIcons.unlocked(AppIcons.BENEFIT));
        assertFalse("the picker's benefit set before the context was answered yes", AppIcons.entitled(false));
        assertFalse("an app icon before the context was kept from the update reset",
                AppIcons.keepsIcon(new ComponentName("com.facebook.katana", AppIcons.LAUNCHER)));
        assertFalse("a picture-in-picture check before the context said yes", PictureInPictureForTests.allowsWithTheFeature());
        assertFalse("the Reels viewer's gate before the context said yes", PictureInPictureForTests.surfaceAllows());
        assertFalse("the Watch viewer's flag before the context said yes", PictureInPictureForTests.immersiveAllows());
        assertFalse("the Video tab's gate before the context said yes", PictureInPictureForTests.homeGateAllows());
        assertFalse("the Video tab's flag before the context said yes", PictureInPictureForTests.homeFlagAllows());
        assertFalse("an HDR window kept in the usual range before the context said yes",
                HdrBrightnessForTests.keepsAnHdrWindowInTheUsualRange());
        assertFalse("a headroom held before the context said yes", HdrBrightnessForTests.holdsTheHeadroom());
        assertFalse("the screen answered as showing no HDR before the context said yes",
                HdrBrightnessForTests.answersTheScreenAsShowingNoHdr());
        assertFalse("a reel's bar kept full size before the context said yes", ProgressBar.keepsReelBar());
        assertFalse("a fade timer skipped before the context said yes", ProgressBar.keepsControls());
        assertTrue("a haptic before the context was held back", Haptics.performHapticFeedback(new android.view.View(app) {
            @Override
            public boolean performHapticFeedback(int feedbackConstant) {
                return true;
            }
        }, android.view.HapticFeedbackConstants.LONG_PRESS));
        assertFalse("a screenshot check before the context was blocked", ScreenshotDetection.ignoresChange());
        assertNotNull("a double tap before the context lost its handler", DoubleTapLike.handler(new Object()));
        assertNotNull("a double tap before the context lost its heart", DoubleTapLike.heart(new Object()));
        assertNotNull("a double tap like before the context lost its key", DoubleTapLike.likeKey("reel"));
        assertFalse("a like from a double tap before the context was held back", DoubleTapLike.holdBackLike("DOUBLE_TAP"));
        assertFalse("an attachment's double tap before the context was left unhandled", DoubleTapLike.holdBackTap());
        // In a build with Hold a reel for 2x, so each answer is the context check's.
        ReelHoldForTests.withHold(() -> {
            assertFalse("a long press on a reel before the context went to the speed-up", ReelHold.longPress(false));
            assertFalse("a hold before the context counted anywhere on a reel", ReelHold.anywhere(false));
            assertFalse("a press on a reel's right third before the context counted", ReelHoldForTests.pressAt(290, false));
            assertFalse("a reel before the context got a release listener", ReelHold.speedUp(false));
            ReelHold.held();
            assertEquals("a speed set before the context changed", 2f, ReelHold.speedSet(new Object(), 2f), 0f);
            assertEquals("a hold speed read before the context changed", 1.0, ReelHold.holdSpeed(1.0), 0.0);
            assertFalse("a lift after a hold before the context put a speed back", ReelHold.release(false));
            return true;
        });
        assertFalse("a lift's speed before the context was changed", ReelHoldForTests.putsBackTheSpeedBeforeAHold());
        assertFalse("stories viewed before the context were kept off their viewer lists", StorySeenForTests.holdsABatch());
        assertFalse("a marked story viewed before the context went out alone", StorySeenForTests.sendsOnlyTheMarked());
        assertFalse("a story viewed before the context got the Mark as seen button", StorySeenForTests.pointsTheButton());
        assertFalse("a story that finished before the context played again", StoryAdvance.loop());
        assertFalse(PlayerSourcesForTests.recordsAPlayer());
        assertFalse("a post menu built before the context got the video item", VideoMenuItemForTests.addsAnItem());
        assertFalse("a post menu built before the context got the photo item", PhotoMenuItemForTests.addsAnItem());
        assertFalse(PlayerSourcesForTests.recordsAVideoPlayer());
        assertFalse("a screen resumed before the context read the clipboard", ClipboardLinkForTests.readsTheClipboard());
        assertFalse("a start before the context asked Facebook for a tab", StartTabRouteForTests.routes());
        assertFalse("a tab bar built before the context lost Home", MarketplaceOnlyForTests.hidesHome());
        assertFalse("a feed warm-up before the context was skipped", MarketplaceOnlyForTests.skipsFeedPrefetch());
        assertFalse("notifications before the context were muted", MarketplaceOnlyForTests.quietsNotifications());
        assertFalse("a seller page before the context was given View profile",
                MarketplaceSellerProfileForTests.givesSellersViewProfile());
        assertFalse("a tab bar built before the context lost the Reels tab", ReelsTabForTests.hidesTheTab());
        assertFalse("a tab bar built before the context lost the Friends tab", HiddenTabsForTests.hidesTheTab());
        assertFalse("a comment request built before the context was given an order",
                DefaultCommentOrderForTests.asksForTheChosenOrder());
        assertFalse("a comment summary held before the context said yes",
                MetaAiSummaries.holds(MetaAiSummaries.SHEET_SUMMARY));
        assertFalse("a comment box drawn before the context lost its GIF button",
                CommentSheetOptions.holdsButton(CommentSheetOptions.GIF_BUTTON));
        assertFalse("a long press on Like before the context kept the reaction picker closed",
                CommentSheetOptions.skipReactionPicker());
        assertFalse("a comment drawn before the context had its reply thread open",
                CommentSheetOptions.openReplyThreads(false));
        assertFalse("a post's comments drawn before the context lost Related groups",
                CommentSheetOptions.holdsBottomContent(CommentSheetOptions.RELATED_GROUPS));
        assertFalse("a word typed before the context lost its tag suggestions", TagSuggestionsForTests.skipsAPlainWord());
        assertFalse("a list of people open before the context was closed", TagSuggestionsForTests.closesAListLeftOpen());
        assertFalse("a Chats list built before the context lost the Get Messenger card",
                MessengerCardForTests.hidesWithMessenger());
        assertFalse("a Chats list built before the context lost its notes tray", ChatListForTests.dropsTheNotesTiles());
        assertFalse("a Chats list built before the context lost its promotions", ChatListForTests.hidesAPromotion());
        assertFalse("a Messenger icon tapped before the context opened Messenger",
                MessengerIconForTests.opensMessenger());
        assertFalse("a photo sent before the context went out as the original", OriginalChatMediaForTests.sendsAPhotoAsIs());
        assertFalse("a photo sent before the context was handed over", OriginalChatMediaForTests.sendsAPhotoAsyncAsIs());
        assertFalse("a video sent before the context skipped its re-encode", OriginalChatMediaForTests.passesAVideo());
        assertFalse("a Menu built before the context lost its Upgrades", MenuSectionsForTests.hidesUpgrades());
        assertFalse("a profile built before the context lost People you may know",
                ProfileSuggestionsForTests.hidesTheCarousel());
        assertFalse("a Menu built before the context lost Also from Meta", MenuSectionsForTests.hidesServerAlsoFromMeta());
        assertTrue("a Menu list built before the context changed", MenuSettingsRow.withRow(Collections.emptyList()).isEmpty());
        assertTrue("an Edits flag read before the context lost its yes", MetaUpsells.editsHeader(true));
        assertTrue("a feed request made before the context stopped asking for the Edits pill", MetaUpsells.fetchEditsPill(true));
        assertTrue("a composer opened before the context lost its Threads prompt", MetaUpsells.threadsOnboarding(1));
        assertEquals("a post made before the context lost its Meta Verified sheet", Boolean.TRUE,
                MetaUpsells.metaVerifiedSheet(Boolean.TRUE));
        assertEquals("a header built before the context lost its Meta Verified label", "Meta Verified",
                MetaUpsells.metaVerifiedLabel("Meta Verified"));
        assertFalse("an avatar upsell drawn before the context drew nothing", MetaUpsells.hidesAvatarUpsell());
        assertFalse("a post button checked before the context lost Imagine me",
                MetaUpsells.hidesImagineCta(MetaUpsells.IMAGINE_ME_PLUGIN));
        assertFalse("a post button checked before the context lost its Meta AI button",
                MetaUpsells.hidesImagineCta(MetaUpsells.META_AI_POST_PLUGINS.get(0)));
        assertFalse("a caption read before the context lost Meta AI's deep dive", MetaUpsells.hidesDeepDiveBelowCaption());
        assertTrue("a composer opened before the context lost Imagine", MetaUpsells.imagineCapability(true));
        List<Thread.State> tools = Arrays.asList(Thread.State.values());
        assertSame("Create story's tools listed before the context changed", tools, MetaUpsells.storyTools(tools));
        assertSame("a share sheet built before the context changed", tools, MetaUpsells.shareTargets(tools));
        assertSame("a share sheet built before the context went through the item picks", tools, ShareSheetItems.targets(tools));
        assertFalse("a results page built before the context lost its Meta AI answer", MetaAiSearchForTests.hidesAnswer());
        assertFalse("a results page built before the context lost its Meta AI prompts", MetaAiSearchForTests.dropsPrompts());
        assertFalse("a suggestion parsed before the context lost its Meta AI route",
                MetaAiSearchForTests.stopsSuggestionRoute());
        // A push can start Facebook, so the notification hook can run this early.
        assertFalse("a trending video push before the context was blocked", NotificationKindsForTests.blocksTrendingVideo());
        assertFalse("a birthday push before the context was blocked", NotificationKindsForTests.blocksBirthday());
        assertFalse("a live video push before the context was blocked", NotificationKindsForTests.blocksLiveVideo());
        assertTrue("a player start before the context was held",
                TapToPlay.allowStart(new Object(), TapToPlayForTests.Trigger.BY_AUTOPLAY));
        assertTrue("an older player start before the context was held",
                TapToPlay.allowLegacyStart(new Object(), TapToPlayForTests.Trigger.BY_AUTOPLAY));
        assertSame("the Autoplay setting read before the context was changed", TapToPlayForTests.Autoplay.ON,
                TapToPlay.autoplaySetting(TapToPlayForTests.Autoplay.ON));
        assertFalse("a reel built before the context was given its play button", TapToPlay.showReelPlayButton(false));
        assertFalse("a long video started before the context was moved", ResumePlaybackForTests.resumesALongVideo());
        assertFalse("a reel started before the context got a picked speed", ReelSpeedForTests.keepsAPickedSpeed());
        assertFalse("a speed menu built before the context offered slower speeds", ReelSpeedForTests.offersSlowerSpeeds());
        assertFalse("a gear speed sheet built before the context offered slower speeds",
                ReelSpeedForTests.gearOffersSlowerSpeeds());
        assertFalse("a video started before the context played at the chosen quality",
                QualityChoiceForTests.playsTheChosenQuality());
        String shared = "https://www.facebook.com/share/p/1AbCdEf/?mibextid=WC7FNe";
        assertEquals("a link shared before the context was cleaned", shared, LinkCleaner.sanitizeShared(shared));
        assertSame("a typeface resolved before the context was swapped", Typeface.SERIF,
                OwnFont.replace(Typeface.SERIF, FontFamily.OPTIMISTIC_TEXT_APP_BOLD, 700));
        Object fontBuilder = new Object();
        OwnFont.rememberVariation(fontBuilder, "'wght' 700");
        assertSame("a typeface built before the context was swapped", Typeface.SERIF,
                OwnFont.replaceBuilt(Typeface.SERIF, fontBuilder));
        assertSame("React Native text drawn before the context was swapped", Typeface.SERIF,
                OwnFont.replaceReactNative(Typeface.SERIF, "Optimistic VF App Lite 500"));
        assertSame("Android's default bold read before the context was swapped", Typeface.DEFAULT_BOLD,
                OwnFont.defaultBold());
        TextView builtEarly = new TextView(RuntimeEnvironment.getApplication());
        Typeface given = builtEarly.getTypeface();
        OwnFont.textView(builtEarly);
        assertSame("a text view built before the context was given another typeface", given, builtEarly.getTypeface());
        // Facebook warms its emoji font from an app init task, which can ask the provider this early.
        assertNull("an emoji typeface asked for before the context was answered", SystemEmoji.typeface());
        assertFalse("a chat's big emoji asked for before the context lost Meta's picture", SystemEmoji.skipRemoteEmoji());

        // A hook that touched the settings above left them unusable, and this is where a real
        // start would crash. While setContext decides the pause the context is already set, so a
        // hook firing then still has to wait: a paused start must not run its patched path.
        boolean[] readyWhileDeciding = { true };
        boolean[] adKeptWhileDeciding = { false };
        PauseForTests.whileDeciding(() -> {
            readyWhileDeciding[0] = Utils.settingsReady();
            VideoAd early = new VideoAd();
            adKeptWhileDeciding[0] = ReelsAdFilter.withoutAds(Arrays.asList(new Reel(), early), ad).contains(early);
        });
        try {
            Utils.setContext(app);
        } catch (Throwable poisoned) {
            throw new AssertionError("a hook read a setting before the context was set, and setContext then threw",
                    poisoned);
        } finally {
            PauseForTests.whileDeciding(null);
        }
        assertFalse("hooks could read settings while the pause was still being decided", readyWhileDeciding[0]);
        assertTrue("a hook ran its patched path while the pause was still being decided", adKeptWhileDeciding[0]);
        assertTrue(Utils.settingsReady());
        assertTrue("the settings don't answer after a cold start", Settings.HIDE_SPONSORED_REELS.get());
    }
}
