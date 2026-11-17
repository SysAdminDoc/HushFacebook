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
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #498: on each declared build and ABI, the page "To use Location Services, allow Facebook to
 * access your location" is a promotion that shows only while Facebook's nxx_location_service_client
 * filter passes, and exactly one method is that filter: it takes the session, the trigger, the
 * filter and the promotion, and keeps its count under "location_service". The hook goes first in
 * it: the trigger goes to LocationPrompts.skips, a yes answers false, and a no lands on the
 * method's own first instruction.
 */
class LocationPromptsFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.method() = (this as? ReferenceInstruction)?.reference as? MethodReference

    /** Every class of [bundle] holding the password-change key, the only ones the patch looks in. */
    private fun candidates(bundle: java.io.File): List<ClassDef> = FixtureDex.classesHolding(bundle, PASSWORD_CHANGE)

    @Test
    fun `the location promotion's filter asks the extension first, on each declared build`() {
        val checked = mutableSetOf<String>()
        var bundles = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val classes = candidates(bundle)
                val filters = classes.flatMap { it.methods.filter(::isLocationPromotionFilter) }
                assertEquals("$name: one location promotion filter", 1, filters.size)
                val filter = filters.single()
                val body = filter.body()
                val registers = filter.implementation!!.registerCount
                // Facebook's own filter answers from location access and the count it keeps.
                assertTrue("$name: the filter doesn't read location access",
                    body.any { it.opcode == Opcode.INVOKE_VIRTUAL && it.method()?.parameterTypes?.map(CharSequence::toString) == listOf("Ljava/lang/String;") && it.method()?.returnType == "Z" })

                assertThrows("$name: no filter must refuse the patch", PatchException::class.java) {
                    PatchContexts.of(emptyList()).skipLocationPromotion()
                }
                val context = PatchContexts.of(classes)
                with(context) { skipLocationPromotion() }
                val after = context.mutableClassDefBy(filter.definingClass).methods
                    .single { it.name == filter.name && it.parameterTypes == filter.parameterTypes }.body()
                val ask = after[0]
                assertEquals("$name: the trigger goes to LocationPrompts.skips first", SKIPS, ask.method().toString())
                ask as RegisterRangeInstruction
                assertEquals("$name: one register", 1, ask.registerCount)
                assertEquals("$name: the trigger, the second parameter after this", registers - 3, ask.startRegister)
                assertEquals("$name: the answer lands in v0", 0, (after[1] as OneRegisterInstruction).registerA)
                assertEquals("$name: a no branches", Opcode.IF_EQZ, after[2].opcode)
                assertEquals("$name: to the filter's own first instruction", 5, landing(after, 2))
                assertEquals("$name: which is the one it started with", body[0].opcode, after[5].opcode)
                assertEquals("$name: a yes answers false", 0, (after[3] as NarrowLiteralInstruction).narrowLiteral)
                assertEquals("$name: in v0", 0, (after[3] as OneRegisterInstruction).registerA)
                assertEquals("$name: and returns it", Opcode.RETURN, after[4].opcode)
                assertEquals("$name: five instructions added", body.size + 5, after.size)
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
