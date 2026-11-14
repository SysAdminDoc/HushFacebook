/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.morphe.extension.facebook.settings;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;

import app.morphe.extension.facebook.comments.CommentOrder;
import app.morphe.extension.facebook.comments.CommentsHeight;
import app.morphe.extension.facebook.download.DownloadQuality;
import app.morphe.extension.facebook.download.FileNameTemplate;
import app.morphe.extension.facebook.download.SaveFolder;
import app.morphe.extension.facebook.download.SaveTo;
import app.morphe.extension.facebook.download.SendLink;
import app.morphe.extension.facebook.feed.SeenPosts;
import app.morphe.extension.facebook.media.PlaybackQuality;
import app.morphe.extension.facebook.media.RecentlyWatched;
import app.morphe.extension.facebook.media.SurfaceQuality;
import app.morphe.extension.facebook.reels.BurnInMode;
import app.morphe.extension.facebook.reels.BurnInWait;
import app.morphe.extension.facebook.reels.FeedVideoControls;
import app.morphe.extension.facebook.reels.GrayOver;
import app.morphe.extension.facebook.reels.HoldReaction;
import app.morphe.extension.facebook.reels.ReelDateStyle;
import app.morphe.extension.facebook.reels.ReelsOrder;
import app.morphe.extension.facebook.misc.AppLock;
import app.morphe.extension.facebook.misc.BreakTimer;
import app.morphe.extension.facebook.misc.OpeningPause;
import app.morphe.extension.facebook.misc.TextSize;
import app.morphe.extension.facebook.navigation.FeedsSubtab;
import app.morphe.extension.facebook.feed.ReactionCeiling;
import app.morphe.extension.facebook.theme.AccentColor;
import app.morphe.extension.facebook.navigation.StartTab;
import app.morphe.extension.facebook.notifications.QuietHour;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.EnumSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.settings.preference.CrashFile;

/**
 * The switches behind the hooks that ask before they act.
 *
 * <p>A switch's default is the second argument of its {@link BooleanSetting}. Every patch that can
 * leave Facebook as it ships is in Morphe Manager's default selection, so a patch that joined it
 * from the opt-in list starts with its switches off, and the switch is how you turn it on without
 * patching a second time. While Hushfacebook is paused, safe mode included
 * ({@link app.morphe.extension.shared.settings.HushfacebookPause}), a switch answers off unless
 * {@link app.morphe.extension.shared.settings.Setting#keepWhenPaused} marks it, and the hook behind
 * it takes Facebook's own path.
 */
@SuppressWarnings("unused")
public class Settings extends BaseSettings {
    static {
        StoriesSetting.migrate();
        CommentsHeightSetting.migrate();
        RecentlyWatched.keepOlderListAsItWas();
    }

    /** Feed edges Facebook files under the SPONSORED story category. */
    public static final BooleanSetting HIDE_SPONSORED_POSTS =
            new BooleanSetting("hushfacebook_hide_sponsored_posts", TRUE);

    /** Feed edges filed under PROMOTION, which FroggoMorphePatches also drops. */
    public static final BooleanSetting HIDE_PROMOTED_POSTS =
            new BooleanSetting("hushfacebook_hide_promoted_posts", TRUE);

    /** "Pages you may like", Facebook's own upsell units and the in-feed surveys. */
    public static final BooleanSetting HIDE_SUGGESTED_POSTS =
            new BooleanSetting("hushfacebook_hide_suggested_posts", TRUE);

    /**
     * Posts Facebook recommends from people, pages and groups you don't follow, which its own
     * recommendation flag marks: the one Facebook's "hide suggested posts" filter reads.
     */
    public static final BooleanSetting HIDE_SUGGESTED_FOR_YOU =
            new BooleanSetting("hushfacebook_hide_suggested_for_you", TRUE);

    /**
     * The "People you may know" row in the feed, found by its GraphQL type name, the carousel on
     * your own profile, found by the name its section gives itself, and, when Hide suggested
     * stories is in too, the People you may know cards in the Stories tray, found by their bucket
     * type (PYMK_STORY or PYMK_PROFILE_FORWARD_STORY).
     */
    public static final BooleanSetting HIDE_PEOPLE_YOU_MAY_KNOW =
            new BooleanSetting("hushfacebook_hide_people_you_may_know", TRUE);

    /**
     * The row of groups to join that Facebook puts between posts, found by its GraphQL type name,
     * and, when Clean up Reels or Hide reel interest prompts is in too, the "Suggested for you"
     * groups card between reels, found by its mid-card type (GYSJ_MIDCARD, #441). Posts from groups
     * you're in are stories of their own and stay.
     */
    public static final BooleanSetting HIDE_SUGGESTED_GROUPS =
            new BooleanSetting("hushfacebook_hide_suggested_groups", TRUE);

    /**
     * Posts from groups you aren't in, such as a friend's post in a public group, found by the join
     * state Facebook's own Join button reads off the group the story was posted to (#195), or off
     * the group of the post it wraps when a friend commented on or shared a group post. Posts from
     * groups you're in stay. Off until picked.
     */
    public static final BooleanSetting HIDE_GROUP_POSTS_NOT_JOINED =
            new BooleanSetting("hushfacebook_hide_group_posts_not_joined", FALSE);

    /**
     * Every post from a group, your own groups' too (#409), found the way
     * {@link #HIDE_GROUP_POSTS_NOT_JOINED} finds a group, whatever its join state. A group post a
     * friend commented on or shared goes too. The Groups tab keeps its own feed. Off until picked.
     */
    public static final BooleanSetting HIDE_GROUP_POSTS =
            new BooleanSetting("hushfacebook_hide_group_posts", FALSE);

    /**
     * The row of Stories from people you aren't connected to that Facebook puts between posts,
     * "Stories you might like", found by the flag Facebook's own Discover unit reads for it. A row
     * of your friends' Stories and the Stories tray stay.
     */
    public static final BooleanSetting HIDE_STORIES_YOU_MIGHT_LIKE =
            new BooleanSetting("hushfacebook_hide_stories_you_might_like", TRUE);

    /**
     * Memories Facebook puts between posts, "On this day" and friendship anniversaries, found by
     * their GraphQL type names. Off until picked, like the other kinds below.
     */
    public static final BooleanSetting HIDE_FEED_MEMORIES =
            new BooleanSetting("hushfacebook_hide_feed_memories", FALSE);

    /** The row of friend requests between posts, found by its GraphQL type name. */
    public static final BooleanSetting HIDE_FEED_FRIEND_REQUESTS =
            new BooleanSetting("hushfacebook_hide_feed_friend_requests", FALSE);

    /** The card of where your friends are, between posts, found by its GraphQL type name. */
    public static final BooleanSetting HIDE_FRIENDS_LOCATIONS =
            new BooleanSetting("hushfacebook_hide_friends_locations", FALSE);

    /**
     * The "More from your friends" list under a post opened from a notification, ads among it (#163).
     * It's a server-built footer asked for with the post, never a feed unit, so the query is told not
     * to ask for it ({@link app.morphe.extension.facebook.feed.NotificationFooter}).
     */
    public static final BooleanSetting HIDE_NOTIFICATION_POST_FOOTER =
            new BooleanSetting("hushfacebook_hide_notification_post_footer", FALSE);

    /**
     * The Related posts list under a post's buttons, with Meta AI's titles (#231). It's a plugin of the
     * socket under the buttons, never a feed unit, so that socket's check gets a no for it
     * ({@link app.morphe.extension.facebook.feed.RelatedPosts}). Asked as each post is drawn.
     */
    public static final BooleanSetting HIDE_RELATED_POSTS =
            new BooleanSetting("hushfacebook_hide_related_posts", FALSE);

    /**
     * The "Want to see more posts from friends? Find friends" card where the feed runs out of friends'
     * posts (#187). It's a part of the end of feed section, never a feed unit, so the section is told
     * it has no card ({@link app.morphe.extension.facebook.feed.FindFriendsCard}).
     */
    public static final BooleanSetting HIDE_FIND_FRIENDS_CARD =
            new BooleanSetting("hushfacebook_hide_find_friends_card", FALSE);

    /**
     * The row of stories at the top of the feed. The feed's adapter list builds it as an adapter of
     * its own, and the patch has both tray adapters count no rows while this is on.
     */
    public static final BooleanSetting HIDE_TOP_STORIES_TRAY =
            new StoriesSetting(StoriesSetting.TOP_KEY);

    /** Rows, large tiles and inline viewers of Stories between feed posts. */
    public static final BooleanSetting HIDE_STORIES_BETWEEN_POSTS =
            new StoriesSetting(StoriesSetting.BETWEEN_KEY);

    /**
     * The "What's on your mind?" composer row at the top of Home. Like the tray, it's an adapter of
     * the feed's own, and the patch has it count no rows while this is on. Off until picked: the
     * row is how most people start a post.
     */
    public static final BooleanSetting HIDE_HOME_COMPOSER =
            new BooleanSetting("hushfacebook_hide_home_composer", FALSE);

    /**
     * The feed's rows of reels: the "Reels" carousels between posts and the reels Facebook adds where
     * the feed you follow ends. Each is an edge of its own, filed under a reels story category.
     */
    public static final BooleanSetting HIDE_FEED_REELS =
            new BooleanSetting("hushfacebook_hide_feed_reels", FALSE);

    /** Keep the current feed when returning to Facebook within ten minutes. */
    public static final BooleanSetting BLOCK_RETURN_REFRESH =
            new BooleanSetting("hushfacebook_block_return_refresh", FALSE);

    /**
     * With the switch above, keep the feed however long Facebook stayed in the background (#23). It's
     * greyed out while that switch is off, and keeps its saved value (#130).
     */
    public static final BooleanSetting RETURN_REFRESH_NO_LIMIT =
            new BooleanSetting("hushfacebook_return_refresh_no_limit", FALSE, Setting.parent(BLOCK_RETURN_REFRESH));

    /**
     * Back on a scrolled-down Home leaves the feed where it is instead of scrolling to the top and
     * refreshing, and a second Back within two seconds does that (#247). Independent of the switches above.
     */
    public static final BooleanSetting BACK_KEEPS_HOME =
            new BooleanSetting("hushfacebook_back_keeps_home", FALSE);

    /** Back keeps Home without the toast saying a second Back goes to the top (#334). */
    public static final BooleanSetting HIDE_BACK_REMINDER =
            new BooleanSetting("hushfacebook_hide_back_reminder", FALSE, Setting.parent(BACK_KEEPS_HOME));

    /**
     * Feed posts Facebook's own detection marked as made with AI. Off until one AI-labeled and one
     * ordinary post have been recorded on a signed-in feed and the rule told them apart.
     */
    public static final BooleanSetting HIDE_AI_DETECTED_POSTS =
            new BooleanSetting("hushfacebook_hide_ai_detected_posts", FALSE);

    /**
     * Feed posts carrying Facebook's AI label for any reason: the ones its creator labelled as AI,
     * which {@link #HIDE_AI_DETECTED_POSTS} keeps, and the detected ones too. Off, like the switch
     * above, until a labelled post has been seen going on a signed-in feed.
     */
    public static final BooleanSetting HIDE_AI_LABELLED_POSTS =
            new BooleanSetting("hushfacebook_hide_ai_labelled_posts", FALSE);

    /**
     * The Meta AI cards Facebook adds to the feed between posts, known by their GraphQL type name
     * alone. On: the cards are Facebook's own promotion, not anyone's post, and the type can't match
     * a post.
     */
    public static final BooleanSetting HIDE_META_AI_FEED_UNITS =
            new BooleanSetting("hushfacebook_hide_meta_ai_feed_units", TRUE);

    /**
     * Feed posts whose attachment carries one of Meta's AI characters, known by the attachment
     * style Facebook draws it with. Off: these are someone's posts, a creator's or a character's
     * account's, and no feed here has been served one to show the rule tells them apart.
     */
    public static final BooleanSetting HIDE_AI_CHARACTER_POSTS =
            new BooleanSetting("hushfacebook_hide_ai_character_posts", FALSE);

    /**
     * Reels and Watch videos Facebook's own detection marked as made with AI, read off the
     * attribution the Reels viewer draws its AI label from. Off until it has been checked on a
     * signed-in Reels feed, like the feed switch above.
     */
    public static final BooleanSetting HIDE_AI_DETECTED_REELS =
            new BooleanSetting("hushfacebook_hide_ai_detected_reels", FALSE);

    /**
     * Feed posts whose own words hold a phrase from {@link #HIDDEN_WORDS} and none from
     * {@link #KEPT_WORDS} ({@link app.morphe.extension.facebook.feed.PostWords}). Off by default,
     * and with the hide list empty it reads nothing of any post.
     */
    public static final BooleanSetting HIDE_POSTS_WITH_WORDS =
            new BooleanSetting("hushfacebook_hide_posts_with_words", FALSE);

    /** Applies word-run boundaries to both lists. Existing installs keep substring matching. */
    public static final BooleanSetting POST_WORDS_WHOLE_WORDS =
            new BooleanSetting("hushfacebook_post_words_whole_words", FALSE);

    /**
     * The words and phrases that hide a post while {@link #HIDE_POSTS_WITH_WORDS} is on, one per
     * line, bounded wherever it's read. It isn't a switch, and a paused Facebook reads it as empty.
     */
    public static final StringSetting HIDDEN_WORDS =
            new StringSetting("hushfacebook_hidden_words", "");

    /**
     * The words and phrases that keep a post whatever else it says, one per line, bounded the same
     * way. It isn't a switch either.
     */
    public static final StringSetting KEPT_WORDS =
            new StringSetting("hushfacebook_kept_words", "");

    /**
     * Feed posts written by a person or Page in {@link #HIDDEN_SOURCES}, or linking to a site in it,
     * and shares of them ({@link app.morphe.extension.facebook.feed.PostSources}). Off by default,
     * and with the list empty it reads nothing of any post.
     */
    public static final BooleanSetting HIDE_POSTS_FROM_SOURCES =
            new BooleanSetting("hushfacebook_hide_posts_from_sources", FALSE);

    /**
     * The people, Pages and sites whose posts {@link #HIDE_POSTS_FROM_SOURCES} hides, one per line:
     * a name, a profile or Page id, or a site's domain, bounded wherever it's read. It isn't a
     * switch, and a paused Facebook reads it as empty.
     */
    public static final StringSetting HIDDEN_SOURCES =
            new StringSetting("hushfacebook_hidden_sources", "");

    /**
     * The feed unit type names picked in Feed types to hide, as GraphQL names them
     * ({@code FriendsLocationsFeedUnit}), separated by commas. Empty, no unit is hidden by name.
     * A paused Facebook reads it as empty.
     */
    public static final StringSetting HIDDEN_FEED_TYPES =
            new StringSetting("hushfacebook_hidden_feed_types", "");

    /**
     * Feed posts whose attachment Facebook draws as a photo or an album, and shares of them
     * ({@link app.morphe.extension.facebook.feed.PostTypes}). Off by default, like the three below:
     * none has been checked on a signed-in feed yet.
     */
    public static final BooleanSetting HIDE_PHOTO_POSTS =
            new BooleanSetting("hushfacebook_hide_photo_posts", FALSE);

    /** Feed posts whose attachment Facebook draws as a video, and shares of them. */
    public static final BooleanSetting HIDE_VIDEO_POSTS =
            new BooleanSetting("hushfacebook_hide_video_posts", FALSE);

    /** Feed posts whose attachment Facebook draws as a shared link, and shares of them. */
    public static final BooleanSetting HIDE_LINK_POSTS =
            new BooleanSetting("hushfacebook_hide_link_posts", FALSE);

    /** Feed posts written on a colored background, which Facebook draws as large formatted text. */
    public static final BooleanSetting HIDE_BACKGROUND_POSTS =
            new BooleanSetting("hushfacebook_hide_background_posts", FALSE);

    /**
     * Feed posts that share another post, with a caption or without: a story whose attached story
     * is a story ({@link app.morphe.extension.facebook.feed.PostText#shareKind}). Off by default:
     * no served repost has been checked on a signed-in feed yet.
     */
    public static final BooleanSetting HIDE_REPOSTS =
            new BooleanSetting("hushfacebook_hide_reposts", FALSE);

    /**
     * Feed posts every author of which is a Page (#262), read from the story's own authors
     * ({@link app.morphe.extension.facebook.feed.PostSources#authorKind}). Friends' posts, group
     * posts and a friend's share of a Page's post stay. Off by default.
     */
    public static final BooleanSetting HIDE_PAGE_POSTS =
            new BooleanSetting("hushfacebook_hide_page_posts", FALSE);

    /**
     * Only friends' posts (#406): the feed keeps a post when one of its own authors is a friend
     * ({@link app.morphe.extension.facebook.feed.PostSources#friendKind}). It also turns on the ads,
     * suggested posts, People you may know, suggested groups, group posts, Pages and feed reels
     * switches while it's on, without saving them. A post whose authors can't be read stays. Off
     * until picked.
     */
    public static final BooleanSetting ONLY_FRIENDS_POSTS =
            new BooleanSetting("hushfacebook_only_friends_posts", FALSE);

    /**
     * Feed posts whose words the phone is sure are in a language not on {@link #KEPT_LANGUAGES}
     * (#382, {@link app.morphe.extension.facebook.feed.PostLanguages}). A post with no words, or
     * none the phone is sure of, stays. Off by default.
     */
    public static final BooleanSetting HIDE_OTHER_LANGUAGES =
            new BooleanSetting("hushfacebook_hide_other_languages", FALSE);

    /**
     * The languages {@link #HIDE_OTHER_LANGUAGES} keeps, as Google Translate codes separated by
     * commas, like {@link #TRANSLATE_LEAVE_ALONE}. Empty keeps the phone's language. Not a switch.
     */
    public static final StringSetting KEPT_LANGUAGES =
            new StringSetting("hushfacebook_kept_languages", "");

    /**
     * The reaction count above which feed posts are hidden, read from the feed unit's own feedback
     * ({@link app.morphe.extension.facebook.feed.PostReactions}). Off hides nothing. Not a switch:
     * a list picks the count, and a paused Facebook reads Off.
     */
    public static final EnumSetting<ReactionCeiling> HIDE_POSTS_OVER_REACTIONS =
            new EnumSetting<>("hushfacebook_hide_posts_over_reactions", ReactionCeiling.OFF);

    /** The four story bucket sources that splice ad cards into the story viewer. */
    public static final BooleanSetting HIDE_SPONSORED_STORIES =
            new BooleanSetting("hushfacebook_hide_sponsored_stories", TRUE);

    /**
     * The buckets of the Stories tray that Facebook suggests from people and Pages you don't
     * follow: a bucket whose is_story_bucket_suggested flag is true or whose first label is
     * SUGGESTED, the two things the tray's card reads before it says "Suggested". Friends' stories,
     * Pages you follow and your own story stay.
     */
    public static final BooleanSetting HIDE_SUGGESTED_STORIES =
            new BooleanSetting("hushfacebook_hide_suggested_stories", TRUE);

    /**
     * The "Find friends from contacts" card in the Stories tray, which asks to upload the phone's
     * contacts: a bucket whose type is CONTACT_IMPORTER_STORY, the type the tray's card dispatcher
     * draws that card for. Stories and the other cards stay.
     */
    public static final BooleanSetting HIDE_CONTACT_IMPORT_CARD =
            new BooleanSetting("hushfacebook_hide_contact_import_card", TRUE);

    /**
     * The buckets of the Stories tray that hold a Page's stories: their story_bucket_owner is a
     * Page, or their type is PAGE_STORY or SHARED_PAGE_STORY (#313). Friends' stories stay. Off until
     * you turn it on.
     */
    public static final BooleanSetting HIDE_PAGE_STORIES =
            new BooleanSetting("hushfacebook_hide_page_stories", FALSE);

    /**
     * The buckets of the Stories tray you've watched: their is_bucket_seen_by_viewer flag is true,
     * the one that greys the ring (#317). A new story turns it back, so the bucket comes back with
     * it. Off until you turn it on.
     */
    public static final BooleanSetting HIDE_SEEN_STORIES =
            new BooleanSetting("hushfacebook_hide_seen_stories", FALSE);

    /**
     * The other way round from {@link #HIDE_SEEN_STORIES} (#340): the Stories tray keeps only the
     * buckets you've watched, and Hide seen stories waits while this is on. Off until you turn it on.
     */
    public static final BooleanSetting SHOW_ONLY_WATCHED_STORIES =
            new BooleanSetting("hushfacebook_show_only_watched_stories", FALSE);

    /**
     * The Stories tray's buckets whose tile came into view on an earlier visit to Facebook, watched or
     * not (#463). A new story brings the person back. Off until you turn it on.
     */
    public static final BooleanSetting HIDE_PASSED_STORIES =
            new BooleanSetting("hushfacebook_hide_passed_stories", FALSE);

    /**
     * The cards beside Create story in the Stories tray that suggest a story to make, such as
     * "Share music you love": the tray's fetch asks the server to leave out their list,
     * skip_srtt_item_list, as Facebook does for the Video tab's tray. Off until you turn it on.
     */
    public static final BooleanSetting HIDE_STORY_PROMPTS =
            new BooleanSetting("hushfacebook_hide_story_prompts", FALSE);

    /**
     * The Create story tile at the start of Home's Stories tray
     * ({@link app.morphe.extension.facebook.stories.CreateStoryTile}, #445). The stories in the tray
     * stay. Off until you turn it on. A change shows when Facebook next builds the tray.
     */
    public static final BooleanSetting HIDE_CREATE_STORY =
            new BooleanSetting("hushfacebook_hide_create_story", FALSE);

    /** Keep a finished Story visible until the user navigates. */
    public static final BooleanSetting BLOCK_STORY_AUTO_ADVANCE =
            new BooleanSetting("hushfacebook_block_story_auto_advance", FALSE);

    /**
     * Stop Story auto-advance's second switch: a finished story starts again from the beginning
     * through Facebook's own restart, instead of holding its last frame. Off until you turn it on.
     */
    public static final BooleanSetting LOOP_STORIES =
            new BooleanSetting("hushfacebook_loop_stories", FALSE, Setting.parent(BLOCK_STORY_AUTO_ADVANCE));

    /**
     * The batches of viewed story cards the story viewer sends as DirectSeenMutation, which put you
     * on each story's viewer list. Held back, replies and reactions still show you, and a bucket
     * watched to its newest story is marked watched on the phone only (StorySeen, #61). Saved off,
     * the buckets kept for that are forgotten.
     */
    public static final BooleanSetting VIEW_STORIES_ANONYMOUSLY =
            new BooleanSetting("hushfacebook_view_stories_anonymously", FALSE);

    /**
     * View stories anonymously's second switch: an eye button over each story that marks it, so the
     * next report of viewed stories carries the marked ones and nothing else. Off until it's turned on.
     */
    public static final BooleanSetting MARK_STORIES_SEEN =
            new BooleanSetting("hushfacebook_mark_stories_seen", FALSE);

