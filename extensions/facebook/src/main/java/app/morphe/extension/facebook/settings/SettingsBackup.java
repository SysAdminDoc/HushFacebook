/*
 * Forked from:
 * https://github.com/SysAdminDoc/hushfeed/blob/bcc57ee555f4346c4eb1cbdae9f6ca8a3fa6fef4/extensions/tiktok/src/main/java/app/morphe/extension/tiktok/settings/SettingsBackup.java
 * Copyright 2026 Hushfeed contributors (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026: an exact list of the switches in place of every
 * setting, no Feature Gate Lab, reset, undo or journal, an import read into a preview before
 * anything is written, and a refusal of its own for a name given twice.
 */
package app.morphe.extension.facebook.settings;

import android.os.Bundle;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;

import app.morphe.extension.facebook.comments.CommentOrder;
import app.morphe.extension.facebook.reels.BurnInMode;
import app.morphe.extension.facebook.reels.BurnInWait;
import app.morphe.extension.facebook.reels.ReelsOrder;
import app.morphe.extension.facebook.download.DownloadQuality;
import app.morphe.extension.facebook.download.FileNameTemplate;
import app.morphe.extension.facebook.download.SaveFolder;
import app.morphe.extension.facebook.download.SaveTo;
import app.morphe.extension.facebook.download.SendLink;
import app.morphe.extension.facebook.feed.FeedTypeNames;
import app.morphe.extension.facebook.feed.PostSources;
import app.morphe.extension.facebook.feed.PostWords;
import app.morphe.extension.facebook.menu.MenuTiles;
import app.morphe.extension.facebook.media.PlaybackQuality;
import app.morphe.extension.facebook.media.RecentlyWatched;
import app.morphe.extension.facebook.media.SurfaceQuality;
import app.morphe.extension.facebook.misc.AppLock;
import app.morphe.extension.facebook.misc.BreakTimer;
import app.morphe.extension.facebook.misc.HeaderText;
import app.morphe.extension.facebook.misc.OpeningPause;
import app.morphe.extension.facebook.misc.ShareSheetItems;
import app.morphe.extension.facebook.misc.TextSize;
import app.morphe.extension.facebook.feed.ReactionCeiling;
import app.morphe.extension.facebook.theme.AccentColor;
import app.morphe.extension.facebook.theme.AmoledBackground;
import app.morphe.extension.facebook.theme.AmoledLightBackground;
import app.morphe.extension.facebook.theme.MarkColors;
import app.morphe.extension.facebook.navigation.FeedsSubtab;
import app.morphe.extension.facebook.navigation.StartTab;
import app.morphe.extension.facebook.feed.SeenPosts;
import app.morphe.extension.facebook.notifications.QuietHour;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.EnumSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.SettingsJson;
import app.morphe.extension.shared.settings.StringSetting;

/**
 * Hushfacebook's switches as a file, and back.
 *
 * <p>Morphe Manager can export the patches that were picked and the signing key. It can't see the
 * switches, which live in Facebook's own data, so a reinstall or a new phone started them all
 * over. This writes them to a JSON file the person chooses and reads one back.
 *
 * <p>Only the switches in {@link #ALLOWLIST} and the settings in {@link #VALUES} (the word
 * filter's two lists, the top folder saves go to, the save folder and its video and photo
 * subfolders, the save quality, the video file name, what a tap on Download does, the app links go
 * to, the tab Facebook opens on, the order comments open in, the qualities videos, reels and
 * video stories play at, the hours notification quiet hours start and end, how long the app lock
 * waits, the share sheet items to hide and AMOLED's Background color) go out or come in. Pause, safe mode, the debug settings, the app language
 * and the counters Hushfacebook keeps for itself stay out, and so do the log, the diagnostic data
 * and anything about the person or the phone: a file is a format name, a version number, one true
 * or false per switch, two word lists, one top folder, one folder name and two subfolder names,
 * one save quality, one file name template, one download action, one package name or none, one
 * tab, one comment order, three playback qualities, two hours, one lock time, one list of
 * share item types and one color code or none. The word lists go only into the file the
 * person picks, with the rest. An import applies what it read in one preference commit. A file
 * that is too large, isn't JSON, names something twice, holds a value of the wrong type, a word
 * list that isn't one clean list, word lists past the room they share, a folder or a template
 * that isn't one clean name, an app that isn't a package name, a color the Background color row
 * refuses, or a top folder, quality, download
 * action, tab, comment order or lock time this build doesn't offer, or comes from a newer version changes
 * nothing.
 * <p>The release check stays out of the file: it puts the phone online, so it's switched on
 * from the phone's own screen, never by a file.
 *
 * <p>Call the file and preference work on a worker thread. An export reads what it writes on the
 * main thread first ({@link Saved}), so its file is one whole set of choices.
 */
public final class SettingsBackup {
    /**
     * Far more than a settings file needs: one is a few hundred bytes, or about 88 KB at most with
     * both word lists filling the room they share, the sources list filling its own and every
     * other value at its longest, switches and the 2 KB of share sheet items included. That leaves
     * about 8 KB for switches to come. SettingsBackupTest's longest-file check fails once they've
     * used it; raise this then.
     */
    public static final int MAX_BYTES = 96 * 1024;
    public static final String FORMAT = "hushfacebook-settings";
    /** The file shape this build writes and the newest it reads. A file declaring more is refused. */
    static final int SCHEMA = 1;

    static final String FORMAT_NAME = "format";
    static final String SCHEMA_NAME = "schema";
    static final String SETTINGS_NAME = "settings";

