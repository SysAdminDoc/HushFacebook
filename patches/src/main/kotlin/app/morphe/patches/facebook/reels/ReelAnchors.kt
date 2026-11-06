/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patches.facebook.feed.addressed
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.switchTarget
import app.morphe.patches.facebook.feed.tableStringsAsked
import app.morphe.patches.facebook.shared.redexOriginalName
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
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * Where Clean up Reels hooks, found by kept names only (read from 577 and 580, 2026-09-25). The
 * obfuscated names in these comments are for reviewers; the code never writes one down.
 *
 * - The chips under a reel: one static method takes the reel's attribution models as an
 *   ImmutableList, switches on each one's getTypeName() over the XFBFBShorts*Attribution literals,
 *   and returns the ones it will draw from an ImmutableList$Builder (580 LX/AyX;->A00, 577
 *   LX/B1d;->A00). Both builds end it in one `return-object` after `build()`. 582's armeabi-v7a
 *   builder (LX/8JF;->A00) asks a string table for one of the literals (LX/SoX;->A00 with 0xa for
 *   XFBFBShortsExternalLinkAttribution) instead of loading it; its arm64 one (LX/8GT;->A00) loads
 *   all eight.
 * - The Following button: the bug report dumper (the method holding "WatchFeedData.txt") writes
 *   each Reels viewer config value beside its name, and the value beside the name ending
 *   "removeFollowingButton" comes from a no-argument boolean getter (580 LX/4Xa;->A1c, written
 *   twice, under getPlayerTabGrowthConfig and getUDDConfig; 577 LX/52v;->A1b, after a split
 *   ".removeFollowingButton"). Its only other readers are the reel author row's two renderers.
 *   The main one (580 LX/AyA;->A1F, 577 LX/B1B;->A1N) reads it only when the author wasn't
 *   followable as the row's state was made: the state keeps whether the owner's subscribe status
 *   was CAN_SUBSCRIBE (580 LX/AyD;->A0H), and the getter is skipped when it was. So it takes the
 *   Following button off an author you already follow and never runs for one you can follow,
 *   which is why 0.1.7 left the Follow button on the S22's Reels tab. 583's arm64 build and 584
 *   have no such getter: the value is one of two MobileConfig flags read in cases of a merged lambda,
 *   picked by whether the viewer is Shorts in the Watch tab, so both flag reads are hooked
 *   ([followFlagsAt]) and the author row is the one method holding its literal. 585 keeps that, with
 *   the two flags in lambdas of two classes (arm64 LX/0ACw; and LX/0ACj;, v7a LX/0AD1; and
 *   LX/0ACo;): the Player tab's value comes from a boolean helper the dump turns into text
 *   (arm64 LX/0ACj;->A0t) and the UDD's from a string helper that makes both lambdas
 *   (LX/0Ap7;->A00). The first flag's case is no longer a method of its own: it's written into the
 *   third switch of its lambda (arm64 LX/0ACw;->A1Z, v7a LX/0AD1;->A1Z, key 0x9b on both) and reads
 *   the flag through a cached holder, so the patch hooks the lambda's `invoke()` by its key rather
 *   than a case method.
 * - The Follow button: the same renderer builds it through the method holding
 *   "video_author_follow_button" (580 LX/AyO;->A01, 577 LX/B1T;->A01), and only when Facebook's
 *   Follow check answers yes (580 LX/8O4;->A0B, 577 LX/8qp;->A0B). The check is static, answers a
 *   boolean, takes the session first and names the two surfaces where it holds Follow back for a
 *   friend, "friendly_feed" and "friends_tab_ifu". It's asked before any Follow button is built:
 *   by this renderer, both ads author rows (580 LX/BF0;->render, which calls the same builder, and
 *   LX/RtQ;->render) and a feed lambda that checks a reel attached to a post (580 LX/Ru9).
 *   The profile's own Follow button doesn't ask it. 585 folded that builder into the row: the
 *   renderer (arm64 LX/09bx;->A17, v7a LX/09c2;->A17) holds the key itself and calls the check
 *   (arm64 LX/09bX;->A02), while the old builder (LX/09bY;->A01) now serves the ads row only. The
 *   check itself became an instance method of a singleton (read with invoke-virtual/range off
 *   LX/09bX;->A00), so it's recognised by its shape and the two surfaces, not by being static.
 * - The footer previews: two runnables whose Redex names are kept, each of which only starts one
 *   query and attaches its callback (580 LX/Aw3 and LX/XZu, 577 LX/B8E and LX/h8J).
 */

internal const val IMMUTABLE_LIST = "Lcom/google/common/collect/ImmutableList;"
private const val IMMUTABLE_LIST_BUILDER = "Lcom/google/common/collect/ImmutableList\$Builder;"
private const val OBJECT_ARRAY = "[Ljava/lang/Object;"

/** How the chip filter's answer becomes a list again: Facebook's own Guava, by name. */
internal const val COPY_OF = "$IMMUTABLE_LIST->copyOf($OBJECT_ARRAY)$IMMUTABLE_LIST"

