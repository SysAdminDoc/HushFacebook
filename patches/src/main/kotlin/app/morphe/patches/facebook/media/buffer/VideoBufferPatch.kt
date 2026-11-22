/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.buffer

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.freeLocalsAt
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.extension.requireParameterIntact
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.executeBytecode
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val PATCH = "Video buffer"
internal const val VIDEO_BUFFER = "$EXTENSION_PACKAGE/media/VideoBuffer;"
internal const val START_AT = "$VIDEO_BUFFER->startAt(JZ)J"
internal const val AHEAD_LOW = "$VIDEO_BUFFER->aheadLow(J)J"
internal const val AHEAD_HIGH = "$VIDEO_BUFFER->aheadHigh(JJ)J"

/** The trace HeroPlayer's own load control opens its keep-loading check with. */
internal const val HERO_LOADING = "shouldContinueLoading"

/** The warning ExoPlayer's DefaultLoadControl gives from its keep-loading check. */
internal const val EXO_LOADING = "Target buffer size reached with less than 500ms of buffered media data."

/** ExoPlayer's C.TIME_UNSET, the duration a start check sees for a video of unknown length. */
internal const val TIME_UNSET = Long.MIN_VALUE + 1

private val WIDE_CONSTANTS = setOf(Opcode.CONST_WIDE_16, Opcode.CONST_WIDE_32, Opcode.CONST_WIDE, Opcode.CONST_WIDE_HIGH16)

/**
 * Lets people pick how much of a video loads before it starts and while it plays (#526). Facebook
 * plays videos through two load controls, each asked over and over by its player: HeroPlayer's
 * own, which most videos get and which works its numbers out from its settings and the server's,
 * and ExoPlayer's DefaultLoadControl, which some get and which keeps fixed ones. Both answer the
 * same two questions with the same two methods, a start check (whether enough has loaded to start
 * or to play on after a stall) and a keep-loading check (whether to load more).
 *
 * Each start check settles on the time it waits for along a few ways, all of which meet before
 * the check caps that wait at half a short clip's length. The extension's VideoBuffer gets the
 * wait right there with whether it's after a stall ([askStart]), so the cap and everything after
 * it stay Facebook's. Each keep-loading check settles on a low mark, under which it always loads,
 * and a high mark, past which it stops; VideoBuffer gets both once they're final ([askAhead]).
 * Facebook's memory cap on what a player may hold still applies on top of all of it.
 *
 * Starts at Facebook's choice, which hands every number back as it came.
 */
@Suppress("unused")
val videoBufferPatch = patch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Video buffer",
    description = "Picks how much of a video loads before it starts and while it plays. Low starts videos sooner " +
        "and uses less data. Medium and Maximum wait a little longer to start and keep more loaded ahead, which can " +
        "help on a slow connection. Starts at Facebook's choice. Pick a size in Hushfacebook settings > Video and audio.",
) {
    category("Playback")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        // Every check is found and checked before any is changed. The fixture tests hold all four on
        // every declared build; on another one, a check of a new shape keeps Facebook's numbers and
        // the rest still take the choice, so a miss is a warning until nothing at all is found.
        val hooks = mutableListOf<Pair<Method, MutableMethod.() -> Unit>>()
        val missed = mutableListOf<String>()
        for (player in Player.entries) {
            val control = try {
                loadControl(player)
            } catch (exception: PatchException) {
                missed += exception.message.orEmpty()
                continue
            }
            try {
                val hook = startHook(control.start)
                hooks += hook.method to { askStart(hook) }
            } catch (exception: PatchException) {
                missed += exception.message.orEmpty()
            }
            try {
                val hook = player.ahead(control.loading)
                hooks += hook.method to { askAhead(hook) }
            } catch (exception: PatchException) {
                missed += exception.message.orEmpty()
            }
        }
        if (hooks.isEmpty()) throw PatchException(missed.joinToString("; "))
        missed.forEach { patchLog.warning("$it, so that check keeps Facebook's numbers.") }
        hooks.forEach { (method, apply) ->
            mutableClassDefBy(method.definingClass).methods.single { sameMethod(it, method) }.apply()
        }
        enableStatus("videoBuffer")
    }
}

/** The two load controls, each found by a string its keep-loading check carries. */
internal enum class Player(val label: String, val marker: String, val ahead: (Method) -> AheadHook) {
    HERO("HeroPlayer", HERO_LOADING, ::heroAhead),
    EXO("ExoPlayer", EXO_LOADING, ::exoAhead),
}

