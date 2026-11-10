/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postdates

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keep post dates' anchor on every Facebook build the bundle declares: the one method loading both
 * of the post header subtitle's rotation logs, in the class that names itself FDSPostHeaderSubtitle,
 * with the choice in the register each build keeps it in. Then the patch on it: the choice goes
 * through the extension between its call's answer and the log, and the extension's answer lands
 * back in that register. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without
 * it.
 */
class KeepPostDatesFixtureTest {
    /** Where each declared build keeps the choice, by ABI: the subtitle's class and the choice's register. */
    private val expected = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf(
            "arm64-v8a" to ("LX/03gS;" to 13),
            "armeabi-v7a" to ("LX/03gS;" to 13),
            "arm64-v8a-minapi28-storeless" to ("LX/03hC;" to 13),
        ),
    )

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    private fun stringAt(code: List<Instruction>, index: Int) =
        ((code[index] as? ReferenceInstruction)?.reference as? StringReference)?.string

    @Test
    fun `each declared build has the subtitle's choice once, and it goes through the extension`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val owners = FixtureDex.classesHolding(bundle, CYCLING_LOG).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                val renders = owners.flatMap { owner ->
                    owner.methods.filter { holdsString(it, CYCLING_LOG) && holdsString(it, CYCLING_COUNT_LOG) }
                }
                assertEquals("$name: methods loading \"$CYCLING_LOG\" and \"$CYCLING_COUNT_LOG\"", 1, renders.size)
                val choice = cyclingChoice(renders.single())
                val owner = owners.single { it.type == choice.method.definingClass }
                assertTrue("$name: ${owner.type} doesn't name itself FDSPostHeaderSubtitle",
                    owner.methods.any { it.name == "<init>" && holdsString(it, "FDSPostHeaderSubtitle") })
                assertEquals("$name: the subtitle's class and the choice's register", expected[version]?.get(name.substringAfter("-$version-").substringBefore(".apkm")),
                    owner.type to choice.register)
                val original = choice.method.code()

                val context = PatchContexts.of(listOf(owner, ExtensionDex.classDef(SETTINGS_STATUS)))
                keepPostDatesPatch.execute(context)

                val patched = context.mutableClassDefBy(owner.type).methods.single {
                    it.name == choice.method.name && it.parameterTypes == choice.method.parameterTypes
                }.code()
                assertEquals("$name: two instructions for the hook", original.size + 2, patched.size)
                val calls = patched.withIndex().filter { (it.value as? ReferenceInstruction)?.reference?.toString() == CYCLING }
                assertEquals("$name: calls of the extension", 1, calls.size)
                val (at, call) = calls.single()
                assertEquals("$name: the hook's place", choice.logIndex, at)
                // The patcher's compiler drops an instruction whose register doesn't fit, so the
                // call has to be there, naming the choice's register.
                assertEquals("$name: the call", Opcode.INVOKE_STATIC_RANGE, call.opcode)
                val range = call as RegisterRangeInstruction
                assertEquals("$name: the register handed over", choice.register to 1, range.startRegister to range.registerCount)
                assertEquals("$name: right after the choice's own answer", Opcode.MOVE_RESULT, patched[at - 1].opcode)
                assertEquals(choice.register, (patched[at - 1] as OneRegisterInstruction).registerA)
                assertEquals("$name: the extension's answer", Opcode.MOVE_RESULT, patched[at + 1].opcode)
                assertEquals("$name: the extension's answer lands where the log and the branch read it", choice.register,
                    (patched[at + 1] as OneRegisterInstruction).registerA)
                assertEquals("$name: the log after the hook", CYCLING_LOG, stringAt(patched, at + 2))

                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "postDates" }
                assertEquals("$name: SettingsStatus.postDates() isn't switched on", 1,
                    (status.code()[0] as NarrowLiteralInstruction).narrowLiteral)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }
}
