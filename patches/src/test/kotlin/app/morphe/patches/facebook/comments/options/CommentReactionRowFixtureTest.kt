/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.options

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Show every reaction on comments (#241) on every Facebook build the bundle declares: the class
 * named CommentActionsWithSimplifiedReactionsComponent is found by its kept string, and of the
 * methods that make one, exactly one decides on a MobileConfig read, the comment actions plugin's.
 * Right after that read's `move-result` the extension is asked, as a range call, and its answer
 * goes back into the register the branch before the component tests. Reads the fixture bundles
 * from HUSHFACEBOOK_FIXTURE_DIR and skips without it. Redex names differ between the arm64 and
 * armv7 bundles, so nothing here names a class.
 */
class CommentReactionRowFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.called() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    @Test
    fun `each declared build's pick of the top-reaction row goes through the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val components = FixtureDex.classesHolding(bundle, TOP_REACTION_COMPONENT)
                    .filter { owner -> owner.methods.any { holdsString(it, TOP_REACTION_COMPONENT) } }
                assertEquals("$name: classes named $TOP_REACTION_COMPONENT", 1, components.size)
                val component = components.single()

                val builders = FixtureDex.methodsWhere(
                    bundle,
                    { dex -> dex.typeSection.any { it.toString() == component.type } },
                ) { method ->
                    method.implementation?.instructions?.any {
                        it.opcode == Opcode.NEW_INSTANCE &&
                            ((it as ReferenceInstruction).reference as TypeReference).type == component.type
                    } == true
                }
                // Positive control: the plugin and the other builders make the component.
                assertTrue("$name: only ${builders.size} methods make the component", builders.size >= 2)
                val owners = FixtureDex.classes(bundle, builders.map { it.definingClass }.toSet()).values.toList()

                val reads = topReactionReads(owners + component)
                assertEquals("$name: reads deciding on the component", 1, reads.size)
                val read = reads.single()
                val before = read.method.code()
                assertEquals("$name: the answer is a move-result", Opcode.MOVE_RESULT, before[read.result].opcode)
                assertEquals("$name: the answer's register",
                    read.register, (before[read.result] as OneRegisterInstruction).registerA)

                val context = PatchContexts.of(owners + component)
                commentReactionRowPatch.execute(context)
                val patched = context.mutableClassDefBy(read.method.definingClass).methods.single {
                    it.name == read.method.name && it.returnType == read.method.returnType &&
                        it.parameterTypes.map(CharSequence::toString) == read.method.parameterTypes.map(CharSequence::toString)
                }.code()
                assertEquals("$name: two instructions added", before.size + 2, patched.size)
                assertEquals("$name: the extension is asked as a range call", Opcode.INVOKE_STATIC_RANGE, patched[read.result + 1].opcode)
                assertEquals("$name: it asks $TOP_REACTION_ONLY", TOP_REACTION_ONLY, patched[read.result + 1].called())
                assertEquals("$name: its answer goes back", read.register, (patched[read.result + 2] as OneRegisterInstruction).registerA)
                assertEquals("$name: the rest is Facebook's own",
                    before.drop(read.result + 1).map { it.opcode }, patched.drop(read.result + 3).map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has the hook`() {
        val hook = ExtensionDex.classDef(COMMENT_SHEET_OPTIONS).methods.singleOrNull { it.name == "topReactionOnly" }
        assertTrue("the extension has no CommentSheetOptions.topReactionOnly", hook != null)
        assertEquals("topReactionOnly's shape", TOP_REACTION_ONLY, hook!!.descriptor())
    }
}
