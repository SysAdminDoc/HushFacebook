/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.hdr

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.PatchLogCapture
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The playback format evidence on each declared Facebook build: dav1d's adapter and the one
 * adapter for Android's own decoder on the same playback interface, which the Hero video renderer
 * builder reaches, each get the extension's call at the start of configure and before each return
 * of getOutputFormat, with the same registers and nothing else changed. dav1d's output format
 * carries only the picture's size, so a missing transfer there stays unknown, never SDR; what a
 * served video actually was still takes a Debug report from a phone.
 */
class PlaybackFormatEvidenceFixtureTest {
    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Method.code() = implementation!!.instructions.toList()

    private fun bundles(check: (File) -> Unit) {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `each declared build's two decoders get the evidence and keep every format they're given and return`() = bundles { bundle ->
        val name = bundle.name
        val dav1d = FixtureDex.classes(bundle, setOf(DAV1D_ADAPTER)).values.single()
        val interfaces = FixtureDex.classes(bundle, dav1d.interfaces.toSet()).values
        val adapterInterface = interfaces.single { it.methods.any(::isPlaybackConfigure) }
        val platforms = mutableListOf<ImmutableClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (candidate in dex.classes) {
                if (isPlatformPlaybackAdapter(candidate, adapterInterface.type)) platforms += ImmutableClassDef.of(candidate)
            }
        }
        assertEquals("$name: platform adapters on the kept playback interface", 1, platforms.size)
        val platform = platforms.single()

        val helperType = "Lcom/facebook/video/heroplayer/service/heroexoplayer2/HeroExoPlayer2InitHelper;"
        val helper = FixtureDex.classes(bundle, setOf(helperType)).values.single()
        val builder = helper.methods.single { it.name == "buildMediaCodecVideoRendererWithDav1dInternal" }
        // 581 split the same kept builder into private helpers. Follow its own calls instead of
        // assuming that 577 and 580's inlined decision still lives in that method.
        val related = linkedSetOf<Method>()
        val pending = ArrayDeque(listOf(builder))
        while (pending.isNotEmpty()) {
            val method = pending.removeFirst()
            if (!related.add(method)) continue
            method.code().mapNotNull { it.call }.filter { it.definingClass == helperType }.forEach { call ->
                helper.methods.singleOrNull {
                    it.name == call.name && it.returnType == call.returnType &&
                        it.parameterTypes.map(CharSequence::toString) == call.parameterTypes.map(CharSequence::toString) &&
                        it.implementation != null
                }?.let(pending::addLast)
            }
        }
        assertTrue("$name: the renderer tells the HDR playback origin apart", related.any { holdsString(it, "-hdr") })
        val constructed = related.flatMap { it.code() }.mapNotNull { it.call?.takeIf { call -> call.name == "<init>" }?.definingClass }.toSet()
        val configured = FixtureDex.methodsWhere(bundle, dexFilter = { dex ->
            dex.methodSection.any { it.definingClass == adapterInterface.type && it.name == "configure" }
        }) { method ->
            method.implementation?.instructions?.any { it.call?.let { call -> call.definingClass == adapterInterface.type && call.name == "configure" } == true } == true
        }
        // 584 configures the codec in the renderers' abstract bases, so the classes those extend count too.
        val lineage = mutableSetOf<String>()
        var layer = constructed
        while (layer.isNotEmpty() && lineage.size < 64) {
            lineage += layer
            layer = FixtureDex.classes(bundle, layer).values.mapNotNull { it.superclass }
                .filter { it !in lineage && !it.startsWith("Ljava/") && !it.startsWith("Landroid/") }.toSet()
        }
        // 588 moves the call into a static the renderers share, so a static they call counts too.
        val lineageClasses = FixtureDex.classes(bundle, lineage).values
        fun calledFromLineage(target: Method) = lineageClasses.any { owner ->
            owner.methods.any { method ->
                method.implementation?.instructions?.any { instruction ->
                    instruction.call?.let { call ->
                        call.definingClass == target.definingClass && call.name == target.name && call.returnType == target.returnType &&
                            call.parameterTypes.map(CharSequence::toString) == target.parameterTypes.map(CharSequence::toString)
                    } == true
                } == true
            }
        }
        assertTrue("$name: the Hero video renderer reaches this adapter interface", configured.any {
            it.definingClass in lineage || (AccessFlags.STATIC.isSet(it.accessFlags) && calledFromLineage(it))
        })

        val context = PatchContexts.of(listOf(dav1d, platform, adapterInterface, ExtensionDex.classDef(FORMAT_EVIDENCE)))
        val anchors = context.findPlaybackEvidenceAnchors()
        assertEquals("$name: observed routes", listOf("platform", "dav1d"), anchors.map { it.route })
        assertEquals("$name: two distinct adapter owners", 2, anchors.map { it.owner.type }.toSet().size)
        val dav1dOutput = anchors.single { it.route == "dav1d" }.output
        assertEquals("$name: native output builds resolution only", 1,
            dav1dOutput.code().count { it.call?.let { call -> call.definingClass == MEDIA_FORMAT && call.name == "createVideoFormat" } == true })
        assertFalse("$name: missing native transfer metadata must remain unknown", holdsString(dav1dOutput, "color-transfer"))

        assertEquals("$name: warned", emptyList<String>(), PatchLogCapture.warnings { playbackFormatEvidencePatch.execute(context) })
        for (anchor in anchors) {
            fun patched(method: Method) = context.mutableClassDefBy(method.definingClass).methods.single {
                it.name == method.name && it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
            }
            val input = patched(anchor.configure)
            val inputCode = input.code()
            val first = inputCode.first() as RegisterRangeInstruction
            assertEquals("$name: configure hands on only its own format", anchor.configure.localRegisterCount() + 1, first.startRegister)
            assertEquals(1, first.registerCount)
            assertEquals("$FORMAT_EVIDENCE->${anchor.route}Input($MEDIA_FORMAT)V", inputCode.first().call.toString())
            assertEquals("$name: no configure register added", anchor.configure.implementation!!.registerCount, input.implementation!!.registerCount)
            assertEquals("$name: configure body stays intact", anchor.configure.code().map { it.opcode }, inputCode.drop(1).map { it.opcode })
            assertEquals("$name: configure references stay intact", anchor.configure.code().mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() },
                inputCode.drop(1).mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() })

            val output = patched(anchor.output)
            val code = output.code()
            val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }
            assertTrue("$name: output returns a format", returns.isNotEmpty())
            for (index in returns) {
                val observation = code[index - 1] as RegisterRangeInstruction
                assertEquals("$name: the same returned format is observed", (code[index] as OneRegisterInstruction).registerA, observation.startRegister)
                assertEquals(1, observation.registerCount)
                assertEquals("$FORMAT_EVIDENCE->${anchor.route}Output($MEDIA_FORMAT)V", code[index - 1].call.toString())
            }
            assertEquals("$name: no output register added", anchor.output.implementation!!.registerCount, output.implementation!!.registerCount)
            assertEquals("$name: only observations were inserted", anchor.output.code().map { it.opcode },
                code.filterNot { it.call?.definingClass == FORMAT_EVIDENCE }.map { it.opcode })
        }
    }
}