    /**
     * A video posted only to your Story asks the upload server for high quality instead of the
     * standard quality it gets otherwise ({@link
     * app.morphe.extension.facebook.stories.StoryUpload}, #123). Starts off.
     */
    public static final BooleanSetting UPLOAD_STORIES_IN_HD =
            new BooleanSetting("hushfacebook_upload_stories_in_hd", FALSE);

    /**
     * A song added to a photo for your Story gets a clip of at least 30 seconds instead of
     * Facebook's 15 ({@link app.morphe.extension.facebook.stories.StoryMusic}, #156). Starts off.
     * Read each time a song is picked.
     */
    public static final BooleanSetting LONGER_STORY_MUSIC =
            new BooleanSetting("hushfacebook_longer_story_music", FALSE);

    /**
     * Home's stories row drawn as round pictures ({@link app.morphe.extension.facebook.stories.StoriesTrayCircles},
     * #260). Starts off. Read as each tile appears.
     */
    public static final BooleanSetting STORIES_TRAY_CIRCLES =
            new BooleanSetting("hushfacebook_stories_tray_circles", FALSE);

    /** The diameter of those pictures in dp, digits only, blank for the default. Not a switch. */
    public static final StringSetting STORIES_TRAY_SIZE =
            new StringSetting("hushfacebook_stories_tray_size", "");

    /**
     * A tap in the middle of a story shows a play and pause button, like Reels ({@link
     * app.morphe.extension.facebook.stories.StoryTaps}, #380). Starts off. Read on each tap.
     */
    public static final BooleanSetting STORY_PLAY_BUTTON =
            new BooleanSetting("hushfacebook_story_play_button", FALSE);

    /**
     * Stories change only on a double tap, and a single tap pauses ({@link
     * app.morphe.extension.facebook.stories.StoryTaps}, #380). Starts off. Read on each tap.
     */
    public static final BooleanSetting STORY_DOUBLE_TAP =
            new BooleanSetting("hushfacebook_story_double_tap", FALSE);

    /**
     * The song and effect credits leave the line under the poster's name on a story ({@link
     * app.morphe.extension.facebook.stories.StoryViewerCleanup}, #380). Starts off. Read as the line draws.
     */
    public static final BooleanSetting HIDE_STORY_CREDITS =
            new BooleanSetting("hushfacebook_hide_story_credits", FALSE);

    /**
     * The row of suggested replies leaves the bottom of a story ({@link
     * app.morphe.extension.facebook.stories.StoryViewerCleanup}, #380). Starts off. Read as the row draws.
     */
    public static final BooleanSetting HIDE_STORY_REPLY_SUGGESTIONS =
            new BooleanSetting("hushfacebook_hide_story_reply_suggestions", FALSE);

    /**
     * The Subscribe pill leaves the top of a story ({@link
     * app.morphe.extension.facebook.stories.StoryViewerCleanup}, #380). Starts off. Read as the pill draws.
     */
    public static final BooleanSetting HIDE_STORY_SUBSCRIBE =
            new BooleanSetting("hushfacebook_hide_story_subscribe", FALSE);

    /**
     * The "Add to story" card Facebook puts between other people's stories, the plain one and the
     * ones built from photos in the phone's gallery, stays out of the viewer ({@link
     * app.morphe.extension.facebook.stories.StoryViewerCleanup}, #439). Starts off. Read as the
     * viewer lays out its stories.
     */
    public static final BooleanSetting HIDE_STORY_CREATE_CARDS =
            new BooleanSetting("hushfacebook_hide_story_create_cards", FALSE);

    /**
     * A thin bar along the bottom of a story fills as it plays ({@link
     * app.morphe.extension.facebook.stories.StoryProgressBar}, #380). Starts off. Read as the story plays.
     */
    public static final BooleanSetting STORY_PROGRESS_BAR =
            new BooleanSetting("hushfacebook_story_progress_bar", FALSE);

    /**
     * The story and LIVE rings leave the profile pictures at the top of posts, and a tap on the picture
     * opens the profile ({@link app.morphe.extension.facebook.stories.StoryRings}, #397). Starts off.
     * Read as each post's header draws.
     */
    public static final BooleanSetting HIDE_FEED_STORY_RINGS =
            new BooleanSetting("hushfacebook_hide_feed_story_rings", FALSE);

    /** The story ring leaves the profile pictures beside comments (#397). Starts off. Read as each comment draws. */
    public static final BooleanSetting HIDE_COMMENT_STORY_RINGS =
            new BooleanSetting("hushfacebook_hide_comment_story_rings", FALSE);

    /**
     * The story and LIVE rings leave the profile picture on a profile, and the lists and search
     * results that draw it the same way (#397). Starts off. Read as the picture draws.
     */
    public static final BooleanSetting HIDE_PROFILE_STORY_RINGS =
            new BooleanSetting("hushfacebook_hide_profile_story_rings", FALSE);

    /** The two page filters that take server-inlined ads out of Reels and Watch. */
    public static final BooleanSetting HIDE_SPONSORED_REELS =
            new BooleanSetting("hushfacebook_hide_sponsored_reels", TRUE);

    /** The modules of a page of search results that Facebook's search result role marks as ads. */
    public static final BooleanSetting HIDE_SPONSORED_SEARCH_RESULTS =
            new BooleanSetting("hushfacebook_hide_sponsored_search_results", TRUE);

    /** The posts on a profile or Page timeline that carry Facebook's sponsored data. */
    public static final BooleanSetting HIDE_SPONSORED_PROFILE_POSTS =
            new BooleanSetting("hushfacebook_hide_sponsored_profile_posts", TRUE);

    /**
     * The note bubble on a profile picture, left out of the profile's Bloks layout ({@link
     * app.morphe.extension.facebook.profile.ProfileNotes}). Starts off. Read each time Facebook
     * lays the profile out, so a change shows the next time a profile opens.
     */
    public static final BooleanSetting HIDE_PROFILE_NOTES =
            new BooleanSetting("hushfacebook_hide_profile_notes", FALSE);

    /**
     * Friends, followers and following counts of your own profile that you type yourself, shown on this
     * phone only ({@link app.morphe.extension.facebook.profile.LocalProfileCounts}, #280). Starts off.
     * Read each time Facebook lays the profile out, so a change shows the next time a profile opens.
     */
    public static final BooleanSetting LOCAL_PROFILE_COUNTS =
            new BooleanSetting("hushfacebook_local_profile_counts", FALSE);

    /** The count shown for Friends on your own profile: digits only, blank keeps Facebook's own. Not a switch. */
    public static final StringSetting LOCAL_FRIENDS =
            new StringSetting("hushfacebook_local_friends", "");

    /** The count shown for Followers on your own profile: digits only, blank keeps Facebook's own. Not a switch. */
    public static final StringSetting LOCAL_FOLLOWERS =
            new StringSetting("hushfacebook_local_followers", "");

    /** The count shown for Following on your own profile: digits only, blank keeps Facebook's own. Not a switch. */
    public static final StringSetting LOCAL_FOLLOWING =
            new StringSetting("hushfacebook_local_following", "");

    /**
     * Facebook's own Save button in the row under a post, beside Like, Comment and Share (issue
     * #265, {@link app.morphe.extension.facebook.feed.PostSaveButton}). Starts off. Read each time
     * a post's row is built, so a change shows on posts drawn after it.
     */
    public static final BooleanSetting POST_SAVE_BUTTON =
            new BooleanSetting("hushfacebook_post_save_button", FALSE);

    /**
     * A Download button in the row under a post holding a video or photos (issue #151, {@link
     * app.morphe.extension.facebook.download.PostDownloadButton}). It saves through Download feed
     * and Watch videos and Save any photo, so it needs those on. Starts off.
     */
    public static final BooleanSetting POST_DOWNLOAD_BUTTON =
            new BooleanSetting("hushfacebook_post_download_button", FALSE);

    /**
     * Each takes one button out of the row under a post (issue #265, {@link
     * app.morphe.extension.facebook.feed.PostSaveButton}): Like, Comment, Repost (#450), Share or
     * Send. Start off.
     */
    public static final BooleanSetting HIDE_POST_LIKE_BUTTON =
            new BooleanSetting("hushfacebook_hide_post_like_button", FALSE);
    public static final BooleanSetting HIDE_POST_COMMENT_BUTTON =
            new BooleanSetting("hushfacebook_hide_post_comment_button", FALSE);
    public static final BooleanSetting HIDE_POST_REPOST_BUTTON =
            new BooleanSetting("hushfacebook_hide_post_repost_button", FALSE);
    public static final BooleanSetting HIDE_POST_SHARE_BUTTON =
            new BooleanSetting("hushfacebook_hide_post_share_button", FALSE);
    public static final BooleanSetting HIDE_POST_SEND_BUTTON =
            new BooleanSetting("hushfacebook_hide_post_send_button", FALSE);

    /**
     * The ads and boosted listings of Marketplace's feed: its query asks the server to skip them,
     * and its ads-only queries aren't sent.
     */
    public static final BooleanSetting HIDE_SPONSORED_MARKETPLACE_LISTINGS =
            new BooleanSetting("hushfacebook_hide_sponsored_marketplace_listings", TRUE);

    /**
     * Marketplace search reads rules in what's typed: a quoted term a title must hold, a minus term
     * it mustn't, and a place after in: the listing must be in
     * ({@link app.morphe.extension.facebook.ads.MarketplaceSearchSyntax}). Off, a search goes out as
     * typed and no Adv. Filters button shows. Facebook keeps the results of a search it has seen, so a
     * change asks for a restart.
     */
    public static final BooleanSetting MARKETPLACE_ADVANCED_SEARCH =
            new BooleanSetting("hushfacebook_marketplace_advanced_search", FALSE, true);

    /**
     * The Adv. Filters button on Marketplace's search results, which writes the rules
     * ({@link app.morphe.extension.facebook.navigation.MarketplaceFilterButton}). Starts off. Off, typed
     * rules still work. A change asks for a restart.
     */
    public static final BooleanSetting MARKETPLACE_FILTERS_BUTTON =
            new BooleanSetting("hushfacebook_marketplace_filters_button", FALSE, true,
                    Setting.parent(MARKETPLACE_ADVANCED_SEARCH));

    /**
     * A listing's own page says when it was listed, after its place
     * ({@link app.morphe.extension.facebook.ads.MarketplaceListingAge}). Read as each listing page
     * loads, so a change shows on the next one.
     */
    public static final BooleanSetting MARKETPLACE_LISTING_DATE =
            new BooleanSetting("hushfacebook_marketplace_listing_date", TRUE);

    /**
     * A seller's Marketplace page always offers View profile, which opens the seller's regular
     * Facebook profile ({@link app.morphe.extension.facebook.navigation.MarketplaceSellerProfile}).
     * Off or paused, the page asks Facebook's experiment flag again, which shows it to some accounts.
     */
    public static final BooleanSetting SHOW_SELLER_VIEW_PROFILE =
            new BooleanSetting("hushfacebook_show_seller_view_profile", TRUE);

    /**
     * Facebook's safe area for React Native pages counts only what overlaps the page itself, so a
     * Marketplace listing that already sits below the status bar doesn't pad its top by the bar's
     * height again ({@link app.morphe.extension.facebook.navigation.ListingGap}). Off or paused, the
     * pages get the whole window's insets as Facebook hands them out.
     */
    public static final BooleanSetting REMOVE_LISTING_GAP =
            new BooleanSetting("hushfacebook_remove_listing_gap", TRUE);

    /**
     * The ads Instant Games ask Facebook for ({@link app.morphe.extension.facebook.ads.GameAds}): each
     * request is answered with no ad.
     */
    public static final BooleanSetting BLOCK_GAME_ADS =
            new BooleanSetting("hushfacebook_block_game_ads", TRUE);

    /**
     * With {@link #BLOCK_GAME_ADS} on, a rewarded video a game asks for is answered as watched
     * ({@link app.morphe.extension.facebook.ads.GameAds#answer}): no ad loads and the game grants
     * its reward. Other game ads still get none.
     */
    public static final BooleanSetting ANSWER_REWARDED_GAME_ADS =
            new BooleanSetting("hushfacebook_answer_rewarded_game_ads", FALSE);

    /**
     * The product cards of the shop links a creator attaches to a post go: on a reel, under a feed
     * post and floating over the comment box ({@link app.morphe.extension.facebook.ads.AffiliateLinks}).
     * The "Commission eligible" label has its own switch, {@link #HIDE_COMMISSION_LABEL}. A change
     * shows on the reels, posts and comment sheets built after it.
     */
    public static final BooleanSetting HIDE_AFFILIATE_LINKS =
            new BooleanSetting("hushfacebook_hide_affiliate_links", TRUE);

    /** The "Commission eligible" label under a reel's creator goes (#329). Shows on the reels built after a change. */
    public static final BooleanSetting HIDE_COMMISSION_LABEL =
            new BooleanSetting("hushfacebook_hide_commission_label", FALSE);

    /**
     * The chips under a reel that prompt you to make something (Remix, Use template, Add yours,
     * Edits) or promote something (Stars, games, a partner app, a link out of Facebook). A chip of
     * any other type stays.
     */
    public static final BooleanSetting HIDE_REEL_CHIPS =
            new BooleanSetting("hushfacebook_hide_reel_chips", FALSE);

    /**
     * The Follow button in a reel's author row, through Facebook's own check for offering it, and
     * the Following button an author you already follow gets there, through its config for
     * removing that one.
     */
    public static final BooleanSetting HIDE_REEL_FOLLOW_BUTTON =
            new BooleanSetting("hushfacebook_hide_reel_follow_button", FALSE);

    /**
     * The Subscribe pill beside a full-screen video's author, where Facebook offers Subscribe
     * instead of Follow. A Follow pill stays (#291). Subscribe takes effect with one tap and no
     * prompt.
     */
    public static final BooleanSetting HIDE_VIDEO_SUBSCRIBE_BUTTON =
            new BooleanSetting("hushfacebook_hide_video_subscribe_button", FALSE);

    /**
     * The comment Facebook previews under a reel and the bubbles of friends who reacted. Both
     * queries are skipped, so neither is drawn.
     */
    public static final BooleanSetting HIDE_REEL_SOCIAL_FOOTER =
            new BooleanSetting("hushfacebook_hide_reel_social_footer", FALSE);

    /**
     * The "Threads you might like" card between reels, a mid-card Facebook types THREADS_MIDCARD.
     * It comes off each batch of reels as it arrives. Off until it's turned on, since nobody has
     * seen the card go on a signed-in Reels feed yet.
     */
    public static final BooleanSetting HIDE_REEL_THREADS_CARDS =
            new BooleanSetting("hushfacebook_hide_reel_threads_cards", FALSE);

    /**
     * Only reels from accounts you follow on the Reels tab ({@link
     * app.morphe.extension.facebook.reels.FollowedReels}, #349): a reel whose author Facebook's own
     * Follow button would offer to follow comes off each batch as it arrives. Off by default, since
     * the tab then fills slower.
     */
    public static final BooleanSetting FOLLOWED_REELS_ONLY =
            new BooleanSetting("hushfacebook_followed_reels_only", FALSE);

    /**
     * Reels and the videos that open in the same viewer start in Facebook's own Clean mode, the
     * state its three-dot menu's Clean mode item puts one reel in, with the buttons down the side
     * hidden. Facebook's own pinch and menu still bring them back for that reel, and an ad reel's
     * overlay keeps its controls. Off until it's turned on, since nobody has seen it on a phone yet.
     */
    public static final BooleanSetting REEL_CLEAN_MODE =
            new BooleanSetting("hushfacebook_reel_clean_mode", FALSE);

    /**
     * A Clean mode button on a reel's sidebar, under the other buttons (#391). Its tap puts the
     * viewer in Facebook's own Clean mode, which then stays on from reel to reel until Facebook's
     * Clean mode button over a clean reel turns it off. It goes through Clean up Reels' store hooks
     * and Download any reel's sidebar, so a build without either shows no button. Starts off.
     */
    public static final BooleanSetting REEL_CLEAN_BUTTON =
            new BooleanSetting("hushfacebook_reel_clean_button", FALSE);

    /**
     * A reel stops on its last frame instead of starting over, so a tap plays it again and a swipe
     * moves on. Feed videos, stories and every other video play as Facebook has them. Off until
     * it's turned on, since nobody has seen it on a phone yet.
     */
    public static final BooleanSetting PLAY_REELS_ONCE =
            new BooleanSetting("hushfacebook_play_reels_once", FALSE);

    /**
     * Reels and videos keep the sound they were made with instead of a version Facebook dubbed into
     * your language with Meta AI ({@link app.morphe.extension.facebook.reels.OriginalAudio}). The
     * player is told no language was asked for, so it keeps the video's main audio track. Off until
     * it's turned on, since nobody has seen it on a phone yet.
     */
    public static final BooleanSetting ORIGINAL_AUDIO =
            new BooleanSetting("hushfacebook_original_audio", FALSE);

    /**
     * The search pill under a reel's caption, Facebook's "search this" prompt with a suggested
     * query ({@link app.morphe.extension.facebook.reels.ReelDeclutter#hideSearchPill}, #173). The
     * component that draws it isn't built, and the comment sheet gets no prompt for the one over a
     * reel's comments ({@link app.morphe.extension.facebook.reels.ReelDeclutter#commentSearchPrompt},
     * #197). The search bar and send button over a reel opened from a profile stay out of its header
     * too ({@link app.morphe.extension.facebook.reels.ReelDeclutter#searchBarPart}, #201). Off until
     * it's turned on, since the pill depends on a
     * server setting and nobody has seen it go on a signed-in Reels feed yet.
     */
    public static final BooleanSetting HIDE_REEL_SEARCH_PILL =
            new BooleanSetting("hushfacebook_hide_reel_search_pill", FALSE);

    /**
     * Search and the button beside it at the top of a video or reel opened full screen, the Reels
     * profile button over a reel and More over a feed video ({@link
     * app.morphe.extension.facebook.reels.ViewerTopBar}, #311). Back stays, and so does More at the
     * foot of the side buttons, which holds Download. Asked after each layout of the screen.
     */
    public static final BooleanSetting HIDE_VIEWER_TOP_BUTTONS =
            new BooleanSetting("hushfacebook_hide_viewer_top_buttons", FALSE);

    /**
     * The "Reels" title in the header at the top of the Reels tab goes ({@link
     * app.morphe.extension.facebook.reels.ViewerTopBar}, #362). Menu and the other buttons stay.
     * Asked after each layout of the screen.
     */
    public static final BooleanSetting HIDE_REELS_TAB_TITLE =
            new BooleanSetting("hushfacebook_hide_reels_tab_title", FALSE);

    /**
     * Back at the start of the top bar of a video or reel opened full screen goes, and Menu in the
     * same spot on the Reels tab's header ({@link app.morphe.extension.facebook.reels.ViewerTopBar},
     * #388, #390). The phone's own back still goes back. Asked after each layout of the screen.
     */
    public static final BooleanSetting HIDE_VIEWER_BACK_BUTTON =
            new BooleanSetting("hushfacebook_hide_viewer_back_button", FALSE);

    /**
     * Burn-in guard on the Reels screen ({@link app.morphe.extension.facebook.reels.BurnInGuard},
     * #288): once the screen has gone {@link #BURN_IN_WAIT} without a touch, what shows beside the
     * video fades, and with {@link BurnInMode#DIM_AND_SHIFT} the screen also moves a step every two
     * minutes. Off by default. Read on each pass, so a change shows without a restart.
     */
    public static final EnumSetting<BurnInMode> BURN_IN_GUARD =
            new EnumSetting<>("hushfacebook_burn_in_guard", BurnInMode.OFF);

    /**
     * Dim after: how long the Reels screen goes untouched before Burn-in guard dims it (#288). A tap
     * on the video, or a double tap to like it, doesn't start it over. Five seconds by default.
     */
    public static final EnumSetting<BurnInWait> BURN_IN_WAIT =
            new EnumSetting<>("hushfacebook_burn_in_wait", BurnInWait.FIVE_SECONDS);

    /**
     * Stay dim when I tap (#288): once Burn-in guard has dimmed the Reels screen, no tap or swipe on
     * it brings the controls back, the next reel included, and they still work. Leaving Reels does.
     * Off by default.
     */
    public static final BooleanSetting BURN_IN_STAY_DIM =
            new BooleanSetting("hushfacebook_burn_in_stay_dim", FALSE);

    /**
     * Show Reels in gray: the Reels screen shows in black and white, video and buttons alike, and
     * every other screen keeps its colors ({@link app.morphe.extension.facebook.reels.GrayReels}).
     * The reels viewer's players draw into a TextureView while it's on, so the gray reaches the
     * video ({@link app.morphe.extension.facebook.media.TextureVideo}). Off by default. The screen
     * is read on each look; the video from the next player Facebook sets up.
     */
    public static final BooleanSetting GRAY_REELS =
            new BooleanSetting("hushfacebook_gray_reels", FALSE);

    /**
     * Turn gray over: how many minutes on the Reels screen {@link #GRAY_REELS} takes to go from full
     * color to gray ({@link app.morphe.extension.facebook.reels.GrayReels}). Fifteen minutes away
     * from Reels starts the colors over. At once by default, the gray as it always was. Read on each
     * look, so a change shows right away. It isn't a switch.
     */
    public static final EnumSetting<GrayOver> GRAY_REELS_OVER =
            new EnumSetting<>("hushfacebook_gray_reels_over", GrayOver.AT_ONCE, Setting.parent(GRAY_REELS));

    /**
     * Show time in Reels: a small label at the top of the Reels screen with the minutes watched
     * there, counted the way {@link #GRAY_REELS_OVER} counts them, with or without the gray
     * ({@link app.morphe.extension.facebook.reels.GrayReels}). Off by default. Read on each look.
     */
    public static final BooleanSetting REELS_MINUTES =
            new BooleanSetting("hushfacebook_reels_minutes", FALSE);

    /**
     * The numbers under a reel's Like, Comment and Share icons go, and the icons stay where they are
     * ({@link app.morphe.extension.facebook.reels.ReelDeclutter#countText}, #180). Off by default.
     * A change shows on the buttons drawn after it.
     */
    public static final BooleanSetting HIDE_REEL_COUNTS =
            new BooleanSetting("hushfacebook_hide_reel_counts", FALSE);

    /**
     * The Save button down a reel's right side isn't built, and the buttons above it move down
     * ({@link app.morphe.extension.facebook.reels.ReelDeclutter#hideSidebarButton}, #190). Off by
     * default. A change shows from the next reel.
     */
    public static final BooleanSetting HIDE_REEL_SAVE_BUTTON =
            new BooleanSetting("hushfacebook_hide_reel_save_button", FALSE);

