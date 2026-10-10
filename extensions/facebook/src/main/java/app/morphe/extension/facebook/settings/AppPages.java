/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.morphe.extension.facebook.settings;

import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.category;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.info;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.quietHourRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.lockAfterRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.toggle;

import android.app.AlertDialog;
import android.content.Context;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import app.morphe.extension.facebook.misc.AppLock;
import app.morphe.extension.facebook.misc.ShareSheetItems;
import app.morphe.extension.facebook.notifications.NotificationSound;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Utils;

/**
 * The category pages for the rest of Facebook: Chats, Menu, Search, Marketplace, Notifications
 * and Links. Each method adds its section to the page
 * {@link HushfacebookPreferenceFragment#initialize} builds, and leaves it out when the build has none of
 * its patches, but for the sections every build has.
 */
@SuppressWarnings("deprecation")
final class AppPages {
    private AppPages() {
    }

    /** Chats: the Get Messenger card, the chat list clean-up and the Messenger icon. */
    static void chats(HushfacebookPreferenceFragment page, PreferenceScreen screen, Context context,
            Set<PatchFamily> build) {
        if (build.contains(PatchFamily.MESSENGER_CARD) || build.contains(PatchFamily.CHAT_LIST)
                || build.contains(PatchFamily.MESSENGER_ICON) || build.contains(PatchFamily.ORIGINAL_CHAT_MEDIA)) {
            PreferenceCategory chats = category(screen, L10n.t("Chats"));
            if (build.contains(PatchFamily.MESSENGER_CARD)) {
                chats.addPreference(toggle(context, Settings.HIDE_GET_MESSENGER_CARD,
                        L10n.t("Hides the card at the top of Chats that asks you to get Messenger, once Messenger is installed. "
                                + "Without Messenger, the card stays.")));
            }
            if (build.contains(PatchFamily.CHAT_LIST)) {
                chats.addPreference(toggle(context, Settings.HIDE_CHAT_NOTES_TRAY,
                        L10n.t("Removes the row above your chats that shows friends' notes and who's active. Your own note tile may "
                                + "go too. Your chats stay.")));
                chats.addPreference(toggle(context, Settings.HIDE_CHAT_PROMOTIONS,
                        L10n.t("Hides the banners at the top of Chats, like the one asking you to turn on notifications.")));
            }
            if (build.contains(PatchFamily.MESSENGER_ICON)) {
                chats.addPreference(toggle(context, Settings.OPEN_MESSENGER_APP,
                        L10n.t("Tapping the Messenger icon at the top of Facebook opens the Messenger app instead of Chats. Without "
                                + "Messenger, Chats opens as before.")));
            }
            if (build.contains(PatchFamily.ORIGINAL_CHAT_MEDIA)) {
                chats.addPreference(toggle(context, Settings.ORIGINAL_CHAT_MEDIA,
                        L10n.t("Sends photos and videos in Facebook chats at full quality. Photos lose location and camera details. "
                                + "Big files and videos with a location tag are still shrunk.")));
            }
        }
    }

