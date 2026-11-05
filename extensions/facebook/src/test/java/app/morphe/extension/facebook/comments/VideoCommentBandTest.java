/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.SettingReadsForTests;

/**
 * The Shorts viewer a feed video opens in (#375): with Hide the video comment box on, the comment
 * bar its root renders is dropped, and only after a pick that came back with no bar does the seek bar
 * go down to the bottom, counted. Its list sizes the videos before any root has picked a bar, so with the switch on a video
 * keeps its full height, counted, unless its viewer's root kept a bar, the reply bar under a reel
 * shared in a chat say, which keeps the band for that viewer alone. Off by default, under no other
 * switch, and Facebook's when paused, before the settings are ready, or when the switch can't be read.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class VideoCommentBandTest {
    /** The band height Facebook reads on the S22, in dp. */
    private static final int BAND = 60;

    /** A bar a root's pick hands back. */
    private static final Object BAR = new Object();

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** A viewer's config, the object its roots and its list are handed with each band height. */
    private final Object viewer = new Object();

    @Before
    public void start() {
        HookStatus.clear();
        CommentSheetOptions.pickingShortsBar();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.LIKE_ONLY.resetToDefault();
        Settings.HIDE_UP_NEXT.resetToDefault();
        Settings.HIDE_VIDEO_COMMENT_COMPOSER.resetToDefault();
        CommentSheetOptions.pickingShortsBar();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.COMMENT_SHEET_OPTIONS + ":")) return line;
        }
        return null;
    }

    private static void assertNothingCounted(String when) {
        String line = statusLine();
        assertFalse("a band left alone " + when + " was counted: " + line, line != null && line.contains("Counted"));
    }

    private static int seekBar() {
        return CommentSheetOptions.shortsSeekBarBand(BAND);
    }

    private static int video(Object config) {
        return CommentSheetOptions.shortsVideoBand(BAND, config);
    }

    /** A root render of {@code config}'s viewer whose pick hands back the comment bar, dropped or kept. */
    private static boolean render(Object config) {
        CommentSheetOptions.pickingShortsBar();
        boolean dropped = CommentSheetOptions.dropsShortsCommentBar();
        CommentSheetOptions.keptShortsBar(dropped ? null : BAR, config);
        return dropped;
    }

    @Test
    public void theBandGoesWithTheSwitchUnlessTheViewerKeptABar() {
        assertFalse(Settings.HIDE_VIDEO_COMMENT_COMPOSER.defaultValue);
        assertFalse("the Shorts viewer dropped its bar by default", render(viewer));
        assertEquals("the seek bar moved by default", BAND, seekBar());
        assertEquals("the video took its full height by default", BAND, video(viewer));
        Settings.LIKE_ONLY.save(true);
        Settings.HIDE_UP_NEXT.save(true);
        assertFalse("another switch dropped the bar", CommentSheetOptions.dropsShortsCommentBar());
        Object opened = new Object();
        assertEquals("another switch gave the video its full height", BAND, video(opened));

        Settings.HIDE_VIDEO_COMMENT_COMPOSER.save(true);
        assertEquals("a new viewer's video, sized before its root picked, kept the band", 0, video(opened));
        assertTrue("the Shorts viewer kept its comment bar", render(opened));
        assertEquals("the seek bar stayed over the caption", CommentSheetOptions.NO_BAR_SEEK_BAR_DP, seekBar());
        assertEquals("the video got the band back once the bar went", 0, video(opened));
        assertEquals("a viewer whose root kept its bar with the switch off lost its band before rendering again",
                BAND, video(viewer));
        assertTrue(render(viewer));
        assertEquals("a viewer whose bar went at last kept the band", 0, video(viewer));
        assertEquals(FamilyNames.COMMENT_SHEET_OPTIONS + ": invoked 15, 4 found, 0 missing. Counted: "
                + CommentSheetOptions.FULL_HEIGHT + " 3, " + CommentSheetOptions.COMPOSER_HIDDEN + " 2, "
                + CommentSheetOptions.SEEK_BAR_LOWERED + " 1", statusLine());

        // A render whose pick keeps another bar keeps the band, the seek bar's and the video's.
        CommentSheetOptions.pickingShortsBar();
        CommentSheetOptions.keptShortsBar(BAR, viewer);
        assertEquals("the seek bar went under another bar", BAND, seekBar());
        assertEquals("another bar lost its band", BAND, video(viewer));
        // A pick of no bar at all gives the video its full height too, so the seek bar goes down with
        // it instead of staying where the caption now ends (the feed reel scroller's overlap).
        CommentSheetOptions.pickingShortsBar();
        CommentSheetOptions.keptShortsBar(null, viewer);
        assertEquals("a pick of no bar left the seek bar over the caption",
                CommentSheetOptions.NO_BAR_SEEK_BAR_DP, seekBar());
        assertEquals("a pick of no bar kept the video's band", 0, video(viewer));
        CommentSheetOptions.pickingShortsBar();
        assertEquals("the next render started with the seek bar down", BAND, seekBar());

        assertTrue(render(viewer));
        assertEquals(CommentSheetOptions.NO_BAR_SEEK_BAR_DP, seekBar());
        Settings.HIDE_VIDEO_COMMENT_COMPOSER.save(false);
        assertEquals("turning the switch off didn't give the band back", BAND, video(viewer));
        assertEquals("turning the switch off left the seek bar down", BAND, seekBar());
        CommentSheetOptions.pickingShortsBar();
        CommentSheetOptions.keptShortsBar(null, viewer);
        assertEquals("a pick of no bar with the switch off moved the seek bar", BAND, seekBar());
    }

    /**
     * Each viewer keeps its own answer: one whose root kept another bar keeps the band while another's
     * videos stay full height, and a list handed no config keeps it. A drop on one thread leaves a
     * seek bar placed on another alone.
     */
    @Test
    public void anotherViewerKeepsItsOwnBand() throws InterruptedException {
        Settings.HIDE_VIDEO_COMMENT_COMPOSER.save(true);
        Object shared = new Object();
        CommentSheetOptions.keptShortsBar(BAR, shared);
        assertTrue(render(viewer));
        assertEquals("a viewer that kept another bar lost its band", BAND, video(shared));
        assertEquals("a viewer whose bar went kept the band", 0, video(viewer));
        assertEquals("a list handed no config lost its band", BAND, video(null));
        CommentSheetOptions.keptShortsBar(BAR, null);
        assertEquals("a bar kept with no config marked another viewer", 0, video(viewer));

        assertTrue(render(viewer));
        int[] answer = new int[1];
        Thread render = new Thread(() -> answer[0] = seekBar());
        render.start();
        render.join();
        assertEquals("a drop on one thread moved another thread's seek bar", BAND, answer[0]);
        assertEquals(CommentSheetOptions.NO_BAR_SEEK_BAR_DP, seekBar());
    }

    /** The full-screen video viewer's own comment bar hook doesn't move the Shorts viewer's seek bar. */
    @Test
    public void theFullScreenViewersBarLeavesTheShortsSeekBarAlone() {
        Settings.HIDE_VIDEO_COMMENT_COMPOSER.save(true);
        assertTrue(CommentSheetOptions.hidesVideoCommentComposer());
        assertEquals("the full-screen viewer's bar moved the Shorts seek bar", BAND, seekBar());
    }

    @Test
    public void theBandStaysFacebooksWhilePausedOrBeforeTheSettingsAreReady() {
        Settings.HIDE_VIDEO_COMMENT_COMPOSER.save(true);
        assertTrue(render(viewer));
        assertEquals(CommentSheetOptions.NO_BAR_SEEK_BAR_DP, seekBar());
        HookStatus.clear();
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals("a paused Hushfacebook gave the video its full height", BAND, video(viewer));
        assertEquals("a paused Hushfacebook moved the seek bar", BAND, seekBar());
        assertFalse("a paused Hushfacebook dropped the bar", CommentSheetOptions.dropsShortsCommentBar());
        assertNothingCounted("while paused");
        PauseForTests.resume();
        assertTrue(render(viewer));
        assertEquals(CommentSheetOptions.NO_BAR_SEEK_BAR_DP, seekBar());
        HookStatus.clear();
        SettingsContextRule.withoutContext(() -> {
            assertEquals("the video took its full height before the settings were ready", BAND, video(viewer));
            assertEquals("the seek bar moved before the settings were ready", BAND, seekBar());
            assertFalse("the bar went before the settings were ready", CommentSheetOptions.dropsShortsCommentBar());
        });
        assertNothingCounted("before the settings were ready");
    }

    /** A switch that can't be read keeps Facebook's height, bar and seek bar, and the report names each hook. */
    @Test
    public void aSwitchThatCantBeReadKeepsTheBand() {
        Settings.HIDE_VIDEO_COMMENT_COMPOSER.save(true);
        assertTrue(render(viewer));
        assertEquals(CommentSheetOptions.NO_BAR_SEEK_BAR_DP, seekBar());
        SettingReadsForTests.breakReads(Settings.HIDE_VIDEO_COMMENT_COMPOSER);
        try {
            assertEquals(BAND, video(viewer));
            assertEquals(BAND, seekBar());
            assertFalse(CommentSheetOptions.dropsShortsCommentBar());
        } finally {
            SettingReadsForTests.mend(Settings.HIDE_VIDEO_COMMENT_COMPOSER);
        }
        String missing = HookStatus.missing(FamilyNames.COMMENT_SHEET_OPTIONS).toString();
        for (String hook : new String[] {CommentSheetOptions.VIDEO_HEIGHT, CommentSheetOptions.SEEK_BAR,
                CommentSheetOptions.SHORTS_BAR}) {
            assertTrue(missing, missing.contains("a working '" + hook + "' hook (it threw "
                    + NullPointerException.class.getName() + ")"));
        }
    }
}
