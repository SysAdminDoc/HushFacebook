/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.media.MediaFormat;
import android.os.Build;

import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;

/**
 * With Debug logging on, the video formats Facebook's two decoders are set up with and hand back,
 * so a report says whether a video was served in HDR. Facebook plays video through Android's own
 * MediaCodec and, for AV1, through dav1d, both behind one playback interface. Each one's {@code
 * configure} hands its format here first, and each {@code getOutputFormat} the format it's about
 * to return. A line names the decoder and the way the format went, then its type, size, colour
 * transfer (SDR, PQ or HLG), standard and range, whether it carries static HDR metadata, and on
 * Android 12 and newer whether Facebook asked the decoder to tone-map it.
 *
 * <p>That's what {@link HdrBrightness} can't tell from the screen (#93, #66). With Turn off HDR
 * brightness on, a platform input with transfer=PQ or HLG says Facebook still picked the HDR
 * stream, and transfer=SDR says it took the plain one. dav1d builds its output format from the
 * picture's size alone, so its output line says unknown, never SDR. An output line describes what
 * the decoder handed back, not how bright the screen went.
 *
 * <p>Only those fixed words and bounded numbers are written: a type outside the list is unknown,
 * and nothing else Facebook put in the format, such as a link or an id, is read. Audio formats
 * are skipped. Each distinct line is written once and again every 50th time it repeats, for
 * {@link #MAX_SHAPES} distinct lines a run, and one more line then says the rest were left out.
 * A diagnostic clear starts the count over, so a report taken after one still has the lines.
 * Off, before the settings are ready, or when anything here fails, nothing is read and Facebook's
 * call goes on as it was. Pause leaves it on, since it only reads.
 */
public final class PlaybackFormatEvidence {
    /** The name the log lines go under, which is also their logcat tag after Morphe's prefix. */
    static final String SOURCE = "PlaybackFormatEvidence";

    /** What every line starts with, for a person reading the log. */
    static final String PREFIX = "Playback format evidence: ";

    /** Distinct lines kept a run. Past them, one line says the evidence isn't complete. */
    static final int MAX_SHAPES = 32;

    private static final Map<String, Integer> shapes = new LinkedHashMap<>();
    private static boolean omissionReported;
    /**
     * The diagnostic clear the kept lines belong to. A clear empties the report, so every line
     * writes again after one: a video that started before the clear would otherwise leave a report
     * taken after it with no line for its format.
     */
    private static long shapesGeneration = -1;

    private PlaybackFormatEvidence() {
    }

    /** Injection point, at the start of Android's decoder's configure. */
    public static void platformInput(MediaFormat format) {
        observe(format, "platform input");
    }

    /** Injection point, right before Android's decoder's getOutputFormat returns. */
    public static void platformOutput(MediaFormat format) {
        observe(format, "platform output");
    }

    /** Injection point, at the start of dav1d's configure. */
    public static void dav1dInput(MediaFormat format) {
        observe(format, "dav1d input");
    }

    /** Injection point, right before dav1d's getOutputFormat returns. */
    public static void dav1dOutput(MediaFormat format) {
        observe(format, "dav1d output");
    }

