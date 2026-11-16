/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.progressbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.media.reelspeed.REEL_PARAM_STUBS
import app.morphe.patches.facebook.media.resume.reportedValues
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// Dragging the bar of a live video opened from search results did nothing (#482). That viewer's
// scrubber sends a seek request, and Facebook's seek handler skips any seek on a live video unless
// the player is marked as rewound, which Facebook's full player does while its bar is dragged and
// this scrubber never does.

/** The format the player's seek request describes itself with, a kept literal (587 arm64 `LX/05Z3;`). */
internal const val SEEK_REQUEST_FORMAT = "%s: %s, lastStartPosition: %d"

/** What the player's live latency setter logs when it can't reach the player, a kept literal. */
internal const val LIVE_LATENCY_LOG = "Unable to grootPlayer.setLiveLatencyMode"

/** The name the params' debug dump gives the live flag, a kept literal. */
internal const val LIVE_NOW_REPORTED = "isLiveNow"

internal const val SCRUBBER_SEEK = "$PROGRESS_BAR->scrubberSeek(Ljava/lang/Object;)V"
internal const val LIVE_SEEK_REFUSED = "$PROGRESS_BAR->liveSeekRefused(ILjava/lang/Object;Ljava/lang/Object;)Z"

/** The extension's stub the patch fills with a call of the player's live latency setter. */
internal const val LIVE_REWOUND_STUB = "liveRewound"

private fun refuseLiveSeek(detail: String): Nothing = throw PatchException("Keep the progress bar: $detail")

private fun Instruction.referenced() = (this as? ReferenceInstruction)?.reference?.toString()

/**
 * The player's seek request: its [type], the constructor that takes the [trigger], the position
 * and a flag, as smali, and the field holding the trigger (587 arm64 `LX/05Z3;->A01:LX/02xv;`).
 */
internal class SeekRequest(val type: String, val init: String, val trigger: FieldReference)

/**
 * The one class among [holders] whose own toString loads [SEEK_REQUEST_FORMAT] and reads one
 * object field of its own, the trigger, and an int, the position, with a constructor taking the
 * trigger, an int and a boolean. Refuses otherwise.
 */
internal fun seekRequest(holders: List<ClassDef>): SeekRequest {
    val described = holders.mapNotNull { holder ->
        holder.methods.singleOrNull {
            it.name == "toString" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" &&
                holdsString(it, SEEK_REQUEST_FORMAT)
        }?.let { holder to it }
    }
    val (request, describe) = described.singleOrNull()
        ?: refuseLiveSeek("${described.size} classes describe themselves with \"$SEEK_REQUEST_FORMAT\", expected one seek request")
    val reads = describe.implementation!!.instructions.mapNotNull { instruction ->
        val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
        field?.takeIf { (instruction.opcode == Opcode.IGET_OBJECT || instruction.opcode == Opcode.IGET) && it.definingClass == request.type }
    }
    val triggers = reads.filter { it.type.startsWith("L") }.distinctBy { it.toString() }
    val trigger = triggers.singleOrNull()
        ?: refuseLiveSeek("${request.type}->toString reads ${triggers.size} object fields of its own, expected the seek's trigger")
    if (reads.none { it.type == "I" }) refuseLiveSeek("${request.type}->toString reads no position")
    val parameters = listOf(trigger.type, "I", "Z")
    if (request.methods.none { it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == parameters }) {
        refuseLiveSeek("${request.type} has no constructor taking ${parameters.joinToString("")}")
    }
    return SeekRequest(request.type, "${request.type}-><init>(${parameters.joinToString("")})V", trigger)
}

/** Where [method] builds [request]: the constructor call's index and the register holding the request. */
internal fun scrubberSeekSite(method: Method, request: SeekRequest): Pair<Int, Int> {
    val code = method.implementation?.instructions?.toList() ?: emptyList()
    val builds = code.indices.filter {
        (code[it].opcode == Opcode.INVOKE_DIRECT || code[it].opcode == Opcode.INVOKE_DIRECT_RANGE) && code[it].referenced() == request.init
    }
    val at = builds.singleOrNull()
        ?: refuseLiveSeek("${method.definingClass}->${method.name} builds ${builds.size} seek requests, expected one")
    val register = when (val init = code[at]) {
        is RegisterRangeInstruction -> init.startRegister
        is FiveRegisterInstruction -> init.registerC
        else -> refuseLiveSeek("${method.definingClass}->${method.name} builds its seek request oddly")
    }
    if (at + 1 >= code.size) refuseLiveSeek("${method.definingClass}->${method.name} ends at its seek request")
    return at to register
}

