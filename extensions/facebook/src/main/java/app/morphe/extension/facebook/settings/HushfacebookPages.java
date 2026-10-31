/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.morphe.extension.facebook.settings;

import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.SOURCE_ADDRESS;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.SOURCE_URL;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.STAYS_WHILE_PAUSED;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.amoledSummary;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.category;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.info;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.mark;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.subheading;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.accentRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.textSizeRow;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.toggle;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.text.Layout;
import android.text.util.Linkify;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Set;

import app.morphe.extension.facebook.navigation.HiddenTabs;
import app.morphe.extension.facebook.navigation.TabBadges;
import app.morphe.extension.facebook.coexist.MessengerLinkCheck;
import app.morphe.extension.facebook.settings.SettingsRows.BackupRow;
import app.morphe.extension.facebook.settings.SettingsRows.ClearRow;
import app.morphe.extension.facebook.settings.SettingsRows.ExportRow;
import app.morphe.extension.facebook.settings.SettingsRows.FontRow;
import app.morphe.extension.facebook.settings.SettingsRows.PictureRow;
import app.morphe.extension.facebook.settings.SettingsRows.Row;
import app.morphe.extension.facebook.theme.AmoledTheme;
import app.morphe.extension.facebook.theme.MarkColors;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.ClearLogBufferPreference;
import app.morphe.extension.shared.settings.preference.ExportDiagnosticReportPreference;

/**
 * The category pages about Hushfacebook itself: Updates, Tabs and navigation, Appearance, Built-in
 * changes, Pause, backup and diagnostics, and About. Each method adds its section to the page
 * {@link HushfacebookPreferenceFragment#initialize} builds, and leaves it out when the build has none of
 * its patches, but for the sections every build has.
 */
@SuppressWarnings("deprecation")
final class HushfacebookPages {
    private HushfacebookPages() {
    }

    /** Updates, in every build, with the release check. */
    static void updates(HushfacebookPreferenceFragment page, PreferenceScreen screen, Context context,
            Set<PatchFamily> build) {
        // In every build: the release check is the settings entry's own, not a patch's. Its switch
        // is one Pause turns off, so it sits above the Pause row with the rest.
        PreferenceCategory updates = category(screen, L10n.t("Updates"));
        if (build.contains(PatchFamily.UPDATE_PROMPTS)) {
            updates.addPreference(toggle(context, Settings.STOP_UPDATE_PROMPTS,
                    L10n.t("Stops update nagging through Meta App Manager and hides chat promotions for older versions. A "
                            + "patched Facebook can't use Meta's updates anyway.")));
        }
        updates.addPreference(toggle(context, Settings.CHECK_FOR_RELEASES,
                L10n.t("Once a day, when Facebook starts, checks GitHub for a newer Hushfacebook and tells you at the top "
                        + "of this page. Nothing downloads on its own.")));
        updates.addPreference(page.checkNowRow(context));
        ReleaseCheck.watch(page);
    }

