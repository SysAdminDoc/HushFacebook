/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.appicons

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unlock app icons' anchors on every declared Facebook build: one benefit provider with one check of
 * a benefit name, which the App icon page and the start-up job both ask for [BENEFIT], and one App
 * icon page callback that looks for [BENEFIT] in each synced set. Then both hooks go in on the real
 * classes, which also assembles their smali.
 */
class UnlockAppIconsFixtureTest {
    @Test
    fun `each declared build has the benefit check and the App icon page's look once`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun check(bundle: File) {
        val name = bundle.name
        val providers = FixtureDex.classesHolding(bundle, PROVIDER_TAG).filter(::isBenefitProvider)
        assertEquals("$name: benefit providers", 1, providers.size)
        val provider = providers.single()
        val checks = provider.methods.filter(::isBenefitCheck)
        assertEquals("$name: benefit checks in ${provider.type}", 1, checks.size)
        val check = checks.single()

        // The App icon page and the start-up job that puts the default icon back both ask the check for the benefit.
        val askers = FixtureDex.classesHolding(bundle, BENEFIT)
        val asking = askers.filter { classDef ->
            classDef.methods.any { method ->
                method.implementation?.instructions?.any { it.calls(provider.type, check.name) } == true
            }
        }
        assertTrue("$name: no class asks ${provider.type}->${check.name} near $BENEFIT", asking.isNotEmpty())

        val looks = askers.flatMap { classDef -> classDef.methods.mapNotNull { m -> pickerBenefitLook(m)?.let { Triple(classDef, m, it) } } }
        assertEquals("$name: App icon page looks at $BENEFIT", 1, looks.size)
        val (pageClass, callback, result) = looks.single()
        val code = callback.implementation!!.instructions.toList()
        val answer = (code[result] as OneRegisterInstruction).registerA

        // Both hooks go in on the real classes.
        val context = PatchContexts.of(listOf(provider, pageClass))
        context.mutableClassDefBy(provider.type).findMutableMethodOf(check).unlockFirst()
        context.mutableClassDefBy(pageClass.type).findMutableMethodOf(callback).passPickerBenefit(result)

        val gate = context.mutableClassDefBy(provider.type).findMutableMethodOf(check).implementation!!.instructions.toList()
        assertEquals("$name: the check's first call", UNLOCKED, gate[0].call())
        assertEquals("$name: the unlocked answer", Opcode.RETURN, gate[4].opcode)
        assertEquals("$name: the check's own code after the hook", check.implementation!!.instructions.count() + 5, gate.size)

        val patched = context.mutableClassDefBy(pageClass.type).findMutableMethodOf(callback).implementation!!.instructions.toList()
        val hook = patched[result + 1]
        assertEquals("$name: the call after the page's look", ENTITLED, hook.call())
        assertEquals("$name: the register handed over", answer, (hook as RegisterRangeInstruction).startRegister)
        assertEquals("$name: the answer goes back where it came from", answer, (patched[result + 2] as OneRegisterInstruction).registerA)
        assertEquals("$name: the page's own code after the hook", code.size + 2, patched.size)
    }

    private fun Instruction.calls(type: String, method: String): Boolean =
        ((this as? ReferenceInstruction)?.reference as? MethodReference)?.let { it.definingClass == type && it.name == method } == true

    private fun Instruction.call(): String? =
        ((this as? ReferenceInstruction)?.reference as? MethodReference)?.let {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
        }
}
