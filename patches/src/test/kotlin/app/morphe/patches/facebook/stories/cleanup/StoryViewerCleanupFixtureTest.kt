/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.cleanup

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
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
 * unless Facebook marked the story with one of its labels.
 * Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
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

    @Test
    fun `the extension has both hooks`() {
        for (hook in listOf(HIDE_CREDITS, HIDE_REPLY_SUGGESTIONS)) {
            val method = ExtensionDex.classDef(STORY_VIEWER_CLEANUP).methods.singleOrNull {
                "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook
            }
            assertNotNull("the extension has no $hook", method)
            assertTrue("$hook isn't static", AccessFlags.STATIC.isSet(method!!.accessFlags))
        }
    }
}
