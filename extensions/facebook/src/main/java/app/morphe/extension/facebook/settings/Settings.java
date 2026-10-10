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
import app.morphe.extension.facebook.download.DownloadQuality;
import app.morphe.extension.facebook.download.FileNameTemplate;
import app.morphe.extension.facebook.download.SaveFolder;
import app.morphe.extension.facebook.download.SaveTo;
import app.morphe.extension.facebook.download.SendLink;
import app.morphe.extension.facebook.feed.SeenPosts;
import app.morphe.extension.facebook.media.PlaybackQuality;
import app.morphe.extension.facebook.media.SurfaceQuality;
import app.morphe.extension.facebook.misc.AppLock;
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
     * The row of groups to join that Facebook puts between posts, found by its GraphQL type name.
     * Posts from groups you're in are stories of their own and stay.
     */
    public static final BooleanSetting HIDE_SUGGESTED_GROUPS =
            new BooleanSetting("hushfacebook_hide_suggested_groups", TRUE);

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

    /** With the switch above, keep the feed however long Facebook stayed in the background (#23). */
    public static final BooleanSetting RETURN_REFRESH_NO_LIMIT =
            new BooleanSetting("hushfacebook_return_refresh_no_limit", FALSE);

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
     * The cards beside Create story in the Stories tray that suggest a story to make, such as
     * "Share music you love": the tray's fetch asks the server to leave out their list,
     * skip_srtt_item_list, as Facebook does for the Video tab's tray. Off until you turn it on.
     */
    public static final BooleanSetting HIDE_STORY_PROMPTS =
            new BooleanSetting("hushfacebook_hide_story_prompts", FALSE);

    /** Keep a finished Story visible until the user navigates. */
    public static final BooleanSetting BLOCK_STORY_AUTO_ADVANCE =
            new BooleanSetting("hushfacebook_block_story_auto_advance", FALSE);

    /**
     * Stop Story auto-advance's second switch: a finished story starts again from the beginning
     * through Facebook's own restart, instead of holding its last frame. Off until you turn it on.
     */
    public static final BooleanSetting LOOP_STORIES =
            new BooleanSetting("hushfacebook_loop_stories", FALSE);

    /**
     * The batches of viewed story cards the story viewer sends as DirectSeenMutation, which put you
     * on each story's viewer list. Held back, replies and reactions still show you, and stories you
     * viewed keep their unwatched ring.
     */
    public static final BooleanSetting VIEW_STORIES_ANONYMOUSLY =
            new BooleanSetting("hushfacebook_view_stories_anonymously", FALSE);

    /**
     * View stories anonymously's second switch: an eye button over each story that marks it, so the
     * next report of viewed stories carries the marked ones and nothing else. Off until it's turned on.
     */
    public static final BooleanSetting MARK_STORIES_SEEN =
            new BooleanSetting("hushfacebook_mark_stories_seen", FALSE);

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
     * The ads and boosted listings of Marketplace's feed: its query asks the server to skip them,
     * and its ads-only queries aren't sent.
     */
    public static final BooleanSetting HIDE_SPONSORED_MARKETPLACE_LISTINGS =
            new BooleanSetting("hushfacebook_hide_sponsored_marketplace_listings", TRUE);

    /**
     * A seller's Marketplace page always offers View profile, which opens the seller's regular
     * Facebook profile ({@link app.morphe.extension.facebook.navigation.MarketplaceSellerProfile}).
     * Off or paused, the page asks Facebook's experiment flag again, which shows it to some accounts.
     */
    public static final BooleanSetting SHOW_SELLER_VIEW_PROFILE =
            new BooleanSetting("hushfacebook_show_seller_view_profile", TRUE);

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
     * The "Commission eligible" label stays. A change shows on the reels, posts and comment sheets
     * built after it.
     */
    public static final BooleanSetting HIDE_AFFILIATE_LINKS =
            new BooleanSetting("hushfacebook_hide_affiliate_links", TRUE);

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
     * Reels and the videos that open in the same viewer start in Facebook's own Clean mode, the
     * state its three-dot menu's Clean mode item puts one reel in, with the buttons down the side
     * hidden. Facebook's own pinch and menu still bring them back for that reel, and an ad reel's
     * overlay keeps its controls. Off until it's turned on, since nobody has seen it on a phone yet.
     */
    public static final BooleanSetting REEL_CLEAN_MODE =
            new BooleanSetting("hushfacebook_reel_clean_mode", FALSE);

    /**
     * A reel stops on its last frame instead of starting over, so a tap plays it again and a swipe
     * moves on. Feed videos, stories and every other video play as Facebook has them. Off until
     * it's turned on, since nobody has seen it on a phone yet.
     */
    public static final BooleanSetting PLAY_REELS_ONCE =
            new BooleanSetting("hushfacebook_play_reels_once", FALSE);

    /**
     * The batches of watched reels the Reels viewer sends as FbShortsSeenStateMutation: only their
     * ids, the record Facebook ranks the Reels feed with, which nobody else sees. Held back, reels
     * already watched may come back in the feed.
     */
    public static final BooleanSetting DONT_SEND_REEL_WATCH_HISTORY =
            new BooleanSetting("hushfacebook_dont_send_reel_watch_history", FALSE);

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
     * Facebook's haptics on its own taps and gestures ({@link app.morphe.extension.facebook.misc.Haptics}).
     * Starts off.
     */
    public static final BooleanSetting TURN_OFF_HAPTICS =
            new BooleanSetting("hushfacebook_turn_off_haptics", FALSE);

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
     * A double tap on a reel or a video left without Facebook's like: no heart, no like sent. A
     * single tap and the Like button do what they always did. Starts off.
     */
    public static final BooleanSetting TURN_OFF_DOUBLE_TAP_LIKE =
            new BooleanSetting("hushfacebook_turn_off_double_tap_like", FALSE);

    /**
     * A speed picked in a reel's menu stays for the next reels where it was picked, ads and live
     * videos aside, until another is picked or Facebook restarts
     * ({@link app.morphe.extension.facebook.media.ReelSpeed}). Nothing is
     * stored. Off or paused, each reel starts at the speed Facebook starts it at.
     */
    public static final BooleanSetting KEEP_REEL_SPEED =
            new BooleanSetting("hushfacebook_keep_reel_speed", TRUE);

    /**
     * A speed picked in a feed or Watch video's gear menu stays for the next videos that aren't
     * reels, ads or live, until another is picked or Facebook restarts. Reels keep their own speed
     * by {@link #KEEP_REEL_SPEED}. It rides on Keep the reel speed's hooks
     * ({@link app.morphe.extension.facebook.media.ReelSpeed}). Nothing is stored. Off or paused,
     * each video starts at the speed Facebook starts it at.
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
     * Every comment starts with its reply thread open, as if View replies had been tapped
     * ({@link app.morphe.extension.facebook.comments.CommentSheetOptions}). Off by default. A
     * change shows on the next comments drawn.
     */
    public static final BooleanSetting OPEN_REPLY_THREADS =
            new BooleanSetting("hushfacebook_open_reply_threads", FALSE);

    /**
     * A group post's comments end without the Related groups list and its Join buttons
     * ({@link app.morphe.extension.facebook.comments.CommentSheetOptions}, #101). Off by default. A
     * change shows on the next comments opened.
     */
    public static final BooleanSetting HIDE_RELATED_GROUPS_UNDER_COMMENTS =
            new BooleanSetting("hushfacebook_hide_related_groups_under_comments", FALSE);

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
     * tap has just ended, and Facebook's own Autoplay setting reads Off
     * ({@link app.morphe.extension.facebook.media.TapToPlay}). The stored Autoplay setting is never
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
     * A video longer than two minutes that was left partway picks up where it was left, once, the
     * next time a player starts it ({@link app.morphe.extension.facebook.media.ResumePlayback}).
     * Starts off. Off or paused, nothing is saved or looked up and videos start as Facebook starts
     * them; the points already saved stay until they're 30 days old, and Facebook's start drops
     * them after that either way.
     */
    public static final BooleanSetting RESUME_LONG_VIDEOS =
            new BooleanSetting("hushfacebook_resume_long_videos", FALSE);

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
     * HDR videos and photos stay in the screen's usual range instead of turning it up to full
     * brightness ({@link app.morphe.extension.facebook.media.HdrBrightness}). Starts off. Off
     * or paused, Facebook asks for its HDR window again.
     */
    public static final BooleanSetting TURN_OFF_HDR_BRIGHTNESS =
            new BooleanSetting("hushfacebook_turn_off_hdr_brightness", FALSE);

    /**
     * A reel's progress bar stays full size, thumb and all, and a full-screen video's controls stay
     * until a tap hides them ({@link app.morphe.extension.facebook.media.ProgressBar}). Off by
     * default. Off or paused, Facebook shrinks the bar and fades the controls as before.
     */
    public static final BooleanSetting KEEP_PROGRESS_BAR =
            new BooleanSetting("hushfacebook_keep_progress_bar", FALSE);

    /**
     * Facebook's own text, React Native screens' included, drawn in the font {@link #FONT_SOURCE}
     * names instead of Meta's Optimistic, at the same weight and slant. A typeface already on
     * screen keeps its font until Facebook restarts.
     */
    public static final BooleanSetting USE_SYSTEM_FONT =
            new BooleanSetting("hushfacebook_use_system_font", FALSE);

    /**
     * Emoji drawn with the phone's own emoji font instead of the one Meta downloads. An emoji
     * already laid out keeps its look until its text is drawn again, and the quick emoji picker
     * until Facebook restarts. Reactions and stickers are pictures, not text, and don't change.
     */
    public static final BooleanSetting USE_SYSTEM_EMOJI =
            new BooleanSetting("hushfacebook_use_system_emoji", FALSE);

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
     * A tap on the Messenger icon at the top of Facebook opens the Messenger app, while it's
     * installed, instead of Facebook's own Chats. Starts off. Without Messenger, Chats opens as it
     * always did.
     */
    public static final BooleanSetting OPEN_MESSENGER_APP =
            new BooleanSetting("hushfacebook_open_messenger_app", FALSE);

    /**
     * The Upgrades section of Facebook's Menu, the group Facebook types UPSELL, with its offers.
     * Only that group goes: Settings, Help and support and the rest of the Menu stay.
     */
    public static final BooleanSetting HIDE_MENU_UPGRADES =
            new BooleanSetting("hushfacebook_hide_menu_upgrades", TRUE);

    /**
     * The Also from Meta section of Facebook's Menu, the group Facebook types
     * PRODUCTS_FROM_FACEBOOK: its links to Meta's other apps and its cards for Meta's devices.
     */
    public static final BooleanSetting HIDE_MENU_ALSO_FROM_META =
            new BooleanSetting("hushfacebook_hide_menu_also_from_meta", TRUE);

    /**
     * The Muse card in Facebook's Menu, Meta's ad for its AI agent app, which reads as dismissed
     * the way Facebook's own Dismiss leaves it.
     */
    public static final BooleanSetting HIDE_MENU_MUSE =
            new BooleanSetting("hushfacebook_hide_menu_muse", TRUE);

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
     * The share sheet's Send to group button, which makes a new group chat of the people picked,
     * and its new-group row, header icon and search button. Send separately stays. Off until it's
     * turned on.
     */
    public static final BooleanSetting HIDE_SHARE_GROUP_BUTTONS =
            new BooleanSetting("hushfacebook_hide_share_group_buttons", FALSE);

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
     * A cold start, and a return after {@link #APP_LOCK_AFTER}, ask for the phone's screen lock
     * before Facebook shows ({@link app.morphe.extension.facebook.misc.AppLock}). The settings
     * entry's own switch, so every build has it ({@link PatchFamily#ENTRY_SWITCHES}). Off by default.
     * It keeps its value while Hushfacebook is paused, see the block after {@link #APP_LOCK_AFTER}.
     */
    public static final BooleanSetting APP_LOCK =
            new BooleanSetting("hushfacebook_app_lock", FALSE);

    /**
     * The story viewer's menu offers Save on anyone's story, and Save runs Hushfacebook's own
     * download. Off, only your own stories offer it, and it's Facebook's own save.
     */
    public static final BooleanSetting DOWNLOAD_STORIES =
            new BooleanSetting("hushfacebook_download_stories", TRUE);

    /** The Download button the reel patch adds to every reel's sidebar. */
    public static final BooleanSetting DOWNLOAD_REELS =
            new BooleanSetting("hushfacebook_download_reels", TRUE);

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
     * The tab bar keeps Marketplace, Notifications and the profile or Menu tab, and a start from
     * the launcher icon opens Marketplace whatever {@link #START_TAB} says. Home with the news feed,
     * Video, Friends, Feeds, Groups, Gaming and Events go. Facebook builds the bar once, so a change
     * shows when it restarts.
     */
    public static final BooleanSetting MARKETPLACE_ONLY =
            new BooleanSetting("hushfacebook_marketplace_only", FALSE, true);

    /** Silences only recognized social promotions while Marketplace mode is selected. */
    public static final BooleanSetting MARKETPLACE_QUIET_NOTIFICATIONS =
            new BooleanSetting("hushfacebook_marketplace_quiet_notifications", FALSE);

    /** Skip only confirmed feed warm-ups once the tab bar has applied Marketplace mode. */
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
     * The tab bar goes to the bottom of the screen on accounts Facebook gives it at the top
     * ({@link app.morphe.extension.facebook.navigation.BottomTabBar}). Facebook places the bar as
     * its main screen starts, so a change waits for a restart.
     */
    public static final BooleanSetting BOTTOM_TAB_BAR =
            new BooleanSetting("hushfacebook_bottom_tab_bar", FALSE, true);

    /**
     * The tab bar at the bottom slides away while the feed scrolls down and comes back scrolling
     * up, through Facebook's own scroll-away
     * ({@link app.morphe.extension.facebook.navigation.TabBarScrollAway}). Facebook adds the bar to
     * the views that scroll away as its main screen starts, so a change waits for a restart.
     */
    public static final BooleanSetting TAB_BAR_SCROLL_AWAY =
            new BooleanSetting("hushfacebook_tab_bar_scroll_away", FALSE, true);

    /**
     * Facebook's dark mode controller answers dark whatever its own setting says
     * ({@link app.morphe.extension.facebook.theme.ForceDarkMode}), for tablets whose Facebook
     * settings have no Dark mode row. Facebook asks as each screen applies its theme, so a change
     * shows fully after a restart.
     */
    public static final BooleanSetting FORCE_DARK_MODE =
            new BooleanSetting("hushfacebook_force_dark_mode", FALSE, true);

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
     * Posts you've already scrolled past stay out of the feed on later loads
     * ({@link app.morphe.extension.facebook.feed.SeenPosts}). Off by default: it remembers which
     * posts you saw, on the phone only. Posts already on screen are never touched, and a change
     * shows on the next feed load.
     */
    public static final BooleanSetting HIDE_SEEN_POSTS =
            new BooleanSetting("hushfacebook_hide_seen_posts", FALSE);

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
     * Posts and reel captions keep the language they were written in, with Facebook's See
     * translation link under them ({@link app.morphe.extension.facebook.feed.AutoTranslation}). A
     * change shows on the posts and reels drawn after it.
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
     * ({@link app.morphe.extension.facebook.reels.ReelPrompts}). A change shows on the reels built
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
     * choice until someone picks Most relevant, Newest or All comments, so picking the patch changes
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
            new EnumSetting<>("hushfacebook_app_lock_after", AppLock.After.ONE_MINUTE);

    static {
        // The lock guards the phone's owner rather than changing Facebook, so no pause opens it: not
        // the Pause switch, not the marker file someone could leave over USB, and not the safe mode
        // a few quick crashes can turn on. Its own switch, behind the lock, is the only way off.
        // Here, after both fields, since a static block runs in the order it's written.
        Setting.keepWhenPaused(APP_LOCK, APP_LOCK_AFTER);
    }

    /**
     * How large Facebook's text is, as a share of the phone's font size ({@link TextSize}). It
     * isn't a switch: 100% is Facebook as it ships, which is also what a paused Facebook reads.
     * The settings entry carries it, so every build has it.
     */
    public static final EnumSetting<TextSize.Scale> TEXT_SIZE =
            new EnumSetting<>("hushfacebook_text_size", TextSize.Scale.P100);

    /**
     * The colour that stands in for Facebook's blue on links, buttons, switches and the selected
     * tab ({@link AccentColor}), with the Accent color patch in the build. Facebook blue is Facebook
     * as it ships, which is also what a paused Facebook reads. Not a switch.
     */
    public static final EnumSetting<AccentColor.Preset> ACCENT_COLOR =
            new EnumSetting<>("hushfacebook_accent_color", AccentColor.Preset.FACEBOOK);

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
