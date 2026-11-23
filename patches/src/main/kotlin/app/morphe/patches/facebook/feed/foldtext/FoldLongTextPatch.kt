/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.foldtext

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.ads.affiliate.FB_USER_SESSION
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.isStringTable
import app.morphe.patches.facebook.feed.methodsNaming
import app.morphe.patches.facebook.feed.posttext.CAPTION_TOGGLE
import app.morphe.patches.facebook.feed.posttext.CHARACTER_STYLE
import app.morphe.patches.facebook.feed.posttext.GRAPHQL_STORY
import app.morphe.patches.facebook.feed.posttext.LINE_LIMIT_KEY
import app.morphe.patches.facebook.feed.posttext.SPANNABLE_STRING
import app.morphe.patches.facebook.feed.posttext.appClass
import app.morphe.patches.facebook.feed.posttext.findPostTextBuilder
import app.morphe.patches.facebook.feed.posttext.postTextCutter
import app.morphe.patches.facebook.feed.posttext.wrappedCutOf
import app.morphe.patches.facebook.feed.posttext.sendersOf
import app.morphe.patches.facebook.feed.posttext.toggled
import app.morphe.patches.facebook.feed.posttext.writes
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.reels.resolveCalled
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.containsLiteralInstruction
import app.morphe.util.executeBytecode
import app.morphe.util.findMutableMethodOf
import app.morphe.util.readsAfter
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val FOLD_PATCH = "Fold long text"

internal const val FOLD_LONG_TEXT = "Lapp/morphe/extension/facebook/feed/FoldLongText;"
internal const val POST_LINES = "$FOLD_LONG_TEXT->postLines(I)I"
internal const val POST_CHARS = "$FOLD_LONG_TEXT->postChars(II)I"
internal const val POST_CUT = "$FOLD_LONG_TEXT->postCut(Ljava/lang/Integer;)Ljava/lang/Integer;"
internal const val POST_HOISTED = "$FOLD_LONG_TEXT->hoisted(I)I"
internal const val COMMENT_LINES = "$FOLD_LONG_TEXT->commentLines(I)I"
internal const val COMMENT_CHARS = "$FOLD_LONG_TEXT->commentChars(IILjava/lang/CharSequence;)I"
internal const val CAPTION_LINES = "$FOLD_LONG_TEXT->captionLines(I)I"

/** The trace Facebook opens around its cut of a comment's text in the comment sheet (587). */
internal const val COMMENT_TRACE = "CommentBodyTruncationUtil.maybeTruncateCommentBody"

/** The kept class Facebook names beside its choice to show a feed post's text whole (588). */
internal const val ATTACHED_STORY_COMPONENTS =
    "Lcom/facebook/feedplugins/plugins/content/textcontent/interfaces/NewsFeedAttachedStoryComponents;"

/** Where a text cut takes the fixed place it may cut at, and that place's type. */
private const val FIXED_CUT_ARGUMENT = 4
private const val FIXED_CUT_TYPE = "Ljava/lang/Integer;"

/** The ints and booleans of the class a video caption's measure lands in: lines, limit, overflow, long. */
private val CAPTION_MEASURE = listOf("I", "I", "Z", "Z")

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
 *
 * Four more places decide a fold without those numbers (#506). The cuts also take a fixed position
 * that beats the config, so it goes through the extension at each cut's label. The feed component
 * decides to show some posts whole, right beside [ATTACHED_STORY_COMPONENTS], and that answer goes
 * through it too. The comment's character budget gets the comment's text, since Facebook's line limit
 * there only counts line breaks. And a video's caption folds by a limit of its own in the component
 * [CAPTION_TOGGLE] names, from the component's field or a server number, which both go through it.
 */
@Suppress("unused")
val foldLongTextPatch = patch(
    // The README table check reads this literal; FOLD_PATCH carries the same text for the messages.
    name = "Fold long text",
    description = "Lets you pick how many lines a long post or comment shows before See more, from 1 to 20, " +
        "so you can fold sooner or read more before tapping. Starts at Facebook's choice. Pick the lines for " +
        "posts in Hushfacebook settings > News feed, and for comments under Comments and writing. " +
        "Reel captions get a count of their own under Reels and Watch. While Show full post text is on, posts still show whole.",
) {
    // Feed already holds the most a group may, and the comment half lives under Comments and writing in settings.
    category("Comments")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        requireStatusMethod("foldLongText")
        // Every place is found before any is changed, so a build missing one keeps Facebook's as it is.
        val inserts = findPostFolds() + findHoistedPost() + findCommentFold() + findCaptionFolds()
        addFolds(inserts)
        enableStatus("foldLongText")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$FOLD_PATCH: $detail")

/**
 * One hook: [code] goes in at [index] of [method]. With [atLabel], the instruction there is a call
 * something may branch to, and the branches land on the hook. Without it, nothing branches there.
 */
internal class FoldInsert(val method: Method, val index: Int, val code: String, val atLabel: Boolean)

private val STATIC_CALLS = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)
private val DIRECT_CALLS = setOf(Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE)
private val ZERO_BRANCHES = setOf(Opcode.IF_EQZ, Opcode.IF_NEZ)
private val INT_MOVES = setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16)
private val GOTOS = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)

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

