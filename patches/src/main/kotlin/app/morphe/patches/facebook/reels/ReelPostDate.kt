/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.RegisterLiveness
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.namedRegisters
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * The post date under a reel's or a full-screen video's author (#296). Read from 585, 2026-10-31,
 * arm64 and armeabi-v7a; the obfuscated names here are for reviewers, the code never writes one down.
 *
 * - The line under the author is built by a static helper (arm64 LX/07pM;->A02, v7 LX/07pR;->A02)
 *   that the reel footer's builder (LX/09bx;->A17, v7 LX/09c2;->A17) calls. It takes the session
 *   first and a flag last, and answers null, which draws nothing, unless a server-side switch says yes.
 * - That switch is one static call (LX/0ACj;->A0t, v7 LX/0ACo;->A0t) handed a lazy config, a
 *   Function0 and a field number, whose boolean comes back through move-result and is compared
 *   with 1. Facebook's answer is no for most accounts.
 * - Past it the helper reads the video's creation_time through TreeJNI.getTimeValue and formats it
 *   with the same (Context, FbUserSession, long) formatter feed headers use. The hook hands the
 *   switch's answer to the extension, which says yes while the setting is on.
 * - That formatter (arm64 LX/0dPO;->A00) takes the time in seconds and answers "3 days ago", but
 *   weeks, months and a date each sit behind another server switch, and it answers null past them,
 *   which draws nothing (#357). A second hook hands its answer and the time to the extension, which
 *   words the date itself where it's blank or the date style asks for the day.
 * - Only one line sits under the author, though, and on a reel with a song the Reels tab's audio
 *   line (arm64 LX/09UU;->render, v7 LX/09UZ;->render) takes it, so most reels still showed no date
 *   (#357, 2026-11-02, five of six reels on the S22). That render keeps the reel's story in a field of
 *   its own, the same type the helper takes second and casts to TreeJNI for creation_time, and joins
 *   "<artist> · <title>" with one static call of three Strings. A third hook hands the joined line
 *   and the story to the extension, which puts the date in front.
 */

internal const val SHOW_POST_DATE = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->showsPostDate(Z)Z"
internal const val POST_DATE_TEXT = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->postDateText(Ljava/lang/String;J)Ljava/lang/String;"
internal const val AUDIO_LINE_TEXT =
    "$EXTENSION_PACKAGE/reels/ReelDeclutter;->audioLineText(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;"
private const val STRING = "Ljava/lang/String;"

/** The video's tree key for when it was posted. It's the name's String.hashCode(). */
internal val CREATION_TIME = "creation_time".hashCode()

private const val PATCH = "Clean up Reels"
private const val TREE = "Lcom/facebook/graphservice/tree/TreeJNI;"
private const val USER_SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"
private const val FUNCTION0 = "Lkotlin/jvm/functions/Function0;"
private val CONSTANTS = setOf(Opcode.CONST, Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST_HIGH16)

private val Method.code get() = implementation?.instructions?.toList().orEmpty()

private fun MethodReference.params() = parameterTypes.map(CharSequence::toString)

/** Where [method] reads [CREATION_TIME] through TreeJNI.getTimeValue, or -1 unless it does exactly once. */
internal fun creationTimeRead(method: Method): Int {
    val code = method.code
    val reads = code.indices.filter { at ->
        val call = code[at] as? FiveRegisterInstruction ?: return@filter false
        val reference = (call as ReferenceInstruction).reference as? MethodReference ?: return@filter false
        if (call.opcode != Opcode.INVOKE_VIRTUAL || reference.definingClass != TREE || reference.name != "getTimeValue" ||
            reference.returnType != "J" || reference.params() != listOf("I") || call.registerCount != 2
        ) return@filter false
        val writers = method.writersReaching(at, call.registerD)
        writers.isNotEmpty() && writers.all {
            it >= 0 && code[it].opcode in CONSTANTS && (code[it] as NarrowLiteralInstruction).narrowLiteral == CREATION_TIME
        }
    }
    return reads.singleOrNull() ?: -1
}

/**
 * The server switch's call in [method] before [read]: invoke-static of a method taking an object, a
 * Function0 and an int and answering a boolean, with a move-result after it. Null unless there's one.
 */
internal fun postDateGate(method: Method, read: Int): Int? {
    val code = method.code
    return (0 until read).filter { at ->
        val reference = (code[at] as? ReferenceInstruction)?.reference as? MethodReference ?: return@filter false
        val params = reference.params()
        (code[at].opcode == Opcode.INVOKE_STATIC || code[at].opcode == Opcode.INVOKE_STATIC_RANGE) && reference.returnType == "Z" && params.size == 3 &&
            params[0].startsWith("L") && params[1] == FUNCTION0 && params[2] == "I" &&
            code.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT
    }.singleOrNull()
}

/** Whether [instruction] calls a (Context, FbUserSession, long) to String formatter. */
private fun formatsTime(instruction: Instruction): Boolean {
    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return reference.returnType == "Ljava/lang/String;" &&
        reference.params() == listOf("Landroid/content/Context;", USER_SESSION, "J")
}

/** Where [method] formats the time it read at [read], or -1 unless one call after it does. */
internal fun postDateFormat(method: Method, read: Int): Int =
    method.code.indices.filter { it > read && formatsTime(method.code[it]) }.singleOrNull() ?: -1

/**
 * Whether [method] is the reel's post date line: static, the session first and a boolean last, an
 * object back, one [CREATION_TIME] read formatted by a (Context, FbUserSession, long) to String
 * call, and one [postDateGate] ahead of the read.
 */
internal fun isReelPostDate(method: Method): Boolean {
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || !method.returnType.startsWith("L")) return false
    val params = method.parameterTypes.map(CharSequence::toString)
    if (params.size < 3 || params.first() != USER_SESSION || params.last() != "Z") return false
    val read = creationTimeRead(method)
    if (read < 0) return false
    return postDateFormat(method, read) >= 0 && postDateGate(method, read) != null
}

/**
 * The registers the formatter call at [at] in [method] takes the time in, low then high, and the one
 * its words land in through the move-result-object right after. Null when the call has no such
 * result or the three don't fit a plain invoke (each below v16, the words apart from the time).
 */
internal fun postDateRegisters(method: Method, at: Int): Triple<Int, Int, Int>? {
    val code = method.code
    val call = code[at]
    val result = code.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction ?: return null
    // The receiver, when there is one, then the Context and the session, then the time's pair.
    val first = if (call.opcode == Opcode.INVOKE_STATIC || call.opcode == Opcode.INVOKE_STATIC_RANGE) 2 else 3
    val registers = when (call) {
        is FiveRegisterInstruction -> listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG)
            .take(call.registerCount)
        is RegisterRangeInstruction -> (call.startRegister until call.startRegister + call.registerCount).toList()
        else -> return null
    }
    val low = registers.getOrNull(first) ?: return null
    val high = registers.getOrNull(first + 1) ?: return null
    val words = result.registerA
    if (words == low || words == high || maxOf(words, low, high) > 15) return null
    return Triple(low, high, words)
}

/**
 * Where [method], the Reels tab's audio line, joins the song's artist and title: the one static call
 * past [SOUNDTRACK_CHIP] that takes three Strings and answers one, with its move-result-object right
 * after. -1 unless there's exactly one.
 */
internal fun audioLineJoin(method: Method): Int {
    val code = method.code
    val named = code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == SOUNDTRACK_CHIP }
    if (named < 0) return -1
    return code.indices.filter { at ->
        val reference = (code[at] as? ReferenceInstruction)?.reference as? MethodReference ?: return@filter false
        at > named && (code[at].opcode == Opcode.INVOKE_STATIC || code[at].opcode == Opcode.INVOKE_STATIC_RANGE) &&
            reference.returnType == STRING && reference.params() == listOf(STRING, STRING, STRING) &&
            code.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
    }.singleOrNull() ?: -1
}

