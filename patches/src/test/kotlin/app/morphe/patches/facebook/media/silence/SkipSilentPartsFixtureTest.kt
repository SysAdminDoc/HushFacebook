/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.silence

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.readsAfter
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Skip silent parts on each declared Facebook build: each audio sink's hand-off of the
 * skip-silence flag to its processor chain asks the extension first, into a register nothing
 * reads afterwards, and hands the chain that answer, with the sink's own copy of the flag and the
 * rest of the method as Facebook wrote it. The listener's own dispatch is left alone.
 */
class SkipSilentPartsFixtureTest {
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

    private fun same(a: Method, b: Method) = a.name == b.name && a.returnType == b.returnType &&
        a.parameterTypes.map(CharSequence::toString) == b.parameterTypes.map(CharSequence::toString)

    @Test
    fun `each declared build's audio sinks ask before handing their chain the skip-silence flag`() = bundles { bundle ->
        val tellers = FixtureDex.methodsWhere(bundle, { true }) { method ->
            method.implementation?.instructions?.any(::tellsListener) == true
        }
        // Probed on both 582 builds (2026-10-14): the DefaultAudioSink of each of the two ExoPlayer
        // copies Facebook ships, and one copy's listener dispatch, a Runnable that hands nothing on.
        assertEquals("${bundle.name}: methods telling a listener the flag", 3, tellers.size)
        val expected = tellers.mapNotNull(::silenceApply)
        assertEquals("${bundle.name}: audio sinks", 2, expected.size)
        val dispatch = tellers.single { teller -> expected.none { it.method.definingClass == teller.definingClass } }
        assertEquals("${bundle.name}: the dispatch", "run", dispatch.name)

        val owners = FixtureDex.classes(bundle, tellers.map { it.definingClass }.toSet())
        val context = PatchContexts.of(owners.values)
        val applies = context.silenceApplies()
        assertEquals("${bundle.name}: the sinks found", expected.map { "${it.method.definingClass} ${it.index}" }.sorted(),
            applies.map { "${it.method.definingClass} ${it.index}" }.sorted())

        for (apply in applies) {
            val where = "${bundle.name}: ${apply.method.definingClass}->${apply.method.name}"
            val original = owners.getValue(apply.method.definingClass).methods.single { same(it, apply.method) }
            val code = original.implementation!!.instructions.toList()
            val call = code[apply.index]
            assertEquals("$where: the hand-off's form", Opcode.INVOKE_INTERFACE, call.opcode)
            assertTrue("$where: the scratch register fits the call", apply.scratch < 16)
            assertNotEquals("$where: the scratch register is the flag", apply.flag, apply.scratch)
            assertEquals("$where: something reads v${apply.scratch} after the hand-off", emptyList<Int>(),
                original.readsAfter(apply.index, apply.scratch))
            assertTrue("$where: the listener is told after the hand-off",
                code.indexOfFirst(::tellsListener) > apply.index)

            val method = context.mutableClassDefBy(apply.method.definingClass).methods.single { same(it, apply.method) }
            method.askBeforeApplying(apply)
            val body = method.implementation!!.instructions.toList()
            assertEquals("$where: two instructions added", code.size + 2, body.size)
            for (at in 0 until apply.index) assertEquals("$where: instruction $at", code[at].opcode, body[at].opcode)
            val ask = body[apply.index]
            assertEquals("$where: the ask's form", Opcode.INVOKE_STATIC_RANGE, ask.opcode)
            assertEquals("$where: the ask", OWN_SKIP_SILENCE, (ask as ReferenceInstruction).reference.toString())
            assertEquals("$where: the ask reads the flag", apply.flag, (ask as RegisterRangeInstruction).startRegister)
            assertEquals("$where: the ask reads one register", 1, ask.registerCount)
            val answer = body[apply.index + 1]
            assertEquals("$where: the answer's form", Opcode.MOVE_RESULT, answer.opcode)
            assertEquals("$where: the answer's register", apply.scratch, (answer as OneRegisterInstruction).registerA)
            val handed = body[apply.index + 2]
            assertEquals("$where: the hand-off's form after", Opcode.INVOKE_INTERFACE, handed.opcode)
            assertEquals("$where: the hand-off's method", (call as ReferenceInstruction).reference.toString(),
                (handed as ReferenceInstruction).reference.toString())
            val registers = handed as FiveRegisterInstruction
            assertEquals("$where: the hand-off's chain", (call as FiveRegisterInstruction).registerC, registers.registerC)
            assertEquals("$where: the hand-off's flag", apply.scratch, registers.registerD)
            // 587's chain answers the flag it applied (Z), and the sink keeps Facebook's flag instead.
            val answers = ((call as ReferenceInstruction).reference as MethodReference).returnType == "Z"
            assertEquals("$where: the chain's answer is taken", answers, apply.result != null)
            for (at in apply.index + 1 until code.size) {
                if (at == apply.index + 1 && apply.result != null) continue
                assertEquals("$where: instruction $at", code[at].opcode, body[at + 2].opcode)
            }
            apply.result?.let { result ->
                assertEquals("$where: the chain's answer went to v$result", Opcode.MOVE_RESULT, code[apply.index + 1].opcode)
                val kept = body[apply.index + 3]
                assertEquals("$where: the answer's place takes a move", Opcode.MOVE_FROM16, kept.opcode)
                assertEquals("$where: of Facebook's flag into the answer's register", listOf(result, apply.flag),
                    (kept as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
            }
        }
    }
}