/** One player's load control: its keep-loading check [loading] and its start check [start]. */
internal class LoadControl(val loading: Method, val start: Method)

/**
 * A start check's hook at [at], where every way to the wait meets: [wait] (and the register past
 * it) holds the wait in microseconds, [parameters] the check's parameters object, whose
 * [rebuffering] says it's after a stall, and [scratch] is a register nothing reads there.
 */
internal class StartHook(
    val method: Method,
    val at: Int,
    val wait: Int,
    val parameters: Int,
    val rebuffering: FieldReference,
    val scratch: Int,
)

/**
 * A keep-loading check's hooks: at [lowAt], [low] (and the register past it) holds the final low
 * mark, and at [highAt] the final high mark is in [high]. The same index when both are final at
 * once. [kept] is the field the check stores its low mark in, which gets the answer too, on [self].
 */
internal class AheadHook(
    val method: Method,
    val lowAt: Int,
    val low: Int,
    val highAt: Int,
    val high: Int,
    val kept: FieldReference? = null,
    val self: Int = -1,
)

internal fun sameMethod(a: Method, b: Method) = a.name == b.name && a.returnType == b.returnType &&
    a.parameterTypes.map(CharSequence::toString) == b.parameterTypes.map(CharSequence::toString)

private fun loadsString(method: Method, string: String) = method.implementation?.instructions?.any {
    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
} == true

internal fun isTimeUnset(instruction: Instruction) =
    instruction.opcode == Opcode.CONST_WIDE && (instruction as WideLiteralInstruction).wideLiteral == TIME_UNSET

private fun callsMath(instruction: Instruction, name: String): Boolean {
    if (instruction.opcode != Opcode.INVOKE_STATIC) return false
    val called = (instruction as ReferenceInstruction).reference as MethodReference
    return called.definingClass == "Ljava/lang/Math;" && called.name == name && called.returnType == "J" &&
        called.parameterTypes.map(CharSequence::toString) == listOf("J", "J")
}

private fun compares(instruction: Instruction) = instruction.opcode == Opcode.CMP_LONG

/** Every instruction a branch, a switch or a handler lands on, as opposed to one only fallen into. */
internal fun jumpTargets(method: Method): Set<Int> {
    val flow = ControlFlow.of(method)
    val targets = mutableSetOf<Int>()
    for (from in flow.normal.indices) {
        flow.normal[from].filterTo(targets) { it != from + 1 }
        targets += flow.exceptional[from]
    }
    return targets
}

private fun where(method: Method) = "$PATCH: ${method.definingClass}->${method.name}"

/**
 * [player]'s load control: the one class outside the extension whose method loads its marker, the
 * keep-loading check, beside a sibling taking the same parameters that loads [TIME_UNSET], the
 * start check. Throws when there isn't exactly one such pair.
 */
internal fun BytecodePatchContext.loadControl(player: Player): LoadControl {
    val found = mutableListOf<LoadControl>()
    classDefForEachReferencing(strings = listOf(player.marker)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        for (loading in classDef.methods.filter { loadsString(it, player.marker) }) {
            val starts = classDef.methods.filter { other ->
                !sameMethod(other, loading) && other.returnType == "Z" &&
                    other.parameterTypes.map(CharSequence::toString) == loading.parameterTypes.map(CharSequence::toString) &&
                    other.implementation?.instructions?.any(::isTimeUnset) == true
            }
            // A class that only logs the marker has no start check beside it, and isn't a load control.
            if (starts.isEmpty()) continue
            val start = starts.singleOrNull()
                ?: throw PatchException("$PATCH: ${player.label}'s load control ${classDef.type} has ${starts.size} start checks")
            found += LoadControl(loading, start)
        }
    }
    return found.singleOrNull() ?: throw PatchException("$PATCH: found ${found.size} ${player.label} load controls")
}

/**
 * The start check's hook. The check reads the unknown-duration constant only where it caps the
 * wait at half a short clip, and every way to the wait meets right before that: the hook goes on
 * the nearest instruction at or before the constant that something jumps to. The wait is the
 * register that cap's Math.min answers into, which nothing between the hook and the cap may write.
 */
