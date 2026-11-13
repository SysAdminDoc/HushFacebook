/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.posttext

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.isStringTable
import app.morphe.patches.facebook.feed.methodsNaming
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.feed.tableStringAt
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.containsLiteralInstruction
import app.morphe.util.findMutableMethodOf
import app.morphe.util.returnEarly
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Format
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

internal const val PATCH = "Show full post text"

/** The key Facebook reads a post's line limit under (message_truncation_line_limit, 584). */
internal const val LINE_LIMIT_KEY = 0x32d58eaeL

internal const val CHARACTER_STYLE = "Landroid/text/style/CharacterStyle;"
internal const val SPANNABLE = "Landroid/text/Spannable;"
internal const val SPANNABLE_STRING = "Landroid/text/SpannableString;"
internal const val GRAPHQL_STORY = "Lcom/facebook/graphql/model/GraphQLStory;"

/** The feed post component's spec name, in the method that sets a post's first state (585). */
internal const val COMPONENT_SPEC = "FeedStoryContentWithExtensionComponentSpec"

/** The state update a tap on a post's text sends, right before it names the component's class (585). */
internal const val TOGGLE = "updateState:FeedStoryContentWithExtensionComponent.toggleAllowTruncation"

/** The state update "more" on a reel caption sends, right before it names the caption's component (585). */
internal const val CAPTION_TOGGLE = "updateState:FbShortsViewerDescriptionComponent.doTextExpansion"

internal const val FULL_POST_TEXT = "Lapp/morphe/extension/facebook/feed/FullPostText;"
internal const val CUT = "$FULL_POST_TEXT->cut(${SPANNABLE_STRING}Ljava/lang/Object;)$SPANNABLE_STRING"
internal const val FIRST_STATE = "$FULL_POST_TEXT->allowTruncation(Z)Z"
internal const val TAP = "$FULL_POST_TEXT->truncation(Ljava/lang/Object;Z)V"
internal const val CAPTION_FIRST_STATE = "$FULL_POST_TEXT->reelCaptionExpanded(Z)Z"

private const val BOOLEAN = "Ljava/lang/Boolean;"
private const val BOOLEAN_VALUE = "$BOOLEAN->booleanValue()Z"
private const val BOOLEAN_OF = "$BOOLEAN->valueOf(Z)$BOOLEAN"

private val RETURNS = setOf(Opcode.RETURN, Opcode.RETURN_VOID, Opcode.RETURN_OBJECT, Opcode.RETURN_WIDE)

private val GOTOS = setOf(Format.Format10t, Format.Format20t, Format.Format30t)

private val SWITCHES = setOf(Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH)

/**
 * A post's text shows whole in the feed (issue #268), and a tap folds it back (#354). Facebook cuts
 * a long post's text to the line limit it reads under [LINE_LIMIT_KEY] and adds See more after it.
 * 585's `LX/03Bn;->A05` takes the post's text (a Spannable) and the See more style (a
 * CharacterStyle), asks a helper for the cut text, and gets null back when the text already fits,
 * in which case the whole text goes out as it is. Right after that answer, the extension swaps the
 * cut text for null while the switch is on, unless the reader folded that post.
 *
 * The feed's post component (`FeedStoryContentWithExtensionComponent`) keeps an "allow truncation"
 * state per post that starts true on the feed, and a tap on the text flips it. Its first value goes
 * through the extension, which starts it false while the switch is on, so the first tap folds the
 * post by Facebook's own path. Each flip goes through the extension with the post's story right
 * after it's stored, so it knows which posts were folded.
 *
 * A reel's caption (`FbShortsViewerDescriptionComponent`, 585 `LX/09as;`) keeps an "expanded" state
 * that "more" flips through [CAPTION_TOGGLE]. Its first value is the component's prop (585 `A0G`),
 * unless Facebook remembers that reel's caption from an earlier tap. The prop goes through the
 * extension as it's read, so with the switch on a caption starts whole and a fold is still kept (#268).
 *
 * An open reel caption can also sit on a dark box (#419): right before the caption's text is built,
 * [CAPTION_BACKDROP] gets the builder's line limit and hands back a background for the one built with
 * no limit. See [findCaptionBackdrop].
 */
