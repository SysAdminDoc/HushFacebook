/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * Joins a DASH picture track and its sound track into one MP4 by copying boxes and samples, with no
 * MediaMuxer in between.
 *
 * <p>MediaMuxer won't put VP9 into an MP4 on any Android version, nor AV1 before Android 14, though
 * an MP4 holds either and Android's own extractor reads both back from Android 10. Facebook sends
 * each track as a fragmented MP4 of its own: a {@code moov} that describes the track, then
 * {@code moof} and {@code mdat} pairs that hold its samples. This reads each sample's size,
 * duration, flags and composition offset out of the fragments' runs and writes one plain MP4, the
 * kind MediaMuxer writes and every player reads: {@code ftyp}, then a {@code moov} with both tracks
 * and their full sample tables, then one {@code mdat}. Each track's sample descriptions
 * ({@code stsd}) are copied byte for byte, so {@code vp09} with its {@code vpcC}, {@code av01} with
 * its {@code av1C} and {@code mp4a} with its {@code esds} go through unchanged, and so would any
 * other codec. Nothing is decoded or encoded.
 *
 * <p>The samples go straight from file to file, up to half a second of one track at a time,
 * alternating between the tracks by time the way MediaMuxer lays them out. Only the tables are held
 * in memory, 13 bytes a sample: a minute of 30 fps picture beside 44.1 kHz AAC is under 60 KB.
 *
 * <p>Timing is kept exactly. Each sample keeps its duration and composition offset, and each
 * track's edit list is carried over, moved to where the plain file's samples start. So an AAC
 * track's priming stays trimmed by its edit list, a picture's B-frame delay by its own, and both
 * tracks start where they did in Facebook's player. Negative composition offsets become
 * non-negative ones and an edit list that starts that much later, which every player reads.
 *
 * <p>A track can also come as a plain MP4, the kind MediaMuxer writes: the AAC-LC sound
 * {@link AacReencode} makes of an xHE-AAC one. Its samples are read from its own sample tables,
 * each chunk as one run, and copied the same way.
 *
 * <p>What it can't copy, an encrypted track or a file of another shape, fails as an
 * {@link IOException} before the output holds anything worth keeping.
 */
final class Mp4Join {

    private Mp4Join() {}

    /** The largest {@code moov} or {@code moof} read whole. Facebook's are a few kilobytes. */
    private static final int MAX_HEADER_BOX = 16 * 1024 * 1024;

    /** The most samples one track may list: over nine hours of 60 fps picture. */
    private static final int MAX_SAMPLES = 2_000_000;

    /** The longest run of one track the output keeps together before it turns to the other. */
    private static final double CHUNK_SECONDS = 0.5;

    /** The timescale of the output's movie header and edit lists: milliseconds, as MediaMuxer writes. */
    private static final long MOVIE_TIMESCALE = 1000;

    private static final int SAMPLE_IS_NON_SYNC = 0x00010000;

    private static final int FTYP = type("ftyp");
    private static final int MOOV = type("moov");
    private static final int MOOF = type("moof");
    private static final int MDAT = type("mdat");
    private static final int MVHD = type("mvhd");
    private static final int TRAK = type("trak");
    private static final int TKHD = type("tkhd");
    private static final int EDTS = type("edts");
    private static final int ELST = type("elst");
    private static final int MDIA = type("mdia");
    private static final int MDHD = type("mdhd");
    private static final int HDLR = type("hdlr");
    private static final int MINF = type("minf");
    private static final int STBL = type("stbl");
    private static final int STSD = type("stsd");
    private static final int STSZ = type("stsz");
    private static final int STTS = type("stts");
    private static final int CTTS = type("ctts");
    private static final int STSS = type("stss");
    private static final int STSC = type("stsc");
    private static final int STCO = type("stco");
    private static final int CO64 = type("co64");
    private static final int MVEX = type("mvex");
    private static final int TREX = type("trex");
    private static final int TRAF = type("traf");
    private static final int TFHD = type("tfhd");
    private static final int TFDT = type("tfdt");
    private static final int TRUN = type("trun");
    private static final int VMHD = type("vmhd");
    private static final int SMHD = type("smhd");
    private static final int NMHD = type("nmhd");
    private static final int DINF = type("dinf");
    private static final int DREF = type("dref");
    private static final int URL = type("url ");
    private static final int VIDE = type("vide");
    private static final int SOUN = type("soun");
    private static final int ENCV = type("encv");
    private static final int ENCA = type("enca");

    private static final int[] IDENTITY = {0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000};

    /**
     * Joins [video] and [audio], each a fragmented MP4 of one track or a plain MP4 that holds one of
     * its kind, into the plain MP4 [out].
     * [audio] is null for a picture with no sound. Answers false when [progress] was cancelled,
     * which is read before the copy starts and before every chunk of it.
     */
    static boolean join(File video, File audio, File out, Downloader.Progress progress) throws IOException {
        try (RandomAccessFile videoIn = new RandomAccessFile(video, "r");
             RandomAccessFile audioIn = audio == null ? null : new RandomAccessFile(audio, "r")) {
            List<Track> tracks = new ArrayList<>();
            tracks.add(Track.read(videoIn, VIDE, "video", "picture"));
            if (audioIn != null) tracks.add(Track.read(audioIn, SOUN, "audio", "sound"));
            if (progress.cancelled()) return false;
            progress.joining();
            return write(tracks, out, progress);
        }
    }

    // ---------------------------------------------------------------- reading the tracks

