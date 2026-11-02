/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.tabbar

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tab order (#341) on every Facebook build the bundle declares: the tab bar's list builder keeps
 * its shown tabs in one place, the same builder the tab bar filter asks in, the build's Guava has
 * ImmutableList.copyOf(Collection), and the patch, run on the build's own tab bar state with the
 * tab bar filter in or not, hands the list to the extension and back through copyOf right before
 * the builder keeps it. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class TabOrderFixtureTest {
    private fun Method.same(other: Method) = definingClass == other.definingClass && name == other.name &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

    @Test
    fun `each declared build keeps its shown tabs once, and the order goes in right before, filter or not`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (fixture in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = fixture.name
                val stores = mutableListOf<Pair<Method, Int>>()
                val owners = mutableMapOf<String, ClassDef>()
                var guava: ClassDef? = null
                FixtureDex.forEach(fixture) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.type == IMMUTABLE_LIST) guava = ImmutableClassDef.of(classDef)
                        if (classDef.fields.none { it.type == NAVIGATION_CONFIG }) continue
                        for (method in classDef.methods) {
                            val store = shownTabStore(method) ?: continue
                            stores += method to store
                            owners[classDef.type] = ImmutableClassDef.of(classDef)
                        }
                    }
                }
                assertEquals("$name: places that keep the shown tabs", 1, stores.size)
                val (builder, store) = stores.single()
                assertNotNull("$name: the tab bar filter doesn't ask in the same builder", shownTabFilter(builder))
                assertNotNull("$name: no ImmutableList", guava)

                for (withFilter in listOf(false, true)) {
                    val context = PatchContexts.of(listOf(owners.getValue(builder.definingClass), guava!!))
                    if (withFilter) tabBarFilterPatch.execute(context)
                    tabOrderPatch.execute(context)
                    val patched = context.mutableClassDefBy(builder.definingClass).methods.single { it.same(builder) }
                        .implementation!!.instructions.toList()
                    val at = store + if (withFilter) 2 else 0
                    val list = (patched[at + 4] as TwoRegisterInstruction).registerA
                    val label = "$name${if (withFilter) " with the filter" else ""}"
                    assertEquals("$label: the store moved", Opcode.IPUT_OBJECT, patched[at + 4].opcode)
                    assertEquals("$label: the order call", TAB_ORDER_CALL,
                        (patched[at] as ReferenceInstruction).reference.toString())
                    assertEquals("$label: the copy", IMMUTABLE_LIST_COPY_OF,
                        (patched[at + 2] as ReferenceInstruction).reference.toString())
                    listOf(at, at + 2).forEach {
                        assertEquals("$label: what the call reads", listOf(list), patched[it].callRegisters())
                    }
                    listOf(at + 1, at + 3).forEach {
                        assertEquals("$label: ${patched[it].opcode}", Opcode.MOVE_RESULT_OBJECT, patched[it].opcode)
                        assertEquals("$label: what the result goes to", list,
                            (patched[it] as OneRegisterInstruction).registerA)
                    }
                    assertEquals("$label: a second order call", 1,
                        patched.count { (it as? ReferenceInstruction)?.reference?.toString() == TAB_ORDER_CALL })
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