@Suppress("unused")
val showFullPostTextPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Show full post text",
    description = "Shows all of a post's text in the feed, and a reel's caption, so you don't have to tap See more. " +
        "A tap on the text folds it back. Starts off. Turn posts on in Hushfacebook settings > News feed, " +
        "and reel captions under Reels and Watch, where an open caption can also sit on a dark box like subtitles.",
) {
    category("Feed")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val builder = findPostTextBuilder(PATCH)
        val method = mutableClassDefBy(builder.definingClass).findMutableMethodOf(builder)
        // Translate posts adds to this method too, and may have run first, so the place is read from it as it is now.
        val cut = textCut(method)
        val fold = findFold(cut.storyField)
        // At the null check's label: 587's second cut reaches it by a goto.
        method.addInstructionsAtControlFlowLabel(
            cut.hookIndex,
            """
                invoke-static { v${cut.register}, v${cut.story} }, $CUT
                move-result-object v${cut.register}
            """,
        )
        mutableClassDefBy(fold.first.definingClass).findMutableMethodOf(fold.first).addInstructions(
            fold.firstIndex,
            """
                invoke-static/range { v${fold.firstValue} .. v${fold.firstValue} }, $FIRST_STATE
                move-result v${fold.firstValue}
            """,
        )
        // After the flip is stored, so a branch to the store (585 has one) still passes the hook.
        mutableClassDefBy(fold.tap.definingClass).findMutableMethodOf(fold.tap).addInstructions(
            fold.tapIndex + 1,
            """
                iget-object v${fold.props}, v${fold.props}, ${cut.storyField}
                invoke-static { v${fold.props}, v${fold.tapValue} }, $TAP
            """,
        )
        val caption = findCaptionStart()
        mutableClassDefBy(caption.method.definingClass).findMutableMethodOf(caption.method).addInstructions(
            caption.index + 1,
            """
                invoke-static/range { v${caption.register} .. v${caption.register} }, $CAPTION_FIRST_STATE
                move-result v${caption.register}
            """,
        )
        // The dark box is its own switch's; a build missing one of its anchors keeps the rest.
        try {
            addCaptionBackdrop(findCaptionBackdrop())
        } catch (missing: PatchException) {
            patchLog.warning("${missing.message}, so the dark box behind open reel captions isn't in this build")
        }
        enableStatus("fullPostText")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * The feed's text cut: its method, where the hook goes (right after the cut's answer lands), the
 * answer's register, and the story's register and the props field it's read from.
 */
internal class TextCut(val method: Method, val hookIndex: Int, val register: Int, val story: Int, val storyField: FieldReference)

private fun cutsText(reference: MethodReference) =
    reference.returnType == SPANNABLE_STRING && reference.parameterTypes.firstOrNull()?.toString() == CHARACTER_STYLE

/** A method shaped like the cut's caller: Spannable back, the text and the See more style in. */
internal fun cutShaped(method: Method) =
    method.returnType == SPANNABLE && method.parameterTypes.size >= 2 &&
        method.parameterTypes[0].toString() == SPANNABLE && method.parameterTypes[1].toString() == CHARACTER_STYLE &&
        method.implementation != null && method.containsLiteralInstruction(LINE_LIMIT_KEY)

/** Whether [instruction] writes [register]. */
private fun writes(instruction: Instruction, register: Int): Boolean {
    val target = (instruction as? OneRegisterInstruction)?.registerA ?: return false
    return (instruction.opcode.setsRegister() && target == register) ||
        (instruction.opcode.setsWideRegister() && target + 1 == register)
}

/** Whether [instruction] reads or writes [register]. */
private fun mentions(instruction: Instruction, register: Int): Boolean = when (instruction) {
    is RegisterRangeInstruction -> register in instruction.startRegister until instruction.startRegister + instruction.registerCount
    is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE,
        instruction.registerF, instruction.registerG).take(instruction.registerCount).contains(register)
    else -> (instruction as? OneRegisterInstruction)?.registerA == register ||
        (instruction as? TwoRegisterInstruction)?.registerB == register ||
        (instruction as? ThreeRegisterInstruction)?.registerC == register
}

/** The instructions that read the register they write. */
private val READ_WRITES = setOf(
    Opcode.CHECK_CAST,
    Opcode.ADD_INT_2ADDR, Opcode.SUB_INT_2ADDR, Opcode.MUL_INT_2ADDR, Opcode.DIV_INT_2ADDR, Opcode.REM_INT_2ADDR,
    Opcode.AND_INT_2ADDR, Opcode.OR_INT_2ADDR, Opcode.XOR_INT_2ADDR, Opcode.SHL_INT_2ADDR, Opcode.SHR_INT_2ADDR,
    Opcode.USHR_INT_2ADDR, Opcode.ADD_LONG_2ADDR, Opcode.SUB_LONG_2ADDR, Opcode.MUL_LONG_2ADDR, Opcode.DIV_LONG_2ADDR,
    Opcode.REM_LONG_2ADDR, Opcode.AND_LONG_2ADDR, Opcode.OR_LONG_2ADDR, Opcode.XOR_LONG_2ADDR, Opcode.SHL_LONG_2ADDR,
    Opcode.SHR_LONG_2ADDR, Opcode.USHR_LONG_2ADDR, Opcode.ADD_FLOAT_2ADDR, Opcode.SUB_FLOAT_2ADDR,
    Opcode.MUL_FLOAT_2ADDR, Opcode.DIV_FLOAT_2ADDR, Opcode.REM_FLOAT_2ADDR, Opcode.ADD_DOUBLE_2ADDR,
    Opcode.SUB_DOUBLE_2ADDR, Opcode.MUL_DOUBLE_2ADDR, Opcode.DIV_DOUBLE_2ADDR, Opcode.REM_DOUBLE_2ADDR,
)

