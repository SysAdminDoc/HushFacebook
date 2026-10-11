/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Turns a sound track into AAC-LC, the AAC every app that takes an MP4 plays, in an MP4 of its own.
 *
 * <p>For a save whose only sound is xHE-AAC: video editors turn that down (on the S22 in 2026,
 * CapCut's export stalled at 99% and InShot's came out silent), and the picture beside it is fine.
 * Android's own AAC decoder has read xHE-AAC since Android 9, and every phone has an AAC-LC
 * encoder, so the sound is decoded to PCM and encoded again, at a bitrate well above what it came
 * in at, and nothing else of the save changes.
 *
 * <p>The track keeps its timing: each block of sound is stamped from the decoder's own time for it,
 * so a track that starts late, or early, still lines up with the picture after the join.
 */
final class AacReencode {
    /** What the new track is encoded at. Facebook sends xHE-AAC at 48 to 96 kbps. */
    static final int BIT_RATE = 160_000;

    /** How long one wait on a codec lasts. */
    private static final long WAIT_US = 10_000;

    /** A codec that gives nothing back for this long has stalled. */
    static final long STALL_MS = 10_000;

    /**
     * Rounds of the loop with nothing done that also count as a stall: each waits up to three
     * times {@link #WAIT_US}, and a codec that answers a wait at once must not spin for ever.
     */
    static final int STALL_ROUNDS = 3_000;

    /** The AAC object type xHE-AAC (USAC) declares, as in {@code mp4a.40.42}. */
    static final int XHE_OBJECT_TYPE = 42;

    private AacReencode() {}

    /** Whether [codecs], a manifest track's codec string, is xHE-AAC. */
    static boolean isXhe(@Nullable String codecs) {
        return codecs != null && codecs.trim().equalsIgnoreCase("mp4a.40." + XHE_OBJECT_TYPE);
    }

