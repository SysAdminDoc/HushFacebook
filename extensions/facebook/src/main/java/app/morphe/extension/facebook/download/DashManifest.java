/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/extensions/extension/src/main/java/app/andrewliang/extension/DashManifest.java
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.extension.facebook.download;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tracks of a DASH manifest, and the best video track and audio track of them.
 *
 * <p>This class holds no Android type, like {@link RenditionPicker}. So {@code javac} alone can
 * test it against a manifest from a device.
 *
 * <p>Facebook writes each track as one {@code Representation} with one {@code BaseURL}. That is the
 * address of a whole MP4 file that holds only this track. So one plain fetch gets a track, and the
 * video and the audio are two files. A track with no {@code BaseURL} is not used, because one fetch
 * cannot get it.
 *
 * <p>This reads only that one shape. It is not a general XML parser.
 */
final class DashManifest {

    private DashManifest() {}

    /** How far over its announced rate {@link Track#bytesOver} takes a track, for its boxes. */
    static final double FRAGMENT_ALLOWANCE = 1.06;

    /** One track of the manifest. */
    static final class Track {
        final String mime;
        final String codecs;
        final int width;
        final int height;
        final long bandwidth;
        final String url;

        /**
         * The quality Facebook's own menu names this track by, such as 480 for {@code 480p}, or 0
         * when the track carries no label. Facebook encodes several of a video's tracks at one
         * frame size and tells them apart by this: a 720 by 1280 track can be its 480p.
         */
        final int label;

        Track(String mime, String codecs, int width, int height, long bandwidth, String url) {
            this(mime, codecs, width, height, bandwidth, url, 0);
        }

        Track(String mime, String codecs, int width, int height, long bandwidth, String url, int label) {
            this.mime = mime;
            this.codecs = codecs;
            this.width = width;
            this.height = height;
            this.bandwidth = bandwidth;
            this.url = url;
            this.label = label;
        }

        boolean isVideo() {
            return mime.startsWith("video/");
        }

        boolean isAudio() {
            return mime.startsWith("audio/");
        }

        /** The quality in the same unit as {@code 720p}: the short side, in pixels. */
        int shortSide() {
            return Math.min(width, height);
        }

        /** The quality a setting below the best is held to: the label, else the short side. */
        int quality() {
            return label > 0 ? label : shortSide();
        }

        /**
         * About how many bytes this track takes over [lengthUs], or 0 when its rate or the length
         * is unknown. Facebook announces a sound track's average rate, which leaves out the boxes
         * each fragment wraps its samples in, so the file runs a few percent over it (37 MB against
         * 36 on a 78 minute replay). The guess takes {@link #FRAGMENT_ALLOWANCE} more so it errs
         * high: a total that turns out smaller moves a bar forward, one that turns out bigger
         * moves it back.
         */
        long bytesOver(long lengthUs) {
            if (bandwidth <= 0 || lengthUs <= 0) return 0;
            return Math.round(bandwidth / 8.0 * (lengthUs / 1_000_000.0) * FRAGMENT_ALLOWANCE);
        }

        @Override
        public String toString() {
            return mime + " " + codecs + " " + width + "x" + height + " " + (bandwidth / 1000) + "kbps"
                + (label > 0 ? " " + label + "p" : "");
        }
    }

    private static final Pattern ADAPTATION_SET =
        Pattern.compile("<AdaptationSet\\b([^>]*)>(.*?)</AdaptationSet>", Pattern.DOTALL);

    private static final Pattern REPRESENTATION =
        Pattern.compile("<Representation\\b([^>]*?)(?:/>|>(.*?)</Representation>)", Pattern.DOTALL);

    private static final Pattern ATTRIBUTE = Pattern.compile("([\\w:]+)\\s*=\\s*\"([^\"]*)\"");

    private static final Pattern BASE_URL = Pattern.compile("<BaseURL[^>]*>(.*?)</BaseURL>", Pattern.DOTALL);

    /**
     * The longest manifest that's kept or read, in characters. The captured one in the tests is
     * 6.7 KB with nine tracks, and a track with Facebook's full signed address runs to about 1.5 KB.
     */
    static final int MAX_CHARS = 128 * 1024;

    /** The most tracks, and track groups, a manifest that's kept or read may list. */
    static final int MAX_REPRESENTATIONS = 64;
    static final int MAX_ADAPTATION_SETS = 16;

