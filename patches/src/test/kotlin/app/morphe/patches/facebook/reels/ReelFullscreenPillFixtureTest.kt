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
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clean up Reels' Fullscreen pill hook (#394) on every Facebook build the bundle declares (arm64 and
 * armv7): one component holds "full_screen_pill", and after the hook its render asks the extension
 * first and answers null when told to. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and
 * skips without it.
 */
class ReelFullscreenPillFixtureTest {
    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.called() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    /** Every class in [bundle] with a method that loads the pill's literal. */
    private fun holding(bundle: java.io.File): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.stringSection.none { it == FULL_SCREEN_PILL }) return@forEach
            dex.classes.filterTo(found) { classDef -> classDef.methods.any { holdsString(it, FULL_SCREEN_PILL) } }
        }
        return found.map(ImmutableClassDef::of)
    }

    @Test
    fun `each declared build's Fullscreen pill asks the extension first`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val classes = holding(bundle)
                assertEquals("$name: classes holding \"$FULL_SCREEN_PILL\"", 1, classes.size)
                val pill = classes.single()
                val render = fullscreenPillRender(pill)
                assertNotNull("$name: ${pill.type} has no render the patch takes", render)
                assertEquals("$name: the render answers an object", "L", render!!.returnType.take(1))
                // Negative control: the same class without its render isn't taken.
                val noRender = ImmutableClassDef(
                    pill.type, pill.accessFlags, pill.superclass, pill.interfaces, pill.sourceFile, pill.annotations,
                    pill.staticFields, pill.instanceFields, pill.directMethods, pill.virtualMethods.filter { it.name != "render" },
                )
                assertNull("$name: a class with no render was taken", fullscreenPillRender(noRender))

                val context = PatchContexts.of(classes)
                with(context) { hideFullscreenPill() }
                val hooked = context.mutableClassDefBy(pill.type).methods.single { it.name == "render" }.body()
                assertEquals("$name: asks first", HIDE_FULLSCREEN_PILL, hooked[0].called())
                assertEquals("$name: tests the answer", Opcode.IF_EQZ, hooked[2].opcode)
                assertEquals("$name: loads null", Opcode.CONST_4, hooked[3].opcode)
                assertEquals("$name: and answers it when told to", Opcode.RETURN_OBJECT, hooked[4].opcode)
                assertEquals("$name: the rest is Facebook's", render.body().map { it.opcode }, hooked.drop(5).map { it.opcode })
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }

    @Test
    fun `the extension has the hook`() {
        val type = HIDE_FULLSCREEN_PILL.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "hideFullscreenPill" }
        assertNotNull("the extension has no ReelDeclutter.hideFullscreenPill", hook)
        assertEquals("hideFullscreenPill's shape", HIDE_FULLSCREEN_PILL,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("hideFullscreenPill isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }
}