    /**
     * The switches a file carries, and the only settings an import can write: every feature
     * switch, and nothing of Hushfacebook's own. A switch added to {@link Settings} stays out
     * until someone decides it belongs here, and SettingsBackupTest fails until they do.
     */
    static final List<BooleanSetting> ALLOWLIST = Collections.unmodifiableList(Arrays.asList(
            Settings.HIDE_SPONSORED_POSTS,
            Settings.HIDE_PROMOTED_POSTS,
            Settings.HIDE_SUGGESTED_POSTS,
            Settings.HIDE_SUGGESTED_FOR_YOU,
            Settings.HIDE_PEOPLE_YOU_MAY_KNOW,
            Settings.HIDE_SUGGESTED_GROUPS,
            Settings.HIDE_GROUP_POSTS_NOT_JOINED,
            Settings.HIDE_STORIES_YOU_MIGHT_LIKE,
            Settings.HIDE_FEED_MEMORIES,
            Settings.HIDE_FEED_FRIEND_REQUESTS,
            Settings.HIDE_FRIENDS_LOCATIONS,
            Settings.HIDE_NOTIFICATION_POST_FOOTER,
            Settings.HIDE_RELATED_POSTS,
            Settings.HIDE_FIND_FRIENDS_CARD,
            Settings.HIDE_SEARCH_FOR_YOU,
            Settings.HIDE_SEARCH_PEOPLE_YOU_MAY_KNOW,
            Settings.HIDE_TOP_STORIES_TRAY,
            Settings.HIDE_STORIES_BETWEEN_POSTS,
            Settings.HIDE_HOME_COMPOSER,
            Settings.HIDE_FEED_REELS,
            Settings.BLOCK_RETURN_REFRESH,
            Settings.RETURN_REFRESH_NO_LIMIT,
            Settings.BACK_KEEPS_HOME,
            Settings.HIDE_AI_DETECTED_POSTS,
            Settings.HIDE_AI_LABELLED_POSTS,
            Settings.HIDE_META_AI_FEED_UNITS,
            Settings.HIDE_AI_CHARACTER_POSTS,
            Settings.HIDE_AI_DETECTED_REELS,
            Settings.HIDE_POSTS_WITH_WORDS,
            Settings.HIDE_POSTS_FROM_SOURCES,
            Settings.POST_WORDS_WHOLE_WORDS,
            Settings.HIDE_PHOTO_POSTS,
            Settings.HIDE_VIDEO_POSTS,
            Settings.HIDE_LINK_POSTS,
            Settings.HIDE_BACKGROUND_POSTS,
            Settings.HIDE_REPOSTS,
            Settings.HIDE_PAGE_POSTS,
            Settings.HIDE_POST_PROMPTS,
            Settings.HIDE_POST_FOLLOW_LINK,
            Settings.HIDE_POST_COUNTS,
            Settings.HIDE_POST_REACTIONS,
            Settings.HIDE_COMMENT_REACTIONS,
            Settings.HIDE_SEEN_POSTS,
            Settings.HIDE_META_AI_QUESTIONS,
            Settings.KEEP_POST_DATES,
            Settings.SHOW_FULL_POST_TEXT,
            Settings.SEE_WHO_SHARED,
            Settings.TURN_OFF_AUTO_TRANSLATION,
            Settings.HIDE_FEEDS_HEADER,
            Settings.HIDE_SPONSORED_STORIES,
            Settings.HIDE_SUGGESTED_STORIES,
            Settings.HIDE_CONTACT_IMPORT_CARD,
            Settings.HIDE_STORY_PROMPTS,
            Settings.HIDE_PAGE_STORIES,
            Settings.HIDE_SEEN_STORIES,
            Settings.BLOCK_STORY_AUTO_ADVANCE,
            Settings.LOOP_STORIES,
            Settings.VIEW_STORIES_ANONYMOUSLY,
            Settings.MARK_STORIES_SEEN,
            Settings.UPLOAD_STORIES_IN_HD,
            Settings.LONGER_STORY_MUSIC,
            Settings.STORIES_TRAY_CIRCLES,
            Settings.PINCH_TO_ZOOM,
            Settings.PINCH_TO_ZOOM_KEEP,
            Settings.PINCH_TO_ZOOM_DOUBLE_TAP,
            Settings.HIDE_SPONSORED_REELS,
            Settings.HIDE_SPONSORED_SEARCH_RESULTS,
            Settings.HIDE_SPONSORED_PROFILE_POSTS,
            Settings.HIDE_PROFILE_NOTES,
            Settings.LOCAL_PROFILE_COUNTS,
            Settings.POST_SAVE_BUTTON,
            Settings.POST_DOWNLOAD_BUTTON,
            Settings.HIDE_POST_LIKE_BUTTON,
            Settings.HIDE_POST_COMMENT_BUTTON,
            Settings.HIDE_POST_SHARE_BUTTON,
            Settings.HIDE_POST_SEND_BUTTON,
            Settings.HIDE_SPONSORED_MARKETPLACE_LISTINGS,
            Settings.MARKETPLACE_ADVANCED_SEARCH,
            Settings.MARKETPLACE_FILTERS_BUTTON,
            Settings.MARKETPLACE_LISTING_DATE,
            Settings.SHOW_SELLER_VIEW_PROFILE,
            Settings.BLOCK_GAME_ADS,
            Settings.ANSWER_REWARDED_GAME_ADS,
            Settings.HIDE_AFFILIATE_LINKS,
            Settings.HIDE_REEL_CHIPS,
            Settings.HIDE_REEL_FOLLOW_BUTTON,
            Settings.HIDE_VIDEO_SUBSCRIBE_BUTTON,
            Settings.HIDE_REEL_SOCIAL_FOOTER,
            Settings.HIDE_REEL_THREADS_CARDS,
            Settings.REEL_CLEAN_MODE,
            Settings.PLAY_REELS_ONCE,
            Settings.ORIGINAL_AUDIO,
            Settings.HIDE_REEL_SEARCH_PILL,
            Settings.HIDE_VIEWER_TOP_BUTTONS,
            Settings.BURN_IN_STAY_DIM,
            Settings.HIDE_REEL_COUNTS,
            Settings.HIDE_REEL_SAVE_BUTTON,
            Settings.HIDE_REEL_WHATSAPP_BUTTON,
            Settings.HIDE_REEL_LIKE_BUTTON,
            Settings.HIDE_REEL_COMMENT_BUTTON,
            Settings.HIDE_REEL_SHARE_BUTTON,
            Settings.SHOW_REEL_COMMENT_BUTTON,
            Settings.SHOW_REEL_POST_DATE,
            Settings.HIDE_REEL_SOUND_BUTTON,
            Settings.HIDE_REEL_AUDIO_LINE,
            Settings.HIDE_SLOW_CONNECTION_MESSAGE,
            Settings.FEED_VIDEOS_ALONE,
            Settings.PLAY_FEED_REELS_WHOLE,
            Settings.DONT_SEND_REEL_WATCH_HISTORY,
            Settings.NEWEST_REELS_FIRST,
            Settings.PROFILE_REELS_MOST_VIEWED,
            Settings.HOLD_ANALYTICS_UPLOADS,
            Settings.HOLD_CAMERA_ROLL_PROCESSING,
            Settings.ALLOW_SCREENSHOTS,
            Settings.TURN_OFF_HAPTICS,
            Settings.HIDE_DATA_MODE_BANNER,
            Settings.HIDE_FREE_MODE_BANNER,
            Settings.TURN_OFF_SCREEN_TRANSITIONS,
            Settings.UNLOCK_APP_ICONS,
            Settings.USE_APP_ICON_IN_HEADER,
            Settings.BLOCK_SCREENSHOT_DETECTION,
            Settings.HIDE_CHAT_TYPING,
            Settings.HIDE_COMMENT_TYPING,
            Settings.HIDE_READ_RECEIPTS,
            Settings.ORIGINAL_CHAT_MEDIA,
            Settings.TURN_OFF_DOUBLE_TAP_LIKE,
            Settings.DOUBLE_TAP_SKIP,
            Settings.TAP_SHOWS_CONTROLS,
            Settings.DOUBLE_TAP_OPENS_COMMENTS,
            Settings.LONG_PRESS_OPENS_COMMENTS,
            Settings.SELECT_POST_TEXT,
            Settings.KEEP_REEL_SPEED,
            Settings.KEEP_VIDEO_SPEED,
            Settings.SLOWER_REEL_SPEEDS,
            Settings.SPEED_ON_REPLAYS,
            Settings.HOLD_REEL_FOR_2X,
            Settings.HOLD_REEL_RIGHT_EDGE,
            Settings.HOLD_FEED_VIDEO_FOR_2X,
            Settings.HIDE_2X_LOCK_HINT,
            Settings.DEFAULT_COMMENT_ORDER,
            Settings.HIDE_META_AI_SUMMARIES,
            Settings.LIKE_ONLY,
            Settings.HIDE_COMMENT_GIF_STICKER_BUTTONS,
            Settings.HIDE_COMMENT_EMOJI_ROW,
            Settings.OPEN_REPLY_THREADS,
            Settings.LOAD_WHOLE_REPLY_THREADS,
            Settings.TAP_ANYWHERE_ON_VIEW_MORE_REPLIES,
            Settings.HIDE_POST_COMMENT_PREVIEWS,
            Settings.HIDE_COMMENT_DOWNVOTE_BUTTON,
            Settings.HIDE_COMMENT_LIKE_BUTTON,
            Settings.COMMENT_BLOCK_BUTTON,
            Settings.HIDE_RELATED_GROUPS_UNDER_COMMENTS,
            Settings.HIDE_SHOP_LINK_COMMENTS,
            Settings.HIDE_COMMENTS_WITH_WORDS,
            Settings.HIDE_INCOMING_COMMENT_TYPING,
            Settings.HIDE_NEW_COMMENTS_PILL,
            Settings.HIDE_VIDEO_COMMENT_COMPOSER,
            Settings.OPEN_COMMENTS_FULL_HEIGHT,
            Settings.PAUSE_REELS_UNDER_COMMENTS,
            Settings.TAP_REELS_ABOVE_COMMENTS,
            Settings.TAG_SUGGESTIONS_ONLY_AFTER_AT,
            Settings.TAP_TO_PLAY,
            Settings.TAP_TO_PLAY_REELS_AFTER_FIRST,
            Settings.TAP_TO_PLAY_STORIES,
            Settings.INLINE_FEED_VIDEO_CONTROLS,
            Settings.FEED_VIDEO_CAST_BUTTON,
            Settings.RESUME_LONG_VIDEOS,
            Settings.RECENTLY_WATCHED,
            Settings.FRAME_STEP,
            Settings.DEFAULT_PLAYBACK_QUALITY,
            Settings.PICTURE_IN_PICTURE,
            Settings.PICTURE_IN_PICTURE_FROM_MENU,
            Settings.PICTURE_IN_PICTURE_BUTTON,
            Settings.TURN_OFF_HDR_BRIGHTNESS,
            Settings.PREFER_SDR_RENDITIONS,
            Settings.DRAW_VIDEOS_WITH_THE_SCREEN,
            Settings.SKIP_SILENT_PARTS,
            Settings.KEEP_FEED_VIDEOS_MUTED,
            Settings.MUTE_STORIES,
            Settings.KEEP_PROGRESS_BAR,
            Settings.KEEP_PROGRESS_BAR_TIME,
            Settings.TAP_TO_SEEK,
            Settings.USE_SYSTEM_FONT,
            Settings.USE_SYSTEM_EMOJI,
            Settings.OPEN_LINKS_EXTERNALLY,
            Settings.HOLD_LINK_HISTORY,
            Settings.SANITIZE_SHARING_LINKS,
            Settings.SHARE_POST_OWN_LINK,
            Settings.HIDE_SHARE_GROUP_BUTTONS,
            Settings.HIDE_SHARE_PEOPLE_ROW,
            Settings.STOP_UPDATE_PROMPTS,
            Settings.DOWNLOAD_STORIES,
            Settings.SAVE_STORY_PHOTOS,
            Settings.DOWNLOAD_REELS,
            Settings.SHOW_REEL_DOWNLOAD_BUTTON,
            Settings.REEL_CAST_BUTTON,
            Settings.COPY_REEL_CAPTION,
            Settings.DOWNLOAD_VIDEOS,
            Settings.CLIPBOARD_DOWNLOAD,
            Settings.DOWNLOAD_PHOTOS,
            Settings.POST_MENU_PHOTO_SAVE,
            Settings.OPEN_IN_ANOTHER_APP,
            Settings.POST_MENU_DOWNLOAD_FIRST,
            Settings.DOWNLOAD_COMPATIBLE,
            Settings.DOWNLOAD_CONVERT,
            Settings.DOWNLOAD_QUIET,
            Settings.OPEN_ON_CHOSEN_TAB,
            Settings.FOLLOWING_FEED_HOME,
            Settings.SAVED_SHORTCUT,
            Settings.WATCH_HISTORY_SHORTCUT,
            Settings.FREE_STUFF_SHORTCUT,
            Settings.SETTINGS_SHORTCUT,
            Settings.SETTINGS_LOGO_PRESS,
            Settings.SETTINGS_MENU_ROW,
            Settings.APP_LOCK,
            Settings.SAVE_CRASH_FILE,
            Settings.CLOSE_WHEN_LEFT,
            Settings.OPENING_PAUSE,
            Settings.TAKE_BREAKS,
            Settings.MARKETPLACE_ONLY,
            Settings.EVENTS_ONLY,
            Settings.MARKETPLACE_QUIET_NOTIFICATIONS,
            Settings.MARKETPLACE_SKIP_FEED_PREFETCH,
            Settings.HIDE_REELS_TAB,
            Settings.HIDE_REELS_TAB_DOT,
            Settings.HIDE_HOME_TAB_BADGE,
            Settings.HIDE_FRIENDS_TAB_BADGE,
            Settings.HIDE_MARKETPLACE_TAB_BADGE,
            Settings.HIDE_NOTIFICATIONS_TAB_BADGE,
            Settings.HIDE_MENU_TAB_BADGE,
            Settings.HIDE_GROUPS_TAB_BADGE,
            Settings.HIDE_OTHER_TAB_BADGES,
            Settings.HIDE_APP_ICON_COUNT,
            Settings.HIDE_FEEDS_TAB,
            Settings.HIDE_FRIENDS_TAB,
            Settings.HIDE_MARKETPLACE_TAB,
            Settings.HIDE_GROUPS_TAB,
            Settings.HIDE_GAMING_TAB,
            Settings.HIDE_EVENTS_TAB,
            Settings.HIDE_DATING_TAB,
            Settings.HIDE_PROFESSIONAL_DASHBOARD_TAB,
            Settings.HIDE_SAVED_TAB,
            Settings.HIDE_AD_CENTER_TAB,
            Settings.HIDE_CREATE_TAB,
            Settings.HIDE_EXPLORE_TAB,
            Settings.HIDE_JOBS_TAB,
            Settings.BOTTOM_TAB_BAR,
            Settings.TOP_TAB_BAR,
            Settings.TAB_BAR_SCROLL_AWAY,
            Settings.KEEP_TAB_BAR,
            Settings.KEEP_TOP_BAR,
            Settings.PHONE_LAYOUT_ON_BIG_SCREENS,
            Settings.TRANSLUCENT_BARS,
            Settings.HIDE_STATUS_BAR,
            Settings.HIDE_MESSENGER_BUTTON,
            Settings.HIDE_MESSENGER_COUNT,
            Settings.HIDE_FACEBOOK_LOGO,
            Settings.HIDE_CREATE_BUTTON,
            Settings.HIDE_SEARCH_BUTTON,
            Settings.SEARCH_WITH_PICTURE,
            Settings.FORCE_DARK_MODE,
            Settings.AMOLED_FLAT_CARDS,
            Settings.AMOLED_FEED_GAPS,
            Settings.HIDE_REEL_PROMPTS,
            Settings.HIDE_GET_MESSENGER_CARD,
            Settings.HIDE_CHAT_NOTES_TRAY,
            Settings.HIDE_CHAT_PROMOTIONS,
            Settings.HIDE_CHAT_META_AI_BUTTON,
            Settings.OPEN_MESSENGER_APP,
            Settings.LEAVE_CALLS_AND_CHATS_TO_MESSENGER,
            Settings.HIDE_MENU_UPGRADES,
            Settings.HIDE_MENU_ALSO_FROM_META,
            Settings.HIDE_MENU_HELP,
            Settings.KEEP_MENU_SETTINGS_EXPANDED,
            Settings.HIDE_MENU_MUSE,
            Settings.HIDE_MENU_META_AI,
            Settings.HIDE_EDITS_UPSELLS,
            Settings.HIDE_THREADS_CROSS_POSTING,
            Settings.HIDE_THREADS_SHARE_BUTTON,
            Settings.HIDE_MUSE_SHARE_BUTTON,
            Settings.HIDE_META_VERIFIED_UPSELLS,
            Settings.HIDE_AVATAR_UPSELLS,
            Settings.HIDE_META_AI_IMAGINE,
            Settings.HIDE_META_AI_POST_BUTTONS,
            Settings.HIDE_META_AI_IN_SEARCH,
            Settings.DONT_SAVE_SEARCH_HISTORY,
            Settings.SEARCH_DATE_RANGE,
            Settings.BLOCK_TRENDING_VIDEO_NOTIFICATIONS,
            Settings.BLOCK_MEMORY_NOTIFICATIONS,
            Settings.BLOCK_BIRTHDAY_NOTIFICATIONS,
            Settings.BLOCK_HIGHLIGHT_NOTIFICATIONS,
            Settings.BLOCK_PEOPLE_YOU_MAY_KNOW_NOTIFICATIONS,
            Settings.BLOCK_NEARBY_NOTIFICATIONS,
            Settings.BLOCK_ACCOUNT_SETUP_NOTIFICATIONS,
            Settings.BLOCK_GROUP_ACTIVITY_NOTIFICATIONS,
            Settings.BLOCK_EVENT_NOTIFICATIONS,
            Settings.BLOCK_LIVE_VIDEO_NOTIFICATIONS,
            Settings.BLOCK_REACTION_NOTIFICATIONS,
            Settings.NOTIFICATION_QUIET_HOURS));

    /**
     * The word filter's two lists, held in a file exactly as the settings row stores them: one
     * phrase per line within {@link PostWords}' bounds. A value {@link PostWords#clean} would change
     * refuses the whole file, as a switch that isn't true or false does, so a file can't slip in a
     * list longer or looser than the row allows. So do lists past the room the two share
     * ({@link PostWords#MAX_LIST_BYTES}), which every file this class writes fits whole.
     */
    static final StringSetting HIDDEN = Settings.HIDDEN_WORDS;
    static final StringSetting KEPT = Settings.KEPT_WORDS;

    /**
     * The people, Pages and sites list, held in a file exactly as its row stores it, within
     * {@link PostSources}' bounds. A value {@link PostSources#clean} would change refuses the whole
     * file, as a word list does.
     */
    static final StringSetting SOURCES = Settings.HIDDEN_SOURCES;

    /**
     * The top folder saves go to, held in a file as its {@link SaveTo#fileValue}. Anything else
     * refuses the whole file, as a quality does. Saves already made stay where they are.
     */
    static final EnumSetting<SaveTo> TO = Settings.SAVE_TO;

    /**
     * The one setting a file carries that isn't a switch: the folder saves go to. A file holds it
     * as the clean folder name the saves use, and an import takes nothing else there. A value
     * {@link SaveFolder#sanitize} would change refuses the whole file, as a switch that isn't true
     * or false does, so a file can't point the saves at a path. A character this phone doesn't know
     * yet, from a newer Android, counts as an ordinary one ({@link SaveFolder#isImportable}), and
     * the folder taken is the name the saves here will use.
     */
    static final StringSetting FOLDER = Settings.SAVE_FOLDER;

    /**
     * The folders inside the save folder that video and photo saves go in, held in a file as the
     * clean name the saves use, or blank for none, and taken back only as one of those
     * ({@link SaveFolder#isImportableSubfolder}), so a file can't point the saves at a path.
     */
    static final StringSetting VIDEO_SUBFOLDER = Settings.VIDEO_SUBFOLDER;
    static final StringSetting PHOTO_SUBFOLDER = Settings.PHOTO_SUBFOLDER;

    /**
     * The quality video saves ask for, held in a file as its {@link DownloadQuality#fileValue}.
     * Anything but one of those refuses the whole file, as a switch that isn't true or false does.
     */
    static final EnumSetting<DownloadQuality> QUALITY = Settings.DOWNLOAD_QUALITY;

    /**
     * The name saved videos get, held in a file as the clean template the saves use and taken back
     * only as one, like the folder ({@link FileNameTemplate#isImportable}).
     */
    static final StringSetting FILE_NAME = Settings.FILENAME_TEMPLATE;

    /**
     * The name saved photos get, held in a file the same way and taken back only as a clean photo
     * template ({@link FileNameTemplate#isImportablePhoto}).
     */
    static final StringSetting PHOTO_NAME = Settings.PHOTO_FILENAME_TEMPLATE;

    /**
     * What a tap on Download does, held in a file as its {@link SendLink.Action#fileValue}.
     * Anything else refuses the whole file, as a quality does.
     */
    static final EnumSetting<SendLink.Action> ACTION = Settings.DOWNLOAD_ACTION;

    /**
     * The app links are sent to, held in a file as the package name they really go to, or blank
     * for Android's chooser ({@link SendLink#fileApp}), and taken back only as one of those, so a
     * file can't slip in a name the row would turn down.
     */
    static final StringSetting APP = Settings.SEND_TO_APP;

