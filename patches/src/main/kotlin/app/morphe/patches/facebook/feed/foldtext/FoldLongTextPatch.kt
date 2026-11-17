/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.foldtext

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.ads.affiliate.FB_USER_SESSION
import app.morphe.patches.facebook.feed.isStringTable
import app.morphe.patches.facebook.feed.methodsNaming
import app.morphe.patches.facebook.feed.posttext.CHARACTER_STYLE
import app.morphe.patches.facebook.feed.posttext.GRAPHQL_STORY
import app.morphe.patches.facebook.feed.posttext.LINE_LIMIT_KEY
import app.morphe.patches.facebook.feed.posttext.SPANNABLE_STRING
import app.morphe.patches.facebook.feed.posttext.appClass
import app.morphe.patches.facebook.feed.posttext.findPostTextBuilder
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.containsLiteralInstruction
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val FOLD_PATCH = "Fold long text"

internal const val FOLD_LONG_TEXT = "Lapp/morphe/extension/facebook/feed/FoldLongText;"
internal const val POST_LINES = "$FOLD_LONG_TEXT->postLines(I)I"
internal const val POST_CHARS = "$FOLD_LONG_TEXT->postChars(II)I"
internal const val COMMENT_LINES = "$FOLD_LONG_TEXT->commentLines(I)I"
internal const val COMMENT_CHARS = "$FOLD_LONG_TEXT->commentChars(II)I"

/** The trace Facebook opens around its cut of a comment's text in the comment sheet (587). */
internal const val COMMENT_TRACE = "CommentBodyTruncationUtil.maybeTruncateCommentBody"

/**
 * A long post's text and a long comment fold after the number of lines the reader picks (#497).
 *
 * Facebook cuts a post's text in Show full post text's builder ([findPostTextBuilder]) one of two
 * ways, and both take a config made from two numbers. With the text's width known, the config holds
 * a line limit, the one a static method reading [LINE_LIMIT_KEY] answers (587 `LX/03GS;->A00`), and
 * the cut comes after that many laid out lines. Otherwise a static `(II)` factory makes it from a
 * character budget and a line limit (587 `LX/03Eg;->A00`), and the cut comes after whichever runs
 * out first. The line limit's class makes the same config again for the post's whole text to ask
 * whether it runs long (587 `LX/03GS;->A02`). A comment in the comment sheet is cut through the same
 * factory, from 400 characters and 5 lines unless the server says otherwise, in the one method that
 * names [COMMENT_TRACE].
 *
 * Each line limit goes through the extension right after it's answered, and each factory call hands
 * its two numbers through it first, at the call's label, so a branch to the call (587's maker has
 * three) passes the hooks too. The extension answers Facebook's own numbers until a count is picked.
 */
@Suppress("unused")
val foldLongTextPatch = bytecodePatch(
    // The README table check reads this literal; FOLD_PATCH carries the same text for the messages.
    name = "Fold long text",
    description = "Lets you pick how many lines a long post or comment shows before See more, from 1 to 20, " +
        "so you can fold sooner or read more before tapping. Starts at Facebook's choice. Pick the lines for " +
        "posts in Hushfacebook settings > News feed, and for comments under Comments and writing. " +
        "While Show full post text is on, posts still show whole.",
) {
    // Feed already holds the most a group may, and the comment half lives under Comments and writing in settings.
    category("Comments")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        requireStatusMethod("foldLongText")
        // Every place is found before any is changed, so a build missing one keeps Facebook's as it is.
        val hooks = findPostFolds() + findCommentFold()
        addFolds(hooks)
        enableStatus("foldLongText")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$FOLD_PATCH: $detail")

/**
 * One hook: in [method], [lines] is a line limit and [chars], when there is one, the character
 * budget beside it. Without [chars], [index] is right after the line limit's answer lands, and the
 * hook goes there. With it, [index] is the factory call that takes both, and the hook goes at its
 * label. [comment] picks the comment's hooks over the post's.
 */
internal class FoldHook(val method: Method, val index: Int, val lines: Int, val chars: Int?, val comment: Boolean)

private val STATIC_CALLS = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)

