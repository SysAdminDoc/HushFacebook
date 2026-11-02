/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.RegisterLiveness
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
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