/**
 * The register holding the reel's story where code put at [at] in [method] runs: the one read of the
 * render's own field of [storyType], when that read is the only write of its register reaching [at].
 * Null otherwise.
 */
internal fun audioLineStory(method: Method, at: Int, storyType: String): Int? {
    val code = method.code
    val reads = code.indices.filter { index ->
        val field = (code[index] as? ReferenceInstruction)?.reference as? FieldReference ?: return@filter false
        code[index].opcode == Opcode.IGET_OBJECT && field.definingClass == method.definingClass && field.type == storyType
    }
    val read = reads.singleOrNull()?.takeIf { it < at } ?: return null
    val register = (code[read] as TwoRegisterInstruction).registerA
    return register.takeIf { method.writersReaching(at, register) == listOf(read) }
}

/**
 * Puts the post date in front of the Reels tab's song line, [method], the render `reelsTabAudioLine`
 * found: the joined line and the reel's story, of [storyType], go to the extension right after the
 * join, and its answer takes the line's place. The registers are copied down below v16 through ones
 * nothing reads there when they sit higher.
 */
internal fun dateReelsTabAudioLine(method: MutableMethod, storyType: String) {
    val owner = method.definingClass
    val join = audioLineJoin(method)
    if (join < 0) throw PatchException("$PATCH: the audio line in $owner doesn't join its song in one call")
    val at = join + 2
    val line = (method.code[join + 1] as OneRegisterInstruction).registerA
    val story = audioLineStory(method, at, storyType)
        ?: throw PatchException("$PATCH: the audio line in $owner holds no story of $storyType where its song is joined")
    val live = RegisterLiveness.of(method).liveInto(at)
    val spare = (0 until minOf(16, method.implementation!!.registerCount)).filter { it !in live && it != line && it != story }
    val lineLow = if (line < 16) line else spare.getOrNull(0)
    val storyLow = if (story < 16) story else spare.getOrNull(if (line < 16) 0 else 1)
    if (lineLow == null || storyLow == null) throw PatchException("$PATCH: no spare register below v16 in $owner for the post date")
    val code = buildString {
        if (lineLow != line) appendLine("move-object/from16 v$lineLow, v$line")
        if (storyLow != story) appendLine("move-object/from16 v$storyLow, v$story")
        appendLine("invoke-static { v$lineLow, v$storyLow }, $AUDIO_LINE_TEXT")
        appendLine("move-result-object v$lineLow")
        if (lineLow != line) appendLine("move-object/from16 v$line, v$lineLow")
    }
    method.addInstructions(at, code)
}

