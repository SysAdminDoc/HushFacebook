/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.morphe.extension.facebook.settings;

import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.breakAfterRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.breakLengthRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.category;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.commentOrderRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.feedsSubtabRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.homeFeedRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.info;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.pauseLengthRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.reactionCeilingRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.seenKeepRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.startTabRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.subheading;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.toggle;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.translateToRow;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import app.morphe.extension.facebook.comments.CommentSheetOptions;
import app.morphe.extension.facebook.feed.FeedTypeNames;
import app.morphe.extension.facebook.feed.HiddenTally;
import app.morphe.extension.facebook.feed.SeenPosts;
import app.morphe.extension.facebook.feed.TranslateLanguages;
import app.morphe.extension.facebook.misc.BreakTimer;
import app.morphe.extension.facebook.navigation.MarketplaceOnly;
import app.morphe.extension.facebook.profile.LocalProfileCounts;
import app.morphe.extension.facebook.settings.SettingsRows.Row;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;

/**
 * The category pages for the feed: Opening Facebook, News feed, Stories, and Comments and writing. Each method adds its section to the page
 * {@link HushfacebookPreferenceFragment#initialize} builds, and leaves it out when the build has none of
 * its patches, but for the sections every build has.
 */
@SuppressWarnings("deprecation")
final class FeedPages {
    private FeedPages() {
    }

    /**
     * Opening Facebook: the opening pause, breaks, Marketplace only, the tab Facebook opens on, and
     * the feed Home loads. Every build has the section, since the pause and breaks are the settings
     * entry's own.
     */
    static void opening(HushfacebookPreferenceFragment page, PreferenceScreen screen, Context context,
            Set<PatchFamily> build) {
        // First: it's what happens before anything the other rows change comes on screen.
        PreferenceCategory opening = category(screen, L10n.t("Opening Facebook"));
        // The length follows the switch and is greyed out until it's on, as the seen posts time is.
        SwitchPreference pause = toggle(context, Settings.OPENING_PAUSE,
                L10n.t("Opening Facebook from its icon shows a short countdown first, so you can change your mind. "
                        + "Links, notifications and shares open it straight away, and so does coming back within a minute."));
        Preference pauseLength = pauseLengthRow(context);
        pauseLength.setEnabled(Settings.OPENING_PAUSE.savedValue());
        pause.setOnPreferenceChangeListener((preference, value) -> {
            pauseLength.setEnabled(Boolean.TRUE.equals(value));
            return true;
        });
        opening.addPreference(pause);
        opening.addPreference(pauseLength);
        // Take breaks (#248), the settings entry's own too, with its two lists following its switch.
        SwitchPreference breaks = toggle(context, Settings.TAKE_BREAKS,
                L10n.t("After the time you pick on Home, Feeds, Video and Reels, they're covered for a break. "
                        + "Chats, your profile and the Menu still work, and time away as long as a break counts as one."));
        Preference breakAfter = breakAfterRow(context);
        Preference breakLength = breakLengthRow(context);
        boolean breaksOn = Settings.TAKE_BREAKS.savedValue();
        breakAfter.setEnabled(breaksOn);
        breakLength.setEnabled(breaksOn);
        breaks.setOnPreferenceChangeListener((preference, value) -> {
            breakAfter.setEnabled(Boolean.TRUE.equals(value));
            breakLength.setEnabled(Boolean.TRUE.equals(value));
            // The screen under the settings stays resumed, so nothing else would start or end the count.
            BreakTimer.switched();
            return true;
        });
        opening.addPreference(breaks);
        opening.addPreference(breakAfter);
        opening.addPreference(breakLength);
        if (build.contains(PatchFamily.MARKETPLACE_ONLY)) {
            // Facebook builds the tab bar once, and the hook is asked then and not again.
            opening.addPreference(toggle(context, Settings.MARKETPLACE_ONLY, ""));
            opening.addPreference(toggle(context, Settings.MARKETPLACE_QUIET_NOTIFICATIONS,
                    L10n.t("While Marketplace only is on, silences video suggestions, memories, birthdays and friend "
                            + "suggestions. Messages and trading updates still arrive.")));
            opening.addPreference(toggle(context, Settings.MARKETPLACE_SKIP_FEED_PREFETCH,
                    L10n.t("While Marketplace only is on, loads less of the feed in the background. Some loading still happens "
                            + "at startup.")));
            // After Marketplace only's own two rows, so they sit under the switch they follow.
            opening.addPreference(toggle(context, Settings.EVENTS_ONLY, ""));
            Row regular = new Row(context);
            regular.actsAtOnce = true;
            regular.setKey("action_regular_facebook");
            regular.setTitle(L10n.t("Return to regular Facebook"));
            regular.setSummary(L10n.t("Restore the normal tabs at the next restart. Your other settings stay saved."));
            regular.setOnPreferenceClickListener(ignored -> {
                if (Settings.MARKETPLACE_ONLY.save(false) && Settings.EVENTS_ONLY.save(false)) {
                    page.refreshSwitches();
                    Utils.showToastLong(MarketplaceOnly.state() == MarketplaceOnly.State.RESTART_NEEDED
                            ? L10n.t("Marketplace only and Events only are off. Restart Facebook to restore its normal tabs.")
                            : L10n.t("Marketplace only and Events only are off."));
                } else {
                    Utils.showToastLong(L10n.t("Couldn't save the change. Try again."));
                }
                return true;
            });
            opening.addPreference(regular);
        }
        if (build.contains(PatchFamily.START_TAB)) {
            opening.addPreference(toggle(context, Settings.OPEN_ON_CHOSEN_TAB,
                    L10n.t("Choose where Facebook opens from its icon. Notifications and links still open their destination.")));
            opening.addPreference(startTabRow(context));
            opening.addPreference(feedsSubtabRow(context));
        }
        if (build.contains(PatchFamily.FOLLOWING_HOME)) {
            // Home asks for its feed each time it loads one, so no restart is needed.
            SwitchPreference following = toggle(context, Settings.FOLLOWING_FEED_HOME,
                    L10n.t("Home shows the newest posts from friends, groups and Pages you follow, like All in the Feeds tab, "
                            + "or the filter you pick below. The Feeds tab's filters don't change."));
            // The feed choice follows the switch and is greyed out until it's on, as the seen posts time is.
            Preference homeFeed = homeFeedRow(context);
            homeFeed.setEnabled(Settings.FOLLOWING_FEED_HOME.savedValue());
            following.setOnPreferenceChangeListener((preference, value) -> {
                homeFeed.setEnabled(Boolean.TRUE.equals(value));
                return true;
            });
            opening.addPreference(following);
            opening.addPreference(homeFeed);
        }
    }

