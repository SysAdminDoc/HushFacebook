/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.morphe.extension.facebook.settings;

import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.burnInGuardRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.burnInWaitRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.category;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.downloadActionRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.fileNameRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.folderRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.info;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.photoNameRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.playbackQualityRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.qualityRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.reelsOrderRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.saveStorageRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.saveToRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.sendAppRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.subfolderRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.subheading;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.surfaceQualityRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.toggle;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.watchedKeepRow;

import android.content.Context;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;

import androidx.annotation.Nullable;

import java.util.Set;

import app.morphe.extension.facebook.settings.SettingsRows.Row;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.settings.BooleanSetting;

/**
 * The category pages for video: Reels and Watch, Video and audio, and Downloads. Each method adds its section to the page
 * {@link HushfacebookPreferenceFragment#initialize} builds, and leaves it out when the build has none of
 * its patches, but for the sections every build has.
 */
@SuppressWarnings("deprecation")
final class VideoPages {
    private VideoPages() {
    }

    /** Reels and Watch, in every build, with the map of the four places Reels show up. */
    static void reels(HushfacebookPreferenceFragment page, PreferenceScreen screen, Context context,
            Set<PatchFamily> build) {
        // In every build: "How do I block Reels?" has four answers in four places (discussion #17),
        // and the tab's answer is Facebook's own setting, so the map is here whatever was patched.
        PreferenceCategory reels = category(screen, L10n.t("Reels and Watch"));
        reels.addPreference(info(context, L10n.t("How to block Reels"),
                L10n.t("Reels show up in four places, and each one has its own control.")));
        reels.addPreference(reelsLink(page, context, build, PatchFamily.FEED_REELS, Settings.HIDE_FEED_REELS,
                L10n.t("Reels in the feed"),
                L10n.t("In News feed, Hide Reels in the feed blocks the rows of reels between posts.")));
        reels.addPreference(reelsLink(page, context, build, PatchFamily.TAP_TO_PLAY, Settings.TAP_TO_PLAY,
                L10n.t("Reels that play by themselves"),
                L10n.t("In Video and audio, Tap to play blocks autoplay, so reels and other videos wait for your tap.")));
        // Without Hide the Reels tab, Facebook's own Hide is the answer, on the accounts that have it.
        if (build.contains(PatchFamily.REELS_TAB)) {
            reels.addPreference(reelsLink(page, context, build, PatchFamily.REELS_TAB, Settings.HIDE_REELS_TAB,
                    L10n.t("The Reels tab"),
                    L10n.t("In Reels and Watch, Hide the Reels tab blocks it after a restart.")));
        } else {
            reels.addPreference(info(context, L10n.t("The Reels tab"),
                    L10n.f("Facebook's own setting blocks it. Open Settings, Tab bar, Customize the bar and choose "
                            + "Hide next to Reels, which some accounts call Video. If neither is listed, choose the "
                            + "%1$s patch in Morphe Manager and patch again.",
                            L10n.isolate(PatchFamily.REELS_TAB.patchName))));
        }
        reels.addPreference(reelsLink(page, context, build, PatchFamily.MARKETPLACE_ONLY, Settings.MARKETPLACE_ONLY,
                L10n.t("Everything except Marketplace"),
                L10n.t("In Opening Facebook, Marketplace only blocks the feed, the Reels tab and the other social "
                        + "tabs after a restart.")));
        subheading(reels, L10n.t("Reels tab and ads"));
        if (build.contains(PatchFamily.REELS_TAB)) {
            // Facebook keeps the tab bar it built, so a change waits for a restart and the page says so.
            reels.addPreference(toggle(context, Settings.HIDE_REELS_TAB,
                    L10n.t("Removes the Reels tab, called Video on some accounts, from the tab bar. Reels in the feed and reel "
                            + "links still open. Restart Facebook to see the change.")));
        }
        if (build.contains(PatchFamily.REELS_TAB_DOT)) {
            reels.addPreference(toggle(context, Settings.HIDE_REELS_TAB_DOT,
                    L10n.t("No dot or new count on the Reels tab, called Video on some accounts. Other tabs keep theirs.")));
        }
        if (build.contains(PatchFamily.REEL_PROMPTS)) {
            reels.addPreference(toggle(context, Settings.HIDE_REEL_PROMPTS,
                    L10n.t("No \"Are you interested in this reel?\" prompt on reels. The reel plays as usual.")));
        }
        // Both reel filters work on each batch of reels as it arrives, so a change leaves the
        // reels already loaded as they are, and the rows say so.
        if (build.contains(PatchFamily.SPONSORED_REELS)) {
            reels.addPreference(toggle(context, Settings.HIDE_SPONSORED_REELS,
                    L10n.t("Hides ads in Reels, starting with the next batch Facebook loads. Banner ads, mid-video ads and "
                            + "other ads added by the app stay blocked even while paused.")));
        }
        if (build.contains(PatchFamily.AI_DETECTED_POSTS)) {
            reels.addPreference(toggle(context, Settings.HIDE_AI_DETECTED_REELS,
                    L10n.t("Hides reels and Watch videos that Facebook marks as made with AI. Ones only their creator labeled "
                            + "stay. Off by default, since it hasn't been tried on a real account yet.")));
        }
        subheading(reels, L10n.t("On reels and videos"));
        if (build.contains(PatchFamily.REEL_DECLUTTER)) {
            reels.addPreference(toggle(context, Settings.HIDE_REEL_CHIPS,
                    L10n.t("Hides prompts and promos under reels, like Remix, Use template, Add yours, Edits, Stars, games, "
                            + "partner apps and outside links. The song label stays.")));
            reels.addPreference(toggle(context, Settings.HIDE_REEL_FOLLOW_BUTTON,
                    L10n.t("The Follow button next to the reel's author. You can still follow them from their profile.")));
            reels.addPreference(toggle(context, Settings.HIDE_VIDEO_SUBSCRIBE_BUTTON,
                    L10n.t("The Subscribe button some authors get there instead, which subscribes with one tap. "
                            + "Follow buttons stay.")));
            reels.addPreference(toggle(context, Settings.HIDE_REEL_SOCIAL_FOOTER,
                    L10n.t("The comment Facebook previews under a reel and the bubbles of friends who reacted. "
                            + "Open the comments to see them all.")));
            reels.addPreference(toggle(context, Settings.HIDE_REEL_THREADS_CARDS,
                    L10n.t("Hides the \"Threads you might like\" card between reels. Off by default, since it hasn't "
                            + "been tried on a real account yet.")));
            reels.addPreference(toggle(context, Settings.REEL_CLEAN_MODE,
                    L10n.t("Opens reels and videos in Facebook's Clean mode, which hides the buttons down the side. You can "
                            + "still leave Clean mode as usual. Off by default, since it hasn't been tried on a real "
                            + "account yet.")));
            reels.addPreference(toggle(context, Settings.PLAY_REELS_ONCE,
                    L10n.t("A reel stops on its last frame instead of starting over. Tap it to watch again, or swipe to "
                            + "the next one. Feed videos and stories aren't changed. Off by default, since it hasn't "
                            + "been tried on a real account yet.")));
            // Asked as each video's audio is picked, so a change shows from the next video.
            reels.addPreference(toggle(context, Settings.ORIGINAL_AUDIO,
                    L10n.t("Reels and videos play with the sound they were made with, not a version Facebook dubbed "
                            + "into your language with Meta AI. Off by default, since it hasn't been tried on a real "
                            + "account yet.")));
            // The pill is built as each reel's footer is, so a change shows from the next reel.
            reels.addPreference(toggle(context, Settings.HIDE_REEL_SEARCH_PILL,
                    L10n.t("Hides the search suggestion under a reel's caption, the magnifier with a question to look up, "
                            + "the one at the top of its comments and the search bar over reels you open from a profile.")));
            // Asked after each layout of the full-screen player, so a change shows right away.
            reels.addPreference(toggle(context, Settings.HIDE_VIEWER_TOP_BUTTONS,
                    L10n.t("Takes Search and the button next to it off the top of a video or reel you open. Back "
                            + "stays, and so does More at the bottom of the side buttons, where Download is.")));
            // Burn-in guard (#288): read on each pass, so a change shows right away.
            reels.addPreference(burnInGuardRow(context));
            reels.addPreference(burnInWaitRow(context));
            reels.addPreference(toggle(context, Settings.BURN_IN_STAY_DIM,
                    L10n.t("Once Burn-in guard fades the buttons, they stay faded on Reels whatever you tap or swipe, "
                            + "the next reel too. They still work. Leaving Reels brings them back.")));
            // The sidebar buttons are built as each reel is, so a change shows from the next reel.
            reels.addPreference(toggle(context, Settings.HIDE_REEL_COUNTS,
                    L10n.t("Takes the numbers away from under a reel's Like, Comment and Share icons. "
                            + "The icons stay where they are. A change shows from the next reel.")));
            reels.addPreference(toggle(context, Settings.HIDE_REEL_SAVE_BUTTON,
                    L10n.t("Takes the Save button off the side of reels. The buttons above it move down. "
                            + "A change shows from the next reel.")));
            reels.addPreference(toggle(context, Settings.HIDE_REEL_WHATSAPP_BUTTON,
                    L10n.t("Hides the WhatsApp button beside reels and videos. WhatsApp stays in the Share menu. "
                            + "A change shows from the next video.")));
            for (BooleanSetting hide : new BooleanSetting[] {Settings.HIDE_REEL_LIKE_BUTTON,
                    Settings.HIDE_REEL_COMMENT_BUTTON, Settings.HIDE_REEL_SHARE_BUTTON}) {
                reels.addPreference(toggle(context, hide,
                        L10n.t("Takes this button off the side of reels. The buttons above it move down. "
                                + "A change shows from the next reel.")));
            }
            reels.addPreference(toggle(context, Settings.SHOW_REEL_COMMENT_BUTTON,
                    L10n.t("Keeps the Comment button on reels whose comments are turned off, so you can still read "
                            + "the ones already there. A change shows from the next reel.")));
            reels.addPreference(toggle(context, Settings.SHOW_REEL_POST_DATE,
                    L10n.t("Shows when a reel or a video you open full screen was posted, under the name of "
                            + "who posted it. A change shows from the next reel.")));
            reels.addPreference(toggle(context, Settings.HIDE_REEL_SOUND_BUTTON,
                    L10n.t("Takes the speaker button off the bottom of the side of reels. "
                            + "A change shows from the next reel.")));
            // The audio line is one of the chips, built as each reel's footer is.
            reels.addPreference(toggle(context, Settings.HIDE_REEL_AUDIO_LINE,
                    L10n.t("Takes the line with the reel's sound, \"Original audio\" or a song, off the bottom of "
                            + "reels. Other info stays. A change shows from the next reel.")));
            // Asked each time a video stalls long enough for the message, so a change shows right away.
            reels.addPreference(toggle(context, Settings.HIDE_SLOW_CONNECTION_MESSAGE,
                    L10n.t("Stops the \"This is taking a while\" message about your network connection when a video "
                            + "or GIF is slow to load. It keeps loading as before.")));
            // Asked as a feed video's player asks for more, so a change shows from the next feed video.
            reels.addPreference(toggle(context, Settings.FEED_VIDEOS_ALONE,
                    L10n.t("A video you tap in the feed opens full screen by itself, with no reels to swipe to after it. "
                            + "The Reels tab keeps scrolling. Off by default, since it hasn't been tried on a real "
                            + "account yet.")));
            // Asked as each tile of the feed's Reels row is drawn, so a change shows on the next tiles.
            reels.addPreference(toggle(context, Settings.PLAY_FEED_REELS_WHOLE,
                    L10n.t("The reels in your feed's Reels row play from the start and keep going, instead of "
                            + "repeating a second or two. Tiles drawn after a change pick it up.")));
        }
        if (build.contains(PatchFamily.REEL_WATCH_HISTORY)) {
            reels.addPreference(toggle(context, Settings.DONT_SEND_REEL_WATCH_HISTORY,
                    L10n.t("Stops sending Facebook your list of watched reels. It uses the list to rank your feed, so reels "
                            + "you've watched may come back.")));
        }
        if (build.contains(PatchFamily.NEWEST_REELS_FIRST)) {
            Preference sort = toggle(context, Settings.NEWEST_REELS_FIRST,
                    L10n.t("The Reels tab plays the reels Facebook has loaded for you in the order picked below, instead "
                            + "of its own pick. It can't fetch more, and reels already placed stay put. Off by default, "
                            + "since it hasn't been tried on a real account yet."));
            // Its title was Newest reels first until it sorted by likes and plays too (#134).
            SettingsRows.alias(sort, L10n.t("Newest reels first"));
            reels.addPreference(sort);
            reels.addPreference(reelsOrderRow(context));
            reels.addPreference(toggle(context, Settings.PROFILE_REELS_MOST_VIEWED,
                    L10n.t("A profile's Reels tab lists its most viewed reels first instead of the newest. Off keeps "
                            + "Facebook's order. Only the reels the tab has loaded are sorted, and a reel with no view count "
                            + "stays after the rest.")));
        }
        subheading(reels, L10n.t("Video controls"));
        if (build.contains(PatchFamily.DOUBLE_TAP_LIKE)) {
            reels.addPreference(toggle(context, Settings.TURN_OFF_DOUBLE_TAP_LIKE,
                    L10n.t("A double tap on a reel, a video or a photo in the feed no longer likes it or shows a heart. "
                            + "A single tap and the Like button work as before.")));
        }
        if (build.contains(PatchFamily.DOUBLE_TAP_SKIP)) {
            // Read as each player is set up, so a change shows on the next reel or video.
            reels.addPreference(toggle(context, Settings.DOUBLE_TAP_SKIP,
                    L10n.t("A double tap on the left or right edge of a reel or video skips back or forward 10 seconds, "
                            + "or 5 on one under 30 seconds. A double tap in the middle works as before.")));
        }
        if (build.contains(PatchFamily.TAP_SHOWS_CONTROLS)) {
            // Read on each tap, so a change shows on the next one.
            reels.addPreference(toggle(context, Settings.TAP_SHOWS_CONTROLS,
                    L10n.t("A tap on a playing reel shows its controls while it keeps playing, and a second tap hides "
                            + "them. The button in the middle still pauses.")));
        }
        if (build.contains(PatchFamily.REEL_GESTURES)) {
            reels.addPreference(toggle(context, Settings.DOUBLE_TAP_OPENS_COMMENTS,
                    L10n.t("A double tap on a reel opens its comments instead of liking it. Turn off double tap to like "
                            + "still wins when it's on. Off by default, since it hasn't been tried on a real account yet.")));
            reels.addPreference(toggle(context, Settings.LONG_PRESS_OPENS_COMMENTS,
                    L10n.t("Holding a reel opens its comments instead of Facebook's long-press menu or the 2x hold. The "
                            + "reel's more button still opens that menu. Holding a feed post's photo or the line with its "
                            + "name opens that post's comments, and holding its text or a profile picture works as before. "
                            + "Off by default, since it hasn't been tried on a real account yet.")));
        }
        if (build.contains(PatchFamily.KEEP_REEL_SPEED)) {
            reels.addPreference(toggle(context, Settings.KEEP_REEL_SPEED,
                    L10n.t("A playback speed you pick in a reel's menu stays for the next reels, after a restart too, "
                            + "until you pick another. Off, every reel starts at normal speed.")));
            reels.addPreference(toggle(context, Settings.KEEP_VIDEO_SPEED,
                    L10n.t("The speed you pick in a feed or Watch video's menu carries over to the next videos. Reels keep "
                            + "their own speed. Off by default, since it hasn't been tried on a real account yet.")));
            reels.addPreference(toggle(context, Settings.SLOWER_REEL_SPEEDS,
                    L10n.t("Adds 0.1x and 0.25x to the speed menu of reels and of feed and Watch videos, for slowing down a "
                            + "fast moment.")));
            reels.addPreference(toggle(context, Settings.SPEED_ON_REPLAYS,
                    L10n.t("A live video that has ended gets Playback speed in its gear menu, like other videos. "
                            + "Videos that are still live, and ads, keep Facebook's menu.")));
        }
        if (build.contains(PatchFamily.REEL_HOLD)) {
            reels.addPreference(toggle(context, Settings.HOLD_REEL_FOR_2X,
                    L10n.t("Holding a reel plays it at double speed until you let go, in place of Facebook's long-press "
                            + "menu. The reel's more button still opens that menu.")));
            reels.addPreference(toggle(context, Settings.HOLD_REEL_RIGHT_EDGE,
                    L10n.t("With the switch above on, only a hold on the right third of a reel plays it at double "
                            + "speed. Hold anywhere else and Facebook's long-press menu opens.")));
            reels.addPreference(toggle(context, Settings.HOLD_FEED_VIDEO_FOR_2X,
                    L10n.t("Holding a video in the feed or in Watch plays it at double speed until you let go. It works "
                            + "without the switches above.")));
            reels.addPreference(toggle(context, Settings.HIDE_2X_LOCK_HINT,
                    L10n.t("Holding a video for 2x shows the plain 2x pill, without the \"Pull down to lock 2x speed\" "
                            + "hint. Pulling down still locks it. It works without the switches above.")));
        }
        if (build.contains(PatchFamily.REEL_DOWNLOAD)) {
            subheading(reels, L10n.t("Saving reels"));
            reels.addPreference(toggle(context, Settings.DOWNLOAD_REELS, null));
            reels.addPreference(toggle(context, Settings.SHOW_REEL_DOWNLOAD_BUTTON,
                    L10n.t("Add a Download button to reels, using your download quality. Off or paused, Facebook's own buttons return."))
                    .needs(Settings.DOWNLOAD_REELS));
            reels.addPreference(toggle(context, Settings.REEL_CAST_BUTTON,
                    L10n.t("Adds a cast button to reels, under Download. It hands the reel to a casting app you have, such as Web Video Caster, which sends it to your TV.")));
            reels.addPreference(toggle(context, Settings.COPY_REEL_CAPTION,
                    L10n.t("A reel's More menu gets Copy caption, which copies the whole caption, even the part behind See more.")));
        }
    }

