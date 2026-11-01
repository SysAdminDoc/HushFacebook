/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.facebook.feed.SeenPosts;
import app.morphe.extension.facebook.misc.AppLock;
import app.morphe.extension.facebook.notifications.PushDiagnostics;
import app.morphe.extension.facebook.reels.BurnInMode;
import app.morphe.extension.facebook.theme.MaterialYouTheme;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Every patch in this source, and what Pause does to it.
 *
 * <p>Pause and safe mode work through the switches: while either is on, every feature switch
 * answers off and the hook behind it takes Facebook's own path. The hook's code is still there,
 * only its answer changes, and Debug logging keeps its saved value. An edit made when you patched
 * has no switch to ask: a neutered method or a disabled manifest component stays in until you
 * patch again. The reel sidebar's Download button and the story menu's Save item were edits like
 * that until each asked its switch before it goes in, and the video menu's item asks its own the
 * same way. Some patches are both, so each one says which of its parts stay.
 *
 * <p>The settings screen and the diagnostic report read this list, so they can't disagree about
 * it. A family is found in this build by the name of its {@link SettingsStatus} method, the same
 * name the patch uses to switch that method on.
 */
public enum PatchFamily {
    SPONSORED_POSTS(FamilyNames.SPONSORED_POSTS, "sponsoredPosts", null,
            Settings.HIDE_SPONSORED_POSTS, Settings.HIDE_PROMOTED_POSTS),
    SUGGESTED_POSTS(FamilyNames.SUGGESTED_POSTS, "suggestedPosts", null,
            Settings.HIDE_SUGGESTED_POSTS, Settings.HIDE_SUGGESTED_FOR_YOU, Settings.HIDE_PEOPLE_YOU_MAY_KNOW,
            Settings.HIDE_SUGGESTED_GROUPS, Settings.HIDE_GROUP_POSTS_NOT_JOINED, Settings.HIDE_STORIES_YOU_MIGHT_LIKE,
            Settings.HIDE_FEED_MEMORIES, Settings.HIDE_FEED_FRIEND_REQUESTS, Settings.HIDE_FRIENDS_LOCATIONS,
            Settings.HIDE_NOTIFICATION_POST_FOOTER, Settings.HIDE_FIND_FRIENDS_CARD, Settings.HIDE_SEARCH_FOR_YOU,
            Settings.HIDE_SEARCH_PEOPLE_YOU_MAY_KNOW, Settings.HIDE_RELATED_POSTS),
    STORIES_TRAY(FamilyNames.STORIES_TRAY, "storiesTray", null,
            Settings.HIDE_TOP_STORIES_TRAY, Settings.HIDE_STORIES_BETWEEN_POSTS, Settings.HIDE_HOME_COMPOSER),
    FEED_REELS(FamilyNames.FEED_REELS, "feedReels", null,
            Settings.HIDE_FEED_REELS),
    RETURN_REFRESH(FamilyNames.RETURN_REFRESH, "returnRefresh", null,
            Settings.BLOCK_RETURN_REFRESH, Settings.RETURN_REFRESH_NO_LIMIT, Settings.BACK_KEEPS_HOME),
    AI_DETECTED_POSTS(FamilyNames.AI_DETECTED_POSTS, "aiDetectedPosts", null,
            Settings.HIDE_AI_DETECTED_POSTS, Settings.HIDE_AI_LABELLED_POSTS, Settings.HIDE_AI_DETECTED_REELS,
            Settings.HIDE_META_AI_FEED_UNITS, Settings.HIDE_AI_CHARACTER_POSTS),
    POST_WORDS(FamilyNames.POST_WORDS, "postWords", null,
            Settings.HIDE_POSTS_WITH_WORDS, Settings.POST_WORDS_WHOLE_WORDS, Settings.HIDE_POSTS_FROM_SOURCES,
            Settings.HIDE_PHOTO_POSTS, Settings.HIDE_VIDEO_POSTS, Settings.HIDE_LINK_POSTS,
            Settings.HIDE_BACKGROUND_POSTS, Settings.HIDE_REPOSTS, Settings.HIDE_PAGE_POSTS),
    POST_PROMPTS(FamilyNames.POST_PROMPTS, "postPrompts", null,
            Settings.HIDE_POST_PROMPTS, Settings.HIDE_POST_FOLLOW_LINK, Settings.HIDE_POST_COUNTS,
            Settings.HIDE_POST_REACTIONS, Settings.HIDE_COMMENT_REACTIONS),
    SEEN_POSTS(FamilyNames.SEEN_POSTS, "seenPosts", null,
            Settings.HIDE_SEEN_POSTS),
    META_AI_QUESTIONS(FamilyNames.META_AI_QUESTIONS, "metaAiQuestions", null,
            Settings.HIDE_META_AI_QUESTIONS),
    POST_DATES(FamilyNames.POST_DATES, "postDates", null,
            Settings.KEEP_POST_DATES),
    FULL_POST_TEXT(FamilyNames.FULL_POST_TEXT, "fullPostText", null,
            Settings.SHOW_FULL_POST_TEXT),
    WHO_SHARED(FamilyNames.WHO_SHARED, "whoShared", null,
            Settings.SEE_WHO_SHARED),
    AUTO_TRANSLATION(FamilyNames.AUTO_TRANSLATION, "autoTranslation", null,
            Settings.TURN_OFF_AUTO_TRANSLATION),
    FEEDS_HEADER(FamilyNames.FEEDS_HEADER, "feedsHeader", null,
            Settings.HIDE_FEEDS_HEADER),
    SPONSORED_STORIES(FamilyNames.SPONSORED_STORIES, "sponsoredStories", null,
            Settings.HIDE_SPONSORED_STORIES),
    SUGGESTED_STORIES(FamilyNames.SUGGESTED_STORIES, "suggestedStories", null,
            Settings.HIDE_SUGGESTED_STORIES, Settings.HIDE_CONTACT_IMPORT_CARD, Settings.HIDE_STORY_PROMPTS,
            Settings.HIDE_PAGE_STORIES, Settings.HIDE_SEEN_STORIES),
    STORY_AUTO_ADVANCE(FamilyNames.STORY_AUTO_ADVANCE, "storyAutoAdvance", null,
            Settings.BLOCK_STORY_AUTO_ADVANCE, Settings.LOOP_STORIES),
    STORY_SEEN(FamilyNames.STORY_SEEN, "storySeen", null,
            Settings.VIEW_STORIES_ANONYMOUSLY, Settings.MARK_STORIES_SEEN),
    STORY_UPLOAD_HD(FamilyNames.STORY_UPLOAD_HD, "storyUploadHd", null,
            Settings.UPLOAD_STORIES_IN_HD),
    STORY_MUSIC_LENGTH(FamilyNames.STORY_MUSIC_LENGTH, "storyMusicLength", null,
            Settings.LONGER_STORY_MUSIC),
    STORIES_TRAY_CIRCLES(FamilyNames.STORIES_TRAY_CIRCLES, "storiesTrayCircles", null,
            Settings.STORIES_TRAY_CIRCLES),
    PINCH_TO_ZOOM(FamilyNames.PINCH_TO_ZOOM, "pinchToZoom", null,
            Settings.PINCH_TO_ZOOM, Settings.PINCH_TO_ZOOM_KEEP, Settings.PINCH_TO_ZOOM_DOUBLE_TAP),
    SPONSORED_REELS(FamilyNames.SPONSORED_REELS, "sponsoredReels", "Part of the Reels ad blocking",
            Settings.HIDE_SPONSORED_REELS),
    SPONSORED_SEARCH(FamilyNames.SPONSORED_SEARCH, "sponsoredSearch", null,
            Settings.HIDE_SPONSORED_SEARCH_RESULTS),
    SPONSORED_PROFILE_POSTS(FamilyNames.SPONSORED_PROFILE_POSTS, "sponsoredProfilePosts", null,
            Settings.HIDE_SPONSORED_PROFILE_POSTS),
    PROFILE_NOTES(FamilyNames.PROFILE_NOTES, "profileNotes", null,
            Settings.HIDE_PROFILE_NOTES),
    LOCAL_PROFILE_COUNTS(FamilyNames.LOCAL_PROFILE_COUNTS, "localProfileCounts", null,
            Settings.LOCAL_PROFILE_COUNTS),
    POST_ACTION_BUTTONS(FamilyNames.POST_ACTION_BUTTONS, "postActionButtons", null,
            Settings.POST_SAVE_BUTTON, Settings.POST_DOWNLOAD_BUTTON, Settings.HIDE_POST_LIKE_BUTTON,
            Settings.HIDE_POST_COMMENT_BUTTON, Settings.HIDE_POST_SHARE_BUTTON, Settings.HIDE_POST_SEND_BUTTON),
    SPONSORED_MARKETPLACE(FamilyNames.SPONSORED_MARKETPLACE, "sponsoredMarketplace", null,
            Settings.HIDE_SPONSORED_MARKETPLACE_LISTINGS, Settings.MARKETPLACE_ADVANCED_SEARCH,
            Settings.MARKETPLACE_FILTERS_BUTTON, Settings.MARKETPLACE_LISTING_DATE),
    GAME_ADS(FamilyNames.GAME_ADS, "gameAds", null,
            Settings.BLOCK_GAME_ADS, Settings.ANSWER_REWARDED_GAME_ADS),
    AFFILIATE_LINKS(FamilyNames.AFFILIATE_LINKS, "affiliateLinks", null,
            Settings.HIDE_AFFILIATE_LINKS),
    REEL_DECLUTTER(FamilyNames.REEL_DECLUTTER, "reelDeclutter", null,
            Settings.HIDE_REEL_CHIPS, Settings.HIDE_REEL_FOLLOW_BUTTON, Settings.HIDE_VIDEO_SUBSCRIBE_BUTTON,
            Settings.HIDE_REEL_SOCIAL_FOOTER, Settings.HIDE_REEL_THREADS_CARDS, Settings.REEL_CLEAN_MODE, Settings.PLAY_REELS_ONCE,
            Settings.ORIGINAL_AUDIO, Settings.HIDE_REEL_SEARCH_PILL, Settings.HIDE_VIEWER_TOP_BUTTONS, Settings.BURN_IN_STAY_DIM,
            Settings.HIDE_REEL_COUNTS,
            Settings.HIDE_REEL_SAVE_BUTTON, Settings.HIDE_REEL_WHATSAPP_BUTTON, Settings.HIDE_REEL_LIKE_BUTTON,
            Settings.HIDE_REEL_COMMENT_BUTTON, Settings.HIDE_REEL_SHARE_BUTTON, Settings.SHOW_REEL_COMMENT_BUTTON,
            Settings.SHOW_REEL_POST_DATE,
            Settings.HIDE_REEL_SOUND_BUTTON, Settings.HIDE_REEL_AUDIO_LINE,
            Settings.HIDE_SLOW_CONNECTION_MESSAGE, Settings.FEED_VIDEOS_ALONE, Settings.PLAY_FEED_REELS_WHOLE),
    REEL_PROMPTS(FamilyNames.REEL_PROMPTS, "reelPrompts", null,
            Settings.HIDE_REEL_PROMPTS),
    REEL_WATCH_HISTORY(FamilyNames.REEL_WATCH_HISTORY, "reelWatchHistory", null,
            Settings.DONT_SEND_REEL_WATCH_HISTORY),
    NEWEST_REELS_FIRST(FamilyNames.NEWEST_REELS_FIRST, "newestReelsFirst", null,
            Settings.NEWEST_REELS_FIRST, Settings.PROFILE_REELS_MOST_VIEWED),
    DOUBLE_TAP_LIKE(FamilyNames.DOUBLE_TAP_LIKE, "doubleTapLike", null,
            Settings.TURN_OFF_DOUBLE_TAP_LIKE),
    DOUBLE_TAP_SKIP(FamilyNames.DOUBLE_TAP_SKIP, "doubleTapSkip", null,
            Settings.DOUBLE_TAP_SKIP),
    TAP_SHOWS_CONTROLS(FamilyNames.TAP_SHOWS_CONTROLS, "tapShowsControls", null,
            Settings.TAP_SHOWS_CONTROLS),
    REEL_GESTURES(FamilyNames.REEL_GESTURES, "reelGestures", null,
            Settings.DOUBLE_TAP_OPENS_COMMENTS, Settings.LONG_PRESS_OPENS_COMMENTS, Settings.SELECT_POST_TEXT),
    KEEP_REEL_SPEED(FamilyNames.KEEP_REEL_SPEED, "keepReelSpeed", null,
            Settings.KEEP_REEL_SPEED, Settings.KEEP_VIDEO_SPEED, Settings.SLOWER_REEL_SPEEDS,
            Settings.SPEED_ON_REPLAYS),
    REEL_HOLD(FamilyNames.HOLD_REEL_FOR_2X, "reelHold", null,
            Settings.HOLD_REEL_FOR_2X, Settings.HOLD_REEL_RIGHT_EDGE, Settings.HOLD_FEED_VIDEO_FOR_2X,
            Settings.HIDE_2X_LOCK_HINT),
    DEFAULT_COMMENT_ORDER(FamilyNames.DEFAULT_COMMENT_ORDER, "defaultCommentOrder", null,
            Settings.DEFAULT_COMMENT_ORDER),
    META_AI_SUMMARIES(FamilyNames.META_AI_SUMMARIES, "metaAiSummaries", null,
            Settings.HIDE_META_AI_SUMMARIES),
    COMMENT_SHEET_OPTIONS(FamilyNames.COMMENT_SHEET_OPTIONS, "commentSheetOptions", null,
            Settings.LIKE_ONLY, Settings.HIDE_COMMENT_GIF_STICKER_BUTTONS, Settings.OPEN_REPLY_THREADS,
            Settings.LOAD_WHOLE_REPLY_THREADS, Settings.HIDE_RELATED_GROUPS_UNDER_COMMENTS,
            Settings.HIDE_SHOP_LINK_COMMENTS, Settings.HIDE_COMMENTS_WITH_WORDS,
            Settings.HIDE_INCOMING_COMMENT_TYPING, Settings.HIDE_NEW_COMMENTS_PILL,
            Settings.HIDE_VIDEO_COMMENT_COMPOSER, Settings.TAP_ANYWHERE_ON_VIEW_MORE_REPLIES,
            Settings.HIDE_POST_COMMENT_PREVIEWS, Settings.HIDE_COMMENT_DOWNVOTE_BUTTON,
            Settings.HIDE_COMMENT_LIKE_BUTTON, Settings.HIDE_COMMENT_EMOJI_ROW, Settings.COMMENT_BLOCK_BUTTON),
    FULL_HEIGHT_COMMENTS(FamilyNames.FULL_HEIGHT_COMMENTS, "fullHeightComments", null,
            Settings.OPEN_COMMENTS_FULL_HEIGHT, Settings.PAUSE_REELS_UNDER_COMMENTS, Settings.TAP_REELS_ABOVE_COMMENTS),
    TAG_SUGGESTIONS(FamilyNames.TAG_SUGGESTIONS, "tagSuggestions", null,
            Settings.TAG_SUGGESTIONS_ONLY_AFTER_AT),
    TAP_TO_PLAY(FamilyNames.TAP_TO_PLAY, "tapToPlay", null,
            Settings.TAP_TO_PLAY, Settings.TAP_TO_PLAY_REELS_AFTER_FIRST, Settings.TAP_TO_PLAY_STORIES,
            Settings.INLINE_FEED_VIDEO_CONTROLS,
            Settings.FEED_VIDEO_CAST_BUTTON),
    RESUME_LONG_VIDEOS(FamilyNames.RESUME_LONG_VIDEOS, "resumeLongVideos", null,
            Settings.RESUME_LONG_VIDEOS, Settings.RECENTLY_WATCHED),
    FRAME_STEP(FamilyNames.FRAME_STEP, "frameStep", null,
            Settings.FRAME_STEP),
    PLAYBACK_QUALITY(FamilyNames.PLAYBACK_QUALITY, "defaultPlaybackQuality", null,
            Settings.DEFAULT_PLAYBACK_QUALITY),
    PICTURE_IN_PICTURE(FamilyNames.PICTURE_IN_PICTURE, "pictureInPicture", null,
            Settings.PICTURE_IN_PICTURE, Settings.PICTURE_IN_PICTURE_FROM_MENU, Settings.PICTURE_IN_PICTURE_BUTTON),
    HDR_BRIGHTNESS(FamilyNames.HDR_BRIGHTNESS, "turnOffHdrBrightness", null,
            Settings.TURN_OFF_HDR_BRIGHTNESS, Settings.PREFER_SDR_RENDITIONS, Settings.DRAW_VIDEOS_WITH_THE_SCREEN),
    SKIP_SILENCE(FamilyNames.SKIP_SILENCE, "skipSilentParts", null,
            Settings.SKIP_SILENT_PARTS),
    KEEP_FEED_VIDEOS_MUTED(FamilyNames.KEEP_FEED_VIDEOS_MUTED, "keepFeedVideosMuted", null,
            Settings.KEEP_FEED_VIDEOS_MUTED, Settings.MUTE_STORIES),
    PROGRESS_BAR(FamilyNames.PROGRESS_BAR, "keepProgressBar", null,
            Settings.KEEP_PROGRESS_BAR, Settings.KEEP_PROGRESS_BAR_TIME, Settings.KEEP_REEL_OVERLAY, Settings.TAP_TO_SEEK),
    SYSTEM_FONT(FamilyNames.SYSTEM_FONT, "systemFont", null,
            Settings.USE_SYSTEM_FONT),
    SYSTEM_EMOJI(FamilyNames.SYSTEM_EMOJI, "systemEmoji", null,
            Settings.USE_SYSTEM_EMOJI),
    HAPTICS(FamilyNames.HAPTICS, "turnOffHaptics", null,
            Settings.TURN_OFF_HAPTICS),
    CARRIER_BANNERS(FamilyNames.CARRIER_BANNERS, "carrierBanners", null,
            Settings.HIDE_DATA_MODE_BANNER, Settings.HIDE_FREE_MODE_BANNER),
    SCREEN_TRANSITIONS(FamilyNames.SCREEN_TRANSITIONS, "turnOffScreenTransitions", null,
            Settings.TURN_OFF_SCREEN_TRANSITIONS),
    APP_ICONS(FamilyNames.APP_ICONS, "unlockAppIcons", null,
            Settings.UNLOCK_APP_ICONS, Settings.USE_APP_ICON_IN_HEADER),
    // No switch: the typed text is the choice, and Pause makes it read blank, Facebook's wordmark.
    HEADER_TEXT(FamilyNames.HEADER_TEXT, "headerText", Settings.HEADER_TEXT),
    EXTERNAL_BROWSER(FamilyNames.EXTERNAL_BROWSER, "externalBrowser", null,
            Settings.OPEN_LINKS_EXTERNALLY, Settings.HOLD_LINK_HISTORY),
    SANITIZE_SHARING_LINKS(FamilyNames.SANITIZE_SHARING_LINKS, "sanitizeSharingLinks", null,
            Settings.SANITIZE_SHARING_LINKS, Settings.SHARE_POST_OWN_LINK),
    UPDATE_PROMPTS(FamilyNames.UPDATE_PROMPTS, "updatePrompts", null,
            Settings.STOP_UPDATE_PROMPTS),
    STORY_DOWNLOAD(FamilyNames.STORY_DOWNLOAD, "storyDownload", null,
            Settings.DOWNLOAD_STORIES, Settings.SAVE_STORY_PHOTOS),
    REEL_DOWNLOAD(FamilyNames.REEL_DOWNLOAD, "reelDownload", null,
            Settings.DOWNLOAD_REELS, Settings.SHOW_REEL_DOWNLOAD_BUTTON, Settings.REEL_CAST_BUTTON,
            Settings.COPY_REEL_CAPTION),
    VIDEO_DOWNLOAD(FamilyNames.VIDEO_DOWNLOAD, "videoDownload", null,
            Settings.DOWNLOAD_VIDEOS, Settings.CLIPBOARD_DOWNLOAD, Settings.POST_MENU_DOWNLOAD_FIRST,
            Settings.OPEN_IN_ANOTHER_APP),
    PHOTO_DOWNLOAD(FamilyNames.PHOTO_DOWNLOAD, "photoDownload", null,
            Settings.DOWNLOAD_PHOTOS, Settings.POST_MENU_PHOTO_SAVE),
    START_TAB(FamilyNames.START_TAB, "startTab", null,
            Settings.OPEN_ON_CHOSEN_TAB),
    FOLLOWING_HOME(FamilyNames.FOLLOWING_HOME, "followingHome", null,
            Settings.FOLLOWING_FEED_HOME),
    MARKETPLACE_ONLY(FamilyNames.MARKETPLACE_ONLY, "marketplaceOnly", null,
            Settings.MARKETPLACE_ONLY, Settings.EVENTS_ONLY, Settings.MARKETPLACE_QUIET_NOTIFICATIONS,
            Settings.MARKETPLACE_SKIP_FEED_PREFETCH),
    SELLER_VIEW_PROFILE(FamilyNames.SELLER_VIEW_PROFILE, "sellerViewProfile", null,
            Settings.SHOW_SELLER_VIEW_PROFILE),
    REELS_TAB(FamilyNames.REELS_TAB, "reelsTab", null,
            Settings.HIDE_REELS_TAB),
    REELS_TAB_DOT(FamilyNames.REELS_TAB_DOT, "reelsTabDot", null,
            Settings.HIDE_REELS_TAB_DOT),
    TAB_BADGES(FamilyNames.TAB_BADGES, "tabBadges", null,
            Settings.HIDE_HOME_TAB_BADGE, Settings.HIDE_FRIENDS_TAB_BADGE, Settings.HIDE_MARKETPLACE_TAB_BADGE,
            Settings.HIDE_NOTIFICATIONS_TAB_BADGE, Settings.HIDE_MENU_TAB_BADGE, Settings.HIDE_GROUPS_TAB_BADGE,
            Settings.HIDE_OTHER_TAB_BADGES, Settings.HIDE_APP_ICON_COUNT),
    HIDDEN_TABS(FamilyNames.HIDDEN_TABS, "hiddenTabs", null,
            Settings.HIDE_FEEDS_TAB, Settings.HIDE_FRIENDS_TAB, Settings.HIDE_MARKETPLACE_TAB, Settings.HIDE_GROUPS_TAB,
            Settings.HIDE_GAMING_TAB, Settings.HIDE_EVENTS_TAB, Settings.HIDE_DATING_TAB,
            Settings.HIDE_PROFESSIONAL_DASHBOARD_TAB, Settings.HIDE_SAVED_TAB, Settings.HIDE_AD_CENTER_TAB,
            Settings.HIDE_CREATE_TAB, Settings.HIDE_EXPLORE_TAB, Settings.HIDE_JOBS_TAB),
    BOTTOM_TAB_BAR(FamilyNames.BOTTOM_TAB_BAR, "bottomTabBar", null,
            Settings.BOTTOM_TAB_BAR, Settings.TOP_TAB_BAR, Settings.TAB_BAR_SCROLL_AWAY, Settings.KEEP_TAB_BAR,
            Settings.KEEP_TOP_BAR,
            Settings.PHONE_LAYOUT_ON_BIG_SCREENS, Settings.TRANSLUCENT_BARS, Settings.HIDE_STATUS_BAR,
            Settings.HIDE_MESSENGER_BUTTON, Settings.HIDE_MESSENGER_COUNT, Settings.HIDE_FACEBOOK_LOGO,
            Settings.HIDE_CREATE_BUTTON, Settings.HIDE_SEARCH_BUTTON, Settings.SEARCH_WITH_PICTURE),
    FORCE_DARK_MODE(FamilyNames.FORCE_DARK_MODE, "forceDarkMode", null,
            Settings.FORCE_DARK_MODE),
    MESSENGER_CARD(FamilyNames.MESSENGER_CARD, "messengerCard", null,
            Settings.HIDE_GET_MESSENGER_CARD),
    CHAT_LIST(FamilyNames.CHAT_LIST, "chatListCleanup", null,
            Settings.HIDE_CHAT_NOTES_TRAY, Settings.HIDE_CHAT_PROMOTIONS, Settings.HIDE_CHAT_META_AI_BUTTON),
    MESSENGER_ICON(FamilyNames.MESSENGER_ICON, "messengerIcon", null,
            Settings.OPEN_MESSENGER_APP, Settings.LEAVE_CALLS_AND_CHATS_TO_MESSENGER),
    MENU_PROMOTIONS(FamilyNames.MENU_PROMOTIONS, "menuPromotions", null,
            Settings.HIDE_MENU_UPGRADES, Settings.HIDE_MENU_ALSO_FROM_META, Settings.HIDE_MENU_HELP,
            Settings.KEEP_MENU_SETTINGS_EXPANDED, Settings.HIDE_MENU_MUSE, Settings.HIDE_MENU_META_AI),
    META_UPSELLS(FamilyNames.META_UPSELLS, "metaUpsells", null,
            Settings.HIDE_EDITS_UPSELLS, Settings.HIDE_THREADS_CROSS_POSTING, Settings.HIDE_THREADS_SHARE_BUTTON,
            Settings.HIDE_MUSE_SHARE_BUTTON, Settings.HIDE_META_VERIFIED_UPSELLS, Settings.HIDE_AVATAR_UPSELLS, Settings.HIDE_META_AI_IMAGINE,
            Settings.HIDE_META_AI_POST_BUTTONS),
    SHARE_SHEET_ITEMS(FamilyNames.SHARE_SHEET_ITEMS, "shareSheetItems", Settings.HIDDEN_SHARE_ITEMS,
            new BooleanSetting[]{Settings.HIDE_SHARE_GROUP_BUTTONS, Settings.HIDE_SHARE_PEOPLE_ROW}),
    META_AI_SEARCH(FamilyNames.META_AI_SEARCH, "metaAiSearch", null,
            Settings.HIDE_META_AI_IN_SEARCH),
    SEARCH_HISTORY(FamilyNames.SEARCH_HISTORY, "searchHistory", null,
            Settings.DONT_SAVE_SEARCH_HISTORY),
    SEARCH_DATE_RANGE(FamilyNames.SEARCH_DATE_RANGE, "searchDateRange", null,
            Settings.SEARCH_DATE_RANGE),
    ANALYTICS_UPLOADS(FamilyNames.ANALYTICS_UPLOADS, "analyticsUploads", null,
            Settings.HOLD_ANALYTICS_UPLOADS, Settings.HOLD_CAMERA_ROLL_PROCESSING),
    SCREENSHOTS(FamilyNames.SCREENSHOTS, "allowScreenshots", null,
            Settings.ALLOW_SCREENSHOTS),
    SCREENSHOT_DETECTION(FamilyNames.SCREENSHOT_DETECTION, "screenshotDetection", null,
            Settings.BLOCK_SCREENSHOT_DETECTION),
    TYPING_INDICATOR(FamilyNames.TYPING_INDICATOR, "typingIndicator", null,
            Settings.HIDE_CHAT_TYPING, Settings.HIDE_COMMENT_TYPING),
    READ_RECEIPTS(FamilyNames.READ_RECEIPTS, "readReceipts", null,
            Settings.HIDE_READ_RECEIPTS),
    ORIGINAL_CHAT_MEDIA(FamilyNames.ORIGINAL_CHAT_MEDIA, "originalChatMedia", null,
            Settings.ORIGINAL_CHAT_MEDIA),
    PROMO_NOTIFICATIONS(FamilyNames.PROMO_NOTIFICATIONS, "promoNotifications", null,
            Settings.BLOCK_TRENDING_VIDEO_NOTIFICATIONS, Settings.BLOCK_MEMORY_NOTIFICATIONS,
            Settings.BLOCK_BIRTHDAY_NOTIFICATIONS, Settings.BLOCK_HIGHLIGHT_NOTIFICATIONS,
            Settings.BLOCK_PEOPLE_YOU_MAY_KNOW_NOTIFICATIONS, Settings.BLOCK_NEARBY_NOTIFICATIONS,
            Settings.BLOCK_ACCOUNT_SETUP_NOTIFICATIONS, Settings.BLOCK_GROUP_ACTIVITY_NOTIFICATIONS,
            Settings.BLOCK_EVENT_NOTIFICATIONS, Settings.BLOCK_LIVE_VIDEO_NOTIFICATIONS,
            Settings.BLOCK_REACTION_NOTIFICATIONS, Settings.NOTIFICATION_QUIET_HOURS),
    AD_PREFETCH(FamilyNames.AD_PREFETCH, "adPrefetch", "No ads downloaded in advance"),
    AD_TELEMETRY(FamilyNames.AD_TELEMETRY, "adTelemetry", "No ad tracking"),
    AUDIENCE_NETWORK(FamilyNames.AUDIENCE_NETWORK, "audienceNetwork", "No Facebook ads in other apps"),
    // The theme is written into the APK and stays; Pause takes Black cards back to near black and the lines away.
    AMOLED_THEME(FamilyNames.AMOLED_THEME, "amoledTheme", FamilyNames.AMOLED_THEME, Settings.AMOLED_FLAT_CARDS,
            Settings.AMOLED_FEED_GAPS),
    MATERIAL_YOU_THEME(FamilyNames.MATERIAL_YOU_THEME, "materialYouTheme", FamilyNames.MATERIAL_YOU_THEME),
    // No switch: a list picks the accent, and Pause makes it read Facebook's blue, so nothing stays in.
    ACCENT_COLOR(FamilyNames.ACCENT_COLOR, "accentColor", Settings.ACCENT_COLOR),
    // No switch: three color codes pick the colors, and Pause makes them read Facebook's own.
    MARK_COLORS(FamilyNames.MARK_COLORS, "markColors", Settings.BADGE_COLOR),
    RESTORE_TRUST(FamilyNames.RESTORE_TRUST, "restoreTrust", "Profiles and posts work again"),
    // Runs while the application is built, before a switch can be read, and keeps Facebook starting.
    TRANSLATED_START(FamilyNames.TRANSLATED_START, "translatedStart", "Start-up fix for x86 phones"),
    // A manifest can't be switched at run time: the permissions are renamed in the APK, and Facebook's
    // code has to keep using the names this install holds whether or not Hushfacebook is paused.
    INSTALL_BESIDE_META_APPS(FamilyNames.INSTALL_BESIDE_META_APPS, "installBesideMetaApps",
            "Meta's apps can install beside this one"),
    // The version code is in the manifest, and Facebook's reads of it have to keep answering the
    // real one whether or not Hushfacebook is paused.
    PLAY_STORE_UPDATES(FamilyNames.PLAY_STORE_UPDATES, "playStoreUpdates", "Version number Google Play sees"),
    // The settings entry's own way in, like the logo long press and the launcher shortcut, which
    // stay reachable while paused because the settings screen is where a pause is lifted.
    MENU_SETTINGS_ROW(FamilyNames.MENU_SETTINGS_ROW, "menuSettingsRow", FamilyNames.MENU_SETTINGS_ROW);

