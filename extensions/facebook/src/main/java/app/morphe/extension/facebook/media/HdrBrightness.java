/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.content.pm.ActivityInfo;
import android.media.MediaCodec;
import android.media.MediaCodecInfo.CodecProfileLevel;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.Window;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Turn off HDR brightness. Facebook shows an HDR video or photo brighter than the rest of the
 * screen by asking Android for an HDR window: as the feed, a story or a reel comes to the front it
 * sets its window's colour mode to HDR and, on Android 15, how far above the screen's usual white
 * it wants to go, a figure its server sends. Android then turns the panel up for the HDR parts.
 * In a window at the default colour mode, Android draws the same video tone-mapped into the usual
 * range, at the same resolution.
 *
 * <p>Each of Facebook's calls of {@code Window.setColorMode} and {@code
 * Window.setDesiredHdrHeadroom} comes here instead. While the switch is on, a request for the HDR
 * mode becomes one for the default mode and a headroom becomes none; every other request goes
 * through as asked. A video Facebook draws on a surface of its own doesn't follow the window's
 * mode, so on Android 15 each SurfaceView Facebook builds asks for no headroom as well.
 *
 * <p>Before Android 15 there's no headroom to hold, and on a screen that shows HLG Facebook lifts
 * ordinary videos into HDR on Android 14 and newer (its inverse tone mapping, 581 {@code
 * LX/Lpl;->A00}, "InverseToneMapDisplayEligibility", read by {@code applyItmHdrGate}). That
 * starts from asking the screen what it shows, so each of Facebook's calls of {@code
 * Display.isHdr} and the two {@code getSupportedHdrTypes} comes here too, and while the switch is
 * on the screen answers as one that shows no HDR: the lift stays off, ExoPlayer takes a Dolby
 * Vision video's fallback track, and the device details Facebook records name no HDR type.
 * Whether a stream labelled HDR is then passed over for its plain twin isn't known (#93); with
 * Debug logging on, {@link PlaybackFormatEvidence} says which one each decoder was set up with. {@code
 * Display.isHdrSdrRatioAvailable} stays Facebook's: the AV1 decoder backs its own lift off only
 * when it can read a low ratio.
 *
 * <p>On Android 14 none of that reaches an HDR video: #93's report showed a VP9 HLG reel set up on
 * Android's own decoder, drawn on Facebook's surface, with not one of the calls above made. So
 * each of Facebook's calls of {@code MediaCodec.configure} comes here as well, and while the switch
 * is on, a video decoder drawing an HDR video (PQ, HLG, Dolby Vision or an HDR profile) to a
 * surface is asked, on Android 12 and newer, to hand its pictures over in the usual range ({@code
 * color-transfer-request} set to SDR). A phone whose decoder can do that keeps the request in the
 * decoder's input format, and the report counts which way each one answered. One that can't
 * ignores it, and the video plays as before. dav1d's AV1 decoder isn't Android's, so it isn't
 * asked. A decoder that fails to set up with the request is set up again exactly as Facebook asked.
 *
 * <p>A switch change shows from the next screen Facebook brings to the front. Nothing here may
 * throw into Facebook's screen: off, paused, before the settings are ready or when anything here
 * fails, Facebook's request goes through unchanged.
 */
public final class HdrBrightness {
    /** Counted under the patch's name for each HDR window asked for in the usual range instead. */
    static final String WINDOW_HELD = "HDR window kept in the usual range";

    /** Counted for each headroom Facebook asked for and didn't get. */
    static final String HEADROOM_HELD = "HDR headroom held to none";

    /** Counted for each SurfaceView built asking for no headroom, Android 15 and newer. */
    static final String SURFACE_HELD = "video surface kept in the usual range";

    /** Counted for each time Facebook asked what the screen shows and heard that it shows no HDR. */
    static final String SCREEN_HELD = "screen answered as showing no HDR";

    /** Counted for each HDR video decoder that said it would hand its pictures over in the usual range. */
    static final String DECODER_TONE_MAPS = "HDR video decoder tone-maps to the usual range";

    /** Counted for each HDR video decoder asked and not saying yes: this phone's decoder can't. */
    static final String DECODER_CANNOT = "HDR video decoder can't tone-map";

    /** Counted for each HDR video decoder that failed to set up with the request, and was set up without it. */
    static final String DECODER_REFUSED = "HDR video decoder refused the request";

