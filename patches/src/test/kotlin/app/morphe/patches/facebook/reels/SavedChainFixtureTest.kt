/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.media.taptoplay.FRAGMENT_ACTIVITY
import app.morphe.patches.facebook.media.taptoplay.MOTION_EVENT
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clean up Reels' Saved page taps (#404) on every Facebook build the bundle declares: the Saved
 * page's screen base, DelegatingFbFragmentFrameworkActivity, dispatches touches without running
 * FbFragmentActivity's dispatch (so a hook in that one alone never sees a Saved tap), and after the
 * patch both dispatches hand the screen and the event to the extension first, with the rest of each
 * Facebook's. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class SavedChainFixtureTest {
    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.called() = ((this as? ReferenceInstruction)?.reference as? MethodReference)

    private fun MethodReference.text() =
        "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    private fun dispatchOf(classDef: ClassDef) = classDef.methods.single {
        it.name == "dispatchTouchEvent" && it.returnType == "Z" &&
            it.parameterTypes.map(CharSequence::toString) == listOf(MOTION_EVENT)
    }

    @Test
    fun `each declared build's screens hand every touch to the extension first`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val found = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex -> dex.classes.filterTo(found) { it.type in SAVED_TOUCH_SCREENS } }
                val classes = found.map(ImmutableClassDef::of)
                assertEquals("$name: both screen bases", SAVED_TOUCH_SCREENS.toSet(), classes.map { it.type }.toSet())

                // Positive control: the Saved page's base goes straight to its delegate, never through FbFragmentActivity's.
                val delegating = dispatchOf(classes.single { it.type == DELEGATING_ACTIVITY })
                assertFalse("$name: $DELEGATING_ACTIVITY's dispatch runs $FRAGMENT_ACTIVITY's after all",
                    delegating.body().any { it.called()?.let { call -> call.definingClass == FRAGMENT_ACTIVITY && call.name == "dispatchTouchEvent" } == true })

                val context = PatchContexts.of(classes)
                context.observeSavedTouches()
                for (type in SAVED_TOUCH_SCREENS) {
                    val original = dispatchOf(classes.single { it.type == type })
                    val patched = dispatchOf(context.mutableClassDefBy(type))
                    val body = patched.body()
                    assertEquals("$name: one instruction added to $type", original.body().size + 1, body.size)
                    val first = body[0]
                    assertEquals("$name: $type calls the extension first", Opcode.INVOKE_STATIC_RANGE, first.opcode)
                    assertEquals("$name: $type hands it the touch hook", SAVED_CHAIN_TOUCH, first.called()?.text())
                    first as RegisterRangeInstruction
                    val registers = patched.implementation!!.registerCount
                    assertEquals("$name: $type hands over the screen and the event, p0 and p1",
                        listOf(2, registers - 2), listOf(first.registerCount, first.startRegister))
                    assertEquals("$name: the rest of $type's dispatch is Facebook's",
                        original.body().map { it.opcode }, body.drop(1).map { it.opcode })
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has the touch hook`() {
        val hook = ExtensionDex.classDef("$EXTENSION_PACKAGE/reels/SavedChain;").methods.singleOrNull { it.name == "touched" }
        assertNotNull("the extension has no $SAVED_CHAIN_TOUCH", hook)
        assertEquals("the hook's shape", SAVED_CHAIN_TOUCH,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("the hook isn't public static",
            AccessFlags.STATIC.isSet(hook.accessFlags) && AccessFlags.PUBLIC.isSet(hook.accessFlags))
    }
}