    /** One track: what its moov says of it, and each of its samples. */
    private static final class Track {
        final RandomAccessFile file;
        final int handler;
        /** What the report calls its file: "video" or "audio". */
        final String kind;

        long trackId;
        long timescale;
        int language;
        long movieTimescale;
        /** The stsd box's payload, copied whole. */
        ByteBuffer descriptions;
        int descriptionCount;
        byte[] matrix;
        int width;
        int height;
        int volume;
        /** Each edit as {segment duration in the input's movie timescale, media time, rate}, or null. */
        long[][] edits;

        int defaultDescription = 1;
        int defaultDuration;
        int defaultSize;
        int defaultFlags;

        final Ints sizes = new Ints();
        final Ints durations = new Ints();
        final Ints offsets = new Ints();
        final BitSet nonSync = new BitSet();
        /** Each run of samples whose data lie together: where it starts, its end sample, its description. */
        long[] runStarts = new long[16];
        final Ints runEnds = new Ints();
        final Ints runDescriptions = new Ints();
        /** Ordered payload bounds, excluding each mdat's eight- or sixteen-byte header. */
        long[] mediaStarts = new long[16];
        long[] mediaEnds = new long[16];
        int mediaBoxes;

        /** The decode time of the first sample, and of the next one to be read. */
        long firstDecodeTime = -1;
        long decodeTime;

        /** The track's sample tables when they list samples, kept until the moov says whether it has fragments. */
        private List<Box> listedTables;

        /** The chunks the output writes this track's samples in, in order. */
        final List<Chunk> chunks = new ArrayList<>();

        Track(RandomAccessFile file, int handler, String kind) {
            this.file = file;
            this.handler = handler;
            this.kind = kind;
        }

        static Track read(RandomAccessFile file, int handler, String kind, String what) throws IOException {
            Track track = new Track(file, handler, kind);
            try {
                track.readBoxes(what);
            } catch (BufferUnderflowException | IndexOutOfBoundsException | IllegalArgumentException e) {
                throw new IOException("a box of the " + kind + " file is cut short", e);
            }
            return track;
        }

        private void readBoxes(String what) throws IOException {
            long length = file.length();
            long at = 0;
            boolean described = false;
            byte[] head = new byte[16];
            while (length - at >= 8) {
                file.seek(at);
                file.readFully(head, 0, 8);
                long size = ByteBuffer.wrap(head).getInt(0) & 0xFFFFFFFFL;
                int type = ByteBuffer.wrap(head).getInt(4);
                int header = 8;
                if (size == 1) {
                    if (length - at < 16) throw new IOException("a box runs past the end of the " + kind + " file");
                    file.readFully(head, 8, 8);
                    size = ByteBuffer.wrap(head).getLong(8);
                    header = 16;
                } else if (size == 0) {
                    size = length - at;
                }
                if (size < header || size > length - at) {
                    throw new IOException("a box runs past the end of the " + kind + " file");
                }
                if (type == MOOV) {
                    if (described) throw new IOException("the " + kind + " file has two moov boxes");
                    describe(whole(at + header, size - header), what);
                    described = true;
                } else if (type == MOOF) {
                    if (!described) throw new IOException("the " + kind + " file has a fragment before its moov");
                    fragment(whole(at + header, size - header), at, length);
                } else if (type == MDAT) {
                    // Keep range metadata within the same budget as the sample tables, even when
                    // an input lists media boxes its fragments never reference.
                    if (mediaBoxes == MAX_SAMPLES) {
                        throw new IOException("the " + kind + " file lists too many media data boxes");
                    }
                    if (mediaBoxes == mediaStarts.length) {
                        int capacity = Math.min(MAX_SAMPLES, mediaBoxes * 2);
                        mediaStarts = Arrays.copyOf(mediaStarts, capacity);
                        mediaEnds = Arrays.copyOf(mediaEnds, capacity);
                    }
                    mediaStarts[mediaBoxes] = at + header;
                    mediaEnds[mediaBoxes++] = at + size;
                }
                at += size;
            }
            if (!described) throw new IOException("the " + kind + " file has no moov");
            if (sizes.size == 0) throw new IOException("the " + kind + " file holds no samples");
            validateMediaData();
        }

        /** Runs are contiguous, so containing the run contains every nonempty sample in it. */
        private void validateMediaData() throws IOException {
            if (mediaBoxes == 0) throw new IOException("the " + kind + " file has no media data boxes");
            int first = 0;
            for (int run = 0; run < runEnds.size; run++) {
                long bytes = 0;
                int end = runEnds.get(run);
                for (int sample = first; sample < end; sample++) bytes += sizes.get(sample);
                first = end;
                if (bytes == 0) continue;
                long start = runStarts[run];
                int box = Arrays.binarySearch(mediaStarts, 0, mediaBoxes, start);
                if (box < 0) box = -box - 2;
                if (box < 0 || bytes > mediaEnds[box] - start) {
                    throw new IOException("a fragment's samples lie outside the " + kind + " file's media data");
                }
            }
        }

        /** The [size] bytes at [at], which hold one box's payload. */
        private ByteBuffer whole(long at, long size) throws IOException {
            if (size > MAX_HEADER_BOX) throw new IOException("the " + kind + " file has a header box of " + size + " bytes");
            byte[] bytes = new byte[(int) size];
            file.seek(at);
            file.readFully(bytes);
            return ByteBuffer.wrap(bytes);
        }