/**
 * Right after the bar's release builds its seek request, the extension gets the request. Refuses
 * when a jump lands there, as the hook would run without the request built.
 */
internal fun MutableMethod.reportsScrubberSeek(request: SeekRequest) {
    val (at, register) = scrubberSeekSite(this, request)
    val next = implementation!!.instructions[at + 1] as BuilderInstruction
    if (next.location.labels.isNotEmpty()) refuseLiveSeek("$definingClass->$name has a jump to just after its seek request")
    addInstructions(at + 1, "invoke-static/range { v$register .. v$register }, $SCRUBBER_SEEK")
}

/**
 * Facebook's skip of a seek on a live video, in the handler of [SeekRequest] (587 arm64
 * `LX/068M;->A1A`): the [player]'s [live] check, whose answer lands in [refused] and is jumped on
 * at [at], over a read of the trigger from the [request] and the player's [seek] with it.
 */
internal class LiveSeekCheck(
    val method: Method,
    val at: Int,
    val refused: Int,
    val request: Int,
    val player: Int,
    val live: MethodReference,
    val seek: MethodReference,
)

/** Each live check in [holder]'s methods in front of a seek with [request]'s trigger. */
internal fun liveSeekChecks(holder: ClassDef, request: SeekRequest): List<LiveSeekCheck> =
    holder.methods.flatMap { method ->
        val code = method.implementation?.instructions?.toList() ?: return@flatMap emptyList()
        (0 until code.size - 4).mapNotNull { i -> liveSeekCheckAt(method, code, i, request) }
    }

private fun liveSeekCheckAt(method: Method, code: List<Instruction>, i: Int, request: SeekRequest): LiveSeekCheck? {
    val (ask, result, jump, read, seek) = code.subList(i, i + 5)
    if (ask.opcode != Opcode.INVOKE_VIRTUAL || result.opcode != Opcode.MOVE_RESULT || jump.opcode != Opcode.IF_NEZ ||
        read.opcode != Opcode.IGET_OBJECT || seek.opcode != Opcode.INVOKE_VIRTUAL
    ) return null
    val live = (ask as ReferenceInstruction).reference as MethodReference
    if (live.returnType != "Z" || live.parameterTypes.isNotEmpty() || (ask as FiveRegisterInstruction).registerCount != 1) return null
    val player = ask.registerC
    val refused = (result as OneRegisterInstruction).registerA
    if ((jump as OneRegisterInstruction).registerA != refused || read.referenced() != request.trigger.toString()) return null
    val trigger = (read as TwoRegisterInstruction).registerA
    val call = (seek as ReferenceInstruction).reference as MethodReference
    val arguments = seek as FiveRegisterInstruction
    if (call.definingClass != live.definingClass || call.returnType != "V" ||
        call.parameterTypes.map(CharSequence::toString) != listOf(request.trigger.type, "I") ||
        arguments.registerCount != 3 || arguments.registerC != player || arguments.registerD != trigger
    ) return null
    return LiveSeekCheck(method, i + 2, refused, read.registerB, player, live, call)
}

/** The field [params]' debug dump reports as [LIVE_NOW_REPORTED] (587 `VideoPlayerParams->A1s:Z`). */
internal fun liveNowField(params: ClassDef): FieldReference {
    val dumps = params.methods.filter { method -> REEL_PARAM_STUBS.keys.all { holdsString(method, it) } }
    val dump = dumps.singleOrNull()
        ?: refuseLiveSeek("expected one method of ${params.type} reporting ${REEL_PARAM_STUBS.keys.joinToString()}, found ${dumps.size}")
    return reportedValues(dump)[LIVE_NOW_REPORTED]?.takeIf { it.type == "Z" }
        ?: refuseLiveSeek("${params.type}->${dump.name} doesn't report $LIVE_NOW_REPORTED as a boolean")
}

