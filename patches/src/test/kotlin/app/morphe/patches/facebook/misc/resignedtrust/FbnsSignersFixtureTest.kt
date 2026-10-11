/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.resignedtrust

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The push service's package check on each declared build hashes the signers the extension answers,
 * straight after its own read; a check shaped so the call can't be placed is refused with a reason;
 * and every build carries the fix through Hushfacebook settings (#112).
 */
class FbnsSignersFixtureTest {
    private val marker = "Failed to create SHA-256 hash"

    @Before
    @After
    fun forgetTheMatch() = FbnsPackageCheckFingerprint.clearMatch()

    @Test
    fun `each declared build's push service check hashes the signers the extension answers`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                forgetTheMatch()
                val context = PatchContexts.of(FixtureDex.classesHolding(bundle, marker))
                val method = with(context) { FbnsPackageCheckFingerprint.method }
                val stock = method.implementation!!.instructions.toList()
                val readAt = stock.indexOfFirst { it.isSignaturesRead() }
                assertTrue("${bundle.name}: no signatures read", readAt >= 0)

                method.routeFbnsSigners()
                val body = method.implementation!!.instructions.toList()
                val read = body[readAt] as TwoRegisterInstruction
                assertTrue(bundle.name, body[readAt].isSignaturesRead())
                val call = body[readAt + 1]
                assertEquals(bundle.name, Opcode.INVOKE_STATIC, call.opcode)
                assertEquals(bundle.name, FBNS_SIGNERS, (call as ReferenceInstruction).reference.toString())
                assertEquals("${bundle.name}: the package", read.registerB, (call as FiveRegisterInstruction).registerC)
                assertEquals("${bundle.name}: the signers", read.registerA, call.registerD)
                assertEquals(bundle.name, Opcode.MOVE_RESULT_OBJECT, body[readAt + 2].opcode)
                assertEquals(bundle.name, read.registerA, (body[readAt + 2] as OneRegisterInstruction).registerA)
                assertEquals("${bundle.name}: nothing else moved", stock.size + 2, body.size)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** A second read, none, or one that overwrites the package it reads from has no single safe place for the call. */
    @Test
    fun `a check the call can't be placed in is refused with the reason`() {
        val twice = check("iget-object v1, v0, $SIGNATURES\niget-object v1, v0, $SIGNATURES")
        assertTrue(assertThrows(PatchException::class.java) { twice.routeFbnsSigners() }.message!!.contains("found 2"))
        val none = check("const/4 v1, 0x0")
        assertTrue(assertThrows(PatchException::class.java) { none.routeFbnsSigners() }.message!!.contains("found 0"))
        val overwrites = check("iget-object v0, v0, $SIGNATURES")
        assertTrue(assertThrows(PatchException::class.java) { overwrites.routeFbnsSigners() }.message!!.contains("can't name"))
    }

    /** No selection leaves the fix out: it has no name to deselect and Hushfacebook settings depends on it. */
    @Test
    fun `Hushfacebook settings brings the fix into every build`() {
        assertNull("a named patch can be deselected", fbnsSignersPatch.name)
        assertTrue(settingsPatch.dependencies.any { it === fbnsSignersPatch })
    }

    /** A static check taking the package in v0, with [read] where its signatures are read. */
    private fun check(read: String): MutableMethod = MutableMethod(
        ImmutableMethod(
            "Lfixture/Fbns;",
            "check",
            emptyList(),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            ImmutableMethodImplementation(2, emptyList(), null, null),
        ),
    ).apply { addInstructions(0, "const/4 v0, 0x0\n$read\nreturn-void") }

    private fun Instruction.isSignaturesRead(): Boolean = opcode == Opcode.IGET_OBJECT &&
        ((this as ReferenceInstruction).reference as FieldReference).let {
            it.definingClass == "Landroid/content/pm/PackageInfo;" && it.name == "signatures"
        }

    private companion object {
        const val SIGNATURES = "Landroid/content/pm/PackageInfo;->signatures:[Landroid/content/pm/Signature;"
    }
}