    /** The patch's name in Morphe Manager. */
    public final String patchName;

    /** The {@link SettingsStatus} method the patch switches on. */
    final String statusMethod;

    /**
     * What of this patch stays in while Hushfacebook is paused, or null when nothing does: the
     * same name its row on Set when you patched has, so the two pages never call one thing two
     * names. On Pause it's one line of a list, followed by its patch's name in brackets unless
     * it is that name. Never a near copy of the name ("Hushfacebook row in the Menu (Hushfacebook
     * in the Menu)"): it's either the name or words of its own.
     * The English is also a key of {@link L10n}: the screen shows it translated, and the report
     * keeps it in English.
     */
    @Nullable
    public final String staysWhilePaused;

    /** The switches Pause turns off for this patch. Empty when it has none. */
    public final List<BooleanSetting> switches;

    /**
     * For a patch a list picks rather than a switch: the list setting, which Pause makes read its
     * default, Facebook as it ships. Null for the rest.
     */
    @Nullable
    public final Setting<?> choice;

    /**
     * The switches of the settings entry itself, which no family owns: every build with this screen
     * carries them. These are the release check, the Saved, Watch history, Free stuff and settings
     * shortcuts, the logo press and Menu row that open the settings, Lock Facebook, the crash file,
     * Close Facebook when you leave it, the Opening pause and Take breaks. Pause turns them
     * off like a family's switches, so the screen draws them above the Pause row with the rest, but for
     * Lock Facebook and the three ways into the settings, which a pause leaves as they are.
     */
    static final List<BooleanSetting> ENTRY_SWITCHES = Collections.unmodifiableList(
            java.util.Arrays.asList(Settings.CHECK_FOR_RELEASES, Settings.SAVED_SHORTCUT,
                    Settings.WATCH_HISTORY_SHORTCUT, Settings.FREE_STUFF_SHORTCUT, Settings.SETTINGS_SHORTCUT,
                    Settings.SETTINGS_LOGO_PRESS,
                    Settings.SETTINGS_MENU_ROW, Settings.APP_LOCK,
                    Settings.SAVE_CRASH_FILE, Settings.CLOSE_WHEN_LEFT, Settings.OPENING_PAUSE,
                    Settings.TAKE_BREAKS));