    /** News feed: the ads, suggestions, prompts and words the feed can leave out. */
    static void newsFeed(HushfacebookPreferenceFragment page, PreferenceScreen screen, Context context,
            Set<PatchFamily> build) {
        if (build.contains(PatchFamily.SPONSORED_POSTS) || build.contains(PatchFamily.SUGGESTED_POSTS)
                || build.contains(PatchFamily.STORIES_TRAY) || build.contains(PatchFamily.FEED_REELS)
                || build.contains(PatchFamily.RETURN_REFRESH)
                || build.contains(PatchFamily.AI_DETECTED_POSTS)
                || build.contains(PatchFamily.SPONSORED_PROFILE_POSTS)
                || build.contains(PatchFamily.PROFILE_NOTES)
                || build.contains(PatchFamily.LOCAL_PROFILE_COUNTS)
                || build.contains(PatchFamily.POST_ACTION_BUTTONS)
                || build.contains(PatchFamily.AFFILIATE_LINKS)
                || build.contains(PatchFamily.POST_WORDS)
                || build.contains(PatchFamily.POST_PROMPTS)
                || build.contains(PatchFamily.SEEN_POSTS)
                || build.contains(PatchFamily.META_AI_QUESTIONS)
                || build.contains(PatchFamily.POST_DATES)
                || build.contains(PatchFamily.AUTO_TRANSLATION)
                || build.contains(PatchFamily.FEEDS_HEADER)) {
            PreferenceCategory feed = category(screen, L10n.t("News feed"));
            // The families whose rules run in the feed guard, which keeps the tally.
            if (build.contains(PatchFamily.SPONSORED_POSTS) || build.contains(PatchFamily.SUGGESTED_POSTS)
                    || build.contains(PatchFamily.STORIES_TRAY) || build.contains(PatchFamily.FEED_REELS)
                    || build.contains(PatchFamily.AI_DETECTED_POSTS) || build.contains(PatchFamily.POST_WORDS)
                    || build.contains(PatchFamily.SEEN_POSTS)) {
                // Read as the page is built, so leaving settings and coming back shows the count now.
                feed.addPreference(info(context, L10n.t("Hidden since Facebook started"), HiddenTally.summary()));
            }
            subheading(feed, L10n.t("Ads and suggestions"));
            if (build.contains(PatchFamily.SPONSORED_POSTS)) {
                feed.addPreference(toggle(context, Settings.HIDE_SPONSORED_POSTS,
                        L10n.t("Hides paid ads in the feed. They're removed before they appear, so no gap is left.")));
                feed.addPreference(toggle(context, Settings.HIDE_PROMOTED_POSTS,
                        L10n.t("Hides posts Facebook labels as promotions rather than ads.")));
            }
            // Profiles have no section of their own; their ads sit with the feed's.
            if (build.contains(PatchFamily.SPONSORED_PROFILE_POSTS)) {
                feed.addPreference(toggle(context, Settings.HIDE_SPONSORED_PROFILE_POSTS,
                        L10n.t("Hides ads between posts on someone's profile or a Page. Their own posts stay.")));
            }
            // One switch covers the cards on reels and in the comment sheet too.
            if (build.contains(PatchFamily.AFFILIATE_LINKS)) {
                feed.addPreference(toggle(context, Settings.HIDE_AFFILIATE_LINKS,
                        L10n.t("Hides product cards from shopping links that creators add to posts, reels and comments.")));
                feed.addPreference(toggle(context, Settings.HIDE_COMMISSION_LABEL,
                        L10n.t("Hides the \"Commission eligible\" label under the creator's name on reels.")));
            }
            if (build.contains(PatchFamily.SUGGESTED_POSTS)) {
                feed.addPreference(toggle(context, Settings.HIDE_SUGGESTED_POSTS,
                        L10n.t("Hides \"Pages you may like\" cards, cards promoting Facebook's own features, and surveys in the "
                                + "feed.")));
                feed.addPreference(toggle(context, Settings.HIDE_SUGGESTED_FOR_YOU,
                        L10n.t("Hides posts Facebook adds from people and Pages you don't follow and groups you haven't joined.")));
                // The Stories tray's cards are filtered by Hide suggested stories' hook, so the
                // switch reaches them only when that patch is in too.
                feed.addPreference(toggle(context, Settings.HIDE_PEOPLE_YOU_MAY_KNOW,
                        build.contains(PatchFamily.SUGGESTED_STORIES)
                                ? L10n.t("Hides friend suggestions between posts, on your own profile, and as cards with an Add button in the "
                                        + "Stories tray.")
                                : L10n.t("Hides friend suggestions between posts and on your own profile.")));
                feed.addPreference(toggle(context, Settings.HIDE_SUGGESTED_GROUPS,
                        L10n.t("Hides the row of groups to join between posts. Posts from groups you're in stay.")));
                feed.addPreference(toggle(context, Settings.HIDE_GROUP_POSTS_NOT_JOINED,
                        L10n.t("Hides posts from groups you haven't joined, like a friend's post in a public group, and a "
                                + "group post a friend commented on or shared. Posts from your groups stay.")));
                feed.addPreference(toggle(context, Settings.HIDE_STORIES_YOU_MIGHT_LIKE,
                        L10n.t("Hides the row of Stories from people you aren't connected to, between posts. Your friends' Stories "
                                + "and the Stories tray stay.")));
                feed.addPreference(toggle(context, Settings.HIDE_FEED_MEMORIES,
                        L10n.t("Hides Memories between posts, like \"On this day\" and friendship anniversaries. Your Memories page "
                                + "stays.")));
                feed.addPreference(toggle(context, Settings.HIDE_FEED_FRIEND_REQUESTS,
                        L10n.t("Hides friend requests shown between posts. Your requests stay under Friends.")));
                feed.addPreference(toggle(context, Settings.HIDE_FRIENDS_LOCATIONS,
                        L10n.t("Hides the card between posts that shows where your friends are.")));
                // Asked as a post loads, so a change shows from the next post you open.
                feed.addPreference(toggle(context, Settings.HIDE_NOTIFICATION_POST_FOOTER,
                        L10n.t("A post you open from a notification ends without the \"More from your friends\" list "
                                + "Facebook adds under it, which can carry ads.")));
                // Asked as each post is drawn, so a change shows on the next post that scrolls in.
                feed.addPreference(toggle(context, Settings.HIDE_RELATED_POSTS,
                        L10n.t("Hides the \"Related posts\" list Facebook adds under some posts, with its titles written "
                                + "by Meta AI. The post and its comments stay.")));
                // The end of the feed is built as the feed runs out, so a change shows the next time it does.
                feed.addPreference(toggle(context, Settings.HIDE_FIND_FRIENDS_CARD,
                        L10n.t("Hides the \"Want to see more posts from friends?\" card with its Find friends button where "
                                + "your friends' posts run out.")));
            }
            subheading(feed, L10n.t("Feed layout"));
            if (build.contains(PatchFamily.STORIES_TRAY)) {
                // Facebook builds the feed's adapters once, when the feed is set up, and the tray is
                // one of them. The hook is asked then and not again, so a change waits for a restart.
                feed.addPreference(toggle(context, Settings.HIDE_TOP_STORIES_TRAY,
                        L10n.t("Hides the row of stories at the top of the feed, including Create story.") + " "
                                + L10n.t("A change shows the next time you pull down to refresh.")));
                feed.addPreference(toggle(context, Settings.HIDE_STORIES_BETWEEN_POSTS,
                        L10n.t("Hides Stories shown between posts, from the next time the feed loads. The Stories tray at the top "
                                + "has its own switch.")));
                feed.addPreference(toggle(context, Settings.HIDE_HOME_COMPOSER,
                        L10n.t("Hides the \"What's on your mind?\" row at the top of Home. The create button in the top bar still "
                                + "starts a post.") + " "
                                + L10n.t("A change shows the next time you pull down to refresh.")));
            }
            if (build.contains(PatchFamily.FEED_REELS)) {
                feed.addPreference(toggle(context, Settings.HIDE_FEED_REELS,
                        L10n.t("Hides rows of reels between posts, and the reels Facebook adds where your feed ends.")));
            }
            if (build.contains(PatchFamily.POST_PROMPTS)) {
                feed.addPreference(toggle(context, Settings.HIDE_POST_PROMPTS,
                        L10n.t("Hides the strip on some posts, like \"Are you interested in this post?\" or who recently commented, "
                                + "plus follow and chat suggestions there. The post stays.")));
                feed.addPreference(toggle(context, Settings.HIDE_POST_FOLLOW_LINK,
                        L10n.t("Hides the \"Follow\" link next to the name on posts from Pages and people you don't follow. "
                                + "Tapping the name still opens the profile. A change shows on the posts drawn after it.")));
                feed.addPreference(toggle(context, Settings.HIDE_POST_COUNTS,
                        L10n.t("Takes the numbers off the Like, Comment and Share buttons under posts, so each shows "
                                + "just its word. The buttons stay where they are. A change shows on the posts drawn after it.")));
                feed.addPreference(toggle(context, Settings.HIDE_POST_REACTIONS,
                        L10n.t("Hides the stacked reaction icons and the reactions count under posts. The space stays, "
                                + "so a tap where they were still opens the list of reactions. To take the number off the Like "
                                + "button too, turn on Hide counts on posts.")));
            }
            if (build.contains(PatchFamily.META_AI_QUESTIONS)) {
                feed.addPreference(toggle(context, Settings.HIDE_META_AI_QUESTIONS,
                        L10n.t("Hides the row of Meta AI questions under some posts. The post, its link card and its buttons stay.")));
            }
            // Each post's row reads them as it's built, so a change shows on the posts drawn after it.
            if (build.contains(PatchFamily.POST_ACTION_BUTTONS)) {
                feed.addPreference(toggle(context, Settings.POST_SAVE_BUTTON,
                        L10n.t("Adds Facebook's own Save button next to Like, Comment and Share under posts. A saved post "
                                + "shows its bookmark filled in.")));
                if (build.contains(PatchFamily.VIDEO_DOWNLOAD) || build.contains(PatchFamily.PHOTO_DOWNLOAD)) {
                    feed.addPreference(toggle(context, Settings.POST_DOWNLOAD_BUTTON,
                            L10n.t("Adds a Download button under posts with a video or photos. It saves the video, the "
                                    + "photo, or every photo of an album, using the Downloads settings.")));
                }
                String hides = L10n.t("Takes this button out of the row under posts in the feed. The other buttons stay, and "
                        + "a post with all of its buttons hidden shows no row.");
                feed.addPreference(toggle(context, Settings.HIDE_POST_LIKE_BUTTON, hides));
                feed.addPreference(toggle(context, Settings.HIDE_POST_COMMENT_BUTTON, hides));
                feed.addPreference(toggle(context, Settings.HIDE_POST_SHARE_BUTTON, hides));
                feed.addPreference(toggle(context, Settings.HIDE_POST_SEND_BUTTON, hides));
            }
            if (build.contains(PatchFamily.POST_DATES)) {
                feed.addPreference(toggle(context, Settings.KEEP_POST_DATES,
                        L10n.t("Shows the post's date under the poster's name instead of Facebook's rotating details, which can go "
                                + "blank on some phones.")));
            }
            if (build.contains(PatchFamily.FULL_POST_TEXT)) {
                feed.addPreference(toggle(context, Settings.SHOW_FULL_POST_TEXT,
                        L10n.t("Posts in the feed show all of their text, so there's no See more to tap.")));
            }
            if (build.contains(PatchFamily.TRANSLATE_POSTS)) {
                feed.addPreference(toggle(context, Settings.TRANSLATE_POST_LINK,
                        L10n.t("Posts in another language get a Translate link under their text. A tap sends that post's "
                                + "text to Google Translate.")));
                feed.addPreference(toggle(context, Settings.TRANSLATE_POSTS_BY_THEMSELVES,
                        L10n.t("Posts in another language show translated, with See original under them. Each post's "
                                + "text goes to Google Translate as it's drawn.")));
                feed.addPreference(translateToRow(context));
                feed.addPreference(leaveAloneRow(page, context));
            }
            if (build.contains(PatchFamily.REEL_GESTURES)) {
                // Read on each long press, so a change shows on the next one.
                feed.addPreference(toggle(context, Settings.SELECT_POST_TEXT,
                        L10n.t("Holding a post's text still copies all of it, and also opens the text in a box where you "
                                + "can select just the part you want and copy that.")));
            }
            if (build.contains(PatchFamily.WHO_SHARED)) {
                feed.addPreference(toggle(context, Settings.SEE_WHO_SHARED,
                        L10n.t("Adds See who shared to a post's menu. It opens Facebook's list of the people who shared it.")));
            }
            if (build.contains(PatchFamily.AUTO_TRANSLATION)) {
                // Each post and reel reads the answer as it's drawn, so a change shows on the next ones.
                feed.addPreference(toggle(context, Settings.TURN_OFF_AUTO_TRANSLATION,
                        L10n.t("Posts and reel captions stay in the language they were written in. Facebook's "
                                + "See translation link stays under them.")));
            }
            if (build.contains(PatchFamily.FEEDS_HEADER)) {
                // Facebook settles the Feeds tab's header as the tab is built, so a change waits for a restart.
                feed.addPreference(toggle(context, Settings.HIDE_FEEDS_HEADER,
                        L10n.t("Shows the Feeds tab straight on its posts, without the title row or the filters. Restart Facebook "
                                + "to see the change.")));
            }
            // Read each time Facebook lays a profile out, so a change shows on the next profile opened.
            if (build.contains(PatchFamily.PROFILE_NOTES)) {
                feed.addPreference(toggle(context, Settings.HIDE_PROFILE_NOTES,
                        L10n.t("Hides the note bubble that sits on profile pictures. Applies the next time you open a "
                                + "profile.")));
            }
            // Read each time Facebook lays a profile out, so a change shows on the next profile opened.
            if (build.contains(PatchFamily.LOCAL_PROFILE_COUNTS)) {
                feed.addPreference(toggle(context, Settings.LOCAL_PROFILE_COUNTS,
                        L10n.t("Shows the friends, followers and following numbers you type below on your own profile, "
                                + "on this phone only. Other profiles and Facebook's real numbers aren't touched. "
                                + "Applies the next time you open your profile.")));
                feed.addPreference(HushfacebookPreferenceFragment.countRow(context, LocalProfileCounts.Count.FRIENDS));
                feed.addPreference(HushfacebookPreferenceFragment.countRow(context, LocalProfileCounts.Count.FOLLOWERS));
                feed.addPreference(HushfacebookPreferenceFragment.countRow(context, LocalProfileCounts.Count.FOLLOWING));
            }
            if (build.contains(PatchFamily.RETURN_REFRESH)) {
                feed.addPreference(toggle(context, Settings.BLOCK_RETURN_REFRESH,
                        L10n.t("Returning to Facebook within ten minutes, or switching back to Home, keeps your place. "
                                + "Pull to refresh still works.")));
                feed.addPreference(toggle(context, Settings.RETURN_REFRESH_NO_LIMIT,
                        L10n.t("With the switch above on, your place stays however long you're away. Pull to refresh and a fresh start still load new posts."))
                        .needs(Settings.BLOCK_RETURN_REFRESH));
                feed.addPreference(toggle(context, Settings.BACK_KEEPS_HOME,
                        L10n.t("Pressing Back on a scrolled Home no longer jumps to the top and reloads the feed. "
                                + "Press Back again within two seconds to do that.")));
                feed.addPreference(toggle(context, Settings.HIDE_BACK_REMINDER,
                        L10n.t("Back keeps your place without saying \"Press Back again to go to the top\"."))
                        .needs(Settings.BACK_KEEPS_HOME));
            }
            subheading(feed, L10n.t("Content filters"));
            if (build.contains(PatchFamily.SEEN_POSTS)) {
                seenPosts(feed, context);
            }
            if (build.contains(PatchFamily.AI_DETECTED_POSTS)) {
                feed.addPreference(toggle(context, Settings.HIDE_AI_DETECTED_POSTS,
                        L10n.t("Hides posts that Facebook's own detection marks as made with AI. Posts only their creator labeled "
                                + "stay. Off by default, since it hasn't been tried on a real feed yet.")));
                feed.addPreference(toggle(context, Settings.HIDE_AI_LABELLED_POSTS,
                        L10n.t("Also hides posts their creator labeled as made with AI, not just the ones Facebook detects itself. "
                                + "Off by default, since it hasn't been tried on a real feed yet.")));
                feed.addPreference(toggle(context, Settings.HIDE_META_AI_FEED_UNITS,
                        L10n.t("Hides Meta AI cards between posts and cards promoting the Vibes app. People's posts stay, whatever "
                                + "they say about AI.")));
                feed.addPreference(toggle(context, Settings.HIDE_AI_CHARACTER_POSTS,
                        L10n.t("Hides posts featuring Meta's AI characters, the chatbots people make with Meta AI "
                                + "Studio. Off by default, since it hasn't been tried on a real feed yet.")));
            }
            if (build.contains(PatchFamily.POST_WORDS)) {
                feed.addPreference(toggle(context, Settings.HIDE_POSTS_WITH_WORDS,
                        L10n.t("Hides posts containing a word or phrase from your list below. Words that keep a post win over it. "
                                + "Your words stay on this phone unless you export them.")));
                feed.addPreference(page.wordsRow(context, Settings.HIDDEN_WORDS, true));
                feed.addPreference(page.wordsRow(context, Settings.KEPT_WORDS, false));
                feed.addPreference(toggle(context, Settings.POST_WORDS_WHOLE_WORDS,
                        L10n.t("Matches whole words only, in both lists. For example, \"hat\" matches \"hat!\" but not \"what\" or \"hats\".")));
                feed.addPreference(toggle(context, Settings.HIDE_POSTS_FROM_SOURCES,
                        L10n.t("Hides posts by people or Pages on your list below, or linking to sites on it, including shares. "
                                + "Your list stays on this phone unless you export it.")));
                feed.addPreference(page.sourcesRow(context));
                feed.addPreference(toggle(context, Settings.HIDE_PHOTO_POSTS,
                        L10n.t("Hides posts with a photo or album, including shares.")));
                feed.addPreference(toggle(context, Settings.HIDE_VIDEO_POSTS,
                        L10n.t("Hides posts with a video, including shares. Reels in the feed have their own switch.")));
                feed.addPreference(toggle(context, Settings.HIDE_LINK_POSTS,
                        L10n.t("Hides posts that share a link to a website, with its preview card.")));
                feed.addPreference(toggle(context, Settings.HIDE_BACKGROUND_POSTS,
                        L10n.t("Hides short posts shown as big text on a colored background.")));
                feed.addPreference(toggle(context, Settings.HIDE_REPOSTS,
                        L10n.t("Hides posts that share another post, with or without a caption. Original posts stay. Off "
                                + "by default, since it hasn't been tried on a real feed yet.")));
                feed.addPreference(toggle(context, Settings.HIDE_PAGE_POSTS,
                        L10n.t("Hides posts written by Pages, the ones you follow too. Posts from friends and groups "
                                + "stay, and so does a friend's share of a Page's post.")));
                feed.addPreference(reactionCeilingRow(context));
                // For people who read Debug reports, where the type names come from, so it shows
                // with Debug logging on, and from then on while anything's picked.
                if (BaseSettings.DEBUG.savedValue() || !FeedTypeNames.hidden().isEmpty()) {
                    feed.addPreference(feedTypesRow(page, context));
                }
            }
        }
    }

