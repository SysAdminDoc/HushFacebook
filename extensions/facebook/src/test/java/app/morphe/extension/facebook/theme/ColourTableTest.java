/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Route five's resource table, read back chunk by chunk the way Android's table reader walks it:
 * the table header, the empty value pool, one package of the app's id and name, its type names
 * with "color" at the colour type's index, a type spec, and one type chunk per configuration whose
 * offsets point at a colour entry for each id and at nothing in between.
 */
public class ColourTableTest {
    private static final int[] IDS = {0x7f0601d4, 0x7f0601d5, 0x7f06044a};
    private static final int[] DEFAULTS = {0xff112233, 0xff445566, 0x80778899};
    private static final int[] NIGHTS = {0, 0, 0xffaabbcc};
    private static final boolean[] HAS_NIGHT = {false, false, true};

    /** What one type chunk says: its configuration's uiMode and sdkVersion, and each entry's colour by index. */
    private static final class Config {
        int uiMode;
        int sdkVersion;
        final Map<Integer, Integer> colours = new LinkedHashMap<>();
    }

    @Test
    public void theTableReadsBackAsTheAppsColourResources() {
        byte[] bytes = ColourTable.write("com.facebook.katana", IDS, DEFAULTS, NIGHTS, HAS_NIGHT);
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals("a resource table", 0x0002, in.getShort(0));
        assertEquals(12, in.getShort(2));
        assertEquals("its size is the whole", bytes.length, in.getInt(4));
        assertEquals("one package", 1, in.getInt(8));
        assertEquals("the value pool", 0x0001, in.getShort(12));
        assertEquals("holds no strings", 0, in.getInt(12 + 8));

        int pkg = 12 + in.getInt(12 + 4);
        assertEquals("a package", 0x0200, in.getShort(pkg));
        assertEquals(288, in.getShort(pkg + 2));
        assertEquals("the package ends the table", bytes.length, pkg + in.getInt(pkg + 4));
        assertEquals("the app's id", 0x7f, in.getInt(pkg + 8));
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 128 && in.getChar(pkg + 12 + 2 * i) != 0; i++) name.append(in.getChar(pkg + 12 + 2 * i));
        assertEquals("com.facebook.katana", name.toString());

        List<String> types = strings(in, pkg + in.getInt(pkg + 268));
        assertEquals("the colour type's name sits at its id", "color", types.get(5));
        List<String> keys = strings(in, pkg + in.getInt(pkg + 276));
        assertEquals("a key for each id", IDS.length, keys.size());

        int at = pkg + in.getInt(pkg + 276) + in.getInt(pkg + in.getInt(pkg + 276) + 4);
        assertEquals("a type spec", 0x0202, in.getShort(at));
        assertEquals("of the colour type", 6, in.get(at + 8));
        int entryCount = in.getInt(at + 12);
        assertEquals("entries up to the last id", 0x44a + 1, entryCount);
        assertEquals("a colour with a night value changes with night mode", 0x1000, in.getInt(at + 16 + 4 * 0x44a));
        assertEquals("one without doesn't", 0, in.getInt(at + 16 + 4 * 0x1d4));
        at += in.getInt(at + 4);

        List<Config> configs = new ArrayList<>();
        while (at < bytes.length) {
            assertEquals("a type chunk", 0x0201, in.getShort(at));
            assertEquals(84, in.getShort(at + 2));
            assertEquals(6, in.get(at + 8));
            assertEquals(entryCount, in.getInt(at + 12));
            int entriesStart = in.getInt(at + 16);
            assertEquals("entries follow the offsets", 84 + 4 * entryCount, entriesStart);
            assertEquals("a 64 byte configuration", 64, in.getInt(at + 20));
            Config config = new Config();
            config.sdkVersion = in.getShort(at + 20 + 24);
            config.uiMode = in.get(at + 20 + 29);
            for (int index = 0; index < entryCount; index++) {
                int offset = in.getInt(at + 84 + 4 * index);
                if (offset == 0xFFFFFFFF) continue;
                int entry = at + entriesStart + offset;
                assertEquals("an entry's size", 8, in.getShort(entry));
                assertEquals("a simple entry", 0, in.getShort(entry + 2));
                assertTrue("its key is in the pool", in.getInt(entry + 4) < keys.size());
                assertEquals("a value's size", 8, in.getShort(entry + 8));
                assertEquals("a colour", 0x1c, in.get(entry + 11));
                config.colours.put(index, in.getInt(entry + 12));
            }
            configs.add(config);
            at += in.getInt(at + 4);
        }
        assertEquals("the chunks end where the table does", bytes.length, at);

