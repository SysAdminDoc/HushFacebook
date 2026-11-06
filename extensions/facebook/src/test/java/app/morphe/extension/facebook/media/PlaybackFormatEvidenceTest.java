/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.media.MediaFormat;
import android.os.Build;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Playback format evidence: with Debug logging on, each decoder's format is written as fixed
 * words and bounded numbers under the report's other category, beside Turn off HDR brightness's
 * lines. Nothing else in the format gets out, the format itself is left as it was, missing colour
 * metadata never reads as SDR, and a run keeps a bounded number of distinct lines. Off, or before
 * the settings are ready, nothing is read.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {30, 36})
public class PlaybackFormatEvidenceTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private static final String PRIVATE = "PRIVATE_112233 https://private.invalid/authenticated";

    @Before
    public void prepare() {
        BaseSettings.DEBUG.save(true);
        PauseForTests.resume();
        PlaybackFormatEvidence.resetForTests();
        ShadowLog.clear();
        LogBufferManager.clearLogBuffer();
    }

    @After
    public void restore() {
        BaseSettings.DEBUG.resetToDefault();
        PauseForTests.resume();
        PlaybackFormatEvidence.resetForTests();
        LogBufferManager.clearLogBuffer();
    }

    private static MediaFormat video(int width, int transfer) {
        MediaFormat format = MediaFormat.createVideoFormat("video/av01", width, 1920);
        format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, transfer);
        format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020);
        format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        format.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, ByteBuffer.wrap(PRIVATE.getBytes()));
        format.setString("id", PRIVATE);
        format.setString("url", PRIVATE);
        format.setString("title", PRIVATE);
        return format;
    }

    /** The exported report, checked first for anything private in it or in logcat. */
    private String evidence() {
        String result = LogBufferManager.buildExportText();
        assertFalse("format contents or an exception value escaped", result.contains(PRIVATE));
        assertFalse("static HDR bytes escaped", result.contains("PRIVATE_112233"));
        for (ShadowLog.LogItem line : ShadowLog.getLogs()) {
            if (line.tag.contains(PlaybackFormatEvidence.SOURCE)) {
                assertFalse("raw logs included private fields", line.msg.contains(PRIVATE));
                assertFalse("raw logs included HDR bytes", line.msg.contains("PRIVATE_112233"));
            }
        }
        return result;
    }

    @Test
    public void explicitMetadataNamesTheRouteAndResolutionWithoutChangingThePassedFormat() {
        MediaFormat format = video(1080, MediaFormat.COLOR_TRANSFER_ST2084);
        ByteBuffer bytes = format.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO);
        PlaybackFormatEvidence.platformInput(format);
        String logs = evidence();
        assertTrue(logs, logs.contains("platform input video/av01 1080x1920 transfer=PQ"));
        assertTrue(logs, logs.contains("standard=BT2020 range=limited static-info=present"));
        assertTrue(logs, logs.contains("metadata-complete=true"));
        assertEquals(MediaFormat.COLOR_TRANSFER_ST2084, format.getInteger(MediaFormat.KEY_COLOR_TRANSFER));
        assertEquals(1080, format.getInteger(MediaFormat.KEY_WIDTH));
        assertEquals(PRIVATE, format.getString("url"));
        assertSame(bytes, format.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO));
        assertEquals(0, bytes.position());
        assertFalse(format.containsKey("color-transfer-request"));
    }

    /** A report taken after a diagnostic clear still has the line for a format seen before it. */
    @Test
    public void aFormatSeenBeforeADiagnosticClearIsWrittenAgainAfterIt() {
        String line = PlaybackFormatEvidence.PREFIX + "platform input video/av01 1080x1920 transfer=PQ";
        PlaybackFormatEvidence.platformInput(video(1080, MediaFormat.COLOR_TRANSFER_ST2084));
        assertTrue(evidence().contains(line));
        LogBufferManager.clearLogBuffer();
        assertFalse(evidence().contains(line));
        PlaybackFormatEvidence.platformInput(video(1080, MediaFormat.COLOR_TRANSFER_ST2084));
        assertTrue(evidence().contains(line));
        // Without a clear it's still one line until the 50th repeat.
        PlaybackFormatEvidence.platformInput(video(1080, MediaFormat.COLOR_TRANSFER_ST2084));
        String logs = evidence();
        assertEquals(logs, logs.indexOf(line), logs.lastIndexOf(line));
    }

    /** The report files it with the other media lines, Turn off HDR brightness's among them, not under feed. */
    @Test
    public void theLineGoesUnderTheReportsOtherCategoryByItsOwnName() {
        PlaybackFormatEvidence.platformInput(video(1080, MediaFormat.COLOR_TRANSFER_HLG));
        boolean filed = false;
        for (String line : evidence().split("\n")) {
            if (!line.contains(PlaybackFormatEvidence.PREFIX + "platform input")) continue;
            assertTrue(line, line.startsWith(DiagnosticCategory.OTHER.value + " | "));
            assertTrue(line, line.contains(" | " + PlaybackFormatEvidence.SOURCE + " | "));
            filed = true;
        }
        assertTrue("no evidence line in the report", filed);
    }

    @Test
    public void aNativeOutputWithoutColorMetadataNeverBecomesSdrEvidence() {
        MediaFormat format = MediaFormat.createVideoFormat(null, 1080, 1920);
        PlaybackFormatEvidence.dav1dOutput(format);
        String logs = evidence();
        assertTrue(logs, logs.contains("dav1d output unknown 1080x1920 transfer=unknown"));
        assertTrue(logs, logs.contains("metadata-complete=false"));
        assertFalse(logs, logs.contains("transfer=SDR"));
    }

    @Test
    public void anSdrOutputAndHlgInputAreDistinctEvidence() {
        PlaybackFormatEvidence.dav1dInput(video(1080, MediaFormat.COLOR_TRANSFER_HLG));
        PlaybackFormatEvidence.platformOutput(video(1080, MediaFormat.COLOR_TRANSFER_SDR_VIDEO));
        String logs = evidence();
        assertTrue(logs, logs.contains("dav1d input video/av01 1080x1920 transfer=HLG"));
        assertTrue(logs, logs.contains("platform output video/av01 1080x1920 transfer=SDR"));
    }

    @Test
    public void debugOffReadsAndLogsNothingAndPauseKeepsReadOnlyIntake() {
        BaseSettings.DEBUG.save(false);
        PlaybackFormatEvidence.platformInput(video(1080, MediaFormat.COLOR_TRANSFER_ST2084));
        assertFalse(evidence().contains(PlaybackFormatEvidence.PREFIX));
        BaseSettings.DEBUG.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        MediaFormat format = video(1080, MediaFormat.COLOR_TRANSFER_ST2084);
        PlaybackFormatEvidence.platformInput(format);
        assertTrue(evidence().contains("platform input"));
        assertEquals(MediaFormat.COLOR_TRANSFER_ST2084, format.getInteger(MediaFormat.KEY_COLOR_TRANSFER));
    }

    @Test
    public void startupBeforeSettingsAreReadyDoesNotReadFormatsOrInitializeIntake() throws Exception {
        Field ready = Utils.class.getDeclaredField("settingsReady");
        ready.setAccessible(true);
        boolean saved = ready.getBoolean(null);
        try {
            ready.setBoolean(null, false);
            PlaybackFormatEvidence.platformInput(video(1080, MediaFormat.COLOR_TRANSFER_ST2084));
            PlaybackFormatEvidence.dav1dOutput(null);
            assertFalse(evidence().contains(PlaybackFormatEvidence.PREFIX));
        } finally {
            ready.setBoolean(null, saved);
        }
        PlaybackFormatEvidence.platformInput(video(1080, MediaFormat.COLOR_TRANSFER_ST2084));
        assertTrue(evidence().contains("samples=1"));
    }

    @Test
    public void malformedFieldsAndPrivateMimeDoNotEscapeOrPretendToBeKnownMetadata() {
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, PRIVATE);
        format.setString(MediaFormat.KEY_WIDTH, PRIVATE);
        format.setInteger(MediaFormat.KEY_HEIGHT, 1122334455);
        format.setString(MediaFormat.KEY_COLOR_TRANSFER, PRIVATE);
        PlaybackFormatEvidence.platformOutput(format);
        PlaybackFormatEvidence.dav1dInput(null);
        String logs = evidence();
        assertTrue(logs, logs.contains("unknown unknownxunknown transfer=unknown"));
        assertTrue(logs, logs.contains("metadata-complete=false"));
        assertFalse(logs, logs.contains("1122334455"));
    }

    @Test
    public void audioFormatsDoNotSpendTheVideoEvidenceBudget() {
        for (int i = 0; i < 40; i++) {
            PlaybackFormatEvidence.platformInput(MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, 2));
        }
        assertFalse(evidence().contains(PlaybackFormatEvidence.PREFIX));
        PlaybackFormatEvidence.platformInput(video(1080, MediaFormat.COLOR_TRANSFER_ST2084));
        assertTrue(evidence().contains("1080x1920"));
        assertFalse(evidence().contains("additional shapes omitted"));
    }

    @Test
    public void theExactUniqueShapeLimitReportsDroppedEvidenceAndRetainsDuplicates() {
        for (int i = 0; i < PlaybackFormatEvidence.MAX_SHAPES; i++) {
            PlaybackFormatEvidence.platformInput(video(640 + i, MediaFormat.COLOR_TRANSFER_ST2084));
        }
        assertFalse(evidence().contains("additional shapes omitted"));
        for (int i = 1; i < 50; i++) {
            PlaybackFormatEvidence.platformInput(video(640, MediaFormat.COLOR_TRANSFER_ST2084));
        }
        assertTrue(evidence().contains("640x1920 transfer=PQ"));
        assertTrue(evidence().contains("samples=50"));
        assertFalse(evidence().contains("additional shapes omitted"));
        PlaybackFormatEvidence.platformInput(video(900, MediaFormat.COLOR_TRANSFER_ST2084));
        PlaybackFormatEvidence.platformInput(video(901, MediaFormat.COLOR_TRANSFER_ST2084));
        String logs = evidence();
        assertTrue(logs, logs.contains("additional shapes omitted; evidence-complete=false"));
        assertFalse(logs, logs.contains("900x1920"));
        assertEquals(1, logs.split("additional shapes omitted", -1).length - 1);
    }

    @Test
    public void aToneMappingRequestIsReadAsARequestRatherThanAnOutputGuarantee() {
        MediaFormat format = video(1080, MediaFormat.COLOR_TRANSFER_ST2084);
        if (Build.VERSION.SDK_INT >= 31) {
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
        }
        PlaybackFormatEvidence.platformInput(format);
        String logs = evidence();
        assertTrue(logs, logs.contains("transfer=PQ"));
        assertTrue(logs, logs.contains(Build.VERSION.SDK_INT >= 31 ? "transfer-request=SDR" : "transfer-request=absent"));
        assertFalse(logs, logs.contains("tone-mapped"));
    }

    @Test
    @Config(sdk = 36)
    public void anUnreadablePresentToneMappingRequestMarksEvidenceIncomplete() {
        MediaFormat format = video(1080, MediaFormat.COLOR_TRANSFER_ST2084);
        format.setString("color-transfer-request", PRIVATE);
        PlaybackFormatEvidence.platformInput(format);
        String logs = evidence();
        assertTrue(logs, logs.contains("transfer-request=unknown"));
        assertTrue(logs, logs.contains("metadata-complete=false"));
        assertEquals(PRIVATE, format.getString("color-transfer-request"));
    }
}