    /** Menu: the sections of Facebook's Menu that sell something. */
    static void menu(HushfacebookPreferenceFragment page, PreferenceScreen screen, Context context,
            Set<PatchFamily> build) {
        PreferenceCategory menu = category(screen, L10n.t("Menu"));
        SwitchPreference saved = toggle(context, Settings.SAVED_SHORTCUT, build.contains(PatchFamily.MENU_SETTINGS_ROW)
                ? L10n.t("Adds Saved to the menu you get by holding Facebook's icon, if there's room, and a Saved row in "
                        + "Settings and privacy. Existing shortcuts stay.")
                : L10n.t("Adds Saved to the menu you get by holding Facebook's icon, if there's room. Existing shortcuts stay."));
        saved.setOnPreferenceChangeListener((preference, value) -> {
            Settings.SAVED_SHORTCUT.save((Boolean) value);
            SavedShortcut.changed(context);
            return true;
        });
        menu.addPreference(saved);
        SwitchPreference watched = toggle(context, Settings.WATCH_HISTORY_SHORTCUT,
                build.contains(PatchFamily.MENU_SETTINGS_ROW)
                ? L10n.t("Adds Watch history, the videos and reels you've watched, to the menu you get by holding "
                        + "Facebook's icon, if there's room, and a Watch history row in Settings and privacy. Existing "
                        + "shortcuts stay.")
                : L10n.t("Adds Watch history, the videos and reels you've watched, to the menu you get by holding "
                        + "Facebook's icon, if there's room. Existing shortcuts stay."));
        watched.setOnPreferenceChangeListener((preference, value) -> {
            Settings.WATCH_HISTORY_SHORTCUT.save((Boolean) value);
            WatchHistoryShortcut.changed(context);
            return true;
        });
        menu.addPreference(watched);
        if (build.contains(PatchFamily.MENU_PROMOTIONS)) {
            menu.addPreference(toggle(context, Settings.HIDE_MENU_UPGRADES,
                    L10n.t("The Upgrades section and its offers leave Facebook's Menu. Settings, Help and support "
                            + "and the rest of the Menu stay.")));
            menu.addPreference(toggle(context, Settings.HIDE_MENU_ALSO_FROM_META,
                    L10n.t("The Also from Meta section leaves Facebook's Menu, with its links to Meta's other apps "
                            + "and its ads for Meta's devices. Your own shortcuts stay.")));
            menu.addPreference(toggle(context, Settings.HIDE_MENU_MUSE,
                    L10n.t("The Muse card, Meta's ad for its AI agent app, leaves the top of Facebook's Menu.")));
        }
        if (build.contains(PatchFamily.GAME_ADS)) {
            menu.addPreference(toggle(context, Settings.BLOCK_GAME_ADS,
                    L10n.t("Games you play in Facebook show no ads. A game that asks for one is told there's none, so rewarded "
                            + "ads give no reward.")));
            menu.addPreference(toggle(context, Settings.ANSWER_REWARDED_GAME_ADS,
                    L10n.t("With Block Instant Games ads on, a game's rewarded ad counts as watched. No ad plays and the game "
                            + "still gives its reward.")));
        }
        if (build.contains(PatchFamily.META_UPSELLS)) {
            PreferenceCategory upsells = category(screen, L10n.t("Meta's other products"));
            upsells.addPreference(toggle(context, Settings.HIDE_EDITS_UPSELLS,
                    L10n.t("Hides the Edits button in the Reels composer and the Edits pill under videos. You can still make "
                            + "reels in Facebook.")));
            upsells.addPreference(toggle(context, Settings.HIDE_THREADS_CROSS_POSTING,
                    L10n.t("Facebook stops asking whether to share your post to Threads too. Posting to Facebook works as "
                            + "before.")));
            upsells.addPreference(toggle(context, Settings.HIDE_THREADS_SHARE_BUTTON,
                    L10n.t("Removes the Threads button when you share something. All other ways to share stay, in the same "
                            + "order.")));
            upsells.addPreference(toggle(context, Settings.HIDE_META_VERIFIED_UPSELLS,
                    L10n.t("No Meta Verified offer after you post, and no Meta Verified label under the names on "
                            + "posts. Posting works as usual.")));
            upsells.addPreference(toggle(context, Settings.HIDE_AVATAR_UPSELLS,
                    L10n.t("Hides avatar sticker promotions in comments and elsewhere, and the prompt to make an avatar. "
                            + "Stickers still send.")));
            upsells.addPreference(toggle(context, Settings.HIDE_META_AI_IMAGINE,
                    L10n.t("Hides the Imagine me button on posts and the Imagine option when you write a post or create a "
                            + "story. Everything else works as before.")));
            upsells.addPreference(toggle(context, Settings.HIDE_META_AI_POST_BUTTONS,
                    L10n.t("Hides the other Meta AI buttons Facebook puts under posts. The post's next button shows instead, if "
                            + "it has one.")));
        }
    }