        private void describe(ByteBuffer moov, String what) throws IOException {
            ByteBuffer mvex = null;
            for (Box box : children(moov)) {
                if (box.type == MVHD) {
                    ByteBuffer b = box.data;
                    b.position(b.get(0) == 1 ? 20 : 12);
                    movieTimescale = u32(b);
                } else if (box.type == TRAK && trackId == 0) {
                    trak(box.data);
                } else if (box.type == MVEX) {
                    mvex = box.data;
                }
            }
            if (trackId == 0) throw new IOException("the " + kind + " file holds no " + what + " track");
            if (timescale == 0) throw new IOException("the " + kind + " track has no timescale");
            if (movieTimescale == 0) movieTimescale = timescale;
            if (listedTables != null) {
                // A fragmented file's samples are all in its fragments.
                if (mvex != null) throw new IOException("the " + kind + " track keeps samples outside its fragments");
                plain(listedTables);
                return;
            }
            if (mvex == null) return;
            for (Box box : children(mvex)) {
                if (box.type != TREX) continue;
                ByteBuffer b = box.data;
                b.position(4);
                if (u32(b) != trackId) continue;
                defaultDescription = b.getInt();
                defaultDuration = b.getInt();
                defaultSize = b.getInt();
                defaultFlags = b.getInt();
            }
        }

        /** Reads [trak] when it's a track of this file's kind. */
        private void trak(ByteBuffer trak) throws IOException {
            List<Box> parts = children(trak);
            Box mdia = find(parts, MDIA);
            if (mdia == null) return;
            List<Box> media = children(mdia.data);
            Box hdlr = find(media, HDLR);
            if (hdlr == null || hdlr.data.getInt(8) != handler) return;

            Box tkhd = find(parts, TKHD);
            if (tkhd == null) throw new IOException("the " + kind + " track has no header");
            ByteBuffer b = tkhd.data;
            boolean wide = b.get(0) == 1;
            b.position(wide ? 20 : 12);
            trackId = u32(b);
            if (trackId == 0) throw new IOException("the " + kind + " track has no id");
            b.position(b.position() + 4 + (wide ? 8 : 4) + 12);
            volume = b.getShort();
            b.position(b.position() + 2);
            matrix = new byte[36];
            b.get(matrix);
            width = b.getInt();
            height = b.getInt();

            Box edts = find(parts, EDTS);
            Box elst = edts == null ? null : find(children(edts.data), ELST);
            if (elst != null) {
                ByteBuffer e = elst.data;
                boolean wideEdits = e.get(0) == 1;
                e.position(4);
                long count = u32(e);
                if (count > e.remaining() / 12) throw new IOException("the " + kind + " track's edit list is cut short");
                edits = new long[(int) count][];
                for (int i = 0; i < count; i++) {
                    long duration = wideEdits ? e.getLong() : u32(e);
                    long mediaTime = wideEdits ? e.getLong() : e.getInt();
                    edits[i] = new long[] {duration, mediaTime, e.getInt()};
                }
            }

            Box mdhd = find(media, MDHD);
            if (mdhd == null) throw new IOException("the " + kind + " track has no media header");
            ByteBuffer m = mdhd.data;
            boolean wideMedia = m.get(0) == 1;
            m.position(wideMedia ? 20 : 12);
            timescale = u32(m);
            m.position(m.position() + (wideMedia ? 8 : 4));
            language = m.getShort() & 0x7FFF;

            Box minf = find(media, MINF);
            Box stbl = minf == null ? null : find(children(minf.data), STBL);
            if (stbl == null) throw new IOException("the " + kind + " track has no sample table");
            List<Box> tables = children(stbl.data);
            Box stsd = find(tables, STSD);
            if (stsd == null) throw new IOException("the " + kind + " track has no sample description");
            descriptions = stsd.data;
            descriptionCount = descriptions.getInt(4);
            List<Box> entries = children(slice(descriptions, 8, descriptions.limit()));
            if (descriptionCount < 1 || entries.size() < descriptionCount) {
                throw new IOException("the " + kind + " track's sample descriptions are cut short");
            }
            for (Box entry : entries) {
                if (entry.type == ENCV || entry.type == ENCA) throw new IOException("the " + kind + " track is encrypted");
            }
            Box stsz = find(tables, STSZ);
            Box stts = find(tables, STTS);
            if ((stsz != null && stsz.data.getInt(8) != 0) || (stts != null && stts.data.getInt(4) != 0)) {
                listedTables = tables;
            }
        }