    /** The row's key, for the settings search. No setting is behind it. */
    static final String FEED_TYPES_ROW = "action_feed_types";

    /** The row's key, for the settings search. The setting it writes is a list of codes. */
    static final String LEAVE_ALONE_ROW = "action_translate_leave_alone";

    /** Feed types to hide: units hidden by the type name Debug logging records, picked from a list. */
    /** The languages Translate posts leaves as they are, picked from a list. */
    static Preference leaveAloneRow(HushfacebookPreferenceFragment page, Context context) {
        Row row = new Row(context);
        row.setKey(LEAVE_ALONE_ROW);
        row.setTitle(L10n.t("Languages to leave alone"));
        row.setPersistent(false);
        row.setSummary(leaveAloneSummary());
        row.setOnPreferenceClickListener(p -> {
            showLeaveAlone(page, context, row);
            return true;
        });
        return row;
    }

    private static void showLeaveAlone(HushfacebookPreferenceFragment page, Context context, Row row) {
        List<String> codes = TranslateLanguages.codes();
        List<String> picked = Arrays.asList(Settings.TRANSLATE_LEAVE_ALONE.get().split(","));
        CharSequence[] names = new CharSequence[codes.size()];
        boolean[] ticked = new boolean[codes.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = TranslateLanguages.name(codes.get(i));
            ticked[i] = picked.contains(codes.get(i));
        }
        AlertDialog list = page.show(new AlertDialog.Builder(context)
                .setTitle(L10n.t("Languages to leave alone"))
                .setMultiChoiceItems(names, ticked, (shown, which, isTicked) -> ticked[which] = isTicked)
                .setPositiveButton(L10n.t("Save"), null)
                .setNegativeButton(L10n.t("Cancel"), null));
        if (list == null) return;
        list.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(button -> {
            List<String> kept = new ArrayList<>();
            for (int i = 0; i < ticked.length; i++) {
                if (ticked[i]) kept.add(codes.get(i));
            }
            Settings.TRANSLATE_LEAVE_ALONE.save(TextUtils.join(",", kept));
            row.setSummary(leaveAloneSummary());
            list.dismiss();
        });
    }

