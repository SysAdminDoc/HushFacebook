/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.foldtext

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.posttext.CUT
import app.morphe.patches.facebook.feed.posttext.TRANSLATION_TEXT
import app.morphe.patches.facebook.feed.posttext.captionMarked
import app.morphe.patches.facebook.feed.posttext.captionTypes
import app.morphe.patches.facebook.feed.posttext.cutShaped
import app.morphe.patches.facebook.feed.posttext.foldMarked
import app.morphe.patches.facebook.feed.posttext.foldTypes
import app.morphe.patches.facebook.feed.posttext.showFullPostTextPatch
import app.morphe.patches.facebook.feed.posttext.translatePostsPatch
import app.morphe.patches.facebook.media.taptoplay.FRAGMENT_ACTIVITY
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fold long text's places on every Facebook build the bundle declares: the post text builder's
 * line limit and cut config, the line limit's class's maker, and the comment sheet's cut config.
 * Then the patch on them: each line limit goes through the extension right after it lands, each
 * config call hands its characters and lines through it first at the call's label, so every jump
 * that went to the call lands on the hook, and the comment's hook sits in the call's try block. It
 * also checks that Show full post text's and Translate posts' hooks keep their places in the
 * builder, whatever order the three run in. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR
 * and skips without it.
 */
class FoldFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.names(call: String) = (this as? ReferenceInstruction)?.reference?.toString() == call

    private fun Instruction.call() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun traced(method: Method) = method.implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == COMMENT_TRACE
    } == true

    /** How many branches and gotos in [code] land on [index]. */
    private fun jumpsInto(code: List<Instruction>, index: Int): Int {
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        return code.indices.count { from ->
            val jump = code[from] as? OffsetInstruction ?: return@count false
            code[from].opcode !in setOf(Opcode.FILL_ARRAY_DATA, Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH) &&
                addresses[from] + jump.codeOffset == addresses[index]
        }
    }

    /** The exception types of each try block covering the instruction at [index] of [method]. */
    private fun catching(method: Method, index: Int): List<List<String?>> {
        val address = method.code().take(index).sumOf { it.codeUnits }
        return method.implementation!!.tryBlocks.filter { address >= it.startCodeAddress && address < it.startCodeAddress + it.codeUnitCount }
            .map { block -> block.exceptionHandlers.map { it.exceptionType } }
    }

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    private fun BytecodePatchContext.after(method: Method): Method = mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes == method.parameterTypes
    }

    /** One line hook: right after the line limit's answer, through the range form, landing back where it was. */
    private fun assertLineHook(name: String, code: List<Instruction>, call: MethodReference, hook: String) {
        val at = code.indexOfFirst { it.call()?.toString() == call.toString() }
        assertTrue("$name: no call of $call", at >= 0)
        val answer = code[at + 1]
        assertEquals("$name: the limit's answer", Opcode.MOVE_RESULT, answer.opcode)
        val register = (answer as OneRegisterInstruction).registerA
        assertTrue("$name: no line hook right after the limit's answer", code[at + 2].names(hook))
        assertEquals(register to 1, (code[at + 2] as RegisterRangeInstruction).let { it.startRegister to it.registerCount })
        assertEquals(Opcode.MOVE_RESULT, code[at + 3].opcode)
        assertEquals(register, (code[at + 3] as OneRegisterInstruction).registerA)
    }

    /**
     * One config hook in [patched], ahead of its config call: the call's two numbers handed over,
     * the characters first, and every jump that went to the call in [original] now landing on it.
     * Answers the hook's index.
     */
    private fun assertConfigHook(name: String, original: List<Instruction>, patched: List<Instruction>, chars: String, lines: String): Int {
        val factories = { code: List<Instruction> -> code.indices.filter { at -> code[at].call()?.let { it.parameterTypes.map(CharSequence::toString) == listOf("I", "I") && it.returnType.startsWith("L") && code.getOrNull(at - 2)?.names(lines) == true } == true } }
        val at = patched.indexOfFirst { it.names(chars) }
        assertTrue("$name: no character hook", at >= 0)
        val config = patched[at + 4]
        assertTrue("$name: the config call doesn't follow its hook", factories(patched).contains(at + 4))
        val (charsRegister, linesRegister) = arguments(config)
        assertEquals("$name: the numbers handed over", listOf(charsRegister, linesRegister), arguments(patched[at]))
        assertEquals(Opcode.MOVE_RESULT, patched[at + 1].opcode)
        assertEquals(charsRegister, (patched[at + 1] as OneRegisterInstruction).registerA)
        assertTrue(patched[at + 2].names(lines))
        assertEquals(linesRegister to 1, (patched[at + 2] as RegisterRangeInstruction).let { it.startRegister to it.registerCount })
        assertEquals(linesRegister, (patched[at + 3] as OneRegisterInstruction).registerA)
        val stock = original.indexOfFirst { it.call()?.toString() == config.call().toString() && arguments(it) == arguments(config) }
        assertEquals("$name: jumps onto the hook", jumpsInto(original, stock), jumpsInto(patched, at))
        assertEquals("$name: jumps past the hook to the config call", 0, jumpsInto(patched, at + 4))
        return at
    }

    @Test
    fun `each declared build folds a post's text and a comment through the extension`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val found = FixtureDex.methodsWhere(bundle, { true }) {
                    !it.definingClass.startsWith(EXTENSION_CLASSES) && (cutShaped(it) || foldMarked(it) || captionMarked(it) || traced(it))
                }
                val builder = found.filter(::cutShaped).single()
                val commentMethods = found.filter(::traced)
                assertEquals("$name: methods naming the comment's trace", 1, commentMethods.size)
                val comment = commentMethods.single()
                val builderCode = builder.code()
                val limits = builderCode.mapNotNull { it.call() }.filter { lineLimitShaped(it) }
                val limit = limits.distinctBy { it.toString() }.singleOrNull()
                    ?: throw AssertionError("$name: the builder's line limits: $limits")
                // Show full post text's fold and reel caption classes, for running it after or before.
                val folds = foldTypes(found) + captionTypes(bundle, found)
                val classes = FixtureDex.classes(bundle,
                    setOf(builder.definingClass, limit.definingClass, comment.definingClass, FRAGMENT_ACTIVITY) + folds)
                val commentCode = comment.code()
                val stock = { PatchContexts.of((classes.values + ExtensionDex.classDef(SETTINGS_STATUS)).toList()) }

                // Negative control: without the line limit's class, nothing is found, and nothing changes.
                val limitless = PatchContexts.of((classes.filterKeys { it != limit.definingClass }.values +
                    ExtensionDex.classDef(SETTINGS_STATUS)).toList())
                assertThrows("$name: folded with no line limit to read", PatchException::class.java) {
                    foldLongTextPatch.execute(limitless)
                }
                assertEquals("$name: the comment changed before the refusal", commentCode.size, limitless.after(comment).code().size)
                assertEquals("$name: the builder changed before the refusal", builderCode.size, limitless.after(builder).code().size)

                val context = stock()
                foldLongTextPatch.execute(context)

                // The line limit's class holds one maker beside it, the one method there the patch hooked.
                val makers = context.mutableClassDefBy(limit.definingClass).methods.filter { method ->
                    method.implementation?.instructions?.any { it.names(POST_LINES) } == true
                }
                assertEquals("$name: makers hooked beside the line limit", 1, makers.size)
                val maker = classes.getValue(limit.definingClass).methods.single {
                    it.name == makers.single().name && it.parameterTypes == makers.single().parameterTypes
                }
                val makerCode = maker.code()

                val post = context.after(builder).code()
                assertEquals("$name: the builder's hooks", builderCode.size + 6, post.size)
                assertEquals(2, post.count { it.names(POST_LINES) })
                assertEquals(1, post.count { it.names(POST_CHARS) })
                assertLineHook("$name builder", post, limit, POST_LINES)
                assertConfigHook("$name builder", builderCode, post, POST_CHARS, POST_LINES)

                val made = context.after(maker).code()
                assertEquals("$name: the maker's hooks", makerCode.size + 6, made.size)
                assertLineHook("$name maker", made, limit, POST_LINES)
                val makerHook = assertConfigHook("$name maker", makerCode, made, POST_CHARS, POST_LINES)
                // 587's maker jumps to its config call from three places, which is why the hook takes the call's label.
                assertTrue("$name: nothing jumps to the maker's config call", jumpsInto(made, makerHook) > 0)

                val patchedComment = context.after(comment)
                val folded = patchedComment.code()
                assertEquals("$name: the comment's hook", commentCode.size + 4, folded.size)
                assertEquals(0, folded.count { it.names(POST_LINES) || it.names(POST_CHARS) })
                val commentHook = assertConfigHook("$name comment", commentCode, folded, COMMENT_CHARS, COMMENT_LINES)
                val stockConfig = commentCode.indexOfFirst { it.call()?.toString() == folded[commentHook + 4].call().toString() }
                val tries = catching(comment, stockConfig)
                assertTrue("$name: the comment's config call isn't in a try block", tries.isNotEmpty())
                assertEquals("$name: the hook left the call's try block", tries, catching(patchedComment, commentHook))
                assertEquals(tries, catching(patchedComment, commentHook + 4))

                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "foldLongText" }
                assertEquals("$name: SettingsStatus.foldLongText() isn't switched on", 1,
                    (status.implementation!!.instructions.first() as NarrowLiteralInstruction).narrowLiteral)

                // Whatever order the three run in, each keeps its hooks in the builder.
                val returns = builderCode.count { it.opcode == Opcode.RETURN_OBJECT }
                val orders: List<List<BytecodePatch>> = listOf(
                    listOf(foldLongTextPatch, showFullPostTextPatch, translatePostsPatch),
                    listOf(showFullPostTextPatch, translatePostsPatch, foldLongTextPatch),
                    listOf(translatePostsPatch, foldLongTextPatch, showFullPostTextPatch),
                )
                for (order in orders) {
                    val together = stock()
                    order.forEach { it.execute(together) }
                    val all = together.after(builder).code()
                    val names = order.map { it.name }
                    val cut = all.indexOfFirst { it.names(CUT) }
                    assertTrue("$name: no cut hook after $names", cut > 0)
                    assertEquals(Opcode.MOVE_RESULT_OBJECT, all[cut - 1].opcode)
                    assertEquals(Opcode.MOVE_RESULT_OBJECT, all[cut + 1].opcode)
                    assertEquals(Opcode.IF_EQZ, all[cut + 2].opcode)
                    assertTrue("$name: the text's hook after $names", all[0].names(TRANSLATION_TEXT))
                    assertLineHook("$name after $names", all, limit, POST_LINES)
                    assertConfigHook("$name after $names", builderCode, all, POST_CHARS, POST_LINES)
                    assertEquals("$name: hooks after $names", builderCode.size + 2 * (returns + 2) + 6, all.size)
                    assertEquals(made.size, together.after(maker).code().size)
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }
}
