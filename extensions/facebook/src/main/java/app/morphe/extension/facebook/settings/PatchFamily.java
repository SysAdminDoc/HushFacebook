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
            Settings.HIDE_SUGGESTED_GROUPS, Settings.HIDE_STORIES_YOU_MIGHT_LIKE, Settings.HIDE_FEED_MEMORIES,
            Settings.HIDE_FEED_FRIEND_REQUESTS, Settings.HIDE_FRIENDS_LOCATIONS),
    STORIES_TRAY(FamilyNames.STORIES_TRAY, "storiesTray", null,
            Settings.HIDE_TOP_STORIES_TRAY, Settings.HIDE_STORIES_BETWEEN_POSTS, Settings.HIDE_HOME_COMPOSER),
    FEED_REELS(FamilyNames.FEED_REELS, "feedReels", null,
            Settings.HIDE_FEED_REELS),
    RETURN_REFRESH(FamilyNames.RETURN_REFRESH, "returnRefresh", null,
            Settings.BLOCK_RETURN_REFRESH, Settings.RETURN_REFRESH_NO_LIMIT),
    AI_DETECTED_POSTS(FamilyNames.AI_DETECTED_POSTS, "aiDetectedPosts", null,
            Settings.HIDE_AI_DETECTED_POSTS, Settings.HIDE_AI_LABELLED_POSTS, Settings.HIDE_AI_DETECTED_REELS,
            Settings.HIDE_META_AI_FEED_UNITS, Settings.HIDE_AI_CHARACTER_POSTS),
    POST_WORDS(FamilyNames.POST_WORDS, "postWords", null,
            Settings.HIDE_POSTS_WITH_WORDS, Settings.POST_WORDS_WHOLE_WORDS, Settings.HIDE_POSTS_FROM_SOURCES,
            Settings.HIDE_PHOTO_POSTS, Settings.HIDE_VIDEO_POSTS, Settings.HIDE_LINK_POSTS,
            Settings.HIDE_BACKGROUND_POSTS),
    POST_PROMPTS(FamilyNames.POST_PROMPTS, "postPrompts", null,
            Settings.HIDE_POST_PROMPTS, Settings.HIDE_POST_FOLLOW_LINK),
    SEEN_POSTS(FamilyNames.SEEN_POSTS, "seenPosts", null,
            Settings.HIDE_SEEN_POSTS),
    META_AI_QUESTIONS(FamilyNames.META_AI_QUESTIONS, "metaAiQuestions", null,
            Settings.HIDE_META_AI_QUESTIONS),
    POST_DATES(FamilyNames.POST_DATES, "postDates", null,
            Settings.KEEP_POST_DATES),
    AUTO_TRANSLATION(FamilyNames.AUTO_TRANSLATION, "autoTranslation", null,
            Settings.TURN_OFF_AUTO_TRANSLATION),
    FEEDS_HEADER(FamilyNames.FEEDS_HEADER, "feedsHeader", null,
            Settings.HIDE_FEEDS_HEADER),
    SPONSORED_STORIES(FamilyNames.SPONSORED_STORIES, "sponsoredStories", null,
            Settings.HIDE_SPONSORED_STORIES),
    SUGGESTED_STORIES(FamilyNames.SUGGESTED_STORIES, "suggestedStories", null,
            Settings.HIDE_SUGGESTED_STORIES, Settings.HIDE_CONTACT_IMPORT_CARD, Settings.HIDE_STORY_PROMPTS),
    STORY_AUTO_ADVANCE(FamilyNames.STORY_AUTO_ADVANCE, "storyAutoAdvance", null,
            Settings.BLOCK_STORY_AUTO_ADVANCE, Settings.LOOP_STORIES),
    STORY_SEEN(FamilyNames.STORY_SEEN, "storySeen", null,
            Settings.VIEW_STORIES_ANONYMOUSLY, Settings.MARK_STORIES_SEEN),
    SPONSORED_REELS(FamilyNames.SPONSORED_REELS, "sponsoredReels",
            "the part of the Reels ad blocking built in when you patched",
            Settings.HIDE_SPONSORED_REELS),
    SPONSORED_SEARCH(FamilyNames.SPONSORED_SEARCH, "sponsoredSearch", null,
            Settings.HIDE_SPONSORED_SEARCH_RESULTS),
    SPONSORED_PROFILE_POSTS(FamilyNames.SPONSORED_PROFILE_POSTS, "sponsoredProfilePosts", null,
            Settings.HIDE_SPONSORED_PROFILE_POSTS),
    SPONSORED_MARKETPLACE(FamilyNames.SPONSORED_MARKETPLACE, "sponsoredMarketplace", null,
            Settings.HIDE_SPONSORED_MARKETPLACE_LISTINGS),
    GAME_ADS(FamilyNames.GAME_ADS, "gameAds", null,
            Settings.BLOCK_GAME_ADS, Settings.ANSWER_REWARDED_GAME_ADS),
    AFFILIATE_LINKS(FamilyNames.AFFILIATE_LINKS, "affiliateLinks", null,
            Settings.HIDE_AFFILIATE_LINKS),
    REEL_DECLUTTER(FamilyNames.REEL_DECLUTTER, "reelDeclutter", null,
            Settings.HIDE_REEL_CHIPS, Settings.HIDE_REEL_FOLLOW_BUTTON, Settings.HIDE_REEL_SOCIAL_FOOTER,
            Settings.HIDE_REEL_THREADS_CARDS, Settings.REEL_CLEAN_MODE, Settings.PLAY_REELS_ONCE),
    REEL_PROMPTS(FamilyNames.REEL_PROMPTS, "reelPrompts", null,
            Settings.HIDE_REEL_PROMPTS),
    REEL_WATCH_HISTORY(FamilyNames.REEL_WATCH_HISTORY, "reelWatchHistory", null,
            Settings.DONT_SEND_REEL_WATCH_HISTORY),
    DOUBLE_TAP_LIKE(FamilyNames.DOUBLE_TAP_LIKE, "doubleTapLike", null,
            Settings.TURN_OFF_DOUBLE_TAP_LIKE),
    KEEP_REEL_SPEED(FamilyNames.KEEP_REEL_SPEED, "keepReelSpeed", null,
            Settings.KEEP_REEL_SPEED, Settings.KEEP_VIDEO_SPEED, Settings.SLOWER_REEL_SPEEDS),
    REEL_HOLD(FamilyNames.HOLD_REEL_FOR_2X, "reelHold", null,
            Settings.HOLD_REEL_FOR_2X, Settings.HOLD_REEL_RIGHT_EDGE),
    DEFAULT_COMMENT_ORDER(FamilyNames.DEFAULT_COMMENT_ORDER, "defaultCommentOrder", null,
            Settings.DEFAULT_COMMENT_ORDER),
    META_AI_SUMMARIES(FamilyNames.META_AI_SUMMARIES, "metaAiSummaries", null,
            Settings.HIDE_META_AI_SUMMARIES),
    COMMENT_SHEET_OPTIONS(FamilyNames.COMMENT_SHEET_OPTIONS, "commentSheetOptions", null,
            Settings.LIKE_ONLY, Settings.HIDE_COMMENT_GIF_STICKER_BUTTONS, Settings.OPEN_REPLY_THREADS,
            Settings.HIDE_RELATED_GROUPS_UNDER_COMMENTS),
    TAG_SUGGESTIONS(FamilyNames.TAG_SUGGESTIONS, "tagSuggestions", null,
            Settings.TAG_SUGGESTIONS_ONLY_AFTER_AT),
    TAP_TO_PLAY(FamilyNames.TAP_TO_PLAY, "tapToPlay", null,
            Settings.TAP_TO_PLAY, Settings.TAP_TO_PLAY_REELS_AFTER_FIRST),
    RESUME_LONG_VIDEOS(FamilyNames.RESUME_LONG_VIDEOS, "resumeLongVideos", null,
            Settings.RESUME_LONG_VIDEOS),
    PLAYBACK_QUALITY(FamilyNames.PLAYBACK_QUALITY, "defaultPlaybackQuality", null,
            Settings.DEFAULT_PLAYBACK_QUALITY),
    PICTURE_IN_PICTURE(FamilyNames.PICTURE_IN_PICTURE, "pictureInPicture", null,
            Settings.PICTURE_IN_PICTURE),
    HDR_BRIGHTNESS(FamilyNames.HDR_BRIGHTNESS, "turnOffHdrBrightness", null,
            Settings.TURN_OFF_HDR_BRIGHTNESS),
    PROGRESS_BAR(FamilyNames.PROGRESS_BAR, "keepProgressBar", null,
            Settings.KEEP_PROGRESS_BAR, Settings.KEEP_PROGRESS_BAR_TIME),
    SYSTEM_FONT(FamilyNames.SYSTEM_FONT, "systemFont", null,
            Settings.USE_SYSTEM_FONT),
    SYSTEM_EMOJI(FamilyNames.SYSTEM_EMOJI, "systemEmoji", null,
            Settings.USE_SYSTEM_EMOJI),
    HAPTICS(FamilyNames.HAPTICS, "turnOffHaptics", null,
            Settings.TURN_OFF_HAPTICS),
    SCREEN_TRANSITIONS(FamilyNames.SCREEN_TRANSITIONS, "turnOffScreenTransitions", null,
            Settings.TURN_OFF_SCREEN_TRANSITIONS),
    APP_ICONS(FamilyNames.APP_ICONS, "unlockAppIcons", null,
            Settings.UNLOCK_APP_ICONS),
    EXTERNAL_BROWSER(FamilyNames.EXTERNAL_BROWSER, "externalBrowser", null,
            Settings.OPEN_LINKS_EXTERNALLY, Settings.HOLD_LINK_HISTORY),
    SANITIZE_SHARING_LINKS(FamilyNames.SANITIZE_SHARING_LINKS, "sanitizeSharingLinks", null,
            Settings.SANITIZE_SHARING_LINKS, Settings.SHARE_POST_OWN_LINK),
    UPDATE_PROMPTS(FamilyNames.UPDATE_PROMPTS, "updatePrompts", null,
            Settings.STOP_UPDATE_PROMPTS),
    STORY_DOWNLOAD(FamilyNames.STORY_DOWNLOAD, "storyDownload", null,
            Settings.DOWNLOAD_STORIES),
    REEL_DOWNLOAD(FamilyNames.REEL_DOWNLOAD, "reelDownload", null,
            Settings.DOWNLOAD_REELS),
    VIDEO_DOWNLOAD(FamilyNames.VIDEO_DOWNLOAD, "videoDownload", null,
            Settings.DOWNLOAD_VIDEOS, Settings.CLIPBOARD_DOWNLOAD),
    PHOTO_DOWNLOAD(FamilyNames.PHOTO_DOWNLOAD, "photoDownload", null,
            Settings.DOWNLOAD_PHOTOS, Settings.POST_MENU_PHOTO_SAVE),
    START_TAB(FamilyNames.START_TAB, "startTab", null,
            Settings.OPEN_ON_CHOSEN_TAB),
    FOLLOWING_HOME(FamilyNames.FOLLOWING_HOME, "followingHome", null,
            Settings.FOLLOWING_FEED_HOME),
    MARKETPLACE_ONLY(FamilyNames.MARKETPLACE_ONLY, "marketplaceOnly", null,
            Settings.MARKETPLACE_ONLY, Settings.MARKETPLACE_QUIET_NOTIFICATIONS, Settings.MARKETPLACE_SKIP_FEED_PREFETCH),
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
            Settings.BOTTOM_TAB_BAR, Settings.TAB_BAR_SCROLL_AWAY),
    FORCE_DARK_MODE(FamilyNames.FORCE_DARK_MODE, "forceDarkMode", null,
            Settings.FORCE_DARK_MODE),
    MESSENGER_CARD(FamilyNames.MESSENGER_CARD, "messengerCard", null,
            Settings.HIDE_GET_MESSENGER_CARD),
    CHAT_LIST(FamilyNames.CHAT_LIST, "chatListCleanup", null,
            Settings.HIDE_CHAT_NOTES_TRAY, Settings.HIDE_CHAT_PROMOTIONS),
    MESSENGER_ICON(FamilyNames.MESSENGER_ICON, "messengerIcon", null,
            Settings.OPEN_MESSENGER_APP),
    MENU_PROMOTIONS(FamilyNames.MENU_PROMOTIONS, "menuPromotions", null,
            Settings.HIDE_MENU_UPGRADES, Settings.HIDE_MENU_ALSO_FROM_META, Settings.HIDE_MENU_MUSE),
    META_UPSELLS(FamilyNames.META_UPSELLS, "metaUpsells", null,
            Settings.HIDE_EDITS_UPSELLS, Settings.HIDE_THREADS_CROSS_POSTING, Settings.HIDE_THREADS_SHARE_BUTTON,
            Settings.HIDE_META_VERIFIED_UPSELLS, Settings.HIDE_AVATAR_UPSELLS, Settings.HIDE_META_AI_IMAGINE,
            Settings.HIDE_META_AI_POST_BUTTONS),
    SHARE_SHEET_ITEMS(FamilyNames.SHARE_SHEET_ITEMS, "shareSheetItems", Settings.HIDDEN_SHARE_ITEMS,
            new BooleanSetting[]{Settings.HIDE_SHARE_GROUP_BUTTONS}),
    META_AI_SEARCH(FamilyNames.META_AI_SEARCH, "metaAiSearch", null,
            Settings.HIDE_META_AI_IN_SEARCH),
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
    AD_PREFETCH(FamilyNames.AD_PREFETCH, "adPrefetch", "the block on downloading ads in the background"),
    AD_TELEMETRY(FamilyNames.AD_TELEMETRY, "adTelemetry", "the block on reports of ad screenshots and app installs"),
    AUDIENCE_NETWORK(FamilyNames.AUDIENCE_NETWORK, "audienceNetwork", "the Audience Network block"),
    AMOLED_THEME(FamilyNames.AMOLED_THEME, "amoledTheme", "the black background in dark mode"),
    MATERIAL_YOU_THEME(FamilyNames.MATERIAL_YOU_THEME, "materialYouTheme", "the recolored dark mode"),
    // No switch: a list picks the accent, and Pause makes it read Facebook's blue, so nothing stays in.
    ACCENT_COLOR(FamilyNames.ACCENT_COLOR, "accentColor", Settings.ACCENT_COLOR),
    RESTORE_TRUST(FamilyNames.RESTORE_TRUST, "restoreTrust", "the re-signed build fix"),
    // Runs while the application is built, before a switch can be read, and keeps Facebook starting.
    TRANSLATED_START(FamilyNames.TRANSLATED_START, "translatedStart", "the start-up fix for x86 devices"),
    // A manifest can't be switched at run time: the permissions are renamed in the APK, and Facebook's
    // code has to keep using the names this install holds whether or not Hushfacebook is paused.
    INSTALL_BESIDE_META_APPS(FamilyNames.INSTALL_BESIDE_META_APPS, "installBesideMetaApps",
            "the new names for shared permissions"),
    // The version code is in the manifest, and Facebook's reads of it have to keep answering the
    // real one whether or not Hushfacebook is paused.
    PLAY_STORE_UPDATES(FamilyNames.PLAY_STORE_UPDATES, "playStoreUpdates", "the version number Google Play sees"),
    // The settings entry's own way in, like the logo long press and the launcher shortcut, which
    // stay reachable while paused because the settings screen is where a pause is lifted.
    MENU_SETTINGS_ROW(FamilyNames.MENU_SETTINGS_ROW, "menuSettingsRow", "the settings row in Facebook's Menu");

    /** The patch's name in Morphe Manager. */
    public final String patchName;

    /** The {@link SettingsStatus} method the patch switches on. */
    final String statusMethod;

    /**
     * What of this patch stays in while Hushfacebook is paused, or null when nothing does. One
     * thing, never a plural: alone on the screen it's followed by its patch's name in brackets and
     * "It was set when you patched".
     * It says what stays in, not what the patch is called. The name follows it in brackets, so an
     * item that was the name read it twice: "the AMOLED black theme (AMOLED black theme)".
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
     * carries them. These are the release check, the Saved and Watch history shortcuts and Lock Facebook. Pause
     * turns them off like a family's switches, so the screen draws them above the Pause row with the rest.
     */
    static final List<BooleanSetting> ENTRY_SWITCHES = Collections.unmodifiableList(
            java.util.Arrays.asList(Settings.CHECK_FOR_RELEASES, Settings.SAVED_SHORTCUT,
                    Settings.WATCH_HISTORY_SHORTCUT, Settings.APP_LOCK));

    /**
     * The switches the download patches share and none of them owns: each shapes what every
     * save picks, a story's, a reel's or a feed video's, so it's on the screen under Downloads
     * whenever one of them is in the build. Today that's saves other apps can open. Pause turns
     * them off like a family's switches, and a paused Facebook makes no Hushfacebook saves anyway.
     */
    static final List<BooleanSetting> DOWNLOAD_SWITCHES = Collections.singletonList(Settings.DOWNLOAD_COMPATIBLE);

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
     * What of these families stays in while Hushfacebook is paused, as a sentence in the phone's
     * language, or null when a pause turns every one of them off. The list leads the sentence, so
     * its first letter is raised the way that language does it.
     *
     * <p>Each item is followed by its patch's name in brackets, the name Morphe Manager lists it
     * under, which stays English there. "The part of the Reels ad block patched into the app" is
     * found in Manager as Hide sponsored reels, and nothing in the item's own words said so.
     */
    @Nullable
    static String staysWhilePausedSummary(Set<PatchFamily> inBuild) {
        List<String> parts = new ArrayList<>();
        for (PatchFamily family : values()) {
            if (inBuild.contains(family) && family.staysWhilePaused != null) {
                parts.add(L10n.t(family.staysWhilePaused) + " (" + L10n.isolate(family.patchName) + ")");
            }
        }
        if (parts.isEmpty()) return null;
        return L10n.capitalize(L10n.quantity(parts.size(),
                "%1$s. It was set when you patched, so Pause can't turn it off. To rule it out, patch again "
                        + "and leave out that patch.",
                "%1$s. They were set when you patched, so Pause can't turn them off. To rule one out, patch "
                        + "again and leave out the patch in brackets after it.",
                L10n.join(parts)));
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
     * family set by its list says so, with its switches saved beside the list.
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
        if (this == MARKETPLACE_ONLY) anyOn = Settings.MARKETPLACE_ONLY.savedValue();
        if (this == VIDEO_DOWNLOAD) anyOn = Settings.DOWNLOAD_VIDEOS.savedValue();
        if (this == TAP_TO_PLAY) anyOn = Settings.TAP_TO_PLAY.savedValue();
        if (this == PROMO_NOTIFICATIONS) {
            anyOn = false;
            for (BooleanSetting setting : switches) {
                if (setting != Settings.NOTIFICATION_QUIET_HOURS) anyOn |= setting.savedValue();
            }
        }
        if (this == POST_WORDS) {
            anyOn = Settings.HIDE_POSTS_WITH_WORDS.savedValue() || Settings.HIDE_POSTS_FROM_SOURCES.savedValue()
                    || Settings.HIDE_PHOTO_POSTS.savedValue() || Settings.HIDE_VIDEO_POSTS.savedValue()
                    || Settings.HIDE_LINK_POSTS.savedValue() || Settings.HIDE_BACKGROUND_POSTS.savedValue();
        }
        line.append(paused ? "disabled while paused (saved " : anyOn ? "on (" : "disabled by its switch (");
        for (int i = 0; i < switches.size(); i++) {
            if (i > 0) line.append(", ");
            BooleanSetting setting = switches.get(i);
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
        for (PatchFamily family : values()) {
            if (family.switches.isEmpty() && family.choice == null) HookStatus.runsWhilePaused(family.patchName);
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
