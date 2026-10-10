/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.morphe.extension.facebook.settings;

import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.category;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.downloadActionRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.fileNameRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.folderRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.info;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.photoNameRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.playbackQualityRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.qualityRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.saveToRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.sendAppRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.subfolderRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.surfaceQualityRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.toggle;

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
 * The category pages for video: Reels and Watch, Playback and Downloads. Each method adds its section to the page
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
                L10n.t("In Playback, Tap to play blocks autoplay, so reels and other videos wait for your tap.")));
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
                            + "stay. Untested on a real account, so it starts off.")));
        }
        if (build.contains(PatchFamily.REEL_DECLUTTER)) {
            reels.addPreference(toggle(context, Settings.HIDE_REEL_CHIPS,
                    L10n.t("Hides prompts and promos under reels, like Remix, Use template, Add yours, Edits, Stars, games, "
                            + "partner apps and outside links. The song label stays.")));
            reels.addPreference(toggle(context, Settings.HIDE_REEL_FOLLOW_BUTTON,
                    L10n.t("The Follow button next to the reel's author. You can still follow them from their profile.")));
            reels.addPreference(toggle(context, Settings.HIDE_REEL_SOCIAL_FOOTER,
                    L10n.t("The comment Facebook previews under a reel and the bubbles of friends who reacted. "
                            + "Open the comments to see them all.")));
            reels.addPreference(toggle(context, Settings.HIDE_REEL_THREADS_CARDS,
                    L10n.t("Hides the \"Threads you might like\" card between reels. Untested on a real account, so it starts "
                            + "off.")));
            reels.addPreference(toggle(context, Settings.REEL_CLEAN_MODE,
                    L10n.t("Opens reels and videos in Facebook's Clean mode, which hides the buttons down the side. You can "
                            + "still leave Clean mode as usual. Untested on a real account, so it starts off.")));
            reels.addPreference(toggle(context, Settings.PLAY_REELS_ONCE,
                    L10n.t("A reel stops on its last frame instead of starting over. Tap it to watch again, or swipe to "
                            + "the next one. Feed videos and stories aren't changed. Untested on a real account, so it "
                            + "starts off.")));
        }
        if (build.contains(PatchFamily.REEL_WATCH_HISTORY)) {
            reels.addPreference(toggle(context, Settings.DONT_SEND_REEL_WATCH_HISTORY,
                    L10n.t("Stops sending Facebook your list of watched reels. It uses the list to rank your feed, so reels "
                            + "you've watched may come back.")));
        }
        if (build.contains(PatchFamily.DOUBLE_TAP_LIKE)) {
            reels.addPreference(toggle(context, Settings.TURN_OFF_DOUBLE_TAP_LIKE,
                    L10n.t("A double tap on a reel or video no longer likes it or shows a heart. A single tap and the Like "
                            + "button work as before.")));
        }
        if (build.contains(PatchFamily.KEEP_REEL_SPEED)) {
            reels.addPreference(toggle(context, Settings.KEEP_REEL_SPEED,
                    L10n.t("A playback speed you pick in a reel's menu stays for the next reels until you pick another "
                            + "or Facebook restarts. Off, every reel starts at normal speed.")));
            reels.addPreference(toggle(context, Settings.KEEP_VIDEO_SPEED,
                    L10n.t("The speed you pick in a feed or Watch video's menu carries over to the next videos. Reels keep "
                            + "their own speed. Untested on a real account, so it starts off.")));
            reels.addPreference(toggle(context, Settings.SLOWER_REEL_SPEEDS,
                    L10n.t("Adds 0.1x and 0.25x to the speed menu of reels and of feed and Watch videos, for slowing down a "
                            + "fast moment.")));
        }
        if (build.contains(PatchFamily.REEL_HOLD)) {
            reels.addPreference(toggle(context, Settings.HOLD_REEL_FOR_2X,
                    L10n.t("Holding a reel plays it at double speed until you let go, in place of Facebook's long-press "
                            + "menu. The reel's more button still opens that menu.")));
            reels.addPreference(toggle(context, Settings.HOLD_REEL_RIGHT_EDGE,
                    L10n.t("With the switch above on, only a hold on the right third of a reel plays it at double "
                            + "speed. Hold anywhere else and Facebook's long-press menu opens.")));
        }
        if (build.contains(PatchFamily.REEL_DOWNLOAD)) {
            reels.addPreference(toggle(context, Settings.DOWNLOAD_REELS,
                    L10n.t("Add a Download button to reels, using your download quality. Off or paused, Facebook's own buttons return.")));
        }
    }

    /** Playback. */
    static void playback(HushfacebookPreferenceFragment page, PreferenceScreen screen, Context context,
            Set<PatchFamily> build) {
        if (build.contains(PatchFamily.TAP_TO_PLAY) || build.contains(PatchFamily.RESUME_LONG_VIDEOS)
                || build.contains(PatchFamily.PLAYBACK_QUALITY) || build.contains(PatchFamily.PICTURE_IN_PICTURE)
                || build.contains(PatchFamily.HDR_BRIGHTNESS) || build.contains(PatchFamily.PROGRESS_BAR)) {
            PreferenceCategory playback = category(screen, L10n.t("Playback"));
            if (build.contains(PatchFamily.TAP_TO_PLAY)) {
                playback.addPreference(toggle(context, Settings.TAP_TO_PLAY,
                        L10n.t("Videos, reels, stories and music wait for your tap instead of playing by themselves. Facebook's own "
                                + "Autoplay setting shows Off while this is on.")));
                playback.addPreference(toggle(context, Settings.TAP_TO_PLAY_REELS_AFTER_FIRST,
                        L10n.t("With the switch above on, after you play one reel, the reels you swipe to play on their own. The "
                                + "feed, Watch and stories always wait.")));
            }
            if (build.contains(PatchFamily.RESUME_LONG_VIDEOS)) {
                playback.addPreference(toggle(context, Settings.RESUME_LONG_VIDEOS,
                        L10n.t("Videos over two minutes pick up where you left off. Drag the bar to start elsewhere. Short reels, "
                                + "live videos and ads start as usual.")));
            }
            if (build.contains(PatchFamily.PLAYBACK_QUALITY)) {
                playback.addPreference(toggle(context, Settings.DEFAULT_PLAYBACK_QUALITY,
                        L10n.t("Play videos, reels and stories at the quality below. A quality picked in a video's own menu still wins.")));
                playback.addPreference(playbackQualityRow(context));
                playback.addPreference(surfaceQualityRow(context, true));
                playback.addPreference(surfaceQualityRow(context, false));
            }
            if (build.contains(PatchFamily.PICTURE_IN_PICTURE)) {
                playback.addPreference(toggle(context, Settings.PICTURE_IN_PICTURE,
                        L10n.t("A playing reel or full-screen video keeps going in a small window when you leave Facebook. Android 12 or later.")));
            }
            if (build.contains(PatchFamily.HDR_BRIGHTNESS)) {
                // Asked as each screen comes to the front, so a change shows from the next one.
                playback.addPreference(toggle(context, Settings.TURN_OFF_HDR_BRIGHTNESS,
                        L10n.t("HDR videos and photos stay at your screen's usual brightness instead of turning it up to "
                                + "full. They keep their resolution.")));
            }
            if (build.contains(PatchFamily.PROGRESS_BAR)) {
                playback.addPreference(toggle(context, Settings.KEEP_PROGRESS_BAR,
                        L10n.t("A reel's progress bar stays full size, ready to drag. A full-screen video's controls "
                                + "stay until you tap.")));
                playback.addPreference(toggle(context, Settings.KEEP_PROGRESS_BAR_TIME,
                        L10n.t("With Keep the progress bar on, a reel's elapsed and total time stay above the bar. "
                                + "Off, they show only while you drag it.")));
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
                        L10n.t("Add Download to phone to feed and Watch video menus. Uses the quality below. Off or paused, Facebook's menu returns.")));
                downloads.addPreference(toggle(context, Settings.CLIPBOARD_DOWNLOAD,
                        L10n.t("With Download feed and Watch videos on, coming back to Facebook with a reel or video link copied offers to download it, once per link. Off, Facebook doesn't look at what you copied.")));
            }
            if (build.contains(PatchFamily.PHOTO_DOWNLOAD)) {
                downloads.addPreference(toggle(context, Settings.DOWNLOAD_PHOTOS,
                        L10n.t("Shows Save photo on every photo you open, even where saving is turned off, and saves the biggest "
                                + "size. Off or paused, Facebook decides again.")));
                downloads.addPreference(toggle(context, Settings.POST_MENU_PHOTO_SAVE,
                        L10n.t("With Save any photo on, a post with photos gets Save photo in its three-dot menu too. A post with several saves each one, one after another.")));
            }
            // Every save reads it, a story's and a reel's as much as a feed video's, so it's here
            // whichever download patch is in, above the quality it keeps within.
            downloads.addPreference(toggle(context, Settings.DOWNLOAD_COMPATIBLE,
                    L10n.t("Fixes saved videos that play without sound in WhatsApp, video editors such as CapCut and InShot, or "
                            + "some galleries and players. May lower quality.")));
            downloads.addPreference(qualityRow(context));
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
