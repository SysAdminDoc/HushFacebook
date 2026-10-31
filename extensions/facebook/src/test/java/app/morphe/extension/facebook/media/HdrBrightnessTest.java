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
import android.os.Build;
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
import org.robolectric.util.ReflectionHelpers;

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
        // Android 12 is where Build grew this field.
        if (Build.VERSION.SDK_INT >= 31) ReflectionHelpers.setStaticField(Build.class, "SOC_MODEL", Build.UNKNOWN);
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
            assertTrue("transfer " + transfer, HdrBrightness.askForUsualRange(format, surface(), 0, false));
            assertEquals("transfer " + transfer, MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    format.getInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST));
        }
    }

    /**
     * #93: a VP9 HLG reel came back from a phone's tone map labelled BT.709, bright and yellow. An
     * HLG video goes to its decoder as one at the usual range in its own BT.2020 colours; a PQ video
     * keeps PQ, since it has no plain form and only the tone map brings it down.
     */
    @Test
    public void anHlgVideoGoesToItsDecoderAtTheUsualRangeInItsOwnColours() {
        MediaFormat hlg = video(MediaFormat.COLOR_TRANSFER_HLG);
        hlg.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020);
        hlg.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        assertTrue(HdrBrightness.askForUsualRange(hlg, surface(), 0, false));
        assertEquals("the transfer", MediaFormat.COLOR_TRANSFER_SDR_VIDEO, hlg.getInteger(MediaFormat.KEY_COLOR_TRANSFER));
        assertEquals("the colours", MediaFormat.COLOR_STANDARD_BT2020, hlg.getInteger(MediaFormat.KEY_COLOR_STANDARD));
        assertEquals("the range", MediaFormat.COLOR_RANGE_LIMITED, hlg.getInteger(MediaFormat.KEY_COLOR_RANGE));

        MediaFormat pq = video(MediaFormat.COLOR_TRANSFER_ST2084);
        assertTrue(HdrBrightness.askForUsualRange(pq, surface(), 0, false));
        assertEquals("PQ's transfer", MediaFormat.COLOR_TRANSFER_ST2084, pq.getInteger(MediaFormat.KEY_COLOR_TRANSFER));

        Settings.TURN_OFF_HDR_BRIGHTNESS.save(false);
        MediaFormat off = video(MediaFormat.COLOR_TRANSFER_HLG);
        assertFalse(HdrBrightness.askForUsualRange(off, surface(), 0, false));
        assertEquals("off, HLG stays", MediaFormat.COLOR_TRANSFER_HLG, off.getInteger(MediaFormat.KEY_COLOR_TRANSFER));
    }

    /** A decoder that refuses the request is set up again with Facebook's own format, HLG and all. */
    @Test
    public void aRefusedHlgVideoGetsFacebooksFormatBack() {
        MediaFormat hlg = video(MediaFormat.COLOR_TRANSFER_HLG);
        assertTrue(HdrBrightness.askForUsualRange(hlg, surface(), 0, false));
        assertTrue(HdrBrightness.withdraw(hlg, true));
        assertFalse("the request", hlg.containsKey(MediaFormat.KEY_COLOR_TRANSFER_REQUEST));
        assertEquals("the transfer", MediaFormat.COLOR_TRANSFER_HLG, hlg.getInteger(MediaFormat.KEY_COLOR_TRANSFER));
    }

    /**
     * Why an HLG signal can be shown as it is (BT.2100 and BT.2408): its reference white sits at 75%
     * of the signal, and an 18% grey card lands at 38%, against 41% on the usual BT.709 curve. The
     * midtones match within a few points, so handing the decoder HLG as the usual range keeps faces
     * and greys where a plain video has them and only folds the highlights in.
     */
    @Test
    public void hlgFollowsTheUsualCurveThroughTheMidtones() {
        double a = 0.17883277;
        double b = 1 - 4 * a;
        double c = 0.5 - a * Math.log(4 * a);
        // HLG's scene light for its reference white: the inverse of its curve at 75%.
        double referenceWhite = (Math.exp((0.75 - c) / a) + b) / 12;
        assertEquals("HLG reference white", 0.265, referenceWhite, 0.001);
        double grey = 0.18 * referenceWhite;
        double hlgGrey = grey <= 1.0 / 12 ? Math.sqrt(3 * grey) : a * Math.log(12 * grey - b) + c;
        double usualGrey = 1.099 * Math.pow(0.18, 0.45) - 0.099;
        assertEquals("HLG's 18% grey", 0.38, hlgGrey, 0.005);
        assertEquals("the usual curve's 18% grey", 0.41, usualGrey, 0.005);
        assertTrue("the midtones part", Math.abs(usualGrey - hlgGrey) < 0.04);
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
        assertFalse("a request of Facebook's own", HdrBrightness.askForUsualRange(facebooks, surface(), 0, false));
        assertEquals("Facebook's own request", MediaFormat.COLOR_TRANSFER_HLG,
                facebooks.getInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST));
        assertNull("something was counted", statusLine());
    }

    private static void assertNotAsked(String what, MediaFormat format, Surface surface, int flags) {
        assertFalse(what, HdrBrightness.askForUsualRange(format, surface, flags, false));
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
            assertEquals("the HLG video's transfer", MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    codec.getInputFormat().getInteger(MediaFormat.KEY_COLOR_TRANSFER));
            assertEquals(FamilyNames.HDR_BRIGHTNESS + ": invoked 1, 1 found, 0 missing. Counted: "
                    + HdrBrightness.HLG_AS_USUAL_RANGE + " 1, " + HdrBrightness.DECODER_TONE_MAPS + " 1", statusLine());
            codec.release();

            MediaCodec pq = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_VP9);
            HdrBrightness.configure(pq, video(MediaFormat.COLOR_TRANSFER_ST2084), surface(), null, 0);
            assertEquals("the PQ video's transfer", MediaFormat.COLOR_TRANSFER_ST2084,
                    pq.getInputFormat().getInteger(MediaFormat.KEY_COLOR_TRANSFER));
            assertEquals(FamilyNames.HDR_BRIGHTNESS + ": invoked 2, 1 found, 0 missing. Counted: "
                    + HdrBrightness.HLG_AS_USUAL_RANGE + " 1, " + HdrBrightness.DECODER_TONE_MAPS + " 2", statusLine());
            pq.release();

            MediaCodec plain = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_VP9);
            HdrBrightness.configure(plain, video(MediaFormat.COLOR_TRANSFER_SDR_VIDEO), surface(), null, 0);
            assertFalse("an SDR video was asked", plain.getInputFormat().containsKey(MediaFormat.KEY_COLOR_TRANSFER_REQUEST));
            plain.release();
        } finally {
            ShadowMediaCodec.clearCodecs();
        }
    }

    /**
     * A Snapdragon 8 Gen 1's media codec service aborts in the vendor's tone-map filter when its
     * Qualcomm decoder is sent the request on HDR reels. That decoder never gets it: an HLG video
     * still goes over at the usual range, a PQ video exactly as Facebook asked.
     */
    @Test
    public void aSnapdragon8Gen1DecoderIsNeverSentTheRequest() throws Exception {
        String name = "c2.qti.vp9.decoder";
        ReflectionHelpers.setStaticField(Build.class, "SOC_MODEL", "SM8450");
        ShadowMediaCodec.clearCodecs();
        ShadowMediaCodec.addDecoder(name, new ShadowMediaCodec.CodecConfig(1_024, 1_024, (in, out) -> { }));
        try {
            MediaCodec hlg = MediaCodec.createByCodecName(name);
            assertTrue("the decoder's name", HdrBrightness.qualcomm(hlg));
            HdrBrightness.configure(hlg, video(MediaFormat.COLOR_TRANSFER_HLG), surface(), null, 0);
            assertFalse("the HLG video was sent the request", hlg.getInputFormat().containsKey(MediaFormat.KEY_COLOR_TRANSFER_REQUEST));
            assertEquals("the HLG video's transfer", MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    hlg.getInputFormat().getInteger(MediaFormat.KEY_COLOR_TRANSFER));
            assertEquals(FamilyNames.HDR_BRIGHTNESS + ": invoked 1, 1 found, 0 missing. Counted: "
                    + HdrBrightness.HLG_AS_USUAL_RANGE + " 1, " + HdrBrightness.QUALCOMM_NOT_ASKED + " 1", statusLine());
            hlg.release();

            MediaCodec pq = MediaCodec.createByCodecName(name);
            MediaFormat format = video(MediaFormat.COLOR_TRANSFER_ST2084);
            HdrBrightness.configure(pq, format, surface(), null, 0);
            assertFalse("the PQ video was sent the request", pq.getInputFormat().containsKey(MediaFormat.KEY_COLOR_TRANSFER_REQUEST));
            assertFalse("Facebook's PQ format was changed", format.containsKey(MediaFormat.KEY_COLOR_TRANSFER_REQUEST));
            assertEquals("the PQ video's transfer", MediaFormat.COLOR_TRANSFER_ST2084,
                    pq.getInputFormat().getInteger(MediaFormat.KEY_COLOR_TRANSFER));
            assertEquals(FamilyNames.HDR_BRIGHTNESS + ": invoked 2, 1 found, 0 missing. Counted: "
                    + HdrBrightness.HLG_AS_USUAL_RANGE + " 1, " + HdrBrightness.QUALCOMM_NOT_ASKED + " 2", statusLine());
            pq.release();
        } finally {
            ShadowMediaCodec.clearCodecs();
        }
    }

    /**
     * An 8 Elite tone-maps a PQ video on any of its decoders. Its VP9 and AV1 decoders stop with a
     * fatal error when the format names no transfer, or names the usual one with the request, so a
     * video that's HDR by its profile alone isn't asked and an HLG video gets only its new name.
     */
    @Test
    public void aNewerSnapdragonDecoderIsAskedOnlyForAVideoNamingPq() throws Exception {
        String name = "c2.qti.vp9.decoder";
        ReflectionHelpers.setStaticField(Build.class, "SOC_MODEL", "SM8750");
        ShadowMediaCodec.clearCodecs();
        ShadowMediaCodec.addDecoder(name, new ShadowMediaCodec.CodecConfig(1_024, 1_024, (in, out) -> { }));
        try {
            MediaCodec pq = MediaCodec.createByCodecName(name);
            HdrBrightness.configure(pq, video(MediaFormat.COLOR_TRANSFER_ST2084), surface(), null, 0);
            assertEquals("the PQ video's request", MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    pq.getInputFormat().getInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST));
            assertEquals("the PQ video's transfer", MediaFormat.COLOR_TRANSFER_ST2084,
                    pq.getInputFormat().getInteger(MediaFormat.KEY_COLOR_TRANSFER));
            pq.release();

            MediaCodec hlg = MediaCodec.createByCodecName(name);
            HdrBrightness.configure(hlg, video(MediaFormat.COLOR_TRANSFER_HLG), surface(), null, 0);
            assertFalse("the HLG video was sent the request", hlg.getInputFormat().containsKey(MediaFormat.KEY_COLOR_TRANSFER_REQUEST));
            assertEquals("the HLG video's transfer", MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    hlg.getInputFormat().getInteger(MediaFormat.KEY_COLOR_TRANSFER));
            hlg.release();

            MediaFormat unnamed = video(-1);
            unnamed.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR);
            assertTrue("HDR by its profile", HdrBrightness.hdrVideo(unnamed));
            MediaCodec profile = MediaCodec.createByCodecName(name);
            HdrBrightness.configure(profile, unnamed, surface(), null, 0);
            assertFalse("the video naming no transfer was sent the request", profile.getInputFormat().containsKey(MediaFormat.KEY_COLOR_TRANSFER_REQUEST));
            profile.release();

            String line = statusLine();
            assertTrue(line, line.contains(HdrBrightness.HLG_AS_USUAL_RANGE + " 1"));
            assertTrue(line, line.contains(HdrBrightness.QUALCOMM_NOT_ASKED + " 2"));
        } finally {
            ShadowMediaCodec.clearCodecs();
        }
    }

    @Test
    public void onlyAQualcommCodec2DecoderIsPassedOver() {
        ReflectionHelpers.setStaticField(Build.class, "SOC_MODEL", "SM8450");
        assertTrue("Qualcomm's VP9 decoder", HdrBrightness.qualcomm("c2.qti.vp9.decoder"));
        assertTrue("any case", HdrBrightness.qualcomm("C2.QTI.hevc.decoder"));
        assertFalse("Android's own decoder", HdrBrightness.qualcomm("c2.android.vp9.decoder"));
        assertFalse("an Exynos decoder", HdrBrightness.qualcomm("c2.exynos.vp9.decoder"));
        assertFalse("Qualcomm's older OMX decoder", HdrBrightness.qualcomm("OMX.qcom.video.decoder.vp9"));
        assertFalse("no name", HdrBrightness.qualcomm((String) null));
        assertFalse("a Qualcomm prefix only", HdrBrightness.qualcomm("c2.qti"));
        assertTrue("opened by an older alias", HdrBrightness.qualcomm("c2.qti.vp9.decoder", "OMX.qcom.video.decoder.vp9"));
        assertTrue("no underlying name read", HdrBrightness.qualcomm(null, "c2.qti.vp9.decoder"));
        assertFalse("Android's own, by an alias", HdrBrightness.qualcomm("c2.android.vp9.decoder", "OMX.google.vp9.decoder"));

        MediaFormat pq = video(MediaFormat.COLOR_TRANSFER_ST2084);
        assertFalse("a PQ video on a Qualcomm decoder", HdrBrightness.askForUsualRange(pq, surface(), 0, true));
        assertFalse("its request", pq.containsKey(MediaFormat.KEY_COLOR_TRANSFER_REQUEST));
        MediaFormat hlg = video(MediaFormat.COLOR_TRANSFER_HLG);
        assertTrue("an HLG video on a Qualcomm decoder", HdrBrightness.askForUsualRange(hlg, surface(), 0, true));
        assertFalse("its request", hlg.containsKey(MediaFormat.KEY_COLOR_TRANSFER_REQUEST));
        assertEquals("its transfer", MediaFormat.COLOR_TRANSFER_SDR_VIDEO, hlg.getInteger(MediaFormat.KEY_COLOR_TRANSFER));
        assertTrue("its withdrawal", HdrBrightness.withdraw(hlg, true));
        assertEquals("HLG back", MediaFormat.COLOR_TRANSFER_HLG, hlg.getInteger(MediaFormat.KEY_COLOR_TRANSFER));

        Settings.TURN_OFF_HDR_BRIGHTNESS.save(false);
        MediaFormat off = video(MediaFormat.COLOR_TRANSFER_ST2084);
        assertFalse(HdrBrightness.askForUsualRange(off, surface(), 0, true));
        assertEquals("off, nothing counted", FamilyNames.HDR_BRIGHTNESS + ": invoked 3, 1 found, 0 missing. Counted: "
                + HdrBrightness.QUALCOMM_NOT_ASKED + " 1", statusLine());
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