    private static String leaveAloneSummary() {
        List<String> names = new ArrayList<>();
        for (String code : Settings.TRANSLATE_LEAVE_ALONE.get().split(",")) {
            if (!code.isEmpty()) names.add(TranslateLanguages.name(code));
        }
        if (names.isEmpty()) return L10n.t("None picked. Posts in every language can be translated.");
        return L10n.f("Left alone: %s", L10n.join(names));
    }

    static Preference feedTypesRow(HushfacebookPreferenceFragment page, Context context) {
        Row row = new Row(context);
        row.setKey(FEED_TYPES_ROW);
        row.setTitle(L10n.t("Feed types to hide"));
        row.setPersistent(false);
        row.setSummary(feedTypesSummary(FeedTypeNames.hidden()));
        row.setOnPreferenceClickListener(p -> {
            showFeedTypes(page, context, row);
            return true;
        });
        return row;
    }

    /** The row's summary: the types picked, or none, and that a type Home needs is the picker's risk. */
    static String feedTypesSummary(Set<String> hidden) {
        String warning = L10n.t("Picking a type Home needs can leave it empty.");
        if (hidden.isEmpty()) {
            return L10n.t("None picked. Hides posts and cards by Facebook's own type name, as Debug logging records it.") + " " + warning;
        }
        return L10n.f("Hidden: %s", L10n.join(new ArrayList<>(hidden))) + " " + warning;
    }

