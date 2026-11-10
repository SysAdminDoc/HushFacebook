/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.downloads.photo

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.facebook.comments.options.GRAPHQL_COMMENT
import app.morphe.patches.facebook.downloads.video.ATTACHMENTS_FIELD
import app.morphe.patches.facebook.downloads.video.IMMUTABLE_LIST
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.layout.theme.branchTargets
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Download any photo's Save GIF row (#226) and Download any video's Save video row (#318) on every
 * Facebook build the bundle declares (arm64 and armv7): one comment sheet render builds Save photo's
 * row from the comment's attachments, and after the hook the render asks the extension for the row
 * right after its rows are counted, builds it with Save photo's own builder and icon, and leaves the
 * rest Facebook's. With both patches in, in either order, both rows go in at that point. Reads the
 * fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class CommentGifFixtureTest {
    /** One comment sheet row: what it's called, the step that adds it, and its extension hooks. */
    private data class Row(
        val label: String,
        val step: BytecodePatchContext.() -> () -> Unit,
        val click: String,
        val title: String,
    )

    private companion object {
        /** The instructions one row adds. */
        const val ROW = 14
    }

    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.called() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    /**
     * The row [click] and [title] describe, built at [at] in [code] the way Save photo's is: the
     * comment handed over, Facebook's row-click, builder, icon and context, the list's add, and only
     * v0 to v15, none of them the comment's, context's or list's written over.
     */
    private fun assertRow(what: String, code: List<Instruction>, at: Int, point: CommentGifPoint, click: String, title: String) {
        assertEquals("$what: hands over the comment", point.comment, (code[at] as TwoRegisterInstruction).registerB)
        assertEquals("$what: asks for the row's tap", click, code[at + 1].called())
        assertEquals("$what: news up Facebook's row-click", point.wrapperInit, code[at + 6].called())
        assertEquals("$what: asks for the title", title, code[at + 7].called())
        assertEquals("$what: with Save photo's icon", point.icon, (code[at + 9] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$what: builds it with Save photo's builder", point.builder, code[at + 10].called())
        assertEquals("$what: in the component context", point.context, (code[at + 10] as FiveRegisterInstruction).registerD)
        assertEquals("$what: adds it to the rows", point.add, code[at + 13].called())
        assertEquals("$what: to the list", point.list, (code[at + 13] as FiveRegisterInstruction).registerC)
        for (index in at until at + ROW) {
            val registers = when (val instruction = code[index]) {
                is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE,
                    instruction.registerF, instruction.registerG).take(instruction.registerCount)
                is OneRegisterInstruction -> listOf(instruction.registerA)
                else -> emptyList()
            }
            val written = (code[index] as? OneRegisterInstruction)
                ?.takeIf { code[index].opcode.setsRegister() }?.registerA
            assertTrue("$what: the row writes over the comment, context or list",
                written == null || written !in listOf(point.comment, point.context, point.list))
            assertTrue("$what: a register past v15", registers.all { it <= 15 })
        }
    }

    /** GraphQLComment and every class with a method holding the sheet's name, in [bundle]. */
    private fun classes(bundle: java.io.File): Pair<ClassDef?, List<ClassDef>> {
        var comment: ClassDef? = null
        val sheets = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.type == GRAPHQL_COMMENT) comment = ImmutableClassDef.of(classDef)
                if (classDef.methods.any { holdsString(it, COMMENT_SHEET_SOCKET) }) sheets += ImmutableClassDef.of(classDef)
            }
        }
        return comment to sheets
    }

    @Test
    fun `each declared build's comment sheet asks the extension for a GIF row and a video row after Save photo`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val (comment, sheets) = classes(bundle)
                assertNotNull("$name: no GraphQLComment", comment)
                val getters = comment!!.methods.filter { method ->
                    method.parameterTypes.isEmpty() && method.returnType == IMMUTABLE_LIST &&
                        method.body().any { (it as? NarrowLiteralInstruction)?.narrowLiteral == ATTACHMENTS_FIELD }
                }
                assertEquals("$name: GraphQLComment's attachments getters", 1, getters.size)
                val getter = getters.single().name

                val taken = sheets.flatMap { owner -> owner.methods.filter { commentGifPoint(it, getter) != null }.map { owner to it } }
                assertEquals("$name: methods the patch takes", 1, taken.size)
                val (owner, render) = taken.single()
                // Negative control: the same render read through another getter isn't taken.
                assertNull("$name: a wrong getter still matched", commentGifPoint(render, getter + "x"))
                val point = commentGifPoint(render, getter)!!
                val original = render.body()
                assertEquals("$name: the hook follows the empty test", Opcode.IF_NEZ, original[point.insert - 1].opcode)
                assertTrue("$name: the short call forms take the context and list", point.context <= 15 && point.list <= 15)

                fun hooked(vararg steps: BytecodePatchContext.() -> () -> Unit): List<Instruction> {
                    val context = PatchContexts.of(listOf(owner, comment))
                    // Each step is found and run in turn, as each patch finds and runs its own.
                    for (step in steps) context.step()()
                    return context.mutableClassDefBy(owner.type).methods.single {
                        it.name == render.name && it.parameterTypes.map(CharSequence::toString) == render.parameterTypes.map(CharSequence::toString)
                    }.body()
                }
                val at = point.insert

                // Save GIF alone, then Save video alone (#318), each one row at the hook point.
                for ((label, step, click, title) in listOf(
                    Row("Save GIF", { commentSheetGifRow() }, GIF_ROW_CLICK, GIF_ROW_TITLE),
                    Row("Save video", { commentSheetVideoRow() }, VIDEO_ROW_CLICK, VIDEO_ROW_TITLE),
                )) {
                    val code = hooked(step)
                    assertEquals("$name: $label's code and nothing else", original.size + ROW, code.size)
                    assertRow("$name: $label", code, at, point, click, title)
                    assertTrue("$name: $label jumps to Facebook's next instruction", (at + ROW) in branchTargets(code))
                    assertEquals("$name: Facebook's code goes on after $label", original[at].opcode, code[at + ROW].opcode)
                    val rest = code.filterIndexed { index, _ -> index !in at until at + ROW }
                    assertEquals("$name: the rest is Facebook's with $label", original.map { it.opcode }, rest.map { it.opcode })
                }

                // Both patches in, either order: the second finds the same point and goes in front.
                for ((first, second) in listOf(
                    Row("Save GIF", { commentSheetGifRow() }, GIF_ROW_CLICK, GIF_ROW_TITLE) to
                        Row("Save video", { commentSheetVideoRow() }, VIDEO_ROW_CLICK, VIDEO_ROW_TITLE),
                    Row("Save video", { commentSheetVideoRow() }, VIDEO_ROW_CLICK, VIDEO_ROW_TITLE) to
                        Row("Save GIF", { commentSheetGifRow() }, GIF_ROW_CLICK, GIF_ROW_TITLE),
                )) {
                    val code = hooked(first.step, second.step)
                    val both = "$name: ${first.label} then ${second.label}"
                    assertEquals("$both: two rows' code and nothing else", original.size + 2 * ROW, code.size)
                    assertRow("$both, the second", code, at, point, second.click, second.title)
                    assertRow("$both, the first", code, at + ROW, point, first.click, first.title)
                    val targets = branchTargets(code)
                    assertTrue("$both: no second row jumps to the first", (at + ROW) in targets)
                    assertTrue("$both: no first row jumps to Facebook's next instruction", (at + 2 * ROW) in targets)
                    val rest = code.filterIndexed { index, _ -> index !in at until at + 2 * ROW }
                    assertEquals("$both: the rest is Facebook's", original.map { it.opcode }, rest.map { it.opcode })
                }
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }

    @Test
    fun `the extension has the hooks`() {
        for (hook in listOf(GIF_ROW_CLICK, GIF_ROW_TITLE, VIDEO_ROW_CLICK, VIDEO_ROW_TITLE)) {
            val type = hook.substringBefore("->")
            val name = hook.substringAfter("->").substringBefore("(")
            val method = ExtensionDex.classDef(type).methods.singleOrNull { it.name == name }
            assertNotNull("the extension has no ${type.substringAfterLast('/').removeSuffix(";")}.$name", method)
            assertEquals("$name's shape", hook,
                "${method!!.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}")
            assertTrue("$name isn't static", AccessFlags.STATIC.isSet(method.accessFlags))
            assertTrue("$name isn't public", AccessFlags.PUBLIC.isSet(method.accessFlags))
        }
    }
}
