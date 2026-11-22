/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.foldtext

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.posttext.CHARACTER_STYLE
import app.morphe.patches.facebook.feed.posttext.CUT
import app.morphe.patches.facebook.feed.posttext.SPANNABLE_STRING
import app.morphe.patches.facebook.feed.posttext.TRANSLATION_TEXT
import app.morphe.patches.facebook.feed.posttext.captionComponents
import app.morphe.patches.facebook.feed.posttext.captionMarked
import app.morphe.patches.facebook.feed.posttext.captionTypes
import app.morphe.patches.facebook.feed.posttext.callerShaped
import app.morphe.patches.facebook.feed.posttext.foldMarked
import app.morphe.patches.facebook.feed.posttext.foldTypes
import app.morphe.patches.facebook.feed.posttext.postTextBuilders
import app.morphe.patches.facebook.feed.posttext.showFullPostTextPatch
import app.morphe.patches.facebook.feed.posttext.translatePostsPatch
import app.morphe.patches.facebook.media.taptoplay.FRAGMENT_ACTIVITY
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.reels.resolveCalled
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.writersReaching
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
 * line limit, cut config and two cuts, the line limit's class's maker, the comment sheet's cut
 * config, the feed's choice to show a post whole, and a video caption's limit. Then the patch on
 * them: each line limit goes through the extension right after it lands, each config call hands its
 * characters and lines through it first at the call's label, so every jump that went to the call
 * lands on the hook, and the comment's hook sits in the call's try block and hands over the text the
 * cut reads. It also checks that Show full post text's and Translate posts' hooks keep their places
 * in the builder, whatever order the three run in. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
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
        assertEquals("$name: the numbers handed over", listOf(charsRegister, linesRegister), arguments(patched[at]).take(2))
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

    /** Each hook on a fixed cut: through the range form into the same register, with the cut taking it next. */
    private fun assertCutHooks(name: String, code: List<Instruction>, cuts: Int) {
        val hooks = code.indices.filter { code[it].names(POST_CUT) }
        assertEquals("$name: the fixed cut hooks", cuts, hooks.size)
        for (at in hooks) {
            val register = (code[at] as RegisterRangeInstruction).let {
                assertEquals("$name: the fixed cut hook's registers", 1, it.registerCount)
                it.startRegister
            }
            assertEquals(Opcode.MOVE_RESULT_OBJECT, code[at + 1].opcode)
            assertEquals(register, (code[at + 1] as OneRegisterInstruction).registerA)
            val cut = code[at + 2].call()
            assertEquals("$name: no cut after the hook", SPANNABLE_STRING, cut?.returnType)
            assertEquals(CHARACTER_STYLE, cut!!.parameterTypes.first().toString())
            assertEquals("$name: the cut's fixed place", register, arguments(code[at + 2])[4])
        }
    }

    /** The hook right after [at] passing the int [at] left in its register through [hook]. */
    private fun assertPassedOn(name: String, code: List<Instruction>, at: Int, hook: String) {
        val register = (code[at] as OneRegisterInstruction).registerA
        assertTrue("$name: no hook right after index $at", code[at + 1].names(hook))
        assertEquals(register to 1, (code[at + 1] as RegisterRangeInstruction).let { it.startRegister to it.registerCount })
        assertEquals(Opcode.MOVE_RESULT, code[at + 2].opcode)
        assertEquals(register, (code[at + 2] as OneRegisterInstruction).registerA)
    }

    /**
     * The caption's two hooks: on the limit where its ways in meet, and on the server number. They're in
     * [component], or on 588's Android 9 build in the spec class it hands its props to, among [types].
     */
    private fun assertCaptionHooks(name: String, context: BytecodePatchContext, component: String, types: Collection<String>) {
        val hooked = (listOf(component) + (types - component)).flatMap { type ->
            context.mutableClassDefBy(type).methods.filter { method ->
                method.implementation?.instructions?.any { it.names(CAPTION_LINES) } == true
            }
        }
        assertEquals("$name: caption methods hooked", 1, hooked.size)
        val code = hooked.single().code()
        val hooks = code.indices.filter { code[it].names(CAPTION_LINES) }
        assertEquals("$name: the caption's hooks", 2, hooks.size)

        val first = hooks.first()
        assertPassedOn("$name caption limit", code, first - 1, CAPTION_LINES)
        assertTrue("$name: nothing jumps to the limit's hook", jumpsInto(code, first) > 0)
        assertEquals("$name: a jump skips the limit's hook", 0, jumpsInto(code, first + 2))
        val measure = code.indices.single { at ->
            code[at].opcode in setOf(Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE) && code[at].call()?.let { call ->
                call.name == "<init>" && call.definingClass != component &&
                    call.parameterTypes.map(CharSequence::toString) == listOf("I", "I", "Z", "Z")
            } == true
        }
        val limit = (code[first - 1] as OneRegisterInstruction).registerA
        assertEquals("$name: the measure's limit", limit, arguments(code[measure])[2])
        assertEquals("$name: the hook's answer is the only limit the measure can get",
            listOf(first + 1), hooked.single().writersReaching(measure, limit))

        val second = hooks.last()
        // 588's Android 9 build reads the number and turns it into an int in one outlined static call.
        val outlined = code[second - 1].opcode == Opcode.MOVE_RESULT &&
            code[second - 2].opcode in setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE) && code[second - 2].call()?.returnType == "I"
        assertTrue("$name: the server number before its hook is ${code[second - 1].opcode}",
            code[second - 1].opcode == Opcode.LONG_TO_INT || outlined)
        assertPassedOn("$name caption server number", code, second - 1, CAPTION_LINES)
        if (outlined) assertTrue("$name: the server number's hook is right before the goto", code[second + 2].opcode in
            setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32))
    }

    @Test
    fun `each declared build folds a post's text and a comment through the extension`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val found = FixtureDex.methodsWhere(bundle, { true }) {
                    !it.definingClass.startsWith(EXTENSION_CLASSES) && (callerShaped(it) || foldMarked(it) || captionMarked(it) ||
                        traced(it) || hoistedAnswers(it.implementation?.instructions?.toList().orEmpty()).isNotEmpty())
                }
                val builders = postTextBuilders(bundle, found)
                assertEquals("$name: post text builders", 1, builders.size)
                // 588's Android 9 build cuts in the method its builder hands the text to, once for both ways of folding.
                val (textBuilder, wrapper) = builders.single()
                val builder = if (wrapper == null) textBuilder else
                    resolveCalled(FixtureDex.classes(bundle, setOf(wrapper.definingClass)).getValue(wrapper.definingClass), wrapper)!!
                val cuts = if (wrapper == null) 2 else 1
                val foldHooks = 6 + 2 * cuts
                val textCode = textBuilder.code()
                val commentMethods = found.filter(::traced)
                assertEquals("$name: methods naming the comment's trace", 1, commentMethods.size)
                val comment = commentMethods.single()
                val hoistedMethods = found.filter { hoistedAnswers(it.code()).isNotEmpty() }
                assertEquals("$name: methods asking whether a post shows whole", 1, hoistedMethods.size)
                val hoisted = hoistedMethods.single()
                val builderCode = builder.code()
                val limits = builderCode.mapNotNull { it.call() }.filter { lineLimitShaped(it) }
                val limit = limits.distinctBy { it.toString() }.singleOrNull()
                    ?: throw AssertionError("$name: the builder's line limits: $limits")
                // Show full post text's fold and reel caption classes, for running it after or before.
                val folds = foldTypes(found) + captionTypes(bundle, found)
                val classes = FixtureDex.classes(bundle,
                    setOf(textBuilder.definingClass, builder.definingClass, limit.definingClass, comment.definingClass,
                        hoisted.definingClass, FRAGMENT_ACTIVITY) + folds)
                val commentCode = comment.code()
                val stock = { PatchContexts.of((classes.values + ExtensionDex.classDef(SETTINGS_STATUS)).toList()) }

                // Negative control: without the line limit's class, nothing is found, and nothing changes.
                val limitless = PatchContexts.of((classes.filterKeys { it != limit.definingClass }.values +
                    ExtensionDex.classDef(SETTINGS_STATUS)).toList())
                assertThrows("$name: folded with no line limit to read", PatchException::class.java) {
                    foldLongTextPatch.execute(limitless)
                }
                assertEquals("$name: the comment changed before the refusal", commentCode.size, limitless.after(comment).code().size)
                assertEquals("$name: the builder changed before the refusal", textCode.size, limitless.after(textBuilder).code().size)
                if (builder.definingClass != limit.definingClass) {
                    assertEquals("$name: the cut changed before the refusal", builderCode.size, limitless.after(builder).code().size)
                }

                val context = stock()
                foldLongTextPatch.execute(context)

                // The line limit's class holds one maker beside it, the one method there the patch hooked but for the
                // cut itself, which 588's Android 9 build keeps in that class too.
                fun Method.signature() = "$definingClass->$name${parameterTypes.map(CharSequence::toString)}"
                val makers = context.mutableClassDefBy(limit.definingClass).methods.filter { method ->
                    method.signature() != builder.signature() &&
                        method.implementation?.instructions?.any { it.names(POST_LINES) } == true
                }
                assertEquals("$name: makers hooked beside the line limit", 1, makers.size)
                val maker = classes.getValue(limit.definingClass).methods.single {
                    it.name == makers.single().name && it.parameterTypes == makers.single().parameterTypes
                }
                val makerCode = maker.code()

                val post = context.after(builder).code()
                assertEquals("$name: the builder's hooks", builderCode.size + foldHooks, post.size)
                assertEquals(2, post.count { it.names(POST_LINES) })
                assertEquals(1, post.count { it.names(POST_CHARS) })
                assertLineHook("$name builder", post, limit, POST_LINES)
                assertConfigHook("$name builder", builderCode, post, POST_CHARS, POST_LINES)
                assertCutHooks("$name builder", post, cuts)

                val whole = context.after(hoisted).code()
                val answer = hoistedAnswers(whole).single()
                assertPassedOn("$name post shown whole", whole, answer, POST_HOISTED)
                assertEquals("$name: the hook on a post shown whole", hoisted.code().size + 2, whole.size)

                val components = captionComponents(found)
                assertEquals("$name: the caption's component", 1, components.size)
                assertCaptionHooks(name, context, components.single(), classes.keys)

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
                val commentCut = (commentHook + 4 until folded.size).first { index ->
                    folded[index].call()?.let { it.returnType == SPANNABLE_STRING && it.parameterTypes.first().toString() == CHARACTER_STYLE } == true
                }
                assertEquals("$name: the text handed over is the one the cut reads",
                    arguments(folded[commentCut])[2], arguments(folded[commentHook])[2])
                val stockConfig = commentCode.indexOfFirst { it.call()?.toString() == folded[commentHook + 4].call().toString() }
                val tries = catching(comment, stockConfig)
                assertTrue("$name: the comment's config call isn't in a try block", tries.isNotEmpty())
                assertEquals("$name: the hook left the call's try block", tries, catching(patchedComment, commentHook))
                assertEquals(tries, catching(patchedComment, commentHook + 4))

                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "foldLongText" }
                assertEquals("$name: SettingsStatus.foldLongText() isn't switched on", 1,
                    (status.implementation!!.instructions.first() as NarrowLiteralInstruction).narrowLiteral)

                // Whatever order the three run in, each keeps its hooks in the builder (and the cut's method).
                val returns = textCode.count { it.opcode == Opcode.RETURN_OBJECT }
                val orders: List<List<Patch>> = listOf(
                    listOf(foldLongTextPatch, showFullPostTextPatch, translatePostsPatch),
                    listOf(showFullPostTextPatch, translatePostsPatch, foldLongTextPatch),
                    listOf(translatePostsPatch, foldLongTextPatch, showFullPostTextPatch),
                )
                for (order in orders) {
                    val together = stock()
                    order.forEach { it.execute(together) }
                    val all = together.after(textBuilder).code()
                    val names = order.map { it.name }
                    val cut = all.indexOfFirst { it.names(CUT) }
                    assertTrue("$name: no cut hook after $names", cut > 0)
                    assertEquals(Opcode.MOVE_RESULT_OBJECT, all[cut - 1].opcode)
                    assertEquals(Opcode.MOVE_RESULT_OBJECT, all[cut + 1].opcode)
                    assertEquals(Opcode.IF_EQZ, all[cut + 2].opcode)
                    assertTrue("$name: the text's hook after $names", all[0].names(TRANSLATION_TEXT))
                    val foldCode = if (wrapper == null) all else together.after(builder).code()
                    assertLineHook("$name after $names", foldCode, limit, POST_LINES)
                    assertConfigHook("$name after $names", builderCode, foldCode, POST_CHARS, POST_LINES)
                    assertCutHooks("$name after $names", foldCode, cuts)
                    if (wrapper == null) {
                        assertEquals("$name: hooks after $names", textCode.size + 2 * (returns + 2) + foldHooks, all.size)
                    } else {
                        assertEquals("$name: hooks after $names", textCode.size + 2 * (returns + 2), all.size)
                        assertEquals("$name: the cut's hooks after $names", builderCode.size + foldHooks, foldCode.size)
                    }
                    assertEquals(made.size, together.after(maker).code().size)
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }
}