    /**
     * Whether this phone can make the new track: an AAC decoder, which on every Android this runs
     * on reads xHE-AAC, and an AAC encoder. Asked before a save is planned around it.
     */
    static boolean available() {
        try {
            MediaCodecList codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            boolean decoder = false;
            boolean encoder = false;
            for (MediaCodecInfo info : codecs.getCodecInfos()) {
                for (String type : info.getSupportedTypes()) {
                    if (!MediaFormat.MIMETYPE_AUDIO_AAC.equalsIgnoreCase(type)) continue;
                    if (info.isEncoder()) encoder = true; else decoder = true;
                }
            }
            return decoder && encoder;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /**
     * Writes the first sound track of [in] to [out] as AAC-LC. Answers false when [progress] was
     * cancelled first; throws when a codec can't be had, stalls or fails, and [out] is then the
     * caller's to discard.
     */
    static boolean reencode(File in, File out, Downloader.Progress progress) throws IOException {
        MediaExtractor source = null;
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        boolean muxing = false;
        Throwable failure = null;

        try {
            source = new MediaExtractor();
            source.setDataSource(in.getPath());
            MediaFormat format = soundTrack(source);
            if (format == null) throw new IOException("the sound file holds no sound track");
            // 16-bit samples, which the encoder takes as they come.
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);

            String decoderName = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format);
            decoder = decoderName != null
                ? MediaCodec.createByCodecName(decoderName)
                : MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            decoder.configure(format, null, null, 0);
            decoder.start();

            muxer = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            boolean sourceDone = false;
            boolean decoderDone = false;
            boolean encoderDone = false;
            boolean endSent = false;
            int track = -1;
            // The block of sound the decoder handed over and the encoder hasn't taken all of yet.
            ByteBuffer pending = null;
            int pendingIndex = -1;
            long pendingTime = 0;
            int frameBytes = 2;
            int sampleRate = 0;
            long lastWork = SystemClock.uptimeMillis();
            int idle = 0;
            int written = 0;

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

                // 2. The decoder's sound into the encoder, as much as each input buffer holds.
                if (pending == null && !decoderDone) {
                    int index = decoder.dequeueOutputBuffer(info, WAIT_US);
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat pcm = decoder.getOutputFormat();
                        int rate = pcm.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? pcm.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 0;
                        int channels = pcm.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? pcm.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 0;
                        if (rate <= 0 || channels <= 0) throw new IOException("the decoder gave no sample rate or channel count");
                        if (encoder == null) {
                            sampleRate = rate;
                            frameBytes = 2 * channels;
                            encoder = startEncoder(rate, channels);
                        } else {
                            sameFormat(rate, channels, sampleRate, frameBytes);
                        }
                        worked = true;
                    } else if (index >= 0) {
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) decoderDone = true;
                        ByteBuffer decoded = decoder.getOutputBuffer(index);
                        if (decoded != null && info.size > 0) {
                            decoded.position(info.offset);
                            decoded.limit(info.offset + info.size);
                            pending = decoded;
                            pendingIndex = index;
                            pendingTime = Math.max(0L, info.presentationTimeUs);
                        } else {
                            decoder.releaseOutputBuffer(index, false);
                        }
                        worked = true;
                    }
                }
                if (encoder == null) {
                    if (decoderDone) throw new IOException("the decoder gave no sound format");
                    idle = worked ? 0 : idle + 1;
                    if (worked) lastWork = SystemClock.uptimeMillis();
                    else stalled(lastWork, idle, "the decoder");
                    continue;
                }
                if (pending != null) {
                    int index = encoder.dequeueInputBuffer(WAIT_US);
                    if (index >= 0) {
                        ByteBuffer into = encoder.getInputBuffer(index);
                        if (into == null) throw new IOException("the encoder gave no input buffer");
                        into.clear();
                        int take = Math.min(into.remaining(), pending.remaining());
                        take -= take % frameBytes;
                        if (take <= 0) throw new IOException("the encoder's input buffer holds no whole frame");
                        long time = pendingTime;
                        ByteBuffer part = pending.duplicate();
                        part.limit(part.position() + take);
                        into.put(part);
                        pending.position(pending.position() + take);
                        pendingTime += take / frameBytes * 1_000_000L / sampleRate;
                        encoder.queueInputBuffer(index, 0, take, time, 0);
                        if (!pending.hasRemaining()) {
                            decoder.releaseOutputBuffer(pendingIndex, false);
                            pending = null;
                            pendingIndex = -1;
                        }
                        worked = true;
                    }
                } else if (decoderDone && !endSent) {
                    int index = encoder.dequeueInputBuffer(WAIT_US);
                    if (index >= 0) {
                        encoder.queueInputBuffer(index, 0, 0, pendingTime, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        endSent = true;
                        worked = true;
                    }
                }

                // 3. The encoder's AAC into the new file.
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
                        if (!muxing) throw new IOException("the encoder gave sound before its format");
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
                else stalled(lastWork, idle, "the re-encode");
            }
            // A file with no sound in it would join into a silent save.
            if (!muxing || written == 0) throw new IOException("the re-encode made no sound");
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
            if (encoder != null) {
                MediaCodec codec = encoder;
                closing = attempt(closing, codec::stop);
                closing = attempt(closing, codec::release);
            }
            if (decoder != null) {
                MediaCodec codec = decoder;
                closing = attempt(closing, codec::stop);
                closing = attempt(closing, codec::release);
            }
            if (source != null) closing = attempt(closing, source::release);
            if (failure == null && closing != null) {
                throw new IOException("the re-encoded sound could not be finished: " + closing.getClass().getSimpleName(),
                    closing);
            }
        }
    }

    /**
     * A later format from the decoder has to repeat the first: the encoder was made for that one,
     * and sound at another rate or channel count through it would come out wrong.
     */
    static void sameFormat(int rate, int channels, int sampleRate, int frameBytes) throws IOException {
        if (rate != sampleRate || 2 * channels != frameBytes) throw new IOException("the decoder changed its sound format");
    }

    private static MediaCodec startEncoder(int sampleRate, int channels) throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        String name = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format);
        MediaCodec encoder = name != null
            ? MediaCodec.createByCodecName(name)
            : MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();
        return encoder;
    }

    private static void stalled(long lastWork, int idle, String what) throws IOException {
        if (SystemClock.uptimeMillis() - lastWork > STALL_MS || idle > STALL_ROUNDS) {
            throw new IOException(what + " stalled for " + STALL_MS / 1000 + " seconds");
        }
    }

    /** Selects the first sound track of [source] and answers its format, or null. */
    @Nullable
    private static MediaFormat soundTrack(MediaExtractor source) {
        for (int i = 0; i < source.getTrackCount(); i++) {
            MediaFormat format = source.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
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
