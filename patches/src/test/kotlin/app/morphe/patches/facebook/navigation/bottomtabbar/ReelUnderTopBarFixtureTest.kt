/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.bottomtabbar

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reel under a top tab bar (#264) on every Facebook build the bundle declares: the content
 * manager is the one class tracing the padding setup, it has exactly two top padding methods (each
 * ends by returning a static (controller, boolean) int answer), and the patch hands each answer to
 * the extension right before its return and changes nothing else. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class ReelUnderTopBarFixtureTest {
    private fun List<Instruction>.opcodes() = map { it.opcode }.filter { it != Opcode.NOP }

    @Test
    fun `each declared build hands the content's top padding to the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val holders = FixtureDex.classesHolding(bundle, CONTENT_PADDING_TRACE)
                    .filterNot { it.type.startsWith(EXTENSION_CLASSES) }.distinctBy { it.type }
                assertEquals("$name: classes tracing \"$CONTENT_PADDING_TRACE\"", 1, holders.size)
                val holder = holders.single()
                val methods = topPaddingMethods(holder)
                assertEquals("$name: top padding methods", 2, methods.size)
                val before = methods.associate { (method, _) -> method.name to method.implementation!!.instructions.toList() }
                // Positive control: each ends by returning the answer of a (controller, boolean) int call.
                for ((method, register) in methods) {
                    val code = before.getValue(method.name)
                    val ask = (code[code.size - 3] as ReferenceInstruction).reference as MethodReference
                    assertEquals("$name: ${method.name} asks a static", Opcode.INVOKE_STATIC, code[code.size - 3].opcode)
                    assertEquals("$name: ${method.name}'s answer", "I", ask.returnType)
                    assertTrue("$name: ${method.name} returns what it asked", (code.last() as OneRegisterInstruction).registerA == register)
                }
                val context = PatchContexts.of(listOf(holder))
                context.hookReelUnderTopBar { why -> throw AssertionError("$name: $why") }
                val patched = context.mutableClassDefBy(holder.type)
                for ((method, register) in methods) {
                    val code = before.getValue(method.name)
                    val after = patched.methods.single { it.name == method.name && it.parameterTypes.isEmpty() }
                        .implementation!!.instructions.toList()
                    assertEquals("$name: ${method.name} gained two instructions", code.opcodes().size + 2, after.opcodes().size)
                    val hand = after[after.size - 3] as FiveRegisterInstruction
                    assertEquals("$name: ${method.name} hands the answer to the extension",
                        listOf(Opcode.INVOKE_STATIC, PAGES_TOP, 1, register),
                        listOf(hand.opcode, ((hand as ReferenceInstruction).reference as MethodReference).toString(), hand.registerCount, hand.registerC))
                    assertEquals("$name: ${method.name} takes the answer back",
                        listOf(Opcode.MOVE_RESULT, register, Opcode.RETURN, register),
                        listOf(after[after.size - 2].opcode, (after[after.size - 2] as OneRegisterInstruction).registerA,
                            after.last().opcode, (after.last() as OneRegisterInstruction).registerA))
                    assertEquals("$name: the rest of ${method.name} is Facebook's", code.opcodes().dropLast(1),
                        after.opcodes().dropLast(3))
                }
            }
        }
    }

    @Test
    fun `the extension has the hook`() {
        val type = PAGES_TOP.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "pagesTop" }
        assertNotNull("the extension has no TabBarScrollAway.pagesTop", hook)
        assertEquals(PAGES_TOP, "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("pagesTop isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }
}