    /**
     * The tab a start from the launcher icon opens on, held in a file as its
     * {@link StartTab#fileValue}. Anything else refuses the whole file, as a quality does.
     */
    static final EnumSetting<StartTab> START = Settings.START_TAB;

    /**
     * The filter the Feeds tab opens on after such a start, held in a file as its
     * {@link FeedsSubtab#fileValue}. Anything else refuses the whole file, as a tab does.
     */
    static final EnumSetting<FeedsSubtab> SUBTAB = Settings.FEEDS_SUBTAB;

    /**
     * The feed Home shows while Following feed on Home is on, held in a file as its
     * {@link FeedsSubtab#fileValue}, like the Feeds filter. Anything else refuses the whole file.
     */
    static final EnumSetting<FeedsSubtab> HOME_FEED = Settings.HOME_FEED;

    /**
     * AMOLED's Background color, held in a file as its row keeps it: blank, or {@code #RRGGBB} in
     * capitals. A colour {@link AmoledBackground} refuses, or one written any other way, refuses the
     * whole file, as a Menu tiles list does.
     */
    static final StringSetting BACKGROUND = Settings.AMOLED_BACKGROUND;

    /** AMOLED's Light background color, held and refused the same way, by {@link AmoledLightBackground}'s rule. */
    static final StringSetting LIGHT_BACKGROUND = Settings.AMOLED_LIGHT_BACKGROUND;

    /**
     * Badge and logo colors, each held in a file as its row keeps it: blank, or {@code #RRGGBB} in
     * capitals that {@link MarkColors} takes. Anything else refuses the whole file, as a Background
     * color does.
     */
    static final StringSetting BADGE = Settings.BADGE_COLOR;
    static final StringSetting LOGO = Settings.LOGO_COLOR;
    static final StringSetting TAB = Settings.TAB_COLOR;

    /**
     * Home header text, held in a file as its row keeps it: blank, or one line {@link HeaderText}
     * leaves as it is. Text written any other way refuses the whole file.
     */
    static final StringSetting HEADER_TEXT = Settings.HEADER_TEXT;

    /**
     * The order comment sheets ask for, held in a file as its {@link CommentOrder#fileValue}.
     * Anything else refuses the whole file, as a tab does.
     */
    static final EnumSetting<CommentOrder> ORDER = Settings.COMMENT_ORDER;

    /**
     * The quality videos play at, held in a file as its {@link PlaybackQuality#fileValue}. Anything
     * else refuses the whole file, as a comment order does.
     */
    static final EnumSetting<PlaybackQuality> PLAYBACK = Settings.PLAYBACK_QUALITY;

    /**
     * The quality reels and video stories play at, each held in a file as its
     * {@link SurfaceQuality#fileValue}. Anything else refuses the whole file, as a playback quality does.
     */
    static final EnumSetting<SurfaceQuality> REELS_QUALITY = Settings.REELS_PLAYBACK_QUALITY;
    static final EnumSetting<SurfaceQuality> STORIES_QUALITY = Settings.STORIES_PLAYBACK_QUALITY;

    /**
     * What Sort loaded reels sorts by, held in a file as its {@link ReelsOrder#fileValue}. Anything
     * else refuses the whole file, as a comment order does.
     */
    static final EnumSetting<ReelsOrder> REELS_ORDER = Settings.REELS_ORDER;

    /**
     * Burn-in guard and its Dim after wait (#288), held in a file as their {@link BurnInMode#fileValue}
     * and {@link BurnInWait#fileValue}. Anything else refuses the whole file, as a reels order does.
     */
    static final EnumSetting<BurnInMode> BURN_IN = Settings.BURN_IN_GUARD;
    static final EnumSetting<BurnInWait> BURN_IN_WAIT = Settings.BURN_IN_WAIT;

    /**
     * The hours notification quiet hours start and end, each held in a file as its
     * {@link QuietHour#fileValue}, a 24-hour time on the hour. Anything else refuses the whole file,
     * as a playback quality does.
     */
    static final EnumSetting<QuietHour> QUIET_FROM = Settings.QUIET_HOURS_FROM;
    static final EnumSetting<QuietHour> QUIET_UNTIL = Settings.QUIET_HOURS_UNTIL;

    /**
     * How long Facebook may be away before the app lock asks again, held in a file as its
     * {@link AppLock.After#fileValue}. Anything else refuses the whole file, as a comment order does.
     */
    static final EnumSetting<AppLock.After> LOCK_AFTER = Settings.APP_LOCK_AFTER;

    /**
     * How long the opening pause counts down, held in a file as its
     * {@link OpeningPause.Length#fileValue}. Anything else refuses the whole file, as a lock time does.
     */
    static final EnumSetting<OpeningPause.Length> PAUSE_LENGTH = Settings.OPENING_PAUSE_LENGTH;

    /**
     * How long Take breaks lets browsing go and how long a break lasts, held in a file as their
     * {@link BreakTimer.Use#fileValue} and {@link BreakTimer.Length#fileValue}. Anything else refuses
     * the whole file, as a pause length does. The time used and a break under way never go in a file.
     */
    static final EnumSetting<BreakTimer.Use> BREAK_AFTER = Settings.BREAK_AFTER;
    static final EnumSetting<BreakTimer.Length> BREAK_LENGTH = Settings.BREAK_LENGTH;

    /**
     * How long a Recently watched entry stays, held in a file as its
     * {@link RecentlyWatched.Keep#fileValue}. Anything else refuses the whole file, as a pause
     * length does. The entries themselves never go in a file.
     */
    static final EnumSetting<RecentlyWatched.Keep> WATCHED_KEEP = Settings.RECENTLY_WATCHED_KEEP;

    /**
     * How long a seen post stays hidden, held in a file as its {@link SeenPosts.Keep#fileValue}.
     * Anything else refuses the whole file, as a lock time does. The list of seen posts itself is
     * never carried: it stays on the phone.
     */
    static final EnumSetting<SeenPosts.Keep> SEEN_KEEP = Settings.SEEN_POSTS_KEEP;

    /**
     * How large Facebook's text is, held in a file as its {@link TextSize.Scale#fileValue}, the
     * percentage. Anything else refuses the whole file, as a lock time does.
     */
    static final EnumSetting<TextSize.Scale> TEXT_SIZE = Settings.TEXT_SIZE;

    /**
     * The accent color, held in a file as its {@link AccentColor.Preset#fileValue}. Anything else
     * refuses the whole file, as a text size does.
     */
    static final EnumSetting<AccentColor.Preset> ACCENT = Settings.ACCENT_COLOR;

    /**
     * The reaction count above which the feed hides a post, held in a file as its
     * {@link ReactionCeiling#fileValue}. Anything else refuses the whole file, as an accent color does.
     */
    static final EnumSetting<ReactionCeiling> CEILING = Settings.HIDE_POSTS_OVER_REACTIONS;

    /**
     * The share sheet items to hide, held in a file exactly as their row stores them: Facebook's
     * type names, commas between. A type this build doesn't name is carried like any other, so a
     * newer Facebook's items survive the trip. A list {@link ShareSheetItems#clean} would change
     * refuses the whole file, as a sources list does. The types Facebook has offered on this phone
     * stay on it.
     */
    static final StringSetting SHARE_ITEMS = Settings.HIDDEN_SHARE_ITEMS;

    /**
     * The Menu tiles to hide, held in a file exactly as their row stores them: the first word of each
     * tile's deep link, commas between. A key this build doesn't list is carried like any other. A list
     * {@link MenuTiles#clean} would change refuses the whole file, as a share items list does.
     */
    static final StringSetting MENU_TILES = Settings.HIDDEN_MENU_TILES;

    /**
     * The feed types to hide, held in a file exactly as their row stores them: GraphQL type names,
     * commas between. A type this build hasn't seen is carried like any other. A list
     * {@link FeedTypeNames#clean} would change refuses the whole file, as a Menu tiles list does.
     */
    static final StringSetting FEED_TYPES = Settings.HIDDEN_FEED_TYPES;

    /** The settings a file carries that aren't switches, in the order Settings declares them. */
    static final List<Setting<?>> VALUES = Collections.unmodifiableList(
            Arrays.<Setting<?>>asList(HIDDEN, KEPT, SOURCES, FEED_TYPES, CEILING, BURN_IN, BURN_IN_WAIT, REELS_ORDER, WATCHED_KEEP, SHARE_ITEMS, MENU_TILES, HOME_FEED, BACKGROUND, LIGHT_BACKGROUND, HEADER_TEXT, SEEN_KEEP, TO, FOLDER, VIDEO_SUBFOLDER, PHOTO_SUBFOLDER, QUALITY,
                    FILE_NAME, PHOTO_NAME, ACTION, APP, START, SUBTAB, ORDER, PLAYBACK, REELS_QUALITY, STORIES_QUALITY,
                    QUIET_FROM, QUIET_UNTIL, LOCK_AFTER, PAUSE_LENGTH, BREAK_AFTER, BREAK_LENGTH, TEXT_SIZE, ACCENT, BADGE,
                    LOGO, TAB));

    /** The longest name or value a file holds that isn't a word list, far past a package name. */
    private static final int MAX_OTHER_CHARS = 1024;

    /**
     * The longest string a file can hold, in Java chars: a word list at its longest, which a file
     * carries whole, or any other name or value.
     */
    static final int MAX_STRING_CHARS = Math.max(PostWords.MAX_STORED_CHARS, MAX_OTHER_CHARS);

    /**
     * Bounds for the parser. A string may be as long as the longest this class writes, and the
     * rest are well past anything it writes, so a file built to be expensive to read is refused
     * before it is.
     */
    private static final SettingsJson.Limits LIMITS = new SettingsJson.Limits(8, 1024, MAX_STRING_CHARS, 256, MAX_BYTES);

    /** Written by some editors at the start of a UTF-8 file. It isn't part of the JSON. */
    private static final String BYTE_ORDER_MARK = String.valueOf((char) 0xFEFF);

    private SettingsBackup() {
    }

    /**
     * Why a file was refused. Each one reaches the person as its own sentence: a download that
     * stopped halfway, a file from a newer Hushfacebook and a photo picked by mistake call for
     * different things.
     */
    public enum Reason {
        /** Larger than any settings file. */
        SIZE,
        /** Not UTF-8 text at all. */
        ENCODING,
        /** Text that isn't one JSON object, which is what a part-finished download is. */
        DAMAGED,
        /** A name given twice in one object, so which of its values was meant can't be told. */
        DUPLICATE,
        /** JSON, but not a Hushfacebook settings file, or one whose version isn't a version. */
        FORMAT,
        /** A settings file from a newer Hushfacebook, in a shape this build doesn't know. */
        SCHEMA,
        /** A switch whose value isn't true or false. */
        VALUE,
        /** Word lists that, beside the one it leaves as it is, don't fit in the room the two share. */
        WORDS,
        /** The file couldn't be opened or read to the end. */
        UNREADABLE
    }

    /** A file refused before anything was written. */
    public static final class Rejected extends Exception {
        public final Reason reason;

        Rejected(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }
    }

    /** A write that failed. Whether the switches are all back as they were is known and said. */
    public static final class ApplyFailed extends Exception {
        public final boolean rolledBack;

        ApplyFailed(boolean rolledBack, Throwable cause) {
            super(cause.getClass().getSimpleName(), cause);
            this.rolledBack = rolledBack;
        }
    }

    /**
     * What a file says: a value for each switch it names, the folder, the quality, the file name,
     * the start tab, the comment order, the playback quality, the download action, the app links go
     * to, the top folder, the Feeds filter, the two subfolders, the reels and video stories
     * qualities, the quiet hours, the app lock's time and the Background color when it names them, and
     * how many other names it holds.
     */
    public static final class Snapshot {
        private static final String SWITCHES = "switches";
        private static final String UNKNOWN = "unknown";
        private static final String FOLDER_NAME = "folder";
        private static final String QUALITY_NAME = "quality";
        private static final String FILE_NAME_NAME = "file_name";
        private static final String START_NAME = "start_tab";
        private static final String ORDER_NAME = "comment_order";
        private static final String HIDDEN_NAME = "hidden_words";
        private static final String KEPT_NAME = "kept_words";
        private static final String SOURCES_NAME = "hidden_sources";
        private static final String PLAYBACK_NAME = "playback_quality";
        private static final String ACTION_NAME = "download_action";
        private static final String APP_NAME = "send_to_app";
        private static final String TO_NAME = "save_to";
        private static final String SUBTAB_NAME = "feeds_subtab";
        private static final String PHOTO_NAME_NAME = "photo_name";
        private static final String VIDEO_SUBFOLDER_NAME = "video_subfolder";
        private static final String PHOTO_SUBFOLDER_NAME = "photo_subfolder";
        private static final String REELS_QUALITY_NAME = "reels_quality";
        private static final String STORIES_QUALITY_NAME = "stories_quality";
        private static final String QUIET_FROM_NAME = "quiet_hours_from";
        private static final String QUIET_UNTIL_NAME = "quiet_hours_until";
        private static final String LOCK_AFTER_NAME = "app_lock_after";
        private static final String SEEN_KEEP_NAME = "seen_posts_keep";
        private static final String PAUSE_LENGTH_NAME = "opening_pause_length";
        private static final String BREAK_AFTER_NAME = "break_after";
        private static final String BREAK_LENGTH_NAME = "break_length";
        private static final String WATCHED_KEEP_NAME = "recently_watched_keep";
        private static final String TEXT_SIZE_NAME = "text_size";
        private static final String ACCENT_NAME = "accent_color";
        private static final String CEILING_NAME = "hide_posts_over_reactions";
        private static final String SHARE_ITEMS_NAME = "hidden_share_items";
        private static final String MENU_TILES_NAME = "hidden_menu_tiles";
        private static final String FEED_TYPES_NAME = "hidden_feed_types";
        private static final String HOME_FEED_NAME = "home_feed";
        private static final String BACKGROUND_NAME = "amoled_background";
        private static final String LIGHT_BACKGROUND_NAME = "amoled_light_background";
        private static final String BADGE_NAME = "badge_color";
        private static final String LOGO_NAME = "logo_color";
        private static final String TAB_NAME = "tab_color";
        private static final String HEADER_TEXT_NAME = "header_text";
        private static final String REELS_ORDER_NAME = "reels_order";
        private static final String BURN_IN_NAME = "burn_in_guard";
        private static final String BURN_IN_WAIT_NAME = "burn_in_wait";

