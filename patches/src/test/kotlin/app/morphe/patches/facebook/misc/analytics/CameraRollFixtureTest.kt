/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.analytics

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.search.branchTarget
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The camera roll switch's anchors in Hold back analytics uploads, on every declared Facebook build:
 * the one config check the pipeline's run asks, which the scheduling app job asks too, and the media
 * count job's kill switch, whose branch cancels that job's work. Then both hooks go in on the real
 * classes, which also assembles their smali.
 */
class CameraRollFixtureTest {
    @Test
    fun `each declared build has the processing check and the media count kill switch once`() {
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

    private fun check(bundle: File) {
        val name = bundle.name
        val kept = FixtureDex.classes(bundle, setOf(RUN_PIPELINE, MEDIA_COUNT_JOB, SCHEDULING_JOB))
        val runPipeline = kept[RUN_PIPELINE]
        val mediaCountJob = kept[MEDIA_COUNT_JOB]
        val scheduling = kept[SCHEDULING_JOB]
        assertNotNull("$name: no $RUN_PIPELINE", runPipeline)
        assertNotNull("$name: no $MEDIA_COUNT_JOB", mediaCountJob)
        assertNotNull("$name: no $SCHEDULING_JOB", scheduling)

        // 584 asks a string table for the reason, so the run names it through one.
        val call = processingCheck(runPipeline!!) { asked ->
            FixtureDex.classes(bundle, setOf(asked.definingClass))[asked.definingClass]?.let { resolveStatic(it, asked) }
        }
        assertNotNull("$name: the pipeline's run doesn't call one (FbUserSession)Z check", call)
        val checkClass = FixtureDex.classes(bundle, setOf(call!!.definingClass))[call.definingClass]
        val checks = checkClass?.methods?.filter { isProcessingCheck(it, call) }.orEmpty()
        assertEquals("$name: methods matching ${call.definingClass}->${call.name}", 1, checks.size)
        // The same check gates the scheduling: the app job that enqueues the processing workers asks it.
        val asked = scheduling!!.methods.flatMap(::sessionChecks).map { "${it.definingClass}->${it.name}" }
        assertTrue("$name: $SCHEDULING_JOB doesn't ask ${call.definingClass}->${call.name}", "${call.definingClass}->${call.name}" in asked)

        val classes = mutableMapOf<String, ClassDef?>()
        val classOf = { type: String -> classes.getOrPut(type) { FixtureDex.classes(bundle, setOf(type))[type] } }
        val switches = mediaCountJob!!.methods.mapNotNull { method -> mediaCountSwitch(method, classOf)?.let { method to it } }
        assertEquals("$name: media count kill switches", 1, switches.size)
        val (job, result) = switches.single()
        val code = job.implementation!!.instructions.toList()
        val answer = (code[result] as OneRegisterInstruction).registerA
        val test = code.indexOfFirst { it.opcode == Opcode.IF_NEZ && (it as OneRegisterInstruction).registerA == answer }
        val cancel = branchTarget(code, test)
        assertNotNull("$name: the kill switch's branch target", cancel)
        assertTrue("$name: the cancel branch comes after the switch", cancel!! > test)

        // Both hooks go in on the real classes.
        val context = PatchContexts.of(listOf(checkClass!!, mediaCountJob))
        context.mutableClassDefBy(checkClass.type).findMutableMethodOf(checks.single()).holdProcessingFirst()
        context.mutableClassDefBy(MEDIA_COUNT_JOB).findMutableMethodOf(job).passMediaCountSwitch(result)

        val gate = context.mutableClassDefBy(checkClass.type).findMutableMethodOf(checks.single()).implementation!!.instructions.toList()
        assertEquals("$name: the check's first call", HOLD_PROCESSING, gate[0].call())
        assertEquals("$name: the held answer", Opcode.RETURN, gate[4].opcode)
        assertEquals("$name: the check's own code after the hook", checks.single().implementation!!.instructions.count() + 5, gate.size)

        val patched = context.mutableClassDefBy(MEDIA_COUNT_JOB).findMutableMethodOf(job).implementation!!.instructions.toList()
        val hook = patched[result + 1]
        assertEquals("$name: the call after the kill switch's read", STOP_MEDIA_COUNT, hook.call())
        assertEquals("$name: the register handed over", answer, (hook as RegisterRangeInstruction).startRegister)
        assertEquals("$name: the answer goes back where it came from", answer, (patched[result + 2] as OneRegisterInstruction).registerA)
        // 586 asks a string table for the work's name in between, so the test is the first branch after.
        val nextBranch = patched.drop(result + 3).first { it is OffsetInstruction }
        assertEquals("$name: the kill switch's test still follows", Opcode.IF_NEZ, nextBranch.opcode)
        assertEquals("$name: the test reads the handed-back answer", answer, (nextBranch as OneRegisterInstruction).registerA)
    }

    private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.call(): String? =
        ((this as? ReferenceInstruction)?.reference as? MethodReference)?.let {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
        }
}