/**
 * Hands the line's server switch to the extension, which says yes while the setting is on, and
 * answers the type of the story the line is built from.
 */
internal fun BytecodePatchContext.showReelPostDate(): String {
    val found = mutableListOf<Method>()
    classDefForEachReferencing(names = listOf("getTimeValue")) { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
        classDef.methods.filterTo(found, ::isReelPostDate)
    }
    val helper = found.singleOrNull()
        ?: throw PatchException("$PATCH: expected one post date line reading creation_time behind a server switch, found ${found.size}")
    val method = mutableClassDefBy(helper.definingClass).methods.single {
        it.name == helper.name && it.parameterTypes.map(CharSequence::toString) == helper.parameterTypes.map(CharSequence::toString)
    }
    val read = creationTimeRead(method)
    val format = postDateFormat(method, read)
    val (low, high, words) = postDateRegisters(method, format)
        ?: throw PatchException("$PATCH: the post date's formatter in ${helper.definingClass} doesn't hand back its words where a hook can read them")
    // The later hook goes in first, so the gate's index still holds.
    method.addInstructions(
        format + 2,
        """
            invoke-static { v$words, v$low, v$high }, $POST_DATE_TEXT
            move-result-object v$words
        """,
    )
    val gate = postDateGate(method, read)!!
    val result = (method.code[gate + 1] as OneRegisterInstruction).registerA
    method.addInstructions(
        gate + 2,
        """
            invoke-static/range { v$result .. v$result }, $SHOW_POST_DATE
            move-result v$result
        """,
    )
    return helper.parameterTypes[1].toString()
}

/**
 * The reel footer's server flag that leaves a recommendation reason alone under the author (#496).
 * The footer builder lists the post date and one label per attribution, slots the reason in, and
 * with this flag on swaps the whole list for the reason, so a reel with one showed no date and the
 * next one without did. The builder reads the flag right after naming it in this message.
 */
