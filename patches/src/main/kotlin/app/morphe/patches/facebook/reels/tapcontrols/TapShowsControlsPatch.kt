/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.tapcontrols

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.layout.theme.branchTargets
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.search.branchTarget
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
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * A tap on a playing reel shows its controls and lets it play on, read from 584 (#225, 2026-10-24).
 * The obfuscated names here are for reviewers; the code never writes one down.
 *
 * A single tap on a reel reaches the reel control component's tap handler (584 LX/8bK;->A0E, the
 * method that logs "tap_to_show_player_control" and "reels_controls_tap_unmute"). Facebook built two
 * answers into it. One pauses the reel and shows its controls around a play button, which is what
 * everyone gets. The other shows the controls around a pause button while the reel plays on, a
 * second tap hides them, and a tap on a paused reel plays it. One boolean on the player's config
 * picks between them:
 *
 *     if-eqz vConfig, :pause_path
 *     invoke-interface {vConfig}, <config>->get()Z
 *     move-result vOn
 *     if-ne vOn, vTrue, :pause_path
 *
 * Its one implementation in 584 answers false (LX/A6n;->AlR, answered by LX/rFW;), and the tap
 * handler is its only caller. The patch hands that answer to the extension, which says yes while the
 * switch is on. Ads keep the pause: a check right after it sends an ad's tap there either way.
 *
 * The controls on a playing reel then hide after a second. The controller's show method (584
 * LX/8rP;->A04, PlayerControlController) posts that hide after a 1000 ms wide constant unless the
 * player is paused, and on the unified player the tap handler posts its own after the config's delay
 * (an int getter, also 1000). A second is too short to reach the skip or mute buttons, so both waits
 * go through the extension too, which makes them three seconds while the switch is on. The show
 * method is the one the tap handler calls right before announceControlsShowingForScreenReaders, a
 * name Facebook keeps.
 *
 * On the unified reel player (584 LX/8mO;->A01, true for UNIFIED_PLAYER_VDD) neither hide is posted:
 * the show method returns before its post, and so does the tap handler, through a static check right
 * before its delay read:
 *
 *     invoke-static {vConfig}, <check>(<config>)Z
 *     move-result vSkip
 *     if-eq vSkip, vTrue, :return
 *     invoke-interface {vConfig}, <config>->delay()I
 *
 * Facebook never ships that player with the show-and-play answer, so the controls would stay up until
 * the next tap. The check's answer goes through the extension, which says no while the switch is on,
 * and the tap handler posts its hide (the controller's hide and its cleanup 200 ms later).
 *
 * Every site is found before anything changes, and each has to be the only one of its kind.
 */

internal const val TAP_PATCH = "Tap shows the controls"

/** Strings the tap handler logs, and no other method holds both. */
internal const val TAP_LOG = "tap_to_show_player_control"
internal const val UNMUTE_LOG = "reels_controls_tap_unmute"

/** The kept name of the call that follows the controls showing. */
internal const val ANNOUNCE = "announceControlsShowingForScreenReaders"

private const val TAP_CLASS = "$EXTENSION_PACKAGE/reels/TapShowsControls;"
internal const val GATE_CALL = "$TAP_CLASS->showControls(Z)Z"
internal const val WAIT_CALL = "$TAP_CLASS->hideDelay(J)J"
internal const val UNIFIED_WAIT_CALL = "$TAP_CLASS->hideDelay(I)I"
internal const val SKIP_CALL = "$TAP_CLASS->skipHide(Z)Z"

private const val RUNNABLE = "Ljava/lang/Runnable;"

private val WIDE_CONSTS = setOf(Opcode.CONST_WIDE, Opcode.CONST_WIDE_32, Opcode.CONST_WIDE_16, Opcode.CONST_WIDE_HIGH16)

private fun refuse(detail: String): Nothing = throw PatchException("$TAP_PATCH: $detail")

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.where(): String = "$definingClass->$name"

private fun Instruction.call(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun MethodReference.parameters(): List<String> = parameterTypes.map(CharSequence::toString)

/** The registers a call passes, in order. */
private fun Instruction.registers(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

private fun Instruction.target(): Int = (this as OneRegisterInstruction).registerA

/** One hook site: the hook goes in right after the instruction at [after], on [register]. */
internal class Site(val after: Int, val register: Int)

/** Whether [method] is the reel control component's tap handler: it logs [TAP_LOG] and [UNMUTE_LOG]. */
internal fun isTapHandler(method: Method): Boolean {
    val strings = method.code().mapNotNullTo(mutableSetOf()) { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
    return TAP_LOG in strings && UNMUTE_LOG in strings
}

/** Throws unless nothing branches to the instruction a hook after [site] goes in front of. */
private fun Method.requireFallThrough(site: Site, what: String) {
    if (site.after + 1 in branchTargets(code())) refuse("a branch lands right after $what in ${where()}")
}

/**
 * The tap handler's config gate: a null check of a register, a boolean getter taking nothing called
 * on it through an interface, its move-result, then an if-ne on that result landing where the null
 * check lands. Exactly one, or a refusal.
 */
internal fun gateOf(handler: Method): Site {
    val code = handler.code()
    val gates = (0 until code.size - 3).filter { at ->
        val check = code[at]
        val get = code[at + 1].call()
        val result = code[at + 2]
        val branch = code[at + 3]
        check.opcode == Opcode.IF_EQZ && code[at + 1].opcode == Opcode.INVOKE_INTERFACE && get != null &&
            get.returnType == "Z" && get.parameterTypes.isEmpty() && code[at + 1].registers() == listOf(check.target()) &&
            result.opcode == Opcode.MOVE_RESULT && branch.opcode == Opcode.IF_NE &&
            (branch as TwoRegisterInstruction).registerA == result.target() &&
            branchTarget(code, at) != null && branchTarget(code, at) == branchTarget(code, at + 3)
    }
    val at = gates.singleOrNull() ?: refuse("expected one config gate in ${handler.where()}, found ${gates.size}")
    return Site(at + 2, code[at + 2].target())
}

/**
 * The tap handler's read of the unified player's hide delay: an int getter taking nothing called
 * through an interface, its move-result, then an int-to-long of that result. Exactly one, or a refusal.
 */
internal fun unifiedWaitOf(handler: Method): Site {
    val code = handler.code()
    val reads = (0 until code.size - 2).filter { at ->
        val get = code[at].call()
        code[at].opcode == Opcode.INVOKE_INTERFACE && get != null && get.returnType == "I" && get.parameterTypes.isEmpty() &&
            code[at + 1].opcode == Opcode.MOVE_RESULT && code[at + 2].opcode == Opcode.INT_TO_LONG &&
            (code[at + 2] as TwoRegisterInstruction).registerB == code[at + 1].target()
    }
    val at = reads.singleOrNull() ?: refuse("expected one hide delay read in ${handler.where()}, found ${reads.size}")
    return Site(at + 1, code[at + 1].target())
}

/**
 * The unified player check right before the tap handler's hide delay read ([unifiedWait]): a static
 * boolean call taking the config the read is made on, its move-result, then an if-eq on that result.
 */
internal fun unifiedSkipOf(handler: Method, unifiedWait: Site): Site {
    val code = handler.code()
    val read = unifiedWait.after - 1
    if (read < 3) refuse("nothing before the hide delay read in ${handler.where()}")
    val check = code[read - 3]
    val skip = check.call()
    val result = code[read - 2]
    val branch = code[read - 1]
    val found = check.opcode == Opcode.INVOKE_STATIC && skip != null && skip.returnType == "Z" &&
        skip.parameterTypes.size == 1 && check.registers() == code[read].registers() &&
        result.opcode == Opcode.MOVE_RESULT && branch.opcode == Opcode.IF_EQ &&
        result.target() in (branch as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) }
    if (!found) refuse("no unified player check right before the hide delay read in ${handler.where()}")
    return Site(read - 2, result.target())
}

/**
 * The controller's show method: the call taking an object and a boolean and returning nothing that
 * the tap handler makes within the four instructions before an [ANNOUNCE], the first that has one.
 * 587 (`LX/08kZ;->A0T`) moved its first [ANNOUNCE] out of line behind a branch, nine instructions
 * past the show, and keeps the show right before a later one.
 */
internal fun controllerShowOf(handler: Method): MethodReference {
    val code = handler.code()
    val announces = code.indices.filter { code[it].call()?.name == ANNOUNCE }
    if (announces.isEmpty()) refuse("${handler.where()} never calls $ANNOUNCE")
    return announces.firstNotNullOfOrNull { announce ->
        (announce - 1 downTo maxOf(0, announce - 4)).firstNotNullOfOrNull { at ->
            code[at].call()?.takeIf { show ->
                code[at].opcode == Opcode.INVOKE_VIRTUAL && show.returnType == "V" &&
                    show.parameters().let { it.size == 2 && it[0].startsWith("L") && it[1] == "Z" }
            }
        }
    } ?: refuse("no controller call before $ANNOUNCE in ${handler.where()}")
}

/**
 * Where the controller's show method loads the wait before the hide it posts: its one wide
 * constant, passed last to a post taking a Runnable and a long within four instructions.
 */
internal fun hideWaitOf(show: Method): Site {
    val code = show.code()
    val loads = code.indices.filter { code[it].opcode in WIDE_CONSTS }
    val load = loads.singleOrNull() ?: refuse("expected one wide constant in ${show.where()}, found ${loads.size}")
    val register = code[load].target()
    val posted = (load + 1..minOf(load + 4, code.size - 1)).any { at ->
        val post = code[at].call()
        post != null && post.returnType == "V" && post.parameters() == listOf(RUNNABLE, "J") &&
            code[at].registers().takeLast(2) == listOf(register, register + 1)
    }
    if (!posted) refuse("${show.where()} doesn't post its wide constant as a wait")
    return Site(load, register)
}

/**
 * The tap handler, its gate, unified player check and unified wait, the controller's show method and
 * its wait, found before anything changes.
 */
internal class TapSites(
    val handler: Method,
    val gate: Site,
    val skip: Site,
    val unifiedWait: Site,
    val show: Method,
    val wait: Site,
)

internal fun BytecodePatchContext.findTapSites(): TapSites {
    val handlers = mutableListOf<Method>()
    classDefForEachReferencing(strings = listOf(TAP_LOG, UNMUTE_LOG)) { classDef: ClassDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        classDef.methods.filterTo(handlers, ::isTapHandler)
    }
    val handler = handlers.singleOrNull()
        ?: refuse("expected one method logging $TAP_LOG and $UNMUTE_LOG, found " + handlers.joinToString { it.where() }.ifEmpty { "none" })
    val gate = gateOf(handler)
    val unifiedWait = unifiedWaitOf(handler)
    val skip = unifiedSkipOf(handler, unifiedWait)
    val showCall = controllerShowOf(handler)
    val show = classDefByOrNull(showCall.definingClass)?.methods?.firstOrNull {
        it.name == showCall.name && it.returnType == showCall.returnType && it.parameterTypes.map(CharSequence::toString) == showCall.parameters()
    } ?: refuse("${showCall.definingClass}->${showCall.name} isn't in the app")
    val wait = hideWaitOf(show)
    handler.requireFallThrough(gate, "the config gate's answer")
    handler.requireFallThrough(skip, "the unified player check")
    handler.requireFallThrough(unifiedWait, "the hide delay read")
    show.requireFallThrough(wait, "the wait")
    return TapSites(handler, gate, skip, unifiedWait, show, wait)
}

/**
 * Hands the gate's answer, the unified player check and both waits to the extension, the later
 * handler sites first so the earlier indices still hold.
 */
internal fun BytecodePatchContext.askAtTapSites(sites: TapSites) {
    val handler = mutableClassDefBy(sites.handler.definingClass).findMutableMethodOf(sites.handler)
    val inHandler = listOf(sites.gate to GATE_CALL, sites.skip to SKIP_CALL, sites.unifiedWait to UNIFIED_WAIT_CALL)
        .sortedByDescending { it.first.after }
    for ((site, call) in inHandler) {
        handler.addInstructions(
            site.after + 1,
            """
                invoke-static/range { v${site.register} .. v${site.register} }, $call
                move-result v${site.register}
            """,
        )
    }
    val wait = sites.wait
    mutableClassDefBy(sites.show.definingClass).findMutableMethodOf(sites.show).addInstructions(
        wait.after + 1,
        """
            invoke-static/range { v${wait.register} .. v${wait.register + 1} }, $WAIT_CALL
            move-result-wide v${wait.register}
        """,
    )
}

/**
 * A tap on a playing reel shows its controls and lets it play on. See the notes above for where,
 * and the extension's TapShowsControls for when.
 *
 * In Morphe Manager's default selection with the switch off, so nothing changes until it's turned on.
 */
@Suppress("unused")
val tapShowsControlsPatch = bytecodePatch(
    name = "Tap shows the controls",
    description = "A tap on a playing reel shows its controls and lets it keep playing, so you can skip or mute " +
        "without stopping it. The button in the middle still pauses. Starts off. Turn it on in Hushfacebook settings > " +
        "Reels and Watch.",
) {
    category("Reels")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val sites = findTapSites()
        requireStatusMethod("tapShowsControls")
        askAtTapSites(sites)
        enableStatus("tapShowsControls")
    }
}