    /**
     * The list: every type seen since Facebook started and every one picked, ticked when it's
     * hidden. Save keeps the ticks or, when they don't fit or the phone doesn't keep the write,
     * keeps the list open with its ticks.
     */
    private static void showFeedTypes(HushfacebookPreferenceFragment page, Context context, Preference row) {
        List<String> types = FeedTypeNames.choices();
        if (types.isEmpty()) {
            page.show(new AlertDialog.Builder(context)
                    .setTitle(L10n.t("Feed types to hide"))
                    .setMessage(L10n.t("No types seen yet. Scroll Home and come back."))
                    .setPositiveButton(L10n.t("OK"), null));
            return;
        }
        Set<String> hidden = FeedTypeNames.hidden();
        String[] names = types.toArray(new String[0]);
        boolean[] ticked = new boolean[names.length];
        for (int i = 0; i < names.length; i++) ticked[i] = hidden.contains(names[i]);
        AlertDialog list = page.show(new AlertDialog.Builder(context)
                .setTitle(L10n.t("Feed types to hide"))
                .setMultiChoiceItems(names, ticked, (shown, which, isTicked) -> ticked[which] = isTicked)
                .setPositiveButton(L10n.t("Save"), null)
                .setNegativeButton(L10n.t("Cancel"), null));
        if (list == null) return;
        list.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(button -> {
            List<String> picked = new ArrayList<>();
            for (int i = 0; i < ticked.length; i++) {
                if (ticked[i]) picked.add(names[i]);
            }
            FeedTypeNames.Hidden saved = FeedTypeNames.hide(picked);
            if (saved == FeedTypeNames.Hidden.NO_ROOM) {
                Utils.showToastLong(L10n.t("This list needs more room than it has. Shorten it, then save again."));
                return;
            }
            if (saved == FeedTypeNames.Hidden.NOT_STORED) {
                Utils.showToastLong(L10n.t("Couldn't save the change. Try again."));
                return;
            }
            row.setSummary(feedTypesSummary(FeedTypeNames.hidden()));
            list.dismiss();
        });
    }