    /**
     * The switches the download patches share and none of them owns: each shapes what every
     * save picks, a story's, a reel's or a feed video's, so it's on the screen under Downloads
     * whenever one of them is in the build. Today that's saves other apps can open, converting to
     * keep the full size, and quiet saves.
     * Pause turns them off like a family's switches, and a paused Facebook makes no Hushfacebook
     * saves anyway.
     */
    static final List<BooleanSetting> DOWNLOAD_SWITCHES = Collections.unmodifiableList(
            java.util.Arrays.asList(Settings.DOWNLOAD_COMPATIBLE, Settings.DOWNLOAD_CONVERT, Settings.DOWNLOAD_QUIET));

    /** The families whose saves read {@link #DOWNLOAD_SWITCHES}. */
    static final Set<PatchFamily> DOWNLOADS = Collections.unmodifiableSet(
            EnumSet.of(STORY_DOWNLOAD, REEL_DOWNLOAD, VIDEO_DOWNLOAD, PHOTO_DOWNLOAD));

    /**
     * The patches Morphe Manager leaves out of its default selection. Each one goes in when you
     * patch with no switch to leave Facebook as it ships: the two themes rewrite dark mode's colours,
     * and Disable Play Store updates raises the version number, which a Root Mount install can't
     * take and every later build has to keep. Everything else is in the default selection, with any
     * switch a patch brought in from this list starting off.
     */
    private static final EnumSet<PatchFamily> OPT_IN = EnumSet.of(AMOLED_THEME, MATERIAL_YOU_THEME, PLAY_STORE_UPDATES);

