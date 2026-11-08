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
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clean up Reels' Saved videos hook (#404) on every Facebook build the bundle declares: the
 * opens-elsewhere ask Watch again uses names its channel through one sibling returning a String,
 * that sibling and the ask both spell out Saved's channel (so the extension compares against what
 * Facebook really returns), and after the patch the ask hands that channel to the extension right
 * after it lands and returns no on a yes, with the rest of the ask Facebook's. Reads the fixture
 * bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class SavedVideosFixtureTest {
    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.sameAs(other: Method) = name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

    private fun Instruction.called() = ((this as? ReferenceInstruction)?.reference as? MethodReference)

    /** Every class in [bundle] logging a Watch again tap, calling a method shaped like the ask, or defining one. */
    private fun holders(bundle: java.io.File): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            dex.classes.filterTo(found) { classDef ->
                !classDef.type.startsWith(EXTENSION_PACKAGE) && classDef.methods.any { method ->
                    isOpensElsewhereAsk(method) || WATCH_AGAIN_MARKERS.any { holdsString(method, it) } ||
                        method.body().any { instruction -> instruction.called()?.let(::isOpensElsewhereAsk) == true }
                }
            }
        }
        return found.map(ImmutableClassDef::of)
    }

    @Test
    fun `each declared build's saved videos ask the extension before they open Reels`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val classes = holders(bundle)
                val context = PatchContexts.of(classes)
                val (ask, _) = watchAgainAnchors(context.watchAgainTaps())
                val owner = classes.single { it.type == ask.definingClass }
                val method = owner.methods.single { it.name == ask.name && isOpensElsewhereAsk(it) }
                val channel = channelCall(method, ask)
                assertNotNull("$name: ${described(ask)} names no channel", channel)
                val namer = method.body()[channel!!.call].called()!!
                val sibling = owner.methods.single { it.name == namer.name && it.returnType == namer.returnType &&
                    it.parameterTypes.map(CharSequence::toString) == namer.parameterTypes.map(CharSequence::toString) }
                // Positive control: Saved's channel is what the sibling hands back, and what the ask then says yes to.
                assertTrue("$name: ${described(namer)} never names $SAVED_CHANNEL", holdsString(sibling, SAVED_CHANNEL))
                assertTrue("$name: ${described(ask)} never compares against $SAVED_CHANNEL", holdsString(method, SAVED_CHANNEL))

                // As Clean up Reels runs it: Watch again's hook goes in first, on the same ask.
                assertEquals("$name: both hooks on the ask go in", emptyList<String>(), context.hookWatchAgainAsk())
                val original = method.body()
                val patched = context.mutableClassDefBy(owner.type).methods.single { it.sameAs(method) }.body()
                val at = channel.call + 2
                assertEquals("$name: five instructions added", original.size + 5, patched.size)
                val call = patched[at]
                assertEquals("$name: the channel goes to the extension", STAYS_IN_PLAYER, call.called()?.let(::described))
                call as RegisterRangeInstruction
                assertEquals("$name: from the register Facebook took it in",
                    listOf(1, channel.register), listOf(call.registerCount, call.startRegister))
                val answer = (patched[at + 1] as OneRegisterInstruction).registerA
                assertEquals("$name: the answer is taken", Opcode.MOVE_RESULT, patched[at + 1].opcode)
                assertNotEquals("$name: the answer lands on the channel", channel.register, answer)
                assertEquals("$name: then branched on, set to no and returned",
                    listOf(Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN),
                    listOf(patched[at + 2].opcode, patched[at + 3].opcode, patched[at + 4].opcode))
                assertEquals("$name: all in the answer's register", listOf(answer, answer, answer),
                    (2..4).map { (patched[at + it] as OneRegisterInstruction).registerA })
                assertEquals("$name: the no is false", 0, (patched[at + 3] as NarrowLiteralInstruction).narrowLiteral)
                val addresses = patched.runningFold(0) { address, instruction -> address + instruction.codeUnits }
                assertEquals("$name: Facebook's own answer goes on where it did",
                    addresses[at + 5], addresses[at + 2] + (patched[at + 2] as OffsetInstruction).codeOffset)
                assertEquals("$name: the rest is Facebook's",
                    original.map { it.opcode }, patched.filterIndexed { index, _ -> index !in at until at + 5 }.map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has the hook and compares against Saved's channel`() {
        val hook = ExtensionDex.classDef(SAVED_VIDEOS).methods.singleOrNull { it.name == "staysInPlayer" }
        assertNotNull("the extension has no $STAYS_IN_PLAYER", hook)
        assertEquals("the hook's shape", STAYS_IN_PLAYER, described(hook!!))
        assertTrue("the hook isn't public static",
            AccessFlags.STATIC.isSet(hook.accessFlags) && AccessFlags.PUBLIC.isSet(hook.accessFlags))
        assertTrue("the hook never names $SAVED_CHANNEL", holdsString(hook, SAVED_CHANNEL))
    }
}