internal fun startHook(method: Method): StartHook {
    val where = where(method)
    val code = method.implementation?.instructions?.toList() ?: throw PatchException("$where has no body")
    if (method.parameterTypes.size != 1) throw PatchException("$where takes ${method.parameterTypes.size} parameters")
    val unset = code.indexOfFirst(::isTimeUnset)
    val targets = jumpTargets(method)
    val at = (unset downTo 0).firstOrNull { it in targets }
        ?: throw PatchException("$where has no meeting point before its duration check")
    val cap = (unset until code.size).firstOrNull { callsMath(code[it], "min") }
        ?: throw PatchException("$where caps nothing after its duration check")
    val answer = code.getOrNull(cap + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_WIDE } as? OneRegisterInstruction
        ?: throw PatchException("$where keeps no answer from its cap")
    val wait = answer.registerA
    val call = code[cap] as FiveRegisterInstruction
    if (call.registerC != wait && call.registerE != wait) throw PatchException("$where caps something other than its wait")
    for (register in listOf(wait, wait + 1)) {
        val writes = method.writersReaching(cap, register).filter { it in at until cap }
        if (writes.isNotEmpty()) throw PatchException("$where writes its wait at ${writes.joinToString()} after the meeting point")
    }
    val parameters = method.parameterRegisterNumber(0)
    val flag = code.firstOrNull { it.opcode == Opcode.IGET_BOOLEAN && (it as TwoRegisterInstruction).registerB == parameters }
        ?: throw PatchException("$where reads no flag from its parameters")
    val rebuffering = (flag as ReferenceInstruction).reference as FieldReference
    if (rebuffering.definingClass != method.parameterTypes[0].toString()) {
        throw PatchException("$where reads its first flag from ${rebuffering.definingClass}")
    }
    method.requireParameterIntact(where, 0, listOf(at))
    if (wait + 1 > 15 || parameters > 15) throw PatchException("$where keeps its wait or parameters past v15")
    val scratch = method.freeLocalsAt(where, at, 1).single()
    return StartHook(method, at, wait, parameters, rebuffering, scratch)
}

/** Whether the instruction at [index] is a cmp-long of anything against [mark]. */
private fun comparesAgainst(code: List<Instruction>, index: Int, mark: Int): Boolean {
    val instruction = code[index]
    return compares(instruction) && (instruction as ThreeRegisterInstruction).registerC == mark
}

/** Throws unless nothing from [from] up to [to] writes the mark in [register] (a pair) that [to] reads. */
private fun requireUnwritten(method: Method, what: String, register: Int, from: Int, to: Int) {
    for (half in listOf(register, register + 1)) {
        val writes = method.writersReaching(to, half).filter { it in from until to }
        if (writes.isNotEmpty()) throw PatchException("${where(method)} writes its $what at ${writes.joinToString()} before comparing it")
    }
}

/**
 * HeroPlayer's keep-loading check stores its final low mark in a wide field of its own, once,
 * right before comparing what's loaded against it and then against the high mark. Both go to the
 * extension in the gap after the store, and the store is made again with the answer.
 *
 * 588's Android 9+ build (#102, read 2026-11-19) stores a second wide field further down, a start
 * time it reads the clock into (`LX/08nx;->LC6` stores `A04` at 275), which no compare reads next.
 * Only a store whose value the next cmp-long compares against is the low mark's.
 */
internal fun heroAhead(method: Method): AheadHook {
    val where = where(method)
    val code = method.implementation?.instructions?.toList() ?: throw PatchException("$where has no body")
    val stores = code.indices.filter { index ->
        code[index].opcode == Opcode.IPUT_WIDE &&
            ((code[index] as ReferenceInstruction).reference as FieldReference).definingClass == method.definingClass &&
            (index + 1 until code.size).firstOrNull { compares(code[it]) }
                ?.let { comparesAgainst(code, it, (code[index] as TwoRegisterInstruction).registerA) } == true
    }
    val store = stores.singleOrNull()
        ?: throw PatchException("$where stores ${stores.size} wide fields of its own that it compares against next")
    val put = code[store] as TwoRegisterInstruction
    val low = put.registerA
    val at = store + 1
    if (at >= code.size || at in jumpTargets(method)) throw PatchException("$where can be jumped into after its low mark")
    val lowCompare = (at until code.size).firstOrNull { comparesAgainst(code, it, low) }
        ?: throw PatchException("$where never compares against its low mark")
    val buffered = (code[lowCompare] as ThreeRegisterInstruction).registerB
    val highCompare = (lowCompare + 1 until code.size).firstOrNull { index ->
        compares(code[index]) && (code[index] as ThreeRegisterInstruction).let { it.registerB == buffered && it.registerC != low }
    } ?: throw PatchException("$where never compares against a high mark")
    val high = (code[highCompare] as ThreeRegisterInstruction).registerC
    requireUnwritten(method, "low mark", low, at, lowCompare)
    requireUnwritten(method, "high mark", high, at, highCompare)
    if (maxOf(low, high) + 1 > 15 || put.registerB > 15) throw PatchException("$where keeps its marks past v15")
    val kept = (code[store] as ReferenceInstruction).reference as FieldReference
    return AheadHook(method, at, low, at, high, kept, put.registerB)
}