/**
 * The chip types the extension's `ReelDeclutter.HIDDEN_CHIPS` hides. The builder has to name every
 * one, so a build that renamed a chip stops the patch instead of leaving that chip in quietly.
 * ReelAnchorsFixtureTest holds the two lists together.
 */
internal val HIDDEN_CHIPS = listOf(
    "XFBFBShortsRemixAttribution",
    "XFBFBShortsTemplateAttribution",
    "XFBFBShortsAddYoursStickerAttribution",
    "XFBFBShortsEditsAppAttribution",
    "XFBFBShortsSendStarsAttribution",
    "XFBFBShortsInstantGamesAttribution",
    "XFBFBShortsPartnerAppAttribution",
    "XFBFBShortsExternalLinkAttribution",
)

/**
 * The line naming a reel's sound, "Original audio" or a song, which the extension's
 * `ReelDeclutter.SOUNDTRACK_CHIP` hides under its own switch (#200). The builder has to name it
 * too, and ReelAnchorsFixtureTest holds the two together.
 */
internal const val SOUNDTRACK_CHIP = "XFBFBShortsSoundtrackTitle"

/*
 * The Reels tab draws the audio line in a component of its own (#200; arm64 LX/8MN; on 583, read
 * 2026-10-20). Its render gets the reel's attribution models from a general getter (LX/88g;->A02),
 * walks them for the SOUNDTRACK_CHIP type and returns null when none is there, all before anything
 * else, then builds "<artist> · <title>" from that model. The chip list builder never sees that list,
 * so the chip filter alone left the line on the Reels tab. Three other renders hold the name
 * (LX/9FP;, LX/WvP;, LX/Wuq;), and each either returns before it names the type or first returns a
 * component it built.
 */

/**
 * Whether [method] is the Reels tab's audio line row: a Litho `render` that reads a model's
 * `getTypeName()` and loads [SOUNDTRACK_CHIP] before its first `return-object`, and whose first
 * answer is the null constant, Facebook's own answer for a reel with no sound line.
 */
internal fun isAudioLineRender(method: Method): Boolean {
    if (method.name != RENDER || AccessFlags.STATIC.isSet(method.accessFlags) || !method.returnType.startsWith("L")) return false
    val body = method.body()
    val named = body.indexOfFirst { it.string == SOUNDTRACK_CHIP }
    val firstReturn = body.indexOfFirst { it.opcode == Opcode.RETURN_OBJECT }
    if (named < 0 || firstReturn < named) return false
    if (body.subList(0, named).none { it.methodReference?.name == "getTypeName" }) return false
    return constantAt(body, firstReturn, (body[firstReturn] as OneRegisterInstruction).registerA) == 0
}

/** The chip the patch looks the builder up by. Two dozen methods hold it; one builds the list. */
internal const val CHIP_ANCHOR = "XFBFBShortsRemixAttribution"

internal const val WATCH_FEED_DUMP = "WatchFeedData.txt"
internal const val REMOVE_FOLLOWING_BUTTON = "removeFollowingButton"

/** A literal only the reel author row's main renderer holds. */
internal const val AUTHOR_COMPONENT = "FbShortsViewerVideoAuthorComponentSpec:placeholder_only_for_blank_pfp"

/** The author row's second renderer: a Litho component's `render`, a name the framework keeps. */
internal const val RENDER = "render"

/** The session Facebook's Follow check takes first. The class keeps its name. */
internal const val FB_USER_SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"

/** The two surfaces Facebook's Follow check names, where it holds the button back for a friend. */
internal val FOLLOW_CHECK_SURFACES = listOf("friendly_feed", "friends_tab_ifu")

/** The test key Facebook gives the Follow button it builds for a reel's author. */
internal const val FOLLOW_BUTTON_KEY = "video_author_follow_button"

internal const val HOT_COMMENT_RUNNABLE = "FbShortsViewerFooterHotCommentHelper\$createHotCommentQueryRunnable\$1"
internal const val SOCIAL_BUBBLES_RUNNABLE =
    "FbShortsViewerFooterSocialBubblesHelper\$setupSocialBubblesQuery\$socialBubblesQueryRunnable\$1"

/** The query the hot comment runnable starts, the plain one beside its XAR variant. */
internal const val INLINE_COMMENTS_QUERY = "FBShortsInlineCommentsQuery"

/** The one literal the social bubbles runnable loads: the reel it asks about. */
internal const val VIDEO_ID = "video_id"

private const val RUNNABLE = "Ljava/lang/Runnable;"

private val Instruction.methodReference
    get() = (this as? ReferenceInstruction)?.reference as? MethodReference

private val Instruction.string
    get() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun MethodReference.sameAs(other: MethodReference) =
    definingClass == other.definingClass && name == other.name && returnType == other.returnType &&
        parameterTypes.map { it.toString() } == other.parameterTypes.map { it.toString() }

/** The strings [method] loads. */
internal fun stringsOf(method: Method): Set<String> = method.body().mapNotNull { it.string }.toSet()

/** Whether [method] calls [callee] anywhere in its body. */
internal fun calls(method: Method, callee: MethodReference): Boolean =
    method.body().any { it.methodReference?.sameAs(callee) == true }

