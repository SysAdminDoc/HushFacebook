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

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * The one list the settings screen and the diagnostic report read to say what Pause turns off
 * and what it can't reach. It has to cover every switch, every patch and every status flag, or
 * one of them goes unmentioned.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PatchFamilyTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        PauseForTests.resume();
        Settings.HIDE_PROMOTED_POSTS.resetToDefault();
        Settings.HIDE_SPONSORED_POSTS.resetToDefault();
        Settings.HIDE_REEL_FOLLOW_BUTTON.resetToDefault();
        Settings.HIDE_MENU_ALSO_FROM_META.resetToDefault();
        Settings.DOWNLOAD_VIDEOS.resetToDefault();
        Settings.CLIPBOARD_DOWNLOAD.resetToDefault();
        HookStatus.clear();
    }

    /**
     * A switch is a family's, or the settings entry's own (the release check), and never both: a
     * switch in neither list goes unmentioned by the screen and the tests that hold Pause to it.
     */
    @Test
    public void everySwitchBelongsToExactlyOneFamily() {
        Map<BooleanSetting, String> owners = new HashMap<>();
        for (PatchFamily family : PatchFamily.values()) {
            for (BooleanSetting setting : family.switches) {
                String earlier = owners.put(setting, family.name());
                assertNull(setting.key + " belongs to " + earlier + " and to " + family, earlier);
            }
        }
        for (BooleanSetting setting : PatchFamily.ENTRY_SWITCHES) {
            String earlier = owners.put(setting, "the settings entry");
            assertNull(setting.key + " belongs to " + earlier + " and to the settings entry", earlier);
        }
        for (BooleanSetting setting : PatchFamily.DOWNLOAD_SWITCHES) {
            String earlier = owners.put(setting, "the download patches");
            assertNull(setting.key + " belongs to " + earlier + " and to the download patches", earlier);
        }
        assertEquals(new HashSet<>(PausedHooksTest.settingsSwitches()), owners.keySet());
        assertTrue("the release check is the settings entry's own",
                PatchFamily.ENTRY_SWITCHES.contains(Settings.CHECK_FOR_RELEASES));
        assertTrue("saves other apps can open are every download's",
                PatchFamily.DOWNLOAD_SWITCHES.contains(Settings.DOWNLOAD_COMPATIBLE));
        assertEquals(EnumSet.of(PatchFamily.STORY_DOWNLOAD, PatchFamily.REEL_DOWNLOAD, PatchFamily.VIDEO_DOWNLOAD,
                PatchFamily.PHOTO_DOWNLOAD),
                PatchFamily.DOWNLOADS);
    }

    /**
     * The switches every download shares get one line of their own when a download patch is in:
     * what each is set to, and never that downloads are off, since they shape a save rather than
     * run a hook. A build with no download patch has no such line.
     */
    @Test
    public void theReportSaysWhatTheSharedDownloadSwitchesAreSetTo() {
        try {
            List<String> lines = PatchFamily.reportLines(EnumSet.of(PatchFamily.STORY_DOWNLOAD), false);
            assertEquals("Download any story: on (hushfacebook_download_stories=on)", lines.get(0));
            assertEquals("Every download patch: hushfacebook_download_compatible=off", lines.get(1));
            assertTrue(lines.get(2), lines.get(2).startsWith("not in this build: "));

            Settings.DOWNLOAD_COMPATIBLE.save(true);
            assertEquals("Every download patch: hushfacebook_download_compatible=on",
                    PatchFamily.reportLines(EnumSet.of(PatchFamily.VIDEO_DOWNLOAD), false).get(1));
            assertEquals("Every download patch: off while paused (saved hushfacebook_download_compatible=on)",
                    PatchFamily.reportLines(EnumSet.of(PatchFamily.REEL_DOWNLOAD), true).get(1));

            for (String line : PatchFamily.reportLines(EnumSet.of(PatchFamily.SPONSORED_POSTS), false)) {
                assertFalse(line, line.contains("hushfacebook_download_compatible"));
            }
        } finally {
            Settings.DOWNLOAD_COMPATIBLE.resetToDefault();
        }
    }

    @Test
    public void onlyTheFirstReelWaitsDoesNotClaimTapToPlayIsOn() {
        try {
            Settings.TAP_TO_PLAY.save(false);
            Settings.TAP_TO_PLAY_REELS_AFTER_FIRST.save(true);
            String line = PatchFamily.reportLines(EnumSet.of(PatchFamily.TAP_TO_PLAY), false).get(0);
            assertTrue(line, line.startsWith(FamilyNames.TAP_TO_PLAY + ": disabled by its switch ("));
            Settings.TAP_TO_PLAY.save(true);
            assertTrue(PatchFamily.reportLines(EnumSet.of(PatchFamily.TAP_TO_PLAY), false).get(0)
                    .startsWith(FamilyNames.TAP_TO_PLAY + ": on ("));
        } finally {
            Settings.TAP_TO_PLAY.resetToDefault();
            Settings.TAP_TO_PLAY_REELS_AFTER_FIRST.resetToDefault();
        }
    }

    @Test
    public void marketplaceExtrasDoNotClaimTheDisabledModeIsOn() {
        try {
            Settings.MARKETPLACE_ONLY.save(false);
            Settings.MARKETPLACE_QUIET_NOTIFICATIONS.save(true);
            Settings.MARKETPLACE_SKIP_FEED_PREFETCH.save(true);
            String line = PatchFamily.reportLines(EnumSet.of(PatchFamily.MARKETPLACE_ONLY), false).get(0);
            assertTrue(line, line.startsWith("Marketplace only: disabled by its switch ("));
            Settings.MARKETPLACE_ONLY.save(true);
            assertTrue(PatchFamily.reportLines(EnumSet.of(PatchFamily.MARKETPLACE_ONLY), false).get(0)
                    .startsWith("Marketplace only: on ("));
        } finally {
            Settings.MARKETPLACE_ONLY.resetToDefault();
            Settings.MARKETPLACE_QUIET_NOTIFICATIONS.resetToDefault();
            Settings.MARKETPLACE_SKIP_FEED_PREFETCH.resetToDefault();
        }
    }

    @Test
    public void wholeWordModeDoesNotEnableTheWordFilterByItself() {
        try {
            Settings.HIDE_POSTS_WITH_WORDS.save(false);
            Settings.POST_WORDS_WHOLE_WORDS.save(true);
            String line = PatchFamily.reportLines(EnumSet.of(PatchFamily.POST_WORDS), false).get(0);
            assertTrue(line, line.startsWith(FamilyNames.POST_WORDS + ": disabled by its switch ("));
            assertTrue(line, line.contains(Settings.POST_WORDS_WHOLE_WORDS.key + "=on"));
            Settings.HIDE_POSTS_WITH_WORDS.save(true);
            assertTrue(PatchFamily.reportLines(EnumSet.of(PatchFamily.POST_WORDS), false).get(0)
                    .startsWith(FamilyNames.POST_WORDS + ": on ("));
            assertTrue(PatchFamily.reportLines(EnumSet.of(PatchFamily.POST_WORDS), true).get(0)
                    .startsWith(FamilyNames.POST_WORDS + ": disabled while paused (saved "));
        } finally {
            Settings.HIDE_POSTS_WITH_WORDS.resetToDefault();
            Settings.POST_WORDS_WHOLE_WORDS.resetToDefault();
        }
    }

    @Test
    public void everyStatusFlagBelongsToExactlyOneFamily() {
        Set<String> flags = new TreeSet<>();
        for (Method method : SettingsStatus.class.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers)
                    && method.getReturnType() == boolean.class && method.getParameterCount() == 0) {
                flags.add(method.getName());
            }
        }
        Set<String> named = new TreeSet<>();
        for (PatchFamily family : PatchFamily.values()) {
            assertTrue("two families share " + family.statusMethod, named.add(family.statusMethod));
        }
        assertEquals(flags, named);
    }

    /** The names are Morphe Manager's, so a report and the patch list say the same thing. */
    @Test
    public void everyPatchButTheSettingsEntryIsAFamily() throws Exception {
        JSONArray patches = new JSONObject(new String(Files.readAllBytes(patchesList().toPath()),
                StandardCharsets.UTF_8)).getJSONArray("patches");
        Set<String> listed = new TreeSet<>();
        for (int i = 0; i < patches.length(); i++) listed.add(patches.getJSONObject(i).getString("name"));
        assertTrue("the settings entry left the patch list", listed.remove("Hushfacebook settings"));

        Set<String> families = new TreeSet<>();
        for (PatchFamily family : PatchFamily.values()) families.add(family.patchName);
        assertEquals(listed, families);
    }

    /**
     * The overview and the report name the default patches a build lacks from this list, so it has
     * to be Morphe Manager's own default selection: a new default patch fails here until it's listed.
     */
    @Test
    public void theDefaultSelectionIsTheOneManagerMakes() throws Exception {
        JSONArray patches = new JSONObject(new String(Files.readAllBytes(patchesList().toPath()),
                StandardCharsets.UTF_8)).getJSONArray("patches");
        Set<String> selected = new TreeSet<>();
        for (int i = 0; i < patches.length(); i++) {
            JSONObject patch = patches.getJSONObject(i);
            if (patch.getBoolean("use")) selected.add(patch.getString("name"));
        }
        assertTrue("the settings entry left the default selection", selected.remove("Hushfacebook settings"));

        Set<String> listed = new TreeSet<>();
        for (PatchFamily family : PatchFamily.DEFAULT_SELECTION) listed.add(family.patchName);
        assertEquals(selected, listed);
        // The check can fail: an opt-in patch isn't in either list.
        assertFalse(selected.contains(PatchFamily.AMOLED_THEME.patchName));
        assertFalse(PatchFamily.DEFAULT_SELECTION.contains(PatchFamily.AMOLED_THEME));
    }

    /**
     * The patches that joined the default selection from the opt-in list start with every switch
     * off, so a build patched with the defaults acts as Facebook does until a switch is turned on.
     * Share sheet items and Accent color have a choice instead, and it starts as Facebook's own.
     */
    @Test
    public void thePatchesThatJoinedTheDefaultSelectionStartWithEverySwitchOff() {
        Set<PatchFamily> joined = EnumSet.of(PatchFamily.STORIES_TRAY, PatchFamily.FEED_REELS,
                PatchFamily.RETURN_REFRESH, PatchFamily.SEEN_POSTS, PatchFamily.STORY_AUTO_ADVANCE,
                PatchFamily.STORY_SEEN, PatchFamily.REEL_DECLUTTER, PatchFamily.REEL_WATCH_HISTORY,
                PatchFamily.DOUBLE_TAP_LIKE, PatchFamily.REEL_HOLD, PatchFamily.DEFAULT_COMMENT_ORDER,
                PatchFamily.COMMENT_SHEET_OPTIONS, PatchFamily.TAG_SUGGESTIONS, PatchFamily.TAP_TO_PLAY,
                PatchFamily.PLAYBACK_QUALITY, PatchFamily.PICTURE_IN_PICTURE, PatchFamily.HDR_BRIGHTNESS,
                PatchFamily.SYSTEM_FONT, PatchFamily.SYSTEM_EMOJI, PatchFamily.HAPTICS,
                PatchFamily.SCREEN_TRANSITIONS, PatchFamily.VIDEO_DOWNLOAD, PatchFamily.START_TAB,
                PatchFamily.REELS_TAB, PatchFamily.META_UPSELLS, PatchFamily.SHARE_SHEET_ITEMS,
                PatchFamily.ANALYTICS_UPLOADS, PatchFamily.SCREENSHOTS, PatchFamily.SCREENSHOT_DETECTION,
                PatchFamily.TYPING_INDICATOR, PatchFamily.READ_RECEIPTS, PatchFamily.ACCENT_COLOR);
        assertEquals(32, joined.size());
        for (PatchFamily family : joined) {
            assertTrue(family.patchName + " isn't in the default selection",
                    PatchFamily.DEFAULT_SELECTION.contains(family));
            for (BooleanSetting setting : family.switches) {
                assertFalse(family.patchName + ": " + setting.key + " starts on", setting.defaultValue);
            }
        }
        assertEquals("", Settings.HIDDEN_SHARE_ITEMS.defaultValue);
        assertEquals(app.morphe.extension.facebook.theme.AccentColor.Preset.FACEBOOK,
                Settings.ACCENT_COLOR.defaultValue);
    }

    /**
     * A build that lacks default patches names them, in the order the report lists families, and
     * one with every default patch names none. Opt-in patches left out are never named.
     */
    @Test
    public void theMissingDefaultsAreTheDefaultPatchesABuildLacks() {
        Set<PatchFamily> build = EnumSet.allOf(PatchFamily.class);
        assertEquals(Collections.emptyList(), PatchFamily.missingDefaults(build));
        build.remove(PatchFamily.AMOLED_THEME);
        build.remove(PatchFamily.MATERIAL_YOU_THEME);
        assertEquals(Collections.emptyList(), PatchFamily.missingDefaults(build));
        for (String line : PatchFamily.reportLines(build, false)) {
            assertFalse(line, line.startsWith("left out of Manager's default selection"));
        }

        build.remove(PatchFamily.SPONSORED_REELS);
        build.remove(PatchFamily.SPONSORED_POSTS);
        assertEquals(Arrays.asList("Hide sponsored posts", "Hide sponsored reels"), PatchFamily.missingDefaults(build));
        List<String> lines = PatchFamily.reportLines(build, false);
        assertEquals("left out of Manager's default selection: Hide sponsored posts, Hide sponsored reels",
                lines.get(lines.size() - 1));
        assertEquals("not in this build: Hide sponsored posts, Hide sponsored reels, AMOLED black theme, "
                + "Material You theme", lines.get(lines.size() - 2));
    }

    /** The new line goes through the redactor like the rest of the section and comes out whole. */
    @Test
    public void theExportCarriesTheMissingDefaultsWhole() {
        Set<PatchFamily> build = EnumSet.allOf(PatchFamily.class);
        build.remove(PatchFamily.RESTORE_TRUST);
        build.remove(PatchFamily.INSTALL_BESIDE_META_APPS);
        PatchFamily.inBuildForTests = build;
        LogBufferManager.registerReportSection(PatchFamily.REPORT);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);

        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("\nleft out of Manager's default selection: Restore screens on re-signed "
                + "builds, Install beside Meta's apps\n"));
    }

    @Test
    public void marketplaceIsIncludedByDefaultWithItsSettingsAndNotificationHooks() throws Exception {
        JSONArray patches = new JSONObject(new String(Files.readAllBytes(patchesList().toPath()), StandardCharsets.UTF_8))
                .getJSONArray("patches");
        JSONObject mode = null;
        for (int i = 0; i < patches.length(); i++) {
            if ("Marketplace only".equals(patches.getJSONObject(i).getString("name"))) mode = patches.getJSONObject(i);
        }
        assertNotNull(mode);
        assertTrue(mode.getBoolean("use"));
        JSONArray required = mode.getJSONArray("dependencies");
        Set<String> dependencies = new HashSet<>();
        for (int i = 0; i < required.length(); i++) dependencies.add(required.getString(i));
        assertTrue(dependencies.containsAll(Arrays.asList("Hushfacebook settings", "Hushfacebook in the Menu",
                "Open on a chosen tab", "Block promotional notifications")));
    }

    @Test
    public void aPatchWithNoSwitchSaysWhatOfItStaysIn() {
        for (PatchFamily family : PatchFamily.values()) {
            if (family.switches.isEmpty() && family.choice == null) {
                assertNotNull(family.patchName + " has no switch, so Pause can't reach it", family.staysWhilePaused);
            }
        }
    }

    @Test
    public void theStaysRowNamesWhatPauseCantReach() {
        assertNull("a build of switches alone has nothing that stays in",
                PatchFamily.staysWhilePausedSummary(EnumSet.of(PatchFamily.SPONSORED_POSTS, PatchFamily.EXTERNAL_BROWSER)));
        // Each item names the patch Morphe Manager lists it under, so the reader knows which one
        // to leave out.
        assertEquals("The block on downloading ads in the background (" + L10n.isolate("Block background ad prefetch")
                        + "). It was set when you patched, so Pause can't turn it off. To rule it out, patch again "
                        + "and leave out that patch.",
                PatchFamily.staysWhilePausedSummary(EnumSet.of(PatchFamily.AD_PREFETCH)));
        // Every download asks its switch before it goes in, so a pause takes them out whole.
        assertNull(PatchFamily.staysWhilePausedSummary(EnumSet.of(PatchFamily.REEL_DOWNLOAD, PatchFamily.STORY_DOWNLOAD,
                PatchFamily.VIDEO_DOWNLOAD)));
        // Alone, a family's text is followed by "It was set", so a text naming several parts still
        // has to be one thing. 4a7bba9 made the Reels one plural and this sentence stopped reading.
        assertEquals("The part of the Reels ad blocking built in when you patched (" + L10n.isolate("Hide sponsored reels")
                        + "). It was set when you patched, so Pause can't turn it off. To rule it out, patch again "
                        + "and leave out that patch.",
                PatchFamily.staysWhilePausedSummary(EnumSet.of(PatchFamily.SPONSORED_REELS)));
        assertEquals("The part of the Reels ad blocking built in when you patched (" + L10n.isolate("Hide sponsored reels")
                        + ") and the block on reports of ad screenshots and app installs ("
                        + L10n.isolate("Block ad telemetry") + "). They were set "
                        + "when you patched, so Pause can't turn them off. To rule one out, patch again and leave out "
                        + "the patch in brackets after it.",
                PatchFamily.staysWhilePausedSummary(EnumSet.of(PatchFamily.SPONSORED_REELS, PatchFamily.AD_TELEMETRY,
                        PatchFamily.SPONSORED_POSTS, PatchFamily.STORY_DOWNLOAD)));

        String everything = PatchFamily.staysWhilePausedSummary(EnumSet.allOf(PatchFamily.class));
        for (PatchFamily family : PatchFamily.values()) {
            if (family.staysWhilePaused == null) continue;
            assertTrue(family.patchName + " is missing from: " + everything,
                    everything.toLowerCase().contains(family.staysWhilePaused.toLowerCase()));
            assertTrue(family.patchName + " isn't named in: " + everything,
                    everything.contains("(" + L10n.isolate(family.patchName) + ")"));
        }
    }

    @Test
    public void theReportSaysWhatASwitchRunsAndWhatStaysIn() {
        Set<PatchFamily> build = EnumSet.of(PatchFamily.SPONSORED_POSTS, PatchFamily.SPONSORED_REELS,
                PatchFamily.AD_PREFETCH);
        Settings.HIDE_PROMOTED_POSTS.save(false);

        List<String> running = PatchFamily.reportLines(build, false);
        assertEquals(Arrays.asList(
                "Hide sponsored posts: on (hushfacebook_hide_sponsored_posts=on, hushfacebook_hide_promoted_posts=off)",
                "Hide sponsored reels: on (hushfacebook_hide_sponsored_reels=on); stays in while paused: "
                        + "the part of the Reels ad blocking built in when you patched",
                "Block background ad prefetch: no switch, stays in while paused: the block on downloading ads in "
                        + "the background",
                "not in this build: Hide suggested and promoted posts, Hide Stories tray, Hide Reels in the feed, "
                        + "Block background-return feed refresh, Hide AI-detected posts, Hide posts by words, Hide "
                        + "post prompts, Hide seen posts, Hide Meta AI questions under posts, Keep post dates, Turn "
                        + "off auto-translation, Hide the Feeds header, Hide sponsored stories, Hide suggested "
                        + "stories, Stop Story auto-advance, View stories anonymously, Hide sponsored search results, "
                        + "Hide sponsored profile posts, Hide sponsored Marketplace listings, Block Instant Games "
                        + "ads, Hide affiliate product links, Clean up Reels, Hide reel interest prompts, Don't send "
                        + "reel watch history, Turn off double tap to like, Keep the reel speed, Hold a reel for 2x, "
                        + "Default comment order, Hide Meta AI comment summaries, Comment sheet options, Tag "
                        + "suggestions only after @, Tap to play, Resume long videos, Default playback quality, "
                        + "Picture-in-picture, Turn off HDR brightness, Keep the progress bar, Use the system font, "
                        + "Use the phone's emoji, Turn off haptics, Turn off screen transitions, Unlock app icons, Open links in "
                        + "external browser, Sanitize sharing links, Stop update prompts, Download any story, "
                        + "Download any reel, Download any video, Download any photo, Open on a chosen tab, Following "
                        + "feed on Home, Marketplace only, Show View profile on Marketplace sellers, Hide the Reels "
                        + "tab, Hide the Reels tab dot, Hide tab badges, Hide tabs, Tab bar at the bottom, Force dark "
                        + "mode, Hide the Get Messenger card, Clean up Facebook's chat list, Open Messenger from the "
                        + "top bar, Hide Menu promotions, Hide Meta upsells, Share sheet items, Hide Meta AI in search, "
                        + "Hold back analytics uploads, Allow screenshots, Block screenshot detection, Hide typing indicator, "
                        + "Hide read receipts, Send chat photos and videos at original quality, Block promotional "
                        + "notifications, Block ad telemetry, Disable Audience Network, AMOLED black theme, Material "
                        + "You theme, Accent color, Restore screens on re-signed builds, Start on x86 devices, "
                        + "Install beside Meta's apps, Disable Play Store updates, Hushfacebook in the Menu",
                "left out of Manager's default selection: Hide suggested and promoted posts, Hide Stories tray, Hide "
                        + "Reels in the feed, Block background-return feed refresh, Hide AI-detected posts, Hide "
                        + "posts by words, Hide post prompts, Hide seen posts, Hide Meta AI questions under posts, "
                        + "Keep post dates, Turn off auto-translation, Hide the Feeds header, Hide sponsored stories, "
                        + "Hide suggested stories, Stop Story auto-advance, View stories anonymously, Hide sponsored "
                        + "search results, Hide sponsored profile posts, Hide sponsored Marketplace listings, Block "
                        + "Instant Games ads, Hide affiliate product links, Clean up Reels, Hide reel interest "
                        + "prompts, Don't send reel watch history, Turn off double tap to like, Keep the reel speed, "
                        + "Hold a reel for 2x, Default comment order, Hide Meta AI comment summaries, Comment sheet "
                        + "options, Tag suggestions only after @, Tap to play, Resume long videos, Default playback "
                        + "quality, Picture-in-picture, Turn off HDR brightness, Keep the progress bar, Use the "
                        + "system font, Use the phone's emoji, Turn off haptics, Turn off screen transitions, Unlock app icons, Open "
                        + "links in external browser, Sanitize sharing links, Stop update prompts, Download any "
                        + "story, Download any reel, Download any video, Download any photo, Open on a chosen tab, "
                        + "Following feed on Home, Marketplace only, Show View profile on Marketplace sellers, Hide "
                        + "the Reels tab, Hide the Reels tab dot, Hide tab badges, Hide tabs, Tab bar at the bottom, "
                        + "Force dark mode, Hide the Get Messenger card, Clean up Facebook's chat list, Open "
                        + "Messenger from the top bar, Hide Menu promotions, Hide Meta upsells, Share sheet items, Hide "
                        + "Meta AI in search, Hold back analytics uploads, Allow screenshots, Block screenshot detection, Hide "
                        + "typing indicator, Hide read receipts, Send chat photos and videos at original quality, "
                        + "Block promotional notifications, Block ad telemetry, Disable Audience Network, Accent "
                        + "color, Restore screens on re-signed builds, Start on x86 devices, Install beside Meta's "
                        + "apps, Hushfacebook in the Menu"),
                running);
        // Clean up Reels has six switches, all off to start, and the report names each one.
        Settings.HIDE_REEL_FOLLOW_BUTTON.save(true);
        assertEquals("Clean up Reels: on (hushfacebook_hide_reel_chips=off, hushfacebook_hide_reel_follow_button=on, "
                        + "hushfacebook_hide_reel_social_footer=off, hushfacebook_hide_reel_threads_cards=off, "
                        + "hushfacebook_reel_clean_mode=off, hushfacebook_play_reels_once=off)",
                PatchFamily.reportLines(EnumSet.of(PatchFamily.REEL_DECLUTTER), false).get(0));
        // Share sheet items is set by its list, and its group switch is saved beside it.
        Settings.HIDE_SHARE_GROUP_BUTTONS.save(true);
        assertEquals("Share sheet items: set by its list (hushfacebook_hidden_share_items=, "
                        + "hushfacebook_hide_share_group_buttons=on)",
                PatchFamily.reportLines(EnumSet.of(PatchFamily.SHARE_SHEET_ITEMS), false).get(0));
        assertEquals("Share sheet items: disabled while paused (saved hushfacebook_hidden_share_items=, "
                        + "hushfacebook_hide_share_group_buttons=on)",
                PatchFamily.reportLines(EnumSet.of(PatchFamily.SHARE_SHEET_ITEMS), true).get(0));
        Settings.HIDE_SHARE_GROUP_BUTTONS.resetToDefault();
        // The reel button has a switch now, so the report says what it's set to.
        assertEquals("Download any reel: on (hushfacebook_download_reels=on)",
                PatchFamily.reportLines(EnumSet.of(PatchFamily.REEL_DOWNLOAD), false).get(0));
        // So has the video menu's item, and a pause takes it out whole.
        // Its copied-link offer is an option of that item: on alone, it doesn't turn the patch on.
        Settings.DOWNLOAD_VIDEOS.save(true);
        assertEquals("Download any video: on (hushfacebook_download_videos=on, hushfacebook_clipboard_download=off)",
                PatchFamily.reportLines(EnumSet.of(PatchFamily.VIDEO_DOWNLOAD), false).get(0));
        assertEquals("Download any video: disabled while paused (saved hushfacebook_download_videos=on, "
                        + "hushfacebook_clipboard_download=off)",
                PatchFamily.reportLines(EnumSet.of(PatchFamily.VIDEO_DOWNLOAD), true).get(0));
        Settings.DOWNLOAD_VIDEOS.save(false);
        Settings.CLIPBOARD_DOWNLOAD.save(true);
        assertEquals("Download any video: disabled by its switch (hushfacebook_download_videos=off, "
                        + "hushfacebook_clipboard_download=on)",
                PatchFamily.reportLines(EnumSet.of(PatchFamily.VIDEO_DOWNLOAD), false).get(0));
        Settings.DOWNLOAD_VIDEOS.resetToDefault();
        Settings.CLIPBOARD_DOWNLOAD.resetToDefault();
        // The Menu's two groups and its Muse card have a switch each, and the report names all three.
        Settings.HIDE_MENU_ALSO_FROM_META.save(false);
        assertEquals("Hide Menu promotions: on (hushfacebook_hide_menu_upgrades=on, "
                        + "hushfacebook_hide_menu_also_from_meta=off, hushfacebook_hide_menu_muse=on)",
                PatchFamily.reportLines(EnumSet.of(PatchFamily.MENU_PROMOTIONS), false).get(0));
        // Every notification switch starts off, so the patch reads off until one of them is turned on.
        assertEquals("Block promotional notifications: disabled by its switch ("
                        + "hushfacebook_block_trending_video_notifications=off, hushfacebook_block_memory_notifications=off, "
                        + "hushfacebook_block_birthday_notifications=off, hushfacebook_block_highlight_notifications=off, "
                        + "hushfacebook_block_people_you_may_know_notifications=off, "
                        + "hushfacebook_block_nearby_notifications=off, "
                        + "hushfacebook_block_account_setup_notifications=off, "
                        + "hushfacebook_block_group_activity_notifications=off, hushfacebook_block_event_notifications=off, "
                        + "hushfacebook_block_live_video_notifications=off, hushfacebook_block_reaction_notifications=off, "
                        + "hushfacebook_notification_quiet_hours=off)",
                PatchFamily.reportLines(EnumSet.of(PatchFamily.PROMO_NOTIFICATIONS), false).get(0));
        // Quiet hours only narrows the kinds that are on, so on its own the patch still reads off.
        Settings.NOTIFICATION_QUIET_HOURS.save(true);
        assertTrue(PatchFamily.reportLines(EnumSet.of(PatchFamily.PROMO_NOTIFICATIONS), false).get(0)
                .startsWith("Block promotional notifications: disabled by its switch ("));
        Settings.BLOCK_EVENT_NOTIFICATIONS.save(true);
        assertTrue(PatchFamily.reportLines(EnumSet.of(PatchFamily.PROMO_NOTIFICATIONS), false).get(0)
                .startsWith("Block promotional notifications: on ("));
        Settings.NOTIFICATION_QUIET_HOURS.resetToDefault();
        Settings.BLOCK_EVENT_NOTIFICATIONS.resetToDefault();

        List<String> paused = PatchFamily.reportLines(build, true);
        assertEquals("Hide sponsored posts: disabled while paused (saved "
                + "hushfacebook_hide_sponsored_posts=on, hushfacebook_hide_promoted_posts=off)", paused.get(0));
        assertEquals("Hide sponsored reels: disabled while paused (saved "
                + "hushfacebook_hide_sponsored_reels=on); stays in while paused: the part of the Reels ad "
                + "blocking built in when you patched",
                paused.get(1));
        assertEquals("a patch with no switch reads the same paused", running.get(2), paused.get(2));
        assertEquals(running.get(3), paused.get(3));

        // Both of a family's switches off is the family off; one of them on keeps it on above.
        Settings.HIDE_SPONSORED_POSTS.save(false);
        assertEquals("Hide sponsored posts: disabled by its switch (hushfacebook_hide_sponsored_posts=off, "
                + "hushfacebook_hide_promoted_posts=off)", PatchFamily.reportLines(build, false).get(0));
    }

    /**
     * A paused export marks the Hook status lines of the families a switch runs, and leaves the
     * ones with no switch alone: they keep working, and a mark would tell a reader otherwise.
     */
    @Test
    public void aPausedExportMarksOnlyTheFamiliesASwitchRuns() {
        HookStatus.clear();
        PatchFamily.registerDiagnostics();
        HookStatus.invoked(FamilyNames.SPONSORED_POSTS);
        HookStatus.invoked(FamilyNames.AMOLED_THEME);

        List<String> lines = HookStatus.report(" (paused)");
        assertTrue(String.join("\n", lines),
                lines.contains("Hide sponsored posts: invoked 1, 0 found, 0 missing (paused)"));
        assertTrue(String.join("\n", lines), lines.contains("AMOLED black theme: invoked 1, 0 found, 0 missing"));
    }

    /** The section goes through the redactor like every other one, and has to come out whole. */
    @Test
    public void theExportCarriesTheSectionWhole() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        LogBufferManager.registerReportSection(PatchFamily.REPORT);
        // A paused process always makes a report, so nothing else has to go wrong first.
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);

        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("\n[PATCHES]\n"));
        assertTrue(report, report.contains("what was set when patching stays in"));
        for (String line : PatchFamily.reportLines(EnumSet.allOf(PatchFamily.class), true)) {
            assertTrue("the export changed or lost \"" + line + "\":\n" + report, report.contains("\n" + line + "\n"));
        }
    }

    /** patches-list.json at the repository root, found from wherever Gradle runs the test. */
    private static File patchesList() {
        for (File dir = new File("").getAbsoluteFile(); dir != null; dir = dir.getParentFile()) {
            File candidate = new File(dir, "patches-list.json");
            if (candidate.isFile()) return candidate;
        }
        throw new AssertionError("no patches-list.json above " + new File("").getAbsolutePath());
    }
}
