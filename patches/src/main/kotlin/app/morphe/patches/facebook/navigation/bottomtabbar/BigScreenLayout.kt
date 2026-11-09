/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.bottomtabbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.misc.extension.patchLog
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.util.MethodUtil

/*
 * Phone layout on big screens (#149). Read from 582, 2026-10-14; the obfuscated names are for
 * reviewers, the code never writes one down.
 *
 * The place that decides whether the tab bar goes to the bottom (582 LX/22j;->A08, one of the two
 * reads of the override Tab bar at the bottom answers) first asks a static check taking the
 * Activity (LX/1We;->A07) and, when it says yes, puts the bar at the top without reading the
 * override at all. That check is Facebook's big screen test: it asks a gate of MobileConfig flags
 * (LX/1We;->A09) and, when that allows the big screen layout, the window size class (wide and tall
 * enough), and stores the answer in a static flag the rest of the app reads (LX/1We;->A05). The
 * main screen asks the same check in a dozen places of its own (FbChromeFragment's navigation bar,
 * the insets the bottom bar makes room for, the main screen's theme and layout mode), so answering
 * only the tab bar's place would leave the rest laid out for a big screen. The gate is asked by
 * the check and by the class's other writer of the flag, so a no there gives the whole app the
 * phone layout, the way accounts outside the big screen rollout get it.
 *
 * 586 (2026-11-09) moved the override's read into a method of its own (LX/02Cc;->A05), which
 * asks no check; the place's `(Activity)Z` methods in that class ask the big screen check before
 * they call it (A09 asks LX/01AV;->A09), so [bigScreenChecksInCallers] looks there.
 */

internal const val PHONE_LAYOUT = "$BOTTOM_TAB_BAR->phoneLayout()Z"

// ACTIVITY is TabBarScrollAwayPatch.kt's.
private const val CONTEXT = "Landroid/content/Context;"

private fun MethodReference.params() = parameterTypes.map(CharSequence::toString)

/**
 * The static `(Activity)Z` checks [read]'s method asks before it loads the override's key, whose
 * yes jumps away at once (`move-result` then `if-nez` on it), the way the tab bar's place skips
 * the override on a big screen.
 */
internal fun bigScreenChecks(read: OverrideRead): List<MethodReference> {
    val code = read.method.implementation?.instructions?.toList() ?: return emptyList()
    return checksBefore(code, read.resultIndex - 4)
}

/**
 * 586's form of [bigScreenChecks]: the read's method (`LX/02Cc;->A05()Z` on arm64) asks no check,
 * and the `(Activity)Z` methods of [owner], its class, that call it ask one first (`A09` asks
 * `LX/01AV;->A09`, then `A05`).
 */
internal fun bigScreenChecksInCallers(read: OverrideRead, owner: ClassDef): List<MethodReference> =
    owner.methods.filter { it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf(ACTIVITY) }
        .flatMap { caller ->
            val code = caller.implementation?.instructions?.toList() ?: return@flatMap emptyList()
            val at = code.indexOfFirst { instruction ->
                val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                call != null && call.definingClass == read.method.definingClass && MethodUtil.methodSignaturesMatch(call, read.method)
            }
            if (at < 0) emptyList() else checksBefore(code, at)
        }

/** The static `(Activity)Z` calls in [code] before [end] whose yes jumps away at once. */
private fun checksBefore(code: List<Instruction>, end: Int): List<MethodReference> =
    (0 until end).mapNotNull { at ->
        val call = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
        if (code[at].opcode != Opcode.INVOKE_STATIC || call == null || call.returnType != "Z" || call.params() != listOf(ACTIVITY)) {
            return@mapNotNull null
        }
        val result = code.getOrNull(at + 1)
        val branch = code.getOrNull(at + 2)
        if (result?.opcode != Opcode.MOVE_RESULT || branch?.opcode != Opcode.IF_NEZ ||
            (branch as OneRegisterInstruction).registerA != (result as OneRegisterInstruction).registerA
        ) {
            return@mapNotNull null
        }
        call
    }

/**
 * The gate [check] asks: the first static `(Context)Z` call on [check]'s own class, when [check]
 * also stores a static boolean of that class, the flag the app reads. Null otherwise.
 */
internal fun bigScreenGate(check: Method): MethodReference? {
    val code = check.implementation?.instructions?.toList() ?: return null
    val storesFlag = code.any {
        it.opcode == Opcode.SPUT_BOOLEAN && ((it as ReferenceInstruction).reference as FieldReference).definingClass == check.definingClass
    }
    if (!storesFlag) return null
    return code.firstNotNullOfOrNull { instruction ->
        val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        call?.takeIf {
            instruction.opcode == Opcode.INVOKE_STATIC && it.definingClass == check.definingClass &&
                it.returnType == "Z" && it.params() == listOf(CONTEXT)
        }
    }
}

/**
 * Puts the extension at the top of the big screen gate, found from the big screen check the tab
 * bar's place asks before its override [reads]. A build where either isn't found keeps
 * Facebook's own gate, says so in the patch log, and the switch does nothing there; Tab bar at
 * the bottom goes on without it.
 */
internal fun BytecodePatchContext.hookBigScreenLayout(reads: List<OverrideRead>) {
    val checks = reads.flatMap(::bigScreenChecks).ifEmpty {
        reads.flatMap { read -> classDefByOrNull(read.method.definingClass)?.let { bigScreenChecksInCallers(read, it) }.orEmpty() }
    }.distinctBy { it.toString() }
    val check = checks.singleOrNull() ?: return skip("expected one big screen check before the override, found ${checks.size}")
    val checkMethod = classDefByOrNull(check.definingClass)?.methods?.singleOrNull {
        it.name == check.name && it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf(ACTIVITY)
    } ?: return skip("$check isn't in this build")
    val gate = bigScreenGate(checkMethod) ?: return skip("$check stores no flag or asks no gate")
    val gateMethod = mutableClassDefBy(gate.definingClass).methods.singleOrNull {
        it.name == gate.name && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Z" &&
            it.parameterTypes.map(CharSequence::toString) == listOf(CONTEXT)
    } ?: return skip("$gate isn't in this build")
    if (gateMethod.implementation?.instructions?.any { ((it as? ReferenceInstruction)?.reference)?.toString() == PHONE_LAYOUT } == true) return
    if (gateMethod.implementation!!.registerCount < 2) return skip("$gate has no local register to borrow")
    gateMethod.answerPhoneLayout()
}

private fun skip(why: String) {
    patchLog.warning("$PATCH: $why, so Phone layout on big screens does nothing on this build")
}

/**
 * [hookBigScreenLayout] for the gate itself: while the switch is on it answers no before it
 * reads a flag; otherwise its own first instruction runs. v0 is a local the gate sets before it
 * reads it, and the label binds to that real first instruction.
 */
internal fun MutableMethod.answerPhoneLayout() {
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $PHONE_LAYOUT
            move-result v0
            if-eqz v0, :facebook
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}