    /** No headroom over the screen's usual white, so nothing on screen goes brighter than it. */
    static final float NO_HEADROOM = 1f;

    /** The HDR types of a screen that shows none. */
    private static final int[] NO_HDR_TYPES = new int[0];

    private static final String FAMILY = FamilyNames.HDR_BRIGHTNESS;

    private static volatile boolean windowLogged;
    private static volatile boolean decoderLogged;

    private HdrBrightness() {
    }

    /**
     * Injection point, in place of each of Facebook's calls of {@code MediaCodec.configure}. Asks an
     * HDR video decoder for the usual range first while the switch is on ({@link #askForUsualRange}),
     * then sets it up as Facebook asked and counts the decoder's answer. Anything else is set up
     * exactly as Facebook asked.
     */
    public static void configure(MediaCodec codec, @Nullable MediaFormat format, @Nullable Surface surface,
                                 @Nullable MediaCrypto crypto, int flags) {
        if (!askForUsualRange(format, surface, flags)) {
            codec.configure(format, surface, crypto, flags);
            return;
        }
        try {
            codec.configure(format, surface, crypto, flags);
        } catch (IllegalArgumentException | MediaCodec.CodecException refused) {
            // The request must never cost a video: set it up again exactly as Facebook asked. If
            // that can't be done, Facebook hears what went wrong the first time.
            if (!withdraw(format)) throw refused;
            try {
                codec.reset();
            } catch (RuntimeException stuck) {
                throw refused;
            }
            codec.configure(format, surface, crypto, flags);
            count(DECODER_REFUSED);
            return;
        }
        count(toneMaps(codec) ? DECODER_TONE_MAPS : DECODER_CANNOT);
    }