    /** The direct WhatsApp sidebar button, independent of the share-sheet item. Off by default. */
    public static final BooleanSetting HIDE_REEL_WHATSAPP_BUTTON =
            new BooleanSetting("hushfacebook_hide_reel_whatsapp_button", FALSE);

    /**
     * Like, Comment, Repost (#450) or Share down a reel's right side isn't built, and the buttons
     * above it move down ({@link app.morphe.extension.facebook.reels.ReelDeclutter#hideSidebarButton},
     * #265). Each off by default. A change shows from the next reel.
     */
    public static final BooleanSetting HIDE_REEL_LIKE_BUTTON =
            new BooleanSetting("hushfacebook_hide_reel_like_button", FALSE);
    public static final BooleanSetting HIDE_REEL_COMMENT_BUTTON =
            new BooleanSetting("hushfacebook_hide_reel_comment_button", FALSE);
    public static final BooleanSetting HIDE_REEL_REPOST_BUTTON =
            new BooleanSetting("hushfacebook_hide_reel_repost_button", FALSE);
    public static final BooleanSetting HIDE_REEL_SHARE_BUTTON =
            new BooleanSetting("hushfacebook_hide_reel_share_button", FALSE);

    /**
     * A reel whose comments are turned off still gets the Comment button down its right side, so
     * the comments already there can be read, as its feed post allows
     * ({@link app.morphe.extension.facebook.reels.ReelDeclutter#showsCommentButton}, #299). Off by
     * default. A change shows from the next reel.
     */
    public static final BooleanSetting SHOW_REEL_COMMENT_BUTTON =
            new BooleanSetting("hushfacebook_show_reel_comment_button", FALSE);

    /**
     * The date a reel or full-screen video was posted shows under its author, a line Facebook
     * builds behind a server switch that's off for most accounts
     * ({@link app.morphe.extension.facebook.reels.ReelDeclutter#showsPostDate}, #296). Off by
     * default. A change shows from the next reel.
     */
    public static final BooleanSetting SHOW_REEL_POST_DATE =
            new BooleanSetting("hushfacebook_show_reel_post_date", FALSE);

    /**
     * Show the post date as: how a reel's post date reads, wherever it shows
     * ({@link app.morphe.extension.facebook.reels.ReelDeclutter#postDateText}, #357). How long ago by
     * default, which also fills in the older reels Facebook leaves blank while
     * {@link #SHOW_REEL_POST_DATE} is on. A change shows from the next reel.
     */
    public static final EnumSetting<ReelDateStyle> REEL_POST_DATE_STYLE =
            new EnumSetting<>("hushfacebook_reel_post_date_style", ReelDateStyle.AGO);

    /**
     * The speaker button at the bottom of a reel's right side isn't built
     * ({@link app.morphe.extension.facebook.reels.ReelDeclutter#showsSoundButton}, #190). Off by
     * default. A change shows from the next reel.
     */
    public static final BooleanSetting HIDE_REEL_SOUND_BUTTON =
            new BooleanSetting("hushfacebook_hide_reel_sound_button", FALSE);

    /**
     * A reel's speaker button sits in the side column right above the three dots, not in the corner
     * under them ({@link app.morphe.extension.facebook.reels.ReelDeclutter#cornerForSide}, #331). Off
     * by default. A change shows from the next reel.
     */
    public static final BooleanSetting REEL_SPEAKER_ABOVE_MENU =
            new BooleanSetting("hushfacebook_reel_speaker_above_menu", FALSE);

    /**
     * The line under a reel naming its sound, "Original audio" or a song, isn't drawn, and the other
     * chips stay ({@link app.morphe.extension.facebook.reels.ReelDeclutter#filterChips}, #200). Off
     * by default. A change shows from the next reel.
     */
    public static final BooleanSetting HIDE_REEL_AUDIO_LINE =
            new BooleanSetting("hushfacebook_hide_reel_audio_line", FALSE);

    /**
     * The "This is taking a while. Try checking your network connection." message a video shows
     * when it's slow to load isn't shown
     * ({@link app.morphe.extension.facebook.reels.ReelDeclutter#hideSlowConnectionMessage}, #222).
     * The video keeps loading as before. Off by default. A change shows the next time a video stalls.
     */
    public static final BooleanSetting HIDE_SLOW_CONNECTION_MESSAGE =
            new BooleanSetting("hushfacebook_hide_slow_connection_message", FALSE);

    /**
     * The UP NEXT bar under a video opened from the feed, naming the video Facebook lines up after
     * it, gets no video to show: the lookup returns before it queries and what was found is handed
     * out empty ({@link app.morphe.extension.facebook.reels.ReelDeclutter#skipUpNextFetch} and
     * {@link app.morphe.extension.facebook.reels.ReelDeclutter#hideUpNextVideos}, #367), and the
     * player keeps no empty room for it under the seek bar ({@link
     * app.morphe.extension.facebook.reels.ReelDeclutter#skipUpNextRoom}, #375). In the Shorts
     * viewer most feed videos open in, the UP NEXT pill isn't shown ({@link
     * app.morphe.extension.facebook.reels.ReelDeclutter#hideUpNextPill}, #375). Off by default.
     */
    public static final BooleanSetting HIDE_UP_NEXT =
            new BooleanSetting("hushfacebook_hide_up_next", FALSE);

    /**
     * The Fullscreen pill under a wide video in the Reels viewer isn't drawn ({@link
     * app.morphe.extension.facebook.reels.ReelDeclutter#hideFullscreenPill}, #394). Off by default.
     * A change shows as the next video draws.
     */
    public static final BooleanSetting HIDE_REEL_FULLSCREEN_PILL =
            new BooleanSetting("hushfacebook_hide_reel_fullscreen_pill", FALSE);

    /**
     * The tiles in the feed's Reels row play their reel from the start to its end instead of the
     * short clip Facebook picks, which is often one second played over and over
     * ({@link app.morphe.extension.facebook.reels.ReelDeclutter#allowsPreviewClip} and
     * {@link app.morphe.extension.facebook.reels.ReelDeclutter#previewClipEnd}, #228). Off by
     * default. A change shows on tiles the row draws after it.
     */
    public static final BooleanSetting PLAY_FEED_REELS_WHOLE =
            new BooleanSetting("hushfacebook_play_feed_reels_whole", FALSE);

    /**
     * A video tapped in the feed opens in the full-screen player on its own, with no reels to swipe
     * to after it ({@link app.morphe.extension.facebook.reels.FeedVideoAlone}, #161). The player's
     * reels feed is told nothing more came. The Reels tab keeps scrolling. Off until it's turned on,
     * since nobody has seen it on a phone yet.
     */
    public static final BooleanSetting FEED_VIDEOS_ALONE =
            new BooleanSetting("hushfacebook_feed_videos_alone", FALSE);

    /**
     * A reel opened from a link in another app or from a chat plays on its own, with no reels to
     * swipe to after it, the way {@link #FEED_VIDEOS_ALONE} keeps a feed video
     * ({@link app.morphe.extension.facebook.reels.FeedVideoAlone}, #421). Off by default. A change
     * shows from the next reel opened.
     */
    public static final BooleanSetting SHARED_REELS_ALONE =
            new BooleanSetting("hushfacebook_shared_reels_alone", FALSE);

    /**
     * Watch again at the end of a feed video plays it again where it is instead of opening it in
     * the Reels viewer ({@link app.morphe.extension.facebook.reels.WatchAgain}, #403). Off by
     * default. A change shows on the next tap.
     */
    public static final BooleanSetting WATCH_AGAIN_IN_PLACE =
            new BooleanSetting("hushfacebook_watch_again_in_place", FALSE);

    /**
     * A video tapped in Saved plays in the video player instead of the Reels viewer, whose next
     * swipe is a suggested reel ({@link app.morphe.extension.facebook.reels.SavedVideos}, #404).
     * Off by default. A change shows on the next tap.
     */
    public static final BooleanSetting SAVED_VIDEOS_IN_PLAYER =
            new BooleanSetting("hushfacebook_saved_videos_in_player", FALSE);

    /**
     * A reel opened from Saved goes on to the next one in Saved when it ends or is swiped past,
     * instead of a suggested reel ({@link app.morphe.extension.facebook.reels.SavedChain}, #404).
     * Off by default. A change shows on the next video opened from Saved.
     */
    public static final BooleanSetting SAVED_VIDEOS_IN_ORDER =
            new BooleanSetting("hushfacebook_saved_videos_in_order", FALSE);

    /**
     * Reels and videos go on to the next one without Facebook holding it back to ask "Are you still
     * watching?" ({@link app.morphe.extension.facebook.reels.StillWatching}, #427). Off by default.
     * Asked before each next video, so a change shows from the next one.
     */
    public static final BooleanSetting SKIP_STILL_WATCHING =
            new BooleanSetting("hushfacebook_skip_still_watching", FALSE);

    /**
     * A reel or full-screen video that Facebook would fit between black bars fills the screen
     * instead, with a little cut off its edges ({@link app.morphe.extension.facebook.reels.ReelFill},
     * #453, #466), and a pinch on a reel shows it whole or fills it again
     * ({@link app.morphe.extension.facebook.reels.ReelFitPinch}). Off by default. Asked each time a
     * player view is sized, so a change shows on the next reel.
     */
    public static final BooleanSetting FILL_REELS =
            new BooleanSetting("hushfacebook_fill_reels", FALSE);

    /**
     * The batches of watched reels the Reels viewer sends as FbShortsSeenStateMutation: only their
     * ids, the record Facebook ranks the Reels feed with, which nobody else sees. Held back, reels
     * already watched may come back in the feed.
     */
    public static final BooleanSetting DONT_SEND_REEL_WATCH_HISTORY =
            new BooleanSetting("hushfacebook_dont_send_reel_watch_history", FALSE);

    /**
     * The Reels tab's next reel, picked from what Facebook has loaded ({@link
     * app.morphe.extension.facebook.reels.NewestReels}): the newest posted first instead of
     * Facebook's ranking, or what {@link #REELS_ORDER} picks. Starts off. Reels already placed stay
     * where they are.
     */
    public static final BooleanSetting NEWEST_REELS_FIRST =
            new BooleanSetting("hushfacebook_newest_reels_first", FALSE);

    /**
     * What {@link #NEWEST_REELS_FIRST} sorts the loaded reels by: newest posted until someone picks
     * Most liked (#134), so the switch keeps doing what it did. It isn't a switch, and
     * a paused Facebook keeps its own ranking.
     */
    public static final EnumSetting<ReelsOrder> REELS_ORDER =
            new EnumSetting<>("hushfacebook_reels_order", ReelsOrder.NEWEST);

    /**
     * A profile's Reels tab lists its most viewed reels first
     * ({@link app.morphe.extension.facebook.reels.ProfileReelsSort}, #232), by the play count
     * Facebook reads for each tile. Off is Facebook's own order, newest first. Starts off. A
     * profile opened after the change picks it up.
     */
    public static final BooleanSetting PROFILE_REELS_MOST_VIEWED =
            new BooleanSetting("hushfacebook_profile_reels_most_viewed", FALSE);

    /**
     * Facebook's own analytics uploads ({@link app.morphe.extension.facebook.misc.AnalyticsUploads}):
     * the XAnalytics event uploader and the Papaya on-device learning jobs. Starts off.
     * The uploader is resumed as Facebook starts, so a change shows fully after a restart.
     */
    public static final BooleanSetting HOLD_ANALYTICS_UPLOADS =
            new BooleanSetting("hushfacebook_hold_analytics_uploads", FALSE, true);

    /**
     * Facebook's camera roll cloud processing and its media count reports
     * ({@link app.morphe.extension.facebook.misc.CameraRollProcessing}). Starts off. Facebook
     * cancels the work it scheduled the next time it checks, which a restart brings about.
     */
    public static final BooleanSetting HOLD_CAMERA_ROLL_PROCESSING =
            new BooleanSetting("hushfacebook_hold_camera_roll_processing", FALSE, true);

    /**
     * Screenshots of the screens Facebook marks secure ({@link app.morphe.extension.facebook.misc.Screenshots}).
     * Starts off. A screen takes it when it's next opened.
     */
    public static final BooleanSetting ALLOW_SCREENSHOTS =
            new BooleanSetting("hushfacebook_allow_screenshots", FALSE);

    /**
     * Facebook's Facebook Plus app icons on its App icon page ({@link app.morphe.extension.facebook.misc.AppIcons}).
     * Starts off. The App icon page reads it each time it opens. An icon picked while it's on is a
     * launcher alias, so it stays when the switch goes off, unless Facebook's start-up rollback (behind
     * a server flag, off on the accounts seen so far) puts the blue one back. Facebook's component
     * manager resets it on the first start after an update, which the switch skips while it's on.
     */
    public static final BooleanSetting UNLOCK_APP_ICONS =
            new BooleanSetting("hushfacebook_unlock_app_icons", FALSE);

    /**
     * The picked app icon stands in for the Facebook wordmark at the top of Home
     * ({@link app.morphe.extension.facebook.misc.AppIcons#dressLogo}, #172). Starts off, so Facebook's
     * wordmark stays. Needs {@link #UNLOCK_APP_ICONS}. Facebook builds the header once, so a change
     * waits for a restart. It never changes which launcher alias is on.
     */
    public static final BooleanSetting USE_APP_ICON_IN_HEADER =
            new BooleanSetting("hushfacebook_use_app_icon_in_header", FALSE, true, Setting.parent(UNLOCK_APP_ICONS));

    /**
     * Facebook's haptics on its own taps and gestures ({@link app.morphe.extension.facebook.misc.Haptics}).
     * Starts off.
     */
    public static final BooleanSetting TURN_OFF_HAPTICS =
            new BooleanSetting("hushfacebook_turn_off_haptics", FALSE);

    /**
     * The Data mode banner Facebook Flex carriers show under the top bar, with its offer to buy
     * mobile data ({@link app.morphe.extension.facebook.misc.CarrierBanners}, #70). Starts off.
     */
    public static final BooleanSetting HIDE_DATA_MODE_BANNER =
            new BooleanSetting("hushfacebook_hide_data_mode_banner", FALSE);

    /**
     * The purple Free mode banner of Facebook Flex carriers, which is also the way back to Data
     * mode ({@link app.morphe.extension.facebook.misc.CarrierBanners}, #70). Starts off.
     */
    public static final BooleanSetting HIDE_FREE_MODE_BANNER =
            new BooleanSetting("hushfacebook_hide_free_mode_banner", FALSE);

    /**
     * Facebook's screens and tabs show without the slide or fade between them
     * ({@link app.morphe.extension.facebook.misc.ScreenTransitions}). Starts off.
     */
    public static final BooleanSetting TURN_OFF_SCREEN_TRANSITIONS =
            new BooleanSetting("hushfacebook_turn_off_screen_transitions", FALSE);

    /**
     * Facebook's screenshot and screen recording detection
     * ({@link app.morphe.extension.facebook.misc.ScreenshotDetection}). Starts off.
     */
    public static final BooleanSetting BLOCK_SCREENSHOT_DETECTION =
            new BooleanSetting("hushfacebook_block_screenshot_detection", FALSE);

    /**
     * Others see that you're typing, in a chat that opens inside Facebook and in a comment box
     * ({@link app.morphe.extension.facebook.chats.TypingIndicator}). Both start off.
     */
    public static final BooleanSetting HIDE_CHAT_TYPING =
            new BooleanSetting("hushfacebook_hide_chat_typing", FALSE);

    public static final BooleanSetting HIDE_COMMENT_TYPING =
            new BooleanSetting("hushfacebook_hide_comment_typing", FALSE);

    /**
     * The sender sees that you've read a chat that opens inside Facebook
     * ({@link app.morphe.extension.facebook.chats.ReadReceipts}). Starts off.
     */
    public static final BooleanSetting HIDE_READ_RECEIPTS =
            new BooleanSetting("hushfacebook_hide_read_receipts", FALSE);

    /**
     * Photos and videos sent from a chat that opens inside Facebook go out as the originals
     * ({@link app.morphe.extension.facebook.chats.OriginalChatMedia}). Starts off.
     */
    public static final BooleanSetting ORIGINAL_CHAT_MEDIA =
            new BooleanSetting("hushfacebook_original_chat_media", FALSE);

    /**
     * A double tap on a reel, a video or a photo on Home left without Facebook's like: no heart, no
     * like sent. A single tap and the Like button do what they always did. Starts off.
     */
    public static final BooleanSetting TURN_OFF_DOUBLE_TAP_LIKE =
            new BooleanSetting("hushfacebook_turn_off_double_tap_like", FALSE);

    /**
     * A tap on Like under a post on Home asks "Like or unlike this post?" first, and only Continue
     * likes or unlikes it ({@link app.morphe.extension.facebook.feed.AskBeforeLike}, #437). Starts
     * off. Read on each tap.
     */
    public static final BooleanSetting ASK_BEFORE_LIKING_POSTS =
            new BooleanSetting("hushfacebook_ask_before_liking_posts", FALSE);

    /**
     * The same question before the Like beside a reel ({@link
     * app.morphe.extension.facebook.feed.AskBeforeLike}, #437). Starts off. Read on each tap.
     */
    public static final BooleanSetting ASK_BEFORE_LIKING_REELS =
            new BooleanSetting("hushfacebook_ask_before_liking_reels", FALSE);

    /**
     * The same question before the Like under a comment ({@link
     * app.morphe.extension.facebook.feed.AskBeforeLike}, #443). Starts off. Read on each tap.
     */
    public static final BooleanSetting ASK_BEFORE_LIKING_COMMENTS =
            new BooleanSetting("hushfacebook_ask_before_liking_comments", FALSE);

    /**
     * Facebook's own double-tap skip on reels and videos, which a server test keeps off for most
     * accounts: a double tap on the left or right edge skips back or forward 10 seconds, or 5 on a
     * video under 30 seconds ({@link app.morphe.extension.facebook.reels.DoubleTapSkip}, #225).
     * Starts off. Read as each player is set up and on each double tap.
     */
    public static final BooleanSetting DOUBLE_TAP_SKIP =
            new BooleanSetting("hushfacebook_double_tap_skip", FALSE);

    /**
     * A tap on a playing reel shows its controls and lets it play on, Facebook's own answer to a
     * tap that it ships off, with the controls kept up three seconds in place of one
     * ({@link app.morphe.extension.facebook.reels.TapShowsControls}, #225). The button in the middle
     * still pauses. Starts off. Read on each tap.
     */
    public static final BooleanSetting TAP_SHOWS_CONTROLS =
            new BooleanSetting("hushfacebook_tap_shows_controls", FALSE);

    /**
     * A double tap on a reel opens its comments, on the sheet the Comment button opens, in place of
     * liking it ({@link app.morphe.extension.facebook.reels.ReelGestures}, #179). Turn off double
     * tap to like stays the stronger switch: with it on, the double tap does nothing, as before.
     * Starts off.
     */
    public static final BooleanSetting DOUBLE_TAP_OPENS_COMMENTS =
            new BooleanSetting("hushfacebook_double_tap_opens_comments", FALSE);

    /**
     * A long press on a reel opens its comments in place of Facebook's hold behavior, the long-press
     * menu or Hold a reel for 2x ({@link app.morphe.extension.facebook.reels.ReelGestures}, #179).
     * Starts off.
     */
    public static final BooleanSetting LONG_PRESS_OPENS_COMMENTS =
            new BooleanSetting("hushfacebook_long_press_opens_comments", FALSE);

    /**
     * A long press on a feed post's text, which Facebook answers by copying all of it, also opens
     * that text in a box where a part can be selected and copied
     * ({@link app.morphe.extension.facebook.reels.PostTextSelect}, #294). Starts off.
     */
    public static final BooleanSetting SELECT_POST_TEXT =
            new BooleanSetting("hushfacebook_select_post_text", FALSE);

    /**
     * A hold on a feed post's photo or video does what a hold on its Like button does: opens the
     * reaction tray, or gives {@link #HOLD_REACTION} straight away
     * ({@link app.morphe.extension.facebook.reels.ReactionHold}, #454). Wins over
     * {@link #LONG_PRESS_OPENS_COMMENTS} on a feed post. Starts off.
     */
    public static final BooleanSetting HOLD_POST_TO_REACT =
            new BooleanSetting("hushfacebook_hold_post_to_react", FALSE);

    /**
     * What {@link #HOLD_POST_TO_REACT} gives: the reaction tray to pick from, by default, or one
     * reaction (#454).
     */
    public static final EnumSetting<HoldReaction> HOLD_REACTION =
            new EnumSetting<>("hushfacebook_hold_reaction", HoldReaction.PICK);

    /**
     * A speed picked in a reel's menu stays for the next reels where it was picked, ads and live
     * videos aside, until another is picked, and is saved so a restart keeps it
     * ({@link app.morphe.extension.facebook.media.ReelSpeed}). Normal speed forgets it.
     * Off or paused, each reel starts at the speed Facebook starts it at.
     */
    public static final BooleanSetting KEEP_REEL_SPEED =
            new BooleanSetting("hushfacebook_keep_reel_speed", TRUE);

    /**
     * A speed picked in a feed or Watch video's gear menu stays for the next videos that aren't
     * reels, ads or live, until another is picked, and is saved so a restart keeps it. Reels keep
     * their own speed by {@link #KEEP_REEL_SPEED}. It rides on Keep the reel speed's hooks
     * ({@link app.morphe.extension.facebook.media.ReelSpeed}). Normal speed forgets it. Off or
     * paused, each video starts at the speed Facebook starts it at.
     */
    public static final BooleanSetting KEEP_VIDEO_SPEED =
            new BooleanSetting("hushfacebook_keep_video_speed", FALSE);

    /**
     * The Reels menu's two speed pickers also offer 0.1x and 0.25x, slower than Facebook's 0.5x
     * (#95). It rides on Keep the reel speed's patch
     * ({@link app.morphe.extension.facebook.media.ReelSpeed#speedChoices}). Off by default until
     * it's seen on a phone. Off or paused, the pickers offer Facebook's speeds.
     */
    public static final BooleanSetting SLOWER_REEL_SPEEDS =
            new BooleanSetting("hushfacebook_slower_reel_speeds", FALSE);

    /**
     * The Reels menu's two speed pickers and the gear menu's speed sheet also offer 2.5x and 3x,
     * faster than the 2x Facebook stops at (#430). It rides on Keep the reel speed's patch like
     * {@link #SLOWER_REEL_SPEEDS}. Starts off. Off or paused, the pickers offer Facebook's speeds.
     */
    public static final BooleanSetting FASTER_REEL_SPEEDS =
            new BooleanSetting("hushfacebook_faster_reel_speeds", FALSE);

