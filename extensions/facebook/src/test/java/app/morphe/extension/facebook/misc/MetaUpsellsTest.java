/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Hide Meta upsells: each of the eight switches starts off, and on it answers away only its own
 * promotions, counted. A no stays a no, Kotlin's suspend marker passes the Meta Verified hook
 * untouched, other post buttons, story tools and share targets stay in order, and off or paused every
 * answer is Facebook's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MetaUpsellsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /**
     * The eight switches, filled in once the rule has set the context: naming Settings in a static
     * field loads it at class init, before any context, and leaves BaseSettings broken for every
     * test that runs after it in this sandbox.
     */
    private BooleanSetting[] switches;

    /** What Kotlin hands back from a suspend method that hasn't finished, as far as the hook can tell. */
    private static final Object NOT_YET = new Object();

    /** Stands in for Facebook's enum of Create story tools, which names its constants the same way. */
    private enum StoryTool { TEXT_BASE, BOOMERANG, IMAGINE, TRY_IT }

    private static final List<StoryTool> TOOLS = Arrays.asList(StoryTool.values());

    /** Stands in for Facebook's enum of share sheet items, which names its constants the same way. */
    private enum ShareItem { SHARE_NOW, SHARE_TO_HATCH, SHARE_TO_THREADS, OFF_PLATFORM_WHATSAPP, COPY_LINK }

    private static final List<ShareItem> SHARE_ITEMS = Arrays.asList(ShareItem.values());

    /** Another post button the call-to-action selector can show. */
    private static final String OTHER_CTA = "com.facebook.feed.plugins.calltoaction.impl.aistyles.AIStylesPlugin";

    @Before
    public void start() {
        switches = new BooleanSetting[] {Settings.HIDE_EDITS_UPSELLS, Settings.HIDE_THREADS_CROSS_POSTING,
                Settings.HIDE_THREADS_SHARE_BUTTON, Settings.HIDE_MUSE_SHARE_BUTTON, Settings.HIDE_META_VERIFIED_UPSELLS,
                Settings.HIDE_AVATAR_UPSELLS, Settings.HIDE_META_AI_IMAGINE,
                Settings.HIDE_META_AI_POST_BUTTONS};
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        for (BooleanSetting setting : switches) setting.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.META_UPSELLS + ":")) return line;
        }
        return "";
    }

    /**
     * Whether each part hides, in the switches' order: Edits, Threads cross-posting, Threads in the
     * share sheet, Muse in the share sheet, Meta Verified, avatar stickers, Imagine, the other Meta AI
     * post buttons.
     */
    private static boolean[] hiding() {
        boolean edits = !MetaUpsells.editsHeader(true) && !MetaUpsells.fetchEditsPill(true)
                && Boolean.FALSE.equals(MetaUpsells.fetchEditsPill(Boolean.TRUE));
        boolean threads = !MetaUpsells.threadsOnboarding(1);
        // One ask for both share switches, so each is counted once a round like the rest.
        List<?> shared = MetaUpsells.shareTargets(SHARE_ITEMS);
        boolean threadsShare = !shared.contains(ShareItem.SHARE_TO_THREADS);
        boolean museShare = !shared.contains(ShareItem.SHARE_TO_HATCH);
        boolean verified = Boolean.FALSE.equals(MetaUpsells.metaVerifiedSheet(Boolean.TRUE))
                && MetaUpsells.metaVerifiedLabel("Meta Verified") == null;
        boolean avatar = MetaUpsells.hidesAvatarUpsell();
        boolean imagine = MetaUpsells.hidesImagineCta(MetaUpsells.IMAGINE_ME_PLUGIN) && !MetaUpsells.imagineCapability(true)
                && !MetaUpsells.storyTools(TOOLS).contains(StoryTool.IMAGINE);
        boolean metaAiButtons = true;
        for (String plugin : MetaUpsells.META_AI_POST_PLUGINS) metaAiButtons &= MetaUpsells.hidesImagineCta(plugin);
        metaAiButtons &= MetaUpsells.hidesDeepDiveBelowCaption();
        return new boolean[] {edits, threads, threadsShare, museShare, verified, avatar, imagine, metaAiButtons};
    }

    @Test
    public void everySwitchStartsOffAndFacebookDecides() {
        for (BooleanSetting setting : switches) assertFalse(setting.key + " starts on", setting.get());
        assertTrue(Arrays.toString(hiding()), Arrays.equals(new boolean[8], hiding()));
        assertSame("Create story's tools were copied with the switch off", TOOLS, MetaUpsells.storyTools(TOOLS));
        assertSame("the share sheet's items were copied with the switch off", SHARE_ITEMS, MetaUpsells.shareTargets(SHARE_ITEMS));
        assertEquals("Meta Verified", MetaUpsells.metaVerifiedLabel("Meta Verified"));
        assertEquals(Boolean.TRUE, MetaUpsells.fetchEditsPill(Boolean.TRUE));
    }

    @Test
    public void eachSwitchHidesOnlyItsOwnAndIsCounted() {
        for (int on = 0; on < switches.length; on++) {
            for (BooleanSetting setting : switches) setting.save(setting == switches[on]);
            boolean[] expected = new boolean[8];
            expected[on] = true;
            // Asked once per switch: each ask counts, so the counts below are one round of asks.
            boolean[] hid = hiding();
            assertTrue(switches[on].key + " hid " + Arrays.toString(hid), Arrays.equals(expected, hid));
        }
        String line = statusLine();
        assertTrue(line, line.contains(MetaUpsells.EDITS_HIDDEN + " 3"));
        assertTrue(line, line.contains(MetaUpsells.THREADS_HIDDEN + " 1"));
        assertTrue(line, line.contains(MetaUpsells.VERIFIED_HIDDEN + " 2"));
        assertTrue(line, line.contains(MetaUpsells.AVATAR_HIDDEN + " 1"));
        assertTrue(line, line.contains(MetaUpsells.IMAGINE_HIDDEN + " 3"));
        assertTrue(line, line.contains(MetaUpsells.THREADS_SHARE_HIDDEN + " 1"));
        assertTrue(line, line.contains(MetaUpsells.MUSE_SHARE_HIDDEN + " 1"));
        assertTrue(line, line.contains(MetaUpsells.META_AI_BUTTON_HIDDEN + " 5"));
        assertTrue(line, line.contains(MetaUpsells.CAPTION_DEEP_DIVE_HIDDEN + " 1"));
    }

    /**
     * The Edits pill over feed videos (#314) is left out of the request under Clean up Reels'
     * prompts and promos switch too. The Reels composer's Edits header stays the Edits switch's own.
     */
    @Test
    public void theEditsPillRequestAlsoGoesWithTheReelChipsSwitch() {
        try {
            Settings.HIDE_REEL_CHIPS.save(true);
            assertFalse("the chips switch kept the pill in the feed's request", MetaUpsells.fetchEditsPill(true));
            assertEquals("the chips switch kept the pill in the video queries", Boolean.FALSE, MetaUpsells.fetchEditsPill(Boolean.TRUE));
            assertTrue("the chips switch took the composer's Edits header", MetaUpsells.editsHeader(true));
            assertFalse("a request that didn't ask was turned around", MetaUpsells.fetchEditsPill(false));
            SettingsContextRule.withoutContext(() -> assertTrue("the pill went before the settings were ready",
                    MetaUpsells.fetchEditsPill(true)));
            Settings.HIDE_REEL_CHIPS.save(false);
            assertTrue("turning the switch off didn't bring the pill back", MetaUpsells.fetchEditsPill(true));
        } finally {
            Settings.HIDE_REEL_CHIPS.resetToDefault();
        }
    }

    /**
     * #314 on 588: the pill over a feed video is drawn from the video's Edits attribution, so its
     * getter answers none with either switch on, a pill already sent or cached included.
     */
    @Test
    public void theEditsAttributionGoesWithEitherSwitch() {
        try {
            assertFalse("the attribution went with both switches off", MetaUpsells.hidesEditsAttribution());
            Settings.HIDE_EDITS_UPSELLS.save(true);
            assertTrue("the Edits switch kept the attribution", MetaUpsells.hidesEditsAttribution());
            Settings.HIDE_EDITS_UPSELLS.save(false);
            Settings.HIDE_REEL_CHIPS.save(true);
            assertTrue("the chips switch kept the attribution", MetaUpsells.hidesEditsAttribution());
            SettingsContextRule.withoutContext(() -> assertFalse("the attribution went before the settings were ready",
                    MetaUpsells.hidesEditsAttribution()));
            Settings.HIDE_REEL_CHIPS.save(false);
            assertFalse("turning the switch off didn't bring the attribution back", MetaUpsells.hidesEditsAttribution());
        } finally {
            Settings.HIDE_EDITS_UPSELLS.resetToDefault();
            Settings.HIDE_REEL_CHIPS.resetToDefault();
        }
    }

    /**
     * #314 on 588: a Page's timeline builds the "Made with Edits" pill under a video from the pill the
     * server sent, so the builder hands over the pill's type and draws nothing for the Edits one with
     * either switch on. Every other pill stays.
     */
    @Test
    public void onlyTheEditsPillUnderAVideoGoesWithEitherSwitch() {
        try {
            assertFalse("the Edits pill went with both switches off", MetaUpsells.hidesDeepDivePill("edits_app"));
            Settings.HIDE_EDITS_UPSELLS.save(true);
            assertTrue("the Edits switch kept the Edits pill", MetaUpsells.hidesDeepDivePill("edits_app"));
            assertFalse("the Edits switch took the stars pill", MetaUpsells.hidesDeepDivePill("stars"));
            assertFalse("a pill with no type went", MetaUpsells.hidesDeepDivePill(null));
            Settings.HIDE_EDITS_UPSELLS.save(false);
            Settings.HIDE_REEL_CHIPS.save(true);
            assertTrue("the chips switch kept the Edits pill", MetaUpsells.hidesDeepDivePill("edits_app"));
            SettingsContextRule.withoutContext(() -> assertFalse("the Edits pill went before the settings were ready",
                    MetaUpsells.hidesDeepDivePill("edits_app")));
            PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
            assertFalse("a paused Hushfacebook took the Edits pill", MetaUpsells.hidesDeepDivePill("edits_app"));
            PauseForTests.resume();
            Settings.HIDE_REEL_CHIPS.save(false);
            assertFalse("turning the switch off didn't bring the Edits pill back", MetaUpsells.hidesDeepDivePill("edits_app"));
            String line = statusLine();
            assertTrue(line, line.contains(MetaUpsells.EDITS_PILL_HIDDEN + " 2"));
        } finally {
            Settings.HIDE_EDITS_UPSELLS.resetToDefault();
            Settings.HIDE_REEL_CHIPS.resetToDefault();
        }
    }

    @Test
    public void theShareSheetKeepsItsOtherItemsInOrder() {
        Settings.HIDE_THREADS_SHARE_BUTTON.save(true);
        assertEquals("the share sheet's other items moved",
                Arrays.asList(ShareItem.SHARE_NOW, ShareItem.SHARE_TO_HATCH, ShareItem.OFF_PLATFORM_WHATSAPP, ShareItem.COPY_LINK),
                MetaUpsells.shareTargets(SHARE_ITEMS));
        List<ShareItem> without = Arrays.asList(ShareItem.SHARE_NOW, ShareItem.COPY_LINK);
        assertSame("a sheet with no Threads item was copied", without, MetaUpsells.shareTargets(without));
        assertSame(Collections.emptyList(), MetaUpsells.shareTargets(Collections.emptyList()));
        assertNull(MetaUpsells.shareTargets(null));
        assertFalse("a sheet with no Threads item was counted", statusLine().contains(MetaUpsells.THREADS_SHARE_HIDDEN + " 2"));
    }

    @Test
    public void museLeavesTheShareSheetOnItsOwnOrWithThreads() {
        Settings.HIDE_MUSE_SHARE_BUTTON.save(true);
        assertEquals("Muse's switch took more than Muse",
                Arrays.asList(ShareItem.SHARE_NOW, ShareItem.SHARE_TO_THREADS, ShareItem.OFF_PLATFORM_WHATSAPP, ShareItem.COPY_LINK),
                MetaUpsells.shareTargets(SHARE_ITEMS));
        List<ShareItem> noMuse = Arrays.asList(ShareItem.SHARE_NOW, ShareItem.SHARE_TO_THREADS);
        assertSame("a sheet with no Muse item was copied", noMuse, MetaUpsells.shareTargets(noMuse));
        assertFalse("a sheet with no Muse item was counted", statusLine().contains(MetaUpsells.MUSE_SHARE_HIDDEN + " 2"));

        Settings.HIDE_THREADS_SHARE_BUTTON.save(true);
        assertEquals("both switches on left one in or moved the rest",
                Arrays.asList(ShareItem.SHARE_NOW, ShareItem.OFF_PLATFORM_WHATSAPP, ShareItem.COPY_LINK),
                MetaUpsells.shareTargets(SHARE_ITEMS));
        String line = statusLine();
        assertTrue(line, line.contains(MetaUpsells.MUSE_SHARE_HIDDEN + " 2"));
        assertTrue(line, line.contains(MetaUpsells.THREADS_SHARE_HIDDEN + " 1"));
    }

    @Test
    public void imagineLeavesOtherButtonsAndToolsAsTheyWere() {
        Settings.HIDE_META_AI_IMAGINE.save(true);
        assertFalse("another post button got a no", MetaUpsells.hidesImagineCta(OTHER_CTA));
        assertFalse("a missing plugin name got a no", MetaUpsells.hidesImagineCta(null));
        assertEquals("Create story's other tools moved",
                Arrays.asList(StoryTool.TEXT_BASE, StoryTool.BOOMERANG, StoryTool.TRY_IT), MetaUpsells.storyTools(TOOLS));
        List<StoryTool> without = Arrays.asList(StoryTool.TEXT_BASE, StoryTool.TRY_IT);
        assertSame("a list with no Imagine was copied", without, MetaUpsells.storyTools(without));
        assertSame(Collections.emptyList(), MetaUpsells.storyTools(Collections.emptyList()));
        assertNull(MetaUpsells.storyTools(null));
    }

    @Test
    public void theOtherMetaAiButtonsLeaveImagineMeAndEveryOtherButton() {
        Settings.HIDE_META_AI_POST_BUTTONS.save(true);
        for (String plugin : MetaUpsells.META_AI_POST_PLUGINS) assertTrue(plugin + " kept its yes", MetaUpsells.hidesImagineCta(plugin));
        assertFalse("Imagine me got a no from the other switch", MetaUpsells.hidesImagineCta(MetaUpsells.IMAGINE_ME_PLUGIN));
        assertTrue("a business's AI agent button kept its yes",
                MetaUpsells.hidesImagineCta("com.facebook.feed.plugins.calltoaction.impl.bizaiagent.BizAiAgentCtaPlugin"));
        assertTrue("Meta AI's deep dive under a caption kept its model", MetaUpsells.hidesDeepDiveBelowCaption());
        assertFalse("a group invite button got a no",
                MetaUpsells.hidesImagineCta("com.facebook.feed.rows.sections.calltoaction.GroupsInviteFeedStoryCtaPlugin"));
        assertFalse("another post button got a no",
                MetaUpsells.hidesImagineCta("com.facebook.feed.plugins.calltoaction.impl.telluswhy.FeedTellUsWhyCtaPlugin"));
        assertFalse("a missing plugin name got a no", MetaUpsells.hidesImagineCta(null));
        String line = statusLine();
        assertTrue(line, line.contains(MetaUpsells.META_AI_BUTTON_HIDDEN + " 6"));
        assertTrue(line, line.contains(MetaUpsells.CAPTION_DEEP_DIVE_HIDDEN + " 1"));
        assertFalse(line, line.contains(MetaUpsells.IMAGINE_HIDDEN));
    }

    @Test
    public void aNoStaysANoAndTheSuspendMarkerPasses() {
        for (BooleanSetting setting : switches) setting.save(true);
        assertFalse(MetaUpsells.editsHeader(false));
        assertFalse(MetaUpsells.fetchEditsPill(false));
        assertFalse(MetaUpsells.threadsOnboarding(0));
        assertFalse(MetaUpsells.imagineCapability(false));
        assertSame("the eligibility check's not-yet marker was swapped", NOT_YET, MetaUpsells.metaVerifiedSheet(NOT_YET));
        assertEquals(Boolean.FALSE, MetaUpsells.metaVerifiedSheet(Boolean.FALSE));
        assertNull(MetaUpsells.metaVerifiedLabel(null));
        assertFalse("a no was counted", statusLine().contains("Counted"));
        // A gate that had no answer still sends a no, so the server isn't left to pick.
        assertEquals(Boolean.FALSE, MetaUpsells.fetchEditsPill((Boolean) null));
    }

    @Test
    public void pausedEveryAnswerIsFacebooks() {
        for (BooleanSetting setting : switches) setting.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertTrue("a Hushfacebook paused by " + reason + " hid " + Arrays.toString(hiding()),
                    Arrays.equals(new boolean[8], hiding()));
            PauseForTests.resume();
        }
    }

    @Test
    public void theSwitchesTravelWithThePatch() {
        assertEquals(Arrays.asList(switches), PatchFamily.META_UPSELLS.switches);
        assertEquals("Hide Meta upsells", FamilyNames.META_UPSELLS);
    }
}