/**
 * The [player] class's live latency setter, the [setter] that marks a live video as rewound or
 * not (587 arm64 `LX/05MK;->A1q(Z)V`). Refuses unless [check]'s live check is the player's own and
 * reads [liveNow], and the player and the setter are public, as the extension's stub calls it.
 */
internal class LiveRewind(val player: String, val setter: Method)

/** The [player]'s own methods that take a boolean and log [LIVE_LATENCY_LOG], the live latency setter. */
internal fun liveLatencySetters(player: ClassDef): List<Method> = player.methods.filter {
    !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
        it.parameterTypes.map(CharSequence::toString) == listOf("Z") && holdsString(it, LIVE_LATENCY_LOG)
}

internal fun liveRewind(player: ClassDef, check: LiveSeekCheck, liveNow: FieldReference): LiveRewind {
    val live = player.methods.singleOrNull {
        it.name == check.live.name && it.returnType == "Z" && it.parameterTypes.isEmpty() && it.implementation != null
    } ?: refuseLiveSeek("${player.type} has no ${check.live.name}()Z of its own")
    if (live.implementation!!.instructions.none { it.opcode == Opcode.IGET_BOOLEAN && it.referenced() == liveNow.toString() }) {
        refuseLiveSeek("${player.type}->${live.name}, which the seek handler skips on, doesn't read $liveNow")
    }
    val setters = liveLatencySetters(player)
    val setter = setters.singleOrNull()
        ?: refuseLiveSeek("${setters.size} methods of ${player.type} take a boolean and log \"$LIVE_LATENCY_LOG\", expected one")
    if (!AccessFlags.PUBLIC.isSet(player.accessFlags) || !AccessFlags.PUBLIC.isSet(setter.accessFlags)) {
        refuseLiveSeek("${player.type}->${setter.name} isn't public, so the extension can't call it")
    }
    return LiveRewind(player.type, setter)
}

/**
 * Refuses unless the hook can go in front of [check]'s jump: its registers fit the call's four
 * bits, nothing jumps onto the jump, and no way on from it reads the live check's answer.
 */
internal fun checkLiveSeekThrough(check: LiveSeekCheck) {
    val where = "${check.method.definingClass}->${check.method.name}"
    if (maxOf(check.refused, check.request, check.player) > 15) refuseLiveSeek("$where keeps the live seek check past v15")
    val liveness = RegisterLiveness.of(check.method)
    val onward = ControlFlow.of(check.method).normal[check.at]
    if (onward.any { check.refused in liveness.liveInto(it) }) refuseLiveSeek("$where reads its live check again past the jump")
}

/**
 * In front of the seek handler's jump over the seek on a live video, the extension gets the live
 * check's answer, the request and the player, and its answer takes the place of the check's: a
 * no seeks. Refuses when something jumps onto the jump, as the hook would be skipped there.
 */
internal fun MutableMethod.letsLiveSeekThrough(check: LiveSeekCheck) {
    checkLiveSeekThrough(check)
    val jump = implementation!!.instructions[check.at] as BuilderInstruction
    if (jump.opcode != Opcode.IF_NEZ || (jump as OneRegisterInstruction).registerA != check.refused) {
        refuseLiveSeek("$definingClass->$name changed at its live seek check")
    }
    if (jump.location.labels.isNotEmpty()) {
        refuseLiveSeek("$definingClass->$name has a jump onto its live seek check")
    }
    addInstructions(
        check.at,
        """
            invoke-static { v${check.refused}, v${check.request}, v${check.player} }, $LIVE_SEEK_REFUSED
            move-result v${check.refused}
        """,
    )
}

internal fun isLiveRewoundStub(method: Method) = method.name == LIVE_REWOUND_STUB &&
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
    method.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;", "Z")

/**
 * Fills the extension's [LIVE_REWOUND_STUB] with a call of the player's live latency setter on the
 * player and the flag it's handed. d8 gives the stub no locals of its own, so the player is cast in p0.
 */
