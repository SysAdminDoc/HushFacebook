/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.search.poststab

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.reels.categoryNames
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Search posts tab on every Facebook build the bundle declares, both ABIs of each. One method builds
 * the row of chips over search results from `(selected, id, label)` chips and hands it to the chip
 * row's class, which calls one table of tab labels whose tabs still include the posts tab
 * (BLENDED_STORIES) next to REELS. Patched, the built row goes through the extension and comes back
 * as an ImmutableList in the same register, the rest of Facebook's code stays as it was, and the
 * extension's stub answers that table.
 */
class SearchPostsTabFixtureTest {
    private fun bundles(check: (File) -> Unit) {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun row(bundle: File) {
        val holders = FixtureDex.classesHolding(bundle, CHIP_TAB_NAME).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val found = chipRows(holders)
        assertEquals("${bundle.name}: one method builds the search chip row, found ${found.map { it.first.descriptor() }}",
            1, found.size)
        val (builder, row) = found.single()
        val where = "${bundle.name}: ${row.draw.descriptor()}"
        assertTrue("${bundle.name}: ${builder.descriptor()} makes its chips with a (selected, id, label) constructor",
            row.chipType.startsWith("L"))

        // The chip row's class calls one table of tab labels, and its tabs still have posts and Reels.
        val rowClass = FixtureDex.classes(bundle, setOf(row.draw.definingClass)).values.single()
        val original = rowClass.methods.single { it.descriptor() == row.draw.descriptor() }.implementation!!.instructions.toList()
        val table = tabLabelTables(rowClass).single()
        val tableClass = FixtureDex.classes(bundle, setOf(table.definingClass)).values.single()
        val tabTypes = tableClass.methods.single { it.descriptor() == table.descriptor() }
            .implementation!!.instructions
            .filter { it.opcode == Opcode.SGET_OBJECT }
            .map { ((it as ReferenceInstruction).reference as FieldReference).type }
            .toSet()
        val tabs = FixtureDex.classes(bundle, tabTypes).values.flatMap { categoryNames(it) }.toSet()
        assertTrue("$where: ${table.definingClass}'s table has the posts tab", "BLENDED_STORIES" in tabs)
        assertTrue("$where: ${table.definingClass}'s table has Reels", "REELS" in tabs)

        val context = PatchContexts.of((holders + rowClass + listOf(
            ExtensionDex.classDef(POSTS_TAB_CLASS),
            ExtensionDex.classDef(SETTINGS_STATUS),
        )).distinctBy { it.type })
        searchPostsTabPatch.execute(context)

        val draw = context.mutableClassDefBy(row.draw.definingClass).methods.single { it.descriptor() == row.draw.descriptor() }
        val patched = draw.implementation!!.instructions.toList().filter { it.opcode != Opcode.NOP }
        val stock = original.filter { it.opcode != Opcode.NOP }
        // The draw method is static, so its parameters are the last registers and p0 is the first of them.
        val parameterRegisters = draw.parameterTypes.map { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }.sum()
        val list = draw.implementation!!.registerCount - parameterRegisters + draw.listParameter()
        assertEquals("$where gains the hook's two instructions", stock.size + 2, patched.size)
        assertEquals("$where hands its chips over first", Opcode.INVOKE_STATIC_RANGE, patched[0].opcode)
        assertEquals("$where: to the extension", CHIPS, (patched[0] as ReferenceInstruction).reference.toString())
        assertEquals("$where: its List parameter", list, (patched[0] as RegisterRangeInstruction).startRegister)
        assertEquals("$where: and draws what comes back", list, (patched[1] as OneRegisterInstruction).registerA)
        assertEquals("$where: the rest of Facebook's code stays", stock.map { it.opcode }, patched.drop(2).map { it.opcode })

        val stub = context.mutableClassDefBy(POSTS_TAB_CLASS).methods.single { it.name == TAB_LABELS_STUB }
            .implementation!!.instructions.toList()
        assertEquals("${bundle.name}: the stub answers Facebook's table", table.toString(),
            ((stub[0] as ReferenceInstruction).reference as MethodReference).toString())
        assertEquals("${bundle.name}: and returns it", listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT),
            stub.take(3).map { it.opcode })
    }

    @Test
    fun `each declared build hands the search chip row over and fills the tab label stub`() = bundles(::row)
}
