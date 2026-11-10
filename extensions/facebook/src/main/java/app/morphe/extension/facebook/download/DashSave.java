/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/extensions/extension/src/main/java/app/andrewliang/extension/DashSave.java
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.extension.facebook.download;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;

/**
 * Saves one DASH video track and one audio track as one MP4 file.
 *
 * <p>A DASH manifest keeps the picture and the sound in two files. One plain fetch gets each file.
 * Then {@code MediaMuxer} copies the samples of both into one file, or {@link Mp4Join} does for a
 * picture the muxer won't write ({@link #ownWriter}). Neither decodes or encodes them again. So the
 * file has the quality that the player streams, and the join takes less than a second.
 *
 * <p>The two tracks and the result go into the cache of the app first, because the muxer must seek
 * in its files. Only the finished file goes into the gallery, through the same
 * {@link Downloader.Sink} as all other saves. So an error at any step leaves nothing in the gallery,
 * and the files in the cache are removed whatever happens.
 */
final class DashSave {

    private DashSave() {}

    /** The source every event of a DASH save carries in the diagnostic report. */
    private static final String SOURCE = "DashSave";

    private static final String CACHE_FOLDER = "hushfacebook-save";

    /** A file older than this is from a save that the system stopped. */
    private static final long STALE_MS = 60L * 60L * 1000L;

    private static final int DEFAULT_SAMPLE_BUFFER = 2 * 1024 * 1024;

    /** The largest sample a join makes room for. A 1080p key frame runs to a megabyte or two. */
    private static final int MAX_SAMPLE_BUFFER = 16 * 1024 * 1024;

    /** Space the work files leave free on their storage, whatever the running saves want. */
    static final long KEEP_FREE = 128L * 1024L * 1024L;

    /** The free space a test says the work folder's storage has. Never set on a phone. */
    static volatile java.util.function.LongSupplier usableForTests;

    private static volatile Boolean canWriteAv1;

    /**
     * Whether this device can save an AV1 track: whether it has an AV1 decoder, without which it
     * can't play the file. The muxer writes AV1 into an MP4 from Android 14, and {@link Mp4Join}
     * before that.
     */
    static boolean canWriteAv1() {
        if (canWriteAv1 == null) canWriteAv1 = hasAv1Decoder();
        return canWriteAv1;
    }

    private static boolean hasAv1Decoder() {
        try {
            for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (codec.isEncoder()) continue;
                for (String type : codec.getSupportedTypes()) {
                    if ("video/av01".equalsIgnoreCase(type)) return true;
                }
            }
        } catch (Throwable ignored) {
            // No codec list, so no decoder.
        }