    /**
     * Hide posts you've already seen, with how long they stay hidden and a row that forgets them.
     * The keep time is greyed out while the switch is off, Forget seen posts never is, and turning
     * the switch off forgets the list.
     */
    private static void seenPosts(PreferenceCategory feed, Context context) {
        SwitchPreference seen = toggle(context, Settings.HIDE_SEEN_POSTS,
                L10n.t("Posts you've scrolled past stay out of the feed when it loads again. The list stays on this phone. "
                        + "Turning this off empties it."));
        Preference keep = seenKeepRow(context);
        Row forget = new Row(context);
        forget.setTitle(L10n.t("Forget seen posts"));
        forget.setSummary(L10n.t("Empties the list of posts you've seen, so they can show up again."));
        forget.setPersistent(false);
        forget.actsAtOnce = true;
        forget.setOnPreferenceClickListener(p -> {
            SeenPosts.clear();
            Utils.showToastShort(SeenPosts.clearedMessage());
            return true;
        });
        // The keep time follows the switch and is greyed out until it's on. Forget seen posts
        // works either way, so a list is never out of reach.
        keep.setEnabled(Settings.HIDE_SEEN_POSTS.savedValue());
        seen.setOnPreferenceChangeListener((preference, value) -> {
            boolean now = Boolean.TRUE.equals(value);
            keep.setEnabled(now);
            // Off forgets the list. The file goes on a background thread.
            if (!now) SeenPosts.clear();
            return true;
        });
        feed.addPreference(seen);
        feed.addPreference(keep);
        feed.addPreference(forget);
    }

