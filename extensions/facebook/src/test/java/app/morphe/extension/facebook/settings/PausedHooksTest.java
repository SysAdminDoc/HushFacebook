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
import android.text.Spannable;
import android.text.SpannableString;
import android.view.View;
import android.widget.FrameLayout;

import com.facebook.common.util.TriState;
import com.facebook.graphql.model.GraphQLComment;
import com.facebook.graphql.model.GraphQLPagesYouMayLikeFeedUnit;
import com.facebook.graphql.model.GraphQLStory;
import com.facebook.graphservice.tree.TreeJNI;

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
import app.morphe.extension.facebook.chats.ChatList;
import app.morphe.extension.facebook.chats.ChatListForTests;
import app.morphe.extension.facebook.chats.MessengerCardForTests;
import app.morphe.extension.facebook.chats.MessengerHandoffForTests;
import app.morphe.extension.facebook.chats.MessengerIconForTests;
import app.morphe.extension.facebook.chats.OriginalChatMediaForTests;
import app.morphe.extension.facebook.chats.ReadReceipts;
import app.morphe.extension.facebook.chats.TypingIndicator;
import app.morphe.extension.facebook.download.ClipboardLinkForTests;
import app.morphe.extension.facebook.download.CommentGifForTests;
import app.morphe.extension.facebook.download.CommentVideoForTests;
import app.morphe.extension.facebook.download.MediaDownload;
import app.morphe.extension.facebook.download.PhotoMenuItemForTests;
import app.morphe.extension.facebook.download.PhotoSave;
import app.morphe.extension.facebook.download.PlayerSourcesForTests;
import app.morphe.extension.facebook.download.PostActionButtonsForTests;
import app.morphe.extension.facebook.download.ReelCaptionForTests;
import app.morphe.extension.facebook.download.ReelDownload;
import app.morphe.extension.facebook.download.SaveRulesForTests;
import app.morphe.extension.facebook.download.StoryPhoto;
import app.morphe.extension.facebook.download.VideoMenuItemForTests;
import app.morphe.extension.facebook.emoji.SystemEmoji;
import app.morphe.extension.facebook.feed.AutoTranslationForTests;
import app.morphe.extension.facebook.feed.FeedFilter;
import app.morphe.extension.facebook.feed.FindFriendsCard;
import app.morphe.extension.facebook.feed.SeenPostsForTests;
import app.morphe.extension.facebook.feed.FeedsHeader;
import app.morphe.extension.facebook.feed.FollowingHome;
import app.morphe.extension.facebook.feed.GroupPosts;
import app.morphe.extension.facebook.feed.HomeBack;
import app.morphe.extension.facebook.feed.ReturnRefresh;
import app.morphe.extension.facebook.feed.FeedGuardForTests;
import app.morphe.extension.facebook.feed.FullPostText;
import app.morphe.extension.facebook.feed.PostTranslation;
import app.morphe.extension.facebook.feed.PostTranslationForTests;
import app.morphe.extension.facebook.feed.MetaAiQuestions;
import app.morphe.extension.facebook.feed.NotificationFooter;
import app.morphe.extension.facebook.feed.PostDates;
import app.morphe.extension.facebook.feed.PostPrompts;
import app.morphe.extension.facebook.feed.ProfileSuggestionsForTests;
import app.morphe.extension.facebook.feed.RelatedPosts;
import app.morphe.extension.facebook.misc.AppLockForTests;
import app.morphe.extension.facebook.feed.TypedFeedUnit;
import app.morphe.extension.facebook.feed.WhoSharedForTests;
import app.morphe.extension.facebook.font.OwnFont;
import app.morphe.extension.facebook.comments.DefaultCommentOrderForTests;
import app.morphe.extension.facebook.comments.CommentBlock;
import app.morphe.extension.facebook.comments.CommentSheetOptions;
import app.morphe.extension.facebook.comments.FullHeightComments;
import app.morphe.extension.facebook.comments.MetaAiSummaries;
import app.morphe.extension.facebook.composer.TagSuggestionsForTests;
import app.morphe.extension.facebook.media.HdrBrightnessForTests;
import app.morphe.extension.facebook.media.PictureInPictureForTests;
import app.morphe.extension.facebook.media.FeedSound;
import app.morphe.extension.facebook.media.FrameStepForTests;
import app.morphe.extension.facebook.media.PinchZoomForTests;
import app.morphe.extension.facebook.media.ProgressBar;
import app.morphe.extension.facebook.media.QualityChoiceForTests;
import app.morphe.extension.facebook.media.SkipSilence;
import app.morphe.extension.facebook.media.ReelSpeedForTests;
import app.morphe.extension.facebook.media.ReplaySpeed;
import app.morphe.extension.facebook.media.RecentlyWatchedForTests;
import app.morphe.extension.facebook.media.ResumePlaybackForTests;
import app.morphe.extension.facebook.media.TapToPlay;
import app.morphe.extension.facebook.media.TapToPlayForTests;
import app.morphe.extension.facebook.media.TapToSeek;
import app.morphe.extension.facebook.menu.MenuPanel;
import app.morphe.extension.facebook.menu.MenuSectionsForTests;
import app.morphe.extension.facebook.misc.MetaUpsells;
import app.morphe.extension.facebook.misc.AnalyticsUploads;
import app.morphe.extension.facebook.misc.AppIcons;
import app.morphe.extension.facebook.misc.CarrierBanners;
import app.morphe.extension.facebook.misc.Haptics;
import app.morphe.extension.facebook.misc.ScreenshotDetection;
import app.morphe.extension.facebook.misc.ScreenTransitionsForTests;
import app.morphe.extension.facebook.misc.Screenshots;
import app.morphe.extension.facebook.misc.ExternalBrowser;
import app.morphe.extension.facebook.misc.LinkCleaner;
import app.morphe.extension.facebook.misc.OwnPostLink;
import app.morphe.extension.facebook.misc.ShareSheetGroups;
import app.morphe.extension.facebook.misc.ShareSheetPeople;
import app.morphe.extension.facebook.navigation.BottomTabBar;
import app.morphe.extension.facebook.navigation.TopBarButtons;
import app.morphe.extension.facebook.navigation.TabBarScrollAway;
import app.morphe.extension.facebook.navigation.TranslucentBarsForTests;
import app.morphe.extension.facebook.navigation.MarketplaceOnlyForTests;
import app.morphe.extension.facebook.navigation.MarketplaceSellerProfileForTests;
import app.morphe.extension.facebook.navigation.HiddenTabsForTests;
import app.morphe.extension.facebook.navigation.ReelsTabForTests;
import app.morphe.extension.facebook.navigation.TabBadgesForTests;
import app.morphe.extension.facebook.navigation.StartTabRouteForTests;
import app.morphe.extension.facebook.notifications.NotificationKindsForTests;
import app.morphe.extension.facebook.profile.ProfileNotesForTests;
import app.morphe.extension.facebook.reels.DoubleTapLike;
import app.morphe.extension.facebook.reels.DoubleTapSkip;
import app.morphe.extension.facebook.reels.OriginalAudio;
import app.morphe.extension.facebook.reels.ReelGesturesForTests;
import app.morphe.extension.facebook.reels.FeedVideoHoldForTests;
import app.morphe.extension.facebook.reels.ReelHold;
import app.morphe.extension.facebook.reels.ReelHoldForTests;
import app.morphe.extension.facebook.reels.FeedVideoAlone;
import app.morphe.extension.facebook.reels.FeedVideoAloneForTests;
import app.morphe.extension.facebook.reels.ReelCleanMode;
import app.morphe.extension.facebook.reels.ReelDeclutter;
import app.morphe.extension.facebook.reels.ReelLoopForTests;
import app.morphe.extension.facebook.reels.NewestReelsForTests;
import app.morphe.extension.facebook.reels.ReelMidCardsForTests;
import app.morphe.extension.facebook.reels.ReelPrompts;
import app.morphe.extension.facebook.reels.SeenStateSendForTests;
import app.morphe.extension.facebook.reels.TapShowsControls;
import app.morphe.extension.facebook.search.MetaAiSearchForTests;
import app.morphe.extension.facebook.search.PreSearchSectionsForTests;
import app.morphe.extension.facebook.search.SearchDateRange;
import app.morphe.extension.facebook.search.SearchDateRangeTest;
import app.morphe.extension.facebook.search.SearchHistory;
import app.morphe.extension.facebook.stories.StoryAdvance;
import app.morphe.extension.facebook.stories.StorySeenForTests;
import app.morphe.extension.facebook.stories.StoryMusic;
import app.morphe.extension.facebook.stories.StoryUpload;
import app.morphe.extension.facebook.stories.SuggestedStoriesForTests;
import app.morphe.extension.facebook.theme.AmoledTheme;
import app.morphe.extension.facebook.theme.DarkMode;
import app.morphe.extension.facebook.theme.FeedGapsForTests;
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
 *
 * <p>Also at sdk 37, Android 17, which reaches Samsung phones from One UI 9.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {30, 37})
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

    /**
     * A photo Story with music's card: its node holds the tree Facebook's card kind asks for
     * (StoryPhoto.MADE_FROM_PHOTO), and its media the video and the photo it was made from.
     */
    public static final class MusicPhotoStoryCard {
        final TreeJNI story = new TreeJNI().with(0xaa18ee7f, new TreeJNI());
        final TreeJNI media = new TreeJNI().with("playable_url", "https://video.xx.fbcdn.net/v/t42/story.mp4")
                .with("stories_photo_uri", new TreeJNI().with("uri", "https://scontent.xx.fbcdn.net/v/t39/story.jpg"));
    }

    /** The same media on a node without that tree, which Facebook plays and labels as a video (#38). */
    public static final class UnmarkedPhotoStoryCard {
        final TreeJNI story = new TreeJNI();
        final TreeJNI media = new MusicPhotoStoryCard().media;
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
    private enum ShareItem { SHARE_NOW, SHARE_TO_HATCH, SHARE_TO_THREADS }

    /** Stands in for Facebook's icon enum, whose constants the reel sidebar walks one button each. */
    private enum SidebarIcon { LIKE, BOOKMARK, APP_WHATSAPP }

    /** Stands in for FDS's colour token enum: the theme goes by the constant's name. */
    private enum Surface { CARD_BACKGROUND }

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
        probes.put(Settings.WATCH_HISTORY_SHORTCUT, WatchHistoryShortcutTest::aStartPublishes);
        probes.put(Settings.FREE_STUFF_SHORTCUT, FreeStuffShortcutTest::aStartPublishes);
        // A start puts the Hushfacebook settings shortcut in the launcher icon's menu.
        probes.put(Settings.SETTINGS_SHORTCUT, SettingsEntryTest::aStartPublishes);
        // A finger on the logo is timed for a long press, and the Menu list gets the Hushfacebook row.
        probes.put(Settings.SETTINGS_LOGO_PRESS, LogoPressTest::aPressIsTimed);
        probes.put(Settings.SETTINGS_MENU_ROW, app.morphe.extension.facebook.menu.MenuSettingsRowTest::aListGetsTheRow);
        // A cold start on a phone with a screen lock covers Facebook and asks for it.
        probes.put(Settings.APP_LOCK, AppLockForTests::aStartLocks);
        // A crash that ends the app writes its file to Downloads.
        probes.put(Settings.SAVE_CRASH_FILE, app.morphe.extension.shared.settings.preference.CrashFileForTests::aCrashWritesAFile);
        // Leaving Facebook ends its processes a few seconds later.
        probes.put(Settings.CLOSE_WHEN_LEFT, app.morphe.extension.facebook.misc.CloseWhenLeftForTests::aLeaveCloses);
        // A start from Facebook's icon puts the countdown cover over its first screen.
        probes.put(Settings.OPENING_PAUSE, app.morphe.extension.facebook.misc.OpeningPauseForTests::aLaunchCovers);
        // An hour of feed browsing puts a break over the feed.
        probes.put(Settings.TAKE_BREAKS, app.morphe.extension.facebook.misc.BreakTimerForTests::aBreakCovers);
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
        // With it, the sharper AV1 picture is converted rather than the H.264 one kept.
        probes.put(Settings.DOWNLOAD_CONVERT, SaveRulesForTests::convertsTheFullSize);
        // A finished save goes without its Saved to toast.
        probes.put(Settings.DOWNLOAD_QUIET, SaveRulesForTests::finishesQuietly);
        return probes;
    }

    /**
     * The switches that keep working while paused. Lock Facebook guards the phone's owner rather
     * than changing Facebook, and every way to pause is in reach of someone holding the phone: the
     * Pause switch, a marker file left over USB, safe mode after a few quick crashes. The settings
     * shortcut, the logo press and the Menu row are ways back to the screen where a pause is lifted (#146). A method, not a field, so
     * the class doesn't load Settings before the rule hands it a context.
     */
    private static Set<BooleanSetting> kept() {
        return new HashSet<>(Arrays.asList(Settings.APP_LOCK, Settings.SETTINGS_SHORTCUT, Settings.SETTINGS_LOGO_PRESS,
                Settings.SETTINGS_MENU_ROW));
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
                // A friend's post in a public group you're not in.
                () -> FeedGuardForTests.hidesGroupPost(Category.ORGANIC, FeedGuardForTests.group(),
                        FeedGuardForTests.JoinState.CAN_JOIN),
                // Home is asked for each group's join state where Facebook wouldn't ask.
                () -> GroupPosts.askForJoinState(false),
                // A row of Stories from people you aren't connected to.
                () -> FeedGuardForTests.hides(Category.ORGANIC, FeedGuardForTests.storiesRow(true)),
                // Your own profile's People you may know carousel builds nothing.
                ProfileSuggestionsForTests::hidesTheCarousel,
                // A post opened from a notification is asked for without its list of more posts.
                () -> !NotificationFooter.show(true),
                // The end of the feed is built without its Find friends card.
                () -> FindFriendsCard.unit("card") == null,
                // The search screen draws its For you section as an empty row.
                PreSearchSectionsForTests::drawsForYouEmpty,
                // The socket under a post's buttons gets a no for the Related posts list.
                () -> RelatedPosts.holds(RelatedPosts.PLUGIN)));
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
                ReturnRefresh::holdTabEntryHotLoad,
                () -> {
                    // A Back on a scrolled Home (#247), past the last one's two seconds so it's a first Back.
                    SystemClock.sleep(3_000);
                    return HomeBack.holdScrollToTop();
                }));
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
        // the kinds of post switches read without any list. Paused, the list reads empty too. The
        // last is a story that shares another, with no words on either.
        // Saved here, with the context, so a probe run without one reads it rather than loading it.
        Settings.HIDDEN_WORDS.save("spoiler");
        Settings.HIDE_POSTS_OVER_REACTIONS.save(app.morphe.extension.facebook.feed.ReactionCeiling.K1);
        probes.put(PatchFamily.POST_WORDS, Arrays.asList(
                () -> FeedGuardForTests.hidesByWords(Category.ORGANIC, new GraphQLStory(),
                        FeedGuardForTests.postText("Big SPOILER inside")),
                () -> FeedGuardForTests.hidesPhotoPost(Category.ORGANIC),
                () -> FeedGuardForTests.hidesPopularPost(Category.ORGANIC),
                () -> FeedGuardForTests.hidesRepost(Category.ORGANIC)));
        // A story with a bumper is answered as one without, so no strip is drawn and no room kept, and a
        // header whose title plugin would add "Follow" is told it has nothing to add. A button's count
        // builder is told to give its zero answer, so the button shows its word.
        probes.put(PatchFamily.POST_PROMPTS, Arrays.asList(
                () -> !PostPrompts.keep(true),
                () -> !PostPrompts.showFollowLink(true),
                PostPrompts::hidePostCounts,
                // The reaction preview is left undrawn only while the patch runs.
                PostPrompts::hideReactionPreview,
                () -> PostPrompts.hideReactionPreview(true),
                // So is the count beside a post's faces.
                () -> PostPrompts.reactionCount("56") == null));
        // A post the store remembers is dropped by the feed guard while the switch is on.
        probes.put(PatchFamily.SEEN_POSTS, Collections.singletonList(SeenPostsForTests::hidesARememberedPost));
        // The pill socket's yes for Meta AI's questions is answered as a no, so it draws no row for
        // them, and its default way of drawing a pill drops one typed meta_ai. The call-to-action
        // selector hears Meta AI's question chips don't apply, and the caption deep dive reads as missing.
        probes.put(PatchFamily.META_AI_QUESTIONS, Arrays.asList(
                () -> !MetaAiQuestions.keep(1, MetaAiQuestions.META_AI_PILL),
                () -> MetaAiQuestions.dropsDefaultPill(MetaAiQuestions.META_AI_TYPE),
                () -> MetaAiQuestions.hidesQuestionButton(MetaAiQuestions.QUESTION_BUTTON),
                MetaAiQuestions::hidesQuestionsBelowCaption));
        // A post header's yes to rotating its subtitle is answered as a no, so it keeps the one line.
        probes.put(PatchFamily.POST_DATES, Collections.singletonList(() -> !PostDates.cycling(true)));
        // A post's cut text is answered with null, so Facebook keeps the whole text with no See more.
        probes.put(PatchFamily.FULL_POST_TEXT, Collections.singletonList(() -> FullPostText.cut(new SpannableString("x")) == null));
        probes.put(PatchFamily.TRANSLATE_POSTS, Collections.singletonList(() -> {
            PostTranslationForTests.noNetwork();
            Spannable post = new SpannableString("Una frase en otro idioma");
            return PostTranslation.shown(PostTranslation.text(post)) != post;
        }));
        // A shared post's menu gets the See who shared row below Facebook's.
        probes.put(PatchFamily.WHO_SHARED, Collections.singletonList(WhoSharedForTests::addsARow));
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
        // A tray of a friend's bucket, a suggested one and one labelled SUGGESTED keeps only the friend's,
        // a Page's bucket leaves it, and so does a bucket you've watched.
        probes.put(PatchFamily.SUGGESTED_STORIES, Arrays.asList(SuggestedStoriesForTests::hidesSuggestions,
                SuggestedStoriesForTests::hidesPageStories, SuggestedStoriesForTests::hidesSeenStories));
        probes.put(PatchFamily.STORY_AUTO_ADVANCE, Arrays.asList(StoryAdvance::waitForTap, StoryAdvance::loop));
        // The story viewer's report of the stories you viewed goes out whole, and with Mark as seen
        // on a marked card goes alone and the button follows the card on screen.
        probes.put(PatchFamily.STORY_SEEN, Arrays.asList(
                StorySeenForTests::holdsABatch,
                StorySeenForTests::sendsOnlyTheMarked,
                StorySeenForTests::pointsTheButton));
        // A Story video's upload asks for high quality.
        probes.put(PatchFamily.PINCH_TO_ZOOM, Collections.singletonList(PinchZoomForTests::zoomsAPhoto));
        probes.put(PatchFamily.STORY_UPLOAD_HD, Collections.singletonList(() -> "high".equals(StoryUpload.quality("standard"))));
        // A tile in the stories row comes out round.
        probes.put(PatchFamily.STORIES_TRAY_CIRCLES,
                Collections.singletonList(app.morphe.extension.facebook.stories.StoriesTrayCirclesForTests::rounds));
        // A photo's song gets the longer clip.
        probes.put(PatchFamily.STORY_MUSIC_LENGTH, Collections.singletonList(() -> StoryMusic.clipLength(15000) == 30000));
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
                ReelsAdFilter::holdProfileReelAds,
                // The feed's Reels row gets no ad tile from its pool.
                ReelsAdFilter::holdRowAd));
        // A search results page leaves its SEARCH_ADS module out.
        probes.put(PatchFamily.SPONSORED_SEARCH, Collections.singletonList(SearchAdFilterForTests::dropsAnAd));
        // A timeline story with sponsored data isn't drawn on a profile.
        probes.put(PatchFamily.SPONSORED_PROFILE_POSTS,
                Collections.singletonList(ProfileAdFilterForTests::hidesASponsoredStory));
        // The note bubble over a profile picture is left out of its Bloks children.
        probes.put(PatchFamily.PROFILE_NOTES, Collections.singletonList(ProfileNotesForTests::hidesANote));
        // Your own profile's count line shows the number you typed.
        probes.put(PatchFamily.LOCAL_PROFILE_COUNTS,
                Collections.singletonList(app.morphe.extension.facebook.profile.LocalProfileCountsForTests::swapsAFriendsCount));
        // A post's row gets Save and loses Like, and a video post's row gets Download.
        probes.put(PatchFamily.POST_ACTION_BUTTONS, Arrays.asList(
                PostActionButtonsForTests::addsSave,
                PostActionButtonsForTests::hidesLike,
                PostActionButtonsForTests::addsDownload));
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
                // The audio line goes under its own switch.
                () -> ReelDeclutter.filterChips(Arrays.asList(new TypedFeedUnit("XFBFBShortsSoundtrackTitle"))) != null,
                // So does the Reels tab's own audio line row.
                ReelDeclutter::hideAudioLine,
                // And "Made with Edits" under the caption.
                ReelDeclutter::hideEditsLabel,
                // A stalled video's slow connection message.
                ReelDeclutter::hideSlowConnectionMessage,
                ReelDeclutter::hideFollowButton,
                ReelDeclutter::hideFollowingButton,
                ReelDeclutter::skipHotComment,
                ReelDeclutter::skipSocialBubbles,
                ReelMidCardsForTests::dropsAThreadsCard,
                () -> ReelCleanMode.startClean(false),
                // Its button takes the sticky way, and a reel taken out keeps it (#304).
                () -> ReelCleanMode.stickyClean(false),
                () -> ReelCleanMode.cleanButtonState(ReelCleanMode.markLeftClean(new Object[] {Boolean.FALSE})[0]) == Boolean.TRUE,
                ReelLoopForTests::stopsAReel,
                // A video whose audio Facebook would pick in Spanish is asked for no language.
                () -> !"es".equals(OriginalAudio.language("es")),
                // And a video's dubbed tracks are dropped as its manifest is read (#148).
                () -> !OriginalAudio.dubbing(true),
                () -> OriginalAudio.filtering(0) != 0,
                // The search pill is answered as empty only while the patch runs.
                ReelDeclutter::hideSearchPill,
                // So is the prompt over a reel's comments.
                () -> ReelDeclutter.commentSearchPrompt(new Object()) == null,
                // And the search bar over a reel opened from a profile.
                () -> ReelDeclutter.searchBarPart(new Object()) == null,
                // A sidebar button's count loses its number only while the patch runs.
                () -> !"12".equals(ReelDeclutter.countText("12")),
                // The sidebar's Save button is skipped only while the patch runs.
                () -> ReelDeclutter.hideSidebarButton(SidebarIcon.BOOKMARK),
                // The direct WhatsApp button has its own switch in the same type walk.
                () -> ReelDeclutter.hideSidebarButton(SidebarIcon.APP_WHATSAPP),
                // A reel with comments off gets its Comment button only while the patch runs.
                () -> ReelDeclutter.showsCommentButton(false),
                // A video author's Subscribe pill stays out of the viewer's row and a shared row's slot (#291).
                () -> !ReelDeclutter.showAuthorPill(true, true),
                () -> {
                    ReelDeclutter.noteAuthorPill(true);
                    return ReelDeclutter.authorPillSlot(new Object()) == null;
                },
                // So is the speaker button under it.
                () -> !ReelDeclutter.showsSoundButton(true),
                // And the footer's speaker, when the footer builds it.
                () -> ReelDeclutter.soundButton(new Object()) == null,
                // Move the speaker above the three dots hands it from the corner to the side column.
                () -> {
                    Object reel = new Object();
                    Object corner = new Object();
                    ReelDeclutter.sideColumnComing(reel);
                    ReelDeclutter.rememberCorner(reel, corner);
                    return ReelDeclutter.speakerStays(new Object(), reel) == null && corner != null;
                },
                () -> ReelDeclutter.speakerLayout(false),
                // A tile of the feed's Reels row skips its clip and plays to the end.
                () -> !ReelDeclutter.allowsPreviewClip(true),
                () -> ReelDeclutter.previewClipEnd(1000) != 1000,
                // A feed video's player asking for more right after the tap gets none.
                () -> {
                    FeedVideoAlone.feedVideoTapped();
                    return FeedVideoAlone.holdsBack(new Object());
                },
                // So does a reel's data source as the reels viewer opens from the feed. A feed reel's
                // origin: an ordinary video post's ("newsfeed::feed_story") keeps Facebook's answer (#237).
                () -> {
                    FeedVideoAlone.reelsViewerOpening("newsfeed::fb_shorts_native_in_feed_unit");
                    return FeedVideoAlone.seedOnly(new Object());
                },
                // And that viewer's list shows no loading page after the video.
                FeedVideoAloneForTests::dropsTheLoadingPage));
        // A reel that would get the interest prompt is answered as one that doesn't.
        probes.put(PatchFamily.REEL_PROMPTS, Collections.singletonList(() -> !ReelPrompts.keep(true)));
        // The Reels batcher's send of the reels you watched never reaches its executor.
        probes.put(PatchFamily.REEL_WATCH_HISTORY, Collections.singletonList(SeenStateSendForTests::heldBack));
        // A Reels rerank that put an older reel first hands the newer one the first slot.
        probes.put(PatchFamily.NEWEST_REELS_FIRST, Collections.singletonList(NewestReelsForTests::putsTheNewestFirst));
        // A double tap on a reel finds no handler and no heart, the reel like helper finds no key and
        // sends no like from a double tap, a feed attachment leaves its double tap unhandled, and a
        // photo on Home reports no double tap and sends no like.
        probes.put(PatchFamily.DOUBLE_TAP_LIKE, Arrays.asList(
                () -> DoubleTapLike.handler(new Object()) == null,
                () -> DoubleTapLike.heart(new Object()) == null,
                () -> DoubleTapLike.likeKey("reel") == null,
                () -> DoubleTapLike.holdBackLike("DOUBLE_TAP"),
                DoubleTapLike::holdBackTap,
                () -> DoubleTapLike.holdBackFeedTap(null),
                DoubleTapLike::holdBackFeedLike));
        // Facebook's double-tap skip test, read as off, is answered on.
        probes.put(PatchFamily.DOUBLE_TAP_SKIP, Collections.singletonList(() -> DoubleTapSkip.flag(false)));
        // A reel tap gets the controls in place of the pause, they stay up longer, and the unified player hides them.
        probes.put(PatchFamily.TAP_SHOWS_CONTROLS, Arrays.asList(
                () -> TapShowsControls.showControls(false),
                () -> TapShowsControls.hideDelay(1000L) != 1000L,
                () -> !TapShowsControls.skipHide(true)));
        // A double tap on a reel opens its comments in place of a like, a long press does too, and neither
        // a heart plays nor a like from a double tap is sent while the first switch is on.
        probes.put(PatchFamily.REEL_GESTURES, Arrays.asList(
                ReelGesturesForTests::doubleTapOpens,
                ReelGesturesForTests::longPressOpens,
                () -> DoubleTapLike.heart(new Object()) == null,
                () -> DoubleTapLike.holdBackLike("DOUBLE_TAP")));
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
                ReelHoldForTests::putsBackTheSpeedBeforeAHold,
                // With Hold a feed video for 2x on, a finger down starts timing a hold.
                FeedVideoHoldForTests::startsTiming,
                // With Hide the pull down to lock hint on, the 2x pill's hint is drawn as the plain pill.
                ReelHoldForTests::hidesTheLockHint,
                // With Hide the 2x popups on, a 2x pill draws nothing.
                ReelHoldForTests::hidesThePill));
        // A speed picked on a reel is set on the next reel the viewer starts, and one picked on a
        // feed video on the next feed video. A full-screen video that isn't live, and isn't an ad,
        // gets Playback speed in its gear menu (#238).
        probes.put(PatchFamily.KEEP_REEL_SPEED, Arrays.asList(ReelSpeedForTests::keepsAPickedSpeed,
                ReelSpeedForTests::keepsAPickedVideoSpeed, ReelSpeedForTests::offersSlowerSpeeds,
                ReelSpeedForTests::gearOffersSlowerSpeeds, () -> ReplaySpeed.offerSpeed(0)));
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
                () -> {
                    // Inline feed video controls keeps the chosen setting, its button needs a player (#239).
                    Settings.INLINE_FEED_VIDEO_CONTROLS.save(false);
                    try {
                        return TapToPlay.autoplaySetting(TapToPlayForTests.Autoplay.ON) != TapToPlayForTests.Autoplay.ON;
                    } finally {
                        Settings.INLINE_FEED_VIDEO_CONTROLS.save(true);
                    }
                },
                () -> TapToPlay.showReelPlayButton(false)));
        // A long video left at 5:00 is saved, and its next start seeks back there.
        // A long video picks up where it was left, and a reel played on and a story viewed go on the
        // Recently watched list (#183).
        probes.put(PatchFamily.RESUME_LONG_VIDEOS, Arrays.asList(ResumePlaybackForTests::resumesALongVideo,
                RecentlyWatchedForTests::keepsAWatchedReel, StorySeenForTests::keepsAViewedStory));
        // A tap on the right third of a video you paused steps it a frame.
        probes.put(PatchFamily.FRAME_STEP, Collections.singletonList(FrameStepForTests::stepsAPausedVideo));
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
                // A photo Story with music gets Save photo beside Save.
                () -> StoryPhoto.menuTitle(new MusicPhotoStoryCard()) != null,
                // Save photo on more stories: so does one Facebook doesn't mark as made from a photo.
                () -> StoryPhoto.menuTitle(new UnmarkedPhotoStoryCard()) != null,
                // The recorder runs in every player Facebook builds, not only in stories.
                PlayerSourcesForTests::recordsAPlayer));
        // Every reel's sidebar gets the Download button, a reel on Facebook's older button layout gets
        // the newer one so it has the button too, and a reel whose caption was seen gets Copy caption.
        probes.put(PatchFamily.REEL_DOWNLOAD, Arrays.asList(ReelDownload::showsButton,
                () -> ReelDownload.verticalUfi(false), ReelCaptionForTests::addsTheRow));
        // A video post's menu gets Download to phone, a video comment's sheet gets Save video, and
        // the video recorder keeps a player. Its copied-link option reads the clipboard as a screen
        // comes to the front.
        probes.put(PatchFamily.VIDEO_DOWNLOAD, Arrays.asList(
                VideoMenuItemForTests::addsAnItem,
                VideoMenuItemForTests::addsTheOpenItem,
                CommentVideoForTests::addsARow,
                PlayerSourcesForTests::recordsAVideoPlayer,
                ClipboardLinkForTests::readsTheClipboard));
        // Every photo the viewer opens offers Save photo, a photo post's menu gets Save photo, and a
        // GIF comment's sheet gets Save GIF.
        probes.put(PatchFamily.PHOTO_DOWNLOAD, Arrays.asList(
                () -> PhotoSave.offersSave(false),
                PhotoMenuItemForTests::addsAnItem,
                CommentGifForTests::addsARow));
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
        // With the bar at the bottom, Facebook's check of whether it slides away answers yes. The top
        // bar's entry is told to move 0 and fade by 0. (A kept bottom bar has its own test: with every
        // switch on, the slide wins over it.) Facebook's big screen layout gate is answered no. A frame
        // of the main screen makes the top bar's background see-through.
        probes.put(PatchFamily.BOTTOM_TAB_BAR, Arrays.asList(
                () -> BottomTabBar.override(TriState.NO.ordinal()) == TriState.YES.ordinal(),
                BottomTabBar::phoneLayout,
                TabBarScrollAway::slidesAway,
                () -> TabBarScrollAway.topBarOffset("app_header_container", -120) == 0,
                () -> TabBarScrollAway.topBarFade("app_header_container", 1f) == 0f,
                TranslucentBarsForTests::makesABarSeeThrough,
                () -> {
                    View button = new View(RuntimeEnvironment.getApplication());
                    TopBarButtons.messengerButton(button);
                    return button.getVisibility() == View.GONE;
                },
                () -> TopBarButtons.messengerCount(3) == 0,
                () -> {
                    View logo = new View(RuntimeEnvironment.getApplication());
                    TopBarButtons.facebookLogo(logo);
                    return logo.getAlpha() == 0f;
                }));
        // Facebook's dark mode controller answers dark where it answered light.
        probes.put(PatchFamily.FORCE_DARK_MODE, Collections.singletonList(ForceDarkModeForTests::forcesDark));
        // Black cards: a card's dark grey takes the page's black, where the theme alone keeps it near black.
        // Lines between posts: a feed row paints the gap under its content.
        probes.put(PatchFamily.AMOLED_THEME, Arrays.asList(
                () -> {
                    DarkMode.answer(true);
                    return AmoledTheme.apply(0xFF333334, Surface.CARD_BACKGROUND) == 0xFF000000;
                },
                () -> {
                    DarkMode.answer(true);
                    return FeedGapsForTests.drawsTheGap();
                }));
        // A request for a post's comments that names no order asks for the chosen one, and with
        // All comments chosen, the menu its answer draws is picked in.
        probes.put(PatchFamily.DEFAULT_COMMENT_ORDER, Arrays.asList(
                DefaultCommentOrderForTests::asksForTheChosenOrder,
                DefaultCommentOrderForTests::picksInTheMenu));
        // A comment sheet's summary and the one under a post get a no from their sockets' checks.
        probes.put(PatchFamily.META_AI_SUMMARIES, Arrays.asList(
                () -> MetaAiSummaries.holds(MetaAiSummaries.SHEET_SUMMARY),
                () -> MetaAiSummaries.holds(MetaAiSummaries.POST_SUMMARY)));
        // The comment box's check answers no for its GIF and sticker buttons, a long press on Like
        // returns before the reaction picker opens, a comment starts with its replies open, an open
        // thread's replies page on as they're scrolled, a View more replies row takes a tap anywhere
        // on it, and the
        // check under a post's comments answers no for Related groups. A comment linking to a shop,
        // and one holding a phrase from the hide list, are left out of the comment section.
        probes.put(PatchFamily.COMMENT_SHEET_OPTIONS, Arrays.asList(
                () -> CommentSheetOptions.holdsButton(CommentSheetOptions.GIF_BUTTON),
                () -> CommentSheetOptions.holdsButton(CommentSheetOptions.STICKER_BUTTON),
                CommentSheetOptions::skipReactionPicker,
                () -> CommentSheetOptions.openReplyThreads(false),
                () -> CommentSheetOptions.loadWholeReplyThreads(false),
                () -> CommentSheetOptions.drawsHideRepliesRow(true, false, null, null),
                () -> !CommentSheetOptions.viewMoreRepliesTextOnly(true),
                () -> CommentSheetOptions.holdsBottomContent(CommentSheetOptions.RELATED_GROUPS),
                () -> CommentSheetOptions.holdsPostComments(CommentSheetOptions.INLINE_COMMENTS),
                () -> {
                    // Block button on comments keeps the dislike it redraws (#271).
                    Settings.COMMENT_BLOCK_BUTTON.save(false);
                    try {
                        return CommentSheetOptions.holdsCommentDislike();
                    } finally {
                        Settings.COMMENT_BLOCK_BUTTON.save(true);
                    }
                },
                CommentBlock::blockIcon,
                () -> CommentSheetOptions.hidesComment(new GraphQLComment("https://s.shopee.vn/9zKs")),
                () -> CommentSheetOptions.hidesComment(new GraphQLComment("Big SPOILER inside")),
                () -> CommentSheetOptions.incomingTypers(3) == 0,
                () -> CommentSheetOptions.newCommentsPill("3 new comments").isEmpty()));
        // A reel's comment sheet asking for two thirds of the screen gets all of it, a split-screen
        // launcher answers that it didn't open, a full-height sheet fills the screen, the reel
        // drawn above the comments is hidden, and a part-height sheet over the Reels tab lets a tap
        // above it through to the reel.
        probes.put(PatchFamily.FULL_HEIGHT_COMMENTS, Arrays.asList(
                () -> FullHeightComments.sheetHeight(0.66f) == 1f,
                FullHeightComments::skipSplitScreen,
                () -> FullHeightComments.fillsScreen(1f),
                PausedHooksTest::hidesTheVideoAboveTheComments,
                PausedHooksTest::letsATapThroughToTheReel));
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
                ChatListForTests::hidesAPromotion,
                // The search row at the top of Chats loses its Meta AI button, and so does the search screen.
                () -> ChatList.metaAiButton(new Object()) == null,
                () -> !ChatList.showsSearchMetaAiButton(true)));
        // With Messenger installed, a tap on the top bar's Messenger icon opens Messenger instead of
        // Chats, and an incoming call's push, its ring and a new message, handed to the notifiers or
        // posted as a push, are left to Messenger.
        probes.put(PatchFamily.MESSENGER_ICON, Arrays.asList(
                MessengerIconForTests::opensMessenger,
                MessengerHandoffForTests::dropsACallPush,
                MessengerHandoffForTests::dropsARing,
                MessengerHandoffForTests::dropsAMessage,
                MessengerHandoffForTests::dropsAChatPush));
        // The Menu's Upgrades, Also from Meta and Help and support groups build nothing, in the
        // section Facebook draws and in the one carrying what the server sends, Settings and privacy
        // builds open, and the side panel and the Menu page get Facebook's own Menu instead of the
        // Bloks one.
        probes.put(PatchFamily.MENU_PROMOTIONS, Arrays.asList(
                MenuSectionsForTests::hidesUpgrades,
                MenuSectionsForTests::hidesAlsoFromMeta,
                MenuSectionsForTests::hidesServerUpgrades,
                MenuSectionsForTests::hidesServerAlsoFromMeta,
                MenuSectionsForTests::hidesHelp,
                MenuSectionsForTests::keepsSettingsOpen,
                () -> !MenuPanel.bloksPanel(true),
                () -> !MenuPanel.bloksMenu(true)));
        // Edits' header flag and both shapes of its pill request, the Threads cross-posting
        // onboarding and the share sheet's Threads and Muse items, the Meta Verified sheet and label, an avatar
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
                () -> !ShareSheetGroups.offerNewGroup(true),
                () -> !ShareSheetPeople.addRow(new java.util.ArrayList<>(), new Object()),
                () -> {
                    java.util.ArrayList<Object> children = new java.util.ArrayList<>();
                    ShareSheetPeople.addRowThen(new Object(), null, children);
                    return children.isEmpty();
                }));
        // Search leaves out its Meta AI answer and its prompt modules, and a suggestion set to open
        // Meta AI opens the results.
        probes.put(PatchFamily.META_AI_SEARCH, Arrays.asList(
                MetaAiSearchForTests::hidesAnswer,
                MetaAiSearchForTests::dropsPrompts,
                MetaAiSearchForTests::stopsSuggestionRoute));
        // A search Facebook would add to your history is kept out, and off the Recent list.
        probes.put(PatchFamily.SEARCH_HISTORY, Arrays.asList(() -> SearchHistory.skipSave(false), () -> {
            Map<Object, Object> recent = new LinkedHashMap<>();
            SearchHistory.keepRecent(recent, "filter", "list");
            return recent.isEmpty();
        }));
        // The Posted date filter keeps its list of years.
        probes.put(PatchFamily.SEARCH_DATE_RANGE, Arrays.asList(
                () -> SearchDateRange.kind(new SearchDateRangeTest.Filter("rp_creation_time"),
                        SearchDateRangeTest.Kind.MULTI_CHOICE) == SearchDateRangeTest.Kind.DATE_RANGE_PICKER,
                () -> {
                    SearchDateRange.kind(new SearchDateRangeTest.Filter("rp_creation_time"),
                            SearchDateRangeTest.Kind.MULTI_CHOICE);
                    return !SearchDateRange.bottomSheet(true);
                },
                () -> SearchDateRange.callback(new SearchDateRangeTest.Filter("rp_creation_time"),
                        SearchDateRangeTest.Kind.MULTI_CHOICE, "callback") == null));
        // XAnalytics' upload and uploader resume are skipped, and the Papaya job finishes unrun.
        probes.put(PatchFamily.ANALYTICS_UPLOADS, Arrays.asList(
                AnalyticsUploads::holdXAnalyticsUpload,
                () -> !AnalyticsUploads.papayaOn(true)));
        // A haptic Facebook asks for doesn't play.
        // Facebook Flex's Data mode and Free mode banners are hidden.
        probes.put(PatchFamily.CARRIER_BANNERS, Arrays.asList(
                () -> CarrierBanners.hides("autoflex_paid_mode_banner"),
                () -> CarrierBanners.hides("autoflex_banner")));
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
                PictureInPictureForTests::homeFlagAllows, PictureInPictureForTests::offersTheMenuRow,
                PictureInPictureForTests::offersTheButton));
        // An HDR window comes out in the default colour mode, a headroom as none, the screen
        // answers as one that shows no HDR, a manifest loses the HDR twin of an SDR rendition, and
        // a player goes from its SurfaceView to its TextureView.
        probes.put(PatchFamily.HDR_BRIGHTNESS, Arrays.asList(
                HdrBrightnessForTests::keepsAnHdrWindowInTheUsualRange, HdrBrightnessForTests::holdsTheHeadroom,
                HdrBrightnessForTests::answersTheScreenAsShowingNoHdr, HdrBrightnessForTests::dropsTheHdrRendition,
                HdrBrightnessForTests::drawsVideosWithTheScreen));
        // A sink's sound starts with its silent parts skipped.
        probes.put(PatchFamily.SKIP_SILENCE, Collections.singletonList(() -> SkipSilence.skipSilence(false)));
        // A new session starts muted, and a sound-on that isn't the user's keeps the feed muted.
        probes.put(PatchFamily.KEEP_FEED_VIDEOS_MUTED, Arrays.asList(() -> !FeedSound.sessionSound(true),
                () -> !FeedSound.soundChange(null, true, false)));
        // A reel's bar is kept full size, a full-screen video sets no fade timer, a press off the
        // bar's thumb is held as a tap to seek, the next reel's bar starts over, and a bar still in
        // Facebook's thin look takes the kept look.
        probes.put(PatchFamily.PROGRESS_BAR, Arrays.asList(ProgressBar::keepsReelBar, ProgressBar::keepsControls,
                ProgressBar::liftsFooter, ProgressBar::noFadedBar, PausedHooksTest::holdsATapOnTheBar,
                PausedHooksTest::startsTheNextReelOver, PausedHooksTest::givesAThinBarTheKeptLook));
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

    /** The scrubber takes the next reel with its bar where the last reel stopped. */
    private static boolean startsTheNextReelOver() {
        android.widget.SeekBar bar = new android.widget.SeekBar(RuntimeEnvironment.getApplication());
        bar.setProgress(47);
        ProgressBar.nextReel(null, bar);
        return bar.getProgress() == 0;
    }

    /** A scrubber still in Facebook's thin look, which an update would give the kept look. */
    private static boolean givesAThinBarTheKeptLook() {
        return ProgressBar.looksAgain(new android.widget.LinearLayout(RuntimeEnvironment.getApplication()));
    }

    /** A comment sheet whose header holds a video, the reel above its comments, set up by Facebook. */
    private static boolean hidesTheVideoAboveTheComments() {
        android.content.Context app = RuntimeEnvironment.getApplication();
        FrameLayout header = new FrameLayout(app);
        header.setId(0x7f0a0612);
        header.addView(new android.view.TextureView(app));
        FrameLayout sheet = new FrameLayout(app);
        sheet.addView(header);
        FullHeightComments.watchHeader(sheet, 0x7f0a0612, true);
        return header.getVisibility() == View.GONE;
    }

    /** A part-height comment sheet opened from the Reels tab, surface 20, handed over by Facebook. */
    private static boolean letsATapThroughToTheReel() {
        return FullHeightComments.tapThrough(
                new android.app.Dialog(Robolectric.buildActivity(Activity.class).setup().get()), 20);
    }

    /** A press at the far end of a bar whose thumb sits at its start, then a cancel to end it. */
    private static boolean holdsATapOnTheBar() {
        android.widget.SeekBar bar = new android.widget.SeekBar(RuntimeEnvironment.getApplication());
        android.graphics.drawable.ShapeDrawable thumb = new android.graphics.drawable.ShapeDrawable();
        thumb.setIntrinsicWidth(40);
        thumb.setIntrinsicHeight(40);
        bar.setThumb(thumb);
        bar.setMax(1000);
        bar.measure(android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(60, android.view.View.MeasureSpec.EXACTLY));
        bar.layout(0, 0, 1000, 60);
        android.view.MotionEvent down = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 900, 30, 0);
        android.view.MotionEvent cancel = android.view.MotionEvent.obtain(0, 10, android.view.MotionEvent.ACTION_CANCEL, 900, 30, 0);
        try {
            boolean held = TapToSeek.takesTouch(bar, down);
            TapToSeek.takesTouch(bar, cancel);
            return held;
        } finally {
            down.recycle();
            cancel.recycle();
        }
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
