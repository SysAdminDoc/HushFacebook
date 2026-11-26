/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.updates.playstore

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.apksig.internal.apk.AndroidBinXmlParser
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.reandroid.apk.ApkModule
import com.reandroid.apk.ApkModuleXmlDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document

/**
 * Disable Play Store updates on every Facebook build the bundle declares: the resource half reads
 * the manifest's version code and raises it to the highest Android allows. Then every place Facebook's code reads a PackageInfo version code,
 * the int field or the long getter, goes through the extension, which answers the real one, with
 * its answer landing in the register the read wrote. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class DisablePlayStoreUpdatesFixtureTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private fun bundles(check: (File) -> Unit) {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** The version code the base APK's binary manifest declares. */
    private fun manifestVersionCode(bundle: File): Int {
        ZipFile(bundle).use { zip ->
            val base = checkNotNull(zip.getEntry("base.apk")) { "${bundle.name} holds no base.apk" }
            ZipInputStream(zip.getInputStream(base).buffered()).use { apk ->
                while (true) {
                    val entry = apk.nextEntry ?: break
                    if (entry.name != "AndroidManifest.xml") continue
                    val parser = AndroidBinXmlParser(ByteBuffer.wrap(apk.readBytes()))
                    while (true) {
                        val event = parser.next()
                        if (event == AndroidBinXmlParser.EVENT_END_DOCUMENT) break
                        if (event != AndroidBinXmlParser.EVENT_START_ELEMENT || parser.name != "manifest") continue
                        for (at in 0 until parser.attributeCount) {
                            if (parser.getAttributeNamespace(at) == android && parser.getAttributeName(at) == "versionCode") {
                                return parser.getAttributeIntValue(at)
                            }
                        }
                    }
                }
            }
        }
        throw AssertionError("${bundle.name}: the base manifest declares no version code")
    }

    /**
     * The base APK's manifest as Morphe hands it to a resource patch: decoded to XML by ARSCLib's
     * ApkModuleXmlDecoder, the decoder its resource coder extends, and parsed as a plain document.
     */
    private fun decodedManifest(bundle: File): Document {
        val work = Files.createTempDirectory("hushfacebook-manifest").toFile()
        try {
            val apk = File(work, "base.apk")
            ZipFile(bundle).use { zip ->
                val base = checkNotNull(zip.getEntry("base.apk")) { "${bundle.name} holds no base.apk" }
                zip.getInputStream(base).use { input -> apk.outputStream().use { input.copyTo(it) } }
            }
            val decoded = File(work, "decoded")
            val module = ApkModule.loadApkFile(apk)
            try {
                ApkModuleXmlDecoder(module).decodeAndroidManifest(decoded)
            } finally {
                module.close()
            }
            val manifest = decoded.walk().single { it.name == "AndroidManifest.xml" }
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun ClassDef.code(): List<Instruction> = methods.flatMap { it.implementation?.instructions?.toList().orEmpty() }

    private fun Instruction.names(reference: String) = (this as? ReferenceInstruction)?.reference?.toString() == reference

    private fun Instruction.readsInt() = opcode == Opcode.IGET && names(VERSION_CODE_FIELD)

    /** The long getter calls whose answer is kept. One on each build drops it and goes on, so it's left alone. */
    private fun List<Instruction>.keptLongReads() = indices.filter {
        this[it].opcode == Opcode.INVOKE_VIRTUAL && this[it].names(LONG_VERSION_CODE) && getOrNull(it + 1)?.opcode == Opcode.MOVE_RESULT_WIDE
    }

    private fun List<Instruction>.droppedLongReads() =
        count { it.opcode == Opcode.INVOKE_VIRTUAL && it.names(LONG_VERSION_CODE) } - keptLongReads().size

    @Test
    fun `each declared build raises its version code and keeps reading the real one`() = bundles { bundle ->
        val name = bundle.name
        // AppCompatibilitiesMatchFixturesTest holds it to the code the bundle declares for the build.
        val code = manifestVersionCode(bundle)
        assertTrue("$name: the manifest's version code $code is already raised", code in 1 until Int.MAX_VALUE)

        // The resource half, on the manifest as Morphe decodes it.
        val document = decodedManifest(bundle)
        assertEquals("$name: the version code read", code, document.versionCode())
        document.raiseVersionCode()
        assertEquals("$name: the version code written", Int.MAX_VALUE, document.versionCode())

        // Every class of Facebook's that reads a version code.
        val readers = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            val reads = dex.fieldSection.any { it.toString() == VERSION_CODE_FIELD } ||
                dex.methodSection.any { it.toString() == LONG_VERSION_CODE }
            if (!reads) return@forEach
            for (classDef in dex.classes) {
                if (readsVersionCode(classDef)) readers += ImmutableClassDef.of(classDef)
            }
        }
        val intReads = readers.sumOf { reader -> reader.code().count { it.readsInt() } }
        val longReads = readers.sumOf { reader -> reader.methods.sumOf { it.implementation?.instructions?.toList().orEmpty().keptLongReads().size } }
        assertTrue("$name reads no int version code", intReads > 0)
        assertTrue("$name reads no long version code", longReads > 0)

        val context = PatchContexts.of(readers)
        var swapped = 0
        for (reader in readers) {
            for (method in context.mutableClassDefBy(reader.type).methods) {
                val original = method.implementation?.instructions?.toList().orEmpty()
                val destinations = original.filter { it.readsInt() }.map { (it as TwoRegisterInstruction).registerA }
                val infos = original.filter { it.readsInt() }.map { (it as TwoRegisterInstruction).registerB } +
                    original.keptLongReads().map { (original[it] as FiveRegisterInstruction).registerC }
                val dropped = original.droppedLongReads()
                swapped += method.readRealVersionCode()
                val patched = method.implementation?.instructions?.toList().orEmpty()
                val where = "$name: ${reader.type}->${method.name}"
                assertTrue("$where still reads the raised version code", patched.none { it.names(VERSION_CODE_FIELD) })
                assertEquals("$where still keeps a raised long version code", 0, patched.keptLongReads().size)
                assertEquals("$where: a call whose answer is dropped changed", dropped, patched.droppedLongReads())
                // Each int read's answer lands where the read put it, straight after the call.
                val intCalls = patched.indices.filter { patched[it].names(GET_VERSION_CODE) }
                assertEquals("$where: int reads sent to the extension", destinations.size, intCalls.size)
                assertEquals("$where: the answers land where the reads put them", destinations.sorted(),
                    intCalls.map { (patched[it + 1].also { next -> assertEquals(Opcode.MOVE_RESULT, next.opcode) }
                        as OneRegisterInstruction).registerA }.sorted())
                val longCalls = patched.indices.filter { patched[it].names(GET_VERSION_CODE_LONG) }
                longCalls.forEach { assertEquals("$where: a long answer is kept", Opcode.MOVE_RESULT_WIDE, patched[it + 1].opcode) }
                val calledWith = (intCalls + longCalls).map { (patched[it] as RegisterRangeInstruction).startRegister }
                assertEquals("$where: each call is handed the PackageInfo the read was", infos.sorted(), calledWith.sorted())
                assertEquals("$where: only the reads changed", original.size + destinations.size, patched.size)
            }
        }
        assertEquals("$name: every version code read was swapped", intReads + longReads, swapped)
    }

    /**
     * Morphe's own Disable Play Store updates (morphe-patches 2.4.0-dev.5 and newer) sends every version
     * code read outside its own extension class through that class, ours included, and this patch does
     * the same with Morphe's. With both picked, a read left in our helper went to Morphe's helper, which
     * this patch had sent back to ours, so Facebook died of a StackOverflowError at its first version read
     * (#561). The same rewrite, run over the helper as the bundle ships it, has to find nothing to send.
     */
    @Test
    fun `another Disable Play Store updates finds no read to send back into the extension`() {
        val helper = ExtensionDex.classDef(EXTENSION_CLASS)
        assertFalse("$EXTENSION_CLASS reads a version code another copy of the patch would swap", readsVersionCode(helper))
        val context = PatchContexts.of(listOf(ImmutableClassDef.of(helper)))
        val swapped = context.mutableClassDefBy(EXTENSION_CLASS).methods.sumOf { it.readRealVersionCode() }
        assertEquals("version code reads in $EXTENSION_CLASS another copy of the patch swaps", 0, swapped)
    }
}
