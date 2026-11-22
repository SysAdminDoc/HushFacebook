/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.seen

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.hook.feedFilterHookPatch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val PATCH = "Hide seen posts"

/**
 * What the viewport logger names itself in its trace, a literal Redex keeps. The method it labels
 * is `persistSeenState`, and that name is kept too.
 */
internal const val SEEN_TRACE = "VPVDLOGGING.ViewportLoggingHandler.persistSeenState"

/** The method Facebook calls with a feed unit once it counts as seen. Its name is kept. */
internal const val SEEN_METHOD = "persistSeenState"

/** What the method reads the unit's id with, an interface method whose name is kept. */
internal const val CACHE_ID_READER = "getCacheId"

/** The trace of the runnable that times a post's view and is persistSeenState's one caller. */
internal const val DWELL_TRACE = "VPVDLOGGING.ViewportLoggingHandler.logFeedUnitDurationInternal"

internal const val SEEN_POSTS = "Lapp/morphe/extension/facebook/feed/SeenPosts;"
internal const val SEEN = "$SEEN_POSTS->seen(Ljava/lang/Object;)V"

/**
 * Posts you've already scrolled past can stay out of the feed on later loads. Facebook's viewport
 * logger decides a post was seen: when a post leaves the screen, a runnable works out how long it
 * was on screen, and from 250 ms on it calls `ViewportLoggingHandler.persistSeenState(session,
 * feedUnit)`. That call is skipped on the News Feed itself (577 to 581 check the surface for
 * native_newsfeed, friendly_feed and the most recent tab first), so the hook can't sit inside
 * persistSeenState. It goes in the runnable, right after the dwell check passes and before the
 * surface check, and hands the extension the unit persistSeenState would get. The extension
 * remembers the unit's cache id. The feed guard drops a remembered post before Facebook adds it, so
 * the rule lives with the other feed rules and this patch only adds the signal. It brings the guard
 * ([feedFilterHookPatch]) itself, so picked alone it hides as well as remembers.
 */
@Suppress("unused")
val hideSeenPostsPatch = patch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Hide seen posts",
    description = "Keeps posts you've already scrolled past out of the feed when it loads again, for 1, 3, 7, " +
        "30 or 90 days, so you see something new. The list stays on your phone. Starts off. Turn it on in Hushfacebook " +
        "settings > News feed.",
) {
    category("Feed")
    dependsOn(settingsPatch, feedFilterHookPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        val gate = findDwellGate(findSeenMethod())
        mutableClassDefBy(gate.runner.definingClass).findMutableMethodOf(gate.runner).addInstructions(
            gate.index,
            // A range call takes any register, where a plain one only reaches v0 to v15.
            "invoke-static/range { v${gate.unitRegister} .. v${gate.unitRegister} }, $SEEN",
        )
        enableStatus("seenPosts")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Where the seen signal goes: the instruction [index] of [runner] that runs once the view lasted
 * long enough to count, with the feed unit in [unitRegister].
 */
internal class DwellGate(val runner: Method, val index: Int, val unitRegister: Int)

/**
 * The runnable's spot right after its own seen check: the last `cmp-long` and `if-ltz` before its
 * call to [seen], so the instruction after them runs only when the view lasted long enough, on
 * every surface. Refuses when that instruction is a branch target (a jump could skip the call) or
 * when anything between it and the call writes the unit's register. Changes nothing.
 */
internal fun BytecodePatchContext.findDwellGate(seen: Method): DwellGate {
    val runners = classDefByStrings(DWELL_TRACE, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { classDef -> classDef.methods.filter { isDwellRunner(it, seen) } }
    val runner = runners.singleOrNull()
        ?: refuse("expected one run() loading \"$DWELL_TRACE\" and calling $SEEN_METHOD, found ${runners.size}")
    return dwellGateIn(runner, seen) ?: refuse("the dwell check before $SEEN_METHOD in ${runner.definingClass} changed")
}

/** Whether [method] is a `run()V` that calls [seen]. */
internal fun isDwellRunner(method: Method, seen: Method): Boolean =
    method.name == "run" && method.returnType == "V" && method.parameterTypes.isEmpty() &&
        callIndex(method.implementation?.instructions?.toList().orEmpty(), seen) >= 0

private fun callIndex(code: List<Instruction>, seen: Method): Int = code.indexOfFirst {
    val reference = (it as? ReferenceInstruction)?.reference as? MethodReference
    it.opcode == Opcode.INVOKE_VIRTUAL && reference != null && reference.definingClass == seen.definingClass &&
        reference.name == seen.name && reference.parameterTypes.map(CharSequence::toString) == seen.parameterTypes.map(CharSequence::toString)
}

/** The gate in [runner] before its call to [seen], or null when the code isn't shaped as expected. */
internal fun dwellGateIn(runner: Method, seen: Method): DwellGate? {
    val code = runner.implementation?.instructions?.toList() ?: return null
    val call = callIndex(code, seen)
    if (call < 0) return null
    // this, the session and the feed unit.
    val invoke = code[call] as? FiveRegisterInstruction ?: return null
    if (invoke.registerCount != 3) return null
    val unit = invoke.registerE
    val check = (call - 1 downTo 1).firstOrNull { code[it].opcode == Opcode.IF_LTZ } ?: return null
    if (code[check - 1].opcode != Opcode.CMP_LONG) return null
    val index = check + 1
    if (index >= call) return null
    val written = (index until call).any { at ->
        val instruction = code[at]
        val target = (instruction as? OneRegisterInstruction)?.registerA
        target != null && instruction.opcode.setsRegister() &&
            (target == unit || (instruction.opcode.setsWideRegister() && target + 1 == unit))
    }
    if (written) return null
    val addresses = IntArray(code.size)
    for (at in 1 until code.size) addresses[at] = addresses[at - 1] + code[at - 1].codeUnits
    val targets = code.indices.mapNotNull { at -> (code[at] as? OffsetInstruction)?.let { addresses[at] + it.codeOffset } }
    if (addresses[index] in targets) return null
    return DwellGate(runner, index, unit)
}

/**
 * Whether [method] is the viewport logger's seen-state saver: an instance method of two parameters
 * that returns nothing, loads [SEEN_TRACE], and reads the unit's id through [CACHE_ID_READER].
 */
internal fun isSeenMethod(method: Method): Boolean =
    method.name == SEEN_METHOD && !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
        method.parameterTypes.size == 2 && holdsString(method, SEEN_TRACE) && readsCacheId(method)

private fun readsCacheId(method: Method): Boolean = method.implementation?.instructions?.any {
    val reference = (it as? ReferenceInstruction)?.reference as? MethodReference
    reference != null && reference.name == CACHE_ID_READER && reference.returnType == "Ljava/lang/String;" &&
        reference.parameterTypes.isEmpty()
} == true

/** The one method that saves the seen state. Changes nothing. */
internal fun BytecodePatchContext.findSeenMethod(): Method {
    val holders = classDefByStrings(SEEN_TRACE, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
    val methods = holders.flatMap { classDef -> classDef.methods.filter(::isSeenMethod) }
    return methods.singleOrNull()
        ?: refuse("expected one $SEEN_METHOD loading \"$SEEN_TRACE\" and reading $CACHE_ID_READER, found ${methods.size}")
}
