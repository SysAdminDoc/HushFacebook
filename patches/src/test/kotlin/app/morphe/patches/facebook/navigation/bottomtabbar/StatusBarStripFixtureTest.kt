/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.bottomtabbar

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The strip behind a hidden status bar (#124) on every Facebook build the bundle declares: the one
 * view id the kept StatusBarUtil finds on a window, and the one static system bar padding that
 * loads it and pads the content view. Then the patch on it: the extension first, handed the content
 * view and the top inset and answering into the top inset's register, then handed the content view
 * and the bottom inset the same way (#466), both put there once however often the hook runs. Reads
 * the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class StatusBarStripFixtureTest {
    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    private fun Method.calls() = implementation?.instructions?.toList().orEmpty()
        .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }

    private fun Method.sameAs(other: Method) = name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

    @Test
    fun `each declared build has one system bar padding loading the strip's id, and it asks the extension first`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val util = FixtureDex.classes(bundle, setOf(STATUS_BAR_UTIL))[STATUS_BAR_UTIL]
                assertNotNull("$name: $STATUS_BAR_UTIL is kept", util)
                val ids = stripIds(util!!)
                assertEquals("$name: view ids StatusBarUtil finds on a window", 1, ids.size)
                val candidates = FixtureDex.methodsWhere(bundle, { true }, ::takesSystemBarPadding)
                val paddings = candidates.filter { loadsId(it, ids.single()) }
                assertEquals("$name: system bar paddings loading the strip's id", 1, paddings.size)
                val padding = paddings.single()
                // Positive control: it pads a view, the content, by what it's handed.
                assertTrue("$name: ${padding.definingClass}->${padding.name} pads a view",
                    padding.calls().any { it.toString() == "Landroid/view/View;->setPadding(IIII)V" })

                val context = PatchContexts.of(FixtureDex.classes(bundle, setOf(padding.definingClass, STATUS_BAR_UTIL)).values)
                context.hookStatusBarStrip(candidates)
                context.hookStatusBarStrip(candidates)

                val original = padding.implementation!!.instructions.toList()
                val patched = context.mutableClassDefBy(padding.definingClass).methods.single { it.sameAs(padding) }
                    .implementation!!
                val code = patched.instructions.toList()
                val where = "$name: ${padding.definingClass}->${padding.name}"
                val first = patched.registerCount - 8
                assertEquals("$where gains four instructions, once", original.size + 4, code.size)
                assertEquals("$where: the extension first", TOP_INSET, (code[0] as ReferenceInstruction).reference.toString())
                val call = code[0] as FiveRegisterInstruction
                assertEquals("$where: handed the content view and the top inset", listOf(first + 1, first + 5),
                    listOf(call.registerC, call.registerD))
                assertEquals("$where: its answer", Opcode.MOVE_RESULT, code[1].opcode)
                assertEquals("$where: replaces the top inset", first + 5, (code[1] as OneRegisterInstruction).registerA)
                // #466: then the bottom inset, the one the content is padded up off the gesture bar by.
                assertEquals("$where: the extension asked for the bottom", BOTTOM_INSET, (code[2] as ReferenceInstruction).reference.toString())
                val bottom = code[2] as FiveRegisterInstruction
                assertEquals("$where: handed the content view and the bottom inset", listOf(first + 1, first + 7),
                    listOf(bottom.registerC, bottom.registerD))
                assertEquals("$where: its answer", Opcode.MOVE_RESULT, code[3].opcode)
                assertEquals("$where: replaces the bottom inset", first + 7, (code[3] as OneRegisterInstruction).registerA)
                assertEquals("$where: Facebook's padding follows", original.map { it.opcode }, code.drop(4).map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }
}
