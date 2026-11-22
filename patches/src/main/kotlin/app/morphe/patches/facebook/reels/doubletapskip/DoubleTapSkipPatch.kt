/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.doubletapskip

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.media.taptoplay.MOTION_EVENT
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.shared.MOBILE_CONFIG
import app.morphe.patches.facebook.shared.readsMobileConfig
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.executeBytecode
import app.morphe.util.findMutableMethodOf
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
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
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
 *
 * The skip seconds (#476, read from 587 on 2026-11-14): the lambda's invoke checks the view it's
 * handed against one type with instance-of, the player's gesture view (587 arm64 LX/0CXf;, armv7
 * LX/0CX8;, a FrameLayout), and keeps it in a static WeakReference for the overlay. That view holds
 * one TextView, the "+5s" or "-10s" label. The overlay's lambda arm (587 LX/0On1;->invoke, a merged
 * lambda) reads the label, makes it the first time (setVisibility(GONE), addView, then stores it),
 * sets its text, places it and calls setVisibility(VISIBLE) before the scale animation and the hide
 * 500 ms later. The gesture view's unbind writes null there and shows nothing. The patch swaps each
 * setVisibility on the label, from its read of the field to the next write of that register, for
 * the extension's label(View, int), which passes Facebook's visibility through unless the switch is
 * on. When the label can't be found, it warns and the skip still works.
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

internal const val SKIP_LABEL_CALL = "$EXTENSION_PACKAGE/reels/DoubleTapSkip;->label(Landroid/view/View;I)V"

private const val VIEW = "Landroid/view/View;"

private const val TEXT_VIEW = "Landroid/widget/TextView;"

/** One setVisibility on the skip label: where it is and the registers it passes, label first. */
internal class LabelShow(val method: Method, val index: Int, val registers: List<Int>, val range: Boolean)

private fun Instruction.isField(field: FieldReference): Boolean {
    val reference = (this as? ReferenceInstruction)?.reference as? FieldReference ?: return false
    return reference.definingClass == field.definingClass && reference.name == field.name && reference.type == field.type
}

/** A View.setVisibility(int) call, named through View or TextView. */
private fun Instruction.isSetVisibility(): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return false
    val call = (this as ReferenceInstruction).reference as? MethodReference ?: return false
    return call.name == "setVisibility" && call.returnType == "V" && call.definingClass in setOf(VIEW, TEXT_VIEW) &&
        call.parameterTypes.map(CharSequence::toString) == listOf("I")
}

/** Whether this puts something new in [register]. Making the label (new-instance TextView) doesn't count. */
private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister() || this !is OneRegisterInstruction) return false
    if (registerA != register && !(opcode.setsWideRegister() && registerA + 1 == register)) return false
    return !(opcode == Opcode.NEW_INSTANCE && ((this as ReferenceInstruction).reference as TypeReference).type == TEXT_VIEW)
}

/**
 * The skip label: the one TextView field of the one type [invoke] (the lambda's) checks with
 * instance-of, the player's gesture view.
 */
internal fun BytecodePatchContext.skipLabelField(invoke: Method): FieldReference {
    val types = invoke.code().filter { it.opcode == Opcode.INSTANCE_OF }
        .map { ((it as ReferenceInstruction).reference as TypeReference).type }.distinct()
    val view = types.singleOrNull()
        ?: refuse("expected one instance-of in ${invoke.definingClass}->invoke, found ${types.ifEmpty { listOf("none") }.joinToString()}")
    val gestureView = classDefByOrNull(view) ?: refuse("$view, the skip lambda's view, isn't in the app")
    val labels = gestureView.fields.filter { it.type == TEXT_VIEW && !AccessFlags.STATIC.isSet(it.accessFlags) }
    return labels.singleOrNull() ?: refuse("expected one TextView in $view, found ${labels.size}")
}

/**
 * The setVisibility calls on [label] in [method]: for each write of the field, from the read of
 * the field into the same register (when there's one) and from the write itself, to the next
 * instruction that puts something else there, can't fall through or ends the method.
 *
 * Walking from the write too covers a read that's null-checked first: 588's Android 9 build
 * (`LX/0p28;->invoke`) reads the label, makes one into the same register when it's null (which
 * ends the walk from the read), stores it, and only shows it after the store.
 */
internal fun labelShowsIn(method: Method, label: FieldReference): List<LabelShow> {
    val code = method.code()
    val shows = mutableListOf<LabelShow>()
    code.forEachIndexed { put, instruction ->
        if (instruction.opcode != Opcode.IPUT_OBJECT || !instruction.isField(label)) return@forEachIndexed
        val view = (instruction as TwoRegisterInstruction).registerA
        val read = (put - 1 downTo 0).firstOrNull {
            code[it].opcode == Opcode.IGET_OBJECT && code[it].isField(label) && (code[it] as OneRegisterInstruction).registerA == view
        }
        for (start in listOfNotNull(read, put)) {
            for (at in start + 1 until code.size) {
                val next = code[at]
                if (next.isSetVisibility() && next.registers().firstOrNull() == view) {
                    shows += LabelShow(method, at, next.registers(), next.opcode == Opcode.INVOKE_VIRTUAL_RANGE)
                }
                if (!next.opcode.canContinue() || next.writes(view)) break
            }
        }
    }
    return shows.distinctBy { it.index }
}

/** Every setVisibility on the skip label outside the extension, found before anything changes. */
internal fun BytecodePatchContext.findSkipLabelShows(invoke: Method): List<LabelShow> {
    val label = skipLabelField(invoke)
    val shows = mutableListOf<LabelShow>()
    classDefForEachReferencing(types = listOf(label.definingClass)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        classDef.methods.forEach { method -> shows += labelShowsIn(method, label) }
    }
    if (shows.isEmpty()) refuse("nothing shows ${label.definingClass}'s TextView")
    return shows
}

/** Hands each setVisibility on the label to the extension. Same size, so no index moves. */
internal fun BytecodePatchContext.askBeforeShowingSkipLabel(shows: List<LabelShow>) {
    for (show in shows) {
        val (view, visibility) = show.registers
        val call = if (show.range) "invoke-static/range { v$view .. v$visibility }, $SKIP_LABEL_CALL"
        else "invoke-static { v$view, v$visibility }, $SKIP_LABEL_CALL"
        mutableClassDefBy(show.method.definingClass).findMutableMethodOf(show.method).replaceInstruction(show.index, call)
    }
}

/**
 * A double tap on the left or right edge of a reel or video skips back or forward. See the notes
 * above for where, and the extension's DoubleTapSkip for when.
 *
 * In Morphe Manager's default selection with the switch off, so nothing changes until it's turned on.
 */
@Suppress("unused")
val doubleTapSkipPatch = patch(
    name = "Double tap to skip",
    description = "A double tap on the left or right edge of a reel or video skips back or forward 10 seconds, or 5 " +
        "on one under 30 seconds. Starts off. Turn it on in Hushfacebook settings > Reels and Watch. Hide the skip " +
        "seconds there, also off, keeps the +5s or -10s off the screen while the skip still works.",
) {
    category("Reels")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        requireStatusMethod("doubleTapSkip")
        val reads = findSkipFlagReads()
        val labelShows = try {
            findSkipLabelShows(reads.first().method)
        } catch (exception: PatchException) {
            patchLog.warning("${exception.message}, so a skip still shows its seconds.")
            emptyList()
        }
        // The label calls keep their size, so the reads' indices still hold after them.
        askBeforeShowingSkipLabel(labelShows)
        askAfterSkipFlagReads(reads)
        enableStatus("doubleTapSkip")
    }
}
