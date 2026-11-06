/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.SystemClock;
import android.view.Surface;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Turns a VP9, AV1 or H.265 picture into H.264, the one video format every app that takes an MP4
 * plays, in an MP4 of its own.
 *
 * <p>For a save with Save videos other apps can open on whose manifest has no H.264 track at all.
 * Facebook sends some stories only as VP9 with xHE-AAC sound, from 360p to 1080p, and its only
 * H.264 copy is the story's 360p file (issue #77). The decoder draws each frame straight into the
 * H.264 encoder's input surface, so the picture never passes through this code, and each frame
 * keeps the time the source gave it and the colors the source states ({@link #keepColors}).
 *
 * <p>Only 8-bit sources are taken. A 10-bit picture is HDR or meant to look like it, and drawing it
 * into an 8-bit encoder without tone mapping would wash it out; those saves keep the single file.
 */
final class VideoTranscode {
    /** How long one wait on a codec lasts. */
    private static final long WAIT_US = 10_000;

    /** A codec that gives nothing back for this long has stalled. */
    static final long STALL_MS = 10_000;

    /** Rounds of the loop with nothing done that also count as a stall, as in {@link AacReencode}. */
    static final int STALL_ROUNDS = (int) (STALL_MS * 1000 / WAIT_US);

    /**
     * The longest picture this converts. Stories and reels run to a minute or two; a feed video
     * that's longer would hold the save for many minutes, and keeps the single file instead.
     */
    static final long MAX_DURATION_US = 10L * 60 * 1_000_000;

    /** The frame rate a source that doesn't state one is taken to have. */
    static final int DEFAULT_FRAME_RATE = 30;

    /** The H.264 bitrate's floor and ceiling, in bits per second. */
    static final int MIN_BIT_RATE = 1_000_000;
    static final int MAX_BIT_RATE = 8_000_000;

    /**
     * Fewer frames out than this share of the frames drawn into the encoder means it dropped some,
     * and the save keeps the single file rather than a stuttering one.
     */
    static final double MIN_FRAMES_KEPT = 0.9;

    /** The least room a converted picture is given, as before it was worked out from the picture's length. */
    static final long LEAST_ROOM = 32L << 20;

    /** The most frames a second the tables are counted for, whatever a file states. */
    private static final int MOST_FRAMES_A_SECOND = 240;

    private VideoTranscode() {
    }

    /**
     * The MIME type of [codecs]'s picture when it's an 8-bit one this can convert, else null: VP9
     * profile 0 ({@code vp09.00.LL.08}), AV1 Main ({@code av01.0.LLT.08}) and H.265 Main
     * ({@code hvc1.1.} or {@code hev1.1.}). H.264 needs no converting, and anything else, 10-bit
     * included, isn't taken.
     */
    @Nullable
    static String sourceType(String codecs) {
        if (codecs == null) return null;
        String[] parts = codecs.trim().toLowerCase(Locale.ROOT).split("\\.");
        if (parts.length == 0) return null;
        switch (parts[0]) {
            case "vp09":
                return parts.length >= 4 && parts[1].equals("00") && parts[3].equals("08")
                    ? MediaFormat.MIMETYPE_VIDEO_VP9 : null;
            case "av01":
                return parts.length >= 4 && parts[1].equals("0") && parts[3].equals("08")
                    ? MediaFormat.MIMETYPE_VIDEO_AV1 : null;
            case "hvc1":
            case "hev1":
                return parts.length >= 2 && parts[1].equals("1") ? MediaFormat.MIMETYPE_VIDEO_HEVC : null;
            default:
                return null;
        }
    }

    /**
     * The H.264 bitrate for a [width] by [height] picture at [frameRate] whose source ran at
     * [sourceBitRate]. H.264 needs a few times what VP9 and AV1 take for the same look, and phone
     * encoders do poorly when starved, so it's the larger of three times the source and a modest
     * rate for the frame size, within {@link #MIN_BIT_RATE} and {@link #MAX_BIT_RATE}.
     */
    static int bitRate(int width, int height, int frameRate, long sourceBitRate) {
        long forSize = (long) width * height * Math.max(1, frameRate) / 25;
        long wanted = Math.max(sourceBitRate * 3, forSize);
        return (int) Math.max(MIN_BIT_RATE, Math.min(MAX_BIT_RATE, wanted));
    }

    /**
     * The room the converted picture needs ({@link DashSave#reserve}): as long as [in]'s picture
     * runs, at {@link #MAX_BIT_RATE} and a quarter again, with the tables and boxes on top. Phone
     * encoders hold a quality floor rather than the rate they're asked for: the S22's made about
     * 8 Mbps of 1080p asked for 3 to 3.8, and a four minute 1080p reel asked for 2.5 outgrew both
     * six times its 467 kbps file and half again over 2.5 Mbps, so the save fell back to
     * Facebook's 720p file. The ceiling is the most any of them made.
     */
    static long room(File in, long sourceBitRate) {
        long durationUs = -1;
        int width = 0;
        int height = 0;
        int frameRate = DEFAULT_FRAME_RATE;
        MediaExtractor source = null;
        try {
            source = new MediaExtractor();
            source.setDataSource(in.getPath());
            MediaFormat format = pictureTrack(source);
            if (format != null) {
                if (format.containsKey(MediaFormat.KEY_DURATION)) durationUs = format.getLong(MediaFormat.KEY_DURATION);
                width = format.getInteger(MediaFormat.KEY_WIDTH);
                height = format.getInteger(MediaFormat.KEY_HEIGHT);
                if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    frameRate = Math.max(1, format.getInteger(MediaFormat.KEY_FRAME_RATE));
                }
            }
        } catch (Throwable unreadable) {
            // The file's size stands in for the length and size it doesn't give.
        } finally {
            if (source != null) attempt(null, source::release);
        }
        return roomFor(durationUs, width, height, frameRate, sourceBitRate, in.length());
    }

    /**
     * {@link #room} from what the picture says: it runs [durationUs] at [width] by [height] and
     * [frameRate], from a source at [sourceBitRate]. Never below six times [bytes] or
     * {@link #LEAST_ROOM}, the room every picture got before, which is all a picture that doesn't
     * say how long it runs or how large it is gets.
     */
    static long roomFor(long durationUs, int width, int height, int frameRate, long sourceBitRate, long bytes) {
        long floor = Math.max(LEAST_ROOM, Math.max(0L, bytes) * 6);
        if (durationUs <= 0 || width <= 0 || height <= 0) return floor;
        long ms = durationUs / 1000;
        long picture = ms * (MAX_BIT_RATE / 8) / 1000 * 5 / 4;
        long frames = ms * Math.max(1, Math.min(frameRate, MOST_FRAMES_A_SECOND)) / 1000 + 1;
        return Math.max(floor, OutputAllowance.BOXES + picture + frames * OutputAllowance.PER_SAMPLE);
    }

    /** The color description a source can state, carried to the encoder by {@link #keepColors}. */
    private static final String[] COLOR_KEYS = {
        MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_RANGE, MediaFormat.KEY_COLOR_TRANSFER,
    };

    /**
     * Gives [encode] the color description [source] states. The frames reach the encoder with
     * their values as they are, and an encoder told nothing labels them BT.709: right for most
     * pictures, but a BT.601 one then plays a shade off in every player. A source that states
     * nothing leaves the encoder's own label.
     */
    static void keepColors(MediaFormat source, MediaFormat encode) {
        for (String key : COLOR_KEYS) {
            if (source.containsKey(key)) encode.setInteger(key, source.getInteger(key));
        }
    }

    /** The colors [source] states, for the report: each value by its key, or that there are none. */
    static String describeColors(MediaFormat source) {
        StringBuilder stated = new StringBuilder();
        for (String key : COLOR_KEYS) {
            if (!source.containsKey(key)) continue;
            stated.append(stated.length() == 0 ? "colors " : ", ").append(key).append(' ').append(source.getInteger(key));
        }
        return stated.length() == 0 ? "no colors stated" : stated.toString();
    }

    /** The encoder's format for a [width] by [height] picture, fed from a surface. */
    static MediaFormat encoderFormat(int width, int height, int frameRate, int bitRate) {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        return format;
    }

    /**
     * Whether this phone can convert [track]: an 8-bit picture of a type it decodes at that size,
     * and an H.264 encoder that takes that size from a surface. Asked while a save is planned, so a
     * phone that can't keeps the single file without trying.
     */
    static boolean canConvert(DashManifest.Track track) {
        try {
            String type = sourceType(track.codecs);
            if (type == null || track.width <= 0 || track.height <= 0) return false;
            if (track.width % 2 != 0 || track.height % 2 != 0) return false;
            MediaCodecList codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            if (codecs.findDecoderForFormat(MediaFormat.createVideoFormat(type, track.width, track.height)) == null) {
                return false;
            }
            MediaFormat encode = encoderFormat(track.width, track.height, DEFAULT_FRAME_RATE,
                bitRate(track.width, track.height, DEFAULT_FRAME_RATE, track.bandwidth));
            return codecs.findEncoderForFormat(encode) != null;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /**
     * Writes the picture of [in] to [out] as H.264 at its own size and frame times, in at most
     * [room] bytes. Answers false when [progress] was cancelled first; throws when a codec can't be
     * had, stalls, drops frames or fails, or when the file would pass [room]
     * ({@link OutputAllowance.Exceeded}), and [out] is then the caller's to discard.
     */
    static boolean transcode(File in, File out, long sourceBitRate, long room, Downloader.Progress progress)
            throws IOException {
        OutputAllowance allowance = new OutputAllowance(room);
        if (!transcode(in, out, sourceBitRate, allowance, progress)) return false;
        // The muxer's tables went in when it stopped, after the last frame was counted.
        allowance.check(out);
        return true;
    }

    private static boolean transcode(File in, File out, long sourceBitRate, OutputAllowance allowance,
            Downloader.Progress progress) throws IOException {
        MediaExtractor source = null;
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        Surface surface = null;
        MediaMuxer muxer = null;
        boolean muxing = false;
        Throwable failure = null;

        try {
            source = new MediaExtractor();
            source.setDataSource(in.getPath());
            MediaFormat format = pictureTrack(source);
            if (format == null) throw new IOException("the picture file holds no picture track");
            int width = format.getInteger(MediaFormat.KEY_WIDTH);
            int height = format.getInteger(MediaFormat.KEY_HEIGHT);
            if (format.containsKey(MediaFormat.KEY_DURATION) && format.getLong(MediaFormat.KEY_DURATION) > MAX_DURATION_US) {
                throw new IOException("the picture runs over " + MAX_DURATION_US / 60_000_000 + " minutes");
            }
            int frameRate = format.containsKey(MediaFormat.KEY_FRAME_RATE)
                ? Math.max(1, format.getInteger(MediaFormat.KEY_FRAME_RATE)) : DEFAULT_FRAME_RATE;
            int rotation = format.containsKey(MediaFormat.KEY_ROTATION) ? format.getInteger(MediaFormat.KEY_ROTATION) : 0;

            MediaCodecList codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            MediaFormat encode = encoderFormat(width, height, frameRate, bitRate(width, height, frameRate, sourceBitRate));
            String encoderName = codecs.findEncoderForFormat(encode);
            if (encoderName == null) throw new IOException("no H.264 encoder takes " + width + "x" + height);
            keepColors(format, encode);
            encoder = MediaCodec.createByCodecName(encoderName);
            encoder.configure(encode, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = encoder.createInputSurface();
            encoder.start();

            // A stated frame rate makes findDecoderForFormat demand that speed at this size, and a
            // conversion doesn't need real time, so it's left out of the question.
            // Android 9 has no removeKey, and a null string is the documented way to clear it there.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) format.removeKey(MediaFormat.KEY_FRAME_RATE);
            else format.setString(MediaFormat.KEY_FRAME_RATE, null);
            String decoderName = codecs.findDecoderForFormat(format);
            decoder = decoderName != null
                ? MediaCodec.createByCodecName(decoderName)
                : MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            decoder.configure(format, surface, null, 0);
            decoder.start();

            muxer = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (rotation != 0) muxer.setOrientationHint(rotation);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            boolean sourceDone = false;
            boolean decoderDone = false;
            boolean encoderDone = false;
            int track = -1;
            int drawn = 0;
            int written = 0;
            long lastWork = SystemClock.uptimeMillis();
            int idle = 0;

            while (!encoderDone) {
                if (progress.cancelled()) return false;
                boolean worked = false;

                // 1. The file's next sample into the decoder.
                if (!sourceDone) {
                    int index = decoder.dequeueInputBuffer(WAIT_US);
                    if (index >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(index);
                        int size = buffer == null ? -1 : source.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            sourceDone = true;
                        } else {
                            decoder.queueInputBuffer(index, 0, size, Math.max(0L, source.getSampleTime()), 0);
                            source.advance();
                        }
                        worked = true;
                    }
                }

                // 2. The decoder's frames onto the encoder's surface, each with its own time.
                if (!decoderDone) {
                    int index = decoder.dequeueOutputBuffer(info, WAIT_US);
                    if (index >= 0) {
                        boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        if (info.size > 0) {
                            decoder.releaseOutputBuffer(index, Math.max(0L, info.presentationTimeUs) * 1000);
                            drawn++;
                        } else {
                            decoder.releaseOutputBuffer(index, false);
                        }
                        if (end) {
                            encoder.signalEndOfInputStream();
                            decoderDone = true;
                        }
                        worked = true;
                    } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        worked = true;
                    }
                }

                // 3. The encoder's H.264 into the new file.
                int index = encoder.dequeueOutputBuffer(info, WAIT_US);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxing) throw new IOException("the encoder changed its format twice");
                    track = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxing = true;
                    worked = true;
                } else if (index >= 0) {
                    ByteBuffer encoded = encoder.getOutputBuffer(index);
                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (encoded != null && info.size > 0 && !config) {
                        if (!muxing) throw new IOException("the encoder gave a frame before its format");
                        // Counted before the muxer has it, so a frame that won't fit is never written.
                        try {
                            allowance.take(info.size);
                        } catch (OutputAllowance.Exceeded over) {
                            // How far in it got says how far the encoder ran over.
                            throw new OutputAllowance.Exceeded(over.getMessage() + ", " + info.presentationTimeUs / 1000
                                + " ms into the picture");
                        }
                        encoded.position(info.offset);
                        encoded.limit(info.offset + info.size);
                        muxer.writeSampleData(track, encoded, info);
                        written++;
                    }
                    encoder.releaseOutputBuffer(index, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encoderDone = true;
                    worked = true;
                }

                idle = worked ? 0 : idle + 1;
                if (worked) lastWork = SystemClock.uptimeMillis();
                else stalled(lastWork, idle);
            }
            if (!muxing || written == 0) throw new IOException("the conversion made no picture");
            if (written < drawn * MIN_FRAMES_KEPT) {
                final int kept = written;
                throw new IOException("the encoder kept " + kept + " of " + drawn + " frames");
            }
            final int frames = written;
            final int rate = encode.getInteger(MediaFormat.KEY_BIT_RATE);
            final String colors = describeColors(format);
            MediaDownload.info(() -> "made the picture H.264 " + width + "x" + height + " at " + rate / 1000
                + " kbps, " + frames + " frames, " + colors);
            return true;
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            Throwable closing = failure;
            if (muxer != null) {
                if (muxing) closing = attempt(closing, muxer::stop);
                closing = attempt(closing, muxer::release);
            }
            if (decoder != null) {
                MediaCodec codec = decoder;
                closing = attempt(closing, codec::stop);
                closing = attempt(closing, codec::release);
            }
            if (encoder != null) {
                MediaCodec codec = encoder;
                closing = attempt(closing, codec::stop);
                closing = attempt(closing, codec::release);
            }
            if (surface != null) closing = attempt(closing, surface::release);
            if (source != null) closing = attempt(closing, source::release);
            if (failure == null && closing != null) {
                throw new IOException("the converted picture could not be finished: " + closing.getClass().getSimpleName(),
                    closing);
            }
        }
    }

    private static void stalled(long lastWork, int idle) throws IOException {
        if (SystemClock.uptimeMillis() - lastWork > STALL_MS || idle > STALL_ROUNDS) {
            throw new IOException("the conversion stalled for " + STALL_MS / 1000 + " seconds");
        }
    }

    /** Selects the first picture track of [source] and answers its format, or null. */
    @Nullable
    private static MediaFormat pictureTrack(MediaExtractor source) {
        for (int i = 0; i < source.getTrackCount(); i++) {
            MediaFormat format = source.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                source.selectTrack(i);
                return format;
            }
        }
        return null;
    }

    private interface Step {
        void run() throws Exception;
    }

    private static Throwable attempt(Throwable first, Step step) {
        try {
            step.run();
            return first;
        } catch (Throwable t) {
            return first != null ? first : t;
        }
    }
}
