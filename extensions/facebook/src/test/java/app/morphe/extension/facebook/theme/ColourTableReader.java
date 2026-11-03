/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads a {@link ColourTable} back: its type spec's flags and each configuration's colours by entry index. */
final class ColourTableReader {

    /** What one type chunk says: its configuration's uiMode and sdkVersion, and each entry's colour by index. */
    static final class Config {
        int uiMode;
        int sdkVersion;
        final Map<Integer, Integer> colours = new LinkedHashMap<>();
    }

    final int[] specFlags;
    final List<Config> configs = new ArrayList<>();

    ColourTableReader(byte[] bytes) {
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int pkg = 12 + in.getInt(16);
        int at = pkg + in.getInt(pkg + 276) + in.getInt(pkg + in.getInt(pkg + 276) + 4);
        assertEquals("a type spec", 0x0202, in.getShort(at));
        specFlags = new int[in.getInt(at + 12)];
        for (int i = 0; i < specFlags.length; i++) specFlags[i] = in.getInt(at + 16 + 4 * i);
        at += in.getInt(at + 4);
        while (at < bytes.length) {
            assertEquals("a type chunk", 0x0201, in.getShort(at));
            int entryCount = in.getInt(at + 12);
            int entriesStart = in.getInt(at + 16);
            Config config = new Config();
            config.sdkVersion = in.getShort(at + 20 + 24);
            config.uiMode = in.get(at + 20 + 29);
            for (int index = 0; index < entryCount; index++) {
                int offset = in.getInt(at + 84 + 4 * index);
                if (offset != 0xFFFFFFFF) config.colours.put(index, in.getInt(at + entriesStart + offset + 12));
            }
            configs.add(config);
            at += in.getInt(at + 4);
        }
        assertEquals("the chunks end where the table does", bytes.length, at);
    }
}