    /**
     * Tabs and navigation: where the tab bar goes and what it does as you scroll, the top bar's
     * buttons, the tabs to hide and the dots on them. Left out when the build has none of them.
     */
    static void tabs(HushfacebookPreferenceFragment page, PreferenceScreen screen, Context context,
            Set<PatchFamily> build) {
        if (!build.contains(PatchFamily.BOTTOM_TAB_BAR) && !build.contains(PatchFamily.HIDDEN_TABS)
                && !build.contains(PatchFamily.TAB_BADGES)) {
            return;
        }
        PreferenceCategory tabs = category(screen, L10n.t("Tabs and navigation"));
        if (build.contains(PatchFamily.BOTTOM_TAB_BAR)) {
            subheading(tabs, L10n.t("Tab bar"));
            // Facebook places the tab bar, and adds it to the views that scroll away, as its main
            // screen starts, so both choices wait for a restart. Each row saves its two switches.
            tabs.addPreference(TabBarChoices.row(page, context, TabBarChoices.Pair.POSITION));
            tabs.addPreference(TabBarChoices.row(page, context, TabBarChoices.Pair.SCROLLING));
            // Facebook decides on its big screen layout as its main screen starts, so a change waits for a restart.
            tabs.addPreference(toggle(context, Settings.PHONE_LAYOUT_ON_BIG_SCREENS,
                    L10n.t("A tablet or an unfolded phone gets the layout Facebook uses on a phone, so the tab bar choices "
                            + "above work on the big screen too. Facebook's wider big screen layout goes away. Off by "
                            + "default, since it hasn't been tried on a big screen yet. Restart Facebook to see the "
                            + "change.")));
            subheading(tabs, L10n.t("Top bar"));
            // Asked each time Facebook moves the top bar, so it takes effect on the next scroll.
            tabs.addPreference(toggle(context, Settings.KEEP_TOP_BAR,
                    L10n.t("The top bar with search and Messenger stays put while you scroll down, instead of sliding away.")));
            // Facebook builds the top bar as the main screen starts, so these two wait for a restart.
            tabs.addPreference(toggle(context, Settings.HIDE_MESSENGER_BUTTON,
                    L10n.t("Takes the Messenger button out of the top bar, and the buttons beside it close up the gap. "
                            + "Restart Facebook to see the change.")));
            // The count is set each time Facebook updates it, so a change shows with the next count.
            tabs.addPreference(toggle(context, Settings.HIDE_MESSENGER_COUNT,
                    L10n.t("Keeps the Messenger button but drops the unread count on it.")));
            tabs.addPreference(toggle(context, Settings.HIDE_FACEBOOK_LOGO,
                    L10n.t("Hides the Facebook logo in the top bar. The empty spot is the same size and still opens "
                            + "Hushfacebook settings when you press and hold it. Restart Facebook to see the change.")));
            tabs.addPreference(toggle(context, Settings.HIDE_CREATE_BUTTON,
                    L10n.t("Takes the + button for making a post, reel or story out of the top bar. "
                            + "Restart Facebook to see the change.")));
            tabs.addPreference(toggle(context, Settings.HIDE_SEARCH_BUTTON,
                    L10n.t("Takes the search button out of the top bar. Restart Facebook to see the change.")));
            tabs.addPreference(toggle(context, Settings.SEARCH_WITH_PICTURE,
                    L10n.t("Press and hold the search button to open Google Lens, then point the camera at something "
                            + "or pick a picture to find out what it is. The picture goes to Google, not Facebook. "
                            + "Restart Facebook to see the change.")));
        }
        if (build.contains(PatchFamily.HIDDEN_TABS)) {
            subheading(tabs, L10n.t("Tabs to hide"));
            // Facebook builds the tab bar once, so a change waits for a restart.
            for (HiddenTabs.Tab tab : HiddenTabs.Tab.values()) {
                tabs.addPreference(toggle(context, tab.setting(),
                        L10n.t("Removes this tab from the tab bar. Its page is still in the Menu. Restart Facebook to see the "
                                + "change.")));
            }
        }
        if (build.contains(PatchFamily.TAB_BADGES)) {
            subheading(tabs, L10n.t("Dots and counts"));
            // The tab bar asks for each tab's count as it changes, so a change shows the next time it asks.
            for (TabBadges.Tab tab : TabBadges.Tab.values()) {
                tabs.addPreference(toggle(context, tab.setting(), tab == TabBadges.Tab.OTHER
                        ? L10n.t("No dot or count on Feeds, Gaming, Events and the other tabs not listed here. "
                                + "The Reels tab has its own switch.")
                        : L10n.t("No dot or count on this tab. Its page still shows what's new when you open it.")));
            }
            tabs.addPreference(toggle(context, Settings.HIDE_APP_ICON_COUNT,
                    L10n.t("No number on Facebook's app icon. Notifications still arrive. Some launchers add their own count, "
                            + "which this can't remove.")));
        }
    }

