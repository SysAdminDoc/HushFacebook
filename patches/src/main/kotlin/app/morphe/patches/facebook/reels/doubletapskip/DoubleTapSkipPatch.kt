/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.doubletapskip

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.media.taptoplay.MOTION_EVENT
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.shared.MOBILE_CONFIG
import app.morphe.patches.facebook.shared.readsMobileConfig
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.util.EnumSet

/*
 * Facebook's own double-tap skip on reels and videos, read from 584 (#225, 2026-10-24). The
 * obfuscated names here are for reviewers; the code never writes one down.
 *
 * The gesture view over a reel or video player (GestureReactionComponent's view, see
 * TurnOffDoubleTapLikePatch) hands each double tap to the player's interceptor before its heart
 * and like. One interceptor is a Kotlin lambda whose captured values keep their names:
 * $edgeWidthPercent, $skipDuration and $videoDurationInMs among them (584 LX/8ab;). Its
 * invoke(Object, Object) takes the MotionEvent and the view. A double tap on the left or right
 * edgeWidthPercent of the view, on a player that isn't an ad, asks one MobileConfig flag:
 *
 *     const-wide vId, <flag>
 *     check-cast vConfig, MobileConfigUnsafeContext
 *     invoke-interface {vConfig, vId, vId+1}, MobileConfigUnsafeContext->B61(J)Z
 *     move-result vOn
 *     if-eqz vOn, :not_handled
 *
 * With it on, the lambda seeks back on the left and forward on the right by skipDuration, shows
 * the player's skip overlay and takes the tap, so no heart or like follows. The component that
 * builds the lambda reads the same flag to pick the skip and the edge: 10 seconds on a video of 30
 * seconds or more and 5 on a shorter one, and an edge of a fifth of the player unless another
 * MobileConfig value gives one (584 LX/8bK;->A0l). One more player builder reads it too (584
 * LX/8XQ;->A00). Most accounts don't get the flag, so a double tap on the edge likes the video.
 *
 * The patch takes the flag's id from the lambda's one read, finds every read of that id in the
 * classes naming MobileConfigUnsafeContext, and hands each answer to the extension, which says yes
 * while the switch is on. Every load of the id there has to be a read the patch knows, and at
 * least one has to sit outside the lambda, or it refuses before it changes anything.
 */

internal const val SKIP_PATCH = "Double tap to skip"

internal const val SKIP_FLAG_CALL = "$EXTENSION_PACKAGE/reels/DoubleTapSkip;->flag(Z)Z"

private const val OBJECT = "Ljava/lang/Object;"

/** Captured values of the double-tap skip lambda, by name and type. Kotlin keeps these names. */
internal val SKIP_FIELDS = mapOf("\$edgeWidthPercent" to "F", "\$skipDuration" to "I", "\$videoDurationInMs" to "J")

/** The most instructions from the id's load to the read taking it: the config's cast between them. */
private const val READ_REACH = 3

private val WIDE_CONSTS = EnumSet.of(Opcode.CONST_WIDE, Opcode.CONST_WIDE_32, Opcode.CONST_WIDE_16, Opcode.CONST_WIDE_HIGH16)

private fun refuse(detail: String): Nothing = throw PatchException("$SKIP_PATCH: $detail")

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.wideLiteral(): Long? = if (opcode in WIDE_CONSTS) (this as WideLiteralInstruction).wideLiteral else null

