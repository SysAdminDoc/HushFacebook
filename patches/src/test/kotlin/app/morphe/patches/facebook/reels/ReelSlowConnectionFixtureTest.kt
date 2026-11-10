/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.shared.redexOriginalName
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
 * Clean up Reels' slow connection message hook (#222) on every Facebook build the bundle declares
 * (arm64 and armv7): one Runnable Redex names WeakSignalToastManager's showToastRunnable, and after
 * the hook its run() asks the extension first and returns when told to. Reads the fixture bundles
 * from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class ReelSlowConnectionFixtureTest {
    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.called() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    /** Every class in [bundle] whose Redex name is the message's runnable. */
    private fun named(bundle: java.io.File): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.stringSection.none { it == SLOW_CONNECTION_RUNNABLE }) return@forEach
            dex.classes.filterTo(found) { redexOriginalName(it) == SLOW_CONNECTION_RUNNABLE }
        }
        return found.map(ImmutableClassDef::of)
    }

    @Test
    fun `each declared build's slow connection runnable asks the extension first`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val classes = named(bundle)
                assertEquals("$name: classes Redex names $SLOW_CONNECTION_RUNNABLE", 1, classes.size)
                val runnable = classes.single()
                val run = slowConnectionRun(runnable)
                assertNotNull("$name: ${runnable.type} has no run() the patch takes", run)
                // Negative control: the same class without its Runnable interface isn't taken.
                val notRunnable = ImmutableClassDef(
                    runnable.type, runnable.accessFlags, runnable.superclass, emptyList(), runnable.sourceFile,
                    runnable.annotations, runnable.staticFields, runnable.instanceFields, runnable.directMethods,
                    runnable.virtualMethods,
                )
                assertNull("$name: a class that isn't a Runnable was taken", slowConnectionRun(notRunnable))

                val context = PatchContexts.of(classes)
                with(context) { hideSlowConnectionMessage() }
                val hooked = context.mutableClassDefBy(runnable.type).methods
                    .single { it.name == "run" && it.parameterTypes.isEmpty() }.body()
                assertEquals("$name: asks first", HIDE_SLOW_CONNECTION_MESSAGE, hooked[0].called())
                assertEquals("$name: tests the answer", Opcode.IF_EQZ, hooked[2].opcode)
                assertEquals("$name: and returns when told to", Opcode.RETURN_VOID, hooked[3].opcode)
                assertEquals("$name: the rest is Facebook's", run!!.body().map { it.opcode }, hooked.drop(4).map { it.opcode })
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }

    @Test
    fun `the extension has the hook`() {
        val type = HIDE_SLOW_CONNECTION_MESSAGE.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "hideSlowConnectionMessage" }
        assertNotNull("the extension has no ReelDeclutter.hideSlowConnectionMessage", hook)
        assertEquals("hideSlowConnectionMessage's shape", HIDE_SLOW_CONNECTION_MESSAGE,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("hideSlowConnectionMessage isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }
}