    /** Appearance, in every build for Text size: the font, the emoji, the colors, the bars, motion and the app icons. */
    static void appearance(HushfacebookPreferenceFragment page, PreferenceScreen screen, Context context,
            Set<PatchFamily> build) {
        PreferenceCategory appearance = category(screen, L10n.t("Appearance"));
        subheading(appearance, L10n.t("Text and emoji"));
        // The settings entry's own, so every build has it. 100% is Facebook as it ships.
        appearance.addPreference(textSizeRow(context));
        if (build.contains(PatchFamily.SYSTEM_FONT)) {
            appearance.addPreference(toggle(context, Settings.USE_SYSTEM_FONT,
                    L10n.t("Draws Facebook's text in your phone's font, or in a font file you choose below. Restart Facebook to "
                            + "see the change.")));
            // The file the switch draws in, and, while one is picked, the way back to the phone's font.
            // The way back goes in once whatever is picked, so it keeps its place right after Font file
            // when a pick brings it back, rather than landing at the end of the section.
            FontRow choose = new FontRow(page, context, FontFilePreference.CHOOSE);
            choose.wayBack = new FontRow(page, context, FontFilePreference.PHONE_FONT);
            appearance.addPreference(choose);
            appearance.addPreference(choose.wayBack);
            choose.show();
        }
        if (build.contains(PatchFamily.SYSTEM_EMOJI)) {
            // The quick emoji picker keeps the first typeface it's given until Facebook restarts.
            appearance.addPreference(toggle(context, Settings.USE_SYSTEM_EMOJI,
                    L10n.t("Shows your phone's own emoji instead of Facebook's. Reactions and stickers don't change. Restart "
                            + "Facebook to see the change.")));
        }
        subheading(appearance, L10n.t("Colors and theme"));
        if (build.contains(PatchFamily.ACCENT_COLOR)) {
            // Colours Facebook asks for as each screen builds, so a change shows fully after a restart.
            appearance.addPreference(accentRow(context, build.contains(PatchFamily.MATERIAL_YOU_THEME)));
        }
        if (build.contains(PatchFamily.MARK_COLORS)) {
            // Colours Facebook asks for as each screen builds, so a change shows fully after a restart.
            appearance.addPreference(page.markColorRow(context, MarkColors.Mark.BADGE));
            appearance.addPreference(page.markColorRow(context, MarkColors.Mark.LOGO));
            appearance.addPreference(page.markColorRow(context, MarkColors.Mark.TAB));
        }
        if (build.contains(PatchFamily.FORCE_DARK_MODE)) {
            // Facebook asks for dark mode as each screen applies its theme, so a change shows fully after a restart.
            appearance.addPreference(toggle(context, Settings.FORCE_DARK_MODE,
                    L10n.t("Keeps Facebook dark no matter its own setting. Meant for tablets where Facebook has no Dark mode "
                            + "switch. Restart Facebook to see the change.")));
        }
        if (build.contains(PatchFamily.AMOLED_THEME)) {
            // Read once as Facebook starts (#214). Material You recolours the same greys, light mode's too,
            // so it has neither row there.
            if (!build.contains(PatchFamily.MATERIAL_YOU_THEME)) {
                appearance.addPreference(page.backgroundRow(context));
                appearance.addPreference(page.lightBackgroundRow(context));
            }
            // Colours Facebook asks for as each screen builds, so a change shows fully after a restart.
            appearance.addPreference(toggle(context, Settings.AMOLED_FLAT_CARDS,
                    L10n.t("Cards and menus take the page's own color instead of the near black that sets them apart. "
                            + "Buttons and search fields keep a dark gray fill, so you can still see them. Restart "
                            + "Facebook to see the change.")));
            // Rows read it as they draw, so the next posts that scroll in show it.
            appearance.addPreference(toggle(context, Settings.AMOLED_FEED_GAPS,
                    L10n.t("A thin gray line between posts in the feed, so posts without photos don't run together on "
                            + "the black page.")));
        } else if (!build.contains(PatchFamily.MATERIAL_YOU_THEME)) {
            // Morphe Manager leaves the theme out unless it's picked, so dark mode staying gray says why (#275).
            appearance.addPreference(info(context, L10n.t(FamilyNames.AMOLED_THEME),
                    L10n.f("Not in this build, so dark mode stays Facebook's dark gray. Morphe Manager doesn't pick it "
                            + "by default. Turn on Expert mode in Manager, choose the %1$s patch and patch again.",
                            L10n.isolate(FamilyNames.AMOLED_THEME))));
        }
        subheading(appearance, L10n.t("Screen and motion"));
        if (build.contains(PatchFamily.BOTTOM_TAB_BAR)) {
            // Asked as each frame of the main screen draws, so a change shows right away, except on
            // a kept tab bar, where the page's restart row says the change waits (#250).
            appearance.addPreference(toggle(context, Settings.TRANSLUCENT_BARS,
                    L10n.t("The top bar and the tab bar turn see-through, so the feed shows through them as you scroll. "
                            + "On Android 12 and newer the feed behind them is blurred.")));
            // Asked as the main screen gets its focus back, so a change shows when these settings close.
            appearance.addPreference(toggle(context, Settings.HIDE_STATUS_BAR,
                    L10n.t("Takes the phone's status bar, with the time and battery, off Facebook's main screen, so "
                            + "the app gets the whole screen. Swipe down from the top edge to see it for a moment.")));
        }
        if (build.contains(PatchFamily.CARRIER_BANNERS)) {
            // Asked each time Facebook shows its carrier banner, which it does as screens open and the mode changes.
            appearance.addPreference(toggle(context, Settings.HIDE_DATA_MODE_BANNER,
                    L10n.t("On carriers with Facebook Flex, hides the Data mode bar under the top bar and its button to "
                            + "buy mobile data.")));
            appearance.addPreference(toggle(context, Settings.HIDE_FREE_MODE_BANNER,
                    L10n.t("On carriers with Facebook Flex, hides the purple Free mode bar. That bar is also how you go "
                            + "back to Data mode, so turn this off when you want to switch.")));
        }
        if (build.contains(PatchFamily.HAPTICS)) {
            appearance.addPreference(toggle(context, Settings.TURN_OFF_HAPTICS,
                    L10n.t("Stops the small vibrations Facebook makes when you tap or swipe. Your keyboard and phone vibrations "
                            + "aren't affected.")));
        }
        if (build.contains(PatchFamily.SCREEN_TRANSITIONS)) {
            // Asked at each tap and each screen change, so a change shows from the next one.
            appearance.addPreference(toggle(context, Settings.TURN_OFF_SCREEN_TRANSITIONS,
                    L10n.t("Tabs, the Menu, photos and screens that open over Facebook appear at once instead of sliding or "
                            + "zooming in. Swiping between tabs still works.")));
        }
        subheading(appearance, L10n.t("App icon and header"));
        if (build.contains(PatchFamily.APP_ICONS)) {
            // The App icon page asks as it opens. A picked icon is a launcher alias, so it outlives the switch.
            appearance.addPreference(toggle(context, Settings.UNLOCK_APP_ICONS,
                    L10n.t("Lets you pick the app icons Facebook keeps for Facebook Plus, on Facebook's App icon page. "
                            + "The Pick an app icon row opens that page. Your pick stays if you turn this off, so pick the "
                            + "blue one first to go back.")));
            // Facebook builds the Home header once, so a change waits for a restart. Needs the unlock above.
            appearance.addPreference(toggle(context, Settings.USE_APP_ICON_IN_HEADER,
                    L10n.t("Shows the app icon you picked at the top of Home instead of the Facebook wordmark. Needs "
                            + "Unlock app icons and a pick other than the blue one. Restart Facebook to see the change."))
                    .needs(Settings.UNLOCK_APP_ICONS));
            appearance.addPreference(AppIconScreen.row(context));
        }
        if (build.contains(PatchFamily.HEADER_TEXT)) {
            // Facebook builds the Home header once, so a change waits for a restart.
            appearance.addPreference(HushfacebookPreferenceFragment.headerTextRow(context));
            PictureRow picture = new PictureRow(page, context, HeaderPicturePreference.CHOOSE);
            picture.wayBack = new PictureRow(page, context, HeaderPicturePreference.REMOVE);
            // The Remove row goes in once, as Font file's way back does, so it keeps its place after a pick.
            appearance.addPreference(picture);
            appearance.addPreference(picture.wayBack);
            picture.show();
        }
    }