    private static void observe(@Nullable MediaFormat format, String phase) {
        // Logger can build a debug message before the settings have a context. This can't.
        try {
            if (!Utils.settingsReady() || !BaseSettings.DEBUG.get()) return;
            String mime = string(format, MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) return;
            String message = retain(describe(format, phase, mime));
            if (message != null) Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> PREFIX + message);
        } catch (Throwable failure) {
            // Never into Facebook's player, and never the failure itself: a host exception's
            // message is free text. Nothing here changed the format or the player.
        }
    }

    /** The line to write for [shape], or null when it's a repeat or past the limit. */
    @Nullable
    private static synchronized String retain(String shape) {
        long current = HookStatus.generation();
        if (shapesGeneration != current) {
            shapesGeneration = current;
            shapes.clear();
            omissionReported = false;
        }
        Integer previous = shapes.get(shape);
        if (previous != null) {
            int count = previous == Integer.MAX_VALUE ? previous : previous + 1;
            shapes.put(shape, count);
            return count % 50 == 0 ? shape + " samples=" + count : null;
        }
        if (shapes.size() < MAX_SHAPES) {
            shapes.put(shape, 1);
            return shape + " samples=1";
        }
        if (omissionReported) return null;
        omissionReported = true;
        return "additional shapes omitted; evidence-complete=false";
    }

    /** [format] in fixed words and bounded numbers. Anything unreadable or unlisted is unknown. */
    private static String describe(@Nullable MediaFormat format, String phase, @Nullable String suppliedMime) {
        String mime = "unknown";
        if ("video/avc".equals(suppliedMime) || "video/hevc".equals(suppliedMime) || "video/av01".equals(suppliedMime)
                || "video/x-vnd.on2.vp9".equals(suppliedMime) || "video/x-vnd.on2.vp8".equals(suppliedMime)
                || "video/dolby-vision".equals(suppliedMime)) mime = suppliedMime;
        int width = integer(format, MediaFormat.KEY_WIDTH);
        int height = integer(format, MediaFormat.KEY_HEIGHT);
        String transfer = transfer(integer(format, MediaFormat.KEY_COLOR_TRANSFER));
        String standard = "unknown";
        String range = "unknown";
        switch (integer(format, MediaFormat.KEY_COLOR_STANDARD)) {
            case MediaFormat.COLOR_STANDARD_BT709: standard = "BT709"; break;
            case MediaFormat.COLOR_STANDARD_BT601_NTSC: standard = "BT601-NTSC"; break;
            case MediaFormat.COLOR_STANDARD_BT601_PAL: standard = "BT601-PAL"; break;
            case MediaFormat.COLOR_STANDARD_BT2020: standard = "BT2020"; break;
        }
        switch (integer(format, MediaFormat.KEY_COLOR_RANGE)) {
            case MediaFormat.COLOR_RANGE_LIMITED: range = "limited"; break;
            case MediaFormat.COLOR_RANGE_FULL: range = "full"; break;
        }
        // A request for the decoder to tone-map, not a promise that it did.
        String request = "absent";
        if (Build.VERSION.SDK_INT >= 31 && present(format, MediaFormat.KEY_COLOR_TRANSFER_REQUEST)) {
            request = transfer(integer(format, MediaFormat.KEY_COLOR_TRANSFER_REQUEST));
        }
        boolean complete = !mime.equals("unknown") && validDimension(width) && validDimension(height)
                && !transfer.equals("unknown") && !standard.equals("unknown") && !range.equals("unknown")
                && !request.equals("unknown");
        return phase + " " + mime + " " + dimension(width) + "x" + dimension(height) + " transfer=" + transfer
                + " standard=" + standard + " range=" + range
                + " static-info=" + (present(format, MediaFormat.KEY_HDR_STATIC_INFO) ? "present" : "absent")
                + " transfer-request=" + request + " metadata-complete=" + complete;
    }

    private static String transfer(int value) {
        switch (value) {
            case MediaFormat.COLOR_TRANSFER_SDR_VIDEO: return "SDR";
            case MediaFormat.COLOR_TRANSFER_ST2084: return "PQ";
            case MediaFormat.COLOR_TRANSFER_HLG: return "HLG";
            case MediaFormat.COLOR_TRANSFER_LINEAR: return "linear";
            default: return "unknown";
        }
    }

    private static boolean validDimension(int value) {
        return value > 0 && value <= 16384;
    }

    private static String dimension(int value) {
        return validDimension(value) ? Integer.toString(value) : "unknown";
    }

    private static int integer(@Nullable MediaFormat format, String key) {
        try {
            return format == null ? -1 : format.getInteger(key);
        } catch (RuntimeException unreadable) {
            return -1;
        }
    }

    @Nullable
    private static String string(@Nullable MediaFormat format, String key) {
        try {
            return format == null ? null : format.getString(key);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static boolean present(@Nullable MediaFormat format, String key) {
        try {
            return format != null && format.containsKey(key);
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /** Forgets this run's lines, as a new Facebook process would. For tests. */
    static synchronized void resetForTests() {
        shapes.clear();
        omissionReported = false;
    }
}
