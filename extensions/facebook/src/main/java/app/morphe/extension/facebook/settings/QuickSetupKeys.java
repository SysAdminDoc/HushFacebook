/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.preference.QuickSetups;

/**
 * Which switches each Quick setups choice changes, all in one place.
 *
 * <p>Quiet and Privacy only turn switches on, and only switches whose effect is plain from their
 * name and takes nothing away that someone would miss. Left out on purpose: the tabs, Marketplace
 * only, Hide Reels in the feed and Only reels from accounts I follow, which change what Facebook
 * shows; Memories, birthdays, events
 * and the other notifications people ask for; Meta AI's Imagine, its buttons under posts and in
 * Chats, its Menu shortcut and its comment summaries, which some people use; Hide read receipts, which can leave a chat unread on
 * this phone; Don't send reel watch history, which changes how reels are ranked; View stories
 * anonymously, which leaves stories unseen; and Open links in your browser, which changes how
 * every link opens. Back to defaults puts every switch a settings file carries back the way it
 * started, except Lock Facebook: one tap shouldn't take a lock off, the same way Pause leaves it on.
 */
final class QuickSetupKeys {
    /** Fewer ads, suggestions, promos, prompts and nagging notifications. */
    static final List<BooleanSetting> QUIET = Collections.unmodifiableList(Arrays.asList(
            Settings.HIDE_SPONSORED_POSTS,
            Settings.HIDE_PROMOTED_POSTS,
            Settings.HIDE_SUGGESTED_POSTS,
            Settings.HIDE_SUGGESTED_FOR_YOU,
            Settings.HIDE_PEOPLE_YOU_MAY_KNOW,
            Settings.HIDE_SUGGESTED_GROUPS,
            Settings.HIDE_STORIES_YOU_MIGHT_LIKE,
            Settings.HIDE_META_AI_FEED_UNITS,
            Settings.HIDE_POST_PROMPTS,
            Settings.HIDE_META_AI_QUESTIONS,
            Settings.HIDE_SPONSORED_STORIES,
            Settings.HIDE_SUGGESTED_STORIES,
            Settings.HIDE_CONTACT_IMPORT_CARD,
            Settings.HIDE_STORY_PROMPTS,
            Settings.HIDE_SPONSORED_REELS,
            Settings.HIDE_REEL_CHIPS,
            Settings.HIDE_REEL_THREADS_CARDS,
            Settings.HIDE_REEL_PROMPTS,
            Settings.HIDE_REELS_TAB_DOT,
            Settings.HIDE_SPONSORED_SEARCH_RESULTS,
            Settings.HIDE_META_AI_IN_SEARCH,
            Settings.HIDE_SPONSORED_PROFILE_POSTS,
            Settings.HIDE_SPONSORED_MARKETPLACE_LISTINGS,
            Settings.BLOCK_GAME_ADS,
            Settings.HIDE_AFFILIATE_LINKS,
            Settings.STOP_UPDATE_PROMPTS,
            Settings.HIDE_GET_MESSENGER_CARD,
            Settings.HIDE_CHAT_PROMOTIONS,
            Settings.HIDE_MENU_UPGRADES,
            Settings.HIDE_MENU_ALSO_FROM_META,
            Settings.HIDE_MENU_MUSE,
            Settings.HIDE_EDITS_UPSELLS,
            Settings.HIDE_THREADS_CROSS_POSTING,
            Settings.HIDE_META_VERIFIED_UPSELLS,
            Settings.HIDE_AVATAR_UPSELLS,
            Settings.BLOCK_TRENDING_VIDEO_NOTIFICATIONS,
            Settings.BLOCK_PEOPLE_YOU_MAY_KNOW_NOTIFICATIONS,
            Settings.BLOCK_ACCOUNT_SETUP_NOTIFICATIONS,
            Settings.BLOCK_HIGHLIGHT_NOTIFICATIONS));

    /** Less sent about you: usage reports, camera roll scans, link tracking, typing and screenshots. */
    static final List<BooleanSetting> PRIVACY = Collections.unmodifiableList(Arrays.asList(
            Settings.HOLD_ANALYTICS_UPLOADS,
            Settings.HOLD_CAMERA_ROLL_PROCESSING,
            Settings.HOLD_LINK_HISTORY,
            Settings.SANITIZE_SHARING_LINKS,
            Settings.BLOCK_SCREENSHOT_DETECTION,
            Settings.HIDE_CHAT_TYPING,
            Settings.HIDE_COMMENT_TYPING));

    /**
     * Every feature switch a settings file carries but Lock Facebook. Pause, debug logging and the
     * release check aren't in a settings file, so they stay as they are too.
     */
    static final List<BooleanSetting> DEFAULTS = withoutLock(SettingsBackup.ALLOWLIST);

    private QuickSetupKeys() {
    }

    static Map<BooleanSetting, Boolean> quiet() {
        return QuickSetups.allOn(QUIET);
    }

    static Map<BooleanSetting, Boolean> privacy() {
        return QuickSetups.allOn(PRIVACY);
    }

    static Map<BooleanSetting, Boolean> defaults() {
        return QuickSetups.defaults(DEFAULTS);
    }

    private static List<BooleanSetting> withoutLock(List<BooleanSetting> switches) {
        List<BooleanSetting> kept = new ArrayList<>(switches);
        kept.remove(Settings.APP_LOCK);
        return Collections.unmodifiableList(kept);
    }
}