    /**
     * The patches Morphe Manager selects by default. One of them left out is the usual answer to a
     * report of ads or suggestions that still show (#29, #35), so the overview and the report name
     * the ones a build lacks. PatchFamilyTest holds this to the "use" flags in patches-list.json, so
     * a new patch fails it until patches-list.json and {@link #OPT_IN} agree on it.
     */
    static final Set<PatchFamily> DEFAULT_SELECTION = Collections.unmodifiableSet(EnumSet.complementOf(OPT_IN));

    /** The families a test says this build carries, instead of asking {@link SettingsStatus}. */
    @Nullable
    static volatile Set<PatchFamily> inBuildForTests;

    PatchFamily(String patchName, String statusMethod, @Nullable String staysWhilePaused,
                BooleanSetting... switches) {
        this.patchName = patchName;
        this.statusMethod = statusMethod;
        this.staysWhilePaused = staysWhilePaused;
        this.switches = Collections.unmodifiableList(Arrays.asList(switches));
        this.choice = null;
    }

    PatchFamily(String patchName, String statusMethod, Setting<?> choice) {
        this(patchName, statusMethod, choice, new BooleanSetting[0]);
    }

    /**
     * A family set by its list that also has switches. The switches come as an array, not varargs,
     * so a call passing null as its third argument still picks the constructor for switches alone.
     */
    PatchFamily(String patchName, String statusMethod, Setting<?> choice, BooleanSetting[] switches) {
        this.patchName = patchName;
        this.statusMethod = statusMethod;
        this.staysWhilePaused = null;
        this.switches = Collections.unmodifiableList(Arrays.asList(switches.clone()));
        this.choice = choice;
    }