/** The instructions that pass the int in [register] through [call] and keep its answer there. */
private fun through(register: Int, call: String) = """
    invoke-static/range {v$register .. v$register}, $call
    move-result v$register
"""

/** The instructions that can go on to [index] of [flow], thrown exceptions included. */
private fun comesFrom(flow: ControlFlow, index: Int): List<Int> =
    flow.instructions.indices.filter { index in flow.normal[it] || index in flow.exceptional[it] }

/** The hook after the line limit [method] asks for at [at]: its answer has to be kept right after the call. */
private fun lineHook(method: Method, code: List<Instruction>, at: Int, linesCall: String): FoldInsert {
    val answer = code.getOrNull(at + 1)
    if (answer?.opcode != Opcode.MOVE_RESULT) refuse("${where(method)} doesn't keep its line limit right after asking at $at")
    val register = (answer as OneRegisterInstruction).registerA
    if (at + 2 >= code.size) refuse("${where(method)} ends right after its line limit")
    // After the answer lands: a branch to the next instruction still finds it there.
    return FoldInsert(method, at + 2, through(register, linesCall), atLabel = false)
}

/**
 * The hook on the factory call at [at] in [method], with [text], when there is one, handed over
 * after its two numbers. They go to the extension in the short call form, or the range form when they
 * sit side by side, so all of them have to be named that way.
 */
private fun configHook(
    method: Method,
    code: List<Instruction>,
    at: Int,
    charsCall: String,
    linesCall: String,
    text: Int? = null,
): FoldInsert {
    val registers = arguments(code[at])
    if (registers.size != 2) refuse("${where(method)} makes its cut config at $at from ${registers.size} registers")
    val (chars, lines) = registers
    if (chars == lines) refuse("${where(method)} makes its cut config at $at from one register twice")
    val handed = registers + listOfNotNull(text)
    val sideBySide = handed.zipWithNext().all { (first, next) -> next == first + 1 }
    if (handed.any { it > 15 } && !sideBySide) {
        refuse("${where(method)} makes its cut config at $at from $handed, which no call can hand on")
    }
    if (handed.any { it > 255 }) refuse("${where(method)} keeps its cut config's numbers past v255")
    val handOver = if (handed.all { it <= 15 }) {
        "invoke-static {${handed.joinToString { "v$it" }}}, $charsCall"
    } else {
        "invoke-static/range {v${handed.first()} .. v${handed.last()}}, $charsCall"
    }
    // The characters first, while the line limit beside them is still Facebook's.
    return FoldInsert(
        method,
        at,
        """
            $handOver
            move-result v$chars
            ${through(lines, linesCall)}
        """,
        atLabel = true,
    )
}

/** Whether [call] cuts a text and takes a fixed place to cut at. */
private fun takesFixedCut(call: MethodReference) =
    call.returnType == SPANNABLE_STRING && call.parameterTypes.size > FIXED_CUT_ARGUMENT &&
        call.parameterTypes[0].toString() == CHARACTER_STYLE &&
        call.parameterTypes[FIXED_CUT_ARGUMENT].toString() == FIXED_CUT_TYPE