    /** Video and audio, whose route is still Playback, its earlier name. */
    static void playback(HushfacebookPreferenceFragment page, PreferenceScreen screen, Context context,
            Set<PatchFamily> build) {
        if (build.contains(PatchFamily.TAP_TO_PLAY) || build.contains(PatchFamily.RESUME_LONG_VIDEOS)
                || build.contains(PatchFamily.FRAME_STEP)
                || build.contains(PatchFamily.PLAYBACK_QUALITY) || build.contains(PatchFamily.PICTURE_IN_PICTURE)
                || build.contains(PatchFamily.HDR_BRIGHTNESS) || build.contains(PatchFamily.SKIP_SILENCE)
                || build.contains(PatchFamily.KEEP_FEED_VIDEOS_MUTED) || build.contains(PatchFamily.PROGRESS_BAR)) {
            PreferenceCategory playback = category(screen, L10n.t("Video and audio"));
            subheading(playback, L10n.t("Autoplay and resume"));
            if (build.contains(PatchFamily.TAP_TO_PLAY)) {
                playback.addPreference(toggle(context, Settings.TAP_TO_PLAY,
                        L10n.t("Videos, reels, stories and music wait for your tap instead of playing by themselves. Facebook's own "
                                + "Autoplay setting shows Off while this is on.")));
                playback.addPreference(toggle(context, Settings.TAP_TO_PLAY_REELS_AFTER_FIRST,
                        L10n.t("With the switch above on, after you play one reel, the reels you swipe to play on their own. The "
                                + "feed, Watch and stories always wait.")));
                // Asked at each story video's start, so it applies to the next story you open.
                playback.addPreference(toggle(context, Settings.TAP_TO_PLAY_STORIES,
                        L10n.t("Video stories don't start by themselves. Touch and hold a story, then let go, to play it. "
                                + "Works with Tap to play on or off.")));
                playback.addPreference(toggle(context, Settings.INLINE_FEED_VIDEO_CONTROLS,
                        L10n.t("Adds a play/pause button to feed videos. Tap the video itself to open it full screen. Live videos and ads keep their own controls. "
                                + "With Tap to play on too, Facebook's own Autoplay setting stays as you set it so the button can show, and videos still wait for your tap.")));
                // The tap hands over the file the video patch's player record found.
                if (build.contains(PatchFamily.VIDEO_DOWNLOAD)) {
                    playback.addPreference(toggle(context, Settings.FEED_VIDEO_CAST_BUTTON,
                            L10n.t("Adds a cast button to feed videos. It hands the video to a casting app you have, such as Web Video Caster, which sends it to your TV. Facebook has no casting of its own anymore.")));
                }
            }
            if (build.contains(PatchFamily.RESUME_LONG_VIDEOS)) {
                playback.addPreference(toggle(context, Settings.RESUME_LONG_VIDEOS,
                        L10n.t("Videos over two minutes pick up where you left off. Drag the bar to start elsewhere. Short reels, "
                                + "live videos and ads start as usual.")));
                // Asked at each start and each story card, so a change shows from the next one (#183).
                playback.addPreference(toggle(context, Settings.RECENTLY_WATCHED,
                        L10n.t("Keeps a list on this phone of the reels, videos and stories you watch, so you can find one "
                                + "again after the feed refreshes. Nothing leaves your phone.")));
                playback.addPreference(RecentlyWatchedScreen.row(page, context));
                // Usable with recording off too, since what's listed goes on aging.
                playback.addPreference(watchedKeepRow(context));
            }
            subheading(playback, L10n.t("Video quality"));
            if (build.contains(PatchFamily.PLAYBACK_QUALITY)) {
                playback.addPreference(toggle(context, Settings.DEFAULT_PLAYBACK_QUALITY,
                        L10n.t("Play videos, reels and stories at the quality below. A quality picked in a video's own menu still wins.")));
                playback.addPreference(playbackQualityRow(context));
                playback.addPreference(surfaceQualityRow(context, true));
                playback.addPreference(surfaceQualityRow(context, false));
            }
            if (build.contains(PatchFamily.HDR_BRIGHTNESS)) {
                // Asked as each screen comes to the front, so a change shows from the next one.
                playback.addPreference(toggle(context, Settings.TURN_OFF_HDR_BRIGHTNESS,
                        L10n.t("HDR videos and photos stay at your screen's usual brightness instead of turning it up to "
                                + "full. They keep their resolution.")));
                // Asked as each video's manifest is read, so a change shows from the next new video.
                playback.addPreference(toggle(context, Settings.PREFER_SDR_RENDITIONS,
                        L10n.t("When Facebook also sends an HDR video in SDR at the same size, play that version instead. "
                                + "The screen doesn't brighten and the picture stays as sharp. Works with the switch above "
                                + "on or off.")));
                // Asked as each player picks its view, so a change shows from the next video.
                playback.addPreference(toggle(context, Settings.DRAW_VIDEOS_WITH_THE_SCREEN,
                        L10n.t("Videos and reels draw together with the rest of the screen, like Disable HW overlays "
                                + "in developer options. Try it if reels look pale until something covers them. "
                                + "Uses a little more battery.")));
            }
            subheading(playback, L10n.t("Sound"));
            if (build.contains(PatchFamily.SKIP_SILENCE)) {
                // Asked as each player's sound starts, so a change shows from the next seek or video.
                playback.addPreference(toggle(context, Settings.SKIP_SILENT_PARTS,
                        L10n.t("Videos, reels and stories jump past the quiet stretches in their sound, so talks and "
                                + "how-tos get to the point sooner.")));
            }
            if (build.contains(PatchFamily.KEEP_FEED_VIDEOS_MUTED)) {
                // Asked as each session starts and each time something turns the sound on, so a restart shows it.
                playback.addPreference(toggle(context, Settings.KEEP_FEED_VIDEOS_MUTED,
                        L10n.t("Videos in your feed start with the sound off every time you open Facebook, and only you "
                                + "turn it on. Tap a video's speaker or press volume up to hear it.")));
                // Asked at each story's volume, so the next story you open shows it.
                playback.addPreference(toggle(context, Settings.MUTE_STORIES,
                        L10n.t("Stories start with the sound off every time you open Facebook. Tap a speaker or press "
                                + "volume up to hear them, and they stay on until you mute again.")));
            }
            subheading(playback, L10n.t("Controls and picture-in-picture"));
            if (build.contains(PatchFamily.FRAME_STEP)) {
                // Asked on each tap, so a change shows from the next one.
                playback.addPreference(toggle(context, Settings.FRAME_STEP,
                        L10n.t("With a reel or video paused, tap its right side to step one frame forward and its left "
                                + "side to step one frame back. Tap the middle to play.")));
            }
            if (build.contains(PatchFamily.PROGRESS_BAR)) {
                playback.addPreference(toggle(context, Settings.KEEP_PROGRESS_BAR,
                        L10n.t("A reel's progress bar stays full size, ready to drag. A full-screen video's controls "
                                + "stay until you tap.")));
                playback.addPreference(toggle(context, Settings.KEEP_PROGRESS_BAR_TIME,
                        L10n.t("With Keep the progress bar on, a reel's elapsed and total time stay above the bar. "
                                + "Off, they show only while you drag it.")));
                // Asked on every press of the bar, so it works from the next tap.
                playback.addPreference(toggle(context, Settings.TAP_TO_SEEK,
                        L10n.t("Tap anywhere on a reel's or video's progress bar to jump there, instead of dragging "
                                + "the dot.")));
            }
            if (build.contains(PatchFamily.PICTURE_IN_PICTURE)) {
                playback.addPreference(toggle(context, Settings.PICTURE_IN_PICTURE,
                        L10n.t("A playing reel or full-screen video keeps going in a small window when you leave Facebook. Android 12 or later.")));
                // Asked as each window is armed and on each tap of the row, so a change shows from the next reel.
                playback.addPreference(toggle(context, Settings.PICTURE_IN_PICTURE_FROM_MENU,
                        L10n.t("Leaving Facebook doesn't open the window. Tap Picture-in-picture in a reel's More menu "
                                + "(the three dots) when you want one. That row shows on upright reels, not wide ones.")));
                // Asked as each screen is armed and as the screen changes, so a change shows within a moment.
                playback.addPreference(toggle(context, Settings.PICTURE_IN_PICTURE_BUTTON,
                        L10n.t("Shows a small button at the left edge of a reel or video that has the window ready. Tap it to open "
                                + "the window right away. Works with Picture-in-picture on.")));
            }
        }
    }

