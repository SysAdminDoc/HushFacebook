/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.metaai

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.comments.summaries.holdSummaries
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.misc.upsells.CAPTION_DEEP_DIVE_PLUGIN
import app.morphe.patches.facebook.misc.upsells.IMAGINE_CTA_SOCKET
import app.morphe.patches.facebook.misc.upsells.captionDeepDiveGetter
import app.morphe.patches.facebook.misc.upsells.ctaCheck
import app.morphe.patches.facebook.misc.upsells.ctaTable
import app.morphe.patches.facebook.misc.upsells.dropCaptionDeepDive
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
import app.morphe.util.findMutableMethodOf
import app.morphe.util.namedRegisters
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val PATCH = "Hide Meta AI questions under posts"

/** The name the pill socket gives itself, in the one method that draws every pill under a post. */
internal const val PILL_SOCKET = "DeepDivePillSocket"

/** The package of the pill plugins. The socket loads each plugin's class name before it asks about it. */
internal const val PILL_PLUGINS = "com.facebook.feed.plugins.attachments.deepdivepill.impl."

/** Meta AI's pill plugin: the row of Meta AI questions under a post. */
internal const val META_AI_PILL = PILL_PLUGINS + "genai.GenAiDeepDivePillPlugin"

internal const val META_AI_QUESTIONS = "Lapp/morphe/extension/facebook/feed/MetaAiQuestions;"
internal const val KEEP = "$META_AI_QUESTIONS->keep(ILjava/lang/Object;)Z"
internal const val DROPS_DEFAULT_PILL = "$META_AI_QUESTIONS->dropsDefaultPill(Ljava/lang/String;)Z"
internal const val HIDES_QUESTION_BUTTON = "$META_AI_QUESTIONS->hidesQuestionButton(Ljava/lang/String;)Z"
internal const val HIDES_QUESTIONS_BELOW_CAPTION = "$META_AI_QUESTIONS->hidesQuestionsBelowCaption()Z"

/**
 * Meta AI's deep dive in the post call-to-action selector: a row of question chips under a post's
 * photo, between the photo and the Like row. The 582 test account got these under photo posts on
 * 2026-10-14 with the pill socket's hook in place, since they never go through that socket.
 */
internal const val META_AI_QUESTION_BUTTON = "com.facebook.feed.plugins.calltoaction.impl.genaideepdive.GenAiDeepDiveCtaPlugin"

/** The type the socket's default way of drawing a pill gives Meta AI's icon to. */
internal const val META_AI_TYPE = "meta_ai"

/**
 * The type name the default way compares a pill's type with first once every icon path has
 * joined, so every pill that way draws passes that compare.
 */
internal const val STARS_TYPE = "stars"

/** The getter of Facebook's tree class the default way reads a pill's type with, by the field's key. */
internal const val TYPE_GETTER = "getCachedNullableString"

private const val STRING_EQUALS = "Ljava/lang/String;->equals(Ljava/lang/Object;)Z"