/** The hook on the fixed place the cut at [at] in [method] is handed, at the cut's label. */
private fun fixedCutHook(method: Method, code: List<Instruction>, at: Int): FoldInsert {
    val register = arguments(code[at]).getOrNull(FIXED_CUT_ARGUMENT)
        ?: refuse("${where(method)} hands the cut at $at no fixed place")
    if (register > 255) refuse("${where(method)} keeps the cut's fixed place past v255")
    return FoldInsert(
        method,
        at,
        """
            invoke-static/range {v$register .. v$register}, $POST_CUT
            move-result-object v$register
        """,
        atLabel = true,
    )
}

/**
 * The post hooks. In the builder, the line limit is the one static call shaped like it
 * ([lineLimitShaped]) whose method reads [LINE_LIMIT_KEY], the factory is the one static `(II)` call
 * making a config a cut there takes ([cutConfigs]), and the two cuts, one for each way of folding,
 * take the fixed place. The maker is the one static method of the line limit's class that answers
 * that config and calls both, once each.
 *
 * 588's Android 9 build moves all of that into the method the builder hands its text to
 * ([postTextCutter], `LX/047J;->A06`), where both ways make their config and join at one cut.
 */
internal fun BytecodePatchContext.findPostFolds(): List<FoldInsert> {
    val found = findPostTextBuilder(FOLD_PATCH)
    val wrapped = wrappedCutOf(found) != null
    val builder = mutable(postTextCutter(found, FOLD_PATCH))
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
    val cuts = code.indices.filter { at -> staticCall(code[at])?.let(::takesFixedCut) == true }
    val ways = if (wrapped) 1 else 2
    if (cuts.size != ways) refuse("expected ${where(builder)} to hand a fixed place to $ways cuts, found ${cuts.size}")

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
        lineHook(builder, code, limitAt, POST_LINES),
        configHook(builder, code, factoryAt, POST_CHARS, POST_LINES),
        lineHook(maker, makerCode, callsOf(makerCode, limit).single(), POST_LINES),
        configHook(maker, makerCode, callsOf(makerCode, factory).single(), POST_CHARS, POST_LINES),
    ) + cuts.map { fixedCutHook(builder, code, it) }
}

/**
 * Where [code] keeps the answer to whether a feed post shows whole: a boolean asked from the user
 * session right after [ATTACHED_STORY_COMPONENTS] is named and a null check.
 */
internal fun hoistedAnswers(code: List<Instruction>): List<Int> = code.indices.filter { at ->
    code[at].opcode == Opcode.CONST_CLASS &&
        ((code[at] as ReferenceInstruction).reference as? TypeReference)?.type == ATTACHED_STORY_COMPONENTS &&
        code.getOrNull(at + 1)?.opcode == Opcode.IF_EQZ &&
        code.getOrNull(at + 2)?.let(::staticCall)?.let { call ->
            call.returnType == "Z" && call.parameterTypes.firstOrNull()?.toString() == FB_USER_SESSION
        } == true &&
        code.getOrNull(at + 3)?.opcode == Opcode.MOVE_RESULT
}.map { it + 3 }

/**
 * The hook on the one answer [hoistedAnswers] finds. An int goes back where a boolean was, so
 * everything reading it has to be a check against zero.
 */
internal fun BytecodePatchContext.findHoistedPost(): FoldInsert {
    val found = mutableListOf<Method>()
    classDefForEachReferencing(types = listOf(ATTACHED_STORY_COMPONENTS)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        classDef.methods.filterTo(found) { hoistedAnswers(it.code()).isNotEmpty() }
    }
    val stock = found.singleOrNull()
        ?: refuse("expected one method asking whether a feed post shows whole, found ${found.size}")
    val method = mutable(stock)
    val code = method.code()
    val answer = hoistedAnswers(code).singleOrNull() ?: refuse("${where(method)} asks more than once whether a post shows whole")
    val register = (code[answer] as OneRegisterInstruction).registerA
    val readers = method.readsAfter(answer, register)
    if (readers.isEmpty() || readers.any { code[it].opcode !in ZERO_BRANCHES }) {
        refuse("${where(method)} reads whether a post shows whole other than by checking it, at $readers")
    }
    val next = answer + 1
    if (comesFrom(ControlFlow.of(method), next) != listOf(answer)) refuse("${where(method)} jumps in right after that answer")
    return FoldInsert(method, next, through(register, POST_HOISTED), atLabel = false)
}

