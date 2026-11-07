/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * What scripts/device-audit drives over adb: a settings file applied at Facebook's start, a
 * settings page opened from an intent, and the diagnostic report written where adb can pull it.
 *
 * <p>All of it waits for a folder named {@link #FOLDER} in Facebook's external files folder.
 * Since Android 11 only Facebook itself and adb can write there, so the extras below do nothing
 * for another app that sends them, and nothing at all on a phone nobody set up for an audit.
 *
 * <p>The folder's files:
 * <ul>
 *   <li>{@code settings.json}, read at the next start and renamed {@code settings.done.json}.
 *       Either a backup file the settings screen exports, or
 *       {@code {"format":"hushfacebook-audit","profile":"all-on"|"defaults","on":[],"off":[]}}.
 *   <li>{@code import.txt}, what the last import did.
 *   <li>{@code before.json}, the switches as they were before the first import, written once, so
 *       the audit can put them back afterwards.
 *   <li>{@code report.txt}, the full diagnostic report, written for {@link #EXTRA_REPORT}.
 * </ul>
 */
public final class AuditMode {
    static final String FOLDER = "hushfacebook-audit";
    static final String EXTRA_REPORT = "app.morphe.extension.facebook.AUDIT_REPORT";
    static final String EXTRA_PAGE = "app.morphe.extension.facebook.AUDIT_PAGE";

    static final String AUDIT_FORMAT = "hushfacebook-audit";
    static final String PROFILE_ALL_ON = "all-on";
    static final String PROFILE_DEFAULTS = "defaults";

    /**
     * Switches all-on leaves at their defaults, since each one stops a tour of the app from
     * seeing what it should: a single kept tab, a lock over every screen, a Facebook that closes
     * whenever the tour opens another app, a cover before every launch or after half an hour of
     * feeds, links and chats handed to
     * other apps, whole kinds of posts gone from the feed, and the second tab bar position.
     */
    static final List<BooleanSetting> ALL_ON_EXCLUDED = Collections.unmodifiableList(Arrays.asList(
            Settings.MARKETPLACE_ONLY,
            Settings.EVENTS_ONLY,
            Settings.TOP_TAB_BAR,
            Settings.APP_LOCK,
            Settings.CLOSE_WHEN_LEFT,
            Settings.OPENING_PAUSE,
            Settings.TAKE_BREAKS,
            Settings.OPEN_LINKS_EXTERNALLY,
            Settings.OPEN_MESSENGER_APP,
            Settings.LEAVE_CALLS_AND_CHATS_TO_MESSENGER,
            Settings.OPEN_CHATS_IN_MESSENGER,
            Settings.HIDE_PHOTO_POSTS,
            Settings.HIDE_VIDEO_POSTS,
            Settings.HIDE_LINK_POSTS,
            Settings.HIDE_BACKGROUND_POSTS,
            Settings.HIDE_REPOSTS,
            Settings.HIDE_PAGE_POSTS,
            Settings.HIDE_GROUP_POSTS,
            Settings.ONLY_FRIENDS_POSTS,
            Settings.HIDE_OTHER_LANGUAGES,
            Settings.HIDE_SEEN_POSTS));

    private AuditMode() {
    }

    /** The audit folder, or null when this phone wasn't set up for an audit. */
    @Nullable
    static File folder(@Nullable Context context) {
        if (context == null) return null;
        File files = context.getExternalFilesDir(null);
        if (files == null) return null;
        File folder = new File(files, FOLDER);
        return folder.isDirectory() ? folder : null;
    }

    /** Called from the application's onCreate in the main process. */
    static void onApplicationCreate(Context context) {
        File folder = folder(context);
        if (folder == null) return;
        try {
            File before = new File(folder, "before.json");
            if (!before.exists()) write(before, SettingsBackup.create());
            File request = new File(folder, "settings.json");
            if (!request.isFile()) return;
            String outcome = importFile(read(request));
            if (outcome.startsWith("applied ")) write(new File(folder, "applied.json"), SettingsBackup.create());
            File done = new File(folder, "settings.done.json");
            if (done.exists() && !done.delete()) outcome += "\nCould not remove the last settings.done.json";
            if (!request.renameTo(done) && !request.delete()) outcome += "\nCould not move settings.json aside";
            write(new File(folder, "import.txt"), outcome + "\n");
            String logged = outcome;
            Logger.printInfo(() -> "Audit: " + logged);
        } catch (Throwable ex) {
            Logger.printException(() -> "Audit: could not import", ex);
        }
    }

    /** Applies [text], a backup file or an audit profile, and says what happened. */
    static String importFile(String text) {
        try {
            String backup = text;
            JSONObject root = new JSONObject(text);
            if (AUDIT_FORMAT.equals(root.optString(SettingsBackup.FORMAT_NAME))) {
                backup = profile(root);
            }
            int changed = SettingsBackup.apply(SettingsBackup.parse(backup));
            return "applied " + changed;
        } catch (SettingsBackup.Rejected rejected) {
            return "refused: " + rejected.getMessage();
        } catch (SettingsBackup.ApplyFailed failed) {
            return "failed: " + failed.getMessage();
        } catch (JSONException ex) {
            return "refused: " + ex.getMessage();
        }
    }

    /** Keeps acknowledgments from older callers readable without claiming accordion proof. */
    static void pageShown(Context context, String route, CharSequence title) {
        recordPage(context, route, title, null, 0);
    }

    /** Records the revealed section and its actual child rows after navigation has laid it out. */
    static void pageShown(Context context, String route, CharSequence title,
                          List<String> expandedSections, int sectionRows) {
        recordPage(context, route, title, expandedSections, sectionRows);
    }

    private static void recordPage(Context context, String route, CharSequence title,
                                   @Nullable List<String> expandedSections, int sectionRows) {
        File folder = folder(context);
        if (folder == null) return;
        try {
            JSONObject record = new JSONObject().put("schema", expandedSections == null ? 2 : 3)
                    .put("route", route).put("title", title.toString())
                    .put("locale", L10n.locale(context).toLanguageTag())
                    .put("localeTags", new JSONArray(L10n.tags(context)));
            if (expandedSections != null) {
                record.put("expandedSections", new JSONArray(expandedSections))
                        .put("sectionRows", sectionRows)
                        .put("targetExpanded", !route.isEmpty() && expandedSections.contains(route));
            }
            write(new File(folder, "page.json"), record.toString());
        } catch (Throwable failure) {
            Logger.printException(() -> "Audit: could not record the displayed page", failure);
        }
    }

    /** An audit profile as the backup file it stands for. */
    static String profile(JSONObject root) throws JSONException {
        String name = root.optString("profile", PROFILE_ALL_ON);
        boolean allOn = PROFILE_ALL_ON.equals(name);
        if (!allOn && !PROFILE_DEFAULTS.equals(name)) throw new JSONException("Unknown profile " + name);
        Set<String> on = keys(root.optJSONArray("on"));
        Set<String> off = keys(root.optJSONArray("off"));
        Set<BooleanSetting> excluded = new HashSet<>(ALL_ON_EXCLUDED);
        Map<String, Object> switches = new LinkedHashMap<>();
        for (BooleanSetting setting : SettingsBackup.ALLOWLIST) {
            boolean value = allOn ? !excluded.contains(setting) : setting.defaultValue;
            if (on.contains(setting.key)) value = true;
            if (off.contains(setting.key)) value = false;
            switches.put(setting.key, value);
        }
        return new JSONObject()
                .put(SettingsBackup.FORMAT_NAME, SettingsBackup.FORMAT)
                .put(SettingsBackup.SCHEMA_NAME, SettingsBackup.SCHEMA)
                .put(SettingsBackup.SETTINGS_NAME, new JSONObject(switches))
                .toString();
    }

    private static Set<String> keys(@Nullable JSONArray array) {
        Set<String> keys = new HashSet<>();
        if (array != null) for (int i = 0; i < array.length(); i++) keys.add(array.optString(i));
        return keys;
    }

    /**
     * The settings page an audit intent asks the screen to open on, or null. Only a page name's
     * shape is taken: letters, digits, spaces, commas and apostrophes.
     */
    @Nullable
    static String page(@Nullable Context context, @Nullable Intent intent) {
        if (intent == null || !intent.hasExtra(EXTRA_PAGE)) return null;
        String page = intent.getStringExtra(EXTRA_PAGE);
        intent.removeExtra(EXTRA_PAGE);
        if (folder(context) == null || page == null || !page.matches("[A-Za-z0-9 ,']{1,64}")) return null;
        return page;
    }

    /** Writes the full report to report.txt when an audit intent asks for it. */
    static void noteIntent(@Nullable Context context, @Nullable Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_REPORT, false)) return;
        intent.removeExtra(EXTRA_REPORT);
        File folder = folder(context);
        if (folder == null) return;
        Utils.runOnBackgroundThread(() -> writeReport(folder));
    }

    static void writeReport(File folder) {
        try {
            File part = new File(folder, "report.txt.part");
            write(part, LogBufferManager.buildExportText());
            File report = new File(folder, "report.txt");
            if (report.exists() && !report.delete()) throw new IOException("Could not replace report.txt");
            if (!part.renameTo(report)) throw new IOException("Could not name report.txt");
            Logger.printInfo(() -> "Audit: report written");
        } catch (IOException ex) {
            Logger.printException(() -> "Audit: could not write the report", ex);
        }
    }

    private static String read(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            return SettingsBackup.read(input);
        } catch (SettingsBackup.Rejected rejected) {
            throw new IOException(rejected.getMessage());
        }
    }

    private static void write(File file, String text) throws IOException {
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