    /**
     * A feed or Watch video's 3-dot menu gets Playback speed, which offers 0.5x to 3x for that
     * video, a live one too (#429). It rides on Keep the reel speed's patch for the player and on
     * Download any video's for the menu ({@link app.morphe.extension.facebook.media.ReelSpeed}).
     * Starts off. Off or paused, the menu is Facebook's.
     */
    public static final BooleanSetting POST_MENU_SPEED =
            new BooleanSetting("hushfacebook_post_menu_speed", FALSE);

    /**
     * The gear menu offers Playback speed on a full-screen video that isn't live, a finished live
     * video among them, where Facebook's full-screen player leaves it out behind a server flag
     * (#238). It rides on Keep the reel speed's patch
     * ({@link app.morphe.extension.facebook.media.ReplaySpeed}). Ads and videos that are still
     * live keep Facebook's menu, and so does everything while it's off or paused.
     */
    public static final BooleanSetting SPEED_ON_REPLAYS =
            new BooleanSetting("hushfacebook_speed_on_replays", TRUE);

    /**
     * A reel you hold plays at double speed until you let go, through the speed-up Facebook's Reels
     * controls already have, in place of Facebook's long-press menu
     * ({@link app.morphe.extension.facebook.reels.ReelHold}). Starts off. Off or paused, a long press
     * opens Facebook's menu.
     */
    public static final BooleanSetting HOLD_REEL_FOR_2X =
            new BooleanSetting("hushfacebook_hold_reel_for_2x", FALSE);

    /**
     * With {@link #HOLD_REEL_FOR_2X} on, only a hold on a reel's right third speeds it up, measured
     * against the reel's width where the finger landed. A hold anywhere else gets Facebook's own
     * answer, its long-press menu on most accounts. Off by default.
     */
    public static final BooleanSetting HOLD_REEL_RIGHT_EDGE =
            new BooleanSetting("hushfacebook_hold_reel_right_edge", FALSE);

    /**
     * A video playing in a feed, Home's or Watch's, plays at double speed while you hold it and goes
     * back to its speed when you let go ({@link app.morphe.extension.facebook.reels.FeedVideoHold}).
     * Works without {@link #HOLD_REEL_FOR_2X}. Starts off. Off or paused, a hold on a feed video is
     * Facebook's.
     */
    public static final BooleanSetting HOLD_FEED_VIDEO_FOR_2X =
            new BooleanSetting("hushfacebook_hold_feed_video_for_2x", FALSE);

    /**
     * The 2x pill a held video shows stays the plain 2x pill, without Facebook's "Pull down to lock
     * 2x speed" hint and its unlock and first-time versions
     * ({@link app.morphe.extension.facebook.reels.LockHint}). Pulling down still locks 2x. Works
     * without {@link #HOLD_REEL_FOR_2X}. Starts off.
     */
    public static final BooleanSetting HIDE_2X_LOCK_HINT =
            new BooleanSetting("hushfacebook_hide_2x_lock_hint", FALSE);

    /**
     * Holding a video for 2x draws no pill at all: no speed, no "Release to lock" and no "Locked at 2x
     * speed" (#289). The hold and its lock work as before. Works without {@link #HOLD_REEL_FOR_2X}.
     * Starts off.
     */
    public static final BooleanSetting HIDE_2X_POPUPS =
            new BooleanSetting("hushfacebook_hide_2x_popups", FALSE);

    /**
     * Comment sheets ask for the order in {@link #COMMENT_ORDER} where Facebook's servers would
     * choose one, and an order picked in a post's comments stays for that post until Facebook
     * restarts ({@link app.morphe.extension.facebook.comments.DefaultCommentOrder}). A request that
     * names its own order, and a link to one comment, keep Facebook's. With the order left as
     * Facebook's, the switch changes nothing.
     */
    public static final BooleanSetting DEFAULT_COMMENT_ORDER =
            new BooleanSetting("hushfacebook_default_comment_order", FALSE);

    /**
     * Comment sheets open without Meta AI's summary of the comments at the top, and posts come
     * without the summary Facebook adds under their buttons
     * ({@link app.morphe.extension.facebook.comments.MetaAiSummaries}). Off by default. A change
     * shows on the next comment sheet or post drawn.
     */
    public static final BooleanSetting HIDE_META_AI_SUMMARIES =
            new BooleanSetting("hushfacebook_hide_meta_ai_summaries", FALSE);

    /**
     * A long press on Like doesn't open the reaction picker, so a tap that likes is all Like does
     * ({@link app.morphe.extension.facebook.comments.CommentSheetOptions}). Off by default. A change
     * shows on the next long press.
     */
    public static final BooleanSetting LIKE_ONLY =
            new BooleanSetting("hushfacebook_like_only", FALSE);

    /**
     * The comment box comes without its GIF and sticker buttons
     * ({@link app.morphe.extension.facebook.comments.CommentSheetOptions}). Typing, photos and
     * posting stay. Off by default. A change shows on the next comment box drawn.
     */
    public static final BooleanSetting HIDE_COMMENT_GIF_STICKER_BUTTONS =
            new BooleanSetting("hushfacebook_hide_comment_gif_sticker_buttons", FALSE);

    /**
     * The comment box comes without the row of emoji over it (#290,
     * {@link app.morphe.extension.facebook.comments.CommentSheetOptions}). Typing and the box's
     * buttons stay. Off by default. A change shows on the next comment box drawn.
     */
    public static final BooleanSetting HIDE_COMMENT_EMOJI_ROW =
            new BooleanSetting("hushfacebook_hide_comment_emoji_row", FALSE);

    /**
     * The comment box comes without the thin line along its top (#344,
     * {@link app.morphe.extension.facebook.comments.CommentSheetOptions}). Off by default. A
     * change shows on the next comment box built.
     */
    public static final BooleanSetting HIDE_COMMENT_BOX_LINE =
            new BooleanSetting("hushfacebook_hide_comment_box_line", FALSE);

    /**
     * The comment composer at the bottom of Facebook's full-screen video viewer is omitted from
     * the viewer's render method ({@link app.morphe.extension.facebook.comments.CommentSheetOptions}).
     * In the Shorts viewer most feed videos open in, the band the bar sits in goes with it, the
     * video takes its full height and the seek bar goes down with it ({@link
     * app.morphe.extension.facebook.comments.CommentSheetOptions#dropsShortsCommentBar}, #375).
     * Comment boxes on ordinary posts are unchanged. Off by default.
     */
    public static final BooleanSetting HIDE_VIDEO_COMMENT_COMPOSER =
            new BooleanSetting("hushfacebook_hide_video_comment_composer", FALSE);

    /**
     * Every comment starts with its reply thread open, as if View replies had been tapped
     * ({@link app.morphe.extension.facebook.comments.CommentSheetOptions}). Off by default. A
     * change shows on the next comments drawn.
     */
    public static final BooleanSetting OPEN_REPLY_THREADS =
            new BooleanSetting("hushfacebook_open_reply_threads", FALSE);

    /**
     * An open reply thread loads the rest of its replies as it's scrolled, with no View more
     * replies to tap ({@link app.morphe.extension.facebook.comments.CommentSheetOptions}, #140).
     * Works with or without {@link #OPEN_REPLY_THREADS}. Off by default. A change shows on the
     * next thread opened.
     */
    public static final BooleanSetting LOAD_WHOLE_REPLY_THREADS =
            new BooleanSetting("hushfacebook_load_whole_reply_threads", FALSE);

    /**
     * A reply thread a tap opened gets a Hide replies row over it, with its arrow up, that closes it
     * again ({@link app.morphe.extension.facebook.comments.CommentSheetOptions}, #325). Off by
     * default. A change shows on the next comments drawn.
     */
    public static final BooleanSetting HIDE_REPLIES_BUTTON =
            new BooleanSetting("hushfacebook_hide_replies_button", FALSE);

    /**
     * A tap anywhere on a View more replies row opens the replies, not only a tap on its words
     * ({@link app.morphe.extension.facebook.comments.CommentSheetOptions}, #219). Off by default.
     * A change shows on the next rows drawn.
     */
    public static final BooleanSetting TAP_ANYWHERE_ON_VIEW_MORE_REPLIES =
            new BooleanSetting("hushfacebook_tap_anywhere_on_view_more_replies", FALSE);

    /**
     * A group post's comments end without the Related groups list and its Join buttons
     * ({@link app.morphe.extension.facebook.comments.CommentSheetOptions}, #101). Off by default. A
     * change shows on the next comments opened.
     */
    public static final BooleanSetting HIDE_RELATED_GROUPS_UNDER_COMMENTS =
            new BooleanSetting("hushfacebook_hide_related_groups_under_comments", FALSE);

    /**
     * Feed posts show none of the few comments Facebook draws under their buttons
     * ({@link app.morphe.extension.facebook.comments.CommentSheetOptions}, #253). The Comment button
     * still opens them all. Off by default. A change shows on posts drawn after it.
     */
    public static final BooleanSetting HIDE_POST_COMMENT_PREVIEWS =
            new BooleanSetting("hushfacebook_hide_post_comment_previews", FALSE);

    /**
     * Comments lose the thumbs-down Facebook draws next to Like
     * ({@link app.morphe.extension.facebook.comments.CommentSheetOptions}, #241). Like, Reply and
     * the rest stay. Off by default. A change shows on comments drawn after it.
     */
    public static final BooleanSetting HIDE_COMMENT_DOWNVOTE_BUTTON =
            new BooleanSetting("hushfacebook_hide_comment_downvote_button", FALSE);

    /**
     * Comments lose Like from the row under them
     * ({@link app.morphe.extension.facebook.comments.CommentSheetOptions}, #241). Reply, the reaction
     * icons and their count stay. Off by default. A change shows on comments drawn after it.
     */
    public static final BooleanSetting HIDE_COMMENT_LIKE_BUTTON =
            new BooleanSetting("hushfacebook_hide_comment_like_button", FALSE);

    /**
     * Comments lose Reply from the row under them
     * ({@link app.morphe.extension.facebook.comments.CommentSheetOptions}, #447). View replies, Like,
     * the reaction icons and their count stay. Off by default. A change shows on comments drawn after it.
     */
    public static final BooleanSetting HIDE_COMMENT_REPLY_BUTTON =
            new BooleanSetting("hushfacebook_hide_comment_reply_button", FALSE);

    /**
     * The thumbs-down under a comment becomes a Block button that opens Facebook's own block
     * sheet for the commenter, confirmation included
     * ({@link app.morphe.extension.facebook.comments.CommentBlock}, #271). Off by default. A
     * change shows on comments drawn after it.
     */
    public static final BooleanSetting COMMENT_BLOCK_BUTTON =
            new BooleanSetting("hushfacebook_comment_block_button", FALSE);

    /**
     * The button at the end of each row of a post's reaction list (Add friend, Message and the
     * rest) becomes a Block button that opens Facebook's own block sheet for that person
     * ({@link app.morphe.extension.facebook.comments.ReactorBlock}, #271). Off by default. A
     * change shows on lists opened after it.
     */
    public static final BooleanSetting REACTOR_BLOCK_BUTTON =
            new BooleanSetting("hushfacebook_reactor_block_button", FALSE);

    /**
     * Comments whose words link to an online shop, like the Shopee links pasted under reels, are
     * left out with their replies ({@link app.morphe.extension.facebook.comments.CommentSheetOptions},
     * #118). Off by default. A change shows on the next comments drawn.
     */
    public static final BooleanSetting HIDE_SHOP_LINK_COMMENTS =
            new BooleanSetting("hushfacebook_hide_shop_link_comments", FALSE);

    /**
     * Comments holding a phrase from {@link #HIDDEN_WORDS} and none from {@link #KEPT_WORDS} are
     * left out with their replies, matched as posts are
     * ({@link app.morphe.extension.facebook.comments.CommentSheetOptions}, #118). Off by default.
     */
    public static final BooleanSetting HIDE_COMMENTS_WITH_WORDS =
            new BooleanSetting("hushfacebook_hide_comments_with_words", FALSE);

    /**
     * The "someone is typing" line under a comment box is never shown: the typer count that arrives
     * for a post is answered as zero
     * ({@link app.morphe.extension.facebook.comments.CommentSheetOptions}, #170). Sending your own
     * typing is {@link #HIDE_COMMENT_TYPING}. Off by default.
     */
    public static final BooleanSetting HIDE_INCOMING_COMMENT_TYPING =
            new BooleanSetting("hushfacebook_hide_incoming_comment_typing", FALSE);

    /**
     * The "3 new comments" pill that shows while a comment list is open never shows: its text is
     * answered empty, and Facebook skips a pill with no text
     * ({@link app.morphe.extension.facebook.comments.CommentSheetOptions}, #185). New comments still
     * arrive in the list. Off by default.
     */
    public static final BooleanSetting HIDE_NEW_COMMENTS_PILL =
            new BooleanSetting("hushfacebook_hide_new_comments_pill", FALSE);

    /**
     * How high the comment sheet opens: where Facebook stops it, the full height of the screen, or
     * half of it with the reel or video playing above. Full or half, a reel's or a feed video's
     * Comment button opens that sheet instead of the split screen with the video above
     * ({@link app.morphe.extension.facebook.comments.FullHeightComments}, #179). Facebook's height
     * by default; a phone that had the old Open comments at full height switch on starts at full
     * height ({@link CommentsHeightSetting}).
     */
    public static final EnumSetting<CommentsHeight> COMMENTS_HEIGHT = new CommentsHeightSetting();

    /**
     * The reel playing on screen pauses when its comment sheet opens and plays on from there when
     * the sheet closes ({@link app.morphe.extension.facebook.media.PauseUnderComments}, #266). Needs
     * Open comments at full height's sheet hook and Tap to play's player bridge. Off by default.
     */
    public static final BooleanSetting PAUSE_REELS_UNDER_COMMENTS =
            new BooleanSetting("hushfacebook_pause_reels_under_comments", FALSE);

    /**
     * A tap on the reel above a comment sheet that stops part way up goes to the reel, which plays
     * or pauses, instead of closing the sheet
     * ({@link app.morphe.extension.facebook.comments.FullHeightComments#tapThrough}, #315). The
     * Reels tab's sheet, which fills the screen with the reel showing through its header, plays or
     * pauses the reel through Tap to play's player bridge instead
     * ({@link app.morphe.extension.facebook.comments.FullHeightComments#watchHeader}). Needs Open
     * comments at full height's hooks, and does nothing while that switch keeps sheets at full
     * height. Off by default.
     */
    public static final BooleanSetting TAP_REELS_ABOVE_COMMENTS =
            new BooleanSetting("hushfacebook_tap_reels_above_comments", FALSE);

    /**
     * A magnifier at the top of a comment sheet's list opens a search box that finds the comments
     * with your words and steps through them
     * ({@link app.morphe.extension.facebook.comments.CommentSearch}, #420). Needs Open comments at
     * full height's sheet hook, and works whatever that switch says. Off by default. A change shows
     * on the next sheet opened.
     */
    public static final BooleanSetting SEARCH_COMMENTS =
            new BooleanSetting("hushfacebook_search_comments", FALSE);

    /**
     * While the comment search box holds words, the comments without them leave the list, and
     * clearing the box brings them back
     * ({@link app.morphe.extension.facebook.comments.CommentSearch}). Needs Search comments and
     * Comment sheet options' comment and list hooks. Off by default.
     */
    public static final BooleanSetting FILTER_COMMENT_SEARCH =
            new BooleanSetting("hushfacebook_filter_comment_search", FALSE);

    /**
     * Facebook's text boxes (posts, comments, captions, a story's text) look people up to tag only
     * for a word that starts with @. Off, they also look them up for a plain word Facebook takes for
     * a name, what its code calls an implicit mention
     * ({@link app.morphe.extension.facebook.composer.TagSuggestions}). Words with @ or #, photo tags
     * and the text itself are never touched.
     */
    public static final BooleanSetting TAG_SUGGESTIONS_ONLY_AFTER_AT =
            new BooleanSetting("hushfacebook_tag_suggestions_only_after_at", FALSE);

    /**
     * Videos, reels, stories and songs start only after a tap: a player's start goes ahead when a
     * tap has just ended, and Facebook's own Autoplay setting reads Off unless
     * {@link #INLINE_FEED_VIDEO_CONTROLS} is on ({@link app.morphe.extension.facebook.media.TapToPlay}).
     * The stored Autoplay setting is never
     * written, so off or paused, Facebook plays as you set it.
     */
    public static final BooleanSetting TAP_TO_PLAY =
            new BooleanSetting("hushfacebook_tap_to_play", FALSE);

    /**
     * With {@link #TAP_TO_PLAY} on, once a tap plays a reel that was waiting, the reels swiped to
     * after it start on their own, until a start is held again (#91). Off by default, so every reel
     * waits. The feed, Watch and stories wait either way.
     */
    public static final BooleanSetting TAP_TO_PLAY_REELS_AFTER_FIRST =
            new BooleanSetting("hushfacebook_tap_to_play_reels_after_first", FALSE);

    /**
     * A story's video waits for a touch on the story itself: one that ends after the story first
     * tried to start, so the tap that opened the story doesn't count (#261). Works with
     * {@link #TAP_TO_PLAY} on or off. Off, stories follow that switch as before.
     */
    public static final BooleanSetting TAP_TO_PLAY_STORIES =
            new BooleanSetting("hushfacebook_tap_to_play_stories", FALSE);

    /**
     * A video in search results waits for a tap, by the same rules as Tap to play, whether that
     * switch is on or off (#363). Off, search results follow Tap to play as before.
     */
    public static final BooleanSetting TAP_TO_PLAY_SEARCH =
            new BooleanSetting("hushfacebook_tap_to_play_search", FALSE);

    /**
     * A separate feed play/pause button. Native video taps still open fullscreen. Starts off. With
     * {@link #TAP_TO_PLAY} on it keeps Facebook's Autoplay setting as chosen, since the feed binds a
     * player to a video only while autoplay is allowed (#239).
     */
    public static final BooleanSetting INLINE_FEED_VIDEO_CONTROLS =
            new BooleanSetting("hushfacebook_inline_feed_video_controls", FALSE) {
                @Override
                protected void onValueChanged() {
                    FeedVideoControls.settingsChanged();
                }
            };

    /**
     * A cast button on a feed video's own surface, beside the play/pause one (#182). Facebook's
     * build has no cast code left, so a tap hands that video's file to a casting app, the way
     * {@link #OPEN_IN_ANOTHER_APP} does from the menu. Starts off.
     */
    public static final BooleanSetting FEED_VIDEO_CAST_BUTTON =
            new BooleanSetting("hushfacebook_feed_video_cast_button", FALSE) {
                @Override
                protected void onValueChanged() {
                    FeedVideoControls.settingsChanged();
                }
            };

    /**
     * A bar along the bottom of a feed video to drag to any point in it (#326). A drag shows the
     * time, and the video moves when it's let go. Starts off.
     */
    public static final BooleanSetting FEED_VIDEO_SEEK_BAR =
            new BooleanSetting("hushfacebook_feed_video_seek_bar", FALSE) {
                @Override
                protected void onValueChanged() {
                    FeedVideoControls.settingsChanged();
                }
            };

    /**
     * With {@link #FEED_VIDEO_SEEK_BAR} on, a playing video's bar and Facebook's speaker fade a few
     * seconds after the video starts, a touch on the bar or speaker, or a pause (#386). Its own
     * switch, since not everyone wants it. Starts off.
     */
    public static final BooleanSetting FEED_VIDEO_SEEK_BAR_FADE =
            new BooleanSetting("hushfacebook_feed_video_seek_bar_fade", FALSE) {
                @Override
                protected void onValueChanged() {
                    FeedVideoControls.settingsChanged();
                }
            };

    /**
     * With {@link #FEED_VIDEO_SEEK_BAR} on, the bar and Facebook's buttons in the video's bottom
     * corners, the speaker and the tagged people, sit closer to the video's bottom edge (#386).
     * Starts off.
     */
    public static final BooleanSetting FEED_VIDEO_SEEK_BAR_LOWER =
            new BooleanSetting("hushfacebook_feed_video_seek_bar_lower", FALSE) {
                @Override
                protected void onValueChanged() {
                    FeedVideoControls.settingsChanged();
                }
            };

    /**
     * With {@link #FEED_VIDEO_SEEK_BAR} on, the bar runs along the video's bottom edge, and Facebook's
     * buttons in the video's bottom corners move up to sit above it. Wins over
     * {@link #FEED_VIDEO_SEEK_BAR_LOWER} (#431). Starts off.
     */
    public static final BooleanSetting FEED_VIDEO_SEEK_BAR_EDGE =
            new BooleanSetting("hushfacebook_feed_video_seek_bar_edge", FALSE) {
                @Override
                protected void onValueChanged() {
                    FeedVideoControls.settingsChanged();
                }
            };

    /**
     * A seek bar along the bottom of a video story, where {@link #STORY_PROGRESS_BAR} draws, which
     * makes way for it. A drag holds the story and the let go moves it there (#386). Starts off.
     */
    public static final BooleanSetting STORY_SEEK_BAR =
            new BooleanSetting("hushfacebook_story_seek_bar", FALSE) {
                @Override
                protected void onValueChanged() {
                    FeedVideoControls.settingsChanged();
                }
            };

    /**
     * With {@link #STORY_SEEK_BAR} on, the bar fades a few seconds after the story starts or was
     * last touched. Its own switch, apart from the feed's (#386). Starts off.
     */
    public static final BooleanSetting STORY_SEEK_BAR_FADE =
            new BooleanSetting("hushfacebook_story_seek_bar_fade", FALSE) {
                @Override
                protected void onValueChanged() {
                    FeedVideoControls.settingsChanged();
                }
            };

    /**
     * A button on a feed video, beside the play/pause one, that opens the video in Facebook's own
     * full player with all its controls, the way Recently watched opens one (#239). Live videos
     * get it too. Starts off.
     */
    public static final BooleanSetting FEED_VIDEO_FULL_PLAYER_BUTTON =
            new BooleanSetting("hushfacebook_feed_video_full_player_button", FALSE) {
                @Override
                protected void onValueChanged() {
                    FeedVideoControls.settingsChanged();
                }
            };

    /**
     * With {@link #FEED_VIDEO_FULL_PLAYER_BUTTON} on, the button sits in the video's bottom corner
     * where Facebook's speaker was, and the speaker moves over to make room (#431). A live video, or
     * one without the speaker, keeps it in the row at the top. Starts off.
     */
    public static final BooleanSetting FEED_VIDEO_FULL_PLAYER_CORNER =
            new BooleanSetting("hushfacebook_feed_video_full_player_corner", FALSE) {
                @Override
                protected void onValueChanged() {
                    FeedVideoControls.settingsChanged();
                }
            };

