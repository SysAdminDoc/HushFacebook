/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.content.pm.ActivityInfo;

/** Turn off HDR brightness, asked from tests in other packages. */
public final class HdrBrightnessForTests {
    private HdrBrightnessForTests() {
    }

    /** Whether a request for an HDR window comes out as one for the default colour mode. */
    public static boolean keepsAnHdrWindowInTheUsualRange() {
        return HdrBrightness.colorMode(ActivityInfo.COLOR_MODE_HDR) == ActivityInfo.COLOR_MODE_DEFAULT;
    }

    /** Whether a headroom Facebook asks for comes out as none. */
    public static boolean holdsTheHeadroom() {
        return HdrBrightness.headroom(4f) == HdrBrightness.NO_HEADROOM;
    }

    /** Whether the screen answers Facebook's questions as one that shows no HDR. */
    public static boolean answersTheScreenAsShowingNoHdr() {
        return HdrBrightness.holdsScreen();
    }

    /** A manifest with a 1080p video in SDR and in HLG, in a set each. */
    public static final String SDR_AND_HDR_MANIFEST = "<MPD><Period>"
            + "<AdaptationSet contentType=\"video\"><Representation id=\"s\" codecs=\"avc1.64002a\" height=\"1080\"/></AdaptationSet>"
            + "<AdaptationSet contentType=\"video\"><SupplementalProperty schemeIdUri=\"urn:mpeg:mpegB:cicp:TransferCharacteristics\" "
            + "value=\"18\"/><Representation id=\"h\" codecs=\"vp09.02.40.10\" height=\"1080\"/></AdaptationSet>"
            + "</Period></MPD>";

    /** Whether a manifest with an SDR and an HDR version of the same height reaches the player without the HDR one. */
    public static boolean dropsTheHdrRendition() {
        return !SdrRenditions.manifest(SDR_AND_HDR_MANIFEST).equals(SDR_AND_HDR_MANIFEST);
    }

    /** Whether a player Facebook puts on a SurfaceView draws into its TextureView instead. */
    public static boolean drawsVideosWithTheScreen() {
        return !TextureVideo.surfaceView(true, TextureVideo.REELS_VIEWER) && TextureVideo.preferTextureView(false);
    }
}
