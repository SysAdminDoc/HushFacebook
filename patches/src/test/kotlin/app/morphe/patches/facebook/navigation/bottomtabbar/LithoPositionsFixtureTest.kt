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
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Litho's position walk (#466) on every Facebook build the bundle declares: one static (View,
 * boolean) to float method adding up each parent's X or Y, and the patch on it, its getY() asking
 * the extension with the same view, the answer read back the same way, put there once however
 * often the hook runs. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class LithoPositionsFixtureTest {
    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    private fun Method.sameAs(other: Method) = name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

    private fun called(instruction: Any) = ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    @Test
    fun `each declared build has one Litho position walk, and its getY asks the extension`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val walks = FixtureDex.methodsWhere(bundle, { true }, ::walksLithoPositions)
                assertEquals("$name: Litho position walks", 1, walks.size)
                val walk = walks.single()
                val original = walk.implementation!!.instructions.toList()
                val reads = original.indices.filter { called(original[it]) == "Landroid/view/View;->getY()F" }
                // Positive control: it loops up the parents, reading one Y.
                assertEquals("$name: ${walk.definingClass}->${walk.name} reads one view's Y", 1, reads.size)
                assertTrue("$name: and climbs to its parent", original.any { it.opcode == Opcode.GOTO })

                val context = PatchContexts.of(FixtureDex.classes(bundle, setOf(walk.definingClass)).values)
                context.hookLithoPositions()
                context.hookLithoPositions()

                val code = context.mutableClassDefBy(walk.definingClass).methods.single { it.sameAs(walk) }
                    .implementation!!.instructions.toList()
                val where = "$name: ${walk.definingClass}->${walk.name}"
                assertEquals("$where keeps its length", original.size, code.size)
                val at = reads.single()
                assertEquals("$where: the extension in getY's place", LITHO_Y, called(code[at]))
                val view = (original[at] as FiveRegisterInstruction).registerC
                val handed = code[at] as RegisterRangeInstruction
                assertEquals("$where: handed the same view", listOf(view, 1), listOf(handed.startRegister, handed.registerCount))
                assertEquals("$where: its answer read back", Opcode.MOVE_RESULT, code[at + 1].opcode)
                assertEquals("$where: into the same register", (original[at + 1] as OneRegisterInstruction).registerA,
                    (code[at + 1] as OneRegisterInstruction).registerA)
                assertEquals("$where: nothing else changes", original.map { it.opcode }.filterIndexed { i, _ -> i != at },
                    code.map { it.opcode }.filterIndexed { i, _ -> i != at })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }
}
