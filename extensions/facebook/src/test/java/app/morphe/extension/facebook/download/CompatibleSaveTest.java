/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.media.MediaFormat;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.MediaCodecInfoBuilder;
import org.robolectric.shadows.ShadowMediaCodecList;
import org.robolectric.util.ReflectionHelpers;

import java.net.InetAddress;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Saves other apps can open, the way a reel, a story and a feed video start one (issue #11). With
 * the switch on, a save takes H.264 and AAC-LC or HE-AAC tracks, or the single MP4 when the
 * manifest has no such pair, and with nothing of either it saves what it would with the switch off
 * and the report says so. Every save here starts for real and is refused before any socket opens,
 * and the report says what it chose.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class CompatibleSaveTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Context context;

    /** What the phone found about AV1 before this test said it can write it. */
    private Boolean av1Before;

    private static final String HD = "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/clip_720p.mp4?oh=1&oe=2";
    private static final String SD = "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/clip_360p.mp4?oh=1&oe=2";

    private static final String AV1 = "av01.0.08m.08.0.111.01.01.01.0";

    /** The tracks of the S22's reel in issue #11, beside the H.264 and AAC-LC ones a manifest can offer. */
    private static final String MANIFEST = manifest(
            representation(AV1, 1080, 1920, 1_525_000, "1080p", "v1080")
                    + representation("avc1.64001f", 720, 1280, 1_200_000, "720p", "v720"),
            sound("mp4a.40.42", 84_000, "xhe") + sound("mp4a.40.2", 64_000, "lc"));

    /** AV1 pictures only, as stories often are. */
    private static final String AV1_ONLY = manifest(
            representation(AV1, 1080, 1920, 1_525_000, "1080p", "v1080"),
            sound("mp4a.40.2", 64_000, "lc"));

    /** Ceilings: an AV1 480p beside H.264 at 720p, 360p and 240p, and HE-AAC sound only. */
    private static final String LADDER = manifest(
            representation(AV1, 720, 1280, 500_000, "480p", "a480")
                    + representation("avc1.64001f", 720, 1280, 1_200_000, "720p", "h720")
                    + representation("avc1.64001e", 720, 1280, 400_000, "360p", "h360")
                    + representation("avc1.64001e", 720, 1280, 250_000, "240p", "h240"),
            sound("mp4a.40.42", 84_000, "xhe") + sound("mp4a.40.5", 48_000, "he"));

    private static final String ISSUE_11_OFF = "video/mp4 " + AV1 + " 1080x1920 1525kbps 1080p"
            + " + audio/mp4 mp4a.40.2 0x0 64kbps";
    private static final String ISSUE_11_ON = "video/mp4 avc1.64001f 720x1280 1200kbps 720p"
            + " + audio/mp4 mp4a.40.2 0x0 64kbps";
    private static final String KEPT = ", kept to files other apps can open";
    /** A single file's line when the switch is why it was saved: why, never what formats it holds. */
    private static final String TAKEN = ", taken over the manifest's better tracks, which aren't H.264 with AAC-LC "
            + "or HE-AAC sound";

    private static String manifest(String videos, String sounds) {
        return "<MPD><Period><AdaptationSet mimeType=\"video/mp4\">" + videos
                + "</AdaptationSet><AdaptationSet mimeType=\"audio/mp4\">" + sounds
                + "</AdaptationSet></Period></MPD>";
    }

    private static String representation(String codecs, int width, int height, long bandwidth, String label, String name) {
        return "<Representation codecs=\"" + codecs + "\" width=\"" + width + "\" height=\"" + height
                + "\" bandwidth=\"" + bandwidth + "\" FBQualityLabel=\"" + label + "\">"
                + "<BaseURL>https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/" + name + ".mp4?oh=1&amp;oe=2</BaseURL>"
                + "</Representation>";
    }

    private static String sound(String codecs, long bandwidth, String name) {
        return "<Representation codecs=\"" + codecs + "\" bandwidth=\"" + bandwidth + "\">"
                + "<BaseURL>https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/" + name + ".mp4?oh=1&amp;oe=2</BaseURL>"
                + "</Representation>";
    }

    /** A reel's player source, its fields named the way the patch passes them. */
    static final class ReelSource {
        final String hd;
        final String sd;
        final String manifest;

        ReelSource(String hd, String sd, String manifest) {
            this.hd = hd;
            this.sd = sd;
            this.manifest = manifest;
        }
    }

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        LogBufferManager.clearLogBuffer();
        // Every Meta name answers a private address, so each save is refused before it connects.
        MediaDownload.policyForTests = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") });
        // The phone in issue #11 runs Android 16 with an AV1 decoder. Robolectric's Android 11 has
        // none, and with the switch off a save would never pick AV1 here.
        av1Before = ReflectionHelpers.getStaticField(DashSave.class, "canWriteAv1");
        ReflectionHelpers.setStaticField(DashSave.class, "canWriteAv1", Boolean.TRUE);
    }

    @After
    public void tearDown() throws InterruptedException {
        waitForSaves();
        ReflectionHelpers.setStaticField(DashSave.class, "canWriteAv1", av1Before);
        MediaDownload.policyForTests = null;
        Settings.DOWNLOAD_COMPATIBLE.resetToDefault();
        Settings.DOWNLOAD_QUALITY.resetToDefault();
        Settings.DOWNLOAD_VIDEOS.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        PauseForTests.resume();
        LogBufferManager.clearLogBuffer();
    }

    private static void waitForSaves() throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (MediaDownload.savesInFlight() > 0) {
            assertTrue("a save never finished", System.nanoTime() < deadline);
            Thread.sleep(10);
        }
    }

    /** The report after one reel save from [source], with the switch set to [compatible]. */
    private String reelSave(boolean compatible, ReelSource source) throws InterruptedException {
        Settings.DOWNLOAD_COMPATIBLE.save(compatible);
        LogBufferManager.clearLogBuffer();
        assertTrue(MediaDownload.saveVideo(context, source, "hd", "sd", "manifest"));
        waitForSaves();
        return LogBufferManager.buildExportText();
    }

    private static int count(String text, String part) {
        int found = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) found++;
        return found;
    }

    @Test
    public void theSwitchStartsOffAndKeepsTheBestPictureWithSaferSound() throws Exception {
        assertFalse("the switch doesn't start off", Settings.DOWNLOAD_COMPATIBLE.get());
        assertFalse(MediaDownload.compatibleSaves());
        // Keep the S22's 1080p picture, but take AAC-LC when the manifest offers it.
        String report = reelSave(false, new ReelSource(HD, null, MANIFEST));
        assertTrue(report, report.contains("saving the reel from its DASH manifest: " + ISSUE_11_OFF
                + ", instead of mp4 (720p)\n"));
        assertFalse(report, report.contains("other apps"));
    }

    @Test
    public void onAReelSavesH264AndAacLc() throws Exception {
        String report = reelSave(true, new ReelSource(null, SD, MANIFEST));
        assertTrue(report, report.contains("saving the reel from its DASH manifest: " + ISSUE_11_ON
                + ", instead of mp4 (360p)" + KEPT));

    }

    /** A file URL says nothing about its audio/video codecs, even when its quality is higher. */
    @Test
    public void aKnownCompatiblePairWinsOverAnUncheckedSingleFile() throws Exception {
        for (String file : new String[]{HD, HD.replace("720p", "1080p")}) {
            String report = reelSave(true, new ReelSource(file, null, MANIFEST));
            assertTrue(report, report.contains("saving the reel from its DASH manifest: " + ISSUE_11_ON));
            assertTrue(report, report.contains(KEPT));
        }
    }

    @Test
    public void unsupportedManifestAudioUsesTheCompleteSingleFile() throws Exception {
        String opus = manifest(representation("avc1.640028", 1080, 1920, 2_000_000, "1080p", "v1080"),
                sound("opus", 96_000, "opus"));
        String report = reelSave(false, new ReelSource(HD, null, opus));
        assertFalse(report, report.contains("saving the reel from its DASH manifest"));
        assertTrue(report, report.contains("saving video mp4 (720p)"));
    }

    /**
     * A save with no manifest cannot claim checked codecs. When a compatible pair exists, its
     * known formats take priority over the unchecked file's larger dimensions.
     */
    @Test
    public void checkedFormatsTakePriorityOverTheSingleFilesResolution() throws Exception {
        String report = reelSave(true, new ReelSource(HD, null, null));
        assertTrue(report, report.contains("saving video mp4 (720p) from 1 candidate(s): mp4 (720p)\n"));

        String small = manifest(representation("avc1.64001e", 360, 640, 400_000, "360p", "h360"),
                sound("mp4a.40.2", 64_000, "lc"));
        report = reelSave(true, new ReelSource(HD, null, small));
        assertTrue(report, report.contains("saving the reel from its DASH manifest: video/mp4 avc1.64001e 360x640"));
        assertTrue(report, report.contains(KEPT));
        assertFalse(report, report.contains(TAKEN));
    }

    /** No H.264 picture: Facebook's single MP4, whose formats the save doesn't read. */
    @Test
    public void anAv1OnlyManifestSavesTheSingleFile() throws Exception {
        String report = reelSave(true, new ReelSource(HD, null, AV1_ONLY));
        assertTrue(report, report.contains("the manifest of the reel has no H.264 video with AAC-LC or HE-AAC sound, "
                + "saving the single file instead"));
        assertFalse(report, report.contains("from its DASH manifest"));
        assertTrue(report, report.contains("saving video mp4 (720p) from 1 candidate(s): mp4 (720p)" + TAKEN + "\n"));

        report = reelSave(false, new ReelSource(HD, null, AV1_ONLY));
        assertTrue(report, report.contains("saving the reel from its DASH manifest: video/mp4 " + AV1));
    }

    /**
     * Nothing other apps can open, and no single file: a file some apps turn down beats none, so
     * the save takes what it would with the switch off, and says so once.
     */
    @Test
    public void withNothingCompatibleTheSaveTakesTodaysPickAndSaysSo() throws Exception {
        String report = reelSave(true, new ReelSource(null, null, AV1_ONLY));
        String note = "nothing of the reel is in a format other apps can open, saving it as the switch off would: "
                + "video/mp4 " + AV1 + " 1080x1920 1525kbps 1080p + audio/mp4 mp4a.40.2 0x0 64kbps";
        assertTrue(report, report.contains(note));
        assertEquals(report, 1, count(report, "in a format other apps can open"));
        assertTrue(report, report.contains("saving the reel from its DASH manifest: video/mp4 " + AV1
                + " 1080x1920 1525kbps 1080p + audio/mp4 mp4a.40.2 0x0 64kbps, instead of nothing\n"));
        assertFalse("a save that isn't kept to other apps' formats says it is", report.contains(KEPT));

        // An H.264 picture whose only sound is xHE-AAC is no pair either.
        String xheOnly = manifest(representation("avc1.64001f", 720, 1280, 1_200_000, "720p", "v720"),
                sound("mp4a.40.42", 84_000, "xhe"));
        report = reelSave(true, new ReelSource(null, null, xheOnly));
        assertTrue(report, report.contains("nothing of the reel is in a format other apps can open"));
        report = reelSave(true, new ReelSource(null, SD, xheOnly));
        assertTrue(report, report.contains("has no H.264 video with AAC-LC or HE-AAC sound, saving the single file instead"));
        assertTrue(report, report.contains("saving video mp4 (360p) from 1 candidate(s)"));
    }

    /** What a DASH save line adds when the sound it joins is xHE-AAC. */
    private static final String XHE = ", the sound is xHE-AAC because the manifest offers no AAC-LC or HE-AAC, and "
            + "some players can't play xHE-AAC";

    /**
     * A 580 reel on 2026-09-28 listed eight AV1 pictures and four sound tracks, every one xHE-AAC,
     * and its Best save kept xHE-AAC, which some players can't play (#14). The save still keeps it,
     * since a file some players play silent beats a silent file, and its line now says so in words.
     * Beside AAC-LC the save takes AAC-LC at every quality, switch on or off, and says nothing of it.
     */
    @Test
    public void xheAacIsSavedOnlyWhenItIsTheOnlySoundAndTheLineSaysSo() throws Exception {
        String xheOnly = manifest(representation(AV1, 1080, 1920, 1_265_000, "1080p", "v1080"),
                sound("mp4a.40.42", 39_000, "xhe39") + sound("mp4a.40.42", 120_000, "xhe120"));
        String report = reelSave(false, new ReelSource(HD, null, xheOnly));
        assertTrue(report, report.contains("saving the reel from its DASH manifest: video/mp4 " + AV1
                + " 1080x1920 1265kbps 1080p + audio/mp4 mp4a.40.42 0x0 120kbps, instead of mp4 (720p)" + XHE + "\n"));
        // With the switch on and no single file, it's still the only sound there is.
        report = reelSave(true, new ReelSource(null, null, xheOnly));
        assertTrue(report, report.contains("nothing of the reel is in a format other apps can open"));
        assertTrue(report, report.contains(" + audio/mp4 mp4a.40.42 0x0 120kbps, instead of nothing" + XHE + "\n"));

        for (DownloadQuality quality : DownloadQuality.values()) {
            Settings.DOWNLOAD_QUALITY.save(quality);
            for (boolean compatible : new boolean[]{false, true}) {
                report = reelSave(compatible, new ReelSource(null, null, MANIFEST));
                String which = quality + (compatible ? " on\n" : " off\n");
                assertTrue(which + report, report.contains(" + audio/mp4 mp4a.40.2 0x0 64kbps, instead of nothing"));
                assertFalse(which + report, report.contains("mp4a.40.42 0x0 84kbps, instead of"));
                assertFalse(which + report, report.contains("xHE-AAC"));
            }
        }
    }

    /** What a DASH save line adds when its xHE-AAC sound is made AAC-LC before the join. */
    private static final String MADE_LC = ", the sound is xHE-AAC because the manifest offers no AAC-LC or HE-AAC, "
            + "made AAC-LC for apps that turn xHE-AAC down";

    /**
     * Messenger won't send a video whose sound is xHE-AAC (HushMessenger #38) and some gallery
     * players play it silent (#14), so a phone with an AAC decoder and encoder makes that sound
     * AAC-LC with the switch off too. Beside AAC-LC there's nothing to re-encode.
     */
    @Test
    public void xheOnlySoundIsMadeAacLcWithTheSwitchOffWhereThePhoneCan() throws Exception {
        MediaFormat aac = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 2);
        try {
            ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("test.aac.decoder")
                    .setCapabilities(MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                            .setMediaFormat(aac).build())
                    .build());
            ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("test.aac.encoder")
                    .setIsEncoder(true)
                    .setCapabilities(MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                            .setMediaFormat(aac).setIsEncoder(true).build())
                    .build());
            assertTrue("the test phone can't re-encode", AacReencode.available());

            String xheOnly = manifest(representation(AV1, 1080, 1920, 1_265_000, "1080p", "v1080"),
                    sound("mp4a.40.42", 39_000, "xhe39") + sound("mp4a.40.42", 120_000, "xhe120"));
            String report = reelSave(false, new ReelSource(HD, null, xheOnly));
            assertTrue(report, report.contains(" + audio/mp4 mp4a.40.42 0x0 120kbps, instead of mp4 (720p)" + MADE_LC + "\n"));

            report = reelSave(false, new ReelSource(null, null, MANIFEST));
            assertTrue(report, report.contains(" + audio/mp4 mp4a.40.2 0x0 64kbps, instead of nothing"));
            assertFalse(report, report.contains("xHE-AAC"));
        } finally {
            ShadowMediaCodecList.reset();
        }
    }

    /** 480p asks for the best H.264 at or under 480p, with HE-AAC when there's no AAC-LC. */
    @Test
    public void theSwitchKeepsWithinTheDownloadQuality() throws Exception {
        Settings.DOWNLOAD_QUALITY.save(DownloadQuality.P480);
        String report = reelSave(true, new ReelSource(null, null, LADDER));
        assertTrue(report, report.contains("saving the reel from its DASH manifest: video/mp4 avc1.64001e 720x1280 "
                + "400kbps 360p + audio/mp4 mp4a.40.5 0x0 48kbps, instead of nothing, quality setting 480p" + KEPT));

        report = reelSave(false, new ReelSource(null, null, LADDER));
        assertTrue(report, report.contains("saving the reel from its DASH manifest: video/mp4 " + AV1 + " 720x1280 "
                + "500kbps 480p + audio/mp4 mp4a.40.5 0x0 48kbps, instead of nothing, quality setting 480p\n"));

        Settings.DOWNLOAD_QUALITY.save(DownloadQuality.SMALLEST);
        report = reelSave(true, new ReelSource(null, null, LADDER));
        assertTrue(report, report.contains("from its DASH manifest: video/mp4 avc1.64001e 720x1280 250kbps 240p"));
    }

    /** A story and a feed video take the same route as a reel. */
    @Test
    public void storiesAndFeedVideosKeepToItToo() throws Exception {
        Settings.DOWNLOAD_COMPATIBLE.save(true);

        String story = "300000000000" + System.nanoTime() % 1000;
        PlayerSources.remember(new PlayerSourcesForTests.Params(story,
                new PlayerSourcesForTests.HdSource(null, MANIFEST)), "videoId", "hd", "manifest");
        assertTrue(MediaDownload.saveStory(context, new PlayerSourcesForTests.Card(story)));
        waitForSaves();
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("saving the story video from its DASH manifest: " + ISSUE_11_ON));

        LogBufferManager.clearLogBuffer();
        Settings.DOWNLOAD_VIDEOS.save(true);
        String feed = "400000000000" + System.nanoTime() % 1000;
        PlayerSources.rememberVideo(new PlayerSourcesForTests.Params(feed,
                new PlayerSourcesForTests.HdSource(null, AV1_ONLY)), "videoId", "hd", "manifest");
        assertTrue(MediaDownload.saveFeedVideo(context, feed, HD, SD));
        waitForSaves();
        report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("the manifest of the video has no H.264 video"));
        assertTrue(report, report.contains("saving video mp4 (720p) from 2 candidate(s)"));
    }

    /**
     * With Debug logging on, the report lists every track the manifest offered, as type, codec,
     * size and bitrate, and never an address.
     */
    @Test
    public void debugLoggingListsTheOfferedTracks() throws Exception {
        BaseSettings.DEBUG.save(true);
        String report = reelSave(true, new ReelSource(null, SD, MANIFEST));
        String offered = "the manifest of the reel offers 4 track(s): [video/mp4 " + AV1 + " 1080x1920 1525kbps 1080p, "
                + "video/mp4 avc1.64001f 720x1280 1200kbps 720p, audio/mp4 mp4a.40.42 0x0 84kbps, "
                + "audio/mp4 mp4a.40.2 0x0 64kbps]";
        assertTrue(report, report.contains(offered));
        String line = report.substring(report.lastIndexOf('\n', report.indexOf(offered)) + 1,
                report.indexOf('\n', report.indexOf(offered)));
        assertTrue(line, line.contains("DEBUG"));
        assertFalse(line, line.contains("http") || line.contains("fbcdn") || line.contains(".mp4"));

        BaseSettings.DEBUG.save(false);
        report = reelSave(true, new ReelSource(null, SD, MANIFEST));
        assertFalse("a Debug line reached the report with Debug logging off", report.contains("offers 4 track(s)"));
        assertTrue(report, report.contains(ISSUE_11_ON));
    }

    /** Before the settings can be read, and while paused, the switch is off. */
    @Test
    public void beforeTheSettingsAreReadyOrPausedTheSwitchIsOff() {
        Settings.DOWNLOAD_COMPATIBLE.save(true);
        assertTrue(MediaDownload.compatibleSaves());
        SettingsContextRule.withoutContext(() -> assertFalse(MediaDownload.compatibleSaves()));
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(MediaDownload.compatibleSaves());
        PauseForTests.resume();
        assertTrue(Settings.DOWNLOAD_COMPATIBLE.savedValue());
        assertTrue(MediaDownload.compatibleSaves());
    }
}