    /**
     * Two buttons on a feed video, beside the play/pause one, that jump back or ahead 10 seconds,
     * as in Facebook's own player (#239). Live videos don't get them. Starts off.
     */
    public static final BooleanSetting FEED_VIDEO_SKIP_BUTTONS =
            new BooleanSetting("hushfacebook_feed_video_skip_buttons", FALSE) {
                @Override
                protected void onValueChanged() {
                    FeedVideoControls.settingsChanged();
                }
            };

    /**
     * A double tap on the right or left side of a started feed video jumps it 10 seconds ahead or
     * back, a live one too while it can be rewound, and a lone tap on a side opens it a moment later
     * (#432). Asked at each press. Starts off.
     */
    public static final BooleanSetting FEED_VIDEO_DOUBLE_TAP_SKIP =
            new BooleanSetting("hushfacebook_feed_video_double_tap_skip", FALSE);

    /**
     * A button on a feed video that shows its speed and steps it on a tap: 1x, 1.25x, 1.5x, 2x,
     * then 0.5x (#239). Needs Hold a reel for 2x in the build, whose patch reaches the player's
     * speed. Live videos don't get it. Starts off.
     */
    public static final BooleanSetting FEED_VIDEO_SPEED_BUTTON =
            new BooleanSetting("hushfacebook_feed_video_speed_button", FALSE) {
                @Override
                protected void onValueChanged() {
                    FeedVideoControls.settingsChanged();
                }
            };

    /**
     * With {@link #FEED_VIDEO_SEEK_BAR} on, the time so far shows at the bar's left end and the
     * time left at its right, as in Facebook's own player (#239). Starts off.
     */
    public static final BooleanSetting FEED_VIDEO_SEEK_BAR_TIMES =
            new BooleanSetting("hushfacebook_feed_video_seek_bar_times", FALSE) {
                @Override
                protected void onValueChanged() {
                    FeedVideoControls.settingsChanged();
                }
            };

    /**
     * A video opened in Facebook's full screen player, from a comment or a link, starts with its
     * details and comments folded away, so it sits in the middle of the screen the way the
     * player's own chevron leaves it (#242). Asked as each video is set up. Starts off.
     */
    public static final BooleanSetting CENTER_FULL_SCREEN_VIDEOS =
            new BooleanSetting("hushfacebook_center_full_screen_videos", FALSE);

    /**
     * A video longer than two minutes that was left partway picks up where it was left, once, the
     * next time a player starts it ({@link app.morphe.extension.facebook.media.ResumePlayback}).
     * Starts off. Off or paused, nothing is saved or looked up and videos start as Facebook starts
     * them; the points already saved stay until they're 30 days old, and Facebook's start drops
     * them after that either way.
     */
    public static final BooleanSetting RESUME_LONG_VIDEOS =
            new BooleanSetting("hushfacebook_resume_long_videos", FALSE);

    /**
     * Keeps a list on the phone of the reels, videos and stories you watch, newest first, so one a
     * refresh took away can be opened again ({@link app.morphe.extension.facebook.media.RecentlyWatched},
     * #183). Starts off. Off or paused, nothing is added; what's listed stays for
     * {@link #RECENTLY_WATCHED_KEEP}.
     */
    public static final BooleanSetting RECENTLY_WATCHED =
            new BooleanSetting("hushfacebook_recently_watched", FALSE) {
                @Override
                protected void onValueChanged() {
                    RecentlyWatched.recordingChanged();
                }
            };

    /**
     * How long a Recently watched entry stays after it's watched, whether the list is recording or
     * not. 30 days to start with. A list an earlier build kept, when entries never expired, stays
     * until it's cleared instead, until another time is picked.
     */
    public static final EnumSetting<RecentlyWatched.Keep> RECENTLY_WATCHED_KEEP =
            new EnumSetting<RecentlyWatched.Keep>(RecentlyWatched.KEEP_KEY, RecentlyWatched.Keep.THIRTY_DAYS) {
                @Override
                protected void onValueChanged() {
                    RecentlyWatched.keepChanged();
                }
            };

    /**
     * With a reel or video paused, a tap on its right third steps it one frame forward and a tap on
     * its left third one frame back ({@link app.morphe.extension.facebook.media.FrameStep}). Starts
     * off. Off or paused, every tap goes to Facebook as before.
     */
    public static final BooleanSetting FRAME_STEP =
            new BooleanSetting("hushfacebook_frame_step", FALSE);

    /**
     * Two fingers on a profile's photo, a story, a feed video or a photo in Photo carousel's row zoom
     * it in place, and it springs back when they lift ({@link
     * app.morphe.extension.facebook.media.PinchZoom}, #220). Starts off. Asked as each
     * second finger lands, so a change shows from the next pinch. Off or paused, every touch goes to
     * Facebook as before.
     */
    public static final BooleanSetting PINCH_TO_ZOOM =
            new BooleanSetting("hushfacebook_pinch_to_zoom", FALSE);

    /**
     * Pinch to zoom's second switch (#220): a zoom stays where the fingers left it when they lift,
     * until a tap, a pinch back to 1x, Back or the window losing focus lets it go. Starts off. Asked
     * as each zoom starts.
     */
    public static final BooleanSetting PINCH_TO_ZOOM_KEEP =
            new BooleanSetting("hushfacebook_pinch_to_zoom_keep", FALSE, Setting.parent(PINCH_TO_ZOOM));

    /**
     * Pinch to zoom's third switch (#220): two quick taps on a profile photo zoom it to twice its size
     * about the tap, and two more put it back. The first tap waits the double tap time, so a lone tap
     * still opens the photo, just that much later. A finger that lands while the profile is still
     * scrolling stops it at once and never waits. Never in the story viewer. Starts off. Asked on
     * each tap, so a change shows from the next one.
     */
    public static final BooleanSetting PINCH_TO_ZOOM_DOUBLE_TAP =
            new BooleanSetting("hushfacebook_pinch_to_zoom_double_tap", FALSE, Setting.parent(PINCH_TO_ZOOM));

    /**
     * Pinch to zoom's fourth switch (#220): the screen around a zoomed photo or story darkens as the
     * zoom grows. Off, the zoom grows over the screen as it is. Starts on, the way zooms always
     * looked. Asked as each zoom starts.
     */
    public static final BooleanSetting PINCH_TO_ZOOM_DIM =
            new BooleanSetting("hushfacebook_pinch_to_zoom_dim", TRUE, Setting.parent(PINCH_TO_ZOOM));

    /**
     * Pinch to zoom's fifth switch (#220): a zoomed video keeps playing in the zoom, its copy redrawn
     * from the video's own surface, and a press and hold on a kept zoom pauses the video and plays it
     * again. A story plays on too, unless it was held before the pinch, which zooms the frame it
     * paused on. Off, a video zooms on the frame it showed. Asked as each zoom starts.
     */
    public static final BooleanSetting PINCH_TO_ZOOM_LIVE =
            new BooleanSetting("hushfacebook_pinch_to_zoom_live", FALSE, Setting.parent(PINCH_TO_ZOOM));

    /**
     * Videos, reels and video stories start at the quality in {@link #PLAYBACK_QUALITY}, through
     * the same per-video choice Facebook's own quality menu makes
     * ({@link app.morphe.extension.facebook.media.QualityChoice}). A pick in that menu still wins
     * for its video. With the quality left as Facebook's, the switch changes nothing, and off or
     * paused, Facebook picks the quality as it plays.
     */
    public static final BooleanSetting DEFAULT_PLAYBACK_QUALITY =
            new BooleanSetting("hushfacebook_default_playback_quality", FALSE);

    /**
     * A playing reel or full-screen video shrinks into a window when you leave Facebook, through the picture-in-picture
     * Facebook ships for its Reels viewer behind server flags
     * ({@link app.morphe.extension.facebook.media.PictureInPicture}). Starts off. Off or
     * paused, Facebook decides as before.
     */
    public static final BooleanSetting PICTURE_IN_PICTURE =
            new BooleanSetting("hushfacebook_picture_in_picture", FALSE);

    /**
     * With Picture-in-picture on, leaving Facebook opens no window and only the Picture-in-picture
     * row in a reel's More menu does (#44,
     * {@link app.morphe.extension.facebook.media.PictureInPictureRow}). Starts off, so leaving
     * still opens it. Off, paused or with Picture-in-picture off, it changes nothing.
     */
    public static final BooleanSetting PICTURE_IN_PICTURE_FROM_MENU =
            new BooleanSetting("hushfacebook_picture_in_picture_from_menu", FALSE);

    /**
     * With Picture-in-picture on, a small button floats over the screen the window is armed on, and
     * its tap opens the window right away (#44,
     * {@link app.morphe.extension.facebook.media.PictureInPictureButton}). Starts off. Off, paused
     * or with Picture-in-picture off, no button is drawn.
     */
    public static final BooleanSetting PICTURE_IN_PICTURE_BUTTON =
            new BooleanSetting("hushfacebook_pip_button", FALSE);

    /**
     * A video you started keeps its sound after you leave Facebook, in Facebook's own background
     * player with its notification ({@link app.morphe.extension.facebook.media.BackgroundPlay}). Off
     * by default: it keeps a player going once Facebook is out of sight, which not everyone wants.
     */
    public static final BooleanSetting KEEP_PLAYING_IN_BACKGROUND =
            new BooleanSetting("hushfacebook_keep_playing_in_background", FALSE);

    /**
     * The picture-in-picture window gets a headphones button that closes the window and keeps the
     * sound going in Facebook's background player, with its notification (#455,
     * {@link app.morphe.extension.facebook.media.BackgroundPlay}). Works with {@link
     * #KEEP_PLAYING_IN_BACKGROUND} on or off. Starts off. Off or paused, the window has Facebook's
     * buttons alone.
     */
    public static final BooleanSetting BACKGROUND_PLAY_BUTTON =
            new BooleanSetting("hushfacebook_background_play_button", FALSE);

    /**
     * HDR videos and photos stay in the screen's usual range instead of turning it up to full
     * brightness ({@link app.morphe.extension.facebook.media.HdrBrightness}). Starts off. Off
     * or paused, Facebook asks for its HDR window again.
     */
    public static final BooleanSetting TURN_OFF_HDR_BRIGHTNESS =
            new BooleanSetting("hushfacebook_turn_off_hdr_brightness", FALSE);

    /**
     * An HDR video Facebook also serves in SDR at the same height or taller plays the SDR version
     * ({@link app.morphe.extension.facebook.media.SdrRenditions}, #66). Works with {@link
     * #TURN_OFF_HDR_BRIGHTNESS} on or off. Starts off. Off or paused, the next video's manifest
     * reaches the player whole.
     */
    public static final BooleanSetting PREFER_SDR_RENDITIONS =
            new BooleanSetting("hushfacebook_prefer_sdr_renditions", FALSE);

    /**
     * Videos and reels draw into a TextureView, composed with the rest of the screen, instead of a
     * SurfaceView of their own ({@link app.morphe.extension.facebook.media.TextureVideo}, #308).
     * Starts off. Off or paused, the next player Facebook builds picks its own view again.
     */
    public static final BooleanSetting DRAW_VIDEOS_WITH_THE_SCREEN =
            new BooleanSetting("hushfacebook_draw_videos_with_the_screen", FALSE);

    /**
     * Videos, reels and stories play past the silent stretches in their sound ({@link
     * app.morphe.extension.facebook.media.SkipSilence}). Starts off. Off or paused, each player
     * gets Facebook's own flag again from its next seek or video.
     */
    public static final BooleanSetting SKIP_SILENT_PARTS =
            new BooleanSetting("hushfacebook_skip_silent_parts", FALSE);

    /**
     * Feed videos start muted in every new session, and only a tap or the volume up key turns their
     * sound on ({@link app.morphe.extension.facebook.media.FeedSound}). Starts off. Off or paused,
     * Facebook sets the feed's sound as before. A restart starts the new session that mutes it.
     */
    public static final BooleanSetting KEEP_FEED_VIDEOS_MUTED =
            new BooleanSetting("hushfacebook_keep_feed_videos_muted", FALSE, true);

    /**
     * Stories play with their sound off until you turn the sound on with a speaker tap or the
     * volume up key, and again from each new session
     * ({@link app.morphe.extension.facebook.media.StorySound}, #261). Works with
     * {@link #KEEP_FEED_VIDEOS_MUTED} on or off.
     */
    public static final BooleanSetting MUTE_STORIES =
            new BooleanSetting("hushfacebook_mute_stories", FALSE);

    /**
     * A video opened from the feed plays with its sound as Facebook picks, and back in the feed the
     * sound is what it was before you opened it, however long you watched
     * ({@link app.morphe.extension.facebook.media.FeedSound#viewerSoundTime}, #350). Off, a long
     * watch with sound turns the feed's sound on. Works with {@link #KEEP_FEED_VIDEOS_MUTED} on or off.
     */
    public static final BooleanSetting RESTORE_FEED_SOUND =
            new BooleanSetting("hushfacebook_restore_feed_sound", FALSE);

    /**
     * A reel's progress bar stays full size, thumb and all, and a full-screen video's controls stay
     * until a tap hides them ({@link app.morphe.extension.facebook.media.ProgressBar}). Off by
     * default. Off or paused, Facebook shrinks the bar and fades the controls as before.
     */
    public static final BooleanSetting KEEP_PROGRESS_BAR =
            new BooleanSetting("hushfacebook_keep_progress_bar", FALSE);

    /**
     * With {@link #KEEP_PROGRESS_BAR} on, a reel's elapsed and total time stay up above the bar and
     * keep counting ({@link app.morphe.extension.facebook.media.ProgressBar}). Starts on. Off, the
     * time shows only while you drag the bar, as Facebook has it.
     */
    public static final BooleanSetting KEEP_PROGRESS_BAR_TIME =
            new BooleanSetting("hushfacebook_keep_progress_bar_time", TRUE);

    /**
     * Pressing a reel's progress bar leaves its name, caption and buttons on screen
     * ({@link app.morphe.extension.facebook.media.ProgressBar}). Off by default. Off or paused,
     * Facebook clears them until you let go, as before.
     */
    public static final BooleanSetting KEEP_REEL_OVERLAY =
            new BooleanSetting("hushfacebook_keep_reel_overlay", FALSE);

    /**
     * A tap anywhere on a reel's or video's progress bar jumps there, where Facebook's bar only seeks
     * on a drag from its thumb ({@link app.morphe.extension.facebook.media.TapToSeek}).
     * Works with {@link #KEEP_PROGRESS_BAR} on or off. Off by default, and asked on every press, so
     * no restart.
     */
    public static final BooleanSetting TAP_TO_SEEK =
            new BooleanSetting("hushfacebook_tap_to_seek", FALSE);

    /**
     * Facebook's own text, React Native screens' included, drawn in the font {@link #FONT_SOURCE}
     * names instead of Meta's Optimistic, at the same weight and slant. A typeface already on
     * screen keeps its font until Facebook restarts, so a change brings up the restart row.
     */
    public static final BooleanSetting USE_SYSTEM_FONT =
            new BooleanSetting("hushfacebook_use_system_font", FALSE, true);

    /**
     * Emoji drawn with the phone's own emoji font instead of the one Meta downloads. An emoji
     * already laid out keeps its look until its text is drawn again, and the quick emoji picker
     * until Facebook restarts, so a change brings up the restart row. Reactions and stickers are
     * pictures, not text, and don't change.
     */
    public static final BooleanSetting USE_SYSTEM_EMOJI =
            new BooleanSetting("hushfacebook_use_system_emoji", FALSE, true);

    /** Web links leave Facebook's in-app browser for the default browser. */
    public static final BooleanSetting OPEN_LINKS_EXTERNALLY =
            new BooleanSetting("hushfacebook_open_links_externally", TRUE);

    /**
     * Facebook's own browser builds neither of its link history writers
     * ({@link app.morphe.extension.facebook.misc.LinkHistory}), so a link that opens in it isn't
     * recorded for your link history or sent as a navigation event. On by default. A change applies
     * to the next link opened.
     */
    public static final BooleanSetting HOLD_LINK_HISTORY =
            new BooleanSetting("hushfacebook_hold_link_history", TRUE);

    /** The tracking keys come off the links Facebook hands out when someone shares. */
    public static final BooleanSetting SANITIZE_SHARING_LINKS =
            new BooleanSetting("hushfacebook_sanitize_sharing_links", TRUE);

    /**
     * A shared post's link is its own address, not the facebook.com/share/ link Facebook makes for
     * each share (#98). Copy link and the share sheet's other link shares take theirs from the
     * same place.
     */
    public static final BooleanSetting SHARE_POST_OWN_LINK =
            new BooleanSetting("hushfacebook_share_post_own_link", FALSE);

    /**
     * Facebook's own update prompts, which a build signed with the patcher's key can't act on:
     * the Meta App Manager promotions, the push that has the manager look for an update, and the
     * chat promotions aimed at versions below a ceiling.
     */
    public static final BooleanSetting STOP_UPDATE_PROMPTS =
            new BooleanSetting("hushfacebook_stop_update_prompts", TRUE);

    /**
     * The "Get the Messenger app" card at the top of Facebook's own Chats, while Messenger is
     * installed. Facebook drops it by itself only for a Messenger signed with its own key, which a
     * re-signed Facebook never matches. Without Messenger the card stays, and so does its way to
     * install it.
     */
    public static final BooleanSetting HIDE_GET_MESSENGER_CARD =
            new BooleanSetting("hushfacebook_hide_get_messenger_card", TRUE);

    /**
     * The row of friends' notes and active friends above the chats in Facebook's own Chats. Starts
     * off. The chats, search and new messages stay.
     */
    public static final BooleanSetting HIDE_CHAT_NOTES_TRAY =
            new BooleanSetting("hushfacebook_hide_chat_notes_tray", FALSE);

    /**
     * The promotional banners at the top of Facebook's own Chats, like the one asking you to turn
     * on notifications. Starts off.
     */
    public static final BooleanSetting HIDE_CHAT_PROMOTIONS =
            new BooleanSetting("hushfacebook_hide_chat_promotions", FALSE);

    /**
     * The Meta AI button beside the search box in Facebook's own Chats, at the top of the list and
     * on the search screen. Starts off. The search box and chats with Meta AI stay.
     */
    public static final BooleanSetting HIDE_CHAT_META_AI_BUTTON =
            new BooleanSetting("hushfacebook_hide_chat_meta_ai_button", FALSE);

    /**
     * A tap on the Messenger icon at the top of Facebook opens the Messenger app, while it's
     * installed, instead of Facebook's own Chats. Starts off. Without Messenger, Chats opens as it
     * always did.
     */
    public static final BooleanSetting OPEN_MESSENGER_APP =
            new BooleanSetting("hushfacebook_open_messenger_app", FALSE);

    /**
     * While Messenger is installed and enabled, Facebook doesn't ring for calls or post
     * notifications for new messages, reactions, message requests and missed calls, so only
     * Messenger does. Starts off. Without Messenger, or with it disabled when Facebook starts,
     * Facebook rings and notifies as it always did. A restart rebuilds Facebook's MQTT connection
     * without its VOIP capability.
     */
    public static final BooleanSetting LEAVE_CALLS_AND_CHATS_TO_MESSENGER =
            new BooleanSetting("hushfacebook_leave_calls_and_chats_to_messenger", FALSE, true);

    /**
     * While Meta's Messenger is installed and enabled, chats open in Messenger instead of inside
     * Facebook, the Message button on a profile included (#392). Starts off. Facebook keeps its
     * Messenger lookup for the process, so the switch restarts it. Without Messenger, chats stay in
     * Facebook.
     */
    public static final BooleanSetting OPEN_CHATS_IN_MESSENGER =
            new BooleanSetting("hushfacebook_open_chats_in_messenger", FALSE, true);

    /**
     * The Upgrades section of Facebook's Menu, the group Facebook types UPSELL, with its offers.
     * Only that group goes: Settings, Help and support and the rest of the Menu stay. A Menu built
     * before the switch changed keeps the group on screen even though its sections ask again (#184),
     * so the change waits for a restart.
     */
    public static final BooleanSetting HIDE_MENU_UPGRADES =
            new BooleanSetting("hushfacebook_hide_menu_upgrades", TRUE, true);

    /**
     * The Also from Meta section of Facebook's Menu, the group Facebook types
     * PRODUCTS_FROM_FACEBOOK: its links to Meta's other apps and its cards for Meta's devices.
     * Like Upgrades, it waits for a restart.
     */
    public static final BooleanSetting HIDE_MENU_ALSO_FROM_META =
            new BooleanSetting("hushfacebook_hide_menu_also_from_meta", TRUE, true);

    /**
     * The Help and support section of Facebook's Menu, the group Facebook types HELP, with its
     * header and rows (issue #224). Off until it's turned on. Like Upgrades, it waits for a restart.
     */
    public static final BooleanSetting HIDE_MENU_HELP =
            new BooleanSetting("hushfacebook_hide_menu_help", FALSE, true);

    /**
     * The Your shortcuts row of Facebook's Menu, the groups and Pages Facebook pins under the profile
     * card, which come back after being hidden one by one (issue #445). Facebook builds it either as
     * a folder in the server section of the group it types PROFILE or inside that group's Bloks view.
     * Off until it's turned on. Like Upgrades, it waits for a restart.
     */
    public static final BooleanSetting HIDE_MENU_SHORTCUTS =
            new BooleanSetting("hushfacebook_hide_menu_shortcuts", FALSE, true);

    /**
     * The Create Facebook Page row under the profile card at the top of Facebook's Menu, drawn inside
     * the Bloks view of the group Facebook types PROFILE (issue #445). Off until it's turned on. Like
     * Upgrades, it waits for a restart.
     */
    public static final BooleanSetting HIDE_MENU_CREATE_PAGE =
            new BooleanSetting("hushfacebook_hide_menu_create_page", FALSE, true);

    /**
     * Keeps the Settings and privacy section of Facebook's Menu, the group Facebook types SETTINGS,
     * open under its header instead of folded up, so its rows show without a tap (issue #224). Off
     * until it's turned on. A Menu already built keeps its own state, so it waits for a restart.
     */
    public static final BooleanSetting KEEP_MENU_SETTINGS_EXPANDED =
            new BooleanSetting("hushfacebook_keep_menu_settings_expanded", FALSE, true);

    /**
     * The Muse card in Facebook's Menu, Meta's ad for its AI agent app, which reads as dismissed
     * the way Facebook's own Dismiss leaves it.
     */
    public static final BooleanSetting HIDE_MENU_MUSE =
            new BooleanSetting("hushfacebook_hide_menu_muse", TRUE);

    /**
     * The Meta AI shortcut in Facebook's Menu, beside Feeds, Marketplace, Reels and Groups, taken
     * out of the bookmarks list it's drawn from ({@link app.morphe.extension.facebook.menu.MenuShortcuts}).
     * Off until it's turned on, like the other ways into Meta AI that some people use.
     */
    public static final BooleanSetting HIDE_MENU_META_AI =
            new BooleanSetting("hushfacebook_hide_menu_meta_ai", FALSE);

