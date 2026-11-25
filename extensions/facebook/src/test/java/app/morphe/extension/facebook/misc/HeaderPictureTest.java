/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Home header picture (#230): a picked picture is shrunk to the header's size and kept as a PNG,
 * a file that isn't a picture or is too large is turned down with the old copy kept, and the header
 * reads the copy once and nothing while paused or when it's gone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HeaderPictureTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HEADER_PICTURE.resetToDefault();
        HeaderPicture.forget();
        File copy = copy();
        if (copy.exists()) assertTrue(copy.delete());
    }

    private static File copy() {
        return HeaderPicture.file(RuntimeEnvironment.getApplication());
    }

    private static File partial() {
        return new File(copy().getParentFile(), HeaderPicture.PARTIAL);
    }

    /** A [width] by [height] picture all [color], as the bytes of a [format] file. */
    public static byte[] picture(int width, int height, int color, Bitmap.CompressFormat format) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(format, 100, bytes));
        return bytes.toByteArray();
    }

    /** A Choice that saves the name and counts what it was asked. */
    private static final class Counted implements HeaderPicture.Choice {
        final AtomicInteger saves = new AtomicInteger();
        final AtomicInteger undos = new AtomicInteger();
        final boolean answer;

        Counted(boolean answer) {
            this.answer = answer;
        }

        @Override
        public boolean save() {
            saves.incrementAndGet();
            return answer;
        }

        @Override
        public void undo() {
            undos.incrementAndGet();
        }
    }

    @Test
    public void aLargePictureIsShrunkToFitTheHeaderAndKeptAsAPng() throws Exception {
        Counted choice = new Counted(true);
        HeaderPicture.copy(new ByteArrayInputStream(picture(2048, 1024, Color.RED, Bitmap.CompressFormat.PNG)),
                copy(), choice);

        assertEquals("the name was saved once", 1, choice.saves.get());
        Bitmap kept = BitmapFactory.decodeFile(copy().getPath());
        assertNotNull("the copy decodes", kept);
        assertEquals("shrunk to the height limit with its shape kept", 512, kept.getWidth());
        assertEquals(HeaderPicture.MAX_HEIGHT, kept.getHeight());
        assertEquals(Color.RED, kept.getPixel(10, 10));
        assertFalse("no partial file is left", partial().exists());
    }

    @Test
    public void aSmallJpegKeepsItsSizeAndBecomesAPng() throws Exception {
        HeaderPicture.copy(new ByteArrayInputStream(picture(100, 40, Color.BLUE, Bitmap.CompressFormat.JPEG)),
                copy(), new Counted(true));

        byte[] head = new byte[4];
        System.arraycopy(Files.readAllBytes(copy().toPath()), 0, head, 0, 4);
        assertArrayEquals("a PNG file", new byte[]{(byte) 0x89, 'P', 'N', 'G'}, head);
        Bitmap kept = BitmapFactory.decodeFile(copy().getPath());
        assertEquals("never made larger", 100, kept.getWidth());
        assertEquals(40, kept.getHeight());
    }

    @Test
    public void aFileThatIsntAPictureIsTurnedDownAndTheOldCopyStays() throws Exception {
        byte[] before = picture(30, 10, Color.GREEN, Bitmap.CompressFormat.PNG);
        Files.write(copy().toPath(), before);
        Counted choice = new Counted(true);

        assertRefused(HeaderPicture.Refusal.NOT_A_PICTURE,
                new ByteArrayInputStream("not a picture".getBytes(StandardCharsets.UTF_8)), choice);
        assertRefused(HeaderPicture.Refusal.NOT_A_PICTURE, new ByteArrayInputStream(new byte[0]), choice);
        assertRefused(HeaderPicture.Refusal.UNREADABLE, null, choice);

        assertEquals("no name was saved", 0, choice.saves.get());
        assertArrayEquals("the copy picked before is there", before, Files.readAllBytes(copy().toPath()));
        assertFalse("no partial file is left", partial().exists());
    }

    @Test
    public void aFileOverTheLimitIsTurnedDownWithoutReadingItAll() {
        AtomicInteger read = new AtomicInteger();
        InputStream endless = new InputStream() {
            @Override
            public int read() {
                read.incrementAndGet();
                return 0;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                read.addAndGet(length);
                return length;
            }
        };
        assertRefused(HeaderPicture.Refusal.TOO_LARGE, endless, new Counted(true));
        assertTrue("it stopped soon after the limit: " + read.get(),
                read.get() <= HeaderPicture.MAX_BYTES + 64 * 1024);
        assertFalse(copy().exists());
    }

    @Test
    public void aNameThatWontSaveLeavesTheOldCopy() throws Exception {
        byte[] before = picture(30, 10, Color.GREEN, Bitmap.CompressFormat.PNG);
        Files.write(copy().toPath(), before);

        assertRefused(HeaderPicture.Refusal.NOT_SAVED,
                new ByteArrayInputStream(picture(60, 20, Color.RED, Bitmap.CompressFormat.PNG)), new Counted(false));

        assertArrayEquals(before, Files.readAllBytes(copy().toPath()));
        assertFalse(partial().exists());
    }

    @Test
    public void theHeaderReadsTheCopyOnceAndNothingPausedOrMissing() throws Exception {
        assertNull("nothing picked", HeaderPicture.load(RuntimeEnvironment.getApplication()));
        HeaderPicture.copy(new ByteArrayInputStream(picture(60, 20, Color.RED, Bitmap.CompressFormat.PNG)),
                copy(), new Counted(true));
        assertNull("a copy no name points at isn't shown", HeaderPicture.load(RuntimeEnvironment.getApplication()));
        Settings.HEADER_PICTURE.save("logo.png");

        Bitmap first = HeaderPicture.load(RuntimeEnvironment.getApplication());
        assertNotNull(first);
        assertEquals(60, first.getWidth());
        assertSame("read once", first, HeaderPicture.load(RuntimeEnvironment.getApplication()));

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertNull("paused", HeaderPicture.load(RuntimeEnvironment.getApplication()));
        PauseForTests.resume();

        assertTrue(copy().delete());
        assertNull("the copy is gone", HeaderPicture.load(RuntimeEnvironment.getApplication()));

        Files.write(copy().toPath(), "broken".getBytes(StandardCharsets.UTF_8));
        assertNull("a copy that won't decode", HeaderPicture.load(RuntimeEnvironment.getApplication()));
    }

    @Test
    public void theKeptSizeFitsTheLimitsAndNeverGrows() {
        assertArrayEquals(new int[]{1, 1}, HeaderPicture.fit(1, 1));
        assertArrayEquals(new int[]{300, 100}, HeaderPicture.fit(300, 100));
        assertArrayEquals("held to the width", new int[]{1024, 2}, HeaderPicture.fit(5000, 10));
        assertArrayEquals("never below a pixel", new int[]{1, 256}, HeaderPicture.fit(10, 5000));
    }

    private static void assertRefused(HeaderPicture.Refusal reason, InputStream input, HeaderPicture.Choice choice) {
        try {
            HeaderPicture.copy(input, copy(), choice);
            fail("taken, but should be " + reason);
        } catch (HeaderPicture.Refused refused) {
            assertEquals(reason, refused.reason);
        }
    }
}