        /**
         * Reads the samples a plain file's [tables] list: sizes from stsz, durations from stts,
         * composition offsets from ctts, sync samples from stss, and the chunks they lie in from
         * stsc with stco or co64. Each chunk becomes a run, as a fragment's trun does.
         */
        private void plain(List<Box> tables) throws IOException {
            Box stsz = find(tables, STSZ);
            Box stts = find(tables, STTS);
            Box stsc = find(tables, STSC);
            Box stco = find(tables, STCO);
            Box co64 = find(tables, CO64);
            if (stsz == null || stts == null || stsc == null || (stco == null && co64 == null)) {
                throw new IOException("the " + kind + " track's sample tables are incomplete");
            }

            ByteBuffer z = stsz.data;
            z.position(4);
            long fixed = u32(z);
            long count = u32(z);
            if (count > MAX_SAMPLES) throw new IOException("the " + kind + " file lists too many samples");
            if (fixed == 0 && count > z.remaining() / 4) throw cutShort("size");
            for (long i = 0; i < count; i++) {
                long size = fixed != 0 ? fixed : u32(z);
                if (size > Integer.MAX_VALUE) throw new IOException("a sample of the " + kind + " file is over 2 GB");
                sizes.add((int) size);
            }

            ByteBuffer t = stts.data;
            t.position(4);
            long entries = u32(t);
            if (entries > t.remaining() / 8) throw cutShort("time");
            for (long e = 0; e < entries; e++) {
                long samples = u32(t);
                int duration = t.getInt();
                if (samples > count - durations.size) throw disagree();
                for (long i = 0; i < samples; i++) {
                    durations.add(duration);
                    decodeTime += duration & 0xFFFFFFFFL;
                }
            }
            if (durations.size != count) throw disagree();
            firstDecodeTime = 0;

            Box ctts = find(tables, CTTS);
            if (ctts != null) {
                ByteBuffer c = ctts.data;
                c.position(4);
                entries = u32(c);
                if (entries > c.remaining() / 8) throw cutShort("composition offset");
                for (long e = 0; e < entries; e++) {
                    long samples = u32(c);
                    // Version 1 offsets are signed. Version 0 ones are unsigned, but none over 2^31 is real.
                    int offset = c.getInt();
                    if (samples > count - offsets.size) throw disagree();
                    for (long i = 0; i < samples; i++) offsets.add(offset);
                }
            }
            while (offsets.size < count) offsets.add(0);

            // With no stss every sample is a sync sample, as every AAC frame is.
            Box stss = find(tables, STSS);
            if (stss != null) {
                ByteBuffer s = stss.data;
                s.position(4);
                entries = u32(s);
                if (entries > s.remaining() / 4) throw cutShort("sync sample");
                nonSync.set(0, (int) count);
                for (long e = 0; e < entries; e++) {
                    long sample = u32(s);
                    if (sample < 1 || sample > count) throw disagree();
                    nonSync.clear((int) sample - 1);
                }
            }

            boolean wide = co64 != null;
            ByteBuffer o = (wide ? co64 : stco).data;
            long chunks = o.getInt(4) & 0xFFFFFFFFL;
            if (chunks > MAX_SAMPLES || chunks > (o.limit() - 8) / (wide ? 8 : 4)) throw cutShort("chunk offset");
            ByteBuffer c = stsc.data;
            entries = c.getInt(4) & 0xFFFFFFFFL;
            if (entries > (c.limit() - 8) / 12) throw cutShort("sample-to-chunk");
            int sample = 0;
            for (int r = 0; r < entries; r++) {
                int rule = 8 + r * 12;
                long first = c.getInt(rule) & 0xFFFFFFFFL;
                long perChunk = c.getInt(rule + 4) & 0xFFFFFFFFL;
                long description = c.getInt(rule + 8) & 0xFFFFFFFFL;
                long last = r + 1 < entries ? (c.getInt(rule + 12) & 0xFFFFFFFFL) - 1 : chunks;
                if (first < 1 || last < first || last > chunks) throw disagree();
                if (description < 1 || description > descriptionCount) {
                    throw new IOException("a chunk of the " + kind + " file names a sample description it hasn't got");
                }
                for (long chunk = first; chunk <= last; chunk++) {
                    if (perChunk > count - sample) throw disagree();
                    if (perChunk == 0) continue;
                    int at = (int) (8 + (chunk - 1) * (wide ? 8 : 4));
                    long start = wide ? o.getLong(at) : o.getInt(at) & 0xFFFFFFFFL;
                    if (start < 0) throw new IOException("a chunk of the " + kind + " file has an unsupported data offset");
                    sample += (int) perChunk;
                    addRun(start, sample, (int) description);
                }
            }
            if (sample != count) throw disagree();
        }

        private IOException cutShort(String table) {
            return new IOException("the " + kind + " track's " + table + " table is cut short");
        }

        private IOException disagree() {
            return new IOException("the " + kind + " track's sample tables disagree on its samples");
        }

        /** Notes a run of samples whose data lie together from [start], up to sample [end]. */
        private void addRun(long start, int end, int description) {
            int runs = runEnds.size;
            if (runs == runStarts.length) runStarts = Arrays.copyOf(runStarts, runs * 2);
            runStarts[runs] = start;
            runEnds.add(end);
            runDescriptions.add(description);
        }

        /** Reads the samples of this track in [moof], which starts at [moofStart] of a file of [length]. */
        private void fragment(ByteBuffer moof, long moofStart, long length) throws IOException {
            boolean firstTraf = true;
            for (Box traf : children(moof)) {
                if (traf.type != TRAF) continue;
                boolean first = firstTraf;
                firstTraf = false;
                List<Box> parts = children(traf.data);
                Box tfhd = find(parts, TFHD);
                if (tfhd == null) throw new IOException("a fragment of the " + kind + " file has no header");
                ByteBuffer h = tfhd.data;
                int flags = h.getInt() & 0xFFFFFF;
                if (u32(h) != trackId) continue;

                long base;
                if ((flags & 0x1) != 0) {
                    base = h.getLong();
                } else if ((flags & 0x020000) != 0 || first) {
                    base = moofStart;
                } else {
                    throw new IOException("a fragment of the " + kind + " file puts its data after another track's");
                }
                // tfhd stores an unsigned 64-bit base. A signed-negative representation cannot
                // name a position in this RandomAccessFile and must not wrap into a small offset.
                if (base < 0) throw new IOException("a fragment of the " + kind + " file has an unsupported data offset");
                int description = (flags & 0x2) != 0 ? h.getInt() : defaultDescription;
                int duration = (flags & 0x8) != 0 ? h.getInt() : defaultDuration;
                int size = (flags & 0x10) != 0 ? h.getInt() : defaultSize;
                int sampleFlags = (flags & 0x20) != 0 ? h.getInt() : defaultFlags;
                if (description < 1 || description > descriptionCount) {
                    throw new IOException("a fragment of the " + kind + " file names a sample description it hasn't got");
                }

                Box tfdt = find(parts, TFDT);
                if (tfdt != null) {
                    ByteBuffer t = tfdt.data;
                    boolean wide = t.get(0) == 1;
                    t.position(4);
                    startAt(wide ? t.getLong() : u32(t));
                } else if (firstDecodeTime < 0) {
                    startAt(0);
                }

                long next = base;
                for (Box trun : parts) {
                    if (trun.type == TRUN) next = run(trun.data, base, next, description, duration, size, sampleFlags, length);
                }
            }
        }