    /** Whether this patch was selected for this build. */
    public boolean inBuild() {
        Set<PatchFamily> forced = inBuildForTests;
        if (forced != null) return forced.contains(this);
        try {
            return Boolean.TRUE.equals(SettingsStatus.class.getMethod(statusMethod).invoke(null));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Logger.printException(() -> "Could not ask whether " + patchName + " is in this build", failure);
            return false;
        }
    }

    /** The families this build carries, in declaration order. */
    public static Set<PatchFamily> inThisBuild() {
        Set<PatchFamily> found = EnumSet.noneOf(PatchFamily.class);
        for (PatchFamily family : values()) {
            if (family.inBuild()) found.add(family);
        }
        return found;
    }

    /** The names of the default patches this build doesn't carry, in declaration order. */
    static List<String> missingDefaults(Set<PatchFamily> inBuild) {
        List<String> names = new ArrayList<>();
        for (PatchFamily family : values()) {
            if (DEFAULT_SELECTION.contains(family) && !inBuild.contains(family)) names.add(family.patchName);
        }
        return names;
    }

    /**
     * What of these families stays in while Hushfacebook is paused, in the phone's language, or
     * null when a pause turns every one of them off: a line saying why, one line per item, and
     * how to rule one out.
     *
     * <p>Each item is followed by its patch's name in brackets, the name Morphe Manager lists it
     * under, which stays English there, unless the item is that name. "Part of the Reels ad
     * blocking" is found in Manager as Hide sponsored reels, and nothing in its own words says so.
     */
    @Nullable
    static String staysWhilePausedSummary(Set<PatchFamily> inBuild) {
        List<String> lines = new ArrayList<>();
        for (PatchFamily family : values()) {
            if (inBuild.contains(family) && family.staysWhilePaused != null) {
                String item = "• " + L10n.t(family.staysWhilePaused);
                if (!family.staysWhilePaused.equals(family.patchName)) item += " (" + L10n.isolate(family.patchName) + ")";
                lines.add(item);
            }
        }
        if (lines.isEmpty()) return null;
        // Joined before the call: every literal inside an L10n call is read as text to translate.
        String list = String.join("\n", lines);
        return L10n.quantity(lines.size(),
                "Set when you patched, so Pause can't turn it off:\n%1$s\nTo rule it out, patch again and leave out "
                        + "its patch.",
                "Set when you patched, so Pause can't turn these off:\n%1$s\nTo rule one out, patch again and leave "
                        + "out its patch.",
                list);
    }