/** Whether [instruction] reads [register]: any mention but a plain write of it. */
private fun reads(instruction: Instruction, register: Int): Boolean {
    if (!mentions(instruction, register)) return false
    if (!writes(instruction, register) || instruction.opcode in READ_WRITES) return true
    return (instruction as? TwoRegisterInstruction)?.registerB == register ||
        (instruction as? ThreeRegisterInstruction)?.registerC == register
}

/** Where control can go in [code]: each instruction's next ones, by index. */
private class Flow(private val code: List<Instruction>, private val where: String) {
    private val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
    private val byAddress = addresses.withIndex().associate { it.value to it.index }

    private fun at(index: Int, offset: Int) = byAddress[addresses[index] + offset]
        ?: refuse("$where branches into the middle of an instruction")

    /** The instructions control can go to from [index]: the next one, and its branch's targets. */
    fun next(index: Int): List<Int> {
        val instruction = code[index]
        val opcode = instruction.opcode
        return when {
            opcode in RETURNS || opcode == Opcode.THROW -> emptyList()
            opcode in SWITCHES -> {
                val payload = code[at(index, (instruction as OffsetInstruction).codeOffset)] as SwitchPayload
                payload.switchElements.map { at(index, it.offset) } + (index + 1)
            }
            opcode.format in GOTOS -> listOf(at(index, (instruction as OffsetInstruction).codeOffset))
            instruction is OffsetInstruction && opcode != Opcode.FILL_ARRAY_DATA ->
                listOf(at(index, instruction.codeOffset), index + 1)
            else -> listOf(index + 1)
        }
    }

    /** The instructions some branch jumps to. */
    val targets: Set<Int> by lazy { code.indices.flatMap { index -> next(index) - (index + 1) }.toSet() }

    /** The instructions control can reach [index] from: [index] itself only when it's on a loop. */
    fun leadingTo(index: Int): Set<Int> {
        val successors = code.indices.map(::next)
        val leading = mutableSetOf<Int>()
        do {
            val grown = code.indices.filter { from ->
                from !in leading && successors[from].any { it == index || it in leading }
            }
            leading += grown
        } while (grown.isNotEmpty())
        return leading
    }
}

/**
 * Refuses unless no way on from [index] reads [register] before writing it, so a hook may change it
 * right before [index] (or right after it, where [index] doesn't read it).
 */
private fun requireDeadAfter(code: List<Instruction>, index: Int, register: Int, where: String) {
    val flow = Flow(code, where)
    val seen = mutableSetOf<Int>()
    val pending = ArrayDeque(flow.next(index))
    while (pending.isNotEmpty()) {
        val at = pending.removeFirst()
        if (at >= code.size) refuse("$where runs off its end after its state is set")
        if (!seen.add(at)) continue
        if (reads(code[at], register)) refuse("$where uses v$register after its state is set")
        if (!writes(code[at], register)) pending += flow.next(at)
    }
}

/**
 * Reads the cut out of [method]. Refuses unless every call of the helper (a static call answering a
 * SpannableString from a CharacterStyle first) keeps its answer right after it and goes on to one
 * branch on that answer being null, the split between text that fits and cut text: right after the
 * answer, or by a goto there. 585 and 586 cut once; 587 cuts in two places (`LX/03GO;->A06`, the
 * line-limit cut jumping back to the other's null check), so the hook goes at that branch, at its
 * label, and nothing else may reach it.
 * The story is the one register cast to [GRAPHQL_STORY] right after it's read from the post's props,
 * and every way to the cut gets its value from that cast, so it still holds the story there (586's
 * Android 9 build reuses the register after the cut, `LX/042d;->A06` v0).
 */
