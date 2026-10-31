/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.video.engine.api.VideoPlayerParams;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Issue #18: a reel with no Download button left nothing in the report, because the only counter
 * ran on a tap. Every time Facebook builds a reel's buttons, Hook status now counts which sidebar
 * it was and, for the one the button goes in, why the button went in or stayed out. Each reason
 * is a fixed phrase, so the count says nothing about the reel.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReelSidebarReportTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    @After
    public void clean() {
        HookStatus.clear();
        PauseForTests.resume();
        Settings.DOWNLOAD_REELS.resetToDefault();
        Settings.SHOW_REEL_DOWNLOAD_BUTTON.resetToDefault();
        Settings.REEL_CAST_BUTTON.resetToDefault();
    }

    private static String line() {
        List<String> lines = HookStatus.report();
        assertEquals(lines.toString(), 1, lines.size());
        return lines.get(0);
    }

    @Test
    public void aSidebarBuiltWithTheButtonIsCounted() {
        assertTrue(ReelDownload.showsButton());
        assertTrue(ReelDownload.showsButton());
        assertEquals("Download any reel: invoked 0, 0 found, 0 missing. Counted: " + ReelDownload.SIDEBAR_WITH_BUTTON + " 2",
                line());
    }

    @Test
    public void hidingTheButtonHasItsOwnReasonAndDoesNotDisableDownloads() {
        assertTrue("the default layout lost its Download button", ReelDownload.showsButton());
        Settings.SHOW_REEL_DOWNLOAD_BUTTON.save(false);
        assertFalse(ReelDownload.showsButton());
        assertTrue("hiding the button disabled menu downloads", Settings.DOWNLOAD_REELS.get());
        String report = line();
        assertTrue(report, report.contains(ReelDownload.SIDEBAR_HIDDEN + " 1"));
        assertFalse(report, report.contains(ReelDownload.SIDEBAR_SWITCH_OFF));
        Settings.SHOW_REEL_DOWNLOAD_BUTTON.save(true);
        assertTrue("the next sidebar did not show the button again", ReelDownload.showsButton());
    }

    @Test
    public void eachReasonTheButtonStaysOutIsCountedApart() {
        Settings.DOWNLOAD_REELS.save(false);
        assertFalse(ReelDownload.showsButton());

        Settings.DOWNLOAD_REELS.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("a paused build showed the button", ReelDownload.showsButton());
        PauseForTests.resume();

        SettingsContextRule.beforeThePauseIsDecided(() -> assertFalse(ReelDownload.showsButton()));

        String line = line();
        assertTrue(line, line.contains(ReelDownload.SIDEBAR_SWITCH_OFF + " 1"));
        assertTrue(line, line.contains(ReelDownload.SIDEBAR_PAUSED + " 1"));
        assertTrue(line, line.contains(ReelDownload.SIDEBAR_NOT_READY + " 1"));
        assertFalse(line, line.contains(ReelDownload.SIDEBAR_WITH_BUTTON));
    }

    /**
     * The route the button doesn't reach. The Reels viewer draws FbShortsSideBarComponent when a
     * server flag says so, and other viewers do too, so a phone that only ever counts this
     * line never had a sidebar the button could go in. It counts whatever the switch or a pause
     * says, and before the settings are ready, since it changes nothing on the screen.
     */
    @Test
    public void theOtherSidebarIsCountedWhateverTheSwitchSays() {
        ReelDownload.otherSidebarBuilt();
        Settings.DOWNLOAD_REELS.save(false);
        ReelDownload.otherSidebarBuilt();
        SettingsContextRule.withoutContext(ReelDownload::otherSidebarBuilt);

        assertEquals("Download any reel: invoked 0, 0 found, 0 missing. Counted: " + ReelDownload.OTHER_SIDEBAR + " 3",
                line());
    }

    /**
     * Issues #316 and #298: some accounts get Facebook's older reel button layout, which never
     * builds the sidebar the button goes in. The patch hands Facebook's answer for that layout to
     * verticalUfi as each reel is drawn. Yes stays yes, and no turns into the newer layout only
     * while the button would show, so a switched off, hidden, paused or not yet ready button leaves
     * Facebook's own layout and buttons.
     */
    @Test
    public void theOlderLayoutSwitchesToTheNewerOneOnlyForTheButton() {
        assertTrue("Facebook's newer layout was turned down", ReelDownload.verticalUfi(true));
        assertTrue("the older layout kept the reel from its Download button", ReelDownload.verticalUfi(false));

        Settings.SHOW_REEL_DOWNLOAD_BUTTON.save(false);
        assertFalse("a hidden button still switched the layout", ReelDownload.verticalUfi(false));
        assertTrue("hiding the button turned down the newer layout", ReelDownload.verticalUfi(true));
        Settings.SHOW_REEL_DOWNLOAD_BUTTON.save(true);

        Settings.DOWNLOAD_REELS.save(false);
        assertFalse("downloads off still switched the layout", ReelDownload.verticalUfi(false));
        Settings.DOWNLOAD_REELS.save(true);

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("a paused start switched the layout", ReelDownload.verticalUfi(false));
        PauseForTests.resume();

        SettingsContextRule.beforeThePauseIsDecided(() -> assertFalse(ReelDownload.verticalUfi(false)));
        SettingsContextRule.withoutContext(() -> assertTrue(ReelDownload.verticalUfi(true)));

        String line = line();
        assertTrue(line, line.contains(ReelDownload.LAYOUT_NEWER + " 3"));
        assertTrue(line, line.contains(ReelDownload.LAYOUT_SWITCHED + " 1"));
        assertTrue(line, line.contains(ReelDownload.LAYOUT_OLDER + " 4"));
    }

    /**
     * The button Facebook draws gets a handler in every one of its factory's slots, and only the
     * tap slot saves: once per tap. The touch slot fires twice a press and a visibility slot on its
     * own, so a save from either would save the reel again or unasked.
     */
    @Test
    public void onlyTheTapSlotSavesAndOncePerTap() throws Exception {
        AtomicInteger saves = new AtomicInteger();
        MediaDownload.detailsForTests = details -> saves.incrementAndGet();
        // Every Meta name answers a private address, so each save stops before it connects.
        MediaDownload.policyForTests = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") });
        try {
            FileNameTemplateTest.ReelSourceParams reel = new FileNameTemplateTest.ReelSourceParams(new VideoPlayerParams(
                    "VideoId: 2233445566778899", new FileNameTemplateTest.ReelSource(
                            "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/clip_720p.mp4?oh=1&oe=2")));
            for (int slot = 0; slot <= 6; slot++) {
                ReelDownload handler = new ReelDownload(reel, RuntimeEnvironment.getApplication(), "hd", "sd", "manifest",
                        slot, slot == 1);
                handler.invoke(null);
                handler.invoke(null);
            }
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (MediaDownload.savesInFlight() > 0) {
                assertTrue("a save never finished", System.nanoTime() < deadline);
                Thread.sleep(10);
            }
        } finally {
            MediaDownload.detailsForTests = null;
            MediaDownload.policyForTests = null;
            LogBufferManager.clearLogBuffer();
        }
        assertEquals("two taps of the tap slot, and six other slots fired twice each", 2, saves.get());
    }

    /**
     * Issue #182: the Cast button goes in the same sidebar as Download, under its own switch. With
     * only Cast on, the sidebar is built for it and the helper skips Download, the older layout
     * switches for it too, and a pause keeps it out like the rest.
     */
    @Test
    public void theCastButtonGetsTheSidebarOnItsOwn() {
        Settings.SHOW_REEL_DOWNLOAD_BUTTON.save(false);
        assertFalse("the Cast button started on", ReelDownload.castButton());
        assertFalse(ReelDownload.showsButton());

        Settings.REEL_CAST_BUTTON.save(true);
        assertTrue("only Cast on built no sidebar for it", ReelDownload.showsButton());
        assertFalse("a hidden Download button was built", ReelDownload.downloadButton());
        assertTrue(ReelDownload.castButton());
        assertTrue("the older layout kept the reel from its Cast button", ReelDownload.verticalUfi(false));

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("a paused build showed the Cast button", ReelDownload.showsButton());
        assertFalse(ReelDownload.castButton());
        PauseForTests.resume();

        String line = line();
        assertTrue(line, line.contains(ReelDownload.SIDEBAR_WITH_CAST + " 1"));
        assertTrue(line, line.contains(ReelDownload.SIDEBAR_HIDDEN + " 2"));
        assertTrue(line, line.contains(ReelDownload.SIDEBAR_PAUSED + " 1"));
        assertTrue(line, line.contains(ReelDownload.LAYOUT_SWITCHED + " 1"));
    }

    /** With both on, both buttons are built, and the Cast handler's slots never save the reel. */
    @Test
    public void aCastHandlerNeverSaves() throws Exception {
        Settings.REEL_CAST_BUTTON.save(true);
        assertTrue(ReelDownload.downloadButton());
        assertTrue(ReelDownload.castButton());

        AtomicInteger saves = new AtomicInteger();
        MediaDownload.detailsForTests = details -> saves.incrementAndGet();
        try {
            FileNameTemplateTest.ReelSourceParams reel = new FileNameTemplateTest.ReelSourceParams(new VideoPlayerParams(
                    "VideoId: 2233445566778899", new FileNameTemplateTest.ReelSource(
                            "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/clip_720p.mp4?oh=1&oe=2")));
            for (int slot = 0; slot <= 6; slot++) {
                new ReelDownload(reel, RuntimeEnvironment.getApplication(), "hd", "sd", "manifest",
                        slot, slot == 1, null, true).invoke(null);
            }
        } finally {
            MediaDownload.detailsForTests = null;
            LogBufferManager.clearLogBuffer();
        }
        assertEquals("a Cast slot saved the reel", 0, saves.get());
        assertEquals(0, MediaDownload.savesInFlight());
    }

    /** The phrases are the whole of what a count says: no id, name or address of a reel. */
    @Test
    public void everyCountedPhraseIsFixedText() {
        for (String phrase : new String[]{ReelDownload.SIDEBAR_WITH_BUTTON, ReelDownload.SIDEBAR_WITH_CAST,
                ReelDownload.SIDEBAR_SWITCH_OFF,
                ReelDownload.SIDEBAR_HIDDEN, ReelDownload.SIDEBAR_PAUSED, ReelDownload.SIDEBAR_NOT_READY,
                ReelDownload.OTHER_SIDEBAR, ReelDownload.LAYOUT_NEWER, ReelDownload.LAYOUT_SWITCHED,
                ReelDownload.LAYOUT_OLDER}) {
            assertTrue(phrase, phrase.matches("[A-Za-z ,]+"));
        }
        assertEquals(Collections.emptyList(), HookStatus.report());
    }
}
