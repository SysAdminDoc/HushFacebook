/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.cleanup

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clean up the story viewer on every declared build and ABI: one component names itself as the
 * credit line, one as the turning credit line and one as the reply suggestions, each with a render.
 * After the patch each render asks the extension first, and on a yes the credit line and the reply
 * suggestions answer null while the turning line answers the plain subtitle its constructor kept,
 * unless Facebook marked the story with one of its labels. Each progress listener hands the
 * extension its segments and its own progress argument right after it moves them, where only that
 * move reaches. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class StoryViewerCleanupFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun Method.code() = implementation!!.instructions.toList()
    private fun Instruction.reference() = (this as ReferenceInstruction).reference.toString()

    /** Opcodes past the NOP that pads a switch payload, which moves when the code above it grows. */
    private fun List<Instruction>.unpadded() = map { it.opcode }.filter { it != Opcode.NOP }

    private fun component(bundle: File, name: String): ClassDef {
        val found = FixtureDex.classesHolding(bundle, name).filterNot { it.type.startsWith(EXTENSION_PACKAGE) }
            .distinctBy { it.type }.filter { isNamedComponent(it, name) }
        assertEquals("${bundle.name}: components naming themselves $name", 1, found.size)
        return found.single()
    }

    /** Whether every branch in the first [size] instructions of [code] goes to the one right after them. */
    private fun branchesPast(code: List<Instruction>, size: Int): Boolean {
        val past = code.take(size).sumOf { it.codeUnits }
        var at = 0
        return code.take(size).all { instruction ->
            val lands = instruction !is OffsetInstruction || at + instruction.codeOffset == past
            at += instruction.codeUnits
            lands
        }
    }

    /** Whether [code] answers null somewhere: a zero loaded into a register that's returned next. */
    private fun answersNull(code: List<Instruction>) = code.zipWithNext().any { (load, ret) ->
        load.opcode == Opcode.CONST_4 && (load as NarrowLiteralInstruction).narrowLiteral == 0 &&
            ret.opcode == Opcode.RETURN_OBJECT && (ret as OneRegisterInstruction).registerA == (load as OneRegisterInstruction).registerA
    }

    @Test
    fun `each declared build's credit lines and reply suggestions ask the extension first`() {
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val creditLine = component(bundle, CREDIT_LINE)
                val rotating = component(bundle, ROTATING_CREDIT_LINE)
                val suggestions = component(bundle, REPLY_SUGGESTIONS)

                // Positive controls: Facebook's own render of the reply suggestions answers null for a
                // story without them, which goes for the other two as well since all three are one
                // kind of component, and the turning line's label flags are booleans of its own.
                assertTrue("$name: the reply suggestions never answer null themselves",
                    answersNull(renderOf(suggestions)!!.code()))
                assertEquals("$name: the three aren't one kind of component", 1,
                    listOf(creditLine, rotating, suggestions).map { it.superclass }.distinct().size)
                val plain = plainSubtitleField(rotating)
                assertNotNull("$name: the turning credit line keeps no plain subtitle from its first argument", plain)
                val flags = labelFlags(rotating)
                assertNotNull("$name: the turning credit line tests no flag before each of its labels", flags)
                assertTrue("$name: a label flag isn't a boolean the turning credit line declares", flags!!.all { flag ->
                    rotating.fields.any { it.name == flag.name && it.type == "Z" && !AccessFlags.STATIC.isSet(it.accessFlags) }
                })
                assertTrue("$name: the credit line has a plain subtitle field too, so it isn't the one-credit line",
                    plainSubtitleField(creditLine) == null)

                val originals = listOf(creditLine, rotating, suggestions).associate { it.type to renderOf(it)!!.code() }
                val context = PatchContexts.of(listOf(creditLine, rotating, suggestions))
                context.cleanUpStoryViewer()
                fun patched(classDef: ClassDef) = context.mutableClassDefBy(classDef.type).methods.single {
                    it.name == "render" && it.parameterTypes.size == 1
                }.code()

                for ((classDef, hook) in listOf(creditLine to HIDE_CREDITS, suggestions to HIDE_REPLY_SUGGESTIONS)) {
                    val code = patched(classDef)
                    assertEquals("$name: ${classDef.type} answers null on a yes",
                        listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN_OBJECT),
                        code.take(5).map { it.opcode })
                    assertEquals("$name: ${classDef.type} asks its own switch", hook, code[0].reference())
                    assertTrue("$name: ${classDef.type}'s early answer isn't null", answersNull(code.subList(3, 5)))
                    assertTrue("$name: ${classDef.type}'s no doesn't go to Facebook's render", branchesPast(code, 5))
                    assertEquals("$name: the rest of ${classDef.type} is Facebook's",
                        originals.getValue(classDef.type).unpadded(), code.drop(5).unpadded())
                }

                val turning = patched(rotating)
                val prefix = listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.MOVE_OBJECT_FROM16) +
                    flags.flatMap { listOf(Opcode.IGET_BOOLEAN, Opcode.IF_NEZ) } + listOf(Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT)
                assertEquals("$name: the turning line answers its plain subtitle on a yes for a story with no label",
                    prefix, turning.take(prefix.size).map { it.opcode })
                assertEquals("$name: it asks the credits switch", HIDE_CREDITS, turning[0].reference())
                flags.forEachIndexed { at, flag ->
                    assertEquals("$name: and tests the ${STORY_LABELS[at]} flag", "${flag.definingClass}->${flag.name}:${flag.type}",
                        turning[4 + 2 * at].reference())
                }
                assertEquals("$name: and reads the kept subtitle", "${rotating.type}->${plain!!.name}:${plain.type}",
                    turning[prefix.size - 2].reference())
                assertTrue("$name: a no or a label doesn't go to Facebook's render", branchesPast(turning, prefix.size))
                val render = context.mutableClassDefBy(rotating.type).methods.single { it.name == "render" }
                val self = render.implementation!!.registerCount - 2
                assertEquals("$name: from this", self, (turning[3] as TwoRegisterInstruction).registerB)
                assertEquals("$name: the rest of the turning line is Facebook's",
                    originals.getValue(rotating.type).unpadded(), turning.drop(prefix.size).unpadded())
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** The code-unit offset each instruction of [code] starts at. */
    private fun offsets(code: List<Instruction>): List<Int> = code.runningFold(0) { at, it -> at + it.codeUnits }.dropLast(1)

    @Test
    fun `each declared build's progress listeners hand over the segments and progress after moving them`() {
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val listeners = FixtureDex.classesHolding(bundle, PROGRESS_TRACE)
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                val looked = mutableMapOf<String, ClassDef?>()
                val lookup = { type: String ->
                    if (type in looked) looked[type] else FixtureDex.classes(bundle, setOf(type))[type].also { looked[type] = it }
                }
                val methods = listeners.flatMap { listener ->
                    listener.methods.filter { holdsString(it, PROGRESS_TRACE) }.map { listener to it }
                }
                val moves = methods.associate { (_, method) -> method to segmentsMove(method, lookup) }

                // Positive controls: one listener moves a view of segments through its interface and one a
                // holder of the segments drawable, and neither writes over the progress it was handed.
                assertEquals("$name: the progress listeners", 2, methods.size)
                assertTrue("$name: a listener doesn't move its segments once, right before it returns",
                    moves.values.all { it != null })
                assertEquals("$name: listeners moving a view of segments", 1, moves.values.count { it!!.drawable == null })
                assertEquals("$name: listeners moving a holder of the segments drawable", 1,
                    moves.values.count { it!!.drawable != null })
                assertTrue("$name: a listener writes over its progress",
                    methods.all { (_, method) -> keeps(method, lastArgument(method)) })

                val context = PatchContexts.of((listeners + looked.values.filterNotNull()).distinctBy { it.type })
                context.followStoryProgress()
                for ((listener, method) in methods) {
                    val move = moves.getValue(method)!!
                    val where = "$name: ${listener.type}->${method.name}"
                    val patchedMethod = context.mutableClassDefBy(listener.type).methods.single {
                        it.name == method.name && it.parameterTypes.map(CharSequence::toString) ==
                            method.parameterTypes.map(CharSequence::toString)
                    }
                    val original = method.code()
                    val code = patchedMethod.code()
                    val hook = listOf(Opcode.MOVE_OBJECT_FROM16) +
                        (if (move.drawable != null) listOf(Opcode.IGET_OBJECT) else emptyList()) +
                        listOf(Opcode.MOVE_FROM16, Opcode.INVOKE_STATIC)
                    val start = move.index + 1
                    assertEquals("$where: Facebook's code up to the move is kept",
                        original.take(start).unpadded(), code.take(start).unpadded())
                    assertEquals("$where: hands over the segments and progress right before it returns",
                        hook + Opcode.RETURN_VOID, code.subList(start, start + hook.size + 1).map { it.opcode })
                    assertEquals("$where: from the register the move went to", move.register,
                        (code[start] as TwoRegisterInstruction).registerB)
                    assertEquals("$where: the segments into v0", 0, (code[start] as TwoRegisterInstruction).registerA)
                    move.drawable?.let {
                        val read = code[start + 1] as TwoRegisterInstruction
                        assertEquals("$where: reading the segments drawable out of the holder",
                            "${it.definingClass}->${it.name}:${it.type}", code[start + 1].reference())
                        assertEquals("$where: from the holder in v0 into v0", listOf(0, 0), listOf(read.registerA, read.registerB))
                    }
                    // The progress is the last parameter's register, counted from the parameters: past the
                    // locals, `this` and every parameter before it, a wide one taking two.
                    val words = { type: CharSequence -> if (type.toString() == "J" || type.toString() == "D") 2 else 1 }
                    val parameters = patchedMethod.parameterTypes
                    val progress = patchedMethod.implementation!!.registerCount - (1 + parameters.sumOf(words)) + 1 +
                        parameters.dropLast(1).sumOf(words)
                    val handed = code[start + hook.size - 2] as TwoRegisterInstruction
                    assertEquals("$where: with the progress it was handed", progress, handed.registerB)
                    assertEquals("$where: the progress into v1", 1, handed.registerA)
                    val call = code[start + hook.size - 1] as FiveRegisterInstruction
                    assertEquals("$where: to the extension", SEGMENTS_MOVED, code[start + hook.size - 1].reference())
                    assertEquals("$where: handing over v0 and v1", listOf(2, 0, 1),
                        listOf(call.registerCount, call.registerC, call.registerD))
                    assertEquals("$where: the rest is Facebook's",
                        original.drop(start).unpadded(), code.drop(start + hook.size).unpadded())
                    val at = offsets(code)
                    val inserted = (start until start + hook.size).map { at[it] }.toSet()
                    assertTrue("$where: a branch lands in the hook, so it runs without the move", code.indices.none { i ->
                        val branch = code[i] as? OffsetInstruction
                        branch != null && at[i] + branch.codeOffset in inserted
                    })
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has every hook`() {
        for ((owner, hook) in listOf(STORY_VIEWER_CLEANUP to HIDE_CREDITS, STORY_VIEWER_CLEANUP to HIDE_REPLY_SUGGESTIONS,
            STORY_PROGRESS_BAR to SEGMENTS_MOVED)) {
            val method = ExtensionDex.classDef(owner).methods.singleOrNull {
                "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook
            }
            assertNotNull("the extension has no $hook", method)
            assertTrue("$hook isn't static", AccessFlags.STATIC.isSet(method!!.accessFlags))
        }
    }
}