    /** Built-in changes: what the patches picked in Morphe Manager do, which no switch changes. */
    static void patched(HushfacebookPreferenceFragment page, PreferenceScreen screen, Context context,
            Set<PatchFamily> build) {
        if (build.contains(PatchFamily.AD_PREFETCH) || build.contains(PatchFamily.AD_TELEMETRY)
                || build.contains(PatchFamily.AUDIENCE_NETWORK) || build.contains(PatchFamily.AMOLED_THEME)
                || build.contains(PatchFamily.MATERIAL_YOU_THEME) || build.contains(PatchFamily.RESTORE_TRUST)
                || build.contains(PatchFamily.INSTALL_BESIDE_META_APPS)) {
            PreferenceCategory patched = category(screen, L10n.t("Built-in changes"));
            if (build.contains(PatchFamily.AD_PREFETCH)) {
                patched.addPreference(mark(info(context, L10n.t(PatchFamily.AD_PREFETCH.staysWhilePaused),
                        L10n.t("Facebook no longer downloads ads, or the data it uses to pick them, in the background.")), SettingsIcons.BLOCK));
            }
            if (build.contains(PatchFamily.AD_TELEMETRY)) {
                patched.addPreference(mark(info(context, L10n.t(PatchFamily.AD_TELEMETRY.staysWhilePaused),
                        L10n.t("Facebook doesn't watch for screenshots to target ads, and doesn't report which apps you install.")), SettingsIcons.TELEMETRY));
            }
            if (build.contains(PatchFamily.AUDIENCE_NETWORK)) {
                patched.addPreference(mark(info(context, L10n.t(PatchFamily.AUDIENCE_NETWORK.staysWhilePaused),
                        L10n.t("Facebook doesn't show its ads inside other apps on this phone.")), SettingsIcons.NETWORK));
            }
            if (build.contains(PatchFamily.AMOLED_THEME)) {
                patched.addPreference(mark(info(context, L10n.t(PatchFamily.AMOLED_THEME.staysWhilePaused),
                        amoledSummary(AmoledTheme.background())), SettingsIcons.MOON));
            }
            if (build.contains(PatchFamily.MATERIAL_YOU_THEME)) {
                patched.addPreference(mark(info(context, L10n.t(PatchFamily.MATERIAL_YOU_THEME.staysWhilePaused),
                        L10n.t("Dark mode and this screen use colors from your wallpaper (blue on Android 11). Turn on dark mode in "
                                + "Facebook to see it.")), SettingsIcons.APPEARANCE));
            }
            if (build.contains(PatchFamily.RESTORE_TRUST)) {
                patched.addPreference(mark(info(context, L10n.t(PatchFamily.RESTORE_TRUST.staysWhilePaused),
                        L10n.t("On a patched Facebook, profiles, photos, posts and some Settings pages wouldn't open. This fixes "
                                + "that.")),
                        SettingsIcons.BUILD));
            }
            if (build.contains(PatchFamily.INSTALL_BESIDE_META_APPS)) {
                patched.addPreference(mark(info(context, L10n.t(PatchFamily.INSTALL_BESIDE_META_APPS.staysWhilePaused),
                        L10n.t("Messenger, Facebook Lite, Business Suite and Workplace can install next to this Facebook. Their "
                                + "shared permissions no longer clash.")), SettingsIcons.PHONE));
            }
            patched.addPreference(mark(info(context, L10n.t("Changing these"),
                    L10n.t("They're chosen in Morphe Manager when you patch, and Pause doesn't turn them off. "
                            + "Patch again to change them.")), SettingsIcons.ABOUT));
        }
    }