    /** Search. */
    static void search(HushfacebookPreferenceFragment page, PreferenceScreen screen, Context context,
            Set<PatchFamily> build) {
        if (build.contains(PatchFamily.META_AI_SEARCH) || build.contains(PatchFamily.SPONSORED_SEARCH)) {
            PreferenceCategory search = category(screen, L10n.t("Search"));
            if (build.contains(PatchFamily.META_AI_SEARCH)) {
                search.addPreference(toggle(context, Settings.HIDE_META_AI_IN_SEARCH,
                        L10n.t("Search no longer shows Meta AI answers or Ask Meta AI prompts, or sends your search to Meta AI. "
                                + "Results for people, groups, pages and posts stay.")));
            }
            if (build.contains(PatchFamily.SPONSORED_SEARCH)) {
                search.addPreference(toggle(context, Settings.HIDE_SPONSORED_SEARCH_RESULTS,
                        L10n.t("Hides ads between your search results. The results you searched for stay.")));
            }
        }
    }

    /** Marketplace. */
    static void marketplace(HushfacebookPreferenceFragment page, PreferenceScreen screen, Context context,
            Set<PatchFamily> build) {
        if (!build.contains(PatchFamily.SPONSORED_MARKETPLACE) && !build.contains(PatchFamily.SELLER_VIEW_PROFILE)) return;
        PreferenceCategory marketplace = category(screen, L10n.t("Marketplace"));
        if (build.contains(PatchFamily.SPONSORED_MARKETPLACE)) {
            marketplace.addPreference(toggle(context, Settings.HIDE_SPONSORED_MARKETPLACE_LISTINGS,
                    L10n.t("Hides ads and boosted listings in Marketplace's feed and search results. Regular listings stay.")));
        }
        if (build.contains(PatchFamily.SELLER_VIEW_PROFILE)) {
            // Read as a seller's page opens, so a change shows on the next one.
            marketplace.addPreference(toggle(context, Settings.SHOW_SELLER_VIEW_PROFILE,
                    L10n.t("Adds a View profile button to every seller's Marketplace page, opening their Facebook profile. "
                            + "Facebook only shows it to some accounts.")));
        }
    }

    /** The row that puts Facebook's chime in the phone's notification sounds. */
    static final String SAVE_NOTIFICATION_SOUND = "action_save_notification_sound";

