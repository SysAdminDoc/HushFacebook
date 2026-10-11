/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.media.MediaExtractor;
import android.media.MediaFormat;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowMediaCodec;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The re-encode's loop against Robolectric's codecs: every block of sound the decoder gives goes
 * through the encoder into the new file, a cancel stops it, and a decoder that gives no sound makes
 * no file rather than a silent one. What a real phone's codecs make of xHE-AAC is checked on the
 * phone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, shadows = {AacReencodeTest.SoundFile.class})
public class AacReencodeTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    /** Samples the sound file holds, each 370 bytes of xHE-AAC, 21.3 ms apart as at 48 kHz. */
    static final int SAMPLES = 40;

    /** Blocks of PCM the decoder made, and blocks of AAC the encoder made. */
    static final AtomicInteger decoded = new AtomicInteger();
    static final AtomicInteger encoded = new AtomicInteger();

    /** A sound file of [SAMPLES] samples, one xHE-AAC track at [sampleRate] stereo. */
    @Implements(MediaExtractor.class)
    public static class SoundFile {
        /** What the track says its rate is; the shadow decoder's sound comes out at the same rate. */
        static int sampleRate = 48_000;

        private boolean selected;
        private int at;

        @Implementation
        protected void setDataSource(String path) throws IOException {
            if (!new File(path).getName().startsWith("audio")) throw new IOException("not a sound file: " + path);
        }

        @Implementation
        protected int getTrackCount() {
            return 1;
        }

        @Implementation
        protected MediaFormat getTrackFormat(int index) {
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, AacReencode.XHE_OBJECT_TYPE);
            return format;
        }

        @Implementation
        protected void selectTrack(int index) {
            selected = true;
        }

        @Implementation
        protected int readSampleData(ByteBuffer buffer, int offset) {
            if (!selected || at >= SAMPLES) return -1;
            buffer.position(offset);
            buffer.put(new byte[370]);
            return 370;
        }

        @Implementation
        protected boolean advance() {
            at++;
            return at < SAMPLES;
        }

        @Implementation
        protected long getSampleTime() {
            return at >= SAMPLES ? -1 : at * 21_333L;
        }
    }

    private static final class Progress implements Downloader.Progress {
        int cancelAfter = Integer.MAX_VALUE;
        int asked;

        @Override
        public void transferred(long done, long total) {
        }

        @Override
        public void reading(Runnable close) {
        }

        @Override
        public boolean cancelled() {
            return ++asked > cancelAfter;
        }
    }

    @Before
    public void codecs() {
        decoded.set(0);
        encoded.set(0);
        SoundFile.sampleRate = 48_000;
        ShadowMediaCodec.clearCodecs();
        // 1,024 stereo 16-bit frames of PCM for each sample, as an AAC decoder gives.
        ShadowMediaCodec.addDecoder(MediaFormat.MIMETYPE_AUDIO_AAC, new ShadowMediaCodec.CodecConfig(4_096, 4_096,
                (in, out) -> {
                    // The shadow runs this for the empty end of stream too, which carries no sound.
                    if (!in.hasRemaining()) return;
                    in.position(in.limit());
                    out.put(new byte[4_096]);
                    decoded.incrementAndGet();
                }));
        // An encoder whose input buffer holds half a decoded block, so each block goes in two parts.
        ShadowMediaCodec.addEncoder(MediaFormat.MIMETYPE_AUDIO_AAC, new ShadowMediaCodec.CodecConfig(2_048, 512,
                (in, out) -> {
                    if (!in.hasRemaining()) return;
                    in.position(in.limit());
                    out.put(new byte[300]);
                    encoded.incrementAndGet();
                }));
    }

    @After
    public void noCodecs() {
        ShadowMediaCodec.clearCodecs();
    }

    private File sound() throws IOException {
        return folder.newFile("audio.mp4");
    }

    @Test
    public void everyBlockOfSoundGoesThroughTheEncoderIntoTheNewFile() throws IOException {
        File out = folder.newFile("sound.m4a");
        assertTrue(AacReencode.reencode(sound(), out, new Progress()));
        assertEquals("blocks decoded", SAMPLES, decoded.get());
        assertEquals("each block goes to the encoder in two parts", 2 * SAMPLES, encoded.get());
        assertTrue("the new file is empty", out.length() > 0);
    }

    @Test
    public void aCancelStopsTheReencode() throws IOException {
        Progress progress = new Progress();
        progress.cancelAfter = 5;
        assertFalse(AacReencode.reencode(sound(), folder.newFile("sound.m4a"), progress));
        assertTrue("it went on past the cancel", decoded.get() < SAMPLES);
    }

    /** A decoder that gives no sound at all makes no file: joined, it would be a silent save. */
    @Test
    public void aDecoderThatGivesNoSoundMakesNoFile() throws IOException {
        ShadowMediaCodec.clearCodecs();
        ShadowMediaCodec.addDecoder(MediaFormat.MIMETYPE_AUDIO_AAC, new ShadowMediaCodec.CodecConfig(4_096, 4_096,
                (in, out) -> in.position(in.limit())));
        ShadowMediaCodec.addEncoder(MediaFormat.MIMETYPE_AUDIO_AAC, new ShadowMediaCodec.CodecConfig(2_048, 512,
                (in, out) -> in.position(in.limit())));
        File sound = sound();
        File out = folder.newFile("sound.m4a");
        IOException silent = assertThrows(IOException.class, () -> AacReencode.reencode(sound, out, new Progress()));
        assertEquals("the re-encode made no sound", silent.getMessage());
    }

    /**
     * A decoder that names no sample rate can't have its sound timed. The re-encode says so, where it
     * used to divide by zero and leave the save with a bare "try again".
     */
    @Test
    public void aDecoderWithNoSampleRateFailsWithAReason() throws IOException {
        SoundFile.sampleRate = 0;
        File sound = sound();
        File out = folder.newFile("sound.m4a");
        IOException why = assertThrows(IOException.class, () -> AacReencode.reencode(sound, out, new Progress()));
        assertEquals("the decoder gave no sample rate or channel count", why.getMessage());
    }

    /** A later decoder format that repeats the first passes; one that differs ends the re-encode with a reason. */
    @Test
    public void aChangedDecoderFormatIsRefused() throws IOException {
        AacReencode.sameFormat(48_000, 2, 48_000, 4);
        IOException rate = assertThrows(IOException.class, () -> AacReencode.sameFormat(44_100, 2, 48_000, 4));
        assertEquals("the decoder changed its sound format", rate.getMessage());
        assertThrows(IOException.class, () -> AacReencode.sameFormat(48_000, 1, 48_000, 4));
    }

    @Test
    public void onlyXheAacIsReencoded() {
        assertTrue(AacReencode.isXhe("mp4a.40.42"));
        assertTrue(AacReencode.isXhe(" MP4A.40.42 "));
        assertFalse(AacReencode.isXhe("mp4a.40.2"));
        assertFalse(AacReencode.isXhe("mp4a.40.5"));
        assertFalse(AacReencode.isXhe("mp4a.40.420"));
        assertFalse(AacReencode.isXhe(null));
    }
}
