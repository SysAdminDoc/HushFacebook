/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe

import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.PatcherContext
import app.morphe.patcher.PatcherProgress
import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import java.io.File

/**
 * A patcher context over a handful of classes and no APK, so a test can run a patch's execute block
 * as the patcher does and read what it left in each class.
 *
 * The patcher fills its class pool from an APK's dex files, and what takes a pool directly is
 * internal to it: the context's constructor, the pool's class and its setter. They are reached by
 * their JVM names, pinned to morphe-patcher 2.3.0. A bump that renames one fails here, naming it,
 * rather than leaving a test that never ran the patch. Nothing is written to disk: the context only
 * names its work folders until something compiles the dex, which no test here asks for. Patcher
 * 2.3.0 hands the pool a nullable PatcherProgress beside the classes; tests report none.
 */
internal object PatchContexts {
    fun of(classes: Collection<ClassDef>, validate: Boolean = false): BytecodePatchContext {
        val work = File(System.getProperty("java.io.tmpdir"), "hushfacebook-patch-context")
        val config = PatcherConfig(apkFile = File(work, "none.apk"), temporaryFilesPath = work, validate = validate)
        val metadataType = Class.forName("app.morphe.patcher.PackageMetadata")
        val metadata = metadataType.getConstructor(
            String::class.java, String::class.java, String::class.java,
            Class.forName("com.reandroid.archive.block.ApkSignatureBlock"),
        ).newInstance("com.facebook.katana", "0", "0", null)
        val context = BytecodePatchContext::class.java.getConstructor(PatcherConfig::class.java, metadataType)
            .newInstance(config, metadata)
        val poolType = Class.forName("app.morphe.patcher.util.PatchClasses")
        val pool = poolType.getConstructor(Set::class.java, PatcherProgress::class.java).newInstance(classes.toSet(), null)
        BytecodePatchContext::class.java.getMethod("setPatchClasses\$morphe_patcher", poolType).invoke(context, pool)
        BytecodePatchContext::class.java.getMethod("setOpcodes\$morphe_patcher", Opcodes::class.java)
            .invoke(context, Opcodes.getDefault())
        makeCurrent(context, metadata, work)
        return context
    }

    /**
     * Patcher 2.3.0 matches fingerprints, and answers its global functions, against the one APK being
     * patched: [PatcherContext]'s `current`, which a real context sets as it is built. Its
     * constructor reads an APK's resources, so a context over loose classes gets a [PatcherContext]
     * made without one, holding this bytecode context and its metadata, and becomes current the
     * same way. The newest context built is the current one, as in the patcher. It has no resource
     * context, so a test that reaches the resources fails on that rather than reading another APK's.
     */
    private fun makeCurrent(context: BytecodePatchContext, metadata: Any, work: File) {
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null)
        val patcherContext = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, PatcherContext::class.java)
        fun set(name: String, value: Any) =
            PatcherContext::class.java.getDeclaredField(name).apply { isAccessible = true }.set(patcherContext, value)
        set("bytecodeContext", context)
        set("packageMetadata", metadata)
        set("fileWorkspace", work)
        val companion = PatcherContext::class.java.getField("Companion").get(null)
        companion.javaClass.getMethod("setCurrent\$morphe_patcher", PatcherContext::class.java).invoke(companion, patcherContext)
    }
}