internal fun textCut(method: Method): TextCut {
    val code = method.implementation!!.instructions.toList()
    val where = "${method.definingClass}->${method.name}"
    val calls = code.indices.filter { index ->
        val instruction = code[index]
        (instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE) &&
            ((instruction as ReferenceInstruction).reference as? MethodReference)?.let(::cutsText) == true
    }
    if (calls.isEmpty()) refuse("expected a call cutting the text in $where, found 0")
    val flow = Flow(code, where)
    // Each cut's branch and answer register, and the instruction that goes on to that branch.
    val ways = calls.map { call ->
        val answer = code.getOrNull(call + 1)
        if (answer?.opcode != Opcode.MOVE_RESULT_OBJECT) refuse("the cut text in $where isn't kept right after its call at $call")
        val after = code.getOrNull(call + 2)
        val branch = if (after?.opcode?.format in GOTOS) flow.next(call + 2).single() else call + 2
        Triple(branch, (answer as OneRegisterInstruction).registerA, if (branch == call + 2) call + 1 else call + 2)
    }
    val (branch, register) = ways.map { it.first to it.second }.distinct().singleOrNull()
        ?: refuse("the ${calls.size} cuts in $where don't go on to one place: ${ways.map { it.first to it.second }}")
    val test = code.getOrNull(branch)
    if (test?.opcode != Opcode.IF_EQZ || (test as OneRegisterInstruction).registerA != register) {
        refuse("$where doesn't branch on the cut text in v$register right after it")
    }
    val leading = code.indices.filter { branch in flow.next(it) }.toSet()
    if (leading != ways.map { it.third }.toSet()) {
        refuse("$where reaches its null check on the cut text from $leading, not only from its cuts")
    }

    val casts = code.indices.filter { index ->
        code[index].opcode == Opcode.CHECK_CAST &&
            ((code[index] as ReferenceInstruction).reference as TypeReference).type == GRAPHQL_STORY
    }
    val cast = casts.singleOrNull() ?: refuse("expected one cast to the story in $where, found ${casts.size}")
    val story = (code[cast] as OneRegisterInstruction).registerA
    val read = code.getOrNull(cast - 1)
    if (read?.opcode != Opcode.IGET_OBJECT || (read as OneRegisterInstruction).registerA != story) {
        refuse("the story in $where isn't read from the post's props right before its cast")
    }
    val storyField = (read as ReferenceInstruction).reference as FieldReference
    val reaching = method.writersReaching(branch, story)
    if (reaching != listOf(cast)) refuse("$where's v$story reaches its cut written at $reaching, not only by the story's cast at $cast")
    if (story > 15 || register > 15) refuse("the cut's v$register or the story's v$story in $where is past v15")
    return TextCut(method, branch, register, story, storyField)
}

/** The one method shaped like the cut's caller that reads the line limit: Facebook's builder of a post's text. */
internal fun BytecodePatchContext.findPostTextBuilder(patch: String): Method {
    val methods = mutableListOf<Method>()
    classDefForEachReferencing(types = listOf(CHARACTER_STYLE)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        classDef.methods.filterTo(methods, ::cutShaped)
    }
    return methods.singleOrNull()
        ?: throw PatchException("$patch: expected one method building a post's text to its line limit, found ${methods.size}")
}

/**
 * Where a feed post's "allow truncation" is set: [first] sets its first value at [firstIndex] from
 * [firstValue], and [tap] sets each flip at [tapIndex] from [tapValue], with the post's props in
 * [props].
 */
internal class Fold(
    val first: Method,
    val firstIndex: Int,
    val firstValue: Int,
    val tap: Method,
    val tapIndex: Int,
    val tapValue: Int,
    val props: Int,
)

/** The boolean field [instruction] sets, or null. */
private fun booleanSet(instruction: Instruction): FieldReference? {
    if (instruction.opcode != Opcode.IPUT_BOOLEAN) return null
    return ((instruction as ReferenceInstruction).reference as FieldReference).takeIf { it.type == "Z" }
}

/**
 * Where [method] flips [field] on a tap: each store of it that comes after a read of it and a cast to
 * the post's props ([propsType]), in that order.
 */
private fun flipsOf(method: Method, field: FieldReference, propsType: String): List<Int> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    val key = field.toString()
    val read = code.indexOfFirst {
        it.opcode == Opcode.IGET_BOOLEAN && (it as ReferenceInstruction).reference.toString() == key
    }
    if (read < 0) return emptyList()
    val cast = (read + 1 until code.size).firstOrNull { index ->
        code[index].opcode == Opcode.CHECK_CAST && ((code[index] as ReferenceInstruction).reference as TypeReference).type == propsType
    } ?: return emptyList()
    return (cast + 1 until code.size).filter { booleanSet(code[it])?.toString() == key }
}

/**
 * The components named right after each [toggle] in [method]: a const-class straight after the
 * string's load or, where it asks a string table for it as 586 also does (`LX/040m;->A19` arm64),
 * straight after the table's answer is moved into its register.
 */
internal fun BytecodePatchContext.toggled(method: Method, toggle: String = TOGGLE): List<String> {
    val code = method.implementation!!.instructions.toList()
    val resolve: (MethodReference) -> Method? = { call -> classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) } }
    return code.indices.mapNotNull { index ->
        when {
            ((code[index] as? ReferenceInstruction)?.reference as? StringReference)?.string == toggle -> index + 1
            tableStringAt(code, index, resolve) == toggle ->
                if (code.getOrNull(index + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT) index + 2 else index + 1
            else -> null
        }
    }.map { at ->
        val named = code.getOrNull(at)
        if (named?.opcode != Opcode.CONST_CLASS) refuse("${method.definingClass}->${method.name} names no component after \"$toggle\"")
        ((named as ReferenceInstruction).reference as TypeReference).type
    }
}

/**
 * The methods sending [toggle]: those loading it and, once Redex moves it into a string table as 586
 * does with [TOGGLE] (`LX/01AE;->A00` arm64), those asking the table for it, never the table itself.
 */
internal fun BytecodePatchContext.sendersOf(toggle: String) = methodsNaming(toggle) { !isStringTable(it) }

