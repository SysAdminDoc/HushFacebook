/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Writes a resource table, the binary form of {@code resources.arsc}, that holds nothing but colour
 * values for some of the app's own colour resources. Android 11 and newer load such a table through
 * a {@code ResourcesLoader}, and a colour in it answers for the app's colour of the same id and the
 * same configuration (AssetManager2 lets a loader's entry win a tie), so a drawable or a theme that
 * names the colour gets this one. Material Components builds its custom colour tables the same way.
 *
 * <p>Each colour has a default value and may have a night one. The night values go in twice, as
 * {@code night} and as {@code night-v8}: aapt2 writes {@code values-night} with the v8 its qualifier
 * implies and other tools don't, and only a configuration equal to the app's wins the tie.
 *
 * <p>Plain Java on purpose, so a test can read the bytes back with a resource table reader.
 */
final class ColourTable {

    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int RES_TABLE_TYPE = 0x0002;
    private static final int RES_TABLE_PACKAGE_TYPE = 0x0200;
    private static final int RES_TABLE_TYPE_TYPE = 0x0201;
    private static final int RES_TABLE_TYPE_SPEC_TYPE = 0x0202;

    private static final int TABLE_HEADER_SIZE = 12;
    private static final int STRING_POOL_HEADER_SIZE = 28;
    private static final int PACKAGE_HEADER_SIZE = 288;
    private static final int PACKAGE_NAME_CHARS = 128;
    private static final int TYPE_SPEC_HEADER_SIZE = 16;
    private static final int CONFIG_SIZE = 64;
    private static final int TYPE_HEADER_SIZE = 20 + CONFIG_SIZE;
    /** A ResTable_entry and its Res_value. */
    private static final int ENTRY_SIZE = 16;

    private static final int UTF8_FLAG = 0x100;
    private static final int NO_ENTRY = 0xFFFFFFFF;
    private static final int TYPE_INT_COLOR_ARGB8 = 0x1c;

    /** ResTable_config's uiMode, at byte 29, for night; and the configuration-change flag that goes with it. */
    private static final int CONFIG_UI_MODE_OFFSET = 29;
    private static final int UI_MODE_NIGHT_YES = 0x20;
    private static final int CONFIG_SDK_VERSION_OFFSET = 24;
    private static final int SPEC_UI_MODE = 0x1000;

    private ColourTable() {}

    /**
     * The table for {@code ids}, colour resources of one app package and type, each with its
     * {@code defaults} value and, where {@code hasNight} says so, its {@code nights} one.
     *
     * @throws IllegalArgumentException for no ids, ids of more than one package or type, an id
     *     given twice, or arrays of different lengths
     */
    static byte[] write(String packageName, int[] ids, int[] defaults, int[] nights, boolean[] hasNight) {
        if (ids.length == 0 || defaults.length != ids.length || nights.length != ids.length || hasNight.length != ids.length) {
            throw new IllegalArgumentException("ids and colours don't line up");
        }
        int packageId = ids[0] >>> 24;
        int typeId = (ids[0] >> 16) & 0xFF;
        int entryCount = 0;
        boolean anyNight = false;
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] >>> 24 != packageId || ((ids[i] >> 16) & 0xFF) != typeId) {
                throw new IllegalArgumentException("ids of more than one package or type");
            }
            for (int j = 0; j < i; j++) {
                if (ids[j] == ids[i]) throw new IllegalArgumentException("id given twice");
            }
            entryCount = Math.max(entryCount, (ids[i] & 0xFFFF) + 1);
            anyNight |= hasNight[i];
        }
        if (packageId == 0 || typeId == 0) throw new IllegalArgumentException("not an app resource id");

        // The type names up to this one, which is all a lookup by id reads: placeholders, then "color".
        String[] typeNames = new String[typeId];
        for (int i = 0; i < typeId; i++) typeNames[i] = "?" + (i + 1);
        typeNames[typeId - 1] = "color";
        byte[] typePool = stringPool(typeNames);
        String[] keys = new String[ids.length];
        for (int i = 0; i < ids.length; i++) keys[i] = "c" + Integer.toHexString(ids[i]);
        byte[] keyPool = stringPool(keys);

        int[] specFlags = new int[entryCount];
        for (int i = 0; i < ids.length; i++) if (hasNight[i]) specFlags[ids[i] & 0xFFFF] = SPEC_UI_MODE;
        byte[] spec = typeSpec(typeId, specFlags);
        byte[] plain = type(typeId, entryCount, ids, defaults, null, 0, 0);
        byte[] night = anyNight ? type(typeId, entryCount, ids, nights, hasNight, UI_MODE_NIGHT_YES, 0) : new byte[0];
        byte[] nightV8 = anyNight ? type(typeId, entryCount, ids, nights, hasNight, UI_MODE_NIGHT_YES, 8) : new byte[0];

        int packageSize = PACKAGE_HEADER_SIZE + typePool.length + keyPool.length + spec.length
                + plain.length + night.length + nightV8.length;
        byte[] globalPool = stringPool(new String[0]);
        ByteBuffer out = buffer(TABLE_HEADER_SIZE + globalPool.length + packageSize);
        out.putShort((short) RES_TABLE_TYPE).putShort((short) TABLE_HEADER_SIZE).putInt(out.capacity()).putInt(1);
        out.put(globalPool);

        out.putShort((short) RES_TABLE_PACKAGE_TYPE).putShort((short) PACKAGE_HEADER_SIZE).putInt(packageSize);
        out.putInt(packageId);
        char[] name = packageName.toCharArray();
        for (int i = 0; i < PACKAGE_NAME_CHARS; i++) out.putChar(i < name.length && i < PACKAGE_NAME_CHARS - 1 ? name[i] : 0);
        out.putInt(PACKAGE_HEADER_SIZE); // typeStrings
        out.putInt(typeNames.length); // lastPublicType
        out.putInt(PACKAGE_HEADER_SIZE + typePool.length); // keyStrings
        out.putInt(keys.length); // lastPublicKey
        out.putInt(0); // typeIdOffset
        out.put(typePool).put(keyPool).put(spec).put(plain).put(night).put(nightV8);
        return out.array();
    }

    private static ByteBuffer buffer(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** A UTF-8 string pool. Each string is short ASCII, so both of its lengths fit one byte. */
    private static byte[] stringPool(String[] strings) {
        int data = 0;
        for (String s : strings) {
            if (s.length() >= 0x80) throw new IllegalArgumentException("string too long for one length byte");
            data += 2 + s.getBytes(StandardCharsets.UTF_8).length + 1;
        }
        int padded = (data + 3) & ~3;
        int stringsStart = strings.length == 0 ? 0 : STRING_POOL_HEADER_SIZE + 4 * strings.length;
        int size = STRING_POOL_HEADER_SIZE + 4 * strings.length + padded;
        ByteBuffer out = buffer(size);
        out.putShort((short) RES_STRING_POOL_TYPE).putShort((short) STRING_POOL_HEADER_SIZE).putInt(size);
        out.putInt(strings.length).putInt(0).putInt(UTF8_FLAG).putInt(stringsStart).putInt(0);
        int offset = 0;
        for (String s : strings) {
            out.putInt(offset);
            offset += 2 + s.getBytes(StandardCharsets.UTF_8).length + 1;
        }
        for (String s : strings) {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            out.put((byte) s.length()).put((byte) bytes.length).put(bytes).put((byte) 0);
        }
        return out.array();
    }

    private static byte[] typeSpec(int typeId, int[] flags) {
        ByteBuffer out = buffer(TYPE_SPEC_HEADER_SIZE + 4 * flags.length);
        out.putShort((short) RES_TABLE_TYPE_SPEC_TYPE).putShort((short) TYPE_SPEC_HEADER_SIZE).putInt(out.capacity());
        out.put((byte) typeId).put((byte) 0).putShort((short) 0).putInt(flags.length);
        for (int flag : flags) out.putInt(flag);
        return out.array();
    }

    /**
     * One configuration of the colours: the default one when {@code only} is null, otherwise the
     * entries {@code only} marks, under a configuration with this {@code uiMode} and {@code sdkVersion}.
     */
    private static byte[] type(int typeId, int entryCount, int[] ids, int[] colours, boolean[] only, int uiMode, int sdkVersion) {
        int present = 0;
        for (int i = 0; i < ids.length; i++) if (only == null || only[i]) present++;
        int entriesStart = TYPE_HEADER_SIZE + 4 * entryCount;
        ByteBuffer out = buffer(entriesStart + ENTRY_SIZE * present);
        out.putShort((short) RES_TABLE_TYPE_TYPE).putShort((short) TYPE_HEADER_SIZE).putInt(out.capacity());
        out.put((byte) typeId).put((byte) 0).putShort((short) 0).putInt(entryCount).putInt(entriesStart);
        byte[] config = new byte[CONFIG_SIZE];
        config[0] = CONFIG_SIZE;
        config[CONFIG_SDK_VERSION_OFFSET] = (byte) sdkVersion;
        config[CONFIG_UI_MODE_OFFSET] = (byte) uiMode;
        out.put(config);

        int[] offsets = new int[entryCount];
        java.util.Arrays.fill(offsets, NO_ENTRY);
        int next = 0;
        int[] order = new int[present];
        for (int i = 0, k = 0; i < ids.length; i++) {
            if (only != null && !only[i]) continue;
            offsets[ids[i] & 0xFFFF] = next;
            next += ENTRY_SIZE;
            order[k++] = i;
        }
        for (int offset : offsets) out.putInt(offset);
        for (int i : order) {
            // ResTable_entry: its size, no flags, and the key; then a Res_value holding the colour.
            out.putShort((short) 8).putShort((short) 0).putInt(i);
            out.putShort((short) 8).put((byte) 0).put((byte) TYPE_INT_COLOR_ARGB8).putInt(colours[i]);
        }
        return out.array();
    }
}
