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
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tab bar at the bottom's anchors on every Facebook build the bundle declares: the key field the
 * override's class sets from its name, the two places that read it as a TriState and branch on its
 * ordinal, and Facebook's tab menu, which writes the key without reading it. Then the patch on
 * their classes: the extension right after each ordinal, its answer back in the ordinal's
 * register, and the write left as it was. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR
 * and skips without it.
 */
class BottomTabBarFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    private fun Method.sameAs(other: Method) = name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

    /** How many places read the override, per build: two up to 585, and on 586 one method every place asks (`LX/02Cc;->A05()Z` on arm64). */
    private val expectedReads = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to 1,
    )

    @Test
    fun `each declared build reads the override where it decides the bar, and the extension goes after each ordinal`() {
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
                assertEquals("$name: reads of the override", expectedReads.getValue(version), reads.size)
                assertEquals("$name: the reads sit in one class", 1, reads.map { it.method.definingClass }.distinct().size)
                val writes = users.filter { overrideReads(it, key).isEmpty() }
                assertEquals("$name: loads of the key that read nothing, the tab menu's write", 1, writes.size)

                val types = users.map { it.definingClass }.toSet() + holders.map { it.type }
                val context = PatchContexts.of(FixtureDex.classes(bundle, types).values + ExtensionDex.classDef(SETTINGS_STATUS))
                bottomTabBarPatch.execute(context)

                for ((method, sites) in reads.groupBy { it.method }) {
                    val original = method.code()
                    val patched = context.mutableClassDefBy(method.definingClass).methods.single { it.sameAs(method) }.code()
                    val where = "$name: ${method.definingClass}->${method.name}"
                    assertEquals("$where gains two instructions for each read", original.size + 2 * sites.size, patched.size)
                    var shift = 0
                    for (site in sites.sortedBy { it.resultIndex }) {
                        val at = site.resultIndex + shift
                        assertEquals("$where: Facebook's ordinal is kept", Opcode.MOVE_RESULT, patched[at].opcode)
                        val call = patched[at + 1]
                        assertEquals("$where: the call after the ordinal", Opcode.INVOKE_STATIC_RANGE, call.opcode)
                        assertEquals("$where: the call after the ordinal", OVERRIDE, (call as ReferenceInstruction).reference.toString())
                        assertEquals("$where: the register handed over is the ordinal's", site.register to 1,
                            (call as RegisterRangeInstruction).startRegister to call.registerCount)
                        val back = patched[at + 2]
                        assertEquals("$where: the answer lands back", Opcode.MOVE_RESULT, back.opcode)
                        assertEquals("$where: the answer lands back", site.register, (back as OneRegisterInstruction).registerA)
                        assertEquals("$where: Facebook's next instruction follows",
                            original[site.resultIndex + 1].opcode, patched[at + 3].opcode)
                        shift += 2
                    }
                }
                val write = writes.single()
                assertEquals("$name: the tab menu's write is left as it was", write.code().map { it.opcode },
                    context.mutableClassDefBy(write.definingClass).methods.single { it.sameAs(write) }.code().map { it.opcode })

                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "bottomTabBar" }
                assertEquals("$name: SettingsStatus.bottomTabBar() isn't switched on", 1,
                    (status.code()[0] as NarrowLiteralInstruction).narrowLiteral)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }
}