/**
 * The row of Meta AI questions under some posts goes (issue #48). Facebook calls it a deep dive
 * pill, and one static method draws every kind of pill under a post (580 `LX/3A1;->A06`, 577
 * `LX/39F;->A06`, both naming themselves DeepDivePillSocket). It goes through the pill plugins in
 * a fixed order: for each, it loads the plugin's class name into one register, asks a static
 * (model, index) -> Z check of its own class whether the plugin applies to the post, and draws the
 * first one that does. A second call of the same check feeds a log and jumps back to the same
 * branch. After each of those calls, the answer goes through the extension with the plugin's name,
 * and the extension turns a yes for GenAiDeepDivePillPlugin into a no while the switch is on, so
 * the socket moves on to the next plugin. Every other pill, the affiliate one included, keeps
 * Facebook's answer.
 *
 * A plugin that says yes but brings no renderer, or one whose renderer draws nothing, gets the
 * socket's default way of drawing a pill (580 `LX/3A1;->A06` from @450): it picks an icon, Meta AI's
 * for a pill typed `meta_ai`, and builds the pill. The default click handler's check doesn't look
 * at the type, so a Meta AI row could come back that way once Meta AI's own plugin said no. Once
 * every icon path has joined, the default way reads the type again and compares it with `stars`
 * first. Right after that read, the type goes through the extension, and a pill typed `meta_ai`
 * gets no pill while the switch is on. Other types are drawn as before. On 585 (read 2026-10-29,
 * arm64 `LX/03kH;->A0F`) the default way reads the type through the pill model's own interface
 * getter (`LX/04R7;->GDu()`, invoke-interface/range) instead of getCachedNullableString with a key,
 * so a compare's source is the key or that getter.
 *
 * The same questions come two more ways that never touch the pill socket. The post call-to-action
 * selector (FeedStoryCtaSelectorSocket) draws Meta AI's deep dive as a row of question chips under
 * a post's photo through GenAiDeepDiveCtaPlugin, and the socket that collects what goes under a
 * caption draws it there through GenAiDeepDiveBelowCaptionPlugin. Hide Meta upsells already hooks
 * both for its "other Meta AI buttons" switch, which starts off: the selector's check (the first
 * thing in it hands the extension the plugin's name from the selector's table and answers no on a
 * yes) and the caption plugin's one getter of a post's deep dive (null on a yes). This patch puts
 * its own hook of the same shape first in each, so the questions go with this switch alone, with
 * or without Hide Meta upsells in the build. Two guards in front of one method each answer no on
 * their own yes and otherwise run on into the other, so their order doesn't matter.
 */