    /** Stories. */
    static void stories(HushfacebookPreferenceFragment page, PreferenceScreen screen, Context context,
            Set<PatchFamily> build) {
        if (build.contains(PatchFamily.SPONSORED_STORIES) || build.contains(PatchFamily.SUGGESTED_STORIES)
                || build.contains(PatchFamily.STORY_AUTO_ADVANCE) || build.contains(PatchFamily.STORY_SEEN)
                || build.contains(PatchFamily.STORY_DOWNLOAD) || build.contains(PatchFamily.STORY_UPLOAD_HD)
                || build.contains(PatchFamily.STORY_MUSIC_LENGTH) || build.contains(PatchFamily.STORIES_TRAY_CIRCLES)
                || build.contains(PatchFamily.PINCH_TO_ZOOM)) {
            PreferenceCategory stories = category(screen, L10n.t("Stories"));
            if (build.contains(PatchFamily.SPONSORED_STORIES)) {
                stories.addPreference(toggle(context, Settings.HIDE_SPONSORED_STORIES,
                        L10n.t("Hides ad cards between people's stories.")));
            }
            if (build.contains(PatchFamily.SUGGESTED_STORIES)) {
                // The tray's buckets are filtered as each answer of its fetch comes in, so a change
                // shows when Facebook next loads the tray, not on the tray already drawn.
                stories.addPreference(toggle(context, Settings.HIDE_SUGGESTED_STORIES,
                        L10n.t("Shows only friends and followed Pages in the Stories tray. Applies when Facebook next loads the "
                                + "tray.")));
                stories.addPreference(toggle(context, Settings.HIDE_CONTACT_IMPORT_CARD,
                        L10n.t("Hides the Stories tray card that asks to upload your contacts. Applies when Facebook next loads the "
                                + "tray.")));
                stories.addPreference(toggle(context, Settings.HIDE_STORY_PROMPTS,
                        L10n.t("Hides cards beside Create story that suggest a story to make, like Share music you love. Applies "
                                + "when Facebook next loads the tray.")));
                stories.addPreference(toggle(context, Settings.HIDE_PAGE_STORIES,
                        L10n.t("Shows only people's stories in the Stories tray, without the Pages you follow. Applies when "
                                + "Facebook next loads the tray.")));
                stories.addPreference(toggle(context, Settings.HIDE_SEEN_STORIES,
                        L10n.t("Stories you've already watched leave the Stories tray until there's a new one. Applies when "
                                + "Facebook next loads the tray.")));
            }
            if (build.contains(PatchFamily.STORY_AUTO_ADVANCE)) {
                stories.addPreference(toggle(context, Settings.BLOCK_STORY_AUTO_ADVANCE,
                        L10n.t("A finished story stays on screen until you tap or swipe. Turn this off for Facebook's timing.")));
                stories.addPreference(toggle(context, Settings.LOOP_STORIES,
                        L10n.t("With Stop Story auto-advance on, a finished story plays again from the start "
                                + "instead of waiting on its last frame. Tap or swipe to move on."))
                        .needs(Settings.BLOCK_STORY_AUTO_ADVANCE));
            }
            if (build.contains(PatchFamily.STORY_SEEN)) {
                stories.addPreference(toggle(context, Settings.VIEW_STORIES_ANONYMOUSLY,
                        L10n.t("Facebook isn't told which stories you watch, so you stay off their viewer lists. "
                                + "Replying or reacting still shows you. Stories you watch to the end show as watched "
                                + "on this phone only, until that person posts a new one.")));
                stories.addPreference(toggle(context, Settings.MARK_STORIES_SEEN,
                        L10n.t("Adds an eye button to the top of each story while you view anonymously. Tap it to "
                                + "show up on that story's viewer list. The other stories stay hidden.")));
            }
            if (build.contains(PatchFamily.STORY_DOWNLOAD)) {
                stories.addPreference(toggle(context, Settings.DOWNLOAD_STORIES,
                        L10n.t("Add Save to every story menu, using your download quality. Off or paused, Facebook only saves your own stories.")));
                // Read as each story's menu opens, so a change shows on the next menu.
                stories.addPreference(toggle(context, Settings.SAVE_STORY_PHOTOS,
                        L10n.t("Adds Save photo to any story that carries a picture beside its video, like a photo "
                                + "story with music that Facebook didn't mark as one. On a video story it saves the "
                                + "cover picture. Off by default, since it hasn't been tried on a real account yet.")));
            }
            if (build.contains(PatchFamily.STORY_UPLOAD_HD)) {
                // Read as each Story's upload is set up, so a change applies to the next one you post.
                stories.addPreference(toggle(context, Settings.UPLOAD_STORIES_IN_HD,
                        L10n.t("Videos you post to your Story ask Facebook for its high upload quality instead of "
                                + "standard. Applies to the next Story you post.")));
            }
            if (build.contains(PatchFamily.STORY_MUSIC_LENGTH)) {
                // Read each time a song is picked, so a change applies from the next one.
                stories.addPreference(toggle(context, Settings.LONGER_STORY_MUSIC,
                        L10n.t("A song you add to a photo for your Story plays for up to 30 seconds instead of 15. "
                                + "Applies to the next song you pick.")));
            }
            if (build.contains(PatchFamily.STORIES_TRAY_CIRCLES)) {
                // Read as each tile appears, so a change shows the next time Home loads its stories.
                stories.addPreference(toggle(context, Settings.STORIES_TRAY_CIRCLES,
                        L10n.t("Draws the stories row on Home as round pictures, like Instagram. Applies the next time "
                                + "Home loads its stories.")));
                stories.addPreference(HushfacebookPreferenceFragment.sizeRow(context));
            }
            if (build.contains(PatchFamily.PINCH_TO_ZOOM)) {
                // Asked as each second finger lands, so a change shows from the next pinch.
                stories.addPreference(toggle(context, Settings.PINCH_TO_ZOOM,
                        L10n.t("Pinch a story or a photo on someone's profile to zoom in. It goes back when you let go.")));
                stories.addPreference(toggle(context, Settings.PINCH_TO_ZOOM_KEEP,
                        L10n.t("With Pinch to zoom on, the photo or story stays zoomed when you let go, and a story "
                                + "stays paused. Drag with one finger or pinch again. Tap or press Back when you're done."))
                        .needs(Settings.PINCH_TO_ZOOM));
                stories.addPreference(toggle(context, Settings.PINCH_TO_ZOOM_DOUBLE_TAP,
                        L10n.t("With Pinch to zoom on, double tap a photo on a profile to zoom in where you tapped, "
                                + "and double tap again to put it back. A single tap still opens the photo, a moment later."))
                        .needs(Settings.PINCH_TO_ZOOM));
            }
        }
    }

