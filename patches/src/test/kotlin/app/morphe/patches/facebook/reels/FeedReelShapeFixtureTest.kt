/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.ads.affiliate.writesRegister
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clean up Reels' Show feed reels uncropped hook (#581) on every Facebook build the bundle declares:
 * one static (FbUserSession, ?, ?, ?, F)F sits beside FEED_REEL_SPEC, keeps the video's shape in its
 * last parameter to the end, and the feed reel component asks it. After the patch every return hands
 * the answer and the video's shape to the extension and returns what comes back, and no branch skips
 * the hook. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class FeedReelShapeFixtureTest {
    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.called() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun MethodReference.isMethod(method: Method) = definingClass == method.definingClass &&
        name == method.name && returnType == method.returnType &&
        parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)

    private fun kind(opcode: Opcode) = if (opcode == Opcode.GOTO_16 || opcode == Opcode.GOTO_32) Opcode.GOTO else opcode

    @Test
    fun `each declared build hands every answer of the feed reel frame helper to the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val owners = FixtureDex.classesHolding(bundle, FEED_REEL_SPEC).filter { !it.type.startsWith(EXTENSION_PACKAGE) }
                val helpers = feedReelShapers(owners)
                assertEquals("$name: static (FbUserSession, ?, ?, ?, F)F beside \"$FEED_REEL_SPEC\"", 1, helpers.size)
                val helper = helpers.single()
                val original = helper.body()
                val video = feedReelVideoRegister(helper)

                // Positive controls: the video's shape is read and never written, and the feed reel
                // component that names the spec asks the helper.
                assertTrue("$name: the helper never reads the video's shape in v$video",
                    original.any { video in registersRead(it) })
                assertTrue("$name: the helper writes v$video", original.none { writesRegister(it, video) })
                val callers = owners.flatMap { it.methods }.filter { caller -> caller.body().any { it.called()?.isMethod(helper) == true } }
                assertTrue("$name: nothing beside \"$FEED_REEL_SPEC\" asks the helper", callers.isNotEmpty())

                val context = PatchContexts.of((owners + ExtensionDex.classDef(FEED_REEL_SHAPE)).distinctBy { it.type })
                context.uncropFeedReels()
                val patched = context.mutableClassDefBy(helper.definingClass).methods.single { it.name == helper.name &&
                    it.parameterTypes.map(CharSequence::toString) == helper.parameterTypes.map(CharSequence::toString) }.body()

                val returns = patched.withIndex().filter { it.value.opcode == Opcode.RETURN }.map { it.index }
                assertEquals("$name: returns", original.count { it.opcode == Opcode.RETURN }, returns.size)
                val hooked = mutableSetOf<Int>()
                for (index in returns) {
                    val answer = (patched[index] as OneRegisterInstruction).registerA
                    val ask = patched[index - 2]
                    assertEquals("$name: the return at $index isn't handed to the extension",
                        FEED_REEL_SHAPE_HOOK, ask.called()?.let(::described))
                    ask as FiveRegisterInstruction
                    assertEquals("$name: the extension gets the answer and the video's shape",
                        listOf(answer, video), listOf(ask.registerC, ask.registerD).take(ask.registerCount))
                    assertEquals("$name: the extension's answer isn't returned", Opcode.MOVE_RESULT, patched[index - 1].opcode)
                    assertEquals("$name: the extension's answer register", answer, (patched[index - 1] as OneRegisterInstruction).registerA)
                    hooked += index - 1
                    hooked += index
                }

                // Every way to a return goes through the extension: no branch lands past the ask.
                val addresses = patched.runningFold(0) { address, instruction -> address + instruction.codeUnits }
                patched.withIndex().filter { it.value is OffsetInstruction }.forEach { (index, branch) ->
                    val to = addresses.indexOf(addresses[index] + (branch as OffsetInstruction).codeOffset)
                    assertTrue("$name: the branch at $index skips the hook at $to", to !in hooked)
                }
                val asks = returns.map { it - 2 }.toSet() + returns.map { it - 1 }
                assertEquals("$name: the rest is Facebook's",
                    original.map { kind(it.opcode) }.filter { it != Opcode.NOP },
                    patched.withIndex().filter { it.index !in asks }.map { kind(it.value.opcode) }.filter { it != Opcode.NOP })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has the hook`() {
        val hook = ExtensionDex.classDef(FEED_REEL_SHAPE).methods.singleOrNull { described(it) == FEED_REEL_SHAPE_HOOK }
        assertNotNull("the extension has no $FEED_REEL_SHAPE_HOOK", hook)
        assertTrue("the hook isn't public static",
            AccessFlags.STATIC.isSet(hook!!.accessFlags) && AccessFlags.PUBLIC.isSet(hook.accessFlags))
    }

    /** The registers [instruction] reads, as far as this test needs: invoke arguments and the operands of the rest. */
    private fun registersRead(instruction: Instruction): List<Int> = when (instruction) {
        is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE,
            instruction.registerF, instruction.registerG).take(instruction.registerCount)
        is ThreeRegisterInstruction -> listOf(instruction.registerB, instruction.registerC)
        is TwoRegisterInstruction -> listOf(instruction.registerB)
        else -> emptyList()
    }
}