/**
 * Whether [method] is the chip list builder: static, an ImmutableList in and out, every hidden chip
 * and the audio line named (loaded, or asked of a string table [resolve] finds), each item read by `getTypeName()`, and
 * the answer built by `ImmutableList$Builder.build()`.
 */
internal fun isChipListBuilder(method: Method, resolve: (MethodReference) -> Method?): Boolean {
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != IMMUTABLE_LIST) return false
    if (method.parameterTypes.none { it.toString() == IMMUTABLE_LIST }) return false
    val body = method.body()
    if (body.isEmpty() || !namesEvery(method, HIDDEN_CHIPS + SOUNDTRACK_CHIP, resolve)) return false
    val calls = body.mapNotNull { it.methodReference }
    return calls.any { it.definingClass == IMMUTABLE_LIST_BUILDER && it.name == "build" && it.returnType == IMMUTABLE_LIST } &&
        calls.any { it.name == "getTypeName" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" }
}

/** Whether [method] names every one of [literals], each loaded or asked of a string table [resolve] reads. */
private fun namesEvery(method: Method, literals: List<String>, resolve: (MethodReference) -> Method?): Boolean {
    val loaded = stringsOf(method)
    return loaded.containsAll(literals) || (loaded + tableStringsAsked(method, resolve)).containsAll(literals)
}

/** The chip list builders [classDef] declares, with [resolve] finding the string tables they ask. */
internal fun chipListBuilders(classDef: ClassDef, resolve: (MethodReference) -> Method?): List<Method> =
    classDef.methods.filter { isChipListBuilder(it, resolve) }

/** Whether [immutableList] has the public static `copyOf(Object[])` the chip hook rebuilds with. */
internal fun hasCopyOfArray(immutableList: ClassDef): Boolean = immutableList.methods.any {
    it.name == "copyOf" && AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) &&
        it.returnType == IMMUTABLE_LIST && it.parameterTypes.map(CharSequence::toString) == listOf(OBJECT_ARRAY)
}

/** Every register an instruction's call passes. */
private fun invokeRegisters(instruction: Instruction): List<Int> = when (instruction) {
    is RegisterRangeInstruction -> (0 until instruction.registerCount).map { instruction.startRegister + it }
    is FiveRegisterInstruction -> listOf(
        instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG,
    ).take(instruction.registerCount)
    else -> emptyList()
}

private fun isInvoke(instruction: Instruction) = instruction.opcode.name.startsWith("invoke")

/** Whether [instruction] leaves something new in [register]. */
private fun writes(instruction: Instruction, register: Int): Boolean {
    if (!instruction.opcode.setsRegister()) return false
    val target = (instruction as? OneRegisterInstruction)?.registerA ?: return false
    return target == register || (instruction.opcode.setsWideRegister() && target + 1 == register)
}

private val MOVES = setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16)
private val OBJECT_MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

/** How far the trace walks from a literal to its write, and back from the write to a value. */
private const val WINDOW = 12

/**
 * The boolean getter whose answer the dump writes beside the name at [literal], or null.
 *
 * <p>The dump writes each value with a call that takes the name, or a name built from it (577 joins
 * a prefix to ".removeFollowingButton" first). That call is followed forward from the literal. Its
 * arguments are then followed back to a no-argument boolean getter, through `String.valueOf(Z)` (580
 * turns the answer into text first) and plain moves. Nearness alone picks wrong: on 580 the getter of
 * the next name sits closer to the "getUDDConfig" literal than its own does.
 */
internal fun followGetterAt(body: List<Instruction>, literal: Int): MethodReference? {
    val key = mutableSetOf((body[literal] as? OneRegisterInstruction)?.registerA ?: return null)
    var write = -1
    var index = literal + 1
    val end = minOf(body.size, literal + WINDOW)
    while (index < end) {
        val instruction = body[index]
        if (isInvoke(instruction) && invokeRegisters(instruction).any(key::contains)) {
            val call = instruction.methodReference ?: return null
            if (call.returnType == "V") {
                write = index
                break
            }
            // A name built from the literal: the call's answer carries the key on.
            val result = body.getOrNull(index + 1)
            if (result?.opcode == Opcode.MOVE_RESULT_OBJECT) {
                key += (result as OneRegisterInstruction).registerA
                index += 2
                continue
            }
        } else if (instruction.opcode in OBJECT_MOVES && (instruction as TwoRegisterInstruction).registerB in key) {
            key += (instruction as TwoRegisterInstruction).registerA
        } else {
            key.removeAll { writes(instruction, it) }
            if (key.isEmpty()) return null
        }
        index++
    }
    if (write < 0) return null
    val getters = invokeRegisters(body[write]).filterNot(key::contains)
        .mapNotNull { getterFeeding(body, write, it, depth = 0) }
    return getters.distinctBy { "${it.definingClass}->${it.name}" }.singleOrNull()
}