        /** Reads one run, whose data follows [next] unless it names an offset from [base]. Answers where its data ends. */
        private long run(ByteBuffer r, long base, long next, int description, int duration, int size, int sampleFlags,
                long length) throws IOException {
            int flags = r.getInt() & 0xFFFFFF;
            long count = u32(r);
            long start = next;
            if ((flags & 0x1) != 0) {
                try {
                    start = Math.addExact(base, r.getInt());
                } catch (ArithmeticException e) {
                    throw new IOException("a fragment's data offset overflows in the " + kind + " file", e);
                }
            }
            boolean hasFirstFlags = (flags & 0x4) != 0;
            int firstFlags = hasFirstFlags ? r.getInt() : 0;
            int fields = Integer.bitCount(flags & 0xF00);
            if (fields > 0 && count > r.remaining() / (4L * fields)) {
                throw new IOException("a run of the " + kind + " file lists more samples than it holds");
            }
            if (sizes.size + count > MAX_SAMPLES) throw new IOException("the " + kind + " file lists too many samples");
            if (count == 0) return start;

            long bytes = 0;
            for (long i = 0; i < count; i++) {
                int sampleDuration = (flags & 0x100) != 0 ? r.getInt() : duration;
                int sampleSize = (flags & 0x200) != 0 ? r.getInt() : size;
                int flagsOf = (flags & 0x400) != 0 ? r.getInt() : (i == 0 && hasFirstFlags ? firstFlags : sampleFlags);
                // Version 1 offsets are signed. Version 0 ones are unsigned, but none over 2^31 is real.
                int offset = (flags & 0x800) != 0 ? r.getInt() : 0;
                if (sampleSize < 0) throw new IOException("a sample of the " + kind + " file is over 2 GB");
                if ((flagsOf & SAMPLE_IS_NON_SYNC) != 0) nonSync.set(sizes.size);
                sizes.add(sampleSize);
                durations.add(sampleDuration);
                offsets.add(offset);
                decodeTime += sampleDuration & 0xFFFFFFFFL;
                bytes += sampleSize;
            }
            if (start < 0 || start > length || bytes > length - start) {
                throw new IOException("a fragment's samples run past the end of the " + kind + " file");
            }
            addRun(start, sizes.size, description);
            return start + bytes;
        }

        /**
         * A fragment's samples start at decode time [time]. The first fixes where the track starts.
         * A later one that doesn't follow on from the samples before it moves the last of them to
         * end where it starts, so every sample keeps its decode time: a plain file has no gaps.
         */
        private void startAt(long time) {
            if (firstDecodeTime < 0 || durations.size == 0) {
                firstDecodeTime = time;
                decodeTime = time;
                return;
            }
            if (time == decodeTime) return;
            int last = durations.size - 1;
            long lastDuration = durations.get(last) & 0xFFFFFFFFL;
            long moved = Math.max(0, Math.min(0xFFFFFFFFL, lastDuration + time - decodeTime));
            durations.set(last, (int) moved);
            decodeTime += moved - lastDuration;
        }

        int count() {
            return sizes.size;
        }
    }

    // ---------------------------------------------------------------- timing

    /** What the output says of a track's time, worked out from its samples and its edit list. */
    private static final class Timing {
        /** Added to every composition offset, so none is negative. */
        long shift;
        /** The decode time the plain file's samples end at. */
        long mediaDuration;
        /** Each edit as {duration in milliseconds, media time, rate}, or null for none. */
        long[][] edits;
        /** The track's length in milliseconds. */
        long duration;