        /** In {@link #ALLOWLIST} order, and only the switches the file named. */
        final Map<BooleanSetting, Boolean> values;
        /** The clean folder name the file holds, or null when it names none. */
        @Nullable
        final String folder;
        /** The save quality the file holds, or null when it names none. */
        @Nullable
        final DownloadQuality quality;
        /** The clean file name template the file holds, or null when it names none. */
        @Nullable
        final String fileName;
        /** The tab Facebook opens on that the file holds, or null when it names none. */
        @Nullable
        final StartTab start;
        /** The order comments open in that the file holds, or null when it names none. */
        @Nullable
        final CommentOrder order;
        /** The clean list of words to hide the file holds, or null when it names none. */
        @Nullable
        final String hidden;
        /** The clean list of words that keep a post the file holds, or null when it names none. */
        @Nullable
        final String kept;
        /** The quality videos play at that the file holds, or null when it names none. */
        @Nullable
        final PlaybackQuality playback;
        /** What a tap on Download does that the file holds, or null when it names none. */
        @Nullable
        final SendLink.Action action;
        /** The package name links go to that the file holds, blank for Android's chooser, or null when it names none. */
        @Nullable
        final String app;
        /** The top folder saves go to that the file holds, or null when it names none. */
        @Nullable
        final SaveTo to;
        /** The filter the Feeds tab opens on that the file holds, or null when it names none. */
        @Nullable
        final FeedsSubtab subtab;
        /** The clean people, Pages and sites list the file holds, or null when it names none. */
        @Nullable
        final String sources;
        /** The clean photo file name template the file holds, or null when it names none. */
        @Nullable
        final String photoName;
        /** The clean video subfolder the file holds, blank for none, or null when it names none. */
        @Nullable
        final String videoSubfolder;
        /** The clean photo subfolder the file holds, blank for none, or null when it names none. */
        @Nullable
        final String photoSubfolder;
        /** The quality reels play at that the file holds, or null when it names none. */
        @Nullable
        final SurfaceQuality reelsQuality;
        /** The quality video stories play at that the file holds, or null when it names none. */
        @Nullable
        final SurfaceQuality storiesQuality;
        /** The hour quiet hours start that the file holds, or null when it names none. */
        @Nullable
        final QuietHour quietFrom;
        /** The hour quiet hours end that the file holds, or null when it names none. */
        @Nullable
        final QuietHour quietUntil;
        /** How long the app lock waits that the file holds, or null when it names none. */
        @Nullable
        final AppLock.After lockAfter;
        /** How large the file says Facebook's text is, or null when it names none. */
        @Nullable
        final TextSize.Scale textSize;
        /** The accent the file says to use, or null when it names none. */
        @Nullable
        final AccentColor.Preset accent;
        /** The reaction ceiling the file says to use, or null when it names none. */
        @Nullable
        final ReactionCeiling ceiling;
        /** How long the file says a seen post stays hidden, or null when it names none. Set once, as it's read. */
        @Nullable
        SeenPosts.Keep seenKeep;
        /** How long the file says the opening pause counts down, or null when it names none. Set once, as it's read. */
        @Nullable
        OpeningPause.Length pauseLength;
        /** How long the file says browsing goes before a break, or null when it names none. Set once, as it's read. */
        @Nullable
        BreakTimer.Use breakAfter;
        /** How long the file says a break lasts, or null when it names none. Set once, as it's read. */
        @Nullable
        BreakTimer.Length breakLength;
        /** How long the file says a Recently watched entry stays, or null when it names none. Set once, as it's read. */
        @Nullable
        RecentlyWatched.Keep watchedKeep;
        /** The clean list of share sheet items to hide the file holds, or null when it names none. Set once, as it's read. */
        @Nullable
        String shareItems;
        /** The clean list of Menu tiles to hide the file holds, or null when it names none. Set once, as it's read. */
        @Nullable
        String menuTiles;
        /** The clean list of feed types to hide the file holds, or null when it names none. Set once, as it's read. */
        @Nullable
        String feedTypes;
        /** The feed the file says Home shows, or null when it names none. Set once, as it's read. */
        @Nullable
        FeedsSubtab homeFeed;
        /** The Background color the file holds, blank or {@code #RRGGBB}, or null when it names none. Set once, as it's read. */
        @Nullable
        String background;
        /** The Light background color the file holds, the same way. */
        @Nullable
        String lightBackground;
        /** The Badge color the file holds, the same way. */
        @Nullable
        String badge;
        /** The Logo color the file holds, the same way. */
        @Nullable
        String logo;
        /** The Selected tab color the file holds, the same way. */
        @Nullable
        String tab;
        /** The Home header text the file holds, the same way. */
        @Nullable
        String headerText;
        /** What the file says Sort loaded reels sorts by, or null when it names none. Set once, as it's read. */
        @Nullable
        ReelsOrder reelsOrder;
        /** What the file says Burn-in guard does, or null when it names none. Set once, as it's read. */
        @Nullable
        BurnInMode burnIn;
        /** The Dim after wait the file holds, the same way. */
        @Nullable
        BurnInWait burnInWait;
        /** Names the file holds that aren't settings this build knows. They're left out. */
        final int unknown;

        Snapshot(Map<BooleanSetting, Boolean> values, @Nullable String folder, @Nullable DownloadQuality quality,
                 @Nullable String fileName, @Nullable StartTab start, @Nullable CommentOrder order, int unknown) {
            this(values, folder, quality, fileName, start, order, null, null, unknown);
        }

        Snapshot(Map<BooleanSetting, Boolean> values, @Nullable String folder, @Nullable DownloadQuality quality,
                 @Nullable String fileName, @Nullable StartTab start, @Nullable CommentOrder order,
                 @Nullable String hidden, @Nullable String kept, int unknown) {
            this(values, folder, quality, fileName, start, order, hidden, kept, null, unknown);
        }

        Snapshot(Map<BooleanSetting, Boolean> values, @Nullable String folder, @Nullable DownloadQuality quality,
                 @Nullable String fileName, @Nullable StartTab start, @Nullable CommentOrder order,
                 @Nullable String hidden, @Nullable String kept, @Nullable PlaybackQuality playback, int unknown) {
            this(values, folder, quality, fileName, start, order, hidden, kept, playback, null, null, unknown);
        }

        Snapshot(Map<BooleanSetting, Boolean> values, @Nullable String folder, @Nullable DownloadQuality quality,
                 @Nullable String fileName, @Nullable StartTab start, @Nullable CommentOrder order,
                 @Nullable String hidden, @Nullable String kept, @Nullable PlaybackQuality playback,
                 @Nullable SendLink.Action action, @Nullable String app, int unknown) {
            this(values, folder, quality, fileName, start, order, hidden, kept, playback, action, app, null, unknown);
        }

        Snapshot(Map<BooleanSetting, Boolean> values, @Nullable String folder, @Nullable DownloadQuality quality,
                 @Nullable String fileName, @Nullable StartTab start, @Nullable CommentOrder order,
                 @Nullable String hidden, @Nullable String kept, @Nullable PlaybackQuality playback,
                 @Nullable SendLink.Action action, @Nullable String app, @Nullable SaveTo to, int unknown) {
            this(values, folder, quality, fileName, start, order, hidden, kept, playback, action, app, to, null,
                    unknown);
        }

        Snapshot(Map<BooleanSetting, Boolean> values, @Nullable String folder, @Nullable DownloadQuality quality,
                 @Nullable String fileName, @Nullable StartTab start, @Nullable CommentOrder order,
                 @Nullable String hidden, @Nullable String kept, @Nullable PlaybackQuality playback,
                 @Nullable SendLink.Action action, @Nullable String app, @Nullable SaveTo to,
                 @Nullable FeedsSubtab subtab, int unknown) {
            this(values, folder, quality, fileName, start, order, hidden, kept, playback, action, app, to, subtab,
                    null, unknown);
        }

        Snapshot(Map<BooleanSetting, Boolean> values, @Nullable String folder, @Nullable DownloadQuality quality,
                 @Nullable String fileName, @Nullable StartTab start, @Nullable CommentOrder order,
                 @Nullable String hidden, @Nullable String kept, @Nullable PlaybackQuality playback,
                 @Nullable SendLink.Action action, @Nullable String app, @Nullable SaveTo to,
                 @Nullable FeedsSubtab subtab, @Nullable String sources, int unknown) {
            this(values, folder, quality, fileName, start, order, hidden, kept, playback, action, app, to, subtab,
                    sources, null, unknown);
        }

        Snapshot(Map<BooleanSetting, Boolean> values, @Nullable String folder, @Nullable DownloadQuality quality,
                 @Nullable String fileName, @Nullable StartTab start, @Nullable CommentOrder order,
                 @Nullable String hidden, @Nullable String kept, @Nullable PlaybackQuality playback,
                 @Nullable SendLink.Action action, @Nullable String app, @Nullable SaveTo to,
                 @Nullable FeedsSubtab subtab, @Nullable String sources, @Nullable String photoName, int unknown) {
            this(values, folder, quality, fileName, start, order, hidden, kept, playback, action, app, to, subtab,
                    sources, photoName, null, null, null, null, unknown);
        }

        Snapshot(Map<BooleanSetting, Boolean> values, @Nullable String folder, @Nullable DownloadQuality quality,
                 @Nullable String fileName, @Nullable StartTab start, @Nullable CommentOrder order,
                 @Nullable String hidden, @Nullable String kept, @Nullable PlaybackQuality playback,
                 @Nullable SendLink.Action action, @Nullable String app, @Nullable SaveTo to,
                 @Nullable FeedsSubtab subtab, @Nullable String sources, @Nullable String photoName,
                 @Nullable String videoSubfolder, @Nullable String photoSubfolder,
                 @Nullable SurfaceQuality reelsQuality, @Nullable SurfaceQuality storiesQuality, int unknown) {
            this(values, folder, quality, fileName, start, order, hidden, kept, playback, action, app, to, subtab,
                    sources, photoName, videoSubfolder, photoSubfolder, reelsQuality, storiesQuality, null, null, null,
                    unknown);
        }

        Snapshot(Map<BooleanSetting, Boolean> values, @Nullable String folder, @Nullable DownloadQuality quality,
                 @Nullable String fileName, @Nullable StartTab start, @Nullable CommentOrder order,
                 @Nullable String hidden, @Nullable String kept, @Nullable PlaybackQuality playback,
                 @Nullable SendLink.Action action, @Nullable String app, @Nullable SaveTo to,
                 @Nullable FeedsSubtab subtab, @Nullable String sources, @Nullable String photoName,
                 @Nullable String videoSubfolder, @Nullable String photoSubfolder,
                 @Nullable SurfaceQuality reelsQuality, @Nullable SurfaceQuality storiesQuality,
                 @Nullable QuietHour quietFrom, @Nullable QuietHour quietUntil, @Nullable AppLock.After lockAfter,
                 int unknown) {
            this(values, folder, quality, fileName, start, order, hidden, kept, playback, action, app, to, subtab,
                    sources, photoName, videoSubfolder, photoSubfolder, reelsQuality, storiesQuality, quietFrom,
                    quietUntil, lockAfter, null, unknown);
        }

        Snapshot(Map<BooleanSetting, Boolean> values, @Nullable String folder, @Nullable DownloadQuality quality,
                 @Nullable String fileName, @Nullable StartTab start, @Nullable CommentOrder order,
                 @Nullable String hidden, @Nullable String kept, @Nullable PlaybackQuality playback,
                 @Nullable SendLink.Action action, @Nullable String app, @Nullable SaveTo to,
                 @Nullable FeedsSubtab subtab, @Nullable String sources, @Nullable String photoName,
                 @Nullable String videoSubfolder, @Nullable String photoSubfolder,
                 @Nullable SurfaceQuality reelsQuality, @Nullable SurfaceQuality storiesQuality,
                 @Nullable QuietHour quietFrom, @Nullable QuietHour quietUntil, @Nullable AppLock.After lockAfter,
                 @Nullable TextSize.Scale textSize, int unknown) {
            this(values, folder, quality, fileName, start, order, hidden, kept, playback, action, app, to, subtab,
                    sources, photoName, videoSubfolder, photoSubfolder, reelsQuality, storiesQuality, quietFrom,
                    quietUntil, lockAfter, textSize, null, unknown);
        }

