/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clean up Reels' Fill the screen with reels hook (#453) on every Facebook build the bundle declares:
 * the one static method holding UPDATE_SIZE asks the one static (DIIIZ)[I holding DERIVE_SIZE for the
 * view's size with a six-register range, whose mode and flag it moves there from its own last two
 * parameters and never reads again, and whose answer it reads as four sizes (so that call really is
 * what sizes the view). After the patch the extension is asked with the same six registers right
 * before that call, and a yes sets the mode to fill and the flag off, with only the extension's no
 * jumping onto Facebook's call. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips
 * without it.
 */
class ReelFillFixtureTest {
    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.called() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun MethodReference.isMethod(method: Method) = definingClass == method.definingClass &&
        name == method.name && returnType == method.returnType &&
        parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)

    private fun kind(opcode: Opcode) = if (opcode == Opcode.GOTO_16 || opcode == Opcode.GOTO_32) Opcode.GOTO else opcode

    @Test
    fun `each declared build asks the extension before sizing a player view`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val helperOwners = FixtureDex.classesHolding(bundle, DERIVE_SIZE).filter { !it.type.startsWith(EXTENSION_PACKAGE) }
                val helpers = helperOwners.flatMap { owner -> owner.methods.filter { isSizeHelper(it) && holdsString(it, DERIVE_SIZE) } }
                assertEquals("$name: static (DIIIZ)[I holding \"$DERIVE_SIZE\"", 1, helpers.size)
                val helper = helpers.single()
                val sizingOwners = FixtureDex.classesHolding(bundle, UPDATE_SIZE).filter { !it.type.startsWith(EXTENSION_PACKAGE) }
                val sizings = sizingOwners.flatMap { owner -> owner.methods.filter { isSizing(it) && holdsString(it, UPDATE_SIZE) } }
                assertEquals("$name: static methods holding \"$UPDATE_SIZE\"", 1, sizings.size)
                val sizing = sizings.single()
                val original = sizing.body()
                val site = reelFillSite(sizing, helper)
                val first = site.first

                // Positive control: the mode and flag the helper gets are the sizing method's own last
                // two parameters, moved into the range, and the helper's answer is read as four sizes.
                val registers = sizing.implementation!!.registerCount
                listOf(first + 4 to registers - 2, first + 5 to registers - 1).forEach { (register, parameter) ->
                    val writers = sizing.writersReaching(site.call, register)
                    assertEquals("$name: v$register at the call has one writer", 1, writers.size)
                    val move = original[writers.single()]
                    assertTrue("$name: v$register isn't moved from p-register v$parameter",
                        move.opcode in setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16) &&
                            (move as TwoRegisterInstruction).registerB == parameter)
                }
                assertEquals("$name: the helper's answer isn't taken", Opcode.MOVE_RESULT_OBJECT, original[site.call + 1].opcode)
                val sizes = (original[site.call + 1] as OneRegisterInstruction).registerA
                assertEquals("$name: sizes read off the helper's answer", 4,
                    original.count { it.opcode == Opcode.AGET && (it as ThreeRegisterInstruction).registerB == sizes })

                val owners = (helperOwners + sizingOwners).distinctBy { it.type }
                val pool = (owners + ExtensionDex.classDef(REEL_FILL)).distinctBy { it.type }
                val context = PatchContexts.of(pool)
                context.fillReels()
                val patched = context.mutableClassDefBy(sizing.definingClass).methods.single { it.name == sizing.name &&
                    it.parameterTypes.map(CharSequence::toString) == sizing.parameterTypes.map(CharSequence::toString) }.body()
                val at = site.call
                val ask = patched[at]
                assertEquals("$name: the extension isn't asked before the call", REEL_FILL_FILLS, ask.called()?.let(::described))
                ask as RegisterRangeInstruction
                assertEquals("$name: the extension gets the call's registers", listOf(6, first), listOf(ask.registerCount, ask.startRegister))
                assertEquals("$name: the answer isn't taken", Opcode.MOVE_RESULT, patched[at + 1].opcode)
                assertEquals("$name: the answer register", site.answer, (patched[at + 1] as OneRegisterInstruction).registerA)
                assertEquals("$name: no test of the answer", Opcode.IF_EQZ, patched[at + 2].opcode)
                assertEquals("$name: the test reads another register", site.answer, (patched[at + 2] as OneRegisterInstruction).registerA)
                listOf(at + 3 to (first + 4 to 1), at + 4 to (first + 5 to 0)).forEach { (index, expected) ->
                    val set = patched[index]
                    assertEquals("$name: $index isn't a const", Opcode.CONST_16, set.opcode)
                    assertEquals("$name: $index sets", expected,
                        (set as OneRegisterInstruction).registerA to (set as NarrowLiteralInstruction).narrowLiteral)
                }
                assertTrue("$name: Facebook's call isn't right after the hook", patched[at + 5].called()?.isMethod(helper) == true)

                // The no jumps onto Facebook's call, and nothing else lands inside the hook.
                val addresses = patched.runningFold(0) { address, instruction -> address + instruction.codeUnits }
                val branches = patched.withIndex().filter { it.value is OffsetInstruction }
                    .map { (index, branch) -> index to addresses.indexOf(addresses[index] + (branch as OffsetInstruction).codeOffset) }
                assertTrue("$name: the hook's test doesn't land on the call", (at + 2 to at + 5) in branches)
                branches.filter { it.first != at + 2 }.forEach { (from, to) ->
                    assertTrue("$name: the branch at $from lands inside the hook at $to", to !in at + 1..at + 5)
                }
                assertEquals("$name: the rest is Facebook's",
                    original.map { kind(it.opcode) }.filter { it != Opcode.NOP },
                    patched.filterIndexed { index, _ -> index !in at until at + 5 }.map { kind(it.opcode) }.filter { it != Opcode.NOP })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has the hook`() {
        val hook = ExtensionDex.classDef(REEL_FILL).methods.singleOrNull { described(it) == REEL_FILL_FILLS }
        assertNotNull("the extension has no $REEL_FILL_FILLS", hook)
        assertTrue("the hook isn't public static",
            AccessFlags.STATIC.isSet(hook!!.accessFlags) && AccessFlags.PUBLIC.isSet(hook.accessFlags))
    }
}
