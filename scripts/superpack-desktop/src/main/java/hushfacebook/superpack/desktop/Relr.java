package hushfacebook.superpack.desktop;

import com.github.unidbg.Emulator;
import com.github.unidbg.arm.backend.Backend;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Applies a 64-bit library's RELR relative relocations, which unidbg's loader doesn't read.
 *
 * <p>Facebook links libsuperpack-jni.so with Android's packed RELR table (DT_ANDROID_RELR,
 * 0x6fffe000). Without it every pointer the table covers keeps its link-time value, so the first
 * init_array entry jumps to a bare file offset. The standard DT_RELR tags are read too, for builds
 * linked with a newer toolchain.
 */
final class Relr {
    private static final int PT_DYNAMIC = 2;
    private static final long DT_NULL = 0;
    private static final long DT_RELRSZ = 35;
    private static final long DT_RELR = 36;
    private static final long DT_ANDROID_RELR = 0x6fffe000L;
    private static final long DT_ANDROID_RELRSZ = 0x6fffe001L;

    private Relr() {
    }

    /**
     * Adds {@code base} to every 8-byte word the ELF's RELR table names, in the emulator's copy of
     * the library loaded at {@code base}, and returns how many words changed.
     */
    static int apply(Emulator<?> emulator, byte[] elf, long base) throws IOException {
        ByteBuffer file = ByteBuffer.wrap(elf).order(ByteOrder.LITTLE_ENDIAN);
        if (elf.length < 64 || file.getInt(0) != 0x464c457f || elf[4] != 2 || elf[5] != 1) {
            throw new IOException("not a little-endian 64-bit ELF");
        }
        long phoff = file.getLong(0x20);
        int phentsize = file.getShort(0x36) & 0xffff;
        int phnum = file.getShort(0x38) & 0xffff;
        long dynOffset = -1;
        long dynSize = 0;
        for (int i = 0; i < phnum; i++) {
            int at = Math.toIntExact(phoff + (long) i * phentsize);
            if (file.getInt(at) == PT_DYNAMIC) {
                dynOffset = file.getLong(at + 0x08);
                dynSize = file.getLong(at + 0x20);
            }
        }
        if (dynOffset < 0) return 0;

        long table = 0;
        long size = 0;
        for (long at = dynOffset; at + 16 <= dynOffset + dynSize; at += 16) {
            long tag = file.getLong(Math.toIntExact(at));
            long value = file.getLong(Math.toIntExact(at + 8));
            if (tag == DT_NULL) break;
            if (tag == DT_RELR || tag == DT_ANDROID_RELR) table = value;
            if (tag == DT_RELRSZ || tag == DT_ANDROID_RELRSZ) size = value;
        }
        if (table == 0 || size == 0) return 0;
        if (size % 8 != 0) throw new IOException("RELR table size " + size + " isn't whole words");

        // The table sits in a loaded segment, so it's read from the emulator's copy.
        Backend backend = emulator.getBackend();
        ByteBuffer entries = ByteBuffer.wrap(backend.mem_read(base + table, size)).order(ByteOrder.LITTLE_ENDIAN);
        int changed = 0;
        long where = 0;
        while (entries.hasRemaining()) {
            long entry = entries.getLong();
            if ((entry & 1) == 0) {
                relocate(backend, base, entry);
                changed++;
                where = entry + 8;
            } else {
                long bits = entry >>> 1;
                for (int i = 0; bits != 0; i++, bits >>>= 1) {
                    if ((bits & 1) != 0) {
                        relocate(backend, base, where + 8L * i);
                        changed++;
                    }
                }
                where += 8L * 63;
            }
        }
        return changed;
    }

    private static void relocate(Backend backend, long base, long offset) {
        long address = base + offset;
        long value = ByteBuffer.wrap(backend.mem_read(address, 8)).order(ByteOrder.LITTLE_ENDIAN).getLong();
        byte[] bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value + base).array();
        backend.mem_write(address, bytes);
    }
}
