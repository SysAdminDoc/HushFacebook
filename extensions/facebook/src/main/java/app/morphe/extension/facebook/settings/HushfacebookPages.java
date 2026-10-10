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
import app.morphe.extension.facebook.settings.SettingsRows.Row;
import app.morphe.extension.facebook.theme.AmoledTheme;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.ClearLogBufferPreference;
import app.morphe.extension.shared.settings.preference.ExportDiagnosticReportPreference;

/**
 * The category pages about Hushfacebook itself: Updates, Appearance, Set when you patched, Pause,
 * backup and diagnostics, and About. Each method adds its section to the page
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

    /** Appearance, in every build for Text size: the font, the emoji, where the tab bar goes, dark mode, haptics, screen transitions and the app icons. */
    static void appearance(HushfacebookPreferenceFragment page, PreferenceScreen screen, Context context,
            Set<PatchFamily> build) {
        PreferenceCategory appearance = category(screen, L10n.t("Appearance"));
        // The settings entry's own, so every build has it. 100% is Facebook as it ships.
        appearance.addPreference(textSizeRow(context));
        if (build.contains(PatchFamily.ACCENT_COLOR)) {
            // Colours Facebook asks for as each screen builds, so a change shows fully after a restart.
            appearance.addPreference(accentRow(context, build.contains(PatchFamily.MATERIAL_YOU_THEME)));
        }
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
        if (build.contains(PatchFamily.BOTTOM_TAB_BAR)) {
            // Facebook places the tab bar as its main screen starts, so a change waits for a restart.
            appearance.addPreference(toggle(context, Settings.BOTTOM_TAB_BAR,
                    L10n.t("Moves Facebook's tab bar to the bottom, within thumb reach, on accounts that show it at the top. "
                            + "Restart Facebook to see the change.")));
            // Facebook adds the bar to the views that scroll away as its main screen starts.
            appearance.addPreference(toggle(context, Settings.TAB_BAR_SCROLL_AWAY,
                    L10n.t("The bottom tab bar hides as you scroll down and returns when you scroll up, for more room. Restart "
                            + "Facebook to see the change.")));
        }
        if (build.contains(PatchFamily.HIDDEN_TABS)) {
            // Facebook builds the tab bar once, so a change waits for a restart.
            for (HiddenTabs.Tab tab : HiddenTabs.Tab.values()) {
                appearance.addPreference(toggle(context, tab.setting(),
                        L10n.t("Removes this tab from the tab bar. Its page is still in the Menu. Restart Facebook to see the "
                                + "change.")));
            }
        }
        if (build.contains(PatchFamily.TAB_BADGES)) {
            // The tab bar asks for each tab's count as it changes, so a change shows the next time it asks.
            for (TabBadges.Tab tab : TabBadges.Tab.values()) {
                appearance.addPreference(toggle(context, tab.setting(), tab == TabBadges.Tab.OTHER
                        ? L10n.t("No dot or count on Feeds, Gaming, Events and the other tabs not listed here. "
                                + "The Reels tab has its own switch.")
                        : L10n.t("No dot or count on this tab. Its page still shows what's new when you open it.")));
            }
            appearance.addPreference(toggle(context, Settings.HIDE_APP_ICON_COUNT,
                    L10n.t("No number on Facebook's app icon. Notifications still arrive. Some launchers add their own count, "
                            + "which this can't remove.")));
        }
        if (build.contains(PatchFamily.FORCE_DARK_MODE)) {
            // Facebook asks for dark mode as each screen applies its theme, so a change shows fully after a restart.
            appearance.addPreference(toggle(context, Settings.FORCE_DARK_MODE,
                    L10n.t("Keeps Facebook dark no matter its own setting. Meant for tablets where Facebook has no Dark mode "
                            + "switch. Restart Facebook to see the change.")));
        }
        if (build.contains(PatchFamily.HAPTICS)) {
            appearance.addPreference(toggle(context, Settings.TURN_OFF_HAPTICS,
                    L10n.t("Stops the small vibrations Facebook makes when you tap or swipe. Your keyboard and phone vibrations "
                            + "aren't affected.")));
        }
        if (build.contains(PatchFamily.SCREEN_TRANSITIONS)) {
            // Asked at each tap and each screen change, so a change shows from the next one.
            appearance.addPreference(toggle(context, Settings.TURN_OFF_SCREEN_TRANSITIONS,
                    L10n.t("Tabs, the Menu and screens that open over Facebook appear at once instead of sliding in. Swiping "
                            + "between tabs still works.")));
        }
        if (build.contains(PatchFamily.APP_ICONS)) {
            // The App icon page asks as it opens. A picked icon is a launcher alias, so it outlives the switch.
            appearance.addPreference(toggle(context, Settings.UNLOCK_APP_ICONS,
                    L10n.t("Lets you pick the app icons Facebook keeps for Facebook Plus on its App icon page. Your pick "
                            + "stays if you turn this off, so pick the blue one first to go back.")));
        }
    }

    /** Set when you patched: what the patches picked in Morphe Manager do, which no switch changes. */
    static void patched(HushfacebookPreferenceFragment page, PreferenceScreen screen, Context context,
            Set<PatchFamily> build) {
        if (build.contains(PatchFamily.AD_PREFETCH) || build.contains(PatchFamily.AD_TELEMETRY)
                || build.contains(PatchFamily.AUDIENCE_NETWORK) || build.contains(PatchFamily.AMOLED_THEME)
                || build.contains(PatchFamily.MATERIAL_YOU_THEME) || build.contains(PatchFamily.RESTORE_TRUST)
                || build.contains(PatchFamily.INSTALL_BESIDE_META_APPS)) {
            PreferenceCategory patched = category(screen, L10n.t("Set when you patched"));
            if (build.contains(PatchFamily.AD_PREFETCH)) {
                patched.addPreference(mark(info(context, L10n.t("Ads aren't downloaded in advance"),
                        L10n.t("Facebook no longer downloads ads, or the data it uses to pick them, in the background.")), SettingsIcons.BLOCK));
            }
            if (build.contains(PatchFamily.AD_TELEMETRY)) {
                patched.addPreference(mark(info(context, L10n.t("Ad tracking blocked"),
                        L10n.t("Facebook doesn't watch for screenshots to target ads, and doesn't report which apps you install.")), SettingsIcons.TELEMETRY));
            }
            if (build.contains(PatchFamily.AUDIENCE_NETWORK)) {
                patched.addPreference(mark(info(context, L10n.t("Audience Network off"),
                        L10n.t("Facebook doesn't show its ads inside other apps on this phone.")), SettingsIcons.NETWORK));
            }
            if (build.contains(PatchFamily.AMOLED_THEME)) {
                patched.addPreference(mark(info(context, L10n.t("AMOLED black theme"),
                        amoledSummary(AmoledTheme.backgroundColour())), SettingsIcons.MOON));
            }
            if (build.contains(PatchFamily.MATERIAL_YOU_THEME)) {
                patched.addPreference(mark(info(context, L10n.t("Material You theme"),
                        L10n.t("Dark mode and this screen use colors from your wallpaper (blue on Android 11). Turn on dark mode in "
                                + "Facebook to see it.")), SettingsIcons.APPEARANCE));
            }
            if (build.contains(PatchFamily.RESTORE_TRUST)) {
                patched.addPreference(mark(info(context, L10n.t("Profiles and posts work again"),
                        L10n.t("On a patched Facebook, profiles, photos, posts and some Settings pages wouldn't open. This fixes "
                                + "that.")),
                        SettingsIcons.BUILD));
            }
            if (build.contains(PatchFamily.INSTALL_BESIDE_META_APPS)) {
                patched.addPreference(mark(info(context, L10n.t("Meta's apps can install beside this one"),
                        L10n.t("Messenger, Facebook Lite, Business Suite and Workplace can install next to this Facebook. Their "
                                + "shared permissions no longer clash.")), SettingsIcons.PHONE));
            }
            patched.addPreference(info(context, L10n.t("Changing these"),
                    L10n.t("They're chosen in Morphe Manager when you patch, and Pause doesn't turn them off. "
                            + "Patch again to change them.")));
        }
    }

    /** Pause, backup and diagnostics, in every build. */
    static void pause(HushfacebookPreferenceFragment page, PreferenceScreen screen, Context context,
            Set<PatchFamily> build) {
        // Named for its rows: the screen's own title already says Hushfacebook.
        PreferenceCategory hushfacebook = category(screen, L10n.t("Pause, backup and diagnostics"));
        hushfacebook.addPreference(mark(toggle(context, BaseSettings.PAUSED, L10n.t("Pause Hushfacebook"),
                L10n.t("Try Facebook without Hushfacebook. From the next start, switches act as if off, except Debug "
                        + "logging and Lock Facebook. Your choices stay saved.")),
                SettingsIcons.PATCHED));
        String stays = PatchFamily.staysWhilePausedSummary(build);
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
            link.setSummary(L10n.t("Checks now whether Facebook can reach Messenger, the way it does at startup, and shows each result. "
                    + "Appears while Debug logging is on."));
            link.setPersistent(false);
            link.setOnPreferenceClickListener(p -> {
                MessengerLinkCheck.start(context);
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
        if (stays != null) hushfacebook.addPreference(info(context, L10n.t(STAYS_WHILE_PAUSED), stays));
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
        ScreenColors colors = ScreenColors.shown == null ? ScreenColors.DEFAULT : ScreenColors.shown;
        text.setTextColor(colors.summary);
        text.setLinkTextColor(colors.heading);
        Linkify.addLinks(text, Linkify.WEB_URLS);
        page.show(new AlertDialog.Builder(context)
                .setTitle(L10n.t("Licenses"))
                .setView(scroll)
                .setPositiveButton(L10n.t("OK"), null));
    }
}
