/*
 * Forked from:
 * https://github.com/SysAdminDoc/hushfeed/blob/bcc57ee555f4346c4eb1cbdae9f6ca8a3fa6fef4/extensions/tiktok/src/test/java/app/morphe/extension/tiktok/settings/SettingsBackupTest.java
 * Copyright 2026 Hushfeed contributors (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026: rewritten around the list of switches a file may
 * carry, the preview an import shows first, and the picker round trip through Hushfacebook's
 * settings dialog, the activity rebuilt behind the picker included.
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.SwitchPreference;
import android.preference.TwoStatePreference;
import android.widget.ListView;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowToast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import app.morphe.extension.facebook.download.DownloadQuality;
import app.morphe.extension.facebook.notifications.QuietHour;
import app.morphe.extension.facebook.download.FileNameTemplate;
import app.morphe.extension.facebook.download.SaveFolder;
import app.morphe.extension.facebook.download.SaveTo;
import app.morphe.extension.facebook.download.SendLink;
import app.morphe.extension.facebook.comments.CommentOrder;
import app.morphe.extension.facebook.reels.ReelsOrder;
import app.morphe.extension.facebook.feed.PostSources;
import app.morphe.extension.facebook.feed.PostWords;
import app.morphe.extension.facebook.feed.PostWordsTest;
import app.morphe.extension.facebook.feed.WordsCorpus;
import app.morphe.extension.facebook.media.PlaybackQuality;
import app.morphe.extension.facebook.media.SurfaceQuality;
import app.morphe.extension.facebook.misc.AppLock;
import app.morphe.extension.facebook.misc.BreakTimer;
import app.morphe.extension.facebook.media.RecentlyWatched;
import app.morphe.extension.facebook.misc.OpeningPause;
import app.morphe.extension.facebook.misc.ShareSheetItems;
import app.morphe.extension.facebook.misc.TextSize;
import app.morphe.extension.facebook.theme.AccentColor;
import app.morphe.extension.facebook.theme.MarkColors.Mark;
import app.morphe.extension.facebook.navigation.FeedsSubtab;
import app.morphe.extension.facebook.navigation.StartTab;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.WorkerPoolForTests;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.FailingStore;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.shared.settings.preference.ReopenSettingsAfterRestart;
import app.morphe.extension.shared.settings.preference.SharedPrefCategory;

/**
 * Export settings and Import settings: what a file may carry, what an import refuses, the preview
 * it shows first and the one commit it writes, and the round trip through Android's file picker.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class SettingsBackupTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /**
     * Switches in {@link Settings} that are meant to stay out of a settings file, each with the
     * reason. A switch that shows up in neither this nor the list fails the test below, so it's a
     * decision someone makes rather than one that happens. Keyed by the switch's key as text: a
     * static that loads Settings here would load it before the rule sets a context, and that
     * poisons the sandbox for every class after this one (SettingsContextRule).
     */
    private static final Map<String, String> STAYS_OUT = Collections.singletonMap(
            "hushfacebook_check_releases",
            "It puts the phone online, and the import preview gives only a count of the switches it "
                    + "changes, so a file someone shared could turn it on unseen. It's switched on from the "
                    + "phone's own screen.");

    /**
     * Settings in {@link Settings} that aren't switches and stay out of a settings file, by key,
     * each with the reason. A new one in neither this nor {@link SettingsBackup#VALUES} fails the
     * test below. Keys, not the settings: a Setting read before the context rule has run poisons
     * the registry for the rest of the sandbox.
     */
    private static final Map<String, String> VALUES_STAY_OUT = valuesStayOut();

    private static Map<String, String> valuesStayOut() {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        out.put("hushfacebook_font_source",
                "it names the font file Use the system font draws in, whose copy only this install holds. A settings "
                        + "file can't carry the font itself, and the name alone would point at nothing on another phone.");
        out.put("hushfacebook_translate_to",
                "it's a language pick for the person on this phone, and left empty it follows the phone's own language.");
        out.put("hushfacebook_translate_leave_alone",
                "it lists the languages the person on this phone reads, which belongs to them, not to a settings file.");
        out.put("hushfacebook_header_picture",
                "it names the picture at the top of Home, whose shrunk copy only this install holds. A settings file "
                        + "can't carry the picture itself, and the name alone would point at nothing on another phone.");
        out.put("hushfacebook_stories_tray_size",
                "it is a width in dp picked for this phone's screen, which reads differently on another phone.");
        for (String count : new String[] {"friends", "followers", "following"}) {
            out.put("hushfacebook_local_" + count,
                    "it's a number typed for your own profile on this phone, for fun. It stays here and isn't "
                            + "something to share in a settings file.");
        }
        out.put("hushfacebook_save_storage",
                "it names the SD card saves go to by the volume name Android gave that card in this phone, which "
                        + "points at nothing on another phone.");
        out.put("hushfacebook_seen_share_items",
                "it records the share items this install's Facebook offered, which the hook writes, not a choice.");
        out.put("hushfacebook_seen_menu_tiles",
                "it records the Menu tiles this install's Facebook showed, which the hook writes, not a choice.");
        return Collections.unmodifiableMap(out);
    }

    /** Hushfacebook's own state and its diagnostics. None of them is ever in a file. */
    private static List<Setting<?>> neverInAFile() {
        return Arrays.asList(BaseSettings.PAUSED, BaseSettings.SAFE_MODE, BaseSettings.DEBUG,
                BaseSettings.DEBUG_LOG_FILTERS, BaseSettings.DEBUG_STACKTRACE, BaseSettings.DEBUG_TOAST_ON_ERROR,
                BaseSettings.CAPTURE_JAVA_CRASHES, BaseSettings.MORPHE_LANGUAGE, BaseSettings.SHOW_MENU_ICONS,
                BaseSettings.FIRST_TIME_APP_LAUNCHED, BaseSettings.EXPERIMENTAL_APP_CONFIRMED,
                BaseSettings.CHECK_ENVIRONMENT_WARNINGS_ISSUED, BaseSettings.HIDE_QUICK_SETUPS);
    }

    private static final String EXPORT_ROW = "action_export_settings";
    private static final String IMPORT_ROW = "action_import_settings";
    /** The app behind every file the tests pick. */
    private static final String AUTHORITY = "settings-test";

    @Before
    public void startClean() {
        for (BooleanSetting setting : SettingsBackup.ALLOWLIST) setting.resetToDefault();
        PatchFamily.inBuildForTests = java.util.EnumSet.allOf(PatchFamily.class);
        ShadowToast.reset();
        ShadowAlertDialog.reset();
        SettingsFileProvider.install(AUTHORITY);
    }

    @After
    public void restore() throws Exception {
        // A test that stopped while the app still held its file would leave that run holding a
        // worker and the rows for the tests after it. Letting the open go ends the run, and the
        // rows come back the way they do in the app.
        CountDownLatch held = SettingsFileProvider.stall;
        if (held != null) held.countDown();
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        for (BooleanSetting setting : SettingsBackup.ALLOWLIST) setting.resetToDefault();
        Settings.SAVE_FOLDER.resetToDefault();
        Settings.SAVE_STORAGE.resetToDefault();
        Settings.DOWNLOAD_QUALITY.resetToDefault();
        Settings.FILENAME_TEMPLATE.resetToDefault();
        Settings.PHOTO_FILENAME_TEMPLATE.resetToDefault();
        Settings.START_TAB.resetToDefault();
        Settings.FEEDS_SUBTAB.resetToDefault();
        Settings.HOME_FEED.resetToDefault();
        Settings.COMMENT_ORDER.resetToDefault();
        Settings.PLAYBACK_QUALITY.resetToDefault();
        Settings.REELS_PLAYBACK_QUALITY.resetToDefault();
        Settings.STORIES_PLAYBACK_QUALITY.resetToDefault();
        Settings.REELS_ORDER.resetToDefault();
        Settings.BURN_IN_GUARD.resetToDefault();
        Settings.BURN_IN_WAIT.resetToDefault();
        Settings.RECENTLY_WATCHED_KEEP.resetToDefault();
        Settings.QUIET_HOURS_FROM.resetToDefault();
        Settings.QUIET_HOURS_UNTIL.resetToDefault();
        Settings.APP_LOCK_AFTER.resetToDefault();
        Settings.OPENING_PAUSE_LENGTH.resetToDefault();
        Settings.BREAK_AFTER.resetToDefault();
        Settings.BREAK_LENGTH.resetToDefault();
        Settings.TEXT_SIZE.resetToDefault();
        Settings.ACCENT_COLOR.resetToDefault();
        Settings.VIDEO_SUBFOLDER.resetToDefault();
        Settings.PHOTO_SUBFOLDER.resetToDefault();
        Settings.DOWNLOAD_ACTION.resetToDefault();
        Settings.SEND_TO_APP.resetToDefault();
        Settings.SAVE_TO.resetToDefault();
        Settings.HIDDEN_WORDS.resetToDefault();
        Settings.KEPT_WORDS.resetToDefault();
        Settings.HIDDEN_SOURCES.resetToDefault();
        Settings.HIDDEN_SHARE_ITEMS.resetToDefault();
        Settings.HIDDEN_MENU_TILES.resetToDefault();
        Settings.HIDDEN_FEED_TYPES.resetToDefault();
        Settings.AMOLED_BACKGROUND.resetToDefault();
        Settings.AMOLED_LIGHT_BACKGROUND.resetToDefault();
        Settings.BADGE_COLOR.resetToDefault();
        Settings.LOGO_COLOR.resetToDefault();
        Settings.TAB_COLOR.resetToDefault();
        Settings.HEADER_TEXT.resetToDefault();
        Settings.SEEN_MENU_TILES.resetToDefault();
        Settings.SEEN_SHARE_ITEMS.resetToDefault();
        BaseSettings.PAUSED.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        BaseSettings.DEBUG_LOG_FILTERS.resetToDefault();
        PauseForTests.resume();
        PatchFamily.inBuildForTests = null;
        AbstractPreferenceFragment.settingImportInProgress = false;
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
    }

    // ---- What a file carries ------------------------------------------------------------------

    /**
     * Every switch in Settings is either on the list or stays out with a reason. A new switch
     * fails here until someone decides which, so it can't slip into files, or out of them, unseen.
     */
    @Test
    public void theListIsEverySwitchInSettings() throws Exception {
        Set<String> switches = new TreeSet<>();
        for (BooleanSetting setting : PausedHooksTest.settingsSwitches()) switches.add(setting.key);
        Set<String> expected = new TreeSet<>(switches);
        expected.removeAll(STAYS_OUT.keySet());
        assertEquals("a switch in Settings has no decision: add it to SettingsBackup.ALLOWLIST, or to STAYS_OUT "
                + "here with the reason it stays out of a settings file", expected, keys(SettingsBackup.ALLOWLIST));
        assertTrue("STAYS_OUT names a switch Settings no longer has", switches.containsAll(STAYS_OUT.keySet()));
        assertEquals("the list names a switch twice", SettingsBackup.ALLOWLIST.size(),
                new HashSet<>(SettingsBackup.ALLOWLIST).size());
        // A file carries true or false, one folder name it checks as a folder name and one quality
        // it checks against the ones this build offers. Any other setting in Settings needs a
        // format that can carry it before it can be decided on.
        List<Setting<?>> notSwitches = new ArrayList<>();
        Set<String> declared = new TreeSet<>();
        for (Setting<?> setting : declaredSettings(Settings.class)) {
            declared.add(setting.key);
            if (!(setting instanceof BooleanSetting) && !VALUES_STAY_OUT.containsKey(setting.key)) notSwitches.add(setting);
        }
        assertEquals("a setting in Settings that isn't a switch has no format in a settings file: add it to "
                + "SettingsBackup.VALUES, or to VALUES_STAY_OUT here with the reason", SettingsBackup.VALUES, notSwitches);
        assertTrue("VALUES_STAY_OUT names a setting Settings no longer has", declared.containsAll(VALUES_STAY_OUT.keySet()));
        for (Setting<?> setting : SettingsBackup.VALUES) {
            assertFalse(setting.key + " is carried and kept out at once", VALUES_STAY_OUT.containsKey(setting.key));
        }
        assertEquals(Arrays.<Setting<?>>asList(Settings.HIDDEN_WORDS, Settings.KEPT_WORDS, Settings.HIDDEN_SOURCES, Settings.HIDDEN_FEED_TYPES, Settings.HIDE_POSTS_OVER_REACTIONS, Settings.BURN_IN_GUARD, Settings.BURN_IN_WAIT, Settings.REELS_ORDER, Settings.RECENTLY_WATCHED_KEEP, Settings.HIDDEN_SHARE_ITEMS, Settings.HIDDEN_MENU_TILES, Settings.HOME_FEED, Settings.AMOLED_BACKGROUND, Settings.AMOLED_LIGHT_BACKGROUND, Settings.HEADER_TEXT, Settings.SEEN_POSTS_KEEP, Settings.SAVE_TO,
                Settings.SAVE_FOLDER, Settings.VIDEO_SUBFOLDER, Settings.PHOTO_SUBFOLDER, Settings.DOWNLOAD_QUALITY,
                Settings.FILENAME_TEMPLATE, Settings.PHOTO_FILENAME_TEMPLATE, Settings.DOWNLOAD_ACTION, Settings.SEND_TO_APP,
                Settings.START_TAB, Settings.FEEDS_SUBTAB, Settings.COMMENT_ORDER, Settings.PLAYBACK_QUALITY,
                Settings.REELS_PLAYBACK_QUALITY, Settings.STORIES_PLAYBACK_QUALITY, Settings.QUIET_HOURS_FROM,
                Settings.QUIET_HOURS_UNTIL, Settings.APP_LOCK_AFTER, Settings.OPENING_PAUSE_LENGTH, Settings.BREAK_AFTER,
                Settings.BREAK_LENGTH, Settings.TEXT_SIZE, Settings.ACCENT_COLOR, Settings.BADGE_COLOR, Settings.LOGO_COLOR, Settings.TAB_COLOR), SettingsBackup.VALUES);
        assertEquals(Settings.SAVE_TO, SettingsBackup.TO);
        assertEquals(Settings.HIDDEN_WORDS, SettingsBackup.HIDDEN);
        assertEquals(Settings.KEPT_WORDS, SettingsBackup.KEPT);
        assertEquals(Settings.HIDDEN_SOURCES, SettingsBackup.SOURCES);
        assertEquals(Settings.HIDDEN_SHARE_ITEMS, SettingsBackup.SHARE_ITEMS);
        assertEquals(Settings.HIDDEN_MENU_TILES, SettingsBackup.MENU_TILES);
        assertEquals(Settings.HIDDEN_FEED_TYPES, SettingsBackup.FEED_TYPES);
        assertEquals(Settings.SAVE_FOLDER, SettingsBackup.FOLDER);
        assertEquals(Settings.DOWNLOAD_QUALITY, SettingsBackup.QUALITY);
        assertEquals(Settings.FILENAME_TEMPLATE, SettingsBackup.FILE_NAME);
        assertEquals(Settings.PHOTO_FILENAME_TEMPLATE, SettingsBackup.PHOTO_NAME);
        assertEquals(Settings.START_TAB, SettingsBackup.START);
        assertEquals(Settings.FEEDS_SUBTAB, SettingsBackup.SUBTAB);
        assertEquals(Settings.HOME_FEED, SettingsBackup.HOME_FEED);
        assertEquals(Settings.AMOLED_BACKGROUND, SettingsBackup.BACKGROUND);
        assertEquals(Settings.AMOLED_LIGHT_BACKGROUND, SettingsBackup.LIGHT_BACKGROUND);
        assertEquals(Settings.COMMENT_ORDER, SettingsBackup.ORDER);
        assertEquals(Settings.PLAYBACK_QUALITY, SettingsBackup.PLAYBACK);
        assertEquals(Settings.REELS_PLAYBACK_QUALITY, SettingsBackup.REELS_QUALITY);
        assertEquals(Settings.STORIES_PLAYBACK_QUALITY, SettingsBackup.STORIES_QUALITY);
        assertEquals(Settings.REELS_ORDER, SettingsBackup.REELS_ORDER);
        assertEquals(Settings.BURN_IN_GUARD, SettingsBackup.BURN_IN);
        assertEquals(Settings.BURN_IN_WAIT, SettingsBackup.BURN_IN_WAIT);
        assertEquals(Settings.QUIET_HOURS_FROM, SettingsBackup.QUIET_FROM);
        assertEquals(Settings.QUIET_HOURS_UNTIL, SettingsBackup.QUIET_UNTIL);
        assertEquals(Settings.TEXT_SIZE, SettingsBackup.TEXT_SIZE);
        assertEquals(Settings.ACCENT_COLOR, SettingsBackup.ACCENT);
        assertEquals(Settings.VIDEO_SUBFOLDER, SettingsBackup.VIDEO_SUBFOLDER);
        assertEquals(Settings.PHOTO_SUBFOLDER, SettingsBackup.PHOTO_SUBFOLDER);
        assertEquals(Settings.DOWNLOAD_ACTION, SettingsBackup.ACTION);
        assertEquals(Settings.SEND_TO_APP, SettingsBackup.APP);
    }

    @Test
    public void nothingOfHushfacebooksOwnIsOnTheList() throws Exception {
        List<Setting<?>> own = declaredSettings(BaseSettings.class);
        assertTrue("the scan of BaseSettings found nothing", own.size() >= neverInAFile().size());
        for (Setting<?> setting : own) {
            assertFalse(setting.key + " is Hushfacebook's own and is on the list", SettingsBackup.ALLOWLIST.contains(setting));
        }
        for (Setting<?> setting : neverInAFile()) {
            assertFalse(setting.key + " is on the list", SettingsBackup.ALLOWLIST.contains(setting));
        }
    }

    /**
     * The font file Use the system font draws in stays out of a file both ways: an export doesn't
     * name it, and a file that does has it counted as something this build doesn't know.
     */
    @Test
    public void reelMenuOnlySurvivesExportImportAndTheMainSwitch() throws Exception {
        Settings.SHOW_REEL_DOWNLOAD_BUTTON.save(false);
        String file = SettingsBackup.create();
        JSONObject values = new JSONObject(file).getJSONObject("settings");
        assertTrue(values.getBoolean(Settings.DOWNLOAD_REELS.key));
        assertFalse(values.getBoolean(Settings.SHOW_REEL_DOWNLOAD_BUTTON.key));

        Settings.SHOW_REEL_DOWNLOAD_BUTTON.resetToDefault();
        assertEquals(1, SettingsBackup.apply(SettingsBackup.parse(file)));
        assertTrue(Settings.DOWNLOAD_REELS.get());
        assertFalse(Settings.SHOW_REEL_DOWNLOAD_BUTTON.savedValue());

        Settings.DOWNLOAD_REELS.save(false);
        String disabled = SettingsBackup.create();
        Settings.DOWNLOAD_REELS.save(true);
        Settings.SHOW_REEL_DOWNLOAD_BUTTON.save(true);
        assertEquals(2, SettingsBackup.apply(SettingsBackup.parse(disabled)));
        assertFalse(Settings.DOWNLOAD_REELS.savedValue());
        assertFalse(Settings.SHOW_REEL_DOWNLOAD_BUTTON.savedValue());
        Settings.DOWNLOAD_REELS.save(true);
        assertFalse("enabling downloads forgot the saved sidebar choice", Settings.SHOW_REEL_DOWNLOAD_BUTTON.get());
    }

    @Test
    public void thePickedFontStaysOutOfAFile() throws Exception {
        Settings.FONT_SOURCE.save("Inter-Regular.ttf");
        try {
            String text = SettingsBackup.create();
            assertFalse(text, text.contains(Settings.FONT_SOURCE.key));
            assertFalse(text, text.contains("Inter-Regular.ttf"));

            JSONObject file = new JSONObject(text);
            file.getJSONObject("settings").put(Settings.FONT_SOURCE.key, "Other.ttf");
            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file.toString());
            assertEquals(1, snapshot.unknown);
            assertEquals(0, SettingsBackup.apply(snapshot));
            assertEquals("Inter-Regular.ttf", Settings.FONT_SOURCE.savedValue());
        } finally {
            Settings.FONT_SOURCE.resetToDefault();
        }
    }

    /**
     * A file is a format name, a version, one true or false per switch and the save folder's
     * name, whatever else the phone holds: not the pause, not debug logging or safe mode, not the
     * log, the diagnostic counts or anything that names the phone or the person.
     */
    @Test
    public void aFileIsItsFormatItsVersionAndOneValuePerSwitch() throws Exception {
        Settings.HIDE_PROMOTED_POSTS.save(false);
        Settings.DOWNLOAD_REELS.save(false);
        // Stored as it came, and written as the folder the saves really use.
        Settings.SAVE_FOLDER.save("../My/Clips");
        Settings.DOWNLOAD_QUALITY.save(DownloadQuality.P480);
        Settings.FILENAME_TEMPLATE.save("../{video_id}");
        Settings.PHOTO_FILENAME_TEMPLATE.save("FB_VID_{photo_id}");
        Settings.START_TAB.save(StartTab.FRIENDS);
        Settings.COMMENT_ORDER.save(CommentOrder.ALL_COMMENTS);
        // Stored as typed, and written as the list the filter reads.
        Settings.HIDDEN_WORDS.save(" spoiler \nSPOILER\nleak");
        BaseSettings.PAUSED.save(true);
        BaseSettings.DEBUG.save(true);
        BaseSettings.DEBUG_LOG_FILTERS.save("downloads");
        PauseForTests.pause(HushfacebookPause.Reason.CRASH_LOOP);
        String cookie = "c_user=100012345678901; xs=secret-session";
        LogBufferManager.appendToLogBuffer("sentinel line " + cookie);
        HookStatus.invoked(FamilyNames.SPONSORED_POSTS);
        Context app = RuntimeEnvironment.getApplication();

        String text = SettingsBackup.create();
        JSONObject root = new JSONObject(text);
        assertEquals(new TreeSet<>(Arrays.asList("format", "schema", "settings")), names(root));
        assertEquals("hushfacebook-settings", root.get("format"));
        assertEquals(1, root.get("schema"));
        JSONObject switches = root.getJSONObject("settings");
        Set<String> carried = keys(SettingsBackup.ALLOWLIST);
        carried.addAll(keys(SettingsBackup.VALUES));
        assertEquals(carried, names(switches));
        for (BooleanSetting setting : SettingsBackup.ALLOWLIST) {
            // Saved, not what a paused Facebook is answered: paused, every switch answers false, but
            // for the ones a pause leaves as they are (Lock Facebook, the settings shortcut).
            assertEquals(setting.key, setting.isKeptWhenPaused() && setting.savedValue(), setting.get());
            assertEquals(setting.key, setting.savedValue(), switches.get(setting.key));
        }
        assertEquals("My_Clips", switches.get(SettingsBackup.FOLDER.key));
        // Saved, not what a paused Facebook is answered: paused, the quality answers the best.
        assertEquals("480p", switches.get(SettingsBackup.QUALITY.key));
        assertEquals("{video_id}", switches.get(SettingsBackup.FILE_NAME.key));
        assertEquals("FB_IMG_{photo_id}", switches.get(SettingsBackup.PHOTO_NAME.key));
        // Saved, not what a paused Facebook is answered: paused, it opens where it chooses.
        assertEquals("friends", switches.get(SettingsBackup.START.key));
        // Saved, not what a paused Facebook is answered: paused, comments open as Facebook picks.
        assertEquals("all_comments", switches.get(SettingsBackup.ORDER.key));
        // Saved, not what a paused Facebook is answered: paused, the lists read empty.
        assertEquals("spoiler\nleak", switches.get(SettingsBackup.HIDDEN.key));
        assertEquals("", switches.get(SettingsBackup.KEPT.key));
        for (Setting<?> setting : Setting.allLoadedSettings()) {
            if (SettingsBackup.ALLOWLIST.contains(setting) || SettingsBackup.VALUES.contains(setting)) continue;
            assertFalse(setting.key + " is in the file", text.contains(setting.key));
        }
        for (String leak : new String[]{"sentinel", "c_user", "100012345678901", "secret-session",
                app.getPackageName(), FamilyNames.SPONSORED_POSTS, "downloads"}) {
            assertFalse("the file carries " + leak + ":\n" + text, text.contains(leak));
        }
    }

    @Test
    public void everySwitchRoundTripsBothWays() throws Exception {
        for (int pattern = 0; pattern < 2; pattern++) {
            Map<BooleanSetting, Boolean> chosen = new LinkedHashMap<>();
            int index = 0;
            for (BooleanSetting setting : SettingsBackup.ALLOWLIST) {
                boolean value = (index++ + pattern) % 2 == 0;
                setting.save(value);
                chosen.put(setting, value);
            }
            String file = SettingsBackup.create();
            for (BooleanSetting setting : SettingsBackup.ALLOWLIST) setting.save(!setting.savedValue());

            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
            assertEquals(0, snapshot.unknown);
            assertEquals(SettingsBackup.ALLOWLIST.size(), snapshot.changes().size());
            assertEquals(SettingsBackup.ALLOWLIST.size(), SettingsBackup.apply(snapshot));
            for (Map.Entry<BooleanSetting, Boolean> entry : chosen.entrySet()) {
                BooleanSetting setting = entry.getKey();
                assertEquals(setting.key, entry.getValue(), setting.savedValue());
                // What the next start reads: a switch at its default is absent from the store.
                assertEquals(setting.key, entry.getValue(),
                        Setting.preferences.preferences.getBoolean(setting.key, setting.defaultValue));
            }
            assertEquals("a file read back is the file", file, SettingsBackup.create());
        }
    }

    @Test
    public void sparseAndLegacyImportsDoNotChangeAnOmittedWholeWordMode() throws Exception {
        for (boolean full : new boolean[]{false, true}) {
            for (boolean saved : new boolean[]{false, true}) {
                Settings.POST_WORDS_WHOLE_WORDS.save(saved);
                JSONObject file = new JSONObject(SettingsBackup.create());
                assertEquals(1, file.getInt("schema"));
                if (!full) {
                    file.put("settings", new JSONObject().put(Settings.HIDE_POSTS_WITH_WORDS.key,
                            !Settings.HIDE_POSTS_WITH_WORDS.savedValue()));
                }
                file.getJSONObject("settings").remove(Settings.POST_WORDS_WHOLE_WORDS.key);
                SettingsBackup.Snapshot incoming = SettingsBackup.parse(file.toString());
                assertFalse(incoming.values.containsKey(Settings.POST_WORDS_WHOLE_WORDS));
                SettingsBackup.apply(incoming);
                assertEquals(saved, Settings.POST_WORDS_WHOLE_WORDS.savedValue());
            }
        }
    }

    /** Saves other apps can open travel with the other download settings, both ways (issue #11). */
    @Test
    public void theCompatibleSavesSwitchGoesOutAndComesBack() throws Exception {
        assertFalse("the switch doesn't start off", Settings.DOWNLOAD_COMPATIBLE.savedValue());
        Settings.DOWNLOAD_COMPATIBLE.save(true);
        JSONObject exported = new JSONObject(SettingsBackup.create()).getJSONObject("settings");
        assertTrue(exported.toString(), exported.getBoolean(Settings.DOWNLOAD_COMPATIBLE.key));

        Settings.DOWNLOAD_COMPATIBLE.save(false);
        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(fileWith(Settings.DOWNLOAD_COMPATIBLE, true));
        assertEquals(0, snapshot.unknown);
        assertEquals(1, SettingsBackup.apply(snapshot));
        assertTrue(Settings.DOWNLOAD_COMPATIBLE.savedValue());
    }

    // ---- Writing ---------------------------------------------------------------------------

    @Test
    public void anImportIsOneCommit() throws Exception {
        Settings.HIDE_SPONSORED_POSTS.save(false);
        String file = SettingsBackup.create();
        Settings.HIDE_SPONSORED_POSTS.save(true);
        Settings.OPEN_LINKS_EXTERNALLY.save(false);
        Settings.DOWNLOAD_STORIES.save(false);
        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
        assertEquals(3, snapshot.changes().size());

        Counts counts = new Counts();
        withStore(counting(Setting.preferences.preferences, counts, () -> false, false), () -> {
            assertEquals(3, SettingsBackup.apply(snapshot));
            return null;
        });
        assertEquals("editors opened", 1, counts.edits.get());
        assertEquals("commits", 1, counts.commits.get());
        assertEquals("asynchronous applies", 0, counts.applies.get());
        assertFalse(Settings.HIDE_SPONSORED_POSTS.savedValue());
        assertTrue(Settings.OPEN_LINKS_EXTERNALLY.savedValue());
        assertTrue(Settings.DOWNLOAD_STORIES.savedValue());

        // A file that changes nothing writes nothing.
        Counts none = new Counts();
        withStore(counting(Setting.preferences.preferences, none, () -> false, false), () -> {
            assertEquals(0, SettingsBackup.apply(SettingsBackup.parse(file)));
            return null;
        });
        assertEquals(0, none.edits.get());
    }

    @Test
    public void aFailedCommitIsPutBackAndSaysSo() throws Exception {
        String file = fileWith(Settings.HIDE_SPONSORED_POSTS, false, Settings.DOWNLOAD_REELS, false);
        Map<String, ?> before = store();
        Counts counts = new Counts();
        SettingsBackup.ApplyFailed failure = withStore(
                counting(Setting.preferences.preferences, counts, () -> counts.commits.get() == 1, false), () -> {
                    try {
                        SettingsBackup.apply(SettingsBackup.parse(file));
                        fail("a failed commit was reported as written");
                        return null;
                    } catch (SettingsBackup.ApplyFailed failed) {
                        return failed;
                    }
                });
        assertTrue("the switches are back, and the failure says they aren't", failure.rolledBack);
        assertEquals(before, store());
        assertTrue(Settings.HIDE_SPONSORED_POSTS.savedValue());
        assertTrue(Settings.DOWNLOAD_REELS.savedValue());
    }

    @Test
    public void aRollbackThatFailsTooSaysSomeSwitchesMayHaveChanged() throws Exception {
        String file = fileWith(Settings.HIDE_SPONSORED_POSTS, false);
        Counts counts = new Counts();
        // The first commit lands and reports failure; the one putting it back never lands.
        SettingsBackup.ApplyFailed failure = withStore(
                counting(Setting.preferences.preferences, counts, () -> true, true), () -> {
                    try {
                        SettingsBackup.apply(SettingsBackup.parse(file));
                        fail("a failed commit was reported as written");
                        return null;
                    } catch (SettingsBackup.ApplyFailed failed) {
                        return failed;
                    }
                });
        assertFalse("a store left changed was reported as put back", failure.rolledBack);
        assertEquals(2, counts.commits.get());
    }

    /**
     * An editor that won't open, a value it refuses, a commit that answers false or throws, before
     * or after landing: each leaves both switches on the value the store still holds, which is what
     * a restart loads, and only then does the failure say nothing changed.
     */
    @Test
    public void everyFailedWriteLeavesTheLiveSwitchesOnWhatTheStoreKeeps() throws Exception {
        String file = fileWith(Settings.HIDE_SPONSORED_POSTS, false, Settings.DOWNLOAD_REELS, false);
        Map<String, ?> before = store();
        for (FailingStore.Fault fault : new FailingStore.Fault[]{FailingStore.Fault.EDIT_THROWS,
                FailingStore.Fault.STAGE_THROWS, FailingStore.Fault.COMMIT_FALSE, FailingStore.Fault.COMMIT_THROWS,
                FailingStore.Fault.COMMIT_THROWS_AFTER_LANDING}) {
            SettingsBackup.ApplyFailed failure = failedApply(file, fault);
            assertTrue(fault + " said the switches may have changed", failure.rolledBack);
            assertEquals(fault + " left the store changed", before, store());
            assertTrue(fault + " left a live switch changed", Settings.HIDE_SPONSORED_POSTS.savedValue());
            assertTrue(fault + " left a live switch changed", Settings.DOWNLOAD_REELS.savedValue());
        }
    }

    /**
     * A rollback that doesn't land either says so, and the switches then hold what the store kept,
     * so the screen and the next start agree.
     */
    @Test
    public void aRollbackThatFailsSaysSoAndTheSwitchesHoldWhatTheStoreKept() throws Exception {
        String file = fileWith(Settings.HIDE_SPONSORED_POSTS, false, Settings.DOWNLOAD_REELS, false);
        FailingStore.Fault[][] scripts = {
                {FailingStore.Fault.COMMIT_FALSE, FailingStore.Fault.LOST},
                {FailingStore.Fault.COMMIT_THROWS_AFTER_LANDING, FailingStore.Fault.COMMIT_THROWS},
                {FailingStore.Fault.COMMIT_FALSE, FailingStore.Fault.EDIT_THROWS},
                {FailingStore.Fault.COMMIT_THROWS_AFTER_LANDING, FailingStore.Fault.STAGE_THROWS}};
        for (FailingStore.Fault[] script : scripts) {
            String name = Arrays.toString(script);
            SettingsBackup.ApplyFailed failure = failedApply(file, script);
            assertFalse(name + " said the switches are back", failure.rolledBack);
            assertEquals(name, false, store().get(Settings.HIDE_SPONSORED_POSTS.key));
            assertFalse(name + " runs a switch the store doesn't hold", Settings.HIDE_SPONSORED_POSTS.savedValue());
            assertFalse(name + " runs a switch the store doesn't hold", Settings.DOWNLOAD_REELS.savedValue());
            Settings.HIDE_SPONSORED_POSTS.resetToDefault();
            Settings.DOWNLOAD_REELS.resetToDefault();
        }
    }

    /** What a failed import says is what the screen shows and the store holds. */
    @Test
    public void aFailedImportSaysWhatTheStoreKeptAndTheScreenShowsIt() throws Exception {
        String file = fileWith(Settings.HIDE_SPONSORED_POSTS, false);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            SwitchPreference row = (SwitchPreference) page.findPreference(Settings.HIDE_SPONSORED_POSTS.key);

            deliver(activity, tap(activity, page, IMPORT_ROW), file);
            try (FailingStore ignored = FailingStore.install(FailingStore.Fault.COMMIT_THROWS)) {
                shownPreview().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                settle();
            }
            assertEquals("Couldn't import the settings. Nothing was changed.", ShadowToast.getTextOfLatestToast());
            assertTrue("the process runs a switch the file never managed to set", Settings.HIDE_SPONSORED_POSTS.savedValue());
            assertTrue(row.isChecked());
            assertFalse(store().containsKey(Settings.HIDE_SPONSORED_POSTS.key));

            ShadowToast.reset();
            deliver(activity, tap(activity, page, IMPORT_ROW), file);
            try (FailingStore ignored = FailingStore.install(FailingStore.Fault.COMMIT_FALSE, FailingStore.Fault.LOST)) {
                shownPreview().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                settle();
            }
            assertEquals("Couldn't import the settings, and couldn't put back the ones you had. "
                    + "Check the switches on this screen.", ShadowToast.getTextOfLatestToast());
            assertEquals(false, store().get(Settings.HIDE_SPONSORED_POSTS.key));
            assertFalse(Settings.HIDE_SPONSORED_POSTS.savedValue());
            assertFalse("the screen shows a switch the store doesn't hold", row.isChecked());
        }
    }

    // ---- Refusals ------------------------------------------------------------------------------

    @Test
    public void aFileThatIsNotOneJsonObjectIsDamaged() throws Exception {
        String good = SettingsBackup.create();
        for (String bad : new String[]{good.substring(0, good.length() / 2), "[]", good + "{}", good + " x",
                "just some text", good.replace("\"schema\"", "schema"), good + '\0', "",
                // Numbers past what BigDecimal can hold: these used to escape every refusal.
                "{\"format\":\"hushfacebook-settings\",\"schema\":1,\"settings\":{\"later\":1e9999999999}}",
                "{\"format\":\"hushfacebook-settings\",\"schema\":1e9999999999,\"settings\":{}}"}) {
            assertEquals(printable(bad), SettingsBackup.Reason.DAMAGED, reasonFor(bad));
        }
        // An editor's byte order mark isn't damage.
        assertEquals(0, SettingsBackup.parse((char) 0xFEFF + good).unknown);
    }

    @Test
    public void anOversizedFileIsRefusedWithoutBeingReadWhole() throws Exception {
        String good = SettingsBackup.create();
        int room = SettingsBackup.MAX_BYTES - good.getBytes(StandardCharsets.UTF_8).length;
        String full = good + repeat(' ', room);
        assertEquals("a file of exactly the limit is refused", SettingsBackup.MAX_BYTES,
                full.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(0, SettingsBackup.parse(full).unknown);
        assertEquals(SettingsBackup.Reason.SIZE, reasonFor(full + " "));

        byte[] atTheLimit = full.getBytes(StandardCharsets.UTF_8);
        assertEquals(full, SettingsBackup.read(new ByteArrayInputStream(atTheLimit)));
        byte[] over = (full + " ").getBytes(StandardCharsets.UTF_8);
        assertEquals(SettingsBackup.Reason.SIZE, readReason(new ByteArrayInputStream(over)));

        // A stream that never ends is given up on just past the limit, not read into memory.
        AtomicInteger pulled = new AtomicInteger();
        InputStream endless = new InputStream() {
            @Override public int read() {
                pulled.incrementAndGet();
                return ' ';
            }

            @Override public int read(byte[] buffer, int offset, int length) {
                Arrays.fill(buffer, offset, offset + length, (byte) ' ');
                pulled.addAndGet(length);
                return length;
            }
        };
        assertEquals(SettingsBackup.Reason.SIZE, readReason(endless));
        assertTrue("read " + pulled.get() + " bytes of an endless stream",
                pulled.get() <= SettingsBackup.MAX_BYTES + 8192);
    }

    @Test
    public void aNameGivenTwiceIsRefused() throws Exception {
        String key = Settings.HIDE_SPONSORED_POSTS.key;
        String twice = "{\"format\":\"hushfacebook-settings\",\"schema\":1,\"settings\":{\"" + key + "\":false,\""
                + key + "\":true}}";
        assertEquals(SettingsBackup.Reason.DUPLICATE, reasonFor(twice));
        assertEquals(SettingsBackup.Reason.DUPLICATE, reasonFor(
                "{\"format\":\"hushfacebook-settings\",\"format\":\"hushfacebook-settings\",\"schema\":1,\"settings\":{}}"));
        assertEquals("a name repeated inside a name this build doesn't know", SettingsBackup.Reason.DUPLICATE,
                reasonFor("{\"format\":\"hushfacebook-settings\",\"schema\":1,\"settings\":{\"later\":{\"a\":1,\"a\":2}}}"));
        // The same name in two different objects is two names.
        assertEquals(0, SettingsBackup.parse(
                "{\"format\":\"hushfacebook-settings\",\"schema\":1,\"settings\":{\"" + key + "\":false}}").unknown);
    }

    @Test
    public void aValueThatIsNotTrueOrFalseIsRefused() throws Exception {
        for (Object bad : new Object[]{"false", "true", 0, 1, JSONObject.NULL, new JSONObject(), new org.json.JSONArray(),
                1.5}) {
            JSONObject file = new JSONObject(SettingsBackup.create());
            file.getJSONObject("settings").put(Settings.DOWNLOAD_STORIES.key, bad);
            assertEquals(String.valueOf(bad), SettingsBackup.Reason.VALUE, reasonFor(file.toString()));
        }
    }

    @Test
    public void aFileFromANewerVersionIsRefused() throws Exception {
        JSONObject newer = new JSONObject(SettingsBackup.create()).put("schema", SettingsBackup.SCHEMA + 1);
        assertEquals(SettingsBackup.Reason.SCHEMA, reasonFor(newer.toString()));
        assertEquals(SettingsBackup.Reason.SCHEMA, reasonFor(new JSONObject(SettingsBackup.create()).put("schema", 99).toString()));
        // Anything else where the version goes was never written by Hushfacebook.
        String good = SettingsBackup.create();
        for (String version : new String[]{"0", "-1", "\"1\"", "1.0", "1.5", "4294967297", "null", "true", "[1]"}) {
            String bad = good.replace("\"schema\": 1", "\"schema\": " + version);
            assertTrue("the replacement missed", !bad.equals(good));
            assertEquals(version, SettingsBackup.Reason.FORMAT, reasonFor(bad));
        }
        JSONObject none = new JSONObject(good);
        none.remove("schema");
        assertEquals(SettingsBackup.Reason.FORMAT, reasonFor(none.toString()));
    }

    @Test
    public void aFileThatIsNotHushfacebooksIsRefused() throws Exception {
        String good = SettingsBackup.create();
        assertEquals(SettingsBackup.Reason.FORMAT, reasonFor(new JSONObject(good).put("format", "hushfeed-settings").toString()));
        assertEquals(SettingsBackup.Reason.FORMAT, reasonFor(new JSONObject(good).put("format", 1).toString()));
        JSONObject noFormat = new JSONObject(good);
        noFormat.remove("format");
        assertEquals(SettingsBackup.Reason.FORMAT, reasonFor(noFormat.toString()));
        assertEquals(SettingsBackup.Reason.FORMAT, reasonFor(new JSONObject(good).put("settings", "all of them").toString()));
        JSONObject noSettings = new JSONObject(good);
        noSettings.remove("settings");
        assertEquals(SettingsBackup.Reason.FORMAT, reasonFor(noSettings.toString()));
    }

    @Test
    public void bytesThatAreNotUtf8AreRefused() {
        assertEquals(SettingsBackup.Reason.ENCODING,
                readReason(new ByteArrayInputStream(new byte[]{'{', (byte) 0xC3, (byte) 0x28, '}'})));
        assertEquals(SettingsBackup.Reason.UNREADABLE, readReason(null));
        assertEquals(SettingsBackup.Reason.UNREADABLE, readReason(new InputStream() {
            @Override public int read() throws java.io.IOException {
                throw new java.io.IOException("content://provider/document/private-name.json went away");
            }
        }));
    }

    /**
     * A name this build doesn't know is counted for the preview and left alone, Pause and debug
     * logging included: naming them in a file changes neither.
     */
    @Test
    public void unknownNamesAreCountedAndLeftAlone() throws Exception {
        JSONObject file = new JSONObject(SettingsBackup.create()).put("exported_by", "a newer build");
        file.getJSONObject("settings")
                .put(Settings.HIDE_SPONSORED_REELS.key, false)
                .put(BaseSettings.PAUSED.key, true)
                .put(BaseSettings.DEBUG.key, true)
                .put("hushfacebook_kept_by_a_later_version", "whatever that version keeps here");
        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file.toString());
        assertEquals(4, snapshot.unknown);
        assertEquals(1, snapshot.changes().size());
        assertEquals(1, SettingsBackup.apply(snapshot));
        assertFalse(Settings.HIDE_SPONSORED_REELS.savedValue());
        assertFalse("a file paused Hushfacebook", BaseSettings.PAUSED.savedValue());
        assertFalse("a file turned on debug logging", BaseSettings.DEBUG.savedValue());
    }

    /**
     * The folder goes out as the name the saves use and comes back only as one: a value the
     * sanitizer would change, or that isn't text, refuses the whole file, so a file can't point
     * the saves at a path or hide them.
     */
    @Test
    public void theFolderRoundTripsAndComesBackOnlyAsOneCleanName() throws Exception {
        Settings.SAVE_FOLDER.save("Clips");
        String file = SettingsBackup.create();
        Settings.SAVE_FOLDER.resetToDefault();
        Settings.SAVE_STORAGE.resetToDefault();

        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
        assertEquals("Clips", snapshot.folder);
        assertEquals("Clips", snapshot.folderChange());
        assertEquals(0, snapshot.switchChanges());
        assertEquals(Collections.singletonMap(SettingsBackup.FOLDER, "Clips"), snapshot.changes());
        assertEquals(1, SettingsBackup.apply(snapshot));
        assertEquals("Clips", Settings.SAVE_FOLDER.savedValue());
        assertEquals("a file read back is the file", file, SettingsBackup.create());
        assertEquals("the same folder again changes nothing", 0, SettingsBackup.parse(file).changes().size());

        Map<String, ?> before = store();
        for (Object refused : new Object[]{"../Clips", "My/Clips", "My\\Clips", ".hidden", "Clips.", " Clips", "",
                "a\u200Bb", "a\u202Eb", "a\nb", repeat('a', 51), 5, true, JSONObject.NULL, new JSONObject()}) {
            JSONObject hostile = new JSONObject(file);
            hostile.getJSONObject("settings").put(SettingsBackup.FOLDER.key, refused);
            try {
                SettingsBackup.parse(hostile.toString());
                fail("a file with the folder " + printable(String.valueOf(refused)) + " was read");
            } catch (SettingsBackup.Rejected rejected) {
                assertEquals(printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
            }
        }
        assertEquals("a refused file wrote something", before, store());
        assertEquals("Clips", Settings.SAVE_FOLDER.savedValue());

        // A file from before the folder was carried leaves it alone.
        SettingsBackup.Snapshot older = SettingsBackup.parse(fileWith(Settings.HIDE_SUGGESTED_POSTS, false));
        assertNull(older.folder);
        assertNull(older.folderChange());
        SettingsBackup.apply(older);
        assertEquals("Clips", Settings.SAVE_FOLDER.savedValue());
    }

    /**
     * The word filter's two lists go out and come back exactly as the settings row stores them. A
     * list the row would clean differently, or that isn't text, refuses the whole file, so a file
     * can't carry a longer or looser list than someone could type.
     */
    @Test
    public void theWordListsRoundTripAndComeBackOnlyAsCleanLists() throws Exception {
        Settings.HIDDEN_WORDS.save("spoiler\ngiveaway now");
        Settings.KEPT_WORDS.save("my team");
        String file = SettingsBackup.create();
        Settings.HIDDEN_WORDS.resetToDefault();
        Settings.KEPT_WORDS.resetToDefault();

        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
        assertEquals("spoiler\ngiveaway now", snapshot.hiddenChange());
        assertEquals("my team", snapshot.keptChange());
        assertEquals(0, snapshot.switchChanges());
        assertEquals(2, SettingsBackup.apply(snapshot));
        assertEquals("spoiler\ngiveaway now", Settings.HIDDEN_WORDS.savedValue());
        assertEquals("my team", Settings.KEPT_WORDS.savedValue());
        assertEquals("a file read back is the file", file, SettingsBackup.create());
        assertEquals("the same lists again change nothing", 0, SettingsBackup.parse(file).changes().size());

        Map<String, ?> before = store();
        // One phrase past the most a list holds.
        StringBuilder long51 = new StringBuilder("w0");
        for (int i = 1; i <= PostWords.MAX_PHRASES; i++) long51.append("\nw").append(i);
        for (Object refused : new Object[]{" spoiler", "spoiler\n", "spoiler\nSPOILER", "a", repeat('a', 61),
                "spoiler\n\nleak", long51.toString(), 5, true, JSONObject.NULL, new JSONObject()}) {
            for (Setting<?> list : Arrays.<Setting<?>>asList(SettingsBackup.HIDDEN, SettingsBackup.KEPT)) {
                JSONObject hostile = new JSONObject(file);
                hostile.getJSONObject("settings").put(list.key, refused);
                try {
                    SettingsBackup.parse(hostile.toString());
                    fail("a file with the list " + printable(String.valueOf(refused)) + " was read");
                } catch (SettingsBackup.Rejected rejected) {
                    assertEquals(printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
                    assertFalse("the refusal quotes the list", rejected.getMessage().contains("spoiler"));
                }
            }
        }
        assertEquals("a refused file wrote something", before, store());

        // An empty list is a list: a file can clear one.
        JSONObject emptied = new JSONObject(file);
        emptied.getJSONObject("settings").put(SettingsBackup.KEPT.key, "");
        SettingsBackup.Snapshot clearing = SettingsBackup.parse(emptied.toString());
        assertEquals("", clearing.keptChange());
        assertNull(clearing.hiddenChange());
        SettingsBackup.apply(clearing);
        assertEquals("", Settings.KEPT_WORDS.savedValue());

        // A file from before the lists were carried leaves them alone.
        SettingsBackup.Snapshot older = SettingsBackup.parse(fileWith(Settings.HIDE_SUGGESTED_POSTS, false));
        assertNull(older.hidden);
        assertNull(older.hiddenChange());
        SettingsBackup.apply(older);
        assertEquals("spoiler\ngiveaway now", Settings.HIDDEN_WORDS.savedValue());

        // A waiting import keeps its lists across a rebuilt page only while they're clean.
        Bundle state = snapshot.toBundle();
        SettingsBackup.Snapshot back = SettingsBackup.Snapshot.fromBundle(state);
        assertEquals("spoiler\ngiveaway now", back.hidden);
        assertEquals("my team", back.kept);
        state.putString("hidden_words", "spoiler\nSPOILER");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).hidden);
    }

    /**
     * The people, Pages and sites list goes out and comes back as its row stores it, a file can
     * clear it, an older file leaves it alone, and a list the row would clean differently refuses
     * the whole file without quoting it.
     */
    @Test
    public void theSourcesListRoundTripsAndComesBackOnlyAsACleanList() throws Exception {
        Settings.HIDDEN_SOURCES.save("example.com\nDaily Bugle");
        String file = SettingsBackup.create();
        Settings.HIDDEN_SOURCES.resetToDefault();

        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
        assertEquals("example.com\nDaily Bugle", snapshot.sourcesChange());
        assertEquals(0, snapshot.switchChanges());
        assertEquals(1, SettingsBackup.apply(snapshot));
        assertEquals("example.com\nDaily Bugle", Settings.HIDDEN_SOURCES.savedValue());
        assertEquals("a file read back is the file", file, SettingsBackup.create());
        assertEquals("the same list again changes nothing", 0, SettingsBackup.parse(file).changes().size());
        assertEquals("Your list of people, Pages and sites to hide will hold 2 entries.",
                SettingsBackupPreference.sourcesSentence(snapshot.sources));
        assertEquals("Your list of people, Pages and sites to hide will be empty.",
                SettingsBackupPreference.sourcesSentence(""));

        Map<String, ?> before = store();
        for (Object refused : new Object[]{" example.com", "example.com\n", "example.com\nEXAMPLE.com",
                "example.com\n\nDaily Bugle", repeat('a', PostSources.MAX_LENGTH + 1),
                "https://www.facebook.com/DailyBugle", 5, true, JSONObject.NULL, new JSONObject()}) {
            JSONObject hostile = new JSONObject(file);
            hostile.getJSONObject("settings").put(SettingsBackup.SOURCES.key, refused);
            try {
                SettingsBackup.parse(hostile.toString());
                fail("a file with the list " + printable(String.valueOf(refused)) + " was read");
            } catch (SettingsBackup.Rejected rejected) {
                assertEquals(printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
                assertFalse("the refusal quotes the list", rejected.getMessage().contains("example")
                        || rejected.getMessage().contains("Bugle"));
            }
        }
        assertEquals("a refused file wrote something", before, store());

        JSONObject emptied = new JSONObject(file);
        emptied.getJSONObject("settings").put(SettingsBackup.SOURCES.key, "");
        SettingsBackup.Snapshot clearing = SettingsBackup.parse(emptied.toString());
        assertEquals("", clearing.sourcesChange());
        SettingsBackup.apply(clearing);
        assertEquals("", Settings.HIDDEN_SOURCES.savedValue());

        Settings.HIDDEN_SOURCES.save("example.com");
        SettingsBackup.Snapshot older = SettingsBackup.parse(fileWith(Settings.HIDE_SUGGESTED_POSTS, false));
        assertNull(older.sources);
        assertNull(older.sourcesChange());
        SettingsBackup.apply(older);
        assertEquals("example.com", Settings.HIDDEN_SOURCES.savedValue());

        Bundle state = snapshot.toBundle();
        assertEquals("example.com\nDaily Bugle", SettingsBackup.Snapshot.fromBundle(state).sources);
        state.putString("hidden_sources", "example.com\nEXAMPLE.com");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).sources);
    }

    /**
     * The Menu tiles to hide go out and come back as their row stores them, a key this build
     * doesn't list included. A file can clear the list, an older file leaves it alone, and a list
     * the row would store differently refuses the whole file.
     */
    @Test
    public void menuTilePicksRoundTripWithKeysThisBuildDoesntList() throws Exception {
        String picks = "marketplace,gaming,hologram";
        Settings.HIDDEN_MENU_TILES.save(picks);
        Settings.SEEN_MENU_TILES.save("marketplace=Marketplace\nhologram=Hologram");
        String file = SettingsBackup.create();
        JSONObject written = new JSONObject(file).getJSONObject("settings");
        assertEquals(picks, written.get(SettingsBackup.MENU_TILES.key));
        assertFalse("the tiles Facebook showed went into the file", written.has(Settings.SEEN_MENU_TILES.key));
        Settings.HIDDEN_MENU_TILES.resetToDefault();
        Settings.SEEN_MENU_TILES.resetToDefault();

        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
        assertEquals(picks, snapshot.menuTilesChange());
        assertEquals(1, SettingsBackup.apply(snapshot));
        assertEquals(picks, Settings.HIDDEN_MENU_TILES.savedValue());
        assertEquals("an import wrote the tiles Facebook showed", "", Settings.SEEN_MENU_TILES.savedValue());
        assertEquals("a file read back is the file", file, SettingsBackup.create());
        assertEquals(0, SettingsBackup.parse(file).changes().size());
        assertEquals("Your list of Menu tiles to hide will hold 3 tiles.",
                SettingsBackupPreference.menuTilesSentence(snapshot.menuTiles));
        assertEquals("Your list of Menu tiles to hide will hold 1 tile.", SettingsBackupPreference.menuTilesSentence("gaming"));
        assertEquals("Your list of Menu tiles to hide will be empty.", SettingsBackupPreference.menuTilesSentence(""));

        Map<String, ?> before = store();
        for (Object refused : new Object[]{" gaming", "gaming,", "gaming,gaming", "Gaming", "gaming-tab", "1gaming", 5, true,
                JSONObject.NULL}) {
            JSONObject hostile = new JSONObject(file);
            hostile.getJSONObject("settings").put(SettingsBackup.MENU_TILES.key, refused);
            try {
                SettingsBackup.parse(hostile.toString());
                fail("a file with the list " + printable(String.valueOf(refused)) + " was read");
            } catch (SettingsBackup.Rejected rejected) {
                assertEquals(printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
            }
        }
        assertEquals("a refused file wrote something", before, store());

        JSONObject emptied = new JSONObject(file);
        emptied.getJSONObject("settings").put(SettingsBackup.MENU_TILES.key, "");
        assertEquals(1, SettingsBackup.apply(SettingsBackup.parse(emptied.toString())));
        assertEquals("", Settings.HIDDEN_MENU_TILES.savedValue());

        Settings.HIDDEN_MENU_TILES.save("gaming");
        JSONObject older = new JSONObject(file);
        older.getJSONObject("settings").remove(SettingsBackup.MENU_TILES.key);
        SettingsBackup.apply(SettingsBackup.parse(older.toString()));
        assertEquals("gaming", Settings.HIDDEN_MENU_TILES.savedValue());

        Bundle state = snapshot.toBundle();
        assertEquals(picks, SettingsBackup.Snapshot.fromBundle(state).menuTiles);
        state.putString("hidden_menu_tiles", "gaming,gaming");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).menuTiles);
    }

    /**
     * The feed types to hide go out and come back as their row stores them, a type this build has
     * never been handed included. A file can clear the list, an older file leaves it alone, and a
     * list the row would store differently refuses the whole file, an ordinary post's type with it.
     */
    @Test
    public void feedTypePicksRoundTripWithTypesThisBuildHasntSeen() throws Exception {
        String picks = "FriendsLocationsFeedUnit,GroupsYouShouldJoinFeedUnit,SomeNewerFeedUnit";
        Settings.HIDDEN_FEED_TYPES.save(picks);
        String file = SettingsBackup.create();
        JSONObject written = new JSONObject(file).getJSONObject("settings");
        assertEquals(picks, written.get(SettingsBackup.FEED_TYPES.key));
        Settings.HIDDEN_FEED_TYPES.resetToDefault();

        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
        assertEquals(picks, snapshot.feedTypesChange());
        assertEquals(1, SettingsBackup.apply(snapshot));
        assertEquals(picks, Settings.HIDDEN_FEED_TYPES.savedValue());
        assertEquals("a file read back is the file", file, SettingsBackup.create());
        assertEquals(0, SettingsBackup.parse(file).changes().size());
        assertEquals("Your list of feed types to hide will hold 3 types.",
                SettingsBackupPreference.feedTypesSentence(snapshot.feedTypes));
        assertEquals("Your list of feed types to hide will hold 1 type.",
                SettingsBackupPreference.feedTypesSentence("FriendsLocationsFeedUnit"));
        assertEquals("Your list of feed types to hide will be empty.", SettingsBackupPreference.feedTypesSentence(""));

        Map<String, ?> before = store();
        for (Object refused : new Object[]{" FriendsLocationsFeedUnit", "FriendsLocationsFeedUnit,",
                "FriendsLocationsFeedUnit,FriendsLocationsFeedUnit", "Story", "Feed-Unit", "1FeedUnit", 5, true,
                JSONObject.NULL}) {
            JSONObject hostile = new JSONObject(file);
            hostile.getJSONObject("settings").put(SettingsBackup.FEED_TYPES.key, refused);
            try {
                SettingsBackup.parse(hostile.toString());
                fail("a file with the list " + printable(String.valueOf(refused)) + " was read");
            } catch (SettingsBackup.Rejected rejected) {
                assertEquals(printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
            }
        }
        assertEquals("a refused file wrote something", before, store());

        JSONObject emptied = new JSONObject(file);
        emptied.getJSONObject("settings").put(SettingsBackup.FEED_TYPES.key, "");
        assertEquals(1, SettingsBackup.apply(SettingsBackup.parse(emptied.toString())));
        assertEquals("", Settings.HIDDEN_FEED_TYPES.savedValue());

        Settings.HIDDEN_FEED_TYPES.save("FriendsLocationsFeedUnit");
        JSONObject older = new JSONObject(file);
        older.getJSONObject("settings").remove(SettingsBackup.FEED_TYPES.key);
        SettingsBackup.apply(SettingsBackup.parse(older.toString()));
        assertEquals("FriendsLocationsFeedUnit", Settings.HIDDEN_FEED_TYPES.savedValue());

        Bundle state = snapshot.toBundle();
        assertEquals(picks, SettingsBackup.Snapshot.fromBundle(state).feedTypes);
        state.putString("hidden_feed_types", "Story");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).feedTypes);
    }

    /**
     * AMOLED's Background color (#214) goes out and comes back as its row keeps it. A file can set
     * it back to blank, an older file leaves it alone, and a color the row would refuse or keep
     * differently refuses the whole file.
     */
    @Test
    public void backgroundColorRoundTripsAsItsRowKeepsIt() throws Exception {
        Settings.AMOLED_BACKGROUND.save("#0D1117");
        String file = SettingsBackup.create();
        assertEquals("#0D1117", new JSONObject(file).getJSONObject("settings").get(SettingsBackup.BACKGROUND.key));
        Settings.AMOLED_BACKGROUND.resetToDefault();

        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
        assertEquals("#0D1117", snapshot.backgroundChange());
        assertEquals(1, SettingsBackup.apply(snapshot));
        assertEquals("#0D1117", Settings.AMOLED_BACKGROUND.savedValue());
        assertEquals("a file read back is the file", file, SettingsBackup.create());
        assertEquals(0, SettingsBackup.parse(file).changes().size());
        // L10n.isolate wraps the color code in direction marks (U+2068, U+2069), which aren't compared.
        assertEquals("If your patched Facebook includes the AMOLED black theme, dark mode's backgrounds will be #0D1117.",
                SettingsBackupPreference.backgroundSentence(snapshot.background)
                        .replace(String.valueOf((char) 0x2068), "").replace(String.valueOf((char) 0x2069), ""));
        assertEquals("If your patched Facebook includes the AMOLED black theme, dark mode's backgrounds will be the color "
                + "it was patched with.", SettingsBackupPreference.backgroundSentence(""));

        // A color saved some other way goes out as the row keeps it, and one it refuses goes out blank.
        Settings.AMOLED_BACKGROUND.save(" 0d1117 ");
        assertEquals("#0D1117", new JSONObject(SettingsBackup.create()).getJSONObject("settings").get(SettingsBackup.BACKGROUND.key));
        Settings.AMOLED_BACKGROUND.save("#FFFFFF");
        assertEquals("", new JSONObject(SettingsBackup.create()).getJSONObject("settings").get(SettingsBackup.BACKGROUND.key));
        Settings.AMOLED_BACKGROUND.save("#0D1117");

        Map<String, ?> before = store();
        for (Object refused : new Object[]{"#0d1117", "0D1117", " #0D1117", "#FF0D1117", "#800D1117", "#545454", "#FFFFFF",
                "black", "#0D111", 5, true, JSONObject.NULL}) {
            JSONObject hostile = new JSONObject(file);
            hostile.getJSONObject("settings").put(SettingsBackup.BACKGROUND.key, refused);
            try {
                SettingsBackup.parse(hostile.toString());
                fail("a file with the Background color " + printable(String.valueOf(refused)) + " was read");
            } catch (SettingsBackup.Rejected rejected) {
                assertEquals(printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
            }
        }
        assertEquals("a refused file wrote something", before, store());

        JSONObject lightest = new JSONObject(file);
        lightest.getJSONObject("settings").put(SettingsBackup.BACKGROUND.key, "#535353");
        assertEquals(1, SettingsBackup.apply(SettingsBackup.parse(lightest.toString())));
        assertEquals("#535353", Settings.AMOLED_BACKGROUND.savedValue());

        JSONObject blank = new JSONObject(file);
        blank.getJSONObject("settings").put(SettingsBackup.BACKGROUND.key, "");
        assertEquals(1, SettingsBackup.apply(SettingsBackup.parse(blank.toString())));
        assertEquals("", Settings.AMOLED_BACKGROUND.savedValue());

        Settings.AMOLED_BACKGROUND.save("#101010");
        JSONObject older = new JSONObject(file);
        older.getJSONObject("settings").remove(SettingsBackup.BACKGROUND.key);
        SettingsBackup.apply(SettingsBackup.parse(older.toString()));
        assertEquals("#101010", Settings.AMOLED_BACKGROUND.savedValue());

        Bundle state = snapshot.toBundle();
        assertEquals("#0D1117", SettingsBackup.Snapshot.fromBundle(state).background);
        state.putString("amoled_background", "#0d1117");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).background);
    }

    /**
     * AMOLED's Light background color (#214) goes out and comes back as its row keeps it, the same
     * way, and a color its rule refuses, too dark for light mode's text, refuses the whole file.
     */
    @Test
    public void lightBackgroundColorRoundTripsAsItsRowKeepsIt() throws Exception {
        Settings.AMOLED_LIGHT_BACKGROUND.save("#FFF8E7");
        String file = SettingsBackup.create();
        assertEquals("#FFF8E7", new JSONObject(file).getJSONObject("settings").get(SettingsBackup.LIGHT_BACKGROUND.key));
        Settings.AMOLED_LIGHT_BACKGROUND.resetToDefault();

        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
        assertEquals("#FFF8E7", snapshot.lightBackgroundChange());
        assertNull("the dark row's is left", snapshot.backgroundChange());
        assertEquals(1, SettingsBackup.apply(snapshot));
        assertEquals("#FFF8E7", Settings.AMOLED_LIGHT_BACKGROUND.savedValue());
        assertEquals("", Settings.AMOLED_BACKGROUND.savedValue());
        assertEquals("a file read back is the file", file, SettingsBackup.create());
        assertEquals(0, SettingsBackup.parse(file).changes().size());
        assertEquals("If your patched Facebook includes the AMOLED black theme, light mode's backgrounds will be #FFF8E7.",
                SettingsBackupPreference.lightBackgroundSentence(snapshot.lightBackground)
                        .replace(String.valueOf((char) 0x2068), "").replace(String.valueOf((char) 0x2069), ""));
        assertEquals("If your patched Facebook includes the AMOLED black theme, light mode's backgrounds will be "
                + "Facebook's own white.", SettingsBackupPreference.lightBackgroundSentence(""));

        // A color saved some other way goes out as the row keeps it, and one it refuses goes out blank.
        Settings.AMOLED_LIGHT_BACKGROUND.save(" fff8e7 ");
        assertEquals("#FFF8E7", new JSONObject(SettingsBackup.create()).getJSONObject("settings").get(SettingsBackup.LIGHT_BACKGROUND.key));
        Settings.AMOLED_LIGHT_BACKGROUND.save("#000000");
        assertEquals("", new JSONObject(SettingsBackup.create()).getJSONObject("settings").get(SettingsBackup.LIGHT_BACKGROUND.key));
        Settings.AMOLED_LIGHT_BACKGROUND.save("#FFF8E7");

        Map<String, ?> before = store();
        for (Object refused : new Object[]{"#fff8e7", "FFF8E7", " #FFF8E7", "#FFFFF8E7", "#80FFF8E7", "#A5A5A5", "#979797",
                "#2D88FF", "cream", "#FFF8E", 5, true, JSONObject.NULL}) {
            JSONObject hostile = new JSONObject(file);
            hostile.getJSONObject("settings").put(SettingsBackup.LIGHT_BACKGROUND.key, refused);
            try {
                SettingsBackup.parse(hostile.toString());
                fail("a file with the Light background color " + printable(String.valueOf(refused)) + " was read");
            } catch (SettingsBackup.Rejected rejected) {
                assertEquals(printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
            }
        }
        assertEquals("a refused file wrote something", before, store());

        JSONObject darkest = new JSONObject(file);
        darkest.getJSONObject("settings").put(SettingsBackup.LIGHT_BACKGROUND.key, "#A6A6A6");
        assertEquals(1, SettingsBackup.apply(SettingsBackup.parse(darkest.toString())));
        assertEquals("#A6A6A6", Settings.AMOLED_LIGHT_BACKGROUND.savedValue());

        JSONObject blank = new JSONObject(file);
        blank.getJSONObject("settings").put(SettingsBackup.LIGHT_BACKGROUND.key, "");
        assertEquals(1, SettingsBackup.apply(SettingsBackup.parse(blank.toString())));
        assertEquals("", Settings.AMOLED_LIGHT_BACKGROUND.savedValue());

        Settings.AMOLED_LIGHT_BACKGROUND.save("#F5F5F5");
        JSONObject older = new JSONObject(file);
        older.getJSONObject("settings").remove(SettingsBackup.LIGHT_BACKGROUND.key);
        SettingsBackup.apply(SettingsBackup.parse(older.toString()));
        assertEquals("#F5F5F5", Settings.AMOLED_LIGHT_BACKGROUND.savedValue());

        Bundle state = snapshot.toBundle();
        assertEquals("#FFF8E7", SettingsBackup.Snapshot.fromBundle(state).lightBackground);
        state.putString("amoled_light_background", "#fff8e7");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).lightBackground);
    }

    /**
     * Badge and logo colors (#263) and the Selected tab color (#297) go out and come back as their
     * rows keep them, and a code written any other way refuses the whole file.
     */
    @Test
    public void badgeLogoAndTabColorsRoundTripAsTheirRowsKeepThem() throws Exception {
        Settings.BADGE_COLOR.save("#2ECC71");
        Settings.LOGO_COLOR.save(" ff00aa ");
        Settings.TAB_COLOR.save("#e91e63");
        String file = SettingsBackup.create();
        JSONObject settings = new JSONObject(file).getJSONObject("settings");
        assertEquals("#2ECC71", settings.get(SettingsBackup.BADGE.key));
        assertEquals("#FF00AA", settings.get(SettingsBackup.LOGO.key));
        assertEquals("#E91E63", settings.get(SettingsBackup.TAB.key));
        Settings.BADGE_COLOR.resetToDefault();
        Settings.LOGO_COLOR.resetToDefault();
        Settings.TAB_COLOR.resetToDefault();

        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
        assertEquals("#2ECC71", snapshot.badgeChange());
        assertEquals("#FF00AA", snapshot.logoChange());
        assertEquals("#E91E63", snapshot.tabChange());
        assertEquals(3, SettingsBackup.apply(snapshot));
        assertEquals("#2ECC71", Settings.BADGE_COLOR.savedValue());
        assertEquals("#FF00AA", Settings.LOGO_COLOR.savedValue());
        assertEquals("#E91E63", Settings.TAB_COLOR.savedValue());
        assertEquals("If your patched Facebook includes Badge and logo colors, badges will be #2ECC71.",
                SettingsBackupPreference.markColorSentence(Mark.BADGE, snapshot.badge)
                        .replace(String.valueOf((char) 0x2068), "").replace(String.valueOf((char) 0x2069), ""));
        assertEquals("If your patched Facebook includes Badge and logo colors, the logo will be Facebook's blue.",
                SettingsBackupPreference.markColorSentence(Mark.LOGO, ""));
        assertEquals("If your patched Facebook includes Badge and logo colors, the selected tab will be #E91E63.",
                SettingsBackupPreference.markColorSentence(Mark.TAB, snapshot.tab)
                        .replace(String.valueOf((char) 0x2068), "").replace(String.valueOf((char) 0x2069), ""));

        Map<String, ?> before = store();
        for (StringSetting row : new StringSetting[]{SettingsBackup.LOGO, SettingsBackup.TAB}) {
            for (Object refused : new Object[]{"#2ecc71", "2ECC71", "#802ECC71", "green", "#2ECC7", 5, JSONObject.NULL}) {
                JSONObject hostile = new JSONObject(file);
                hostile.getJSONObject("settings").put(row.key, refused);
                try {
                    SettingsBackup.parse(hostile.toString());
                    fail("a file with " + row.key + " " + printable(String.valueOf(refused)) + " was read");
                } catch (SettingsBackup.Rejected rejected) {
                    assertEquals(printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
                }
            }
        }
        assertEquals("a refused file wrote something", before, store());

        Bundle state = snapshot.toBundle();
        assertEquals("#2ECC71", SettingsBackup.Snapshot.fromBundle(state).badge);
        assertEquals("#FF00AA", SettingsBackup.Snapshot.fromBundle(state).logo);
        assertEquals("#E91E63", SettingsBackup.Snapshot.fromBundle(state).tab);
    }

    /**
     * Home header text (#230) goes out and comes back as its row keeps it, one trimmed line, and
     * text written any other way refuses the whole file.
     */
    @Test
    public void homeHeaderTextRoundTripsAsItsRowKeepsIt() throws Exception {
        Settings.HEADER_TEXT.save("Matt's feed");
        String file = SettingsBackup.create();
        assertEquals("Matt's feed", new JSONObject(file).getJSONObject("settings").get(SettingsBackup.HEADER_TEXT.key));
        Settings.HEADER_TEXT.resetToDefault();

        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
        assertEquals("Matt's feed", snapshot.headerTextChange());
        assertEquals(1, SettingsBackup.apply(snapshot));
        assertEquals("Matt's feed", Settings.HEADER_TEXT.savedValue());
        assertEquals("a file read back is the file", file, SettingsBackup.create());
        assertEquals(0, SettingsBackup.parse(file).changes().size());
        assertEquals("If your patched Facebook includes Home header text, the top of Home will say Matt's feed.",
                SettingsBackupPreference.headerTextSentence(snapshot.headerText)
                        .replace(String.valueOf((char) 0x2068), "").replace(String.valueOf((char) 0x2069), ""));
        assertEquals("The top of Home will show Facebook's own logo.", SettingsBackupPreference.headerTextSentence(""));

        // Text saved some other way goes out as the header shows it.
        Settings.HEADER_TEXT.save("  Matt's \n feed ");
        assertEquals("Matt's feed", new JSONObject(SettingsBackup.create()).getJSONObject("settings").get(SettingsBackup.HEADER_TEXT.key));
        Settings.HEADER_TEXT.save("Matt's feed");

        Map<String, ?> before = store();
        for (Object refused : new Object[]{" Matt's feed", "Matt's feed ", "two\nlines", "tab\there",
                "0123456789012345678901234567890", 5, true, JSONObject.NULL}) {
            JSONObject hostile = new JSONObject(file);
            hostile.getJSONObject("settings").put(SettingsBackup.HEADER_TEXT.key, refused);
            try {
                SettingsBackup.parse(hostile.toString());
                fail("a file with the header text " + printable(String.valueOf(refused)) + " was read");
            } catch (SettingsBackup.Rejected rejected) {
                assertEquals(printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
            }
        }
        assertEquals("a refused file wrote something", before, store());

        JSONObject blank = new JSONObject(file);
        blank.getJSONObject("settings").put(SettingsBackup.HEADER_TEXT.key, "");
        assertEquals(1, SettingsBackup.apply(SettingsBackup.parse(blank.toString())));
        assertEquals("", Settings.HEADER_TEXT.savedValue());

        Settings.HEADER_TEXT.save("Home");
        JSONObject older = new JSONObject(file);
        older.getJSONObject("settings").remove(SettingsBackup.HEADER_TEXT.key);
        SettingsBackup.apply(SettingsBackup.parse(older.toString()));
        assertEquals("Home", Settings.HEADER_TEXT.savedValue());

        Bundle state = snapshot.toBundle();
        assertEquals("Matt's feed", SettingsBackup.Snapshot.fromBundle(state).headerText);
        state.putString("header_text", " Matt's feed");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).headerText);
    }

    /**
     * The share sheet items to hide go out and come back as their row stores them, a type this
     * build doesn't name included, so a newer Facebook's survive the trip. A file can clear the
     * list, an older file leaves it alone, the types Facebook offered on this phone stay on it, and
     * a list the row would store differently refuses the whole file.
     */
    @Test
    public void shareSheetPicksRoundTripWithTypesThisBuildDoesntName() throws Exception {
        String picks = "SHARE_TO_META_AI,SHARE_TO_HOLOGRAM,COPY_LINK";
        Settings.HIDDEN_SHARE_ITEMS.save(picks);
        Settings.SEEN_SHARE_ITEMS.save("SHARE_NOW,SHARE_TO_HOLOGRAM");
        String file = SettingsBackup.create();
        JSONObject written = new JSONObject(file).getJSONObject("settings");
        assertEquals(picks, written.get(SettingsBackup.SHARE_ITEMS.key));
        assertFalse("the types Facebook offered went into the file", written.has(Settings.SEEN_SHARE_ITEMS.key));
        Settings.HIDDEN_SHARE_ITEMS.resetToDefault();
        Settings.SEEN_SHARE_ITEMS.resetToDefault();

        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
        assertEquals(0, snapshot.unknown);
        assertEquals(picks, snapshot.shareItemsChange());
        assertEquals(0, snapshot.switchChanges());
        assertEquals(1, SettingsBackup.apply(snapshot));
        assertEquals(picks, Settings.HIDDEN_SHARE_ITEMS.savedValue());
        assertEquals("an import wrote the types Facebook offered", "", Settings.SEEN_SHARE_ITEMS.savedValue());
        assertTrue("the list doesn't offer the type this build doesn't name",
                ShareSheetItems.choices().contains("SHARE_TO_HOLOGRAM"));
        assertEquals("a file read back is the file", file, SettingsBackup.create());
        assertEquals("the same list again changes nothing", 0, SettingsBackup.parse(file).changes().size());
        assertEquals("Your list of share sheet items to hide will hold 3 items.",
                SettingsBackupPreference.shareItemsSentence(snapshot.shareItems));
        assertEquals("Your list of share sheet items to hide will hold 1 item.",
                SettingsBackupPreference.shareItemsSentence("COPY_LINK"));
        assertEquals("Your list of share sheet items to hide will be empty.",
                SettingsBackupPreference.shareItemsSentence(""));

        Map<String, ?> before = store();
        for (Object refused : new Object[]{" COPY_LINK", "COPY_LINK,", ",COPY_LINK", "COPY_LINK,,SHARE_NOW",
                "COPY_LINK, SHARE_NOW", "COPY_LINK,COPY_LINK", "copy_link", "COPY-LINK", "1COPY_LINK",
                shareTypesPast(ShareSheetItems.MAX_HIDDEN_CHARS), 5, true, JSONObject.NULL, new JSONObject()}) {
            JSONObject hostile = new JSONObject(file);
            hostile.getJSONObject("settings").put(SettingsBackup.SHARE_ITEMS.key, refused);
            try {
                SettingsBackup.parse(hostile.toString());
                fail("a file with the list " + printable(String.valueOf(refused)) + " was read");
            } catch (SettingsBackup.Rejected rejected) {
                assertEquals(printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
            }
        }
        assertEquals("a refused file wrote something", before, store());

        JSONObject emptied = new JSONObject(file);
        emptied.getJSONObject("settings").put(SettingsBackup.SHARE_ITEMS.key, "");
        SettingsBackup.Snapshot clearing = SettingsBackup.parse(emptied.toString());
        assertEquals("", clearing.shareItemsChange());
        assertEquals(1, SettingsBackup.apply(clearing));
        assertEquals("", Settings.HIDDEN_SHARE_ITEMS.savedValue());

        Settings.HIDDEN_SHARE_ITEMS.save("COPY_LINK");
        JSONObject older = new JSONObject(file);
        older.getJSONObject("settings").remove(SettingsBackup.SHARE_ITEMS.key);
        SettingsBackup.Snapshot fromOlder = SettingsBackup.parse(older.toString());
        assertNull(fromOlder.shareItems);
        assertNull(fromOlder.shareItemsChange());
        SettingsBackup.apply(fromOlder);
        assertEquals("COPY_LINK", Settings.HIDDEN_SHARE_ITEMS.savedValue());

        Bundle state = snapshot.toBundle();
        assertEquals(picks, SettingsBackup.Snapshot.fromBundle(state).shareItems);
        state.putString("hidden_share_items", "COPY_LINK,COPY_LINK");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).shareItems);
    }

    /** Clean share item types, commas between, one type past [length] chars. */
    private static String shareTypesPast(int length) {
        StringBuilder list = new StringBuilder();
        for (int i = 0; list.length() <= length; i++) {
            if (i > 0) list.append(',');
            list.append("SHARE_TO_APP_").append(i);
        }
        return list.toString();
    }

    /**
     * #58: lists that fill the room the two share to the byte, written with emoji, a CJK
     * character, an escaped slash and plain letters, go out and come back whole, both at once, with
     * every other value a file carries at its longest, in a file inside the size limit. The people,
     * Pages and sites list at its own room, a slash in every other character, fits on top.
     */
    @Test
    public void wordListsFillingTheirRoomRoundTripWithEverythingElseAtItsLongest() throws Exception {
        String hidden = roomFilling(PostWords.MAX_LIST_BYTES / 2);
        String kept = PostWordsTest.asciiOfBytes(PostWords.MAX_LIST_BYTES - PostWords.encodedBytes(hidden));
        assertEquals(PostWords.MAX_LIST_BYTES, PostWords.encodedBytes(hidden) + PostWords.encodedBytes(kept));
        assertTrue(PostWords.fits(hidden, kept));
        assertFalse(PostWords.fits(hidden, kept + "x"));
        Settings.HIDDEN_WORDS.save(hidden);
        Settings.KEPT_WORDS.save(kept);
        // Every other value at the longest a file takes back: a folder and a file name kept from
        // a run of four-byte characters longer than either row keeps, and an app name of the
        // parser's longest string.
        String folder = SaveFolder.sanitize(emoji(80));
        assertEquals("a folder keeps fifty", emoji(50), folder);
        Settings.SAVE_FOLDER.save(folder);
        String fileName = FileNameTemplate.sanitize(emoji(80));
        assertEquals(fileName, FileNameTemplate.sanitize(fileName));
        assertEquals("a file name keeps fifty, its date token included", 50,
                fileName.codePointCount(0, fileName.length()));
        Settings.FILENAME_TEMPLATE.save(fileName);
        Settings.PHOTO_FILENAME_TEMPLATE.save(FileNameTemplate.sanitizePhoto(emoji(80)));
        assertEquals("a subfolder keeps fifty", emoji(50), SaveFolder.cleanSubfolder(emoji(80)));
        Settings.VIDEO_SUBFOLDER.save(emoji(80));
        Settings.PHOTO_SUBFOLDER.save(emoji(80));
        String app = "a." + repeat('b', 1024 - 2);
        assertTrue(SendLink.isFileApp(app));
        Settings.SEND_TO_APP.save(app);
        String shareItems = ShareSheetItems.clean(shareTypesPast(ShareSheetItems.MAX_HIDDEN_CHARS));
        assertTrue("the share items stop within a type of their room",
                shareItems.length() > ShareSheetItems.MAX_HIDDEN_CHARS - 20);
        Settings.HIDDEN_SHARE_ITEMS.save(shareItems);
        String file = SettingsBackup.create();
        int size = file.getBytes(StandardCharsets.UTF_8).length;
        assertTrue("a file of " + size + " bytes", size <= SettingsBackup.MAX_BYTES);
        // Both word lists and every other value at their longest, the two file names among them, leave
        // the sources list the rest of MAX_BYTES.
        assertTrue("without the sources list, MAX_BYTES less its room at most: " + size,
                size <= SettingsBackup.MAX_BYTES - PostSources.MAX_LIST_BYTES);

        StringBuilder typed = new StringBuilder();
        for (int i = 0; i < PostSources.MAX_RULES; i++) {
            StringBuilder line = new StringBuilder("https://s").append(i).append(".example.com");
            while (line.length() < PostSources.MAX_LENGTH) line.append("/x");
            typed.append(line, 0, PostSources.MAX_LENGTH).append('\n');
        }
        String sources = PostSources.clean(typed.toString());
        assertTrue("the list stops within a line of its room",
                PostWords.encodedBytes(sources) > PostSources.MAX_LIST_BYTES - 2 * PostSources.MAX_LENGTH - 2);
        Settings.HIDDEN_SOURCES.save(sources);
        file = SettingsBackup.create();
        size = file.getBytes(StandardCharsets.UTF_8).length;
        assertTrue("a file of " + size + " bytes with the sources list too", size <= SettingsBackup.MAX_BYTES);
        Settings.HIDDEN_WORDS.resetToDefault();
        Settings.KEPT_WORDS.resetToDefault();
        Settings.HIDDEN_SOURCES.resetToDefault();

        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
        assertEquals(hidden, snapshot.hiddenChange());
        assertEquals(kept, snapshot.keptChange());
        assertEquals(sources, snapshot.sourcesChange());
        assertEquals(3, SettingsBackup.apply(snapshot));
        assertEquals(hidden, Settings.HIDDEN_WORDS.savedValue());
        assertEquals(kept, Settings.KEPT_WORDS.savedValue());
        assertEquals(sources, Settings.HIDDEN_SOURCES.savedValue());
        assertEquals("a file read back is the file", file, SettingsBackup.create());
    }

    /** #58: a thousand short phrases in each list go out and come back whole. */
    @Test
    public void aThousandShortPhrasesInEachListRoundTrip() throws Exception {
        String hidden = WordsCorpus.hide();
        String kept = WordsCorpus.keep();
        Settings.HIDDEN_WORDS.save(hidden);
        Settings.KEPT_WORDS.save(kept);
        String file = SettingsBackup.create();
        assertTrue(file.getBytes(StandardCharsets.UTF_8).length <= SettingsBackup.MAX_BYTES);
        Settings.HIDDEN_WORDS.resetToDefault();
        Settings.KEPT_WORDS.resetToDefault();
        assertEquals(2, SettingsBackup.apply(SettingsBackup.parse(file)));
        assertEquals(1000, PostWords.count(Settings.HIDDEN_WORDS.savedValue()));
        assertEquals(hidden, Settings.HIDDEN_WORDS.savedValue());
        assertEquals(kept, Settings.KEPT_WORDS.savedValue());
    }

    /**
     * #58: lists past the room they share are refused whole, and nothing changes: two in one file,
     * or one beside the list the file leaves as it is on the phone. Within the room, both come in.
     */
    @Test
    public void wordListsPastTheirRoomAreRefusedAndChangeNothing() throws Exception {
        String half = PostWordsTest.asciiOfBytes(PostWords.MAX_LIST_BYTES / 2);
        String more = PostWordsTest.asciiOfBytes(PostWords.MAX_LIST_BYTES / 2 + 1);
        JSONObject both = new JSONObject(SettingsBackup.create());
        both.getJSONObject("settings").put(SettingsBackup.HIDDEN.key, half).put(SettingsBackup.KEPT.key, more);
        assertEquals(SettingsBackup.Reason.WORDS, reasonFor(both.toString()));

        Settings.KEPT_WORDS.save(more);
        Map<String, ?> before = store();
        JSONObject one = new JSONObject(SettingsBackup.create());
        one.getJSONObject("settings").put(SettingsBackup.HIDDEN.key, half).remove(SettingsBackup.KEPT.key);
        assertEquals("beside the phone's list", SettingsBackup.Reason.WORDS, reasonFor(one.toString()));
        assertEquals(before, store());

        Settings.KEPT_WORDS.save(half);
        assertEquals(half, SettingsBackup.parse(one.toString()).hiddenChange());
        both.getJSONObject("settings").put(SettingsBackup.KEPT.key, half);
        assertEquals(half, SettingsBackup.parse(both.toString()).hiddenChange());
    }

    @Test
    public void aPartialWordListImportRechecksSharedRoomBeforeApplying() throws Exception {
        String half = PostWordsTest.asciiOfBytes(PostWords.MAX_LIST_BYTES / 2);
        String more = PostWordsTest.asciiOfBytes(PostWords.MAX_LIST_BYTES / 2 + 1);
        JSONObject file = new JSONObject(fileWith());
        file.getJSONObject("settings").put(SettingsBackup.HIDDEN.key, half);
        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file.toString());
        Settings.KEPT_WORDS.save(more);
        Map<String, ?> before = store();

        assertWordRoomRefused(snapshot);

        assertEquals(before, store());
        assertEquals("", Settings.HIDDEN_WORDS.savedValue());
        assertEquals(more, Settings.KEPT_WORDS.savedValue());
        Settings.KEPT_WORDS.save(half);
        assertEquals(1, SettingsBackup.apply(snapshot));
        assertTrue(PostWords.fits(Settings.HIDDEN_WORDS.savedValue(), Settings.KEPT_WORDS.savedValue()));
        SettingsBackup.parse(SettingsBackup.create());
    }

    @Test
    public void aRestoredWordListPreviewRechecksSharedRoomBeforeApplying() throws Exception {
        String half = PostWordsTest.asciiOfBytes(PostWords.MAX_LIST_BYTES / 2);
        String more = PostWordsTest.asciiOfBytes(PostWords.MAX_LIST_BYTES / 2 + 1);
        JSONObject file = new JSONObject(fileWith());
        file.getJSONObject("settings").put(SettingsBackup.HIDDEN.key, half);
        Bundle saved = SettingsBackup.parse(file.toString()).toBundle();
        Settings.KEPT_WORDS.save(more);
        Map<String, ?> before = store();

        SettingsBackup.Snapshot restored = SettingsBackup.Snapshot.fromBundle(saved);
        if (restored != null) assertWordRoomRefused(restored);

        assertEquals(before, store());
        assertEquals("", Settings.HIDDEN_WORDS.savedValue());
        assertEquals(more, Settings.KEPT_WORDS.savedValue());
        Settings.KEPT_WORDS.save(half);
        SettingsBackup.Snapshot fits = SettingsBackup.Snapshot.fromBundle(saved);
        assertNotNull("a restored preview that fits was dropped", fits);
        assertEquals(1, SettingsBackup.apply(fits));
    }

    private static void assertWordRoomRefused(SettingsBackup.Snapshot snapshot) throws Exception {
        try {
            SettingsBackup.apply(snapshot);
            fail("the import kept word lists past their shared room");
        } catch (SettingsBackup.ApplyFailed refused) {
            assertTrue("refusing a stale preview changed the previous settings", refused.rolledBack);
        }
    }

    /** Lists of 1,024 and 1,025 chars, the most a file's string could be before, both come back. */
    @Test
    public void listsEitherSideOfTheOldStringLimitComeBack() throws Exception {
        for (int length : new int[]{1024, 1025}) {
            String list = asciiList(length);
            assertEquals(length, list.length());
            assertTrue(PostWords.isClean(list));
            Settings.HIDDEN_WORDS.save(list);
            String file = SettingsBackup.create();
            Settings.HIDDEN_WORDS.resetToDefault();
            SettingsBackup.apply(SettingsBackup.parse(file));
            assertEquals(length + " chars", list, Settings.HIDDEN_WORDS.savedValue());
        }
    }

    /**
     * A string past the longest list, as a list, an unknown value or a name, or one that never
     * ends, is refused before anything is read into a setting, and every setting stays as it was.
     */
    @Test
    public void aStringPastTheLongestListIsRefusedAndChangesNothing() throws Exception {
        assertEquals(PostWords.MAX_STORED_CHARS, SettingsBackup.MAX_STRING_CHARS);
        Settings.HIDDEN_WORDS.save("spoiler");
        Settings.KEPT_WORDS.save("my team");
        Settings.SAVE_FOLDER.save("Clips");
        Settings.HIDE_SUGGESTED_POSTS.save(!Settings.HIDE_SUGGESTED_POSTS.defaultValue);
        String file = SettingsBackup.create();
        Map<String, ?> before = store();
        String past = repeat('x', SettingsBackup.MAX_STRING_CHARS + 1);

        JSONObject asList = new JSONObject(file);
        asList.getJSONObject("settings").put(SettingsBackup.HIDDEN.key, past);
        JSONObject asUnknown = new JSONObject(file);
        asUnknown.getJSONObject("settings").put("later", past);
        JSONObject asName = new JSONObject(file);
        asName.getJSONObject("settings").put(past, true);
        String endless = file.substring(0, file.lastIndexOf('}')) + ",\"later\":\"" + repeat('x', SettingsBackup.MAX_STRING_CHARS + 1);
        for (String refused : new String[]{asList.toString(), asUnknown.toString(), asName.toString(), endless}) {
            assertTrue(refused.getBytes(StandardCharsets.UTF_8).length <= SettingsBackup.MAX_BYTES);
            assertEquals(SettingsBackup.Reason.DAMAGED, reasonFor(refused));
        }
        String oversized = file.substring(0, file.lastIndexOf('}')) + ",\"later\":\"" + repeat('x', SettingsBackup.MAX_BYTES);
        assertEquals(SettingsBackup.Reason.SIZE, reasonFor(oversized));
        assertEquals(SettingsBackup.Reason.SIZE, readReason(new ByteArrayInputStream(oversized.getBytes(StandardCharsets.UTF_8))));

        // The longest list beside the kept one still comes in.
        String longest = PostWordsTest.asciiOfBytes(PostWords.MAX_LIST_BYTES - PostWords.encodedBytes("my team"));
        JSONObject withLongest = new JSONObject(file);
        withLongest.getJSONObject("settings").put(SettingsBackup.HIDDEN.key, longest);
        assertEquals(longest, SettingsBackup.parse(withLongest.toString()).hiddenChange());
        assertEquals("a refused file wrote something", before, store());
    }

    /** [count] grinning faces, four bytes each in UTF-8. */
    private static String emoji(int count) {
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < count; i++) run.appendCodePoint(0x1F600);
        return run.toString();
    }

    /**
     * A clean list taking exactly [bytes] in a file: phrases of a CJK character, a slash and emoji
     * to sixty characters, each its own emoji, then plain letters for the rest.
     */
    private static String roomFilling(int bytes) {
        List<String> phrases = new ArrayList<>();
        int used = 0;
        for (int phrase = 0; ; phrase++) {
            StringBuilder next = new StringBuilder("猫/");
            while (next.codePointCount(0, next.length()) < PostWords.MAX_LENGTH) next.appendCodePoint(0x1F600 + phrase);
            int cost = PostWords.encodedBytes(next.toString()) + (phrases.isEmpty() ? 0 : 2);
            // Leave the letters a line break and a phrase of eight at least.
            if (used + cost > bytes - 2 - 8) break;
            phrases.add(next.toString());
            used += cost;
        }
        String letters = PostWordsTest.asciiOfBytes(bytes - used - 2);
        String list = String.join("\n", phrases) + "\n" + letters;
        assertTrue(PostWords.isClean(list));
        assertEquals(bytes, PostWords.encodedBytes(list));
        return list;
    }

    /** A clean list of plain letters exactly [length] chars long. */
    private static String asciiList(int length) {
        StringBuilder list = new StringBuilder();
        for (int phrase = 0; list.length() < length; phrase++) {
            if (phrase > 0) list.append('\n');
            String start = String.format(Locale.ROOT, "p%02d", phrase);
            int room = Math.min(PostWords.MAX_LENGTH, length - list.length());
            list.append(start).append(repeat('x', room - start.length()));
        }
        return list.toString();
    }

    /** The preview and the toast say how many phrases each list will hold, never which. */
    @Test
    public void importOfTheWordListsSaysHowManyPhrasesEachHolds() {
        assertEquals("Settings imported. Your list of words to hide will hold 2 words or phrases. Your list of words "
                        + "that keep a post will be empty.",
                SettingsBackupPreference.importedMessage(0, null, null, null, null, null, "spoiler\nleak", ""));
        assertEquals("Settings imported. 1 switch changed. Your list of words to hide will be empty. Your list of "
                        + "words that keep a post will hold 1 word or phrase.",
                SettingsBackupPreference.importedMessage(1, null, null, null, null, null, "", "my team"));
        String withFolder = SettingsBackupPreference.importedMessage(0, "Clips", null, null, null, null, "spoiler", null);
        assertFalse(withFolder, withFolder.contains("spoiler"));
        assertTrue(withFolder, withFolder.contains("1 word or phrase"));
    }

    /**
     * A file a phone on a newer Android wrote can name a folder with a character this one doesn't
     * know yet. The folder comes in as the name the saves here will use, and the switches in the
     * same file come in with it, where before the whole file was refused.
     */
    @Test
    public void aFolderFromANewerAndroidComesInAsTheNameSavesUseHere() throws Exception {
        int newer = 0x50000;
        assertEquals("the stand-in for a newer character has to be unknown here",
                Character.UNASSIGNED, Character.getType(newer));
        String unknown = new String(Character.toChars(newer));
        Settings.SAVE_FOLDER.save("Old");
        boolean flipped = !Settings.HIDE_SUGGESTED_POSTS.savedValue();
        JSONObject file = new JSONObject(fileWith(Settings.HIDE_SUGGESTED_POSTS, flipped));
        file.getJSONObject("settings").put(SettingsBackup.FOLDER.key, "Clips " + unknown);

        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file.toString());
        assertEquals("Clips", snapshot.folder);
        assertEquals("Clips", snapshot.folderChange());
        assertEquals(1, snapshot.switchChanges());
        assertEquals(2, SettingsBackup.apply(snapshot));
        assertEquals("Clips", Settings.SAVE_FOLDER.savedValue());
        assertEquals(flipped, Settings.HIDE_SUGGESTED_POSTS.savedValue());

        file.getJSONObject("settings").put(SettingsBackup.FOLDER.key, unknown);
        assertEquals("a name of nothing this phone knows is the default folder",
                app.morphe.extension.facebook.download.SaveFolder.DEFAULT, SettingsBackup.parse(file.toString()).folder);
        for (String refused : new String[]{"../" + unknown, "My/" + unknown, "." + unknown}) {
            file.getJSONObject("settings").put(SettingsBackup.FOLDER.key, refused);
            try {
                SettingsBackup.parse(file.toString());
                fail("a file with the folder " + printable(refused) + " was read");
            } catch (SettingsBackup.Rejected rejected) {
                assertEquals(printable(refused), SettingsBackup.Reason.VALUE, rejected.reason);
            }
        }
    }

    /**
     * The quality goes out as its file value and comes back only as one this build offers: any
     * other value, or one that isn't text, refuses the whole file.
     */
    @Test
    public void theQualityRoundTripsAndComesBackOnlyAsOneThisBuildOffers() throws Exception {
        for (DownloadQuality quality
                : DownloadQuality.values()) {
            Settings.DOWNLOAD_QUALITY.save(quality);
            String file = SettingsBackup.create();
            assertEquals(quality.fileValue, new JSONObject(file).getJSONObject("settings").get(SettingsBackup.QUALITY.key));
            Settings.DOWNLOAD_QUALITY.save(quality == DownloadQuality.P720
                    ? DownloadQuality.SMALLEST
                    : DownloadQuality.P720);

            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
            assertEquals(quality, snapshot.quality);
            assertEquals(quality, snapshot.qualityChange());
            assertEquals(0, snapshot.switchChanges());
            assertEquals(Collections.singletonMap(SettingsBackup.QUALITY, quality), snapshot.changes());
            assertEquals(1, SettingsBackup.apply(snapshot));
            assertEquals(quality, Settings.DOWNLOAD_QUALITY.savedValue());
            assertEquals("a file read back is the file", file, SettingsBackup.create());
            assertEquals("the same quality again changes nothing", 0, SettingsBackup.parse(file).changes().size());
        }

        Settings.DOWNLOAD_QUALITY.save(DownloadQuality.P480);
        String file = SettingsBackup.create();
        Map<String, ?> before = store();
        for (Object refused : new Object[]{"BEST", "P480", "Best", "720", "720P", "1440p", " 480p", "", 480, true,
                JSONObject.NULL, new JSONObject(), new org.json.JSONArray()}) {
            JSONObject hostile = new JSONObject(file);
            hostile.getJSONObject("settings").put(SettingsBackup.QUALITY.key, refused);
            try {
                SettingsBackup.parse(hostile.toString());
                fail("a file with the quality " + printable(String.valueOf(refused)) + " was read");
            } catch (SettingsBackup.Rejected rejected) {
                assertEquals(printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
            }
        }
        assertEquals("a refused file wrote something", before, store());

        // A file from before the quality was carried leaves it alone.
        SettingsBackup.Snapshot older = SettingsBackup.parse(fileWith(Settings.HIDE_SUGGESTED_POSTS, false));
        assertNull(older.quality);
        assertNull(older.qualityChange());
        SettingsBackup.apply(older);
        assertEquals(DownloadQuality.P480, Settings.DOWNLOAD_QUALITY.savedValue());
    }

    /**
     * The file name goes out as the template the saves use and comes back only as one: a value the
     * sanitizer would change, or that isn't text, refuses the whole file, so a file can't name a
     * path, a photo's name, an extension or one name for every video. A character this phone
     * doesn't know yet counts as an ordinary one.
     */
    @Test
    public void theFileNameRoundTripsAndComesBackOnlyAsOneCleanName() throws Exception {
        Settings.FILENAME_TEMPLATE.save("Reel {video_id}");
        String file = SettingsBackup.create();
        Settings.FILENAME_TEMPLATE.resetToDefault();

        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
        assertEquals("Reel {video_id}", snapshot.fileName);
        assertEquals("Reel {video_id}", snapshot.fileNameChange());
        assertEquals(Collections.singletonMap(SettingsBackup.FILE_NAME, "Reel {video_id}"), snapshot.changes());
        assertEquals(1, SettingsBackup.apply(snapshot));
        assertEquals("Reel {video_id}", Settings.FILENAME_TEMPLATE.savedValue());
        assertEquals("a file read back is the file", file, SettingsBackup.create());
        assertEquals("the same name again changes nothing", 0, SettingsBackup.parse(file).changes().size());

        // Stored past the row as a name with no token, it goes out as the one the saves use.
        Settings.FILENAME_TEMPLATE.save("Clip.mp4");
        assertEquals("Clip_{date}", new JSONObject(SettingsBackup.create()).getJSONObject("settings")
                .get(SettingsBackup.FILE_NAME.key));
        Settings.FILENAME_TEMPLATE.save("Reel {video_id}");

        Map<String, ?> before = store();
        for (Object refused : new Object[]{"../{date}", "My/{date}", "a\\b", ".{date}", "{date}.", " {date}", "",
                "a\u200Bb", "a\nb", repeat('a', 51), "Clip", "{date}.mp4", "FB_IMG_{date}", ".nomedia",
                5, true, JSONObject.NULL, new JSONObject()}) {
            JSONObject hostile = new JSONObject(file);
            hostile.getJSONObject("settings").put(SettingsBackup.FILE_NAME.key, refused);
            try {
                SettingsBackup.parse(hostile.toString());
                fail("a file with the name " + printable(String.valueOf(refused)) + " was read");
            } catch (SettingsBackup.Rejected rejected) {
                assertEquals(printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
            }
        }
        assertEquals("a refused file wrote something", before, store());

        String unknown = new String(Character.toChars(0x50000));
        JSONObject newer = new JSONObject(file);
        newer.getJSONObject("settings").put(SettingsBackup.FILE_NAME.key, "Clip " + unknown + " {date}");
        assertEquals("Clip {date}", SettingsBackup.parse(newer.toString()).fileName);

        // A file from before the name was carried leaves it alone.
        SettingsBackup.Snapshot older = SettingsBackup.parse(fileWith(Settings.HIDE_SUGGESTED_POSTS, false));
        assertNull(older.fileName);
        assertNull(older.fileNameChange());
        SettingsBackup.apply(older);
        assertEquals("Reel {video_id}", Settings.FILENAME_TEMPLATE.savedValue());

        // A preview kept across a rebuild keeps its name, and only a clean one comes back.
        Bundle state = SettingsBackup.parse(file).toBundle();
        assertEquals("Reel {video_id}", SettingsBackup.Snapshot.fromBundle(state).fileName);
        state.putString("file_name", "../{date}");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).fileName);
        state.putInt("file_name", 5);
        assertNull(SettingsBackup.Snapshot.fromBundle(state).fileName);
    }

    /**
     * The photo file name goes out as the template photo saves use and comes back only as one, the
     * way the video's does: a video's name, an extension or one name for every photo refuses the
     * whole file.
     */
    @Test
    public void thePhotoNameRoundTripsAndComesBackOnlyAsOneCleanName() throws Exception {
        Settings.PHOTO_FILENAME_TEMPLATE.save("Shot {photo_id}");
        String file = SettingsBackup.create();
        Settings.PHOTO_FILENAME_TEMPLATE.resetToDefault();

        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
        assertEquals("Shot {photo_id}", snapshot.photoName);
        assertEquals("Shot {photo_id}", snapshot.photoNameChange());
        assertNull("the video's name is the one it was", snapshot.fileNameChange());
        assertEquals(Collections.singletonMap(SettingsBackup.PHOTO_NAME, "Shot {photo_id}"), snapshot.changes());
        assertEquals(1, SettingsBackup.apply(snapshot));
        assertEquals("Shot {photo_id}", Settings.PHOTO_FILENAME_TEMPLATE.savedValue());
        assertEquals(FileNameTemplate.DEFAULT, Settings.FILENAME_TEMPLATE.savedValue());
        assertEquals("a file read back is the file", file, SettingsBackup.create());
        assertEquals("the same name again changes nothing", 0, SettingsBackup.parse(file).changes().size());

        Map<String, ?> before = store();
        for (Object refused : new Object[]{"../{date}", "a\\b", ".{date}", " {date}", "", "Shot", "{date}.jpg",
                "FB_VID_{date}", ".nomedia", 5, true, JSONObject.NULL, new JSONObject()}) {
            JSONObject hostile = new JSONObject(file);
            hostile.getJSONObject("settings").put(SettingsBackup.PHOTO_NAME.key, refused);
            try {
                SettingsBackup.parse(hostile.toString());
                fail("a file with the photo name " + printable(String.valueOf(refused)) + " was read");
            } catch (SettingsBackup.Rejected rejected) {
                assertEquals(printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
            }
        }
        assertEquals("a refused file wrote something", before, store());

        // A file from before the photo name was carried leaves it alone.
        SettingsBackup.Snapshot older = SettingsBackup.parse(fileWith(Settings.HIDE_SUGGESTED_POSTS, false));
        assertNull(older.photoName);
        assertNull(older.photoNameChange());
        SettingsBackup.apply(older);
        assertEquals("Shot {photo_id}", Settings.PHOTO_FILENAME_TEMPLATE.savedValue());

        // A preview kept across a rebuild keeps its photo name, and only a clean one comes back.
        Bundle state = SettingsBackup.parse(file).toBundle();
        assertEquals("Shot {photo_id}", SettingsBackup.Snapshot.fromBundle(state).photoName);
        state.putString("photo_name", "FB_VID_{date}");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).photoName);
        state.putInt("photo_name", 5);
        assertNull(SettingsBackup.Snapshot.fromBundle(state).photoName);

        assertEquals("Settings imported. Saved photos will be named "
                        + app.morphe.extension.shared.L10n.isolate("Shot {photo_id}") + ".",
                SettingsBackupPreference.importedMessage(0, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, "Shot {photo_id}"));
    }

    /** A file that renames saved videos says so, before and after, beside what else it changes. */
    @Test
    public void importOfAFileNameSaysWhatVideosWillBeNamed() throws Exception {
        JSONObject file = new JSONObject(fileWith(Settings.DOWNLOAD_REELS, false));
        file.getJSONObject("settings").put(SettingsBackup.FILE_NAME.key, "{date}_{video_id}")
                .put(SettingsBackup.QUALITY.key, "720p");
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            deliver(activity, tap(activity, page, IMPORT_ROW), file.toString());
            AlertDialog preview = shownPreview();
            String quality = "Videos will save at " + app.morphe.extension.shared.L10n.isolate("720p")
                    + " or the closest quality below it. A video with nothing that low will save at the closest "
                    + "quality above.";
            String name = "Saved videos will be named " + app.morphe.extension.shared.L10n.isolate("{date}_{video_id}") + ".";
            assertEquals("1 switch will change.\n\n" + quality + "\n\n" + name,
                    String.valueOf(shadowOf(preview).getMessage()));
            preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();
            assertEquals("Settings imported. 1 switch changed. " + quality + " " + name, ShadowToast.getTextOfLatestToast());
            assertEquals("{date}_{video_id}", Settings.FILENAME_TEMPLATE.savedValue());
            assertEquals("the name row still shows the old name",
                    HushfacebookPreferenceFragment.fileNameSummary("{date}_{video_id}"),
                    String.valueOf(page.findPreference(Settings.FILENAME_TEMPLATE.key).getSummary()));
        }
        assertEquals("Settings imported. " + "Saved videos will be named "
                        + app.morphe.extension.shared.L10n.isolate("Clip") + ".",
                SettingsBackupPreference.importedMessage(0, null, null, "Clip"));
    }

    /** A preview kept across a rebuild keeps its quality, and only one this build offers comes back. */
    @Test
    public void aWaitingImportKeepsItsQualityOnlyWhileItIsOne() throws Exception {
        Settings.DOWNLOAD_QUALITY.save(DownloadQuality.SMALLEST);
        Bundle state = SettingsBackup.parse(SettingsBackup.create()).toBundle();
        assertEquals(DownloadQuality.SMALLEST,
                SettingsBackup.Snapshot.fromBundle(state).quality);

        state.putString("quality", "SMALLEST");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).quality);
        state.putInt("quality", 360);
        assertNull(SettingsBackup.Snapshot.fromBundle(state).quality);
    }

    /**
     * The tab Facebook opens on goes out as its file value and comes back only as one this build
     * offers: any other value, or one that isn't text, refuses the whole file.
     */
    @Test
    public void theStartTabRoundTripsAndComesBackOnlyAsOneThisBuildOffers() throws Exception {
        for (StartTab tab : StartTab.choices()) {
            Settings.START_TAB.save(tab);
            String file = SettingsBackup.create();
            assertEquals(tab.fileValue, new JSONObject(file).getJSONObject("settings").get(SettingsBackup.START.key));
            Settings.START_TAB.save(tab == StartTab.HOME ? StartTab.MENU : StartTab.HOME);

            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
            assertEquals(tab, snapshot.start);
            assertEquals(tab, snapshot.startChange());
            assertEquals(0, snapshot.switchChanges());
            assertEquals(Collections.singletonMap(SettingsBackup.START, tab), snapshot.changes());
            assertEquals(1, SettingsBackup.apply(snapshot));
            assertEquals(tab, Settings.START_TAB.savedValue());
            assertEquals("a file read back is the file", file, SettingsBackup.create());
            assertEquals("the same tab again changes nothing", 0, SettingsBackup.parse(file).changes().size());
        }

        Settings.START_TAB.save(StartTab.FRIENDS);
        String file = SettingsBackup.create();
        Map<String, ?> before = store();
        for (Object refused : new Object[]{"FRIENDS", "Friends", "friend", " friends", "", 772219799489960L, true,
                JSONObject.NULL, new JSONObject(), new org.json.JSONArray()}) {
            JSONObject hostile = new JSONObject(file);
            hostile.getJSONObject("settings").put(SettingsBackup.START.key, refused);
            try {
                SettingsBackup.parse(hostile.toString());
                fail("a file with the start tab " + printable(String.valueOf(refused)) + " was read");
            } catch (SettingsBackup.Rejected rejected) {
                assertEquals(printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
            }
        }
        assertEquals("a refused file wrote something", before, store());

        // A file from before the tab was carried leaves it alone.
        SettingsBackup.Snapshot older = SettingsBackup.parse(fileWith(Settings.HIDE_SUGGESTED_POSTS, false));
        assertNull(older.start);
        assertNull(older.startChange());
        SettingsBackup.apply(older);
        assertEquals(StartTab.FRIENDS, Settings.START_TAB.savedValue());

        // A preview kept across a rebuild keeps its tab, and only one this build offers comes back.
        Bundle state = SettingsBackup.parse(file).toBundle();
        assertEquals(StartTab.FRIENDS, SettingsBackup.Snapshot.fromBundle(state).start);
        state.putString("start_tab", "FRIENDS");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).start);
        state.putInt("start_tab", 3);
        assertNull(SettingsBackup.Snapshot.fromBundle(state).start);
    }

    /**
     * The Feeds filter goes out as its file value and comes back only as one this build offers,
     * like the start tab, and the preview and the toast each say what it does (#56).
     */
    @Test
    public void theFeedsFilterRoundTripsAndComesBackOnlyAsOneThisBuildOffers() throws Exception {
        for (FeedsSubtab subtab : FeedsSubtab.values()) {
            Settings.FEEDS_SUBTAB.save(subtab);
            String file = SettingsBackup.create();
            assertEquals(subtab.fileValue, new JSONObject(file).getJSONObject("settings").get(SettingsBackup.SUBTAB.key));
            Settings.FEEDS_SUBTAB.save(subtab == FeedsSubtab.ALL ? FeedsSubtab.PAGES : FeedsSubtab.ALL);

            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
            assertEquals(subtab, snapshot.subtab);
            assertEquals(subtab, snapshot.subtabChange());
            assertEquals(Collections.singletonMap(SettingsBackup.SUBTAB, subtab), snapshot.changes());
            assertEquals(1, SettingsBackup.apply(snapshot));
            assertEquals(subtab, Settings.FEEDS_SUBTAB.savedValue());
            assertEquals("a file read back is the file", file, SettingsBackup.create());
            assertEquals("the same filter again changes nothing", 0, SettingsBackup.parse(file).changes().size());
        }

        Settings.FEEDS_SUBTAB.save(FeedsSubtab.GROUPS);
        String file = SettingsBackup.create();
        Map<String, ?> before = store();
        for (Object refused : new Object[]{"GROUPS", "Groups", "group", "most_recent_group", "", 3, true,
                JSONObject.NULL, new JSONObject(), new org.json.JSONArray()}) {
            JSONObject hostile = new JSONObject(file);
            hostile.getJSONObject("settings").put(SettingsBackup.SUBTAB.key, refused);
            try {
                SettingsBackup.parse(hostile.toString());
                fail("a file with the Feeds filter " + printable(String.valueOf(refused)) + " was read");
            } catch (SettingsBackup.Rejected rejected) {
                assertEquals(printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
            }
        }
        assertEquals("a refused file wrote something", before, store());

        // A file from before the filter was carried leaves it alone.
        SettingsBackup.Snapshot older = SettingsBackup.parse(fileWith(Settings.HIDE_SUGGESTED_POSTS, false));
        assertNull(older.subtab);
        assertNull(older.subtabChange());
        SettingsBackup.apply(older);
        assertEquals(FeedsSubtab.GROUPS, Settings.FEEDS_SUBTAB.savedValue());

        // A preview kept across a rebuild keeps its filter, and only one this build offers comes back.
        Bundle state = SettingsBackup.parse(file).toBundle();
        assertEquals(FeedsSubtab.GROUPS, SettingsBackup.Snapshot.fromBundle(state).subtab);
        state.putString("feeds_subtab", "GROUPS");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).subtab);

        // What the preview and the toast say it does.
        assertEquals(Collections.singletonList(L10n.f("The Feeds tab will open on %1$s.", L10n.t("Groups"))),
                SettingsBackupPreference.valueSentences(null, null, null, null, null, null, null, null, null, null,
                        null, FeedsSubtab.GROUPS));
        assertEquals("Settings imported. " + L10n.t("The Feeds tab will open on the filter Facebook picks."),
                SettingsBackupPreference.importedMessage(0, null, null, null, null, null, null, null, null, null,
                        null, null, FeedsSubtab.ALL));
        assertEquals("the folder alone keeps its own sentence",
                SettingsBackupPreference.importedMessage(0, "Hush", null, null, null),
                SettingsBackupPreference.importedMessage(0, "Hush", null, null, null, null, null, null, null, null,
                        null, null, null));
    }

    /**
     * The feed Home shows goes out as its file value and comes back only as one this build offers,
     * like the Feeds filter, and the preview says what it does. A file from before it was carried
     * leaves it alone.
     */
    @Test
    public void homesFeedRoundTripsAndComesBackOnlyAsOneThisBuildOffers() throws Exception {
        for (FeedsSubtab feed : FeedsSubtab.values()) {
            Settings.HOME_FEED.save(feed);
            String file = SettingsBackup.create();
            assertEquals(feed.fileValue, new JSONObject(file).getJSONObject("settings").get(SettingsBackup.HOME_FEED.key));
            Settings.HOME_FEED.save(feed == FeedsSubtab.ALL ? FeedsSubtab.FRIENDS : FeedsSubtab.ALL);

            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
            assertEquals(feed, snapshot.homeFeed);
            assertEquals(feed, snapshot.homeFeedChange());
            assertEquals(Collections.singletonMap(SettingsBackup.HOME_FEED, feed), snapshot.changes());
            assertEquals(1, SettingsBackup.apply(snapshot));
            assertEquals(feed, Settings.HOME_FEED.savedValue());
            assertEquals("a file read back is the file", file, SettingsBackup.create());
            assertEquals("the same feed again changes nothing", 0, SettingsBackup.parse(file).changes().size());
        }

        Settings.HOME_FEED.save(FeedsSubtab.FRIENDS);
        String file = SettingsBackup.create();
        Map<String, ?> before = store();
        for (Object refused : new Object[]{"FRIENDS", "Friends", "most_recent_friend", "top_stories", "", 3, true,
                JSONObject.NULL, new JSONObject(), new org.json.JSONArray()}) {
            JSONObject hostile = new JSONObject(file);
            hostile.getJSONObject("settings").put(SettingsBackup.HOME_FEED.key, refused);
            try {
                SettingsBackup.parse(hostile.toString());
                fail("a file with the Home feed " + printable(String.valueOf(refused)) + " was read");
            } catch (SettingsBackup.Rejected rejected) {
                assertEquals(printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
            }
        }
        assertEquals("a refused file wrote something", before, store());

        SettingsBackup.Snapshot older = SettingsBackup.parse(fileWith(Settings.HIDE_SUGGESTED_POSTS, false));
        assertNull(older.homeFeed);
        assertNull(older.homeFeedChange());
        SettingsBackup.apply(older);
        assertEquals(FeedsSubtab.FRIENDS, Settings.HOME_FEED.savedValue());

        // A preview kept across a rebuild keeps its feed, and only one this build offers comes back.
        Bundle state = SettingsBackup.parse(file).toBundle();
        assertEquals(FeedsSubtab.FRIENDS, SettingsBackup.Snapshot.fromBundle(state).homeFeed);
        state.putString("home_feed", "FRIENDS");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).homeFeed);

        assertEquals(L10n.f("With %1$s on, Home will show the same posts as %2$s in the Feeds tab.",
                        L10n.isolate(SwitchLabels.title(Settings.FOLLOWING_FEED_HOME)), L10n.t("Groups")),
                SettingsBackupPreference.homeFeedSentence(FeedsSubtab.GROUPS));
    }

    /**
     * What Sort loaded reels sorts by goes out as its file value and comes back only as one this
     * build offers, and the preview says what it does. A file from before it was carried leaves it
     * alone (#134).
     */
    @Test
    public void theReelsOrderRoundTripsAndComesBackOnlyAsOneThisBuildOffers() throws Exception {
        for (ReelsOrder order : ReelsOrder.values()) {
            Settings.REELS_ORDER.save(order);
            String file = SettingsBackup.create();
            assertEquals(order.fileValue, new JSONObject(file).getJSONObject("settings").get(SettingsBackup.REELS_ORDER.key));
            Settings.REELS_ORDER.save(order == ReelsOrder.NEWEST ? ReelsOrder.MOST_LIKED : ReelsOrder.NEWEST);

            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
            assertEquals(order, snapshot.reelsOrder);
            assertEquals(order, snapshot.reelsOrderChange());
            assertEquals(Collections.singletonMap(SettingsBackup.REELS_ORDER, order), snapshot.changes());
            assertEquals(1, SettingsBackup.apply(snapshot));
            assertEquals(order, Settings.REELS_ORDER.savedValue());
            assertEquals("a file read back is the file", file, SettingsBackup.create());
            assertEquals("the same order again changes nothing", 0, SettingsBackup.parse(file).changes().size());
        }

        Settings.REELS_ORDER.save(ReelsOrder.MOST_LIKED);
        String file = SettingsBackup.create();
        Map<String, ?> before = store();
        for (Object refused : new Object[]{"MOST_LIKED", "Most liked", "oldest", "", 1, true,
                JSONObject.NULL, new JSONObject(), new org.json.JSONArray()}) {
            JSONObject hostile = new JSONObject(file);
            hostile.getJSONObject("settings").put(SettingsBackup.REELS_ORDER.key, refused);
            try {
                SettingsBackup.parse(hostile.toString());
                fail("a file with the reels order " + printable(String.valueOf(refused)) + " was read");
            } catch (SettingsBackup.Rejected rejected) {
                assertEquals(printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
            }
        }
        assertEquals("a refused file wrote something", before, store());

        SettingsBackup.Snapshot older = SettingsBackup.parse(fileWith(Settings.HIDE_SUGGESTED_POSTS, false));
        assertNull(older.reelsOrder);
        assertNull(older.reelsOrderChange());
        SettingsBackup.apply(older);
        assertEquals(ReelsOrder.MOST_LIKED, Settings.REELS_ORDER.savedValue());

        // A preview kept across a rebuild keeps its order, and only one this build offers comes back.
        Bundle state = SettingsBackup.parse(file).toBundle();
        assertEquals(ReelsOrder.MOST_LIKED, SettingsBackup.Snapshot.fromBundle(state).reelsOrder);
        state.putString("reels_order", "MOST_LIKED");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).reelsOrder);

        assertEquals(L10n.f("Reels order will be set to %1$s.", L10n.t("Most liked")),
                SettingsBackupPreference.reelsOrderSentence(ReelsOrder.MOST_LIKED));
    }

    /**
     * The order comments open in goes out as its file value and comes back only as one this build
     * offers: any other value, or one that isn't text, refuses the whole file.
     */
    @Test
    public void theCommentOrderRoundTripsAndComesBackOnlyAsOneThisBuildOffers() throws Exception {
        for (CommentOrder order : CommentOrder.values()) {
            Settings.COMMENT_ORDER.save(order);
            String file = SettingsBackup.create();
            assertEquals(order.fileValue, new JSONObject(file).getJSONObject("settings").get(SettingsBackup.ORDER.key));
            Settings.COMMENT_ORDER.save(order == CommentOrder.NEWEST ? CommentOrder.FACEBOOK : CommentOrder.NEWEST);

            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
            assertEquals(order, snapshot.order);
            assertEquals(order, snapshot.orderChange());
            assertEquals(0, snapshot.switchChanges());
            assertEquals(Collections.singletonMap(SettingsBackup.ORDER, order), snapshot.changes());
            assertEquals(1, SettingsBackup.apply(snapshot));
            assertEquals(order, Settings.COMMENT_ORDER.savedValue());
            assertEquals("a file read back is the file", file, SettingsBackup.create());
            assertEquals("the same order again changes nothing", 0, SettingsBackup.parse(file).changes().size());
        }

        Settings.COMMENT_ORDER.save(CommentOrder.ALL_COMMENTS);
        String file = SettingsBackup.create();
        Map<String, ?> before = store();
        for (Object refused : new Object[]{"ALL_COMMENTS", "All comments", "all", " all_comments", "",
                "RANKED_UNFILTERED_CHRONOLOGICAL_REPLIES_INTENT_V1", 3, true, JSONObject.NULL, new JSONObject(),
                new org.json.JSONArray()}) {
            JSONObject hostile = new JSONObject(file);
            hostile.getJSONObject("settings").put(SettingsBackup.ORDER.key, refused);
            try {
                SettingsBackup.parse(hostile.toString());
                fail("a file with the comment order " + printable(String.valueOf(refused)) + " was read");
            } catch (SettingsBackup.Rejected rejected) {
                assertEquals(printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
            }
        }
        assertEquals("a refused file wrote something", before, store());

        // A file from before the order was carried leaves it alone.
        SettingsBackup.Snapshot older = SettingsBackup.parse(fileWith(Settings.HIDE_SUGGESTED_POSTS, false));
        assertNull(older.order);
        assertNull(older.orderChange());
        SettingsBackup.apply(older);
        assertEquals(CommentOrder.ALL_COMMENTS, Settings.COMMENT_ORDER.savedValue());

        // A preview kept across a rebuild keeps its order, and only one this build offers comes back.
        Bundle state = SettingsBackup.parse(file).toBundle();
        assertEquals(CommentOrder.ALL_COMMENTS, SettingsBackup.Snapshot.fromBundle(state).order);
        state.putString("comment_order", "ALL_COMMENTS");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).order);
        state.putInt("comment_order", 3);
        assertNull(SettingsBackup.Snapshot.fromBundle(state).order);
    }

    /** A file that changes the comment order says how comments will open, before and after. */
    @Test
    public void importOfACommentOrderSaysHowCommentsWillOpen() throws Exception {
        JSONObject file = new JSONObject(fileWith(Settings.DOWNLOAD_REELS, false));
        file.getJSONObject("settings").put(SettingsBackup.ORDER.key, "newest")
                .put(SettingsBackup.START.key, "notifications");
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            deliver(activity, tap(activity, page, IMPORT_ROW), file.toString());
            AlertDialog preview = shownPreview();
            String start = "Facebook will open on Notifications.";
            String order = "Comments will open with Newest picked in their sort menu.";
            assertEquals("1 switch will change.\n\n" + start + "\n\n" + order,
                    String.valueOf(shadowOf(preview).getMessage()));
            preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();
            assertEquals("Settings imported. 1 switch changed. " + start + " " + order,
                    ShadowToast.getTextOfLatestToast());
            assertEquals(CommentOrder.NEWEST, Settings.COMMENT_ORDER.savedValue());
            assertEquals("the comment order row still shows the old order",
                    HushfacebookPreferenceFragment.commentOrderSummary(CommentOrder.NEWEST),
                    String.valueOf(page.findPreference(Settings.COMMENT_ORDER.key).getSummary()));
        }
        assertEquals("Settings imported. Comments will open in the order Facebook picks.",
                SettingsBackupPreference.importedMessage(0, null, null, null, null, CommentOrder.FACEBOOK));
    }

    /**
     * The quality videos play at goes out as its file value and comes back only as one this build
     * offers: any other value, or one that isn't text, refuses the whole file.
     */
    @Test
    public void thePlaybackQualityRoundTripsAndComesBackOnlyAsOneThisBuildOffers() throws Exception {
        for (PlaybackQuality quality : PlaybackQuality.values()) {
            Settings.PLAYBACK_QUALITY.save(quality);
            String file = SettingsBackup.create();
            assertEquals(quality.fileValue, new JSONObject(file).getJSONObject("settings").get(SettingsBackup.PLAYBACK.key));
            Settings.PLAYBACK_QUALITY.save(quality == PlaybackQuality.P720 ? PlaybackQuality.AUTO : PlaybackQuality.P720);

            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
            assertEquals(quality, snapshot.playback);
            assertEquals(quality, snapshot.playbackChange());
            assertEquals(0, snapshot.switchChanges());
            assertEquals(Collections.singletonMap(SettingsBackup.PLAYBACK, quality), snapshot.changes());
            assertEquals(1, SettingsBackup.apply(snapshot));
            assertEquals(quality, Settings.PLAYBACK_QUALITY.savedValue());
            assertEquals("a file read back is the file", file, SettingsBackup.create());
            assertEquals("the same quality again changes nothing", 0, SettingsBackup.parse(file).changes().size());
        }

        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.DATA_SAVER);
        String file = SettingsBackup.create();
        Map<String, ?> before = store();
        for (Object refused : new Object[]{"DATA_SAVER", "Data saver", "480", " 720p", "", "1080p", 720, true,
                JSONObject.NULL, new JSONObject(), new org.json.JSONArray()}) {
            JSONObject hostile = new JSONObject(file);
            hostile.getJSONObject("settings").put(SettingsBackup.PLAYBACK.key, refused);
            try {
                SettingsBackup.parse(hostile.toString());
                fail("a file with the playback quality " + printable(String.valueOf(refused)) + " was read");
            } catch (SettingsBackup.Rejected rejected) {
                assertEquals(printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
            }
        }
        assertEquals("a refused file wrote something", before, store());

        // A file from before the quality was carried leaves it alone.
        SettingsBackup.Snapshot older = SettingsBackup.parse(fileWith(Settings.HIDE_SUGGESTED_POSTS, false));
        assertNull(older.playback);
        assertNull(older.playbackChange());
        SettingsBackup.apply(older);
        assertEquals(PlaybackQuality.DATA_SAVER, Settings.PLAYBACK_QUALITY.savedValue());

        // A preview kept across a rebuild keeps its quality, and only one this build offers comes back.
        Bundle state = SettingsBackup.parse(file).toBundle();
        assertEquals(PlaybackQuality.DATA_SAVER, SettingsBackup.Snapshot.fromBundle(state).playback);
        state.putString("playback_quality", "DATA_SAVER");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).playback);
        state.putInt("playback_quality", 3);
        assertNull(SettingsBackup.Snapshot.fromBundle(state).playback);
    }

    /** A file that changes the playback quality says what videos will play at, before and after. */
    @Test
    public void importOfAPlaybackQualitySaysWhatVideosWillPlayAt() throws Exception {
        JSONObject file = new JSONObject(fileWith(Settings.DOWNLOAD_REELS, false));
        file.getJSONObject("settings").put(SettingsBackup.PLAYBACK.key, "720p")
                .put(SettingsBackup.ORDER.key, "newest");
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            deliver(activity, tap(activity, page, IMPORT_ROW), file.toString());
            AlertDialog preview = shownPreview();
            String order = "Comments will open with Newest picked in their sort menu.";
            String quality = "Default quality will be set to Up to " + L10n.isolate("720p") + ".";
            assertEquals("1 switch will change.\n\n" + order + "\n\n" + quality,
                    String.valueOf(shadowOf(preview).getMessage()));
            preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();
            assertEquals("Settings imported. 1 switch changed. " + order + " " + quality,
                    ShadowToast.getTextOfLatestToast());
            assertEquals(PlaybackQuality.P720, Settings.PLAYBACK_QUALITY.savedValue());
            assertEquals("the playback quality row still shows the old quality",
                    HushfacebookPreferenceFragment.playbackQualitySummary(PlaybackQuality.P720),
                    String.valueOf(page.findPreference(Settings.PLAYBACK_QUALITY.key).getSummary()));
        }
        assertEquals("Settings imported. Facebook will pick the quality videos play at.",
                SettingsBackupPreference.importedMessage(0, null, null, null, null, null, null, null, PlaybackQuality.AUTO));
    }

    /**
     * The qualities reels and video stories play at go out as their file values and come back only
     * as ones this build offers, each on its own: any other value, or one that isn't text, refuses
     * the whole file.
     */
    @Test
    public void theReelsAndStoriesQualitiesRoundTripAndComeBackOnlyAsOnesThisBuildOffers() throws Exception {
        for (SurfaceQuality choice : SurfaceQuality.values()) {
            SurfaceQuality other = choice == SurfaceQuality.P720 ? SurfaceQuality.SAME : SurfaceQuality.P720;
            Settings.REELS_PLAYBACK_QUALITY.save(choice);
            Settings.STORIES_PLAYBACK_QUALITY.save(other);
            String file = SettingsBackup.create();
            JSONObject written = new JSONObject(file).getJSONObject("settings");
            assertEquals(choice.fileValue, written.get(SettingsBackup.REELS_QUALITY.key));
            assertEquals(other.fileValue, written.get(SettingsBackup.STORIES_QUALITY.key));
            Settings.REELS_PLAYBACK_QUALITY.save(other);
            Settings.STORIES_PLAYBACK_QUALITY.save(choice);

            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
            assertEquals(choice, snapshot.reelsQuality);
            assertEquals(other, snapshot.storiesQuality);
            assertEquals(choice, snapshot.reelsQualityChange());
            assertEquals(other, snapshot.storiesQualityChange());
            assertEquals(0, snapshot.switchChanges());
            Map<Setting<?>, Object> expected = new LinkedHashMap<>();
            expected.put(SettingsBackup.REELS_QUALITY, choice);
            expected.put(SettingsBackup.STORIES_QUALITY, other);
            assertEquals(expected, snapshot.changes());
            assertEquals(2, SettingsBackup.apply(snapshot));
            assertEquals(choice, Settings.REELS_PLAYBACK_QUALITY.savedValue());
            assertEquals(other, Settings.STORIES_PLAYBACK_QUALITY.savedValue());
            assertEquals("a file read back is the file", file, SettingsBackup.create());
            assertEquals("the same qualities again change nothing", 0, SettingsBackup.parse(file).changes().size());
        }

        Settings.REELS_PLAYBACK_QUALITY.save(SurfaceQuality.DATA_SAVER);
        Settings.STORIES_PLAYBACK_QUALITY.save(SurfaceQuality.HIGHEST);
        String file = SettingsBackup.create();
        Map<String, ?> before = store();
        for (String key : new String[]{SettingsBackup.REELS_QUALITY.key, SettingsBackup.STORIES_QUALITY.key}) {
            for (Object refused : new Object[]{"SAME", "Same as Playback quality", "DATA_SAVER", "480", " 720p", "", "1080p", 720,
                    true, JSONObject.NULL, new JSONObject(), new org.json.JSONArray()}) {
                JSONObject hostile = new JSONObject(file);
                hostile.getJSONObject("settings").put(key, refused);
                try {
                    SettingsBackup.parse(hostile.toString());
                    fail("a file with " + key + " " + printable(String.valueOf(refused)) + " was read");
                } catch (SettingsBackup.Rejected rejected) {
                    assertEquals(key + " " + printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
                }
            }
        }
        assertEquals("a refused file wrote something", before, store());

        // A file from before the qualities were carried leaves them alone.
        SettingsBackup.Snapshot older = SettingsBackup.parse(fileWith(Settings.HIDE_SUGGESTED_POSTS, false));
        assertNull(older.reelsQuality);
        assertNull(older.storiesQuality);
        assertNull(older.reelsQualityChange());
        assertNull(older.storiesQualityChange());
        SettingsBackup.apply(older);
        assertEquals(SurfaceQuality.DATA_SAVER, Settings.REELS_PLAYBACK_QUALITY.savedValue());
        assertEquals(SurfaceQuality.HIGHEST, Settings.STORIES_PLAYBACK_QUALITY.savedValue());

        // A preview kept across a rebuild keeps both, and only ones this build offers come back.
        Bundle state = SettingsBackup.parse(file).toBundle();
        assertEquals(SurfaceQuality.DATA_SAVER, SettingsBackup.Snapshot.fromBundle(state).reelsQuality);
        assertEquals(SurfaceQuality.HIGHEST, SettingsBackup.Snapshot.fromBundle(state).storiesQuality);
        state.putString("reels_quality", "DATA_SAVER");
        state.putInt("stories_quality", 3);
        assertNull(SettingsBackup.Snapshot.fromBundle(state).reelsQuality);
        assertNull(SettingsBackup.Snapshot.fromBundle(state).storiesQuality);
    }

    /**
     * The hours notification quiet hours start and end go out as 24-hour times on the hour and come
     * back only as one of those, each on its own: any other value, or one that isn't text, refuses
     * the whole file. The preview and the toast say each one that changes.
     */
    @Test
    public void theQuietHoursRoundTripAndComeBackOnlyAsWholeHours() throws Exception {
        Settings.QUIET_HOURS_FROM.save(QuietHour.H23);
        Settings.QUIET_HOURS_UNTIL.save(QuietHour.H6);
        String file = SettingsBackup.create();
        JSONObject written = new JSONObject(file).getJSONObject("settings");
        assertEquals("23:00", written.get(SettingsBackup.QUIET_FROM.key));
        assertEquals("06:00", written.get(SettingsBackup.QUIET_UNTIL.key));
        Settings.QUIET_HOURS_FROM.resetToDefault();
        Settings.QUIET_HOURS_UNTIL.resetToDefault();

        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
        assertEquals(QuietHour.H23, snapshot.quietFromChange());
        assertEquals(QuietHour.H6, snapshot.quietUntilChange());
        assertEquals(0, snapshot.switchChanges());
        Map<Setting<?>, Object> expected = new LinkedHashMap<>();
        expected.put(SettingsBackup.QUIET_FROM, QuietHour.H23);
        expected.put(SettingsBackup.QUIET_UNTIL, QuietHour.H6);
        assertEquals(expected, snapshot.changes());
        String from = HushfacebookPreferenceFragment.quietHourLabel(QuietHour.H23);
        String until = HushfacebookPreferenceFragment.quietHourLabel(QuietHour.H6);
        assertEquals(Arrays.asList("Quiet hours will start at " + from + ".", "Quiet hours will end at " + until + "."),
                SettingsBackupPreference.valueSentences(null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, snapshot.quietFromChange(), snapshot.quietUntilChange(), null));
        assertEquals(2, SettingsBackup.apply(snapshot));
        assertEquals(QuietHour.H23, Settings.QUIET_HOURS_FROM.savedValue());
        assertEquals(QuietHour.H6, Settings.QUIET_HOURS_UNTIL.savedValue());
        assertEquals("a file read back is the file", file, SettingsBackup.create());
        assertEquals("the same hours again change nothing", 0, SettingsBackup.parse(file).changes().size());

        Map<String, ?> before = store();
        for (String key : new String[]{SettingsBackup.QUIET_FROM.key, SettingsBackup.QUIET_UNTIL.key}) {
            for (Object refused : new Object[]{"H23", "23", "11 PM", "7:00", "24:00", "23:30", "", 23, true,
                    JSONObject.NULL, new JSONObject(), new org.json.JSONArray()}) {
                JSONObject hostile = new JSONObject(file);
                hostile.getJSONObject("settings").put(key, refused);
                try {
                    SettingsBackup.parse(hostile.toString());
                    fail("a file with " + key + " " + printable(String.valueOf(refused)) + " was read");
                } catch (SettingsBackup.Rejected rejected) {
                    assertEquals(key + " " + printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
                }
            }
        }
        assertEquals("a refused file wrote something", before, store());

        // A file from before the hours were carried leaves them alone.
        SettingsBackup.Snapshot older = SettingsBackup.parse(fileWith(Settings.HIDE_SUGGESTED_POSTS, false));
        assertNull(older.quietFrom);
        assertNull(older.quietUntil);
        SettingsBackup.apply(older);
        assertEquals(QuietHour.H23, Settings.QUIET_HOURS_FROM.savedValue());

        // A preview kept across a rebuild keeps both, and only whole hours come back.
        Settings.QUIET_HOURS_FROM.resetToDefault();
        Bundle state = SettingsBackup.parse(file).toBundle();
        assertEquals(QuietHour.H23, SettingsBackup.Snapshot.fromBundle(state).quietFrom);
        assertEquals(QuietHour.H6, SettingsBackup.Snapshot.fromBundle(state).quietUntil);
        state.putString("quiet_hours_from", "H23");
        state.putInt("quiet_hours_until", 6);
        assertNull(SettingsBackup.Snapshot.fromBundle(state).quietFrom);
        assertNull(SettingsBackup.Snapshot.fromBundle(state).quietUntil);
    }

    /**
     * How long the app lock waits goes out as its file value and comes back only as one this build
     * offers: any other value, or one that isn't text, refuses the whole file. The switch rides with
     * the other switches.
     */
    @Test
    public void theAppLockTimeRoundTripsAndComesBackOnlyAsOneThisBuildOffers() throws Exception {
        assertTrue(SettingsBackup.ALLOWLIST.contains(Settings.APP_LOCK));
        for (AppLock.After choice : AppLock.After.values()) {
            AppLock.After other = choice == AppLock.After.ONE_HOUR ? AppLock.After.IMMEDIATELY : AppLock.After.ONE_HOUR;
            Settings.APP_LOCK_AFTER.save(choice);
            String file = SettingsBackup.create();
            assertEquals(choice.fileValue, new JSONObject(file).getJSONObject("settings").get(SettingsBackup.LOCK_AFTER.key));
            Settings.APP_LOCK_AFTER.save(other);

            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
            assertEquals(choice, snapshot.lockAfter);
            assertEquals(choice, snapshot.lockAfterChange());
            Map<Setting<?>, Object> expected = new LinkedHashMap<>();
            expected.put(SettingsBackup.LOCK_AFTER, choice);
            assertEquals(expected, snapshot.changes());
            assertEquals(1, SettingsBackup.apply(snapshot));
            assertEquals(choice, Settings.APP_LOCK_AFTER.savedValue());
            assertEquals("a file read back is the file", file, SettingsBackup.create());
            assertEquals("the same time again changes nothing", 0, SettingsBackup.parse(file).changes().size());
        }

        Settings.APP_LOCK_AFTER.save(AppLock.After.FIVE_MINUTES);
        String file = SettingsBackup.create();
        Map<String, ?> before = store();
        for (Object refused : new Object[]{"FIVE_MINUTES", "5 minutes", "2_minutes", "", 300_000, true, JSONObject.NULL,
                new JSONObject(), new org.json.JSONArray()}) {
            JSONObject hostile = new JSONObject(file);
            hostile.getJSONObject("settings").put(SettingsBackup.LOCK_AFTER.key, refused);
            try {
                SettingsBackup.parse(hostile.toString());
                fail("a file with the lock time " + printable(String.valueOf(refused)) + " was read");
            } catch (SettingsBackup.Rejected rejected) {
                assertEquals(printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
            }
        }
        assertEquals("a refused file wrote something", before, store());

        // A file from before the time was carried leaves it alone.
        SettingsBackup.Snapshot older = SettingsBackup.parse(fileWith(Settings.HIDE_SUGGESTED_POSTS, false));
        assertNull(older.lockAfter);
        assertNull(older.lockAfterChange());
        SettingsBackup.apply(older);
        assertEquals(AppLock.After.FIVE_MINUTES, Settings.APP_LOCK_AFTER.savedValue());

        // A preview kept across a rebuild keeps it, and only one this build offers comes back.
        Bundle state = SettingsBackup.parse(file).toBundle();
        assertEquals(AppLock.After.FIVE_MINUTES, SettingsBackup.Snapshot.fromBundle(state).lockAfter);
        state.putString("app_lock_after", "FIVE_MINUTES");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).lockAfter);

        assertEquals("Settings imported. With Lock Facebook on, it will lock once you've been away for 1 hour.",
                SettingsBackupPreference.importedMessage(0, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, AppLock.After.ONE_HOUR));
    }

    /**
     * The opening pause's length goes out as its file value and comes back only as one this build
     * offers: any other value refuses the whole file. The switch rides with the other switches.
     */
    @Test
    public void theOpeningPauseLengthRoundTripsAndComesBackOnlyAsOneThisBuildOffers() throws Exception {
        assertTrue(SettingsBackup.ALLOWLIST.contains(Settings.OPENING_PAUSE));
        for (OpeningPause.Length choice : OpeningPause.Length.values()) {
            OpeningPause.Length other = choice == OpeningPause.Length.TEN_SECONDS
                    ? OpeningPause.Length.FIVE_SECONDS : OpeningPause.Length.TEN_SECONDS;
            Settings.OPENING_PAUSE_LENGTH.save(choice);
            String file = SettingsBackup.create();
            assertEquals(choice.fileValue, new JSONObject(file).getJSONObject("settings").get(SettingsBackup.PAUSE_LENGTH.key));
            Settings.OPENING_PAUSE_LENGTH.save(other);

            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
            assertEquals(choice, snapshot.pauseLength);
            Map<Setting<?>, Object> expected = new LinkedHashMap<>();
            expected.put(SettingsBackup.PAUSE_LENGTH, choice);
            assertEquals(expected, snapshot.changes());
            assertEquals(1, SettingsBackup.apply(snapshot));
            assertEquals(choice, Settings.OPENING_PAUSE_LENGTH.savedValue());
            assertEquals("a file read back is the file", file, SettingsBackup.create());
        }

        Settings.OPENING_PAUSE_LENGTH.save(OpeningPause.Length.TEN_SECONDS);
        String file = SettingsBackup.create();
        Map<String, ?> before = store();
        for (Object refused : new Object[]{"TEN_SECONDS", "10 seconds", "15_seconds", "", 10, true, JSONObject.NULL}) {
            JSONObject hostile = new JSONObject(file);
            hostile.getJSONObject("settings").put(SettingsBackup.PAUSE_LENGTH.key, refused);
            try {
                SettingsBackup.parse(hostile.toString());
                fail("a file with the pause length " + printable(String.valueOf(refused)) + " was read");
            } catch (SettingsBackup.Rejected rejected) {
                assertEquals(printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
            }
        }
        assertEquals("a refused file wrote something", before, store());

        // A file from before the length was carried leaves it alone.
        SettingsBackup.Snapshot older = SettingsBackup.parse(fileWith(Settings.HIDE_SUGGESTED_POSTS, false));
        assertNull(older.pauseLength);
        SettingsBackup.apply(older);
        assertEquals(OpeningPause.Length.TEN_SECONDS, Settings.OPENING_PAUSE_LENGTH.savedValue());

        // A preview kept across a rebuild keeps it, and only one this build offers comes back.
        Bundle state = SettingsBackup.parse(file).toBundle();
        assertEquals(OpeningPause.Length.TEN_SECONDS, SettingsBackup.Snapshot.fromBundle(state).pauseLength);
        state.putString("opening_pause_length", "TEN_SECONDS");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).pauseLength);

        assertEquals("With Opening pause on, Facebook will wait 10 seconds before it opens.",
                SettingsBackupPreference.pauseLengthSentence(OpeningPause.Length.TEN_SECONDS));
    }

    /**
     * The time before a break and the break's length go out as their file values and come back
     * only as ones this build offers: any other value refuses the whole file. The switch rides with
     * the other switches.
     */
    @Test
    public void theBreakTimesRoundTripAndComeBackOnlyAsOnesThisBuildOffers() throws Exception {
        assertTrue(SettingsBackup.ALLOWLIST.contains(Settings.TAKE_BREAKS));
        for (BreakTimer.Use choice : BreakTimer.Use.values()) {
            BreakTimer.Use other = choice == BreakTimer.Use.TEN_MINUTES
                    ? BreakTimer.Use.SIXTY_MINUTES : BreakTimer.Use.TEN_MINUTES;
            Settings.BREAK_AFTER.save(choice);
            String file = SettingsBackup.create();
            assertEquals(choice.fileValue, new JSONObject(file).getJSONObject("settings").get(SettingsBackup.BREAK_AFTER.key));
            Settings.BREAK_AFTER.save(other);

            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
            assertEquals(choice, snapshot.breakAfter);
            Map<Setting<?>, Object> expected = new LinkedHashMap<>();
            expected.put(SettingsBackup.BREAK_AFTER, choice);
            assertEquals(expected, snapshot.changes());
            assertEquals(1, SettingsBackup.apply(snapshot));
            assertEquals(choice, Settings.BREAK_AFTER.savedValue());
            assertEquals("a file read back is the file", file, SettingsBackup.create());
        }
        for (BreakTimer.Length choice : BreakTimer.Length.values()) {
            BreakTimer.Length other = choice == BreakTimer.Length.FIVE_MINUTES
                    ? BreakTimer.Length.ONE_MINUTE : BreakTimer.Length.FIVE_MINUTES;
            Settings.BREAK_LENGTH.save(choice);
            String file = SettingsBackup.create();
            assertEquals(choice.fileValue, new JSONObject(file).getJSONObject("settings").get(SettingsBackup.BREAK_LENGTH.key));
            Settings.BREAK_LENGTH.save(other);

            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
            assertEquals(choice, snapshot.breakLength);
            Map<Setting<?>, Object> expected = new LinkedHashMap<>();
            expected.put(SettingsBackup.BREAK_LENGTH, choice);
            assertEquals(expected, snapshot.changes());
            assertEquals(1, SettingsBackup.apply(snapshot));
            assertEquals(choice, Settings.BREAK_LENGTH.savedValue());
        }

        Settings.BREAK_AFTER.save(BreakTimer.Use.THIRTY_MINUTES);
        Settings.BREAK_LENGTH.save(BreakTimer.Length.TEN_MINUTES);
        String file = SettingsBackup.create();
        Map<String, ?> before = store();
        for (String key : new String[]{SettingsBackup.BREAK_AFTER.key, SettingsBackup.BREAK_LENGTH.key}) {
            for (Object refused : new Object[]{"TEN_MINUTES", "10 minutes", "20_minutes", "", 10, true, JSONObject.NULL}) {
                JSONObject hostile = new JSONObject(file);
                hostile.getJSONObject("settings").put(key, refused);
                try {
                    SettingsBackup.parse(hostile.toString());
                    fail("a file with " + key + " " + printable(String.valueOf(refused)) + " was read");
                } catch (SettingsBackup.Rejected rejected) {
                    assertEquals(key + " " + printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
                }
            }
        }
        assertEquals("a refused file wrote something", before, store());

        // A file from before the break times were carried leaves them alone.
        SettingsBackup.Snapshot older = SettingsBackup.parse(fileWith(Settings.HIDE_SUGGESTED_POSTS, false));
        assertNull(older.breakAfter);
        assertNull(older.breakLength);
        SettingsBackup.apply(older);
        assertEquals(BreakTimer.Use.THIRTY_MINUTES, Settings.BREAK_AFTER.savedValue());
        assertEquals(BreakTimer.Length.TEN_MINUTES, Settings.BREAK_LENGTH.savedValue());

        // A preview kept across a rebuild keeps them, and only ones this build offers come back.
        Bundle state = SettingsBackup.parse(file).toBundle();
        assertEquals(BreakTimer.Use.THIRTY_MINUTES, SettingsBackup.Snapshot.fromBundle(state).breakAfter);
        assertEquals(BreakTimer.Length.TEN_MINUTES, SettingsBackup.Snapshot.fromBundle(state).breakLength);
        state.putString("break_after", "THIRTY_MINUTES");
        state.putString("break_length", "TEN_MINUTES");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).breakAfter);
        assertNull(SettingsBackup.Snapshot.fromBundle(state).breakLength);

        assertEquals("With Take breaks on, a break will start after 30 minutes of browsing.",
                SettingsBackupPreference.breakAfterSentence(BreakTimer.Use.THIRTY_MINUTES));
        assertEquals("With Take breaks on, a break will last 10 minutes.",
                SettingsBackupPreference.breakLengthSentence(BreakTimer.Length.TEN_MINUTES));
    }

    /**
     * How long Recently watched keeps entries goes out as its file value and comes back only as one
     * this build offers. The entries themselves never go in the file.
     */
    @Test
    public void theWatchedEntriesTimeRoundTripsAndTheEntriesStayHome() throws Exception {
        app.morphe.extension.facebook.media.RecentlyWatchedForTests.install();
        try {
            app.morphe.extension.facebook.media.RecentlyWatchedForTests.watched(
                    RecentlyWatched.REEL, "4455667788", null, "A private caption", null, System.currentTimeMillis(), 0);
            for (RecentlyWatched.Keep choice : RecentlyWatched.Keep.values()) {
                RecentlyWatched.Keep other = choice == RecentlyWatched.Keep.SEVEN_DAYS
                        ? RecentlyWatched.Keep.THIRTY_DAYS : RecentlyWatched.Keep.SEVEN_DAYS;
                Settings.RECENTLY_WATCHED_KEEP.save(choice);
                String file = SettingsBackup.create();
                assertEquals(choice.fileValue, new JSONObject(file).getJSONObject("settings").get(SettingsBackup.WATCHED_KEEP.key));
                assertFalse("an entry went in the file", file.contains("4455667788") || file.contains("A private caption"));
                Settings.RECENTLY_WATCHED_KEEP.save(other);

                SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
                assertEquals(choice, snapshot.watchedKeep);
                Map<Setting<?>, Object> expected = new LinkedHashMap<>();
                expected.put(SettingsBackup.WATCHED_KEEP, choice);
                assertEquals(expected, snapshot.changes());
                assertEquals(1, SettingsBackup.apply(snapshot));
                assertEquals(choice, Settings.RECENTLY_WATCHED_KEEP.savedValue());
                assertEquals("a file read back is the file", file, SettingsBackup.create());
            }
        } finally {
            app.morphe.extension.facebook.media.RecentlyWatchedForTests.forget();
        }

        Settings.RECENTLY_WATCHED_KEEP.save(RecentlyWatched.Keep.SEVEN_DAYS);
        String file = SettingsBackup.create();
        Map<String, ?> before = store();
        for (Object refused : new Object[]{"SEVEN_DAYS", "7 days", "14_days", "", 7, true, JSONObject.NULL}) {
            JSONObject hostile = new JSONObject(file);
            hostile.getJSONObject("settings").put(SettingsBackup.WATCHED_KEEP.key, refused);
            try {
                SettingsBackup.parse(hostile.toString());
                fail("a file with the watched entries time " + printable(String.valueOf(refused)) + " was read");
            } catch (SettingsBackup.Rejected rejected) {
                assertEquals(printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
            }
        }
        assertEquals("a refused file wrote something", before, store());

        // A file from before the time was carried leaves it alone.
        SettingsBackup.Snapshot older = SettingsBackup.parse(fileWith(Settings.HIDE_SUGGESTED_POSTS, false));
        assertNull(older.watchedKeep);
        SettingsBackup.apply(older);
        assertEquals(RecentlyWatched.Keep.SEVEN_DAYS, Settings.RECENTLY_WATCHED_KEEP.savedValue());

        // A preview kept across a rebuild keeps it, and only one this build offers comes back.
        Bundle state = SettingsBackup.parse(file).toBundle();
        assertEquals(RecentlyWatched.Keep.SEVEN_DAYS, SettingsBackup.Snapshot.fromBundle(state).watchedKeep);
        state.putString("recently_watched_keep", "SEVEN_DAYS");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).watchedKeep);

        assertEquals("Recently watched will keep entries for 7 days.",
                SettingsBackupPreference.watchedKeepSentence(RecentlyWatched.Keep.SEVEN_DAYS));
        assertEquals("Recently watched will keep entries until you clear them.",
                SettingsBackupPreference.watchedKeepSentence(RecentlyWatched.Keep.UNTIL_CLEARED));
    }

    /**
     * The text size goes out as its percentage and comes back only as one this build offers: any
     * other value, or one that isn't text, refuses the whole file.
     */
    @Test
    public void theTextSizeRoundTripsAndComesBackOnlyAsOneThisBuildOffers() throws Exception {
        for (TextSize.Scale choice : TextSize.Scale.values()) {
            TextSize.Scale other = choice == TextSize.Scale.P130 ? TextSize.Scale.P85 : TextSize.Scale.P130;
            Settings.TEXT_SIZE.save(choice);
            String file = SettingsBackup.create();
            assertEquals(choice.fileValue, new JSONObject(file).getJSONObject("settings").get(SettingsBackup.TEXT_SIZE.key));
            Settings.TEXT_SIZE.save(other);

            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
            assertEquals(choice, snapshot.textSize);
            assertEquals(choice, snapshot.textSizeChange());
            Map<Setting<?>, Object> expected = new LinkedHashMap<>();
            expected.put(SettingsBackup.TEXT_SIZE, choice);
            assertEquals(expected, snapshot.changes());
            assertEquals(1, SettingsBackup.apply(snapshot));
            assertEquals(choice, Settings.TEXT_SIZE.savedValue());
            assertEquals("a file read back is the file", file, SettingsBackup.create());
            assertEquals("the same size again changes nothing", 0, SettingsBackup.parse(file).changes().size());
        }

        Settings.TEXT_SIZE.save(TextSize.Scale.P115);
        String file = SettingsBackup.create();
        Map<String, ?> before = store();
        for (Object refused : new Object[]{"P115", "115%", "95", "", 115, true, JSONObject.NULL,
                new JSONObject(), new org.json.JSONArray()}) {
            JSONObject hostile = new JSONObject(file);
            hostile.getJSONObject("settings").put(SettingsBackup.TEXT_SIZE.key, refused);
            try {
                SettingsBackup.parse(hostile.toString());
                fail("a file with the text size " + printable(String.valueOf(refused)) + " was read");
            } catch (SettingsBackup.Rejected rejected) {
                assertEquals(printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
            }
        }
        assertEquals("a refused file wrote something", before, store());

        // A file from before the size was carried leaves it alone.
        SettingsBackup.Snapshot older = SettingsBackup.parse(fileWith(Settings.HIDE_SUGGESTED_POSTS, false));
        assertNull(older.textSize);
        assertNull(older.textSizeChange());
        SettingsBackup.apply(older);
        assertEquals(TextSize.Scale.P115, Settings.TEXT_SIZE.savedValue());

        // A preview kept across a rebuild keeps it, and only one this build offers comes back.
        Bundle state = SettingsBackup.parse(file).toBundle();
        assertEquals(TextSize.Scale.P115, SettingsBackup.Snapshot.fromBundle(state).textSize);
        state.putString("text_size", "P115");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).textSize);

        assertEquals("Settings imported. Facebook's text will be 130% of the size your phone's font size setting gives it.",
                SettingsBackupPreference.importedMessage(0, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null, TextSize.Scale.P130));
    }

    /**
     * The accent color goes out as its file value and comes back only as one this build offers:
     * anything else, or a value that isn't text, refuses the whole file.
     */
    @Test
    public void theAccentColorRoundTripsAndComesBackOnlyAsOneThisBuildOffers() throws Exception {
        for (AccentColor.Preset choice : AccentColor.Preset.values()) {
            AccentColor.Preset other = choice == AccentColor.Preset.TEAL ? AccentColor.Preset.RED : AccentColor.Preset.TEAL;
            Settings.ACCENT_COLOR.save(choice);
            String file = SettingsBackup.create();
            assertEquals(choice.fileValue, new JSONObject(file).getJSONObject("settings").get(SettingsBackup.ACCENT.key));
            Settings.ACCENT_COLOR.save(other);

            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
            assertEquals(choice, snapshot.accent);
            assertEquals(choice, snapshot.accentChange());
            assertEquals(1, SettingsBackup.apply(snapshot));
            assertEquals(choice, Settings.ACCENT_COLOR.savedValue());
            assertEquals("a file read back is the file", file, SettingsBackup.create());
            assertEquals("the same accent again changes nothing", 0, SettingsBackup.parse(file).changes().size());
        }

        Settings.ACCENT_COLOR.save(AccentColor.Preset.PURPLE);
        String file = SettingsBackup.create();
        Map<String, ?> before = store();
        for (Object refused : new Object[]{"PURPLE", "Purple", "mauve", "", 7, true, JSONObject.NULL,
                new JSONObject(), new org.json.JSONArray()}) {
            JSONObject hostile = new JSONObject(file);
            hostile.getJSONObject("settings").put(SettingsBackup.ACCENT.key, refused);
            try {
                SettingsBackup.parse(hostile.toString());
                fail("a file with the accent " + printable(String.valueOf(refused)) + " was read");
            } catch (SettingsBackup.Rejected rejected) {
                assertEquals(printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
            }
        }
        assertEquals("a refused file wrote something", before, store());

        // A file from before the accent was carried leaves it alone.
        SettingsBackup.Snapshot older = SettingsBackup.parse(fileWith(Settings.HIDE_SUGGESTED_POSTS, false));
        assertNull(older.accent);
        assertNull(older.accentChange());
        SettingsBackup.apply(older);
        assertEquals(AccentColor.Preset.PURPLE, Settings.ACCENT_COLOR.savedValue());

        Bundle state = SettingsBackup.parse(file).toBundle();
        assertEquals(AccentColor.Preset.PURPLE, SettingsBackup.Snapshot.fromBundle(state).accent);
        state.putString("accent_color", "PURPLE");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).accent);

        assertEquals("Settings imported. Facebook will keep its own blue.",
                SettingsBackupPreference.importedMessage(0, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null, null,
                        AccentColor.Preset.FACEBOOK));
    }

    /**
     * The video and photo subfolders go out as the names the saves use, blank for none, and come
     * back only as a name the subfolder row would keep as typed, or blank. A path, a name the row
     * would change, or a value that isn't text refuses the whole file, so a file can't point the
     * saves anywhere the row couldn't.
     */
    @Test
    public void theSubfoldersRoundTripAndComeBackOnlyAsCleanNamesOrBlank() throws Exception {
        // Stored as typed, and written as the folder the saves really use.
        Settings.VIDEO_SUBFOLDER.save("/Clips/");
        Settings.PHOTO_SUBFOLDER.save("Pics");
        String file = SettingsBackup.create();
        JSONObject written = new JSONObject(file).getJSONObject("settings");
        assertEquals("Clips", written.get(SettingsBackup.VIDEO_SUBFOLDER.key));
        assertEquals("Pics", written.get(SettingsBackup.PHOTO_SUBFOLDER.key));
        Settings.VIDEO_SUBFOLDER.resetToDefault();
        Settings.PHOTO_SUBFOLDER.save("Old");

        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
        assertEquals("Clips", snapshot.videoSubfolder);
        assertEquals("Pics", snapshot.photoSubfolder);
        assertEquals("Clips", snapshot.videoSubfolderChange());
        assertEquals("Pics", snapshot.photoSubfolderChange());
        assertEquals(0, snapshot.switchChanges());
        Map<Setting<?>, Object> expected = new LinkedHashMap<>();
        expected.put(SettingsBackup.VIDEO_SUBFOLDER, "Clips");
        expected.put(SettingsBackup.PHOTO_SUBFOLDER, "Pics");
        assertEquals(expected, snapshot.changes());
        assertEquals(2, SettingsBackup.apply(snapshot));
        assertEquals("Clips", Settings.VIDEO_SUBFOLDER.savedValue());
        assertEquals("Pics", Settings.PHOTO_SUBFOLDER.savedValue());
        assertEquals("a file read back is the file", file, SettingsBackup.create());
        assertEquals("the same subfolders again change nothing", 0, SettingsBackup.parse(file).changes().size());

        // Blank is no subfolder, and a file that says so takes a subfolder away.
        JSONObject blank = new JSONObject(file);
        blank.getJSONObject("settings").put(SettingsBackup.VIDEO_SUBFOLDER.key, "");
        SettingsBackup.Snapshot none = SettingsBackup.parse(blank.toString());
        assertEquals("", none.videoSubfolderChange());
        assertNull(none.photoSubfolderChange());
        assertEquals(Collections.singletonMap(SettingsBackup.VIDEO_SUBFOLDER, ""), none.changes());
        assertEquals(1, SettingsBackup.apply(none));
        assertEquals("", Settings.VIDEO_SUBFOLDER.savedValue());
        Settings.VIDEO_SUBFOLDER.save("Clips");

        Map<String, ?> before = store();
        for (String key : new String[]{SettingsBackup.VIDEO_SUBFOLDER.key, SettingsBackup.PHOTO_SUBFOLDER.key}) {
            for (Object refused : new Object[]{"../Clips", "My/Clips", "My\\Clips", "/Clips", "Clips/", ".hidden",
                    "Clips.", " Clips", " ", "..", "a\u200Bb", "a\u202Eb", "a\nb", repeat('a', 51), 5, true,
                    JSONObject.NULL, new JSONObject()}) {
                JSONObject hostile = new JSONObject(file);
                hostile.getJSONObject("settings").put(key, refused);
                try {
                    SettingsBackup.parse(hostile.toString());
                    fail("a file with " + key + " " + printable(String.valueOf(refused)) + " was read");
                } catch (SettingsBackup.Rejected rejected) {
                    assertEquals(key + " " + printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
                }
            }
        }
        assertEquals("a refused file wrote something", before, store());

        // A name from a newer Android comes in as the name the saves here will use, as the folder does.
        String unknown = new String(Character.toChars(0x50000));
        assertEquals(Character.UNASSIGNED, Character.getType(0x50000));
        JSONObject newer = new JSONObject(file);
        newer.getJSONObject("settings").put(SettingsBackup.PHOTO_SUBFOLDER.key, "Snaps " + unknown);
        assertEquals("Snaps", SettingsBackup.parse(newer.toString()).photoSubfolder);
        newer.getJSONObject("settings").put(SettingsBackup.PHOTO_SUBFOLDER.key, "My/" + unknown);
        try {
            SettingsBackup.parse(newer.toString());
            fail("a file with the photo subfolder My/ and a newer character was read");
        } catch (SettingsBackup.Rejected rejected) {
            assertEquals(SettingsBackup.Reason.VALUE, rejected.reason);
        }

        // A file from before the subfolders were carried leaves them alone.
        SettingsBackup.Snapshot older = SettingsBackup.parse(fileWith(Settings.HIDE_SUGGESTED_POSTS, false));
        assertNull(older.videoSubfolder);
        assertNull(older.photoSubfolder);
        assertNull(older.videoSubfolderChange());
        SettingsBackup.apply(older);
        assertEquals("Clips", Settings.VIDEO_SUBFOLDER.savedValue());
        assertEquals("Pics", Settings.PHOTO_SUBFOLDER.savedValue());

        // A preview kept across a rebuild keeps both, blank included, and only clean names come back.
        Bundle state = SettingsBackup.parse(blank.toString()).toBundle();
        assertEquals("", SettingsBackup.Snapshot.fromBundle(state).videoSubfolder);
        assertEquals("Pics", SettingsBackup.Snapshot.fromBundle(state).photoSubfolder);
        state.putString("video_subfolder", "My/Clips");
        state.putInt("photo_subfolder", 3);
        assertNull(SettingsBackup.Snapshot.fromBundle(state).videoSubfolder);
        assertNull(SettingsBackup.Snapshot.fromBundle(state).photoSubfolder);
    }

    /**
     * A file that changes the reels and video stories qualities and the subfolders counts each one in
     * the preview and the toast, with a sentence saying what it'll do, and the rows show them after.
     */
    @Test
    public void importOfTheSurfaceQualitiesAndSubfoldersSaysWhatEachWillDo() throws Exception {
        Settings.STORIES_PLAYBACK_QUALITY.save(SurfaceQuality.HIGHEST);
        Settings.PHOTO_SUBFOLDER.save("Pics");
        JSONObject file = new JSONObject(fileWith(Settings.DOWNLOAD_REELS, false));
        file.getJSONObject("settings").put(SettingsBackup.REELS_QUALITY.key, "720p")
                .put(SettingsBackup.STORIES_QUALITY.key, "same")
                .put(SettingsBackup.VIDEO_SUBFOLDER.key, "Clips")
                .put(SettingsBackup.PHOTO_SUBFOLDER.key, "");
        assertEquals(5, SettingsBackup.parse(file.toString()).changes().size());
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            deliver(activity, tap(activity, page, IMPORT_ROW), file.toString());
            AlertDialog preview = shownPreview();
            String reels = "Reels quality will be set to Up to " + L10n.isolate("720p") + ".";
            String stories = "Video stories will play at the same quality as other videos.";
            String videos = "Videos will go in a subfolder named " + L10n.isolate("Clips") + ".";
            String photos = "Photos will go in the save folder itself.";
            assertEquals("1 switch will change.\n\n" + reels + "\n\n" + stories + "\n\n" + videos + "\n\n" + photos,
                    String.valueOf(shadowOf(preview).getMessage()));
            preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();
            assertEquals("Settings imported. 1 switch changed. " + reels + " " + stories + " " + videos + " " + photos,
                    ShadowToast.getTextOfLatestToast());
            assertEquals(SurfaceQuality.P720, Settings.REELS_PLAYBACK_QUALITY.savedValue());
            assertEquals(SurfaceQuality.SAME, Settings.STORIES_PLAYBACK_QUALITY.savedValue());
            assertEquals("Clips", Settings.VIDEO_SUBFOLDER.savedValue());
            assertEquals("", Settings.PHOTO_SUBFOLDER.savedValue());
            assertEquals("the Reels quality row still shows the old quality",
                    HushfacebookPreferenceFragment.surfaceQualitySummary(SurfaceQuality.P720, true),
                    String.valueOf(page.findPreference(Settings.REELS_PLAYBACK_QUALITY.key).getSummary()));
            assertEquals("the Video subfolder row still shows the old folder",
                    HushfacebookPreferenceFragment.subfolderSummary(true, "Clips"),
                    String.valueOf(page.findPreference(Settings.VIDEO_SUBFOLDER.key).getSummary()));
        }
        assertEquals("Settings imported. Photos will go in a subfolder named " + L10n.isolate("Pics") + ".",
                SettingsBackupPreference.importedMessage(0, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, "Pics", null, null));
        assertEquals("Settings imported. Saves will go to a folder named " + L10n.isolate("Clips") + ".",
                SettingsBackupPreference.importedMessage(0, "Clips", null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null));
        assertEquals("Settings imported. Stories quality will be set to Highest. Saves will go to a folder named "
                        + L10n.isolate("Clips") + ".",
                SettingsBackupPreference.importedMessage(0, "Clips", null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, SurfaceQuality.HIGHEST));
    }

    /**
     * A file exported with Send the link to an app and a package set puts both back on a clean
     * install (#41).
     */
    @Test
    public void theDownloadActionAndTheSendToAppComeBackOnACleanInstall() throws Exception {
        Settings.DOWNLOAD_ACTION.save(SendLink.Action.SEND);
        Settings.SEND_TO_APP.save(SendLink.YTDLNIS);
        String file = SettingsBackup.create();
        JSONObject written = new JSONObject(file).getJSONObject("settings");
        assertEquals("send", written.get(SettingsBackup.ACTION.key));
        assertEquals(SendLink.YTDLNIS, written.get(SettingsBackup.APP.key));

        Settings.DOWNLOAD_ACTION.resetToDefault();
        Settings.SEND_TO_APP.resetToDefault();
        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
        assertEquals(SendLink.Action.SEND, snapshot.actionChange());
        assertEquals(SendLink.YTDLNIS, snapshot.appChange());
        Map<Setting<?>, Object> expected = new LinkedHashMap<>();
        expected.put(SettingsBackup.ACTION, SendLink.Action.SEND);
        expected.put(SettingsBackup.APP, SendLink.YTDLNIS);
        assertEquals(expected, snapshot.changes());
        assertEquals(2, SettingsBackup.apply(snapshot));
        assertEquals(SendLink.Action.SEND, Settings.DOWNLOAD_ACTION.savedValue());
        assertEquals(SendLink.YTDLNIS, Settings.SEND_TO_APP.savedValue());
        assertEquals("a file read back is the file", file, SettingsBackup.create());
        assertEquals("the same file again changes nothing", 0, SettingsBackup.parse(file).changes().size());
    }

    /**
     * The download action goes out as its file value and the app as the package links really go
     * to, blank for Android's chooser, and each comes back only as one of those: any other action,
     * or an app that isn't blank or one package name as it stands, refuses the whole file.
     */
    @Test
    public void theDownloadActionAndTheAppComeBackOnlyAsOnesTheRowsTake() throws Exception {
        for (SendLink.Action action : SendLink.Action.values()) {
            Settings.DOWNLOAD_ACTION.save(action);
            String file = SettingsBackup.create();
            assertEquals(action.fileValue, new JSONObject(file).getJSONObject("settings").get(SettingsBackup.ACTION.key));
            Settings.DOWNLOAD_ACTION.save(action == SendLink.Action.SEND ? SendLink.Action.SAVE : SendLink.Action.SEND);

            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
            assertEquals(action, snapshot.action);
            assertEquals(Collections.singletonMap(SettingsBackup.ACTION, action), snapshot.changes());
            assertEquals(1, SettingsBackup.apply(snapshot));
            assertEquals(action, Settings.DOWNLOAD_ACTION.savedValue());
            assertEquals("a file read back is the file", file, SettingsBackup.create());
        }
        for (String app : new String[]{"", SendLink.SEAL, "a.b"}) {
            Settings.SEND_TO_APP.save(app);
            String file = SettingsBackup.create();
            assertEquals(app, new JSONObject(file).getJSONObject("settings").get(SettingsBackup.APP.key));
            Settings.SEND_TO_APP.save(app.isEmpty() ? SendLink.YTDLNIS : "");

            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
            assertEquals(app, snapshot.app);
            assertEquals(Collections.singletonMap(SettingsBackup.APP, app), snapshot.changes());
            assertEquals(1, SettingsBackup.apply(snapshot));
            assertEquals(app, Settings.SEND_TO_APP.savedValue());
            assertEquals("a file read back is the file", file, SettingsBackup.create());
        }

        // A saved name with spaces round it, or one that isn't a package name, goes out as where
        // links really go, and that's no change here.
        Settings.SEND_TO_APP.save(" " + SendLink.SEAL + "  ");
        String trimmed = SettingsBackup.create();
        assertEquals(SendLink.SEAL, new JSONObject(trimmed).getJSONObject("settings").get(SettingsBackup.APP.key));
        assertNull(SettingsBackup.parse(trimmed).appChange());
        Settings.SEND_TO_APP.save("not a package");
        String blank = SettingsBackup.create();
        assertEquals("", new JSONObject(blank).getJSONObject("settings").get(SettingsBackup.APP.key));
        assertNull(SettingsBackup.parse(blank).appChange());

        Settings.DOWNLOAD_ACTION.save(SendLink.Action.SEND);
        Settings.SEND_TO_APP.save(SendLink.YTDLNIS);
        String file = SettingsBackup.create();
        Map<String, ?> before = store();
        for (Object refused : new Object[]{"SEND", "Send", "share", "", " send", 1, true, JSONObject.NULL,
                new JSONObject(), new org.json.JSONArray()}) {
            JSONObject hostile = new JSONObject(file);
            hostile.getJSONObject("settings").put(SettingsBackup.ACTION.key, refused);
            try {
                SettingsBackup.parse(hostile.toString());
                fail("a file with the download action " + printable(String.valueOf(refused)) + " was read");
            } catch (SettingsBackup.Rejected rejected) {
                assertEquals(printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
            }
        }
        for (Object refused : new Object[]{"ytdl", " " + SendLink.YTDLNIS, SendLink.YTDLNIS + " ", "com..ytdl",
                "com.ytdl.", "1com.ytdl", "com.1ytdl", "com.ytdl/Main", "com.ytdl;x", "com.yt\u00e9dl", " ", "\n", 7,
                false, JSONObject.NULL, new JSONObject(), new org.json.JSONArray()}) {
            JSONObject hostile = new JSONObject(file);
            hostile.getJSONObject("settings").put(SettingsBackup.APP.key, refused);
            try {
                SettingsBackup.parse(hostile.toString());
                fail("a file with the app " + printable(String.valueOf(refused)) + " was read");
            } catch (SettingsBackup.Rejected rejected) {
                assertEquals(printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
                assertEquals("a refusal names the setting, never what the file holds",
                        "Not one package name: " + SettingsBackup.APP.key, rejected.getMessage());
            }
        }
        assertEquals("a refused file wrote something", before, store());

        // A file from before these were carried leaves them alone.
        SettingsBackup.Snapshot older = SettingsBackup.parse(fileWith(Settings.HIDE_SUGGESTED_POSTS, false));
        assertNull(older.action);
        assertNull(older.app);
        assertNull(older.actionChange());
        assertNull(older.appChange());
        SettingsBackup.apply(older);
        assertEquals(SendLink.Action.SEND, Settings.DOWNLOAD_ACTION.savedValue());
        assertEquals(SendLink.YTDLNIS, Settings.SEND_TO_APP.savedValue());

        // A preview kept across a rebuild keeps both, and only what a file could hold comes back.
        Bundle state = SettingsBackup.parse(file).toBundle();
        SettingsBackup.Snapshot kept = SettingsBackup.Snapshot.fromBundle(state);
        assertEquals(SendLink.Action.SEND, kept.action);
        assertEquals(SendLink.YTDLNIS, kept.app);
        state.putString("download_action", "SEND");
        state.putString("send_to_app", "not a package");
        kept = SettingsBackup.Snapshot.fromBundle(state);
        assertNull(kept.action);
        assertNull(kept.app);
        state.putInt("download_action", 1);
        state.putInt("send_to_app", 1);
        kept = SettingsBackup.Snapshot.fromBundle(state);
        assertNull(kept.action);
        assertNull(kept.app);
    }

    /** A file that changes the download action and the app says what Download will do, before and after. */
    @Test
    public void importOfTheDownloadActionAndAppSaysWhereLinksWillGo() throws Exception {
        JSONObject file = new JSONObject(fileWith(Settings.DOWNLOAD_REELS, false));
        file.getJSONObject("settings").put(SettingsBackup.ACTION.key, "send")
                .put(SettingsBackup.APP.key, SendLink.YTDLNIS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            deliver(activity, tap(activity, page, IMPORT_ROW), file.toString());
            AlertDialog preview = shownPreview();
            String action = "Tapping Download on a reel or video will send its link to an app.";
            String app = "Links will go to " + L10n.isolate(SendLink.YTDLNIS) + ".";
            assertEquals("1 switch will change.\n\n" + action + "\n\n" + app,
                    String.valueOf(shadowOf(preview).getMessage()));
            preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();
            assertEquals("Settings imported. 1 switch changed. " + action + " " + app,
                    ShadowToast.getTextOfLatestToast());
            assertEquals(SendLink.Action.SEND, Settings.DOWNLOAD_ACTION.savedValue());
            assertEquals(SendLink.YTDLNIS, Settings.SEND_TO_APP.savedValue());
            assertEquals("the download action row still shows the old action",
                    HushfacebookPreferenceFragment.downloadActionSummary(SendLink.Action.SEND),
                    String.valueOf(page.findPreference(Settings.DOWNLOAD_ACTION.key).getSummary()));
            assertEquals("the send-to row still shows the old app",
                    HushfacebookPreferenceFragment.sendAppSummary(SendLink.YTDLNIS),
                    String.valueOf(page.findPreference(Settings.SEND_TO_APP.key).getSummary()));
        }
        assertEquals("Settings imported. Reels and videos will save to this phone when you tap Download. "
                        + "Android will ask which app gets the links each time.",
                SettingsBackupPreference.importedMessage(0, null, null, null, null, null, null, null, null,
                        SendLink.Action.SAVE, ""));
    }

    /** A file exported with Save to set to Download puts it back on a clean install (#42). */
    @Test
    public void theTopFolderComesBackOnACleanInstall() throws Exception {
        Settings.SAVE_TO.save(SaveTo.DOWNLOAD);
        String file = SettingsBackup.create();
        assertEquals("download", new JSONObject(file).getJSONObject("settings").get(SettingsBackup.TO.key));

        Settings.SAVE_TO.resetToDefault();
        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
        assertEquals(SaveTo.DOWNLOAD, snapshot.toChange());
        assertEquals(Collections.singletonMap(SettingsBackup.TO, SaveTo.DOWNLOAD), snapshot.changes());
        assertEquals(1, SettingsBackup.apply(snapshot));
        assertEquals(SaveTo.DOWNLOAD, Settings.SAVE_TO.savedValue());
        assertEquals("a file read back is the file", file, SettingsBackup.create());
        assertEquals("the same file again changes nothing", 0, SettingsBackup.parse(file).changes().size());
    }

    /**
     * Each top folder goes out as its file value and comes back only as one of those. Anything else
     * refuses the whole file, a file from before it was carried leaves it alone, and a preview kept
     * across a rebuild keeps only a value a file could hold.
     */
    @Test
    public void theTopFolderComesBackOnlyAsOneTheRowOffers() throws Exception {
        for (SaveTo to : SaveTo.values()) {
            Settings.SAVE_TO.save(to);
            String file = SettingsBackup.create();
            assertEquals(to.fileValue, new JSONObject(file).getJSONObject("settings").get(SettingsBackup.TO.key));
            Settings.SAVE_TO.save(to == SaveTo.DCIM ? SaveTo.DOWNLOAD : SaveTo.DCIM);

            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
            assertEquals(to, snapshot.to);
            assertEquals(Collections.singletonMap(SettingsBackup.TO, to), snapshot.changes());
            assertEquals(1, SettingsBackup.apply(snapshot));
            assertEquals(to, Settings.SAVE_TO.savedValue());
            assertEquals("a file read back is the file", file, SettingsBackup.create());
        }
        assertEquals(Arrays.asList("movies_pictures", "dcim", "download"), Arrays.asList(
                SaveTo.MOVIES_AND_PICTURES.fileValue, SaveTo.DCIM.fileValue, SaveTo.DOWNLOAD.fileValue));

        Settings.SAVE_TO.save(SaveTo.DCIM);
        String file = SettingsBackup.create();
        Map<String, ?> before = store();
        for (Object refused : new Object[]{"DCIM", "Download", "downloads", "movies", "pictures", "MOVIES_AND_PICTURES",
                "", " dcim", "dcim ", "sdcard", 1, true, JSONObject.NULL, new JSONObject(), new org.json.JSONArray()}) {
            JSONObject hostile = new JSONObject(file);
            hostile.getJSONObject("settings").put(SettingsBackup.TO.key, refused);
            try {
                SettingsBackup.parse(hostile.toString());
                fail("a file with the top folder " + printable(String.valueOf(refused)) + " was read");
            } catch (SettingsBackup.Rejected rejected) {
                assertEquals(printable(String.valueOf(refused)), SettingsBackup.Reason.VALUE, rejected.reason);
                assertEquals("a refusal names the setting, never what the file holds",
                        "Not a save location: " + SettingsBackup.TO.key, rejected.getMessage());
            }
        }
        assertEquals("a refused file wrote something", before, store());

        // A file from before the top folder was carried leaves it alone.
        SettingsBackup.Snapshot older = SettingsBackup.parse(fileWith(Settings.HIDE_SUGGESTED_POSTS, false));
        assertNull(older.to);
        assertNull(older.toChange());
        SettingsBackup.apply(older);
        assertEquals(SaveTo.DCIM, Settings.SAVE_TO.savedValue());

        Bundle state = SettingsBackup.parse(file).toBundle();
        assertEquals(SaveTo.DCIM, SettingsBackup.Snapshot.fromBundle(state).to);
        state.putString("save_to", "DCIM");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).to);
        state.putInt("save_to", 1);
        assertNull(SettingsBackup.Snapshot.fromBundle(state).to);
    }

    /** A file that changes the top folder says where saves will go, in the preview and the toast. */
    @Test
    public void importOfTheTopFolderSaysWhereSavesWillGo() throws Exception {
        JSONObject file = new JSONObject(fileWith(Settings.DOWNLOAD_REELS, false));
        file.getJSONObject("settings").put(SettingsBackup.TO.key, "download");
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            deliver(activity, tap(activity, page, IMPORT_ROW), file.toString());
            AlertDialog preview = shownPreview();
            String to = "Videos and photos will go to " + L10n.isolate("Download") + ".";
            assertEquals("1 switch will change.\n\n" + to, String.valueOf(shadowOf(preview).getMessage()));
            preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();
            assertEquals("Settings imported. 1 switch changed. " + to, ShadowToast.getTextOfLatestToast());
            assertEquals(SaveTo.DOWNLOAD, Settings.SAVE_TO.savedValue());
            assertEquals("the Save to row still shows the old top folder",
                    HushfacebookPreferenceFragment.saveToSummary(SaveTo.DOWNLOAD),
                    String.valueOf(page.findPreference(Settings.SAVE_TO.key).getSummary()));
            assertEquals("the folder row still names the old top folder",
                    HushfacebookPreferenceFragment.folderSummary(
                            app.morphe.extension.facebook.download.SaveFolder.DEFAULT, SaveTo.DOWNLOAD),
                    String.valueOf(page.findPreference(Settings.SAVE_FOLDER.key).getSummary()));
        }
        assertEquals("Settings imported. Videos will go to " + L10n.isolate("Movies") + " and photos to "
                        + L10n.isolate("Pictures") + ".",
                SettingsBackupPreference.importedMessage(0, null, null, null, null, null, null, null, null, null, null,
                        SaveTo.MOVIES_AND_PICTURES));
    }

    /** A file that changes the start tab says where Facebook will open, before and after. */
    @Test
    public void importOfAStartTabSaysWhereFacebookWillOpen() throws Exception {
        JSONObject file = new JSONObject(fileWith(Settings.DOWNLOAD_REELS, false));
        file.getJSONObject("settings").put(SettingsBackup.START.key, "notifications")
                .put(SettingsBackup.QUALITY.key, "720p");
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            deliver(activity, tap(activity, page, IMPORT_ROW), file.toString());
            AlertDialog preview = shownPreview();
            String start = "Facebook will open on Notifications.";
            String quality = "Videos will save at " + app.morphe.extension.shared.L10n.isolate("720p")
                    + " or the closest quality below it. A video with nothing that low will save at the closest "
                    + "quality above.";
            assertEquals("1 switch will change.\n\n" + start + "\n\n" + quality,
                    String.valueOf(shadowOf(preview).getMessage()));
            preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();
            assertEquals("Settings imported. 1 switch changed. " + start + " " + quality,
                    ShadowToast.getTextOfLatestToast());
            assertEquals(StartTab.NOTIFICATIONS, Settings.START_TAB.savedValue());
            assertEquals("the start tab row still shows the old tab",
                    HushfacebookPreferenceFragment.startTabSummary(StartTab.NOTIFICATIONS),
                    String.valueOf(page.findPreference(Settings.START_TAB.key).getSummary()));
        }
        assertEquals("Settings imported. Facebook will open on Home.",
                SettingsBackupPreference.importedMessage(0, null, null, null, StartTab.HOME));
    }

    /** A preview kept across a rebuild keeps its folder, and only a clean one comes back. */
    @Test
    public void aWaitingImportKeepsItsFolderOnlyWhileItIsClean() throws Exception {
        Settings.SAVE_FOLDER.save("Clips");
        SettingsBackup.Snapshot read = SettingsBackup.parse(SettingsBackup.create());
        Bundle state = read.toBundle();
        assertEquals("Clips", SettingsBackup.Snapshot.fromBundle(state).folder);

        state.putString("folder", "../Clips");
        assertNull(SettingsBackup.Snapshot.fromBundle(state).folder);
        state.putInt("folder", 5);
        assertNull(SettingsBackup.Snapshot.fromBundle(state).folder);
    }

    @Test
    public void aFileThatNamesSomeSwitchesLeavesTheRestAlone() throws Exception {
        Settings.OPEN_LINKS_EXTERNALLY.save(false);
        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(fileWith(Settings.HIDE_SUGGESTED_POSTS, false));
        assertEquals(1, snapshot.values.size());
        assertEquals(1, SettingsBackup.apply(snapshot));
        assertFalse(Settings.HIDE_SUGGESTED_POSTS.savedValue());
        assertFalse("a switch the file didn't name was reset", Settings.OPEN_LINKS_EXTERNALLY.savedValue());
        assertTrue(Settings.HIDE_SPONSORED_POSTS.savedValue());
    }

    @Test
    public void aWaitingImportSurvivesAsABundle() throws Exception {
        JSONObject file = new JSONObject(fileWith(Settings.HIDE_PROMOTED_POSTS, false, Settings.DOWNLOAD_REELS, true));
        file.getJSONObject("settings").put("later_switch", true);
        SettingsBackup.Snapshot read = SettingsBackup.parse(file.toString());
        SettingsBackup.Snapshot back = SettingsBackup.Snapshot.fromBundle(read.toBundle());
        assertNotNull(back);
        assertEquals(read.values, back.values);
        assertEquals(1, back.unknown);

        // A saved state is read back as strictly as a file: only switches on the list, as true or false.
        Bundle state = read.toBundle();
        Bundle switches = state.getBundle("switches");
        switches.putBoolean(BaseSettings.PAUSED.key, true);
        switches.putString(Settings.HIDE_SPONSORED_POSTS.key, "false");
        SettingsBackup.Snapshot filtered = SettingsBackup.Snapshot.fromBundle(state);
        assertEquals(read.values, filtered.values);
        assertNull(SettingsBackup.Snapshot.fromBundle(null));
        assertNull(SettingsBackup.Snapshot.fromBundle(new Bundle()));
        Bundle negative = read.toBundle();
        negative.putInt("unknown", -1);
        assertNull(SettingsBackup.Snapshot.fromBundle(negative));
    }

    @Test
    public void everyRefusalHasItsOwnSentence() {
        Set<String> sentences = new HashSet<>();
        for (SettingsBackup.Reason reason : SettingsBackup.Reason.values()) {
            String sentence = SettingsBackupPreference.refusal(reason);
            assertTrue(reason + ": " + sentence, sentence.endsWith("Nothing was changed."));
            sentences.add(sentence);
        }
        assertEquals("two refusals read the same", SettingsBackup.Reason.values().length, sentences.size());
    }

    // ---- The screen and the picker ------------------------------------------------------------

    @Test
    public void exportWritesTheFileThroughTheSystemPicker() throws Exception {
        Settings.HIDE_PROMOTED_POSTS.save(false);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            ShadowActivity.IntentForResult started = tap(activity, page, EXPORT_ROW);
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, started.intent.getAction());
            assertEquals(SettingsBackupPreference.EXPORT, started.requestCode);
            assertEquals("application/json", started.intent.getType());
            assertTrue(started.intent.hasCategory(Intent.CATEGORY_OPENABLE));
            String name = started.intent.getStringExtra(Intent.EXTRA_TITLE);
            assertTrue("the picker would suggest " + name, name.matches("hushfacebook-settings-\\d{8}-\\d{6}\\.json"));

            Uri uri = SettingsFileProvider.put(AUTHORITY, "export.json", new byte[0]);
            shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, new Intent().setData(uri));
            settle();

            assertEquals("Settings exported.", ShadowToast.getTextOfLatestToast());
            assertEquals(SettingsBackup.create(), new String(SettingsFileProvider.get(uri), StandardCharsets.UTF_8));
            assertTrue(page.findPreference(EXPORT_ROW).isEnabled());
        }
    }

    /** What Export writes is what Import takes back, through both pickers, every switch included. */
    @Test
    public void aFileExportedThroughThePickerImportsBackThroughIt() throws Exception {
        Settings.HIDE_SUGGESTED_POSTS.save(false);
        Settings.OPEN_LINKS_EXTERNALLY.save(false);
        Map<BooleanSetting, Boolean> exported = new LinkedHashMap<>();
        for (BooleanSetting setting : SettingsBackup.ALLOWLIST) exported.put(setting, setting.savedValue());
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            Uri uri = SettingsFileProvider.put(AUTHORITY, "round-trip.json", new byte[0]);
            shadowOf(activity).receiveResult(tap(activity, page, EXPORT_ROW).intent, Activity.RESULT_OK,
                    new Intent().setData(uri));
            settle();

            for (BooleanSetting setting : SettingsBackup.ALLOWLIST) setting.save(!setting.savedValue());
            deliver(activity, tap(activity, page, IMPORT_ROW), new String(SettingsFileProvider.get(uri), StandardCharsets.UTF_8));
            AlertDialog preview = shownPreview();
            assertEquals(SettingsBackup.ALLOWLIST.size() + " switches will change.",
                    String.valueOf(shadowOf(preview).getMessage()));
            preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();
            for (Map.Entry<BooleanSetting, Boolean> entry : exported.entrySet()) {
                assertEquals(entry.getKey().key, entry.getValue(), entry.getKey().savedValue());
            }
        }
    }

    /**
     * An export asked for just before a Quick setup, whose worker gets to run only once the setup
     * is halfway down its rows. A worker reading the switches at that moment finds some of the
     * setup's switches on and the rest still off, a mix nobody chose, and that's where exports used
     * to read them. The file holds every switch as it was when the export was asked for, and an
     * export asked for after the setup holds all of it.
     */
    @Test
    public void anExportOverlappingAQuickSetupHoldsTheChoicesFromBeforeIt() throws Exception {
        for (BooleanSetting setting : QuickSetupKeys.QUIET) setting.save(false);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            List<BooleanSetting> flipped = new ArrayList<>();
            for (BooleanSetting setting : QuickSetupKeys.QUIET) {
                Preference row = page.findPreference(setting.key);
                if (row instanceof TwoStatePreference && !((TwoStatePreference) row).isChecked()) flipped.add(setting);
            }
            assertTrue("Quiet flips too few rows here to stop between two of them", flipped.size() >= 3);
            String middle = flipped.get(flipped.size() / 2).key;
            String before = SettingsBackup.create();
            Uri uri = SettingsFileProvider.put(AUTHORITY, "during-setup.json", new byte[0]);
            ShadowActivity.IntentForResult picker = tap(activity, page, EXPORT_ROW);

            String[] midway = new String[1];
            try (WorkerPoolForTests held = WorkerPoolForTests.hold()) {
                // Each row's write is announced on this thread as it lands, with the setup still going.
                SharedPreferences.OnSharedPreferenceChangeListener betweenRows = (changed, key) -> {
                    if (midway[0] == null && middle.equals(key)) midway[0] = letTheExportRunAndRead(held);
                };
                Setting.preferences.preferences.registerOnSharedPreferenceChangeListener(betweenRows);
                try {
                    shadowOf(activity).receiveResult(picker.intent, Activity.RESULT_OK, new Intent().setData(uri));
                    page.applyQuickSetup(QuickSetupKeys.quiet());
                } finally {
                    Setting.preferences.preferences.unregisterOnSharedPreferenceChangeListener(betweenRows);
                }
            }
            settle();
            String after = SettingsBackup.create();

            assertNotNull("the export's worker never ran inside the Quick setup", midway[0]);
            assertEquals(0, switchesOn(before, flipped));
            assertEquals(flipped.size(), switchesOn(after, flipped));
            int mixed = switchesOn(midway[0], flipped);
            assertTrue("a worker reading halfway through found " + mixed + " of " + flipped.size()
                    + " switches on, which is no mix", mixed > 0 && mixed < flipped.size());
            assertEquals("the file holds part of a Quick setup", before,
                    new String(SettingsFileProvider.get(uri), StandardCharsets.UTF_8));

            Uri later = SettingsFileProvider.put(AUTHORITY, "after-setup.json", new byte[0]);
            export(activity, page, later);
            assertEquals(after, new String(SettingsFileProvider.get(later), StandardCharsets.UTF_8));
        }
    }

    /**
     * The same for the two word lists, which are saved one at a time: a phrase moved from the hide
     * list to the keep list, with the export's worker running between the two saves. A worker
     * reading then finds the phrase in neither list. The file holds both lists as they were when
     * the export was asked for.
     */
    @Test
    public void anExportOverlappingAChangeToBothWordListsHoldsBothFromBeforeIt() throws Exception {
        Settings.HIDDEN_WORDS.save("spoiler\nmy team");
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            String before = SettingsBackup.create();
            Uri uri = SettingsFileProvider.put(AUTHORITY, "during-lists.json", new byte[0]);
            ShadowActivity.IntentForResult picker = tap(activity, page, EXPORT_ROW);

            String midway;
            try (WorkerPoolForTests held = WorkerPoolForTests.hold()) {
                shadowOf(activity).receiveResult(picker.intent, Activity.RESULT_OK, new Intent().setData(uri));
                Settings.HIDDEN_WORDS.save("spoiler");
                midway = letTheExportRunAndRead(held);
                Settings.KEPT_WORDS.save("my team");
            }
            settle();

            JSONObject mixed = new JSONObject(midway).getJSONObject("settings");
            assertEquals("spoiler", mixed.get(SettingsBackup.HIDDEN.key));
            assertEquals("a worker reading between the two saves found the phrase", "", mixed.get(SettingsBackup.KEPT.key));
            String file = new String(SettingsFileProvider.get(uri), StandardCharsets.UTF_8);
            assertEquals("the file holds half of a change to both word lists", before, file);
            JSONObject carried = new JSONObject(file).getJSONObject("settings");
            assertEquals("spoiler\nmy team", carried.get(SettingsBackup.HIDDEN.key));
            assertEquals("", carried.get(SettingsBackup.KEPT.key));
            assertEquals("my team", Settings.KEPT_WORDS.savedValue());
        }
    }

    /** A file is written from what was saved when it was read, whatever is saved before it's written. */
    @Test
    public void aFileIsWrittenFromWhatWasSavedWhenItWasRead() throws Exception {
        Settings.HIDDEN_WORDS.save("spoiler");
        SettingsBackup.Saved saved = SettingsBackup.savedNow();
        String then = SettingsBackup.create();

        for (BooleanSetting setting : SettingsBackup.ALLOWLIST) setting.save(!setting.savedValue());
        Settings.HIDDEN_WORDS.save("leak");
        Settings.START_TAB.save(StartTab.FRIENDS);

        assertEquals(then, saved.file());
        assertFalse("the saves after it never landed", then.equals(SettingsBackup.create()));
    }

    /** With nothing on the phone to answer either picker, both rows say so and stay usable. */
    @Test
    public void aPhoneWithNoFilePickerSaysSo() throws Exception {
        org.robolectric.shadows.ShadowApplication application = shadowOf(RuntimeEnvironment.getApplication());
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(controller.get()));
            application.checkActivities(true);
            for (String row : new String[]{EXPORT_ROW, IMPORT_ROW}) {
                ShadowToast.reset();
                click(page.findPreference(row));
                ShadowLooper.idleMainLooper();
                assertEquals(row, "This phone has no file picker, so there's no way to choose a file here.",
                        ShadowToast.getTextOfLatestToast());
                assertTrue(row + " stayed out of reach", page.findPreference(row).isEnabled());
            }
        } finally {
            application.checkActivities(false);
        }
    }

    @Test
    public void importShowsHowManySwitchesChangeAndWritesThemOnImport() throws Exception {
        JSONObject file = new JSONObject(fileWith(Settings.HIDE_SPONSORED_POSTS, false,
                Settings.HIDE_SPONSORED_STORIES, false, Settings.DOWNLOAD_STORIES, true));
        file.getJSONObject("settings").put("later_switch", true);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            ShadowActivity.IntentForResult started = tap(activity, page, IMPORT_ROW);
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.getAction());
            assertEquals(SettingsBackupPreference.IMPORT, started.requestCode);
            assertTrue(started.intent.hasCategory(Intent.CATEGORY_OPENABLE));
            assertEquals("*/*", started.intent.getType());
            assertTrue(Arrays.asList(started.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)).contains("application/json"));

            Map<String, ?> before = store();
            deliver(activity, started, file.toString());
            AlertDialog preview = shownPreview();
            assertEquals("Import settings", String.valueOf(shadowOf(preview).getTitle()));
            assertEquals("2 switches will change.\n\n1 item in that file isn't a setting this version of "
                    + "Hushfacebook knows, so it'll be left out.", String.valueOf(shadowOf(preview).getMessage()));
            assertEquals("the preview wrote something", before, store());
            assertEquals("Import", String.valueOf(preview.getButton(AlertDialog.BUTTON_POSITIVE).getText()));
            assertEquals("Cancel", String.valueOf(preview.getButton(AlertDialog.BUTTON_NEGATIVE).getText()));

            preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();
            assertEquals("Settings imported. 2 switches changed.", ShadowToast.getTextOfLatestToast());
            assertFalse(Settings.HIDE_SPONSORED_POSTS.savedValue());
            assertFalse(Settings.HIDE_SPONSORED_STORIES.savedValue());
            assertTrue(Settings.DOWNLOAD_STORIES.savedValue());
            assertFalse("the page still shows the switch on",
                    ((SwitchPreference) page.findPreference(Settings.HIDE_SPONSORED_POSTS.key)).isChecked());
            assertNull(page.pendingImport);
            assertFalse(AbstractPreferenceFragment.settingImportInProgress);
        }
    }

    @Test
    public void importingARestartGatedSwitchShowsTheRestartRow() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            assertEquals(0, ReopenSettingsAfterRestart.changesWaiting(activity));
            deliver(activity, tap(activity, page, IMPORT_ROW), fileWith(Settings.USE_SYSTEM_FONT, true));

            shownPreview().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();

            assertTrue(Settings.USE_SYSTEM_FONT.savedValue());
            assertEquals(1, ReopenSettingsAfterRestart.changesWaiting(activity));
            assertTrue(AbstractPreferenceFragment.restartPending.contains(Settings.USE_SYSTEM_FONT.key));
            page.navigation.search("system font");
            assertTrue("the imported change has no visible restart action", visibleKey(page, SettingsNavigation.RESTART));
        }
    }

    @Test
    public void importingBackToTheRunningValueClearsItsRestartDebt() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            ((SwitchPreference) page.findPreference(Settings.USE_SYSTEM_FONT.key)).setChecked(true);
            ShadowLooper.idleMainLooper();
            assertEquals(1, ReopenSettingsAfterRestart.changesWaiting(activity));
            deliver(activity, tap(activity, page, IMPORT_ROW), fileWith(Settings.USE_SYSTEM_FONT, false));

            shownPreview().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();

            assertFalse(Settings.USE_SYSTEM_FONT.savedValue());
            assertEquals("restoring the running value still owes a restart", 0,
                    ReopenSettingsAfterRestart.changesWaiting(activity));
            assertFalse(AbstractPreferenceFragment.restartPending.contains(Settings.USE_SYSTEM_FONT.key));
            page.navigation.search("system font");
            assertFalse(visibleKey(page, SettingsNavigation.RESTART));
        }
    }

    @Test
    public void failedImportsReconcileExistingRestartDebtWithWhatActuallyLanded() throws Exception {
        FailingStore.Fault[][] failures = {
                {FailingStore.Fault.COMMIT_THROWS},
                {FailingStore.Fault.COMMIT_FALSE, FailingStore.Fault.LOST}};
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            SwitchPreference row = (SwitchPreference) page.findPreference(Settings.USE_SYSTEM_FONT.key);
            for (int index = 0; index < failures.length; index++) {
                row.setChecked(true);
                ShadowLooper.idleMainLooper();
                assertEquals(1, ReopenSettingsAfterRestart.changesWaiting(activity));
                deliver(activity, tap(activity, page, IMPORT_ROW), fileWith(Settings.USE_SYSTEM_FONT, false));
                try (FailingStore ignored = FailingStore.install(failures[index])) {
                    shownPreview().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                    settle();
                }
                boolean keptPreviousValue = index == 0;
                assertEquals(keptPreviousValue, Settings.USE_SYSTEM_FONT.savedValue());
                assertEquals(keptPreviousValue, row.isChecked());
                assertEquals("the restart row disagrees with what survived the failed import",
                        keptPreviousValue ? 1 : 0, ReopenSettingsAfterRestart.changesWaiting(activity));
            }
        }
    }

    @Test
    public void anAsyncImportCannotSplitAnotherRowsStoredAndRunningValues() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            deliver(activity, tap(activity, page, IMPORT_ROW), fileWith(Settings.HIDE_REELS_TAB, true));
            AlertDialog preview = shownPreview();
            SwitchPreference other = (SwitchPreference) page.findPreference(Settings.HIDE_SPONSORED_POSTS.key);
            assertTrue(other.isEnabled());
            SharedPreferences actual = Setting.preferences.preferences;
            CountDownLatch committing = new CountDownLatch(1);
            CountDownLatch proceed = new CountDownLatch(1);

            withStore(blockingFirstCommit(actual, committing, proceed), () -> {
                try {
                    preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                    ShadowLooper.idleMainLooper();
                    assertTrue("the import never reached its commit", committing.await(5, TimeUnit.SECONDS));
                    if (other.isEnabled()) {
                        other.setChecked(!other.isChecked());
                        ShadowLooper.idleMainLooper();
                    }
                } finally {
                    proceed.countDown();
                    settle();
                }
                return null;
            });

            assertTrue(Settings.HIDE_REELS_TAB.savedValue());
            assertEquals("the edited row and the running hook disagree", other.isChecked(),
                    Settings.HIDE_SPONSORED_POSTS.savedValue());
            assertEquals("the next start would use a different value", other.isChecked(),
                    actual.getBoolean(Settings.HIDE_SPONSORED_POSTS.key, Settings.HIDE_SPONSORED_POSTS.defaultValue));
            assertTrue("the ordinary row stayed disabled after import", other.isEnabled());
        }
    }

    private static boolean visibleKey(HushfacebookPreferenceFragment page, String key) {
        ListView list = page.getView().findViewById(android.R.id.list);
        for (int index = 0; index < list.getCount(); index++) {
            if (key.equals(((Preference) list.getItemAtPosition(index)).getKey())) return true;
        }
        return false;
    }

    /** A file that moves only the folder says where saves will go, before and after. */
    @Test
    public void importOfAFolderAloneSaysWhereSavesWillGo() throws Exception {
        JSONObject file = new JSONObject(SettingsBackup.create());
        file.getJSONObject("settings").put(SettingsBackup.FOLDER.key, "Clips");
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            deliver(activity, tap(activity, page, IMPORT_ROW), file.toString());
            AlertDialog preview = shownPreview();
            assertEquals("Saves will go to a folder named " + app.morphe.extension.shared.L10n.isolate("Clips") + ".",
                    String.valueOf(shadowOf(preview).getMessage()));
            preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();
            assertEquals("Settings imported. Saves will go to a folder named "
                    + app.morphe.extension.shared.L10n.isolate("Clips") + ".", ShadowToast.getTextOfLatestToast());
            assertEquals("Clips", Settings.SAVE_FOLDER.savedValue());
            assertEquals("the folder row still shows the old folder",
                    HushfacebookPreferenceFragment.folderSummary("Clips"),
                    String.valueOf(page.findPreference(Settings.SAVE_FOLDER.key).getSummary()));
        }
    }

    /** A file that moves switches and the folder counts the switches and names the folder. */
    @Test
    public void importOfSwitchesAndAFolderSaysBoth() throws Exception {
        JSONObject file = new JSONObject(fileWith(Settings.DOWNLOAD_REELS, false));
        file.getJSONObject("settings").put(SettingsBackup.FOLDER.key, "Clips");
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            deliver(activity, tap(activity, page, IMPORT_ROW), file.toString());
            AlertDialog preview = shownPreview();
            String folder = "Saves will go to a folder named " + app.morphe.extension.shared.L10n.isolate("Clips") + ".";
            assertEquals("1 switch will change.\n\n" + folder, String.valueOf(shadowOf(preview).getMessage()));
            preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();
            assertEquals("Settings imported. 1 switch changed. " + folder, ShadowToast.getTextOfLatestToast());
            assertFalse(Settings.DOWNLOAD_REELS.savedValue());
            assertEquals("Clips", Settings.SAVE_FOLDER.savedValue());
        }
    }

    /**
     * A file that moves the quality says what videos will save at, before and after, beside the
     * switches and the folder it changes, and the row shows the new quality.
     */
    @Test
    public void importOfAQualitySaysWhatVideosWillSaveAt() throws Exception {
        JSONObject file = new JSONObject(SettingsBackup.create());
        file.getJSONObject("settings").put(SettingsBackup.QUALITY.key, "480p");
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            deliver(activity, tap(activity, page, IMPORT_ROW), file.toString());
            AlertDialog preview = shownPreview();
            String quality = "Videos will save at " + app.morphe.extension.shared.L10n.isolate("480p")
                    + " or the closest quality below it. A video with nothing that low will save at the closest "
                    + "quality above.";
            assertEquals(quality, String.valueOf(shadowOf(preview).getMessage()));
            preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();
            assertEquals("Settings imported. " + quality, ShadowToast.getTextOfLatestToast());
            assertEquals(DownloadQuality.P480, Settings.DOWNLOAD_QUALITY.savedValue());
            assertEquals("the quality row still shows the old quality",
                    HushfacebookPreferenceFragment.qualitySummary(DownloadQuality.P480),
                    String.valueOf(page.findPreference(Settings.DOWNLOAD_QUALITY.key).getSummary()));
        }

        file = new JSONObject(fileWith(Settings.DOWNLOAD_REELS, false));
        file.getJSONObject("settings").put(SettingsBackup.QUALITY.key, "smallest")
                .put(SettingsBackup.FOLDER.key, "Clips");
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            deliver(activity, tap(activity, page, IMPORT_ROW), file.toString());
            AlertDialog preview = shownPreview();
            String quality = "Videos will save at their lowest quality, for the smallest files.";
            String folder = "Saves will go to a folder named " + app.morphe.extension.shared.L10n.isolate("Clips") + ".";
            assertEquals("1 switch will change.\n\n" + quality + "\n\n" + folder,
                    String.valueOf(shadowOf(preview).getMessage()));
            preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();
            assertEquals("Settings imported. 1 switch changed. " + quality + " " + folder,
                    ShadowToast.getTextOfLatestToast());
            assertEquals(DownloadQuality.SMALLEST,
                    Settings.DOWNLOAD_QUALITY.savedValue());
            assertEquals("Clips", Settings.SAVE_FOLDER.savedValue());
            assertFalse(Settings.DOWNLOAD_REELS.savedValue());
        }
        // Back to the best: that has a sentence of its own, and a quality with a folder drops the
        // folder's own toast for the sentences.
        assertEquals("Videos will save at the best quality.",
                SettingsBackupPreference.qualitySentence(DownloadQuality.BEST));
        assertEquals("Settings imported. Videos will save at the best quality. Saves will go to a folder named "
                        + app.morphe.extension.shared.L10n.isolate("Clips") + ".",
                SettingsBackupPreference.importedMessage(0, "Clips",
                        DownloadQuality.BEST, null));
    }

    @Test
    public void aFileThatChangesNothingSaysSoAndOffersNoImport() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            deliver(activity, tap(activity, page, IMPORT_ROW), SettingsBackup.create());
            AlertDialog preview = shownPreview();
            assertEquals("Your settings already match that file, so nothing will change.",
                    String.valueOf(shadowOf(preview).getMessage()));
            assertEquals("OK", String.valueOf(preview.getButton(AlertDialog.BUTTON_POSITIVE).getText()));
            assertFalse(preview.getButton(AlertDialog.BUTTON_NEGATIVE).isShown());
            preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();
            assertNull(page.pendingImport);
        }
    }

    @Test
    public void cancellingThePreviewChangesNothing() throws Exception {
        String file = fileWith(Settings.OPEN_LINKS_EXTERNALLY, false);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            Map<String, ?> before = store();

            deliver(activity, tap(activity, page, IMPORT_ROW), file);
            shownPreview().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            settle();
            assertNull(page.pendingImport);

            // Back, or a tap outside it, is a cancel too.
            deliver(activity, tap(activity, page, IMPORT_ROW), file);
            shownPreview().cancel();
            settle();
            assertNull(page.pendingImport);

            assertEquals(before, store());
            assertTrue(Settings.OPEN_LINKS_EXTERNALLY.savedValue());
            assertNull("a cancelled import said something", ShadowToast.getTextOfLatestToast());
        }
    }

    @Test
    public void aCancelledPickerChangesNothingAndSaysNothing() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            for (String row : new String[]{EXPORT_ROW, IMPORT_ROW}) {
                shadowOf(activity).receiveResult(tap(activity, page, row).intent, Activity.RESULT_CANCELED, null);
                settle();
            }
            assertNull(ShadowToast.getTextOfLatestToast());
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
            assertTrue(page.findPreference(IMPORT_ROW).isEnabled());
        }
    }

    /**
     * The picker is an activity of its own, and Android may rebuild Facebook's behind it, the
     * settings dialog and its page with it. The answer still reaches the rebuilt page: Android
     * finds it by the name the framework gave the page that asked, which the rebuilt one keeps.
     */
    @Test
    public void thePickersAnswerReachesThePageRebuiltBehindIt() throws Exception {
        String file = fileWith(Settings.DOWNLOAD_REELS, false);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment asked = SettingsL10nTest.pageOf(SettingsL10nTest.show(controller.get()));
            ShadowActivity.IntentForResult started = tap(controller.get(), asked, IMPORT_ROW);

            controller.recreate();
            ShadowLooper.idleMainLooper();
            Activity rebuilt = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(dialogOf(rebuilt));
            assertNotSame("the page wasn't rebuilt", asked, page);

            deliver(rebuilt, started, file);
            AlertDialog preview = shownPreview();
            assertEquals("1 switch will change.", String.valueOf(shadowOf(preview).getMessage()));
            preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();
            assertFalse(Settings.DOWNLOAD_REELS.savedValue());
            assertEquals("Settings imported. 1 switch changed.", ShadowToast.getTextOfLatestToast());
        }
    }

    @Test
    public void anExportAnswerReachesThePageRebuiltBehindIt() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment asked = SettingsL10nTest.pageOf(SettingsL10nTest.show(controller.get()));
            ShadowActivity.IntentForResult started = tap(controller.get(), asked, EXPORT_ROW);
            controller.recreate();
            ShadowLooper.idleMainLooper();

            Uri uri = SettingsFileProvider.put(AUTHORITY, "rebuilt-export.json", new byte[0]);
            shadowOf(controller.get()).receiveResult(started.intent, Activity.RESULT_OK, new Intent().setData(uri));
            settle();
            assertEquals("Settings exported.", ShadowToast.getTextOfLatestToast());
            assertEquals(SettingsBackup.create(), new String(SettingsFileProvider.get(uri), StandardCharsets.UTF_8));
        }
    }

    /**
     * A preview on screen when the activity is rebuilt comes back on the rebuilt page, still
     * unanswered, and the old one goes with the old window.
     */
    @Test
    public void thePreviewComesBackWhenThePageIsRebuilt() throws Exception {
        String file = fileWith(Settings.HIDE_SUGGESTED_POSTS, false, Settings.HIDE_PROMOTED_POSTS, false);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(controller.get()));
            deliver(controller.get(), tap(controller.get(), page, IMPORT_ROW), file);
            AlertDialog first = shownPreview();
            String message = String.valueOf(shadowOf(first).getMessage());
            assertEquals("2 switches will change.", message);

            controller.recreate();
            ShadowLooper.idleMainLooper();
            assertFalse("the old preview stayed up over a window that's gone", first.isShowing());
            AlertDialog again = shownPreview();
            assertNotSame(first, again);
            assertEquals(message, String.valueOf(shadowOf(again).getMessage()));
            assertTrue("the rebuild answered the preview", Settings.HIDE_SUGGESTED_POSTS.savedValue());

            again.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();
            assertFalse(Settings.HIDE_SUGGESTED_POSTS.savedValue());
            assertFalse(Settings.HIDE_PROMOTED_POSTS.savedValue());
            assertNull(SettingsL10nTest.pageOf(dialogOf(controller.get())).pendingImport);
        }
    }

    /**
     * Malformed, oversized, duplicate-name, wrong-type, newer-version and word-lists-past-their-room
     * files each say why and change nothing: no preview, no write, the switches as they were.
     */
    @Test
    public void aRefusedFileSaysWhyAndChangesNothing() throws Exception {
        String good = SettingsBackup.create();
        String key = Settings.HIDE_SPONSORED_POSTS.key;
        JSONObject wrongType = new JSONObject(good);
        wrongType.getJSONObject("settings").put(key, "false");
        Map<String, SettingsBackup.Reason> files = new LinkedHashMap<>();
        files.put(good.substring(0, good.length() - 3), SettingsBackup.Reason.DAMAGED);
        files.put(good + repeat(' ', SettingsBackup.MAX_BYTES), SettingsBackup.Reason.SIZE);
        files.put(good.replace("\"" + key + "\": true", "\"" + key + "\": false, \"" + key + "\": false"),
                SettingsBackup.Reason.DUPLICATE);
        files.put(wrongType.toString(), SettingsBackup.Reason.VALUE);
        files.put(new JSONObject(good).put("schema", 2).toString(), SettingsBackup.Reason.SCHEMA);
        files.put(new JSONObject(good).put("format", "something else").toString(), SettingsBackup.Reason.FORMAT);
        JSONObject pastTheRoom = new JSONObject(good);
        pastTheRoom.getJSONObject("settings")
                .put(SettingsBackup.HIDDEN.key, PostWordsTest.asciiOfBytes(PostWords.MAX_LIST_BYTES / 2))
                .put(SettingsBackup.KEPT.key, PostWordsTest.asciiOfBytes(PostWords.MAX_LIST_BYTES / 2 + 1));
        files.put(pastTheRoom.toString(), SettingsBackup.Reason.WORDS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            Map<String, ?> before = store();
            for (Map.Entry<String, SettingsBackup.Reason> entry : files.entrySet()) {
                ShadowToast.reset();
                ShadowAlertDialog.reset();
                Counts counts = new Counts();
                withStore(counting(Setting.preferences.preferences, counts, () -> false, false), () -> {
                    deliver(activity, tap(activity, page, IMPORT_ROW), entry.getKey());
                    return null;
                });
                assertEquals(entry.getValue() + " said", SettingsBackupPreference.refusal(entry.getValue()),
                        ShadowToast.getTextOfLatestToast());
                assertNull(entry.getValue() + " showed a preview", ShadowAlertDialog.getLatestAlertDialog());
                assertNull(page.pendingImport);
                assertEquals(entry.getValue() + " opened an editor", 0, counts.edits.get());
                assertEquals(entry.getValue() + " changed the store", before, store());
                assertTrue(page.findPreference(IMPORT_ROW).isEnabled());
            }
            assertTrue(Settings.HIDE_SPONSORED_POSTS.savedValue());
        }
    }

    /**
     * While a file is read, both rows are out of reach and the one acting says what it's doing,
     * which is also what a screen reader hears as its state. A second picker can't be opened.
     */
    @Test
    public void theRowsAreOutOfReachWhileAFileIsRead() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            Preference export = page.findPreference(EXPORT_ROW);
            Preference importRow = page.findPreference(IMPORT_ROW);
            String exportSummary = String.valueOf(export.getSummary());
            String importSummary = String.valueOf(importRow.getSummary());

            CountDownLatch release = new CountDownLatch(1);
            SettingsFileProvider.stall = release;
            Uri uri = SettingsFileProvider.put(AUTHORITY, "slow.json", SettingsBackup.create().getBytes(StandardCharsets.UTF_8));
            ShadowActivity.IntentForResult started = tap(activity, page, IMPORT_ROW);
            shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, new Intent().setData(uri));
            try {
                assertFalse(export.isEnabled());
                assertFalse(importRow.isEnabled());
                assertEquals("Reading the settings file", String.valueOf(importRow.getSummary()));
                assertEquals("a row that isn't acting changed its summary", exportSummary, String.valueOf(export.getSummary()));

                click(export);
                assertNull("a tap during a read opened a picker", shadowOf(activity).getNextStartedActivityForResult());
            } finally {
                release.countDown();
            }
            settle();
            assertTrue(export.isEnabled());
            assertTrue(importRow.isEnabled());
            assertEquals(importSummary, String.valueOf(importRow.getSummary()));
            shownPreview().cancel();
            settle();
        }
    }

    /**
     * A full worker queue runs nothing, says so, and leaves the rows usable for another try.
     *
     * <p>The pool is one static executor for every test in the JVM, and a worker left finishing
     * something else when the fill stops hands its slot to the read (the full suite saw that on
     * 2026-09-26, with the pool drained first). WorkerPoolForTests holds it full until every worker
     * holds a filler.
     */
    @Test
    public void aFullWorkerQueueLeavesTheRowsUsable() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            byte[] bytes = fileWith(Settings.HIDE_SPONSORED_POSTS, false).getBytes(StandardCharsets.UTF_8);
            Uri uri = SettingsFileProvider.put(AUTHORITY, "queued.json", bytes);
            ShadowActivity.IntentForResult started = tap(activity, page, IMPORT_ROW);

            try (WorkerPoolForTests full = WorkerPoolForTests.fill()) {
                shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, new Intent().setData(uri));
                ShadowLooper.idleMainLooper();
                assertEquals("Couldn't start that. Try again in a moment.", ShadowToast.getTextOfLatestToast());
                assertEquals("the file was read anyway", 0, SettingsFileProvider.opened.get());
                assertTrue(page.findPreference(IMPORT_ROW).isEnabled());
                assertTrue(page.findPreference(EXPORT_ROW).isEnabled());
            }
            settle();
            // And it works once the queue has room.
            deliver(activity, tap(activity, page, IMPORT_ROW), fileWith(Settings.HIDE_SPONSORED_POSTS, false));
            shownPreview().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();
            assertFalse(Settings.HIDE_SPONSORED_POSTS.savedValue());
        }
    }

    /**
     * An import that fails after it has claimed the rows, before a worker has it, gives them back
     * and changes nothing. That path has no wait to run out, so nothing else would: the rows would
     * stay out of reach until Facebook restarts. Here the folder the toast names can't be read once
     * Import is pressed, so the toast's text can't be built.
     */
    @Test
    public void anImportThatFailsBeforeItsWorkerStartsGivesTheRowsBack() throws Exception {
        JSONObject file = new JSONObject(fileWith(Settings.HIDE_SPONSORED_POSTS, false));
        file.getJSONObject("settings").put(SettingsBackup.FOLDER.key, "Clips");
        Field value = Setting.class.getDeclaredField("value");
        value.setAccessible(true);
        Object folder = value.get(Settings.SAVE_FOLDER);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            deliver(activity, tap(activity, page, IMPORT_ROW), file.toString());
            AlertDialog preview = shownPreview();
            RuntimeException escaped = null;
            // Not a folder name, so no sentence can be worded around it.
            value.set(Settings.SAVE_FOLDER, 7);
            try {
                preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                settle();
            } catch (RuntimeException failure) {
                escaped = failure;
            } finally {
                value.set(Settings.SAVE_FOLDER, folder);
            }
            assertTrue("the rows stayed out of reach", page.findPreference(IMPORT_ROW).isEnabled());
            assertTrue(page.findPreference(EXPORT_ROW).isEnabled());
            assertFalse(AbstractPreferenceFragment.settingImportInProgress);
            assertNull("the failure reached Facebook", escaped);
            assertEquals("Couldn't start that. Try again in a moment.", ShadowToast.getTextOfLatestToast());
            assertTrue("a switch changed", Settings.HIDE_SPONSORED_POSTS.savedValue());

            // And the next import goes through.
            deliver(activity, tap(activity, page, IMPORT_ROW), fileWith(Settings.HIDE_SPONSORED_POSTS, false));
            shownPreview().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();
            assertFalse(Settings.HIDE_SPONSORED_POSTS.savedValue());
        }
    }

    // ---- The app holding the file ------------------------------------------------------------

    private static final String EXPORT_TIMEOUT = "The app holding the settings file is taking too long, so Hushfacebook "
            + "stopped waiting. That app may still finish saving it, so check the file before you rely on it.";
    private static final String IMPORT_TIMEOUT =
            "The app holding that file is taking too long, so Hushfacebook stopped waiting. Nothing was changed.";
    private static final String STALLED = "The app holding the last settings file still hasn't answered. Try again later.";
    private static final String MISMATCH =
            "The settings file was saved, but it doesn't match what Hushfacebook wrote. Save it again as a new "
                    + "file.";
    private static final String UNCHECKED = "Settings exported. Hushfacebook couldn't read the file back to check it.";

    /**
     * An app that turns "wt" down gets "w", which here keeps the old file's longer tail. The file is
     * read back and the export says it doesn't read as what was written, rather than claiming
     * success. An app that truncates gets the plain answer.
     */
    @Test
    public void anExportThatKeepsOldBytesIsReadBackAndSaysSo() throws Exception {
        SettingsFileProvider.refusesTruncate = true;
        String now = SettingsBackup.create();
        Uri uri = SettingsFileProvider.put(AUTHORITY, "old.json",
                (now + repeat(' ', 64) + "{\"old\":true}").getBytes(StandardCharsets.UTF_8));
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            export(activity, page, uri);
            assertEquals(MISMATCH, ShadowToast.getTextOfLatestToast());
            String kept = new String(SettingsFileProvider.get(uri), StandardCharsets.UTF_8);
            assertTrue("the app truncated after all", kept.startsWith(now) && kept.endsWith("{\"old\":true}"));
            assertEquals(SettingsBackup.Reason.DAMAGED, reasonFor(kept));
            assertTrue(page.findPreference(EXPORT_ROW).isEnabled());

            SettingsFileProvider.refusesTruncate = false;
            export(activity, page, uri);
            assertEquals("Settings exported.", ShadowToast.getTextOfLatestToast());
            assertEquals(now, new String(SettingsFileProvider.get(uri), StandardCharsets.UTF_8));
        }
    }

    /** An app that won't open the file for reading leaves the export unchecked, and the export says so. */
    @Test
    public void anExportTheAppWontReadBackSaysItWasNotChecked() throws Exception {
        SettingsFileProvider.readFailure = new SecurityException("no read grant");
        Uri uri = SettingsFileProvider.put(AUTHORITY, "write-only.json", new byte[0]);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            export(activity, page, uri);
            assertEquals(UNCHECKED, ShadowToast.getTextOfLatestToast());
            assertEquals(SettingsBackup.create(), new String(SettingsFileProvider.get(uri), StandardCharsets.UTF_8));
            assertTrue(page.findPreference(EXPORT_ROW).isEnabled());
        }
    }

    /** A runtime failure partway through a read is refused like an IOException, with a message and the rows back. */
    @Test
    public void aRuntimeFailureWhileReadingIsUnreadable() throws Exception {
        InputStream failing = new InputStream() {
            private int given;

            @Override public int read() {
                if (given++ < 3) return '{';
                throw new IllegalStateException("the app went away");
            }
        };
        assertEquals(SettingsBackup.Reason.UNREADABLE, readReason(failing));

        SettingsFileProvider.readFailure = new IllegalStateException("the app went away");
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            ShadowAlertDialog.reset();
            deliver(activity, tap(activity, page, IMPORT_ROW), SettingsBackup.create());
            assertEquals(SettingsBackupPreference.refusal(SettingsBackup.Reason.UNREADABLE), ShadowToast.getTextOfLatestToast());
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
            assertTrue(page.findPreference(IMPORT_ROW).isEnabled());
            assertTrue(page.findPreference(EXPORT_ROW).isEnabled());
        }
    }

    /**
     * An app that never answers: after the wait the rows come back, the cancel reaches the app and
     * the message says only that Hushfacebook stopped waiting. While the app still holds a worker
     * nothing new starts, and its late answer shows no preview and says nothing.
     */
    @Test
    public void aStalledAppGivesTheRowsBackAndItsLateAnswerChangesNothing() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        SettingsFileProvider.stall = release;
        SettingsFileProvider.ignoresCancel = true;
        Uri uri = SettingsFileProvider.put(AUTHORITY, "stalled.json",
                fileWith(Settings.HIDE_SPONSORED_POSTS, false).getBytes(StandardCharsets.UTF_8));
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            ShadowAlertDialog.reset();
            Preference importRow = page.findPreference(IMPORT_ROW);
            try {
                shadowOf(activity).receiveResult(tap(activity, page, IMPORT_ROW).intent, Activity.RESULT_OK,
                        new Intent().setData(uri));
                ShadowLooper.idleMainLooper();
                assertFalse(importRow.isEnabled());
                awaitHeldOpen();
                waitOut();
                assertEquals(IMPORT_TIMEOUT, ShadowToast.getTextOfLatestToast());
                assertTrue(importRow.isEnabled());
                assertTrue(page.findPreference(EXPORT_ROW).isEnabled());
                assertEquals("the cancel never reached the app", 1, SettingsFileProvider.cancels.get());

                ShadowToast.reset();
                click(page.findPreference(EXPORT_ROW));
                assertNull("a picker opened while a stuck app held a worker",
                        shadowOf(activity).getNextStartedActivityForResult());
                assertEquals(STALLED, ShadowToast.getTextOfLatestToast());
            } finally {
                release.countDown();
            }
            settle();
            assertNull("the late answer showed a preview", ShadowAlertDialog.getLatestAlertDialog());
            assertNull(page.pendingImport);
            assertEquals("the late answer said something", STALLED, ShadowToast.getTextOfLatestToast());
            assertTrue(Settings.HIDE_SPONSORED_POSTS.savedValue());

            // Once the app has answered, a new read starts.
            deliver(activity, tap(activity, page, IMPORT_ROW), fileWith(Settings.HIDE_SPONSORED_POSTS, false));
            shownPreview().cancel();
            settle();
        }
    }

    /**
     * A file turned down at the moment the wait runs out: the worker returned first, so the screen
     * says why the file was turned down rather than that the app took too long, and the rows come
     * back. The answer used to lose to the wait, which then dropped it.
     */
    @Test
    public void aRefusalThatLandsAsTheWaitRunsOutIsTheOneSaid() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        SettingsFileProvider.stall = release;
        Uri uri = SettingsFileProvider.put(AUTHORITY, "notes.json", "just some text".getBytes(StandardCharsets.UTF_8));
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            ShadowAlertDialog.reset();
            try {
                shadowOf(activity).receiveResult(tap(activity, page, IMPORT_ROW).intent, Activity.RESULT_OK,
                        new Intent().setData(uri));
                ShadowLooper.idleMainLooper();
                awaitHeldOpen();
                // The wait is due, but the main thread hasn't got to it when the worker returns.
                ShadowSystemClock.advanceBy(Duration.ofMillis(SettingsBackupPreference.timeoutMs + 1));
            } finally {
                release.countDown();
            }
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();

            assertEquals(SettingsBackupPreference.refusal(SettingsBackup.Reason.DAMAGED), ShadowToast.getTextOfLatestToast());
            assertEquals("the cancel went out after the worker returned", 0, SettingsFileProvider.cancels.get());
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
            assertTrue(page.findPreference(IMPORT_ROW).isEnabled());
            assertTrue(page.findPreference(EXPORT_ROW).isEnabled());
            // Nothing is left holding a worker, so the next pick opens the picker.
            tap(activity, page, IMPORT_ROW);
        }
    }

    /**
     * An export whose app honours the cancel ends when the screen stops waiting, so the next one
     * starts at once. One whose app ignores it keeps a second export off the file until it has
     * answered, and its late answer is neither written to nor called a success.
     */
    @Test
    public void aStalledExportIsCancelledOrKeepsASecondWriteOffItsFile() throws Exception {
        Uri uri = SettingsFileProvider.put(AUTHORITY, "busy.json", new byte[0]);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            SettingsFileProvider.stall = new CountDownLatch(1);
            shadowOf(activity).receiveResult(tap(activity, page, EXPORT_ROW).intent, Activity.RESULT_OK,
                    new Intent().setData(uri));
            ShadowLooper.idleMainLooper();
            awaitHeldOpen();
            waitOut();
            assertEquals(EXPORT_TIMEOUT, ShadowToast.getTextOfLatestToast());
            assertEquals(1, SettingsFileProvider.cancels.get());
            settle();
            assertEquals("the cancelled export said more", EXPORT_TIMEOUT, ShadowToast.getTextOfLatestToast());
            assertEquals(0, SettingsFileProvider.get(uri).length);

            CountDownLatch release = new CountDownLatch(1);
            SettingsFileProvider.stall = release;
            SettingsFileProvider.ignoresCancel = true;
            try {
                shadowOf(activity).receiveResult(tap(activity, page, EXPORT_ROW).intent, Activity.RESULT_OK,
                        new Intent().setData(uri));
                ShadowLooper.idleMainLooper();
                awaitHeldOpen();
                waitOut();
                assertEquals(EXPORT_TIMEOUT, ShadowToast.getTextOfLatestToast());
                ShadowToast.reset();
                click(page.findPreference(EXPORT_ROW));
                assertNull("a second write could reach the file", shadowOf(activity).getNextStartedActivityForResult());
                assertEquals(STALLED, ShadowToast.getTextOfLatestToast());
            } finally {
                release.countDown();
            }
            settle();
            // Handed over after the cancel, the file is closed unwritten, and nothing is said.
            assertEquals(0, SettingsFileProvider.get(uri).length);
            assertEquals("the late answer was called a success", STALLED, ShadowToast.getTextOfLatestToast());

            SettingsFileProvider.stall = null;
            export(activity, page, uri);
            assertEquals("Settings exported.", ShadowToast.getTextOfLatestToast());
        }
    }

    /**
     * A test that stops while the app still holds its file, as a failed assertion does, leaves the
     * next test nothing busy. The cleanup lets the open go, so the run ends and gives the rows back
     * the way it does in the app. A held open used to outlast the cleanup's wait for background
     * work, which skipped the rest of the cleanup and kept the rows claimed for every later test.
     */
    @Test
    public void aTestThatStopsWhileTheAppHoldsItsFileLeavesTheNextOneFree() throws Exception {
        SettingsFileProvider.stall = new CountDownLatch(1);
        Uri held = SettingsFileProvider.put(AUTHORITY, "held.json", SettingsBackup.create().getBytes(StandardCharsets.UTF_8));
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            shadowOf(activity).receiveResult(tap(activity, page, IMPORT_ROW).intent, Activity.RESULT_OK,
                    new Intent().setData(held));
            assertFalse(page.findPreference(EXPORT_ROW).isEnabled());
            awaitHeldOpen();
        }
        // What runs after a failed test and before the next one.
        restore();
        startClean();
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            assertTrue("the next test started with the rows busy", page.findPreference(EXPORT_ROW).isEnabled());
            export(activity, page, SettingsFileProvider.put(AUTHORITY, "next.json", new byte[0]));
            assertEquals("Settings exported.", ShadowToast.getTextOfLatestToast());
        }
    }

    // ---- Helpers -------------------------------------------------------------------------------

    /** Taps a row and hands back the picker it opened. */
    private static ShadowActivity.IntentForResult tap(Activity activity, HushfacebookPreferenceFragment page, String row) {
        click(page.findPreference(row));
        ShadowActivity.IntentForResult started = shadowOf(activity).getNextStartedActivityForResult();
        assertNotNull(row + " opened no picker", started);
        return started;
    }

    private static void click(Preference row) {
        assertNotNull("no such row", row);
        row.getOnPreferenceClickListener().onPreferenceClick(row);
    }

    /** Exports through the picker to [uri], and waits for what that sets off. */
    private static void export(Activity activity, HushfacebookPreferenceFragment page, Uri uri) throws Exception {
        shadowOf(activity).receiveResult(tap(activity, page, EXPORT_ROW).intent, Activity.RESULT_OK, new Intent().setData(uri));
        settle();
    }

    /**
     * Lets the workers [held] go, waits for the export among them to finish, and answers the file a
     * worker reading the settings at this moment writes, which is how exports read them before.
     */
    private static String letTheExportRunAndRead(WorkerPoolForTests held) {
        try {
            held.close();
            return Utils.submitOnBackgroundThread(SettingsBackup::create).get(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError("the export's worker didn't finish inside the change", failure);
        }
    }

    /** How many of [switches] a file has on. */
    private static int switchesOn(String file, List<BooleanSetting> switches) throws Exception {
        JSONObject carried = new JSONObject(file).getJSONObject("settings");
        int on = 0;
        for (BooleanSetting setting : switches) {
            if (carried.getBoolean(setting.key)) on++;
        }
        return on;
    }

    /** Lets the screen's wait for the file's app run out. */
    private static void waitOut() {
        ShadowLooper.idleMainLooper(SettingsBackupPreference.timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Waits until the app holds the open with its cancel listener in place, as it has long before
     * 30 seconds run out on a phone. waitOut moves only the main thread's clock, so without this the
     * worker may not have reached the app when the cancel goes out, and the app never counts it.
     */
    private static void awaitHeldOpen() throws InterruptedException {
        assertTrue("the app never got the open", SettingsFileProvider.holding.tryAcquire(5, TimeUnit.SECONDS));
    }

    /** Answers a picker with a file holding [text], and waits for what that sets off. */
    private static void deliver(Activity activity, ShadowActivity.IntentForResult started, String text) throws Exception {
        Uri uri = SettingsFileProvider.put(AUTHORITY, System.nanoTime() + ".json", text.getBytes(StandardCharsets.UTF_8));
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, new Intent().setData(uri));
        settle();
    }

    private static AlertDialog shownPreview() {
        AlertDialog preview = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("no preview", preview);
        assertTrue("the preview isn't showing", preview.isShowing());
        return preview;
    }

    private static SettingsDialog dialogOf(Activity activity) {
        SettingsDialog dialog = (SettingsDialog) activity.getFragmentManager().findFragmentByTag("hushfacebook_settings");
        assertNotNull("the settings didn't come back", dialog);
        return dialog;
    }

    private static void settle() throws Exception {
        for (int round = 0; round < 3; round++) {
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
        }
    }

    private static SettingsBackup.Reason reasonFor(String text) {
        try {
            SettingsBackup.parse(text);
        } catch (SettingsBackup.Rejected refused) {
            return refused.reason;
        }
        throw new AssertionError("accepted: " + printable(text));
    }

    private static SettingsBackup.Reason readReason(InputStream input) {
        try {
            SettingsBackup.read(input);
        } catch (SettingsBackup.Rejected refused) {
            return refused.reason;
        }
        throw new AssertionError("read without a refusal");
    }

    /** A file naming only these switches, as [setting, value] pairs. */
    private static String fileWith(Object... pairs) throws Exception {
        JSONObject switches = new JSONObject();
        for (int index = 0; index < pairs.length; index += 2) {
            switches.put(((BooleanSetting) pairs[index]).key, pairs[index + 1]);
        }
        return new JSONObject().put("format", SettingsBackup.FORMAT).put("schema", SettingsBackup.SCHEMA)
                .put("settings", switches).toString();
    }

    private static Map<String, ?> store() {
        return new HashMap<>(Setting.preferences.preferences.getAll());
    }

    /** Applies [file] through a store with [faults], and hands back the failure it has to end in. */
    private static SettingsBackup.ApplyFailed failedApply(String file, FailingStore.Fault... faults) throws Exception {
        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
        try (FailingStore ignored = FailingStore.install(faults)) {
            SettingsBackup.apply(snapshot);
        } catch (SettingsBackup.ApplyFailed failed) {
            return failed;
        }
        throw new AssertionError("a failed write was reported as written: " + Arrays.toString(faults));
    }

    private static Set<String> keys(List<? extends Setting<?>> settings) {
        Set<String> keys = new TreeSet<>();
        for (Setting<?> setting : settings) keys.add(setting.key);
        return keys;
    }

    private static Set<String> names(JSONObject object) {
        Set<String> names = new TreeSet<>();
        for (Iterator<String> iterator = object.keys(); iterator.hasNext(); ) names.add(iterator.next());
        return names;
    }

    private static List<Setting<?>> declaredSettings(Class<?> owner) throws IllegalAccessException {
        List<Setting<?>> found = new ArrayList<>();
        for (Field field : owner.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && Setting.class.isAssignableFrom(field.getType())) {
                found.add((Setting<?>) field.get(null));
            }
        }
        return found;
    }

    private static String repeat(char character, int count) {
        char[] run = new char[count];
        Arrays.fill(run, character);
        return new String(run);
    }

    private static String printable(String text) {
        String shown = text.length() > 80 ? text.substring(0, 80) + "..." : text;
        return shown.replace("\0", "\\0");
    }

    private static final class Counts {
        final AtomicInteger edits = new AtomicInteger();
        final AtomicInteger commits = new AtomicInteger();
        final AtomicInteger applies = new AtomicInteger();
    }

    private interface Body<T> {
        T run() throws Exception;
    }

    /** Runs [body] with Setting's store swapped for [store], and puts the real one back after. */
    private static <T> T withStore(SharedPreferences store, Body<T> body) throws Exception {
        Field field = SharedPrefCategory.class.getDeclaredField("preferences");
        field.setAccessible(true);
        Object original = field.get(Setting.preferences);
        field.set(Setting.preferences, store);
        try {
            return body.run();
        } finally {
            field.set(Setting.preferences, original);
        }
    }

    /**
     * [target], counting editors and their commits. While [fail] answers true, a commit reports
     * failure: after landing, or, with [landThenLose], the first lands and the rest never do.
     */
    private static SharedPreferences counting(SharedPreferences target, Counts counts, BooleanSupplier fail,
                                              boolean landThenLose) {
        return (SharedPreferences) Proxy.newProxyInstance(target.getClass().getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("edit")) return method.invoke(target, args);
                    counts.edits.incrementAndGet();
                    SharedPreferences.Editor editor = target.edit();
                    return Proxy.newProxyInstance(editor.getClass().getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class}, (editorProxy, call, values) -> {
                                if (call.getName().equals("apply")) {
                                    counts.applies.incrementAndGet();
                                    return call.invoke(editor, values);
                                }
                                if (call.getName().equals("commit")) {
                                    int commit = counts.commits.incrementAndGet();
                                    if (landThenLose && commit > 1 && fail.getAsBoolean()) return false;
                                    Object landed = call.invoke(editor, values);
                                    return !fail.getAsBoolean() && (Boolean) landed;
                                }
                                Object result = call.invoke(editor, values);
                                return result instanceof SharedPreferences.Editor ? editorProxy : result;
                            });
                });
    }

    private static SharedPreferences blockingFirstCommit(SharedPreferences target, CountDownLatch entered,
                                                          CountDownLatch proceed) {
        AtomicInteger commits = new AtomicInteger();
        return (SharedPreferences) Proxy.newProxyInstance(target.getClass().getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("edit")) return method.invoke(target, args);
                    SharedPreferences.Editor editor = target.edit();
                    return Proxy.newProxyInstance(editor.getClass().getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class}, (editorProxy, call, values) -> {
                                if (call.getName().equals("commit") && commits.incrementAndGet() == 1) {
                                    entered.countDown();
                                    assertTrue("the test did not release the import", proceed.await(5, TimeUnit.SECONDS));
                                }
                                Object result = call.invoke(editor, values);
                                return result instanceof SharedPreferences.Editor ? editorProxy : result;
                            });
                });
    }
}