internal const val REASON_ALONE_FLAG = "Rec reason exclusive mode is only supported on Reels Tab surface"
internal const val REASON_ALONE = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->reasonAlone(Z)Z"

/**
 * Where [method] holds the reason-alone flag: the move-result of the first Boolean.booleanValue()
 * past [REASON_ALONE_FLAG], within a few instructions of it. -1 unless the message is there once.
 */
internal fun reasonAloneRead(method: Method): Int {
    val code = method.code
    val named = code.indices.filter {
        ((code[it] as? ReferenceInstruction)?.reference as? StringReference)?.string == REASON_ALONE_FLAG
    }.singleOrNull() ?: return -1
    val unbox = (named + 1 until minOf(code.size, named + 16)).firstOrNull {
        val reference = (code[it] as? ReferenceInstruction)?.reference as? MethodReference ?: return@firstOrNull false
        code[it].opcode == Opcode.INVOKE_VIRTUAL && reference.definingClass == "Ljava/lang/Boolean;" &&
            reference.name == "booleanValue" && reference.returnType == "Z"
    } ?: return -1
    return if (code.getOrNull(unbox + 1)?.opcode == Opcode.MOVE_RESULT) unbox + 1 else -1
}

/** The reel footer's builder, the one method that reads [REASON_ALONE_FLAG] as a boolean. */
private fun BytecodePatchContext.reelFooter(): MutableMethod {
    val builders = classDefByStrings(REASON_ALONE_FLAG, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .flatMap { classDef -> classDef.methods.filter { reasonAloneRead(it) >= 0 }.map { classDef.type to it } }
    val (owner, builder) = builders.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one reel footer that reads \"$REASON_ALONE_FLAG\" as a boolean, found ${builders.size}",
    )
    return mutableClassDefBy(owner).methods.single {
        it.name == builder.name && it.parameterTypes.map(CharSequence::toString) == builder.parameterTypes.map(CharSequence::toString)
    }
}

/** Hands the reel footer's reason-alone flag to the extension, which says no while the date is on. */
internal fun BytecodePatchContext.keepPostDateBesideReason() {
    val method = reelFooter()
    val read = reasonAloneRead(method)
    val flag = (method.code[read] as OneRegisterInstruction).registerA
    method.addInstructions(
        read + 1,
        """
            invoke-static/range { v$flag .. v$flag }, $REASON_ALONE
            move-result v$flag
        """,
    )
}

/*
 * The date on a reel without sound (#496, read from 587, 2026-11-17, arm64 and armeabi-v7a).
 *
 * - A reel with a song shows the song line under its author, which the hook above dates. A silent
 *   reel has none: the audio line's render answers null without audio, and the reel's soundtrack
 *   attribution ("Reel has no sound") shows as one of the footer's ticker labels instead, undated.
 * - The footer's builder (arm64 LX/08j3;->A19, v7 LX/08ip;->A19) names SUBTITLE_LABEL, then loops
 *   over the attributions, wording each with one static helper (arm64 LX/09Zl;->A06, v7
 *   LX/09ZY;->A06) taking the session, the attribution and the story, and builds a ticker label
 *   (arm64 LX/0CTW, v7 LX/0CSz) of an icon, that String, a Function0 and a flag. The same String
 *   also goes into a row label, which the date row already sits above, so that copy stays as it was.
 * - When the ticker comes up empty the builder asks a second helper in the first one's class (arm64
 *   LX/09Zl;->A08) for the soundtrack's ticker label alone. It words it with the same helper, but
 *   clears the register holding the story first, so the story is kept for it on the way in.
 */

/** The id prefix the reel footer gives each label under the author, named right before its attribution loop. */
internal const val SUBTITLE_LABEL = "FBShortsVideoAugmentSubtitleLabel-"
internal const val SOUND_LABEL_TEXT =
    "$EXTENSION_PACKAGE/reels/ReelDeclutter;->soundLabelText(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/String;"
internal const val SOUND_STORY = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->soundStory(Ljava/lang/Object;)V"
internal const val SOUND_TICKER_TEXT =
    "$EXTENSION_PACKAGE/reels/ReelDeclutter;->soundTickerText(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;"