        Snapshot(Map<BooleanSetting, Boolean> values, @Nullable String folder, @Nullable DownloadQuality quality,
                 @Nullable String fileName, @Nullable StartTab start, @Nullable CommentOrder order,
                 @Nullable String hidden, @Nullable String kept, @Nullable PlaybackQuality playback,
                 @Nullable SendLink.Action action, @Nullable String app, @Nullable SaveTo to,
                 @Nullable FeedsSubtab subtab, @Nullable String sources, @Nullable String photoName,
                 @Nullable String videoSubfolder, @Nullable String photoSubfolder,
                 @Nullable SurfaceQuality reelsQuality, @Nullable SurfaceQuality storiesQuality,
                 @Nullable QuietHour quietFrom, @Nullable QuietHour quietUntil, @Nullable AppLock.After lockAfter,
                 @Nullable TextSize.Scale textSize, @Nullable AccentColor.Preset accent, int unknown) {
            this(values, folder, quality, fileName, start, order, hidden, kept, playback, action, app, to, subtab,
                    sources, photoName, videoSubfolder, photoSubfolder, reelsQuality, storiesQuality, quietFrom,
                    quietUntil, lockAfter, textSize, accent, null, unknown);
        }

        Snapshot(Map<BooleanSetting, Boolean> values, @Nullable String folder, @Nullable DownloadQuality quality,
                 @Nullable String fileName, @Nullable StartTab start, @Nullable CommentOrder order,
                 @Nullable String hidden, @Nullable String kept, @Nullable PlaybackQuality playback,
                 @Nullable SendLink.Action action, @Nullable String app, @Nullable SaveTo to,
                 @Nullable FeedsSubtab subtab, @Nullable String sources, @Nullable String photoName,
                 @Nullable String videoSubfolder, @Nullable String photoSubfolder,
                 @Nullable SurfaceQuality reelsQuality, @Nullable SurfaceQuality storiesQuality,
                 @Nullable QuietHour quietFrom, @Nullable QuietHour quietUntil, @Nullable AppLock.After lockAfter,
                 @Nullable TextSize.Scale textSize, @Nullable AccentColor.Preset accent,
                 @Nullable ReactionCeiling ceiling, int unknown) {
            this.values = Collections.unmodifiableMap(values);
            this.folder = folder;
            this.quality = quality;
            this.fileName = fileName;
            this.start = start;
            this.order = order;
            this.hidden = hidden;
            this.kept = kept;
            this.playback = playback;
            this.action = action;
            this.app = app;
            this.to = to;
            this.subtab = subtab;
            this.sources = sources;
            this.photoName = photoName;
            this.videoSubfolder = videoSubfolder;
            this.photoSubfolder = photoSubfolder;
            this.reelsQuality = reelsQuality;
            this.storiesQuality = storiesQuality;
            this.quietFrom = quietFrom;
            this.quietUntil = quietUntil;
            this.lockAfter = lockAfter;
            this.textSize = textSize;
            this.accent = accent;
            this.ceiling = ceiling;
            this.unknown = unknown;
        }

        /**
         * The settings whose saved value this file changes, the switches first. Saved, not what a
         * paused Facebook is answered: a file holds what the person chose.
         */
        Map<Setting<?>, Object> changes() {
            Map<Setting<?>, Object> changes = new LinkedHashMap<>();
            for (Map.Entry<BooleanSetting, Boolean> entry : values.entrySet()) {
                if (!entry.getValue().equals(entry.getKey().savedValue())) {
                    changes.put(entry.getKey(), entry.getValue());
                }
            }
            SaveTo toChange = toChange();
            if (toChange != null) changes.put(TO, toChange);
            String folderChange = folderChange();
            if (folderChange != null) changes.put(FOLDER, folderChange);
            String videoSubfolderChange = videoSubfolderChange();
            if (videoSubfolderChange != null) changes.put(VIDEO_SUBFOLDER, videoSubfolderChange);
            String photoSubfolderChange = photoSubfolderChange();
            if (photoSubfolderChange != null) changes.put(PHOTO_SUBFOLDER, photoSubfolderChange);
            DownloadQuality qualityChange = qualityChange();
            if (qualityChange != null) changes.put(QUALITY, qualityChange);
            String fileNameChange = fileNameChange();
            if (fileNameChange != null) changes.put(FILE_NAME, fileNameChange);
            String photoNameChange = photoNameChange();
            if (photoNameChange != null) changes.put(PHOTO_NAME, photoNameChange);
            StartTab startChange = startChange();
            if (startChange != null) changes.put(START, startChange);
            FeedsSubtab subtabChange = subtabChange();
            if (subtabChange != null) changes.put(SUBTAB, subtabChange);
            CommentOrder orderChange = orderChange();
            if (orderChange != null) changes.put(ORDER, orderChange);
            String hiddenChange = hiddenChange();
            if (hiddenChange != null) changes.put(HIDDEN, hiddenChange);
            String keptChange = keptChange();
            if (keptChange != null) changes.put(KEPT, keptChange);
            String sourcesChange = sourcesChange();
            if (sourcesChange != null) changes.put(SOURCES, sourcesChange);
            PlaybackQuality playbackChange = playbackChange();
            if (playbackChange != null) changes.put(PLAYBACK, playbackChange);
            SurfaceQuality reelsQualityChange = reelsQualityChange();
            if (reelsQualityChange != null) changes.put(REELS_QUALITY, reelsQualityChange);
            SurfaceQuality storiesQualityChange = storiesQualityChange();
            if (storiesQualityChange != null) changes.put(STORIES_QUALITY, storiesQualityChange);
            QuietHour quietFromChange = quietFromChange();
            if (quietFromChange != null) changes.put(QUIET_FROM, quietFromChange);
            QuietHour quietUntilChange = quietUntilChange();
            if (quietUntilChange != null) changes.put(QUIET_UNTIL, quietUntilChange);
            AppLock.After lockAfterChange = lockAfterChange();
            if (lockAfterChange != null) changes.put(LOCK_AFTER, lockAfterChange);
            if (pauseLength != null && pauseLength != PAUSE_LENGTH.savedValue()) changes.put(PAUSE_LENGTH, pauseLength);
            if (breakAfter != null && breakAfter != BREAK_AFTER.savedValue()) changes.put(BREAK_AFTER, breakAfter);
            if (breakLength != null && breakLength != BREAK_LENGTH.savedValue()) changes.put(BREAK_LENGTH, breakLength);
            if (watchedKeep != null && watchedKeep != WATCHED_KEEP.savedValue()) changes.put(WATCHED_KEEP, watchedKeep);
            TextSize.Scale textSizeChange = textSizeChange();
            if (textSizeChange != null) changes.put(TEXT_SIZE, textSizeChange);
            AccentColor.Preset accentChange = accentChange();
            if (accentChange != null) changes.put(ACCENT, accentChange);
            ReactionCeiling ceilingChange = ceilingChange();
            if (ceilingChange != null) changes.put(CEILING, ceilingChange);
            String shareItemsChange = shareItemsChange();
            if (shareItemsChange != null) changes.put(SHARE_ITEMS, shareItemsChange);
            String menuTilesChange = menuTilesChange();
            if (menuTilesChange != null) changes.put(MENU_TILES, menuTilesChange);
            String feedTypesChange = feedTypesChange();
            if (feedTypesChange != null) changes.put(FEED_TYPES, feedTypesChange);
            if (seenKeep != null && seenKeep != SEEN_KEEP.savedValue()) changes.put(SEEN_KEEP, seenKeep);
            FeedsSubtab homeFeedChange = homeFeedChange();
            if (homeFeedChange != null) changes.put(HOME_FEED, homeFeedChange);
            String backgroundChange = backgroundChange();
            if (backgroundChange != null) changes.put(BACKGROUND, backgroundChange);
            String lightBackgroundChange = lightBackgroundChange();
            if (lightBackgroundChange != null) changes.put(LIGHT_BACKGROUND, lightBackgroundChange);
            String badgeChange = badgeChange();
            if (badgeChange != null) changes.put(BADGE, badgeChange);
            String logoChange = logoChange();
            if (logoChange != null) changes.put(LOGO, logoChange);
            String tabChange = tabChange();
            if (tabChange != null) changes.put(TAB, tabChange);
            String headerTextChange = headerTextChange();
            if (headerTextChange != null) changes.put(HEADER_TEXT, headerTextChange);
            ReelsOrder reelsOrderChange = reelsOrderChange();
            if (reelsOrderChange != null) changes.put(REELS_ORDER, reelsOrderChange);
            BurnInMode burnInChange = burnInChange();
            if (burnInChange != null) changes.put(BURN_IN, burnInChange);
            BurnInWait burnInWaitChange = burnInWaitChange();
            if (burnInWaitChange != null) changes.put(BURN_IN_WAIT, burnInWaitChange);
            SendLink.Action actionChange = actionChange();
            if (actionChange != null) changes.put(ACTION, actionChange);
            String appChange = appChange();
            if (appChange != null) changes.put(APP, appChange);
            return changes;
        }

        private boolean wordListsFit() {
            return (hidden == null && kept == null) || PostWords.fits(
                    hidden != null ? hidden : PostWords.clean(HIDDEN.savedValue()),
                    kept != null ? kept : PostWords.clean(KEPT.savedValue()));
        }

        /** How many switches this file changes, the number the preview and the toast give. */
        int switchChanges() {
            int count = 0;
            for (Map.Entry<BooleanSetting, Boolean> entry : values.entrySet()) {
                if (!entry.getValue().equals(entry.getKey().savedValue())) count++;
            }
            return count;
        }

        /**
         * The folder name this file moves the saves to, or null when it names none or the one the
         * saves already use.
         */
        @Nullable
        String folderChange() {
            if (folder == null) return null;
            return folder.equals(SaveFolder.sanitize(FOLDER.savedValue())) ? null : folder;
        }

        /**
         * The subfolder this file moves video saves to, blank for the save folder itself, or null
         * when it names none or the one video saves already use.
         */
        @Nullable
        String videoSubfolderChange() {
            return subfolderChange(videoSubfolder, VIDEO_SUBFOLDER);
        }

        /** The same as {@link #videoSubfolderChange}, for photo saves. */
        @Nullable
        String photoSubfolderChange() {
            return subfolderChange(photoSubfolder, PHOTO_SUBFOLDER);
        }

        @Nullable
        private static String subfolderChange(@Nullable String subfolder, StringSetting setting) {
            if (subfolder == null) return null;
            return subfolder.equals(SaveFolder.cleanSubfolder(setting.savedValue())) ? null : subfolder;
        }

        /** The quality this file sets, or null when it names none or the one saves already use. */
        @Nullable
        DownloadQuality qualityChange() {
            return quality == null || quality == QUALITY.savedValue() ? null : quality;
        }

        /** The template this file sets, or null when it names none or the one saves already use. */
        @Nullable
        String fileNameChange() {
            if (fileName == null) return null;
            return fileName.equals(FileNameTemplate.sanitize(FILE_NAME.savedValue())) ? null : fileName;
        }

        /** The photo template this file sets, or null when it names none or the one photo saves already use. */
        @Nullable
        String photoNameChange() {
            if (photoName == null) return null;
            return photoName.equals(FileNameTemplate.sanitizePhoto(PHOTO_NAME.savedValue())) ? null : photoName;
        }

        /** The start tab this file sets, or null when it names none or the one already set. */
        @Nullable
        StartTab startChange() {
            return start == null || start == START.savedValue() ? null : start;
        }

        /** The Feeds filter this file sets, or null when it names none or the one already set. */
        @Nullable
        FeedsSubtab subtabChange() {
            return subtab == null || subtab == SUBTAB.savedValue() ? null : subtab;
        }

        /** The comment order this file sets, or null when it names none or the one already set. */
        @Nullable
        CommentOrder orderChange() {
            return order == null || order == ORDER.savedValue() ? null : order;
        }

        /** The playback quality this file sets, or null when it names none or the one already set. */
        @Nullable
        PlaybackQuality playbackChange() {
            return playback == null || playback == PLAYBACK.savedValue() ? null : playback;
        }

        /** The reels quality this file sets, or null when it names none or the one already set. */
        @Nullable
        SurfaceQuality reelsQualityChange() {
            return reelsQuality == null || reelsQuality == REELS_QUALITY.savedValue() ? null : reelsQuality;
        }

        /** The video stories quality this file sets, or null when it names none or the one already set. */
        @Nullable
        SurfaceQuality storiesQualityChange() {
            return storiesQuality == null || storiesQuality == STORIES_QUALITY.savedValue() ? null : storiesQuality;
        }

        /** The hour quiet hours start that this file sets, or null when it names none or the one already set. */
        @Nullable
        QuietHour quietFromChange() {
            return quietFrom == null || quietFrom == QUIET_FROM.savedValue() ? null : quietFrom;
        }

        /** The hour quiet hours end that this file sets, or null when it names none or the one already set. */
        @Nullable
        QuietHour quietUntilChange() {
            return quietUntil == null || quietUntil == QUIET_UNTIL.savedValue() ? null : quietUntil;
        }

        /** How long the app lock waits after this file, or null when it names none or the one already set. */
        @Nullable
        AppLock.After lockAfterChange() {
            return lockAfter == null || lockAfter == LOCK_AFTER.savedValue() ? null : lockAfter;
        }

        /** The text size after this file, or null when it names none or the one already set. */
        @Nullable
        TextSize.Scale textSizeChange() {
            return textSize == null || textSize == TEXT_SIZE.savedValue() ? null : textSize;
        }

        /** The accent after this file, or null when it names none or the one already set. */
        @Nullable
        AccentColor.Preset accentChange() {
            return accent == null || accent == ACCENT.savedValue() ? null : accent;
        }

        /** The reaction ceiling after this file, or null when it names none or the one already set. */
        @Nullable
        ReactionCeiling ceilingChange() {
            return ceiling == null || ceiling == CEILING.savedValue() ? null : ceiling;
        }