/**
 * The comment hook: the one factory call in the one method naming [COMMENT_TRACE], which hands the
 * extension the comment's text too. That's what the cut taking this config reads, so the register
 * has to hold it already at the factory call.
 */
internal fun BytecodePatchContext.findCommentFold(): FoldInsert {
    val methods = methodsNaming(COMMENT_TRACE) { it.implementation != null && !isStringTable(it) }
    val found = methods.singleOrNull() ?: refuse("expected one method naming \"$COMMENT_TRACE\", found ${methods.size}")
    val method = mutable(found)
    val code = method.code()
    val configs = cutConfigs(code)
    val factories = code.indices.filter { at -> staticCall(code[at])?.let { makesConfig(it, configs) } == true }
    val at = factories.singleOrNull()
        ?: refuse("expected ${where(method)} to make one cut config from characters and lines, found ${factories.size}")

    val made = code.getOrNull(at + 1)
    if (made?.opcode != Opcode.MOVE_RESULT_OBJECT) refuse("${where(method)} doesn't keep its cut config right after making it")
    val config = (made as OneRegisterInstruction).registerA
    val cut = (at + 1 until code.size).firstOrNull { index ->
        staticCall(code[index])?.let { it.returnType == SPANNABLE_STRING && it.parameterTypes.firstOrNull()?.toString() == CHARACTER_STYLE } == true
    } ?: refuse("${where(method)} doesn't cut a comment after making its config")
    val handed = arguments(code[cut])
    if (handed.getOrNull(1) != config) refuse("${where(method)} cuts a comment with something other than the config it made")
    val text = handed.getOrNull(2) ?: refuse("${where(method)} hands its cut no text")

    val flow = ControlFlow.of(method)
    for (index in at until cut) {
        if (flow.normal[index] != listOf(index + 1) || writes(code[index], text)) {
            refuse("${where(method)} doesn't hold the comment's text in v$text from its config to its cut")
        }
    }
    return configHook(method, code, at, COMMENT_CHARS, COMMENT_LINES, text)
}

/** Where [code] makes the class a caption's measure lands in, which isn't [component] itself. */
private fun captionMeasures(code: List<Instruction>, component: String): List<Int> = code.indices.filter { at ->
    code[at].opcode in DIRECT_CALLS && ((code[at] as ReferenceInstruction).reference as? MethodReference)?.let { call ->
        call.name == "<init>" && call.returnType == "V" && call.definingClass != component &&
            call.parameterTypes.map { it.toString() } == CAPTION_MEASURE
    } == true
}

/**
 * Where [code] picks a caption's limit between a server number and the measure's, each index being
 * where the server number lands in its register, with nothing else in between there and the
 * instruction after it. Either a `long-to-int` moved into a register, a goto, then an int field of
 * [measure] moved into the same register and a goto to the same place, or (588's Android 9 build,
 * which outlines the server read and its `long-to-int` into `LX/01CA;->A03`) a static call's int
 * answer kept with `move-result`, a goto, then that int field read straight into the same register
 * and a goto to the same place.
 */
private fun captionLimitChoices(code: List<Instruction>, flow: ControlFlow, measure: String): List<Int> =
    code.indices.filter { at -> convertedNumber(code, flow, measure, at) || calledNumber(code, flow, measure, at) }

/** The int field of [measure] [instruction] reads, as the read, or null. */
private fun measureRead(instruction: Instruction?, measure: String): TwoRegisterInstruction? {
    if (instruction?.opcode != Opcode.IGET) return null
    val read = (instruction as ReferenceInstruction).reference as FieldReference
    return (instruction as TwoRegisterInstruction).takeIf { read.type == "I" && read.definingClass == measure }
}