/** Whether [instruction] calls a static (FbUserSession, attribution, [storyType], ...) to String helper. */
private fun wordsLabel(instruction: Instruction, storyType: String): Boolean {
    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    val params = reference.params()
    return (instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE) &&
        reference.returnType == STRING && params.size >= 3 && params[0] == USER_SESSION &&
        params[1].startsWith("L") && params[2] == storyType
}

/**
 * Where [method], the reel footer, words an attribution's label: the one [wordsLabel] call past
 * [SUBTITLE_LABEL], with its move-result-object right after. -1 unless there's exactly one.
 */
internal fun subtitleLabelCall(method: Method, storyType: String): Int {
    val code = method.code
    val named = code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == SUBTITLE_LABEL }
    if (named < 0) return -1
    return code.indices.filter {
        it > named && wordsLabel(code[it], storyType) && code.getOrNull(it + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
    }.singleOrNull() ?: -1
}

/**
 * The ticker label [method] builds from the label it words at [call]: the index of the new-instance
 * and of its constructor, which takes the label as a String beside a Function0. Null unless the label
 * reaches that constructor unchanged and nothing branches between the two.
 */
internal fun soundTicker(method: Method, call: Int): Pair<Int, Int>? {
    val code = method.code
    val label = (code.getOrNull(call + 1) as? OneRegisterInstruction)?.registerA ?: return null
    val init = (call + 2 until code.size).firstOrNull {
        val reference = (code[it] as? ReferenceInstruction)?.reference as? MethodReference ?: return@firstOrNull false
        (code[it].opcode == Opcode.INVOKE_DIRECT || code[it].opcode == Opcode.INVOKE_DIRECT_RANGE) &&
            reference.name == "<init>" && label in code[it].namedRegisters().drop(1)
    } ?: return null
    val params = ((code[init] as ReferenceInstruction).reference as MethodReference).params()
    val args = code[init].namedRegisters()
    if (FUNCTION0 !in params || params.getOrNull(args.indexOf(label) - 1) != STRING) return null
    val created = (call + 2 until init).lastOrNull {
        code[it].opcode == Opcode.NEW_INSTANCE && (code[it] as OneRegisterInstruction).registerA == args[0]
    } ?: return null
    if ((created until init).any { code[it] is OffsetInstruction || !code[it].opcode.canContinue() }) return null
    if (method.writersReaching(init, label) != listOf(call + 1)) return null
    return created to init
}

private fun Instruction.written(): List<Int> {
    if (!opcode.setsRegister()) return emptyList()
    val first = namedRegisters().firstOrNull() ?: return emptyList()
    return if (opcode.setsWideRegister()) listOf(first, first + 1) else listOf(first)
}

/**
 * Hands the label [method] words at [call] to the extension right before the ticker label built from
 * it, [ticker], with its attribution and, in [story], the reel's story, or with null for the one
 * [SOUND_STORY] kept. Facebook's own label goes back right after the ticker's constructor, for the
 * row label and anything else built from it. Registers at v16 and up are copied down through ones
 * nothing reads there.
 */