        /** The top folder this file sends saves to, or null when it names none or the one already set. */
        @Nullable
        SaveTo toChange() {
            return to == null || to == TO.savedValue() ? null : to;
        }

        /** The download action this file sets, or null when it names none or the one already set. */
        @Nullable
        SendLink.Action actionChange() {
            return action == null || action == ACTION.savedValue() ? null : action;
        }

        /**
         * The app this file sends links to, blank for Android's chooser, or null when it names none
         * or the one links already go to.
         */
        @Nullable
        String appChange() {
            if (app == null) return null;
            return app.equals(SendLink.fileApp(APP.savedValue())) ? null : app;
        }

        /** The list of words to hide this file sets, or null when it names none or the one already set. */
        @Nullable
        String hiddenChange() {
            return listChange(hidden, HIDDEN);
        }

        /** The list of words that keep a post this file sets, or null when it names none or the one already set. */
        @Nullable
        String keptChange() {
            return listChange(kept, KEPT);
        }

        /** The people, Pages and sites list this file sets, or null when it names none or the one already set. */
        @Nullable
        String sourcesChange() {
            if (sources == null) return null;
            return sources.equals(PostSources.clean(SOURCES.savedValue())) ? null : sources;
        }

        /** The share sheet items to hide this file sets, or null when it names none or the ones already set. */
        @Nullable
        String shareItemsChange() {
            if (shareItems == null) return null;
            return shareItems.equals(ShareSheetItems.clean(SHARE_ITEMS.savedValue())) ? null : shareItems;
        }

        /** The Menu tiles to hide this file sets, or null when it names none or the ones already set. */
        @Nullable
        String menuTilesChange() {
            if (menuTiles == null) return null;
            return menuTiles.equals(MenuTiles.clean(MENU_TILES.savedValue())) ? null : menuTiles;
        }

        /** The feed types to hide this file sets, or null when it names none or the ones already set. */
        @Nullable
        String feedTypesChange() {
            if (feedTypes == null) return null;
            return feedTypes.equals(FeedTypeNames.clean(FEED_TYPES.savedValue())) ? null : feedTypes;
        }

        /** The Background color this file sets, or null when it names none or the one already set. */
        @Nullable
        String backgroundChange() {
            if (background == null) return null;
            return background.equals(BACKGROUND.savedValue()) ? null : background;
        }

        /** The Light background color this file sets, or null when it names none or the one already set. */
        @Nullable
        String lightBackgroundChange() {
            if (lightBackground == null) return null;
            return lightBackground.equals(LIGHT_BACKGROUND.savedValue()) ? null : lightBackground;
        }

        /** The Badge color this file sets, or null when it names none or the one already set. */
        @Nullable
        String badgeChange() {
            if (badge == null) return null;
            return badge.equals(BADGE.savedValue()) ? null : badge;
        }

        /** The Logo color this file sets, or null when it names none or the one already set. */
        @Nullable
        String logoChange() {
            if (logo == null) return null;
            return logo.equals(LOGO.savedValue()) ? null : logo;
        }

        /** The Selected tab color this file sets, or null when it names none or the one already set. */
        @Nullable
        String tabChange() {
            if (tab == null) return null;
            return tab.equals(TAB.savedValue()) ? null : tab;
        }

        /** The Home header text this file sets, or null when it names none or the one already set. */
        @Nullable
        String headerTextChange() {
            if (headerText == null) return null;
            return headerText.equals(HEADER_TEXT.savedValue()) ? null : headerText;
        }

        /** The feed Home shows after this file, or null when it names none or the one already set. */
        @Nullable
        FeedsSubtab homeFeedChange() {
            return homeFeed == null || homeFeed == HOME_FEED.savedValue() ? null : homeFeed;
        }

        /** What Sort loaded reels sorts by after this file, or null when it names none or the one already set. */
        @Nullable
        ReelsOrder reelsOrderChange() {
            return reelsOrder == null || reelsOrder == REELS_ORDER.savedValue() ? null : reelsOrder;
        }

        /** What Burn-in guard does after this file, or null when it names none or the one already set. */
        @Nullable
        BurnInMode burnInChange() {
            return burnIn == null || burnIn == BURN_IN.savedValue() ? null : burnIn;
        }

        /** The Dim after wait after this file, the same way. */
        @Nullable
        BurnInWait burnInWaitChange() {
            return burnInWait == null || burnInWait == BURN_IN_WAIT.savedValue() ? null : burnInWait;
        }

        @Nullable
        private static String listChange(@Nullable String list, StringSetting setting) {
            if (list == null) return null;
            return list.equals(PostWords.clean(setting.savedValue())) ? null : list;
        }

        /** For the settings page's saved state, so a preview outlives the page being rebuilt. */
        Bundle toBundle() {
            Bundle switches = new Bundle();
            for (Map.Entry<BooleanSetting, Boolean> entry : values.entrySet()) {
                switches.putBoolean(entry.getKey().key, entry.getValue());
            }
            Bundle state = new Bundle();
            state.putBundle(SWITCHES, switches);
            if (folder != null) state.putString(FOLDER_NAME, folder);
            if (quality != null) state.putString(QUALITY_NAME, quality.fileValue);
            if (fileName != null) state.putString(FILE_NAME_NAME, fileName);
            if (photoName != null) state.putString(PHOTO_NAME_NAME, photoName);
            if (start != null) state.putString(START_NAME, start.fileValue);
            if (order != null) state.putString(ORDER_NAME, order.fileValue);
            if (hidden != null) state.putString(HIDDEN_NAME, hidden);
            if (kept != null) state.putString(KEPT_NAME, kept);
            if (sources != null) state.putString(SOURCES_NAME, sources);
            if (playback != null) state.putString(PLAYBACK_NAME, playback.fileValue);
            if (action != null) state.putString(ACTION_NAME, action.fileValue);
            if (app != null) state.putString(APP_NAME, app);
            if (to != null) state.putString(TO_NAME, to.fileValue);
            if (subtab != null) state.putString(SUBTAB_NAME, subtab.fileValue);
            if (videoSubfolder != null) state.putString(VIDEO_SUBFOLDER_NAME, videoSubfolder);
            if (photoSubfolder != null) state.putString(PHOTO_SUBFOLDER_NAME, photoSubfolder);
            if (reelsQuality != null) state.putString(REELS_QUALITY_NAME, reelsQuality.fileValue);
            if (storiesQuality != null) state.putString(STORIES_QUALITY_NAME, storiesQuality.fileValue);
            if (quietFrom != null) state.putString(QUIET_FROM_NAME, quietFrom.fileValue());
            if (quietUntil != null) state.putString(QUIET_UNTIL_NAME, quietUntil.fileValue());
            if (lockAfter != null) state.putString(LOCK_AFTER_NAME, lockAfter.fileValue);
            if (textSize != null) state.putString(TEXT_SIZE_NAME, textSize.fileValue);
            if (accent != null) state.putString(ACCENT_NAME, accent.fileValue);
            if (ceiling != null) state.putString(CEILING_NAME, ceiling.fileValue);
            if (seenKeep != null) state.putString(SEEN_KEEP_NAME, seenKeep.fileValue);
            if (pauseLength != null) state.putString(PAUSE_LENGTH_NAME, pauseLength.fileValue);
            if (breakAfter != null) state.putString(BREAK_AFTER_NAME, breakAfter.fileValue);
            if (breakLength != null) state.putString(BREAK_LENGTH_NAME, breakLength.fileValue);
            if (watchedKeep != null) state.putString(WATCHED_KEEP_NAME, watchedKeep.fileValue);
            if (shareItems != null) state.putString(SHARE_ITEMS_NAME, shareItems);
            if (menuTiles != null) state.putString(MENU_TILES_NAME, menuTiles);
            if (feedTypes != null) state.putString(FEED_TYPES_NAME, feedTypes);
            if (homeFeed != null) state.putString(HOME_FEED_NAME, homeFeed.fileValue);
            if (background != null) state.putString(BACKGROUND_NAME, background);
            if (lightBackground != null) state.putString(LIGHT_BACKGROUND_NAME, lightBackground);
            if (badge != null) state.putString(BADGE_NAME, badge);
            if (logo != null) state.putString(LOGO_NAME, logo);
            if (tab != null) state.putString(TAB_NAME, tab);
            if (headerText != null) state.putString(HEADER_TEXT_NAME, headerText);
            if (reelsOrder != null) state.putString(REELS_ORDER_NAME, reelsOrder.fileValue);
            if (burnIn != null) state.putString(BURN_IN_NAME, burnIn.fileValue);
            if (burnInWait != null) state.putString(BURN_IN_WAIT_NAME, burnInWait.fileValue);
            state.putInt(UNKNOWN, unknown);
            return state;
        }

        /**
         * The snapshot a page saved, or null when there's none or it isn't one. Only switches on
         * the list come back, whatever the bundle holds.
         */
        @Nullable
        @SuppressWarnings("deprecation") // Bundle.get is how a value's type is checked below API 33.
        static Snapshot fromBundle(@Nullable Bundle state) {
            if (state == null) return null;
            Bundle switches = state.getBundle(SWITCHES);
            int unknown = state.getInt(UNKNOWN, -1);
            if (switches == null || unknown < 0) return null;
            Map<BooleanSetting, Boolean> values = new LinkedHashMap<>();
            for (BooleanSetting setting : ALLOWLIST) {
                Object value = switches.get(setting.key);
                if (value instanceof Boolean) values.put(setting, (Boolean) value);
            }
            Object folder = state.get(FOLDER_NAME);
            Object fileName = state.get(FILE_NAME_NAME);
            Object hidden = state.get(HIDDEN_NAME);
            Object kept = state.get(KEPT_NAME);
            Object sources = state.get(SOURCES_NAME);
            Object app = state.get(APP_NAME);
            Object photoName = state.get(PHOTO_NAME_NAME);
            Object videoSubfolder = state.get(VIDEO_SUBFOLDER_NAME);
            Object photoSubfolder = state.get(PHOTO_SUBFOLDER_NAME);
            Snapshot read = new Snapshot(values, folder instanceof String && SaveFolder.isClean((String) folder)
                    ? (String) folder : null, DownloadQuality.fromFile(state.get(QUALITY_NAME)),
                    fileName instanceof String && FileNameTemplate.isClean((String) fileName) ? (String) fileName : null,
                    StartTab.fromFile(state.get(START_NAME)), CommentOrder.fromFile(state.get(ORDER_NAME)),
                    hidden instanceof String && PostWords.isClean((String) hidden) ? (String) hidden : null,
                    kept instanceof String && PostWords.isClean((String) kept) ? (String) kept : null,
                    PlaybackQuality.fromFile(state.get(PLAYBACK_NAME)), SendLink.Action.fromFile(state.get(ACTION_NAME)),
                    SendLink.isFileApp(app) ? (String) app : null, SaveTo.fromFile(state.get(TO_NAME)),
                    FeedsSubtab.fromFile(state.get(SUBTAB_NAME)),
                    sources instanceof String && PostSources.isClean((String) sources) ? (String) sources : null,
                    photoName instanceof String && FileNameTemplate.isCleanPhoto((String) photoName) ? (String) photoName : null,
                    videoSubfolder instanceof String && SaveFolder.isCleanSubfolder((String) videoSubfolder)
                            ? (String) videoSubfolder : null,
                    photoSubfolder instanceof String && SaveFolder.isCleanSubfolder((String) photoSubfolder)
                            ? (String) photoSubfolder : null,
                    SurfaceQuality.fromFile(state.get(REELS_QUALITY_NAME)),
                    SurfaceQuality.fromFile(state.get(STORIES_QUALITY_NAME)),
                    QuietHour.fromFile(state.get(QUIET_FROM_NAME)), QuietHour.fromFile(state.get(QUIET_UNTIL_NAME)),
                    AppLock.After.fromFile(state.get(LOCK_AFTER_NAME)),
                    TextSize.Scale.fromFile(state.get(TEXT_SIZE_NAME)),
                    AccentColor.Preset.fromFile(state.get(ACCENT_NAME)),
                    ReactionCeiling.fromFile(state.get(CEILING_NAME)),
                    unknown);
            read.seenKeep = SeenPosts.Keep.fromFile(state.get(SEEN_KEEP_NAME));
            read.pauseLength = OpeningPause.Length.fromFile(state.get(PAUSE_LENGTH_NAME));
            read.breakAfter = BreakTimer.Use.fromFile(state.get(BREAK_AFTER_NAME));
            read.breakLength = BreakTimer.Length.fromFile(state.get(BREAK_LENGTH_NAME));
            read.watchedKeep = RecentlyWatched.Keep.fromFile(state.get(WATCHED_KEEP_NAME));
            Object shareItems = state.get(SHARE_ITEMS_NAME);
            read.shareItems = shareItems instanceof String && ShareSheetItems.isClean((String) shareItems)
                    ? (String) shareItems : null;
            Object menuTiles = state.get(MENU_TILES_NAME);
            read.menuTiles = menuTiles instanceof String && MenuTiles.isClean((String) menuTiles)
                    ? (String) menuTiles : null;
            Object feedTypes = state.get(FEED_TYPES_NAME);
            read.feedTypes = feedTypes instanceof String && FeedTypeNames.isClean((String) feedTypes)
                    ? (String) feedTypes : null;
            read.homeFeed = FeedsSubtab.fromFile(state.get(HOME_FEED_NAME));
            read.reelsOrder = ReelsOrder.fromFile(state.get(REELS_ORDER_NAME));
            read.burnIn = BurnInMode.fromFile(state.get(BURN_IN_NAME));
            read.burnInWait = BurnInWait.fromFile(state.get(BURN_IN_WAIT_NAME));
            Object background = state.get(BACKGROUND_NAME);
            read.background = isBackground(background) ? (String) background : null;
            Object lightBackground = state.get(LIGHT_BACKGROUND_NAME);
            read.lightBackground = isLightBackground(lightBackground) ? (String) lightBackground : null;
            Object badge = state.get(BADGE_NAME);
            read.badge = isMarkColor(badge) ? (String) badge : null;
            Object logo = state.get(LOGO_NAME);
            read.logo = isMarkColor(logo) ? (String) logo : null;
            Object tab = state.get(TAB_NAME);
            read.tab = isMarkColor(tab) ? (String) tab : null;
            Object headerText = state.get(HEADER_TEXT_NAME);
            read.headerText = HeaderText.isClean(headerText instanceof String ? (String) headerText : null)
                    ? (String) headerText : null;
            if (read.hidden != null && read.kept != null && !read.wordListsFit()) return null;
            return read;
        }
    }