    /**
     * Notifications: the kinds of notification that can be blocked, what always comes through, and
     * Facebook's chime for a category Android set to None. The section is in every build, since the
     * chime needs no patch.
     */
    static void notifications(HushfacebookPreferenceFragment page, PreferenceScreen screen, Context context,
            Set<PatchFamily> build) {
        PreferenceCategory notifications = category(screen, L10n.t("Notifications"));
        if (build.contains(PatchFamily.PROMO_NOTIFICATIONS)) {
            notifications.addPreference(toggle(context, Settings.BLOCK_TRENDING_VIDEO_NOTIFICATIONS,
                    L10n.t("Trending videos and the reels Facebook picked for you stop showing up in your "
                            + "notifications.")));
            notifications.addPreference(toggle(context, Settings.BLOCK_MEMORY_NOTIFICATIONS,
                    L10n.t("Facebook's \"On this day\" memories stop showing up in your notifications.")));
            notifications.addPreference(toggle(context, Settings.BLOCK_BIRTHDAY_NOTIFICATIONS,
                    L10n.t("No more reminders that it's a friend's birthday.")));
            notifications.addPreference(toggle(context, Settings.BLOCK_HIGHLIGHT_NOTIFICATIONS,
                    L10n.t("Digests of what's going on in groups, Pages and creators you follow stop. Comments, "
                            + "replies and mentions still come through.")));
            notifications.addPreference(toggle(context, Settings.BLOCK_PEOPLE_YOU_MAY_KNOW_NOTIFICATIONS,
                    L10n.t("Friend suggestions stop showing up in your notifications. Friend requests still come "
                            + "through.")));
            notifications.addPreference(toggle(context, Settings.BLOCK_NEARBY_NOTIFICATIONS,
                    L10n.t("Alerts about places near you and about the weather stop.")));
            notifications.addPreference(toggle(context, Settings.BLOCK_ACCOUNT_SETUP_NOTIFICATIONS,
                    L10n.t("Reminders to finish setting up a Facebook account stop. Login and security alerts "
                            + "still come through.")));
            notifications.addPreference(toggle(context, Settings.BLOCK_GROUP_ACTIVITY_NOTIFICATIONS,
                    L10n.t("Notifications about new activity in your groups stop. Comments, replies and mentions in "
                            + "groups still come through.")));
            notifications.addPreference(toggle(context, Settings.BLOCK_EVENT_NOTIFICATIONS,
                    L10n.t("Invites to events stop showing up in your notifications.")));
            notifications.addPreference(toggle(context, Settings.BLOCK_LIVE_VIDEO_NOTIFICATIONS,
                    L10n.t("Notifications that someone is live stop, including the ones you asked Facebook for.")));
            notifications.addPreference(toggle(context, Settings.BLOCK_REACTION_NOTIFICATIONS,
                    L10n.t("Likes and reactions to your posts and comments stop showing up in your notifications. "
                            + "Comments still come through.")));
            notifications.addPreference(toggle(context, Settings.NOTIFICATION_QUIET_HOURS,
                    L10n.t("The switches above block their kinds only between the two times below. The rest of the "
                            + "day those kinds come through.")));
            notifications.addPreference(quietHourRow(context, true));
            notifications.addPreference(quietHourRow(context, false));
            notifications.addPreference(info(context, L10n.t("What always comes through"),
                    L10n.t("Messages, friend requests, comments, mentions, calls, login alerts and any kind not listed above "
                            + "always arrive. Android's notification settings for Facebook can block more.")));
        }
        notifications.addPreference(notificationSoundRow(context));
    }

    /**
     * A tap copies Facebook's chime into the phone's notification sounds, off the main thread, and
     * a toast says how it went. Android's picker then lists it for a category that came up as
     * None, which no app can set back (#83).
     */
    static Preference notificationSoundRow(Context context) {
        SettingsRows.Row row = new SettingsRows.Row(context);
        row.setKey(SAVE_NOTIFICATION_SOUND);
        row.setPersistent(false);
        row.actsAtOnce = true;
        row.setTitle(L10n.t("Save Facebook's notification sound"));
        row.setSummary(L10n.t("Adds Facebook's chime to your phone's notification sounds, for categories set to None. Then pick it "
                + "in Android's notification settings for Facebook, under Sound."));
        Context app = context.getApplicationContext();
        row.setOnPreferenceClickListener(p -> {
            boolean accepted = Utils.runOnBackgroundThread(() ->
                    Utils.showToastLong(notificationSoundMessage(NotificationSound.save(app))));
            if (!accepted) Utils.showToastLong(notificationSoundMessage(NotificationSound.Outcome.FAILED, null));
            return true;
        });
        return row;
    }

    static String notificationSoundMessage(NotificationSound.Result result) {
        return notificationSoundMessage(result.outcome, result.name);
    }

    /** What the row's toast says for each way a save can go; the name is the file's, for the two that have one. */
    static String notificationSoundMessage(NotificationSound.Outcome outcome, @Nullable String name) {
        switch (outcome) {
            case SAVED:
                return L10n.f("Saved as %1$s in the Notifications folder. Pick it under Sound in Android's notification "
                        + "settings for Facebook.", L10n.isolate(name));
            case ALREADY_THERE:
                return L10n.f("%1$s is already in your notification sounds.", L10n.isolate(name));
            case NO_SOUND:
                return L10n.t("This build has no notification sound to save.");
            default:
                return L10n.t("Couldn't save the sound. Try again.");
        }
    }