/**
 * Whether [method] loads [string]. Facebook 585 loads both of [TOGGLE]'s with const-string/jumbo,
 * which indexOfFirstStringInstruction doesn't match, so any string load counts.
 */
internal fun loadsString(method: Method, string: String) = method.implementation?.instructions?.any {
    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
} == true

/** Methods outside the extension that load [string]. */
internal fun BytecodePatchContext.methodsWithString(string: String): List<Method> {
    val methods = mutableListOf<Method>()
    classDefForEachReferencing(strings = listOf(string)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        classDef.methods.filterTo(methods) { loadsString(it, string) }
    }
    return methods
}

/**
 * Finds the post state's "allow truncation". The component is the class named right after [TOGGLE]
 * wherever it's sent, and its method naming [COMPONENT_SPEC] sets its state's first values. Of those,
 * the one a method of the state's class flips on a tap (it reads it, casts the post's props, then
 * stores it) is the one. The props cast is the only write of its register but for the read right
 * before it, so it still holds the props at the store, and the hook goes after the store.
 */
internal fun BytecodePatchContext.findFold(storyField: FieldReference): Fold {
    val components = sendersOf(TOGGLE).flatMap { toggled(it) }.toSet()
    val component = components.singleOrNull()
        ?: refuse("expected one component named after \"$TOGGLE\", found ${components.size}")
    val firsts = methodsWithString(COMPONENT_SPEC).filter { it.definingClass == component }
    val first = firsts.singleOrNull()
        ?: refuse("expected one method of $component naming \"$COMPONENT_SPEC\", found ${firsts.size}")
    val firstCode = first.implementation!!.instructions.toList()
    val places = firstCode.indices.flatMap { index ->
        val field = booleanSet(firstCode[index])?.takeIf { it.definingClass != component } ?: return@flatMap emptyList()
        classDefByOrNull(field.definingClass)?.methods?.toList().orEmpty().flatMap { method ->
            flipsOf(method, field, storyField.definingClass).map { Triple(index, method, it) }
        }
    }
    val (firstIndex, tap, tapIndex) = places.singleOrNull()
        ?: refuse("expected one state $component starts that a tap on a post flips, found ${places.size}")

    val firstWhere = "${first.definingClass}->${first.name}"
    val firstValue = (firstCode[firstIndex] as TwoRegisterInstruction).registerA
    if (firstIndex in Flow(firstCode, firstWhere).targets) refuse("$firstWhere jumps straight to where its first state is set")
    requireDeadAfter(firstCode, firstIndex, firstValue, firstWhere)

    val tapCode = tap.implementation!!.instructions.toList()
    val tapWhere = "${tap.definingClass}->${tap.name}"
    val set = tapCode[tapIndex] as TwoRegisterInstruction
    val cast = (tapIndex - 1 downTo 0).first { index ->
        tapCode[index].opcode == Opcode.CHECK_CAST &&
            ((tapCode[index] as ReferenceInstruction).reference as TypeReference).type == storyField.definingClass
    }
    val props = (tapCode[cast] as OneRegisterInstruction).registerA
    val written = tapCode.indices.filter { it != cast && it != cast - 1 && writes(tapCode[it], props) }
    if (written.isNotEmpty()) refuse("$tapWhere writes its post props' v$props again at $written")
    if (props == set.registerA || props == set.registerB) refuse("$tapWhere sets its state from its post props")
    if (tapIndex + 1 in Flow(tapCode, tapWhere).targets) refuse("$tapWhere jumps straight past where its state is set")
    requireDeadAfter(tapCode, tapIndex, props, tapWhere)
    if (props > 15 || set.registerA > 15) refuse("$tapWhere keeps its props v$props or state v${set.registerA} past v15")
    return Fold(first, firstIndex, firstValue, tap, tapIndex, set.registerA, props)
}

/** Where a reel caption's first "expanded" is read: instruction [index] of [method] reads the component's prop into [register]. */
internal class CaptionStart(val method: Method, val index: Int, val register: Int)

/** Whether [instruction] calls [reference]. */
private fun calls(instruction: Instruction, reference: String) =
    instruction.opcode in INVOKES && (instruction as ReferenceInstruction).reference.toString() == reference

private val INVOKES = setOf(
    Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_SUPER, Opcode.INVOKE_DIRECT, Opcode.INVOKE_STATIC, Opcode.INVOKE_INTERFACE,
    Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_SUPER_RANGE, Opcode.INVOKE_DIRECT_RANGE, Opcode.INVOKE_STATIC_RANGE,
    Opcode.INVOKE_INTERFACE_RANGE,
)

/** The first register [instruction] hands its call, or null. */
private fun firstArgument(instruction: Instruction): Int? = when (instruction) {
    is RegisterRangeInstruction -> instruction.startRegister.takeIf { instruction.registerCount > 0 }
    is FiveRegisterInstruction -> instruction.registerC.takeIf { instruction.registerCount > 0 }
    else -> null
}

