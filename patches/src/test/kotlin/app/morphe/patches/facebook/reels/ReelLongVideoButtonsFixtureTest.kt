/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Side buttons on long videos (#525) on every Facebook build the bundle declares: the side
 * column's list builder, which hands back a viewer config's preset button list as it is, hands
 * the extension that list right after reading it and keeps the answer, so Facebook's own null
 * check builds the usual list when the answer is null. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class ReelLongVideoButtonsFixtureTest {
    private val buttonList = "Lcom/google/common/collect/ImmutableList;"

    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.called() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Instruction.field() = (this as? ReferenceInstruction)?.reference as? FieldReference

    private fun Instruction.namesConfig() =
        ((this as? ReferenceInstruction)?.reference as? TypeReference)?.type == VIEWER_CONFIG

    private fun MethodReference.text() =
        "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    /** Whether [instruction] reads a viewer config's button list. */
    private fun readsPreset(instruction: Instruction) = instruction.opcode == Opcode.IGET_OBJECT &&
        instruction.field()?.let { it.definingClass == VIEWER_CONFIG && it.type == buttonList } == true

    @Test
    fun `each declared build's side column list gives way to the usual one`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                // Every class reading a viewer config's list or making a config.
                val found = mutableMapOf<String, ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    dex.classes.forEach { classDef ->
                        val keep = classDef.methods.any { method -> method.body().any { readsPreset(it) || it.namesConfig() } }
                        if (keep) found[classDef.type] = ImmutableClassDef.of(classDef)
                    }
                }

                // Positive control: Facebook makes a config with a preset list.
                assertTrue("$name: no config is made with a preset list", found.values.flatMap { it.methods }.any { method ->
                    val body = method.body()
                    body.any { it.opcode == Opcode.NEW_INSTANCE && it.namesConfig() } &&
                        body.any { it.called()?.definingClass == "Lcom/google/common/collect/ImmutableList\$Builder;" }
                })

                val context = PatchContexts.of(found.values)
                context.showLongVideoButtons()
                val hooked = found.values.flatMap { classDef ->
                    context.mutableClassDefBy(classDef.type).methods.filter { method ->
                        method.body().any { it.called()?.text() == PRESET_BUTTONS }
                    }
                }
                assertEquals("$name: one list builder is hooked", 1, hooked.size)
                assertEquals("$name: it builds an ImmutableList", buttonList, hooked.single().returnType)
                val body = hooked.single().body()
                val at = body.indexOfFirst { it.called()?.text() == PRESET_BUTTONS }
                assertTrue("$name: the hook follows the config list's read", at > 0 && readsPreset(body[at - 1]))
                val list = (body[at - 1] as TwoRegisterInstruction).registerA
                assertEquals("$name: the hook is handed the list", list, (body[at] as FiveRegisterInstruction).registerC)
                assertEquals("$name: the answer replaces the list, cast back, then Facebook's null check and return",
                    listOf(Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST, Opcode.IF_EQZ, Opcode.RETURN_OBJECT),
                    body.subList(at + 1, at + 5).map { it.opcode })
                assertEquals("$name: all on the list's register", listOf(list, list, list, list),
                    body.subList(at + 1, at + 5).map { (it as OneRegisterInstruction).registerA })
                assertTrue("$name: past the return, the builder goes on to build its own list",
                    body.drop(at + 5).any { it.called()?.name == "build" })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has the preset hook`() {
        val declutter = ExtensionDex.classDef("$EXTENSION_PACKAGE/reels/ReelDeclutter;")
        val method = declutter.methods.singleOrNull {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == PRESET_BUTTONS
        }
        assertNotNull("the extension has no $PRESET_BUTTONS", method)
        assertTrue("$PRESET_BUTTONS isn't public static",
            AccessFlags.STATIC.isSet(method!!.accessFlags) && AccessFlags.PUBLIC.isSet(method.accessFlags))
    }
}