    /** Links, in every build. */
    static void links(HushfacebookPreferenceFragment page, PreferenceScreen screen, Context context,
            Set<PatchFamily> build) {
        // In every build: Android checks Facebook's links against Meta's signing key, which no
        // re-signed build has, whatever its patches.
        PreferenceCategory links = category(screen, L10n.t("Links"));
        if (build.contains(PatchFamily.EXTERNAL_BROWSER)) {
            links.addPreference(toggle(context, Settings.OPEN_LINKS_EXTERNALLY,
                    L10n.t("Open web links in your browser. Facebook's own pages stay in the app.")));
        }
        if (build.contains(PatchFamily.SANITIZE_SHARING_LINKS)) {
            links.addPreference(toggle(context, Settings.SANITIZE_SHARING_LINKS,
                    L10n.t("Removes tracking tags like mibextid from links you share or copy. A facebook.com/share/ link is "
                            + "unique to each share, so Facebook can still trace it.")));
            links.addPreference(toggle(context, Settings.SHARE_POST_OWN_LINK,
                    L10n.t("Copy link and the share sheet's other link shares give the post's own facebook.com address "
                            + "instead of a facebook.com/share/ link made for that one share.")));
        }
        if (build.contains(PatchFamily.SHARE_SHEET_ITEMS)) {
            links.addPreference(shareItemsRow(page, context));
            links.addPreference(toggle(context, Settings.HIDE_SHARE_GROUP_BUTTONS,
                    L10n.t("Takes away Send to group when you pick two or more people, and the share sheet's own ways to start "
                            + "a new group, so nothing you share makes a group chat by accident. Send separately stays.")));
        }
        links.addPreference(page.supportedLinksRow(context));
        for (Preference holder : page.linkHolderRows(context)) links.addPreference(holder);
        links.addPreference(info(context, L10n.t("Choose which links open here"),
                L10n.t("Android can't verify a patched Facebook for Facebook links. Selecting the addresses yourself sends "
                        + "those links here. Your other link settings stay.")));
    }

    /** Share sheet items' row: the items it keeps out, picked from a list of checkboxes. */
    static Preference shareItemsRow(HushfacebookPreferenceFragment page, Context context) {
        SettingsRows.Row row = new SettingsRows.Row(context);
        row.setKey(SHARE_ITEMS_ROW);
        row.setTitle(L10n.t("Share sheet items to hide"));
        row.setPersistent(false);
        row.setSummary(shareItemsSummary(ShareSheetItems.hidden()));
        row.setOnPreferenceClickListener(p -> {
            showShareItems(page, context, row);
            return true;
        });
        return row;
    }

    /** The row's key, for the settings search. No setting is behind it. */
    static final String SHARE_ITEMS_ROW = "action_share_sheet_items";

    /** The row's summary: the items kept out, by the names the list gives them, or none. */
    static String shareItemsSummary(Set<String> hidden) {
        if (hidden.isEmpty()) return L10n.t("None. The share sheet shows everything Facebook offers.");
        List<String> names = new ArrayList<>();
        for (String type : hidden) names.add(ShareSheetItems.label(type));
        return L10n.f("Hidden: %s", L10n.join(names));
    }

    /** The list: every item it knows, ticked when it's kept out. Save keeps the ticks. */
    private static void showShareItems(HushfacebookPreferenceFragment page, Context context, Preference row) {
        List<String> types = ShareSheetItems.choices();
        Set<String> hidden = ShareSheetItems.hidden();
        String[] names = new String[types.size()];
        boolean[] ticked = new boolean[types.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = ShareSheetItems.label(types.get(i));
            ticked[i] = hidden.contains(types.get(i));
        }
        // Through the page, so it closes with the page's view and takes the screen's colors.
        page.show(new AlertDialog.Builder(context)
                .setTitle(L10n.t("Hide from the share sheet"))
                .setMultiChoiceItems(names, ticked, (shown, which, isTicked) -> ticked[which] = isTicked)
                .setPositiveButton(L10n.t("Save"), (shown, which) -> {
                    List<String> picked = new ArrayList<>();
                    for (int i = 0; i < ticked.length; i++) {
                        if (ticked[i]) picked.add(types.get(i));
                    }
                    ShareSheetItems.hide(picked);
                    row.setSummary(shareItemsSummary(ShareSheetItems.hidden()));
                })
                .setNegativeButton(L10n.t("Cancel"), null));
    }