/** Whether [method] reads [field] as a boolean, stores it, and flips a value with `xor 1`: a state update toggling it. */
private fun toggles(method: Method, field: String): Boolean {
    val code = method.implementation?.instructions?.toList() ?: return false
    fun touches(opcode: Opcode) = code.any { it.opcode == opcode && (it as ReferenceInstruction).reference.toString() == field }
    return touches(Opcode.IGET_BOOLEAN) && touches(Opcode.IPUT_BOOLEAN) &&
        code.any { it.opcode == Opcode.XOR_INT_LIT8 && (it as NarrowLiteralInstruction).narrowLiteral == 1 }
}

/**
 * Finds a reel caption's first "expanded". The component is the class named right after
 * [CAPTION_TOGGLE] wherever it's sent, and one of its methods sets its state's first values. The
 * caption's "expanded" is the one boolean stored there straight from a Boolean's `booleanValue()`,
 * into a field a method of the state's class reads, flips (`xor 1`) and stores. That Boolean is the
 * one `Boolean.valueOf` boxed last before it, and what it boxes starts as the component's one boolean
 * prop the method reads, right there or through the move after it. The hook goes right after that
 * read, so Facebook's own memory of a caption tapped before, which may replace it, still wins.
 */
internal fun BytecodePatchContext.findCaptionStart(): CaptionStart {
    val components = sendersOf(CAPTION_TOGGLE).flatMap { toggled(it, CAPTION_TOGGLE) }.toSet()
    val component = components.singleOrNull()
        ?: refuse("expected one component named after \"$CAPTION_TOGGLE\", found ${components.size}")
    val owner = classDefByOrNull(component) ?: refuse("$component isn't in this build")
    val unboxed = owner.methods.flatMap { method ->
        val code = method.implementation?.instructions?.toList() ?: return@flatMap emptyList()
        code.indices.filter { index ->
            booleanSet(code[index])?.takeIf { it.definingClass != component } != null &&
                index >= 2 && calls(code[index - 2], BOOLEAN_VALUE) && code[index - 1].opcode == Opcode.MOVE_RESULT &&
                (code[index - 1] as OneRegisterInstruction).registerA == (code[index] as TwoRegisterInstruction).registerA
        }.map { method to it }
    }
    val stores = unboxed.filter { (method, index) ->
        val field = booleanSet(method.implementation!!.instructions.elementAt(index))!!
        classDefByOrNull(field.definingClass)?.methods?.any { toggles(it, field.toString()) } == true
    }
    val (method, store) = stores.singleOrNull() ?: refuse(
        "expected one state $component starts from a Boolean that its state class flips, found ${stores.size} of " +
            unboxed.joinToString(prefix = "${unboxed.size} unboxed (", postfix = ")") { (method, index) ->
                booleanSet(method.implementation!!.instructions.elementAt(index)).toString()
            },
    )
    val code = method.implementation!!.instructions.toList()
    val where = "${method.definingClass}->${method.name}"
    val boxed = firstArgument(code[store - 2]) ?: refuse("$where unboxes no register before its caption state")
    val boxing = (store - 3 downTo 1).firstOrNull { writes(code[it], boxed) }
    if (boxing == null || code[boxing].opcode != Opcode.MOVE_RESULT_OBJECT || !calls(code[boxing - 1], BOOLEAN_OF)) {
        refuse("$where doesn't box its caption state with Boolean.valueOf")
    }
    val value = firstArgument(code[boxing - 1])!!
    val props = code.indices.filter { index ->
        code[index].opcode == Opcode.IGET_BOOLEAN &&
            ((code[index] as ReferenceInstruction).reference as FieldReference).definingClass == component
    }
    val read = props.singleOrNull() ?: refuse("expected $where to read one boolean prop of $component, found ${props.size}")
    val register = (code[read] as TwoRegisterInstruction).registerA
    val next = code.getOrNull(read + 1)
    val carried = register == value || (next != null && next.opcode in MOVES &&
        (next as TwoRegisterInstruction).registerB == register && next.registerA == value)
    if (!carried) refuse("$where doesn't box its caption prop in v$register as its first state")
    if (read + 1 in Flow(code, where).targets) refuse("$where jumps straight past where it reads its caption prop")
    return CaptionStart(method, read, register)
}

private val MOVES = setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16)

internal const val CAPTION_BACKDROP = "Lapp/morphe/extension/facebook/feed/CaptionBackdrop;"

/** The helper the dark box adds to the class of the method that builds a reel caption's text. */
internal const val BACKDROP_HELPER = "hushfacebookCaptionBackdrop"

/** What the method building a reel caption's text loads: its component's name, its role and its description key (585). */
internal const val CAPTION_COMPONENT = "FbShortsViewerDescriptionComponent"
internal const val CAPTION_ROLE = "android.widget.Button"
internal const val CAPTION_DESCRIPTION = "description"