        return false;
    }

    /**
     * The folder every save works in, created and cleared of what a stopped save left, or
     * {@code null} when the cache can't hold one.
     */
    static File workFolder(Context application) {
        File folder = new File(application.getCacheDir(), CACHE_FOLDER);
        // mkdirs() answers false when another save made the folder a moment ago, so it only
        // failed if the folder still isn't there.
        if (!folder.mkdirs() && !folder.isDirectory()) return null;
        removeStale(folder);
        return folder;
    }

    /**
     * Download [video] and [audio], join them, and write the result to [sink], reporting each
     * track's fetch to [progress] and stopping when it's cancelled. This blocks and never throws.
     * [audio] is {@code null} for a video with no sound. Both tracks go through the same checks as
     * a single file, so nothing reaches the gallery unless both are Meta's media.
     *
     * <p>[maxBytes] holds the two tracks together, the way it holds a single file: the sound gets
     * what the picture left of it, and the joined file is held to it too. So what reaches the
     * gallery is never over the cap, whichever way it was saved.
     */
    static Downloader.Result save(
        Context application,
        DashManifest.Track video,
        DashManifest.Track audio,
        Downloader.Sink sink,
        MediaUrlPolicy policy,
        long maxBytes,
        Downloader.Progress progress
    ) {
        return save(application, video, audio, false, false, sink, policy, maxBytes, progress);
    }

    /**
     * As above. With [transcodeVideo], the fetched [video] is made H.264 ({@link VideoTranscode}),
     * and with [reencodeSound], the fetched [audio] is made AAC-LC ({@link AacReencode}), before
     * the join. A conversion that fails fails the save, and the caller's single file follows. Each
     * conversion writes into the room reserved for it and stops at the first sample past it
     * ({@link OutputAllowance}), and its work file goes with the rest whatever happens.
     */
    static Downloader.Result save(
        Context application,
        DashManifest.Track video,
        DashManifest.Track audio,
        boolean transcodeVideo,
        boolean reencodeSound,
        Downloader.Sink sink,
        MediaUrlPolicy policy,
        long maxBytes,
        Downloader.Progress progress
    ) {
        return save(application, video, audio, transcodeVideo, reencodeSound, -1, sink, policy, maxBytes, progress);
    }

    /**
     * As above, with [lengthUs] how long the manifest says the video runs, or -1. The sound's size
     * comes only with its own fetch, so until then the picture's total counts the sound at a little
     * over its announced rate for that long ({@link DashManifest.Track#bytesOver}). Added only when
     * the sound started, the total grew and the bar fell back (83% to 78% on a 78 minute replay).
     */
    static Downloader.Result save(
        Context application,
        DashManifest.Track video,
        DashManifest.Track audio,
        boolean transcodeVideo,
        boolean reencodeSound,
        long lengthUs,
        Downloader.Sink sink,
        MediaUrlPolicy policy,
        long maxBytes,
        Downloader.Progress progress
    ) {
        File videoFile = null;
        File audioFile = null;
        File picture = null;
        File sound = null;
        File joined = null;

        try {
            File folder = workFolder(application);
            if (folder == null) {
                return Downloader.Result.fail(Downloader.Status.WRITE_ERROR, Downloader.Cause.STORAGE, "no cache folder");
            }

            videoFile = workFile("video", ".mp4", folder);
            Downloader.Result result = fetchWork(video.url, Downloader.Kind.VIDEO, videoFile, policy, maxBytes,
                Downloader.between(0, audio == null ? 0 : audio.bytesOver(lengthUs), progress));
            if (!result.ok()) return result;

            if (audio != null) {
                audioFile = workFile("audio", ".mp4", folder);
                // One count for the pair: the sound's bytes go on from the picture's.
                result = fetchWork(audio.url, Downloader.Kind.AUDIO, audioFile, policy,
                    maxBytes - videoFile.length(), Downloader.after(videoFile.length(), progress));
                if (!result.ok()) return result;
            }

            // What the join writes: H.264 once the picture is converted.
            String pictureCodecs = video.codecs;
            if (transcodeVideo) {
                if (progress.cancelled()) return Downloader.Result.fail(Downloader.Status.CANCELLED, "cancelled before the conversion");
                progress.joining();
                picture = workFile("picture", ".mp4", folder);
                // H.264 at the bitrate VideoTranscode picks runs several times the VP9 or AV1 it came
                // from, so the room goes by how long the picture runs, as the sound's does.
                long room = Math.min(maxBytes, VideoTranscode.room(videoFile, video.bandwidth));
                if (reserve(picture, room) < room) {
                    return Downloader.Result.fail(Downloader.Status.WRITE_ERROR, Downloader.Cause.NO_SPACE,
                        "not enough free space to convert the picture");
                }
                long started = SystemClock.uptimeMillis();
                try {
                    if (!VideoTranscode.transcode(videoFile, picture, video.bandwidth, room, progress)) {
                        return Downloader.Result.fail(Downloader.Status.CANCELLED, "cancelled during the conversion");
                    }
                } catch (OutputAllowance.Exceeded over) {
                    return Downloader.Result.fail(Downloader.Status.WRITE_ERROR, Downloader.Cause.CONVERSION,
                        "the converted picture outgrew the " + room + " bytes kept for it (" + over.getMessage() + ")");
                }
                long took = SystemClock.uptimeMillis() - started;
                File made = picture;
                holdTo(made, made.length());
                MediaDownload.info(() -> "made the " + video.codecs + " picture H.264 in " + took + " ms (" + made.length()
                    + " bytes)");
                videoFile = discard(videoFile);
                videoFile = picture;
                picture = null;
                pictureCodecs = "avc1";
            }

            if (audioFile != null && reencodeSound) {
                if (progress.cancelled()) return Downloader.Result.fail(Downloader.Status.CANCELLED, "cancelled before the re-encode");
                sound = workFile("sound", ".m4a", folder);
                // AAC-LC at its bitrate runs a few times the xHE-AAC it came from, so the room goes
                // by how long the sound runs. A track over the cap couldn't join under it either.
                long room = Math.min(maxBytes, AacReencode.room(audioFile));
                if (reserve(sound, room) < room) {
                    return Downloader.Result.fail(Downloader.Status.WRITE_ERROR, Downloader.Cause.NO_SPACE,
                        "not enough free space to re-encode the sound");
                }
                long started = SystemClock.uptimeMillis();
                try {
                    if (!AacReencode.reencode(audioFile, sound, room, progress)) {
                        return Downloader.Result.fail(Downloader.Status.CANCELLED, "cancelled during the re-encode");
                    }
                } catch (OutputAllowance.Exceeded over) {
                    return Downloader.Result.fail(Downloader.Status.WRITE_ERROR, Downloader.Cause.CONVERSION,
                        "the re-encoded sound outgrew the " + room + " bytes kept for it");
                }
                long took = SystemClock.uptimeMillis() - started;
                File made = sound;
                holdTo(made, made.length());
                MediaDownload.info(() -> "made the xHE-AAC sound AAC-LC in " + took + " ms (" + made.length() + " bytes)");
                audioFile = discard(audioFile);
                audioFile = sound;
                sound = null;
            }

            if (progress.cancelled()) return Downloader.Result.fail(Downloader.Status.CANCELLED, "cancelled before the join");
            joined = workFile("joined", ".mp4", folder);
            // The joined file is about the size of the two tracks, with boxes of its own on top.
            long tracks = videoFile.length() + (audioFile == null ? 0 : audioFile.length());
            long room = tracks + tracks / 16 + JOIN_BOXES;
            if (reserve(joined, room) < room) {
                return Downloader.Result.fail(Downloader.Status.WRITE_ERROR, Downloader.Cause.NO_SPACE,
                    "not enough free space to join the tracks");
            }
            boolean own = ownWriter(pictureCodecs, Build.VERSION.SDK_INT);
            if (own) {
                String codec = pictureCodecs.startsWith("vp09") ? "VP9" : "AV1 before Android 14";
                MediaDownload.info(() -> "joining the tracks without MediaMuxer, which can't write " + codec
                    + " into an MP4");
            }
            try {
                if (!(own ? Mp4Join.join(videoFile, audioFile, joined, Math.min(maxBytes, room), progress)
                        : join(videoFile, audioFile, joined, maxBytes, room, progress))) {
                    return cancelledJoining();
                }
            } catch (JoinTooLarge over) {
                return Downloader.Result.fail(Downloader.Status.TOO_LARGE, "the joined samples exceed " + maxBytes + " bytes");
            } catch (OutputAllowance.Exceeded over) {
                if (own && maxBytes <= room) {
                    return Downloader.Result.fail(Downloader.Status.TOO_LARGE, "the joined file exceeds " + maxBytes + " bytes");
                }
                return Downloader.Result.fail(Downloader.Status.WRITE_ERROR, Downloader.Cause.NO_SPACE,
                        "the joined file outgrew its reserved space");
            }
            // The tracks are in the joined file now. Kept, they'd sit beside it and the gallery's
            // copy of it: the video on the phone four times over.
            videoFile = discard(videoFile);
            audioFile = discard(audioFile);
            // Joining writes boxes of its own, so the file itself is held to the cap as well.
            if (joined.length() > maxBytes) {
                return Downloader.Result.fail(Downloader.Status.TOO_LARGE,
                    "the joined file is " + joined.length() + " bytes, more than " + maxBytes);
            }

            Downloader.Result published = Downloader.publish(joined, "video/mp4", sink, progress);
            if (published.ok()) {
                ReadBack holds = savedFormat(joined);
                MediaDownload.info(() -> "the saved file holds " + holds.text);
                if (holds.refused) return published.refused();
            }
            return published;
        } catch (Throwable t) {
            // A cancel can end the join in a failure of its own, a muxer stopped with no sample
            // for one. It's still the person's cancel.
            if (progress.cancelled()) return cancelledJoining();
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "the DASH save failed", t);
            // A work file that couldn't be made is the storage's doing. Anything else thrown in
            // here came out of a conversion or the join.
            if (t instanceof Downloader.StorageFailure) {
                return Downloader.Result.fail(Downloader.Status.WRITE_ERROR, Downloader.Cause.STORAGE,
                    "a work file could not be made");
            }
            return Downloader.Result.fail(Downloader.Status.WRITE_ERROR, Downloader.Cause.CONVERSION,
                "the tracks could not be joined");
        } finally {
            discard(videoFile);
            discard(audioFile);
            discard(picture);
            discard(sound);
            discard(joined);
        }
    }

    /**
     * Whether a picture of [codecs] is joined by {@link Mp4Join} on Android [sdk], rather than by
     * MediaMuxer: VP9, which the muxer never writes into an MP4, and AV1 before Android 14, when the
     * muxer learned it. H.264, H.265 and AV1 from Android 14 keep the muxer, as every save did.
     */
    static boolean ownWriter(String codecs, int sdk) {
        return codecs.startsWith("vp09") || (codecs.startsWith("av01") && sdk < 34);
    }

    // ---------------------------------------------------------------- work files

    /** Room a join keeps for the boxes it writes beside the samples. */
    private static final long JOIN_BOXES = 1024L * 1024L;

    /**
     * The work files of the running saves, each with the size it may grow to. Up to three saves run
     * at once, each with up to two tracks and a joined file. A file listed here is one a save is
     * still using, and cleanup of old files leaves it alone.
     */
    private static final Map<String, Long> WORK = new HashMap<>();

    /**
     * Makes [file] a work file of a running save, which may grow to the answer: at most [wanted],
     * and no more than the storage has free beyond {@link #KEEP_FREE} and what the other work files
     * may still grow by. The file stays the save's until {@link #discard}.
     */
    static long reserve(File file, long wanted) {
        synchronized (WORK) {
            long growing = 0;
            for (Map.Entry<String, Long> other : WORK.entrySet()) {
                growing += Math.max(0L, other.getValue() - new File(other.getKey()).length());
            }
            java.util.function.LongSupplier forTests = usableForTests;
            long usable = forTests != null ? forTests.getAsLong() : file.getParentFile().getUsableSpace();
            long granted = Math.max(0L, Math.min(wanted, usable - KEEP_FREE - growing));
            WORK.put(file.getAbsolutePath(), granted);
            return granted;
        }
    }

    /** [file] won't grow past [size], so it claims no more than that. */
    private static void holdTo(File file, long size) {
        synchronized (WORK) {
            Long granted = WORK.get(file.getAbsolutePath());
            if (granted != null && size >= 0 && size < granted) WORK.put(file.getAbsolutePath(), size);
        }
    }

    /**
     * A new, empty work file in [folder]. One that can't be made is the storage's doing, told apart
     * from a conversion or a join that failed.
     */
    static File workFile(String prefix, String suffix, File folder) throws Downloader.StorageFailure {
        try {
            return File.createTempFile(prefix, suffix, folder);
        } catch (IOException e) {
            throw new Downloader.StorageFailure(e);
        }
    }

    /** Whether a running save still uses [file]. Cleanup of work files leaves such a file alone. */
    static boolean inUse(File file) {
        synchronized (WORK) {
            return WORK.containsKey(file.getAbsolutePath());
        }
    }

    /** Deletes [file] and gives up its claim. Answers null, for the variable that held it. */
    static File discard(File file) {
        if (file == null) return null;
        synchronized (WORK) {
            WORK.remove(file.getAbsolutePath());
        }
        Downloader.delete(file);
        return null;
    }

    /**
     * Fetches [url] into the work file [into], which may grow to [cap] as far as the free space
     * allows ({@link #reserve}). A fetch the free space held below the cap fails for want of room,
     * not as a file too large to save. The caller discards [into].
     */
    static Downloader.Result fetchWork(String url, Downloader.Kind kind, File into, MediaUrlPolicy policy, long cap,
            Downloader.Progress progress) {
        long room = reserve(into, cap);
        if (room <= 0 && cap > 0) {
            return Downloader.Result.fail(Downloader.Status.WRITE_ERROR, Downloader.Cause.NO_SPACE,
                "not enough free space for a work file");
        }
        Downloader.Result result = Downloader.fetch(url, kind, into, policy, room, new Downloader.Progress() {
            private boolean sized;

            @Override
            public void transferred(long done, long total) {
                // The first report comes once the answer has begun, with its announced size.
                if (!sized) holdTo(into, total);
                sized = true;
                progress.transferred(done, total);
            }

            @Override
            public void reading(Runnable close) {
                progress.reading(close);
            }

            @Override
            public boolean cancelled() {
                return progress.cancelled();
            }
        });
        // Fetched, it grows no more, whatever size was announced or not.
        holdTo(into, into.length());
        if (result.status == Downloader.Status.TOO_LARGE && room < cap) {
            return Downloader.Result.fail(Downloader.Status.WRITE_ERROR, Downloader.Cause.NO_SPACE,
                "not enough free space for a work file over " + room + " bytes");
        }
        return result;
    }

    // ---------------------------------------------------------------- internals

    private static Downloader.Result cancelledJoining() {
        return Downloader.Result.fail(Downloader.Status.CANCELLED, "cancelled during the join");
    }

    /**
     * Copy the samples of both files into [out], in order of time. Answers false when [progress]
     * was cancelled first.
     *
     * <p>Each step writes the sample that comes first in time, from either track. A file with all
     * of the video before all of the sound also plays. But a player must then seek across the whole
     * file to start, and some players refuse that.
     *
     * <p>Cancel is read before every sample, between one native read and write and the next. A
     * native call that blocks isn't interrupted, so a cancel waits for that one call at most. Under
     * Robolectric (DashJoinTest) a minute of 720p, 4,384 samples, joined in 37 to 66 ms, and a
     * cancel half way ended the join within 1 ms. A phone's time per sample isn't measured yet.
     */
    private static boolean join(File video, File audio, File out, Downloader.Progress progress) throws IOException {
        return join(video, audio, out, Long.MAX_VALUE, Long.MAX_VALUE, progress);
    }

    private static boolean join(File video, File audio, File out, long maxBytes, long room,
                                Downloader.Progress progress) throws IOException {
        OutputAllowance allowance = new OutputAllowance(room);
        long copied = 0;
        MediaExtractor videoIn = null;
        MediaExtractor audioIn = null;
        MediaMuxer muxer = null;
        boolean started = false;
        Throwable failure = null;

        try {
            videoIn = new MediaExtractor();
            videoIn.setDataSource(video.getPath());
            if (audio != null) {
                audioIn = new MediaExtractor();
                audioIn.setDataSource(audio.getPath());
            }
            if (progress.cancelled()) return false;
            progress.joining();

            muxer = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int bufferSize = DEFAULT_SAMPLE_BUFFER;

            MediaFormat videoFormat = selectTrack(videoIn, "video/");
            if (videoFormat == null) throw new IOException("the video file holds no video track");
            int videoTrack = muxer.addTrack(videoFormat);
            bufferSize = Math.max(bufferSize, maxInputSize(videoFormat, "video"));

            int audioTrack = -1;
            if (audioIn != null) {
                MediaFormat audioFormat = selectTrack(audioIn, "audio/");
                if (audioFormat == null) throw new IOException("the audio file holds no audio track");
                audioTrack = muxer.addTrack(audioFormat);
                bufferSize = Math.max(bufferSize, maxInputSize(audioFormat, "audio"));
            }

            muxer.start();
            started = true;

            ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            // An AAC track with an edit list starts before zero: MediaExtractor reports its
            // priming frames at negative times, and MediaMuxer refuses a negative presentation
            // time. A loop that ended a track at its first negative time copied no sound at all.
            // So a track ends only on an empty read, and both tracks shift by the one lead-in, so
            // the gap between the picture and the sound survives the shift.
            long videoTime = videoIn.getSampleTime();
            long audioTime = audioIn == null ? Long.MAX_VALUE : audioIn.getSampleTime();
            long leadIn = Math.min(0L, Math.min(videoTime, audioIn == null ? 0L : audioTime));

            boolean videoDone = false;
            boolean audioDone = audioIn == null;

            while (!videoDone || !audioDone) {
                if (progress.cancelled()) return false;
                boolean takeVideo = !videoDone && (audioDone || videoTime <= audioTime);
                MediaExtractor from = takeVideo ? videoIn : audioIn;
                int track = takeVideo ? videoTrack : audioTrack;

                buffer.clear();
                int size = from.readSampleData(buffer, 0);
                if (size < 0) {
                    if (takeVideo) videoDone = true; else audioDone = true;
                    continue;
                }

                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = (takeVideo ? videoTime : audioTime) - leadIn;
                info.flags = (from.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME
                    : 0;

                if (size > maxBytes - copied) throw new JoinTooLarge();
                allowance.take(size);
                copied += size;
                muxer.writeSampleData(track, buffer, info);

                boolean more = from.advance();
                long next = more ? from.getSampleTime() : Long.MAX_VALUE;
                if (takeVideo) {
                    videoTime = next;
                    if (!more) videoDone = true;
                } else {
                    audioTime = next;
                    if (!more) audioDone = true;
                }
            }
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            // Each one is released whatever the one before it did. A muxer that failed to stop used
            // to skip both extractors, and its failure took the place of the one that came first.
            Throwable closing = failure;
            if (muxer != null) {
                if (started) closing = attempt(closing, muxer::stop);
                closing = attempt(closing, muxer::release);
            }
            if (videoIn != null) closing = attempt(closing, videoIn::release);
            if (audioIn != null) closing = attempt(closing, audioIn::release);
            if (failure == null && closing != null) {
                throw new IOException("the joined file could not be finished: " + closing.getClass().getSimpleName(),
                    closing);
            }
        }
        allowance.check(out);
        return true;
    }

    private static final class JoinTooLarge extends IOException { }

    /**
     * Runs [step], and answers the failure to report: [failure] when there was one, with the step's
     * own added to it, else the step's.
     */
    private static Throwable attempt(Throwable failure, Runnable step) {
        try {
            step.run();
        } catch (Throwable t) {
            if (failure == null) return t;
            failure.addSuppressed(t);
        }
        return failure;
    }

    // ---------------------------------------------------------------- what a saved file holds

    /** The most tracks a saved file's report line describes. */
    private static final int MAX_DESCRIBED_TRACKS = 4;

    /**
     * The short side of the first video track [file] holds, in pixels, or 0 when it has none or the
     * phone can't read it. What a save's "lower" note is weighed against: the file that actually
     * reached the gallery, never a label read off its address or a track's own declared size.
     */
    static int savedVideoShortSide(File file) {
        MediaExtractor extractor = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(file.getPath());
            MediaFormat format = selectTrack(extractor, "video/");
            if (format == null) return 0;
            Integer width = number(format, MediaFormat.KEY_WIDTH);
            Integer height = number(format, MediaFormat.KEY_HEIGHT);
            return width == null || height == null ? 0 : Math.min(width, height);
        } catch (Throwable t) {
            return 0;
        } finally {
            if (extractor != null) attempt(null, extractor::release);
        }
    }

    /**
     * What [file] holds, read back from the file itself: each track's codec and profile, its size or
     * its sample rate and channels, and its duration. Reports like #11 and #14 can't be settled from
     * a candidate's address, its quality label or an MP4 type. What the file doesn't say is
     * "unknown", never a guess: an AAC track names the object type its header declares, which for
     * HE-AAC with implicit signalling is the LC core. Only codec facts go in, cut to a fixed size,
     * and never an address, a path, a name or an id.
     */
    static ReadBack savedFormat(File file) {
        MediaExtractor extractor = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(file.getPath());
            int count = extractor.getTrackCount();
            if (count <= 0) return new ReadBack("no track the phone could read", false);
            StringBuilder tracks = new StringBuilder();
            boolean refused = false;
            for (int i = 0; i < count; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                refused |= othersMayRefuse(token(text(format, MediaFormat.KEY_MIME)), aacType(format));
                if (i >= MAX_DESCRIBED_TRACKS) continue;
                if (tracks.length() > 0) tracks.append(", ");
                tracks.append(describe(format));
            }
            if (count > MAX_DESCRIBED_TRACKS) tracks.append(", ").append(count - MAX_DESCRIBED_TRACKS).append(" more track(s)");
            return new ReadBack(tracks.toString(), refused);
        } catch (Throwable t) {
            return new ReadBack("nothing the phone could read (" + t.getClass().getSimpleName() + ")", false);
        } finally {
            if (extractor != null) attempt(null, extractor::release);
        }
    }

    /** What {@link #savedFormat} read back from a saved file. */
    static final class ReadBack {
        /** Each track's codec facts, for the report. */
        final String text;
        /**
         * A track the file declares is one WhatsApp and some editors refuse ({@link #othersMayRefuse}).
         * A file the phone couldn't read isn't one: the message says what's known, never a guess.
         */
        final boolean refused;

        ReadBack(String text, boolean refused) {
            this.text = text;
            this.refused = refused;
        }
    }

    /**
     * Whether a track of type [mime], of AAC object type [aacType] when it's AAC, is one WhatsApp
     * and some editors refuse: AV1, VP9 or HEVC pictures, or xHE-AAC sound. #11's failed file was
     * AV1 (dav1d), and WhatsApp stopped at 40%; #14's POCO gallery couldn't play an AV1 reel with
     * xHE-AAC sound. H.264 and the other AAC types every one of them plays.
     */
    static boolean othersMayRefuse(String mime, Integer aacType) {
        if (mime == null) return false;
        switch (mime) {
            case "video/av01":
            case "video/x-vnd.on2.vp9":
            case "video/hevc":
                return true;
            case "audio/mp4a-latm":
                return aacType != null && aacType == MediaCodecInfo.CodecProfileLevel.AACObjectXHE;
            default:
                return false;
        }
    }

    /** The AAC object type a track declares, or null. */
    private static Integer aacType(MediaFormat format) {
        Integer type = number(format, MediaFormat.KEY_AAC_PROFILE);
        return type != null ? type : number(format, MediaFormat.KEY_PROFILE);
    }

    private static String describe(MediaFormat format) {
        String mime = token(text(format, MediaFormat.KEY_MIME));
        StringBuilder track = new StringBuilder(mime == null ? "a track of unknown type" : mime);
        String codecs = token(text(format, MediaFormat.KEY_CODECS_STRING));
        if (codecs != null) track.append(" (").append(codecs).append(')');
        if (mime != null && mime.startsWith("video/")) {
            track.append(' ').append(videoProfile(mime, number(format, MediaFormat.KEY_PROFILE)));
            Integer width = number(format, MediaFormat.KEY_WIDTH);
            Integer height = number(format, MediaFormat.KEY_HEIGHT);
            track.append(' ').append(width == null || height == null ? "size unknown" : width + "x" + height);
        } else if (mime != null && mime.startsWith("audio/")) {
            if (mime.equals("audio/mp4a-latm")) {
                Integer type = aacType(format);
                track.append(' ').append(type == null ? "AAC object type unknown" : "AAC object type " + type + aacName(type));
            }
            Integer rate = number(format, MediaFormat.KEY_SAMPLE_RATE);
            Integer channels = number(format, MediaFormat.KEY_CHANNEL_COUNT);
            track.append(' ').append(rate == null ? "rate unknown" : rate + " Hz");
            track.append(' ').append(channels == null ? "channels unknown" : channels + " ch");
        }
        Long duration = null;
        try {
            if (format.containsKey(MediaFormat.KEY_DURATION)) duration = format.getLong(MediaFormat.KEY_DURATION);
        } catch (Throwable ignored) {
            // Stored as something other than a long: unknown.
        }
        track.append(' ').append(duration == null || duration < 0 ? "duration unknown"
            : String.format(Locale.US, "%.2f s", duration / 1_000_000.0));
        return track.toString();
    }

    /** The profile of a video track, named where it's one of the common ones. */
    private static String videoProfile(String mime, Integer profile) {
        if (profile == null) return "profile unknown";
        String name = null;
        switch (mime) {
            case "video/avc":
                if (profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline) name = "Baseline";
                else if (profile == MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline) name = "Constrained Baseline";
                else if (profile == MediaCodecInfo.CodecProfileLevel.AVCProfileMain) name = "Main";
                else if (profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh) name = "High";
                else if (profile == MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh) name = "Constrained High";
                break;
            case "video/hevc":
                if (profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain) name = "Main";
                else if (profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) name = "Main 10";
                break;
            case "video/av01":
                if (profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8) name = "Main 8-bit";
                else if (profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10) name = "Main 10-bit";
                break;
            default:
                break;
        }
        return "profile " + (name == null ? String.valueOf(profile) : name + " (" + profile + ")");
    }

    /** The name of the AAC object type a track declares, for the common ones. */
    private static String aacName(int type) {
        if (type == MediaCodecInfo.CodecProfileLevel.AACObjectLC) return " (LC)";
        if (type == MediaCodecInfo.CodecProfileLevel.AACObjectHE) return " (HE-AAC)";
        if (type == MediaCodecInfo.CodecProfileLevel.AACObjectHE_PS) return " (HE-AAC v2)";
        if (type == MediaCodecInfo.CodecProfileLevel.AACObjectXHE) return " (xHE-AAC)";
        return "";
    }

    private static String text(MediaFormat format, String key) {
        try {
            return format.containsKey(key) ? format.getString(key) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Integer number(MediaFormat format, String key) {
        try {
            return format.containsKey(key) ? format.getInteger(key) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** [value] as a codec token of at most 40 characters, or null when it's anything else. */
    private static String token(String value) {
        if (value == null) return null;
        String token = value.trim().toLowerCase(Locale.US);
        return token.matches("[a-z0-9][a-z0-9./+-]{0,39}") ? token : null;
    }

    /** Select the first track of [kind] and return its format, or {@code null}. */
    private static MediaFormat selectTrack(MediaExtractor extractor, String kind) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(kind)) {
                extractor.selectTrack(i);
                return format;
            }
        }
        return null;
    }

    /**
     * The largest sample [format]'s track declares, or 0. The file says so itself, and the buffer
     * used to be allocated at whatever it said, so a track over {@link #MAX_SAMPLE_BUFFER} fails the
     * join and the save goes on to the single file.
     */
    private static int maxInputSize(MediaFormat format, String kind) throws IOException {
        int declared;
        try {
            declared = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                : 0;
        } catch (Throwable t) {
            return 0;
        }
        if (declared > MAX_SAMPLE_BUFFER) {
            throw new IOException("the " + kind + " track declares samples of " + declared + " bytes, more than the "
                + MAX_SAMPLE_BUFFER + " a join holds");
        }
        return declared;
    }

    private static void removeStale(File folder) {
        File[] files = folder.listFiles();
        if (files == null) return;

        long now = System.currentTimeMillis();
        for (File file : files) {
            // A running save still owns its older files: a picture can wait an hour on its sound.
            if (now - file.lastModified() > STALE_MS && !inUse(file)) Downloader.delete(file);
        }
    }
}