/** The method a static call names, or null for any other instruction. */
private fun staticCall(instruction: Instruction): MethodReference? =
    if (instruction.opcode in STATIC_CALLS) (instruction as ReferenceInstruction).reference as? MethodReference else null

/** The registers [instruction] hands its call, in order. */
internal fun arguments(instruction: Instruction): List<Int> = when (instruction) {
    is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
    is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE,
        instruction.registerF, instruction.registerG).take(instruction.registerCount)
    else -> emptyList()
}

/**
 * The configs a text cut in [code] takes: the second parameter's type of each static call answering
 * a SpannableString from a CharacterStyle first (587 `LX/03Eh;->A05` and `A06`, both taking `LX/03Eg;`).
 */
internal fun cutConfigs(code: List<Instruction>): Set<String> = code.mapNotNull(::staticCall).filter { call ->
    call.returnType == SPANNABLE_STRING && call.parameterTypes.size >= 2 && call.parameterTypes[0].toString() == CHARACTER_STYLE
}.map { it.parameterTypes[1].toString() }.filter(::appClass).toSet()

/** Whether [call] makes one of [configs] from two ints: the character budget, then the line limit. */
internal fun makesConfig(call: MethodReference, configs: Set<String>) =
    call.returnType in configs && call.parameterTypes.map { it.toString() } == listOf("I", "I")

/** Whether [call] is shaped like a post's line limit: an int from the user session first and the story among the rest. */
internal fun lineLimitShaped(call: MethodReference) =
    call.returnType == "I" && call.parameterTypes.size >= 2 && call.parameterTypes[0].toString() == FB_USER_SESSION &&
        call.parameterTypes.any { it.toString() == GRAPHQL_STORY }

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).findMutableMethodOf(method)

private fun where(method: Method) = "${method.definingClass}->${method.name}"

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

/** Where [code] calls [call]. */
private fun callsOf(code: List<Instruction>, call: MethodReference): List<Int> =
    code.indices.filter { staticCall(code[it])?.toString() == call.toString() }

/** The hook after the line limit [method] asks for at [at]: its answer has to be kept right after the call. */
private fun lineHook(method: Method, code: List<Instruction>, at: Int, comment: Boolean): FoldHook {
    val answer = code.getOrNull(at + 1)
    if (answer?.opcode != Opcode.MOVE_RESULT) refuse("${where(method)} doesn't keep its line limit right after asking at $at")
    val register = (answer as OneRegisterInstruction).registerA
    if (at + 2 >= code.size) refuse("${where(method)} ends right after its line limit")
    return FoldHook(method, at + 2, register, null, comment)
}

/**
 * The hook on the factory call at [at] in [method]. Its numbers are handed to the extension in the
 * short call form, or the range form when they sit side by side, so both have to be named that way.
 */
private fun configHook(method: Method, code: List<Instruction>, at: Int, comment: Boolean): FoldHook {
    val registers = arguments(code[at])
    if (registers.size != 2) refuse("${where(method)} makes its cut config at $at from ${registers.size} registers")
    val (chars, lines) = registers
    if (chars == lines) refuse("${where(method)} makes its cut config at $at from one register twice")
    if ((chars > 15 || lines > 15) && lines != chars + 1) {
        refuse("${where(method)} makes its cut config at $at from v$chars and v$lines, which no call can hand on")
    }
    if (chars > 255 || lines > 255) refuse("${where(method)} keeps its cut config's numbers past v255")
    return FoldHook(method, at, lines, chars, comment)
}

/**
 * The four post hooks. In the builder, the line limit is the one static call shaped like it
 * ([lineLimitShaped]) whose method reads [LINE_LIMIT_KEY], and the factory is the one static `(II)`
 * call making a config a cut there takes ([cutConfigs]). The maker is the one static method of the
 * line limit's class that answers that config and calls both, once each.
 */
