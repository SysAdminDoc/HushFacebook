/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.Window;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowMediaCodec;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Turn off HDR brightness: with its switch on, Facebook's request for an HDR window comes out as
 * one for the default colour mode, a headroom as none, on Android 15 a SurfaceView Facebook
 * builds asks for none, and an HDR screen answers Facebook's questions as one that shows no HDR.
 * Every other colour mode goes through, and off or paused Facebook's requests and questions go
 * through as asked.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class HdrBrightnessTest {
    private static final HushfacebookPause.Reason[] PAUSES = {
            HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
            HushfacebookPause.Reason.MARKER_FILE};

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Activity activity;

    @Before
    public void start() {
        HookStatus.clear();
        activity = Robolectric.buildActivity(Activity.class).create().get();
        // The patch is in Morphe Manager's default selection with its switch off; these tests run with it on.
        Settings.TURN_OFF_HDR_BRIGHTNESS.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.TURN_OFF_HDR_BRIGHTNESS.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.HDR_BRIGHTNESS + ":")) return line;
        }
        return null;
    }

    /** The colour mode the window ends up in once Facebook asks for [mode]. */
    private int asked(int mode) {
        Window window = activity.getWindow();
        window.setColorMode(ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT);
        HdrBrightness.setColorMode(window, mode);
        return window.getColorMode();
    }

    private float askedHeadroom(float headroom) {
        Window window = activity.getWindow();
        window.setDesiredHdrHeadroom(0f);
        HdrBrightness.setDesiredHdrHeadroom(window, headroom);
        return window.getDesiredHdrHeadroom();
    }

    @Test
    public void anHdrWindowStaysInTheUsualRange() {
        assertEquals("an HDR window", ActivityInfo.COLOR_MODE_DEFAULT, asked(ActivityInfo.COLOR_MODE_HDR));
        assertEquals("the default mode", ActivityInfo.COLOR_MODE_DEFAULT, asked(ActivityInfo.COLOR_MODE_DEFAULT));
        assertEquals("wide colour", ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT, asked(ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT));
        assertEquals(FamilyNames.HDR_BRIGHTNESS + ": invoked 1, 1 found, 0 missing. Counted: "
                + HdrBrightness.WINDOW_HELD + " 1", statusLine());
    }

    @Test
    public void theHeadroomIsHeldToNone() {
        assertEquals(HdrBrightness.NO_HEADROOM, askedHeadroom(4f), 0f);
        assertEquals(HdrBrightness.NO_HEADROOM, askedHeadroom(100f), 0f);
        assertEquals(FamilyNames.HDR_BRIGHTNESS + ": invoked 2, 1 found, 0 missing. Counted: "
                + HdrBrightness.HEADROOM_HELD + " 2", statusLine());
    }

    /**
     * The phone's screen, set up to show HLG and HDR10, the kind Facebook lifts ordinary videos on.
     * From DisplayManager, since an activity Robolectric only created has no display of its own.
     */
    private Display hdrScreen() {
        Display display = activity.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        shadowOf(display).setDisplayHdrCapabilities(display.getDisplayId(), 1000f, 500f, 0.1f,
                Display.HdrCapabilities.HDR_TYPE_HLG, Display.HdrCapabilities.HDR_TYPE_HDR10);
        return display;
    }

    @Test
    public void theScreenAnswersAsShowingNoHdr() {
        Display display = hdrScreen();
        assertTrue("the screen itself shows HDR", display.isHdr());
        assertEquals("the screen's own types", 2, display.getHdrCapabilities().getSupportedHdrTypes().length);

        assertFalse("isHdr", HdrBrightness.isHdr(display));
        assertEquals("the capabilities' types", 0, HdrBrightness.getSupportedHdrTypes(display.getHdrCapabilities()).length);
        assertEquals("the mode's types", 0, HdrBrightness.getSupportedHdrTypes(display.getMode()).length);
        assertEquals(FamilyNames.HDR_BRIGHTNESS + ": invoked 3, 1 found, 0 missing. Counted: "
                + HdrBrightness.SCREEN_HELD + " 3", statusLine());
    }

    @Test
    public void aSurfaceViewAsksForNoHeadroom() {
        HdrBrightness.surfaceBuilt(new SurfaceView(activity));
        assertEquals(FamilyNames.HDR_BRIGHTNESS + ": invoked 1, 1 found, 0 missing. Counted: "
                + HdrBrightness.SURFACE_HELD + " 1", statusLine());
    }

    @Test
    @Config(sdk = 34)
    public void beforeAndroid15ASurfaceViewIsLeftAlone() {
        HdrBrightness.surfaceBuilt(new SurfaceView(activity));
        assertNull("a SurfaceView on Android 14 was counted", statusLine());
        // Android 14 has the HDR window and no headroom, and the window is still kept in the usual range.
        assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, asked(ActivityInfo.COLOR_MODE_HDR));
    }

    @Test
    public void offOrPausedFacebooksRequestsGoThrough() {
        Settings.TURN_OFF_HDR_BRIGHTNESS.save(false);
        assertFacebooks("off");
        Settings.TURN_OFF_HDR_BRIGHTNESS.save(true);
        for (HushfacebookPause.Reason reason : PAUSES) {
            PauseForTests.pause(reason);
            assertFacebooks("paused by " + reason);
            PauseForTests.resume();
        }
        assertEquals("the switch didn't come back after the pause",
                ActivityInfo.COLOR_MODE_DEFAULT, asked(ActivityInfo.COLOR_MODE_HDR));
    }

    /** A video format as Facebook's player hands its decoder one: VP9, with [transfer] when it isn't -1. */
    private static MediaFormat video(int transfer) {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_VP9, 720, 1280);
        if (transfer != -1) format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, transfer);
        return format;
    }

    private static Surface surface() {
        return new Surface(new SurfaceTexture(0));
    }

    @Test
    public void anHdrVideoDecoderIsAskedForTheUsualRange() {
        for (int transfer : new int[] {MediaFormat.COLOR_TRANSFER_HLG, MediaFormat.COLOR_TRANSFER_ST2084}) {
            MediaFormat format = video(transfer);
            assertTrue("transfer " + transfer, HdrBrightness.askForUsualRange(format, surface(), 0));
            assertEquals("transfer " + transfer, MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    format.getInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST));
        }
    }

    @Test
    public void onlyAnHdrVideoDecodingToASurfaceIsAsked() {
        assertNotAsked("an SDR video", video(MediaFormat.COLOR_TRANSFER_SDR_VIDEO), surface(), 0);
        assertNotAsked("a video naming no transfer or profile", video(-1), surface(), 0);
        assertNotAsked("no surface", video(MediaFormat.COLOR_TRANSFER_HLG), null, 0);
        assertNotAsked("an encoder", video(MediaFormat.COLOR_TRANSFER_HLG), surface(), MediaCodec.CONFIGURE_FLAG_ENCODE);
        MediaFormat sound = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 2);
        sound.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG);
        assertNotAsked("sound", sound, surface(), 0);
        MediaFormat facebooks = video(MediaFormat.COLOR_TRANSFER_HLG);
        facebooks.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_HLG);
        assertFalse("a request of Facebook's own", HdrBrightness.askForUsualRange(facebooks, surface(), 0));
        assertEquals("Facebook's own request", MediaFormat.COLOR_TRANSFER_HLG,
                facebooks.getInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST));
        assertNull("something was counted", statusLine());
    }

    private static void assertNotAsked(String what, MediaFormat format, Surface surface, int flags) {
        assertFalse(what, HdrBrightness.askForUsualRange(format, surface, flags));
        assertFalse(what + " holds a request", format.containsKey(MediaFormat.KEY_COLOR_TRANSFER_REQUEST));
    }

    @Test
    public void anHdrProfileCountsWhenNoTransferIsNamed() {
        MediaFormat hevc = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1080, 1920);
        hevc.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10);
        assertTrue("HEVC HDR10", HdrBrightness.hdrVideo(hevc));
        MediaFormat vp9 = video(-1);
        vp9.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR);
        assertTrue("VP9 profile 2 HDR", HdrBrightness.hdrVideo(vp9));
        vp9.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.VP9Profile0);
        assertFalse("VP9 profile 0", HdrBrightness.hdrVideo(vp9));
        MediaFormat labelled = video(MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
        labelled.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR);
        assertFalse("an SDR transfer beats the profile", HdrBrightness.hdrVideo(labelled));
        assertTrue("Dolby Vision", HdrBrightness.hdrVideo(
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION, 1080, 1920)));
    }

    @Test
    public void aDecoderIsSetUpAndItsAnswerCounted() throws Exception {
        ShadowMediaCodec.clearCodecs();
        ShadowMediaCodec.addDecoder(MediaFormat.MIMETYPE_VIDEO_VP9, new ShadowMediaCodec.CodecConfig(1_024, 1_024, (in, out) -> { }));
        try {
            MediaCodec codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_VP9);
            HdrBrightness.configure(codec, video(MediaFormat.COLOR_TRANSFER_HLG), surface(), null, 0);
            // Robolectric's decoder keeps what it's given, as one that tone-maps does.
            assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, codec.getInputFormat().getInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST));
            assertEquals(FamilyNames.HDR_BRIGHTNESS + ": invoked 1, 1 found, 0 missing. Counted: "
                    + HdrBrightness.DECODER_TONE_MAPS + " 1", statusLine());
            codec.release();

            MediaCodec plain = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_VP9);
            HdrBrightness.configure(plain, video(MediaFormat.COLOR_TRANSFER_SDR_VIDEO), surface(), null, 0);
            assertFalse("an SDR video was asked", plain.getInputFormat().containsKey(MediaFormat.KEY_COLOR_TRANSFER_REQUEST));
            plain.release();
        } finally {
            ShadowMediaCodec.clearCodecs();
        }
    }

    @Test
    public void aDecoderThatDropsTheRequestCantToneMap() {
        assertFalse("no request kept", HdrBrightness.keptRequest(video(MediaFormat.COLOR_TRANSFER_HLG)));
        assertFalse("no input format", HdrBrightness.keptRequest(null));
        MediaFormat kept = video(MediaFormat.COLOR_TRANSFER_HLG);
        kept.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
        assertTrue("the request kept", HdrBrightness.keptRequest(kept));
    }

    @Test
    @Config(sdk = 30)
    public void beforeAndroid12NoDecoderIsAsked() {
        assertNotAsked("Android 11", video(MediaFormat.COLOR_TRANSFER_HLG), surface(), 0);
    }

    private void assertFacebooks(String when) {
        HookStatus.clear();
        assertEquals(when + ", an HDR window", ActivityInfo.COLOR_MODE_HDR, asked(ActivityInfo.COLOR_MODE_HDR));
        assertEquals(when + ", the headroom", 4f, askedHeadroom(4f), 0f);
        HdrBrightness.surfaceBuilt(new SurfaceView(activity));
        Display display = hdrScreen();
        assertTrue(when + ", isHdr", HdrBrightness.isHdr(display));
        assertArrayEquals(when + ", the capabilities' types", display.getHdrCapabilities().getSupportedHdrTypes(),
                HdrBrightness.getSupportedHdrTypes(display.getHdrCapabilities()));
        assertArrayEquals(when + ", the mode's types", display.getMode().getSupportedHdrTypes(),
                HdrBrightness.getSupportedHdrTypes(display.getMode()));
        assertNotAsked(when + ", an HDR video decoder", video(MediaFormat.COLOR_TRANSFER_HLG), surface(), 0);
        String line = statusLine();
        assertEquals(when + ", the report", FamilyNames.HDR_BRIGHTNESS + ": invoked 7, 0 found, 0 missing", line);
    }
}
