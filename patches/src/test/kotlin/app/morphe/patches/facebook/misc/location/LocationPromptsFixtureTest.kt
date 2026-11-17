/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.location

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #498: on each declared build and ABI, Facebook's device location screen has to start with
 * its parent's onActivityCreate and close through one (Intent, boolean) method, which it already
 * calls when there's nothing to ask. The hook goes in right after the parent's call: the screen goes
 * to LocationPrompts.skips, a yes closes it through that method and returns, and a no lands on the
 * method's own next instruction.
 */
class LocationPromptsFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.method() = (this as? ReferenceInstruction)?.reference as? MethodReference

    @Test
    fun `the location screen goes to the extension right after its parent's start, on each declared build`() {
        val checked = mutableSetOf<String>()
        var bundles = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val screen = FixtureDex.classes(bundle, setOf(LOCATION_SCREEN))[LOCATION_SCREEN]
                assertTrue("$name: no $LOCATION_SCREEN", screen != null)
                val start = screenStart(screen!!)
                val body = start.method.body()
                assertEquals("$name: the screen closes through its parent's method", screen.superclass, start.close.definingClass)
                // Facebook's own "nothing to ask" path calls the close, so the hook's call is one it makes.
                assertTrue("$name: the method never calls ${start.close}",
                    body.any { it.opcode == Opcode.INVOKE_VIRTUAL && it.method()?.toString() == start.close.toString() })
                assertEquals("$name: the screen is the method's first parameter register",
                    start.method.implementation!!.registerCount - 2, start.self)

                assertThrows("$name: no screen must refuse the patch", PatchException::class.java) {
                    PatchContexts.of(emptyList()).hookLocationScreen()
                }
                val context = PatchContexts.of(listOf(screen))
                with(context) { hookLocationScreen() }
                val after = context.mutableClassDefBy(LOCATION_SCREEN).methods
                    .single { it.name == start.method.name && it.parameterTypes == start.method.parameterTypes }.body()
                assertEquals("$name: the parent's start stays first", body[0].method().toString(), after[0].method().toString())
                val ask = after[1]
                assertEquals("$name: the screen goes to LocationPrompts.skips", SKIPS, ask.method().toString())
                assertEquals("$name: as itself", start.self, (ask as FiveRegisterInstruction).registerC)
                assertEquals("$name: the answer lands in v0", 0, (after[2] as OneRegisterInstruction).registerA)
                assertEquals("$name: a no branches", Opcode.IF_EQZ, after[3].opcode)
                assertEquals("$name: to the method's own next instruction", 8, landing(after, 3))
                assertEquals("$name: which is the one that followed the parent's start", body[1].opcode, after[8].opcode)
                val close = after[6] as FiveRegisterInstruction
                assertEquals("$name: a yes closes through Facebook's own close", start.close.toString(), after[6].method().toString())
                assertEquals("$name: on the screen with no intent and false", listOf(start.self, 0, 1),
                    listOf(close.registerC, close.registerD, close.registerE))
                assertEquals("$name: then returns", Opcode.RETURN_VOID, after[7].opcode)
                assertEquals("$name: seven instructions added", body.size + 7, after.size)
                bundles++
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertTrue("only $bundles bundles checked", bundles >= versions.size)
    }

    /** The index the branch at [at] in [body] lands on. */
    private fun landing(body: List<Instruction>, at: Int): Int {
        var address = 0
        val addresses = body.map { instruction -> address.also { address += instruction.codeUnits } }
        val target = addresses[at] + (body[at] as OffsetInstruction).codeOffset
        return addresses.indexOf(target)
    }
}