internal fun BytecodePatchContext.fillLiveRewound(rewind: LiveRewind) {
    val stub = mutableClassDefBy(PROGRESS_BAR).methods.singleOrNull(::isLiveRewoundStub)
        ?: refuseLiveSeek("$PROGRESS_BAR has no static void $LIVE_REWOUND_STUB(Object, boolean)")
    stub.addInstructions(
        0,
        """
            check-cast p0, ${rewind.player}
            invoke-virtual { p0, p1 }, ${rewind.player}->${rewind.setter.name}(Z)V
        """,
    )
}

internal const val LIVE_BAR_SEEK = "$PROGRESS_BAR->liveBarSeek(Ljava/lang/Object;Ljava/lang/Object;)V"

/**
 * The player's own skip of a seek on a live video, in the [method] its seek hands the seek to (587
 * arm64 `LX/05MK;->A1a(LX/02xv;IZ)V`, from `JsQ`): past its read of the live flag, [at] is its ask
 * whether the video was rewound, and a no returns without the seek. The scrubber search results
 * open a live video with sends its bar's seek straight there, past the seek handler (#482).
 */
internal class LiveGate(val method: Method, val at: Int)

/**
 * The live gate of [rewind]'s player: the one method of its own that [check]'s seek calls, taking
 * the trigger, a position and a flag, where a read of [liveNow] comes before the player's ask of
 * a check that reads what the latency setter writes, whose no returns. Refuses otherwise, or when
 * the hook's registers wouldn't fit.
 */
internal fun liveGate(player: ClassDef, check: LiveSeekCheck, liveNow: FieldReference, rewind: LiveRewind): LiveGate {
    fun own(call: MethodReference) = player.methods.singleOrNull {
        it.name == call.name && it.returnType == call.returnType && it.implementation != null &&
            it.parameterTypes.map(CharSequence::toString) == call.parameterTypes.map(CharSequence::toString)
    }
    val seek = own(check.seek) ?: refuseLiveSeek("${player.type} has no ${check.seek.name} of its own")
    val handedTo = seek.implementation!!.instructions.mapNotNull { it.referencedMethod() }
        .filter { it.definingClass == player.type }.distinctBy { it.toString() }
    val call = handedTo.singleOrNull()
        ?: refuseLiveSeek("${player.type}->${seek.name} calls ${handedTo.size} of the player's methods, expected the one it hands the seek to")
    val gate = own(call) ?: refuseLiveSeek("${player.type} has no ${call.name} of its own")
    val parameters = listOf(check.seek.parameterTypes.first().toString(), "I", "Z")
    if (AccessFlags.STATIC.isSet(gate.accessFlags) || gate.returnType != "V" || gate.parameterTypes.map(CharSequence::toString) != parameters) {
        refuseLiveSeek("${player.type}->${gate.name} doesn't take the trigger, a position and a flag")
    }
    val written = rewind.setter.implementation!!.instructions
        .mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
        .filter { it.definingClass == player.type }.map { it.toString() }.toSet()
    val implementation = gate.implementation!!
    val code = implementation.instructions.toList()
    // The player and the trigger, in the last registers: this, then the trigger, the position and the flag.
    val self = implementation.registerCount - 4
    val liveRead = code.indexOfFirst { it.opcode == Opcode.IGET_BOOLEAN && it.referenced() == liveNow.toString() }
    if (liveRead < 0) refuseLiveSeek("${player.type}->${gate.name} doesn't read $liveNow")
    val asks = (liveRead + 1 until code.size - 3).filter { i ->
        val ask = code[i]
        val asked = ask.referencedMethod()
        ask.opcode == Opcode.INVOKE_VIRTUAL && asked != null && asked.definingClass == player.type &&
            asked.returnType == "Z" && asked.parameterTypes.isEmpty() &&
            (ask as FiveRegisterInstruction).registerCount == 1 && ask.registerC == self &&
            code[i + 1].opcode == Opcode.MOVE_RESULT && code[i + 2].opcode == Opcode.IF_NEZ &&
            (code[i + 2] as OneRegisterInstruction).registerA == (code[i + 1] as OneRegisterInstruction).registerA &&
            code[i + 3].opcode == Opcode.RETURN_VOID &&
            own(asked)?.implementation?.instructions?.any { it.referenced() in written } == true
    }
    val at = asks.singleOrNull()
        ?: refuseLiveSeek("${player.type}->${gate.name} asks ${asks.size} times whether a live video was rewound, expected once")
    if (self + 1 > 15) refuseLiveSeek("${player.type}->${gate.name} keeps its trigger past v15")
    return LiveGate(gate, at)
}

