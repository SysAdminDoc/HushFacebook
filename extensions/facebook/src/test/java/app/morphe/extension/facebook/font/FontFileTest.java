/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.font;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What a picked font has to be before its copy is kept, and what its fvar table says. The fonts
 * are Android's own, as Robolectric's native runtime ships them: a static TrueType font, a
 * variable one with a 'wght' axis and a collection.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FontFileTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    public static final String STATIC_FONT = "fonts/Rubik-Regular.ttf";
    public static final String VARIABLE_FONT = "fonts/NotoSansKhmer-VF.ttf";
    public static final String COLLECTION = "fonts/NotoSansCJK-Regular.ttc";

    /** A font Robolectric's native runtime ships, as bytes. */
    public static byte[] font(String resource) throws IOException {
        try (InputStream input = FontFileTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(resource + " isn't on the test classpath", input);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return bytes.toByteArray();
        }
    }

    /** A stream that counts what was read from it and whether it was closed. */
    static final class Watched extends InputStream {
        private final InputStream inner;
        long read;
        boolean closed;

        Watched(InputStream inner) {
            this.inner = inner;
        }

        @Override
        public int read() throws IOException {
            int value = inner.read();
            if (value >= 0) read++;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = inner.read(buffer, offset, length);
            if (count > 0) read += count;
            return count;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            inner.close();
        }
    }

    /** [length] bytes that start with a TrueType tag and are zeros after it, made as they're read. */
    static InputStream fontShaped(long length) {
        return new InputStream() {
            private long at;

            @Override
            public int read() {
                if (at >= length) return -1;
                int value = at == 1 ? 1 : 0;
                at++;
                return value;
            }

            @Override
            public int read(byte[] buffer, int offset, int count) {
                if (at >= length) return -1;
                int n = (int) Math.min(count, length - at);
                for (int i = 0; i < n; i++) buffer[offset + i] = (byte) (at + i == 1 ? 1 : 0);
                at += n;
                return n;
            }
        };
    }

    private File existingCopy() throws IOException {
        File target = new File(folder.getRoot(), FontFile.NAME);
        Files.write(target.toPath(), "the font picked before".getBytes(StandardCharsets.UTF_8));
        return target;
    }

    private static FontFile.Refusal refusal(InputStream input, File target, FontFile.Check check) {
        try {
            FontFile.copy(input, target, check);
        } catch (FontFile.Refused refused) {
            return refused.reason;
        }
        fail("the file was taken");
        return null;
    }

    private void assertUntouched(File target) throws IOException {
        assertEquals("the copy picked before changed", "the font picked before",
                new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
        assertFalse("a partial copy was left behind", new File(folder.getRoot(), FontFile.PARTIAL).exists());
    }

    @Test
    public void aFontIsKnownByItsFirstFourBytes() {
        assertTrue(FontFile.isFontTag(0x00010000));
        assertTrue(FontFile.isFontTag(tag("true")));
        assertTrue(FontFile.isFontTag(tag("OTTO")));
        assertTrue(FontFile.isFontTag(tag("ttcf")));
        // Web fonts, which Android can't draw from, a zip, a photo and text.
        for (String other : new String[]{"wOFF", "wOF2", "PK\u0003\u0004", "\u0089PNG", "GIF8", "{\"fo", "TRUE", "otto"}) {
            assertFalse(other, FontFile.isFontTag(tag(other)));
        }
        assertFalse(FontFile.isFontTag(0));
    }

    private static int tag(String four) {
        return ByteBuffer.wrap(four.getBytes(StandardCharsets.ISO_8859_1)).getInt();
    }

    @Test
    public void aFontIsCopiedWholeOnceAndroidBuildsIt() throws Exception {
        byte[] rubik = font(STATIC_FONT);
        File target = existingCopy();
        List<byte[]> checked = new ArrayList<>();
        Watched input = new Watched(new ByteArrayInputStream(rubik));
        FontFile.copy(input, target, file -> {
            try {
                checked.add(Files.readAllBytes(file.toPath()));
            } catch (IOException unreadable) {
                throw new AssertionError(unreadable);
            }
            assertFalse("the check saw the copy in place of the old one", file.equals(target));
            return true;
        });
        assertArrayEquals(rubik, Files.readAllBytes(target.toPath()));
        assertEquals("the check didn't see the whole file", 1, checked.size());
        assertArrayEquals(rubik, checked.get(0));
        assertTrue("the picker's stream was left open", input.closed);
        assertFalse(new File(folder.getRoot(), FontFile.PARTIAL).exists());
    }

    @Test
    public void everyKindOfFontFileIsTaken() throws Exception {
        for (String resource : new String[]{STATIC_FONT, VARIABLE_FONT}) {
            File target = new File(folder.getRoot(), FontFile.NAME);
            FontFile.copy(new ByteArrayInputStream(font(resource)), target, file -> true);
            assertArrayEquals(resource, font(resource), Files.readAllBytes(target.toPath()));
        }
        // CFF outlines, and Apple's tag: only the first four bytes are the question here.
        for (String head : new String[]{"OTTO", "true", "ttcf"}) {
            File target = new File(folder.getRoot(), FontFile.NAME);
            byte[] bytes = (head + " and the rest of a font").getBytes(StandardCharsets.ISO_8859_1);
            FontFile.copy(new ByteArrayInputStream(bytes), target, file -> true);
            assertArrayEquals(head, bytes, Files.readAllBytes(target.toPath()));
        }
    }

    /** A photo picked by mistake is turned down on its first four bytes, before anything is written. */
    @Test
    public void aFileThatIsntAFontIsTurnedDownBeforeAnythingIsWritten() throws Exception {
        File target = existingCopy();
        byte[] photo = new byte[100_000];
        byte[] head = {(byte) 0x89, 'P', 'N', 'G'};
        System.arraycopy(head, 0, photo, 0, head.length);
        Watched input = new Watched(new ByteArrayInputStream(photo));
        AtomicBoolean asked = new AtomicBoolean();
        assertEquals(FontFile.Refusal.NOT_A_FONT, refusal(input, target, file -> asked.getAndSet(true)));
        assertEquals("read past the font header", 4, input.read);
        assertTrue(input.closed);
        assertFalse("Android was asked about a photo", asked.get());
        assertUntouched(target);

        assertEquals("an empty file is no font", FontFile.Refusal.NOT_A_FONT,
                refusal(new ByteArrayInputStream(new byte[0]), target, file -> true));
        assertEquals(FontFile.Refusal.NOT_A_FONT,
                refusal(new ByteArrayInputStream(new byte[]{0, 1, 0}), target, file -> true));
        assertUntouched(target);
    }

    @Test
    public void aFileLargerThanTheCapIsTurnedDown() throws Exception {
        File target = existingCopy();
        assertEquals(FontFile.Refusal.TOO_LARGE, refusal(fontShaped(FontFile.MAX_BYTES + 1), target, file -> true));
        assertUntouched(target);

        // The cap itself is taken.
        FontFile.copy(fontShaped(FontFile.MAX_BYTES), target, file -> true);
        assertEquals(FontFile.MAX_BYTES, target.length());
        assertEquals(20, FontFile.MAX_MEGABYTES);
    }

    /** A file that starts like a font and isn't one Android builds is turned down, the check's own failure too. */
    @Test
    public void aFontAndroidWontBuildIsTurnedDown() throws Exception {
        File target = existingCopy();
        assertEquals(FontFile.Refusal.WONT_LOAD,
                refusal(new ByteArrayInputStream(font(STATIC_FONT)), target, file -> false));
        assertUntouched(target);
        assertEquals(FontFile.Refusal.WONT_LOAD, refusal(new ByteArrayInputStream(font(STATIC_FONT)), target,
                file -> {
                    throw new IllegalStateException("native font parser gave up");
                }));
        assertUntouched(target);
    }

    @Test
    public void aStreamThatBreaksOffIsUnreadable() throws Exception {
        File target = existingCopy();
        InputStream breaks = new InputStream() {
            private int at;

            @Override
            public int read() throws IOException {
                if (at >= 10) throw new IOException("content://provider/private-name.ttf went away");
                return at++ == 1 ? 1 : 0;
            }
        };
        assertEquals(FontFile.Refusal.UNREADABLE, refusal(breaks, target, file -> true));
        assertUntouched(target);
        assertEquals(FontFile.Refusal.UNREADABLE, refusal(null, target, file -> true));
        assertUntouched(target);
    }

    /** The name is saved once the copy has passed and before it moves in; one that won't save changes nothing. */
    @Test
    public void theNameIsSavedBeforeTheCopyMovesIn() throws Exception {
        byte[] rubik = font(STATIC_FONT);
        File target = existingCopy();
        List<String> said = new ArrayList<>();
        FontFile.copy(new ByteArrayInputStream(rubik), target, file -> {
            said.add("check");
            return true;
        }, new FontFile.Choice() {
            @Override
            public boolean save() {
                try {
                    said.add("save, the copy before still " + (Files.readAllBytes(target.toPath()).length
                            == "the font picked before".length() ? "in place" : "gone"));
                } catch (IOException unreadable) {
                    throw new AssertionError(unreadable);
                }
                return true;
            }

            @Override
            public void undo() {
                said.add("undo");
            }
        });
        assertEquals(Arrays.asList("check", "save, the copy before still in place"), said);
        assertArrayEquals(rubik, Files.readAllBytes(target.toPath()));

        // A name that won't save, or whose saving throws, leaves the copy before where it was.
        File again = existingCopy();
        AtomicInteger undone = new AtomicInteger();
        for (boolean throwing : new boolean[]{false, true}) {
            try {
                FontFile.copy(new ByteArrayInputStream(rubik), again, file -> true, new FontFile.Choice() {
                    @Override
                    public boolean save() {
                        if (throwing) throw new IllegalStateException("injected commit failure");
                        return false;
                    }

                    @Override
                    public void undo() {
                        undone.incrementAndGet();
                    }
                });
                fail("the copy was taken without its name");
            } catch (FontFile.Refused refused) {
                assertEquals(FontFile.Refusal.NOT_SAVED, refused.reason);
            }
            assertUntouched(again);
        }
        assertEquals("a name that never saved was put back", 0, undone.get());
    }

    /**
     * A copy that can't move in leaves the one picked before in place, never neither, and puts the
     * name back. Windows won't rename a file another handle holds open without sharing its
     * deletion, which stands in here for a rename that fails.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void aCopyThatCantMoveInLeavesTheOneBefore() throws Exception {
        assumeTrue("only Windows refuses to rename a file held open",
                System.getProperty("os.name", "").startsWith("Windows"));
        // The JDK's own option, looked up by name: the test sources compile against Android's classes.
        OpenOption noShareDelete = (OpenOption) Enum.valueOf(
                (Class) Class.forName("com.sun.nio.file.ExtendedOpenOption"), "NOSHARE_DELETE");
        File target = existingCopy();
        List<Closeable> held = new ArrayList<>();
        AtomicInteger saved = new AtomicInteger();
        AtomicInteger undone = new AtomicInteger();
        try {
            FontFile.copy(new ByteArrayInputStream(font(STATIC_FONT)), target, file -> {
                try {
                    held.add(Files.newByteChannel(file.toPath(), StandardOpenOption.READ, noShareDelete));
                } catch (IOException unreadable) {
                    throw new AssertionError(unreadable);
                }
                return true;
            }, new FontFile.Choice() {
                @Override
                public boolean save() {
                    saved.incrementAndGet();
                    return true;
                }

                @Override
                public void undo() {
                    undone.incrementAndGet();
                }
            });
            fail("a copy held open moved in");
        } catch (FontFile.Refused refused) {
            assertEquals(FontFile.Refusal.NOT_SAVED, refused.reason);
        } finally {
            for (Closeable handle : held) handle.close();
        }
        assertTrue("the copy picked before was lost", target.isFile());
        assertEquals("the copy picked before changed", "the font picked before",
                new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
        assertEquals(1, saved.get());
        assertEquals("the name wasn't put back", 1, undone.get());
    }

    @Test
    public void aCopyWithNowhereToGoIsNotSaved() throws Exception {
        File nowhere = new File(new File(folder.getRoot(), "missing-folder"), FontFile.NAME);
        assertEquals(FontFile.Refusal.NOT_SAVED,
                refusal(new ByteArrayInputStream(font(STATIC_FONT)), nowhere, file -> true));
        assertFalse(nowhere.exists());
    }

    @Test
    public void theWeightAxisComesFromTheFvarTable() throws Exception {
        assertArrayEquals("Noto Sans Khmer's early axis runs from 26 to 190", new float[]{26f, 190f},
                FontFile.weightAxis(write("khmer.ttf", font(VARIABLE_FONT))), 0.001f);
        assertNull("a static font has no axis", FontFile.weightAxis(write("rubik.ttf", font(STATIC_FONT))));
        assertNull("a collection of static fonts has none", FontFile.weightAxis(write("cjk.ttc", font(COLLECTION))));
        assertNull(FontFile.weightAxis(new File(folder.getRoot(), "not-there.ttf")));

        byte[] variable = sfntWithAxes(new String[]{"wdth", "wght"}, new float[][]{{75, 100}, {100, 900}}, 20);
        assertArrayEquals(new float[]{100f, 900f}, FontFile.weightAxis(write("made.ttf", variable)), 0.001f);
        assertArrayEquals("the first font of a collection", new float[]{100f, 900f},
                FontFile.weightAxis(write("made.ttc", collectionOf(variable))), 0.001f);
        assertNull("no wght axis", FontFile.weightAxis(write("wdth.ttf",
                sfntWithAxes(new String[]{"wdth"}, new float[][]{{75, 100}}, 20))));
        assertNull("an axis record too short to hold one", FontFile.weightAxis(write("short.ttf",
                sfntWithAxes(new String[]{"wght"}, new float[][]{{100, 900}}, 16))));
        assertNull("weights no font takes", FontFile.weightAxis(write("zero.ttf",
                sfntWithAxes(new String[]{"wght"}, new float[][]{{0, 900}}, 20))));
        byte[] cut = new byte[variable.length - 30];
        System.arraycopy(variable, 0, cut, 0, cut.length);
        assertNull("a table cut off", FontFile.weightAxis(write("cut.ttf", cut)));
        byte[] boastful = variable.clone();
        boastful[4] = (byte) 0xFF;
        boastful[5] = (byte) 0xFF;
        assertNull("a directory claiming 65535 tables", FontFile.weightAxis(write("boastful.ttf", boastful)));
    }

    private File write(String name, byte[] bytes) throws IOException {
        File file = new File(folder.getRoot(), name);
        Files.write(file.toPath(), bytes);
        return file;
    }

    /** A TrueType header with one table, an fvar holding [tags] with their least and greatest values. */
    static byte[] sfntWithAxes(String[] tags, float[][] ranges, int axisSize) {
        int fvarAt = 12 + 16;
        int axesAt = 16;
        ByteBuffer bytes = ByteBuffer.allocate(fvarAt + axesAt + axisSize * tags.length + 8);
        bytes.putInt(0x00010000).putShort((short) 1).putShort((short) 16).putShort((short) 0).putShort((short) 0);
        bytes.putInt(tag("fvar")).putInt(0).putInt(fvarAt).putInt(axesAt + axisSize * tags.length);
        bytes.putShort((short) 1).putShort((short) 0).putShort((short) axesAt).putShort((short) 2)
                .putShort((short) tags.length).putShort((short) axisSize).putShort((short) 0).putShort((short) 0);
        for (int i = 0; i < tags.length; i++) {
            int at = fvarAt + axesAt + axisSize * i;
            bytes.position(at);
            bytes.putInt(tag(tags[i]));
            if (axisSize >= 16) {
                bytes.putInt(Math.round(ranges[i][0] * 65536)).putInt(Math.round(ranges[i][1] * 65536))
                        .putInt(Math.round(ranges[i][1] * 65536));
            }
        }
        return bytes.array();
    }

    /** A 'ttcf' header whose one font is [font], placed after it. */
    static byte[] collectionOf(byte[] font) {
        // The font's own table offsets count from the start of the file, so it goes where its
        // tables still land: the header is 16 bytes, and the font's first table sits past that.
        ByteBuffer bytes = ByteBuffer.allocate(16 + font.length + 16);
        bytes.putInt(tag("ttcf")).putInt(0x00010000).putInt(1).putInt(16);
        // The font's directory at 16; its fvar offset moves by the same 16.
        byte[] moved = font.clone();
        ByteBuffer directory = ByteBuffer.wrap(moved);
        int fvar = directory.getInt(12 + 8);
        directory.putInt(12 + 8, fvar + 16);
        bytes.put(moved);
        return bytes.array();
    }

    // ---- Emoji fonts ------------------------------------------------------------------------------

    /** Counts what a copy did with its setting. */
    private static final class Saves implements FontFile.Choice {
        int saved;
        int undone;

        @Override
        public boolean save() {
            saved++;
            return true;
        }

        @Override
        public void undo() {
            undone++;
        }
    }

    @Test
    public void aMapReachingTheEmojiBlocksHoldsEmoji() throws Exception {
        assertFalse(FontFile.lacksEmoji(write("smileys.ttf", sfntWithMap(12, 0x1F600, 0x1F64F))));
        assertFalse("the first emoji block's start", FontFile.lacksEmoji(write("first.ttf", sfntWithMap(12, 0x1F300, 0x1F300))));
        assertFalse("the last emoji block's end", FontFile.lacksEmoji(write("last.ttf", sfntWithMap(12, 0x1FAFF, 0x1FAFF))));
        assertFalse("one group across them all", FontFile.lacksEmoji(write("wide.ttf", sfntWithMap(12, 0x10000, 0x2FFFF))));
        assertFalse("format 13", FontFile.lacksEmoji(write("last-resort.ttf", sfntWithMap(13, 0x1F600, 0x1F64F))));
    }

    @Test
    public void aMapStoppingShortOfThemHoldsNone() throws Exception {
        assertTrue(FontFile.lacksEmoji(write("latin.ttf", sfntWithMap(12, 0x41, 0x5A))));
        assertTrue("the enclosed ideographs just before them", FontFile.lacksEmoji(write("cjk.ttf", sfntWithMap(12, 0x1F200, 0x1F2FF))));
        assertTrue("the block just after them", FontFile.lacksEmoji(write("legacy.ttf", sfntWithMap(12, 0x1FB00, 0x1FBFF))));
        assertTrue("a basic plane map", FontFile.lacksEmoji(write("basic.ttf", sfntWithMap(4, 0, 0))));
    }

    /** Android's own text fonts, which a person could well pick by mistake. */
    @Test
    public void realTextFontsHoldNone() throws Exception {
        assertTrue(FontFile.lacksEmoji(write("rubik.ttf", font(STATIC_FONT))));
        assertTrue(FontFile.lacksEmoji(write("khmer.ttf", font(VARIABLE_FONT))));
        assertTrue(FontFile.lacksEmoji(write("cjk.ttc", font(COLLECTION))));
        assertFalse(FontFile.lacksEmoji(write("rubik-emoji.ttf", withEmoji(font(STATIC_FONT)))));
    }

    @Test
    public void aCollectionIsJudgedByItsFirstFont() throws Exception {
        assertFalse(FontFile.lacksEmoji(write("emoji.ttc", collectionOf(sfntWithMap(12, 0x1F600, 0x1F64F)))));
        assertTrue(FontFile.lacksEmoji(write("latin.ttc", collectionOf(sfntWithMap(12, 0x41, 0x5A)))));
    }

    /** Android has already built a typeface by now, so what can't be read isn't held against it. */
    @Test
    public void aMapThatCantBeReadIsGivenTheBenefitOfTheDoubt() throws Exception {
        byte[] whole = sfntWithMap(12, 0x41, 0x5A);
        assertFalse("cut off in its groups", FontFile.lacksEmoji(write("cut.ttf", Arrays.copyOf(whole, whole.length - 8))));
        assertFalse("no map at all", FontFile.lacksEmoji(write("none.ttf", sfntWithAxes(new String[]{"wght"},
                new float[][]{{100, 900}}, 20))));
        assertFalse("format 10 isn't read", FontFile.lacksEmoji(write("ten.ttf", sfntWithMap(10, 0x41, 0x5A))));
        assertFalse("not a font at all", FontFile.lacksEmoji(write("short.ttf", new byte[]{1, 2})));
    }

    @Test
    public void anEmojiFontIsCopiedAndItsChoiceSaved() throws Exception {
        byte[] emoji = withEmoji(font(STATIC_FONT));
        File target = existingCopy();
        Saves choice = new Saves();
        FontFile.copyEmoji(new ByteArrayInputStream(emoji), target, file -> true, choice);
        assertArrayEquals(emoji, Files.readAllBytes(target.toPath()));
        assertEquals(1, choice.saved);
        assertEquals(0, choice.undone);
    }

    @Test
    public void aTextFontIsTurnedDownAsAnEmojiFont() throws Exception {
        File target = existingCopy();
        Saves choice = new Saves();
        try {
            FontFile.copyEmoji(new ByteArrayInputStream(font(STATIC_FONT)), target, file -> true, choice);
            fail("a font with no emoji was taken");
        } catch (FontFile.Refused refused) {
            assertEquals(FontFile.Refusal.NO_EMOJI, refused.reason);
        }
        assertUntouched(target);
        assertEquals("the setting moved for a refused font", 0, choice.saved);
    }

    /** The same font stays fine as a text font. */
    @Test
    public void aTextFontStaysWelcomeAsATextFont() throws Exception {
        byte[] rubik = font(STATIC_FONT);
        File target = existingCopy();
        FontFile.copy(new ByteArrayInputStream(rubik), target, file -> true);
        assertArrayEquals(rubik, Files.readAllBytes(target.toPath()));
    }

    /** A color emoji font runs well past the text limit, and has a limit of its own. */
    @Test
    public void anEmojiFontHasALargerLimitOfItsOwn() throws Exception {
        File target = existingCopy();
        FontFile.copyEmoji(fontShaped(FontFile.MAX_BYTES + 1), target, file -> true, new Saves());
        assertEquals(FontFile.MAX_BYTES + 1, target.length());

        Saves choice = new Saves();
        try {
            FontFile.copyEmoji(fontShaped(FontFile.MAX_EMOJI_BYTES + 1), target, file -> true, choice);
            fail("a file over the emoji limit was taken");
        } catch (FontFile.Refused refused) {
            assertEquals(FontFile.Refusal.TOO_LARGE, refused.reason);
        }
        assertEquals("the copy before it changed", FontFile.MAX_BYTES + 1, target.length());
        assertEquals(0, choice.saved);
        assertFalse(new File(folder.getRoot(), FontFile.PARTIAL).exists());
    }

    /** A TrueType header with one table, a character map whose one subtable has one group. */
    private static byte[] sfntWithMap(int format, int first, int last) {
        int mapAt = 12 + 16;
        int mapLength = 4 + 8 + 16 + 12;
        ByteBuffer bytes = ByteBuffer.allocate(mapAt + mapLength);
        bytes.putInt(0x00010000).putShort((short) 1).putShort((short) 16).putShort((short) 0).putShort((short) 0);
        bytes.putInt(tag("cmap")).putInt(0).putInt(mapAt).putInt(mapLength);
        bytes.putShort((short) 0).putShort((short) 1);
        bytes.putShort((short) 3).putShort((short) 10).putInt(12);
        bytes.putShort((short) format).putShort((short) 0).putInt(16 + 12).putInt(0).putInt(1);
        bytes.putInt(first).putInt(last).putInt(1);
        return bytes.array();
    }

    /**
     * [font] with the smileys, U+1F600 on, added to its character map. The map moves to the end of
     * the file with one more encoding, so Android still builds a typeface from the result.
     */
    public static byte[] withEmoji(byte[] font) {
        ByteBuffer in = ByteBuffer.wrap(font);
        int tables = in.getShort(4) & 0xFFFF;
        for (int index = 0; index < tables; index++) {
            int record = 12 + 16 * index;
            if (in.getInt(record) != tag("cmap")) continue;
            int at = in.getInt(record + 8);
            int length = in.getInt(record + 12);
            int encodings = in.getShort(at + 2) & 0xFFFF;
            int headerLength = 4 + 8 * encodings;
            int added = 8;
            int groupsAt = (length + added + 3) & ~3;
            int total = groupsAt + 16 + 12;
            int start = (font.length + 3) & ~3;
            ByteBuffer out = ByteBuffer.allocate(start + total);
            out.put(font);
            out.position(start);
            out.putShort((short) 0).putShort((short) (encodings + 1));
            for (int encoding = 0; encoding < encodings; encoding++) {
                int entry = at + 4 + 8 * encoding;
                out.putShort(in.getShort(entry)).putShort(in.getShort(entry + 2)).putInt(in.getInt(entry + 4) + added);
            }
            out.putShort((short) 3).putShort((short) 10).putInt(groupsAt);
            out.put(font, at + headerLength, length - headerLength);
            out.position(start + groupsAt);
            out.putShort((short) 12).putShort((short) 0).putInt(16 + 12).putInt(0).putInt(1);
            out.putInt(0x1F600).putInt(0x1F64F).putInt(1);
            out.putInt(record + 8, start).putInt(record + 12, total);
            return out.array();
        }
        throw new AssertionError("no character map");
    }
}
