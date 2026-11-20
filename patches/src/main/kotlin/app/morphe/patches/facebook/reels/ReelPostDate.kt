/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.methodsNaming
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.shared.MOBILE_CONFIG
import app.morphe.patches.facebook.shared.readsMobileConfig
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.namedRegisters
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
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
internal fun creationTimeRead(method: Method): Int = creationTimeReads(method).singleOrNull() ?: -1

/** Each place [method] reads [CREATION_TIME] through TreeJNI.getTimeValue. */
internal fun creationTimeReads(method: Method): List<Int> {
    val code = method.code
    return code.indices.filter { at ->
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
 * A method with no such field takes the story as its one parameter of [storyType] instead (588's
 * Android 9 footer, LX/093B;->A02, copies it down once, `move-object/from16 v12, v161`): then the
 * lowest copy of it that is the only write of its register reaching [at], else the parameter's own
 * register while nothing has written it. Null otherwise.
 */
internal fun audioLineStory(method: Method, at: Int, storyType: String): Int? {
    val code = method.code
    val reads = code.indices.filter { index ->
        val field = (code[index] as? ReferenceInstruction)?.reference as? FieldReference ?: return@filter false
        code[index].opcode == Opcode.IGET_OBJECT && field.definingClass == method.definingClass && field.type == storyType
    }
    if (reads.isEmpty()) return parameterStory(method, at, storyType)
    val read = reads.singleOrNull()?.takeIf { it < at } ?: return null
    val register = (code[read] as TwoRegisterInstruction).registerA
    return register.takeIf { method.writersReaching(at, register) == listOf(read) }
}

private fun parameterStory(method: Method, at: Int, storyType: String): Int? {
    val code = method.code
    val parameter = parameterRegister(method, storyType) ?: return null
    val copies = code.indices.filter { index ->
        index < at && code[index].opcode in OBJECT_MOVES && (code[index] as TwoRegisterInstruction).registerB == parameter &&
            method.writersReaching(index, parameter) == listOf(-1)
    }.filter { index -> method.writersReaching(at, (code[index] as TwoRegisterInstruction).registerA) == listOf(index) }
    return copies.minOfOrNull { (code[it] as TwoRegisterInstruction).registerA }
        ?: parameter.takeIf { method.writersReaching(at, it) == listOf(-1) }
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
    handTextAndStory(method, at, line, story, AUDIO_LINE_TEXT)
}

/**
 * Puts a call of [hook], handed the String in [text] and the story in [story], in front of [at] in
 * [method], its answer taking the String's place. The registers are copied down below v16 through
 * ones nothing reads there when they sit higher.
 */
private fun handTextAndStory(method: MutableMethod, at: Int, text: Int, story: Int, hook: String) {
    val live = RegisterLiveness.of(method).liveInto(at)
    val spare = (0 until minOf(16, method.implementation!!.registerCount)).filter { it !in live && it != text && it != story }
    val textLow = if (text < 16) text else spare.getOrNull(0)
    val storyLow = if (story < 16) story else spare.getOrNull(if (text < 16) 0 else 1)
    if (textLow == null || storyLow == null) {
        throw PatchException("$PATCH: no spare register below v16 in ${method.definingClass} for the post date")
    }
    val code = buildString {
        if (textLow != text) appendLine("move-object/from16 v$textLow, v$text")
        if (storyLow != story) appendLine("move-object/from16 v$storyLow, v$story")
        appendLine("invoke-static { v$textLow, v$storyLow }, $hook")
        appendLine("move-result-object v$textLow")
        if (textLow != text) appendLine("move-object/from16 v$text, v$textLow")
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
 * "New for you" under a reel's author (#520, read from 588, 2026-11-19, arm64 and armeabi-v7a).
 *
 * - The footer builder above slots its New-for-you item, the reason Facebook picked the reel, in at
 *   a place the server names: a long read from MobileConfigUnsafeContext, made an int, less one,
 *   clamped to the list's size by one static (III)I call and handed to List.add(int, Object). It does
 *   that twice (arm64 LX/08yM;->A1B, v7 LX/08yP;->A1B): once into the rows the date row leads, once
 *   into the ticker labels the song or sound label sits in. The server's place is the top, so the
 *   item showed first and the date only after the line turned over.
 * - The builder's other positioned adds use a place it counts itself, never one read from the server.
 * - The hook swaps both adds for one extension call taking the list, the place and the item, which
 *   leaves the item out while the switch is on and puts it after the date while the date is on.
 * - 588's Android 9 build (LX/093B;->A02) moves the read and the cast to an int into a static
 *   (Object, long) int helper, `LX/01CA;->A03`, that casts its Object to the config and asks it.
 *   Only [classOf] sees that body, so without it that form doesn't count.
 */
internal const val SLOT_NEW_FOR_YOU = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->slotNewForYou(Ljava/util/List;ILjava/lang/Object;)V"

/** The List.add(int, Object) calls in [method] whose place is a server long clamped to the list. */
internal fun newForYouInserts(method: Method, classOf: ((String) -> ClassDef?)? = null): List<Int> {
    val code = method.code
    val adds = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE)
    val statics = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)
    val configs = setOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE)
    return code.indices.filter { at ->
        val add = (code[at] as? ReferenceInstruction)?.reference as? MethodReference ?: return@filter false
        if (code[at].opcode !in adds || add.name != "add" || add.returnType != "V" ||
            !add.definingClass.startsWith("Ljava/util/") || add.params() != listOf("I", "Ljava/lang/Object;")
        ) return@filter false
        val place = code[at].namedRegisters().getOrNull(1) ?: return@filter false
        val clamped = method.writersReaching(at, place).singleOrNull() ?: return@filter false
        val clamp = (code.getOrNull(clamped - 1) as? ReferenceInstruction)?.reference as? MethodReference
        if (code[clamped].opcode != Opcode.MOVE_RESULT || code[clamped - 1].opcode !in statics || clamp == null ||
            clamp.returnType != "I" || clamp.params() != listOf("I", "I", "I")
        ) return@filter false
        (maxOf(0, clamped - 17) until clamped - 1).any { read ->
            val config = (code[read] as? ReferenceInstruction)?.reference as? MethodReference
            code[read].opcode in configs && config != null && config.definingClass == MOBILE_CONFIG &&
                config.returnType == "J" && config.params() == listOf("J") ||
                classOf != null && code[read].opcode in statics && readsMobileConfig(code[read], "I", classOf)
        }
    }
}

/** Hands the reel footer's two New-for-you inserts to the extension. */
internal fun BytecodePatchContext.slotNewForYou() {
    val method = reelFooter()
    val sites = newForYouInserts(method) { classDefByOrNull(it) }
    if (sites.size != 2) {
        throw PatchException("$PATCH: expected the reel footer to slot New for you in at a server place twice, found ${sites.size}")
    }
    sites.forEach { at ->
        val add = method.code[at]
        val registers = add.namedRegisters()
        method.replaceInstruction(
            at,
            if (add is RegisterRangeInstruction) {
                "invoke-static/range { v${registers.first()} .. v${registers.last()} }, $SLOT_NEW_FOR_YOU"
            } else {
                "invoke-static { ${registers.joinToString { "v$it" }} }, $SLOT_NEW_FOR_YOU"
            },
        )
    }
}

/*
 * The date on a group's video in the full-screen viewer (#520, read from 588, 2026-11-19, arm64 and
 * armeabi-v7a).
 *
 * - A group's video doesn't use the reel footer above. Its author row is a component of its own
 *   (arm64 LX/0X81, v7 LX/0X82), whose class initializer names it GROUP_AUTHOR, and its render shows
 *   the group's name over the poster's with the privacy icon after, and no date line at all, so
 *   the switch's hooks never reached it.
 * - The render keeps the video's story in a field of its own, the type the post date helper takes
 *   second, and copies it into a high register at the top. The poster's name is one static call of
 *   one object answering a String, and that String is the text of the row's name spans, each a
 *   constructor of (something, CharSequence, Integer). One span is built on each of two ways, and
 *   the row's accessibility label reads the same String.
 * - The hook hands the name and the story to the extension right after the call, and its answer,
 *   the name with the date after it, takes the name's place on every way.
 */
internal const val GROUP_AUTHOR = "FbShortsViewerVideoGroupAuthorKComponent"
internal const val GROUP_POSTER_TEXT =
    "$EXTENSION_PACKAGE/reels/ReelDeclutter;->groupPosterText(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;"
private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"
private val OBJECT_MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

/** Whether [instruction] builds a text span: a constructor of (something, CharSequence, Integer). */
private fun buildsNameSpan(instruction: Instruction): Boolean {
    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    val params = reference.params()
    return instruction.opcode == Opcode.INVOKE_DIRECT && reference.name == "<init>" && params.size == 3 &&
        params[0].startsWith("L") && params[1] == CHAR_SEQUENCE && params[2] == "Ljava/lang/Integer;"
}

/**
 * Where [method], a group video's author row, gets the poster's name: the move-result-object of the
 * one static call of one object answering a String that is the text of every name span. -1 unless
 * the spans all take their text from that one call.
 */
internal fun groupPosterName(method: Method): Int {
    val code = method.code
    val spans = code.indices.filter { buildsNameSpan(code[it]) }
    if (spans.isEmpty()) return -1
    val names = spans.map { at ->
        val text = code[at].namedRegisters().getOrNull(2) ?: return -1
        method.writersReaching(at, text).singleOrNull() ?: return -1
    }.toSet()
    val name = names.singleOrNull()?.takeIf { it > 0 && code[it].opcode == Opcode.MOVE_RESULT_OBJECT } ?: return -1
    val call = code[name - 1]
    val reference = (call as? ReferenceInstruction)?.reference as? MethodReference ?: return -1
    val static = call.opcode == Opcode.INVOKE_STATIC || call.opcode == Opcode.INVOKE_STATIC_RANGE
    return if (static && reference.returnType == STRING && reference.parameterTypes.size == 1) name else -1
}

/**
 * The register holding the video's story where code put at [at] in [method] runs: the one read of
 * the method's own field of [storyType], or a copy of it, when that's the only write of the register
 * reaching [at]. The lowest such register, or null when none holds it.
 */
internal fun storyHeld(method: Method, at: Int, storyType: String): Int? {
    val code = method.code
    val read = code.indices.filter { index ->
        val field = (code[index] as? ReferenceInstruction)?.reference as? FieldReference ?: return@filter false
        code[index].opcode == Opcode.IGET_OBJECT && field.definingClass == method.definingClass && field.type == storyType
    }.singleOrNull()?.takeIf { it < at } ?: return null
    val held = (code[read] as TwoRegisterInstruction).registerA
    val copies = code.indices.filter { index ->
        code[index].opcode in OBJECT_MOVES && (code[index] as TwoRegisterInstruction).registerB == held &&
            method.writersReaching(index, held) == listOf(read)
    }
    val candidates = mapOf(held to read) + copies.associateBy { (code[it] as TwoRegisterInstruction).registerA }
    return candidates.filter { (register, writer) -> method.writersReaching(at, register) == listOf(writer) }.keys.minOrNull()
}

/** Whether anything but the instruction before [at] in [method] can go on to it: a branch, a switch arm or a handler. */
private fun landedOn(method: Method, at: Int): Boolean {
    val flow = ControlFlow.of(method)
    return flow.normal.indices.any { from -> from != at - 1 && at in flow.normal[from] } ||
        flow.exceptional.any { at in it }
}

/** The render of a group video's author row: the one method of a [GROUP_AUTHOR] class whose name spans read one call. */
private fun BytecodePatchContext.groupAuthorRender(): MutableMethod {
    val renders = classDefByStrings(GROUP_AUTHOR, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .flatMap { classDef -> classDef.methods.filter { groupPosterName(it) >= 0 }.map { classDef.type to it } }
    val (owner, render) = renders.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one \"$GROUP_AUTHOR\" render that names its poster in one call, found ${renders.size}",
    )
    return mutableClassDefBy(owner).methods.single {
        it.name == render.name && it.parameterTypes.map(CharSequence::toString) == render.parameterTypes.map(CharSequence::toString)
    }
}

/**
 * Puts the post date after the poster's name on a group's video, in the render [groupAuthorRender]
 * finds: the name and the video's story, of [storyType], go to the extension right after the name's
 * call, and its answer takes the name's place.
 */
internal fun BytecodePatchContext.dateGroupPoster(storyType: String) {
    val method = groupAuthorRender()
    val owner = method.definingClass
    val name = groupPosterName(method)
    val at = name + 1
    if (landedOn(method, at)) throw PatchException("$PATCH: something in $owner branches onto the poster's name")
    val register = (method.code[name] as OneRegisterInstruction).registerA
    val story = storyHeld(method, at, storyType)
        ?: throw PatchException("$PATCH: $owner holds no story of $storyType where it names the poster")
    handTextAndStory(method, at, register, story, GROUP_POSTER_TEXT)
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
 * - 588's footer (arm64 LX/08yM;->A1B, v7 LX/08yP;->A1B, read 2026-11-18) puts the attribution's type
 *   name in the attribution's own register before the ticker label, so the hook carries the
 *   attribution there in a register nothing is using.
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
 * A register to carry the attribution from the wording at [call] to the ticker label at [at], whose
 * constructor runs at [init]: nothing reads it from right after the wording on, and nothing writes
 * it up to that constructor. Refuses unless the code between runs straight down, nothing jumping in,
 * so the copy always happens on the way to the ticker.
 */
private fun attributionCarrier(method: Method, call: Int, at: Int, init: Int, label: Int, attribution: Int, story: Int?): Int {
    val owner = method.definingClass
    val code = method.code
    val from = call + 2
    val flow = ControlFlow.of(method)
    val straight = label != attribution && (from until at).all { (it + 1) in flow.normal[it] } &&
        code.indices.none { source -> (flow.normal[source] + flow.exceptional[source]).any { it in from..at && it != source + 1 } }
    if (!straight) throw PatchException("$PATCH: the attribution in $owner changes before its ticker label is built")
    val live = RegisterLiveness.of(method).liveInto(from)
    val written = (from..init).flatMap { code[it].written() }.toSet()
    return (0 until minOf(256, method.implementation!!.registerCount)).firstOrNull {
        it !in live && it !in written && it != label && it != attribution && it != story
    } ?: throw PatchException("$PATCH: no spare register in $owner to carry the attribution")
}

/**
 * Hands the label [method] words at [call] to the extension right before the ticker label built from
 * it, [ticker], with its attribution and, in [story], the reel's story, or with null for the one
 * [SOUND_STORY] kept. Facebook's own label goes back right after the ticker's constructor, for the
 * row label and anything else built from it. Registers at v16 and up are copied down through ones
 * nothing reads there, and an attribution whose register is reused first comes in a copy made right
 * after the wording ([attributionCarrier]).
 */
private fun dateSoundTicker(method: MutableMethod, call: Int, ticker: Pair<Int, Int>, story: Int?) {
    val owner = method.definingClass
    val code = method.code
    val (at, init) = ticker
    val label = (code[call + 1] as OneRegisterInstruction).registerA
    val attribution = code[call].namedRegisters()[1]
    val carrier = if (method.writersReaching(at, attribution) == method.writersReaching(call, attribution)) {
        attribution
    } else {
        attributionCarrier(method, call, at, init, label, attribution, story)
    }
    val live = RegisterLiveness.of(method).liveInto(at)
    val held = setOfNotNull(label, carrier, story)
    val written = (at..init).flatMap { code[it].written() }.toSet()
    val keep = (0 until minOf(256, method.implementation!!.registerCount))
        .firstOrNull { it !in live && it !in held && it !in written }
        ?: throw PatchException("$PATCH: no spare register in $owner to keep the sound label")
    val spare = (0 until minOf(16, method.implementation!!.registerCount))
        .filter { it !in live && it !in held && it != keep }.iterator()
    fun low(register: Int) = if (register < 16) register else if (spare.hasNext()) spare.next() else
        throw PatchException("$PATCH: no spare register below v16 in $owner for the sound label")
    val labelLow = low(label)
    val attributionLow = low(carrier)
    val storyLow = story?.let(::low)
    val hook = buildString {
        appendLine("move-object/from16 v$keep, v$label")
        if (labelLow != label) appendLine("move-object/from16 v$labelLow, v$label")
        if (attributionLow != carrier) appendLine("move-object/from16 v$attributionLow, v$carrier")
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
    if (carrier != attribution) method.addInstructions(call + 2, "move-object/from16 v$carrier, v$attribution")
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

/*
 * The date kept in sight under a reel's author (#503, read from 588, 2026-11-19, arm64).
 *
 * - The Reels tab's song line is a chip (arm64 LX/09H2) built from the line's text and two flags
 *   last. The last one makes it a marquee that scrolls the text sideways, so the date in front of
 *   the song slid out of sight within a second. The hook hands that flag to the extension right
 *   before the chip's constructor, and the extension says no while the date is on.
 * - On a reel without a song the footer's builder hands the rows under the author, the date row
 *   first, to a view (arm64 LX/0aU0) of (FbUserSession, something, List, Function0, boolean,
 *   boolean) that flips through them when there are two or more, so the date gave way to the
 *   location or New for you after a few seconds. Each row the post date helper answers goes to the
 *   extension, and the rows go to it right before that view is built: while the date is on and one
 *   of those rows is among them, the view gets that row alone and holds still.
 */
internal const val AUDIO_LINE_SCROLLS = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->audioLineScrolls(I)Z"
internal const val FOOTER_DATE_ROW = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->footerDateRow(Ljava/lang/Object;)V"
internal const val FOOTER_ROWS = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->footerRows(Ljava/util/List;)Ljava/util/List;"
private const val ROWS = "Ljava/util/List;"
private val CONSTRUCTS = setOf(Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE)
private val STATICS = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)

/** The constructor [instruction] calls, or null when it calls none. */
private fun constructed(instruction: Instruction): MethodReference? {
    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    return reference.takeIf { instruction.opcode in CONSTRUCTS && it.name == "<init>" }
}

/**
 * Where [method], the Reels tab's audio line, builds its chip: the one constructor past
 * [audioLineJoin] whose last three parameters are a String and two booleans. -1 unless one.
 */
internal fun audioLineChip(method: Method): Int {
    val code = method.code
    val join = audioLineJoin(method)
    if (join < 0) return -1
    return code.indices.filter { at ->
        val params = constructed(code[at])?.params() ?: return@filter false
        at > join && params.size >= 3 && params.takeLast(3) == listOf(STRING, "Z", "Z")
    }.singleOrNull() ?: -1
}

/**
 * Hands the song chip's scrolling flag in [method], the Reels tab's audio line, to the extension
 * right before the chip is built. Refuses when the flag's register goes in twice, is read after
 * the constructor, or something branches onto the constructor.
 */
internal fun stillAudioLine(method: MutableMethod) {
    val owner = method.definingClass
    val chip = audioLineChip(method)
    if (chip < 0) throw PatchException("$PATCH: the audio line in $owner builds no chip of its line and two flags")
    val registers = method.code[chip].namedRegisters()
    val flag = registers.last()
    if (registers.count { it == flag } != 1 || flag in RegisterLiveness.of(method).liveInto(chip + 1) || landedOn(method, chip)) {
        throw PatchException("$PATCH: the song chip's scrolling flag in $owner can't be handed over where the chip is built")
    }
    method.addInstructions(
        chip,
        """
            invoke-static/range { v$flag .. v$flag }, $AUDIO_LINE_SCROLLS
            move-result v$flag
        """,
    )
}

/**
 * Where [method], the reel footer, builds the view that flips through the rows under the author:
 * the one constructor of (FbUserSession, something, List, Function0, boolean, boolean). -1 unless one.
 */
internal fun footerFlipper(method: Method): Int {
    val code = method.code
    return code.indices.filter { at ->
        val params = constructed(code[at])?.params() ?: return@filter false
        params.size == 6 && params[0] == USER_SESSION && params[2] == ROWS && params[3] == FUNCTION0 &&
            params[4] == "Z" && params[5] == "Z"
    }.singleOrNull() ?: -1
}

/**
 * Where [method] gets a row from the post date helper: the move-result-object after each static
 * call whose method [isDate] says is that helper.
 */
internal fun footerDateRows(method: Method, isDate: (MethodReference) -> Boolean): List<Int> {
    val code = method.code
    return code.indices.filter { at ->
        val reference = (code[at] as? ReferenceInstruction)?.reference as? MethodReference ?: return@filter false
        val params = reference.params()
        code[at].opcode in STATICS && reference.returnType.startsWith("L") && params.size >= 3 &&
            params.first() == USER_SESSION && params.last() == "Z" &&
            code.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT && isDate(reference)
    }.map { it + 1 }
}

/**
 * Holds the line under a reel's author on the date while it shows: each row the post date helper
 * answers in the reel footer goes to the extension, and the rows go to it right before the view
 * that flips through them is built, its answer taking their place.
 */
internal fun BytecodePatchContext.holdFooterOnDate() {
    val method = reelFooter()
    val owner = method.definingClass
    val flipper = footerFlipper(method)
    if (flipper < 0) throw PatchException("$PATCH: the reel footer in $owner builds no view that flips through its rows")
    val rows = footerDateRows(method) { reference ->
        classDefByOrNull(reference.definingClass)?.methods?.any {
            it.name == reference.name && it.parameterTypes.map(CharSequence::toString) == reference.params() && isReelPostDate(it)
        } == true
    }
    if (rows.isEmpty()) throw PatchException("$PATCH: the reel footer in $owner never asks the post date helper for its row")
    val list = method.code[flipper].namedRegisters()[3]
    // A row hook may sit where a branch lands: 588 jumps onto the instruction after the helper's
    // row from paths that never asked it, and a branch keeps landing on that instruction, past
    // the hook, so only a row the helper just answered is handed over.
    if (landedOn(method, flipper)) {
        throw PatchException("$PATCH: something in $owner branches onto where its rows would be handed over")
    }
    // From the bottom up, so the indices above still hold.
    val inserts = rows.map { it + 1 to "invoke-static/range { v${(method.code[it] as OneRegisterInstruction).registerA} .. " +
        "v${(method.code[it] as OneRegisterInstruction).registerA} }, $FOOTER_DATE_ROW" } +
        (flipper to "invoke-static/range { v$list .. v$list }, $FOOTER_ROWS\nmove-result-object v$list")
    inserts.sortedByDescending { it.first }.forEach { (at, code) -> method.addInstructions(at, code) }
}

/*
 * Facebook's own date after a reel's caption (#503, read from 588, 2026-11-19, arm64 and armeabi-v7a).
 *
 * - The caption's render (arm64 LX/09Og;->A10, the method naming [CAPTION_RENDER]) adds a row of
 *   the reel's creation_time below the caption, a new object of (FbUserSession, long) handed the
 *   time it just read. It does that at two places, each behind a server switch of its own: the same
 *   static (object, Function0, int) to boolean call the post date helper asks, a few instructions up.
 * - Accounts in that test saw the date twice with the post date on, "Jul 15, 2025" under the name
 *   and "July 15, 2025" after the caption. Each switch's answer goes to the extension, which says no
 *   while the date is on, so the date shows under the name only.
 */
internal const val CAPTION_DATE_SHOWS = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->captionDateShows(Z)Z"

/** The name a reel caption's render gives the text component it builds. */
private const val CAPTION_RENDER = "FbShortsViewerDescriptionComponentSpec_Text_Component_"

/** How far above its creation_time read a caption date's switch may sit. */
private const val CAPTION_GATE_REACH = 16

/**
 * The server switches in [method] that let a caption's date row in: for each [CREATION_TIME] read
 * whose time goes straight into a constructor of (FbUserSession, long), the [postDateGate]-shaped
 * call closest above it, at most [CAPTION_GATE_REACH] instructions up. One call index per read.
 */
internal fun captionDateGates(method: Method): List<Int> {
    val code = method.code
    return creationTimeReads(method).mapNotNull { read ->
        val time = (code.getOrNull(read + 1) as? OneRegisterInstruction)?.takeIf { it.opcode == Opcode.MOVE_RESULT_WIDE }?.registerA
            ?: return@mapNotNull null
        val dated = (read + 2..minOf(read + 6, code.lastIndex)).any { at ->
            val made = constructed(code[at]) ?: return@any false
            made.params() == listOf(USER_SESSION, "J") && code[at].namedRegisters().takeLast(2) == listOf(time, time + 1)
        }
        if (!dated) return@mapNotNull null
        (maxOf(0, read - CAPTION_GATE_REACH) until read).lastOrNull { at ->
            val reference = (code[at] as? ReferenceInstruction)?.reference as? MethodReference ?: return@lastOrNull false
            val params = reference.params()
            code[at].opcode in STATICS && reference.returnType == "Z" && params.size == 3 && params[0].startsWith("L") &&
                params[1] == FUNCTION0 && params[2] == "I" && code.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT
        }
    }.distinct()
}

/** Hands each server switch that lets a reel caption's own date row in to the extension. */
internal fun BytecodePatchContext.dropCaptionDate() {
    val renders = classDefByStrings(CAPTION_RENDER, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .flatMap { classDef -> classDef.methods.filter { captionDateGates(it).isNotEmpty() }.map { classDef.type to it } }
        // 588's Android 9 build dates the caption in a helper class (`LX/09TV;->A02`) that asks a string
        // table (`LX/0vfR;->A00(I)String`) for the render's name instead of loading it.
        .ifEmpty { methodsNaming(CAPTION_RENDER) { captionDateGates(it).isNotEmpty() }.map { it.definingClass to it } }
    val (owner, render) = renders.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one reel caption render that dates its caption behind a server switch, found ${renders.size}",
    )
    val method = mutableClassDefBy(owner).methods.single {
        it.name == render.name && it.parameterTypes.map(CharSequence::toString) == render.parameterTypes.map(CharSequence::toString)
    }
    val gates = captionDateGates(method)
    // A branch onto the instruction after the switch's move-result would land past the hook.
    if (gates.any { landedOn(method, it + 2) }) {
        throw PatchException("$PATCH: something in $owner branches onto where a caption date's switch would be handed over")
    }
    // From the bottom up, so the indices above still hold.
    gates.sortedDescending().forEach { gate ->
        val result = (method.code[gate + 1] as OneRegisterInstruction).registerA
        method.addInstructions(
            gate + 2,
            """
                invoke-static/range { v$result .. v$result }, $CAPTION_DATE_SHOWS
                move-result v$result
            """,
        )
    }
}