    /**
     * Every value a file carries, as saved at the moment this was made. Saved rather than what a
     * paused Facebook is answered, so exporting while paused keeps what the person chose.
     *
     * <p>Making one is a single pass on the calling thread, and the settings screen changes what's
     * saved on the main thread: a Quick setup flips its switches one after another in one step
     * there, and the two word lists are saved there one at a time. So an export makes its own on
     * the main thread, where none of that can be halfway, and the file holds what was saved when
     * the person asked for it. Read on a worker instead, as exports once were, the values could
     * straddle such a change and make a file nobody chose. They never change afterwards, which
     * lets a worker write the file from them whatever is edited meanwhile.
     */
    static final class Saved {
        private final Map<Setting<?>, Object> values = new HashMap<>();

        private Saved() {
            for (BooleanSetting setting : ALLOWLIST) values.put(setting, setting.savedValue());
            for (Setting<?> setting : VALUES) values.put(setting, setting.savedValue());
        }

        @SuppressWarnings("unchecked")
        private <T> T of(Setting<T> setting) {
            return (T) values.get(setting);
        }

        /** The file for these values. Reads no setting, so it can run on a worker. */
        String file() throws JSONException {
            JSONObject switches = new JSONObject();
            for (BooleanSetting setting : ALLOWLIST) {
                switches.put(setting.key, of(setting).booleanValue());
            }
            switches.put(TO.key, of(TO).fileValue);
            // The name the saves use, so a file never carries one an import would refuse.
            switches.put(FOLDER.key, SaveFolder.sanitize(of(FOLDER)));
            switches.put(VIDEO_SUBFOLDER.key, SaveFolder.cleanSubfolder(of(VIDEO_SUBFOLDER)));
            switches.put(PHOTO_SUBFOLDER.key, SaveFolder.cleanSubfolder(of(PHOTO_SUBFOLDER)));
            switches.put(QUALITY.key, of(QUALITY).fileValue);
            switches.put(FILE_NAME.key, FileNameTemplate.sanitize(of(FILE_NAME)));
            switches.put(PHOTO_NAME.key, FileNameTemplate.sanitizePhoto(of(PHOTO_NAME)));
            switches.put(START.key, of(START).fileValue);
            switches.put(SUBTAB.key, of(SUBTAB).fileValue);
            switches.put(HOME_FEED.key, of(HOME_FEED).fileValue);
            switches.put(ORDER.key, of(ORDER).fileValue);
            switches.put(PLAYBACK.key, of(PLAYBACK).fileValue);
            switches.put(REELS_QUALITY.key, of(REELS_QUALITY).fileValue);
            switches.put(STORIES_QUALITY.key, of(STORIES_QUALITY).fileValue);
            switches.put(REELS_ORDER.key, of(REELS_ORDER).fileValue);
            switches.put(BURN_IN.key, of(BURN_IN).fileValue);
            switches.put(BURN_IN_WAIT.key, of(BURN_IN_WAIT).fileValue);
            switches.put(QUIET_FROM.key, of(QUIET_FROM).fileValue());
            switches.put(QUIET_UNTIL.key, of(QUIET_UNTIL).fileValue());
            switches.put(LOCK_AFTER.key, of(LOCK_AFTER).fileValue);
            switches.put(PAUSE_LENGTH.key, of(PAUSE_LENGTH).fileValue);
            switches.put(BREAK_AFTER.key, of(BREAK_AFTER).fileValue);
            switches.put(BREAK_LENGTH.key, of(BREAK_LENGTH).fileValue);
            switches.put(WATCHED_KEEP.key, of(WATCHED_KEEP).fileValue);
            switches.put(SEEN_KEEP.key, of(SEEN_KEEP).fileValue);
            switches.put(TEXT_SIZE.key, of(TEXT_SIZE).fileValue);
            switches.put(ACCENT.key, of(ACCENT).fileValue);
            switches.put(CEILING.key, of(CEILING).fileValue);
            switches.put(ACTION.key, of(ACTION).fileValue);
            // The app links really go to, so a file never carries a name an import would refuse.
            switches.put(APP.key, SendLink.fileApp(of(APP)));
            // The lists the filter reads, so a file never carries one an import would refuse.
            switches.put(HIDDEN.key, PostWords.clean(of(HIDDEN)));
            switches.put(KEPT.key, PostWords.clean(of(KEPT)));
            switches.put(SOURCES.key, PostSources.clean(of(SOURCES)));
            switches.put(SHARE_ITEMS.key, ShareSheetItems.clean(of(SHARE_ITEMS)));
            switches.put(MENU_TILES.key, MenuTiles.clean(of(MENU_TILES)));
            switches.put(FEED_TYPES.key, FeedTypeNames.clean(of(FEED_TYPES)));
            // The colour as the row keeps it, so a file never carries one an import would refuse.
            String background = AmoledBackground.stored(of(BACKGROUND));
            switches.put(BACKGROUND.key, background == null ? "" : background);
            String lightBackground = AmoledLightBackground.stored(of(LIGHT_BACKGROUND));
            switches.put(LIGHT_BACKGROUND.key, lightBackground == null ? "" : lightBackground);
            String badge = MarkColors.stored(of(BADGE));
            switches.put(BADGE.key, badge == null ? "" : badge);
            String logo = MarkColors.stored(of(LOGO));
            switches.put(LOGO.key, logo == null ? "" : logo);
            String tab = MarkColors.stored(of(TAB));
            switches.put(TAB.key, tab == null ? "" : tab);
            switches.put(HEADER_TEXT.key, HeaderText.clean(of(HEADER_TEXT)));
            return new JSONObject()
                    .put(FORMAT_NAME, FORMAT)
                    .put(SCHEMA_NAME, SCHEMA)
                    .put(SETTINGS_NAME, switches)
                    .toString(2);
        }
    }

    /** What's saved right now, read in one pass on this thread. An export asks on the main thread. */
    static Saved savedNow() {
        return new Saved();
    }

    /** The file for the switches as they're saved now, read and written out on this thread. */
    public static String create() throws JSONException {
        return savedNow().file();
    }

