/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import android.widget.TextView;

import app.morphe.extension.facebook.comments.CommentOrder;
import app.morphe.extension.facebook.download.DownloadQuality;
import app.morphe.extension.facebook.download.SaveTo;
import app.morphe.extension.facebook.download.SendLink;
import app.morphe.extension.facebook.feed.PostWordsForTests;
import app.morphe.extension.facebook.feed.SeenPostsForTests;
import app.morphe.extension.facebook.media.PlaybackQuality;
import app.morphe.extension.facebook.media.SurfaceQuality;
import app.morphe.extension.facebook.navigation.FeedsSubtab;
import app.morphe.extension.facebook.navigation.HiddenTabs;
import app.morphe.extension.facebook.navigation.StartTab;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The settings screen as it draws inside Facebook: a black page whose rows must be readable.
 *
 * <p>On a phone on 2026-09-24 every row title was near-black on black, because the rows took
 * Facebook's light activity theme, and the two diagnostics rows had no text at all.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class HushfacebookPreferenceFragmentTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Wording that tells the reader a paused Facebook is an unpatched one, which it isn't. */
    private static final Pattern UNPATCHED = Pattern.compile("(?i)unpatched|n't patched|not patched|as if it weren");

    /** The count of hidden posts is the process's, and other test classes in this JVM hide posts too. */
    @Before
    public void forgetHiddenPosts() {
        PostWordsForTests.forget();
    }

    @After
    public void restore() {
        PostWordsForTests.forget();
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.resetToDefault();
        Settings.SAVE_FOLDER.resetToDefault();
        Settings.SAVE_TO.resetToDefault();
        Settings.VIDEO_SUBFOLDER.resetToDefault();
        Settings.PHOTO_SUBFOLDER.resetToDefault();
        Settings.DOWNLOAD_QUALITY.resetToDefault();
        Settings.FILENAME_TEMPLATE.resetToDefault();
        Settings.PHOTO_FILENAME_TEMPLATE.resetToDefault();
        Settings.DOWNLOAD_ACTION.resetToDefault();
        Settings.SEND_TO_APP.resetToDefault();
        Settings.HIDDEN_WORDS.resetToDefault();
        Settings.KEPT_WORDS.resetToDefault();
    }

    @Test
    public void everyRowHasATitleAndLightText() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            assertReadable(rowsOf(controller));
        }
    }

    /**
     * A test JVM has no patched status flags, so the test above sees only the rows every build
     * has. This one draws the screen with every patch in.
     */
    @Test
    public void withEveryPatchInEveryRowIsReadableAndNoneCallsAPausedFacebookUnpatched() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(controller);
            assertReadable(rows);

            Set<String> switchKeys = new HashSet<>();
            for (PatchFamily family : PatchFamily.values()) {
                for (BooleanSetting setting : family.switches) switchKeys.add(setting.key);
            }
            // The settings entry's own switches are Pause's to turn off too, and so are the ones
            // the downloads share.
            for (BooleanSetting setting : PatchFamily.ENTRY_SWITCHES) switchKeys.add(setting.key);
            for (BooleanSetting setting : PatchFamily.DOWNLOAD_SWITCHES) switchKeys.add(setting.key);
            Set<String> shown = new HashSet<>();
            Preference stays = null;
            for (Preference row : rows) {
                String text = row.getTitle() + " " + row.getSummary();
                assertFalse("\"" + text + "\" says a paused Facebook is unpatched", UNPATCHED.matcher(text).find());
                if (row instanceof SwitchPreference && switchKeys.contains(row.getKey())) shown.add(row.getKey());
                if (HushfacebookPreferenceFragment.STAYS_WHILE_PAUSED.contentEquals(row.getTitle())) stays = row;
            }
            assertEquals("a switch Pause turns off is missing from the screen", switchKeys, shown);
            assertNotNull("nothing on the screen says what Pause can't reach", stays);
            assertEquals(PatchFamily.staysWhilePausedSummary(EnumSet.allOf(PatchFamily.class)),
                    String.valueOf(stays.getSummary()));

            // Pause covers runtime features and explicitly keeps debugging available.
            int pause = indexOfKey(rows, BaseSettings.PAUSED.key);
            assertTrue("the Pause row is missing", pause >= 0);
            for (String key : switchKeys) {
                assertTrue(key + " is drawn below the Pause row", indexOfKey(rows, key) < pause);
            }
            assertTrue("Debug logging is drawn above the Pause row", indexOfKey(rows, BaseSettings.DEBUG.key) > pause);
            assertTrue(String.valueOf(rows.get(pause).getSummary()),
                    String.valueOf(rows.get(pause).getSummary()).contains("switches act as if off, except "
                            + "Debug logging and Lock Facebook. Your choices stay saved."));
        }
    }

    /**
     * Facebook's chime can be saved from the Notifications section in every build, since a
     * category Android set to None needs no patch to fix (#83). The row acts at once and says how
     * it went in a toast, and with Block promotional notifications in it sits under that patch's
     * rows.
     */
    @Test
    public void theNotificationSoundRowSavesTheChimeInEveryBuild() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.POST_WORDS);
        app.morphe.extension.facebook.notifications.NotificationSound.sourceForTests = () -> null;
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(controller);
            int at = indexOfKey(rows, AppPages.SAVE_NOTIFICATION_SOUND);
            assertTrue("no sound row without the notifications patch", at >= 0);
            Preference sound = rows.get(at);
            assertEquals("Save Facebook's notification sound", String.valueOf(sound.getTitle()));
            assertTrue("the row's tap acts at once, so it goes without a chevron", ((SettingsRows.Row) sound).actsOnTap());
            assertTrue(String.valueOf(sound.getSummary()).contains("for categories set to None"));
            assertEquals(-1, indexOfKey(rows, Settings.BLOCK_ACCOUNT_SETUP_NOTIFICATIONS.key));

            ShadowToast.reset();
            sound.getOnPreferenceClickListener().onPreferenceClick(sound);
            app.morphe.extension.shared.Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertEquals("This build has no notification sound to save.", ShadowToast.getTextOfLatestToast());
        } finally {
            app.morphe.extension.facebook.notifications.NotificationSound.sourceForTests = null;
        }

        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PROMO_NOTIFICATIONS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(controller);
            int sound = indexOfKey(rows, AppPages.SAVE_NOTIFICATION_SOUND);
            int reminders = indexOfKey(rows, Settings.BLOCK_ACCOUNT_SETUP_NOTIFICATIONS.key);
            assertTrue("the sound row sits under the notification switches", reminders >= 0 && sound > reminders);
        }
    }

    /**
     * With Material You theme in the build the Accent color row stays greyed out, even after the
     * shared page enables every row it syncs from its setting.
     */
    @Test
    public void theAccentRowStaysGreyedOutWhenMaterialYouIsInTheBuild() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.ACCENT_COLOR, PatchFamily.MATERIAL_YOU_THEME);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(controller);
            int at = indexOfKey(rows, Settings.ACCENT_COLOR.key);
            assertTrue("no Accent color row with its patch in the build", at >= 0);
            assertFalse("the row still takes taps", rows.get(at).isEnabled());
        }

        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.ACCENT_COLOR);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(controller);
            assertTrue(rows.get(indexOfKey(rows, Settings.ACCENT_COLOR.key)).isEnabled());
        }
    }

    private static int indexOfKey(List<Preference> rows, String key) {
        for (int i = 0; i < rows.size(); i++) {
            if (key.equals(rows.get(i).getKey())) return i;
        }
        return -1;
    }

    /**
     * The tray adapter is built at every start and counts no rows while the switch is on, and the
     * feed reads that count on a pull to refresh, so a change shows then (a Galaxy S25 check on
     * 2026-10-05, both ways). The row says so, after what the tray is.
     */
    @Test
    public void theStoriesTrayRowSaysAChangeShowsOnTheNextRefresh() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.STORIES_TRAY);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(controller);
            int tray = indexOfKey(rows, Settings.HIDE_TOP_STORIES_TRAY.key);
            assertTrue("the Stories tray row is missing", tray >= 0);
            assertEquals("Hides the row of stories at the top of the feed, including Create story. "
                    + "A change shows the next time you pull down to refresh.",
                    String.valueOf(rows.get(tray).getSummary()));
            int between = indexOfKey(rows, Settings.HIDE_STORIES_BETWEEN_POSTS.key);
            assertEquals("the between-post Stories switch isn't immediately after the tray switch", tray + 1, between);
            assertEquals("Hide Stories between posts", String.valueOf(rows.get(between).getTitle()));
            assertEquals("Hides Stories shown between posts, from the next time the feed loads. The Stories tray at the top "
                    + "has its own switch.",
                    String.valueOf(rows.get(between).getSummary()));
        }
    }

    /**
     * The sponsored and AI reel filters take ads and flagged reels out of each batch of reels as it
     * arrives, so reels already loaded stay as they were until the next batch. Both rows say when a
     * change starts, right after what the switch hides.
     */
    @Test
    public void theReelFilterRowsSayAChangeStartsWithTheNextBatch() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.SPONSORED_REELS, PatchFamily.AI_DETECTED_POSTS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(controller);
            int sponsored = indexOfKey(rows, Settings.HIDE_SPONSORED_REELS.key);
            int ai = indexOfKey(rows, Settings.HIDE_AI_DETECTED_REELS.key);
            assertTrue("the sponsored reels row is missing", sponsored >= 0);
            assertTrue("the AI reels row is missing", ai >= 0);
            assertTrue(String.valueOf(rows.get(sponsored).getSummary()), String.valueOf(rows.get(sponsored).getSummary())
                    .startsWith("Hides ads in Reels, starting with the next batch Facebook loads. "));
            assertTrue(String.valueOf(rows.get(ai).getSummary()), String.valueOf(rows.get(ai).getSummary())
                    .startsWith("Hides reels and Watch videos that Facebook marks as made with AI. "));
        }
    }

    /**
     * The creator AI label switch sits right below Hide AI-detected posts in News feed, which it adds
     * to, and says it hides both kinds of labelled post.
     */
    @Test
    public void theAiLabelRowSitsRightBelowTheDetectionRow() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.AI_DETECTED_POSTS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(controller);
            int detected = indexOfKey(rows, Settings.HIDE_AI_DETECTED_POSTS.key);
            int labelled = indexOfKey(rows, Settings.HIDE_AI_LABELLED_POSTS.key);
            assertTrue("the AI-detected posts row is missing", detected >= 0);
            assertEquals("the AI label row isn't right below the detection row", detected + 1, labelled);
            assertEquals("Also hide posts labeled as AI", String.valueOf(rows.get(labelled).getTitle()));
            assertTrue(String.valueOf(rows.get(labelled).getSummary()), String.valueOf(rows.get(labelled).getSummary())
                    .contains("not just the ones Facebook detects itself."));
        }
    }

    /**
     * The AI character switch sits right below the Meta AI cards' row, in the same family, and says
     * it starts off.
     */
    @Test
    public void theAiCharacterRowSitsRightBelowTheMetaAiCardsRow() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.AI_DETECTED_POSTS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(controller);
            int cards = indexOfKey(rows, Settings.HIDE_META_AI_FEED_UNITS.key);
            int characters = indexOfKey(rows, Settings.HIDE_AI_CHARACTER_POSTS.key);
            assertTrue("the Meta AI cards row is missing", cards >= 0);
            assertEquals("the AI character row isn't right below the Meta AI cards row", cards + 1, characters);
            assertEquals("Hide AI character posts", String.valueOf(rows.get(characters).getTitle()));
            assertTrue(String.valueOf(rows.get(characters).getSummary()), String.valueOf(rows.get(characters)
                    .getSummary()).contains("so it starts off"));
        }
    }

    @Test
    public void thePausedCardSaysWhatStaysInForEveryReason() {
        for (HushfacebookPause.Reason why : HushfacebookPause.Reason.values()) {
            String summary = HushfacebookPreferenceFragment.pausedSummary(why, "com.facebook.katana");
            assertFalse(why + ": " + summary, UNPATCHED.matcher(summary).find());
            assertTrue(why + ": " + summary, summary.contains("what was set when you patched stays in"));
            // Debug logging and Lock Facebook keep working while paused, so the card can't say every switch is off.
            assertTrue(why + ": " + summary, summary.contains("Every switch but Debug logging and Lock Facebook"));
        }

        // The marker counts only in the app's own files folder, and the card names that folder,
        // not the one above it a person finds first. The folder is isolated, so a right-to-left
        // sentence keeps the path in the order it was written.
        String pkg = RuntimeEnvironment.getApplication().getPackageName();
        assertTrue(HushfacebookPreferenceFragment.pausedSummary(HushfacebookPause.Reason.MARKER_FILE, pkg)
                .contains("in " + L10n.isolate("Android/data/" + pkg + "/files") + " paused Hushfacebook"));

        PauseForTests.pause(HushfacebookPause.Reason.CRASH_LOOP);
        // A crash-loop pause comes from safe mode, which stays on for the next start too. The card
        // reads that to say whether the next start still runs paused.
        BaseSettings.SAFE_MODE.save(true);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Preference card = rowsOf(controller).get(0);
            assertEquals("Hushfacebook is paused", String.valueOf(card.getTitle()));
            assertEquals(HushfacebookPreferenceFragment.pausedSummary(HushfacebookPause.Reason.CRASH_LOOP, RuntimeEnvironment.getApplication().getPackageName())
                    + " Tap to turn it back on.\n" + HushfacebookPreferenceFragment.overviewBuildDetails(), String.valueOf(card.getSummary()));
        }
    }

    /**
     * A version is a value set into a sentence, so both rows that show one isolate it: in a
     * right-to-left sentence "582.0.0.50.54" then keeps the order it was written in.
     */
    @Test
    public void theVersionRowsIsolateTheVersions() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(context.getPackageManager())
                .getInternalMutablePackageInfo(context.getPackageName()).versionName = "582.0.0.50.54";
        String facebook = app.morphe.extension.shared.Utils.getAppVersionName();
        assertTrue("no Facebook version to look for", facebook != null && !facebook.isEmpty());

        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(controller);
            Preference card = rows.get(0);
            assertEquals("Hushfacebook is on", String.valueOf(card.getTitle()));
            // The compact overview names this patch version; About retains Facebook's full version too.
            assertTrue(String.valueOf(card.getSummary()), String.valueOf(card.getSummary()).contains(
                    L10n.isolate(app.morphe.extension.shared.Utils.getPatchesReleaseVersion())));
            assertTrue(String.valueOf(card.getSummary()), String.valueOf(card.getSummary()).contains(L10n.isolate("unknown")));
            Preference version = null;
            for (Preference row : rows) {
                if ("Version".contentEquals(row.getTitle())) version = row;
            }
            assertNotNull("no Version row", version);
            assertTrue(String.valueOf(version.getSummary()), String.valueOf(version.getSummary()).contains(L10n.isolate(facebook)));
            assertTrue(String.valueOf(version.getSummary()), String.valueOf(version.getSummary()).contains(L10n.isolate("unknown")));
        }
    }

    /**
     * The save folder's row keeps the one clean folder name a save would use, whatever is typed
     * into it, and says where videos and photos go. It's there with any download in the build.
     */
    @Test
    public void theFolderRowKeepsOneCleanNameAndSaysWhereSavesGo() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.STORY_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            ValueRows.FolderRow folder = null;
            for (Preference row : rowsOf(controller)) {
                if (row instanceof ValueRows.FolderRow) folder = (ValueRows.FolderRow) row;
            }
            assertNotNull("no folder row with a download in the build", folder);
            assertEquals(Settings.SAVE_FOLDER.key, folder.getKey());
            assertEquals("Videos go to " + L10n.isolate("Movies/Facebook") + " and photos to "
                    + L10n.isolate("Pictures/Facebook") + ".", String.valueOf(folder.getSummary()));

            // What's typed reaches the row's check the way the dialog's OK sends it.
            Preference.OnPreferenceChangeListener ok = folder.getOnPreferenceChangeListener();
            assertFalse("a path was kept as typed", ok.onPreferenceChange(folder, "../My/Clips"));
            ShadowLooper.idleMainLooper();
            assertEquals("My_Clips", folder.getText());
            assertEquals("My_Clips", Settings.SAVE_FOLDER.savedValue());
            assertEquals(HushfacebookPreferenceFragment.folderSummary("My_Clips"), String.valueOf(folder.getSummary()));

            assertTrue("a clean name was changed", ok.onPreferenceChange(folder, "Clips"));
            folder.setText("Clips");
            ShadowLooper.idleMainLooper();
            assertEquals("Clips", Settings.SAVE_FOLDER.savedValue());

            assertFalse("an empty name was kept", ok.onPreferenceChange(folder, "  "));
            ShadowLooper.idleMainLooper();
            assertEquals("Facebook", folder.getText());
            assertEquals("Facebook", Settings.SAVE_FOLDER.savedValue());
        }

        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.SPONSORED_POSTS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            for (Preference row : rowsOf(controller)) {
                assertFalse("a folder row with no download in the build",
                        row instanceof ValueRows.FolderRow);
            }
        }
    }

    /**
     * Below the file names, which stay next to the folder, videos and photos each get a subfolder
     * row, the photo one with Download any photo in the build. Blank, the default, saves stay in the
     * folder itself; a name is cleaned as the folder's is, and both rows and the folder row say
     * where each kind goes.
     */
    @Test
    public void theSubfolderRowsKeepOneCleanNameEachAndSayWhereSavesGo() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.STORY_DOWNLOAD, PatchFamily.PHOTO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(controller);
            int folderAt = indexOfKey(rows, Settings.SAVE_FOLDER.key);
            ValueRows.FolderRow folder = (ValueRows.FolderRow) rows.get(folderAt);
            int namesEnd = indexOfKey(rows, Settings.PHOTO_FILENAME_TEMPLATE.key);
            assertEquals("the file names sit right below the folder", folderAt + 2, namesEnd);
            ValueRows.SubfolderRow videos = (ValueRows.SubfolderRow) rows.get(namesEnd + 1);
            ValueRows.SubfolderRow photos = (ValueRows.SubfolderRow) rows.get(namesEnd + 2);
            assertEquals(Settings.VIDEO_SUBFOLDER.key, videos.getKey());
            assertEquals("Video subfolder", String.valueOf(videos.getTitle()));
            assertEquals(Settings.PHOTO_SUBFOLDER.key, photos.getKey());
            assertEquals("Photo subfolder", String.valueOf(photos.getTitle()));
            assertEquals("Videos go to " + L10n.isolate("Movies/Facebook") + ".", String.valueOf(videos.getSummary()));
            assertEquals("Photos go to " + L10n.isolate("Pictures/Facebook") + ".", String.valueOf(photos.getSummary()));

            Preference.OnPreferenceChangeListener ok = videos.getOnPreferenceChangeListener();
            assertFalse("a path was kept as typed", ok.onPreferenceChange(videos, "../My/Videos"));
            ShadowLooper.idleMainLooper();
            assertEquals("My_Videos", videos.getText());
            assertEquals("My_Videos", Settings.VIDEO_SUBFOLDER.savedValue());
            assertEquals("Videos go to " + L10n.isolate("Movies/Facebook/My_Videos") + ".", String.valueOf(videos.getSummary()));
            assertEquals("Videos go to " + L10n.isolate("Movies/Facebook/My_Videos") + " and photos to "
                    + L10n.isolate("Pictures/Facebook") + ".", HushfacebookPreferenceFragment.folderSummary("Facebook"));

            // Blank is no subfolder, not the default folder's name.
            assertFalse(ok.onPreferenceChange(videos, " .. "));
            ShadowLooper.idleMainLooper();
            assertEquals("", Settings.VIDEO_SUBFOLDER.savedValue());
            assertEquals("Videos go to " + L10n.isolate("Movies/Facebook") + ".", String.valueOf(videos.getSummary()));
            assertTrue("a clean name was changed", photos.getOnPreferenceChangeListener().onPreferenceChange(photos, "Photos"));
        }

        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.SPONSORED_POSTS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            for (Preference row : rowsOf(controller)) {
                assertFalse("a subfolder row with no download in the build", row instanceof ValueRows.SubfolderRow);
            }
        }
    }

    /**
     * Save to (#42): right above the folder, it offers Movies and Pictures, the default, DCIM and
     * Download, and the folder row's summary and dialog name the top folder it picks, after a tap
     * as much as on a fresh page.
     */
    @Test
    public void theSaveToRowPicksTheTopFolderAndTheFolderRowFollowsIt() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.STORY_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(controller);
            int toAt = -1;
            int folderAt = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i) instanceof ValueRows.SaveToRow) toAt = i;
                if (rows.get(i) instanceof ValueRows.FolderRow) folderAt = i;
            }
            assertTrue("no Save to row with a download in the build", toAt >= 0);
            assertEquals("Save to isn't right above the folder", folderAt - 1, toAt);
            ValueRows.SaveToRow to = (ValueRows.SaveToRow) rows.get(toAt);
            ValueRows.FolderRow folder = (ValueRows.FolderRow) rows.get(folderAt);
            assertEquals(Settings.SAVE_TO.key, to.getKey());
            assertEquals("Save to", String.valueOf(to.getTitle()));
            List<String> entries = new ArrayList<>();
            for (CharSequence entry : to.getEntries()) entries.add(String.valueOf(entry));
            assertEquals(Arrays.asList("Movies and Pictures", L10n.isolate("DCIM"), L10n.isolate("Download")), entries);
            List<String> values = new ArrayList<>();
            for (CharSequence value : to.getEntryValues()) values.add(String.valueOf(value));
            assertEquals(Arrays.asList("MOVIES_AND_PICTURES", "DCIM", "DOWNLOAD"), values);
            assertEquals("MOVIES_AND_PICTURES", to.getValue());
            assertEquals("Videos go to " + L10n.isolate("Movies") + " and photos to " + L10n.isolate("Pictures")
                    + ", as Facebook's own saves do. Some galleries don't show " + L10n.isolate("Movies") + ".",
                    String.valueOf(to.getSummary()));
            assertEquals("Choose a folder name under Movies and Pictures. Invalid characters become underscores. "
                    + "Leave it blank to use the default folder, " + L10n.isolate("Facebook") + ".",
                    String.valueOf(folder.getDialogMessage()));

            for (SaveTo choice : new SaveTo[]{SaveTo.DOWNLOAD, SaveTo.DCIM}) {
                to.setValue(choice.name());
                ShadowLooper.idleMainLooper();
                assertEquals(choice, Settings.SAVE_TO.savedValue());
                assertEquals(HushfacebookPreferenceFragment.saveToSummary(choice), String.valueOf(to.getSummary()));
                String top = choice.directory(true);
                assertTrue(String.valueOf(to.getSummary()), String.valueOf(to.getSummary()).startsWith(
                        "Videos and photos go to " + L10n.isolate(top) + ", "));
                assertEquals("Videos and photos go to " + L10n.isolate(top + "/Facebook") + ".",
                        String.valueOf(folder.getSummary()));
                assertEquals("Choose a folder name under " + L10n.isolate(top) + ". Invalid characters become "
                        + "underscores. Leave it blank to use the default folder, " + L10n.isolate("Facebook") + ".",
                        String.valueOf(folder.getDialogMessage()));
            }

            to.setValue("MOVIES_AND_PICTURES");
            ShadowLooper.idleMainLooper();
            assertEquals(SaveTo.MOVIES_AND_PICTURES, Settings.SAVE_TO.savedValue());
            assertEquals("Videos go to " + L10n.isolate("Movies/Facebook") + " and photos to "
                    + L10n.isolate("Pictures/Facebook") + ".", String.valueOf(folder.getSummary()));
        }

        // A fresh page reads the saved choice.
        Settings.SAVE_TO.save(SaveTo.DOWNLOAD);
        Settings.SAVE_FOLDER.save("Clips");
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            ValueRows.SaveToRow to = null;
            ValueRows.FolderRow folder = null;
            for (Preference row : rowsOf(controller)) {
                if (row instanceof ValueRows.SaveToRow) to = (ValueRows.SaveToRow) row;
                if (row instanceof ValueRows.FolderRow) folder = (ValueRows.FolderRow) row;
            }
            assertEquals("DOWNLOAD", to.getValue());
            assertEquals("Videos and photos go to " + L10n.isolate("Download/Clips") + ".", String.valueOf(folder.getSummary()));
        }
    }

    /**
     * Send to an app (#41): with a reel or video download in the build, Downloads ends with what a
     * tap on Download does, saving by default, and the app the links go to. The app row keeps a
     * package name trimmed and turns down anything else. A story-only build has neither, since a
     * story always saves.
     */
    @Test
    public void theSendRowsChooseWhatDownloadDoesAndWhichAppGetsTheLink() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(controller);
            int nameAt = -1;
            int actionAt = -1;
            int appAt = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i) instanceof ValueRows.FileNameRow) nameAt = i;
                if (rows.get(i) instanceof ValueRows.DownloadActionRow) actionAt = i;
                if (rows.get(i) instanceof ValueRows.SendAppRow) appAt = i;
            }
            assertTrue("no download action row with a reel download in the build", actionAt >= 0);
            assertEquals("the action row isn't under the file name", nameAt + 1, actionAt);
            assertEquals("the app row isn't under the action", actionAt + 1, appAt);

            ValueRows.DownloadActionRow action =
                    (ValueRows.DownloadActionRow) rows.get(actionAt);
            assertEquals(Settings.DOWNLOAD_ACTION.key, action.getKey());
            assertEquals("When you tap Download", String.valueOf(action.getTitle()));
            assertEquals(Arrays.asList("Save to phone", "Send the link to an app"),
                    Arrays.asList(String.valueOf(action.getEntries()[0]), String.valueOf(action.getEntries()[1])));
            assertEquals(Arrays.asList("SAVE", "SEND"),
                    Arrays.asList(String.valueOf(action.getEntryValues()[0]), String.valueOf(action.getEntryValues()[1])));
            assertEquals("SAVE", action.getValue());
            assertEquals("Reels, videos and stories save to this phone.", String.valueOf(action.getSummary()));

            action.setValue("SEND");
            ShadowLooper.idleMainLooper();
            assertEquals(SendLink.Action.SEND, Settings.DOWNLOAD_ACTION.savedValue());
            assertEquals(HushfacebookPreferenceFragment.downloadActionSummary(SendLink.Action.SEND),
                    String.valueOf(action.getSummary()));

            ValueRows.SendAppRow app = (ValueRows.SendAppRow) rows.get(appAt);
            assertEquals(Settings.SEND_TO_APP.key, app.getKey());
            assertEquals("App to send to", String.valueOf(app.getTitle()));
            assertEquals("Android asks which app each time.", String.valueOf(app.getSummary()));
            String message = String.valueOf(app.getDialogMessage());
            assertTrue(message, message.contains(L10n.isolate(SendLink.YTDLNIS)) && message.contains(L10n.isolate(SendLink.SEAL)));

            Preference.OnPreferenceChangeListener ok = app.getOnPreferenceChangeListener();
            assertFalse("a name with spaces round it was kept as typed", ok.onPreferenceChange(app, " com.junkfood.seal "));
            ShadowLooper.idleMainLooper();
            assertEquals(SendLink.SEAL, app.getText());
            assertEquals(SendLink.SEAL, Settings.SEND_TO_APP.savedValue());
            assertEquals("Links go to " + L10n.isolate(SendLink.SEAL) + ". When it isn't installed, Android asks which app.",
                    String.valueOf(app.getSummary()));

            assertFalse("something that isn't a package name was kept", ok.onPreferenceChange(app, "seal --exec"));
            ShadowLooper.idleMainLooper();
            assertEquals(SendLink.SEAL, Settings.SEND_TO_APP.savedValue());
            assertEquals(L10n.isolate("seal --exec") + " isn't a valid app ID, so the app stays as it was.",
                    ShadowToast.getTextOfLatestToast());

            assertTrue("a clean name was changed", ok.onPreferenceChange(app, SendLink.YTDLNIS));
            assertTrue("a blank name was turned down", ok.onPreferenceChange(app, ""));
        }

        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.STORY_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            for (Preference row : rowsOf(controller)) {
                assertFalse("a send row with only story downloads in the build",
                        row instanceof ValueRows.DownloadActionRow
                                || row instanceof ValueRows.SendAppRow);
            }
        }
    }

    /**
     * The word filter's switch sits in News feed with its two lists under it. Each list keeps what
     * the filter will read, whatever is typed, and says how many lines were left out, never which.
     * That's a dialog rather than a toast: Android 12 and later cut a toast to two lines, and the
     * reasons run past that, most of all at a large text size. The rows are there only with the
     * patch in the build.
     */
    @Test
    public void theWordRowsKeepCleanListsAndSayHowManyPhrasesTheyHold() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.POST_WORDS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(controller);
            int switchAt = indexOfKey(rows, Settings.HIDE_POSTS_WITH_WORDS.key);
            assertTrue("no word filter switch", switchAt >= 0);
            assertEquals("the hide list isn't under the switch", switchAt + 1, indexOfKey(rows, Settings.HIDDEN_WORDS.key));
            assertEquals("the keep list isn't under the hide list", switchAt + 2, indexOfKey(rows, Settings.KEPT_WORDS.key));
            ValueRows.WordsRow hide = (ValueRows.WordsRow) rows.get(switchAt + 1);
            ValueRows.WordsRow keep = (ValueRows.WordsRow) rows.get(switchAt + 2);
            assertEquals("Words to hide", String.valueOf(hide.getTitle()));
            assertEquals("No words yet, so no post is hidden.", String.valueOf(hide.getSummary()));
            assertEquals("No words yet.", String.valueOf(keep.getSummary()));
            assertFalse("the list's field is one line", hide.getEditText().getMaxLines() == 1);

            ShadowToast.reset();
            ShadowAlertDialog.reset();
            Preference.OnPreferenceChangeListener ok = hide.getOnPreferenceChangeListener();
            assertFalse("a list with lines out of bounds was kept as typed",
                    ok.onPreferenceChange(hide, " spoiler \na\nSPOILER\ngiveaway now\n"));
            ShadowLooper.idleMainLooper();
            assertEquals("spoiler\ngiveaway now", hide.getText());
            assertEquals("spoiler\ngiveaway now", Settings.HIDDEN_WORDS.savedValue());
            assertEquals("2 words or phrases.", String.valueOf(hide.getSummary()));
            assertLeftOut("Words to hide", "2 lines were left out. A phrase needs 2 to 60 characters, but a single "
                    + "emoji or Chinese, Japanese or Korean character is enough. One given twice counts once.");

            ShadowAlertDialog.reset();
            assertTrue("a clean list was changed", ok.onPreferenceChange(hide, "spoiler"));
            assertFalse("only spaces around a phrase", ok.onPreferenceChange(hide, "spoiler  "));
            ShadowLooper.idleMainLooper();
            assertNull("a list cleaned of spaces alone said something", ShadowAlertDialog.getLatestAlertDialog());
            assertNull(ShadowToast.getTextOfLatestToast());
            assertEquals("spoiler", Settings.HIDDEN_WORDS.savedValue());

            assertFalse(keep.getOnPreferenceChangeListener().onPreferenceChange(keep, "my team\nmy team"));
            ShadowLooper.idleMainLooper();
            assertEquals("my team", Settings.KEPT_WORDS.savedValue());
            assertEquals("1 word or phrase.", String.valueOf(keep.getSummary()));
            assertLeftOut("Words that keep a post", "1 line was left out. A phrase needs 2 to 60 characters, but a single "
                    + "emoji or Chinese, Japanese or Korean character is enough. One given twice counts once.");
        }

        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.SPONSORED_POSTS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(controller);
            assertEquals(-1, indexOfKey(rows, Settings.HIDDEN_WORDS.key));
            assertEquals(-1, indexOfKey(rows, Settings.HIDE_POSTS_WITH_WORDS.key));
        }
    }

    /**
     * The dialog that says lines were left out: titled with its list, holding the whole message
     * with nothing cut, and no toast beside it. OK closes it.
     */
    private static void assertLeftOut(String list, String message) {
        AlertDialog shown = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("nothing said lines were left out", shown);
        assertTrue(shown.isShowing());
        assertEquals(list, String.valueOf(Shadows.shadowOf(shown).getTitle()));
        TextView text = shown.findViewById(android.R.id.message);
        assertEquals(message, String.valueOf(text.getText()));
        assertEquals("the message is cut to a number of lines", Integer.MAX_VALUE, text.getMaxLines());
        assertNull("the message is cut short", text.getEllipsize());
        assertNull("a toast said it too", ShadowToast.getTextOfLatestToast());
        shown.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse("OK left it open", shown.isShowing());
    }

    /**
     * The page's own dialogs are drawn over its activity's window: the list of sections, Licenses
     * and a word list's note. Open when the page went, on a rotation, Back or Facebook closing,
     * they outlived that window, which Android reports as a leaked window, and the note went with it.
     */
    @Test
    public void thePagesDialogsCloseWhenItsViewGoes() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.POST_WORDS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(controller);
            List<AlertDialog> open = new ArrayList<>();
            Preference jump = rows.get(indexOfKey(rows, "action_jump_to_section"));
            jump.getOnPreferenceClickListener().onPreferenceClick(jump);
            open.add(ShadowAlertDialog.getLatestAlertDialog());
            Preference licenses = null;
            for (Preference row : rows) if ("Licenses".contentEquals(row.getTitle())) licenses = row;
            assertNotNull("no Licenses row", licenses);
            licenses.getOnPreferenceClickListener().onPreferenceClick(licenses);
            open.add(ShadowAlertDialog.getLatestAlertDialog());
            Preference hide = rows.get(indexOfKey(rows, Settings.HIDDEN_WORDS.key));
            assertFalse(hide.getOnPreferenceChangeListener().onPreferenceChange(hide, "a\nspoiler"));
            ShadowLooper.idleMainLooper();
            open.add(ShadowAlertDialog.getLatestAlertDialog());

            List<String> titles = new ArrayList<>();
            for (AlertDialog dialog : open) {
                assertTrue(dialog.isShowing());
                titles.add(String.valueOf(Shadows.shadowOf(dialog).getTitle()));
            }
            assertEquals(Arrays.asList("Jump to a section", "Licenses", "Words to hide"), titles);

            controller.recreate();
            ShadowLooper.idleMainLooper();

            for (AlertDialog dialog : open) {
                assertFalse(Shadows.shadowOf(dialog).getTitle() + " outlived the page", dialog.isShowing());
            }
        }
    }

    /**
     * Share sheet items to hide opens a list of checkboxes over the page's window like the dialogs
     * above, and it was the one the page didn't track: a rotation left it up as a leaked window.
     */
    @Test
    public void theShareSheetListClosesWhenTheViewGoes() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.SHARE_SHEET_ITEMS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(controller);
            Preference items = rows.get(indexOfKey(rows, AppPages.SHARE_ITEMS_ROW));
            ShadowAlertDialog.reset();
            items.getOnPreferenceClickListener().onPreferenceClick(items);
            AlertDialog list = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull("the row opened nothing", list);
            assertTrue(list.isShowing());
            assertEquals("Hide from the share sheet", String.valueOf(Shadows.shadowOf(list).getTitle()));

            controller.recreate();
            ShadowLooper.idleMainLooper();

            assertFalse("the share sheet list outlived the page", list.isShowing());
        }
    }

    /**
     * A Save on a word list can land as the activity goes. The button's click and the edit
     * dialog's close are posted one after the other, and when the activity's end comes between
     * them, it closes the dialog itself, which still counts as Save. The list's listener then runs
     * after the page is gone, and its note that lines were left out came up over a window that
     * was gone with it.
     */
    @Test
    public void aWordListSavedAsTheActivityGoesShowsNoNoteOverIt() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.POST_WORDS);
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        List<Preference> rows = rowsOf(controller);
        ValueRows.WordsRow hide =
                (ValueRows.WordsRow) rows.get(indexOfKey(rows, Settings.HIDDEN_WORDS.key));
        hide.showDialog(null);
        AlertDialog edit = (AlertDialog) hide.getDialog();
        hide.getEditText().setText("a\nspoiler");
        ShadowLooper.idleMainLooper();
        ShadowAlertDialog.reset();

        edit.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        // The click runs, and the close waits behind it.
        ShadowLooper.shadowMainLooper().runOneTask();
        assertTrue("the edit dialog closed before the activity went", edit.isShowing());
        controller.pause().stop().destroy();
        ShadowLooper.idleMainLooper();

        // Only the list's listener puts the cleaned list in the row.
        assertEquals("the listener never ran, so this checked nothing", "spoiler", hide.getText());
        AlertDialog note = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue("a note came up over a destroyed activity", note == null || !note.isShowing());
        // The row can show the clean list while the filter still runs the old one: only the
        // shared preferences listener carries a save into the running Setting.
        assertEquals("the filter kept the old list", "spoiler", Settings.HIDDEN_WORDS.get());
    }

    /** The hide list's row counts the posts it hid since Facebook started, and never names one. */
    @Test
    public void theHideListSaysHowManyPostsItHid() {
        assertEquals("1 word or phrase.", HushfacebookPreferenceFragment.wordsSummary("spoiler", true));
        PostWordsForTests.countHidden(3);
        assertEquals("1 word or phrase. Hid 3 posts since Facebook started.",
                HushfacebookPreferenceFragment.wordsSummary("spoiler", true));
        assertEquals("the keep list hides nothing", "1 word or phrase.",
                HushfacebookPreferenceFragment.wordsSummary("spoiler", false));
    }

    /**
     * The download quality's row offers every quality, says what the chosen one does, and a pick
     * reaches the setting the way the list's own dialog sends it. It sits above Save to and the
     * folder, and it's there with any download in the build.
     */
    @Test
    public void theQualityRowOffersEveryQualityAndSaysWhatItDoes() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = new HushfacebookPreferenceFragment();
            controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
            List<Preference> rows = new ArrayList<>();
            collect(page.getPreferenceScreen(), rows);
            ValueRows.QualityRow quality = null;
            int qualityAt = -1;
            int toAt = -1;
            int folderAt = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i) instanceof ValueRows.QualityRow) {
                    quality = (ValueRows.QualityRow) rows.get(i);
                    qualityAt = i;
                }
                if (rows.get(i) instanceof ValueRows.SaveToRow) toAt = i;
                if (rows.get(i) instanceof ValueRows.FolderRow) folderAt = i;
            }
            assertNotNull("no quality row with a download in the build", quality);
            // Save to (#42) goes between them: the top folder, then the folder under it.
            assertEquals("the quality row isn't right above Save to", toAt - 1, qualityAt);
            assertEquals("Save to isn't right above the folder", folderAt - 1, toAt);
            assertEquals(Settings.DOWNLOAD_QUALITY.key, quality.getKey());
            assertEquals("Download quality", String.valueOf(quality.getTitle()));

            List<String> entries = new ArrayList<>();
            for (CharSequence entry : quality.getEntries()) entries.add(String.valueOf(entry));
            assertEquals(Arrays.asList("Best", L10n.isolate("1080p"), L10n.isolate("720p"), L10n.isolate("480p"),
                    L10n.isolate("360p"), "Smallest"), entries);
            List<String> values = new ArrayList<>();
            for (CharSequence value : quality.getEntryValues()) values.add(String.valueOf(value));
            List<String> names = new ArrayList<>();
            for (DownloadQuality each : DownloadQuality.values()) names.add(each.name());
            assertEquals(names, values);

            assertEquals("BEST", quality.getValue());
            assertEquals("Each video saves at the best quality the player streams.", String.valueOf(quality.getSummary()));

            // A pick in the list, the way its dialog sends one.
            quality.setValue("P480");
            ShadowLooper.idleMainLooper();
            assertEquals(DownloadQuality.P480, Settings.DOWNLOAD_QUALITY.savedValue());
            // A cap prefers anything at or under it, so the summary can't promise the nearest
            // quality: under 720p a video with 1080p and 240p saves at 240p.
            assertEquals("Each video saves at " + L10n.isolate("480p") + " or the closest quality below it. A video "
                            + "with nothing that low saves at the closest quality above.",
                    String.valueOf(quality.getSummary()));

            quality.setValue("SMALLEST");
            ShadowLooper.idleMainLooper();
            assertEquals(DownloadQuality.SMALLEST, Settings.DOWNLOAD_QUALITY.savedValue());
            assertEquals("Each video saves at its lowest quality, for the smallest file.",
                    String.valueOf(quality.getSummary()));

            // A value set behind the row, as an import does, shows once the page syncs.
            Settings.DOWNLOAD_QUALITY.save(DownloadQuality.P720);
            page.refreshSwitches();
            assertEquals("P720", quality.getValue());
            assertEquals(HushfacebookPreferenceFragment.qualitySummary(DownloadQuality.P720),
                    String.valueOf(quality.getSummary()));
        }

        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.SPONSORED_POSTS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            for (Preference row : rowsOf(controller)) {
                assertFalse("a quality row with no download in the build",
                        row instanceof ValueRows.QualityRow);
            }
        }
    }

    /**
     * Saves other apps can open (issue #11) is a switch every save reads, so it's under Downloads
     * with any one download patch in, right above the quality it keeps within, and starts off. Its
     * summary names WhatsApp, the app that turned an AV1 reel down, and a gallery or player that
     * plays a save without sound, since some can't decode the xHE-AAC sound a Best reel can carry.
     */
    @Test
    public void theCompatibleSwitchSitsAboveTheQualityWithAnyDownloadIn() {
        for (PatchFamily download : new PatchFamily[]{PatchFamily.STORY_DOWNLOAD, PatchFamily.REEL_DOWNLOAD, PatchFamily.PHOTO_DOWNLOAD,
                PatchFamily.VIDEO_DOWNLOAD}) {
            PatchFamily.inBuildForTests = EnumSet.of(download);
            try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
                List<Preference> rows = rowsOf(controller);
                int compatible = indexOfKey(rows, Settings.DOWNLOAD_COMPATIBLE.key);
                assertTrue(download + ": no row for saves other apps can open", compatible >= 0);
                Preference row = rows.get(compatible);
                assertTrue(row instanceof SwitchPreference);
                assertFalse(((SwitchPreference) row).isChecked());
                assertEquals(indexOfKey(rows, Settings.DOWNLOAD_QUALITY.key) - 1, compatible);
                assertEquals("Save videos other apps can open", String.valueOf(row.getTitle()));
                assertEquals("Fixes saved videos that play without sound in WhatsApp, video editors such as CapCut and InShot, or "
                        + "some galleries and players. May lower quality.",
                        String.valueOf(row.getSummary()));
            }
        }

        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.SPONSORED_POSTS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            assertEquals("a compatible-saves row with no download in the build",
                    -1, indexOfKey(rowsOf(controller), Settings.DOWNLOAD_COMPATIBLE.key));
        }
    }

    /**
     * With Open on a chosen tab in the build, the screen opens with its switch and the list of
     * tabs, which offers each tab by its name in Facebook, says what the chosen one does, and
     * reaches the setting the way the list's own dialog sends a pick.
     */
    @Test
    public void theStartTabRowOffersEveryTabAndSaysWhatItDoes() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.START_TAB, PatchFamily.SPONSORED_POSTS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = new HushfacebookPreferenceFragment();
            controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
            List<Preference> rows = new ArrayList<>();
            collect(page.getPreferenceScreen(), rows);
            // Right under the status card and the section jump: the heading, the switch, then the list.
            assertEquals("Jump to a section", String.valueOf(page.getPreferenceScreen().getPreference(1).getTitle()));
            assertEquals("Opening Facebook", String.valueOf(page.getPreferenceScreen().getPreference(2).getTitle()));
            assertEquals(Settings.OPEN_ON_CHOSEN_TAB.key, rows.get(2).getKey());
            assertEquals("Open on a chosen tab", String.valueOf(rows.get(2).getTitle()));
            assertTrue(rows.get(3) instanceof ValueRows.StartTabRow);
            ValueRows.StartTabRow start = (ValueRows.StartTabRow) rows.get(3);
            assertEquals(Settings.START_TAB.key, start.getKey());
            assertEquals("Tab to open on", String.valueOf(start.getTitle()));

            List<String> entries = new ArrayList<>();
            for (CharSequence entry : start.getEntries()) entries.add(String.valueOf(entry));
            assertEquals(Arrays.asList("Home", "Feeds", "Video", "Friends", "Marketplace", "Notifications", "Menu"),
                    entries);
            List<String> values = new ArrayList<>();
            for (CharSequence value : start.getEntryValues()) values.add(String.valueOf(value));
            List<String> names = new ArrayList<>();
            for (StartTab each : StartTab.values()) names.add(each.name());
            assertEquals(names, values);

            assertEquals("MARKETPLACE", start.getValue());
            assertEquals("Facebook opens on Marketplace. If your tab bar doesn't have it, Facebook opens on Home.",
                    String.valueOf(start.getSummary()));

            // A pick in the list, the way its dialog sends one.
            start.setValue("FRIENDS");
            ShadowLooper.idleMainLooper();
            assertEquals(StartTab.FRIENDS, Settings.START_TAB.savedValue());
            assertEquals("Facebook opens on Friends. If your tab bar doesn't have it, Facebook opens on Home.",
                    String.valueOf(start.getSummary()));

            // A value set behind the row, as an import does, shows once the page syncs.
            Settings.START_TAB.save(StartTab.MENU);
            page.refreshSwitches();
            assertEquals("MENU", start.getValue());
            assertEquals(HushfacebookPreferenceFragment.startTabSummary(StartTab.MENU), String.valueOf(start.getSummary()));
        } finally {
            Settings.START_TAB.resetToDefault();
        }

        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.SPONSORED_POSTS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            for (Preference row : rowsOf(controller)) {
                assertFalse("a start tab row with no Open on a chosen tab in the build",
                        row instanceof ValueRows.StartTabRow);
                assertFalse("a Feeds filter row with no Open on a chosen tab in the build",
                        row instanceof ValueRows.FeedsSubtabRow);
                assertFalse(Settings.OPEN_ON_CHOSEN_TAB.key.equals(row.getKey()));
            }
        }
    }

    /**
     * Under the start tab's list, the Feeds filter's list offers each filter by its name in
     * Facebook, says what the chosen one does, and reaches the setting the way its dialog sends a
     * pick (#56).
     */
    @Test
    public void theFeedsFilterRowOffersEveryFilterAndSaysWhatItDoes() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.START_TAB, PatchFamily.SPONSORED_POSTS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = new HushfacebookPreferenceFragment();
            controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
            List<Preference> rows = new ArrayList<>();
            collect(page.getPreferenceScreen(), rows);
            assertTrue(rows.get(3) instanceof ValueRows.StartTabRow);
            assertTrue(rows.get(4) instanceof ValueRows.FeedsSubtabRow);
            ValueRows.FeedsSubtabRow subtab = (ValueRows.FeedsSubtabRow) rows.get(4);
            assertEquals(Settings.FEEDS_SUBTAB.key, subtab.getKey());
            assertEquals("Feeds opens on", String.valueOf(subtab.getTitle()));

            List<String> entries = new ArrayList<>();
            for (CharSequence entry : subtab.getEntries()) entries.add(String.valueOf(entry));
            assertEquals(Arrays.asList("All", "Favorites", "Friends", "Groups", "Pages"), entries);
            List<String> values = new ArrayList<>();
            for (CharSequence value : subtab.getEntryValues()) values.add(String.valueOf(value));
            List<String> names = new ArrayList<>();
            for (FeedsSubtab each : FeedsSubtab.values()) names.add(each.name());
            assertEquals(names, values);

            assertEquals("ALL", subtab.getValue());
            assertEquals("When Facebook opens on Feeds, the Feeds tab opens on the filter Facebook picks.",
                    String.valueOf(subtab.getSummary()));

            subtab.setValue("GROUPS");
            ShadowLooper.idleMainLooper();
            assertEquals(FeedsSubtab.GROUPS, Settings.FEEDS_SUBTAB.savedValue());
            assertEquals("When Facebook opens on Feeds, the Feeds tab opens on Groups. If your Feeds tab doesn't "
                    + "have it, it opens as usual.", String.valueOf(subtab.getSummary()));

            Settings.FEEDS_SUBTAB.save(FeedsSubtab.FAVORITES);
            page.refreshSwitches();
            assertEquals("FAVORITES", subtab.getValue());
            assertEquals(HushfacebookPreferenceFragment.feedsSubtabSummary(FeedsSubtab.FAVORITES),
                    String.valueOf(subtab.getSummary()));
        } finally {
            Settings.FEEDS_SUBTAB.resetToDefault();
        }
    }

    /**
     * With Hide the Reels tab in the build, its switch is under Reels and Watch, and while it's on a
     * chosen Video tab's row says Facebook opens on Home, since a start never lands on the hidden
     * tab. Switched off, with no Marketplace only in the build, the row names Video again. The saved
     * choice stays Video throughout.
     */
    @Test
    public void aChosenReelsTabSaysItOpensHomeWhileHideTheReelsTabIsOn() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.START_TAB, PatchFamily.REELS_TAB);
        Settings.START_TAB.save(StartTab.VIDEO);
        Settings.HIDE_REELS_TAB.save(true);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = new HushfacebookPreferenceFragment();
            controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
            SwitchPreference hide = (SwitchPreference) page.findPreference(Settings.HIDE_REELS_TAB.key);
            assertEquals("Hide the Reels tab", String.valueOf(hide.getTitle()));
            assertEquals("Reels and Watch", String.valueOf(hide.getParent().getTitle()));
            assertTrue("the row doesn't show the switch on", hide.isChecked());
            Preference start = page.findPreference(Settings.START_TAB.key);
            assertEquals("Facebook opens on Home while Hide the Reels tab is on, since Video is off the tab bar. "
                    + "Your choice stays saved.", String.valueOf(start.getSummary()));

            hide.setChecked(false);
            ShadowLooper.idleMainLooper();
            assertFalse(Settings.HIDE_REELS_TAB.savedValue());
            assertEquals("Facebook opens on Video. If your tab bar doesn't have it, Facebook opens on Home.",
                    String.valueOf(start.getSummary()));
            assertEquals(StartTab.VIDEO, Settings.START_TAB.savedValue());

            // Any other tab keeps its own summary with the switch on.
            hide.setChecked(true);
            ShadowLooper.idleMainLooper();
            assertEquals(HushfacebookPreferenceFragment.startTabSummary(StartTab.FRIENDS),
                    "Facebook opens on Friends. If your tab bar doesn't have it, Facebook opens on Home.");
        } finally {
            Settings.START_TAB.resetToDefault();
            Settings.HIDE_REELS_TAB.resetToDefault();
        }

        // Without the patch, a chosen Video tab is Facebook's to open or not, whatever the stored switch says.
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.START_TAB);
        assertEquals("Facebook opens on Video. If your tab bar doesn't have it, Facebook opens on Home.",
                HushfacebookPreferenceFragment.startTabSummary(StartTab.VIDEO));
    }

    /** Feeds off the bar, the account's or kept off by Hide tabs, opens as its own page over Home. */
    @Test public void aStartOnFeedsSaysItsPageOpensOffTheBar() {
        String feeds = "Facebook opens on Feeds. If your tab bar doesn't have it, the Feeds page opens over Home.";
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.START_TAB);
        assertEquals(feeds, HushfacebookPreferenceFragment.startTabSummary(StartTab.FEEDS));
        assertEquals(Settings.HIDE_FEEDS_TAB, HiddenTabs.Tab.forStart(StartTab.FEEDS).setting());
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.START_TAB, PatchFamily.HIDDEN_TABS);
        Settings.HIDE_FEEDS_TAB.save(true);
        try {
            assertEquals(feeds, HushfacebookPreferenceFragment.startTabSummary(StartTab.FEEDS));
            // Any other tab Hide the Friends tab and its kin keep off the bar still opens Home.
            Settings.HIDE_FRIENDS_TAB.save(true);
            assertEquals("Facebook opens on Home while Hide the Friends tab is on, since Friends is off the tab bar. Your choice stays saved.",
                    HushfacebookPreferenceFragment.startTabSummary(StartTab.FRIENDS));
        } finally {
            Settings.HIDE_FEEDS_TAB.resetToDefault();
            Settings.HIDE_FRIENDS_TAB.resetToDefault();
        }
    }

    /**
     * With Default comment order in the build, the screen has a Comments section with its switch and
     * the list of orders, which offers Facebook's own choice first and each order by the name
     * Facebook's sort menu gives it, says what the chosen one does, and reaches the setting the way
     * the list's own dialog sends a pick.
     */
    @Test
    public void theCommentOrderRowOffersFacebooksChoiceAndEachOrder() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.DEFAULT_COMMENT_ORDER, PatchFamily.SPONSORED_POSTS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = new HushfacebookPreferenceFragment();
            controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
            List<Preference> rows = new ArrayList<>();
            collect(page.getPreferenceScreen(), rows);
            int toggle = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (Settings.DEFAULT_COMMENT_ORDER.key.equals(rows.get(i).getKey())) toggle = i;
            }
            assertTrue("no Default comment order switch", toggle >= 0);
            // Its own section, headed Comments, holds the switch and the list and nothing else.
            PreferenceGroup section = null;
            for (int i = 0; i < page.getPreferenceScreen().getPreferenceCount(); i++) {
                Preference top = page.getPreferenceScreen().getPreference(i);
                if (top instanceof PreferenceGroup
                        && ((PreferenceGroup) top).findPreference(Settings.DEFAULT_COMMENT_ORDER.key) != null) {
                    section = (PreferenceGroup) top;
                }
            }
            assertNotNull(section);
            assertEquals("Comments", String.valueOf(section.getTitle()));
            assertEquals(2, section.getPreferenceCount());
            assertEquals("Default comment order", String.valueOf(rows.get(toggle).getTitle()));
            assertTrue(rows.get(toggle + 1) instanceof ValueRows.CommentOrderRow);
            ValueRows.CommentOrderRow order =
                    (ValueRows.CommentOrderRow) rows.get(toggle + 1);
            assertEquals(Settings.COMMENT_ORDER.key, order.getKey());
            assertEquals("Comment order", String.valueOf(order.getTitle()));

            List<String> entries = new ArrayList<>();
            for (CharSequence entry : order.getEntries()) entries.add(String.valueOf(entry));
            assertEquals(Arrays.asList("Facebook's choice", "Most relevant", "Newest", "All comments"), entries);
            List<String> values = new ArrayList<>();
            for (CharSequence value : order.getEntryValues()) values.add(String.valueOf(value));
            List<String> names = new ArrayList<>();
            for (CommentOrder each : CommentOrder.values()) names.add(each.name());
            assertEquals(names, values);

            assertEquals("FACEBOOK", order.getValue());
            assertEquals("Comments open in the order Facebook picks, which is usually Most relevant.",
                    String.valueOf(order.getSummary()));

            // A pick in the list, the way its dialog sends one.
            order.setValue("ALL_COMMENTS");
            ShadowLooper.idleMainLooper();
            assertEquals(CommentOrder.ALL_COMMENTS, Settings.COMMENT_ORDER.savedValue());
            assertEquals("A post's comments open with All comments picked in their sort menu, where the post offers it.",
                    String.valueOf(order.getSummary()));

            // A value set behind the row, as an import does, shows once the page syncs.
            Settings.COMMENT_ORDER.save(CommentOrder.NEWEST);
            page.refreshSwitches();
            assertEquals("NEWEST", order.getValue());
            assertEquals(HushfacebookPreferenceFragment.commentOrderSummary(CommentOrder.NEWEST),
                    String.valueOf(order.getSummary()));
        } finally {
            Settings.COMMENT_ORDER.resetToDefault();
        }

        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.SPONSORED_POSTS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            for (Preference row : rowsOf(controller)) {
                assertFalse("a comment order row with no Default comment order in the build",
                        row instanceof ValueRows.CommentOrderRow);
                assertFalse(Settings.DEFAULT_COMMENT_ORDER.key.equals(row.getKey()));
            }
        }
    }

    /**
     * With Default playback quality in the build, the Playback section has its switch and the list
     * of qualities right below it, which offers Auto first, says what the chosen one does, and
     * reaches the setting the way the list's own dialog sends a pick. Without the patch there's
     * neither row.
     */
    @Test
    public void thePlaybackQualityRowOffersAutoAndEachQuality() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PLAYBACK_QUALITY, PatchFamily.SPONSORED_POSTS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = new HushfacebookPreferenceFragment();
            controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
            List<Preference> rows = new ArrayList<>();
            collect(page.getPreferenceScreen(), rows);
            int toggle = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (Settings.DEFAULT_PLAYBACK_QUALITY.key.equals(rows.get(i).getKey())) toggle = i;
            }
            assertTrue("no Default playback quality switch", toggle >= 0);
            PreferenceGroup section = null;
            for (int i = 0; i < page.getPreferenceScreen().getPreferenceCount(); i++) {
                Preference top = page.getPreferenceScreen().getPreference(i);
                if (top instanceof PreferenceGroup
                        && ((PreferenceGroup) top).findPreference(Settings.DEFAULT_PLAYBACK_QUALITY.key) != null) {
                    section = (PreferenceGroup) top;
                }
            }
            assertNotNull(section);
            assertEquals("Playback", String.valueOf(section.getTitle()));
            assertEquals(4, section.getPreferenceCount());
            assertEquals("Default playback quality", String.valueOf(rows.get(toggle).getTitle()));
            assertTrue(rows.get(toggle + 1) instanceof ValueRows.PlaybackQualityRow);
            ValueRows.PlaybackQualityRow quality =
                    (ValueRows.PlaybackQualityRow) rows.get(toggle + 1);
            assertEquals(Settings.PLAYBACK_QUALITY.key, quality.getKey());
            assertEquals("Playback quality", String.valueOf(quality.getTitle()));

            List<String> entries = new ArrayList<>();
            for (CharSequence entry : quality.getEntries()) entries.add(String.valueOf(entry));
            assertEquals(Arrays.asList("Auto", "Data saver", "Up to " + L10n.isolate("480p"),
                    "Up to " + L10n.isolate("720p"), "Highest"), entries);
            List<String> values = new ArrayList<>();
            for (CharSequence value : quality.getEntryValues()) values.add(String.valueOf(value));
            List<String> names = new ArrayList<>();
            for (PlaybackQuality each : PlaybackQuality.values()) names.add(each.name());
            assertEquals(names, values);

            assertEquals("AUTO", quality.getValue());
            assertEquals("Facebook picks the quality as each video plays, from your connection.",
                    String.valueOf(quality.getSummary()));

            // A pick in the list, the way its dialog sends one.
            quality.setValue("DATA_SAVER");
            ShadowLooper.idleMainLooper();
            assertEquals(PlaybackQuality.DATA_SAVER, Settings.PLAYBACK_QUALITY.savedValue());
            assertEquals("Videos play at the lowest quality Facebook offers for each.", String.valueOf(quality.getSummary()));

            // A value set behind the row, as an import does, shows once the page syncs.
            Settings.PLAYBACK_QUALITY.save(PlaybackQuality.P720);
            page.refreshSwitches();
            assertEquals("P720", quality.getValue());
            assertEquals("Videos play at the best quality up to " + L10n.isolate("720p")
                    + " that Facebook offers for each, or the closest above.", String.valueOf(quality.getSummary()));

            // Reels and Stories follow it below until they have a quality of their own.
            ValueRows.SurfaceQualityRow reels = (ValueRows.SurfaceQualityRow) rows.get(toggle + 2);
            ValueRows.SurfaceQualityRow stories = (ValueRows.SurfaceQualityRow) rows.get(toggle + 3);
            assertEquals(Settings.REELS_PLAYBACK_QUALITY.key, reels.getKey());
            assertEquals("Reels quality", String.valueOf(reels.getTitle()));
            assertEquals(Settings.STORIES_PLAYBACK_QUALITY.key, stories.getKey());
            assertEquals("Stories quality", String.valueOf(stories.getTitle()));
            List<String> own = new ArrayList<>();
            for (CharSequence entry : reels.getEntries()) own.add(String.valueOf(entry));
            assertEquals(Arrays.asList("Same as videos", "Auto", "Data saver", "Up to " + L10n.isolate("480p"),
                    "Up to " + L10n.isolate("720p"), "Highest"), own);
            assertEquals("SAME", reels.getValue());
            assertEquals("Reels play at the playback quality above.", String.valueOf(reels.getSummary()));
            assertEquals("Video stories play at the playback quality above.", String.valueOf(stories.getSummary()));
            reels.setValue("DATA_SAVER");
            ShadowLooper.idleMainLooper();
            assertEquals(SurfaceQuality.DATA_SAVER, Settings.REELS_PLAYBACK_QUALITY.savedValue());
            assertEquals("Data saver", String.valueOf(reels.getSummary()));
            assertEquals(SurfaceQuality.SAME, Settings.STORIES_PLAYBACK_QUALITY.savedValue());
        } finally {
            Settings.PLAYBACK_QUALITY.resetToDefault();
            Settings.REELS_PLAYBACK_QUALITY.resetToDefault();
            Settings.STORIES_PLAYBACK_QUALITY.resetToDefault();
        }

        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.SPONSORED_POSTS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            for (Preference row : rowsOf(controller)) {
                assertFalse("a playback quality row with no Default playback quality in the build",
                        row instanceof ValueRows.PlaybackQualityRow || row instanceof ValueRows.SurfaceQualityRow);
                assertFalse(Settings.DEFAULT_PLAYBACK_QUALITY.key.equals(row.getKey()));
            }
        }
    }

    /**
     * Default comment order and Default playback quality start off, so a pick in one of their lists
     * turns the switch on and its row shows it on. The list's default changes nothing and leaves
     * the switch alone, a pick while it's on changes only the list, and turning the switch off
     * keeps the pick.
     */
    @Test
    public void aPickInAListTurnsItsSwitchOn() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.DEFAULT_COMMENT_ORDER, PatchFamily.PLAYBACK_QUALITY,
                PatchFamily.SPONSORED_POSTS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = new HushfacebookPreferenceFragment();
            controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
            SwitchPreference orderSwitch = (SwitchPreference) page.findPreference(Settings.DEFAULT_COMMENT_ORDER.key);
            ListPreference order = (ListPreference) page.findPreference(Settings.COMMENT_ORDER.key);
            assertFalse(orderSwitch.isChecked());

            pick(order, "FACEBOOK");
            assertFalse("Facebook's choice turned the switch on", Settings.DEFAULT_COMMENT_ORDER.savedValue());
            pick(order, "NEWEST");
            assertEquals(CommentOrder.NEWEST, Settings.COMMENT_ORDER.savedValue());
            assertTrue(Settings.DEFAULT_COMMENT_ORDER.savedValue());
            assertTrue("the switch's row doesn't show it on", orderSwitch.isChecked());

            // Off by its own row, the pick stays, and a pick with the switch on leaves it on.
            orderSwitch.setChecked(false);
            ShadowLooper.idleMainLooper();
            assertFalse(Settings.DEFAULT_COMMENT_ORDER.savedValue());
            assertEquals(CommentOrder.NEWEST, Settings.COMMENT_ORDER.savedValue());
            orderSwitch.setChecked(true);
            ShadowLooper.idleMainLooper();
            pick(order, "ALL_COMMENTS");
            assertEquals(CommentOrder.ALL_COMMENTS, Settings.COMMENT_ORDER.savedValue());
            assertTrue(Settings.DEFAULT_COMMENT_ORDER.savedValue());

            // The playback quality and its Reels and Stories lists work under one switch.
            SwitchPreference qualitySwitch = (SwitchPreference) page.findPreference(Settings.DEFAULT_PLAYBACK_QUALITY.key);
            ListPreference stories = (ListPreference) page.findPreference(Settings.STORIES_PLAYBACK_QUALITY.key);
            pick(stories, "SAME");
            assertFalse("Same as videos turned the switch on", Settings.DEFAULT_PLAYBACK_QUALITY.savedValue());
            pick(stories, "HIGHEST");
            assertEquals(SurfaceQuality.HIGHEST, Settings.STORIES_PLAYBACK_QUALITY.savedValue());
            assertTrue(Settings.DEFAULT_PLAYBACK_QUALITY.savedValue());
            assertTrue("the switch's row doesn't show it on", qualitySwitch.isChecked());
            qualitySwitch.setChecked(false);
            ShadowLooper.idleMainLooper();
            ListPreference quality = (ListPreference) page.findPreference(Settings.PLAYBACK_QUALITY.key);
            pick(quality, "P720");
            assertEquals(PlaybackQuality.P720, Settings.PLAYBACK_QUALITY.savedValue());
            assertTrue(qualitySwitch.isChecked());
            assertEquals(SurfaceQuality.HIGHEST, Settings.STORIES_PLAYBACK_QUALITY.savedValue());
        } finally {
            Settings.DEFAULT_COMMENT_ORDER.resetToDefault();
            Settings.COMMENT_ORDER.resetToDefault();
            Settings.DEFAULT_PLAYBACK_QUALITY.resetToDefault();
            Settings.PLAYBACK_QUALITY.resetToDefault();
            Settings.STORIES_PLAYBACK_QUALITY.resetToDefault();
        }
    }

    /** A pick the way a list's own dialog sends one: its change listener first, then the value if it agrees. */
    private static void pick(ListPreference row, String value) {
        Preference.OnPreferenceChangeListener listener = row.getOnPreferenceChangeListener();
        if (listener == null || listener.onPreferenceChange(row, value)) row.setValue(value);
        ShadowLooper.idleMainLooper();
    }

    /**
     * With Tag suggestions only after @ in the build, the screen has a Writing section holding its
     * one switch, which says what goes and what stays. Without the patch there's no such row.
     */
    @Test
    public void theTagSuggestionRowSitsAloneUnderWriting() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.TAG_SUGGESTIONS, PatchFamily.SPONSORED_POSTS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = new HushfacebookPreferenceFragment();
            controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
            PreferenceGroup section = null;
            for (int i = 0; i < page.getPreferenceScreen().getPreferenceCount(); i++) {
                Preference top = page.getPreferenceScreen().getPreference(i);
                if (top instanceof PreferenceGroup
                        && ((PreferenceGroup) top).findPreference(Settings.TAG_SUGGESTIONS_ONLY_AFTER_AT.key) != null) {
                    section = (PreferenceGroup) top;
                }
            }
            assertNotNull("no Tag suggestions only after @ switch", section);
            assertEquals("Writing", String.valueOf(section.getTitle()));
            assertEquals(1, section.getPreferenceCount());
            Preference toggle = section.getPreference(0);
            assertTrue(toggle instanceof SwitchPreference);
            assertEquals("Tag suggestions only after @", String.valueOf(toggle.getTitle()));
            assertEquals("Type @ before Facebook suggests someone to tag in posts or comments. Your text stays unchanged.",
                    String.valueOf(toggle.getSummary()));
        }

        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.SPONSORED_POSTS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            assertEquals("a tag suggestion row with the patch left out", -1,
                    indexOfKey(rowsOf(controller), Settings.TAG_SUGGESTIONS_ONLY_AFTER_AT.key));
        }
    }

    /**
     * The video file name's row, next to the folder, keeps the one clean template a save would use,
     * whatever is typed into it, says so in a toast when it changed what was typed, says what videos
     * and photos are named, and names both tokens in its dialog.
     */
    @Test
    public void theFileNameRowKeepsOneCleanTemplateNextToTheFolder() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(controller);
            ValueRows.FileNameRow name = null;
            int nameAt = -1;
            int folderAt = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i) instanceof ValueRows.FileNameRow) {
                    name = (ValueRows.FileNameRow) rows.get(i);
                    nameAt = i;
                }
                if (rows.get(i) instanceof ValueRows.FolderRow) folderAt = i;
            }
            assertNotNull("no file name row with a download in the build", name);
            assertEquals("the file name row isn't next to the folder", folderAt + 1, nameAt);
            assertEquals(Settings.FILENAME_TEMPLATE.key, name.getKey());
            assertEquals("Video file name", String.valueOf(name.getTitle()));
            assertFalse("the video's row names photos", name.photo);
            assertEquals("Videos are named " + L10n.isolate("FB_VID_{date}") + ".", String.valueOf(name.getSummary()));
            String message = String.valueOf(name.getDialogMessage());
            assertTrue(message, message.contains(L10n.isolate("{date}")) && message.contains(L10n.isolate("{video_id}"))
                    && message.contains(L10n.isolate("{owner}")) && message.contains(L10n.isolate("{posted}"))
                    && message.contains(L10n.isolate("FB_VID_{date}")));
            // The folder's dialog, in the same words: a Save button and a hint that says what goes in.
            assertEquals("Save", String.valueOf(name.getPositiveButtonText()));
            assertEquals("File name", String.valueOf(name.getEditText().getHint()));

            Preference.OnPreferenceChangeListener ok = name.getOnPreferenceChangeListener();
            assertFalse("a path was kept as typed", ok.onPreferenceChange(name, "../Reels/{video_id}"));
            ShadowLooper.idleMainLooper();
            assertEquals("Reels_{video_id}", name.getText());
            assertEquals("Reels_{video_id}", Settings.FILENAME_TEMPLATE.savedValue());
            assertEquals(HushfacebookPreferenceFragment.fileNameSummary("Reels_{video_id}"), String.valueOf(name.getSummary()));
            assertEquals("File name set to " + L10n.isolate("Reels_{video_id}") + ".", ShadowToast.getTextOfLatestToast());

            // A name that would be the same for every video gets the date.
            assertFalse("a name with no token was kept", ok.onPreferenceChange(name, "Clip"));
            ShadowLooper.idleMainLooper();
            assertEquals("Clip_{date}", name.getText());
            assertEquals("Clip_{date}", Settings.FILENAME_TEMPLATE.savedValue());
            assertEquals("File name set to " + L10n.isolate("Clip_{date}") + ".", ShadowToast.getTextOfLatestToast());

            ShadowToast.reset();
            assertTrue("a clean template was changed", ok.onPreferenceChange(name, "{date} {video_id}"));
            name.setText("{date} {video_id}");
            ShadowLooper.idleMainLooper();
            assertEquals("{date} {video_id}", Settings.FILENAME_TEMPLATE.savedValue());
            assertNull("a clean template raised a toast", ShadowToast.getLatestToast());

            assertFalse("an empty template was kept", ok.onPreferenceChange(name, " . "));
            ShadowLooper.idleMainLooper();
            assertEquals("FB_VID_{date}", name.getText());
            assertEquals("FB_VID_{date}", Settings.FILENAME_TEMPLATE.savedValue());
        }

        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.SPONSORED_POSTS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            for (Preference row : rowsOf(controller)) {
                assertFalse("a file name row with no download in the build",
                        row instanceof ValueRows.FileNameRow);
            }
        }
    }

    /**
     * With Download any photo in the build, the photo file name row sits under the video's, keeps
     * its own clean template, and says what photos are named; without it there's no such row.
     */
    @Test
    public void thePhotoNameRowKeepsItsOwnCleanTemplateUnderTheVideos() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PHOTO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(controller);
            List<Integer> at = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i) instanceof ValueRows.FileNameRow) at.add(i);
            }
            assertEquals("a video and a photo name row", 2, at.size());
            assertEquals("the photo's row isn't under the video's", at.get(0) + 1, (int) at.get(1));
            ValueRows.FileNameRow video = (ValueRows.FileNameRow) rows.get(at.get(0));
            ValueRows.FileNameRow photo = (ValueRows.FileNameRow) rows.get(at.get(1));
            assertFalse(video.photo);
            assertTrue(photo.photo);
            assertEquals(Settings.PHOTO_FILENAME_TEMPLATE.key, photo.getKey());
            assertEquals("Photo file name", String.valueOf(photo.getTitle()));
            assertEquals("Photos are named " + L10n.isolate("FB_IMG_{date}") + ".", String.valueOf(photo.getSummary()));
            String message = String.valueOf(photo.getDialogMessage());
            assertTrue(message, message.contains(L10n.isolate("{photo_id}")) && message.contains(L10n.isolate("{owner}"))
                    && message.contains(L10n.isolate("FB_IMG_{date}")) && !message.contains("{video_id}"));
            assertEquals("Save", String.valueOf(photo.getPositiveButtonText()));
            assertEquals("File name", String.valueOf(photo.getEditText().getHint()));

            Preference.OnPreferenceChangeListener ok = photo.getOnPreferenceChangeListener();
            assertFalse("a video's name was kept", ok.onPreferenceChange(photo, "FB_VID_{photo_id}"));
            ShadowLooper.idleMainLooper();
            assertEquals("FB_IMG_{photo_id}", photo.getText());
            assertEquals("FB_IMG_{photo_id}", Settings.PHOTO_FILENAME_TEMPLATE.savedValue());
            assertEquals(HushfacebookPreferenceFragment.photoNameSummary("FB_IMG_{photo_id}"), String.valueOf(photo.getSummary()));
            assertEquals("File name set to " + L10n.isolate("FB_IMG_{photo_id}") + ".", ShadowToast.getTextOfLatestToast());
            assertEquals("the video's template moved", "FB_VID_{date}", Settings.FILENAME_TEMPLATE.savedValue());

            assertFalse("a name with no token was kept", ok.onPreferenceChange(photo, "Shot"));
            ShadowLooper.idleMainLooper();
            assertEquals("Shot_{date}", Settings.PHOTO_FILENAME_TEMPLATE.savedValue());

            ShadowToast.reset();
            assertTrue("a clean template was changed", ok.onPreferenceChange(photo, "{date} {photo_id}"));
            assertNull("a clean template raised a toast", ShadowToast.getLatestToast());

            assertFalse("an empty template was kept", ok.onPreferenceChange(photo, " . "));
            ShadowLooper.idleMainLooper();
            assertEquals("FB_IMG_{date}", Settings.PHOTO_FILENAME_TEMPLATE.savedValue());

            String preview = ValueRows.FileNameRow.previewName("Shot {photo_id}", new java.util.Date(0), true);
            assertEquals("Shot 123456.jpg", preview);
        }

        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            for (Preference row : rowsOf(controller)) {
                assertFalse("a photo name row with no photo download in the build",
                        row instanceof ValueRows.FileNameRow && ((ValueRows.FileNameRow) row).photo);
            }
        }
    }

    /**
     * Hide seen posts' rows. Keep seen posts hidden for follows the switch, Forget seen posts works with the
     * switch off too, and turning the switch off forgets the list and its file.
     */
    @Test
    public void turningHideSeenPostsOffForgetsTheListAndForgetWorksEitherWay() throws java.io.IOException {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.SEEN_POSTS);
        java.io.File file = java.io.File.createTempFile("seen-posts", ".txt");
        SeenPostsForTests.useStore(file);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(controller);
            int switchAt = indexOfKey(rows, Settings.HIDE_SEEN_POSTS.key);
            assertTrue("no Hide seen posts switch", switchAt >= 0);
            Preference seen = rows.get(switchAt);
            Preference keep = rows.get(switchAt + 1);
            Preference forget = rows.get(switchAt + 2);
            assertEquals(Settings.SEEN_POSTS_KEEP.key, keep.getKey());
            assertEquals("Forget seen posts", String.valueOf(forget.getTitle()));
            assertFalse("the keep time is open with the switch off", keep.isEnabled());
            assertEquals("Keep seen posts hidden for", String.valueOf(keep.getTitle()));
            String greyed = String.valueOf(keep.getSummary());
            assertTrue("the greyed keep time doesn't say why: " + greyed,
                    greyed.contains("Turn on") && greyed.contains(SwitchLabels.title(Settings.HIDE_SEEN_POSTS))
                            && greyed.endsWith("to use this."));
            assertTrue("Forget seen posts is greyed out with the switch off", forget.isEnabled());

            SeenPostsForTests.rememberAndWrite("left from before");
            assertTrue(forget.getOnPreferenceClickListener().onPreferenceClick(forget));
            ShadowLooper.idleMainLooper();
            assertEquals("Forget seen posts left the list with the switch off", 0, SeenPostsForTests.size());
            assertFalse(file.exists());

            assertTrue(seen.getOnPreferenceChangeListener().onPreferenceChange(seen, true));
            assertTrue(keep.isEnabled());
            assertFalse("the open keep time still says to turn the switch on",
                    String.valueOf(keep.getSummary()).contains("Turn on"));
            assertTrue(forget.isEnabled());
            SeenPostsForTests.rememberAndWrite("seen with it on");
            assertTrue(file.exists());
            assertTrue(seen.getOnPreferenceChangeListener().onPreferenceChange(seen, false));
            assertFalse(keep.isEnabled());
            assertTrue(forget.isEnabled());
            assertEquals("turning the switch off kept the list", 0, SeenPostsForTests.size());
            assertFalse("turning the switch off kept the file", file.exists());
        } finally {
            SeenPostsForTests.forgetStore();
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static List<Preference> rowsOf(ActivityController<Activity> controller) {
        HushfacebookPreferenceFragment fragment = new HushfacebookPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction()
                .add(android.R.id.content, fragment)
                .commitNow();
        List<Preference> rows = new ArrayList<>();
        collect(fragment.getPreferenceScreen(), rows);
        assertFalse("the screen has no rows", rows.isEmpty());
        return rows;
    }

    private static void assertReadable(List<Preference> rows) {
        for (Preference row : rows) {
            CharSequence title = row.getTitle();
            assertTrue("a row has no title: " + row.getClass().getSimpleName() + " " + row.getKey(),
                    title != null && title.toString().trim().length() > 0);

            TypedArray styled = row.getContext().obtainStyledAttributes(
                    new int[]{android.R.attr.textColorPrimary});
            try {
                ColorStateList primary = styled.getColorStateList(0);
                assertTrue("no primary text color for " + title, primary != null);
                ScreenColors page = ScreenColors.shown;
                if (page == null) {
                    assertTrue("\"" + title + "\" is drawn dark on the black page",
                            Color.luminance(primary.getDefaultColor()) > 0.5f);
                } else {
                    // The Material You theme's page is the palette's, dark or light as the phone
                    // is. The theme's own text, before a row paints it, still has to read on it.
                    int blended = blend(primary.getDefaultColor(), page.background);
                    assertTrue("\"" + title + "\" is " + Integer.toHexString(blended) + " on the page's "
                                    + Integer.toHexString(page.background),
                            ScreenColorsTest.contrast(blended, page.background) >= ScreenColorsTest.TEXT);
                }
            } finally {
                styled.recycle();
            }
        }
    }

    private static void collect(PreferenceGroup group, List<Preference> rows) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference preference = group.getPreference(i);
            if (preference instanceof PreferenceGroup) {
                collect((PreferenceGroup) preference, rows);
            } else {
                rows.add(preference);
            }
        }
    }

    /** A translucent text colour as it lands on an opaque background. */
    private static int blend(int color, int background) {
        int alpha = color >>> 24;
        int[] out = new int[3];
        for (int shift = 16, i = 0; i < 3; shift -= 8, i++) {
            int top = (color >> shift) & 0xFF;
            int bottom = (background >> shift) & 0xFF;
            out[i] = (top * alpha + bottom * (255 - alpha) + 127) / 255;
        }
        return 0xFF000000 | (out[0] << 16) | (out[1] << 8) | out[2];
    }

    /** Issue #34: the AMOLED row under Patched names the Background colour the patch was given. */
    @Test
    public void theAmoledRowNamesAPickedBackgroundColour() {
        assertEquals("Dark mode uses black instead of dark gray. Turn on dark mode in Facebook to see it.",
                HushfacebookPreferenceFragment.amoledSummary(Color.BLACK));
        assertEquals("Dark mode uses " + L10n.isolate("#0D1117")
                        + " instead of dark gray. Turn on dark mode in Facebook to see it.",
                HushfacebookPreferenceFragment.amoledSummary(0xFF0D1117));
    }
}