    /**
     * Whether [manifest] is small enough to keep and to read. The expressions below scan ahead for
     * each tag they find, so an unbounded manifest could stall a save or hold megabytes for a
     * player long gone. Counting the tags is one pass, and stops at the limit.
     */
    static boolean withinLimits(String manifest) {
        return manifest != null && manifest.length() <= MAX_CHARS
            && atMost(manifest, "<Representation", MAX_REPRESENTATIONS)
            && atMost(manifest, "<AdaptationSet", MAX_ADAPTATION_SETS);
    }

    /** Whether [text] holds [tag] no more than [limit] times. */
    private static boolean atMost(String text, String tag, int limit) {
        int found = 0;
        for (int at = text.indexOf(tag); at >= 0; at = text.indexOf(tag, at + tag.length())) {
            if (++found > limit) return false;
        }
        return true;
    }

    /**
     * Every track with a single file address, or none for a manifest over the limits. Never throws,
     * and never returns {@code null}.
     */
    static List<Track> parse(String manifest) {
        List<Track> tracks = new ArrayList<>();
        if (!withinLimits(manifest)) return tracks;

        try {
            Matcher sets = ADAPTATION_SET.matcher(manifest);
            while (sets.find()) {
                String setAttributes = sets.group(1);
                Matcher representations = REPRESENTATION.matcher(sets.group(2));

                while (representations.find()) {
                    Track track = track(setAttributes, representations.group(1), representations.group(2));
                    if (track != null) tracks.add(track);
                }
            }
        } catch (Throwable ignored) {
            // A manifest of an unexpected shape answers with what was read so far.
        }

        return tracks;
    }

    private static final Pattern PRESENTATION_LENGTH = Pattern.compile("<MPD\\b[^>]*?\\bmediaPresentationDuration"
        + "\\s*=\\s*\"P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?\"");

    /**
     * How long [manifest] says its video runs, in microseconds, from the {@code MPD}'s
     * {@code mediaPresentationDuration} ({@code PT64.814812S} in Facebook's), or -1 when it doesn't
     * say or isn't read. Never throws. A save weighs a conversion by it before it fetches anything
     * ({@link VideoTranscode#MAX_DURATION_US}).
     */
    static long durationUs(String manifest) {
        if (!withinLimits(manifest)) return -1;
        try {
            Matcher length = PRESENTATION_LENGTH.matcher(manifest);
            if (!length.find()) return -1;
            if (length.group(1) == null && length.group(2) == null && length.group(3) == null && length.group(4) == null) {
                return -1;
            }
            long seconds = 0;
            long[] unit = {86_400, 3_600, 60};
            for (int i = 0; i < unit.length; i++) {
                String part = length.group(i + 1);
                if (part != null) seconds = Math.addExact(seconds, Math.multiplyExact(Long.parseLong(part), unit[i]));
            }
            long us = Math.multiplyExact(seconds, 1_000_000L);
            String rest = length.group(4);
            return rest == null ? us : Math.addExact(us, Math.round(Double.parseDouble(rest) * 1_000_000));
        } catch (Throwable ignored) {
            // A length too large to hold, or of an unexpected shape, says nothing.
            return -1;
        }
    }

    /**
     * The best video track a save can write into an MP4, or {@code null}.
     *
     * <p>H.264 and H.265 are always permitted. AV1 is permitted only when [allowAv1] is true: a
     * story often lists only AV1 tracks, but a device with no AV1 decoder cannot play the file. VP9
     * in an MP4 is always permitted. {@code MediaMuxer} refuses it, also on Android 17, so
     * {@link Mp4Join} writes it, and every Android phone decodes VP9. At the same size, H.264 is
     * the first choice, because all players can play it, and VP9 the last, the one format no
     * Android muxer writes.
     */
    static Track bestVideo(List<Track> tracks, boolean allowAv1) {
        Track best = null;

        for (Track track : tracks) {
            if (!track.isVideo()) continue;

            int family = videoFamily(track, allowAv1);
            if (family == 0) continue;

            if (best == null) {
                best = track;
                continue;
            }

            if (track.shortSide() != best.shortSide()) {
                if (track.shortSide() > best.shortSide()) best = track;
                continue;
            }

            int bestFamily = videoFamily(best, allowAv1);
            if (family != bestFamily) {
                if (family > bestFamily) best = track;
                continue;
            }

            if (track.bandwidth > best.bandwidth) best = track;
        }

        return best;
    }