    /**
     * A file as text, read no further than {@link #MAX_BYTES}, so a huge file or a stream that
     * never ends is refused without being held in memory.
     */
    static String read(@Nullable InputStream input) throws Rejected {
        if (input == null) throw new Rejected(Reason.UNREADABLE, "No stream to read");
        byte[] bytes;
        try (InputStream stream = input; ByteArrayOutputStream buffered = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (buffered.size() + count > MAX_BYTES) {
                    throw new Rejected(Reason.SIZE, "Larger than " + MAX_BYTES + " bytes");
                }
                buffered.write(buffer, 0, count);
            }
            bytes = buffered.toByteArray();
        } catch (IOException | RuntimeException error) {
            // A provider's stream can fail with a runtime exception as readily as an IOException.
            // The class only: a provider's message can carry the document's name or address.
            throw new Rejected(Reason.UNREADABLE, error.getClass().getSimpleName());
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw new Rejected(Reason.ENCODING, "Not UTF-8");
        }
    }

    /** What a file's text says, or why it's refused. Nothing is written here. */
    static Snapshot parse(@Nullable String text) throws Rejected {
        if (text == null) throw new Rejected(Reason.UNREADABLE, "No text");
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new Rejected(Reason.SIZE, "Larger than " + MAX_BYTES + " bytes");
        }
        if (text.startsWith(BYTE_ORDER_MARK)) text = text.substring(1);
        JSONObject root;
        try {
            root = SettingsJson.parseObject(text, LIMITS);
        } catch (SettingsJson.DuplicateNameException duplicate) {
            throw new Rejected(Reason.DUPLICATE, "A name appears twice");
        } catch (IOException | JSONException error) {
            // The parser reports a document that simply stops as an IOException, not a
            // JSONException, and a download cut off halfway is exactly that.
            throw new Rejected(Reason.DAMAGED, "Not one JSON object");
        }
        if (!FORMAT.equals(root.opt(FORMAT_NAME))) {
            throw new Rejected(Reason.FORMAT, "Not a Hushfacebook settings file");
        }
        // A version is a whole number this build wrote or a later one. A later one is refused
        // rather than read field by field, which could land half of a file this build doesn't
        // understand. Anything else in its place was never written by Hushfacebook.
        Object schema = root.opt(SCHEMA_NAME);
        if (!(schema instanceof Integer) || (Integer) schema < 1) {
            throw new Rejected(Reason.FORMAT, "No version number");
        }
        if ((Integer) schema > SCHEMA) {
            throw new Rejected(Reason.SCHEMA, "Version " + schema + " is newer than " + SCHEMA);
        }
        Object settings = root.opt(SETTINGS_NAME);
        if (!(settings instanceof JSONObject)) {
            throw new Rejected(Reason.FORMAT, "No settings");
        }

        int unknown = 0;
        for (Iterator<String> names = root.keys(); names.hasNext(); ) {
            String name = names.next();
            if (!FORMAT_NAME.equals(name) && !SCHEMA_NAME.equals(name) && !SETTINGS_NAME.equals(name)) {
                unknown++;
            }
        }
        Map<String, BooleanSetting> known = new HashMap<>();
        for (BooleanSetting setting : ALLOWLIST) known.put(setting.key, setting);
        Map<BooleanSetting, Boolean> found = new HashMap<>();
        Boolean legacyStories = null;
        String folder = null;
        DownloadQuality quality = null;
        String fileName = null;
        String photoName = null;
        StartTab start = null;
        CommentOrder order = null;
        String hidden = null;
        String kept = null;
        String sources = null;
        PlaybackQuality playback = null;
        SendLink.Action action = null;
        String app = null;
        SaveTo to = null;
        FeedsSubtab subtab = null;
        String videoSubfolder = null;
        String photoSubfolder = null;
        SurfaceQuality reelsQuality = null;
        SurfaceQuality storiesQuality = null;
        QuietHour quietFrom = null;
        QuietHour quietUntil = null;
        AppLock.After lockAfter = null;
        SeenPosts.Keep seenKeep = null;
        OpeningPause.Length pauseLength = null;
        BreakTimer.Use breakAfter = null;
        BreakTimer.Length breakLength = null;
        RecentlyWatched.Keep watchedKeep = null;
        TextSize.Scale textSize = null;
        AccentColor.Preset accent = null;
        ReactionCeiling ceiling = null;
        String shareItems = null;
        String menuTiles = null;
        String feedTypes = null;
        FeedsSubtab homeFeed = null;
        String background = null;
        String lightBackground = null;
        String badge = null;
        String logo = null;
        String tab = null;
        String headerText = null;
        ReelsOrder reelsOrder = null;
        BurnInMode burnIn = null;
        BurnInWait burnInWait = null;
        JSONObject values = (JSONObject) settings;
        for (Iterator<String> names = values.keys(); names.hasNext(); ) {
            String name = names.next();
            if (StoriesSetting.LEGACY_KEY.equals(name)) {
                Object value = values.opt(name);
                if (!(value instanceof Boolean)) {
                    throw new Rejected(Reason.VALUE, "Not true or false: " + name);
                }
                legacyStories = (Boolean) value;
                continue;
            }
            if (FOLDER.key.equals(name)) {
                Object value = values.opt(name);
                if (!(value instanceof String) || !SaveFolder.isImportable((String) value)) {
                    throw new Rejected(Reason.VALUE, "Not one clean folder name: " + name);
                }
                // A newer phone's name can hold characters this one doesn't know yet, which the
                // saves here drop, so the folder taken is the one they'll really use.
                folder = SaveFolder.sanitize((String) value);
                continue;
            }
            if (VIDEO_SUBFOLDER.key.equals(name) || PHOTO_SUBFOLDER.key.equals(name)) {
                Object value = values.opt(name);
                // Blank is no subfolder. Anything else is one name the row would keep as typed,
                // never a path.
                if (!(value instanceof String) || !SaveFolder.isImportableSubfolder((String) value)) {
                    throw new Rejected(Reason.VALUE, "Not one clean folder name or blank: " + name);
                }
                String subfolder = SaveFolder.cleanSubfolder((String) value);
                if (VIDEO_SUBFOLDER.key.equals(name)) videoSubfolder = subfolder;
                else photoSubfolder = subfolder;
                continue;
            }
            if (QUALITY.key.equals(name)) {
                quality = DownloadQuality.fromFile(values.opt(name));
                if (quality == null) throw new Rejected(Reason.VALUE, "Not a save quality: " + name);
                continue;
            }
            if (FILE_NAME.key.equals(name)) {
                Object value = values.opt(name);
                if (!(value instanceof String) || !FileNameTemplate.isImportable((String) value)) {
                    throw new Rejected(Reason.VALUE, "Not one clean file name: " + name);
                }
                fileName = FileNameTemplate.sanitize((String) value);
                continue;
            }
            if (PHOTO_NAME.key.equals(name)) {
                Object value = values.opt(name);
                if (!(value instanceof String) || !FileNameTemplate.isImportablePhoto((String) value)) {
                    throw new Rejected(Reason.VALUE, "Not one clean photo file name: " + name);
                }
                photoName = FileNameTemplate.sanitizePhoto((String) value);
                continue;
            }
            if (START.key.equals(name)) {
                start = StartTab.fromFile(values.opt(name));
                if (start == null) throw new Rejected(Reason.VALUE, "Not a start tab: " + name);
                continue;
            }
            if (SUBTAB.key.equals(name)) {
                subtab = FeedsSubtab.fromFile(values.opt(name));
                if (subtab == null) throw new Rejected(Reason.VALUE, "Not a Feeds filter: " + name);
                continue;
            }
            if (HOME_FEED.key.equals(name)) {
                homeFeed = FeedsSubtab.fromFile(values.opt(name));
                if (homeFeed == null) throw new Rejected(Reason.VALUE, "Not a Home feed: " + name);
                continue;
            }
            if (BACKGROUND.key.equals(name)) {
                Object value = values.opt(name);
                if (!isBackground(value)) throw new Rejected(Reason.VALUE, "Not a Background color: " + name);
                background = (String) value;
                continue;
            }
            if (LIGHT_BACKGROUND.key.equals(name)) {
                Object value = values.opt(name);
                if (!isLightBackground(value)) throw new Rejected(Reason.VALUE, "Not a Light background color: " + name);
                lightBackground = (String) value;
                continue;
            }
            if (BADGE.key.equals(name) || LOGO.key.equals(name) || TAB.key.equals(name)) {
                Object value = values.opt(name);
                if (!isMarkColor(value)) throw new Rejected(Reason.VALUE, "Not a color code: " + name);
                if (BADGE.key.equals(name)) badge = (String) value;
                else if (LOGO.key.equals(name)) logo = (String) value;
                else tab = (String) value;
                continue;
            }
            if (HEADER_TEXT.key.equals(name)) {
                Object value = values.opt(name);
                if (!(value instanceof String) || !HeaderText.isClean((String) value)) {
                    throw new Rejected(Reason.VALUE, "Not a Home header text: " + name);
                }
                headerText = (String) value;
                continue;
            }
            if (ORDER.key.equals(name)) {
                order = CommentOrder.fromFile(values.opt(name));
                if (order == null) throw new Rejected(Reason.VALUE, "Not a comment order: " + name);
                continue;
            }
            if (PLAYBACK.key.equals(name)) {
                playback = PlaybackQuality.fromFile(values.opt(name));
                if (playback == null) throw new Rejected(Reason.VALUE, "Not a playback quality: " + name);
                continue;
            }
            if (REELS_QUALITY.key.equals(name) || STORIES_QUALITY.key.equals(name)) {
                SurfaceQuality choice = SurfaceQuality.fromFile(values.opt(name));
                if (choice == null) throw new Rejected(Reason.VALUE, "Not a playback quality: " + name);
                if (REELS_QUALITY.key.equals(name)) reelsQuality = choice;
                else storiesQuality = choice;
                continue;
            }
            if (REELS_ORDER.key.equals(name)) {
                reelsOrder = ReelsOrder.fromFile(values.opt(name));
                if (reelsOrder == null) throw new Rejected(Reason.VALUE, "Not a reels order: " + name);
                continue;
            }
            if (BURN_IN.key.equals(name)) {
                burnIn = BurnInMode.fromFile(values.opt(name));
                if (burnIn == null) throw new Rejected(Reason.VALUE, "Not a burn-in guard choice: " + name);
                continue;
            }
            if (BURN_IN_WAIT.key.equals(name)) {
                burnInWait = BurnInWait.fromFile(values.opt(name));
                if (burnInWait == null) throw new Rejected(Reason.VALUE, "Not a dim after wait: " + name);
                continue;
            }
            if (QUIET_FROM.key.equals(name) || QUIET_UNTIL.key.equals(name)) {
                QuietHour hour = QuietHour.fromFile(values.opt(name));
                if (hour == null) throw new Rejected(Reason.VALUE, "Not an hour on the hour: " + name);
                if (QUIET_FROM.key.equals(name)) quietFrom = hour;
                else quietUntil = hour;
                continue;
            }
            if (SEEN_KEEP.key.equals(name)) {
                seenKeep = SeenPosts.Keep.fromFile(values.opt(name));
                if (seenKeep == null) throw new Rejected(Reason.VALUE, "Not a seen posts time: " + name);
                continue;
            }
            if (LOCK_AFTER.key.equals(name)) {
                lockAfter = AppLock.After.fromFile(values.opt(name));
                if (lockAfter == null) throw new Rejected(Reason.VALUE, "Not an app lock time: " + name);
                continue;
            }
            if (PAUSE_LENGTH.key.equals(name)) {
                pauseLength = OpeningPause.Length.fromFile(values.opt(name));
                if (pauseLength == null) throw new Rejected(Reason.VALUE, "Not an opening pause length: " + name);
                continue;
            }
            if (BREAK_AFTER.key.equals(name)) {
                breakAfter = BreakTimer.Use.fromFile(values.opt(name));
                if (breakAfter == null) throw new Rejected(Reason.VALUE, "Not a time before a break: " + name);
                continue;
            }
            if (BREAK_LENGTH.key.equals(name)) {
                breakLength = BreakTimer.Length.fromFile(values.opt(name));
                if (breakLength == null) throw new Rejected(Reason.VALUE, "Not a break length: " + name);
                continue;
            }
            if (WATCHED_KEEP.key.equals(name)) {
                watchedKeep = RecentlyWatched.Keep.fromFile(values.opt(name));
                if (watchedKeep == null) throw new Rejected(Reason.VALUE, "Not a watched entries time: " + name);
                continue;
            }
            if (TEXT_SIZE.key.equals(name)) {
                textSize = TextSize.Scale.fromFile(values.opt(name));
                if (textSize == null) throw new Rejected(Reason.VALUE, "Not a text size: " + name);
                continue;
            }
            if (ACCENT.key.equals(name)) {
                accent = AccentColor.Preset.fromFile(values.opt(name));
                if (accent == null) throw new Rejected(Reason.VALUE, "Not an accent color: " + name);
                continue;
            }
            if (CEILING.key.equals(name)) {
                ceiling = ReactionCeiling.fromFile(values.opt(name));
                if (ceiling == null) throw new Rejected(Reason.VALUE, "Not a reaction ceiling: " + name);
                continue;
            }
            if (TO.key.equals(name)) {
                to = SaveTo.fromFile(values.opt(name));
                if (to == null) throw new Rejected(Reason.VALUE, "Not a save location: " + name);
                continue;
            }
            if (ACTION.key.equals(name)) {
                action = SendLink.Action.fromFile(values.opt(name));
                if (action == null) throw new Rejected(Reason.VALUE, "Not a download action: " + name);
                continue;
            }
            if (APP.key.equals(name)) {
                Object value = values.opt(name);
                // The setting's name only, as for the word lists: what was typed stays out of a refusal.
                if (!SendLink.isFileApp(value)) throw new Rejected(Reason.VALUE, "Not one package name: " + name);
                app = (String) value;
                continue;
            }
            if (HIDDEN.key.equals(name) || KEPT.key.equals(name)) {
                Object value = values.opt(name);
                // The setting's name only: a list's words stay out of what a refusal says.
                if (!(value instanceof String) || !PostWords.isClean((String) value)) {
                    throw new Rejected(Reason.VALUE, "Not one clean word list: " + name);
                }
                if (HIDDEN.key.equals(name)) hidden = (String) value;
                else kept = (String) value;
                continue;
            }
            if (SOURCES.key.equals(name)) {
                Object value = values.opt(name);
                // The setting's name only, as for the word lists: what was typed stays out of a refusal.
                if (!(value instanceof String) || !PostSources.isClean((String) value)) {
                    throw new Rejected(Reason.VALUE, "Not one clean list of people, Pages and sites: " + name);
                }
                sources = (String) value;
                continue;
            }
            if (SHARE_ITEMS.key.equals(name)) {
                Object value = values.opt(name);
                // Any type name is taken, one this build doesn't know included. Anything that isn't
                // one clean list refuses the file, as a sources list does.
                if (!(value instanceof String) || !ShareSheetItems.isClean((String) value)) {
                    throw new Rejected(Reason.VALUE, "Not one clean list of share items: " + name);
                }
                shareItems = (String) value;
                continue;
            }
            if (MENU_TILES.key.equals(name)) {
                Object value = values.opt(name);
                if (!(value instanceof String) || !MenuTiles.isClean((String) value)) {
                    throw new Rejected(Reason.VALUE, "Not one clean list of Menu tiles: " + name);
                }
                menuTiles = (String) value;
                continue;
            }
            if (FEED_TYPES.key.equals(name)) {
                Object value = values.opt(name);
                if (!(value instanceof String) || !FeedTypeNames.isClean((String) value)) {
                    throw new Rejected(Reason.VALUE, "Not one clean list of feed types: " + name);
                }
                feedTypes = (String) value;
                continue;
            }
            BooleanSetting setting = known.get(name);
            if (setting == null) {
                // A name this build doesn't know, Pause and the debug settings included: left
                // out and counted, so the preview can say so.
                unknown++;
                continue;
            }
            Object value = values.opt(name);
            if (!(value instanceof Boolean)) {
                throw new Rejected(Reason.VALUE, "Not true or false: " + name);
            }
            found.put(setting, (Boolean) value);
        }
        if (legacyStories != null) {
            // The alias supplies both choices. Explicit independent keys take precedence, whatever
            // order the JSON object uses, and an absent alias supplies nothing.
            found.putIfAbsent(Settings.HIDE_TOP_STORIES_TRAY, legacyStories);
            found.putIfAbsent(Settings.HIDE_STORIES_BETWEEN_POSTS, legacyStories);
        }
        // The lists share their room. One the file leaves out stays as it is, so it counts as stored.
        if ((hidden != null || kept != null) && !PostWords.fits(
                hidden != null ? hidden : PostWords.clean(HIDDEN.savedValue()),
                kept != null ? kept : PostWords.clean(KEPT.savedValue()))) {
            throw new Rejected(Reason.WORDS, "Word lists past the room they share");
        }
        Map<BooleanSetting, Boolean> ordered = new LinkedHashMap<>();
        for (BooleanSetting setting : ALLOWLIST) {
            Boolean value = found.get(setting);
            if (value != null) ordered.put(setting, value);
        }
        Snapshot read = new Snapshot(ordered, folder, quality, fileName, start, order, hidden, kept, playback, action, app, to,
                subtab, sources, photoName, videoSubfolder, photoSubfolder, reelsQuality, storiesQuality, quietFrom,
                quietUntil, lockAfter, textSize, accent, ceiling, unknown);
        read.seenKeep = seenKeep;
        read.pauseLength = pauseLength;
        read.breakAfter = breakAfter;
        read.breakLength = breakLength;
        read.watchedKeep = watchedKeep;
        read.shareItems = shareItems;
        read.menuTiles = menuTiles;
        read.feedTypes = feedTypes;
        read.homeFeed = homeFeed;
        read.background = background;
        read.lightBackground = lightBackground;
        read.badge = badge;
        read.logo = logo;
        read.tab = tab;
        read.headerText = headerText;
        read.reelsOrder = reelsOrder;
        read.burnIn = burnIn;
        read.burnInWait = burnInWait;
        return read;
    }

    /** A Background color as the row keeps it: blank, or {@code #RRGGBB} in capitals that {@link AmoledBackground} takes. */
    static boolean isBackground(@Nullable Object value) {
        return value instanceof String && value.equals(AmoledBackground.stored((String) value));
    }

    /** A Light background color as its row keeps it, which {@link AmoledLightBackground} takes. */
    static boolean isLightBackground(@Nullable Object value) {
        return value instanceof String && value.equals(AmoledLightBackground.stored((String) value));
    }

    /** A Badge, Logo or Selected tab color as its row keeps it, which {@link MarkColors} takes. */
    static boolean isMarkColor(@Nullable Object value) {
        return value instanceof String && value.equals(MarkColors.stored((String) value));
    }

    /**
     * Writes the switches and the other settings a file changes, all of them in one preference
     * commit. A setting the file doesn't name is left as it is.
     *
     * @return how many settings changed, each setting that isn't a switch counted as one.
     * @throws ApplyFailed when the commit failed. {@link Setting#saveAll} puts the switches
     *                     back, live and stored; {@link ApplyFailed#rolledBack} says whether that
     *                     worked for every one of them.
     */
    static int apply(Snapshot snapshot) throws ApplyFailed {
        synchronized (Setting.class) {
            try {
                // A preview can outlive an edit to the list its partial file leaves in place.
                if (!snapshot.wordListsFit()) throw new IOException("Word lists past their shared room");
                Map<Setting<?>, Object> changes = snapshot.changes();
                if (changes.isEmpty()) return 0;
                StoriesSetting.finishMigration();
                Setting.saveAll(changes);
                return changes.size();
            } catch (Setting.BatchFailed failed) {
                throw new ApplyFailed(failed.restored, failed);
            } catch (IOException | RuntimeException refused) {
                // Refused before anything was written.
                throw new ApplyFailed(true, refused);
            }
        }
    }
}