internal const val CALLER_CONTEXT = "Lcom/facebook/common/callercontext/CallerContext;"
internal const val DRAWABLE = "Landroid/graphics/drawable/Drawable;"
private const val RECT = "Landroid/graphics/Rect;"
internal const val FOR_CAPTION = "$CAPTION_BACKDROP->forCaption(I)$DRAWABLE"

/**
 * Where an open reel caption gets its dark box (#419). [builder] makes the caption's Litho text
 * builder (a [builderType]) in v[register], sets its line limit [maxLines], and builds it at
 * [buildIndex] by handing it a [CALLER_CONTEXT]. Litho takes a background as a style: [background]
 * makes one from a drawable on top of [emptyStyle], and [setter] hands a style to the builder.
 */
internal class Backdrop(
    val builder: Method,
    val buildIndex: Int,
    val register: Int,
    val builderType: String,
    val maxLines: String,
    val setter: String,
    val background: String,
    val emptyStyle: String,
)

private fun descriptor(method: Method) =
    "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"

private fun isStaticCall(instruction: Instruction) =
    instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE

/** Whether [method] asks a static helper for a drawable's padding, as Litho's background style does. */
private fun readsPadding(method: Method) = method.implementation?.instructions?.any { instruction ->
    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
    isStaticCall(instruction) && reference != null && reference.returnType == RECT &&
        reference.parameterTypes.map { it.toString() } == listOf(DRAWABLE)
} == true

/** Whether [type] is an app class: one a Litho style or builder could be. */
private fun appClass(type: String) = type.startsWith("L") && !type.startsWith("Ljava/") && !type.startsWith("Landroid/")

/**
 * Finds where an open reel caption gets its dark box. The caption's text is built in the one static
 * method loading [CAPTION_COMPONENT], [CAPTION_ROLE] and [CAPTION_DESCRIPTION] (585 `LX/09Uk;->A01`).
 * It sets one int field, the line limit, on a text builder it makes there and keeps in that register
 * up to the build, the one call handing that builder a [CALLER_CONTEXT] (585 `LX/0IoR;->A1y`). The
 * builder's classes take styles through one-argument setters, and Litho's background style is the one
 * static method taking a drawable and one of those style types, giving one back, that asks for the
 * drawable's padding (585 `LX/02Hx;->A03`). The empty style is that style type's one static field of
 * a subclass of it (585 `LX/01Tv;->A02`).
 */