    /**
     * Whether [format] was just asked to come out in the usual range: an HDR video ({@link
     * #hdrVideo}) going to [surface] on a decoder, on Android 12 or newer, with the switch on and
     * no request of Facebook's own. Adds the request to [format] when it answers yes.
     */
    static boolean askForUsualRange(@Nullable MediaFormat format, @Nullable Surface surface, int flags) {
        if (Build.VERSION.SDK_INT < 31 || format == null || surface == null
                || (flags & MediaCodec.CONFIGURE_FLAG_ENCODE) != 0) return false;
        try {
            if (!hdrVideo(format) || format.containsKey(MediaFormat.KEY_COLOR_TRANSFER_REQUEST) || !on()) return false;
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            HookStatus.bound(FAMILY, "decoder");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "decoder", failure);
            return false;
        }
    }

    /**
     * Whether [format] is a video in HDR: a PQ or HLG transfer, Dolby Vision, or, when it names no
     * transfer, an HDR profile of HEVC, VP9 or AV1. A transfer that's neither says it isn't.
     */
    static boolean hdrVideo(MediaFormat format) {
        String mime = format.getString(MediaFormat.KEY_MIME);
        if (mime == null || !mime.startsWith("video/")) return false;
        if (MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION.equals(mime)) return true;
        if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
            int transfer = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER);
            return transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG;
        }
        if (!format.containsKey(MediaFormat.KEY_PROFILE)) return false;
        int profile = format.getInteger(MediaFormat.KEY_PROFILE);
        switch (mime) {
            case MediaFormat.MIMETYPE_VIDEO_HEVC:
                return profile == CodecProfileLevel.HEVCProfileMain10HDR10 || profile == CodecProfileLevel.HEVCProfileMain10HDR10Plus;
            case MediaFormat.MIMETYPE_VIDEO_VP9:
                return profile == CodecProfileLevel.VP9Profile2HDR || profile == CodecProfileLevel.VP9Profile3HDR
                        || profile == CodecProfileLevel.VP9Profile2HDR10Plus || profile == CodecProfileLevel.VP9Profile3HDR10Plus;
            case MediaFormat.MIMETYPE_VIDEO_AV1:
                return profile == CodecProfileLevel.AV1ProfileMain10HDR10 || profile == CodecProfileLevel.AV1ProfileMain10HDR10Plus;
            default:
                return false;
        }
    }

    /** Whether [codec], just set up with the request, kept it: a decoder that will tone-map does. */
    private static boolean toneMaps(MediaCodec codec) {
        try {
            return keptRequest(codec.getInputFormat());
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "decoder answer", failure);
            return false;
        }
    }

    /** Whether a decoder's input format [input] holds the request for the usual range. */
    static boolean keptRequest(@Nullable MediaFormat input) {
        return Build.VERSION.SDK_INT >= 31 && input != null && input.containsKey(MediaFormat.KEY_COLOR_TRANSFER_REQUEST)
                && input.getInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST) == MediaFormat.COLOR_TRANSFER_SDR_VIDEO;
    }

    /** Takes the request back out of [format]. False when it can't. */
    private static boolean withdraw(@Nullable MediaFormat format) {
        try {
            if (format == null || Build.VERSION.SDK_INT < 31) return false;
            format.removeKey(MediaFormat.KEY_COLOR_TRANSFER_REQUEST);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "decoder retry", failure);
            return false;
        }
    }

    private static void count(String what) {
        try {
            HookStatus.counted(FAMILY, what);
            if (!decoderLogged) {
                decoderLogged = true;
                Logger.printDebug(() -> "Turn off HDR brightness: " + what);
            }
        } catch (Throwable ignored) {
            // Counting is for the report only; Facebook's decoder is already set up.
        }
    }

    /** Injection point, in place of each of Facebook's calls of {@code Window.setColorMode}. */
    public static void setColorMode(Window window, int mode) {
        window.setColorMode(colorMode(mode));
    }

    /**
     * Injection point, in place of each of Facebook's calls of {@code
     * Window.setDesiredHdrHeadroom}, which Facebook makes on Android 15 and newer only.
     */
    public static void setDesiredHdrHeadroom(Window window, float headroom) {
        if (Build.VERSION.SDK_INT >= 35) window.setDesiredHdrHeadroom(headroom(headroom));
    }

    /** Injection point, right after each SurfaceView Facebook builds, its own kinds included. */
    public static void surfaceBuilt(SurfaceView view) {
        if (Build.VERSION.SDK_INT < 35) return;
        try {
            if (!on()) return;
            view.setDesiredHdrHeadroom(NO_HEADROOM);
            HookStatus.bound(FAMILY, "surface built");
            HookStatus.counted(FAMILY, SURFACE_HELD);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "surface built", failure);
        }
    }

    /** Injection point, in place of each of Facebook's calls of {@code Display.isHdr}. */
    public static boolean isHdr(Display display) {
        return !holdsScreen() && display.isHdr();
    }

    /**
     * Injection point, in place of each of Facebook's calls of {@code
     * Display.Mode.getSupportedHdrTypes}, which Facebook makes on Android 14 and newer only.
     */
    @RequiresApi(34)
    public static int[] getSupportedHdrTypes(Display.Mode mode) {
        return holdsScreen() ? NO_HDR_TYPES : mode.getSupportedHdrTypes();
    }

    /** Injection point, in place of each of Facebook's calls of {@code Display.HdrCapabilities.getSupportedHdrTypes}. */
    public static int[] getSupportedHdrTypes(Display.HdrCapabilities capabilities) {
        return holdsScreen() ? NO_HDR_TYPES : capabilities.getSupportedHdrTypes();
    }

    /** Whether the screen answers as one that shows no HDR: while the switch is on. */
    static boolean holdsScreen() {
        try {
            if (!on()) return false;
            HookStatus.bound(FAMILY, "screen");
            HookStatus.counted(FAMILY, SCREEN_HELD);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "screen", failure);
            return false;
        }
    }

    /** The colour mode to ask Android for: the default one in place of HDR while the switch is on, else [mode]. */
    static int colorMode(int mode) {
        try {
            if (mode != ActivityInfo.COLOR_MODE_HDR || !on()) return mode;
            HookStatus.bound(FAMILY, "colour mode");
            HookStatus.counted(FAMILY, WINDOW_HELD);
            if (!windowLogged) {
                windowLogged = true;
                Logger.printDebug(() -> "Turn off HDR brightness: an HDR window kept in the usual range");
            }
            return ActivityInfo.COLOR_MODE_DEFAULT;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "colour mode", failure);
            return mode;
        }
    }

    /** The headroom to ask Android for: none while the switch is on, else [headroom]. */
    static float headroom(float headroom) {
        try {
            if (!on()) return headroom;
            HookStatus.bound(FAMILY, "headroom");
            HookStatus.counted(FAMILY, HEADROOM_HELD);
            return NO_HEADROOM;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "headroom", failure);
            return headroom;
        }
    }

    private static boolean on() {
        HookStatus.invoked(FAMILY);
        return Utils.settingsReady() && Settings.TURN_OFF_HDR_BRIGHTNESS.get();
    }
}