/** The boolean getter whose answer reaches [register] just before [before], or null. */
private fun getterFeeding(body: List<Instruction>, before: Int, register: Int, depth: Int): MethodReference? {
    if (depth > 3) return null
    for (index in before - 1 downTo maxOf(0, before - WINDOW)) {
        val instruction = body[index]
        if (!writes(instruction, register)) continue
        val call = body.getOrNull(index - 1)?.takeIf(::isInvoke)
        return when (instruction.opcode) {
            Opcode.MOVE_RESULT -> call?.takeIf(::isBooleanGetterCall)?.methodReference
            Opcode.MOVE_RESULT_OBJECT -> {
                val invoke = call ?: return null
                val text = invoke.methodReference ?: return null
                val valueOfBoolean = text.definingClass == "Ljava/lang/String;" && text.name == "valueOf" &&
                    text.parameterTypes.map(CharSequence::toString) == listOf("Z")
                if (valueOfBoolean) getterFeeding(body, index - 1, invokeRegisters(invoke).single(), depth + 1) else null
            }
            in MOVES -> getterFeeding(body, index, (instruction as TwoRegisterInstruction).registerB, depth + 1)
            else -> null
        }
    }
    return null
}

/** An instance call to a method that takes nothing and answers a boolean. */
private fun isBooleanGetterCall(instruction: Instruction): Boolean {
    val call = instruction.methodReference ?: return false
    return (instruction.opcode == Opcode.INVOKE_VIRTUAL || instruction.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
        call.returnType == "Z" && call.parameterTypes.isEmpty() && invokeRegisters(instruction).size == 1
}

/**
 * What the dump says about the Follow button: the getter, or from 583 on the [flags] it's read from
 * instead, or why neither was found.
 */
internal class FollowGetter(
    val getter: MethodReference?,
    val literals: Int,
    val problem: String?,
    val flags: List<FollowFlag> = emptyList(),
)

/**
 * One MobileConfig flag behind the Following button's value: the key [key] names a case of the
 * merged lambda class [lambda]'s `invoke()`, and [reader] is the method holding the flag read (the
 * case's own static method, or on 585 the static switch whose case reads it inline).
 */
internal class FollowFlag(val lambda: String, val key: Int, val reader: MethodReference) {
    override fun toString() = "$lambda key $key (read in ${reader.definingClass}->${reader.name})"
}

/**
 * The getter every "…removeFollowingButton" value in [dumper] comes from. Each literal has to trace
 * to one getter, and all of them to the same one. From 583 on no getter is called: each value comes from
 * two flag reads in a merged lambda ([followFlagsAt]), and then all of them have to trace to the
 * same pair. [classOf] finds the classes that trace goes through.
 */
internal fun followButtonGetter(dumper: Method, classOf: (String) -> ClassDef? = { null }): FollowGetter {
    val body = dumper.body()
    val literals = body.indices.filter { body[it].string?.endsWith(REMOVE_FOLLOWING_BUTTON) == true }
    if (literals.isEmpty()) return FollowGetter(null, 0, "the dump names no \"$REMOVE_FOLLOWING_BUTTON\" value")
    val getters = literals.map { followGetterAt(body, it) }
    if (getters.all { it == null }) {
        val flags = literals.map { followFlagsAt(body, it, classOf) }
        val pairs = flags.filterNotNull().map { pair -> pair.map { "${it.lambda}:${it.key}" } }.distinct()
        if (flags.none { it == null } && pairs.size == 1) return FollowGetter(null, literals.size, null, flags.first()!!)
    }
    if (getters.any { it == null }) {
        return FollowGetter(null, literals.size,
            "${getters.count { it == null }} of ${literals.size} \"$REMOVE_FOLLOWING_BUTTON\" values trace to no boolean getter")
    }
    val distinct = getters.filterNotNull().distinctBy { "${it.definingClass}->${it.name}" }
    if (distinct.size != 1) {
        return FollowGetter(null, literals.size,
            "the \"$REMOVE_FOLLOWING_BUTTON\" values come from ${distinct.size} getters: ${distinct.joinToString()}")
    }
    return FollowGetter(distinct.single(), literals.size, null)
}

private val INT_CONSTANTS = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST)

/** Where [register] was last written before [before], or null. 583's dump runs straight through, so this reads back over all of it. */
private fun lastWrite(body: List<Instruction>, before: Int, register: Int): Int? =
    (before - 1 downTo 0).firstOrNull { writes(body[it], register) }

/** The int constant [register] holds at [before], through plain moves, or null. */
private fun constantAt(body: List<Instruction>, before: Int, register: Int, depth: Int = 0): Int? {
    if (depth > 3) return null
    val at = lastWrite(body, before, register) ?: return null
    return when (body[at].opcode) {
        in INT_CONSTANTS -> (body[at] as NarrowLiteralInstruction).narrowLiteral
        in MOVES -> constantAt(body, at, (body[at] as TwoRegisterInstruction).registerB, depth + 1)
        else -> null
    }
}

/**
 * The static call whose answer the dump writes beside a name, found from the value [register] that
 * the write at [write] passes: the call's `move-result-object`, or on 585 its `move-result` boolean
 * that `String.valueOf(Z)` turns into text first. Returns the call's index, or null.
 */
