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
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
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
        val action = (code[at] as TwoRegisterInstruction).registerA
        point.commentFields.forEachIndexed { index, field ->
            val read = code[at + 1 + index]
            assertEquals("$what: reads the comment again through render's fields", Opcode.IGET_OBJECT, read.opcode)
            assertEquals("$what: field ${index + 1} of the comment's path", field, (read as ReferenceInstruction).reference.toString())
            val registers = read as TwoRegisterInstruction
            assertEquals("$what: the path stays in one register", listOf(action, action), listOf(registers.registerA, registers.registerB))
        }
        val shift = point.commentFields.size
        assertEquals("$what: asks for the row's tap", click, code[at + shift + 1].called())
        assertEquals("$what: news up Facebook's row-click", point.wrapperInit, code[at + shift + 6].called())
        assertEquals("$what: asks for the title", title, code[at + shift + 7].called())
        assertEquals("$what: with Save photo's icon", point.icon, (code[at + shift + 9] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$what: builds it with Save photo's builder", point.builder, code[at + shift + 10].called())
        assertEquals("$what: in the component context", point.context, (code[at + shift + 10] as FiveRegisterInstruction).registerD)
        assertEquals("$what: adds it to the rows", point.add, code[at + shift + 13].called())
        assertEquals("$what: to the list", point.list, (code[at + shift + 13] as FiveRegisterInstruction).registerC)
        for (index in at until at + ROW + shift) {
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

    /**
     * GraphQLComment, every class with a method holding the sheet's name, and every class with a
     * method setting a Dialog's content, in [bundle].
     */
    private fun classes(bundle: java.io.File): Triple<ClassDef?, List<ClassDef>, List<ClassDef>> {
        var comment: ClassDef? = null
        val sheets = mutableListOf<ClassDef>()
        val dialogs = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.type == GRAPHQL_COMMENT) comment = ImmutableClassDef.of(classDef)
                if (classDef.methods.any { holdsString(it, COMMENT_SHEET_SOCKET) }) sheets += ImmutableClassDef.of(classDef)
                if (classDef.methods.any { method -> method.body().any { it.called() == SET_CONTENT_VIEW } }) {
                    dialogs += ImmutableClassDef.of(classDef)
                }
            }
        }
        return Triple(comment, sheets, dialogs)
    }

    /** GraphQLComment's one attachments getter in [comment]. */
    private fun attachmentsGetter(name: String, comment: ClassDef): String {
        val getters = comment.methods.filter { method ->
            method.parameterTypes.isEmpty() && method.returnType == IMMUTABLE_LIST &&
                method.body().any { (it as? NarrowLiteralInstruction)?.narrowLiteral == ATTACHMENTS_FIELD }
        }
        assertEquals("$name: GraphQLComment's attachments getters", 1, getters.size)
        return getters.single().name
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
                val getter = attachmentsGetter(name, comment!!)

                val taken = sheets.flatMap { owner -> owner.methods.filter { commentGifPoint(it, getter) != null }.map { owner to it } }
                assertEquals("$name: methods the patch takes", 1, taken.size)
                val (owner, render) = taken.single()
                // Negative control: the same render read through another getter isn't taken.
                assertNull("$name: a wrong getter still matched", commentGifPoint(render, getter + "x"))
                val point = commentGifPoint(render, getter)!!
                val original = render.body()
                assertEquals("$name: the hook follows the empty test", Opcode.IF_NEZ, original[point.insert - 1].opcode)
                assertTrue("$name: the short call forms take the context and list", point.context <= 15 && point.list <= 15)
                // The phone builds keep the comment in its register up to the hook point. The
                // Android 9 build reuses it, so the hook reads it again through render's own field.
                val abi = name.substringAfter("-$version-").substringBefore(".apkm")
                assertEquals("$name: the comment's register is reused", abi.contains("-minapi"), point.commentFields.isNotEmpty())
                point.commentFields.firstOrNull()?.let {
                    assertEquals("$name: the comment's path starts at render's own field", owner.type, it.substringBefore("->"))
                }
                val row = ROW + point.commentFields.size

                fun hooked(vararg steps: BytecodePatchContext.() -> () -> Unit): List<Instruction> {
                    val context = PatchContexts.of(listOf(owner, comment))
                    // Each step is found and run in turn, as each patch finds and runs its own.
                    for (step in steps) context.step()()
                    return context.mutableClassDefBy(owner.type).methods.single {
                        it.name == render.name && it.parameterTypes.map(CharSequence::toString) == render.parameterTypes.map(CharSequence::toString)
                    }.body()
                }
                val at = point.insert

                // Save GIF alone, Save video alone (#318) and Save photo alone (#440), each one row
                // at the hook point.
                for ((label, step, click, title) in listOf(
                    Row("Save GIF", { commentSheetGifRow() }, GIF_ROW_CLICK, GIF_ROW_TITLE),
                    Row("Save video", { commentSheetVideoRow() }, VIDEO_ROW_CLICK, VIDEO_ROW_TITLE),
                    Row("Save photo", { commentSheetPhotoRow() }, PHOTO_ROW_CLICK, PHOTO_ROW_TITLE),
                )) {
                    val code = hooked(step)
                    assertEquals("$name: $label's code and nothing else", original.size + row, code.size)
                    assertRow("$name: $label", code, at, point, click, title)
                    assertTrue("$name: $label jumps to Facebook's next instruction", (at + row) in branchTargets(code))
                    assertEquals("$name: Facebook's code goes on after $label", original[at].opcode, code[at + row].opcode)
                    val rest = code.filterIndexed { index, _ -> index !in at until at + row }
                    assertEquals("$name: the rest is Facebook's with $label", original.map { it.opcode }, rest.map { it.opcode })
                }

                // Both patches in, either order: the second finds the same point and goes in front.
                // Download any photo adds Save GIF, then Save photo the same way.
                for ((first, second) in listOf(
                    Row("Save GIF", { commentSheetGifRow() }, GIF_ROW_CLICK, GIF_ROW_TITLE) to
                        Row("Save video", { commentSheetVideoRow() }, VIDEO_ROW_CLICK, VIDEO_ROW_TITLE),
                    Row("Save video", { commentSheetVideoRow() }, VIDEO_ROW_CLICK, VIDEO_ROW_TITLE) to
                        Row("Save GIF", { commentSheetGifRow() }, GIF_ROW_CLICK, GIF_ROW_TITLE),
                    Row("Save GIF", { commentSheetGifRow() }, GIF_ROW_CLICK, GIF_ROW_TITLE) to
                        Row("Save photo", { commentSheetPhotoRow() }, PHOTO_ROW_CLICK, PHOTO_ROW_TITLE),
                )) {
                    val code = hooked(first.step, second.step)
                    val both = "$name: ${first.label} then ${second.label}"
                    assertEquals("$both: two rows' code and nothing else", original.size + 2 * row, code.size)
                    assertRow("$both, the second", code, at, point, second.click, second.title)
                    assertRow("$both, the first", code, at + row, point, first.click, first.title)
                    val targets = branchTargets(code)
                    assertTrue("$both: no second row jumps to the first", (at + row) in targets)
                    assertTrue("$both: no first row jumps to Facebook's next instruction", (at + 2 * row) in targets)
                    val rest = code.filterIndexed { index, _ -> index !in at until at + 2 * row }
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
    fun `each declared build's comment sheet hands its dialog to the extension once the sheet is its content`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val (comment, sheets, dialogs) = classes(bundle)
                assertNotNull("$name: no GraphQLComment", comment)
                val getter = attachmentsGetter(name, comment!!)
                val owner = sheets.single { sheet -> sheet.methods.any { commentGifPoint(it, getter) != null } }

                val taken = dialogs.flatMap { maker ->
                    maker.methods.filter { commentSheetDialogPoint(it, owner.type) != null }.map { maker to it }
                }
                assertEquals("$name: methods that set the sheet's dialog", 1, taken.size)
                val (fragment, method) = taken.single()
                assertEquals("$name: the fragment's onCreateView", "onCreateView", method.name)
                // Negative control: the same method read for another render isn't taken.
                assertNull("$name: another render still matched", commentSheetDialogPoint(method, owner.type.dropLast(1) + "x;"))
                val (insert, dialog) = commentSheetDialogPoint(method, owner.type)!!
                val original = method.body()
                assertEquals("$name: right after the sheet goes in", SET_CONTENT_VIEW, original[insert - 1].called())
                val made = original.subList(0, insert - 1).last {
                    it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == dialog
                }
                assertEquals("$name: the dialog is the one newed up for the sheet", Opcode.NEW_INSTANCE, made.opcode)

                val context = PatchContexts.of(listOf(owner, comment, fragment))
                context.commentSheetDialog("test")()
                val code = context.mutableClassDefBy(fragment.type).methods.single {
                    it.name == method.name && it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
                }.body()
                assertEquals("$name: one call and nothing else", original.size + 1, code.size)
                assertEquals("$name: hands the dialog over", SHEET_OPENED, code[insert].called())
                assertEquals("$name: the dialog's register", dialog, code[insert].let {
                    (it as? FiveRegisterInstruction)?.registerC ?: (it as RegisterRangeInstruction).startRegister
                })
                val rest = code.filterIndexed { index, _ -> index != insert }
                assertEquals("$name: the rest is Facebook's", original.map { it.opcode }, rest.map { it.opcode })
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }

    @Test
    fun `the extension has the hooks`() {
        for (hook in listOf(GIF_ROW_CLICK, GIF_ROW_TITLE, VIDEO_ROW_CLICK, VIDEO_ROW_TITLE, PHOTO_ROW_CLICK, PHOTO_ROW_TITLE, SHEET_OPENED)) {
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