private fun convertedNumber(code: List<Instruction>, flow: ControlFlow, measure: String, at: Int): Boolean {
    if (at + 5 >= code.size) return false
    val number = code[at] as? TwoRegisterInstruction
    val moved = code[at + 1] as? TwoRegisterInstruction
    val field = measureRead(code[at + 3], measure)
    val kept = code[at + 4] as? TwoRegisterInstruction
    return number != null && moved != null && field != null && kept != null &&
        code[at].opcode == Opcode.LONG_TO_INT &&
        code[at + 1].opcode in INT_MOVES && moved.registerB == number.registerA &&
        code[at + 2].opcode in GOTOS &&
        code[at + 4].opcode in INT_MOVES && kept.registerB == field.registerA && kept.registerA == moved.registerA &&
        code[at + 5].opcode in GOTOS && flow.normal[at + 2] == flow.normal[at + 5]
}

private fun calledNumber(code: List<Instruction>, flow: ControlFlow, measure: String, at: Int): Boolean {
    if (at < 1 || at + 3 >= code.size) return false
    if (staticCall(code[at - 1])?.returnType != "I" || code[at].opcode != Opcode.MOVE_RESULT) return false
    val field = measureRead(code[at + 2], measure) ?: return false
    return code[at + 1].opcode in GOTOS && field.registerA == (code[at] as OneRegisterInstruction).registerA &&
        code[at + 3].opcode in GOTOS && flow.normal[at + 1] == flow.normal[at + 3]
}

/**
 * The methods outside [component] that its own methods call and that measure its caption, each with
 * every call of it from [owner]'s methods (588's Android 9 build moves a component's work into a spec
 * class it holds in a static field, handing it the props).
 */
private fun BytecodePatchContext.delegatedMeasures(owner: ClassDef, component: String): Map<Method, List<Pair<Method, Int>>> {
    val calls = owner.methods.flatMap { method ->
        val code = method.code()
        code.indices.filter { index ->
            code[index].opcode in ANY_CALLS &&
                ((code[index] as ReferenceInstruction).reference as MethodReference).definingClass.let { it != component && appClass(it) }
        }.map { method to it }
    }
    val resolved = mutableMapOf<String, Method?>()
    return calls.groupBy { (method, index) ->
        val call = (method.code()[index] as ReferenceInstruction).reference as MethodReference
        resolved.getOrPut(call.toString()) {
            classDefByOrNull(call.definingClass)?.let { resolveCalled(it, call) }
                ?.takeIf { captionMeasures(it.code(), component).isNotEmpty() }
        }
    }.filterKeys { it != null }.mapKeys { it.key!! }
}

/** Whether [code] of [method] fills v[register] at [at] from an int field of [component], through int moves only. */
private fun fromComponentField(method: Method, code: List<Instruction>, at: Int, register: Int, component: String): Boolean {
    var index = at
    var source = register
    repeat(8) {
        val writer = method.writersReaching(index, source).singleOrNull()?.takeIf { it >= 0 } ?: return false
        val instruction = code[writer]
        when (instruction.opcode) {
            Opcode.IGET -> return ((instruction as ReferenceInstruction).reference as FieldReference).let { field ->
                field.type == "I" && field.definingClass == component
            }
            in INT_MOVES -> {
                index = writer
                source = (instruction as TwoRegisterInstruction).registerB
            }
            else -> return false
        }
    }
    return false
}

/** The number of registers [method]'s parameters take, `this` included. */
private fun Method.parameterRegisters(): Int = (if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1) +
    parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }

private val ANY_CALLS = setOf(
    Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_SUPER, Opcode.INVOKE_DIRECT, Opcode.INVOKE_STATIC, Opcode.INVOKE_INTERFACE,
    Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_SUPER_RANGE, Opcode.INVOKE_DIRECT_RANGE, Opcode.INVOKE_STATIC_RANGE,
    Opcode.INVOKE_INTERFACE_RANGE,
)

/**
 * The two hooks of a video's caption, in the method of the component [CAPTION_TOGGLE] names that
 * measures it. The limit handed to the measure starts as the component's field, and Facebook can
 * swap it for a fixed number or one worked out from the text. All of those meet right after the
 * last of them in the code, and the first hook goes there. Past the measure, a server number can
 * take the measure's limit's place, and it goes through the second.
 *
 * 588's Android 9 build measures in the spec method the component hands its props to
 * (`LX/09TV;->A02`, called from `LX/09TU;->A16`), where the limit starts as the parameter the
 * component fills from that same field.
 */