    /**
     * Edits outside the Menu: the button and badge in the Reels composer's header, and the Edits
     * pill under feed videos, which the feed's requests stop asking the server for
     * ({@link app.morphe.extension.facebook.misc.MetaUpsells}). Off until it's turned on.
     */
    public static final BooleanSetting HIDE_EDITS_UPSELLS =
            new BooleanSetting("hushfacebook_hide_edits_upsells", FALSE);

    /** The composer's onboarding for cross-posting to Threads. Off until it's turned on. */
    public static final BooleanSetting HIDE_THREADS_CROSS_POSTING =
            new BooleanSetting("hushfacebook_hide_threads_cross_posting", FALSE);

    /** The share sheet's button for sharing to Threads. Off until it's turned on. */
    public static final BooleanSetting HIDE_THREADS_SHARE_BUTTON =
            new BooleanSetting("hushfacebook_hide_threads_share_button", FALSE);

    /**
     * The share sheet's Muse button, Meta's AI agent app, wherever Facebook's one list of share
     * items reaches. Off until it's turned on.
     */
    public static final BooleanSetting HIDE_MUSE_SHARE_BUTTON =
            new BooleanSetting("hushfacebook_hide_muse_share_button", FALSE);

    /**
     * The share sheet items Share sheet items keeps out, as the item enum's constant names separated
     * by commas. Empty, every sheet is Facebook's own.
     */
    public static final StringSetting HIDDEN_SHARE_ITEMS =
            new StringSetting("hushfacebook_hidden_share_items", "");

    /**
     * The share sheet item types Facebook has offered on this install, in the order they first
     * came, so the list of items to hide shows real ones. Written by the hook, never by a person.
     */
    public static final StringSetting SEEN_SHARE_ITEMS =
            new StringSetting("hushfacebook_seen_share_items", "");

    /**
     * The Menu tiles kept out of the shortcut list (#153), as the first word of each tile's deep link
     * ({@code marketplace}, {@code gaming}), separated by commas. Empty, the Menu is Facebook's own.
     */
    public static final StringSetting HIDDEN_MENU_TILES =
            new StringSetting("hushfacebook_hidden_menu_tiles", "");

    /**
     * The Menu tiles Facebook has shown on this install, a {@code key=name} line each in the Menu's
     * order, so the list of tiles to hide shows this Menu's own in its words. Written by the hook,
     * never by a person.
     */
    public static final StringSetting SEEN_MENU_TILES =
            new StringSetting("hushfacebook_seen_menu_tiles", "");

    /**
     * The share sheet's Send to group button, which makes a new group chat of the people picked,
     * and its new-group row, header icon and search button. Send separately stays. Off until it's
     * turned on.
     */
    public static final BooleanSetting HIDE_SHARE_GROUP_BUTTONS =
            new BooleanSetting("hushfacebook_hide_share_group_buttons", FALSE);

    /**
     * The share sheet's row of round profile pictures of people to send the post to, and its
     * loading placeholder. The search box and the other ways to share stay. Off until it's turned on.
     */
    public static final BooleanSetting HIDE_SHARE_PEOPLE_ROW =
            new BooleanSetting("hushfacebook_hide_share_people_row", FALSE);

    /**
     * The Meta Verified offer sheet after you post, and the Meta Verified label under some posts'
     * headers. Off until it's turned on.
     */
    public static final BooleanSetting HIDE_META_VERIFIED_UPSELLS =
            new BooleanSetting("hushfacebook_hide_meta_verified_upsells", FALSE);

    /** The avatar sticker upsells in comments and Facebook's promotion slots. Off until it's turned on. */
    public static final BooleanSetting HIDE_AVATAR_UPSELLS =
            new BooleanSetting("hushfacebook_hide_avatar_upsells", FALSE);

    /**
     * Meta AI's Imagine: the Imagine me button under posts, the post composer's Imagine and Create
     * story's Imagine tile. Off until it's turned on.
     */
    public static final BooleanSetting HIDE_META_AI_IMAGINE =
            new BooleanSetting("hushfacebook_hide_meta_ai_imagine", FALSE);

    /**
     * The other Meta AI buttons the post call-to-action selector can put under a post: AI styles
     * and Meta AI's deep dive and chat starter. Off until it's turned on.
     */
    public static final BooleanSetting HIDE_META_AI_POST_BUTTONS =
            new BooleanSetting("hushfacebook_hide_meta_ai_post_buttons", FALSE);

    /**
     * Meta AI in Facebook's search: the answer a results page adds on top, the Meta AI modules and
     * "Ask Meta AI" prompts among the results, and the suggestions Facebook's server sets to open in
     * Meta AI. People, groups, pages and posts stay, and so do the Meta AI button and the results
     * page's own Meta AI tab.
     */
    public static final BooleanSetting HIDE_META_AI_IN_SEARCH =
            new BooleanSetting("hushfacebook_hide_meta_ai_in_search", TRUE);

    /**
     * What you search for, from the main search bar, Reels or Watch, stops being added to your
     * Facebook search history: the recent searches store takes Facebook's own "don't add this one"
     * branch ({@link app.morphe.extension.facebook.search.SearchHistory}, #196). Searches already
     * in the history stay. Off by default.
     */
    public static final BooleanSetting DONT_SAVE_SEARCH_HISTORY =
            new BooleanSetting("hushfacebook_dont_save_search_history", FALSE);

    /**
     * The Posted date filter in post search opens Facebook's own from and to month picker, with a
     * day wheel for each end, in place of the list of years
     * ({@link app.morphe.extension.facebook.search.SearchDateRange}, #279).
     * Off by default.
     */
    public static final BooleanSetting SEARCH_DATE_RANGE =
            new BooleanSetting("hushfacebook_search_date_range", FALSE);

    /**
     * The Posts chip goes back in the row over search results, after Reels, and Facebook builds it
     * like its other tabs ({@link app.morphe.extension.facebook.search.SearchPostsTab}, #339). On by
     * default: it only adds the chip Facebook stopped listing.
     */
    public static final BooleanSetting SEARCH_POSTS_TAB =
            new BooleanSetting("hushfacebook_search_posts_tab", TRUE);

    /**
     * A Saved button at the end of the search bar opens Facebook's Saved screen
     * ({@link app.morphe.extension.facebook.search.SearchSavedButton}, #433). Off by default. The
     * button goes in as Facebook builds the bar, so turning it on takes a restart.
     */
    public static final BooleanSetting SEARCH_SAVED_BUTTON =
            new BooleanSetting("hushfacebook_search_saved_button", FALSE, true);

    /**
     * The For you section of searches Facebook suggests, on the search screen before anything is
     * typed (#80), found by the module name Facebook gives its entry. Recent searches, the search box
     * and the suggestions while typing stay. Part of Hide suggested and promoted posts, off until picked.
     */
    public static final BooleanSetting HIDE_SEARCH_FOR_YOU =
            new BooleanSetting("hushfacebook_hide_search_for_you", FALSE);

    /**
     * People you may know on the search screen before anything is typed (#80), found by the module
     * name Facebook gives its entry, the same way as For you. Recent searches, the search box and the
     * suggestions while typing stay. Part of Hide suggested and promoted posts, off until picked.
     */
    public static final BooleanSetting HIDE_SEARCH_PEOPLE_YOU_MAY_KNOW =
            new BooleanSetting("hushfacebook_hide_search_people_you_may_know", FALSE);

    /**
     * Push notifications Facebook types TOP_TRENDING_VIDEO or PERSONALIZED_REELS: trending videos
     * and reels it picked for you. Off by default, like every notification switch: each one drops a
     * whole kind of notification, so it's yours to turn on.
     */
    public static final BooleanSetting BLOCK_TRENDING_VIDEO_NOTIFICATIONS =
            new BooleanSetting("hushfacebook_block_trending_video_notifications", FALSE);

    /** Push notifications Facebook types ONTHISDAY: its "On this day" memories. */
    public static final BooleanSetting BLOCK_MEMORY_NOTIFICATIONS =
            new BooleanSetting("hushfacebook_block_memory_notifications", FALSE);

    /** Push notifications Facebook types BIRTHDAY_REMINDER: a friend's birthday is today. */
    public static final BooleanSetting BLOCK_BIRTHDAY_NOTIFICATIONS =
            new BooleanSetting("hushfacebook_block_birthday_notifications", FALSE);

    /**
     * Push notifications Facebook types GROUP_HIGHLIGHTS, GROUP_NF_HIGHLIGHTS, PAGE_HIGHLIGHTS or
     * CREATOR_HIGHLIGHTS: digests of what happened in groups, pages and creators you follow.
     * Comments, replies and mentions in groups are other kinds and still come through.
     */
    public static final BooleanSetting BLOCK_HIGHLIGHT_NOTIFICATIONS =
            new BooleanSetting("hushfacebook_block_highlight_notifications", FALSE);

    /** Push notifications Facebook types PYMK_EMAIL: friend suggestions. Friend requests still come through. */
    public static final BooleanSetting BLOCK_PEOPLE_YOU_MAY_KNOW_NOTIFICATIONS =
            new BooleanSetting("hushfacebook_block_people_you_may_know_notifications", FALSE);

    /** Push notifications Facebook types PLACE_FEED_NEARBY, NEAR_SAVED_PLACE or WEATHER_NOWCAST. */
    public static final BooleanSetting BLOCK_NEARBY_NOTIFICATIONS =
            new BooleanSetting("hushfacebook_block_nearby_notifications", FALSE);

    /**
     * Push notifications typed FB_REGISTRATION_REMINDER: "finish setting up your account" reminders
     * that keep coming to a phone already signed in. Facebook's server sends the type, which its own
     * NotificationType doesn't name, so Facebook would show them under a generic kind.
     */
    public static final BooleanSetting BLOCK_ACCOUNT_SETUP_NOTIFICATIONS =
            new BooleanSetting("hushfacebook_block_account_setup_notifications", FALSE);

    /**
     * Push notifications Facebook types GROUP_ACTIVITY: new activity in your groups. Comments,
     * replies and mentions in groups are other kinds and still come through.
     */
    public static final BooleanSetting BLOCK_GROUP_ACTIVITY_NOTIFICATIONS =
            new BooleanSetting("hushfacebook_block_group_activity_notifications", FALSE);

    /** Push notifications Facebook types EVENT_INVITE: invites to events. */
    public static final BooleanSetting BLOCK_EVENT_NOTIFICATIONS =
            new BooleanSetting("hushfacebook_block_event_notifications", FALSE);

    /** Push notifications Facebook types LIVE_VIDEO or LIVE_VIDEO_EXPLICIT: someone is live. */
    public static final BooleanSetting BLOCK_LIVE_VIDEO_NOTIFICATIONS =
            new BooleanSetting("hushfacebook_block_live_video_notifications", FALSE);

    /**
     * Push notifications Facebook types LIKE or FEEDBACK_REACTION_GENERIC: likes and reactions to
     * your posts and comments. Comments themselves are other kinds and still come through.
     */
    public static final BooleanSetting BLOCK_REACTION_NOTIFICATIONS =
            new BooleanSetting("hushfacebook_block_reaction_notifications", FALSE);

    /**
     * The notification switches above block their kinds only between {@link #QUIET_HOURS_FROM}
     * and {@link #QUIET_HOURS_UNTIL}, and the rest of the day those kinds come through. Off by
     * default, when a switch blocks its kinds all day.
     */
    public static final BooleanSetting NOTIFICATION_QUIET_HOURS =
            new BooleanSetting("hushfacebook_notification_quiet_hours", FALSE);

    /**
     * Once a day, when Facebook starts, ask api.github.com whether a newer Hushfacebook release is
     * out, and say so on the settings screen ({@link ReleaseCheck}). It's the settings entry's own
     * switch rather than a patch's, so every build has it ({@link PatchFamily#ENTRY_SWITCHES}). Off
     * by default, and a settings file never carries it: a file shouldn't be able to put a phone
     * online.
     */
    public static final BooleanSetting CHECK_FOR_RELEASES =
            new BooleanSetting("hushfacebook_check_releases", FALSE);

    /** A launcher shortcut to Saved, added only when it won't displace an existing entry. */
    public static final BooleanSetting SAVED_SHORTCUT =
            new BooleanSetting("hushfacebook_saved_shortcut", FALSE);

    /**
     * A launcher shortcut and a Menu row to the videos you've watched, reels included (Facebook's
     * Activity log, Videos you've watched), added only when the shortcut won't displace an existing
     * entry.
     */
    public static final BooleanSetting WATCH_HISTORY_SHORTCUT =
            new BooleanSetting("hushfacebook_watch_history_shortcut", FALSE);

    /**
     * A launcher shortcut and a Menu row to Marketplace's free listings (the web's Free Stuff
     * category, which Facebook's link handler opens in Marketplace), added only when the shortcut
     * won't displace an existing entry (Discussion #52).
     */
    public static final BooleanSetting FREE_STUFF_SHORTCUT =
            new BooleanSetting("hushfacebook_free_stuff_shortcut", FALSE);

    /**
     * The Hushfacebook settings shortcut in the menu a long press on Facebook's launcher icon opens
     * ({@link SettingsEntry}). On by default, as it always was. Off, the shortcut goes at once and
     * isn't published again; the Menu row and the logo's long press still open the screen (#146).
     * Kept while paused, since it's a way back to the switch that resumes Hushfacebook.
     */
    public static final BooleanSetting SETTINGS_SHORTCUT =
            new BooleanSetting("hushfacebook_settings_shortcut", TRUE);

    /**
     * A long press on the Facebook logo opens the settings ({@link SettingsEntry}). On by default,
     * as it always was. Off, the press is Facebook's again. Kept while paused, like the shortcut.
     * Whichever of the three ways in is the last one on can't be turned off (#146).
     */
    public static final BooleanSetting SETTINGS_LOGO_PRESS =
            new BooleanSetting("hushfacebook_settings_logo_press", TRUE);

    /**
     * The Hushfacebook row at the end of Settings and privacy in Facebook's Menu
     * ({@link app.morphe.extension.facebook.menu.MenuSettingsRow}). On by default. Off, the row
     * leaves the list. Kept while paused, and never the last way in turned off (#146).
     */
    public static final BooleanSetting SETTINGS_MENU_ROW =
            new BooleanSetting("hushfacebook_settings_menu_row", TRUE);

    /**
     * A cold start, and a return after {@link #APP_LOCK_AFTER}, ask for the phone's screen lock
     * before Facebook shows ({@link app.morphe.extension.facebook.misc.AppLock}). The settings
     * entry's own switch, so every build has it ({@link PatchFamily#ENTRY_SWITCHES}). Off by default.
     * It keeps its value while Hushfacebook is paused, see the block after {@link #APP_LOCK_AFTER}.
     */
    public static final BooleanSetting APP_LOCK =
            new BooleanSetting("hushfacebook_app_lock", FALSE);

    /**
     * A Java crash that ends the app also goes to Download/Hushfacebook as a text file
     * ({@link CrashFile}), so it can be sent with a bug report when Facebook won't open. The
     * settings entry's own switch, so every build has it ({@link PatchFamily#ENTRY_SWITCHES}). On
     * by default: the file holds only what the diagnostic report's crash section shows, redacted
     * the same way. Like the other switches it answers off while Hushfacebook is paused.
     */
    public static final BooleanSetting SAVE_CRASH_FILE =
            new BooleanSetting("hushfacebook_save_crash_file", TRUE);

    /**
     * A few seconds after you leave Facebook, its tasks leave the recent apps and its processes end
     * ({@link app.morphe.extension.facebook.misc.CloseWhenLeft}, #202). The settings entry's own
     * switch, so every build has it ({@link PatchFamily#ENTRY_SWITCHES}). Off by default: the next
     * start is a cold one, and notifications that need Facebook running can wait for it.
     */
    public static final BooleanSetting CLOSE_WHEN_LEFT =
            new BooleanSetting("hushfacebook_close_when_left", FALSE);

    /**
     * The people on Facebook's Blocking page show without their profile pictures
     * ({@link app.morphe.extension.facebook.misc.BlockedListPictures}, #361). The settings entry's
     * own switch, so every build has it ({@link PatchFamily#ENTRY_SWITCHES}). Off by default.
     */
    public static final BooleanSetting HIDE_BLOCKED_LIST_PICTURES =
            new BooleanSetting("hushfacebook_hide_blocked_list_pictures", FALSE);

    /**
     * Opening Facebook from its icon puts a cover that counts down {@link #OPENING_PAUSE_LENGTH}
     * over its first screen ({@link app.morphe.extension.facebook.misc.OpeningPause}). The settings
     * entry's own switch, so every build has it ({@link PatchFamily#ENTRY_SWITCHES}). Off by default.
     */
    public static final BooleanSetting OPENING_PAUSE =
            new BooleanSetting("hushfacebook_opening_pause", FALSE);

    /**
     * Browsing for {@link #BREAK_AFTER} starts a break of {@link #BREAK_LENGTH} that covers Home,
     * Feeds, Video and Reels ({@link app.morphe.extension.facebook.misc.BreakTimer}). The settings
     * entry's own switch, so every build has it ({@link PatchFamily#ENTRY_SWITCHES}). Off by default.
     */
    public static final BooleanSetting TAKE_BREAKS =
            new BooleanSetting("hushfacebook_take_breaks", FALSE);

    /**
     * {@link #TAKE_BREAKS} also starts a break once 20 different reels have each played on for
     * three seconds, whichever comes first (#248). Counted from Resume long videos' player hook, so
     * it's in that family. Off by default.
     */
    public static final BooleanSetting BREAK_AFTER_REELS =
            new BooleanSetting("hushfacebook_break_after_reels", FALSE, Setting.parent(TAKE_BREAKS));

    static {
        // The crash handler is the shared library's, which can't name this class.
        CrashFile.useSwitch(SAVE_CRASH_FILE);
    }

    /**
     * The story viewer's menu offers Save on anyone's story, and Save runs Hushfacebook's own
     * download. Off, only your own stories offer it, and it's Facebook's own save.
     */
    public static final BooleanSetting DOWNLOAD_STORIES =
            new BooleanSetting("hushfacebook_download_stories", TRUE);

    /**
     * Save photo goes on every story whose media carries a picture beside its video, not only on
     * the ones Facebook marks as made from a photo (#38). See StoryPhoto.
     */
    public static final BooleanSetting SAVE_STORY_PHOTOS =
            new BooleanSetting("hushfacebook_save_story_photos", FALSE);

    /** The reel download actions in the sidebar and More menu. */
    public static final BooleanSetting DOWNLOAD_REELS =
            new BooleanSetting("hushfacebook_download_reels", TRUE);

    /**
     * The sidebar button alone (#244). Starts on to keep the existing layout. Turning this off
     * leaves Download in More, while DOWNLOAD_REELS still enables both save entry points.
     */
    public static final BooleanSetting SHOW_REEL_DOWNLOAD_BUTTON =
            new BooleanSetting("hushfacebook_show_reel_download_button", TRUE, Setting.parent(DOWNLOAD_REELS));

    /**
     * A reel's Download, from the sidebar or More, adds the reel to a queue that saves one reel at a
     * time in the background, with Pause and Cancel (#449). Starts off, so a tap saves at once as
     * before. Every reel in the queue is one that was tapped. See ReelQueue.
     */
    public static final BooleanSetting QUEUE_REEL_SAVES =
            new BooleanSetting("hushfacebook_queue_reel_saves", FALSE, Setting.parent(DOWNLOAD_REELS));

    /**
     * A Cast button on reels, under where Download goes (#182). Its tap hands the reel to a casting
     * app the way {@link #FEED_VIDEO_CAST_BUTTON} does for feed videos. Starts off. It saves nothing,
     * so it stands apart from DOWNLOAD_REELS.
     */
    public static final BooleanSetting REEL_CAST_BUTTON =
            new BooleanSetting("hushfacebook_reel_cast_button", FALSE);

    /**
     * Copy caption in a reel's More sheet, which copies the reel's whole caption (#240). Starts on.
     * It saves nothing, so it stands apart from DOWNLOAD_REELS.
     */
    public static final BooleanSetting COPY_REEL_CAPTION =
            new BooleanSetting("hushfacebook_copy_reel_caption", TRUE);

    /**
     * The Download to phone item the video patch adds to the menu of a feed or Watch video. Off,
     * the menu is Facebook's own.
     */
    public static final BooleanSetting DOWNLOAD_VIDEOS =
            new BooleanSetting("hushfacebook_download_videos", FALSE);

    /**
     * Download any video's second switch: coming back to Facebook with a reel or video link
     * copied offers to download it, once per link. Off until you turn it on, and it needs
     * {@link #DOWNLOAD_VIDEOS} on too. Off, the clipboard is never read.
     */
    public static final BooleanSetting CLIPBOARD_DOWNLOAD =
            new BooleanSetting("hushfacebook_clipboard_download", FALSE);

    /**
     * Save photo in the photo viewer's menu for every photo, saved at its biggest size where
     * downloads go. Off, the item shows only where the poster allows it and saves through Facebook.
     */
    public static final BooleanSetting DOWNLOAD_PHOTOS =
            new BooleanSetting("hushfacebook_download_photos", TRUE);

    /**
     * Download any photo's second switch: Save photo in the three-dot menu of a post holding
     * photos, saving its photo, or each photo of a multi-photo post. Off until you turn it on, and
     * it needs {@link #DOWNLOAD_PHOTOS} on too.
     */
    public static final BooleanSetting POST_MENU_PHOTO_SAVE =
            new BooleanSetting("hushfacebook_post_menu_photo_save", FALSE);

    /**
     * Download any photo's third switch (#245, #248): Search photo in the three-dot menu of a post
     * holding one photo, handing that photo to the app picked in Android's chooser, such as Google
     * Lens, to look up what's in it or copy its text. Off until you turn it on, and it needs
     * {@link #DOWNLOAD_PHOTOS} on too.
     */
    public static final BooleanSetting POST_MENU_PHOTO_SEARCH =
            new BooleanSetting("hushfacebook_post_menu_photo_search", FALSE);

    /**
     * Moves Download to phone and Save photo from the end of a post's three-dot menu to the start of
     * its options list (issue #151). Facebook's sheet keeps the rows it tags itself above them, and
     * on photo posts that list is the More options page. The row under a post has no room for a
     * button, so the menu is where a post's download lives. Off until you turn it on.
     */
    public static final BooleanSetting POST_MENU_DOWNLOAD_FIRST =
            new BooleanSetting("hushfacebook_post_menu_download_first", FALSE);

    /**
     * Open in another app, a second item in the menu of a feed or Watch video that hands the video's
     * file to a player or casting app (issue #182). Off until you turn it on. Off, the menu is
     * Facebook's own (and the Download item's, if that switch is on).
     */
    public static final BooleanSetting OPEN_IN_ANOTHER_APP =
            new BooleanSetting("hushfacebook_open_in_another_app", FALSE);