private fun Instruction.referencedMethod() = (this as? ReferenceInstruction)?.reference as? MethodReference

/**
 * In front of the player's ask whether a live video was rewound, the extension gets the seek's
 * trigger and the player, and marks the video rewound for the scrubber's bar, so the ask says yes.
 * Refuses when a jump lands on the ask, as the hook would be skipped there.
 */
internal fun MutableMethod.rewindsForTheBar(gate: LiveGate) {
    val ask = implementation!!.instructions[gate.at] as BuilderInstruction
    if (ask.opcode != Opcode.INVOKE_VIRTUAL || ask.referenced() != gate.method.implementation!!.instructions.elementAt(gate.at).referenced()) {
        refuseLiveSeek("$definingClass->$name changed at its rewound check")
    }
    if (ask.location.labels.isNotEmpty()) refuseLiveSeek("$definingClass->$name has a jump onto its rewound check")
    addInstructions(gate.at, "invoke-static { p1, p0 }, $LIVE_BAR_SEEK")
}

/** What the player's conversion of a seek position logs when the result is negative, a kept literal. */
internal const val NEGATIVE_SEEK_LOG = "seekTo's mediaTimeMsWithPreview is negative: %d. Forcing to 0"

internal const val LIVE_OFFSET_STUB = "liveOffset"
internal const val LIVE_SEEK_SHIFT_STUB = "liveSeekShift"

private const val ATOMIC_BOOLEAN = "Ljava/util/concurrent/atomic/AtomicBoolean;"

/**
 * How the player turns a seek position into its engine's (587 arm64 `LX/05MK;->A00(LX/02xv;LX/05MK;I)I`):
 * for a live video, or while the player's [shift] flag is set, it adds the [offset] of the playback
 * engine the static [engine] getter returns. Search results open some live videos with settings that
 * say they aren't live, so their seeks skip that, and the player stays at the live edge (#482).
 */
internal class LiveShift(val player: String, val engine: MethodReference, val offset: MethodReference, val shift: FieldReference)

/**
 * The [player]'s seek conversion: its one static method returning an int that logs [NEGATIVE_SEEK_LOG]
 * and takes the player, where [check]'s live check comes with one static engine getter on the
 * player, one offset call on that engine and one read of an AtomicBoolean of the player's. Refuses
 * otherwise, or when the extension couldn't reach any of them.
 */
