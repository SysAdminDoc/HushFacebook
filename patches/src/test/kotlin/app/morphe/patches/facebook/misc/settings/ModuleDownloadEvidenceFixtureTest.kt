/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.settings

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.PatchLogCapture
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #168's module download evidence on each declared build: the module downloader's step runner hands
 * each step to the extension right after casting it, and that step's class is the one whose text
 * reads "RequestAction{modules=". The download screen's callback hands its task over on the failure
 * path, after reading the task's exception, so both the "Failed, no exception" and the exception
 * branch pass it. Nothing else in either method changes.
 */
class ModuleDownloadEvidenceFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun Method.code() = implementation!!.instructions.toList()

    /** The opcodes without NOPs: one keeps the runner's switch payload aligned after the call moves it. */
    private fun List<Instruction>.opcodes() = map { it.opcode }.filter { it != Opcode.NOP }

    private fun Method.descriptor() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    @Test
    fun `each declared build's downloader hands its steps and its failed task to the report`() {
        val extension = ExtensionDex.classDef(MODULE_DOWNLOAD_EVIDENCE)
        for (call in listOf("step", "failed")) {
            val method = extension.methods.single { it.name == call }
            assertTrue("$call is public and static", AccessFlags.PUBLIC.isSet(method.accessFlags) && AccessFlags.STATIC.isSet(method.accessFlags))
            assertEquals(listOf("Ljava/lang/Object;"), method.parameterTypes.map(CharSequence::toString))
        }

        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val owners = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.methods.any { holdsString(it, NO_STEPS) || holdsString(it, FAILED_NO_EXCEPTION) }) {
                            owners += ImmutableClassDef.of(classDef)
                        }
                    }
                }
                val context = PatchContexts.of(owners)
                val (step, failure) = context.findModuleEvidenceSites()

                // The step is the class whose own text names its modules.
                val stepType = ((step.method.code()[step.index - 1] as ReferenceInstruction).reference as TypeReference).type
                val stepClass = FixtureDex.classes(bundle, setOf(stepType)).values.single()
                assertTrue("$name: $stepType reads as a RequestAction", stepClass.methods.any { it.name == "toString" && holdsString(it, "RequestAction{modules=") })

                // The failure path reads the task's exception just before the site, from the callback's
                // own parameter. 586 merges the callback with another lambda's, and its big frame
                // copies the parameter into a low register first, which nothing writes again before the read.
                val failureCode = failure.method.code()
                val read = failureCode[failure.index - 2]
                assertEquals("$name: the task's exception is read", "Ljava/lang/Exception;", ((read as ReferenceInstruction).reference as MethodReference).returnType)
                assertEquals("$name: on the task", failure.register, read.firstRegister())
                val parameter = failure.method.implementation!!.registerCount - 1
                val source = if (failure.register == parameter) parameter else {
                    val copy = failureCode.take(failure.index - 2).lastOrNull {
                        it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == failure.register
                    }
                    assertTrue("$name: the task's register is written last by $copy",
                        copy?.opcode == Opcode.MOVE_OBJECT || copy?.opcode == Opcode.MOVE_OBJECT_FROM16)
                    (copy as TwoRegisterInstruction).registerB
                }
                assertEquals("$name: the task is the callback's parameter", parameter, source)

                assertEquals("$name: warned", emptyList<String>(), PatchLogCapture.warnings { moduleDownloadEvidencePatch.execute(context) })
                for (site in listOf(step, failure)) {
                    val patched = context.mutableClassDefBy(site.method.definingClass).methods.single { it.descriptor() == site.method.descriptor() }
                    val code = patched.code()
                    val call = code[site.index] as RegisterRangeInstruction
                    assertEquals("$name: ${site.method.descriptor()} calls the extension", site.call, (code[site.index] as ReferenceInstruction).reference.toString())
                    assertEquals("$name: on the site's register", site.register, call.startRegister)
                    assertEquals(1, call.registerCount)
                    assertEquals("$name: no register added", site.method.implementation!!.registerCount, patched.implementation!!.registerCount)
                    assertEquals("$name: only the call was added", site.method.code().opcodes(),
                        code.filterNot { (it as? ReferenceInstruction)?.reference?.toString() == site.call }.opcodes())
                }
                val cast = step.method.code()[step.index - 1]
                assertEquals("$name: the step is read right after its cast", Opcode.CHECK_CAST, cast.opcode)
                assertEquals("$name: on the cast's register", (cast as OneRegisterInstruction).registerA, step.register)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