    /**
     * One line per family in this build, saying whether a switch runs it, what the switch is set
     * to and what stays in while paused, then the switches every download shares when a download
     * patch is in, then the families this build doesn't carry, and which of those Morphe Manager
     * selects by default.
     */
    static List<String> reportLines(Set<PatchFamily> inBuild, boolean paused) {
        List<String> lines = new ArrayList<>();
        List<String> absent = new ArrayList<>();
        for (PatchFamily family : values()) {
            if (inBuild.contains(family)) lines.add(family.reportLine(paused));
            else absent.add(family.patchName);
        }
        if (!Collections.disjoint(inBuild, DOWNLOADS)) lines.add(downloadSwitchesLine(paused));
        if (!absent.isEmpty()) lines.add("not in this build: " + String.join(", ", absent));
        List<String> defaults = missingDefaults(inBuild);
        if (!defaults.isEmpty()) lines.add("left out of Manager's default selection: " + String.join(", ", defaults));
        return lines;
    }

    /**
     * The shared download switches as saved. They shape a save rather than run a hook, so the line
     * says what each is set to and never that downloads are off.
     */
    private static String downloadSwitchesLine(boolean paused) {
        StringBuilder line = new StringBuilder("Every download patch: ");
        if (paused) line.append("off while paused (saved ");
        for (int i = 0; i < DOWNLOAD_SWITCHES.size(); i++) {
            if (i > 0) line.append(", ");
            BooleanSetting setting = DOWNLOAD_SWITCHES.get(i);
            line.append(setting.key).append(setting.savedValue() ? "=on" : "=off");
        }
        if (paused) line.append(')');
        return line.toString();
    }