private fun pickerBefore(body: List<Instruction>, write: Int, register: Int): Int? {
    val at = lastWrite(body, write, register)?.takeIf { body[it].opcode == Opcode.MOVE_RESULT_OBJECT } ?: return null
    val call = body.getOrNull(at - 1)?.takeIf { it.opcode == Opcode.INVOKE_STATIC } ?: return null
    val text = call.methodReference
    val valueOfBoolean = text != null && text.definingClass == "Ljava/lang/String;" && text.name == "valueOf" &&
        text.parameterTypes.map(CharSequence::toString) == listOf("Z")
    if (!valueOfBoolean) return at - 1
    val answer = lastWrite(body, at - 1, invokeRegisters(call).single())?.takeIf { body[it].opcode == Opcode.MOVE_RESULT } ?: return null
    return (answer - 1).takeIf { body.getOrNull(it)?.opcode == Opcode.INVOKE_STATIC }
}

/**
 * The class and key of the first lambda the dump hands to the picker in [first]: the answer of a
 * static maker keyed by its last int (583, 585), or a `new-instance` of the dump's own keyed by its
 * constructor's int (584).
 */
private fun firstLambdaOf(body: List<Instruction>, picker: Int, first: Int): Pair<String, Int>? {
    val made = lastWrite(body, picker, first) ?: return null
    return when (body[made].opcode) {
        Opcode.MOVE_RESULT_OBJECT -> {
            val maker = body.getOrNull(made - 1)?.takeIf { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
                ?: return null
            val type = maker.methodReference?.takeIf { it.parameterTypes.lastOrNull()?.toString() == "I" }?.returnType ?: return null
            type to (constantAt(body, made - 1, invokeRegisters(maker).last()) ?: return null)
        }
        Opcode.NEW_INSTANCE -> {
            val type = (body[made] as ReferenceInstruction).reference.toString()
            val init = (made + 1 until picker).firstOrNull {
                body[it].opcode == Opcode.INVOKE_DIRECT && invokeRegisters(body[it]).firstOrNull() == first
            } ?: return null
            val constructor = body[init].methodReference ?: return null
            if (constructor.definingClass != type || constructor.name != "<init>" ||
                constructor.parameterTypes.map { it.toString() } != listOf("Ljava/lang/Object;", "I")) return null
            type to (constantAt(body, init, invokeRegisters(body[init]).last()) ?: return null)
        }
        else -> null
    }
}

/** A lambda the picker helper hands the config's pick: one of its own parameters, or one it makes from an int parameter. */
private sealed interface PickedLambda

private class PassedIn(val parameter: Int) : PickedLambda

private class MadeHere(val type: String, val keyParameter: Int) : PickedLambda

/** The index among [helper]'s declared parameters that [register] holds on entry (the helper is static), or null. */
private fun parameterAt(helper: Method, register: Int): Int? {
    fun width(type: CharSequence) = if (type.toString() == "J" || type.toString() == "D") 2 else 1
    var start = (helper.implementation?.registerCount ?: return null) - helper.parameterTypes.sumOf(::width)
    helper.parameterTypes.forEachIndexed { index, type ->
        if (register == start) return index
        start += width(type)
    }
    return null
}

/**
 * The two lambdas the static [helper] gives the config's pick (an instance call taking two
 * Function0 and answering Object), in order, or null when it doesn't have that shape. Each is a
 * parameter passed through, or a `new-instance` made in the helper with its constructor's int read
 * from a parameter.
 */
private fun pickedLambdas(helper: Method): List<PickedLambda>? {
    val body = helper.body()
    val picks = body.indices.filter {
        val call = body[it].methodReference
        call != null && (body[it].opcode == Opcode.INVOKE_VIRTUAL || body[it].opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
            call.returnType == "Ljava/lang/Object;" && call.parameterTypes.map(CharSequence::toString) == listOf(FUNCTION0, FUNCTION0)
    }
    val at = picks.singleOrNull() ?: return null
    val registers = invokeRegisters(body[at]).drop(1).takeIf { it.size == 2 } ?: return null
    return registers.map { register ->
        val written = lastWrite(body, at, register)
        when {
            written == null -> PassedIn(parameterAt(helper, register) ?: return null)
            body[written].opcode == Opcode.NEW_INSTANCE -> {
                val type = (body[written] as ReferenceInstruction).reference.toString()
                val init = (written + 1 until at).firstOrNull {
                    body[it].opcode == Opcode.INVOKE_DIRECT && invokeRegisters(body[it]).firstOrNull() == register
                } ?: return null
                val constructor = body[init].methodReference ?: return null
                if (constructor.definingClass != type || constructor.name != "<init>" ||
                    constructor.parameterTypes.map { it.toString() } != listOf("Ljava/lang/Object;", "I")) return null
                MadeHere(type, parameterAt(helper, invokeRegisters(body[init]).last()) ?: return null)
            }
            else -> return null
        }
    }
}

/**
 * The two flag reads behind the "…removeFollowingButton" value at [literal] from 583 on (583 LX/8cq;,
 * read 2026-10-16). The dump writes the answer of a static helper that has the config pick one of two
 * lambdas (`A0f` on 583, by whether the viewer is Shorts in the Watch tab). Each lambda is a key into a
 * merged Kotlin lambda class, a case of its `invoke()` ([flagReader]) that reads one MobileConfig flag
 * and boxes it. Four helper shapes have been seen:
 * - 583 (A02) and 584 (A03): `(config, Function0, I)String` makes the second lambda of the first
 *   one's class from its int. The first lambda is the answer of the static call that also wrote the
 *   previous value (583 A05), keyed by its last int, or 584 makes it in the dump with `new-instance`.
 * - 585, the Player tab's value (LX/0ACj;->A0t on arm64): `(config, Function0, I)Z`, which the dump
 *   turns into text with `String.valueOf(Z)`. The first lambda is the maker's answer as on 583, and the
 *   helper makes the second one of a class of its own from its int.
 * - 585, the UDD value (LX/0Ap7;->A00): `(config, I, I)String` makes both lambdas, one from each int.
 * - 585's Android 9 build, both values (LX/0ArK;->A01): `(config, Function0, I)String` as on 584, the
 *   first lambda made in the dump with `new-instance`, but the helper makes the second of a class of
 *   its own from its int, as the Player tab's helper does on 585's other builds.
 */
internal fun followFlagsAt(body: List<Instruction>, literal: Int, classOf: (String) -> ClassDef?): List<FollowFlag>? {
    val name = (body[literal] as? OneRegisterInstruction)?.registerA ?: return null
    val write = (literal + 1 until minOf(body.size, literal + WINDOW)).firstOrNull {
        isInvoke(body[it]) && name in invokeRegisters(body[it])
    } ?: return null
    if (body[write].methodReference?.returnType != "V") return null
    val picker = invokeRegisters(body[write]).filterNot { it == name }.firstNotNullOfOrNull { pickerBefore(body, write, it) } ?: return null
    val pick = body[picker].methodReference ?: return null
    val parameters = pick.parameterTypes.map { it.toString() }
    if (parameters.size != 3 || pick.returnType != "Ljava/lang/String;" && pick.returnType != "Z") return null
    val registers = invokeRegisters(body[picker]).takeIf { it.size == 3 } ?: return null

    val helper = classOf(pick.definingClass)?.methods?.singleOrNull {
        it.sameAs(pick) && AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null
    } ?: return null
    // Each lambda of the pair as its class and the key that names its case.
    val oneClass: List<Pair<String, Int>>? = if (pick.returnType == "Ljava/lang/String;" && parameters.drop(1) == listOf(FUNCTION0, "I")) {
        // The helper makes its lambda of the first one's class, so both keys name cases of one lambda.
        firstLambdaOf(body, picker, registers[1])?.takeIf { (lambdaType, _) ->
            helper.body().any { it.opcode == Opcode.NEW_INSTANCE && (it as ReferenceInstruction).reference.toString() == lambdaType }
        }?.let { (lambdaType, firstKey) -> listOf(lambdaType to firstKey, lambdaType to (constantAt(body, picker, registers[2]) ?: return null)) }
    } else {
        null
    }
    // Otherwise each lambda is passed in or made by the helper (585, and 585's Android 9 build's LX/0ArK;->A01).
    val lambdas: List<Pair<String, Int>> = oneClass ?: run {
        pickedLambdas(helper)?.map { lambda ->
            when (lambda) {
                is PassedIn -> firstLambdaOf(body, picker, registers[lambda.parameter]) ?: return null
                is MadeHere -> lambda.type to (constantAt(body, picker, registers[lambda.keyParameter]) ?: return null)
            }
        } ?: return null
    }

    val flags = lambdas.map { (type, key) ->
        FollowFlag(type, key, flagReader(classOf(type) ?: return null, key) ?: return null)
    }
    return flags.takeIf { it.distinctBy { flag -> "${flag.lambda}:${flag.key}" }.size == 2 }
}

private const val FUNCTION0 = "Lkotlin/jvm/functions/Function0;"

private const val OBJECT = "Ljava/lang/Object;"

/**
 * Where a merged lambda's switches send a key: the [method] whose switch has the case, the [address]
 * the case starts at, and its [call] when the case is only a call to a static method of the lambda's
 * class that takes the lambda and answers an Object (null for code written into the switch).
 */
private class CaseSite(val method: Method, val address: Int, val call: MethodReference?)

/** The lambda's `invoke()`: no arguments, an Object back, a body. */
private fun invokeOf(lambda: ClassDef): Method? = lambda.methods.singleOrNull {
    it.name == "invoke" && it.parameterTypes.isEmpty() && it.returnType == OBJECT && it.implementation != null
}

/**
 * Follows [key] through a Redex-merged Kotlin lambda's switches: `invoke()` switches on the key the
 * lambda was made with, and its case either calls a static method that holds the work, hands the key
 * on to another static switch of the class (583's `A1J(L, I)`), or has the work written into the
 * switch itself (585's flag at the third switch, with a read cached in the config's holder). On 584 a
 * key with no case falls through the switch's default to the next one, twice for the Follow flags
 * (`A1D`, then `A1B`).
 */
private fun caseSite(lambda: ClassDef, key: Int): CaseSite? {
    var method: Method = invokeOf(lambda) ?: return null
    repeat(LAMBDA_SWITCHES) {
        val byAddress = addressed(method)
        val target = caseOrDefault(byAddress, key) ?: return null
        val call = byAddress[target]?.takeIf { it.opcode == Opcode.INVOKE_STATIC }?.methodReference
        val parameters = call?.parameterTypes?.map { it.toString() }
        when {
            call == null || call.definingClass != lambda.type -> return CaseSite(method, target, null)
            parameters == listOf(lambda.type) && call.returnType == OBJECT -> return CaseSite(method, target, call)
            parameters == listOf(lambda.type, "I") ->
                method = lambda.methods.singleOrNull { it.sameAs(call) && it.implementation != null } ?: return null
            // Some other call at the case's start, such as a holder lookup, is code of the case itself.
            else -> return CaseSite(method, target, null)
        }
    }
    return null
}

/**
 * The static method a Redex-merged Kotlin lambda of class [lambda] runs for [key], or null when its
 * case is written into a switch instead ([caseSite]).
 */
internal fun lambdaCase(lambda: ClassDef, key: Int): MethodReference? = caseSite(lambda, key)?.call

/** How many switches [lambdaCase] follows a key through before it gives up. */
private const val LAMBDA_SWITCHES = 4

/**
 * The method that reads the MobileConfig flag [lambda]'s case for [key] answers, or null when the
 * case isn't one flag read. A case that calls a static method of its own is read there (583, 584); 585
 * writes some flags into the switch itself, and then the switch's method is the reader.
 */
internal fun flagReader(lambda: ClassDef, key: Int): MethodReference? {
    val site = caseSite(lambda, key) ?: return null
    val call = site.call
    return if (call != null) call.takeIf { isFlagRead(lambda, it) } else site.method.takeIf { readsFlagInline(it, site.address) }
}

/**
 * Whether the case of [method] that starts at [address] reads a MobileConfig flag: before its first
 * jump away or return it calls a method of the config classes that answers a boolean.
 */
private fun readsFlagInline(method: Method, address: Int): Boolean {
    for ((at, instruction) in addressed(method)) {
        if (at < address) continue
        val call = instruction.methodReference
        if (call != null && call.definingClass.startsWith("Lcom/facebook/mobileconfig/") && call.returnType == "Z") return true
        val leaves = instruction.opcode == Opcode.GOTO || instruction.opcode == Opcode.GOTO_16 || instruction.opcode == Opcode.GOTO_32 ||
            instruction.opcode == Opcode.RETURN_OBJECT || instruction.opcode == Opcode.RETURN || instruction.opcode == Opcode.THROW
        if (leaves) return false
    }
    return false
}

/**
 * The int field [lambda]'s `invoke()` switches on, the key every lambda of the class was made with:
 * the field it reads off itself before its first switch. Null when `invoke()` reads none.
 */
internal fun lambdaKeyField(lambda: ClassDef): FieldReference? {
    val body = invokeOf(lambda)?.body() ?: return null
    val switch = body.indexOfFirst { it.opcode == Opcode.PACKED_SWITCH || it.opcode == Opcode.SPARSE_SWITCH }
    if (switch < 0) return null
    val read = body.take(switch).lastOrNull { it.opcode == Opcode.IGET } ?: return null
    return (read as ReferenceInstruction).reference.let { it as? FieldReference }
        ?.takeIf { it.definingClass == lambda.type && it.type == "I" }
}

/** Where the method's first switch sends [key]: its case, or the instruction after the switch when it has none. */
private fun caseOrDefault(byAddress: Map<Int, Instruction>, key: Int): Int? {
    switchTarget(byAddress, key)?.let { return it }
    val (address, switch) = byAddress.entries.firstOrNull {
        it.value.opcode == Opcode.SPARSE_SWITCH || it.value.opcode == Opcode.PACKED_SWITCH
    } ?: return null
    return address + switch.codeUnits
}

/** Whether [flag], a case of [lambda], answers one boxed boolean read from a MobileConfig flag. */
private fun isFlagRead(lambda: ClassDef, flag: MethodReference): Boolean {
    val method = lambda.methods.singleOrNull { it.sameAs(flag) && AccessFlags.STATIC.isSet(it.accessFlags) } ?: return false
    val calls = method.body().mapNotNull { it.methodReference }
    return method.returnType == "Ljava/lang/Object;" &&
        calls.any { it.definingClass == "Ljava/lang/Boolean;" && it.name == "valueOf" } &&
        calls.any { it.definingClass.startsWith("Lcom/facebook/mobileconfig/") && it.returnType == "Z" }
}

/**
 * Why [readers] aren't the Follow getter's known readers, or null when they are: the dump, the
 * author row's renderer holding [AUTHOR_COMPONENT], and one other `render`. A new reader could draw
 * the button somewhere this hasn't been checked, so it stops the patch.
 */
internal fun followReaderProblem(readers: List<Method>, dumper: Method): String? {
    fun describe() = readers.joinToString { "${it.definingClass}->${it.name}" }
    if (readers.size != 3) return "the Follow getter has ${readers.size} readers, expected 3: ${describe()}"
    val others = readers.filterNot { it.definingClass == dumper.definingClass && it.name == dumper.name }
    if (others.size != 2) return "the dump isn't among the Follow getter's readers: ${describe()}"
    val author = others.filter { holdsString(it, AUTHOR_COMPONENT) }
    if (author.size != 1) return "${author.size} of the Follow getter's readers hold \"$AUTHOR_COMPONENT\": ${describe()}"
    val second = others.single { it !== author.single() }
    if (second.name != RENDER) return "the Follow getter's last reader isn't a render method: ${describe()}"
    return null
}

/** The reel author row's renderer among [readers]: the one reader besides [dumper] holding [AUTHOR_COMPONENT]. */
internal fun authorRow(readers: List<Method>, dumper: Method): Method? = readers
    .filterNot { it.definingClass == dumper.definingClass && it.name == dumper.name }
    .singleOrNull { holdsString(it, AUTHOR_COMPONENT) }

/** The methods [method] calls with `invoke-static`, each once, in the order it first calls them. */
internal fun staticCalls(method: Method): List<MethodReference> = method.body()
    .filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
    .mapNotNull { it.methodReference }
    .distinctBy { it.toString() }

/**
 * The methods [method] calls with `invoke-static` or `invoke-virtual`, each once, in the order it
 * first calls them. 585 moved the Follow check onto a singleton, so the row now calls it virtually.
 */
internal fun staticOrVirtualCalls(method: Method): List<MethodReference> = method.body()
    .filter {
        it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE ||
            it.opcode == Opcode.INVOKE_VIRTUAL || it.opcode == Opcode.INVOKE_VIRTUAL_RANGE
    }
    .mapNotNull { it.methodReference }
    .distinctBy { it.toString() }

/** The method [owner] declares under [call]'s name and shape, static or not; null when it declares none. */
internal fun resolveCalled(owner: ClassDef, call: MethodReference): Method? = owner.methods.firstOrNull {
    it.name == call.name && it.returnType == call.returnType &&
        it.parameterTypes.map { type -> type.toString() } == call.parameterTypes.map { type -> type.toString() }
}

/**
 * Whether [method] has the shape of Facebook's Follow check: a boolean answer, the session first,
 * and both [FOLLOW_CHECK_SURFACES] named. Static up to 584, an instance method of a singleton on 585.
 */
internal fun isFollowCheck(method: Method): Boolean =
    method.returnType == "Z" &&
        method.parameterTypes.firstOrNull()?.toString() == FB_USER_SESSION &&
        stringsOf(method).containsAll(FOLLOW_CHECK_SURFACES)

/** What the author row says about the Follow check: the check, or why none was taken. */
internal class FollowCheck(val check: Method?, val problem: String?)

/**
 * The Follow check [author], the reel author row's renderer, asks before it builds the Follow
 * button. [resolve] finds the method a static call reaches, or null for one outside the APK.
 *
 * <p>The row has to build the button itself, through a call holding [FOLLOW_BUTTON_KEY] or (585,
 * where Redex folded the builder into the row) by holding it, or the check it asks may no longer be
 * what decides the button. And it has to ask exactly one check: a second would mean the rule that
 * picks it no longer tells them apart.
 */
internal fun followCheck(author: Method, resolve: (MethodReference) -> Method?): FollowCheck {
    val callees = staticOrVirtualCalls(author).mapNotNull(resolve)
    if (!holdsString(author, FOLLOW_BUTTON_KEY) && callees.none { holdsString(it, FOLLOW_BUTTON_KEY) }) {
        return FollowCheck(null, "the author row doesn't build the Follow button itself: neither it nor its calls hold \"$FOLLOW_BUTTON_KEY\"")
    }
    val checks = callees.filter(::isFollowCheck)
    return checks.singleOrNull()?.let { FollowCheck(it, null) } ?: FollowCheck(null,
        "the author row asks ${checks.size} Follow checks, expected 1 (a boolean, the session first, " +
            "naming ${FOLLOW_CHECK_SURFACES.joinToString(" and ") { "\"$it\"" }})" +
            checks.joinToString(prefix = if (checks.isEmpty()) "" else ": ") { "${it.definingClass}->${it.name}" },
    )
}

/**
 * The `run()` of [classDef] when Redex left it the name [redexName], it's a Runnable, and its run
 * loads [holding]; otherwise null.
 */
internal fun footerRunnable(classDef: ClassDef, redexName: String, holding: String): Method? {
    if (redexOriginalName(classDef) != redexName) return null
    if (classDef.interfaces.none { it.toString() == RUNNABLE }) return null
    return classDef.methods.singleOrNull {
        it.name == "run" && it.returnType == "V" && it.parameterTypes.isEmpty() && it.implementation != null &&
            holdsString(it, holding)
    }
}
