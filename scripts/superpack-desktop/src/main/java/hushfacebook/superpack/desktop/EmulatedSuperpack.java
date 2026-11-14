package hushfacebook.superpack.desktop;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.arm.backend.BackendFactory;
import com.github.unidbg.arm.backend.DynarmicFactory;
import com.github.unidbg.file.FileResult;
import com.github.unidbg.file.IOResolver;
import com.github.unidbg.file.linux.AndroidFileIO;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.android.dvm.AbstractJni;
import com.github.unidbg.linux.android.dvm.BaseVM;
import com.github.unidbg.linux.android.dvm.DalvikModule;
import com.github.unidbg.linux.android.dvm.DvmClass;
import com.github.unidbg.linux.android.dvm.DvmObject;
import com.github.unidbg.linux.android.dvm.StringObject;
import com.github.unidbg.linux.android.dvm.VM;
import com.github.unidbg.linux.android.dvm.VaList;
import com.github.unidbg.linux.android.dvm.VarArg;
import com.github.unidbg.linux.android.dvm.array.ByteArray;
import com.github.unidbg.linux.file.ByteArrayFileIO;
import com.github.unidbg.memory.Memory;
import com.github.unidbg.virtualmodule.android.AndroidModule;
import com.sun.jna.Pointer;
import hushfacebook.superpack.DecodeStore;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The input APK's own arm64 libsuperpack-jni.so, run under unidbg's ARM64 emulator instead of on
 * a phone. JNI_OnLoad registers its natives against the same class names the phone route stubs
 * in com.facebook.superpack, and each {@link DecodeStore.Natives} call goes to the registered
 * function. The one Java call the library makes back is InputStream.read([BII)I, answered from
 * the real stream here.
 */
final class EmulatedSuperpack extends AbstractJni implements DecodeStore.Natives, IOResolver<AndroidFileIO> {
    private static final String INPUT_STREAM = "java/io/InputStream";
    // The library sizes its buffers from MemAvailable. A phone with 4 GB free says about this.
    private static final String MEMINFO = "MemTotal:        7766340 kB\nMemFree:         2097152 kB\nMemAvailable:    4194304 kB\n";

    private final File library;
    private final File rootDir;
    private final String backend;
    private final List<String> thrown = new ArrayList<>();
    private AndroidEmulator emulator;
    private VM vm;
    private DvmClass archiveClass;
    private DvmClass fileClass;
    private DvmClass streamClass;
    private int relocated = -1;

    EmulatedSuperpack(File library, File rootDir, String backend) {
        this.library = library;
        this.rootDir = rootDir;
        this.backend = backend;
    }

    @Override
    public void load() throws IOException {
        if (!rootDir.mkdirs() && !rootDir.isDirectory()) throw new IOException("can't make " + rootDir);
        List<BackendFactory> backends = new ArrayList<>();
        switch (backend) {
            case "dynarmic":
                // A JIT: about 25 seconds for a 30 MB store, where Unicorn runs well past ten
                // minutes. true: fall back to Unicorn when dynarmic's native library won't load.
                backends.add(new DynarmicFactory(true));
                break;
            case "unicorn":
                // unidbg's own default, kept to tell a dynarmic fault from a decoder one.
                break;
            default:
                throw new IllegalArgumentException("unknown backend " + backend + " (dynarmic or unicorn)");
        }
        emulator = new DecoderEmulator("com.facebook.katana", rootDir, backends);
        // readNative hands the decompression to worker threads and waits on a futex for them.
        // Without the dispatcher those threads never run and the wait spins forever.
        emulator.getSyscallHandler().setEnableThreadDispatcher(true);
        Memory memory = emulator.getMemory();
        memory.setLibraryResolver(new AndroidResolver(23));
        emulator.getSyscallHandler().addIOResolver(this);
        vm = emulator.createDalvikVM();
        vm.setVerbose(false);
        vm.setJni(this);
        // Before the library loads: JNI_OnLoad looks up the exception classes it throws.
        vm.setDvmClassFactory((base, name, superClass, interfaces) ->
                name.endsWith("Exception") ? new ThrownClass(base, name, superClass, interfaces) : null);
        new AndroidModule(emulator, vm).register(memory);
        // unidbg maps the library and applies its other relocations, then calls this before any
        // init function runs, which is when the RELR pointers have to be right.
        byte[] elf = Files.readAllBytes(library.toPath());
        String soName = library.getName();
        memory.addModuleListener((emu, loaded) -> {
            if (!soName.equals(loaded.name)) return;
            try {
                relocated = Relr.apply(emu, elf, loaded.base);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
        DalvikModule module = vm.loadLibrary(library, true);
        if (relocated < 0) throw new IOException("unidbg never reported " + soName + " loaded");
        module.callJNI_OnLoad(emulator);
        archiveClass = vm.resolveClass("com/facebook/superpack/SuperpackArchive");
        fileClass = vm.resolveClass("com/facebook/superpack/SuperpackFile");
        streamClass = vm.resolveClass(INPUT_STREAM);
        check("JNI_OnLoad");
    }

    @Override
    public long readArchive(InputStream in, String extension, long options) {
        DvmObject<?> stream = streamClass.newObject(in);
        if (options == 0) {
            // Left at 0 the library starts four workers. unidbg runs every emulated thread on this
            // one, so more than one only adds switching; the output is the same either way.
            options = archiveClass.callStaticJniMethodLong(emulator, "getThreadNumOption(I)J", 1);
            check("getThreadNumOption");
        }
        long archive = archiveClass.callStaticJniMethodLong(emulator, "readNative(Ljava/io/InputStream;Ljava/lang/String;J)J", stream, extension, options);
        check("readNative");
        return archive;
    }

    @Override
    public boolean isEmpty(long archive) {
        boolean empty = archiveClass.callStaticJniMethodBoolean(emulator, "isEmptyNative(J)Z", archive);
        check("isEmptyNative");
        return empty;
    }

    @Override
    public long next(long archive) {
        long file = archiveClass.callStaticJniMethodLong(emulator, "nextNative(J)J", archive);
        check("nextNative");
        return file;
    }

    @Override
    public void closeArchive(long archive) {
        archiveClass.callStaticJniMethod(emulator, "closeNative(J)V", archive);
        check("SuperpackArchive.closeNative");
    }

    @Override
    public String name(long file) {
        StringObject name = fileClass.callStaticJniMethodObject(emulator, "getNameNative(J)Ljava/lang/String;", file);
        check("getNameNative");
        return name == null ? null : name.getValue();
    }

    @Override
    public int length(long file) {
        int length = fileClass.callStaticJniMethodInt(emulator, "getLengthNative(J)I", file);
        check("getLengthNative");
        return length;
    }

    @Override
    public void readBytes(long file, int offset, int count, byte[] into, int intoOffset) {
        // The library copies into the array with SetByteArrayRegion, which writes through to into.
        fileClass.callStaticJniMethod(emulator, "readBytesNative(JII[BI)V", file, offset, count, new ByteArray(vm, into), intoOffset);
        check("readBytesNative");
    }

    @Override
    public void closeFile(long file) {
        fileClass.callStaticJniMethod(emulator, "closeNative(J)V", file);
        check("SuperpackFile.closeNative");
    }

    /** Frees the emulator. Safe to call when load never ran. */
    void close() throws IOException {
        if (emulator != null) emulator.close();
    }

    @Override
    public int callIntMethod(BaseVM vm, DvmObject<?> dvmObject, String signature, VarArg varArg) {
        if ("java/io/InputStream->read([BII)I".equals(signature)) return read(dvmObject, varArg);
        return super.callIntMethod(vm, dvmObject, signature, varArg);
    }

    @Override
    public int callIntMethodV(BaseVM vm, DvmObject<?> dvmObject, String signature, VaList vaList) {
        if ("java/io/InputStream->read([BII)I".equals(signature)) return read(dvmObject, vaList);
        return super.callIntMethodV(vm, dvmObject, signature, vaList);
    }

    private int read(DvmObject<?> stream, VarArg args) {
        ByteArray buffer = args.getObjectArg(0);
        int offset = args.getIntArg(1);
        int count = args.getIntArg(2);
        try {
            return ((InputStream) stream.getValue()).read(buffer.getValue(), offset, count);
        } catch (IOException failure) {
            thrown.add("java/io/IOException: " + failure.getMessage());
            return -1;
        }
    }

    @Override
    public FileResult<AndroidFileIO> resolve(Emulator<AndroidFileIO> emulator, String pathname, int oflags) {
        if ("/proc/meminfo".equals(pathname)) {
            return FileResult.success(new ByteArrayFileIO(oflags, pathname, MEMINFO.getBytes(StandardCharsets.US_ASCII)));
        }
        return null;
    }

    private void check(String call) {
        if (!thrown.isEmpty()) {
            String what = String.join("; ", thrown);
            thrown.clear();
            throw new IllegalStateException(call + " threw " + what);
        }
    }

    /**
     * An exception class. unidbg clears a pending exception when a call returns, so ThrowNew's
     * message is kept here, through the object it makes, for {@link #check} to report.
     */
    private final class ThrownClass extends DvmClass {
        ThrownClass(BaseVM vm, String className, DvmClass superClass, DvmClass[] interfaceClasses) {
            super(vm, className, superClass, interfaceClasses);
        }

        @Override
        public DvmObject<?> newObject(Object value) {
            thrown.add(getClassName() + (value instanceof Pointer ? ": " + ((Pointer) value).getString(0) : ""));
            return super.newObject(value);
        }
    }
}