    /**
     * "on", "disabled by its switch" or "disabled while paused", then the saved switches. A
     * family with independent switches is on while either is. Options need their main switch. A
     * family set by its list says so, with its switches saved beside the list. The AMOLED theme is
     * on either way, its switches being options on it.
     */
    private String reportLine(boolean paused) {
        StringBuilder line = new StringBuilder(patchName).append(": ");
        if (choice != null) {
            StringBuilder saved = new StringBuilder(choice.key).append('=').append(choice.savedValue());
            for (BooleanSetting setting : switches) {
                saved.append(", ").append(setting.key).append(setting.savedValue() ? "=on" : "=off");
            }
            return line.append(paused ? "disabled while paused (saved " + saved + ")" : "set by its list (" + saved + ")")
                    .toString();
        }
        if (switches.isEmpty()) {
            return line.append("no switch, stays in while paused: ").append(staysWhilePaused).toString();
        }
        boolean anyOn = false;
        for (BooleanSetting setting : switches) anyOn |= setting.savedValue();
        // Options cannot enable these families without their main switch.
        if (this == MARKETPLACE_ONLY) anyOn = Settings.MARKETPLACE_ONLY.savedValue() || Settings.EVENTS_ONLY.savedValue();
        if (this == REEL_DOWNLOAD) anyOn = Settings.DOWNLOAD_REELS.savedValue() || Settings.COPY_REEL_CAPTION.savedValue()
                || Settings.REEL_CAST_BUTTON.savedValue();
        if (this == VIDEO_DOWNLOAD) anyOn = Settings.DOWNLOAD_VIDEOS.savedValue() || Settings.OPEN_IN_ANOTHER_APP.savedValue();
        if (this == TAP_TO_PLAY) anyOn = Settings.TAP_TO_PLAY.savedValue() || Settings.INLINE_FEED_VIDEO_CONTROLS.savedValue()
                || Settings.FEED_VIDEO_CAST_BUTTON.savedValue();
        // The time switch starts on, and does nothing without the bar kept; Tap the bar to seek works alone.
        if (this == PROGRESS_BAR) anyOn = Settings.KEEP_PROGRESS_BAR.savedValue() || Settings.TAP_TO_SEEK.savedValue();
        if (this == PROMO_NOTIFICATIONS) {
            anyOn = false;
            for (BooleanSetting setting : switches) {
                if (setting != Settings.NOTIFICATION_QUIET_HOURS) anyOn |= setting.savedValue();
            }
        }
        // Burn-in guard runs by its list; Stay dim when I tap does nothing with the guard off (#288).
        if (this == REEL_DECLUTTER) {
            anyOn = Settings.BURN_IN_GUARD.savedValue() != BurnInMode.OFF;
            for (BooleanSetting setting : switches) {
                if (setting != Settings.BURN_IN_STAY_DIM) anyOn |= setting.savedValue();
            }
        }
        if (this == POST_WORDS) {
            anyOn = Settings.HIDE_POSTS_WITH_WORDS.savedValue() || Settings.HIDE_POSTS_FROM_SOURCES.savedValue()
                    || Settings.HIDE_PHOTO_POSTS.savedValue() || Settings.HIDE_VIDEO_POSTS.savedValue()
                    || Settings.HIDE_LINK_POSTS.savedValue() || Settings.HIDE_BACKGROUND_POSTS.savedValue()
                    || Settings.HIDE_REPOSTS.savedValue() || Settings.HIDE_PAGE_POSTS.savedValue();
        }
        // The theme runs with its switches off and while paused: they only flatten the cards and draw the lines.
        if (this == AMOLED_THEME) {
            line.append(paused ? "on, its switches off while paused (saved " : "on (");
        } else {
            line.append(paused ? "disabled while paused (saved " : anyOn ? "on (" : "disabled by its switch (");
        }
        for (int i = 0; i < switches.size(); i++) {
            if (i > 0) line.append(", ");
            BooleanSetting setting = switches.get(i);
            // Burn-in guard's two lists, which no switch holds, sit before its switch (#288).
            if (setting == Settings.BURN_IN_STAY_DIM) {
                line.append(Settings.BURN_IN_GUARD.key).append('=').append(Settings.BURN_IN_GUARD.savedValue()).append(", ")
                        .append(Settings.BURN_IN_WAIT.key).append('=').append(Settings.BURN_IN_WAIT.savedValue()).append(", ");
            }
            line.append(setting.key).append(setting.savedValue() ? "=on" : "=off");
        }
        line.append(')');
        if (staysWhilePaused != null) line.append("; stays in while paused: ").append(staysWhilePaused);
        return line.toString();
    }