internal fun liveShift(player: ClassDef, check: LiveSeekCheck, classOf: (String) -> ClassDef?): LiveShift {
    val conversions = player.methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "I" &&
            player.type in it.parameterTypes.map(CharSequence::toString) && holdsString(it, NEGATIVE_SEEK_LOG)
    }
    val conversion = conversions.singleOrNull()
        ?: refuseLiveSeek("${conversions.size} static methods of ${player.type} log \"$NEGATIVE_SEEK_LOG\", expected one")
    val where = "${player.type}->${conversion.name}"
    val code = conversion.implementation!!.instructions.toList()
    if (code.none { it.opcode == Opcode.INVOKE_VIRTUAL && it.referenced() == check.live.toString() }) {
        refuseLiveSeek("$where doesn't ask ${check.live}, the live check")
    }
    fun calls(opcode: Opcode, matches: (MethodReference) -> Boolean) = code.withIndex()
        .filter { (_, it) -> it.opcode == opcode && it.referencedMethod()?.let(matches) == true }
    val engines = calls(Opcode.INVOKE_STATIC) {
        it.returnType.startsWith("L") && it.parameterTypes.map(CharSequence::toString) == listOf(player.type)
    }
    val engine = engines.map { it.value.referencedMethod()!! }.distinctBy { it.toString() }.singleOrNull()
        ?: refuseLiveSeek("$where gets ${engines.size} things from the player, expected its playback engine")
    val offsets = calls(Opcode.INVOKE_INTERFACE) {
        it.definingClass == engine.returnType && it.returnType == "J" && it.parameterTypes.isEmpty()
    }
    val offset = offsets.map { it.value.referencedMethod()!! }.distinctBy { it.toString() }.singleOrNull()
        ?: refuseLiveSeek("$where asks ${engine.returnType} for ${offsets.size} longs, expected its live offset")
    if (offsets.first().index < engines.first().index) refuseLiveSeek("$where asks for the live offset before it gets the engine")
    val shifts = code.mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
        .filter { it.definingClass == player.type && it.type == ATOMIC_BOOLEAN }.distinctBy { it.toString() }
    val shift = shifts.singleOrNull()
        ?: refuseLiveSeek("$where reads ${shifts.size} AtomicBooleans of the player, expected the flag that adds the live offset")
    val engineClass = classOf(engine.definingClass)
    val getter = engineClass?.methods?.singleOrNull {
        it.name == engine.name && it.returnType == engine.returnType &&
            it.parameterTypes.map(CharSequence::toString) == engine.parameterTypes.map(CharSequence::toString)
    }
    if (engineClass == null || getter == null || !AccessFlags.PUBLIC.isSet(engineClass.accessFlags) ||
        !AccessFlags.PUBLIC.isSet(getter.accessFlags) || !AccessFlags.STATIC.isSet(getter.accessFlags)
    ) refuseLiveSeek("$engine, the player's engine getter, isn't public static, so the extension can't call it")
    val engineType = classOf(engine.returnType)
    if (engineType == null || !AccessFlags.PUBLIC.isSet(engineType.accessFlags)) {
        refuseLiveSeek("${engine.returnType}, the player's engine, isn't public, so the extension can't ask it")
    }
    val field = player.fields.singleOrNull { it.name == shift.name && it.type == shift.type }
    if (field == null || !AccessFlags.PUBLIC.isSet(field.accessFlags)) {
        refuseLiveSeek("$shift isn't public, so the extension can't set it")
    }
    return LiveShift(player.type, engine, offset, shift)
}

internal fun isLiveOffsetStub(method: Method) = method.name == LIVE_OFFSET_STUB &&
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "J" &&
    method.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;")

internal fun isLiveSeekShiftStub(method: Method) = method.name == LIVE_SEEK_SHIFT_STUB &&
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Ljava/lang/Object;" &&
    method.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;")

/**
 * Fills the extension's [LIVE_OFFSET_STUB] with the engine's live offset for the player (its zero
 * when the player has no engine) and [LIVE_SEEK_SHIFT_STUB] with the player's flag that adds it. d8
 * gives the offset stub two locals for its zero, which the offset comes back in.
 */
internal fun BytecodePatchContext.fillLiveShift(shift: LiveShift) {
    val extension = mutableClassDefBy(PROGRESS_BAR)
    val offset = extension.methods.singleOrNull(::isLiveOffsetStub)
        ?: refuseLiveSeek("$PROGRESS_BAR has no static long $LIVE_OFFSET_STUB(Object)")
    if (offset.implementation!!.registerCount < 3) refuseLiveSeek("$PROGRESS_BAR->$LIVE_OFFSET_STUB has no locals for a long")
    offset.addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${shift.player}
            invoke-static { p0 }, ${shift.engine}
            move-result-object p0
            if-eqz p0, :none
            invoke-interface { p0 }, ${shift.offset}
            move-result-wide v0
            return-wide v0
        """,
        ExternalLabel("none", offset.getInstruction(0)),
    )
    val flag = extension.methods.singleOrNull(::isLiveSeekShiftStub)
        ?: refuseLiveSeek("$PROGRESS_BAR has no static Object $LIVE_SEEK_SHIFT_STUB(Object)")
    flag.addInstructions(
        0,
        """
            check-cast p0, ${shift.player}
            iget-object p0, p0, ${shift.shift}
            return-object p0
        """,
    )
}