    /**
     * A start from Facebook's launcher icon opens the tab in {@link #START_TAB} instead of the one
     * Facebook would choose. Notifications, links and shortcuts keep their own destination.
     */
    public static final BooleanSetting OPEN_ON_CHOSEN_TAB =
            new BooleanSetting("hushfacebook_open_on_chosen_tab", FALSE);

    /**
     * Home asks Facebook for its most recent feed where it would ask for the ranked one
     * ({@link app.morphe.extension.facebook.feed.FollowingHome}). Off by default. The Feeds tab's
     * filters keep their own feeds, and a change shows the next time Home loads its feed.
     */
    public static final BooleanSetting FOLLOWING_FEED_HOME =
            new BooleanSetting("hushfacebook_following_feed_home", FALSE);

    /**
     * Which of the Feeds tab's filters Home shows while {@link #FOLLOWING_FEED_HOME} is on. All is
     * the most recent feed. A filter Facebook's feed types don't have leaves Home on All.
     */
    public static final EnumSetting<FeedsSubtab> HOME_FEED =
            new EnumSetting<>("hushfacebook_home_feed", FeedsSubtab.ALL, Setting.parent(FOLLOWING_FEED_HOME));

    /**
     * The tab bar keeps Marketplace, Notifications and the profile or Menu tab, and a start from
     * the launcher icon opens Marketplace whatever {@link #START_TAB} says. Home with the news feed,
     * Video, Friends, Feeds, Groups, Gaming and Events go. Facebook builds the bar once, so a change
     * shows when it restarts.
     */
    public static final BooleanSetting MARKETPLACE_ONLY =
            new BooleanSetting("hushfacebook_marketplace_only", FALSE, true);

    /**
     * Like {@link #MARKETPLACE_ONLY}, with Events in Marketplace's place (#164): the tab bar keeps
     * Events, Notifications and the profile or Menu tab, and a start from the launcher icon opens
     * Events. Marketplace only wins when both are on, and this greys out under it. Facebook builds
     * the bar once, so a change shows when it restarts.
     */
    public static final BooleanSetting EVENTS_ONLY =
            new BooleanSetting("hushfacebook_events_only", FALSE, true, Setting.parentNot(MARKETPLACE_ONLY));

    /** Silences only recognized social promotions while Marketplace only is selected. */
    public static final BooleanSetting MARKETPLACE_QUIET_NOTIFICATIONS =
            new BooleanSetting("hushfacebook_marketplace_quiet_notifications", FALSE);

    /** Skip only confirmed feed warm-ups once the tab bar has applied Marketplace only. */
    public static final BooleanSetting MARKETPLACE_SKIP_FEED_PREFETCH =
            new BooleanSetting("hushfacebook_marketplace_skip_feed_prefetch", FALSE);

    /**
     * The Reels tab, which some accounts call Video, stays off the tab bar, and a start sent to
     * it by {@link #START_TAB} opens Home. Facebook builds the bar once, so a change shows when it
     * restarts. A Reels tab Facebook's own tab bar settings hide stays hidden either way.
     */
    public static final BooleanSetting HIDE_REELS_TAB =
            new BooleanSetting("hushfacebook_hide_reels_tab", FALSE, true);

    /**
     * The Reels tab, which some accounts call Video, shows no new-item dot or count
     * ({@link app.morphe.extension.facebook.navigation.ReelsTabDot}). Off or paused, Facebook's
     * count comes back when the tab bar next asks for it.
     */
    public static final BooleanSetting HIDE_REELS_TAB_DOT =
            new BooleanSetting("hushfacebook_hide_reels_tab_dot", TRUE);

    /*
     * The tabs Hide tab badges takes the dot and count off
     * ({@link app.morphe.extension.facebook.navigation.TabBadges}), each off until you pick it. The
     * tab bar asks for each count as it changes, so a change shows the next time it asks.
     */
    public static final BooleanSetting HIDE_HOME_TAB_BADGE =
            new BooleanSetting("hushfacebook_hide_home_tab_badge", FALSE);

    public static final BooleanSetting HIDE_FRIENDS_TAB_BADGE =
            new BooleanSetting("hushfacebook_hide_friends_tab_badge", FALSE);

    public static final BooleanSetting HIDE_MARKETPLACE_TAB_BADGE =
            new BooleanSetting("hushfacebook_hide_marketplace_tab_badge", FALSE);

    public static final BooleanSetting HIDE_NOTIFICATIONS_TAB_BADGE =
            new BooleanSetting("hushfacebook_hide_notifications_tab_badge", FALSE);

    public static final BooleanSetting HIDE_MENU_TAB_BADGE =
            new BooleanSetting("hushfacebook_hide_menu_tab_badge", FALSE);

    public static final BooleanSetting HIDE_GROUPS_TAB_BADGE =
            new BooleanSetting("hushfacebook_hide_groups_tab_badge", FALSE);

    /** Every tab but Reels and the six above: Feeds, Gaming, Events, Dating and the rest. */
    public static final BooleanSetting HIDE_OTHER_TAB_BADGES =
            new BooleanSetting("hushfacebook_hide_other_tab_badges", FALSE);

    /**
     * Facebook's own launcher badge writers put 0 on the app icon. Notifications still arrive. A
     * change shows the next time Facebook updates the badge.
     */
    public static final BooleanSetting HIDE_APP_ICON_COUNT =
            new BooleanSetting("hushfacebook_hide_app_icon_count", FALSE);

    /*
     * The tabs Hide tabs takes off the tab bar ({@link app.morphe.extension.facebook.navigation.HiddenTabs}),
     * each off until it's picked. Facebook builds the bar once, so a change shows when it restarts.
     */
    public static final BooleanSetting HIDE_FEEDS_TAB =
            new BooleanSetting("hushfacebook_hide_feeds_tab", FALSE, true);

    public static final BooleanSetting HIDE_FRIENDS_TAB =
            new BooleanSetting("hushfacebook_hide_friends_tab", FALSE, true);

    public static final BooleanSetting HIDE_MARKETPLACE_TAB =
            new BooleanSetting("hushfacebook_hide_marketplace_tab", FALSE, true);

    public static final BooleanSetting HIDE_GROUPS_TAB =
            new BooleanSetting("hushfacebook_hide_groups_tab", FALSE, true);

    public static final BooleanSetting HIDE_GAMING_TAB =
            new BooleanSetting("hushfacebook_hide_gaming_tab", FALSE, true);

    public static final BooleanSetting HIDE_EVENTS_TAB =
            new BooleanSetting("hushfacebook_hide_events_tab", FALSE, true);

    public static final BooleanSetting HIDE_DATING_TAB =
            new BooleanSetting("hushfacebook_hide_dating_tab", FALSE, true);

    public static final BooleanSetting HIDE_PROFESSIONAL_DASHBOARD_TAB =
            new BooleanSetting("hushfacebook_hide_professional_dashboard_tab", FALSE, true);

    public static final BooleanSetting HIDE_SAVED_TAB =
            new BooleanSetting("hushfacebook_hide_saved_tab", FALSE, true);

    public static final BooleanSetting HIDE_AD_CENTER_TAB =
            new BooleanSetting("hushfacebook_hide_ad_center_tab", FALSE, true);

    public static final BooleanSetting HIDE_CREATE_TAB =
            new BooleanSetting("hushfacebook_hide_create_tab", FALSE, true);

    public static final BooleanSetting HIDE_EXPLORE_TAB =
            new BooleanSetting("hushfacebook_hide_explore_tab", FALSE, true);

    public static final BooleanSetting HIDE_JOBS_TAB =
            new BooleanSetting("hushfacebook_hide_jobs_tab", FALSE, true);

    /**
     * The order Hide tabs puts the tab bar's tabs in (#341,
     * {@link app.morphe.extension.facebook.navigation.TabOrder}): each tab's key, commas between,
     * the first tab first. Blank keeps Facebook's order. Facebook builds the bar once, so a change
     * shows when it restarts.
     */
    public static final StringSetting TAB_ORDER =
            new StringSetting("hushfacebook_tab_order", "", true);

    /**
     * The tab bar goes to the bottom of the screen on accounts Facebook gives it at the top
     * ({@link app.morphe.extension.facebook.navigation.BottomTabBar}). Facebook places the bar as
     * its main screen starts, so a change waits for a restart.
     */
    public static final BooleanSetting BOTTOM_TAB_BAR =
            new BooleanSetting("hushfacebook_bottom_tab_bar", FALSE, true);

    /**
     * The tab bar goes to the top of the screen, under the search row, as older Facebook had it
     * (#166, {@link app.morphe.extension.facebook.navigation.BottomTabBar}). Facebook places the bar
     * as its main screen starts, so a change waits for a restart. Greyed out while
     * {@link #BOTTOM_TAB_BAR} is on, which wins.
     */
    public static final BooleanSetting TOP_TAB_BAR =
            new BooleanSetting("hushfacebook_top_tab_bar", FALSE, true, Setting.parentNot(BOTTOM_TAB_BAR));

    /**
     * The tab bar at the bottom slides away while the feed scrolls down and comes back scrolling
     * up, through Facebook's own scroll-away
     * ({@link app.morphe.extension.facebook.navigation.TabBarScrollAway}). Facebook adds the bar to
     * the views that scroll away as its main screen starts, so a change waits for a restart.
     */
    public static final BooleanSetting TAB_BAR_SCROLL_AWAY =
            new BooleanSetting("hushfacebook_tab_bar_scroll_away", FALSE, true);

    /**
     * The tab bar at the bottom stays on screen while the feed scrolls, on the accounts Facebook's
     * server settings give scroll-away to. Facebook adds the bar to the views that scroll away as its
     * main screen starts, so a change waits for a restart. Greyed out while
     * {@link #TAB_BAR_SCROLL_AWAY} is on, which wins.
     */
    public static final BooleanSetting KEEP_TAB_BAR =
            new BooleanSetting("hushfacebook_keep_tab_bar", FALSE, true, Setting.parentNot(TAB_BAR_SCROLL_AWAY));

    /**
     * The top bar stays where it rests while the feed scrolls, instead of sliding away. Asked each
     * time Facebook moves it, so a change shows on the next scroll.
     */
    public static final BooleanSetting KEEP_TOP_BAR =
            new BooleanSetting("hushfacebook_keep_top_bar", FALSE);

    /**
     * A tablet or an unfolded phone gets Facebook's phone layout, so the tab bar switches work on
     * the big screen too (#149, {@link app.morphe.extension.facebook.navigation.BottomTabBar#phoneLayout}).
     * Facebook decides on its big screen layout as its main screen starts, so a change waits for a
     * restart.
     */
    public static final BooleanSetting PHONE_LAYOUT_ON_BIG_SCREENS =
            new BooleanSetting("hushfacebook_phone_layout_big_screens", FALSE, true);

    /**
     * The tab bar goes away while the Reels tab is open, and the reel runs down to the bottom of the
     * screen ({@link app.morphe.extension.facebook.navigation.ReelsTabBarHidden}). Facebook pads the
     * Reels page for the bar as the page is set up, so a change waits for a restart.
     */
    public static final BooleanSetting HIDE_REELS_TAB_BAR =
            new BooleanSetting("hushfacebook_hide_reels_tab_bar", FALSE, true);

    /**
     * The top bar and the tab bar let the feed show through, with a blur of it behind them on
     * Android 12 and newer (#124, {@link app.morphe.extension.facebook.navigation.TranslucentBars}).
     * Asked as each frame of the main screen draws, so a change shows right away. A kept tab bar is
     * the exception (#250): with it on, Facebook builds the layout that runs the pages under the bar
     * as the main screen starts, so there the change waits for a restart, and the settings page
     * shows its restart row ({@link app.morphe.extension.facebook.navigation.TabBarScrollAway#seeThroughRestartOwed}).
     */
    public static final BooleanSetting TRANSLUCENT_BARS =
            new BooleanSetting("hushfacebook_translucent_bars", FALSE);

    /**
     * How much of their own color See-through bars keep, a percent, digits only, blank for the
     * stock amount (#425, {@link app.morphe.extension.facebook.navigation.TranslucentBars#opacityPercent}).
     * Not a switch. Asked as each frame of the main screen draws.
     */
    public static final StringSetting SEE_THROUGH_OPACITY =
            new StringSetting("hushfacebook_see_through_opacity", "");

    /**
     * The tab bar at the bottom is drawn as a pill with round ends, a little smaller than the bar and
     * a few dp above the bottom of the screen (#344, {@link app.morphe.extension.facebook.navigation.RoundedTabBar}).
     * Asked as each frame of the main screen draws, so a change shows right away.
     */
    public static final BooleanSetting ROUNDED_TAB_BAR =
            new BooleanSetting("hushfacebook_rounded_tab_bar", FALSE);

    /**
     * The rounded tab bar's width as a percent of the screen's, digits only, blank for the stock
     * size (#425, {@link app.morphe.extension.facebook.navigation.RoundedTabBar#widthPercent}). Not
     * a switch. Asked as each frame of the main screen draws.
     */
    public static final StringSetting TAB_BAR_WIDTH =
            new StringSetting("hushfacebook_tab_bar_width", "");

    /**
     * How much of its height the rounded tab bar keeps, a percent, digits only, blank for all of it
     * (#425, {@link app.morphe.extension.facebook.navigation.RoundedTabBar#heightPercent}). Not a
     * switch. Asked as each frame of the main screen draws.
     */
    public static final StringSetting TAB_BAR_HEIGHT =
            new StringSetting("hushfacebook_tab_bar_height", "");

    /**
     * Android's status bar goes from Facebook's main screen, a swipe down from the top edge showing
     * it for a moment (#124, {@link app.morphe.extension.facebook.navigation.HiddenStatusBar}).
     * Asked as the main screen resumes, gets its focus back or lays itself out, so a change shows
     * when the settings close.
     */
    public static final BooleanSetting HIDE_STATUS_BAR =
            new BooleanSetting("hushfacebook_hide_status_bar", FALSE);

    /**
     * In light mode the Reels tab keeps the light status bar, top bar and tab bar the other tabs
     * have, where Facebook turns them dark (#423,
     * {@link app.morphe.extension.facebook.navigation.LightReelsTabBars}). Asked each time the tab
     * is shown, so a change shows on the next visit to the Reels tab.
     */
    public static final BooleanSetting LIGHT_REELS_TAB_BARS =
            new BooleanSetting("hushfacebook_light_reels_tab_bars", FALSE);

    /**
     * The home top bar loses its Messenger button and the buttons beside it close up (#181,
     * {@link app.morphe.extension.facebook.navigation.TopBarButtons}). Facebook builds the bar as
     * the main screen starts, so a change waits for a restart.
     */
    public static final BooleanSetting HIDE_MESSENGER_BUTTON =
            new BooleanSetting("hushfacebook_hide_messenger_button", FALSE, true);

    /** The Messenger button keeps its place and loses only its unread count (#181). Shows with the next count. */
    public static final BooleanSetting HIDE_MESSENGER_COUNT =
            new BooleanSetting("hushfacebook_hide_messenger_count", FALSE);

    /**
     * The Facebook logo in the home top bar goes invisible but keeps its size, so the empty spot
     * still opens Hushfacebook settings on a long press (#181). A change waits for a restart.
     */
    public static final BooleanSetting HIDE_FACEBOOK_LOGO =
            new BooleanSetting("hushfacebook_hide_facebook_logo", FALSE, true);

    /**
     * The home top bar loses its Create (+) button and the buttons beside it close up (#257,
     * {@link app.morphe.extension.facebook.navigation.TopBarButtons}). A change waits for a restart.
     */
    public static final BooleanSetting HIDE_CREATE_BUTTON =
            new BooleanSetting("hushfacebook_hide_create_button", FALSE, true);

    /**
     * The home top bar loses its search button and the buttons beside it close up (#321,
     * {@link app.morphe.extension.facebook.navigation.TopBarButtons}). A change waits for a restart.
     */
    public static final BooleanSetting HIDE_SEARCH_BUTTON =
            new BooleanSetting("hushfacebook_hide_search_button", FALSE, true);

    /**
     * Pressing and holding the top bar's search button opens Google Lens (#245,
     * {@link app.morphe.extension.facebook.search.PictureSearch}). The long press is set as the bar
     * makes the button, so turning it on waits for a restart.
     */
    public static final BooleanSetting SEARCH_WITH_PICTURE =
            new BooleanSetting("hushfacebook_search_with_picture", FALSE, true);

    /**
     * Facebook's dark mode controller answers dark whatever its own setting says
     * ({@link app.morphe.extension.facebook.theme.ForceDarkMode}), for tablets whose Facebook
     * settings have no Dark mode row. Facebook asks as each screen applies its theme, so a change
     * shows fully after a restart.
     */
    public static final BooleanSetting FORCE_DARK_MODE =
            new BooleanSetting("hushfacebook_force_dark_mode", FALSE, true);

    /**
     * With the AMOLED black theme, a card, a comment or a popover takes the page's colour instead of
     * the near black that keeps its edge, and a secondary button takes an input's fill so it still
     * shows ({@link app.morphe.extension.facebook.theme.AmoledTheme}, issue #191). Facebook asks for
     * colours as each screen builds, so a change shows fully after a restart.
     */
    public static final BooleanSetting AMOLED_FLAT_CARDS =
            new BooleanSetting("hushfacebook_amoled_flat_cards", FALSE, true);

    /**
     * With the AMOLED black theme, the gap Facebook leaves between a list's rows, posts in the feed
     * among them, shows as a thin gray line instead of the page's black
     * ({@link app.morphe.extension.facebook.theme.FeedGaps}, issue #206). Rows read it as they
     * draw, so a change shows on the rows drawn after it.
     */
    public static final BooleanSetting AMOLED_FEED_GAPS =
            new BooleanSetting("hushfacebook_amoled_feed_gaps", FALSE, false);

    /**
     * With the AMOLED black theme, the dark colour its backgrounds take, as {@code #RRGGBB}, in place
     * of the one the patch's Background colour option wrote ({@link
     * app.morphe.extension.facebook.theme.AmoledTheme#load}, issue #214). Blank keeps the patch's,
     * and so does a paused Facebook. Read once as Facebook starts, so a change brings up the restart
     * row. Not a switch.
     */
    public static final StringSetting AMOLED_BACKGROUND =
            new StringSetting("hushfacebook_amoled_background", "", true);

    /**
     * With the AMOLED black theme, the light colour Facebook's light mode backgrounds take in place of
     * its white, as {@code #RRGGBB} ({@link app.morphe.extension.facebook.theme.AmoledTheme#load},
     * issue #214). Blank keeps Facebook's own, and so does a paused Facebook. Read once as Facebook
     * starts, so a change brings up the restart row. Not a switch.
     */
    public static final StringSetting AMOLED_LIGHT_BACKGROUND =
            new StringSetting("hushfacebook_amoled_light_background", "", true);

    /**
     * Words of your own at the top of Home in place of Facebook's wordmark, with the Home header text
     * patch in the build ({@link app.morphe.extension.facebook.misc.HeaderText}, #230). Blank keeps
     * the wordmark, and so does a paused Facebook. Facebook builds the header once, so a change brings
     * up the restart row. Not a switch.
     */
    public static final StringSetting HEADER_TEXT =
            new StringSetting("hushfacebook_header_text", "", true);

    /**
     * The name of a picture of your own for the top of Home, which takes the place of
     * {@link #HEADER_TEXT}'s words and Facebook's wordmark ({@link
     * app.morphe.extension.facebook.misc.HeaderPicture}, #230). Empty keeps the words or the
     * wordmark, and so does a paused Facebook. Only the settings screen writes it, after the shrunk
     * copy has been written to Facebook's files. It isn't a switch, and a settings file leaves it out:
     * the picture can't travel in one, and the name alone would point at nothing on another phone.
     */
    public static final StringSetting HEADER_PICTURE =
            new StringSetting("hushfacebook_header_picture", "");

    /**
     * The strip some posts carry ("Are you interested in this post?", "Show less", who recently
     * commented, follow and chat suggestions) goes, and so does the room kept for it
     * ({@link app.morphe.extension.facebook.feed.PostPrompts}). A change shows on the posts drawn
     * after it.
     */
    public static final BooleanSetting HIDE_POST_PROMPTS =
            new BooleanSetting("hushfacebook_hide_post_prompts", TRUE);

    /**
     * The "Follow" link after the name in the header of posts from Pages and people you don't follow
     * goes ({@link app.morphe.extension.facebook.feed.PostPrompts#showFollowLink}). Off by default.
     * A change shows on the posts drawn after it.
     */
    public static final BooleanSetting HIDE_POST_FOLLOW_LINK =
            new BooleanSetting("hushfacebook_hide_post_follow_link", FALSE);

    /**
     * The Join button after the group's name in the header of a post from a group you haven't joined
     * goes, and the dot before it ({@link app.morphe.extension.facebook.feed.PostPrompts#hideJoinButton},
     * #356). Off by default. A change shows on the posts drawn after it.
     */
    public static final BooleanSetting HIDE_POST_JOIN_BUTTON =
            new BooleanSetting("hushfacebook_hide_post_join_button", FALSE);

    /**
     * The X at the top of a feed post, next to its ⋯ menu, goes, and the header is drawn as for a
     * post Facebook gives no X ({@link app.morphe.extension.facebook.feed.PostPrompts#showPostX},
     * #389). Off by default. A change shows on the posts drawn after it.
     */
    public static final BooleanSetting HIDE_POST_X =
            new BooleanSetting("hushfacebook_hide_post_x", FALSE);

    /**
     * The numbers on a post's Like, Comment and Share buttons go and each button shows its word, as
     * on a post nobody has reacted to yet ({@link app.morphe.extension.facebook.feed.PostPrompts#hidePostCounts},
     * #180). The buttons stay where they are. Off by default. A change shows on the posts drawn after it.
     */
    public static final BooleanSetting HIDE_POST_COUNTS =
            new BooleanSetting("hushfacebook_hide_post_counts", FALSE);

    /**
     * The stacked reaction faces at the end of a post's button row and the bling bar's reaction icons
     * with their count line are left undrawn, and a tap where they were still works
     * ({@link app.morphe.extension.facebook.feed.PostPrompts#hideReactionPreview}, #158). Off by default.
     * A change shows on the posts drawn after it.
     */
    public static final BooleanSetting HIDE_POST_REACTIONS =
            new BooleanSetting("hushfacebook_hide_post_reactions", FALSE);

    /**
     * The stacked reaction faces in the corner of a comment bubble are left undrawn, and a tap on them
     * still opens the list of reactions
     * ({@link app.morphe.extension.facebook.feed.PostPrompts#hideReactionPreview(boolean)}, #158). Off by
     * default. A change shows on the comments drawn after it.
     */
    public static final BooleanSetting HIDE_COMMENT_REACTIONS =
            new BooleanSetting("hushfacebook_hide_comment_reaction_preview", FALSE);