/**
 * ExoPlayer's keep-loading check raises its low mark to half a second with its one Math.max,
 * compares what's loaded against it, and further down against the high mark, a constant loaded
 * right before that compare. The low mark goes to the extension after the Math.max, the high one
 * right at its compare, which reads the low mark's answer.
 */
internal fun exoAhead(method: Method): AheadHook {
    val where = where(method)
    val code = method.implementation?.instructions?.toList() ?: throw PatchException("$where has no body")
    val raises = code.indices.filter { callsMath(code[it], "max") }
    val raise = raises.singleOrNull() ?: throw PatchException("$where raises ${raises.size} marks")
    val answer = code.getOrNull(raise + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_WIDE } as? OneRegisterInstruction
        ?: throw PatchException("$where keeps no low mark from its raise")
    val low = answer.registerA
    val lowAt = raise + 2
    val targets = jumpTargets(method)
    if (lowAt >= code.size || lowAt in targets) throw PatchException("$where can be jumped into after its low mark")
    val lowCompare = (lowAt until code.size).firstOrNull { comparesAgainst(code, it, low) }
        ?: throw PatchException("$where never compares against its low mark")
    val buffered = (code[lowCompare] as ThreeRegisterInstruction).registerB
    val highCompare = (lowCompare + 1 until code.size).firstOrNull { index ->
        val loaded = code.getOrNull(index - 1)
        compares(code[index]) && (code[index] as ThreeRegisterInstruction).let { compare ->
            compare.registerB == buffered && compare.registerC != low &&
                loaded != null && loaded.opcode in WIDE_CONSTANTS &&
                (loaded as OneRegisterInstruction).registerA == compare.registerC
        }
    } ?: throw PatchException("$where never compares against a high mark")
    if (highCompare in targets) throw PatchException("$where can be jumped into at its high mark's compare")
    val high = (code[highCompare] as ThreeRegisterInstruction).registerC
    requireUnwritten(method, "low mark", low, lowAt, lowCompare)
    requireUnwritten(method, "low mark", low, lowAt, highCompare)
    if (maxOf(low, high) + 1 > 15) throw PatchException("$where keeps its marks past v15")
    return AheadHook(method, lowAt, low, highCompare, high)
}

/**
 * Hands the start check's wait and its after-a-stall flag to the extension where every way to the
 * wait meets, under that spot's own label so the jumps there run it too, and goes on with the answer.
 */
internal fun MutableMethod.askStart(hook: StartHook) {
    addInstructionsAtControlFlowLabel(
        hook.at,
        """
            iget-boolean v${hook.scratch}, v${hook.parameters}, ${hook.rebuffering}
            invoke-static { v${hook.wait}, v${hook.wait + 1}, v${hook.scratch} }, $START_AT
            move-result-wide v${hook.wait}
        """,
    )
}

/**
 * Hands the keep-loading check's low mark, then its high mark with the answered low one, to the
 * extension, and goes on with the answers. Neither spot is jumped to, so plain inserts do, the
 * later one first so the earlier index still holds.
 */
internal fun MutableMethod.askAhead(hook: AheadHook) {
    val high = """
        invoke-static { v${hook.low}, v${hook.low + 1}, v${hook.high}, v${hook.high + 1} }, $AHEAD_HIGH
        move-result-wide v${hook.high}
    """
    val low = buildString {
        appendLine("invoke-static { v${hook.low}, v${hook.low + 1} }, $AHEAD_LOW")
        appendLine("move-result-wide v${hook.low}")
        if (hook.highAt == hook.lowAt) append(high).append('\n')
        hook.kept?.let { appendLine("iput-wide v${hook.low}, v${hook.self}, $it") }
    }
    if (hook.highAt != hook.lowAt) {
        if (hook.highAt < hook.lowAt) throw PatchException("${where(this)} has its high mark before its low one")
        addInstructions(hook.highAt, high)
    }
    addInstructions(hook.lowAt, low)
}