    /**
     * The video track that suits [quality] best, or {@code null}. The best quality is
     * {@link #bestVideo}, as it always was. Below it, the tracks are held to their labels: the
     * best one at or under the ceiling, else the nearest above it, and for the smallest file the
     * lowest. At one quality H.264 still goes first, then the higher bitrate, or the lower one for
     * the smallest file. A track the phone can't write is never picked, whatever it's labelled.
     */
    static Track pickVideo(List<Track> tracks, boolean allowAv1, DownloadQuality quality) {
        if (quality == null || quality == DownloadQuality.BEST) return bestVideo(tracks, allowAv1);

        Track best = null;

        for (Track track : tracks) {
            if (!track.isVideo()) continue;

            int family = videoFamily(track, allowAv1);
            if (family == 0) continue;

            if (best == null) {
                best = track;
                continue;
            }

            int order = quality.compare(track.quality(), best.quality());
            if (order != 0) {
                if (order < 0) best = track;
                continue;
            }

            int bestFamily = videoFamily(best, allowAv1);
            if (family != bestFamily) {
                if (family > bestFamily) best = track;
                continue;
            }

            boolean smaller = quality == DownloadQuality.SMALLEST;
            if (smaller ? track.bandwidth < best.bandwidth : track.bandwidth > best.bandwidth) best = track;
        }

        return best;
    }

    /**
     * Prefer AAC-LC or HE-AAC sound that gallery players can open, without lowering the video's
     * resolution. Use another AAC profile only when neither is offered. Stories can list only
     * xHE-AAC ({@code mp4a.40.42}), so discarding it would lose their sound.
     */
    static Track bestAudio(List<Track> tracks) {
        Track compatible = bestCompatibleAudio(tracks);
        if (compatible != null) return compatible;
        Track best = null;

        for (Track track : tracks) {
            if (!track.isAudio()) continue;
            if (!track.codecs.startsWith("mp4a")) continue;
            if (best == null || track.bandwidth > best.bandwidth) best = track;
        }

        return best;
    }

    // ---------------------------------------------------------------- files other apps can open

    /**
     * Whether a video track is H.264 ({@code avc1} or {@code avc3}), the one video format every app
     * that takes an MP4 plays. WhatsApp names H.264 with AAC sound as what it sends, and turned
     * down a reel saved as AV1 with xHE-AAC sound that Gallery and VLC played (issue #11).
     */
    static boolean isCompatibleVideo(Track track) {
        return track.isVideo() && (track.codecs.startsWith("avc1") || track.codecs.startsWith("avc3"));
    }

