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
import app.morphe.patches.facebook.media.taptoplay.FRAGMENT_ACTIVITY
import app.morphe.patches.facebook.media.taptoplay.touchDispatches
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clean up Reels' Fill the screen with reels hook (#453, #456) on every Facebook build the bundle
 * declares: the one static (DIIIZ)[I holding DERIVE_SIZE reads its mode and flag, and both the method
 * that sizes a player view and the video surface's own resize ask it (closing a reel's comment sheet
 * resizes the surface, which brought the bars back under a filled view). After the patch the helper
 * asks the extension first thing with its own six parameter registers, and a yes sets the mode to
 * fill and the flag off, with only the extension's no jumping onto Facebook's first instruction.
 * Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class ReelFillFixtureTest {
    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.called() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun MethodReference.isMethod(method: Method) = definingClass == method.definingClass &&
        name == method.name && returnType == method.returnType &&
        parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)

    private fun kind(opcode: Opcode) = if (opcode == Opcode.GOTO_16 || opcode == Opcode.GOTO_32) Opcode.GOTO else opcode

    /** Every register [instruction] reads or writes, as far as this test needs to know. */
    private fun registersOf(instruction: Instruction): List<Int> = when (instruction) {
        is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
        is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE,
            instruction.registerF, instruction.registerG).take(instruction.registerCount)
        is ThreeRegisterInstruction -> listOf(instruction.registerA, instruction.registerB, instruction.registerC)
        is TwoRegisterInstruction -> listOf(instruction.registerA, instruction.registerB)
        is OneRegisterInstruction -> listOf(instruction.registerA)
        else -> emptyList()
    }

    @Test
    fun `each declared build asks the extension first thing in the size helper`() {
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
                val original = helper.body()
                val first = reelFillFirst(helper)

                // Positive controls: the helper reads the mode and the flag the hook sets, and both the
                // player view's sizing and the surface's own resize ask it.
                listOf(first + 4, first + 5).forEach { register ->
                    assertTrue("$name: the helper never reads v$register", original.any { register in registersOf(it) })
                }
                listOf(PLAYER_VIEW_SIZING, SURFACE_RESIZE).forEach { literal ->
                    val callers = FixtureDex.classesHolding(bundle, literal).filter { !it.type.startsWith(EXTENSION_PACKAGE) }
                        .flatMap { it.methods }.filter { holdsString(it, literal) }
                        .filter { caller -> caller.body().any { it.called()?.isMethod(helper) == true } }
                    assertEquals("$name: methods holding \"$literal\" that ask the helper", 1, callers.size)
                }

                val context = PatchContexts.of((helperOwners + ExtensionDex.classDef(REEL_FILL)).distinctBy { it.type })
                context.fillReels()
                val patched = context.mutableClassDefBy(helper.definingClass).methods.single { it.name == helper.name &&
                    it.parameterTypes.map(CharSequence::toString) == helper.parameterTypes.map(CharSequence::toString) }.body()
                val ask = patched[0]
                assertEquals("$name: the extension isn't asked first", REEL_FILL_FILLS, ask.called()?.let(::described))
                ask as RegisterRangeInstruction
                assertEquals("$name: the extension gets the helper's parameters", listOf(6, first), listOf(ask.registerCount, ask.startRegister))
                assertEquals("$name: the answer isn't taken", Opcode.MOVE_RESULT, patched[1].opcode)
                assertEquals("$name: the answer register", 0, (patched[1] as OneRegisterInstruction).registerA)
                assertEquals("$name: no test of the answer", Opcode.IF_EQZ, patched[2].opcode)
                assertEquals("$name: the test reads another register", 0, (patched[2] as OneRegisterInstruction).registerA)
                listOf(3 to (first + 4 to 1), 4 to (first + 5 to 0)).forEach { (index, expected) ->
                    val set = patched[index]
                    assertEquals("$name: $index isn't a const", Opcode.CONST_16, set.opcode)
                    assertEquals("$name: $index sets", expected,
                        (set as OneRegisterInstruction).registerA to (set as NarrowLiteralInstruction).narrowLiteral)
                }

                // The no jumps onto Facebook's first instruction, and nothing else lands inside the hook.
                val addresses = patched.runningFold(0) { address, instruction -> address + instruction.codeUnits }
                val branches = patched.withIndex().filter { it.value is OffsetInstruction }
                    .map { (index, branch) -> index to addresses.indexOf(addresses[index] + (branch as OffsetInstruction).codeOffset) }
                assertTrue("$name: the hook's test doesn't land on Facebook's first instruction", (2 to 5) in branches)
                branches.filter { it.first != 2 }.forEach { (from, to) ->
                    assertTrue("$name: the branch at $from lands inside the hook at $to", to !in 1..4)
                }
                assertEquals("$name: the rest is Facebook's",
                    original.map { kind(it.opcode) }.filter { it != Opcode.NOP },
                    patched.drop(5).map { kind(it.opcode) }.filter { it != Opcode.NOP })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * A pinch on a reel (#466): FbFragmentActivity's one touch dispatch asks the extension first
     * with the screen and the event, a yes returns true in v0, and a no goes on to Facebook's own
     * first instruction.
     */
    @Test
    fun `each declared build asks the extension first thing in the touch dispatch`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val activity = FixtureDex.classes(bundle, setOf(FRAGMENT_ACTIVITY))[FRAGMENT_ACTIVITY]
                assertNotNull("$name: no $FRAGMENT_ACTIVITY", activity)
                val original = touchDispatches(activity!!).singleOrNull()
                assertNotNull("$name: touch dispatches", original)

                val context = PatchContexts.of(listOf(activity))
                context.pinchReels()
                val dispatch = context.mutableClassDefBy(FRAGMENT_ACTIVITY).methods.single {
                    it.name == original!!.name && it.returnType == original.returnType &&
                        it.parameterTypes.map(CharSequence::toString) == original.parameterTypes.map(CharSequence::toString)
                }
                val touch = dispatch.body()
                val locals = dispatch.localRegisterCount()
                assertEquals("$name: the pinch isn't asked first", REEL_FIT_PINCH_TOUCH, touch[0].called()?.let(::described))
                assertEquals("$name: the pinch gets the screen and the event", listOf(locals, locals + 1), registersOf(touch[0]))
                assertEquals("$name: the answer", Opcode.MOVE_RESULT, touch[1].opcode)
                assertEquals("$name: the answer's register", 0, (touch[1] as OneRegisterInstruction).registerA)
                assertEquals("$name: the yes test", Opcode.IF_EQZ, touch[2].opcode)
                assertEquals("$name: a yes doesn't return", Opcode.RETURN, touch[3].opcode)
                assertEquals("$name: a yes doesn't return the answer", 0, (touch[3] as OneRegisterInstruction).registerA)
                assertSame("$name: a no doesn't go on to Facebook's code", touch[4],
                    (touch[2] as BuilderOffsetInstruction).target.location.instruction)
                assertEquals("$name: dispatchTouchEvent lost its own code",
                    original!!.body().map { it.opcode }, touch.drop(4).map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has the hooks`() {
        val hook = ExtensionDex.classDef(REEL_FILL).methods.singleOrNull { described(it) == REEL_FILL_FILLS }
        assertNotNull("the extension has no $REEL_FILL_FILLS", hook)
        assertTrue("the hook isn't public static",
            AccessFlags.STATIC.isSet(hook!!.accessFlags) && AccessFlags.PUBLIC.isSet(hook.accessFlags))
        val pinch = ExtensionDex.classDef(REEL_FIT_PINCH_TOUCH.substringBefore("->")).methods
            .singleOrNull { described(it) == REEL_FIT_PINCH_TOUCH }
        assertNotNull("the extension has no $REEL_FIT_PINCH_TOUCH", pinch)
        assertTrue("the pinch hook isn't public static",
            AccessFlags.STATIC.isSet(pinch!!.accessFlags) && AccessFlags.PUBLIC.isSet(pinch.accessFlags))
    }

    private companion object {
        /** Kept literal. Facebook's trace section for sizing a player view. */
        const val PLAYER_VIEW_SIZING = "VideoResizeUtils.updateSizeAndTranslation"

        /** Kept literal. The video surface's resize logs it when it reuses a surface. */
        const val SURFACE_RESIZE = "surfaceCreated_reuse"
    }
}