    /** Pause, backup and diagnostics, in every build. */
    static void pause(HushfacebookPreferenceFragment page, PreferenceScreen screen, Context context,
            Set<PatchFamily> build) {
        // Named for its rows: the screen's own title already says Hushfacebook.
        PreferenceCategory hushfacebook = category(screen, L10n.t("Pause, backup and diagnostics"));
        String stays = PatchFamily.staysWhilePausedSummary(build);
        subheading(hushfacebook, L10n.t("Backup and restore"));
        // Morphe Manager can export the patch choices and the signing key, not these switches.
        hushfacebook.addPreference(mark(new BackupRow(page, context, SettingsBackupPreference.EXPORT,
                L10n.t("Export settings"),
                L10n.t("Saves your switches and download choices to a file, to back up or copy to another phone. Pause, "
                        + "Debug logging and the update check aren't included.")), SettingsIcons.EXPORT));
        // The preview gives a count of the switches and the download settings' new values, not
        // each switch by name.
        hushfacebook.addPreference(mark(new BackupRow(page, context, SettingsBackupPreference.IMPORT,
                L10n.t("Import settings"),
                L10n.t("Choose a settings file you saved earlier. You'll see how many switches it changes before anything "
                        + "is applied.")), SettingsIcons.DOWNLOADS));
        subheading(hushfacebook, L10n.t("Troubleshooting"));
        // The settings entry's own: it's what a Facebook that won't open can still send.
        hushfacebook.addPreference(mark(toggle(context, Settings.SAVE_CRASH_FILE,
                L10n.t("When Facebook crashes, the error goes in a text file in Download/Hushfacebook, so you can send it with a "
                        + "bug report even if Facebook won't open. Links and IDs are left out. The newest five are kept.")),
                SettingsIcons.BUG));
        // Pause is a troubleshooting step too. Every switch it turns off sits above it, crash saving
        // included, and what stays on while paused sits below it.
        hushfacebook.addPreference(mark(toggle(context, BaseSettings.PAUSED, L10n.t("Pause Hushfacebook"),
                L10n.t("Try Facebook without Hushfacebook. From the next start, switches act as if off, except Debug "
                        + "logging and Lock Facebook. Your choices stay saved.")),
                SettingsIcons.PATCHED));
        // Debug logging also fills the exported report and turns on error toasts (Logger).
        hushfacebook.addPreference(mark(toggle(context, BaseSettings.DEBUG, L10n.t("Debug logging"),
                L10n.t("Records what the patches do and shows error messages, to help with a bug report. Leave it off for "
                        + "everyday use.")), SettingsIcons.BUG));
        // A test for Debug logging only: a Messenger patched with this build's key, asked now
        // instead of on Facebook's own schedule. Restore screens fills it in. The screen is built
        // once, so the row comes and goes when settings open again after Debug logging changes: a
        // row this page can only disable would sit greyed out in every build with Restore screens.
        if (build.contains(PatchFamily.RESTORE_TRUST) && BaseSettings.DEBUG.get() && MessengerLinkCheck.available()) {
            Preference link = new Row(context);
            link.setTitle(L10n.t("Test the Messenger link"));
            String about = L10n.t("Checks now whether Facebook can reach Messenger, the way it does at startup, and shows each result. "
                    + "Appears while Debug logging is on.");
            link.setSummary(about);
            link.setPersistent(false);
            // The result goes under the description, whole: a toast that long gets cut on some phones.
            link.setOnPreferenceClickListener(p -> {
                MessengerLinkCheck.start(context, report -> p.setSummary(about + "\n" + report));
                return true;
            });
            hushfacebook.addPreference(mark(link, SettingsIcons.BUG));
        }
        // Both rows come without a title of their own: Hushfeed's gave them one from string
        // resources that Facebook's APK doesn't have, and untitled they showed as blank rows.
        ExportDiagnosticReportPreference export = new ExportRow(context);
        export.setTitle(L10n.t("Export diagnostic report"));
        export.setSummary(L10n.t("Copy or save a report to send with a bug report. Links, IDs, cookies and sign-in details are left "
                + "out. Check it before sharing."));
        hushfacebook.addPreference(mark(export, SettingsIcons.LICENSE));
        ClearLogBufferPreference clear = new ClearRow(context);
        clear.setTitle(L10n.t("Clear diagnostic data"));
        clear.setClearAndUndoSummaries(L10n.t("Erases the activity log and the counts of hidden items that a report would include."),
                L10n.t("Diagnostic data cleared. Tap again to put it back."));
        hushfacebook.addPreference(mark(clear, SettingsIcons.DELETE));
        // Facebook's own traffic tools (user certificates, proxy, TLS 1.3) sit with the report: both
        // are for someone looking into what the app does. About only describes Hushfacebook.
        hushfacebook.addPreference(mark(WhitehatScreen.row(context), SettingsIcons.NETWORK));
        // Keep the detailed patch-time exception list after the controls people come here for.
        if (stays != null) hushfacebook.addPreference(mark(info(context, L10n.t(STAYS_WHILE_PAUSED), stays), SettingsIcons.PATCHED));
    }

