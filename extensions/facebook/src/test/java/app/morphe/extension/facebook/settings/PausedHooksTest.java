/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.SystemClock;
import android.widget.FrameLayout;

import com.facebook.common.util.TriState;
import com.facebook.graphql.model.GraphQLPagesYouMayLikeFeedUnit;
import com.facebook.graphql.model.GraphQLStory;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.facebook.ads.AffiliateLinks;
import app.morphe.extension.facebook.ads.GameAds;
import app.morphe.extension.facebook.ads.MarketplaceAdFilterForTests;
import app.morphe.extension.facebook.ads.ProfileAdFilterForTests;
import app.morphe.extension.facebook.ads.FeedAdPills;
import app.morphe.extension.facebook.ads.ReelsAdFilter;
import app.morphe.extension.facebook.ads.SearchAdFilterForTests;
import app.morphe.extension.facebook.chats.ChatListForTests;
import app.morphe.extension.facebook.chats.MessengerCardForTests;
import app.morphe.extension.facebook.chats.MessengerIconForTests;
import app.morphe.extension.facebook.chats.OriginalChatMediaForTests;
import app.morphe.extension.facebook.chats.ReadReceipts;
import app.morphe.extension.facebook.chats.TypingIndicator;
import app.morphe.extension.facebook.download.ClipboardLinkForTests;
import app.morphe.extension.facebook.download.MediaDownload;
import app.morphe.extension.facebook.download.PhotoMenuItemForTests;
import app.morphe.extension.facebook.download.PhotoSave;
import app.morphe.extension.facebook.download.PlayerSourcesForTests;
import app.morphe.extension.facebook.download.ReelDownload;
import app.morphe.extension.facebook.download.SaveRulesForTests;
import app.morphe.extension.facebook.download.VideoMenuItemForTests;
import app.morphe.extension.facebook.emoji.SystemEmoji;
import app.morphe.extension.facebook.feed.AutoTranslationForTests;
import app.morphe.extension.facebook.feed.FeedFilter;
import app.morphe.extension.facebook.feed.SeenPostsForTests;
import app.morphe.extension.facebook.feed.FeedsHeader;
import app.morphe.extension.facebook.feed.FollowingHome;
import app.morphe.extension.facebook.feed.ReturnRefresh;
import app.morphe.extension.facebook.feed.FeedGuardForTests;
import app.morphe.extension.facebook.feed.MetaAiQuestions;
import app.morphe.extension.facebook.feed.PostDates;
import app.morphe.extension.facebook.feed.PostPrompts;
import app.morphe.extension.facebook.feed.ProfileSuggestionsForTests;
import app.morphe.extension.facebook.misc.AppLockForTests;
import app.morphe.extension.facebook.feed.TypedFeedUnit;
import app.morphe.extension.facebook.font.OwnFont;
import app.morphe.extension.facebook.comments.DefaultCommentOrderForTests;
import app.morphe.extension.facebook.comments.CommentSheetOptions;
import app.morphe.extension.facebook.comments.MetaAiSummaries;
import app.morphe.extension.facebook.composer.TagSuggestionsForTests;
import app.morphe.extension.facebook.media.HdrBrightnessForTests;
import app.morphe.extension.facebook.media.PictureInPictureForTests;
import app.morphe.extension.facebook.media.ProgressBar;
import app.morphe.extension.facebook.media.QualityChoiceForTests;
import app.morphe.extension.facebook.media.ReelSpeedForTests;
import app.morphe.extension.facebook.media.ResumePlaybackForTests;
import app.morphe.extension.facebook.media.TapToPlay;
import app.morphe.extension.facebook.media.TapToPlayForTests;
import app.morphe.extension.facebook.menu.MenuSectionsForTests;
import app.morphe.extension.facebook.misc.MetaUpsells;
import app.morphe.extension.facebook.misc.AnalyticsUploads;
import app.morphe.extension.facebook.misc.AppIcons;
import app.morphe.extension.facebook.misc.Haptics;
import app.morphe.extension.facebook.misc.ScreenshotDetection;
import app.morphe.extension.facebook.misc.ScreenTransitionsForTests;
import app.morphe.extension.facebook.misc.Screenshots;
import app.morphe.extension.facebook.misc.ExternalBrowser;
import app.morphe.extension.facebook.misc.LinkCleaner;
import app.morphe.extension.facebook.misc.OwnPostLink;
import app.morphe.extension.facebook.misc.ShareSheetGroups;
import app.morphe.extension.facebook.navigation.BottomTabBar;
import app.morphe.extension.facebook.navigation.TabBarScrollAway;
import app.morphe.extension.facebook.navigation.MarketplaceOnlyForTests;
import app.morphe.extension.facebook.navigation.MarketplaceSellerProfileForTests;
import app.morphe.extension.facebook.navigation.HiddenTabsForTests;
import app.morphe.extension.facebook.navigation.ReelsTabForTests;
import app.morphe.extension.facebook.navigation.TabBadgesForTests;
import app.morphe.extension.facebook.navigation.StartTabRouteForTests;
import app.morphe.extension.facebook.notifications.NotificationKindsForTests;
import app.morphe.extension.facebook.reels.DoubleTapLike;
import app.morphe.extension.facebook.reels.ReelHold;
import app.morphe.extension.facebook.reels.ReelHoldForTests;
import app.morphe.extension.facebook.reels.ReelCleanMode;
import app.morphe.extension.facebook.reels.ReelDeclutter;
import app.morphe.extension.facebook.reels.ReelLoopForTests;
import app.morphe.extension.facebook.reels.ReelMidCardsForTests;
import app.morphe.extension.facebook.reels.ReelPrompts;
import app.morphe.extension.facebook.reels.SeenStateSendForTests;
import app.morphe.extension.facebook.search.MetaAiSearchForTests;
import app.morphe.extension.facebook.stories.StoryAdvance;
import app.morphe.extension.facebook.stories.StorySeenForTests;
import app.morphe.extension.facebook.stories.SuggestedStoriesForTests;
import app.morphe.extension.facebook.theme.ForceDarkModeForTests;
import app.morphe.extension.facebook.updates.UpdatePrompts;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * What Pause and safe mode promise: every hook a switch runs takes Facebook's own path, and every
 * saved value stays as it is. Lock Facebook is the one switch that keeps working ({@link #kept}).
 *
 * <p>Each probe is one hook with its switch on. It must change Facebook's behaviour while
 * Hushfacebook runs, which is the control, and leave it alone while paused. A family that gains a
 * switch without a probe here fails the first test.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PausedHooksTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for GraphQLFeedStoryCategory: only the constant names matter to the guard. */
    enum Category { ORGANIC, SPONSORED, PROMOTION, FB_SHORTS, SHOWCASE }

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

    /** A section wrapper: the screen reads the list it holds. */
    public static final class Section {
        List<Object> items;

        Section(List<Object> items) {
            this.items = items;
        }
    }

    /**
     * A story card. Hushfacebook's save asks it for its media before anything else, so a card
     * nobody asked was left to Facebook's own save.
     */
    public static final class StoryCard {
        boolean asked;

        public Object getMedia() {
            asked = true;
            return null;
        }
    }

    /** One hook with its switch on: true when it changed what Facebook would have done. */
    interface Probe {
        boolean changedFacebook();
    }

    private static final String AD = AdBase.class.getName();

    /** A reel overlay call-to-action kind named like Facebook's storefront card. */
    private enum ReelCta { STOREFRONT }

    /** Stands in for Facebook's enum of Create story tools: the hook goes by the constant's name. */
    private enum StoryTool { TEXT_BASE, IMAGINE }

    /** Stands in for Facebook's enum of share sheet items, named the same way. */
    private enum ShareItem { SHARE_NOW, SHARE_TO_THREADS }

    @After
    public void restore() {
        PauseForTests.resume();
        for (BooleanSetting setting : settingsSwitches()) setting.resetToDefault();
        Settings.HIDDEN_WORDS.resetToDefault();
        Settings.HIDE_POSTS_OVER_REACTIONS.resetToDefault();
        FeedFilterCounters.clear();
        ReleaseCheckForTests.forget();
    }

    /**
     * The settings entry's own switches, which no family owns, one probe each, held to the same
     * promise as a family's: paused, or before the settings are ready, a start makes no request.
     */
    private static Map<BooleanSetting, Probe> entryProbes() {
        Map<BooleanSetting, Probe> probes = new LinkedHashMap<>();
        // Loads the check's own settings here, with the context, so a probe run without one reads
        // them rather than loading them.
        ReleaseCheck.Stored.CHECKED_AT.savedValue();
        // A Facebook start a day after the last try asks GitHub for the newest release.
        probes.put(Settings.CHECK_FOR_RELEASES, ReleaseCheckForTests::aStartAsksGitHub);
        probes.put(Settings.SAVED_SHORTCUT, SavedShortcutTest::aStartPublishes);
        // A cold start on a phone with a screen lock covers Facebook and asks for it.
        probes.put(Settings.APP_LOCK, AppLockForTests::aStartLocks);
        return probes;
    }

    /**
     * The switches the download patches share, one probe each, held to the same promise: paused,
     * or before the settings are ready, a save picks what it would with the switch off.
     */
    private static Map<BooleanSetting, Probe> downloadProbes() {
        Map<BooleanSetting, Probe> probes = new LinkedHashMap<>();
        // A save takes the H.264 picture over the sharper AV1 one.
        probes.put(Settings.DOWNLOAD_COMPATIBLE, SaveRulesForTests::picksAFileOtherAppsOpen);
        return probes;
    }

    /**
     * The switches that keep working while paused. Lock Facebook guards the phone's owner rather
     * than changing Facebook, and every way to pause is in reach of someone holding the phone: the
     * Pause switch, a marker file left over USB, safe mode after a few quick crashes. A method, not
     * a field, so the class doesn't load Settings before the rule hands it a context.
     */
    private static Set<BooleanSetting> kept() {
        return Collections.singleton(Settings.APP_LOCK);
    }

    /** The probes whose switch is in [kept] when [keep], and the others otherwise. */
    private static Map<BooleanSetting, Probe> keptOrNot(Map<BooleanSetting, Probe> probes, boolean keep) {
        Map<BooleanSetting, Probe> picked = new LinkedHashMap<>();
        for (Map.Entry<BooleanSetting, Probe> entry : probes.entrySet()) {
            if (kept().contains(entry.getKey()) == keep) picked.put(entry.getKey(), entry.getValue());
        }
        return picked;
    }

    /** Adds a line to [wrong] for every entry probe that didn't answer [changes]. */
    private static void everyEntryProbe(Map<BooleanSetting, Probe> probes, boolean changes, String when,
                                        List<String> wrong) {
        for (Map.Entry<BooleanSetting, Probe> entry : probes.entrySet()) {
            if (entry.getValue().changedFacebook() != changes) {
                wrong.add(entry.getKey().key + ", " + when + (changes ? ": left Facebook alone" : ": still changed Facebook"));
            }
        }
    }

    private static Map<PatchFamily, List<Probe>> probes() {
        Map<PatchFamily, List<Probe>> probes = new EnumMap<>(PatchFamily.class);
        // A sponsored and a promoted edge at the funnel, and an ad swapped into the feed over another edge.
        probes.put(PatchFamily.SPONSORED_POSTS, Arrays.asList(
                () -> FeedGuardForTests.hides(Category.SPONSORED, new Object()),
                () -> FeedGuardForTests.hides(Category.PROMOTION, new Object()),
                () -> FeedGuardForTests.swapHides(Category.SPONSORED, new Object()),
                // A feed ad's comments open without the floating ad button.
                () -> FeedAdPills.holdsAdPill(
                        "com.facebook.feedback.comments.plugins.indicatorpill.permalinkadsfloatingcta.PermalinkAdsFloatingCtaPlugin")));
        probes.put(PatchFamily.SUGGESTED_POSTS, Arrays.asList(
                () -> FeedGuardForTests.hides(Category.ORGANIC, new GraphQLPagesYouMayLikeFeedUnit()),
                // A story Facebook's own recommendation flag marks as suggested for you.
                () -> FeedGuardForTests.hidesRecommended(Category.ORGANIC, new GraphQLStory(),
                        FeedGuardForTests.recommendationContext(true)),
                () -> FeedGuardForTests.hides(Category.ORGANIC, TypedFeedUnit.peopleYouMayKnow()),
                () -> FeedGuardForTests.hides(Category.ORGANIC, TypedFeedUnit.suggestedGroups()),
                // A row of Stories from people you aren't connected to.
                () -> FeedGuardForTests.hides(Category.ORGANIC, FeedGuardForTests.storiesRow(true)),
                // Your own profile's People you may know carousel builds nothing.
                ProfileSuggestionsForTests::hidesTheCarousel));
        // Each of the feed's two Stories tray adapters, and the composer row's, new to the hook,
        // counts no rows.
        probes.put(PatchFamily.STORIES_TRAY, Arrays.asList(
                () -> FeedFilter.storiesTrayCount(new Object(), FeedFilter.LEGACY_TRAY, 1) == 0,
                () -> FeedFilter.storiesTrayCount(new Object(), FeedFilter.UNIFIED_TRAY, 1) == 0,
                () -> FeedFilter.storiesTrayCount(new Object(), FeedFilter.HOME_COMPOSER, 1) == 0));
        // A row of reels between posts, by its category and by its showcase story type, and the
        // Reels row the pre-EOF injector builds without passing the edge guard.
        probes.put(PatchFamily.FEED_REELS, Arrays.asList(
                () -> FeedGuardForTests.hidesReels(Category.FB_SHORTS, new Object()),
                () -> FeedGuardForTests.hidesShowcaseReels(Category.SHOWCASE, ShowcaseStoryType.SHOWCASE_SHORT_VIDEO),
                FeedFilter::hidePreEofReels));
        // The refresh controller's resume callback, the feed's warm-start check and the foreground
        // auto-scroll, each the first check of a return, the feed teardown while away, and the
        // hot-start check, stale-post executor, tab AUTO_REFRESH and the friendly feed's HOT_LOAD a
        // tab switch back to Home reaches.
        probes.put(PatchFamily.RETURN_REFRESH, Arrays.asList(
                () -> {
                    ReturnRefresh.uiHidden();
                    return ReturnRefresh.skip();
                },
                () -> {
                    ReturnRefresh.uiHidden();
                    return ReturnRefresh.holdWarmStart();
                },
                () -> {
                    ReturnRefresh.uiHidden();
                    return ReturnRefresh.holdAutoScroll();
                },
                ReturnRefresh::keepFeedWhileAway,
                () -> {
                    // Past the return the probes above started, so these are a tab switch inside the app.
                    SystemClock.sleep(60_000);
                    ReturnRefresh.hotStart();
                    return ReturnRefresh.holdWarmStart();
                },
                ReturnRefresh::holdStalePost,
                ReturnRefresh::holdTabAutoRefresh,
                ReturnRefresh::holdTabEntryHotLoad));
        // A story Facebook's own detection marked as made with AI, one only its creator labelled as AI,
        // and a reel whose GenAI attribution carries the detected flag, at both levels a page of reels
        // enters.
        probes.put(PatchFamily.AI_DETECTED_POSTS, Arrays.asList(
                () -> FeedGuardForTests.hides(Category.ORGANIC, new GraphQLStory(), FeedGuardForTests.detectedInfo(true)),
                () -> FeedGuardForTests.hidesLabelled(Category.ORGANIC, new GraphQLStory(),
                        FeedGuardForTests.detectedInfo(false), FeedGuardForTests.selfDisclosureInfo(true)),
                () -> FeedGuardForTests.hidesAiReel(new FeedGuardForTests.ReelItem(FeedGuardForTests.reelModel(true))),
                () -> {
                    FeedGuardForTests.ReelItem reel = new FeedGuardForTests.ReelItem(FeedGuardForTests.reelModel(true));
                    Section section = new Section(new ArrayList<>(Arrays.asList(new Reel(), reel)));
                    FeedGuardForTests.aiReelSections(Collections.singletonList(section));
                    return !section.items.contains(reel);
                }));
        // A story whose own words hold a phrase from the hide list, and one drawn as a photo, which
        // the kinds of post switches read without any list. Paused, the list reads empty too.
        // Saved here, with the context, so a probe run without one reads it rather than loading it.
        Settings.HIDDEN_WORDS.save("spoiler");
        Settings.HIDE_POSTS_OVER_REACTIONS.save(app.morphe.extension.facebook.feed.ReactionCeiling.K1);
        probes.put(PatchFamily.POST_WORDS, Arrays.asList(
                () -> FeedGuardForTests.hidesByWords(Category.ORGANIC, new GraphQLStory(),
                        FeedGuardForTests.postText("Big SPOILER inside")),
                () -> FeedGuardForTests.hidesPhotoPost(Category.ORGANIC),
                () -> FeedGuardForTests.hidesPopularPost(Category.ORGANIC)));
        // A story with a bumper is answered as one without, so no strip is drawn and no room kept, and a
        // header whose title plugin would add "Follow" is told it has nothing to add.
        probes.put(PatchFamily.POST_PROMPTS, Arrays.asList(
                () -> !PostPrompts.keep(true),
                () -> !PostPrompts.showFollowLink(true)));
        // A post the store remembers is dropped by the feed guard while the switch is on.
        probes.put(PatchFamily.SEEN_POSTS, Collections.singletonList(SeenPostsForTests::hidesARememberedPost));
        // The pill socket's yes for Meta AI's questions is answered as a no, so it draws no row for
        // them, and its default way of drawing a pill drops one typed meta_ai.
        probes.put(PatchFamily.META_AI_QUESTIONS, Arrays.asList(
                () -> !MetaAiQuestions.keep(1, MetaAiQuestions.META_AI_PILL),
                () -> MetaAiQuestions.dropsDefaultPill(MetaAiQuestions.META_AI_TYPE)));
        // A post header's yes to rotating its subtitle is answered as a no, so it keeps the one line.
        probes.put(PatchFamily.POST_DATES, Collections.singletonList(() -> !PostDates.cycling(true)));
        // A post marked for automatic translation reads as one to translate on request, and the
        // reels footer hears a caption can't be translated by itself, so it asks for nothing.
        probes.put(PatchFamily.AUTO_TRANSLATION, Arrays.asList(AutoTranslationForTests::keepsThePost,
                AutoTranslationForTests::keepsTheCaption));
        // The Feeds tab's yes to a title row is answered as a no, its filters go to a container
        // that's never on screen, and its posts get no room for them. The room follows what the
        // tab did with its filters, so that probe builds a tab first.
        probes.put(PatchFamily.FEEDS_HEADER, Arrays.asList(
                () -> !FeedsHeader.navBar(true),
                () -> FeedsHeader.hidesFilters(new FrameLayout(RuntimeEnvironment.getApplication())),
                () -> {
                    FeedsHeader.hidesFilters(new FrameLayout(RuntimeEnvironment.getApplication()));
                    return !FeedsHeader.roomForFilters(true);
                }));
        probes.put(PatchFamily.SPONSORED_STORIES, Collections.singletonList(FeedFilter::hideSponsoredStories));
        // A tray of a friend's bucket, a suggested one and one labelled SUGGESTED keeps only the friend's.
        probes.put(PatchFamily.SUGGESTED_STORIES, Collections.singletonList(SuggestedStoriesForTests::hidesSuggestions));
        probes.put(PatchFamily.STORY_AUTO_ADVANCE, Arrays.asList(StoryAdvance::waitForTap, StoryAdvance::loop));
        // The story viewer's report of the stories you viewed goes out whole, and with Mark as seen
        // on a marked card goes alone and the button follows the card on screen.
        probes.put(PatchFamily.STORY_SEEN, Arrays.asList(
                StorySeenForTests::holdsABatch,
                StorySeenForTests::sendsOnlyTheMarked,
                StorySeenForTests::pointsTheButton));
        probes.put(PatchFamily.SPONSORED_REELS, Arrays.asList(
                () -> {
                    VideoAd ad = new VideoAd();
                    return !ReelsAdFilter.withoutAds(Arrays.asList(new Reel(), ad), AD).contains(ad);
                },
                () -> {
                    VideoAd ad = new VideoAd();
                    Section section = new Section(new ArrayList<>(Arrays.asList(new Reel(), ad)));
                    ReelsAdFilter.withoutAdSections(Collections.singletonList(section), AD);
                    return !section.items.contains(ad);
                },
                () -> ReelsAdFilter.holdsAdPill(
                        "com.facebook.feedback.comments.plugins.indicatorpill.reelsadsfloatingcta.ReelsAdsFloatingCtaPlugin"),
                // A profile's Reels tab sends no query for its ads.
                ReelsAdFilter::holdProfileReelAds));
        // A search results page leaves its SEARCH_ADS module out.
        probes.put(PatchFamily.SPONSORED_SEARCH, Collections.singletonList(SearchAdFilterForTests::dropsAnAd));
        // A timeline story with sponsored data isn't drawn on a profile.
        probes.put(PatchFamily.SPONSORED_PROFILE_POSTS,
                Collections.singletonList(ProfileAdFilterForTests::hidesASponsoredStory));
        // Marketplace's feed query asks to skip its ads, an ads-only query isn't sent, and a search
        // answer loses its ad.
        probes.put(PatchFamily.SPONSORED_MARKETPLACE, Arrays.asList(
                MarketplaceAdFilterForTests::asksTheFeedToSkipAds,
                MarketplaceAdFilterForTests::holdsBackAnAdsQuery,
                MarketplaceAdFilterForTests::dropsASearchAd));
        // A game's ad load is answered with no ad.
        probes.put(PatchFamily.GAME_ADS, Collections.singletonList(
                () -> GameAds.heldPromise("{\"type\":\"loadadasync\",\"content\":{\"promiseID\":\"1\"}}") != null));
        // A reel's product card is answered away, and so are a feed post's product footer, the
        // comment sheet's floating card and a reel's storefront card.
        probes.put(PatchFamily.AFFILIATE_LINKS, Arrays.asList(
                () -> !AffiliateLinks.keepReelCard(true),
                () -> AffiliateLinks.keepFooter("footer") == null,
                () -> AffiliateLinks.keepCommentCard(new Object()) == null,
                () -> AffiliateLinks.keepReelCtas(null, Collections.singletonList(ReelCta.STOREFRONT)) != null));
        // A Remix chip under a reel, the Follow and Following buttons beside its author, both
        // footer queries, a Threads card between reels, and a reel starting outside Clean mode.
        probes.put(PatchFamily.REEL_DECLUTTER, Arrays.asList(
                () -> ReelDeclutter.filterChips(Arrays.asList(new TypedFeedUnit("XFBFBShortsRemixAttribution"))) != null,
                ReelDeclutter::hideFollowButton,
                ReelDeclutter::hideFollowingButton,
                ReelDeclutter::skipHotComment,
                ReelDeclutter::skipSocialBubbles,
                ReelMidCardsForTests::dropsAThreadsCard,
                () -> ReelCleanMode.startClean(false),
                ReelLoopForTests::stopsAReel));
        // A reel that would get the interest prompt is answered as one that doesn't.
        probes.put(PatchFamily.REEL_PROMPTS, Collections.singletonList(() -> !ReelPrompts.keep(true)));
        // The Reels batcher's send of the reels you watched never reaches its executor.
        probes.put(PatchFamily.REEL_WATCH_HISTORY, Collections.singletonList(SeenStateSendForTests::heldBack));
        // A double tap on a reel finds no handler and no heart, the reel like helper finds no key and
        // sends no like from a double tap, and a feed attachment leaves its double tap unhandled.
        probes.put(PatchFamily.DOUBLE_TAP_LIKE, Arrays.asList(
                () -> DoubleTapLike.handler(new Object()) == null,
                () -> DoubleTapLike.heart(new Object()) == null,
                () -> DoubleTapLike.likeKey("reel") == null,
                () -> DoubleTapLike.holdBackLike("DOUBLE_TAP"),
                DoubleTapLike::holdBackTap));
        // A long press on a reel goes to Facebook's speed-up wherever it lands, the reel gets its
        // release listener, a hold speed of normal becomes 2x, the lift of a hold puts the speed back,
        // and that lift's speed is the one the reel had before the hold.
        // Each in a build with Hold a reel for 2x, as its patch's status says in one.
        probes.put(PatchFamily.REEL_HOLD, Arrays.asList(
                () -> ReelHoldForTests.withHold(() -> ReelHold.longPress(false)),
                () -> ReelHoldForTests.withHold(() -> ReelHold.anywhere(false)),
                // With Only on the right edge on, a press on a reel's right third.
                () -> ReelHoldForTests.withHold(() -> ReelHoldForTests.pressAt(290, false)),
                () -> ReelHoldForTests.withHold(() -> ReelHold.speedUp(false)),
                () -> ReelHoldForTests.withHold(() -> ReelHold.holdSpeed(1.0) != 1.0),
                () -> ReelHoldForTests.withHold(() -> {
                    ReelHold.held();
                    return ReelHold.release(false);
                }),
                ReelHoldForTests::putsBackTheSpeedBeforeAHold));
        // A speed picked on a reel is set on the next reel the viewer starts, and one picked on a
        // feed video on the next feed video.
        probes.put(PatchFamily.KEEP_REEL_SPEED, Arrays.asList(ReelSpeedForTests::keepsAPickedSpeed,
                ReelSpeedForTests::keepsAPickedVideoSpeed, ReelSpeedForTests::offersSlowerSpeeds,
                ReelSpeedForTests::gearOffersSlowerSpeeds));
        // A player's start with no tap before it is held, and Facebook's Autoplay setting reads Off.
        probes.put(PatchFamily.TAP_TO_PLAY, Arrays.asList(
                () -> {
                    TapToPlayForTests.forget();
                    return !TapToPlay.allowStart(new Object(), TapToPlayForTests.Trigger.BY_AUTOPLAY);
                },
                () -> {
                    TapToPlayForTests.forget();
                    return !TapToPlay.allowLegacyStart(new Object(), TapToPlayForTests.Trigger.BY_AUTOPLAY);
                },
                () -> TapToPlay.autoplaySetting(TapToPlayForTests.Autoplay.ON) != TapToPlayForTests.Autoplay.ON,
                () -> TapToPlay.showReelPlayButton(false)));
        // A long video left at 5:00 is saved, and its next start seeks back there.
        probes.put(PatchFamily.RESUME_LONG_VIDEOS, Collections.singletonList(ResumePlaybackForTests::resumesALongVideo));
        // A new video's first choice plays the chosen quality rather than Facebook's.
        probes.put(PatchFamily.PLAYBACK_QUALITY, Collections.singletonList(QualityChoiceForTests::playsTheChosenQuality));
        // The repository's answer for one of Meta's families, a variable-font builder's, and React
        // Native's for a family Facebook registered there.
        probes.put(PatchFamily.SYSTEM_FONT, Arrays.asList(
                () -> OwnFont.replace(Typeface.SERIF, FontFamily.OPTIMISTIC_TEXT_APP_BOLD, -1) != Typeface.SERIF,
                () -> {
                    Object builder = new Object();
                    OwnFont.rememberVariation(builder, "'wght' 700");
                    return OwnFont.replaceBuilt(Typeface.SERIF, builder) != Typeface.SERIF;
                },
                () -> OwnFont.replaceReactNative(Typeface.SERIF, "Optimistic VF App Lite 500") != Typeface.SERIF));
        // The emoji typeface provider hears the phone's default typeface instead of running its own code,
        // and the maker of Meta's emoji picture addresses hears there's no picture for a chat's big emoji.
        probes.put(PatchFamily.SYSTEM_EMOJI, Arrays.asList(
                () -> SystemEmoji.typeface() != null,
                SystemEmoji::skipRemoteEmoji));
        probes.put(PatchFamily.EXTERNAL_BROWSER, Collections.singletonList(() -> {
            Activity browser = Robolectric.buildActivity(Activity.class,
                    new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/"))).create().get();
            boolean left = ExternalBrowser.redirect(browser, browser.getIntent());
            boolean started = shadowOf(browser).getNextStartedActivity() != null;
            assertEquals("the answer and what the browser did disagree", left, started);
            return left;
        }));
        probes.put(PatchFamily.STORY_DOWNLOAD, Arrays.asList(
                () -> {
                    StoryCard card = new StoryCard();
                    MediaDownload.saveStory(RuntimeEnvironment.getApplication(), card);
                    return card.asked;
                },
                // The menu offers Save on someone else's story.
                () -> MediaDownload.offersSave(false),
                // The recorder runs in every player Facebook builds, not only in stories.
                PlayerSourcesForTests::recordsAPlayer));
        // Every reel's sidebar gets the Download button.
        probes.put(PatchFamily.REEL_DOWNLOAD, Collections.singletonList(ReelDownload::showsButton));
        // A video post's menu gets Download to phone, and the video recorder keeps a player.
        // Its copied-link option reads the clipboard as a screen comes to the front.
        probes.put(PatchFamily.VIDEO_DOWNLOAD, Arrays.asList(
                VideoMenuItemForTests::addsAnItem,
                PlayerSourcesForTests::recordsAVideoPlayer,
                ClipboardLinkForTests::readsTheClipboard));
        // Every photo the viewer opens offers Save photo, and a photo post's menu gets Save photo.
        probes.put(PatchFamily.PHOTO_DOWNLOAD, Arrays.asList(
                () -> PhotoSave.offersSave(false),
                PhotoMenuItemForTests::addsAnItem));
        // A start from the launcher icon asks Facebook for the chosen tab.
        probes.put(PatchFamily.START_TAB, Collections.singletonList(StartTabRouteForTests::routes));
        // Home's request for its feed goes out for the most recent feed.
        probes.put(PatchFamily.FOLLOWING_HOME, Collections.singletonList(
                () -> FollowingHome.feedType(com.facebook.api.feedtype.FeedType.TOP_STORIES)
                        != com.facebook.api.feedtype.FeedType.TOP_STORIES));
        // The tab bar builder is told to leave Home out.
        probes.put(PatchFamily.MARKETPLACE_ONLY, Arrays.asList(
                MarketplaceOnlyForTests::hidesHome, MarketplaceOnlyForTests::quietsNotifications,
                MarketplaceOnlyForTests::skipsFeedPrefetch));
        // A seller's Marketplace page is told its View profile flag is on.
        probes.put(PatchFamily.SELLER_VIEW_PROFILE, Arrays.asList(
                MarketplaceSellerProfileForTests::givesSellersViewProfile,
                MarketplaceSellerProfileForTests::givesSellersViewProfileById));
        // The tab bar builder is told to leave the Reels tab out.
        // Facebook's push of its Reels launcher shortcut is held back too.
        probes.put(PatchFamily.REELS_TAB, Arrays.asList(ReelsTabForTests::hidesTheTab,
                ReelsTabForTests::dropsTheShortcut));
        // The tab bar's count for the Reels tab reads none.
        probes.put(PatchFamily.REELS_TAB_DOT, Collections.singletonList(ReelsTabForTests::clearsTheDot));
        // The tab bar's count for the Friends tab reads none, and a launcher writer puts 0 on the icon.
        probes.put(PatchFamily.TAB_BADGES, Arrays.asList(TabBadgesForTests::clearsTheFriendsTab,
                TabBadgesForTests::clearsTheIcon));
        // The Friends tab comes off the bar.
        probes.put(PatchFamily.HIDDEN_TABS, Collections.singletonList(HiddenTabsForTests::hidesTheTab));
        // Facebook's own override of where the tab bar goes reads YES, for the bottom, where it read NO.
        // With the bar at the bottom, Facebook's check of whether it slides away answers yes.
        probes.put(PatchFamily.BOTTOM_TAB_BAR, Arrays.asList(
                () -> BottomTabBar.override(TriState.NO.ordinal()) == TriState.YES.ordinal(),
                TabBarScrollAway::slidesAway));
        // Facebook's dark mode controller answers dark where it answered light.
        probes.put(PatchFamily.FORCE_DARK_MODE, Collections.singletonList(ForceDarkModeForTests::forcesDark));
        // A request for a post's comments that names no order asks for the chosen one.
        probes.put(PatchFamily.DEFAULT_COMMENT_ORDER,
                Collections.singletonList(DefaultCommentOrderForTests::asksForTheChosenOrder));
        // A comment sheet's summary and the one under a post get a no from their sockets' checks.
        probes.put(PatchFamily.META_AI_SUMMARIES, Arrays.asList(
                () -> MetaAiSummaries.holds(MetaAiSummaries.SHEET_SUMMARY),
                () -> MetaAiSummaries.holds(MetaAiSummaries.POST_SUMMARY)));
        // The comment box's check answers no for its GIF and sticker buttons, a long press on Like
        // returns before the reaction picker opens, a comment starts with its replies open, and the
        // check under a post's comments answers no for Related groups.
        probes.put(PatchFamily.COMMENT_SHEET_OPTIONS, Arrays.asList(
                () -> CommentSheetOptions.holdsButton(CommentSheetOptions.GIF_BUTTON),
                () -> CommentSheetOptions.holdsButton(CommentSheetOptions.STICKER_BUTTON),
                CommentSheetOptions::skipReactionPicker,
                () -> CommentSheetOptions.openReplyThreads(false),
                () -> CommentSheetOptions.holdsBottomContent(CommentSheetOptions.RELATED_GROUPS)));
        // A word without @ in a post or comment box looks nobody up, and a list of people left open
        // by an earlier @ is closed.
        probes.put(PatchFamily.TAG_SUGGESTIONS, Arrays.asList(
                TagSuggestionsForTests::skipsAPlainWord,
                TagSuggestionsForTests::closesAListLeftOpen));
        // With Messenger installed, the card's show question answers no in Chats.
        probes.put(PatchFamily.MESSENGER_CARD, Collections.singletonList(MessengerCardForTests::hidesWithMessenger));
        // The notes tray's tiles go in Chats, and a promotion banner's show question answers no.
        probes.put(PatchFamily.CHAT_LIST, Arrays.asList(
                ChatListForTests::dropsTheNotesTiles,
                ChatListForTests::hidesAPromotion));
        // With Messenger installed, a tap on the top bar's Messenger icon opens Messenger instead of Chats.
        probes.put(PatchFamily.MESSENGER_ICON, Collections.singletonList(MessengerIconForTests::opensMessenger));
        // The Menu's Upgrades and Also from Meta groups build nothing, in the section Facebook
        // draws and in the one carrying what the server sends.
        probes.put(PatchFamily.MENU_PROMOTIONS, Arrays.asList(
                MenuSectionsForTests::hidesUpgrades,
                MenuSectionsForTests::hidesAlsoFromMeta,
                MenuSectionsForTests::hidesServerUpgrades,
                MenuSectionsForTests::hidesServerAlsoFromMeta));
        // Edits' header flag and both shapes of its pill request, the Threads cross-posting
        // onboarding and the share sheet's Threads item, the Meta Verified sheet and label, an avatar
        // sticker upsell, Imagine's post button, composer capability and Create story tile, and the
        // other Meta AI post buttons, with the deep dive under a caption.
        probes.put(PatchFamily.META_UPSELLS, Arrays.asList(
                () -> !MetaUpsells.editsHeader(true),
                () -> !MetaUpsells.fetchEditsPill(true),
                () -> Boolean.FALSE.equals(MetaUpsells.fetchEditsPill(Boolean.TRUE)),
                () -> !MetaUpsells.threadsOnboarding(1),
                () -> MetaUpsells.shareTargets(Arrays.asList(ShareItem.values())).size() == 1,
                () -> Boolean.FALSE.equals(MetaUpsells.metaVerifiedSheet(Boolean.TRUE)),
                () -> MetaUpsells.metaVerifiedLabel("Meta Verified") == null,
                MetaUpsells::hidesAvatarUpsell,
                () -> MetaUpsells.hidesImagineCta(MetaUpsells.IMAGINE_ME_PLUGIN),
                () -> !MetaUpsells.imagineCapability(true),
                () -> MetaUpsells.storyTools(Arrays.asList(StoryTool.values())).size() == 1,
                () -> MetaUpsells.hidesImagineCta(MetaUpsells.META_AI_POST_PLUGINS.get(0)),
                MetaUpsells::hidesDeepDiveBelowCaption));
        // The share sheet's footer is built without Send to group, and a new-group entry answers off.
        probes.put(PatchFamily.SHARE_SHEET_ITEMS, Arrays.asList(
                () -> ShareSheetGroups.sendToGroupButton(new Object()) == null,
                () -> !ShareSheetGroups.offerNewGroup(true)));
        // Search leaves out its Meta AI answer and its prompt modules, and a suggestion set to open
        // Meta AI opens the results.
        probes.put(PatchFamily.META_AI_SEARCH, Arrays.asList(
                MetaAiSearchForTests::hidesAnswer,
                MetaAiSearchForTests::dropsPrompts,
                MetaAiSearchForTests::stopsSuggestionRoute));
        // XAnalytics' upload and uploader resume are skipped, and the Papaya job finishes unrun.
        probes.put(PatchFamily.ANALYTICS_UPLOADS, Arrays.asList(
                AnalyticsUploads::holdXAnalyticsUpload,
                () -> !AnalyticsUploads.papayaOn(true)));
        // A haptic Facebook asks for doesn't play.
        probes.put(PatchFamily.HAPTICS, Collections.singletonList(
                () -> !Haptics.performHapticFeedback(new android.view.View(RuntimeEnvironment.getApplication()) {
                    @Override
                    public boolean performHapticFeedback(int feedbackConstant) {
                        return true;
                    }
                }, android.view.HapticFeedbackConstants.LONG_PRESS)));
        // ReelsPipUtil's check and the Reels viewer's gate say yes on Android 12 with the phone's feature,
        // and so do the Watch viewer's flag and the Video tab's copy of the gate and its flag.
        probes.put(PatchFamily.PICTURE_IN_PICTURE, Arrays.asList(
                PictureInPictureForTests::allowsWithTheFeature, PictureInPictureForTests::surfaceAllows,
                PictureInPictureForTests::immersiveAllows, PictureInPictureForTests::homeGateAllows,
                PictureInPictureForTests::homeFlagAllows));
        // An HDR window comes out in the default colour mode, a headroom as none, and the screen
        // answers as one that shows no HDR.
        probes.put(PatchFamily.HDR_BRIGHTNESS, Arrays.asList(
                HdrBrightnessForTests::keepsAnHdrWindowInTheUsualRange, HdrBrightnessForTests::holdsTheHeadroom,
                HdrBrightnessForTests::answersTheScreenAsShowingNoHdr));
        // A reel's bar is kept full size, and a full-screen video sets no fade timer.
        probes.put(PatchFamily.PROGRESS_BAR, Arrays.asList(ProgressBar::keepsReelBar, ProgressBar::keepsControls));
        // A tab asked for shows without its slide.
        probes.put(PatchFamily.SCREEN_TRANSITIONS, Collections.singletonList(() -> !ScreenTransitionsForTests.slides()));
        // The app icon benefit is answered yes, at the provider's check and at the picker's own look,
        // and the update reset leaves a picked icon alone.
        probes.put(PatchFamily.APP_ICONS, Arrays.asList(
                () -> AppIcons.unlocked(AppIcons.BENEFIT), () -> AppIcons.entitled(false),
                () -> AppIcons.keepsIcon(new ComponentName("com.facebook.katana", AppIcons.LAUNCHER + ".vaporwave_ic"))));
        // A window's secure flag comes out.
        probes.put(PatchFamily.SCREENSHOTS, Collections.singletonList(
                () -> Screenshots.layoutFlags(0x2000) == 0));
        // A new picture in the photo library isn't looked at.
        probes.put(PatchFamily.SCREENSHOT_DETECTION, Collections.singletonList(
                ScreenshotDetection::ignoresChange));
        // "Typing" goes out as "not typing", and the chat and comment runnables skip their send.
        probes.put(PatchFamily.TYPING_INDICATOR, Arrays.asList(
                () -> !TypingIndicator.chatTyping(true), TypingIndicator::holdsChatTyping,
                TypingIndicator::holdsCommentTyping));
        // Mailbox's mark-read hands back its future without sending the read.
        probes.put(PatchFamily.READ_RECEIPTS, Collections.singletonList(ReadReceipts::holdsChatRead));
        // A standard JPEG goes out as its own bytes from both photo hooks, and a small video's size
        // check says it can skip the re-encode.
        probes.put(PatchFamily.ORIGINAL_CHAT_MEDIA, Arrays.asList(OriginalChatMediaForTests::sendsAPhotoAsIs,
                OriginalChatMediaForTests::sendsAPhotoAsyncAsIs, OriginalChatMediaForTests::passesAVideo));
        // A push of each kind a notification switch blocks isn't posted, asked at 11 PM, inside
        // the quiet hours every switch on turns on.
        probes.put(PatchFamily.PROMO_NOTIFICATIONS, Arrays.asList(
                NotificationKindsForTests::blocksTrendingVideo,
                NotificationKindsForTests::blocksMemory,
                NotificationKindsForTests::blocksBirthday,
                NotificationKindsForTests::blocksHighlights,
                NotificationKindsForTests::blocksPeopleYouMayKnow,
                NotificationKindsForTests::blocksNearby,
                NotificationKindsForTests::blocksGroupActivity,
                NotificationKindsForTests::blocksEventInvite,
                NotificationKindsForTests::blocksLiveVideo,
                NotificationKindsForTests::blocksReaction));
        // A shared link loses what the app added to it, and a post's /share/ link gives way to
        // its own address.
        probes.put(PatchFamily.SANITIZE_SHARING_LINKS, Arrays.asList(() -> {
            String shared = "https://www.facebook.com/share/p/1AbCdEf/?mibextid=WC7FNe";
            return !shared.equals(LinkCleaner.sanitizeShared(shared));
        }, () -> {
            String own = "https://www.facebook.com/story.php?story_fbid=1&id=2";
            return own.equals(OwnPostLink.shareLink("https://www.facebook.com/share/p/1AbCdEf/", own));
        }));
        // Both Meta App Manager promotion filters fail, the force-sync push is skipped, and the
        // chat filter that targets older versions fails.
        probes.put(PatchFamily.UPDATE_PROMPTS, Arrays.asList(
                UpdatePrompts::blockPromotion,
                UpdatePrompts::blockForceSync,
                () -> UpdatePrompts.blockVersionCeiling(UpdatePrompts.VERSION_CEILING_FILTER)));
        return probes;
    }

    /** Every switch the settings screen can show, read off the class so a new one can't hide. */
    static List<BooleanSetting> settingsSwitches() {
        List<BooleanSetting> switches = new ArrayList<>();
        for (Field field : Settings.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != BooleanSetting.class) continue;
            try {
                switches.add((BooleanSetting) field.get(null));
            } catch (IllegalAccessException unreadable) {
                throw new AssertionError(unreadable);
            }
        }
        return switches;
    }

    private static Set<PatchFamily> switched() {
        Set<PatchFamily> switched = EnumSet.noneOf(PatchFamily.class);
        for (PatchFamily family : PatchFamily.values()) {
            if (!family.switches.isEmpty()) switched.add(family);
        }
        return switched;
    }

    /** Adds a line to [wrong] for every probe that didn't answer [changes]. */
    private static void everyProbe(Map<PatchFamily, List<Probe>> probes, boolean changes, String when,
                                   List<String> wrong) {
        for (Map.Entry<PatchFamily, List<Probe>> entry : probes.entrySet()) {
            for (int i = 0; i < entry.getValue().size(); i++) {
                if (entry.getValue().get(i).changedFacebook() != changes) {
                    wrong.add(entry.getKey().patchName + ", probe " + i + ", " + when
                            + (changes ? ": left Facebook alone" : ": still changed Facebook"));
                }
            }
        }
    }

    @Test
    public void everyHookASwitchRunsTakesFacebooksOwnPathWhilePaused() {
        for (BooleanSetting setting : settingsSwitches()) setting.save(true);
        Map<PatchFamily, List<Probe>> probes = probes();
        assertEquals("every family with a switch needs a probe here", switched(), probes.keySet());
        Map<BooleanSetting, Probe> entry = entryProbes();
        assertEquals("every switch of the settings entry needs a probe here",
                new HashSet<>(PatchFamily.ENTRY_SWITCHES), entry.keySet());
        Map<BooleanSetting, Probe> downloads = downloadProbes();
        assertEquals("every switch the downloads share needs a probe here",
                new HashSet<>(PatchFamily.DOWNLOAD_SWITCHES), downloads.keySet());

        // Every hook is asked every time, so one run names every hook that broke the promise.
        List<String> wrong = new ArrayList<>();
        everyProbe(probes, true, "running", wrong);
        everyEntryProbe(entry, true, "running", wrong);
        everyEntryProbe(downloads, true, "running", wrong);

        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            everyProbe(probes, false, "paused by " + why, wrong);
            everyEntryProbe(keptOrNot(entry, false), false, "paused by " + why, wrong);
            // The lock still covers Facebook and asks, whatever paused it.
            everyEntryProbe(keptOrNot(entry, true), true, "paused by " + why, wrong);
            everyEntryProbe(downloads, false, "paused by " + why, wrong);
        }

        PauseForTests.resume();
        everyProbe(probes, true, "running again", wrong);
        everyEntryProbe(entry, true, "running again", wrong);
        everyEntryProbe(downloads, true, "running again", wrong);
        assertEquals(Collections.emptyList(), wrong);
    }

    /**
     * Facebook can call a hook before its application's onCreate hands Hushfacebook the context,
     * from a thread it starts early, and again while setContext is still deciding whether this
     * start runs paused. Until both are done, every hook takes Facebook's own path whatever is
     * saved (see Utils.settingsReady). This JVM's Setting class loaded with a context, so a hook
     * that reads its switch anyway answers on here and is named. Whether a hook reads a switch
     * before its guard, which is what crashes a start, is ColdStartHooksTest's to see.
     */
    @Test
    public void untilTheSettingsAreReadyEveryHookTakesFacebooksOwnPath() {
        for (BooleanSetting setting : settingsSwitches()) setting.save(true);
        Map<PatchFamily, List<Probe>> probes = probes();
        assertEquals("every family with a switch needs a probe here", switched(), probes.keySet());
        Map<BooleanSetting, Probe> entry = entryProbes();
        Map<BooleanSetting, Probe> downloads = downloadProbes();

        List<String> wrong = new ArrayList<>();
        SettingsContextRule.withoutContext(() -> {
            everyProbe(probes, false, "before the context is set", wrong);
            everyEntryProbe(entry, false, "before the context is set", wrong);
            everyEntryProbe(downloads, false, "before the context is set", wrong);
        });
        // Safe mode on, as after three crashed starts: the context is set and the pause undecided.
        BaseSettings.SAFE_MODE.save(true);
        try {
            SettingsContextRule.beforeThePauseIsDecided(() -> {
                everyProbe(probes, false, "before the pause is decided", wrong);
                everyEntryProbe(entry, false, "before the pause is decided", wrong);
                everyEntryProbe(downloads, false, "before the pause is decided", wrong);
            });
        } finally {
            BaseSettings.SAFE_MODE.resetToDefault();
        }
        everyProbe(probes, true, "once they're ready", wrong);
        everyEntryProbe(entry, true, "once they're ready", wrong);
        everyEntryProbe(downloads, true, "once they're ready", wrong);
        assertEquals(Collections.emptyList(), wrong);
    }

    @Test
    public void pausedEverySwitchAnswersOffAndKeepsWhatWasSaved() {
        List<BooleanSetting> switches = settingsSwitches();
        assertFalse("found no switches to check", switches.isEmpty());
        for (BooleanSetting setting : switches) {
            // A switch that keeps working through a pause is one kept() names, with its reason.
            assertEquals(setting.key + " keeps its value while paused", kept().contains(setting), setting.isKeptWhenPaused());
            setting.save(true);
        }

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        for (BooleanSetting setting : switches) {
            if (kept().contains(setting)) {
                assertTrue(setting.key + " answered off while paused", setting.get());
            } else {
                assertFalse(setting.key + " answered on while paused", setting.get());
            }
            assertTrue(setting.key + " lost what was saved", setting.savedValue());
        }

        PauseForTests.resume();
        for (BooleanSetting setting : switches) {
            assertTrue(setting.key + " stayed off after the pause ended", setting.get());
        }
    }

    /**
     * The Pause row and the paused card say Debug logging keeps working, which is how a paused
     * start gets logged for a report.
     */
    @Test
    public void debugLoggingKeepsWorkingWhilePaused() {
        BaseSettings.DEBUG.save(true);
        try {
            for (HushfacebookPause.Reason why : HushfacebookPause.Reason.values()) {
                if (why == HushfacebookPause.Reason.NONE) continue;
                PauseForTests.pause(why);
                assertTrue("Debug logging answered off while paused by " + why, BaseSettings.DEBUG.get());
            }
        } finally {
            BaseSettings.DEBUG.resetToDefault();
        }
    }
}