@Suppress("unused")
val hideMetaAiQuestionsPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Hide Meta AI questions under posts",
    description = "Removes the row of Meta AI questions Facebook adds under some posts, for a cleaner feed. The " +
        "post and its buttons stay. On by default. Turn it off in Hushfacebook settings > News feed.",
) {
    category("Feed")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val socket = findPillSocket()
        val pill = defaultPill(socket.method)
        if (socket.answers.any { it >= pill.index }) {
            refuse("a check of the plugins in ${socket.method.definingClass} comes after its default way of drawing a pill")
        }
        val questionButtons = findQuestionButtons()
        val method = mutableClassDefBy(socket.method.definingClass).findMutableMethodOf(socket.method)
        // The default way's hook first, then the checks from the last back, so the indices of
        // the earlier ones stay where they are.
        method.hookDefaultPill(pill)
        socket.answers.sortedDescending().forEach { index ->
            val answer = method.getInstruction<OneRegisterInstruction>(index).registerA
            method.addInstructions(
                index + 1,
                """
                    invoke-static { v$answer, v${socket.nameRegister} }, $KEEP
                    move-result v$answer
                """,
            )
        }
        mutableClassDefBy(questionButtons.check.definingClass).methods
            .single { it.descriptor() == questionButtons.check.descriptor() }
            .holdSummaries(questionButtons.table, HIDES_QUESTION_BUTTON, PATCH)
        mutableClassDefBy(questionButtons.captionGetter.definingClass).methods
            .single { it.descriptor() == questionButtons.captionGetter.descriptor() }
            .dropCaptionDeepDive(HIDES_QUESTIONS_BELOW_CAPTION, PATCH)
        enableStatus("metaAiQuestions")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * The two other ways Meta AI's questions reach a post: the call-to-action selector's name [table]
 * and its plugin [check], and the caption deep dive plugin's getter of a post's deep dive [captionGetter].
 */
internal class QuestionButtons(val table: Method, val check: Method, val captionGetter: Method)

/**
 * Finds [QuestionButtons]: the selector's table by [META_AI_QUESTION_BUTTON], its check through the
 * methods naming the selector's socket, and the caption plugin's getter. Refuses unless each is
 * found once and both have a local register for their hook. Changes nothing.
 */
internal fun BytecodePatchContext.findQuestionButtons(): QuestionButtons {
    fun holders(string: String) = classDefByStrings(string, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }.distinctBy { it.type }
    val table = ctaTable(holders(META_AI_QUESTION_BUTTON), META_AI_QUESTION_BUTTON, PATCH)
    val sockets = holders(IMAGINE_CTA_SOCKET).flatMap { methodsHolding(it, IMAGINE_CTA_SOCKET) }
    val check = ctaCheck(table, sockets, PATCH) { classDefByOrNull(it) }
    val captionPlugin = classDefByOrNull(CAPTION_DEEP_DIVE_PLUGIN) ?: refuse("this Facebook build has no $CAPTION_DEEP_DIVE_PLUGIN")
    val captionGetter = captionDeepDiveGetter(captionPlugin, PATCH)
    for (method in listOf(check, captionGetter)) {
        if (method.localRegisterCount() < 1) refuse("${method.definingClass}->${method.name} has no local register")
    }
    return QuestionButtons(table, check, captionGetter)
}

/**
 * The pill socket: its method, the register it loads each plugin's class name into, and the index
 * of the move-result after each call of its per-plugin check.
 */
internal class PillSocket(val method: Method, val nameRegister: Int, val answers: List<Int>)

/** Whether [instruction] calls a static (model, index) -> Z method of [owner]: the socket's per-plugin check. */
internal fun isPluginCheck(instruction: Instruction, owner: String): Boolean {
    if (instruction.opcode != Opcode.INVOKE_STATIC) return false
    val target = (instruction as ReferenceInstruction).reference as MethodReference
    return target.definingClass == owner && target.returnType == "Z" && target.parameterTypes.size == 2 &&
        target.parameterTypes[0].toString().startsWith("L") && target.parameterTypes[1].toString() == "I"
}

/**
 * Reads the socket out of [method], which loads [PILL_SOCKET] and [META_AI_PILL]. Refuses when the
 * plugin names go to more than one register, when no per-plugin check is followed by a move-result,
 * when a register the hook hands over doesn't fit the call's four bits, when a way past a hook's
 * spot skips it, and when a check can be reached with anything but a plugin's name in the name
 * register or answers into it. A check is numbered by its move-result in every refusal.
 */
internal fun pillSocket(method: Method): PillSocket {
    val code = method.implementation!!.instructions.toList()
    val nameRegisters = code.filter(::isPluginName).map { (it as OneRegisterInstruction).registerA }.toSet()
    val nameRegister = nameRegisters.singleOrNull()
        ?: refuse("expected the plugin names in one register in ${method.definingClass}->${method.name}, found $nameRegisters")
    val answers = code.indices.filter { index ->
        index > 0 && code[index].opcode == Opcode.MOVE_RESULT && isPluginCheck(code[index - 1], method.definingClass)
    }
    if (answers.isEmpty()) refuse("found no (model, index) -> Z check in ${method.definingClass}->${method.name}")
    val registers = answers.map { (code[it] as OneRegisterInstruction).registerA } + nameRegister
    if (registers.any { it > 15 }) refuse("a register the hook hands over is past v15: $registers")
    requireAnswerSpots(method, answers)
    requirePluginNames(method, nameRegister, answers)
    return PillSocket(method, nameRegister, answers)
}

/** Whether [instruction] loads a pill plugin's class name. */
private fun isPluginName(instruction: Instruction): Boolean =
    (instruction.opcode == Opcode.CONST_STRING || instruction.opcode == Opcode.CONST_STRING_JUMBO) &&
        ((instruction as ReferenceInstruction).reference as StringReference).string.startsWith(PILL_PLUGINS)

/**
 * Proves the name each check's hook hands over is a plugin's. The hook decides by it, so another
 * value there could keep Meta AI's yes or turn another plugin's yes into a no. On every way into
 * each check, the name register was last written by the load of a plugin's class name, and the
 * check doesn't answer into that register, which would hand the hook a boolean for the name.
 */
private fun requirePluginNames(method: Method, nameRegister: Int, answers: List<Int>) {
    val where = "${method.definingClass}->${method.name}"
    val code = method.implementation!!.instructions.toList()
    for (answer in answers) {
        val check = answer - 1
        if ((code[answer] as OneRegisterInstruction).registerA == nameRegister) {
            refuse("in $where the plugin check at $answer answers into v$nameRegister, the plugin's name its hook hands over")
        }
        val strangers = method.writersReaching(check, nameRegister).filterNot { it >= 0 && isPluginName(code[it]) }
        if (strangers.isNotEmpty()) {
            val ways = strangers.joinToString(" and ") { if (it < 0) "the method's start" else "the write at $it" }
            refuse("in $where the plugin check at $answer can get v$nameRegister from $ways, not only from a plugin's name")
        }
    }
}

/**
 * Proves each check's hook can go in at the instruction after its move-result. The hook goes in
 * front of that instruction, and dexlib2 keeps the instruction's labels on it, so any other way
 * in skips the hook. That way's answer has to be one a hook already took, like the second check's
 * that feeds the log and jumps back to the first one's branch. A catch handler starting there
 * would skip the hook the same way, and a try block over it, or starting or ending there, would
 * take the hook's call in or leave it out.
 */
private fun requireAnswerSpots(method: Method, answers: List<Int>) {
    val where = "${method.definingClass}->${method.name}"
    val code = method.implementation!!.instructions.toList()
    for (answer in answers) {
        val spot = answer + 1
        val edges = tryEdgesAt(method, spot)
        if (edges.isNotEmpty()) {
            refuse("in $where ${edges.joinToString(" and ")} at the instruction after the plugin check at $answer, where its hook goes")
        }
        if (tryBlockOver(method, spot)) {
            refuse("in $where a try block covers the instruction after the plugin check at $answer, where its hook goes")
        }
        val register = (code[answer] as OneRegisterInstruction).registerA
        val unhooked = method.writersReaching(spot, register).filterNot { it in answers }
        if (unhooked.isNotEmpty()) {
            val ways = unhooked.joinToString(" and ") { if (it < 0) "the method's start" else "the write at $it" }
            refuse("in $where the instruction after the plugin check at $answer can get v$register from $ways, not only from a plugin check")
        }
    }
}

/** The one method that loads both [PILL_SOCKET] and [META_AI_PILL], read as a socket. Changes nothing. */
internal fun BytecodePatchContext.findPillSocket(): PillSocket {
    val methods = classDefByStrings(META_AI_PILL, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { classDef -> classDef.methods.filter { holdsString(it, META_AI_PILL) && holdsString(it, PILL_SOCKET) } }
    val method = methods.singleOrNull()
        ?: refuse("expected one method loading \"$PILL_SOCKET\" and $META_AI_PILL, found ${methods.size}")
    return pillSocket(method)
}

/**
 * A read of a pill's type compared with a type name right away: [TYPE_GETTER] on the tree in
 * [tree] with the key in [key] (null when no literal loads it just before), or a no-argument String
 * getter of the pill model in [tree] (585), the answer moved into [typeRegister], the name loaded
 * into [nameRegister] at [nameIndex], then String.equals of the two. [source] names what was read:
 * the literal key, or the getter itself, and is null for a key no literal loads.
 */
internal class TypeCompare(
    val name: String,
    val nameIndex: Int,
    val typeRegister: Int,
    val nameRegister: Int,
    val tree: Int,
    val key: Long?,
    val source: String?,
)

/** Every [TypeCompare] in [code]. */
internal fun typeCompares(code: List<Instruction>): List<TypeCompare> = (2 until code.size - 1).mapNotNull { index ->
    val load = code[index]
    if (load.opcode != Opcode.CONST_STRING && load.opcode != Opcode.CONST_STRING_JUMBO) return@mapNotNull null
    val name = ((load as ReferenceInstruction).reference as StringReference).string
    val nameRegister = (load as OneRegisterInstruction).registerA
    val moved = code[index - 1]
    if (moved.opcode != Opcode.MOVE_RESULT_OBJECT) return@mapNotNull null
    val typeRegister = (moved as OneRegisterInstruction).registerA
    val equals = code[index + 1]
    if (equals.opcode != Opcode.INVOKE_VIRTUAL || (equals as ReferenceInstruction).reference.toString() != STRING_EQUALS) {
        return@mapNotNull null
    }
    val compared = equals as FiveRegisterInstruction
    if (compared.registerCount != 2 || compared.registerC != nameRegister || compared.registerD != typeRegister) {
        return@mapNotNull null
    }
    val read = code[index - 2]
    val getter = (read as? ReferenceInstruction)?.reference as? MethodReference ?: return@mapNotNull null
    if (getter.returnType != "Ljava/lang/String;") return@mapNotNull null
    val parameters = getter.parameterTypes.map { it.toString() }
    when {
        read.opcode == Opcode.INVOKE_VIRTUAL && getter.name == TYPE_GETTER && parameters == listOf("I") -> {
            val call = read as FiveRegisterInstruction
            val key = keyOf(code, index - 2, call.registerD)
            TypeCompare(name, index, typeRegister, nameRegister, call.registerC, key, key?.let { "$TYPE_GETTER#$it" })
        }
        // 585 reads the type through the pill model's own getter (an interface's no-argument String
        // method), so the getter stands in for the key.
        read.opcode in MODEL_GETTER_CALLS && parameters.isEmpty() -> {
            val tree = when (read) {
                is RegisterRangeInstruction -> read.startRegister.takeIf { read.registerCount == 1 }
                is FiveRegisterInstruction -> read.registerC.takeIf { read.registerCount == 1 }
                else -> null
            } ?: return@mapNotNull null
            TypeCompare(name, index, typeRegister, nameRegister, tree, null, getter.toString())
        }
        else -> null
    }
}

private val MODEL_GETTER_CALLS = setOf(
    Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE, Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE,
)

/** The literal a const loads into [register] within the few instructions before [index], if one does. */
private fun keyOf(code: List<Instruction>, index: Int, register: Int): Long? {
    for (at in index - 1 downTo maxOf(0, index - 4)) {
        val instruction = code[at]
        if (register !in instruction.namedRegisters()) continue
        val loads = instruction.opcode in setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16)
        return if (loads && (instruction as OneRegisterInstruction).registerA == register) {
            (instruction as WideLiteralInstruction).wideLiteral
        } else {
            null
        }
    }
    return null
}

/**
 * Where the socket's default way of drawing a pill gets its hook: the [STARS_TYPE] name's load at
 * [index], right after the pill's type is read into [typeRegister]. The name's own register,
 * [freeRegister], is written by that load, and no catch handler over it reads that register, so
 * the hook can use it until then.
 */
internal class DefaultPill(val index: Int, val typeRegister: Int, val freeRegister: Int)

/**
 * Reads the default way's hook point out of [method]. Refuses unless the method returns an object,
 * compares a pill's type with [META_AI_TYPE] once, reading it with a literal key, and compares the
 * type read with that key from the same tree with [STARS_TYPE] once, unless that compare can only
 * be reached through the read just before it, with no try block's edge or handler on its name's
 * load and no catch handler over that load reading the name's register, and unless every path
 * from the [META_AI_TYPE] compare gets there before it returns, through the catch handlers of what
 * can throw on it too.
 */
internal fun defaultPill(method: Method): DefaultPill {
    val code = method.implementation!!.instructions.toList()
    val where = "${method.definingClass}->${method.name}"
    if (method.returnType.first() != 'L' && method.returnType.first() != '[') {
        refuse("$where returns ${method.returnType}, so the hook can't return no pill there")
    }
    val compares = typeCompares(code)
    val metaAi = compares.filter { it.name == META_AI_TYPE }
    val icon = metaAi.singleOrNull()
        ?: refuse("expected one compare of a pill's type with \"$META_AI_TYPE\" in $where, found ${metaAi.size}")
    if (icon.source == null) refuse("the pill type compared with \"$META_AI_TYPE\" in $where isn't read with a literal key")
    val stars = compares.filter { it.name == STARS_TYPE && it.source == icon.source && it.tree == icon.tree }
    val site = stars.singleOrNull()
        ?: refuse("expected one compare of the same pill type with \"$STARS_TYPE\" in $where, found ${stars.size}")
    val flow = ControlFlow.of(method)
    val into = flow.normal.indices.filter { site.nameIndex in flow.normal[it] }
    if (into != listOf(site.nameIndex - 1)) {
        refuse("in $where the \"$STARS_TYPE\" compare can be reached from $into, not only through the read of the type")
    }
    // The hook goes in above the name's load, and dexlib2 keeps a handler's start and a try block's
    // edges on the load itself: a handler there would skip the hook, a try block ending there would
    // take it in, and one starting there would leave it out.
    val edges = tryEdgesAt(method, site.nameIndex)
    if (edges.isNotEmpty()) refuse("in $where ${edges.joinToString(" and ")} at the \"$STARS_TYPE\" compare's name, where the hook goes")
    // The hook leaves its answer in the name's register ahead of the load, which writes over it on
    // normal flow only. A catch handler over the load still sees the answer there, and one that
    // reads the register as the name would fail verification, taking the whole class with it.
    if (site.nameRegister in RegisterLiveness.of(method).liveInto(site.nameIndex)) {
        refuse("in $where a catch handler over the \"$STARS_TYPE\" compare's name reads v${site.nameRegister}, where the hook leaves its answer")
    }
    // A pill typed meta_ai that could be returned before the stars compare would be drawn with the
    // hook in place, so every way on from the meta_ai compare has to pass it first. A return only on
    // the way where the type isn't meta_ai is refused too, since 577 and 580 join both ways before
    // the stars compare, and the refusal names that way. A return a handler reaches before the
    // branch on the compare's answer is on neither way, so that refusal names none.
    val (reaches, returns) = pathsFrom(flow, icon.nameIndex, site.nameIndex)
    if (returns.isNotEmpty()) {
        val anyType = "in $where a pill can be returned at $returns after the \"$META_AI_TYPE\" compare, before the \"$STARS_TYPE\" compare"
        val (branch, way) = metaAiWay(code, flow, icon) ?: refuse(anyType)
        val beforeBranch = pathsFrom(flow, icon.nameIndex, site.nameIndex) { if (it == branch) emptyList() else flow.normal[it] }.second
        if (beforeBranch.isNotEmpty()) refuse(anyType)
        val onMetaAi = pathsFrom(flow, way, site.nameIndex) { if (it == branch) listOf(way) else flow.normal[it] }.second
        if (onMetaAi.isEmpty()) {
            refuse("in $where a pill whose type isn't \"$META_AI_TYPE\" can be returned at $returns before the \"$STARS_TYPE\" compare")
        }
        refuse("in $where a pill typed \"$META_AI_TYPE\" can be returned at $onMetaAi before the \"$STARS_TYPE\" compare")
    }
    if (!reaches) refuse("in $where nothing after the \"$META_AI_TYPE\" compare reaches the \"$STARS_TYPE\" compare")
    // The hook's call and its const/4 take four-bit registers only.
    if (site.typeRegister > 15 || site.nameRegister > 15) {
        refuse("in $where the \"$STARS_TYPE\" compare's registers v${site.typeRegister} and v${site.nameRegister} don't fit the hook's four bits")
    }
    return DefaultPill(site.nameIndex, site.typeRegister, site.nameRegister)
}

private val RETURNS = setOf(Opcode.RETURN_OBJECT, Opcode.RETURN, Opcode.RETURN_WIDE, Opcode.RETURN_VOID)

/** What [method]'s try blocks put on the instruction at [index]: a catch handler's start, a try block's start or its end. */
private fun tryEdgesAt(method: Method, index: Int): List<String> {
    val implementation = method.implementation!!
    val address = implementation.instructions.take(index).sumOf { it.codeUnits }
    return implementation.tryBlocks.flatMap { block ->
        listOfNotNull(
            "a try block starts".takeIf { block.startCodeAddress == address },
            "a try block ends".takeIf { block.startCodeAddress + block.codeUnitCount == address },
        ) + block.exceptionHandlers.filter { it.handlerCodeAddress == address }.map { "a catch handler starts" }
    }.distinct()
}

/** Whether one of [method]'s try blocks covers the instruction at [index] without starting there. */
private fun tryBlockOver(method: Method, index: Int): Boolean {
    val implementation = method.implementation!!
    val address = implementation.instructions.take(index).sumOf { it.codeUnits }
    return implementation.tryBlocks.any { address > it.startCodeAddress && address < it.startCodeAddress + it.codeUnitCount }
}

/**
 * Follows every path from [from] in [flow], into the catch handlers of what can throw on it too,
 * stopping at [to]: whether one gets there, and the returns reached without passing it. [next]
 * names the ways on from an instruction that doesn't end its path, its normal ones by default.
 */
private fun pathsFrom(
    flow: ControlFlow,
    from: Int,
    to: Int,
    next: (Int) -> List<Int> = { flow.normal[it] },
): Pair<Boolean, List<Int>> {
    val seen = mutableSetOf<Int>()
    val pending = ArrayDeque(listOf(from))
    val returns = sortedSetOf<Int>()
    var reaches = false
    while (pending.isNotEmpty()) {
        val at = pending.removeFirst()
        if (at == to) {
            reaches = true
            continue
        }
        if (!seen.add(at)) continue
        if (flow.instructions[at].opcode in RETURNS) {
            returns += at
            continue
        }
        pending += next(at)
        if (flow.instructions[at].opcode.canThrow()) pending += flow.exceptional[at]
    }
    return reaches to returns.toList()
}

/**
 * The branch on [compare]'s answer right after it, and the instruction its way for a type that is
 * [META_AI_TYPE] starts at: the jump for an if-nez, the instruction below for an if-eqz. Null when
 * nothing branches on the answer right away.
 */
private fun metaAiWay(code: List<Instruction>, flow: ControlFlow, compare: TypeCompare): Pair<Int, Int>? {
    val answer = code.getOrNull(compare.nameIndex + 2)
    if (answer?.opcode != Opcode.MOVE_RESULT) return null
    val branch = compare.nameIndex + 3
    val test = code.getOrNull(branch)
    if (test?.opcode != Opcode.IF_EQZ && test?.opcode != Opcode.IF_NEZ) return null
    if ((test as OneRegisterInstruction).registerA != (answer as OneRegisterInstruction).registerA) return null
    // ControlFlow lists a branch's jump first, then the instruction below it.
    val (jump, below) = flow.normal[branch].takeIf { it.size == 2 } ?: return null
    return branch to if (test.opcode == Opcode.IF_NEZ) jump else below
}

/**
 * Hands the pill's type to the extension right after the default way reads it, before the
 * [STARS_TYPE] compare, and returns no pill when the extension says to drop it. The answer goes
 * into the register the compare's name is about to be loaded into, so nothing live is touched.
 * The compare's own plain call names both registers, so both fit the plain forms here.
 */
internal fun MutableMethod.hookDefaultPill(pill: DefaultPill) {
    val free = pill.freeRegister
    addInstructionsWithLabels(
        pill.index,
        """
            invoke-static { v${pill.typeRegister} }, $DROPS_DEFAULT_PILL
            move-result v$free
            if-eqz v$free, :draw
            const/4 v$free, 0x0
            return-object v$free
        """,
        ExternalLabel("draw", getInstruction(pill.index)),
    )
}