internal fun BytecodePatchContext.findPostFolds(): List<FoldHook> {
    val builder = mutable(findPostTextBuilder(FOLD_PATCH))
    val code = builder.code()
    val readsLineLimit = { call: MethodReference ->
        classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) }?.containsLiteralInstruction(LINE_LIMIT_KEY) == true
    }
    val limits = code.indices.filter { at -> staticCall(code[at])?.let { lineLimitShaped(it) && readsLineLimit(it) } == true }
    val limitAt = limits.singleOrNull() ?: refuse("expected ${where(builder)} to ask for one post's line limit, found ${limits.size}")
    val limit = staticCall(code[limitAt])!!

    val configs = cutConfigs(code)
    if (configs.isEmpty()) refuse("${where(builder)} hands no cut config to a cut")
    val factories = code.indices.filter { at -> staticCall(code[at])?.let { makesConfig(it, configs) } == true }
    val factoryAt = factories.singleOrNull()
        ?: refuse("expected ${where(builder)} to make one cut config from characters and lines, found ${factories.size}")
    val factory = staticCall(code[factoryAt])!!

    val owner = classDefByOrNull(limit.definingClass) ?: refuse("${limit.definingClass} isn't in this build")
    val makers = owner.methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == factory.returnType &&
            callsOf(method.code(), limit).size == 1 && callsOf(method.code(), factory).size == 1
    }
    val maker = mutable(
        makers.singleOrNull()
            ?: refuse("expected one method of ${owner.type} making a ${factory.returnType} from its line limit, found ${makers.size}"),
    )
    val makerCode = maker.code()
    return listOf(
        lineHook(builder, code, limitAt, comment = false),
        configHook(builder, code, factoryAt, comment = false),
        lineHook(maker, makerCode, callsOf(makerCode, limit).single(), comment = false),
        configHook(maker, makerCode, callsOf(makerCode, factory).single(), comment = false),
    )
}

/** The comment hook: the one factory call in the one method naming [COMMENT_TRACE]. */
internal fun BytecodePatchContext.findCommentFold(): FoldHook {
    val methods = methodsNaming(COMMENT_TRACE) { it.implementation != null && !isStringTable(it) }
    val found = methods.singleOrNull() ?: refuse("expected one method naming \"$COMMENT_TRACE\", found ${methods.size}")
    val method = mutable(found)
    val code = method.code()
    val configs = cutConfigs(code)
    val factories = code.indices.filter { at -> staticCall(code[at])?.let { makesConfig(it, configs) } == true }
    val at = factories.singleOrNull()
        ?: refuse("expected ${where(method)} to make one cut config from characters and lines, found ${factories.size}")
    return configHook(method, code, at, comment = true)
}

/** Adds [hooks], from the last one up in each method, so each index still holds when its turn comes. */
internal fun BytecodePatchContext.addFolds(hooks: List<FoldHook>) {
    for (inMethod in hooks.groupBy { it.method.toString() }.values) {
        val method = mutable(inMethod.first().method)
        for (hook in inMethod.sortedByDescending { it.index }) {
            val linesCall = if (hook.comment) COMMENT_LINES else POST_LINES
            val lines = """
                invoke-static/range {v${hook.lines} .. v${hook.lines}}, $linesCall
                move-result v${hook.lines}
            """
            val chars = hook.chars
            if (chars == null) {
                // After the answer lands: a branch to the next instruction still finds it there.
                method.addInstructions(hook.index, lines)
                continue
            }
            val charsCall = if (hook.comment) COMMENT_CHARS else POST_CHARS
            val handOver = if (chars <= 15 && hook.lines <= 15) {
                "invoke-static {v$chars, v${hook.lines}}, $charsCall"
            } else {
                "invoke-static/range {v$chars .. v${hook.lines}}, $charsCall"
            }
            // The characters first, while the line limit beside them is still Facebook's.
            method.addInstructionsAtControlFlowLabel(
                hook.index,
                """
                    $handOver
                    move-result v$chars
                    $lines
                """,
            )
        }
    }
}
