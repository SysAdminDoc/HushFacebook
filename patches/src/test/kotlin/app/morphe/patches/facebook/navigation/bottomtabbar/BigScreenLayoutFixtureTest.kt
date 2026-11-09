/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.bottomtabbar

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phone layout on big screens' anchors on every Facebook build the bundle declares (#149): the one
 * big screen check the tab bar's place asks before its override (586: before calling the method
 * that reads it), a check the main screen's
 * fragment asks too, which stores the app's big screen flag and asks a gate of MobileConfig flags.
 * Then the patch on those classes: the extension at the top of the gate, whose no-answer goes on
 * to the gate's own first instruction, put there once however often the hook runs. Reads the
 * fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class BigScreenLayoutFixtureTest {
    private val chromeFragment = "Lcom/facebook/katana/fragment/FbChromeFragment;"
    private val mobileConfig = "Lcom/facebook/mobileconfig/factory/MobileConfigUnsafeContext;"

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    private fun Method.calls() = implementation?.instructions?.toList().orEmpty()
        .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }

    private fun Method.sameAs(other: MethodReference) = name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

    @Test
    fun `each declared build has one big screen check, and its gate answers the extension first`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val holders = FixtureDex.classesHolding(bundle, OVERRIDE_KEY).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                val key = overrideKeyField(holders)
                val users = FixtureDex.methodsWhere(bundle, { dex -> dex.fieldSection.any { it == key } }) { method ->
                    method.implementation?.instructions?.any {
                        it.opcode == Opcode.SGET_OBJECT && (it as ReferenceInstruction).reference == key
                    } == true
                }
                val reads = users.flatMap { overrideReads(it, key) }
                // 586 asks the check one call up, in the read's class (`LX/02Cc;->A09` before `A05` on arm64).
                val owners = FixtureDex.classes(bundle, reads.map { it.method.definingClass }.toSet())
                val checks = reads.flatMap(::bigScreenChecks).ifEmpty {
                    reads.flatMap { bigScreenChecksInCallers(it, owners.getValue(it.method.definingClass)) }
                }.distinctBy { it.toString() }
                assertEquals("$name: big screen checks before the override", 1, checks.size)
                val check = checks.single()

                val classes = FixtureDex.classes(bundle, setOf(check.definingClass, chromeFragment))
                val checkMethod = classes.getValue(check.definingClass).methods.single { it.sameAs(check) }
                val gate = bigScreenGate(checkMethod)
                assertNotNull("$name: $check stores its class's flag and asks a gate", gate)
                gate!!
                assertEquals("$name: the gate sits beside the check", check.definingClass, gate.definingClass)
                val gateMethod = classes.getValue(gate.definingClass).methods.single { it.sameAs(gate) }
                assertTrue("$name: $gate reads MobileConfig flags", gateMethod.calls().any { it.definingClass == mobileConfig })
                // Positive control: the main screen's own fragment asks the same check, so the gate
                // decides more than the tab bar's place.
                assertTrue("$name: FbChromeFragment asks $check",
                    classes.getValue(chromeFragment).methods.any { method -> method.calls().any { it.toString() == check.toString() } })

                val types = users.map { it.definingClass }.toSet() + holders.map { it.type } + check.definingClass
                val context = PatchContexts.of(FixtureDex.classes(bundle, types).values + ExtensionDex.classDef(SETTINGS_STATUS))
                bottomTabBarPatch.execute(context)
                context.hookBigScreenLayout(reads)

                val original = gateMethod.implementation!!.instructions.toList()
                val patched = context.mutableClassDefBy(gate.definingClass).methods.single { it.sameAs(gate) }
                    .implementation!!.instructions.toList()
                val where = "$name: $gate"
                assertEquals("$where gains five instructions, once", original.size + 5, patched.size)
                assertEquals("$where: the extension first", PHONE_LAYOUT, (patched[0] as ReferenceInstruction).reference.toString())
                assertEquals("$where: its answer", Opcode.MOVE_RESULT, patched[1].opcode)
                assertEquals("$where: its answer in v0", 0, (patched[1] as OneRegisterInstruction).registerA)
                assertEquals("$where: a no goes on to Facebook's gate", Opcode.IF_EQZ, patched[2].opcode)
                assertEquals("$where: the branch lands on the gate's own first instruction", 5,
                    (patched[2] as BuilderOffsetInstruction).target.location.index)
                assertEquals("$where: a yes answers false", 0, (patched[3] as NarrowLiteralInstruction).narrowLiteral)
                assertEquals("$where: and returns it", Opcode.RETURN, patched[4].opcode)
                assertEquals("$where: Facebook's gate follows", original.map { it.opcode }, patched.drop(5).map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }
}
