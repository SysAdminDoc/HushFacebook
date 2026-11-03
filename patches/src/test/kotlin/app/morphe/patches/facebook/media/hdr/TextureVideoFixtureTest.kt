/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.hdr

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fix washed-out video colors (#308) on each declared Facebook build: the player's one
 * must-use-a-SurfaceView decision answers through the extension at every return, a jump to a
 * return landing on the hook, and each read of the player's TextureView preference behind the
 * decision's no answers through the extension right after its move-result, with nothing else in
 * those methods changed.
 */
class TextureVideoFixtureTest {
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

    private fun calls(instruction: Instruction, own: String) =
        ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == own

    private fun range(instruction: Instruction) = (instruction as RegisterRangeInstruction).let {
        (it.startRegister until it.startRegister + it.registerCount).toList()
    }

    private fun five(instruction: Instruction) = (instruction as FiveRegisterInstruction).let {
        listOf(it.registerC, it.registerD, it.registerE, it.registerF, it.registerG).take(it.registerCount)
    }

    /** The opcodes of [body] with each hook of [own] and the move-result after it left out. */
    private fun withoutHooks(body: List<Instruction>, own: String): List<Opcode> {
        val hooks = body.indices.filter { calls(body[it], own) }
        return body.filterIndexed { index, _ -> index !in hooks && index - 1 !in hooks }.map { it.opcode }
    }

    private val payloadPointers = setOf(Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH, Opcode.FILL_ARRAY_DATA)

    /** The instructions in [body] a goto or an if lands on. */
    private fun jumpTargets(body: List<Instruction>): List<Instruction> {
        val addresses = body.runningFold(0) { at, instruction -> at + instruction.codeUnits }
        return body.indices.mapNotNull { at ->
            val jump = body[at] as? OffsetInstruction ?: return@mapNotNull null
            if (jump.opcode in payloadPointers) return@mapNotNull null
            body.getOrNull(addresses.indexOf(addresses[at] + jump.codeOffset))
        }
    }

    /**
     * Checks the hooked decision: each return reads the extension's answer, given with the player's
     * origin in [origin], and no jump skips one.
     */
    private fun assertDecisionHooked(where: String, original: Method, origin: Int, body: List<Instruction>) {
        val code = original.implementation!!.instructions.toList()
        val returns = surfaceAnswers(original).map { it.second }
        val hooked = body.indices.filter { body[it].opcode == Opcode.RETURN }
        assertEquals("$where: the returns", returns, hooked.map { (body[it] as OneRegisterInstruction).registerA })
        for (at in hooked) {
            val register = (body[at] as OneRegisterInstruction).registerA
            assertTrue("$where: the hook before the return at $at", calls(body[at - 2], OWN_SURFACE_VIEW))
            assertEquals("$where: the answer and the origin handed over at $at", listOf(register, origin), five(body[at - 2]))
            assertEquals("$where: read back at $at", Opcode.MOVE_RESULT, body[at - 1].opcode)
            assertEquals("$where: into the returned register at $at", register, (body[at - 1] as OneRegisterInstruction).registerA)
        }
        assertTrue("$where: a jump lands past a hook", jumpTargets(body).none { it.opcode == Opcode.RETURN })
        assertEquals("$where: nothing else changed", code.map { it.opcode }, withoutHooks(body, OWN_SURFACE_VIEW))
    }

    /** Checks a hooked reader: each preference read hands its result to the extension and reads the answer back. */
    private fun assertReadsHooked(where: String, original: Method, reads: Int, body: List<Instruction>) {
        val code = original.implementation!!.instructions.toList()
        val hooks = body.indices.filter { calls(body[it], OWN_PREFER_TEXTURE_VIEW) }
        assertEquals("$where: reads hooked", reads, hooks.size)
        for (at in hooks) {
            val read = (body[at - 1] as OneRegisterInstruction).registerA
            assertEquals("$where: after a move-result at $at", Opcode.MOVE_RESULT, body[at - 1].opcode)
            assertTrue("$where: of the preference's get at $at", ((body[at - 2] as ReferenceInstruction).reference as MethodReference)
                .let { it.definingClass == ATOMIC_BOOLEAN && it.name == "get" })
            assertEquals("$where: the read handed over at $at", listOf(read), range(body[at]))
            assertEquals("$where: read back at $at", Opcode.MOVE_RESULT, body[at + 1].opcode)
            assertEquals("$where: into the same register at $at", read, (body[at + 1] as OneRegisterInstruction).registerA)
        }
        assertEquals("$where: nothing else changed", code.map { it.opcode }, withoutHooks(body, OWN_PREFER_TEXTURE_VIEW))
    }

    @Test
    fun `each declared build's player is turned to its TextureView`() = bundles { bundle ->
        val decisions = FixtureDex.methodsWhere(bundle, { true }, ::isSurfaceDecision)
        // 585 (2026-10-30): one (five arguments)Z method of the video config helper.
        assertEquals("${bundle.name}: surface decisions", 1, decisions.size)
        val decision = decisions.single()
        // Four returns, three of them jump targets.
        assertTrue("${bundle.name}: ${surfaceAnswers(decision).size} answers", surfaceAnswers(decision).size >= 2)
        val readers = FixtureDex.methodsWhere(bundle, { true }) { preferenceReads(it, decision).isNotEmpty() }
        val reads = readers.associateWith { preferenceReads(it, decision) }
        // The player's attach and its set-up, both reading the same AtomicBoolean of the player.
        assertTrue("${bundle.name}: ${reads.values.sumOf { it.size }} preference reads", reads.values.sumOf { it.size } >= 2)
        assertEquals("${bundle.name}: one preference", 1, reads.values.flatten().map { it.field }.toSet().size)

        // 585: the origin is the second of the five arguments, p2 of a virtual method in v10 (#250).
        val origin = originRegister(decision)
        assertTrue("${bundle.name}: the decision takes no player origin", origin != null)

        val owners = FixtureDex.classes(bundle, (readers.map { it.definingClass } + decision.definingClass).toSet())
        val context = PatchContexts.of(owners.values)
        val hooks = context.textureHooks()
        assertTrue("${bundle.name}: the decision found", same(decision, hooks.decision))
        assertEquals("${bundle.name}: the origin found", origin, hooks.origin)
        assertEquals("${bundle.name}: the reads found", reads.values.sumOf { it.size }, hooks.reads.size)
        assertEquals("${bundle.name}: places hooked", surfaceAnswers(decision).size + hooks.reads.size, context.hookTextureVideo(hooks))

        fun body(method: Method) = context.mutableClassDefBy(method.definingClass).methods.single { same(it, method) }
            .implementation!!.instructions.toList()
        assertDecisionHooked("${bundle.name}: the decision", decision, hooks.origin, body(decision))
        for ((reader, inReader) in reads) {
            assertReadsHooked("${bundle.name}: ${reader.definingClass}->${reader.name}", reader, inReader.size, body(reader))
        }
    }

    private fun method(type: String, parameters: List<String>, returnType: String, registers: Int, smali: String): MutableMethod =
        MutableMethod(
            ImmutableMethod(
                type,
                "run",
                parameters.map { ImmutableMethodParameter(it, null, null) },
                returnType,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        ).apply { addInstructions(0, smali) }

    private fun type(name: String, vararg methods: Method): ClassDef =
        ImmutableClassDef(name, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods.toList())

    /** A decision taking a config and the player's origin (p1, in v3); [writesOrigin] reuses p1 as a local. */
    private fun decision(origin: Boolean = true, writesOrigin: Boolean = false) = type(
        "LX/decision;",
        method(
            "LX/decision;", listOf("Ljava/lang/String;", if (origin) PLAYER_ORIGIN else "Ljava/lang/Object;"), "Z", 4,
            """
                const/4 v0, 0x0
                const-string v1, "$FORCE_TEXTURE_FOR_BLUR"
                ${if (writesOrigin) "const/4 p1, 0x0" else "nop"}
                if-eqz p0, :answer
                const/4 v0, 0x1
                :answer
                return v0
            """.trimIndent(),
        ),
    )

    private fun player(readsPreference: Boolean) = type(
        "LX/player;",
        method(
            "LX/player;", listOf("LX/player;"), "Z", 3,
            """
                const/4 v0, 0x0
                invoke-static { v0, v0 }, LX/decision;->run(Ljava/lang/String;$PLAYER_ORIGIN)Z
                move-result v0
                if-eqz v0, :no
                const/4 v0, 0x0
                return v0
                :no
                ${if (readsPreference) "iget-object v1, p0, LX/player;->texture:$ATOMIC_BOOLEAN" else "const/4 v1, 0x0"}
                invoke-virtual { v1 }, $ATOMIC_BOOLEAN->get()Z
                move-result v0
                return v0
            """.trimIndent(),
        ),
    )

    @Test
    fun `a jump to the decision's return lands on the hook, and the read behind its no is hooked`() {
        val context = PatchContexts.of(listOf(decision(), player(readsPreference = true)))
        val hooks = context.textureHooks()
        assertEquals(1, hooks.reads.size)
        assertEquals("LX/player;->texture:$ATOMIC_BOOLEAN", hooks.reads.single().field)
        assertEquals("the origin's register", 3, hooks.origin)
        assertEquals(2, context.hookTextureVideo(hooks))
        val decided = context.mutableClassDefBy("LX/decision;").methods.single().implementation!!.instructions.toList()
        assertDecisionHooked("decision", decision().methods.single(), 3, decided)
        assertEquals(Opcode.INVOKE_STATIC, jumpTargets(decided).single().opcode)
        val read = context.mutableClassDefBy("LX/player;").methods.single().implementation!!.instructions.toList()
        assertReadsHooked("player", player(readsPreference = true).methods.single(), 1, read)
    }

    @Test
    fun `a build without the decision, or without a preference read behind its no, is refused`() {
        assertThrows(PatchException::class.java) { PatchContexts.of(listOf(player(readsPreference = true))).surfaceDecision() }
        assertThrows(PatchException::class.java) { PatchContexts.of(listOf(decision(), player(readsPreference = false))).textureHooks() }
        assertThrows(PatchException::class.java) { PatchContexts.of(listOf(decision())).textureHooks() }
    }

    @Test
    fun `a decision without the player's origin, or one that writes over it, is refused`() {
        assertThrows(PatchException::class.java) {
            PatchContexts.of(listOf(decision(origin = false), player(readsPreference = true))).textureHooks()
        }
        assertThrows(PatchException::class.java) {
            PatchContexts.of(listOf(decision(writesOrigin = true), player(readsPreference = true))).textureHooks()
        }
    }
}