    /**
     * How widely an audio track plays: 2 for AAC-LC ({@code mp4a.40.2}), 1 for HE-AAC and HE-AAC v2
     * ({@code mp4a.40.5}, {@code mp4a.40.29}), which carry an AAC-LC core, and 0 for anything else.
     * xHE-AAC ({@code mp4a.40.42}, USAC) is a different codec under the same {@code mp4a.40} name,
     * and apps that take AAC refuse it.
     */
    static int compatibleAudioRank(Track track) {
        if (!track.isAudio() || !track.codecs.startsWith("mp4a.40.")) return 0;
        int objectType;
        try {
            objectType = Integer.parseInt(track.codecs.substring("mp4a.40.".length()).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
        if (objectType == 2) return 2;
        if (objectType == 5 || objectType == 29) return 1;
        return 0;
    }

    /** The H.264 track that suits [quality] best, by the rules of {@link #pickVideo}, or null. */
    static Track pickCompatibleVideo(List<Track> tracks, DownloadQuality quality) {
        List<Track> h264 = new ArrayList<>();
        for (Track track : tracks) {
            if (isCompatibleVideo(track)) h264.add(track);
        }
        return pickVideo(h264, false, quality);
    }

    /**
     * The AAC-LC track with the highest bitrate, else the HE-AAC track with the highest, else
     * {@code null}. Never xHE-AAC or any other profile.
     */
    static Track bestCompatibleAudio(List<Track> tracks) {
        Track best = null;
        int bestRank = 0;

        for (Track track : tracks) {
            int rank = compatibleAudioRank(track);
            if (rank == 0) continue;
            if (best == null || rank > bestRank || (rank == bestRank && track.bandwidth > best.bandwidth)) {
                best = track;
                bestRank = rank;
            }
        }

        return best;
    }

    /** A video track and the audio track to join it with, or no audio for a video with no sound. */
    static final class Pick {
        final Track video;
        final Track audio;
        /** Whether [audio], xHE-AAC, is to be made AAC-LC before the join ({@link AacReencode}). */
        final boolean reencodeSound;
        /** Whether [video], VP9, AV1 or H.265, is to be made H.264 before the join ({@link VideoTranscode}). */
        final boolean transcodeVideo;

        Pick(Track video, Track audio) {
            this(video, audio, false, false);
        }

        Pick(Track video, Track audio, boolean reencodeSound) {
            this(video, audio, reencodeSound, false);
        }

        Pick(Track video, Track audio, boolean reencodeSound, boolean transcodeVideo) {
            this.video = video;
            this.audio = audio;
            this.reencodeSound = reencodeSound;
            this.transcodeVideo = transcodeVideo;
        }
    }

    /** Whether this phone can make a track's picture H.264 ({@link VideoTranscode#canConvert}). */
    interface Converter {
        boolean canConvert(Track track);
    }

    /**
     * The tracks a save of this manifest joins, or {@code null} when it has none to offer.
     *
     * <p>Not [compatible], that's {@link #pickVideo} and {@link #bestAudio}. [compatible] holds
     * both to what other apps open: an H.264 track
     * ({@link #pickCompatibleVideo}) and an AAC-LC or HE-AAC one ({@link #bestCompatibleAudio}).
     * A manifest that has sound but none of it in those profiles has no pick, rather than a silent
     * one: the caller saves the single file instead.
     */
    static Pick pick(List<Track> tracks, boolean allowAv1, DownloadQuality quality, boolean compatible) {
        return pick(tracks, allowAv1, quality, compatible, false);
    }

    /**
     * As above. With [compatible] and [reencodable], a manifest whose only sound is xHE-AAC still
     * has a pick: its H.264 track and the xHE-AAC sound, marked to be made AAC-LC before the join,
     * rather than none, which left the save to Facebook's single file (360p for a story).
     */
    static Pick pick(List<Track> tracks, boolean allowAv1, DownloadQuality quality, boolean compatible,
            boolean reencodable) {
        return pick(tracks, allowAv1, quality, compatible, reencodable, null);
    }

    /**
     * As above. With [compatible] and a [converter], a manifest with no H.264 track at all still
     * has a pick: the 8-bit VP9, AV1 or H.265 track that suits [quality] best among those the phone
     * can convert ({@link #pickConvertibleVideo}), marked to be made H.264 before the join. Facebook
     * sends some stories only as VP9 with xHE-AAC sound, and their only H.264 copy is 360p (#77).
     * Whether that's worth the time over the single file is the caller's to weigh.
     */
    static Pick pick(List<Track> tracks, boolean allowAv1, DownloadQuality quality, boolean compatible,
            boolean reencodable, Converter converter) {
        return pick(tracks, allowAv1, quality, compatible, reencodable, converter, false);
    }

    /**
     * As above. With [convertLarger] too, the picture to convert is picked over the H.264 one
     * whenever it suits [quality] better, not only when there's no H.264 track: Facebook often lists
     * a reel's full size only as VP9 or AV1 and its H.264 a size or two down (#189). On a tie the
     * H.264 track stays, since it needs no converting.
     */
    static Pick pick(List<Track> tracks, boolean allowAv1, DownloadQuality quality, boolean compatible,
            boolean reencodable, Converter converter, boolean convertLarger) {
        if (!compatible) {
            Track video = pickVideo(tracks, allowAv1, quality);
            Track audio = bestAudio(tracks);
            // A supported picture beside unsupported sound must not become a silent save.
            if (video == null || (audio == null && hasSound(tracks))) return null;
            return new Pick(video, audio);
        }

        Track video = pickCompatibleVideo(tracks, quality);
        boolean convert = false;
        if (converter != null && (video == null || convertLarger)) {
            Track converted = pickConvertibleVideo(tracks, quality, converter);
            if (converted != null && (video == null || suitsBetter(converted, video, quality))) {
                video = converted;
                convert = true;
            }
        }
        if (video == null) return null;

        Track audio = bestCompatibleAudio(tracks);
        if (audio == null && hasSound(tracks)) {
            Track xhe = reencodable ? bestXheAudio(tracks) : null;
            return xhe == null ? null : new Pick(video, xhe, true, convert);
        }
        return new Pick(video, audio, false, convert);
    }

    /**
     * The 8-bit VP9, AV1 or H.265 track that suits [quality] best, by the rules of
     * {@link #pickVideo}, among those [converter] says the phone can make H.264, or null.
     */
    static Track pickConvertibleVideo(List<Track> tracks, DownloadQuality quality, Converter converter) {
        List<Track> convertible = new ArrayList<>();
        for (Track track : tracks) {
            if (!track.isVideo() || VideoTranscode.sourceType(track.codecs) == null) continue;
            if (converter.canConvert(track)) convertible.add(track);
        }
        return pickVideo(convertible, true, quality);
    }

    /** Whether [video] suits [quality] better than [other]: at the best quality a larger picture, below it a nearer fit. */
    static boolean suitsBetter(Track video, Track other, DownloadQuality quality) {
        if (quality == null || quality == DownloadQuality.BEST) return video.shortSide() > other.shortSide();
        return quality.compare(video.quality(), other.quality()) < 0;
    }

    /** The xHE-AAC track with the highest bitrate, or {@code null}. */
    static Track bestXheAudio(List<Track> tracks) {
        Track best = null;
        for (Track track : tracks) {
            if (!track.isAudio() || !AacReencode.isXhe(track.codecs)) continue;
            if (best == null || track.bandwidth > best.bandwidth) best = track;
        }
        return best;
    }

    /** Whether the manifest lists any sound at all, whatever its codec. */
    static boolean hasSound(List<Track> tracks) {
        for (Track track : tracks) {
            if (track.isAudio()) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- internals

    /**
     * A higher number is a better choice: 4 for H.264, 3 for H.265, 2 for AV1, 1 for VP9, 0 for not
     * used. VP9 counts only in an MP4, the container {@link Mp4Join} copies it from.
     */
    private static int videoFamily(Track track, boolean allowAv1) {
        String codecs = track.codecs;
        if (codecs.startsWith("avc1") || codecs.startsWith("avc3")) return 4;
        if (codecs.startsWith("hvc1") || codecs.startsWith("hev1")) return 3;
        if (allowAv1 && codecs.startsWith("av01")) return 2;
        if (codecs.startsWith("vp09") && track.mime.equals("video/mp4")) return 1;
        return 0;
    }

    private static Track track(String setAttributes, String attributes, String body) {
        if (body == null) return null;

        Matcher base = BASE_URL.matcher(body);
        if (!base.find()) return null;

        String url = unescape(base.group(1).trim());
        if (!url.toLowerCase(Locale.US).startsWith("https://")) return null;

        // A track gives its type on itself or on its adaptation set. An empty one gives none.
        String mime = given(attribute(attributes, "mimeType"));
        if (mime == null) mime = given(attribute(setAttributes, "mimeType"));
        if (mime == null) {
            String content = given(attribute(setAttributes, "contentType"));
            if (content != null) mime = content + "/mp4";
        }
        if (mime == null) return null;

        String codecs = attribute(attributes, "codecs");
        if (codecs == null) codecs = attribute(setAttributes, "codecs");

        return new Track(
            mime.toLowerCase(Locale.US),
            codecs == null ? "" : codecs.toLowerCase(Locale.US),
            number(attribute(attributes, "width")),
            number(attribute(attributes, "height")),
            number(attribute(attributes, "bandwidth")),
            url,
            qualityLabel(attribute(attributes, "FBQualityLabel"))
        );
    }

    /** [value] without the spaces around it, or null when nothing is left. */
    private static String given(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** The number in a label such as {@code 720p}, or 0 for anything else. */
    private static int qualityLabel(String label) {
        if (label == null) return 0;
        String text = label.trim().toLowerCase(Locale.US);
        int digits = text.length() - 1;
        if (digits < 3 || digits > 4 || text.charAt(digits) != 'p') return 0;
        for (int i = 0; i < digits; i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') return 0;
        }
        return number(text.substring(0, digits));
    }

    private static String attribute(String attributes, String name) {
        if (attributes == null) return null;

        Matcher matcher = ATTRIBUTE.matcher(attributes);
        while (matcher.find()) {
            if (matcher.group(1).equals(name)) return unescape(matcher.group(2));
        }
        return null;
    }

    /** A size or bitrate, 0 when it's missing or no number, and a negative one is no size either. */
    private static int number(String text) {
        if (text == null) return 0;
        try {
            long value = Long.parseLong(text.trim());
            if (value < 0) return 0;
            return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The five entities of XML. {@code &amp;} is the last. Thus {@code &amp;lt;} becomes {@code &lt;}. */
    private static String unescape(String text) {
        return text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&");
    }
}
