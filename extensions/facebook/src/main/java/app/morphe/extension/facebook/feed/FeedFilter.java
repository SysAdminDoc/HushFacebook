/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * The rules follow the feed guards of https://github.com/andrewliang25/morphe-patches (GPL-3.0,
 * Andrew Liang: the SPONSORED category and the suggested unit list) and of
 * https://github.com/SapitoSucio/FroggoMorphePatches (GPL-3.0: the PROMOTION category, and the
 * AI filter the GenAI rule follows).
 */
package app.morphe.extension.facebook.feed;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;

import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the news feed guard asks about each edge before Facebook adds it to the feed.
 *
 * <p>The guard runs at the start of {@code FeedUnitCollectionManager.addNewEdgeToCollection},
 * the one funnel every news feed edge is added through. Answering true there makes the method
 * return false, which Facebook already handles: it logs "Edge not added to FUC" and carries on,
 * so a hidden post leaves no gap and logs no impression. An edge swapped in over another one
 * never passes the funnel, so the swap asks the same rules through {@link #hideSwappedEdge}.
 *
 * <p>Every feed filter shares this one guard. Two patches each prepending their own guard to the
 * same method is how FroggoMorphePatches ended up with branch targets that pointed into the wrong
 * code, so the patches here only switch their rule on and the rules live in Java.
 */
@SuppressWarnings("unused")
public final class FeedFilter {
    private static final String SPONSORED = "SPONSORED";
    private static final String PROMOTION = "PROMOTION";

    /**
     * The category of Facebook's own engagement cards, its quick promotions. The suggested groups
     * row comes as one now ("Suggested for you", Join, "Discover more groups"), no longer as a
     * GroupsYouShouldJoinFeedUnit. The promos switch hides every card; the groups switch hides the
     * one {@link #GROUPS_PROMOTION_ID} names.
     */
    static final String ENGAGEMENT_PROMO = "ENGAGEMENT_QP";

    /**
     * The quick promotion Facebook serves the suggested groups row as: a CustomizedStory drawn by a
     * Bloks hscroll template. The category's other cards are other promotions, People you may know
     * and a Meta AI discover unit (emulator, 580, 2026-09-30), and every card's {@code tracking}
     * JSON names its own. The id is Facebook's server data, not the app's: if the row moves to a new
     * promotion, the groups switch alone keeps it until this names the new id, and the promos switch
     * still hides it. The feed-edge log names each card's id.
     */
    static final String GROUPS_PROMOTION_ID = "625620278343662";
    private static final int TRACKING_KEY = "tracking".hashCode();
    private static final String PROMOTION_ID_FIELD = "\"quick_promotion_id\":\"";

    /**
     * The GraphQL type the "People you may know" row answers {@code getTypeName()} with. Its class
     * is renamed on every release and shared with GroupsYouShouldJoinFeedUnit and
     * FriendRequestsFeedUnit, but {@code getTypeName()} answers by the model's type tag, and each
     * type name is a literal in it, in 577 and 580. So the name picks this row and leaves the others.
     */
    static final String PEOPLE_YOU_MAY_KNOW_TYPE = "PaginatedPeopleYouMayKnowFeedUnit";

    /**
     * The GraphQL type the suggested groups row answers: groups to join, under a "Suggested for
     * you" header, with a "Discover more groups" button. Its model is the People you may know one,
     * whose {@code getTypeName()} answers this name for its own type tag in 577 and 580. A post
     * from a group you're in is a Story, and friend requests answer FriendRequestsFeedUnit, so
     * neither matches.
     */
    static final String GROUPS_YOU_SHOULD_JOIN_TYPE = "GroupsYouShouldJoinFeedUnit";

    /**
     * The GraphQL type names of Memories between posts: "On this day" and its section header, a
     * memory shown as its own post, and friendship anniversaries. Each is only a name in Facebook's
     * type tables on 577, 580 and 581, with no model class of its own, so a unit of one answers
     * {@code getTypeName()} from the shared model it's built on.
     */
    static final String[] MEMORIES_TYPES = {
            "ThrowbackPromotionFeedUnit", "ThrowbackSectionHeaderFeedUnit", "ThrowbackPermalinkStoryFeedUnit",
            "GoodwillThrowbackFeedUnit",
    };

    /** The row of friend requests between posts, on the model People you may know shares. */
    static final String FRIEND_REQUESTS_TYPE = "FriendRequestsFeedUnit";

    /** The card of where your friends are, between posts. */
    static final String FRIENDS_LOCATIONS_TYPE = "FriendsLocationsFeedUnit";

    /**
     * Facebook's own promotions and prompts between posts that have no model class on every build,
     * found by the GraphQL type name instead of by class like {@link #SUGGESTED_UNITS}: two more
     * kinds of Quick Promotion (the Vibes one has a class on 580 and 581 only), the social list
     * prompt, people to invite to a group, and a row of suggested shows. They go with the suggested
     * posts switch. The shows row is only a name in the type tables of 577, 580 and 581, and no feed
     * has served one yet, so it's an extra until a diagnostic report shows one.
     */
    static final String[] SUGGESTED_TYPES = {
            "ClientTriggeredQPFeedUnit", "VibesRifuQuickPromotionFeedUnit", "SocialListPromptFeedUnit",
            "PaginatedGroupsPeopleYouMayInviteFeedUnit", "SuggestedShowsFeedUnit",
    };

    /**
     * Story categories Facebook can file a post it picked for you under: one it adds to the feed
     * from outside what you follow, and a trending one. They go with the "Suggested for you" switch,
     * whatever the post's recommendation flag reads. Neither has reached the feed guard on the
     * accounts this was tested with, where recommended posts came as ENGAGEMENT, so they're extras
     * until a diagnostic report shows one. Every build since 577 builds both constants, and no app
     * code names either (577 keeps a field for each that nothing reads, 580 and 581 keep none), so
     * an edge under one only comes from Facebook's servers. The feed edge log names each category.
     */
    static final String[] SUGGESTED_CATEGORIES = {"INJECTED_STORY", "TRENDING"};

    /**
     * A carousel of several ads in one unit. Facebook draws it from the same ad pool as every other
     * ad (581's AdValidator.checkValidity vets it), so it goes with the sponsored posts switch by its
     * type name, whatever category the edge carries.
     */
    static final String MULTI_ADS_TYPE = "FBMultiAdsFeedUnit";

    /**
     * The GraphQL type of the feed's rows of Stories between posts, a literal of the shared showcase
     * model's {@code getTypeName()} in 577 and 580. Facebook draws one with its DiscoverUnitComponent,
     * which reads {@link #UNCONNECTED_STORIES_FLAG} to tell a row of Stories from people you aren't
     * connected to, "Stories you might like", from a row of your friends' Stories.
     */
    static final String DISCOVER_UNIT_TYPE = "DiscoverFeedUnit";
    static final String UNCONNECTED_STORIES_FLAG = "is_unconnected_mbsu";
    private static final int UNCONNECTED_STORIES_KEY = UNCONNECTED_STORIES_FLAG.hashCode();
    /** What a read of that flag found, as the report counts it. Only the first hides anything. */
    static final String UNCONNECTED = "unconnected";
    static final String CONNECTED = "connected";

    /**
     * The GraphQL type the same model answers for the Stories tray, which the feed adds as an
     * adapter of its own. A unit answering it as an edge would be the tray between posts.
     */
    static final String STORIES_TRAY_UNIT_TYPE = "StoriesTrayFeedUnit";

    /**
     * The type name of the Meta AI card Facebook adds to the feed between posts. Facebook's own feed
     * unit dispatcher compares a unit's type name with it in 581. Hide AI-detected posts takes it out
     * under {@link Settings#HIDE_META_AI_FEED_UNITS}.
     */
    static final String META_AI_UNIT_TYPE = "XFBFBImplicitMetaAIFeedUnit";

    /**
     * The type name of the card promoting Vibes, Meta AI's app of AI-made videos, that Facebook can
     * put between posts. Its model, GraphQLVibesRifuQuickPromotionFeedUnit, answers it from
     * getTypeName() in 580 and 581; 577 has no such unit. It goes under the same switch as the Meta
     * AI card.
     */
    static final String VIBES_PROMOTION_UNIT_TYPE = "VibesRifuQuickPromotionFeedUnit";

    /**
     * The other two kinds of Stories between posts the same model answers in 577 and 580, through
     * its table of type names rather than a literal: one large Stories tile, and one person's
     * Stories in a viewer of their own.
     */
    static final String STORIES_LARGE_TILE_UNIT_TYPE = "StoriesOneColumnOneRowLargeTileFeedUnit";
    static final String STORIES_INLINE_VIEWER_UNIT_TYPE = "StoriesSingleBucketInlineViewerFeedUnit";
    /** What Hide the Stories tray's rule adds to the type of a row of Stories it took out of the feed. */
    static final String STORIES_TRAY_REASON = "stories tray";

    /** Whether Hide Stories tray is in this build, when a test says so instead of {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean storiesTrayInBuildForTests;

    /** The diagnostic counter routes. Each news feed edge counts as a list of one post. */
    static final String FEED_ROUTE = "News feed posts";
    static final String STORY_ROUTE = "Story ad sources";
    /**
     * The edges the GenAI rule read, counted only while its switch is on. Its kinds say what each
     * read found, so a report shows why the posts it kept were kept.
     */
    static final String AI_ROUTE = "GenAI flag";
    /**
     * The stories the creator's AI label rule read, counted only while its switch is on, with what
     * each read of the self-disclosed flag found as the kind.
     */
    static final String AI_LABEL_ROUTE = "Creator AI label flag";
    /**
     * The stories the "Suggested for you" rule read, counted only while its switch is on, with what
     * each read of Facebook's recommendation flag found as the kind.
     */
    static final String RECOMMENDATION_ROUTE = "Recommendation flag";
    /**
     * The rows of Stories between posts the "Stories you might like" rule read, counted only while
     * its switch is on, with what each read of the row's flag found as the kind.
     */
    static final String STORIES_YOU_MIGHT_LIKE_ROUTE = "Stories you might like";
    /**
     * The stories the word filter read, counted only while its switch is on, with which list
     * matched or why nothing was read as the kind. Never a word or a phrase.
     */
    static final String WORDS_ROUTE = "Your words";
    /** What a post the word filter hid counts under on the feed's route. */
    static final String WORDS_REASON = "word filter";
    /** The kind a post counts under while the hide list is empty, when nothing of it is read. */
    static final String NO_WORDS = "no words listed";
    /**
     * The stories the people, Pages and sites rule read, counted only while its switch is on, with
     * whether a rule matched or why nothing was read as the kind. Never a name, an id or a link.
     */
    static final String SOURCES_ROUTE = "Your people, Pages and sites";
    /** What a post that rule hid counts under, followed by the number of the line that matched. */
    static final String SOURCES_REASON = "source rule";
    /** The kind a post counts under while the list is empty, when nothing of it is read. */
    static final String NO_SOURCES = "nobody listed";
    /** The kind a post counts under when it was read and no line matched. */
    static final String NO_SOURCE_MATCH = "no line matched";
    /**
     * The stories Hide posts from Pages read, counted only while its switch is on, with what kind of
     * author wrote each as the kind: {@value PostSources#PAGE} when every author is a Page,
     * {@value #PAGE_IN_A_GROUP} for a Page's post in a group, or the first other author's type, or
     * why nothing was read. Never a name or an id.
     */
    static final String PAGES_ROUTE = "Posts from Pages";
    /** What Hide posts from Pages counts a Page's post in a group under. It stays. */
    static final String PAGE_IN_A_GROUP = "Page in a group";
    /**
     * The stories Only friends' posts read, counted only while its switch is on, with what
     * {@link PostSources#friendKind} found as the kind: a friend's friendship status, why a post isn't
     * a friend's, or why nothing was read. Never a name or an id.
     */
    static final String FRIENDS_ROUTE = "Only friends' posts";
    /**
     * The Stories tray adapters the feed asked for, each call counted with its adapter as the kind,
     * and a skipped one as a removal. The tray is never a feed edge: the feed's adapter list adds it
     * as an adapter of its own, so it never reaches the edge guard.
     */
    static final String TRAY_ROUTE = "Stories tray adapters";

    /** The composer row's adapter, counted the same way as the tray's under a route of its own. */
    static final String COMPOSER_ROUTE = "Composer row adapter";

    /**
     * The story categories Facebook files the feed's rows of reels under: the "Reels" carousels
     * between posts, their fallback, and the reels it adds where the feed you follow ends. On a
     * signed-in 580 each arrived as a ShowcaseFeedUnit edge (S25, 2026-09-25). The names are the
     * enum's own, which the obfuscator keeps, and the patch holds both builds to all three.
     */
    static final String[] REELS_CATEGORIES = {"FB_SHORTS", "FB_SHORTS_FALLBACK", "END_OF_FEED_REELS"};

    /**
     * What the reels rule read beyond the category: the story type of each ShowcaseFeedUnit it
     * looked at while its switch was on, and every call of the pre-EOF injector, which builds a
     * "Reels" row of its own and adds it to the feed without passing the edge guard.
     */
    static final String REELS_ROUTE = "Reels in feed";
    /** The kind and the removal reason a pre-EOF injector call counts under. */
    static final String PRE_EOF_UNIT = "pre-EOF unit";

    /**
     * The adapter the patch passes: the classic tray, the unified one a server gate turns on, or the
     * "What's on your mind?" composer row above them.
     */
    public static final int LEGACY_TRAY = 0;
    public static final int UNIFIED_TRAY = 1;
    public static final int HOME_COMPOSER = 2;

    /**
     * Units Facebook injects into the feed that are not posts from anyone you follow. Every one
     * keeps its real name through Meta's obfuscator, so the check needs no obfuscated identifier.
     *
     * <p>Left out on purpose: friends' locations, a real feature, which has a switch of its own that
     * starts off ({@link #FRIENDS_LOCATIONS_TYPE}). People You May Know and suggested groups aren't
     * here either: their unit class is Redex-renamed and shared with other rows, so their own rules
     * read the GraphQL type name the unit answers ({@link #PEOPLE_YOU_MAY_KNOW_TYPE},
     * {@link #GROUPS_YOU_SHOULD_JOIN_TYPE}).
     */
    private static final String[] SUGGESTED_UNITS = {
            // "Pages you may like" and its variants.
            "com.facebook.graphql.model.GraphQLPagesYouMayLikeFeedUnit",
            "com.facebook.graphql.model.GraphQLPaginatedPagesYouMayLikeFeedUnit",
            "com.facebook.graphql.model.GraphQLCreativePagesYouMayLikeFeedUnit",
            "com.facebook.graphql.model.GraphQLPYMLWithLargeImageFeedUnit",
            "com.facebook.graphql.model.GraphQLPagesYouMayFollowFeedUnit",
            "com.facebook.graphql.model.GraphQLPagesYouMayAdvertiseFeedUnit",
            "com.facebook.graphql.model.GraphQLPymgfFeedUnit",
            // Facebook's own in-feed upsell nags.
            "com.facebook.graphql.model.GraphQLQuickPromotionFeedUnit",
            "com.facebook.graphql.model.GraphQLQuickPromotionNativeTemplateFeedUnit",
            "com.facebook.graphql.model.GraphQLEndOfFeedUpsellCustomNTFeedUnit",
            "com.facebook.graphql.model.GraphQLExploreFeedUpsellNTUnit",
            "com.facebook.graphql.model.GraphQLGreetingCardPromotionFeedUnit",
            // In-feed prompts and experiment slots.
            "com.facebook.graphql.model.GraphQLStoryGallerySurveyFeedUnit",
            "com.facebook.graphql.model.GraphQLBusinessPageReviewFeedUnit",
            "com.facebook.graphql.model.GraphQLHoldoutAdFeedUnit",
    };

    /** The unit classes this build carries, looked up once. A class Facebook dropped is skipped. */
    private static volatile Class<?>[] suggestedClasses;

    private FeedFilter() {
    }

    /**
     * Injection point, at the start of the feed funnel. Whether the edge with this story category
     * and this feed unit stays out of the news feed. Never throws: false leaves the edge to
     * Facebook.
     *
     * @param category the edge's {@code GraphQLFeedStoryCategory} constant.
     * @param feedUnit the edge's feed unit, inflated if the tree had not built it yet.
     */
    public static boolean hideEdge(Object category, Object feedUnit) {
        return withPatches(category, feedUnit);
    }

    /**
     * The guard with every patch-time flag and filled stub this build carries. The swap guard asks
     * this rather than {@link #hideEdge(Object, Object)}: the mutation contract holds a patched APK
     * to one call of that, the funnel's, and counts the extension's own calls too.
     */
    private static boolean withPatches(Object category, Object feedUnit) {
        return hideEdge(category, feedUnit, SettingsStatus.sponsoredPosts(), SettingsStatus.suggestedPosts(),
                RecommendationLabel.PATCHED, SettingsStatus.aiDetectedPosts(), GenAiLabel.PATCHED,
                SettingsStatus.feedReels(), ShowcaseType.PATCHED, SettingsStatus.postWords(), PostText.MESSAGE,
                PostText.ATTACHED, GenAiLabel.SELF_LABEL_PATCHED, AiCharacterPosts.ATTACHMENTS,
                AiCharacterPosts.STYLES, PostTypes.READERS, GroupPosts.PATCHED);
    }

    /** The guard with the sponsored and suggested patch-time flags passed in, and no GenAI rule. */
    static boolean hideEdge(Object category, Object feedUnit, boolean sponsoredPatched, boolean suggestedPatched) {
        return hideEdge(category, feedUnit, sponsoredPatched, suggestedPatched, RecommendationLabel.PATCHED, false,
                GenAiLabel.PATCHED);
    }

    /**
     * The guard, with the three patch-time flags passed in so a test can stand in for the patches,
     * and the recommendation and GenAI accessors passed in so a test can stand in for the stubs the
     * patches fill in.
     *
     * <p>Every edge is counted before any rule runs, and every hidden one records why, so a
     * diagnostic report shows the guard is alive and what it took out without anyone having to
     * turn debug logging on first. The category name is an enum constant and the reason is a
     * kept class name or a GraphQL field name; none of them is content.
     *
     * <p>The two rules built on a story flag, "Suggested for you" and GenAI, read a story only while
     * their switch is on, so with the switch off or Hushfacebook paused they read nothing of the post
     * and Facebook's own path is all that runs.
     */
    static boolean hideEdge(Object category, Object feedUnit, boolean sponsoredPatched, boolean suggestedPatched,
            StoryFlag.Accessor recommendationAccessor, boolean aiPatched, StoryFlag.Accessor aiAccessor) {
        return hideEdge(category, feedUnit, sponsoredPatched, suggestedPatched, recommendationAccessor, aiPatched,
                aiAccessor, false);
    }

    /** The guard with the reels patch's flag passed in too, and the showcase stub it fills. */
    static boolean hideEdge(Object category, Object feedUnit, boolean sponsoredPatched, boolean suggestedPatched,
            StoryFlag.Accessor recommendationAccessor, boolean aiPatched, StoryFlag.Accessor aiAccessor,
            boolean reelsPatched) {
        return hideEdge(category, feedUnit, sponsoredPatched, suggestedPatched, recommendationAccessor, aiPatched,
                aiAccessor, reelsPatched, ShowcaseType.PATCHED);
    }

    /** The guard with the showcase story type accessor passed in, so a test can stand in for it. */
    static boolean hideEdge(Object category, Object feedUnit, boolean sponsoredPatched, boolean suggestedPatched,
            StoryFlag.Accessor recommendationAccessor, boolean aiPatched, StoryFlag.Accessor aiAccessor,
            boolean reelsPatched, StoryFlag.Accessor showcaseAccessor) {
        return hideEdge(category, feedUnit, sponsoredPatched, suggestedPatched, recommendationAccessor, aiPatched,
                aiAccessor, reelsPatched, showcaseAccessor, false, PostText.MESSAGE, PostText.ATTACHED);
    }

    /**
     * The guard with the word filter's flag passed in too, and the two text accessors its patch
     * fills, so a test can stand in for them.
     *
     * <p>The word filter reads a post only while its switch is on and its hide list holds a
     * phrase. Its kinds and its reason are shapes; the words it read and the phrase that matched
     * go nowhere.
     */
    static boolean hideEdge(Object category, Object feedUnit, boolean sponsoredPatched, boolean suggestedPatched,
            StoryFlag.Accessor recommendationAccessor, boolean aiPatched, StoryFlag.Accessor aiAccessor,
            boolean reelsPatched, StoryFlag.Accessor showcaseAccessor, boolean wordsPatched,
            StoryFlag.Accessor messageAccessor, StoryFlag.Accessor attachedAccessor) {
        return hideEdge(category, feedUnit, sponsoredPatched, suggestedPatched, recommendationAccessor, aiPatched,
                aiAccessor, reelsPatched, showcaseAccessor, wordsPatched, messageAccessor, attachedAccessor,
                GenAiLabel.SELF_LABEL_PATCHED);
    }

    /**
     * The guard with the GenAI patch's second stub passed in too, the creator's AI label, so a test
     * can stand in for it.
     *
     * <p>Facebook's header label shows on either GenAI flag: the one its detection sets, and the one
     * a post's creator sets. The detection rule reads the first while either GenAI switch is on, and
     * the label rule reads the second only while its own switch is on, after the first found nothing,
     * so with that switch on every post carrying either flag goes and with it off nothing changes.
     */
    static boolean hideEdge(Object category, Object feedUnit, boolean sponsoredPatched, boolean suggestedPatched,
            StoryFlag.Accessor recommendationAccessor, boolean aiPatched, StoryFlag.Accessor aiAccessor,
            boolean reelsPatched, StoryFlag.Accessor showcaseAccessor, boolean wordsPatched,
            StoryFlag.Accessor messageAccessor, StoryFlag.Accessor attachedAccessor,
            StoryFlag.Accessor aiLabelAccessor) {
        return hideEdge(category, feedUnit, sponsoredPatched, suggestedPatched, recommendationAccessor, aiPatched,
                aiAccessor, reelsPatched, showcaseAccessor, wordsPatched, messageAccessor, attachedAccessor,
                aiLabelAccessor, null, null);
    }

    /**
     * The guard with the two readers of the AI character rule passed in too, so a test can stand in
     * for the stubs Hide AI-detected posts fills: the story's attachments and Facebook's finder of an
     * attachment's style. Null readers leave the rule out, as the overloads above do.
     */
    static boolean hideEdge(Object category, Object feedUnit, boolean sponsoredPatched, boolean suggestedPatched,
            StoryFlag.Accessor recommendationAccessor, boolean aiPatched, StoryFlag.Accessor aiAccessor,
            boolean reelsPatched, StoryFlag.Accessor showcaseAccessor, boolean wordsPatched,
            StoryFlag.Accessor messageAccessor, StoryFlag.Accessor attachedAccessor,
            StoryFlag.Accessor aiLabelAccessor, @Nullable StoryFlag.Accessor attachmentsAccessor,
            @Nullable AiCharacterPosts.Finder styleFinder) {
        return hideEdge(category, feedUnit, sponsoredPatched, suggestedPatched, recommendationAccessor, aiPatched,
                aiAccessor, reelsPatched, showcaseAccessor, wordsPatched, messageAccessor, attachedAccessor,
                aiLabelAccessor, attachmentsAccessor, styleFinder, null);
    }

    /**
     * The guard with the readers of the word filter's kinds of post passed in too, so a test can
     * stand in for the stubs Hide posts by words fills: the story's attachments, an attachment's
     * styles and the story's text format. Null readers leave those switches out, as the overloads
     * above do.
     */
    static boolean hideEdge(Object category, Object feedUnit, boolean sponsoredPatched, boolean suggestedPatched,
            StoryFlag.Accessor recommendationAccessor, boolean aiPatched, StoryFlag.Accessor aiAccessor,
            boolean reelsPatched, StoryFlag.Accessor showcaseAccessor, boolean wordsPatched,
            StoryFlag.Accessor messageAccessor, StoryFlag.Accessor attachedAccessor,
            StoryFlag.Accessor aiLabelAccessor, @Nullable StoryFlag.Accessor attachmentsAccessor,
            @Nullable AiCharacterPosts.Finder styleFinder, @Nullable PostTypes.Readers typeReaders) {
        return hideEdge(category, feedUnit, sponsoredPatched, suggestedPatched, recommendationAccessor, aiPatched,
                aiAccessor, reelsPatched, showcaseAccessor, wordsPatched, messageAccessor, attachedAccessor,
                aiLabelAccessor, attachmentsAccessor, styleFinder, typeReaders, null);
    }

    /**
     * The guard with the two readers of the groups rule passed in too, so a test can stand in for
     * the stubs Hide suggested and promoted posts fills: the story's {@code to} and that group's join
     * state. Null readers leave the rule out, as the overloads above do.
     */
    static boolean hideEdge(Object category, Object feedUnit, boolean sponsoredPatched, boolean suggestedPatched,
            StoryFlag.Accessor recommendationAccessor, boolean aiPatched, StoryFlag.Accessor aiAccessor,
            boolean reelsPatched, StoryFlag.Accessor showcaseAccessor, boolean wordsPatched,
            StoryFlag.Accessor messageAccessor, StoryFlag.Accessor attachedAccessor,
            StoryFlag.Accessor aiLabelAccessor, @Nullable StoryFlag.Accessor attachmentsAccessor,
            @Nullable AiCharacterPosts.Finder styleFinder, @Nullable PostTypes.Readers typeReaders,
            @Nullable GroupPosts.Readers groupReaders) {
        boolean trayPatched = storiesTrayInBuild();
        try {
            if (sponsoredPatched) HookStatus.invoked(FamilyNames.SPONSORED_POSTS);
            if (trayPatched) HookStatus.invoked(FamilyNames.STORIES_TRAY);
            if (reelsPatched) HookStatus.invoked(FamilyNames.FEED_REELS);
            if (wordsPatched) HookStatus.invoked(FamilyNames.POST_WORDS);
            if (suggestedPatched) {
                HookStatus.invoked(FamilyNames.SUGGESTED_POSTS);
                reportSuggestedClasses();
                RecommendationLabel.FLAG.report();
            }
            if (aiPatched) {
                HookStatus.invoked(FamilyNames.AI_DETECTED_POSTS);
                GenAiLabel.FLAG.report();
            }
            FeedFilterCounters.sawList(FEED_ROUTE, 1);
            String categoryName = category instanceof Enum ? ((Enum<?>) category).name() : null;
            FeedFilterCounters.sawKind(FEED_ROUTE, categoryName);
            // What kind of post this is, for a report of what reaches the feed: an enum constant, a
            // GraphQL type name and what Facebook's recommendation and GenAI flags read, never the
            // post itself. Built only when debug logging is on, and the flags and the showcase type
            // only read with the patch that fills their accessor.
            Logger.printDebug(() -> {
                String type = typeName(feedUnit);
                String id = SeenPosts.shortId(feedUnit);
                return "Feed edge: " + categoryName + " " + type + " ifr="
                        + (suggestedPatched ? recommendationFlag(feedUnit, recommendationAccessor) : "none")
                        + (reelsPatched ? " showcase=" + showcaseFor(type, feedUnit, showcaseAccessor) : "")
                        + (suggestedPatched && DISCOVER_UNIT_TYPE.equals(type)
                                ? " stories=" + unconnectedStories(feedUnit) : "")
                        + (suggestedPatched && groupReaders != null
                                ? " group=" + GroupPosts.describe(feedUnit, groupReaders) : "")
                        + (aiPatched ? " genai=" + flagValue(GenAiLabel.FLAG, feedUnit, aiAccessor)
                                + " ailabel=" + flagValue(GenAiLabel.SELF_LABEL, feedUnit, aiLabelAccessor) : "")
                        + (ENGAGEMENT_PROMO.equals(categoryName) ? " qp=" + promotionId(feedUnit) : "")
                        + (wordsPatched ? " author=" + PostSources.authorKind(feedUnit, PostSources.ACTORS)
                                + " friend=" + PostSources.friendKind(feedUnit, PostSources.ACTORS) : "")
                        + (id != null ? " id=" + id : "");
            });
            // An edge a prefetch adds before the settings are ready stays: no switch can be read yet.
            if (!Utils.settingsReady()) return false;

            String reason = null;
            // What a hidden edge counts under on the settings tally. Each group of rules below sets
            // it while nothing has hidden the edge yet, so it ends on the group that did.
            HiddenTally.Kind kind = HiddenTally.Kind.ADS;
            if (sponsoredPatched && hiddenCategory(category)) {
                reason = categoryName;
            } else if (sponsoredPatched && withFriendsOnly(Settings.HIDE_SPONSORED_POSTS)
                    && MULTI_ADS_TYPE.equals(typeName(feedUnit))) {
                reason = MULTI_ADS_TYPE;
            }
            if (reason == null) kind = HiddenTally.Kind.REELS;
            if (reason == null && reelsPatched && withFriendsOnly(Settings.HIDE_FEED_REELS)) {
                reason = isReelsCategory(categoryName) ? categoryName : showcaseReason(feedUnit, showcaseAccessor);
            }
            if (reason == null) kind = HiddenTally.Kind.SUGGESTED;
            if (reason == null && suggestedPatched) {
                boolean suggestedPosts = withFriendsOnly(Settings.HIDE_SUGGESTED_POSTS);
                boolean suggestedGroups = withFriendsOnly(Settings.HIDE_SUGGESTED_GROUPS);
                if (suggestedPosts) reason = suggestedUnitName(feedUnit);
                if (reason == null && ENGAGEMENT_PROMO.equals(categoryName)) {
                    if (suggestedPosts) {
                        reason = ENGAGEMENT_PROMO;
                    } else if (suggestedGroups && GROUPS_PROMOTION_ID.equals(promotionId(feedUnit))) {
                        reason = ENGAGEMENT_PROMO + ":" + GROUPS_PROMOTION_ID;
                    }
                }
                if (reason == null && withFriendsOnly(Settings.HIDE_SUGGESTED_FOR_YOU)) {
                    reason = suggestedCategory(categoryName);
                    if (reason == null) {
                        reason = flagReason(RecommendationLabel.FLAG, RECOMMENDATION_ROUTE, feedUnit, recommendationAccessor);
                    }
                }
                boolean storiesYouMightLike = withFriendsOnly(Settings.HIDE_STORIES_YOU_MIGHT_LIKE);
                if (reason == null && (suggestedPosts || withFriendsOnly(Settings.HIDE_PEOPLE_YOU_MAY_KNOW)
                        || suggestedGroups || storiesYouMightLike || Settings.HIDE_FEED_MEMORIES.get()
                        || Settings.HIDE_FEED_FRIEND_REQUESTS.get() || Settings.HIDE_FRIENDS_LOCATIONS.get())) {
                    String type = typeName(feedUnit);
                    reason = suggestedTypeReason(type);
                    if (cardType(reason)) kind = HiddenTally.Kind.CARDS;
                    if (reason == null && storiesYouMightLike && DISCOVER_UNIT_TYPE.equals(type)) {
                        reason = unconnectedStoriesReason(feedUnit);
                    }
                }
                if (reason == null) kind = HiddenTally.Kind.GROUPS;
                if (reason == null && groupReaders != null && withFriendsOnly(Settings.HIDE_GROUP_POSTS)) {
                    reason = groupReason(feedUnit, groupReaders, true);
                } else if (reason == null && groupReaders != null && Settings.HIDE_GROUP_POSTS_NOT_JOINED.get()) {
                    reason = groupReason(feedUnit, groupReaders, false);
                }
            }
            if (reason == null) kind = HiddenTally.Kind.STORIES;
            if (reason == null && trayPatched && Settings.HIDE_STORIES_BETWEEN_POSTS.get()) {
                reason = storiesRowReason(typeName(feedUnit));
            }
            if (reason == null) kind = HiddenTally.Kind.AI;
            boolean metaAi = aiPatched && Settings.HIDE_META_AI_FEED_UNITS.get();
            if (reason == null && metaAi) {
                String type = typeName(feedUnit);
                if (META_AI_UNIT_TYPE.equals(type) || VIBES_PROMOTION_UNIT_TYPE.equals(type)) reason = type;
            }
            if (reason == null && aiPatched && attachmentsAccessor != null && styleFinder != null
                    && Settings.HIDE_AI_CHARACTER_POSTS.get()) {
                reason = aiCharacterReason(feedUnit, attachmentsAccessor, styleFinder);
            }
            boolean aiLabelled = aiPatched && Settings.HIDE_AI_LABELLED_POSTS.get();
            if (reason == null && aiPatched && (aiLabelled || Settings.HIDE_AI_DETECTED_POSTS.get())) {
                reason = flagReason(GenAiLabel.FLAG, AI_ROUTE, feedUnit, aiAccessor);
            }
            if (reason == null && aiLabelled) {
                reason = flagReason(GenAiLabel.SELF_LABEL, AI_LABEL_ROUTE, feedUnit, aiLabelAccessor);
            }
            if (reason == null) kind = HiddenTally.Kind.YOUR_FILTERS;
            if (reason == null && wordsPatched && Settings.HIDE_POSTS_WITH_WORDS.get()) {
                reason = wordsReason(feedUnit, messageAccessor, attachedAccessor, PostSources.ATTACHMENTS,
                        PostText.ACTION_LINKS);
            }
            if (reason == null && wordsPatched) {
                reason = sourcesReason(feedUnit, PostSources.ACTORS, PostSources.ATTACHMENTS, attachedAccessor);
            }
            if (reason == null && wordsPatched && withFriendsOnly(Settings.HIDE_PAGE_POSTS)) {
                reason = pagesReason(feedUnit, PostSources.ACTORS, groupReaders);
            }
            if (reason == null && wordsPatched && Settings.ONLY_FRIENDS_POSTS.get()) {
                reason = friendsReason(feedUnit, PostSources.ACTORS);
            }
            if (reason == null && wordsPatched && Settings.HIDE_OTHER_LANGUAGES.get()) {
                reason = languageReason(feedUnit, messageAccessor, attachedAccessor, PostSources.ATTACHMENTS,
                        PostText.ACTION_LINKS);
            }
            if (reason == null && wordsPatched && typeReaders != null) {
                reason = typesReason(feedUnit, typeReaders, attachedAccessor);
            }
            if (reason == null && wordsPatched && typeReaders != null) {
                reason = reactionsReason(feedUnit, typeReaders);
            }
            if (reason == null && wordsPatched && Settings.HIDE_REPOSTS.get()) {
                reason = repostReason(feedUnit, attachedAccessor);
            }
            // Last of the filters: a type picked by name, which also writes the type down for its list.
            if (reason == null && wordsPatched) reason = FeedTypeNames.reason(typeName(feedUnit));
            // The seen rule goes last so a post another rule would hide is counted under that rule.
            if (reason == null) {
                kind = HiddenTally.Kind.SEEN;
                reason = SeenPosts.hideReason(feedUnit);
            }
            // The first post after a link lands Home on it is that post (#306): the person asked
            // for it, so whatever rule would hide it stands aside, the ad rules too. Facebook serves
            // a boosted page post the link opens as a sponsored edge, so holding ads back hid the
            // linked post and spent the link on the suggested post after it.
            if (LinkedPost.isPost(feedUnit) && LinkedPost.takeFirst()) {
                FeedEndHint.kept();
                if (reason == null) return false;
                FeedFilterCounters.sawKind(FEED_ROUTE, LinkedPost.KEPT_KIND);
                final String setAside = reason;
                Logger.printDebug(() -> "Feed filter: kept the post a link opened (" + setAside + ")");
                return false;
            }
            if (reason == null) {
                FeedEndHint.kept();
                return false;
            }

            FeedFilterCounters.removed(FEED_ROUTE, 1, reason);
            HiddenTally.hid(kind);
            FeedEndHint.hid(kind);
            // A page this empties can leave Home's scroll fetcher at the end of its list with no
            // reason to ask again, so TailFetch asks for it from now on.
            TailFetch.hidEdge();
            final String hidden = reason;
            Logger.printDebug(() -> "Feed filter: hid a " + hidden + " post");
            return true;
        } catch (Throwable failure) {
            if (sponsoredPatched) HookStatus.threw(FamilyNames.SPONSORED_POSTS, "feed guard", failure);
            if (trayPatched) HookStatus.threw(FamilyNames.STORIES_TRAY, "feed guard", failure);
            if (reelsPatched) HookStatus.threw(FamilyNames.FEED_REELS, "feed guard", failure);
            if (suggestedPatched) HookStatus.threw(FamilyNames.SUGGESTED_POSTS, "feed guard", failure);
            if (aiPatched) HookStatus.threw(FamilyNames.AI_DETECTED_POSTS, "feed guard", failure);
            if (wordsPatched) HookStatus.threw(FamilyNames.POST_WORDS, "feed guard", failure);
            Logger.printException(() -> "Feed filter: could not judge an edge", failure);
            return false;
        }
    }

    /**
     * Whether Hide Stories tray is in this build: its switch hides the rows of Stories between posts
     * as well as the tray, through this guard.
     */
    static boolean storiesTrayInBuild() {
        Boolean forTests = storiesTrayInBuildForTests;
        return forTests != null ? forTests : SettingsStatus.storiesTray();
    }

    /**
     * Hide the Stories tray's rule for the Stories that come as feed edges (issue #45): a row of
     * several people's Stories between posts, {@link #DISCOVER_UNIT_TYPE}, your friends' as well as
     * the ones "Stories you might like" takes, the tray itself should it come as an edge,
     * {@link #STORIES_TRAY_UNIT_TYPE}, and the single tiles and viewers of Stories,
     * {@link #STORIES_LARGE_TILE_UNIT_TYPE} and {@link #STORIES_INLINE_VIEWER_UNIT_TYPE}. The reason
     * names the type; any other type, or none, is null.
     */
    @Nullable
    static String storiesRowReason(@Nullable String type) {
        if (DISCOVER_UNIT_TYPE.equals(type) || STORIES_TRAY_UNIT_TYPE.equals(type)
                || STORIES_LARGE_TILE_UNIT_TYPE.equals(type) || STORIES_INLINE_VIEWER_UNIT_TYPE.equals(type)) {
            return type + ":" + STORIES_TRAY_REASON;
        }
        return null;
    }

    /**
     * A rule built on a story flag: the flag's name when it reads a definite true for this unit,
     * otherwise null. Every unit it reads is counted on the rule's own route under what the read
     * found, so a kept post always has a reason in the report.
     */
    private static String flagReason(StoryFlag flag, String route, Object feedUnit, StoryFlag.Accessor accessor) {
        StoryFlag.Outcome outcome = flag.read(feedUnit, accessor);
        String why = flag.reason(outcome);
        FeedFilterCounters.sawList(route, 1);
        FeedFilterCounters.sawKind(route, why);
        if (!outcome.hides) return null;
        FeedFilterCounters.removed(route, 1, why);
        return flag.flag;
    }

    /**
     * Hide AI character posts' rule for posts that carry an AI character: the style's type name
     * when one of the post's attachments has it, otherwise null. Every unit it reads is counted on
     * its own route under what the read found, so a kept post always has a reason in the report.
     */
    private static String aiCharacterReason(Object feedUnit, StoryFlag.Accessor attachments,
            AiCharacterPosts.Finder styles) {
        String kind = AiCharacterPosts.read(feedUnit, attachments, styles);
        FeedFilterCounters.sawList(AiCharacterPosts.ROUTE, 1);
        FeedFilterCounters.sawKind(AiCharacterPosts.ROUTE, kind);
        if (!AiCharacterPosts.FOUND.equals(kind)) return null;
        FeedFilterCounters.removed(AiCharacterPosts.ROUTE, 1, kind);
        return AiCharacterPosts.STYLE_TYPE;
    }

    /**
     * The rules built on a unit's GraphQL type name: the name, when it's People you may know,
     * suggested groups, a Memory, friend requests, friends' locations or one of
     * {@link #SUGGESTED_TYPES} and that kind's switch is on, otherwise null. A name that couldn't be
     * read is null, so the unit stays.
     */
    private static String suggestedTypeReason(String type) {
        if (type == null) return null;
        if (withFriendsOnly(Settings.HIDE_SUGGESTED_POSTS)) {
            for (String kind : SUGGESTED_TYPES) {
                if (kind.equals(type)) return kind;
            }
        }
        if (PEOPLE_YOU_MAY_KNOW_TYPE.equals(type)) {
            return withFriendsOnly(Settings.HIDE_PEOPLE_YOU_MAY_KNOW) ? PEOPLE_YOU_MAY_KNOW_TYPE : null;
        }
        if (GROUPS_YOU_SHOULD_JOIN_TYPE.equals(type)) {
            return withFriendsOnly(Settings.HIDE_SUGGESTED_GROUPS) ? GROUPS_YOU_SHOULD_JOIN_TYPE : null;
        }
        if (FRIEND_REQUESTS_TYPE.equals(type)) {
            return Settings.HIDE_FEED_FRIEND_REQUESTS.get() ? FRIEND_REQUESTS_TYPE : null;
        }
        if (FRIENDS_LOCATIONS_TYPE.equals(type)) {
            return Settings.HIDE_FRIENDS_LOCATIONS.get() ? FRIENDS_LOCATIONS_TYPE : null;
        }
        for (String memory : MEMORIES_TYPES) {
            if (memory.equals(type)) return Settings.HIDE_FEED_MEMORIES.get() ? memory : null;
        }
        return null;
    }

    /** Whether a reason from {@link #suggestedTypeReason} is a card of your own, not a suggestion. */
    static boolean cardType(@Nullable String reason) {
        if (FRIEND_REQUESTS_TYPE.equals(reason) || FRIENDS_LOCATIONS_TYPE.equals(reason)) return true;
        for (String memory : MEMORIES_TYPES) {
            if (memory.equals(reason)) return true;
        }
        return false;
    }

    /**
     * The "Stories you might like" rule for a unit that answers {@link #DISCOVER_UNIT_TYPE}: its
     * reason when the row's {@link #UNCONNECTED_STORIES_FLAG} reads a definite true, otherwise null,
     * so a row of your friends' Stories, and one this can't read, stays. Every row it reads is
     * counted on its own route under what the read found.
     */
    private static String unconnectedStoriesReason(Object feedUnit) {
        String kind = unconnectedStories(feedUnit);
        FeedFilterCounters.sawList(STORIES_YOU_MIGHT_LIKE_ROUTE, 1);
        FeedFilterCounters.sawKind(STORIES_YOU_MIGHT_LIKE_ROUTE, kind);
        if (!UNCONNECTED.equals(kind)) return null;
        String reason = DISCOVER_UNIT_TYPE + ":" + UNCONNECTED_STORIES_FLAG;
        FeedFilterCounters.removed(STORIES_YOU_MIGHT_LIKE_ROUTE, 1, reason);
        return reason;
    }

    /**
     * What a row of Stories' {@link #UNCONNECTED_STORIES_FLAG} reads: {@link #UNCONNECTED},
     * {@link #CONNECTED}, or why it couldn't be read. It goes through
     * {@code BaseModelWithTree.getCachedBoolean}, the reader the story flags use, which checks the
     * native tree is still there first. Never throws.
     */
    static String unconnectedStories(Object feedUnit) {
        StoryFlag.Members found = StoryFlag.members();
        if (found.treeModel == null || found.cachedBoolean == null) return "reader missing";
        if (!found.treeModel.isInstance(feedUnit)) return "not a tree model";
        try {
            Object value = found.cachedBoolean.invoke(feedUnit, UNCONNECTED_STORIES_KEY);
            if (!(value instanceof Boolean)) return "read failed";
            return (Boolean) value ? UNCONNECTED : CONNECTED;
        } catch (InvocationTargetException failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_POSTS, "Stories you might like flag reader", failure.getCause());
            return "read failed";
        } catch (ReflectiveOperationException | RuntimeException failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_POSTS, "Stories you might like flag reader", failure);
            return "read failed";
        }
    }

    /**
     * The quick promotion an engagement card's {@code tracking} JSON names, or null when it names
     * none or can't be read. It goes through {@code BaseModelWithTree.getCachedString}, the reader
     * Facebook's own code reads a model's tracking with in 577 and 580, which checks the native
     * tree is still there first. Never throws.
     */
    static String promotionId(Object feedUnit) {
        PostText.Members found = PostText.members();
        if (found.treeModel == null || found.cachedString == null) return null;
        if (!found.treeModel.isInstance(feedUnit)) return null;
        try {
            Object tracking = found.cachedString.invoke(feedUnit, TRACKING_KEY);
            if (!(tracking instanceof String)) return null;
            String json = (String) tracking;
            int start = json.indexOf(PROMOTION_ID_FIELD);
            if (start < 0) return null;
            start += PROMOTION_ID_FIELD.length();
            int end = json.indexOf('"', start);
            return end > start ? json.substring(start, end) : null;
        } catch (InvocationTargetException failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_POSTS, "promotion id reader", failure.getCause());
            return null;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_POSTS, "promotion id reader", failure);
            return null;
        }
    }

    /**
     * The people, Pages and sites rule: {@link #SOURCES_REASON} and the matching line's number when
     * one of the post's authors or links, or those of the post it shares, is on the list, otherwise
     * null. Nothing is read while the switch is off or the list is empty, and a post whose authors
     * and links can't be read is kept. Each story it reads is counted on its route by outcome.
     */
    static String sourcesReason(Object feedUnit, StoryFlag.Accessor actors, StoryFlag.Accessor attachments,
            StoryFlag.Accessor attached) {
        if (!Settings.HIDE_POSTS_FROM_SOURCES.get()) return null;
        FeedFilterCounters.sawList(SOURCES_ROUTE, 1);
        java.util.List<PostSources.Rule> rules = PostSources.cachedRules(Settings.HIDDEN_SOURCES.get());
        if (rules.isEmpty()) {
            FeedFilterCounters.sawKind(SOURCES_ROUTE, NO_SOURCES);
            return null;
        }
        PostSources.Found found = PostSources.read(feedUnit, actors, attachments, attached);
        if (found.outcome != PostSources.Outcome.READ) {
            FeedFilterCounters.sawKind(SOURCES_ROUTE, found.outcome.reason);
            return null;
        }
        int line = PostSources.match(rules, found);
        if (line == 0) {
            FeedFilterCounters.sawKind(SOURCES_ROUTE, NO_SOURCE_MATCH);
            return null;
        }
        String reason = SOURCES_REASON + " " + line;
        FeedFilterCounters.sawKind(SOURCES_ROUTE, reason);
        FeedFilterCounters.removed(SOURCES_ROUTE, 1, reason);
        return reason;
    }

    /**
     * Only friends' posts (#406): what {@link PostSources#friendKind} found, such as
     * {@code not a friend: Page}, when none of the post's own authors is a friend, otherwise null.
     * A post whose authors can't be read stays. Each story it reads is counted on its route by kind.
     */
    static String friendsReason(Object feedUnit, StoryFlag.Accessor actors) {
        FeedFilterCounters.sawList(FRIENDS_ROUTE, 1);
        String kind = PostSources.friendKind(feedUnit, actors);
        FeedFilterCounters.sawKind(FRIENDS_ROUTE, kind);
        if (!PostSources.notAFriend(kind)) return null;
        FeedFilterCounters.removed(FRIENDS_ROUTE, 1, kind);
        return kind;
    }

    /**
     * Whether a switch Only friends' posts turns on with it is on (#406): its own value, or true
     * while Only friends' posts is on. The switch's saved value doesn't change, so turning Only
     * friends' posts off puts back what was picked.
     */
    static boolean withFriendsOnly(BooleanSetting setting) {
        return setting.get() || Settings.ONLY_FRIENDS_POSTS.get();
    }

    /**
     * Hide posts from Pages (#262): {@value PostSources#PAGE} when every author of the post is a
     * Page and the post isn't in a group, otherwise null. Only the post's own authors count, so a
     * friend's share of a Page's post stays. A post in a group stays whoever wrote it, as the
     * switch's summary says: on 586 a Page is told apart only by its person fields, and Hide all
     * group posts is the switch for group posts. [groups] is null in a build without the group
     * rule, and then the authors alone decide. An author whose type can't be read counts as a
     * person, and a post whose authors can't be read is kept.
     */
    static String pagesReason(Object feedUnit, StoryFlag.Accessor actors, @Nullable GroupPosts.Readers groups) {
        FeedFilterCounters.sawList(PAGES_ROUTE, 1);
        String kind = PostSources.authorKind(feedUnit, actors);
        if (PostSources.PAGE.equals(kind) && groups != null && GroupPosts.inAGroup(GroupPosts.read(feedUnit, groups))) {
            kind = PAGE_IN_A_GROUP;
        }
        FeedFilterCounters.sawKind(PAGES_ROUTE, kind);
        if (!PostSources.PAGE.equals(kind)) return null;
        FeedFilterCounters.removed(PAGES_ROUTE, 1, kind);
        return kind;
    }

    /**
     * The word filter: {@link #WORDS_REASON} when the post's own words, or those of the post it
     * shares, hold a phrase from the hide list and none from the keep list, otherwise null. A post's
     * words include its link cards' headlines and descriptions, read through [links] and each card's
     * [actionLinks]. Every story
     * it's asked about is counted on its route under which list matched or why nothing was read, so
     * a kept post always has a reason in the report. With the hide list empty nothing of the post is
     * read, and a post whose words can't be read is kept.
     */
    static String wordsReason(Object feedUnit, StoryFlag.Accessor message, StoryFlag.Accessor attached,
            StoryFlag.Accessor links, StoryFlag.Accessor actionLinks) {
        FeedFilterCounters.sawList(WORDS_ROUTE, 1);
        PostWords.Rules rules = PostWords.rules(Settings.HIDDEN_WORDS.get(), Settings.KEPT_WORDS.get(),
                Settings.POST_WORDS_WHOLE_WORDS.get());
        if (rules.hidesNothing()) {
            FeedFilterCounters.sawKind(WORDS_ROUTE, NO_WORDS);
            return null;
        }
        PostText.Read read = PostText.read(feedUnit, message, attached, links, actionLinks);
        if (read.outcome != PostText.Outcome.TEXT) {
            FeedFilterCounters.sawKind(WORDS_ROUTE, read.outcome.reason);
            return null;
        }
        PostWords.Verdict verdict = rules.judge(read.texts);
        FeedFilterCounters.sawKind(WORDS_ROUTE, verdict.reason);
        if (!verdict.hides()) return null;
        FeedFilterCounters.removed(WORDS_ROUTE, 1, verdict.reason);
        PostWords.HIDDEN.incrementAndGet();
        return WORDS_REASON;
    }

    /**
     * Hide posts in other languages: {@link PostLanguages#REASON} when the phone is sure the words the
     * word filter reads of a post are in languages that aren't kept, otherwise null. Every story it's
     * asked about is counted on its route under what it decided, or why nothing was read, and a post
     * whose words can't be read is kept.
     */
    static String languageReason(Object feedUnit, StoryFlag.Accessor message, StoryFlag.Accessor attached,
            StoryFlag.Accessor links, StoryFlag.Accessor actionLinks) {
        FeedFilterCounters.sawList(PostLanguages.ROUTE, 1);
        String kind = PostLanguages.storyKind(feedUnit, message, attached, links, actionLinks);
        FeedFilterCounters.sawKind(PostLanguages.ROUTE, kind);
        if (!PostLanguages.REASON.equals(kind)) return null;
        FeedFilterCounters.removed(PostLanguages.ROUTE, 1, kind);
        return PostLanguages.REASON;
    }

    /**
     * Hide posts from groups you're not in: the group type and the join state, such as
     * {@code Group:CAN_JOIN}, when the post was posted in a group Facebook would show a Join button
     * for, or wraps one a friend commented on or shared ({@code Group:wrapped CAN_JOIN}), otherwise
     * null. With [all], Hide all group posts (#409): any group, your own included, such as
     * {@code Group:MEMBER}. Nothing is read while the switch is off, and a post that can't be read
     * stays. Each post it reads is counted on the switch's route by what the read found.
     */
    static String groupReason(Object feedUnit, GroupPosts.Readers readers, boolean all) {
        String route = all ? GroupPosts.ALL_ROUTE : GroupPosts.ROUTE;
        String kind = GroupPosts.read(feedUnit, readers);
        FeedFilterCounters.sawList(route, 1);
        FeedFilterCounters.sawKind(route, kind);
        if (!(all ? GroupPosts.inAGroup(kind) : GroupPosts.notJoined(kind))) return null;
        String reason = GroupPosts.GROUP_TYPE + ":" + kind;
        FeedFilterCounters.removed(route, 1, reason);
        return reason;
    }

    /**
     * The reaction ceiling: {@link PostReactions#ABOVE} when the post has more reactions than the
     * ceiling the person set, otherwise null. Nothing is read while the ceiling is off, and a post
     * whose count can't be read stays. Each post it reads is counted on its route by what the read
     * found.
     */
    static String reactionsReason(Object feedUnit, PostTypes.Readers readers) {
        ReactionCeiling ceiling = Settings.HIDE_POSTS_OVER_REACTIONS.get();
        if (ceiling == ReactionCeiling.OFF) return null;
        FeedFilterCounters.sawList(PostReactions.ROUTE, 1);
        PostReactions.Read read = PostReactions.read(feedUnit, readers.feedback, readers.reactors);
        boolean above = ceiling.exceeds(read.count);
        FeedFilterCounters.sawKind(PostReactions.ROUTE, above ? PostReactions.ABOVE : read.reason);
        if (!above) return null;
        FeedFilterCounters.removed(PostReactions.ROUTE, 1, PostReactions.ABOVE);
        return PostReactions.ABOVE;
    }

    /**
     * Hide reposts' rule: {@link PostText#REPOST} when the post shares another post, with a caption
     * or without, otherwise null. Nothing is read while the switch is off, and an original post or
     * one that can't be read stays. Each post it reads is counted on its route by what the read
     * found, a shape and never the post.
     */
    private static String repostReason(Object feedUnit, StoryFlag.Accessor attached) {
        String kind = PostText.shareKind(feedUnit, attached);
        FeedFilterCounters.sawList(PostText.REPOST_ROUTE, 1);
        FeedFilterCounters.sawKind(PostText.REPOST_ROUTE, kind);
        if (!PostText.REPOST.equals(kind)) return null;
        FeedFilterCounters.removed(PostText.REPOST_ROUTE, 1, kind);
        return kind;
    }

    /**
     * The kinds of post rule: the kind's name, such as {@link PostTypes#PHOTO}, when the post or
     * the one it shares is a kind a switch hides, otherwise null. Nothing is read while every one of
     * the four switches is off, and a post that can't be read stays. Each story it reads is counted
     * on its route by what the read found.
     */
    static String typesReason(Object feedUnit, PostTypes.Readers readers, StoryFlag.Accessor attached) {
        PostTypes.Wanted wanted = new PostTypes.Wanted(Settings.HIDE_PHOTO_POSTS.get(), Settings.HIDE_VIDEO_POSTS.get(),
                Settings.HIDE_LINK_POSTS.get(), Settings.HIDE_BACKGROUND_POSTS.get());
        if (!wanted.any()) return null;
        FeedFilterCounters.sawList(PostTypes.ROUTE, 1);
        String kind = PostTypes.read(feedUnit, readers, attached, wanted);
        FeedFilterCounters.sawKind(PostTypes.ROUTE, kind);
        if (!wanted.hides(kind)) return null;
        FeedFilterCounters.removed(PostTypes.ROUTE, 1, kind);
        return kind;
    }

    /**
     * The reels rule for an edge whose category isn't one of the reels categories: a
     * ShowcaseFeedUnit whose story type is a row of reels. Its reason is the type name and the story
     * type, such as {@code ShowcaseFeedUnit:SHOWCASE_SHORT_VIDEO}; any other unit, and a showcase
     * unit of another type or one this can't read, answers null and stays. Every showcase unit it
     * reads is counted on the reels route under the type it read. Any other unit never reaches the
     * accessor: the patch fills it for the one class that answers the showcase type name.
     */
    private static String showcaseReason(Object feedUnit, StoryFlag.Accessor accessor) {
        if (!ShowcaseType.UNIT_TYPE.equals(typeName(feedUnit))) return null;
        String type = ShowcaseType.read(feedUnit, accessor);
        FeedFilterCounters.sawList(REELS_ROUTE, 1);
        FeedFilterCounters.sawKind(REELS_ROUTE, type);
        if (!ShowcaseType.isReels(type)) return null;
        String reason = ShowcaseType.UNIT_TYPE + ":" + type;
        FeedFilterCounters.removed(REELS_ROUTE, 1, reason);
        return reason;
    }

    /** A showcase unit's story type for the debug line, or none for any other unit. */
    private static String showcaseFor(String typeName, Object feedUnit, StoryFlag.Accessor accessor) {
        return ShowcaseType.UNIT_TYPE.equals(typeName) ? ShowcaseType.read(feedUnit, accessor) : "none";
    }

    /** Facebook's recommendation flag for the debug line: true, false, or none when it can't say. */
    private static String recommendationFlag(Object feedUnit, StoryFlag.Accessor accessor) {
        return flagValue(RecommendationLabel.FLAG, feedUnit, accessor);
    }

    /** What a story flag reads for the debug line: true, false, or none when there's nothing to read. */
    private static String flagValue(StoryFlag flag, Object feedUnit, StoryFlag.Accessor accessor) {
        StoryFlag.Outcome outcome = flag.read(feedUnit, accessor);
        if (outcome == StoryFlag.Outcome.FLAGGED) return "true";
        if (outcome == StoryFlag.Outcome.NOT_FLAGGED) return "false";
        return "none";
    }

    /** Which Hook status row the suggested unit classes were last reported into. */
    private static volatile long reportedGeneration = -1;

    /**
     * The suggested unit classes this build carries, reported once per Hook status row: again
     * after a diagnostic clear, which empties the row this was written into. One class missing is
     * Facebook dropping a unit; all of them missing is a moved model package, and the rule then
     * hides nothing at all.
     */
    private static void reportSuggestedClasses() {
        long generation = HookStatus.generation();
        if (reportedGeneration == generation) return;
        reportedGeneration = generation;
        Class<?>[] found = suggestedClasses();
        for (Class<?> type : found) HookStatus.bound(FamilyNames.SUGGESTED_POSTS, type.getSimpleName());
        if (found.length == 0) {
            HookStatus.missingMember(FamilyNames.SUGGESTED_POSTS, "class", "com.facebook.graphql.model",
                    "any suggested feed unit");
        }
    }

    /**
     * Whether a story category is one the switches hide. The category arrives as the enum
     * constant itself: the obfuscator renames the enum's fields on every release, but
     * {@link Enum#name()} answers the literal the constant was built with.
     */
    static boolean hiddenCategory(Object category) {
        if (!(category instanceof Enum)) return false;
        String name = ((Enum<?>) category).name();
        if (SPONSORED.equals(name)) return withFriendsOnly(Settings.HIDE_SPONSORED_POSTS);
        if (PROMOTION.equals(name)) return withFriendsOnly(Settings.HIDE_PROMOTED_POSTS);
        return false;
    }

    /** Whether a story category is one Facebook files the feed's rows of reels under. */
    static boolean isReelsCategory(String categoryName) {
        if (categoryName == null) return false;
        for (String reels : REELS_CATEGORIES) {
            if (reels.equals(categoryName)) return true;
        }
        return false;
    }

    /** The category's name when it's one of {@link #SUGGESTED_CATEGORIES}, otherwise null. */
    static String suggestedCategory(String categoryName) {
        if (categoryName == null) return null;
        for (String suggested : SUGGESTED_CATEGORIES) {
            if (suggested.equals(categoryName)) return suggested;
        }
        return null;
    }

    /** Whether the feed unit is one of the injected suggestion or upsell units. */
    static boolean isSuggested(Object feedUnit) {
        return suggestedUnitName(feedUnit) != null;
    }

    /** The kept name of the suggestion or upsell unit this feed unit is, or null for any other. */
    static String suggestedUnitName(Object feedUnit) {
        if (feedUnit == null) return null;
        for (Class<?> type : suggestedClasses()) {
            if (type.isInstance(feedUnit)) return type.getSimpleName();
        }
        return null;
    }

    /**
     * What a feed unit class offers for reading its type name: its public {@code getTypeName()},
     * or null when it has none, and its public {@code isValidGraphServicesJNIModel()}, or null.
     */
    private static final class TypeNameReader {
        final java.lang.reflect.Method typeName;
        final java.lang.reflect.Method valid;

        TypeNameReader(java.lang.reflect.Method typeName, java.lang.reflect.Method valid) {
            this.typeName = typeName;
            this.valid = valid;
        }

        static TypeNameReader of(Class<?> type) {
            return new TypeNameReader(publicMethod(type, "getTypeName", String.class),
                    publicMethod(type, "isValidGraphServicesJNIModel", boolean.class));
        }

        private static java.lang.reflect.Method publicMethod(Class<?> type, String name, Class<?> returns) {
            try {
                java.lang.reflect.Method found = type.getMethod(name);
                return found.getReturnType() == returns ? found : null;
            } catch (NoSuchMethodException none) {
                return null;
            }
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, TypeNameReader> TYPE_NAME_READERS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The GraphQL type name a feed unit answers, or null when it has no {@code getTypeName()}, its
     * native tree is gone, or the call fails. Facebook's generated models all keep that method's
     * name, whatever their own class is renamed to, and it answers a literal. A unit this can't read
     * is kept: the rules built on it fail open.
     *
     * <p>A model whose tree Facebook already released answers {@code getTypeName()} from native code
     * with nothing behind it, and no try block catches what that does, so a unit that says its tree
     * isn't valid is never asked.
     *
     * <p>Public because the chips under a reel are generated models too, and the Reels clean-up
     * reads their type the same way.
     */
    public static String typeName(Object feedUnit) {
        if (feedUnit == null) return null;
        try {
            TypeNameReader reader = TYPE_NAME_READERS.computeIfAbsent(feedUnit.getClass(), TypeNameReader::of);
            if (reader.typeName == null) return null;
            if (reader.valid != null && !Boolean.TRUE.equals(reader.valid.invoke(feedUnit))) return null;
            Object name = reader.typeName.invoke(feedUnit);
            return name instanceof String ? (String) name : null;
        } catch (Throwable failure) {
            return null;
        }
    }

    /**
     * Whether a model says its native tree is gone, through the same
     * {@code isValidGraphServicesJNIModel()} {@link #typeName} asks. A model that has no such method
     * can't say, and counts as still there; one whose answer can't be read counts as gone.
     */
    static boolean released(Object model) {
        try {
            TypeNameReader reader = TYPE_NAME_READERS.computeIfAbsent(model.getClass(), TypeNameReader::of);
            return reader.valid != null && !Boolean.TRUE.equals(reader.valid.invoke(model));
        } catch (Throwable failure) {
            return true;
        }
    }

    private static Class<?>[] suggestedClasses() {
        Class<?>[] found = suggestedClasses;
        if (found != null) return found;
        java.util.List<Class<?>> classes = new java.util.ArrayList<>(SUGGESTED_UNITS.length);
        ClassLoader loader = FeedFilter.class.getClassLoader();
        for (String name : SUGGESTED_UNITS) {
            try {
                classes.add(Class.forName(name, false, loader));
            } catch (Throwable missing) {
                // This build has no such unit. Nothing to hide for it.
            }
        }
        found = classes.toArray(new Class<?>[0]);
        suggestedClasses = found;
        if (found.length == 0) {
            Logger.printInfo(() -> "Feed filter: none of the suggested unit classes is in this build");
        }
        return found;
    }

    /**
     * Injection point, the item count of a Stories tray adapter: the patch gives the classic and
     * the unified tray adapter a getItemCount() that asks here with Facebook's own count. It answers
     * 0 while the tray is hidden, which leaves the tray built but out of the feed, and
     * {@code count} otherwise. The composer row's adapter asks here too, under its own switch.
     *
     * <p>Facebook's feed adapter reads its children's counts again whenever one of them changes,
     * and tells the list only what that child said changed. So an adapter's answer changes only
     * through its own notifyDataSetChanged, which has the feed rebuild its rows: a count that finds
     * the switch changed keeps the answer it gave and posts the change to the main thread
     * ({@link #flipTray}). The feed reads the counts on a pull to refresh, so the switch shows by
     * then, with no restart. The adapters themselves are built once per feed view (the
     * built-once fixture test), which is why the switch isn't where they're built.
     *
     * <p>Until the settings are ready, and while Hushfacebook is paused, the tray is shown.
     *
     * @param kind {@link #LEGACY_TRAY}, {@link #UNIFIED_TRAY} or {@link #HOME_COMPOSER}, the adapter
     *             the patch hooked.
     */
    public static int storiesTrayCount(Object adapter, int kind, int count) {
        try {
            Boolean hidden = TRAY_HIDDEN.get(adapter);
            if (hidden == null) {
                hidden = hideStoriesTray(kind);
                TRAY_HIDDEN.put(adapter, hidden);
            } else if (hidden != trayHidden(kind) && !TRAY_STUCK.containsKey(adapter)
                    && TRAY_PENDING.put(adapter, Boolean.TRUE) == null) {
                Utils.runOnMainThread(() -> flipTray(adapter, kind));
            }
            return hidden ? 0 : count;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORIES_TRAY, "stories tray count", failure);
            Logger.printException(() -> "Stories tray: could not answer its count", failure);
            return count;
        }
    }

    /** Each tray adapter's answer: hidden or not. Weak, so a feed view that goes takes its adapters. */
    private static final Map<Object, Boolean> TRAY_HIDDEN = Collections.synchronizedMap(new WeakHashMap<>());

    /** Tray adapters with a change posted and not yet made. */
    private static final Map<Object, Boolean> TRAY_PENDING = Collections.synchronizedMap(new WeakHashMap<>());

    /** Tray adapters whose notifyDataSetChanged failed: they keep their answer until Facebook restarts. */
    private static final Map<Object, Boolean> TRAY_STUCK = Collections.synchronizedMap(new WeakHashMap<>());

    private static boolean trayHidden(int kind) {
        if (!Utils.settingsReady()) return false;
        return (kind == HOME_COMPOSER ? Settings.HIDE_HOME_COMPOSER : Settings.HIDE_TOP_STORIES_TRAY).get();
    }

    /**
     * On the main thread, after the count that found the switch changed: the adapter takes what the
     * switch says now, and its own notifyDataSetChanged has the feed read every count again and
     * rebuild its rows, the change the list is built to take. Should that call fail, the adapter
     * keeps the answer the feed has, and the switch waits for a restart.
     */
    static void flipTray(Object adapter, int kind) {
        TRAY_PENDING.remove(adapter);
        Boolean was = TRAY_HIDDEN.get(adapter);
        if (was == null || was == trayHidden(kind)) return;
        boolean hide = hideStoriesTray(kind);
        TRAY_HIDDEN.put(adapter, hide);
        try {
            adapter.getClass().getMethod("notifyDataSetChanged").invoke(adapter);
        } catch (Throwable failure) {
            TRAY_HIDDEN.put(adapter, was);
            TRAY_STUCK.put(adapter, Boolean.TRUE);
            Throwable cause = failure instanceof InvocationTargetException ? failure.getCause() : failure;
            HookStatus.threw(FamilyNames.STORIES_TRAY, "stories tray recount", cause);
            Logger.printException(() -> "Stories tray: the feed couldn't be told, so the switch waits for a restart", cause);
        }
    }

    static void forgetTraysForTests() {
        TRAY_HIDDEN.clear();
        TRAY_PENDING.clear();
        TRAY_STUCK.clear();
    }

    /**
     * Whether a tray adapter is hidden, counted in the report: when an adapter first gives its
     * count, and each time its answer changes. The report counts each as a list of one, under the
     * adapter's kind, and a hidden one as removed.
     *
     * @param adapter {@link #LEGACY_TRAY}, {@link #UNIFIED_TRAY} or {@link #HOME_COMPOSER}.
     */
    static boolean hideStoriesTray(int adapter) {
        try {
            HookStatus.invoked(FamilyNames.STORIES_TRAY);
            String kind = adapter == HOME_COMPOSER ? "composer" : adapter == UNIFIED_TRAY ? "unified" : "legacy";
            String route = adapter == HOME_COMPOSER ? COMPOSER_ROUTE : TRAY_ROUTE;
            FeedFilterCounters.sawList(route, 1);
            FeedFilterCounters.sawKind(route, kind);
            boolean hide = trayHidden(adapter);
            if (hide) FeedFilterCounters.removed(route, 1, kind + " adapter hidden");
            logTrayOnce(adapter, kind, hide);
            return hide;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORIES_TRAY, "stories tray adapter", failure);
            Logger.printException(() -> "Stories tray: could not read its switch", failure);
            return false;
        }
    }

    /** One bit per adapter and decision that has had its debug line, so each is logged once. */
    static final AtomicInteger TRAY_LOGGED = new AtomicInteger();

    /**
     * A debug line the first time each adapter is hidden or shown: which tray Facebook builds on
     * this phone, and what the switch did to it. Only once the settings can be read and debug
     * logging is on, so turning logging on later still gets the line.
     */
    private static void logTrayOnce(int adapter, String kind, boolean hide) {
        if (!Utils.settingsReady() || !BaseSettings.DEBUG.get()) return;
        int bit = 1 << (adapter * 2 + (hide ? 1 : 0));
        if ((TRAY_LOGGED.getAndUpdate(logged -> logged | bit) & bit) != 0) return;
        String what = adapter == HOME_COMPOSER ? "Composer row" : "Stories tray";
        Logger.printDebug(() -> what + ": " + (hide ? "hid" : "kept") + " the " + kind + " adapter");
    }

    /**
     * Injection point, at the start of the pre-EOF injector: the method that builds a "Reels" row of
     * its own shortly before the feed you follow ends and adds it at the tail of the feed. It never
     * goes through {@code addNewEdgeToCollection}, so the edge guard can't see that row. True makes
     * the method return before it builds anything, which is what it does when Facebook's own gate
     * turns it away.
     *
     * <p>Every call is counted on the reels route, and a skipped one as a removal. Until the settings
     * are ready, and while Hushfacebook is paused, it answers false and Facebook builds the row.
     */
    public static boolean hidePreEofReels() {
        try {
            HookStatus.invoked(FamilyNames.FEED_REELS);
            FeedFilterCounters.sawList(REELS_ROUTE, 1);
            FeedFilterCounters.sawKind(REELS_ROUTE, PRE_EOF_UNIT);
            boolean hide = Utils.settingsReady() && withFriendsOnly(Settings.HIDE_FEED_REELS);
            if (hide) FeedFilterCounters.removed(REELS_ROUTE, 1, PRE_EOF_UNIT);
            logPreEofOnce(hide);
            return hide;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_REELS, "pre-EOF reels injector", failure);
            Logger.printException(() -> "Reels in feed: could not read its switch", failure);
            return false;
        }
    }

    /** One bit per decision that has had its debug line, so each is logged once. */
    static final AtomicInteger PRE_EOF_LOGGED = new AtomicInteger();

    /**
     * A debug line the first time the pre-EOF row is skipped or kept, which says Facebook asks for
     * that row on this account at all. Only once the settings can be read and debug logging is on,
     * so turning logging on later still gets the line.
     */
    private static void logPreEofOnce(boolean hide) {
        if (!Utils.settingsReady() || !BaseSettings.DEBUG.get()) return;
        int bit = hide ? 2 : 1;
        if ((PRE_EOF_LOGGED.getAndUpdate(logged -> logged | bit) & bit) != 0) return;
        Logger.printDebug(() -> "Reels in feed: " + (hide ? "skipped" : "kept") + " the pre-EOF Reels unit");
    }

    /**
     * Every edge Facebook swaps into the feed in another's place, with its category as the kind and
     * each one kept out as a removal, so a report says whether swaps happen on this account at all.
     */
    static final String SWAP_ROUTE = "Feed edge swaps";
    /** What each swap counts under on the sponsored patch's Hook status line. */
    static final String SWAP_KEPT = "edge swap kept";
    static final String SWAP_SKIPPED = "edge swap skipped";

    /**
     * Injection point, in the runnable FeedUnitCollectionManager posts when a feed data loader swaps
     * one edge for another, right after it reads the incoming edge and before it touches the feed.
     * Whether that edge stays out. True makes the runnable return there, so the old edge keeps its
     * place and nothing about the swap is logged as sent.
     *
     * <p>A swap replaces an edge in the feed collection without passing
     * {@code addNewEdgeToCollection}, and the ad hot-swaps Facebook runs go this way, each asking
     * for a SPONSORED edge. So the edge gets the funnel guard's own verdict: every switch, Pause and
     * the early prefetch rule apply as they do there, and it counts as a news feed post too. Never
     * throws: false leaves the swap to Facebook.
     */
    public static boolean hideSwappedEdge(Object category, Object feedUnit) {
        return swapped(category, SettingsStatus.sponsoredPosts(), withPatches(category, feedUnit));
    }

    /** The swap guard with the sponsored and suggested patch-time flags passed in, and no GenAI rule. */
    static boolean hideSwappedEdge(Object category, Object feedUnit, boolean sponsoredPatched, boolean suggestedPatched) {
        return swapped(category, sponsoredPatched, hideEdge(category, feedUnit, sponsoredPatched, suggestedPatched));
    }

    /** Counts a swap the guard judged, on its route and on the sponsored patch's line, and answers [hide]. */
    private static boolean swapped(Object category, boolean sponsoredPatched, boolean hide) {
        try {
            String kind = category instanceof Enum ? ((Enum<?>) category).name() : null;
            FeedFilterCounters.sawList(SWAP_ROUTE, 1);
            FeedFilterCounters.sawKind(SWAP_ROUTE, kind);
            if (hide) FeedFilterCounters.removed(SWAP_ROUTE, 1, kind == null ? "swap skipped" : kind + " swap skipped");
            if (sponsoredPatched) HookStatus.counted(FamilyNames.SPONSORED_POSTS, hide ? SWAP_SKIPPED : SWAP_KEPT);
        } catch (Throwable failure) {
            Logger.printException(() -> "Feed filter: could not count an edge swap", failure);
        }
        return hide;
    }

    /** Injection point. Whether the story viewer's ad bucket sources contribute nothing. */
    public static boolean hideSponsoredStories() {
        try {
            HookStatus.invoked(FamilyNames.SPONSORED_STORIES);
            FeedFilterCounters.sawList(STORY_ROUTE, 1);
            boolean hide = Utils.settingsReady() && Settings.HIDE_SPONSORED_STORIES.get();
            if (hide) FeedFilterCounters.removed(STORY_ROUTE, 1, "ad buckets skipped");
            return hide;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SPONSORED_STORIES, "story ad sources", failure);
            Logger.printException(() -> "Story filter: could not read its switch", failure);
            return false;
        }
    }
}
