/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.buffer

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Video buffer on each declared Facebook build (#526): HeroPlayer's load control and ExoPlayer's
 * DefaultLoadControl are each found with their start check and keep-loading check. The start check
 * asks the extension where every way to its wait meets, under that spot's label so the jumps there
 * run the ask too, and the keep-loading check asks with its low mark and then its high one. Every
 * other instruction stays as Facebook wrote it, and a check of another shape is refused.
 */
class VideoBufferFixtureTest {
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

    /** The classes loading either marker, with SettingsStatus for a whole run of the patch. */
    private fun owners(bundle: File): List<ClassDef> =
        (FixtureDex.classesHolding(bundle, HERO_LOADING) + FixtureDex.classesHolding(bundle, EXO_LOADING))
            .distinctBy { it.type } + ExtensionDex.classDef(SETTINGS_STATUS)

    private fun BytecodePatchContext.mutable(method: Method) =
        mutableClassDefBy(method.definingClass).methods.single { sameMethod(it, method) }

    private fun registersOf(instruction: Instruction): List<Int> {
        val five = instruction as FiveRegisterInstruction
        return listOf(five.registerC, five.registerD, five.registerE, five.registerF, five.registerG)
            .take(five.registerCount)
    }

    private fun calls(instruction: Instruction) = (instruction as? ReferenceInstruction)?.reference as? MethodReference

    private fun callsMath(instruction: Instruction, name: String) = instruction.opcode == Opcode.INVOKE_STATIC &&
        calls(instruction)?.let { it.definingClass == "Ljava/lang/Math;" && it.name == name } == true

    private fun assertAsk(where: String, instruction: Instruction, method: String, registers: List<Int>) {
        assertEquals("$where: the ask's form", Opcode.INVOKE_STATIC, instruction.opcode)
        assertEquals("$where: the ask", method, calls(instruction).toString())
        assertEquals("$where: the ask's registers", registers, registersOf(instruction))
    }

    private fun assertAnswer(where: String, instruction: Instruction, register: Int) {
        assertEquals("$where: the answer's form", Opcode.MOVE_RESULT_WIDE, instruction.opcode)
        assertEquals("$where: the answer's register", register, (instruction as OneRegisterInstruction).registerA)
    }

    /** [body] is [code] with [added] instructions at each of [at], in order, and nothing else changed. */
    private fun assertRestUnchanged(where: String, code: List<Instruction>, body: List<Instruction>, added: Map<Int, Int>) {
        assertEquals("$where: instructions added", code.size + added.values.sum(), body.size)
        var shift = 0
        for (index in code.indices) {
            shift += added[index] ?: 0
            assertEquals("$where: instruction $index", code[index].opcode, body[index + shift].opcode)
        }
    }

    @Test
    fun `each declared build's start checks ask where every way to the wait meets`() = bundles { bundle ->
        val context = PatchContexts.of(owners(bundle))
        for (player in Player.entries) {
            val control = context.loadControl(player)
            val start = control.start
            val where = "${bundle.name}: ${player.label} ${start.definingClass}->${start.name}"
            assertEquals("$where: answers yes or no", "Z", start.returnType)
            assertEquals("$where: takes the loading check's parameters",
                control.loading.parameterTypes.map(CharSequence::toString), start.parameterTypes.map(CharSequence::toString))
            assertEquals("$where: one class holds both checks", control.loading.definingClass, start.definingClass)

            val code = start.implementation!!.instructions.toList()
            val hook = startHook(start)
            val unset = code.indexOfFirst(::isTimeUnset)
            val targets = jumpTargets(start)
            assertTrue("$where: something jumps to the meeting point ${hook.at}", hook.at in targets)
            assertTrue("$where: the meeting point comes before the duration check", hook.at <= unset)
            assertEquals("$where: no later meeting point before the duration check", emptyList<Int>(),
                targets.filter { it in hook.at + 1..unset })
            val cap = (unset until code.size).first { callsMath(code[it], "min") }
            assertTrue("$where: the cap reads the wait", hook.wait in registersOf(code[cap]))
            assertAnswer("$where: the cap", code[cap + 1], hook.wait)
            assertEquals("$where: the stall flag is a boolean", "Z", hook.rebuffering.type)
            assertEquals("$where: of the parameters", start.parameterTypes[0].toString(), hook.rebuffering.definingClass)
            assertTrue("$where: the scratch register fits the call", hook.scratch < 16)
            assertTrue("$where: the scratch register is the wait or the parameters",
                hook.scratch !in listOf(hook.wait, hook.wait + 1, hook.parameters))

            val method = context.mutable(start)
            method.askStart(hook)
            val body = method.implementation!!.instructions.toList()
            assertRestUnchanged(where, code, body, mapOf(hook.at to 3))
            val read = body[hook.at]
            assertEquals("$where: the flag's read", Opcode.IGET_BOOLEAN, read.opcode)
            assertEquals("$where: the flag's registers", listOf(hook.scratch, hook.parameters),
                (read as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
            assertEquals("$where: the flag", hook.rebuffering, (read as ReferenceInstruction).reference as FieldReference)
            assertAsk(where, body[hook.at + 1], START_AT, listOf(hook.wait, hook.wait + 1, hook.scratch))
            assertAnswer(where, body[hook.at + 2], hook.wait)
            val after = jumpTargets(method)
            assertTrue("$where: the jumps to the meeting point skip the ask", hook.at in after)
            assertTrue("$where: a jump lands past the ask", hook.at + 3 !in after)
        }
    }

    @Test
    fun `each declared build's keep-loading checks ask with their low and high marks`() = bundles { bundle ->
        val context = PatchContexts.of(owners(bundle))
        for (player in Player.entries) {
            val loading = context.loadControl(player).loading
            val where = "${bundle.name}: ${player.label} ${loading.definingClass}->${loading.name}"
            val code = loading.implementation!!.instructions.toList()
            val hook = player.ahead(loading)
            val targets = jumpTargets(loading)
            assertTrue("$where: something jumps into the low mark's ask", hook.lowAt !in targets)
            assertTrue("$where: something jumps into the high mark's ask", hook.highAt !in targets)
            assertNotEquals("$where: the marks share a register", hook.low, hook.high)
            assertTrue("$where: the marks fit the call", maxOf(hook.low, hook.high) + 1 < 16)
            val compares = code.indices.filter { code[it].opcode == Opcode.CMP_LONG && it >= hook.lowAt }
            assertTrue("$where: the low mark is compared after its ask",
                compares.any { (code[it] as ThreeRegisterInstruction).registerC == hook.low })
            assertTrue("$where: the high mark is compared after its ask", compares.any {
                it >= hook.highAt && (code[it] as ThreeRegisterInstruction).registerC == hook.high
            })

            val method = context.mutable(loading)
            method.askAhead(hook)
            val body = method.implementation!!.instructions.toList()
            when (player) {
                Player.HERO -> {
                    assertEquals("$where: both marks are final at once", hook.lowAt, hook.highAt)
                    val store = code[hook.lowAt - 1]
                    assertEquals("$where: the low mark's store", Opcode.IPUT_WIDE, store.opcode)
                    assertEquals("$where: the store's field", hook.kept, (store as ReferenceInstruction).reference)
                    assertEquals("$where: the store's registers", listOf(hook.low, hook.self),
                        (store as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
                    assertRestUnchanged(where, code, body, mapOf(hook.lowAt to 5))
                    assertAsk(where, body[hook.lowAt], AHEAD_LOW, listOf(hook.low, hook.low + 1))
                    assertAnswer(where, body[hook.lowAt + 1], hook.low)
                    assertAsk(where, body[hook.lowAt + 2], AHEAD_HIGH, listOf(hook.low, hook.low + 1, hook.high, hook.high + 1))
                    assertAnswer(where, body[hook.lowAt + 3], hook.high)
                    val kept = body[hook.lowAt + 4]
                    assertEquals("$where: the answer is stored again", Opcode.IPUT_WIDE, kept.opcode)
                    assertEquals("$where: in the same field", hook.kept, (kept as ReferenceInstruction).reference)
                    assertEquals("$where: from the answer", listOf(hook.low, hook.self),
                        (kept as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
                }
                Player.EXO -> {
                    assertTrue("$where: the high mark is final after the low one", hook.highAt > hook.lowAt)
                    assertTrue("$where: the low mark comes from the raise", callsMath(code[hook.lowAt - 2], "max"))
                    assertAnswer("$where: the raise", code[hook.lowAt - 1], hook.low)
                    assertEquals("$where: the high mark is a constant loaded right before its compare", hook.high,
                        (code[hook.highAt - 1] as OneRegisterInstruction).registerA)
                    assertEquals("$where: the high mark's compare", Opcode.CMP_LONG, code[hook.highAt].opcode)
                    assertRestUnchanged(where, code, body, mapOf(hook.lowAt to 2, hook.highAt to 2))
                    assertAsk(where, body[hook.lowAt], AHEAD_LOW, listOf(hook.low, hook.low + 1))
                    assertAnswer(where, body[hook.lowAt + 1], hook.low)
                    assertAsk(where, body[hook.highAt + 2], AHEAD_HIGH,
                        listOf(hook.low, hook.low + 1, hook.high, hook.high + 1))
                    assertAnswer(where, body[hook.highAt + 3], hook.high)
                    assertTrue("$where: the constant before the high mark is still jumped to", hook.highAt + 1 in jumpTargets(method))
                }
            }
        }
    }

    @Test
    fun `the patch asks at all four checks and turns its status on`() = bundles { bundle ->
        val context = PatchContexts.of(owners(bundle))
        val controls = Player.entries.associateWith { context.loadControl(it) }
        videoBufferPatch.execute(context)
        val asks = mutableMapOf<String, Int>()
        for ((player, control) in controls) {
            for ((method, wanted) in listOf(control.start to listOf(START_AT), control.loading to listOf(AHEAD_LOW, AHEAD_HIGH))) {
                val where = "${bundle.name}: ${player.label} ${method.definingClass}->${method.name}"
                val body = context.mutable(method).implementation!!.instructions.toList()
                val made = body.mapNotNull { calls(it)?.toString() }.filter { it.startsWith(VIDEO_BUFFER) }
                assertEquals("$where: the asks", wanted, made)
                made.forEach { asks[it] = (asks[it] ?: 0) + 1 }
            }
        }
        assertEquals("${bundle.name}: each ask once per player", mapOf(START_AT to 2, AHEAD_LOW to 2, AHEAD_HIGH to 2), asks)
        val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "videoBuffer" }
            .implementation!!.instructions.toList()
        assertEquals("${bundle.name}: the status answers yes", 1, (status[0] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(Opcode.RETURN, status[1].opcode)
    }

    private fun refuses(what: String, expected: String, find: () -> Unit) {
        try {
            find()
            fail("$what was hooked")
        } catch (refused: PatchException) {
            assertTrue("$what: ${refused.message}", refused.message.orEmpty().contains(expected))
        }
    }

    @Test
    fun `a check of another shape is refused`() = bundles { bundle ->
        // A start check that writes its wait between the meeting point and the cap.
        PatchContexts.of(owners(bundle)).let { context ->
            val start = context.loadControl(Player.HERO).start
            val hook = startHook(start)
            val code = start.implementation!!.instructions.toList()
            val cap = (code.indexOfFirst(::isTimeUnset) until code.size).first { callsMath(code[it], "min") }
            val method = context.mutable(start)
            method.addInstructions(cap, "const-wide/16 v${hook.wait}, 0x0")
            refuses("${bundle.name}: a wait written after the meeting point", "writes its wait") { startHook(method) }
        }
        // ExoPlayer's check writing its low mark between the raise and the compare.
        PatchContexts.of(owners(bundle)).let { context ->
            val loading = context.loadControl(Player.EXO).loading
            val hook = exoAhead(loading)
            val code = loading.implementation!!.instructions.toList()
            val compare = (hook.lowAt until code.size).first {
                code[it].opcode == Opcode.CMP_LONG && (code[it] as ThreeRegisterInstruction).registerC == hook.low
            }
            val method = context.mutable(loading)
            method.addInstructions(compare, "const-wide/16 v${hook.low}, 0x0")
            refuses("${bundle.name}: a low mark written before its compare", "writes its low mark") { exoAhead(method) }
        }
        // HeroPlayer's check storing a second wide field of its own.
        PatchContexts.of(owners(bundle)).let { context ->
            val loading = context.loadControl(Player.HERO).loading
            val hook = heroAhead(loading)
            val method = context.mutable(loading)
            method.addInstructions(hook.lowAt, "iput-wide v${hook.low}, v${hook.self}, ${hook.kept}")
            refuses("${bundle.name}: two wide stores", "stores 2 wide fields") { heroAhead(method) }
        }
        // A wide store the next compare doesn't read, like 588 store-less's start time, isn't the low mark.
        PatchContexts.of(owners(bundle)).let { context ->
            val loading = context.loadControl(Player.HERO).loading
            val hook = heroAhead(loading)
            val method = context.mutable(loading)
            method.addInstructions(hook.lowAt, "iput-wide v${hook.high}, v${hook.self}, ${hook.kept}")
            val again = heroAhead(method)
            assertEquals("${bundle.name}: the low mark stays the compared one", listOf(hook.low, hook.high), listOf(again.low, again.high))
        }
    }
}
