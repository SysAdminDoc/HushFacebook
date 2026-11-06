/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.posttext

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.containsLiteralInstruction
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Format
import com.android.tools.smali.dexlib2.Opcode
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
 */
@Suppress("unused")
val showFullPostTextPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Show full post text",
    description = "Shows all of a post's text in the feed, and a reel's caption, so you don't have to tap See more. " +
        "A tap on the text folds it back. Starts off. Turn posts on in Hushfacebook settings > News feed, " +
        "and reel captions under Reels and Watch.",
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
        method.addInstructions(
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
 * Reads the cut out of [method]. Refuses unless it calls the helper once (a static call answering a
 * SpannableString from a CharacterStyle first), the answer lands right after it, and the very next
 * instruction branches on that answer being null: the split between text that fits and cut text.
 * The story is the one register cast to [GRAPHQL_STORY] right after it's read from the post's props,
 * and nothing else in the method writes it, so it still holds the story at the cut.
 */
internal fun textCut(method: Method): TextCut {
    val code = method.implementation!!.instructions.toList()
    val where = "${method.definingClass}->${method.name}"
    val calls = code.indices.filter { index ->
        val instruction = code[index]
        (instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE) &&
            ((instruction as ReferenceInstruction).reference as? MethodReference)?.let(::cutsText) == true
    }
    val call = calls.singleOrNull() ?: refuse("expected one call cutting the text in $where, found ${calls.size}")
    val answer = code.getOrNull(call + 1)
    if (answer?.opcode != Opcode.MOVE_RESULT_OBJECT) refuse("the cut text in $where isn't kept right after its call")
    val register = (answer as OneRegisterInstruction).registerA
    val branch = code.getOrNull(call + 2)
    if (branch?.opcode != Opcode.IF_EQZ || (branch as OneRegisterInstruction).registerA != register) {
        refuse("$where doesn't branch on the cut text in v$register right after it")
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
    val written = code.indices.filter { it != cast && it != cast - 1 && writes(code[it], story) }
    if (written.isNotEmpty()) refuse("$where writes the story's v$story again at $written")
    if (story > 15 || register > 15) refuse("the cut's v$register or the story's v$story in $where is past v15")
    return TextCut(method, call + 2, register, story, storyField)
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

/** The components named right after each [toggle] in [method]. */
private fun toggled(method: Method, toggle: String = TOGGLE): List<String> {
    val code = method.implementation!!.instructions.toList()
    return code.indices.filter { index ->
        ((code[index] as? ReferenceInstruction)?.reference as? StringReference)?.string == toggle
    }.map { index ->
        val named = code.getOrNull(index + 1)
        if (named?.opcode != Opcode.CONST_CLASS) refuse("${method.definingClass}->${method.name} names no component after \"$toggle\"")
        ((named as ReferenceInstruction).reference as TypeReference).type
    }
}

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
    val components = methodsWithString(TOGGLE).flatMap(::toggled).toSet()
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
    val components = methodsWithString(CAPTION_TOGGLE).flatMap { toggled(it, CAPTION_TOGGLE) }.toSet()
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