        assertEquals("default, night and night-v8", 3, configs.size());
        Config plain = configs.get(0);
        assertEquals(0, plain.uiMode);
        assertEquals(0, plain.sdkVersion);
        Map<Integer, Integer> expected = new LinkedHashMap<>();
        for (int i = 0; i < IDS.length; i++) expected.put(IDS[i] & 0xFFFF, DEFAULTS[i]);
        assertEquals("every id and nothing else", expected, plain.colours);
        Map<Integer, Integer> night = new LinkedHashMap<>();
        night.put(0x44a, 0xffaabbcc);
        assertEquals(0x20, configs.get(1).uiMode);
        assertEquals(0, configs.get(1).sdkVersion);
        assertEquals("only the colour with a night value", night, configs.get(1).colours);
        assertEquals(0x20, configs.get(2).uiMode);
        assertEquals(8, configs.get(2).sdkVersion);
        assertEquals(night, configs.get(2).colours);
    }

    @Test
    public void withNoNightValueThereIsOnlyTheDefaultConfiguration() {
        byte[] bytes = ColourTable.write("p", new int[]{0x7f060001}, new int[]{0xff0000ff}, new int[]{0}, new boolean[]{false});
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int pkg = 12 + in.getInt(16);
        int at = pkg + in.getInt(pkg + 276) + in.getInt(pkg + in.getInt(pkg + 276) + 4);
        at += in.getInt(at + 4);
        assertEquals(0x0201, in.getShort(at));
        assertEquals("one type chunk ends the table", bytes.length, at + in.getInt(at + 4));
    }

    @Test
    public void idsOfAnotherTypeOrPackageOrNoneAtAllAreRefused() {
        int[] none = {0};
        assertThrows(IllegalArgumentException.class,
                () -> ColourTable.write("p", new int[0], new int[0], new int[0], new boolean[0]));
        assertThrows(IllegalArgumentException.class,
                () -> ColourTable.write("p", new int[]{0x7f060001, 0x7f070001}, new int[2], new int[2], new boolean[2]));
        assertThrows(IllegalArgumentException.class,
                () -> ColourTable.write("p", new int[]{0x7f060001, 0x01060001}, new int[2], new int[2], new boolean[2]));
        assertThrows(IllegalArgumentException.class,
                () -> ColourTable.write("p", new int[]{0x7f060001, 0x7f060001}, new int[2], new int[2], new boolean[2]));
        assertThrows(IllegalArgumentException.class, () -> ColourTable.write("p", new int[]{0x7f060001}, none, none, new boolean[2]));
    }

    /** The strings of the UTF-8 pool at {@code at}. */
    private static List<String> strings(ByteBuffer in, int at) {
        assertEquals("a string pool", 0x0001, in.getShort(at));
        int count = in.getInt(at + 8);
        assertEquals("UTF-8", 0x100, in.getInt(at + 16) & 0x100);
        int start = at + in.getInt(at + 20);
        List<String> strings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int s = start + in.getInt(at + 28 + 4 * i);
            int length = in.get(s + 1) & 0xFF;
            byte[] bytes = new byte[length];
            for (int b = 0; b < length; b++) bytes[b] = in.get(s + 2 + b);
            assertEquals("null-terminated", 0, in.get(s + 2 + length));
            strings.add(new String(bytes, StandardCharsets.UTF_8));
        }
        return strings;
    }
}
