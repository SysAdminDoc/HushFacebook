/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import app.morphe.extension.facebook.media.TapToPlayForTests.Autoplay;
import app.morphe.extension.facebook.media.TapToPlayForTests.Trigger;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * #602: feed videos stopped starting by themselves after a while, with Tap to play off. The
 * report's VIDEO STARTS section lists the last starts Facebook asked for, newest first, with the
 * trigger, the player's origin and whether it played or Hushfacebook held it, and what Facebook's
 * Autoplay setting last answered, so a report sent right then tells the two apart.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class VideoStartsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        SystemClock.sleep(60_000);
        TapToPlayForTests.forget();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.TAP_TO_PLAY.resetToDefault();
        TapToPlayForTests.forget();
        HookStatus.clear();
    }

    @Test
    public void everyStartIsListedWithItsOriginAndWhatCameOfIt() {
        assertTrue("nothing seen, nothing said", VideoStarts.REPORT.lines().isEmpty());
        Object feedVideo = new Object();
        TapToPlay.origins = player -> player == feedVideo ? "newsfeed::feed_story" : null;

        assertTrue("Tap to play off, the start plays", TapToPlay.allowStart(feedVideo, Trigger.BY_AUTOPLAY));
        List<String> lines = VideoStarts.REPORT.lines();
        assertEquals("Facebook's Autoplay setting: not read yet", lines.get(0));
        assertEquals("Starts asked for: 1, held by Hushfacebook: 0", lines.get(1));
        assertEquals("Last starts, newest first:", lines.get(2));
        assertEquals("  0 s ago, BY_AUTOPLAY, newsfeed::feed_story, played", lines.get(3));

        Settings.TAP_TO_PLAY.save(true);
        SystemClock.sleep(5_000);
        assertFalse("no tap, so Tap to play holds it", TapToPlay.allowStart(feedVideo, Trigger.BY_AUTOPLAY));
        assertFalse(TapToPlay.allowLegacyStart(new Object(), Trigger.BY_AUTOPLAY));
        lines = VideoStarts.REPORT.lines();
        assertEquals("Starts asked for: 3, held by Hushfacebook: 2", lines.get(1));
        assertEquals("  0 s ago, BY_AUTOPLAY, older player, held by Tap to play", lines.get(3));
        assertEquals("  0 s ago, BY_AUTOPLAY, newsfeed::feed_story, held by Tap to play", lines.get(4));
        assertEquals("  5 s ago, BY_AUTOPLAY, newsfeed::feed_story, played", lines.get(5));
        assertEquals("a player with no origin says so", "no origin",
                originOf(TapToPlay.allowStart(new Object(), Trigger.BY_USER)));
    }

    @Test
    public void theAutoplayReadSaysWhatFacebookChoseAndWhatItWasAnswered() {
        TapToPlay.autoplaySetting(Autoplay.ON);
        assertEquals("Facebook's Autoplay setting: ON, read 0 s ago", VideoStarts.REPORT.lines().get(0));
        assertEquals("no start yet, so no list", 2, VideoStarts.REPORT.lines().size());

        Settings.TAP_TO_PLAY.save(true);
        TapToPlay.autoplaySetting(Autoplay.WIFI_ONLY);
        SystemClock.sleep(3_000);
        assertEquals("Facebook's Autoplay setting: WIFI_ONLY, answered OFF, read 3 s ago",
                VideoStarts.REPORT.lines().get(0));
    }

    @Test
    public void onlyTheLastStartsAreKeptButEveryOneIsCounted() {
        for (int i = 0; i < VideoStarts.KEPT + 6; i++) TapToPlay.allowStart(new Object(), Trigger.BY_AUTOPLAY);
        List<String> lines = VideoStarts.REPORT.lines();
        assertEquals("Starts asked for: " + (VideoStarts.KEPT + 6) + ", held by Hushfacebook: 0", lines.get(1));
        assertEquals(3 + VideoStarts.KEPT, lines.size());
    }

    @Test
    public void theReportCarriesTheSection() {
        PatchFamily.registerDiagnostics();
        TapToPlay.allowStart(new Object(), Trigger.BY_AUTOPLAY);
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("[VIDEO STARTS]"));
        assertTrue(report, report.contains("BY_AUTOPLAY, no origin, played"));
    }

    /** The origin of the newest start listed; [ignored] is the start's answer. */
    private static String originOf(boolean ignored) {
        String newest = VideoStarts.REPORT.lines().get(3);
        String[] parts = newest.split(", ");
        return parts[2];
    }
}