/** The registers a call passes, in order. */
private fun Instruction.registers(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

/** The lambda's invoke: invoke(Object, Object)Object with code that casts its first argument to a MotionEvent. */
internal fun skipInvoke(classDef: ClassDef): Method? = classDef.methods.singleOrNull {
    it.name == "invoke" && it.returnType == OBJECT && it.parameterTypes.map(CharSequence::toString) == listOf(OBJECT, OBJECT) &&
        it.code().any { instruction ->
            instruction.opcode == Opcode.CHECK_CAST &&
                ((instruction as ReferenceInstruction).reference as TypeReference).type == MOTION_EVENT
        }
}

/** Whether [classDef] is the double-tap skip lambda: it holds [SKIP_FIELDS] and a [skipInvoke]. */
internal fun isDoubleTapSkip(classDef: ClassDef): Boolean =
    SKIP_FIELDS.all { (name, type) -> classDef.fields.any { it.name == name && it.type == type } } && skipInvoke(classDef) != null

/** One read of a MobileConfig flag: where its id loads and where the answer lands. */
internal class FlagRead(val method: Method, val load: Int, val result: Int, val register: Int)

/**
 * The read that takes the id [code] loads at [load]: a MobileConfig read answering a boolean
 * within [READ_REACH] instructions, passing that id's register pair last, its move-result right
 * after. Null when there's none.
 */
private fun readAt(method: Method, code: List<Instruction>, load: Int): FlagRead? {
    val id = (code[load] as OneRegisterInstruction).registerA
    val ask = (load + 1..minOf(load + READ_REACH, code.size - 2)).firstOrNull { readsMobileConfig(code[it], "Z") } ?: return null
    if (code[ask].registers().takeLast(2) != listOf(id, id + 1)) return null
    val result = code[ask + 1]
    if (result.opcode != Opcode.MOVE_RESULT) return null
    return FlagRead(method, load, ask + 1, (result as OneRegisterInstruction).registerA)
}

/** Where [method] loads [flag]. Read in one pass with nothing copied: every MobileConfig reader is asked (#216). */
internal fun loadsOf(method: Method, flag: Long): List<Int> {
    val loads = mutableListOf<Int>()
    method.implementation?.instructions?.forEachIndexed { at, instruction ->
        if (instruction.opcode in WIDE_CONSTS && (instruction as WideLiteralInstruction).wideLiteral == flag) loads += at
    }
    return loads
}

/** [method]'s reads of [flag], or a refusal when one of its loads of [flag] isn't a read the patch knows. */
internal fun readsOf(method: Method, flag: Long): List<FlagRead> {
    val loads = loadsOf(method, flag)
    if (loads.isEmpty()) return emptyList()
    val code = method.code()
    return loads.map { load ->
        readAt(method, code, load) ?: refuse("${method.definingClass}->${method.name} loads the skip flag at $load without reading it")
    }
}

/** The skip flag's id: what the lambda's one MobileConfig boolean read asks. */
internal fun skipFlag(lambda: ClassDef): Long {
    val invoke = skipInvoke(lambda) ?: refuse("${lambda.type} has no invoke taking a MotionEvent")
    val code = invoke.code()
    val reads = code.indices.mapNotNull { at -> if (code[at].wideLiteral() != null) readAt(invoke, code, at) else null }
    val read = reads.singleOrNull() ?: refuse("expected one MobileConfig read in ${lambda.type}->invoke, found ${reads.size}")
    return code[read.load].wideLiteral()!!
}

/** Every read of the skip flag, the lambda's first, found before anything changes. */
internal fun BytecodePatchContext.findSkipFlagReads(): List<FlagRead> {
    val lambdas = mutableListOf<ClassDef>()
    classDefForEachReferencing(names = SKIP_FIELDS.keys) { classDef ->
        if (!classDef.type.startsWith(EXTENSION_CLASSES) && isDoubleTapSkip(classDef)) lambdas += classDef
    }
    val lambda = lambdas.singleOrNull()
        ?: refuse("expected one lambda holding ${SKIP_FIELDS.keys.joinToString()}, found " +
            lambdas.joinToString { it.type }.ifEmpty { "none" })
    val flag = skipFlag(lambda)

    val reads = mutableListOf<FlagRead>()
    classDefForEachReferencing(types = listOf(MOBILE_CONFIG)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        classDef.methods.forEach { method -> reads += readsOf(method, flag) }
    }
    val inLambda = reads.count { it.method.definingClass == lambda.type }
    if (inLambda != 1) refuse("expected the skip flag read once in ${lambda.type}, found $inLambda")
    if (reads.size < 2) refuse("only ${lambda.type} reads the skip flag, so no player would set up its skip")
    return reads.sortedBy { if (it.method.definingClass == lambda.type) 0 else 1 }
}

/** Hands each read's answer to the extension, the later ones in a method first so the earlier indices still hold. */
internal fun BytecodePatchContext.askAfterSkipFlagReads(reads: List<FlagRead>) {
    for (read in reads.sortedByDescending { it.result }) {
        mutableClassDefBy(read.method.definingClass).findMutableMethodOf(read.method).addInstructions(
            read.result + 1,
            """
                invoke-static/range { v${read.register} .. v${read.register} }, $SKIP_FLAG_CALL
                move-result v${read.register}
            """,
        )
    }
}

/**
 * A double tap on the left or right edge of a reel or video skips back or forward. See the notes
 * above for where, and the extension's DoubleTapSkip for when.
 *
 * In Morphe Manager's default selection with the switch off, so nothing changes until it's turned on.
 */
@Suppress("unused")
val doubleTapSkipPatch = bytecodePatch(
    name = "Double tap to skip",
    description = "A double tap on the left or right edge of a reel or video skips back or forward 10 seconds, or 5 " +
        "on one under 30 seconds. Starts off. Turn it on in Hushfacebook settings > Reels and Watch.",
) {
    category("Reels")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        requireStatusMethod("doubleTapSkip")
        askAfterSkipFlagReads(findSkipFlagReads())
        enableStatus("doubleTapSkip")
    }
}