internal fun BytecodePatchContext.findCaptionBackdrop(): Backdrop {
    val builders = methodsWithString(CAPTION_COMPONENT).filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && loadsString(it, CAPTION_ROLE) && loadsString(it, CAPTION_DESCRIPTION)
    }
    val builder = builders.singleOrNull() ?: refuse(
        "expected one static method building a reel caption's text (\"$CAPTION_COMPONENT\", \"$CAPTION_ROLE\", " +
            "\"$CAPTION_DESCRIPTION\"), found ${builders.size}",
    )
    val host = builder.definingClass
    val where = "$host->${builder.name}"
    val code = builder.implementation!!.instructions.toList()

    val limits = code.indices.filter { index ->
        code[index].opcode == Opcode.IPUT && ((code[index] as ReferenceInstruction).reference as FieldReference).type == "I"
    }
    val limit = limits.singleOrNull() ?: refuse("expected $where to set one int field, its line limit, found ${limits.size}")
    val maxLines = (code[limit] as ReferenceInstruction).reference as FieldReference
    val register = (code[limit] as TwoRegisterInstruction).registerB
    val builderType = maxLines.definingClass

    val builds = code.indices.filter { index ->
        val instruction = code[index]
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        (instruction.opcode == Opcode.INVOKE_VIRTUAL || instruction.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
            reference != null && reference.returnType != "V" && firstArgument(instruction) == register &&
            reference.parameterTypes.map { it.toString() } == listOf(CALLER_CONTEXT)
    }
    val build = builds.singleOrNull()
        ?: refuse("expected $where to build its text in v$register once with a caller context, found ${builds.size}")
    val made = code.indices.filter { it < build && writes(code[it], register) }
    val creation = made.singleOrNull()
    if (creation == null || creation > limit || limit > build || code[creation].opcode != Opcode.NEW_INSTANCE ||
        ((code[creation] as ReferenceInstruction).reference as TypeReference).type != builderType
    ) {
        refuse("$where doesn't keep one new $builderType in v$register from its line limit to its build")
    }
    val flow = Flow(code, where)
    if (build in flow.targets) refuse("$where jumps straight to where it builds its caption")
    // Dex can lay a block out after the build and jump back from it (the Android 9 build's 09VI does), so
    // go by the flow: the build runs once, and no way to it sets the builder's register again.
    val leadsToBuild = flow.leadingTo(build)
    if (build in leadsToBuild) refuse("$where loops back to its caption's build")
    if (leadsToBuild.any { it > build && writes(code[it], register) }) {
        refuse("$where sets v$register again on a way back to its caption's build")
    }

    val chain = generateSequence(classDefByOrNull(builderType)) { it.superclass?.let(::classDefByOrNull) }.toList()
    if (chain.isEmpty()) refuse("$builderType isn't in this build")
    val setters = chain.flatMap { owner ->
        owner.methods.filter { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) && !AccessFlags.PRIVATE.isSet(method.accessFlags) &&
                method.returnType == "V" && method.parameterTypes.size == 1 && appClass(method.parameterTypes[0].toString())
        }
    }
    val styles = setters.map { it.parameterTypes[0].toString() }.toSet()
    val backgrounds = mutableListOf<Method>()
    classDefForEachReferencing(types = styles) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        classDef.methods.filterTo(backgrounds) { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType in styles &&
                method.parameterTypes.map { it.toString() } == listOf(DRAWABLE, method.returnType) && readsPadding(method)
        }
    }
    val background = backgrounds.singleOrNull() ?: refuse(
        "expected one static method making a background style from a drawable for $builderType, found ${backgrounds.size}",
    )
    val style = background.returnType
    val styleSetters = setters.filter { it.parameterTypes[0].toString() == style }.distinctBy { it.name }
    val setter = styleSetters.singleOrNull()
        ?: refuse("expected $builderType to take a $style through one method, found ${styleSetters.size}")

    fun subclassOf(type: String, of: String) =
        generateSequence(classDefByOrNull(type)?.superclass) { classDefByOrNull(it)?.superclass }.any { it == of }
    val styleClass = classDefByOrNull(style) ?: refuse("$style isn't in this build")
    val empties = styleClass.staticFields.filter { subclassOf(it.type, style) }
    val empty = empties.singleOrNull() ?: refuse("expected $style to keep one empty style, found ${empties.size}")

    // The helper lives on the builder's own class, so all it calls must be reachable from there.
    fun reachable(owner: String, flags: Int): Boolean {
        val ownerClass = classDefByOrNull(owner) ?: return false
        val near = owner.substringBeforeLast('/') == host.substringBeforeLast('/')
        return (AccessFlags.PUBLIC.isSet(ownerClass.accessFlags) || near) &&
            (AccessFlags.PUBLIC.isSet(flags) || (near && !AccessFlags.PRIVATE.isSet(flags)))
    }
    if (!reachable(setter.definingClass, setter.accessFlags) || !reachable(background.definingClass, background.accessFlags) ||
        !reachable(style, empty.accessFlags)
    ) {
        refuse("$host can't reach $builderType's style setter, the background style or the empty style")
    }
    val extension = classDefByOrNull(CAPTION_BACKDROP) ?: refuse("the extension has no $CAPTION_BACKDROP")
    if (extension.methods.none { it.name == "inBuild" && it.returnType == "Z" && it.parameterTypes.isEmpty() }) {
        refuse("the extension has no $CAPTION_BACKDROP->inBuild()")
    }
    if (classDefByOrNull(host)!!.methods.any { it.name == BACKDROP_HELPER }) refuse("$host already has $BACKDROP_HELPER")
    return Backdrop(
        builder, build, register, builderType,
        "$builderType->${maxLines.name}:I",
        descriptor(setter),
        descriptor(background),
        "$style->${empty.name}:${empty.type}",
    )
}

/**
 * Gives an open reel caption its dark box: right before [Backdrop.builder] builds the caption, a
 * helper on its class reads the builder's line limit and asks [CAPTION_BACKDROP] for a drawable. An
 * open caption is the one built with no line limit. A drawable comes back as a background style on
 * the builder, and null leaves Facebook's caption as it is.
 */
internal fun BytecodePatchContext.addCaptionBackdrop(backdrop: Backdrop) {
    val host = backdrop.builder.definingClass
    val helper = ImmutableMethod(
        host,
        BACKDROP_HELPER,
        listOf(ImmutableMethodParameter(backdrop.builderType, null, null)),
        "V",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(3),
    ).toMutable().apply {
        addInstructions(
            0,
            """
                iget v1, p0, ${backdrop.maxLines}
                invoke-static { v1 }, $FOR_CAPTION
                move-result-object v0
                if-eqz v0, :facebook
                sget-object v1, ${backdrop.emptyStyle}
                invoke-static { v0, v1 }, ${backdrop.background}
                move-result-object v0
                invoke-virtual { p0, v0 }, ${backdrop.setter}
                :facebook
                return-void
            """,
        )
    }
    val owner = mutableClassDefBy(host)
    owner.methods.add(helper)
    owner.findMutableMethodOf(backdrop.builder).addInstructions(
        backdrop.buildIndex,
        "invoke-static/range { v${backdrop.register} .. v${backdrop.register} }, $host->$BACKDROP_HELPER(${backdrop.builderType})V",
    )
    mutableClassDefBy(CAPTION_BACKDROP).methods.single {
        it.name == "inBuild" && it.returnType == "Z" && it.parameterTypes.isEmpty()
    }.returnEarly(true)
}