private fun dateSoundTicker(method: MutableMethod, call: Int, ticker: Pair<Int, Int>, story: Int?) {
    val owner = method.definingClass
    val code = method.code
    val (at, init) = ticker
    val label = (code[call + 1] as OneRegisterInstruction).registerA
    val attribution = code[call].namedRegisters()[1]
    if (method.writersReaching(at, attribution) != method.writersReaching(call, attribution)) {
        throw PatchException("$PATCH: the attribution in $owner changes before its ticker label is built")
    }
    val live = RegisterLiveness.of(method).liveInto(at)
    val held = setOfNotNull(label, attribution, story)
    val written = (at..init).flatMap { code[it].written() }.toSet()
    val keep = (0 until minOf(256, method.implementation!!.registerCount))
        .firstOrNull { it !in live && it !in held && it !in written }
        ?: throw PatchException("$PATCH: no spare register in $owner to keep the sound label")
    val spare = (0 until minOf(16, method.implementation!!.registerCount))
        .filter { it !in live && it !in held && it != keep }.iterator()
    fun low(register: Int) = if (register < 16) register else if (spare.hasNext()) spare.next() else
        throw PatchException("$PATCH: no spare register below v16 in $owner for the sound label")
    val labelLow = low(label)
    val attributionLow = low(attribution)
    val storyLow = story?.let(::low)
    val hook = buildString {
        appendLine("move-object/from16 v$keep, v$label")
        if (labelLow != label) appendLine("move-object/from16 v$labelLow, v$label")
        if (attributionLow != attribution) appendLine("move-object/from16 v$attributionLow, v$attribution")
        if (storyLow != null && storyLow != story) appendLine("move-object/from16 v$storyLow, v$story")
        if (storyLow != null) {
            appendLine("invoke-static { v$labelLow, v$attributionLow, v$storyLow }, $SOUND_LABEL_TEXT")
        } else {
            appendLine("invoke-static { v$labelLow, v$attributionLow }, $SOUND_TICKER_TEXT")
        }
        appendLine("move-result-object v$labelLow")
        if (labelLow != label) appendLine("move-object/from16 v$label, v$labelLow")
    }
    // The later insert goes in first, so the earlier index still holds.
    method.addInstructions(init + 1, "move-object/from16 v$label, v$keep")
    method.addInstructions(at, hook)
}

/** The register [method]'s parameter of [type] arrives in, or null unless one parameter is of it. */
private fun parameterRegister(method: Method, type: String): Int? {
    val params = method.parameterTypes.map(CharSequence::toString)
    val index = params.indexOf(type).takeIf { it >= 0 && params.lastIndexOf(type) == it } ?: return null
    val sizes = params.map { if (it == "J" || it == "D") 2 else 1 }
    return method.implementation!!.registerCount - sizes.sum() + sizes.take(index).sum()
}

/**
 * The methods in [helper]'s class, other than [helper] itself, that word one label with it, build a
 * [soundTicker] from it and take a story of [storyType]: the soundtrack's own ticker label (#496).
 */
internal fun soundTickerHelpers(methods: Iterable<Method>, helper: MethodReference, storyType: String): List<Method> =
    methods.filter { method ->
        val code = method.code
        val calls = code.indices.filter { ((code[it] as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == helper.toString() }
        method.name != helper.name && calls.size == 1 && code.getOrNull(calls.single() + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT &&
            soundTicker(method, calls.single()) != null && parameterRegister(method, storyType) != null
    }

/**
 * Puts the post date in front of a silent reel's sound label (#496): in the reel footer's ticker
 * label for each attribution, and in the soundtrack's own ticker label built when that comes up
 * empty. The extension leaves every other attribution's label as it was.
 */
internal fun BytecodePatchContext.dateSoundLabels(storyType: String) {
    val footer = reelFooter()
    val owner = footer.definingClass
    val call = subtitleLabelCall(footer, storyType)
    if (call < 0) throw PatchException("$PATCH: the reel footer in $owner doesn't word its labels in one call")
    val helper = (footer.code[call] as ReferenceInstruction).reference as MethodReference
    val ticker = soundTicker(footer, call)
        ?: throw PatchException("$PATCH: the reel footer in $owner builds no ticker label from its words")
    val story = audioLineStory(footer, ticker.first, storyType)
        ?: throw PatchException("$PATCH: the reel footer in $owner holds no story of $storyType where it builds a label")
    dateSoundTicker(footer, call, ticker, story)
    val helpers = soundTickerHelpers(classDefByOrNull(helper.definingClass)?.methods ?: emptyList(), helper, storyType)
    val soundtrack = helpers.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one soundtrack ticker label beside ${helper.definingClass}->${helper.name}, found ${helpers.size}",
    )
    val method = mutableClassDefBy(helper.definingClass).methods.single {
        it.name == soundtrack.name && it.parameterTypes.map(CharSequence::toString) == soundtrack.parameterTypes.map(CharSequence::toString)
    }
    val worded = method.code.indices.single { ((method.code[it] as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == helper.toString() }
    dateSoundTicker(method, worded, soundTicker(method, worded)!!, null)
    val kept = parameterRegister(method, storyType)!!
    method.addInstructions(0, "invoke-static/range { v$kept .. v$kept }, $SOUND_STORY")
}