internal fun BytecodePatchContext.findCaptionFolds(): List<FoldInsert> {
    val components = sendersOf(CAPTION_TOGGLE).flatMap { toggled(it, CAPTION_TOGGLE) }.toSet()
    val component = components.singleOrNull() ?: refuse("expected one component after \"$CAPTION_TOGGLE\", found $components")
    val owner = classDefByOrNull(component) ?: refuse("$component isn't in this build")
    val own = owner.methods.filter { captionMeasures(it.code(), component).isNotEmpty() }
    val delegated = if (own.isEmpty()) delegatedMeasures(owner, component) else emptyMap()
    val measuring = own.ifEmpty { delegated.keys.toList() }
    val found = measuring.singleOrNull() ?: refuse("expected one method of $component or its spec measuring its caption, found ${measuring.size}")
    val callers = delegated[found].orEmpty()
    val method = mutable(found)
    val code = method.code()
    val at = captionMeasures(code, component).singleOrNull() ?: refuse("${where(method)} measures its caption more than once")
    val measure = ((code[at] as ReferenceInstruction).reference as MethodReference).definingClass

    // The arguments start with the new object: lines, then the limit.
    val limit = arguments(code[at]).getOrNull(2) ?: refuse("${where(method)} hands its caption's measure no limit")
    if (limit > 255) refuse("${where(method)} keeps its caption's limit past v255")
    val writers = method.writersReaching(at, limit)
    // In a spec, the parameter the limit starts as is the one every call from the component fills from its field.
    val firstParameter = method.implementation!!.registerCount - method.parameterRegisters()
    fun handedField(writer: Int): Boolean {
        if (callers.isEmpty() || code[writer].opcode !in INT_MOVES) return false
        val parameter = (code[writer] as TwoRegisterInstruction).registerB
        if (parameter < firstParameter || method.writersReaching(writer, parameter) != listOf(-1)) return false
        return callers.all { (caller, call) ->
            val callerCode = caller.code()
            val handed = arguments(callerCode[call]).getOrNull(parameter - firstParameter)
            handed != null && fromComponentField(caller, callerCode, call, handed, component)
        }
    }
    val startsFromField = writers.any { writer ->
        writer >= 0 && (code[writer].opcode == Opcode.IGET &&
            ((code[writer] as ReferenceInstruction).reference as FieldReference).let { field ->
                field.type == "I" && field.definingClass == component
            } || handedField(writer))
    }
    if (!startsFromField || -1 in writers) refuse("${where(method)} sets its caption's limit from $writers")
    val join = (writers.filter { it < at }.maxOrNull() ?: refuse("${where(method)} sets its caption's limit from $writers")) + 1

    // Every value the limit can take has to pass the place the hook goes on its way to the measure.
    val flow = ControlFlow.of(method)
    val seen = writers.toMutableSet()
    val waiting = ArrayDeque(writers)
    while (waiting.isNotEmpty()) {
        val from = waiting.removeLast()
        for (next in flow.normal[from] + flow.exceptional[from]) {
            if (next == join) continue
            if (next == at) refuse("${where(method)} reaches its caption's measure around index $join")
            if (seen.add(next)) waiting.addLast(next)
        }
    }

    val choice = captionLimitChoices(code, flow, measure).singleOrNull()
        ?: refuse("expected ${where(method)} to pick its caption's limit between a server number and the measure's once")
    val moved = choice + 1
    if (comesFrom(flow, moved) != listOf(choice)) refuse("${where(method)} jumps in right after its caption's server number")
    val number = (code[choice] as OneRegisterInstruction).registerA
    return listOf(
        FoldInsert(method, join, through(limit, CAPTION_LINES), atLabel = true),
        FoldInsert(method, moved, through(number, CAPTION_LINES), atLabel = false),
    )
}

/** Adds [inserts], from the last one up in each method, so each index still holds when its turn comes. */
internal fun BytecodePatchContext.addFolds(inserts: List<FoldInsert>) {
    for (inMethod in inserts.groupBy { it.method.toString() }.values) {
        val method = mutable(inMethod.first().method)
        for (insert in inMethod.sortedByDescending { it.index }) {
            if (insert.atLabel) {
                method.addInstructionsAtControlFlowLabel(insert.index, insert.code)
            } else {
                method.addInstructions(insert.index, insert.code)
            }
        }
    }
}