    /**
     * Privacy, in every build for Lock Facebook: who can open Facebook on this phone, what Facebook
     * sends home in the background, and what it shows others while you write and read. The map of
     * where ads and tracking are blocked ends it.
     */
    static void privacy(HushfacebookPreferenceFragment page, PreferenceScreen screen, Context context,
            Set<PatchFamily> build) {
        PreferenceCategory privacy = category(screen, L10n.t("Privacy"));
        // The settings entry's own, so it's in every build. A phone without a screen lock has nothing to ask with.
        SwitchPreference lock = toggle(context, Settings.APP_LOCK,
                L10n.t("Facebook asks for your fingerprint, face or screen lock at startup and when you return after the "
                        + "time below. Your phone needs a screen lock."));
        lock.setOnPreferenceChangeListener((preference, value) -> {
            if (Boolean.TRUE.equals(value) && !AppLock.canLock(context)) {
                Utils.showToastLong(L10n.t("Set a screen lock in your phone's settings first, so Facebook has "
                        + "something to ask for."));
                return false;
            }
            return true;
        });
        privacy.addPreference(lock);
        privacy.addPreference(lockAfterRow(context));
        if (build.contains(PatchFamily.ANALYTICS_UPLOADS)) {
            // XAnalytics resumes its uploader once, as Facebook starts.
            privacy.addPreference(toggle(context, Settings.HOLD_ANALYTICS_UPLOADS,
                    L10n.t("Stops Facebook sending usage statistics in the background and running its on-phone learning tasks. "
                            + "Restart Facebook to see the change.")));
            // Facebook cancels the work it scheduled the next time it asks, which a restart brings about.
            privacy.addPreference(toggle(context, Settings.HOLD_CAMERA_ROLL_PROCESSING,
                    L10n.t("Stops the camera roll processing behind sharing suggestions, which goes through your photos "
                            + "and videos and uploads photos and details about them, and stops the report of how many you "
                            + "have. Restart Facebook to see the change.")));
        }
        if (build.contains(PatchFamily.EXTERNAL_BROWSER)) {
            privacy.addPreference(toggle(context, Settings.HOLD_LINK_HISTORY,
                    L10n.t("Links that open in Facebook's own browser aren't recorded for your link history or sent to "
                            + "Facebook as pages you visited. Applies to the next link you open.")));
        }
        if (build.contains(PatchFamily.SCREENSHOT_DETECTION)) {
            privacy.addPreference(toggle(context, Settings.BLOCK_SCREENSHOT_DETECTION,
                    L10n.t("Facebook can't tell when you take a screenshot or record your screen, so it can't react.")));
        }
        if (build.contains(PatchFamily.SCREENSHOTS)) {
            privacy.addPreference(toggle(context, Settings.ALLOW_SCREENSHOTS,
                    L10n.t("Lets you take screenshots and screen recordings on pages where Facebook blocks them. Reopen a page "
                            + "that's already open.")));
        }
        if (build.contains(PatchFamily.TYPING_INDICATOR)) {
            privacy.addPreference(toggle(context, Settings.HIDE_CHAT_TYPING,
                    L10n.t("People you chat with in Facebook's own chats can't see when you're typing. Messages send as usual.")));
            privacy.addPreference(toggle(context, Settings.HIDE_COMMENT_TYPING,
                    L10n.t("People looking at a post don't see that you're writing a comment.")));
        }
        if (build.contains(PatchFamily.READ_RECEIPTS)) {
            privacy.addPreference(toggle(context, Settings.HIDE_READ_RECEIPTS,
                    L10n.t("People you chat with in Facebook's own chats can't see that you've read their messages. The chat "
                            + "can stay unread on this phone.")));
        }
        AdsMap.add(page, privacy, context, build);
    }
}