    /**
     * The profile picture leaves the top of posts, and the name moves over into its space
     * ({@link app.morphe.extension.facebook.feed.ProfilePictures}, #400). Off by default. Read as each
     * post's header draws.
     */
    public static final BooleanSetting HIDE_FEED_PROFILE_PICTURES =
            new BooleanSetting("hushfacebook_hide_feed_profile_pictures", FALSE);

    /**
     * The profile picture leaves comments, and the name and text move over into its space (#400). Off
     * by default. Read as each comment draws.
     */
    public static final BooleanSetting HIDE_COMMENT_PROFILE_PICTURES =
            new BooleanSetting("hushfacebook_hide_comment_profile_pictures", FALSE);

    /**
     * Posts you've already scrolled past stay out of the feed on later loads
     * ({@link app.morphe.extension.facebook.feed.SeenPosts}). Off by default: it remembers which
     * posts you saw, on the phone only. Posts already on screen are never touched, and a change
     * shows on the next feed load.
     */
    public static final BooleanSetting HIDE_SEEN_POSTS =
            new BooleanSetting("hushfacebook_hide_seen_posts", FALSE);

    /**
     * With {@link #HIDE_SEEN_POSTS} on, a post scrolled past shows up one more time and is hidden
     * only once it's scrolled past again (#186). Off by default, so a seen post stays out from its
     * first return.
     */
    public static final BooleanSetting SEEN_POSTS_COME_BACK_ONCE =
            new BooleanSetting("hushfacebook_seen_posts_come_back_once", FALSE, Setting.parent(HIDE_SEEN_POSTS));

    /**
     * How long a seen post stays hidden while {@link #HIDE_SEEN_POSTS} is on. It isn't a switch.
     * Its row is greyed out while the switch is off: the settings page enables each row by its
     * setting's availability after every change, which would otherwise undo the row's own greying.
     */
    public static final EnumSetting<SeenPosts.Keep> SEEN_POSTS_KEEP =
            new EnumSetting<>("hushfacebook_seen_posts_keep", SeenPosts.Keep.SEVEN_DAYS, Setting.parent(HIDE_SEEN_POSTS));

    /**
     * Posts come without the row of Meta AI questions Facebook adds under some of them
     * ({@link app.morphe.extension.facebook.feed.MetaAiQuestions}). A change shows on the posts
     * drawn after it.
     */
    public static final BooleanSetting HIDE_META_AI_QUESTIONS =
            new BooleanSetting("hushfacebook_hide_meta_ai_questions", TRUE);

    /**
     * Post headers keep the one line with the date instead of Facebook's rotating subtitle
     * ({@link app.morphe.extension.facebook.feed.PostDates}). A change shows on the headers drawn
     * after it.
     */
    public static final BooleanSetting KEEP_POST_DATES =
            new BooleanSetting("hushfacebook_keep_post_dates", TRUE);

    /**
     * Posts in the feed show their whole text instead of Facebook's cut with See more
     * ({@link app.morphe.extension.facebook.feed.FullPostText}). A change shows on the posts drawn
     * after it.
     */
    public static final BooleanSetting SHOW_FULL_POST_TEXT =
            new BooleanSetting(ReelCaptionSetting.FEED_KEY, FALSE);

    /**
     * Reel captions start whole instead of cut with "more" (#398, split from
     * {@link #SHOW_FULL_POST_TEXT}, whose stored value it takes over once). A change shows on the
     * reels drawn after it.
     */
    public static final BooleanSetting SHOW_FULL_REEL_CAPTIONS =
            new ReelCaptionSetting("hushfacebook_show_full_reel_captions");

    /**
     * An open reel caption sits on a dark rounded box, like subtitles (#419,
     * {@link app.morphe.extension.facebook.feed.CaptionBackdrop}). A change shows on the captions
     * drawn after it.
     */
    public static final BooleanSetting DIM_OPEN_REEL_CAPTIONS =
            new BooleanSetting("hushfacebook_dim_open_reel_captions", FALSE);

    /**
     * A post in another language gets a Translate line under its text, and a tap sends the text to
     * Google Translate ({@link app.morphe.extension.facebook.feed.PostTranslation}). A change shows
     * on the posts drawn after it.
     */
    public static final BooleanSetting TRANSLATE_POST_LINK =
            new BooleanSetting("hushfacebook_translate_post_link", FALSE);

    /**
     * A post in another language shows translated by itself, with See original under it. Each such
     * post's text goes to Google Translate as it's drawn. Includes the line above, for the posts
     * whose answer doesn't come in time.
     */
    public static final BooleanSetting TRANSLATE_POSTS_BY_THEMSELVES =
            new BooleanSetting("hushfacebook_translate_posts_by_themselves", FALSE);

    /** The language posts are translated to, as Google Translate's code. Empty goes by the phone's language. */
    public static final StringSetting TRANSLATE_TO =
            new StringSetting("hushfacebook_translate_to", "");

    /** The languages posts stay in, as Google Translate's codes joined with commas. */
    public static final StringSetting TRANSLATE_LEAVE_ALONE =
            new StringSetting("hushfacebook_translate_leave_alone", "");

    /**
     * A post's three-dot menu gets See who shared, which opens Facebook's own list of the people
     * who shared it ({@link app.morphe.extension.facebook.feed.WhoShared}). A change shows on the
     * next menu opened.
     */
    public static final BooleanSetting SEE_WHO_SHARED =
            new BooleanSetting("hushfacebook_see_who_shared", FALSE);

    /**
     * A post with several photos or videos shows them in the row Facebook swipes through, one at a
     * time, instead of a grid ({@link app.morphe.extension.facebook.feed.PhotoCarousel}). A change
     * shows on the posts drawn after it.
     */
    public static final BooleanSetting PHOTO_CAROUSEL =
            new BooleanSetting("hushfacebook_photo_carousel", FALSE);

    /**
     * Posts, comments and reel captions keep the language they were written in, with Facebook's See
     * translation link under them ({@link app.morphe.extension.facebook.feed.AutoTranslation}). A
     * change shows on the posts, comments and reels drawn after it.
     */
    public static final BooleanSetting TURN_OFF_AUTO_TRANSLATION =
            new BooleanSetting("hushfacebook_turn_off_auto_translation", FALSE);

    /**
     * The Feeds tab opens on its posts, without the title row or the filters under it
     * ({@link app.morphe.extension.facebook.feed.FeedsHeader}). Facebook settles both as the tab is
     * built, so a change waits for a restart.
     */
    public static final BooleanSetting HIDE_FEEDS_HEADER =
            new BooleanSetting("hushfacebook_hide_feeds_header", FALSE, true);

    /**
     * Reels come without the "Are you interested in this reel?" prompt
     * ({@link app.morphe.extension.facebook.reels.ReelPrompts}) or the "To what extent does this
     * video match your interests?" survey card between them (#405,
     * {@link app.morphe.extension.facebook.reels.ReelMidCards}). A change shows on the reels built
     * after it.
     */
    public static final BooleanSetting HIDE_REEL_PROMPTS =
            new BooleanSetting("hushfacebook_hide_reel_prompts", TRUE);

    /**
     * The top folder saves go to: Movies for a video and Pictures for a photo, the default and
     * where Facebook's own saves go, or DCIM or Download for both (#42). The {@link #SAVE_FOLDER}
     * goes under it. Saves made before a change stay where they are. Like the folder, it isn't a
     * switch.
     */
    public static final EnumSetting<SaveTo> SAVE_TO =
            new EnumSetting<>("hushfacebook_save_to", SaveTo.MOVIES_AND_PICTURES);

    /**
     * The storage saves go to (#274): empty, the default, for the phone's own, or the MediaStore
     * volume name of an SD card ({@link app.morphe.extension.facebook.download.SaveStorage}). While
     * that card is out of the phone, saves go to the phone's storage. It isn't a switch, and it
     * stays out of settings files, since a card's name means nothing on another phone.
     */
    public static final StringSetting SAVE_STORAGE =
            new StringSetting("hushfacebook_save_storage", "");

    /**
     * The folder every save goes to, under the top folder {@link #SAVE_TO} names. The
     * settings row and an import keep it clean, and {@link SaveFolder#sanitize} cleans it again
     * wherever it's read, so whatever wrote the store, a save lands in one folder under each.
     * It isn't a switch, and a paused Facebook makes no Hushfacebook saves for it to steer.
     */
    public static final StringSetting SAVE_FOLDER =
            new StringSetting("hushfacebook_save_folder", SaveFolder.DEFAULT);

    /**
     * A folder inside {@link #SAVE_FOLDER} that video saves go in, or empty, the default, for the
     * save folder itself. {@link SaveFolder#subfolder} cleans it the way the save folder is cleaned,
     * so it's one folder name, never a path. It isn't a switch.
     */
    public static final StringSetting VIDEO_SUBFOLDER =
            new StringSetting("hushfacebook_video_subfolder", "");

    /** The same as {@link #VIDEO_SUBFOLDER}, for photo saves. */
    public static final StringSetting PHOTO_SUBFOLDER =
            new StringSetting("hushfacebook_photo_subfolder", "");

    /**
     * The quality a video save asks for: the best the player streams, a ceiling, or the smallest
     * file. Every save of a story, a reel or a feed video reads it when it starts, and one that
     * finds nothing at or under a ceiling takes the nearest above it. Photos always save whole.
     * Like the folder, it isn't a switch.
     */
    public static final EnumSetting<DownloadQuality> DOWNLOAD_QUALITY =
            new EnumSetting<>("hushfacebook_download_quality", DownloadQuality.BEST);

    /**
     * A reel's Download button and a feed or Watch video's Download to phone show the list of
     * qualities first, {@link #DOWNLOAD_QUALITY} checked, and the save takes the one picked for
     * itself alone (issue #448). Off by default, so a save starts at once at Download quality.
     * Every download patch has it ({@link PatchFamily#DOWNLOAD_SWITCHES}).
     */
    public static final BooleanSetting DOWNLOAD_QUALITY_ASK =
            new BooleanSetting("hushfacebook_download_quality_ask", FALSE);

    /**
     * Video saves keep to what other apps open: H.264 video with AAC-LC or HE-AAC sound, within
     * {@link #DOWNLOAD_QUALITY}, or Facebook's single MP4 file when the manifest has no such pair.
     * The sharpest version Facebook streams is often AV1 with xHE-AAC sound, which Gallery and VLC
     * play and WhatsApp turns down (issue #11). Off by default, so a save keeps the sharpest. Every
     * download patch reads it ({@link PatchFamily#DOWNLOAD_SWITCHES}), and a paused Facebook makes
     * no Hushfacebook saves for it to steer.
     */
    public static final BooleanSetting DOWNLOAD_COMPATIBLE =
            new BooleanSetting("hushfacebook_download_compatible", FALSE);

    /**
     * With {@link #DOWNLOAD_COMPATIBLE} on, a VP9, AV1 or H.265 picture larger than the best H.264
     * one is converted to H.264 on the phone instead of the save keeping the smaller H.264 (#189).
     * Facebook often lists a reel's full size only as VP9 or AV1. Converting takes a while, so it's
     * off by default. Every download patch reads it ({@link PatchFamily#DOWNLOAD_SWITCHES}).
     */
    public static final BooleanSetting DOWNLOAD_CONVERT =
            new BooleanSetting("hushfacebook_download_convert", FALSE);

    /**
     * Saves go without the toasts that only say how a save went: Saving as it starts, Saved to as
     * it finishes, and Save canceled (issue #142). A failed save still says why, and the WhatsApp
     * warning still shows when no notification card carries it. Off by default. Every download
     * patch reads it ({@link PatchFamily#DOWNLOAD_SWITCHES}).
     */
    public static final BooleanSetting DOWNLOAD_QUIET =
            new BooleanSetting("hushfacebook_download_quiet", FALSE);

    /**
     * The name a saved video gets: {date}, {video_id}, {owner}, {owner_id} and {posted} fill in per
     * save, the last four only when the save knows them, and the default is Facebook's own FB_VID_
     * name, so nothing changes for anyone who leaves it. Photos have {@link #PHOTO_FILENAME_TEMPLATE}.
     * Cleaned like the folder wherever it's read ({@link FileNameTemplate#sanitize}), and like the
     * folder, it isn't a switch.
     */
    public static final StringSetting FILENAME_TEMPLATE =
            new StringSetting("hushfacebook_filename_template", FileNameTemplate.DEFAULT);

    /**
     * The name a saved photo gets, the same way: {date}, {photo_id}, {owner}, {owner_id} and
     * {posted}, and Facebook's own FB_IMG_ name by default. Cleaned wherever it's read
     * ({@link FileNameTemplate#sanitizePhoto}).
     */
    public static final StringSetting PHOTO_FILENAME_TEMPLATE =
            new StringSetting("hushfacebook_photo_filename_template", FileNameTemplate.PHOTO_DEFAULT);

    /**
     * What a tap on Download does for a reel or a feed or Watch video: save it here, the default,
     * or send its link to another app ({@link SendLink}, #41). Stories always save. Like the
     * quality, it isn't a switch.
     */
    public static final EnumSetting<SendLink.Action> DOWNLOAD_ACTION =
            new EnumSetting<>("hushfacebook_download_action", SendLink.Action.SAVE);

    /**
     * The app {@link #DOWNLOAD_ACTION} sends links to, by package name. Blank, or anything that
     * isn't a package name, leaves the choice to Android's chooser each time.
     */
    public static final StringSetting SEND_TO_APP =
            new StringSetting("hushfacebook_send_to_app", "");

    /**
     * The tab a start from the launcher icon opens on while {@link #OPEN_ON_CHOSEN_TAB} is on:
     * Marketplace, the one people asked for, unless it's changed. A tab this account's tab bar
     * hasn't got opens Home, which is what Facebook does with a notification about such a tab. It
     * isn't a switch, and a paused Facebook opens where it chooses.
     */
    public static final EnumSetting<StartTab> START_TAB =
            new EnumSetting<>("hushfacebook_start_tab", StartTab.MARKETPLACE);

    /**
     * The filter the Feeds tab opens on after a start from the launcher icon that
     * {@link #START_TAB} sends to Feeds (#56): All, which is whatever Facebook opens it on, unless
     * it's changed. Asked once per start, the first time the Feeds tab shows, so every filter tapped
     * after that stays as tapped. A filter this account's Feeds tab hasn't got leaves it as it
     * opened.
     */
    public static final EnumSetting<FeedsSubtab> FEEDS_SUBTAB =
            new EnumSetting<>("hushfacebook_feeds_subtab", FeedsSubtab.ALL);

    /**
     * The order comment sheets ask for while {@link #DEFAULT_COMMENT_ORDER} is on: Facebook's own
     * choice until someone picks Most relevant, Newest, All comments or Oldest, so picking the patch changes
     * nothing on its own. It isn't a switch, and a paused Facebook takes the order its servers
     * choose.
     */
    public static final EnumSetting<CommentOrder> COMMENT_ORDER =
            new EnumSetting<>("hushfacebook_comment_order", CommentOrder.FACEBOOK);

    /**
     * The quality videos start at while {@link #DEFAULT_PLAYBACK_QUALITY} is on: Facebook's own
     * choice until someone picks another, so picking the patch changes nothing on its own. It isn't
     * a switch, and a paused Facebook picks the quality itself.
     */
    public static final EnumSetting<PlaybackQuality> PLAYBACK_QUALITY =
            new EnumSetting<>("hushfacebook_playback_quality", PlaybackQuality.AUTO);

    /**
     * The quality reels start at while {@link #DEFAULT_PLAYBACK_QUALITY} is on: the same as
     * {@link #PLAYBACK_QUALITY} until someone picks one of their own, so an update changes nothing on
     * its own. It isn't a switch, and a paused Facebook picks the quality itself.
     */
    public static final EnumSetting<SurfaceQuality> REELS_PLAYBACK_QUALITY =
            new EnumSetting<>("hushfacebook_reels_playback_quality", SurfaceQuality.SAME);

    /** The quality video stories start at, the way {@link #REELS_PLAYBACK_QUALITY} is the reels'. */
    public static final EnumSetting<SurfaceQuality> STORIES_PLAYBACK_QUALITY =
            new EnumSetting<>("hushfacebook_stories_playback_quality", SurfaceQuality.SAME);

    /**
     * The hour quiet hours start while {@link #NOTIFICATION_QUIET_HOURS} is on, 10 PM until someone
     * picks another. It isn't a switch.
     */
    public static final EnumSetting<QuietHour> QUIET_HOURS_FROM =
            new EnumSetting<>("hushfacebook_quiet_hours_from", QuietHour.H22);

    /** The hour quiet hours end, 7 AM until someone picks another, the way {@link #QUIET_HOURS_FROM} starts them. */
    public static final EnumSetting<QuietHour> QUIET_HOURS_UNTIL =
            new EnumSetting<>("hushfacebook_quiet_hours_until", QuietHour.H7);

    /**
     * How long Facebook may be away before a return asks for the screen lock while {@link #APP_LOCK}
     * is on. It isn't a switch.
     */
    public static final EnumSetting<AppLock.After> APP_LOCK_AFTER =
            new EnumSetting<>("hushfacebook_app_lock_after", AppLock.After.ONE_MINUTE, Setting.parent(APP_LOCK));

    /** How long {@link #OPENING_PAUSE}'s cover counts down before Open now works. It isn't a switch. */
    public static final EnumSetting<OpeningPause.Length> OPENING_PAUSE_LENGTH =
            new EnumSetting<>("hushfacebook_opening_pause_length", OpeningPause.Length.FIVE_SECONDS,
                    Setting.parent(OPENING_PAUSE));

    /** How long {@link #TAKE_BREAKS} lets browsing go before a break. It isn't a switch. */
    public static final EnumSetting<BreakTimer.Use> BREAK_AFTER =
            new EnumSetting<>("hushfacebook_break_after", BreakTimer.Use.THIRTY_MINUTES,
                    Setting.parent(TAKE_BREAKS));

    /** How long a {@link #TAKE_BREAKS} break lasts. It isn't a switch. */
    public static final EnumSetting<BreakTimer.Length> BREAK_LENGTH =
            new EnumSetting<>("hushfacebook_break_length", BreakTimer.Length.FIVE_MINUTES,
                    Setting.parent(TAKE_BREAKS));

    static {
        // The lock guards the phone's owner rather than changing Facebook, so no pause opens it: not
        // the Pause switch, not the marker file someone could leave over USB, and not the safe mode
        // a few quick crashes can turn on. Its own switch, behind the lock, is the only way off.
        // Here, after both fields, since a static block runs in the order it's written.
        Setting.keepWhenPaused(APP_LOCK, APP_LOCK_AFTER);
        // The launcher shortcut, the logo's long press and the Menu row are ways back to the settings
        // screen, where a pause is lifted, so a pause neither brings them back nor takes them away (#146).
        Setting.keepWhenPaused(SETTINGS_SHORTCUT, SETTINGS_LOGO_PRESS, SETTINGS_MENU_ROW);
    }

    /**
     * How large Facebook's text is, as a share of the phone's font size ({@link TextSize}). It
     * isn't a switch: 100% is Facebook as it ships, which is also what a paused Facebook reads.
     * The settings entry carries it, so every build has it. Its row says to restart, so a change
     * brings up the restart row.
     */
    public static final EnumSetting<TextSize.Scale> TEXT_SIZE =
            new EnumSetting<>("hushfacebook_text_size", TextSize.Scale.P100, true);

    /**
     * Mark new settings ({@link NewRowDots}, #16): how long the dot beside a setting an update added
     * stays, from none to 7 days after its page was first opened. Until that page is left by
     * default, as the dot always worked. The settings entry carries it, so every build has it.
     */
    public static final EnumSetting<NewRowMark> NEW_ROW_MARK =
            new EnumSetting<>("hushfacebook_new_row_mark", NewRowMark.ONE_VISIT);

    /**
     * The colour that stands in for Facebook's blue on links, buttons, switches and the selected
     * tab ({@link AccentColor}), with the Accent color patch in the build. Facebook blue is Facebook
     * as it ships, which is also what a paused Facebook reads. Not a switch. Screens pick their
     * colours as they build, so a change brings up the restart row.
     */
    public static final EnumSetting<AccentColor.Preset> ACCENT_COLOR =
            new EnumSetting<>("hushfacebook_accent_color", AccentColor.Preset.FACEBOOK, true);

    /**
     * The color the Accent color row's Custom choice uses (#355), as {@code #RRGGBB}. Blank means none
     * picked yet, and Custom then keeps Facebook's blue. A settings file carries it. Screens pick
     * their colours as they build, so a change brings up the restart row. Not a switch.
     */
    public static final StringSetting ACCENT_CUSTOM =
            new StringSetting("hushfacebook_accent_custom", "", true);

    /**
     * An emoji, or a few characters, drawn after the Facebook logo at the top of Home in their own
     * colors (#360), with the Home header text patch in the build ({@link
     * app.morphe.extension.facebook.misc.HeaderText}). Blank is off, and so is a paused Facebook.
     * Facebook builds the header once, so a change brings up the restart row. A settings file carries
     * it. Not a switch.
     */
    public static final StringSetting LOGO_EMOJI =
            new StringSetting("hushfacebook_logo_emoji", "", true);

    /**
     * The colour the count badges on the tabs take ({@link app.morphe.extension.facebook.theme.MarkColors}),
     * as {@code #RRGGBB}, issue #263. Blank keeps Facebook's red, and so does a paused Facebook. Screens
     * pick their colours as they build, so a change brings up the restart row. Not a switch.
     */
    public static final StringSetting BADGE_COLOR = new StringSetting("hushfacebook_badge_color", "", true);

    /** The colour the Facebook logo at the top of Home takes, the same way. Blank keeps Facebook's blue. */
    public static final StringSetting LOGO_COLOR = new StringSetting("hushfacebook_logo_color", "", true);

    /** The colour of the selected tab's icon and the line over it, the same way (#297). Blank keeps Facebook's own. */
    public static final StringSetting TAB_COLOR = new StringSetting("hushfacebook_tab_color", "", true);

    /**
     * Where {@link #USE_SYSTEM_FONT} takes its font from: empty for the phone's own, or the name of
     * the font file picked in the settings, whose copy sits in Facebook's files
     * ({@link app.morphe.extension.facebook.font.FontFile}). Only the settings screen writes it,
     * after the copy has passed its checks. It isn't a switch, and a settings file leaves it out:
     * the font can't travel in one, and the name alone would point at nothing on another phone.
     */
    public static final StringSetting FONT_SOURCE =
            new StringSetting("hushfacebook_font_source", "");
}