    /**
     * Registers the [PATCHES] report section, and tells Hook status which families no pause
     * reaches, so a paused export marks only the ones a switch runs. Registering twice keeps one.
     */
    public static void registerDiagnostics() {
        LogBufferManager.registerReportSection(REPORT);
        LogBufferManager.registerReportSection(SupportedLinks.REPORT);
        LogBufferManager.registerReportSection(LastScreen.REPORT);
        LogBufferManager.registerReportSection(ScreenLog.REPORT);
        LogBufferManager.registerReportSection(MaterialYouTheme.REPORT);
        LogBufferManager.registerReportSection(SeenPosts.REPORT);
        LogBufferManager.registerReportSection(AppLock.REPORT);
        LogBufferManager.registerReportSection(PushDiagnostics.REPORT);
        LogBufferManager.registerReportSection(NetworkUsage.REPORT);
        for (PatchFamily family : values()) {
            // The theme's hooks run paused too: only Black cards, its one switch, steps back.
            if ((family.switches.isEmpty() && family.choice == null) || family == AMOLED_THEME) {
                HookStatus.runsWhilePaused(family.patchName);
            }
        }
    }

    /** The [PATCHES] section of the diagnostic report. */
    static final LogBufferManager.ReportSection REPORT = new LogBufferManager.ReportSection() {
        @Override
        public String title() {
            return "PATCHES";
        }

        @Override
        public List<String> lines() {
            return reportLines(inThisBuild(), HushfacebookPause.isPaused());
        }
    };
}