        Timing(Track track) throws IOException {
            long lowest = 0;
            for (int i = 0; i < track.count(); i++) lowest = Math.min(lowest, track.offsets.get(i));
            shift = -lowest;

            // Where the last sample shown ends, in the plain file's media time.
            long end = 0;
            long decode = 0;
            for (int i = 0; i < track.count(); i++) {
                long length = track.durations.get(i) & 0xFFFFFFFFL;
                end = Math.max(end, decode + track.offsets.get(i) + shift + length);
                decode += length;
            }
            mediaDuration = decode;

            long[][] input = track.edits != null && track.edits.length > 0 ? track.edits : new long[][] {{0, 0, 0x00010000}};
            List<long[]> output = new ArrayList<>();
            for (long[] edit : input) {
                long segment = edit[0];
                if (edit[1] == -1) {
                    if (segment > 0) output.add(new long[] {scale(segment, track.movieTimescale), -1, 0x00010000});
                    continue;
                }
                // The plain file's samples start at zero, and at the shift in composition time.
                long media = edit[1] - track.firstDecodeTime + shift;
                long shown = segment == 0 ? -1 : scale(segment, track.movieTimescale);
                if (media < 0) {
                    // The edit starts before the first sample: that stretch shows nothing.
                    long lead = scale(-media, track.timescale);
                    output.add(new long[] {lead, -1, 0x00010000});
                    if (shown >= 0) shown = Math.max(0, shown - lead);
                    media = 0;
                }
                long available = scale(Math.max(0, end - media), track.timescale);
                shown = shown < 0 ? available : Math.min(shown, available);
                if (shown > 0) output.add(new long[] {shown, media, edit[2]});
            }
            if (output.isEmpty()) throw new IOException("the " + track.kind + " track's edit list shows nothing");

            long total = 0;
            for (long[] edit : output) total += edit[0];
            long[] only = output.get(0);
            boolean identity = output.size() == 1 && only[1] == 0 && only[2] == 0x00010000
                && only[0] == scale(end, track.timescale);
            edits = identity ? null : output.toArray(new long[0][]);
            duration = identity ? scale(mediaDuration, track.timescale) : total;
        }

        /** [value] in [timescale] units as milliseconds, rounded. */
        private static long scale(long value, long timescale) {
            return value / timescale * MOVIE_TIMESCALE + (value % timescale * MOVIE_TIMESCALE + timescale / 2) / timescale;
        }
    }

    // ---------------------------------------------------------------- writing the plain file

    /** A stretch of one track's samples that lie together in its file and go out together. */
    private static final class Chunk {
        final Track track;
        final int first;
        final int count;
        final long from;
        final long bytes;
        final int description;
        long at;

        Chunk(Track track, int first, int count, long from, long bytes, int description) {
            this.track = track;
            this.first = first;
            this.count = count;
            this.from = from;
            this.bytes = bytes;
            this.description = description;
        }
    }

    private static boolean write(List<Track> tracks, File out, Downloader.Progress progress) throws IOException {
        List<Chunk> chunks = interleave(tracks);
        long data = 0;
        for (Chunk chunk : chunks) {
            chunk.at = data;
            data += chunk.bytes;
        }
        Timing[] timing = new Timing[tracks.size()];
        for (int i = 0; i < timing.length; i++) timing[i] = new Timing(tracks.get(i));

        byte[] ftyp = ftyp();
        boolean wide = data + (64L << 20) > 0xFFFFFFFFL;
        int mdatHeader = data + 8 > 0xFFFFFFFFL ? 16 : 8;
        long dataStart = ftyp.length + moov(tracks, timing, 0, wide).length + mdatHeader;
        byte[] moov = moov(tracks, timing, dataStart, wide);

        try (FileOutputStream stream = new FileOutputStream(out)) {
            FileChannel channel = stream.getChannel();
            writeFully(channel, ByteBuffer.wrap(ftyp));
            writeFully(channel, ByteBuffer.wrap(moov));
            ByteBuffer header = ByteBuffer.allocate(mdatHeader);
            if (mdatHeader == 16) {
                header.putInt(1).putInt(MDAT).putLong(data + 16);
            } else {
                header.putInt((int) (data + 8)).putInt(MDAT);
            }
            header.flip();
            writeFully(channel, header);
            for (Chunk chunk : chunks) {
                if (progress.cancelled()) return false;
                FileChannel from = chunk.track.file.getChannel();
                long done = 0;
                while (done < chunk.bytes) {
                    long moved = from.transferTo(chunk.from + done, chunk.bytes - done, channel);
                    if (moved <= 0) throw new IOException("the " + chunk.track.kind + " file ended before its samples did");
                    done += moved;
                }
            }
        }
        return true;
    }

