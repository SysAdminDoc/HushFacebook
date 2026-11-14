package hushfacebook.superpack.desktop;

import com.github.unidbg.arm.ARMEmulator;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.arm.backend.BackendException;
import com.github.unidbg.arm.backend.BackendFactory;
import com.github.unidbg.file.linux.AndroidFileIO;
import com.github.unidbg.linux.ARM64SyscallHandler;
import com.github.unidbg.linux.android.AndroidARM64Emulator;
import com.github.unidbg.memory.SvcMemory;
import com.github.unidbg.unix.UnixSyscallHandler;
import unicorn.Arm64Const;

import java.io.File;
import java.util.Collection;

/**
 * unidbg's 64-bit Android emulator with madvise(MADV_DONTNEED) done the way Linux does it.
 *
 * <p>unidbg answers every madvise with success and changes nothing. The jemalloc in the libc it
 * loads purges freed pages with MADV_DONTNEED and then counts them as zeroed, so a later calloc
 * can hand out old bytes. Without this the decoder stops with "Context 4096 already allocated,
 * cannot allocate again" on every backend, and the dynarmic one can crash the JVM on a wild read.
 * Linux gives an anonymous page back as zeros after MADV_DONTNEED, so that's what this does.
 */
final class DecoderEmulator extends AndroidARM64Emulator {
    DecoderEmulator(String processName, File rootDir, Collection<BackendFactory> backendFactories) {
        super(processName, rootDir, backendFactories);
    }

    @Override
    protected UnixSyscallHandler<AndroidFileIO> createSyscallHandler(SvcMemory svcMemory) {
        return new SyscallHandler(svcMemory);
    }

    private static final class SyscallHandler extends ARM64SyscallHandler {
        private static final int NR_MADVISE = 233;
        private static final int MADV_DONTNEED = 4;
        private static final int ENOMEM = 12;
        private static final byte[] ZEROS = new byte[1 << 16];

        SyscallHandler(SvcMemory svcMemory) {
            super(svcMemory);
        }

        @Override
        public void hook(Backend backend, int intno, int swi, Object user) {
            if (intno == ARMEmulator.EXCP_SWI && swi == 0
                    && backend.reg_read(Arm64Const.UC_ARM64_REG_X8).intValue() == NR_MADVISE) {
                long address = backend.reg_read(Arm64Const.UC_ARM64_REG_X0).longValue();
                long length = backend.reg_read(Arm64Const.UC_ARM64_REG_X1).longValue();
                int advice = backend.reg_read(Arm64Const.UC_ARM64_REG_X2).intValue();
                long result = 0;
                if (advice == MADV_DONTNEED) {
                    try {
                        for (long at = 0; at < length; at += ZEROS.length) {
                            int count = (int) Math.min(ZEROS.length, length - at);
                            backend.mem_write(address + at, count == ZEROS.length ? ZEROS : new byte[count]);
                        }
                    } catch (BackendException unmapped) {
                        result = -ENOMEM;
                    }
                }
                backend.reg_write(Arm64Const.UC_ARM64_REG_X0, result);
                return;
            }
            super.hook(backend, intno, swi, user);
        }
    }
}