    /**
     * Downloads, where the saves running now are listed too. Answers the section, or null when no
     * download patch is in.
     */
    @Nullable
    static PreferenceCategory downloads(PreferenceScreen screen, Context context, Set<PatchFamily> build) {
        if (build.contains(PatchFamily.STORY_DOWNLOAD) || build.contains(PatchFamily.REEL_DOWNLOAD)
                || build.contains(PatchFamily.VIDEO_DOWNLOAD) || build.contains(PatchFamily.PHOTO_DOWNLOAD)) {
            PreferenceCategory downloads = category(screen, L10n.t("Downloads"));
            if (build.contains(PatchFamily.VIDEO_DOWNLOAD)) {
                downloads.addPreference(toggle(context, Settings.DOWNLOAD_VIDEOS,
                        L10n.t("Add Download to phone to feed and Watch video menus. Uses the quality below. Off or paused, Facebook's menu returns.")
                                + " " + L10n.t("A video comment gets Save video when you long-press it.")));
                downloads.addPreference(toggle(context, Settings.CLIPBOARD_DOWNLOAD,
                        L10n.t("With Download feed and Watch videos on, coming back to Facebook with a reel or video link copied offers to download it, once per link. Off, Facebook doesn't look at what you copied.")));
            }
            if (build.contains(PatchFamily.VIDEO_DOWNLOAD)) {
                downloads.addPreference(toggle(context, Settings.OPEN_IN_ANOTHER_APP,
                        L10n.t("Adds Open in another app to feed and Watch video menus. It hands the video to a player or casting app, such as VLC or Web Video Caster. A video with no single file to hand over says so.")));
            }
            if (build.contains(PatchFamily.VIDEO_DOWNLOAD) || build.contains(PatchFamily.PHOTO_DOWNLOAD)) {
                downloads.addPreference(toggle(context, Settings.POST_MENU_DOWNLOAD_FIRST,
                        L10n.t("Moves Download to phone and Save photo from the end of a post's three-dot menu to the start of its options list. Facebook's own top rows stay above them (on photo posts the list is the More options page). A post with nothing to save gets neither.")));
            }
            if (build.contains(PatchFamily.PHOTO_DOWNLOAD)) {
                downloads.addPreference(toggle(context, Settings.DOWNLOAD_PHOTOS,
                        L10n.t("Shows Save photo on every photo you open, even where saving is turned off, and saves the biggest "
                                + "size. Off or paused, Facebook decides again.")
                                + " " + L10n.t("A GIF comment gets Save GIF when you long-press it.")));
                downloads.addPreference(toggle(context, Settings.POST_MENU_PHOTO_SAVE,
                        L10n.t("With Save any photo on, a post with photos gets Save photo in its three-dot menu too. A post with several saves each one, one after another.")));
            }
            // Every save reads these, a story's and a reel's as much as a feed video's, so they're here
            // whichever download patch is in, the compatible ones above the quality they keep within.
            downloads.addPreference(toggle(context, Settings.DOWNLOAD_QUIET,
                    L10n.t("No toast when a save starts, finishes or is canceled. A save that fails still says why.")));
            downloads.addPreference(toggle(context, Settings.DOWNLOAD_COMPATIBLE,
                    L10n.t("Fixes saved videos that play without sound in WhatsApp, video editors such as CapCut and InShot, or "
                            + "some galleries and players. May lower quality.")));
            downloads.addPreference(toggle(context, Settings.DOWNLOAD_CONVERT,
                    L10n.t("With Save videos other apps can open on, a full size video Facebook only has as VP9 or AV1 is "
                            + "converted to H.264 on your phone instead of saved smaller. Slower, and videos over 10 minutes "
                            + "stay smaller.")));
            downloads.addPreference(qualityRow(context));
            downloads.addPreference(saveStorageRow(context));
            downloads.addPreference(saveToRow(context));
            downloads.addPreference(folderRow(context));
            downloads.addPreference(fileNameRow(context));
            if (build.contains(PatchFamily.PHOTO_DOWNLOAD)) downloads.addPreference(photoNameRow(context));
            // Reels and feed and Watch videos can go to another app as a link (#41). A story can't:
            // its link opens only for someone signed in, so no downloader could fetch it.
            if (build.contains(PatchFamily.REEL_DOWNLOAD) || build.contains(PatchFamily.VIDEO_DOWNLOAD)) {
                downloads.addPreference(downloadActionRow(context));
                downloads.addPreference(sendAppRow(context));
            }
            // Last, so the names stay next to the folder and what Download does stays under them.
            downloads.addPreference(subfolderRow(context, true));
            if (build.contains(PatchFamily.PHOTO_DOWNLOAD)) downloads.addPreference(subfolderRow(context, false));
            return downloads;
        }
        return null;
    }

    /**
     * One line of the Reels map. A tap goes to [setting]'s own row, and changes nothing. Without
     * [family] in the build there's no row to go to, so the line names the patch to add instead
     * and can't be tapped.
     */
    private static Preference reelsLink(HushfacebookPreferenceFragment page, Context context, Set<PatchFamily> build,
                                        PatchFamily family, BooleanSetting setting, String title, String summary) {
        if (!build.contains(family)) {
            return info(context, title, L10n.f("Not in this build. To block this, choose the %1$s patch in "
                    + "Morphe Manager and patch again.", L10n.isolate(family.patchName)));
        }
        Row row = new Row(context);
        row.setKey("action_show_" + setting.key);
        row.setPersistent(false);
        row.setTitle(title);
        row.setSummary(summary);
        row.setOnPreferenceClickListener(ignored -> {
            Preference target = page.findPreference(setting.key);
            return target != null && page.jumpTo(target);
        });
        return row;
    }
}