    private static void writeFully(FileChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) channel.write(bytes);
    }

    /**
     * The order the samples go out in: each time a run of the track whose next sample comes first,
     * up to {@link #CHUNK_SECONDS} of it and never past the end of the run its data lie in.
     */
    private static List<Chunk> interleave(List<Track> tracks) {
        int n = tracks.size();
        int[] next = new int[n];
        int[] run = new int[n];
        long[] within = new long[n];
        long[] time = new long[n];
        List<Chunk> chunks = new ArrayList<>();
        while (true) {
            int pick = -1;
            double earliest = 0;
            for (int t = 0; t < n; t++) {
                Track track = tracks.get(t);
                if (next[t] >= track.count()) continue;
                double at = (track.firstDecodeTime + time[t]) / (double) track.timescale;
                if (pick < 0 || at < earliest) {
                    pick = t;
                    earliest = at;
                }
            }
            if (pick < 0) return chunks;

            Track track = tracks.get(pick);
            int first = next[pick];
            int end = track.runEnds.get(run[pick]);
            long limit = time[pick] + (long) (CHUNK_SECONDS * track.timescale);
            long bytes = 0;
            int i = first;
            while (i < end && (i == first || time[pick] < limit)) {
                bytes += track.sizes.get(i);
                time[pick] += track.durations.get(i) & 0xFFFFFFFFL;
                i++;
            }
            Chunk chunk = new Chunk(track, first, i - first, track.runStarts[run[pick]] + within[pick], bytes,
                track.runDescriptions.get(run[pick]));
            chunks.add(chunk);
            track.chunks.add(chunk);
            next[pick] = i;
            if (i == end) {
                run[pick]++;
                within[pick] = 0;
            } else {
                within[pick] += bytes;
            }
        }
    }

    private static byte[] ftyp() {
        Boxes out = new Boxes();
        out.begin(FTYP).u32(type("isom")).u32(0x200).u32(type("isom")).u32(type("iso2")).u32(type("mp41")).end();
        return out.bytes();
    }

    /** The moov, its chunk offsets counted from [dataStart], 64-bit when [wide]. */
    private static byte[] moov(List<Track> tracks, Timing[] timing, long dataStart, boolean wide) {
        long duration = 0;
        for (Timing t : timing) duration = Math.max(duration, t.duration);

        Boxes out = new Boxes();
        out.begin(MOOV);
        boolean wideMovie = duration > 0xFFFFFFFFL;
        out.full(MVHD, wideMovie ? 1 : 0, 0);
        times(out, wideMovie).u32(MOVIE_TIMESCALE);
        length(out, wideMovie, duration);
        out.u32(0x00010000).u16(0x0100).zeros(10);
        for (int value : IDENTITY) out.u32(value);
        out.zeros(24).u32(tracks.size() + 1).end();

        for (int t = 0; t < tracks.size(); t++) trak(out, tracks.get(t), timing[t], t + 1, dataStart, wide);
        return out.end().bytes();
    }

    private static void trak(Boxes out, Track track, Timing timing, int id, long dataStart, boolean wide) {
        out.begin(TRAK);

        boolean wideTrack = timing.duration > 0xFFFFFFFFL;
        out.full(TKHD, wideTrack ? 1 : 0, 0x3);
        times(out, wideTrack).u32(id).u32(0);
        length(out, wideTrack, timing.duration);
        out.zeros(8).u16(0).u16(0).u16(track.handler == SOUN ? 0x0100 : 0).u16(0);
        if (track.matrix != null) {
            out.bytes(track.matrix);
        } else {
            for (int value : IDENTITY) out.u32(value);
        }
        out.u32(track.width & 0xFFFFFFFFL).u32(track.height & 0xFFFFFFFFL).end();

        if (timing.edits != null) {
            boolean wideEdits = false;
            for (long[] edit : timing.edits) {
                wideEdits |= edit[0] > 0xFFFFFFFFL || edit[1] > Integer.MAX_VALUE;
            }
            out.begin(EDTS).full(ELST, wideEdits ? 1 : 0, 0).u32(timing.edits.length);
            for (long[] edit : timing.edits) {
                if (wideEdits) out.u64(edit[0]).u64(edit[1]);
                else out.u32(edit[0]).u32(edit[1] & 0xFFFFFFFFL);
                out.u32(edit[2] & 0xFFFFFFFFL);
            }
            out.end().end();
        }

        out.begin(MDIA);
        boolean wideMedia = timing.mediaDuration > 0xFFFFFFFFL;
        out.full(MDHD, wideMedia ? 1 : 0, 0);
        times(out, wideMedia).u32(track.timescale);
        length(out, wideMedia, timing.mediaDuration);
        out.u16(track.language).u16(0).end();
        String name = track.handler == VIDE ? "VideoHandler" : track.handler == SOUN ? "SoundHandler" : "DataHandler";
        out.full(HDLR, 0, 0).u32(0).u32(track.handler).zeros(12).ascii(name).u8(0).end();

        out.begin(MINF);
        if (track.handler == VIDE) out.full(VMHD, 0, 1).zeros(8).end();
        else if (track.handler == SOUN) out.full(SMHD, 0, 0).u32(0).end();
        else out.full(NMHD, 0, 0).end();
        out.begin(DINF).full(DREF, 0, 0).u32(1).full(URL, 0, 1).end().end().end();
        stbl(out, track, timing, dataStart, wide);
        out.end().end().end();
    }

    private static void stbl(Boxes out, Track track, Timing timing, long dataStart, boolean wide) {
        int count = track.count();
        out.begin(STBL);
        out.begin(STSD).bytes(track.descriptions).end();

        Ints stts = new Ints();
        for (int i = 0; i < count; i++) runLength(stts, track.durations.get(i));
        out.full(STTS, 0, 0).u32(stts.size / 2).ints(stts).end();

        boolean composed = timing.shift != 0;
        for (int i = 0; i < count && !composed; i++) composed = track.offsets.get(i) != 0;
        if (composed) {
            Ints ctts = new Ints();
            for (int i = 0; i < count; i++) runLength(ctts, (int) (track.offsets.get(i) + timing.shift));
            out.full(CTTS, 0, 0).u32(ctts.size / 2).ints(ctts).end();
        }

        if (!track.nonSync.isEmpty()) {
            out.full(STSS, 0, 0).u32(count - track.nonSync.cardinality());
            for (int i = track.nonSync.nextClearBit(0); i < count; i = track.nonSync.nextClearBit(i + 1)) out.u32(i + 1);
            out.end();
        }

        Ints stsc = new Ints();
        for (int c = 0; c < track.chunks.size(); c++) {
            Chunk chunk = track.chunks.get(c);
            int entries = stsc.size / 3;
            if (entries > 0 && stsc.get(stsc.size - 2) == chunk.count && stsc.get(stsc.size - 1) == chunk.description) continue;
            stsc.add(c + 1);
            stsc.add(chunk.count);
            stsc.add(chunk.description);
        }
        out.full(STSC, 0, 0).u32(stsc.size / 3).ints(stsc).end();

        boolean uniform = true;
        for (int i = 1; i < count && uniform; i++) uniform = track.sizes.get(i) == track.sizes.get(0);
        out.full(STSZ, 0, 0);
        if (uniform) {
            out.u32(track.sizes.get(0)).u32(count);
        } else {
            out.u32(0).u32(count).ints(track.sizes);
        }
        out.end();

        out.full(wide ? CO64 : STCO, 0, 0).u32(track.chunks.size());
        for (Chunk chunk : track.chunks) {
            if (wide) out.u64(dataStart + chunk.at);
            else out.u32(dataStart + chunk.at);
        }
        out.end();

        out.end();
    }

    /** Adds [value] to [table], pairs of a count and a value, as a run of the last pair where it can. */
    private static void runLength(Ints table, int value) {
        if (table.size > 0 && table.get(table.size - 1) == value) {
            table.set(table.size - 2, table.get(table.size - 2) + 1);
        } else {
            table.add(1);
            table.add(value);
        }
    }

    /** A header's creation and modification times: none, as MediaMuxer writes them. */
    private static Boxes times(Boxes out, boolean wide) {
        return wide ? out.u64(0).u64(0) : out.u32(0).u32(0);
    }

    private static void length(Boxes out, boolean wide, long value) {
        if (wide) out.u64(value);
        else out.u32(value);
    }

    // ---------------------------------------------------------------- boxes

    /** A box: its type and its payload. */
    private static final class Box {
        final int type;
        final ByteBuffer data;

        Box(int type, ByteBuffer data) {
            this.type = type;
            this.data = data;
        }
    }

    /** The boxes inside [parent], from its start to its limit. */
    private static List<Box> children(ByteBuffer parent) throws IOException {
        List<Box> boxes = new ArrayList<>();
        int at = 0;
        int end = parent.limit();
        while (end - at >= 8) {
            long size = parent.getInt(at) & 0xFFFFFFFFL;
            int type = parent.getInt(at + 4);
            int header = 8;
            if (size == 1) {
                if (end - at < 16) throw new IOException("a box header is cut short");
                size = parent.getLong(at + 8);
                header = 16;
            } else if (size == 0) {
                size = end - at;
            }
            if (size < header || size > end - at) throw new IOException("a box runs past the box that holds it");
            boxes.add(new Box(type, slice(parent, at + header, at + (int) size)));
            at += (int) size;
        }
        return boxes;
    }

    private static Box find(List<Box> boxes, int type) {
        for (Box box : boxes) {
            if (box.type == type) return box;
        }
        return null;
    }

    /** [buffer]'s bytes from [from] to [to], as a buffer of their own that starts at zero. */
    private static ByteBuffer slice(ByteBuffer buffer, int from, int to) {
        ByteBuffer copy = buffer.duplicate();
        copy.limit(to);
        copy.position(from);
        return copy.slice();
    }

    private static long u32(ByteBuffer buffer) {
        return buffer.getInt() & 0xFFFFFFFFL;
    }

    private static int type(String name) {
        return (name.charAt(0) << 24) | (name.charAt(1) << 16) | (name.charAt(2) << 8) | name.charAt(3);
    }

    /** Big-endian bytes, with boxes whose sizes are filled in as they end. */
    private static final class Boxes {
        private byte[] bytes = new byte[4096];
        private int size;
        private final int[] open = new int[16];
        private int depth;

        Boxes begin(int type) {
            open[depth++] = size;
            return u32(0).u32(type);
        }

        Boxes full(int type, int version, int flags) {
            return begin(type).u32(((long) version << 24) | flags);
        }

        Boxes end() {
            int start = open[--depth];
            ByteBuffer.wrap(bytes).putInt(start, size - start);
            return this;
        }

        private void room(int more) {
            if (size + more > bytes.length) bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, size + more));
        }

        Boxes u8(int value) {
            room(1);
            bytes[size++] = (byte) value;
            return this;
        }

        Boxes u16(int value) {
            return u8(value >> 8).u8(value);
        }

        Boxes u32(long value) {
            room(4);
            ByteBuffer.wrap(bytes).putInt(size, (int) value);
            size += 4;
            return this;
        }

        Boxes u64(long value) {
            return u32(value >>> 32).u32(value);
        }

        Boxes zeros(int count) {
            room(count);
            size += count;
            return this;
        }

        Boxes ascii(String text) {
            for (int i = 0; i < text.length(); i++) u8(text.charAt(i));
            return this;
        }

        Boxes bytes(byte[] more) {
            room(more.length);
            System.arraycopy(more, 0, bytes, size, more.length);
            size += more.length;
            return this;
        }

        Boxes bytes(ByteBuffer more) {
            ByteBuffer copy = more.duplicate();
            copy.position(0);
            int length = copy.remaining();
            room(length);
            copy.get(bytes, size, length);
            size += length;
            return this;
        }

        Boxes ints(Ints values) {
            room(values.size * 4);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            for (int i = 0; i < values.size; i++) buffer.putInt(size + i * 4, values.get(i));
            size += values.size * 4;
            return this;
        }

        byte[] bytes() {
            return Arrays.copyOf(bytes, size);
        }
    }

    /** A growing list of ints, without boxing each one. */
    private static final class Ints {
        private int[] values = new int[256];
        int size;

        void add(int value) {
            if (size == values.length) values = Arrays.copyOf(values, size * 2);
            values[size++] = value;
        }

        int get(int index) {
            return values[index];
        }

        void set(int index, int value) {
            values[index] = value;
        }
    }
}