    /**
     * Comments: the order they open in, Meta AI's summaries of them, what the comment box and the
     * Like button offer, and how far up the sheet opens.
     */
    static void comments(HushfacebookPreferenceFragment page, PreferenceScreen screen, Context context,
            Set<PatchFamily> build) {
        boolean order = build.contains(PatchFamily.DEFAULT_COMMENT_ORDER);
        boolean summaries = build.contains(PatchFamily.META_AI_SUMMARIES);
        boolean options = build.contains(PatchFamily.COMMENT_SHEET_OPTIONS);
        boolean reactions = build.contains(PatchFamily.POST_PROMPTS);
        boolean fullHeight = build.contains(PatchFamily.FULL_HEIGHT_COMMENTS);
        boolean tags = build.contains(PatchFamily.TAG_SUGGESTIONS);
        if (!order && !summaries && !options && !reactions && !fullHeight && !tags) return;
        PreferenceCategory comments = category(screen, L10n.t("Comments and writing"));
        if (order) {
            comments.addPreference(toggle(context, Settings.DEFAULT_COMMENT_ORDER,
                    L10n.t("Use the order below. A choice made on a post lasts until restart. Links to comments keep Facebook's order.")));
            comments.addPreference(commentOrderRow(context));
        }
        if (summaries) {
            comments.addPreference(toggle(context, Settings.HIDE_META_AI_SUMMARIES,
                    L10n.t("Hides Meta AI's summary at the top of comments and the comment summary under a post's buttons. The "
                            + "comments themselves stay.")));
        }
        if (reactions) {
            comments.addPreference(toggle(context, Settings.HIDE_COMMENT_REACTIONS,
                    L10n.t("Hides the stacked reaction icons in the corner of a comment. The space stays, so a tap "
                            + "where they were still opens the list of reactions.")));
        }
        if (options) {
            comments.addPreference(toggle(context, Settings.LIKE_ONLY,
                    L10n.t("A long press on Like doesn't open the reactions. A tap still likes.")));
            comments.addPreference(toggle(context, Settings.HIDE_COMMENT_GIF_STICKER_BUTTONS,
                    L10n.t("The comment box loses its GIF and sticker buttons. Typing, photos and posting work as before.")));
            comments.addPreference(toggle(context, Settings.HIDE_COMMENT_EMOJI_ROW,
                    L10n.t("The row of emoji over the comment box goes away. You can still type emoji from your keyboard.")));
            comments.addPreference(toggle(context, Settings.HIDE_VIDEO_COMMENT_COMPOSER,
                    L10n.t("The Add a comment box under a full-screen video goes away. Comment boxes on posts stay.")));
            comments.addPreference(toggle(context, Settings.OPEN_REPLY_THREADS,
                    L10n.t("Comments show their replies right away, so there's no View replies to tap.")));
            comments.addPreference(toggle(context, Settings.LOAD_WHOLE_REPLY_THREADS,
                    L10n.t("An open reply thread keeps loading as you scroll, so there's no View more replies to tap.")));
            comments.addPreference(toggle(context, Settings.HIDE_REPLIES_BUTTON,
                    L10n.t("An open reply thread gets a Hide replies button, so you can close it again after reading.")));
            comments.addPreference(toggle(context, Settings.TAP_ANYWHERE_ON_VIEW_MORE_REPLIES,
                    L10n.t("The whole View more replies row opens the replies, not just its words.")));
            comments.addPreference(toggle(context, Settings.HIDE_POST_COMMENT_PREVIEWS,
                    L10n.t("Feed posts stop showing a few comments under their buttons. Tap Comment to read them all.")));
            comments.addPreference(toggle(context, Settings.HIDE_COMMENT_DOWNVOTE_BUTTON,
                    L10n.t("Takes the thumbs-down off comments. Like and Reply stay.")));
            comments.addPreference(toggle(context, Settings.HIDE_COMMENT_LIKE_BUTTON,
                    L10n.t("Takes Like off the row under comments. Reply and the reaction count stay.")));
            comments.addPreference(toggle(context, Settings.COMMENT_BLOCK_BUTTON,
                    L10n.t("The thumbs-down on a comment becomes Block. One tap opens Facebook's own block screen for that person.")));
            comments.addPreference(toggle(context, Settings.HIDE_RELATED_GROUPS_UNDER_COMMENTS,
                    L10n.t("A group post's comments end without the Related groups list and its Join buttons.")));
            comments.addPreference(toggle(context, Settings.HIDE_SHOP_LINK_COMMENTS,
                    L10n.t("Hides comments that link to a shop like Shopee, Lazada or Amazon, along with their replies.")));
            // The words are the feed's own lists, edited on the Feed page, so the switch shows
            // only in a build that has them.
            if (build.contains(PatchFamily.POST_WORDS)) {
                comments.addPreference(toggle(context, Settings.HIDE_COMMENTS_WITH_WORDS,
                        L10n.t("Hides comments containing a word from your Words to hide list in News feed, along with their "
                                + "replies. Words that keep a post keep a comment too.")));
            }
            comments.addPreference(toggle(context, Settings.HIDE_INCOMING_COMMENT_TYPING,
                    L10n.t("Takes away the line that says someone is typing a comment. Your own typing is a separate switch under Privacy.")));
            comments.addPreference(toggle(context, Settings.HIDE_NEW_COMMENTS_PILL,
                    L10n.t("The \"new comments\" bubble no longer pops up while you read comments. New comments still show in the list.")));
            comments.addPreference(reactionCountsRow(context));
        }
        if (fullHeight) {
            comments.addPreference(toggle(context, Settings.OPEN_COMMENTS_FULL_HEIGHT,
                    L10n.t("Comments open on a sheet that fills the screen instead of stopping part way up. On reels and "
                            + "videos, Comment opens that sheet in place of the split view with the video on top.")));
            // The pause goes through Tap to play's player bridge (#266).
            if (build.contains(PatchFamily.TAP_TO_PLAY)) {
                comments.addPreference(toggle(context, Settings.PAUSE_REELS_UNDER_COMMENTS,
                        L10n.t("A reel stops while you read its comments and plays on from the same spot when you close "
                                + "them.")));
            }
            // Facebook's own tap on the reel does the playing and pausing, or on the Reels tab Tap to play's bridge (#315).
            comments.addPreference(toggle(context, Settings.TAP_REELS_ABOVE_COMMENTS,
                    L10n.t("Tapping the reel above its comments plays or pauses it, and the comments stay open. "
                            + "Works while comments open part way up, not at full height.")));
        }
        // Writing had a section of its own for this one switch.
        if (tags) {
            subheading(comments, L10n.t("Writing"));
            comments.addPreference(toggle(context, Settings.TAG_SUGGESTIONS_ONLY_AFTER_AT,
                    L10n.t("Type @ before Facebook suggests someone to tag in posts or comments. Your text stays unchanged.")));
        }
    }

    /**
     * Opens Facebook's own settings, where Preferences has Reaction preferences and its switches
     * for hiding reaction counts. Facebook keeps those on its servers, so Hushfacebook doesn't
     * rebuild them.
     */
    static Preference reactionCountsRow(Context context) {
        Preference row = new Row(context);
        row.setTitle(L10n.t("Hide reaction counts"));
        row.setSummary(L10n.t("Facebook has its own setting for this. Open Facebook's settings, then Preferences "
                + "and Reaction preferences."));
        row.setPersistent(false);
        row.setOnPreferenceClickListener(p -> {
            if (!CommentSheetOptions.openReactionSettings(p.getContext())) {
                Utils.showToastLong(L10n.t("Facebook's settings didn't open. You'll find Reaction preferences in its Settings, "
                        + "under Preferences."));
            }
            return true;
        });
        // No icon: it shares a card with switches, which have none, and an icon would push its text in.
        return row;
    }
}