    /** About, in every build. */
    static void about(HushfacebookPreferenceFragment page, PreferenceScreen screen, Context context,
            Set<PatchFamily> build) {
        PreferenceCategory about = category(screen, L10n.t("About"));
        about.addPreference(mark(info(context, L10n.t("Version"), L10n.f("Hushfacebook %1$s on Facebook %2$s",
                L10n.isolate(Utils.getPatchesReleaseVersion()), L10n.isolate(Utils.getAppVersionName()))
                + "\n" + L10n.f("Build %1$s", L10n.isolate(Utils.getPatchesBuildIdentity()))), SettingsIcons.ABOUT));

        Preference source = new Row(context);
        source.setTitle(L10n.t("Source code and issues"));
        source.setSummary(SOURCE_ADDRESS);
        source.setPersistent(false);
        source.setOnPreferenceClickListener(p -> {
            try {
                page.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)));
            } catch (ActivityNotFoundException | SecurityException missing) {
                // No browser, or none switched on. Uncaught, Android's exception closed Facebook.
                Logger.printInfo(() -> "No app opened the source code link");
                Utils.showToastLong(L10n.f("No app on this phone can open the link. The address is %1$s.",
                        L10n.isolate(SOURCE_ADDRESS)));
            }
            return true;
        });
        about.addPreference(mark(source, SettingsIcons.OPENING));

        // Section 7b asks that its notice reach the person using the software, and a file in the
        // repository does not reach them.
        Preference licenses = new Row(context);
        licenses.setTitle(L10n.t("Licenses"));
        licenses.setSummary(L10n.t("The GPL-3.0 license, plus notices for the projects this is built on"));
        licenses.setPersistent(false);
        licenses.setOnPreferenceClickListener(p -> {
            showNotice(page, context);
            return true;
        });
        about.addPreference(mark(licenses, SettingsIcons.LICENSE));
    }

    /** Where Support Hushfacebook goes: the maintainer's Ko-fi page. */
    static final String SUPPORT_URL = "https://ko-fi.com/X8K126YVER";

    /** SUPPORT_URL without its scheme, for the toast. Worked out here, so no literal sits in the L10n call. */
    static final String SUPPORT_ADDRESS = SUPPORT_URL.substring(SUPPORT_URL.indexOf("://") + 3);

    /** The key of Support Hushfacebook, the settings home page's last row. */
    static final String SUPPORT = "action_support_hushfacebook";

    /** The Ko-fi page, for a browser, in a task of its own so it never opens inside Facebook's. */
    static Intent supportIntent() {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(SUPPORT_URL))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Opens the Ko-fi page, or says no app here can, as Source code and issues does. */
    static void openSupport(Context context) {
        try {
            context.startActivity(supportIntent());
        } catch (ActivityNotFoundException | SecurityException missing) {
            // No browser, or none switched on. Uncaught, Android's exception closed Facebook.
            Logger.printInfo(() -> "No app opened the support link");
            Utils.showToastLong(L10n.f("No app on this phone can open the link. The address is %1$s.",
                    L10n.isolate(SUPPORT_ADDRESS)));
        }
    }

    /**
     * The notice itself stays in English, as the licence texts it carries are: a translation of
     * the GPL is not the licence.
     */
    private static void showNotice(HushfacebookPreferenceFragment page, Context context) {
        TextView text = new TextView(context);
        // NOTICE is hard-wrapped for a source file. Reflow prose on a narrow screen while keeping
        // blank lines, headings, lists and the generated notice itself intact.
        String notice = LicenseNotice.TEXT.replaceAll("(?<=[\\p{L}.,;])\\n(?=\\p{L})", " ")
                .replaceAll("(?m)^(  .+?) {2,}(https?://[^\\n]+)$", "$1\n$2\n");
        text.setText(notice);
        text.setTextIsSelectable(true);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        text.setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY);
        int pad = Math.round(16 * context.getResources().getDisplayMetrics().density);
        text.setPadding(pad, pad, pad, pad);
        text.setLineSpacing(Math.round(2 * context.getResources().getDisplayMetrics().density), 1.04f);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(text);
        ScreenColors colors = ScreenColors.current();
        text.setTextColor(colors.summary);
        text.setLinkTextColor(colors.heading);
        Linkify.addLinks(text, Linkify.WEB_URLS);
        page.show(new AlertDialog.Builder(context)
                .setTitle(L10n.t("Licenses"))
                .setView(scroll)
                .setPositiveButton(L10n.t("OK"), null));
    }
}
